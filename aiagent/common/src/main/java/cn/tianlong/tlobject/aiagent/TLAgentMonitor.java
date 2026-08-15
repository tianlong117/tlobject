package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

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

        StageRecord(long ts, String agentName, String sessionId, String roundId,
                    String stage, String detail, long durationMs) {
            this.ts = ts;
            this.agentName = agentName;
            this.sessionId = sessionId;
            this.roundId = roundId;
            this.stage = stage;
            this.detail = detail;
            this.durationMs = durationMs;
        }
    }

    /** sessionId → 运行条目（ConcurrentHashMap，跨线程安全） */
    private final ConcurrentHashMap<String, RunEntry> runningAgents = new ConcurrentHashMap<>();

    /** rootSessionId → 最新一轮的环节记录（roundId 变化时清空重建） */
    private final ConcurrentHashMap<String, LatestRound> latestRounds = new ConcurrentHashMap<>();

    /** 是否落盘 JSONL（agentMonitor 配置 enableTrace，默认 false——不落盘但内存保留最新轮） */
    private boolean enableTrace = false;

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
    protected TLBaseModule init() { return this; }

    @Override
    protected void setModuleParams() {
        if (params != null && params.get("enableTrace") != null) {
            enableTrace = "true".equals(params.get("enableTrace"));
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
                msg.getLongParam("durationMs", 0L));

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

    /**
     * detail 净化（登记/打点发送方调用）：压平成单行 + 截断到 200 字符。
     * 多行任务文本/监理反馈等长内容在发送时即裁剪，不进消息、不进记录。
     */
    public static String sanitizeDetail(String detail) {
        if (detail == null || detail.isEmpty()) return "";
        String s = detail.replace("\r", " ").replace("\n", " ");
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
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
                + "\",\"durationMs\":" + rec.durationMs + "}\n";
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

    /** doChat 入口登记 */
    private TLMsg register(Object fromWho, TLMsg msg) {
        String sid = msg.getStringParam(AI_P_SESSIONID, "");
        String rootSid = msg.getStringParam("rootSessionId", sid);
        String agentName = msg.getStringParam("agentName", "");
        Thread thread = Thread.currentThread();
        if (sid.isEmpty()) return null;

        runningAgents.put(sid, new RunEntry(rootSid, agentName, fromWho, thread));
        putLog("Agent registered: sid=" + sid + " root=" + rootSid
                + " agent=" + agentName, LogLevel.DEBUG);
        return null;
    }

    /** doChat finally 注销 */
    private TLMsg unregister(Object fromWho, TLMsg msg) {
        String sid = msg.getStringParam(AI_P_SESSIONID, "");
        if (sid.isEmpty()) return null;

        RunEntry removed = runningAgents.remove(sid);
        if (removed != null) {
            putLog("Agent unregistered: sid=" + sid + " agent=" + removed.agentName, LogLevel.DEBUG);
        }
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
