package cn.tianlong.tlobject.aiagent.evals;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条用例运行结果 POJO。
 *
 * 创建日期：2026/7/29
 * 作者:tianlong
 */
public class TLEvalRunResult {
    public String caseId;
    public String caseName;
    public String targetAgent;
    public String callType;
    public String response;
    public boolean cancelled;
    public int iterations;
    public int promptTokens;
    public int completionTokens;
    public int totalTokens;
    public int promptTokensTotal;
    public int completionTokensTotal;
    public int totalTokensTotal;
    public List<String> toolCalls = new ArrayList<>();
    public long latencyMs;
    public String error;
    public List<TLEvalVerdict> verdicts = new ArrayList<>();
    public boolean passed;
    /** 用例稳定性标记（用例 metadata.stability），随报告落盘，供两次运行之间对比时区分噪声与真回归 */
    public String stability;

    public TLEvalRunResult() {}

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getCaseName() { return caseName; }
    public void setCaseName(String caseName) { this.caseName = caseName; }
    public String getTargetAgent() { return targetAgent; }
    public void setTargetAgent(String targetAgent) { this.targetAgent = targetAgent; }
    public String getCallType() { return callType; }
    public void setCallType(String callType) { this.callType = callType; }
    public String getResponse() { return response; }
    public void setResponse(String response) { this.response = response; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    public int getIterations() { return iterations; }
    public void setIterations(int iterations) { this.iterations = iterations; }
    public int getPromptTokens() { return promptTokens; }
    public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }
    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }
    public int getPromptTokensTotal() { return promptTokensTotal; }
    public void setPromptTokensTotal(int promptTokensTotal) { this.promptTokensTotal = promptTokensTotal; }
    public int getCompletionTokensTotal() { return completionTokensTotal; }
    public void setCompletionTokensTotal(int completionTokensTotal) { this.completionTokensTotal = completionTokensTotal; }
    public int getTotalTokensTotal() { return totalTokensTotal; }
    public void setTotalTokensTotal(int totalTokensTotal) { this.totalTokensTotal = totalTokensTotal; }
    public List<String> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<String> toolCalls) { this.toolCalls = toolCalls; }
    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public List<TLEvalVerdict> getVerdicts() { return verdicts; }
    public void setVerdicts(List<TLEvalVerdict> verdicts) { this.verdicts = verdicts; }
    public boolean isPassed() { return passed; }
    public void setPassed(boolean passed) { this.passed = passed; }
    public String getStability() { return stability; }
    public void setStability(String stability) { this.stability = stability; }
}
