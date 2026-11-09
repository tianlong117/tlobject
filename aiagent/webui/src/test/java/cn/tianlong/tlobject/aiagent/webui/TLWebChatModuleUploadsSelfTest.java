package cn.tianlong.tlobject.aiagent.webui;

import cn.tianlong.tlobject.aiagent.TLUserWorkspace;

import java.lang.reflect.Method;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * WebUI 上传件路径自测（runnable main，无 JUnit——仓库惯例）：
 * 附件相对路径按 workspace 解析、出图路径只允许本用户 workspace/data。
 * 失败退出码 1。
 */
public class TLWebChatModuleUploadsSelfTest {

    private static int passed = 0, failed = 0;

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("tlweb-uploads");
        String old = System.getProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
        try {
            System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, tmp.toString());

            // normalizeAttachments：uploads/xxx -> workspace/{uid}/uploads/xxx
            Method norm = TLWebChatModule.class.getDeclaredMethod(
                    "normalizeAttachments", List.class, String.class);
            norm.setAccessible(true);
            List<Object> out = (List<Object>) norm.invoke(null,
                    List.of(Map.of("path", "uploads/pic.png", "name", "pic.png")), "alice");
            Map<String, Object> a = (Map<String, Object>) out.get(0);
            Path expected = TLUserWorkspace.resolveUploadPath("alice", "uploads/pic.png");
            check("附件相对路径解析到 workspace",
                    expected.toString().equals(String.valueOf(a.get("path"))));

            // resolveImageFile：workspace 内命中、外部绝对路径拒绝
            Path wsImg = tmp.resolve("alice/uploads/pic.png");
            Files.createDirectories(wsImg.getParent());
            Files.write(wsImg, new byte[]{1});
            check("出图命中 workspace",
                    wsImg.toAbsolutePath().normalize().toFile()
                            .equals(TLWebChatModule.resolveImageFile("alice", "uploads/pic.png")));

            Path outside = tmp.resolve("outside.png");
            Files.write(outside, new byte[]{2});
            check("出图拒绝外部绝对路径",
                    TLWebChatModule.resolveImageFile("alice", outside.toString()) == null);
        } finally {
            if (old == null) System.clearProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
            else System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, old);
            deleteRecursively(tmp);
        }
        System.out.println("TLWebChatModuleUploadsSelfTest: " + passed + " passed, " + failed + " failed");
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
