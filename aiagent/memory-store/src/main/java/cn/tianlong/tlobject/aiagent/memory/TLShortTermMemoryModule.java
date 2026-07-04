package cn.tianlong.tlobject.aiagent.memory;

import cn.tianlong.tlobject.aiagent.TLBaseMemory;
import cn.tianlong.tlobject.aiagent.TLMemoryEntry;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 短期记忆模块。基于ConcurrentHashMap的内存存储，支持TTL过期。
 * 适用于会话级别的临时记忆，进程重启后丢失。
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public class TLShortTermMemoryModule extends TLBaseMemory {

    /** 记忆存储：key -> TLMemoryEntry */
    protected Map<String, TLMemoryEntry> store;

    /** 最大条目数 */
    protected int maxEntries = 1000;

    /** 上次清理时间 */
    protected long lastCleanupTime = System.currentTimeMillis();

    /** 清理间隔（毫秒） */
    protected long cleanupInterval = 60000; // 1分钟

    public TLShortTermMemoryModule() {
        super();
        this.memoryType = "shortTerm";
    }

    public TLShortTermMemoryModule(String name) {
        super(name);
        this.memoryType = "shortTerm";
    }

    public TLShortTermMemoryModule(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
        this.memoryType = "shortTerm";
    }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null) {
            if (params.get("maxEntries") != null) {
                try { maxEntries = Integer.parseInt(params.get("maxEntries")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("cleanupInterval") != null) {
                try { cleanupInterval = Long.parseLong(params.get("cleanupInterval")) * 1000; }
                catch (NumberFormatException ignored) {}
            }
        }
    }

    @Override
    protected TLBaseModule init() {
        store = new ConcurrentHashMap<>();
        return this;
    }

    // ======================== 动作实现 ========================

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg store(Object fromWho, TLMsg msg) {
        ensureCapacity();
        cleanupExpired();

        // 支持从msg直接构建或传入entry
        TLMemoryEntry entry = (TLMemoryEntry) msg.getParam("entry", TLMemoryEntry.class);
        if (entry == null) {
            String key = msg.getStringParam(AI_P_MEMORYKEY, "");
            if (key.isEmpty()) {
                // 自动生成key
                key = "mem_" + UUID.randomUUID().toString().substring(0, 8);
            }
            Object value = msg.getParam(AI_P_MEMORYVALUE);
            String type = msg.getStringParam(AI_P_MEMORYTYPE, memoryType);
            String tag = msg.getStringParam(AI_P_MEMORYTAG, null);
            int exptimeMinutes = msg.getIntParam(AI_P_MEMORYEXPTIME, -1);

            entry = new TLMemoryEntry(key, value, type);
            entry.setTag(tag);
            entry.setExpiresAt(calculateExpiresAt(exptimeMinutes));

            if (msg.containsParam("metadata")) {
                entry.setMetadata((Map<String, Object>) msg.getMapParam("metadata", new HashMap<>()));
            }
        }

        // 构建session-scoped key
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "global");
        String scopedKey = sessionId + ":" + entry.getKey();
        entry.setKey(scopedKey);

        store.put(scopedKey, entry);
        putLog("Memory stored: " + scopedKey + " (tag=" + entry.getTag() + ")", LogLevel.DEBUG);
        return createMsg().setParam(RESULT, true).setParam("key", scopedKey);
    }

    @Override
    protected TLMsg retrieve(Object fromWho, TLMsg msg) {
        cleanupExpired();

        String key = msg.getStringParam(AI_P_MEMORYKEY, "");
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "global");
        String scopedKey = sessionId + ":" + key;

        TLMemoryEntry entry = store.get(scopedKey);
        if (entry == null || entry.isExpired()) {
            if (entry != null) store.remove(scopedKey);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_MEMORYRESULT, null);
        }

        return createMsg().setParam(RESULT, true)
                .setParam(AI_P_MEMORYVALUE, entry.getValue())
                .setParam(AI_P_MEMORYRESULT, entry.getValue())
                .setParam("entry", entry);
    }

    @Override
    protected TLMsg search(Object fromWho, TLMsg msg) {
        cleanupExpired();

        String query = msg.getStringParam(AI_P_MEMORYQUERY, "");
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "global");
        String tag = msg.getStringParam(AI_P_MEMORYTAG, null);
        int topK = msg.getIntParam(AI_P_TOPK, 5);

        List<TLMemoryEntry> results = store.values().stream()
                .filter(e -> !e.isExpired())
                .filter(e -> {
                    // session过滤
                    if (!"global".equals(sessionId) && !e.getKey().startsWith(sessionId + ":")) {
                        return false;
                    }
                    // tag过滤
                    if (tag != null && !tag.equals(e.getTag())) {
                        return false;
                    }
                    // 简单文本匹配（key或value包含查询词）
                    if (!query.isEmpty()) {
                        String keyLower = e.getKey().toLowerCase();
                        String valueStr = (e.getValue() != null) ? e.getValue().toString().toLowerCase() : "";
                        String queryLower = query.toLowerCase();
                        return keyLower.contains(queryLower) || valueStr.contains(queryLower);
                    }
                    return true;
                })
                .sorted(Comparator.comparingLong(TLMemoryEntry::getCreatedAt).reversed())
                .limit(topK)
                .collect(Collectors.toList());

        return createMsg().setParam(RESULT, true)
                .setParam(AI_P_MEMORYRESULT, results)
                .setParam("count", results.size());
    }

    @Override
    protected TLMsg delete(Object fromWho, TLMsg msg) {
        String key = msg.getStringParam(AI_P_MEMORYKEY, "");
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "global");
        String scopedKey = sessionId + ":" + key;

        TLMemoryEntry removed = store.remove(scopedKey);
        return createMsg().setParam(RESULT, removed != null)
                .setParam("removed", removed != null);
    }

    @Override
    protected TLMsg clearAll(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "");
        if (sessionId.isEmpty()) {
            int size = store.size();
            store.clear();
            putLog("All short-term memory cleared: " + size + " entries", LogLevel.DEBUG);
            return createMsg().setParam(RESULT, true).setParam("cleared", size);
        } else {
            // 仅清除指定session的记忆
            List<String> toRemove = store.keySet().stream()
                    .filter(k -> k.startsWith(sessionId + ":"))
                    .collect(Collectors.toList());
            toRemove.forEach(store::remove);
            putLog("Memory cleared for session: " + sessionId + " (" + toRemove.size() + " entries)", LogLevel.DEBUG);
            return createMsg().setParam(RESULT, true).setParam("cleared", toRemove.size());
        }
    }

    // ======================== 内部方法 ========================

    /**
     * 清理过期条目
     */
    protected void cleanupExpired() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupTime < cleanupInterval) return;
        lastCleanupTime = now;

        List<String> expiredKeys = store.entrySet().stream()
                .filter(e -> e.getValue().isExpired())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (!expiredKeys.isEmpty()) {
            expiredKeys.forEach(store::remove);
            putLog("Cleaned up " + expiredKeys.size() + " expired memory entries", LogLevel.DEBUG);
        }
    }

    /**
     * 确保容量不超限（LRU淘汰）
     */
    protected void ensureCapacity() {
        if (store.size() < maxEntries) return;

        // 找到最旧的条目并移除
        store.values().stream()
                .min(Comparator.comparingLong(TLMemoryEntry::getCreatedAt))
                .ifPresent(oldest -> {
                    store.remove(oldest.getKey());
                    putLog("Memory capacity limit reached, evicted: " + oldest.getKey(), LogLevel.DEBUG);
                });
    }
}
