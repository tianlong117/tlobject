package cn.tianlong.tlobject.aiagent.word;

import org.apache.poi.xwpf.usermodel.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 改：跨 run 精确替换 + 段落/表格操作。
 *
 * 核心难点：Word 会把一段文字任意切碎成多个 run（编辑历史/拼写检查/输入法都会造成），
 * "订单号：${orderNo}" 在 XML 里可能是 4 个 run。直接对单个 run 做字符串查找必然搜不到。
 *
 * 解法（最小重建）：把段落所有 run 文本拼成 full 做匹配，定位覆盖区间后只改这几个 run
 * 的**文本**，不新建不删除 run 对象 —— 字体/加粗/颜色/字号挂在 run 上原地不动，格式必然保留。
 *
 * 安全策略：命中含超链接/域，或除 w:t / w:rPr 外还有别的子元素（br/tab/drawing/softHyphen…）
 * 的 run 时**跳过并上报**，绝不静默改坏——这类 run 的 text() 无法原样写回。
 *
 * 创建日期：2026/10/07 作者:tianlong
 */
public final class WordTextEditor {

    /** 替换计数返回值：>=0 为成功替换处数；-2 表示命中不安全 run 被跳过 */
    public static final int SKIPPED_UNSAFE = -2;

    private WordTextEditor() {}

    private static class Span {
        int start, end;
        XWPFRun run;
        Span(int s, int e, XWPFRun r) { start = s; end = e; run = r; }
    }

    /** 段落里是否含"重写会丢东西"的 run */
    static boolean isUnsafeRun(XWPFRun r) {
        if (r instanceof XWPFHyperlinkRun) return true;   // 文本由 relationship 管理
        if (r instanceof XWPFFieldRun) return true;       // PAGE/NUMPAGES 等域
        try {
            // 穷举式判定：只允许 w:t 与 w:rPr 两种子元素。
            // 用"列举坏元素"的黑名单一定会漏——Word 会在 w:softHyphen/w:noBreakHyphen/
            // w:lastRenderedPageBreak 等处同样切断 w:t，而这些都会让 text() 无法原样写回。
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR ctr = r.getCTR();
            org.apache.xmlbeans.XmlCursor c = ctr.newCursor();
            try {
                c.selectPath("./*");
                while (c.toNextSelection()) {
                    String name = c.getObject().getDomNode().getLocalName();
                    if (name == null) {                   // 非命名空间感知的 DOM 兜底：nodeName 去前缀
                        String nn = c.getObject().getDomNode().getNodeName();
                        int colon = nn == null ? -1 : nn.indexOf(':');
                        name = colon >= 0 ? nn.substring(colon + 1) : nn;
                    }
                    if (!"t".equals(name) && !"rPr".equals(name)) return true;
                }
            } finally {
                c.dispose();
            }
        } catch (Throwable ignored) {
            return true;                                      // 探测不了就当不安全，保守
        }
        return false;
    }

    /**
     * 写入 run 文本，使其与 XWPFRun.text() **互逆**。
     *
     * 坑：XWPFRun.setText(v, 0) 只覆盖 &lt;w:t&gt;[0]，而 XWPFRun.text() 拼接**全部** &lt;w:t&gt;。
     * 两者不是逆操作——一个 run 里若有多个 &lt;w:t&gt;，写完后残留的 t[1..] 仍会被 text() 读出来。
     * 后果实测：替换循环永不停（每次残留都重新命中），rewrite 模式复制段落内容。
     * 故写完后必须把多余的 &lt;w:t&gt; 删掉。
     */
    static void setRunText(XWPFRun r, String text) {
        r.setText(text == null ? "" : text, 0);
        try {
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR ctr = r.getCTR();
            while (ctr.sizeOfTArray() > 1) ctr.removeT(1);
        } catch (Throwable ignored) { }
    }

    /**
     * 预扫描：本段所有 find 匹配的 run 区间里，是否有一处会碰到不安全 run。
     * 用于保证整段替换的原子性——不能改了一半才发现后面有一处碰不得。
     */
    private static boolean anyHitUnsafe(XWPFParagraph p, String find) {
        List<XWPFRun> runs = p.getRuns();
        List<Span> spans = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        for (XWPFRun r : runs) {
            String t = r.text();
            if (t == null) t = "";
            spans.add(new Span(full.length(), full.length() + t.length(), r));
            full.append(t);
        }
        String hay = full.toString();
        int scan = 0;
        while (true) {
            int ms = hay.indexOf(find, scan);
            if (ms < 0) return false;
            int me = ms + find.length();
            int ri = -1, rj = -1;
            for (int k = 0; k < spans.size(); k++) {
                Span s = spans.get(k);
                if (ri < 0 && s.start <= ms && ms < s.end) ri = k;
                if (s.start < me && me <= s.end) { rj = k; break; }
            }
            if (ri < 0 || rj < 0 || ri > rj) { scan = ms + 1; continue; }
            for (int k = ri; k <= rj; k++) if (isUnsafeRun(spans.get(k).run)) return true;
            scan = me;
        }
    }

    /**
     * 段落内替换。find 一律按**字面量**处理——占位符里带 ${} 会干扰正则，本技能不需要正则替换。
     *
     * @return >=0 替换处数；-2 命中不安全 run
     */
    public static int replaceInParagraph(XWPFParagraph p, String find, String replace) {
        if (find == null || find.isEmpty()) return 0;
        List<XWPFRun> runs = p.getRuns();
        if (runs.isEmpty()) return 0;
        if (anyHitUnsafe(p, find)) return SKIPPED_UNSAFE;    // 原子性：先判后改，不做半改

        List<Span> spans = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        for (XWPFRun r : runs) {
            String t = r.text();
            if (t == null) t = "";
            spans.add(new Span(full.length(), full.length() + t.length(), r));
            full.append(t);
        }
        String hay = full.toString();

        int count = 0;
        int searchFrom = 0;
        while (true) {
            int ms = hay.indexOf(find, searchFrom);
            if (ms < 0) break;
            int me = ms + find.length();

            int ri = -1, rj = -1;
            for (int k = 0; k < spans.size(); k++) {
                Span s = spans.get(k);
                if (ri < 0 && s.start <= ms && ms < s.end) ri = k;
                if (s.start < me && me <= s.end) { rj = k; break; }
            }
            // 匹配落在零长度 run 边界等异常情形：放弃该处，往后挪一格避免死循环
            if (ri < 0 || rj < 0 || ri > rj) { searchFrom = ms + 1; continue; }

            String prefix = hay.substring(spans.get(ri).start, ms);
            String suffix = hay.substring(me, spans.get(rj).end);
            if (ri == rj) {
                setRunText(spans.get(ri).run, prefix + replace + suffix);
            } else {
                setRunText(spans.get(ri).run, prefix + replace);
                for (int k = ri + 1; k < rj; k++) setRunText(spans.get(k).run, "");
                setRunText(spans.get(rj).run, suffix);
            }
            count++;

            // 就地把 hay / spans 重建，支持同段多处替换。
            // run 文本是唯一真值：手写增量更新（只改 [ri,rj] 区间）会在替换长度变化时破坏
            // 区间表的分区不变量——空隙让后续匹配被静默漏掉，重叠会把文本写进错误的 run。
            StringBuilder rebuilt = new StringBuilder();
            for (Span s : spans) {
                String t = s.run.text();
                if (t == null) t = "";
                s.start = rebuilt.length();
                s.end = rebuilt.length() + t.length();
                rebuilt.append(t);
            }
            hay = rebuilt.toString();
            searchFrom = ms + replace.length();
        }
        return count;
    }

    /** 替换回执 */
    public static class ReplaceReport {
        public int replaced;
        public int skipped;
        public List<String> skippedReasons = new ArrayList<>();
    }

    /**
     * 全文替换（正文段落 + 表格单元格）。
     *
     * @param pIndex   >=0 时只处理该段落号；<0 处理全文
     * @param mode     "preserve"（默认，保格式）/ "rewrite"（整段重写，丢段内格式但必成）
     */
    public static ReplaceReport replaceInDocument(XWPFDocument doc, String find, String replace,
                                                  int pIndex, String mode) {
        ReplaceReport rep = new ReplaceReport();
        boolean rewrite = "rewrite".equalsIgnoreCase(mode);
        List<XWPFParagraph> ps = doc.getParagraphs();
        for (int i = 0; i < ps.size(); i++) {
            if (pIndex >= 0 && i != pIndex) continue;
            XWPFParagraph p = ps.get(i);
            if (rewrite) {
                String t = p.getText();
                if (t != null && t.contains(find)) {
                    // getText() 把 br/tab 合成了 \n/\t，直接写回会造成换行重复；
                    // rewrite 语义=丢段内格式，故先拉平
                    String flat = t.replace("\n", " ").replace("\t", " ");
                    int before = countOccurrences(flat, find);
                    rewriteParagraph(p, flat.replace(find, replace));
                    rep.replaced += before;
                }
            } else {
                int n = replaceInParagraph(p, find, replace);
                if (n == SKIPPED_UNSAFE) {
                    rep.skipped++;
                    rep.skippedReasons.add("paragraph " + i
                            + " contains hyperlink/field/break run, skipped");
                } else if (n > 0) {
                    rep.replaced += n;
                }
            }
        }
        // 表格：单元格内也有段落，按表格定位；表格单元格里的不安全 run 同样计数
        for (XWPFTable t : doc.getTables()) {
            for (XWPFTableRow r : t.getRows()) {
                for (XWPFTableCell c : r.getTableCells()) {
                    for (XWPFParagraph p : c.getParagraphs()) {
                        int n = replaceInParagraph(p, find, replace);
                        if (n > 0) rep.replaced += n;
                        else if (n == SKIPPED_UNSAFE) rep.skipped++;
                    }
                }
            }
        }
        return rep;
    }

    /** 整段重写：清空所有 run，用第一个 run 的格式写回（丢段内混合格式） */
    static void rewriteParagraph(XWPFParagraph p, String newText) {
        List<XWPFRun> runs = p.getRuns();
        if (runs.isEmpty()) {
            setRunText(p.createRun(), newText);
            return;
        }
        setRunText(runs.get(0), newText);
        // 其余 run 一律清空——必须走 setRunText：只写 t[0] 会留下 t[1..]，
        // 那些残留文本会被段落 text() 拼回来（rewrite 模式整段复制/尾串重复的根因）
        for (int k = 1; k < runs.size(); k++) setRunText(runs.get(k), "");
        // 清掉残留的 br/tab：否则它们会与新写入的文本重复（Task 10 实测）
        for (XWPFRun r : runs) {
            try {
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR ctr = r.getCTR();
                while (ctr.sizeOfBrArray() > 0) ctr.removeBr(0);
                while (ctr.sizeOfTabArray() > 0) ctr.removeTab(0);
            } catch (Throwable ignored) { }
        }
    }

    static int countOccurrences(String hay, String needle) {
        if (needle.isEmpty()) return 0;
        int c = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }

    // ================= 段落操作 =================
    // 段落号一律 = doc.getParagraphs() 下标，与 WordTextExtractor 的 [N] 同一套编号。
    // 越界一律抛 IndexOutOfBoundsException：上层引擎翻成 JSON 错误交给模型自己改，
    // 静默 no-op 会让模型误以为改成功了。

    public static void setParagraphText(XWPFDocument doc, int index, String text) {
        List<XWPFParagraph> ps = doc.getParagraphs();
        if (index < 0 || index >= ps.size())
            throw new IndexOutOfBoundsException("paragraph index " + index
                    + " out of range (document has " + ps.size() + " paragraphs)");
        rewriteParagraph(ps.get(index), text);
    }

    public static void insertParagraph(XWPFDocument doc, int index, String text,
                                       String style, boolean before) {
        List<XWPFParagraph> ps = doc.getParagraphs();
        if (index < 0 || index >= ps.size())
            throw new IndexOutOfBoundsException("paragraph index " + index
                    + " out of range (document has " + ps.size() + " paragraphs)");
        XWPFParagraph anchor = ps.get(index);
        XWPFParagraph np;
        if (before) {
            np = doc.insertNewParagraph(anchor.getCTP().newCursor());
        } else {
            org.apache.xmlbeans.XmlCursor c = nextCursor(anchor);
            // 锚点是 body 最后一个元素时没有下一兄弟：toNextSibling 失败后游标仍停在锚点之前，
            // 再 insertNewParagraph 会把新段落到锚点**前面**（实测：末段后插跑到了文档开头，
            // 落盘 XML 也是错的）。此时改用末尾追加——句面等价，且 XML 位置正确（sectPr 之前）。
            np = (c == null) ? doc.createParagraph() : doc.insertNewParagraph(c);
        }
        if (np == null) throw new IllegalStateException("insertNewParagraph returned null");

        // style="HeadingN" 不能只 setStyle：本技能自己的 create 建出来的文档**没有 styles part**
        // （new XWPFDocument() 的 getStyles() 就是 null），pStyle="Heading1" 是个悬空引用——
        // Word 里渲染成正文，而 read/outline 却报 Heading1（两处自相矛盾）。
        // 故与 WordMarkdownWriter.applyHeading 走同一套三重设定：pStyle + outlineLvl + 直接格式。
        int headingLv = 0;
        if (style != null && !style.isEmpty()) {
            java.util.regex.Matcher hm =
                    java.util.regex.Pattern.compile("(?i)^heading\\s*([1-6])$").matcher(style.trim());
            if (hm.matches()) {
                headingLv = Integer.parseInt(hm.group(1));
                WordMarkdownWriter.applyHeading(np, headingLv);
            } else {
                np.setStyle(style);
            }
        }
        XWPFRun run = np.createRun();
        run.setText(text);
        if (headingLv > 0) {
            // 第 3 重（直接格式兜底）挂在 run 上：writer 的 writeSpans(p,spans,lv) 就是这么做的
            run.setBold(true);
            run.setFontSize(WordMarkdownWriter.headingFontSize(headingLv));
        }
    }

    /** 锚点之后的游标；锚点是 body 最后一个元素（没有下一兄弟）时返回 null，由调用方兜底 */
    private static org.apache.xmlbeans.XmlCursor nextCursor(XWPFParagraph anchor) {
        org.apache.xmlbeans.XmlCursor c = anchor.getCTP().newCursor();
        return c.toNextSibling() ? c : null;
    }

    public static void deleteParagraph(XWPFDocument doc, int index) {
        List<XWPFParagraph> ps = doc.getParagraphs();
        if (index < 0 || index >= ps.size())
            throw new IndexOutOfBoundsException("paragraph index " + index
                    + " out of range (document has " + ps.size() + " paragraphs)");
        doc.removeBodyElement(doc.getPosOfParagraph(ps.get(index)));
    }

    // ================= 表格操作 =================

    public static void setTableCell(XWPFDocument doc, int table, int row, int col, String text) {
        XWPFTable t = tableAt(doc, table);
        if (row < 0 || row >= t.getRows().size())
            throw new IndexOutOfBoundsException("row " + row + " out of range (table has "
                    + t.getRows().size() + " rows)");
        XWPFTableRow r = t.getRow(row);
        if (col < 0 || col >= r.getTableCells().size())
            throw new IndexOutOfBoundsException("col " + col + " out of range (row has "
                    + r.getTableCells().size() + " cells)");
        XWPFTableCell c = r.getCell(col);
        List<XWPFParagraph> cps = c.getParagraphs();
        if (cps.isEmpty()) { c.addParagraph().createRun().setText(text); return; }
        rewriteParagraph(cps.get(0), text);
        for (int k = cps.size() - 1; k >= 1; k--) c.removeParagraph(k);
    }

    public static void addTableRow(XWPFDocument doc, int table, String[] values) {
        XWPFTable t = tableAt(doc, table);
        XWPFTableRow r = t.createRow();
        int cols = r.getTableCells().size();
        if (values != null) {
            for (int i = 0; i < values.length && i < cols; i++) {
                XWPFTableCell c = r.getCell(i);
                if (c.getParagraphs().isEmpty()) c.addParagraph();
                rewriteParagraph(c.getParagraphs().get(0),
                        values[i] == null ? "" : values[i]);
            }
        }
    }

    static XWPFTable tableAt(XWPFDocument doc, int table) {
        List<XWPFTable> ts = doc.getTables();
        if (table < 0 || table >= ts.size())
            throw new IndexOutOfBoundsException("table index " + table
                    + " out of range (document has " + ts.size() + " tables)");
        return ts.get(table);
    }

    /**
     * 模板填充：对每个键做一次全文替换（正文 + 表格），占位符 ${key} 与 {{key}} 都认。
     * 数据里没有的键保持原样——不静默清空。
     */
    public static ReplaceReport fillTemplate(XWPFDocument doc, Map<String, String> data) {
        ReplaceReport total = new ReplaceReport();
        if (data == null || data.isEmpty()) return total;
        for (Map.Entry<String, String> e : data.entrySet()) {
            if (e.getKey() == null || e.getKey().isEmpty()) continue;
            String v = e.getValue() == null ? "" : e.getValue();
            for (String ph : new String[]{"${" + e.getKey() + "}", "{{" + e.getKey() + "}}"}) {
                ReplaceReport r = replaceInDocument(doc, ph, v, -1, "preserve");
                total.replaced += r.replaced;
                total.skipped += r.skipped;
                total.skippedReasons.addAll(r.skippedReasons);
            }
        }
        return total;
    }
}
