package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Browser 自动化 Skill（keep-alive 常驻模式）。
 * 函数名: browser
 *
 * 与 TLScriptExecutionSkill 单次子进程的区别：
 * - 长驻 python 进程（browser_agent.py --serve）+ 单个常驻 Chromium
 * - 页面状态跨调用保留：navigate 后 extract/click/type 作用于同一页面
 * - 省去每次 1-2s 的浏览器冷启动
 * - 空闲超时自动回收（idleTimeoutSeconds 默认 300s），下次调用自动重新拉起
 * - 进程崩溃/卡死自愈：请求失败即杀掉，下次调用重启
 *
 * 生命周期：首次调用懒启动 → 空闲回收/崩溃重启 → 应用退出(ShutdownHook + destroy)清理。
 *
 * 配置参数（XML params）：
 * - interpreter: 解释器命令，默认 "python"
 * - scriptsDir: 脚本目录，默认 "skills/browser/scripts"（兼容旧名 allowedScriptDir）
 * - port: 常驻进程端口，0=自动选空闲端口（默认）
 * - maxExecutionTime: 单次动作超时秒数，默认 60
 * - idleTimeoutSeconds: 空闲回收秒数，默认 300（0=不回收）
 *
 * 创建日期：2026/8/29
 * 作者:tianlong
 */
public class TLBrowserSkill extends TLBaseSkill {

    private String interpreter = "python";
    private String scriptsDir = "skills/browser/scripts";
    private int port = 0;
    private int maxExecutionTime = 60;
    private int idleTimeoutSeconds = 300;

    private OkHttpClient httpClient;
    /** 短超时 client：health 轮询与 shutdown 用（不能卡住 JVM 退出/启动等待） */
    private OkHttpClient shortClient;
    private final Gson gson = new Gson();

    // ======================== keep-alive 生命周期状态 ========================
    /** 常驻子进程（null=未启动/已回收） */
    private Process browserProcess;
    /** 实际监听端口（port=0 时自动分配） */
    private int actualPort = -1;
    /** 最近一次请求时间戳（空闲回收依据） */
    private volatile long lastUsed;
    /** 生命周期锁：启动/杀死/检查互斥 */
    private final Object lifecycleLock = new Object();
    /** 空闲回收线程只启动一次 */
    private volatile boolean idleKillerStarted;
    /** 子进程 stdout/stderr 排空线程（防止管道缓冲写满阻塞——历史死锁教训） */
    private Thread outputDrainer;
    /** 排空内容环形缓冲（最近 50 行，出错时附到错误信息便于排查） */
    private final java.util.ArrayDeque<String> drainTail = new java.util.ArrayDeque<>();

    public TLBrowserSkill() { super(); }
    public TLBrowserSkill(String name) { super(name); }
    public TLBrowserSkill(String name, TLObjectFactory factory) { super(name, factory); }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null) {
            if (params.get("interpreter") != null)
                interpreter = params.get("interpreter");
            if (params.get("scriptsDir") != null)
                scriptsDir = params.get("scriptsDir");
            else if (params.get("allowedScriptDir") != null)  // 兼容旧配置名
                scriptsDir = params.get("allowedScriptDir");
            if (params.get("port") != null) {
                try { port = Integer.parseInt(params.get("port")); } catch (NumberFormatException ignored) {}
            }
            if (params.get("maxExecutionTime") != null) {
                try { maxExecutionTime = Integer.parseInt(params.get("maxExecutionTime")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("idleTimeoutSeconds") != null) {
                try { idleTimeoutSeconds = Integer.parseInt(params.get("idleTimeoutSeconds")); }
                catch (NumberFormatException ignored) {}
            }
        }

        if (skillName == null || skillName.isEmpty())
            skillName = "browser";
        if (skillDescription == null || skillDescription.isEmpty())
            skillDescription = "Browser automation via Playwright (persistent browser session). " +
                    "Navigate to URLs, click elements, fill forms, extract page text/links/tables, capture screenshots. " +
                    "IMPORTANT: the browser session persists across calls — after navigate, subsequent click/type/extract " +
                    "act on the SAME page. Use this for ANY task involving websites.";

        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();

            Map<String, Object> actionProp = new LinkedHashMap<>();
            actionProp.put("type", "string");
            actionProp.put("enum", new String[]{"navigate", "click", "type", "screenshot", "extract", "scroll"});
            actionProp.put("description", "Action to perform: navigate=open URL, click=click element (CSS selector or visible text), type=fill input field, screenshot=capture current page, extract=read page content, scroll=scroll page");
            actionProp.put("required", true);
            parameterSchema.put("action", actionProp);

            Map<String, Object> urlProp = new LinkedHashMap<>();
            urlProp.put("type", "string");
            urlProp.put("description", "URL to open (required for navigate)");
            parameterSchema.put("url", urlProp);

            Map<String, Object> selectorProp = new LinkedHashMap<>();
            selectorProp.put("type", "string");
            selectorProp.put("description", "Element selector (CSS selector or visible text) for click/type");
            parameterSchema.put("selector", selectorProp);

            Map<String, Object> textProp = new LinkedHashMap<>();
            textProp.put("type", "string");
            textProp.put("description", "Text to type into the element (for type action)");
            parameterSchema.put("text", textProp);

            Map<String, Object> whatProp = new LinkedHashMap<>();
            whatProp.put("type", "string");
            whatProp.put("enum", new String[]{"text", "links", "tables", "all"});
            whatProp.put("description", "What to extract (for extract action, default all)");
            parameterSchema.put("what", whatProp);

            Map<String, Object> directionProp = new LinkedHashMap<>();
            directionProp.put("type", "string");
            directionProp.put("enum", new String[]{"down", "up"});
            directionProp.put("description", "Scroll direction (for scroll action)");
            parameterSchema.put("direction", directionProp);

            Map<String, Object> amountProp = new LinkedHashMap<>();
            amountProp.put("type", "integer");
            amountProp.put("description", "Scroll amount in pixels (for scroll action, default 500)");
            parameterSchema.put("amount", amountProp);
        }
    }

    @Override
    protected TLBaseModule init() {
        if (httpClient != null) return this;  // 幂等：重复 init 不重复注册 ShutdownHook
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(maxExecutionTime, TimeUnit.SECONDS)
                .build();
        shortClient = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build();
        // 应用退出兜底：正常关闭（destroy 消息）未到达时也清理常驻进程
        Runtime.getRuntime().addShutdownHook(new Thread(this::killProcess, "browser-skill-shutdown"));
        return this;
    }

    @Override
    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        killProcess();
        return super.destroy(fromWho, msg);
    }

    /**
     * 解析 scriptsDir 为绝对路径（与 TLScriptExecutionSkill 同逻辑）。
     */
    private String resolveScriptDir() {
        Path p = Paths.get(scriptsDir);
        if (p.isAbsolute()) return p.toString();
        String base = moduleFactory != null ? moduleFactory.getConfigDir() : ".";
        if (base.startsWith("CLASSPATH/"))
            base = base.substring("CLASSPATH/".length());
        else if (base.startsWith("CLASSPATH\\"))
            base = base.substring("CLASSPATH\\".length());
        if (base.startsWith("/") && base.length() > 3 && base.charAt(2) == ':')
            base = base.substring(1);
        base = base.replaceAll("[/\\\\]+", "/");
        return base + scriptsDir;
    }

    // ======================== 生命周期 ========================

    /**
     * 确保常驻进程存活。进程不存在/已死 → 重新拉起；存活则直接复用。
     * 启动后轮询 /health 等待就绪（Chromium 冷启动约 2-5s）。
     */
    private void ensureProcess() throws IOException {
        synchronized (lifecycleLock) {
            if (httpClient == null) init();  // 防御：独立使用路径（如测试）可能未走框架 init
            if (browserProcess != null && browserProcess.isAlive()) return;

            // 清理可能残留的旧状态
            cleanupProcessQuietly();

            // 自动选端口：0=任意空闲端口（避免与其他实例冲突）
            if (port == 0) {
                try (ServerSocket s = new ServerSocket(0)) { actualPort = s.getLocalPort(); }
            } else {
                actualPort = port;
            }

            Path script = Paths.get(resolveScriptDir()).resolve("browser_agent.py");
            if (!Files.exists(script)) {
                throw new IOException("browser_agent.py not found: " + script);
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(interpreter);
            cmd.add(script.toString());
            cmd.add("--serve");
            cmd.add("--port");
            cmd.add(String.valueOf(actualPort));

            putLog("Starting persistent browser: " + String.join(" ", cmd), LogLevel.DEBUG);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            // 排空线程：防管道写满阻塞（历史死锁教训：waitFor 前必须持续读）
            StringBuilder buf = new StringBuilder();
            outputDrainer = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (drainTail) {
                            drainTail.addLast(line);
                            while (drainTail.size() > 50) drainTail.removeFirst();
                        }
                    }
                } catch (IOException ignored) {}
            }, "browser-serve-drain");
            outputDrainer.setDaemon(true);
            outputDrainer.start();

            // 等待 /health 就绪
            long deadline = System.currentTimeMillis() + 30000;
            boolean ready = false;
            while (System.currentTimeMillis() < deadline) {
                if (!p.isAlive()) break;
                try (Response resp = shortClient.newCall(healthRequest()).execute()) {
                    if (resp.code() == 200) { ready = true; break; }
                } catch (IOException ignored) {}
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }

            if (!ready) {
                p.destroyForcibly();
                throw new IOException("Persistent browser failed to start. " + drainTailText());
            }

            browserProcess = p;
            lastUsed = System.currentTimeMillis();
            startIdleKiller();
            putLog("Persistent browser ready on port " + actualPort, LogLevel.DEBUG);
        }
    }

    /** 空闲回收：超过 idleTimeoutSeconds 无调用 → 杀掉释放内存；下次调用自动重启 */
    private void startIdleKiller() {
        if (idleKillerStarted || idleTimeoutSeconds <= 0) return;
        synchronized (lifecycleLock) {
            if (idleKillerStarted) return;
            idleKillerStarted = true;
        }
        Thread killer = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(15000);
                    long idle = System.currentTimeMillis() - lastUsed;
                    if (idleTimeoutSeconds > 0 && idle > idleTimeoutSeconds * 1000L) {
                        putLog("Browser idle " + idle / 1000 + "s, recycling persistent process", LogLevel.DEBUG);
                        killProcess();
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "browser-idle-killer");
        killer.setDaemon(true);
        killer.start();
    }

    /** 杀掉常驻进程（幂等）：先尝试优雅 /shutdown，超时则强杀 */
    private void killProcess() {
        synchronized (lifecycleLock) {
            Process p = browserProcess;
            if (p == null) return;
            browserProcess = null;

            // 优雅退出：POST /shutdown（短超时 client，避免 JVM 退出被卡）
            try {
                Request req = new Request.Builder()
                        .url("http://127.0.0.1:" + actualPort + "/shutdown")
                        .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}"))
                        .build();
                try (Response resp = shortClient.newCall(req).execute()) {
                    // ignore
                }
            } catch (IOException ignored) {}

            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    // destroyForcibly 只杀直接子进程，chromium 孙进程会成孤儿——Windows 上
                    // taskkill /T 连根杀进程树（优雅路径正常时不会走到这）
                    if (!p.waitFor(2, TimeUnit.SECONDS) && System.getProperty("os.name", "").toLowerCase().contains("win")) {
                        try {
                            new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(p.pid()))
                                    .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
                        } catch (IOException ignored) {}
                    }
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }

            if (outputDrainer != null) {
                try { outputDrainer.join(2000); } catch (InterruptedException ignored) {}
                outputDrainer = null;
            }
            putLog("Persistent browser stopped", LogLevel.DEBUG);
        }
    }

    private void cleanupProcessQuietly() {
        try { killProcess(); } catch (Exception ignored) {}
    }

    private Request healthRequest() {
        return new Request.Builder().url("http://127.0.0.1:" + actualPort + "/health").build();
    }

    /** 排空缓冲尾部（排障用） */
    private String drainTailText() {
        synchronized (drainTail) {
            return String.join("\n", drainTail);
        }
    }

    // ======================== 执行 ========================

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        Map<String, Object> input = (Map<String, Object>) msg.getMapParam(AI_P_SKILLINPUT, new LinkedHashMap<>());
        if (input.isEmpty()) {
            input = new LinkedHashMap<>();
            if (msg.containsParam("action")) input.put("action", msg.getStringParam("action", ""));
            if (msg.containsParam("url")) input.put("url", msg.getStringParam("url", ""));
            if (msg.containsParam("selector")) input.put("selector", msg.getStringParam("selector", ""));
            if (msg.containsParam("text")) input.put("text", msg.getStringParam("text", ""));
            if (msg.containsParam("what")) input.put("what", msg.getStringParam("what", ""));
        }

        String action = input.get("action") != null ? String.valueOf(input.get("action")) : "";
        // 兼容旧式自由格式 arguments（如 "navigate https://www.baidu.com" 或 "--action navigate --url ..."）
        if (action.isEmpty() && input.get("arguments") != null) {
            Map<String, Object> parsed = parseArguments(String.valueOf(input.get("arguments")));
            input = parsed;
            action = parsed.get("action") != null ? String.valueOf(parsed.get("action")) : "";
        }

        if (action.isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error: action is required. Valid actions: "
                            + String.join(", ", new String[]{"navigate", "click", "type", "screenshot", "extract", "scroll"}));
        }

        lastUsed = System.currentTimeMillis();
        try {
            ensureProcess();
        } catch (IOException e) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Browser failed to start: " + e.getMessage());
        }

        // 组装动作请求体（只传非空参数）
        JsonObject body = new JsonObject();
        body.addProperty("action", action);
        for (String key : new String[]{"url", "selector", "text", "what", "direction"}) {
            Object v = input.get(key);
            if (v != null && !String.valueOf(v).isEmpty()) body.addProperty(key, String.valueOf(v));
        }
        Object amount = input.get("amount");
        if (amount != null && !String.valueOf(amount).isEmpty()) {
            try { body.addProperty("amount", Integer.parseInt(String.valueOf(amount))); }
            catch (NumberFormatException ignored) {}
        }

        Request req = new Request.Builder()
                .url("http://127.0.0.1:" + actualPort + "/action")
                .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body.toString()))
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            String respBody = resp.body() != null ? resp.body().string() : "";
            if (resp.code() != 200 || respBody.isEmpty()) {
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "Browser action failed (HTTP " + resp.code() + "): " + respBody);
            }
            boolean ok;
            try {
                ok = JsonParser.parseString(respBody).getAsJsonObject().get("ok").getAsBoolean();
            } catch (Exception e) {
                ok = true;
            }
            return createMsg().setParam(RESULT, ok)
                    .setParam(AI_P_SKILLOUTPUT, respBody);
        } catch (IOException e) {
            // 超时/连接失败：常驻进程可能卡死，杀掉让下次调用自愈重启
            putLog("Browser action failed, recycling process: " + e, LogLevel.WARN);
            killProcess();
            String tail = drainTailText();
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Browser action timeout (" + maxExecutionTime + "s) or connection lost: " + e.getMessage()
                            + (tail.isEmpty() ? "" : "\nprocess output tail:\n" + tail));
        }
    }

    /** 解析旧式 arguments 字符串：支持 "--action navigate --url X" 与裸词 "navigate X" */
    private Map<String, Object> parseArguments(String args) {
        Map<String, Object> out = new LinkedHashMap<>();
        String[] toks = args.trim().split("\\s+");
        if (toks.length == 0) return out;
        List<String> positional = new ArrayList<>();
        int i = 0;
        if (!toks[0].startsWith("--")) {
            out.put("action", toks[0]);
            i = 1;
        }
        while (i < toks.length) {
            String t = toks[i];
            if (t.startsWith("--") && t.length() > 2) {
                String k = t.substring(2);
                String v = (i + 1 < toks.length && !toks[i + 1].startsWith("--")) ? toks[++i] : "true";
                out.put(k, v);
            } else {
                positional.add(t);
            }
            i++;
        }
        // 裸位置参数：navigate 场景视为 url
        if (!positional.isEmpty() && !out.containsKey("url") && "navigate".equals(out.get("action"))) {
            out.put("url", positional.get(0));
        }
        return out;
    }
}
