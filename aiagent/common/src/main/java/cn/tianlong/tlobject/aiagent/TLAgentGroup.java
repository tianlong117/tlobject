package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLModuleConfig;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;
import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Group Agent 模块：把一组成员 agent 当作一个子 agent 对外提供服务。
 * 对 master 而言就是一个普通子 agent（进 subAgents，接收 AGENT_CHAT），
 * 创建路径无任何特判：类由配置决定（classfile/sameClassAs），
 * 组有自己的配置文件 {name}_config.xml（TLBaseModule 自动发现）。
 * 内部按 mode 调度成员：
 * - sequential: 串行，前一步输出 → 下一步输入
 * - parallel:   并发，putMsgGroupByThread 一发全发，等齐合并结果
 *
 * 成员是本 group 的私有实例，由组自己初始化（与 TLAiAgent.initAgents 对称）：
 * 自身配置的 <agents> 名单即成员列表（保序，名单顺序即 sequential 执行顺序），
 * 条目只含创建所需项（sameClassAs/classfile/statup 等），
 * 成员详细配置在各自的 {member}_config.xml 中自治管理。
 * 运行时也可经 AGENT_REGISTERAGENT 消息动态追加成员
 * （AI_P_AGENTNAME + AI_P_AGENTCONFIG，与 TLAiAgent.registerAgent 同一契约）。
 *
 * md 文件也由组自己读（与 TLAiAgent.loadAgentMd 同构发现链）：
 * frontmatter description 合并进 agentDescription，master 生成 delegate_to
 * 描述时经 AGENT_GETDESCRIPTION 消息获取；组无 LLM 无 context，正文忽略。
 *
 * 创建日期：2026/7/16
 * 作者:tianlong
 */
public class TLAgentGroup extends TLBaseModule implements TLAiAgentParamString {

    /** 成员名列表（自身配置 <agents> 名单顺序；运行时 registerAgent 追加） */
    protected String[] memberNames;

    /** 调度模式：sequential | parallel */
    protected String mode = "sequential";

    /** parallel 模式等待超时（毫秒） */
    protected int waitTime = 120000;

    /** 本组描述（XML description 参数 + md frontmatter 合并），master 经 AGENT_GETDESCRIPTION 消息读取 */
    protected String agentDescription;

    /** 组 md 文件路径（XML 显式配置 agentMd，未配则自动发现 {configDir}md/{name}.md） */
    protected String agentMdPath;

    public TLAgentGroup() { super(); }
    public TLAgentGroup(String name) { super(name); }
    public TLAgentGroup(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected Object setConfig() {
        myConfig config = new myConfig(configFile, moduleFactory.getConfigDir());
        mconfig = config;
        super.setConfig();
        return config;
    }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("mode") != null)
                mode = params.get("mode");
            if (params.get("waitTime") != null) {
                try { waitTime = Integer.parseInt(params.get("waitTime")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("description") != null)
                agentDescription = params.get("description");
            if (params.get("agentMd") != null)
                agentMdPath = params.get("agentMd");
        }
        // 自动加载本组的 md 文件（组自己读，与 TLAiAgent 对称）
        loadAgentMd();
    }

    @Override
    protected TLBaseModule init() {
        return this;
    }

    @Override
    public void runStartMsg() {
        initMembers();
        super.runStartMsg();
        if (memberNames == null || memberNames.length == 0)
            putLog("Group " + name + " has no members", LogLevel.WARN);
    }

    /**
     * 自动加载本组的 md 文件（与 TLAiAgent.loadAgentMd 同构，组自己读自己的 md）。
     * 查找顺序：1. XML 显式配置 agentMd 路径；2. {configDir}md/{groupName}.md。
     * 组无 LLM 无 context，只取 frontmatter description 合并进 agentDescription
     * （XML 描述在前，md 追加在后，contains 去重），正文无消费方，忽略。
     */
    protected void loadAgentMd() {
        String content = null;
        if (agentMdPath != null && !agentMdPath.isEmpty())
            content = TLMdFileLoader.readFileOrResource(agentMdPath, this.getClass());
        if (content == null)
            content = TLMdFileLoader.readFileOrResource(
                    moduleFactory.getConfigDir() + "md/" + name + ".md", this.getClass());
        if (content == null || content.trim().isEmpty()) return;

        String fmDescription = TLMdFileLoader.parseFrontmatterDescription(content);
        if (fmDescription == null || fmDescription.isEmpty()) return;
        if (agentDescription == null || agentDescription.isEmpty())
            agentDescription = fmDescription;
        else if (!agentDescription.contains(fmDescription))
            agentDescription = agentDescription + "\n" + fmDescription;
        putLog("Group md loaded: " + name, LogLevel.DEBUG);
    }

    /**
     * 从自身配置的 <agents> 名单初始化成员（与 TLAiAgent.initAgents 对称）。
     * 名单 LinkedHashMap 保序，顺序即 sequential 执行顺序；
     * cfg 注入 modulesClass/modulesParams 后 getMyModule 创建私有实例，
     * 类解析走工厂既有机制（classfile/sameClassAs），代码不硬编码类名。
     */
    protected void initMembers() {
        if (!(mconfig instanceof myConfig)) return;
        LinkedHashMap<String, HashMap<String, String>> membersConfig = ((myConfig) mconfig).getAgents();
        if (membersConfig == null || membersConfig.isEmpty()) return;

        if (modulesClass == null) modulesClass = new HashMap<>();
        if (modulesParams == null) modulesParams = new HashMap<>();
        List<String> created = new ArrayList<>();
        for (String mName : membersConfig.keySet()) {
            HashMap<String, String> cfg = membersConfig.get(mName);
            if (!TLDataUtils.parseBoolean(cfg.get("statup"), true)) continue;
            try {
                modulesClass.putIfAbsent(mName, cfg);
                modulesParams.putIfAbsent(mName, cfg);
                TLBaseModule module = (TLBaseModule) getMyModule(mName);
                if (module == null) {
                    putLog("Failed to create group member: " + mName + " (group " + name + ")", LogLevel.ERROR);
                    continue;
                }
                created.add(mName);
                putLog("Group member initialized: " + mName + " (group " + name + ")", LogLevel.DEBUG);
            } catch (Exception e) {
                putLog("Failed to init group member: " + mName + " error: " + e.toString(), LogLevel.ERROR);
            }
        }
        memberNames = created.toArray(new String[0]);
        System.out.println("  ▸ Group [" + name + "] mode=" + mode
                + " members=[" + String.join(";", created) + "]");
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
            case AGENT_GETDESCRIPTION:
                returnMsg = createMsg().setParam(RESULT, true)
                        .setParam(AI_P_AGENTDESCRIPTION, agentDescription != null ? agentDescription : "");
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
     * 运行时注册成员：cfg 注入 modulesClass/modulesParams 后 getMyModule 创建私有实例，
     * 并追加进调度名单。消息契约与 TLAiAgent.registerAgent 一致：AI_P_AGENTNAME + AI_P_AGENTCONFIG。
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
            appendMember(agentName);
            putLog("Group member registered: " + agentName + " (group " + name + ")", LogLevel.DEBUG);
            return createMsg().setParam(RESULT, true).setParam(AI_P_AGENTNAME, agentName);
        } catch (Exception e) {
            putLog("Failed to register group member: " + agentName + " error: " + e.toString(), LogLevel.ERROR);
            return createMsg().setParam(RESULT, false).setParam("error", "register failed: " + e);
        }
    }

    /** 追加成员到调度名单末尾（已存在则跳过） */
    protected synchronized void appendMember(String mName) {
        List<String> list = new ArrayList<>();
        if (memberNames != null) Collections.addAll(list, memberNames);
        if (!list.contains(mName)) list.add(mName);
        memberNames = list.toArray(new String[0]);
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

    // ======================== 内部配置解析类 ========================

    /**
     * 解析组自身配置的 <agents> 名单。遵循 TLAiAgent.myConfig 模式，
     * 但用 LinkedHashMap 保序——名单顺序即 sequential 执行顺序。
     */
    protected class myConfig extends TLModuleConfig {
        protected LinkedHashMap<String, HashMap<String, String>> agents;

        public myConfig() {}

        public myConfig(String configFile, String configDir) {
            super(configFile, configDir);
        }

        public LinkedHashMap<String, HashMap<String, String>> getAgents() { return agents; }

        @Override
        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if (xpp.getName().equals("agents")) {
                    agents = getLinkedHashMap(xpp, "agents", "agent");
                }
            } catch (Throwable t) {
                putLog("TLAgentGroup config parse error: " + t.toString(), LogLevel.WARN);
            }
        }

        /** 与 TLModuleConfig.getHashMap 同构，改用 LinkedHashMap 保留 XML 名单顺序 */
        protected LinkedHashMap<String, HashMap<String, String>> getLinkedHashMap(
                XmlPullParser xpp, String firstTag, String tag) throws Throwable {
            LinkedHashMap<String, HashMap<String, String>> map = null;
            String findex = null;
            HashMap<String, String> sonMap = null;
            while (true) {
                xpp.next();
                if ((xpp.getEventType() == XmlPullParser.END_TAG && xpp.getName().equals(firstTag))
                        || xpp.getEventType() == XmlPullParser.END_DOCUMENT)
                    break;
                if (xpp.getEventType() == XmlPullParser.END_TAG && xpp.getName().equals(tag)) {
                    if (findex != null && sonMap != null) {
                        if (map == null)
                            map = new LinkedHashMap<>();
                        map.put(findex, sonMap);
                    }
                }
                if (xpp.getEventType() == XmlPullParser.START_TAG) {
                    if (xpp.getName().equals(tag)) {
                        sonMap = new HashMap<>();
                        findex = xpp.getAttributeValue(0);
                    }
                    for (int i = 1; i < xpp.getAttributeCount(); i++) {
                        sonMap.put(xpp.getAttributeName(i), xpp.getAttributeValue(i));
                    }
                }
            }
            return map;
        }
    }
}
