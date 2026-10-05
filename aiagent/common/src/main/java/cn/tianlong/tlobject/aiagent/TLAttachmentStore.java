package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/**
 * 附件登记模块：图片/文件的落盘、去重、元数据本地抽取与清理。
 * 本体是模块（配置进 XML，工厂单例）；两个静态方法供 provider 热路径零往返调用。
 *
 * 存储布局：
 *   data/{uid}/attachments/auto/{hash[0:2]}/{hash}.{ext}   自动产物（截图/base64 输入），内容哈希去重
 *   data/{uid}/uploads/...                                 用户上传件原地引用，不复制
 *
 * 创建日期：2026/10/5
 * 作者:tianlong
 */
public class TLAttachmentStore extends TLBaseModule implements TLAiAgentParamString {

    private String dataBasePath = "./data/";
    private int maxImageMB = 32;
    private int retentionDays = 7;
    private int storeMaxMB = 500;

    public TLAttachmentStore() { super(); }
    public TLAttachmentStore(String name) { super(name); }
    public TLAttachmentStore(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("dataBasePath") != null) dataBasePath = params.get("dataBasePath");
            maxImageMB = intParam("maxImageMB", maxImageMB);
            retentionDays = intParam("attachmentRetentionDays", retentionDays);
            storeMaxMB = intParam("attachmentStoreMaxMB", storeMaxMB);
        }
    }

    private int intParam(String key, int def) {
        if (params == null || params.get(key) == null) return def;
        try { return Integer.parseInt(params.get(key)); } catch (NumberFormatException e) { return def; }
    }

    @Override
    protected TLBaseModule init() { return this; }

    @Override
    public void runStartMsg() {          // ⚠ 基类签名为无参 void，不是 TLMsg
        super.runStartMsg();
        // 启动清理：所有用户目录各清一次（失败不影响启动）
        try {
            File root = new File(dataBasePath);
            File[] users = root.listFiles(File::isDirectory);
            if (users != null) for (File u : users) cleanupUser(u.getName());
        } catch (Exception e) {
            putLog("attachment cleanup on start failed: " + e, LogLevel.WARN);
        }
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case ATTACH_REGISTER:    return register(fromWho, msg);
            case ATTACH_STOREBASE64: return storeBase64(fromWho, msg);
            case ATTACH_CLEANUP:     return cleanup(fromWho, msg);
            default: return null;
        }
    }

    // ======================== 登记 ========================

    @SuppressWarnings("unchecked")
    protected TLMsg register(Object fromWho, TLMsg msg) {
        Map<String, Object> cfg = (Map<String, Object>) msg.getParam("config", new LinkedHashMap<>());
        String userId = msg.getStringParam(AI_P_USERID, "default");
        String path = str(cfg.get("path"));
        String name = str(cfg.get("name"));
        String origin = str(cfg.get("origin"));
        if (path.isEmpty()) return fail("path 为空");

        File f = new File(path);
        if (!f.isAbsolute()) f = new File(userRoot(userId), path);
        if (!f.isFile()) return fail("文件不存在: " + path);
        // 越界判定必须收在本用户目录内（data/{uid}/）：只校验 data/ 会让调用方指向别人的目录
        if (!isUnder(f, userRoot(userId))) return fail("路径越界: " + path);
        if (name.isEmpty()) name = f.getName();

        Map<String, Object> sn = sniff(f);
        String mime = sn != null ? (String) sn.get("mime") : guessMime(f.getName());
        long limit = (long) maxImageMB * 1024 * 1024;
        if (sn != null && f.length() > limit) return fail("图片超过 " + maxImageMB + "MB: " + name);

        TLAttachmentRef ref = sn != null
                ? TLAttachmentRef.forImage(f.getAbsolutePath(), name, mime, origin)
                : TLAttachmentRef.forFile(f.getAbsolutePath(), name, mime, origin);
        ref.setSize(f.length());
        Map<String, Object> meta = new LinkedHashMap<>();
        if (sn != null) { meta.put("width", sn.get("width")); meta.put("height", sn.get("height")); }
        Map<String, Object> extra = extractMeta(f, mime);
        if (extra != null) meta.putAll(extra);
        if (!meta.isEmpty()) ref.setMeta(meta);

        putLog("attach register: " + name + " (" + mime + ", " + f.length() + "B) -> " + ref.getKind(),
                LogLevel.DEBUG);
        return createMsg().setParam(RESULT, true).setParam("ref", ref);
    }

    protected TLMsg storeBase64(Object fromWho, TLMsg msg) {
        String userId = msg.getStringParam(AI_P_USERID, "default");
        String b64 = msg.getStringParam("base64", "");
        String name = msg.getStringParam("name", "image");
        String origin = msg.getStringParam("origin", "api");
        if (b64.isEmpty()) return fail("base64 为空");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(stripDataUrl(b64)); }
        catch (IllegalArgumentException e) { return fail("base64 解码失败"); }
        long limit = (long) maxImageMB * 1024 * 1024;
        if (bytes.length > limit) return fail("图片超过 " + maxImageMB + "MB");
        try {
            String hash = sha256(bytes);
            Map<String, Object> sn = sniffBytes(bytes);
            if (sn == null) return fail("不是支持的图片格式（PNG/JPEG/GIF/WebP）");
            String ext = extFor((String) sn.get("mime"));
            File dir = new File(userRoot(userId), "attachments/auto/" + hash.substring(0, 2));
            File f = new File(dir, hash + "." + ext);
            if (!f.exists()) {   // 内容寻址：同内容天然去重
                dir.mkdirs();
                Files.write(f.toPath(), bytes);
            }
            TLAttachmentRef ref = TLAttachmentRef.forImage(f.getAbsolutePath(), name,
                    (String) sn.get("mime"), origin);
            ref.setSize(bytes.length);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("width", sn.get("width")); meta.put("height", sn.get("height"));
            ref.setMeta(meta);
            return createMsg().setParam(RESULT, true).setParam("ref", ref);
        } catch (Exception e) {
            putLog("attach store failed: " + e, LogLevel.ERROR);
            return fail("落盘失败: " + e.getMessage());
        }
    }

    protected TLMsg cleanup(Object fromWho, TLMsg msg) {
        int n = cleanupUser(msg.getStringParam(AI_P_USERID, "default"));
        return createMsg().setParam(RESULT, true).setParam("removed", n);
    }

    // ======================== 静态工具（provider 热路径） ========================

    /** 读附件字节；文件缺失返回 null（调用方降级，不抛） */
    public static byte[] readBytes(TLAttachmentRef ref) {
        if (ref == null || ref.getPath() == null) return null;
        File f = new File(ref.getPath());
        if (!f.isFile()) return null;
        try { return Files.readAllBytes(f.toPath()); }
        catch (IOException e) { return null; }
    }

    /** 魔数嗅探：PNG/JPEG/GIF/WebP；返回 {mime,width,height}，非图片返回 null。尺寸读不到时为 0 */
    public static Map<String, Object> sniff(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] head = new byte[16];
            int n = raf.read(head);
            if (n < 12) return null;
            String mime = magicMime(head);
            if (mime == null) return null;
            int w = 0, h = 0;
            try {
                BufferedImage img = ImageIO.read(f);   // JDK 内置：PNG/JPEG/GIF 可读，WebP 不可
                if (img != null) { w = img.getWidth(); h = img.getHeight(); }
            } catch (Exception ignored) {}
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mime", mime); m.put("width", w); m.put("height", h);
            return m;
        } catch (IOException e) {
            return null;
        }
    }

    private static Map<String, Object> sniffBytes(byte[] b) {
        if (b.length < 12) return null;
        byte[] head = Arrays.copyOf(b, 16);
        String mime = magicMime(head);
        if (mime == null) return null;
        int w = 0, h = 0;
        try {
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(b));
            if (img != null) { w = img.getWidth(); h = img.getHeight(); }
        } catch (Exception ignored) {}
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mime", mime); m.put("width", w); m.put("height", h);
        return m;
    }

    private static String magicMime(byte[] h) {
        if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G')
            return "image/png";
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF)
            return "image/jpeg";
        if (h.length >= 6 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F')
            return "image/gif";
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P')
            return "image/webp";
        return null;
    }

    /**
     * 本地抽取元数据（不调 LLM，失败静默返回 null）：
     * 文本/CSV → rows/chars/columns；PDF → pages（粗估）。
     */
    public static Map<String, Object> extractMeta(File f, String mime) {
        try {
            String lower = f.getName().toLowerCase();
            boolean textish = (mime != null && mime.startsWith("text/"))
                    || lower.endsWith(".csv") || lower.endsWith(".txt") || lower.endsWith(".md")
                    || lower.endsWith(".json") || lower.endsWith(".log") || lower.endsWith(".tsv");
            if (textish && f.length() < 256L * 1024 * 1024) {
                String content = new String(Files.readAllBytes(f.toPath()), "UTF-8");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("chars", content.length());
                // 不用 -1 上限：尾部换行产生的空串不计入行数
                String[] lines = content.isEmpty() ? new String[0] : content.split("\r?\n");
                m.put("rows", lines.length);
                if ((lower.endsWith(".csv") || lower.endsWith(".tsv")) && lines.length > 0) {
                    String sep = lower.endsWith(".tsv") ? "\t" : ",";
                    String[] cols = lines[0].split(java.util.regex.Pattern.quote(sep), -1);
                    if (cols.length > 1 && cols.length <= 64) m.put("columns", String.join(",", cols));
                }
                return m;
            }
            if (lower.endsWith(".pdf")) {
                byte[] all = Files.readAllBytes(f.toPath());
                String s = new String(all, "ISO-8859-1");
                int pages = 0, idx = 0;
                while ((idx = s.indexOf("/Type", idx)) >= 0) {
                    if (s.startsWith("/Page", idx + 5) && !s.startsWith("/Pages", idx + 5)) pages++;
                    idx += 5;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("pages", pages);   // 粗估：无 xref 解析
                return m;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ======================== 清理 ========================

    private int cleanupUser(String userId) {
        int removed = 0;
        long cutoff = System.currentTimeMillis() - (long) retentionDays * 24 * 3600 * 1000;
        for (String sub : new String[]{"attachments", "uploads"}) {
            File dir = new File(userRoot(userId), sub);
            List<File> files = new ArrayList<>();
            collect(dir, files);
            for (File f : files)
                if (f.lastModified() < cutoff && f.delete()) removed++;
        }
        // 总量上限：按 mtime LRU 淘汰（只统计两个附件目录）
        List<File> all = new ArrayList<>();
        collect(new File(userRoot(userId), "attachments"), all);
        collect(new File(userRoot(userId), "uploads"), all);
        long total = 0;
        for (File f : all) total += f.length();
        long cap = (long) storeMaxMB * 1024 * 1024;
        if (total > cap) {
            all.sort(Comparator.comparingLong(File::lastModified));
            for (File f : all) {
                if (total <= cap) break;
                long len = f.length();
                if (f.delete()) { total -= len; removed++; }
            }
        }
        return removed;
    }

    private static void collect(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out);
            else out.add(f);
        }
    }

    // ======================== 辅助 ========================

    private File userRoot(String userId) {
        File root = new File(dataBasePath, safe(userId));
        // 防御：safe() 不过滤点号，"." / ".." 会让 root 塌陷成 data/ 甚至仓库根，越过 per-user 隔离
        try {
            String base = new File(dataBasePath).getCanonicalPath() + File.separator;
            if (!(root.getCanonicalPath() + File.separator).startsWith(base)) {
                putLog("attach: 非法 userId，回落到 default: " + userId, LogLevel.WARN);
                return new File(dataBasePath, "default");
            }
        } catch (Exception e) {
            return new File(dataBasePath, "default");
        }
        return root;
    }

    private static String safe(String s) {
        if (s == null || s.isEmpty()) return "default";
        String r = s.replaceAll("[\\\\/:*?\"<>|]", "_");
        // 第二层：点号不在过滤集里，纯点号名（"." / ".."）会让 userRoot 塌陷/上跳
        if (r.isEmpty() || ".".equals(r) || "..".equals(r)) return "default";
        return r;
    }

    private static boolean isUnder(File f, File root) {
        try {
            return f.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator);
        } catch (IOException e) { return false; }
    }

    private static String sha256(byte[] b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest(b)) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String extFor(String mime) {
        switch (mime) {
            case "image/png": return "png";
            case "image/jpeg": return "jpg";
            case "image/gif": return "gif";
            case "image/webp": return "webp";
            default: return "bin";
        }
    }

    private static String guessMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".md")) return "text/markdown";
        if (n.endsWith(".txt") || n.endsWith(".log")) return "text/plain";
        if (n.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    private static String stripDataUrl(String b64) {
        int comma = b64.indexOf(',');
        return b64.startsWith("data:") && comma > 0 ? b64.substring(comma + 1) : b64;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private TLMsg fail(String reason) {
        putLog("attach: " + reason, LogLevel.WARN);
        return createMsg().setParam(RESULT, false).setParam("error", reason);
    }
}
