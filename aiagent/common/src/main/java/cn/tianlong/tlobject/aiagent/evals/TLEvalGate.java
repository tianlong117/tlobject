package cn.tianlong.tlobject.aiagent.evals;

import java.util.ArrayList;
import java.util.List;

/**
 * 评测门禁：把"评测跑完了"变成"这次能不能过"。
 *
 * 判定 = 通过率达标 且 回归数未超限。纯算法类，不依赖框架——规则能脱离运行环境验证。
 *
 * flaky 用例不计入任何一边：一条时好时坏的用例就能让门禁随机变红，久了就没人看它了。
 *
 * 创建日期：2026/9/19
 * 作者:tianlong
 */
public class TLEvalGate {

    private TLEvalGate() {}

    /** 判定结果 */
    public static class Result {
        public final boolean passed;
        public final List<String> failures;

        public Result(boolean passed, List<String> failures) {
            this.passed = passed;
            this.failures = failures;
        }
    }

    /**
     * @param report         本次报告
     * @param passRate       通过率阈值；&lt;=0 表示不启用通过率门禁
     * @param maxRegressions 允许的回归数上限；&lt;0 表示不检查回归
     */
    public static Result evaluate(TLEvalReport report, double passRate, int maxRegressions) {
        List<String> failures = new ArrayList<>();
        if (report == null || report.results == null) {
            return new Result(true, failures);
        }

        int counted = 0, passed = 0;
        for (TLEvalRunResult r : report.results) {
            if (!isStable(r)) continue;
            counted++;
            if (r.passed) passed++;
        }

        if (passRate > 0) {
            if (counted == 0) {
                // 悄悄放行会更糟：门禁看起来在工作，实际什么都没判
                failures.add("没有 stable 用例，无法判定通过率（用例是否全被标成了 flaky？）");
            } else {
                double rate = (double) passed / counted;
                if (rate < passRate) {
                    failures.add(String.format("通过率 %.1f%% < %.1f%%（stable 用例 %d/%d）",
                            rate * 100, passRate * 100, passed, counted));
                }
            }
        }

        if (maxRegressions >= 0) {
            List<TLEvalReport.Change> regs = new ArrayList<>();
            if (report.summary != null && report.summary.regressions != null) {
                for (TLEvalReport.Change c : report.summary.regressions) {
                    if ("stable".equals(stabilityOf(c.stability))) regs.add(c);
                }
            }
            if (regs.size() > maxRegressions) {
                StringBuilder ids = new StringBuilder();
                for (TLEvalReport.Change c : regs) {
                    if (ids.length() > 0) ids.append("、");
                    ids.append(c.caseId);
                }
                failures.add(String.format("出现 %d 项回归（上限 %d）: %s",
                        regs.size(), maxRegressions, ids));
            }
        }

        return new Result(failures.isEmpty(), failures);
    }

    private static boolean isStable(TLEvalRunResult r) {
        return "stable".equals(stabilityOf(r.stability));
    }

    /** 未标记一律按 stable 处理（与 TLEvalCase.getStability 的缺省一致） */
    private static String stabilityOf(String stability) {
        return (stability == null || stability.trim().isEmpty()) ? "stable" : stability.trim();
    }
}
