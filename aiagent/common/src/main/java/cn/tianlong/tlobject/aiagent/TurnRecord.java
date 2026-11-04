package cn.tianlong.tlobject.aiagent;

import java.util.ArrayList;
import java.util.List;

/**
 * checkpoint/commit 所需的轮次快照；ChatTurn 在调用时构造。
 * history 是快照副本，外部不会在提交期间被 Turn 继续修改。
 */
final class TurnRecord {

    final TurnIdentity id;
    final TurnPlan plan;
    final String userMessage;
    final List<TLConversationHistory> history;
    final int msgStartIdx;
    final List<TLConversationHistory> resumeRoundMessages;
    final boolean noHistory;
    final String model;
    final double temperature;
    final int maxTokens;
    final String reasoningMode;
    final boolean reasoningVisible;
    final int iterations;
    final TurnOutcome.Tokens tokens;
    final int finalAssistantIdx;
    final String reasoning;

    TurnRecord(TurnIdentity id, TurnPlan plan, String userMessage,
               List<TLConversationHistory> history, int msgStartIdx,
               List<TLConversationHistory> resumeRoundMessages, boolean noHistory,
               String model, double temperature, int maxTokens,
               String reasoningMode, boolean reasoningVisible,
               int iterations, TurnOutcome.Tokens tokens, int finalAssistantIdx,
               String reasoning) {
        this.id = id;
        this.plan = plan;
        this.userMessage = userMessage;
        this.history = new ArrayList<>(history);
        this.msgStartIdx = msgStartIdx;
        this.resumeRoundMessages = resumeRoundMessages == null
                ? null : new ArrayList<>(resumeRoundMessages);
        this.noHistory = noHistory;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.reasoningMode = reasoningMode;
        this.reasoningVisible = reasoningVisible;
        this.iterations = iterations;
        this.tokens = tokens;
        this.finalAssistantIdx = finalAssistantIdx;
        this.reasoning = reasoning;
    }

    /** 本轮落盘消息 = 恢复轮原消息前缀 + 本轮增量；同一 roundId last-wins。 */
    List<TLConversationHistory> roundMessages() {
        List<TLConversationHistory> delta = new ArrayList<>();
        if (msgStartIdx < history.size()) {
            delta.addAll(history.subList(msgStartIdx, history.size()));
        }
        if (resumeRoundMessages == null || resumeRoundMessages.isEmpty()) return delta;
        List<TLConversationHistory> out = new ArrayList<>(
                resumeRoundMessages.size() + delta.size());
        out.addAll(resumeRoundMessages);
        out.addAll(delta);
        return out;
    }
}
