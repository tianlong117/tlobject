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
    }

    public TLEvalReport() {}

    public void computeSummary() {
        summary.total = results.size();
        summary.passed = 0;
        summary.failed = 0;
        int totalTokens = 0;
        int totalIterations = 0;
        long totalLatency = 0;

        for (TLEvalRunResult r : results) {
            if (r.passed) summary.passed++; else summary.failed++;
            totalTokens += r.totalTokens;
            totalIterations += r.iterations;
            totalLatency += r.latencyMs;
        }

        summary.passRate = summary.total > 0 ? (double) summary.passed / summary.total : 0.0;
        summary.avgTokens = summary.total > 0 ? (double) totalTokens / summary.total : 0.0;
        summary.avgIterations = summary.total > 0 ? (double) totalIterations / summary.total : 0.0;
        summary.avgLatencyMs = summary.total > 0 ? (double) totalLatency / summary.total : 0.0;
    }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public Summary getSummary() { return summary; }
    public void setSummary(Summary summary) { this.summary = summary; }
    public List<TLEvalRunResult> getResults() { return results; }
    public void setResults(List<TLEvalRunResult> results) { this.results = results; }
}
