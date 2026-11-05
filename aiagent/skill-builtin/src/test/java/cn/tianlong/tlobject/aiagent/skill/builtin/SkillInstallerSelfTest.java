package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.base.TLBaseModule;
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
 * install_skill 技能自测（runnable main，无 JUnit——仓库自测惯例）。
 *
 * 覆盖：非法字符路径（InvalidPathException 曾杀应用）、盘符相对路径（本机实测该输入落在
 * "脚本文件不存在"分支——installer 的 IOError 腿无自然触发用例，IOError 收口由 script 自测实证覆盖）、
 * 非字符串 script_path（asString 防崩回归）、缺 skill_name 校验、Number 超时（Gson Double 回归——
 * instanceof Integer 会让 60.0 静默回落 120）。
 *
 * 落盘位置：moduleFactory=null 时 configDir="."，skillsDir = ./skills/{skill_name}，相对**进程 CWD**。
 * 测试用唯一 skill 名（zz-selftest-skill-<毫秒>，注意 skill_name 格式规则禁止下划线），
 * 每一项无论断言成败都在 finally 里删除 ./skills/<该名字>；若 ./skills 由本测试创建且事后为空则一并删除。
 *
 * 运行（在仓库根执行，使 ./skills 落在仓库根）：
 *   /d/maven/bin/mvn -q -pl aiagent/skill-builtin -am compile
 *   /d/maven/bin/mvn -q -pl aiagent/skill-builtin dependency:build-classpath -Dmdep.outputFile=cp.txt   # 首次或 pom 变化时
 *   CP="aiagent/skill-builtin/target/classes;aiagent/common/target/classes;core/target/classes;$(cat aiagent/skill-builtin/cp.txt)"
 *   java -cp "$CP" cn.tianlong.tlobject.aiagent.skill.builtin.SkillInstallerSelfTest
 * 失败退出码 1。
 */
public class SkillInstallerSelfTest extends TLSkillInstallerSkill {

    private static int passed = 0, failed = 0;
    private static Path tempDir;

    public SkillInstallerSelfTest() { super(); }

    @Override public void putLog(String content, LogLevel logLevel) { /* 静默：绕开未初始化工厂 */ }
    @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }
    @Override public void putLog(String content, LogLevel logLevel, String tag) { }

    /**
     * 假父 Agent：只用于满足“fromWho instanceof TLBaseModule”，getMsg 直接返回注册成功，
     * 让第 5 项走到 isOk 的成功分支（真实应用里这一步是 TLAiAgent.hotLoadSkill）。
     */
    static class FakeAgent extends TLBaseModule {
        FakeAgent() { super("selftestAgent"); }
        @Override protected TLBaseModule init() { return this; }
        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) { return null; }
        @Override public TLMsg getMsg(Object fromWho, TLMsg msg) {
            return new TLMsg().setParam(RESULT, true).setParam(MODULENAME, "selftestScriptSkill");
        }
    }

    /** 组装一次 SKILLINPUT 调用；fromWho 传父 Agent（可空） */
    static TLMsg call(SkillInstallerSelfTest s, Object fromWho, Object... kv) {
        Map<String, Object> input = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) input.put((String) kv[i], kv[i + 1]);
        TLMsg m = new TLMsg();
        m.setParam(AI_P_SKILLINPUT, input);
        return s.execute(fromWho, m);
    }

    static String out(TLMsg r) { return r.getStringParam(AI_P_SKILLOUTPUT, ""); }

    /** RESULT 参数为 true 才判成功 */
    static boolean isOk(TLMsg r) { return r.parseBoolean(RESULT, false); }

    static void check(String name, boolean cond, String extra) {
        if (cond) { passed++; System.out.println("PASS " + name); }
        else { failed++; System.out.println("FAIL " + name + " | " + extra); }
    }

    /** 唯一的合法 skill 名（格式规则只允许小写字母/数字/连字符） */
    private static String uniqueName() {
        return "zz-selftest-skill-" + System.currentTimeMillis();
    }

    private static Path skillDir(String skillName) {
        return Paths.get(".", "skills", skillName);
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

    private static String readIfExists(Path p) {
        try {
            return Files.exists(p) ? new String(Files.readAllBytes(p), StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        tempDir = Files.createTempDirectory("installerskilltest");
        Path pScript = tempDir.resolve("p.py");
        Files.write(pScript, "print(5)\n".getBytes(StandardCharsets.UTF_8));

        SkillInstallerSelfTest s = new SkillInstallerSelfTest();
        s.skillName = "install_skill";
        s.params = new HashMap<>();
        s.setModuleParams();
        s.init();

        Path skillsRoot = Paths.get("skills");
        boolean skillsRootExisted = Files.exists(skillsRoot);

        // ---- 1 非法字符（*）：Paths.get 抛 InvalidPathException（曾逃出 execute 杀应用）----
        String n1 = uniqueName();
        try {
            TLMsg r = call(s, null, "script_path", "a*b.py", "skill_name", n1);
            check("1 非法字符不抛异常且报 script_path 无效",
                    !isOk(r) && out(r).contains("script_path 无效"), out(r));
        } catch (Throwable t) {
            check("1 非法字符不抛异常且报 script_path 无效", false, t.toString());
        } finally {
            deleteRecursively(skillDir(n1));
        }

        // ---- 2 盘符相对路径（a:b.py）：toAbsolutePath 抛 IOError（Error 族，catch(Exception) 接不住）----
        String n2 = uniqueName();
        try {
            TLMsg r = call(s, null, "script_path", "a:b.py", "skill_name", n2);
            // 两种优雅结局都算通过（视盘符 A: 是否存在，Files.exists 可能直接 false，或路径操作抛
            // IOError 被守卫块接住转 "script_path 无效"）；崩掉（异常逃出）或成功才算红
            check("2 盘符相对路径不抛异常且优雅失败",
                    !isOk(r) && (out(r).contains("脚本文件不存在") || out(r).contains("script_path 无效")),
                    "ok=" + isOk(r) + ", out=" + out(r));
        } catch (Throwable t) {
            check("2 盘符相对路径不抛异常且优雅失败", false, t.toString());
        } finally {
            deleteRecursively(skillDir(n2));
        }

        // ---- 3 非字符串 script_path（Integer；Gson 真实链路为 Double 同类）：asString 防崩回归 ----
        String n3 = uniqueName();
        try {
            TLMsg r = call(s, null, "script_path", 123, "skill_name", n3);
            // String.valueOf(123)="123" 经正常路径走到"文件不存在"，全程无 CCE/JVM 文案泄出
            check("3 非字符串 script_path 字符串化后优雅报不存在",
                    !isOk(r) && out(r).contains("脚本文件不存在") && !out(r).contains("cannot be cast"),
                    "ok=" + isOk(r) + ", out=" + out(r));
        } catch (Throwable t) {
            check("3 非字符串 script_path 字符串化后优雅报不存在", false, t.toString());
        } finally {
            deleteRecursively(skillDir(n3));
        }

        // ---- 4 缺 skill_name：脚本存在也只报缺参数 ----
        String n4 = uniqueName();
        try {
            TLMsg r = call(s, null, "script_path", pScript.toAbsolutePath().toString());
            check("4 缺 skill_name 报必填参数", !isOk(r) && out(r).contains("skill_name"), out(r));
        } catch (Throwable t) {
            check("4 缺 skill_name 报必填参数", false, t.toString());
        } finally {
            deleteRecursively(skillDir(n4));
        }

        // ---- 5 Number 超时（Gson Double 回归）：instanceof Integer 会让 60.0 静默回落 120 ----
        String n5 = uniqueName();
        Path landed = skillDir(n5);
        try {
            TLMsg r = call(s, new FakeAgent(), "script_path", pScript.toAbsolutePath().toString(),
                    "skill_name", n5, "skill_description", "selftest", "max_execution_time", 60.0);
            String md = readIfExists(landed.resolve("SKILL.md"));
            check("5 Double 超时被接收（SKILL.md 含 超时: 60 秒）",
                    isOk(r) && md != null && md.contains("超时: 60 秒"),
                    "ok=" + isOk(r) + ", out=" + out(r) + ", md=" + (md == null ? "<无>" : md.replace("\n", "\\n")));
            check("5b 脚本已复制到 skills/" + n5 + "/scripts/p.py",
                    "print(5)\n".equals(readIfExists(landed.resolve("scripts").resolve("p.py"))),
                    "落点=" + landed.toAbsolutePath());
        } catch (Throwable t) {
            check("5 Double 超时被接收（SKILL.md 含 超时: 60 秒）", false, t.toString());
        } finally {
            deleteRecursively(landed);
        }

        System.out.println("\n== " + passed + " passed, " + failed + " failed ==");

        // ./skills 若由本测试创建且事后为空，一并清理（不留空目录残渣）
        if (!skillsRootExisted) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(skillsRoot)) {
                if (!ds.iterator().hasNext()) Files.deleteIfExists(skillsRoot);
            } catch (Exception ignored) { }
        }
        deleteRecursively(tempDir);
        System.exit(failed == 0 ? 0 : 1);
    }
}
