package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * todo 技能自测（runnable main，无 JUnit——仓库自测惯例）。
 *
 * 运行：
 *   /d/maven/bin/mvn -q -pl aiagent/skill-builtin -am compile
 *   /d/maven/bin/mvn -q -pl aiagent/skill-builtin dependency:build-classpath -Dmdep.outputFile=cp.txt
 *   java -cp "aiagent/skill-builtin/target/classes;aiagent/common/target/classes;core/target/classes;$(cat aiagent/skill-builtin/cp.txt)" \
 *        cn.tianlong.tlobject.aiagent.skill.builtin.TodoSkillSelfTest
 * 失败退出码 1。
 *
 * 变异探针（仓库外副本，Windows 绝对路径 classpath，javap/-Xlog 证实类来源）：
 *   ① msgBus 守卫块（getModuleInFactory + instanceof + try/catch）整体换成裸 putMsg("msgBus", evt)
 *      → 用例 11 变红（无工厂环境 NPE 逃出，write 被 execute 顶层兜底转 err）；
 *   ② 去掉状态归一（normalizeStatus 直接返回原值）→ 用例 4 变红；
 *   ③ 去掉 item.text 换行归一（parseItems 直接用原文）→ 用例 17 变红（写读同构破裂：3 项写进去 4 项读回来）；
 *   ④ 去掉短哈希行（sanitizeId 里 !r.equals(s) 分支）→ 用例 18a/18b/19 变红（全非法字符 id 塌缩同名）。
 */
public class TodoSkillSelfTest extends TLTodoSkill {

    private static int passed = 0, failed = 0;
    private static Path root;       // 临时根（含 tempData，测后递归清理）
    private static Path tempData;   // dataDir 覆写目标

    private static final String SID = "s1";
    private static final String UID = "u1";

    public TodoSkillSelfTest() { super(); }

    @Override public void putLog(String content, LogLevel logLevel) { /* 静默：绕开未初始化工厂 */ }
    @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }

    static void check(String name, boolean cond, String extra) {
        if (cond) { passed++; System.out.println("PASS " + name); }
        else { failed++; System.out.println("FAIL " + name + " | " + extra); }
    }

    /** call 助手：input 装进 AI_P_SKILLINPUT；sessionId/userId 走 systemArgs（执行器同款通道） */
    static TLMsg call(TodoSkillSelfTest s, String sid, String uid, Object... kv) {
        Map<String, Object> input = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) input.put((String) kv[i], kv[i + 1]);
        TLMsg m = new TLMsg();
        m.setParam(AI_P_SKILLINPUT, input);
        m.setSystemParam(AI_P_SESSIONID, sid);
        m.setSystemParam("userId", uid);
        return s.execute(null, m);
    }

    static Map<String, Object> item(String text, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text);
        m.put("status", status);
        return m;
    }

    static String out(TLMsg r) { return r.getStringParam(AI_P_SKILLOUTPUT, ""); }

    /** RESULT 参数为 true 才判成功 */
    static boolean isOk(TLMsg r) { return r.parseBoolean(RESULT, false); }

    /** 读文件内容；不存在/读失败返回 null（让断言干净报 FAIL，而不是抛异常中断整个自测） */
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

    private static Path taskFile(String uid, String sid) {
        return tempData.resolve(uid).resolve("tasks").resolve(sid + ".md");
    }

    /** tasks 目录下是否有匹配 glob 的残留（原子写临时文件检查用） */
    static boolean hasGlobMatch(Path dir, String glob) {
        if (!Files.isDirectory(dir)) return false;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
            return ds.iterator().hasNext();
        } catch (Exception e) {
            return true;    // 打不开目录按"有残留"报，不静默放行
        }
    }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("todoskilltest");
        tempData = root.resolve("tempData");

        TodoSkillSelfTest s = new TodoSkillSelfTest();
        s.skillName = "todo";
        s.params = new HashMap<>();
        s.params.put("dataDir", tempData.toString());
        s.setModuleParams();

        // ---- 1. write 3 项（各一状态）：回执含计数头 + 三种标记 ----
        List<Object> three = new ArrayList<>(Arrays.asList(
                item("第一项", "in_progress"),
                item("第二项", "completed"),
                item("第三项", "pending")));
        TLMsg r = call(s, SID, UID, "operation", "write", "items", three);
        String writeOut = out(r);
        check("1a write 成功", isOk(r), writeOut);
        check("1b 回执含计数头", writeOut.contains("任务清单（3 项") && writeOut.contains("1 进行中")
                && writeOut.contains("1 完成") && writeOut.contains("1 待执行"), writeOut);
        check("1c 计数头格式精确", writeOut.split("\n", 2)[0].equals("任务清单（3 项：1 进行中 / 1 完成 / 1 待执行）："),
                writeOut.split("\n", 2)[0]);
        check("1d 三种标记齐（[~]/[x]/[ ]）",
                writeOut.contains("- [~] 第一项") && writeOut.contains("- [x] 第二项") && writeOut.contains("- [ ] 第三项"),
                writeOut);

        // ---- 2. 文件落盘：tempData/{uid}/tasks/{sid}.md，含 "- [" 行与可选头 ----
        Path f = taskFile(UID, SID);
        String fileText = readIfExists(f);
        check("2a 文件落盘且含 - [ 行", fileText != null && fileText.contains("- [~] 第一项")
                && fileText.contains("- [x] 第二项") && fileText.contains("- [ ] 第三项"), "file=" + f);
        check("2b 文件头含 sessionId/userId", fileText != null && fileText.startsWith("# 任务清单（sessionId=" + SID
                + ", userId=" + UID + "）"), fileText == null ? "null" : fileText.split("\n", 2)[0]);
        check("2c 原子写无 .todo 临时残留", !hasGlobMatch(f.getParent(), ".todo*.tmp"), "tmp residue in " + f.getParent());

        // ---- 3. read 与 write 回执同构（渲染文本一字不差） ----
        r = call(s, SID, UID, "operation", "read");
        check("3 read 与 write 回执同构", isOk(r) && out(r).equals(writeOut), out(r));

        // ---- 4. 状态归一：IN PROGRESS→[~]、DONE/complete/x→[x]、failed→[✗]、乱值→[ ] ----
        List<Object> mixed = new ArrayList<>(Arrays.asList(
                item("归一甲", "IN PROGRESS"),
                item("归一乙", "DONE"),
                item("归一丙", "complete"),
                item("归一丁", "x"),
                item("归一戊", "failed"),
                item("归一己", "随便什么")));
        r = call(s, SID, UID, "operation", "write", "items", mixed);
        String mo = out(r);
        check("4a 归一标记", isOk(r) && mo.contains("- [~] 归一甲") && mo.contains("- [x] 归一乙")
                && mo.contains("- [x] 归一丙") && mo.contains("- [x] 归一丁") && mo.contains("- [✗] 归一戊")
                && mo.contains("- [ ] 归一己"), mo);
        check("4b 归一计数", mo.contains("6 项：1 进行中 / 3 完成 / 1 待执行 / 1 失败"), mo);

        // ---- 5. 裸 String items → 全 pending ----
        r = call(s, SID, UID, "operation", "write", "items", Arrays.asList("光秃秃甲", "光秃秃乙"));
        check("5 裸串 items 全 pending", isOk(r) && out(r).contains("- [ ] 光秃秃甲")
                && out(r).contains("- [ ] 光秃秃乙") && out(r).contains("2 待执行"), out(r));

        // ---- 6. 二次 write 全量覆盖（旧项消失） ----
        r = call(s, SID, UID, "operation", "write", "items", Arrays.asList(item("独苗", "pending")));
        String after = readIfExists(f);
        check("6 全量覆盖旧项消失", isOk(r) && !out(r).contains("光秃秃")
                && after != null && !after.contains("光秃秃") && after.contains("独苗"), "file=" + after);

        // ---- 7. items=null → 优雅报错且点名 items ----
        r = call(s, SID, UID, "operation", "write", "items", null);
        check("7 items null 报错点名 items", !isOk(r) && out(r).contains("items"), out(r));

        // ---- 8. items=123（数字）→ 优雅报错不抛 ----
        try {
            r = call(s, SID, UID, "operation", "write", "items", 123);
            check("8 items 数字优雅报错不抛", !isOk(r) && out(r).contains("items"), out(r));
        } catch (Throwable t) {
            check("8 items 数字优雅报错不抛", false, t.toString());
        }

        // ---- 9. 未知 / 缺省 operation → 优雅报错 ----
        try {
            r = call(s, SID, UID, "operation", "frobnicate", "items", Arrays.asList("x"));
            check("9a 未知 operation 优雅报错", !isOk(r) && out(r).contains("Unknown operation"), out(r));
        } catch (Throwable t) {
            check("9a 未知 operation 优雅报错", false, t.toString());
        }
        try {
            r = call(s, SID, UID, "items", Arrays.asList("x"));
            check("9b 缺省 operation 优雅报错", !isOk(r) && out(r).contains("operation is required"), out(r));
        } catch (Throwable t) {
            check("9b 缺省 operation 优雅报错", false, t.toString());
        }

        // ---- 10. 缺 sessionId/userId systemParam → 落 default/default 路径且不抛 ----
        try {
            r = call(s, null, null, "operation", "write", "items", Arrays.asList("缺省会话项"));
            check("10 缺省会话标识落 default 路径", isOk(r)
                    && Files.exists(tempData.resolve("default").resolve("tasks").resolve("default.md")), out(r));
        } catch (Throwable t) {
            check("10 缺省会话标识落 default 路径", false, t.toString());
        }

        // ---- 11. msgBus 缺失（本测试无工厂）→ write 正常成功不抛（防"杀应用"守卫回归） ----
        // 守卫：getModuleInFactory + instanceof；裸按名 putMsg 在真实应用里模块缺失会 shutdown(-1)，
        // 在无工厂环境则以 NPE 形态逃出——两者都会让本用例红
        try {
            r = call(s, "mb-session", UID, "operation", "write", "items", Arrays.asList(item("守卫项", "pending")));
            check("11 无 msgBus/无工厂 write 仍成功不抛", isOk(r) && out(r).contains("守卫项"), out(r));
        } catch (Throwable t) {
            check("11 无 msgBus/无工厂 write 仍成功不抛", false, "threw: " + t);
        }

        // ---- 12. 文件名净化：sessionId="a/b..\c" → 无路径分隔符、不越界建目录（净化名含短哈希） ----
        String chaos = "a/b..\\c";
        r = call(s, chaos, UID, "operation", "write", "items", Arrays.asList("净化项"));
        Path tasksDir = tempData.resolve(UID).resolve("tasks");
        Path safe = tasksDir.resolve(sanitizeId(chaos, "default") + ".md");
        check("12a 净化落盘（分隔符→_ + 短哈希）", isOk(r) && Files.exists(safe) && safe.getParent().equals(tasksDir), out(r));
        check("12b 未越界建 a/ 目录", !Files.exists(tasksDir.resolve("a")), "tasks/ 下多了 a/ 子目录");
        check("12c 旧无哈希名不再落盘", !Files.exists(tasksDir.resolve("a_b.._c.md")), "旧名 a_b.._c.md 仍存在");
        r = call(s, chaos, UID, "operation", "read");
        check("12d 混沌 id 读回自身清单", isOk(r) && out(r).contains("净化项"), out(r));

        // ---- 13. read 不存在 → 含"无任务清单" ----
        r = call(s, "never-written", UID, "operation", "read");
        check("13 read 不存在提示无清单", isOk(r) && out(r).contains("无任务清单"), out(r));

        // ---- 14. 元素乱入消化：数字元素当 text、pending（字符串化，不抛） ----
        try {
            List<Object> chaosItems = new ArrayList<>(Arrays.asList(123, item("正常项", "completed")));
            r = call(s, "mix-el", UID, "operation", "write", "items", chaosItems);
            check("14 数字元素消化为 text+pending", isOk(r) && out(r).contains("- [ ] 123")
                    && out(r).contains("- [x] 正常项"), out(r));
        } catch (Throwable t) {
            check("14 数字元素消化为 text+pending", false, t.toString());
        }

        // ---- 15. 旧式平铺调用兼容（无 skillInput 包装） ----
        try {
            TLMsg flat = new TLMsg();
            flat.setParam("operation", "read");
            flat.setSystemParam(AI_P_SESSIONID, SID);
            flat.setSystemParam("userId", UID);
            r = s.execute(null, flat);
            check("15 平铺旧式调用兼容", isOk(r) && out(r).contains("独苗"), out(r));
        } catch (Throwable t) {
            check("15 平铺旧式调用兼容", false, t.toString());
        }

        // ---- 16. operation 显式 JSON null / 非字符串 → 不抛、优雅报错 ----
        try {
            r = call(s, SID, UID, "operation", null, "items", Arrays.asList("x"));
            check("16a operation 显式 null 优雅报错", !isOk(r) && out(r).contains("operation is required"), out(r));
            r = call(s, SID, UID, "operation", 123, "items", Arrays.asList("x"));
            check("16b operation 数字优雅报错", !isOk(r) && out(r).contains("Unknown operation"), out(r));
        } catch (Throwable t) {
            check("16c operation 乱入不抛", false, t.toString());
        }

        // ---- 17. item.text 含换行：归一空格，写读同构（伪造成完成项的第二行不得变成独立项） ----
        List<Object> nlItems = new ArrayList<>(Arrays.asList(
                item("第一行\n- [x] 伪造完成项", "pending"),
                item("正常项", "pending")));
        r = call(s, "nlsess", UID, "operation", "write", "items", nlItems);
        String nlOut = out(r);
        check("17a 换行归一为空格（回执单行化）", isOk(r)
                && nlOut.contains("- [ ] 第一行 - [x] 伪造完成项"), nlOut);
        boolean forgedAlone = false;
        for (String line : nlOut.split("\n")) if (line.trim().equals("- [x] 伪造完成项")) forgedAlone = true;
        check("17b 项数不增（只 2 个项行）且伪造行不独立成项",
                nlOut.lines().filter(l -> l.startsWith("- [")).count() == 2 && !forgedAlone, nlOut);
        r = call(s, "nlsess", UID, "operation", "read");
        check("17c 写读同构（read 回执 == write 回执，3 项写入 3 项读回）", isOk(r) && out(r).equals(nlOut), out(r));
        // \r 单独出现、归一后全空（占位）、首尾空白 trim——都要同构
        r = call(s, "nlsess2", UID, "operation", "write", "items",
                Arrays.asList(item("甲\r乙", "pending"), item("\r\n", "pending"), item("  尾空白  ", "completed")));
        String nlOut2 = out(r);
        check("17d \\r 归一 + 空文本占位 + trim", isOk(r) && nlOut2.contains("- [ ] 甲 乙")
                && nlOut2.contains("- [ ] (未命名任务)") && nlOut2.contains("- [x] 尾空白"), nlOut2);
        r = call(s, "nlsess2", UID, "operation", "read");
        check("17e 17d 写读同构", isOk(r) && out(r).equals(nlOut2), out(r));

        // ---- 18. sanitizeId 单元断言（塌缩/Windows 别名/保留名/干净 id 不加哈希） ----
        check("18a 全非法字符不塌缩（用户甲 != 用户乙）",
                !sanitizeId("用户甲", "default").equals(sanitizeId("用户乙", "default")),
                sanitizeId("用户甲", "default") + " vs " + sanitizeId("用户乙", "default"));
        check("18b 尾点别名不撞（aliasA. != aliasA）",
                !sanitizeId("aliasA.", "default").equals(sanitizeId("aliasA", "default")),
                sanitizeId("aliasA.", "default") + " vs " + sanitizeId("aliasA", "default"));
        check("18c 保留名 CON → _CON_<hash>", sanitizeId("CON", "default").startsWith("_CON_"),
                sanitizeId("CON", "default"));
        check("18d 干净 ASCII id 不加哈希（旧文件名不变）",
                sanitizeId("webchat_admin_1", "default").equals("webchat_admin_1")
                        && sanitizeId("s1", "default").equals("s1"),
                sanitizeId("webchat_admin_1", "default"));
        check("18e 空值/全空白 → def（def 自身也过净化）", sanitizeId(null, "default").equals("default")
                && sanitizeId("  ", "default").equals("default") && sanitizeId("", "x-def").equals("x-def"), "");
        check("18f 首尾点替换（. / .. 不以点开头结尾）",
                !sanitizeId(".", "default").startsWith(".") && !sanitizeId("..", "default").endsWith("."),
                sanitizeId(".", "default") + " / " + sanitizeId("..", "default"));

        // ---- 19. 碰撞行为：用户甲/用户乙 各自独立落盘，互读不串 ----
        r = call(s, "用户甲", UID, "operation", "write", "items", Arrays.asList("甲项"));
        check("19a 用户甲 write 成功", isOk(r), out(r));
        r = call(s, "用户乙", UID, "operation", "read");
        check("19b 用户乙 读不到 用户甲（无塌缩串读）", isOk(r) && out(r).contains("无任务清单"), out(r));
        r = call(s, "用户乙", UID, "operation", "write", "items", Arrays.asList("乙项"));
        check("19c 用户乙 write 成功", isOk(r), out(r));
        r = call(s, "用户甲", UID, "operation", "read");
        check("19d 乙写后甲仍读回甲项（双向不串）", isOk(r) && out(r).contains("甲项") && !out(r).contains("乙项"), out(r));

        // ---- 20. Windows 尾点别名：aliasA. 与 aliasA 落不同文件，各自读回 ----
        r = call(s, "aliasA.", UID, "operation", "write", "items", Arrays.asList("带点项"));
        check("20a aliasA. write 成功", isOk(r), out(r));
        r = call(s, "aliasA", UID, "operation", "write", "items", Arrays.asList("无点项"));
        check("20b aliasA write 成功", isOk(r), out(r));
        Path dotFile = tasksDir.resolve(sanitizeId("aliasA.", "default") + ".md");
        Path plainFile = tasksDir.resolve(sanitizeId("aliasA", "default") + ".md");
        check("20c 两 id 落不同文件且都在", !dotFile.equals(plainFile)
                && Files.exists(dotFile) && Files.exists(plainFile), dotFile + " / " + plainFile);
        r = call(s, "aliasA.", UID, "operation", "read");
        check("20d aliasA. 读回带点项", isOk(r) && out(r).contains("带点项") && !out(r).contains("无点项"), out(r));
        r = call(s, "aliasA", UID, "operation", "read");
        check("20e aliasA 读回无点项", isOk(r) && out(r).contains("无点项") && !out(r).contains("带点项"), out(r));

        // ---- 21. Windows 保留名：CON 不再优雅失败，落 _CON_<hash> 正常写读 ----
        r = call(s, "CON", UID, "operation", "write", "items", Arrays.asList("保留名项"));
        check("21a CON write 成功", isOk(r), out(r));
        Path conFile = tasksDir.resolve(sanitizeId("CON", "default") + ".md");
        check("21b 落在 _CON_<hash> 文件", conFile.getFileName().toString().startsWith("_CON_")
                && Files.exists(conFile), "file=" + conFile);
        r = call(s, "CON", UID, "operation", "read");
        check("21c CON 读回自身清单", isOk(r) && out(r).contains("保留名项"), out(r));

        System.out.println("\n== " + passed + " passed, " + failed + " failed ==");
        deleteRecursively(root);
        System.exit(failed == 0 ? 0 : 1);
    }
}
