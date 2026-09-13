package cn.tianlong.tlobject.aiagent.workflow;

import java.util.HashMap;
import java.util.Map;

/**
 * 工作流节点定义
 *
 * @author tianlong
 * @since 2026/7/26
 */
public class TLWorkflowNode {

    /**
     * 字段级合并策略的配置前缀：{@code <param name="merge.<字段>" value="<策略>"/>}。
     * 这是图级配置不是消息参数——runAgentNode 会按此前缀过滤，不往下游透传。
     */
    public static final String MERGE_KEY_PREFIX = "merge.";

    /** 节点唯一标识 */
    private String id;

    /** 节点类型 */
    private TLWorkflowNodeType type = TLWorkflowNodeType.AGENT;

    /** Agent类型节点：目标模块名（工厂级单例，无 sameClassAs/classfile 时使用） */
    private String module;

    /** Agent类型节点：私有实例的模板模块名（sameClassAs 模式，如 "aiagent"） */
    private String sameClassAs;

    /** Agent类型节点：私有实例的类全限定名（classfile 模式） */
    private String classfile;

    /** Agent类型节点：调用的action */
    private String action = "chat";

    /** 节点参数（表达式、timeout 等） */
    private Map<String, String> params;

    /**
     * 字段级合并策略：key = 上游产出的字段名。
     * 来源是节点配置里的 merge.&lt;字段&gt; = &lt;策略名&gt;（见 TLAgentWorkflow.parseFieldReducers）。
     * 未声明的字段由引擎按类型自适应合并。
     */
    private Map<String, TLStateReducer> fieldReducers;

    /** 单节点超时（毫秒，0=不限制） */
    private long timeout;

    /** 失败处理策略：fail(默认) | skip | retry */
    private String onFailure = "fail";

    /** 重试次数（onFailure=retry时有效） */
    private int maxRetries;

    /** 超时处理策略：fail(默认) | skip */
    private String onTimeout = "fail";

    /** 降级节点id（失败/超时时走备选节点） */
    private String fallbackNodeId;

    public TLWorkflowNode() {}

    public TLWorkflowNode(String id, TLWorkflowNodeType type) {
        this.id = id;
        this.type = type;
    }

    // ===== Getters & Setters =====

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public TLWorkflowNodeType getType() { return type; }
    public void setType(TLWorkflowNodeType type) { this.type = type; }

    public String getModule() { return module; }
    public void setModule(String module) { this.module = module; }

    public String getSameClassAs() { return sameClassAs; }
    public void setSameClassAs(String sameClassAs) { this.sameClassAs = sameClassAs; }

    public String getClassfile() { return classfile; }
    public void setClassfile(String classfile) { this.classfile = classfile; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public Map<String, String> getParams() {
        if (params == null) params = new HashMap<>();
        return params;
    }
    public void setParams(Map<String, String> params) { this.params = params; }

    /** 字段级合并策略（无声明时返回空 Map，调用方可直接 get） */
    public Map<String, TLStateReducer> getFieldReducers() {
        if (fieldReducers == null) fieldReducers = new HashMap<>();
        return fieldReducers;
    }
    public void setFieldReducers(Map<String, TLStateReducer> fieldReducers) {
        this.fieldReducers = fieldReducers;
    }

    public long getTimeout() { return timeout; }
    public void setTimeout(long timeout) { this.timeout = timeout; }

    public String getOnFailure() { return onFailure; }
    public void setOnFailure(String onFailure) { this.onFailure = onFailure; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public String getOnTimeout() { return onTimeout; }
    public void setOnTimeout(String onTimeout) { this.onTimeout = onTimeout; }

    public String getFallbackNodeId() { return fallbackNodeId; }
    public void setFallbackNodeId(String fallbackNodeId) { this.fallbackNodeId = fallbackNodeId; }

    @Override
    public String toString() {
        return "Node[" + id + "] type=" + type
                + (module != null ? " module=" + module + "." + action : "");
    }
}
