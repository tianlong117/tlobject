package cn.tianlong.tlobject.aiagent;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 对话历史记录条目。表示一次对话中的一条消息（system/user/assistant/tool）。
 * 携带文本内容、tool calls、时间戳等元数据。
 * 作为POJO在TLMsg.args中传输，不继承TLMsg。
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public class TLConversationHistory implements Serializable {
    private static final long serialVersionUID = 7352549231086429889L;

    public enum Role {
        system, user, assistant, tool, reasoning
    }

    private Role role;
    private String content;
    private List<TLToolCall> toolCalls;
    private String toolCallId;
    private String name;
    private long timestamp;
    /** 会话内线性递增序号（TLAiContext 维护，摘要 coverSeq 对齐边界；随 rounds 持久化，恢复保留） */
    private long seq;
    private Map<String, Object> metadata;
    /** 推理/思考内容（ReAct thought chain） */
    private String reasoningContent;

    public TLConversationHistory() {
        this.timestamp = System.currentTimeMillis();
    }

    public TLConversationHistory(Role role, String content) {
        this();
        this.role = role;
        this.content = content;
    }

    public TLConversationHistory(Role role, List<TLToolCall> toolCalls) {
        this();
        this.role = role;
        this.toolCalls = toolCalls;
    }

    /**
     * 构造tool结果消息
     */
    public TLConversationHistory(String toolCallId, String toolName, String content) {
        this();
        this.role = Role.tool;
        this.toolCallId = toolCallId;
        this.name = toolName;
        this.content = content;
    }

    /**
     * 构造推理/思考条目（ReAct thought chain）
     */
    public static TLConversationHistory createReasoning(String reasoningContent) {
        TLConversationHistory h = new TLConversationHistory();
        h.role = Role.reasoning;
        h.reasoningContent = reasoningContent;
        return h;
    }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<TLToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<TLToolCall> toolCalls) { this.toolCalls = toolCalls; }

    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public String getReasoningContent() { return reasoningContent; }
    public void setReasoningContent(String reasoningContent) { this.reasoningContent = reasoningContent; }

    public boolean isAssistantWithToolCalls() {
        return role == Role.assistant && toolCalls != null && !toolCalls.isEmpty();
    }

    public boolean isTextOnly() {
        return content != null && (toolCalls == null || toolCalls.isEmpty());
    }

    @Override
    public String toString() {
        return "TLConversationHistory{role=" + role + ", content="
                + (content != null ? content.substring(0, Math.min(100, content.length())) : "null")
                + ", toolCalls=" + (toolCalls != null ? toolCalls.size() : 0) + '}';
    }
}
