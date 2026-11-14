package cn.tianlong.tlobject.aiagent.webui;

import cn.tianlong.tlobject.aiagent.TLUserWorkspace;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 工作空间文件管理（管理页面用）：按目录浏览、建文件夹、删除文件。
 *
 * <p>安全边界与下载一致：只允许 {@code workspace/{userId}} 内、真实路径仍在 workspace 的
 * 普通文件/目录；不跟随软链（目录项用 NOFOLLOW_LINKS 跳过 symlink，路径用 realpath 校验）。
 * 目录浏览只列当前目录的直接子项（文件 + 文件夹），子文件夹点进去再看；单层最多
 * {@link #MAX_ENTRIES} 条。
 */
public final class TLWorkspaceService {

    public static final int MAX_ENTRIES = 2000;

    private TLWorkspaceService() { }

    public static final class Listing {
        public final String dir;       // 当前目录相对 workspace 根（"" = 根）
        public final String parent;    // 上级目录相对根（"" = 根）
        public final List<Map<String, Object>> entries;
        public final boolean truncated;
        Listing(String dir, String parent, List<Map<String, Object>> entries, boolean truncated) {
            this.dir = dir;
            this.parent = parent;
            this.entries = entries;
            this.truncated = truncated;
        }
    }

    /** 兼容旧调用：列 workspace 根。 */
    public static Listing list(String userId) {
        return list(userId, "");
    }

    /**
     * 列当前目录直接子项（文件夹 + 普通文件），目录在前、名称排序。
     * dir 为空 = workspace 根；越权/不存在/不是目录返回 null。
     */
    public static Listing list(String userId, String rawDir) {
        Path dir = resolveDir(userId, rawDir);
        if (dir == null) {
            // 新用户还没产生任何文件（workspace 根不存在）：管理页首次打开应为空列表，
            // 不是“加载失败”。只在列根目录时如此；指定子目录不存在仍返回 null。
            if (userId != null && (rawDir == null || rawDir.trim().isEmpty())
                    && !Files.exists(TLUserWorkspace.userRoot(userId), LinkOption.NOFOLLOW_LINKS)) {
                return new Listing("", "", new ArrayList<>(), false);
            }
            return null;
        }
        Path root;
        try {
            root = TLUserWorkspace.userRoot(userId).toRealPath();
        } catch (Exception e) {
            return null;
        }
        String dirRel = rel(root, dir);
        String parentRel = dirRel.isEmpty() ? "" : rel(root, dir.getParent());
        List<Map<String, Object>> entries = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            children.forEach(p -> {
                try {
                    String name = p.getFileName() == null ? "" : p.getFileName().toString();
                    if (name.isEmpty() || Files.isSymbolicLink(p)) return;   // 不列 symlink，防逃逸
                    boolean isDir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);
                    boolean isFile = Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS);
                    if (!isDir && !isFile) return;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", isDir ? "dir" : "file");
                    m.put("path", rel(root, p));
                    m.put("name", name);
                    if (isFile) m.put("size", Files.size(p));
                    m.put("mtime", Files.getLastModifiedTime(p).toMillis());
                    entries.add(m);
                } catch (Exception ignored) {
                    // 列目录期间被删/无权限：跳过该条
                }
            });
        } catch (Exception ignored) {
            // 目录遍历失败：按空列表返回
        }
        entries.sort((a, b) -> {
            boolean ad = "dir".equals(a.get("type")), bd = "dir".equals(b.get("type"));
            if (ad != bd) return ad ? -1 : 1;
            return String.valueOf(a.get("name"))
                    .compareToIgnoreCase(String.valueOf(b.get("name")));
        });
        boolean truncated = entries.size() > MAX_ENTRIES;
        List<Map<String, Object>> result = truncated
                ? new ArrayList<>(entries.subList(0, MAX_ENTRIES)) : entries;
        return new Listing(dirRel, parentRel, result, truncated);
    }

    /** 解析当前用户 workspace 内目录（realpath 校验）；空 = 根；非目录/越权返回 null。 */
    public static Path resolveDir(String userId, String rawDir) {
        if (userId == null) return null;
        Path root = TLUserWorkspace.userRoot(userId);
        if (!Files.isDirectory(root)) return null;
        Path p;
        try {
            p = (rawDir == null || rawDir.trim().isEmpty())
                    ? root : root.resolve(rawDir.trim()).normalize();
        } catch (Exception e) {
            return null;
        }
        try {
            Path realRoot = root.toRealPath();
            Path real = p.toRealPath();
            if (!real.startsWith(realRoot)) return null;
            if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) return null;
            return real;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析目录，根目录不存在时按需创建；指定子目录不自动创建（不存在返回 null）。
     * 用于新用户首次上传/建文件夹——workspace 根还没建时不应报“目录无权访问”。
     */
    public static Path ensureDir(String userId, String rawDir) {
        Path dir = resolveDir(userId, rawDir);
        if (dir != null) return dir;
        if (userId == null || (rawDir != null && !rawDir.trim().isEmpty())) return null;
        try {
            Files.createDirectories(TLUserWorkspace.userRoot(userId));
        } catch (Exception e) {
            return null;
        }
        return resolveDir(userId, "");
    }

    /** 在当前目录下建文件夹；name 必须是单段名（拒绝 / \ . ..），已存在/非法返回 null。 */
    public static Path mkdir(String userId, String rawDir, String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty() || ".".equals(n) || "..".equals(n)
                || n.indexOf('/') >= 0 || n.indexOf('\\') >= 0 || n.indexOf('\0') >= 0)
            return null;
        Path parent = ensureDir(userId, rawDir);
        if (parent == null) return null;
        Path target = parent.resolve(n).normalize();
        if (!parent.equals(target.getParent())) return null;   // 防多段名
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            Files.createDirectory(target);
            return target;
        } catch (Exception e) {
            return null;
        }
    }

    /** 删除当前用户 workspace 内的普通文件；目录/越权/不存在返回 false。 */
    public static boolean delete(String userId, String rawPath) {
        if (userId == null || rawPath == null || rawPath.trim().isEmpty()) return false;
        File f = TLDownloadService.resolve(userId, rawPath);
        if (f == null) return false;
        try {
            Path p = f.toPath();
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return false;
            Files.delete(p);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 批量删除：逐条走同一安全校验；返回成功删除条数（部分失败不影响其余）。 */
    public static int deleteAll(String userId, List<String> paths) {
        if (paths == null || paths.isEmpty()) return 0;
        int deleted = 0;
        for (String p : paths) if (delete(userId, p)) deleted++;
        return deleted;
    }

    /** 相对 workspace 根的路径（统一 /，根返回 ""）。 */
    private static String rel(Path root, Path p) {
        try {
            return root.relativize(p).toString().replace('\\', '/');
        } catch (Exception e) {
            return "";
        }
    }
}
