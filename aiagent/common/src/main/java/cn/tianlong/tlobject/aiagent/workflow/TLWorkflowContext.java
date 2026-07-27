package cn.tianlong.tlobject.aiagent.workflow;

import cn.tianlong.tlobject.base.TLMsg;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作流运行时上下文：节点间数据流转的载体。
 * 每个节点执行完后把产出写入，下游节点执行时从此读取。
 * 线程安全（节点可能并行执行）。
 *
 * @author tianlong
 * @since 2026/7/26
 */
public class TLWorkflowContext {

    /** 工作流的初始输入 */
    private TLMsg input;

    /** 各节点产出：key=nodeId, value=产出msg */
    private final Map<String, TLMsg> nodeOutputs = new ConcurrentHashMap<>();

    /** 各节点状态：key=nodeId, value=状态描述 */
    private final Map<String, String> nodeStatus = new ConcurrentHashMap<>();

    /** 各节点耗时（毫秒）：key=nodeId */
    private final Map<String, Long> nodeDuration = new ConcurrentHashMap<>();

    public TLWorkflowContext() {}

    public TLWorkflowContext(TLMsg input) {
        this.input = input;
    }

    /** 写入节点产出 */
    public void putOutput(String nodeId, TLMsg output) {
        if (output == null) output = new TLMsg();
        nodeOutputs.put(nodeId, output);
    }

    /** 读取节点产出 */
    public TLMsg getOutput(String nodeId) {
        return nodeOutputs.get(nodeId);
    }

    /** 按路径读取值，如 "filter.confidence" → 从 filter 节点的产出 msg 中取 confidence 字段 */
    public Object getValueByPath(String path) {
        if (path == null || path.isEmpty()) return null;
        int dot = path.indexOf('.');
        if (dot <= 0) return null;
        String nodeId = path.substring(0, dot);
        String field = path.substring(dot + 1);
        TLMsg output = nodeOutputs.get(nodeId);
        if (output == null) return null;
        return output.getParam(field);
    }

    /** 写入节点状态 */
    public void putStatus(String nodeId, String status) {
        if (nodeId == null) return;
        nodeStatus.put(nodeId, status != null ? status : "");
    }

    /** 写入节点耗时 */
    public void putDuration(String nodeId, long durationMs) {
        if (nodeId == null) return;
        nodeDuration.put(nodeId, durationMs);
    }

    // ===== Getters =====

    public TLMsg getInput() { return input; }
    public void setInput(TLMsg input) { this.input = input; }

    public Map<String, TLMsg> getNodeOutputs() { return nodeOutputs; }
    public Map<String, String> getNodeStatus() { return nodeStatus; }
    public Map<String, Long> getNodeDuration() { return nodeDuration; }
}
