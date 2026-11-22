package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.aiagent.approval.TLApprovalRequest;
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
 * 工具/子 Agent 结构化结果契约自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>覆盖：普通工具 RESULT=false 标记 error + 错误码；缺省错误码回落 tool_error；
 * 成功仍为 done；旧技能不设 RESULT 保持 done；子 Agent/组边界把失败与显式错误码
 * 带回工具边界，且 LLM 可见输出文本不变。</p>
 */
public class TLToolResultContractSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    static class ReplySkill extends TLBaseModule {
        final TLMsg reply;
        ReplySkill(String n, TLMsg reply) {
            super(n);
            this.reply = reply;
        }
        @Override protected TLBaseModule init() { return this; }
        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) { return null; }
        @Override public TLMsg getMsg(Object fromWho, TLMsg msg) { return reply; }
    }

    /** 用固定 chat 结果模拟子 Agent 的委托边界 */
    static class TestAgent extends TLAiAgent {
        final TLMsg reply;
        TestAgent(String n, TLMsg reply) {
            super(n);
            this.reply = reply;
        }
        @Override protected TLMsg chat(Object fromWho, TLMsg msg) { return reply; }
        TLMsg runAsTool(TLMsg msg) { return executeAsTool("test", msg); }
    }

    /** 用固定 chat 结果模拟 Agent 组的工具边界 */
    static class TestGroup extends TLAgentGroup {
        final TLMsg reply;
        TestGroup(String n, TLMsg reply) {
            super(n);
            this.reply = reply;
        }
        @Override protected TLMsg chat(Object fromWho, TLMsg msg) { return reply; }
        TLMsg runAsTool(TLMsg msg) { return executeAsTool("test", msg); }
    }

    public static void main(String[] args) throws Exception {
        toolBusinessFailureCarriesCode();
        toolBusinessFailureDefaultsCode();
        toolSuccessStaysDone();
        legacyNoResultStaysDone();
        agentBoundaryCarriesFailure();
        agentBoundaryKeepsExplicitCode();
        agentBoundaryMissingResultIsSuccess();
        groupBoundaryCarriesFailure();
        groupBoundarySuccess();
        toolInputSchemaValidation();

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static void toolBusinessFailureCarriesCode() throws Exception {
        TLToolExecutor ex = executor("contractFailCode");
        ReplySkill skill = new ReplySkill("fileSkill",
                new TLMsg().setParam(RESULT, false)
                        .setParam(AI_P_ERRORCODE, "file_not_found")
                        .setParam(AI_P_SKILLOUTPUT, "文件不存在"));
        TLToolExecutor.ToolResult tr = run(ex, skill, "file_operation");

        check("失败：state 保持控制态语义（null）", tr.state == null || tr.state.isEmpty());
        check("失败：finalStatus=error", TOOL_STATUS_ERROR.equals(tr.finalStatus));
        check("失败：显式错误码保留", "file_not_found".equals(tr.errorCode));
        check("失败：LLM 可见输出文本不变", "文件不存在".equals(tr.output));
    }

    private static void toolBusinessFailureDefaultsCode() throws Exception {
        TLToolExecutor ex = executor("contractFailDefault");
        ReplySkill skill = new ReplySkill("bizSkill",
                new TLMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "业务失败"));
        TLToolExecutor.ToolResult tr = run(ex, skill, "biz_tool");

        check("失败未给码：finalStatus=error", TOOL_STATUS_ERROR.equals(tr.finalStatus));
        check("失败未给码：回落 tool_error", ERRORCODE_TOOL.equals(tr.errorCode));
        check("失败未给码：输出文本不变", "业务失败".equals(tr.output));
    }

    private static void toolSuccessStaysDone() throws Exception {
        TLToolExecutor ex = executor("contractOk");
        ReplySkill skill = new ReplySkill("okSkill",
                new TLMsg().setParam(RESULT, true)
                        .setParam(AI_P_SKILLOUTPUT, "ok"));
        TLToolExecutor.ToolResult tr = run(ex, skill, "ok_tool");

        check("成功：finalStatus=done", TOOL_STATUS_DONE.equals(tr.finalStatus));
        check("成功：无错误码", tr.errorCode == null || tr.errorCode.isEmpty());
    }

    private static void legacyNoResultStaysDone() throws Exception {
        TLToolExecutor ex = executor("contractLegacy");
        ReplySkill skill = new ReplySkill("legacySkill",
                new TLMsg().setParam(AI_P_SKILLOUTPUT, "legacy ok"));
        TLToolExecutor.ToolResult tr = run(ex, skill, "legacy_tool");

        check("旧技能缺 RESULT：按成功处理", TOOL_STATUS_DONE.equals(tr.finalStatus));
        check("旧技能缺 RESULT：无错误码", tr.errorCode == null || tr.errorCode.isEmpty());
    }

    private static void agentBoundaryCarriesFailure() throws Exception {
        TLMsg child = new TLMsg().setParam(RESULT, false)
                .setParam(AI_P_RESPONSE, "Agent error: boom");
        TestAgent agent = new TestAgent("childAgent", child);
        TLMsg ret = agent.runAsTool(toolMsg());

        check("Agent 边界：RESULT=false 上抛", !ret.parseBoolean(RESULT, true));
        check("Agent 边界：错误码 agent_error", ERRORCODE_AGENT.equals(ret.getStringParam(AI_P_ERRORCODE, "")));
        check("Agent 边界：输出文本不变",
                "Agent error: boom".equals(ret.getStringParam(AI_P_SKILLOUTPUT, "")));
    }

    private static void agentBoundaryKeepsExplicitCode() throws Exception {
        TLMsg child = new TLMsg().setParam(RESULT, false)
                .setParam(AI_P_ERRORCODE, "provider_quota")
                .setParam(AI_P_RESPONSE, "quota exceeded");
        TestAgent agent = new TestAgent("quotaAgent", child);
        TLMsg ret = agent.runAsTool(toolMsg());

        check("Agent 边界：显式错误码优先",
                "provider_quota".equals(ret.getStringParam(AI_P_ERRORCODE, "")));
    }

    private static void agentBoundaryMissingResultIsSuccess() throws Exception {
        TLMsg child = new TLMsg().setParam(AI_P_RESPONSE, "done without result flag");
        TestAgent agent = new TestAgent("legacyAgent", child);
        TLMsg ret = agent.runAsTool(toolMsg());

        check("Agent 边界：缺 RESULT 按成功", ret.parseBoolean(RESULT, false));
    }

    private static void groupBoundaryCarriesFailure() throws Exception {
        TLMsg child = new TLMsg().setParam(RESULT, false)
                .setParam(AI_P_RESPONSE, "Group step [a] failed: x");
        TestGroup group = new TestGroup("team", child);
        TLMsg ret = group.runAsTool(toolMsg());

        check("Group 边界：RESULT=false 上抛", !ret.parseBoolean(RESULT, true));
        check("Group 边界：错误码 group_error", ERRORCODE_GROUP.equals(ret.getStringParam(AI_P_ERRORCODE, "")));
        check("Group 边界：输出文本不变",
                "Group step [a] failed: x".equals(ret.getStringParam(AI_P_SKILLOUTPUT, "")));
    }

    private static void groupBoundarySuccess() throws Exception {
        TLMsg child = new TLMsg().setParam(RESULT, true).setParam(AI_P_RESPONSE, "team ok");
        TestGroup group = new TestGroup("teamOk", child);
        TLMsg ret = group.runAsTool(toolMsg());

        check("Group 边界：成功保持 true", ret.parseBoolean(RESULT, false));
        check("Group 边界：成功无错误码", ret.getStringParam(AI_P_ERRORCODE, "").isEmpty());
    }

    /** 入参 schema 不合法时执行器不执行模块，并回喂结构化参数错误。 */
    private static void toolInputSchemaValidation() throws Exception {
        TLToolExecutor ex = executor("contractSchema");
        SchemaSkill skill = new SchemaSkill("schemaSkill");
        Map<String, Object> pathDef = new LinkedHashMap<>();
        pathDef.put("type", "string");
        pathDef.put("required", true);
        skill.parameterSchema = new LinkedHashMap<>();
        skill.parameterSchema.put("path", pathDef);

        TLToolExecutor.ToolResult bad = run(ex, skill, "schema_tool", new LinkedHashMap<>());
        check("schema：非法参数不执行", skill.calls == 0);
        check("schema：标 error", TOOL_STATUS_ERROR.equals(bad.finalStatus));
        check("schema：回喂参数错误",
                bad.output != null && bad.output.contains("参数错误")
                        && bad.output.contains("path"));

        TLToolExecutor.ToolResult ok = run(ex, skill, "schema_tool", Map.of("path", "x"));
        check("schema：合法参数执行", skill.calls == 1
                && TOOL_STATUS_DONE.equals(ok.finalStatus));
    }

    static class SchemaSkill extends TLBaseSkill {
        int calls;
        SchemaSkill(String n) { super(n); }
        @Override protected TLMsg execute(Object fromWho, TLMsg msg) {
            calls++;
            return createMsg().setParam(RESULT, true)
                    .setParam(AI_P_SKILLOUTPUT, "ok");
        }
    }

    // ======================== 辅助 ========================

    private static TLMsg toolMsg() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("task", "do it");
        return new TLMsg().setParam(AI_P_SKILLINPUT, input);
    }

    private static TLToolExecutor executor(String name) {
        return new TLToolExecutor(name) {
            @Override public void putLog(String content, LogLevel logLevel) { }
            @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }
        };
    }

    private static TLToolExecutor.ToolResult run(TLToolExecutor ex, TLBaseModule skill,
                                                 String functionName) throws Exception {
        return run(ex, skill, functionName, new LinkedHashMap<>());
    }

    private static TLToolExecutor.ToolResult run(TLToolExecutor ex, TLBaseModule skill,
                                                 String functionName,
                                                 Map<String, Object> args) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Class<?> esCls = Class.forName("cn.tianlong.tlobject.aiagent.TLToolExecutor$ExecutionState");
        Constructor<?> ctor = esCls.getDeclaredConstructor(String.class, String.class, CountDownLatch.class);
        ctor.setAccessible(true);
        String execId = "exec-" + System.nanoTime();
        Object state = ctor.newInstance(execId, "sess1", latch);
        Field totalF = esCls.getDeclaredField("total");
        totalF.setAccessible(true);
        totalF.setInt(state, 1);

        @SuppressWarnings("unchecked")
        Map<String, Object> states = (Map<String, Object>) getField(ex, "states");
        states.put(execId, state);

        TLToolExecutor.ToolTask task = new TLToolExecutor.ToolTask(
                "call1", skill, SKILL_EXECUTE, args);
        task.functionName = functionName;
        task.moduleName = skill.getName();

        TLMsg execMsg = new TLMsg()
                .setParam("_task", task)
                .setParam("_idx", 0)
                .setSystemParam("executionId", execId)
                .setSystemParam(AI_P_SESSIONID, "sess1")
                .setSystemParam("userId", "alice")
                .setSystemParam("rootSessionId", "sess1")
                .setSystemParam(AI_P_ROUNDID, "round1")
                .setSystemParam(AI_P_APPROVAL_DECISION, TLApprovalRequest.APPROVED)
                .setSystemParam(AI_P_APPROVAL_ORIGIN, "static");

        Method doExec = TLToolExecutor.class.getDeclaredMethod("doToolExec", Object.class, TLMsg.class);
        doExec.setAccessible(true);
        doExec.invoke(ex, null, execMsg);

        @SuppressWarnings("unchecked")
        Map<Integer, TLToolExecutor.ToolResult> results =
                (Map<Integer, TLToolExecutor.ToolResult>) getField(state, "results");
        TLToolExecutor.ToolResult tr = results.get(0);
        check("工具产生结果", tr != null);
        return tr;
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
