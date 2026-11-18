package cn.tianlong.tlobject.aiagent.board;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.aiagent.TLPathSafety;
import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 跨 Agent/跨任务共享黑板（最小版）。
 *
 * <p>定位：工作流 {@code TLWorkflowContext} 是单次运行、引擎私有的；组成员之间只靠返回文本
 * 传递结果；{@code TLMsgBus} 是纯内存 pub/sub。本模块补的是「一个任务/一个用户的可读写
 * 共享状态」：多次工具调用、多个 Agent、甚至不同会话（scope=user）都能读写同一份产物。
 *
 * <p>作用域：
 * <ul>
 *   <li>{@code session}（默认）：userId + rootSessionId（缺失回落 sessionId），同名会话共享；</li>
 *   <li>{@code user}：userId，跨会话/跨任务共享（文章里的"调研→文案→预算"场景）。</li>
 * </ul>
 *
 * <p>Key 是 Map 键，不参与文件路径；值按 UTF-8 文本保存（非 String 用 Gson 序列化）。
 * 持久化默认开启：{@code {dataDir}/{safeUserId}/board/{sha256(scopeKey)}.json}，
 * 写临时文件后原子替换；损坏/读失败回落空板并 WARN，不影响运行。
 *
 * <p>限额：TTL（默认 24h，惰性清理）、单作用域条目数、单值长度、作用域总数（LRU 淘汰）。
 * 这些是防无界增长的最小保护，不是安全边界——不要存密钥/口令。
 *
 * <p>事件：变更后向 msgBus 发 {@code boardEvent}（bus 缺失/异常静默降级）。
 */
public class TLSharedBoard extends TLBaseModule implements TLAiAgentParamString {

    public static final String ACTION_PUT = "boardPut";
    public static final String ACTION_APPEND = "boardAppend";
    public static final String ACTION_GET = "boardGet";
    public static final String ACTION_LIST = "boardList";
    public static final String ACTION_DELETE = "boardDelete";
    public static final String ACTION_CLEAR = "boardClearScope";
    public static final String ACTION_STATS = "boardStats";
    public static final String EVENT_TOPIC = "boardEvent";

    private String dataDir = "data";
    private boolean persist = true;
    private long ttlMs = 24L * 60 * 60 * 1000;
    private int maxEntriesPerScope = 200;
    private int maxValueChars = 65536;
    private int maxKeyChars = 128;
    private int maxScopes = 1000;

    private final Gson gson = new Gson();
    private final Map<String, Scope> scopes = new ConcurrentHashMap<>();

    public TLSharedBoard() { super(); }
    public TLSharedBoard(String name) { super(name); }
    public TLSharedBoard(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected void setModuleParams() {
        if (params == null) return;
        if (params.get("dataDir") != null) dataDir = params.get("dataDir");
        if (params.get("persist") != null) persist = Boolean.parseBoolean(params.get("persist"));
        ttlMs = parseLong("ttlMs", ttlMs);
        maxEntriesPerScope = (int) parseLong("maxEntriesPerScope", maxEntriesPerScope);
        maxValueChars = (int) parseLong("maxValueChars", maxValueChars);
        maxKeyChars = (int) parseLong("maxKeyChars", maxKeyChars);
        maxScopes = (int) parseLong("maxScopes", maxScopes);
        if (maxEntriesPerScope < 1) maxEntriesPerScope = 1;
        if (maxValueChars < 1) maxValueChars = 1;
        if (maxKeyChars < 1) maxKeyChars = 1;
        if (maxScopes < 1) maxScopes = 1;
    }

    private long parseLong(String key, long def) {
        String v = params.get(key);
        if (v == null || v.trim().isEmpty()) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            putLog("sharedBoard invalid " + key + ": " + v + ", keep " + def, LogLevel.WARN);
            return def;
        }
    }

    @Override
    protected TLBaseModule init() { return this; }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case ACTION_PUT:    return doPut(msg);
            case ACTION_APPEND: return doAppend(msg);
            case ACTION_GET:    return doGet(msg);
            case ACTION_LIST:   return doList(msg);
            case ACTION_DELETE: return doDelete(msg);
            case ACTION_CLEAR:  return doClear(msg);
            case ACTION_STATS:  return doStats();
            default:            return null;
        }
    }

    // ======================== 动作 ========================

    private TLMsg doPut(TLMsg msg) {
        String key = msg.getStringParam("key", "").trim();
        String err = validateKey(key);
        if (err != null) return error(err);
        String text = toText(msg.getParam("value"));
        if (text.length() > maxValueChars)
            return error("value 过长（" + text.length() + " > " + maxValueChars + " 字符）");
        Scope s = resolveScope(msg);
        s.ioLock.lock();
        try {
            Entry old = s.entries.get(key);
            long currentVersion = old == null ? 0 : old.version;
            Long expected;
            try {
                expected = parseExpectedVersion(msg);
            } catch (IllegalArgumentException e) {
                return error(e.getMessage());
            }
            if (expected != null && expected != currentVersion) {
                return createMsg().setParam(RESULT, false)
                        .setParam("conflict", true)
                        .setParam("currentVersion", currentVersion)
                        .setParam("error", "版本冲突：期望 v" + expected
                                + "，当前 v" + currentVersion + "（请重新读取后再改）");
            }
            long version = currentVersion + 1;
            if (old == null && s.entries.size() >= maxEntriesPerScope) evictOldestEntry(s);
            Entry e = new Entry(text, version, System.currentTimeMillis(), producerOf(msg));
            s.entries.put(key, e);
            saveScope(s);
            publish("put", s, key, e);
            return createMsg().setParam(RESULT, true)
                    .setParam("key", key).setParam("version", version)
                    .setParam("updatedAt", e.updatedAt)
                    .setParam("scopeType", s.scopeType).setParam("scope", s.key);
        } finally {
            s.ioLock.unlock();
        }
    }

    private TLMsg doAppend(TLMsg msg) {
        String key = msg.getStringParam("key", "").trim();
        String err = validateKey(key);
        if (err != null) return error(err);
        String text = toText(msg.getParam("value"));
        Scope s = resolveScope(msg);
        s.ioLock.lock();
        try {
            Entry old = s.entries.get(key);
            String merged = old == null || old.value == null || old.value.isEmpty()
                    ? text : old.value + "\n" + text;
            if (merged.length() > maxValueChars)
                return error("append 后 value 过长（" + merged.length() + " > " + maxValueChars + " 字符）");
            long version = (old == null ? 0 : old.version) + 1;
            if (old == null && s.entries.size() >= maxEntriesPerScope) evictOldestEntry(s);
            Entry e = new Entry(merged, version, System.currentTimeMillis(), producerOf(msg));
            s.entries.put(key, e);
            saveScope(s);
            publish("append", s, key, e);
            return createMsg().setParam(RESULT, true)
                    .setParam("key", key).setParam("version", version)
                    .setParam("updatedAt", e.updatedAt)
                    .setParam("scopeType", s.scopeType).setParam("scope", s.key)
                    .setParam("length", merged.length());
        } finally {
            s.ioLock.unlock();
        }
    }

    private TLMsg doGet(TLMsg msg) {
        String key = msg.getStringParam("key", "").trim();
        String err = validateKey(key);
        if (err != null) return error(err);
        Scope s = resolveScope(msg);
        s.ioLock.lock();
        try {
            Entry e = s.entries.get(key);
            if (e != null && expired(e)) {
                s.entries.remove(key);
                saveScope(s);
                e = null;
            }
            TLMsg ret = createMsg().setParam(RESULT, true)
                    .setParam("found", e != null)
                    .setParam("key", key)
                    .setParam("scopeType", s.scopeType).setParam("scope", s.key);
            if (e != null) {
                ret.setParam("value", e.value)
                        .setParam("version", e.version)
                        .setParam("updatedAt", e.updatedAt)
                        .setParam("producer", e.producer);
            }
            return ret;
        } finally {
            s.ioLock.unlock();
        }
    }

    private TLMsg doList(TLMsg msg) {
        Scope s = resolveScope(msg);
        s.ioLock.lock();
        try {
            boolean changed = false;
            List<String> keys = new ArrayList<>(s.entries.keySet());
            keys.sort(Comparator.naturalOrder());
            List<Map<String, Object>> list = new ArrayList<>();
            for (String key : keys) {
                Entry e = s.entries.get(key);
                if (e == null) continue;
                if (expired(e)) {
                    s.entries.remove(key);
                    changed = true;
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", key);
                m.put("version", e.version);
                m.put("updatedAt", e.updatedAt);
                m.put("producer", e.producer);
                m.put("size", e.value != null ? e.value.length() : 0);
                list.add(m);
            }
            if (changed) saveScope(s);
            return createMsg().setParam(RESULT, true)
                    .setParam("entries", list).setParam("count", list.size())
                    .setParam("scopeType", s.scopeType).setParam("scope", s.key);
        } finally {
            s.ioLock.unlock();
        }
    }

    private TLMsg doDelete(TLMsg msg) {
        String key = msg.getStringParam("key", "").trim();
        String err = validateKey(key);
        if (err != null) return error(err);
        Scope s = resolveScope(msg);
        s.ioLock.lock();
        try {
            Entry e = s.entries.remove(key);
            if (e != null) {
                saveScope(s);
                publish("delete", s, key, e);
            }
            return createMsg().setParam(RESULT, true)
                    .setParam("found", e != null).setParam("key", key)
                    .setParam("scopeType", s.scopeType).setParam("scope", s.key);
        } finally {
            s.ioLock.unlock();
        }
    }

    private TLMsg doClear(TLMsg msg) {
        Scope s = resolveScope(msg);
        int count;
        s.ioLock.lock();
        try {
            count = s.entries.size();
            s.entries.clear();
            deleteScopeFile(s);
            // 不在这里 remove(scope)：并发 put 可能仍持有该 scope 的引用，
            // 移除后它再写入会"复活"已清空的板；保留空 scope 交给 LRU/TTL 回收。
            publish("clear", s, "", new Entry("", 0, System.currentTimeMillis(), producerOf(msg)));
        } finally {
            s.ioLock.unlock();
        }
        return createMsg().setParam(RESULT, true).setParam("cleared", count)
                .setParam("scopeType", s.scopeType).setParam("scope", s.key);
    }

    private TLMsg doStats() {
        int entries = 0;
        long now = System.currentTimeMillis();
        int expired = 0;
        for (Scope s : scopes.values()) {
            for (Entry e : s.entries.values()) {
                entries++;
                if (expired(e)) expired++;
            }
        }
        return createMsg().setParam(RESULT, true)
                .setParam("scopes", scopes.size())
                .setParam("entries", entries)
                .setParam("expired", expired)
                .setParam("ttlMs", ttlMs)
                .setParam("persist", persist)
                .setParam("now", now);
    }

    // ======================== 作用域与存储 ========================

    private Scope resolveScope(TLMsg msg) {
        String scopeTypeRaw = msg.getStringParam("scopeType", "session");
        final String scopeType = "user".equalsIgnoreCase(scopeTypeRaw) ? "user" : "session";
        String userIdRaw = msg.getStringParam("userId", "default");
        final String userId = userIdRaw == null || userIdRaw.trim().isEmpty()
                ? "default" : userIdRaw;
        String rootSessionId = msg.getStringParam("rootSessionId", "");
        String sessionId = msg.getStringParam("sessionId", "");
        if (rootSessionId == null || rootSessionId.trim().isEmpty())
            rootSessionId = sessionId == null || sessionId.trim().isEmpty() ? "default" : sessionId;
        String key = "user".equals(scopeType)
                ? "user " + userId
                : "session " + userId + " " + rootSessionId;
        Scope s = scopes.computeIfAbsent(key, k -> new Scope(k, scopeType, userId));
        s.lastAccess = System.currentTimeMillis();
        loadScope(s);
        evictScopesIfNeeded(s.key);
        return s;
    }

    private String validateKey(String key) {
        if (key == null || key.isEmpty()) return "key 不能为空";
        if (key.length() > maxKeyChars)
            return "key 过长（" + key.length() + " > " + maxKeyChars + " 字符）";
        return null;
    }

    private String producerOf(TLMsg msg) {
        String producer = msg.getStringParam("producer", "");
        return producer != null ? producer : "";
    }

    /** 可选乐观锁：不传 = 现有覆盖语义；传了就必须等于当前版本（0 表示要求 key 尚不存在） */
    private Long parseExpectedVersion(TLMsg msg) {
        Object raw = msg.getParam("expectedVersion");
        if (raw == null || String.valueOf(raw).trim().isEmpty()) return null;
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expectedVersion 不是数字: " + raw);
        }
    }

    private String toText(Object value) {
        if (value == null) return "";
        if (value instanceof String) return (String) value;
        try { return gson.toJson(value); }
        catch (Exception e) { return String.valueOf(value); }
    }

    private boolean expired(Entry e) {
        return ttlMs > 0 && e != null && System.currentTimeMillis() - e.updatedAt > ttlMs;
    }

    private void evictOldestEntry(Scope s) {
        String oldestKey = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, Entry> e : s.entries.entrySet()) {
            if (e.getValue().updatedAt < oldest) {
                oldest = e.getValue().updatedAt;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null) s.entries.remove(oldestKey);
    }

    private void evictScopesIfNeeded(String keepKey) {
        if (scopes.size() <= maxScopes) return;
        List<Scope> sorted = new ArrayList<>(scopes.values());
        sorted.sort(Comparator.comparingLong(s -> s.lastAccess));
        for (Scope s : sorted) {
            if (scopes.size() <= maxScopes) break;
            if (s.key.equals(keepKey)) continue;
            scopes.remove(s.key);
        }
    }

    // ======================== 持久化 ========================

    private Path scopeFile(Scope s) {
        try {
            Path root = Paths.get(dataDir).toAbsolutePath().normalize();
            String user = TLPathSafety.safeSegment(s.userId, "default");
            Path dir = root.resolve(user).resolve("board").normalize();
            if (!dir.startsWith(root)) return null;
            return dir.resolve(sha256(s.key) + ".json");
        } catch (Exception e) {
            return null;
        }
    }

    private void loadScope(Scope s) {
        if (!persist || s.loaded) return;
        s.ioLock.lock();
        try {
            if (s.loaded) return;
            s.loaded = true;
            Path f = scopeFile(s);
            if (f == null || !Files.isRegularFile(f)) return;
            String json = Files.readString(f, StandardCharsets.UTF_8);
            PersistedScope ps = gson.fromJson(json, PersistedScope.class);
            if (ps == null || ps.entries == null) return;
            for (Map.Entry<String, PersistedEntry> e : ps.entries.entrySet()) {
                PersistedEntry pe = e.getValue();
                if (pe == null) continue;
                s.entries.put(e.getKey(), new Entry(
                        pe.value != null ? pe.value : "",
                        pe.version, pe.updatedAt, pe.producer != null ? pe.producer : ""));
            }
        } catch (Exception e) {
            putLog("sharedBoard load failed: " + e, LogLevel.WARN);
        } finally {
            s.ioLock.unlock();
        }
    }

    private void saveScope(Scope s) {
        if (!persist) return;
        Path f = scopeFile(s);
        if (f == null) return;
        try {
            Files.createDirectories(f.getParent());
            PersistedScope ps = new PersistedScope();
            ps.entries = new LinkedHashMap<>();
            for (Map.Entry<String, Entry> e : s.entries.entrySet()) {
                PersistedEntry pe = new PersistedEntry();
                pe.value = e.getValue().value;
                pe.version = e.getValue().version;
                pe.updatedAt = e.getValue().updatedAt;
                pe.producer = e.getValue().producer;
                ps.entries.put(e.getKey(), pe);
            }
            String json = gson.toJson(ps);
            Path tmp = f.resolveSibling(f.getFileName().toString() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            putLog("sharedBoard save failed: " + f + " " + e, LogLevel.WARN);
        }
    }

    private void deleteScopeFile(Scope s) {
        if (!persist) return;
        Path f = scopeFile(s);
        if (f == null) return;
        try { Files.deleteIfExists(f); }
        catch (Exception e) { putLog("sharedBoard delete file failed: " + f + " " + e, LogLevel.WARN); }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    // ======================== 事件 ========================

    private void publish(String op, Scope s, String key, Entry e) {
        try {
            Object bus = getModuleInFactory("msgBus");
            if (!(bus instanceof IObject)) return;
            TLMsg evt = createMsg().setAction(EVENT_TOPIC).setDestination(EVENT_TOPIC)
                    .setParam("op", op)
                    .setParam("scopeType", s.scopeType)
                    .setParam("scope", s.key)
                    .setParam("userId", s.userId)
                    .setParam("key", key)
                    .setParam("version", e != null ? e.version : 0)
                    .setParam("producer", e != null ? e.producer : "")
                    .setParam("ts", System.currentTimeMillis());
            putMsg((IObject) bus, evt);
        } catch (Throwable t) {
            putLog("sharedBoard publish event failed: " + t, LogLevel.DEBUG);
        }
    }

    private TLMsg error(String message) {
        return createMsg().setParam(RESULT, false).setParam("error", message);
    }

    // ======================== 数据结构 ========================

    private static final class Scope {
        final String key;
        final String scopeType;
        final String userId;
        final Map<String, Entry> entries = new ConcurrentHashMap<>();
        final ReentrantLock ioLock = new ReentrantLock();
        volatile long lastAccess = System.currentTimeMillis();
        volatile boolean loaded;

        Scope(String key, String scopeType, String userId) {
            this.key = key;
            this.scopeType = scopeType;
            this.userId = userId;
        }
    }

    private static final class Entry {
        final String value;
        final long version;
        final long updatedAt;
        final String producer;

        Entry(String value, long version, long updatedAt, String producer) {
            this.value = value;
            this.version = version;
            this.updatedAt = updatedAt;
            this.producer = producer;
        }
    }

    private static final class PersistedScope {
        Map<String, PersistedEntry> entries;
    }

    private static final class PersistedEntry {
        String value;
        long version;
        long updatedAt;
        String producer;
    }
}
