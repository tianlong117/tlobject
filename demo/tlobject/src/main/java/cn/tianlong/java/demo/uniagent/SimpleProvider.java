package cn.tianlong.java.demo.uniagent;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.uniagent.*;

import com.google.gson.*;
import okhttp3.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 简易 OpenAI 兼容 Provider，专为 uniagent Demo 使用。
 * 直接处理 uniagent 的 TLConversationHistory 类型，零 aiagent 依赖。
 *
 * 配置参数:
 * - apiKey: API密钥
 * - apiBaseUrl: API基础URL (默认 https://api.openai.com)
 * - defaultModel: 默认模型 (默认 gpt-4o)
 */
public class SimpleProvider extends TLBaseModule implements UniAgentParamString {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    private String apiKey;
    private String apiBaseUrl = "https://api.openai.com";
    private String defaultModel = "gpt-4o";
    private Gson gson = new Gson();

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null) {
            apiKey = params.getOrDefault("apiKey", apiKey);
            apiBaseUrl = params.getOrDefault("apiBaseUrl", apiBaseUrl);
            defaultModel = params.getOrDefault("defaultModel", defaultModel);
        }
    }

    @Override
    protected TLBaseModule init() {
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        String action = msg.getAction();
        if (LLM_COMPLETION.equals(action)) return doCompletion(fromWho, msg);
        if (LLM_COMPLETIONSTREAM.equals(action)) return doCompletion(fromWho, msg); // stream fallback to non-stream
        if (LLM_CANCEL.equals(action)) return createMsg(); // no-op for simple provider
        return null;
    }

    @SuppressWarnings("unchecked")
    protected TLMsg doCompletion(Object fromWho, TLMsg msg) {
        List<TLConversationHistory> history =
                (List<TLConversationHistory>) msg.getParam(AI_P_MESSAGEHISTORY);
        String model = (String) msg.getParam(AI_P_MODEL);
        if (model == null) model = defaultModel;
        double temperature = getDoubleParam(msg, AI_P_TEMPERATURE, 0.7);
        int maxTokens = getIntParam(msg, AI_P_MAXTOKENS, 4096);
        List<TLFunctionDefinition> toolDefs =
                (List<TLFunctionDefinition>) msg.getParam(AI_P_FUNCTIONDEFS);

        try {
            // 构建 OpenAI 请求体
            JsonObject body = new JsonObject();
            body.addProperty("model", model);

            // messages
            JsonArray messages = new JsonArray();
            for (TLConversationHistory h : history) {
                JsonObject m = new JsonObject();
                String role = h.getRole().name();
                if (h.getRole() == TLConversationHistory.Role.tool) {
                    m.addProperty("role", "tool");
                    m.addProperty("tool_call_id", h.getToolCallId());
                    m.addProperty("content", h.getContent() != null ? h.getContent() : "");
                } else if (h.getRole() == TLConversationHistory.Role.assistant && h.isAssistantWithToolCalls()) {
                    m.addProperty("role", "assistant");
                    JsonArray tcs = new JsonArray();
                    for (TLToolCall tc : h.getToolCalls()) {
                        JsonObject tco = new JsonObject();
                        tco.addProperty("id", tc.getId());
                        tco.addProperty("type", "function");
                        JsonObject func = new JsonObject();
                        func.addProperty("name", tc.getFunctionName());
                        func.add("arguments", gson.toJsonTree(tc.getArguments()));
                        tco.add("function", func);
                        tcs.add(tco);
                    }
                    m.add("tool_calls", tcs);
                } else if (h.getRole() == TLConversationHistory.Role.reasoning) {
                    continue; // skip reasoning messages in API call
                } else {
                    m.addProperty("role", role);
                    m.addProperty("content", h.getContent() != null ? h.getContent() : "");
                }
                messages.add(m);
            }
            body.add("messages", messages);

            body.addProperty("temperature", temperature);
            body.addProperty("max_tokens", maxTokens);

            // tools
            if (toolDefs != null && !toolDefs.isEmpty()) {
                JsonArray tools = new JsonArray();
                for (TLFunctionDefinition def : toolDefs) {
                    JsonObject tool = new JsonObject();
                    tool.addProperty("type", "function");
                    JsonObject func = new JsonObject();
                    func.addProperty("name", def.getName());
                    func.addProperty("description", def.getDescription() != null ? def.getDescription() : "");
                    func.add("parameters", gson.toJsonTree(def.getParameters()));
                    tool.add("function", func);
                    tools.add(tool);
                }
                body.add("tools", tools);
                body.addProperty("tool_choice", "auto");
            }

            // 发送 HTTP 请求
            String url = apiBaseUrl;
            if (!url.endsWith("/")) url += "/";
            url += "chat/completions";

            RequestBody requestBody = RequestBody.create(JSON, gson.toJson(body));
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    putLog("LLM API error: " + response.code() + " " + responseBody, LogLevel.ERROR);
                    return createMsg().setParam(AI_P_RESPONSE, "API error: " + response.code());
                }
                return parseResponse(responseBody);
            }
        } catch (IOException e) {
            putLog("LLM call failed: " + e.toString(), LogLevel.ERROR);
            return createMsg().setParam(AI_P_RESPONSE, "LLM error: " + e.getMessage());
        }
    }

    /** 解析 OpenAI 响应 */
    private TLMsg parseResponse(String jsonStr) {
        try {
            JsonObject root = gson.fromJson(jsonStr, JsonObject.class);
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                return createMsg().setParam(AI_P_RESPONSE, "No response from LLM");
            }

            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message");

            TLMsg result = createMsg();

            // 文本回复
            if (message.has("content") && !message.get("content").isJsonNull()) {
                result.setParam(AI_P_RESPONSE, message.get("content").getAsString());
            }

            // tool calls
            if (message.has("tool_calls") && !message.get("tool_calls").isJsonNull()) {
                JsonArray tcs = message.getAsJsonArray("tool_calls");
                List<TLToolCall> toolCalls = new ArrayList<>();
                for (int i = 0; i < tcs.size(); i++) {
                    JsonObject tc = tcs.get(i).getAsJsonObject();
                    String id = tc.get("id").getAsString();
                    JsonObject func = tc.getAsJsonObject("function");
                    String name = func.get("name").getAsString();
                    Map<String, Object> args = gson.fromJson(func.get("arguments").getAsString(), Map.class);
                    toolCalls.add(new TLToolCall(id, name, args));
                }
                result.setParam(AI_P_TOOLCALLS, toolCalls);
            }

            // token usage
            if (root.has("usage")) {
                JsonObject usage = root.getAsJsonObject("usage");
                result.setParam(AI_P_PROMPTTOKENS, usage.get("prompt_tokens").getAsInt());
                result.setParam(AI_P_COMPLETIONTOKENS, usage.get("completion_tokens").getAsInt());
                result.setParam(AI_P_TOTALTOKENS, usage.get("total_tokens").getAsInt());
            }

            return result;
        } catch (Exception e) {
            putLog("Failed to parse LLM response: " + e.toString(), LogLevel.ERROR);
            return createMsg().setParam(AI_P_RESPONSE, "Parse error: " + e.getMessage());
        }
    }

    private double getDoubleParam(TLMsg msg, String key, double def) {
        Object v = msg.getParam(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        return def;
    }

    private int getIntParam(TLMsg msg, String key, int def) {
        Object v = msg.getParam(key);
        if (v instanceof Number) return ((Number) v).intValue();
        return def;
    }
}
