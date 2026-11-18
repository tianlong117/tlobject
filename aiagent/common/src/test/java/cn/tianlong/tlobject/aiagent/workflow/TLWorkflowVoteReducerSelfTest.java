package cn.tianlong.tlobject.aiagent.workflow;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLParamString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * vote/bestOf 字段级聚合自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>覆盖：多数表决、平票取最早、空白忽略、全空移除；bestOf 取最长；
 * 引擎在所有上游合并后调用 finalizeValue（字段真正收敛）；既有 reducer 默认恒等。</p>
 */
public class TLWorkflowVoteReducerSelfTest implements TLParamString, TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        voteMajority();
        voteTieKeepsFirst();
        voteBlankIgnored();
        bestOfLongest();
        bestOfAllBlankRemoved();
        engineFinalizesVoteField();
        legacyReducerUnaffected();
        namesRegistered();

        System.out.println("\n结果: 通过=" + passed + ", 失败=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static void voteMajority() {
        Object acc = reduceAll(TLReducers.VOTE, "A", "B", "A");
        check("多数表决 A", "A".equals(TLReducers.VOTE.finalizeValue(acc)));
    }

    private static void voteTieKeepsFirst() {
        Object acc = reduceAll(TLReducers.VOTE, "A", "B");
        check("平票取最早（A）", "A".equals(TLReducers.VOTE.finalizeValue(acc)));
        Object acc2 = reduceAll(TLReducers.VOTE, "B", "A");
        check("平票按到达顺序（B）", "B".equals(TLReducers.VOTE.finalizeValue(acc2)));
    }

    private static void voteBlankIgnored() {
        Object acc = reduceAll(TLReducers.VOTE, "", "  ", "A", "A", "B");
        check("空白候选忽略", "A".equals(TLReducers.VOTE.finalizeValue(acc)));
        Object allBlank = reduceAll(TLReducers.VOTE, "", " ");
        check("全空白返回 null", TLReducers.VOTE.finalizeValue(allBlank) == null);
    }

    private static void bestOfLongest() {
        Object acc = reduceAll(TLReducers.BEST_OF, "short", "much longer answer", "mid");
        check("bestOf 取最长", "much longer answer".equals(TLReducers.BEST_OF.finalizeValue(acc)));
        Object tie = reduceAll(TLReducers.BEST_OF, "abcd", "wxyz");
        check("bestOf 平票取最早", "abcd".equals(TLReducers.BEST_OF.finalizeValue(tie)));
    }

    private static void bestOfAllBlankRemoved() {
        Object acc = reduceAll(TLReducers.BEST_OF, "", "  ");
        check("bestOf 全空白返回 null", TLReducers.BEST_OF.finalizeValue(acc) == null);
    }

    private static void engineFinalizesVoteField() {
        Map<String, TLWorkflowNode> nodes = new LinkedHashMap<>();
        nodes.put("u1", new TLWorkflowNode("u1", TLWorkflowNodeType.AGENT));
        nodes.put("u2", new TLWorkflowNode("u2", TLWorkflowNodeType.AGENT));
        nodes.put("u3", new TLWorkflowNode("u3", TLWorkflowNodeType.AGENT));
        TLWorkflowNode target = new TLWorkflowNode("target", TLWorkflowNodeType.AGENT);
        target.setFieldReducers(Collections.singletonMap("choice", TLReducers.VOTE));
        nodes.put("target", target);

        List<TLWorkflowEdge> edges = new ArrayList<>();
        edges.add(new TLWorkflowEdge("u1", "target"));
        edges.add(new TLWorkflowEdge("u2", "target"));
        edges.add(new TLWorkflowEdge("u3", "target"));

        AtomicReference<TLMsg> targetInput = new AtomicReference<>();
        TLWorkflowEngine runner = new TLWorkflowEngine(nodes, edges,
                new TLWorkflowContext(new TLMsg()),
                (node, input) -> {
                    if ("target".equals(node.getId())) {
                        targetInput.set(input);
                        return new FakeTask(new TLMsg().setParam(RESULT, true));
                    }
                    Map<String, String> value = Map.of(
                            "u1", "A", "u2", "B", "u3", "A");
                    TLMsg result = new TLMsg().setParam(RESULT, true)
                            .setParam("choice", value.get(node.getId()));
                    return new FakeTask(result);
                });
        runner.execute();

        TLMsg in = targetInput.get();
        check("引擎把 vote 字段收敛为多数值",
                in != null && "A".equals(in.getStringParam("choice", "")));
    }

    private static void legacyReducerUnaffected() {
        TLStateReducer legacy = (existing, incoming) -> incoming;
        check("旧 reducer 默认 finalize 恒等",
                "x".equals(TLStateReducer.class.cast(legacy).finalizeValue("x")));
        Object acc = TLReducers.APPEND.reduce(null, "a");
        acc = TLReducers.APPEND.reduce(acc, "b");
        check("append finalize 不变", acc instanceof List && ((List<?>) acc).size() == 2);
    }

    private static void namesRegistered() {
        check("byName(vote)", TLReducers.byName("vote") == TLReducers.VOTE);
        check("byName(bestOf 大小写不敏感)", TLReducers.byName("bestOf") == TLReducers.BEST_OF);
        check("knownNames 含 vote/bestOf",
                TLReducers.knownNames().contains("vote")
                        && TLReducers.knownNames().contains("bestOf"));
    }

    private static Object reduceAll(TLStateReducer reducer, Object... values) {
        Object acc = null;
        for (Object v : values) acc = reducer.reduce(acc, v);
        return acc;
    }

    private static final class FakeTask implements TLWorkflowEngine.NodeTask {
        private final TLMsg result;
        FakeTask(TLMsg result) { this.result = result; }
        @Override public boolean isOver() { return true; }
        @Override public TLMsg getResult() { return result; }
        @Override public void cancel() { }
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  ✓ " + name); }
        else { failed++; System.out.println("  ✗ " + name); }
    }
}
