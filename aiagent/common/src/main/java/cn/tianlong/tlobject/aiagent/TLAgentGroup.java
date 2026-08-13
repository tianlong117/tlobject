package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLModuleConfig;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;
import cn.tianlong.tlobject.utils.TLXmlConfigWriter;
import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * 监理（supervisor，可选）：名单中 role="supervisor" 标记的成员（普通 LLM agent，
 * 不进调度名单）。成员执行完后，组把 任务+各成员结果+输出契约 发给监理审核：
 * 达标 → 监理输出面向用户的最终统一结果（组黑盒统一输出）；
 * 不达标 → 监理输出 {"retry":{"成员名":"反馈"}}，组重跑被点名成员（输入追加反馈）再审，
 * 最多 maxReviewRounds 轮（默认 1），轮次用尽返回当前拼接结果。
 * 审核业务标准写在监理自己的 md/system prompt 里，组代码只带 JSON 输出契约。
 * 不配监理时保持原行为（parallel 拼接 / sequential 返回末步输出）。
 *
 * 创建日期：2026/7/16
 * 作者:tianlong
 */
public class TLAgentGroup extends TLBaseModule implements TLAiAgentParamString, IAgentCapable {

    /** 成员名列表（自身配置 <agents> 名单顺序；运行时 registerAgent 追加），不含监理 */
    protected String[] memberNames;

    /** 调度模式：sequential | parallel */
    protected String mode = "sequential";

    /** parallel 模式等待超时（毫秒） */
    protected int waitTime = 120000;

    /** 监理成员名（名单 role="supervisor" 标记；审核+汇总，不进调度名单），null 表示无监理 */
    protected String supervisorName;

    /** 监理审核最大重跑轮次（params maxReviewRounds，默认 1） */
    protected int maxReviewRounds = 1;

    /** 本组描述（XML description 参数 + md frontmatter 合并），master 经 AGENT_GETDESCRIPTION 消息读取 */
    protected String agentDescription;

    /** 组 md 文件路径（XML 显式配置 agentMd，未配则自动发现 {configDir}md/{name}.md） */
    protected String agentMdPath;

    /** JSON 解析（宽容解析监理 retry 指令） */
    protected com.google.gson.Gson gson = new com.google.gson.Gson();

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
            if (params.get("maxReviewRounds") != null) {
                try { maxReviewRounds = Integer.parseInt(params.get("maxReviewRounds")); }
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
     * role="supervisor" 的条目创建为监理（不进调度名单，多个只取第一个）。
     */
    protected void initMembers() {
        if (!(mconfig instanceof myConfig)) return;
        LinkedHashMap<String, HashMap<String, String>> membersConfig = ((myConfig) mconfig).getAgents();
        if (membersConfig == null || membersConfig.isEmpty()) return;

        List<String> created = new ArrayList<>();
        for (String mName : membersConfig.keySet()) {
            HashMap<String, String> cfg = membersConfig.get(mName);
            if (!TLDataUtils.parseBoolean(cfg.get("statup"), true)) continue;
            boolean isSupervisor = "supervisor".equals(cfg.get("role"));
            try {
                modulesClass.putIfAbsent(mName, cfg);
                modulesParams.putIfAbsent(mName, cfg);
                TLBaseModule module = (TLBaseModule) getMyModule(mName);
                registerToRegistry(mName, module, "agent");
                if (module == null) {
                    putLog("Failed to create group member: " + mName + " (group " + name + ")", LogLevel.ERROR);
                    continue;
                }
                if (isSupervisor) {
                    if (supervisorName == null) {
                        supervisorName = mName;
                        putLog("Group supervisor initialized: " + mName + " (group " + name + ")", LogLevel.DEBUG);
                    } else {
                        putLog("Group " + name + " already has supervisor " + supervisorName
                                + ", ignore: " + mName, LogLevel.WARN);
                    }
                } else {
                    created.add(mName);
                    putLog("Group member initialized: " + mName + " (group " + name + ")", LogLevel.DEBUG);
                }
            } catch (Exception e) {
                putLog("Failed to init group member: " + mName + " error: " + e.toString(), LogLevel.ERROR);
            }
        }
        memberNames = created.toArray(new String[0]);
        putLog("Group [" + name + "] mode=" + mode
                + " members=[" + String.join(";", created) + "]"
                + (supervisorName != null ? " supervisor=" + supervisorName : ""), LogLevel.DEBUG);
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case AGENT_CHAT:
                returnMsg = chat(fromWho, msg);
                break;
            case SKILL_EXECUTE:
                // group 作为 function 被调用：内部 chat，结果放 AI_P_SKILLOUTPUT
                returnMsg = executeAsTool(fromWho, msg);
                break;
            case AGENT_REGISTERAGENT:
                returnMsg = registerMember(fromWho, msg);
                break;
            case AGENT_UNREGISTERAGENT:
                returnMsg = unregisterMember(fromWho, msg);
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
     * 转发给每个成员和监理。成员 doChat 期间也会向 agentMonitor 自注册被 stopByRoot 直接停到，
     * 此处转发补的是间隙（成员尚未进入/已退出 doChat 时），重复停止无害（置标志+interrupt 幂等）。
     */
    protected TLMsg stopChat(Object fromWho, TLMsg msg) {
        String sid = msg.getStringParam(AI_P_SESSIONID, "default");
        List<String> targets = new ArrayList<>();
        if (memberNames != null) Collections.addAll(targets, memberNames);
        if (supervisorName != null) targets.add(supervisorName);
        for (String mName : targets) {
            mName = mName.trim();
            if (mName.isEmpty()) continue;
            TLBaseModule member = (TLBaseModule) getModule(mName);
            if (member == null) continue;
            TLMsg stopMsg = createMsg().setAction(AGENT_STOPCHAT)
                    .setParam(AI_P_SESSIONID, name + "_" + mName + ":" + sid)
                    .setParam("cascade", true);
            putMsg(member, stopMsg);
        }
        putLog("Group stopChat forwarded to members: sessionId=" + sid, LogLevel.INFO);
        return createMsg().setParam(RESULT, true).setParam(AI_P_SESSIONID, sid);
    }

    /**
     * 运行时注册成员：cfg 注入 modulesClass/modulesParams 后 getMyModule 创建私有实例，
     * 并追加进调度名单（cfg role="supervisor" 时注册为监理）。
     * 消息契约与 TLAiAgent.registerAgent 一致：AI_P_AGENTNAME + AI_P_AGENTCONFIG。
     */
    private void registerToRegistry(String subName, Object module, String moduleType) {
        if (module == null) return;
        String familyName = module instanceof TLBaseModule
                ? ((TLBaseModule) module).getFamilyName() : getName() + ":" + subName;
        TLMsg msg = createMsg().setAction(REGISTRY_REGISTER)
                .setParam(REGISTRY_P_KEY, familyName)
                .setParam(MODULENAME, subName)
                .setParam(INSTANCE, module);
        // owner 由 familyName 自描述，type 由 instanceof 判断，无需额外存储
        msg.setSystemParam(IGNOREMODULEISNULL, true);
        putMsg(DEFAULTMODULEREGISTRY, msg);
    }

    protected synchronized TLMsg registerMember(Object fromWho, TLMsg msg) {
        String agentName = msg.getStringParam(AI_P_AGENTNAME, "");
        if (agentName.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "agentName required");
        }
        HashMap<String, String> cfg = new HashMap<>(msg.getMapParam(AI_P_AGENTCONFIG, new HashMap<>()));
        try {
            // 前置校验（含类型检查）
            TLMsg err = validateModuleRef(cfg, IAgentCapable.class);
            if (err != null) return err;
            modulesClass.put(agentName, cfg);
            modulesParams.put(agentName, cfg);
            TLBaseModule module = (TLBaseModule) getMyModule(agentName);
            registerToRegistry(agentName, module, "agent");
            if (module == null) {
                modulesClass.remove(agentName);
                modulesParams.remove(agentName);
                return createMsg().setParam(RESULT, false).setParam("error", "create failed: " + agentName);
            }
            if ("supervisor".equals(cfg.get("role"))) {
                if (supervisorName == null) supervisorName = agentName;
                else putLog("Group " + name + " already has supervisor " + supervisorName
                        + ", ignore: " + agentName, LogLevel.WARN);
            } else {
                appendMember(agentName);
            }
            putLog("Group member registered: " + agentName + " (group " + name + ")", LogLevel.DEBUG);

            // 持久化到配置文件
            boolean persist = msg.parseBoolean(HOTLOAD_P_PERSIST, true);
            if (persist && configFile != null) {
                try {
                    Map<String, String> attrs = new LinkedHashMap<>();
                    for (Map.Entry<String, String> e : cfg.entrySet()) {
                        if (e.getValue() != null) attrs.put(e.getKey(), e.getValue());
                    }
                    TLXmlConfigWriter.addOrReplaceElement(configFile, "agents", "agent", agentName, attrs);
                } catch (Exception ex) {
                    putLog("persist group member config failed: " + ex, LogLevel.ERROR);
                }
            }
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

    /** 从调度名单移除成员 */
    protected synchronized void removeMember(String mName) {
        if (memberNames == null) return;
        List<String> list = new ArrayList<>();
        for (String n : memberNames) {
            if (!n.equals(mName)) list.add(n);
        }
        memberNames = list.toArray(new String[0]);
    }

    /** 卸载成员：从内存和配置文件移除 */
    protected synchronized TLMsg unregisterMember(Object fromWho, TLMsg msg) {
        String agentName = msg.getStringParam(AI_P_AGENTNAME, "");
        if (agentName.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "agentName required");
        }
        removeMember(agentName);
        modules.remove(agentName);
        if (modulesClass != null) modulesClass.remove(agentName);
        if (modulesParams != null) modulesParams.remove(agentName);
        if (supervisorName != null && supervisorName.equals(agentName)) supervisorName = null;

        // 持久化：从配置文件删除
        boolean persist = msg.parseBoolean(HOTLOAD_P_PERSIST, true);
        if (persist && configFile != null) {
            try {
                TLXmlConfigWriter.removeElement(configFile, "agents", "agent", agentName);
            } catch (Exception ex) {
                putLog("remove group member from config failed: " + ex, LogLevel.ERROR);
            }
        }
        putLog("Group member unregistered: " + agentName + " (group " + name + ")", LogLevel.DEBUG);
        return createMsg().setParam(RESULT, true).setParam(AI_P_AGENTNAME, agentName);
    }

    /**
     * 子 agent chat 契约：收 AI_P_USERMESSAGE，按 mode 调度成员，
     * 返回 RESULT + AI_P_RESPONSE（master 委托路径读取 AI_P_RESPONSE）。
     * 配置了监理时结果交监理审核+汇总（见 supervise），组黑盒统一输出；
     * 无监理时 parallel 拼接返回 / sequential 返回链条末步输出（原语义）。
     * 向 agentMonitor 自注册：stopByRoot 级联时能找到组本身，
     * 组的 stopChat 再转发给成员（补成员未进入 doChat 时的间隙）。
     */
    /** Group 作为 function 被调用：内部 chat → AI_P_SKILLOUTPUT */
    @SuppressWarnings("unchecked")
    protected TLMsg executeAsTool(Object fromWho, TLMsg msg) {
        Map<String, Object> args = (Map<String, Object>) msg.getParam(AI_P_SKILLINPUT, Map.class);
        String task = (args != null && args.containsKey("task")) ? String.valueOf(args.get("task")) : "";
        if (task.isEmpty()) task = msg.getStringParam(AI_P_USERMESSAGE, "");
        TLMsg chatMsg = createMsg().setAction(AGENT_CHAT).setParam(AI_P_USERMESSAGE, task);
        if (msg.containsParam(AI_P_SESSIONID)) chatMsg.setParam(AI_P_SESSIONID, msg.getStringParam(AI_P_SESSIONID, ""));
        if (msg.containsParam("rootSessionId")) chatMsg.setParam("rootSessionId", msg.getStringParam("rootSessionId", ""));
        if (msg.containsParam("userId")) chatMsg.setParam("userId", msg.getStringParam("userId", ""));
        TLMsg result = chat(fromWho, chatMsg);
        return createMsg().setParam(AI_P_SKILLOUTPUT, result != null ? result.getStringParam(AI_P_RESPONSE, "") : "");
    }

    protected TLMsg chat(Object fromWho, TLMsg msg) {
        if (memberNames == null || memberNames.length == 0) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, "Error: group " + name + " has no members");
        }
        String task = msg.getStringParam(AI_P_USERMESSAGE, "");
        String baseSession = msg.getStringParam(AI_P_SESSIONID, String.valueOf(System.currentTimeMillis()));
        String rootSessionId = msg.getStringParam("rootSessionId", baseSession);

        putLog(">>> [Group " + name + "] mode=" + mode + " members=" + String.join(";", memberNames)
                + (supervisorName != null ? " supervisor=" + supervisorName : ""), LogLevel.INFO);
        putLog("    任务: " + task, LogLevel.INFO);

        // 向监控模块登记（与 TLAiAgent.doChat 同契约），finally 注销
        putMsg(M_AGENTMONITOR, createMsg().setAction("register")
                .setParam(AI_P_SESSIONID, baseSession)
                .setParam("rootSessionId", rootSessionId)
                .setParam("agentName", getName()));
        try {
            LinkedHashMap<String, String> results;
            String plainAnswer;   // 无监理时的返回内容
            if ("parallel".equals(mode)) {
                LinkedHashMap<String, String> tasks = new LinkedHashMap<>();
                for (String mName : memberNames) tasks.put(mName, task);
                results = runParallel(tasks, baseSession, rootSessionId);
                plainAnswer = mergeResults(results);
            } else {
                results = new LinkedHashMap<>();
                TLMsg err = runSequential(task, baseSession, rootSessionId, results);
                if (err != null) return err;
                // sequential 无监理时维持原语义：返回链条最后一步输出
                String last = "";
                for (String v : results.values()) last = v;
                plainAnswer = last;
            }
            if (supervisorName != null)
                return supervise(task, results, baseSession, rootSessionId);
            return createMsg().setParam(RESULT, true).setParam(AI_P_RESPONSE, plainAnswer);
        } finally {
            putMsg(M_AGENTMONITOR, createMsg().setAction("unregister")
                    .setParam(AI_P_SESSIONID, baseSession));
        }
    }

    /**
     * 串行执行全部成员：前一步输出 → 下一步输入，各步结果按序存入 results。
     * @return 成员失败时返回错误 TLMsg，全部成功返回 null
     */
    protected TLMsg runSequential(String task, String baseSession, String rootSessionId,
                                  LinkedHashMap<String, String> results) {
        String currentInput = task;
        for (String mName : memberNames) {
            mName = mName.trim();
            if (mName.isEmpty()) continue;
            TLBaseModule member = (TLBaseModule) getModule(mName);
            if (member == null) {
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_RESPONSE, "Group member not found: " + mName);
            }
            String sid = name + "_" + mName + ":" + baseSession;
            putLog("  → [Group-seq] " + mName, LogLevel.DEBUG);
            TLMsg result = putMsg(member, createMsg().setAction(AGENT_CHAT)
                    .setParam(AI_P_USERMESSAGE, currentInput).setParam(AI_P_SESSIONID, sid)
                    .setParam("rootSessionId", rootSessionId));
            if (result == null || !result.parseBoolean(RESULT, false)) {
                String err = result != null ? result.getStringParam(AI_P_RESPONSE, "unknown") : "no response";
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_RESPONSE, "Group step [" + mName + "] failed: " + err);
            }
            String resp = result.getStringParam(AI_P_RESPONSE, "");
            results.put(mName, resp);
            if (!resp.isEmpty())
                currentInput = resp;
            putLog("  ✓ [Group-seq] " + mName + " done", LogLevel.DEBUG);
        }
        return null;
    }

    /**
     * 并行执行指定成员：memberTasks 为 成员名→输入（保序），一发全发等齐。
     * 返回 成员名→响应；失败/超时的条目内容为 "[错误] xxx"。
     * 监理重跑时传入被点名成员的子集（输入已追加反馈）。
     */
    @SuppressWarnings("unchecked")
    protected LinkedHashMap<String, String> runParallel(LinkedHashMap<String, String> memberTasks,
                                                        String baseSession, String rootSessionId) {
        List<String> order = new ArrayList<>(memberTasks.keySet());
        List<TLMsg> msgList = new ArrayList<>();
        for (String mName : order) {
            String sid = name + "_" + mName + ":" + baseSession;
            msgList.add(createMsg().setAction(AGENT_CHAT)
                    .setParam(AI_P_USERMESSAGE, memberTasks.get(mName)).setParam(AI_P_SESSIONID, sid)
                    .setParam("rootSessionId", rootSessionId)
                    .setDestination(mName));
        }

        TLMsg groupResult = putMsgGroupByThread(msgList, waitTime);
        List<TLMsg> resultList = (List<TLMsg>) groupResult.getParam(RESULT, List.class);

        LinkedHashMap<String, String> results = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            String mn = order.get(i);
            TLMsg r = (resultList != null && i < resultList.size()) ? resultList.get(i) : null;
            if (r != null && r.parseBoolean(RESULT, false)) {
                results.put(mn, r.getStringParam(AI_P_RESPONSE, ""));
            } else {
                results.put(mn, "[错误] " + (r != null ? r.getStringParam(AI_P_RESPONSE, "failed") : "timeout"));
            }
        }
        return results;
    }

    /** 拼接各成员结果为 【成员】内容 格式文本（无监理时的返回格式 / 监理审核的输入） */
    protected String mergeResults(LinkedHashMap<String, String> results) {
        StringBuilder merged = new StringBuilder();
        for (Map.Entry<String, String> e : results.entrySet())
            merged.append("【").append(e.getKey()).append("】\n").append(e.getValue()).append("\n\n");
        return merged.toString().trim();
    }

    /**
     * 监理审核循环：任务+各成员结果 发给监理（AGENT_CHAT），审核标准在监理自己的
     * md/system prompt 中，此处只携带 JSON 输出契约。
     * - 监理返回解析不出 retry 指令 → 即最终统一结果，返回 master
     * - 有 retry 且轮次未用尽 → 重跑被点名成员（parallel 只重发点名者，输入追加 [监理反馈]；
     *   sequential 整链重跑，任务追加全部反馈），更新结果后再审
     * - 轮次用尽仍不达标 / 监理不可用 → 返回当前拼接结果（不把 JSON 指令返给 master），WARN
     */
    protected TLMsg supervise(String task, LinkedHashMap<String, String> results,
                              String baseSession, String rootSessionId) {
        TLBaseModule supervisor = (TLBaseModule) getModule(supervisorName);
        if (supervisor == null) {
            putLog("Group supervisor not found: " + supervisorName + ", fallback to merged results", LogLevel.WARN);
            return createMsg().setParam(RESULT, true).setParam(AI_P_RESPONSE, mergeResults(results));
        }
        String sid = name + "_" + supervisorName + ":" + baseSession;

        for (int round = 0; round <= maxReviewRounds; round++) {
            String reviewInput = "任务：" + task
                    + "\n\n各成员执行结果：\n" + mergeResults(results)
                    + "\n\n请按你的审核标准逐个检查成员结果："
                    + "全部达标则直接输出面向用户的最终统一结果（不要提及审核过程）；"
                    + "若有成员结果不达标，只输出JSON（不要任何其他文字）："
                    + "{\"retry\":{\"成员名\":\"具体反馈意见\"}}";
            putLog("  → [Group-supervisor] " + supervisorName + " 审核 (round " + (round + 1) + ")", LogLevel.DEBUG);
            TLMsg r = putMsg(supervisor, createMsg().setAction(AGENT_CHAT)
                    .setParam(AI_P_USERMESSAGE, reviewInput).setParam(AI_P_SESSIONID, sid)
                    .setParam("rootSessionId", rootSessionId));
            if (r == null || !r.parseBoolean(RESULT, false)) {
                putLog("Group supervisor chat failed, fallback to merged results", LogLevel.WARN);
                return createMsg().setParam(RESULT, true).setParam(AI_P_RESPONSE, mergeResults(results));
            }
            String resp = r.getStringParam(AI_P_RESPONSE, "");
            Map<String, String> retry = parseRetryDirective(resp);
            if (retry == null || retry.isEmpty()) {
                putLog("  ✓ [Group-supervisor] 审核通过，输出统一结果", LogLevel.DEBUG);
                return createMsg().setParam(RESULT, true).setParam(AI_P_RESPONSE, resp);
            }
            if (round == maxReviewRounds) {
                putLog("Group " + name + " review rounds exhausted, still failing: " + retry.keySet(), LogLevel.WARN);
                break;
            }

            putLog("  ↻ [Group-supervisor] 要求重跑: " + retry.keySet() + " 反馈: " + retry.values(), LogLevel.DEBUG);
            if ("parallel".equals(mode)) {
                // 只重发被点名且在结果集中的成员，输入 = 原任务 + 监理反馈
                LinkedHashMap<String, String> retryTasks = new LinkedHashMap<>();
                for (Map.Entry<String, String> e : retry.entrySet()) {
                    if (results.containsKey(e.getKey()))
                        retryTasks.put(e.getKey(), task + "\n\n[监理反馈] " + e.getValue());
                }
                if (retryTasks.isEmpty()) {
                    putLog("Group supervisor named unknown members: " + retry.keySet(), LogLevel.WARN);
                    break;
                }
                results.putAll(runParallel(retryTasks, baseSession, rootSessionId));
            } else {
                // sequential 链条有依赖，整链重跑，任务追加全部反馈
                String fbTask = task + "\n\n[监理反馈] " + String.join("；", retry.values());
                LinkedHashMap<String, String> rerun = new LinkedHashMap<>();
                TLMsg err = runSequential(fbTask, baseSession, rootSessionId, rerun);
                if (err != null) {
                    putLog("Group sequential rerun failed, keep previous results", LogLevel.WARN);
                    break;
                }
                results.clear();
                results.putAll(rerun);
            }
        }
        return createMsg().setParam(RESULT, true).setParam(AI_P_RESPONSE, mergeResults(results));
    }

    /**
     * 宽容解析监理的 retry 指令：截取首个 '{' 到末个 '}'（天然剥掉 ```json 围栏），
     * gson 解析取 "retry" 对象 → 成员名→反馈 map。任何解析失败返回 null（视为最终结果）。
     */
    protected Map<String, String> parseRetryDirective(String resp) {
        if (resp == null || resp.isEmpty()) return null;
        try {
            int start = resp.indexOf('{');
            int end = resp.lastIndexOf('}');
            if (start < 0 || end <= start) return null;
            com.google.gson.JsonObject obj =
                    gson.fromJson(resp.substring(start, end + 1), com.google.gson.JsonObject.class);
            if (obj == null || !obj.has("retry") || !obj.get("retry").isJsonObject()) return null;
            Map<String, String> map = new LinkedHashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> e : obj.getAsJsonObject("retry").entrySet()) {
                map.put(e.getKey(), e.getValue().isJsonPrimitive()
                        ? e.getValue().getAsString() : e.getValue().toString());
            }
            return map;
        } catch (Exception e) {
            return null;
        }
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
