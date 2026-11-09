package cn.tianlong.tlobject.aiagent;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 旧上传件迁移：{@code data/{uid}/uploads} → {@code workspace/{uid}/uploads}，幂等。
 *
 * <p>为什么需要独立入口：{@code attachmentStore} 在多数部署里是懒加载模块（首次附件操作才
 * 实例化），只靠它的 {@code runStartMsg} 无法保证"启动时迁移"。因此：
 * <ul>
 *   <li>agent 启动阶段调用 {@link #migrateOnce()}（默认 data/ 根，每 JVM 一次）；</li>
 *   <li>{@code attachmentStore} 自启动时用自定义 {@code dataBasePath} 调 {@link #migrate(Path)}，
 *       覆盖改过数据根的部署。</li>
 * </ul></p>
 *
 * <p>规则：目标同名文件不覆盖（保留源并记 warning）；跨卷失败复制后删源；
 * 迁空删目录；失败不抛异常，文件留在原位等待下次重试。</p>
 *
 * 创建日期：2026/11/10
 * 作者:tianlong
 */
public final class TLUploadMigration {

    private static final AtomicBoolean DONE = new AtomicBoolean(false);

    private TLUploadMigration() {}

    /** 迁移结果：成功文件数与需上报的告警（源文件保留） */
    public static final class Result {
        public int moved;
        public final List<String> warnings = new ArrayList<>();
    }

    /** 默认 data/ 根，每 JVM 只执行一次；失败会复位标记，下次调用可重试 */
    public static Result migrateOnce() {
        if (!DONE.compareAndSet(false, true)) return new Result();
        try {
            return migrate(Paths.get("data"));
        } catch (Exception e) {
            DONE.set(false);
            Result r = new Result();
            r.warnings.add("迁移失败: " + e);
            return r;
        }
    }

    /**
     * 迁移 {@code dataRoot/{uid}/uploads} 下所有文件到 workspace 对应目录。
     * 每个用户/文件独立处理，单个失败不影响其余文件。
     */
    public static Result migrate(Path dataRoot) {
        Result result = new Result();
        File[] users = dataRoot.toFile().listFiles(File::isDirectory);
        if (users == null) return result;
        for (File u : users) {
            File legacy = new File(u, "uploads");
            if (!legacy.isDirectory()) continue;
            File target = TLUserWorkspace.uploadsDir(u.getName()).toFile();
            try {
                if (legacy.getCanonicalPath().equals(target.getCanonicalPath())) continue;
            } catch (IOException e) {
                result.warnings.add("源/目标路径解析失败: " + legacy + " -> " + target);
                continue;
            }
            migrateDir(legacy, target, result);
            deleteEmptyDirs(legacy);
        }
        return result;
    }

    private static void migrateDir(File srcDir, File dstDir, Result result) {
        File[] files = srcDir.listFiles();
        if (files == null) return;
        for (File src : files) {
            if (src.isDirectory()) {
                migrateDir(src, new File(dstDir, src.getName()), result);
                continue;
            }
            File dst = new File(dstDir, src.getName());
            if (dst.exists()) {
                result.warnings.add("跳过（目标已存在）: " + dst);
                continue;
            }
            if (!dstDir.exists() && !dstDir.mkdirs()) {
                result.warnings.add("创建目录失败: " + dstDir);
                continue;
            }
            try {
                Files.move(src.toPath(), dst.toPath());
                result.moved++;
            } catch (Exception moveError) {
                try {
                    Files.copy(src.toPath(), dst.toPath());
                    if (Files.deleteIfExists(src.toPath())) result.moved++;
                    else result.warnings.add("复制后删除源失败: " + src);
                } catch (Exception copyError) {
                    result.warnings.add("迁移失败 " + src + " -> " + dst + ": " + copyError);
                }
            }
        }
    }

    private static void deleteEmptyDirs(File dir) {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) if (f.isDirectory()) deleteEmptyDirs(f);
        File[] left = dir.listFiles();
        if (left != null && left.length == 0) dir.delete();
    }
}
