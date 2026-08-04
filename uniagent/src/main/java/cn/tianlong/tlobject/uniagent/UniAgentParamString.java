package cn.tianlong.tlobject.uniagent;

import cn.tianlong.tlobject.base.TLParamString;

/**
 * UniAgent框架常量定义接口，继承TLParamString获得所有核心框架常量。
 */
public interface UniAgentParamString extends TLParamString {

    // ======================== 模块名 ========================
    String M_UNIAGENT = "uniagent";
    String M_TOOLFACTORY = "toolFactory";
    String M_TOOLEXECENGINE = "toolExecutionEngine";

    // ======================== UniAgent动作 ========================
    /** 非流式聊天 */
    String AGENT_CHAT = "chat";
    /** 流式聊天 */
    String AGENT_CHATSTREAM = "chatStream";
    /** 中断正在进行的聊天 */
    String AGENT_STOPCHAT = "stopChat";
    /** 获取Agent描述 */
    String AGENT_GETDESCRIPTION = "getAgentDescription";
    /** 获取工具定义列表 */
    String AGENT_GETTOOLDEFS = "getToolDefinitions";

    // ======================== ToolFactory动作 ========================
    /** 注册工具模块 */
    String TOOL_REGISTER = "registerTool";
    /** 注销工具模块 */
    String TOOL_UNREGISTER = "unregisterTool";
    /** 列出所有工具 */
    String TOOL_LIST = "listTools";
    /** 启用/禁用工具 */
    String TOOL_SETENABLED = "setToolEnabled";
    /** 重载工具 */
    String TOOL_RELOAD = "reloadTool";

    // ======================== ToolExecutionEngine动作 ========================
    /** 执行工具调用列表 */
    String ENGINE_EXECUTETOOLS = "executeTools";

    // ======================== LLM Provider动作 ========================
    String LLM_COMPLETION = "completion";
    String LLM_COMPLETIONSTREAM = "completionStream";
    String LLM_LISTMODELS = "listModels";
    String LLM_CANCEL = "cancelLlm";
    String LLM_EMBEDDING = "embedding";
    String LLM_DEBUG_ON = "debugOn";
    String LLM_DEBUG_OFF = "debugOff";
    String LLM_GETTRACES = "getTraces";
    String LLM_CLEARTRACES = "clearTraces";

    // ======================== Context动作 ========================
    String CONTEXT_ADDMESSAGE = "addMessage";
    String CONTEXT_GETMESSAGES = "getMessages";
    String CONTEXT_CLEAR = "clearContext";
    String CONTEXT_REPLACE = "replaceContext";
    String CONTEXT_GETTURNCOUNT = "getTurnCount";
    String CONTEXT_SETSYSTEM = "setSystem";

    // ======================== Skill动作 ========================
    String SKILL_GETINFO = "getSkillInfo";
    String SKILL_EXECUTE = "skillExecute";
    String SKILL_VALIDATE = "skillValidate";

    // ======================== Memory动作 ========================
    String MEMORY_STORE = "store";
    String MEMORY_SEARCH = "search";

    // ======================== 通用参数键 ========================
    String AI_P_PROVIDER = "llmProvider";
    String AI_P_MODEL = "model";
    String AI_P_TEMPERATURE = "temperature";
    String AI_P_MAXTOKENS = "maxTokens";

    // 会话/上下文
    String AI_P_SESSIONID = "sessionId";
    String AI_P_USERMESSAGE = "userMessage";
    String AI_P_SYSTEMMESSAGE = "systemMessage";
    String AI_P_MESSAGEHISTORY = "messageHistory";
    String AI_P_RESPONSE = "aiResponse";
    String AI_P_TOOLCALLS = "toolCalls";
    String AI_P_TOOLRESULTS = "toolResults";

    // Tool/Function
    String AI_P_TOOLNAME = "toolName";
    String AI_P_TOOLID = "toolId";
    String AI_P_TOOLARGUMENTS = "toolArguments";
    String AI_P_FUNCTIONDEFS = "functionDefinitions";
    String AI_P_TRUNCATED = "truncated";

    // Token
    String AI_P_PROMPTTOKENS = "promptTokens";
    String AI_P_COMPLETIONTOKENS = "completionTokens";
    String AI_P_TOTALTOKENS = "totalTokens";

    // Skill
    String AI_P_SKILLINPUT = "skillInput";
    String AI_P_SKILLOUTPUT = "skillOutput";
    String AI_P_ENABLED = "enabled";

    // HTTP内部
    String AI_P_HTTPSTATUS = "httpStatus";
    String AI_P_RESPONSEBODY = "responseBody";

    // 流式
    String AI_P_CHUNK = "chunk";
    String AI_P_STREAMDONE = "streamDone";
    String AI_P_STREAMERROR = "streamError";
    String AI_P_CANCELLED = "cancelled";
    String AI_P_REASONING_CHUNK = "reasoningChunk";

    // Prompt Caching
    String AI_P_ENABLEPROMPTCACHING = "enablePromptCaching";
    String AI_P_CACHECREATIONTOKENS = "cacheCreationTokens";
    String AI_P_CACHEHITTOKENS = "cacheHitTokens";
    String AI_P_CACHEMISSTOKENS = "cacheMissTokens";
    String AI_P_CACHEHITTOKENS_TOTAL = "cacheHitTokensTotal";
    String AI_P_CACHEMISSTOKENS_TOTAL = "cacheMissTokensTotal";

    // Embedding
    String AI_P_EMBEDTEXT = "embedText";
    String AI_P_EMBEDDINGMODEL = "embeddingModel";
    String AI_P_EMBEDDING = "embeddingVector";

    // 附加参数
    String AI_P_TOPP = "topP";
    String AI_P_RESPONSEFORMAT = "responseFormat";

    // Provider
    String AI_P_APIKEY = "apiKey";
    String AI_P_APIBASEURL = "apiBaseUrl";

    // Agent内部
    String AI_P_MAXTOOLCALLITERATIONS = "maxToolCallIterations";
    String AI_P_MAXHISTORYTURNS = "maxHistoryTurns";

    // 推理
    String AI_P_REASONING_MODE = "reasoningMode";
    String AI_P_REASONING = "reasoning";

    // 工具管理
    String AI_P_TOOLNAME_PARAM = "toolName";
    String AI_P_TOOLDESCRIPTION = "toolDescription";
    String AI_P_TOOLCONFIG = "toolConfig";

    // Session
    String AI_P_ROOTSESSIONID = "rootSessionId";
    String AI_P_USERID = "userId";
}
