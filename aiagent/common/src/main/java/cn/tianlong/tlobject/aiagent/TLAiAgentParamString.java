package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLParamString;

/**
 * AI Agent框架常量定义接口，继承TLParamString获得所有核心框架常量。
 * 定义所有AI Agent相关模块名、消息动作、参数键等常量。
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public interface TLAiAgentParamString extends TLParamString {

    // ======================== 模块名 ========================
    String M_AIAGENT = "aiagent";
    String M_AICONTEXT = "aiContext";
    String M_LLMPROVIDER_OPENAI = "openAiProvider";
    String M_LLMPROVIDER_CLAUDE = "claudeProvider";
    String M_SHORTTERMMEMORY = "shortTermMemory";
    String M_LONGTERMMEMORY = "longTermMemory";
    String M_SKILL_HTTPREQUEST = "httpRequestSkill";
    String M_SKILL_FILEOPERATION = "fileOperationSkill";
    String M_SKILL_CODEEXECUTION = "codeExecutionSkill";

    // ======================== Agent动作 ========================
    /** 发送用户消息，获取AI回复（非流式，完整chat循环含tool-call） */
    String AGENT_CHAT = "chat";
    /** 流式chat */
    String AGENT_CHATSTREAM = "chatStream";
    /** 注册skill模块 */
    String AGENT_REGISTERSKILL = "registerSkill";
    /** 注销skill模块 */
    String AGENT_UNREGISTERSKILL = "unregisterSkill";
    /** 列出所有已注册skill */
    String AGENT_LISTSKILLS = "listSkills";
    /** 注册子Agent */
    String AGENT_REGISTERAGENT = "registerAgent";
    /** 注销子Agent */
    String AGENT_UNREGISTERAGENT = "unregisterAgent";
    /** 列出所有已注册子Agent */
    String AGENT_LISTAGENTS = "listAgents";
    /** 委托任务给子Agent */
    String AGENT_DELEGATE = "delegateToAgent";
    /** 获取当前会话上下文 */
    String AGENT_GETCONTEXT = "getAgentContext";
    /** 清除会话上下文 */
    String AGENT_CLEARCONTEXT = "clearAgentContext";
    /** 持久化记忆 */
    String AGENT_SAVEMEMORY = "saveAgentMemory";
    /** 召回记忆 */
    String AGENT_RECALLMEMORY = "recallAgentMemory";
    /** 设置系统提示词 */
    String AGENT_SETSYSTEMMSG = "setSystemMsg";
    /** 运行时切换LLM provider */
    String AGENT_SETPROVIDER = "setLlmProvider";

    // ======================== LLM Provider动作 ========================
    /** 发送completion请求（非流式） */
    String LLM_COMPLETION = "completion";
    /** 发送流式completion请求 */
    String LLM_COMPLETIONSTREAM = "completionStream";
    /** 列出可用模型 */
    String LLM_LISTMODELS = "listModels";
    /** 取消进行中的请求 */
    String LLM_CANCEL = "cancelLlm";

    // ======================== Context动作 ========================
    /** 添加一条消息到历史 */
    String CONTEXT_ADDMESSAGE = "addMessage";
    /** 获取消息历史 */
    String CONTEXT_GETMESSAGES = "getMessages";
    /** 清除会话历史 */
    String CONTEXT_CLEAR = "clearContext";
    /** 批量替换会话历史 */
    String CONTEXT_REPLACE = "replaceContext";
    /** 获取对话轮次计数 */
    String CONTEXT_GETTURNCOUNT = "getTurnCount";
    /** 设置系统消息 */
    String CONTEXT_SETSYSTEM = "setSystem";

    // ======================== Skill动作 ========================
    /** 获取skill信息（名称、描述、参数schema） */
    String SKILL_GETINFO = "getSkillInfo";
    /** 执行skill */
    String SKILL_EXECUTE = "skillExecute";
    /** 校验输入参数 */
    String SKILL_VALIDATE = "skillValidate";

    // ======================== Memory动作 ========================
    /** 存储记忆条目 */
    String MEMORY_STORE = "store";
    /** 按键检索 */
    String MEMORY_RETRIEVE = "retrieve";
    /** 语义/相似度搜索 */
    String MEMORY_SEARCH = "search";
    /** 按键删除 */
    String MEMORY_DELETE = "delete";
    /** 清除所有记忆 */
    String MEMORY_CLEARALL = "clearAll";

    // ======================== 参数键 ========================
    // 通用
    String AI_P_PROVIDER = "llmProvider";
    String AI_P_MODEL = "model";
    String AI_P_TEMPERATURE = "temperature";
    String AI_P_MAXTOKENS = "maxTokens";
    String AI_P_TOPP = "topP";
    String AI_P_STOP = "stop";
    String AI_P_STREAM = "stream";

    // 会话/上下文
    String AI_P_SESSIONID = "sessionId";
    String AI_P_USERMESSAGE = "userMessage";
    String AI_P_SYSTEMMESSAGE = "systemMessage";
    String AI_P_CONVERSATIONID = "conversationId";
    String AI_P_MESSAGEHISTORY = "messageHistory";
    String AI_P_RESPONSE = "aiResponse";
    String AI_P_TOOLCALLS = "toolCalls";
    String AI_P_TOOLRESULTS = "toolResults";

    // Tool/Function
    String AI_P_TOOLNAME = "toolName";
    String AI_P_TOOLID = "toolId";
    String AI_P_TOOLARGUMENTS = "toolArguments";
    String AI_P_FUNCTIONDEFS = "functionDefinitions";

    // Skill
    String AI_P_SKILLNAME = "skillName";
    String AI_P_SKILLDESCRIPTION = "skillDescription";
    String AI_P_SKILLPARAMS = "skillParams";
    String AI_P_SKILLINPUT = "skillInput";
    String AI_P_SKILLOUTPUT = "skillOutput";

    // Memory
    String AI_P_MEMORYKEY = "memoryKey";
    String AI_P_MEMORYVALUE = "memoryValue";
    String AI_P_MEMORYTYPE = "memoryType";
    String AI_P_MEMORYTAG = "memoryTag";
    String AI_P_MEMORYEXPTIME = "memoryExptime";
    String AI_P_MEMORYQUERY = "memoryQuery";
    String AI_P_MEMORYRESULT = "memoryResult";
    String AI_P_TOPK = "topK";

    // 流式
    String AI_P_CHUNK = "chunk";
    String AI_P_STREAMDONE = "streamDone";
    String AI_P_STREAMERROR = "streamError";

    // HTTP内部
    String AI_P_APIKEY = "apiKey";
    String AI_P_APIBASEURL = "apiBaseUrl";
    String AI_P_HTTPHEADERS = "httpHeaders";
    String AI_P_REQUESTBODY = "requestBody";
    String AI_P_RESPONSEBODY = "responseBody";
    String AI_P_HTTPSTATUS = "httpStatus";

    // Agent内部
    String AI_P_MAXTOOLCALLITERATIONS = "maxToolCallIterations";
    String AI_P_MAXHISTORYTURNS = "maxHistoryTurns";
    String AI_P_DEFAULTSKILLPACKAGENAME = "defaultSkillPackageName";
    String AI_P_DEFAULTMEMORYPACKAGENAME = "defaultMemoryPackageName";

    // Agent管理
    String AI_P_AGENTNAME = "agentName";
    String AI_P_AGENTDESCRIPTION = "agentDescription";
    String AI_P_AGENTCONFIG = "agentConfig";
    String AI_P_AGENTINPUT = "agentInput";
    String AI_P_AGENTOUTPUT = "agentOutput";
    String AI_P_AGENTERROR = "agentError";
    String AI_P_SUBAGENTS = "subAgents";

    // Agent错误码
    String AGENT_ERR_OUTOFSCOPE = "out_of_scope";
    String AGENT_ERR_NOTFOUND = "agent_not_found";

    // ======================== MCP Agent 常量 ========================
    /** Agent type 参数：标记为 MCP 桥接 Agent */
    String AGENT_TYPE_MCP = "mcp";
    /** Agent type 参数：Agent 组（配置层串行链，不创建实例） */
    String AGENT_TYPE_GROUP = "group";
    /** Agent type 参数：普通 LLM Agent（默认） */
    String AGENT_TYPE_AGENT = "agent";
    /** Agent type 参数的配置键 */
    String AI_P_AGENTTYPE = "type";
    /** MCP Agent: 调用指定 tool */
    String MCP_CALLTOOL = "callTool";
    /** MCP Agent: 列出所有 tool */
    String MCP_LISTTOOLS = "listTools";
    /** MCP Agent: 重新发现 tool */
    String MCP_REFRESHTOOLS = "refreshTools";

    // ======================== Stream Callback 动作 ========================
    /** 流式块到达 */
    String STREAM_ONCHUNK = "onStreamChunk";
    /** 流式完成 */
    String STREAM_ONDONE = "onStreamDone";
    /** 流式错误 */
    String STREAM_ONERROR = "onStreamError";
    /** 获取流缓冲 */
    String STREAM_GETBUFFER = "getStreamBuffer";
    /** 清空流缓冲 */
    String STREAM_CLEARBUFFER = "clearStreamBuffer";
    /** 重置流状态 */
    String STREAM_RESET = "resetStream";
    /** 等待流完成 */
    String STREAM_WAITFORSTREAM = "waitForStream";
}
