package cn.tianlong.tlobject.aiagent.evals;

import cn.tianlong.tlobject.aiagent.*;
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
    private String contextModule = "aiContext";

    private final Map<String, TLEvalJudge> judges = new LinkedHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public TLEvalsModule() { super(); }
    public TLEvalsModule(String name) { super(name); }
    public TLEvalsModule(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected TLBaseModule init() {
        judges.put("exact_match", new TLExactMatchJudge());
        judges.put("llm_judge", new TLLlmJudge());
        judges.put("constraint", new TLConstraintJudge());

        if (params.containsKey("evalCaseDir")) evalCaseDir = params.get("evalCaseDir");
        if (params.containsKey("reportOutputDir")) reportOutputDir = params.get("reportOutputDir");
        if (params.containsKey("targetAgent")) targetAgent = params.get("targetAgent");
        if (params.containsKey("judgeProvider")) judgeProvider = params.get("judgeProvider");
        if (params.containsKey("contextModule")) contextModule = params.get("contextModule");

        ensureDir(reportOutputDir);

        putLog("TLEvalsModule 初始化完成。evalCaseDir=" + evalCaseDir
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

            return createMsg().setParam(RESULT, true).setParam("reportPath", reportPath)
                    .setParam("passed", report.summary.passed)
                    .setParam("failed", report.summary.failed)
                    .setParam("passRate", report.summary.passRate);
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

            return createMsg().setParam(RESULT, true).setParam("reportPath", reportPath)
                    .setParam("total", report.summary.total)
                    .setParam("passed", report.summary.passed)
                    .setParam("failed", report.summary.failed)
                    .setParam("passRate", report.summary.passRate);
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
                return createMsg().setParam(RESULT, r.passed).setParam("reportPath", reportPath);
            }
        }
        return createMsg().setParam(RESULT, false).setParam("error", "未找到用例: " + caseId);
    }

    protected TLMsg listEvalCases(Object fromWho, TLMsg msg) {
        List<TLEvalCase> allCases = scanCaseFiles(new File(evalCaseDir));
        StringBuilder sb = new StringBuilder();
        sb.append("===== 可用评测用例 (").append(allCases.size()).append(" 条) =====\n");
        for (TLEvalCase c : allCases) {
            String judgesDesc = c.judges != null
                    ? c.judges.stream().map(j -> j.type).reduce((a, b) -> a + "," + b).orElse("none")
                    : "none";
            sb.append(String.format("  [%s] %-30s 评判: %s\n", c.id, c.name, judgesDesc));
        }
        putLog(sb.toString(), LogLevel.INFO);
        return createMsg().setParam(RESULT, true).setParam("cases", allCases).setParam("count", allCases.size());
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

        return createMsg().setParam(RESULT, r.passed).setParam("reportPath", reportPath)
                .setParam("response", r.response);
    }

    // ======================== 单用例执行 ========================

    protected TLEvalRunResult runSingleCase(TLEvalCase evalCase) {
        TLEvalRunResult result = new TLEvalRunResult();
        result.caseId = evalCase.id;
        result.caseName = evalCase.name;

        String sessionId = "eval_" + evalCase.id + "_" + System.currentTimeMillis();
        long startTime = System.currentTimeMillis();

        try {
            TLMsg chatMsg = createMsg()
                    .setAction(AGENT_CHAT)
                    .setParam(AI_P_SESSIONID, sessionId)
                    .setParam(AI_P_USERMESSAGE, evalCase.input);

            TLMsg response = putMsg(targetAgent, chatMsg);
            result.latencyMs = System.currentTimeMillis() - startTime;

            if (response == null) {
                result.error = "Agent 返回 null";
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

            result.toolCalls = extractToolCalls(sessionId);
            runAllJudges(evalCase, result);

        } catch (Exception e) {
            result.latencyMs = System.currentTimeMillis() - startTime;
            result.error = e.toString();
            result.response = "";
            try { runAllJudges(evalCase, result); } catch (Exception ignored) {}
        } finally {
            try {
                putMsg(contextModule, createMsg()
                        .setAction(CONTEXT_CLEAR)
                        .setParam(AI_P_SESSIONID, sessionId));
            } catch (Exception ignored) {}
        }
        return result;
    }

    // ======================== 评判执行 ========================

    private void runAllJudges(TLEvalCase evalCase, TLEvalRunResult result) {
        JudgeContext ctx = new JudgeContext(this, judgeProvider);
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
    protected List<String> extractToolCalls(String sessionId) {
        List<String> toolNames = new ArrayList<>();
        try {
            TLMsg getMsg = createMsg().setAction(CONTEXT_GETMESSAGES).setParam(AI_P_SESSIONID, sessionId);
            TLMsg result = putMsg(contextModule, getMsg);
            if (result == null) return toolNames;

            List<TLConversationHistory> history =
                    (List<TLConversationHistory>) result.getParam("history", List.class);
            if (history == null) return toolNames;

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
            putLog("提取 tool calls 失败: " + e.toString(), LogLevel.DEBUG);
        }
        return toolNames;
    }

    // ======================== 用例加载 ========================

    protected List<TLEvalCase> scanCaseFiles(File dir) {
        List<TLEvalCase> cases = new ArrayList<>();

        // 1. 先尝试文件系统
        if (dir.exists() && dir.isDirectory()) {
            scanFilesInDir(dir, cases);
            if (!cases.isEmpty()) return cases;
        }

        // 2. 回退到 classpath（CLASSPATH/ 前缀或相对路径）
        String cpPath = evalCaseDir;
        if (cpPath.startsWith("CLASSPATH/")) cpPath = cpPath.substring(10);
        if (!cpPath.endsWith("/")) cpPath += "/";

        try {
            java.net.URL dirUrl = getClass().getClassLoader().getResource(cpPath);
            if (dirUrl != null && "file".equals(dirUrl.getProtocol())) {
                File cpDir = new File(dirUrl.toURI());
                if (cpDir.exists() && cpDir.isDirectory()) {
                    scanFilesInDir(cpDir, cases);
                }
            }
        } catch (Exception e) {
            putLog("classpath 扫描失败: " + e.toString(), LogLevel.DEBUG);
        }

        return cases;
    }

    /** 扫描目录中的 JSON 文件并加载用例 */
    private void scanFilesInDir(File dir, List<TLEvalCase> cases) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
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

    protected TLEvalReport buildReport(List<TLEvalRunResult> results) {
        TLEvalReport report = new TLEvalReport();
        report.timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date());
        report.results = results;
        report.computeSummary();
        return report;
    }

    protected String saveReport(TLEvalReport report) {
        try {
            String filename = "eval_report_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".json";
            File outFile = new File(reportOutputDir, filename);
            String json = gson.toJson(report);
            writeStringToFile(outFile, json);
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
        if (s.failed > 0) {
            sb.append("--- 失败用例 ---\n");
            for (TLEvalRunResult r : report.results) {
                if (!r.passed) sb.append(String.format("  [FAIL] %s (%s)\n", r.caseName, r.caseId));
            }
        }
        sb.append("==============================");
        putLog(sb.toString(), LogLevel.INFO);
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
