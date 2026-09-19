package cn.tianlong.tlobject.aiagent.evals;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基线对比：把本次评测报告与上一次报告按用例 id 对比，标出回归与改善。
 *
 * 不依赖框架（只碰文件系统）——找哪一份报告当基准、怎么比，都能脱离运行环境验证，
 * 报告的解析与落盘留在模块里做。
 *
 * 创建日期：2026/9/19
 * 作者:tianlong
 */
public class TLEvalBaseline {

    private TLEvalBaseline() {}

    /** 报告文件名前缀，与 TLEvalsModule.saveReport 保持一致 */
    public static final String REPORT_PREFIX = "eval_report_";

    /** 报告目录里的全部报告（评测报告前缀），按文件名（时间戳）从新到旧 */
    public static List<File> listReportsNewestFirst(File reportDir) {
        return listReportsNewestFirst(reportDir, REPORT_PREFIX);
    }

    /** 指定前缀的版本：测试层用自己的前缀，两边的基线扫描互不干扰 */
    public static List<File> listReportsNewestFirst(File reportDir, String prefix) {
        List<File> reports = new ArrayList<>();
        if (reportDir == null) return reports;
        File[] files = reportDir.listFiles((d, n) -> n.startsWith(prefix) && n.endsWith(".json"));
        if (files == null || files.length == 0) return reports;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (int i = files.length - 1; i >= 0; i--) reports.add(files[i]);
        return reports;
    }

    /**
     * 两份报告是否评的同一个 Agent。
     * 报告目录是各应用共用的，目标不一致时对比出来的"回归"没有意义。
     * 旧报告没有 targetAgent 字段（该字段是后加的），返回 true 以保留跨版本的那次对比。
     */
    public static boolean sameScope(TLEvalReport a, TLEvalReport b) {
        if (a == null || b == null) return false;
        if (a.targetAgent == null || b.targetAgent == null) return true;
        return a.targetAgent.equals(b.targetAgent);
    }

    /**
     * 定位指定的基准报告：先按原样当路径找，再当成报告目录下的文件名找。
     * 都找不到返回 null（调用方据此保留 baselineFound=false，不能当成"无回归"）。
     */
    public static File resolveBaselineFile(File reportDir, String nameOrPath) {
        if (nameOrPath == null || nameOrPath.trim().isEmpty()) return null;
        File f = new File(nameOrPath);
        if (f.exists()) return f;
        if (reportDir != null) {
            f = new File(reportDir, nameOrPath);
            if (f.exists()) return f;
        }
        return null;
    }

    /**
     * 用基线报告填充本次报告的对比字段（覆盖 summary 里的对比项）。
     *
     * @param current      本次报告
     * @param baseline     上一次报告；传 null 表示没找到/读不出基准，此时 baselineFound=false
     * @param baselineFile 基准报告文件名，写进报告便于回溯"跟哪一次比的"
     */
    public static void compare(TLEvalReport current, TLEvalReport baseline, String baselineFile) {
        TLEvalReport.Summary s = current.summary;
        s.baselineFile = baselineFile;
        s.baselineFound = false;
        s.baselineCompared = 0;
        s.regressions = new ArrayList<>();
        s.improvements = new ArrayList<>();

        if (current.results == null) return;
        if (baseline == null || baseline.results == null) return;
        s.baselineFound = true;

        Map<String, TLEvalRunResult> old = new HashMap<>();
        for (TLEvalRunResult r : baseline.results) {
            if (r != null && r.caseId != null) old.put(r.caseId, r);
        }

        for (TLEvalRunResult r : current.results) {
            if (r == null || r.caseId == null) continue;
            TLEvalRunResult was = old.get(r.caseId);
            // 只在两次都存在的用例上比较：新增用例没有基准可比，
            // 删掉的用例也不该被算成"回归"
            if (was == null) continue;
            s.baselineCompared++;
            if (was.passed && !r.passed) {
                s.regressions.add(change(r, was));
            } else if (!was.passed && r.passed) {
                s.improvements.add(change(r, was));
            }
        }
    }

    private static TLEvalReport.Change change(TLEvalRunResult current, TLEvalRunResult baseline) {
        String stability = current.stability != null ? current.stability
                : (baseline.stability != null ? baseline.stability : "stable");
        return new TLEvalReport.Change(current.caseId, current.caseName, stability, shortReason(current));
    }

    /** 失败原因：优先取错误信息（超时、返回 null 等），否则取首个失败裁决的说明 */
    private static String shortReason(TLEvalRunResult r) {
        if (r.error != null && !r.error.isEmpty()) return r.error;
        if (r.verdicts != null) {
            for (TLEvalVerdict v : r.verdicts) {
                if (!v.passed && v.reason != null && !v.reason.isEmpty()) return v.reason;
            }
        }
        return null;
    }
}
