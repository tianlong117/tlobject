package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import okhttp3.*;

import javax.net.ssl.*;
import java.io.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 抽象LLM Provider基类。封装与大语言模型API的HTTP通信。
 * 提供OkHttp请求模板、认证头构建、超时配置等公共能力。
 * 具体provider（OpenAI、Claude等）实现buildRequestBody和parseResponse。
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public abstract class TLLlmProvider extends TLBaseModule implements TLAiAgentParamString {

    protected String apiKey;
    protected String apiBaseUrl;
    protected String defaultModel;
    protected Long connTimeOut = 30L;    // 秒
    protected Long readTimeOut = 120L;   // 秒（LLM响应可能很慢）
    protected Long writeTimeOut = 30L;   // 秒
    protected boolean verifySsl = true;  // 是否验证SSL证书，默认true（安全）
    protected int maxRetries = 3;        // 最大重试次数
    protected long retryDelayMs = 1000;  // 重试间隔（毫秒）
    protected Map<String, String> defaultHeaders;
    protected OkHttpClient okHttpClient;
    protected Gson gson;

    // ======================== Debug / Trace ========================
    /** 调试追踪开关：配置文件 debugMode=true 或运行时 debugOn/debugOff 控制 */
    protected boolean debugMode = false;
    /** trace 文件输出目录，默认 ./data/traces */
    protected String traceDir = "./data/traces";
    /** 每个 session 的调用计数器，用于给 trace 编号 */
    private final Map<String, AtomicInteger> callCounters = new ConcurrentHashMap<>();

    public TLLlmProvider() {
        super();
    }

    public TLLlmProvider(String name) {
        super(name);
    }

    public TLLlmProvider(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("apiKey") != null)
                apiKey = params.get("apiKey");
            if (params.get("apiBaseUrl") != null)
                apiBaseUrl = params.get("apiBaseUrl");
            if (params.get("defaultModel") != null)
                defaultModel = params.get("defaultModel");
            if (params.get("connTimeOut") != null) {
                try { connTimeOut = Long.parseLong(params.get("connTimeOut")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("readTimeOut") != null) {
                try { readTimeOut = Long.parseLong(params.get("readTimeOut")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("writeTimeOut") != null) {
                try { writeTimeOut = Long.parseLong(params.get("writeTimeOut")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("verifySsl") != null) {
                verifySsl = Boolean.parseBoolean(params.get("verifySsl"));
            }
            if (params.get("maxRetries") != null) {
                try { maxRetries = Integer.parseInt(params.get("maxRetries")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("retryDelayMs") != null) {
                try { retryDelayMs = Long.parseLong(params.get("retryDelayMs")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("debugMode") != null) {
                debugMode = Boolean.parseBoolean(params.get("debugMode"));
            }
            if (params.get("traceDir") != null) {
                traceDir = params.get("traceDir");
            }
        }
    }

    @Override
    protected TLBaseModule init() {
        gson = new GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss").create();
        okHttpClient = buildDefaultHttpClient();
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case LLM_COMPLETION:
                returnMsg = completion(fromWho, msg);
                break;
            case LLM_COMPLETIONSTREAM:
                returnMsg = completionStream(fromWho, msg);
                break;
            case LLM_LISTMODELS:
                returnMsg = listModels(fromWho, msg);
                break;
            case LLM_CANCEL:
                returnMsg = cancel(fromWho, msg);
                break;
            case LLM_DEBUG_ON:
                debugMode = true;
                putLog("Debug trace enabled for provider: " + getName(), LogLevel.INFO);
                returnMsg = createMsg().setParam(RESULT, true);
                break;
            case LLM_DEBUG_OFF:
                debugMode = false;
                putLog("Debug trace disabled for provider: " + getName(), LogLevel.INFO);
                returnMsg = createMsg().setParam(RESULT, true);
                break;
            case LLM_GETTRACES:
                returnMsg = getTraces(msg);
                break;
            case LLM_CLEARTRACES:
                returnMsg = clearTraces(msg);
                break;
            default:
                returnMsg = null;
        }
        return returnMsg;
    }

    // ======================== 抽象方法（子类实现） ========================

    /**
     * 非流式completion请求
     */
    protected abstract TLMsg completion(Object fromWho, TLMsg msg);

    /**
     * 流式completion请求
     */
    protected abstract TLMsg completionStream(Object fromWho, TLMsg msg);

    /**
     * 列出可用模型
     */
    protected abstract TLMsg listModels(Object fromWho, TLMsg msg);

    /**
     * 创建流式回调（子类实现，返回OkHttp Callback）
     */
    protected abstract Callback createStreamCallback(String resultFor, String resultAction,
                                                      String sessionId, TLMsg msg);

    /**
     * 构建provider特定的请求体JSON
     */
    public abstract String buildRequestBody(TLMsg msg, List<TLConversationHistory> messages,
                                             List<TLFunctionDefinition> tools, boolean stream);

    /**
     * 解析provider特定的响应JSON到TLMsg
     */
    public abstract TLMsg parseResponse(String responseBody, TLMsg originalMsg);

    /**
     * 获取completions API path（如 "/v1/chat/completions"）
     */
    protected abstract String getCompletionsPath();

    // ======================== 模板方法（共享实现） ========================

    /**
     * 流式completion请求模板方法。
     * 子类只需实现 createStreamCallback() 提供各自的SSE回调。
     */
    @SuppressWarnings("unchecked")
    protected TLMsg doCompletionStream(Object fromWho, TLMsg msg) {
        List<TLConversationHistory> messages =
                (List<TLConversationHistory>) msg.getListParam(AI_P_MESSAGEHISTORY, new ArrayList<>());
        List<TLFunctionDefinition> tools =
                (List<TLFunctionDefinition>) msg.getListParam(AI_P_FUNCTIONDEFS, null);

        String jsonBody = buildRequestBody(msg, messages, tools, true);
        Request request = buildHttpRequest(getCompletionsPath(), jsonBody, null);

        String resultFor = msg.getStringParam(RESULTFOR, fromWho.toString());
        String resultAction = msg.getStringParam(RESULTACTION, "onStreamChunk");
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");

        Callback callback = createStreamCallback(resultFor, resultAction, sessionId, msg);
        executeHttpRequestAsync(request, callback);

        return null; // 异步，无返回值
    }

    /**
     * 取消所有进行中的HTTP请求
     */
    protected TLMsg cancel(Object fromWho, TLMsg msg) {
        for (Call call : okHttpClient.dispatcher().queuedCalls()) {
            call.cancel();
        }
        for (Call call : okHttpClient.dispatcher().runningCalls()) {
            call.cancel();
        }
        return createMsg().setParam(RESULT, true);
    }

    /**
     * 构建默认的OkHttpClient
     */
    protected OkHttpClient buildDefaultHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(connTimeOut, TimeUnit.SECONDS)
                .readTimeout(readTimeOut, TimeUnit.SECONDS)
                .writeTimeout(writeTimeOut, TimeUnit.SECONDS);

        if (!verifySsl) {
            // 信任所有证书（仅开发环境使用，生产环境应配置正确证书）
            try {
                TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }
                };
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, trustAllCerts, new SecureRandom());
                builder.sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0]);
                builder.hostnameVerifier((hostname, session) -> true);
                putLog("SSL verification disabled (verifySsl=false) - NOT for production!", LogLevel.WARN);
            } catch (Exception e) {
                putLog("SSL init error: " + e.toString(), LogLevel.WARN);
            }
        }

        return builder.build();
    }

    /**
     * 构建HTTP请求
     */
    protected Request buildHttpRequest(String path, String jsonBody, Map<String, String> extraHeaders) {
        String url = apiBaseUrl;
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        url += path;

        MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(mediaType, jsonBody);

        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(body);

        // 基础认证头
        Map<String, String> allHeaders = buildHeaders();
        if (allHeaders != null) {
            for (Map.Entry<String, String> e : allHeaders.entrySet()) {
                builder.addHeader(e.getKey(), e.getValue());
            }
        }
        // 额外请求头
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                builder.addHeader(e.getKey(), e.getValue());
            }
        }

        return builder.build();
    }

    /**
     * 构建认证和通用HTTP头
     */
    protected Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        return headers;
    }

    /**
     * 同步执行HTTP请求（带重试机制）
     * 可重试的错误: 网络异常、429(Rate Limit)、502/503/504(服务端临时错误)
     */
    protected TLMsg executeHttpRequest(Request request, Object fromWho, TLMsg msg) {
        int attempt = 0;
        Exception lastException = null;
        int lastStatusCode = 0;
        String lastBody = "";

        while (attempt <= maxRetries) {
            attempt++;
            Call call = okHttpClient.newCall(request);
            try {
                Response response = call.execute();
                String responseBody = response.body() != null ? response.body().string() : "";
                int statusCode = response.code();
                response.close();

                if (statusCode >= 200 && statusCode < 300) {
                    return createMsg()
                            .setParam(AI_P_HTTPSTATUS, statusCode)
                            .setParam(AI_P_RESPONSEBODY, responseBody)
                            .setParam(RESULT, true);
                }

                // 可重试的状态码
                if (isRetryableStatus(statusCode) && attempt <= maxRetries) {
                    lastStatusCode = statusCode;
                    lastBody = responseBody;
                    long delay = getRetryDelay(response, attempt);
                    putLog("LLM retry " + attempt + "/" + maxRetries + " after HTTP " + statusCode
                            + " (delay " + delay + "ms)", LogLevel.WARN);
                    try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
                    continue;
                }

                // 不可重试的错误
                putLog("LLM HTTP error: " + statusCode + " body="
                        + (responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody),
                        LogLevel.ERROR);
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_HTTPSTATUS, statusCode)
                        .setParam(AI_P_RESPONSEBODY, responseBody);

            } catch (IOException e) {
                // 被 cancel() 主动取消 → 不重试，返回取消标志，交由 Agent 干净收尾
                if (call.isCanceled()) {
                    putLog("LLM request cancelled by user", LogLevel.INFO);
                    return createMsg().setParam(RESULT, false).setParam(AI_P_CANCELLED, true);
                }
                lastException = e;
                if (attempt <= maxRetries) {
                    putLog("LLM retry " + attempt + "/" + maxRetries + " after IO error: "
                            + e.toString(), LogLevel.WARN);
                    try { Thread.sleep(retryDelayMs); } catch (InterruptedException ignored) {}
                }
            }
        }

        // 所有重试已用尽
        putLog("LLM HTTP all retries exhausted (status=" + lastStatusCode + ")", LogLevel.ERROR);
        TLMsg failed = createMsg().setParam(RESULT, false)
                .setParam(AI_P_HTTPSTATUS, lastStatusCode)
                .setParam(AI_P_RESPONSEBODY, lastBody);
        if (lastException != null) {
            failed.setParam(EXCEPTION, lastException.getMessage());
        }
        return failed;
    }

    /**
     * 判断HTTP状态码是否可重试
     */
    private boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    /**
     * 计算重试延迟，优先使用Retry-After头
     */
    private long getRetryDelay(Response response, int attempt) {
        String retryAfter = response.header("Retry-After");
        if (retryAfter != null) {
            try { return Long.parseLong(retryAfter) * 1000; }
            catch (NumberFormatException ignored) {}
        }
        return retryDelayMs * attempt; // 线性退避
    }

    /**
     * 异步执行HTTP请求
     */
    protected void executeHttpRequestAsync(Request request, Callback callback) {
        okHttpClient.newCall(request).enqueue(callback);
    }

    // ======================== Debug Trace 方法 ========================

    /**
     * 记录一次 LLM 调用 trace。当 debugMode=true 时，格式化写入 session 专属文件。
     *
     * @param sessionId    会话 ID（用于文件隔离和编号）
     * @param senderName   发起调用的 agent/skill 名称
     * @param requestBody  发送给 LLM 的原始 JSON
     * @param responseBody LLM 返回的原始 JSON
     * @param httpStatus   HTTP 状态码
     * @param model        使用的模型
     * @param durationMs   耗时（毫秒）
     */
    protected void traceLlmCall(String sessionId, String senderName, String requestBody,
                                 String responseBody, int httpStatus, String model, long durationMs) {
        if (!debugMode) return;
        // sessionId 安全化：替换路径分隔符，防止路径穿越
        String safeSession = sessionId != null ? sessionId.replaceAll("[/\\\\:\"*?<>|]", "_") : "default";
        if (safeSession.isEmpty()) safeSession = "default";

        try {
            // 确保目录存在
            File dir = new File(traceDir);
            if (!dir.exists()) dir.mkdirs();

            // 获取该 session 的调用序号
            AtomicInteger counter = callCounters.computeIfAbsent(sessionId, k -> new AtomicInteger(0));
            int callIndex = counter.incrementAndGet();

            // 构建 trace 对象
            TLLlmTrace trace = new TLLlmTrace();
            trace.setSessionId(sessionId);
            trace.setSenderName(senderName);
            trace.setProviderName(getName());
            trace.setRequestBody(requestBody);
            trace.setResponseBody(responseBody);
            trace.setHttpStatus(httpStatus);
            trace.setTimestamp(System.currentTimeMillis());
            trace.setModel(model);
            trace.setDurationMs(durationMs);
            trace.setCallIndex(callIndex);

            // 追加写入文件
            File file = new File(dir, getName() + "_" + safeSession + ".trace");
            try (FileWriter fw = new FileWriter(file, true);
                 BufferedWriter bw = new BufferedWriter(fw)) {
                bw.write(trace.toFormattedString());
                bw.flush();
            }
        } catch (Exception e) {
            putLog("Trace write error: " + e.toString(), LogLevel.WARN);
        }
    }

    /**
     * 读取指定 session 的 trace 文件内容
     */
    private TLMsg getTraces(TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, null);
        try {
            if (sessionId != null && !sessionId.isEmpty()) {
                // 读取单个 session
                String safeSession = sessionId.replaceAll("[/\\\\:\"*?<>|]", "_");
                File file = new File(traceDir, getName() + "_" + safeSession + ".trace");
                if (!file.exists()) {
                    return createMsg().setParam(RESULT, true)
                            .setParam("traceContent", "(no trace file for session: " + sessionId + ")");
                }
                String content = readFileToString(file);
                return createMsg().setParam(RESULT, true).setParam("traceContent", content)
                        .setParam("sessionId", sessionId);
            } else {
                // 列出所有 session 的 trace 文件
                File dir = new File(traceDir);
                StringBuilder all = new StringBuilder();
                if (dir.exists() && dir.isDirectory()) {
                    File[] files = dir.listFiles((d, name) ->
                            name.startsWith(getName() + "_") && name.endsWith(".trace"));
                    if (files != null) {
                        for (File f : files) {
                            all.append("=== ").append(f.getName()).append(" ===\n");
                            all.append(readFileToString(f)).append("\n");
                        }
                    }
                }
                if (all.length() == 0) all.append("(no trace files)");
                return createMsg().setParam(RESULT, true).setParam("traceContent", all.toString());
            }
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false)
                    .setParam(EXCEPTION, "Read trace error: " + e.getMessage());
        }
    }

    /**
     * 清除指定 session 的 trace 文件
     */
    private TLMsg clearTraces(TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, null);
        try {
            if (sessionId != null && !sessionId.isEmpty()) {
                String safeSession = sessionId.replaceAll("[/\\\\:\"*?<>|]", "_");
                File file = new File(traceDir, getName() + "_" + safeSession + ".trace");
                if (file.exists()) {
                    file.delete();
                    callCounters.remove(sessionId);
                    return createMsg().setParam(RESULT, true)
                            .setParam("msg", "Trace file deleted for session: " + sessionId);
                }
                return createMsg().setParam(RESULT, true)
                        .setParam("msg", "No trace file for session: " + sessionId);
            } else {
                // 清空所有
                File dir = new File(traceDir);
                int count = 0;
                if (dir.exists() && dir.isDirectory()) {
                    File[] files = dir.listFiles((d, name) ->
                            name.startsWith(getName() + "_") && name.endsWith(".trace"));
                    if (files != null) {
                        for (File f : files) { if (f.delete()) count++; }
                    }
                }
                callCounters.clear();
                return createMsg().setParam(RESULT, true)
                        .setParam("msg", "Deleted " + count + " trace file(s)");
            }
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false)
                    .setParam(EXCEPTION, "Clear trace error: " + e.getMessage());
        }
    }

    /**
     * 读取文件内容为字符串
     */
    private String readFileToString(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 从msg中提取LLM参数覆盖默认值
     */
    protected String getEffectiveModel(TLMsg msg) {
        return msg.getStringParam(AI_P_MODEL, defaultModel);
    }

    protected double getEffectiveTemperature(TLMsg msg) {
        return msg.getDoubleParam(AI_P_TEMPERATURE, 0.7);
    }

    protected int getEffectiveMaxTokens(TLMsg msg) {
        return msg.getIntParam(AI_P_MAXTOKENS, 4096);
    }

    // ======================== getters/setters ========================

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

    public OkHttpClient getOkHttpClient() { return okHttpClient; }
}
