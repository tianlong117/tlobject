package cn.tianlong.tlobject.aiagent.word;

/**
 * word 引擎自测（runnable main，无 JUnit——与仓库自测惯例一致）。
 *
 * 运行：
 *   /d/maven/bin/mvn -q -pl aiagent/word-java dependency:build-classpath -Dmdep.outputFile=cp.txt
 *   java -cp "aiagent/word-java/target/classes;$(cat aiagent/word-java/cp.txt)" \
 *        cn.tianlong.tlobject.aiagent.word.WordEngineSelfTest
 * 失败以退出码 1 结束。
 */
public class WordEngineSelfTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        check("骨架可实例化", true);

        // ================= WordMarkdown 解析 =================
        java.util.List<WordMarkdown.Block> bl =
                WordMarkdown.parse("# 标题\n\n正文一段\n\n- 甲\n- 乙\n\n| 月 | 值 |\n| 1 | 100 |\n\n---\n\n**粗体**开头的段落");
        // 7 块：# 标题 / 正文一段 / -甲 / -乙 / 表格 / --- / **粗体**开头
        check("markdown: 块数量 = 7", bl.size() == 7);
        check("markdown: # → HEADING level1",
                bl.get(0).type == WordMarkdown.Block.Type.HEADING && bl.get(0).level == 1);
        check("markdown: 标题文本", "标题".equals(bl.get(0).spans.get(0).text));
        check("markdown: 普通段落",
                bl.get(1).type == WordMarkdown.Block.Type.PARAGRAPH
                        && "正文一段".equals(bl.get(1).spans.get(0).text));
        check("markdown: - → BULLET 两条", bl.get(2).type == WordMarkdown.Block.Type.BULLET
                && "甲".equals(bl.get(2).spans.get(0).text));
        check("markdown: - 的第二条", "乙".equals(bl.get(3).spans.get(0).text));
        check("markdown: 表格 2行2列",
                bl.get(4).type == WordMarkdown.Block.Type.TABLE
                        && bl.get(4).rows.size() == 2 && bl.get(4).rows.get(0).size() == 2);
        check("markdown: 表格内容", "月".equals(bl.get(4).rows.get(0).get(0))
                && "100".equals(bl.get(4).rows.get(1).get(1)));
        check("markdown: --- → PAGEBREAK", bl.get(5).type == WordMarkdown.Block.Type.PAGEBREAK);
        check("markdown: 第 7 块是粗体段落，首 span 为粗",
                bl.get(6).type == WordMarkdown.Block.Type.PARAGRAPH
                        && bl.get(6).spans.get(0).bold
                        && "粗体".equals(bl.get(6).spans.get(0).text));
        check("markdown: 粗体后的普通文字不粗",
                !WordMarkdown.parse("**粗体**普通").get(0).spans.get(1).bold);
        check("markdown: 多级标题 ###### → level 6",
                WordMarkdown.parse("###### 深").get(0).level == 6);
        check("markdown: 有序列表 1. → NUMBERED",
                WordMarkdown.parse("1. 首项").get(0).type == WordMarkdown.Block.Type.NUMBERED);

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void check(String desc, boolean ok) {
        if (ok) { passed++; System.out.println("  [PASS] " + desc); }
        else    { failed++; System.out.println("  [FAIL] " + desc); }
    }
}
