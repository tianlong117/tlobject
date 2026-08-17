package cn.tianlong.tlobject.aiagent.webui;

import cn.tianlong.tlobject.base.TLObjectFactory;
import com.google.gson.Gson;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Web Chat IO 适配层：HTTP ↔ TLWebChatModule 方法调用。
 *
 * <p>挂载（TLJettyServer extraServlets 参数）: "/"、"/api/*"、"/webui/*" 三个路径。
 * 静态页面从 classpath /webui/ 读取（内嵌 jar，免外部 resourceBase 配置）。</p>
 *
 * <p>协议约定：</p>
 * <ul>
 *   <li>GET  /api/session        → {loggedIn, [userId]}</li>
 *   <li>POST /api/login          {userId, password} → session 写入</li>
 *   <li>POST /api/logout</li>
 *   <li>POST /api/command        {action, params} → {success, message, [error], [data]}</li>
 *   <li>POST /api/chat           {message, sessionId, [resume], [reasoningMode]} → {response, tokens...}</li>
 *   <li>POST /api/chatStream     {message, sessionId} → SSE 帧流（data: {json}）</li>
 *   <li>POST /api/stopChat       {sessionId}</li>
 *   <li>GET  /api/events         → SSE 帧流（审批事件推送）</li>
 * </ul>
 *
 * @author tianlong
 * @date 2026/8/17
 */
public class TLWebChatServlet extends HttpServlet {

    private static final Gson GSON = new Gson();
    private static final String MODULE_NAME = "webui";
    private static final String SESSION_USER = "userId";

    private final TLObjectFactory factory;
    private final String moduleName;

    /** TLJettyServer 优先走 (String, TLObjectFactory) 构造器（同 TLServletDispatch 模式） */
    public TLWebChatServlet(String name, TLObjectFactory modulefactory) {
        this.factory = modulefactory;
        this.moduleName = MODULE_NAME;
    }

    public TLWebChatServlet() {
        this.factory = null;
        this.moduleName = MODULE_NAME;
    }

    private TLWebChatModule module() {
        if (factory == null) return null;
        Object m = factory.getModule(moduleName);
        return m instanceof TLWebChatModule ? (TLWebChatModule) m : null;
    }

    // ======================== GET ========================

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String path = req.getRequestURI();
            if (path.equals("/") || path.equals("/webui") || path.equals("/webui/")) {
                resp.sendRedirect("/webui/chat.html");
                return;
            }
            if (path.startsWith("/webui/")) {
                serveStatic(path, resp);
                return;
            }
            if (path.equals("/api/session")) {
                handleSession(req, resp);
                return;
            }
            if (path.equals("/api/events")) {
                String userId = sessionUserId(req);
                if (userId == null) { writeJson(resp, 401, json("success", false, "error", "未登录")); return; }
                handleEvents(req, resp, userId);
                return;
            }
            writeJson(resp, 404, json("success", false, "error", "not found: " + path));
        } catch (Exception e) {
            try { writeJson(resp, 500, json("success", false, "error", "服务器内部错误")); } catch (Exception ignored) {}
        }
    }

    private void handleSession(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String userId = sessionUserId(req);
        Map<String, Object> out = new LinkedHashMap<>();
        if (userId != null) {
            out.put("loggedIn", true);
            out.put("userId", userId);
        } else {
            out.put("loggedIn", false);
        }
        writeJson(resp, 200, out);
    }

    /** 静态资源：classpath /webui/ 下读取 */
    private void serveStatic(String path, HttpServletResponse resp) throws IOException {
        String resource = path.substring("/webui".length());
        if (resource.equals("/")) resource = "/chat.html";
        InputStream in = getClass().getResourceAsStream("/webui" + resource);
        if (in == null) {
            writeJson(resp, 404, json("success", false, "error", "资源不存在: " + resource));
            return;
        }
        resp.setStatus(200);
        String ct = contentType(resource);
        resp.setContentType(ct + ";charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) resp.getOutputStream().write(buf, 0, n);
        } finally {
            in.close();
        }
    }

    private static String contentType(String resource) {
        if (resource.endsWith(".html")) return "text/html";
        if (resource.endsWith(".js")) return "application/javascript";
        if (resource.endsWith(".css")) return "text/css";
        if (resource.endsWith(".json")) return "application/json";
        if (resource.endsWith(".svg")) return "image/svg+xml";
        if (resource.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    /** /api/events SSE 长连接：注册通道 + 心跳 + 阻塞至关闭 */
    private void handleEvents(HttpServletRequest req, HttpServletResponse resp, String userId) throws IOException {
        TLWebChatModule mod = module();
        if (mod == null) { writeJson(resp, 500, json("success", false, "error", "webui 模块未就绪")); return; }
        resp.setStatus(200);
        resp.setContentType("text/event-stream;charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        ServletChannel channel = new ServletChannel(resp.getWriter());
        mod.registerEventsChannel(userId, channel);
        Thread hb = startHeartbeat(channel, "webui-hb-" + userId);
        try {
            channel.awaitClosed();
        } finally {
            mod.unregisterEventsChannel(userId, channel);
            hb.interrupt();
        }
    }

    // ======================== POST ========================

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String path = req.getRequestURI();
            String userId = sessionUserId(req);
            if (path.equals("/api/login")) {
                handleLogin(req, resp);
                return;
            }
            if (userId == null) { writeJson(resp, 401, json("success", false, "error", "未登录")); return; }
            switch (path) {
                case "/api/logout":      handleLogout(req, resp, userId); break;
                case "/api/command":     handleCommand(req, resp, userId); break;
                case "/api/chat":        handleChat(req, resp, userId); break;
                case "/api/chatStream":  handleChatStream(req, resp, userId); break;
                case "/api/stopChat":    handleStopChat(req, resp, userId); break;
                default: writeJson(resp, 404, json("success", false, "error", "not found: " + path));
            }
        } catch (Exception e) {
            try { writeJson(resp, 500, json("success", false, "error", "服务器内部错误")); } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private void handleLogin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = readJson(req);
        String userId = body != null && body.get("userId") != null ? String.valueOf(body.get("userId")) : null;
        String password = body != null && body.get("password") != null ? String.valueOf(body.get("password")) : null;
        TLWebChatModule mod = module();
        if (mod == null) { writeJson(resp, 500, json("success", false, "error", "webui 模块未就绪")); return; }
        Map<String, Object> r = mod.login(userId, password);
        if (Boolean.TRUE.equals(r.get("success"))) {
            req.getSession(true).setAttribute(SESSION_USER, r.get("userId"));
        }
        writeJson(resp, Boolean.TRUE.equals(r.get("success")) ? 200 : 401, r);
    }

    private void handleLogout(HttpServletRequest req, HttpServletResponse resp, String userId) throws IOException {
        TLWebChatModule mod = module();
        if (mod != null) mod.closeEventsChannel(userId);
        if (mod != null) mod.stopChat(userId, "");
        HttpSession s = req.getSession(false);
        if (s != null) s.invalidate();
        writeJson(resp, 200, json("success", true, "message", "已退出"));
    }

    @SuppressWarnings("unchecked")
    private void handleCommand(HttpServletRequest req, HttpServletResponse resp, String userId) throws IOException {
        Map<String, Object> body = readJson(req);
        String action = body != null && body.get("action") != null ? String.valueOf(body.get("action")) : null;
        Map<String, Object> params = body != null && body.get("params") instanceof Map
                ? (Map<String, Object>) body.get("params") : new LinkedHashMap<>();
        TLWebChatModule mod = module();
        if (mod == null) { writeJson(resp, 500, json("success", false, "error", "webui 模块未就绪")); return; }
        writeJson(resp, 200, mod.handleCommand(userId, action, params));
    }

    @SuppressWarnings("unchecked")
    private void handleChat(HttpServletRequest req, HttpServletResponse resp, String userId) throws IOException {
        Map<String, Object> body = readJson(req);
        String message = body != null && body.get("message") != null ? String.valueOf(body.get("message")) : "";
        String sessionId = body != null && body.get("sessionId") != null ? String.valueOf(body.get("sessionId")) : null;
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = "webchat_" + userId + "_" + System.currentTimeMillis();
        }
        boolean resume = body != null && Boolean.TRUE.equals(body.get("resume"));
        String reasoningMode = body != null && body.get("reasoningMode") != null ? String.valueOf(body.get("reasoningMode")) : null;
        TLWebChatModule mod = module();
        if (mod == null) { writeJson(resp, 500, json("success", false, "error", "webui 模块未就绪")); return; }
        Map<String, Object> r = mod.chat(userId, sessionId, message, reasoningMode, resume);
        r.put("sessionId", sessionId);
        writeJson(resp, 200, r);
    }

    @SuppressWarnings("unchecked")
    private void handleChatStream(HttpServletRequest req, HttpServletResponse resp, String userId) throws IOException {
        Map<String, Object> body = readJson(req);
        String message = body != null && body.get("message") != null ? String.valueOf(body.get("message")) : "";
        String sessionId = body != null && body.get("sessionId") != null ? String.valueOf(body.get("sessionId")) : null;
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = "webchat_" + userId + "_" + System.currentTimeMillis();
        }
        String reasoningMode = body != null && body.get("reasoningMode") != null ? String.valueOf(body.get("reasoningMode")) : null;
        TLWebChatModule mod = module();
        if (mod == null) { writeJson(resp, 500, json("success", false, "error", "webui 模块未就绪")); return; }
        resp.setStatus(200);
        resp.setContentType("text/event-stream;charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        ServletChannel channel = new ServletChannel(resp.getWriter());
        Map<String, Object> r = mod.beginChatStream(userId, sessionId, message, reasoningMode, channel);
        if (!Boolean.TRUE.equals(r.get("success"))) {
            channel.close();
            writeJson(resp, 400, r);
            return;
        }
        Thread hb = startHeartbeat(channel, "webui-cs-hb-" + userId);
        try {
            channel.awaitClosed();
        } finally {
            hb.interrupt();
        }
    }

    private void handleStopChat(HttpServletRequest req, HttpServletResponse resp, String userId) throws IOException {
        Map<String, Object> body = readJson(req);
        String sessionId = body != null && body.get("sessionId") != null ? String.valueOf(body.get("sessionId")) : "";
        TLWebChatModule mod = module();
        if (mod == null) { writeJson(resp, 500, json("success", false, "error", "webui 模块未就绪")); return; }
        writeJson(resp, 200, mod.stopChat(userId, sessionId));
    }

    // ======================== 工具 ========================

    private static Thread startHeartbeat(ServletChannel channel, String threadName) {
        Thread hb = new Thread(() -> {
            while (channel.isOpen()) {
                try {
                    Thread.sleep(20000);
                    if (channel.isOpen()) channel.writeHeartbeat();
                } catch (InterruptedException ie) {
                    break;
                } catch (Exception e) {
                    channel.close();
                    break;
                }
            }
        }, threadName);
        hb.setDaemon(true);
        hb.start();
        return hb;
    }

    private String sessionUserId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null ? (String) s.getAttribute(SESSION_USER) : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(HttpServletRequest req) throws IOException {
        req.setCharacterEncoding("UTF-8");
        StringBuilder sb = new StringBuilder();
        String line;
        try (BufferedReader br = req.getReader()) {
            while ((line = br.readLine()) != null) sb.append(line);
        }
        if (sb.length() == 0) return new LinkedHashMap<>();
        try {
            return GSON.fromJson(sb.toString(), Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static Map<String, Object> json(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static void writeJson(HttpServletResponse resp, int status, Object obj) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter w = resp.getWriter();
        w.print(GSON.toJson(obj));
        w.flush();
    }

    /** SSE 通道实现：写 "data: {json}\n\n"，关闭时唤醒等待线程 */
    static class ServletChannel implements TLWebChatModule.TLWebChannel {
        private final PrintWriter writer;
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile boolean open = true;

        ServletChannel(PrintWriter writer) { this.writer = writer; }

        @Override
        public synchronized void write(String data) {
            if (!open) return;
            writer.print("data: " + data + "\n\n");
            writer.flush();
            if (writer.checkError()) { close(); return; }
        }

        /** 心跳帧（前端按 hb 标志忽略） */
        synchronized void writeHeartbeat() {
            if (!open) return;
            writer.print("data: {\"hb\":true}\n\n");
            writer.flush();
            if (writer.checkError()) { close(); return; }
        }

        @Override
        public synchronized void close() {
            if (!open) return;
            open = false;
            latch.countDown();
        }

        @Override
        public boolean isOpen() { return open; }

        void awaitClosed() {
            try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
