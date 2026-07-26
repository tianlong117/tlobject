package cn.tianlong.tlobject.aiagent.workflow;

/**
 * 工作流节点类型枚举
 *
 * @author tianlong
 * @since 2026/7/26
 */
public enum TLWorkflowNodeType {
    /** 调用Agent/Skill的普通节点 */
    AGENT,
    /** 条件分支节点：根据上游结果表达式选择激活的边 */
    CONDITION,
    /** 汇聚等待节点：等待所有上游完成后合并结果 */
    JOIN,
    /** 扇出节点：一份输入复制到所有下游（显式声明并行） */
    FANOUT,
    /** 循环节点：对上游结果逐项或重试执行子图 */
    LOOP
}
