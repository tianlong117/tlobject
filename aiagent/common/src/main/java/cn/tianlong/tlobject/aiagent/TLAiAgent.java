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
        }
    }

    @Override
    protected TLBaseModule init() {
        skills = new ConcurrentHashMap<>();
        memoryStores = new ConcurrentHashMap<>();

        // 加载Provider并注入配置参数
        if (defaultLlmProvider != null && !defaultLlmProvider.isEmpty()) {
            TLBaseModule module = (TLBaseModule) getModule(defaultLlmProvider);
            if (module instanceof TLLlmProvider) {
                llmProvider = (TLLlmProvider) module;
                modules.put(defaultLlmProvider, llmProvider);

                // 从 <providers> 配置中注入参数
                if (providersConfig != null) {
                    HashMap<String, String> pConf = providersConfig.get(defaultLlmProvider);
                    if (pConf != null) {
                        if (pConf.get("apiKey") != null)
                            llmProvider.setApiKey(pConf.get("apiKey"));
                        if (pConf.get("apiBaseUrl") != null)
                            llmProvider.setApiBaseUrl(pConf.get("apiBaseUrl"));
                        if (pConf.get("defaultModel") != null)
                            llmProvider.setDefaultModel(pConf.get("defaultModel"));
                        putLog("LLM Provider configured from XML: " + defaultLlmProvider
                                + " model=" + llmProvider.getDefaultModel(), LogLevel.DEBUG);
                    }
                }
                putLog("LLM Provider loaded: " + defaultLlmProvider, LogLevel.DEBUG);
            } else {
                putLog("LLM Provider not found or wrong type: " + defaultLlmProvider, LogLevel.ERROR);
            }
        }

        // 加载内置模块
        if (modules.containsKey(M_AICONTEXT)) {
            putLog("Context module registered: " + M_AICONTEXT, LogLevel.DEBUG);
        }

        return this;
    }

    @Override
    public void runStartMsg() {
        initSkills();
        initMemoryStores();
        super.runStartMsg();
    }

    /**
     * 初始化Skills（从config或运行时注册）
     */
    protected void initSkills() {
        myConfig config = (myConfig) mconfig;
        if (config == null || config.getSkills() == null) return;

        HashMap<String, HashMap<String, String>> skillConfigs = config.getSkills();
        for (String skillName : skillConfigs.keySet()) {
            HashMap<String, String> skillParams = skillConfigs.get(skillName);
            String classfile = skillParams.get(MODULE_CLASSFILE);
            boolean startup = TLDataUtils.parseBoolean(skillParams.get("statup"), true);

            if (startup && classfile != null) {
                String resolvedClass = addPackage(classfile, defaultSkillPackageName);
                try {
                    TLBaseModule module = (TLBaseModule) getNewModule(skillName, resolvedClass, skillParams);
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
    }

    /**
     * 初始化Memory Stores
     */
    protected void initMemoryStores() {
        myConfig config = (myConfig) mconfig;
        if (config == null || config.getMemoryStores() == null) return;

        HashMap<String, HashMap<String, String>> memoryConfigs = config.getMemoryStores();
        for (String storeName : memoryConfigs.keySet()) {
            HashMap<String, String> storeParams = memoryConfigs.get(storeName);
            String classfile = storeParams.get(MODULE_CLASSFILE);
            boolean startup = TLDataUtils.parseBoolean(storeParams.get("statup"), true);

            if (startup && classfile != null) {
                String resolvedClass = addPackage(classfile, defaultMemoryPackageName);
                try {
                    TLBaseModule module = (TLBaseModule) getNewModule(storeName, resolvedClass, storeParams);
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
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        String userMessage = msg.getStringParam(AI_P_USERMESSAGE, "");
        String model = msg.getStringParam(AI_P_MODEL, llmProvider.getDefaultModel());
        double temperature = msg.getDoubleParam(AI_P_TEMPERATURE, defaultTemperature);
        int maxTokens = msg.getIntParam(AI_P_MAXTOKENS, defaultMaxTokens);

        if (userMessage.isEmpty() || llmProvider == null) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, "Error: " + (userMessage.isEmpty() ? "missing userMessage" : "no provider"));
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

            List<TLConversationHistory> history = getContextHistory(sessionId);
            if (memoryContext != null && !memoryContext.isEmpty()) {
                history.add(new TLConversationHistory(TLConversationHistory.Role.system, memoryContext));
            }
            history.add(new TLConversationHistory(TLConversationHistory.Role.user, userMessage));
            List<TLFunctionDefinition> toolDefs = buildFunctionDefinitions();

            // ==== LLM请求 ====
            String finalResponse;
            int iteration = 0;

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
                }
                if (finalResponse == null) finalResponse = "Reached max iterations (" + maxToolCallIterations + ")";
            }

            // ==== 后处理: 保存上下文 + 长期记忆 ====
            saveContextHistory(sessionId, history);
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
            // 尝试从context module获取
            memory = (TLBaseMemory) getModule(storeName);
            if (memory == null) {
                return createMsg().setParam(RESULT, false).setParam("error", "Memory store not found: " + storeName);
            }
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
            if (memory == null) {
                memory = (TLBaseMemory) getModule(storeName);
            }
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
     * 从已注册skills构建function definitions列表
     */
    protected List<TLFunctionDefinition> buildFunctionDefinitions() {
        List<TLFunctionDefinition> defs = new ArrayList<>();
        for (TLBaseSkill skill : skills.values()) {
            if (skill.isEnabled()) {
                try {
                    defs.add(skill.buildFunctionDefinition());
                } catch (Exception e) {
                    putLog("Build function def error for " + skill.getName() + ": " + e.toString(), LogLevel.WARN);
                }
            }
        }
        return defs;
    }

    /**
     * 执行单个tool call，找到对应skill并执行
     */
    protected TLMsg executeToolCall(TLToolCall tc, Object fromWho) {
        String functionName = tc.getFunctionName();
        TLBaseSkill skill = skills.get(functionName);

        if (skill == null) {
            putLog("Skill not found for tool call: " + functionName, LogLevel.WARN);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error: Skill not found: " + functionName)
                    .setParam("error", "Skill not found");
        }

        try {
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

    // ======================== 内部配置解析类 ========================

    /**
     * 解析AI Agent自定义XML配置段：providers, skills, memoryStores。
     * 遵循TLDataBase.myConfig模式。
     */
    protected class myConfig extends TLModuleConfig {
        protected HashMap<String, HashMap<String, String>> providers;
        protected HashMap<String, HashMap<String, String>> skills;
        protected HashMap<String, HashMap<String, String>> memoryStores;

        public myConfig() {}

        public myConfig(String configFile, String configDir) {
            super(configFile, configDir);
        }

        public HashMap<String, HashMap<String, String>> getProviders() { return providers; }
        public HashMap<String, HashMap<String, String>> getSkills() { return skills; }
        public HashMap<String, HashMap<String, String>> getMemoryStores() { return memoryStores; }

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
            } catch (Throwable t) {
                putLog("TLAiAgent config parse error: " + t.toString(), LogLevel.WARN);
            }
        }
    }
}
