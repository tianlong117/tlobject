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

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
