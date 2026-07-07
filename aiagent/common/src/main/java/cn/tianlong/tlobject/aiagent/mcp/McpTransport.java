package cn.tianlong.tlobject.aiagent.mcp;

import cn.tianlong.tlobject.modules.LogLevel;

/**
 * MCP Transport 抽象接口。
 * 定义 MCP 客户端与服务器之间的通信通道，支持 stdio 和 SSE/HTTP 两种实现。
 *
 * 创建日期：2026/7/7
 * 作者:tianlong
 */
public interface McpTransport {

    /**
     * 建立连接并完成 MCP 初始化握手。
     *
     * @param clientName 客户端名称
     * @param clientVersion 客户端版本
     * @throws Exception 连接或握手失败
     */
    void connect(String clientName, String clientVersion) throws Exception;

    /**
     * 发送 JSON-RPC 请求并接收响应。
     *
     * @param jsonRpcRequest JSON-RPC 请求字符串
     * @return JSON-RPC 响应字符串
     * @throws Exception 发送或接收失败
     */
    String send(String jsonRpcRequest) throws Exception;

    /**
     * 关闭连接，释放资源。
     */
    void disconnect();

    /**
     * 检查连接是否处于活跃状态。
     */
    boolean isConnected();

    /**
     * 日志回调接口，将 transport 的日志输出到框架日志系统。
     */
    interface LogCallback {
        void log(String message, LogLevel level);
    }
}
