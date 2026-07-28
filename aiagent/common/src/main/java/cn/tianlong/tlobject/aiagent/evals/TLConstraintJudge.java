package cn.tianlong.tlobject.aiagent.evals;

import java.util.ArrayList;
import java.util.List;

/**
 * 约束检查评判器。
 *
 * 创建日期：2026/7/29
 * 作者:tianlong
 */
public class TLConstraintJudge implements TLEvalJudge {

    @Override
    public String getType() { return "constraint"; }

    @Override
    @SuppressWarnings("unchecked")
    public TLEvalVerdict judge(TLEvalCase evalCase, TLEvalRunResult runResult, JudgeContext context) {
        JudgeConfig config = findConfig(evalCase);
        if (config == null || config.config == null || config.config.isEmpty()) {
            return TLEvalVerdict.pass("constraint", 1.0, "无约束条件，默认通过");
        }

        List<String> failures = new ArrayList<>();
        int checksPassed = 0;
        int checksTotal = 0;

        // token 用量
        int maxTokens = config.getInt("maxTokens", -1);
        if (maxTokens > 0) {
            checksTotal++;
            if (runResult.totalTokens <= maxTokens) checksPassed++;
            else failures.add(String.format("Token用量超标: %d > %d", runResult.totalTokens, maxTokens));
        }

        int maxTotalTokens = config.getInt("maxTotalTokens", -1);
        if (maxTotalTokens > 0) {
            checksTotal++;
            if (runResult.totalTokensTotal <= maxTotalTokens) checksPassed++;
            else failures.add(String.format("累计Token用量超标: %d > %d", runResult.totalTokensTotal, maxTotalTokens));
        }

        // 迭代次数
        int maxIterations = config.getInt("maxIterations", -1);
        if (maxIterations >= 0) {
            checksTotal++;
            if (runResult.iterations <= maxIterations) checksPassed++;
            else failures.add(String.format("迭代次数超标: %d > %d", runResult.iterations, maxIterations));
        }

        // 延迟
        int maxLatencyMs = config.getInt("maxLatencyMs", -1);
        if (maxLatencyMs > 0) {
            checksTotal++;
            if (runResult.latencyMs <= maxLatencyMs) checksPassed++;
            else failures.add(String.format("响应延迟超标: %dms > %dms", runResult.latencyMs, maxLatencyMs));
        }

        // 必须调用的工具
        List<String> mustCallTools = getStringList(config, "mustCallTools");
        if (mustCallTools != null && !mustCallTools.isEmpty()) {
            checksTotal++;
            List<String> missing = new ArrayList<>();
            for (String tool : mustCallTools) {
                if (!runResult.toolCalls.contains(tool)) missing.add(tool);
            }
            if (missing.isEmpty()) checksPassed++;
            else failures.add("缺少必须的工具调用: " + String.join(", ", missing));
        }

        // 禁止调用的工具
        List<String> mustNotCallTools = getStringList(config, "mustNotCallTools");
        if (mustNotCallTools != null && !mustNotCallTools.isEmpty()) {
            checksTotal++;
            List<String> forbidden = new ArrayList<>();
            for (String tool : mustNotCallTools) {
                if (runResult.toolCalls.contains(tool)) forbidden.add(tool);
            }
            if (forbidden.isEmpty()) checksPassed++;
            else failures.add("调用了禁止的工具: " + String.join(", ", forbidden));
        }

        // 必须包含/禁止包含的字符串
        String response = runResult.response != null ? runResult.response : "";

        List<String> mustContain = getStringList(config, "mustContain");
        if (mustContain != null && !mustContain.isEmpty()) {
            checksTotal++;
            List<String> notFound = new ArrayList<>();
            for (String s : mustContain) {
                if (!response.contains(s)) notFound.add(s);
            }
            if (notFound.isEmpty()) checksPassed++;
            else failures.add("回复中缺少关键内容: " + String.join(", ", notFound));
        }

        List<String> mustNotContain = getStringList(config, "mustNotContain");
        if (mustNotContain != null && !mustNotContain.isEmpty()) {
            checksTotal++;
            List<String> found = new ArrayList<>();
            for (String s : mustNotContain) {
                if (response.contains(s)) found.add(s);
            }
            if (found.isEmpty()) checksPassed++;
            else failures.add("回复中包含禁止内容: " + String.join(", ", found));
        }

        // 回复长度
        int minLen = config.getInt("minResponseLength", -1);
        if (minLen > 0) {
            checksTotal++;
            if (response.length() >= minLen) checksPassed++;
            else failures.add(String.format("回复过短: %d < %d", response.length(), minLen));
        }

        int maxLen = config.getInt("maxResponseLength", -1);
        if (maxLen > 0) {
            checksTotal++;
            if (response.length() <= maxLen) checksPassed++;
            else failures.add(String.format("回复过长: %d > %d", response.length(), maxLen));
        }

        if (checksTotal == 0) {
            return TLEvalVerdict.pass("constraint", 1.0, "无可检查的约束条件");
        }

        boolean allPassed = failures.isEmpty();
        double score = (double) checksPassed / checksTotal;
        String reason = allPassed
                ? String.format("全部 %d 项约束通过", checksTotal)
                : String.format("%d/%d 项约束通过。失败项: %s", checksPassed, checksTotal,
                        String.join("; ", failures));

        return new TLEvalVerdict("constraint", allPassed, score, reason);
    }

    private JudgeConfig findConfig(TLEvalCase evalCase) {
        if (evalCase.judges != null) {
            for (JudgeConfig jc : evalCase.judges) {
                if ("constraint".equals(jc.type)) return jc;
            }
        }
        return new JudgeConfig("constraint", null);
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(JudgeConfig config, String key) {
        Object v = config.config != null ? config.config.get(key) : null;
        if (v == null) return null;
        if (v instanceof List) return (List<String>) v;
        String[] parts = v.toString().split(",");
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }
}
