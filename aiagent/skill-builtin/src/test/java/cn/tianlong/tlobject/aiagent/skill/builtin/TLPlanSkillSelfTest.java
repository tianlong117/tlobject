package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.board.TLSharedBoard;
import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * plan 技能自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>覆盖：create/get/list、依赖校验（未知依赖/自依赖/环）、update_task 状态推进、
 * add_task/cancel_task 重规划记录、user 隔离、计划状态推导、planEvent、模块缺失。</p>
 */
public class TLPlanSkillSelfTest extends TLPlanSkill {

    private static int passed = 0, failed = 0;
    private TLSharedBoard board;
    private boolean missingModule;
    private FakeBus bus;

    public TLPlanSkillSelfTest() { super("planSkill"); }

    @Override public void putLog(String content, LogLevel logLevel) { }
    @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }

    @Override
    protected Object resolveBoardModule() {
        return missingModule ? null : board;
    }

    @Override
    protected Object getModuleInFactory(String moduleName) {
        return "msgBus".equals(moduleName) ? bus : null;
    }

    static class FakeBus implements IObject {
        final List<TLMsg> events = new ArrayList<>();
        @Override public String getName() { return "msgBus"; }
        @Override public TLMsg putMsg(IObject toWho, TLMsg msg) {
            events.add(msg);
            return new TLMsg().setParam(RESULT, true);
        }
        @Override public TLMsg getMsg(Object fromWho, TLMsg msg) {
            events.add(msg);
            return new TLMsg().setParam(RESULT, true);
        }
    }

    public static void main(String[] args) throws Exception {
        TLPlanSkillSelfTest s = new TLPlanSkillSelfTest();
        s.params = new HashMap<>();
        s.setModuleParams();
        s.board = new TLSharedBoard("planBoardForTest");
        setField(s.board, "persist", false);
        s.bus = new FakeBus();

        createAndGet(s);
        updateAndReplan(s);
        invalidPlans(s);
        isolationAndList(s);
        missingModule(s);

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static void createAndGet(TLPlanSkillSelfTest s) {
        TLMsg r = call(s, "create", "alice", "p1", "main", "session",
                "goal", "完成竞品调研和营销方案",
                "tasks", tasks(
                        task("t1", "调研", "research", "browserAgent", new String[]{}),
                        task("t2", "写方案", "writing", "fileAgent", new String[]{"t1"}),
                        task("t3", "预算", "calc", "codeAgent", new String[]{"t2"})));
        check("create 成功", r.parseBoolean(RESULT, false)
                && out(r).contains("计划已创建") && out(r).contains("t1")
                && out(r).contains("t2") && out(r).contains("t3"));
        check("create 发 planEvent", !s.bus.events.isEmpty()
                && "planEvent".equals(s.bus.events.get(0).getAction())
                && "create".equals(s.bus.events.get(0).getStringParam("op", "")));

        TLMsg got = call(s, "get", "alice", "p1", "main", "session");
        check("get 显示依赖与未就绪", got.parseBoolean(RESULT, false)
                && out(got).contains("deps=t1") && out(got).contains("ready=false"));
        check("get 显示 rev/status", out(got).contains("rev=1") && out(got).contains("status=active"));
    }

    private static void updateAndReplan(TLPlanSkillSelfTest s) {
        TLMsg u1 = call(s, "update_task", "alice", "p1", "main", "session",
                "taskId", "t1", "status", "done");
        check("update_task 推进状态", u1.parseBoolean(RESULT, false)
                && out(u1).contains("[x] t1"));
        TLMsg got = call(s, "get", "alice", "p1", "main", "session");
        check("t1 完成后 t2 ready", out(got).contains("[x] t1")
                && out(got).contains("ready=true"));

        TLMsg add = call(s, "add_task", "alice", "p1", "main", "session",
                "task", task("t4", "补充检索", "research", "browserAgent", new String[]{"t1"}),
                "reason", "竞品价格信息不足");
        check("add_task 新增并记录重规划", add.parseBoolean(RESULT, false)
                && out(add).contains("t4") && out(add).contains("竞品价格信息不足")
                && out(add).contains("最近重规划"));

        TLMsg cancel = call(s, "cancel_task", "alice", "p1", "main", "session",
                "taskId", "t3", "reason", "预算改由财务系统处理");
        check("cancel_task 标记 skipped", cancel.parseBoolean(RESULT, false)
                && out(cancel).contains("[-] t3"));

        call(s, "update_task", "alice", "p1", "main", "session", "taskId", "t2", "status", "done");
        call(s, "update_task", "alice", "p1", "main", "session", "taskId", "t4", "status", "done");
        TLMsg done = call(s, "get", "alice", "p1", "main", "session");
        check("全部 done/skipped 后计划 status=done",
                out(done).contains("status=done") && out(done).contains("完成 4/4"));
    }

    private static void invalidPlans(TLPlanSkillSelfTest s) {
        TLMsg cycle = call(s, "create", "alice", "p2", "cycle", "session",
                "goal", "环测试",
                "tasks", tasks(
                        task("a", "A", "", "", new String[]{"b"}),
                        task("b", "B", "", "", new String[]{"a"})));
        check("环依赖拒绝", !cycle.parseBoolean(RESULT, true) && out(cycle).contains("环"));

        TLMsg unknownDep = call(s, "create", "alice", "p2", "unk", "session",
                "goal", "未知依赖",
                "tasks", tasks(task("a", "A", "", "", new String[]{"missing"})));
        check("未知依赖拒绝", !unknownDep.parseBoolean(RESULT, true)
                && out(unknownDep).contains("依赖不存在"));

        TLMsg dup = call(s, "create", "alice", "p2", "dup", "session",
                "goal", "重复 id",
                "tasks", tasks(task("a", "A", "", "", new String[]{}),
                        task("a", "A2", "", "", new String[]{})));
        check("重复 id 拒绝", !dup.parseBoolean(RESULT, true) && out(dup).contains("重复"));

        TLMsg selfDep = call(s, "create", "alice", "p2", "self", "session",
                "goal", "自依赖",
                "tasks", tasks(task("a", "A", "", "", new String[]{"a"})));
        check("自依赖拒绝", !selfDep.parseBoolean(RESULT, true)
                && out(selfDep).contains("自己"));

        TLMsg badStatus = call(s, "update_task", "alice", "p1", "main", "session",
                "taskId", "t1", "status", "bogus");
        check("非法状态拒绝", !badStatus.parseBoolean(RESULT, true));
        TLMsg noTask = call(s, "update_task", "alice", "p1", "main", "session",
                "taskId", "nope", "status", "done");
        check("不存在任务报错", !noTask.parseBoolean(RESULT, true));

        TLMsg exists = call(s, "create", "alice", "p1", "main", "session",
                "goal", "重复创建", "tasks", tasks(task("x", "X", "", "", new String[]{})));
        check("已存在计划 create 拒绝", !exists.parseBoolean(RESULT, true));
        TLMsg overwrite = call(s, "create", "alice", "p1", "main", "session",
                "goal", "覆盖重建", "overwrite", true,
                "tasks", tasks(task("x", "X", "", "", new String[]{})));
        check("overwrite 可重建", overwrite.parseBoolean(RESULT, false)
                && out(overwrite).contains("rev=1"));
    }

    private static void isolationAndList(TLPlanSkillSelfTest s) {
        call(s, "create", "alice", "iso", "isoPlan", "session",
                "goal", "alice 的计划",
                "tasks", tasks(task("a", "A", "", "", new String[]{})));
        TLMsg bob = call(s, "get", "bob", "iso", "isoPlan", "session");
        check("跨用户隔离", out(bob).contains("没有计划"));

        TLMsg list = call(s, "list", "alice", "iso", "", "session");
        check("list 列出计划名", list.parseBoolean(RESULT, false)
                && out(list).contains("isoPlan"));
    }

    private static void missingModule(TLPlanSkillSelfTest s) {
        s.missingModule = true;
        TLMsg r = call(s, "get", "alice", "p1", "main", "session");
        check("模块缺失中文错误", !r.parseBoolean(RESULT, true)
                && out(r).contains("共享黑板模块未配置"));
        s.missingModule = false;
    }

    // ======================== 辅助 ========================

    private static TLMsg call(TLPlanSkillSelfTest s, String op, String uid, String sid,
                              String planName, String scope, Object... kv) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("operation", op);
        if (planName != null && !planName.isEmpty()) input.put("planName", planName);
        if (scope != null && !scope.isEmpty()) input.put("scope", scope);
        for (int i = 0; i + 1 < kv.length; i += 2)
            input.put((String) kv[i], kv[i + 1]);
        TLMsg msg = new TLMsg().setParam(AI_P_SKILLINPUT, input)
                .setSystemParam(AI_P_USERID, uid)
                .setSystemParam(AI_P_SESSIONID, sid)
                .setSystemParam("rootSessionId", sid)
                .setSystemParam(AI_P_AGENTNAME, "tester");
        return s.execute(null, msg);
    }

    @SafeVarargs
    private static List<Map<String, Object>> tasks(Map<String, Object>... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static Map<String, Object> task(String id, String title, String type,
                                            String owner, String[] deps) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("type", type);
        m.put("owner", owner);
        m.put("deps", Arrays.asList(deps));
        return m;
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
