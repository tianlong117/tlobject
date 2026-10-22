package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具参数身份净化回归自测（runnable main，无 JUnit——与仓库自测惯例一致）。
 *
 * <p>背景（2026-10-22）：LLM 的 function arguments 原样透传给技能，schedule_task 等技能
 * 又优先读 args 里的 userId/sessionId。用户只要诱导模型在参数里带上别人的 userId，
 * 就能越权 list/create/remove 对方的任务（confused deputy）。
 *
 * <p>判定：身份字段（userId/sessionId/rootSessionId）只允许来自执行器注入的 systemArgs；
 * LLM args 里的同名字段必须被覆盖（systemArgs 缺失则剔除），且不得改写原始 args。
 *
 * 失败以退出码 1 结束。
 */
public class TLToolExecutorIdentityGuardSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        // 1) 伪造三个身份字段 → 全部被 systemArgs 覆盖
        Map<String, Object> forged = new LinkedHashMap<>();
        forged.put("op", "list");
        forged.put(AI_P_USERID, "admin");
        forged.put(AI_P_SESSIONID, "webchat_admin_forged");
        forged.put("rootSessionId", "webchat_admin_forged");
        TLMsg trusted = new TLMsg()
                .setSystemParam("userId", "tianlong")
                .setSystemParam(AI_P_SESSIONID, "webchat_tianlong_real")
                .setSystemParam("rootSessionId", "webchat_tianlong_real");
        Map<String, Object> safe = TLToolExecutor.sanitizeIdentityArgs(forged, trusted);
        check("userId 以 systemArgs 为准", "tianlong".equals(safe.get(AI_P_USERID)));
        check("sessionId 以 systemArgs 为准", "webchat_tianlong_real".equals(safe.get(AI_P_SESSIONID)));
        check("rootSessionId 以 systemArgs 为准", "webchat_tianlong_real".equals(safe.get("rootSessionId")));
        check("业务参数保留", "list".equals(safe.get("op")));
        check("不修改原始 LLM args", "admin".equals(forged.get(AI_P_USERID)));

        // 2) systemArgs 缺失 → 仿造身份字段被剔除，而不是放行
        Map<String, Object> forged2 = new LinkedHashMap<>();
        forged2.put(AI_P_USERID, "admin");
        forged2.put(AI_P_SESSIONID, "victim");
        forged2.put("rootSessionId", "victim");
        Map<String, Object> safe2 = TLToolExecutor.sanitizeIdentityArgs(forged2, new TLMsg());
        check("systemArgs 缺失时剔除 userId", !safe2.containsKey(AI_P_USERID));
        check("systemArgs 缺失时剔除 sessionId", !safe2.containsKey(AI_P_SESSIONID));
        check("systemArgs 缺失时剔除 rootSessionId", !safe2.containsKey("rootSessionId"));

        // 3) systemArgs 为空串 → 同样剔除（不能把空身份当合法）
        Map<String, Object> forged3 = new LinkedHashMap<>();
        forged3.put(AI_P_USERID, "admin");
        TLMsg emptyTrusted = new TLMsg().setSystemParam("userId", "");
        Map<String, Object> safe3 = TLToolExecutor.sanitizeIdentityArgs(forged3, emptyTrusted);
        check("systemArgs 为空串时剔除伪造 userId", !safe3.containsKey(AI_P_USERID));

        // 4) 无身份字段的普通 args → 原样保留，且不凭空注入身份（不污染 MCP/业务参数形状）
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("path", "a.txt");
        Map<String, Object> safe4 = TLToolExecutor.sanitizeIdentityArgs(plain, trusted);
        check("普通参数不受影响", "a.txt".equals(safe4.get("path")));
        check("不凭空注入身份字段", !safe4.containsKey(AI_P_USERID) && !safe4.containsKey(AI_P_SESSIONID));

        // 5) null args → 空表（不 NPE，也不注入）
        check("null args 返回空表", TLToolExecutor.sanitizeIdentityArgs(null, trusted).isEmpty());

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
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
