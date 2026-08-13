package cn.tianlong.tlobject.aiagent.mcp;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.modules.LogLevel;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MCP SSE/Streamable HTTP Transport 实现。
 * 通过 HTTP POST 发送 JSON-RPC 请求，支持 JSON 直接响应和 SSE 流式响应。
 * 适用于远程 MCP Server（如企业内部 MCP 网关）。
 *
 * 创建日期：2026/7/7
 * 作者:tianlong
 */
public class SseMcpTransport implements McpTransport, TLAiAgentParamString {

    private final String url;
    private final OkHttpClient httpClient;
    private final McpJsonRpc jsonRpc;
    private final McpTransport.LogCallback logCallback;

    /** MCP 会话 ID（initialize 后由服务器返回在响应头 Mcp-Session-Id 中） */
    private String sessionId;
    private volatile boolean connected = false;

    /**
     * @param url MCP Server 端点 URL
     * @param logCallback 日志回调
     */
    public SseMcpTransport(String url, McpTransport.LogCallback logCallback) {
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.jsonRpc = new McpJsonRpc();
        this.logCallback = logCallback;

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 带自定义 OkHttpClient 的构造函数（复用已有的 client 配置）。
     */
    public SseMcpTransport(String url, OkHttpClient httpClient, McpTransport.LogCallback logCallback) {
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.jsonRpc = new McpJsonRpc();
        this.httpClient = httpClient;
        this.logCallback = logCallback;
    }

    @Override
    public void connect(String clientName, String clientVersion) throws Exception {
        // MCP 初始化握手
        Map<String, Object> initParams = jsonRpc.buildInitializeParams(clientName, clientVersion);
        String initRequest = jsonRpc.buildRequest("initialize", initParams);
        String initResponse = send(initRequest);

        try {
            jsonRpc.parseResponse(initResponse);
            log("MCP initialize successful via SSE", LogLevel.DEBUG);
        } catch (McpJsonRpc.McpRpcException e) {
            log("MCP initialize failed: " + e.toString(), LogLevel.ERROR);
            throw e;
        }

        // 发送 initialized 通知
        String notif = jsonRpc.buildNotification("initialized", null);
        sendNotification(notif);

        connected = true;
        log("MCP SSE transport connected to " + url, LogLevel.DEBUG);
    }

    @Override
    public String send(String jsonRpcRequest) throws Exception {
        Request request = buildPostRequest(jsonRpcRequest);
        return executeRequest(request);
    }

    @Override
    public void disconnect() {
        connected = false;
        sessionId = null;
        log("MCP SSE transport disconnected", LogLevel.DEBUG);
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    // ======================== 内部方法 ========================

    /**
     * 发送通知（无 id 字段，服务器不返回响应）。
     */
    private void sendNotification(String jsonRpcNotification) {
        try {
            Request request = buildPostRequest(jsonRpcNotification);
            // 通知不需要解析响应，但需要关闭 response body
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.body() != null) {
                    response.body().close();
                }
            }
        } catch (IOException e) {
            log("MCP notification send failed: " + e.getMessage(), LogLevel.WARN);
        }
    }

    private Request buildPostRequest(String jsonBody) {
        MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(mediaType, jsonBody);

        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json, text/event-stream");

        // 携带 session ID（initialize 后服务器返回）
        if (sessionId != null && !sessionId.isEmpty()) {
            builder.addHeader("Mcp-Session-Id", sessionId);
        }

        return builder.build();
    }

    private String executeRequest(Request request) throws IOException, McpJsonRpc.McpRpcException {
        try (Response response = httpClient.newCall(request).execute()) {
            // 保存 session ID
            String respSessionId = response.header("Mcp-Session-Id");
            if (respSessionId != null && !respSessionId.isEmpty()) {
                this.sessionId = respSessionId;
            }

            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                throw new IOException("MCP HTTP error: " + response.code() + " body=" + errorBody);
            }

            String contentType = response.header("Content-Type", "");
            String body = response.body() != null ? response.body().string() : "";

            if (contentType.contains("text/event-stream")) {
                // SSE 响应：提取 data 字段
                return parseSseResponse(body);
            } else {
                // 普通 JSON 响应
                return body;
            }
        }
    }

    /**
     * 解析 SSE 文本流，提取最后一个含 data 的完整 event 作为 JSON-RPC 响应。
     * 按 SSE 规范：同一 event 的多个 data: 行以 \n 拼接，空行结束一个 event；
     * 服务器把 JSON 分多帧 data 下发时不再丢数据。
     */
    private String parseSseResponse(String sseText) throws IOException {
        String lastEventData = null;
        StringBuilder current = new StringBuilder();
        boolean hasData = false;
        try (BufferedReader br = new BufferedReader(new StringReader(sseText))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) {
                    // event 边界：提交当前 event
                    if (hasData) lastEventData = current.toString();
                    current.setLength(0);
                    hasData = false;
                } else if (line.startsWith("data:")) {
                    // 规范：去掉 "data:" 前缀及紧随的一个空格
                    String data = line.substring(5);
                    if (data.startsWith(" ")) data = data.substring(1);
                    if (hasData) current.append("\n");
                    current.append(data);
                    hasData = true;
                } else if (line.startsWith(":")) {
                    // 注释行忽略
                }
                // 其他字段（event:/id:/retry:）不影响 data 累积
            }
            // 兼容末尾无空行的流：提交最后一个未闭合 event
            if (hasData) lastEventData = current.toString();
        }
        if (lastEventData == null) {
            throw new IOException("SSE response contained no data");
        }
        return lastEventData;
    }

    private void log(String msg, LogLevel level) {
        if (logCallback != null) {
            logCallback.log(msg, level);
        }
    }
}
