package cn.tianlong.tlobject.aiagent.webui;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.servletutils.clientinterface.TLWebChannel;

import java.lang.reflect.Method;

/**
 * WebUI 工具进度消费者自测（runnable main，无 JUnit——仓库惯例）。
 * 判定：工具 worker 调用 onToolProgress 必须立即返回，慢 channel 的 write 在 writer 线程完成。
 * 失败退出码 1。
 */
public class TLWebChatModuleProgressSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        TLWebChatModule module = new TLWebChatModule("progressSelfTest");
        BlockingChannel ch = new BlockingChannel(800);
        module.registerEventsChannel("u1", ch);

        TLMsg evt = new TLMsg()
                .setAction(TOOL_PROGRESS_TOPIC)
                .setParam("userId", "u1")
                .setParam("sessionId", "s1")
                .setParam("executionId", "e1")
                .setParam("toolCallId", "c1")
                .setParam("toolName", "browser")
                .setParam("index", 0)
                .setParam("total", 1)
                .setParam("status", TOOL_STATUS_STARTING);

        Method m = TLWebChatModule.class.getDeclaredMethod(
                "checkMsgAction", Object.class, TLMsg.class);
        m.setAccessible(true);

        long t0 = System.currentTimeMillis();
        m.invoke(module, null, evt);
        long elapsed = System.currentTimeMillis() - t0;
        check("onToolProgress 不等待慢 channel（elapsed=" + elapsed + "ms）", elapsed < 300);

        long deadline = System.currentTimeMillis() + 3000;
        while (ch.json == null && System.currentTimeMillis() < deadline) Thread.sleep(20);
        check("writer 最终写出事件", ch.json != null);
        check("事件类型正确", ch.json != null && ch.json.contains("\"type\":\"toolProgress\""));
        check("事件不含工具 output", ch.json != null && !ch.json.contains("\"output\""));
        check("工具名正确", ch.json != null && ch.json.contains("\"toolName\":\"browser\""));

        workflowProgress();

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    /** 工作流节点进度：独立 writer、字段完整、不拖住引擎线程。 */
    private static void workflowProgress() throws Exception {
        TLWebChatModule module = new TLWebChatModule("workflowProgressSelfTest");
        BlockingChannel ch = new BlockingChannel(800);
        module.registerEventsChannel("u2", ch);

        TLMsg evt = new TLMsg()
                .setAction(WORKFLOW_PROGRESS_TOPIC)
                .setParam("workflowName", "poemWorkflow")
                .setParam("workflowFamilyName", "poemWorkflow")
                .setParam("runId", "wf_1")
                .setParam("nodeId", "poetA")
                .setParam("nodeType", "AGENT")
                .setParam("status", "RUNNING")
                .setParam("durationMs", 0L)
                .setParam("detail", "type=AGENT")
                .setParam("totalNodes", 3)
                .setParam("sessionId", "webchat_u2_1")
                .setParam("rootSessionId", "webchat_u2_1")
                .setParam("userId", "u2")
                .setParam("roundId", "r1");

        Method m = TLWebChatModule.class.getDeclaredMethod(
                "checkMsgAction", Object.class, TLMsg.class);
        m.setAccessible(true);

        long t0 = System.currentTimeMillis();
        m.invoke(module, null, evt);
        long elapsed = System.currentTimeMillis() - t0;
        check("onWorkflowProgress 不等待慢 channel（elapsed=" + elapsed + "ms）", elapsed < 300);

        long deadline = System.currentTimeMillis() + 3000;
        while (ch.json == null && System.currentTimeMillis() < deadline) Thread.sleep(20);
        check("工作流进度最终写出", ch.json != null);
        check("事件类型是 workflowProgress",
                ch.json != null && ch.json.contains("\"type\":\"workflowProgress\""));
        check("节点字段完整", ch.json != null && ch.json.contains("\"nodeId\":\"poetA\"")
                && ch.json.contains("\"status\":\"RUNNING\"")
                && ch.json.contains("\"runId\":\"wf_1\"")
                && ch.json.contains("\"totalNodes\":3"));
        check("事件不含节点产出", ch.json != null && !ch.json.contains("\"output\""));
    }

    static class BlockingChannel implements TLWebChannel {
        private final long sleepMs;
        volatile String json;
        volatile boolean open = true;
        BlockingChannel(long sleepMs) { this.sleepMs = sleepMs; }
        public void write(String data) {
            try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            json = data;
        }
        public boolean isOpen() { return open; }
        public void close() { open = false; }
        public void awaitClosed() { }
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
