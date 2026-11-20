package cn.tianlong.tlobject.aiagent.user;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 用户文件树安全删除自测（runnable main，无 JUnit）。
 * 覆盖：三棵树整删、根/他人/共享目录保护、保留名拒绝、symlink 不跟随、用户根为链接 fail-closed。
 */
public class TLUserDataFilePurgerSelfTest {
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("tl-user-purge-test");
        Path ws = Files.createDirectories(base.resolve("workspace"));
        Path data = Files.createDirectories(base.resolve("data"));
        Path docs = Files.createDirectories(data.resolve("documents"));

        try {
            testMainPurge(ws, data, docs);
            testReservedNames(ws, data, docs);
            testSymlinkInsideTree(base, ws, data, docs);
            testUserRootSymlink(base, ws, data, docs);
        } finally {
            deleteRecursively(base);
        }
        System.out.println("[TLUserDataFilePurgerSelfTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void testMainPurge(Path ws, Path data, Path docs) throws Exception {
        write(ws.resolve("alice/uploads/a.txt"), "a");
        write(ws.resolve("alice/sub/b.txt"), "bb");
        write(data.resolve("alice/attachments/c.bin"), "ccc");
        write(data.resolve("alice/longterm_memory/memory_store.jsonl"), "{}");
        write(docs.resolve("alice/doc.docx"), "doc");
        // 他人/共享哨兵
        write(ws.resolve("bob/keep.txt"), "keep");
        write(data.resolve("bob/attachments/keep.bin"), "keep");
        write(docs.resolve("bob/keep.docx"), "keep");
        write(data.resolve("traces/global.jsonl"), "global");
        write(data.resolve("evals/case.json"), "case");

        TLUserDataFilePurger.Result r = TLUserDataFilePurger.purgeUser(
                "alice", ws, data, docs);
        check("1 删除成功", r.ok);
        check("2 workspace 用户树删除", !Files.exists(ws.resolve("alice")));
        check("3 data 用户树删除", !Files.exists(data.resolve("alice")));
        check("4 documents 用户树删除", !Files.exists(docs.resolve("alice")));
        check("5 三个根仍存在", Files.isDirectory(ws) && Files.isDirectory(data) && Files.isDirectory(docs));
        check("6 他人数据保留", Files.exists(ws.resolve("bob/keep.txt"))
                && Files.exists(data.resolve("bob/attachments/keep.bin"))
                && Files.exists(docs.resolve("bob/keep.docx")));
        check("7 共享目录保留", Files.exists(data.resolve("traces/global.jsonl"))
                && Files.exists(data.resolve("evals/case.json")));
        check("8 有文件/目录/字节计数", r.filesDeleted >= 5 && r.dirsDeleted >= 7 && r.bytesDeleted > 0);

        TLUserDataFilePurger.Result none = TLUserDataFilePurger.purgeUser(
                "nouser", ws, data, docs);
        check("9 不存在的用户不报错", none.ok && none.filesDeleted == 0);
    }

    static void testReservedNames(Path ws, Path data, Path docs) throws Exception {
        write(ws.resolve("documents/keep.txt"), "keep");
        write(data.resolve("traces/keep.jsonl"), "keep");
        TLUserDataFilePurger.Result docsReserved = TLUserDataFilePurger.purgeUser(
                "documents", ws, data, docs);
        check("10 保留名 documents 拒绝", !docsReserved.ok
                && docsReserved.errors.contains("ambiguous_user_id")
                && Files.exists(ws.resolve("documents/keep.txt")));
        TLUserDataFilePurger.Result tracesReserved = TLUserDataFilePurger.purgeUser(
                "traces", ws, data, docs);
        check("11 保留名 traces 拒绝且全局 trace 保留", !tracesReserved.ok
                && tracesReserved.errors.contains("ambiguous_user_id")
                && Files.exists(data.resolve("traces/keep.jsonl")));
    }

    static void testSymlinkInsideTree(Path base, Path ws, Path data, Path docs) throws Exception {
        Path outside = Files.createDirectories(base.resolve("outside"));
        write(outside.resolve("sentinel.txt"), "sentinel");
        write(ws.resolve("linkuser/keep.txt"), "keep");
        boolean created = false;
        try {
            Files.createSymbolicLink(ws.resolve("linkuser/link"), outside);
            created = true;
        } catch (Exception e) {
            // Windows 无权限创建符号链接：SKIP
        }
        if (!created) {
            check("12 树内 symlink 不跟随（无权限创建，SKIP）", true);
            return;
        }
        TLUserDataFilePurger.Result r = TLUserDataFilePurger.purgeUser(
                "linkuser", ws, data, docs);
        check("12 树内 symlink 不跟随", r.ok
                && !Files.exists(ws.resolve("linkuser"))
                && Files.exists(outside.resolve("sentinel.txt")));
    }

    static void testUserRootSymlink(Path base, Path ws, Path data, Path docs) throws Exception {
        Path outside = Files.createDirectories(base.resolve("outside2"));
        write(outside.resolve("sentinel.txt"), "sentinel");
        boolean created = false;
        try {
            Files.createSymbolicLink(ws.resolve("rootlink"), outside);
            created = true;
        } catch (Exception e) {
            // SKIP
        }
        if (!created) {
            check("13 用户根为链接时 fail-closed（无权限创建，SKIP）", true);
            return;
        }
        TLUserDataFilePurger.Result r = TLUserDataFilePurger.purgeUser(
                "rootlink", ws, data, docs);
        check("13 用户根为链接时 fail-closed", !r.ok
                && r.errors.contains("workspace_user_root_is_link")
                && Files.exists(outside.resolve("sentinel.txt")));
    }

    static void write(Path p, String text) throws Exception {
        Files.createDirectories(p.getParent());
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
    }

    static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        }
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (ok) pass++; else fail++;
    }
}
