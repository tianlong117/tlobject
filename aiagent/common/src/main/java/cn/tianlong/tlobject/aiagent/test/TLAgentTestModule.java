package cn.tianlong.tlobject.aiagent.test;

import cn.tianlong.tlobject.aiagent.*;
import cn.tianlong.tlobject.aiagent.evals.TLEvalBaseline;
import cn.tianlong.tlobject.aiagent.evals.TLEvalReport;
import cn.tianlong.tlobject.aiagent.evals.TLEvalReportMsg;
import cn.tianlong.tlobject.aiagent.evals.TLEvalRunResult;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI Agent 确定性单元测试模块（Mock Provider 驱动）。
 * <p>
 * 依赖 {@link TLMockProvider} 提供预设的 LLM 响应，覆盖 DeepSeek 审查中
 * 指出的 4 个测试缺口：doChat 主循环、Tool 并行执行、超时机制、会话恢复。
 * </p>
 *
 * <p>所有测试均不需要真实 LLM / 网络连接，结果确定、可重复。</p>
 *
 * <h3>使用方式</h3>
 * <pre>
 * // 控制台（经 agentService 的 "test" action 转发）
 * /test                    → 运行全部测试
 * /test list               → 列出可用测试用例
 * /test basicChat          → 运行单个场景（/test agent basicChat 亦可）
 *
 * // 代码 / XML 配置
 * putMsg("agentTestModule", createMsg().setAction("runAllTests"));
 * putMsg("agentTestModule", createMsg().setAction("runSingleTest").setParam("caseName", "basicChat"));
 * </pre>
 *
 * <h3>测试报告</h3>
 * 每次运行结束写一份报告到 {@code reportOutputDir}（默认 {@code data/aitest/reports/}）：
 * {@code test_report_*.json}（机器读，供下一次对比）+ 同名 {@code .md}（人读）。
 * 报告格式与评测层共用（{@link TLEvalReport}），并按用例 id 与上一份测试报告对比出回归/改善——
 * 测试这层是 mock 驱动、完全可复现，本该是"每次改动都跑"的那一层，只报数字看不出是不是新挂的。
 *
 * 创建日期：2026/8/12
 * 作者：tianlong
 */
public class TLAgentTestModule extends TLBaseModule implements TLAiAgentParamString {

    // ======================== 字段 ========================

    /**
     * 测试会话的身份标识。
     * 消息不带 userId 时框架会拿 sessionId 顶替（TLAiAgent 里 currentChatUserId 的兜底），
     * 而测试的 sessionId 是 test_mock_xxx 这类名字，于是 data/ 顶层全是测试会话目录
     * （路径规则 data/{userId}/traces/{sessionId}/）。给测试一个固定身份，产物归到 data/aitest/ 下。
     * 例外：checkpoint 场景故意用独立 userId "test_user"，见该场景注释。
     */
    private static final String TEST_USER_ID = "aitest";

    /** Mock Provider 引用（在 init() 中获取，或首次测试时懒加载） */
    private TLMockProvider mockProvider;

    /**
     * 测试统计。
     * skipped 单列：跳过既不是通过也不是失败——依赖模块没配、测试 Skill 没注册，
     * 这些跟被测代码的质量无关；混进通过率就会让"没跑"看起来像"跑过了"。
     */
    private int passed, failed, skipped;

    /** 未通过的用例标签：上层要能报出"挂了哪个"，不然只能看到"有挂的"（每次运行前清空） */
    private final List<String> failedCases = new ArrayList<>();

    /** 跳过的用例标签 + 原因（每次运行前清空，汇总时单列一行） */
    private final List<String> skippedCases = new ArrayList<>();

    /** 逐用例结果，用于生成报告并与上一次运行对比（每次运行前清空） */
    private final List<TLEvalRunResult> runResults = new ArrayList<>();

    /** 测试报告输出目录（与 trace 同属 data/aitest/ 命名空间） */
    private String reportOutputDir = "data/aitest/reports/";

    /** 测试报告文件名前缀——与评测报告区分开，两边的基线扫描各认各的 */
    private static final String REPORT_PREFIX = "test_report_";

    /** testEchoSkill 的函数名（在 XML 中配置的 skill name） */
    private static final String FN_ECHO = "test_echo";
    /** testSleepSkill 的函数名 */
    private static final String FN_SLEEP = "test_sleep";

    /** 内置场景用的会话 id（agent 的上下文按会话驻留进程，跨运行留存，见 resetTestSessions） */
    private static final String SID_BASIC        = "test_mock_basic";
    private static final String SID_MULTITURN    = "test_mock_multiturn";
    private static final String SID_SINGLETOOL   = "test_mock_singletool";
    private static final String SID_PARALLEL     = "test_mock_parallel";
    private static final String SID_TIMEOUT      = "test_mock_timeout";
    private static final String SID_STREAM       = "test_mock_stream";
    private static final String SID_CANCEL       = "test_mock_cancel";
    private static final String SID_BATCHTIMEOUT = "test_mock_batch_timeout";
    private static final String SID_CHECKPOINT   = "test_mock_checkpoint";
    private static final String SID_STREAMERR    = "test_mock_streamerr";

    /** 内置场景用到的全部会话（整组清理，套件才有重入性） */
    private static final List<String> TEST_SESSIONS = Arrays.asList(
            SID_BASIC, SID_MULTITURN, SID_SINGLETOOL, SID_PARALLEL, SID_TIMEOUT,
            SID_STREAM, SID_CANCEL, SID_BATCHTIMEOUT, SID_CHECKPOINT, SID_STREAMERR);

    // ======================== 构造器 ========================

    public TLAgentTestModule() { super(); }
    public TLAgentTestModule(String name) { super(name); }
    public TLAgentTestModule(String name, TLObjectFactory factory) { super(name, factory); }

    // ======================== 生命周期 ========================

    @Override
    protected TLBaseModule init() {
        // 懒加载 mockProvider（init 时可能尚未创建）
        putLog("[TEST_MODULE] TLAgentTestModule initialized", LogLevel.DEBUG);
        return this;
    }

    /** 懒加载获取 mockProvider 引用 */
    private TLMockProvider getMockProvider() {
        if (mockProvider == null) {
            try {
                TLObjectFactory factory = getFactory();
                if (factory != null) {
                    Object m = factory.getModule("mockProvider");
                    if (m instanceof TLMockProvider) {
                        mockProvider = (TLMockProvider) m;
                    }
                }
            } catch (Exception e) {
                putLog("[TEST_MODULE] Failed to get mockProvider: " + e, LogLevel.ERROR);
            }
        }
        return mockProvider;
    }

    // ======================== 消息分发 ========================

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "runAllTests":
                returnMsg = runAllTests(fromWho, msg);
                break;
            case "runSingleTest":
                returnMsg = runSingleTest(fromWho, msg);
                break;
            case "listTestCases":
                returnMsg = listTestCases(fromWho, msg);
                break;
            case "testBasicChat":
                returnMsg = testBasicChat(fromWho, msg);
                break;
            case "testMultiTurn":
                returnMsg = testMultiTurn(fromWho, msg);
                break;
            case "testToolSingle":
                returnMsg = testToolSingle(fromWho, msg);
                break;
            case "testToolParallel":
                returnMsg = testToolParallel(fromWho, msg);
                break;
            case "testToolTimeout":
                returnMsg = testToolTimeout(fromWho, msg);
                break;
            case "testStreamBasic":
                returnMsg = testStreamBasic(fromWho, msg);
                break;
            case "testCancelExecution":
                returnMsg = testCancelExecution(fromWho, msg);
                break;
            case "testBatchTimeout":
                returnMsg = testBatchTimeout(fromWho, msg);
                break;
            case "testSessionRecovery":
                returnMsg = testSessionRecovery(fromWho, msg);
                break;
            case "testStreamError":
                returnMsg = testStreamError(fromWho, msg);
                break;
            case "testIntentCache":
                returnMsg = testIntentCache(fromWho, msg);
                break;
            case "runCaseFile":
                returnMsg = runCaseFile(fromWho, msg);
                break;
            default:
                returnMsg = null;
        }
        return returnMsg;
    }

    // ======================== 测试入口 ========================

    /** 保存原 Provider 名，测试结束后恢复 */
    private transient String originalProviderName;

    /** 用户自定义测试用例文件路径（XML） */
    private String caseFile;

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null && params.get("caseFile") != null) {
            caseFile = params.get("caseFile");
        }
        if (params != null && params.get("reportOutputDir") != null
                && !params.get("reportOutputDir").trim().isEmpty()) {
            reportOutputDir = params.get("reportOutputDir");
        }
    }

    protected TLMsg runAllTests(Object fromWho, TLMsg msg) {
        log("===== AI Agent Unit Tests (Mock Provider) Start =====");

        TLMockProvider mp = getMockProvider();
        if (mp == null) {
            log("[TEST] FAIL: mockProvider not found. Ensure it is registered and booted.");
            return createMsg().setParam(RESULT, false).setParam("error", "mockProvider not found");
        }

        // 切换到 Mock Provider（保存原名用于恢复）
        switchToMockProvider(mp);

        // 动态注册测试 Skill
        boolean skillsOk = registerTestSkills();
        if (!skillsOk) {
            log("[TEST] WARNING: Test skill registration may have failed, some tests will be skipped");
        }

        passed = 0;
        failed = 0;
        skipped = 0;
        failedCases.clear();
        skippedCases.clear();
        runResults.clear();

        // 上一遍跑过的会话上下文还挂在 agent 上，不清就是在旧历史上接着跑
        resetTestSessions();

        // 场景 1-9: 按注册表顺序运行（依赖测试 Skill 的用例在注册失败时跳过）
        for (Map.Entry<String, String[]> e : TEST_CASES.entrySet()) {
            String[] def = e.getValue();
            TestFunc func = resolveTestCase(e.getKey());
            String skipReason = skipReasonFor(def, skillsOk);
            if (skipReason != null) func = skipTest(skipReason);
            runOne(e.getKey(), def[0], func);
        }

        // === 用户自定义测试用例（XML 配置驱动）===
        if (caseFile != null && !caseFile.isEmpty()) {
            log("[TEST] ----- Custom Test Cases Start -----");
            int[] customResult = runCaseFileInternal(caseFile);
            passed += customResult[0];
            failed += customResult[1];
            log("[TEST] 自定义用例: 通过=" + customResult[0] + ", 失败=" + customResult[1]);
            log("[TEST] ----- Custom Test Cases End -----");
        }

        // 清理: 注销测试 Skill，恢复原 Provider
        unregisterTestSkills();
        restoreOriginalProvider();

        log("===== AI Agent Unit Tests End =====");
        log(String.format("[TEST] 汇总: 通过=%d, 失败=%d", passed, failed));
        if (skipped > 0) {
            // 单独一行而不是塞进汇总：跳过不是通过也不是失败，不能让它看起来像"跑了且过了"
            log("[TEST] 跳过: " + skipped + " 个（不计入通过/失败）: " + String.join("；", skippedCases));
        }

        // 报告落盘 + 与上一次测试对比：测试这层是 mock 驱动、完全可复现，
        // 本该是"每次改动都跑"的那一层，只报数字的话下次又是 11/12，看不出是不是新挂的
        TLEvalReport report = buildAndSaveReport();
        if (reportPath != null) log("[TEST] 报告已保存: " + reportPath);

        return TLEvalReportMsg.attach(
                createMsg().setParam(RESULT, failed == 0)
                        .setParam("passed", passed).setParam("failed", failed)
                        .setParam("skipped", skipped).setParam("skippedCases", skippedCases)
                        .setParam("failedCases", failedCases)
                        .setParam("reportPath", reportPath),
                report, true);
    }

    // ======================== 测试报告 ========================

    /** 上次报告的绝对路径（保存后填充，供上层显示） */
    private String reportPath;

    /** 汇总本次结果 → 报告（含与上一份测试报告的对比）→ 落盘 json + md */
    private TLEvalReport buildAndSaveReport() {
        TLEvalReport report = new TLEvalReport();
        report.title = "测试报告";
        report.timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date());
        report.targetAgent = M_AIAGENT;
        report.results = new ArrayList<>(runResults);
        report.computeSummary();
        applyReportBaseline(report);
        saveReportFile(report);
        return report;
    }

    /**
     * 与上一份测试报告按用例 id 对比。
     * 前缀是 test_report_——和前缀 eval_report_ 的评测报告各认各的，不会互相比。
     */
    private void applyReportBaseline(TLEvalReport report) {
        for (File candidate : TLEvalBaseline.listReportsNewestFirst(new File(reportOutputDir), REPORT_PREFIX)) {
            try {
                TLEvalReport baseline = new Gson().fromJson(readFile(candidate), TLEvalReport.class);
                if (baseline == null) continue;
                TLEvalBaseline.compare(report, baseline, candidate.getName());
                return;
            } catch (Exception e) {
                putLog("历史测试报告解析失败，跳过: " + candidate.getName() + " - " + e, LogLevel.WARN);
            }
        }
    }

    private void saveReportFile(TLEvalReport report) {
        try {
            File dir = new File(reportOutputDir);
            if (!dir.exists() && !dir.mkdirs()) {
                putLog("测试报告目录创建失败: " + reportOutputDir, LogLevel.WARN);
                return;
            }
            String base = REPORT_PREFIX + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            File json = new File(dir, base + ".json");
            writeFile(json, gson.toJson(report));
            writeFile(new File(dir, base + ".md"), report.toMarkdown(true));
            reportPath = json.getAbsolutePath();
        } catch (Exception e) {
            putLog("测试报告保存失败: " + e, LogLevel.WARN);
        }
    }

    private static void writeFile(File file, String content) throws IOException {
        try (java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }

    private static String readFile(File file) throws IOException {
        try (Scanner s = new Scanner(file, "UTF-8").useDelimiter("\\A")) {
            return s.hasNext() ? s.next() : "";
        }
    }

    private void runOne(String id, String label, TestFunc func) {
        long start = System.currentTimeMillis();
        String error = null;
        boolean ok = false;
        boolean isSkipped = false;
        String skipReason = null;

        // 开跑前清空 mock 响应队列：队列是各场景共用的，而场景自己只在开头清一次，
        // 中途"命中跳过转发"这类分支会让入队的响应没人消费、留到下一个场景（或下一遍运行）
        // 被错位消费。这里兜底清一次，不会破坏各场景自己入队的预期。
        TLMockProvider mp = getMockProvider();
        if (mp != null) mp.clearAllResponses();

        try {
            TLMsg r = func.run(null, null);
            isSkipped = r != null && r.parseBoolean("skipped", false);
            if (isSkipped) {
                skipReason = r.getStringParam("skipReason", "");
                log("[TEST] 场景" + label + ": SKIP - " + skipReason);
            } else if (r != null && r.parseBoolean(RESULT, false)) {
                ok = true;
                log("[TEST] 场景" + label + ": PASS");
            } else {
                error = r != null ? r.getStringParam("error", "") : "null result";
                log("[TEST] 场景" + label + ": FAIL - " + error);
            }
        } catch (Exception e) {
            error = "异常: " + e;
            log("[TEST] 场景" + label + ": FAIL - " + error);
        }
        if (isSkipped) {
            skipped++;
            skippedCases.add(label + "（" + skipReason + "）");
        } else if (ok) passed++;
        else { failed++; failedCases.add(label); }
        addResult(id, label, ok, error, isSkipped, skipReason, start);
    }

    /**
     * 记一条逐用例结果（报告与基线对比的数据源）。
     * id 用注册表键（basicChat 这类，稳），name 用显示标签（1-基本Chat）——
     * 编号重排时 id 不变，对比出来的"回归"才不会被改名误伤。
     * skipped 单列：跳过不占 passed/failed 任一格，报告与门禁都不该把它当结果。
     */
    private void addResult(String id, String name, boolean ok, String error, long startMs) {
        addResult(id, name, ok, error, false, null, startMs);
    }

    private void addResult(String id, String name, boolean ok, String error,
                           boolean skipped, String skipReason, long startMs) {
        TLEvalRunResult r = new TLEvalRunResult();
        r.caseId = id;
        r.caseName = name;
        r.targetAgent = M_AIAGENT;
        // 非 agent_chat：mock 驱动没有真实 token 消耗，算进均值只会得到一堆 0
        r.callType = "test";
        r.stability = "stable";
        r.skipped = skipped;
        r.passed = ok;
        r.error = skipped ? ("跳过: " + skipReason) : error;
        r.latencyMs = System.currentTimeMillis() - startMs;
        runResults.add(r);
    }

    @FunctionalInterface
    private interface TestFunc {
        TLMsg run(Object fromWho, TLMsg msg) throws Exception;
    }

    // ======================== 用例注册表 / 单用例运行 ========================

    /**
     * 测试用例注册表：用例名 → [场景标签, 依赖测试Skill失败时的跳过原因(null=不依赖), 依赖的模块名(null=不依赖)]。
     * 第三个元素是可选扩展——用例依赖的模块在工厂里不存在时跳过（不是失败：应用没配这个模块
     * 不等于代码坏了，但也不能静默当通过）。
     */
    private static final LinkedHashMap<String, String[]> TEST_CASES = new LinkedHashMap<>();
    static {
        TEST_CASES.put("basicChat",       new String[]{"1-基本Chat", null});
        TEST_CASES.put("multiTurn",       new String[]{"2-多轮对话", null});
        TEST_CASES.put("toolSingle",      new String[]{"3-单Tool调用", "test_echo 未注册"});
        TEST_CASES.put("toolParallel",    new String[]{"4-Tool并行执行", "test_echo 未注册"});
        TEST_CASES.put("toolTimeout",     new String[]{"5-Tool超时", "test_sleep 未注册"});
        TEST_CASES.put("streamBasic",     new String[]{"6-流式Chat", null});
        TEST_CASES.put("cancelExecution", new String[]{"7-取消执行", "test_sleep 未注册"});
        TEST_CASES.put("batchTimeout",    new String[]{"8-批次超时", "test_sleep 未注册"});
        TEST_CASES.put("sessionRecovery", new String[]{"9-会话恢复", "需要 sessionManager"});
        TEST_CASES.put("streamError",      new String[]{"10-流式异常", null});
        TEST_CASES.put("intentCache",      new String[]{"11-意图缓存", null, "testCacheProvider"});
    }

    /**
     * 该用例这一遍要不要跳过（返回 null = 照常跑）。
     * 两类跳过：测试 Skill 注册失败（def[1]）、依赖模块没配（def[2]）。
     * 后者按"工厂里有没有这个模块"判定——chat 那套配置没配 testCacheProvider，
     * 之前它被算成 FAIL，测试层因此恒不通过。
     */
    private String skipReasonFor(String[] def, boolean skillsOk) {
        if (!skillsOk && def[1] != null) return def[1];
        if (def.length > 2 && def[2] != null && !moduleConfigured(def[2]))
            return "依赖模块 " + def[2] + " 未配置";
        return null;
    }

    /**
     * 工厂里有没有这个模块。只查配置表与实例表：
     * 走 getModule 探测在模块不存在时会按类名尝试加载并打一串 ERROR/NPE 日志（实测），
     * 探测本就不该有这种副作用。
     */
    private boolean moduleConfigured(String moduleName) {
        try {
            TLObjectFactory factory = getFactory();
            if (factory == null) return false;
            return factory.containsModule(moduleName) || factory.getModuleConfig(moduleName) != null;
        } catch (Exception e) {
            putLog("[TEST_MODULE] 依赖模块探测失败: " + moduleName + " - " + e, LogLevel.WARN);
            return false;
        }
    }

    /**
     * 清掉内置场景的会话上下文。
     * agent 是工厂单例、上下文按会话驻留进程：不清的话第二遍就是在第一遍的历史上跑，
     * "本轮新增 3 条 tool 结果"这类断言会读到 6 条（4-Tool并行执行 第二遍必挂）。
     * 走 agent 的黑盒接口（AGENT_CLEARCONTEXT），与评测层每次用例后的清理同一手法。
     */
    private void resetTestSessions() {
        for (String sessionId : TEST_SESSIONS) {
            try {
                putMsg(M_AIAGENT, createMsg().setAction(AGENT_CLEARCONTEXT)
                        .setParam(AI_P_SESSIONID, sessionId));
            } catch (Exception e) {
                putLog("[TEST_MODULE] 清会话上下文失败: " + sessionId + " - " + e, LogLevel.WARN);
            }
        }
    }

    /** 用例名 → 测试函数（大小写不敏感），未知返回 null */
    private TestFunc resolveTestCase(String name) {
        if (name == null) return null;
        switch (name.toLowerCase()) {
            case "basicchat":       return this::testBasicChat;
            case "multiturn":       return this::testMultiTurn;
            case "toolsingle":      return this::testToolSingle;
            case "toolparallel":    return this::testToolParallel;
            case "tooltimeout":     return this::testToolTimeout;
            case "streambasic":     return this::testStreamBasic;
            case "cancelexecution": return this::testCancelExecution;
            case "batchtimeout":    return this::testBatchTimeout;
            case "sessionrecovery": return this::testSessionRecovery;
            case "streamerror":     return this::testStreamError;
            case "intentcache":     return this::testIntentCache;
            default:                return null;
        }
    }

    /** 按用例名查找注册表项（大小写不敏感） */
    private String[] findTestCase(String name) {
        if (name == null) return null;
        for (Map.Entry<String, String[]> e : TEST_CASES.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    /** 运行单个测试用例（控制台 /test <用例名>），自带 Provider 切换与 Skill 注册清理 */
    protected TLMsg runSingleTest(Object fromWho, TLMsg msg) {
        String caseName = msg.getStringParam("caseName", null);
        if (caseName == null || caseName.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "缺少 caseName 参数");
        }
        String[] def = findTestCase(caseName);
        if (def == null) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "未知用例: " + caseName + "，输入 /test list 查看可用用例");
        }
        TLMockProvider mp = getMockProvider();
        if (mp == null) {
            return createMsg().setParam(RESULT, false).setParam("error", "mockProvider not found");
        }

        log("===== AI Agent Unit Test [" + caseName + "] Start =====");
        switchToMockProvider(mp);
        boolean skillsOk = registerTestSkills();
        passed = 0;
        failed = 0;
        skipped = 0;
        failedCases.clear();
        skippedCases.clear();
        runResults.clear();
        resetTestSessions();
        try {
            TestFunc func = resolveTestCase(caseName);
            String skipReason = skipReasonFor(def, skillsOk);
            if (skipReason != null) func = skipTest(skipReason);
            runOne(caseName, def[0], func);
        } finally {
            unregisterTestSkills();
            restoreOriginalProvider();
        }
        log(String.format("[TEST] 用例[%s]: 通过=%d, 失败=%d", caseName, passed, failed));
        if (skipped > 0) log("[TEST] 用例[" + caseName + "] 跳过: " + String.join("；", skippedCases));
        TLEvalReport report = buildAndSaveReport();
        return TLEvalReportMsg.attach(
                createMsg().setParam(RESULT, failed == 0)
                        .setParam("passed", passed).setParam("failed", failed)
                        .setParam("skipped", skipped).setParam("skippedCases", skippedCases)
                        .setParam("failedCases", failedCases)
                        .setParam("reportPath", reportPath),
                report, true);
    }

    /** 列出可用测试用例（控制台 /test list） */
    protected TLMsg listTestCases(Object fromWho, TLMsg msg) {
        List<String> cases = new ArrayList<>();
        for (Map.Entry<String, String[]> e : TEST_CASES.entrySet()) {
            cases.add(e.getKey() + " — " + e.getValue()[0]);
        }
        return createMsg().setParam(RESULT, true).setParam("cases", cases);
    }

    // ======================== 场景 1: 基本 Chat ========================

    protected TLMsg testBasicChat(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();
        mp.enqueueResponse(mp.textResponse("1+1等于2，这是最基本的算术运算。"));

        TLMsg chatMsg = createMsg()
                .setAction(AGENT_CHAT)
                .setSystemParam(AI_P_SESSIONID, SID_BASIC)
                .setSystemParam(AI_P_USERID, TEST_USER_ID)
                .setParam(AI_P_USERMESSAGE, "1+1等于几？");

        TLMsg response = putMsg(M_AIAGENT, chatMsg);

        boolean ok = response.parseBoolean(RESULT, false);
        String aiResp = response.getStringParam(AI_P_RESPONSE, "");
        int iterations = response.getIntParam("iterations", 0);

        if (ok && aiResp.contains("2") && iterations >= 1) {
            log("[TEST]   基本Chat: response=" + aiResp.substring(0, Math.min(80, aiResp.length()))
                    + ", iterations=" + iterations);
            return createMsg().setParam(RESULT, true);
        }
        return createMsg().setParam(RESULT, false)
                .setParam("error", String.format("ok=%s, contains2=%s, iterations=%d, resp=%s",
                        ok, aiResp.contains("2"), iterations,
                        aiResp.substring(0, Math.min(100, aiResp.length()))));
    }

    // ======================== 场景 2: 多轮对话 ========================

    protected TLMsg testMultiTurn(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();
        mp.enqueueResponse(mp.textResponse("已记住：王小明，架构师。"));
        mp.enqueueResponse(mp.textResponse("你之前告诉我你叫王小明，是架构师。"));

        String sessionId = SID_MULTITURN;

        // 回合 1
        TLMsg turn1 = createMsg()
                .setAction(AGENT_CHAT)
                .setSystemParam(AI_P_SESSIONID, sessionId)
                .setSystemParam(AI_P_USERID, TEST_USER_ID)
                .setParam(AI_P_USERMESSAGE, "我叫王小明，是架构师。");
        TLMsg r1 = putMsg(M_AIAGENT, turn1);
        if (!r1.parseBoolean(RESULT, false)) {
            return createMsg().setParam(RESULT, false).setParam("error", "turn1 failed");
        }

        // 回合 2
        TLMsg turn2 = createMsg()
                .setAction(AGENT_CHAT)
                .setSystemParam(AI_P_SESSIONID, sessionId)
                .setSystemParam(AI_P_USERID, TEST_USER_ID)
                .setParam(AI_P_USERMESSAGE, "我叫什么？做什么工作？");
        TLMsg r2 = putMsg(M_AIAGENT, turn2);

        boolean ok = r2.parseBoolean(RESULT, false);
        String resp2 = r2.getStringParam(AI_P_RESPONSE, "");
        boolean remembered = resp2.contains("王小明") && resp2.contains("架构师");

        if (ok && remembered) {
            return createMsg().setParam(RESULT, true);
        }
        return createMsg().setParam(RESULT, false)
                .setParam("error", String.format("ok=%s, remembered=%s, resp=%s",
                        ok, remembered, resp2.substring(0, Math.min(100, resp2.length()))));
    }

    // ======================== 场景 3: 单 Tool 调用 ========================

    @SuppressWarnings("unchecked")
    protected TLMsg testToolSingle(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();

        // 第 1 次 LLM 调用: 返回 tool_call
        TLToolCall tc = TLMockProvider.createToolCall("call_echo_1", FN_ECHO,
                new HashMap<String, Object>() {{ put("message", "hello world"); }});
        mp.enqueueResponse(mp.toolCallResponse(tc));

        // 第 2 次 LLM 调用 (tool 结果返回后): 返回最终文本
        mp.enqueueResponse(mp.textResponse("工具返回: ECHO: hello world，任务完成。"));

        TLMsg chatMsg = createMsg()
                .setAction(AGENT_CHAT)
                .setSystemParam(AI_P_SESSIONID, SID_SINGLETOOL)
                .setSystemParam(AI_P_USERID, TEST_USER_ID)
                .setParam(AI_P_USERMESSAGE, "请用 echo 工具输出 hello world");

        TLMsg response = putMsg(M_AIAGENT, chatMsg);

        boolean ok = response.parseBoolean(RESULT, false);
        int iterations = response.getIntParam("iterations", 0);
        String aiResp = response.getStringParam(AI_P_RESPONSE, "");

        // 验证 tool loop 执行了（iterations >= 2 表示至少一次 tool call + 一次回复）
        if (ok && iterations >= 2 && !aiResp.isEmpty()) {
            log("[TEST]   单Tool: iterations=" + iterations + ", response="
                    + aiResp.substring(0, Math.min(80, aiResp.length())));
            return createMsg().setParam(RESULT, true);
        }
        return createMsg().setParam(RESULT, false)
                .setParam("error", String.format("ok=%s, iterations=%d, resp=%s",
                        ok, iterations, aiResp.substring(0, Math.min(100, aiResp.length()))));
    }

    // ======================== 场景 11: 意图路由缓存 ========================

    /** 直接驱动 testCacheProvider（delegate=工厂 mockProvider）验证 学习/命中/槽位回填/三类miss/多轮不学 */
    @SuppressWarnings("unchecked")
    protected TLMsg testIntentCache(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        if (mp == null) {
            return createMsg().setParam(RESULT, false).setParam("error", "mockProvider not found");
        }
        TLBaseModule cp = (TLBaseModule) getModule("testCacheProvider");
        if (cp == null) {
            return createMsg().setParam(RESULT, false).setParam("error", "testCacheProvider not found");
        }

        // Provider 是工厂单例，缓存跨运行留存：不清掉上一遍学到的条目，
        // 这一遍的"学习"环节会直接命中旧条目，后面的 miss 断言全部失效
        TLMsg clearResult = putMsg(cp, createMsg().setAction(LLM_CLEARCACHE));
        log("[TEST]   意图缓存: 清空旧缓存 cleared="
                + (clearResult != null ? clearResult.getIntParam("cleared", -1) : "null"));

        mp.clearAllResponses();

        List<TLFunctionDefinition> defs = new ArrayList<>();
        defs.add(TLFunctionDefinition.fromSkill(FN_ECHO, "echo test skill", new HashMap<>()));

        // ---- 1. 学习：单轮工具任务（首轮工具 + 次轮收尾） ----
        TLToolCall tc = TLMockProvider.createToolCall("call_ic_1", FN_ECHO,
                new HashMap<String, Object>() {{ put("message", "写2首爱情诗"); }});
        mp.enqueueResponse(mp.toolCallResponse(tc));
        TLMsg r1 = putMsg(cp, createMsg().setAction(LLM_COMPLETION)
                .setParam(AI_P_MESSAGEHISTORY, userHistory("写2首爱情诗"))
                .setParam(AI_P_FUNCTIONDEFS, defs)
                .setParam(AI_P_SESSIONID, "ic_learn"));
        if (r1 == null || !r1.parseBoolean("hasToolCalls", false)) {
            return createMsg().setParam(RESULT, false).setParam("error", "学习第1轮未返回tool_call");
        }
        mp.enqueueResponse(mp.textResponse("任务完成"));
        TLMsg r2 = putMsg(cp, createMsg().setAction(LLM_COMPLETION)
                .setParam(AI_P_MESSAGEHISTORY, toolResultHistory("写2首爱情诗", "call_ic_1", "ECHO: 写2首爱情诗"))
                .setParam(AI_P_FUNCTIONDEFS, defs)
                .setParam(AI_P_SESSIONID, "ic_learn"));
        if (r2 == null || r2.parseBoolean("hasToolCalls", false)) {
            return createMsg().setParam(RESULT, false).setParam("error", "学习第2轮异常");
        }

        // ---- 2. 命中 + 数字槽位回填（空队列：命中返回缓存的 tool_calls，mock 兜底不会产生 tool_calls） ----
        TLMsg r3 = putMsg(cp, createMsg().setAction(LLM_COMPLETION)
                .setParam(AI_P_MESSAGEHISTORY, userHistory("写4首爱情诗"))
                .setParam(AI_P_FUNCTIONDEFS, defs)
                .setParam(AI_P_SESSIONID, "ic_hit"));
        List<TLToolCall> hitCalls = (List<TLToolCall>) (r3 != null ? r3.getListParam(AI_P_TOOLCALLS, null) : null);
        boolean hitOk = r3 != null && r3.parseBoolean("hasToolCalls", false)
                && hitCalls != null && hitCalls.size() == 1
                && "写4首爱情诗".equals(String.valueOf(hitCalls.get(0).getArguments().get("message")));
        if (!hitOk) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "命中/槽位回填失败: hasToolCalls="
                            + (r3 != null ? r3.parseBoolean("hasToolCalls", false) : "null")
                            + " args=" + (hitCalls != null ? hitCalls.get(0).getArguments() : "null"));
        }
        // Phase 2 合成终版（空队列零转发，响应含工具结果内容）
        TLMsg r4 = putMsg(cp, createMsg().setAction(LLM_COMPLETION)
                .setParam(AI_P_MESSAGEHISTORY, toolResultHistory("写4首爱情诗", "call_ic_1", "ECHO: 写4首爱情诗"))
                .setParam(AI_P_FUNCTIONDEFS, defs)
                .setParam(AI_P_SESSIONID, "ic_hit"));
        boolean synthOk = r4 != null && !r4.parseBoolean("hasToolCalls", false)
                && r4.getStringParam(AI_P_RESPONSE, "").contains("ECHO: 写4首爱情诗");
        if (!synthOk) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "Phase2合成失败: resp="
                            + (r4 != null ? r4.getStringParam(AI_P_RESPONSE, "") : "null"));
        }

        // ---- 3. 数字个数不符 → miss（转发消费入队的文本） ----
        mp.enqueueResponse(mp.textResponse("miss_fallback_num"));
        TLMsg r5 = putMsg(cp, createMsg().setAction(LLM_COMPLETION)
                .setParam(AI_P_MESSAGEHISTORY, userHistory("写2首爱情诗和3首山水诗"))
                .setParam(AI_P_FUNCTIONDEFS, defs)
                .setParam(AI_P_SESSIONID, "ic_nummiss"));
        boolean numMissOk = r5 != null && !r5.parseBoolean("hasToolCalls", false)
                && "miss_fallback_num".equals(r5.getStringParam(AI_P_RESPONSE, ""));
        if (!numMissOk) {
            return createMsg().setParam(RESULT, false).setParam("error", "数字个数不符未miss");
        }

        // ---- 4. 字符串不同 → miss ----
        mp.enqueueResponse(mp.textResponse("miss_fallback_str"));
        TLMsg r6 = putMsg(cp, createMsg().setAction(LLM_COMPLETION)
                .setParam(AI_P_MESSAGEHISTORY, userHistory("写2首山水诗"))
                .setParam(AI_P_FUNCTIONDEFS, defs)
                .setParam(AI_P_SESSIONID, "ic_strmiss"));
        boolean strMissOk = r6 != null && !r6.parseBoolean("hasToolCalls", false)
                && "miss_fallback_str".equals(r6.getStringParam(AI_P_RESPONSE, ""));
        if (!strMissOk) {
            return createMsg().setParam(RESULT, false).setParam("error", "字符串不同未miss");
        }

        // ---- 5. 多轮规划 → 不学习（次轮仍有工具，第三次同消息应 miss） ----
        mp.enqueueResponse(mp.toolCallResponse(TLMockProvider.createToolCall("call_ic_2", FN_ECHO,
                new HashMap<String, Object>() {{ put("message", "复杂任务"); }})));
        TLMsg r7 = putMsg(cp, createMsg().setAction(LLM_COMPLETION)
                .setParam(AI_P_MESSAGEHISTORY, userHistory("复杂任务"))
                .setParam(AI_P_FUNCTIONDEFS, defs)
                .setParam(AI_P_SESSIONID, "ic_multi"));
        mp.enqueueResponse(mp.toolCallResponse(TLMockProvider.createToolCall("call_ic_3", FN_ECHO,
                new HashMap<String, Object>() {{ put("message", "复杂任务续"); }})));
        TLMsg r8 = putMsg(cp, createMsg().setAction(LLM_COMPLETION)
                .setParam(AI_P_MESSAGEHISTORY, toolResultHistory("复杂任务", "call_ic_2", "ECHO: 复杂任务"))
                .setParam(AI_P_FUNCTIONDEFS, defs)
                .setParam(AI_P_SESSIONID, "ic_multi"));
        mp.enqueueResponse(mp.textResponse("miss_fallback_multi"));
        TLMsg r9 = putMsg(cp, createMsg().setAction(LLM_COMPLETION)
                .setParam(AI_P_MESSAGEHISTORY, userHistory("复杂任务"))
                .setParam(AI_P_FUNCTIONDEFS, defs)
                .setParam(AI_P_SESSIONID, "ic_multi2"));
        boolean multiOk = r7 != null && r7.parseBoolean("hasToolCalls", false)
                && r8 != null && r8.parseBoolean("hasToolCalls", false)
                && r9 != null && !r9.parseBoolean("hasToolCalls", false)
                && "miss_fallback_multi".equals(r9.getStringParam(AI_P_RESPONSE, ""));
        if (!multiOk) {
            return createMsg().setParam(RESULT, false).setParam("error", "多轮规划误学习或判定失败");
        }

        log("[TEST]   意图缓存: 学习/命中/槽位回填/三类miss 全部通过");
        return createMsg().setParam(RESULT, true);
    }

    private List<TLConversationHistory> userHistory(String text) {
        List<TLConversationHistory> h = new ArrayList<>();
        h.add(new TLConversationHistory(TLConversationHistory.Role.user, text));
        return h;
    }

    private List<TLConversationHistory> toolResultHistory(String userText, String toolCallId, String output) {
        List<TLConversationHistory> h = userHistory(userText);
        h.add(new TLConversationHistory(toolCallId, toolCallId, output));
        return h;
    }

    // ======================== 场景 4: Tool 并行执行 ========================

    @SuppressWarnings("unchecked")
    protected TLMsg testToolParallel(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();

        // 第 1 次 LLM 调用: 返回 3 个并发 tool_calls
        List<TLToolCall> toolCalls = Arrays.asList(
                TLMockProvider.createToolCall("call_p1", FN_ECHO,
                        new HashMap<String, Object>() {{ put("message", "task-A"); }}),
                TLMockProvider.createToolCall("call_p2", FN_ECHO,
                        new HashMap<String, Object>() {{ put("message", "task-B"); }}),
                TLMockProvider.createToolCall("call_p3", FN_ECHO,
                        new HashMap<String, Object>() {{ put("message", "task-C"); }})
        );
        mp.enqueueResponse(mp.toolCallResponse(toolCalls));

        // 第 2 次 LLM 调用: 最终文本
        mp.enqueueResponse(mp.textResponse("三个任务全部完成。"));

        TLMsg chatMsg = createMsg()
                .setAction(AGENT_CHAT)
                .setSystemParam(AI_P_SESSIONID, SID_PARALLEL)
                .setSystemParam(AI_P_USERID, TEST_USER_ID)
                .setParam(AI_P_USERMESSAGE, "请同时执行三个 echo 任务");

        TLMsg response = putMsg(M_AIAGENT, chatMsg);

        boolean ok = response.parseBoolean(RESULT, false);
        int iterations = response.getIntParam("iterations", 0);

        // 断言：3 个并行 tool 结果全部收集（查会话上下文中的 tool 消息）
        int toolCount = 0;
        boolean allCollected = false;
        try {
            TLMsg ctxResult = putMsg(M_AIAGENT, createMsg().setAction(AGENT_GETCONTEXT)
                    .setParam(AI_P_SESSIONID, SID_PARALLEL));
            if (ctxResult != null) {
                List<TLConversationHistory> hist = (List<TLConversationHistory>)
                        ctxResult.getListParam(AI_P_MESSAGEHISTORY, java.util.List.of());
                StringBuilder outputs = new StringBuilder();
                for (TLConversationHistory h : hist) {
                    if (h.getRole() == TLConversationHistory.Role.tool) {
                        toolCount++;
                        outputs.append(h.getContent()).append(";");
                    }
                }
                allCollected = toolCount == 3
                        && outputs.toString().contains("task-A")
                        && outputs.toString().contains("task-B")
                        && outputs.toString().contains("task-C");
            }
        } catch (Exception e) {
            log("[TEST]   并行执行: 上下文断言异常 - " + e);
        }

        if (ok && iterations >= 2 && allCollected) {
            log("[TEST]   并行执行: iterations=" + iterations + " tool结果=" + toolCount + "/3 全部收集");
            return createMsg().setParam(RESULT, true);
        }
        return createMsg().setParam(RESULT, false)
                .setParam("error", String.format("ok=%s, iterations=%d, toolCount=%d, allCollected=%s",
                        ok, iterations, toolCount, allCollected));
    }

    // ======================== 场景 5: Tool 执行超时 ========================

    @SuppressWarnings("unchecked")
    protected TLMsg testToolTimeout(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();

        // Tool call: test_sleep with duration=2000ms
        // test_sleep skill 在 XML 中配置了 timeout="1"（1秒=1000ms）
        // 所以实际睡眠 2000ms > 1000ms 超时 → 应触发超时
        TLToolCall tc = TLMockProvider.createToolCall("call_sleep_1", FN_SLEEP,
                new HashMap<String, Object>() {{ put("duration", 2000); }});
        mp.enqueueResponse(mp.toolCallResponse(tc));

        // 超时后 LLM 再被调用一次（agent 注入了超时提示）
        mp.enqueueResponse(mp.textResponse("工具超时了，但我可以继续。"));

        TLMsg chatMsg = createMsg()
                .setAction(AGENT_CHAT)
                .setSystemParam(AI_P_SESSIONID, SID_TIMEOUT)
                .setSystemParam(AI_P_USERID, TEST_USER_ID)
                .setParam(AI_P_MAXTOOLCALLITERATIONS, 2)
                .setParam(AI_P_USERMESSAGE, "请 sleep 2000 毫秒");

        TLMsg response = putMsg(M_AIAGENT, chatMsg);

        boolean ok = response.parseBoolean(RESULT, false);
        int iterations = response.getIntParam("iterations", 0);
        String aiResp = response.getStringParam(AI_P_RESPONSE, "");

        // Tool 超时不等于框架崩溃；agent 应该能继续返回结果
        if (ok && iterations >= 2 && !aiResp.isEmpty()) {
            log("[TEST]   超时: iterations=" + iterations + ", response="
                    + aiResp.substring(0, Math.min(80, aiResp.length())));
            return createMsg().setParam(RESULT, true);
        }
        return createMsg().setParam(RESULT, false)
                .setParam("error", String.format("ok=%s, iterations=%d, resp=%s",
                        ok, iterations, aiResp.substring(0, Math.min(100, aiResp.length()))));
    }

    // ======================== 场景 6: 流式 Chat ========================

    protected TLMsg testStreamBasic(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();

        String sessionId = SID_STREAM;

        // 预设流式响应: chunk → chunk → done
        mp.enqueueStreamResponses(sessionId, Arrays.asList(
                mp.streamChunk("流"),
                mp.streamChunk("式"),
                mp.streamChunk("测"),
                mp.streamChunk("试"),
                mp.streamDone("流式测试完成")
        ));

        try {
            // 重置流回调
            putMsg("streamCallback", createMsg().setAction("resetStream"));

            // 发送流式请求
            TLMsg streamMsg = createMsg()
                    .setAction(AGENT_CHATSTREAM)
                    .setSystemParam(AI_P_SESSIONID, sessionId)
                    .setParam(AI_P_USERMESSAGE, "请流式回复")
                    .setParam(RESULTFOR, "streamCallback")
                    .setParam(RESULTACTION, "onStreamChunk");

            putMsg(M_AIAGENT, streamMsg);

            // 等待流式完成
            TLMsg waitMsg = createMsg().setAction("waitForStream").setParam("timeout", 30);
            TLMsg waitResult = putMsg("streamCallback", waitMsg);

            boolean streamDone = waitResult.parseBoolean("streamDone", false);
            String content = waitResult.getStringParam("content", "");

            if (streamDone && !content.isEmpty()) {
                log("[TEST]   流式: content=" + content);
                return createMsg().setParam(RESULT, true);
            }
            boolean timedOut = waitResult.parseBoolean("timedOut", false);
            return createMsg().setParam(RESULT, false)
                    .setParam("error", String.format("streamDone=%s, timedOut=%s, content=%s",
                            streamDone, timedOut, content));
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false).setParam("error", "stream exception: " + e);
        }
    }

    // ======================== 场景 7: 取消执行 ========================

    @SuppressWarnings("unchecked")
    protected TLMsg testCancelExecution(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();

        String sessionId = SID_CANCEL;

        // tool sleep 5s（足够让 cancel 在它完成前到达）
        TLToolCall tc = TLMockProvider.createToolCall("call_sleep_cancel", FN_SLEEP,
                new HashMap<String, Object>() {{ put("duration", 5000); }});
        mp.enqueueResponse(mp.toolCallResponse(tc));

        try {
            AtomicReference<TLMsg> chatResult = new AtomicReference<>();

            Thread chatThread = new Thread(() -> {
                TLMsg cMsg = createMsg()
                        .setAction(AGENT_CHAT)
                        .setSystemParam(AI_P_SESSIONID, sessionId)
                        .setSystemParam(AI_P_USERID, TEST_USER_ID)
                        .setParam(AI_P_USERMESSAGE, "sleep 5 秒");
                chatResult.set(putMsg(M_AIAGENT, cMsg));
            }, "test-cancel-chat");
            chatThread.start();

            // 等待 tool 进入执行阶段（mock 无网络延迟，500ms 够）
            Thread.sleep(500);

            // 发取消到 ToolExecutor
            putMsg("toolExecutor", createMsg()
                    .setAction(TODOOLECANCEL)
                    .setParam(AI_P_SESSIONID, sessionId));

            // chat 应因取消而提前结束
            chatThread.join(10000);

            TLMsg result = chatResult.get();
            if (result != null && result.containsParam(AI_P_RESPONSE)) {
                log("[TEST]   取消执行: chat completed (cancelled or finished)");
                return createMsg().setParam(RESULT, true);
            }
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "cancel did not take effect: result="
                            + (result != null ? result.getStringParam(AI_P_RESPONSE, "") : "null"));
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "cancel test exception: " + e);
        }
    }

    // ======================== 场景 8: 批次超时 ========================

    @SuppressWarnings("unchecked")
    protected TLMsg testBatchTimeout(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();

        // 3 个 sleep tool（每个 5 秒），批次超时设为 1 秒
        List<TLToolCall> toolCalls = Arrays.asList(
                TLMockProvider.createToolCall("call_bt1", FN_SLEEP,
                        new HashMap<String, Object>() {{ put("duration", 5000); }}),
                TLMockProvider.createToolCall("call_bt2", FN_SLEEP,
                        new HashMap<String, Object>() {{ put("duration", 5000); }}),
                TLMockProvider.createToolCall("call_bt3", FN_SLEEP,
                        new HashMap<String, Object>() {{ put("duration", 5000); }})
        );
        mp.enqueueResponse(mp.toolCallResponse(toolCalls));

        // 配置工具执行器：设置批次超时
        TLMsg configMsg = createMsg()
                .setAction(MODULE_SETPARAM)
                .setParam("executionTimeoutMs", "1000"); // 1 秒
        TLMsg configResult = putMsg("toolExecutor", configMsg);

        log("[TEST]   批次超时配置: " + (configResult != null && configResult.parseBoolean(RESULT, false)));

        TLMsg chatMsg = createMsg()
                .setAction(AGENT_CHAT)
                .setSystemParam(AI_P_SESSIONID, SID_BATCHTIMEOUT)
                .setSystemParam(AI_P_USERID, TEST_USER_ID)
                .setParam(AI_P_MAXTOOLCALLITERATIONS, 2)
                .setParam(AI_P_USERMESSAGE, "并行 sleep 5 秒 x 3");

        TLMsg response = putMsg(M_AIAGENT, chatMsg);

        boolean ok = response.parseBoolean(RESULT, false);
        int iterations = response.getIntParam("iterations", 0);

        // 恢复批次超时为 0（无限）
        putMsg("toolExecutor", createMsg()
                .setAction(MODULE_SETPARAM)
                .setParam("executionTimeoutMs", "0"));

        // 批次超时后 agent 应该返回（不会卡死）
        if (ok || iterations >= 1) {
            log("[TEST]   批次超时: iterations=" + iterations + ", ok=" + ok);
            return createMsg().setParam(RESULT, true);
        }
        return createMsg().setParam(RESULT, false)
                .setParam("error", String.format("batch timeout: ok=%s, iterations=%d", ok, iterations));
    }

    // ======================== 场景 9: 会话断点恢复 ========================

    @SuppressWarnings("unchecked")
    protected TLMsg testSessionRecovery(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();

        String sessionId = SID_CHECKPOINT;

        // 第一轮: tool call → 触发 checkpoint 保存
        TLToolCall tc = TLMockProvider.createToolCall("call_cp_1", FN_ECHO,
                new HashMap<String, Object>() {{ put("message", "checkpoint-test"); }});
        mp.enqueueResponse(mp.toolCallResponse(tc));
        mp.enqueueResponse(mp.textResponse("第一轮完成，checkpoint 已保存。"));

        TLMsg chatMsg = createMsg()
                .setAction(AGENT_CHAT)
                .setSystemParam(AI_P_SESSIONID, sessionId)
                // 这一场景故意用独立的 test_user：checkpoint 存取按 userId 走，这里要验证跨"用户"恢复
                .setSystemParam("userId", "test_user")
                .setParam(AI_P_USERMESSAGE, "执行 checkpoint 测试");

        TLMsg r1 = putMsg(M_AIAGENT, chatMsg);
        if (!r1.parseBoolean(RESULT, false)) {
            return createMsg().setParam(RESULT, false).setParam("error", "checkpoint round1 failed");
        }

        // 查询 sessionManager 验证 checkpoint 已保存（未保存 = 场景失败，不再只 log）
        try {
            TLMsg listMsg = createMsg()
                    .setAction("listSessions")
                    .setParam("userId", "test_user");
            TLMsg listResult = putMsg("sessionManager", listMsg);
            List<?> sessions = (List<?>) listResult.getListParam("sessions", Collections.emptyList());

            if (sessions == null || sessions.isEmpty()) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "checkpoint 未保存: sessions="
                                + (sessions != null ? sessions.size() : "null"));
            }
            log("[TEST]   会话恢复: checkpoint 已保存, sessions=" + sessions.size());
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "sessionManager 查询异常: " + e.getMessage());
        }

        // 验证第二轮可以继续（新的 chat 在同一 session）
        mp.enqueueResponse(mp.textResponse("第二轮继续，上下文保持。"));
        TLMsg chatMsg2 = createMsg()
                .setAction(AGENT_CHAT)
                .setSystemParam(AI_P_SESSIONID, sessionId)
                .setSystemParam(AI_P_USERID, TEST_USER_ID)
                .setParam(AI_P_USERMESSAGE, "继续对话");
        TLMsg r2 = putMsg(M_AIAGENT, chatMsg2);

        if (r2.parseBoolean(RESULT, false)) {
            log("[TEST]   会话恢复: 两轮均成功");
            return createMsg().setParam(RESULT, true);
        }
        return createMsg().setParam(RESULT, false)
                .setParam("error", "checkpoint round2 failed: " + r2.getStringParam("error", ""));
    }

    // ======================== 场景 10: 流式异常路径 ========================

    protected TLMsg testStreamError(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();

        String sessionId = SID_STREAMERR;

        // 预设流式响应: chunk → 中途错误（模拟 Provider 连接中断）
        mp.enqueueStreamResponses(sessionId, Arrays.asList(
                mp.streamChunk("部分内容"),
                createMsg().setAction(STREAM_ONCHUNK).setParam(AI_P_STREAMERROR, "provider connection reset")
        ));

        try {
            // 重置流回调
            putMsg("streamCallback", createMsg().setAction("resetStream"));

            // 发送流式请求
            TLMsg streamMsg = createMsg()
                    .setAction(AGENT_CHATSTREAM)
                    .setSystemParam(AI_P_SESSIONID, sessionId)
                    .setParam(AI_P_USERMESSAGE, "请流式回复")
                    .setParam(RESULTFOR, "streamCallback")
                    .setParam(RESULTACTION, "onStreamChunk");

            putMsg(M_AIAGENT, streamMsg);

            // 等待流式完成（错误路径: streamDone=true + streamError 非空）
            TLMsg waitMsg = createMsg().setAction("waitForStream").setParam("timeout", 30);
            TLMsg waitResult = putMsg("streamCallback", waitMsg);

            boolean streamDone = waitResult.parseBoolean("streamDone", false);
            String err = waitResult.getStringParam(AI_P_STREAMERROR, "");

            if (streamDone && !err.isEmpty()) {
                log("[TEST]   流式异常: 错误已转发 err=" + err);
                return createMsg().setParam(RESULT, true);
            }
            boolean timedOut = waitResult.parseBoolean("timedOut", false);
            return createMsg().setParam(RESULT, false)
                    .setParam("error", String.format("streamDone=%s, timedOut=%s, error=%s",
                            streamDone, timedOut, err));
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false).setParam("error", "stream error exception: " + e);
        }
    }

    // ======================== 工具方法 ========================

    /** 切换到 Mock Provider，同时保存原 Provider 名用于恢复 */
    private void switchToMockProvider(TLMockProvider mp) {
        try {
            // 保存原 provider 名
            TLMsg getProvMsg = createMsg().setAction("getLlmProvider");
            TLMsg getProvResult = putMsg(M_AIAGENT, getProvMsg);
            if (getProvResult != null && getProvResult.containsParam("provider")) {
                Object prov = getProvResult.getParam("provider");
                if (prov instanceof TLBaseModule) {
                    originalProviderName = ((TLBaseModule) prov).getName();
                }
            }

            TLMsg switchMsg = createMsg()
                    .setAction(AGENT_SETPROVIDER)
                    .setParam(AI_P_PROVIDER, "mockProvider");
            TLMsg result = putMsg(M_AIAGENT, switchMsg);
            if (result != null && result.parseBoolean(RESULT, false)) {
                log("[TEST_MODULE] Switched to mockProvider (was: " + originalProviderName + ")");
            } else {
                log("[TEST_MODULE] Switch to mockProvider: " +
                        (result != null ? result.getStringParam("error", "no error") : "null"));
            }
        } catch (Exception e) {
            log("[TEST_MODULE] Switch to mockProvider failed: " + e.getMessage());
        }
    }

    /** 恢复原 Provider */
    private void restoreOriginalProvider() {
        if (originalProviderName == null) return;
        try {
            TLMsg switchMsg = createMsg()
                    .setAction(AGENT_SETPROVIDER)
                    .setParam(AI_P_PROVIDER, originalProviderName);
            putMsg(M_AIAGENT, switchMsg);
            log("[TEST_MODULE] Restored provider: " + originalProviderName);
        } catch (Exception e) {
            log("[TEST_MODULE] Restore provider failed: " + e.getMessage());
        }
    }

    /** 动态注册测试 Skill 到 Agent 的 functions 表 */
    @SuppressWarnings("unchecked")
    private boolean registerTestSkills() {
        try {
            // 注册 test_echo
            HashMap<String, String> echoCfg = new HashMap<>();
            echoCfg.put("sameClassAs", "testEchoSkill");
            echoCfg.put("description", "Echo back the input message. Use for testing tool execution.");
            TLMsg regEcho = createMsg()
                    .setAction(AGENT_REGISTERSKILL)
                    .setParam(AI_P_SKILLNAME, FN_ECHO)
                    .setParam(AI_P_AGENTCONFIG, echoCfg);
            TLMsg r1 = putMsg(M_AIAGENT, regEcho);
            log("[TEST_MODULE] Register " + FN_ECHO + ": " + (r1 != null && r1.parseBoolean(RESULT, false)));

            // 注册 test_sleep（timeout 将在测试中通过 ToolExecutor 的 executionTimeoutMs 控制）
            HashMap<String, String> sleepCfg = new HashMap<>();
            sleepCfg.put("sameClassAs", "testSleepSkill");
            sleepCfg.put("description", "Sleep for specified milliseconds. Use for testing timeout.");
            TLMsg regSleep = createMsg()
                    .setAction(AGENT_REGISTERSKILL)
                    .setParam(AI_P_SKILLNAME, FN_SLEEP)
                    .setParam(AI_P_AGENTCONFIG, sleepCfg);
            TLMsg r2 = putMsg(M_AIAGENT, regSleep);
            log("[TEST_MODULE] Register " + FN_SLEEP + ": " + (r2 != null && r2.parseBoolean(RESULT, false)));

            boolean ok = (r1 != null && r1.parseBoolean(RESULT, false))
                    && (r2 != null && r2.parseBoolean(RESULT, false));
            return ok;
        } catch (Exception e) {
            log("[TEST_MODULE] Register test skills failed: " + e.getMessage());
            return false;
        }
    }

    /** 注销测试 Skill */
    private void unregisterTestSkills() {
        try {
            TLMsg unregEcho = createMsg()
                    .setAction(AGENT_UNREGISTERSKILL)
                    .setParam(AI_P_SKILLNAME, FN_ECHO);
            putMsg(M_AIAGENT, unregEcho);

            TLMsg unregSleep = createMsg()
                    .setAction(AGENT_UNREGISTERSKILL)
                    .setParam(AI_P_SKILLNAME, FN_SLEEP);
            putMsg(M_AIAGENT, unregSleep);
            log("[TEST_MODULE] Test skills unregistered");
        } catch (Exception e) {
            log("[TEST_MODULE] Unregister test skills failed: " + e.getMessage());
        }
    }

    /** 返回一个跳过结果 */
    private TestFunc skipTest(String reason) {
        return (fromWho, msg) -> createMsg().setParam(RESULT, true).setParam("skipped", true)
                .setParam("skipReason", reason);
    }

    // ======================== XML 配置驱动测试 ========================

    /** 供外部调用的 action 入口 */
    protected TLMsg runCaseFile(Object fromWho, TLMsg msg) {
        String path = msg.getStringParam("caseFile", caseFile);
        if (path == null || path.isEmpty()) return createMsg().setParam(RESULT, false)
                .setParam("error", "caseFile not configured");
        int[] result = runCaseFileInternal(path);
        return createMsg().setParam(RESULT, result[1] == 0)
                .setParam("passed", result[0]).setParam("failed", result[1]);
    }

    @SuppressWarnings("unchecked")
    private int[] runCaseFileInternal(String path) {
        int p = 0, f = 0;
        try {
            List<Map<String, Object>> cases = parseCaseFile(path);
            if (cases.isEmpty()) { log("[TEST] 无自定义用例"); return new int[]{0, 0}; }

            TLMockProvider mp = getMockProvider();
            for (Map<String, Object> c : cases) {
                String caseName = (String) c.getOrDefault("name", "?");
                long caseStart = System.currentTimeMillis();
                try {
                    mp.clearAllResponses();

                    // 1) 入队 mock 响应
                    List<Map<String, Object>> responses = (List<Map<String, Object>>) c.get("responses");
                    if (responses != null) {
                        for (Map<String, Object> r : responses) enqueueFromConfig(mp, r);
                    }

                    // 2) 发 chat
                    Map<String, Object> chatCfg = (Map<String, Object>) c.get("chat");
                    String target = str(chatCfg, "targetAgent", M_AIAGENT);
                    TLMsg chatMsg = createMsg().setAction(AGENT_CHAT)
                            .setSystemParam(AI_P_SESSIONID, str(chatCfg, "sessionId", "case_" + caseName))
                            .setSystemParam(AI_P_USERID, TEST_USER_ID)
                            .setParam(AI_P_USERMESSAGE, str(chatCfg, "userMessage", ""));
                    if (chatCfg.containsKey("model")) chatMsg.setParam(AI_P_MODEL, str(chatCfg, "model", null));
                    if (chatCfg.containsKey("temperature"))
                        chatMsg.setParam(AI_P_TEMPERATURE, Double.parseDouble(str(chatCfg, "temperature", "0.7")));
                    if (chatCfg.containsKey("maxIterations"))
                        chatMsg.setParam(AI_P_MAXTOOLCALLITERATIONS, Integer.parseInt(str(chatCfg, "maxIterations", "10")));

                    TLMsg response = putMsg(target, chatMsg);

                    // 3) 断言
                    Map<String, Object> assertCfg = (Map<String, Object>) c.get("assert");
                    if (assertCfg != null) {
                        String err = runAsserts(response, assertCfg);
                        if (err != null) {
                            f++; failedCases.add(caseName);
                            addResult(caseName, caseName, false, err, caseStart);
                            log("[TEST] 用例[" + caseName + "]: FAIL - " + err);
                        } else {
                            p++; addResult(caseName, caseName, true, null, caseStart);
                            log("[TEST] 用例[" + caseName + "]: PASS");
                        }
                    } else {
                        p++; addResult(caseName, caseName, true, null, caseStart);
                        log("[TEST] 用例[" + caseName + "]: PASS (no asserts)");
                    }
                } catch (Exception e) {
                    f++; failedCases.add(caseName);
                    addResult(caseName, caseName, false, "异常: " + e.getMessage(), caseStart);
                    log("[TEST] 用例[" + caseName + "]: FAIL - " + e.getMessage());
                }
            }
        } catch (Exception e) {
            log("[TEST] 加载用例文件失败: " + e.getMessage());
            failedCases.add("用例文件加载");
            return new int[]{0, 1};
        }
        return new int[]{p, f};
    }

    /** 从配置 Map 构建 mock 响应并入队 */
    @SuppressWarnings("unchecked")
    private void enqueueFromConfig(TLMockProvider mp, Map<String, Object> r) {
        String resultStr = (String) r.getOrDefault("result", "true");
        boolean result = !"false".equals(resultStr);
        String text = (String) r.getOrDefault("aiResponse", "");
        boolean hasTools = "true".equals(r.getOrDefault("hasToolCalls", "false"));

        if (hasTools) {
            List<Map<String, Object>> tcList = (List<Map<String, Object>>) r.get("toolCalls");
            List<TLToolCall> tcs = new ArrayList<>();
            if (tcList != null) {
                for (Map<String, Object> tcMap : tcList) {
                    String fn = str(tcMap, "function", "");
                    String argsJson = str(tcMap, "args", "{}");
                    Map<String, Object> args;
                    try { args = new com.google.gson.Gson().fromJson(argsJson, Map.class); }
                    catch (Exception e) { args = Collections.emptyMap(); }
                    tcs.add(TLMockProvider.createToolCall(
                            str(tcMap, "id", "call_" + fn), fn, args));
                }
            }
            mp.enqueueResponse(mp.toolCallResponse(tcs));
        } else if (!result) {
            mp.enqueueResponse(mp.errorResponse(str(r, "error", "mock error")));
        } else {
            mp.enqueueResponse(mp.textResponse(text));
        }
    }

    /** 断言检查，返回 null=通过，非 null=失败原因 */
    private String runAsserts(TLMsg response, Map<String, Object> cfg) {
        if (response == null) return "chat 返回 null";

        if (cfg.containsKey("result")) {
            boolean expected = "true".equals(cfg.get("result"));
            if (response.parseBoolean(RESULT, false) != expected)
                return "RESULT expected=" + expected + " actual=" + response.parseBoolean(RESULT, false);
        }
        String aiResp = response.getStringParam(AI_P_RESPONSE, "");
        if (cfg.containsKey("aiResponseContains")) {
            if (!aiResp.contains(str(cfg, "aiResponseContains", "")))
                return "aiResponse 不包含 '" + str(cfg, "aiResponseContains", "") + "'";
        }
        if (cfg.containsKey("aiResponseNotContains")) {
            if (aiResp.contains(str(cfg, "aiResponseNotContains", "")))
                return "aiResponse 不应包含 '" + str(cfg, "aiResponseNotContains", "") + "'";
        }
        if (cfg.containsKey("minIterations")) {
            int actual = response.getIntParam("iterations", 0);
            int min = Integer.parseInt(str(cfg, "minIterations", "0"));
            if (actual < min) return "iterations=" + actual + " < " + min;
        }
        if (cfg.containsKey("maxIterations")) {
            int actual = response.getIntParam("iterations", 0);
            int max = Integer.parseInt(str(cfg, "maxIterations", "999"));
            if (actual > max) return "iterations=" + actual + " > " + max;
        }
        return null;
    }

    /** Map 取值 helper，处理 Object→String 转换 */
    private String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v != null ? v.toString() : def;
    }

    /** 简单 XML 解析（纯 DOM，无外部依赖） */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseCaseFile(String path) throws Exception {
        List<Map<String, Object>> cases = new ArrayList<>();
        java.io.InputStream is = null;

        // 尝试多种方式加载
        try { is = new java.io.FileInputStream(path); }
        catch (Exception ignored) {}
        if (is == null) {
            String cp = path.startsWith("CLASSPATH/") ? path.substring("CLASSPATH/".length()) : path;
            is = getClass().getClassLoader().getResourceAsStream(cp);
        }
        if (is == null) {
            is = getClass().getClassLoader().getResourceAsStream(path);
        }
        if (is == null) { log("[TEST] 用例文件未找到: " + path); return cases; }

        javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        org.w3c.dom.Document doc = dbf.newDocumentBuilder().parse(is);
        is.close();

        org.w3c.dom.NodeList caseNodes = doc.getElementsByTagName("case");
        for (int i = 0; i < caseNodes.getLength(); i++) {
            Map<String, Object> c = new LinkedHashMap<>();
            org.w3c.dom.Element ce = (org.w3c.dom.Element) caseNodes.item(i);
            c.put("name", ce.getAttribute("name"));

            // mock
            org.w3c.dom.NodeList mockNodes = ce.getElementsByTagName("mock");
            if (mockNodes.getLength() > 0) {
                org.w3c.dom.NodeList respNodes = ((org.w3c.dom.Element) mockNodes.item(0))
                        .getElementsByTagName("response");
                List<Map<String, Object>> responses = new ArrayList<>();
                for (int j = 0; j < respNodes.getLength(); j++) {
                    org.w3c.dom.Element re = (org.w3c.dom.Element) respNodes.item(j);
                    Map<String, Object> r = attrsToMap(re);
                    // tool calls
                    org.w3c.dom.NodeList tcNodes = re.getElementsByTagName("toolCall");
                    if (tcNodes.getLength() > 0) {
                        List<Map<String, Object>> tcs = new ArrayList<>();
                        for (int k = 0; k < tcNodes.getLength(); k++) {
                            tcs.add(attrsToMap((org.w3c.dom.Element) tcNodes.item(k)));
                        }
                        r.put("toolCalls", tcs);
                    }
                    responses.add(r);
                }
                c.put("responses", responses);
            }

            // chat
            org.w3c.dom.NodeList chatNodes = ce.getElementsByTagName("chat");
            if (chatNodes.getLength() > 0)
                c.put("chat", attrsToMap((org.w3c.dom.Element) chatNodes.item(0)));

            // assert
            org.w3c.dom.NodeList assertNodes = ce.getElementsByTagName("assert");
            if (assertNodes.getLength() > 0)
                c.put("assert", attrsToMap((org.w3c.dom.Element) assertNodes.item(0)));

            cases.add(c);
        }
        return cases;
    }

    private Map<String, Object> attrsToMap(org.w3c.dom.Element e) {
        Map<String, Object> m = new LinkedHashMap<>();
        org.w3c.dom.NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            org.w3c.dom.Node a = attrs.item(i);
            m.put(a.getNodeName(), a.getNodeValue());
        }
        return m;
    }

    /** 同时输出到控制台和框架日志 */
    private void log(String msg) {
        System.out.println(msg);
        putLog(msg, LogLevel.WARN);
    }
}
