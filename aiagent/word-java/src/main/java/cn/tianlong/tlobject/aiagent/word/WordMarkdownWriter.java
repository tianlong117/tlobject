package cn.tianlong.tlobject.aiagent.word;

import org.apache.poi.xwpf.usermodel.*;

import java.util.List;

/**
 * 写：Block 列表 → docx 内容（追加语义，新建=对空文档写）。
 *
 * 创建日期：2026/10/07 作者:tianlong
 */
public final class WordMarkdownWriter {

    private WordMarkdownWriter() {}

    public static void writeBlocks(XWPFDocument doc, List<WordMarkdown.Block> blocks) {
        for (WordMarkdown.Block b : blocks) {
            switch (b.type) {
                case HEADING: {
                    XWPFParagraph p = doc.createParagraph();
                    int lv = Math.max(1, Math.min(6, b.level));
                    applyHeading(p, lv);
                    writeSpans(p, b.spans, lv);
                    break;
                }
                case PARAGRAPH: {
                    XWPFParagraph p = doc.createParagraph();
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
    static void applyHeading(XWPFParagraph p, int lv) {
        p.setStyle("Heading" + lv);
        try {
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr pPr = p.getCTP().isSetPPr()
                    ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
            pPr.addNewOutlineLvl().setVal(java.math.BigInteger.valueOf(lv - 1));
        } catch (Throwable ignored) { }
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
