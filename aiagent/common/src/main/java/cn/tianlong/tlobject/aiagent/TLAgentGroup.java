package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Group Agent 模块：把一组成员 agent 当作一个子 agent 对外提供服务。
 * 对 master 而言就是一个普通子 agent（进 subAgents，接收 AGENT_CHAT），
 * 内部按 mode 调度成员：
 * - sequential: 串行，前一步输出 → 下一步输入
 * - parallel:   并发，putMsgGroupByThread 一发全发，等齐合并结果
 *
 * 成员是本 group 的私有实例：master 创建 group 后，通过 AGENT_REGISTERAGENT
 * 消息逐个注入成员 cfg（AI_P_AGENTNAME + AI_P_AGENTCONFIG，与 TLAiAgent.registerAgent
 * 同一契约），group 用 getMyModule 创建并存入本地 modules map——
 * sequential 的 getModule(mName) 与 parallel 的按名 destination 解析都命中私有成员。
 *
 * 无 LLM、无 context、无自己的 config 文件（TLBaseModule 生命周期对 mconfig=null 安全），
 * 参考 TLMcpAgent 的"无 LLM 子 agent"模式。
 *
 * 创建日期：2026/7/16
 * 作者:tianlong
 */
public class TLAgentGroup extends TLBaseModule implements TLAiAgentParamString {

    /** 成员名列表（params "members" 分号分隔） */
    protected String[] memberNames;

    /** 调度模式：sequential | parallel */
    protected String mode = "sequential";

    /** parallel 模式等待超时（毫秒） */
    protected int waitTime = 120000;

    public TLAgentGroup() { super(); }
    public TLAgentGroup(String name) { super(name); }
    public TLAgentGroup(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            String membersStr = params.get("members");
            if (membersStr != null && !membersStr.isEmpty())
                memberNames = membersStr.split(";");
            if (params.get("mode") != null)
                mode = params.get("mode");
            if (params.get("waitTime") != null) {
                try { waitTime = Integer.parseInt(params.get("waitTime")); }
                catch (NumberFormatException ignored) {}
            }
        }
        if (memberNames == null || memberNames.length == 0)
            putLog("Group " + name + " has no members", LogLevel.WARN);
    }

    @Override
    protected TLBaseModule init() {
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case AGENT_CHAT:
                returnMsg = chat(fromWho, msg);
                break;
            case AGENT_REGISTERAGENT:
                returnMsg = registerMember(fromWho, msg);
                break;
            case AGENT_STOPCHAT:
                returnMsg = stopChat(fromWho, msg);
                break;
            default:
                returnMsg = null;
        }
        return returnMsg;
    }

    /**
     * 停止级联：组收到 stopChat 后按成员 sessionId 派生规则（{group}_{member}:{sid}）
     * 转发给每个成员。成员 doChat 期间也会向 agentMonitor 自注册被 stopByRoot 直接停到，
     * 此处转发补的是间隙（成员尚未进入/已退出 doChat 时），重复停止无害（置标志+interrupt 幂等）。
     */
    protected TLMsg stopChat(Object fromWho, TLMsg msg) {
        String sid = msg.getStringParam(AI_P_SESSIONID, "default");
        if (memberNames != null) {
            for (String mName : memberNames) {
                mName = mName.trim();
                if (mName.isEmpty()) continue;
                TLBaseModule member = (TLBaseModule) getModule(mName);
                if (member == null) continue;
                TLMsg stopMsg = createMsg().setAction(AGENT_STOPCHAT)
                        .setParam(AI_P_SESSIONID, name + "_" + mName + ":" + sid)
                        .setParam("cascade", true);
                putMsg(member, stopMsg);
            }
        }
        putLog("Group stopChat forwarded to members: sessionId=" + sid, LogLevel.INFO);
        return createMsg().setParam(RESULT, true).setParam(AI_P_SESSIONID, sid);
    }

    /**
     * 注册成员：cfg 注入 modulesClass/modulesParams 后 getMyModule 创建私有实例。
     * 消息契约与 TLAiAgent.registerAgent 一致：AI_P_AGENTNAME + AI_P_AGENTCONFIG。
     */
    protected synchronized TLMsg registerMember(Object fromWho, TLMsg msg) {
        String agentName = msg.getStringParam(AI_P_AGENTNAME, "");
        if (agentName.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "agentName required");
        }
        HashMap<String, String> cfg = new HashMap<>(msg.getMapParam(AI_P_AGENTCONFIG, new HashMap<>()));
        try {
            if (modulesClass == null) modulesClass = new HashMap<>();
            if (modulesParams == null) modulesParams = new HashMap<>();
            modulesClass.put(agentName, cfg);
            modulesParams.put(agentName, cfg);
            TLBaseModule module = (TLBaseModule) getMyModule(agentName);
            if (module == null) {
                modulesClass.remove(agentName);
                modulesParams.remove(agentName);
                return createMsg().setParam(RESULT, false).setParam("error", "create failed: " + agentName);
            }
            putLog("Group member registered: " + agentName + " (group " + name + ")", LogLevel.DEBUG);
            return createMsg().setParam(RESULT, true).setParam(AI_P_AGENTNAME, agentName);
        } catch (Exception e) {
            putLog("Failed to register group member: " + agentName + " error: " + e.toString(), LogLevel.ERROR);
            return createMsg().setParam(RESULT, false).setParam("error", "register failed: " + e);
        }
    }

    /**
     * 子 agent chat 契约：收 AI_P_USERMESSAGE，按 mode 调度成员，
     * 返回 RESULT + AI_P_RESPONSE（master 委托路径读取 AI_P_RESPONSE）。
     * 向 agentMonitor 自注册：stopByRoot 级联时能找到组本身，
     * 组的 stopChat 再转发给成员（补成员未进入 doChat 时的间隙）。
     */
    protected TLMsg chat(Object fromWho, TLMsg msg) {
        if (memberNames == null || memberNames.length == 0) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, "Error: group " + name + " has no members");
        }
        String task = msg.getStringParam(AI_P_USERMESSAGE, "");
        String baseSession = msg.getStringParam(AI_P_SESSIONID, String.valueOf(System.currentTimeMillis()));
        String rootSessionId = msg.getStringParam("rootSessionId", baseSession);

        System.out.println(">>> [Group " + name + "] mode=" + mode + " members=" + String.join(";", memberNames));
        System.out.println("    任务: " + task);

        // 向监控模块登记（与 TLAiAgent.doChat 同契约），finally 注销
        putMsg(M_AGENTMONITOR, createMsg().setAction("register")
                .setParam(AI_P_SESSIONID, baseSession)
                .setParam("rootSessionId", rootSessionId)
                .setParam("agentName", getName()));
        try {
            if ("parallel".equals(mode)) {
                return chatParallel(task, baseSession, rootSessionId);
            }
            return chatSequential(task, baseSession, rootSessionId);
        } finally {
            putMsg(M_AGENTMONITOR, createMsg().setAction("unregister")
                    .setParam(AI_P_SESSIONID, baseSession));
        }
    }

    protected TLMsg chatSequential(String task, String baseSession, String rootSessionId) {
        String currentInput = task;
        TLMsg lastResult = null;
        for (String mName : memberNames) {
            mName = mName.trim();
            if (mName.isEmpty()) continue;
            TLBaseModule member = (TLBaseModule) getModule(mName);
            if (member == null) {
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_RESPONSE, "Group member not found: " + mName);
            }
            String sid = name + "_" + mName + ":" + baseSession;
            System.out.println("  → [Group-seq] " + mName);
            TLMsg result = putMsg(member, createMsg().setAction(AGENT_CHAT)
                    .setParam(AI_P_USERMESSAGE, currentInput).setParam(AI_P_SESSIONID, sid)
                    .setParam("rootSessionId", rootSessionId));
            if (result == null || !result.parseBoolean(RESULT, false)) {
                String err = result != null ? result.getStringParam(AI_P_RESPONSE, "unknown") : "no response";
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_RESPONSE, "Group step [" + mName + "] failed: " + err);
            }
            lastResult = result;
            currentInput = result.getStringParam(AI_P_RESPONSE, currentInput);
            System.out.println("  ✓ [Group-seq] " + mName + " done");
        }
        return createMsg().setParam(RESULT, true)
                .setParam(AI_P_RESPONSE, lastResult != null ? lastResult.getStringParam(AI_P_RESPONSE, "") : "");
    }

    @SuppressWarnings("unchecked")
    protected TLMsg chatParallel(String task, String baseSession, String rootSessionId) {
        List<TLMsg> msgList = new ArrayList<>();
        for (String mName : memberNames) {
            mName = mName.trim();
            if (mName.isEmpty()) continue;
            String sid = name + "_" + mName + ":" + baseSession;
            msgList.add(createMsg().setAction(AGENT_CHAT)
                    .setParam(AI_P_USERMESSAGE, task).setParam(AI_P_SESSIONID, sid)
                    .setParam("rootSessionId", rootSessionId)
                    .setDestination(mName));
        }

        TLMsg groupResult = putMsgGroupByThread(msgList, waitTime);
        List<TLMsg> resultList = (List<TLMsg>) groupResult.getParam(RESULT, List.class);

        StringBuilder merged = new StringBuilder();
        for (int i = 0; i < memberNames.length; i++) {
            String mn = memberNames[i].trim();
            if (mn.isEmpty()) continue;
            TLMsg r = (resultList != null && i < resultList.size()) ? resultList.get(i) : null;
            if (r != null && r.parseBoolean(RESULT, false)) {
                merged.append("【").append(mn).append("】\n").append(r.getStringParam(AI_P_RESPONSE, "")).append("\n\n");
            } else {
                merged.append("【").append(mn).append(" - 错误】")
                        .append(r != null ? r.getStringParam(AI_P_RESPONSE, "failed") : "timeout").append("\n\n");
            }
        }
        return createMsg().setParam(RESULT, true).setParam(AI_P_RESPONSE, merged.toString().trim());
    }
}
