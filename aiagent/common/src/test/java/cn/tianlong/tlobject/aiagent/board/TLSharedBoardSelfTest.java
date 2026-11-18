package cn.tianlong.tlobject.aiagent.board;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 共享黑板自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>覆盖：用户/会话隔离、user scope 跨会话、版本递增/append、TTL、条目/值/键上限、
 * delete/clear、持久化重载、boardEvent、无 bus 静默、用户路径段不越界。</p>
 */
public class TLSharedBoardSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    static class FakeBus implements IObject {
        final List<TLMsg> events = new ArrayList<>();
        @Override public String getName() { return "msgBus"; }
        @Override public TLMsg putMsg(IObject toWho, TLMsg msg) {
            events.add(msg);
            return new TLMsg().setParam(RESULT, true);
        }
        @Override public TLMsg getMsg(Object fromWho, TLMsg msg) {
            // putMsg(IObject) 在 waitFlag=true 时直接走对端 getMsg
            events.add(msg);
            return new TLMsg().setParam(RESULT, true);
        }
    }

    static class TestBoard extends TLSharedBoard {
        FakeBus bus;
        TestBoard(String n) { super(n); }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }
        @Override protected Object getModuleInFactory(String moduleName) {
            return "msgBus".equals(moduleName) ? bus : null;
        }
    }

    public static void main(String[] args) throws Exception {
        sessionIsolation();
        userScopeCrossSession();
        versionAndAppend();
        ttlExpires();
        entryCapEvictsOldest();
        valueAndKeyLimits();
        deleteAndClear();
        persistenceReload();
        eventPublishedAndNoBus();
        pathSafety();
        optimisticVersion();

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static TestBoard board() throws Exception {
        TestBoard b = new TestBoard("boardSelftest");
        set(b, "persist", false);
        return b;
    }

    private static void sessionIsolation() throws Exception {
        TestBoard b = board();
        call(b, "boardPut", "userId", "alice", "sessionId", "s1",
                "rootSessionId", "s1", "key", "plan", "value", "v1");

        TLMsg same = call(b, "boardGet", "userId", "alice", "sessionId", "s1",
                "rootSessionId", "s1", "key", "plan");
        check("同用户同会话可读", same.parseBoolean("found", false)
                && "v1".equals(same.getStringParam("value", "")));

        TLMsg otherUser = call(b, "boardGet", "userId", "bob", "sessionId", "s1",
                "rootSessionId", "s1", "key", "plan");
        check("跨用户隔离", !otherUser.parseBoolean("found", true));

        TLMsg otherSession = call(b, "boardGet", "userId", "alice", "sessionId", "s2",
                "rootSessionId", "s2", "key", "plan");
        check("同用户跨会话隔离", !otherSession.parseBoolean("found", true));
    }

    private static void userScopeCrossSession() throws Exception {
        TestBoard b = board();
        call(b, "boardPut", "scopeType", "user", "userId", "alice",
                "sessionId", "s1", "rootSessionId", "s1", "key", "shared", "value", "v");
        TLMsg other = call(b, "boardGet", "scopeType", "user", "userId", "alice",
                "sessionId", "s2", "rootSessionId", "s2", "key", "shared");
        check("user scope 跨会话可见", other.parseBoolean("found", false)
                && "v".equals(other.getStringParam("value", "")));
        TLMsg bob = call(b, "boardGet", "scopeType", "user", "userId", "bob",
                "sessionId", "s1", "rootSessionId", "s1", "key", "shared");
        check("user scope 仍按用户隔离", !bob.parseBoolean("found", true));
    }

    private static void versionAndAppend() throws Exception {
        TestBoard b = board();
        TLMsg v1 = call(b, "boardPut", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "k", "value", "a");
        TLMsg v2 = call(b, "boardPut", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "k", "value", "b");
        TLMsg v3 = call(b, "boardAppend", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "k", "value", "c");
        check("put 版本递增 1→2", v1.getLongParam("version", 0L) == 1L
                && v2.getLongParam("version", 0L) == 2L);
        check("append 版本递增到 3", v3.getLongParam("version", 0L) == 3L);
        TLMsg got = call(b, "boardGet", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "k");
        check("append 内容换行拼接", "b\nc".equals(got.getStringParam("value", "")));
    }

    private static void ttlExpires() throws Exception {
        TestBoard b = board();
        set(b, "ttlMs", 50L);
        call(b, "boardPut", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "k", "value", "v");
        Thread.sleep(80);
        TLMsg got = call(b, "boardGet", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "k");
        check("TTL 过期读不到", !got.parseBoolean("found", true));
    }

    private static void entryCapEvictsOldest() throws Exception {
        TestBoard b = board();
        set(b, "maxEntriesPerScope", 2);
        for (int i = 1; i <= 3; i++) {
            call(b, "boardPut", "userId", "u", "sessionId", "s",
                    "rootSessionId", "s", "key", "k" + i, "value", "v" + i);
            Thread.sleep(10);
        }
        TLMsg list = call(b, "boardList", "userId", "u", "sessionId", "s",
                "rootSessionId", "s");
        check("条目上限 2", list.getIntParam("count", -1) == 2);
        TLMsg k1 = call(b, "boardGet", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "k1");
        check("最旧条目被淘汰", !k1.parseBoolean("found", true));
    }

    private static void valueAndKeyLimits() throws Exception {
        TestBoard b = board();
        set(b, "maxValueChars", 5);
        set(b, "maxKeyChars", 3);
        TLMsg longValue = call(b, "boardPut", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "k", "value", "abcdef");
        check("超长 value 拒绝", !longValue.parseBoolean(RESULT, true)
                && longValue.getStringParam("error", "").contains("过长"));
        TLMsg longKey = call(b, "boardPut", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "abcd", "value", "v");
        check("超长 key 拒绝", !longKey.parseBoolean(RESULT, true)
                && longKey.getStringParam("error", "").contains("过长"));
        TLMsg emptyKey = call(b, "boardGet", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "");
        check("空 key 拒绝", !emptyKey.parseBoolean(RESULT, true));
    }

    private static void deleteAndClear() throws Exception {
        TestBoard b = board();
        call(b, "boardPut", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "k", "value", "v");
        TLMsg del = call(b, "boardDelete", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "k");
        check("delete 命中", del.parseBoolean("found", false));
        TLMsg got = call(b, "boardGet", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "k");
        check("delete 后读不到", !got.parseBoolean("found", true));

        call(b, "boardPut", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "k2", "value", "v2");
        TLMsg clear = call(b, "boardClearScope", "userId", "u", "sessionId", "s",
                "rootSessionId", "s");
        check("clear 计数=1", clear.getIntParam("cleared", -1) == 1);
        got = call(b, "boardGet", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "k2");
        check("clear 后读不到", !got.parseBoolean("found", true));
    }

    private static void persistenceReload() throws Exception {
        Path tmp = Files.createTempDirectory("tlboard");
        try {
            TestBoard b1 = new TestBoard("boardPersist1");
            set(b1, "persist", true);
            set(b1, "dataDir", tmp.toString());
            call(b1, "boardPut", "userId", "alice", "sessionId", "s1",
                    "rootSessionId", "s1", "key", "plan", "value", "persisted");

            TestBoard b2 = new TestBoard("boardPersist2");
            set(b2, "persist", true);
            set(b2, "dataDir", tmp.toString());
            TLMsg got = call(b2, "boardGet", "userId", "alice", "sessionId", "s1",
                    "rootSessionId", "s1", "key", "plan");
            check("持久化重载可读", got.parseBoolean("found", false)
                    && "persisted".equals(got.getStringParam("value", "")));
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static void eventPublishedAndNoBus() throws Exception {
        TestBoard b = board();
        b.bus = new FakeBus();
        call(b, "boardPut", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "k", "value", "v");
        check("写操作发 boardEvent", !b.bus.events.isEmpty()
                && "boardEvent".equals(b.bus.events.get(0).getAction())
                && "put".equals(b.bus.events.get(0).getStringParam("op", "")));

        TestBoard noBus = board();
        TLMsg r = call(noBus, "boardPut", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "k", "value", "v");
        check("无 bus 不抛异常", r.parseBoolean(RESULT, false));
    }

    private static void pathSafety() throws Exception {
        Path tmp = Files.createTempDirectory("tlboardSafe");
        try {
            TestBoard b = new TestBoard("boardSafe");
            set(b, "persist", true);
            set(b, "dataDir", tmp.toString());
            call(b, "boardPut", "userId", "../../evil", "sessionId", "s",
                    "rootSessionId", "s", "key", "k", "value", "v");
            boolean escaped = false;
            try (java.util.stream.Stream<Path> walk = Files.walk(tmp)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (!p.normalize().startsWith(tmp.normalize())) escaped = true;
                }
            }
            check("恶意 userId 不产生越界文件", !escaped);
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static void optimisticVersion() throws Exception {
        TestBoard b = board();
        TLMsg v1 = call(b, "boardPut", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "k", "value", "a");
        check("首次写入 v1", v1.getLongParam("version", 0L) == 1L);

        TLMsg conflict = call(b, "boardPut", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "k", "value", "b", "expectedVersion", 2L);
        check("错误 expectedVersion 拒绝", !conflict.parseBoolean(RESULT, true)
                && conflict.parseBoolean("conflict", false)
                && conflict.getLongParam("currentVersion", -1L) == 1L);

        TLMsg ok = call(b, "boardPut", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "k", "value", "b", "expectedVersion", 1L);
        check("正确 expectedVersion 写入 v2", ok.parseBoolean(RESULT, false)
                && ok.getLongParam("version", 0L) == 2L);

        TLMsg create = call(b, "boardPut", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "new", "value", "x", "expectedVersion", 0L);
        check("expectedVersion=0 允许新建", create.parseBoolean(RESULT, false));
        TLMsg overwrite = call(b, "boardPut", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "new", "value", "y", "expectedVersion", 0L);
        check("expectedVersion=0 拒绝覆盖已存在 key", !overwrite.parseBoolean(RESULT, true));

        TLMsg bad = call(b, "boardPut", "userId", "u", "sessionId", "s",
                "rootSessionId", "s", "key", "k", "value", "z", "expectedVersion", "abc");
        check("非法 expectedVersion 返回错误", !bad.parseBoolean(RESULT, true)
                && bad.getStringParam("error", "").contains("expectedVersion"));
    }

    // ======================== 辅助 ========================

    private static TLMsg call(TLSharedBoard b, String action, Object... kv) {
        TLMsg msg = new TLMsg().setAction(action);
        for (int i = 0; i + 1 < kv.length; i += 2)
            msg.setParam((String) kv[i], kv[i + 1]);
        return b.getMsg(null, msg);
    }

    private static void set(Object o, String name, Object value) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(o, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void deleteRecursively(Path root) {
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
