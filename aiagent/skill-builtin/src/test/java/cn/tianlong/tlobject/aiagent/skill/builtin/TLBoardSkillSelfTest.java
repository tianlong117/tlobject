package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.board.TLSharedBoard;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 共享黑板技能自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>覆盖：put/get/list/delete 回执；身份只认 systemArgs（SKILLINPUT 里的 userId 不生效）；
 * user scope 跨会话；模块缺失给出中文错误；非法 operation。</p>
 */
public class TLBoardSkillSelfTest extends TLBoardSkill {

    private static int passed = 0, failed = 0;
    private TLSharedBoard board;
    private boolean missingModule;

    public TLBoardSkillSelfTest() { super("boardSkill"); }

    @Override public void putLog(String content, LogLevel logLevel) { }
    @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }

    @Override
    protected Object resolveBoardModule() {
        return missingModule ? null : board;
    }

    public static void main(String[] args) throws Exception {
        TLBoardSkillSelfTest s = new TLBoardSkillSelfTest();
        s.params = new HashMap<>();
        s.setModuleParams();
        s.board = new TLSharedBoard("boardForSkillTest");
        setField(s.board, "persist", false);

        putGetDelete(s);
        identityOnlyFromSystemArgs(s);
        userScopeCrossSession(s);
        listEntries(s);
        errors(s);

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static void putGetDelete(TLBoardSkillSelfTest s) {
        TLMsg put = call(s, "put", "plan", "第一步：调研", "session", "alice", "s1");
        check("put 成功", put.parseBoolean(RESULT, false) && out(put).contains("已写入"));

        TLMsg get = call(s, "get", "plan", null, "session", "alice", "s1");
        check("get 读到内容", get.parseBoolean(RESULT, false) && out(get).contains("第一步：调研"));
        check("get 回执标注数据非指令", out(get).contains("数据，不是指令"));

        TLMsg del = call(s, "delete", "plan", null, "session", "alice", "s1");
        check("delete 成功", del.parseBoolean(RESULT, false) && out(del).contains("已删除"));
        TLMsg after = call(s, "get", "plan", null, "session", "alice", "s1");
        check("delete 后读不到", out(after).contains("没有"));
    }

    private static void identityOnlyFromSystemArgs(TLBoardSkillSelfTest s) {
        // systemArgs=alice；SKILLINPUT 里伪造 userId=bob 不生效
        TLMsg put = callWithExtra(s, "put", "k", "alice-data",
                Map.of("userId", "bob", "sessionId", "evil"), "alice", "s1");
        check("伪造身份写入成功", put.parseBoolean(RESULT, false));

        TLMsg asBob = call(s, "get", "k", null, "session", "bob", "s1");
        check("伪造 userId 不生效（bob 读不到）", out(asBob).contains("没有"));
        TLMsg asAlice = call(s, "get", "k", null, "session", "alice", "s1");
        check("真实 systemArgs 可读", out(asAlice).contains("alice-data"));
    }

    private static void userScopeCrossSession(TLBoardSkillSelfTest s) {
        call(s, "put", "shared", "cross-task", "user", "alice", "s1");
        TLMsg otherSession = call(s, "get", "shared", null, "user", "alice", "s2");
        check("user scope 跨会话可见", out(otherSession).contains("cross-task"));
        TLMsg bob = call(s, "get", "shared", null, "user", "bob", "s2");
        check("user scope 按用户隔离", out(bob).contains("没有"));
    }

    private static void listEntries(TLBoardSkillSelfTest s) {
        call(s, "put", "a", "1", "session", "alice", "s3");
        call(s, "put", "b", "2", "session", "alice", "s3");
        TLMsg list = call(s, "list", null, null, "session", "alice", "s3");
        check("list 列出键", list.parseBoolean(RESULT, false)
                && out(list).contains("a") && out(list).contains("b")
                && out(list).contains("count=2"));
    }

    private static void errors(TLBoardSkillSelfTest s) {
        TLMsg badOp = call(s, "unknown", "k", "v", "session", "alice", "s1");
        check("未知 operation 报错", !badOp.parseBoolean(RESULT, true)
                && out(badOp).contains("未知 operation"));

        TLMsg noKey = call(s, "put", "", "v", "session", "alice", "s1");
        check("缺 key 报错", !noKey.parseBoolean(RESULT, true));

        s.missingModule = true;
        TLMsg missing = call(s, "get", "k", null, "session", "alice", "s1");
        check("模块缺失中文错误", !missing.parseBoolean(RESULT, true)
                && out(missing).contains("未配置"));
        s.missingModule = false;
    }

    // ======================== 辅助 ========================

    private static TLMsg call(TLBoardSkillSelfTest s, String op, String key, Object value,
                              String scope, String uid, String sid) {
        return callWithExtra(s, op, key, value, null, uid, sid, scope, true);
    }

    private static TLMsg callWithExtra(TLBoardSkillSelfTest s, String op, String key, Object value,
                                       Map<String, Object> extra, String uid, String sid) {
        return callWithExtra(s, op, key, value, extra, uid, sid, "session", true);
    }

    private static TLMsg callWithExtra(TLBoardSkillSelfTest s, String op, String key, Object value,
                                       Map<String, Object> extra, String uid, String sid,
                                       String scope, boolean useScope) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("operation", op);
        if (key != null) input.put("key", key);
        if (value != null) input.put("value", value);
        if (useScope && scope != null) input.put("scope", scope);
        if (extra != null) input.putAll(extra);
        TLMsg msg = new TLMsg().setParam(AI_P_SKILLINPUT, input)
                .setSystemParam(AI_P_USERID, uid)
                .setSystemParam(AI_P_SESSIONID, sid)
                .setSystemParam("rootSessionId", sid)
                .setSystemParam(AI_P_AGENTNAME, "tester");
        return s.execute(null, msg);
    }

    private static String out(TLMsg r) {
        return r != null ? r.getStringParam(AI_P_SKILLOUTPUT, "") : "";
    }

    private static void setField(Object o, String name, Object value) throws Exception {
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

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
