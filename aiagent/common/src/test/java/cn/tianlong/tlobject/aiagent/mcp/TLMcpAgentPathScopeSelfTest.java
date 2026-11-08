package cn.tianlong.tlobject.aiagent.mcp;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TLMcpAgent 路径作用域自测（runnable main，无 JUnit——仓库惯例）。
 *
 * 只测静态路径改写辅助方法，不启动任何 MCP Server。失败退出码 1。
 */
public class TLMcpAgentPathScopeSelfTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("tlmcp-scope-test");
        try {
            Path root = tmp.resolve("workspace").toAbsolutePath().normalize();
            Files.createDirectories(root.resolve("alice"));
            Files.createDirectories(root.resolve("bob"));

            Map<String, Object> rel = TLMcpAgent.scopeArguments(
                    new LinkedHashMap<>(Map.of("path", "poet.txt")), "alice", root);
            check("相对路径 poet.txt → alice/poet.txt", "alice/poet.txt".equals(rel.get("path")));

            Map<String, Object> dot = TLMcpAgent.scopeArguments(
                    new LinkedHashMap<>(Map.of("path", ".")), "alice", root);
            check("相对路径 . → alice", "alice".equals(dot.get("path")));

            Map<String, Object> abs = TLMcpAgent.scopeArguments(
                    new LinkedHashMap<>(Map.of("path", root.resolve("alice/x.txt").toString())),
                    "alice", root);
            check("绝对路径（自己 workspace）→ alice/x.txt", "alice/x.txt".equals(abs.get("path")));

            Map<String, Object> list = TLMcpAgent.scopeArguments(
                    new LinkedHashMap<>(Map.of("paths",
                            new ArrayList<>(List.of("a.txt", "b.txt")))), "alice", root);
            check("paths 列表逐项改写 → [alice/a.txt, alice/b.txt]",
                    List.of("alice/a.txt", "alice/b.txt").equals(list.get("paths")));

            Map<String, Object> other = TLMcpAgent.scopeArguments(
                    new LinkedHashMap<>(Map.of("path", "a.txt", "pattern", "*.java")), "alice", root);
            check("未登记参数原样保留", "*.java".equals(other.get("pattern")));
            check("null 参数返回 null", TLMcpAgent.scopeArguments(null, "alice", root) == null);

            Path bobFile = root.resolve("bob/x.txt");
            try {
                TLMcpAgent.scopeArguments(new LinkedHashMap<>(Map.of("path", bobFile.toString())),
                        "alice", root);
                check("绝对路径跨用户 → 拒绝（文案含 其他用户）", false);
            } catch (Exception e) {
                check("绝对路径跨用户 → 拒绝（文案含 其他用户）",
                        e.getMessage() != null && e.getMessage().contains("其他用户"));
            }

            try {
                TLMcpAgent.scopeArguments(new LinkedHashMap<>(Map.of("path", "../bob/x.txt")),
                        "alice", root);
                check("相对路径 ../bob 跨用户 → 拒绝", false);
            } catch (Exception e) {
                check("相对路径 ../bob 跨用户 → 拒绝",
                        e.getMessage() != null && e.getMessage().contains("其他用户"));
            }

            Path outside = tmp.resolve("outside.txt").toAbsolutePath().normalize();
            try {
                TLMcpAgent.scopeArguments(new LinkedHashMap<>(Map.of("path", outside.toString())),
                        "alice", root);
                check("root 之外 → 拒绝（文案含 file_operation）", false);
            } catch (Exception e) {
                check("root 之外 → 拒绝（文案含 file_operation）",
                        e.getMessage() != null && e.getMessage().contains("file_operation"));
            }

            // source/destination（move_file 用）：两个键都要被作用域改写，否则可绕过工作区隔离
            Map<String, Object> move = TLMcpAgent.scopeArguments(
                    new LinkedHashMap<>(Map.of(
                            "source", root.resolve("alice/s.txt").toString(),
                            "destination", root.resolve("alice/d.txt").toString())),
                    "alice", root);
            check("source 改写 → alice/s.txt", "alice/s.txt".equals(move.get("source")));
            check("destination 改写 → alice/d.txt", "alice/d.txt".equals(move.get("destination")));

            // 新用户：pathRoot/{uid} 必须被创建——filesystem MCP 的 write_file 不能 chdir
            // 进不存在的父目录，首次写入会直接失败
            Path newUser = TLMcpAgent.ensureScopedUserRoot(root, "newuser");
            check("ensureScopedUserRoot 创建 pathRoot/newuser",
                    Files.isDirectory(root.resolve("newuser")) && newUser.equals(root.resolve("newuser")));

            // Windows 盘符相对路径（a:b.py）：toAbsolutePath 抛 IOError（Error 族），
            // scopeArguments 必须转成带“非法路径”文案的异常，而不是让 Error 逃逸
            if (java.io.File.separatorChar == '\\') {
                try {
                    TLMcpAgent.scopeArguments(new LinkedHashMap<>(Map.of("path", "a:b.py")),
                            "alice", root);
                    check("盘符相对路径 → 异常（文案含 非法路径）", false);
                } catch (Exception e) {
                    check("盘符相对路径 → 异常（文案含 非法路径）",
                            e.getMessage() != null && e.getMessage().contains("非法路径"));
                }
            } else {
                System.out.println("[SKIP] 盘符相对路径用例（POSIX 无盘符语义，不适用）");
            }

            // 以下用例只对大小写不敏感文件系统（Windows）成立：
            // POSIX 下 ALICE 与 alice 是不同目录，按各自语义不会命中“自己的 uid”。
            if (isCaseInsensitiveFs()) {
                Map<String, Object> absCase = TLMcpAgent.scopeArguments(
                        new LinkedHashMap<>(Map.of(
                                "path", root.resolve("ALICE/x.txt").toString())), "alice", root);
                String absCasePath = String.valueOf(absCase.get("path")).replace('\\', '/');
                check("大小写变体绝对路径（自己 workspace）→ ALICE/x.txt",
                        "ALICE/x.txt".equalsIgnoreCase(absCasePath));

                Map<String, Object> relCase = TLMcpAgent.scopeArguments(
                        new LinkedHashMap<>(Map.of("path", "ALICE/x.txt")), "alice", root);
                String relCasePath = String.valueOf(relCase.get("path")).replace('\\', '/');
                check("大小写变体相对路径 → ALICE/x.txt（不重复加 alice/）",
                        "ALICE/x.txt".equalsIgnoreCase(relCasePath));

                Map<String, Object> listCase = TLMcpAgent.scopeArguments(
                        new LinkedHashMap<>(Map.of("paths",
                                new ArrayList<>(List.of("ALICE/a.txt")))), "alice", root);
                Object listCaseVal = listCase.get("paths");
                boolean listCaseOk = listCaseVal instanceof List
                        && ((List<?>) listCaseVal).size() == 1
                        && String.valueOf(((List<?>) listCaseVal).get(0)).replace('\\', '/')
                                .equalsIgnoreCase("ALICE/a.txt");
                check("大小写变体 paths 列表项 → [ALICE/a.txt]", listCaseOk);

                try {
                    TLMcpAgent.scopeArguments(new LinkedHashMap<>(Map.of(
                            "path", root.resolve("BOB/x.txt").toString())), "alice", root);
                    check("大小写变体跨用户仍拒绝（文案含 其他用户）", false);
                } catch (Exception e) {
                    check("大小写变体跨用户仍拒绝（文案含 其他用户）",
                            e.getMessage() != null && e.getMessage().contains("其他用户"));
                }
            } else {
                System.out.println("[SKIP] 大小写变体用例（POSIX 大小写敏感，不适用）");
            }
        } finally {
            deleteRecursively(tmp);
        }
        System.out.println("TLMcpAgentPathScopeSelfTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /** 文件系统是否大小写不敏感（用 Path.equals 实际探测，不猜平台名） */
    private static boolean isCaseInsensitiveFs() {
        return java.nio.file.Paths.get("ALICE").equals(java.nio.file.Paths.get("alice"));
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
        } catch (IOException ignored) { }
    }
}
