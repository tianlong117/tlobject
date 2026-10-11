package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import okhttp3.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    /** 响应体读取上限（KB，params 可配）。此前 body().string() 全量物化——抓超大页面/文件会吃爆内存
     *  （OOM 是 Error，逃出 execute 经框架链 shutdown 整个应用）；改为流式读取封顶，超出截断并注明 */
    private int maxResponseKB = 2048;

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

        if (params != null && params.get("maxResponseKB") != null) {
            try { maxResponseKB = Integer.parseInt(params.get("maxResponseKB").trim()); }
            catch (NumberFormatException ignored) {}
        }

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

        // asString（非 (String) 强转）：LLM 发数字/布尔时 Gson 解析为 Double/Boolean，强转 CCE 会逃出
        // execute 经框架异常链（无 exceptionHandler 时 moduleFactory.shutdown(-1)）终止整个应用
        String url = asString(input.getOrDefault("url", ""), "");
        String method = asString(input.getOrDefault("method", "GET"), "GET");
        // headers 的 Map 强转同理：非 Map 值（如数字）强转 CCE 同样会杀死应用；非 Map 按"未传"处理
        Object headersRaw = input.getOrDefault("headers", new LinkedHashMap<>());
        Map<String, Object> headers = headersRaw instanceof Map ? (Map<String, Object>) headersRaw : new LinkedHashMap<>();
        String body = asString(input.getOrDefault("body", ""), "");

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
                String responseBody = readBodyCapped(response, maxResponseKB * 1024L);
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
        } catch (IllegalArgumentException e) {
            // 字符串化后的数字 url（如 "123"）/ 非法请求头名：OkHttp 抛 IllegalArgumentException——
            // 它是 RuntimeException，不在 IOException 捕获范围内，会逃出 execute 经框架异常链
            // （无 exceptionHandler 时 moduleFactory.shutdown(-1)）杀死整个应用；转成优雅报错
            putLog("HTTP Skill error: " + e.toString(), LogLevel.ERROR);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "HTTP request failed (invalid url or headers): " + e.getMessage());
        }
    }

    /** 流式读取响应体、封顶 maxBytes（读的是解压后字节；gzip 响应同样受封顶保护）。
     *  超限截断并附注明；UTF-8 边界截断可能产生一个替换符，可接受。 */
    private static String readBodyCapped(Response response, long maxBytes) throws IOException {
        ResponseBody b = response.body();
        if (b == null) return "";
        try (InputStream in = b.byteStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while (total < maxBytes && (n = in.read(buf, 0, (int) Math.min(buf.length, maxBytes - total))) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            boolean truncated = in.read() != -1;
            String s = new String(out.toByteArray(), StandardCharsets.UTF_8);
            if (truncated)
                s = "...(response body exceeds " + (maxBytes / 1024) + "KB, truncated)\n" + s;   // 标记放头：下游还有一次"输出截 5000 字符"，放尾会被切掉
            return s;
        }
    }
}
