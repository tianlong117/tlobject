package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * http_request 技能自测（runnable main，无 JUnit——仓库自测惯例）。
 *
 * 覆盖：响应体流式封顶（maxResponseKB）、分块传输（contentLength=-1）路径、正常小响应、
 * 非字符串 url（asString 防崩回归）、连接失败（IOException 回归）。
 * 服务器用 JDK 内置 com.sun.net.httpserver，无外部依赖。
 *
 * 运行：
 *   /d/maven/bin/mvn -q -pl aiagent/skill-builtin -am compile
 *   /d/maven/bin/mvn -q -pl aiagent/skill-builtin dependency:build-classpath -Dmdep.outputFile=cp.txt   # 首次或 pom 变化时
 *   CP="aiagent/skill-builtin/target/classes;aiagent/common/target/classes;core/target/classes;$(cat aiagent/skill-builtin/cp.txt)"
 *   java -cp "$CP" cn.tianlong.tlobject.aiagent.skill.builtin.HttpSkillSelfTest
 * 失败退出码 1。
 */
public class HttpSkillSelfTest extends TLHttpRequestSkill {

    private static int passed = 0, failed = 0;

    public HttpSkillSelfTest() { super(); }

    @Override public void putLog(String content, LogLevel logLevel) { /* 静默：绕开未初始化工厂 */ }
    @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }
    @Override public void putLog(String content, LogLevel logLevel, String tag) { }

    static void check(String name, boolean cond, String extra) {
        if (cond) { passed++; System.out.println("PASS " + name); }
        else { failed++; System.out.println("FAIL " + name + " | " + extra); }
    }

    /** 组装一次 SKILLINPUT 调用 */
    static TLMsg call(HttpSkillSelfTest s, Object... kv) {
        Map<String, Object> input = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) input.put((String) kv[i], kv[i + 1]);
        TLMsg m = new TLMsg();
        m.setParam(AI_P_SKILLINPUT, input);
        return s.execute(null, m);
    }

    static String out(TLMsg r) { return r.getStringParam(AI_P_SKILLOUTPUT, ""); }

    /** RESULT 参数为 true 才判成功 */
    static boolean isOk(TLMsg r) { return r.parseBoolean(RESULT, false); }

    /** 一次性写完响应体并关闭 exchange（contentLength<0 走分块传输） */
    private static void respond(HttpExchange ex, byte[] body, long contentLength) throws java.io.IOException {
        try {
            if (contentLength < 0) ex.sendResponseHeaders(200, 0);      // 0 = chunked（响应无 Content-Length）
            else ex.sendResponseHeaders(200, contentLength);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        } finally {
            ex.close();
        }
    }

    public static void main(String[] args) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] big = new byte[100 * 1024];
        Arrays.fill(big, (byte) 'A');                                   // 100KB，远超声明的 8KB 上限
        byte[] small = "hello".getBytes(StandardCharsets.UTF_8);

        srv.createContext("/big", ex -> respond(ex, big, big.length));
        srv.createContext("/chunked", ex -> respond(ex, big, -1));      // contentLength=-1 路径（分块）
        srv.createContext("/small", ex -> respond(ex, small, small.length));
        srv.start();
        int port = srv.getAddress().getPort();

        try {
            HttpSkillSelfTest s = new HttpSkillSelfTest();
            s.skillName = "http_request";
            s.params = new HashMap<>();
            s.params.put("maxResponseKB", "8");
            s.setModuleParams();
            s.init();                                                   // 建 OkHttpClient / Gson

            // ---- ① 大响应（带 Content-Length）：封顶截断 + 头标记 ----
            TLMsg r = call(s, "url", "http://127.0.0.1:" + port + "/big");
            String o = out(r);
            check("1 大响应被截断且头部带 exceeds 8KB 标记",
                    o.contains("exceeds 8KB, truncated"), o.substring(0, Math.min(120, o.length())));
            check("1b 输出总长受下游截断兜底(<8000)", o.length() < 8000, "len=" + o.length());

            // ---- ② 分块传输（无 Content-Length，contentLength=-1 路径）：同样封顶 ----
            r = call(s, "url", "http://127.0.0.1:" + port + "/chunked");
            o = out(r);
            check("2 分块响应同样被截断带标记", o.contains("exceeds 8KB, truncated"), o.substring(0, Math.min(120, o.length())));
            check("2b 分块输出总长受下游截断兜底(<8000)", o.length() < 8000, "len=" + o.length());

            // ---- ③ 小响应：完整返回、无截断标记、RESULT=true ----
            r = call(s, "url", "http://127.0.0.1:" + port + "/small");
            o = out(r);
            check("3 小响应完整返回含 hello", isOk(r) && o.contains("hello"), o);
            check("3b 小响应无截断标记", !o.contains("truncated"), o);

            // ---- ④ url 传非字符串（Integer；Gson 真实链路为 Double 同类）：asString 防崩回归 ----
            //      （旧实现 (String) 强转 CCE / OkHttp 抛 IllegalArgumentException 都会逃出 execute
            //       经框架异常链 shutdown(-1) 杀死应用）
            try {
                r = call(s, "url", 123);
                check("4 非字符串 url 不抛异常且优雅报错", !isOk(r) && out(r).contains("invalid url"), out(r));
            } catch (Throwable e) {
                check("4 非字符串 url 不抛异常且优雅报错", false, e.toString());
            }

            // ---- ⑤ 连接失败（127.0.0.1:9 无监听）：IOException 回归为优雅报错 ----
            try {
                r = call(s, "url", "http://127.0.0.1:9/");
                check("5 连接失败不抛异常且报 failed", !isOk(r) && out(r).contains("failed"), out(r));
            } catch (Throwable e) {
                check("5 连接失败不抛异常且报 failed", false, e.toString());
            }

            System.out.println("\n== " + passed + " passed, " + failed + " failed ==");
        } finally {
            srv.stop(0);
        }
        System.exit(failed == 0 ? 0 : 1);
    }
}
