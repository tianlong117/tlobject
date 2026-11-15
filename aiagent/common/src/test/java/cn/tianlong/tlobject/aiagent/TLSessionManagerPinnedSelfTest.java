package cn.tianlong.tlobject.aiagent;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 文件版会话管理器「置顶」自测（runnable main，无 JUnit）：
 * 置顶写入 _session_pins.idx sidecar、列表带 pinned、跨实例持久化、删除会话清理、用户隔离。
 * 失败退出码 1。
 */
public class TLSessionManagerPinnedSelfTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("tl-session-pins");
        try {
            TLSessionManager mgr = new TLSessionManager("sessionPinsSelfTest");
            setField(mgr, "sessionStorePath", tmp.toString() + java.io.File.separator);
            writeSession(mgr, "alice", "s1", "会话一");
            writeSession(mgr, "alice", "s2", "会话二");
            writeSession(mgr, "bob", "b1", "B 会话");

            check("1 默认未置顶", !pinnedOf(mgr, "alice", "s1"));
            check("2 置顶写入", mgr.setSessionPinned("s1", "alice", true)
                    && pinnedOf(mgr, "alice", "s1"));
            check("3 其它会话仍不置顶",
                    !pinnedOf(mgr, "alice", "s2") && !pinnedOf(mgr, "bob", "b1"));

            TLSessionManager restarted = new TLSessionManager("sessionPinsSelfTest2");
            setField(restarted, "sessionStorePath", tmp.toString() + java.io.File.separator);
            check("4 跨实例持久化（sidecar）", pinnedOf(restarted, "alice", "s1"));

            check("5 取消置顶", mgr.setSessionPinned("s1", "alice", false)
                    && !pinnedOf(mgr, "alice", "s1"));
            check("6 会话不存在拒绝", !mgr.setSessionPinned("missing", "alice", true));
            check("7 空 sessionId 拒绝", !mgr.setSessionPinned("  ", "alice", true));

            check("8 用户隔离", mgr.setSessionPinned("b1", "bob", true)
                    && pinnedOf(mgr, "bob", "b1")
                    && !pinnedOf(mgr, "alice", "b1"));
            check("9 用户同名会话互不影响", mgr.setSessionPinned("s2", "alice", true)
                    && pinnedOf(mgr, "alice", "s2")
                    && !pinnedOf(mgr, "bob", "s2"));

            // 删除会话时同步清理置顶 sidecar：用同 sid 重建会话后应回到未置顶
            mgr.setSessionPinned("s2", "alice", true);
            mgr.deleteSessionData("s2", "alice");
            writeSession(mgr, "alice", "s2", "会话二（重建）");
            check("10 删除会话清理置顶", !pinnedOf(mgr, "alice", "s2"));
        } finally {
            deleteRecursively(tmp);
        }
        System.out.println("[TLSessionManagerPinnedSelfTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void writeSession(TLSessionManager mgr, String userId, String sid, String title) throws Exception {
        String dir = (String) getField(mgr, "sessionStorePath");
        Path file = tmpDir(dir).resolve(userId).resolve("session_store").resolve("agent_" + sid + ".json");
        Files.createDirectories(file.getParent());
        String line = "{\"sessionId\":\"" + sid + "\",\"state\":\"completed\",\"agentName\":\"agent\","
                + "\"userMessage\":\"hi\",\"roundSeq\":1,\"messages\":[]}";
        Files.write(file, (line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    static Path tmpDir(String sessionStorePath) {
        Path p = Path.of(sessionStorePath);
        return p.isAbsolute() ? p : p.toAbsolutePath();
    }

    @SuppressWarnings("unchecked")
    static boolean pinnedOf(TLSessionManager mgr, String userId, String sid) {
        List<Map<String, Object>> list = mgr.listSessionsMeta(userId);
        for (Map<String, Object> m : list) {
            if (sid.equals(String.valueOf(m.get("sessionId"))))
                return Boolean.TRUE.equals(m.get("pinned"));
        }
        return false;
    }

    static Object getField(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    static void setField(Object o, String name, Object value) throws Exception {
        Field f = null;
        for (Class<?> c = o.getClass(); c != null && f == null; c = c.getSuperclass()) {
            try { f = c.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
        }
        if (f == null) throw new NoSuchFieldException(name);
        f.setAccessible(true);
        f.set(o, value);
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
