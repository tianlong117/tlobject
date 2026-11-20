package cn.tianlong.tlobject.aiagent.word;

import org.apache.poi.xwpf.usermodel.LineSpacingRule;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTInd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTJc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSpacing;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * Word 格式工具（纯 POI）。
 *
 * <p>两条语义：</p>
 * <ul>
 *   <li>{@link #apply} —— 显式设置传入字段；未传字段保持原值；run 字段作用于该段所有 run。</li>
 *   <li>{@link #copyFormat} —— 复制源段落的直接格式：段级 jc/spacing/ind，run 级整体 rPr；
 *       不复制 pStyle/编号/节属性，不改文本。目标段所有 run 统一套用源参考 run 的属性。</li>
 * </ul>
 *
 * <p>段落号一律 0 基；所有动作在内存中先完成校验，调用方 save 才落盘。</p>
 */
final class WordFormatter {

    private WordFormatter() { }

    static final class FormatSpec {
        String fontName;
        Double fontSize;
        Boolean bold;
        Boolean italic;
        ParagraphAlignment alignment;
        Double lineSpacing;
        Integer firstLineIndentChars;
        Integer spaceBeforePt;
        Integer spaceAfterPt;
        boolean any;

        static FormatSpec from(Map<String, Object> in) {
            FormatSpec s = new FormatSpec();
            if (in == null) return s;
            if (in.containsKey("fontName")) {
                s.fontName = String.valueOf(in.get("fontName")).trim();
                if (s.fontName.isEmpty())
                    throw new IllegalArgumentException("fontName must not be empty");
                s.any = true;
            }
            if (in.containsKey("fontSize")) {
                s.fontSize = positive(in.get("fontSize"), "fontSize");
                s.any = true;
            }
            if (in.containsKey("bold")) {
                s.bold = bool(in.get("bold"), "bold");
                s.any = true;
            }
            if (in.containsKey("italic")) {
                s.italic = bool(in.get("italic"), "italic");
                s.any = true;
            }
            if (in.containsKey("alignment")) {
                s.alignment = alignment(String.valueOf(in.get("alignment")));
                s.any = true;
            }
            if (in.containsKey("lineSpacing")) {
                s.lineSpacing = positive(in.get("lineSpacing"), "lineSpacing");
                s.any = true;
            }
            if (in.containsKey("firstLineIndentChars")) {
                s.firstLineIndentChars = nonNegativeInt(in.get("firstLineIndentChars"),
                        "firstLineIndentChars");
                s.any = true;
            }
            if (in.containsKey("spaceBefore")) {
                s.spaceBeforePt = nonNegativeInt(in.get("spaceBefore"), "spaceBefore");
                s.any = true;
            }
            if (in.containsKey("spaceAfter")) {
                s.spaceAfterPt = nonNegativeInt(in.get("spaceAfter"), "spaceAfter");
                s.any = true;
            }
            return s;
        }

        private static Double positive(Object v, String name) {
            Double d = toDouble(v);
            if (d == null || !Double.isFinite(d) || d <= 0)
                throw new IllegalArgumentException(name + " must be a positive number");
            return d;
        }

        private static Integer nonNegativeInt(Object v, String name) {
            Double d = toDouble(v);
            if (d == null || !Double.isFinite(d) || d < 0 || d > Integer.MAX_VALUE)
                throw new IllegalArgumentException(name + " must be a non-negative number");
            return (int) Math.round(d);
        }

        private static Boolean bool(Object v, String name) {
            if (v instanceof Boolean) return (Boolean) v;
            String s = v == null ? "" : String.valueOf(v).trim();
            if ("true".equalsIgnoreCase(s)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
            throw new IllegalArgumentException(name + " must be true/false");
        }

        private static ParagraphAlignment alignment(String raw) {
            String s = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
            switch (s) {
                case "left": return ParagraphAlignment.LEFT;
                case "center": return ParagraphAlignment.CENTER;
                case "right": return ParagraphAlignment.RIGHT;
                case "justify":
                case "both": return ParagraphAlignment.BOTH;
                default: throw new IllegalArgumentException(
                        "unknown alignment: \"" + raw + "\" (expected left | center | right | justify)");
            }
        }

        private static Double toDouble(Object v) {
            if (v instanceof Number) {
                double d = ((Number) v).doubleValue();
                return Double.isFinite(d) ? d : null;
            }
            try { return Double.parseDouble(String.valueOf(v).trim()); }
            catch (Exception e) { return null; }
        }
    }

    /** 显式设置传入字段；返回实际处理的 run 数。 */
    static int apply(XWPFParagraph p, FormatSpec s) {
        int runs = 0;
        for (XWPFRun r : p.getRuns()) {
            if (s.fontName != null) {
                r.setFontFamily(s.fontName, XWPFRun.FontCharRange.ascii);
                r.setFontFamily(s.fontName, XWPFRun.FontCharRange.hAnsi);
                r.setFontFamily(s.fontName, XWPFRun.FontCharRange.eastAsia);
                r.setFontFamily(s.fontName, XWPFRun.FontCharRange.cs);
            }
            if (s.fontSize != null) r.setFontSize(s.fontSize);
            if (s.bold != null) r.setBold(s.bold);
            if (s.italic != null) r.setItalic(s.italic);
            runs++;
        }
        if (s.alignment != null) p.setAlignment(s.alignment);
        if (s.lineSpacing != null) p.setSpacingBetween(s.lineSpacing, LineSpacingRule.AUTO);
        if (s.firstLineIndentChars != null) setFirstLineChars(p, s.firstLineIndentChars);
        if (s.spaceBeforePt != null) p.setSpacingBefore(s.spaceBeforePt * 20);
        if (s.spaceAfterPt != null) p.setSpacingAfter(s.spaceAfterPt * 20);
        return runs;
    }

    /** 首行缩进 n 字符（firstLineChars 为主、twips 兜底；与 writer 的 2 字符=420 同一口径）。 */
    static void setFirstLineChars(XWPFParagraph p, int chars) {
        CTPPr ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        CTInd ind = ppr.isSetInd() ? ppr.getInd() : ppr.addNewInd();
        ind.setFirstLineChars(BigInteger.valueOf(chars * 100L));
        ind.setFirstLine(BigInteger.valueOf(chars * 210L));
    }

    /** 复制源段直接格式到目标段；返回目标 run 数（源无 run 时只复制段级并返回 0）。 */
    static int copyFormat(XWPFParagraph src, XWPFParagraph dst) {
        CTPPr sp = src.getCTP().getPPr();
        CTPPr dp = dst.getCTP().isSetPPr() ? dst.getCTP().getPPr() : dst.getCTP().addNewPPr();
        if (sp != null && sp.isSetJc()) dp.setJc((CTJc) sp.getJc().copy());
        else if (dp.isSetJc()) dp.unsetJc();
        if (sp != null && sp.isSetSpacing()) dp.setSpacing((CTSpacing) sp.getSpacing().copy());
        else if (dp.isSetSpacing()) dp.unsetSpacing();
        if (sp != null && sp.isSetInd()) dp.setInd((CTInd) sp.getInd().copy());
        else if (dp.isSetInd()) dp.unsetInd();

        XWPFRun ref = referenceRun(src);
        if (ref == null) return 0;
        CTRPr rpr = ref.getCTR().isSetRPr() ? ref.getCTR().getRPr() : null;
        int n = 0;
        for (XWPFRun tr : dst.getRuns()) {
            if (rpr != null) tr.getCTR().setRPr((CTRPr) rpr.copy());
            else tr.getCTR().unsetRPr();
            n++;
        }
        return n;
    }

    /** 源段参考 run：第一个非空文本 run，否则第一个 run。 */
    static XWPFRun referenceRun(XWPFParagraph p) {
        XWPFRun first = null;
        for (XWPFRun r : p.getRuns()) {
            if (first == null) first = r;
            String t = r.text();
            if (t != null && !t.isEmpty()) return r;
        }
        return first;
    }

    /** 页面边距（cm）；至少传一个；应用到 body 级 sectPr（没有则创建）。 */
    static void setMargins(XWPFDocument doc, Double topCm, Double bottomCm,
                           Double leftCm, Double rightCm) {
        if (topCm == null && bottomCm == null && leftCm == null && rightCm == null)
            throw new IllegalArgumentException("set_page requires at least one margin");
        CTBody body = doc.getDocument().getBody();
        CTSectPr sect = body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr();
        CTPageMar mar = sect.isSetPgMar() ? sect.getPgMar() : sect.addNewPgMar();
        if (topCm != null) mar.setTop(BigInteger.valueOf(cmToTwips(positiveCm(topCm, "marginTop"))));
        if (bottomCm != null) mar.setBottom(BigInteger.valueOf(cmToTwips(positiveCm(bottomCm, "marginBottom"))));
        if (leftCm != null) mar.setLeft(BigInteger.valueOf(cmToTwips(positiveCm(leftCm, "marginLeft"))));
        if (rightCm != null) mar.setRight(BigInteger.valueOf(cmToTwips(positiveCm(rightCm, "marginRight"))));
    }

    private static double positiveCm(Double cm, String name) {
        if (cm == null || !Double.isFinite(cm) || cm <= 0)
            throw new IllegalArgumentException(name + " must be a positive number (cm)");
        return cm;
    }

    static long cmToTwips(double cm) {
        return Math.round(cm / 2.54 * 1440);
    }

    /** 新建段落：body 级 sectPr 存在时插在它之前（保持“节属性是 body 最后一个元素”）。 */
    static XWPFParagraph newParagraph(XWPFDocument doc) {
        CTBody body = doc.getDocument().getBody();
        if (body.isSetSectPr()) {
            XmlCursor cur = body.getSectPr().newCursor();
            try {
                XWPFParagraph p = doc.insertNewParagraph(cur);
                if (p != null) return p;
            } finally {
                cur.dispose();
            }
        }
        return doc.createParagraph();
    }

    /** 把新建的 body 元素（表格等）移到 sectPr 之前。 */
    static void moveBeforeSectPr(XWPFDocument doc, XmlObject obj) {
        if (obj == null) return;
        CTBody body = doc.getDocument().getBody();
        if (!body.isSetSectPr()) return;
        XmlCursor src = obj.newCursor();
        XmlCursor dst = body.getSectPr().newCursor();
        try {
            src.moveXml(dst);
        } finally {
            src.dispose();
            dst.dispose();
        }
    }

    /** 段落范围校验（含 to）。 */
    static void checkRange(int from, int to, int size) {
        if (from < 0 || from >= size)
            throw new IndexOutOfBoundsException("from " + from + " out of range (document has "
                    + size + " paragraphs)");
        if (to < from || to >= size)
            throw new IndexOutOfBoundsException("to " + to + " out of range (expected "
                    + from + ".." + (size - 1) + ")");
    }

    /** 取段落（越界抛错，调用方在写盘前失败）。 */
    static XWPFParagraph paragraphAt(XWPFDocument doc, int index) {
        return paragraphAt(doc.getParagraphs(), index);
    }

    static XWPFParagraph paragraphAt(List<XWPFParagraph> ps, int index) {
        if (index < 0 || index >= ps.size())
            throw new IndexOutOfBoundsException("paragraph index " + index
                    + " out of range (document has " + ps.size() + " paragraphs)");
        return ps.get(index);
    }
}
