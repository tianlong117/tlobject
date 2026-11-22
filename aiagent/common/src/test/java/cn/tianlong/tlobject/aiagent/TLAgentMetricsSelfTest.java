package cn.tianlong.tlobject.aiagent;

import java.util.Map;

/**
 * TLAgentMetrics 指标汇总自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>覆盖轮次/状态计数、平均轮次/耗时、工具成功率、超限/无进展/超时率。</p>
 */
public class TLAgentMetricsSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        TurnOutcome.Tokens tokens = new TurnOutcome.Tokens(1, 1, 2, 0, 0, 0);
        TLAgentMetrics m = new TLAgentMetrics();

        m.record(new TurnOutcome.Completed("ok", 2, tokens, null, 1), 2, 100, 1, 4);
        m.record(new TurnOutcome.Truncated("max", 10, tokens), 10, 1000, 0, 5);
        m.record(new TurnOutcome.NoProgress("np", 5, tokens), 5, 500, 2, 3);
        m.record(new TurnOutcome.Aborted("user", true, tokens, 1), 1, 10, 0, 0);
        m.record(new TurnOutcome.Failed("boom"), 0, 5, 0, 0);
        m.record(new TurnOutcome.PendingApproval("ap", "tc", "wait", 1, tokens), 1, 20, 0, 0);
        m.record(new TurnOutcome.Rejected("no", "denied", 2, tokens, 1), 2, 30, 0, 0);
        m.record(new TurnOutcome.Timeout("slow", 2, tokens), 2, 40, 0, 0);
        m.record(new TurnOutcome.DirectAnswer("poem", 1, tokens, 0), 1, 50, 0, 0);
        m.record(new TurnOutcome.Clarified("q", "ask", 1, tokens, 1), 1, 60, 0, 0);
        m.record(new TurnOutcome.EarlyReturn("stop", false), 0, 5, 0, 0);

        Map<String, Object> s = m.snapshot();
        check("turns=11", num(s, "turns") == 11);
        check("completed=2（Completed+DirectAnswer）", num(s, "completed") == 2);
        check("truncated=1", num(s, "truncated") == 1);
        check("noProgress=1", num(s, "noProgress") == 1);
        check("aborted/failed/pending/rejected 各 1",
                num(s, "aborted") == 1 && num(s, "failed") == 1
                        && num(s, "pendingApproval") == 1 && num(s, "rejected") == 1);
        check("timeout/clarified/earlyReturn 各 1",
                num(s, "timeout") == 1 && num(s, "clarified") == 1
                        && num(s, "earlyReturn") == 1);
        check("avgRounds=2.27", Math.abs(dbl(s, "avgRounds") - 2.27) < 0.001);
        check("toolCalls=12/toolErrors=3", num(s, "toolCalls") == 12
                && num(s, "toolErrors") == 3);
        check("toolSuccessRate=0.75", Math.abs(dbl(s, "toolSuccessRate") - 0.75) < 0.0001);
        check("overLimitRate=1/11", Math.abs(dbl(s, "overLimitRate") - 0.0909) < 0.0002);
        check("noProgressRate=1/11", Math.abs(dbl(s, "noProgressRate") - 0.0909) < 0.0002);
        check("timeoutRate=1/11", Math.abs(dbl(s, "timeoutRate") - 0.0909) < 0.0002);

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static long num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).longValue() : -1;
    }

    private static double dbl(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : -1;
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
