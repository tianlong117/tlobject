package cn.tianlong.tlobject.aiagent.word;

import org.apache.poi.xwpf.usermodel.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读：结构化文本 / 大纲 / 表格 / 元数据。
 *
 * 输出里的 [N] 是段落号 = doc.getParagraphs() 下标（只数段落）；
 * [表格 N] 的 N 是 doc.getTables() 下标。两套编号独立，供后续修改动作定位。
 *
 * 创建日期：2026/10/07 作者:tianlong
 */
public final class WordTextExtractor {

    private WordTextExtractor() {}

    public static String read(XWPFDocument doc, int from, int to) {
        StringBuilder sb = new StringBuilder();
        int pIdx = 0, tIdx = 0;
        for (IBodyElement el : doc.getBodyElements()) {
            if (el instanceof XWPFParagraph) {
                XWPFParagraph p = (XWPFParagraph) el;
                int cur = pIdx++;
                if (cur < from) continue;
                if (to >= 0 && cur > to) break;
                sb.append('[').append(cur).append("] [")
                  .append(styleOf(p)).append("] ").append(p.getText()).append('\n');
            } else if (el instanceof XWPFTable) {
                XWPFTable t = (XWPFTable) el;
                int cur = tIdx++;
                // 表格不受 from/to 段落区间约束：区间只切段落，表格整块带上
                sb.append("[表格 ").append(cur).append("] ")
                  .append(t.getRows().size()).append("行×")
                  .append(t.getRow(0) == null ? 0 : t.getRow(0).getTableCells().size())
                  .append("列\n");
                for (XWPFTableRow r : t.getRows()) {
                    sb.append("  |");
                    for (XWPFTableCell c : r.getTableCells())
                        sb.append(' ').append(c.getText().replace("\n", " ")).append(" |");
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    public static String outline(XWPFDocument doc) {
        StringBuilder sb = new StringBuilder();
        List<XWPFParagraph> ps = doc.getParagraphs();
        boolean any = false;
        for (int i = 0; i < ps.size(); i++) {
            XWPFParagraph p = ps.get(i);
            if (!isHeading(p)) continue;
            any = true;
            sb.append('[').append(i).append("] ").append(p.getText()).append('\n');
        }
        if (!any) return "(no headings)";
        return sb.toString();
    }

    public static String tables(XWPFDocument doc, int index) {
        List<XWPFTable> ts = doc.getTables();
        if (ts.isEmpty()) return "(no tables)";
        StringBuilder sb = new StringBuilder();
        int from = index < 0 ? 0 : index;
        int to = index < 0 ? ts.size() - 1 : index;
        if (from >= ts.size())
            return "(no table " + index + "; document has " + ts.size() + ")";
        for (int k = from; k <= to; k++) {
            XWPFTable t = ts.get(k);
            sb.append("[表格 ").append(k).append("]\n");
            for (XWPFTableRow r : t.getRows()) {
                sb.append("  |");
                for (XWPFTableCell c : r.getTableCells())
                    sb.append(' ').append(c.getText().replace("\n", " ")).append(" |");
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public static Map<String, Object> info(XWPFDocument doc) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<XWPFParagraph> ps = doc.getParagraphs();
        int headings = 0, chars = 0;
        for (XWPFParagraph p : ps) {
            if (isHeading(p)) headings++;
            chars += p.getText() == null ? 0 : p.getText().length();
        }
        m.put("paragraphs", ps.size());
        m.put("tables", doc.getTables().size());
        m.put("headings", headings);
        m.put("pictures", doc.getAllPictures().size());
        m.put("chars", chars);
        return m;
    }

    static boolean isHeading(XWPFParagraph p) {
        // 与 styleOf 同源：pStyle 缺失时 outlineLvl 也算标题，
        // 否则 read 会标 [Heading1] 而 outline 却漏掉它（两份输出自相矛盾）
        return styleOf(p).toLowerCase().startsWith("heading");
    }

    static String styleOf(XWPFParagraph p) {
        String s = p.getStyle();
        if (s == null || s.isEmpty()) {
            // 部分文档不写 pStyle，靠 outlineLvl 表示标题
            try {
                if (p.getCTP().getPPr() != null && p.getCTP().getPPr().getOutlineLvl() != null)
                    return "Heading" + (p.getCTP().getPPr().getOutlineLvl().getVal().intValue() + 1);
            } catch (Exception ignored) {}
            return "Normal";
        }
        return s;
    }
}
