package cn.tianlong.tlobject.aiagent.word;

import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTInd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;

import java.math.BigInteger;
import java.util.List;

/**
 * 写：Block 列表 → docx 内容（追加语义，新建=对空文档写）。
 *
 * 创建日期：2026/10/07 作者:tianlong
 */
public final class WordMarkdownWriter {

    private WordMarkdownWriter() {}

    /**
     * @param chineseLayout true = 中文排版默认值（H1 居中 + 正文段落首行缩进 2 字符）；
     *                      false = plain，新段落不加任何对齐/缩进（= 旧行为）
     */
    public static void writeBlocks(XWPFDocument doc, List<WordMarkdown.Block> blocks, boolean chineseLayout) {
        for (WordMarkdown.Block b : blocks) {
            switch (b.type) {
                case HEADING: {
                    XWPFParagraph p = doc.createParagraph();
                    int lv = Math.max(1, Math.min(6, b.level));
                    applyHeading(p, lv, chineseLayout);
                    writeSpans(p, b.spans, lv);
                    break;
                }
                case PARAGRAPH: {
                    XWPFParagraph p = doc.createParagraph();
                    if (chineseLayout) applyFirstLineIndent(p);
                    writeSpans(p, b.spans);
                    break;
                }
                case BULLET: {
                    XWPFParagraph p = doc.createParagraph();
                    p.setStyle("ListParagraph");
                    XWPFRun r = p.createRun();
                    r.setText("• ");
                    writeSpans(p, b.spans);
                    break;
                }
                case NUMBERED: {
                    XWPFParagraph p = doc.createParagraph();
                    XWPFRun r = p.createRun();
                    r.setText("• ");
                    writeSpans(p, b.spans);
                    break;
                }
                case TABLE: {
                    if (b.rows.isEmpty()) break;
                    int rows = b.rows.size();
                    int cols = 0;
                    for (List<String> r : b.rows) cols = Math.max(cols, r.size());
                    XWPFTable t = doc.createTable(rows, cols);
                    for (int i = 0; i < rows; i++) {
                        List<String> row = b.rows.get(i);
                        for (int j = 0; j < cols; j++) {
                            String v = j < row.size() ? row.get(j) : "";
                            XWPFTableCell c = t.getRow(i).getCell(j);
                            if (c.getParagraphs().isEmpty()) c.addParagraph();
                            WordTextEditor.rewriteParagraph(c.getParagraphs().get(0), v);
                        }
                    }
                    break;
                }
                case PAGEBREAK: {
                    XWPFParagraph p = doc.createParagraph();
                    p.createRun().addBreak(BreakType.PAGE);
                    break;
                }
                default: break;
            }
        }
    }

    /**
     * 把段落标成标题。**三重设定，缺一不可**（Task 8 实测发现）：
     *
     *  1. `pStyle="HeadingN"` —— 常规做法，Word 会去 styles.xml 找定义
     *  2. `outlineLvl=N-1` —— 直接写在段落属性上。**关键**：`new XWPFDocument()` 根本没有
     *     styles part（getStyles() 返回 null），createStyles() 建出来也是空的、不含 HeadingN
     *     定义。只写 pStyle 就是个悬空引用——Word 可能退回正文、导航窗格不认。
     *     写了 outlineLvl 则导航/大纲级别无条件成立。
     *  3. 直接格式（粗体 + 字号）—— 即使前两者都不被识别，看上去也仍是标题
     *
     * 三重叠加后，无论 Word / WPS / POI 哪家、无论 styles part 在不在，标题都成立。
     */
    static void applyHeading(XWPFParagraph p, int lv, boolean chineseLayout) {
        p.setStyle("Heading" + lv);
        if (chineseLayout && lv == 1) p.setAlignment(ParagraphAlignment.CENTER);
        try {
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr pPr = p.getCTP().isSetPPr()
                    ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
            pPr.addNewOutlineLvl().setVal(java.math.BigInteger.valueOf(lv - 1));
        } catch (Throwable ignored) { }
    }

    /**
     * 无排版的标题（= 旧行为）。
     *
     * 2 参重载保留给插入类动作（WordTextEditor.insertParagraph）的既有调用点：
     * 本任务只管 create/append 的新写段落，插入路径的排版开关由 Task 2 接管——
     * 在那之前这条路必须保持原样（pStyle + outlineLvl，不写 jc）。
     */
    static void applyHeading(XWPFParagraph p, int lv) {
        applyHeading(p, lv, false);
    }

    /** 标题的字号表（磅） */
    static int headingFontSize(int lv) {
        switch (lv) {
            case 1: return 20;
            case 2: return 16;
            case 3: return 14;
            default: return 12;
        }
    }

    /**
     * 正文段落首行缩进 2 字符。
     *
     * firstLineChars（字符单位）是 Word/WPS 的正主——段落对话框显示"首行缩进 2 字符"，
     * 随字号自适应；firstLine（twips）兜底给不认字符单位的渲染器：
     * 2 × 五号 10.5pt = 21pt = 420 twips ≈ 0.74cm（中文 Word 默认字号下的"2 字符"）。
     * 不吞异常：写不进去就报错，不静默丢格式（与"拒绝而非改坏"哲学一致）。
     */
    static void applyFirstLineIndent(XWPFParagraph p) {
        CTPPr pPr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        CTInd ind = pPr.isSetInd() ? pPr.getInd() : pPr.addNewInd();
        ind.setFirstLineChars(BigInteger.valueOf(200));
        ind.setFirstLine(BigInteger.valueOf(420));
    }

    private static void writeSpans(XWPFParagraph p, List<WordMarkdown.Span> spans) {
        writeSpans(p, spans, 0);
    }

    /** lv>0 时按标题级别加粗并设字号（直接格式兜底） */
    private static void writeSpans(XWPFParagraph p, List<WordMarkdown.Span> spans, int lv) {
        for (WordMarkdown.Span s : spans) {
            XWPFRun r = p.createRun();
            r.setBold(s.bold || lv > 0);
            if (lv > 0) r.setFontSize(headingFontSize(lv));
            r.setText(s.text);
        }
    }
}
