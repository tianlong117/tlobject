package cn.tianlong.tlobject.aiagent.approval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条授权记录（会话级/轮次级免审）。key 由 TLApprovalModule.grantKey() 统一构造。
 *
 * 创建日期：2026/10/12
 * 作者：tianlong
 */
public class ApprovalGrant {

    public static final String SCOPE_ROUND = "round";
    public static final String SCOPE_SESSION = "session";
    /** 通配：ruleId/operation 为 "*" = 匹配任意（自动运行即 *+*） */
    public static final String WILDCARD = "*";

    public String sessionId;   // 归一化后的对话根会话 id
    public String scope;       // "round" | "session"
    public String roundId;     // round 级必填；session 级为 ""
    public String ruleId;      // 规则唯一标识；"*" = 自动运行
    public String operation;   // 命中时的操作名；提取不到 = ""；"*" = 全部操作
    public String toolName;    // 授予时命中的工具名（列表展示）
    public long grantedAt;

    /** 是否为"自动运行"授权（ruleId="*" = 匹配任意规则的会话级通配授权） */
    public boolean isAutoRun() { return WILDCARD.equals(ruleId); }

    /** 列表展示文本：自动运行 / 规则[:操作] */
    public String display() {
        if (isAutoRun()) return "全部规则（自动运行）";
        String r = ruleId == null || ruleId.isEmpty() ? "(未知规则)" : ruleId;
        if (operation == null || operation.isEmpty()) return r;
        if (WILDCARD.equals(operation)) return r + "（全部操作）";
        return r + ":" + operation;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId == null ? "" : sessionId);
        m.put("scope", scope == null ? "" : scope);
        m.put("roundId", roundId == null ? "" : roundId);
        m.put("ruleId", ruleId == null ? "" : ruleId);
        m.put("operation", operation == null ? "" : operation);
        m.put("toolName", toolName == null ? "" : toolName);
        m.put("grantedAt", grantedAt);
        m.put("display", display());
        return m;
    }
}
