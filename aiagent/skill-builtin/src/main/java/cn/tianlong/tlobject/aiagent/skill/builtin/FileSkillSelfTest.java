package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * file_operation 技能自测（runnable main，无 JUnit——仓库自测惯例）。
 *
 * 运行：
 *   /d/maven/bin/mvn -q -pl aiagent/skill-builtin compile
 *   /d/maven/bin/mvn -q -pl aiagent/skill-builtin dependency:build-classpath -Dmdep.outputFile=cp.txt
 *   java -cp "aiagent/skill-builtin/target/classes;aiagent/common/target/classes;core/target/classes;$(cat aiagent/skill-builtin/cp.txt)" \
 *        cn.tianlong.tlobject.aiagent.skill.builtin.FileSkillSelfTest
 * 失败退出码 1。
 */
public class FileSkillSelfTest extends TLFileOperationSkill {

    private static int passed = 0, failed = 0;
    private static Path root;

    public FileSkillSelfTest() { super(); }

    @Override public void putLog(String content, LogLevel logLevel) { /* 静默：绕开未初始化工厂 */ }
    @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }

    static void check(String name, boolean cond, String extra) {
        if (cond) { passed++; System.out.println("PASS " + name); }
        else { failed++; System.out.println("FAIL " + name + " | " + extra); }
    }

    /** 组装一次 SKILLINPUT 调用 */
    static TLMsg call(FileSkillSelfTest s, Object... kv) {
        Map<String, Object> input = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) input.put((String) kv[i], kv[i + 1]);
        TLMsg m = new TLMsg();
        m.setParam(AI_P_SKILLINPUT, input);     // 常量继承自 TLAiAgentParamString（"skillInput"）
        return s.execute(null, m);
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

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("fileskilltest");
        FileSkillSelfTest s = new FileSkillSelfTest();
        s.skillName = "file_operation";
        s.params = new HashMap<>();
        s.params.put("allowedRootPath", root.toString());
        s.setModuleParams();

        // ---- 1. read 区间 ----
        StringBuilder big = new StringBuilder();
        for (int i = 1; i <= 120; i++) big.append("第").append(i).append("行内容\n");
        String bigText = big.toString();
        Files.write(root.resolve("big.txt"), bigText.getBytes(StandardCharsets.UTF_8));

        TLMsg r = call(s, "operation", "read", "path", "big.txt");
        check("1a 无 offset/limit=旧行为整文件", out(r).startsWith("第1行内容") && out(r).contains("第120行内容"), out(r).substring(0, Math.min(60, out(r).length())));

        r = call(s, "operation", "read", "path", "big.txt", "offset", 10, "limit", 5);
        String o = out(r);
        check("1b 区间读只回 5 行(10-14)", o.contains("第10行内容") && o.contains("第14行内容") && !o.contains("第15行内容"), o);
        check("1c 带行号前缀%5d 对齐协议", o.contains("   10| 第10行内容"), o);
        check("1d 尾注共 120 行+续读提示", o.contains("共 120 行") && o.contains("offset=15"), o);

        r = call(s, "operation", "read", "path", "big.txt", "offset", 500);
        check("1e 越界 offset 报错", out(r).contains("beyond end"), out(r));

        r = call(s, "operation", "read", "path", "nope.txt");
        check("1f 不存在文件报错", out(r).contains("File not found"), out(r));

        // 1g 非法 offset：显式报错，不静默回落整文件读
        r = call(s, "operation", "read", "path", "big.txt", "offset", "abc");
        check("1g 非法 offset(abc)显式报错", !isOk(r) && out(r).contains("offset must be"), out(r));

        // 1h 非法 limit / 非正数 offset
        r = call(s, "operation", "read", "path", "big.txt", "limit", "0");
        check("1h 非法 limit(0)显式报错", !isOk(r) && out(r).contains("limit must be"), out(r));
        r = call(s, "operation", "read", "path", "big.txt", "offset", "-3");
        check("1h 非法 offset(-3)显式报错", !isOk(r) && out(r).contains("offset must be"), out(r));

        // 1i CRLF 文件：只剥行尾 \r（行中间的 \r 属内容，不能动）。
        // 精确断言整行 entry：全局剥 \r 会得到 "    2| abcd\n"、完全不剥会得到 "    2| ab\rcd\r\n"，两者都过不了
        Files.write(root.resolve("crlf.txt"), "aa\r\nab\rcd\r\ncc\r\n".getBytes(StandardCharsets.UTF_8));
        r = call(s, "operation", "read", "path", "crlf.txt", "offset", 2, "limit", 1);
        o = out(r);
        check("1i CRLF 只剥行尾\\r 行中\\r保留", isOk(r) && o.contains("    2| ab\rcd\n") && o.contains("共 3 行"),
                o.replace("\r", "\\r").replace("\n", "\\n"));

        // 1j 空文件区间读
        Files.write(root.resolve("empty.txt"), new byte[0]);
        r = call(s, "operation", "read", "path", "empty.txt", "offset", 1);
        check("1j 空文件区间读报错", !isOk(r) && out(r).contains("total 0 lines"), out(r));

        // 1k 仅给 limit：从第 1 行起读
        r = call(s, "operation", "read", "path", "big.txt", "limit", 2);
        o = out(r);
        check("1k 仅 limit=2 从第 1 行起", isOk(r) && o.contains("    1| 第1行内容") && o.contains("第2行内容") && !o.contains("第3行内容"), o);

        // 1l 超 1MB 文件区间读：绕过 maxReadSize，但仍受 16MB 门禁
        StringBuilder huge = new StringBuilder();
        String row = "abcdefgh".repeat(5);                                  // 40 字符/行
        for (int i = 0; i < 30000; i++) huge.append(row).append('\n');      // ~1.23MB
        Files.write(root.resolve("huge.txt"), huge.toString().getBytes(StandardCharsets.UTF_8));
        r = call(s, "operation", "read", "path", "huge.txt", "offset", 1, "limit", 2);
        o = out(r);
        check("1l >1MB 文件区间读成功", isOk(r) && o.contains("    1| " + row) && o.contains("    2| " + row), o.substring(0, Math.min(80, o.length())));

        // 1m 单行超 4000 字符截断
        Files.write(root.resolve("longline.txt"), "a".repeat(5000).getBytes(StandardCharsets.UTF_8));
        r = call(s, "operation", "read", "path", "longline.txt", "offset", 1, "limit", 1);
        o = out(r);
        check("1m 单行截断出尾标", isOk(r) && o.contains("行内容截断") && o.length() < 5500, "len=" + o.length());

        // 1n 输出总长预算：600 行 × 100 字符，区间读不传 limit（默认 500 行）也放不下 → 提前截断 + 续读提示
        StringBuilder wide = new StringBuilder();
        String wrow = "0123456789".repeat(10);                              // 100 字符/行
        for (int i = 0; i < 600; i++) wide.append(wrow).append('\n');
        Files.write(root.resolve("wide.txt"), wide.toString().getBytes(StandardCharsets.UTF_8));
        r = call(s, "operation", "read", "path", "wide.txt", "offset", 1);
        o = out(r);
        check("1n 输出预算截断+续读提示", isOk(r) && o.contains("继续读用 offset=") && o.length() < 25000, "len=" + o.length());
        // 1n2 续读提示必须精确（shownTo+1）：解析尾注区间与 offset，再用它实际续读——首行行号须恰为该 offset（无缝、不重不漏）
        Matcher mRange = Pattern.compile("第 (\\d+)-(\\d+) 行").matcher(o);
        Matcher mCont = Pattern.compile("offset=(\\d+)").matcher(o);
        int contOffset = -1;
        if (mRange.find() && mCont.find()) {
            int shownTo = Integer.parseInt(mRange.group(2));
            int hint = Integer.parseInt(mCont.group(1));
            if (hint == shownTo + 1) contOffset = hint;
        }
        check("1n2 尾注 offset=末行+1", contOffset > 0, "len=" + o.length() + " parsedOffset=" + contOffset);
        r = call(s, "operation", "read", "path", "wide.txt", "offset", contOffset, "limit", 3);
        o = out(r);
        Matcher mLine = Pattern.compile("^\\s*(\\d+)\\| ").matcher(o);
        Integer firstLine = mLine.find() ? Integer.valueOf(Integer.parseInt(mLine.group(1))) : null;
        check("1n2b 续读首行行号恰为 offset", isOk(r) && firstLine != null && firstLine == contOffset,
                "first=" + firstLine + " want=" + contOffset);

        // 1o list 遇文件回退整读（旧语义）
        r = call(s, "operation", "list", "path", "big.txt");
        check("1o list 文件回退整读", isOk(r) && out(r).equals(bigText), out(r).substring(0, Math.min(60, out(r).length())));

        // ---- 2. edit 精确替换 ----
        // 顺序关键：不唯一断言必须最先（先做唯一替换会让 return 1 只剩一处、不唯一断言假红）
        Files.write(root.resolve("code.py"),
                "def f():\n    return 1\n\ndef g():\n    return 1\n".getBytes(StandardCharsets.UTF_8));

        r = call(s, "operation", "edit", "path", "code.py",
                "old_string", "return 1", "new_string", "return 9");
        check("2a 两处匹配不唯一报错", !isOk(r) && out(r).contains("not unique"), out(r));
        check("2a2 报错后文件未被改动", new String(Files.readAllBytes(root.resolve("code.py")),
                StandardCharsets.UTF_8).equals("def f():\n    return 1\n\ndef g():\n    return 1\n"), "file changed");

        r = call(s, "operation", "edit", "path", "code.py",
                "old_string", "return 1", "new_string", "return 9", "replace_all", true);
        check("2b replace_all 两处全替换", new String(Files.readAllBytes(root.resolve("code.py")),
                StandardCharsets.UTF_8).equals("def f():\n    return 9\n\ndef g():\n    return 9\n"), "replace_all failed");

        r = call(s, "operation", "edit", "path", "code.py",
                "old_string", "def f():\n    return 9", "new_string", "def f():\n    return 42");
        check("2c 唯一匹配替换成功", isOk(r) && out(r).contains("Edited"), out(r));
        check("2c2 只改了目标处", new String(Files.readAllBytes(root.resolve("code.py")),
                StandardCharsets.UTF_8).equals("def f():\n    return 42\n\ndef g():\n    return 9\n"), "content");

        r = call(s, "operation", "edit", "path", "code.py",
                "old_string", "不存在的文本", "new_string", "x");
        check("2d 未命中报错并提示先读", !isOk(r) && out(r).contains("not found"), out(r));

        // CRLF 保留：原文 CRLF → 编辑后仍 CRLF
        Files.write(root.resolve("crlf.txt"), "aa\r\nbb\r\ncc\r\n".getBytes(StandardCharsets.UTF_8));
        r = call(s, "operation", "edit", "path", "crlf.txt", "old_string", "bb", "new_string", "BB");
        String afterStr = new String(Files.readAllBytes(root.resolve("crlf.txt")), StandardCharsets.UTF_8);
        check("2e CRLF 保留", afterStr.equals("aa\r\nBB\r\ncc\r\n"), afterStr.replace("\r", "\\r").replace("\n", "\\n"));

        // 2f 非 UTF-8（GBK 字节）：严格解码拒改，文件字节纹丝不动
        byte[] gbkBytes = new byte[]{(byte) 0xD6, (byte) 0xD0, (byte) 0xCE, (byte) 0xC4};   // "中文" 的 GBK 编码（非法 UTF-8）
        Files.write(root.resolve("gbk.txt"), gbkBytes);
        r = call(s, "operation", "edit", "path", "gbk.txt", "old_string", "中文", "new_string", "x");
        check("2f 非UTF-8文件拒改", !isOk(r) && out(r).contains("not valid UTF-8"), out(r));
        check("2f2 非UTF-8文件字节未变", Arrays.equals(Files.readAllBytes(root.resolve("gbk.txt")), gbkBytes), "file changed");

        // 2g 混合行尾（CRLF + 裸 LF）拒改
        byte[] mixedBytes = "l1\nl2\r\nl3\n".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("mixed.txt"), mixedBytes);
        r = call(s, "operation", "edit", "path", "mixed.txt", "old_string", "l2", "new_string", "L2");
        check("2g 混合行尾拒改", !isOk(r) && out(r).contains("Mixed line endings"), out(r));
        check("2g2 混合行尾文件未变", Arrays.equals(Files.readAllBytes(root.resolve("mixed.txt")), mixedBytes), "file changed");

        // 2h 行尾-only 编辑（CRLF→LF，内容零变化）：不是替换成功，不写盘
        byte[] eolBytes = "aa\r\nbb\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("eolonly.txt"), eolBytes);
        r = call(s, "operation", "edit", "path", "eolonly.txt",
                "old_string", "\r\n", "new_string", "\n", "replace_all", true);
        check("2h 行尾-only编辑报无变化", !isOk(r) && out(r).contains("No change"), out(r));
        check("2h2 行尾-only未写盘", Arrays.equals(Files.readAllBytes(root.resolve("eolonly.txt")), eolBytes), "file changed");

        // 2i 字符串布尔 "true"（LLM 常见写法）：replace_all 必须生效
        Files.write(root.resolve("boolstr.txt"), "ab cd ab\n".getBytes(StandardCharsets.UTF_8));
        r = call(s, "operation", "edit", "path", "boolstr.txt",
                "old_string", "ab", "new_string", "AB", "replace_all", "true");
        check("2i 字符串\"true\"识别为真", isOk(r) && new String(Files.readAllBytes(root.resolve("boolstr.txt")),
                StandardCharsets.UTF_8).equals("AB cd AB\n"), out(r));

        // 2j backup=true：.bak 逐字节等于改前全文，目标文件为新内容；回执附 .bak 路径
        Files.write(root.resolve("bak.txt"), "v1 body\n".getBytes(StandardCharsets.UTF_8));
        r = call(s, "operation", "edit", "path", "bak.txt", "old_string", "v1", "new_string", "v2", "backup", true);
        check("2j backup生成.bak且为改前全文", isOk(r)
                && new String(Files.readAllBytes(root.resolve("bak.txt.bak")), StandardCharsets.UTF_8).equals("v1 body\n")
                && new String(Files.readAllBytes(root.resolve("bak.txt")), StandardCharsets.UTF_8).equals("v2 body\n"), out(r));
        check("2j2 成功回执附.bak路径", out(r).contains("bak.txt.bak"), out(r));
        // 2j3 backup 字符串 "true" 同样生效
        Files.write(root.resolve("bak2.txt"), "v1 body\n".getBytes(StandardCharsets.UTF_8));
        r = call(s, "operation", "edit", "path", "bak2.txt", "old_string", "v1", "new_string", "v2", "backup", "true");
        check("2j3 字符串backup生效", isOk(r) && Files.exists(root.resolve("bak2.txt.bak")), out(r));

        // 2k old_string 自带 CRLF（变异 M4 生存点）：CRLF 文件归一化双向命中
        Files.write(root.resolve("crlfk.txt"), "aa\r\nbb\r\ncc\r\n".getBytes(StandardCharsets.UTF_8));
        r = call(s, "operation", "edit", "path", "crlfk.txt",
                "old_string", "aa\r\nbb", "new_string", "aa\r\nXX");
        String crlfkAfter = new String(Files.readAllBytes(root.resolve("crlfk.txt")), StandardCharsets.UTF_8);
        check("2k old含CRLF命中", isOk(r) && crlfkAfter.equals("aa\r\nXX\r\ncc\r\n"),
                crlfkAfter.replace("\r", "\\r").replace("\n", "\\n"));

        // 2l new_string 自带 CRLF（变异 M5 生存点）：落盘不得出现 \r\r\n，行尾统一 CRLF
        Files.write(root.resolve("crlfl.txt"), "aa\r\nbb\r\ncc\r\n".getBytes(StandardCharsets.UTF_8));
        r = call(s, "operation", "edit", "path", "crlfl.txt",
                "old_string", "bb", "new_string", "XX\r\nYY");
        String crlflAfter = new String(Files.readAllBytes(root.resolve("crlfl.txt")), StandardCharsets.UTF_8);
        check("2l new含CRLF不产生\\r\\r\\n且行尾统一", isOk(r) && !crlflAfter.contains("\r\r\n")
                && crlflAfter.equals("aa\r\nXX\r\nYY\r\ncc\r\n"),
                crlflAfter.replace("\r", "\\r").replace("\n", "\\n"));

        // 2m 目录目标拒改（照抄 doRead 守卫）
        Files.createDirectories(root.resolve("subdir"));
        r = call(s, "operation", "edit", "path", "subdir", "old_string", "x", "new_string", "y");
        check("2m 目录目标报错", !isOk(r) && out(r).contains("directory"), out(r));

        // 2n edit 尺寸门禁：第二个实例 maxRangedReadSize=50，100 字节文件被挡（OOM 是 Error，catch 接不住）
        FileSkillSelfTest s2 = new FileSkillSelfTest();
        s2.skillName = "file_operation";
        s2.params = new HashMap<>();
        s2.params.put("allowedRootPath", root.toString());
        s2.params.put("maxRangedReadSize", "50");
        s2.setModuleParams();
        Files.write(root.resolve("size100.txt"), "x".repeat(100).getBytes(StandardCharsets.UTF_8));
        r = call(s2, "operation", "edit", "path", "size100.txt", "old_string", "x", "new_string", "y");
        check("2n 超尺寸门禁拒改", !isOk(r) && out(r).contains("too large"), out(r));
        check("2n2 超尺寸文件未动", new String(Files.readAllBytes(root.resolve("size100.txt")),
                StandardCharsets.UTF_8).equals("x".repeat(100)), "file changed");

        // 2o 未传 new_string：显式报错（不再默默当删除），文件不动
        Files.write(root.resolve("req.txt"), "keep me\n".getBytes(StandardCharsets.UTF_8));
        r = call(s, "operation", "edit", "path", "req.txt", "old_string", "keep me");
        check("2o 未传new_string报错且未动文件", !isOk(r) && out(r).contains("new_string is required")
                && new String(Files.readAllBytes(root.resolve("req.txt")), StandardCharsets.UTF_8).equals("keep me\n"), out(r));

        // 2p 显式空串 = 删除，回执标注
        Files.write(root.resolve("del.txt"), "gone text\nstay\n".getBytes(StandardCharsets.UTF_8));
        r = call(s, "operation", "edit", "path", "del.txt", "old_string", "gone text\n", "new_string", "");
        check("2p 空串删除并标注", isOk(r) && out(r).contains("（删除文本）")
                && new String(Files.readAllBytes(root.resolve("del.txt")), StandardCharsets.UTF_8).equals("stay\n"), out(r));

        // ---- 3. write 原子化 + backup ----
        r = call(s, "operation", "write", "path", "w.txt", "content", "v1");
        check("3a write 成功", out(r).contains("written"), out(r));
        r = call(s, "operation", "write", "path", "w.txt", "content", "v2", "backup", true);
        check("3b write+backup 生成 .bak 且内容=v1",
                Files.exists(root.resolve("w.txt.bak")) &&
                "v1".equals(new String(Files.readAllBytes(root.resolve("w.txt.bak")), StandardCharsets.UTF_8)), "bak missing");
        check("3b2 write+backup 回执附 backup: 标记", out(r).contains("backup:"), out(r));
        check("3c 新内容已写", "v2".equals(new String(Files.readAllBytes(root.resolve("w.txt")), StandardCharsets.UTF_8)), "v2 not written");

        // 3d 原子写无残渣：w 目录里除 w.txt(.bak) 外无 .edit*.tmp/.tmp 残留
        try (java.util.stream.Stream<Path> st = Files.list(root)) {
            long junk = st.filter(p -> p.getFileName().toString().contains(".edit"))
                          .filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
            check("3d 写后无 .edit*.tmp 残留", junk == 0, "junk=" + junk);
        }

        // 3e 字符串 "true" 的 backup 同样生效（parseBoolParam 接线验证）
        r = call(s, "operation", "write", "path", "w2.txt", "content", "a1");
        r = call(s, "operation", "write", "path", "w2.txt", "content", "a2", "backup", "true");
        check("3e 字符串 backup=true 生效（.bak 内容=a1）",
                Files.exists(root.resolve("w2.txt.bak")) &&
                "a1".equals(new String(Files.readAllBytes(root.resolve("w2.txt.bak")), StandardCharsets.UTF_8)), "string-true backup failed");

        // 3f 首次写入（目标不存在）+ backup=true：父目录自动创建、Files.exists 守卫生效（不得去备份不存在的文件）、无 .bak
        r = call(s, "operation", "write", "path", "newdir/deep/n.txt", "content", "x", "backup", true);
        check("3f 深层路径自动建目录且成功（新文件+backup 守卫生效）", isOk(r) && Files.exists(root.resolve("newdir/deep/n.txt")), out(r));
        check("3f2 首次写入无 .bak", !Files.exists(root.resolve("newdir/deep/n.txt.bak")), "unexpected bak");

        // 3g 目录目标拒写（与 read/edit 同一守卫语义），目录本体不受影响
        Files.createDirectories(root.resolve("wdir"));
        r = call(s, "operation", "write", "path", "wdir", "content", "x", "backup", true);
        check("3g 目录目标拒写且不产生 .bak", !isOk(r) && out(r).contains("directory")
                && Files.isDirectory(root.resolve("wdir")) && !Files.exists(root.resolve("wdir.bak")), out(r));

        // 3h 目标不存在 + backup=true：成功且不得虚报 backup（盘上留 stale .bak 时更要守——
        //    旧回执按".bak 是否存在于盘上"判断，stale .bak 会让回执谎称"本次已备份"）
        Files.write(root.resolve("stale.txt.bak"), "stale leftover\n".getBytes(StandardCharsets.UTF_8));
        r = call(s, "operation", "write", "path", "stale.txt", "content", "s1", "backup", true);
        check("3h 目标不存在(有stale .bak)不虚报 backup", isOk(r) && !out(r).contains("backup:")
                && "s1".equals(readIfExists(root.resolve("stale.txt"))), out(r));

        // 3i backup 单份快照覆盖语义：连写两次 backup，.bak 始终=上一版，目标=最新版（M5 生存点：backupFile 少 REPLACE_EXISTING 即红）
        r = call(s, "operation", "write", "path", "f.txt", "content", "f1");
        r = call(s, "operation", "write", "path", "f.txt", "content", "f2", "backup", true);
        boolean fBak1 = "f1".equals(readIfExists(root.resolve("f.txt.bak")));
        r = call(s, "operation", "write", "path", "f.txt", "content", "f3", "backup", true);
        check("3i backup 覆盖：.bak=f2 目标=f3", isOk(r) && fBak1
                && "f2".equals(readIfExists(root.resolve("f.txt.bak")))
                && "f3".equals(readIfExists(root.resolve("f.txt"))), out(r));

        // 3j: 目标被读句柄占用的写入——原子替换在 Windows 上必须失败且目标逐字节不变
        //（旧的直写实现此场景会成功写进去 → isOk 为真，因此该用例对"原子 vs 直写"有判别力）
        if (File.separatorChar == '\\') {
            try {
                Files.write(root.resolve("t.txt"), "orig".getBytes(StandardCharsets.UTF_8));
                try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                        root.resolve("t.txt"), java.nio.file.StandardOpenOption.READ)) {
                    r = call(s, "operation", "write", "path", "t.txt", "content", "changed");
                }
                check("3j 被占用的目标：原子替换失败且目标未变",
                        !isOk(r) && out(r).contains("not modified")
                        && "orig".equals(readIfExists(root.resolve("t.txt"))),
                        out(r));
            } catch (Exception e) {
                check("3j 原子性探针异常", false, e.toString());
            }
        } else {
            check("3j 原子性(非Windows跳过)", true, "non-Windows");
        }

        // 3k content 显式 JSON null（key 在、值为 null）：不得 NPE 把 JVM 内部文案泄给 LLM，按空串写
        r = call(s, "operation", "write", "path", "nullcontent.txt", "content", null);
        check("3k content 显式 null 按空串写", isOk(r) && "".equals(readIfExists(root.resolve("nullcontent.txt"))), out(r));

        // 2q（顺路收 Task2 复审 Minor）：edit 拒改（No change）时不得产生 .bak
        Files.write(root.resolve("nc.txt"), "same\r\n".getBytes(StandardCharsets.UTF_8));
        r = call(s, "operation", "edit", "path", "nc.txt", "old_string", "\r\n", "new_string", "\n",
                "replace_all", true, "backup", true);
        check("2q 拒改(No change)不产生 .bak", !isOk(r) && out(r).contains("No change") &&
                !Files.exists(root.resolve("nc.txt.bak")), out(r));

        // ---- 4. search/glob ----
        Files.createDirectories(root.resolve("src/sub"));
        Files.write(root.resolve("src/a.py"), "import os\nprint(1)\n".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("src/sub/b.py"), "print(2)\n".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("src/c.txt"), "print(3)\n".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("node_modules"));
        Files.write(root.resolve("node_modules/d.py"), "print(4)\n".getBytes(StandardCharsets.UTF_8));

        r = call(s, "operation", "search", "pattern", "print\\(\\d\\)");
        String so = out(r);
        check("4a search 命中多文件", so.contains("src/a.py:2") && so.contains("src/sub/b.py:1"), so);
        check("4b search 默认排除 node_modules", !so.contains("node_modules"), so);

        r = call(s, "operation", "search", "pattern", "print", "glob", "*.py");
        check("4c search glob 过滤只留 .py", out(r).contains("a.py") && !out(r).contains("c.txt"), out(r));

        r = call(s, "operation", "search", "pattern", "print", "max_results", 1);
        check("4d 命中上限提示", out(r).contains("已达上限") && out(r).contains("缩小 pattern/glob"), out(r));

        r = call(s, "operation", "glob", "pattern", "**/*.py");
        check("4e glob 相对路径列举", out(r).contains("src/a.py") && out(r).contains("src/sub/b.py"), out(r));

        // 4f .bak 被排除
        Files.write(root.resolve("src/a.py.bak"), "print(9)\n".getBytes(StandardCharsets.UTF_8));
        r = call(s, "operation", "search", "pattern", "print\\(9\\)");
        check("4f .bak 文件不进搜索结果", !out(r).contains(".bak"), out(r));

        // 4g 二进制（含 \0）跳过
        Files.write(root.resolve("src/bin.dat"), new byte[]{1, 2, 0, 3, 'p', 'r', 'i', 'n', 't'});
        r = call(s, "operation", "search", "pattern", "print");
        check("4g 二进制文件跳过", !out(r).contains("bin.dat"), out(r));

        // 4h glob 上限提示
        r = call(s, "operation", "glob", "pattern", "**/*.py", "max_results", 3);
        check("4h glob 上限提示", out(r).contains("已达上限"), out(r));

        // 4i 文件名式模式（*.py）也可用
        r = call(s, "operation", "glob", "pattern", "*.py");
        check("4i *.py 文件名式模式可用", out(r).contains("src/a.py"), out(r));

        // 4j 无命中
        r = call(s, "operation", "search", "pattern", "不存在的词xyz");
        check("4j 无命中提示", out(r).contains("无命中"), out(r));

        // 4k operation 显式 JSON null（key 在、值为 null）：不得 NPE——NPE 逃出 execute 经框架异常链
        //   （无 exceptionHandler 时 moduleFactory.shutdown(-1)）会 System.exit 整个应用；须走"未知操作"优雅报错
        try {
            r = call(s, "operation", null, "path", "big.txt");
            check("4k operation 显式 null 不抛异常且优雅报错", !isOk(r) && out(r).contains("Unknown operation"), out(r));
        } catch (Throwable e) {
            check("4k operation 显式 null 不抛异常且优雅报错", false, e.toString());
        }

        // 4l path 显式 JSON null：不得 NPE（同类崩应用路径），走 "path is required" 优雅报错
        try {
            r = call(s, "operation", "read", "path", null);
            check("4l path 显式 null 不抛异常且优雅报错", !isOk(r) && out(r).contains("path is required"), out(r));
        } catch (Throwable e) {
            check("4l path 显式 null 不抛异常且优雅报错", false, e.toString());
        }

        // 4m operation 传非字符串（Integer；Gson 把 JSON 数字解析为 Double，同族）：强转 (String) 会抛 CCE 逃出
        //   execute 经框架异常链 shutdown 整个应用；须 String.valueOf 字符串化后走"未知操作"优雅报错（带 path 以进到 switch）
        try {
            r = call(s, "operation", 123, "path", "big.txt");
            check("4m operation 非字符串不抛异常且优雅报错", !isOk(r) && out(r).contains("Unknown operation"), out(r));
        } catch (Throwable e) {
            check("4m operation 非字符串不抛异常且优雅报错", false, e.toString());
        }

        // 4n content 传非字符串（Integer）：字符串化后按既有写路径正常写入（LLM 发数字 content 的正确消化，不是崩溃）
        try {
            r = call(s, "operation", "write", "path", "num.txt", "content", 42);
            String numTxt = readIfExists(root.resolve("num.txt"));
            check("4n content 数字被字符串化后正常写入", isOk(r) && "42".equals(numTxt),
                    "ok=" + isOk(r) + ", file=" + numTxt + ", out=" + out(r));
        } catch (Throwable e) {
            check("4n content 数字被字符串化后正常写入", false, e.toString());
        }

        // 4o search pattern 传非字符串（Integer；真实链路 Gson 把 JSON 数字解析为 Double，同族）：强转 (String)
        //   的 CCE 虽被 execute 的 try 接住，但把 JVM 的 "cannot be cast" 文案泄给 LLM；字符串化后 "123" 是
        //   合法正则，正常返回（命中/无命中均可）
        try {
            r = call(s, "operation", "search", "pattern", 123);
            check("4o pattern 非字符串不抛异常且无 JVM 强转文案", !out(r).contains("cannot be cast"),
                    "ok=" + isOk(r) + ", out=" + out(r));
        } catch (Throwable e) {
            check("4o pattern 非字符串不抛异常且无 JVM 强转文案", false, e.toString());
        }

        // 4p edit old_string 传非字符串（Integer）：字符串化后 "123" 不命中 code.py → 优雅报错、不抛异常、
        //   不泄 JVM 强转文案，也不得改动文件
        try {
            r = call(s, "operation", "edit", "path", "code.py", "old_string", 123, "new_string", "x");
            check("4p old_string 非字符串不抛异常且优雅未命中",
                    !isOk(r) && !out(r).contains("cannot be cast"), out(r));
        } catch (Throwable e) {
            check("4p old_string 非字符串不抛异常且优雅未命中", false, e.toString());
        }

        // 4q 盘符相对路径（a:b.py）：WindowsPath.toAbsolutePath 抛 IOError（Error 族，catch(Exception) 接不住
        //    → 逃出 execute 经框架链 shutdown 整个应用）；须不抛异常 + !isOk 优雅报错。
        //    Windows 专属：非 Windows 上 "a:b.py" 只是普通文件名，没有这条触发路径
        if (File.separatorChar == '\\') {
            try {
                r = call(s, "operation", "read", "path", "a:b.py");
                check("4q 盘符相对路径不抛异常且优雅报错",
                        !isOk(r) && out(r).contains("File operation failed"), out(r));
            } catch (Throwable t) {
                check("4q 盘符相对路径不抛异常且优雅报错", false, t.toString());
            }
        } else {
            check("4q 盘符相对路径(非Windows跳过)", true, "non-Windows");
        }

        // 4r 病态正则 + 长行：逐行匹配爆栈（StackOverflowError 是 Error）必须被逐行消化——该行跳过、
        //    后续行继续、整次搜索 isOk，且发生过跳过要在输出里附提示。
        //    断言必须含"跳过"，不放宽为"跳过或无命中"——大栈环境 5000 字符可能不炸 SOE，
        //    宽容断言会恒绿、失去守护（本机 JDK 17 主线程实测 5000 字符稳定触发 SOE）
        Files.write(root.resolve("src/pathological.txt"), ("x".repeat(5000) + "\n").getBytes(StandardCharsets.UTF_8));
        try {
            r = call(s, "operation", "search", "pattern", "(x|x)*y");
            o = out(r);
            check("4r 病态正则长行不崩溃且附跳过提示",
                    isOk(r) && o.contains("跳过"), o);
        } catch (Throwable t) {
            // 变异（去掉逐行 catch）红在此：SOE 逃出 execute 被记 FAIL，不崩掉自测进程
            check("4r 病态正则长行不崩溃且附跳过提示", false, t.toString());
        }

        System.out.println("\n== " + passed + " passed, " + failed + " failed ==");
        deleteRecursively(root);
        System.exit(failed == 0 ? 0 : 1);
    }
}
