package cn.tianlong.tlobject.aiagent;

import java.util.Map;

/** 审批恢复的输入（Agent 查询 approvalGate 后填入；Turn 不依赖审批模块）。 */
record ApprovalResume(String state, TLToolCall pendingToolCall, String approvalId,
                      String decision, Map<String, Object> modifiedArgs, String rejectReason) {
}
