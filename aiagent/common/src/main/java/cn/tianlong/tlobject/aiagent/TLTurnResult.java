package cn.tianlong.tlobject.aiagent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一轮 chat 的统一输出契约（对外结构化结果，不改用户可见正文）。
 *
 * <p>字段：status / rounds / durationMs / roundId / traceId / errorCode /
 * toolErrorCount / truncated / directAnswer。内部 {@link TurnOutcome} 已有语义，
 * 这里只做稳定映射，供 API、评测、WebUI 等调用方判断"这轮到底算成功还是失败"。</p>
 *
 * <p>注意：status=completed 也可能是 DirectAnswer（工具直出），此时 directAnswer=true；
 * truncated=true 表示撞迭代上限；errorCode 为空表示没有框架级错误。</p>
 */
final class TLTurnResult implements TLAiAgentParamString {

    private TLTurnResult() {}

    static Map<String, Object> build(TurnOutcome outcome, String roundId,
                                     int iterations, long durationMs, int toolErrorCount) {
        return build(outcome, roundId, iterations, durationMs, toolErrorCount, 0);
    }

    static Map<String, Object> build(TurnOutcome outcome, String roundId,
                                     int iterations, long durationMs, int toolErrorCount,
                                     int toolCallCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", statusOf(outcome));
        m.put("rounds", iterations);
        m.put("durationMs", Math.max(0L, durationMs));
        m.put("roundId", roundId == null ? "" : roundId);
        m.put("traceId", roundId == null ? "" : roundId);
        m.put("errorCode", errorCodeOf(outcome));
        m.put("toolErrorCount", Math.max(0, toolErrorCount));
        m.put("toolCallCount", Math.max(0, toolCallCount));
        m.put("truncated", outcome instanceof TurnOutcome.Truncated);
        m.put("directAnswer", outcome instanceof TurnOutcome.DirectAnswer);
        return m;
    }

    static String statusOf(TurnOutcome outcome) {
        if (outcome instanceof TurnOutcome.PendingApproval) return "pending_approval";
        if (outcome instanceof TurnOutcome.Rejected) return "rejected";
        if (outcome instanceof TurnOutcome.Clarified) return "clarified";
        if (outcome instanceof TurnOutcome.Truncated) return "truncated";
        if (outcome instanceof TurnOutcome.NoProgress) return "no_progress";
        if (outcome instanceof TurnOutcome.Aborted) return "aborted";
        if (outcome instanceof TurnOutcome.Failed) return "failed";
        if (outcome instanceof TurnOutcome.Timeout) return "timeout";
        if (outcome instanceof TurnOutcome.EarlyReturn) return "early_return";
        // Completed / DirectAnswer / 未知正常收尾
        return "completed";
    }

    static String errorCodeOf(TurnOutcome outcome) {
        if (outcome instanceof TurnOutcome.Failed) return ERRORCODE_AGENT;
        if (outcome instanceof TurnOutcome.Aborted a) {
            return a.reason() == null || a.reason().isEmpty() ? "aborted" : a.reason();
        }
        if (outcome instanceof TurnOutcome.Truncated) return "max_iterations";
        if (outcome instanceof TurnOutcome.NoProgress) return "no_progress";
        if (outcome instanceof TurnOutcome.Rejected) return "rejected";
        if (outcome instanceof TurnOutcome.PendingApproval) return "pending_approval";
        if (outcome instanceof TurnOutcome.Timeout) return "timeout";
        return "";
    }
}
