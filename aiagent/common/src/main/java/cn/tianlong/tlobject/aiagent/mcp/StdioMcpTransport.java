package cn.tianlong.tlobject.aiagent.mcp;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.modules.LogLevel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * MCP stdio Transport 实现。
 * 通过 ProcessBuilder 启动子进程，使用 stdin/stdout 进行 JSON-RPC 通信。
 * 适用于本地 MCP Server（如 npx @anthropic/mcp-server-filesystem）。
 *
 * 通信协议：每行一个 JSON-RPC 消息，请求写 stdin，响应从 stdout 逐行读取。
 *
 * 创建日期：2026/7/7
 * 作者:tianlong
 */
public class StdioMcpTransport implements McpTransport, TLAiAgentParamString {

    /** 活跃的 MCP 子进程注册表：JVM 退出（含崩溃/强杀路径，disconnect 未被执行）时统一清理，防止孤儿子进程 */
    private static final Set<Process> LIVE_PROCESSES = ConcurrentHashMap.newKeySet();
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Process p : LIVE_PROCESSES) {
                if (p != null && p.isAlive()) {
                    try {
                        // npx 等启动器会再派生 node 孙进程，先杀后代再杀本体
                        p.descendants().forEach(ProcessHandle::destroyForcibly);
                        p.destroyForcibly();
                        p.waitFor(2, TimeUnit.SECONDS);
                    } catch (Exception ignored) {}
                }
            }
        }, "mcp-stdio-shutdown"));
    }

    private final String command;
    private final String[] args;
    private final McpJsonRpc jsonRpc;

    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private Thread stderrThread;
    private final StringBuilder stderrBuffer = new StringBuilder();
    private volatile boolean connected = false;

    /** 日志回调 */
    private final McpTransport.LogCallback logCallback;

    /**
     * @param command 启动命令（如 "npx", "node", "python"）
     * @param args 命令参数
     * @param logCallback 日志回调（用于输出到框架日志系统）
     */
    public StdioMcpTransport(String command, String[] args, McpTransport.LogCallback logCallback) {
        this.command = command;
        this.args = args != null ? args.clone() : new String[0];
        this.jsonRpc = new McpJsonRpc();
        this.logCallback = logCallback;
    }

    @Override
    public void connect(String clientName, String clientVersion) throws Exception {
        String[] cmdArray = new String[args.length + 1];
        cmdArray[0] = command;
        System.arraycopy(args, 0, cmdArray, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(cmdArray);
        pb.redirectErrorStream(false);

        log("Starting MCP stdio process: " + String.join(" ", cmdArray), LogLevel.DEBUG);
        process = pb.start();
        LIVE_PROCESSES.add(process);

        // stdin → writer (向子进程写入)
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        // stdout → reader (从子进程读取)
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        // 单独线程消费 stderr，避免进程阻塞。收集输出用于错误诊断
        stderrBuffer.setLength(0);
        stderrThread = new Thread(() -> {
            try (BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = errReader.readLine()) != null) {
                    stderrBuffer.append(line).append("\n");
                    // 用 WARN 级别确保可见
                    log("[MCP stderr] " + line, LogLevel.WARN);
                }
            } catch (IOException ignored) {}
        }, "mcp-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();

        connected = true;

        // MCP 初始化握手
        try {
            Map<String, Object> initParams = jsonRpc.buildInitializeParams(clientName, clientVersion);
            String initRequest = jsonRpc.buildRequest("initialize", initParams);
            String initResponse = send(initRequest);
            jsonRpc.parseResponse(initResponse);
            log("MCP initialize successful", LogLevel.DEBUG);
        } catch (Exception e) {
            // 握手失败（含进程提前退出等 IOException）：清理连接，惰性重连可重新拉起进程
            log("MCP initialize failed: " + e.toString(), LogLevel.ERROR);
            disconnect();
            throw e;
        }

        // 发送 initialized 通知
        String notif = jsonRpc.buildNotification("initialized", null);
        writeLine(notif);

        log("MCP stdio transport connected", LogLevel.DEBUG);
    }

    @Override
    public synchronized String send(String jsonRpcRequest) throws Exception {
        if (!connected) {
            throw new IOException("Transport not connected");
        }
        checkProcessAlive();
        writeLine(jsonRpcRequest);
        String response = readLine();
        if (response == null) {
            int exitCode = process != null ? process.exitValue() : -1;
            String stderrTail = stderrBuffer.length() > 0
                    ? stderrBuffer.toString().trim() : "(no stderr)";
            throw new IOException("MCP process exited with code " + exitCode
                    + ". stderr: " + stderrTail);
        }
        return response;
    }

    private void checkProcessAlive() throws IOException {
        if (process != null && !process.isAlive()) {
            int exitCode = process.exitValue();
            String stderrTail = stderrBuffer.length() > 0
                    ? stderrBuffer.toString().trim() : "(no stderr)";
            throw new IOException("MCP process already exited, code=" + exitCode
                    + ", stderr: " + stderrTail);
        }
    }

    @Override
    public void disconnect() {
        connected = false;
        try {
            if (writer != null) { writer.close(); writer = null; }
            if (reader != null) { reader.close(); reader = null; }
        } catch (IOException ignored) {}
        Process p = process;
        if (p != null) {
            LIVE_PROCESSES.remove(p);
            if (p.isAlive()) {
                p.destroy();
                try {
                    if (!p.waitFor(5, TimeUnit.SECONDS)) {
                        p.descendants().forEach(ProcessHandle::destroyForcibly);
                        p.destroyForcibly();
                    }
                } catch (InterruptedException ignored) {
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                }
            }
        }
        process = null;
        log("MCP stdio transport disconnected", LogLevel.DEBUG);
    }

    @Override
    public boolean isConnected() {
        return connected && process != null && process.isAlive();
    }

    // ======================== 内部方法 ========================

    private void writeLine(String line) throws IOException {
        if (writer == null) throw new IOException("Writer is null");
        writer.write(line);
        writer.newLine();
        writer.flush();
    }

    private String readLine() throws IOException {
        if (reader == null) throw new IOException("Reader is null");
        return reader.readLine();
    }

    private void log(String msg, LogLevel level) {
        if (logCallback != null) {
            logCallback.log(msg, level);
        }
    }
}
