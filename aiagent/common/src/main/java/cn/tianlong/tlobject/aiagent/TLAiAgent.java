package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;
import org.xmlpull.v1.XmlPullParser;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI Agent主控模块——智能体框架的核心编排器。
 * 接收用户输入，通过LLM分析意图，调用Skill执行任务，管理上下文和记忆。
 *
 * 核心职责：
 * 1. 管理LLM Provider、Context、Skill、Memory等子模块
 * 2. 实现chat()主循环：用户输入 -> LLM分析 -> Skill调用 -> 结果返回
 * 3. 支持tool-call多轮迭代（maxToolCallIterations限制）
 * 4. 管理会话生命周期
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public class TLAiAgent extends TLBaseModule implements TLAiAgentParamString {

    // ======================== 配置字段 ========================

    /** 默认LLM Provider模块名 */
    protected String defaultLlmProvider;

    /** 默认长期记忆存储模块名 */
    protected String defaultMemoryStore = M_LONGTERMMEMORY;

    /** 当前活跃的Provider实例引用 */
    protected TLLlmProvider llmProvider;

    /** 上下文模块名 */
    protected String contextModuleName = M_AICONTEXT;

    /** 最大tool-call迭代次数 */
    protected int maxToolCallIterations = 10;

    /** Skill包名前缀（用于类名解析） */
    protected String defaultSkillPackageName;

    /** Memory包名前缀 */
    protected String defaultMemoryPackageName;

    // ======================== 运行时字段 ========================

    /** 从XML <providers> 解析出的配置 */
    protected HashMap<String, HashMap<String, String>> providersConfig;

    /** 已注册的Skill：skillName → skillModule */
    protected Map<String, TLBaseSkill> skills;

    /** 已注册的Memory：storeName → memoryModule */
    protected Map<String, TLBaseMemory> memoryStores;

    /** 默认模型 */
    protected String defaultModel = "gpt-4o";

    /** 默认temperature */
    protected double defaultTemperature = 0.7;

    /** 默认maxTokens */
    protected int defaultMaxTokens = 4096;

    // ======================== Agent管理（主控模式） ========================

    /** 从XML <agents> 解析出的子Agent配置 */
    protected HashMap<String, HashMap<String, String>> agentsConfig;

    /** 已初始化的子Agent实例：agentName → agentModule */
    protected Map<String, TLBaseModule> subAgents;

    /** 是否主控模式（配置了<agents>即为true） */
    protected boolean isMaster = false;

    /** MCP tool 路由表：functionName → (agentName, toolName) */
    protected Map<String, String[]> mcpToolRoutes;

    /** LLM 可调用的预定义消息列表（从 XML msgTools 段解析） */
    protected List<TLMsg> msgTools;

    /** 当前会话 sessionId（chat 过程中自动设置，供 msgTool 注入上下文参数） */
    protected String currentSessionId;

    // ======================== Session 持久化/断点恢复 ========================

    /** 是否开启断点保存/恢复（从 XML params 读取，默认 false） */
    protected boolean enableCheckpoint = false;

    /** 启动时检查 LLM Provider 是否可用（默认 false） */
    protected boolean checkProviderOnStartup = false;

    /** 会话检查点文件存储路径 */
    protected String sessionStorePath = "./data/session_store/";

    /** JSON 序列化（复用 Gson，aiagent 已依赖） */
    protected com.google.gson.Gson gson;

    // ======================== 构造函数 ========================

    public TLAiAgent() {
        super();
    }

    public TLAiAgent(String name) {
        super(name);
    }

    public TLAiAgent(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    // ======================== 生命周期 ========================

    @Override
    protected Object setConfig() {
        myConfig config = new myConfig(configFile, moduleFactory.getConfigDir());
        mconfig = config;
        super.setConfig();
        providersConfig = config.getProviders();
        msgTools = config.getMsgTools();
        return config;
    }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("defaultLlmProvider") != null)
                defaultLlmProvider = params.get("defaultLlmProvider");
            if (params.get("defaultMemoryStore") != null)
                defaultMemoryStore = params.get("defaultMemoryStore");
            if (params.get("contextModuleName") != null)
                contextModuleName = params.get("contextModuleName");
            if (params.get("maxToolCallIterations") != null) {
                try { maxToolCallIterations = Integer.parseInt(params.get("maxToolCallIterations")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("defaultSkillPackageName") != null)
                defaultSkillPackageName = params.get("defaultSkillPackageName");
            if (params.get("defaultMemoryPackageName") != null)
                defaultMemoryPackageName = params.get("defaultMemoryPackageName");
            if (params.get("defaultModel") != null)
                defaultModel = params.get("defaultModel");
            if (params.get("defaultTemperature") != null) {
                try { defaultTemperature = Double.parseDouble(params.get("defaultTemperature")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("defaultMaxTokens") != null) {
                try { defaultMaxTokens = Integer.parseInt(params.get("defaultMaxTokens")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("enableCheckpoint") != null)
                enableCheckpoint = "true".equals(params.get("enableCheckpoint"));
            if (params.get("sessionStorePath") != null)
                sessionStorePath = params.get("sessionStorePath");
            if (params.get("checkProviderOnStartup") != null)
                checkProviderOnStartup = "true".equals(params.get("checkProviderOnStartup"));
        }
    }

    @Override
    protected TLBaseModule init() {
        skills = new ConcurrentHashMap<>();
        memoryStores = new ConcurrentHashMap<>();
        gson = new com.google.gson.GsonBuilder().create();
        return this;
    }

    @Override
    public void runStartMsg() {
        System.out.println("=== [TLAiAgent] runStartMsg name=" + name + " configFile=" + configFile + " ===");
        // 1. 加载 LLM Provider（每个 Agent 独立实例）
        initProvider();
        // 2. 启动时检查 LLM Provider 连通性（失败则中断启动）
        if (checkProviderOnStartup && !checkProvider()) {
            return;
        }
        // 3. 加载 Skills / Memory / Agents
        initSkills();
        initMemoryStores();
        initAgents();
        super.runStartMsg();
        // 4. 启动后自动恢复持久化的会话 / 断点
        if (enableCheckpoint) {
            restoreSessions();
            autoResumeCheckpoints();
        }
    }

    /**
     * 初始化 LLM Provider。每个 Agent 用 getNewModule 创建独立实例，
     * 模块名 = {agentName}_{providerName}，避免多 Agent 共享单例导致配置互相覆盖。
     */
    protected void initProvider() {
        if (defaultLlmProvider == null || defaultLlmProvider.isEmpty()) return;

        HashMap<String, String> providerParams = new HashMap<>();
        if (providersConfig != null) {
            HashMap<String, String> pConf = providersConfig.get(defaultLlmProvider);
            if (pConf != null) {
                providerParams.putAll(pConf);
            }
        }

        try {
            TLBaseModule module = (TLBaseModule) getNewModule(defaultLlmProvider, providerParams);
            if (module instanceof TLLlmProvider) {
                llmProvider = (TLLlmProvider) module;
                putLog("LLM Provider initialized: " + defaultLlmProvider
                        + " model=" + llmProvider.getDefaultModel(), LogLevel.DEBUG);
            } else {
                putLog("Provider not TLLlmProvider: " + defaultLlmProvider, LogLevel.ERROR);
            }
        } catch (Exception e) {
            putLog("Failed to init provider: " + e.toString(), LogLevel.ERROR);
        }
    }

    /**
     * 初始化Skills（从config或运行时注册）。
     * 框架自动根据classfile解析类名，无需手动addPackage。
     */
    protected void initSkills() {
        myConfig config = (myConfig) mconfig;
        if (config == null || config.getSkills() == null) return;

        HashMap<String, HashMap<String, String>> skillConfigs = config.getSkills();
        for (String skillName : skillConfigs.keySet()) {
            HashMap<String, String> skillParams = skillConfigs.get(skillName);
            boolean startup = TLDataUtils.parseBoolean(skillParams.get("statup"), true);
            if (!startup) continue;

            // 优先用 classfile，没有则用 sameClassAs 引用的 classfile，都没有则让工厂按名查找
            String classfile = skillParams.get(MODULE_CLASSFILE);
            if ((classfile == null || classfile.isEmpty()) && skillParams.containsKey(MODULE_SameClassAs)) {
                HashMap<String, String> ref = skillConfigs.get(skillParams.get(MODULE_SameClassAs));
                if (ref != null) classfile = ref.get(MODULE_CLASSFILE);
            }

            try {
                TLBaseModule module = classfile != null && !classfile.isEmpty()
                        ? (TLBaseModule) getNewModule(skillName, classfile, skillParams)
                        : (TLBaseModule) getNewModule(skillName, skillParams);
                if (module instanceof TLBaseSkill) {
                    skills.put(((TLBaseSkill) module).getSkillName(), (TLBaseSkill) module);
                    modules.put(skillName, module);
                    putLog("Skill registered: " + skillName, LogLevel.DEBUG);
                }
            } catch (Exception e) {
                putLog("Failed to init skill: " + skillName + " error: " + e.toString(), LogLevel.ERROR);
            }
        }
    }

    /**
     * 初始化Memory Stores。
     * 框架自动根据classfile解析类名，自动注入agentNamespace用于多Agent记忆隔离。
     */
    protected void initMemoryStores() {
        myConfig config = (myConfig) mconfig;
        if (config == null || config.getMemoryStores() == null) return;

        String namespace = params != null ? params.get("agentNamespace") : null;

        HashMap<String, HashMap<String, String>> memoryConfigs = config.getMemoryStores();
        for (String storeName : memoryConfigs.keySet()) {
            HashMap<String, String> storeParams = memoryConfigs.get(storeName);
            boolean startup = TLDataUtils.parseBoolean(storeParams.get("statup"), true);
            if (!startup) continue;

            // 优先用 classfile，没有则用 sameClassAs 引用的 classfile，都没有则让工厂按名查找
            String classfile = storeParams.get(MODULE_CLASSFILE);
            if ((classfile == null || classfile.isEmpty()) && storeParams.containsKey(MODULE_SameClassAs)) {
                HashMap<String, String> ref = memoryConfigs.get(storeParams.get(MODULE_SameClassAs));
                if (ref != null) classfile = ref.get(MODULE_CLASSFILE);
            }

            if (namespace != null && !namespace.isEmpty()) {
                storeParams.putIfAbsent("agentNamespace", namespace);
            }
            try {
                TLBaseModule module = classfile != null && !classfile.isEmpty()
                        ? (TLBaseModule) getNewModule(storeName, classfile, storeParams)
                        : (TLBaseModule) getNewModule(storeName, storeParams);
                if (module instanceof TLBaseMemory) {
                    memoryStores.put(storeName, (TLBaseMemory) module);
                    modules.put(storeName, module);
                    putLog("Memory store registered: " + storeName, LogLevel.DEBUG);
                }
            } catch (Exception e) {
                putLog("Failed to init memory store: " + storeName + " error: " + e.toString(), LogLevel.ERROR);
            }
        }
    }

    /**
     * 初始化子Agent（仅主控模式）。
     * 读取<agents>配置，根据 type 参数创建不同类型的 Agent：
     * - type="mcp" → TLMcpAgent（无 LLM，纯协议转发）
     * - type 缺省/"agent" → TLAiAgent（有 LLM，标准子Agent）
     * 框架自动加载 {agentName}_config.xml 作为配置文件。
     */
    protected void initAgents() {
        myConfig config = (myConfig) mconfig;
        if (config == null) return;
        agentsConfig = config.getAgents();
        System.out.println("=== [initAgents] configFile=" + configFile
                + " agentsConfig=" + (agentsConfig != null ? agentsConfig.size() + " entries" : "null") + " ===");
        if (agentsConfig == null || agentsConfig.isEmpty()) return;

        isMaster = true;
        subAgents = new ConcurrentHashMap<>();
        mcpToolRoutes = new ConcurrentHashMap<>();

        for (String agentName : agentsConfig.keySet()) {
            HashMap<String, String> agentCfg = agentsConfig.get(agentName);
            boolean startup = TLDataUtils.parseBoolean(agentCfg.get("statup"), true);
            if (!startup) continue;

            String type = agentCfg.getOrDefault(AI_P_AGENTTYPE, AGENT_TYPE_AGENT);

            try {
                // sameClassAs：Agent局部引用，从同段config中查找classfile
                String classfile = agentCfg.get(MODULE_CLASSFILE);
                String sameClassAs = agentCfg.get(MODULE_SameClassAs);
                if ((classfile == null || classfile.isEmpty()) && sameClassAs != null) {
                    HashMap<String, String> refParams = agentsConfig.get(sameClassAs);
                    if (refParams != null) {
                        classfile = refParams.get(MODULE_CLASSFILE);
                    }
                }

                if (AGENT_TYPE_GROUP.equals(type)) {
                    // Group Agent：不创建实例，members 已在 agentsConfig 中
                    putLog("Group agent registered: " + agentName + " members=" + agentCfg.get("members"), LogLevel.DEBUG);
                } else if (AGENT_TYPE_MCP.equals(type)) {
                    if (classfile == null || classfile.isEmpty()) {
                        putLog("MCP Agent missing classfile/sameClassAs: " + agentName, LogLevel.ERROR);
                        continue;
                    }
                    TLBaseModule module = (TLBaseModule) getNewModule(agentName, classfile, agentCfg);
                    subAgents.put(agentName, module);
                    modules.put(agentName, module);
                    putLog("MCP Agent initialized: " + agentName, LogLevel.DEBUG);
                } else {
                    agentCfg.putIfAbsent("configFile", agentName + "_config.xml");
                    // classfile/sameClassAs 优先，否则用 defaultAgentTemplate（默认 "aiagent"）
                    String template = (classfile != null && !classfile.isEmpty()) ? classfile
                            : (params != null ? params.getOrDefault("defaultAgentTemplate", "aiagent") : "aiagent");
                    TLBaseModule module = (TLBaseModule) getNewModule(agentName, template, agentCfg);
                    // 接受 TLAiAgent、TLAgentGroup 等任何 TLBaseModule
                    subAgents.put(agentName, module);
                    modules.put(agentName, module);
                    putLog("Sub-agent initialized: " + agentName + " (" + module.getClass().getSimpleName() + ")", LogLevel.DEBUG);
                }
            } catch (Exception e) {
                putLog("Failed to init sub-agent: " + agentName + " error: " + e.toString(), LogLevel.ERROR);
            }
        }

        int agentCount = subAgents.size();
        // 计算 group 数量
        for (String name : agentsConfig.keySet()) {
            String t = agentsConfig.get(name).getOrDefault(AI_P_AGENTTYPE, AGENT_TYPE_AGENT);
            if (AGENT_TYPE_GROUP.equals(t) && !subAgents.containsKey(name)) agentCount++;
        }
        System.out.println("★★★ 主控Agent模式已激活, Agent数量: " + agentCount + " ★★★");
        for (String name : agentsConfig.keySet()) {
            String agentType = agentsConfig.get(name).getOrDefault(AI_P_AGENTTYPE, AGENT_TYPE_AGENT);
            if (AGENT_TYPE_GROUP.equals(agentType)) {
                String members = agentsConfig.get(name).getOrDefault("members", "");
                System.out.println("  ▸ Group: delegate_to_" + name + " → [" + members + "]");
            } else if (subAgents.containsKey(name)) {
                if (AGENT_TYPE_MCP.equals(agentType))
                    System.out.println("  ▸ MCP Agent: " + name);
                else
                    System.out.println("  ▸ 子Agent: delegate_to_" + name);
            }
        }
    }

    // ======================== 消息分发 ========================

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case AGENT_CHAT:
                returnMsg = chat(fromWho, msg);
                break;
            case AGENT_CHATSTREAM:
                returnMsg = chatStream(fromWho, msg);
                break;
            case "chatStreamSync":
                returnMsg = doChat(fromWho, msg, true);
                break;
            case AGENT_REGISTERSKILL:
                returnMsg = registerSkill(fromWho, msg);
                break;
            case AGENT_UNREGISTERSKILL:
                returnMsg = unregisterSkill(fromWho, msg);
                break;
            case AGENT_LISTSKILLS:
                returnMsg = listSkills(fromWho, msg);
                break;
            case AGENT_GETCONTEXT:
                returnMsg = getAgentContext(fromWho, msg);
                break;
            case AGENT_CLEARCONTEXT:
                returnMsg = clearAgentContext(fromWho, msg);
                break;
            case AGENT_SAVEMEMORY:
                returnMsg = saveAgentMemory(fromWho, msg);
                break;
            case AGENT_RECALLMEMORY:
                returnMsg = recallAgentMemory(fromWho, msg);
                break;
            case AGENT_SETSYSTEMMSG:
                returnMsg = setSystemMsg(fromWho, msg);
                break;
            case AGENT_SETPROVIDER:
                returnMsg = setLlmProvider(fromWho, msg);
                break;
            case AGENT_REGISTERAGENT:
                returnMsg = registerAgent(fromWho, msg);
                break;
            case AGENT_UNREGISTERAGENT:
                returnMsg = unregisterAgent(fromWho, msg);
                break;
            case AGENT_LISTAGENTS:
                returnMsg = listAgents(fromWho, msg);
                break;
            case "onStreamResult":
                returnMsg = onStreamResult(fromWho, msg);
                break;
            default:
                returnMsg = null;
        }
        return returnMsg;
    }

    // ======================== 核心：Chat循环 ========================

    /**
     * 完整的chat循环：
     * 1. 获取/构建上下文
     * 2. 收集skill function定义
     * 3. 发送到LLM
     * 4. 如果有tool_calls → 执行skills → 结果送回LLM（循环）
     * 5. 返回最终文本响应
     */
    @SuppressWarnings("unchecked")
    protected TLMsg chat(Object fromWho, TLMsg msg) {
        return doChat(fromWho, msg, false);
    }

    /**
     * 统一chat实现。stream=true时LLM请求走流式(无tool-call循环)，
     * stream=false时走非流式(支持tool-call多轮迭代)。
     * 记忆召回、上下文、长期记忆保存等生命周期处理完全相同。
     */
    @SuppressWarnings("unchecked")
    protected TLMsg doChat(Object fromWho, TLMsg msg, boolean stream) {
        // 最优先检查 Provider 可用性
        if (llmProvider == null) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, "LLM Provider 未就绪，请检查 API Key 和余额后重启");
        }
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        currentSessionId = sessionId;
        String userMessage = msg.getStringParam(AI_P_USERMESSAGE, "");
        String model = msg.getStringParam(AI_P_MODEL, llmProvider.getDefaultModel());
        double temperature = msg.getDoubleParam(AI_P_TEMPERATURE, defaultTemperature);
        int maxTokens = msg.getIntParam(AI_P_MAXTOKENS, defaultMaxTokens);

        if (userMessage.isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, "Error: missing userMessage");
        }

        putLog("Chat start: sessionId=" + sessionId + " stream=" + stream, LogLevel.DEBUG);
        try {
            // ==== 预处理: 记忆召回 + 上下文 ====
            TLMsg beforeResult = (TLMsg) msg.getSystemParam(PRERESULT);
            String memoryContext = null;
            if (beforeResult != null && beforeResult.containsParam(AI_P_MEMORYRESULT)) {
                List<TLMemoryEntry> entries = (List<TLMemoryEntry>)
                        beforeResult.getListParam(AI_P_MEMORYRESULT, null);
                if (entries != null && !entries.isEmpty()) {
                    StringBuilder ctx = new StringBuilder("相关历史记忆:\n");
                    for (TLMemoryEntry e : entries) ctx.append("- ").append(e.getValue()).append("\n");
                    memoryContext = ctx.toString();
                }
            }

            // ==== 断点恢复检查 ====
            boolean resume = msg.parseBoolean("resume", enableCheckpoint);
            TLMsg checkpoint = resume ? loadSessionCheckpoint(sessionId) : null;
            List<TLConversationHistory> history;
            int iteration = 0;
            boolean resumedFromCheckpoint = false;

            if (checkpoint != null && SESSION_STATE_CHECKPOINT.equals(checkpoint.getStringParam("state", ""))) {
                // L2: 从 mid-loop 断点恢复，跳过预处理
                history = (List<TLConversationHistory>) checkpoint.getParam("history");
                iteration = checkpoint.getIntParam("iteration", 0);
                if (checkpoint.containsParam("model"))
                    model = checkpoint.getStringParam("model", model);
                temperature = checkpoint.getDoubleParam("temperature", temperature);
                maxTokens = checkpoint.getIntParam("maxTokens", maxTokens);
                resumedFromCheckpoint = true;
                putLog("Resumed from checkpoint: sessionId=" + sessionId + " iter=" + iteration
                        + " historySize=" + (history != null ? history.size() : 0), LogLevel.INFO);
            } else {
                // 正常流程 / L1 恢复
                if (checkpoint != null && SESSION_STATE_COMPLETED.equals(checkpoint.getStringParam("state", ""))) {
                    history = (List<TLConversationHistory>) checkpoint.getParam("history");
                    putLog("Restored completed session: " + sessionId, LogLevel.DEBUG);
                } else {
                    history = getContextHistory(sessionId);
                }
                if (memoryContext != null && !memoryContext.isEmpty()) {
                    history.add(new TLConversationHistory(TLConversationHistory.Role.system, memoryContext));
                }
                history.add(new TLConversationHistory(TLConversationHistory.Role.user, userMessage));
            }
            List<TLFunctionDefinition> toolDefs = buildFunctionDefinitions();

            // ==== LLM请求 ====
            String finalResponse;

            if (stream) {
                // 流式: 单次请求，无tool-call循环
                finalResponse = doStreamCall(history, toolDefs, sessionId, model);
                if (finalResponse != null) {
                    history.add(new TLConversationHistory(TLConversationHistory.Role.assistant, finalResponse));
                }
            } else {
                // 非流式: tool-call循环
                finalResponse = null;
                while (iteration < maxToolCallIterations) {
                    iteration++;
                    // L2 checkpoint: 每次迭代前保存（覆盖 LLM 直接返回 / 中途中断等所有场景）
                    if (enableCheckpoint) {
                        persistSession(sessionId, history, SESSION_STATE_CHECKPOINT,
                                iteration, model, temperature, maxTokens, userMessage);
                    }
                    TLMsg llmMsg = createMsg().setAction(LLM_COMPLETION)
                            .setParam(AI_P_MESSAGEHISTORY, history).setParam(AI_P_MODEL, model)
                            .setParam(AI_P_TEMPERATURE, temperature).setParam(AI_P_MAXTOKENS, maxTokens);
                    if (toolDefs != null && !toolDefs.isEmpty()) {
                        llmMsg.setParam(AI_P_FUNCTIONDEFS, new ArrayList<>(toolDefs));
                    }

                    TLMsg llmResponse = putMsg(llmProvider, llmMsg);
                    if (!llmResponse.parseBoolean(RESULT, false)) {
                        int hs = llmResponse.getIntParam(AI_P_HTTPSTATUS, 0);
                        String body = llmResponse.getStringParam(AI_P_RESPONSEBODY, "");
                        return createMsg().setParam(RESULT, false)
                                .setParam(AI_P_RESPONSE, "Error: HTTP " + hs + " body=" + body);
                    }

                    boolean hasToolCalls = llmResponse.parseBoolean("hasToolCalls", false);
                    List<TLToolCall> toolCalls = (List<TLToolCall>) llmResponse.getListParam(AI_P_TOOLCALLS, null);

                    if (!hasToolCalls || toolCalls == null || toolCalls.isEmpty()) {
                        finalResponse = llmResponse.getStringParam(AI_P_RESPONSE, "");
                        history.add(new TLConversationHistory(TLConversationHistory.Role.assistant, finalResponse));
                        break;
                    }

                    putLog("Tool calls: " + toolCalls.size() + " (iter " + iteration + ")", LogLevel.DEBUG);
                    TLConversationHistory aMsg = new TLConversationHistory(
                            TLConversationHistory.Role.assistant, new ArrayList<>(toolCalls));
                    if (llmResponse.containsParam(AI_P_RESPONSE))
                        aMsg.setContent(llmResponse.getStringParam(AI_P_RESPONSE, null));
                    history.add(aMsg);

                    for (TLToolCall tc : toolCalls) {
                        TLMsg tr = executeToolCall(tc, fromWho);
                        history.add(new TLConversationHistory(tc.getId(), tc.getFunctionName(),
                                tr.getStringParam(AI_P_SKILLOUTPUT, tr.getStringParam("error", ""))));
                    }
                    // L2 checkpoint: 每轮工具调用后保存断点
                    if (enableCheckpoint) {
                        persistSession(sessionId, history, SESSION_STATE_CHECKPOINT,
                                iteration, model, temperature, maxTokens, userMessage);
                    }
                }
                if (finalResponse == null) finalResponse = "Reached max iterations (" + maxToolCallIterations + ")";
            }

            // ==== 后处理: 保存上下文 + 长期记忆 + L1持久化 ====
            saveContextHistory(sessionId, history);
            if (enableCheckpoint) {
                persistSession(sessionId, history, SESSION_STATE_COMPLETED,
                        iteration, model, temperature, maxTokens, userMessage);
            }
            try {
                TLMsg saveMsg = createMsg().setAction(AGENT_SAVEMEMORY)
                        .setParam(AI_P_SESSIONID, sessionId).setParam("storeName", defaultMemoryStore)
                        .setParam(AI_P_MEMORYKEY, "chat_" + System.currentTimeMillis())
                        .setParam(AI_P_MEMORYVALUE, userMessage + " → " + finalResponse)
                        .setParam(AI_P_MEMORYTAG, "chat_history");
                saveAgentMemory(fromWho, saveMsg);
            } catch (Exception e) {
                putLog("Save memory failed: " + e.toString(), LogLevel.ERROR);
            }

            putLog("Chat completed: sessionId=" + sessionId, LogLevel.DEBUG);
            // 输出护栏：检查并净化回复
            finalResponse = guardOutput(finalResponse);
            return createMsg().setParam(RESULT, true).setParam(AI_P_RESPONSE, finalResponse)
                    .setParam(AI_P_SESSIONID, sessionId).setParam("iterations", iteration);

        } catch (Exception e) {
            putLog("Chat error: " + e.toString(), LogLevel.ERROR);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, "Agent error: " + e.getMessage());
        }
    }

    /** 流式LLM调用——使用配置文件中注册的streamCallback模块 */
    private String doStreamCall(List<TLConversationHistory> history,
                                 List<TLFunctionDefinition> toolDefs,
                                 String sessionId, String model) {
        TLBaseModule cb = getModule("streamCallback") instanceof TLBaseModule
                ? (TLBaseModule) getModule("streamCallback") : null;
        if (cb == null) return null;

        putMsg(cb, createMsg().setAction(STREAM_RESET));
        TLMsg sm = createMsg().setAction(LLM_COMPLETIONSTREAM)
                .setParam(AI_P_MESSAGEHISTORY, history).setParam(AI_P_FUNCTIONDEFS, toolDefs)
                .setParam(AI_P_MODEL, model).setParam(AI_P_SESSIONID, sessionId)
                .setParam(RESULTFOR, "streamCallback").setParam(RESULTACTION, STREAM_ONCHUNK);
        putMsg(llmProvider, sm);

        TLMsg wr = putMsg(cb, createMsg().setAction(STREAM_WAITFORSTREAM).setParam("timeout", 120));
        return wr.getStringParam("content", null);
    }

    /**
     * 流式chat（异步，通过回调发送chunks）。
     * 直接将调用者的回调目标传给Provider，避免中间转发。
     */
    @SuppressWarnings("unchecked")
    protected TLMsg chatStream(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        String userMessage = msg.getStringParam(AI_P_USERMESSAGE, "");
        String resultFor = msg.getStringParam(RESULTFOR,
                fromWho instanceof String ? (String) fromWho : "caller");
        String resultAction = msg.getStringParam(RESULTACTION, "onStreamChunk");

        if (llmProvider == null) {
            TLMsg errMsg = createMsg().setAction(resultAction)
                    .setParam(AI_P_STREAMERROR, "No LLM provider configured");
            putMsg(resultFor, errMsg);
            return null;
        }

        // 获取上下文并构建流式请求
        List<TLConversationHistory> history = getContextHistory(sessionId);
        history.add(new TLConversationHistory(TLConversationHistory.Role.user, userMessage));
        List<TLFunctionDefinition> toolDefs = buildFunctionDefinitions();

        // 直接让Provider回调到最终目标
        TLMsg streamMsg = createMsg()
                .setAction(LLM_COMPLETIONSTREAM)
                .setParam(AI_P_MESSAGEHISTORY, history)
                .setParam(AI_P_FUNCTIONDEFS, toolDefs)
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam(RESULTFOR, resultFor)
                .setParam(RESULTACTION, resultAction);

        if (msg.containsParam(AI_P_MODEL))
            streamMsg.setParam(AI_P_MODEL, msg.getParam(AI_P_MODEL));
        if (msg.containsParam(AI_P_TEMPERATURE))
            streamMsg.setParam(AI_P_TEMPERATURE, msg.getParam(AI_P_TEMPERATURE));

        putMsg(llmProvider, streamMsg);
        return null; // 异步
    }

    /**
     * 处理流式LLM响应结果（回调）。
     * 当流式响应包含tool_calls时，自动执行skills并继续chat循环，
     * 最终将完整的文本响应转发给原始调用者。
     */
    @SuppressWarnings("unchecked")
    protected TLMsg onStreamResult(Object fromWho, TLMsg msg) {
        String resultFor = msg.getStringParam("_streamResultFor", "caller");
        String resultAction = msg.getStringParam("_streamResultAction", "onStreamChunk");
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");

        // 转发chunk或完成信号给原始调用者
        if (msg.parseBoolean(AI_P_STREAMDONE, false)) {
            String streamedText = msg.getStringParam(AI_P_RESPONSE, "");
            boolean hasToolCalls = msg.parseBoolean("hasToolCalls", false);
            List<TLToolCall> toolCalls = (List<TLToolCall>) msg.getListParam(AI_P_TOOLCALLS, null);

            if (hasToolCalls && toolCalls != null && !toolCalls.isEmpty()) {
                // 流式响应包含tool calls: 执行skills并继续chat循环
                try {
                    List<TLConversationHistory> history = getContextHistory(sessionId);

                    // 添加assistant消息（流式文本 + tool calls）
                    TLConversationHistory aMsg = new TLConversationHistory(
                            TLConversationHistory.Role.assistant, new ArrayList<>(toolCalls));
                    if (streamedText != null && !streamedText.isEmpty()) {
                        aMsg.setContent(streamedText);
                    }
                    history.add(aMsg);

                    // 执行tool calls
                    for (TLToolCall tc : toolCalls) {
                        TLMsg tr = executeToolCall(tc, fromWho);
                        history.add(new TLConversationHistory(tc.getId(), tc.getFunctionName(),
                                tr.getStringParam(AI_P_SKILLOUTPUT, tr.getStringParam("error", ""))));
                    }

                    // 非流式继续LLM循环（支持后续tool calls）
                    String finalResponse = null;
                    int iteration = 1;
                    while (iteration < maxToolCallIterations) {
                        iteration++;
                        List<TLFunctionDefinition> toolDefs = buildFunctionDefinitions();
                        TLMsg llmMsg = createMsg().setAction(LLM_COMPLETION)
                                .setParam(AI_P_MESSAGEHISTORY, history)
                                .setParam(AI_P_MODEL, msg.getStringParam(AI_P_MODEL, llmProvider.getDefaultModel()))
                                .setParam(AI_P_TEMPERATURE, defaultTemperature)
                                .setParam(AI_P_MAXTOKENS, defaultMaxTokens);
                        if (toolDefs != null && !toolDefs.isEmpty()) {
                            llmMsg.setParam(AI_P_FUNCTIONDEFS, new ArrayList<>(toolDefs));
                        }

                        TLMsg llmResponse = putMsg(llmProvider, llmMsg);
                        if (!llmResponse.parseBoolean(RESULT, false)) break;

                        boolean moreToolCalls = llmResponse.parseBoolean("hasToolCalls", false);
                        List<TLToolCall> moreTCs = (List<TLToolCall>) llmResponse.getListParam(AI_P_TOOLCALLS, null);

                        if (!moreToolCalls || moreTCs == null || moreTCs.isEmpty()) {
                            finalResponse = llmResponse.getStringParam(AI_P_RESPONSE, "");
                            history.add(new TLConversationHistory(TLConversationHistory.Role.assistant, finalResponse));
                            break;
                        }

                        TLConversationHistory aMsg2 = new TLConversationHistory(
                                TLConversationHistory.Role.assistant, new ArrayList<>(moreTCs));
                        if (llmResponse.containsParam(AI_P_RESPONSE))
                            aMsg2.setContent(llmResponse.getStringParam(AI_P_RESPONSE, null));
                        history.add(aMsg2);

                        for (TLToolCall tc : moreTCs) {
                            TLMsg tr = executeToolCall(tc, fromWho);
                            history.add(new TLConversationHistory(tc.getId(), tc.getFunctionName(),
                                    tr.getStringParam(AI_P_SKILLOUTPUT, tr.getStringParam("error", ""))));
                        }
                    }
                    if (finalResponse == null) finalResponse = "Reached max iterations (" + maxToolCallIterations + ")";

                    // 保存上下文和长期记忆
                    saveContextHistory(sessionId, history);
                    try {
                        TLMsg saveMsg = createMsg().setAction(AGENT_SAVEMEMORY)
                                .setParam(AI_P_SESSIONID, sessionId).setParam("storeName", defaultMemoryStore)
                                .setParam(AI_P_MEMORYKEY, "chat_" + System.currentTimeMillis())
                                .setParam(AI_P_MEMORYVALUE, streamedText + " → " + finalResponse)
                                .setParam(AI_P_MEMORYTAG, "chat_history");
                        saveAgentMemory(fromWho, saveMsg);
                    } catch (Exception e) {
                        putLog("Save memory failed: " + e.toString(), LogLevel.ERROR);
                    }

                    // 转发最终完成信号
                    TLMsg doneMsg = createMsg()
                            .setAction(resultAction)
                            .setParam(AI_P_STREAMDONE, true)
                            .setParam(AI_P_RESPONSE, streamedText + finalResponse)
                            .setParam(AI_P_SESSIONID, sessionId);
                    putMsg(resultFor, doneMsg);

                } catch (Exception e) {
                    putLog("Stream tool call continuation error: " + e.toString(), LogLevel.ERROR);
                    TLMsg errMsg = createMsg()
                            .setAction(resultAction)
                            .setParam(AI_P_STREAMERROR, "Tool call processing error: " + e.getMessage());
                    putMsg(resultFor, errMsg);
                }
            } else {
                // 无tool calls: 直接转发完成信号
                TLMsg doneMsg = createMsg()
                        .setAction(resultAction)
                        .setParam(AI_P_STREAMDONE, true)
                        .setParam(AI_P_RESPONSE, streamedText)
                        .setParam(AI_P_SESSIONID, sessionId);
                putMsg(resultFor, doneMsg);
            }
        } else if (msg.containsParam(AI_P_STREAMERROR)) {
            TLMsg errMsg = createMsg()
                    .setAction(resultAction)
                    .setParam(AI_P_STREAMERROR, msg.getParam(AI_P_STREAMERROR));
            putMsg(resultFor, errMsg);
        } else if (msg.containsParam(AI_P_CHUNK)) {
            // 转发chunk
            TLMsg chunkMsg = createMsg()
                    .setAction(resultAction)
                    .setParam(AI_P_CHUNK, msg.getParam(AI_P_CHUNK))
                    .setParam(AI_P_SESSIONID, sessionId);
            putMsg(resultFor, chunkMsg);
        }

        return null;
    }

    // ======================== Skill管理 ========================

    protected synchronized TLMsg registerSkill(Object fromWho, TLMsg msg) {
        // 支持通过instance直接注册
        TLBaseSkill instance = (TLBaseSkill) msg.getParam(INSTANCE, TLBaseSkill.class);
        if (instance != null) {
            skills.put(instance.getSkillName(), instance);
            modules.put(instance.getName(), instance);
            putLog("Skill registered by instance: " + instance.getSkillName(), LogLevel.DEBUG);
            return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLNAME, instance.getSkillName());
        }

        // 通过模块名和类名注册
        String skillModuleName = msg.getStringParam(MODULENAME, "");
        String classfile = msg.getStringParam(MODULE_CLASSFILE, "");
        HashMap<String, String> skillParams = new HashMap<>(msg.getMapParam(MODULE_PARAMS, new HashMap<>()));

        if (!skillModuleName.isEmpty() && !classfile.isEmpty()) {
            TLBaseModule module = (TLBaseModule) getNewModule(skillModuleName, classfile, skillParams);
            if (module instanceof TLBaseSkill) {
                TLBaseSkill skill = (TLBaseSkill) module;
                skills.put(skill.getSkillName(), skill);
                modules.put(skillModuleName, skill);
                return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLNAME, skill.getSkillName());
            }
        }

        return createMsg().setParam(RESULT, false).setParam("error", "Invalid skill registration");
    }

    protected synchronized TLMsg unregisterSkill(Object fromWho, TLMsg msg) {
        String skillName = msg.getStringParam(AI_P_SKILLNAME, "");
        if (!skillName.isEmpty()) {
            TLBaseSkill removed = skills.remove(skillName);
            modules.remove(skillName);
            return createMsg().setParam(RESULT, removed != null);
        }
        return createMsg().setParam(RESULT, false).setParam("error", "skillName required");
    }

    protected TLMsg listSkills(Object fromWho, TLMsg msg) {
        List<Map<String, Object>> skillInfos = new ArrayList<>();
        for (TLBaseSkill skill : skills.values()) {
            if (!skill.isEnabled()) continue;
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", skill.getSkillName());
            info.put("description", skill.getSkillDescription());
            info.put("moduleName", skill.getName());
            skillInfos.add(info);
        }
        return createMsg().setParam(RESULT, true).setParam("skills", skillInfos);
    }

    // ======================== Agent管理 ========================

    protected synchronized TLMsg registerAgent(Object fromWho, TLMsg msg) {
        String agentName = msg.getStringParam(AI_P_AGENTNAME, "");
        String description = msg.getStringParam(AI_P_AGENTDESCRIPTION, agentName);

        if (agentName.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "agentName required");
        }

        try {
            // 基于aiagent模板创建，configFile自动推导为 {agentName}_config.xml
            HashMap<String, String> params = new HashMap<>();
            params.put("configFile", agentName + "_config.xml");
            TLBaseModule module = (TLBaseModule) getNewModule(agentName, "aiagent", params);
            if (module instanceof TLAiAgent) {
                if (subAgents == null) {
                    subAgents = new ConcurrentHashMap<>();
                    isMaster = true;
                }
                subAgents.put(agentName, module);
                modules.put(agentName, module);
                // 记录描述信息，供buildFunctionDefinitions()使用
                if (agentsConfig == null) agentsConfig = new HashMap<>();
                HashMap<String, String> cfg = new HashMap<>();
                cfg.put("description", description);
                agentsConfig.put(agentName, cfg);
                putLog("Agent registered: " + agentName, LogLevel.DEBUG);
                return createMsg().setParam(RESULT, true).setParam(AI_P_AGENTNAME, agentName);
            }
        } catch (Exception e) {
            putLog("Failed to register agent: " + agentName + " error: " + e.toString(), LogLevel.ERROR);
        }
        return createMsg().setParam(RESULT, false).setParam("error", "Invalid agent registration");
    }

    protected synchronized TLMsg unregisterAgent(Object fromWho, TLMsg msg) {
        String agentName = msg.getStringParam(AI_P_AGENTNAME, "");
        if (!agentName.isEmpty() && subAgents != null) {
            TLBaseModule removed = subAgents.remove(agentName);
            modules.remove(agentName);
            if (subAgents.isEmpty()) isMaster = false;
            return createMsg().setParam(RESULT, removed != null);
        }
        return createMsg().setParam(RESULT, false).setParam("error", "agentName required");
    }

    protected TLMsg listAgents(Object fromWho, TLMsg msg) {
        List<Map<String, Object>> agentInfos = new ArrayList<>();
        if (subAgents != null) {
            for (String agentName : subAgents.keySet()) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", agentName);
                HashMap<String, String> agentCfg = agentsConfig != null ? agentsConfig.get(agentName) : null;
                info.put("description", agentCfg != null ? agentCfg.getOrDefault("description", "") : "");
                info.put("moduleName", agentName);
                agentInfos.add(info);
            }
        }
        return createMsg().setParam(RESULT, true).setParam(AI_P_SUBAGENTS, agentInfos);
    }

    // ======================== Context操作 ========================

    protected TLMsg getAgentContext(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        TLMsg ctxMsg = createMsg()
                .setAction(CONTEXT_GETMESSAGES)
                .setParam(AI_P_SESSIONID, sessionId);
        return putMsg(contextModuleName, ctxMsg);
    }

    protected TLMsg clearAgentContext(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        TLMsg ctxMsg = createMsg()
                .setAction(CONTEXT_CLEAR)
                .setParam(AI_P_SESSIONID, sessionId);
        return putMsg(contextModuleName, ctxMsg);
    }

    protected TLMsg setSystemMsg(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        String systemMsg = msg.getStringParam(AI_P_SYSTEMMESSAGE, "");
        TLMsg ctxMsg = createMsg()
                .setAction(CONTEXT_SETSYSTEM)
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam(AI_P_SYSTEMMESSAGE, systemMsg);
        return putMsg(contextModuleName, ctxMsg);
    }

    // ======================== Memory操作 ========================

    protected TLMsg saveAgentMemory(Object fromWho, TLMsg msg) {
        String storeName = msg.getStringParam("storeName", defaultMemoryStore);
        TLBaseMemory memory = memoryStores.get(storeName);
        if (memory == null) {
            return createMsg().setParam(RESULT, false).setParam("error", "Memory store not found: " + storeName);
        }
        TLMsg memMsg = createMsg()
                .setAction(MEMORY_STORE)
                .setParam(AI_P_SESSIONID, msg.getStringParam(AI_P_SESSIONID, "default"))
                .setParam(AI_P_MEMORYKEY, msg.getStringParam(AI_P_MEMORYKEY, ""))
                .setParam(AI_P_MEMORYVALUE, msg.getParam(AI_P_MEMORYVALUE))
                .setParam(AI_P_MEMORYTAG, msg.getStringParam(AI_P_MEMORYTAG, null))
                .setParam(AI_P_MEMORYEXPTIME, msg.getIntParam(AI_P_MEMORYEXPTIME, -1));
        return putMsg(memory, memMsg);
    }

    @SuppressWarnings("unchecked")
    protected TLMsg recallAgentMemory(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        List<TLMemoryEntry> allEntries = new ArrayList<>();

        // 同时搜索短期和长期记忆，合并结果
        for (String storeName : memoryStores.keySet()) {
            TLBaseMemory memory = memoryStores.get(storeName);
            if (memory != null) {
                TLMsg memMsg = createMsg()
                        .setAction(MEMORY_SEARCH)
                        .setParam(AI_P_SESSIONID, sessionId)
                        .setParam(AI_P_MEMORYQUERY, msg.getStringParam(AI_P_MEMORYQUERY, ""))
                        .setParam(AI_P_MEMORYTAG, msg.getStringParam(AI_P_MEMORYTAG, null))
                        .setParam(AI_P_TOPK, msg.getIntParam(AI_P_TOPK, 5));
                TLMsg result = putMsg(memory, memMsg);
                List<TLMemoryEntry> entries = (List<TLMemoryEntry>)
                        result.getListParam(AI_P_MEMORYRESULT, null);
                if (entries != null) {
                    allEntries.addAll(entries);
                }
            }
        }
        return createMsg().setParam(RESULT, !allEntries.isEmpty())
                .setParam(AI_P_MEMORYRESULT, allEntries);
    }

    // ======================== Provider管理 ========================

    protected TLMsg setLlmProvider(Object fromWho, TLMsg msg) {
        String providerName = msg.getStringParam(AI_P_PROVIDER, "");
        if (providerName.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "provider name required");
        }
        TLBaseModule module = getModule(providerName) instanceof TLLlmProvider
                ? (TLBaseModule) getModule(providerName) : null;
        if (module instanceof TLLlmProvider) {
            llmProvider = (TLLlmProvider) module;
            defaultLlmProvider = providerName;

            // 从providersConfig注入参数
            if (providersConfig != null) {
                HashMap<String, String> pConf = providersConfig.get(providerName);
                if (pConf != null) {
                    if (pConf.get("apiKey") != null)
                        llmProvider.setApiKey(pConf.get("apiKey"));
                    if (pConf.get("apiBaseUrl") != null)
                        llmProvider.setApiBaseUrl(pConf.get("apiBaseUrl"));
                    if (pConf.get("defaultModel") != null)
                        llmProvider.setDefaultModel(pConf.get("defaultModel"));
                }
            }

            modules.put(providerName, llmProvider);
            putLog("LLM Provider switched to: " + providerName, LogLevel.DEBUG);
            return createMsg().setParam(RESULT, true);
        }
        return createMsg().setParam(RESULT, false).setParam("error", "Provider not found: " + providerName);
    }

    // ======================== 内部辅助方法 ========================

    /**
     * 获取会话上下文历史
     */
    @SuppressWarnings("unchecked")
    protected List<TLConversationHistory> getContextHistory(String sessionId) {
        TLMsg ctxMsg = createMsg()
                .setAction(CONTEXT_GETMESSAGES)
                .setParam(AI_P_SESSIONID, sessionId);
        TLMsg response = putMsg(contextModuleName, ctxMsg);
        if (response != null && response.containsParam(AI_P_MESSAGEHISTORY)) {
            return (List<TLConversationHistory>) response.getListParam(AI_P_MESSAGEHISTORY, new ArrayList<>());
        }
        return new ArrayList<>();
    }

    /**
     * 保存上下文历史（批量替换，O(1)次消息传递）
     */
    protected void saveContextHistory(String sessionId, List<TLConversationHistory> history) {
        TLMsg replaceMsg = createMsg()
                .setAction(CONTEXT_REPLACE)
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam(AI_P_MESSAGEHISTORY, history);
        putMsg(contextModuleName, replaceMsg);
    }

    /**
     * 从已注册skills构建function definitions列表。
     * 主控模式下，每个子Agent作为一个委托tool。
     */
    protected List<TLFunctionDefinition> buildFunctionDefinitions() {
        List<TLFunctionDefinition> defs = new ArrayList<>();

        // 始终包含自身的skills（主控也可以有自己的skill）
        for (TLBaseSkill skill : skills.values()) {
            if (skill.isEnabled()) {
                try {
                    defs.add(skill.buildFunctionDefinition());
                } catch (Exception e) {
                    putLog("Build function def error for " + skill.getName() + ": " + e.toString(), LogLevel.WARN);
                }
            }
        }

        // msgTools：LLM 可调用的预定义消息
        if (msgTools != null) {
            for (TLMsg msgTool : msgTools) {
                String name = msgTool.getMsgId();
                if (name == null || name.isEmpty()) continue;

                TLFunctionDefinition def = new TLFunctionDefinition();
                def.setName(name);
                String desc = msgTool.getDescription();
                def.setDescription(desc != null && !desc.isEmpty() ? desc : name);

                // 自动从 msg args 推断参数 schema（每个 key → string 类型）
                HashMap<String, Object> args = msgTool.getArgs();
                Map<String, Object> properties = new LinkedHashMap<>();
                if (args != null && !args.isEmpty()) {
                    for (String key : args.keySet()) {
                        Map<String, Object> prop = new LinkedHashMap<>();
                        prop.put("type", "string");
                        prop.put("description", key);
                        properties.put(key, prop);
                    }
                }
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "object");
                schema.put("properties", properties);
                def.setParameters(schema);

                defs.add(def);
            }
        }

        // 主控模式额外添加子Agent委托tools
        if (isMaster && subAgents != null) {
            for (String agentName : subAgents.keySet()) {
                HashMap<String, String> agentCfg = agentsConfig.get(agentName);
                String agentType = agentCfg != null
                        ? agentCfg.getOrDefault(AI_P_AGENTTYPE, AGENT_TYPE_AGENT)
                        : AGENT_TYPE_AGENT;

                if (AGENT_TYPE_MCP.equals(agentType)) {
                    // MCP Agent：展开为 N 个 tool def，注册路由
                    TLBaseModule mcpModule = subAgents.get(agentName);
                    if (mcpModule instanceof cn.tianlong.tlobject.aiagent.mcp.TLMcpAgent) {
                        cn.tianlong.tlobject.aiagent.mcp.TLMcpAgent mcpAgent =
                                (cn.tianlong.tlobject.aiagent.mcp.TLMcpAgent) mcpModule;
                        for (TLFunctionDefinition toolDef : mcpAgent.getToolDefinitions()) {
                            defs.add(toolDef);
                            if (mcpToolRoutes == null) mcpToolRoutes = new ConcurrentHashMap<>();
                            String toolName = mcpAgent.getToolNameByFunctionName(toolDef.getName());
                            if (toolName != null) {
                                mcpToolRoutes.put(toolDef.getName(),
                                        new String[]{agentName, toolName});
                            }
                        }
                        putLog("MCP Agent [" + agentName + "] contributed "
                                + mcpAgent.getToolDefinitions().size() + " tools", LogLevel.DEBUG);
                    }
                } else {
                    // 普通 Agent：单个 delegate_to_xxx def
                    String desc = agentCfg != null ? agentCfg.getOrDefault("description", agentName) : agentName;
                    TLFunctionDefinition def = TLFunctionDefinition.fromSkill(
                            "delegate_to_" + agentName, desc, buildDelegateParamSchema());
                    defs.add(def);
                }
            }
        }
        // Group Agent（不在 subAgents 中，无实例）
        if (isMaster && agentsConfig != null) {
            for (String agentName : agentsConfig.keySet()) {
                if (subAgents != null && subAgents.containsKey(agentName)) continue;
                HashMap<String, String> agentCfg = agentsConfig.get(agentName);
                String agentType = agentCfg != null
                        ? agentCfg.getOrDefault(AI_P_AGENTTYPE, AGENT_TYPE_AGENT) : AGENT_TYPE_AGENT;
                if (AGENT_TYPE_GROUP.equals(agentType)) {
                    String desc = agentCfg.getOrDefault("description", agentName);
                    defs.add(TLFunctionDefinition.fromSkill(
                            "delegate_to_" + agentName, desc, buildDelegateParamSchema()));
                }
            }
        }
        return defs;
    }

    /**
     * 执行 Group Agent：按 mode 调度 members。
     * - sequential: 串行，前一步输出 → 下一步输入
     * - parallel:   并发，putMsgGroupByThread 一发全发，等齐合并结果
     */
    @SuppressWarnings("unchecked")
    private TLMsg executeGroup(String groupName, HashMap<String, String> groupCfg,
                                TLToolCall tc, Object fromWho) {
        String membersStr = groupCfg.getOrDefault("members", "");
        if (membersStr.isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error: group " + groupName + " has no members");
        }
        String[] memberNames = membersStr.split(";");
        String task = parseDelegateArgs(tc.getArguments());
        String mode = groupCfg.getOrDefault("mode", "sequential");

        System.out.println(">>> [主控-Group] 启动组 [" + groupName + "] mode=" + mode + " members=" + membersStr);
        System.out.println("    任务: " + task);

        if ("parallel".equals(mode)) {
            int waitTime = 120000; // 默认120s
            try { waitTime = Integer.parseInt(groupCfg.getOrDefault("waitTime", "120000")); }
            catch (NumberFormatException ignored) {}
            return executeGroupParallel(groupName, memberNames, task, tc, waitTime);
        }
        return executeGroupSequential(groupName, memberNames, task, tc);
    }

    private TLMsg executeGroupSequential(String groupName, String[] memberNames,
                                          String task, TLToolCall tc) {
        String currentInput = task;
        TLMsg lastResult = null;
        for (String mName : memberNames) {
            mName = mName.trim();
            if (mName.isEmpty()) continue;
            TLBaseModule member = (TLBaseModule) getModule(mName);
            if (member == null) {
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "Group member not found: " + mName);
            }
            String sid = groupName + "_" + mName + ":" + (tc.getId() != null ? tc.getId() : System.currentTimeMillis());
            System.out.println("  → [Group-seq] " + mName);
            TLMsg result = putMsg(member, createMsg().setAction(AGENT_CHAT)
                    .setParam(AI_P_USERMESSAGE, currentInput).setParam(AI_P_SESSIONID, sid));
            if (result == null || !result.parseBoolean(RESULT, false)) {
                String err = result != null ? result.getStringParam(AI_P_RESPONSE, "unknown") : "no response";
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "Group step [" + mName + "] failed: " + err);
            }
            lastResult = result;
            currentInput = result.getStringParam(AI_P_RESPONSE, currentInput);
            System.out.println("  ✓ [Group-seq] " + mName + " done");
        }
        return createMsg().setParam(RESULT, true)
                .setParam(AI_P_SKILLOUTPUT, lastResult != null ? lastResult.getStringParam(AI_P_RESPONSE, "") : "");
    }

    @SuppressWarnings("unchecked")
    private TLMsg executeGroupParallel(String groupName, String[] memberNames,
                                        String task, TLToolCall tc, int waitTime) {
        List<TLMsg> msgList = new ArrayList<>();
        for (String mName : memberNames) {
            mName = mName.trim();
            if (mName.isEmpty()) continue;
            String sid = groupName + "_" + mName + ":" + (tc.getId() != null ? tc.getId() : System.currentTimeMillis());
            msgList.add(createMsg().setAction(AGENT_CHAT)
                    .setParam(AI_P_USERMESSAGE, task).setParam(AI_P_SESSIONID, sid)
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
        return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLOUTPUT, merged.toString().trim());
    }

    /**
     * 输出护栏：若 params 中配置了 outputGuard 模块名，校验并净化 LLM 回复。
     */
    private String guardOutput(String response) {
        if (response == null || response.isEmpty()) return response;
        String guardModuleName = params != null ? params.get("outputGuard") : null;
        if (guardModuleName == null || guardModuleName.isEmpty()) return response;
        Object found = getModule(guardModuleName);
        TLBaseModule guard = found instanceof TLBaseModule ? (TLBaseModule) found : null;
        if (guard == null) return response;
        try {
            TLMsg guardMsg = createMsg().setAction("validateOutput")
                    .setParam(AI_P_RESPONSE, response);
            TLMsg result = putMsg(guard, guardMsg);
            if (result != null && result.containsParam(AI_P_RESPONSE)) {
                return result.getStringParam(AI_P_RESPONSE, response);
            }
        } catch (Exception e) {
            putLog("Output guard error: " + e.toString(), LogLevel.WARN);
        }
        return response;
    }

    /**
     * 构建委托子Agent的参数schema（task描述）
     */
    protected Map<String, Object> buildDelegateParamSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("task", Map.of("type", "string", "description", "要交给专业Agent处理的任务描述"));
        return schema;
    }

    /**
     * 执行单个tool call。
     * 主控模式：路由到子Agent（delegate_to_xxx）；
     * 独立模式：路由到对应skill。
     */
    protected TLMsg executeToolCall(TLToolCall tc, Object fromWho) {
        String functionName = tc.getFunctionName();

        // 主控模式：MCP tool 路由（查 mcpToolRoutes 表）
        if (isMaster && mcpToolRoutes != null && mcpToolRoutes.containsKey(functionName)) {
            String[] route = mcpToolRoutes.get(functionName);
            String mcpAgentName = route[0];
            String mcpToolName = route[1];
            cn.tianlong.tlobject.aiagent.mcp.TLMcpAgent mcpAgent =
                    (cn.tianlong.tlobject.aiagent.mcp.TLMcpAgent) subAgents.get(mcpAgentName);
            if (mcpAgent == null) {
                putLog("MCP Agent not found: " + mcpAgentName, LogLevel.WARN);
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "Error: MCP Agent not found: " + mcpAgentName);
            }

            try {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> toolArgs = tc.getArguments() instanceof java.util.Map
                        ? (java.util.Map<String, Object>) tc.getArguments()
                        : new java.util.LinkedHashMap<>();

                System.out.println(">>> [主控-MCP] 调用 MCP tool [" + mcpAgentName + "." + mcpToolName + "]");
                System.out.println("    参数: " + toolArgs);

                TLMsg result = mcpAgent.callTool(mcpToolName, toolArgs);
                String output = result.getStringParam(AI_P_SKILLOUTPUT,
                        result.parseBoolean(RESULT, false) ? "OK" : "Failed");

                System.out.println("<<< [主控-MCP] MCP tool [" + mcpAgentName + "." + mcpToolName
                        + "] 返回 (前200字): " + (output != null ? output.substring(0, Math.min(200, output.length())) : "null"));

                return result;
            } catch (Exception e) {
                putLog("!!! [主控-MCP] MCP tool [" + mcpAgentName + "." + mcpToolName + "] 异常: " + e.toString(), LogLevel.ERROR);
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "Error executing MCP tool: " + e.getMessage());
            }
        }

        // msgTool 路由：查找匹配 msgId 的预定义消息
        TLMsg matchedMsg = findMsgToolByMsgId(functionName);
        if (matchedMsg != null) {
            try {
                TLMsg execMsg = new TLMsg();
                execMsg.copyFrom(matchedMsg);
                execMsg.setSource(getName());

                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> toolArgs = tc.getArguments() instanceof java.util.Map
                        ? (java.util.Map<String, Object>) tc.getArguments()
                        : new java.util.LinkedHashMap<>();
                // 注入 LLM 参数（覆盖/追加到消息 args 中）
                if (toolArgs != null) {
                    for (java.util.Map.Entry<String, Object> entry : toolArgs.entrySet()) {
                        execMsg.setParam(entry.getKey(), entry.getValue());
                    }
                }
                // 自动注入当前会话上下文参数（如果消息模板未预设）
                if (!execMsg.containsParam(AI_P_SESSIONID) && currentSessionId != null) {
                    execMsg.setParam(AI_P_SESSIONID, currentSessionId);
                }

                String dest = execMsg.getDestination();
                if (dest == null || dest.isEmpty()) {
                    return createMsg().setParam(RESULT, false)
                            .setParam(AI_P_SKILLOUTPUT, "msgTool error: no destination for " + functionName);
                }

                System.out.println(">>> [msgTool] 执行消息 [" + functionName
                        + "] action=" + execMsg.getAction() + " dest=" + dest);
                putLog("Executing msgTool: " + functionName + " -> " + dest + "." + execMsg.getAction(), LogLevel.DEBUG);

                TLMsg result = putMsg(dest, execMsg);
                String output;
                if (result == null) {
                    output = "done";
                } else if (result.containsParam(AI_P_SKILLOUTPUT)) {
                    // Skill 风格返回（显式设置了 AI_P_SKILLOUTPUT）
                    output = result.getStringParam(AI_P_SKILLOUTPUT, "");
                } else {
                    // 通用返回：从 args 中拼出所有业务参数
                    HashMap<String, Object> resultArgs = result.getArgs();
                    if (resultArgs != null && !resultArgs.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (java.util.Map.Entry<String, Object> entry : resultArgs.entrySet()) {
                            if (sb.length() > 0) sb.append(", ");
                            sb.append(entry.getKey()).append("=").append(entry.getValue());
                        }
                        output = sb.toString();
                    } else {
                        output = result.toString();
                    }
                }

                System.out.println("<<< [msgTool] 消息 [" + functionName + "] 返回: " + output);

                return createMsg().setParam(RESULT, true)
                        .setParam(AI_P_SKILLOUTPUT, output);
            } catch (Exception e) {
                putLog("msgTool execution error: " + functionName + " -> " + e.toString(), LogLevel.ERROR);
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "Error executing msgTool " + functionName + ": " + e.getMessage());
            }
        }

        // 主控模式：路由到子Agent / Group
        if (isMaster && functionName.startsWith("delegate_to_")) {
            String agentName = functionName.substring("delegate_to_".length());

            // 检查是否为 Group Agent
            HashMap<String, String> agentCfg = agentsConfig != null ? agentsConfig.get(agentName) : null;
            String agentType = agentCfg != null
                    ? agentCfg.getOrDefault(AI_P_AGENTTYPE, AGENT_TYPE_AGENT) : AGENT_TYPE_AGENT;

            if (AGENT_TYPE_GROUP.equals(agentType)) {
                return executeGroup(agentName, agentCfg, tc, fromWho);
            }

            TLBaseModule subAgent = subAgents.get(agentName);
            if (subAgent == null) {
                putLog("Sub-agent not found: " + agentName, LogLevel.WARN);
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "Error: Agent not found: " + agentName)
                        .setParam(AI_P_AGENTERROR, AGENT_ERR_NOTFOUND);
            }

            try {
                String task = parseDelegateArgs(tc.getArguments());
                String childSessionId = agentName + ":"
                        + (tc.getId() != null ? tc.getId() : System.currentTimeMillis());

                TLMsg chatMsg = createMsg()
                        .setAction(AGENT_CHAT)
                        .setParam(AI_P_USERMESSAGE, task)
                        .setParam(AI_P_SESSIONID, childSessionId);

                System.out.println(">>> [主控] 委托任务给子Agent [" + agentName + "]");
                System.out.println("    任务: " + task);

                TLMsg result = putMsg(subAgent, chatMsg);
                String agentOutput = result != null
                        ? result.getStringParam(AI_P_RESPONSE, result.toString())
                        : "No response from agent " + agentName;

                System.out.println("<<< [主控] 子Agent [" + agentName + "] 返回结果 (前200字): "
                        + (agentOutput != null ? agentOutput.substring(0, Math.min(200, agentOutput.length())) : "null"));

                return createMsg().setParam(RESULT, true)
                        .setParam(AI_P_SKILLOUTPUT, agentOutput);
            } catch (Exception e) {
                putLog("!!! [主控] 子Agent [" + agentName + "] 执行异常: " + e.toString(), LogLevel.ERROR);
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "Error in agent " + agentName + ": " + e.getMessage())
                        .setParam(AI_P_AGENTERROR, e.getMessage());
            }
        }

        // 独立模式：原有skill路由
        TLBaseSkill skill = skills.get(functionName);

        if (skill == null) {
            putLog("Skill not found for tool call: " + functionName, LogLevel.WARN);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error: Skill not found: " + functionName)
                    .setParam("error", "Skill not found");
        }

        try {
            // 输入校验：参数不合法则返回错误让 LLM 自修正
            TLMsg validateMsg = createMsg().setAction(SKILL_VALIDATE)
                    .setParam(AI_P_SKILLINPUT, tc.getArguments() != null
                            ? tc.getArguments() : new LinkedHashMap<>());
            TLMsg vResult = putMsg(skill, validateMsg);
            if (!vResult.parseBoolean(RESULT, false)) {
                putLog("Skill validation failed: " + functionName + " - " + vResult.getStringParam("error", ""), LogLevel.WARN);
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "Validation error: " + vResult.getStringParam("error", "unknown"));
            }

            TLMsg executeMsg = createMsg()
                    .setAction(SKILL_EXECUTE)
                    .setParam(AI_P_SKILLINPUT, tc.getArguments() != null
                            ? tc.getArguments() : new LinkedHashMap<>())
                    .setParam(AI_P_TOOLID, tc.getId())
                    .setParam(AI_P_TOOLNAME, functionName);

            putLog("Executing skill: " + functionName + " toolCallId=" + tc.getId(), LogLevel.DEBUG);

            return putMsg(skill, executeMsg);
        } catch (Exception e) {
            putLog("Skill execution error: " + functionName + " -> " + e.toString(), LogLevel.ERROR);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error executing " + functionName + ": " + e.getMessage());
        }
    }

    /**
     * 解析委托参数，提取task描述字符串
     */
    @SuppressWarnings("unchecked")
    protected String parseDelegateArgs(Object args) {
        if (args instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) args;
            Object task = map.get("task");
            if (task != null) return task.toString();
        }
        if (args instanceof String) return (String) args;
        return args != null ? args.toString() : "";
    }

    /**
     * 根据 msgId 查找 msgTools 列表中的匹配消息。
     * @param msgId LLM tool call 中的 function name（对应 msg 的 msgid 属性）
     * @return 匹配的 TLMsg，未找到返回 null
     */
    protected TLMsg findMsgToolByMsgId(String msgId) {
        if (msgTools == null || msgId == null) return null;
        for (TLMsg msg : msgTools) {
            if (msgId.equals(msg.getMsgId())) {
                return msg;
            }
        }
        return null;
    }

    /**
     * 添加包名前缀（与TLDataBase.addPackage相同的模式）
     */
    protected String addPackage(String name, String packageName) {
        if (packageName == null || packageName.isEmpty())
            return name;
        if (name == null || name.isEmpty())
            return name;
        String firstCha = name.substring(0, 1);
        if (firstCha.equals(".")) {
            return packageName + name;
        }
        return name;
    }

    // ======================== Provider 启动检查 ========================

    /**
     * 启动时检查 LLM Provider 是否可用。发送最小化请求验证 API Key / 余额。
     */
    protected boolean checkProvider() {
        if (llmProvider == null) {
            System.err.println("!!! [启动检查] LLM Provider 未加载！");
            return false;
        }
        try {
            List<TLConversationHistory> testHistory = new ArrayList<>();
            testHistory.add(new TLConversationHistory(TLConversationHistory.Role.user, "ping"));
            TLMsg testMsg = createMsg().setAction(LLM_COMPLETION)
                    .setParam(AI_P_MESSAGEHISTORY, testHistory)
                    .setParam(AI_P_MODEL, llmProvider.getDefaultModel())
                    .setParam(AI_P_MAXTOKENS, 1);
            TLMsg result = putMsg(llmProvider, testMsg);
            if (result.parseBoolean(RESULT, false)) {
                System.out.println("=== [启动检查] LLM Provider 可用: " + llmProvider.getDefaultModel() + " ===");
                return true;
            } else {
                int status = result.getIntParam(AI_P_HTTPSTATUS, 0);
                String body = result.getStringParam(AI_P_RESPONSEBODY, "");
                System.err.println("!!! [启动检查] LLM Provider 不可用！HTTP " + status + " body=" + body);
                System.err.println("!!! 请检查 API Key 和余额，或切换 Provider");
                llmProvider = null;  // 置空，chat() 调用时直接返回错误
                return false;
            }
        } catch (Exception e) {
            System.err.println("!!! [启动检查] LLM Provider 连接失败: " + e.getMessage());
            llmProvider = null;
            return false;
        }
    }

    // ======================== Session 持久化/断点恢复 ========================

    /**
     * 保存会话到 JSON 文件。
     * @param state "checkpoint"（mid-loop 断点）或 "completed"（已完成）
     */
    @SuppressWarnings("unchecked")
    protected void persistSession(String sessionId, List<TLConversationHistory> history,
                                   String state, int iteration, String model,
                                   double temperature, int maxTokens, String userMessage) {
        if (!enableCheckpoint) return;
        try {
            java.io.File dir = new java.io.File(sessionStorePath);
            if (!dir.exists()) dir.mkdirs();

            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("sessionId", sessionId);
            data.put("state", state);
            data.put("iteration", iteration);
            data.put("model", model);
            data.put("temperature", temperature);
            data.put("maxTokens", maxTokens);
            data.put("userMessage", userMessage);
            data.put("history", history);
            data.put("savedAt", System.currentTimeMillis());

            String json = gson.toJson(data);
            java.io.File file = new java.io.File(dir, sessionId + ".json");
            java.nio.file.Files.write(file.toPath(), json.getBytes("UTF-8"));
        } catch (Exception e) {
            putLog("persistSession failed: " + e.toString(), LogLevel.WARN);
        }
    }

    /**
     * 加载会话检查点文件，返回 TLMsg 包含所有保存的字段。
     * @return null 表示文件不存在或加载失败
     */
    @SuppressWarnings("unchecked")
    protected TLMsg loadSessionCheckpoint(String sessionId) {
        if (!enableCheckpoint || sessionId == null) return null;
        try {
            java.io.File file = new java.io.File(sessionStorePath, sessionId + ".json");
            if (!file.exists()) return null;

            String json = new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");
            com.google.gson.JsonObject obj = gson.fromJson(json, com.google.gson.JsonObject.class);

            TLMsg result = new TLMsg();
            result.setParam("sessionId", obj.get("sessionId").getAsString());
            result.setParam("state", obj.get("state").getAsString());
            result.setParam("iteration", obj.get("iteration").getAsInt());
            result.setParam("model", obj.has("model") ? obj.get("model").getAsString() : "");
            result.setParam("temperature", obj.has("temperature") ? obj.get("temperature").getAsDouble() : 0.7);
            result.setParam("maxTokens", obj.has("maxTokens") ? obj.get("maxTokens").getAsInt() : 4096);
            result.setParam("userMessage", obj.has("userMessage") ? obj.get("userMessage").getAsString() : "");

            // 反序列化 history 列表
            com.google.gson.JsonArray histArray = obj.getAsJsonArray("history");
            List<TLConversationHistory> history = new ArrayList<>();
            for (int i = 0; i < histArray.size(); i++) {
                TLConversationHistory h = gson.fromJson(histArray.get(i), TLConversationHistory.class);
                history.add(h);
            }
            result.setParam("history", history);
            return result;
        } catch (Exception e) {
            putLog("loadSessionCheckpoint failed for " + sessionId + ": " + e.toString(), LogLevel.WARN);
            return null;
        }
    }

    /**
     * 删除会话持久化文件（清理用，如重置会话）
     */
    protected void deleteSessionFile(String sessionId) {
        try {
            java.io.File file = new java.io.File(sessionStorePath, sessionId + ".json");
            if (file.exists()) file.delete();
        } catch (Exception e) {
            putLog("deleteSessionFile failed: " + e.toString(), LogLevel.WARN);
        }
    }

    /**
     * 程序启动时扫描存储目录，恢复已完成的会话（state=completed）到 TLAiContext。
     */
    @SuppressWarnings("unchecked")
    protected void restoreSessions() {
        if (!enableCheckpoint) return;
        try {
            java.io.File dir = new java.io.File(sessionStorePath);
            if (!dir.exists() || !dir.isDirectory()) return;
            java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
            if (files == null) return;

            for (java.io.File f : files) {
                try {
                    TLMsg checkpoint = loadSessionCheckpoint(
                            f.getName().substring(0, f.getName().length() - 5));
                    if (checkpoint == null) continue;
                    String state = checkpoint.getStringParam("state", "");
                    if (!SESSION_STATE_COMPLETED.equals(state)) continue;

                    String sessionId = checkpoint.getStringParam("sessionId", "");
                    List<TLConversationHistory> history =
                            (List<TLConversationHistory>) checkpoint.getParam("history");
                    if (history == null) continue;

                    // 恢复到 TLAiContext
                    TLMsg replaceMsg = createMsg()
                            .setAction(CONTEXT_REPLACE)
                            .setParam(AI_P_SESSIONID, sessionId)
                            .setParam(AI_P_MESSAGEHISTORY, history);
                    putMsg(contextModuleName, replaceMsg);
                    putLog("Restored session: " + sessionId + " (" + history.size() + " msgs)", LogLevel.INFO);
                } catch (Exception e) {
                    putLog("restoreSessions skip " + f.getName() + ": " + e.toString(), LogLevel.WARN);
                }
            }
        } catch (Exception e) {
            putLog("restoreSessions error: " + e.toString(), LogLevel.WARN);
        }
    }

    /**
     * 程序启动时自动恢复未完成的检查点（state=checkpoint），
     * 异步发送 resume 消息给自己，触发 doChat 从断点继续。
     */
    protected void autoResumeCheckpoints() {
        if (!enableCheckpoint) return;
        try {
            java.io.File dir = new java.io.File(sessionStorePath);
            if (!dir.exists() || !dir.isDirectory()) return;
            java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
            if (files == null) return;

            for (java.io.File f : files) {
                try {
                    TLMsg checkpoint = loadSessionCheckpoint(
                            f.getName().substring(0, f.getName().length() - 5));
                    if (checkpoint == null) continue;
                    String state = checkpoint.getStringParam("state", "");
                    if (!SESSION_STATE_CHECKPOINT.equals(state)) continue;

                    String sessionId = checkpoint.getStringParam("sessionId", "");
                    String userMessage = checkpoint.getStringParam("userMessage", "");

                    putLog("Auto-resuming checkpoint: " + sessionId, LogLevel.INFO);

                    TLMsg resumeMsg = createMsg()
                            .setAction(AGENT_CHAT)
                            .setParam(AI_P_SESSIONID, sessionId)
                            .setParam(AI_P_USERMESSAGE, userMessage)
                            .setParam("resume", true)
                            .setSystemParam(INTHREADPOOL, true);
                    putMsg(getName(), resumeMsg);
                } catch (Exception e) {
                    putLog("autoResumeCheckpoints skip " + f.getName() + ": " + e.toString(), LogLevel.WARN);
                }
            }
        } catch (Exception e) {
            putLog("autoResumeCheckpoints error: " + e.toString(), LogLevel.WARN);
        }
    }

    // ======================== 内部配置解析类 ========================

    /**
     * 解析AI Agent自定义XML配置段：providers, skills, memoryStores。
     * 遵循TLDataBase.myConfig模式。
     */
    protected class myConfig extends TLModuleConfig {
        protected HashMap<String, HashMap<String, String>> providers;
        protected HashMap<String, HashMap<String, String>> skills;
        protected HashMap<String, HashMap<String, String>> memoryStores;
        protected HashMap<String, HashMap<String, String>> agents;
        protected ArrayList<TLMsg> msgTools;

        public myConfig() {}

        public myConfig(String configFile, String configDir) {
            super(configFile, configDir);
        }

        public HashMap<String, HashMap<String, String>> getProviders() { return providers; }
        public HashMap<String, HashMap<String, String>> getSkills() { return skills; }
        public HashMap<String, HashMap<String, String>> getMemoryStores() { return memoryStores; }
        public HashMap<String, HashMap<String, String>> getAgents() { return agents; }
        public ArrayList<TLMsg> getMsgTools() { return msgTools; }

        @Override
        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if (xpp.getName().equals("providers")) {
                    providers = getHashMap(xpp, "providers", "provider");
                }
                if (xpp.getName().equals("skills")) {
                    skills = getHashMap(xpp, "skills", "skill");
                }
                if (xpp.getName().equals("memoryStores")) {
                    memoryStores = getHashMap(xpp, "memoryStores", "memoryStore");
                }
                if (xpp.getName().equals("agents")) {
                    agents = getHashMap(xpp, "agents", "agent");
                }
                if (xpp.getName().equals("msgTools")) {
                    msgTools = getMsgList(xpp, "msgTools");
                }
            } catch (Throwable t) {
                putLog("TLAiAgent config parse error: " + t.toString(), LogLevel.WARN);
            }
        }
    }
}
