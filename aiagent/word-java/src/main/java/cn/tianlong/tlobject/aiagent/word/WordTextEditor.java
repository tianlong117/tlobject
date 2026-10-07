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
 * 安全策略：命中含超链接/域/br/tab/drawing 的 run 时**跳过并上报**，绝不静默改坏。
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
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR ctr = r.getCTR();
            if (ctr.sizeOfBrArray() > 0) return true;         // 段内换行，run.text() 不含它
            if (ctr.sizeOfTabArray() > 0) return true;        // 制表符同理
            if (ctr.sizeOfDrawingArray() > 0) return true;    // 内嵌图片
        } catch (Throwable ignored) {
            return true;                                      // 探测不了就当不安全，保守
        }
        return false;
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
                spans.get(ri).run.setText(prefix + replace + suffix, 0);
            } else {
                spans.get(ri).run.setText(prefix + replace, 0);
                for (int k = ri + 1; k < rj; k++) spans.get(k).run.setText("", 0);
                spans.get(rj).run.setText(suffix, 0);
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
                    int before = countOccurrences(t, find);
                    rewriteParagraph(p, t.replace(find, replace));
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
        // 表格：单元格内也有段落，按表格定位，不做 skip 计数（单元格里少见超链接/域）
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
            p.createRun().setText(newText);
            return;
        }
        runs.get(0).setText(newText, 0);
        for (int k = 1; k < runs.size(); k++) runs.get(k).setText("", 0);
    }

    static int countOccurrences(String hay, String needle) {
        if (needle.isEmpty()) return 0;
        int c = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
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
