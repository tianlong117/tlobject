package cn.tianlong.tlobject.aiagent.approval;

import cn.tianlong.tlobject.base.TLMsg;

/**
 * 审批审查人接口。不同的审查人实现决定"怎么等、谁来审"。
 *
 * <p>同步审查人（如控制台）在 requestApproval 内部阻塞，直接返回 approved/rejected；
 * 异步审查人（如 Web 回调）立即返回 pending，等外部回调后再更新状态。
 *
 * <p>Agent 不关心审查人内部实现，只取返回值决定后续路径。
 *
 * 创建日期：2026/7/25
 * 作者：tianlong
 */
public interface IApprovalReviewer {

    /**
     * 请求人工审批。
     *
     * @param request 审批请求（含 toolName、toolArgs、sessionId 等）
     * @return TLMsg，必须包含 {@code _approvalState} 字段：
     *         {@code "approved"} — 批准执行；
     *         {@code "rejected"} — 拒绝；
     *         {@code "pending"} — 异步等待中
     */
    TLMsg requestApproval(TLApprovalRequest request);
}
