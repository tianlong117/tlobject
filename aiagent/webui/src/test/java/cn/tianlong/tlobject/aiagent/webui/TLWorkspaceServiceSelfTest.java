package cn.tianlong.tlobject.aiagent.webui;

import cn.tianlong.tlobject.aiagent.TLUserWorkspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 工作空间管理自测（runnable main，无 JUnit / 无 web 容器）。
 *
 * <p>守管理页面的安全边界：只列/删当前用户 workspace 内普通文件；子目录文件可见；
 * ../ 越界、别的用户、目录删除全部拒绝。运行：
 * java -cp "webui/target/test-classes;webui/target/classes;common/target/classes;..." \
 *      cn.tianlong.tlobject.aiagent.webui.TLWorkspaceServiceSelfTest
 */
public class TLWorkspaceServiceSelfTest {
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("tl-workspace-test");
        Path user = root.resolve("alice");
        Path sub = user.resolve("sub");
        Files.createDirectories(sub);
        Path a = user.resolve("a.txt");
        Path b = sub.resolve("b.md");
        Path secret = root.resolve("secret.txt");
        Files.write(a, "a".getBytes(StandardCharsets.UTF_8));
        Files.write(b, "b".getBytes(StandardCharsets.UTF_8));
        Files.write(secret, "secret".getBytes(StandardCharsets.UTF_8));
        System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, root.toString());
        try {
            TLWorkspaceService.Listing listing = TLWorkspaceService.list("alice");
            check("1 列出当前用户文件（含子目录）", names(listing.files).contains("a.txt")
                    && names(listing.files).contains("sub/b.md"));
            check("2 不列出 workspace 外文件", !names(listing.files).contains("../secret.txt")
                    && !names(listing.files).contains("secret.txt"));

            check("3 删除自己 workspace 内文件", TLWorkspaceService.delete("alice", "a.txt")
                    && !Files.exists(a));
            check("4 ../ 越界删除被拒", !TLWorkspaceService.delete("alice", "../secret.txt")
                    && Files.exists(secret));
            check("5 别的用户不能删本用户文件", !TLWorkspaceService.delete("bob", "sub/b.md")
                    && Files.exists(b));
            check("6 目录不能通过文件删除接口删除", !TLWorkspaceService.delete("alice", "sub")
                    && Files.isDirectory(sub));
            check("7 别的用户列表为空", TLWorkspaceService.list("bob").files.isEmpty());
        } finally {
            System.clearProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
            for (Path p : new Path[]{a, b, secret}) {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            }
            try { Files.deleteIfExists(sub); } catch (Exception ignored) { }
            try { Files.deleteIfExists(user); } catch (Exception ignored) { }
            try { Files.deleteIfExists(root); } catch (Exception ignored) { }
        }
        System.out.println("\n[TLWorkspaceServiceSelfTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static java.util.Set<String> names(List<Map<String, Object>> files) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Map<String, Object> f : files) out.add(String.valueOf(f.get("path")));
        return out;
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (ok) pass++; else fail++;
    }
}
