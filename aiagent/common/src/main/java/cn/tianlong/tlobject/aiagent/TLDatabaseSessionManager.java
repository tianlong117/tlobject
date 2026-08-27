package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLDataBase;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.*;

/**
 * 数据库版会话管理器——基于框架 database 模块实现。
 *
 * 双表：
 *   ai_sessions       — 会话汇总（列表、断点检测）
 *   ai_session_rounds — 轮次明细（加载完整历史）
 *
 * @author tianlong
 * @date 2026/8/2
 */
public class TLDatabaseSessionManager extends TLBaseSessionManager {

    private TLBaseModule roundsTable;  // ai_session_rounds
    private TLBaseModule sessTable;    // ai_sessions

    public TLDatabaseSessionManager() { super(); }
    public TLDatabaseSessionManager(String name) { super(name); }
    public TLDatabaseSessionManager(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected TLBaseModule init() {
        roundsTable = TLDataBase.getTable("aiSessionRounds", this);
        sessTable   = TLDataBase.getTable("aiSessions", this);
        return this;
    }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null && params.get("enableCheckpoint") != null)
            enableCheckpoint = Boolean.parseBoolean(params.get("enableCheckpoint"));
    }

    // ======================== storeRound ========================

    @Override
    @SuppressWarnings("unchecked")
    protected void storeRound(Map<String, Object> roundData) {
        // 惰性重试：init() 时数据库可能未就绪（多实例并发写 SQLite 时表模块初始化可能失败），
        // 每次保存前重试获取，就绪后自然恢复（否则会话汇总会静默丢失）
        if (roundsTable == null) roundsTable = TLDataBase.getTable("aiSessionRounds", this);
        if (sessTable == null) sessTable = TLDataBase.getTable("aiSessions", this);
        if (!enableCheckpoint || roundsTable == null) return;
        try {
            String sessionId = (String) roundData.getOrDefault("sessionId", "");
            String roundId   = (String) roundData.getOrDefault("roundId", "");
            String messagesJson = gson.toJson(roundData.get("messages"));
            String pendingTcJson = roundData.containsKey("pendingToolCall")
                    ? gson.toJson(roundData.get("pendingToolCall")) : null;
            String state = (String) roundData.getOrDefault("state", "");
            int msgCount = roundData.get("messages") instanceof List
                    ? ((List<?>) roundData.get("messages")).size() : 0;

            // 1) 轮次明细：同 roundId 先删再插
            if (roundId != null && !roundId.isEmpty()) {
                LinkedHashMap<String, Object> delP = new LinkedHashMap<>();
                delP.put("session_id", sessionId);
                delP.put("round_id", roundId);
                putMsg(roundsTable, createMsg().setAction(DB_DELETE)
                        .setParam(DB_P_SQL, "delete from [table] where session_id=? and round_id=?")
                        .setParam(DB_P_PARAMS, delP));
            }

            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("session_id", sessionId);
            params.put("user_id", roundData.getOrDefault("userId", ""));
            params.put("agent_name", roundData.getOrDefault("agentName", ""));
            params.put("round_id", roundId);
            params.put("round_seq", roundData.getOrDefault("roundSeq", 1));
            params.put("state", state);
            params.put("user_message", roundData.getOrDefault("userMessage", ""));
            params.put("model", roundData.getOrDefault("model", ""));
            params.put("temperature", roundData.getOrDefault("temperature", 0.7));
            params.put("max_tokens", roundData.getOrDefault("maxTokens", 4096));
            params.put("messages", messagesJson);
            params.put("pending_tool_call", pendingTcJson);
            params.put("approval_id", roundData.getOrDefault(AI_P_APPROVAL_ID, null));
            params.put("created_at", System.currentTimeMillis());
            putMsg(roundsTable, createMsg().setAction(DB_INSERT).setParam(DB_P_PARAMS, params));

            // 2) 会话汇总：UPDATE 已有 → 无命中则 INSERT（保留 created_at）
            if (sessTable != null) {
                LinkedHashMap<String, Object> upParams = new LinkedHashMap<>();
                upParams.put("state", state);
                upParams.put("agent_name", roundData.getOrDefault("agentName", ""));
                upParams.put("user_message", roundData.getOrDefault("userMessage", ""));
                upParams.put("msg_count", msgCount);
                upParams.put("last_active", System.currentTimeMillis());
                upParams.put("__sid", sessionId);
                putMsg(sessTable, createMsg().setAction(DB_UPDATE)
                        .setParam(DB_P_SQL, "update [table] set state=?,agent_name=?,"
                                + "user_message=?,msg_count=?,last_active=? where session_id=?")
                        .setParam(DB_P_PARAMS, upParams));

                // 新会话：INSERT OR IGNORE（已存在则 UPDATE 已完成，INSERT 静默跳过）
                LinkedHashMap<String, Object> sessParams = new LinkedHashMap<>();
                sessParams.put("session_id", sessionId);
                sessParams.put("user_id", roundData.getOrDefault("userId", ""));
                sessParams.put("agent_name", roundData.getOrDefault("agentName", ""));
                sessParams.put("state", state);
                sessParams.put("user_message", roundData.getOrDefault("userMessage", ""));
                sessParams.put("msg_count", msgCount);
                sessParams.put("last_active", System.currentTimeMillis());
                sessParams.put("created_at", System.currentTimeMillis());
                putMsg(sessTable, createMsg()
                        .setAction(DB_INSERT)   // 必须带 action，否则 TLTable 无法分发（历史 bug：新会话行从未由 storeRound 创建）
                        .setParam(DB_P_SQL, "insert or ignore into [table] "
                                + "(session_id,user_id,agent_name,state,user_message,msg_count,last_active,created_at) "
                                + "values (?,?,?,?,?,?,?,?)")
                        .setParam(DB_P_PARAMS, sessParams));
            }
        } catch (Exception e) {
            putLog("storeRound DB failed: " + e.toString(), LogLevel.WARN);
        }
    }

    // ======================== loadRoundData ========================

    @Override
    @SuppressWarnings("unchecked")
    protected java.util.List<java.util.Map<String, Object>> loadRoundData(String sessionId, String userId) {
        java.util.List<java.util.Map<String, Object>> rounds = new java.util.ArrayList<>();
        if (roundsTable == null) return rounds;
        try {
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("session_id", sessionId);
            TLMsg result = putMsg(roundsTable, createMsg().setAction(DB_QUERY)
                    .setParam(DB_P_SQL, "select * from [table] where session_id=? order by round_seq")
                    .setParam(DB_P_PARAMS, params));
            java.util.List<java.util.Map<String, Object>> rows = getResultList(result);
            if (rows == null) return rounds;

            for (java.util.Map<String, Object> row : rows) {
                Object msgJson = row.get("messages");
                if (msgJson instanceof String) {
                    try {
                        TLConversationHistory[] msgs = gson.fromJson((String) msgJson, TLConversationHistory[].class);
                        row.put("messages", new ArrayList<>(Arrays.asList(msgs)));
                    } catch (Exception e) { row.put("messages", new ArrayList<>()); }
                }
                Object ptc = row.get("pending_tool_call");
                if (ptc instanceof String && !((String) ptc).isEmpty()) {
                    try { row.put("pendingToolCall", gson.fromJson((String) ptc, TLToolCall.class)); }
                    catch (Exception ignored) {}
                }
                normalizeKeys(row);
                rounds.add(row);
            }
        } catch (Exception e) {
            putLog("loadRoundData DB failed: " + e.toString(), LogLevel.WARN);
        }
        return rounds;
    }

    // ======================== findIncompleteMeta（查 ai_sessions，按 checkpoint 过滤） ========================

    @Override
    @SuppressWarnings("unchecked")
    protected java.util.Map<String, Object> findIncompleteMeta(String userId) {
        if (sessTable == null) return null;
        try {
            LinkedHashMap<String, Object> p = new LinkedHashMap<>();
            p.put("user_id", userId != null ? userId : "");
            p.put("state", SESSION_STATE_CHECKPOINT);
            TLMsg result = putMsg(sessTable, createMsg().setAction(DB_QUERY)
                    .setParam(DB_P_SQL, "select * from [table] where user_id=? and state=? order by last_active desc limit 1")
                    .setParam(DB_P_PARAMS, p));
            java.util.List<java.util.Map<String, Object>> rows = getResultList(result);
            if (rows == null || rows.isEmpty()) return null;
            return metaFromSessRow(rows.get(0));
        } catch (Exception e) { return null; }
    }

    // ======================== findLatestMeta（查 ai_sessions） ========================

    @Override
    @SuppressWarnings("unchecked")
    protected java.util.Map<String, Object> findLatestMeta(String userId) {
        if (sessTable == null) return null;
        try {
            LinkedHashMap<String, Object> p = new LinkedHashMap<>();
            p.put("user_id", userId != null ? userId : "");
            TLMsg result = putMsg(sessTable, createMsg().setAction(DB_QUERY)
                    .setParam(DB_P_SQL, "select * from [table] where user_id=? order by last_active desc limit 1")
                    .setParam(DB_P_PARAMS, p));
            java.util.List<java.util.Map<String, Object>> rows = getResultList(result);
            if (rows == null || rows.isEmpty()) return null;
            return metaFromSessRow(rows.get(0));
        } catch (Exception e) { return null; }
    }

    // ======================== listSessionsMeta（查 ai_sessions） ========================

    @Override
    @SuppressWarnings("unchecked")
    protected java.util.List<java.util.Map<String, Object>> listSessionsMeta(String userId) {
        java.util.List<java.util.Map<String, Object>> sessions = new java.util.ArrayList<>();
        if (sessTable == null) return sessions;
        try {
            String uid = userId != null ? userId : "";
            LinkedHashMap<String, Object> p = new LinkedHashMap<>();
            p.put("user_id", uid);
            TLMsg result = putMsg(sessTable, createMsg().setAction(DB_QUERY)
                    .setParam(DB_P_SQL, "select * from [table] where user_id=? order by last_active desc")
                    .setParam(DB_P_PARAMS, p));
            java.util.List<java.util.Map<String, Object>> rows = getResultList(result);
            if (rows == null) return sessions;
            for (java.util.Map<String, Object> row : rows) {
                sessions.add(metaFromSessRow(row));
            }
        } catch (Exception ignored) {}
        return sessions;
    }

    // ======================== deleteSessionData ========================

    @Override
    protected void deleteSessionData(String sessionId, String userId) {
        LinkedHashMap<String, Object> p = new LinkedHashMap<>();
        p.put("session_id", sessionId);
        if (roundsTable != null)
            putMsg(roundsTable, createMsg().setAction(DB_DELETE)
                    .setParam(DB_P_SQL, "delete from [table] where session_id=?")
                    .setParam(DB_P_PARAMS, new LinkedHashMap<>(p)));
        if (sessTable != null)
            putMsg(sessTable, createMsg().setAction(DB_DELETE)
                    .setParam(DB_P_SQL, "delete from [table] where session_id=?")
                    .setParam(DB_P_PARAMS, p));
    }

    // ======================== 辅助方法 ========================

    @SuppressWarnings("unchecked")
    private java.util.List<java.util.Map<String, Object>> getResultList(TLMsg result) {
        if (result == null) return null;
        java.util.List<java.util.Map<String, Object>> list =
                (java.util.List<java.util.Map<String, Object>>) result.getListParam(DB_R_RESULT, null);
        if (list != null) return list;
        java.util.Map<String, Object> single = (java.util.Map<String, Object>) result.getParam(DB_R_RESULT, Map.class);
        if (single != null) { java.util.List<java.util.Map<String, Object>> w = new java.util.ArrayList<>(); w.add(single); return w; }
        return null;
    }

    private void normalizeKeys(java.util.Map<String, Object> row) {
        if (row.containsKey("session_id")) row.put("sessionId", row.get("session_id"));
        if (row.containsKey("user_id")) row.put("userId", row.get("user_id"));
        if (row.containsKey("agent_name")) row.put("agentName", row.get("agent_name"));
        if (row.containsKey("round_id")) row.put("roundId", row.get("round_id"));
        if (row.containsKey("round_seq")) row.put("roundSeq", objToInt(row.get("round_seq")));
        if (row.containsKey("user_message")) row.put("userMessage", row.get("user_message"));
    }

    private java.util.Map<String, Object> metaFromSessRow(java.util.Map<String, Object> row) {
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("sessionId", row.getOrDefault("session_id", ""));
        meta.put("state", row.getOrDefault("state", ""));
        meta.put("agentName", row.getOrDefault("agent_name", ""));
        meta.put("userMessage", row.getOrDefault("user_message", ""));
        meta.put("savedAt", objToLong(row.getOrDefault("last_active", 0L)));
        meta.put("count", objToInt(row.getOrDefault("msg_count", 0)));
        return meta;
    }

    private int objToInt(Object v) {
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Long) return ((Long) v).intValue();
        if (v instanceof Number) return ((Number) v).intValue();
        return 0;
    }

    private long objToLong(Object v) {
        if (v instanceof Long) return (Long) v;
        if (v instanceof Integer) return ((Integer) v).longValue();
        if (v instanceof Number) return ((Number) v).longValue();
        return 0L;
    }
}
