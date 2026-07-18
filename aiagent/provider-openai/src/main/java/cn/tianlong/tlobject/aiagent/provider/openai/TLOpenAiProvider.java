package cn.tianlong.tlobject.aiagent.provider.openai;

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
 * OpenAI-compatible LLM Provider。
 * 支持所有兼容OpenAI Chat Completions API的服务（OpenAI、DeepSeek、Qwen、GLM等）。
 * 支持非流式completion、SSE流式completion、tool/function calling。
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public class TLOpenAiProvider extends TLLlmProvider {

    public TLOpenAiProvider() {
        super();
    }

    public TLOpenAiProvider(String name) {
        super(name);
    }

    public TLOpenAiProvider(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected String getCompletionsPath() {
        return "/v1/chat/completions";
    }

    // ======================== 请求体构建 ========================

    @Override
    public String buildRequestBody(TLMsg msg, List<TLConversationHistory> messages,
                                    List<TLFunctionDefinition> tools, boolean stream) {
        JsonObject body = new JsonObject();

        // model
        body.addProperty("model", getEffectiveModel(msg));

        // messages
        JsonArray msgs = new JsonArray();
        for (TLConversationHistory h : messages) {
            JsonObject m = new JsonObject();
            m.addProperty("role", h.getRole().name());

            if (h.getContent() != null && !h.getContent().isEmpty()) {
                m.addProperty("content", h.getContent());
            }

            // tool_calls（assistant消息中）
            if (h.getToolCalls() != null && !h.getToolCalls().isEmpty()) {
                JsonArray tcArray = new JsonArray();
                for (TLToolCall tc : h.getToolCalls()) {
                    JsonObject tcObj = new JsonObject();
                    tcObj.addProperty("id", tc.getId());
                    tcObj.addProperty("type", "function");
                    JsonObject func = new JsonObject();
                    func.addProperty("name", tc.getFunctionName());
                    // arguments 必须是 JSON 字符串，不是 JSON 对象
                    func.addProperty("arguments", gson.toJson(tc.getArguments() != null
                            ? tc.getArguments() : new LinkedHashMap<>()));
                    tcObj.add("function", func);
                    tcArray.add(tcObj);
                }
                m.add("tool_calls", tcArray);
            }

            // tool result消息
            if (h.getToolCallId() != null && !h.getToolCallId().isEmpty()) {
                m.addProperty("tool_call_id", h.getToolCallId());
            }
            if (h.getName() != null && !h.getName().isEmpty()) {
                m.addProperty("name", h.getName());
            }

            msgs.add(m);
        }
        body.add("messages", msgs);

        // tools
        if (tools != null && !tools.isEmpty()) {
            JsonArray ts = new JsonArray();
            for (TLFunctionDefinition fd : tools) {
                JsonObject t = new JsonObject();
                t.addProperty("type", "function");
                JsonObject f = new JsonObject();
                f.addProperty("name", fd.getName());
                f.addProperty("description", fd.getDescription());
                if (fd.getParameters() != null) {
                    f.add("parameters", gson.toJsonTree(fd.getParameters()));
                }
                t.add("function", f);
                ts.add(t);
            }
            body.add("tools", ts);
        }

        // parameters: 带tools时DeepSeek不接受temperature/max_tokens
        boolean hasTools = (tools != null && !tools.isEmpty());
        if (!hasTools) {
            body.addProperty("temperature", getEffectiveTemperature(msg));
        }
        body.addProperty("max_tokens", getEffectiveMaxTokens(msg));
        body.addProperty("stream", stream);

        // optional top_p
        if (msg.containsParam(AI_P_TOPP)) {
            body.addProperty("top_p", msg.getDoubleParam(AI_P_TOPP, 1.0));
        }

        // structured output: response_format（值 "json_object" 或 json_schema map）
        // 注意：DeepSeek json_object 要求 prompt 内含 "json" 字样；与 tools 的共存约束由调用方负责
        if (msg.containsParam(AI_P_RESPONSEFORMAT)) {
            Object rf = msg.getParam(AI_P_RESPONSEFORMAT);
            if (rf instanceof String) {
                JsonObject fmt = new JsonObject();
                fmt.addProperty("type", (String) rf);
                body.add("response_format", fmt);
            } else if (rf instanceof Map) {
                body.add("response_format", gson.toJsonTree(rf));
            }
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

            // 解析choices
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                return result.setParam(RESULT, false).setParam("error", "No choices in response");
            }

            JsonObject firstChoice = choices.get(0).getAsJsonObject();
            JsonObject message = firstChoice.getAsJsonObject("message");

            // 解析finish_reason
            String finishReason = firstChoice.has("finish_reason")
                    ? firstChoice.get("finish_reason").getAsString() : null;
            result.setParam("finishReason", finishReason);

            // 解析文本内容
            if (message.has("content") && !message.get("content").isJsonNull()) {
                result.setParam(AI_P_RESPONSE, message.get("content").getAsString());
            }

            // 解析tool_calls
            if (message.has("tool_calls") && !message.get("tool_calls").isJsonNull()) {
                JsonArray tcArray = message.getAsJsonArray("tool_calls");
                List<TLToolCall> toolCalls = parseToolCalls(tcArray);
                result.setParam(AI_P_TOOLCALLS, toolCalls);
                result.setParam("hasToolCalls", !toolCalls.isEmpty());
            } else {
                result.setParam(AI_P_TOOLCALLS, new ArrayList<TLToolCall>());
                result.setParam("hasToolCalls", false);
            }

            // 解析usage
            if (json.has("usage")) {
                JsonObject usage = json.getAsJsonObject("usage");
                result.setParam("promptTokens", usage.has("prompt_tokens")
                        ? usage.get("prompt_tokens").getAsInt() : 0);
                result.setParam("completionTokens", usage.has("completion_tokens")
                        ? usage.get("completion_tokens").getAsInt() : 0);
                result.setParam("totalTokens", usage.has("total_tokens")
                        ? usage.get("total_tokens").getAsInt() : 0);
            }

            // 解析model和id
            if (json.has("model")) result.setParam("responseModel", json.get("model").getAsString());
            if (json.has("id")) result.setParam("responseId", json.get("id").getAsString());

            result.setParam(RESULT, true);
            return result;
        } catch (Exception e) {
            putLog("Parse response error: " + e.toString(), LogLevel.ERROR);
            return result.setParam(RESULT, false).setParam(EXCEPTION, e.getMessage());
        }
    }

    /**
     * 解析OpenAI tool_calls格式
     */
    @SuppressWarnings("unchecked")
    protected List<TLToolCall> parseToolCalls(JsonArray tcArray) {
        List<TLToolCall> toolCalls = new ArrayList<>();
        for (JsonElement element : tcArray) {
            JsonObject tcObj = element.getAsJsonObject();
            TLToolCall tc = new TLToolCall();
            tc.setId(tcObj.has("id") ? tcObj.get("id").getAsString() : "");
            tc.setType(tcObj.has("type") ? tcObj.get("type").getAsString() : "function");

            JsonObject func = tcObj.getAsJsonObject("function");
            if (func == null || !func.has("name")) {
                putLog("Tool call missing function name, skipping", LogLevel.WARN);
                continue;
            }
            tc.setFunctionName(func.get("name").getAsString());

            String argsStr = func.has("arguments") ? func.get("arguments").getAsString() : null;
            if (argsStr != null && !argsStr.isEmpty()) {
                try {
                    Map<String, Object> args = gson.fromJson(argsStr, Map.class);
                    tc.setArguments(args);
                } catch (Exception e) {
                    tc.setArguments(new LinkedHashMap<>());
                }
            }
            toolCalls.add(tc);
        }
        return toolCalls;
    }

    // ======================== 核心动作实现 ========================

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg completion(Object fromWho, TLMsg msg) {
        // 提取参数
        List<TLConversationHistory> messages =
                (List<TLConversationHistory>) msg.getListParam(AI_P_MESSAGEHISTORY, new ArrayList<>());
        List<TLFunctionDefinition> tools =
                (List<TLFunctionDefinition>) msg.getListParam(AI_P_FUNCTIONDEFS, null);

        // 构建请求体
        String jsonBody = buildRequestBody(msg, messages, tools, false);

        // 发送HTTP请求
        long traceStart = System.currentTimeMillis();
        Request request = buildHttpRequest(getCompletionsPath(), jsonBody, null);
        putLog("LLM request to: " + request.url(), LogLevel.DEBUG);

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

        // 解析响应
        String responseBody = httpResult.getStringParam(AI_P_RESPONSEBODY, "");
        TLMsg parsedResult = parseResponse(responseBody, msg);

        // 合并HTTP元信息
        parsedResult.setParam(AI_P_HTTPSTATUS, httpResult.getParam(AI_P_HTTPSTATUS));
        return parsedResult;
    }

    @Override
    protected TLMsg embed(Object fromWho, TLMsg msg) {
        String text = msg.getStringParam(AI_P_EMBEDTEXT, "");
        if (text.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "embedText required");
        }
        String model = msg.getStringParam(AI_P_EMBEDDINGMODEL, "text-embedding-3-small");

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("input", text);

        Request request = buildHttpRequest("/v1/embeddings", gson.toJson(body), null);
        TLMsg httpResult = executeHttpRequest(request, fromWho, msg);

        if (!httpResult.parseBoolean(RESULT, false)) {
            return httpResult;
        }

        try {
            String responseBody = httpResult.getStringParam(AI_P_RESPONSEBODY, "");
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray data = json.getAsJsonArray("data");
            if (data == null || data.isEmpty()) {
                return createMsg().setParam(RESULT, false).setParam("error", "no embedding data returned");
            }
            JsonArray embedding = data.get(0).getAsJsonObject().getAsJsonArray("embedding");
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = embedding.get(i).getAsFloat();
            }
            return createMsg().setParam(RESULT, true).setParam(AI_P_EMBEDDING, vector);
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false).setParam(EXCEPTION, e.getMessage());
        }
    }

    @Override
    protected TLMsg completionStream(Object fromWho, TLMsg msg) {
        return doCompletionStream(fromWho, msg);
    }

    @Override
    protected Callback createStreamCallback(String resultFor, String resultAction,
                                             String sessionId, TLMsg msg) {
        return new StreamCallback(resultFor, resultAction, sessionId, msg);
    }

    @Override
    protected TLMsg listModels(Object fromWho, TLMsg msg) {
        String path = "/v1/models";
        Request request = buildHttpRequest(path, "", null);
        // 需要GET请求
        Request getRequest = new Request.Builder()
                .url(apiBaseUrl + path)
                .get()
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        TLMsg httpResult = executeHttpRequest(getRequest, fromWho, msg);
        if (!httpResult.parseBoolean(RESULT, false)) {
            return httpResult;
        }

        try {
            String body = httpResult.getStringParam(AI_P_RESPONSEBODY, "");
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            JsonArray data = json.getAsJsonArray("data");
            List<String> models = new ArrayList<>();
            if (data != null) {
                for (JsonElement e : data) {
                    String id = e.getAsJsonObject().get("id").getAsString();
                    models.add(id);
                }
            }
            return createMsg().setParam(RESULT, true).setParam("models", models);
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false).setParam(EXCEPTION, e.getMessage());
        }
    }

    // ======================== SSE流式回调内部类 ========================

    protected class StreamCallback implements Callback {
        private final String resultFor;
        private final String resultAction;
        private final String sessionId;
        private final TLMsg originalMsg;
        private final StringBuilder contentBuilder = new StringBuilder();
        private final List<TLToolCall> accumulatedToolCalls = new ArrayList<>();

        public StreamCallback(String resultFor, String resultAction, String sessionId, TLMsg originalMsg) {
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
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6);
                        if ("[DONE]".equals(data.trim())) {
                            // 解析流式累积的tool call arguments
                            for (TLToolCall tc : accumulatedToolCalls) {
                                if (tc.getArguments() != null && tc.getArguments().containsKey("_rawArgs")) {
                                    String rawArgs = (String) tc.getArguments().remove("_rawArgs");
                                    if (rawArgs != null && !rawArgs.isEmpty()) {
                                        try {
                                            Map<String, Object> parsed = gson.fromJson(rawArgs, Map.class);
                                            tc.setArguments(parsed);
                                        } catch (Exception e) {
                                            putLog("Failed to parse streamed tool call args: " + e.toString(), LogLevel.WARN);
                                        }
                                    }
                                }
                            }
                            // 发送完成信号
                            TLMsg doneMsg = createMsg()
                                    .setAction(resultAction)
                                    .setParam(AI_P_STREAMDONE, true)
                                    .setParam(AI_P_RESPONSE, contentBuilder.toString())
                                    .setParam(AI_P_SESSIONID, sessionId);
                            if (!accumulatedToolCalls.isEmpty()) {
                                doneMsg.setParam(AI_P_TOOLCALLS, accumulatedToolCalls);
                                doneMsg.setParam("hasToolCalls", true);
                            }
                            putMsg(resultFor, doneMsg);
                            continue;
                        }
                        try {
                            JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                            JsonArray choices = chunk.getAsJsonArray("choices");
                            if (choices != null && choices.size() > 0) {
                                JsonObject choice = choices.get(0).getAsJsonObject();
                                JsonObject delta = choice.getAsJsonObject("delta");

                                // 文本块
                                if (delta != null && delta.has("content") && !delta.get("content").isJsonNull()) {
                                    String text = delta.get("content").getAsString();
                                    contentBuilder.append(text);

                                    TLMsg chunkMsg = createMsg()
                                            .setAction(resultAction)
                                            .setParam(AI_P_CHUNK, text)
                                            .setParam(AI_P_SESSIONID, sessionId);
                                    putMsg(resultFor, chunkMsg);
                                }

                                // tool_calls块（流式累积）
                                if (delta != null && delta.has("tool_calls") && !delta.get("tool_calls").isJsonNull()) {
                                    JsonArray tcArray = delta.getAsJsonArray("tool_calls");
                                    for (JsonElement tcEl : tcArray) {
                                        JsonObject tcObj = tcEl.getAsJsonObject();
                                        int index = tcObj.has("index") ? tcObj.get("index").getAsInt() : 0;

                                        // 确保list足够长
                                        while (accumulatedToolCalls.size() <= index) {
                                            accumulatedToolCalls.add(new TLToolCall());
                                        }
                                        TLToolCall tc = accumulatedToolCalls.get(index);

                                        if (tcObj.has("id") && !tcObj.get("id").isJsonNull()) {
                                            tc.setId(tcObj.get("id").getAsString());
                                        }
                                        if (tcObj.has("type") && !tcObj.get("type").isJsonNull()) {
                                            tc.setType(tcObj.get("type").getAsString());
                                        }
                                        if (tcObj.has("function") && !tcObj.get("function").isJsonNull()) {
                                            JsonObject func = tcObj.getAsJsonObject("function");
                                            if (func.has("name") && !func.get("name").isJsonNull()) {
                                                tc.setFunctionName(func.get("name").getAsString());
                                            }
                                            if (func.has("arguments") && !func.get("arguments").isJsonNull()) {
                                                String argsFragment = func.get("arguments").getAsString();
                                                if (tc.getArguments() == null) {
                                                    tc.setArguments(new LinkedHashMap<>());
                                                }
                                                // 累积arguments片段，最终由完成信号合并
                                                // 流式tool call参数累积到metadata中
                                                String prevArgs = (String) (tc.getArguments().containsKey("_rawArgs")
                                                        ? tc.getArguments().get("_rawArgs") : "");
                                                tc.getArguments().put("_rawArgs", prevArgs + argsFragment);
                                            }
                                        }
                                    }
                                }
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
