package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import okhttp3.Callback;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Mock LLM Provider — 可编程的假 Provider，用于确定性单元测试。
 * <p>
 * 不发送任何网络请求。通过预设响应队列（{@link #enqueueResponse} /
 * {@link #enqueueStreamResponses}）注入期望的 LLM 行为，
 * {@link TLAiAgent} 完全感知不到差异（所有交互都是 putMsg）。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 纯文本响应
 * mockProvider.enqueueResponse(
 *     mockProvider.createMsg().setParam(RESULT, true)
 *         .setParam(AI_P_RESPONSE, "1+1=2"));
 *
 * // 工具调用响应
 * TLToolCall tc = new TLToolCall();
 * tc.setId("call_1"); tc.setFunctionName("httpRequest"); tc.setArguments(Map.of("url", "http://test"));
 * mockProvider.enqueueResponse(
 *     mockProvider.createMsg().setParam(RESULT, true)
 *         .setParam(AI_P_RESPONSE, "").setParam("hasToolCalls", true)
 *         .setParam(AI_P_TOOLCALLS, Collections.singletonList(tc)));
 *
 * // 流式响应
 * mockProvider.enqueueStreamResponses("session_1", Arrays.asList(
 *     chunkMsg("你"), chunkMsg("好"), doneMsg("你好")));
 * </pre>
 *
 * <h3>XML 配置</h3>
 * <pre>
 * &lt;module name="mockProvider" classfile="cn.tianlong.tlobject.aiagent.TLMockProvider"
 *         singleton="true" defaultModel="mock-model"/&gt;
 * </pre>
 *
 * 创建日期：2026/8/12
 * 作者：tianlong
 */
public class TLMockProvider extends TLLlmProvider {

    // ======================== 预设响应队列 ========================

    /** 同步 completion 响应队列（线程安全，每次调用消耗一个） */
    private final ConcurrentLinkedQueue<TLMsg> responseQueue = new ConcurrentLinkedQueue<>();

    /** 流式响应队列：sessionId → chunk/done 消息队列 */
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<TLMsg>> streamQueueMap
            = new ConcurrentHashMap<>();

    // ======================== 构造器 ========================

    public TLMockProvider() {
        super();
    }

    public TLMockProvider(String name) {
        super(name);
    }

    public TLMockProvider(String name, TLObjectFactory factory) {
        super(name, factory);
    }

    // ======================== 生命周期 ========================

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        // Mock Provider 不需要真实的 apiKey/apiBaseUrl，设置默认值避免 null
        if (apiKey == null) apiKey = "mock-key";
        if (apiBaseUrl == null) apiBaseUrl = "http://mock.local";
        if (defaultModel == null) defaultModel = "mock-model";
    }

    @Override
    protected TLBaseModule init() {
        // 不需要 OkHttpClient，跳过父类的 init() 逻辑
        gson = new com.google.gson.GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss").create();
        putLog("TLMockProvider [" + name + "] initialized, model=" + defaultModel, LogLevel.DEBUG);
        return this;
    }

    // ======================== 核心重写 ========================

    @Override
    protected TLMsg completion(Object fromWho, TLMsg msg) {
        return consumeResponse(msg);
    }

    @Override
    protected TLMsg completionStream(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "mock_default");
        String resultFor = msg.getStringParam(RESULTFOR, null);
        if (resultFor == null) {
            putLog("TLMockProvider: completionStream called without RESULTFOR — falling back to sync",
                    LogLevel.WARN);
            return consumeResponse(msg);
        }

        ConcurrentLinkedQueue<TLMsg> streamQueue = streamQueueMap.get(sessionId);
        if (streamQueue == null || streamQueue.isEmpty()) {
            // 无预设流式响应，回退到同步队列
            putLog("TLMockProvider: no stream responses for session=" + sessionId
                    + " — falling back to sync queue", LogLevel.DEBUG);
            return consumeResponse(msg);
        }

        // 启动后台线程按序发射 chunk → done 消息
        String resultAction = msg.getStringParam(RESULTACTION, "onStreamChunk");
        Thread streamThread = new Thread(() -> {
            try {
                // 短暂延迟让 Agent 初始化好流式接收链路
                Thread.sleep(100);
                TLMsg chunk;
                while ((chunk = streamQueue.poll()) != null) {
                    // 覆盖 action 为 Provider msg 指定的 RESULTACTION（Agent 设 "onStreamResult"）
                    chunk.setAction(resultAction);
                    chunk.setParam(AI_P_SESSIONID, sessionId);
                    putMsg(resultFor, chunk);
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                putLog("TLMockProvider stream error: " + e.toString(), LogLevel.ERROR);
            }
        }, "mock-stream-" + sessionId);
        streamThread.setDaemon(true);
        streamThread.start();

        return null; // 异步，无返回值
    }

    /**
     * 覆盖父类的 checkProvider，始终返回 true。
     * 避免因响应队列为空导致 ping completion 失败。
     */
    @Override
    public boolean checkProvider() {
        putLog("TLMockProvider [" + name + "] checkProvider: always available", LogLevel.DEBUG);
        return true;
    }

    @Override
    protected TLMsg listModels(Object fromWho, TLMsg msg) {
        return createMsg().setParam(RESULT, true)
                .setParam("models", Arrays.asList(defaultModel != null ? defaultModel : "mock-model"));
    }

    // ======================== 未实现的方法（桩） ========================

    @Override
    protected Callback createStreamCallback(String resultFor, String resultAction,
                                            String sessionId, TLMsg msg) {
        throw new UnsupportedOperationException("TLMockProvider does not use OkHttp callbacks");
    }

    @Override
    public String buildRequestBody(TLMsg msg, List<TLConversationHistory> messages,
                                   List<TLFunctionDefinition> tools, boolean stream) {
        throw new UnsupportedOperationException("TLMockProvider does not build HTTP requests");
    }

    @Override
    public TLMsg parseResponse(String responseBody, TLMsg originalMsg) {
        throw new UnsupportedOperationException("TLMockProvider does not parse HTTP responses");
    }

    @Override
    protected String getCompletionsPath() {
        throw new UnsupportedOperationException("TLMockProvider does not use HTTP endpoints");
    }

    // ======================== 响应队列管理 API ========================

    /**
     * 入队一个同步 completion 响应。
     * 每次 {@link #completion} 调用会消耗一个。
     */
    public void enqueueResponse(TLMsg response) {
        responseQueue.offer(response);
    }

    /**
     * 批量入队多个同步 completion 响应。
     */
    public void enqueueResponses(TLMsg... responses) {
        for (TLMsg r : responses) {
            responseQueue.offer(r);
        }
    }

    /**
     * 入队流式响应（session 级别）。
     * 每次 {@link #completionStream} 调用会消耗对应 session 的全部消息。
     *
     * @param sessionId 目标会话 ID（需与 chat 请求中的 sessionId 一致）
     * @param chunks    按序排列的消息列表（chunk 消息 + 结束消息）
     */
    public void enqueueStreamResponses(String sessionId, List<TLMsg> chunks) {
        ConcurrentLinkedQueue<TLMsg> queue = streamQueueMap.computeIfAbsent(
                sessionId, k -> new ConcurrentLinkedQueue<>());
        queue.addAll(chunks);
    }

    /**
     * 清除所有预设响应（同步 + 流式）。
     */
    public void clearAllResponses() {
        responseQueue.clear();
        streamQueueMap.clear();
    }

    /**
     * 查询同步队列中剩余的响应数量。
     */
    public int pendingResponseCount() {
        return responseQueue.size();
    }

    /**
     * 查询指定 session 流式队列中剩余的消息数量。
     */
    public int pendingStreamCount(String sessionId) {
        ConcurrentLinkedQueue<TLMsg> q = streamQueueMap.get(sessionId);
        return q != null ? q.size() : 0;
    }

    // ======================== 便捷工厂方法 ========================

    /**
     * 创建一个纯文本 completion 响应。
     */
    public TLMsg textResponse(String text) {
        return createMsg()
                .setParam(RESULT, true)
                .setParam(AI_P_RESPONSE, text)
                .setParam("hasToolCalls", false)
                .setParam(AI_P_PROMPTTOKENS, 100)
                .setParam(AI_P_COMPLETIONTOKENS, text.length())
                .setParam(AI_P_TOTALTOKENS, 100 + text.length());
    }

    /**
     * 创建一个带工具调用的 completion 响应。
     */
    public TLMsg toolCallResponse(List<TLToolCall> toolCalls) {
        return createMsg()
                .setParam(RESULT, true)
                .setParam(AI_P_RESPONSE, "")
                .setParam("hasToolCalls", true)
                .setParam(AI_P_TOOLCALLS, toolCalls)
                .setParam(AI_P_PROMPTTOKENS, 200)
                .setParam(AI_P_COMPLETIONTOKENS, 50)
                .setParam(AI_P_TOTALTOKENS, 250);
    }

    /**
     * 创建一个带 tool_calls 的响应（单次调用便捷版）。
     */
    public TLMsg toolCallResponse(TLToolCall toolCall) {
        return toolCallResponse(Collections.singletonList(toolCall));
    }

    /**
     * 创建一个错误响应。
     */
    public TLMsg errorResponse(String errorMessage) {
        return createMsg()
                .setParam(RESULT, false)
                .setParam(AI_P_RESPONSE, "LLM 调用失败：" + errorMessage)
                .setParam("error", errorMessage);
    }

    /**
     * 创建一个流式 chunk 消息（AI_P_CHUNK）。
     */
    public TLMsg streamChunk(String text) {
        return createMsg().setAction(STREAM_ONCHUNK).setParam(AI_P_CHUNK, text);
    }

    /**
     * 创建一个流式 done 消息（包含最终文本，可选 tool_calls）。
     */
    public TLMsg streamDone(String finalText) {
        return createMsg().setAction(STREAM_ONCHUNK)
                .setParam(AI_P_STREAMDONE, true)
                .setParam(AI_P_RESPONSE, finalText);
    }

    /**
     * 创建一个流式 done 消息（带 tool_calls）。
     */
    public TLMsg streamDoneWithToolCalls(List<TLToolCall> toolCalls) {
        return createMsg().setAction(STREAM_ONCHUNK)
                .setParam(AI_P_STREAMDONE, true)
                .setParam(AI_P_RESPONSE, "")
                .setParam("hasToolCalls", true)
                .setParam(AI_P_TOOLCALLS, toolCalls);
    }

    /**
     * 创建一个可被 Agent 识别的 ToolCall 对象。
     */
    public static TLToolCall createToolCall(String id, String functionName, Map<String, Object> arguments) {
        TLToolCall tc = new TLToolCall();
        tc.setId(id);
        tc.setFunctionName(functionName);
        tc.setArguments(arguments != null ? arguments : Collections.emptyMap());
        return tc;
    }

    // ======================== 内部方法 ========================

    /**
     * 从同步队列中消费一个预设响应。
     */
    private TLMsg consumeResponse(TLMsg requestMsg) {
        TLMsg response = responseQueue.poll();
        if (response != null) {
            putLog("TLMockProvider: returning preset response ("
                    + responseQueue.size() + " remaining)", LogLevel.DEBUG);
            return response;
        }
        // 队列为空，返回一个无害的 pass 响应；
        // 避免 errorSupervisor/记忆召回等额外 LLM 调用因空队列而失败。
        putLog("TLMockProvider: response queue empty! Returning fallback pass.", LogLevel.DEBUG);
        return createMsg()
                .setParam(RESULT, true)
                .setParam(AI_P_RESPONSE, "{\"pass\": true, \"score\": 1.0, \"reason\": \"mock fallback\"}")
                .setParam("finishReason", "stop")
                .setParam(AI_P_PROMPTTOKENS, 0)
                .setParam(AI_P_COMPLETIONTOKENS, 0)
                .setParam(AI_P_TOTALTOKENS, 0);
    }
}
