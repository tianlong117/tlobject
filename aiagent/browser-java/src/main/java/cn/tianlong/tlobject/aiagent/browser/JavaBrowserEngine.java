package cn.tianlong.tlobject.aiagent.browser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitUntilState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程内 Playwright 浏览器引擎：三形态（ephemeral/persistent/cdp）+ 6 action。
 *
 * <p>单线程 worker 独占 Playwright：工具在 ThreadTask 线程池上并行执行，而 Playwright 非线程安全
 * （对象有线程亲和），因此 Playwright 实例与所有 Page/Context 只在 {@code java-browser-engine}
 * 单线程上创建与使用（这同时天然实现 Python 版"动作串行 + _action_lock"的语义）。
 *
 * <p>不依赖框架、不用 putLog——可独立 main 直测；清理只靠 Runtime.addShutdownHook
 * （技能是私有子模块，框架 destroy 不会被调）。
 *
 * <p>JSON 契约与 Python 版 browser_agent.py 逐字一致（字段名/顺序/截断文案），
 * 供 TLBrowserJavaSkill 直接原样序列化返回（webui 从 screenshot_base64 正则提图）。
 *
 * <p>形态判定：cdpEndpoint 非空 → cdp；否则 userDataDir 非空 → persistent；否则 ephemeral。
 */
public class JavaBrowserEngine {

    /** 引擎配置（与 Python 版 CLI 参数一一对应；XML 参数名同名）。 */
    public static class Config {
        /** 无头开关（仅 ephemeral/persistent 生效，cdp 形态由真实浏览器决定） */
        public boolean headless = true;
        /** 持久化 profile 目录；"" = 每次全新浏览器（ephemeral） */
        public String userDataDir = "";
        /** CDP 端点（非空即接管模式，优先级最高），如 http://127.0.0.1:9222 */
        public String cdpEndpoint = "";
        /** CDP 专用 profile 目录（自动拉起与人工启动共用；兼标签页状态文件宿主） */
        public String cdpProfileDir = "";
        /** 系统浏览器路径，"" = 探测 Chrome → Edge（仅 cdpAutoLaunch 自动拉起时生效） */
        public String browserExe = "";
        /** 端点不通时自动拉起系统浏览器（只对本机端点 + 专用 profile） */
        public boolean cdpAutoLaunch = false;
        /** 页面正文(text)返回最大字符数，默认 30000；0/负 = 不限 */
        public int maxTextChars = 30000;
        /** 空闲回收秒数，默认 300；0 = 不回收 */
        public int idleTimeoutSeconds = 300;
    }

    private static final Set<String> ACTIONS =
            new HashSet<>(Arrays.asList("navigate", "click", "type", "screenshot", "extract", "scroll"));
    /** 整页截图高度上限：超限的无限滚动页面截视口，避免输出巨图（对齐 Python 版） */
    private static final int FULLPAGE_MAX_HEIGHT = 12000;
    /** page 级默认超时（与 Python 版一致） */
    private static final double DEFAULT_PAGE_TIMEOUT_MS = 15000;
    /** click/fill 的显式超时（超时才回退 text= 选择器，与 Python 版一致） */
    private static final double ACTION_TIMEOUT_MS = 5000;
    /** 链接数上限（对齐 Python 版） */
    private static final int MAX_LINKS = 50;
    /** 表格数上限（对齐 Python 版） */
    private static final int MAX_TABLES = 10;
    /** click 后等 UI 反应（对齐 Python 版） */
    private static final int CLICK_SETTLE_MS = 500;
    /** scroll 后等渲染（对齐 Python 版） */
    private static final int SCROLL_SETTLE_MS = 300;
    /** 表格文本截断上限（对齐 Python 版） */
    private static final int TABLE_TEXT_MAX_CHARS = 2000;
    /** cdpAutoLaunch 后轮询调试端口就绪的总时长（对齐 Python 版） */
    private static final int CDP_LAUNCH_POLL_MS = 20000;
    /** 调试端口轮询间隔（对齐 Python 版） */
    private static final int CDP_POLL_INTERVAL_MS = 500;
    /** CDP 握手超时：/json/version 已应答但 WebSocket 握手卡住时不吃满默认 30s（对齐 Python 版） */
    private static final int CDP_HANDSHAKE_TIMEOUT_MS = 10000;
    /** 拉起浏览器时保留的 cmd 输出行数上限（失败诊断用） */
    private static final int LAUNCH_OUTPUT_MAX_LINES = 20;
    /** shutdown 等待 worker 收尾/驱动退出的秒数 */
    private static final int SHUTDOWN_WAIT_SECONDS = 5;

    private final Config cfg;
    private final String mode;              // ephemeral | persistent | cdp
    /** 所有 Playwright 操作（含关闭/回收）都在这个单线程上排队；守护线程不阻止 JVM 退出 */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "java-browser-engine");
        t.setDaemon(true);
        return t;
    });
    private final Object lock = new Object();

    // ---- Playwright 句柄：只在 worker 线程创建/使用；仅做跨线程 null 读，声明 volatile 保证可见 ----
    private volatile Playwright playwright;
    private volatile Browser browser;
    private volatile BrowserContext ctx;
    private volatile Page page;

    /** 最近一次调用时间戳（空闲回收依据）：execute 在调用线程入口盖章，worker 在任务结束时再盖一次 */
    private volatile long lastUsed = System.currentTimeMillis();
    private volatile boolean idleKillerStarted = false;
    /** 幂等关闭标记（shutdown hook + 壳 destroy 双重触发） */
    private volatile boolean shutdown = false;
    /** 上次调用超时遗留：下次调用前先清干净重建（对齐 Python 版"失败即回收，下次自愈"） */
    private volatile boolean stale = false;
    /** 直连 HttpClient（绕系统代理探测本地 CDP 端点），worker 线程懒建 */
    private HttpClient probeClient;

    public JavaBrowserEngine(Config cfg) {
        this.cfg = cfg == null ? new Config() : cfg;
        if (!this.cfg.cdpEndpoint.isEmpty()) mode = "cdp";
        else if (!this.cfg.userDataDir.isEmpty()) mode = "persistent";
        else mode = "ephemeral";
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "java-browser-shutdown"));
    }

    /** 生效形态：ephemeral | persistent | cdp（日志/诊断） */
    public String currentMode() {
        return mode;
    }

    /**
     * 壳入口：提交到 worker 线程执行；超时即返回失败（已启动的任务超时才标记下次调用重建）。
     * 工具调用可能来自多个线程 → Playwright 操作在这里天然串行排队。
     */
    public Map<String, Object> execute(Map<String, Object> input, long timeoutMs) {
        lastUsed = System.currentTimeMillis();   // 排队中的调用也算"刚用过"（任务结束时还会再盖一次）
        String action = str(input == null ? null : input.get("action"));
        AtomicBoolean started = new AtomicBoolean(false);
        try {
            Future<Map<String, Object>> f = worker.submit(() -> {
                started.set(true);
                try {
                    return doExecute(input, false);
                } finally {
                    // 任务结束再盖章：动作耗时超过 idleTimeout 时，完成瞬间不被误判空闲而回收
                    lastUsed = System.currentTimeMillis();
                }
            });
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 只有真跑起来的任务才需要清理；纯排队超时（还没开始）不能毒化引擎
            if (started.get()) forceCloseQuietly();
            return err("browser action timeout (" + timeoutMs + "ms): " + action);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return err("browser action interrupted: " + action);
        } catch (ExecutionException e) {
            return err(e.getCause() == null ? e : e.getCause());
        } catch (RejectedExecutionException e) {
            return err("browser engine is shut down");
        }
    }

    /** 超时清理：标记 stale（下次调用先关闭重建）+ 排队补一次 closeBrowser（清掉卡死的句柄）。 */
    private void forceCloseQuietly() {
        stale = true;
        try {
            worker.submit(this::closeBrowser);
        } catch (RejectedExecutionException ignored) {
            // worker 已停：无需补清
        }
    }

    /** worker 线程上的执行体：动作分派 + 连接已死时本次调用内自愈重试一次。 */
    private Map<String, Object> doExecute(Map<String, Object> input, boolean retried) {
        // 关闭窗口守卫：execute 提交后、worker.shutdown() 前排队进来的任务，不得再拉起浏览器（孤儿 driver）
        if (shutdown) return err("browser engine is shut down");
        Map<String, Object> in = input == null ? Collections.emptyMap() : input;
        String action = str(in.get("action"));
        if (!ACTIONS.contains(action)) return err("unknown action: " + action);
        try {
            if (stale) {        // 上次超时遗留的句柄：先清干净再重建
                stale = false;
                closeBrowser();
            }
            ensureBrowser();
            switch (action) {
                case "navigate":   return navigate(str(in.get("url")));
                case "click":      return click(str(in.get("selector")));
                case "type":       return typeText(str(in.get("selector")), str(in.get("text")));
                case "screenshot": return screenshot();
                case "extract":    return extract(strOr(in.get("what"), "all"));
                case "scroll":     return scroll(strOr(in.get("direction"), "down"), intOr(in.get("amount"), 500));
                default:           return err("unknown action: " + action);
            }
        } catch (Exception e) {
            // 连接已死（人关了浏览器/标签页、浏览器被杀）→ 本次调用内清理重建一次，
            // 对齐 Python 版"首调自愈"（cdp 是"人机同窗"形态，这是常规事件）
            if (!retried && isClosedError(e)) {
                try {
                    closeBrowser();
                    ensureBrowser();
                    return doExecute(in, true);
                } catch (Exception e2) {
                    return err(e2);
                }
            }
            return err(e);
        }
    }

    // ======================== 生命周期（全部在 worker 线程上执行） ========================

    /**
     * 确保浏览器可用；句柄失效/连接已断则按形态重建。
     * cdp 形态每次复用前额外探一次端点：isClosed()/isConnected() 只是本地标志，
     * 浏览器被杀后要等下一次协议往返才更新（对齐 Python 版实测结论）。
     */
    private void ensureBrowser() {
        if (page != null && !page.isClosed()) {
            if (!"cdp".equals(mode)) return;
            if (browser != null && browser.isConnected() && probe(cfg.cdpEndpoint)) return;
        }
        closeBrowser();                                    // 清掉失效句柄（cdp 会连同 Playwright 实例一起断开）
        if (playwright == null) playwright = createPlaywright();
        if ("cdp".equals(mode)) {
            ensureCdpBrowser();
        } else if ("persistent".equals(mode)) {
            Path dir = Paths.get(cfg.userDataDir);
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new RuntimeException("无法创建 userDataDir " + dir + ": " + e.getMessage());
            }
            ctx = playwright.chromium().launchPersistentContext(dir,
                    new BrowserType.LaunchPersistentContextOptions().setHeadless(cfg.headless));
            page = ctx.pages().isEmpty() ? ctx.newPage() : ctx.pages().get(0);
        } else {
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(cfg.headless));
            page = browser.newPage();
        }
        page.setDefaultTimeout(DEFAULT_PAGE_TIMEOUT_MS);
        startIdleKiller();
    }

    /** cdp：探端点（不通且 cdpAutoLaunch → 拉起系统浏览器并轮询 20s）→ connectOverCDP → 认领/新建标签页。 */
    private void ensureCdpBrowser() {
        String ep = cfg.cdpEndpoint;
        if (endpointPort(ep) <= 0)
            throw new RuntimeException("cdp 端点需形如 http://host:port: " + ep);
        if (cfg.cdpProfileDir == null || cfg.cdpProfileDir.isEmpty())
            throw new RuntimeException("cdp 模式必须提供 cdpProfileDir（标签页状态文件宿主目录）");
        if (!probe(ep)) {
            if (!cfg.cdpAutoLaunch)
                throw new RuntimeException("无法连接调试浏览器 " + ep
                        + "（配置 cdpAutoLaunch=true 自动拉起，或手动启动：<浏览器> --remote-debugging-port=<端口> --user-data-dir="
                        + cfg.cdpProfileDir + "）");
            if (!isLocalHost(endpointHost(ep)))
                throw new RuntimeException("cdpAutoLaunch 只支持本机端点（127.0.0.1/localhost）: " + ep);
            String profileDirError = null;
            try {
                Files.createDirectories(Paths.get(cfg.cdpProfileDir));
            } catch (IOException e) {
                // 目录交给浏览器自己创建；失败原因必须随"端口未就绪"错误暴露给调用方
                profileDirError = "创建 cdpProfileDir 失败（" + cfg.cdpProfileDir + "）: " + e.getMessage();
            }
            LaunchResult launch = launchSystemBrowser();
            long deadline = System.currentTimeMillis() + CDP_LAUNCH_POLL_MS;
            while (System.currentTimeMillis() < deadline && !probe(ep)) sleep(CDP_POLL_INTERVAL_MS);
            if (!probe(ep)) {
                List<String> why = new ArrayList<>();
                if (profileDirError != null) why.add(profileDirError);
                if (!launch.output.isEmpty()) why.add("浏览器输出: " + launch.output);
                throw new RuntimeException("已拉起浏览器（" + launch.exe + "）但 "
                        + (CDP_LAUNCH_POLL_MS / 1000) + "s 内调试端口未就绪: " + ep
                        + (why.isEmpty() ? "" : "；" + String.join("；", why)));
            }
        }
        // 显式握手超时：/json/version 已应答但 WebSocket 握手卡住时不吃满默认 30s
        browser = playwright.chromium().connectOverCDP(ep,
                new BrowserType.ConnectOverCDPOptions().setTimeout(CDP_HANDSHAKE_TIMEOUT_MS));
        ctx = browser.contexts().isEmpty() ? browser.newContext() : browser.contexts().get(0);
        page = adoptOrCreatePage();
    }

    /**
     * 认领上次的标签页（targetId 比对）；认不到就新开一个并把 id 写回状态文件。
     * 状态文件与 Python 版同路径同格式（两版可互相认领）。绝不采用、绝不关闭用户其它标签页。
     */
    private Page adoptOrCreatePage() {
        Path stateFile = Paths.get(cfg.cdpProfileDir).resolve(".tlobject_agent_tab.json");
        String targetId = null;
        try {
            if (Files.exists(stateFile)) {
                JsonObject saved = JsonParser.parseString(
                        new String(Files.readAllBytes(stateFile), StandardCharsets.UTF_8)).getAsJsonObject();
                if (saved.has("targetId") && !saved.get("targetId").isJsonNull())
                    targetId = saved.get("targetId").getAsString();
            }
        } catch (Exception ignored) {
            // 状态文件坏/缺失：当作认不到，走新建
        }
        if (targetId != null && !targetId.isEmpty()) {
            for (Page p : ctx.pages()) {
                CDPSession session = null;
                try {
                    session = ctx.newCDPSession(p);
                    JsonObject info = session.send("Target.getTargetInfo");
                    JsonObject ti = info == null ? null : info.getAsJsonObject("targetInfo");
                    if (ti != null && ti.has("targetId") && targetId.equals(ti.get("targetId").getAsString())) {
                        System.out.println("[java-browser] cdp: adopted tab " + targetId);
                        return p;
                    }
                } catch (Exception e) {
                    continue;
                } finally {
                    if (session != null) {
                        try { session.detach(); } catch (Exception ignored) { }
                    }
                }
            }
        }
        Page fresh = ctx.newPage();
        String newTargetId = null;
        CDPSession session = null;
        try {
            session = ctx.newCDPSession(fresh);
            JsonObject info = session.send("Target.getTargetInfo");
            JsonObject ti = info == null ? null : info.getAsJsonObject("targetInfo");
            if (ti != null && ti.has("targetId") && !ti.get("targetId").isJsonNull())
                newTargetId = ti.get("targetId").getAsString();
        } catch (Exception e) {
            System.out.println("[java-browser] cdp: cannot identify new tab: " + e);
            // 拿不到 id 就不写状态文件（下次只是认不回这个标签页，不影响动作）
        } finally {
            if (session != null) {
                try { session.detach(); } catch (Exception ignored) { }
            }
        }
        if (newTargetId != null) {
            System.out.println("[java-browser] cdp: new tab " + newTargetId);
            try {
                if (stateFile.getParent() != null) Files.createDirectories(stateFile.getParent());
                Files.write(stateFile, ("{\"targetId\": \"" + newTargetId + "\"}").getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                System.out.println("[java-browser] cdp state file write failed: " + e);
            }
        }
        return fresh;
    }

    /**
     * 创建 Playwright 实例。Node driver 与 Python 版一样吃 HTTP_PROXY：检测到代理时尝试
     * CreateOptions.setEnv 注入 NO_PROXY（本地端点必须绕开代理，否则本地 CDP 连不通）。
     * setEnv 不可用则打印一行警告后正常创建（降级为文档提示）。
     */
    private Playwright createPlaywright() {
        String proxy = System.getenv("HTTP_PROXY");
        if (proxy == null || proxy.isEmpty()) proxy = System.getenv("http_proxy");
        if (proxy != null && !proxy.isEmpty()) {
            try {
                Map<String, String> env = new HashMap<>(System.getenv());
                List<String> noProxy = new ArrayList<>();
                String cur = env.get("NO_PROXY");
                if (cur == null || cur.isEmpty()) cur = env.get("no_proxy");
                if (cur != null) {
                    for (String part : cur.split(",")) {
                        String s = part.trim();
                        if (!s.isEmpty() && !noProxy.contains(s)) noProxy.add(s);
                    }
                }
                for (String s : new String[]{"127.0.0.1", "localhost"}) {
                    if (!noProxy.contains(s)) noProxy.add(s);
                }
                if ("cdp".equals(mode)) {
                    String host = endpointHost(cfg.cdpEndpoint);
                    if (host != null && !host.isEmpty() && !noProxy.contains(host)) noProxy.add(host);
                }
                String joined = String.join(",", noProxy);
                env.put("NO_PROXY", joined);
                env.put("no_proxy", joined);
                return Playwright.create(new Playwright.CreateOptions().setEnv(env));
            } catch (Throwable t) {
                System.out.println("[java-browser] CreateOptions.setEnv unavailable, "
                        + "continuing without NO_PROXY injection: " + t);
            }
        }
        return Playwright.create();
    }

    /**
     * 关浏览器对象（可复用：下次 ensureBrowser 重建）。
     * cdp 只断连——connectOverCDP 的连接由 Playwright 实例持有，关实例等价 Python 版 _pw.stop()
     * （实测只断连，不动用户浏览器的标签页与进程）；绝不调 browser.close() 关用户浏览器。
     * persistent/ephemeral 本方法不动 Playwright 实例（driver 进程复用，本次调用内重建浏览器更快）；
     * 空闲回收路径额外调 {@link #closePlaywright()} 连 driver 一起释放。
     */
    private void closeBrowser() {
        try {
            if ("cdp".equals(mode)) {
                if (playwright != null) playwright.close();
            } else if ("persistent".equals(mode)) {
                if (ctx != null) ctx.close();
            } else {
                if (page != null) {
                    try { page.close(); } catch (Exception ignored) { }
                }
                if (browser != null) browser.close();
            }
        } catch (Exception e) {
            System.out.println("[java-browser] cleanup error: " + e);
        }
        page = null;
        ctx = null;
        browser = null;
        if ("cdp".equals(mode)) playwright = null;
    }

    /**
     * 空闲回收：15s 轮询，idleTimeoutSeconds>0 且空闲超时 → 回收浏览器与 Playwright 实例（driver 进程一起释放）。
     * 循环不 return——回收后下次调用自动重开，本线程继续负责下一轮回收（对齐 Python 版）。
     */
    private void startIdleKiller() {
        if (idleKillerStarted || cfg.idleTimeoutSeconds <= 0) return;
        synchronized (lock) {
            if (idleKillerStarted) return;
            idleKillerStarted = true;
        }
        Thread killer = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (shutdown) return;
                try {
                    worker.submit(this::recycleIfIdle);
                } catch (RejectedExecutionException e) {
                    return;
                }
            }
        }, "browser-idle-killer");
        killer.setDaemon(true);
        killer.start();
    }

    /** 在 worker 线程上判断并回收（检查与关闭都必须在线程内做）。 */
    private void recycleIfIdle() {
        if (page == null) return;
        long idle = System.currentTimeMillis() - lastUsed;
        if (cfg.idleTimeoutSeconds > 0 && idle > cfg.idleTimeoutSeconds * 1000L) {
            System.out.println("[java-browser] browser idle " + idle / 1000 + "s, recycling");
            closeBrowser();
            closePlaywright();   // 与 Python 版回收语义对齐：浏览器与 driver 一起释放（reload 掉的旧引擎由此自愈）
        }
    }

    /**
     * 关闭 Playwright 实例（销毁 node driver 进程）。worker 线程专属；幂等：已关/已空 → no-op。
     * playwright.close() 等待 driver 退出最长约 30s——空闲路径上可以接受。
     * 关闭后下一次 ensureBrowser 会懒重建（driver 进程复用约定只在单次连续使用内成立）。
     */
    private void closePlaywright() {
        Playwright pw = playwright;
        if (pw == null) return;
        playwright = null;
        try {
            pw.close();
        } catch (Exception e) {
            System.out.println("[java-browser] playwright close error: " + e);
        }
    }

    /** 幂等关闭：closeBrowser + 关 Playwright；随后停掉 worker。可由 ShutdownHook / 壳 destroy 触发。 */
    public void shutdown() {
        synchronized (lock) {
            if (shutdown) return;
            shutdown = true;
        }
        try {
            Future<?> f = worker.submit(() -> {
                closeBrowser();
                Playwright pw = playwright;
                playwright = null;
                if (pw != null) {
                    try { pw.close(); } catch (Exception ignored) { }
                }
            });
            f.get(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);   // 钩子线程内等关闭完成，否则 JVM 可能先杀守护 worker
        } catch (RejectedExecutionException ignored) {
            // worker 已停（重复关闭/从未使用）
        } catch (TimeoutException e) {
            System.out.println("browser engine shutdown: driver did not exit within "
                    + SHUTDOWN_WAIT_SECONDS + "s");
        } catch (Exception ignored) {
        } finally {
            worker.shutdown();
        }
    }

    // ======================== 6 个 action（字段名/顺序与 Python 版逐字对齐） ========================

    private Map<String, Object> navigate(String url) {
        page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        Map<String, Object> m = ok();
        m.put("url", page.url());
        m.put("title", page.title());
        m.put("text", getText());
        m.put("screenshot_base64", shot(false));
        return m;
    }

    private Map<String, Object> click(String selector) {
        try {
            page.click(selector, new Page.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
        } catch (TimeoutError e) {
            // CSS 选择器超时 → 回退按可见文本匹配（Playwright text= 引擎，两版同一引擎）
            page.click("text=" + selector, new Page.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
        }
        page.waitForTimeout(CLICK_SETTLE_MS);   // 等 UI 反应
        Map<String, Object> m = ok();
        m.put("url", page.url());
        m.put("title", page.title());
        m.put("text", getText());
        m.put("screenshot_base64", shot(false));
        return m;
    }

    private Map<String, Object> typeText(String selector, String text) {
        try {
            page.fill(selector, text, new Page.FillOptions().setTimeout(ACTION_TIMEOUT_MS));
        } catch (TimeoutError e) {
            page.click("text=" + selector, new Page.ClickOptions().setTimeout(ACTION_TIMEOUT_MS));
            page.keyboard().type(text);
        }
        Map<String, Object> m = ok();
        m.put("text", getText());
        m.put("screenshot_base64", shot(false));
        return m;
    }

    private Map<String, Object> screenshot() {
        Map<String, Object> m = ok();
        m.put("url", page.url());
        m.put("title", page.title());
        m.put("screenshot_base64", shot(true));
        return m;
    }

    private Map<String, Object> extract(String what) {
        Map<String, Object> m = ok();
        m.put("url", page.url());
        if ("text".equals(what) || "all".equals(what)) {
            m.put("text", getText());
        }
        if ("links".equals(what) || "all".equals(what)) {
            Object links = page.evalOnSelectorAll("a[href]",
                    "els => els.map(e => ({text: e.textContent?.trim(), href: e.href})).filter(l=>l.text)");
            m.put("links", limit(normalizeEval(links), MAX_LINKS));
        }
        if ("tables".equals(what) || "all".equals(what)) {
            Object tables = page.evalOnSelectorAll("table",
                    "els => els.map((t,i) => ({index: i, rows: t.rows.length, text: t.innerText?.substring(0,"
                            + TABLE_TEXT_MAX_CHARS + ")}))");
            m.put("tables", limit(normalizeEval(tables), MAX_TABLES));
        }
        return m;
    }

    private Map<String, Object> scroll(String direction, int amount) {
        int delta = "down".equals(direction) ? amount : -amount;
        page.evaluate("window.scrollBy(0, " + delta + ")");
        page.waitForTimeout(SCROLL_SETTLE_MS);
        Map<String, Object> m = ok();
        m.put("screenshot_base64", shot(false));
        return m;
    }

    /** 页面正文：maxTextChars>0 且超长 → 截断 + 中文说明（文案与 Python 版逐字一致）；0/负 = 全文。 */
    private String getText() {
        try {
            String text = page.innerText("body");
            if (text == null) text = "";
            if (cfg.maxTextChars > 0 && text.length() > cfg.maxTextChars) {
                return text.substring(0, cfg.maxTextChars)
                        + "\n…[页面正文超过 " + cfg.maxTextChars + " 字符已截断；如需完整内容请分段提取]";
            }
            return text;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 截图 base64。fullPage=true（screenshot action）：高度探测 ≤12000 才整页，超限/探测失败回落视口；
     * fullPage=false（navigate/click/type/scroll 附带预览）：直接视口（轻量）。
     */
    private String shot(boolean fullPage) {
        if (fullPage) {
            try {
                Object h = page.evaluate("document.documentElement.scrollHeight || document.body.scrollHeight || 0");
                double height = h instanceof Number ? ((Number) h).doubleValue() : 0;
                if (height <= FULLPAGE_MAX_HEIGHT) {
                    return Base64.getEncoder().encodeToString(
                            page.screenshot(new Page.ScreenshotOptions().setFullPage(true)));
                }
            } catch (Exception ignored) {
                // 高度探测/整页截图失败 → 回落视口截图
            }
        }
        return Base64.getEncoder().encodeToString(
                page.screenshot(new Page.ScreenshotOptions().setFullPage(false)));
    }

    // ======================== 工具方法 ========================

    /** CDP 端点探测：/json/version 200 且含 webSocketDebuggerUrl。必须绕开系统代理（本地端点会被误判不通）。 */
    private boolean probe(String endpoint) {
        try {
            if (probeClient == null) {
                probeClient = HttpClient.newBuilder()
                        .proxy(new DirectProxySelector())
                        .connectTimeout(Duration.ofSeconds(2))
                        .build();
            }
            String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/json/version"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> resp = probeClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return false;
            JsonObject info = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (!info.has("webSocketDebuggerUrl") || info.get("webSocketDebuggerUrl").isJsonNull()) return false;
            return !info.get("webSocketDebuggerUrl").getAsString().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** 直连 ProxySelector：本地 CDP 端点绝不走系统代理。 */
    private static final class DirectProxySelector extends ProxySelector {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // no-op
        }
    }

    /** 系统浏览器探测：显式 browserExe > Chrome > Edge（ProgramFiles、ProgramFiles(x86)、LOCALAPPDATA 顺序）。 */
    private String detectSystemBrowser() {
        if (!isWindows()) return null;
        List<String> paths = new ArrayList<>();
        String pf = System.getenv("ProgramFiles");
        String pf86 = System.getenv("ProgramFiles(x86)");
        String local = System.getenv("LOCALAPPDATA");
        if (pf != null) paths.add(pf + "\\Google\\Chrome\\Application\\chrome.exe");
        if (pf86 != null) paths.add(pf86 + "\\Google\\Chrome\\Application\\chrome.exe");
        if (local != null) paths.add(local + "\\Google\\Chrome\\Application\\chrome.exe");
        if (pf != null) paths.add(pf + "\\Microsoft\\Edge\\Application\\msedge.exe");
        if (pf86 != null) paths.add(pf86 + "\\Microsoft\\Edge\\Application\\msedge.exe");
        if (local != null) paths.add(local + "\\Microsoft\\Edge\\Application\\msedge.exe");
        for (String p : paths) {
            if (Files.exists(Paths.get(p))) return p;
        }
        return null;
    }

    /**
     * 自动拉起系统浏览器（专用 profile + 调试端口）。
     * 用 cmd /c start：cmd 立即退出 → 浏览器 ppid 指向已死的 cmd，不会被 JVM 的 taskkill /T 连坐
     * （设计上比 Python 版的"中间进程转手"更直接）。非 Windows 不拉起。
     */
    private LaunchResult launchSystemBrowser() {
        if (cfg.browserExe != null && !cfg.browserExe.isEmpty() && !Files.exists(Paths.get(cfg.browserExe)))
            throw new RuntimeException("指定的 browserExe 不存在: " + cfg.browserExe);
        String exe = cfg.browserExe != null && !cfg.browserExe.isEmpty() ? cfg.browserExe : detectSystemBrowser();
        if (exe == null) throw new RuntimeException("未找到系统 Chrome/Edge，请用 browserExe 配置指定路径");
        int port = endpointPort(cfg.cdpEndpoint);
        Deque<String> output = new ArrayDeque<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "start", "", exe,
                    "--remote-debugging-port=" + port,
                    "--user-data-dir=" + cfg.cdpProfileDir,
                    "--no-first-run", "--no-default-browser-check");
            pb.redirectErrorStream(true);   // cmd/浏览器报错并入 stdout，拉起失败时才能说清原因
            Process p = pb.start();
            captureLaunchOutput(p, output);
            if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroy();
        } catch (IOException e) {
            throw new RuntimeException("拉起浏览器失败（" + exe + "）: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("拉起浏览器被中断（" + exe + "）");
        }
        return new LaunchResult(exe, drainLaunchOutput(output));
    }

    /** 后台读取 cmd/浏览器输出（有界：只留最后 LAUNCH_OUTPUT_MAX_LINES 行），仅供拉起失败诊断。 */
    private static void captureLaunchOutput(Process p, Deque<String> output) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (output) {
                        output.addLast(line);
                        while (output.size() > LAUNCH_OUTPUT_MAX_LINES) output.removeFirst();
                    }
                }
            } catch (Exception ignored) {
                // 输出仅用于诊断，读取异常不影响拉起判断
            }
        }, "java-browser-launch-log");
        t.setDaemon(true);
        t.start();
    }

    /** 取诊断输出快照（读线程可能仍在追加，加锁避免撕裂）。 */
    private static String drainLaunchOutput(Deque<String> output) {
        synchronized (output) {
            return String.join("\n", output);
        }
    }

    /** 拉起结果：exe 路径 + 捕获输出（失败时拼进异常信息）。 */
    private static final class LaunchResult {
        final String exe;
        final String output;

        LaunchResult(String exe, String output) {
            this.exe = exe;
            this.output = output;
        }
    }

    /**
     * 识别"连接已死"类错误（人关标签页/浏览器被杀）→ 触发本次调用内自愈。
     * 与 Python 版同类的错误文案（目标关闭/浏览器关闭/连接关闭）。
     */
    private static boolean isClosedError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m == null) continue;
            if (m.contains("has been closed") || m.contains("Target closed")
                    || m.contains("Browser has been closed") || m.contains("Connection closed")) {
                return true;
            }
        }
        return false;
    }

    /** eval 结果数字归一化：Playwright 把 JS number 反序列化成 Double（0.0），转成整型让 JSON 与 Python 版一致。 */
    private static Object normalizeEval(Object v) {
        if (v instanceof Double) {
            double d = (Double) v;
            if (!Double.isNaN(d) && !Double.isInfinite(d) && d == Math.rint(d)
                    && Math.abs(d) < 9.007199254740992E15) {
                return (long) d;
            }
            return v;
        }
        if (v instanceof List) {
            List<?> list = (List<?>) v;
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) out.add(normalizeEval(o));
            return out;
        }
        if (v instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                out.put(String.valueOf(e.getKey()), normalizeEval(e.getValue()));
            }
            return out;
        }
        return v;
    }

    private static Object limit(Object v, int n) {
        if (v instanceof List && ((List<?>) v).size() > n) {
            return new ArrayList<>(((List<?>) v).subList(0, n));
        }
        return v;
    }

    private static boolean isLocalHost(String host) {
        return host == null || "127.0.0.1".equals(host) || "localhost".equals(host)
                || "::1".equals(host) || "[::1]".equals(host) || "0:0:0:0:0:0:0:1".equals(host);
    }

    private static int endpointPort(String endpoint) {
        try {
            return URI.create(endpoint).getPort();
        } catch (Exception e) {
            return -1;
        }
    }

    private static String endpointHost(String endpoint) {
        try {
            return URI.create(endpoint).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while waiting for cdp endpoint");
        }
    }

    /** ok 系列用 LinkedHashMap 保字段顺序（ok 恒为第一个字段）。 */
    private static Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", msg);
        return m;
    }

    private static Map<String, Object> err(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) msg = String.valueOf(e);
        return err(msg);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String strOr(Object o, String dflt) {
        String s = str(o);
        return s.isEmpty() ? dflt : s;
    }

    private static int intOr(Object o, int dflt) {
        if (o == null) return dflt;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
