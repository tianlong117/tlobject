package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * TLUserWorkspace 自测（runnable main，无 JUnit——仓库惯例）。
 * 失败退出码 1。
 */
public class TLUserWorkspaceSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("tlws-test");
        String old = System.getProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
        try {
            System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, tmp.toString());

            // 与 sameSegment 同口径：Windows 大小写不敏感，POSIX 大小写敏感
            boolean ciFs = Paths.get("a").equals(Paths.get("A"));

            TLMsg alice = new TLMsg()
                    .setSystemParam(AI_P_USERID, "alice")
                    .setSystemParam(AI_P_SESSIONID, "s1");
            TLMsg sidOnly = new TLMsg().setSystemParam(AI_P_SESSIONID, "s2");
            TLMsg argsUser = new TLMsg().setParam(AI_P_USERID, "bob");
            TLMsg argsSid = new TLMsg().setParam(AI_P_SESSIONID, "s3");

            check("userId 取 systemArgs", "alice".equals(TLUserWorkspace.userId(alice)));
            check("userId 缺省回退 sessionId", "s2".equals(TLUserWorkspace.userId(sidOnly)));
            check("userId args 兜底", "bob".equals(TLUserWorkspace.userId(argsUser)));
            check("userId args sessionId 兜底", "s3".equals(TLUserWorkspace.userId(argsSid)));
            TLMsg nullSysUser = new TLMsg()
                    .setSystemParam(AI_P_USERID, "null")
                    .setSystemParam(AI_P_SESSIONID, "s9");
            check("userId systemArgs 字面量 null 视为未提供，回退 sessionId",
                    "s9".equals(TLUserWorkspace.userId(nullSysUser)));
            TLMsg nullArgsUser = new TLMsg()
                    .setSystemParam(AI_P_SESSIONID, "s10")
                    .setParam(AI_P_USERID, "null");
            check("userId args 字面量 null 视为未提供，回退 system sessionId",
                    "s10".equals(TLUserWorkspace.userId(nullArgsUser)));
            check("userRoot = base/userId", TLUserWorkspace.userRoot(alice).equals(tmp.resolve("alice")));
            check("相对路径落 workspace",
                    TLUserWorkspace.resolveInput(alice, "a.txt")
                            .equals(tmp.resolve("alice").resolve("a.txt")));
            Path absInput = tmp.resolve("abs.txt").toAbsolutePath();
            check("绝对路径原样",
                    TLUserWorkspace.resolveInput(alice, absInput.toString()).equals(absInput.normalize()));

            // ===== 上传件位置 =====
            check("uploadsDir = workspace/{uid}/uploads",
                    TLUserWorkspace.uploadsDir("alice").equals(tmp.resolve("alice").resolve("uploads")));
            check("resolveUploadPath 相对路径按 workspace 解析",
                    TLUserWorkspace.resolveUploadPath("alice", "uploads/pic.png")
                            .equals(tmp.resolve("alice/uploads/pic.png").toAbsolutePath().normalize()));

            Path wsUpload = tmp.resolve("alice/uploads/pic.png");
            Files.createDirectories(wsUpload.getParent());
            Files.write(wsUpload, new byte[]{1});
            check("resolveExistingUpload 相对路径命中 workspace",
                    wsUpload.toAbsolutePath().normalize()
                            .equals(TLUserWorkspace.resolveExistingUpload("alice", "uploads/pic.png")));
            check("自己的 workspace 上传件可信",
                    TLUserWorkspace.isTrusted(wsUpload, "alice"));
            check("别人的 workspace 上传件跨用户",
                    TLUserWorkspace.isCrossUser(tmp.resolve("bob/uploads/pic.png"), "alice"));

            // 旧 data/{uid}/uploads：相对路径回退 + 迁移后绝对路径按文件名回退
            String legacyUid = "tlws-legacy-" + System.nanoTime();
            Path legacyUser = Paths.get("data").toAbsolutePath().normalize().resolve(legacyUid);
            Path legacyFile = legacyUser.resolve("uploads/old.png");
            Files.createDirectories(legacyFile.getParent());
            Files.write(legacyFile, new byte[]{2});
            try {
                check("旧 data 上传件相对路径回退",
                        legacyFile.toAbsolutePath().normalize()
                                .equals(TLUserWorkspace.resolveExistingUpload(legacyUid, "uploads/old.png")));
                Path moved = tmp.resolve(legacyUid).resolve("uploads/old.png");
                Files.createDirectories(moved.getParent());
                Files.write(moved, new byte[]{3});
                Files.delete(legacyFile);
                check("旧绝对路径按文件名回退到 workspace",
                        moved.toAbsolutePath().normalize().equals(
                                TLUserWorkspace.resolveExistingUpload(legacyUid, legacyFile.toString())));
            } finally {
                deleteRecursively(legacyUser);
            }

            Files.createDirectories(tmp.resolve("bob"));
            Path bobDir = tmp.resolve("bob");
            Path bobFile = tmp.resolve("bob").resolve("b.txt");
            Path ownFile = tmp.resolve("alice").resolve("a.txt");
            Path wsRoot = tmp.resolve("outside.txt");
            Path trueOutside = tmp.getParent().resolve("outside-true.txt");
            Path dataRoot = Paths.get("data").toAbsolutePath().normalize();
            Path dataOwn = dataRoot.resolve("alice/uploads/x.png");
            Path dataBob = dataRoot.resolve("bob/uploads/x.png");
            Path docsBob = dataRoot.resolve("documents/bob/x.docx");

            check("自己 workspace 可信", TLUserWorkspace.isTrusted(ownFile, "alice"));
            check("别人 workspace 跨用户", TLUserWorkspace.isCrossUser(bobFile, "alice"));
            check("别人 workspace 目录本身跨用户", TLUserWorkspace.isCrossUser(bobDir, "alice"));
            check("自己 data 可信", TLUserWorkspace.isTrusted(dataOwn, "alice"));
            check("别人 data 跨用户", TLUserWorkspace.isCrossUser(dataBob, "alice"));
            check("别人 documents 跨用户", TLUserWorkspace.isCrossUser(docsBob, "alice"));
            check("workspace 根本身不是跨用户", !TLUserWorkspace.isCrossUser(tmp, "alice"));

            // 路径段比较遵循文件系统大小写语义
            if (ciFs) {
                check("大小写不敏感：自己 workspace 可信（Alice）",
                        TLUserWorkspace.isTrusted(ownFile, "Alice"));
                check("大小写不敏感：自己 workspace 不判跨用户（Alice）",
                        !TLUserWorkspace.isCrossUser(ownFile, "Alice"));
            } else {
                check("大小写敏感：POSIX 下 Alice 与 alice 是不同用户（非可信）",
                        !TLUserWorkspace.isTrusted(ownFile, "Alice"));
                check("大小写敏感：POSIX 下 alice 路径对 Alice 跨用户",
                        TLUserWorkspace.isCrossUser(ownFile, "Alice"));
            }
            Path dataBobMixed = dataRoot.resolve("Bob/Uploads/x");
            if (ciFs) {
                check("大小写不敏感：data/Bob/Uploads 跨用户",
                        TLUserWorkspace.isCrossUser(dataBobMixed, "alice"));
            } else {
                check("大小写敏感：POSIX 下 data/Bob/Uploads 第二段非已知子目录",
                        !TLUserWorkspace.isCrossUser(dataBobMixed, "alice"));
            }
            check("data/bob/session_store 跨用户",
                    TLUserWorkspace.isCrossUser(dataRoot.resolve("bob/session_store/x"), "alice"));
            check("data/bob/scheduled_tasks 跨用户",
                    TLUserWorkspace.isCrossUser(dataRoot.resolve("bob/scheduled_tasks/x"), "alice"));

            check("evaluate: workspace 内 ALLOW",
                    TLUserWorkspace.evaluate("file_operation",
                            Map.of("operation", "read", "path", "a.txt"), alice).kind
                            == TLUserWorkspace.Decision.Kind.ALLOW);
            check("evaluate: 跨用户 DENY",
                    TLUserWorkspace.evaluate("file_operation",
                            Map.of("operation", "read", "path", bobFile.toString()), alice).kind
                            == TLUserWorkspace.Decision.Kind.DENY);
            check("evaluate: ../bob 上跳 DENY",
                    TLUserWorkspace.evaluate("file_operation",
                            Map.of("operation", "read", "path", "../bob/x.txt"), alice).kind
                            == TLUserWorkspace.Decision.Kind.DENY);
            check("evaluate: 自己 data 子目录 ALLOW",
                    TLUserWorkspace.evaluate("file_operation",
                            Map.of("operation", "read", "path", dataOwn.toString()), alice).kind
                            == TLUserWorkspace.Decision.Kind.ALLOW);
            TLUserWorkspace.Decision bobDirDeny = TLUserWorkspace.evaluate("file_operation",
                    Map.of("operation", "list", "path", bobDir.toString()), alice);
            check("evaluate: 跨用户目录本身 DENY",
                    bobDirDeny.kind == TLUserWorkspace.Decision.Kind.DENY);
            check("evaluate: 跨用户目录 DENY 错误文案",
                    bobDirDeny.error.contains("无权访问其他用户目录"));
            check("evaluate: 跨用户 DENY 携带路径",
                    bobDirDeny.paths.size() == 1
                            && bobDirDeny.paths.get(0).equals(bobDir.toAbsolutePath().normalize())
                            && bobDirDeny.outsidePaths.size() == 1
                            && bobDirDeny.outsidePaths.get(0).equals(bobDir.toAbsolutePath().normalize()));
            check("evaluate: 外部 NEED_APPROVAL",
                    TLUserWorkspace.evaluate("file_operation",
                            Map.of("operation", "write", "path", wsRoot.toString()), alice).kind
                            == TLUserWorkspace.Decision.Kind.NEED_APPROVAL);
            check("evaluate: workspace 根 NEED_APPROVAL",
                    TLUserWorkspace.evaluate("file_operation",
                            Map.of("operation", "list", "path", tmp.toString()), alice).kind
                            == TLUserWorkspace.Decision.Kind.NEED_APPROVAL);
            check("evaluate: 真正外部路径 NEED_APPROVAL",
                    TLUserWorkspace.evaluate("file_operation",
                            Map.of("operation", "write", "path", trueOutside.toString()), alice).kind
                            == TLUserWorkspace.Decision.Kind.NEED_APPROVAL);
            check("evaluate: 未登记工具不拦截",
                    TLUserWorkspace.evaluate("other_tool",
                            Map.of("path", bobFile.toString()), alice).kind
                            == TLUserWorkspace.Decision.Kind.ALLOW);
            check("evaluate: word path+output ALLOW",
                    TLUserWorkspace.evaluate("word",
                            Map.of("action", "create", "path", "a.docx", "output", "b.docx"), alice).kind
                            == TLUserWorkspace.Decision.Kind.ALLOW);
            check("evaluate: word output 越界 NEED_APPROVAL",
                    TLUserWorkspace.evaluate("word",
                            Map.of("action", "create", "path", "a.docx", "output", wsRoot.toString()), alice).kind
                            == TLUserWorkspace.Decision.Kind.NEED_APPROVAL);
            Path external = tmp.getParent().resolve("tlws-external-output.docx").toAbsolutePath().normalize();
            TLUserWorkspace.Decision wordOut = TLUserWorkspace.evaluate("word",
                    Map.of("action", "create", "path", "a.docx", "output", external.toString()), alice);
            check("evaluate: word 外部 output NEED_APPROVAL",
                    wordOut.kind == TLUserWorkspace.Decision.Kind.NEED_APPROVAL);
            check("evaluate: word 外部 output outsidePaths 唯一", wordOut.outsidePaths.size() == 1);
            check("evaluate: word 外部 output 路径结构化返回",
                    wordOut.outsidePaths.size() == 1 && wordOut.outsidePaths.get(0).equals(external));

            TLMsg traversalUser = new TLMsg().setSystemParam(AI_P_USERID, "../bob");
            check("userId 路径段净化：../bob -> .._bob",
                    TLUserWorkspace.userRoot(traversalUser)
                            .equals(TLUserWorkspace.baseRoot().resolve(".._bob")));

            TLUserWorkspace.Decision badPath = TLUserWorkspace.evaluate("file_operation",
                    Map.of("operation", "read", "path", "a\0b"), alice);
            check("evaluate: 非法路径 DENY 不抛异常",
                    badPath.kind == TLUserWorkspace.Decision.Kind.DENY
                            && badPath.error.startsWith("非法路径"));

            // Windows 盘符相对路径（a:b.py）：toAbsolutePath 抛 IOError（Error 族），
            // 必须收口为 DENY，而不是穿过执行器的 catch (Exception) 杀死工作线程
            if (java.io.File.separatorChar == '\\') {
                TLUserWorkspace.Decision driveRel = TLUserWorkspace.evaluate("file_operation",
                        Map.of("operation", "read", "path", "a:b.py"), alice);
                check("evaluate: 盘符相对路径 DENY 不抛异常",
                        driveRel.kind == TLUserWorkspace.Decision.Kind.DENY
                                && driveRel.error.contains("非法路径"));
            } else {
                System.out.println("[SKIP] 盘符相对路径用例（POSIX 无盘符语义，不适用）");
            }
        } finally {
            if (old == null) System.clearProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
            else System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, old);
            deleteRecursively(tmp);
        }
        System.out.println("TLUserWorkspaceSelfTest: " + passed + " passed, " + failed + " failed");
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
        } catch (IOException ignored) { }
    }
}
