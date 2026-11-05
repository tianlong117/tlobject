package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;

/**
 * sessionId 统一取值回归自测（runnable main，无 JUnit）。
 *
 * <p>背景（2026-11-05）：doChat/chatStream 用 getSystemParam、stopChat 用 getStringParam，
 * systemArgs 显式空串时两条路径会算出不同 key；args-only 的 stopChat 又读不到 systemArgs。
 * 统一为“systemArgs 非空优先 → args 非空兜底 → 默认值”。
 *
 * <p>失败以退出码 1 结束。
 */
public class TLAiAgentSessionIdResolveSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        // 1) systemArgs 优先
        TLMsg m1 = new TLMsg().setSystemParam(AI_P_SESSIONID, "sys").setParam(AI_P_SESSIONID, "arg");
        check("systemArgs 优先", "sys".equals(TLAiAgent.resolveSessionId(m1, "default")));

        // 2) systemArgs 缺失 → args 兜底（stopChat 现有调用方式）
        TLMsg m2 = new TLMsg().setParam(AI_P_SESSIONID, "arg-only");
        check("args 兜底", "arg-only".equals(TLAiAgent.resolveSessionId(m2, "default")));

        // 3) systemArgs 显式空串 → 回落 args，而不是把空串当 key
        TLMsg m3 = new TLMsg().setSystemParam(AI_P_SESSIONID, "").setParam(AI_P_SESSIONID, "arg2");
        check("systemArgs 空串回落 args", "arg2".equals(TLAiAgent.resolveSessionId(m3, "default")));

        // 4) systemArgs 字符串 "null" → 回落 args
        TLMsg m4 = new TLMsg().setSystemParam(AI_P_SESSIONID, "null").setParam(AI_P_SESSIONID, "arg3");
        check("systemArgs 'null' 回落 args", "arg3".equals(TLAiAgent.resolveSessionId(m4, "default")));

        // 5) 两边都没有/都是空 → 默认值
        check("缺失用默认值", "default".equals(TLAiAgent.resolveSessionId(new TLMsg(), "default")));
        check("两边空串用默认值", "default".equals(TLAiAgent.resolveSessionId(
                new TLMsg().setSystemParam(AI_P_SESSIONID, "").setParam(AI_P_SESSIONID, ""), "default")));
        check("null 消息用默认值", "default".equals(TLAiAgent.resolveSessionId(null, "default")));

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
