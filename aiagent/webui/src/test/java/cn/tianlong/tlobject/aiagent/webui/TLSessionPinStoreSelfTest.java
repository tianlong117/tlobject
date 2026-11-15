package cn.tianlong.tlobject.aiagent.webui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话置顶服务端存储自测（runnable main，无 JUnit）：
 * 写入/取消/跨实例持久化/用户隔离/损坏文件降级/路径穿越防护。
 * 失败退出码 1。
 */
public class TLSessionPinStoreSelfTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("tl-pins");
        try {
            TLSessionPinStore store = new TLSessionPinStore(tmp);
            check("1 初始为空", store.load("alice").isEmpty());
            check("2 置顶写入", store.setPinned("alice", "s1", true)
                    && store.load("alice").contains("s1"));
            check("3 跨实例持久化", new TLSessionPinStore(tmp).load("alice").contains("s1"));
            check("4 取消置顶", store.setPinned("alice", "s1", false)
                    && !store.load("alice").contains("s1"));

            store.setPinned("alice", "a1", true);
            store.setPinned("alice", "a2", true);
            store.setPinned("bob", "b1", true);
            check("5 用户隔离", store.load("alice").contains("a1")
                    && store.load("alice").contains("a2")
                    && !store.load("alice").contains("b1")
                    && store.load("bob").contains("b1"));
            check("6 空 sessionId 拒绝", !store.setPinned("alice", "  ", true));

            Path evil = store.fileFor("../../evil");
            check("7 userId 路径穿越被净化", evil.normalize().startsWith(tmp.normalize())
                    && !evil.normalize().toString().contains(".." + java.io.File.separator));

            Path bobFile = store.fileFor("bob");
            Files.createDirectories(bobFile.getParent());
            Files.write(bobFile, "not-json".getBytes(StandardCharsets.UTF_8));
            check("8 损坏文件按空集合降级", store.load("bob").isEmpty());

            // 模块集成：pinSession 本地命令 + sessions 响应补 pinned 标记（不经过 agentService）
            TLWebChatModule module = new TLWebChatModule("pinStoreSelfTest");
            Field pf = TLWebChatModule.class.getDeclaredField("pinStore");
            pf.setAccessible(true);
            pf.set(module, store);
            Map<String, Object> pinParams = new LinkedHashMap<>();
            pinParams.put("sessionId", "s1");
            pinParams.put("pinned", true);
            Map<String, Object> r = module.handleCommand("alice", "pinSession", pinParams);
            check("9 handleCommand pinSession 落盘",
                    Boolean.TRUE.equals(r.get("success")) && store.load("alice").contains("s1"));

            Map<String, Object> out = new LinkedHashMap<>();
            List<Object> data = new ArrayList<>();
            Map<String, Object> s1 = new LinkedHashMap<>();
            s1.put("sessionId", "s1");
            Map<String, Object> s2 = new LinkedHashMap<>();
            s2.put("sessionId", "s2");
            data.add(s1);
            data.add(s2);
            out.put("data", data);
            Method ann = TLWebChatModule.class.getDeclaredMethod(
                    "annotatePinnedFlags", String.class, Map.class);
            ann.setAccessible(true);
            ann.invoke(module, "alice", out);
            check("10 sessions 响应补 pinned 标记",
                    Boolean.TRUE.equals(s1.get("pinned")) && !s2.containsKey("pinned"));

            pinParams.put("pinned", false);
            module.handleCommand("alice", "pinSession", pinParams);
            check("11 pinSession(false) 取消置顶", !store.load("alice").contains("s1"));
        } finally {
            deleteRecursively(tmp);
        }
        System.out.println("[TLSessionPinStoreSelfTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (ok) pass++; else fail++;
    }

    static void deleteRecursively(Path p) {
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
