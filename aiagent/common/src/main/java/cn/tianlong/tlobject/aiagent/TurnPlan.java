package cn.tianlong.tlobject.aiagent;

/** 本轮的来源类型，显式替代 prepareHistory 里的 if/else 分叉。 */
enum TurnPlan {
    /** 正常对话：context 历史 + 本轮用户消息 */
    NORMAL,
    /** 从意外中断的 checkpoint 轮继续 */
    RESUME_CHECKPOINT,
    /** 从待审批断点恢复，按 decision 执行/拒绝/失效/仍等待 */
    RESUME_PENDING,
    /** 调用方直接提供完整 messages（trace 重放/外部驱动） */
    LLM_INPUT
}
