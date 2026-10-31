package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLMsg;

/**
 * Agent 组进度发布器（msgBus topic=workflowProgress，source=group）。
 * 每次 chat 一个实例；bus 可空、会话 id 缺失、发布异常均静默 no-op——
 * 进度是旁路，不能影响组执行。
 */
public class TLAgentGroupProgressPublisher implements TLAiAgentParamString {

    public static final String SOURCE_GROUP = "group";
    public static final String NODE_GROUP = "_group";
    public static final String TYPE_GROUP = "GROUP";
    public static final String TYPE_MEMBER = "MEMBER";
    public static final String TYPE_SUPERVISOR = "SUPERVISOR";

    private final IObject bus;
    private final String sessionId;
    private final String rootSessionId;
    private final String userId;
    private final String roundId;
    private final String groupName;
    private final String groupFamilyName;
    private final String runId;
    private final int totalNodes;
    private boolean terminalSent;

    public TLAgentGroupProgressPublisher(IObject bus, TLMsg input, String groupName,
                                         String groupFamilyName, String runId, int totalNodes) {
        this.bus = bus;
        this.groupName = groupName != null ? groupName : "";
        this.groupFamilyName = groupFamilyName != null ? groupFamilyName : "";
        this.runId = runId != null ? runId : "";
        this.totalNodes = totalNodes;
        String sid = input != null ? str(input.getSystemParam(AI_P_SESSIONID, "")) : "";
        if (sid.isEmpty() && input != null) sid = input.getStringParam(AI_P_SESSIONID, "");
        this.sessionId = norm(sid);
        this.rootSessionId = norm(input != null ? str(input.getSystemParam(AI_P_ROOTSESSIONID, "")) : "");
        this.userId = norm(input != null ? str(input.getSystemParam(AI_P_USERID, "")) : "");
        this.roundId = norm(input != null ? str(input.getSystemParam(AI_P_ROUNDID, "")) : "");
    }

    public boolean isEnabled() {
        return bus != null && sessionId != null && !sessionId.isEmpty();
    }

    public void overall(String status, String detail, long durationMs) {
        if (!isEnabled()) return;
        if (isTerminal(status)) {
            if (terminalSent) return;
            terminalSent = true;
        }
        publish(NODE_GROUP, TYPE_GROUP, status, detail, 1, durationMs);
    }

    public void member(String nodeId, String nodeType, String status,
                       int attempt, long durationMs, String detail) {
        if (!isEnabled()) return;
        publish(nodeId, nodeType, status, detail, attempt, durationMs);
    }

    public void ensureTerminal(String status, String detail, long durationMs) {
        if (!isEnabled() || terminalSent) return;
        terminalSent = true;
        publish(NODE_GROUP, TYPE_GROUP, status, detail, 1, durationMs);
    }

    private static boolean isTerminal(String status) {
        return status != null && !"RUNNING".equals(status);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** systemArgs 显式 null 会被 String.valueOf 变成字面量 "null"，统一归一为空 */
    private static String norm(String value) {
        return value == null || "null".equals(value) ? "" : value;
    }

    private void publish(String nodeId, String nodeType, String status,
                         String detail, int attempt, long durationMs) {
        try {
            TLMsg evt = new TLMsg(WORKFLOW_PROGRESS_TOPIC)
                    .setParam("source", SOURCE_GROUP)
                    .setParam("groupName", groupName)
                    .setParam("groupFamilyName", groupFamilyName)
                    .setParam("workflowName", groupName)
                    .setParam("workflowFamilyName", groupFamilyName)
                    .setParam("runId", runId)
                    .setParam("nodeId", nodeId)
                    .setParam("nodeType", nodeType)
                    .setParam("status", status)
                    .setParam("durationMs", durationMs)
                    .setParam("detail", detail != null ? detail : "")
                    .setParam("totalNodes", totalNodes)
                    .setParam("attempt", attempt)
                    .setParam("sessionId", sessionId)
                    .setParam("rootSessionId", rootSessionId)
                    .setParam("userId", userId)
                    .setParam("roundId", roundId)
                    .setDestination(WORKFLOW_PROGRESS_TOPIC);
            bus.putMsg(bus, evt);
        } catch (Throwable ignored) {
            // 进度旁路：msgBus 故障不能影响组执行
        }
    }
}
