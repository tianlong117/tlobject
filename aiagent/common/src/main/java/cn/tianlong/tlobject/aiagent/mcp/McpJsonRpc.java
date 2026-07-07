package cn.tianlong.tlobject.aiagent.mcp;

import com.google.gson.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP JSON-RPC 2.0 消息构建/解析工具类。
 * 封装 MCP 协议的请求构建和响应解析，所有 MCP 消息均走 JSON-RPC 2.0 格式。
 *
 * MCP 协议核心方法：
 * - initialize  → 握手协商协议版本和能力
 * - tools/list  → 获取可用工具列表
 * - tools/call  → 调用指定工具
 *
 * 创建日期：2026/7/7
 * 作者:tianlong
 */
public class McpJsonRpc {

    private static final String JSONRPC_VERSION = "2.0";
    private static final Gson gson = new GsonBuilder().create();

    /** 原子递增的请求 ID */
    private int nextId = 1;

    /**
     * 构建 JSON-RPC 请求 JSON 字符串。
     *
     * @param method MCP 方法名（如 "tools/list", "tools/call"）
     * @param params 方法参数
     * @return JSON-RPC 请求 JSON 字符串
     */
    public String buildRequest(String method, Map<String, Object> params) {
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", JSONRPC_VERSION);
        req.addProperty("id", nextId++);
        req.addProperty("method", method);

        if (params != null && !params.isEmpty()) {
            req.add("params", gson.toJsonTree(params));
        }
        return gson.toJson(req);
    }

    /**
     * 构建不带 params 的 JSON-RPC 请求。
     */
    public String buildRequest(String method) {
        return buildRequest(method, null);
    }

    /**
     * 构建 JSON-RPC 通知（无 id，服务器不返回响应）。
     */
    public String buildNotification(String method, Map<String, Object> params) {
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", JSONRPC_VERSION);
        req.addProperty("method", method);
        if (params != null && !params.isEmpty()) {
            req.add("params", gson.toJsonTree(params));
        }
        return gson.toJson(req);
    }

    /**
     * 解析 JSON-RPC 响应，提取 result 或 error。
     *
     * @param responseBody 服务器返回的 JSON 字符串
     * @return 包含 result 或 error 的解析结果
     * @throws McpRpcException 如果响应包含 JSON-RPC error
     */
    @SuppressWarnings("unchecked")
    public JsonObject parseResponse(String responseBody) throws McpRpcException {
        JsonObject root = gson.fromJson(responseBody, JsonObject.class);

        if (root.has("error")) {
            JsonObject errorObj = root.getAsJsonObject("error");
            int code = errorObj.has("code") ? errorObj.get("code").getAsInt() : -1;
            String message = errorObj.has("message") ? errorObj.get("message").getAsString() : "Unknown error";
            throw new McpRpcException(code, message);
        }

        return root.has("result") ? root.getAsJsonObject("result") : new JsonObject();
    }

    /**
     * 构建 initialize 请求参数。
     */
    public Map<String, Object> buildInitializeParams(String clientName, String clientVersion) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", "2024-11-05");

        Map<String, Object> capabilities = new LinkedHashMap<>();
        // 声明客户端只支持 tools（不声明 resources 和 prompts）
        Map<String, Object> toolsCap = new LinkedHashMap<>();
        capabilities.put("tools", toolsCap);
        params.put("capabilities", capabilities);

        Map<String, String> clientInfo = new LinkedHashMap<>();
        clientInfo.put("name", clientName);
        clientInfo.put("version", clientVersion);
        params.put("clientInfo", clientInfo);

        return params;
    }

    /**
     * 构建 tools/call 请求参数。
     *
     * @param toolName 要调用的 tool 名称
     * @param arguments tool 参数（JSON 对象）
     */
    public Map<String, Object> buildToolCallParams(String toolName, Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments != null ? arguments : new LinkedHashMap<>());
        return params;
    }

    /**
     * 从 tools/list 响应中解析 tool 列表。
     */
    @SuppressWarnings("unchecked")
    public List<McpTool> parseToolsList(JsonObject result) {
        List<McpTool> tools = new ArrayList<>();
        if (result == null) return tools;

        JsonArray toolsArray = result.getAsJsonArray("tools");
        if (toolsArray == null) return tools;

        for (JsonElement elem : toolsArray) {
            JsonObject toolObj = elem.getAsJsonObject();
            String name = toolObj.has("name") ? toolObj.get("name").getAsString() : "";
            String desc = toolObj.has("description") ? toolObj.get("description").getAsString() : "";

            Map<String, Object> inputSchema = new LinkedHashMap<>();
            if (toolObj.has("inputSchema")) {
                inputSchema = gson.fromJson(toolObj.get("inputSchema"), LinkedHashMap.class);
            }

            tools.add(new McpTool(name, desc, inputSchema));
        }
        return tools;
    }

    /**
     * 从 tools/call 响应中提取文本内容。
     * MCP 响应格式: result.content[{type:"text", text:"..."}]
     */
    public String parseToolCallResult(JsonObject result) {
        if (result == null) return "";
        JsonArray content = result.getAsJsonArray("content");
        if (content == null) return result.toString();

        StringBuilder sb = new StringBuilder();
        for (JsonElement elem : content) {
            JsonObject item = elem.getAsJsonObject();
            String type = item.has("type") ? item.get("type").getAsString() : "";
            if ("text".equals(type) && item.has("text")) {
                sb.append(item.get("text").getAsString());
            } else if ("resource".equals(type)) {
                sb.append("[resource: ").append(item.toString()).append("]");
            }
        }
        return sb.toString();
    }

    // ======================== 内部类 ========================

    /**
     * JSON-RPC 错误异常
     */
    public static class McpRpcException extends Exception {
        private final int code;

        public McpRpcException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int getCode() { return code; }

        @Override
        public String toString() {
            return "McpRpcException{code=" + code + ", message=" + getMessage() + '}';
        }
    }
}
