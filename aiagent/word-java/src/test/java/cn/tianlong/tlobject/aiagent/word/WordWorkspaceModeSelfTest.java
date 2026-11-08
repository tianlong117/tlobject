package cn.tianlong.tlobject.aiagent.word;

import cn.tianlong.tlobject.aiagent.TLUserWorkspace;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * word 技能 workspaceMode + JavaWordEngine.allowOutsideRoot 自测
 * （runnable main，无 JUnit——仓库自测惯例）。
 *
 * 覆盖两件事：
 *   ① 引擎层 allowOutsideRoot=true 时绝对路径不再被 allowedRoot 硬拒绝；
 *      false（默认）时旧行为逐字不变。
 *   ② 技能壳层 workspaceMode=true 时 allowedRoot/workDir 都切到
 *      {@link TLUserWorkspace#userRoot}，并把 allowOutsideRoot 接通；
 *      workspaceMode=false（默认）仍是 allowedRootPath + workDir/userId 旧路径。
 *
 * 运行：
 *   /d/maven/bin/mvn -q -pl aiagent/word-java -am -DskipTests install
 *   /d/maven/bin/mvn -q -pl aiagent/word-java test-compile
 *   /d/maven/bin/mvn -q -pl aiagent/word-java dependency:build-classpath -Dmdep.outputFile=cp.txt
 *   java -cp "aiagent/word-java/target/test-classes;aiagent/word-java/target/classes;aiagent/common/target/classes;core/target/classes;$(cat aiagent/word-java/cp.txt)" \
 *        cn.tianlong.tlobject.aiagent.word.WordWorkspaceModeSelfTest
 * 失败退出码 1。
 */
public class WordWorkspaceModeSelfTest extends TLWordJavaSkill {

    private static int passed = 0, failed = 0;

    public WordWorkspaceModeSelfTest() { super(); }

    /** 静默日志：未初始化工厂时 putLog 会 NPE，错误路径不该把自测打断 */
    @Override public void putLog(String content, LogLevel logLevel) { }
    @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }

    static void check(String name, boolean cond, String extra) {
        if (cond) { passed++; System.out.println("PASS " + name); }
        else { failed++; System.out.println("FAIL " + name + " | " + extra); }
    }

    /** 组装一次 SKILLINPUT 调用：userId/sessionId 走 systemArgs（技能身份字段只信 systemArgs） */
    static TLMsg call(WordWorkspaceModeSelfTest s, Object... kv) {
        return callAs(s, "alice", kv);
    }

    /** 指定 userId（走 systemArgs）的一次调用 */
    static TLMsg callAs(WordWorkspaceModeSelfTest s, String userId, Object... kv) {
        Map<String, Object> input = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) input.put((String) kv[i], kv[i + 1]);
        TLMsg m = new TLMsg();
        m.setParam(AI_P_SKILLINPUT, input);
        m.setSystemParam(AI_P_USERID, userId);
        m.setSystemParam(AI_P_SESSIONID, "s1");
        return s.execute(null, m);
    }

    /** 只放一个身份字段（args 或 systemArgs）的调用：legacy 身份解析自测 */
    static TLMsg callIdentity(WordWorkspaceModeSelfTest s, boolean systemArgs, String key, String value,
                              Object... kv) {
        Map<String, Object> input = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) input.put((String) kv[i], kv[i + 1]);
        TLMsg m = new TLMsg();
        m.setParam(AI_P_SKILLINPUT, input);
        if (systemArgs) m.setSystemParam(key, value);
        else m.setParam(key, value);
        return s.execute(null, m);
    }

    static boolean isOk(TLMsg r) { return r != null && r.parseBoolean(RESULT, false); }

    static String out(TLMsg r) {
        return r == null ? "null" : r.getStringParam(AI_P_SKILLOUTPUT, "");
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
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
        // 三个临时目录都在 try 内创建：后一个创建失败时，前一个仍会在 finally 里被清理
        Path wsBase = null, outside = null, oldRoot = null;
        try {
            wsBase = Files.createTempDirectory("wordws");
            outside = Files.createTempDirectory("wordwsoutside");
            oldRoot = Files.createTempDirectory("wordwsold");
            System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, wsBase.toString());
            Path ws = wsBase.resolve("alice");

            // ================= ① 引擎层：allowOutsideRoot =================
            JavaWordEngine.Config loose = new JavaWordEngine.Config();
            loose.allowedRoot = ws;
            loose.workDir = ws;
            loose.allowOutsideRoot = true;
            JavaWordEngine engLoose = new JavaWordEngine(loose);

            JavaWordEngine.Result r1 = engLoose.execute("create",
                    map("path", "a.docx", "content", "# A"));
            check("1a allowOutsideRoot=true: 裸名 create 成功", r1.ok,
                    String.valueOf(r1.json));
            check("1b 裸名仍落 workDir（=ws）",
                    Files.isRegularFile(ws.resolve("a.docx")), "ws=" + ws);

            Path absOut = outside.resolve("b.docx");
            JavaWordEngine.Result r2 = engLoose.execute("create",
                    map("path", absOut.toString(), "content", "# B"));
            check("2a allowOutsideRoot=true: 绝对越界路径 create 成功（不再被拒）", r2.ok,
                    String.valueOf(r2.json));
            check("2b 越界文件真的落盘", Files.isRegularFile(absOut), "abs=" + absOut);

            JavaWordEngine.Result r3 = engLoose.execute("create",
                    map("path", "sub/c.docx", "content", "# C"));
            check("3 含分隔符的相对路径仍按 allowedRoot(ws) 解析",
                    r3.ok && Files.isRegularFile(ws.resolve("sub/c.docx")),
                    String.valueOf(r3.json));

            JavaWordEngine.Config strict = new JavaWordEngine.Config();
            strict.allowedRoot = ws;
            strict.workDir = ws;
            JavaWordEngine engStrict = new JavaWordEngine(strict);
            check("4a allowOutsideRoot 默认 false", !strict.allowOutsideRoot, "");
            Path strictOut = outside.resolve("d.docx");
            JavaWordEngine.Result r4 = engStrict.execute("create",
                    map("path", strictOut.toString(), "content", "x"));
            check("4b strict（allowOutsideRoot=false）: 同一越界绝对路径被拒", !r4.ok,
                    String.valueOf(r4.json));
            check("4c 错误点名 outside allowed root",
                    String.valueOf(r4.json.get("error")).contains("outside allowed root"),
                    String.valueOf(r4.json.get("error")));
            check("4d strict 下文件未被创建", !Files.exists(strictOut), "abs=" + strictOut);

            // ================= ② 技能壳层：workspaceMode =================
            WordWorkspaceModeSelfTest s = new WordWorkspaceModeSelfTest();
            s.skillName = "word";
            s.params = new HashMap<>();
            s.params.put("workspaceMode", "true");
            s.params.put("allowedRootPath", outside.toString());   // 应被 workspaceMode 取代
            s.setModuleParams();

            TLMsg m1 = call(s, "action", "create", "path", "skill-bare.docx", "content", "# S");
            check("5a workspaceMode=true: 裸名 create 成功", isOk(m1), out(m1));
            check("5b 文件落到 workspace/{userId}",
                    Files.isRegularFile(ws.resolve("skill-bare.docx")), "ws=" + ws);

            // 5c：含分隔符的相对路径（技能层）——旧行为按 allowedRoot 解析，workspaceMode 必须改按
            // workspace/{uid} 解析。裸名旧路径其实落 workDir（不落 allowedRoot），单查裸名是空断言。
            TLMsg m1b = call(s, "action", "create", "path", "sub/skill-rel.docx", "content", "# SR");
            check("5c 相对路径落 workspace/{uid}/sub/",
                    isOk(m1b) && Files.isRegularFile(ws.resolve("sub/skill-rel.docx")), out(m1b));
            check("5d 不再落到旧 allowedRootPath/sub/",
                    !Files.exists(outside.resolve("sub").resolve("skill-rel.docx")),
                    "outside=" + outside);

            Path skillAbs = outside.resolve("skill-abs.docx");
            TLMsg m2 = call(s, "action", "create", "path", skillAbs.toString(), "content", "# SA");
            check("6a workspaceMode=true: 绝对路径可用（allowOutsideRoot 已接通）",
                    isOk(m2), out(m2));
            check("6b 绝对路径文件已创建", Files.isRegularFile(skillAbs), "abs=" + skillAbs);

            // 6c：workspaceMode 是 XML 配置（技能参数），输入里的同名键必须被忽略
            TLMsg mFlag = call(s, "action", "create", "path", "xml-only.docx", "content", "# XF",
                    "workspaceMode", "false");
            check("6c 输入里的 workspaceMode 被忽略（仍落 workspace）",
                    isOk(mFlag) && Files.isRegularFile(ws.resolve("xml-only.docx")), out(mFlag));

            // 6d/6e：新用户 bob——ensureEngine 按需建 workspace/{uid}，即使随后的读失败也应已建好目录
            TLMsg mBob = callAs(s, "bob", "action", "read", "path", "missing.docx");
            check("6d 读缺失 .docx 失败", !isOk(mBob), out(mBob));
            check("6e workspace/{新用户} 目录按需创建",
                    Files.isDirectory(TLUserWorkspace.baseRoot().resolve("bob")),
                    "bob=" + TLUserWorkspace.baseRoot().resolve("bob"));

            // workspaceMode=false（默认）：旧行为逐字不变——越界绝对路径仍被拒。
            // workDir 必须放在 allowedRoot 下（与生产默认 "."+data/documents、以及
            // WordEngineSelfTest 的 root+data/default/documents 同构）：旧语义对裸名解析出的
            // workDir 路径也做 startsWith(allowedRoot) 校验，独置的 workDir 会被拒——那是旧行为本身。
            WordWorkspaceModeSelfTest old = new WordWorkspaceModeSelfTest();
            old.skillName = "word";
            old.params = new HashMap<>();
            old.params.put("allowedRootPath", oldRoot.toString());
            old.params.put("workDir", oldRoot.resolve("data/documents").toString());
            old.setModuleParams();

            Path oldAbs = outside.resolve("legacy-abs.docx");
            TLMsg m3 = call(old, "action", "create", "path", oldAbs.toString(), "content", "# L");
            check("7a workspaceMode 默认 false: 越界绝对路径被拒（旧行为不变）",
                    !isOk(m3), out(m3));
            check("7b 错误命中 outside allowed root", out(m3).contains("outside allowed root"),
                    out(m3));
            check("7c 文件未被创建", !Files.exists(oldAbs), "abs=" + oldAbs);

            TLMsg m4 = call(old, "action", "create", "path", "legacy-bare.docx", "content", "# LB");
            Path legacyWork = oldRoot.resolve("data/documents").resolve("alice");
            check("8a 默认 false: 裸名仍落 workDir/{userId}",
                    isOk(m4) && Files.isRegularFile(legacyWork.resolve("legacy-bare.docx")),
                    out(m4) + " | " + legacyWork);
            check("8b 默认 false: 不落 workspace", !Files.exists(ws.resolve("legacy-bare.docx")),
                    "ws=" + ws);

            // 9：legacy 身份解析——userId 只在 args 时取 args；只有 sessionId 时用 sessionId
            Path legacyDocs = oldRoot.resolve("data/documents");
            TLMsg mArgsUser = callIdentity(old, false, AI_P_USERID, "uargs",
                    "action", "create", "path", "args-user.docx", "content", "# AU");
            check("9a legacy: userId 只在 args → workDir/{userId}",
                    isOk(mArgsUser) && Files.isRegularFile(legacyDocs.resolve("uargs").resolve("args-user.docx")),
                    out(mArgsUser) + " | " + legacyDocs.resolve("uargs"));
            TLMsg mSysSid = callIdentity(old, true, AI_P_SESSIONID, "sargs",
                    "action", "create", "path", "sid-only.docx", "content", "# SO");
            check("9b legacy: 只有 sessionId → workDir/{sessionId}",
                    isOk(mSysSid) && Files.isRegularFile(legacyDocs.resolve("sargs").resolve("sid-only.docx")),
                    out(mSysSid) + " | " + legacyDocs.resolve("sargs"));
        } finally {
            if (oldProp == null) System.clearProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
            else System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, oldProp);
            if (wsBase != null) deleteRecursively(wsBase);
            if (outside != null) deleteRecursively(outside);
            if (oldRoot != null) deleteRecursively(oldRoot);
        }

        System.out.println("\n==== WordWorkspaceModeSelfTest: "
                + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }
}
