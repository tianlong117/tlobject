package cn.tianlong.tlobject.aiagent.evals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 约束检查评判器。
 *
 * 创建日期：2026/7/29
 * 作者:tianlong
 */
public class TLConstraintJudge implements TLEvalJudge {

    /** 认得的键（README §8.2）。说明性键允许存在但不产生检查 */
    private static final Set<String> KNOWN_KEYS = new HashSet<>(Arrays.asList(
            "maxTokens", "maxTotalTokens", "maxIterations", "maxLatencyMs",
            "mustCallTools", "mustNotCallTools", "mustContain", "mustNotContain",
            "minResponseLength", "maxResponseLength",
            "description", "note", "comment"));

    @Override
    public String getType() { return "constraint"; }

    @Override
    @SuppressWarnings("unchecked")
    public TLEvalVerdict judge(TLEvalCase evalCase, TLEvalRunResult runResult, JudgeContext context) {
        JudgeConfig config = findConfig(evalCase);
        if (config == null || config.config == null || config.config.isEmpty()) {
            return TLEvalVerdict.pass("constraint", 1.0, "无约束条件，默认通过");
        }

        List<String> unknownKeys = new ArrayList<>();
        for (String key : config.config.keySet()) {
            if (!KNOWN_KEYS.contains(key)) unknownKeys.add(key);
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
        // 内容包含判定忽略空白差异：LLM 会写成 "2 元" 而用例里写的是 "2元"，
        // 逐字匹配会把正确答案判成失败——假红和假绿一样有害，而且更耗人
        String haystack = stripWhitespace(response);

        List<String> mustContain = getStringList(config, "mustContain");
        if (mustContain != null && !mustContain.isEmpty()) {
            checksTotal++;
            List<String> notFound = new ArrayList<>();
            for (String s : mustContain) {
                if (!haystack.contains(stripWhitespace(s))) notFound.add(s);
            }
            if (notFound.isEmpty()) checksPassed++;
            else failures.add("回复中缺少关键内容: " + String.join(", ", notFound));
        }

        List<String> mustNotContain = getStringList(config, "mustNotContain");
        if (mustNotContain != null && !mustNotContain.isEmpty()) {
            checksTotal++;
            List<String> found = new ArrayList<>();
            for (String s : mustNotContain) {
                if (haystack.contains(stripWhitespace(s))) found.add(s);
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
            // 配了键却一个检查都没生效：全部键都不认识 = 拼写错误/抄错配置，判失败（假绿灯比报错危险）
            if (!unknownKeys.isEmpty() && unknownKeys.size() == config.config.size()) {
                return TLEvalVerdict.fail("constraint", 0.0,
                        "constraint 配置的键全部无法识别（检查拼写）: " + unknownKeys);
            }
            String note = unknownKeys.isEmpty() ? "" : "（忽略未识别的键: " + unknownKeys + "）";
            return TLEvalVerdict.pass("constraint", 1.0, "无可检查的约束条件" + note);
        }

        boolean allPassed = failures.isEmpty();
        double score = (double) checksPassed / checksTotal;
        String reason = allPassed
                ? String.format("全部 %d 项约束通过", checksTotal)
                : String.format("%d/%d 项约束通过。失败项: %s", checksPassed, checksTotal,
                        String.join("; ", failures));
        if (!unknownKeys.isEmpty()) reason += "（忽略未识别的键: " + unknownKeys + "）";

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

    /**
     * 去掉所有空白（空格/换行/制表符）。
     * 只用于 mustContain / mustNotContain 的内容包含判定——"文本在不在回复里"这件事
     * 不该被排版差异影响；长度、token、迭代那几项判据不受影响。
     */
    private static String stripWhitespace(String s) {
        // U+3000 全角空格不在 Java 默认 \s 里；中文 LLM 输出会混进来，不处理会假红
        return s == null ? "" : s.replaceAll("[\\s\\u3000]+", "");
    }

    private List<String> getStringList(JudgeConfig config, String key) {
        Object v = config.config != null ? config.config.get(key) : null;
        if (v == null) return null;
        if (v instanceof List) {
            // 逐元素 toString：LLM 可能生成 [123] / [{...}]，直接 (List<String>) 会在取值处 CCE
            List<String> out = new ArrayList<>();
            for (Object o : (List<?>) v) {
                if (o != null) out.add(String.valueOf(o));
            }
            return out;
        }
        String[] parts = v.toString().split(",");
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }
}
