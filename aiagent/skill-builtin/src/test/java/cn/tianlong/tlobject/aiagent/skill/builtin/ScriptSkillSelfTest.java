package cn.tianlong.tlobject.aiagent.skill.builtin;

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
 * script_execution 技能自测（runnable main，无 JUnit——仓库自测惯例）。
 *
 * 覆盖：正常执行、盘符相对路径 IOError 族（历史"杀应用"路径）、非法字符 InvalidPathException、
 * 脚本不存在、目录内相对子路径、越界路径、script_path/arguments 非字符串（asString 防崩回归）、
 * 目录当脚本（not a file）。
 * 需要本机 PATH 上有 python（本仓库环境已具备）。
 *
 * 运行：
 *   /d/maven/bin/mvn -q -pl aiagent/skill-builtin -am compile
 *   /d/maven/bin/mvn -q -pl aiagent/skill-builtin dependency:build-classpath -Dmdep.outputFile=cp.txt   # 首次或 pom 变化时
 *   CP="aiagent/skill-builtin/target/classes;aiagent/common/target/classes;core/target/classes;$(cat aiagent/skill-builtin/cp.txt)"
 *   java -cp "$CP" cn.tianlong.tlobject.aiagent.skill.builtin.ScriptSkillSelfTest
 * 失败退出码 1。
 */
public class ScriptSkillSelfTest extends TLScriptExecutionSkill {

    private static int passed = 0, failed = 0;
    private static Path tempDir;

    public ScriptSkillSelfTest() { super(); }

    @Override public void putLog(String content, LogLevel logLevel) { /* 静默：绕开未初始化工厂 */ }
    @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }
    @Override public void putLog(String content, LogLevel logLevel, String tag) { }

    static void check(String name, boolean cond, String extra) {
        if (cond) { passed++; System.out.println("PASS " + name); }
        else { failed++; System.out.println("FAIL " + name + " | " + extra); }
    }

    /** 组装一次 SKILLINPUT 调用 */
    static TLMsg call(ScriptSkillSelfTest s, Object... kv) {
        Map<String, Object> input = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) input.put((String) kv[i], kv[i + 1]);
        TLMsg m = new TLMsg();
        m.setParam(AI_P_SKILLINPUT, input);
        return s.execute(null, m);
    }

    static String out(TLMsg r) { return r.getStringParam(AI_P_SKILLOUTPUT, ""); }

    /** RESULT 参数为 true 才判成功 */
    static boolean isOk(TLMsg r) { return r.parseBoolean(RESULT, false); }

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
        tempDir = Files.createTempDirectory("scriptskilltest");
        Files.write(tempDir.resolve("a.py"), "print(1+1)\n".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(tempDir.resolve("sub"));
        Files.write(tempDir.resolve("sub").resolve("b.py"), "print(3)\n".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(tempDir.resolve("empty"));

        ScriptSkillSelfTest s = new ScriptSkillSelfTest();
        s.skillName = "script_execution";
        s.params = new HashMap<>();
        s.params.put("allowedScriptDir", tempDir.toString());     // 绝对路径：resolveScriptDir 原样返回
        s.setModuleParams();
        s.init();

        // ---- 1 正常执行 ----
        try {
            TLMsg r = call(s, "script_path", "a.py");
            check("1 正常执行 a.py 输出 2", isOk(r) && out(r).contains("2"), out(r));
        } catch (Throwable e) {
            check("1 正常执行 a.py 输出 2", false, e.toString());
        }

        // ---- 2 盘符相对路径（a:b.py）：WindowsPath.toAbsolutePath 抛 IOError（Error 族，
        //        catch(Exception) 接不住 → 逃出 execute 经框架链 shutdown 整个应用）----
        try {
            TLMsg r = call(s, "script_path", "a:b.py");
            check("2 盘符相对路径不抛异常且优雅失败", !isOk(r) && out(r).contains("Execution error"), out(r));
        } catch (Throwable e) {
            check("2 盘符相对路径不抛异常且优雅失败", false, e.toString());
        }

        // ---- 3 非法字符（*）：Paths.get 抛 InvalidPathException（IllegalArgumentException 族）----
        try {
            TLMsg r = call(s, "script_path", "a*b.py");
            check("3 非法字符不抛异常且报 invalid script_path",
                    !isOk(r) && out(r).contains("invalid script_path"), out(r));
        } catch (Throwable e) {
            check("3 非法字符不抛异常且报 invalid script_path", false, e.toString());
        }

        // ---- 4 不存在脚本 ----
        try {
            TLMsg r = call(s, "script_path", "nope.py");
            check("4 不存在脚本报 not found", !isOk(r) && out(r).contains("not found"), out(r));
        } catch (Throwable e) {
            check("4 不存在脚本报 not found", false, e.toString());
        }

        // ---- 5 目录内相对子路径 ----
        try {
            TLMsg r = call(s, "script_path", "sub/b.py");
            check("5 子路径 sub/b.py 输出 3", isOk(r) && out(r).contains("3"), out(r));
        } catch (Throwable e) {
            check("5 子路径 sub/b.py 输出 3", false, e.toString());
        }

        // ---- 6 越界路径 ----
        try {
            TLMsg r = call(s, "script_path", "../outside.py");
            check("6 越界路径被拒且含 outside", !isOk(r) && out(r).contains("outside"), out(r));
        } catch (Throwable e) {
            check("6 越界路径被拒且含 outside", false, e.toString());
        }

        // ---- 7 非字符串 script_path（Integer；Gson 真实链路为 Double 同类）：
        //        asString 防崩回归——(String) 强转 CCE 会逃出 execute 经框架链 shutdown 整个应用 ----
        try {
            TLMsg r = call(s, "script_path", 123);
            check("7 非字符串 script_path 不抛异常且不泄 JVM 强转文案",
                    !isOk(r) && !out(r).contains("cannot be cast"), out(r));
        } catch (Throwable e) {
            check("7 非字符串 script_path 不抛异常且不泄 JVM 强转文案", false, e.toString());
        }

        // ---- 8 非字符串 arguments（Integer）：asString 后按参数串传给脚本，正常执行不受影响 ----
        try {
            TLMsg r = call(s, "script_path", "a.py", "arguments", 123);
            check("8 非字符串 arguments 不抛异常且正常执行",
                    isOk(r) && out(r).contains("2"), out(r));
        } catch (Throwable e) {
            check("8 非字符串 arguments 不抛异常且正常执行", false, e.toString());
        }

        // ---- 9 目录当脚本：优雅报 not a file ----
        try {
            TLMsg r = call(s, "script_path", "empty");
            check("9 目录当脚本报 not a file", !isOk(r) && out(r).contains("not a file"), out(r));
        } catch (Throwable e) {
            check("9 目录当脚本报 not a file", false, e.toString());
        }

        System.out.println("\n== " + passed + " passed, " + failed + " failed ==");
        deleteRecursively(tempDir);
        System.exit(failed == 0 ? 0 : 1);
    }
}
