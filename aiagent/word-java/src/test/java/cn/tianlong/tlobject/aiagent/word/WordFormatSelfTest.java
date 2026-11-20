package cn.tianlong.tlobject.aiagent.word;

import org.apache.poi.xwpf.usermodel.LineSpacingRule;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.xmlbeans.XmlCursor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * word 格式动作自测（runnable main，无 JUnit）。
 * 覆盖：format 显式字段/部分字段、copy_format（段级+run 级/目标统一/文本不变/源不变）、
 * set_page cm→twips/缺省保留、append heading/body 来源、sectPr 顺序、insert matchFormatFrom、
 * 非法来源不落盘、技能 schema 接线。
 */
public class WordFormatSelfTest {
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("wordfmt");
        JavaWordEngine.Config cfg = new JavaWordEngine.Config();
        cfg.allowedRoot = dir;
        cfg.workDir = dir;
        JavaWordEngine eng = new JavaWordEngine(cfg);

        // 造带直接格式的文档：p0 标题 22pt 粗居中；p1 诗题 16pt 粗居中；p2 正文 12pt 宋体 1.5/2字符；p3 混合 run
        XWPFDocument d = new XWPFDocument();
        XWPFParagraph p0 = d.createParagraph();
        p0.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun r0 = p0.createRun();
        r0.setText("爱情诗两首"); r0.setBold(true); r0.setFontSize(22);
        r0.setFontFamily("宋体", XWPFRun.FontCharRange.eastAsia);

        XWPFParagraph p1 = d.createParagraph();
        p1.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun r1 = p1.createRun();
        r1.setText("《潮信》"); r1.setBold(true); r1.setFontSize(16);
        r1.setFontFamily("宋体", XWPFRun.FontCharRange.eastAsia);

        XWPFParagraph p2 = d.createParagraph();
        p2.setSpacingBetween(1.5, LineSpacingRule.AUTO);
        WordFormatter.setFirstLineChars(p2, 2);
        XWPFRun r2 = p2.createRun();
        r2.setText("我们之间隔着一片海"); r2.setFontSize(12);
        r2.setFontFamily("宋体", XWPFRun.FontCharRange.eastAsia);

        XWPFParagraph p3 = d.createParagraph();
        XWPFRun r3a = p3.createRun(); r3a.setText("局部加粗"); r3a.setBold(true); r3a.setFontSize(14);
        XWPFRun r3b = p3.createRun(); r3b.setText("普通"); r3b.setFontSize(10);

        WordFormatter.setMargins(d, 2.54, 2.54, 2.54, 2.54);
        Path path = dir.resolve("fmt.docx");
        try (var o = Files.newOutputStream(path)) { d.write(o); }
        d.close();

        // ---- format：显式/部分字段 ----
        JavaWordEngine.Result rf = eng.execute("format", map(
                "path", "fmt.docx", "from", 2, "to", 2,
                "fontName", "楷体", "fontSize", 11, "bold", true, "italic", true,
                "alignment", "right", "lineSpacing", 2.0,
                "firstLineIndentChars", 1, "spaceBefore", 6, "spaceAfter", 3));
        check("format ok", rf.ok);
        try (XWPFDocument x = open(path)) {
            XWPFParagraph q = x.getParagraphs().get(2);
            XWPFRun run = q.getRuns().get(0);
            check("format run 字体/字号/加粗/斜体",
                    "楷体".equals(run.getFontFamily()) && run.getFontSize() == 11
                            && run.isBold() && run.isItalic());
            check("format 对齐/行距/缩进/段前后",
                    q.getAlignment() == ParagraphAlignment.RIGHT
                            && Math.abs(q.getSpacingBetween() - 2.0) < 0.01
                            && q.getIndentationFirstLine() == 210
                            && q.getSpacingBefore() == 120 && q.getSpacingAfter() == 60);
            check("format 未传字段保持（标题仍 22pt）",
                    x.getParagraphs().get(0).getRuns().get(0).getFontSize() == 22);
        }
        JavaWordEngine.Result rfPart = eng.execute("format", map(
                "path", "fmt.docx", "from", 3, "fontSize", 13));
        check("format 部分字段只改字号", rfPart.ok);
        try (XWPFDocument x = open(path)) {
            XWPFParagraph q = x.getParagraphs().get(3);
            check("format 部分字段保留局部加粗",
                    q.getRuns().get(0).getFontSize() == 13 && q.getRuns().get(0).isBold()
                            && q.getRuns().get(1).getFontSize() == 13 && !q.getRuns().get(1).isBold());
        }

        // ---- copy_format：源 p2 → p0；源 p2 → p3 混合 run ----
        JavaWordEngine.Result rc = eng.execute("copy_format", map(
                "path", "fmt.docx", "source", 2, "from", 0, "to", 0));
        check("copy_format ok", rc.ok);
        try (XWPFDocument x = open(path)) {
            XWPFParagraph q = x.getParagraphs().get(0);
            XWPFRun run = q.getRuns().get(0);
            check("copy_format 段级/run 级",
                    q.getAlignment() == ParagraphAlignment.RIGHT
                            && Math.abs(q.getSpacingBetween() - 2.0) < 0.01
                            && q.getIndentationFirstLine() == 210
                            && "楷体".equals(run.getFontFamily()) && run.getFontSize() == 11
                            && run.isBold() && run.isItalic());
            check("copy_format 目标文本不变", "爱情诗两首".equals(q.getText()));
            XWPFParagraph src = x.getParagraphs().get(2);
            check("copy_format 源不变", "我们之间隔着一片海".equals(src.getText())
                    && src.getRuns().get(0).getFontSize() == 11 && src.getRuns().get(0).isBold());
        }
        JavaWordEngine.Result rc2 = eng.execute("copy_format", map(
                "path", "fmt.docx", "source", 2, "from", 3, "to", 3));
        check("copy_format 混合 run 统一且文本不变", rc2.ok);
        try (XWPFDocument x = open(path)) {
            XWPFParagraph q = x.getParagraphs().get(3);
            check("copy_format 两个 run 都套用源格式",
                    q.getRuns().size() == 2
                            && q.getRuns().get(0).getFontSize() == 11 && q.getRuns().get(0).isBold()
                            && q.getRuns().get(1).getFontSize() == 11 && q.getRuns().get(1).isBold()
                            && "局部加粗普通".equals(q.getText()));
        }

        // ---- set_page：cm→twips；缺省字段保留 ----
        JavaWordEngine.Result rp = eng.execute("set_page", map(
                "path", "fmt.docx", "marginTop", 2.54, "marginBottom", 2.54,
                "marginLeft", 2.54, "marginRight", 2.54));
        check("set_page ok", rp.ok);
        try (XWPFDocument x = open(path)) {
            var mar = x.getDocument().getBody().getSectPr().getPgMar();
            check("set_page 2.54cm=1440 twips",
                    String.valueOf(mar.getTop()).equals("1440")
                            && String.valueOf(mar.getBottom()).equals("1440")
                            && String.valueOf(mar.getLeft()).equals("1440")
                            && String.valueOf(mar.getRight()).equals("1440"));
        }
        JavaWordEngine.Result rp2 = eng.execute("set_page", map("path", "fmt.docx", "marginTop", 3.0));
        try (XWPFDocument x = open(path)) {
            var mar = x.getDocument().getBody().getSectPr().getPgMar();
            check("set_page 缺省字段保留（只改 top）",
                    rp2.ok && String.valueOf(mar.getTop()).equals("1701")
                            && String.valueOf(mar.getBottom()).equals("1440")
                            && String.valueOf(mar.getLeft()).equals("1440")
                            && String.valueOf(mar.getRight()).equals("1440"));
        }

        // ---- append：heading/body 来源 + sectPr 顺序 ----
        JavaWordEngine.Result ra = eng.execute("append", map(
                "path", "fmt.docx",
                "content", "## 点评\n\n点评正文",
                "headingFormatFrom", 1, "bodyFormatFrom", 2));
        check("append 格式来源 ok", ra.ok);
        try (XWPFDocument x = open(path)) {
            var ps = x.getParagraphs();
            check("append 原 4 段不变 + 新增 2 段", ps.size() == 6
                    && "爱情诗两首".equals(ps.get(0).getText())
                    && "《潮信》".equals(ps.get(1).getText()));
            XWPFParagraph nh = ps.get(4);
            XWPFParagraph nb = ps.get(5);
            check("append 标题套用 headingFormatFrom",
                    "点评".equals(nh.getText())
                            && nh.getRuns().get(0).getFontSize() == 16
                            && nh.getRuns().get(0).isBold()
                            && nh.getAlignment() == ParagraphAlignment.CENTER);
            check("append 正文套用 bodyFormatFrom",
                    "点评正文".equals(nb.getText())
                            && nb.getRuns().get(0).getFontSize() == 11
                            && nb.getRuns().get(0).isBold()
                            && Math.abs(nb.getSpacingBetween() - 2.0) < 0.01
                            && nb.getIndentationFirstLine() == 210);
            XmlCursor cur = x.getDocument().getBody().getSectPr().newCursor();
            boolean prevIsP = cur.toPrevSibling() && "p".equals(cur.getName().getLocalPart());
            cur.dispose();
            check("append 后 sectPr 仍为 body 最后一个元素", prevIsP);
        }

        // ---- insert_paragraph：matchFormatFrom ----
        JavaWordEngine.Result ri = eng.execute("insert_paragraph", map(
                "path", "fmt.docx", "index", 1, "text", "插入段",
                "position", "after", "matchFormatFrom", 2));
        check("insert_paragraph 格式来源 ok", ri.ok);
        try (XWPFDocument x = open(path)) {
            XWPFParagraph np = x.getParagraphs().get(2);
            check("insert_paragraph 新段套用源格式且文本正确",
                    "插入段".equals(np.getText())
                            && np.getRuns().get(0).getFontSize() == 11
                            && np.getRuns().get(0).isBold()
                            && np.getAlignment() == ParagraphAlignment.RIGHT
                            && np.getIndentationFirstLine() == 210);
        }

        // ---- 非法来源：不落盘 ----
        String before;
        try (XWPFDocument x = open(path)) { before = x.getParagraphs().get(0).getText(); }
        JavaWordEngine.Result badSrc = eng.execute("append", map(
                "path", "fmt.docx", "content", "不会写入", "bodyFormatFrom", 99));
        check("append 非法来源失败", !badSrc.ok);
        try (XWPFDocument x = open(path)) {
            check("append 非法来源不落盘", x.getParagraphs().size() == 7
                    && before.equals(x.getParagraphs().get(0).getText()));
        }
        JavaWordEngine.Result badFmt = eng.execute("format", map(
                "path", "fmt.docx", "from", 0, "alignment", "bogus"));
        check("format 非法 alignment 失败", !badFmt.ok);

        // ---- 技能 schema 接线 ----
        TLWordJavaSkill sk = new TLWordJavaSkill("word");
        java.lang.reflect.Field pf = Class.forName("cn.tianlong.tlobject.base.TLBaseModule")
                .getDeclaredField("params");
        pf.setAccessible(true);
        pf.set(sk, new java.util.HashMap<String, String>());
        sk.setModuleParams();
        java.lang.reflect.Field scf = Class.forName("cn.tianlong.tlobject.aiagent.TLBaseSkill")
                .getDeclaredField("parameterSchema");
        scf.setAccessible(true);
        Object schemaObj = scf.get(sk);
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) schemaObj;
        check("schema 含新动作/参数",
                schema.containsKey("source") && schema.containsKey("matchFormatFrom")
                        && schema.containsKey("bodyFormatFrom") && schema.containsKey("headingFormatFrom")
                        && schema.containsKey("fontName") && schema.containsKey("fontSize")
                        && schema.containsKey("firstLineIndentChars") && schema.containsKey("lineSpacing")
                        && schema.containsKey("marginTop") && schema.containsKey("marginRight"));
        Object actionProp = schema.get("action");
        String actionDesc = actionProp instanceof Map
                ? String.valueOf(((Map<?, ?>) actionProp).get("description")) : "";
        check("action 描述含新动作", actionDesc.contains("copy_format")
                && actionDesc.contains("set_page") && actionDesc.contains("format"));

        // cleanup
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        }
        System.out.println("[WordFormatSelfTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static Map<String, Object> map(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    static XWPFDocument open(Path p) throws Exception {
        return new XWPFDocument(Files.newInputStream(p));
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (ok) pass++; else fail++;
    }
}
