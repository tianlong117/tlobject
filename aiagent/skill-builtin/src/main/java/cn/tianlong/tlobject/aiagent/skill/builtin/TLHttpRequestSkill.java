package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import okhttp3.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * HTTP请求Skill。允许LLM发起HTTP GET/POST请求获取外部数据。
 * 函数名: http_request
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public class TLHttpRequestSkill extends TLBaseSkill {

    private OkHttpClient httpClient;
    private Gson gson;

    public TLHttpRequestSkill() {
        super();
    }

    public TLHttpRequestSkill(String name) {
        super(name);
    }

    public TLHttpRequestSkill(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (skillName == null || skillName.isEmpty())
            skillName = "http_request";
        if (skillDescription == null || skillDescription.isEmpty())
            skillDescription = "Send HTTP requests to fetch web content or call APIs. Supports GET and POST methods with custom headers and body.";

        // 默认参数schema
        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();
            Map<String, Object> urlProp = new LinkedHashMap<>();
            urlProp.put("type", "string");
            urlProp.put("description", "The URL to send the request to");
            urlProp.put("required", true);
            parameterSchema.put("url", urlProp);

            Map<String, Object> methodProp = new LinkedHashMap<>();
            methodProp.put("type", "string");
            methodProp.put("description", "HTTP method: GET or POST");
            methodProp.put("enum", new String[]{"GET", "POST"});
            parameterSchema.put("method", methodProp);

            Map<String, Object> headersProp = new LinkedHashMap<>();
            headersProp.put("type", "object");
            headersProp.put("description", "Optional HTTP headers as key-value pairs");
            parameterSchema.put("headers", headersProp);

            Map<String, Object> bodyProp = new LinkedHashMap<>();
            bodyProp.put("type", "string");
            bodyProp.put("description", "Request body (for POST requests)");
            parameterSchema.put("body", bodyProp);
        }
    }

    @Override
    protected TLBaseModule init() {
        gson = new Gson();
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        // 提取输入
        Map<String, Object> input = (Map<String, Object>) msg.getMapParam(AI_P_SKILLINPUT, new LinkedHashMap<>());
        if (input.isEmpty()) {
            // 兼容直接从msg参数提取
            input = new LinkedHashMap<>();
            if (msg.containsParam("url")) input.put("url", msg.getStringParam("url", ""));
            if (msg.containsParam("method")) input.put("method", msg.getStringParam("method", "GET"));
            if (msg.containsParam("headers")) input.put("headers", msg.getMapParam("headers", new LinkedHashMap<>()));
            if (msg.containsParam("body")) input.put("body", msg.getStringParam("body", ""));
        }

        String url = (String) input.getOrDefault("url", "");
        String method = (String) input.getOrDefault("method", "GET");
        Map<String, Object> headers = (Map<String, Object>) input.getOrDefault("headers", new LinkedHashMap<>());
        String body = (String) input.getOrDefault("body", "");

        if (url.isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error: URL is required");
        }

        try {
            Request.Builder builder = new Request.Builder().url(url);

            // 设置请求头
            if (headers != null) {
                for (Map.Entry<String, Object> h : headers.entrySet()) {
                    builder.addHeader(h.getKey(), String.valueOf(h.getValue()));
                }
            }

            // 设置请求方法
            if ("POST".equalsIgnoreCase(method)) {
                MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
                RequestBody requestBody = RequestBody.create(mediaType,
                        body != null ? body : "");
                builder.post(requestBody);
            } else {
                builder.get();
            }

            Request request = builder.build();
            putLog("HTTP Skill: " + method + " " + url, LogLevel.DEBUG);

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                int statusCode = response.code();

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", statusCode);
                result.put("body", responseBody.length() > 5000
                        ? responseBody.substring(0, 5000) + "...(truncated)" : responseBody);
                result.put("headers", response.headers().toMultimap());

                return createMsg()
                        .setParam(RESULT, statusCode >= 200 && statusCode < 400)
                        .setParam(AI_P_SKILLOUTPUT, gson.toJson(result));
            }
        } catch (IOException e) {
            putLog("HTTP Skill error: " + e.toString(), LogLevel.ERROR);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "HTTP request failed: " + e.getMessage());
        }
    }
}
