package cn.tianlong.tlobject.aiagent;

/** 会话取消标志的只读视图；Turn 不拥有取消状态。 */
interface CancelSignal {
    boolean isCancelled();
}
