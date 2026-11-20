package cn.tianlong.tlobject.aiagent.user;

import cn.tianlong.tlobject.aiagent.TLPathSafety;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户文件树安全删除工具：删除 {@code workspace/{uid}}、{@code data/{uid}}、{@code data/documents/{uid}}。
 *
 * <p>安全约束：</p>
 * <ul>
 *   <li>三个根目录（workspaceRoot/dataRoot/documentsRoot）本身永不删除；用户目录必须是根下的严格子路径；</li>
 *   <li>不 FOLLOW_LINKS：symlink 只删链接本身；疑似 junction 的目录用 toRealPath 校验，越界则 SKIP_SUBTREE
 *       并报错（fail-closed，不清链接）；</li>
 *   <li>用户名与共享 data 顶层目录同名（documents/traces/evals 等）时拒绝，避免误删全局目录。</li>
 * </ul>
 */
public final class TLUserDataFilePurger {

    /** 与 data 根下共享目录同名的用户名：拒绝彻底删除，需人工处理 */
    private static final Set<String> RESERVED_USER_NAMES = Set.of(
            "documents", "traces", "evals", "aitest", "task_plans", "checkpoint",
            "browser_profile", "browser_agent_profile", "smoke_layout",
            "aiagent_longterm_memory", "master_memory", "default");

    private TLUserDataFilePurger() { }

    public static boolean isReservedUserName(String userId) {
        return userId != null && RESERVED_USER_NAMES.contains(userId);
    }

    public static final class Result {
        public boolean ok = true;
        public int filesDeleted;
        public int dirsDeleted;
        public long bytesDeleted;
        public final List<String> warnings = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();

        void error(String msg) {
            ok = false;
            errors.add(msg);
        }
    }

    /**
     * @param workspaceRoot workspace 根（TLUserWorkspace.baseRoot()）
     * @param dataRoot      运行时 data 根（必须与实际 CWD 配置一致）
     * @param documentsRoot Word 旧目录根（为空则 dataRoot/documents）
     */
    public static Result purgeUser(String userId, Path workspaceRoot, Path dataRoot, Path documentsRoot) {
        Result result = new Result();
        if (userId == null || userId.trim().isEmpty()) {
            result.error("empty_user_id");
            return result;
        }
        if (isReservedUserName(userId)) {
            result.error("ambiguous_user_id");
            return result;
        }
        String segment = TLPathSafety.safeSegment(userId, null);
        if (!userId.equals(segment)) {
            result.error("invalid_user_id");
            return result;
        }
        Path wsRoot = workspaceRoot.toAbsolutePath().normalize();
        Path dRoot = dataRoot.toAbsolutePath().normalize();
        Path docRoot = (documentsRoot == null ? null : documentsRoot.toAbsolutePath().normalize());
        if (docRoot == null) docRoot = dRoot.resolve("documents");

        deleteUserTree(wsRoot.resolve(segment), wsRoot, "workspace", result);
        deleteUserTree(dRoot.resolve(segment), dRoot, "data", result);
        deleteUserTree(docRoot.resolve(segment), docRoot, "documents", result);
        return result;
    }

    private static void deleteUserTree(Path target, Path root, String label, Result result) {
        Path normalized = target.toAbsolutePath().normalize();
        if (!isStrictChild(root, normalized)) {
            result.error(label + "_root_escape");
            return;
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(normalized)) {
            // 用户根是链接：fail-closed，不进入目标
            result.error(label + "_user_root_is_link");
            return;
        }
        try {
            Path realRoot = root.toRealPath();
            Path realTarget = normalized.toRealPath();
            if (!realTarget.startsWith(realRoot) || realTarget.equals(realRoot)) {
                result.error(label + "_realpath_escape");
                return;
            }
        } catch (IOException e) {
            result.error(label + "_realpath_failed:" + e.getMessage());
            return;
        }
        deleteTreeNoFollow(normalized, root, label, result);
    }

    private static void deleteTreeNoFollow(Path start, Path root, String label, Result result) {
        final Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException e) {
            result.error(label + "_root_realpath_failed");
            return;
        }
        final Set<Path> skippedDirs = new LinkedHashSet<>();
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(start)) return FileVisitResult.CONTINUE;
                    try {
                        Path real = dir.toRealPath();
                        if (!real.startsWith(realRoot) || real.equals(realRoot)) {
                            // 疑似 junction（JDK 未识别为 symlink）：不递归、不删条目，fail-closed
                            skippedDirs.add(dir);
                            result.error(label + "_subdir_escape:" + dir.getFileName());
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    } catch (IOException e) {
                        skippedDirs.add(dir);
                        result.error(label + "_subdir_realpath_failed:" + dir.getFileName());
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        long size = attrs.isRegularFile() ? attrs.size() : 0L;
                        Files.deleteIfExists(file);
                        result.filesDeleted++;
                        result.bytesDeleted += size;
                    } catch (IOException e) {
                        result.error(label + "_file_delete_failed:" + file.getFileName());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    result.error(label + "_visit_failed:" + file.getFileName());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (exc != null) {
                        result.error(label + "_dir_visit_failed:" + dir.getFileName());
                        return FileVisitResult.CONTINUE;
                    }
                    if (skippedDirs.contains(dir)) return FileVisitResult.CONTINUE;
                    if (dir.equals(start)) return FileVisitResult.CONTINUE;
                    try {
                        Files.deleteIfExists(dir);
                        result.dirsDeleted++;
                    } catch (IOException e) {
                        result.error(label + "_dir_delete_failed:" + dir.getFileName());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            // 最后删除用户根（start 本身）
            try {
                Files.deleteIfExists(start);
                result.dirsDeleted++;
            } catch (IOException e) {
                result.error(label + "_user_root_delete_failed:" + e.getMessage());
            }
        } catch (IOException e) {
            result.error(label + "_walk_failed:" + e.getMessage());
        }
    }

    /** target 必须是 root 的严格后代（规范化路径比较；符号链接由调用方 realpath 复核）。 */
    static boolean isStrictChild(Path root, Path target) {
        Path r = root.toAbsolutePath().normalize();
        Path t = target.toAbsolutePath().normalize();
        return t.startsWith(r) && !t.equals(r);
    }

    /** 测试/调试：默认 data 根（CWD/data）。 */
    public static Path defaultDataRoot() {
        return Paths.get("data").toAbsolutePath().normalize();
    }
}
