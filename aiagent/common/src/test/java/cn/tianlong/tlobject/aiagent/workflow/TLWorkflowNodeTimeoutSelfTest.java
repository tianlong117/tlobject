package cn.tianlong.tlobject.aiagent.workflow;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLParamString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工作流节点级超时自测（runnable main，无 JUnit——与仓库自测惯例一致）。
 *
 * <p>覆盖：节点 timeout 被调度线程强制执行；超时先 cancel 底层任务；默认 fail 不误放行下游；
 * onTimeout=skip 放行下游；onFailure=retry 按次重试并最终失败；timeout=0 不限制；
 * 已完成节点不被误判超时；工作流级超时语义不变。</p>
 *
 * <p>运行（classpath 见 aistart_args.txt）：
 * <pre>java -cp "aiagent/common/target/test-classes;aiagent/common/target/classes;core/target/classes;..." \
 *     cn.tianlong.tlobject.aiagent.workflow.TLWorkflowNodeTimeoutSelfTest</pre>
 * 失败以退出码 1 结束。
 */
public class TLWorkflowNodeTimeoutSelfTest implements TLParamString, TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        timeoutCancelsAndFails();
        timeoutSkipReleasesDownstream();
        timeoutZeroMeansUnlimited();
        timeoutRetryThenExhausted();
        completedBeforeTimeoutIsNotFailed();
        workflowTimeoutStillWins();

        System.out.println("\n结果: 通过=" + passed + ", 失败=" + failed);
        if (failed > 0) System.exit(1);
    }

    /** 默认 onTimeout=fail：取消任务、节点 FAILED:timeout、下游不会被放行。 */
    private static void timeoutCancelsAndFails() {
        Map<String, TLWorkflowNode> nodes = nodes("n1", "n2");
        nodes.get("n1").setTimeout(50);
        FakeTask n1Task = new FakeTask(-1);
        TLWorkflowEngine engine = engine(nodes, edges("n1", "n2"),
                (node, input) -> "n1".equals(node.getId()) ? n1Task : new FakeTask(0));

        TLMsg result = engine.execute();

        check("超时取消底层任务", n1Task.cancelCount.get() == 1);
        check("默认超时状态 FAILED:timeout",
                "FAILED:timeout".equals(statusOf(result, "n1")));
        check("默认失败不放行下游（n2 未执行）",
                statusOf(result, "n2") == null);
        check("超时结果 partial", !result.parseBoolean(RESULT, true)
                && "partial".equals(result.getStringParam("message", "")));
    }

    /** onTimeout=skip：节点记为超时失败，但下游按既有 skip 语义放行执行。 */
    private static void timeoutSkipReleasesDownstream() {
        Map<String, TLWorkflowNode> nodes = nodes("n1", "n2");
        nodes.get("n1").setTimeout(50);
        nodes.get("n1").setOnTimeout("skip");
        FakeTask n1Task = new FakeTask(-1);
        TLWorkflowEngine engine = engine(nodes, edges("n1", "n2"),
                (node, input) -> "n1".equals(node.getId()) ? n1Task : new FakeTask(0));

        TLMsg result = engine.execute();

        check("skip 超时取消任务", n1Task.cancelCount.get() == 1);
        check("skip 超时状态 FAILED:timeout(skip)",
                "FAILED:timeout(skip)".equals(statusOf(result, "n1")));
        check("skip 后下游正常执行", "DONE".equals(statusOf(result, "n2")));
    }

    /** timeout=0：不限制，慢但能完成的任务正常 DONE。 */
    private static void timeoutZeroMeansUnlimited() {
        Map<String, TLWorkflowNode> nodes = nodes("n1");
        FakeTask task = new FakeTask(120);
        TLWorkflowEngine engine = engine(nodes, Collections.emptyList(),
                (node, input) -> task);

        TLMsg result = engine.execute();

        check("timeout=0 节点正常完成", "DONE".equals(statusOf(result, "n1")));
        check("timeout=0 不取消任务", task.cancelCount.get() == 0);
    }

    /** onFailure=retry：每次超时都取消并重排，耗尽后 FAILED:retry(exhausted,timeout)。 */
    private static void timeoutRetryThenExhausted() {
        Map<String, TLWorkflowNode> nodes = nodes("n1");
        nodes.get("n1").setTimeout(40);
        nodes.get("n1").setOnFailure("retry");
        nodes.get("n1").setMaxRetries(1);
        List<FakeTask> tasks = new ArrayList<>();
        TLWorkflowEngine engine = engine(nodes, Collections.emptyList(),
                (node, input) -> {
                    FakeTask t = new FakeTask(-1);
                    tasks.add(t);
                    return t;
                });

        TLMsg result = engine.execute();

        check("retry 场景提交两次（首次 + 一次重试）", tasks.size() == 2);
        check("每次超时都取消底层任务",
                tasks.size() == 2 && tasks.get(0).cancelCount.get() == 1
                        && tasks.get(1).cancelCount.get() == 1);
        check("重试耗尽状态 FAILED:retry(exhausted,timeout)",
                "FAILED:retry(exhausted,timeout)".equals(statusOf(result, "n1")));
    }

    /** 任务恰好在超时判定前完成：不能被误标失败。 */
    private static void completedBeforeTimeoutIsNotFailed() {
        Map<String, TLWorkflowNode> nodes = nodes("n1");
        nodes.get("n1").setTimeout(500);
        FakeTask task = new FakeTask(20);
        TLWorkflowEngine engine = engine(nodes, Collections.emptyList(),
                (node, input) -> task);

        TLMsg result = engine.execute();

        check("期限前完成节点为 DONE", "DONE".equals(statusOf(result, "n1")));
        check("期限前完成不取消任务", task.cancelCount.get() == 0);
    }

    /** 持久化回归：工作流级超时仍是 WORKFLOW_TIMEOUT（节点未配 timeout）。 */
    private static void workflowTimeoutStillWins() {
        Map<String, TLWorkflowNode> nodes = nodes("n1");
        FakeTask task = new FakeTask(-1);
        TLWorkflowEngine engine = engine(nodes, Collections.emptyList(),
                (node, input) -> task);
        engine.setWorkflowTimeout(120L);

        TLMsg result = engine.execute();

        check("工作流级超时仍为 WORKFLOW_TIMEOUT",
                "WORKFLOW_TIMEOUT".equals(statusOf(result, "n1")));
        check("工作流级超时取消任务", task.cancelCount.get() == 1);
    }

    // ======================== 辅助 ========================

    private static Map<String, TLWorkflowNode> nodes(String... ids) {
        Map<String, TLWorkflowNode> nodes = new LinkedHashMap<>();
        for (String id : ids)
            nodes.put(id, new TLWorkflowNode(id, TLWorkflowNodeType.AGENT));
        return nodes;
    }

    private static List<TLWorkflowEdge> edges(String from, String to) {
        List<TLWorkflowEdge> edges = new ArrayList<>();
        edges.add(new TLWorkflowEdge(from, to));
        return edges;
    }

    private static TLWorkflowEngine engine(Map<String, TLWorkflowNode> nodes,
                                           List<TLWorkflowEdge> edges,
                                           TLWorkflowEngine.NodeRunner runner) {
        return new TLWorkflowEngine(nodes, edges, new TLWorkflowContext(new TLMsg()), runner);
    }

    @SuppressWarnings("unchecked")
    private static String statusOf(TLMsg result, String nid) {
        Object v = result != null ? result.getParam("nodeStatus") : null;
        if (!(v instanceof Map)) return null;
        Object s = ((Map<String, Object>) v).get(nid);
        return s != null ? s.toString() : null;
    }

    /** 可控 NodeTask：delayMs >= 0 时到期自动完成；< 0 表示永不完成。 */
    private static final class FakeTask implements TLWorkflowEngine.NodeTask {
        private final long doneAt;
        private final TLMsg result;
        final AtomicInteger cancelCount = new AtomicInteger();

        FakeTask(long delayMs) {
            this.doneAt = delayMs >= 0 ? System.currentTimeMillis() + delayMs : -1;
            this.result = new TLMsg().setParam(RESULT, true).setParam("aiResponse", "ok");
        }

        @Override public boolean isOver() {
            return doneAt >= 0 && System.currentTimeMillis() >= doneAt;
        }
        @Override public TLMsg getResult() { return result; }
        @Override public void cancel() { cancelCount.incrementAndGet(); }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            System.out.println("  ✗ " + name);
        }
    }
}
