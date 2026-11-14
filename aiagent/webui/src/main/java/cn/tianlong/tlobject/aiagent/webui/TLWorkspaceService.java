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
 * 工作空间文件管理（管理页面用）：列出当前用户 workspace 内的文件、删除文件。
 *
 * <p>安全边界与下载一致：只允许 {@code workspace/{userId}} 内、真实路径仍在 workspace 的
 * 普通文件；不跟随软链/目录联接（list 用 NOFOLLOW_LINKS，delete 复用
 * {@link TLDownloadService#resolve} 的 realpath 校验）。只列文件不列目录；子目录文件用
 * 相对路径展示/删除。单次最多返回 {@link #MAX_FILES} 条，超出时 truncated=true。
 */
public final class TLWorkspaceService {

    public static final int MAX_FILES = 2000;

    private TLWorkspaceService() { }

    public static final class Listing {
        public final List<Map<String, Object>> files;
        public final boolean truncated;
        Listing(List<Map<String, Object>> files, boolean truncated) {
            this.files = files;
            this.truncated = truncated;
        }
    }

    /** 列出当前用户 workspace 内的普通文件（相对路径；按修改时间倒序）。 */
    public static Listing list(String userId) {
        List<Map<String, Object>> files = new ArrayList<>();
        Path root = TLUserWorkspace.userRoot(userId);
        if (!Files.isDirectory(root)) return new Listing(files, false);
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .forEach(p -> {
                        try {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("path", root.relativize(p).toString().replace('\\', '/'));
                            m.put("name", p.getFileName() == null ? "" : p.getFileName().toString());
                            m.put("size", Files.size(p));
                            m.put("mtime", Files.getLastModifiedTime(p).toMillis());
                            files.add(m);
                        } catch (Exception ignored) {
                            // 列文件期间被删/无权限：跳过该条
                        }
                    });
        } catch (Exception ignored) {
            // 根目录遍历失败：按空列表返回
        }
        files.sort((a, b) -> Long.compare(
                ((Number) b.get("mtime")).longValue(), ((Number) a.get("mtime")).longValue()));
        boolean truncated = files.size() > MAX_FILES;
        List<Map<String, Object>> result = truncated
                ? new ArrayList<>(files.subList(0, MAX_FILES)) : files;
        return new Listing(result, truncated);
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
}
