package cn.tianlong.tlobject.aiagent.evals;

import cn.tianlong.tlobject.aiagent.*;
import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Agent 评测主控模块（框架级）。
 * 加载评测用例 JSON → 逐用例调用 TLAiAgent.chat() → Judge 评判 → 出报告。
 *
 * 动作：
 *   runEval / runEvalSuite / runEvalByName / listEvalCases / runQuickEval
 *
 * 创建日期：2026/7/29
 * 作者:tianlong
 */
public class TLEvalsModule extends TLBaseModule implements TLAiAgentParamString {

    private String evalCaseDir = "conf/demo/aiagent/evals/cases/";
    private String reportOutputDir = "data/evals/reports/";
    private String targetAgent = "aiagent";
    private String judgeProvider = "openAiProvider";
    /**
     * 裁判调用用的推理模式（默认显式关）。
     * 裁判只要吐一个短 JSON，推理既没用又抢 max_tokens：实测裁判调用（max_tokens 只有 512）
     * 被推理吃光后 content 是空串，拿到的"判决"其实是模型的内心独白 → 一律判成
     * "裁判输出无法解析为 JSON"，用例白白变红。provider 不吃 thinking 字段时可配成 off。
     */
    private String judgeReasoningMode = AI_P_REASONING_MODE_DISABLED;
    /**
     * 评测会话的身份标识。
     * 评测消息不带 userId 时，框架会拿 sessionId 顶替（TLAiAgent 里 currentChatUserId 的兜底），
     * 而评测的 sessionId 是 "eval_用例id_时间戳"——每条用例、每次运行都不同，
     * 于是 data/ 顶层被 eval_xxx 目录铺满（路径规则 data/{userId}/traces/{sessionId}/）。
     * 给评测一个固定身份，产物就都归到 data/evals/ 下。
     */
    private static final String EVAL_USER_ID = "evals";
    /** 是否与上一次报告对比（回归检测）。找到基准才谈得上回归，找不到时报告里标 baselineFound=false */
    private boolean compareBaseline = true;
    /** 指定基准报告（路径或报告目录下的文件名）；留空则自动取报告目录里最近一份 */
    private String baselineFile = "";
    /** 门禁：通过率阈值（<=0 表示不启用）；回归数上限（<0 表示不检查） */
    private double gatePassRate = 0;
    /** 默认 -1 = 不检查回归。不能用 0：0 满足 ">=0" 会让回归门禁默认就是开的，
        与"门禁默认不启用"矛盾，而且首次运行没有基准会直接判失败 */
    private int gateMaxRegressions = -1;
    /**
     * @deprecated 已不再使用。取/清会话历史都改走目标 Agent 的黑盒接口
     * （AGENT_GETCONTEXT / AGENT_CLEARCONTEXT）—— 直接按裸名寻址拿到的是工厂里的 aiContext 单例，
     * 不是被评测 Agent 的私有 context。保留字段只为兼容老配置里的 contextModule 项。
     */
    @Deprecated
    private String contextModule = "aiContext";

    private final Map<String, TLEvalJudge> judges = new LinkedHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public TLEvalsModule() { super(); }
    public TLEvalsModule(String name) { super(name); }
    public TLEvalsModule(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    /** 配置值非 null 且非空才覆盖默认值（避免 <xxx value=""/> 把默认值清成空串） */
    private static String nonEmptyOr(String current, String configured) {
        return (configured != null && !configured.trim().isEmpty()) ? configured : current;
    }

    @Override
    protected TLBaseModule init() {
        judges.put("exact_match", new TLExactMatchJudge());
        judges.put("llm_judge", new TLLlmJudge());
        judges.put("constraint", new TLConstraintJudge());

        // 用 get != null && 非空 判断，与其它模块一致：containsKey 遇到 <xxx value=""/>
        // 会把默认值覆盖成空字符串
        evalCaseDir = nonEmptyOr(evalCaseDir, params.get("evalCaseDir"));
        reportOutputDir = nonEmptyOr(reportOutputDir, params.get("reportOutputDir"));
        targetAgent = nonEmptyOr(targetAgent, params.get("targetAgent"));
        judgeProvider = nonEmptyOr(judgeProvider, params.get("judgeProvider"));
        judgeReasoningMode = nonEmptyOr(judgeReasoningMode, params.get("judgeReasoningMode"));
        contextModule = nonEmptyOr(contextModule, params.get("contextModule"));
        baselineFile = nonEmptyOr(baselineFile, params.get("baselineFile"));
        if (params.get("compareBaseline") != null)
            compareBaseline = Boolean.parseBoolean(params.get("compareBaseline"));
        if (params.get("gatePassRate") != null && !params.get("gatePassRate").trim().isEmpty()) {
            try {
                gatePassRate = Double.parseDouble(params.get("gatePassRate"));
                // "NaN"/"Infinity" 能解析成功但 NaN>0 为假 → 门禁会静默失效，必须挡住
                if (Double.isNaN(gatePassRate) || Double.isInfinite(gatePassRate)) {
                    putLog("gatePassRate 不是有效数字，门禁按不启用处理: " + params.get("gatePassRate"), LogLevel.WARN);
                    gatePassRate = 0;
                }
            } catch (NumberFormatException e) {
                putLog("gatePassRate 不是数字，门禁按不启用处理: " + params.get("gatePassRate"), LogLevel.WARN);
                gatePassRate = 0;
            }
        }
        if (params.get("gateMaxRegressions") != null && !params.get("gateMaxRegressions").trim().isEmpty()) {
            try {
                gateMaxRegressions = Integer.parseInt(params.get("gateMaxRegressions"));
            } catch (NumberFormatException e) {
                putLog("gateMaxRegressions 不是整数，按不检查回归处理: " + params.get("gateMaxRegressions"), LogLevel.WARN);
                gateMaxRegressions = -1;
            }
        }

        ensureDir(reportOutputDir);

        // 把"配置里写的目录"与实际生效的目录一起打出来：两者不一致是"用例怎么扫不到"的头号原因
        // （配置相对仓库根，而仓库根没有 conf/，真实目录在 classpath 的 target/classes 下）
        File realDir = resolveCaseDir(getClass(), evalCaseDir);
        putLog("TLEvalsModule 初始化完成。evalCaseDir=" + evalCaseDir
                + (realDir.equals(new File(evalCaseDir).getAbsoluteFile()) ? "" : "（实际: " + realDir + "）")
                + ", targetAgent=" + targetAgent, LogLevel.INFO);
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "runEval":        returnMsg = runEval(fromWho, msg); break;
            case "runEvalSuite":   returnMsg = runEvalSuite(fromWho, msg); break;
            case "runEvalByName":  returnMsg = runEvalByName(fromWho, msg); break;
            case "listEvalCases":  returnMsg = listEvalCases(fromWho, msg); break;
            case "runQuickEval":   returnMsg = runQuickEval(fromWho, msg); break;
            case "runEvalCascade": returnMsg = runEvalCascade(fromWho, msg); break;
            case "getGenDefaults": returnMsg = getGenDefaults(fromWho, msg); break;
            default: break;
        }
        return returnMsg;
    }

    // ======================== 动作实现 ========================

    protected TLMsg runEval(Object fromWho, TLMsg msg) {
        String caseFile = msg.getStringParam("caseFile", "");
        if (caseFile.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "caseFile 参数必填");
        }

        try {
            List<TLEvalCase> cases = null;

            // 1. 先尝试文件系统
            File file = new File(caseFile);
            if (!file.exists()) file = new File(evalCaseDir, caseFile);
            if (file.exists()) {
                cases = loadCasesFromFile(file);
            }

            // 2. 回退到 classpath
            if (cases == null || cases.isEmpty()) {
                try {
                    if (caseFile.startsWith("CLASSPATH/")) caseFile = caseFile.substring(10);
                    String cpPath = evalCaseDir + caseFile;
                    if (cpPath.startsWith("CLASSPATH/")) cpPath = cpPath.substring(10);
                    cases = loadCasesFromResource(cpPath);
                } catch (Exception ignored) {}
            }

            if (cases == null || cases.isEmpty()) {
                return createMsg().setParam(RESULT, false).setParam("error", "未能加载用例: " + caseFile);
            }
            List<TLEvalRunResult> results = new ArrayList<>();
            for (TLEvalCase c : cases) results.add(runSingleCase(c));

            TLEvalReport report = buildReport(results);
            String reportPath = saveReport(report);
            printSummary(report);

            return withGate(withBaseline(createMsg().setParam(RESULT, true).setParam("reportPath", reportPath)
                    .setParam("passed", report.summary.passed)
                    .setParam("failed", report.summary.failed)
                    .setParam("passRate", report.summary.passRate), report), report);
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false).setParam(EXCEPTION, e.getMessage());
        }
    }

    protected TLMsg runEvalSuite(Object fromWho, TLMsg msg) {
        String suiteDir = msg.getStringParam("suiteDir", evalCaseDir);

        try {
            List<TLEvalCase> allCases = scanCaseFiles(new File(suiteDir));
            if (allCases.isEmpty()) {
                return createMsg().setParam(RESULT, false).setParam("error", "目录中没有用例: " + suiteDir);
            }

            putLog("========== 开始评测 " + allCases.size() + " 条用例 ==========", LogLevel.INFO);

            List<TLEvalRunResult> results = new ArrayList<>();
            for (int i = 0; i < allCases.size(); i++) {
                TLEvalCase c = allCases.get(i);
                putLog(String.format("[%d/%d] 运行: %s (%s)", i + 1, allCases.size(), c.name, c.id), LogLevel.INFO);
                TLEvalRunResult r = runSingleCase(c);
                results.add(r);
                printCaseResult(r);
            }

            TLEvalReport report = buildReport(results);
            String reportPath = saveReport(report);
            printSummary(report);
            putLog("报告已保存: " + reportPath, LogLevel.INFO);

            return withGate(withBaseline(createMsg().setParam(RESULT, true).setParam("reportPath", reportPath)
                    .setParam("total", report.summary.total)
                    .setParam("passed", report.summary.passed)
                    .setParam("failed", report.summary.failed)
                    .setParam("passRate", report.summary.passRate), report), report);
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false).setParam(EXCEPTION, e.getMessage());
        }
    }

    protected TLMsg runEvalByName(Object fromWho, TLMsg msg) {
        String caseId = msg.getStringParam("caseId", "");
        if (caseId.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "caseId 参数必填");
        }
        List<TLEvalCase> allCases = scanCaseFiles(new File(evalCaseDir));
        for (TLEvalCase c : allCases) {
            if (caseId.equals(c.id) || caseId.equals(c.name)) {
                TLEvalRunResult r = runSingleCase(c);
                TLEvalReport report = buildReport(Collections.singletonList(r));
                String reportPath = saveReport(report);
                printSummary(report);
                // 单用例失败时 RESULT=false，上层会走"评测失败"分支并读 error ——
                // 不带上原因的话控制台只会显示"未知错误"，等于把已经知道的原因丢掉。
                // total/passed 同理：不带就等于把"这条其实过了"显示成"0/0 通过"
                TLMsg resultMsg = createMsg().setParam(RESULT, r.passed).setParam("reportPath", reportPath)
                        .setParam("total", report.summary.total)
                        .setParam("passed", report.summary.passed)
                        .setParam("failed", report.summary.failed)
                        .setParam("passRate", report.summary.passRate);
                if (!r.passed) resultMsg.setParam("error", r.error != null ? r.error : "用例未通过");
                return withBaseline(resultMsg, report);
            }
        }
        // 报错要能自助：光说"没找到"没法用，把可用 id 列出来（文件名与 id 并不一一对应，
        // 一个 json 里可以有多条用例，所以这里给的是 id 而不是文件名）
        StringBuilder available = new StringBuilder();
        for (int i = 0; i < allCases.size(); i++) {
            if (i > 0) available.append("、");
            available.append(allCases.get(i).id);
            if (available.length() > 120 && i < allCases.size() - 1) {
                available.append("…（共 ").append(allCases.size()).append(" 条，用 /eval list 查看全部）");
                break;
            }
        }
        return createMsg().setParam(RESULT, false)
                .setParam("error", "未找到用例: " + caseId
                        + (available.length() == 0 ? "（用例目录为空）" : "，可用: " + available));
    }

    protected TLMsg listEvalCases(Object fromWho, TLMsg msg) {
        List<TLEvalCase> allCases = scanCaseFiles(new File(evalCaseDir));

        // 用例清单按"给人看的一行"生成（控制台直接打、前端直接列表格），
        // 顺带把目标一起列出来——"这条用例打的是谁"是理解结果的前提
        List<String> lines = new ArrayList<>();
        for (TLEvalCase c : allCases) {
            String judgesDesc = c.judges != null
                    ? c.judges.stream().map(j -> j.type).reduce((a, b) -> a + "," + b).orElse("none")
                    : "none";
            String target = (c.targetAgent != null && !c.targetAgent.isEmpty()) ? c.targetAgent : targetAgent;
            lines.add(String.format("[%s] %s | 目标: %s | 评判: %s", c.id, c.name, target, judgesDesc));
        }

        putLog("===== 可用评测用例 (" + allCases.size() + " 条) =====\n  "
                + String.join("\n  ", lines), LogLevel.INFO);
        return createMsg().setParam(RESULT, true)
                .setParam("cases", allCases)
                .setParam("caseList", lines)
                .setParam("count", allCases.size());
    }

    /**
     * 把"生成器要用到的默认值"给它：用例目录与 judgeProvider。
     * 生成器有自己的配置，但这两项必须与评测模块一致——各写一份的下场是漂移了没人发现
     * （生成的用例落在一个评测根本不会扫的目录里）。
     */
    protected TLMsg getGenDefaults(Object fromWho, TLMsg msg) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("evalCaseDir", evalCaseDir);
        data.put("judgeProvider", judgeProvider);
        return createMsg().setParam(RESULT, true).setParam("data", data);
    }

    protected TLMsg runQuickEval(Object fromWho, TLMsg msg) {
        putLog("===== 快速评测（内置用例） =====", LogLevel.INFO);

        TLEvalCase quickCase = new TLEvalCase();
        quickCase.id = "quick-basic-001";
        quickCase.name = "快速基本对话测试";
        quickCase.input = "你好，请用一句话介绍你自己。";

        JudgeConfig constraintConfig = new JudgeConfig();
        constraintConfig.type = "constraint";
        Map<String, Object> cMap = new LinkedHashMap<>();
        cMap.put("minResponseLength", 10);
        cMap.put("maxIterations", 5);
        constraintConfig.config = cMap;
        quickCase.judges = Collections.singletonList(constraintConfig);

        TLEvalRunResult r = runSingleCase(quickCase);
        TLEvalReport report = buildReport(Collections.singletonList(r));
        String reportPath = saveReport(report);
        printSummary(report);

        // 单用例动作也要把 total/passed 一起返回：上层默认读 0，不带就等于显示"0/0 通过"，
        // 哪怕这条其实是过的——比不显示更容易让人以为没跑
        TLMsg resultMsg = createMsg().setParam(RESULT, r.passed).setParam("reportPath", reportPath)
                .setParam("response", r.response)
                .setParam("total", report.summary.total)
                .setParam("passed", report.summary.passed)
                .setParam("failed", report.summary.failed)
                .setParam("passRate", report.summary.passRate);
        if (!r.passed) resultMsg.setParam("error", r.error != null ? r.error : "用例未通过");
        return withBaseline(resultMsg, report);
    }

    // ======================== 级联评测 ========================

    /** 级联目标：familyName + 调用类型 + 简称 */
    private static class CascadeTarget {
        final String familyName;
        final String callType;
        final String shortName;
        CascadeTarget(String familyName, String callType, String shortName) {
            this.familyName = familyName;
            this.callType = callType;
            this.shortName = shortName;
        }
    }

    /**
     * 级联评测：自动发现指定 Agent 的所有子模块（子 Agent + Skill），
     * 为每个目标自动生成基础用例并逐一评测，生成合并报告。
     */
    @SuppressWarnings("unchecked")
    protected TLMsg runEvalCascade(Object fromWho, TLMsg msg) {
        String rootAgent = msg.getStringParam("rootAgent", targetAgent);

        // 1. 收集所有目标：根 Agent + 子模块
        List<CascadeTarget> targets = new ArrayList<>();
        targets.add(new CascadeTarget(rootAgent, "agent_chat", rootAgent));

        try {
            TLMsg listResult = putMsg(DEFAULTMODULEREGISTRY,
                    createMsg().setAction(REGISTRY_LIST)
                            .setSystemParam(IGNOREMODULEISNULL, true)
                            .setParam(REGISTRY_P_OWNERNAME, rootAgent));
            if (listResult != null) {
                List<Map<String, Object>> children = (List<Map<String, Object>>) listResult.getParam(RESULT);
                if (children != null) {
                    for (Map<String, Object> child : children) {
                        String familyName = (String) child.get(REGISTRY_P_KEY);
                        Object instance = child.get(INSTANCE);
                        if (familyName == null) continue;

                        // 仅评测 Agent 和 Skill，跳过 Provider/Context/Memory 等
                        boolean isSkill = instance instanceof TLBaseSkill;
                        boolean isAgent = instance instanceof IAgentCapable;
                        if (!isSkill && !isAgent) {
                            putLog("跳过非Agent/Skill模块: " + familyName, LogLevel.DEBUG);
                            continue;
                        }

                        String shortName = familyName.contains(":")
                                ? familyName.substring(familyName.lastIndexOf(':') + 1) : familyName;
                        String callType = isSkill ? "skill_execute" : "agent_chat";
                        targets.add(new CascadeTarget(familyName, callType, shortName));
                    }
                }
            }
        } catch (Exception e) {
            putLog("发现子模块失败: " + e.toString(), LogLevel.DEBUG);
        }

        if (targets.size() <= 1) {
            putLog("未发现子模块，仅评测根 Agent: " + rootAgent, LogLevel.INFO);
        }

        // 2. 预扫描手写用例，按 targetAgent 建索引
        Map<String, List<TLEvalCase>> caseIndex = new HashMap<>();
        try {
            List<TLEvalCase> allCases = scanCaseFiles(new File(evalCaseDir));
            for (TLEvalCase c : allCases) {
                String key = (c.targetAgent != null && !c.targetAgent.isEmpty())
                        ? c.targetAgent : this.targetAgent;
                caseIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
            }
        } catch (Exception e) {
            putLog("预扫描用例失败: " + e.toString(), LogLevel.DEBUG);
        }

        // 3. 逐目标执行：优先手写用例，无则自动生成
        int manualCount = 0;
        for (CascadeTarget ct : targets) {
            List<TLEvalCase> manualCases = caseIndex.get(ct.familyName);
            if (manualCases != null && !manualCases.isEmpty()) manualCount++;
        }
        putLog(String.format("========== 级联评测 %d 个目标（%d 有手写用例）==========",
                targets.size(), manualCount), LogLevel.INFO);

        List<TLEvalRunResult> allResults = new ArrayList<>();
        int idx = 0;
        for (CascadeTarget ct : targets) {
            List<TLEvalCase> manualCases = caseIndex.get(ct.familyName);
            if (manualCases != null && !manualCases.isEmpty()) {
                // 使用手写用例（可能多条）
                for (TLEvalCase mc : manualCases) {
                    idx++;
                    putLog(String.format("[%d] %s/%s — 手写用例: %s",
                            idx, ct.shortName, ct.familyName, mc.name), LogLevel.INFO);
                    TLEvalRunResult r = runSingleCase(mc);
                    allResults.add(r);
                    printCaseResult(r);
                }
            } else {
                // 无手写用例，自动生成冒烟测试
                idx++;
                putLog(String.format("[%d] %s (%s, %s) — 自动生成",
                        idx, ct.shortName, ct.familyName, ct.callType), LogLevel.INFO);
                TLEvalCase autoCase = generateCascadeCase(ct);
                TLEvalRunResult r = runSingleCase(autoCase);
                allResults.add(r);
                printCaseResult(r);
            }
        }

        // 4. 生成合并报告
        TLEvalReport report = buildReport(allResults);
        String reportPath = saveReport(report);
        printSummary(report);
        putLog("级联报告已保存: " + reportPath, LogLevel.INFO);

        return withGate(withBaseline(createMsg().setParam(RESULT, true).setParam("reportPath", reportPath)
                .setParam("total", report.summary.total)
                .setParam("passed", report.summary.passed)
                .setParam("failed", report.summary.failed)
                .setParam("passRate", report.summary.passRate), report), report);
    }

    /** 为级联目标自动生成基础评测用例（轻量冒烟测试） */
    private TLEvalCase generateCascadeCase(CascadeTarget ct) {
        TLEvalCase c = new TLEvalCase();
        c.id = "cascade-" + ct.familyName.replace(':', '-');
        c.name = "级联-" + ct.shortName;
        c.targetAgent = ct.familyName;
        c.callType = ct.callType;

        if ("skill_execute".equals(ct.callType)) {
            c.input = new LinkedHashMap<>();  // 空参数，Skill 自行处理
        } else {
            c.input = "你好，请用一句话介绍你自己。";
        }

        JudgeConfig constraintConfig = new JudgeConfig();
        constraintConfig.type = "constraint";
        Map<String, Object> cMap = new LinkedHashMap<>();
        cMap.put("minResponseLength", 1);
        cMap.put("maxIterations", 5);
        cMap.put("maxLatencyMs", 30000);
        constraintConfig.config = cMap;
        c.judges = Collections.singletonList(constraintConfig);

        return c;
    }

    // ======================== 单用例执行 ========================

    protected TLEvalRunResult runSingleCase(TLEvalCase evalCase) {
        // 用例级 targetAgent 覆盖全局配置
        String targetModule = (evalCase.targetAgent != null && !evalCase.targetAgent.isEmpty())
                ? evalCase.targetAgent : this.targetAgent;
        String callType = (evalCase.callType != null && !evalCase.callType.isEmpty())
                ? evalCase.callType : "agent_chat";

        // skill_execute 模式：直接调用 Skill；agent_chat 模式（默认）：发送 AGENT_CHAT
        TLEvalRunResult result = "skill_execute".equals(callType)
                ? runSkillCase(evalCase, targetModule, callType)
                : runAgentChatCase(evalCase, targetModule, callType);

        // 稳定性标记随结果落盘：对比两次运行时要靠它区分"真回归"和"flaky 用例的正常波动"
        result.stability = evalCase.getStability();
        return result;
    }

    /**
     * 解析目标模块。
     * 1) 先按完整 familyName 查 moduleRegistry（如 "aiagent_master:httpRequestSkill"）；
     * 2) 裸名（无冒号）再按默认家族前缀查（targetAgent + ":" + name）——
     *    子 Agent 与全局工厂模块重名时保证评测目标取到注册表中的 Agent；
     * 3) 仍无则返回名字字符串（由 putMsg 按正常工厂流程查找）。
     */
    private Object resolveTarget(String targetName) {
        Object instance = registryGet(targetName);
        if (instance == null && !targetName.contains(":")) {
            instance = registryGet(targetAgent + ":" + targetName);
        }
        if (instance instanceof IObject) {
            putLog("通过注册表解析目标: " + targetName, LogLevel.DEBUG);
            return instance;
        }
        // 回退：当作工厂注册的普通模块名
        return targetName;
    }

    /** 按 key 查 moduleRegistry，未命中返回 null */
    private Object registryGet(String key) {
        TLMsg getResult = putMsg(DEFAULTMODULEREGISTRY,
                createMsg().setAction(REGISTRY_GET)
                        .setSystemParam(IGNOREMODULEISNULL, true)
                        .setParam(REGISTRY_P_KEY, key));
        if (getResult != null) {
            Object instance = getResult.getParam(INSTANCE);
            if (instance instanceof IObject) {
                return instance;
            }
        }
        return null;
    }

    /** 向目标模块发送消息：实例引用优先（注册表解析），否则按名称查找 */
    private TLMsg sendToTarget(String targetName, TLMsg msg) {
        Object target = resolveTarget(targetName);
        if (target instanceof IObject) {
            return putMsg((IObject) target, msg);
        }
        // IGNOREMODULEISNULL：按名找不到模块时，让 TLBaseModule.putMsg 返回空消息，
        // 而不是走它默认的那条 moduleFactory.shutdown(-1) —— 用例的 targetAgent 写错
        // （家族名不存在、Skill 没配到该 Agent 上）只应该让这一条用例失败，
        // 不能把整个应用一起关掉
        msg.setSystemParam(IGNOREMODULEISNULL, true);
        return putMsg(targetName, msg);
    }

    /**
     * 目标模块不存在时 putMsg 返回的占位消息（见 TLBaseModule.putMsg 的 IGNOREMODULEISNULL 分支）。
     * 用它把"模块不存在"和"模块存在但返回 null"区分开，好给出能直接看懂的错误。
     * 包内共用：评测模块与 llm_judge 都要靠它兜住"目标模块不存在"这种情况。
     */
    static boolean isModuleMissing(TLMsg response) {
        return response != null && Boolean.FALSE.equals(response.getParam(IGNOREMODULEISNULL));
    }

    /** agent_chat 模式：向目标 Agent 发送 AGENT_CHAT 消息 */
    private TLEvalRunResult runAgentChatCase(TLEvalCase evalCase, String targetModule, String callType) {
        TLEvalRunResult result = new TLEvalRunResult();
        result.caseId = evalCase.id;
        result.caseName = evalCase.name;
        result.targetAgent = targetModule;
        result.callType = callType;

        String sessionId = "eval_" + evalCase.id + "_" + System.currentTimeMillis();
        long startTime = System.currentTimeMillis();

        // agent_chat 的用户消息必须是字符串：input 配成对象时 getInput() 会返回
        // "{url=..., method=GET}" 这种 toString 文本，LLM 读不懂，早点报错比默默跑出怪结果好
        Object rawInput = evalCase.getInputRaw();
        if (rawInput != null && !(rawInput instanceof String)) {
            result.passed = false;
            result.error = "agent_chat 用例的 input 必须是字符串，实际是 "
                    + rawInput.getClass().getSimpleName() + "（对象类型请改用 skill_execute）";
            result.latencyMs = 0;
            return result;
        }

        try {
            TLMsg chatMsg = createMsg()
                    .setAction(AGENT_CHAT)
                    .setSystemParam(AI_P_SESSIONID, sessionId)
                    .setSystemParam(AI_P_USERID, EVAL_USER_ID)
                    .setParam(AI_P_USERMESSAGE, evalCase.getInput());

            TLMsg response = sendToTarget(targetModule, chatMsg);
            result.latencyMs = System.currentTimeMillis() - startTime;

            if (response == null || isModuleMissing(response)) {
                result.error = isModuleMissing(response)
                        ? "目标模块不存在: " + targetModule
                        : "Agent 返回 null";
                result.response = "";
                runAllJudges(evalCase, result);
                return result;
            }

            result.response = response.getStringParam(AI_P_RESPONSE, "");
            result.cancelled = response.parseBoolean(AI_P_CANCELLED, false);
            result.iterations = response.getIntParam("iterations", 0);
            result.promptTokens = response.getIntParam(AI_P_PROMPTTOKENS, 0);
            result.completionTokens = response.getIntParam(AI_P_COMPLETIONTOKENS, 0);
            result.totalTokens = response.getIntParam(AI_P_TOTALTOKENS, 0);
            result.promptTokensTotal = response.getIntParam(AI_P_PROMPTTOKENS_TOTAL, 0);
            result.completionTokensTotal = response.getIntParam(AI_P_COMPLETIONTOKENS_TOTAL, 0);
            result.totalTokensTotal = response.getIntParam(AI_P_TOTALTOKENS_TOTAL, 0);

            boolean success = response.parseBoolean(RESULT, false);
            if (!success && result.response.isEmpty()) {
                result.error = "Agent chat 返回失败";
            }

            result.toolCalls = extractToolCalls(targetModule, sessionId);
            runAllJudges(evalCase, result);

        } catch (Exception e) {
            result.latencyMs = System.currentTimeMillis() - startTime;
            result.error = e.toString();
            result.response = "";
            try { runAllJudges(evalCase, result); } catch (Exception ignored) {}
        } finally {
            try {
                // 走 Agent 的黑盒接口清它自己的会话（原来发给裸名 contextModule = 工厂单例，
                // 清的从来不是被评测 Agent 的上下文）
                sendToTarget(targetModule, createMsg()
                        .setAction(AGENT_CLEARCONTEXT)
                        .setParam(AI_P_SESSIONID, sessionId));
            } catch (Exception ignored) {}
        }
        return result;
    }

    /** skill_execute 模式：直接向 Skill 模块发送 SKILL_EXECUTE 消息 */
    @SuppressWarnings("unchecked")
    private TLEvalRunResult runSkillCase(TLEvalCase evalCase, String targetModule, String callType) {
        TLEvalRunResult result = new TLEvalRunResult();
        result.caseId = evalCase.id;
        result.caseName = evalCase.name;
        result.targetAgent = targetModule;
        result.callType = callType;
        result.iterations = 0;
        result.toolCalls = new ArrayList<>();

        long startTime = System.currentTimeMillis();

        try {
            // 解析 input 为 skill 参数 Map
            Map<String, Object> skillInput;
            Object rawInput = evalCase.getInputRaw();
            if (rawInput instanceof Map) {
                skillInput = (Map<String, Object>) rawInput;
            } else if (rawInput instanceof String) {
                String inputStr = (String) rawInput;
                try {
                    Map parsed = gson.fromJson(inputStr, Map.class);
                    skillInput = parsed != null ? parsed : new LinkedHashMap<>();
                } catch (Exception e) {
                    skillInput = new LinkedHashMap<>();
                    skillInput.put("input", inputStr);
                }
            } else {
                skillInput = new LinkedHashMap<>();
            }

            TLMsg skillMsg = createMsg()
                    .setAction(SKILL_EXECUTE)
                    .setParam(AI_P_SKILLINPUT, skillInput);

            TLMsg response = sendToTarget(targetModule, skillMsg);
            result.latencyMs = System.currentTimeMillis() - startTime;

            if (response == null || isModuleMissing(response)) {
                result.error = isModuleMissing(response)
                        ? "目标模块不存在: " + targetModule
                        : "Skill 返回 null";
                result.response = "";
                runAllJudges(evalCase, result);
                return result;
            }

            // 提取 skill 输出
            Object skillOutput = response.getParam(AI_P_SKILLOUTPUT);
            result.response = skillOutput != null ? skillOutput.toString() : "";

            boolean success = response.parseBoolean(RESULT, false);
            if (!success && result.response.isEmpty()) {
                result.error = "Skill 执行返回失败";
            }

            runAllJudges(evalCase, result);

        } catch (Exception e) {
            result.latencyMs = System.currentTimeMillis() - startTime;
            result.error = e.toString();
            result.response = "";
            try { runAllJudges(evalCase, result); } catch (Exception ignored) {}
        }
        // skill_execute 无 session，不需要清理
        return result;
    }

    // ======================== 评判执行 ========================

    private void runAllJudges(TLEvalCase evalCase, TLEvalRunResult result) {
        JudgeContext ctx = new JudgeContext(this, judgeProvider);
        ctx.judgeReasoningMode = judgeReasoningMode;
        result.verdicts = new ArrayList<>();
        boolean allPassed = true;

        if (evalCase.judges == null || evalCase.judges.isEmpty()) {
            result.passed = true;
            return;
        }

        for (JudgeConfig jc : evalCase.judges) {
            TLEvalJudge judge = judges.get(jc.type);
            if (judge == null) {
                result.verdicts.add(TLEvalVerdict.fail(jc.type, 0.0,
                        "未知评判器类型: " + jc.type));
                allPassed = false;
                continue;
            }
            try {
                TLEvalVerdict verdict = judge.judge(evalCase, result, ctx);
                result.verdicts.add(verdict);
                if (!verdict.passed) allPassed = false;
            } catch (Exception e) {
                result.verdicts.add(TLEvalVerdict.fail(jc.type, 0.0, "评判器异常: " + e.toString()));
                allPassed = false;
            }
        }
        result.passed = allPassed && result.error == null;
    }

    // ======================== Tool Calls 提取 ========================

    @SuppressWarnings("unchecked")
    protected List<String> extractToolCalls(String targetAgent, String sessionId) {
        List<String> toolNames = new ArrayList<>();
        try {
            // 必须走 Agent 的黑盒接口（AGENT_GETCONTEXT），不能直接 putMsg("aiContext", ...)：
            // 每个 Agent 的 context 是它用 getMyModule 建的私有实例（singleton=false，不注册进工厂
            // modules 表），而裸名寻址拿到的是共享配置里那个 singleton="true" 的 aiContext ——
            // 里面没有评测会话的数据，toolCalls 恒为空，mustCallTools 必然误报"缺少必须的工具调用"
            TLMsg getMsg = createMsg().setAction(AGENT_GETCONTEXT).setParam(AI_P_SESSIONID, sessionId);
            TLMsg result = sendToTarget(targetAgent, getMsg);
            if (result == null) {
                putLog("提取 tool calls 失败：" + targetAgent + " 无响应，"
                        + "constraint 的 mustCallTools 将因此误报", LogLevel.WARN);
                return toolNames;
            }

            List<TLConversationHistory> history =
                    (List<TLConversationHistory>) result.getListParam(AI_P_MESSAGEHISTORY, null);
            if (history == null) {
                putLog("提取 tool calls 失败：" + targetAgent + " 返回的消息里没有 "
                        + AI_P_MESSAGEHISTORY + "，constraint 的 mustCallTools 将因此误报", LogLevel.WARN);
                return toolNames;
            }

            for (TLConversationHistory entry : history) {
                if (entry.getToolCalls() != null) {
                    for (TLToolCall tc : entry.getToolCalls()) {
                        if (tc.getFunctionName() != null && !tc.getFunctionName().isEmpty()) {
                            toolNames.add(tc.getFunctionName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            putLog("提取 tool calls 失败: " + e.toString(), LogLevel.WARN);
        }
        return toolNames;
    }

    // ======================== 用例加载 ========================

    protected List<TLEvalCase> scanCaseFiles(File dir) {
        List<TLEvalCase> cases = new ArrayList<>();

        // 1. 文件系统
        int fsCount = 0;
        if (dir.exists() && dir.isDirectory()) {
            scanFilesInDir(dir, cases);
            fsCount = cases.size();
        }

        // 2. classpath 目录：**文件系统那个目录非空也照样扫**。
        //    早先的"非空就 return"是个静默陷阱：cwd 下冒出一个 json（比如生成器曾把用例写到
        //    仓库根新建的 conf/），classpath 里原有的一批用例就会集体消失，而且不报错——
        //    看起来就像"用例被删了"。两个来源按 id 去重合并，文件系统优先（部署目录可覆盖开发目录）。
        //    注意用的还是 dir 自己的路径：调用方传自定义 suiteDir 时行为与以前一致（自定义目录
        //    一般不是 classpath 资源，解析不到就只剩文件系统那一份）
        File cpDir = classpathCaseDir(dir.getPath());
        if (cpDir != null && !cpDir.equals(dir.getAbsoluteFile())) {
            List<TLEvalCase> cpCases = new ArrayList<>();
            scanFilesInDir(cpDir, cpCases);
            if (!cpCases.isEmpty()) {
                if (fsCount > 0) {
                    putLog("用例来自两个目录，按 id 去重合并：" + dir.getAbsolutePath() + "（" + fsCount
                            + " 条）+ classpath " + cpDir.getPath() + "（" + cpCases.size() + " 条）"
                            + "——本地目录会遮蔽 classpath 里的同名用例", LogLevel.WARN);
                }
                cases.addAll(cpCases);
            }
        }

        // 同 id 只留先到的（文件系统先扫，所以它优先）
        List<TLEvalCase> deduped = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (TLEvalCase c : cases) {
            if (c == null) continue;
            if (c.id == null || seenIds.add(c.id)) deduped.add(c);
            else putLog("重复用例 id 已跳过: " + c.id, LogLevel.DEBUG);
        }
        return deduped;
    }

    /**
     * 用例目录的绝对化——生成器落盘前必须过这一道。
     *
     * 配置里写的是相对路径（conf/demo/aiagent/evals/cases/），而"相对谁"取决于启动目录：
     * aistart.bat 不带 cd，从仓库根启动，可仓库根并没有 conf/——conf 在 classpath 下（target/classes），
     * 靠 classloader 才找得到。于是直接 new File(dir, name) 会在仓库根新造一个 conf/：
     * 那里评测扫不到（除非它非空后把 classpath 那批遮住，见 scanCaseFiles），又不在 gitignore 里。
     *
     * 解析顺序与 scanCaseFiles 的读取顺序一致——谁在供用例，落盘就落在谁那儿：
     *   1. 已是绝对路径 → 原样
     *   2. cwd 相对且目录里已有 json → 它（部署模式：conf/ 就在启动目录下）
     *   3. classpath 里找得到同名目录 → 它（开发模式：conf 在 target/classes 下）
     *   4. 都没有 → cwd 下的绝对路径（新建；至少落点是确定的，不再随 cwd 漂移）
     */
    public static File resolveCaseDir(Class<?> anchor, String dir) {
        File f = new File(dir);
        if (f.isAbsolute()) return f;
        if (f.exists() && f.isDirectory() && hasCaseFiles(f)) return f.getAbsoluteFile();
        File cpDir = classpathCaseDir(anchor, dir);
        if (cpDir != null) return cpDir;
        return f.getAbsoluteFile();
    }

    /** classpath 里的用例目录（不存在返回 null）。CLASSPATH/ 前缀与裸相对路径都认 */
    private static File classpathCaseDir(Class<?> anchor, String dir) {
        if (anchor == null || dir == null || dir.trim().isEmpty()) return null;
        String cpPath = dir.trim();
        if (cpPath.startsWith(CLASSPATH + "/")) cpPath = cpPath.substring(CLASSPATH.length() + 1);
        if (!cpPath.endsWith("/")) cpPath += "/";
        try {
            java.net.URL dirUrl = anchor.getClassLoader().getResource(cpPath);
            if (dirUrl != null && "file".equals(dirUrl.getProtocol())) {
                File cpDir = new File(dirUrl.toURI());
                if (cpDir.exists() && cpDir.isDirectory()) return cpDir;
            }
        } catch (Exception e) {
            // 解析不了就是"这个目录不在 classpath 上"，交给调用方的文件系统分支
        }
        return null;
    }

    /** 目录里（含子目录）有没有 json 用例文件 */
    private static boolean hasCaseFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.isDirectory()) {
                if (hasCaseFiles(f)) return true;
            } else if (f.getName().endsWith(".json")) {
                return true;
            }
        }
        return false;
    }

    private File classpathCaseDir(String dir) {
        return classpathCaseDir(getClass(), dir);
    }

    /** 扫描目录中的 JSON 文件并加载用例 */
    private void scanFilesInDir(File dir, List<TLEvalCase> cases) {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            // 递归子目录：原来只扫一层，按 math/ http/ llm/ 分目录组织的用例会被静默漏掉
            if (f.isDirectory()) {
                scanFilesInDir(f, cases);
                continue;
            }
            if (!f.getName().endsWith(".json")) continue;
            try {
                List<TLEvalCase> loaded = loadCasesFromFile(f);
                cases.addAll(loaded);
                putLog("加载用例: " + f.getName() + " (" + loaded.size() + " 条)", LogLevel.DEBUG);
            } catch (Exception e) {
                putLog("加载用例失败: " + f.getName() + " - " + e.toString(), LogLevel.ERROR);
            }
        }
    }

    protected List<TLEvalCase> loadCasesFromFile(File file) throws IOException {
        String content = readFileToString(file).trim();
        return parseCases(content);
    }

    /** 从 classpath 资源加载用例 */
    protected List<TLEvalCase> loadCasesFromResource(String resourcePath) throws IOException {
        String cpPath = resourcePath;
        if (cpPath.startsWith("CLASSPATH/")) cpPath = cpPath.substring(10);
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(cpPath)) {
            if (is == null) throw new IOException("classpath 资源不存在: " + cpPath);
            String content = new Scanner(is, "UTF-8").useDelimiter("\\A").next();
            return parseCases(content.trim());
        }
    }

    private List<TLEvalCase> parseCases(String content) {
        List<TLEvalCase> cases = new ArrayList<>();
        if (content.startsWith("[")) {
            Type listType = new TypeToken<List<TLEvalCase>>(){}.getType();
            List<TLEvalCase> loaded = gson.fromJson(content, listType);
            if (loaded != null) cases.addAll(loaded);
        } else {
            TLEvalCase c = gson.fromJson(content, TLEvalCase.class);
            if (c != null) cases.add(c);
        }
        return cases;
    }

    // ======================== 报告 ========================

    /**
     * 组装报告。这里会顺带做基线对比（要读报告目录里的上一份报告），
     * 五个评测入口都走这个方法，所以对比逻辑不必在每个入口重复。
     */
    protected TLEvalReport buildReport(List<TLEvalRunResult> results) {
        TLEvalReport report = new TLEvalReport();
        report.timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date());
        report.targetAgent = targetAgent;
        report.results = results;
        report.computeSummary();
        applyBaseline(report);
        return report;
    }

    /**
     * 与上一次报告对比，填充 regressions / improvements。
     * 找不到基准、或基准读不出来时，只把 baselineFound 留成 false——
     * 不能静默当成"本次无回归"，那两者含义完全不同。
     */
    private void applyBaseline(TLEvalReport report) {
        if (!compareBaseline) return;

        // 显式指定的基准（路径或文件名）：用指定的那份，不与自动选择混用
        if (!baselineFile.isEmpty()) {
            File base = TLEvalBaseline.resolveBaselineFile(new File(reportOutputDir), baselineFile);
            if (base == null) {
                putLog("指定的基准报告不存在，本次不对比: " + baselineFile, LogLevel.WARN);
                return;
            }
            compareWithFile(report, base);
            return;
        }

        // 自动选择：从新到旧找第一份"同一目标 Agent"的报告。
        // 报告目录各应用共用（默认 data/evals/reports/），不比对目标的话，
        // 拿另一个应用的结果来比会凭空比出假回归
        for (File candidate : TLEvalBaseline.listReportsNewestFirst(new File(reportOutputDir))) {
            TLEvalReport baseline = parseReport(candidate);
            if (baseline == null || !TLEvalBaseline.sameScope(report, baseline)) continue;
            TLEvalBaseline.compare(report, baseline, candidate.getName());
            return;
        }
        putLog("没有与本次目标一致的历史报告（目标 " + targetAgent + "），本次无基准可比: "
                + reportOutputDir, LogLevel.INFO);
    }

    private void compareWithFile(TLEvalReport report, File base) {
        TLEvalReport baseline = parseReport(base);
        if (baseline != null) TLEvalBaseline.compare(report, baseline, base.getName());
    }

    /** 读回一份历史报告；读不出来返回 null（调用方据此不做对比，而不是当成"无回归"） */
    private TLEvalReport parseReport(File file) {
        try {
            return gson.fromJson(readFileToString(file), TLEvalReport.class);
        } catch (Exception e) {
            putLog("历史报告解析失败，跳过: " + file.getName() + " - " + e.toString(), LogLevel.WARN);
            return null;
        }
    }

    protected String saveReport(TLEvalReport report) {
        try {
            // 文件名精确到毫秒：只到秒时同一秒内的两次运行会互相覆盖
            String base = TLEvalBaseline.REPORT_PREFIX
                    + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());
            File outFile = new File(reportOutputDir, base + ".json");
            writeStringToFile(outFile, gson.toJson(report));
            // 同名的可读版。渲染出错不影响 JSON——那是基线对比依赖的那一份
            try {
                writeStringToFile(new File(reportOutputDir, base + ".md"), report.toMarkdown(compareBaseline));
            } catch (Exception e) {
                putLog("可读版报告生成失败（JSON 已保存）: " + e, LogLevel.WARN);
            }
            return outFile.getAbsolutePath();
        } catch (Exception e) {
            putLog("保存报告失败: " + e.toString(), LogLevel.ERROR);
            return null;
        }
    }

    protected void printCaseResult(TLEvalRunResult result) {
        String icon = result.passed ? "[PASS]" : "[FAIL]";
        StringBuilder details = new StringBuilder();
        for (TLEvalVerdict v : result.verdicts) {
            details.append(String.format("  [%s] %s: score=%.2f %s\n",
                    v.passed ? "√" : "×", v.judgeType, v.score, v.reason));
        }
        putLog(String.format("%s %s (%s) | tokens=%d iterations=%d latency=%dms\n%s",
                icon, result.caseName, result.caseId,
                result.totalTokens, result.iterations, result.latencyMs, details.toString()),
                LogLevel.INFO);
    }

    protected void printSummary(TLEvalReport report) {
        TLEvalReport.Summary s = report.summary;
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== 评测报告 ==========\n");
        sb.append(String.format("总计: %d | 通过: %d | 失败: %d | 通过率: %.1f%%\n",
                s.total, s.passed, s.failed, s.passRate * 100));
        sb.append(String.format("平均 Tokens: %.0f | 平均迭代次数: %.1f | 平均延迟: %.0fms\n",
                s.avgTokens, s.avgIterations, s.avgLatencyMs));
        sb.append(baselineLine(s));
        if (s.failed > 0) {
            sb.append("--- 失败用例 ---\n");
            for (TLEvalRunResult r : report.results) {
                if (!r.passed) sb.append(String.format("  [FAIL] %s (%s)\n", r.caseName, r.caseId));
            }
        }
        sb.append("==============================");
        putLog(sb.toString(), LogLevel.INFO);
    }

    /**
     * 基线对比摘要 + 回归明细——控制台要能一眼看出"新挂了哪几条"，
     * 而不是只知道通过率变低了。没找到基准时明说，避免和"无回归"混为一谈。
     */
    private String baselineLine(TLEvalReport.Summary s) {
        if (!compareBaseline) return "基线对比: 已关闭 (compareBaseline=false)\n";
        if (!s.baselineFound) return "基线对比: 未找到历史报告，本次无基准可比\n";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("基线对比: %s | 可比 %d 条 | 回归 %d | 改善 %d\n",
                s.baselineFile, s.baselineCompared, s.regressions.size(), s.improvements.size()));
        for (TLEvalReport.Change c : s.regressions) {
            sb.append(String.format("  [回归] %s (%s)%s%s\n", c.caseName, c.caseId,
                    "flaky".equals(c.stability) ? " [flaky]" : "",
                    c.reason != null ? " — " + abbreviate(c.reason) : ""));
        }
        return sb.toString();
    }

    /** 失败原因可能很长（LLM 裁判的说明），摘要里截断，完整内容在报告 JSON 里 */
    private static String abbreviate(String text) {
        if (text == null) return "";
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }

    /** 把基线对比结果挂到返回消息上，供控制台/上层显示（字段名与测试层共用，见 TLEvalReportMsg） */
    private TLMsg withBaseline(TLMsg msg, TLEvalReport report) {
        return TLEvalReportMsg.attach(msg, report, compareBaseline);
    }

    /**
     * 套件级动作收尾：挂上门禁判定结果。
     * 单用例动作不调用——单条用例谈不上"通过率"。
     *
     * 结果用新参数返回，不动 RESULT 语义：RESULT 在框架里是"这个动作是否执行成功"，
     * 改成门禁结果会让控制台把"评测跑完了但没过"报成"评测失败"。
     */
    TLMsg withGate(TLMsg msg, TLEvalReport report) {
        TLEvalGate.Result r = TLEvalGate.evaluate(report, gatePassRate, gateMaxRegressions);
        // gateEnabled 单独给下游（CLI）看：门禁没配时 gatePassed 恒为 true，
        // 只读它会分不清"没配门禁"和"配了且通过"
        return msg.setParam("gatePassed", r.passed)
                .setParam("gateEnabled", gatePassRate > 0 || gateMaxRegressions >= 0)
                .setParam("gateFailures", r.failures);
    }

    // ======================== 工具方法 ========================

    private String readFileToString(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private void writeStringToFile(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write(content);
        }
    }

    private void ensureDir(String path) {
        File dir = new File(path);
        if (!dir.exists()) dir.mkdirs();
    }
}
