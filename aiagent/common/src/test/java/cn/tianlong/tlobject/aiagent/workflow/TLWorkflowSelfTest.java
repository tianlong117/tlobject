package cn.tianlong.tlobject.aiagent.workflow;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLParamString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 工作流回归自测（runnable main，无 JUnit——与仓库自测惯例一致）。
 *
 * <p>覆盖 DeepSeek 工作流审查中确认的边界 bug：
 * <ol>
 *   <li>nodeDuration 存的是时间戳而不是耗时（引擎）——断言数值量级是毫秒耗时；</li>
 *   <li>工作流超时/中断只清 running 表、不取消底层任务——断言 NodeTask.cancel() 被调用；</li>
 *   <li>doWorkflow 的 userMessage 回落被 addArgs 里的空串覆盖——断言 workflowInput 能补位；</li>
 *   <li>doWorkflowAsTool 对 null/空 task 的处理（"null" 字符串 / 空串挡住 AI_P_USERMESSAGE 兜底）；</li>
 *   <li>节点进度：引擎状态回调顺序/耗时，以及宿主发布到 msgBus 的事件字段。</li>
 * </ol>
 *
 * <p>运行（classpath 见 aistart_args.txt）：
 * <pre>java -cp "aiagent/common/target/classes;core/target/classes;..." \
 *     cn.tianlong.tlobject.aiagent.workflow.TLWorkflowSelfTest</pre>
 * 失败以退出码 1 结束。
 */
public class TLWorkflowSelfTest implements TLParamString, TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        durationIsElapsedNotTimestamp();
        nodeProgressReportsLifecycle();
        nodeProgressReportsFailure();
        hostPublishesWorkflowProgress();
        timeoutCancelsRunningTask();
        interruptCancelsRunningTask();
        userMessageFallbackToWorkflowInput();
        toolTaskNullAndEmptyFallback();

        System.out.println("\n结果: 通过=" + passed + ", 失败=" + failed);
        if (failed > 0) System.exit(1);
    }

    // ======================== 1. nodeDuration ========================

    private static void durationIsElapsedNotTimestamp() {
        FakeTask task = new FakeTask(120);
        TLWorkflowEngine engine = engine("n1", (node, input) -> task);
        TLMsg result = engine.execute();

        @SuppressWarnings("unchecked")
        Map<String, Long> durations = (Map<String, Long>) result.getParam("nodeDuration");
        Long d = durations != null ? durations.get("n1") : null;
        check("节点完成", result.parseBoolean(RESULT, false));
        check("nodeDuration 是耗时而非时间戳（实际 " + d + "）",
                d != null && d >= 0 && d < 60_000L);
    }

    // ======================== 2. 节点进度 ========================

    private static void nodeProgressReportsLifecycle() {
        FakeTask task = new FakeTask(80);
        TLWorkflowEngine engine = engine("n1", (node, input) -> task);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        List<Long> doneDurations = Collections.synchronizedList(new ArrayList<>());
        engine.setProgressListener((nid, status, durationMs, detail) -> {
            events.add(nid + ":" + status);
            if ("DONE".equals(status)) doneDurations.add(durationMs);
        });
        engine.execute();

        check("节点进度包含工作流开始", events.contains("_workflow:RUNNING"));
        check("节点进度包含节点 RUNNING", events.contains("n1:RUNNING"));
        check("节点进度包含节点 DONE", events.contains("n1:DONE"));
        check("节点进度包含工作流完成", events.contains("_workflow:DONE"));
        check("生命周期顺序 RUNNING 在 DONE 前",
                events.indexOf("n1:RUNNING") >= 0
                        && events.indexOf("n1:DONE") > events.indexOf("n1:RUNNING"));
        check("DONE 事件带耗时（" + doneDurations + "）",
                !doneDurations.isEmpty() && doneDurations.get(0) >= 0);
    }

    private static void nodeProgressReportsFailure() {
        // runner 返回 null = 提交失败，走 handleNodeFailure（默认 fail）
        TLWorkflowEngine engine = engine("n1", (node, input) -> null);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        engine.setProgressListener((nid, status, durationMs, detail) -> events.add(nid + ":" + status));
        engine.execute();

        check("提交失败发 FAILED:fail", events.contains("n1:FAILED:fail"));
        check("失败时工作流终态 PARTIAL", events.contains("_workflow:PARTIAL"));
    }

    // ======================== 3. 宿主发布事件 ========================

    private static void hostPublishesWorkflowProgress() throws Exception {
        TestWorkflow wf = new TestWorkflow("wf-progress-publisher");
        TLMsg input = new TLMsg()
                .setSystemParam(AI_P_SESSIONID, "sid-1")
                .setSystemParam("rootSessionId", "sid-1")
                .setSystemParam("userId", "u1")
                .setSystemParam(AI_P_ROUNDID, "r1");
        Map<String, TLWorkflowNode> nodes = new LinkedHashMap<>();
        nodes.put("poetA", new TLWorkflowNode("poetA", TLWorkflowNodeType.AGENT));

        TLWorkflowEngine.NodeProgressListener publisher =
                (TLWorkflowEngine.NodeProgressListener) newPublisher(wf, input, nodes, "run-1");
        publisher.onNodeStatus("poetA", "RUNNING", 0L, "type=AGENT");

        check("宿主发布一条节点进度", wf.bus.events.size() == 1);
        TLMsg evt = wf.bus.events.isEmpty() ? null : wf.bus.events.get(0);
        check("topic/destination 正确", evt != null
                && WORKFLOW_PROGRESS_TOPIC.equals(evt.getAction())
                && WORKFLOW_PROGRESS_TOPIC.equals(evt.getDestination()));
        check("事件字段完整", evt != null
                && "poetA".equals(evt.getStringParam("nodeId", ""))
                && "AGENT".equals(evt.getStringParam("nodeType", ""))
                && "RUNNING".equals(evt.getStringParam("status", ""))
                && "run-1".equals(evt.getStringParam("runId", ""))
                && "sid-1".equals(evt.getStringParam("sessionId", ""))
                && "sid-1".equals(evt.getStringParam("rootSessionId", ""))
                && "u1".equals(evt.getStringParam("userId", ""))
                && "r1".equals(evt.getStringParam("roundId", ""))
                && evt.getIntParam("totalNodes", 0) == 1
                && "wf-progress-publisher".equals(evt.getStringParam("workflowName", "")));

        publisher.onNodeStatus("_workflow", "DONE", 12L, "");
        check("工作流事件 nodeType=WORKFLOW", wf.bus.events.size() == 2
                && "WORKFLOW".equals(wf.bus.events.get(1).getStringParam("nodeType", "")));
    }

    /** 反射构造私有内部类（publisher 是运行期快照，不暴露成生产 API） */
    private static Object newPublisher(TLAgentWorkflow wf, TLMsg input,
                                       Map<String, TLWorkflowNode> nodes, String runId) throws Exception {
        Class<?> cls = null;
        for (Class<?> c : TLAgentWorkflow.class.getDeclaredClasses()) {
            if ("NodeProgressPublisher".equals(c.getSimpleName())) { cls = c; break; }
        }
        if (cls == null) throw new IllegalStateException("NodeProgressPublisher not found");
        java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor(
                TLAgentWorkflow.class, TLMsg.class, Map.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(wf, input, nodes, runId);
    }

    private static final class TestWorkflow extends TLAgentWorkflow {
        final FakeBus bus = new FakeBus();
        TestWorkflow(String name) { super(name); }
        @Override protected Object getModuleInFactory(String moduleName) {
            return "msgBus".equals(moduleName) ? bus : null;
        }
    }

    private static final class FakeBus implements IObject {
        final List<TLMsg> events = new ArrayList<>();
        @Override public String getName() { return "msgBus"; }
        @Override public TLMsg putMsg(IObject toWho, TLMsg msg) {
            events.add(msg);
            return new TLMsg().setParam(RESULT, true);
        }
        @Override public TLMsg getMsg(Object fromWho, TLMsg msg) {
            events.add(msg);
            return new TLMsg().setParam(RESULT, true);
        }
    }

    // ======================== 4. 超时取消 ========================

    private static void timeoutCancelsRunningTask() {
        FakeTask task = new FakeTask(-1);   // 永不完成
        TLWorkflowEngine engine = engine("n1", (node, input) -> task);
        engine.setWorkflowTimeout(150L);
        TLMsg result = engine.execute();

        check("超时后取消底层任务", task.cancelCount.get() == 1);
        check("超时后节点状态为 WORKFLOW_TIMEOUT",
                "WORKFLOW_TIMEOUT".equals(statusOf(result, "n1")));
        check("超时结果是 partial", !result.parseBoolean(RESULT, true)
                && "partial".equals(result.getStringParam("message", "")));
    }

    // ======================== 5. 中断取消 ========================

    private static void interruptCancelsRunningTask() throws Exception {
        FakeTask task = new FakeTask(-1);
        CountDownLatch started = new CountDownLatch(1);
        TLWorkflowEngine engine = engine("n1", (node, input) -> {
            started.countDown();
            return task;
        });
        AtomicReference<TLMsg> resultRef = new AtomicReference<>();
        Thread runner = new Thread(() -> resultRef.set(engine.execute()), "wf-selftest-interrupt");
        runner.start();
        if (!started.await(5, TimeUnit.SECONDS)) {
            check("中断场景：节点已启动", false);
            return;
        }
        runner.interrupt();
        runner.join(5000);

        check("中断后取消底层任务", task.cancelCount.get() == 1);
        TLMsg result = resultRef.get();
        check("中断后工作流状态为 INTERRUPTED",
                result != null && "INTERRUPTED".equals(statusOf(result, "_workflow")));
    }

    // ======================== 6. userMessage 回落 ========================

    private static void userMessageFallbackToWorkflowInput() {
        TLAgentWorkflow wf = new TLAgentWorkflow("wf-selftest");

        // key 存在但为空：getStringParam 的默认值不生效，必须显式 isEmpty 判断
        TLMsg emptyButPresent = new TLMsg()
                .setParam("userMessage", "")
                .setParam(WORKFLOW_INPUT, "hello")
                .setParam("other", "keep");
        TLMsg in1 = wf.buildWorkflowInput(emptyButPresent);
        check("空 userMessage 回落 workflowInput",
                "hello".equals(in1.getStringParam("userMessage", "")));
        check("其它调用方参数照常透传", "keep".equals(in1.getStringParam("other", "")));

        // 非空 userMessage 优先，且不会被 args 覆盖回空
        TLMsg both = new TLMsg()
                .setParam("userMessage", "direct")
                .setParam(WORKFLOW_INPUT, "fallback");
        check("非空 userMessage 优先",
                "direct".equals(wf.buildWorkflowInput(both).getStringParam("userMessage", "")));

        // 会话上下文（systemArgs）透传不受影响
        TLMsg withSid = new TLMsg().setParam("userMessage", "u")
                .setSystemParam(AI_P_SESSIONID, "sid")
                .setSystemParam("rootSessionId", "root")
                .setSystemParam("userId", "uid")
                .setSystemParam(AI_P_ROUNDID, "r1");
        TLMsg in3 = wf.buildWorkflowInput(withSid);
        check("systemArgs 会话上下文透传",
                "sid".equals(in3.getSystemParam(AI_P_SESSIONID, ""))
                        && "root".equals(in3.getSystemParam("rootSessionId", ""))
                        && "uid".equals(in3.getSystemParam("userId", ""))
                        && "r1".equals(in3.getSystemParam(AI_P_ROUNDID, "")));

        // 直接调 WORKFLOW_EXECUTE（不经过 master）时只有 sessionId：rootSessionId 要
        // 回落到它，否则节点进度事件没有父会话可路由（UI 会丢弃）
        TLMsg noRoot = new TLMsg().setParam("userMessage", "u")
                .setSystemParam(AI_P_SESSIONID, "sid2");
        TLMsg in4 = wf.buildWorkflowInput(noRoot);
        check("缺 rootSessionId 时回落到 sessionId",
                "sid2".equals(in4.getSystemParam("rootSessionId", "")));
    }

    // ======================== 7. 工具入口 task 兜底 ========================

    private static void toolTaskNullAndEmptyFallback() {
        TLAgentWorkflow wf = new TLAgentWorkflow("wf-selftest");

        TLMsg nullTask = skillMsg(null, "orig");
        wf.applyToolTask(nullTask);
        check("task=null 回落 AI_P_USERMESSAGE（不是字符串 \"null\"）",
                "orig".equals(nullTask.getStringParam("userMessage", "")));

        TLMsg emptyTask = skillMsg("", "orig");
        wf.applyToolTask(emptyTask);
        check("task=\"\" 回落 AI_P_USERMESSAGE", "orig".equals(emptyTask.getStringParam("userMessage", "")));

        TLMsg noSkillInput = new TLMsg().setParam(AI_P_USERMESSAGE, "orig");
        wf.applyToolTask(noSkillInput);
        check("无 SKILLINPUT 时用 AI_P_USERMESSAGE",
                "orig".equals(noSkillInput.getStringParam("userMessage", "")));

        TLMsg normal = skillMsg("do it", "orig");
        wf.applyToolTask(normal);
        check("非空 task 优先", "do it".equals(normal.getStringParam("userMessage", "")));
    }

    private static TLMsg skillMsg(Object task, String fallback) {
        Map<String, Object> input = new HashMap<>();
        input.put("task", task);
        return new TLMsg().setParam(AI_P_SKILLINPUT, input).setParam(AI_P_USERMESSAGE, fallback);
    }

    // ======================== 辅助 ========================

    private static TLWorkflowEngine engine(String nid, TLWorkflowEngine.NodeRunner runner) {
        Map<String, TLWorkflowNode> nodes = new LinkedHashMap<>();
        nodes.put(nid, new TLWorkflowNode(nid, TLWorkflowNodeType.AGENT));
        return new TLWorkflowEngine(nodes, Collections.emptyList(),
                new TLWorkflowContext(new TLMsg()), runner);
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
