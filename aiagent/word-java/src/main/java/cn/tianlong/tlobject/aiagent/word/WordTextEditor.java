package cn.tianlong.tlobject.aiagent.word;

import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 改：跨 run 精确替换 + 段落/表格操作。
 *
 * 核心难点：Word 会把一段文字任意切碎成多个 run（编辑历史/拼写检查/输入法都会造成），
 * "订单号：${orderNo}" 在 XML 里可能是 4 个 run。直接对单个 run 做字符串查找必然搜不到。
 *
 * 解法（最小重建）：把段落所有 run 文本拼成 full 做匹配，定位覆盖区间后只改这几个 run
 * 的**文本**，不新建不删除 run 对象 —— 字体/加粗/颜色/字号挂在 run 上原地不动，格式必然保留。
 *
 * 安全策略一句话：**写回必须与 XWPFRun.text() 互逆**（判据与实测清单见 {@link #unsafeRunReason}）。
 * 命中这类 run 时，精确替换**跳过并上报**（绝不静默改坏）；整段重写（set_paragraph /
 * set_table_cell / replace 的 mode=rewrite）另加段落级守卫 {@link #paragraphUnsafeReason}：
 * 段落里只要有域、超链接、br、内联内容控件就**拒绝执行**——整段重写会把它们当场毁掉
 * （实测：模型文本被写进域代码区、域结果被清空，而 read 却报文本已写入）。
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

    private static final String W_NS =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    /**
     * 实测过"对 XWPFRun.text() 零贡献、且 setRunText 不碰"的元素（w 命名空间局部名）。
     * 只有这些（外加我们真正要改写的 w:t、纯格式的 w:rPr）才允许出现在 run 里；
     * 其余一律不安全——生僻元素（域代码、脚注引用、ruby…）宁可跳过也不赌。
     */
    private static final Set<String> TEXT_INERT_ELEMENTS = new HashSet<>(Arrays.asList(
            "t", "rPr", "lastRenderedPageBreak", "softHyphen", "sym", "drawing", "pict", "object"));

    /**
     * 该 run 的不安全原因（元素名，如 {@code w:br}）；null = 安全可改。
     *
     * 判据不是"元素看着危不危险"，而是**写回必须与 XWPFRun.text() 互逆**：我们只往 &lt;w:t&gt;
     * 写文本，所以只有 text() 会为它**合成/变换字符**的元素才会毁——写回后那个字符会多出一份
     * （前缀/后缀取自 text()，已经把合成的字符包含进去了）。
     *
     * 逐条核实 POI 5.5.1 的 XWPFRun.text()/_getText()（javap -c 反编译
     * D:\repository\org\apache\poi\poi-ooxml\5.5.1\poi-ooxml-5.5.1.jar），并逐元素构造 run 实测：
     *   w:br / w:cr      → 追加 '\n'（CTBr 分支；以及 CTEmpty 的 br/cr）
     *   w:tab / w:ptab   → 追加 '\t'（CTEmpty 的 tab；以及 CTPTab 分支）
     *   w:noBreakHyphen  → 追加 U+2011（CTEmpty 的 noBreakHyphen——"只有 br/tab 才合成字符"的想当然
     *                      在此翻车：实测 [rPr,noBreakHyphen,t("TXT")].text() = "‑TXT"）
     *   w:delText        → 直接追加内容（CTText 分支只排除 instrText/delInstrText）
     *   w:footnoteReference / w:endnoteReference → 追加 "[footnoteRef:N]"（CTFtnEdnRef 分支）
     *   w:fldChar(BEGIN 带 ffData 复选框) → 追加 "|X|"/"|_|"（CTFldChar 分支）
     *   w:ruby           → handleRuby 递归取 rt/rubyBase（实测顶层 &lt;w:t&gt; 为空而 text() 取到注音基字）
     *   rPr 的 w:caps / w:smallCaps → text() 把文本 toUpperCase（实测 "abc def"→"ABC DEF"）：
     *                      写回等于把大写烘进 &lt;w:t&gt;，原文大小写丢失，同样不是互逆
     * 反面（实测零贡献、round-trip 精确，故放行）：
     *   w:t（多个 &lt;w:t&gt; 也安全，setRunText 会把多余的删掉）/ w:rPr / w:lastRenderedPageBreak /
     *   w:softHyphen / w:sym / w:drawing / w:pict / w:object
     * 其中 w:lastRenderedPageBreak 是重点：Windows Word 在每个分页处都会往 run 里塞它，
     * 旧白名单"只许 w:t 与 w:rPr"把它判成危险，整份文档大面积改不动（复审实测 186 篇语料
     * 跳过段落 263→287）。
     */
    static String unsafeRunReason(XWPFRun r) {
        if (r instanceof XWPFHyperlinkRun) return "w:hyperlink";   // 文本由 relationship 管理
        if (r instanceof XWPFFieldRun) return "w:fldChar";         // PAGE/NUMPAGES 等域
        try {
            if (r.isCapitalized() || r.isSmallCaps()) return "w:caps";
            CTR ctr = r.getCTR();
            XmlCursor c = ctr.newCursor();
            try {
                c.selectPath("./*");
                while (c.toNextSelection()) {
                    String name = wElementName(c.getObject());
                    if (name == null) return "<non-w child>";      // 不是 w 命名空间：赌不起
                    if (!TEXT_INERT_ELEMENTS.contains(name)) return "w:" + name;
                }
            } finally {
                c.dispose();
            }
            return null;
        } catch (Throwable ignored) {
            return "<probe failed>";                               // 探测不了就当不安全，保守
        }
    }

    static boolean isUnsafeRun(XWPFRun r) { return unsafeRunReason(r) != null; }

    /** 直接子元素的 w 命名空间局部名（"t"/"br"…）；不是 w 命名空间或取不到时返回 null */
    private static String wElementName(XmlObject o) {
        org.w3c.dom.Node n = o.getDomNode();
        if (!W_NS.equals(n.getNamespaceURI())) return null;
        String ln = n.getLocalName();
        if (ln != null) return ln;
        String nn = n.getNodeName();                               // 非命名空间感知的 DOM 兜底
        int colon = nn == null ? -1 : nn.indexOf(':');
        return colon >= 0 ? nn.substring(colon + 1) : nn;
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
     * 预扫描：本段所有 find 匹配的 run 区间里，第一个会碰到的危险元素名（null=全安全）。
     * 用于保证整段替换的原子性——不能改了一半才发现后面有一处碰不得。
     */
    private static String unsafeHitReason(XWPFParagraph p, String find) {
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
            if (ms < 0) return null;
            int me = ms + find.length();
            int ri = -1, rj = -1;
            for (int k = 0; k < spans.size(); k++) {
                Span s = spans.get(k);
                if (ri < 0 && s.start <= ms && ms < s.end) ri = k;
                if (s.start < me && me <= s.end) { rj = k; break; }
            }
            if (ri < 0 || rj < 0 || ri > rj) { scan = ms + 1; continue; }
            for (int k = ri; k <= rj; k++) {
                String why = unsafeRunReason(spans.get(k).run);
                if (why != null) return why;
            }
            scan = me;
        }
    }

    /**
     * 段落级守卫：整段重写（set_paragraph / set_table_cell / replace 的 mode=rewrite）会清掉
     * **所有** run，段落里只要有危险元素就会被当场毁掉，故这里是"段落里有就算"而不是
     * "命中区间碰到才算"——它动的是整段。
     *
     * @return 危险原因（元素名）；null = 可以整段重写
     */
    static String paragraphUnsafeReason(XWPFParagraph p) {
        for (XWPFRun r : p.getRuns()) {
            String why = unsafeRunReason(r);
            if (why != null) return why;
        }
        // 段落里还有 getRuns() 看不到、Word 却会显示的内容——内联内容控件 w:sdt 最典型。
        // 实测（落盘重载后，真实文档形态）：段落 = run("前缀") + sdt("SECRET-INVISIBLE") 时
        // getRuns() 只有 1 个 run、getText() 却是 "前缀SECRET-INVISIBLE"；整段重写后
        // getText() = "WHOLE-NEWSECRET-INVISIBLE"——新旧内容粘在一起，而回执 ok=true。
        for (IRunElement e : p.getIRuns()) {
            if (!(e instanceof XWPFRun))
                return (e instanceof XWPFSDT) ? "w:sdt" : e.getClass().getSimpleName();
        }
        return null;
    }

    /** 段落内是否有"整段重写会毁掉"的元素（域、超链接、br/tab、内联内容控件 w:sdt 等） */
    static boolean paragraphHasUnsafeRuns(XWPFParagraph p) {
        return paragraphUnsafeReason(p) != null;
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
        if (unsafeHitReason(p, find) != null) return SKIPPED_UNSAFE;   // 原子性：先判后改，不做半改

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
                    String why = paragraphUnsafeReason(p);
                    if (why != null) {
                        // 整段重写会清掉所有 run：域/超链接/br 会被当场毁掉（实测模型文本被写进
                        // 域代码区、域结果被清空）。拒绝并点名，绝不用"改坏"换"改成"。
                        rep.skipped++;
                        rep.skippedReasons.add("paragraph " + i + " contains " + why + ", skipped");
                        continue;
                    }
                    // getText() 把 br/tab 合成了 \n/\t，直接写回会造成换行重复；
                    // rewrite 语义=丢段内格式，故先拉平（能走到这里就说明段落里没有 br/tab）
                    String flat = t.replace("\n", " ").replace("\t", " ");
                    int before = countOccurrences(flat, find);
                    rewriteParagraph(p, flat.replace(find, replace));
                    rep.replaced += before;
                }
            } else {
                int n = replaceInParagraph(p, find, replace);
                if (n == SKIPPED_UNSAFE) {
                    rep.skipped++;
                    String why = unsafeHitReason(p, find);
                    rep.skippedReasons.add("paragraph " + i
                            + " contains " + (why == null ? "unsafe run" : why) + ", skipped");
                } else if (n > 0) {
                    rep.replaced += n;
                }
            }
        }
        // 表格：单元格内也有段落，按表格定位；表格单元格里的不安全 run 同样计数并点名
        List<XWPFTable> ts = doc.getTables();
        for (int ti = 0; ti < ts.size(); ti++) {
            List<XWPFTableRow> rows = ts.get(ti).getRows();
            for (int ri = 0; ri < rows.size(); ri++) {
                List<XWPFTableCell> cells = rows.get(ri).getTableCells();
                for (int ci = 0; ci < cells.size(); ci++) {
                    for (XWPFParagraph p : cells.get(ci).getParagraphs()) {
                        int n = replaceInParagraph(p, find, replace);
                        if (n > 0) rep.replaced += n;
                        else if (n == SKIPPED_UNSAFE) {
                            rep.skipped++;
                            String why = unsafeHitReason(p, find);
                            rep.skippedReasons.add("table " + ti + " row " + ri + " col " + ci
                                    + " contains " + (why == null ? "unsafe run" : why) + ", skipped");
                        }
                    }
                }
            }
        }
        return rep;
    }

    /**
     * 整段重写：清空所有 run，用第一个 run 的格式写回（丢段内混合格式）。
     *
     * 段落含域/超链接/br/tab/内联内容控件时**拒绝执行**（抛 IllegalStateException）：
     * 这类段落整段重写必毁（实测域代码区被写进新文本、域结果被清空，而 read 仍报文本在），
     * 拒绝发生在任何改动之前，调用方拿到的是"段落原封不动 + 一条能读懂的报错"。
     */
    static void rewriteParagraph(XWPFParagraph p, String newText) {
        String why = paragraphUnsafeReason(p);
        if (why != null)
            throw new IllegalStateException("paragraph contains " + why
                    + " and cannot be fully rewritten safely; "
                    + "edit it with replace, or remove the field manually");
        List<XWPFRun> runs = p.getRuns();
        if (runs.isEmpty()) {
            setRunText(p.createRun(), newText);
            return;
        }
        setRunText(runs.get(0), newText);
        // 其余 run 一律清空——必须走 setRunText：只写 t[0] 会留下 t[1..]，
        // 那些残留文本会被段落 text() 拼回来（rewrite 模式整段复制/尾串重复的根因）
        for (int k = 1; k < runs.size(); k++) setRunText(runs.get(k), "");
        // 兜底清残留 br/tab（Task 10 实测：它们会与新写入的文本重复）。
        // 上面的守卫已保证走到这里的段落没有 br/tab——有就抛了；留着纯属二道保险：
        // 将来若有人放宽守卫，这里仍不会把换行标志重复一遍。
        for (XWPFRun r : runs) {
            try {
                CTR ctr = r.getCTR();
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
                                       String style, boolean before, boolean chineseLayout) {
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
        // 排版规则与 writer 同源（显式样式优先）：HeadingN → 三重设定；其他显式样式 → 只 setStyle，
        // 不插手（显式样式自己说了算）；无样式 → 视为正文段落，chinese 时加首行缩进。
        String st = (style == null) ? null : style.trim();
        int headingLv = 0;
        if (st != null && !st.isEmpty()) {
            java.util.regex.Matcher hm =
                    java.util.regex.Pattern.compile("(?i)^heading\\s*([1-6])$").matcher(st);
            if (hm.matches()) {
                headingLv = Integer.parseInt(hm.group(1));
                WordMarkdownWriter.applyHeading(np, headingLv, chineseLayout);
            } else {
                np.setStyle(st);
            }
        } else if (chineseLayout) {
            // 无样式（含只含空白的字符串）= 正文段落：与 writer 的 PARAGRAPH 分支同口径
            WordMarkdownWriter.applyFirstLineIndent(np);
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
