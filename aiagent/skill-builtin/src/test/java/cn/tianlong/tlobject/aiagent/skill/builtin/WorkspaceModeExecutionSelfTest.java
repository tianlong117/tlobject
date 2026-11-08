package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.aiagent.TLUserWorkspace;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * code_execution / script_execution 技能 workspaceMode 自测（runnable main，无 JUnit——仓库自测惯例）。
 *
 * <p>覆盖：
 * <ol>
 *   <li>code_execution workspaceMode=true：Python 子进程 CWD = workspace/{userId}，用户目录按需创建；</li>
 *   <li>code_execution 默认(false)：CWD 保持旧行为（进程 CWD，不切到 workspace）；</li>
 *   <li>script_execution workspaceMode=true 且脚本在 workspace 内：子进程 CWD = workspace，
 *       脚本的相对写入落到 workspace/{userId}；</li>
 *   <li>script_execution workspaceMode=true 但脚本在 workspace 外：保持原 CWD（内置技能脚本依赖）；</li>
 *   <li>script_execution 默认(false)：即使脚本在 workspace 内也保持原 CWD（旧行为不变）；</li>
 *   <li>workspaceMode="TRUE"（大写 XML 值）：Boolean.parseBoolean 仍按 true 生效。</li>
 * </ol>
 *
 * <p>运行：
 *   /d/maven/bin/mvn -q -pl aiagent/skill-builtin -am -DskipTests install
 *   /d/maven/bin/mvn -q -pl aiagent/skill-builtin test-compile
 *   /d/maven/bin/mvn -q -pl aiagent/skill-builtin dependency:build-classpath -Dmdep.outputFile=cp.txt
 *   java -cp "aiagent/skill-builtin/target/test-classes;aiagent/skill-builtin/target/classes;aiagent/common/target/classes;core/target/classes;$(cat aiagent/skill-builtin/cp.txt)" \
 *        cn.tianlong.tlobject.aiagent.skill.builtin.WorkspaceModeExecutionSelfTest
 * 失败退出码 1。
 */
public class WorkspaceModeExecutionSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        String oldProp = System.getProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
        Path tempDir = Files.createTempDirectory("workspacemodeexec");
        try {
            System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, tempDir.toString());
            Path processCwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
            Path aliceDir = tempDir.resolve("alice");

            // ---- 1. code_execution workspaceMode=true：子进程 CWD = workspace/{userId} ----
            CodeSkill code = new CodeSkill("true", "workspaceModeCodeOn");
            TLMsg r = code.run("alice", "python", "import os; print(os.getcwd())");
            String out = out(r);
            check("1a code_execution 子进程 CWD 在 workspace/alice",
                    isOk(r) && containsPath(out, aliceDir), out);
            check("1b workspace 目录按需创建", Files.isDirectory(aliceDir), aliceDir.toString());

            // ---- 2. code_execution 默认(false)：CWD 保持进程 CWD，且不产生用户工作区 ----
            Path bobDir = tempDir.resolve("bob");
            CodeSkill defaultCode = new CodeSkill(null, "workspaceModeCodeDefault");
            r = defaultCode.run("bob", "python", "import os; print(os.getcwd())");
            out = out(r);
            check("2a 默认(false) CWD 保持进程 CWD",
                    isOk(r) && containsPath(out, processCwd), out);
            check("2b 默认(false) 不切换到 workspace", !containsPath(out, bobDir), out);
            check("2c 默认(false) 不创建用户工作区（旧行为无副作用）",
                    !Files.exists(bobDir), bobDir.toString());

            // ---- 3. script_execution workspaceMode=true，脚本在 workspace 内：相对写入落 workspace ----
            Files.createDirectories(aliceDir);
            Files.write(aliceDir.resolve("w.py"),
                    "open('rel_out.txt','w',encoding='utf-8').write('ok')".getBytes(StandardCharsets.UTF_8));
            ScriptSkill script = new ScriptSkill("true", tempDir.toString(), "workspaceModeScriptOn");
            r = script.run("alice", "alice/w.py");
            check("3a 脚本内相对写入落到 workspace/alice",
                    isOk(r) && Files.exists(aliceDir.resolve("rel_out.txt")), out(r));
            check("3b rel_out.txt 内容为 ok",
                    "ok".equals(readIfExists(aliceDir.resolve("rel_out.txt"))),
                    String.valueOf(readIfExists(aliceDir.resolve("rel_out.txt"))));

            // ---- 4. script_execution workspaceMode=true，脚本在 workspace 外：CWD 不变、不预先创建工作区 ----
            Path carolDir = tempDir.resolve("carol");
            Files.write(tempDir.resolve("outside.py"),
                    "import os; print(os.getcwd())".getBytes(StandardCharsets.UTF_8));
            r = script.run("carol", "outside.py");
            out = out(r);
            check("4a 脚本在 workspace 外时 CWD 保持进程 CWD",
                    isOk(r) && containsPath(out, processCwd), out);
            check("4b 脚本在 workspace 外时不切到 workspace", !containsPath(out, carolDir), out);
            check("4c 脚本在 workspace 外时不预先创建用户工作区",
                    !Files.exists(carolDir), carolDir.toString());

            // ---- 5. script_execution 默认(false)：脚本在 workspace 内也不切 CWD（旧行为不变） ----
            // 用只打印的脚本（不写相对文件），避免旧行为把文件写到测试进程 CWD
            Files.write(aliceDir.resolve("inner_print.py"),
                    "import os; print(os.getcwd())".getBytes(StandardCharsets.UTF_8));
            ScriptSkill defaultScript = new ScriptSkill(null, tempDir.toString(), "workspaceModeScriptDefault");
            r = defaultScript.run("alice", "alice/inner_print.py");
            out = out(r);
            check("5 默认(false) 保持进程 CWD 且成功执行",
                    isOk(r) && containsPath(out, processCwd) && !containsPath(out, aliceDir), out);

            // ---- 6. workspaceMode="TRUE"（大写 XML 值）：仍按 true 生效 ----
            Path daveDir = tempDir.resolve("dave");
            CodeSkill upperCode = new CodeSkill("TRUE", "workspaceModeCodeUpper");
            r = upperCode.run("dave", "python", "import os; print(os.getcwd())");
            out = out(r);
            check("6 workspaceMode=\"TRUE\" 切到 workspace/dave",
                    isOk(r) && containsPath(out, daveDir) && Files.isDirectory(daveDir), out);
        } finally {
            if (oldProp == null) System.clearProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
            else System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, oldProp);
            deleteRecursively(tempDir);
        }

        System.out.println("\n==== WorkspaceModeExecutionSelfTest: " + passed + " passed, " + failed + " failed ====");
        System.out.println("RESULT: " + (failed == 0 ? "PASS" : "FAIL"));
        if (failed > 0) System.exit(1);
    }

    static void check(String name, boolean cond, String extra) {
        if (cond) { passed++; System.out.println("PASS " + name); }
        else { failed++; System.out.println("FAIL " + name + " | " + extra); }
    }

    static String out(TLMsg r) { return r == null ? "" : r.getStringParam(AI_P_SKILLOUTPUT, ""); }

    static boolean isOk(TLMsg r) { return r != null && r.parseBoolean(RESULT, false); }

    /** 输出中是否包含目标路径（统一斜杠；兼容短/长路径两种写法——Windows TEMP 可能以 8.3 短名出现） */
    static boolean containsPath(String output, Path expected) {
        String normalized = output.replace('\\', '/');
        if (normalized.contains(expected.toString().replace('\\', '/'))) return true;
        try {
            return normalized.contains(expected.toRealPath().toString().replace('\\', '/'));
        } catch (Exception e) {
            return false;
        }
    }

    /** 读文件内容（UTF-8）；不存在/读失败返回 null */
    static String readIfExists(Path p) {
        try {
            return Files.exists(p) ? new String(Files.readAllBytes(p), StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
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

    /** 组装一次 SKILLINPUT 调用：userId 走 systemArgs（技能身份字段只信 systemArgs） */
    static TLMsg skillMsg(String userId, Map<String, Object> input) {
        TLMsg m = new TLMsg();
        m.setParam(AI_P_SKILLINPUT, input);
        m.setSystemParam(AI_P_USERID, userId);
        m.setSystemParam(AI_P_SESSIONID, "workspaceModeSelfTest");
        return m;
    }

    /** code_execution 测试子类：workspaceMode 由构造参数注入（null = 不配，走默认 false） */
    static class CodeSkill extends TLCodeExecutionSkill {
        CodeSkill(String workspaceMode, String name) {
            super(name);
            params = new HashMap<>();
            if (workspaceMode != null) params.put("workspaceMode", workspaceMode);
            setModuleParams();
        }

        TLMsg run(String userId, String language, String code) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("language", language);
            input.put("code", code);
            return execute(null, skillMsg(userId, input));
        }

        @Override public void putLog(String content, LogLevel logLevel) { /* 自测静默：未初始化工厂 */ }
        @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
    }

    /** script_execution 测试子类：workspaceMode + allowedScriptDir 由构造参数注入 */
    static class ScriptSkill extends TLScriptExecutionSkill {
        ScriptSkill(String workspaceMode, String allowedDir, String name) {
            super(name);
            params = new HashMap<>();
            if (workspaceMode != null) params.put("workspaceMode", workspaceMode);
            params.put("allowedScriptDir", allowedDir);
            setModuleParams();
        }

        TLMsg run(String userId, String scriptPath) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("script_path", scriptPath);
            return execute(null, skillMsg(userId, input));
        }

        @Override public void putLog(String content, LogLevel logLevel) { /* 自测静默：未初始化工厂 */ }
        @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
    }
}
