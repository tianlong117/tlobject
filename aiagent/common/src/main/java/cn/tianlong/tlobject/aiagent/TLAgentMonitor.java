package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

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
 *
 * 创建日期：2026/7/14
 * 作者:tianlong
 */
public class TLAgentMonitor extends TLBaseModule implements TLAiAgentParamString {

    private static final class RunEntry {
        final String rootSessionId;
        final String agentName;
        volatile Thread thread; // doChat 所在线程，供 interrupt

        RunEntry(String rootSessionId, String agentName, Thread thread) {
            this.rootSessionId = rootSessionId;
            this.agentName = agentName;
            this.thread = thread;
        }
    }

    /** sessionId → 运行条目（ConcurrentHashMap，跨线程安全） */
    private final ConcurrentHashMap<String, RunEntry> runningAgents = new ConcurrentHashMap<>();

    public TLAgentMonitor() { super(); }
    public TLAgentMonitor(String name) { super(name); }
    public TLAgentMonitor(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected TLBaseModule init() { return this; }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case "register":
                return register(fromWho, msg);
            case "unregister":
                return unregister(fromWho, msg);
            case "stopByRoot":
                return stopByRoot(fromWho, msg);
            default:
                return null;
        }
    }

    /** doChat 入口登记 */
    private TLMsg register(Object fromWho, TLMsg msg) {
        String sid = msg.getStringParam(AI_P_SESSIONID, "");
        String rootSid = msg.getStringParam("rootSessionId", sid);
        String agentName = msg.getStringParam("agentName", "");
        Thread thread = Thread.currentThread();
        if (sid.isEmpty()) return null;

        runningAgents.put(sid, new RunEntry(rootSid, agentName, thread));
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
            putMsg(re.agentName, stopMsg);
        }

        return createMsg().setParam(RESULT, true).setParam("count", snapshot.size());
    }
}
