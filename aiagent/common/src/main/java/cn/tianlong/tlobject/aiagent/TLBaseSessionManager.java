package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.*;

/**
 * 会话管理抽象基类——消息分发、参数提取、roundId 去重、history 组装。
 *
 * 子类只需实现 4 个存储相关方法，即可支持文件或数据库等不同后端。
 *
 * @author tianlong
 * @date 2026/8/2
 */
public abstract class TLBaseSessionManager extends TLBaseModule implements TLAiAgentParamString {

    /** 是否开启断点保存/恢复 */
    protected boolean enableCheckpoint = false;

    /** JSON 序列化 */
    protected com.google.gson.Gson gson = new com.google.gson.Gson();

    // ======================== 构造函数 ========================

    public TLBaseSessionManager() { super(); }
    public TLBaseSessionManager(String name) { super(name); }
    public TLBaseSessionManager(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected TLBaseModule init() { return this; }

    // ======================== 子类必须实现的 4 个抽象方法 ========================

    /** 存储一轮会话数据 */
    protected abstract void storeRound(java.util.Map<String, Object> roundData);

    /** 加载指定会话的所有轮次原始数据，返回 List<Map>（每条包含 roundId, roundSeq, messages 等） */
    protected abstract java.util.List<java.util.Map<String, Object>> loadRoundData(String sessionId, String userId);

    /** 查找最近会话的元数据，返回 null 表示无会话 */
    protected abstract java.util.Map<String, Object> findLatestMeta(String userId);

    /**
     * 查找该用户最近的断点会话（state=checkpoint），返回 null 表示无断点。
     * 注意：断点会话不一定是最新会话（断点后用户可能又聊过其他会话）——
     * 必须按 state 过滤查最近一条 checkpoint，而不是"最近会话再判状态"。
     */
    protected abstract java.util.Map<String, Object> findIncompleteMeta(String userId);

    /** 删除指定会话的所有数据 */
    protected abstract void deleteSessionData(String sessionId, String userId);

    /** 列出所有会话的元数据列表 */
    protected abstract java.util.List<java.util.Map<String, Object>> listSessionsMeta(String userId);

    // ======================== 消息分发 ========================

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case "sessionUpdated":  return doSessionUpdated(fromWho, msg);
            case "chatFinished":    return doChatFinished(fromWho, msg);
            case "chatAborted":     return doChatAborted(fromWho, msg);
            case "findIncomplete":  return doFindIncomplete(fromWho, msg);
            case "listSessions":    return doListSessions(fromWho, msg);
            case "loadSession":     return doLoadSession(fromWho, msg);
            case "continueSession": return doContinueSession(fromWho, msg);
            case "deleteSession":   return doDeleteSession(fromWho, msg);
            default: return null;
        }
    }

    // ======================== Agent 通知处理 ========================

    @SuppressWarnings("unchecked")
    private TLMsg doSessionUpdated(Object fromWho, TLMsg msg) {
        if (!enableCheckpoint) return createMsg().setParam(RESULT, true);
        storeRound(buildRoundData(msg, msg.containsParam("pendingToolCall")
                ? SESSION_STATE_PENDING_APPROVAL : SESSION_STATE_CHECKPOINT));
        return createMsg().setParam(RESULT, true);
    }

    @SuppressWarnings("unchecked")
    private TLMsg doChatFinished(Object fromWho, TLMsg msg) {
        if (!enableCheckpoint) return createMsg().setParam(RESULT, true);
        storeRound(buildRoundData(msg, SESSION_STATE_COMPLETED));
        return createMsg().setParam(RESULT, true);
    }

    @SuppressWarnings("unchecked")
    private TLMsg doChatAborted(Object fromWho, TLMsg msg) {
        if (!enableCheckpoint) return createMsg().setParam(RESULT, true);
        // 人为停止（ESC/停止）存为 completed：本轮视为结束，不留断点。
        // 断点恢复只针对意外死机（进程被杀，agent 来不及收尾时 checkpoint 轮残留）。
        storeRound(buildRoundData(msg, SESSION_STATE_COMPLETED));
        return createMsg().setParam(RESULT, true);
    }

    /** 从消息中提取参数，构建轮次数据 Map */
    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> buildRoundData(TLMsg msg, String state) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("sessionId", msg.getStringParam("sessionId", ""));
        data.put("userId", msg.getStringParam("userId", ""));
        data.put("agentName", msg.getStringParam("agentName", ""));
        data.put("roundId", msg.getStringParam("roundId", ""));
        data.put("state", state);
        data.put("userMessage", msg.getStringParam("userMessage", ""));
        data.put("model", msg.getStringParam("model", ""));
        data.put("temperature", msg.getDoubleParam("temperature", 0.7));
        data.put("maxTokens", msg.getIntParam("maxTokens", 4096));
        data.put("messages", msg.getParam("messages"));
        if (msg.containsParam("pendingToolCall"))
            data.put("pendingToolCall", msg.getParam("pendingToolCall"));
        String approvalId = msg.getStringParam(AI_P_APPROVAL_ID, "");
        if (!approvalId.isEmpty()) data.put(AI_P_APPROVAL_ID, approvalId);
        return data;
    }

    // ======================== 控制台/agentService 查询 ========================

    /** 查找最近一个未完成的断点 */
    private TLMsg doFindIncomplete(Object fromWho, TLMsg msg) {
        // 查最近的 checkpoint 会话（按 state 过滤，而非"最近会话再判状态"——
        // 断点后用户可能又聊过其他会话，最近会话是 completed 也应能找到断点）
        java.util.Map<String, Object> meta = findIncompleteMeta(msg.getStringParam("userId", null));
        if (meta == null) return createMsg().setParam(RESULT, false);

        return createMsg()
                .setParam(RESULT, true)
                .setParam("sessionId", meta.getOrDefault("sessionId", ""))
                .setParam("agentName", meta.getOrDefault("agentName", ""))
                .setParam("userMessage", meta.getOrDefault("userMessage", ""))
                .setParam("savedAt", meta.getOrDefault("savedAt", 0L))
                .setParam("iteration", meta.getOrDefault("roundSeq", 0));
    }

    /** 列出所有历史会话 */
    @SuppressWarnings("unchecked")
    private TLMsg doListSessions(Object fromWho, TLMsg msg) {
        java.util.List<java.util.Map<String, Object>> sessions = listSessionsMeta(msg.getStringParam("userId", null));
        if (sessions == null) sessions = java.util.Collections.emptyList();
        return createMsg().setParam(RESULT, true).setParam("sessions", sessions);
    }

    /** 加载指定会话的完整数据 */
    @SuppressWarnings("unchecked")
    private TLMsg doLoadSession(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam("sessionId", "");
        String userId = msg.getStringParam("userId", null);
        TLMsg assembled = assembleSession(sessionId, userId);
        if (assembled == null)
            return createMsg().setParam(RESULT, false).setParam("error", "No checkpoint found");
        return assembled;
    }

    /** 继续历史会话 */
    @SuppressWarnings("unchecked")
    private TLMsg doContinueSession(Object fromWho, TLMsg msg) {
        String targetId = msg.getStringParam("sessionId", null);
        String userId = msg.getStringParam("userId", null);

        if (targetId == null || targetId.isEmpty()) {
            java.util.Map<String, Object> latest = findLatestMeta(userId);
            if (latest != null) targetId = (String) latest.get("sessionId");
            if (targetId == null)
                return createMsg().setParam(RESULT, false).setParam("error", "No session to continue");
        }

        TLMsg assembled = assembleSession(targetId, userId);
        if (assembled == null)
            return createMsg().setParam(RESULT, false).setParam("error", "Session not found: " + targetId);

        List<TLConversationHistory> history =
                (List<TLConversationHistory>) assembled.getParam("history");
        return createMsg()
                .setParam(RESULT, true)
                .setParam("sessionId", targetId)
                .setParam("count", history != null ? history.size() : 0)
                .setParam("history", history);
    }

    /** 删除会话（先校验存在性，避免删除不存在的会话也返回成功） */
    @SuppressWarnings("unchecked")
    private TLMsg doDeleteSession(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam("sessionId", "");
        String userId = msg.getStringParam("userId", null);
        if (sessionId.isEmpty()) return createMsg().setParam(RESULT, false).setParam("error", "sessionId 必填");
        // 存在性校验：在用户会话列表中查找（只允许删自己的会话）
        boolean exists = false;
        try {
            java.util.List<java.util.Map<String, Object>> sessions = listSessionsMeta(userId);
            if (sessions != null) {
                for (java.util.Map<String, Object> s : sessions) {
                    if (sessionId.equals(s.get("sessionId"))) { exists = true; break; }
                }
            }
        } catch (Exception ignored) {}
        if (!exists) return createMsg().setParam(RESULT, false).setParam("error", "会话不存在: " + sessionId);
        deleteSessionData(sessionId, userId);
        return createMsg().setParam(RESULT, true);
    }

    // ======================== 公共逻辑：轮次组装 ========================

    /**
     * 加载所有轮次，按 roundId 去重（同轮多次通知取最后一条），
     * 按 roundSeq 排序，拼接所有 messages 为完整 history。
     */
    @SuppressWarnings("unchecked")
    protected TLMsg assembleSession(String sessionId, String userId) {
        if (sessionId == null || sessionId.isEmpty()) return null;
        java.util.List<java.util.Map<String, Object>> rounds = loadRoundData(sessionId, userId);
        if (rounds == null || rounds.isEmpty()) return null;

        // roundId → 最后一条
        java.util.LinkedHashMap<String, java.util.Map<String, Object>> roundMap = new java.util.LinkedHashMap<>();
        java.util.Map<String, Object> lastRound = null;
        for (java.util.Map<String, Object> r : rounds) {
            String rid = (String) r.getOrDefault("roundId", "");
            roundMap.put(rid, r);
            lastRound = r;
        }
        if (lastRound == null) return null;

        // 按 roundSeq 排序
        java.util.List<java.util.Map<String, Object>> sorted = new java.util.ArrayList<>(roundMap.values());
        sorted.sort((a, b) -> {
            int sa = a.get("roundSeq") instanceof Integer ? (Integer) a.get("roundSeq") : 0;
            int sb = b.get("roundSeq") instanceof Integer ? (Integer) b.get("roundSeq") : 0;
            return Integer.compare(sa, sb);
        });

        // 拼接 messages
        List<TLConversationHistory> history = new ArrayList<>();
        for (java.util.Map<String, Object> r : sorted) {
            Object msgs = r.get("messages");
            if (msgs instanceof List) {
                for (Object m : (List<?>) msgs) {
                    if (m instanceof TLConversationHistory)
                        history.add((TLConversationHistory) m);
                }
            }
        }

        TLMsg result = new TLMsg();
        result.setParam(RESULT, true);
        result.setParam("sessionId", lastRound.getOrDefault("sessionId", ""));
        result.setParam("state", lastRound.getOrDefault("state", ""));
        result.setParam("agentName", lastRound.getOrDefault("agentName", ""));
        result.setParam("userMessage", lastRound.getOrDefault("userMessage", ""));
        result.setParam("model", lastRound.getOrDefault("model", ""));
        result.setParam("temperature", lastRound.getOrDefault("temperature", 0.7));
        result.setParam("maxTokens", lastRound.getOrDefault("maxTokens", 4096));
        result.setParam("round", lastRound.getOrDefault("roundSeq", 0));
        result.setParam("roundId", lastRound.getOrDefault("roundId", ""));
        result.setParam("userId", lastRound.getOrDefault("userId", ""));
        result.setParam("history", history);
        result.setParam("count", history.size());
        if (lastRound.containsKey("pendingToolCall"))
            result.setParam("pendingToolCall", lastRound.get("pendingToolCall"));
        if (lastRound.containsKey(AI_P_APPROVAL_ID))
            result.setParam(AI_P_APPROVAL_ID, lastRound.get(AI_P_APPROVAL_ID));

        return result;
    }
}
