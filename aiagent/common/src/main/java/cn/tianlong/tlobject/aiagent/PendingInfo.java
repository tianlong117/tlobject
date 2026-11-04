package cn.tianlong.tlobject.aiagent;

/** 待审批断点信息：同一 roundId 的 sessionUpdated 携带。 */
record PendingInfo(String approvalId, TLToolCall pendingToolCall) {
}
