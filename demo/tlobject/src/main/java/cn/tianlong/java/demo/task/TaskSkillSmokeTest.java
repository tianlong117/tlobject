package cn.tianlong.java.demo.task;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * schedule_task 技能冒烟测试（无 LLM，真实 TLObjectFactory + 真实 TLMsgTask 引擎）。
 *
 * 覆盖链路：
 *   execSkill(create/list/update/remove) → 技能 → 引擎(TLMsgTask) 注册/暂停/恢复/注销 → 持久化 JSON；
 *   到点：引擎 executeTask → stub agent(模拟 agent+toolManager 转发) → 技能 runScheduledTask
 *         → 技能 chat 回 agent 取回复 → msgBus topic taskResult 推送 → 订阅者收到事件。
 *
 * 与真实部署的差异（测试替身，非产品代码）：
 *   - chat 工厂未声明 taskScheduler 实例（Task 6 接线），本测试直接用注册表里的通用声明 msgTask；
 *   - 没有真实 aiagent（无 LLM），用 StubAgent 顶替：收 runScheduledTask 按 toolName 转发给技能，
 *     收 chat 直接回 "[stub] ..."；
 *   - 不执行 factory.boot()（只有 boot 才会拉起 database/chatConsole 等重型模块，本测试不需要，
 *     且控制台模块会抢 stdin）；工厂配置/注册表/日志模块仍走真实 chat 配置。
 *
 * 临时冒烟类（Task 8 端到端验证后删除）。
 *
 * 运行（cwd = demo/tlobject）：
 *   java -cp "<aistart.bat 的 classpath>" cn.tianlong.java.demo.task.TaskSkillSmokeTest
 *
 * 创建日期：2026/10/04 作者:tianlong
 */
public class TaskSkillSmokeTest implements TLAiAgentParamString {

    private static int pass = 0, fail = 0;

    // ======================== 测试替身 ========================

    /**
     * 最小 stub agent：模拟真实 agent 的两段行为，无 LLM。
     * 1) runScheduledTask（引擎定时回调）：按 toolName 找到技能模块并转发（模拟 agent→toolManager→技能）；
     * 2) chat（技能取回复）：直接返回 "[stub] <userMessage>"。
     */
    public static class StubAgent extends TLBaseModule {

        static final AtomicInteger scheduledCount = new AtomicInteger();
        static volatile Map<String, Object> lastScheduledMsg;
        static final List<String> chatPrompts = new CopyOnWriteArrayList<>();

        public StubAgent(String name, TLObjectFactory factory) { super(name, factory); }

        @Override
        protected TLBaseModule init() { return this; }

        @Override
        protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
            String action = msg.getAction();
            if ("runScheduledTask".equals(action)) {
                scheduledCount.incrementAndGet();
                lastScheduledMsg = new LinkedHashMap<>(msg.getArgs());
                String fn = msg.getStringParam(AI_P_TOOLNAME, null);
                Object skill = fn == null ? null : getModuleInFactory(fn);
                if (skill instanceof IObject) {
                    TLMsg fwd = createMsg().copyFrom(msg);
                    fwd.setDestination(null);   // 清 destination，否则目标模块会把消息再路由回本 agent（循环）
                    fwd.setWaitFlag(true);
                    return putMsg((IObject) skill, fwd);
                }
                // 技能模块不可达时的兜底（本测试不应走到这里）
                return createMsg().setParam(RESULT, true)
                        .setParam(AI_P_RESPONSE, "[stub] " + msg.getStringParam("taskPrompt", ""));
            }
            if ("chat".equals(action)) {
                String p = msg.getStringParam("userMessage", "");
                chatPrompts.add(p);
                return createMsg().setParam(RESULT, true).setParam(AI_P_RESPONSE, "[stub] " + p);
            }
            return null;
        }
    }

    /** msgBus topic taskResult 订阅者：收事件存静态表（测试类非 IObject，用独立模块收） */
    public static class BusProbe extends TLBaseModule {

        static final List<Map<String, Object>> taskResults = new CopyOnWriteArrayList<>();

        public BusProbe(String name, TLObjectFactory factory) { super(name, factory); }

        @Override
        protected TLBaseModule init() { return this; }

        @Override
        protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
            if ("taskResult".equals(msg.getAction())) {
                taskResults.add(new LinkedHashMap<>(msg.getArgs()));
                return createMsg().setParam(RESULT, true);
            }
            return null;
        }
    }

    // ======================== main ========================

    public static void main(String[] args) throws Exception {
        System.setProperty("log4j.configurationFile", "target/classes/conf/demo/aiagent/log4j2.xml");
        System.out.println("=== schedule_task 技能冒烟测试（真实工厂 + 真实 TLMsgTask 引擎，无 LLM）===");

        // 干净的存储根目录（绝对路径，避免污染 data/ 与历史记录）
        File storageDir = new File("target/smoke_data").getAbsoluteFile();
        deleteRecursively(storageDir);
        final String storageRoot = storageDir.getPath() + File.separator;
        final String ownerAgent = "smokeAgent";
        final String userId = "smoke_user";
        final String sessionId = "smoke_session";

        TLObjectFactory factory = TLObjectFactory.getInstance(
                "target/classes/conf/demo/aiagent", "moduleFactory_chat_config.xml");
        factory.startFactory(null, null);
        check("工厂启动（真实 chat 配置）", factory.getModule("msgTask") != null);

        // ---- 引擎：注册表通用声明 msgTask（应用实例 taskScheduler 是 Task 6 接线）----
        TLBaseModule engine = (TLBaseModule) factory.getModule("msgTask");
        check("引擎实例可用（msgTask）", engine != null);

        // ---- 测试替身模块：stub agent / msgBus 订阅者（运行期内联配置建模块，不改 conf/）----
        Object agentObj = createRuntimeModule(factory, ownerAgent,
                StubAgent.class.getName(), null);
        check("stub agent 就绪", agentObj instanceof StubAgent);
        Object probeObj = createRuntimeModule(factory, "smokeProbe",
                BusProbe.class.getName(), null);
        check("msgBus 订阅者就绪", probeObj instanceof BusProbe);
        TLBaseModule probe = (TLBaseModule) probeObj;

        // 订阅 msgBus topic taskResult（审批订阅同款手法；经 msgBus 模块名解析顺带把 msgBus 建出来）
        TLMsg reg = probe.putMsg("msgBus", probe.createMsg().setAction("registBus")
                .setParam("destination", "taskResult").setParam("object", probe));
        check("订阅 msgBus/taskResult 成功", reg != null && Boolean.TRUE.equals(reg.getParam(RESULT)));

        // ---- 技能实例：getMyModule 私有子模块语义（无真实 agent，用内联参数构造同款实例）----
        Map<String, String> skillParams = new HashMap<>();
        skillParams.put("msgTaskModule", "msgTask");
        skillParams.put("ownerAgent", ownerAgent);
        skillParams.put("storageRoot", storageRoot);
        Object skillObj = createRuntimeModule(factory, "schedule_task",
                "cn.tianlong.tlobject.aiagent.skill.builtin.TLScheduleTaskSkill", skillParams);
        check("技能实例就绪", skillObj instanceof TLBaseModule);
        TLBaseModule skill = (TLBaseModule) skillObj;

        String fullId1 = ownerAgent + "/smoke1";
        String fullId2 = ownerAgent + "/smoke2";

        // ================= 1. create（message 型，短延迟） =================
        TLMsg r = execSkill(skill, userId, sessionId,
                "op", "create", "task_id", "smoke1", "module", "log4j", "action", "openLog",
                "delay", "2", "unit", "s", "one_shot", "true");
        String out1 = r.getStringParam(AI_P_SKILLOUTPUT, "");
        check("1.1 create(message) result=true | " + oneLine(out1), Boolean.TRUE.equals(r.getParam(RESULT)));
        check("1.2 回执含『已创建定时任务』", out1.contains("已创建定时任务"));
        check("1.3 回执含『下次执行』（引擎在线，非等待调度）",
                out1.contains("下次执行") && !out1.contains("等待引擎调度"));

        // ================= 2. 引擎侧摘要 =================
        Map<String, Object> tasks = engineTasks(engine);
        check("2.1 引擎已注册 " + fullId1, tasks.containsKey(fullId1));
        Map<String, Object> info1 = asMap(tasks.get(fullId1));
        check("2.2 摘要 nextDatetime 非空", info1.get("nextDatetime") != null);
        check("2.3 摘要 executedCount=0（尚未到点）", "0".equals(String.valueOf(info1.get("executedCount"))));

        // ================= 4'. create（agent 型，核心链路） =================
        TLMsg r2 = execSkill(skill, userId, sessionId,
                "op", "create", "task_id", "smoke2", "prompt", "测试提醒",
                "delay", "2", "unit", "s", "one_shot", "true");
        String out2 = r2.getStringParam(AI_P_SKILLOUTPUT, "");
        check("2.4 create(agent) result=true | " + oneLine(out2), Boolean.TRUE.equals(r2.getParam(RESULT)));
        check("2.5 agent 型已注册引擎 " + fullId2, engineTasks(engine).containsKey(fullId2));

        // ================= 3. 到点执行（轮询 executedCount） =================
        boolean fired = waitUntil(10000, () -> {
            Object info = engineTasks(engine).get(fullId1);
            Object cnt = info instanceof Map ? ((Map<?, ?>) info).get("executedCount") : null;
            return cnt != null && "1".equals(String.valueOf(((Number) cnt).longValue()));
        });
        check("3.1 message 型到点执行（executedCount≥1）", fired);

        // ================= 4. agent 型到点全链路 =================
        boolean reached = waitUntil(10000, () -> StubAgent.scheduledCount.get() >= 1);
        check("4.1 stub agent 收到 runScheduledTask", reached);
        Map<String, Object> lastMsg = StubAgent.lastScheduledMsg;
        check("4.2 回调消息 taskId=" + fullId2,
                lastMsg != null && fullId2.equals(String.valueOf(lastMsg.get("taskId"))));
        check("4.3 回调消息 taskPrompt=测试提醒",
                lastMsg != null && "测试提醒".equals(String.valueOf(lastMsg.get("taskPrompt"))));

        boolean published = waitUntil(10000, () -> !BusProbe.taskResults.isEmpty());
        check("4.4 msgBus/taskResult 事件已推送", published);
        Map<String, Object> evt = BusProbe.taskResults.isEmpty() ? null : BusProbe.taskResults.get(0);
        check("4.5 事件 taskId 正确", evt != null && fullId2.equals(String.valueOf(evt.get("taskId"))));
        check("4.6 事件 text 含 [stub]（技能取到了 agent 回复）",
                evt != null && String.valueOf(evt.get("text")).contains("[stub]"));
        check("4.7 技能确实向 agent 发了一轮 chat（prompt=测试提醒）",
                StubAgent.chatPrompts.contains("测试提醒"));
        check("4.8 事件 userId/sessionId 透传",
                evt != null && userId.equals(String.valueOf(evt.get("userId")))
                        && sessionId.equals(String.valueOf(evt.get("sessionId"))));

        // ================= 5. 持久化 =================
        File taskFile = new File(new File(new File(storageRoot, userId), "scheduled_tasks"), ownerAgent + ".json");
        check("5.1 持久化文件存在 " + taskFile.getPath(), taskFile.exists());
        String json = taskFile.exists()
                ? new String(Files.readAllBytes(taskFile.toPath()), StandardCharsets.UTF_8) : "";
        check("5.2 文件含 smoke1/smoke2 记录", json.contains("smoke1") && json.contains("smoke2"));
        check("5.3 文件含执行计数更新（executedCount 已落盘）", json.contains("\"executedCount\": 1"));

        // ================= 6. list =================
        TLMsg rl = execSkill(skill, userId, sessionId, "op", "list");
        String listOut = rl.getStringParam(AI_P_SKILLOUTPUT, "");
        check("6.1 list 含两个任务", listOut.contains("smoke1") && listOut.contains("smoke2"));
        check("6.2 list 无 5.0/0.0 数字渲染（Gson Double 已归一化）",
                !listOut.contains("5.0") && !listOut.contains("0.0"));
        System.out.println("---- list 回执 ----\n" + listOut + "-------------------");

        // ================= 7. update 暂停 =================
        TLMsg rp = execSkill(skill, userId, sessionId, "op", "update", "task_id", "smoke2", "enabled", "false");
        check("7.1 暂停回执含『已暂停』", rp.getStringParam(AI_P_SKILLOUTPUT, "").contains("已暂停"));
        Map<String, Object> info2 = asMap(engineTasks(engine).get(fullId2));
        check("7.2 引擎侧该任务 status=stopped", "stopped".equals(String.valueOf(info2.get("status"))));

        // ================= 8. update 恢复（F1：引擎里还在）=================
        TLMsg rr = execSkill(skill, userId, sessionId, "op", "update", "task_id", "smoke2", "enabled", "true");
        check("8.1 恢复回执含『已恢复』", rr.getStringParam(AI_P_SKILLOUTPUT, "").contains("已恢复"));
        Map<String, Object> info2b = asMap(engineTasks(engine).get(fullId2));
        check("8.2 恢复后引擎侧 status=run", "run".equals(String.valueOf(info2b.get("status"))));

        // ================= 9. remove =================
        TLMsg rm = execSkill(skill, userId, sessionId, "op", "remove", "task_id", "smoke1");
        check("9.1 删除回执含『已删除』", rm.getStringParam(AI_P_SKILLOUTPUT, "").contains("已删除"));
        check("9.2 引擎中已注销 " + fullId1, !engineTasks(engine).containsKey(fullId1));

        // ================= 10. 非法 cron 拒绝 =================
        TLMsg rb = execSkill(skill, userId, sessionId,
                "op", "create", "task_id", "bad", "prompt", "x", "cron", "not a cron");
        check("10.1 非法 cron result=false", !Boolean.TRUE.equals(rb.getParam(RESULT)));
        check("10.2 错误文案含 invalid cron", rb.getStringParam(AI_P_SKILLOUTPUT, "").contains("invalid cron"));
        check("10.3 引擎里没有 bad 任务", !engineTasks(engine).containsKey(ownerAgent + "/bad"));

        // ================= 11. 跨用户隔离 =================
        TLMsg ru = execSkill(skill, "u2", "s2", "op", "list");
        String u2Out = ru.getStringParam(AI_P_SKILLOUTPUT, "");
        check("11.1 用户 u2 列表为空 | " + oneLine(u2Out), u2Out.contains("当前没有定时任务"));

        System.out.println("\n===== 冒烟结果: pass=" + pass + " fail=" + fail + " =====");
        factory.shutdown(fail == 0 ? 0 : 1);
    }

    // ======================== 工具方法 ========================

    /** 运行期用工厂消息建模块（内联模块配置；不改 conf/，等价于 XML 里写一行 module 声明） */
    private static Object createRuntimeModule(TLObjectFactory factory, String name, String classFile,
                                              Map<String, String> params) {
        HashMap<String, String> cfg = new HashMap<>();
        cfg.put("classfile", classFile);
        cfg.put("singleton", "true");
        TLMsg m = factory.createMsg().setAction("getModule")
                .setParam("moduleName", name)
                .setParam("moduleConfig", cfg);
        if (params != null && !params.isEmpty()) m.setParam("params", new HashMap<>(params));
        TLMsg r = factory.putMsg(factory, m);
        return r == null ? null : r.getParam("instance");
    }

    /** 直接以技能标准动作驱动（skillExecute + skillInput），systemArgs 带会话/用户身份 */
    private static TLMsg execSkill(TLBaseModule skill, String userId, String sessionId, Object... kv) {
        Map<String, Object> input = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) input.put(String.valueOf(kv[i]), kv[i + 1]);
        TLMsg m = skill.createMsg().setAction(SKILL_EXECUTE);
        m.setParam(AI_P_SKILLINPUT, input);
        m.setSystemParam(AI_P_SESSIONID, sessionId);
        m.setSystemParam(AI_P_USERID, userId);
        return skill.putMsg(skill, m);
    }

    /** 引擎 getTasks 摘要：fullId → info（status/nextDatetime/executedCount） */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> engineTasks(TLBaseModule engine) {
        TLMsg r = engine.putMsg(engine, engine.createMsg().setAction("getTasks"));
        Object t = r == null ? null : r.getParam("tasks");
        return t instanceof Map ? (Map<String, Object>) t : new LinkedHashMap<>();
    }

    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    private static boolean waitUntil(long timeoutMs, BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return true;
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return cond.getAsBoolean();
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        if (!f.delete()) System.out.println("[warn] 无法删除: " + f);
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace("\n", " | ");
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (ok) pass++; else fail++;
    }
}
