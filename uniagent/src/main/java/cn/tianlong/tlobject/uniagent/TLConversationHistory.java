package cn.tianlong.tlobject.uniagent;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 对话历史记录条目。
 * 复制自 aiagent.TLConversationHistory，包名改为 uniagent。
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
    private Map<String, Object> metadata;
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

    public TLConversationHistory(String toolCallId, String toolName, String content) {
        this();
        this.role = Role.tool;
        this.toolCallId = toolCallId;
        this.name = toolName;
        this.content = content;
    }

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
