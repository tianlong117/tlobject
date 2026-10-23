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

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
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
