package cn.tianlong.tlobject.aiagent.memory;

import cn.tianlong.tlobject.aiagent.TLBaseMemory;
import cn.tianlong.tlobject.aiagent.TLMemoryEntry;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据库持久化长期记忆模块。基于框架database模块（TLTable）实现。
 * 记忆存储在MySQL的ai_sessions和ai_memory两张表中。
 *
 * 创建日期：2026/7/5
 * 作者:tianlong
 */
public class TLDatabaseMemoryModule extends TLBaseMemory {

    private Map<String, TLMemoryEntry> cache;
    private int maxCacheEntries = 10000;
    private Gson gson;

    /** 缓存的table引用 */
    private TLBaseModule memTable;
    private TLBaseModule sessTable;

    public TLDatabaseMemoryModule() { super(); this.memoryType = "longTerm"; }
    public TLDatabaseMemoryModule(String name) { super(name); this.memoryType = "longTerm"; }
    public TLDatabaseMemoryModule(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory); this.memoryType = "longTerm";
    }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null && params.get("maxCacheEntries") != null) {
            try { maxCacheEntries = Integer.parseInt(params.get("maxCacheEntries")); }
            catch (NumberFormatException ignored) {}
        }
    }

    @Override
    protected TLBaseModule init() {
        cache = new ConcurrentHashMap<>();
        gson = new GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss").create();
        memTable = getDbTable("aiMemory");
        sessTable = getDbTable("aiSessions");
        return this;
    }

    // ======================== 5个抽象方法 ========================

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg store(Object fromWho, TLMsg msg) {
        TLMemoryEntry entry = (TLMemoryEntry) msg.getParam("entry", TLMemoryEntry.class);
        if (entry == null) {
            String key = msg.getStringParam(AI_P_MEMORYKEY, "");
            if (key.isEmpty()) key = "mem_" + UUID.randomUUID().toString().substring(0, 8);
            Object value = msg.getParam(AI_P_MEMORYVALUE);
            String sessionId = msg.getStringParam(AI_P_SESSIONID, "global");
            String tag = msg.getStringParam(AI_P_MEMORYTAG, null);
            int exptimeMinutes = msg.getIntParam(AI_P_MEMORYEXPTIME, -1);

            String scopedKey = sessionId + ":" + (tag != null ? tag + ":" : "") + key;
            entry = new TLMemoryEntry(scopedKey, value, memoryType);
            entry.setTag(tag);
            entry.setExpiresAt(calculateExpiresAt(exptimeMinutes));
            if (msg.containsParam("metadata")) {
                entry.setMetadata((Map<String, Object>) msg.getMapParam("metadata", new HashMap<>()));
            }
            ensureSession(sessionId, msg.getStringParam("userId", sessionId));
        }

        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("session_id", extractSessionId(entry.getKey()));
        sqlparams.put("mem_key", entry.getKey());
        sqlparams.put("mem_value", entry.getValue() != null ? entry.getValue().toString() : "");
        sqlparams.put("mem_type", entry.getType());
        sqlparams.put("tag", entry.getTag());
        sqlparams.put("created_at", entry.getCreatedAt());
        sqlparams.put("expires_at", entry.getExpiresAt());
        sqlparams.put("metadata", entry.getMetadata() != null ? gson.toJson(entry.getMetadata()) : null);

        sendToTable(memTable, createMsg().setAction(DB_INSERT).setParam(DB_P_PARAMS, sqlparams));

        cache.put(entry.getKey(), entry);
        ensureCacheCapacity();
        putLog("DB memory stored: " + entry.getKey(), LogLevel.DEBUG);
        return createMsg().setParam(RESULT, true).setParam("key", entry.getKey());
    }

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg retrieve(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "global");
        String tag = msg.getStringParam(AI_P_MEMORYTAG, null);
        String scopedKey = sessionId + ":" + (tag != null ? tag + ":" : "") + msg.getStringParam(AI_P_MEMORYKEY, "");

        TLMemoryEntry cached = cache.get(scopedKey);
        if (cached != null && !cached.isExpired()) {
            return createMsg().setParam(RESULT, true)
                    .setParam(AI_P_MEMORYVALUE, cached.getValue()).setParam(AI_P_MEMORYRESULT, cached.getValue())
                    .setParam("entry", cached);
        }

        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("mem_key", scopedKey);
        TLMsg result = sendToTable(memTable, createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, "select * from [table] where mem_key = ?")
                .setParam(DB_P_PARAMS, sqlparams));

        List<Map<String, Object>> rows = getResultList(result);
        if (rows == null || rows.isEmpty())
            return createMsg().setParam(RESULT, false).setParam(AI_P_MEMORYRESULT, null);

        TLMemoryEntry entry = rowToEntry(rows.get(0));
        if (entry.isExpired()) { delete(scopedKey); return createMsg().setParam(RESULT, false); }
        cache.put(entry.getKey(), entry);
        return createMsg().setParam(RESULT, true)
                .setParam(AI_P_MEMORYVALUE, entry.getValue()).setParam(AI_P_MEMORYRESULT, entry.getValue())
                .setParam("entry", entry);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg search(Object fromWho, TLMsg msg) {
        String query = msg.getStringParam(AI_P_MEMORYQUERY, "");
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "global");
        String tag = msg.getStringParam(AI_P_MEMORYTAG, null);
        int topK = msg.getIntParam(AI_P_TOPK, 5);

        StringBuilder sql = new StringBuilder("select * from [table] where 1=1");
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();

        if (!"global".equals(sessionId)) {
            sql.append(" and session_id = ?"); sqlparams.put("session_id", sessionId);
        }
        if (tag != null && !tag.isEmpty()) {
            sql.append(" and tag = ?"); sqlparams.put("tag", tag);
        }
        if (!query.isEmpty()) {
            sql.append(" and mem_value like ?"); sqlparams.put("mem_value", "%" + query + "%");
        }
        sql.append(" and (expires_at = 0 or expires_at > ?)");
        sqlparams.put("expires_at", System.currentTimeMillis());
        sql.append(" order by created_at desc limit ?");
        sqlparams.put("limit", topK);

        TLMsg result = sendToTable(memTable, createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, sql.toString()).setParam(DB_P_PARAMS, sqlparams));

        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.getListParam(DB_R_RESULT, new ArrayList<>());
        List<TLMemoryEntry> entries = new ArrayList<>();
        if (rows != null) for (Map<String, Object> row : rows) entries.add(rowToEntry(row));

        return createMsg().setParam(RESULT, true).setParam(AI_P_MEMORYRESULT, entries).setParam("count", entries.size());
    }

    @Override
    protected TLMsg delete(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "global");
        String tag = msg.getStringParam(AI_P_MEMORYTAG, null);
        String scopedKey = sessionId + ":" + (tag != null ? tag + ":" : "") + msg.getStringParam(AI_P_MEMORYKEY, "");
        delete(scopedKey);
        return createMsg().setParam(RESULT, true);
    }

    @Override
    protected TLMsg clearAll(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "");
        if (sessionId.isEmpty()) {
            sendToTable(memTable, createMsg().setAction(DB_DELETE)
                    .setParam(DB_P_SQL, "delete from [table]"));
            int size = cache.size(); cache.clear();
            putLog("All DB memory cleared: " + size + " entries", LogLevel.DEBUG);
            return createMsg().setParam(RESULT, true).setParam("cleared", size);
        } else {
            LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
            sqlparams.put("session_id", sessionId);
            sendToTable(memTable, createMsg().setAction(DB_DELETE)
                    .setParam(DB_P_SQL, "delete from [table] where session_id = ?")
                    .setParam(DB_P_PARAMS, sqlparams));
            cache.keySet().removeIf(k -> k.startsWith(sessionId + ":"));
            putLog("DB memory cleared for session: " + sessionId, LogLevel.DEBUG);
            return createMsg().setParam(RESULT, true).setParam("cleared", -1);
        }
    }

    // ======================== Session管理 ========================

    private void ensureSession(String sessionId, String userId) {
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("session_id", sessionId);
        TLMsg result = sendToTable(sessTable, createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, "select * from [table] where session_id = ?")
                .setParam(DB_P_PARAMS, sqlparams));

        List<Map<String, Object>> rows = getResultList(result);
        if (rows == null || rows.isEmpty()) {
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("session_id", sessionId);
            params.put("user_id", userId != null ? userId : sessionId);
            params.put("created_at", System.currentTimeMillis());
            params.put("last_active", System.currentTimeMillis());
            sendToTable(sessTable, createMsg().setAction(DB_INSERT).setParam(DB_P_PARAMS, params));
            putLog("Session created: " + sessionId, LogLevel.DEBUG);
        } else {
            LinkedHashMap<String, Object> updateParams = new LinkedHashMap<>();
            updateParams.put("last_active", System.currentTimeMillis());
            updateParams.put("session_id", sessionId);
            sendToTable(sessTable, createMsg().setAction(DB_UPDATE)
                    .setParam(DB_P_SQL, "update [table] set last_active = ? where session_id = ?")
                    .setParam(DB_P_PARAMS, updateParams));
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getSessions(String userId) {
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("user_id", userId);
        TLMsg result = sendToTable(sessTable, createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, "select * from [table] where user_id = ? order by last_active desc")
                .setParam(DB_P_PARAMS, sqlparams));
        return (List<Map<String, Object>>) result.getListParam(DB_R_RESULT, new ArrayList<>());
    }

    public Map<String, Object> getSessionInfo(String sessionId) {
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("session_id", sessionId);
        TLMsg result = sendToTable(sessTable, createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, "select * from [table] where session_id = ?")
                .setParam(DB_P_PARAMS, sqlparams));
        List<Map<String, Object>> rows = getResultList(result);
        return (rows != null && !rows.isEmpty()) ? rows.get(0) : null;
    }

    // ======================== 内部辅助 ========================

    private TLBaseModule getDbTable(String tableName) {
        TLMsg getMsg = createMsg().setAction(DB_GETTABLE).setParam(DB_P_TABLENAME, tableName);
        TLMsg result = putMsg(DEFAULTDATABASE, getMsg);
        if (result != null && result.containsParam(INSTANCE)) {
            return (TLBaseModule) result.getParam(INSTANCE, TLBaseModule.class);
        }
        putLog("Failed to get DB table: " + tableName, LogLevel.ERROR);
        return null;
    }

    private TLMsg sendToTable(TLBaseModule table, TLMsg msg) {
        if (table == null) return createMsg().setParam(RESULT, false);
        return putMsg(table, msg);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getResultList(TLMsg result) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) result.getListParam(DB_R_RESULT, null);
        if (list != null) return list;
        Map<String, Object> single = (Map<String, Object>) result.getParam(DB_R_RESULT, Map.class);
        if (single != null) { List<Map<String, Object>> w = new ArrayList<>(); w.add(single); return w; }
        return null;
    }

    private void delete(String scopedKey) {
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("mem_key", scopedKey);
        sendToTable(memTable, createMsg().setAction(DB_DELETE)
                .setParam(DB_P_SQL, "delete from [table] where mem_key = ?")
                .setParam(DB_P_PARAMS, sqlparams));
        cache.remove(scopedKey);
    }

    private TLMemoryEntry rowToEntry(Map<String, Object> row) {
        String key = (String) row.get("mem_key");
        TLMemoryEntry entry = new TLMemoryEntry(key, row.get("mem_value"), (String) row.get("mem_type"));
        entry.setTag((String) row.get("tag"));
        entry.setCreatedAt(toLong(row.get("created_at"), System.currentTimeMillis()));
        entry.setExpiresAt(toLong(row.get("expires_at"), 0L));
        String metaStr = (String) row.get("metadata");
        if (metaStr != null && !metaStr.isEmpty()) {
            try { entry.setMetadata(gson.fromJson(metaStr, Map.class)); } catch (Exception ignored) {}
        }
        return entry;
    }

    private String extractSessionId(String scopedKey) {
        int idx = scopedKey.indexOf(':');
        return idx > 0 ? scopedKey.substring(0, idx) : scopedKey;
    }

    private Long toLong(Object val, long defaultVal) {
        if (val == null) return defaultVal;
        if (val instanceof Number) return ((Number) val).longValue();
        try { return Long.parseLong(val.toString()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private void ensureCacheCapacity() {
        if (cache.size() <= maxCacheEntries) return;
        cache.values().stream().min(Comparator.comparingLong(TLMemoryEntry::getCreatedAt))
                .ifPresent(oldest -> cache.remove(oldest.getKey()));
    }
}
