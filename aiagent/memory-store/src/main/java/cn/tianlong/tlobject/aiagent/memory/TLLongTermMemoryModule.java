package cn.tianlong.tlobject.aiagent.memory;

import cn.tianlong.tlobject.aiagent.TLBaseMemory;
import cn.tianlong.tlobject.aiagent.TLConversationHistory;
import cn.tianlong.tlobject.aiagent.TLLlmProvider;
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

    /** 存储基础目录路径（各用户在此下建子目录） */
    private String storagePath = "./data/";

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

    /** 获取 userId 专属的存储文件路径 */
    private Path getUserStorePath(String userId) {
        String uid = (userId != null && !userId.isEmpty()) ? userId : "default";
        return Paths.get(storagePath, uid, "longterm_memory", STORE_FILE);
    }

    /** 扫描所有用户目录，返回已有的 store 文件列表 */
    private java.util.List<Path> listAllStoreFiles() {
        java.util.List<Path> files = new java.util.ArrayList<>();
        try {
            java.io.File base = Paths.get(storagePath).toFile();
            if (!base.exists() || !base.isDirectory()) return files;
            java.io.File[] userDirs = base.listFiles(java.io.File::isDirectory);
            if (userDirs == null) return files;
            for (java.io.File ud : userDirs) {
                Path p = Paths.get(ud.getAbsolutePath(), "longterm_memory", STORE_FILE);
                if (Files.exists(p)) files.add(p);
            }
        } catch (Exception ignored) {}
        return files;
    }

    /** compact阈值：dirty/deleted条目数超过此值时触发compact */
    private static final int COMPACT_THRESHOLD = 100;

    /** 已删除条目的key集合（用于compact时过滤） */
    private Set<String> deletedKeys;

    /** 是否已加载 */
    private boolean loaded = false;

    // ---- embedding 语义搜索 ----
    private boolean enableEmbedding = false;
    private String embeddingModel = "text-embedding-3-small";

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
            if ("true".equals(params.get(AI_P_EMBEDDINGENABLE))) {
                enableEmbedding = true;
                if (params.get(AI_P_EMBEDDINGMODEL) != null)
                    embeddingModel = params.get(AI_P_EMBEDDINGMODEL);
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
        String userId = msg.getStringParam("userId", msg.getStringParam(AI_P_SESSIONID, "global"));
        TLMemoryEntry entry = (TLMemoryEntry) msg.getParam("entry", TLMemoryEntry.class);
        if (entry == null) {
            String key = msg.getStringParam(AI_P_MEMORYKEY, "");
            if (key.isEmpty()) {
                key = "mem_" + UUID.randomUUID().toString().substring(0, 8);
            }
            Object value = msg.getParam(AI_P_MEMORYVALUE);
            String tag = msg.getStringParam(AI_P_MEMORYTAG, null);
            int exptimeMinutes = msg.getIntParam(AI_P_MEMORYEXPTIME, -1);

            // 构建scoped key（用 userId 做用户级隔离，跨会话共享记忆）
            String scopedKey = buildScopedKey(userId + ":" + (tag != null ? tag + ":" : "") + key);
            entry = new TLMemoryEntry(scopedKey, value, memoryType);
            entry.setTag(tag);
            entry.setExpiresAt(calculateExpiresAt(exptimeMinutes));

            if (msg.containsParam("metadata")) {
                entry.setMetadata((Map<String, Object>) msg.getMapParam("metadata", new HashMap<>()));
            }
        }

        // embedding：文本向量化并存入 entry metadata
        Object embedText = entry.getValue();
        if (enableEmbedding && embeddingProviderInstance != null && embedText != null) {
            try {
                String text = embedText.toString();
                TLMsg embedResult = putMsg(embeddingProviderInstance, createMsg().setAction(LLM_EMBEDDING)
                        .setParam(AI_P_EMBEDTEXT, text).setParam(AI_P_EMBEDDINGMODEL, embeddingModel));
                if (embedResult == null || !embedResult.parseBoolean(RESULT, false)) {
                    putLog("embedding store failed: " + (embedResult != null
                            ? embedResult.getStringParam("error", "unknown") : "no response"), LogLevel.WARN);
                } else {
                    Object vecObj = embedResult.getParam(AI_P_EMBEDDING);
                    if (vecObj instanceof float[]) {
                        float[] vector = (float[]) vecObj;
                        Map<String, Object> meta = entry.getMetadata();
                        if (meta == null) meta = new HashMap<>();
                        meta.put("embedding", vectorToString(vector));
                        entry.setMetadata(meta);
                    }
                }
            } catch (Exception e) {
                putLog("embedding store failed: " + e.toString(), LogLevel.WARN);
            }
        }

        cache.put(entry.getKey(), entry);
        // 强制maxCacheEntries限制
        ensureCapacity();
        // 增量异步写入：只追加新条目到JSONL文件
        pendingWrites.add(entry);
        schedulePersist();

        // 摘要模式：碎片照常落库（不改动现有长期记忆）+ 进攒批缓冲；满批 LLM 提炼
        if (summaryEnabled && entry.getValue() != null) {
            try {
                String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
                String agentName = msg.getStringParam("agentName", null);
                long maxSeq = msg.getIntParam("maxSeq", 0);
                collectSummaryFragment(sessionId, userId, agentName, entry.getValue().toString(), maxSeq);
            } catch (Exception e) {
                putLog("summary collect failed: " + e.toString(), LogLevel.WARN);
            }
        }

        putLog("Long-term memory stored: " + entry.getKey(), LogLevel.DEBUG);
        return createMsg().setParam(RESULT, true).setParam("key", entry.getKey());
    }

    /** 摘要条目持久化：内存缓存 + 异步追加 JSONL（与碎片同通道） */
    @Override
    protected void storeSummaryEntry(TLMemoryEntry entry) {
        cache.put(entry.getKey(), entry);
        pendingWrites.add(entry);
        schedulePersist();
    }

    /** 补批收集：该 userId 未被摘要覆盖的历史碎片（createdAt > fromTs，时间序） */
    @Override
    protected List<String> seedSummaryBuffer(String userId, long fromTs) {
        return cache.values().stream()
                .filter(e -> !e.isExpired())
                .filter(e -> e.getKey().startsWith(userId + ":" + TAG_CHAT_HISTORY + ":"))
                .filter(e -> e.getCreatedAt() > fromTs)
                .sorted(Comparator.comparingLong(TLMemoryEntry::getCreatedAt))
                .map(e -> e.getValue() != null ? e.getValue().toString() : "")
                .collect(Collectors.toList());
    }

    @Override
    protected TLMsg retrieve(Object fromWho, TLMsg msg) {
        String key = msg.getStringParam(AI_P_MEMORYKEY, "");
        String userId = msg.getStringParam("userId", msg.getStringParam(AI_P_SESSIONID, "global"));
        String tag = msg.getStringParam(AI_P_MEMORYTAG, null);

        // 构建完整key（用 userId）
        String scopedKey = buildScopedKey(userId + ":" + (tag != null ? tag + ":" : "") + key);

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

    @SuppressWarnings("unchecked")
    @Override
    protected TLMsg search(Object fromWho, TLMsg msg) {
        String query = msg.getStringParam(AI_P_MEMORYQUERY, "");
        String userId = msg.getStringParam("userId", msg.getStringParam(AI_P_SESSIONID, "global"));
        String tag = msg.getStringParam(AI_P_MEMORYTAG, null);
        int topK = msg.getIntParam(AI_P_TOPK, 5);
        // 摘要模式路由：碎片仅当"已被该用户摘要覆盖"（createdAt <= 最新摘要时间）时才排除；
        // 未被覆盖的碎片（开启前的历史碎片、补批残余）照常召回——不丢记忆。
        // 存在未覆盖碎片时触发后台增量补批（一次性）。
        boolean excludeChatHistory = summaryEnabled && tag == null;
        long maxSummaryTs = 0L, maxFragmentTs = 0L;
        if (excludeChatHistory) {
            for (TLMemoryEntry e : cache.values()) {
                if (e.isExpired()) continue;
                if (!"global".equals(userId) && !e.getKey().startsWith(userId + ":")) continue;
                if (e.getKey().contains(":" + TAG_SESSION_SUMMARY + ":")) {
                    if (e.getCreatedAt() > maxSummaryTs) maxSummaryTs = e.getCreatedAt();
                } else if (TAG_CHAT_HISTORY.equals(e.getTag())) {
                    if (e.getCreatedAt() > maxFragmentTs) maxFragmentTs = e.getCreatedAt();
                }
            }
            if (maxFragmentTs > maxSummaryTs) {
                ensureSummarySeed(userId, maxSummaryTs);
            }
        }
        boolean hasSummaryCover = maxSummaryTs > 0L;

        // embedding 语义搜索
        if (enableEmbedding && embeddingProviderInstance != null && !query.isEmpty()) {
            try {
                TLMsg embedResult = putMsg(embeddingProviderInstance, createMsg().setAction(LLM_EMBEDDING)
                        .setParam(AI_P_EMBEDTEXT, query).setParam(AI_P_EMBEDDINGMODEL, embeddingModel));
                if (embedResult != null && embedResult.parseBoolean(RESULT, false)
                        && embedResult.getParam(AI_P_EMBEDDING) instanceof float[]) {
                    float[] queryVec = (float[]) embedResult.getParam(AI_P_EMBEDDING);
                    // 过滤候选 + 算相似度
                    List<Map.Entry<TLMemoryEntry, Double>> scored = new ArrayList<>();
                    for (TLMemoryEntry e : cache.values()) {
                        if (e.isExpired()) continue;
                        if (!"global".equals(userId) && !e.getKey().startsWith(userId + ":")) continue;
                        if (tag != null && !tag.equals(e.getTag())) continue;
                        if (excludeChatHistory && TAG_CHAT_HISTORY.equals(e.getTag())
                                && e.getCreatedAt() <= maxSummaryTs) continue;
                        float[] entryVec = getEmbedding(e);
                        if (entryVec == null) continue;
                        double sim = cosineSimilarity(queryVec, entryVec);
                        if (sim > 0.3) scored.add(new AbstractMap.SimpleEntry<>(e, sim));
                    }
                    if (!scored.isEmpty()) {
                        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
                        List<TLMemoryEntry> results = new ArrayList<>();
                        for (int i = 0; i < Math.min(topK, scored.size()); i++)
                            results.add(scored.get(i).getKey());
                        putLog("embedding search: query=" + query + " candidates=" + scored.size(), LogLevel.DEBUG);
                        return createMsg().setParam(RESULT, true)
                                .setParam(AI_P_MEMORYRESULT, results).setParam("count", results.size());
                    }
                }
            } catch (Exception e) {
                putLog("embedding search failed: " + e.toString() + ", fallback to contains", LogLevel.WARN);
            }
        }

        // 回落 contains 匹配（lambda 需 final 拷贝：maxSummaryTs 在聚合循环中已赋值）
        final long summaryCoverTs = maxSummaryTs;
        List<TLMemoryEntry> results = cache.values().stream()
                .filter(e -> !e.isExpired())
                .filter(e -> {
                    if (!"global".equals(userId) && !e.getKey().startsWith(userId + ":")) {
                        return false;
                    }
                    if (tag != null && !tag.equals(e.getTag())) {
                        return false;
                    }
                    if (excludeChatHistory && TAG_CHAT_HISTORY.equals(e.getTag())
                            && e.getCreatedAt() <= summaryCoverTs) {
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

    /** 从 entry metadata 中提取 embedding 向量（null 表示无向量） */
    private static float[] getEmbedding(TLMemoryEntry entry) {
        if (entry.getMetadata() == null) return null;
        Object v = entry.getMetadata().get("embedding");
        if (v == null) return null;
        return stringToVector(v.toString());
    }

    /** float[] → 逗号分隔字符串（存入 JSON/DB） */
    static String vectorToString(float[] vec) {
        if (vec == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        return sb.toString();
    }

    /** 逗号分隔字符串 → float[] */
    static float[] stringToVector(String str) {
        if (str == null || str.isEmpty()) return null;
        String[] parts = str.split(",");
        float[] vec = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { vec[i] = Float.parseFloat(parts[i]); }
            catch (NumberFormatException e) { return null; }
        }
        return vec;
    }

    /** 余弦相似度 */
    static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    @Override
    protected TLMsg delete(Object fromWho, TLMsg msg) {
        String key = msg.getStringParam(AI_P_MEMORYKEY, "");
        String userId = msg.getStringParam("userId", msg.getStringParam(AI_P_SESSIONID, "global"));
        String tag = msg.getStringParam(AI_P_MEMORYTAG, null);

        String scopedKey = buildScopedKey(userId + ":" + (tag != null ? tag + ":" : "") + key);
        TLMemoryEntry removed = cache.remove(scopedKey);
        if (removed != null) {
            deletedKeys.add(scopedKey);
            schedulePersist();
        }

        return createMsg().setParam(RESULT, removed != null).setParam("removed", removed != null);
    }

    @Override
    protected TLMsg clearAll(Object fromWho, TLMsg msg) {
        String userId = msg.getStringParam("userId", msg.getStringParam(AI_P_SESSIONID, ""));
        if (userId.isEmpty()) {
            int size = cache.size();
            cache.clear();
            deletedKeys.clear();
            // 全量清除：compact时重写空文件
            scheduleCompact();
            putLog("All long-term memory cleared: " + size + " entries", LogLevel.DEBUG);
            return createMsg().setParam(RESULT, true).setParam("cleared", size);
        } else {
            List<String> toRemove = cache.keySet().stream()
                    .filter(k -> k.startsWith(userId + ":"))
                    .collect(Collectors.toList());
            toRemove.forEach(k -> { cache.remove(k); deletedKeys.add(k); });
            scheduleCompact();
            putLog("Long-term memory cleared for user: " + userId + " (" + toRemove.size() + " entries)", LogLevel.DEBUG);
            return createMsg().setParam(RESULT, true).setParam("cleared", toRemove.size());
        }
    }

    // ======================== 持久化方法 ========================

    /**
     * 从磁盘加载记忆（JSONL格式，每行一条记录）
     */
    protected synchronized void loadFromDisk() {
        java.util.List<Path> storeFiles = listAllStoreFiles();
        if (storeFiles.isEmpty()) {
            loaded = true;
            putLog("No existing memory store found, starting fresh.", LogLevel.DEBUG);
            return;
        }
        int totalLoaded = 0;
        for (Path filePath : storeFiles) {
            totalLoaded += loadFromFile(filePath);
        }
        loaded = true;
        putLog("Memory store loaded from " + storeFiles.size() + " user(s), " + totalLoaded + " entries total.", LogLevel.DEBUG);
    }

    /** 从单个文件加载记忆 */
    private int loadFromFile(Path filePath) {
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    TLMemoryEntry entry = gson.fromJson(line, TLMemoryEntry.class);
                    if (entry != null && !entry.isExpired() && entry.getKey() != null) {
                        cache.put(entry.getKey(), entry);
                        count++;
                    }
                } catch (Exception e) {
                    putLog("Skip corrupted line in memory store: " + e.toString(), LogLevel.WARN);
                }
            }
        } catch (IOException e) {
            putLog("Failed to load memory from disk: " + e.getMessage(), LogLevel.ERROR);
        }
        return count;
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
    /** 从 scopedKey 提取 userId（第一段） */
    private String extractUserId(String scopedKey) {
        if (scopedKey == null) return "default";
        int idx = scopedKey.indexOf(':');
        return idx > 0 ? scopedKey.substring(0, idx) : "default";
    }

    protected synchronized void persistPending() {
        if (!loaded) return;
        if (pendingWrites.isEmpty()) return;

        try {
            // 按 userId 分组
            Map<String, List<String>> userLines = new LinkedHashMap<>();
            TLMemoryEntry entry;
            while ((entry = pendingWrites.poll()) != null) {
                String uid = extractUserId(entry.getKey());
                userLines.computeIfAbsent(uid, k -> new ArrayList<>()).add(gson.toJson(entry));
            }

            for (Map.Entry<String, List<String>> e : userLines.entrySet()) {
                Path filePath = getUserStorePath(e.getKey());
                Files.createDirectories(filePath.getParent());
                Files.write(filePath, e.getValue(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }

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
            // 清理过期条目
            cache.values().removeIf(TLMemoryEntry::isExpired);

            // 按 userId 分组，每个用户一个文件
            Map<String, List<TLMemoryEntry>> userEntries = new LinkedHashMap<>();
            for (TLMemoryEntry e : cache.values()) {
                String uid = extractUserId(e.getKey());
                userEntries.computeIfAbsent(uid, k -> new ArrayList<>()).add(e);
            }

            for (Map.Entry<String, List<TLMemoryEntry>> ue : userEntries.entrySet()) {
                List<TLMemoryEntry> sorted = ue.getValue();
                sorted.sort((a, b) -> Long.compare(a.getCreatedAt(), b.getCreatedAt()));
                List<String> lines = new ArrayList<>();
                for (TLMemoryEntry e : sorted) lines.add(gson.toJson(e));

                Path filePath = getUserStorePath(ue.getKey());
                Files.createDirectories(filePath.getParent());
                Files.write(filePath, lines, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }

            deletedKeys.clear();
            putLog("Memory store compacted: " + cache.size() + " entries, " + userEntries.size() + " user(s)", LogLevel.DEBUG);
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
