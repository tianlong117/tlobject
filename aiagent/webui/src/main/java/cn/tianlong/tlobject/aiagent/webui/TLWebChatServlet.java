package cn.tianlong.tlobject.aiagent.webui;

import cn.tianlong.tlobject.base.TLObjectFactory;
import com.google.gson.Gson;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Web UI 静态资源 servlet（普通 HttpServlet，不是框架模块）。
 *
 * <p>TLJettyServer extraServlets 挂载两个路径：</p>
 * <ul>
 *   <li>"/"（默认 servlet 映射）— 根路径重定向到 /webui/chat.html</li>
 *   <li>"/webui/*" — 静态资源，从 classpath /webui/ 读取（内嵌 jar，免外部 resourceBase 配置）</li>
 * </ul>
 *
 * <p>API 请求（/api/*）由 TLServletDispatch + urlMap_config.xml 路由到 TLWebChatModule，
 * 不经过本类。</p>
 *
 * @author tianlong
 * @date 2026/8/17
 */
public class TLWebChatServlet extends HttpServlet {

    private static final Gson GSON = new Gson();

    /** TLJettyServer 优先走 (String, TLObjectFactory) 构造器（同 TLServletDispatch 模式） */
    public TLWebChatServlet(String name, TLObjectFactory modulefactory) {
    }

    public TLWebChatServlet() {
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
            writeJson(resp, 404, json("success", false, "error", "not found: " + path));
        } catch (Exception e) {
            try { writeJson(resp, 500, json("success", false, "error", "服务器内部错误")); } catch (Exception ignored) {}
        }
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
        resp.setHeader("Cache-Control", "no-cache");   // 前端迭代期禁止缓存，刷新即得最新代码
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

    // ======================== 工具 ========================

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
}
