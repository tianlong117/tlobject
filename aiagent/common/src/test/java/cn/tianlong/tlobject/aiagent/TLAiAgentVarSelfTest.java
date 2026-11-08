package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * TLAiAgent.resolveVar 模板变量自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>覆盖 workspace 隔离新增的提示词变量：
 * <ul>
 *   <li>{@code {{userId}}} —— systemArgs 优先的 userId 解析；</li>
 *   <li>{@code {{userWorkspace}}} —— {@code <workspaceRoot>/<userId>}，统一正斜杠；</li>
 *   <li>未匹配变量原样保留（不破坏模板）。</li>
 * </ul>
 *
 * <p>失败以退出码 1 结束。
 */
public class TLAiAgentVarSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    /** 与生产语义一致：resolveVar 是私有实例方法，自测经反射调用 */
    private static final String METHOD = "resolveVar";

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("tlagent-var-test");
        String old = System.getProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
        try {
            System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, tmp.toString());

            // 不启动工厂：只需 name/params/agentDescription 可访问，日志全部丢弃
            TLAiAgent agent = new TLAiAgent("varTest") {
                @Override
                public void putLog(String content, LogLevel logLevel) { }
            };

            Method resolveVar = TLAiAgent.class.getDeclaredMethod(METHOD,
                    String.class, TLMsg.class, String.class, String.class);
            resolveVar.setAccessible(true);

            TLMsg msg = new TLMsg()
                    .setSystemParam(AI_P_USERID, "alice")
                    .setSystemParam(AI_P_SESSIONID, "s1");

            check("{{userId}} 解析为 alice",
                    "alice".equals(resolveVar.invoke(agent, "userId", msg, "s1", "now")));

            String expectedWs = tmp.toAbsolutePath().normalize().toString()
                    .replace('\\', '/') + "/alice";
            String ws = (String) resolveVar.invoke(agent, "userWorkspace", msg, "s1", "now");
            check("{{userWorkspace}} = <root>/alice 且正斜杠: " + ws,
                    expectedWs.equals(ws));

            check("未知变量原样保留",
                    "{{nope}}".equals(resolveVar.invoke(agent, "nope", msg, "s1", "now")));

            // 旧的 fallback 语义不受影响：消息参数 > 内置变量
            check("date/time 仍解析为 nowStr",
                    "now".equals(resolveVar.invoke(agent, "date", msg, "s1", "now")));
            check("sessionId 仍解析为入参",
                    "s1".equals(resolveVar.invoke(agent, "sessionId", msg, "s1", "now")));

            TLMsg override = new TLMsg()
                    .setSystemParam(AI_P_USERID, "alice")
                    .setParam("userWorkspace", "/explicit/ws");
            check("消息参数优先于内置 userWorkspace",
                    "/explicit/ws".equals(resolveVar.invoke(agent, "userWorkspace", override, "s1", "now")));
        } finally {
            if (old == null) System.clearProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
            else System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, old);
            deleteRecursively(tmp);
        }
        System.out.println("TLAiAgentVarSelfTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name);
        }
    }

    private static void deleteRecursively(Path p) {
        try {
            if (Files.isDirectory(p)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                    for (Path c : ds) deleteRecursively(c);
                }
            }
            Files.deleteIfExists(p);
        } catch (IOException ignored) { }
    }
}
