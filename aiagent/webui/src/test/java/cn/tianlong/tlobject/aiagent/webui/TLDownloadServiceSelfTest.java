package cn.tianlong.tlobject.aiagent.webui;

import cn.tianlong.tlobject.aiagent.TLUserWorkspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 生成文件下载解析自测（runnable main，无 JUnit / 无 web 容器）。
 *
 * <p>守 2026-11-14 需求：生成文件提供下载链接，但只能下载本用户 workspace 内文件；
 * {@code ../}、别的用户、非普通文件一律拒绝（realpath 校验防软链/目录联接逃逸）。
 * 运行：java -cp "webui/target/test-classes;webui/target/classes;common/target/classes;... " \
 *      cn.tianlong.tlobject.aiagent.webui.TLDownloadServiceSelfTest
 */
public class TLDownloadServiceSelfTest {
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("tl-download-test");
        Path user = root.resolve("tianlong");
        Files.createDirectories(user);
        Path file = user.resolve("中国癌症升降趋势分组_1980年后.html");
        Files.write(file, "<html>ok</html>".getBytes(StandardCharsets.UTF_8));
        Path secret = root.resolve("secret.html");
        Files.write(secret, "secret".getBytes(StandardCharsets.UTF_8));
        System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, root.toString());
        try {
            check("1 用户 workspace 相对文件名可下载",
                    TLDownloadService.resolve("tianlong", "中国癌症升降趋势分组_1980年后.html") != null);
            check("2 绝对路径可下载",
                    TLDownloadService.resolve("tianlong", file.toString()) != null);
            check("3 ../ 越出 workspace 被拒",
                    TLDownloadService.resolve("tianlong", "../secret.html") == null);
            check("4 别的用户不能下载本用户文件",
                    TLDownloadService.resolve("other", file.toString()) == null);
            check("5 不存在的文件返回 null",
                    TLDownloadService.resolve("tianlong", "missing.html") == null);
            check("6 空路径/null 返回 null",
                    TLDownloadService.resolve("tianlong", "") == null
                            && TLDownloadService.resolve(null, "a.html") == null);
        } finally {
            System.clearProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
            try { Files.deleteIfExists(secret); } catch (Exception ignored) { }
            try { Files.deleteIfExists(file); } catch (Exception ignored) { }
            try { Files.deleteIfExists(user); } catch (Exception ignored) { }
            try { Files.deleteIfExists(root); } catch (Exception ignored) { }
        }
        System.out.println("\n[TLDownloadServiceSelfTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (ok) pass++; else fail++;
    }
}
