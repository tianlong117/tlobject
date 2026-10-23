package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;

/** 控制台工具进度格式化自测（runnable main，无 JUnit——仓库惯例）。失败退出码 1。 */
public class TLChatConsoleProgressSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        TLChatConsole c = new TLChatConsole("progressFormatTest");
        check("starting", c.formatToolProgress(msg("browser", TOOL_STATUS_STARTING, 2, 10, 0, ""))
                .contains("[3/10] browser 开始"));
        check("done+秒", c.formatToolProgress(msg("browser", TOOL_STATUS_DONE, 2, 10, 1200, ""))
                .contains("[3/10] browser 完成 1.2s"));
        check("pending", c.formatToolProgress(msg("browser", TOOL_STATUS_PENDING, 0, 1, 0, ""))
                .contains("等待审批"));
        check("rejected", c.formatToolProgress(msg("browser", TOOL_STATUS_REJECTED, 0, 1, 0, ""))
                .contains("已拒绝"));
        check("timeout", c.formatToolProgress(msg("browser", TOOL_STATUS_TIMEOUT, 0, 1, 0, ""))
                .contains("超时"));
        check("skipped", c.formatToolProgress(msg("browser", TOOL_STATUS_SKIPPED, 0, 1, 0, ""))
                .contains("未执行"));
        check("error message", c.formatToolProgress(msg("browser", TOOL_STATUS_ERROR, 0, 1, 0, "boom"))
                .contains("boom"));
        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static TLMsg msg(String tool, String status, int index, int total, long ms, String message) {
        return new TLMsg()
                .setParam("toolName", tool)
                .setParam("status", status)
                .setParam("index", index)
                .setParam("total", total)
                .setParam("durationMs", ms)
                .setParam("message", message);
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
