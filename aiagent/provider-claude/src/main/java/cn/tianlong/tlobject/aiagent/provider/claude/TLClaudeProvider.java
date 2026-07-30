package cn.tianlong.tlobject.aiagent.provider.claude;

import cn.tianlong.tlobject.aiagent.*;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Claude API Provider。
 * 实现Anthropic Messages API格式的请求构建和响应解析。
 * 支持Claude 3/4系列模型的tool_use功能。
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public class TLClaudeProvider extends TLLlmProvider {

    /** Anthropic API版本头 */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    public TLClaudeProvider() {
        super();
    }

    public TLClaudeProvider(String name) {
        super(name);
    }

    public TLClaudeProvider(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected String getCompletionsPath() {
        return "/v1/messages";
    }

    // ======================== HTTP头覆写 ========================

    @Override
    protected Map<String, String> buildHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.put("x-api-key", apiKey);
        }
        headers.put("anthropic-version", ANTHROPIC_VERSION);
        // Prompt caching beta 头（仅在配置启用时发送）
        if (enablePromptCaching) {
            headers.put("anthropic-beta", promptCachingBeta);
        }
        return headers;
    }

    // ======================== 请求体构建 ========================

    @Override
    public String buildRequestBody(TLMsg msg, List<TLConversationHistory> messages,
                                    List<TLFunctionDefinition> tools, boolean stream) {
        JsonObject body = new JsonObject();

        // model
        body.addProperty("model", getEffectiveModel(msg));

        // max_tokens (required by Anthropic)
        body.addProperty("max_tokens", getEffectiveMaxTokens(msg));

        // stream
        body.addProperty("stream", stream);

        // temperature
        body.addProperty("temperature", getEffectiveTemperature(msg));

        // 提取system消息并分开设置
        String systemMsg = null;
        JsonArray msgsArray = new JsonArray();
        for (TLConversationHistory h : messages) {
            // reasoning 是内部思考过程，不发给 API
            if (h.getRole() == TLConversationHistory.Role.reasoning) continue;

            if (h.getRole() == TLConversationHistory.Role.system) {
                systemMsg = h.getContent();
                continue;
            }

            JsonObject m = new JsonObject();
            m.addProperty("role", h.getRole().name());

            // 构建content数组（Anthropic格式）
            JsonArray content = new JsonArray();

            if (h.getContent() != null && !h.getContent().isEmpty()) {
                JsonObject textBlock = new JsonObject();
                textBlock.addProperty("type", "text");
                textBlock.addProperty("text", h.getContent());
                content.add(textBlock);
            }

            // tool_use块（assistant消息中的tool calls）
            if (h.getToolCalls() != null && !h.getToolCalls().isEmpty()) {
                for (TLToolCall tc : h.getToolCalls()) {
                    JsonObject toolUse = new JsonObject();
                    toolUse.addProperty("type", "tool_use");
                    toolUse.addProperty("id", tc.getId());
                    toolUse.addProperty("name", tc.getFunctionName());
                    toolUse.add("input", gson.toJsonTree(tc.getArguments() != null
                            ? tc.getArguments() : new LinkedHashMap<>()));
                    content.add(toolUse);
                }
            }

            // tool_result块（tool角色消息）
            if (h.getRole() == TLConversationHistory.Role.tool) {
                // Anthropic要求tool_result使用不同的content格式
                m.addProperty("role", "user"); // Claude中没有单独的tool role
                content = new JsonArray();
                JsonObject toolResult = new JsonObject();
                toolResult.addProperty("type", "tool_result");
                toolResult.addProperty("tool_use_id", h.getToolCallId());
                if (h.getContent() != null) {
                    toolResult.addProperty("content", h.getContent());
                }
                content.add(toolResult);
            }

            m.add("content", content);
            msgsArray.add(m);
        }
        body.add("messages", msgsArray);

        // system (Anthropic分离的system prompt)
        if (systemMsg != null && !systemMsg.isEmpty()) {
            boolean caching = getEffectivePromptCaching(msg);
            if (caching) {
                // 转换为内容块数组格式以支持 cache_control
                JsonArray systemBlocks = new JsonArray();
                JsonObject textBlock = new JsonObject();
                textBlock.addProperty("type", "text");
                textBlock.addProperty("text", systemMsg);
                JsonObject cacheControl = new JsonObject();
                cacheControl.addProperty("type", "ephemeral");
                textBlock.add("cache_control", cacheControl);
                systemBlocks.add(textBlock);
                body.add("system", systemBlocks);
            } else {
                body.addProperty("system", systemMsg);
            }
        }

        // tools
        if (tools != null && !tools.isEmpty()) {
            JsonArray ts = new JsonArray();
            for (TLFunctionDefinition fd : tools) {
                JsonObject t = new JsonObject();
                t.addProperty("name", fd.getName());
                t.addProperty("description", fd.getDescription());
                if (fd.getParameters() != null) {
                    t.add("input_schema", gson.toJsonTree(fd.getParameters()));
                }
                ts.add(t);
            }
            // 对最后一个 tool 添加 cache_control 断点（启用缓存时）
            if (getEffectivePromptCaching(msg)) {
                addCacheControlToLast(ts);
            }
            body.add("tools", ts);
        }

        // top_p
        if (msg.containsParam(AI_P_TOPP)) {
            body.addProperty("top_p", msg.getDoubleParam(AI_P_TOPP, 1.0));
        }

        // stop_sequences
        if (msg.containsParam(AI_P_STOP)) {
            JsonArray stops = new JsonArray();
            String stopStr = msg.getStringParam(AI_P_STOP, "");
            if (!stopStr.isEmpty()) {
                for (String s : stopStr.split(",")) {
                    stops.add(s.trim());
                }
                body.add("stop_sequences", stops);
            }
        }

        // thinking (Claude Extended Thinking)
        // - reasoningMode=native|auto 时显式开启
        // - Claude Fable 5 / Opus 4 强制要求 thinking 参数（API 硬约束），即使 off 也发最小 budget
        String reasoningMode = msg.getStringParam(AI_P_REASONING_MODE, "off");
        String model = getEffectiveModel(msg);
        boolean isThinkingModel = model != null && (model.contains("fable-5") || model.contains("opus-4"));
        boolean shouldEnableThinking = "native".equals(reasoningMode) || "auto".equals(reasoningMode) || isThinkingModel;

        if (shouldEnableThinking) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "enabled");
            int budget = msg.getIntParam(AI_P_THINKING_BUDGET, 4000);
            if (!"native".equals(reasoningMode) && !"auto".equals(reasoningMode)) {
                // 模型硬约束：off/prompt 模式下也发最小 budget
                budget = Math.min(budget, 1024);
            }
            thinking.addProperty("budget_tokens", budget);
            body.add("thinking", thinking);
            // Claude thinking 与 temperature/top_p 冲突，移除
            body.remove("temperature");
            body.remove("top_p");
        }

        return body.toString();
    }

    // ======================== 响应解析 ========================

    @Override
    public TLMsg parseResponse(String responseBody, TLMsg originalMsg) {
        TLMsg result = createMsg();
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

            // 检查错误
            if (json.has("error")) {
                JsonObject error = json.getAsJsonObject("error");
                String errMsg = error.has("message") ? error.get("message").getAsString() : "Unknown error";
                return result.setParam(RESULT, false).setParam("error", errMsg);
            }

            // 解析停止原因
            String stopReason = json.has("stop_reason") ? json.get("stop_reason").getAsString() : null;
            result.setParam("finishReason", stopReason);
            result.setParam("hasToolCalls", "tool_use".equals(stopReason));

            // 解析content blocks
            JsonArray content = json.getAsJsonArray("content");
            StringBuilder textContent = new StringBuilder();
            StringBuilder reasoningContent = new StringBuilder();
            List<TLToolCall> toolCalls = new ArrayList<>();

            if (content != null) {
                for (JsonElement block : content) {
                    JsonObject blockObj = block.getAsJsonObject();
                    String type = blockObj.get("type").getAsString();

                    if ("text".equals(type)) {
                        textContent.append(blockObj.get("text").getAsString());
                    } else if ("tool_use".equals(type)) {
                        TLToolCall tc = new TLToolCall();
                        tc.setId(blockObj.get("id").getAsString());
                        tc.setFunctionName(blockObj.get("name").getAsString());

                        JsonObject input = blockObj.getAsJsonObject("input");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> args = gson.fromJson(input, Map.class);
                        tc.setArguments(args);
                        toolCalls.add(tc);
                    } else if ("thinking".equals(type)) {
                        if (blockObj.has("thinking") && !blockObj.get("thinking").isJsonNull()) {
                            reasoningContent.append(blockObj.get("thinking").getAsString());
                        }
                    } else if ("redacted_thinking".equals(type)) {
                        if (blockObj.has("data") && !blockObj.get("data").isJsonNull()) {
                            reasoningContent.append(blockObj.get("data").getAsString());
                        }
                    }
                }
            }

            if (reasoningContent.length() > 0) {
                result.setParam(AI_P_REASONING, reasoningContent.toString());
            }

            result.setParam(AI_P_RESPONSE, textContent.toString());
            result.setParam(AI_P_TOOLCALLS, toolCalls);

            // 解析usage
            if (json.has("usage")) {
                JsonObject usage = json.getAsJsonObject("usage");
                result.setParam("promptTokens", usage.has("input_tokens")
                        ? usage.get("input_tokens").getAsInt() : 0);
                result.setParam("completionTokens", usage.has("output_tokens")
                        ? usage.get("output_tokens").getAsInt() : 0);
                // 缓存相关token（Anthropic prompt caching）
                result.setParam(AI_P_CACHECREATIONTOKENS, usage.has("cache_creation_input_tokens")
                        ? usage.get("cache_creation_input_tokens").getAsLong() : 0L);
                result.setParam(AI_P_CACHEHITTOKENS, usage.has("cache_read_input_tokens")
                        ? usage.get("cache_read_input_tokens").getAsLong() : 0L);
            }

            // 解析model和id
            if (json.has("model")) result.setParam("responseModel", json.get("model").getAsString());
            if (json.has("id")) result.setParam("responseId", json.get("id").getAsString());

            result.setParam(RESULT, true);
            return result;
        } catch (Exception e) {
            putLog("Parse Claude response error: " + e.toString(), LogLevel.ERROR);
            return result.setParam(RESULT, false).setParam(EXCEPTION, e.getMessage());
        }
    }

    // ======================== 核心动作实现 ========================

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg completion(Object fromWho, TLMsg msg) {
        List<TLConversationHistory> messages =
                (List<TLConversationHistory>) msg.getListParam(AI_P_MESSAGEHISTORY, new ArrayList<>());
        List<TLFunctionDefinition> tools =
                (List<TLFunctionDefinition>) msg.getListParam(AI_P_FUNCTIONDEFS, null);

        String jsonBody = buildRequestBody(msg, messages, tools, false);

        long traceStart = System.currentTimeMillis();
        Request request = buildHttpRequest(getCompletionsPath(), jsonBody, null);
        putLog("Claude request to: " + request.url(), LogLevel.DEBUG);

        TLMsg httpResult = executeHttpRequest(request, fromWho, msg);

        // debug trace: 记录原始请求/响应
        traceLlmCall(
                msg.getStringParam(AI_P_SESSIONID, "default"),
                msg.getSource() != null ? msg.getSource().toString() : "unknown",
                jsonBody,
                httpResult.getStringParam(AI_P_RESPONSEBODY, ""),
                httpResult.getIntParam(AI_P_HTTPSTATUS, 0),
                getEffectiveModel(msg),
                System.currentTimeMillis() - traceStart
        );

        if (!httpResult.parseBoolean(RESULT, false)) {
            return httpResult;
        }

        String responseBody = httpResult.getStringParam(AI_P_RESPONSEBODY, "");
        TLMsg parsedResult = parseResponse(responseBody, msg);

        // 累加并记录缓存统计
        long cacheCreated = parsedResult.getLongParam(AI_P_CACHECREATIONTOKENS, 0L);
        long cacheHit = parsedResult.getLongParam(AI_P_CACHEHITTOKENS, 0L);
        if (cacheCreated > 0 || cacheHit > 0) {
            String sid = msg.getStringParam(AI_P_SESSIONID, "default");
            long[] totals = accumulateCacheStats(sid, cacheCreated, cacheHit, 0);
            parsedResult.setParam(AI_P_CACHECREATIONTOKENS_TOTAL, (int) totals[0]);
            parsedResult.setParam(AI_P_CACHEHITTOKENS_TOTAL, (int) totals[1]);
            logCacheEvent(sid, cacheCreated, cacheHit, 0, getEffectiveModel(msg));
        }

        return parsedResult;
    }

    @Override
    protected TLMsg completionStream(Object fromWho, TLMsg msg) {
        return doCompletionStream(fromWho, msg);
    }

    @Override
    protected Callback createStreamCallback(String resultFor, String resultAction,
                                             String sessionId, TLMsg msg) {
        return new ClaudeStreamCallback(resultFor, resultAction, sessionId, msg);
    }

    @Override
    protected TLMsg listModels(Object fromWho, TLMsg msg) {
        // Anthropic没有公开的list models API，返回已知模型列表
        List<String> models = new ArrayList<>();
        models.add("claude-opus-4-8-20250805");
        models.add("claude-sonnet-4-6");
        models.add("claude-haiku-4-5-20251001");
        models.add("claude-fable-5-20250619");
        return createMsg().setParam(RESULT, true).setParam("models", models);
    }

    // ======================== Claude SSE流式回调 ========================

    protected class ClaudeStreamCallback implements Callback {
        private final String resultFor;
        private final String resultAction;
        private final String sessionId;
        private final TLMsg originalMsg;
        private final StringBuilder contentBuilder = new StringBuilder();
        private final StringBuilder reasoningBuilder = new StringBuilder();
        private final List<TLToolCall> accumulatedToolCalls = new ArrayList<>();
        private final Map<Integer, StringBuilder> toolUseInputBuilders = new LinkedHashMap<>();
        /** 缓存统计（从 message_start 事件提取） */
        private long cacheCreationTokens = 0;
        private long cacheHitTokens = 0;

        public ClaudeStreamCallback(String resultFor, String resultAction, String sessionId, TLMsg originalMsg) {
            this.resultFor = resultFor;
            this.resultAction = resultAction;
            this.sessionId = sessionId;
            this.originalMsg = originalMsg;
        }

        @Override
        public void onFailure(Call call, IOException e) {
            TLMsg errMsg = createMsg()
                    .setAction(resultAction)
                    .setParam(AI_P_STREAMERROR, e.getMessage())
                    .setParam(AI_P_SESSIONID, sessionId);
            putMsg(resultFor, errMsg);
        }

        @Override
        public void onResponse(Call call, Response response) throws IOException {
            if (!response.isSuccessful()) {
                TLMsg errMsg = createMsg()
                        .setAction(resultAction)
                        .setParam(AI_P_STREAMERROR, "HTTP " + response.code())
                        .setParam(AI_P_SESSIONID, sessionId);
                putMsg(resultFor, errMsg);
                response.close();
                return;
            }

            try (Response resp = response;
                 BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    // Claude SSE格式: data: {...} 或 event: ...
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6);
                        try {
                            JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                            String eventType = chunk.has("type") ? chunk.get("type").getAsString() : "";

                            switch (eventType) {
                                case "content_block_start":
                                    JsonObject contentBlock = chunk.getAsJsonObject("content_block");
                                    if (contentBlock == null || !contentBlock.has("type")) {
                                        putLog("SSE content_block_start missing content_block/type, skipping", LogLevel.WARN);
                                        break;
                                    }
                                    String blockType = contentBlock.get("type").getAsString();
                                    if ("thinking".equals(blockType)) {
                                        // thinking 块开始，后续 thinking_delta 追加内容
                                        if (contentBlock.has("thinking") && !contentBlock.get("thinking").isJsonNull()) {
                                            reasoningBuilder.append(contentBlock.get("thinking").getAsString());
                                        }
                                    } else if ("tool_use".equals(blockType)) {
                                        int index = chunk.has("index") ? chunk.get("index").getAsInt() : accumulatedToolCalls.size();
                                        TLToolCall tc = new TLToolCall();
                                        tc.setId(contentBlock.get("id").getAsString());
                                        tc.setFunctionName(contentBlock.get("name").getAsString());
                                        while (accumulatedToolCalls.size() <= index) {
                                            accumulatedToolCalls.add(null);
                                        }
                                        accumulatedToolCalls.set(index, tc);
                                        toolUseInputBuilders.put(index, new StringBuilder());
                                    }
                                    break;

                                case "message_start":
                                    // 从 message_start 事件提取 usage（含缓存统计）
                                    JsonObject msgObj = chunk.getAsJsonObject("message");
                                    if (msgObj != null && msgObj.has("usage")) {
                                        JsonObject usageObj = msgObj.getAsJsonObject("usage");
                                        if (usageObj.has("cache_creation_input_tokens")) {
                                            cacheCreationTokens = usageObj.get("cache_creation_input_tokens").getAsLong();
                                        }
                                        if (usageObj.has("cache_read_input_tokens")) {
                                            cacheHitTokens = usageObj.get("cache_read_input_tokens").getAsLong();
                                        }
                                    }
                                    break;

                                case "content_block_delta":
                                    JsonObject delta = chunk.getAsJsonObject("delta");
                                    if (delta == null || !delta.has("type")) {
                                        putLog("SSE content_block_delta missing delta/type, skipping", LogLevel.WARN);
                                        break;
                                    }
                                    String deltaType = delta.get("type").getAsString();
                                    if ("thinking_delta".equals(deltaType)) {
                                        String thinkingText = delta.get("thinking").getAsString();
                                        reasoningBuilder.append(thinkingText);
                                        TLMsg reasoningChunkMsg = createMsg()
                                                .setAction(resultAction)
                                                .setParam(AI_P_REASONING_CHUNK, thinkingText)
                                                .setParam(AI_P_SESSIONID, sessionId);
                                        putMsg(resultFor, reasoningChunkMsg);
                                    } else if ("signature_delta".equals(deltaType)) {
                                        // signature 是 Claude 思考块的签名，不需要展示给用户
                                        if (delta.has("signature")) {
                                            reasoningBuilder.append(delta.get("signature").getAsString());
                                        }
                                    } else if ("text_delta".equals(deltaType)) {
                                        String text = delta.get("text").getAsString();
                                        contentBuilder.append(text);
                                        TLMsg chunkMsg = createMsg()
                                                .setAction(resultAction)
                                                .setParam(AI_P_CHUNK, text)
                                                .setParam(AI_P_SESSIONID, sessionId);
                                        putMsg(resultFor, chunkMsg);
                                    } else if ("input_json_delta".equals(deltaType)) {
                                        int index = chunk.has("index") ? chunk.get("index").getAsInt() : 0;
                                        String partialJson = delta.get("partial_json").getAsString();
                                        toolUseInputBuilders.computeIfAbsent(index, k -> new StringBuilder())
                                                .append(partialJson);
                                    }
                                    break;

                                case "content_block_stop":
                                    // 检查tool use块完成
                                    int stopIndex = chunk.has("index") ? chunk.get("index").getAsInt() : -1;
                                    if (stopIndex >= 0 && toolUseInputBuilders.containsKey(stopIndex)) {
                                        String fullInput = toolUseInputBuilders.get(stopIndex).toString();
                                        TLToolCall tc = accumulatedToolCalls.get(stopIndex);
                                        if (tc != null && !fullInput.isEmpty()) {
                                            try {
                                                @SuppressWarnings("unchecked")
                                                Map<String, Object> args = gson.fromJson(fullInput, Map.class);
                                                tc.setArguments(args);
                                            } catch (Exception e) {
                                                tc.setArguments(new LinkedHashMap<>());
                                            }
                                        }
                                    }
                                    break;

                                case "message_stop":
                                    // 消息完成
                                    TLMsg doneMsg = createMsg()
                                            .setAction(resultAction)
                                            .setParam(AI_P_STREAMDONE, true)
                                            .setParam(AI_P_RESPONSE, contentBuilder.toString())
                                            .setParam(AI_P_SESSIONID, sessionId);

                                    if (reasoningBuilder.length() > 0) {
                                        doneMsg.setParam(AI_P_REASONING, reasoningBuilder.toString());
                                    }

                                    // 过滤掉null的tool call
                                    List<TLToolCall> validToolCalls = new ArrayList<>();
                                    for (TLToolCall tc : accumulatedToolCalls) {
                                        if (tc != null) validToolCalls.add(tc);
                                    }
                                    if (!validToolCalls.isEmpty()) {
                                        doneMsg.setParam(AI_P_TOOLCALLS, validToolCalls);
                                        doneMsg.setParam("hasToolCalls", true);
                                    }
                                    // 附加缓存统计
                                    if (cacheCreationTokens > 0 || cacheHitTokens > 0) {
                                        doneMsg.setParam(AI_P_CACHECREATIONTOKENS, (int) cacheCreationTokens);
                                        doneMsg.setParam(AI_P_CACHEHITTOKENS, (int) cacheHitTokens);
                                        long[] totals = accumulateCacheStats(sessionId,
                                                cacheCreationTokens, cacheHitTokens, 0);
                                        doneMsg.setParam(AI_P_CACHECREATIONTOKENS_TOTAL, (int) totals[0]);
                                        doneMsg.setParam(AI_P_CACHEHITTOKENS_TOTAL, (int) totals[1]);
                                        logCacheEvent(sessionId, cacheCreationTokens, cacheHitTokens,
                                                0, originalMsg.getStringParam(AI_P_MODEL, defaultModel));
                                    }
                                    putMsg(resultFor, doneMsg);
                                    break;

                                case "error":
                                    String errMsg = chunk.has("error")
                                            ? chunk.getAsJsonObject("error").get("message").getAsString()
                                            : "Unknown error";
                                    TLMsg errorMsg = createMsg()
                                            .setAction(resultAction)
                                            .setParam(AI_P_STREAMERROR, errMsg)
                                            .setParam(AI_P_SESSIONID, sessionId);
                                    putMsg(resultFor, errorMsg);
                                    break;

                                default:
                                    break;
                            }
                        } catch (Exception parseErr) {
                            putLog("SSE parse error: " + parseErr.toString(), LogLevel.WARN);
                        }
                    }
                }
            }
        }
    }
}
