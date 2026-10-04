package cn.tianlong.tlobject.aiagent.browser;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Browser 自动化 Skill（Java 版壳，进程内 Playwright Java 引擎）。
 * 函数名: browser
 *
 * <p>与 Python 版 {@code TLBrowserSkill} 的关系：**薄壳 + 引擎**两层中的壳。
 * 参数名、parameterSchema、skillInput 解析（含旧式 arguments 字符串）、错误文案风格
 * 与 Python 版逐字对齐；浏览器逻辑全部在 {@link JavaBrowserEngine}（JSON 契约与
 * browser_agent.py 逐字一致，含 maxTextChars 截断——壳不再截断第二次）。
 *
 * <p>切换方法：demo 配置里把 {@code sameClassAs="browserSkill"} 改成 "browserJavaSkill"
 * （参数同名共用），回退改回来。两版工具名恒为 "browser"。
 *
 * <p>线程模型：工具在 ThreadTask 线程池上并行执行，本壳只做参数搬运；引擎自持单线程
 * worker 独占 Playwright（构造引擎是廉价的：仅起一个守护单线程 + 注册 ShutdownHook，
 * Playwright/driver 在首次 execute 才真正启动）。
 *
 * <p>生命周期：懒建引擎（每 skill 实例一个）→ destroy 兜底 shutdown（幂等）；
 * 引擎自身有 ShutdownHook，是进程退出时的保证路径（技能是私有子模块，框架 destroy
 * 不一定会被调——见 destroy 注释）。
 *
 * <p>超时：maxExecutionTime（秒，默认 60）→ Future.get 的毫秒；<=0 按本仓库惯例是
 * "不限时"，翻译成极大值（Future.get(0) 会立刻超时）；cdp 形态冷路径最坏 ≈ 探测 2s +
 * 自动拉起轮询 20s + 握手 10s + navigate 15s + 截图 15s ≈ 60-65s > 默认 60s，
 * 故 cdp 形态下限抬到 90s。串行队列深度（并发工具调用排队）需配置方自行覆盖。
 *
 * 创建日期：2026/10/3
 * 作者:tianlong
 */
public class TLBrowserJavaSkill extends TLBaseSkill {

    private int maxExecutionTime = 60;
    private int idleTimeoutSeconds = 300;
    private int maxTextChars = 30000;

    // ======================== 浏览器形态（①有头 / ②持久化 / ③CDP接管） ========================
    /** ①有头开关（仅 ephemeral/persistent 生效） */
    private boolean headless = true;
    /**
     * ②持久化 profile 目录；"" = 每次全新浏览器。
     * 相对路径按 **JVM 启动目录** 解析（与 ./data/traces 等运行数据惯例一致）。
     */
    private String userDataDir = "data/browser_profile";
    /** ③CDP 端点（非空即 attach 模式），如 http://127.0.0.1:9222 */
    private String cdpEndpoint = "";
    /** ③端点不通时自动拉起系统浏览器（只对专用 profile） */
    private boolean cdpAutoLaunch = false;
    /** ③专用 profile 目录（自动拉起与人工启动共用；兼标签页状态文件宿主） */
    private String cdpProfileDir = "data/browser_agent_profile";
    /** ③系统浏览器路径（空 = 探测 Chrome → Edge）（仅 cdpAutoLaunch 自动拉起时生效） */
    private String browserExe = "";

    /** 生效形态：cdp | persistent | ephemeral（cdpEndpoint > userDataDir > 全空） */
    private String mode = "ephemeral";
    /** 生效的绝对路径（首次启动时打日志；配置值与生效值不一致是排障头号原因） */
    private String resolvedUserDataDir = "";
    private String resolvedCdpProfileDir = "";
    private String resolvedBrowserExe = "";

    /** cdp 形态超时下限：冷路径 60-65s > 默认 60s（探测+拉起轮询+握手+navigate+截图） */
    private static final long CDP_MIN_TIMEOUT_MS = 90_000L;
    /** maxExecutionTime<=0（不限时）的翻译值：Future.get(0,ms) 会立刻超时，必须给极大值 */
    private static final long UNLIMITED_TIMEOUT_MS = Long.MAX_VALUE / 2;

    /**
     * 输出序列化用 Gson：**关 HTML 转义**。默认转义会改写 base64 填充位的 `=`
     * （连同 &amp;、&lt;、&gt;、' 四个字符），破坏两个下游消费方：webui 截图卡正则
     * （app.js 从 JSON 里提 screenshot_base64）与上下文层的 base64 占位剥离
     * （TLAiContext QUOTED_B64 正则）——约 2/3 的截图填充位含 `=`，不关转义则
     * 双双失效。同时这也抹平了与 Python 版 json.dumps 的最后一个序列化差异。
     */
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    /** 懒建：每 skill 实例一个引擎（首次 execute 时创建） */
    private volatile JavaBrowserEngine engine;
    private final Object engineLock = new Object();
    /** destroy 已置位：堵住 destroy 与首次 execute 的竞态（否则锁窗口内建出的引擎/driver 泄漏到进程退出） */
    private volatile boolean destroyed = false;

    public TLBrowserJavaSkill() { super(); }
    public TLBrowserJavaSkill(String name) { super(name); }
    public TLBrowserJavaSkill(String name, TLObjectFactory factory) { super(name, factory); }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null) {
            if (params.get("maxExecutionTime") != null) {
                try { maxExecutionTime = Integer.parseInt(params.get("maxExecutionTime")); }
                catch (NumberFormatException e) {
                    putLog("Browser(java) param maxExecutionTime parse failed, using default "
                            + maxExecutionTime, LogLevel.DEBUG);
                }
            }
            if (params.get("idleTimeoutSeconds") != null) {
                try { idleTimeoutSeconds = Integer.parseInt(params.get("idleTimeoutSeconds")); }
                catch (NumberFormatException e) {
                    putLog("Browser(java) param idleTimeoutSeconds parse failed, using default "
                            + idleTimeoutSeconds, LogLevel.DEBUG);
                }
            }
            if (params.get("maxTextChars") != null) {
                try { maxTextChars = Integer.parseInt(params.get("maxTextChars")); }
                catch (NumberFormatException e) {
                    putLog("Browser(java) param maxTextChars parse failed, using default "
                            + maxTextChars, LogLevel.DEBUG);
                }
            }
            if (params.get("headless") != null)
                headless = Boolean.parseBoolean(params.get("headless").trim());
            if (params.get("userDataDir") != null)
                userDataDir = params.get("userDataDir").trim();
            if (params.get("cdpEndpoint") != null)
                cdpEndpoint = params.get("cdpEndpoint").trim();
            if (params.get("cdpAutoLaunch") != null)
                cdpAutoLaunch = Boolean.parseBoolean(params.get("cdpAutoLaunch").trim());
            if (params.get("cdpProfileDir") != null)
                cdpProfileDir = params.get("cdpProfileDir").trim();
            if (params.get("browserExe") != null)
                browserExe = params.get("browserExe").trim();
        }

        // 形态优先级：cdp > persistent > ephemeral（与 Python 版逐字一致）
        if (!cdpEndpoint.isEmpty()) {
            mode = "cdp";
            resolvedCdpProfileDir = toAbsolute(cdpProfileDir);
        } else if (!userDataDir.isEmpty()) {
            mode = "persistent";
            resolvedUserDataDir = toAbsolute(userDataDir);
        } else {
            mode = "ephemeral";
        }
        resolvedBrowserExe = browserExe.isEmpty() ? "" : toAbsolute(browserExe);

        if (skillName == null || skillName.isEmpty())
            skillName = "browser";
        if (skillDescription == null || skillDescription.isEmpty())
            skillDescription = "Browser automation via Playwright (persistent browser session). " +
                    "Navigate to URLs, click elements, fill forms, extract page text/links/tables, capture screenshots. " +
                    "IMPORTANT: the browser session persists across calls — after navigate, subsequent click/type/extract " +
                    "act on the SAME page. Use this for ANY task involving websites.";

        // parameterSchema 与 Python 版逐字一致（LLM 侧工具定义两版恒等，切换不改变模型行为）
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
        return this;   // 引擎懒建（首次 execute）：init 无副作用、可重复调用
    }

    @Override
    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        // 兜底清理（幂等）。注意：技能是私有子模块，框架 destroy 不一定会被调
        // （/reload -s 走 getNewModule 换实例、不触发 destroy）——进程退出靠引擎自带的
        // ShutdownHook 保证；此处只覆盖"确实被调"的路径（如工厂 destroyModule）。
        destroyed = true;   // 先置位（锁外）：并发首次 execute 见位即不建引擎
        JavaBrowserEngine e;
        synchronized (engineLock) {
            // 与 ensureEngine 的创建临界区互斥：要么这里看到已建引擎（关掉它），要么 execute
            // 在锁内先看到 destroyed（不建）——锁外裸读会留下"建出来的 driver 活到进程退出"窗口
            e = engine;
        }
        if (e != null) {
            e.shutdown();   // 先清理（幂等）；关闭路径上日志线程池可能已销毁，日志失败不能拦住清理
            try {
                putLog("Browser(java) skill destroy: shutting down engine", LogLevel.DEBUG);
            } catch (Throwable ignored) {
                // 历史教训：关闭钩子里 putLog 触发异常处理再 putLog 会递归 StackOverflow
            }
        }
        return super.destroy(fromWho, msg);
    }

    /** 相对路径按启动目录绝对化（与 ./data/traces 等运行数据惯例一致；同 Python 版）。 */
    private static String toAbsolute(String p) {
        return Paths.get(p).toAbsolutePath().normalize().toString();
    }

    /** 形态描述（排障日志用）。 */
    private String modeDesc() {
        if ("cdp".equals(mode)) return ", endpoint=" + cdpEndpoint + ", profileDir=" + resolvedCdpProfileDir;
        if ("persistent".equals(mode)) return ", userDataDir=" + resolvedUserDataDir;
        return "";
    }

    /** 懒建引擎（每实例一个）；创建即打形态/生效路径日志（配置值与生效值不一致是排障头号原因）。 */
    private JavaBrowserEngine ensureEngine() {
        JavaBrowserEngine e = engine;
        if (e != null) return e;
        synchronized (engineLock) {
            if (engine != null) return engine;
            if (destroyed) return null;   // destroy 已跑过：不再创建（否则引擎/driver 泄漏到进程退出）
            JavaBrowserEngine.Config cfg = new JavaBrowserEngine.Config();
            cfg.headless = headless;
            cfg.userDataDir = resolvedUserDataDir;
            cfg.cdpEndpoint = cdpEndpoint;
            cfg.cdpAutoLaunch = cdpAutoLaunch;
            cfg.cdpProfileDir = resolvedCdpProfileDir;
            cfg.browserExe = resolvedBrowserExe;
            cfg.maxTextChars = maxTextChars;
            cfg.idleTimeoutSeconds = idleTimeoutSeconds;
            JavaBrowserEngine eng = new JavaBrowserEngine(cfg);
            engine = eng;
            putLog("Browser(java) mode=" + mode + modeDesc()
                    + ("cdp".equals(mode) ? "" : ", headless=" + headless)
                    + ", maxTextChars=" + maxTextChars
                    + (resolvedBrowserExe.isEmpty() ? "" : ", browserExe=" + resolvedBrowserExe)
                    + ", timeoutMs=" + timeoutFor(eng.currentMode()), LogLevel.DEBUG);
            return eng;
        }
    }

    /**
     * maxExecutionTime（秒）→ 超时毫秒：
     * - <=0 = 不限时（本仓库惯例）→ 极大值（Future.get(0,ms) 会立刻超时，不能直接换算）
     * - cdp 形态下限 90s：冷路径 = 探测 2s + 拉起轮询 20s + 握手 10s + navigate 15s + 截图 15s ≈ 60-65s
     * 注：引擎内部动作串行排队，队列深度（并发工具调用）需配置方用 maxExecutionTime 自行覆盖。
     *
     * <p>mode 必须传引擎的 {@link JavaBrowserEngine#currentMode()}（首次 execute 时定型的快照），
     * 不能读壳的 mode 字段——后者随 setParam/reloadConfig 重算，运行时清掉 cdpEndpoint 时
     * 壳已降到 60s 而引擎仍是 cdp，冷路径会假失败。
     */
    private long timeoutFor(String mode) {
        long t = maxExecutionTime <= 0 ? UNLIMITED_TIMEOUT_MS : maxExecutionTime * 1000L;
        if ("cdp".equals(mode) && t < CDP_MIN_TIMEOUT_MS) t = CDP_MIN_TIMEOUT_MS;
        return t;
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

        try {
            // ensureEngine 必须在 try 内：手接线 classpath（bat）漏 Playwright jar 时，
            // `new JavaBrowserEngine(cfg)` 抛的是 NoClassDefFoundError（LinkageError），
            // 框架工具执行器不兜 Error——放 try 外工具结果会凭空消失（LLM 零反馈）。
            JavaBrowserEngine eng = ensureEngine();
            if (eng == null) {
                // destroy 已置位（竞态）：不建引擎直接回失败，避免 driver 泄漏到进程退出
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "{\"ok\":false,\"error\":\"browser engine is shut down\"}");
            }
            Map<String, Object> result = eng.execute(input, timeoutFor(eng.currentMode()));
            Object okParam = result.get("ok");
            boolean ok = okParam == null || Boolean.parseBoolean(String.valueOf(okParam));
            // 正文截断由引擎按 Python 版契约做（含说明文案），壳不再截断第二次
            return createMsg().setParam(RESULT, ok)
                    .setParam(AI_P_SKILLOUTPUT, gson.toJson(result));
        } catch (Exception e) {
            // 引擎内部已兜住所有动作异常；这里是防御（如 ensureEngine 建引擎失败）
            putLog("Browser(java) action failed: " + e, LogLevel.WARN);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Browser action failed: " + e);
        } catch (LinkageError e) {
            // 覆盖 ensureEngine 的引擎构造：手接线 classpath（bat）缺 Playwright jar 时
            // 抛 NoClassDefFoundError；框架工具执行器不兜 LinkageError——不接住工具
            // 结果会凭空消失（LLM 收不到任何反馈）。守卫覆盖范围由下方"漏 jar"负向
            // 对照保证：靠的就是 ensureEngine 调用在 try 内。
            putLog("Browser(java) action failed: " + e, LogLevel.WARN);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("error", "Browser action failed (" + e
                    + "); check that the runtime classpath includes the Playwright jars");
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, gson.toJson(err));
        }
    }

    /** 解析旧式 arguments 字符串：支持 "--action navigate --url X" 与裸词 "navigate X"（同 Python 版）。 */
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
