package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;

import java.util.ArrayList;
import java.util.List;

/**
 * 待批工具定位回归自测（runnable main，无 JUnit）。
 *
 * <p>背景（2026-11-05）：pickPendingToolCall 原先在 pendingToolCallId 不匹配时回退
 * toolCalls.get(0)，会把“批准 A”在断点恢复时重放成 B（恢复带免复批标志）。
 * 现在：非空 id 不匹配 → null；空 id 只有单工具批次才允许兜底。
 *
 * <p>失败以退出码 1 结束。
 */
public class ChatTurnPendingPickSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        ChatTurn turn = newTurn();
        TLToolCall a = tool("a", "read_file");
        TLToolCall b = tool("b", "write_file");
        List<TLToolCall> batch = new ArrayList<>();
        batch.add(a);
        batch.add(b);

        check("按 id 命中第二条",
                turn.pickPendingToolCall(batch, exec("b")) == b);
        check("按 id 命中第一条",
                turn.pickPendingToolCall(batch, exec("a")) == a);
        check("id 不匹配拒绝猜测",
                turn.pickPendingToolCall(batch, exec("missing")) == null);
        check("空 id + 多工具拒绝猜测",
                turn.pickPendingToolCall(batch, exec("")) == null);

        List<TLToolCall> single = new ArrayList<>();
        single.add(a);
        check("空 id + 单工具允许兜底",
                turn.pickPendingToolCall(single, exec("")) == a);
        check("多工具里恰好 id 为空且单元素",
                turn.pickPendingToolCall(single, exec("")) == single.get(0));

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static TLMsg exec(String pendingId) {
        return new TLMsg().setParam("pendingToolCallId", pendingId);
    }

    private static TLToolCall tool(String id, String name) {
        TLToolCall tc = new TLToolCall();
        tc.setId(id);
        tc.setFunctionName(name);
        return tc;
    }

    private static ChatTurn newTurn() {
        TurnSeed seed = new TurnSeed(new TLMsg(), "s", "s", "r", "u", "agent", "hi",
                TurnPlan.NORMAL, new ArrayList<>(), 0, null, null,
                new ArrayList<>(), "test-model", 0.0, 0,
                "off", false, false, false, 10, 0,
                StreamSink.NullStreamSink.INSTANCE, () -> false, null);
        TurnDeps deps = new TurnDeps(null, null, null, null, null,
                (history, memory) -> history, null, null, null, null, null);
        return new ChatTurn("turn:test:pick", seed, deps);
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
