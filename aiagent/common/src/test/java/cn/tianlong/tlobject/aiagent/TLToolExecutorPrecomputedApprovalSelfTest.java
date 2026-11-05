package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLMsg;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * 预计算任务不经审批门禁自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>背景（2026-11-02）：澄清/非法调用/参数不完整/未知函数/校验失败这些预计算任务
 * 不执行任何模块，但旧代码先过审批门禁。配了"全部工具需审批"的兜底规则时，用户会被要求
 * 批准一次"请求澄清"。修复：预计算检查放在审批之前，直接返回 SKIPPED 结果。
 */
public class TLToolExecutorPrecomputedApprovalSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    /** 审批桩：被调用即计数（说明预计算任务错误地走了门禁） */
    static class ApprovalStub implements IObject {
        int calls;
        @Override public String getName() { return "approvalStub"; }
        @Override public TLMsg putMsg(IObject toWho, TLMsg msg) { return null; }
        @Override public TLMsg getMsg(Object fromWho, TLMsg msg) {
            calls++;
            return new TLMsg().setParam(RESULT, true).setParam(AI_P_APPROVAL_STATE, "pending");
        }
    }

    public static void main(String[] args) throws Exception {
        TLToolExecutor ex = new TLToolExecutor("precomputedApprovalSelfTest");

        ApprovalStub approval = new ApprovalStub();
        setField(ex, "approvalModule", approval);

        CountDownLatch latch = new CountDownLatch(1);
        Class<?> esCls = Class.forName("cn.tianlong.tlobject.aiagent.TLToolExecutor$ExecutionState");
        Constructor<?> ctor = esCls.getDeclaredConstructor(String.class, String.class, CountDownLatch.class);
        ctor.setAccessible(true);
        Object state = ctor.newInstance("exec1", "sess1", latch);
        Field totalF = esCls.getDeclaredField("total");
        totalF.setAccessible(true);
        totalF.setInt(state, 1);

        @SuppressWarnings("unchecked")
        Map<String, Object> states = (Map<String, Object>) getField(ex, "states");
        states.put("exec1", state);

        TLToolExecutor.ToolTask task = new TLToolExecutor.ToolTask(
                "call1", null, SKILL_EXECUTE, new LinkedHashMap<>());
        task.functionName = "request_clarification";
        task.precomputedOutput = "⚠️ 需要确认: 要继续吗？";

        TLMsg execMsg = new TLMsg()
                .setParam("_task", task)
                .setParam("_idx", 0)
                .setSystemParam("executionId", "exec1");
        Method doExec = TLToolExecutor.class.getDeclaredMethod("doToolExec", Object.class, TLMsg.class);
        doExec.setAccessible(true);
        doExec.invoke(ex, null, execMsg);

        check("预计算任务不调用审批门禁", approval.calls == 0);

        Map<?, ?> results = (Map<?, ?>) getField(state, "results");
        TLToolExecutor.ToolResult r = (TLToolExecutor.ToolResult) results.get(0);
        check("预计算结果直接返回", r != null && task.precomputedOutput.equals(r.output));
        check("latch 正常释放", latch.getCount() == 0);

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static Object getField(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void setField(Object o, String name, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
