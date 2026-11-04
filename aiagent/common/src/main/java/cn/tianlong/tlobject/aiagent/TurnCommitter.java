package cn.tianlong.tlobject.aiagent;

/** 轮次快照/终态提交；实现负责 context/会话/记忆/护栏/起名等跨轮策略。 */
interface TurnCommitter {
    /** 轮内 checkpoint：sessionUpdated（同一 roundId，last-wins） */
    void checkpoint(TurnRecord record);

    /** 审批拦截：sessionUpdated 带 pendingToolCall/approvalId，不 chatFinished */
    void checkpointPending(TurnRecord record, PendingInfo pending);

    /** 终态收敛：返回最终文本 + 对调用方的返回消息 */
    CommitResult commit(TurnRecord record, TurnOutcome outcome);
}
