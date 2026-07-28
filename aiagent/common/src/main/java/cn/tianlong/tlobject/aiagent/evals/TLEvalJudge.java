package cn.tianlong.tlobject.aiagent.evals;

/**
 * 评判器接口。
 *
 * 创建日期：2026/7/29
 * 作者:tianlong
 */
public interface TLEvalJudge {
    String getType();

    TLEvalVerdict judge(TLEvalCase evalCase, TLEvalRunResult runResult, JudgeContext context);
}
