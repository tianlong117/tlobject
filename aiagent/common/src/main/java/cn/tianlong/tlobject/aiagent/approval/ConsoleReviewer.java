package cn.tianlong.tlobject.aiagent.approval;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLParamString;

/**
 * 控制台审查人：不读 stdin，通过 CountDownLatch 阻塞等待 TLApprovalModule 的信号。
 *
 * <p>用户通过控制台 {@code /approve approve:ID}、{@code /approve round:ID}、
 * {@code /approve session:ID} 或 {@code /approve reject:ID:原因} 命令，
 * 以及 {@code /auto}、{@code /permissions}，将决策发给 TLApprovalModule，后者 countDown 唤醒本审查人。
 *
 * <p>无订阅者（{@code publishApprovalEvent} 无 ack）时不阻塞、返回 pending 交上层转断点（等人回来再接续）。
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
        // 框线宽度由 alignBox 在渲染时按实际内容重算（审批 ID 是 16 位随机码，
        // 写死宽度必然错位）—— 这里的行内空格只是原始排版，不必手动对齐
        this.promptTemplate = "\n╔══════════════════════════════════════╗\n"
                + "║   ⚠️  操作需要人工审批                ║\n"
                + "╠══════════════════════════════════════╣\n"
                + "║  说明: {description}\n"
                + "║  工具: {toolName}\n"
                + "║  风险: {riskLevel}\n"
                + "║  参数: {toolArgs}\n"
                + "║  审批: {approvalId}\n"
                + "╠══════════════════════════════════════╣\n"
                + "║  /approve approve:{id}      批准本次        ║\n"
                + "║  /approve round:{id}        本轮同规则免审   ║\n"
                + "║  /approve session:{id}      本会话同规则免审 ║\n"
                + "║  /approve reject:{id}:原因  拒绝           ║\n"
                + "╚══════════════════════════════════════╝";
    }

    public void setOwner(TLApprovalModule owner) { this.owner = owner; }

    // ======================== IApprovalReviewer 实现 ========================

    @Override
    public TLMsg requestApproval(TLApprovalRequest request) {
        // 1. 渲染审批框文本（交付给订阅者渲染；控制台/webui 各取所需）
        String text = renderPromptText(request);

        // 2. 投递判据（"人在不在"）：有订阅者 ack（控制台在跑 / webui 该用户的 SSE 在线）
        //    → 阻塞等决策；没人接收 → 返回 pending 不阻塞，上层落断点等人回来再批。
        //    原"publish 失败 → stdout 兜底打印"已删除：无 UI 部署打印也没人看。
        if (owner == null) {
            return buildResult(request.getApprovalId(), TLApprovalRequest.REJECTED, null, "审查人未就绪");
        }
        if (!owner.publishApprovalEvent(text, request)) {
            return buildResult(request.getApprovalId(), TLApprovalRequest.PENDING, null, null);
        }
        return owner.waitForDecision(request);
    }

    // ======================== 内部 ========================

    /**
     * 模板渲染（占位符替换）+ 框宽自适应。package 可见：自测直接调用。
     */
    static String renderPrompt(String template, java.util.Map<String, String> vars) {
        String text = template;
        for (java.util.Map.Entry<String, String> e : vars.entrySet()) {
            text = text.replace("{" + e.getKey() + "}", e.getValue());
        }
        return alignBox(text);
    }

    /**
     * 框宽自适应：先按渲染后的实际内容算出所需内宽（所有 ║...║ 闭合行中最大者，
     * 命令行的审批 ID 长度决定了这个宽度），再把 ╔/╠/╚ 框线重建到同一内宽、
     * 闭合行右补齐。无右边框的信息行（说明/工具/参数等，可能很长）原样保留不截断。
     */
    static String alignBox(String text) {
        String[] lines = text.split("\n", -1);
        int inner = 0;
        for (String ln : lines) {
            if (isClosedLine(ln)) inner = Math.max(inner, displayWidth(ln) - 2);
        }
        if (inner <= 0) return text;  // 非框文本：原样返回
        StringBuilder out = new StringBuilder(text.length() + lines.length * 4);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            String ln = lines[i];
            char first = ln.isEmpty() ? '\0' : ln.charAt(0);
            if (isBorderLine(ln)) {
                out.append(first).append("═".repeat(inner)).append(ln.charAt(ln.length() - 1));
            } else if (isClosedLine(ln)) {
                out.append("║").append(ln, 1, ln.length() - 1);
                for (int p = inner - (displayWidth(ln) - 2); p > 0; p--) out.append(' ');
                out.append("║");
            } else {
                out.append(ln);
            }
        }
        return out.toString();
    }

    /** 框线行：╔…╗ / ╠…╣ / ╚…╝ —— 首尾必须配对；不配对的行按普通内容行原样保留（防误截中段） */
    private static boolean isBorderLine(String ln) {
        if (ln.length() < 2) return false;
        char first = ln.charAt(0);
        char last = ln.charAt(ln.length() - 1);
        return (first == '╔' && last == '╗')
            || (first == '╠' && last == '╣')
            || (first == '╚' && last == '╝');
    }

    /** ║ 开头且 ║ 结尾的行 = 需要参与宽度对齐的闭合行 */
    private static boolean isClosedLine(String ln) {
        return ln.length() >= 2 && ln.charAt(0) == '║' && ln.endsWith("║");
    }

    /** 显示列宽：CJK 全角=2，其余（含框线/emoji）=1（与 TLChatConsole 同约定） */
    private static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += isFullWidth(s.charAt(i)) ? 2 : 1;
        return w;
    }

    private static boolean isFullWidth(char c) {
        return (c >= 0x2E80 && c <= 0x2EFF)
            || (c >= 0x3000 && c <= 0x303F)
            || (c >= 0x3200 && c <= 0x32FF)
            || (c >= 0x3400 && c <= 0x4DBF)
            || (c >= 0x4E00 && c <= 0x9FFF)
            || (c >= 0xF900 && c <= 0xFAFF)
            || (c >= 0xFE10 && c <= 0xFE1F)
            || (c >= 0xFE30 && c <= 0xFE4F)
            || (c >= 0xFF01 && c <= 0xFF60)
            || (c >= 0xFFE0 && c <= 0xFFE6)
            || c == 0x2014 || c == 0x2015;
    }

    /** 模板渲染为展示文本（不发布、不打印；发布与阻塞等决策由 requestApproval 决定） */
    private String renderPromptText(TLApprovalRequest request) {
        String id = request.getApprovalId() != null ? request.getApprovalId() : "-";
        java.util.Map<String, String> vars = new java.util.LinkedHashMap<>();
        vars.put("toolName", request.getToolName() != null ? request.getToolName() : "-");
        vars.put("riskLevel", request.getRiskLevel() != null ? request.getRiskLevel() : "-");
        vars.put("toolArgs", formatArgs(request.getToolArguments()));
        vars.put("approvalId", id);
        vars.put("id", id);
        vars.put("description", request.getDescription() != null && !request.getDescription().isEmpty()
                ? request.getDescription() : "需要您的确认");
        return renderPrompt(promptTemplate, vars);
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
        result.setParam(TLAiAgentParamString.AI_P_APPROVAL_STATE, state);
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
