package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 文件操作Skill。允许LLM读写本地文件系统上的文件。
 * 函数名: file_operation
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public class TLFileOperationSkill extends TLBaseSkill {

    /** 允许操作的根目录（安全限制） */
    private String allowedRootPath = ".";
    /** 最大文件读取大小 */
    private int maxReadSize = 1024 * 1024; // 1MB
    /** 区间读（offset/limit）允许的最大文件大小——再大只能先 search 定位内容 */
    private int maxRangedReadSize = 16 * 1024 * 1024; // 16MB
    /** 区间读单行输出截断阈值（字符数） */
    private static final int MAX_LINE_CHARS = 4000;
    /** 区间读输出总长预算（字符数）——超出即截断并给续读提示 */
    private static final int MAX_RANGE_OUTPUT_CHARS = 20000;
    /** search/glob 默认排除的目录（逗号分隔；可用 params 的 excludeDirs 覆盖） */
    private static final String DEFAULT_EXCLUDE_DIRS = ".git,node_modules,target,.idea,.vscode,data";
    /** 病态正则逐行消化后的提示（整次搜索只要发生过跳过就附一次） */
    private static final String REGEX_SKIP_NOTE = "…(部分行因正则复杂度过高跳过)";

    public TLFileOperationSkill() {
        super();
    }

    public TLFileOperationSkill(String name) {
        super(name);
    }

    public TLFileOperationSkill(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (skillName == null || skillName.isEmpty())
            skillName = "file_operation";
        if (skillDescription == null || skillDescription.isEmpty())
            skillDescription = "Read, write and edit files on the local filesystem. Operations: read (pass offset/limit to read a RANGE of a large file, output has line numbers; the 'N| ' prefix is display-only - do not copy it into old_string), write, edit (exact old_string->new_string replace; add context if not unique; set replace_all; prefer edit over write for existing files - never rewrite a whole file for a small change), list, exists, delete, search (regex content search, optional glob filter, default skips .git/node_modules/target dirs), glob (find files by pattern like **/*.py). Set backup=true on write/edit to keep a <file>.bak. After changing code, run it to verify.";

        if (params != null) {
            if (params.get("allowedRootPath") != null)
                allowedRootPath = params.get("allowedRootPath");
            if (params.get("maxReadSize") != null) {
                try { maxReadSize = Integer.parseInt(params.get("maxReadSize")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("maxRangedReadSize") != null) {
                try { maxRangedReadSize = Integer.parseInt(params.get("maxRangedReadSize")); }
                catch (NumberFormatException ignored) {}
            }
        }

        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();
            Map<String, Object> opProp = new LinkedHashMap<>();
            opProp.put("type", "string");
            opProp.put("description", "Operation to perform: read, write, edit, list, exists, delete, search, glob");
            opProp.put("enum", new String[]{"read", "write", "edit", "list", "exists", "delete", "search", "glob"});
            opProp.put("required", true);
            parameterSchema.put("operation", opProp);

            Map<String, Object> pathProp = new LinkedHashMap<>();
            pathProp.put("type", "string");
            pathProp.put("description", "File or directory path (relative to allowed root)");
            pathProp.put("required", true);
            parameterSchema.put("path", pathProp);

            Map<String, Object> contentProp = new LinkedHashMap<>();
            contentProp.put("type", "string");
            contentProp.put("description", "Content to write (required for write operation)");
            parameterSchema.put("content", contentProp);

            parameterSchema.put("offset", prop("number", "read: first line to return, 1-based. Use with limit to read part of a large file."));
            parameterSchema.put("limit", prop("number", "read: max lines to return (default 500 when offset is given)."));
            parameterSchema.put("old_string", prop("string", "edit: exact existing text to replace (copy from a read; do not include the 'N| ' line-number prefix; must be unique unless replace_all)."));
            parameterSchema.put("new_string", prop("string", "edit: replacement text (pass empty string to delete)."));
            parameterSchema.put("replace_all", prop("boolean", "edit: replace every occurrence (default false)."));
            parameterSchema.put("backup", prop("boolean", "write/edit: copy the original to <file>.bak before modifying."));
            parameterSchema.put("pattern", prop("string", "search: Java regex. glob: path pattern like **/*.py (also accepts *.py)."));
            parameterSchema.put("glob", prop("string", "search: only search files matching this glob (default *)."));
            parameterSchema.put("max_results", prop("number", "search/glob: result cap (defaults 50 / 200)."));
        }
    }

    /** schema 属性小工厂：parameterSchema 里除 operation/path/content 外的单层 {type, description} 属性 */
    private static Map<String, Object> prop(String type, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", desc);
        return m;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        Map<String, Object> input = (Map<String, Object>) msg.getMapParam(AI_P_SKILLINPUT, new LinkedHashMap<>());
        if (input.isEmpty()) {
            input = new LinkedHashMap<>();
            if (msg.containsParam("operation")) input.put("operation", msg.getStringParam("operation", "read"));
            if (msg.containsParam("path")) input.put("path", msg.getStringParam("path", ""));
            if (msg.containsParam("content")) input.put("content", msg.getStringParam("content", ""));
        }

        // 三字段先取原始 Object 再字符串化：显式 JSON null / 非字符串（Gson 把 JSON 数字解析为 Double）若直接
        // (String) 强转，NPE/CCE 会逃出 execute，经框架异常链（无 exceptionHandler 时 moduleFactory.shutdown(-1)）
        // shutdown 整个应用。语义：缺键 operation→默认 "read"；显式 null→""（走"未知操作"优雅报错）；
        // 非字符串→String.valueOf 字符串化后走各自既有报错/正常路径
        Object opRaw = input.get("operation");
        String operation = (opRaw == null)
                ? (input.containsKey("operation") ? "" : "read")
                : String.valueOf(opRaw);
        Object pathRaw = input.get("path");
        String path = pathRaw == null ? "" : String.valueOf(pathRaw);            // 显式 null→""，走后端 "path is required" 优雅报错（path.isEmpty() 对 null 直接 NPE）
        Object contentRaw = input.get("content");
        String content = contentRaw == null ? "" : String.valueOf(contentRaw);   // 显式 null→""，防 getBytes NPE 把 JVM 内部文案泄给 LLM

        String op = operation.toLowerCase();
        boolean rootScoped = op.equals("search") || op.equals("glob");   // 全库导航：遍历 allowedRoot，不需要 path
        if (path.isEmpty() && !rootScoped) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error: path is required");
        }

        // 安全检查：确保路径在允许范围内
        try {
            Path allowedRoot = Paths.get(allowedRootPath).toAbsolutePath().normalize();
            Path resolvedPath = allowedRoot.resolve(path).normalize().toAbsolutePath();
            // 如果路径超出根目录，尝试用文件名部分在根目录下重建路径
            if (!resolvedPath.startsWith(allowedRoot)) {
                Path fileNamePath = resolvedPath.getFileName();
                if (fileNamePath == null) {
                    return createMsg().setParam(RESULT, false)
                            .setParam(AI_P_SKILLOUTPUT, "Error: path is outside allowed root: " + resolvedPath);
                }
                resolvedPath = allowedRoot.resolve(fileNamePath.toString());
            }

            switch (op) {
                case "search":
                    return doSearch(asString(input.getOrDefault("pattern", ""), ""),
                            asString(input.getOrDefault("glob", "*"), "*"),
                            toInt(input.get("max_results"), 50));
                case "glob":
                    return doGlob(asString(input.getOrDefault("pattern", ""), ""),
                            toInt(input.get("max_results"), 200));
                case "read": {
                    // 区分"没传"与"传了但非法"——非法值显式报错，不静默回落整文件读
                    Integer off = parseIntParam(input.get("offset"));
                    Integer lim = parseIntParam(input.get("limit"));
                    if (input.get("offset") != null && (off == null || off < 1))
                        return err("offset must be a positive integer (1-based line number), got '" + input.get("offset") + "'");
                    if (input.get("limit") != null && (lim == null || lim < 1))
                        return err("limit must be a positive integer, got '" + input.get("limit") + "'");
                    return doRead(resolvedPath,
                            off == null ? 0 : off,
                            lim == null ? 0 : lim);
                }
                case "write":
                    return doWrite(resolvedPath, content, parseBoolParam(input.get("backup")));
                case "edit":
                    return doEdit(resolvedPath,
                            asString(input.getOrDefault("old_string", ""), ""),
                            asString(input.get("new_string"), null),   // null=没传（不是删除）；非字符串→字符串化，显式 null 仍为 null 走 require 报错
                            parseBoolParam(input.get("replace_all")),
                            parseBoolParam(input.get("backup")));
                case "list":
                    return doList(resolvedPath);
                case "exists":
                    return doExists(resolvedPath);
                case "delete":
                    return doDelete(resolvedPath);
                default:
                    return createMsg().setParam(RESULT, false)
                            .setParam(AI_P_SKILLOUTPUT, "Unknown operation: " + operation);
            }
        } catch (Exception | IOError e) {
            // IOError 是 Error（父子句：path="a:b.py" 这类盘符相对路径，WindowsPath.toAbsolutePath 取不到 A: 的
            // 工作目录即抛 IOError），catch(Exception) 接不住，会逃出 execute 经框架链杀死应用。
            // getMessage() 为 null 时退化为类名，不把 "null" 泄给 LLM
            putLog("File Skill error: " + e.toString(), LogLevel.ERROR);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "File operation failed: "
                            + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private TLMsg doRead(Path path, int offset, int limit) {
        try {
            if (!Files.exists(path))
                return err("File not found: " + path);
            if (Files.isDirectory(path))
                return err("Path is a directory, use list operation: " + path);
            long fileSize = Files.size(path);
            boolean ranged = offset > 0 || limit > 0;
            if (!ranged && fileSize > maxReadSize)
                return err("File too large: " + fileSize + " bytes (max: " + maxReadSize
                        + "), use offset/limit to read in ranges");
            // 区间读同样要挡——readAllBytes 物化整个文件，超大文件会让 OOM 逃逸技能层 Exception 捕获
            if (ranged && fileSize > maxRangedReadSize)
                return err("File too large even for a ranged read: " + fileSize + " bytes (max: "
                        + maxRangedReadSize + "); use search to locate content first");
            String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            if (!ranged) {
                putLog("File read: " + path + " (" + fileSize + " bytes)", LogLevel.DEBUG);
                return ok(text);                                // 旧行为原样
            }
            String[] lines = text.split("\n", -1);
            int total = lines.length;
            if (total > 0 && lines[total - 1].isEmpty()) total--;  // 尾换行不额外算一行（120 行文件=共 120 行）
            int start = Math.max(1, offset > 0 ? offset : 1);
            if (start > total)
                return err("offset " + start + " beyond end of file " + path + " (total " + total + " lines)");
            int cnt = limit > 0 ? limit : 500;                  // 默认 500 行（≈2 万字符内，配合入史截断）
            int end = (int) Math.min((long) total, (long) start + cnt - 1);   // long 防 limit=MAX_VALUE 溢出倒挂
            StringBuilder sb = new StringBuilder();
            int shownTo = start - 1;
            for (int i = start; i <= end; i++) {
                String line = lines[i - 1];
                if (line.endsWith("\r"))                        // 只剥行尾 \r（CRLF），不碰行中间的 \r
                    line = line.substring(0, line.length() - 1);
                if (line.length() > MAX_LINE_CHARS)
                    line = line.substring(0, MAX_LINE_CHARS) + "…(行内容截断)";
                String entry = String.format(Locale.ROOT, "%5d| %s\n", i, line);
                if (sb.length() + entry.length() > MAX_RANGE_OUTPUT_CHARS) break;  // 输出预算，单行 entry ≤ ~4016 必然放得下
                sb.append(entry);
                shownTo = i;
            }
            sb.append(String.format(Locale.ROOT, "…(第 %d-%d 行，共 %d 行%s)", start, shownTo, total,
                    shownTo < total ? "；继续读用 offset=" + (shownTo + 1) : ""));
            putLog("File read: " + path + " (" + fileSize + " bytes, lines " + start + "-" + shownTo + "/" + total + ")", LogLevel.DEBUG);
            return ok(sb.toString());
        } catch (IOException e) {
            return err("Read error: " + e.getMessage());
        }
    }

    private TLMsg doEdit(Path path, String oldStr, String newStr, boolean replaceAll, boolean backup) {
        try {
            if (!Files.exists(path)) return err("File not found: " + path);
            if (Files.isDirectory(path)) return err("Path is a directory: " + path);
            if (oldStr == null || oldStr.isEmpty()) return err("old_string is required for edit");
            if (newStr == null) return err("new_string is required (pass an empty string to delete text)");
            long fileSize = Files.size(path);
            if (fileSize > maxRangedReadSize)
                return err("File too large to edit: " + fileSize + " bytes (max: " + maxRangedReadSize + ")");
            // 严格解码：非 UTF-8（GBK/GB18030 等）一律拒改——宽容解码会把整个文件转成 U+FFFD 写回，静默毁文件
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString();
            } catch (CharacterCodingException e) {
                return err("File is not valid UTF-8 (GBK/GB18030?): " + path + " —— convert it to UTF-8 first, nothing was written.");
            }
            // 行尾：混合（CRLF + 裸 LF）拒改；纯 CRLF 保持 CRLF，纯 LF 保持 LF
            boolean hasCrlf = text.contains("\r\n");
            boolean hasBareLf = text.replace("\r\n", "").contains("\n");
            if (hasCrlf && hasBareLf)
                return err("Mixed line endings (CRLF + LF) detected in " + path + "; normalize the file first, nothing was written.");
            String norm = text.replace("\r\n", "\n");           // 匹配按 LF 归一化
            String normOld = oldStr.replace("\r\n", "\n");
            String normNew = newStr.replace("\r\n", "\n");
            int count = countOccurrences(norm, normOld);
            if (count == 0)
                return err("old_string not found. Read the file first (read with offset) and copy the exact text, including indentation.");
            if (count > 1 && !replaceAll)
                return err("old_string appears " + count + " times (not unique). Add more surrounding context to make it unique, or set replace_all=true.");
            String replaced;
            if (replaceAll) {
                replaced = norm.replace(normOld, normNew);
            } else {
                int idx = norm.indexOf(normOld);
                replaced = norm.substring(0, idx) + normNew + norm.substring(idx + normOld.length());
            }
            String outText = hasCrlf ? replaced.replace("\n", "\r\n") : replaced;
            if (outText.equals(text))   // 行尾-only 编辑 / old==new：不是替换成功，绝不写盘
                return err("No change: old_string and new_string are identical after line-ending normalization; nothing was written.");
            boolean madeBackup = backup && Files.exists(path);   // 本次是否真备份（回执据此，不受盘上 stale .bak 影响）
            if (madeBackup) backupFile(path);
            atomicWrite(path, outText);
            putLog("File edited: " + path + " (" + count + " occurrence(s))", LogLevel.DEBUG);
            return ok("Edited: " + path + " —— 替换 " + (replaceAll ? count : 1) + " 处"
                    + (newStr.isEmpty() ? "（删除文本）" : "")
                    + (madeBackup ? "；backup: " + bakPath(path) : "")
                    + "；如改了代码，记得运行验证。");
        } catch (NoSuchFileException e) {
            return err("File not found: " + path);
        } catch (FileSystemException e) {
            // Windows 锁定/只读：原始消息会把 .editXXXX.tmp 内部临时名泄给 LLM 且无原因，换成可行动指令
            return err(fsError("Edit error", path, e, true));
        } catch (IOException e) {
            return err("Edit error: " + e.getMessage());
        }
    }

    private static int countOccurrences(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    /** 同目录临时文件 + 原子替换（word 技能 save 的同款模式；纯文本场景更简单） */
    private static void atomicWrite(Path target, String content) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, ".edit", ".tmp");
        try {
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }   // 清理异常不得吞掉 move 的原始异常
        }
    }

    /** 写类操作的 IOException 翻译：可行动文案 + 目标未被改动安抚；不得泄内部临时文件名 */
    private static String fsError(String op, Path path, IOException e, boolean targetUnchanged) {
        String msg;
        if (e instanceof FileSystemException)
            msg = op + ": file is locked or read-only (open in another program?), close it and retry: " + path;
        else
            msg = op + ": " + e.getMessage();
        if (targetUnchanged) msg += " (target was not modified)";
        return msg;
    }

    /** 备份目标路径：<file>.bak */
    private static Path bakPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".bak");
    }

    /** 写前备份：<file>.bak 同名覆盖（单份快照，语义同 word 技能 backup） */
    private static void backupFile(Path target) throws IOException {
        Files.copy(target, bakPath(target), StandardCopyOption.REPLACE_EXISTING);
    }

    private TLMsg ok(String s) { return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLOUTPUT, s); }

    private TLMsg err(String s) { return createMsg().setParam(RESULT, false).setParam(AI_P_SKILLOUTPUT, s); }

    private static int toInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString().trim()); } catch (NumberFormatException e) { return def; }
    }

    /** null 安全解析：Number/String 都认，解析失败或 null 返回 null（用于区分"没传"与"非法"） */
    private static Integer parseIntParam(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString().trim()); } catch (NumberFormatException e) { return null; }
    }

    /** 字符串 "true"/数字布尔也认（LLM 常把布尔参数写成字符串，直接 Boolean.TRUE.equals 会静默当 false） */
    private static boolean parseBoolParam(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return "true".equalsIgnoreCase(v.toString().trim());
    }

    private TLMsg doWrite(Path path, String content, boolean backup) {
        try {
            if (Files.isDirectory(path))
                return err("Path is a directory: " + path);
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            boolean madeBackup = backup && Files.exists(path);   // 本次是否真备份（回执据此，不受盘上 stale .bak 影响）
            if (madeBackup) backupFile(path);
            atomicWrite(path, content);
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            putLog("File written: " + path + " (" + contentBytes.length + " bytes)", LogLevel.DEBUG);
            return ok("File written successfully: " + path + " (" + contentBytes.length + " bytes)"
                    + (madeBackup ? "；backup: " + bakPath(path) : ""));
        } catch (NoSuchFileException e) {                 // 子类须先于 FileSystemException
            return err("File not found: " + path);
        } catch (FileSystemException e) {                 // 原子替换失败 ⇒ 目标必然未动；原始消息会泄 .edit*.tmp 名，禁直传
            return err(fsError("Write error", path, e, true));
        } catch (IOException e) {
            return err(fsError("Write error", path, e, false));
        }
    }

    private TLMsg doList(Path path) {
        try {
            if (!Files.exists(path)) {
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "Directory not found: " + path);
            }
            if (!Files.isDirectory(path)) {
                return doRead(path, 0, 0); // 如果是文件就读取
            }
            StringBuilder listing = new StringBuilder();
            listing.append("Directory listing for: ").append(path).append("\n");
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path entry : stream) {
                    String type = Files.isDirectory(entry) ? "[DIR] " : "[FILE] ";
                    listing.append(type).append(entry.getFileName()).append("\n");
                }
            }
            return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLOUTPUT, listing.toString());
        } catch (IOException e) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "List error: " + e.getMessage());
        }
    }

    private TLMsg doExists(Path path) {
        boolean exists = Files.exists(path);
        return createMsg().setParam(RESULT, true)
                .setParam(AI_P_SKILLOUTPUT, exists ? "File/directory exists: " + path : "File/directory does NOT exist: " + path);
    }

    private TLMsg doDelete(Path path) {
        try {
            if (!Files.exists(path)) {
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "File not found: " + path);
            }
            Files.delete(path);
            putLog("File deleted: " + path, LogLevel.DEBUG);
            return createMsg().setParam(RESULT, true)
                    .setParam(AI_P_SKILLOUTPUT, "File deleted: " + path);
        } catch (IOException e) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Delete error: " + e.getMessage());
        }
    }

    // ---- search / glob：代码库导航（走 allowedRoot，不按单文件 path 参数）----

    private TLMsg doSearch(String pattern, String globFilter, int maxResults) {
        try {
            if (pattern.isEmpty()) return err("pattern is required for search");
            Pattern re = Pattern.compile(pattern);
            String gf = (globFilter == null || globFilter.isEmpty()) ? "*" : globFilter;
            PathMatcher pm = FileSystems.getDefault().getPathMatcher("glob:" + gf);
            List<String> excludes = excludeSet();
            Path rootDir = allowedRoot();
            StringBuilder sb = new StringBuilder();
            int hits = 0;
            boolean skipped = false;   // 病态正则（StackOverflowError 是 Error）逐行消化的标记
            try (java.util.stream.Stream<Path> walk = Files.walk(rootDir)) {   // try-with-resources：不关会泄漏目录句柄
                java.util.Iterator<Path> it = walk.iterator();
                outer:
                while (it.hasNext()) {
                    Path p = it.next();
                    if (!Files.isRegularFile(p)) continue;
                    if (p.getFileName().toString().endsWith(".bak")) continue;
                    for (Path seg : rootDir.relativize(p))
                        if (excludes.contains(seg.toString())) continue outer;
                    if (!pm.matches(p.getFileName())) continue;
                    try {
                        if (Files.size(p) > maxRangedReadSize) continue;   // 巨文件跳过（防整文件读）；IOException 同样走"单文件跳过"不中断整次搜索
                        if (!looksText(p)) continue;
                        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size(); i++) {
                            String lineText = lines.get(i);
                            boolean matched;
                            try {
                                matched = re.matcher(lineText).find();
                            } catch (StackOverflowError ignored) {
                                // 病态正则（如 (x|x)*y 遇长行）爆栈是 StackOverflowError（Error 族），
                                // catch(Exception) 接不住会逃出 execute；这里逐行消化：该行跳过，后续行/文件继续。
                                // 只接 SOE 不接 Throwable：Throwable 会顺带吞掉 OOM——"继续循环申请内存"
                                // 把资源耗尽静默降级成"正常外观"的搜索结果
                                skipped = true;
                                continue;
                            }
                            if (matched) {
                                String line = lineText.trim();
                                if (line.length() > 120) line = line.substring(0, 120) + "…";
                                sb.append(rootDir.relativize(p).toString().replace('\\', '/'))
                                  .append(':').append(i + 1).append(": ").append(line).append('\n');
                                if (++hits >= maxResults) {
                                    sb.append("…(已达上限 ").append(maxResults).append(" 条；缩小 pattern/glob 或调大 max_results)");
                                    break outer;
                                }
                            }
                        }
                    } catch (Exception ignored) { }        // 单文件读失败不中断整次搜索
                }
            }
            if (hits == 0)
                return ok("(无命中) pattern=" + pattern + (skipped ? "\n" + REGEX_SKIP_NOTE : ""));
            if (skipped) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                sb.append(REGEX_SKIP_NOTE).append('\n');
            }
            return ok(sb.toString());
        } catch (Exception e) {
            return err("Search error: " + e.getMessage());
        }
    }

    private TLMsg doGlob(String pattern, int maxResults) {
        try {
            if (pattern.isEmpty()) return err("pattern is required for glob");
            PathMatcher pm = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<String> excludes = excludeSet();
            Path rootDir = allowedRoot();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            boolean budgetHit = false;
            try (java.util.stream.Stream<Path> walk = Files.walk(rootDir)) {
                java.util.Iterator<Path> it = walk.iterator();
                outer:
                while (it.hasNext()) {
                    Path p = it.next();
                    if (!Files.isRegularFile(p)) continue;
                    if (p.getFileName().toString().endsWith(".bak")) continue;
                    for (Path seg : rootDir.relativize(p))
                        if (excludes.contains(seg.toString())) continue outer;
                    if (!pm.matches(p) && !pm.matches(p.getFileName())) continue;   // **/*.py 与 *.py 都可用
                    String rel = rootDir.relativize(p).toString().replace('\\', '/');
                    if (sb.length() + rel.length() + 1 > MAX_RANGE_OUTPUT_CHARS) { budgetHit = true; break; }
                    sb.append(rel).append('\n');
                    if (++n >= maxResults) { sb.append("…(已达上限 ").append(maxResults).append(" 条)"); break; }
                }
            }
            if (n == 0 && !budgetHit) return ok("(无命中) pattern=" + pattern);
            if (budgetHit) sb.append("…(输出达长度上限，缩小 pattern)");
            return ok(sb.toString());
        } catch (Exception e) {
            return err("Glob error: " + e.getMessage());
        }
    }

    private Path allowedRoot() {
        return Paths.get(allowedRootPath).toAbsolutePath().normalize();
    }

    private List<String> excludeSet() {
        List<String> l = new ArrayList<>();
        String cfgEx = params != null ? params.get("excludeDirs") : null;
        for (String s : (cfgEx == null || cfgEx.isEmpty() ? DEFAULT_EXCLUDE_DIRS : cfgEx).split(","))
            if (!s.trim().isEmpty()) l.add(s.trim());
        return l;
    }

    /** 文本判定：前 8KB 无 \0 视为文本 */
    private static boolean looksText(Path p) {
        try (java.io.InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            for (int i = 0; i < n; i++) if (buf[i] == 0) return false;
            return true;
        } catch (java.io.IOException e) { return false; }
    }
}
