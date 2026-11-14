package cn.tianlong.tlobject.aiagent;

import java.util.List;

/** 发送视图构建；Phase 2 由 Agent 复用现有 buildSendList。 */
interface TurnViewBuilder {
    /**
     * @param roundStartIdx 本轮用户提问在 history 里的下标（-1=未知）；窗口裁剪时据此锚定提问，
     *                      长工具循环不能把任务目标冲出视图。
     */
    List<TLConversationHistory> build(List<TLConversationHistory> history, String memoryContext,
                                      int roundStartIdx);

    /**
     * 带本轮瞬态 system 消息的构建（todo 收尾门禁用）：这些消息只进本次发送视图，
     * 不落 history/会话存储——否则会跨轮累积，后续轮次被旧"清单未完成"带偏。
     * 默认忽略瞬态消息，兼容只实现 3 参的测试替身。
     */
    default List<TLConversationHistory> build(List<TLConversationHistory> history, String memoryContext,
                                              int roundStartIdx,
                                              List<TLConversationHistory> transientSystem) {
        return build(history, memoryContext, roundStartIdx);
    }
}
