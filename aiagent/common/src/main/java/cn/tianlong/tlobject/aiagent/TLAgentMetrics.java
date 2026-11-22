package cn.tianlong.tlobject.aiagent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Agent 轮次级指标（进程内累计，重启清零；轻量、无外部依赖）。
 *
 * <p>不做全链路指标体系，只回答运维最常问的：一共跑了多少轮、成功/超限/无进展/超时
 * 各多少、平均轮次/耗时、工具成功率。数据源是 commitTurn 的 TurnRecord/TurnOutcome，
 * 不额外解析日志。</p>
 *
 * <p>线程安全：LongAdder；snapshot 为弱一致快照，适合监控/健康检查，不用于精确对账。</p>
 */
final class TLAgentMetrics {

    private final LongAdder turns = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder truncated = new LongAdder();
    private final LongAdder noProgress = new LongAdder();
    private final LongAdder aborted = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder pending = new LongAdder();
    private final LongAdder clarified = new LongAdder();
    private final LongAdder timeout = new LongAdder();
    private final LongAdder earlyReturn = new LongAdder();
    private final LongAdder roundsTotal = new LongAdder();
    private final LongAdder durationTotal = new LongAdder();
    private final LongAdder toolCalls = new LongAdder();
    private final LongAdder toolErrors = new LongAdder();

    void record(TurnOutcome outcome, int rounds, long durationMs,
                int toolErrors, int toolCalls) {
        turns.increment();
        roundsTotal.add(Math.max(0, rounds));
        durationTotal.add(Math.max(0L, durationMs));
        this.toolErrors.add(Math.max(0, toolErrors));
        this.toolCalls.add(Math.max(0, toolCalls));
        switch (TLTurnResult.statusOf(outcome)) {
            case "completed":      completed.increment(); break;
            case "truncated":      truncated.increment(); break;
            case "no_progress":    noProgress.increment(); break;
            case "aborted":        aborted.increment(); break;
            case "failed":         failed.increment(); break;
            case "rejected":       rejected.increment(); break;
            case "pending_approval": pending.increment(); break;
            case "clarified":      clarified.increment(); break;
            case "timeout":        timeout.increment(); break;
            case "early_return":   earlyReturn.increment(); break;
            default: break;
        }
    }

    Map<String, Object> snapshot() {
        long t = turns.sum();
        long calls = toolCalls.sum();
        long errs = toolErrors.sum();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("turns", t);
        m.put("completed", completed.sum());
        m.put("truncated", truncated.sum());
        m.put("noProgress", noProgress.sum());
        m.put("aborted", aborted.sum());
        m.put("failed", failed.sum());
        m.put("rejected", rejected.sum());
        m.put("pendingApproval", pending.sum());
        m.put("clarified", clarified.sum());
        m.put("timeout", timeout.sum());
        m.put("earlyReturn", earlyReturn.sum());
        m.put("avgRounds", t > 0 ? round2((double) roundsTotal.sum() / t) : 0.0);
        m.put("avgDurationMs", t > 0 ? round2((double) durationTotal.sum() / t) : 0.0);
        m.put("toolCalls", calls);
        m.put("toolErrors", errs);
        m.put("toolSuccessRate", calls > 0
                ? round4((double) (calls - errs) / calls) : 1.0);
        m.put("overLimitRate", t > 0 ? round4((double) truncated.sum() / t) : 0.0);
        m.put("noProgressRate", t > 0 ? round4((double) noProgress.sum() / t) : 0.0);
        m.put("timeoutRate", t > 0 ? round4((double) timeout.sum() / t) : 0.0);
        return m;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
