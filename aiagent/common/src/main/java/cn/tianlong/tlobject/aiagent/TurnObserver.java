package cn.tianlong.tlobject.aiagent;

import java.util.List;

/** trace/token 上报与格式化；Phase 2 由 Agent 实现，内部继续用现有打点逻辑。 */
interface TurnObserver {
    void traceStage(String sessionId, String rootSid, String roundId, String userId,
                    String stage, String detail, long durationMs, String payload);

    String formatHistory(List<TLConversationHistory> history);

    String formatResponse(String content, List<TLToolCall> toolCalls, String reasoning);

    String withTruncationNotice(String text, String finishReason);

    String emptyHistoryPlaceholder(String finishReason);

    void reportTokenUsage(String sessionId, String rootSid, String userId,
                          long[] usage, long[] cache);
}
