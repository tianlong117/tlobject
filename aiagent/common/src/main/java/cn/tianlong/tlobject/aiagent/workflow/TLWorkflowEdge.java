package cn.tianlong.tlobject.aiagent.workflow;

/**
 * 工作流边定义：表达"to 依赖 from 的产出"。
 * condition 字段仅当 from 节点类型为 CONDITION 时使用：
 * - "true"  → 条件表达式为真时激活此边
 * - "false" → 条件表达式为假时激活此边
 * - null    → 无条件的普通依赖边
 *
 * @author tianlong
 * @since 2026/7/26
 */
public class TLWorkflowEdge {
    private String from;
    private String to;
    private String condition;

    public TLWorkflowEdge() {}

    public TLWorkflowEdge(String from, String to) {
        this.from = from;
        this.to = to;
    }

    public TLWorkflowEdge(String from, String to, String condition) {
        this.from = from;
        this.to = to;
        this.condition = condition;
    }

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }

    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }

    @Override
    public String toString() {
        return from + (condition != null ? "[" + condition + "]" : "") + " → " + to;
    }
}
