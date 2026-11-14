package cn.tianlong.tlobject.aiagent.webui;

import cn.tianlong.tlobject.aiagent.TLUserWorkspace;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 生成文件下载的统一解析器。
 *
 * <p>只允许当前用户 workspace 内的普通文件：
 * <ul>
 *   <li>绝对路径：{@code D:\...\workspace\tianlong\a.html}</li>
 *   <li>进程 CWD 相对路径：{@code workspace\tianlong\a.html}（webui 回答里常见）</li>
 *   <li>用户 workspace 相对路径：{@code a.html}</li>
 * </ul>
 * 通过 {@code toRealPath()} 校验真实路径仍在 workspace 内，防止 {@code ..}、软链/目录联接
 * 把任意服务器文件带出去。下载由 {@code TLWebChatModule.doDownload} 调
 * {@code TLWServModule.putStream} 输出（非图片扩展名自动 attachment）。
 */
public final class TLDownloadService {

    private TLDownloadService() { }

    /** 解析为可下载文件；越权/不存在/非普通文件返回 null。 */
    public static File resolve(String userId, String raw) {
        if (userId == null || userId.trim().isEmpty() || raw == null || raw.trim().isEmpty())
            return null;
        String text = raw.trim();
        try {
            Path p = Paths.get(text);
            if (p.isAbsolute()) {
                Path abs = p.normalize();
                return Files.isRegularFile(abs) && isInWorkspace(abs, userId) ? abs.toFile() : null;
            }
            // 1) CWD 相对（回答里常写 workspace\tianlong\xxx.html）
            Path cwdRel = p.toAbsolutePath().normalize();
            if (Files.isRegularFile(cwdRel) && isInWorkspace(cwdRel, userId)) return cwdRel.toFile();
            // 2) 用户 workspace 相对（只写文件名）
            Path wsRel = TLUserWorkspace.userRoot(userId).resolve(text).normalize().toAbsolutePath();
            if (Files.isRegularFile(wsRel) && isInWorkspace(wsRel, userId)) return wsRel.toFile();
        } catch (Exception ignored) {
            // InvalidPath（非法字符）/ IOError（无盘符工作目录）/ toRealPath 失败：一律不可下载
        }
        return null;
    }

    /** 真实路径必须仍在用户 workspace 真实根内（防 symlink/junction 逃逸）。 */
    private static boolean isInWorkspace(Path p, String userId) {
        try {
            Path root = TLUserWorkspace.userRoot(userId);
            if (!Files.exists(root)) return false;
            return p.toRealPath().startsWith(root.toRealPath());
        } catch (Exception e) {
            return false;
        }
    }
}
