package cn.tianlong.tlobject.aiagent.memory;

import cn.tianlong.tlobject.aiagent.TLBaseMemory;
import cn.tianlong.tlobject.aiagent.TLMemoryEntry;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 长期记忆模块。基于文件持久化的JSON存储。
 * 适用于跨会话的持久化记忆，进程重启后保留。
 * 使用异步写入减少IO开销。
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public class TLLongTermMemoryModule extends TLBaseMemory {

    /** 存储目录路径 */
    private String storagePath = "./data/longterm_memory";

    /** 内存缓存（减少磁盘读取） */
    private Map<String, TLMemoryEntry> cache;

    /** 最大缓存条目数 */
    private int maxCacheEntries = 10000;

    /** JSON序列化 */
    private Gson gson;

    /** 脏标记：需要持久化的key集合 */
    private Set<String> dirtyKeys;

    /** 存储文件名 */
    private static final String STORE_FILE = "memory_store.json";

    /** 是否已加载 */
    private boolean loaded = false;

    public TLLongTermMemoryModule() {
        super();
        this.memoryType = "longTerm";
    }

    public TLLongTermMemoryModule(String name) {
        super(name);
        this.memoryType = "longTerm";
    }

    public TLLongTermMemoryModule(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
        this.memoryType = "longTerm";
    }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null) {
            if (params.get("storagePath") != null)
                storagePath = params.get("storagePath");
            if (params.get("maxCacheEntries") != null) {
                try { maxCacheEntries = Integer.parseInt(params.get("maxCacheEntries")); }
                catch (NumberFormatException ignored) {}
            }
        }
    }

    @Override
    protected TLBaseModule init() {
        gson = new GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss").setPrettyPrinting().create();
        cache = new ConcurrentHashMap<>();
        dirtyKeys = ConcurrentHashMap.newKeySet();
        loadFromDisk();
        return this;
    }

    // ======================== 动作实现 ========================

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg store(Object fromWho, TLMsg msg) {
        TLMemoryEntry entry = (TLMemoryEntry) msg.getParam("entry", TLMemoryEntry.class);
        if (entry == null) {
            String key = msg.getStringParam(AI_P_MEMORYKEY, "");
            if (key.isEmpty()) {
                key = "mem_" + UUID.randomUUID().toString().substring(0, 8);
            }
            Object value = msg.getParam(AI_P_MEMORYVALUE);
            String sessionId = msg.getStringParam(AI_P_SESSIONID, "global");
            String tag = msg.getStringParam(AI_P_MEMORYTAG, null);
            int exptimeMinutes = msg.getIntParam(AI_P_MEMORYEXPTIME, -1);

            // 构建scoped key
            String scopedKey = sessionId + ":" + (tag != null ? tag + ":" : "") + key;
            entry = new TLMemoryEntry(scopedKey, value, memoryType);
            entry.setTag(tag);
            entry.setExpiresAt(calculateExpiresAt(exptimeMinutes));

            if (msg.containsParam("metadata")) {
                entry.setMetadata((Map<String, Object>) msg.getMapParam("metadata", new HashMap<>()));
            }
        }

        cache.put(entry.getKey(), entry);
        dirtyKeys.add(entry.getKey());
        persistToDisk();

        putLog("Long-term memory stored: " + entry.getKey(), LogLevel.DEBUG);
        return createMsg().setParam(RESULT, true).setParam("key", entry.getKey());
    }

    @Override
    protected TLMsg retrieve(Object fromWho, TLMsg msg) {
        String key = msg.getStringParam(AI_P_MEMORYKEY, "");
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "global");
        String tag = msg.getStringParam(AI_P_MEMORYTAG, null);

        // 构建完整key
        String scopedKey = sessionId + ":" + (tag != null ? tag + ":" : "") + key;

        TLMemoryEntry entry = cache.get(scopedKey);
        if (entry == null) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_MEMORYRESULT, null);
        }
        if (entry.isExpired()) {
            cache.remove(scopedKey);
            dirtyKeys.add(scopedKey);
            persistToDisk();
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
        String query = msg.getStringParam(AI_P_MEMORYQUERY, "");
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "global");
        String tag = msg.getStringParam(AI_P_MEMORYTAG, null);
        int topK = msg.getIntParam(AI_P_TOPK, 5);

        List<TLMemoryEntry> results = cache.values().stream()
                .filter(e -> !e.isExpired())
                .filter(e -> {
                    if (!"global".equals(sessionId) && !e.getKey().startsWith(sessionId + ":")) {
                        return false;
                    }
                    if (tag != null && !tag.equals(e.getTag())) {
                        return false;
                    }
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
        String tag = msg.getStringParam(AI_P_MEMORYTAG, null);

        String scopedKey = sessionId + ":" + (tag != null ? tag + ":" : "") + key;
        TLMemoryEntry removed = cache.remove(scopedKey);
        if (removed != null) {
            dirtyKeys.add(scopedKey);
            persistToDisk();
        }

        return createMsg().setParam(RESULT, removed != null).setParam("removed", removed != null);
    }

    @Override
    protected TLMsg clearAll(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "");
        if (sessionId.isEmpty()) {
            int size = cache.size();
            cache.clear();
            dirtyKeys.clear();
            persistToDisk();
            putLog("All long-term memory cleared: " + size + " entries", LogLevel.DEBUG);
            return createMsg().setParam(RESULT, true).setParam("cleared", size);
        } else {
            List<String> toRemove = cache.keySet().stream()
                    .filter(k -> k.startsWith(sessionId + ":"))
                    .collect(Collectors.toList());
            toRemove.forEach(k -> { cache.remove(k); dirtyKeys.add(k); });
            persistToDisk();
            putLog("Long-term memory cleared for session: " + sessionId + " (" + toRemove.size() + " entries)", LogLevel.DEBUG);
            return createMsg().setParam(RESULT, true).setParam("cleared", toRemove.size());
        }
    }

    // ======================== 持久化方法 ========================

    /**
     * 从磁盘加载记忆
     */
    protected synchronized void loadFromDisk() {
        Path filePath = Paths.get(storagePath, STORE_FILE);
        if (!Files.exists(filePath)) {
            loaded = true;
            putLog("No existing memory store found, starting fresh.", LogLevel.DEBUG);
            return;
        }

        try {
            String json = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
            if (json.trim().isEmpty()) {
                loaded = true;
                return;
            }

            java.lang.reflect.Type type = new TypeToken<List<TLMemoryEntry>>(){}.getType();
            List<TLMemoryEntry> entries = gson.fromJson(json, type);

            if (entries != null) {
                for (TLMemoryEntry entry : entries) {
                    if (!entry.isExpired() && entry.getKey() != null) {
                        cache.put(entry.getKey(), entry);
                    }
                }
                putLog("Loaded " + cache.size() + " memory entries from disk.", LogLevel.DEBUG);
            }
        } catch (IOException e) {
            putLog("Failed to load memory from disk: " + e.getMessage(), LogLevel.ERROR);
        }
        loaded = true;
    }

    /**
     * 持久化到磁盘
     */
    protected synchronized void persistToDisk() {
        if (!loaded) return; // 初始化阶段不持久化

        try {
            Path dirPath = Paths.get(storagePath);
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }

            // 清理过期条目
            cache.values().removeIf(TLMemoryEntry::isExpired);

            Path filePath = dirPath.resolve(STORE_FILE);
            // 按时间排序后持久化
            List<TLMemoryEntry> sorted = new ArrayList<>(cache.values());
            sorted.sort((a, b) -> Long.compare(a.getCreatedAt(), b.getCreatedAt()));
            String json = gson.toJson(sorted);
            Files.write(filePath, json.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            dirtyKeys.clear();
        } catch (IOException e) {
            putLog("Failed to persist memory: " + e.getMessage(), LogLevel.ERROR);
        }
    }
}
