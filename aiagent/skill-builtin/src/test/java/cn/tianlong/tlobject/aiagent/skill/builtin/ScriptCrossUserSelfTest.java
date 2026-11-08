package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLUserWorkspace;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * script_execution 跨用户硬拒绝自测（runnable main，无 JUnit——仓库自测惯例）。
 *
 * <p>背景：codeAgent 的 allowedScriptDir 是共享 workspace 根。只有目录白名单时，
 * 用户 alice 在一次会话级授权后可以执行 {@code bob/b.py}。技能必须在执行前按
 * {@link TLUserWorkspace#isCrossUser} 硬拒绝其他用户目录下的脚本（与 workspaceMode 无关）。</p>
 *
 * <p>覆盖：
 * <ol>
 *   <li>alice 执行自己目录 alice/a.py → 成功且输出 A；</li>
 *   <li>alice 执行 bob/b.py → RESULT=false，文案含“无权访问其他用户目录”，脚本未执行。</li>
 * </ol>
 *
 * <p>运行（需要 PATH 上有 python）：
 * <pre>
 *   /d/maven/bin/mvn -q -pl aiagent/skill-builtin test-compile
 *   /d/maven/bin/mvn -q -pl aiagent/skill-builtin dependency:build-classpath -Dmdep.outputFile=cp.txt
 *   java -cp "aiagent/skill-builtin/target/test-classes;aiagent/skill-builtin/target/classes;aiagent/common/target/classes;core/target/classes;$(cat aiagent/skill-builtin/cp.txt)" \
 *        cn.tianlong.tlobject.aiagent.skill.builtin.ScriptCrossUserSelfTest
 * </pre>
 * 失败退出码 1。
 */
public class ScriptCrossUserSelfTest extends TLScriptExecutionSkill {

    private static int passed = 0, failed = 0;

    /** 自测静默：绕开未初始化工厂（setModuleParams/executeScript 会打 DEBUG 日志） */
    @Override public void putLog(String content, LogLevel logLevel) { }
    @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }
    @Override public void putLog(String content, LogLevel logLevel, String tag) { }

    public static void main(String[] args) throws Exception {
        String oldProp = System.getProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
        Path ws = Files.createTempDirectory("scriptcrossuser");
        try {
            System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, ws.toString());
            Files.createDirectories(ws.resolve("alice"));
            Files.createDirectories(ws.resolve("bob"));
            Files.write(ws.resolve("alice").resolve("a.py"),
                    "print('A')\n".getBytes(StandardCharsets.UTF_8));
            Files.write(ws.resolve("bob").resolve("b.py"),
                    "print('B')\n".getBytes(StandardCharsets.UTF_8));

            ScriptCrossUserSelfTest s = new ScriptCrossUserSelfTest();
            s.skillName = "script_execution";
            s.params = new HashMap<>();
            s.params.put("allowedScriptDir", ws.toString());   // codeAgent 场景：共享 workspace 根
            s.params.put("workspaceMode", "true");
            s.setModuleParams();

            TLMsg own = call(s, "alice", "alice/a.py");
            check("1 自己 workspace 脚本可执行",
                    own.parseBoolean(RESULT, false) && out(own).contains("A"), out(own));

            TLMsg cross = call(s, "alice", "bob/b.py");
            check("2 跨用户脚本硬拒绝",
                    !cross.parseBoolean(RESULT, false)
                            && out(cross).contains("无权访问其他用户目录"), out(cross));
        } finally {
            if (oldProp == null) System.clearProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
            else System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, oldProp);
            deleteRecursively(ws);
        }

        System.out.println("\n== ScriptCrossUserSelfTest: " + passed + " passed, " + failed + " failed ==");
        if (failed > 0) System.exit(1);
    }

    /** 组装一次 SKILLINPUT 调用：userId 只走 systemArgs（技能身份字段只信 systemArgs） */
    private static TLMsg call(ScriptCrossUserSelfTest s, String userId, String scriptPath) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("script_path", scriptPath);
        TLMsg m = new TLMsg();
        m.setParam(AI_P_SKILLINPUT, input);
        m.setSystemParam(AI_P_USERID, userId);
        m.setSystemParam(AI_P_SESSIONID, "scriptCrossUserSelfTest");
        return s.execute(null, m);
    }

    private static String out(TLMsg r) {
        return r == null ? "" : r.getStringParam(AI_P_SKILLOUTPUT, "");
    }

    private static void check(String name, boolean ok, String extra) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name + " | " + extra); }
    }

    /** 尽力而为递归删除，异常一律吞掉 */
    private static void deleteRecursively(Path p) {
        try {
            if (Files.isDirectory(p)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                    for (Path c : ds) deleteRecursively(c);
                }
            }
            Files.deleteIfExists(p);
        } catch (Exception ignored) { }
    }
}
