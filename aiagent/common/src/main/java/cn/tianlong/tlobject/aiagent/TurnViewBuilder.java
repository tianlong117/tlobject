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
}
