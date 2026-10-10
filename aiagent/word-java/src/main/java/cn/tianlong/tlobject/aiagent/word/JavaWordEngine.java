package cn.tianlong.tlobject.aiagent.word;

import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Word .docx 引擎（纯 POI，不依赖框架）。
 * 技能壳 TLWordJavaSkill 负责把消息翻成 (action, input)，本类只做文档操作。
 *
 * 两类动作、两种回执：
 *   读（read/outline/tables）→ Result.text（textMode=true，原文交给技能壳直出）；
 *   写（其余）→ JSON 回执 map（ok/action/path/bytes，replace 系另带 replaced/skipped）。
 *
 * 路径规则：裸文件名（不含 / 与 \）落 workDir；含分隔符的相对路径按 allowedRoot 解析；
 * 越出 allowedRoot 一律报错（不像旧 fileOperationSkill 那样悄悄改投裸名——那不可预测）。
 * 扩展名白名单只有 .docx，.doc/.docm 等给出明确提示。
 *
 * 创建日期：2026/10/07 作者:tianlong
 */
public class JavaWordEngine {

    public static class Config {
        public Path allowedRoot;
        public Path workDir;
        public boolean backup;            // 改动前是否留 .bak.docx（默认关，原子写已够安全）
        /** 中文排版（layout="chinese"，默认）：H1 居中 + 正文段落首行缩进 2 字符；false = plain */
        public boolean chineseLayout = true;
    }

    public static class Result {
        public boolean ok;
        public boolean textMode;          // true=读类，输出原始文本
        public String text;
        public Map<String, Object> json = new LinkedHashMap<>();

        public static Result text(String t) {
            Result r = new Result();
            r.ok = true; r.textMode = true; r.text = t;
            return r;
        }
        public static Result fail(String msg) {
            Result r = new Result();
            r.ok = false;
            r.json.put("ok", false);
            r.json.put("error", msg);
            return r;
        }
    }

    private final Config cfg;

    public JavaWordEngine(Config cfg) { this.cfg = cfg; }

    // ================= 分发 =================

    public Result execute(String action, Map<String, Object> input) {
        try {
            switch (action) {
                case "read":  return doRead(input);
                case "outline": return doText(input, "outline");
                case "tables":  return doText(input, "tables");
                case "info":    return doInfo(input);
                case "create":  return doCreate(input);
                case "append":  return doAppend(input);
                case "replace": return doReplace(input);
                case "set_paragraph":    return doSetParagraph(input);
                case "insert_paragraph": return doInsertParagraph(input);
                case "delete_paragraph": return doDeleteParagraph(input);
                case "set_table_cell":   return doSetTableCell(input);
                case "add_table_row":    return doAddTableRow(input);
                case "fill_template":    return doFillTemplate(input);
                default: return Result.fail("unknown action: " + action);
            }
        } catch (Exception e) {
            // 越界/校验失败/文件锁……一律翻成带类型的错误文案：模型读得到，就能自己改（例如换段落号）
            return Result.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ================= 路径安全 =================

    private Path resolve(String raw, boolean mustExist) throws IOException {
        if (raw == null || raw.trim().isEmpty()) throw new IOException("path is required");
        String s = raw.trim();
        boolean bare = s.indexOf('/') < 0 && s.indexOf('\\') < 0;
        Path p = bare ? cfg.workDir.resolve(s) : cfg.allowedRoot.resolve(s);
        p = p.normalize().toAbsolutePath();
        if (!p.startsWith(cfg.allowedRoot.toAbsolutePath().normalize()))
            throw new IOException("path outside allowed root (" + cfg.allowedRoot + "): " + raw);
        String lower = p.getFileName().toString().toLowerCase();
        if (!lower.endsWith(".docx"))
            throw new IOException("only .docx is supported: " + p.getFileName()
                    + (lower.endsWith(".doc") ? " — please save as .docx first" : ""));
        if (mustExist && !Files.isRegularFile(p))
            throw new IOException("file not found: " + p);
        return p;
    }

    private static String od(Map<String, Object> in, String k) {
        Object v = in.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static int oi(Map<String, Object> in, String k, int def) {
        Object v = in.get(k);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Exception e) { return def; }
    }

    /** 取必填文本参数。缺 key 视为调用错误——静默按空串处理会毁掉段落/单元格内容。
     *  显式传空串是允许的（清空是合法意图）。
     *  取值一律经 {@link #toDocString}：JSON 整数到这里已是 Double，String.valueOf 会写出 "100.0"。 */
    private static String requiredText(Map<String, Object> in) {
        if (!in.containsKey("text") || in.get("text") == null)
            throw new IllegalArgumentException(
                    "text is required (to clear the paragraph, pass text=\"\")");
        return toDocString(in.get("text"));
    }

    /**
     * 把入参值转成写入文档的字符串。
     *
     * 坑：provider 用 gson.fromJson(args, Map.class) 解析工具参数，JSON 整数一律变 Double，
     * 直接 String.valueOf 会往文档里写出 "100.0"。整值必须还原成 "100"。
     * 凡"会进文档"的入参一律经此：text / replace / values / data / content（少一处就是一条漏网）。
     */
    static String toDocString(Object v) {
        if (v == null) return "";
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return String.valueOf(d);
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
            return java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(v);
    }

    // ================= 读类动作 =================

    private Result doRead(Map<String, Object> in) throws IOException {
        Path p = resolve(od(in, "path"), true);
        try (XWPFDocument doc = open(p)) {
            return Result.text(WordTextExtractor.read(doc, oi(in, "from", 0), oi(in, "to", -1)));
        }
    }

    private Result doText(Map<String, Object> in, String kind) throws IOException {
        Path p = resolve(od(in, "path"), true);
        try (XWPFDocument doc = open(p)) {
            if ("outline".equals(kind)) return Result.text(WordTextExtractor.outline(doc));
            return Result.text(WordTextExtractor.tables(doc, oi(in, "index", -1)));
        }
    }

    private Result doInfo(Map<String, Object> in) throws IOException {
        Path p = resolve(od(in, "path"), true);
        try (XWPFDocument doc = open(p)) {
            Result r = new Result();
            r.ok = true;
            r.json.putAll(WordTextExtractor.info(doc));
            r.json.put("ok", true);
            r.json.put("path", p.toString());
            return r;
        }
    }

    // ================= 写类动作 =================

    private Result doCreate(Map<String, Object> in) throws IOException {
        Path p = resolve(od(in, "path"), false);
        boolean overwrite = Boolean.parseBoolean(String.valueOf(in.getOrDefault("overwrite", "false")));
        if (Files.exists(p) && !overwrite)
            return Result.fail("file exists (pass overwrite=true): " + p);
        Files.createDirectories(p.getParent());
        String content = toDocString(in.getOrDefault("content", ""));
        try (XWPFDocument doc = new XWPFDocument()) {
            WordMarkdownWriter.writeBlocks(doc, WordMarkdown.parse(content), cfg.chineseLayout);
            save(doc, p);
        }
        return receipt("create", p, null);
    }

    private Result doAppend(Map<String, Object> in) throws IOException {
        Path p = resolve(od(in, "path"), true);
        String content = toDocString(in.getOrDefault("content", ""));
        try (XWPFDocument doc = open(p)) {
            WordMarkdownWriter.writeBlocks(doc, WordMarkdown.parse(content), cfg.chineseLayout);
            save(doc, p);
        }
        return receipt("append", p, null);
    }

    private Result doReplace(Map<String, Object> in) throws IOException {
        Path p = resolve(od(in, "path"), true);
        String find = od(in, "find");
        if (find == null || find.isEmpty()) return Result.fail("find is required");
        // replace 是必填 key：getOrDefault("replace","") 会把"模型漏参数"和"显式删掉这段文字"
        // 当成同一件事——实测漏参数时 {"ok":true,"replaced":1} 而匹配处已被删空（F2 同类）。
        // 显式传空串仍然合法（删除是正常意图）。
        if (!in.containsKey("replace") || in.get("replace") == null)
            return Result.fail("replace is required (to delete the match, pass replace=\"\")");
        String repl = toDocString(in.get("replace"));
        try (XWPFDocument doc = open(p)) {
            WordTextEditor.ReplaceReport rep = WordTextEditor.replaceInDocument(
                    doc, find, repl, oi(in, "index", -1),
                    od(in, "mode") == null ? "preserve" : od(in, "mode"));
            if (rep.replaced == 0 && rep.skipped == 0)
                return Result.fail("no match for: " + find);
            save(doc, p);
            return receipt("replace", p, rep);
        }
    }

    private Result doSetParagraph(Map<String, Object> in) throws IOException {
        Path p = resolve(od(in, "path"), true);
        try (XWPFDocument doc = open(p)) {
            WordTextEditor.setParagraphText(doc, oi(in, "index", -1),
                    requiredText(in));
            save(doc, p);
        }
        return receipt("set_paragraph", p, null);
    }

    private Result doInsertParagraph(Map<String, Object> in) throws IOException {
        Path p = resolve(od(in, "path"), true);
        boolean before = !"after".equalsIgnoreCase(String.valueOf(in.getOrDefault("position", "before")));
        try (XWPFDocument doc = open(p)) {
            WordTextEditor.insertParagraph(doc, oi(in, "index", -1),
                    requiredText(in), od(in, "style"), before, cfg.chineseLayout);
            save(doc, p);
        }
        return receipt("insert_paragraph", p, null);
    }

    private Result doDeleteParagraph(Map<String, Object> in) throws IOException {
        Path p = resolve(od(in, "path"), true);
        try (XWPFDocument doc = open(p)) {
            WordTextEditor.deleteParagraph(doc, oi(in, "index", -1));
            save(doc, p);
        }
        return receipt("delete_paragraph", p, null);
    }

    private Result doSetTableCell(Map<String, Object> in) throws IOException {
        Path p = resolve(od(in, "path"), true);
        try (XWPFDocument doc = open(p)) {
            WordTextEditor.setTableCell(doc, oi(in, "table", -1), oi(in, "row", -1),
                    oi(in, "col", -1), requiredText(in));
            save(doc, p);
        }
        return receipt("set_table_cell", p, null);
    }

    private Result doAddTableRow(Map<String, Object> in) throws IOException {
        Path p = resolve(od(in, "path"), true);
        Object v = in.get("values");
        String[] vals = null;
        if (v instanceof List) {
            List<?> l = (List<?>) v;
            vals = new String[l.size()];
            for (int i = 0; i < l.size(); i++) vals[i] = toDocString(l.get(i));
        } else if (v != null && !String.valueOf(v).trim().isEmpty()) {
            vals = String.valueOf(v).split(",", -1);
        }
        try (XWPFDocument doc = open(p)) {
            WordTextEditor.addTableRow(doc, oi(in, "table", -1), vals);
            save(doc, p);
        }
        return receipt("add_table_row", p, null);
    }

    @SuppressWarnings("unchecked")
    private Result doFillTemplate(Map<String, Object> in) throws IOException {
        Path src = resolve(od(in, "path"), true);
        Path dst = od(in, "output") == null ? src : resolve(od(in, "output"), false);
        Map<String, String> data = new LinkedHashMap<>();
        Object raw = in.get("data");
        if (raw instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) raw).entrySet())
                data.put(e.getKey(), toDocString(e.getValue()));
        }
        try (XWPFDocument doc = open(src)) {
            WordTextEditor.ReplaceReport rep = WordTextEditor.fillTemplate(doc, data);
            save(doc, dst);
            return receipt("fill_template", dst, rep);
        }
    }

    // ================= 文件生命周期 =================

    private XWPFDocument open(Path p) throws IOException {
        try {
            return new XWPFDocument(new ByteArrayInputStream(Files.readAllBytes(p)));
        } catch (org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException e) {
            throw new IOException("not a .docx file (corrupt, or a .doc renamed?): " + p);
        } catch (org.apache.poi.ooxml.POIXMLException e) {
            throw new IOException("document is corrupt: " + p + " (" + e.getMessage() + ")");
        }
    }

    /** 原子写：先写 .tmp 再 move，保证要么改成功要么原文件不动 */
    private void save(XWPFDocument doc, Path p) throws IOException {
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        try {
            if (cfg.backup && Files.isRegularFile(p)) {
                String n = p.getFileName().toString();
                Path bak = p.resolveSibling(n.substring(0, n.length() - 5) + ".bak.docx");
                Files.copy(p, bak, StandardCopyOption.REPLACE_EXISTING);
            }
            try (OutputStream o = Files.newOutputStream(tmp)) { doc.write(o); }
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (FileSystemException e) {
            // Word 打开着文件时持有写锁；说清楚让模型/用户能自己解决
            throw new IOException("cannot write " + p + " — the file is locked (is it open in Word?), "
                    + "please close it and retry");
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    private Result receipt(String action, Path p, WordTextEditor.ReplaceReport rep) {
        Result r = new Result();
        r.ok = true;
        r.json.put("ok", true);
        r.json.put("action", action);
        r.json.put("path", p.toString());
        try { r.json.put("bytes", Files.size(p)); } catch (IOException ignored) {}
        if (rep != null) {
            r.json.put("replaced", rep.replaced);
            r.json.put("skipped", rep.skipped);
            if (!rep.skippedReasons.isEmpty()) r.json.put("skippedReasons", rep.skippedReasons);
        }
        return r;
    }
}
