package cn.tianlong.tlobject.aiagent.workflow;

/**
 * 字段级合并策略（Reducer）。
 *
 * <p>工作流节点可能有多个上游，上游产出按字段合并进下游节点的输入。默认是覆盖式
 * （后合并的赢），但业务上常需要「两份审查意见都留着」「取最小分」这类语义——
 * 实现本接口即可声明某个字段怎么合并。
 *
 * <h3>实现契约（必须遵守，否则并行合并会出错）</h3>
 * <ul>
 *   <li><b>不得修改 existing / incoming</b>——它们可能是上游节点的产出对象，被多个下游共享；
 *       原地修改会污染其他消费者。需要新集合请自行 new。</li>
 *   <li><b>纯函数</b>——同一个 (existing, incoming) 必须得到同样的结果，不许依赖外部状态。
 *       合并可能对同一份上游产出发生多次（不同下游各合一次）。</li>
 *   <li>existing 首次合并时为 null；返回 null 等价于不写入该字段。</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * 合并发生在调度线程（{@code TLWorkflowEngine.collectUpstreamInput}），不在并行节点线程里，
 * 因此实现无需考虑并发；实现类应无可变状态，可安全共用于多个节点。
 *
 * @author tianlong
 * @since 2026/9/13
 */
public interface TLStateReducer {

    /**
     * 合并一个字段的已有值与新到值。
     *
     * @param existing 已合并的值，首次为 null
     * @param incoming 本次上游产出的值
     * @return 合并后的值；返回 null 表示丢弃 incoming，保持 existing
     */
    Object reduce(Object existing, Object incoming);
}
