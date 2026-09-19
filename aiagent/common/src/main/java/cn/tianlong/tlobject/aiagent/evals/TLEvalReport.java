package cn.tianlong.tlobject.aiagent.evals;

import java.util.ArrayList;
import java.util.List;

/**
 * 评测报告 POJO。
 *
 * 创建日期：2026/7/29
 * 作者:tianlong
 */
public class TLEvalReport {
    public String timestamp;
    /** 报告标题（评测层与测试层共用同一套渲染，标题各自指定） */
    public String title = "评测报告";
    /**
     * 本次评测的默认目标模块名。
     * 报告目录是所有应用共用的（默认 data/evals/reports/），对比时必须先确认
     * "两次评测的是不是同一个 Agent"——否则拿 A 应用的结果和 B 应用的比，
     * 会凭空比出一堆假回归。
     */
    public String targetAgent;
    public Summary summary = new Summary();
    public List<TLEvalRunResult> results = new ArrayList<>();

    public static class Summary {
        public int total;
        public int passed;
        public int failed;
        public double passRate;
        public double avgTokens;
        public double avgIterations;
        public double avgLatencyMs;

        /**
         * 是否找到可比的历史报告。
         * 找不到时下面的对比字段全是空的——注意不能把它当成"无回归"，
         * 两者含义完全不同，所以单独用这个字段标出来。
         */
        public boolean baselineFound;
        /** 对比所用的基准报告文件名，便于回溯"跟哪一次比的" */
        public String baselineFile;
        /** 实际参与对比的用例数：只有两次都存在的用例才可比（新增用例没基准，删除的不算回归） */
        public int baselineCompared;
        /** 上次通过、这次失败 */
        public List<Change> regressions = new ArrayList<>();
        /** 上次失败、这次通过 */
        public List<Change> improvements = new ArrayList<>();

        public int getTotal() { return total; }
        public void setTotal(int total) { this.total = total; }
        public int getPassed() { return passed; }
        public void setPassed(int passed) { this.passed = passed; }
        public int getFailed() { return failed; }
        public void setFailed(int failed) { this.failed = failed; }
        public double getPassRate() { return passRate; }
        public void setPassRate(double passRate) { this.passRate = passRate; }
        public double getAvgTokens() { return avgTokens; }
        public void setAvgTokens(double avgTokens) { this.avgTokens = avgTokens; }
        public double getAvgIterations() { return avgIterations; }
        public void setAvgIterations(double avgIterations) { this.avgIterations = avgIterations; }
        public double getAvgLatencyMs() { return avgLatencyMs; }
        public void setAvgLatencyMs(double avgLatencyMs) { this.avgLatencyMs = avgLatencyMs; }
        public boolean isBaselineFound() { return baselineFound; }
        public void setBaselineFound(boolean baselineFound) { this.baselineFound = baselineFound; }
        public String getBaselineFile() { return baselineFile; }
        public void setBaselineFile(String baselineFile) { this.baselineFile = baselineFile; }
        public int getBaselineCompared() { return baselineCompared; }
        public void setBaselineCompared(int baselineCompared) { this.baselineCompared = baselineCompared; }
        public List<Change> getRegressions() { return regressions; }
        public void setRegressions(List<Change> regressions) { this.regressions = regressions; }
        public List<Change> getImprovements() { return improvements; }
        public void setImprovements(List<Change> improvements) { this.improvements = improvements; }
    }

    /** 一条用例在两次运行之间的状态变化 */
    public static class Change {
        public String caseId;
        public String caseName;
        /** 用例稳定性标记（用例 metadata.stability）：stable / flaky / experimental */
        public String stability;
        /** 变化原因：失败用例的错误信息或首个失败裁决的说明，便于一眼看出是超时还是内容不符 */
        public String reason;

        public Change() {}

        public Change(String caseId, String caseName, String stability, String reason) {
            this.caseId = caseId;
            this.caseName = caseName;
            this.stability = stability;
            this.reason = reason;
        }

        public String getCaseId() { return caseId; }
        public void setCaseId(String caseId) { this.caseId = caseId; }
        public String getCaseName() { return caseName; }
        public void setCaseName(String caseName) { this.caseName = caseName; }
        public String getStability() { return stability; }
        public void setStability(String stability) { this.stability = stability; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public TLEvalReport() {}

    public void computeSummary() {
        summary.total = results.size();
        summary.passed = 0;
        summary.failed = 0;
        int totalTokens = 0;
        int totalIterations = 0;
        long totalLatency = 0;
        // avgTokens / avgIterations 只统计 agent_chat 用例（README 的口径）：
        // skill_execute 不跑 LLM，tokens 恒为 0，一起做分母会把均值稀释
        int agentChatCount = 0;

        for (TLEvalRunResult r : results) {
            if (r.passed) summary.passed++; else summary.failed++;
            totalLatency += r.latencyMs;
            if ("agent_chat".equals(r.callType)) {
                totalTokens += r.totalTokens;
                totalIterations += r.iterations;
                agentChatCount++;
            }
        }

        summary.passRate = summary.total > 0 ? (double) summary.passed / summary.total : 0.0;
        summary.avgTokens = agentChatCount > 0 ? (double) totalTokens / agentChatCount : 0.0;
        summary.avgIterations = agentChatCount > 0 ? (double) totalIterations / agentChatCount : 0.0;
        summary.avgLatencyMs = summary.total > 0 ? (double) totalLatency / summary.total : 0.0;
    }

    /**
     * 可读版报告：结论 + 结果表 + 回归/改善 + 失败明细。
     *
     * JSON 那份是给机器读的（基线对比、门禁都靠反序列化），这份是给人读的。
     * 放在 POJO 上而不是某个模块里，是为了评测层和测试层能产出同一种格式的报告。
     *
     * @param compareBaselineShown 调用方是否启用了基线对比（关掉时写明，免得让人以为"没回归"）
     */
    public String toMarkdown(boolean compareBaselineShown) {
        Summary s = summary;
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title == null || title.isEmpty() ? "评测报告" : title)
                .append(" ").append(timestamp).append("\n\n");
        sb.append("- 目标: `").append(nullToEmpty(targetAgent)).append("`\n");
        sb.append(String.format("- 总计 %d | 通过 %d | 失败 %d | 通过率 %.1f%%\n",
                s.total, s.passed, s.failed, s.passRate * 100));
        sb.append(String.format("- 平均 Tokens %.0f | 平均迭代 %.1f | 平均延迟 %.0fms\n",
                s.avgTokens, s.avgIterations, s.avgLatencyMs));
        if (!compareBaselineShown) {
            sb.append("- 基线对比: 已关闭\n");
        } else if (!s.baselineFound) {
            sb.append("- 基线对比: 未找到可比的历史报告（这不等于「没有回归」）\n");
        } else {
            sb.append(String.format("- 基线对比: 基准 `%s` | 可比 %d 条 | 回归 %d | 改善 %d\n",
                    s.baselineFile, s.baselineCompared, s.regressions.size(), s.improvements.size()));
        }

        sb.append("\n## 结果\n\n");
        sb.append("| 用例 | 目标 | 结果 | 迭代 | Tokens | 延迟ms | 失败原因 |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (TLEvalRunResult r : results) {
            // 名称与 id 相同时不重复写一遍（测试场景两者本来就是同一个标签）
            String label = (r.caseName == null || r.caseName.isEmpty() || r.caseName.equals(r.caseId))
                    ? cell(r.caseId) : cell(r.caseName) + " (" + cell(r.caseId) + ")";
            sb.append(String.format("| %s | %s | %s | %d | %d | %d | %s |\n",
                    label, cell(r.targetAgent),
                    r.passed ? "PASS" : "FAIL", r.iterations, r.totalTokens, r.latencyMs,
                    cell(firstFailureReason(r))));
        }

        appendChanges(sb, s.regressions, "回归");
        appendChanges(sb, s.improvements, "改善");

        List<TLEvalRunResult> failures = new ArrayList<>();
        for (TLEvalRunResult r : results) if (!r.passed) failures.add(r);
        if (!failures.isEmpty()) {
            sb.append("\n## 失败详情\n");
            for (TLEvalRunResult r : failures) {
                sb.append("\n### ").append(cell(r.caseName))
                        .append(" (`").append(cell(r.caseId)).append("`)\n\n");
                if (r.error != null && !r.error.isEmpty())
                    sb.append("- **错误**: ").append(oneLine(r.error)).append("\n");
                if (r.verdicts != null) {
                    for (TLEvalVerdict v : r.verdicts) {
                        sb.append("- [").append(v.passed ? "√" : "×").append("] ")
                                .append(v.judgeType).append(": ").append(oneLine(v.reason)).append("\n");
                    }
                }
                if (r.response != null && !r.response.isEmpty())
                    sb.append("- 回复: ").append(oneLine(abbreviate(r.response))).append("\n");
            }
        }
        return sb.toString();
    }

    /** 回归/改善明细表（没有就不输出这一节） */
    private static void appendChanges(StringBuilder sb, List<Change> changes, String title) {
        if (changes == null || changes.isEmpty()) return;
        sb.append("\n## ").append(title).append("\n\n");
        sb.append("| 用例 | 稳定性 | 原因 |\n|---|---|---|\n");
        for (Change c : changes) {
            sb.append(String.format("| %s (%s) | %s | %s |\n",
                    cell(c.caseName), cell(c.caseId), cell(c.stability), cell(abbreviate(c.reason))));
        }
    }

    /** 失败原因：错误信息优先，其次首个失败裁决的说明 */
    private static String firstFailureReason(TLEvalRunResult r) {
        if (r.passed) return "";
        if (r.error != null && !r.error.isEmpty()) return abbreviate(r.error);
        if (r.verdicts != null) {
            for (TLEvalVerdict v : r.verdicts) {
                if (!v.passed) return abbreviate(v.reason);
            }
        }
        return "";
    }

    /** 失败原因可能很长（LLM 裁判的说明），摘要里截断，完整内容在报告 JSON 里 */
    private static String abbreviate(String text) {
        if (text == null) return "";
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }

    /** Markdown 表格单元格：null 安全 + 竖线转义 + 换行压平（不压平会把表格撑破） */
    private static String cell(String text) {
        return oneLine(text).replace("|", "\\|");
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace("\r", " ").replace("\n", " ").trim();
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public String getTargetAgent() { return targetAgent; }
    public void setTargetAgent(String targetAgent) { this.targetAgent = targetAgent; }
    public Summary getSummary() { return summary; }
    public void setSummary(Summary summary) { this.summary = summary; }
    public List<TLEvalRunResult> getResults() { return results; }
    public void setResults(List<TLEvalRunResult> results) { this.results = results; }
}
