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
    FANOUT
    // 没有 LOOP：本引擎是纯无环 DAG（拓扑排序 + inDegree 计数都建立在无环前提上），
    // 图级循环会让"节点只执行一次"的 completed 集合、活入边计数、累积式状态合并全部失效。
    // 迭代需求由内部机制承担：agent 的 ReAct 循环 / 组 supervisor 打回重跑 / onFailure=retry。
    // 曾有一个 LOOP 枚举值，但引擎从未实现它——留着只会让人以为能用，
    // 配上 type="loop" 会静默退化成普通 agent 节点。故删除。
}
