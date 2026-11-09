package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.aiagent.approval.TLApprovalRequest;
import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * TLToolExecutor 路径守卫自测（runnable main）。
 * 覆盖：workspace 内放行、workspace 外触发 path_access 审批、跨用户直接拒绝、
 * 自己的 data 放行、审批请求携带 forceApproval/ruleId/operation/description、审批参数
 * 必须是真实工具参数（不是合成 payload）、拒绝/pending/已批准/预批准恢复/fail-closed、
 * 多个越界路径合并为一次审批、路径守卫不吞静态门禁。
 *
 * <p>说明：
 * <ul>
 *   <li>执行器实例是匿名子类（覆写 putLog），反射读写 TLToolExecutor 的私有字段
 *       必须沿继承链查找，不能只用 getClass().getDeclaredField。</li>
 *   <li>路径守卫先于静态审批，静态审批仍照常执行：桩里用 forcedCalls 单独统计
 *       forceApproval=true 的路径审批调用，避免与静态规则审批混为一谈。</li>
 *   <li>断点恢复的免复批按来源只跳过发起断点的那道门禁：origin=path_access 跳过路径门禁、
 *       静态门禁照常评估；origin=static（或空）跳过静态门禁、路径门禁照常评估。
 *       线上同一次越界调用会先后弹两次框（路径、静态），恢复必须与之对称。</li>
 * </ul>
 */
public class TLToolExecutorPathGuardSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    static class ApprovalStub implements IObject {
        int calls;
        /** 其中 forceApproval=true 的调用（路径守卫触发） */
        int forcedCalls;
        String state = TLApprovalRequest.APPROVED;
        String rejectReason = "测试拒绝原因";
        Map<String, Object> lastArgs = new LinkedHashMap<>();
        String lastForce, lastRuleId, lastOperation, lastDescription;

        @Override public String getName() { return "approvalStub"; }
        @Override public TLMsg putMsg(IObject toWho, TLMsg msg) { return null; }
        @Override public TLMsg getMsg(Object fromWho, TLMsg msg) {
            calls++;
            lastArgs = msg.getMapParam("toolArgs", new LinkedHashMap<>());
            lastForce = String.valueOf(msg.getParam("forceApproval", ""));
            lastRuleId = String.valueOf(msg.getParam("ruleId", ""));
            lastOperation = String.valueOf(msg.getParam("operation", ""));
            lastDescription = msg.getStringParam("description", "");
            if ("true".equals(lastForce)) forcedCalls++;
            return new TLMsg()
                    .setParam(AI_P_APPROVAL_STATE, state)
                    .setParam(AI_P_APPROVAL_ID, "ap1")
                    .setParam(AI_P_APPROVAL_REJECTREASON, rejectReason)
                    .setParam(AI_P_SKILLOUTPUT, TLApprovalRequest.PENDING.equals(state) ? "等待审批" : "");
        }
    }

    static class StubSkill extends TLBaseModule {
        boolean called;
        StubSkill() { super("pathGuardStubSkill"); }
        @Override protected TLBaseModule init() { return this; }
        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) { return null; }
        @Override public TLMsg getMsg(Object fromWho, TLMsg msg) {
            called = true;
            return createMsg().setParam(AI_P_SKILLOUTPUT, "ok");
        }
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("tlpathguard");
        String old = System.getProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
        try {
            System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, tmp.toString());
            Path outside = tmp.resolve("outside.txt").toAbsolutePath().normalize();
            Path inside = tmp.resolve("alice/a.txt").toAbsolutePath().normalize();

            // 1) workspace 内：只过静态审批（守卫不吞门禁，也不触发路径审批）
            ApprovalStub approval = new ApprovalStub();
            StubSkill skill = new StubSkill();
            Result r = run(approval, null, skill, false, "file_operation",
                    argsMap("operation", "write", "path", inside.toString()));
            check("workspace 内执行", r.moduleCalled && r.latchZero);
            check("workspace 内只过静态审批（1 次）", approval.calls == 1);
            check("workspace 内不触发路径审批", approval.forcedCalls == 0);

            // 2) 越界 pending：只有路径审批，静态审批被短路
            approval = new ApprovalStub();
            approval.state = TLApprovalRequest.PENDING;
            skill = new StubSkill();
            r = run(approval, null, skill, false, "file_operation",
                    argsMap("operation", "write", "path", outside.toString()));
            check("越界触发审批", approval.forcedCalls == 1 && !skill.called);
            check("越界 pending 只调用一次审批（路径）", approval.calls == 1);
            check("审批请求带 forceApproval", "true".equals(approval.lastForce));
            check("审批规则 path_access", "path_access".equals(approval.lastRuleId));
            check("审批操作 write", "write".equals(approval.lastOperation));
            check("description 含越界路径与工具名",
                    approval.lastDescription.contains(outside.toString())
                            && approval.lastDescription.contains("file_operation"));
            check("pending 结果回传", TLApprovalRequest.PENDING.equals(r.resultState));

            // 3) 审批参数是真实工具参数（合成 payload 会在断点恢复时覆盖掉真实调用）
            check("审批参数携带真实 path", pathEquals(approval.lastArgs.get("path"), outside));
            check("审批参数不再携带合成字段", !approval.lastArgs.containsKey("paths")
                    && !approval.lastArgs.containsKey("tool"));

            // 4) 越界 approved：路径 + 静态各一次，然后执行
            approval = new ApprovalStub();
            approval.state = TLApprovalRequest.APPROVED;
            skill = new StubSkill();
            r = run(approval, null, skill, false, "file_operation",
                    argsMap("operation", "read", "path", outside.toString()));
            check("批准后执行", skill.called);
            check("批准后路径+静态各一次审批", approval.calls == 2 && approval.forcedCalls == 1);

            // 5) 越界 rejected：不执行，状态与原因回传
            approval = new ApprovalStub();
            approval.state = TLApprovalRequest.REJECTED;
            skill = new StubSkill();
            r = run(approval, null, skill, false, "file_operation",
                    argsMap("operation", "write", "path", outside.toString()));
            check("拒绝后不执行", !skill.called && approval.calls == 1);
            check("拒绝状态回传", "rejected".equals(r.resultState));
            check("拒绝原因回传", r.stateExtra != null && r.stateExtra.contains("测试拒绝原因"));

            // 6) 预批准恢复 + 来源 path_access：只跳过路径门禁，静态门禁照常评估
            // （线上同一次越界调用会先后弹路径/静态两个框，恢复到静态环节不能少审）
            approval = new ApprovalStub();
            skill = new StubSkill();
            r = run(approval, null, skill, true, "path_access", "file_operation",
                    argsMap("operation", "write", "path", outside.toString()));
            check("预批准恢复(路径来源)：路径门禁免复批", approval.forcedCalls == 0);
            check("预批准恢复(路径来源)：静态门禁照常评估（1 次）", approval.calls == 1);
            check("预批准恢复(路径来源)：工具照常执行", skill.called);

            // 7) 预批准不能豁免跨用户硬拒绝（路径来源同样不豁免）
            approval = new ApprovalStub();
            skill = new StubSkill();
            r = run(approval, null, skill, true, "path_access", "file_operation",
                    argsMap("operation", "read", "path", tmp.resolve("bob/b.txt").toString()));
            check("预批准不豁免跨用户拒绝", !skill.called && approval.calls == 0
                    && r.output.contains("无权访问其他用户目录"));

            // 8) 预批准恢复 + 来源 static：只跳过静态门禁，路径门禁仍须评估
            // （静态断点在线时先过路径门禁才轮到静态审批，恢复到路径门禁仍要拦越界）
            approval = new ApprovalStub();
            approval.state = TLApprovalRequest.PENDING;
            skill = new StubSkill();
            r = run(approval, null, skill, true, "static", "file_operation",
                    argsMap("operation", "write", "path", outside.toString()));
            check("预批准恢复(静态来源)：路径门禁仍评估（1 次强制审批）", approval.forcedCalls == 1);
            check("预批准恢复(静态来源)：pending 不执行", !skill.called
                    && TLApprovalRequest.PENDING.equals(r.resultState));

            // 9) 自己的 data：放行，只过静态审批
            approval = new ApprovalStub();
            skill = new StubSkill();
            Path dataOwn = Paths.get("data").toAbsolutePath().normalize().resolve("alice/uploads/x.png");
            r = run(approval, null, skill, false, "file_operation",
                    argsMap("operation", "read", "path", dataOwn.toString()));
            check("自己的 data 放行", skill.called && approval.forcedCalls == 0);
            check("自己的 data 只过静态审批（1 次）", approval.calls == 1);

            // 9b) 自己的 workspace 上传件：放行，只过静态审批
            approval = new ApprovalStub();
            skill = new StubSkill();
            Path wsUpload = tmp.resolve("alice/uploads/pic.png").toAbsolutePath().normalize();
            r = run(approval, null, skill, false, "file_operation",
                    argsMap("operation", "read", "path", wsUpload.toString()));
            check("自己的 workspace 上传件放行", skill.called && approval.forcedCalls == 0);
            check("自己的 workspace 上传件只过静态审批（1 次）", approval.calls == 1);

            // 10) 配置了审批模块但不可用：fail-closed（不执行，状态 unavailable）
            approval = new ApprovalStub();
            skill = new StubSkill();
            r = run(null, "nonexistentApprovalModule", skill, false, "file_operation",
                    argsMap("operation", "write", "path", outside.toString()));
            check("审批不可用 fail-closed 不执行", !skill.called);
            check("审批不可用状态 unavailable", TLApprovalRequest.UNAVAILABLE.equals(r.resultState));

            // 11) 未配置审批模块：NEED_APPROVAL 放行（跨用户仍硬拒绝，不 fail-closed），
            // 且只打一次 WARN（executor 内部 pathGuardWarned，本测试主要锁行为）
            skill = new StubSkill();
            r = run(null, null, skill, false, "file_operation",
                    argsMap("operation", "write", "path", outside.toString()));
            check("未配置审批模块放行越界（跨用户仍拒绝）", skill.called);

            // 12) word 多个越界路径：合并为一次强制审批，description 含全部路径
            approval = new ApprovalStub();
            approval.state = TLApprovalRequest.PENDING;
            skill = new StubSkill();
            Path outOne = tmp.resolve("one.docx").toAbsolutePath().normalize();
            Path outTwo = tmp.resolve("two.docx").toAbsolutePath().normalize();
            r = run(approval, null, skill, false, "word",
                    argsMap("action", "create", "path", outOne.toString(), "output", outTwo.toString()));
            check("word 双越界只触发一次强制审批",
                    approval.forcedCalls == 1 && approval.calls == 1 && !skill.called);
            check("description 含两个越界路径",
                    approval.lastDescription.contains(outOne.toString())
                            && approval.lastDescription.contains(outTwo.toString()));
            check("word 审批参数携带真实 path", pathEquals(approval.lastArgs.get("path"), outOne));
            check("word 审批参数携带真实 output", pathEquals(approval.lastArgs.get("output"), outTwo));
            check("word 审批操作按 action=create 归为 write", "write".equals(approval.lastOperation));
        } finally {
            if (old == null) System.clearProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
            else System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, old);
            deleteRecursively(tmp);
        }
        System.out.println("TLToolExecutorPathGuardSelfTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static class Result {
        boolean moduleCalled, latchZero;
        String resultState, stateExtra, output;
    }

    private static Result run(ApprovalStub approval, String approvalModuleName, StubSkill skill,
                              boolean preApproved, String functionName, Map<String, Object> args)
            throws Exception {
        return run(approval, approvalModuleName, skill, preApproved, "", functionName, args);
    }

    @SuppressWarnings("unchecked")
    private static Result run(ApprovalStub approval, String approvalModuleName, StubSkill skill,
                              boolean preApproved, String approvalOrigin, String functionName,
                              Map<String, Object> args)
            throws Exception {
        TLToolExecutor ex = new TLToolExecutor("pathGuardSelfTest") {
            @Override public void putLog(String content, LogLevel logLevel) { }
            @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }
        };
        if (approval != null) setField(ex, "approvalModule", approval);
        if (approvalModuleName != null) setField(ex, "approvalModuleName", approvalModuleName);
        setField(ex, "pathGuard", true);

        CountDownLatch latch = new CountDownLatch(1);
        Class<?> esCls = Class.forName("cn.tianlong.tlobject.aiagent.TLToolExecutor$ExecutionState");
        Constructor<?> ctor = esCls.getDeclaredConstructor(String.class, String.class, CountDownLatch.class);
        ctor.setAccessible(true);
        Object state = ctor.newInstance("exec1", "sess1", latch);
        Field totalF = esCls.getDeclaredField("total");
        totalF.setAccessible(true);
        totalF.setInt(state, 1);
        Map<String, Object> states = (Map<String, Object>) getField(ex, "states");
        states.put("exec1", state);

        TLToolExecutor.ToolTask task = new TLToolExecutor.ToolTask(
                "call1", skill, SKILL_EXECUTE, new LinkedHashMap<>(args));
        task.functionName = functionName;

        TLMsg execMsg = new TLMsg()
                .setParam("_task", task)
                .setParam("_idx", 0)
                .setSystemParam("executionId", "exec1")
                .setSystemParam(AI_P_SESSIONID, "sess1")
                .setSystemParam("userId", "alice")
                .setSystemParam("rootSessionId", "sess1")
                .setSystemParam(AI_P_ROUNDID, "round1");
        if (preApproved) {
            // 模拟断点恢复：executeTools 会给单任务批次转发决策与来源
            execMsg.setSystemParam(AI_P_APPROVAL_DECISION, TLApprovalRequest.APPROVED);
            if (approvalOrigin != null && !approvalOrigin.isEmpty()) {
                execMsg.setSystemParam(AI_P_APPROVAL_ORIGIN, approvalOrigin);
            }
        }
        Method doExec = TLToolExecutor.class.getDeclaredMethod("doToolExec", Object.class, TLMsg.class);
        doExec.setAccessible(true);
        doExec.invoke(ex, null, execMsg);

        Result r = new Result();
        r.moduleCalled = skill.called;
        r.latchZero = latch.getCount() == 0;
        Map<?, ?> results = (Map<?, ?>) getField(state, "results");
        TLToolExecutor.ToolResult tr = (TLToolExecutor.ToolResult) results.get(0);
        r.resultState = tr == null ? "" : tr.state;
        r.stateExtra = tr == null ? "" : tr.stateExtra;
        r.output = tr == null ? "" : tr.output;
        return r;
    }

    private static Map<String, Object> argsMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static boolean pathEquals(Object actual, Path expected) {
        if (!(actual instanceof String)) return false;
        return Paths.get((String) actual).toAbsolutePath().normalize().equals(expected);
    }

    /** 沿继承链找字段：执行器实例是匿名子类，字段声明在 TLToolExecutor */
    private static Object getField(Object o, String name) throws Exception {
        return findField(o.getClass(), name).get(o);
    }

    private static void setField(Object o, String name, Object value) throws Exception {
        findField(o.getClass(), name).set(o, value);
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static void deleteRecursively(Path p) {
        try {
            if (Files.isDirectory(p)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                    for (Path c : ds) deleteRecursively(c);
                }
            }
            Files.deleteIfExists(p);
        } catch (IOException ignored) { }
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
