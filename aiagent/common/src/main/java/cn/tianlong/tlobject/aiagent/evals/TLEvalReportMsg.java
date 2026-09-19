package cn.tianlong.tlobject.aiagent.evals;

import cn.tianlong.tlobject.base.TLMsg;

import java.util.ArrayList;
import java.util.List;

/**
 * 把报告（连同基线对比结果）挂到消息参数上。
 *
 * 评测层与测试层共用同一套字段名，上层（控制台 / webui / 将来的门禁）不必区分报告来自哪一层：
 *   baselineFound / baselineFile / baselineCompared / regressionCount / improvementCount /
 *   regressions / reportMarkdown
 *
 * 创建日期：2026/9/19
 * 作者:tianlong
 */
public class TLEvalReportMsg {

    private TLEvalReportMsg() {}

    /**
     * @param compareBaseline 调用方是否启用了基线对比（报告正文里要如实写出来）
     */
    public static TLMsg attach(TLMsg msg, TLEvalReport report, boolean compareBaseline) {
        TLEvalReport.Summary s = report.summary;
        return msg.setParam("baselineFound", s.baselineFound)
                .setParam("baselineFile", s.baselineFile)
                .setParam("baselineCompared", s.baselineCompared)
                .setParam("regressionCount", s.regressions.size())
                .setParam("improvementCount", s.improvements.size())
                .setParam("regressions", formatChanges(s.regressions))
                // 报告正文随响应一起回去：webui 直接把这份渲染出来读，
                // 不用再去点文件路径（与落盘的 .md 同源，内容一致）
                .setParam("reportMarkdown", report.toMarkdown(compareBaseline));
    }

    /** 变更列表转成给人看的一行行文字 */
    public static List<String> formatChanges(List<TLEvalReport.Change> changes) {
        List<String> list = new ArrayList<>();
        if (changes == null) return list;
        for (TLEvalReport.Change c : changes) {
            list.add(c.caseName + " (" + c.caseId + ")"
                    + ("flaky".equals(c.stability) ? " [flaky]" : ""));
        }
        return list;
    }
}
