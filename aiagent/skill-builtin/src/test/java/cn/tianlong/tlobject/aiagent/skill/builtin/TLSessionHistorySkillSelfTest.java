package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * session_history 技能自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>覆盖：search/read 结果排版；身份只认 systemArgs（SKILLINPUT 伪造 sessionId/userId
 * 不生效）；当前轮 roundId 传给 sessionManager 用于排除；模块缺失/参数缺失/未知 operation
 * 的中文错误。</p>
 */
public class TLSessionHistorySkillSelfTest extends TLSessionHistorySkill {

    private static int passed = 0, failed = 0;
    FakeSessionManager sm;
    boolean missingModule;

    public TLSessionHistorySkillSelfTest() { super("sessionHistorySkill"); }

    @Override public void putLog(String content, LogLevel logLevel) { }
    @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }

    @Override
    protected Object resolveSessionManager() {
        return missingModule ? null : sm;
    }

    static class FakeSessionManager extends TLBaseModule {
        TLMsg lastReq;
        TLMsg reply;
        FakeSessionManager(String name) { super(name); }
        @Override protected TLBaseModule init() { return this; }
        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
            lastReq = msg;
            return reply;
        }
        @Override public TLMsg getMsg(Object fromWho, TLMsg msg) {
            lastReq = msg;
            return reply;
        }
    }

    public static void main(String[] args) {
        TLSessionHistorySkillSelfTest s = new TLSessionHistorySkillSelfTest();
        s.params = new HashMap<>();
        s.setModuleParams();
        s.sm = new FakeSessionManager("fakeSessionManager");

        searchFormatsAndIdentity(s);
        readFormats(s);
        errors(s);

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static void searchFormatsAndIdentity(TLSessionHistorySkillSelfTest s) {
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("roundSeq", 1);
        r.put("roundId", "r1");
        r.put("role", "assistant");
        r.put("userMessage", "《可能的爱》最近挺火，主要评价是什么");
        r.put("snippet", "《可能的爱》影评汇总：李沧东导演……");
        r.put("createdAt", 1794634842000L);
        results.add(r);
        s.sm.reply = new TLMsg().setParam(RESULT, true)
                .setParam("results", results)
                .setParam("totalMatches", 2)
                .setParam("limit", 5);

        TLMsg out = call(s, "search", Map.of(
                "query", "可能的爱",
                "sessionId", "evil",       // 伪造：必须被忽略
                "userId", "eve"));         // 伪造：必须被忽略
        String text = out.getStringParam(AI_P_SKILLOUTPUT, "");

        check("search 成功", out.parseBoolean(RESULT, false));
        check("search 含命中轮次", text.contains("round 1") && text.contains("assistant"));
        check("search 含片段", text.contains("可能的爱") && text.contains("影评汇总"));
        check("search 提示 read", text.contains("operation=read"));
        check("search 使用 systemArgs sessionId", "s1".equals(s.sm.lastReq.getStringParam("sessionId", "")));
        check("search 使用 systemArgs userId", "alice".equals(s.sm.lastReq.getStringParam("userId", "")));
        check("search 传当前 roundId 排除", "r_cur".equals(s.sm.lastReq.getStringParam("excludeRoundId", "")));
        check("search 默认只看 assistant", "assistant".equals(s.sm.lastReq.getStringParam("role", "")));
    }

    private static void readFormats(TLSessionHistorySkillSelfTest s) {
        s.sm.reply = new TLMsg().setParam(RESULT, true)
                .setParam("roundSeq", 1)
                .setParam("roundId", "r1")
                .setParam("userMessage", "《可能的爱》最近挺火")
                .setParam("createdAt", 1794634842000L)
                .setParam("role", "assistant")
                .setParam("messageIndex", 1)
                .setParam("messageCount", 2)
                .setParam("content", "电影评论原文：李沧东的《可能的爱》……")
                .setParam("truncated", false);

        TLMsg out = call(s, "read", Map.of("roundSeq", 1));
        String text = out.getStringParam(AI_P_SKILLOUTPUT, "");

        check("read 成功", out.parseBoolean(RESULT, false));
        check("read 含来源轮次", text.contains("round 1") && text.contains("r1"));
        check("read 含原文", text.contains("电影评论原文") && text.contains("李沧东"));
        check("read 标注角色与下标", text.contains("assistant") && text.contains("1/2"));
    }

    private static void errors(TLSessionHistorySkillSelfTest s) {
        TLMsg noSession = callWithSystem(s, "search", Map.of("query", "x"), "", "alice", "r_cur");
        check("缺当前会话报错", !noSession.parseBoolean(RESULT, true)
                && out(noSession).contains("会话标识"));

        TLMsg noQuery = call(s, "search", Map.of());
        check("search 缺 query 报错", !noQuery.parseBoolean(RESULT, true));

        TLMsg noRound = call(s, "read", Map.of());
        check("read 缺轮次报错", !noRound.parseBoolean(RESULT, true));

        TLMsg badOp = call(s, "unknown", Map.of());
        check("未知 operation 报错", !badOp.parseBoolean(RESULT, true)
                && out(badOp).contains("未知 operation"));

        s.missingModule = true;
        TLMsg missing = call(s, "search", Map.of("query", "x"));
        check("模块缺失中文错误", !missing.parseBoolean(RESULT, true)
                && out(missing).contains("未配置"));
        s.missingModule = false;
    }

    // ======================== 辅助 ========================

    private static TLMsg call(TLSessionHistorySkillSelfTest s, String op, Map<String, Object> extra) {
        return callWithSystem(s, op, extra, "s1", "alice", "r_cur");
    }

    private static TLMsg callWithSystem(TLSessionHistorySkillSelfTest s, String op,
                                        Map<String, Object> extra, String sessionId,
                                        String userId, String roundId) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("operation", op);
        if (extra != null) input.putAll(extra);
        TLMsg msg = new TLMsg().setParam(AI_P_SKILLINPUT, input)
                .setSystemParam(AI_P_SESSIONID, sessionId)
                .setSystemParam(AI_P_USERID, userId)
                .setSystemParam(AI_P_ROUNDID, roundId)
                .setSystemParam("rootSessionId", sessionId)
                .setSystemParam(AI_P_AGENTNAME, "tester");
        return s.execute(null, msg);
    }

    private static String out(TLMsg r) {
        return r != null ? r.getStringParam(AI_P_SKILLOUTPUT, "") : "";
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
