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
        Path c = user.resolve("c.txt");
        Path d = user.resolve("d.txt");
        Path secret = root.resolve("secret.txt");
        Files.write(a, "a".getBytes(StandardCharsets.UTF_8));
        Files.write(b, "b".getBytes(StandardCharsets.UTF_8));
        Files.write(c, "c".getBytes(StandardCharsets.UTF_8));
        Files.write(d, "d".getBytes(StandardCharsets.UTF_8));
        Files.write(secret, "secret".getBytes(StandardCharsets.UTF_8));
        System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, root.toString());
        try {
            TLWorkspaceService.Listing rootListing = TLWorkspaceService.list("alice", "");
            check("1 根目录只列直接子项（文件 + 文件夹）",
                    names(rootListing.entries).contains("a.txt")
                            && names(rootListing.entries).contains("sub")
                            && !names(rootListing.entries).contains("sub/b.md"));
            TLWorkspaceService.Listing subListing = TLWorkspaceService.list("alice", "sub");
            check("2 进入子文件夹列出 b.md", subListing != null
                    && names(subListing.entries).contains("sub/b.md"));
            check("3 不列出 workspace 外文件", !names(rootListing.entries).contains("secret.txt"));
            check("4 越界目录列表为 null", TLWorkspaceService.list("alice", "../") == null);

            check("5 删除自己 workspace 内文件", TLWorkspaceService.delete("alice", "a.txt")
                    && !Files.exists(a));
            check("6 ../ 越界删除被拒", !TLWorkspaceService.delete("alice", "../secret.txt")
                    && Files.exists(secret));
            check("7 别的用户不能删本用户文件", !TLWorkspaceService.delete("bob", "sub/b.md")
                    && Files.exists(b));
            check("8 目录不能通过文件删除接口删除", !TLWorkspaceService.delete("alice", "sub")
                    && Files.isDirectory(sub));
            check("9 别的用户列表为空", TLWorkspaceService.list("bob", "").entries.isEmpty());
            int deleted = TLWorkspaceService.deleteAll("alice",
                    List.of("c.txt", "d.txt", "../secret.txt", "missing.txt"));
            check("10 批量删除 2/4（越权/不存在失败）", deleted == 2
                    && !Files.exists(c) && !Files.exists(d) && Files.exists(secret));

            Path newDir = TLWorkspaceService.mkdir("alice", "", "newdir");
            check("11 新建文件夹", newDir != null && Files.isDirectory(user.resolve("newdir")));
            check("12 同名/非法文件夹被拒",
                    TLWorkspaceService.mkdir("alice", "", "newdir") == null
                            && TLWorkspaceService.mkdir("alice", "", "../evil") == null);
            TLWorkspaceService.Listing newListing = TLWorkspaceService.list("alice", "newdir");
            check("13 进入新建文件夹（空）", newListing != null && newListing.entries.isEmpty());

            check("14 删除空文件夹", TLWorkspaceService.deleteEntry("alice", "newdir")
                    && !Files.exists(user.resolve("newdir")));
            Path nested = user.resolve("nested/deep/file.txt");
            Files.createDirectories(nested.getParent());
            Files.write(nested, "n".getBytes(StandardCharsets.UTF_8));
            check("15 递归删除非空文件夹", TLWorkspaceService.deleteEntry("alice", "nested")
                    && !Files.exists(user.resolve("nested")));
            check("16 workspace 根不可删",
                    !TLWorkspaceService.deleteEntry("alice", "")
                            && !TLWorkspaceService.deleteEntry("alice", ".")
                            && Files.isDirectory(user));
            Path outsideDir = root.resolve("outsideDir");
            Files.createDirectories(outsideDir);
            Files.write(outsideDir.resolve("x.txt"), "x".getBytes(StandardCharsets.UTF_8));
            check("17 ../ 越界文件夹删除被拒",
                    !TLWorkspaceService.deleteEntry("alice", "../outsideDir")
                            && Files.exists(outsideDir.resolve("x.txt")));
        } finally {
            System.clearProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
            for (Path p : new Path[]{a, b, c, d, secret}) {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            }
            try { Files.deleteIfExists(user.resolve("newdir")); } catch (Exception ignored) { }
            try { Files.deleteIfExists(root.resolve("outsideDir").resolve("x.txt")); } catch (Exception ignored) { }
            try { Files.deleteIfExists(root.resolve("outsideDir")); } catch (Exception ignored) { }
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
