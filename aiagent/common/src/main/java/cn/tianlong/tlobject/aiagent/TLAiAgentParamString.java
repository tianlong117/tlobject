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

    /** 会话运行监控模块名 */
    String M_AGENTMONITOR = "agentMonitor";

    // ======================== Agent动作 ========================
    /** 发送用户消息，获取AI回复（非流式，完整chat循环含tool-call） */
    String AGENT_CHAT = "chat";
    /** 流式chat */
    String AGENT_CHATSTREAM = "chatStream";
    /** 热加载第三方脚本skill */
    String AGENT_HOTLOADSKILL = "hotLoadSkill";
    /** 热卸载skill */
    String AGENT_HOTUNLOADSKILL = "hotUnloadSkill";
    /** 重载Skill（重新构建已有skill） */
    String AGENT_RELOADSKILL = "reloadSkill";
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
    /** 重载子Agent（重新构建已有agent） */
    String AGENT_RELOADAGENT = "reloadAgent";
    /** 列出所有已注册子Agent */
    String AGENT_LISTAGENTS = "listAgents";
    /** 获取Agent描述（XML description + md frontmatter 已合并；master 生成 delegate_to 描述时向子 agent 发此消息，未实现则回落配置 description） */
    String AGENT_GETDESCRIPTION = "getAgentDescription";
    /** 向子 agent 索取其贡献的工具定义（实现者如 MCP 返回 functionDefinitions + toolRoutes 展开为 N 个工具；未实现则 master 生成默认 delegate_to_xxx） */
    String AGENT_GETTOOLDEFS = "getToolDefinitions";
    /** 委托任务给子Agent */
    String AGENT_DELEGATE = "delegateToAgent";
    /** 执行 msgTool（LLM 可调用的预定义消息） */
    String AGENT_MSGTOOLEXECUTE = "msgToolExecute";
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
    /** 检查 LLM Provider 是否可用（复用启动检查 checkProvider()，供外部/测试查询） */
    String AGENT_CHECKPROVIDER = "checkProvider";
    /** 查询指定 session 的累计 token 用量 */
    String AGENT_GETTOKENUSAGE = "getTokenUsage";
    /** 运行时切换skill启用状态（不删实例） */
    String AGENT_SETSKILLENABLED = "setSkillEnabled";
    /** 更新已注册skill的tool定义（描述/参数schema） */
    String AGENT_UPDATESKILL = "updateSkill";
    /** 更新已注册子Agent的描述 */
    String AGENT_UPDATEAGENT = "updateAgent";
    /** 中断指定 session 正在进行的 chat（协作式取消） */
    String AGENT_STOPCHAT = "stopChat";

    // ======================== LLM Provider动作 ========================
    /** 发送completion请求（非流式） */
    String LLM_COMPLETION = "completion";
    /** 发送流式completion请求 */
    String LLM_COMPLETIONSTREAM = "completionStream";
    /** 列出可用模型 */
    String LLM_LISTMODELS = "listModels";
    /** 取消进行中的请求 */
    String LLM_CANCEL = "cancelLlm";
    /** 文本向量化（embedding），返回 float[] */
    String LLM_EMBEDDING = "embedding";
    /** 开启调试追踪（运行时） */
    String LLM_DEBUG_ON = "debugOn";
    /** 关闭调试追踪（运行时） */
    String LLM_DEBUG_OFF = "debugOff";
    /** 获取指定 session 的 trace 文件内容 */
    String LLM_GETTRACES = "getTraces";
    /** 清除指定 session 的 trace 文件 */
    String LLM_CLEARTRACES = "clearTraces";

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
    /** 结构化输出：response_format（值 "json_object" 或 json_schema map） */
    String AI_P_RESPONSEFORMAT = "responseFormat";
    /** tool-call 因到达 maxToolCallIterations 而截断的标志 */
    String AI_P_TRUNCATED = "truncated";

    // Token 用量（与 Provider parseResponse 已用的裸 key 一致）
    String AI_P_PROMPTTOKENS = "promptTokens";
    String AI_P_COMPLETIONTOKENS = "completionTokens";
    String AI_P_TOTALTOKENS = "totalTokens";
    /** 累计用量参数键（会话级） */
    String AI_P_PROMPTTOKENS_TOTAL = "promptTokensTotal";
    String AI_P_COMPLETIONTOKENS_TOTAL = "completionTokensTotal";
    String AI_P_TOTALTOKENS_TOTAL = "totalTokensTotal";

    // Skill
    String AI_P_SKILLNAME = "skillName";
    String AI_P_SKILLDESCRIPTION = "skillDescription";
    String AI_P_SKILLPARAMS = "skillParams";
    String AI_P_SKILLINPUT = "skillInput";
    String AI_P_SKILLOUTPUT = "skillOutput";
    String AI_P_ENABLED = "enabled";

    // Memory
    String AI_P_MEMORYKEY = "memoryKey";
    String AI_P_MEMORYVALUE = "memoryValue";
    String AI_P_MEMORYTYPE = "memoryType";
    String AI_P_MEMORYTAG = "memoryTag";
    String AI_P_MEMORYEXPTIME = "memoryExptime";
    String AI_P_MEMORYQUERY = "memoryQuery";
    String AI_P_MEMORYRESULT = "memoryResult";
    String AI_P_TOPK = "topK";
    /** embedding 功能开关：true 启用向量搜索（需配 embeddingProvider） */
    String AI_P_EMBEDDINGENABLE = "enableEmbedding";
    /** embedding 调用的 Provider 模块名（如 \"openAiProvider\"） */
    String AI_P_EMBEDDINGPROVIDER = "embeddingProvider";
    /** embedding 模型名（如 \"text-embedding-3-small\"，deepseek 的默认即此） */
    String AI_P_EMBEDDINGMODEL = "embeddingModel";
    /** embedding 输入文本 */
    String AI_P_EMBEDTEXT = "embedText";
    /** embedding 返回的浮点向量（float[]） */
    String AI_P_EMBEDDING = "embeddingVector";

    // 流式
    String AI_P_CHUNK = "chunk";
    String AI_P_STREAMDONE = "streamDone";
    String AI_P_STREAMERROR = "streamError";
    /** chat 被 /stop 中断的标志 */
    String AI_P_CANCELLED = "cancelled";

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

    // 推理/思考链 (ReAct)
    /** 推理模式：off | prompt | native | auto */
    String AI_P_REASONING_MODE = "reasoningMode";
    /** 推理内容是否暴露给调用方 */
    String AI_P_REASONING_VISIBLE = "reasoningVisible";
    /** Claude Extended Thinking token预算 */
    String AI_P_THINKING_BUDGET = "thinkingBudget";
    /** 推理文本内容 */
    String AI_P_REASONING = "reasoning";
    /** 流式推理块 */
    String AI_P_REASONING_CHUNK = "reasoningChunk";
    /** 结构化推理步骤列表 */
    String AI_P_REASONING_STEPS = "reasoningSteps";

    // Agent管理
    String AI_P_AGENTNAME = "agentName";
    String AI_P_AGENTDESCRIPTION = "agentDescription";
    String AI_P_AGENTCONFIG = "agentConfig";
    /** 工具路由映射：functionName → 原生 toolName（AGENT_GETTOOLDEFS 返回参数，master 据此登记调用路由；defs 列表复用 AI_P_FUNCTIONDEFS） */
    String AI_P_TOOLROUTES = "toolRoutes";
    String AI_P_AGENTINPUT = "agentInput";
    String AI_P_AGENTOUTPUT = "agentOutput";
    String AI_P_AGENTERROR = "agentError";
    /** delegate_to 返回：子 agent 需要更多信息才能完成任务 */
    String AI_P_NEEDSCLARIFICATION = "needsClarification";
    /** delegate_to 返回：子 agent 提出的澄清问题 */
    String AI_P_CLARIFICATIONQUESTION = "clarificationQuestion";
    /** 内建工具：向用户请求澄清（function name） */
    String AGENT_REQUESTCLARITY = "request_clarification";
    String AI_P_SUBAGENTS = "subAgents";

    // Agent错误码
    String AGENT_ERR_OUTOFSCOPE = "out_of_scope";
    String AGENT_ERR_NOTFOUND = "agent_not_found";

    // ======================== MCP Agent 常量 ========================
    /** Agent type 参数：标记为 MCP 桥接 Agent */
    String AGENT_TYPE_MCP = "mcp";
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

    // ======================== Session 持久化/断点恢复 ========================
    /** 保存会话到持久化存储 */
    String AGENT_SAVESESSION = "saveSession";
    /** 从持久化存储加载会话 */
    String AGENT_LOADSESSION = "loadSession";
    /** 删除持久化的会话 */
    String AGENT_DELETESESSION = "deleteSession";
    /** 会话状态：断点（mid-loop） */
    String SESSION_STATE_CHECKPOINT = "checkpoint";
    /** 会话状态：已完成 */
    String SESSION_STATE_COMPLETED = "completed";
    /** 会话状态：等待人工审批 */
    String SESSION_STATE_PENDING_APPROVAL = "pending_approval";
    /** 查找未完成的检查点会话（state=checkpoint） */
    String FIND_INCOMPLETE_CHECKPOINTS = "findIncompleteCheckpoints";
    /** 列出所有历史会话 */
    String LIST_SESSIONS = "listSessions";

    // ======================== 工作流编排 ========================
    /** 工作流模块名 */
    String M_WORKFLOW = "agentWorkflow";
    /** 执行工作流 */
    String WORKFLOW_EXECUTE = "doWorkflow";
    /** 工作流输入 */
    String WORKFLOW_INPUT = "workflowInput";
    /** 工作流输出 */
    String WORKFLOW_OUTPUT = "workflowOutput";

    // ======================== Plan Task ========================
    /** 任务分解 Skill 模块名 */
    String M_PLANTASK = "planTask";

    // ======================== HITL 审批机制 ========================
    /** 审批模块名 */
    String M_APPROVALMODULE = "approvalModule";
    /** 审批请求 */
    String APPROVAL_REQUEST = "approvalRequest";
    /** 审批批准 */
    String APPROVAL_APPROVE = "approvalApprove";
    /** 审批拒绝 */
    String APPROVAL_REJECT = "approvalReject";
    /** 查询审批状态 */
    String APPROVAL_QUERY = "approvalQuery";
    /** 审批状态：pending/approved/rejected/expired */
    String AI_P_APPROVAL_STATE = "_approvalState";
    /** 审批 ID（UUID 短码） */
    String AI_P_APPROVAL_ID = "approvalId";
    /** 待审批的工具名 */
    String AI_P_APPROVAL_TOOLNAME = "approvalToolName";
    /** 待审批的工具参数 */
    String AI_P_APPROVAL_TOOLARGS = "approvalToolArguments";
    /** 审批决策：approved / rejected */
    String AI_P_APPROVAL_DECISION = "approvalDecision";
    /** 拒绝原因 */
    String AI_P_APPROVAL_REJECTREASON = "approvalRejectReason";
    /** 用户修改后的参数 */
    String AI_P_APPROVAL_MODIFIEDARGS = "approvalModifiedArguments";
    /** 内建工具：LLM 主动请求人工审批 */
    String AGENT_REQUIREAPPROVAL = "require_approval";

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

    // ======================== Routing Provider ========================
    /** 委托的真实 Provider 引用名（resolve from factory） */
    String AI_P_DELEGATEPROVIDER = "delegateProvider";
    /** 路由器策略：rule_only | hybrid | llm_judge */
    String AI_P_ROUTER_STRATEGY = "strategy";
    /** 路由器命中的 tier 名称（simple/complex/default） */
    String AI_P_ROUTER_TIER = "routerTier";
}

