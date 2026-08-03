package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.*;

import cn.tianlong.tlobject.aiagent.mcp.TLMcpAgent;
import cn.tianlong.tlobject.aiagent.mcp.TLMcpRegistry;
import cn.tianlong.tlobject.aiagent.mcp.TLMcpRegistryEntry;
import cn.tianlong.tlobject.aiagent.mcp.TLMcpRuntime;

/**
 * AI Agent 公共服务模块——各种 UI（控制台、WebUI 等）与 Agent 框架之间的中介层。
 *
 * 职责：
 *   1. 提供结构化的 Agent 操作 API（chat、chatStream、install、uninstall 等）
 *   2. 封装与 TLAiAgent、Registry、ApprovalModule、EvalsModule 等模块的消息交互
 *   3. 统一返回格式：{@code {success: boolean, message: String, [error]: String, [data]: Object}}
 *
 * UI 层只需构造结构化消息发给本模块，无需了解底层消息协议细节。
 *
 * 配置参数 (XML params):
 *   agentModule — 默认目标 Agent 家族名，默认 "aiagent"
 *
 * @author tianlong
 * @date 2026/7/31
 */
public class TLAgentService extends TLBaseModule implements TLAiAgentParamString {

    private String agentModule = "aiagent";

    /** 会话管理模块名，默认 "sessionManager" */
    private String sessionManagerName = "sessionManager";

    /** MCP 市场注册表 */
    private final TLMcpRegistry mcpRegistry = new TLMcpRegistry();

    /** 所有可用 action 及其描述，供 UI 做帮助/补全 */
    private static final LinkedHashMap<String, String> ACTION_REGISTRY = new LinkedHashMap<>();
    static {
        ACTION_REGISTRY.put("chat", "发送消息，获取AI回复（阻塞式）");
        ACTION_REGISTRY.put("chatStream", "流式对话，chunk 实时推送");
        ACTION_REGISTRY.put("stopChat", "中断当前对话");
        ACTION_REGISTRY.put("install", "安装 Skill/Agent/BaseSkill");
        ACTION_REGISTRY.put("uninstall", "卸载 Skill/Agent/BaseSkill");
        ACTION_REGISTRY.put("reload", "重载 Skill/Agent/BaseSkill");
        ACTION_REGISTRY.put("agents", "列出已注册 Agent");
        ACTION_REGISTRY.put("skills", "列出已注册 Skill");
        ACTION_REGISTRY.put("sessions", "列出所有历史会话");
        ACTION_REGISTRY.put("continue", "继续历史会话");
        ACTION_REGISTRY.put("resume", "恢复断点会话");
        ACTION_REGISTRY.put("clear", "清除会话上下文");
        ACTION_REGISTRY.put("session", "切换当前会话ID");
        ACTION_REGISTRY.put("approve", "审批操作（批准/拒绝）");
        ACTION_REGISTRY.put("eval", "Agent评测");
        ACTION_REGISTRY.put("getTokenUsage", "查询 Token 用量");
        ACTION_REGISTRY.put("getCacheStats", "查询 Prompt 缓存统计");
        ACTION_REGISTRY.put("checkProvider", "检查 LLM Provider 可用性");
        ACTION_REGISTRY.put("listCommands", "列出所有可用命令");
        ACTION_REGISTRY.put("mcpSearch", "搜索 MCP 服务器市场");
        ACTION_REGISTRY.put("mcpInstall", "从市场安装 MCP 服务器");
        ACTION_REGISTRY.put("mcpList", "列出已安装的 MCP Agent");
        ACTION_REGISTRY.put("mcpRemove", "卸载 MCP Agent");
    }

    public TLAgentService() { super(); }
    public TLAgentService(String name) { super(name); }
    public TLAgentService(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected TLBaseModule init() { return this; }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null) {
            if (params.get("agentModule") != null) {
                agentModule = params.get("agentModule");
            }
            if (params.get("sessionManager") != null) {
                sessionManagerName = params.get("sessionManager");
            }
        }
    }

    /** 获取会话管理模块名 */
    private String targetSessionManager(TLMsg msg) {
        return msg.getStringParam("sessionManager", sessionManagerName);
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            // ── Chat ──
            case "chat":            return doChat(fromWho, msg);
            case "chatStream":      return doChatStream(fromWho, msg);
            case "stopChat":        return doStopChat(fromWho, msg);

            // ── 安装/卸载/重载 ──
            case "install":         return doInstall(fromWho, msg);
            case "uninstall":       return doUninstall(fromWho, msg);
            case "reload":          return doReload(fromWho, msg);

            // ── 查询 ──
            case "agents":          return doListAgents(fromWho, msg);
            case "skills":          return doListSkills(fromWho, msg);
            case "sessions":        return doListSessions(fromWho, msg);
            case "listCommands":    return doListCommands(fromWho, msg);

            // ── 会话管理 ──
            case "continue":        return doContinueSession(fromWho, msg);
            case "resume":          return doResume(fromWho, msg);
            case "clear":           return doClearContext(fromWho, msg);
            case "session":         return doSwitchSession(fromWho, msg);

            // ── 审批 ──
            case "approve":         return doApprove(fromWho, msg);

            // ── 评测 ──
            case "eval":            return doEval(fromWho, msg);

            // ── 状态查询 ──
            case "getTokenUsage":   return doGetTokenUsage(fromWho, msg);
            case "getCacheStats":   return doGetCacheStats(fromWho, msg);
            case "checkProvider":   return doCheckProvider(fromWho, msg);

            // ── MCP 市场 ──
            case "mcpSearch":       return doMcpSearch(fromWho, msg);
            case "mcpInstall":      return doMcpInstall(fromWho, msg);
            case "mcpList":         return doMcpList(fromWho, msg);
            case "mcpRemove":       return doMcpRemove(fromWho, msg);

            // ── 运行时参数 ──
            case "setParam":        return doSetParam(fromWho, msg);

            default: return null;
        }
    }

    // ======================== 工具方法 ========================

    /** 成功返回 */
    private TLMsg ok(String message) {
        return createMsg().setParam("success", true).setParam("message", message);
    }
    /** 成功返回 + 数据 */
    private TLMsg ok(String message, Object data) {
        return createMsg().setParam("success", true).setParam("message", message).setParam("data", data);
    }
    /** 失败返回 */
    private TLMsg fail(String error) {
        return createMsg().setParam("success", false).setParam("error", error);
    }

    /** 获取目标 agent 模块名（优先用消息参数 targetAgent，否则用配置的默认值） */
    private String targetAgent(TLMsg msg) {
        return msg.getStringParam("targetAgent", agentModule);
    }

    /** 从 moduleRegistry 按家族名查找模块实例 */
    private TLBaseModule findAgentInstance(String familyName) {
        TLMsg result = putMsg(DEFAULTMODULEREGISTRY, createMsg().setAction(REGISTRY_GET)
                .setParam(REGISTRY_P_KEY, familyName));
        if (result != null) {
            Object inst = result.getParam(INSTANCE);
            if (inst instanceof TLBaseModule) return (TLBaseModule) inst;
        }
        return null;
    }

    /** 按家族名从 registry 查找模块 */
    private TLBaseModule lookupByFamilyName(String familyName) {
        return findAgentInstance(familyName);
    }

    /** 家族名 "moduleFactory:aiagent:myAgent" → [直接父实例, 目标短名] */
    private Object[] resolveByPath(String path) {
        if (path == null || path.isEmpty()) return null;
        int lastColon = path.lastIndexOf(':');
        if (lastColon < 0) {
            TLBaseModule parent = findAgentInstance(agentModule);
            if (parent == null) return null;
            return new Object[]{parent, path};
        }
        String targetName = path.substring(lastColon + 1);
        String parentPath = path.substring(0, lastColon);
        TLBaseModule parent = lookupByFamilyName(parentPath);
        if (parent == null) return null;
        return new Object[]{parent, targetName};
    }

    /** 解析 type 参数：skill / agent / baseSkill(或bs) */
    private String resolveType(String raw) {
        if (raw == null) return null;
        switch (raw.toLowerCase()) {
            case "s":
            case "skill":
            case "scripts":
            case "script":   return "skill";
            case "a":
            case "agent":    return "agent";
            case "bs":
            case "baseskill":return "baseSkill";
            default:         return raw.toLowerCase();
        }
    }

    // ======================== Chat ========================

    /** 阻塞式对话 */
    @SuppressWarnings("unchecked")
    private TLMsg doChat(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        String userMessage = msg.getStringParam(AI_P_USERMESSAGE, "");
        String userId = msg.getStringParam("userId", "console_user");
        boolean resume = msg.parseBoolean("resume", false);

        TLMsg chatMsg = createMsg()
                .setAction(AGENT_CHAT)
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam("userId", userId)
                .setParam(AI_P_USERMESSAGE, userMessage);
        if (resume) chatMsg.setParam("resume", true);

        // resume=true: 从 SessionManager 预加载历史数据，注入到 Agent 消息中
        if (resume) {
            TLMsg loaded = putMsg(targetSessionManager(msg), createMsg()
                    .setAction("loadSession")
                    .setParam("sessionId", sessionId)
                    .setParam("userId", userId));
            if (loaded != null && loaded.parseBoolean(RESULT, false)) {
                chatMsg.setParam("history", loaded.getParam("history"));
                chatMsg.setParam("resumeModel", loaded.getStringParam("model", ""));
                chatMsg.setParam("resumeRoundId", loaded.getStringParam("roundId", ""));
                chatMsg.setParam("resumeTemperature", loaded.getDoubleParam("temperature", 0.7));
                chatMsg.setParam("resumeMaxTokens", loaded.getIntParam("maxTokens", 4096));
                chatMsg.setParam("resumeState", loaded.getStringParam("state", SESSION_STATE_CHECKPOINT));
                // 透传审批信息（若存在）
                if (loaded.containsParam("pendingToolCall")) {
                    chatMsg.setParam("pendingToolCall", loaded.getParam("pendingToolCall"));
                }
                if (loaded.containsParam(AI_P_APPROVAL_ID)) {
                    chatMsg.setParam(AI_P_APPROVAL_ID, loaded.getStringParam(AI_P_APPROVAL_ID, ""));
                }
            }
        }

        // 透传可选参数
        if (msg.containsParam(AI_P_MODEL)) chatMsg.setParam(AI_P_MODEL, msg.getStringParam(AI_P_MODEL, null));
        if (msg.containsParam(AI_P_TEMPERATURE)) chatMsg.setParam(AI_P_TEMPERATURE, msg.getDoubleParam(AI_P_TEMPERATURE, 0.0));
        if (msg.containsParam(AI_P_REASONING_MODE)) chatMsg.setParam(AI_P_REASONING_MODE, msg.getStringParam(AI_P_REASONING_MODE, null));

        TLMsg result = putMsg(targetAgent(msg), chatMsg);
        if (result == null) return fail("Agent 无响应");

        boolean success = result.parseBoolean(RESULT, false);
        String aiResponse = result.getStringParam(AI_P_RESPONSE, "");
        boolean cancelled = result.parseBoolean(AI_P_CANCELLED, false);

        TLMsg response = createMsg()
                .setParam("success", success || cancelled)
                .setParam("message", success ? "ok" : (cancelled ? "已中断" : "空响应"))
                .setParam(AI_P_RESPONSE, aiResponse)
                .setParam(AI_P_SESSIONID, result.getStringParam(AI_P_SESSIONID, sessionId))
                .setParam(AI_P_CANCELLED, cancelled);

        // Token 统计
        if (result.containsParam(AI_P_PROMPTTOKENS)) response.setParam(AI_P_PROMPTTOKENS, result.getIntParam(AI_P_PROMPTTOKENS, 0));
        if (result.containsParam(AI_P_COMPLETIONTOKENS)) response.setParam(AI_P_COMPLETIONTOKENS, result.getIntParam(AI_P_COMPLETIONTOKENS, 0));
        if (result.containsParam(AI_P_TOTALTOKENS)) response.setParam(AI_P_TOTALTOKENS, result.getIntParam(AI_P_TOTALTOKENS, 0));
        if (result.containsParam(AI_P_TOTALTOKENS_TOTAL)) response.setParam(AI_P_TOTALTOKENS_TOTAL, result.getIntParam(AI_P_TOTALTOKENS_TOTAL, 0));
        // 缓存统计
        if (result.containsParam(AI_P_CACHEHITTOKENS)) response.setParam(AI_P_CACHEHITTOKENS, result.getIntParam(AI_P_CACHEHITTOKENS, 0));
        if (result.containsParam(AI_P_CACHEMISSTOKENS)) response.setParam(AI_P_CACHEMISSTOKENS, result.getIntParam(AI_P_CACHEMISSTOKENS, 0));
        if (result.containsParam(AI_P_CACHECREATIONTOKENS)) response.setParam(AI_P_CACHECREATIONTOKENS, result.getIntParam(AI_P_CACHECREATIONTOKENS, 0));
        // 推理
        if (result.containsParam(AI_P_REASONING)) response.setParam(AI_P_REASONING, result.getStringParam(AI_P_REASONING, ""));

        return response;
    }

    /** 流式对话：请求发给 Agent，chunk 由 Agent 转发到 streamTarget */
    @SuppressWarnings("unchecked")
    private TLMsg doChatStream(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        String userMessage = msg.getStringParam(AI_P_USERMESSAGE, "");
        String userId = msg.getStringParam("userId", "console_user");
        String streamTarget = msg.getStringParam("streamTarget", null);
        String streamAction = msg.getStringParam("streamAction", STREAM_ONCHUNK);
        boolean resume = msg.parseBoolean("resume", false);

        if (streamTarget == null) return fail("streamTarget 参数必填");

        String agent = targetAgent(msg);
        TLMsg streamMsg = createMsg()
                .setAction(AGENT_CHATSTREAM)
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam("userId", userId)
                .setParam(AI_P_USERMESSAGE, userMessage)
                .setParam(RESULTFOR, agent)
                .setParam(RESULTACTION, "onStreamResult")
                .setParam("_streamResultFor", streamTarget)
                .setParam("_streamResultAction", streamAction);
        if (resume) {
            streamMsg.setParam("resume", true);
            // 从 SessionManager 预加载历史数据
            TLMsg loaded = putMsg(targetSessionManager(msg), createMsg()
                    .setAction("loadSession")
                    .setParam("sessionId", sessionId)
                    .setParam("userId", userId));
            if (loaded != null && loaded.parseBoolean(RESULT, false)) {
                streamMsg.setParam("history", loaded.getParam("history"));
                streamMsg.setParam("resumeModel", loaded.getStringParam("model", ""));
                streamMsg.setParam("resumeRoundId", loaded.getStringParam("roundId", ""));
                streamMsg.setParam("resumeTemperature", loaded.getDoubleParam("temperature", 0.7));
                streamMsg.setParam("resumeMaxTokens", loaded.getIntParam("maxTokens", 4096));
                streamMsg.setParam("resumeState", loaded.getStringParam("state", SESSION_STATE_CHECKPOINT));
            }
        }
        if (msg.containsParam(AI_P_REASONING_MODE)) streamMsg.setParam(AI_P_REASONING_MODE, msg.getStringParam(AI_P_REASONING_MODE, null));

        putMsg(agent, streamMsg);
        return ok("流式请求已提交");
    }

    /** 停止对话 */
    private TLMsg doStopChat(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        // 级联停止
        putMsg(M_AGENTMONITOR, createMsg().setAction("stopByRoot")
                .setParam("rootSessionId", sessionId));
        // 主 agent 停止
        putMsg(targetAgent(msg), createMsg().setAction(AGENT_STOPCHAT)
                .setParam(AI_P_SESSIONID, sessionId));
        return ok("已发送停止信号");
    }

    // ======================== 安装/卸载/重载 ========================

    /** 统一安装入口 */
    private TLMsg doInstall(Object fromWho, TLMsg msg) {
        String type = resolveType(msg.getStringParam("type", null));
        String name = msg.getStringParam("name", null);
        String target = targetAgent(msg);

        if (type == null || name == null) {
            return fail("缺少必要参数: type, name");
        }

        switch (type) {
            case "skill":
                return installScriptSkill(name, target);
            case "agent":
                String classRef = msg.getStringParam("classRef", null);
                if (classRef == null) return fail("安装 Agent 需要 classRef 参数");
                return installAgent(name, classRef, target);
            case "baseSkill":
                String classFile = msg.getStringParam("classFile", msg.getStringParam("classRef", null));
                if (classFile == null) return fail("安装 BaseSkill 需要 classFile 参数");
                return installBaseSkill(name, classFile, target);
            default:
                return fail("未知安装类型: " + type + "，可用: skill, agent, baseSkill");
        }
    }

    private TLMsg installScriptSkill(String skillDir, String targetAgent) {
        TLBaseModule agent = findAgentInstance(targetAgent);
        if (agent == null) return fail("Agent 未找到: " + targetAgent);
        TLMsg m = createMsg().setAction(AGENT_HOTLOADSKILL).setParam("skillDir", skillDir);
        m.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg result = putMsg(agent, m);
        if (result != null && result.parseBoolean(RESULT, false)) {
            return ok("Skill 已安装: " + skillDir + " → " + targetAgent);
        }
        String err = result != null ? result.getStringParam("error", "未知错误") : "无响应";
        return fail("安装失败: " + err);
    }

    private TLMsg installAgent(String agentName, String classRef, String targetAgent) {
        TLBaseModule parent = findAgentInstance(targetAgent);
        if (parent == null) return fail("Agent 未找到: " + targetAgent);
        HashMap<String, String> cfg = new HashMap<>();
        if (classRef.contains(".")) {
            cfg.put("classfile", classRef);
        } else {
            cfg.put("sameClassAs", classRef);
        }
        cfg.put("statup", "true");
        TLMsg m = createMsg().setAction(AGENT_REGISTERAGENT)
                .setParam(AI_P_AGENTNAME, agentName)
                .setParam(AI_P_AGENTCONFIG, cfg)
                .setParam(HOTLOAD_P_PERSIST, "true");
        m.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg result = putMsg(parent, m);
        if (result != null && result.parseBoolean(RESULT, false)) {
            return ok("Agent 已安装: " + agentName + " (" + classRef + ") → " + targetAgent);
        }
        String err = result != null ? result.getStringParam("error", "未知错误") : "无响应";
        return fail("安装失败: " + err);
    }

    private TLMsg installBaseSkill(String skillName, String classFile, String targetAgent) {
        TLBaseModule agent = findAgentInstance(targetAgent);
        if (agent == null) return fail("Agent 未找到: " + targetAgent);
        TLMsg m = createMsg()
                .setAction(AGENT_REGISTERSKILL)
                .setParam(MODULENAME, skillName)
                .setParam(MODULE_CLASSFILE, classFile)
                .setParam(HOTLOAD_P_PERSIST, "true");
        m.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg result = putMsg(agent, m);
        if (result != null && result.parseBoolean(RESULT, false)) {
            return ok("JavaSkill 已安装: " + skillName + " (" + classFile + ") → " + targetAgent);
        }
        String err = result != null ? result.getStringParam("error", "未知错误") : "无响应";
        return fail("安装失败: " + err);
    }

    /** 统一卸载入口 */
    private TLMsg doUninstall(Object fromWho, TLMsg msg) {
        String type = resolveType(msg.getStringParam("type", null));
        String name = msg.getStringParam("name", null);
        String target = targetAgent(msg);

        if (type == null || name == null) {
            return fail("缺少必要参数: type, name");
        }

        switch (type) {
            case "skill":    return uninstallScriptSkill(name, target);
            case "agent":    return uninstallAgent(name, target);
            case "baseSkill":return uninstallBaseSkill(name, target);
            default:         return fail("未知卸载类型: " + type + "，可用: skill, agent, baseSkill");
        }
    }

    private TLMsg uninstallScriptSkill(String skillDir, String targetAgent) {
        TLBaseModule agent;
        String shortName;
        if (skillDir.contains(":")) {
            Object[] resolved = resolveByPath(skillDir);
            if (resolved == null) return fail("路径解析失败: " + skillDir);
            agent = (TLBaseModule) resolved[0];
            shortName = (String) resolved[1];
        } else {
            agent = findAgentInstance(targetAgent);
            shortName = skillDir;
        }
        if (agent == null) return fail("Agent 未找到: " + (skillDir.contains(":") ? skillDir : targetAgent));
        TLMsg m = createMsg().setAction(AGENT_HOTUNLOADSKILL).setParam("skillDir", shortName);
        m.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg result = putMsg(agent, m);
        if (result != null && result.parseBoolean(RESULT, false)) {
            return ok("Skill 已卸载: " + shortName + " ← " + agent.getFamilyName());
        }
        String err = result != null ? result.getStringParam("error", "未知错误") : "无响应";
        return fail("卸载失败: " + err);
    }

    private TLMsg uninstallAgent(String agentName, String targetAgent) {
        TLBaseModule parent;
        String shortName;
        if (agentName.contains(":")) {
            Object[] resolved = resolveByPath(agentName);
            if (resolved == null) return fail("路径解析失败: " + agentName);
            parent = (TLBaseModule) resolved[0];
            shortName = (String) resolved[1];
        } else {
            parent = findAgentInstance(targetAgent);
            shortName = agentName;
        }
        if (parent == null) return fail("Agent 未找到: " + (agentName.contains(":") ? agentName : targetAgent));
        TLMsg m = createMsg().setAction(AGENT_UNREGISTERAGENT)
                .setParam(AI_P_AGENTNAME, shortName)
                .setParam(HOTLOAD_P_PERSIST, "true");
        m.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg result = putMsg(parent, m);
        if (result != null && result.parseBoolean(RESULT, false)) {
            return ok("Agent 已卸载: " + shortName + " ← " + parent.getFamilyName());
        }
        String err = result != null ? result.getStringParam("error", "未知错误") : "无响应";
        return fail("卸载失败: " + err);
    }

    private TLMsg uninstallBaseSkill(String skillName, String targetAgent) {
        TLBaseModule agent;
        String shortName;
        if (skillName.contains(":")) {
            Object[] resolved = resolveByPath(skillName);
            if (resolved == null) return fail("路径解析失败: " + skillName);
            agent = (TLBaseModule) resolved[0];
            shortName = (String) resolved[1];
        } else {
            agent = findAgentInstance(targetAgent);
            shortName = skillName;
        }
        if (agent == null) return fail("Agent 未找到: " + (skillName.contains(":") ? skillName : targetAgent));
        TLMsg m = createMsg()
                .setAction(AGENT_UNREGISTERSKILL)
                .setParam(AI_P_SKILLNAME, shortName)
                .setParam(HOTLOAD_P_PERSIST, "true");
        m.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg result = putMsg(agent, m);
        if (result != null && result.parseBoolean(RESULT, false)) {
            return ok("JavaSkill 已卸载: " + shortName + " ← " + agent.getFamilyName());
        }
        String err = result != null ? result.getStringParam("error", "未知错误") : "无响应";
        return fail("卸载失败: " + err);
    }

    /** 统一重载入口 */
    private TLMsg doReload(Object fromWho, TLMsg msg) {
        String type = resolveType(msg.getStringParam("type", null));
        String familyName = msg.getStringParam("name", msg.getStringParam("familyName", null));

        if (type == null || familyName == null) {
            return fail("缺少必要参数: type, name(familyName)");
        }

        Object[] resolved = resolveByPath(familyName);
        if (resolved == null) return fail("路径解析失败: " + familyName);
        TLBaseModule parent = (TLBaseModule) resolved[0];
        String shortName = (String) resolved[1];

        switch (type) {
            case "skill":    return reloadScriptSkill(parent, shortName, familyName);
            case "agent":    return reloadAgent(parent, shortName, familyName);
            case "baseSkill":return reloadBaseSkill(parent, shortName, familyName);
            default:         return fail("未知重载类型: " + type + "，可用: skill, agent, baseSkill");
        }
    }

    private TLMsg reloadScriptSkill(TLBaseModule parent, String skillDir, String path) {
        TLMsg m = createMsg().setAction(AGENT_RELOADSKILL).setParam("skillDir", skillDir);
        m.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg result = putMsg(parent, m);
        if (result != null && result.parseBoolean(RESULT, false)) {
            return ok("Skill 已重载: " + path);
        }
        String err = result != null ? result.getStringParam("error", "未知错误") : "无响应";
        return fail("重载失败: " + err);
    }

    private TLMsg reloadAgent(TLBaseModule parent, String agentName, String path) {
        TLMsg m = createMsg().setAction(AGENT_RELOADAGENT).setParam(AI_P_AGENTNAME, agentName);
        m.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg result = putMsg(parent, m);
        if (result != null && result.parseBoolean(RESULT, false)) {
            return ok("Agent 已重载: " + path);
        }
        String err = result != null ? result.getStringParam("error", "未知错误") : "无响应";
        return fail("重载失败: " + err);
    }

    private TLMsg reloadBaseSkill(TLBaseModule parent, String skillName, String path) {
        TLMsg m = createMsg().setAction(AGENT_RELOADSKILL).setParam(AI_P_SKILLNAME, skillName);
        m.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg result = putMsg(parent, m);
        if (result != null && result.parseBoolean(RESULT, false)) {
            return ok("BaseSkill 已重载: " + path);
        }
        String err = result != null ? result.getStringParam("error", "未知错误") : "无响应";
        return fail("重载失败: " + err);
    }

    // ======================== 查询 ========================

    /** 列出 Agent */
    @SuppressWarnings("unchecked")
    private TLMsg doListAgents(Object fromWho, TLMsg msg) {
        String ownerName = msg.getStringParam("filter", null);
        TLMsg listMsg = createMsg().setAction(REGISTRY_LIST);
        if (ownerName != null && !ownerName.isEmpty()) listMsg.setParam(REGISTRY_P_OWNERNAME, ownerName);

        TLMsg result = putMsg(DEFAULTMODULEREGISTRY, listMsg);
        if (result == null) return fail("moduleRegistry 未配置");
        java.util.List<Map<String, Object>> modules = (java.util.List<Map<String, Object>>) result.getParam(RESULT);
        if (modules == null || modules.isEmpty()) return ok("无 Agent", java.util.List.of());

        java.util.List<Map<String, Object>> filtered = new java.util.ArrayList<>();
        for (Map<String, Object> m : modules) {
            Object inst = m.get(INSTANCE);
            if (inst instanceof IAgentCapable) filtered.add(m);
        }
        return ok("Agent (" + filtered.size() + ")", filtered);
    }

    /** 列出 Skill（registry 格式，同 /agents 风格） */
    @SuppressWarnings("unchecked")
    private TLMsg doListSkills(Object fromWho, TLMsg msg) {
        String ownerName = msg.getStringParam("filter", null);
        TLMsg listMsg = createMsg().setAction(REGISTRY_LIST);
        if (ownerName != null && !ownerName.isEmpty()) listMsg.setParam(REGISTRY_P_OWNERNAME, ownerName);

        TLMsg result = putMsg(DEFAULTMODULEREGISTRY, listMsg);
        if (result == null) return fail("moduleRegistry 未配置");
        java.util.List<Map<String, Object>> modules = (java.util.List<Map<String, Object>>) result.getParam(RESULT);
        if (modules == null || modules.isEmpty()) return ok("无 Skill", java.util.List.of());

        java.util.List<Map<String, Object>> filtered = new java.util.ArrayList<>();
        for (Map<String, Object> m : modules) {
            Object inst = m.get(INSTANCE);
            if (inst instanceof TLBaseSkill) filtered.add(m);
        }
        return ok("Skill (" + filtered.size() + ")", filtered);
    }

    /** 列出历史会话 → SessionManager */
    private TLMsg doListSessions(Object fromWho, TLMsg msg) {
        TLMsg result = putMsg(targetSessionManager(msg), createMsg()
                .setAction("listSessions")
                .setParam("userId", msg.getStringParam("userId", null)));
        if (result == null || !result.parseBoolean(RESULT, false)) return fail("无法获取会话列表");
        java.util.List<?> sessions = result.getListParam("sessions", java.util.List.of());
        return ok("会话 (" + sessions.size() + ")", sessions);
    }

    /** 列出所有可用 action */
    private TLMsg doListCommands(Object fromWho, TLMsg msg) {
        java.util.List<Map<String, String>> cmds = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : ACTION_REGISTRY.entrySet()) {
            Map<String, String> entry = new HashMap<>();
            entry.put("action", e.getKey());
            entry.put("description", e.getValue());
            cmds.add(entry);
        }
        return ok("命令 (" + cmds.size() + ")", cmds);
    }

    // ======================== 会话管理 ========================

    /** 继续历史会话 → SessionManager */
    private TLMsg doContinueSession(Object fromWho, TLMsg msg) {
        String targetId = msg.getStringParam(AI_P_SESSIONID, null);
        String userId = msg.getStringParam("userId", null);

        TLMsg result = putMsg(targetSessionManager(msg), createMsg()
                .setAction("continueSession")
                .setParam("sessionId", targetId)
                .setParam("userId", userId)
                .setParam("currentSessionId", msg.getStringParam("currentSessionId", "")));
        if (result == null || !result.parseBoolean(RESULT, false)) {
            String err = result != null ? result.getStringParam("error", "未知") : "无响应";
            return fail("恢复失败: " + err);
        }

        // 继续会话后，由 Agent 将历史加载到内部 context
        targetId = result.getStringParam("sessionId", targetId);
        java.util.List<?> history = result.getListParam("history", java.util.List.of());
        putMsg(targetAgent(msg), createMsg()
                .setAction("loadHistory")
                .setParam(AI_P_SESSIONID, targetId)
                .setParam(AI_P_MESSAGEHISTORY, history));

        int count = result.getIntParam("count", 0);
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", targetId);
        data.put("count", count);
        data.put("history", history);
        return ok("已恢复会话 " + targetId + " (" + count + " 条)", data);
    }

    /** 恢复断点会话 → SessionManager */
    private TLMsg doResume(Object fromWho, TLMsg msg) {
        TLMsg incompleteResult = putMsg(targetSessionManager(msg), createMsg()
                .setAction("findIncomplete")
                .setParam("userId", msg.getStringParam("userId", null)));
        if (incompleteResult == null || !incompleteResult.parseBoolean(RESULT, false)) {
            return fail("没有可恢复的断点会话");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", incompleteResult.getStringParam("sessionId", ""));
        data.put("agentName", incompleteResult.getStringParam("agentName", ""));
        data.put("userMessage", incompleteResult.getStringParam("userMessage", ""));
        data.put("savedAt", incompleteResult.getLongParam("savedAt", 0L));
        data.put("iteration", incompleteResult.getIntParam("round", 0));
        return ok("找到断点会话", data);
    }

    /** 清除会话上下文 */
    private TLMsg doClearContext(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        putMsg(targetAgent(msg), createMsg().setAction(AGENT_CLEARCONTEXT)
                .setParam(AI_P_SESSIONID, sessionId));
        return ok("上下文已清除");
    }

    /** 切换会话（返回新 sessionId 给 UI 自己维护） */
    private TLMsg doSwitchSession(Object fromWho, TLMsg msg) {
        String newSessionId = msg.getStringParam(AI_P_SESSIONID, null);
        if (newSessionId == null || newSessionId.isEmpty()) return fail("sessionId 参数必填");
        return ok("会话已切换", newSessionId);
    }

    // ======================== 审批 ========================

    private TLMsg doApprove(Object fromWho, TLMsg msg) {
        String subAction = msg.getStringParam("subAction", msg.getStringParam("action2", null));
        String approvalId = msg.getStringParam("approvalId", msg.getStringParam("id", null));

        if (subAction == null || approvalId == null) {
            return fail("缺少参数: subAction(approve/reject), approvalId");
        }

        if ("approve".equals(subAction)) {
            TLMsg approveMsg = createMsg().setAction(APPROVAL_APPROVE)
                    .setParam("approvalId", approvalId);
            if (msg.containsParam("approvalModifiedArguments")) {
                approveMsg.setParam("approvalModifiedArguments", msg.getParam("approvalModifiedArguments"));
            }
            putMsg("approvalGate", approveMsg);
            return ok("已批准: " + approvalId);
        } else if ("reject".equals(subAction)) {
            String reason = msg.getStringParam("reason", msg.getStringParam("approvalRejectReason", "用户拒绝"));
            putMsg("approvalGate", createMsg().setAction(APPROVAL_REJECT)
                    .setParam("approvalId", approvalId)
                    .setParam("approvalRejectReason", reason));
            return ok("已拒绝: " + approvalId + " (" + reason + ")");
        } else {
            return fail("未知审批子操作: " + subAction + "，可用: approve, reject");
        }
    }

    // ======================== 评测 ========================

    private TLMsg doEval(Object fromWho, TLMsg msg) {
        String subAction = msg.getStringParam("subAction", msg.getStringParam("action2", null));
        if (subAction == null) {
            return fail("缺少参数: subAction (suite/list/quick/run/cascade)");
        }

        TLMsg result;
        switch (subAction) {
            case "suite":
                result = putMsg("evals", createMsg().setAction("runEvalSuite"));
                break;
            case "list":
                result = putMsg("evals", createMsg().setAction("listEvalCases"));
                break;
            case "quick":
                result = putMsg("evals", createMsg().setAction("runQuickEval"));
                break;
            case "run": {
                String caseId = msg.getStringParam("caseId", msg.getStringParam("id", null));
                if (caseId == null) return fail("run 需要 caseId 参数");
                result = putMsg("evals", createMsg().setAction("runEvalByName")
                        .setParam("caseId", caseId));
                break;
            }
            case "cascade": {
                String rootAgent = msg.getStringParam("rootAgent", msg.getStringParam("agent", null));
                TLMsg cascadeMsg = createMsg().setAction("runEvalCascade");
                if (rootAgent != null && !rootAgent.isEmpty()) cascadeMsg.setParam("rootAgent", rootAgent);
                result = putMsg("evals", cascadeMsg);
                break;
            }
            default:
                return fail("未知评测子操作: " + subAction + "，可用: suite, list, quick, run, cascade");
        }

        if (result != null && result.parseBoolean(RESULT, false)) {
            Map<String, Object> data = new HashMap<>();
            data.put("passed", result.getIntParam("passed", 0));
            data.put("failed", result.getIntParam("failed", 0));
            data.put("total", result.getIntParam("total", 0));
            data.put("passRate", result.getDoubleParam("passRate", 0.0));
            data.put("reportPath", result.getStringParam("reportPath", ""));
            return ok("评测完成: " + data.get("passed") + "/" + data.get("total") + " 通过", data);
        }
        String err = result != null ? result.getStringParam("error", "未知错误") : "无响应";
        return fail("评测失败: " + err);
    }

    // ======================== 状态查询 ========================

    private TLMsg doGetTokenUsage(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        TLMsg result = putMsg(targetAgent(msg), createMsg().setAction(AGENT_GETTOKENUSAGE)
                .setParam(AI_P_SESSIONID, sessionId));
        if (result == null) return fail("Agent 无响应");

        Map<String, Object> data = new HashMap<>();
        data.put(AI_P_PROMPTTOKENS_TOTAL, result.getIntParam(AI_P_PROMPTTOKENS_TOTAL, 0));
        data.put(AI_P_COMPLETIONTOKENS_TOTAL, result.getIntParam(AI_P_COMPLETIONTOKENS_TOTAL, 0));
        data.put(AI_P_TOTALTOKENS_TOTAL, result.getIntParam(AI_P_TOTALTOKENS_TOTAL, 0));
        return ok("Token 用量", data);
    }

    private TLMsg doGetCacheStats(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        TLMsg result = putMsg(targetAgent(msg), createMsg().setAction("getPromptCacheStats")
                .setParam(AI_P_SESSIONID, sessionId));
        if (result == null || !result.parseBoolean(RESULT, false)) return fail("无法获取缓存统计");

        Map<String, Object> data = new HashMap<>();
        data.put(AI_P_CACHEHITTOKENS_TOTAL, result.getIntParam(AI_P_CACHEHITTOKENS_TOTAL, 0));
        data.put(AI_P_CACHEMISSTOKENS_TOTAL, result.getIntParam(AI_P_CACHEMISSTOKENS_TOTAL, 0));
        data.put(AI_P_CACHECREATIONTOKENS_TOTAL, result.getIntParam(AI_P_CACHECREATIONTOKENS_TOTAL, 0));
        data.put("cacheHitRate", result.getStringParam("cacheHitRate", "0%"));
        return ok("缓存统计", data);
    }

    private TLMsg doCheckProvider(Object fromWho, TLMsg msg) {
        TLMsg result = putMsg(targetAgent(msg), createMsg().setAction(AGENT_CHECKPROVIDER));
        boolean ok = result != null && result.parseBoolean(RESULT, false);
        return ok ? this.ok("Provider 可用") : fail("Provider 不可用");
    }

    // ======================== 运行时参数 ========================

    /** 设置目标 agent 的参数（如推理模式），通过框架 MODULE_SETPARAM 消息 */
    private TLMsg doSetParam(Object fromWho, TLMsg msg) {
        String param = msg.getStringParam("param", null);
        String value = msg.getStringParam("value", null);
        if (param == null) return fail("缺少 param 参数");
        putMsg(targetAgent(msg), createMsg().setAction(MODULE_SETPARAM)
                .setParam(param, value));
        return ok(param + " = " + value);
    }

    // ======================== MCP 市场 ========================

    /**
     * /mcp search [keyword]
     * 搜索 MCP 服务器注册表。
     */
    private TLMsg doMcpSearch(Object fromWho, TLMsg msg) {
        String keyword = msg.getStringParam(AI_P_MCPKEYWORD, "");
        List<TLMcpRegistryEntry> results;
        if (keyword.isEmpty()) {
            results = new ArrayList<>(mcpRegistry.listAll());
        } else {
            results = mcpRegistry.search(keyword);
        }

        List<Map<String, String>> display = new ArrayList<>();
        for (TLMcpRegistryEntry e : results) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("package", e.getPackageName());
            item.put("name", e.getDisplayName());
            item.put("description", e.getDescription());
            item.put("category", e.getCategory());
            item.put("runtime", e.getRuntime());
            item.put("installCmd", "/mcp install " + e.getPackageName());
            if (e.getEnv() != null) item.put("env", e.getEnv());
            display.add(item);
        }
        return ok("找到 " + display.size() + " 个 MCP 服务器", display);
    }

    /**
     * /mcp install &lt;package&gt; [agentName] [--args ...]
     * 一键安装 MCP 服务器。
     */
    private TLMsg doMcpInstall(Object fromWho, TLMsg msg) {
        String packageName = msg.getStringParam(AI_P_MCPPACKAGE, null);
        if (packageName == null || packageName.isEmpty()) {
            return fail("package 参数必填，例如: /mcp install @modelcontextprotocol/server-filesystem");
        }

        String extraArgsStr = msg.getStringParam(AI_P_MCPEXTRAARGS, "");
        String[] extraArgs = extraArgsStr.isEmpty() ? new String[0] : extraArgsStr.split("\\s+");

        // Step 1: 从注册表解析包
        TLMcpRegistryEntry entry = mcpRegistry.resolve(packageName, extraArgs);
        if (entry == null) {
            return fail("无法解析 MCP 包: " + packageName
                    + "。请确认包名正确，或使用 /install -a 手动配置。");
        }

        // Step 1.5: 检测运行时是否可用
        TLMcpRuntime.CheckResult check = TLMcpRuntime.checkCommand(entry.getCommand());
        if (!check.available) {
            return fail("MCP 运行时不可用: " + entry.getCommand() + "\n" + check.hint);
        }
        // 如果检测到了变体（如 npx → npx.cmd），使用实际可用的命令
        String actualCommand = check.foundPath != null ? check.foundPath : entry.getCommand();

        // Step 2: 自动生成 agentName（如未提供）
        String agentName = msg.getStringParam(AI_P_AGENTNAME, "");
        if (agentName.isEmpty()) {
            agentName = deriveAgentName(packageName);
        }

        // Step 3: 构建 cfg map
        HashMap<String, String> cfg = new HashMap<>();
        cfg.put("sameClassAs", "mcpAgent");
        cfg.put("type", AGENT_TYPE_MCP);
        cfg.put("statup", "true");
        cfg.put("transport", entry.getTransport());
        cfg.put("command", actualCommand);

        // 将 args 列表拼成空格分隔字符串（TLMcpAgent.setModuleParams 按 \\s+ 拆分）
        StringBuilder argsBuilder = new StringBuilder();
        for (String a : entry.getArgs()) {
            if (argsBuilder.length() > 0) argsBuilder.append(" ");
            argsBuilder.append(a);
        }
        cfg.put("args", argsBuilder.toString());

        // 描述
        String desc = entry.getDescription() != null ? entry.getDescription() : entry.getPackageName();
        if (entry.getEnv() != null && !entry.getEnv().isEmpty()) {
            desc += " [环境变量: " + entry.getEnv() + "]";
        }
        cfg.put("description", desc);

        // Step 4: 委托给已有 installAgent()
        String target = targetAgent(msg);
        TLBaseModule parent = findAgentInstance(target);
        if (parent == null) return fail("Agent 未找到: " + target);

        TLMsg m = createMsg().setAction(AGENT_REGISTERAGENT)
                .setParam(AI_P_AGENTNAME, agentName)
                .setParam(AI_P_AGENTCONFIG, cfg)
                .setParam(HOTLOAD_P_PERSIST, "true");
        m.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg result = putMsg(parent, m);

        if (result != null && result.parseBoolean(RESULT, false)) {
            String cmdPreview = entry.getCommand() + " " + String.join(" ", entry.getArgs());
            StringBuilder out = new StringBuilder();
            out.append("MCP Agent 已安装: ").append(agentName).append("\n");
            out.append("  包: ").append(packageName).append("\n");
            out.append("  命令: ").append(cmdPreview).append("\n");
            out.append("  传输: ").append(entry.getTransport()).append("\n");
            out.append("  来源: ").append(entry.getCategory());
            if (entry.getEnv() != null) {
                out.append("\n  注意: 请设置环境变量 ").append(entry.getEnv());
            }
            return ok(out.toString());
        }
        String err = result != null ? result.getStringParam("error", "未知错误") : "无响应";
        return fail("MCP 安装失败: " + err);
    }

    /**
     * /mcp list
     * 列出所有 type=mcp 的已安装 Agent。
     */
    @SuppressWarnings("unchecked")
    private TLMsg doMcpList(Object fromWho, TLMsg msg) {
        String ownerName = msg.getStringParam("filter", null);
        TLMsg listMsg = createMsg().setAction(REGISTRY_LIST);
        if (ownerName != null && !ownerName.isEmpty()) listMsg.setParam(REGISTRY_P_OWNERNAME, ownerName);

        TLMsg result = putMsg(DEFAULTMODULEREGISTRY, listMsg);
        if (result == null || !result.parseBoolean(RESULT, true)) {
            return fail("无法获取已安装模块列表");
        }
        List<Map<String, Object>> modules = (List<Map<String, Object>>) result.getParam(RESULT);
        if (modules == null) modules = java.util.List.of();

        List<Map<String, Object>> mcpAgents = new ArrayList<>();
        for (Map<String, Object> mod : modules) {
            Object inst = mod.get(INSTANCE);
            if (inst instanceof TLMcpAgent) {
                TLMcpAgent mcp = (TLMcpAgent) inst;
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", mod.getOrDefault(MODULENAME, "?"));
                info.put("description", mcp.getAgentDescription());
                info.put("initialized", mcp.isInitialized());
                info.put("familyName", mod.getOrDefault(REGISTRY_P_KEY, "?"));
                mcpAgents.add(info);
            }
        }
        return ok("已安装 MCP Agent (" + mcpAgents.size() + ")", mcpAgents);
    }

    /**
     * /mcp remove &lt;name&gt;
     * 卸载 MCP Agent（复用已有 uninstallAgent）。
     */
    private TLMsg doMcpRemove(Object fromWho, TLMsg msg) {
        String agentName = msg.getStringParam(AI_P_AGENTNAME, "");
        if (agentName.isEmpty()) {
            return fail("请指定要卸载的 MCP Agent 名称: /mcp remove <name>");
        }
        String target = targetAgent(msg);
        return uninstallAgent(agentName, target);
    }

    /**
     * 从包名推导 Agent 名称。
     * "@modelcontextprotocol/server-filesystem" → "filesystemMcp"
     * "mcp-server-time" → "timeMcp"
     */
    private String deriveAgentName(String packageName) {
        String[] parts = packageName.split("[/-]");
        String last = parts[parts.length - 1];
        // 去掉 "mcp-" 或 "server-" 前缀
        last = last.replaceAll("^(mcp-|server-)", "");
        // 确保以 "Mcp" 结尾
        if (!last.endsWith("Mcp") && !last.endsWith("mcp")) {
            last = last + "Mcp";
        }
        return last;
    }
}
