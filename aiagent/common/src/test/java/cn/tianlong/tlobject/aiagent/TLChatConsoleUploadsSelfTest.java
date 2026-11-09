package cn.tianlong.tlobject.aiagent;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 控制台 /img 导入自测（runnable main，无 JUnit——仓库惯例）：
 * 导入件落到 workspace/{uid}/uploads，源不存在返回 null。
 * 失败退出码 1。
 */
public class TLChatConsoleUploadsSelfTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("tlconsole-uploads");
        String old = System.getProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
        try {
            System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, tmp.toString());
            Path src = tmp.resolve("src.txt");
            Files.write(src, "hello".getBytes(StandardCharsets.UTF_8));

            java.io.File dst = TLChatConsole.importAttachment("alice", src.toString());
            check("导入成功", dst != null && dst.isFile());
            check("落在 workspace/{uid}/uploads",
                    dst != null && dst.toPath().toAbsolutePath().normalize()
                            .startsWith(tmp.resolve("alice/uploads").toAbsolutePath().normalize()));
            check("内容一致", dst != null
                    && "hello".equals(new String(Files.readAllBytes(dst.toPath()), StandardCharsets.UTF_8)));

            check("源不存在返回 null",
                    TLChatConsole.importAttachment("alice", tmp.resolve("missing.txt").toString()) == null);
        } finally {
            if (old == null) System.clearProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
            else System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, old);
            deleteRecursively(tmp);
        }
        System.out.println("TLChatConsoleUploadsSelfTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }

    private static void deleteRecursively(Path p) {
        try {
            if (Files.isDirectory(p)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                    for (Path c : ds) deleteRecursively(c);
                }
            }
            Files.deleteIfExists(p);
        } catch (Exception ignored) { }
    }
}
