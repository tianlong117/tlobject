package cn.tianlong.tlobject.aiagent.approval;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * TLApprovalModule 强制审批（forceApproval）回归自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>背景：路径守卫通过强制审批进入门禁，toolArgs 是**真实**工具参数；operation 由调用方
 * 显式归类（read/write/delete），不能依赖 extractOperation（word 的真实参数是 action=create）。
 * ruleId 由调用方显式给出，缺省按 toolName 兜底（不同强制调用方不共享隐含授权键）。
 *
 * <p>覆盖：非强制无规则放行、强制 pending、授权粒度（同规则+操作免审 / 不同操作仍审）、
 * 拒绝记忆（同参数重复直接拒绝）、ruleId 缺省兜底。
 */
public class TLApprovalModuleForcedSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    /**
     * 无工厂的独立审批模块：不读配置文件、日志静默、发往其它模块名（log/agentMonitor）
     * 的消息直接丢弃。审查人用工厂无关的 {@link TLTestApprovalReviewer}。
     */
    static class TestGate extends TLApprovalModule {
        TestGate() { super("forcedApprovalSelfTest"); }

        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
        @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }
        @Override public void putLog(TLMsg msg, LogLevel logLevel, String tag) { }

        /** 无工厂时没有 log/agentMonitor 等模块：不发（putMsg(String) 会打 stderr） */
        @Override public TLMsg putMsg(String moduleName, TLMsg msg) {
            return createMsg().setParam(IGNOREMODULEISNULL, false);
        }

        /** 按生产配置的等价参数启动（不读 XML） */
        TestGate boot() {
            params = new HashMap<>();
            params.put("reviewerClass", TLTestApprovalReviewer.class.getName());
            params.put("reviewerStrategy", "sync");
            params.put("approvalTimeoutMs", "0");
            params.put("approvalPendingTtlMs", "0");   // 断点 TTL 不排期：测试不依赖定时器
            setModuleParams();
            init();
            return this;
        }
    }

    public static void main(String[] args) {
        // 1) 非强制未知工具、未配置规则 → 放行，不打扰审查人
        TLTestApprovalReviewer.reset();
        TestGate gate = new TestGate().boot();
        TLMsg r = gate.getMsg(null, request(false, "unknown_tool",
                args("path", "/tmp/x"), null, null, null, "sess1"));
        check("非强制未知工具无规则 → approved",
                TLApprovalRequest.APPROVED.equals(r.getStringParam(AI_P_APPROVAL_STATE, "")));
        check("非强制无规则不调用审查人", TLTestApprovalReviewer.count() == 0);

        // 2) 强制审批：真实参数 + 显式 operation/ruleId/description → pending
        TLTestApprovalReviewer.reset();
        gate = new TestGate().boot();
        Map<String, Object> realArgs = args("operation", "write", "path", "/tmp/outside/a.txt");
        String desc = "访问工作目录之外的文件/目录需要审批: [/tmp/outside/a.txt] (工具: path_access)";
        r = gate.getMsg(null, request(true, "path_access", realArgs,
                "path_access", "write", desc, "sess-forced"));
        check("强制请求 → pending", TLApprovalRequest.PENDING.equals(r.getStringParam(AI_P_APPROVAL_STATE, "")));
        check("强制请求调用审查人 1 次", TLTestApprovalReviewer.count() == 1);
        TLApprovalRequest captured = TLTestApprovalReviewer.snapshot().get(0);
        check("审查人收到真实工具参数",
                "/tmp/outside/a.txt".equals(captured.getToolArguments().get("path"))
                        && "write".equals(captured.getToolArguments().get("operation")));
        check("请求 ruleId=path_access", "path_access".equals(captured.getRuleId()));
        check("请求 operation=write", "write".equals(captured.getOperation()));
        check("请求 description 已设置", desc.equals(captured.getDescription()));

        // 2b) 显式 operation 优先于 extractOperation（word 真实参数是 action=create）
        TLTestApprovalReviewer.reset();
        gate = new TestGate().boot();
        r = gate.getMsg(null, request(true, "word",
                args("action", "create", "path", "/tmp/outside/a.docx"),
                "path_access", "write", "d", "sess-word"));
        check("word 强制请求 → pending",
                TLApprovalRequest.PENDING.equals(r.getStringParam(AI_P_APPROVAL_STATE, "")));
        check("operation 取显式 write（不是 action=create）",
                "write".equals(TLTestApprovalReviewer.snapshot().get(0).getOperation()));

        // 2c) ruleId 缺省 → 按 toolName 兜底（不与其它强制调用方共享隐含键）
        TLTestApprovalReviewer.reset();
        gate = new TestGate().boot();
        r = gate.getMsg(null, request(true, "path_access",
                args("operation", "write", "path", "/tmp/outside/fallback.txt"),
                null, "write", "d", "sess-fallback"));
        check("ruleId 缺省 → toolName 兜底",
                TLApprovalRequest.PENDING.equals(r.getStringParam(AI_P_APPROVAL_STATE, ""))
                        && "path_access".equals(TLTestApprovalReviewer.snapshot().get(0).getRuleId()));

        // 3) 授权粒度：批准 write（session 范围）→ 同操作不同路径免审；read 仍需审批
        TLTestApprovalReviewer.reset();
        gate = new TestGate().boot();
        r = gate.getMsg(null, request(true, "path_access",
                args("operation", "write", "path", "/tmp/outside/one.txt"),
                "path_access", "write", "d", "sess-grant"));
        String approvalId = r.getStringParam(AI_P_APPROVAL_ID, "");
        check("授权用例：首单 pending",
                TLApprovalRequest.PENDING.equals(r.getStringParam(AI_P_APPROVAL_STATE, ""))
                        && !approvalId.isEmpty());
        TLMsg approveResp = gate.getMsg(null, new TLMsg().setAction(APPROVAL_APPROVE)
                .setParam(AI_P_APPROVAL_ID, approvalId)
                .setParam(AI_P_APPROVAL_SCOPE, "session"));
        check("授权用例：批准成功",
                TLApprovalRequest.APPROVED.equals(approveResp.getStringParam(AI_P_APPROVAL_STATE, "")));
        check("授权用例：审查人调用数仍为 1", TLTestApprovalReviewer.count() == 1);

        r = gate.getMsg(null, request(true, "path_access",
                args("operation", "write", "path", "/tmp/outside/two.txt"),
                "path_access", "write", "d", "sess-grant"));
        check("同操作不同路径 → 授权命中 approved",
                TLApprovalRequest.APPROVED.equals(r.getStringParam(AI_P_APPROVAL_STATE, "")));
        check("授权命中不再调审查人", TLTestApprovalReviewer.count() == 1);

        r = gate.getMsg(null, request(true, "path_access",
                args("operation", "read", "path", "/tmp/outside/three.txt"),
                "path_access", "read", "d", "sess-grant"));
        check("不同操作 read → 仍需审批 pending",
                TLApprovalRequest.PENDING.equals(r.getStringParam(AI_P_APPROVAL_STATE, "")));
        check("read 触发新的审查人调用", TLTestApprovalReviewer.count() == 2);

        // 4) 拒绝记忆：同参数重复直接 rejected，不重复弹框
        TLTestApprovalReviewer.reset();
        gate = new TestGate().boot();
        r = gate.getMsg(null, request(true, "path_access",
                args("operation", "delete", "path", "/tmp/outside/del.txt"),
                "path_access", "delete", "d", "sess-reject"));
        approvalId = r.getStringParam(AI_P_APPROVAL_ID, "");
        check("拒绝用例：首单 pending",
                TLApprovalRequest.PENDING.equals(r.getStringParam(AI_P_APPROVAL_STATE, ""))
                        && !approvalId.isEmpty());
        TLMsg rejectResp = gate.getMsg(null, new TLMsg().setAction(APPROVAL_REJECT)
                .setParam(AI_P_APPROVAL_ID, approvalId)
                .setParam(AI_P_APPROVAL_REJECTREASON, "不允许删除"));
        check("拒绝用例：拒绝成功",
                TLApprovalRequest.REJECTED.equals(rejectResp.getStringParam(AI_P_APPROVAL_STATE, "")));
        check("拒绝用例：审查人调用数为 1", TLTestApprovalReviewer.count() == 1);

        r = gate.getMsg(null, request(true, "path_access",
                args("operation", "delete", "path", "/tmp/outside/del.txt"),
                "path_access", "delete", "d", "sess-reject"));
        check("相同参数重复 → 拒绝记忆 rejected",
                TLApprovalRequest.REJECTED.equals(r.getStringParam(AI_P_APPROVAL_STATE, "")));
        check("拒绝记忆不重复弹框", TLTestApprovalReviewer.count() == 1);

        System.out.println("TLApprovalModuleForcedSelfTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static TLMsg request(boolean forced, String toolName, Map<String, Object> args,
                                 String ruleId, String operation, String description, String sessionId) {
        TLMsg m = new TLMsg().setAction(APPROVAL_REQUEST)
                .setParam("toolName", toolName)
                .setParam("toolArgs", args)
                .setParam("toolCallId", "call-" + Math.abs(Objects.hash(toolName, args, sessionId)))
                .setSystemParam(AI_P_SESSIONID, sessionId)
                .setSystemParam("rootSessionId", sessionId)
                .setSystemParam(AI_P_ROUNDID, "round1");
        if (forced) m.setParam(AI_P_APPROVAL_FORCE, true);
        if (ruleId != null) m.setParam(AI_P_APPROVAL_RULEID, ruleId);
        if (operation != null) m.setParam("operation", operation);
        if (description != null) m.setParam("description", description);
        return m;
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
