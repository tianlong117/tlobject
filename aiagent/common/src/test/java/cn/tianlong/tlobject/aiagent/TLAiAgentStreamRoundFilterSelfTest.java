package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 流式事件 roundId 过滤自测（runnable main，无 JUnit）。
 *
 * <p>背景（2026-11-05）：/stop 后旧 Provider 请求若仍在途，迟到的 chunk/done 只按
 * sessionId 命中同会话新轮的 handle，会把旧内容注入新轮、甚至提前完成新轮。
 * 修复后事件带 roundId 时必须与 handle.roundId 一致；缺失 roundId 的旧 Provider
 * 事件保持向后兼容（放行）。
 *
 * <p>失败以退出码 1 结束。
 */
public class TLAiAgentStreamRoundFilterSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        TestAgent agent = new TestAgent();
        FakeSink sink = new FakeSink();
        StreamHandle handle = new StreamHandle(agent, "s1", "r1", sink);
        putHandle(agent, "s1", handle);

        // 1) 旧轮（r2）的 chunk 不能进新轮（r1）的 sink
        agent.onStreamResult(null, new TLMsg()
                .setParam(AI_P_SESSIONID, "s1")
                .setParam(AI_P_ROUNDID, "r2")
                .setParam(AI_P_CHUNK, "stale"));
        check("旧轮 chunk 被丢弃", sink.chunks.isEmpty());

        // 2) 同轮 chunk 正常推送
        agent.onStreamResult(null, new TLMsg()
                .setParam(AI_P_SESSIONID, "s1")
                .setParam(AI_P_ROUNDID, "r1")
                .setParam(AI_P_CHUNK, "fresh"));
        check("同轮 chunk 正常推送", sink.chunks.size() == 1
                && "fresh".equals(sink.chunks.get(0)));

        // 3) 旧轮 done 不能完成新轮 handle
        agent.onStreamResult(null, new TLMsg()
                .setParam(AI_P_SESSIONID, "s1")
                .setParam(AI_P_ROUNDID, "r2")
                .setParam(AI_P_STREAMDONE, true)
                .setParam(AI_P_RESPONSE, "stale-done"));
        check("旧轮 done 不完成新轮", !handle.isCompleted());

        // 4) 同轮 done 正常完成
        agent.onStreamResult(null, new TLMsg()
                .setParam(AI_P_SESSIONID, "s1")
                .setParam(AI_P_ROUNDID, "r1")
                .setParam(AI_P_STREAMDONE, true)
                .setParam(AI_P_RESPONSE, "done"));
        check("同轮 done 正常完成", handle.isCompleted());

        // 5) 缺失 roundId 的旧 Provider 事件保持兼容（放行）
        FakeSink sink2 = new FakeSink();
        StreamHandle handle2 = new StreamHandle(agent, "s2", "r3", sink2);
        putHandle(agent, "s2", handle2);
        agent.onStreamResult(null, new TLMsg()
                .setParam(AI_P_SESSIONID, "s2")
                .setParam(AI_P_CHUNK, "legacy"));
        check("缺 roundId 的旧事件放行", sink2.chunks.size() == 1
                && "legacy".equals(sink2.chunks.get(0)));

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    @SuppressWarnings("unchecked")
    private static void putHandle(TLAiAgent agent, String sessionId, StreamHandle handle)
            throws Exception {
        Field f = TLAiAgent.class.getDeclaredField("streamHandles");
        f.setAccessible(true);
        ((Map<String, StreamHandle>) f.get(agent)).put(sessionId, handle);
    }

    static class TestAgent extends TLAiAgent {
        TestAgent() { super("streamRoundFilterTest"); }
        @Override public void putLog(String content, LogLevel logLevel) { }
    }

    static class FakeSink implements StreamSink {
        final List<String> chunks = new ArrayList<>();
        @Override public void onChunk(String text) { chunks.add(text); }
        @Override public void onToolEvent(String toolName, String output) { }
        @Override public void onFinalText(String answer, boolean alreadyStreamed) { }
        @Override public void onDone() { }
        @Override public void onError(String message) { }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name);
        }
    }
}
