package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 抽象记忆模块基类。管理AI Agent的记忆存储和检索。
 * 短期记忆（内存Map，带TTL）和长期记忆（文件/数据库持久化）的具体实现继承此类。
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public abstract class TLBaseMemory extends TLBaseModule implements TLAiAgentParamString {

    /** 记忆类型：shortTerm / longTerm */
    protected String memoryType = "shortTerm";

    /** 默认过期时间（分钟），0 = 永不过期 */
    protected int defaultExptime = 0;

    /** 命名空间前缀，用于多Agent记忆隔离。通常设为所属Agent名称 */
    protected String agentNamespace = "";

    /** embedding 调用的实际 Provider 实例（由 Agent 注入；工厂单例无 apiKey，不可用名字查找） */
    protected TLLlmProvider embeddingProviderInstance;

    // ---- 会话摘要（攒批总结, tag=session_summary）----
    /** 是否开启摘要模式：开启后碎片照常落库，但召回只返回摘要条目 */
    protected boolean summaryEnabled = false;
    /** 攒批大小（碎片条数, 默认 40 轮） */
    protected int summaryBatchSize = 40;
    /** 摘要 provider 模块名（可选：配置了自建优先，否则借用 store 消息的 agentName） */
    protected String summaryProviderName;
    /** 摘要用模型（可选，provider 支持参数覆盖则生效） */
    protected String summaryModel;
    /** 摘要提示词（默认内置中文） */
    protected String summaryPrompt = DEFAULT_SUMMARY_PROMPT;
    protected static final String DEFAULT_SUMMARY_PROMPT =
            "请将以下对话历史碎片压缩为一段简洁的中文摘要。"
            + "保留：关键事实、用户偏好、未完成的任务、明确的约定。"
            + "不要添加新信息，不要逐条复述，直接用一段话概括。";
    /** 摘要条目标记 tag */
    protected static final String TAG_SESSION_SUMMARY = "session_summary";
    /** 碎片条目标记 tag（与 agent 保存点一致） */
    protected static final String TAG_CHAT_HISTORY = "chat_history";
    /** sessionId → 攒批中的碎片（Q→A 配对文本） */
    private final Map<String, List<String>> summaryBuffer = new ConcurrentHashMap<>();
    /** sessionId → 本批最新碎片对应的 context 最大 seq（coverSeq） */
    private final Map<String, Long> summaryCoverSeq = new ConcurrentHashMap<>();
    /** 历史碎片补批防重入（并发 search 只触发一次） */
    private final java.util.concurrent.atomic.AtomicBoolean summarySeedInProgress =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 最近一次 store 的 agentName（后台补批借用 agent LLM 用；无则退回 summaryProvider 自建） */
    protected volatile String lastAgentName;

    public TLBaseMemory() {
        super();
    }

    public TLBaseMemory(String name) {
        super(name);
    }

    public TLBaseMemory(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("memoryType") != null)
                memoryType = params.get("memoryType");
            if (params.get("defaultExptime") != null) {
                try {
                    defaultExptime = Integer.parseInt(params.get("defaultExptime"));
                } catch (NumberFormatException ignored) {}
            }
            if (params.get("agentNamespace") != null)
                agentNamespace = params.get("agentNamespace");
            if (params.get("summaryEnabled") != null)
                summaryEnabled = Boolean.parseBoolean(params.get("summaryEnabled"));
            if (params.get("summaryBatchSize") != null) {
                try { summaryBatchSize = Integer.parseInt(params.get("summaryBatchSize")); }
                catch (NumberFormatException ignored) {}
            }
            summaryProviderName = params.get("summaryProvider");
            summaryModel = params.get("summaryModel");
            if (params.get("summaryPrompt") != null && !params.get("summaryPrompt").isEmpty())
                summaryPrompt = params.get("summaryPrompt");
        }
    }

    /**
     * 构建带命名空间前缀的key，用于多Agent记忆隔离。
     * 若agentNamespace为空则返回原始key，保持向后兼容。
     */
    protected String buildScopedKey(String key) {
        if (agentNamespace != null && !agentNamespace.isEmpty())
            return agentNamespace + ":" + key;
        return key;
    }

    @Override
    protected TLBaseModule init() {
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case MEMORY_STORE:
                returnMsg = store(fromWho, msg);
                break;
            case MEMORY_RETRIEVE:
                returnMsg = retrieve(fromWho, msg);
                break;
            case MEMORY_SEARCH:
                returnMsg = search(fromWho, msg);
                break;
            case MEMORY_DELETE:
                returnMsg = delete(fromWho, msg);
                break;
            case MEMORY_CLEARALL:
                returnMsg = clearAll(fromWho, msg);
                break;
            case "setEmbeddingProvider":
                if (msg.getParam("provider") instanceof TLLlmProvider)
                    embeddingProviderInstance = (TLLlmProvider) msg.getParam("provider");
                returnMsg = createMsg().setParam(RESULT, true);
                break;
            default:
                returnMsg = null;
        }
        return returnMsg;
    }

    // ======================== 抽象方法（子类实现） ========================

    /**
     * 存储记忆条目
     */
    protected abstract TLMsg store(Object fromWho, TLMsg msg);

    /**
     * 按键检索记忆
     */
    protected abstract TLMsg retrieve(Object fromWho, TLMsg msg);

    /**
     * 按查询文本搜索记忆（支持模糊/语义搜索）
     */
    protected abstract TLMsg search(Object fromWho, TLMsg msg);

    /**
     * 按键删除记忆
     */
    protected abstract TLMsg delete(Object fromWho, TLMsg msg);

    /**
     * 清除所有记忆
     */
    protected abstract TLMsg clearAll(Object fromWho, TLMsg msg);

    // ======================== 工具方法 ========================

    /**
     * 计算过期时间戳
     */
    protected long calculateExpiresAt(int exptimeMinutes) {
        if (exptimeMinutes <= 0) {
            if (defaultExptime <= 0) return 0L;
            exptimeMinutes = defaultExptime;
        }
        return System.currentTimeMillis() + (exptimeMinutes * 60L * 1000L);
    }

    // ======================== 会话摘要（攒批总结） ========================

    /**
     * 碎片进攒批缓冲；满批生成摘要条目（tag=session_summary，metadata 带 sessionId + coverSeq）。
     * 由子类 store 在碎片照常落库后调用；摘要 LLM 解析混合（summaryProvider 自建 / 借用 agent）。
     */
    protected void collectSummaryFragment(String sessionId, String userId, String agentName,
                                          String fragmentValue, long maxSeq) {
        if (!summaryEnabled || fragmentValue == null) return;
        if (agentName != null && !agentName.isEmpty()) lastAgentName = agentName;
        synchronized (summaryBuffer) {
            List<String> buffer = summaryBuffer.computeIfAbsent(sessionId, k -> new ArrayList<>());
            buffer.add(fragmentValue);
            // 本条碎片对应的 context 最大 seq 作为本批 coverSeq 候选（agent 传入的最新 seq）
            summaryCoverSeq.put(sessionId, maxSeq);
            if (buffer.size() >= summaryBatchSize) {
                maybeGenerateSummary(sessionId, userId, agentName);
            }
        }
    }

    /**
     * 满批时 LLM 提炼摘要。失败（provider 不可用 / LLM 调用失败）→ 缓冲保留，下次 store 重试。
     * 生成成功后调用 storeSummaryEntry 由子类持久化（文件 JSONL / DB 表）。
     */
    protected void maybeGenerateSummary(String sessionId, String userId, String agentName) {
        List<String> buffer = summaryBuffer.get(sessionId);
        if (buffer == null || buffer.isEmpty()) return;
        Long coverSeqObj = summaryCoverSeq.get(sessionId);
        long coverSeq = coverSeqObj != null ? coverSeqObj : 0L;

        TLLlmProvider provider = resolveSummaryProvider(agentName);
        if (provider == null) {
            putLog("summary provider unavailable, buffer kept (session=" + sessionId + ")", LogLevel.WARN);
            return; // 缓冲保留，下次重试
        }

        String summary = callSummaryLlm(provider, buffer);
        if (summary == null || summary.isEmpty()) {
            putLog("summary generation failed, buffer kept (session=" + sessionId + ")", LogLevel.WARN);
            return; // 缓冲保留，下次重试
        }

        try {
            String key = "sum_" + sessionId + "_" + System.currentTimeMillis();
            // key 带 userId 前缀：与碎片同构，search 的 userId 前缀隔离照常生效
            String scopedKey = buildScopedKey(userId + ":" + TAG_SESSION_SUMMARY + ":" + key);
            TLMemoryEntry sEntry = new TLMemoryEntry(scopedKey, summary, memoryType);
            sEntry.setTag(TAG_SESSION_SUMMARY);
            Map<String, Object> meta = new HashMap<>();
            meta.put("sessionId", sessionId);
            meta.put("coverSeq", coverSeq);
            sEntry.setMetadata(meta);
            storeSummaryEntry(sEntry);
            summaryBuffer.remove(sessionId);
            summaryCoverSeq.remove(sessionId);
            putLog("Session summary generated: " + sessionId + " coverSeq=" + coverSeq
                    + " fragments=" + buffer.size(), LogLevel.INFO);
        } catch (Exception e) {
            putLog("summary store failed: " + e.toString(), LogLevel.WARN);
        }
    }

    /** 解析摘要 LLM：① summaryProvider 自建 ② 借用 agent（getLlmProvider） ③ 失败返回 null */
    protected TLLlmProvider resolveSummaryProvider(String agentName) {
        if (summaryProviderName != null && !summaryProviderName.isEmpty()) {
            try {
                Object m = getModule(summaryProviderName);
                if (m instanceof TLLlmProvider) {
                    putLog("Summary provider resolved (self): " + summaryProviderName, LogLevel.DEBUG);
                    return (TLLlmProvider) m;
                }
                putLog("summaryProvider not found: " + summaryProviderName, LogLevel.WARN);
            } catch (Exception e) {
                putLog("summaryProvider resolve failed: " + e.toString(), LogLevel.WARN);
            }
        }
        if (agentName != null && !agentName.isEmpty()) {
            try {
                TLMsg refResult = putMsg(agentName,
                        createMsg().setAction("getLlmProvider").setParam("provName", ""));
                Object ref = refResult != null ? refResult.getParam("provider") : null;
                if (ref instanceof TLLlmProvider) {
                    putLog("Summary provider borrowed from agent: " + agentName, LogLevel.DEBUG);
                    return (TLLlmProvider) ref;
                }
                putLog("summary provider borrow no provider: agent=" + agentName
                        + " result=" + (refResult != null ? refResult.getStringParam(RESULT, "") : "null"), LogLevel.WARN);
            } catch (Exception e) {
                putLog("summary provider borrow failed: agent=" + agentName + " err=" + e, LogLevel.WARN);
            }
        }
        return null;
    }

    /** 调用 LLM 提炼摘要（非流式 completion，碎片拼成一条 user 消息） */
    protected String callSummaryLlm(TLLlmProvider provider, List<String> fragments) {
        try {
            StringBuilder body = new StringBuilder();
            for (String f : fragments) {
                body.append("- ").append(f).append("\n");
            }
            List<TLConversationHistory> input = new ArrayList<>();
            input.add(new TLConversationHistory(TLConversationHistory.Role.system, summaryPrompt));
            input.add(new TLConversationHistory(TLConversationHistory.Role.user, body.toString()));
            TLMsg llmMsg = createMsg().setAction(LLM_COMPLETION)
                    .setParam(AI_P_MESSAGEHISTORY, input)
                    .setParam(AI_P_MAXTOKENS, 1024);
            if (summaryModel != null && !summaryModel.isEmpty()) {
                llmMsg.setParam(AI_P_MODEL, summaryModel);
            }
            TLMsg result = putMsg(provider, llmMsg);
            if (result == null) return null;
            return result.getStringParam(AI_P_RESPONSE, null);
        } catch (Exception e) {
            putLog("summary LLM call failed: " + e.toString(), LogLevel.WARN);
            return null;
        }
    }

    /**
     * 持久化摘要条目（子类实现：文件 JSONL / DB 表）。默认空实现（短记忆等不需要摘要的模块）。
     */
    protected void storeSummaryEntry(TLMemoryEntry entry) {
    }

    // ======================== 历史碎片补批（开启摘要后, 后台一次性增量） ========================

    /**
     * 历史碎片增量补批：search 时发现存在未被摘要覆盖的碎片（maxFragmentTs > maxSummaryTs）触发。
     * 后台 daemon 线程执行，不阻塞 search/store；防重入（进行中并发 search 只触发一次）；
     * 线程结束重置标志——成功则下轮 maxFragmentTs <= maxSummaryTs 自然不再触发，失败则下轮再试（碎片照常召回，自愈）。
     */
    protected void ensureSummarySeed(String userId, long fromTs) {
        if (!summaryEnabled) return;
        if (!summarySeedInProgress.compareAndSet(false, true)) return;
        try {
            List<String> fragments = seedSummaryBuffer(userId, fromTs);
            if (fragments == null || fragments.isEmpty()) {
                summarySeedInProgress.set(false);
                return;
            }
            // 不足一批不浓缩：碎片继续累积（search 每轮复查触发），与 summaryBatchSize 攒批语义一致。
            // 原实现 Math.min 只限制批量上限，1-2 条碎片也会立即生成摘要 → 碎片与摘要 1:1 膨胀。
            if (fragments.size() < summaryBatchSize) {
                summarySeedInProgress.set(false);
                return;
            }
            Thread t = new Thread(() -> {
                try {
                    TLLlmProvider provider = resolveSummaryProvider(lastAgentName);
                    if (provider == null) return; // 下次再试
                    List<String> pending = new ArrayList<>(fragments);
                    while (!pending.isEmpty()) {
                        int n = Math.min(summaryBatchSize, pending.size());
                        List<String> batch = new ArrayList<>(pending.subList(0, n));
                        pending = new ArrayList<>(pending.subList(n, pending.size()));
                        String summary = callSummaryLlm(provider, batch);
                        if (summary == null || summary.isEmpty()) {
                            putLog("summary seed batch failed, remaining kept for retry (user=" + userId + ")", LogLevel.WARN);
                            return; // 剩余碎片下轮再试（未浓缩前照常召回）
                        }
                        storeSummaryEntry(createSeedSummaryEntry(userId, summary));
                    }
                    putLog("Summary seed done for user: " + userId + " fragments=" + fragments.size(), LogLevel.INFO);
                } catch (Exception e) {
                    putLog("summary seed failed: " + e.toString(), LogLevel.WARN);
                } finally {
                    summarySeedInProgress.set(false);
                }
            }, "memory-summary-seed");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            summarySeedInProgress.set(false);
            putLog("summary seed start failed: " + e.toString(), LogLevel.WARN);
        }
    }

    /**
     * 收集该 userId 未被摘要覆盖的历史碎片（createdAt > fromTs，时间序）。
     * 子类实现（文件版遍历 cache / DB 版 SQL）；默认返回 null（不需要补批的模块）。
     */
    protected List<String> seedSummaryBuffer(String userId, long fromTs) {
        return null;
    }

    /** 构建补批摘要条目（历史碎片无会话归属：sessionId=null、coverSeq=0，仅作召回内容） */
    protected TLMemoryEntry createSeedSummaryEntry(String userId, String summary) {
        String key = "sum_legacy_" + System.currentTimeMillis();
        String scopedKey = buildScopedKey(userId + ":" + TAG_SESSION_SUMMARY + ":" + key);
        TLMemoryEntry sEntry = new TLMemoryEntry(scopedKey, summary, memoryType);
        sEntry.setTag(TAG_SESSION_SUMMARY);
        Map<String, Object> meta = new HashMap<>();
        meta.put("coverSeq", 0L);
        sEntry.setMetadata(meta);
        return sEntry;
    }

    // ======================== getters/setters ========================

    public String getMemoryType() { return memoryType; }
    public void setMemoryType(String memoryType) { this.memoryType = memoryType; }

    public int getDefaultExptime() { return defaultExptime; }
    public void setDefaultExptime(int defaultExptime) { this.defaultExptime = defaultExptime; }
}
