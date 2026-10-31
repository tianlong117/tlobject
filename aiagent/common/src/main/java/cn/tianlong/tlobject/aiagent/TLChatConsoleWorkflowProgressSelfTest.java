package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;

/**
 * 控制台工作流进度格式化自测（runnable main，无 JUnit——仓库惯例）。
 * 只验证展示契约：状态名可读、耗时/策略/条件/终态不丢信息。
 * 失败退出码 1。
 */
public class TLChatConsoleWorkflowProgressSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        TLChatConsole console = new TLChatConsole("consoleWorkflowProgressSelfTest");

        String start = console.formatWorkflowProgress(
                msg("_workflow", "RUNNING", 3, 0));
        check("工作流开始带节点数", start.contains("[poemWorkflow]") && start.contains("开始（3 节点）"));

        String running = console.formatWorkflowProgress(
                msg("poetA", "RUNNING", 3, 0));
        check("节点运行中", running.contains("poetA") && running.contains("运行中"));

        String done = console.formatWorkflowProgress(
                msg("poetA", "DONE", 3, 1200));
        check("节点完成带耗时", done.contains("完成") && done.contains("1.2s"));

        String failedLine = console.formatWorkflowProgress(
                msg("poetB", "FAILED:fail", 3, 0));
        check("失败带策略", failedLine.contains("失败") && failedLine.contains("（fail）"));

        String retry = console.formatWorkflowProgress(
                msg("poetB", "RETRY:2/3", 3, 0));
        check("重试带次数", retry.contains("重试 2/3"));

        String skipped = console.formatWorkflowProgress(
                msg("poetc", "SKIPPED", 3, 0));
        check("跳过可读", skipped.contains("跳过"));

        String condition = console.formatWorkflowProgress(
                msg("cond", "TRUE", 3, 0));
        check("条件可读", condition.contains("条件 true"));

        String partial = console.formatWorkflowProgress(
                msg("_workflow", "PARTIAL", 3, 0));
        check("工作流部分完成", partial.contains("部分完成"));

        String interrupted = console.formatWorkflowProgress(
                msg("_workflow", "INTERRUPTED", 3, 0));
        check("工作流中断", interrupted.contains("已中断"));

        String timeout = console.formatWorkflowProgress(
                msg("_workflow", "WORKFLOW_TIMEOUT", 3, 0));
        check("工作流超时", timeout.contains("超时"));

        String groupStart = console.formatWorkflowProgress(
                groupMsg("_group", "GROUP", "RUNNING", 0, "mode=parallel members=2", 2, 1));
        check("组开始带成员数与模式",
                groupStart.contains("[组 priceTeam]") && groupStart.contains("开始（2 成员 · parallel）"));

        String groupDone = console.formatWorkflowProgress(
                groupMsg("_group", "GROUP", "DONE", 1500, "", 2, 1));
        check("组完成", groupDone.contains("[组 priceTeam]") && groupDone.contains("完成")
                && !groupDone.contains("_group"));

        String groupPartial = console.formatWorkflowProgress(
                groupMsg("_group", "GROUP", "PARTIAL", 0, "member failed", 2, 1));
        check("组部分完成", groupPartial.contains("部分完成"));

        String groupFailed = console.formatWorkflowProgress(
                groupMsg("_group", "GROUP", "FAILED", 0, "sequential aborted", 2, 1));
        check("组失败带原因", groupFailed.contains("失败") && groupFailed.contains("sequential aborted"));

        String groupInterrupted = console.formatWorkflowProgress(
                groupMsg("_group", "GROUP", "INTERRUPTED", 0, "", 2, 1));
        check("组已中断", groupInterrupted.contains("[组 priceTeam]")
                && groupInterrupted.contains("已中断") && !groupInterrupted.contains("_group"));

        String memberDone = console.formatWorkflowProgress(
                groupMsg("hamburgerAgent", "MEMBER", "DONE", 1200, "", 2, 1));
        check("组成员完成带耗时",
                memberDone.contains("hamburgerAgent") && memberDone.contains("[priceTeam]")
                        && memberDone.contains("完成") && memberDone.contains("1.2s"));

        String memberFailed = console.formatWorkflowProgress(
                groupMsg("pizzaAgent", "MEMBER", "FAILED:timeout", 0, "", 2, 1));
        check("组成员失败带原因",
                memberFailed.contains("pizzaAgent") && memberFailed.contains("[priceTeam]")
                        && memberFailed.contains("（timeout）"));

        String memberFailedRetry = console.formatWorkflowProgress(
                groupMsg("pizzaAgent", "MEMBER", "FAILED:timeout", 0, "", 2, 2));
        check("组成员重试失败带次数", memberFailedRetry.contains("（timeout）（第2次）"));

        String memberSkipped = console.formatWorkflowProgress(
                groupMsg("dessertAgent", "MEMBER", "SKIPPED", 0, "", 2, 1));
        check("组成员跳过", memberSkipped.contains("跳过"));

        String supervisorRun = console.formatWorkflowProgress(
                groupMsg("priceReviewer", "SUPERVISOR", "RUNNING", 0, "round=1", 3, 1));
        check("监理审核中",
                supervisorRun.contains("监理 priceReviewer") && supervisorRun.contains("审核中（第1轮）"));

        String memberRetry = console.formatWorkflowProgress(
                groupMsg("hamburgerAgent", "MEMBER", "RUNNING", 0, "", 2, 2));
        check("成员重试第2次",
                memberRetry.contains("↻") && memberRetry.contains("重试（第2次）"));

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static TLMsg msg(String nodeId, String status, int totalNodes, long durationMs) {
        return new TLMsg()
                .setParam("workflowName", "poemWorkflow")
                .setParam("nodeId", nodeId)
                .setParam("status", status)
                .setParam("totalNodes", totalNodes)
                .setParam("durationMs", durationMs);
    }

    private static TLMsg groupMsg(String nodeId, String nodeType, String status,
                                  long durationMs, String detail, int totalNodes, int attempt) {
        return new TLMsg()
                .setParam("source", "group")
                .setParam("groupName", "priceTeam")
                .setParam("workflowName", "priceTeam")
                .setParam("nodeId", nodeId)
                .setParam("nodeType", nodeType)
                .setParam("status", status)
                .setParam("durationMs", durationMs)
                .setParam("detail", detail)
                .setParam("totalNodes", totalNodes)
                .setParam("attempt", attempt);
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
