package cn.tianlong.tlobject.aiagent.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP 模块回归自测（runnable main，无 JUnit——与仓库自测惯例一致）。
 *
 * <p>覆盖 DeepSeek MCP 审查确认的问题：
 * <ol>
 *   <li>McpJsonRpc.nextId 非原子（并行 tool 调用会拿到重复 id）；</li>
 *   <li>parseResponse 对空/非法响应直接 NPE；</li>
 *   <li>stdio 响应无超时：卡死的 Server 会永久挂住调用线程；超时后必须强杀进程并可重连；</li>
 *   <li>SSE send 无同步 + sessionId 非 volatile：并发请求看不到会话 id；读超时需可配置。</li>
 * </ol>
 *
 * <p>stdio 测试用本类自身当假 Server（子进程带 {@code --fake-mcp-server} 启动），
 * SSE 测试用 JDK 内置 HttpServer。运行（classpath 见 aistart_args.txt）：
 * <pre>java -cp "aiagent/common/target/classes;core/target/classes;..." \
 *     cn.tianlong.tlobject.aiagent.mcp.TLMcpSelfTest</pre>
 * 失败以退出码 1 结束。
 */
public class TLMcpSelfTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--fake-mcp-server".equals(args[0])) {
            runFakeServer();
            return;
        }

        nextIdIsAtomicUnderConcurrency();
        parseResponseGuardsEmptyAndInvalid();
        stdioRoundTripAndTimeoutReconnect();
        sseRoundTripSessionAndTimeout();

        System.out.println("\n结果: 通过=" + passed + ", 失败=" + failed);
        if (failed > 0) System.exit(1);
    }

    // ======================== 1. JSON-RPC id 并发 ========================

    private static void nextIdIsAtomicUnderConcurrency() throws Exception {
        McpJsonRpc rpc = new McpJsonRpc();
        int threads = 8, perThread = 200;
        Set<Integer> ids = Collections.synchronizedSet(new HashSet<>());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        String req = rpc.buildRequest("tools/list");
                        JsonObject obj = JsonParser.parseString(req).getAsJsonObject();
                        ids.add(obj.get("id").getAsInt());
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            }, "mcp-id-test-" + t).start();
        }
        start.countDown();
        done.await();
        check("并发 buildRequest 的 id 全不重复（" + ids.size() + "/" + (threads * perThread) + "）",
                ids.size() == threads * perThread);
    }

    // ======================== 2. 空/非法响应防御 ========================

    private static void parseResponseGuardsEmptyAndInvalid() {
        McpJsonRpc rpc = new McpJsonRpc();
        check("null 响应抛 McpRpcException（不是 NPE）", throwsRpc(rpc, null));
        check("空串响应抛 McpRpcException（不是 NPE）", throwsRpc(rpc, "   "));
        check("非法 JSON 响应抛 McpRpcException", throwsRpc(rpc, "{not-json"));
        try {
            JsonObject result = rpc.parseResponse("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}");
            check("正常响应可直接解析", result != null && result.get("ok").getAsBoolean());
        } catch (Exception e) {
            check("正常响应可直接解析: " + e, false);
        }
    }

    private static boolean throwsRpc(McpJsonRpc rpc, String body) {
        try {
            rpc.parseResponse(body);
            return false;
        } catch (McpJsonRpc.McpRpcException expected) {
            return true;
        } catch (Throwable other) {
            return false;
        }
    }

    // ======================== 3. stdio：往返 + 超时 + 重连 ========================

    private static void stdioRoundTripAndTimeoutReconnect() throws Exception {
        String javaExe = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + "java";
        String cp = System.getProperty("java.class.path");
        String[] cmdArgs = {"-cp", cp, TLMcpSelfTest.class.getName(), "--fake-mcp-server"};

        StdioMcpTransport normal = new StdioMcpTransport(javaExe, cmdArgs, 0L, (m, l) -> {});
        try {
            normal.connect("selftest", "1.0");
            check("stdio 连接成功", normal.isConnected());
            String resp = normal.send(new McpJsonRpc().buildRequest("tools/list"));
            check("stdio tools/list 往返正常", resp != null && resp.contains("\"echo\""));
        } finally {
            normal.disconnect();
        }

        // 第二次连接：让 fake server 对 "hang" 不回包，验证读超时 + 强杀 + 重连
        StdioMcpTransport hang = new StdioMcpTransport(javaExe, cmdArgs, 1500L, (m, l) -> {});
        String timeoutResp = null;
        long start = System.currentTimeMillis();
        try {
            hang.connect("selftest", "1.0");
            timeoutResp = hang.send(new McpJsonRpc().buildRequest("hang"));
            check("stdio 卡死请求应超时（实际返回:" + timeoutResp + "）", false);
        } catch (java.io.IOException expected) {
            long elapsed = System.currentTimeMillis() - start;
            check("stdio 读超时被触发（" + elapsed + "ms）", elapsed < 8000);
            check("超时后连接已断开", !hang.isConnected());
        } finally {
            hang.disconnect();
        }

        try {
            hang.connect("selftest", "1.0");
            String resp = hang.send(new McpJsonRpc().buildRequest("tools/list"));
            check("超时断开后可重连并正常发现工具", resp != null && resp.contains("\"echo\""));
        } finally {
            hang.disconnect();
        }
    }

    // ======================== 4. SSE：往返 + session + 读超时 ========================

    private static void sseRoundTripSessionAndTimeout() throws Exception {
        AtomicReference<String> lastSession = new AtomicReference<>();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject req = JsonParser.parseString(body).getAsJsonObject();
            String method = req.has("method") ? req.get("method").getAsString() : "";
            String sid = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
            if (sid != null) lastSession.set(sid);
            if ("slow".equals(method)) {
                try { Thread.sleep(800); } catch (InterruptedException ignored) {}
            }
            String json = req.has("id")
                    ? "{\"jsonrpc\":\"2.0\",\"id\":" + req.get("id") + ",\"result\":{\"ok\":true}}"
                    : "";
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if ("initialize".equals(method)) {
                exchange.getResponseHeaders().add("Mcp-Session-Id", "sess-123");
            }
            exchange.sendResponseHeaders(200, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";

        SseMcpTransport sse = new SseMcpTransport(url, 400L, (m, l) -> {});
        try {
            sse.connect("selftest", "1.0");
            check("SSE 连接成功", sse.isConnected());
            String r1 = sse.send(new McpJsonRpc().buildRequest("tools/list"));
            check("SSE 响应解析正常", r1 != null && r1.contains("\"ok\":true"));
            sse.send(new McpJsonRpc().buildRequest("tools/list"));
            check("SSE 后续请求携带 Mcp-Session-Id（" + lastSession.get() + "）",
                    "sess-123".equals(lastSession.get()));

            long start = System.currentTimeMillis();
            try {
                sse.send(new McpJsonRpc().buildRequest("slow"));
                check("SSE 慢响应应读超时", false);
            } catch (java.io.IOException expected) {
                check("SSE 读超时生效（" + (System.currentTimeMillis() - start) + "ms）",
                        System.currentTimeMillis() - start < 5000);
            }
        } finally {
            sse.disconnect();
            server.stop(0);
        }
    }

    // ======================== 假 MCP Server（stdio） ========================

    private static void runFakeServer() throws Exception {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(
                new java.io.OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        String line;
        while ((line = in.readLine()) != null) {
            JsonObject req;
            try {
                req = JsonParser.parseString(line).getAsJsonObject();
            } catch (Exception e) {
                continue;
            }
            String method = req.has("method") ? req.get("method").getAsString() : "";
            boolean hasId = req.has("id");
            String result;
            switch (method) {
                case "initialize":
                    result = "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                            + "\"serverInfo\":{\"name\":\"fake\",\"version\":\"1\"}}";
                    break;
                case "tools/list":
                    result = "{\"tools\":[{\"name\":\"echo\",\"description\":\"echo tool\","
                            + "\"inputSchema\":{\"type\":\"object\",\"properties\":"
                            + "{\"text\":{\"type\":\"string\"}}}}]}";
                    break;
                case "tools/call":
                    result = "{\"content\":[{\"type\":\"text\",\"text\":\"ECHO\"}]}";
                    break;
                case "hang":
                    continue;   // 永不回包：模拟卡死的 Server
                default:
                    result = "{}";
            }
            if (hasId) {
                out.println("{\"jsonrpc\":\"2.0\",\"id\":" + req.get("id")
                        + ",\"result\":" + result + "}");
            }
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            System.out.println("  ✗ " + name);
        }
    }
}
