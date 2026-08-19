package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLDataBase;
import cn.tianlong.tlobject.modules.LogLevel;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 会话运行监控模块（注册中心模式）。
 *
 * 每个 agent 进入 doChat 时登记、退出时注销。
 * 控制台 stop / ESC 直接对本模块发 stopByRoot，由本模块级联停止
 * 该根会话下的所有 agent 执行实例（含子 agent、孙子 agent，任意深度）。
 *
 * 注册表按 sessionId 索引，天然支持同一 agent 实例服务多个并发会话而不互相干扰。
 *
 * 消息接口:
 *   register(sessionId, rootSessionId, agentName) — doChat 入口调用
 *   unregister(sessionId)                        — doChat finally 调用
 *   stopByRoot(rootSessionId)                    — 控制台/外部 触发级联停止
 *   recordStage(...)                             — 轮次环节打点（全链追踪）
 *   getLatestTrace(rootSessionId)                — 查询某会话最新一轮的环节记录
 *   recordTokenUsage(promptTokens, ...)          — 单次 LLM 调用用量上报（进程级累计）
 *   getProcessTokenUsage()                       — 查询进程级 token 总累计快照
 *
 * 全链追踪：agent/执行器/审批在各环节发 recordStage；本模块内存保留每个 rootSessionId
 * 的最新一轮记录（roundId 变化时清空重建）；enableTrace=true 时追加落盘
 * data/{userId}/traces/{rootSessionId}/{roundId}.jsonl（用户隔离）。
 *
 * 创建日期：2026/7/14
 * 作者:tianlong
 */
public class TLAgentMonitor extends TLBaseModule implements TLAiAgentParamString {

    private static final class RunEntry {
        final String rootSessionId;
        final String agentName;
        final Object instance;  // Agent 模块实例，stopByRoot 时直接发消息，避免走工厂查找
        volatile Thread thread; // doChat 所在线程，供 interrupt

        RunEntry(String rootSessionId, String agentName, Object instance, Thread thread) {
            this.rootSessionId = rootSessionId;
            this.agentName = agentName;
            this.instance = instance;
            this.thread = thread;
        }
    }

    /** 轮次环节记录（一行 = 一个环节） */
    public static class StageRecord {
        public long ts;
        public String agentName;
        public String sessionId;
        public String roundId;
        public String stage;
        public String detail;
        public long durationMs;
        /** 完整内容载荷（发送给 LLM 的 messages / LLM 返回 / 用户输入等，可读多行文本；不经过 sanitizeDetail 截断） */
        public String payload;

        StageRecord(long ts, String agentName, String sessionId, String roundId,
                    String stage, String detail, long durationMs, String payload) {
            this.ts = ts;
            this.agentName = agentName;
            this.sessionId = sessionId;
            this.roundId = roundId;
            this.stage = stage;
            this.detail = detail;
            this.durationMs = durationMs;
            this.payload = payload;
        }
    }

    /** sessionId → 运行条目（ConcurrentHashMap，跨线程安全） */
    private final ConcurrentHashMap<String, RunEntry> runningAgents = new ConcurrentHashMap<>();

    /** rootSessionId → 最新一轮的环节记录（roundId 变化时清空重建） */
    private final ConcurrentHashMap<String, LatestRound> latestRounds = new ConcurrentHashMap<>();

    /** 是否落盘 JSONL（agentMonitor 配置 enableTrace，默认 false——不落盘但内存保留最新轮） */
    private boolean enableTrace = false;

    // ======================== 进程级 Token 总累计（内存态，重启清零） ========================
    /** 本模块为工厂单例，以下计数器即进程级；AtomicLong 保证多 agent 多线程安全累加 */
    private final AtomicLong procPromptTokens = new AtomicLong();
    private final AtomicLong procCompletionTokens = new AtomicLong();
    private final AtomicLong procTotalTokens = new AtomicLong();
    private final AtomicLong procCacheCreationTokens = new AtomicLong();
    private final AtomicLong procCacheHitTokens = new AtomicLong();
    private final AtomicLong procCacheMissTokens = new AtomicLong();
    /** doChat 轮次（register 时 +1） */
    private final AtomicLong procChatRounds = new AtomicLong();
    /** 真实 LLM 调用次数（全零 usage 的意图缓存命中/mock 由发送方过滤，不计） */
    private final AtomicLong procLlmCalls = new AtomicLong();

    // ======================== 会话级 Token 统计（sessionId 维度，带 userId） ========================
    /** sessionId → 会话统计。工具型子 agent 与主 agent 共享 sessionId，天然并入；group 成员为派生 sid 各自成行 */
    private final ConcurrentHashMap<String, SessionStats> sessionStats = new ConcurrentHashMap<>();

    /** 会话级统计条目（AtomicLong：doChat 线程与流式回调线程并发累加安全） */
    public static class SessionStats {
        public volatile String userId = "";
        public volatile String rootSessionId = "";
        public final AtomicLong promptTokens = new AtomicLong();
        public final AtomicLong completionTokens = new AtomicLong();
        public final AtomicLong totalTokens = new AtomicLong();
        public final AtomicLong cacheCreationTokens = new AtomicLong();
        public final AtomicLong cacheHitTokens = new AtomicLong();
        public final AtomicLong cacheMissTokens = new AtomicLong();
        public final AtomicLong llmCalls = new AtomicLong();
        public final AtomicLong chatRounds = new AtomicLong();

        SessionStats(String userId, String rootSessionId) {
            this.userId = userId != null ? userId : "";
            this.rootSessionId = rootSessionId != null ? rootSessionId : "";
        }

        /** flush/查询用快照：{prompt, completion, total, cacheC, cacheH, cacheM, llmCalls, chatRounds} */
        long[] snapshot() {
            return new long[]{promptTokens.get(), completionTokens.get(), totalTokens.get(),
                    cacheCreationTokens.get(), cacheHitTokens.get(), cacheMissTokens.get(),
                    llmCalls.get(), chatRounds.get()};
        }
    }

    /** 会话级统计落库开关（agentMonitor 配置 persistTokenStats，默认 false——仅内存统计） */
    private boolean persistTokenStats = false;
    /** persistTokenStats=true 时 init 里获取的 ai_token_stats 表引用（拿不到则降级不落库） */
    private TLBaseModule tokenStatsTable;

    // ======================== 最后一轮 per-agent 用量（/stats agent，仅内存每轮覆盖） ========================
    /** 单个 agent 在一轮内的 token 用量 */
    public static class AgentRoundUsage {
        public volatile String agentName = "";
        public final AtomicLong promptTokens = new AtomicLong();
        public final AtomicLong completionTokens = new AtomicLong();
        public final AtomicLong totalTokens = new AtomicLong();
        public final AtomicLong cacheCreationTokens = new AtomicLong();
        public final AtomicLong cacheHitTokens = new AtomicLong();
        public final AtomicLong cacheMissTokens = new AtomicLong();
        public final AtomicLong llmCalls = new AtomicLong();
    }

    /** 单次 LLM 调用记录（/stats agent 逐次明细；按到达序 = 完成时间序） */
    public static class AgentCallRecord {
        public final long ts;
        public final String agentName;
        public final String sessionId;
        public final long promptTokens, completionTokens, totalTokens, cacheHitTokens, cacheMissTokens;

        AgentCallRecord(long ts, String agentName, String sessionId,
                        long p, long c, long t, long ch, long cm) {
            this.ts = ts;
            this.agentName = agentName != null ? agentName : "";
            this.sessionId = sessionId != null ? sessionId : "";
            this.promptTokens = p;
            this.completionTokens = c;
            this.totalTokens = t;
            this.cacheHitTokens = ch;
            this.cacheMissTokens = cm;
        }
    }

    /** 某根会话最后一轮的 per-agent 用量 + 逐次调用明细（仅内存，每轮覆盖） */
    private static class LastRoundUsage {
        volatile String roundId = "";
        volatile String userId = "";
        /** key = sessionId（派生 sid 区分成员；工具型子 agent 与主 agent 共享 sid） */
        final ConcurrentHashMap<String, AgentRoundUsage> agents = new ConcurrentHashMap<>();
        /** 逐次调用明细（到达序=完成时间序；clear/add 均在 synchronized(lr) 下，与轮切换重置原子） */
        final List<AgentCallRecord> calls = new ArrayList<>();
    }

    /** rootSessionId → 最后一轮 per-agent 用量（仅内存，重启清零） */
    private final ConcurrentHashMap<String, LastRoundUsage> lastRoundStats = new ConcurrentHashMap<>();

    /**
     * 最新一轮：同一轮（控制台发起对话到返回结果）全链共享一个 roundId（上游透传），
     * 记录按到达序追加；roundId 变化 = 新一轮开始 → 清空重建。
     */
    private static final class LatestRound {
        volatile String roundId;
        final List<StageRecord> stages = new ArrayList<>();

        synchronized void record(StageRecord rec) {
            if (!rec.roundId.equals(roundId)) {
                roundId = rec.roundId;
                stages.clear();
            }
            stages.add(rec);
        }

        synchronized List<StageRecord> snapshot() {
            return new ArrayList<>(stages);
        }

        synchronized String latestRoundId() {
            return roundId != null ? roundId : "";
        }
    }

    public TLAgentMonitor() { super(); }
    public TLAgentMonitor(String name) { super(name); }
    public TLAgentMonitor(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected TLBaseModule init() {
        if (persistTokenStats) {
            try {
                tokenStatsTable = TLDataBase.getTable("aiTokenStats", this);
                if (tokenStatsTable == null) {
                    putLog("init: aiTokenStats table not found, DB persist disabled", LogLevel.WARN);
                }
            } catch (Exception e) {
                tokenStatsTable = null;
                putLog("init: getTable(aiTokenStats) failed: " + e, LogLevel.WARN);
            }
        }
        return this;
    }

    @Override
    protected void setModuleParams() {
        if (params != null && params.get("enableTrace") != null) {
            enableTrace = "true".equals(params.get("enableTrace"));
        }
        if (params != null && params.get("persistTokenStats") != null) {
            persistTokenStats = "true".equals(params.get("persistTokenStats"));
        }
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case "register":
                return register(fromWho, msg);
            case "unregister":
                return unregister(fromWho, msg);
            case "stopByRoot":
                return stopByRoot(fromWho, msg);
            case "recordStage":
                return recordStage(fromWho, msg);
            case "getLatestTrace":
                return getLatestTrace(fromWho, msg);
            case MONITOR_RECORDTOKENUSAGE:
                return recordTokenUsage(fromWho, msg);
            case MONITOR_GETPROCESSTOKENUSAGE:
                return getProcessTokenUsage(fromWho, msg);
            case MONITOR_GETSESSIONTOKENUSAGE:
                return getSessionTokenUsage(fromWho, msg);
            case MONITOR_GETALLSESSIONTOKENUSAGE:
                return getAllSessionTokenUsage(fromWho, msg);
            case MONITOR_GETDBTOKENUSAGE:
                return getDbTokenUsage(fromWho, msg);
            case MONITOR_GETLASTROUNDAGENTUSAGE:
                return getLastRoundAgentUsage(fromWho, msg);
            default:
                return null;
        }
    }

    /** 轮次环节打点：内存保留最新轮 +（enableTrace 时）用户隔离落盘 JSONL */
    private TLMsg recordStage(Object fromWho, TLMsg msg) {
        String rootSid = msg.getStringParam("rootSessionId", "");
        String roundId = msg.getStringParam(AI_P_ROUNDID, "");
        if (rootSid.isEmpty() || roundId.isEmpty()) return null;

        StageRecord rec = new StageRecord(
                System.currentTimeMillis(),
                msg.getStringParam("agentName", ""),
                msg.getStringParam(AI_P_SESSIONID, ""),
                roundId,
                msg.getStringParam("stage", ""),
                msg.getStringParam("detail", ""),
                msg.getLongParam("durationMs", 0L),
                msg.getStringParam("payload", ""));

        latestRounds.computeIfAbsent(rootSid, k -> new LatestRound()).record(rec);

        if (enableTrace) {
            try {
                appendTraceFile(msg.getStringParam("userId", "default"), rootSid, roundId, rec);
            } catch (Exception e) {
                putLog("recordStage: write trace failed: " + e.toString(), LogLevel.WARN);
            }
        }
        return createMsg().setParam(RESULT, true);
    }

    /** 查询某会话最新一轮的环节记录 */
    private TLMsg getLatestTrace(Object fromWho, TLMsg msg) {
        String rootSid = msg.getStringParam("rootSessionId", "");
        LatestRound lr = rootSid.isEmpty() ? null : latestRounds.get(rootSid);
        List<StageRecord> stages = lr != null ? lr.snapshot() : new ArrayList<>();
        return createMsg().setParam(RESULT, true)
                .setParam("stages", stages)
                .setParam(AI_P_ROUNDID, lr != null ? lr.latestRoundId() : "");
    }

    /** 单次 LLM 调用用量上报（0-usage 响应由发送方过滤：意图缓存命中等非 LLM 调用不计） */
    private TLMsg recordTokenUsage(Object fromWho, TLMsg msg) {
        long p = msg.getLongParam(AI_P_PROMPTTOKENS, 0L);
        long c = msg.getLongParam(AI_P_COMPLETIONTOKENS, 0L);
        long t = msg.getLongParam(AI_P_TOTALTOKENS, 0L);
        long cc = msg.getLongParam(AI_P_CACHECREATIONTOKENS, 0L);
        long ch = msg.getLongParam(AI_P_CACHEHITTOKENS, 0L);
        long cm = msg.getLongParam(AI_P_CACHEMISSTOKENS, 0L);
        // 进程级累计（所有会话合计）
        procPromptTokens.addAndGet(p);
        procCompletionTokens.addAndGet(c);
        procTotalTokens.addAndGet(t);
        procCacheCreationTokens.addAndGet(cc);
        procCacheHitTokens.addAndGet(ch);
        procCacheMissTokens.addAndGet(cm);
        procLlmCalls.incrementAndGet();
        // 会话级累计（sid 为空只记进程级）
        String sid = msg.getStringParam(AI_P_SESSIONID, "");
        if (sid.isEmpty()) return null;
        SessionStats ss = sessionStats.computeIfAbsent(sid,
                k -> new SessionStats(msg.getStringParam(AI_P_USERID, ""),
                        msg.getStringParam(AI_P_ROOTSESSIONID, sid)));
        ss.promptTokens.addAndGet(p);
        ss.completionTokens.addAndGet(c);
        ss.totalTokens.addAndGet(t);
        ss.cacheCreationTokens.addAndGet(cc);
        ss.cacheHitTokens.addAndGet(ch);
        ss.cacheMissTokens.addAndGet(cm);
        ss.llmCalls.incrementAndGet();

        // 最后一轮 per-agent 用量（轮次信号来自 latestRounds——recordStage 已带 roundId 且先于 LLM 循环到达）
        String root = msg.getStringParam(AI_P_ROOTSESSIONID, sid);
        LastRoundUsage lr = lastRoundStats.computeIfAbsent(root, k -> new LastRoundUsage());
        String curRound = currentRoundId(root);
        if (curRound != null && !curRound.isEmpty() && !curRound.equals(lr.roundId)) {
            synchronized (lr) {
                if (!curRound.equals(lr.roundId)) {
                    lr.roundId = curRound;
                    lr.agents.clear();
                    lr.calls.clear();
                }
            }
        }
        AgentRoundUsage au = lr.agents.computeIfAbsent(sid, k -> new AgentRoundUsage());
        RunEntry re = runningAgents.get(sid);
        if (re != null && re.agentName != null && !re.agentName.isEmpty()) {
            au.agentName = re.agentName;
        } else if (au.agentName.isEmpty()) {
            int ci = sid.indexOf(':');
            au.agentName = ci > 0 ? sid.substring(0, ci) : sid;
        }
        au.promptTokens.addAndGet(p);
        au.completionTokens.addAndGet(c);
        au.totalTokens.addAndGet(t);
        au.cacheCreationTokens.addAndGet(cc);
        au.cacheHitTokens.addAndGet(ch);
        au.cacheMissTokens.addAndGet(cm);
        au.llmCalls.incrementAndGet();
        // 逐次调用明细（与轮切换重置共用 lr 锁：clear/add 原子，防并行成员竞态）
        synchronized (lr) {
            lr.calls.add(new AgentCallRecord(System.currentTimeMillis(), au.agentName, sid, p, c, t, ch, cm));
        }
        String uid = msg.getStringParam(AI_P_USERID, "");
        if (!uid.isEmpty()) lr.userId = uid;
        return null;
    }

    /** latestRounds 里的当前轮次（recordStage 维护；无记录返回 null） */
    private String currentRoundId(String rootSid) {
        LatestRound lr = latestRounds.get(rootSid);
        return lr != null ? lr.latestRoundId() : null;
    }

    /** 查询当前会话最后一轮的 per-agent 用量（用户隔离；最新轮零调用时 stale=true） */
    private TLMsg getLastRoundAgentUsage(Object fromWho, TLMsg msg) {
        String root = msg.getStringParam(AI_P_ROOTSESSIONID, msg.getStringParam(AI_P_SESSIONID, ""));
        String userId = msg.getStringParam(AI_P_USERID, "");
        TLMsg r = createMsg().setParam(RESULT, true);
        if (root.isEmpty()) return r.setParam("found", false);
        LastRoundUsage lr = lastRoundStats.get(root);
        if (lr == null) return r.setParam("found", false);
        // 用户隔离：只能看自己的轮次数据
        if (!userId.isEmpty() && !lr.userId.isEmpty() && !userId.equals(lr.userId)) {
            return r.setParam("found", false);
        }
        // 最新轮已开始但无任何 LLM 上报（如零调用轮）→ stale 提示
        String curRound = currentRoundId(root);
        boolean stale = curRound != null && !curRound.isEmpty() && !curRound.equals(lr.roundId);
        r.setParam("found", true).setParam(AI_P_ROUNDID, lr.roundId).setParam("stale", stale);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, AgentRoundUsage> e : lr.agents.entrySet()) {
            AgentRoundUsage au = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentName", au.agentName);
            row.put(AI_P_SESSIONID, e.getKey());
            row.put(AI_P_PROMPTTOKENS, au.promptTokens.get());
            row.put(AI_P_COMPLETIONTOKENS, au.completionTokens.get());
            row.put(AI_P_TOTALTOKENS, au.totalTokens.get());
            row.put(AI_P_CACHEHITTOKENS, au.cacheHitTokens.get());
            row.put(AI_P_CACHEMISSTOKENS, au.cacheMissTokens.get());
            row.put(AI_P_LLMCALLS, au.llmCalls.get());
            list.add(row);
        }
        // 逐次调用明细（到达序 = 完成时间序）+ 本轮汇总（行内显示用：整轮全部 agent 的用量）
        List<Map<String, Object>> calls = new ArrayList<>();
        long rp = 0, rc = 0, rt = 0, rch = 0, rcm = 0;
        synchronized (lr) {
            for (AgentCallRecord cr : lr.calls) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ts", cr.ts);
                row.put("agentName", cr.agentName);
                row.put(AI_P_SESSIONID, cr.sessionId);
                row.put(AI_P_PROMPTTOKENS, cr.promptTokens);
                row.put(AI_P_COMPLETIONTOKENS, cr.completionTokens);
                row.put(AI_P_TOTALTOKENS, cr.totalTokens);
                row.put(AI_P_CACHEHITTOKENS, cr.cacheHitTokens);
                row.put(AI_P_CACHEMISSTOKENS, cr.cacheMissTokens);
                calls.add(row);
                rp += cr.promptTokens; rc += cr.completionTokens; rt += cr.totalTokens;
                rch += cr.cacheHitTokens; rcm += cr.cacheMissTokens;
            }
        }
        return r.setParam("agents", list).setParam("calls", calls)
                .setParam(AI_P_PROMPTTOKENS, rp)
                .setParam(AI_P_COMPLETIONTOKENS, rc)
                .setParam(AI_P_TOTALTOKENS, rt)
                .setParam(AI_P_CACHEHITTOKENS, rch)
                .setParam(AI_P_CACHEMISSTOKENS, rcm);
    }

    /** 进程级 token 总累计快照查询 */
    private TLMsg getProcessTokenUsage(Object fromWho, TLMsg msg) {
        return createMsg().setParam(RESULT, true)
                .setParam(AI_P_PROMPTTOKENS_PROCESS, procPromptTokens.get())
                .setParam(AI_P_COMPLETIONTOKENS_PROCESS, procCompletionTokens.get())
                .setParam(AI_P_TOTALTOKENS_PROCESS, procTotalTokens.get())
                .setParam(AI_P_CACHECREATIONTOKENS_PROCESS, procCacheCreationTokens.get())
                .setParam(AI_P_CACHEHITTOKENS_PROCESS, procCacheHitTokens.get())
                .setParam(AI_P_CACHEMISSTOKENS_PROCESS, procCacheMissTokens.get())
                .setParam(AI_P_CHATROUNDS, procChatRounds.get())
                .setParam(AI_P_LLMCALLS, procLlmCalls.get());
    }

    /** 查询指定会话的 token 统计（**按根会话聚合**：主 agent + workflow/group 成员全部计入，与 /stats all、DB 落户口径一致；无记录时 found=false） */
    private TLMsg getSessionTokenUsage(Object fromWho, TLMsg msg) {
        String sid = msg.getStringParam(AI_P_SESSIONID, "");
        TLMsg r = createMsg().setParam(RESULT, true)
                .setParam("found", false)
                .setParam(AI_P_SESSIONID, sid);
        if (sid.isEmpty()) return r;
        long p = 0, c = 0, t = 0, cc = 0, ch = 0, cm = 0, calls = 0, rounds = 0;
        String userId = "";
        boolean found = false;
        for (Map.Entry<String, SessionStats> e : sessionStats.entrySet()) {
            SessionStats ss = e.getValue();
            String root = (ss.rootSessionId != null && !ss.rootSessionId.isEmpty()) ? ss.rootSessionId : e.getKey();
            if (!sid.equals(root)) continue;
            found = true;
            long[] v = ss.snapshot();
            p += v[0]; c += v[1]; t += v[2]; cc += v[3]; ch += v[4]; cm += v[5]; calls += v[6]; rounds += v[7];
            // userId：主会话 entry（key==sid）优先，其次任意非空
            if (e.getKey().equals(sid) && !ss.userId.isEmpty()) userId = ss.userId;
            else if (userId.isEmpty() && !ss.userId.isEmpty()) userId = ss.userId;
        }
        if (!found) return r;
        return r.setParam("found", true)
                .setParam(AI_P_USERID, userId)
                .setParam(AI_P_ROOTSESSIONID, sid)
                .setParam(AI_P_PROMPTTOKENS, p)
                .setParam(AI_P_COMPLETIONTOKENS, c)
                .setParam(AI_P_TOTALTOKENS, t)
                .setParam(AI_P_CACHECREATIONTOKENS, cc)
                .setParam(AI_P_CACHEHITTOKENS, ch)
                .setParam(AI_P_CACHEMISSTOKENS, cm)
                .setParam(AI_P_LLMCALLS, calls)
                .setParam(AI_P_CHATROUNDS, rounds);
    }

    /** 内存中会话的 token 明细（/stats all 数据源；按 rootSessionId 聚合每会话一行——主 agent+workflow/group 成员合并，
     *  per-agent 明细见 getLastRoundAgentUsage；带 userId 参数时只列该用户，空=不过滤；重启清零） */
    private TLMsg getAllSessionTokenUsage(Object fromWho, TLMsg msg) {
        String userId = msg.getStringParam(AI_P_USERID, "");
        Map<String, Map<String, Object>> byRoot = new LinkedHashMap<>();
        for (Map.Entry<String, SessionStats> e : sessionStats.entrySet()) {
            SessionStats ss = e.getValue();
            if (!userId.isEmpty() && !userId.equals(ss.userId)) continue;
            String root = (ss.rootSessionId != null && !ss.rootSessionId.isEmpty()) ? ss.rootSessionId : e.getKey();
            long[] v = ss.snapshot();
            Map<String, Object> row = byRoot.computeIfAbsent(root, k -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put(AI_P_SESSIONID, root);
                r.put(AI_P_USERID, "");
                r.put(AI_P_ROOTSESSIONID, root);
                r.put(AI_P_PROMPTTOKENS, 0L);
                r.put(AI_P_COMPLETIONTOKENS, 0L);
                r.put(AI_P_TOTALTOKENS, 0L);
                r.put(AI_P_CACHEHITTOKENS, 0L);
                r.put(AI_P_CACHEMISSTOKENS, 0L);
                r.put(AI_P_LLMCALLS, 0L);
                r.put(AI_P_CHATROUNDS, 0L);
                return r;
            });
            row.put(AI_P_PROMPTTOKENS, num(row.get(AI_P_PROMPTTOKENS)) + v[0]);
            row.put(AI_P_COMPLETIONTOKENS, num(row.get(AI_P_COMPLETIONTOKENS)) + v[1]);
            row.put(AI_P_TOTALTOKENS, num(row.get(AI_P_TOTALTOKENS)) + v[2]);
            row.put(AI_P_CACHEHITTOKENS, num(row.get(AI_P_CACHEHITTOKENS)) + v[4]);
            row.put(AI_P_CACHEMISSTOKENS, num(row.get(AI_P_CACHEMISSTOKENS)) + v[5]);
            row.put(AI_P_LLMCALLS, num(row.get(AI_P_LLMCALLS)) + v[6]);
            row.put(AI_P_CHATROUNDS, num(row.get(AI_P_CHATROUNDS)) + v[7]);
            // userId：主会话 entry（key==root）优先，其次任意非空
            if (e.getKey().equals(root) && !ss.userId.isEmpty()) {
                row.put(AI_P_USERID, ss.userId);
            } else if ("".equals(String.valueOf(row.get(AI_P_USERID))) && !ss.userId.isEmpty()) {
                row.put(AI_P_USERID, ss.userId);
            }
        }
        return createMsg().setParam(RESULT, true).setParam("sessions", new ArrayList<>(byRoot.values()));
    }

    private static long num(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    /** DB 历史合计（跨重启，带 userId 参数时按用户过滤；persist 开关关/表缺失时返回 notEnabled 标志） */
    private TLMsg getDbTokenUsage(Object fromWho, TLMsg msg) {
        if (!persistTokenStats || tokenStatsTable == null) {
            return createMsg().setParam(RESULT, true).setParam(AI_P_NOTENABLED, true);
        }
        try {
            String userId = msg.getStringParam(AI_P_USERID, "");
            String sql = "select coalesce(sum(prompt_tokens),0) p, "
                    + "coalesce(sum(completion_tokens),0) c, coalesce(sum(total_tokens),0) t, "
                    + "coalesce(sum(cache_creation_tokens),0) cc, coalesce(sum(cache_hit_tokens),0) ch, "
                    + "coalesce(sum(cache_miss_tokens),0) cm, count(*) n from [table]";
            TLMsg qm = createMsg().setAction(DB_QUERY).setParam(DB_P_SQL, sql);
            if (!userId.isEmpty()) {
                LinkedHashMap<String, Object> p = new LinkedHashMap<>();
                p.put("user_id", userId);
                qm.setParam(DB_P_PARAMS, p)
                  .setParam(DB_P_SQL, sql + " where user_id=?");
            }
            TLMsg q = putMsg(tokenStatsTable, qm);
            Map<String, Object> row = firstRowOf(q);
            return createMsg().setParam(RESULT, true)
                    .setParam("dbPromptTokens", row == null ? 0L : toLong(row.get("p")))
                    .setParam("dbCompletionTokens", row == null ? 0L : toLong(row.get("c")))
                    .setParam("dbTotalTokens", row == null ? 0L : toLong(row.get("t")))
                    .setParam("dbCacheCreationTokens", row == null ? 0L : toLong(row.get("cc")))
                    .setParam("dbCacheHitTokens", row == null ? 0L : toLong(row.get("ch")))
                    .setParam("dbCacheMissTokens", row == null ? 0L : toLong(row.get("cm")))
                    .setParam("dbSessionCount", row == null ? 0L : toLong(row.get("n")));
        } catch (Exception e) {
            putLog("getDbTokenUsage failed: " + e, LogLevel.WARN);
            return createMsg().setParam(RESULT, true).setParam(AI_P_NOTENABLED, true);
        }
    }

    /** DB_QUERY 结果取首行（兼容 List<Map> / 单 Map 两种返回形态，照 TLDatabaseSessionManager.getResultList） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> firstRowOf(TLMsg q) {
        if (q == null) return null;
        // 注意不能写 getParam(key, null)——null 字面量会命中 getParam(String, Class) 重载导致 NPE
        Object r = q.containsParam(DB_R_RESULT) ? q.getParam(DB_R_RESULT) : null;
        if (r instanceof List) {
            List<?> l = (List<?>) r;
            return l.isEmpty() ? null : (Map<String, Object>) l.get(0);
        }
        return r instanceof Map ? (Map<String, Object>) r : null;
    }

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return 0L; }
    }

    /**
     * 每轮结束把该根会话的累计 upsert 到 ai_token_stats（UPDATE + INSERT OR IGNORE 双发，幂等；同步写不丢数据）。
     * 落库按 rootSessionId 合并：主 agent + workflow 节点 + group 成员（派生 sid）汇总为一行——每会话一行，防行数膨胀。
     */
    private void flushSessionToDb(String sid) {
        if (!persistTokenStats || tokenStatsTable == null) return;
        SessionStats ss = sessionStats.get(sid);
        if (ss == null) return;
        String root = (ss.rootSessionId != null && !ss.rootSessionId.isEmpty()) ? ss.rootSessionId : sid;
        try {
            // 汇总该根会话下全部 entry（成员可能先于主 agent flush，聚合保证每次写全量当前值，幂等）
            long p = 0, c = 0, t = 0, cc = 0, ch = 0, cm = 0, calls = 0, rounds = 0;
            String userId = "";
            for (Map.Entry<String, SessionStats> e : sessionStats.entrySet()) {
                SessionStats s = e.getValue();
                if (!root.equals(s.rootSessionId)) continue;
                long[] v = s.snapshot();
                p += v[0]; c += v[1]; t += v[2]; cc += v[3]; ch += v[4]; cm += v[5]; calls += v[6]; rounds += v[7];
                // 优先取主会话 entry（key==root）的 userId，其次任意非空值
                if (!s.userId.isEmpty() && (userId.isEmpty() || e.getKey().equals(root))) userId = s.userId;
            }
            if (userId.isEmpty()) userId = ss.userId;
            if (calls == 0 && rounds == 0) return; // 无任何 LLM 调用的会话不落库（仅 register 未产生消耗）
            long now = System.currentTimeMillis();
            LinkedHashMap<String, Object> up = new LinkedHashMap<>();
            up.put("user_id", userId);
            up.put("prompt_tokens", p);
            up.put("completion_tokens", c);
            up.put("total_tokens", t);
            up.put("cache_creation_tokens", cc);
            up.put("cache_hit_tokens", ch);
            up.put("cache_miss_tokens", cm);
            up.put("llm_calls", calls);
            up.put("chat_rounds", rounds);
            up.put("update_time", now);
            up.put("__sid", root);
            putMsg(tokenStatsTable, createMsg().setAction(DB_UPDATE)
                    .setParam(DB_P_SQL, "update [table] set user_id=?,"
                            + "prompt_tokens=?,completion_tokens=?,total_tokens=?,"
                            + "cache_creation_tokens=?,cache_hit_tokens=?,cache_miss_tokens=?,"
                            + "llm_calls=?,chat_rounds=?,update_time=? where session_id=?")
                    .setParam(DB_P_PARAMS, up));

            LinkedHashMap<String, Object> in = new LinkedHashMap<>();
            in.put("session_id", root);
            in.put("user_id", userId);
            in.put("prompt_tokens", p);
            in.put("completion_tokens", c);
            in.put("total_tokens", t);
            in.put("cache_creation_tokens", cc);
            in.put("cache_hit_tokens", ch);
            in.put("cache_miss_tokens", cm);
            in.put("llm_calls", calls);
            in.put("chat_rounds", rounds);
            in.put("update_time", now);
            putMsg(tokenStatsTable, createMsg().setAction(DB_INSERT)
                    .setParam(DB_P_SQL, "insert or ignore into [table] "
                            + "(session_id,user_id,prompt_tokens,completion_tokens,total_tokens,"
                            + "cache_creation_tokens,cache_hit_tokens,cache_miss_tokens,llm_calls,chat_rounds,update_time) "
                            + "values (?,?,?,?,?,?,?,?,?,?,?)")
                    .setParam(DB_P_PARAMS, in));
        } catch (Exception e) {
            putLog("flushSessionToDb failed for " + sid + ": " + e, LogLevel.WARN);
        }
    }

    /**
     * detail 净化（登记/打点发送方调用）：压平成单行 + 截断到 64 字符。
     * 多行任务文本/监理反馈等长内容在发送时即裁剪，不进消息、不进记录；
     * 整行（时间+agentName+stage+detail）控制在常见终端 120 列内，避免 /trace 折行
     * （完整任务文本在 SessionManager 轮记录 / enableTrace JSONL 可查）。
     */
    public static String sanitizeDetail(String detail) {
        if (detail == null || detail.isEmpty()) return "";
        String s = detail.replace("\r", " ").replace("\n", " ");
        return s.length() > 64 ? s.substring(0, 64) + "..." : s;
    }

    /** 追加一行 JSONL 到 data/{userId}/traces/{rootSessionId}/{roundId}.jsonl（用户隔离） */
    private void appendTraceFile(String userId, String rootSid, String roundId, StageRecord rec)
            throws java.io.IOException {
        String dir = "./data/" + safe(userId) + "/traces/" + safe(rootSid) + "/";
        File f = new File(dir);
        if (!f.exists() && !f.mkdirs()) {
            putLog("recordStage: cannot create trace dir " + dir, LogLevel.WARN);
            return;
        }
        String line = "{\"ts\":" + rec.ts
                + ",\"agentName\":\"" + esc(rec.agentName)
                + "\",\"sessionId\":\"" + esc(rec.sessionId)
                + "\",\"roundId\":\"" + esc(rec.roundId)
                + "\",\"stage\":\"" + esc(rec.stage)
                + "\",\"detail\":\"" + esc(rec.detail)
                + "\",\"durationMs\":" + rec.durationMs
                + ",\"payload\":\"" + escJson(rec.payload) + "\"}\n";
        try (FileWriter fw = new FileWriter(dir + safe(roundId) + ".jsonl", true)) {
            fw.write(line);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    /** JSONL 专用转义：保留换行结构（\n → \\n 字面序列），使 payload 多行文本仍是合法单行 JSON */
    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /** doChat 入口登记 */
    private TLMsg register(Object fromWho, TLMsg msg) {
        String sid = msg.getStringParam(AI_P_SESSIONID, "");
        String rootSid = msg.getStringParam("rootSessionId", sid);
        String agentName = msg.getStringParam("agentName", "");
        String userId = msg.getStringParam(AI_P_USERID, "");
        Thread thread = Thread.currentThread();
        if (sid.isEmpty()) return null;

        runningAgents.put(sid, new RunEntry(rootSid, agentName, fromWho, thread));
        procChatRounds.incrementAndGet();
        SessionStats ss = sessionStats.computeIfAbsent(sid, k -> new SessionStats(userId, rootSid));
        if (userId != null && !userId.isEmpty()) ss.userId = userId;
        ss.rootSessionId = rootSid;
        ss.chatRounds.incrementAndGet();
        putLog("Agent registered: sid=" + sid + " root=" + rootSid
                + " agent=" + agentName, LogLevel.DEBUG);
        return null;
    }

    /** doChat finally 注销（每轮结束：同步把该会话累计 upsert 到 DB，persist 开关关闭时仅内存） */
    private TLMsg unregister(Object fromWho, TLMsg msg) {
        String sid = msg.getStringParam(AI_P_SESSIONID, "");
        if (sid.isEmpty()) return null;

        RunEntry removed = runningAgents.remove(sid);
        if (removed != null) {
            putLog("Agent unregistered: sid=" + sid + " agent=" + removed.agentName, LogLevel.DEBUG);
        }
        // 主/子 agent 共享 sid 时 removed 可能已被先注销者摘掉——flush 不依赖 removed，保证最终值落库
        flushSessionToDb(sid);
        return null;
    }

    /**
     * 级联停止：找到 rootSessionId 匹配的所有条目，逐个通知 agent 的 stopChat。
     * 先 interrupt 线程（快），再发 stopChat(cascade=true)（设 cancelFlags，跳过 cancelLlm）。
     */
    @SuppressWarnings("unchecked")
    private TLMsg stopByRoot(Object fromWho, TLMsg msg) {
        String rootSid = msg.getStringParam("rootSessionId", "");
        if (rootSid.isEmpty()) {
            putLog("stopByRoot: empty rootSessionId", LogLevel.WARN);
            return createMsg().setParam(RESULT, false).setParam("error", "empty rootSessionId");
        }

        // 快照当前匹配的条目（遍历期间可能有注册/注销并发，取快照）
        List<java.util.Map.Entry<String, RunEntry>> snapshot = new ArrayList<>();
        for (java.util.Map.Entry<String, RunEntry> e : runningAgents.entrySet()) {
            if (rootSid.equals(e.getValue().rootSessionId)) {
                snapshot.add(e);
            }
        }

        putLog("stopByRoot: root=" + rootSid + " matched=" + snapshot.size(), LogLevel.INFO);

        for (java.util.Map.Entry<String, RunEntry> e : snapshot) {
            String sid = e.getKey();
            RunEntry re = e.getValue();
            // 先快速中断线程（不依赖消息投递时序）
            if (re.thread != null && re.thread.isAlive()) {
                re.thread.interrupt();
            }
            // 再通过消息让 agent 自身的 stopChat 处理 cancelFlags
            TLMsg stopMsg = createMsg().setAction(AGENT_STOPCHAT)
                    .setParam(AI_P_SESSIONID, sid)
                    .setParam("cascade", true);
            if (re.instance instanceof IObject) {
                putMsg((IObject) re.instance, stopMsg);
            } else {
                putLog("stopByRoot: instance is null for agent=" + re.agentName, LogLevel.WARN);
            }
        }

        return createMsg().setParam(RESULT, true).setParam("count", snapshot.size());
    }
}
