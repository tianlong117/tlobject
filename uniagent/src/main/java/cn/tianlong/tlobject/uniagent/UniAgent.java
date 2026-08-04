package cn.tianlong.tlobject.uniagent;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UniAgent — 统一 Agent，只跟 LLM 聊天。
 *
 * 职责边界：
 * - ✅ 上下文管理（对话历史）
 * - ✅ LLM 调用（completion / streaming）
 * - ✅ 工具调用时委托给 ToolExecutionEngine
 * - ✅ 工具定义从 ToolFactory 获取
 * - ❌ 不管理工具生命周期（ToolFactory 的职责）
 * - ❌ 不处理工具执行细节（ToolExecutionEngine 的职责）
 * - ❌ 不区分 skill/agent/msgtool（都是工具模块）
 *
 * 配置参数（XML <params>）:
 * - llmProvider: Provider 模块名（默认 "openAiProvider"）
 * - contextModule: 上下文模块名（默认 "aiContext"）
 * - toolFactory: 工具工厂模块名（默认 "toolFactory"）
 * - toolEngine: 工具执行引擎模块名（默认 "toolExecutionEngine"）
 * - defaultModel: 默认模型（默认 "gpt-4o"）
 * - defaultTemperature: 默认温度（默认 0.7）
 * - defaultMaxTokens: 默认最大token（默认 4096）
 * - maxToolCallIterations: 最大工具调用轮次（默认 10）
 * - maxHistoryTurns: 最大历史轮次（默认 50）
 * - systemMessage: 系统提示词
 */
public class UniAgent extends TLBaseModule implements UniAgentParamString {

    // Provider 和模块引用（通过 putMsg 运行时解析）
    private String providerName = "openAiProvider";
    private String contextModuleName = "aiContext";
    private String toolFactoryName = "toolFactory";
    private String toolEngineName = "toolExecutionEngine";

    // 默认参数
    private String defaultModel = "gpt-4o";
    private double defaultTemperature = 0.7;
    private int defaultMaxTokens = 4096;
    private int maxToolCallIterations = 10;
    private int maxHistoryTurns = 50;
    private String systemMessage;

    // 运行时状态
    /** sessionId → cancelFlag */
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    /** sessionId → workerThread */
    private final Map<String, Thread> chatThreads = new ConcurrentHashMap<>();

    // ======================== 生命周期 ========================

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null) {
            providerName = params.getOrDefault("llmProvider", providerName);
            contextModuleName = params.getOrDefault("contextModule", contextModuleName);
            toolFactoryName = params.getOrDefault("toolFactory", toolFactoryName);
            toolEngineName = params.getOrDefault("toolEngine", toolEngineName);
            defaultModel = params.getOrDefault("defaultModel", defaultModel);
            defaultTemperature = TLDataUtils.parseDouble(params.get("defaultTemperature"), defaultTemperature);
            defaultMaxTokens = TLDataUtils.parseInt(params.get("defaultMaxTokens"), defaultMaxTokens);
            maxToolCallIterations = TLDataUtils.parseInt(params.get("maxToolCallIterations"), maxToolCallIterations);
            maxHistoryTurns = TLDataUtils.parseInt(params.get("maxHistoryTurns"), maxHistoryTurns);
            systemMessage = params.get("systemMessage");
        }
    }

    @Override
    protected TLBaseModule init() {
        return this;
    }

    // ======================== 消息分发 ========================

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        String action = msg.getAction();
        if (action == null) return null;

        switch (action) {
            case AGENT_CHAT:        return chat(fromWho, msg);
            case AGENT_CHATSTREAM:  return chatStream(fromWho, msg);
            case AGENT_STOPCHAT:    return stopChat(fromWho, msg);
            case AGENT_GETDESCRIPTION: return getAgentDescription(fromWho, msg);
            case AGENT_GETTOOLDEFS: return getMyToolDefs(fromWho, msg);
            case SKILL_EXECUTE:     return executeAsTool(fromWho, msg);
            case SKILL_VALIDATE:    return createMsg().setParam(RESULT, true);
            default: break;
        }
        return null;
    }

    // ======================== 核心：聊天 ========================

    /** 非流式聊天入口 */
    protected TLMsg chat(Object fromWho, TLMsg msg) {
        return doChat(fromWho, msg, false);
    }

    /** 流式聊天入口 */
    protected TLMsg chatStream(Object fromWho, TLMsg msg) {
        return doChat(fromWho, msg, true);
    }

    /**
     * 核心聊天循环。
     * 1. 构建上下文 → 2. LLM调用 → 3. 有 tool_calls 送 Engine → 4. 回填结果 → 循环 → 5. 保存上下文
     */
    @SuppressWarnings("unchecked")
    protected TLMsg doChat(Object fromWho, TLMsg msg, boolean stream) {
        // --- 提取参数 ---
        String sessionId = (String) msg.getParam(AI_P_SESSIONID);
        if (sessionId == null) sessionId = UUID.randomUUID().toString().substring(0, 8);
        String userMessage = (String) msg.getParam(AI_P_USERMESSAGE);
        String model = (String) msg.getParam(AI_P_MODEL);
        if (model == null) model = defaultModel;
        double temperature = msg.getParam(AI_P_TEMPERATURE) instanceof Number
                ? ((Number) msg.getParam(AI_P_TEMPERATURE)).doubleValue() : defaultTemperature;
        int maxTokens = msg.getParam(AI_P_MAXTOKENS) instanceof Number
                ? ((Number) msg.getParam(AI_P_MAXTOKENS)).intValue() : defaultMaxTokens;

        // --- 初始化取消标志 ---
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        cancelFlags.put(sessionId, cancelFlag);
        chatThreads.put(sessionId, Thread.currentThread());

        List<TLConversationHistory> history = new ArrayList<>();
        TLMsg result = createMsg();

        try {
            // --- 1. 构建上下文 ---
            history.addAll(getContextHistory(sessionId));
            if (history.isEmpty() && systemMessage != null) {
                history.add(new TLConversationHistory(TLConversationHistory.Role.system, systemMessage));
            }
            if (userMessage != null && !userMessage.isEmpty()) {
                history.add(new TLConversationHistory(TLConversationHistory.Role.user, userMessage));
            }

            // --- 2. 获取工具定义 ---
            List<TLFunctionDefinition> toolDefs = getToolDefinitions();

            // --- 3. 工具调用循环 ---
            int iteration = 0;
            while (iteration < maxToolCallIterations) {
                if (cancelFlag.get()) {
                    result.setParam(AI_P_RESPONSE, "⏹ 已中断");
                    result.setParam(AI_P_CANCELLED, true);
                    return result;
                }

                iteration++;

                // 3a. LLM 调用
                TLMsg llmResult = callLLM(history, model, temperature, maxTokens, toolDefs, stream);
                if (cancelFlag.get()) {
                    result.setParam(AI_P_RESPONSE, "⏹ 已中断");
                    result.setParam(AI_P_CANCELLED, true);
                    return result;
                }
                if (llmResult == null) {
                    result.setParam(AI_P_RESPONSE, "LLM call returned null");
                    return result;
                }

                // 3b. 检查是否有 tool_calls
                List<TLToolCall> toolCalls = (List<TLToolCall>) llmResult.getParam(AI_P_TOOLCALLS);
                if (toolCalls == null || toolCalls.isEmpty()) {
                    // 纯文本回复，结束循环
                    String response = (String) llmResult.getParam(AI_P_RESPONSE);
                    history.add(new TLConversationHistory(TLConversationHistory.Role.assistant, response));
                    result.setParam(AI_P_RESPONSE, response);
                    break;
                }

                // 3c. 记录 assistant tool_calls 消息
                history.add(new TLConversationHistory(TLConversationHistory.Role.assistant, toolCalls));

                // 3d. 委托给 ToolExecutionEngine 执行
                TLMsg engineMsg = createMsg()
                        .setAction(ENGINE_EXECUTETOOLS)
                        .setParam(AI_P_TOOLCALLS, toolCalls)
                        .setParam(AI_P_SESSIONID, sessionId);
                TLMsg engineResult = putMsg(toolEngineName, engineMsg);

                if (cancelFlag.get()) {
                    result.setParam(AI_P_RESPONSE, "⏹ 已中断");
                    result.setParam(AI_P_CANCELLED, true);
                    return result;
                }

                // 3e. 将工具结果追加到 history
                List<ToolExecutionEngine.ToolCallResult> toolResults =
                        (List<ToolExecutionEngine.ToolCallResult>) engineResult.getParam(AI_P_TOOLRESULTS);
                if (toolResults != null) {
                    for (ToolExecutionEngine.ToolCallResult tr : toolResults) {
                        if (tr.cancelled) continue;
                        history.add(new TLConversationHistory(
                                tr.toolCall.getId(),
                                tr.toolCall.getFunctionName(),
                                tr.success ? tr.output : "Error: " + tr.error));
                    }
                }
            }

            // --- 4. 检查是否达到最大轮次 ---
            if (result.getParam(AI_P_RESPONSE) == null) {
                result.setParam(AI_P_RESPONSE, "达到最大工具调用轮次 (" + maxToolCallIterations + ")，已截断。");
                result.setParam(AI_P_TRUNCATED, true);
            }

            // --- 5. 保存上下文 ---
            saveContextHistory(sessionId, history);

            return result;
        } catch (Exception e) {
            putLog("doChat error: " + e.toString(), LogLevel.ERROR);
            result.setParam(AI_P_RESPONSE, "Agent error: " + e.getMessage());
            return result;
        } finally {
            cancelFlags.remove(sessionId);
            chatThreads.remove(sessionId);
        }
    }

    // ======================== LLM 调用 ========================

    /** 调用 LLM Provider，发送 completion 消息 */
    @SuppressWarnings("unchecked")
    private TLMsg callLLM(List<TLConversationHistory> history, String model,
                          double temperature, int maxTokens,
                          List<TLFunctionDefinition> toolDefs, boolean stream) {
        TLMsg llmMsg = createMsg()
                .setAction(stream ? LLM_COMPLETIONSTREAM : LLM_COMPLETION)
                .setParam(AI_P_MESSAGEHISTORY, history)
                .setParam(AI_P_MODEL, model)
                .setParam(AI_P_TEMPERATURE, temperature)
                .setParam(AI_P_MAXTOKENS, maxTokens);

        if (toolDefs != null && !toolDefs.isEmpty()) {
            llmMsg.setParam(AI_P_FUNCTIONDEFS, toolDefs);
        }

        return putMsg(providerName, llmMsg);
    }

    // ======================== 上下文操作 ========================

    /** 从上下文模块获取会话历史 */
    @SuppressWarnings("unchecked")
    private List<TLConversationHistory> getContextHistory(String sessionId) {
        try {
            TLMsg ctxMsg = createMsg()
                    .setAction(CONTEXT_GETMESSAGES)
                    .setParam(AI_P_SESSIONID, sessionId);
            TLMsg result = putMsg(contextModuleName, ctxMsg);
            if (result != null && result.getParam(AI_P_MESSAGEHISTORY) instanceof List) {
                return (List<TLConversationHistory>) result.getParam(AI_P_MESSAGEHISTORY);
            }
        } catch (Exception e) {
            putLog("Failed to get context history: " + e.toString(), LogLevel.WARN);
        }
        return new ArrayList<>();
    }

    /** 保存会话历史到上下文模块 */
    private void saveContextHistory(String sessionId, List<TLConversationHistory> history) {
        try {
            TLMsg saveMsg = createMsg()
                    .setAction(CONTEXT_REPLACE)
                    .setParam(AI_P_SESSIONID, sessionId)
                    .setParam(AI_P_MESSAGEHISTORY, history);
            putMsg(contextModuleName, saveMsg);
        } catch (Exception e) {
            putLog("Failed to save context: " + e.toString(), LogLevel.WARN);
        }
    }

    // ======================== 工具定义 ========================

    /** 从 ToolFactory 获取工具定义列表 */
    @SuppressWarnings("unchecked")
    private List<TLFunctionDefinition> getToolDefinitions() {
        try {
            TLMsg query = createMsg().setAction(AGENT_GETTOOLDEFS);
            TLMsg result = putMsg(toolFactoryName, query);
            if (result != null && result.getParam(AI_P_FUNCTIONDEFS) instanceof List) {
                return (List<TLFunctionDefinition>) result.getParam(AI_P_FUNCTIONDEFS);
            }
        } catch (Exception e) {
            putLog("Failed to get tool definitions: " + e.toString(), LogLevel.WARN);
        }
        return Collections.emptyList();
    }

    // ======================== 停止 ========================

    /** 中断聊天 */
    protected TLMsg stopChat(Object fromWho, TLMsg msg) {
        String sessionId = (String) msg.getParam(AI_P_SESSIONID);

        if (sessionId != null) {
            // 设置取消标志
            AtomicBoolean flag = cancelFlags.get(sessionId);
            if (flag != null) flag.set(true);

            // 中断工作线程
            Thread worker = chatThreads.get(sessionId);
            if (worker != null) worker.interrupt();

            // 通知 Engine 也取消
            TLMsg cancelMsg = createMsg()
                    .setAction(AGENT_STOPCHAT)
                    .setParam(AI_P_SESSIONID, sessionId);
            putMsg(toolEngineName, cancelMsg);

            // 取消 Provider 的 HTTP 请求
            TLMsg cancelLlm = createMsg()
                    .setAction(LLM_CANCEL)
                    .setParam(AI_P_SESSIONID, sessionId);
            putMsg(providerName, cancelLlm);
        } else {
            // 没有 sessionId 则取消所有
            for (AtomicBoolean flag : cancelFlags.values()) flag.set(true);
            for (Thread t : chatThreads.values()) t.interrupt();
        }

        return createMsg().setParam(AI_P_RESPONSE, "Stopped");
    }

    // ======================== 描述 ========================

    protected TLMsg getAgentDescription(Object fromWho, TLMsg msg) {
        String desc = name;
        if (params != null && params.containsKey("agentDescription")) {
            desc = params.get("agentDescription");
        }
        return createMsg().setParam(AI_P_RESPONSE, desc);
    }

    // ======================== 工具协议（作为子Agent被调用时） ========================

    /** 返回自身的 function definition（用于被父 Agent 作为工具发现） */
    protected TLMsg getMyToolDefs(Object fromWho, TLMsg msg) {
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> taskProp = new LinkedHashMap<>();
        taskProp.put("type", "string");
        taskProp.put("description", "委托给 " + name + " 的任务描述");
        taskProp.put("required", true);
        props.put("task", taskProp);

        String desc = name + " 子Agent";
        if (params != null && params.containsKey("agentDescription"))
            desc = params.get("agentDescription");

        TLFunctionDefinition def = TLFunctionDefinition.create(name, desc, props);
        List<TLFunctionDefinition> defs = new ArrayList<>();
        defs.add(def);
        return createMsg().setParam(AI_P_FUNCTIONDEFS, defs);
    }

    /** 作为工具被执行：提取 task 参数，调用 doChat */
    @SuppressWarnings("unchecked")
    protected TLMsg executeAsTool(Object fromWho, TLMsg msg) {
        Object input = msg.getParam(AI_P_SKILLINPUT);
        String userMessage;

        if (input instanceof Map) {
            userMessage = String.valueOf(((Map) input).getOrDefault("task",
                    input.toString()));
        } else if (input instanceof String) {
            userMessage = (String) input;
        } else {
            userMessage = String.valueOf(input);
        }

        // 构造 chat 消息并调用 doChat
        String childSessionId = name + "-" + System.currentTimeMillis() % 100000;
        TLMsg chatMsg = createMsg()
                .setParam(AI_P_SESSIONID, childSessionId)
                .setParam(AI_P_USERMESSAGE, userMessage);

        TLMsg result = doChat(fromWho, chatMsg, false);
        String response = (String) result.getParam(AI_P_RESPONSE);
        return createMsg().setParam(AI_P_SKILLOUTPUT,
                response != null ? response : "[子Agent无回复]");
    }
}
