package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLDatabase;
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

    /** title 列迁移是否已完成（幂等探测；失败保持 false，下次写路径重试） */
    private volatile boolean titleColumnReady = false;

    public TLDatabaseSessionManager() { super(); }
    public TLDatabaseSessionManager(String name) { super(name); }
    public TLDatabaseSessionManager(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected TLBaseModule init() {
        roundsTable = TLDatabase.getTable("aiSessionRounds", this);
        sessTable   = TLDatabase.getTable("aiSessions", this);
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
        if (roundsTable == null) roundsTable = TLDatabase.getTable("aiSessionRounds", this);
        ensureSessTable();   // 惰性获取 + title 列幂等迁移（老库不会自动加列）
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
                checkStoreResult(putMsg(roundsTable, createMsg().setAction(DB_DELETE)
                        .setParam(DB_P_SQL, "delete from [table] where session_id=? and round_id=?")
                        .setParam(DB_P_PARAMS, delP)), "delete round");
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
            checkStoreResult(putMsg(roundsTable,
                    createMsg().setAction(DB_INSERT).setParam(DB_P_PARAMS, params)), "insert round");

            // 2) 会话汇总：UPDATE 已有 → 无命中则 INSERT（保留 created_at）
            if (sessTable != null) {
                LinkedHashMap<String, Object> upParams = new LinkedHashMap<>();
                upParams.put("state", state);
                upParams.put("agent_name", roundData.getOrDefault("agentName", ""));
                upParams.put("user_message", roundData.getOrDefault("userMessage", ""));
                upParams.put("msg_count", msgCount);
                upParams.put("last_active", System.currentTimeMillis());
                upParams.put("__sid", sessionId);
                checkStoreResult(putMsg(sessTable, createMsg().setAction(DB_UPDATE)
                        .setParam(DB_P_SQL, "update [table] set state=?,agent_name=?,"
                                + "user_message=?,msg_count=?,last_active=? where session_id=?")
                        .setParam(DB_P_PARAMS, upParams)), "update session");

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
                checkStoreResult(putMsg(sessTable, createMsg()
                        .setAction(DB_INSERT)   // 必须带 action，否则 TLTable 无法分发（历史 bug：新会话行从未由 storeRound 创建）
                        .setParam(DB_P_SQL, "insert or ignore into [table] "
                                + "(session_id,user_id,agent_name,state,user_message,msg_count,last_active,created_at) "
                                + "values (?,?,?,?,?,?,?,?)")
                        .setParam(DB_P_PARAMS, sessParams)), "insert session");
            }
        } catch (Exception e) {
            putLog("storeRound DB failed: " + e.toString(), LogLevel.WARN);
        }
    }

    /** storeRound 是 best-effort：DB 错误通常以 RESULT=false 返回而不抛异常，必须显式检查 */
    private void checkStoreResult(TLMsg r, String op) {
        if (r == null || !r.parseBoolean(RESULT, false)) {
            putLog("storeRound DB " + op + " failed: "
                    + (r == null ? "null" : r.getStringParam("error", "")), LogLevel.WARN);
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

    // ======================== findIncompleteMeta（轮次明细开态待批优先 + 会话汇总兜底） ========================

    /**
     * 查找未完成断点。两类（都按 sessionId 可选限定）：
     *  a) 开态待批轮（黏住语义）：轮次明细表直接查 state=pending_approval——
     *     pending 是开态，直到被 resume 收尾；会话汇总表只有一行 state/last_active，
     *     其后任何新轮次写入（后台任务注入/其他写入）都会把它覆盖成 completed → 断点被藏起来（假成功一族）
     *  b) 会话汇总表 state in (checkpoint, pending_approval) 兜底（意外中断断点）
     */
    @Override
    @SuppressWarnings("unchecked")
    protected java.util.Map<String, Object> findIncompleteMeta(String userId, String sessionId) {
        try {
            // 1) 开态待批轮优先（轮次明细表直接查 state）——其后新轮次写入不得隐藏断点
            if (roundsTable == null) roundsTable = TLDatabase.getTable("aiSessionRounds", this);
            if (roundsTable != null) {
                LinkedHashMap<String, Object> p = new LinkedHashMap<>();
                p.put("user_id", userId != null ? userId : "");
                p.put("state", SESSION_STATE_PENDING_APPROVAL);
                String sql = "select * from [table] where user_id=? and state=?";
                if (sessionId != null && !sessionId.isEmpty()) {
                    sql += " and session_id=?";
                    p.put("session_id", sessionId);
                }
                sql += " order by created_at desc limit 1";
                TLMsg pr = putMsg(roundsTable, createMsg().setAction(DB_QUERY)
                        .setParam(DB_P_SQL, sql)
                        .setParam(DB_P_PARAMS, p));
                java.util.List<java.util.Map<String, Object>> prows = getResultList(pr);
                if (prows != null && !prows.isEmpty()) {
                    java.util.Map<String, Object> row = prows.get(0);
                    normalizeKeys(row);
                    java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
                    result.put("sessionId", String.valueOf(row.getOrDefault("sessionId", "")));
                    result.put("state", SESSION_STATE_PENDING_APPROVAL);
                    result.put("agentName", String.valueOf(row.getOrDefault("agentName", "")));
                    result.put("userMessage", String.valueOf(row.getOrDefault("userMessage", "")));
                    result.put("roundSeq", row.getOrDefault("roundSeq", 0));
                    result.put("savedAt", System.currentTimeMillis());
                    Object aid = row.get(AI_P_APPROVAL_ID);
                    if (aid != null && !String.valueOf(aid).isEmpty()) result.put(AI_P_APPROVAL_ID, String.valueOf(aid));
                    return result;
                }
            }

            // 2) 兜底：会话汇总表（checkpoint / pending_approval）
            if (ensureSessTable() == null) return null;
            LinkedHashMap<String, Object> p = new LinkedHashMap<>();
            p.put("user_id", userId != null ? userId : "");
            p.put("s_checkpoint", SESSION_STATE_CHECKPOINT);
            p.put("s_pending", SESSION_STATE_PENDING_APPROVAL);
            String sql = "select * from [table] where user_id=? and state in (?,?)";
            if (sessionId != null && !sessionId.isEmpty()) {
                sql += " and session_id=?";
                p.put("session_id", sessionId);
            }
            sql += " order by last_active desc limit 1";
            TLMsg result = putMsg(sessTable, createMsg().setAction(DB_QUERY)
                    .setParam(DB_P_SQL, sql)
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
        if (ensureSessTable() == null) return null;
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
        if (ensureSessTable() == null) return sessions;
        try {
            String uid = userId != null ? userId : "";
            LinkedHashMap<String, Object> p = new LinkedHashMap<>();
            p.put("user_id", uid);
            // 排除内部会话：task 前缀 = 定时任务的执行会话（每次都新建、last_active 一直在最前，
            // 不过滤会把用户会话列表刷满，且 autoResumeLast 会"接续"到任务会话上）。
            // 直查后按前缀在 Java 侧过滤——LIKE 下划线转义在 SQLite/MySQL 语义有差异，不冒这个险
            TLMsg result = putMsg(sessTable, createMsg().setAction(DB_QUERY)
                    .setParam(DB_P_SQL, "select * from [table] where user_id=? order by last_active desc")
                    .setParam(DB_P_PARAMS, p));
            java.util.List<java.util.Map<String, Object>> rows = getResultList(result);
            if (rows == null) return sessions;
            for (java.util.Map<String, Object> row : rows) {
                Object sid = row.get("session_id");
                if (sid != null && String.valueOf(sid).startsWith("task_")) continue;   // 内部任务执行会话
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

    // ======================== setSessionTitle ========================

    /**
     * 写 ai_sessions.title。
     *
     * updated 用"先查再写"判定，而不是 UPDATE 的影响行数——TLTable 的 map 参数更新走
     * insertData（commons-dbutils runner.update 的返回值被丢弃），DB_R_RESULT 恒为 1，
     * 拿不到真实行数；反过来 UPDATE 的 WHERE 里仍保留 onlyIfEmpty/user_id 守卫做并发兜底。
     * 返回 false 的三种情况：会话不存在（或不属于该 userId）、已有标题且 onlyIfEmpty、写 SQL 报错。
     */
    @Override
    protected boolean setSessionTitle(String sessionId, String userId, String title, boolean onlyIfEmpty) {
        if (sessionId == null || sessionId.isEmpty()) return false;
        if (ensureSessTable() == null) return false;
        try {
            // 用户隔离：带了 userId 才加条件（老数据 user_id 可能为空串，无谓的 scoping 会拒掉合法改名）
            boolean scopeUser = userId != null && !userId.isEmpty();

            StringBuilder q = new StringBuilder("select title from [table] where session_id=?");
            LinkedHashMap<String, Object> qp = new LinkedHashMap<>();
            qp.put("session_id", sessionId);
            if (scopeUser) { q.append(" and user_id=?"); qp.put("user_id", userId); }
            TLMsg qr = putMsg(sessTable, createMsg().setAction(DB_QUERY)
                    .setParam(DB_P_SQL, q.toString())
                    .setParam(DB_P_PARAMS, qp)
                    .setParam(DB_P_RESULTTYPE, TLDatabase.RESULT_TYPE.MAPLIST));
            java.util.List<java.util.Map<String, Object>> rows = getResultList(qr);
            if (rows == null || rows.isEmpty()) return false;   // 会话不存在/查询失败
            if (onlyIfEmpty) {
                Object cur = rows.get(0).get("title");
                if (cur != null && !String.valueOf(cur).isEmpty()) return false;   // 已有标题
            }

            StringBuilder sql = new StringBuilder("update [table] set title=? where session_id=?");
            LinkedHashMap<String, Object> p = new LinkedHashMap<>();
            p.put("title", title != null ? title : "");
            p.put("session_id", sessionId);
            if (onlyIfEmpty) sql.append(" and (title is null or title='')");
            if (scopeUser) { sql.append(" and user_id=?"); p.put("user_id", userId); }
            TLMsg r = putMsg(sessTable, createMsg().setAction(DB_UPDATE)
                    .setParam(DB_P_SQL, sql.toString())
                    .setParam(DB_P_PARAMS, p));
            return r != null && r.parseBoolean(RESULT, false);
        } catch (Exception e) {
            putLog("setSessionTitle DB failed: " + e, LogLevel.WARN);
            return false;
        }
    }

    // ======================== 惰性初始化 + title 列迁移 ========================

    /** 惰性获取 ai_sessions 表引用（失败下次重试），并保证 title 列已迁移 */
    private synchronized TLBaseModule ensureSessTable() {
        if (sessTable == null) sessTable = TLDatabase.getTable("aiSessions", this);
        if (sessTable != null && !titleColumnReady) ensureTitleColumn();
        return sessTable;
    }

    /**
     * 幂等迁移：老库的 ai_sessions 由旧建表 SQL 创建，没有 title 列。
     * 探测（SQLite: pragma table_info；MySQL 不支持 pragma → 回退 select 探测）→ 缺列则 ALTER。
     * 失败打 WARN 不致命，titleColumnReady 保持 false 下次重试。
     * 注意：查询出错时 TLTable 返回 RESULT=false 且不带 DB_R_RESULT，用后者是否存在判定"查询成功"。
     */
    private void ensureTitleColumn() {
        try {
            boolean has = false;
            TLMsg probe = putMsg(sessTable, createMsg().setAction(DB_QUERY)
                    .setParam(DB_P_SQL, "pragma table_info([table])")
                    .setParam(DB_P_RESULTTYPE, TLDatabase.RESULT_TYPE.MAPLIST));
            java.util.List<java.util.Map<String, Object>> cols = getResultList(probe);
            if (cols == null) {
                // 非 SQLite：pragma 语法错误 → 用 select 试列（能执行=列已存在，报错=缺列）
                TLMsg p2 = putMsg(sessTable, createMsg().setAction(DB_QUERY)
                        .setParam(DB_P_SQL, "select title from [table] limit 1")
                        .setParam(DB_P_RESULTTYPE, TLDatabase.RESULT_TYPE.MAPLIST));
                has = getResultList(p2) != null;
            } else {
                for (java.util.Map<String, Object> c : cols) {
                    Object n = c.get("name");
                    if (n != null && "title".equalsIgnoreCase(String.valueOf(n))) { has = true; break; }
                }
            }
            if (!has) {
                TLMsg r = putMsg(sessTable, createMsg().setAction(DB_UPDATE)
                        .setParam(DB_P_SQL, "alter table [table] add column title varchar(255) default ''"));
                if (r == null || !r.parseBoolean(RESULT, false)) {
                    putLog("ai_sessions 迁移失败：新增 title 列未成功（下次重试）", LogLevel.WARN);
                    return;
                }
                putLog("ai_sessions 迁移：新增 title 列", LogLevel.INFO);
            }
            titleColumnReady = true;
        } catch (Exception e) {
            putLog("ai_sessions title 列迁移异常（下次重试）: " + e, LogLevel.WARN);
        }
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
        // approvalId 别名：基类 doFindIncomplete/discardPendingRound 按 AI_P_APPROVAL_ID（camelCase）取用
        if (row.containsKey("approval_id")) row.put(AI_P_APPROVAL_ID, row.get("approval_id"));
        // maxTokens 别名：discard 重存按 load 出的行回写，无别名时 storeRound 落回 4096 默认（DB resume/trace 读不到真值）
        if (row.containsKey("max_tokens")) row.put("maxTokens", objToInt(row.get("max_tokens")));
    }

    private java.util.Map<String, Object> metaFromSessRow(java.util.Map<String, Object> row) {
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("sessionId", row.getOrDefault("session_id", ""));
        meta.put("state", row.getOrDefault("state", ""));
        meta.put("agentName", row.getOrDefault("agent_name", ""));
        meta.put("userMessage", row.getOrDefault("user_message", ""));
        meta.put("title", strOf(row.get("title")));   // 老库/未迁移时列缺失 → 空串（契约：无标题=空串）
        meta.put("savedAt", objToLong(row.getOrDefault("last_active", 0L)));
        meta.put("count", objToInt(row.getOrDefault("msg_count", 0)));
        return meta;
    }

    /** null → ""（row 里键存在值为 null 时 getOrDefault 也返回 null，不能直接用 getOrDefault） */
    private String strOf(Object v) {
        return v == null ? "" : String.valueOf(v);
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
