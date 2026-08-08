package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

    // ======================== Prompt Caching ========================
    /** 启用 prompt caching（默认 true）。
     *  - Anthropic: 显式发送 cache_control + anthropic-beta 头
     *  - DeepSeek/OpenAI: 服务器端自动缓存，仅解析 usage 统计 */
    protected boolean enablePromptCaching = true;
    /** Anthropic prompt-caching beta 版本头字段值 */
    protected String promptCachingBeta = "prompt-caching-2024-07-31";
    /** 会话级缓存统计: sessionId -> {cacheCreationTokens, cacheHitTokens, cacheMissTokens} */
    private final Map<String, long[]> sessionCacheStats = new ConcurrentHashMap<>();

    // ======================== 启动自检 ========================
    /** 启动时自行检查 API Key / 连通性（默认 false） */
    protected boolean checkProviderOnStartup = false;

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
            if (params.get("enablePromptCaching") != null) {
                enablePromptCaching = Boolean.parseBoolean(params.get("enablePromptCaching"));
            }
            if (params.get("promptCachingBeta") != null) {
                promptCachingBeta = params.get("promptCachingBeta");
            }
            if (params.get("checkProviderOnStartup") != null) {
                checkProviderOnStartup = Boolean.parseBoolean(params.get("checkProviderOnStartup"));
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
    public void runStartMsg() {
        super.runStartMsg();
        if (checkProviderOnStartup) {
            checkProvider();
        }
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
            case LLM_EMBEDDING:
                returnMsg = embed(fromWho, msg);
                break;
            case AGENT_CHECKPROVIDER:
                returnMsg = createMsg().setParam(RESULT, checkProvider());
                break;
            default:
                returnMsg = null;
        }
        return returnMsg;
    }

    // ======================== 启动自检 ========================

    /**
     * 检查自身连通性：发送最小化 ping 请求验证 API Key / 余额 / 网络。
     * 成功打印确认信息；失败打印诊断信息并置自身为不可用状态。
     * @return true = provider 可用
     */
    public boolean checkProvider() {
        try {
            List<TLConversationHistory> testHistory = new ArrayList<>();
            testHistory.add(new TLConversationHistory(TLConversationHistory.Role.user, "ping"));
            TLMsg testMsg = createMsg().setAction(LLM_COMPLETION)
                    .setParam(AI_P_MESSAGEHISTORY, testHistory)
                    .setParam(AI_P_MODEL, getDefaultModel())
                    .setParam(AI_P_MAXTOKENS, 1);
            TLMsg result = completion(this, testMsg);
            if (result != null && result.parseBoolean(RESULT, false)) {
                System.out.println("=== [启动检查] LLM Provider 可用: " + getDefaultModel() + " ===");
                return true;
            } else {
                int status = result != null ? result.getIntParam(AI_P_HTTPSTATUS, 0) : 0;
                String body = result != null ? result.getStringParam(AI_P_RESPONSEBODY, "") : "no response";
                System.err.println("!!! [启动检查] LLM Provider 不可用！HTTP " + status + " body=" + body);
                System.err.println("!!! 请检查 API Key 和余额，或切换 Provider");
                return false;
            }
        } catch (Exception e) {
            System.err.println("!!! [启动检查] LLM Provider 连接失败: " + e.getMessage());
            return false;
        }
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
     * 文本向量化（默认不支持，子类可覆盖）。Msg 入参：embedText + model；出参：embeddingVector(float[])。
     */
    protected TLMsg embed(Object fromWho, TLMsg msg) {
        return createMsg().setParam(RESULT, false).setParam("error", "embedding not supported by this provider");
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
                        .setParam(AI_P_RESPONSEBODY, responseBody)
                        .setParam(AI_P_RESPONSE, buildErrorMsg(statusCode, responseBody));

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
        String errMsg = lastException != null ? lastException.getMessage() : "";
        TLMsg failed = createMsg().setParam(RESULT, false)
                .setParam(AI_P_HTTPSTATUS, lastStatusCode)
                .setParam(AI_P_RESPONSEBODY, lastBody)
                .setParam(AI_P_RESPONSE, buildErrorMsg(lastStatusCode, lastBody, errMsg));
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

    // ======================== Prompt Caching 工具方法 ========================

    /**
     * 获取单次请求的 prompt caching 开关状态。
     * 请求级参数覆盖 > Provider 级配置（默认 true）。
     */
    protected boolean getEffectivePromptCaching(TLMsg msg) {
        if (msg != null && msg.containsParam(AI_P_ENABLEPROMPTCACHING)) {
            return msg.parseBoolean(AI_P_ENABLEPROMPTCACHING, true);
        }
        return enablePromptCaching;
    }

    /**
     * 线程安全地累加会话级缓存统计并返回快照。
     * @return new long[]{cacheCreationTokens, cacheHitTokens, cacheMissTokens}
     */
    protected long[] accumulateCacheStats(String sessionId, long creation, long hit, long miss) {
        long[] stats = sessionCacheStats.computeIfAbsent(sessionId, k -> new long[3]);
        synchronized (stats) {
            stats[0] += creation;
            stats[1] += hit;
            stats[2] += miss;
        }
        return new long[]{stats[0], stats[1], stats[2]};
    }

    /** 获取指定 session 的累计缓存统计快照 */
    public long[] getSessionCacheStats(String sessionId) {
        long[] existing = sessionCacheStats.get(sessionId);
        return existing != null ? new long[]{existing[0], existing[1], existing[2]} : new long[]{0L, 0L, 0L};
    }

    /** 清除指定 session 的缓存统计 */
    public void clearSessionCacheStats(String sessionId) {
        sessionCacheStats.remove(sessionId);
    }

    /**
     * 为 JSON 数组的最后一个元素添加 cache_control: {"type": "ephemeral"}。
     * 用于 Anthropic 的 system 内容块数组和 tools 数组设置缓存断点。
     */
    protected void addCacheControlToLast(JsonArray array) {
        if (array == null || array.size() == 0) return;
        JsonObject last = array.get(array.size() - 1).getAsJsonObject();
        JsonObject cacheControl = new JsonObject();
        cacheControl.addProperty("type", "ephemeral");
        last.add("cache_control", cacheControl);
    }

    /** 记录缓存事件日志（debug mode 时输出） */
    protected void logCacheEvent(String sessionId, long creationTokens, long hitTokens,
                                  long missTokens, String model) {
        if (!debugMode) return;
        long[] totals = getSessionCacheStats(sessionId);
        long totalHitMiss = hitTokens + missTokens;
        double hitRate = totalHitMiss > 0 ? (100.0 * hitTokens / totalHitMiss) : 0.0;
        putLog(String.format(
                "[CACHE] session=%s model=%s created=%d hit=%d miss=%d hit_rate=%.1f%% total_created=%d total_hit=%d total_miss=%d",
                sessionId, model, creationTokens, hitTokens, missTokens, hitRate,
                totals[0], totals[1], totals[2]),
                LogLevel.DEBUG);
    }

    // ======================== 错误消息 ========================

    /** 构建用户可读的错误提示 */
    private String buildErrorMsg(int httpStatus, String body) {
        return buildErrorMsg(httpStatus, body, null);
    }

    private String buildErrorMsg(int httpStatus, String body, String exceptionMsg) {
        if (httpStatus == 0) {
            if (exceptionMsg != null && !exceptionMsg.isEmpty())
                return "LLM 连接失败：" + exceptionMsg;
            return "LLM 连接失败，请检查网络或 API 地址是否正确";
        }
        if (httpStatus == 401 || httpStatus == 403)
            return "LLM 认证失败（HTTP " + httpStatus + "），请检查 API Key 或账户余额";
        if (httpStatus == 429)
            return "LLM 请求超限（HTTP 429），请稍后重试或检查账户额度";
        if (httpStatus >= 500)
            return "LLM 服务异常（HTTP " + httpStatus + "），请稍后重试";
        // 尝试从响应体提取错误信息
        if (body != null && !body.isEmpty()) {
            try {
                com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
                if (json.has("error")) {
                    com.google.gson.JsonObject err = json.getAsJsonObject("error");
                    String msg = err.has("message") ? err.get("message").getAsString() : body;
                    return "LLM 错误：HTTP " + httpStatus + " — " + msg;
                }
            } catch (Exception ignored) {}
        }
        return "LLM 返回错误（HTTP " + httpStatus + "）";
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
