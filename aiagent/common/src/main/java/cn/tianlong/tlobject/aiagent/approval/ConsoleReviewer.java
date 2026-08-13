package cn.tianlong.tlobject.aiagent.approval;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLParamString;

/**
 * 控制台审查人：不读 stdin，通过 CountDownLatch 阻塞等待 TLApprovalModule 的信号。
 *
 * <p>用户通过控制台 {@code /approve approve:ID} 或 {@code /approve reject:ID:原因} 命令
 * 将审批决策发给 TLApprovalModule，后者 countDown 唤醒本审查人。
 *
 * <p>与 JLine raw mode 完美兼容——stdin 仍由 TLChatConsole 的 JLine reader 独占。
 *
 * 创建日期：2026/7/25
 * 作者：tianlong
 */
public class ConsoleReviewer implements IApprovalReviewer, TLParamString {

    /** 审批提示模板 */
    private String promptTemplate;

    /** 所属审批模块（用于 latch 等待） */
    private TLApprovalModule owner;

    public ConsoleReviewer() {
        this.promptTemplate = "\n╔══════════════════════════════════════╗\n"
                + "║   ⚠️  操作需要人工审批                ║\n"
                + "╠══════════════════════════════════════╣\n"
                + "║  工具: {toolName}\n"
                + "║  风险: {riskLevel}\n"
                + "║  参数: {toolArgs}\n"
                + "║  审批: {approvalId}\n"
                + "╠══════════════════════════════════════╣\n"
                + "║  /approve approve:{id}    批准       ║\n"
                + "║  /approve reject:{id}:原因  拒绝      ║\n"
                + "╚══════════════════════════════════════╝";
    }

    public void setOwner(TLApprovalModule owner) { this.owner = owner; }

    // ======================== IApprovalReviewer 实现 ========================

    @Override
    public TLMsg requestApproval(TLApprovalRequest request) {
        // 1. 输出审批提示
        printPrompt(request);

        // 2. 阻塞等 TLApprovalModule 的信号（用户通过 /approve 命令发来）
        if (owner != null) {
            return owner.waitForDecision(request);
        }

        // 无 owner 时直接拒绝（不应发生）
        return buildResult(request.getApprovalId(), TLApprovalRequest.REJECTED, null, "审查人未就绪");
    }

    // ======================== 内部 ========================

    private void printPrompt(TLApprovalRequest request) {
        String msg = promptTemplate
                .replace("{toolName}", request.getToolName() != null ? request.getToolName() : "-")
                .replace("{riskLevel}", request.getRiskLevel() != null ? request.getRiskLevel() : "-")
                .replace("{toolArgs}", formatArgs(request.getToolArguments()))
                .replace("{approvalId}", request.getApprovalId() != null ? request.getApprovalId() : "-")
                .replace("{id}", request.getApprovalId() != null ? request.getApprovalId() : "-");
        // 经消息总线发布审批事件，由订阅者（控制台）自行渲染审批框+输入提示符；
        // 无订阅者（无控制台环境）时回退为直接打印
        if (owner == null || !owner.publishApprovalEvent(msg)) {
            System.out.println(msg);
            System.out.flush();
        }
    }

    private String formatArgs(java.util.Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Object> e : args.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            Object v = e.getValue();
            String vs = v != null ? v.toString() : "null";
            if (vs.length() > 40) vs = vs.substring(0, 40) + "...";
            sb.append(e.getKey()).append("=").append(vs);
        }
        return sb.toString();
    }

    private TLMsg buildResult(String approvalId, String state,
                               java.util.Map<String, Object> modifiedArgs, String rejectReason) {
        TLMsg result = new TLMsg();
        result.setParam("_approvalState", state);
        result.setParam("approvalId", approvalId);
        if (modifiedArgs != null) {
            result.setParam("approvalModifiedArguments", new java.util.LinkedHashMap<>(modifiedArgs));
        }
        if (rejectReason != null) {
            result.setParam("approvalRejectReason", rejectReason);
        }
        return result;
    }
}
