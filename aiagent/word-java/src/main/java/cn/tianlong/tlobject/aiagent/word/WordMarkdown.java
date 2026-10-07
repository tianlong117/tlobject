package cn.tianlong.tlobject.aiagent.word;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 子集 → 块模型。纯字符串处理，不依赖 POI，可独立单测。
 *
 * 支持：
 *   标题   # ~ ######
 *   列表   - / * 开头（无序）、1. 开头（有序）
 *   表格   | a | b | 连续行
 *   分页   --- （独占一行）
 *   段落   其余非空行，空行分段
 *   行内   **粗体**
 *
 * 创建日期：2026/10/07 作者:tianlong
 */
public final class WordMarkdown {

    public static class Span {
        public String text;
        public boolean bold;
        public Span(String t, boolean b) { text = t; bold = b; }
    }

    public static class Block {
        public enum Type { HEADING, PARAGRAPH, BULLET, NUMBERED, TABLE, PAGEBREAK }
        public Type type;
        public int level;                    // HEADING 的级别 1-6
        public List<Span> spans = new ArrayList<>();
        public List<List<String>> rows = new ArrayList<>();
    }

    private WordMarkdown() {}

    public static List<Block> parse(String md) {
        List<Block> out = new ArrayList<>();
        if (md == null) return out;
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) { i++; continue; }

            // 分页
            if (trimmed.equals("---")) {
                out.add(newBlock(Block.Type.PAGEBREAK));
                i++; continue;
            }
            // 表格：连续以 | 开头的行
            if (trimmed.startsWith("|")) {
                List<List<String>> rows = new ArrayList<>();
                while (i < lines.length && lines[i].trim().startsWith("|")) {
                    List<String> cells = splitRow(lines[i].trim());
                    if (!isSeparatorRow(cells)) rows.add(cells);
                    i++;
                }
                Block b = newBlock(Block.Type.TABLE);
                b.rows = rows;
                out.add(b);
                continue;
            }
            // 标题
            if (trimmed.startsWith("#")) {
                int lv = 0;
                while (lv < trimmed.length() && trimmed.charAt(lv) == '#') lv++;
                if (lv <= 6 && lv < trimmed.length() && trimmed.charAt(lv) == ' ') {
                    Block b = newBlock(Block.Type.HEADING);
                    b.level = lv;
                    b.spans = inline(trimmed.substring(lv + 1).trim());
                    out.add(b);
                    i++; continue;
                }
            }
            // 无序列表
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                Block b = newBlock(Block.Type.BULLET);
                b.spans = inline(trimmed.substring(2).trim());
                out.add(b);
                i++; continue;
            }
            // 有序列表
            if (isOrdered(trimmed)) {
                int dot = trimmed.indexOf('.');
                Block b = newBlock(Block.Type.NUMBERED);
                b.spans = inline(trimmed.substring(dot + 1).trim());
                out.add(b);
                i++; continue;
            }
            // 段落：吃到空行为止（同段多行用空格接）
            StringBuilder sb = new StringBuilder(trimmed);
            i++;
            while (i < lines.length) {
                String nxt = lines[i].trim();
                if (nxt.isEmpty() || nxt.startsWith("|") || nxt.startsWith("#")
                        || nxt.startsWith("- ") || nxt.startsWith("* ")
                        || nxt.equals("---") || isOrdered(nxt)) break;
                sb.append(' ').append(nxt);
                i++;
            }
            Block b = newBlock(Block.Type.PARAGRAPH);
            b.spans = inline(sb.toString());
            out.add(b);
        }
        return out;
    }

    private static Block newBlock(Block.Type t) {
        Block b = new Block();
        b.type = t;
        return b;
    }

    private static boolean isOrdered(String s) {
        int dot = s.indexOf('.');
        if (dot <= 0 || dot + 1 >= s.length() || s.charAt(dot + 1) != ' ') return false;
        for (int k = 0; k < dot; k++) if (!Character.isDigit(s.charAt(k))) return false;
        return true;
    }

    /** | a | b | → ["a","b"]；首尾空段因起止竖线被丢弃 */
    private static List<String> splitRow(String s) {
        List<String> cells = new ArrayList<>();
        for (String c : s.split("\\|", -1)) cells.add(c.trim());
        if (!cells.isEmpty() && cells.get(0).isEmpty()) cells.remove(0);
        if (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) cells.remove(cells.size() - 1);
        return cells;
    }

    /** 表格分隔行 |---|---| 要丢弃 */
    private static boolean isSeparatorRow(List<String> cells) {
        if (cells.isEmpty()) return false;
        for (String c : cells) {
            String t = c.replace("-", "").replace(":", "").trim();
            if (!t.isEmpty()) return false;
        }
        return true;
    }

    /** 行内 **粗体** → spans */
    private static List<Span> inline(String s) {
        List<Span> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            int open = s.indexOf("**", i);
            if (open < 0) { out.add(new Span(s.substring(i), false)); break; }
            int close = s.indexOf("**", open + 2);
            if (close < 0) { out.add(new Span(s.substring(i), false)); break; }
            if (open > i) out.add(new Span(s.substring(i, open), false));
            out.add(new Span(s.substring(open + 2, close), true));
            i = close + 2;
        }
        if (out.isEmpty()) out.add(new Span("", false));
        return out;
    }
}
