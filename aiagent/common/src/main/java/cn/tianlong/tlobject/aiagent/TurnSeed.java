package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;

import java.util.ArrayList;
import java.util.List;

/**
 * 一轮的内容快照：由 Agent 在轮开始前准备并注入。
 * Turn 不从这里之外读取 context / memory / 会话历史。
 */
final class TurnSeed {

    // 身份与请求
    final TLMsg msg;
    final String sessionId;
    final String rootSessionId;
    final String roundId;
    final String userId;
    final String agentName;
    final String userMessage;
    final TurnPlan plan;

    // 内容
    final List<TLConversationHistory> history;
    final int msgStartIdx;
    final List<TLConversationHistory> resumeRoundMessages;
    final String memoryContext;
    final List<TLFunctionDefinition> toolDefs;

    // 请求参数快照
    final String model;
    final double temperature;
    final int maxTokens;
    final String reasoningMode;
    final boolean reasoningVisible;
    final boolean noHistory;
    final boolean intentCacheEnabled;
    final int maxToolCallIterations;
    final int thinkingBudget;
    final boolean todoGateEnabled;
    final int todoGateMaxRetries;

    // 运行时出口
    final StreamSink sink;
    final CancelSignal cancel;
    final ApprovalResume approvalResume;

    TurnSeed(TLMsg msg,
             String sessionId, String rootSessionId, String roundId, String userId, String agentName,
             String userMessage, TurnPlan plan,
             List<TLConversationHistory> history, int msgStartIdx,
             List<TLConversationHistory> resumeRoundMessages,
             String memoryContext, List<TLFunctionDefinition> toolDefs,
             String model, double temperature, int maxTokens,
             String reasoningMode, boolean reasoningVisible,
             boolean noHistory, boolean intentCacheEnabled, int maxToolCallIterations,
             int thinkingBudget, boolean todoGateEnabled, int todoGateMaxRetries,
             StreamSink sink, CancelSignal cancel, ApprovalResume approvalResume) {
        this.msg = msg;
        this.sessionId = sessionId;
        this.rootSessionId = rootSessionId;
        this.roundId = roundId;
        this.userId = userId;
        this.agentName = agentName;
        this.userMessage = userMessage;
        this.plan = plan;
        this.history = new ArrayList<>(history);
        this.msgStartIdx = msgStartIdx;
        this.resumeRoundMessages = resumeRoundMessages == null
                ? null : new ArrayList<>(resumeRoundMessages);
        this.memoryContext = memoryContext;
        this.toolDefs = toolDefs == null ? new ArrayList<>() : new ArrayList<>(toolDefs);
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.reasoningMode = reasoningMode;
        this.reasoningVisible = reasoningVisible;
        this.noHistory = noHistory;
        this.intentCacheEnabled = intentCacheEnabled;
        this.maxToolCallIterations = maxToolCallIterations;
        this.thinkingBudget = thinkingBudget;
        this.todoGateEnabled = todoGateEnabled;
        this.todoGateMaxRetries = todoGateMaxRetries;
        this.sink = sink;
        this.cancel = cancel;
        this.approvalResume = approvalResume;
    }

    TurnIdentity identity() {
        return new TurnIdentity(sessionId, rootSessionId, roundId, userId, agentName);
    }

    boolean streaming() {
        return sink != null && !sink.isNull();
    }
}
