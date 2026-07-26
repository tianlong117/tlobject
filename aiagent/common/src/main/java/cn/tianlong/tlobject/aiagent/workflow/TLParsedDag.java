package cn.tianlong.tlobject.aiagent.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MD 文件 YAML frontmatter 解析后的 DAG 结构 POJO。
 * 用于在 Planner 和 Workflow 之间传递解析后的工作流定义。
 *
 * @author tianlong
 * @since 2026/7/26
 */
public class TLParsedDag {

    /** 计划名称 */
    private String planName;

    /** 计划描述 */
    private String description;

    /** 节点列表（保持顺序） */
    private Map<String, TLWorkflowNode> nodes = new LinkedHashMap<>();

    /** 边列表 */
    private List<TLWorkflowEdge> edges = new ArrayList<>();

    /** MD 正文部分（人类可读描述） */
    private String body;

    public TLParsedDag() {}

    // ===== Getters & Setters =====

    public String getPlanName() { return planName; }
    public void setPlanName(String planName) { this.planName = planName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, TLWorkflowNode> getNodes() { return nodes; }
    public void setNodes(Map<String, TLWorkflowNode> nodes) { this.nodes = nodes; }

    public List<TLWorkflowEdge> getEdges() { return edges; }
    public void setEdges(List<TLWorkflowEdge> edges) { this.edges = edges; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    /** 添加一个节点 */
    public void addNode(TLWorkflowNode node) {
        nodes.put(node.getId(), node);
    }

    /** 添加一条边 */
    public void addEdge(TLWorkflowEdge edge) {
        edges.add(edge);
    }

    /** 节点数 */
    public int getNodeCount() { return nodes.size(); }

    /** 边数 */
    public int getEdgeCount() { return edges.size(); }

    @Override
    public String toString() {
        return "ParsedDag[" + planName + "] nodes=" + nodes.size()
                + " edges=" + edges.size()
                + (description != null ? " desc=" + description : "");
    }
}
