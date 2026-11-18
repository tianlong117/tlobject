package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.aiagent.approval.TLApprovalRequest;
import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * TLToolExecutor 健康度/熔断自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>覆盖：传输级失败达到阈值后短路（state 保持 null）、业务失败不熔断、
 * 冷却后半开探针成功复位、审批 pending 不计数、resetToolHealth 恢复、
 * fallback 只提示不改派、默认关闭时行为不变。</p>
 */
public class TLToolExecutorHealthSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    static class ThrowingSkill extends TLBaseModule {
        int calls;
        ThrowingSkill(String n) { super(n); }
        @Override protected TLBaseModule init() { return this; }
        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) { return null; }
        @Override public TLMsg getMsg(Object fromWho, TLMsg msg) {
            calls++;
            throw new RuntimeException("boom");
        }
    }

    static class OkSkill extends TLBaseModule {
        int calls;
        OkSkill(String n) { super(n); }
        @Override protected TLBaseModule init() { return this; }
        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) { return null; }
        @Override public TLMsg getMsg(Object fromWho, TLMsg msg) {
            calls++;
            return createMsg().setParam(AI_P_SKILLOUTPUT, "ok");
        }
    }

    static class BusinessFailSkill extends TLBaseModule {
        int calls;
        BusinessFailSkill(String n) { super(n); }
        @Override protected TLBaseModule init() { return this; }
        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) { return null; }
        @Override public TLMsg getMsg(Object fromWho, TLMsg msg) {
            calls++;
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "business error");
        }
    }

    static class ApprovalStub implements IObject {
        String state = TLApprovalRequest.PENDING;
        @Override public String getName() { return "approvalStub"; }
        @Override public TLMsg putMsg(IObject toWho, TLMsg msg) { return null; }
        @Override public TLMsg getMsg(Object fromWho, TLMsg msg) {
            return new TLMsg().setParam(AI_P_APPROVAL_STATE, state)
                    .setParam(AI_P_APPROVAL_ID, "ap1")
                    .setParam(AI_P_SKILLOUTPUT, "等待审批");
        }
    }

    public static void main(String[] args) throws Exception {
        transportFailuresOpenAndShortCircuit();
        businessFailureDoesNotOpen();
        cooldownProbeSuccessCloses();
        approvalPendingDoesNotCount();
        resetRestores();
        fallbackIsHintOnly();
        disabledKeepsOldBehavior();

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static void transportFailuresOpenAndShortCircuit() throws Exception {
        TLToolExecutor ex = executor("healthOpen", 2, 60000);
        ThrowingSkill skill = new ThrowingSkill("badSkill");

        Result r1 = run(ex, skill, "bad_tool");
        Result r2 = run(ex, skill, "bad_tool");
        Result r3 = run(ex, skill, "bad_tool");

        check("前两次异常真实执行", skill.calls == 2);
        check("第三次熔断短路（模块未再调用）", r3.calls == 2);
        check("短路结果是普通工具结果（state=null）", r3.resultState == null || r3.resultState.isEmpty());
        check("短路输出含熔断提示", r3.output != null && r3.output.contains("熔断"));
        check("前两次结果也是错误输出", r1.output != null && r2.output != null);
        check("健康状态 OPEN", "open".equals(healthState(ex, "badSkill")));
    }

    private static void businessFailureDoesNotOpen() throws Exception {
        TLToolExecutor ex = executor("healthBusiness", 2, 60000);
        BusinessFailSkill skill = new BusinessFailSkill("bizSkill");

        run(ex, skill, "biz_tool");
        run(ex, skill, "biz_tool");
        Result r3 = run(ex, skill, "biz_tool");

        check("业务失败每次都执行", skill.calls == 3);
        check("业务失败不短路", r3.output != null && !r3.output.contains("熔断"));
        check("业务失败后仍 CLOSED", "closed".equals(healthState(ex, "bizSkill")));
    }

    private static void cooldownProbeSuccessCloses() throws Exception {
        TLToolExecutor ex = executor("healthCooldown", 2, 120);
        ThrowingSkill bad = new ThrowingSkill("badSkill");
        run(ex, bad, "bad_tool");
        run(ex, bad, "bad_tool");
        check("冷却前 OPEN", "open".equals(healthState(ex, "badSkill")));

        Thread.sleep(160);
        OkSkill good = new OkSkill("probeSkill");
        Result probe = runWithModuleName(ex, good, "probe_tool", "badSkill");
        check("冷却后探针真实执行", good.calls == 1 && probe.output.contains("ok"));
        check("探针成功后 CLOSED", "closed".equals(healthState(ex, "badSkill")));

        Result after = runWithModuleName(ex, good, "probe_tool", "badSkill");
        check("恢复后继续执行", good.calls == 2 && after.output.contains("ok"));
    }

    private static void approvalPendingDoesNotCount() throws Exception {
        TLToolExecutor ex = executor("healthApproval", 2, 60000);
        ApprovalStub approval = new ApprovalStub();
        setField(ex, "approvalModule", approval);
        OkSkill skill = new OkSkill("approvalSkill");

        Result r = runWithApproval(ex, skill, "approval_tool");
        check("审批 pending 不执行", skill.calls == 0);
        check("审批 pending 状态回传", TLApprovalRequest.PENDING.equals(r.resultState));
        check("审批 pending 不产生失败计数", "closed".equals(healthState(ex, "approvalSkill"))
                && healthFailures(ex, "approvalSkill") == 0);
    }

    private static void resetRestores() throws Exception {
        TLToolExecutor ex = executor("healthReset", 1, 60000);
        ThrowingSkill bad = new ThrowingSkill("badSkill");
        run(ex, bad, "bad_tool");
        check("一次失败即 OPEN", "open".equals(healthState(ex, "badSkill")));

        resetHealth(ex, "badSkill");
        check("reset 后状态清空", healthState(ex, "badSkill") == null);

        OkSkill good = new OkSkill("badSkill");
        Result r = run(ex, good, "bad_tool");
        check("reset 后可继续执行", good.calls == 1 && r.output.contains("ok"));
    }

    private static void fallbackIsHintOnly() throws Exception {
        TLToolExecutor ex = executor("healthFallback", 1, 60000);
        healthFallbacks(ex).put("badSkill", "good_tool");
        ThrowingSkill bad = new ThrowingSkill("badSkill");
        OkSkill good = new OkSkill("goodSkill");
        run(ex, bad, "bad_tool");

        Result r = runWithModuleName(ex, good, "bad_tool", "badSkill");
        check("fallback 提示出现", r.output != null && r.output.contains("good_tool"));
        check("fallback 不自动改派", good.calls == 0);
    }

    private static void disabledKeepsOldBehavior() throws Exception {
        TLToolExecutor ex = executor("healthDisabled", 1, 60000);
        setField(ex, "healthEnabled", false);
        ThrowingSkill bad = new ThrowingSkill("badSkill");
        for (int i = 0; i < 3; i++) run(ex, bad, "bad_tool");
        check("默认关闭：每次都会执行", bad.calls == 3);
        check("默认关闭：不产生健康状态", healthStates(ex).isEmpty());
    }

    // ======================== 辅助 ========================

    static class Result {
        String resultState;
        String output;
        int calls;
    }

    private static TLToolExecutor executor(String name, int threshold, long cooldown) throws Exception {
        TLToolExecutor ex = new TLToolExecutor(name) {
            @Override public void putLog(String content, LogLevel logLevel) { }
            @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }
        };
        setField(ex, "healthEnabled", true);
        setField(ex, "healthFailureThreshold", threshold);
        setField(ex, "healthCooldownMs", cooldown);
        return ex;
    }

    private static Result run(TLToolExecutor ex, TLBaseModule skill, String functionName) throws Exception {
        return runInternal(ex, skill, functionName, skill.getName(), false);
    }

    private static Result runWithModuleName(TLToolExecutor ex, TLBaseModule skill,
                                            String functionName, String moduleName) throws Exception {
        return runInternal(ex, skill, functionName, moduleName, false);
    }

    private static Result runWithApproval(TLToolExecutor ex, TLBaseModule skill, String functionName)
            throws Exception {
        return runInternal(ex, skill, functionName, skill.getName(), true);
    }

    @SuppressWarnings("unchecked")
    private static Result runInternal(TLToolExecutor ex, TLBaseModule skill, String functionName,
                                      String moduleName, boolean approvalPath) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Class<?> esCls = Class.forName("cn.tianlong.tlobject.aiagent.TLToolExecutor$ExecutionState");
        Constructor<?> ctor = esCls.getDeclaredConstructor(String.class, String.class, CountDownLatch.class);
        ctor.setAccessible(true);
        String execId = "exec-" + System.nanoTime();
        Object state = ctor.newInstance(execId, "sess1", latch);
        Field totalF = esCls.getDeclaredField("total");
        totalF.setAccessible(true);
        totalF.setInt(state, 1);
        Map<String, Object> states = (Map<String, Object>) getField(ex, "states");
        states.put(execId, state);

        TLToolExecutor.ToolTask task = new TLToolExecutor.ToolTask(
                "call1", skill, SKILL_EXECUTE, new LinkedHashMap<>());
        task.functionName = functionName;
        task.moduleName = moduleName;

        TLMsg execMsg = new TLMsg()
                .setParam("_task", task)
                .setParam("_idx", 0)
                .setSystemParam("executionId", execId)
                .setSystemParam(AI_P_SESSIONID, "sess1")
                .setSystemParam("userId", "alice")
                .setSystemParam("rootSessionId", "sess1")
                .setSystemParam(AI_P_ROUNDID, "round1");
        if (!approvalPath) {
            // 跳过静态审批门禁：本测试关注健康度，不关注审批规则
            execMsg.setSystemParam(AI_P_APPROVAL_DECISION, TLApprovalRequest.APPROVED);
            execMsg.setSystemParam(AI_P_APPROVAL_ORIGIN, "static");
        }

        Method doExec = TLToolExecutor.class.getDeclaredMethod("doToolExec", Object.class, TLMsg.class);
        doExec.setAccessible(true);
        doExec.invoke(ex, null, execMsg);

        Result r = new Result();
        Map<?, ?> results = (Map<?, ?>) getField(state, "results");
        TLToolExecutor.ToolResult tr = (TLToolExecutor.ToolResult) results.get(0);
        r.resultState = tr == null ? "" : tr.state;
        r.output = tr == null ? "" : tr.output;
        r.calls = skillCalls(skill);
        return r;
    }

    private static int skillCalls(TLBaseModule skill) throws Exception {
        Field f = skill.getClass().getDeclaredField("calls");
        f.setAccessible(true);
        return f.getInt(skill);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> healthStates(TLToolExecutor ex) throws Exception {
        return (Map<String, Object>) getField(ex, "healthStates");
    }

    private static Object healthStateObj(TLToolExecutor ex, String key) throws Exception {
        return healthStates(ex).get(key);
    }

    private static String healthState(TLToolExecutor ex, String key) throws Exception {
        Object h = healthStateObj(ex, key);
        if (h == null) return null;
        Method m = h.getClass().getDeclaredMethod("state", long.class);
        m.setAccessible(true);
        return (String) m.invoke(h, System.currentTimeMillis());
    }

    private static int healthFailures(TLToolExecutor ex, String key) throws Exception {
        Object h = healthStateObj(ex, key);
        if (h == null) return 0;
        Method m = h.getClass().getDeclaredMethod("failures");
        m.setAccessible(true);
        return (Integer) m.invoke(h);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> healthFallbacks(TLToolExecutor ex) throws Exception {
        return (Map<String, String>) getField(ex, "healthFallbacks");
    }

    private static void resetHealth(TLToolExecutor ex, String tool) throws Exception {
        Method m = TLToolExecutor.class.getDeclaredMethod("resetToolHealth", TLMsg.class);
        m.setAccessible(true);
        m.invoke(ex, new TLMsg().setParam("tool", tool));
    }

    private static Object getField(Object o, String name) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setField(Object o, String name, Object value) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(o, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
