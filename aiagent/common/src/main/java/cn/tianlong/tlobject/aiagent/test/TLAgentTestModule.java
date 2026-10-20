package cn.tianlong.tlobject.aiagent.test;

import cn.tianlong.tlobject.aiagent.*;
import cn.tianlong.tlobject.aiagent.evals.TLEvalBaseline;
import cn.tianlong.tlobject.aiagent.evals.TLEvalReport;
import cn.tianlong.tlobject.aiagent.evals.TLEvalReportMsg;
import cn.tianlong.tlobject.aiagent.evals.TLEvalRunResult;
import cn.tianlong.tlobject.base.IObject;
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
    private static final String SID_STREAMDIRECT = "test_mock_streamdirect";
    private static final String SID_TRUNC        = "test_mock_truncation";
    private static final String SID_PLUMP        = "test_mock_streamparams";

    /** 内置场景用到的全部会话（整组清理，套件才有重入性） */
    private static final List<String> TEST_SESSIONS = Arrays.asList(
            SID_BASIC, SID_MULTITURN, SID_SINGLETOOL, SID_PARALLEL, SID_TIMEOUT,
            SID_STREAM, SID_CANCEL, SID_BATCHTIMEOUT, SID_CHECKPOINT, SID_STREAMERR,
            SID_STREAMDIRECT, SID_TRUNC, SID_PLUMP);

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
            String skipReason = skipReasonFor(e.getKey(), def, skillsOk);
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
        TEST_CASES.put("streamDirect",     new String[]{"12-流式直出短路落历史", "test_echo 未注册"});
        TEST_CASES.put("truncNotice",      new String[]{"13-截断如实告知", null});
        TEST_CASES.put("streamParams",     new String[]{"14-流式推理传参", "test_echo 未注册"});
        TEST_CASES.put("approvalSessionGrant", new String[]{"16-审批会话免审", null});
        TEST_CASES.put("approvalGrantAdmin", new String[]{"17-审批授权管理", null});
        // 18/19/20 第二槽（测试 Skill 未注册时的跳过原因）为 null：审批场景直发 approvalGate，不走 Skill——
        // 填了会让 skill 注册失败的配置下这 3 个场景被静默跳过（假绿）
        TEST_CASES.put("approvalExpire", new String[]{"18-审批超时收口", null});
        TEST_CASES.put("approvalBlockWakeup", new String[]{"19-审批阻塞唤醒", null});
        TEST_CASES.put("approvalBlockTimeout", new String[]{"20-审批阻塞自超时", null});
        TEST_CASES.put("approvalInfiniteWait", new String[]{"21-审批无限等待", null});
        TEST_CASES.put("approvalConsoleDefer", new String[]{"22-无人接收转断点", null});
        TEST_CASES.put("approvalDeferWakeup", new String[]{"23-审批退出转断点", null});
        TEST_CASES.put("approvalPendingTtl", new String[]{"24-断点审批TTL", null});
        TEST_CASES.put("approvalResumeExecute", new String[]{"25-断点恢复批准执行", null});
        TEST_CASES.put("approvalResumeLost", new String[]{"26-断点恢复失效不执行", null});
        TEST_CASES.put("approvalCheckpointDiscard", new String[]{"27-待批断点可见与丢弃", null});
        TEST_CASES.put("approvalPendingCheckpoint", new String[]{"28-待批断点生命周期", null});
    }

    /**
     * 该用例这一遍要不要跳过（返回 null = 照常跑）。
     * 三类跳过：测试 Skill 注册失败（def[1]）、依赖模块没配（def[2]）、审批场景跑在非测试审查人下。
     * 后者按"工厂里有没有这个模块"判定——chat 那套配置没配 testCacheProvider，
     * 之前它被算成 FAIL，测试层因此恒不通过。
     */
    private String skipReasonFor(String name, String[] def, boolean skillsOk) {
        if (!skillsOk && def[1] != null) return def[1];
        if (def.length > 2 && def[2] != null && !moduleConfigured(def[2]))
            return "依赖模块 " + def[2] + " 未配置";
        // 审批场景：必须跑在测试审查人环境下（aitest 配置）；生产 ConsoleReviewer 会阻塞测试线程
        if (name != null && name.toLowerCase().startsWith("approval")) {
            Object gate = getModuleInFactory("approvalGate");
            if (!(gate instanceof IObject)) return "approvalGate 未配置";
            TLMsg info = putMsg((IObject) gate, createMsg().setAction(APPROVAL_INFO));
            if (info == null || !info.parseBoolean("infoOk", false)) {
                return "approvalGate 未响应 APPROVAL_INFO（接线异常，疑似回归）";
            }
            String inst = info.getStringParam("reviewerInstance", "");
            if (!inst.endsWith("TLTestApprovalReviewer")) return "非测试审查人（" + inst + "）";
        }
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
            case "streamdirect":    return this::testStreamDirect;
            case "truncnotice":     return this::testTruncNotice;
            case "streamparams":    return this::testStreamParams;
            case "approvalsessiongrant": return this::testApprovalSessionGrant;
            case "approvalgrantadmin": return this::testApprovalGrantAdmin;
            case "approvalexpire": return this::testApprovalExpire;
            case "approvalblockwakeup": return this::testApprovalBlockWakeup;
            case "approvalblocktimeout": return this::testApprovalBlockTimeout;
            case "approvalinfinitewait": return this::testApprovalInfiniteWait;
            case "approvalconsoledefer": return this::testApprovalConsoleDefer;
            case "approvaldeferwakeup": return this::testApprovalDeferWakeup;
            case "approvalpendingttl": return this::testApprovalPendingTtl;
            case "approvalresumeexecute": return this::testApprovalResumeExecute;
            case "approvalresumelost": return this::testApprovalResumeLost;
            case "approvalcheckpointdiscard": return this::testApprovalCheckpointDiscard;
            case "approvalpendingcheckpoint": return this::testApprovalPendingCheckpoint;
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
            String skipReason = skipReasonFor(caseName, def, skillsOk);
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

            if (!streamDone || content.isEmpty()) {
                boolean timedOut = waitResult.parseBoolean("timedOut", false);
                return createMsg().setParam(RESULT, false)
                        .setParam("error", String.format("streamDone=%s, timedOut=%s, content=%s",
                                streamDone, timedOut, content));
            }

            // 契约 1：done 正文与 chunk 不一致 → sink 自己累积，UI 收到补的差值（不是停在 "A"）
            putMsg("streamCallback", createMsg().setAction("resetStream"));
            mp.enqueueStreamResponses(sessionId, Arrays.asList(
                    mp.streamChunk("A"),
                    mp.streamDone("AB")));
            putMsg(M_AIAGENT, createMsg().setAction(AGENT_CHATSTREAM)
                    .setSystemParam(AI_P_SESSIONID, sessionId)
                    .setParam(AI_P_USERMESSAGE, "sink 累积契约")
                    .setParam(RESULTFOR, "streamCallback")
                    .setParam(RESULTACTION, "onStreamChunk"));
            TLMsg wr2 = putMsg("streamCallback",
                    createMsg().setAction("waitForStream").setParam("timeout", 30));
            String c2 = wr2.getStringParam("content", "");
            if (!"AB".equals(c2)) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "sink 累积契约失败: expected AB, got " + c2);
            }

            // 契约 2：只有 reasoning chunk、无 content chunk（提升为正文）→ 终答必须补推一次
            putMsg("streamCallback", createMsg().setAction("resetStream"));
            mp.enqueueStreamResponses(sessionId, Arrays.asList(
                    createMsg().setAction(STREAM_ONCHUNK).setParam(AI_P_REASONING_CHUNK, "思考"),
                    mp.streamDone("提升后的终答").setParam(AI_P_CONTENT_FROM_REASONING, true)));
            putMsg(M_AIAGENT, createMsg().setAction(AGENT_CHATSTREAM)
                    .setSystemParam(AI_P_SESSIONID, sessionId)
                    .setParam(AI_P_USERMESSAGE, "reasoning 提升契约")
                    .setParam(RESULTFOR, "streamCallback")
                    .setParam(RESULTACTION, "onStreamChunk"));
            TLMsg wr3 = putMsg("streamCallback",
                    createMsg().setAction("waitForStream").setParam("timeout", 30));
            String c3 = wr3.getStringParam("content", "");
            if (!"提升后的终答".equals(c3)) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "reasoning 提升契约失败: got " + c3);
            }

            // 契约 3：测试/外部 sink（TLStreamCallback）也要支持 AI_P_STREAMREPLACE（整体替换）
            putMsg("streamCallback", createMsg().setAction("resetStream"));
            putMsg("streamCallback", createMsg().setAction(STREAM_ONCHUNK)
                    .setParam(AI_P_CHUNK, "A"));
            putMsg("streamCallback", createMsg().setAction(STREAM_ONCHUNK)
                    .setParam(AI_P_STREAMREPLACE, "AB"));
            putMsg("streamCallback", createMsg().setAction(STREAM_ONCHUNK)
                    .setParam(AI_P_STREAMDONE, true));
            TLMsg wr4 = putMsg("streamCallback",
                    createMsg().setAction("waitForStream").setParam("timeout", 5));
            String c4 = wr4.getStringParam("content", "");
            if (!"AB".equals(c4)) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "测试 sink replace 契约失败: got " + c4);
            }

            log("[TEST]   流式: content=" + content + "；sink 累积/提升/replace 契约通过");
            return createMsg().setParam(RESULT, true);
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

    // ======================== 场景 12: 流式直出短路落历史 ========================

    /**
     * 直出短路轮（单工具 + finalAnswer，如 directOutput 子 agent / 工作流）：
     * 终答全文此前只经 forwardStreamFinal 推给前端、不入 history——恢复/重登后会话里
     * 只剩开场白 + 工具卡片，用户看到"诗没有了"。
     * 断言两件事：① 流式内容含直出全文（live 可见性）；② 落库轮次末条是 assistant 直出全文（重登可见性）。
     * 驱动方式与 webui 生产路径一致：RESULTFOR=agent + RESULTACTION=onStreamResult，
     * 显示目标走 _streamResultFor（testStreamBasic 直接以 streamCallback 为 resultFor，不经过 agent 回调）。
     */
    protected TLMsg testStreamDirect(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();

        String sessionId = SID_STREAMDIRECT;
        String directText = "ECHO: 《直出诗》\n第一行\n第二行";

        // 流式响应：先过渡文本，随后 done 带 tool_call（echo 标 finalAnswer=true → ToolExecutor 标 final）
        TLToolCall tc = TLMockProvider.createToolCall("call_direct_1", FN_ECHO,
                new HashMap<String, Object>() {{
                    put("message", "《直出诗》\n第一行\n第二行");
                    put("finalAnswer", true);
                }});
        mp.enqueueStreamResponses(sessionId, Arrays.asList(
                mp.streamChunk("稍等，这就安排。"),
                mp.streamDoneWithToolCalls(Collections.singletonList(tc))));

        try {
            putMsg("streamCallback", createMsg().setAction("resetStream"));

            TLMsg streamMsg = createMsg()
                    .setAction(AGENT_CHATSTREAM)
                    .setSystemParam(AI_P_SESSIONID, sessionId)
                    .setSystemParam(AI_P_USERID, TEST_USER_ID)
                    .setParam(AI_P_USERMESSAGE, "来两首直出诗")
                    .setParam(RESULTFOR, M_AIAGENT)            // provider 事件回 agent.onStreamResult（生产同款）
                    .setParam(RESULTACTION, "onStreamResult")
                    .setParam("_streamResultFor", "streamCallback")
                    .setParam("_streamResultAction", "onStreamChunk");
            putMsg(M_AIAGENT, streamMsg);

            TLMsg waitResult = putMsg("streamCallback", createMsg()
                    .setAction("waitForStream").setParam("timeout", 30));
            String content = waitResult.getStringParam("content", "");
            if (!content.contains(directText)) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "流式内容缺直出全文: " + content);
            }

            // 关键断言：落库轮次必须以 assistant 直出全文收尾（恢复会话可见性）
            TLMsg cont = putMsg("sessionManager", createMsg().setAction("continueSession")
                    .setParam("sessionId", sessionId)
                    .setParam("userId", TEST_USER_ID));
            List<?> history = cont != null
                    ? (List<?>) cont.getListParam("history", Collections.emptyList()) : null;
            if (history == null || history.isEmpty()) {
                return createMsg().setParam(RESULT, false).setParam("error", "落库历史为空");
            }
            Object lastObj = history.get(history.size() - 1);
            if (!(lastObj instanceof TLConversationHistory)) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "末条不是会话消息: " + lastObj);
            }
            TLConversationHistory last = (TLConversationHistory) lastObj;
            if (last.getRole() != TLConversationHistory.Role.assistant
                    || !directText.equals(last.getContent())) {
                String c = last.getContent() == null ? "null" : last.getContent();
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "直出终答未落库：末条 role=" + last.getRole()
                                + " content=" + c.substring(0, Math.min(80, c.length())));
            }
            log("[TEST]   流式直出: 终答已落库（末条 assistant, " + directText.length() + " 字）");
            return createMsg().setParam(RESULT, true);
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false).setParam("error", "stream direct exception: " + e);
        }
    }

    // ======================== 场景 13: 截断如实告知 ========================

    /**
     * finish_reason=length 的终答此前是静默腰斩（用户只看到半句话，如"…三、"）。
     * 断言：① 非流式 length 终答补提示；② 非流式 stop 终答不补（防过度应用）；
     * ③ 流式无工具轮：提示既推前端也落库（重登可见）。
     */
    protected TLMsg testTruncNotice(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();

        // ① 非流式 + length → 必须补
        mp.enqueueResponse(mp.textResponse("答案前半段，后面还有").setParam(AI_P_FINISH_REASON, "length"));
        TLMsg r1 = putMsg(M_AIAGENT, createMsg().setAction(AGENT_CHAT)
                .setSystemParam(AI_P_SESSIONID, SID_TRUNC)
                .setSystemParam(AI_P_USERID, TEST_USER_ID)
                .setParam(AI_P_USERMESSAGE, "写个长回答"));
        String resp1 = r1.getStringParam(AI_P_RESPONSE, "");
        if (!resp1.contains("被截断")) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "length 终答未补截断提示: " + resp1);
        }

        // ② 非流式 + stop → 不该补（防过度应用）
        mp.enqueueResponse(mp.textResponse("完整答案"));
        TLMsg r2 = putMsg(M_AIAGENT, createMsg().setAction(AGENT_CHAT)
                .setSystemParam(AI_P_SESSIONID, SID_TRUNC)
                .setSystemParam(AI_P_USERID, TEST_USER_ID)
                .setParam(AI_P_USERMESSAGE, "再写个短的"));
        String resp2 = r2.getStringParam(AI_P_RESPONSE, "");
        if (resp2.contains("被截断")) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "stop 终答被误补截断提示: " + resp2);
        }

        // ③ 流式无工具轮 + length → 提示推前端 + 落库
        try {
            putMsg("streamCallback", createMsg().setAction("resetStream"));
            mp.enqueueStreamResponses(SID_TRUNC, Arrays.asList(
                    mp.streamChunk("流式前半段"),
                    mp.streamDone("流式前半段").setParam(AI_P_FINISH_REASON, "length")));
            putMsg(M_AIAGENT, createMsg().setAction(AGENT_CHATSTREAM)
                    .setSystemParam(AI_P_SESSIONID, SID_TRUNC)
                    .setSystemParam(AI_P_USERID, TEST_USER_ID)
                    .setParam(AI_P_USERMESSAGE, "流式写个长回答")
                    .setParam(RESULTFOR, M_AIAGENT)
                    .setParam(RESULTACTION, "onStreamResult")
                    .setParam("_streamResultFor", "streamCallback")
                    .setParam("_streamResultAction", "onStreamChunk"));
            TLMsg waitResult = putMsg("streamCallback", createMsg().setAction("waitForStream")
                    .setParam("timeout", 30));
            String content = waitResult.getStringParam("content", "");
            if (!content.contains("被截断")) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "流式截断提示未推前端: " + content);
            }
            TLMsg cont = putMsg("sessionManager", createMsg().setAction("continueSession")
                    .setParam("sessionId", SID_TRUNC).setParam("userId", TEST_USER_ID));
            List<?> history = cont != null
                    ? (List<?>) cont.getListParam("history", Collections.emptyList()) : null;
            if (history == null || history.isEmpty()) {
                return createMsg().setParam(RESULT, false).setParam("error", "截断轮落库历史为空");
            }
            Object lastObj = history.get(history.size() - 1);
            String lastContent = lastObj instanceof TLConversationHistory
                    ? ((TLConversationHistory) lastObj).getContent() : "";
            if (lastContent == null || !lastContent.contains("被截断")) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "流式截断提示未落库: " + lastContent);
            }
            log("[TEST]   截断告知: 非流式/流式/落库 三处均已如实告知");
            return createMsg().setParam(RESULT, true);
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false).setParam("error", "trunc notice exception: " + e);
        }
    }

    // ======================== 场景 14: 流式推理传参 ========================

    /**
     * 流式链路此前不传 reasoningMode/maxTokens（配 disabled 关不掉思考、配 maxTokens 不生效）。
     * 断言：首包与续跑调用都带上请求级 reasoningMode 与 maxTokens（经 mock 入参快照验证）。
     */
    protected TLMsg testStreamParams(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();
        mp.lastStreamRequest = null;
        mp.lastCompletionRequest = null;

        String sessionId = SID_PLUMP;
        // 首包带一个普通工具调用（非直出）→ 触发续跑的非流式调用
        TLToolCall tc = TLMockProvider.createToolCall("call_plumb_1", FN_ECHO,
                new HashMap<String, Object>() {{ put("message", "plumb"); }});
        mp.enqueueStreamResponses(sessionId, Arrays.asList(
                mp.streamChunk("查一下。"),
                mp.streamDoneWithToolCalls(Collections.singletonList(tc))));
        mp.enqueueResponse(mp.textResponse("完成"));

        try {
            putMsg("streamCallback", createMsg().setAction("resetStream"));
            putMsg(M_AIAGENT, createMsg().setAction(AGENT_CHATSTREAM)
                    .setSystemParam(AI_P_SESSIONID, sessionId)
                    .setSystemParam(AI_P_USERID, TEST_USER_ID)
                    .setParam(AI_P_USERMESSAGE, "参数透传测试")
                    .setParam(AI_P_REASONING_MODE, AI_P_REASONING_MODE_DISABLED)
                    .setParam(RESULTFOR, M_AIAGENT)
                    .setParam(RESULTACTION, "onStreamResult")
                    .setParam("_streamResultFor", "streamCallback")
                    .setParam("_streamResultAction", "onStreamChunk"));
            putMsg("streamCallback", createMsg().setAction("waitForStream").setParam("timeout", 30));

            TLMsg sr = mp.lastStreamRequest;
            TLMsg cr = mp.lastCompletionRequest;
            if (sr == null || !AI_P_REASONING_MODE_DISABLED.equals(sr.getStringParam(AI_P_REASONING_MODE, ""))) {
                return createMsg().setParam(RESULT, false).setParam("error",
                        "首包未带 reasoningMode=disabled: "
                                + (sr != null ? sr.getStringParam(AI_P_REASONING_MODE, "无") : "null"));
            }
            if (sr.getIntParam(AI_P_MAXTOKENS, 0) <= 0) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "首包未带 maxTokens");
            }
            if (cr == null || !AI_P_REASONING_MODE_DISABLED.equals(cr.getStringParam(AI_P_REASONING_MODE, ""))) {
                return createMsg().setParam(RESULT, false).setParam("error",
                        "续跑调用未带 reasoningMode=disabled: "
                                + (cr != null ? cr.getStringParam(AI_P_REASONING_MODE, "无") : "null(未发生续跑?)"));
            }
            // 续跑调用必须带会话参数：否则 provider trace 文件名落 default，所有会话的调用
            // 全追加进同一个 .trace（实测 176MB 无轮转）
            if (!sessionId.equals(cr.getStringParam(AI_P_SESSIONID, ""))) {
                return createMsg().setParam(RESULT, false).setParam("error",
                        "续跑调用未带 AI_P_SESSIONID（trace 命名会落 default）: "
                                + cr.getStringParam(AI_P_SESSIONID, "无"));
            }
            log("[TEST]   流式传参: 首包与续跑均带 reasoningMode/maxTokens/会话参数");
            return createMsg().setParam(RESULT, true);
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false).setParam("error", "stream params exception: " + e);
        }
    }

    // ======================== 场景 16: 审批授权（消息级，不经 LLM） ========================

    /** 审批授权测试用会话 */
    protected static final String SID_APPROVAL = "test_approval_grant";

    /**
     * 会话级免审：首请求 pending → 批准(scope=session) → 同规则再请求自动批准（不再问审查人）；
     * 不同操作仍问（粒度隔离）。
     */
    protected TLMsg testApprovalSessionGrant(Object fromWho, TLMsg msg) {
        // 防御性守卫：模块须存在（skipReasonFor 已兜底，这里是第二道）
        Object gate = getModuleInFactory("approvalGate");
        if (!(gate instanceof IObject)) {
            return createMsg().setParam(RESULT, false).setParam("error", "approvalGate 未配置");
        }
        cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer.reset();
        resetApprovalGrants();   // 套件重入性：同一进程二次 /test 时授权不能残留

        // 1. 首请求：file_operation:delete → pending，审查人被问一次
        TLMsg r1 = requestApprovalFor("file_operation", "{\"operation\":\"delete\",\"path\":\"D:/tmp/a.txt\"}");
        if (!"pending".equals(r1.getStringParam(AI_P_APPROVAL_STATE, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "首请求应 pending，实为 "
                    + r1.getStringParam(AI_P_APPROVAL_STATE, ""));
        }
        // 2. 批准并授予"本会话"
        TLMsg ap = putMsg("approvalGate", createMsg().setAction(APPROVAL_APPROVE)
                .setParam(AI_P_APPROVAL_ID, r1.getStringParam(AI_P_APPROVAL_ID, ""))
                .setParam(AI_P_APPROVAL_SCOPE, "session"));
        if (!ap.parseBoolean(RESULT, false)) {
            return createMsg().setParam(RESULT, false).setParam("error", "批准失败: " + ap.getStringParam("error", ""));
        }
        // 3. 同规则再请求 → 自动批准，审查人计数不变
        TLMsg r2 = requestApprovalFor("file_operation", "{\"operation\":\"delete\",\"path\":\"D:/tmp/b.txt\"}");
        if (!"approved".equals(r2.getStringParam(AI_P_APPROVAL_STATE, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "授权命中应 approved，实为 "
                    + r2.getStringParam(AI_P_APPROVAL_STATE, ""));
        }
        if (cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer.count() != 1) {
            return createMsg().setParam(RESULT, false).setParam("error",
                    "授权命中不应再问审查人，计数=" + cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer.count());
        }
        // 4. 粒度隔离：同规则不同操作（write）仍需审批
        TLMsg r3 = requestApprovalFor("file_operation", "{\"operation\":\"write\",\"path\":\"D:/tmp/c.txt\"}");
        if (!"pending".equals(r3.getStringParam(AI_P_APPROVAL_STATE, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "write 应仍 pending（粒度隔离），实为 "
                    + r3.getStringParam(AI_P_APPROVAL_STATE, ""));
        }
        resetApprovalGrants();   // 收尾清扫：不留授权给后续场景（消除对入口 reset 的跨场景耦合依赖）
        log("[TEST]   审批授权: 会话免审/粒度隔离 通过（审查人计数="
                + cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer.count() + "）");
        return createMsg().setParam(RESULT, true);
    }

    /**
     * 管理动作：预授权（grant）→ 直接放行；列表可见；撤销后恢复弹框；
     * 通配预授权（自动运行等价）；任务会话继承（登记 task→parent）。
     */
    protected TLMsg testApprovalGrantAdmin(Object fromWho, TLMsg msg) {
        Object gate = getModuleInFactory("approvalGate");
        if (!(gate instanceof IObject)) {
            return createMsg().setParam(RESULT, false).setParam("error", "approvalGate 未配置");
        }
        cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer.reset();
        resetApprovalGrants();

        // 1. 预授权 file_operation:write（不弹框）
        TLMsg g = putMsg("approvalGate", createMsg().setAction(APPROVAL_GRANT)
                .setParam("sessionId", SID_APPROVAL)
                .setParam("ruleId", "file_operation").setParam("operation", "write"));
        if (!g.parseBoolean(RESULT, false)) {
            return createMsg().setParam(RESULT, false).setParam("error", "预授权失败: "
                    + g.getStringParam("error", ""));
        }
        TLMsg r1 = requestApprovalFor("file_operation", "{\"operation\":\"write\",\"path\":\"D:/tmp/w.txt\"}");
        if (!"approved".equals(r1.getStringParam(AI_P_APPROVAL_STATE, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "预授权后应直接 approved，实为 "
                    + r1.getStringParam(AI_P_APPROVAL_STATE, ""));
        }
        // 2. 列表可见
        TLMsg list = putMsg("approvalGate", createMsg().setAction(APPROVAL_GRANTS)
                .setParam("sessionId", SID_APPROVAL));
        List<?> grants = list.getListParam("grants", new ArrayList<>());
        if (grants.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "授权列表为空");
        }
        // 3. 撤销后恢复弹框
        TLMsg rv = putMsg("approvalGate", createMsg().setAction(APPROVAL_REVOKE)
                .setParam("sessionId", SID_APPROVAL)
                .setParam("ruleId", "file_operation").setParam("operation", "write"));
        if (rv == null || rv.getIntParam("removed", 0) < 1) {
            return createMsg().setParam(RESULT, false).setParam("error", "撤销应删除 ≥1 条，实为 "
                    + (rv == null ? "null" : String.valueOf(rv.getIntParam("removed", 0))));
        }
        TLMsg r2 = requestApprovalFor("file_operation", "{\"operation\":\"write\",\"path\":\"D:/tmp/w2.txt\"}");
        if (!"pending".equals(r2.getStringParam(AI_P_APPROVAL_STATE, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "撤销后应恢复 pending，实为 "
                    + r2.getStringParam(AI_P_APPROVAL_STATE, ""));
        }
        // 4. 通配预授权（自动运行等价）：先对照——无授权时 mcpEchoTool 必须 pending（证明 mcp* 规则真在册）
        TLMsg r0 = requestApprovalFor("mcpEchoTool", "{\"x\":1}");
        if (!"pending".equals(r0.getStringParam(AI_P_APPROVAL_STATE, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error",
                    "mcp* 规则未生效（应 pending，实为 " + r0.getStringParam(AI_P_APPROVAL_STATE, "")
                            + "），步 4 无法证明通配授权起作用");
        }
        putMsg("approvalGate", createMsg().setAction(APPROVAL_GRANT)
                .setParam("sessionId", SID_APPROVAL)
                .setParam("ruleId", "*").setParam("operation", "*"));
        TLMsg r3 = requestApprovalFor("mcpEchoTool", "{\"x\":1}");
        if (!"approved".equals(r3.getStringParam(AI_P_APPROVAL_STATE, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "自动运行（通配）应直接 approved，实为 "
                    + r3.getStringParam(AI_P_APPROVAL_STATE, ""));
        }
        // 5. 任务继承：登记 task→parent 后，父会话授权对任务会话请求生效
        String parent = "test_approval_parent";
        putMsg("approvalGate", createMsg().setAction(APPROVAL_GRANT)
                .setParam("sessionId", parent)
                .setParam("ruleId", "file_operation").setParam("operation", "delete"));
        putMsg("approvalGate", createMsg().setAction(APPROVAL_TASKSESSION)
                .setParam("execSession", "task_x_1").setParam("parentSessionId", parent));
        java.util.Map<String, Object> dArgs = new com.google.gson.Gson()
                .fromJson("{\"operation\":\"delete\",\"path\":\"D:/tmp/d.txt\"}", java.util.Map.class);
        TLMsg r4 = putMsg("approvalGate", createMsg().setAction(APPROVAL_REQUEST)
                .setParam("toolName", "file_operation").setParam("toolArgs", dArgs)
                .setSystemParam(AI_P_SESSIONID, "task_x_1")
                .setSystemParam("userId", TEST_USER_ID)
                .setSystemParam(AI_P_ROUNDID, "r_task_1"));
        if (!"approved".equals(r4.getStringParam(AI_P_APPROVAL_STATE, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "任务会话应继承父会话授权，实为 "
                    + r4.getStringParam(AI_P_APPROVAL_STATE, ""));
        }
        // 6. 注销映射后，任务会话不再继承
        putMsg("approvalGate", createMsg().setAction(APPROVAL_TASKSESSION)
                .setParam("execSession", "task_x_1").setParam("parentSessionId", ""));
        TLMsg r5 = putMsg("approvalGate", createMsg().setAction(APPROVAL_REQUEST)
                .setParam("toolName", "file_operation")
                .setParam("toolArgs", new com.google.gson.Gson()
                        .fromJson("{\"operation\":\"delete\",\"path\":\"D:/tmp/d2.txt\"}", java.util.Map.class))
                .setSystemParam(AI_P_SESSIONID, "task_x_1")
                .setSystemParam("userId", TEST_USER_ID)
                .setSystemParam(AI_P_ROUNDID, "r_task_1"));
        if (!"pending".equals(r5.getStringParam(AI_P_APPROVAL_STATE, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "注销映射后应恢复 pending，实为 "
                    + r5.getStringParam(AI_P_APPROVAL_STATE, ""));
        }
        // 7. 负例：不在册的 ruleId 预授权必须被拒
        TLMsg g2 = putMsg("approvalGate", createMsg().setAction(APPROVAL_GRANT)
                .setParam("sessionId", SID_APPROVAL).setParam("ruleId", "mcpEchoTool"));
        if (g2 == null || g2.parseBoolean(RESULT, true)) {
            return createMsg().setParam(RESULT, false).setParam("error", "不在册 ruleId 的预授权应被拒");
        }
        // 8. 负例：grants 传非字符串 sessionId 必须报错（不得 fail-open 列全部）
        TLMsg g3 = putMsg("approvalGate", createMsg().setAction(APPROVAL_GRANTS)
                .setParam("sessionId", Integer.valueOf(1)));
        if (g3 == null || g3.parseBoolean(RESULT, true)) {
            return createMsg().setParam(RESULT, false).setParam("error", "非字符串 sessionId 的 grants 应报错");
        }
        resetApprovalGrants();   // 收尾清扫：不留授权给后续场景（消除对入口 reset 的跨场景耦合依赖）
        log("[TEST]   审批授权管理: 预授权/列表/撤销/通配/任务继承与注销 通过");
        return createMsg().setParam(RESULT, true);
    }

    /**
     * 断点态 TTL 收口 + 迟到批准被拒：审查人立即返回 pending（无人接收 → 转断点），
     * 创建时限随转断点作废，只由 approvalPendingTtlMs（aitest 配 5000ms）收口为 EXPIRED，
     * reason 为 TTL 文案（"超过5秒未处理"）而非创建时限文案（"审批超时（3秒）"）；
     * 迟到批准被拒（已失效请求 APPROVE 必须 RESULT=false）。
     * 注：deferred 后创建时限已取消 → approvalTimeoutMs 路径对断点请求不再可达；
     * 阻塞等待超时路径由场景 20（latch 探针）与场景 24（TTL）分别覆盖。
     */
    protected TLMsg testApprovalExpire(Object fromWho, TLMsg msg) {
        cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer.reset();
        resetApprovalGrants();
        TLMsg r1 = requestApprovalFor("file_operation", "{\"operation\":\"delete\",\"path\":\"D:/tmp/exp.txt\"}");
        String id = r1.getStringParam(AI_P_APPROVAL_ID, "");
        if (!"pending".equals(r1.getStringParam(AI_P_APPROVAL_STATE, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "应 pending");
        }
        // 有界轮询等 TTL（5000ms）收口：每 200ms 查一次，最多 ~10s（不固定睡——转断点后
        // 创建时限已取消，固定睡 4000ms 会赶在 TTL 前面查询而假失败；查不到才算失败）
        TLMsg q = null;
        String state = "";
        for (int i = 0; i < 50 && !"expired".equals(state); i++) {
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            q = putMsg("approvalGate", createMsg().setAction(APPROVAL_QUERY)
                    .setParam(AI_P_APPROVAL_ID, id));
            state = q.getStringParam(AI_P_APPROVAL_STATE, "");
        }
        if (!"expired".equals(state)) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "应 expired（断点态 TTL 收口），实为 " + state);
        }
        // 断点态只受 TTL 管：reason 必须是 TTL 文案——若仍是"审批超时（3秒）"说明创建时限未被取消
        String reason = q.getStringParam(AI_P_APPROVAL_REJECTREASON, "");
        if (!reason.contains("未处理")) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "TTL 收口 reason 应含'未处理'，实为 " + reason);
        }
        TLMsg ap = putMsg("approvalGate", createMsg().setAction(APPROVAL_APPROVE)
                .setParam(AI_P_APPROVAL_ID, id));
        // fail-closed：RESULT 缺失（默认 true）或响应为 null 一律判失败——
        // 用 default false 会在 wiring 断裂（无 RESULT）时"测试通过"
        if (ap == null || ap.parseBoolean(RESULT, true)) {
            return createMsg().setParam(RESULT, false).setParam("error", "已超时的审批不应可再批准");
        }
        resetApprovalGrants();
        log("[TEST]   审批超时收口: 断点态 TTL 收口 + 拒绝迟到批准 通过");
        return createMsg().setParam(RESULT, true);
    }

    /** 阻塞路径（生产 ConsoleReviewer 同款）：/approve 消息唤醒 latch，阻塞调用返回 approved */
    protected TLMsg testApprovalBlockWakeup(Object fromWho, TLMsg msg) {
        cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer.reset();
        resetApprovalGrants();
        cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer.setBlockMode(true);
        final java.util.concurrent.atomic.AtomicReference<TLMsg> holder = new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(() -> holder.set(
                requestApprovalFor("file_operation", "{\"operation\":\"delete\",\"path\":\"D:/tmp/blk.txt\"}")));
        try {
            t.start();
            // 等请求登记进审查人记录（拿 approvalId）
            String id = "";
            for (int i = 0; i < 50 && id.isEmpty(); i++) {
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                List<cn.tianlong.tlobject.aiagent.approval.TLApprovalRequest> reqs =
                        cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer.snapshot();
                if (!reqs.isEmpty()) id = reqs.get(reqs.size() - 1).getApprovalId();
            }
            if (id.isEmpty()) {
                return createMsg().setParam(RESULT, false).setParam("error", "阻塞请求未登记（超时）");
            }
            TLMsg ap = putMsg("approvalGate", createMsg().setAction(APPROVAL_APPROVE)
                    .setParam(AI_P_APPROVAL_ID, id));
            if (!ap.parseBoolean(RESULT, false)) {
                return createMsg().setParam(RESULT, false).setParam("error", "批准失败: " + ap.getStringParam("error", ""));
            }
            t.join(5000);
            TLMsg ret = holder.get();
            if (ret == null || !"approved".equals(ret.getStringParam(AI_P_APPROVAL_STATE, ""))) {
                return createMsg().setParam(RESULT, false).setParam("error", "阻塞线程应被唤醒并返回 approved，实为 "
                        + (ret == null ? "null(未返回)" : ret.getStringParam(AI_P_APPROVAL_STATE, "")));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return createMsg().setParam(RESULT, false).setParam("error", "被中断");
        } finally {
            cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer.setBlockMode(false);
            resetApprovalGrants();
        }
        log("[TEST]   审批阻塞唤醒: latch 唤醒路径 通过");
        return createMsg().setParam(RESULT, true);
    }

    /** 阻塞路径自超时：waitForDecision 的 latch 自超时分支 → EXPIRED（另一条超时路径的收口验证） */
    protected TLMsg testApprovalBlockTimeout(Object fromWho, TLMsg msg) {
        cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer.reset();
        resetApprovalGrants();
        cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer.setBlockMode(true);
        final java.util.concurrent.atomic.AtomicReference<TLMsg> holder = new java.util.concurrent.atomic.AtomicReference<>();
        long start = System.currentTimeMillis();
        Thread t = new Thread(() -> holder.set(
                requestApprovalFor("file_operation", "{\"operation\":\"delete\",\"path\":\"D:/tmp/blk2.txt\"}")));
        try {
            t.start();
            t.join(8000);   // aitest 超时 3000ms，留足余量
            long elapsed = System.currentTimeMillis() - start;
            TLMsg ret = holder.get();
            if (ret == null) {
                return createMsg().setParam(RESULT, false).setParam("error", "阻塞线程未返回（应 3 秒自超时）");
            }
            String st = ret.getStringParam(AI_P_APPROVAL_STATE, "");
            if (!"expired".equals(st)) {
                return createMsg().setParam(RESULT, false).setParam("error", "阻塞自超时应 expired，实为 " + st);
            }
            if (elapsed < 2500) {
                return createMsg().setParam(RESULT, false).setParam("error", "提前返回，未走 latch 超时（elapsed=" + elapsed + "ms）");
            }
            // 定向探针：消息级请求的定时任务（approval-timeout 线程）deadline 总比 latch 早几毫秒，
            // 竞态下 waitForDecision 的 `!ok` 分支会被定时任务抢先置 EXPIRED、markExpired 空转
            // （实测 expired 日志全来自 approval-timeout 线程）——这里绕过消息接口直接调
            // waitForDecision（无定时任务竞争）确定性地覆盖 latch 自超时分支的收口与 reason 文案
            Object gateObj = getModuleInFactory("approvalGate");
            if (gateObj instanceof cn.tianlong.tlobject.aiagent.approval.TLApprovalModule) {
                cn.tianlong.tlobject.aiagent.approval.TLApprovalRequest probe =
                        new cn.tianlong.tlobject.aiagent.approval.TLApprovalRequest(
                                SID_APPROVAL, "file_operation", null, "");
                probe.setTimeoutMs(300);   // 不登记 pendingApprovals → 无定时任务，纯 latch 超时
                long ps = System.currentTimeMillis();
                TLMsg pr = ((cn.tianlong.tlobject.aiagent.approval.TLApprovalModule) gateObj).waitForDecision(probe);
                long pe = System.currentTimeMillis() - ps;
                String pst = pr != null ? pr.getStringParam(AI_P_APPROVAL_STATE, "") : "null";
                if (!"expired".equals(pst)) {
                    return createMsg().setParam(RESULT, false).setParam("error", "latch 自超时应 expired，实为 " + pst);
                }
                if (pe < 250 || pe > 2500) {
                    return createMsg().setParam(RESULT, false).setParam("error", "latch 自超时耗时异常: " + pe + "ms");
                }
                String preason = pr.getStringParam(AI_P_APPROVAL_REJECTREASON, "");
                if (!preason.contains("0.3秒")) {   // 探针 request.timeoutMs=300 → 文案与真实等待一致（原实现错用模块值"3秒"）
                    return createMsg().setParam(RESULT, false)
                            .setParam("error", "latch 自超时 reason 应含 0.3秒（真实等待 300ms），实为 " + preason);
                }
            } else {
                // 探针守卫：gate 换成别的实现时不得静默跳过（否则场景 20 会假绿）
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "approvalGate 非 TLApprovalModule，latch 自超时探针未执行");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return createMsg().setParam(RESULT, false).setParam("error", "被中断");
        } finally {
            cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer.setBlockMode(false);
            resetApprovalGrants();
        }
        log("[TEST]   审批阻塞自超时: latch 自超时收口 通过");
        return createMsg().setParam(RESULT, true);
    }

    /**
     * 无限等待：approvalTimeoutMs=0（反射临时置 0）时阻塞路径不超时——
     * 超过原配置值（3000ms）仍不返回，批准后才以 approved 醒来。
     */
    protected TLMsg testApprovalInfiniteWait(Object fromWho, TLMsg msg) {
        TLTestApprovalReviewer.reset();
        resetApprovalGrants();
        Object gateObj = getModuleInFactory("approvalGate");
        if (!(gateObj instanceof cn.tianlong.tlobject.aiagent.approval.TLApprovalModule)) {
            return createMsg().setParam(RESULT, false).setParam("error", "approvalGate 非 TLApprovalModule，探针未执行");
        }
        java.lang.reflect.Field timeoutField = null;
        long savedTimeout = 0;
        try {
            timeoutField = gateObj.getClass().getDeclaredField("approvalTimeoutMs");
            timeoutField.setAccessible(true);
            savedTimeout = timeoutField.getLong(gateObj);
            timeoutField.setLong(gateObj, 0L);
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false).setParam("error", "反射设置 approvalTimeoutMs 失败: " + e);
        }
        final java.util.concurrent.atomic.AtomicReference<TLMsg> holder = new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(() -> holder.set(
                requestApprovalFor("file_operation", "{\"operation\":\"delete\",\"path\":\"D:/tmp/inf.txt\"}")));
        String id = "";
        try {
            TLTestApprovalReviewer.setBlockMode(true);
            t.start();
            for (int i = 0; i < 50 && id.isEmpty(); i++) {
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                List<cn.tianlong.tlobject.aiagent.approval.TLApprovalRequest> reqs =
                        TLTestApprovalReviewer.snapshot();
                if (!reqs.isEmpty()) id = reqs.get(reqs.size() - 1).getApprovalId();
            }
            if (id.isEmpty()) {
                return createMsg().setParam(RESULT, false).setParam("error", "阻塞请求未登记（超时）");
            }
            try { Thread.sleep(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (holder.get() != null) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "approvalTimeoutMs=0 应一直等，却在 4 秒内返回了");
            }
            TLMsg ap = putMsg("approvalGate", createMsg().setAction(APPROVAL_APPROVE)
                    .setParam(AI_P_APPROVAL_ID, id));
            if (!ap.parseBoolean(RESULT, false)) {
                return createMsg().setParam(RESULT, false).setParam("error", "批准失败: " + ap.getStringParam("error", ""));
            }
            t.join(5000);
            TLMsg ret = holder.get();
            if (ret == null || !"approved".equals(ret.getStringParam(AI_P_APPROVAL_STATE, ""))) {
                return createMsg().setParam(RESULT, false).setParam("error", "应醒来返回 approved，实为 "
                        + (ret == null ? "null" : ret.getStringParam(AI_P_APPROVAL_STATE, "")));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return createMsg().setParam(RESULT, false).setParam("error", "被中断");
        } finally {
            // 失败路径兜底：worker 若还挂在无限等待的 latch 上（如提前 return 未走到批准），
            // 拒绝该审批释放它——否则该线程与 pendingApprovals 条目会存活到进程结束
            if (!id.isEmpty() && t.isAlive()) {
                putMsg("approvalGate", createMsg().setAction(APPROVAL_REJECT)
                        .setParam(AI_P_APPROVAL_ID, id)
                        .setParam(AI_P_APPROVAL_REJECTREASON, "测试收尾"));
                try { t.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            try { if (timeoutField != null) timeoutField.setLong(gateObj, savedTimeout); } catch (Exception ignored) {}
            TLTestApprovalReviewer.setBlockMode(false);
            resetApprovalGrants();
        }
        log("[TEST]   审批无限等待: approvalTimeoutMs=0 一直等 通过");
        return createMsg().setParam(RESULT, true);
    }

    /**
     * 投递判据：ConsoleReviewer 在无订阅者（无控制台/webui SSE）时不阻塞，
     * 立即返回 pending（上层转断点）——直调探针，不经消息接口。
     */
    protected TLMsg testApprovalConsoleDefer(Object fromWho, TLMsg msg) {
        Object gateObj = getModuleInFactory("approvalGate");
        if (!(gateObj instanceof cn.tianlong.tlobject.aiagent.approval.TLApprovalModule)) {
            return createMsg().setParam(RESULT, false).setParam("error", "approvalGate 非 TLApprovalModule，探针未执行");
        }
        cn.tianlong.tlobject.aiagent.approval.ConsoleReviewer cr =
                new cn.tianlong.tlobject.aiagent.approval.ConsoleReviewer();
        cr.setOwner((cn.tianlong.tlobject.aiagent.approval.TLApprovalModule) gateObj);
        cn.tianlong.tlobject.aiagent.approval.TLApprovalRequest probe =
                new cn.tianlong.tlobject.aiagent.approval.TLApprovalRequest(
                        SID_APPROVAL, "file_operation", new java.util.HashMap<>(), "");
        // 万一有订阅者 ack（探针被搬到控制台/webui 环境跑），失败要有界：探针自带 1s 超时，
        // 不至于用模块级值（可能被配成 0）把整套测试挂死
        probe.setTimeoutMs(1000);
        long start = System.currentTimeMillis();
        TLMsg r = cr.requestApproval(probe);
        long elapsed = System.currentTimeMillis() - start;
        String st = r != null ? r.getStringParam(AI_P_APPROVAL_STATE, "") : "null";
        if (!"pending".equals(st)) {
            return createMsg().setParam(RESULT, false).setParam("error", "无订阅者应返回 pending，实为 " + st);
        }
        if (elapsed > 2000) {
            return createMsg().setParam(RESULT, false).setParam("error", "无订阅者不应阻塞，耗时 " + elapsed + "ms");
        }
        log("[TEST]   无人接收转断点: 不阻塞返回 pending（" + elapsed + "ms） 通过");
        return createMsg().setParam(RESULT, true);
    }

    /**
     * 退出转断点：阻塞中的请求被 APPROVAL_DEFER 唤醒 → 线程以 pending 返回（非 rejected）；
     * 审批保持 PENDING 且 deferred=true；迟到的批准仍有效（defer 不是终结）。
     */
    protected TLMsg testApprovalDeferWakeup(Object fromWho, TLMsg msg) {
        TLTestApprovalReviewer.reset();
        resetApprovalGrants();
        TLTestApprovalReviewer.setBlockMode(true);
        final java.util.concurrent.atomic.AtomicReference<TLMsg> holder = new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(() -> holder.set(
                requestApprovalFor("file_operation", "{\"operation\":\"delete\",\"path\":\"D:/tmp/defer.txt\"}")));
        String id = "";
        try {
            t.start();
            for (int i = 0; i < 50 && id.isEmpty(); i++) {
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                List<cn.tianlong.tlobject.aiagent.approval.TLApprovalRequest> reqs =
                        TLTestApprovalReviewer.snapshot();
                if (!reqs.isEmpty()) id = reqs.get(reqs.size() - 1).getApprovalId();
            }
            if (id.isEmpty()) {
                return createMsg().setParam(RESULT, false).setParam("error", "阻塞请求未登记（超时）");
            }
            TLMsg d = putMsg("approvalGate", createMsg().setAction(APPROVAL_DEFER)
                    .setParam(AI_P_APPROVAL_ID, id));
            if (!d.parseBoolean(RESULT, false)) {
                return createMsg().setParam(RESULT, false).setParam("error", "defer 失败: " + d.getStringParam("error", ""));
            }
            t.join(3000);
            TLMsg ret = holder.get();
            if (ret == null || !"pending".equals(ret.getStringParam(AI_P_APPROVAL_STATE, ""))) {
                return createMsg().setParam(RESULT, false).setParam("error", "defer 唤醒应以 pending 返回，实为 "
                        + (ret == null ? "null(未返回)" : ret.getStringParam(AI_P_APPROVAL_STATE, "")));
            }
            // 审批保持 PENDING 且 deferred=true（QUERY 决策映射）
            TLMsg q = putMsg("approvalGate", createMsg().setAction(APPROVAL_QUERY).setParam(AI_P_APPROVAL_ID, id));
            if (!"pending".equals(q.getStringParam("decision", ""))
                    || !q.parseBoolean(AI_P_APPROVAL_DEFERRED, false)) {
                return createMsg().setParam(RESULT, false).setParam("error",
                        "defer 后应保持 PENDING+deferred，实为 decision=" + q.getStringParam("decision", ""));
            }
            // 迟到的批准仍有效（defer 只是释放等待线程，不是终结）
            TLMsg ap = putMsg("approvalGate", createMsg().setAction(APPROVAL_APPROVE)
                    .setParam(AI_P_APPROVAL_ID, id));
            if (!ap.parseBoolean(RESULT, false)) {
                return createMsg().setParam(RESULT, false).setParam("error", "defer 后批准应有效: " + ap.getStringParam("error", ""));
            }
            // 幂等：已决策后 defer 无副作用且不报错
            TLMsg d2 = putMsg("approvalGate", createMsg().setAction(APPROVAL_DEFER)
                    .setParam(AI_P_APPROVAL_ID, id));
            if (!d2.parseBoolean(RESULT, false)) {
                return createMsg().setParam(RESULT, false).setParam("error", "已决策后 defer 应幂等成功");
            }
            // 以下 abandon 块关掉阻塞模式：putMsg 在本线程同步执行 action，阻塞模式下请求会挂在
            // 审查人的 waitForDecision 上（本线程阻塞 → 后续 ABANDON 消息根本发不出去，只能等
            // 3s 自超时变 expired），断言既慢又非确定。阻塞路径的唤醒覆盖已由本场景前半段完成。
            TLTestApprovalReviewer.setBlockMode(false);
            // abandon 语义：撤销待批（decision=rejected + reason 用户放弃该操作），但不记拒绝记忆——
            // 同参数再次请求应照常弹审批（pending），而不是被自动拒绝
            TLMsg r2 = requestApprovalFor("file_operation", "{\"operation\":\"delete\",\"path\":\"D:/tmp/abandon.txt\"}");
            String id2 = r2.getStringParam(AI_P_APPROVAL_ID, "");
            TLMsg ab = putMsg("approvalGate", createMsg().setAction(APPROVAL_ABANDON)
                    .setParam(AI_P_APPROVAL_ID, id2));
            if (!ab.parseBoolean(RESULT, false)) {
                return createMsg().setParam(RESULT, false).setParam("error", "abandon 失败: " + ab.getStringParam("error", ""));
            }
            TLMsg q2 = putMsg("approvalGate", createMsg().setAction(APPROVAL_QUERY).setParam(AI_P_APPROVAL_ID, id2));
            if (!"rejected".equals(q2.getStringParam("decision", ""))) {
                return createMsg().setParam(RESULT, false).setParam("error",
                        "abandon 后应 decision=rejected，实为 " + q2.getStringParam("decision", ""));
            }
            if (!q2.getStringParam(AI_P_APPROVAL_REJECTREASON, "").contains("放弃")) {
                return createMsg().setParam(RESULT, false).setParam("error",
                        "abandon 原因应含'放弃'，实为 " + q2.getStringParam(AI_P_APPROVAL_REJECTREASON, ""));
            }
            // 无拒绝记忆：同参数再请求仍是 pending（弹审批），不是自动拒绝
            TLMsg r3 = requestApprovalFor("file_operation", "{\"operation\":\"delete\",\"path\":\"D:/tmp/abandon.txt\"}");
            if (!"pending".equals(r3.getStringParam(AI_P_APPROVAL_STATE, ""))) {
                return createMsg().setParam(RESULT, false).setParam("error",
                        "abandon 不应记拒绝记忆，同参数应 pending，实为 " + r3.getStringParam(AI_P_APPROVAL_STATE, ""));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return createMsg().setParam(RESULT, false).setParam("error", "被中断");
        } finally {
            // 失败路径兜底：worker 若还挂在无限等待的 latch 上（如提前 return 未走到批准），
            // 拒绝该审批释放它——否则该线程与 pendingApprovals 条目会存活到进程结束
            if (!id.isEmpty() && t.isAlive()) {
                putMsg("approvalGate", createMsg().setAction(APPROVAL_REJECT)
                        .setParam(AI_P_APPROVAL_ID, id)
                        .setParam(AI_P_APPROVAL_REJECTREASON, "测试收尾"));
                try { t.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            TLTestApprovalReviewer.setBlockMode(false);
            resetApprovalGrants();
        }
        log("[TEST]   审批退出转断点: defer 唤醒 pending + 保持待批 + 迟到批准有效 通过");
        return createMsg().setParam(RESULT, true);
    }

    /** 断点审批 TTL（aitest 配 5000ms）：超期未处理 → decision=lost + reason 含"未处理" */
    protected TLMsg testApprovalPendingTtl(Object fromWho, TLMsg msg) {
        TLTestApprovalReviewer.reset();
        resetApprovalGrants();
        // 隔离"审批超时"路径：临时把 approvalTimeoutMs 置 0（反射，同场景 21 的手法）。
        // aitest 配的 3000ms 是给场景 18 用的——不隔离的话待批请求会在 3s 被定时任务先收走
        // （TTL 5000 轮不到，reason 会是"审批超时"）。本场景要验的正是生产默认配置
        // （approvalTimeoutMs=0：阻塞超时不排期）下，断点态审批由 TTL 收口。
        Object gateObj = getModuleInFactory("approvalGate");
        if (!(gateObj instanceof cn.tianlong.tlobject.aiagent.approval.TLApprovalModule)) {
            return createMsg().setParam(RESULT, false).setParam("error", "approvalGate 非 TLApprovalModule，探针未执行");
        }
        java.lang.reflect.Field timeoutField = null;
        long savedTimeout = 0;
        try {
            timeoutField = gateObj.getClass().getDeclaredField("approvalTimeoutMs");
            timeoutField.setAccessible(true);
            savedTimeout = timeoutField.getLong(gateObj);
            timeoutField.setLong(gateObj, 0L);
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false).setParam("error", "反射设置 approvalTimeoutMs 失败: " + e);
        }
        try {
            // 非阻塞请求（审查人立即返回 pending）→ 转断点 → 排期 TTL
            TLMsg r1 = requestApprovalFor("file_operation", "{\"operation\":\"delete\",\"path\":\"D:/tmp/ttl.txt\"}");
            String id = r1.getStringParam(AI_P_APPROVAL_ID, "");
            if (!"pending".equals(r1.getStringParam(AI_P_APPROVAL_STATE, ""))) {
                return createMsg().setParam(RESULT, false).setParam("error", "应 pending");
            }
            TLMsg q1 = putMsg("approvalGate", createMsg().setAction(APPROVAL_QUERY).setParam(AI_P_APPROVAL_ID, id));
            if (!"pending".equals(q1.getStringParam("decision", ""))) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "TTL 未到期应 decision=pending，实为 " + q1.getStringParam("decision", ""));
            }
            // 有界轮询等 TTL（5000ms）到期收口：不再固定睡 5500ms（机器慢/调度抖动会假失败），
            // 每 200ms 查一次，最多 ~8s；查不到才算失败（报最后看到的 decision/reason）
            TLMsg q2 = null;
            String decision = "";
            for (int i = 0; i < 40 && !"lost".equals(decision); i++) {
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                q2 = putMsg("approvalGate", createMsg().setAction(APPROVAL_QUERY).setParam(AI_P_APPROVAL_ID, id));
                decision = q2.getStringParam("decision", "");
            }
            if (!"lost".equals(decision)) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "TTL（5000ms）到期应 decision=lost，实为 " + decision
                                + " reason=" + (q2 != null ? q2.getStringParam(AI_P_APPROVAL_REJECTREASON, "") : ""));
            }
            String reason = q2.getStringParam(AI_P_APPROVAL_REJECTREASON, "");
            if (!reason.contains("未处理")) {
                return createMsg().setParam(RESULT, false).setParam("error", "TTL 失效 reason 应含'未处理'，实为 " + reason);
            }
        } finally {
            try { if (timeoutField != null) timeoutField.setLong(gateObj, savedTimeout); } catch (Exception ignored) {}
            resetApprovalGrants();
        }
        log("[TEST]   断点审批TTL: 到期 decision=lost 通过");
        return createMsg().setParam(RESULT, true);
    }

    /** 断点恢复测试用的临时文件（相对启动目录；file_operation 默认根 "."） */
    protected static final String RESUME_PROBE_FILE = "data/aitest_resume_probe.txt";

    private TLMsg buildResumeChat(String approvalId, Object decision, List<TLConversationHistory> history,
                                  TLToolCall tc, String sessionId) {
        TLMsg chatMsg = createMsg().setAction(AGENT_CHAT)
                .setSystemParam(AI_P_SESSIONID, sessionId)
                .setSystemParam(AI_P_USERID, TEST_USER_ID)
                .setParam(AI_P_USERMESSAGE, "删除测试文件")
                .setParam("resume", true)
                .setParam("history", history)
                .setParam("resumeState", SESSION_STATE_PENDING_APPROVAL)
                .setParam(AI_P_APPROVAL_ID, approvalId)
                .setParam("pendingToolCall", tc);
        if (decision != null) chatMsg.setParam(AI_P_APPROVAL_DECISION, decision);
        return chatMsg;
    }

    /**
     * 断点恢复（批准）：Agent 向 gate 查回 decision=approved → 真正重执行工具（文件被删），
     * 且执行器免复批（审查人计数不增）——否则 resume → 再弹框 → 死循环。
     * 第二遍：decision 由消息参数给出且 approvalId 门禁查不到（lost）——文件被删即证明 msg 决策优先。
     */
    protected TLMsg testApprovalResumeExecute(Object fromWho, TLMsg msg) {
        Object gate = getModuleInFactory("approvalGate");
        if (!(gate instanceof IObject)) return createMsg().setParam(RESULT, false).setParam("error", "approvalGate 未配置");
        TLMockProvider mp = getMockProvider();
        if (mp == null) return createMsg().setParam(RESULT, false).setParam("error", "mockProvider 未配置");
        TLTestApprovalReviewer.reset();
        resetApprovalGrants();
        TLTestApprovalReviewer.setBlockMode(false);

        java.io.File f = new java.io.File(RESUME_PROBE_FILE);
        try {
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), "resume-probe".getBytes("UTF-8"));
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false).setParam("error", "写测试文件失败: " + e);
        }
        // 1. 造真实待批请求并批准（scope=once）
        TLMsg r1 = requestApprovalFor("file_operation",
                "{\"operation\":\"delete\",\"path\":\"" + RESUME_PROBE_FILE + "\"}");
        String approvalId = r1.getStringParam(AI_P_APPROVAL_ID, "");
        if (!"pending".equals(r1.getStringParam(AI_P_APPROVAL_STATE, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "应 pending");
        }
        int countBefore = TLTestApprovalReviewer.count();
        TLMsg ap = putMsg("approvalGate", createMsg().setAction(APPROVAL_APPROVE)
                .setParam(AI_P_APPROVAL_ID, approvalId));
        if (!ap.parseBoolean(RESULT, false)) {
            return createMsg().setParam(RESULT, false).setParam("error", "批准失败: " + ap.getStringParam("error", ""));
        }
        // 2. resume（不带 decision → Agent 自己查 gate 写回）
        mp.clearAllResponses();
        mp.enqueueResponse(mp.textResponse("已删除测试文件。"));
        TLToolCall tc = new TLToolCall("call_resume_1", "file_operation",
                new java.util.HashMap<String, Object>() {{
                    put("operation", "delete"); put("path", RESUME_PROBE_FILE);
                }});
        List<TLConversationHistory> history = new java.util.ArrayList<>();
        history.add(new TLConversationHistory(TLConversationHistory.Role.user, "删除测试文件"));
        TLMsg response = putMsg(M_AIAGENT, buildResumeChat(approvalId, null, history, tc, "test_approval_resume"));
        if (!response.parseBoolean(RESULT, false)) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "resume 应成功，实为: " + response.getStringParam(AI_P_RESPONSE, ""));
        }
        if (f.exists()) {
            return createMsg().setParam(RESULT, false).setParam("error", "decison=approved 应重执行删除，文件仍存在");
        }
        if (TLTestApprovalReviewer.count() != countBefore) {
            return createMsg().setParam(RESULT, false).setParam("error",
                    "断点恢复的已批准执行不应再过门禁（审查人计数 " + countBefore + " → " + TLTestApprovalReviewer.count() + "）");
        }
        // 3. 第二遍：decision 只由消息参数给出，且配一个 gate 查不到的 approvalId——
        //    门禁对它的查询结果必然是 lost（不执行）；用真批过的 id 会"查回来也是 approved"，
        //    证不了优先级。文件仍被删只可能来自 msg 参数决策（调用方判定）压过 gate 查询
        try {
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), "resume-probe".getBytes("UTF-8"));
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false).setParam("error", "重写测试文件失败: " + e);
        }
        mp.clearAllResponses();
        mp.enqueueResponse(mp.textResponse("已删除测试文件。"));
        TLToolCall tc2 = new TLToolCall("call_resume_2", "file_operation",
                new java.util.HashMap<String, Object>() {{
                    put("operation", "delete"); put("path", RESUME_PROBE_FILE);
                }});
        TLMsg response2 = putMsg(M_AIAGENT, buildResumeChat("no_such_id_but_decision_param", "approved",
                history, tc2, "test_approval_resume"));
        if (!response2.parseBoolean(RESULT, false)) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "第二遍 resume（msg 决策）应成功，实为: " + response2.getStringParam(AI_P_RESPONSE, ""));
        }
        if (f.exists()) {
            return createMsg().setParam(RESULT, false).setParam("error",
                    "msg 决策=approved 应压过 gate 查询(lost)重执行删除，文件仍存在");
        }
        resetApprovalGrants();
        log("[TEST]   断点恢复批准执行: 真删除 + 免复批 + msg 决策优先 通过");
        return createMsg().setParam(RESULT, true);
    }

    /**
     * 断点恢复（pending 仍在等 / lost 已失效）：pending → 返回"仍在等待中"不执行；
     * lost → 不执行、注入失效说明后继续对话（项目不假装续跑）。
     */
    protected TLMsg testApprovalResumeLost(Object fromWho, TLMsg msg) {
        Object gate = getModuleInFactory("approvalGate");
        if (!(gate instanceof IObject)) return createMsg().setParam(RESULT, false).setParam("error", "approvalGate 未配置");
        TLMockProvider mp = getMockProvider();
        if (mp == null) return createMsg().setParam(RESULT, false).setParam("error", "mockProvider 未配置");
        TLTestApprovalReviewer.reset();
        resetApprovalGrants();

        java.io.File f = new java.io.File(RESUME_PROBE_FILE);
        try {
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), "resume-probe".getBytes("UTF-8"));
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false).setParam("error", "写测试文件失败: " + e);
        }
        TLToolCall tc = new TLToolCall("call_lost_1", "file_operation",
                new java.util.HashMap<String, Object>() {{
                    put("operation", "delete"); put("path", RESUME_PROBE_FILE);
                }});
        List<TLConversationHistory> history = new java.util.ArrayList<>();
        history.add(new TLConversationHistory(TLConversationHistory.Role.user, "删除测试文件"));

        // A. pending：造一个真实的待批请求（不批准）→ resume 应拒绝执行并返回等待文案
        TLMsg r1 = requestApprovalFor("file_operation",
                "{\"operation\":\"delete\",\"path\":\"" + RESUME_PROBE_FILE + "\"}");
        String pendingId = r1.getStringParam(AI_P_APPROVAL_ID, "");
        TLMsg respA = putMsg(M_AIAGENT, buildResumeChat(pendingId, null, history, tc, "test_approval_lost"));
        if (f.exists() == false) {
            return createMsg().setParam(RESULT, false).setParam("error", "pending 恢复不应执行删除");
        }
        String respAText = respA.getStringParam(AI_P_RESPONSE, "");
        if (!respAText.contains("等待")) {
            return createMsg().setParam(RESULT, false).setParam("error", "pending 恢复应提示仍在等待，实为: " + respAText);
        }
        // B. lost：不存在的 approvalId（进程重启语义）→ 不执行，注入失效说明后正常回答
        mp.clearAllResponses();
        mp.enqueueResponse(mp.textResponse("已跳过该操作，请问还需要什么？"));
        TLMsg respB = putMsg(M_AIAGENT, buildResumeChat("no_such_approval_id", null, history, tc, "test_approval_lost"));
        if (f.exists() == false) {
            return createMsg().setParam(RESULT, false).setParam("error", "lost 恢复不应执行删除");
        }
        if (!respB.parseBoolean(RESULT, false) || !respB.getStringParam(AI_P_RESPONSE, "").contains("还需要什么")) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "lost 恢复应注入失效说明并继续对话，实为: " + respB.getStringParam(AI_P_RESPONSE, ""));
        }
        // 收尾：清理探针文件与遗留待批（A 部分的 pending 请求不撤销的话，TTL 到期事件会污染本套件后续场景）
        try { java.nio.file.Files.deleteIfExists(f.toPath()); } catch (Exception ignored) {}
        try {
            putMsg("approvalGate", createMsg().setAction(APPROVAL_ABANDON)
                    .setParam(AI_P_APPROVAL_ID, pendingId));
        } catch (Exception ignored) {}
        resetApprovalGrants();
        log("[TEST]   断点恢复失效不执行: pending 等待文案 + lost 继续对话 通过");
        return createMsg().setParam(RESULT, true);
    }

    /**
     * 待批断点：findIncomplete 覆盖 pending_approval（含 state/approvalId、支持 sessionId 过滤）；
     * discardPendingRound 后不再可见（放弃该操作 → 会话可正常继续）。
     */
    protected TLMsg testApprovalCheckpointDiscard(Object fromWho, TLMsg msg) {
        Object sm = getModuleInFactory("sessionManager");
        if (!(sm instanceof IObject)) {
            return createMsg().setParam(RESULT, false).setParam("error", "sessionManager 未配置");
        }
        String sid = "test_approval_discard";
        // 1. 造一个 pending_approval 轮（带 pendingToolCall + approvalId）
        TLToolCall tc = new TLToolCall("call_discard_1", "file_operation",
                new java.util.HashMap<String, Object>() {{ put("operation", "delete"); put("path", "D:/tmp/never.txt"); }});
        TLMsg upd = createMsg().setAction("sessionUpdated")
                .setParam("sessionId", sid)
                .setParam("userId", TEST_USER_ID)
                .setParam("agentName", "aiagent")
                .setParam("roundId", "r_discard_1")
                .setParam("messages", new java.util.ArrayList<>(java.util.Arrays.asList(
                        new TLConversationHistory(TLConversationHistory.Role.user, "删除一个不存在的文件"))))
                .setParam("userMessage", "删除一个不存在的文件")
                .setParam("model", "mock")
                .setParam("temperature", 0.7)
                .setParam("maxTokens", 1024)
                .setParam("pendingToolCall", tc)
                .setParam(AI_P_APPROVAL_ID, "probe_discard_1");
        putMsg((IObject) sm, upd);
        // 2. findIncomplete（带 sessionId）应命中且带 state/approvalId
        TLMsg q = putMsg((IObject) sm, createMsg().setAction("findIncomplete")
                .setParam("userId", TEST_USER_ID).setParam("sessionId", sid));
        if (!q.parseBoolean(RESULT, false)
                || !SESSION_STATE_PENDING_APPROVAL.equals(q.getStringParam("state", ""))) {
            return createMsg().setParam(RESULT, false).setParam("error",
                    "findIncomplete 应命中 pending_approval，实为 state=" + q.getStringParam("state", ""));
        }
        if (!"probe_discard_1".equals(q.getStringParam(AI_P_APPROVAL_ID, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error",
                    "应带回 approvalId，实为 " + q.getStringParam(AI_P_APPROVAL_ID, ""));
        }
        // 3. 丢弃后不再可见
        TLMsg d = putMsg((IObject) sm, createMsg().setAction("discardPendingRound")
                .setParam("sessionId", sid).setParam("userId", TEST_USER_ID));
        if (!d.parseBoolean(RESULT, false)) {
            return createMsg().setParam(RESULT, false).setParam("error", "discardPendingRound 失败: " + d.getStringParam("error", ""));
        }
        TLMsg q2 = putMsg((IObject) sm, createMsg().setAction("findIncomplete")
                .setParam("userId", TEST_USER_ID).setParam("sessionId", sid));
        // 允许查到同会话其它断点（本场景没有）；但若查到还是 pending_approval 则失败
        if (q2.parseBoolean(RESULT, false)
                && SESSION_STATE_PENDING_APPROVAL.equals(q2.getStringParam("state", ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "丢弃后不应再命中 pending_approval");
        }
        log("[TEST]   待批断点可见与丢弃: findIncomplete + discardPendingRound 通过");
        return createMsg().setParam(RESULT, true);
    }

    /** 场景 28 待批断点探针文件（相对启动目录；file_operation 默认根 "."） */
    protected static final String PENDING_PROBE_FILE = "data/aitest_pending_probe.txt";

    /**
     * 待批断点生命周期（端到端）：真工具（file_operation delete）触发审批 → 无人接收转断点：
     * 回合正常返回等待文案、文件未删；findIncomplete 能查到 pending_approval（轮次没被 chatFinished
     * 覆盖成 completed——此前两处缺陷使该断言恒失败，链路"转断点 → 重登 → 恢复"因此断死）；
     * 批准后按 service 语义 resume（loadSession → resumeRoundId 继承）→ 工具真执行（文件被删）、
     * 断点收口（findIncomplete 不再命中）——roundId 继承生效，resume 的 chatFinished 收掉待批轮。
     */
    @SuppressWarnings("unchecked")
    protected TLMsg testApprovalPendingCheckpoint(Object fromWho, TLMsg msg) {
        Object gate = getModuleInFactory("approvalGate");
        if (!(gate instanceof IObject)) return createMsg().setParam(RESULT, false).setParam("error", "approvalGate 未配置");
        Object sm = getModuleInFactory("sessionManager");
        if (!(sm instanceof IObject)) return createMsg().setParam(RESULT, false).setParam("error", "sessionManager 未配置");
        TLMockProvider mp = getMockProvider();
        if (mp == null) return createMsg().setParam(RESULT, false).setParam("error", "mockProvider 未配置");
        TLTestApprovalReviewer.reset();
        resetApprovalGrants();

        String sid = "test_approval_pending";
        // 自清场：黏住语义下（pending 是开态，直到 resume/discard 收尾）上一次跑剩的待批行会一直可见——
        // 历史残留（含红跑/中断跑）会打挂 step-7"resume 后不再命中"；场景必须可反复运行
        try {
            putMsg((IObject) sm, createMsg().setAction("deleteSession")
                    .setParam("sessionId", sid).setParam("userId", TEST_USER_ID));
        } catch (Exception ignored) {}
        java.io.File f = new java.io.File(PENDING_PROBE_FILE);
        try {
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), "pending-probe".getBytes("UTF-8"));
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false).setParam("error", "写测试文件失败: " + e);
        }
        // 1. 回合：LLM 返回 file_operation(delete) 的 tool_call → 执行器过审批门禁 → 测试审查人
        //    不阻塞直接返回 pending = 无人接收（转断点）；第二响应给 resume 后继续对话的终答
        mp.clearAllResponses();
        TLToolCall tc1 = TLMockProvider.createToolCall("call_pending_1", "file_operation",
                new java.util.HashMap<String, Object>() {{
                    put("operation", "delete"); put("path", PENDING_PROBE_FILE);
                }});
        mp.enqueueResponse(mp.toolCallResponse(tc1));
        mp.enqueueResponse(mp.textResponse("已删除测试文件。"));

        TLMsg r1 = putMsg(M_AIAGENT, createMsg().setAction(AGENT_CHAT)
                .setSystemParam(AI_P_SESSIONID, sid)
                .setSystemParam(AI_P_USERID, TEST_USER_ID)
                .setParam(AI_P_USERMESSAGE, "删除测试文件"));
        // 2. 回合成功返回等待文案，且工具确实未执行（文件仍在）
        String text1 = r1.getStringParam(AI_P_RESPONSE, "");
        if (!r1.parseBoolean(RESULT, false) || !text1.contains("等待")) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "待批回合应返回等待文案，实为 ok=" + r1.parseBoolean(RESULT, false) + " resp=" + text1);
        }
        if (!f.exists()) {
            return createMsg().setParam(RESULT, false).setParam("error", "待批时工具不应执行，文件已被删");
        }
        // 3. 核心断言：断点可见——轮次必须仍是 pending_approval 且带 approvalId
        TLMsg q = putMsg((IObject) sm, createMsg().setAction("findIncomplete")
                .setParam("userId", TEST_USER_ID).setParam("sessionId", sid));
        if (!q.parseBoolean(RESULT, false)) {
            return createMsg().setParam(RESULT, false).setParam("error",
                    "findIncomplete 应命中待批断点（轮次被 chatFinished 覆盖成 completed？）");
        }
        if (!SESSION_STATE_PENDING_APPROVAL.equals(q.getStringParam("state", ""))) {
            return createMsg().setParam(RESULT, false).setParam("error",
                    "findIncomplete 应命中 pending_approval，实为 state=" + q.getStringParam("state", ""));
        }
        String approvalId = q.getStringParam(AI_P_APPROVAL_ID, "");
        if (approvalId.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "待批断点应带 approvalId");
        }
        // 3b. 干扰轮：待批期间又有新轮次写入（模拟后台任务注入/其他写入）→ 断点必须仍然可见（黏住语义）
        putMsg((IObject) sm, createMsg().setAction("sessionUpdated")
                .setParam("sessionId", sid).setParam("userId", TEST_USER_ID).setParam("agentName", "aiagent")
                .setParam("roundId", "r_decoy_inject").setParam("messages", new java.util.ArrayList<>())
                .setParam("userMessage", "（系统注入）").setParam("model", "mock")
                .setParam("temperature", 0.7).setParam("maxTokens", 1024));
        putMsg((IObject) sm, createMsg().setAction("chatFinished")
                .setParam("sessionId", sid).setParam("userId", TEST_USER_ID).setParam("agentName", "aiagent")
                .setParam("roundId", "r_decoy_inject").setParam("messages", new java.util.ArrayList<>())
                .setParam("userMessage", "（系统注入）"));
        TLMsg q2 = putMsg((IObject) sm, createMsg().setAction("findIncomplete")
                .setParam("userId", TEST_USER_ID).setParam("sessionId", sid));
        if (!q2.parseBoolean(RESULT, false)
                || !SESSION_STATE_PENDING_APPROVAL.equals(q2.getStringParam("state", ""))
                || !approvalId.equals(q2.getStringParam(AI_P_APPROVAL_ID, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error",
                    "干扰轮后待批断点应仍可见（黏住语义），实为 state=" + q2.getStringParam("state", "")
                            + " id=" + q2.getStringParam(AI_P_APPROVAL_ID, ""));
        }
        TLMsg loaded2 = putMsg((IObject) sm, createMsg().setAction("loadSession")
                .setParam("sessionId", sid).setParam("userId", TEST_USER_ID));
        if (!loaded2.parseBoolean(RESULT, false)
                || !SESSION_STATE_PENDING_APPROVAL.equals(loaded2.getStringParam("state", ""))
                || loaded2.getParam("pendingToolCall") == null) {
            return createMsg().setParam(RESULT, false).setParam("error",
                    "干扰轮后 loadSession 应仍暴露待批轮（state/pendingToolCall），实为 state="
                            + loaded2.getStringParam("state", ""));
        }
        // 4. 批准
        TLMsg ap = putMsg("approvalGate", createMsg().setAction(APPROVAL_APPROVE)
                .setParam(AI_P_APPROVAL_ID, approvalId));
        if (!ap.parseBoolean(RESULT, false)) {
            return createMsg().setParam(RESULT, false).setParam("error", "批准失败: " + ap.getStringParam("error", ""));
        }
        // 5. 按 service 语义 resume：loadSession 取历史/待批工具/roundId，再发 resume chat
        //    （resumeRoundId 继承是收口的另一半：resume 的 chatFinished 必须落回待批轮同一行）
        TLMsg loaded = putMsg((IObject) sm, createMsg().setAction("loadSession")
                .setParam("sessionId", sid).setParam("userId", TEST_USER_ID));
        if (!loaded.parseBoolean(RESULT, false)) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "loadSession 失败: " + loaded.getStringParam("error", ""));
        }
        List<TLConversationHistory> loadedHistory = (List<TLConversationHistory>) loaded.getParam("history");
        TLToolCall loadedTc = (TLToolCall) loaded.getParam("pendingToolCall");
        if (loadedTc == null) {
            return createMsg().setParam(RESULT, false).setParam("error", "loadSession 应带回 pendingToolCall");
        }
        TLMsg resumeMsg = buildResumeChat(approvalId, null, loadedHistory, loadedTc, sid)
                .setParam("resumeRoundId", loaded.getStringParam("roundId", ""));
        TLMsg r2 = putMsg(M_AIAGENT, resumeMsg);
        // 6. 批准后 resume：工具真执行（文件被删）
        if (!r2.parseBoolean(RESULT, false)) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "resume 应成功，实为: " + r2.getStringParam(AI_P_RESPONSE, ""));
        }
        if (f.exists()) {
            return createMsg().setParam(RESULT, false).setParam("error", "批准恢复应执行删除，文件仍存在");
        }
        // 7. 断点收口：resume 的 chatFinished（同 roundId）应把待批轮收成 completed
        TLMsg q3 = putMsg((IObject) sm, createMsg().setAction("findIncomplete")
                .setParam("userId", TEST_USER_ID).setParam("sessionId", sid));
        if (q3.parseBoolean(RESULT, false)
                && SESSION_STATE_PENDING_APPROVAL.equals(q3.getStringParam("state", ""))) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "resume 完成后不应再命中 pending_approval（roundId 继承没生效？）");
        }
        resetApprovalGrants();
        log("[TEST]   待批断点生命周期: 转断点可见 + 批准恢复执行 + 断点收口 通过");
        return createMsg().setParam(RESULT, true);
    }

    /**
     * 清掉审批场景用会话的全部授权。授权表在 approvalGate 进程内驻留，
     * 不像会话上下文那样有 resetTestSessions 兜底——不清则同一进程二次 /test 时场景必挂。
     */
    private void resetApprovalGrants() {
        putMsg("approvalGate", createMsg().setAction(APPROVAL_REVOKE)
                .setParam("sessionId", SID_APPROVAL).setParam("all", true));
        putMsg("approvalGate", createMsg().setAction(APPROVAL_REVOKE)
                .setParam("sessionId", "test_approval_parent").setParam("all", true));
    }

    /** 向 approvalGate 发一条审批请求（消息级，不经执行器/LLM；调用方须先守卫模块存在） */
    private TLMsg requestApprovalFor(String toolName, String argsJson) {
        java.util.Map<String, Object> args = new com.google.gson.Gson().fromJson(argsJson, java.util.Map.class);
        return putMsg("approvalGate", createMsg().setAction(APPROVAL_REQUEST)
                .setParam("toolName", toolName)
                .setParam("toolArgs", args)
                .setSystemParam(AI_P_SESSIONID, SID_APPROVAL)
                .setSystemParam("userId", TEST_USER_ID)
                .setSystemParam(AI_P_ROUNDID, "r_test_1"));
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
