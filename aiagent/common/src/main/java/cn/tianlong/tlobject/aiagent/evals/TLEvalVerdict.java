package cn.tianlong.tlobject.aiagent.evals;

/**
 * 评判器裁决结果 POJO。
 *
 * 创建日期：2026/7/29
 * 作者:tianlong
 */
public class TLEvalVerdict {
    public String judgeType;
    public boolean passed;
    public double score;
    public String reason;

    public TLEvalVerdict() {}

    public TLEvalVerdict(String judgeType, boolean passed, double score, String reason) {
        this.judgeType = judgeType;
        this.passed = passed;
        this.score = score;
        this.reason = reason;
    }

    public static TLEvalVerdict pass(String judgeType, double score, String reason) {
        return new TLEvalVerdict(judgeType, true, score, reason);
    }

    public static TLEvalVerdict fail(String judgeType, double score, String reason) {
        return new TLEvalVerdict(judgeType, false, score, reason);
    }

    public String getJudgeType() { return judgeType; }
    public void setJudgeType(String judgeType) { this.judgeType = judgeType; }

    public boolean isPassed() { return passed; }
    public void setPassed(boolean passed) { this.passed = passed; }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
