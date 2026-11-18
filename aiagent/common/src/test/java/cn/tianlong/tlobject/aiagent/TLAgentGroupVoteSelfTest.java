package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 组 vote 模式自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>覆盖：多数胜出、平票取成员顺序、[错误]/空白候选剔除、全部失败返回结构化失败、
 * supervisor 评审择优、评审垃圾/不存在成员回落多数、评审缺失回落多数。</p>
 */
public class TLAgentGroupVoteSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    /** 覆盖 runParallel（不真起线程）与 getModule（注入评审桩） */
    static class VoteGroup extends TLAgentGroup {
        LinkedHashMap<String, String> canned = new LinkedHashMap<>();
        LinkedHashMap<String, String> lastTasks;
        TLBaseModule judge;

        VoteGroup(String n) { super(n); }

        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }

        @Override
        protected LinkedHashMap<String, String> runParallel(LinkedHashMap<String, String> memberTasks,
                                                            RunContext ctx, int attempt) {
            lastTasks = new LinkedHashMap<>(memberTasks);
            return new LinkedHashMap<>(canned);
        }

        @Override
        protected Object getModule(String moduleName) {
            if (judge != null && supervisorName != null && supervisorName.equals(moduleName))
                return judge;
            return null;
        }
    }

    static class JudgeStub extends TLBaseModule {
        String response;
        JudgeStub(String n) { super(n); }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }
        @Override protected TLBaseModule init() { return this; }
        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) { return null; }
        @Override public TLMsg getMsg(Object fromWho, TLMsg msg) {
            return createMsg().setParam(RESULT, true).setParam(AI_P_RESPONSE, response);
        }
    }

    public static void main(String[] args) {
        majorityWins();
        tieKeepsMemberOrder();
        errorsAndBlanksDropped();
        allFailedIsStructuredFailure();
        judgeSelectsBest();
        invalidJudgeFallsBackToMajority();
        missingJudgeFallsBackToMajority();
        parseBestTolerant();

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static void majorityWins() {
        VoteGroup g = group("m1", "m2", "m3");
        g.canned.put("m1", "A");
        g.canned.put("m2", "B");
        g.canned.put("m3", "A");

        TLMsg r = g.vote("task", ctx());

        check("多数胜出 A", r.parseBoolean(RESULT, false)
                && "A".equals(r.getStringParam(AI_P_RESPONSE, "")));
        check("voteWinner=m1", "m1".equals(r.getStringParam("voteWinner", "")));
        check("任务发给全部成员", g.lastTasks.size() == 3
                && "task".equals(g.lastTasks.get("m2")));
    }

    private static void tieKeepsMemberOrder() {
        VoteGroup g = group("m1", "m2");
        g.canned.put("m1", "A");
        g.canned.put("m2", "B");

        TLMsg r = g.vote("task", ctx());

        check("平票取成员顺序最早（m1/A）", "m1".equals(r.getStringParam("voteWinner", ""))
                && "A".equals(r.getStringParam(AI_P_RESPONSE, "")));
    }

    private static void errorsAndBlanksDropped() {
        VoteGroup g = group("m1", "m2", "m3", "m4");
        g.canned.put("m1", "[错误] timeout");
        g.canned.put("m2", "   ");
        g.canned.put("m3", "A");
        g.canned.put("m4", "A");

        TLMsg r = g.vote("task", ctx());

        check("错误/空白候选不进表决", "m3".equals(r.getStringParam("voteWinner", ""))
                && "A".equals(r.getStringParam(AI_P_RESPONSE, "")));
    }

    private static void allFailedIsStructuredFailure() {
        VoteGroup g = group("m1", "m2");
        g.canned.put("m1", "[错误] timeout");
        g.canned.put("m2", "");

        TLMsg r = g.vote("task", ctx());

        check("全部失败返回 RESULT=false", !r.parseBoolean(RESULT, true));
        check("终答不是成员错误文本",
                !r.getStringParam(AI_P_RESPONSE, "").startsWith("[错误]"));
    }

    private static void judgeSelectsBest() {
        VoteGroup g = group("m1", "m2");
        g.canned.put("m1", "A");
        g.canned.put("m2", "B");
        JudgeStub judge = new JudgeStub("judge");
        judge.response = "{\"best\":\"m2\"}";
        g.judge = judge;
        g.supervisorName = "judge";

        TLMsg r = g.vote("task", ctx());

        check("评审择优 m2/B", "m2".equals(r.getStringParam("voteWinner", ""))
                && "B".equals(r.getStringParam(AI_P_RESPONSE, "")));
    }

    private static void invalidJudgeFallsBackToMajority() {
        VoteGroup g = group("m1", "m2", "m3");
        g.canned.put("m1", "A");
        g.canned.put("m2", "A");
        g.canned.put("m3", "B");
        JudgeStub judge = new JudgeStub("judge");
        judge.response = "I think m3 is better";       // 非 JSON
        g.judge = judge;
        g.supervisorName = "judge";

        TLMsg r = g.vote("task", ctx());
        check("评审垃圾回落多数（m1/A）", "m1".equals(r.getStringParam("voteWinner", ""))
                && "A".equals(r.getStringParam(AI_P_RESPONSE, "")));

        judge.response = "{\"best\":\"m9\"}";           // 不存在的成员
        r = g.vote("task", ctx());
        check("评审选未知成员回落多数", "m1".equals(r.getStringParam("voteWinner", "")));
    }

    private static void missingJudgeFallsBackToMajority() {
        VoteGroup g = group("m1", "m2");
        g.canned.put("m1", "A");
        g.canned.put("m2", "A");
        g.supervisorName = "missingJudge";   // getModule 返回 null
        g.judge = null;

        TLMsg r = g.vote("task", ctx());
        check("评审缺失回落多数", r.parseBoolean(RESULT, false)
                && "m1".equals(r.getStringParam("voteWinner", "")));
    }

    private static void parseBestTolerant() {
        VoteGroup g = group("m1");
        check("解析纯 JSON", "m2".equals(g.parseBestDirective("{\"best\":\"m2\"}")));
        check("解析围栏 JSON", "m1".equals(g.parseBestDirective("```json\n{\"best\":\"m1\"}\n```")));
        check("无 best 返回 null", g.parseBestDirective("{\"x\":1}") == null);
        check("垃圾返回 null", g.parseBestDirective("not json") == null);
        check("空白 best 返回 null", g.parseBestDirective("{\"best\":\"  \"}") == null);
    }

    // ======================== 辅助 ========================

    private static VoteGroup group(String... members) {
        VoteGroup g = new VoteGroup("voteSelftest");
        g.memberNames = members;
        return g;
    }

    private static TLAgentGroup.RunContext ctx() {
        return new TLAgentGroup.RunContext("sess", "root", "round", "user", null);
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
