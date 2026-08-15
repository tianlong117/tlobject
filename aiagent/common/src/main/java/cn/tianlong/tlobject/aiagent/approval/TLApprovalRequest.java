package cn.tianlong.tlobject.aiagent.approval;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 审批请求数据对象。持有一次审批的完整状态。
 *
 * <p>生命周期：工具调用被拦截 → 创建请求（PENDING）→ 审查人处理 → APPROVED / REJECTED / EXPIRED。
 *
 * 创建日期：2026/7/25
 * 作者：tianlong
 */
public class TLApprovalRequest {

    // ======================== 审批状态 ========================
    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String EXPIRED = "expired";

    // ======================== 风险级别 ========================
    public static final String LOW = "LOW";
    public static final String MEDIUM = "MEDIUM";
    public static final String HIGH = "HIGH";
    public static final String CRITICAL = "CRITICAL";

    // ======================== 字段 ========================

    private String approvalId;
    private String sessionId;
    private String toolName;
    private String operation;
    private String riskLevel;
    private String rationale;
    private String state;
    private Map<String, Object> toolArguments;
    private Map<String, Object> modifiedArguments;
    private String rejectionReason;
    private long createdAt;
    private long timeoutMs;
    private String toolCallId;

    // ======================== 构造器 ========================

    public TLApprovalRequest() {
        this.approvalId = UUID.randomUUID().toString().substring(0, 8);
        this.createdAt = System.currentTimeMillis();
        this.state = PENDING;
        this.toolArguments = new LinkedHashMap<>();
    }

    public TLApprovalRequest(String sessionId, String toolName, Map<String, Object> toolArguments,
                             String toolCallId) {
        this();
        this.sessionId = sessionId;
        this.toolName = toolName;
        this.toolCallId = toolCallId;
        if (toolArguments != null) {
            this.toolArguments = new LinkedHashMap<>(toolArguments);
        }
    }

    // ======================== 全链追踪字段（roundId/rootSessionId，审批请求消息携带） ========================

    private String roundId = "";
    private String rootSessionId = "";

    public String getRoundId() { return roundId; }
    public void setRoundId(String roundId) { this.roundId = roundId; }

    public String getRootSessionId() { return rootSessionId; }
    public void setRootSessionId(String rootSessionId) { this.rootSessionId = rootSessionId; }

    // ======================== getters / setters ========================

    public String getApprovalId() { return approvalId; }
    public void setApprovalId(String approvalId) { this.approvalId = approvalId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }

    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }

    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getToolArguments() { return toolArguments; }
    public void setToolArguments(Map<String, Object> toolArguments) {
        this.toolArguments = toolArguments != null ? new LinkedHashMap<>(toolArguments) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getModifiedArguments() { return modifiedArguments; }
    public void setModifiedArguments(Map<String, Object> modifiedArguments) {
        this.modifiedArguments = modifiedArguments != null ? new LinkedHashMap<>(modifiedArguments) : null;
    }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }

    /** 审批是否已超时 */
    public boolean isExpired() {
        return timeoutMs > 0 && (System.currentTimeMillis() - createdAt) > timeoutMs;
    }

    /** 获取实际应使用的参数（用户修改后的参数优先） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getEffectiveArguments() {
        if (modifiedArguments != null && !modifiedArguments.isEmpty()) {
            return modifiedArguments;
        }
        return toolArguments;
    }

    // ======================== 序列化 ========================

    /** 转为 Map 供持久化 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("approvalId", approvalId);
        map.put("sessionId", sessionId);
        map.put("toolName", toolName);
        map.put("operation", operation);
        map.put("riskLevel", riskLevel);
        map.put("rationale", rationale);
        map.put("state", state);
        map.put("toolArguments", toolArguments);
        map.put("modifiedArguments", modifiedArguments);
        map.put("rejectionReason", rejectionReason);
        map.put("createdAt", createdAt);
        map.put("timeoutMs", timeoutMs);
        map.put("toolCallId", toolCallId);
        return map;
    }

    /** 从 Map 恢复 */
    @SuppressWarnings("unchecked")
    public static TLApprovalRequest fromMap(Map<String, Object> map) {
        TLApprovalRequest req = new TLApprovalRequest();
        req.setApprovalId((String) map.get("approvalId"));
        req.setSessionId((String) map.get("sessionId"));
        req.setToolName((String) map.get("toolName"));
        req.setOperation((String) map.get("operation"));
        req.setRiskLevel((String) map.get("riskLevel"));
        req.setRationale((String) map.get("rationale"));
        req.setState((String) map.get("state"));
        req.setToolArguments((Map<String, Object>) map.get("toolArguments"));
        req.setModifiedArguments((Map<String, Object>) map.get("modifiedArguments"));
        req.setRejectionReason((String) map.get("rejectionReason"));
        if (map.get("createdAt") instanceof Number) {
            req.setCreatedAt(((Number) map.get("createdAt")).longValue());
        }
        if (map.get("timeoutMs") instanceof Number) {
            req.setTimeoutMs(((Number) map.get("timeoutMs")).longValue());
        }
        req.setToolCallId((String) map.get("toolCallId"));
        return req;
    }

    @Override
    public String toString() {
        return "TLApprovalRequest{" +
                "approvalId='" + approvalId + '\'' +
                ", toolName='" + toolName + '\'' +
                ", riskLevel='" + riskLevel + '\'' +
                ", state='" + state + '\'' +
                '}';
    }
}
