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
 * file_operation 技能 workspaceMode 自测（runnable main，无 JUnit——仓库自测惯例）。
 *
 * 运行：
 *   /d/maven/bin/mvn -q -pl aiagent/skill-builtin -am -DskipTests install
 *   /d/maven/bin/mvn -q -pl aiagent/skill-builtin test-compile
 *   /d/maven/bin/mvn -q -pl aiagent/skill-builtin dependency:build-classpath -Dmdep.outputFile=cp.txt
 *   java -cp "aiagent/skill-builtin/target/test-classes;aiagent/skill-builtin/target/classes;aiagent/common/target/classes;core/target/classes;$(cat aiagent/skill-builtin/cp.txt)" \
 *        cn.tianlong.tlobject.aiagent.skill.builtin.FileWorkspaceModeSelfTest
 * 失败退出码 1。
 */
public class FileWorkspaceModeSelfTest extends TLFileOperationSkill {

    private static int passed = 0, failed = 0;

    public FileWorkspaceModeSelfTest() { super(); }

    @Override public void putLog(String content, LogLevel logLevel) { /* 静默：绕开未初始化工厂 */ }
    @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }

    static void check(String name, boolean cond, String extra) {
        if (cond) { passed++; System.out.println("PASS " + name); }
        else { failed++; System.out.println("FAIL " + name + " | " + extra); }
    }

    /** 组装一次 SKILLINPUT 调用：userId/sessionId 走 systemArgs（技能身份字段只信 systemArgs） */
    static TLMsg call(FileWorkspaceModeSelfTest s, Object... kv) {
        Map<String, Object> input = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) input.put((String) kv[i], kv[i + 1]);
        TLMsg m = new TLMsg();
        m.setParam(AI_P_SKILLINPUT, input);     // 常量继承自 TLAiAgentParamString（"skillInput"）
        m.setSystemParam(AI_P_USERID, "alice");
        m.setSystemParam(AI_P_SESSIONID, "s1");
        return s.execute(null, m);
    }

    static String out(TLMsg r) { return r.getStringParam(AI_P_SKILLOUTPUT, ""); }

    static boolean isOk(TLMsg r) { return r.parseBoolean(RESULT, false); }

    /** 从 parameterSchema 取 path 描述（本类继承技能，protected 字段可见） */
    static String pathDesc(FileWorkspaceModeSelfTest s) {
        Object v = s.parameterSchema == null ? null : s.parameterSchema.get("path");
        if (!(v instanceof Map)) return "<no path schema>";
        Object d = ((Map<?, ?>) v).get("description");
        return d == null ? "<no description>" : String.valueOf(d);
    }

    /** 读文件内容；不存在/读失败返回 null */
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

    public static void main(String[] args) throws Exception {
        String oldProp = System.getProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
        Path tempDir = Files.createTempDirectory("workspacemodetest");
        Path oldRoot = Files.createTempDirectory("workspacemodeoldroot");
        Path otherDir = Files.createTempDirectory("workspacemodeabs");
        try {
            System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, tempDir.toString());
            // 旧根里放一个 "hello" 文件：workspaceMode=true 时 search/glob 绝不能看到它（证明根已切到 workspace）
            Files.write(oldRoot.resolve("oldonly.txt"), "hello from old root\n".getBytes(StandardCharsets.UTF_8));

            FileWorkspaceModeSelfTest s = new FileWorkspaceModeSelfTest();
            s.skillName = "file_operation";
            s.params = new HashMap<>();
            s.params.put("workspaceMode", "true");
            s.params.put("allowedRootPath", oldRoot.toString());     // 应被 workspaceMode 取代
            s.setModuleParams();

            // schema 的 path 描述随 workspaceMode 变化（schema 只首建，setModuleParams 可重入）
            check("0a workspaceMode=true 时 path 描述指向用户 workspace",
                    pathDesc(s).contains("current user workspace"), pathDesc(s));

            Path userRoot = tempDir.resolve("alice");

            // ---- 1. 相对路径写入 workspace/{userId} ----
            TLMsg r = call(s, "operation", "write", "path", "rel.txt", "content", "hello workspace\n");
            String relContent = readIfExists(userRoot.resolve("rel.txt"));
            check("1a 相对路径写入 workspace/<userId>", isOk(r) && "hello workspace\n".equals(relContent),
                    "ok=" + isOk(r) + ", content=" + relContent + ", out=" + out(r));
            check("1b workspace 目录按需创建", Files.isDirectory(userRoot), "dir=" + userRoot);
            check("1c 不再落到旧 allowedRootPath", !Files.exists(oldRoot.resolve("rel.txt")), out(r));

            // 跨用户隔离：共享 base 下放 bob 的文件。search/glob 若错把根指向 shared base（而非
            // workspace/{userId}），bob 的 "hello bob secret" 会被搜到/列出——下面 3c/3d/4c 必须失败
            Path bobRoot = tempDir.resolve("bob");
            Files.createDirectories(bobRoot);
            Files.write(bobRoot.resolve("bobonly.txt"), "hello bob secret\n".getBytes(StandardCharsets.UTF_8));

            // ---- 2. 绝对路径原样使用（不做 allowedRoot 硬校验）----
            Path absTarget = otherDir.resolve("abs.txt");
            r = call(s, "operation", "write", "path", absTarget.toString(), "content", "abs content\n");
            String absContent = readIfExists(absTarget);
            check("2 绝对路径原样使用", isOk(r) && "abs content\n".equals(absContent),
                    "ok=" + isOk(r) + ", content=" + absContent + ", out=" + out(r));

            // ---- 3. search 根 = workspace/{userId} ----
            r = call(s, "operation", "search", "pattern", "hello");
            String so = out(r);
            check("3a search 命中 workspace 内文件", isOk(r) && so.contains("rel.txt"), so);
            check("3b search 不扫旧 allowedRootPath", !so.contains("oldonly.txt"), so);
            check("3c search 不跨用户（看不到 bob 的 bobonly.txt）", !so.contains("bobonly.txt"), so);
            check("3d search 不跨用户（看不到 bob 文件内容）", !so.contains("hello bob secret"), so);

            // ---- 4. glob 根 = workspace/{userId} ----
            r = call(s, "operation", "glob", "pattern", "*.txt");
            String go = out(r);
            check("4a glob 命中 workspace 内文件", isOk(r) && go.contains("rel.txt"), go);
            check("4b glob 不扫旧 allowedRootPath", !go.contains("oldonly.txt"), go);
            check("4c glob 不跨用户（看不到 bob 的 bobonly.txt）", !go.contains("bobonly.txt"), go);

            // ---- 5. workspaceMode=false 保持旧行为 ----
            FileWorkspaceModeSelfTest old = new FileWorkspaceModeSelfTest();
            old.skillName = "file_operation";
            old.params = new HashMap<>();
            old.params.put("allowedRootPath", oldRoot.toString());
            old.setModuleParams();
            check("0b workspaceMode=false 时 path 描述指向 allowed root",
                    pathDesc(old).contains("allowed root"), pathDesc(old));
            r = call(old, "operation", "write", "path", "legacy.txt", "content", "legacy\n");
            check("5 默认(false)仍写 allowedRootPath",
                    isOk(r) && "legacy\n".equals(readIfExists(oldRoot.resolve("legacy.txt")))
                            && !Files.exists(userRoot.resolve("legacy.txt")),
                    "ok=" + isOk(r) + ", out=" + out(r));

            // ---- 6. 运行期重入（reloadConfig 场景）：同实例改回 false 后描述随之刷新 ----
            s.params.put("workspaceMode", "false");
            s.setModuleParams();
            check("6 setModuleParams 重入后 path 描述刷新",
                    pathDesc(s).contains("allowed root"), pathDesc(s));
        } finally {
            if (oldProp == null) System.clearProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
            else System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, oldProp);
            deleteRecursively(tempDir);
            deleteRecursively(oldRoot);
            deleteRecursively(otherDir);
        }

        System.out.println("\n==== FileWorkspaceModeSelfTest: " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }
}
