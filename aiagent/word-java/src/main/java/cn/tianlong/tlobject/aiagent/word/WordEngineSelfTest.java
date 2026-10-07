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

        // ================= WordTextExtractor =================
        org.apache.poi.xwpf.usermodel.XWPFDocument d1 = newDoc();
        addPara(d1, "项目报告", "Heading1");
        addPara(d1, "本报告统计了收入。", null);
        org.apache.poi.xwpf.usermodel.XWPFTable t1 = d1.createTable(2, 2);
        t1.getRow(0).getCell(0).setText("月份");
        t1.getRow(0).getCell(1).setText("收入");
        t1.getRow(1).getCell(0).setText("1月");
        t1.getRow(1).getCell(1).setText("100");

        String txt = WordTextExtractor.read(d1, 0, -1);
        check("read: 含段落号 [0] 与样式 Heading1", txt.contains("[0] [Heading1] 项目报告"));
        check("read: 普通段落标注 Normal", txt.contains("[1] [Normal]"));
        check("read: 内嵌表格标记", txt.contains("[表格 0]"));
        check("read: 表格单元格内容入文本", txt.contains("月份") && txt.contains("100"));
        check("read: from/to 切片只取第二段",
                WordTextExtractor.read(d1, 1, 1).contains("[1] ")
                        && !WordTextExtractor.read(d1, 1, 1).contains("项目报告"));

        String ol = WordTextExtractor.outline(d1);
        check("outline: 只出标题", ol.contains("[0] 项目报告") && !ol.contains("本报告统计"));

        // 不写 pStyle、只靠 outlineLvl 表示标题的文档（read 与 outline 必须一致认它）
        org.apache.poi.xwpf.usermodel.XWPFDocument d2 = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFParagraph op = d2.createParagraph();
        op.getCTP().addNewPPr().addNewOutlineLvl().setVal(java.math.BigInteger.valueOf(0));
        op.createRun().setText("靠 outlineLvl 的标题");
        addPara(d2, "正文", null);
        check("outline: 认 outlineLvl 标题（与 read 的 [Heading1] 一致）",
                WordTextExtractor.outline(d2).contains("[0] 靠 outlineLvl 的标题")
                        && WordTextExtractor.read(d2, 0, 0).contains("[0] [Heading1]")
                        && Integer.valueOf(1).equals(WordTextExtractor.info(d2).get("headings")));

        String tb = WordTextExtractor.tables(d1, -1);
        check("tables: 二维内容", tb.contains("月份") && tb.contains("1月"));

        java.util.Map<String, Object> inf = WordTextExtractor.info(d1);
        check("info: 段落数 = 2", Integer.valueOf(2).equals(inf.get("paragraphs")));
        check("info: 表格数 = 1", Integer.valueOf(1).equals(inf.get("tables")));
        check("info: 标题数 = 1", Integer.valueOf(1).equals(inf.get("headings")));

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ================= 共用的造文档工具 =================

    /** 用一个全新文档跑 body，返回它 */
    static org.apache.poi.xwpf.usermodel.XWPFDocument newDoc() {
        return new org.apache.poi.xwpf.usermodel.XWPFDocument();
    }

    static org.apache.poi.xwpf.usermodel.XWPFParagraph addPara(
            org.apache.poi.xwpf.usermodel.XWPFDocument d, String text, String style) {
        org.apache.poi.xwpf.usermodel.XWPFParagraph p = d.createParagraph();
        if (style != null) p.setStyle(style);
        org.apache.poi.xwpf.usermodel.XWPFRun r = p.createRun();
        r.setText(text);
        return p;
    }

    static java.nio.file.Path saveTmp(org.apache.poi.xwpf.usermodel.XWPFDocument d, String name)
            throws Exception {
        java.nio.file.Path p = java.nio.file.Files.createTempDirectory("wordsf")
                .resolve(name);
        try (java.io.OutputStream o = java.nio.file.Files.newOutputStream(p)) { d.write(o); }
        return p;
    }

    static void check(String desc, boolean ok) {
        if (ok) { passed++; System.out.println("  [PASS] " + desc); }
        else    { failed++; System.out.println("  [FAIL] " + desc); }
    }
}
