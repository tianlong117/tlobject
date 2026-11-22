package cn.tianlong.tlobject.aiagent;

import java.util.Map;

/**
 * TLTurnResult 输出契约自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>覆盖：Completed/DirectAnswer/Truncated/Aborted/Failed/Rejected/Pending/
 * Clarified/Timeout 的 status 与 errorCode 映射，以及 rounds/duration/toolErrorCount/
 * directAnswer 透传。</p>
 */
public class TLTurnResultSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        TurnOutcome.Tokens tokens = new TurnOutcome.Tokens(10, 20, 30, 0, 0, 0);

        Map<String, Object> done = TLTurnResult.build(
                new TurnOutcome.Completed("ok", 2, tokens, null, 1), "r1", 2, 1234, 1);
        check("completed status", "completed".equals(done.get("status")));
        check("completed 无错误码", "".equals(done.get("errorCode")));
        check("rounds/duration/toolErrors 透传",
                Integer.valueOf(2).equals(done.get("rounds"))
                        && Long.valueOf(1234L).equals(done.get("durationMs"))
                        && Integer.valueOf(1).equals(done.get("toolErrorCount")));
        check("roundId=traceId", "r1".equals(done.get("roundId"))
                && "r1".equals(done.get("traceId")));
        check("truncated=false/direct=false",
                Boolean.FALSE.equals(done.get("truncated"))
                        && Boolean.FALSE.equals(done.get("directAnswer")));

        Map<String, Object> direct = TLTurnResult.build(
                new TurnOutcome.DirectAnswer("poem", 1, tokens, 0), "r2", 1, 50, 0);
        check("DirectAnswer 归 completed", "completed".equals(direct.get("status")));
        check("DirectAnswer 标记 directAnswer", Boolean.TRUE.equals(direct.get("directAnswer")));

        Map<String, Object> trunc = TLTurnResult.build(
                new TurnOutcome.Truncated("max", 10, tokens), "r3", 10, 5000, 3);
        check("Truncated status", "truncated".equals(trunc.get("status")));
        check("Truncated errorCode", "max_iterations".equals(trunc.get("errorCode")));
        check("Truncated 标记", Boolean.TRUE.equals(trunc.get("truncated")));

        Map<String, Object> noProgress = TLTurnResult.build(
                new TurnOutcome.NoProgress("np", 5, tokens), "r3b", 5, 300, 2, 6);
        check("NoProgress status/errorCode", "no_progress".equals(noProgress.get("status"))
                && "no_progress".equals(noProgress.get("errorCode")));
        check("NoProgress toolCallCount 透传",
                Integer.valueOf(6).equals(noProgress.get("toolCallCount")));

        Map<String, Object> abort = TLTurnResult.build(
                new TurnOutcome.Aborted("user", true, tokens, 4), "r4", 4, 100, 0);
        check("Aborted status", "aborted".equals(abort.get("status")));
        check("Aborted errorCode 带原因", "user".equals(abort.get("errorCode")));

        Map<String, Object> fail = TLTurnResult.build(
                new TurnOutcome.Failed("boom"), "r5", 0, 10, 0);
        check("Failed status", "failed".equals(fail.get("status")));
        check("Failed errorCode=agent_error", ERRORCODE_AGENT.equals(fail.get("errorCode")));

        Map<String, Object> reject = TLTurnResult.build(
                new TurnOutcome.Rejected("no", "denied", 2, tokens, 1), "r6", 2, 80, 0);
        check("Rejected status/errorCode", "rejected".equals(reject.get("status"))
                && "rejected".equals(reject.get("errorCode")));

        Map<String, Object> pending = TLTurnResult.build(
                new TurnOutcome.PendingApproval("ap", "tc", "wait", 1, tokens), "r7", 1, 90, 0);
        check("Pending status/errorCode", "pending_approval".equals(pending.get("status"))
                && "pending_approval".equals(pending.get("errorCode")));

        Map<String, Object> clarify = TLTurnResult.build(
                new TurnOutcome.Clarified("q", "ask", 1, tokens, 1), "r8", 1, 60, 0);
        check("Clarified status 无错误码", "clarified".equals(clarify.get("status"))
                && "".equals(clarify.get("errorCode")));

        Map<String, Object> timeout = TLTurnResult.build(
                new TurnOutcome.Timeout("slow", 2, tokens), "r9", 2, 70, 1);
        check("Timeout status/errorCode", "timeout".equals(timeout.get("status"))
                && "timeout".equals(timeout.get("errorCode")));

        Map<String, Object> early = TLTurnResult.build(
                new TurnOutcome.EarlyReturn("stop", false), "r10", 0, 5, 0);
        check("EarlyReturn status", "early_return".equals(early.get("status")));

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
