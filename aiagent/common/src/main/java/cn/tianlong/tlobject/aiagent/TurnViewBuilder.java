package cn.tianlong.tlobject.aiagent;

import java.util.List;

/** 发送视图构建；Phase 2 由 Agent 复用现有 buildSendList。 */
interface TurnViewBuilder {
    List<TLConversationHistory> build(List<TLConversationHistory> history, String memoryContext);
}
