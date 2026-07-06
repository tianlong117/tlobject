package cn.tianlong.tlobject.aiagent.memory;

import cn.tianlong.tlobject.aiagent.TLBaseMemory;
import cn.tianlong.tlobject.aiagent.TLMemoryEntry;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
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

    /** 新增条目队列（用于增量追加写入） */
    private ConcurrentLinkedQueue<TLMemoryEntry> pendingWrites;

    /** 异步写入线程 */
    private ExecutorService writeExecutor;

    /** 存储文件名——JSONL格式（每行一个JSON entry） */
    private static final String STORE_FILE = "memory_store.jsonl";

    /** compact阈值：dirty/deleted条目数超过此值时触发compact */
    private static final int COMPACT_THRESHOLD = 100;

    /** 已删除条目的key集合（用于compact时过滤） */
    private Set<String> deletedKeys;

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
        gson = new GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss").create();
        cache = new ConcurrentHashMap<>();
        pendingWrites = new ConcurrentLinkedQueue<>();
        deletedKeys = ConcurrentHashMap.newKeySet();
        writeExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "longterm-memory-writer");
            t.setDaemon(true);
            return t;
        });
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

            // 构建scoped key（含Agent命名空间前缀，用于多Agent隔离）
            String scopedKey = buildScopedKey(sessionId + ":" + (tag != null ? tag + ":" : "") + key);
            entry = new TLMemoryEntry(scopedKey, value, memoryType);
            entry.setTag(tag);
            entry.setExpiresAt(calculateExpiresAt(exptimeMinutes));

            if (msg.containsParam("metadata")) {
                entry.setMetadata((Map<String, Object>) msg.getMapParam("metadata", new HashMap<>()));
            }
        }

        cache.put(entry.getKey(), entry);
        // 强制maxCacheEntries限制
        ensureCapacity();
        // 增量异步写入：只追加新条目到JSONL文件
        pendingWrites.add(entry);
        schedulePersist();

        putLog("Long-term memory stored: " + entry.getKey(), LogLevel.DEBUG);
        return createMsg().setParam(RESULT, true).setParam("key", entry.getKey());
    }

    @Override
    protected TLMsg retrieve(Object fromWho, TLMsg msg) {
        String key = msg.getStringParam(AI_P_MEMORYKEY, "");
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "global");
        String tag = msg.getStringParam(AI_P_MEMORYTAG, null);

        // 构建完整key（含Agent命名空间）
        String scopedKey = buildScopedKey(sessionId + ":" + (tag != null ? tag + ":" : "") + key);

        TLMemoryEntry entry = cache.get(scopedKey);
        if (entry == null) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_MEMORYRESULT, null);
        }
        if (entry.isExpired()) {
            cache.remove(scopedKey);
            deletedKeys.add(scopedKey);
            schedulePersist();
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

        String scopedKey = buildScopedKey(sessionId + ":" + (tag != null ? tag + ":" : "") + key);
        TLMemoryEntry removed = cache.remove(scopedKey);
        if (removed != null) {
            deletedKeys.add(scopedKey);
            schedulePersist();
        }

        return createMsg().setParam(RESULT, removed != null).setParam("removed", removed != null);
    }

    @Override
    protected TLMsg clearAll(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "");
        if (sessionId.isEmpty()) {
            int size = cache.size();
            cache.clear();
            deletedKeys.clear();
            // 全量清除：compact时重写空文件
            scheduleCompact();
            putLog("All long-term memory cleared: " + size + " entries", LogLevel.DEBUG);
            return createMsg().setParam(RESULT, true).setParam("cleared", size);
        } else {
            List<String> toRemove = cache.keySet().stream()
                    .filter(k -> k.startsWith(sessionId + ":"))
                    .collect(Collectors.toList());
            toRemove.forEach(k -> { cache.remove(k); deletedKeys.add(k); });
            scheduleCompact();
            putLog("Long-term memory cleared for session: " + sessionId + " (" + toRemove.size() + " entries)", LogLevel.DEBUG);
            return createMsg().setParam(RESULT, true).setParam("cleared", toRemove.size());
        }
    }

    // ======================== 持久化方法 ========================

    /**
     * 从磁盘加载记忆（JSONL格式，每行一条记录）
     */
    protected synchronized void loadFromDisk() {
        Path filePath = Paths.get(storagePath, STORE_FILE);
        if (!Files.exists(filePath)) {
            loaded = true;
            putLog("No existing memory store found, starting fresh.", LogLevel.DEBUG);
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            int loadedCount = 0;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    TLMemoryEntry entry = gson.fromJson(line, TLMemoryEntry.class);
                    if (entry != null && !entry.isExpired() && entry.getKey() != null) {
                        cache.put(entry.getKey(), entry);
                        loadedCount++;
                    }
                } catch (Exception e) {
                    putLog("Skip corrupted line in memory store: " + e.toString(), LogLevel.WARN);
                }
            }
            putLog("Loaded " + loadedCount + " memory entries from disk (JSONL).", LogLevel.DEBUG);
        } catch (IOException e) {
            putLog("Failed to load memory from disk: " + e.getMessage(), LogLevel.ERROR);
        }
        loaded = true;
    }

    /**
     * 调度增量持久化（异步追加写入pending entries）
     */
    private void schedulePersist() {
        if (!loaded) return;
        writeExecutor.submit(() -> {
            try {
                persistPending();
            } catch (Exception e) {
                putLog("Async persist error: " + e.getMessage(), LogLevel.ERROR);
            }
        });
    }

    /**
     * 调度compact（异步重写全文件，去除deleted/expired条目）
     */
    private void scheduleCompact() {
        if (!loaded) return;
        writeExecutor.submit(() -> {
            try {
                compact();
            } catch (Exception e) {
                putLog("Async compact error: " + e.getMessage(), LogLevel.ERROR);
            }
        });
    }

    /**
     * 增量追加pending entries到JSONL文件
     */
    protected synchronized void persistPending() {
        if (!loaded) return;
        if (pendingWrites.isEmpty()) return;

        try {
            Path dirPath = Paths.get(storagePath);
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }
            Path filePath = dirPath.resolve(STORE_FILE);

            List<String> lines = new ArrayList<>();
            TLMemoryEntry entry;
            while ((entry = pendingWrites.poll()) != null) {
                lines.add(gson.toJson(entry));
            }

            if (!lines.isEmpty()) {
                Files.write(filePath, lines, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }

            // 如果删除条目过多，触发compact
            if (deletedKeys.size() >= COMPACT_THRESHOLD) {
                compact();
            }
        } catch (IOException e) {
            putLog("Failed to persist pending entries: " + e.getMessage(), LogLevel.ERROR);
        }
    }

    /**
     * Compact：重写全文件，去除已删除和已过期的条目
     */
    protected synchronized void compact() {
        if (!loaded) return;
        try {
            Path dirPath = Paths.get(storagePath);
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }
            Path filePath = dirPath.resolve(STORE_FILE);

            // 清理过期条目
            cache.values().removeIf(TLMemoryEntry::isExpired);

            // 重写整个文件（只保留未删除的有效条目）
            List<TLMemoryEntry> sorted = new ArrayList<>(cache.values());
            sorted.sort((a, b) -> Long.compare(a.getCreatedAt(), b.getCreatedAt()));

            List<String> lines = new ArrayList<>();
            for (TLMemoryEntry e : sorted) {
                lines.add(gson.toJson(e));
            }

            Files.write(filePath, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            deletedKeys.clear();
            putLog("Memory store compacted: " + sorted.size() + " entries", LogLevel.DEBUG);
        } catch (IOException e) {
            putLog("Failed to compact memory: " + e.getMessage(), LogLevel.ERROR);
        }
    }

    /**
     * 确保缓存不超过maxCacheEntries，超出时驱逐最旧条目
     */
    private void ensureCapacity() {
        if (cache.size() <= maxCacheEntries) return;
        // 找到最旧的条目并移除
        cache.values().stream()
                .min(Comparator.comparingLong(TLMemoryEntry::getCreatedAt))
                .ifPresent(oldest -> {
                    cache.remove(oldest.getKey());
                    deletedKeys.add(oldest.getKey());
                });
    }
}
