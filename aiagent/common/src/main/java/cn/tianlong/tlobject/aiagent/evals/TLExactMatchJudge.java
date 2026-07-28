package cn.tianlong.tlobject.aiagent.evals;

/**
 * 精确匹配评判器。
 *
 * 创建日期：2026/7/29
 * 作者:tianlong
 */
public class TLExactMatchJudge implements TLEvalJudge {

    @Override
    public String getType() { return "exact_match"; }

    @Override
    public TLEvalVerdict judge(TLEvalCase evalCase, TLEvalRunResult runResult, JudgeContext context) {
        String expected = evalCase.getExpectedOutput();
        if (expected == null || expected.isEmpty()) {
            return TLEvalVerdict.fail("exact_match", 0.0, "expectedOutput 为空，无法进行精确匹配");
        }

        JudgeConfig config = findConfig(evalCase);
        boolean ignoreCase = config.getBoolean("ignoreCase", true);
        boolean normalizeWhitespace = config.getBoolean("normalizeWhitespace", true);
        boolean trim = config.getBoolean("trim", true);
        boolean contains = config.getBoolean("contains", false);
        String normalizeMode = config.getString("normalizeMode");
        if (normalizeMode == null) normalizeMode = "flexible";

        String actual = runResult.response;
        if (actual == null) actual = "";

        String expectedNorm = normalize(expected, ignoreCase, normalizeWhitespace, trim, normalizeMode);
        String actualNorm = normalize(actual, ignoreCase, normalizeWhitespace, trim, normalizeMode);

        boolean match;
        String reason;
        if (contains) {
            match = actualNorm.contains(expectedNorm);
            reason = match
                    ? "输出包含期望文本 (包含匹配)"
                    : String.format("输出不包含期望文本。期望片段='%s', 实际='%s'",
                            truncate(expectedNorm, 100), truncate(actualNorm, 100));
        } else {
            match = expectedNorm.equals(actualNorm);
            reason = match
                    ? "输出与期望完全一致"
                    : String.format("输出与期望不一致。期望='%s', 实际='%s'",
                            truncate(expectedNorm, 100), truncate(actualNorm, 100));
        }

        return match
                ? TLEvalVerdict.pass("exact_match", 1.0, reason)
                : TLEvalVerdict.fail("exact_match", 0.0, reason);
    }

    private JudgeConfig findConfig(TLEvalCase evalCase) {
        if (evalCase.judges != null) {
            for (JudgeConfig jc : evalCase.judges) {
                if ("exact_match".equals(jc.type)) return jc;
            }
        }
        return new JudgeConfig("exact_match", null);
    }

    static String normalize(String text, boolean ignoreCase, boolean normalizeWhitespace,
                            boolean trim, String normalizeMode) {
        String result = text;
        if (trim) result = result.trim();
        if (normalizeWhitespace) result = result.replaceAll("\\s+", " ");
        if ("flexible".equals(normalizeMode)) {
            result = result.replace('，', ',').replace('。', '.').replace('！', '!')
                    .replace('？', '?').replace('；', ';').replace('：', ':')
                    .replace('"', '"').replace('"', '"')
                    .replace('（', '(').replace('）', ')')
                    .replace('\'', '\'').replace('\'', '\'');
        }
        if (ignoreCase) result = result.toLowerCase();
        return result;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
