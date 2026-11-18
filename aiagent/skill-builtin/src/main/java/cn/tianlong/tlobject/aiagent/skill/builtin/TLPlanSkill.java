package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结构化计划技能（函数名由配置 skillName="plan" 生效）。
 *
 * <p>补的是“计划对象”这一层：主 Agent 用 create 拆出带依赖的任务，执行过程中用
 * update_task 推进状态、add_task/cancel_task 做重规划；计划本身是 JSON，存进
 * {@link cn.tianlong.tlobject.aiagent.board.TLSharedBoard}（key=plan:&lt;name&gt;，
 * session/user scope），因此跨轮、跨子 Agent、跨会话可读，且进程重启后可恢复。</p>
 *
 * <p>它不替代工作流引擎：工作流是“固定/按次生成的执行图”，plan 是“可读写的计划状态 +
 * 重规划记录”。依赖只做计划层校验（未知依赖/自依赖/环拒绝），不会在运行中改 DAG。</p>
 *
 * <h3>操作</h3>
 * <ul>
 *   <li>create {goal, tasks:[{id,title,type,owner,deps,status,outputKey,note}], planName?, overwrite?}</li>
 *   <li>get {planName?}</li>
 *   <li>list {}</li>
 *   <li>update_task {taskId, status?, owner?, outputKey?, note?, reason?}</li>
 *   <li>add_task {task:{...}, reason?}</li>
 *   <li>cancel_task {taskId, reason?}</li>
 * </ul>
 * 计划变更发 msgBus topic {@code planEvent}；写入用 board 的 expectedVersion 做乐观锁。
 */
public class TLPlanSkill extends TLBaseSkill {

    private static final String BOARD_MODULE_DEFAULT = "sharedBoard";
    private static final String PLAN_PREFIX = "plan:";
    private static final Set<String> STATUSES = Set.of(
            "pending", "running", "done", "failed", "skipped");
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private String boardModule = BOARD_MODULE_DEFAULT;
    private int maxOutputChars = 4000;
    private final Gson gson = new Gson();

    public TLPlanSkill() { super(); }
    public TLPlanSkill(String name) { super(name); }
    public TLPlanSkill(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null) {
            if (params.get("boardModule") != null) boardModule = params.get("boardModule");
            if (params.get("maxOutputChars") != null) {
                try { maxOutputChars = Integer.parseInt(params.get("maxOutputChars")); }
                catch (NumberFormatException ignored) {}
            }
        }
        if (skillDescription == null || skillDescription.isEmpty()) {
            skillDescription = "维护结构化计划（任务拆解/DAG 依赖/状态/重规划记录）。"
                    + "operation=create/get/list/update_task/add_task/cancel_task；planName 默认 main；"
                    + "scope=session（默认）或 user（跨会话）。计划存共享黑板，跨轮/跨 Agent 可读。"
                    + "复杂任务开始时先 create，之后用 update_task 推进状态；发现缺步骤用 add_task，"
                    + "放弃某步用 cancel_task，并带上 reason。";
        }
        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();
            Map<String, Object> op = new LinkedHashMap<>();
            op.put("type", "string");
            op.put("description", "create/get/list/update_task/add_task/cancel_task");
            op.put("enum", new String[]{"create", "get", "list", "update_task", "add_task", "cancel_task"});
            op.put("required", true);
            parameterSchema.put("operation", op);

            parameterSchema.put("planName", prop("string", "计划名，默认 main；同一 scope 下唯一"));
            parameterSchema.put("scope", enumProp("session=本会话；user=同一用户跨会话", "session", "user"));
            parameterSchema.put("goal", prop("string", "create 时的总目标"));
            parameterSchema.put("tasks", prop("array",
                    "create 时的任务数组，元素 {id,title,type,owner,deps:[id],status,outputKey,note}"));
            parameterSchema.put("overwrite", prop("boolean", "create 时是否覆盖已存在计划（默认 false）"));
            parameterSchema.put("taskId", prop("string", "update_task/cancel_task 的任务 id"));
            parameterSchema.put("task", prop("object",
                    "add_task 的新任务 {id,title,type,owner,deps:[id],status,outputKey,note}"));
            parameterSchema.put("status", enumProp("任务状态", "pending", "running", "done", "failed", "skipped"));
            parameterSchema.put("owner", prop("string", "负责的 Agent/角色"));
            parameterSchema.put("outputKey", prop("string", "任务产物在共享黑板里的 key（可选）"));
            parameterSchema.put("note", prop("string", "备注"));
            parameterSchema.put("reason", prop("string", "重规划原因（add_task/cancel_task/update_task 建议填写）"));
        }
    }

    private static Map<String, Object> prop(String type, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", desc);
        return m;
    }

    private static Map<String, Object> enumProp(String desc, String... values) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("description", desc);
        m.put("enum", values);
        return m;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        Map<String, Object> input = (Map<String, Object>) msg.getMapParam(
                AI_P_SKILLINPUT, new LinkedHashMap<>());
        String op = asString(input.get("operation"), "").trim().toLowerCase();
        String planName = asString(input.get("planName"), "main").trim();
        if (planName.isEmpty()) planName = "main";
        String scope = "user".equalsIgnoreCase(asString(input.get("scope"), "session"))
                ? "user" : "session";
        String nameErr = validatePlanName(planName);
        if (nameErr != null) return fail(nameErr);

        Object board = resolveBoardModule();
        if (!(board instanceof IObject)) {
            putLog("plan board module not configured: " + boardModule, LogLevel.WARN);
            return fail("共享黑板模块未配置（" + boardModule + "），计划技能不可用");
        }
        IObject boardObj = (IObject) board;

        try {
            switch (op) {
                case "create":   return doCreate(boardObj, input, planName, scope, msg);
                case "get":      return doGet(boardObj, planName, scope, msg);
                case "list":     return doList(boardObj, scope, msg);
                case "update_task": return doUpdateTask(boardObj, input, planName, scope, msg);
                case "add_task":    return doAddTask(boardObj, input, planName, scope, msg);
                case "cancel_task": return doCancelTask(boardObj, input, planName, scope, msg);
                default:
                    return fail("未知 operation: " + op
                            + "（可用 create/get/list/update_task/add_task/cancel_task）");
            }
        } catch (Throwable t) {
            putLog("plan skill execution failed: " + t, LogLevel.ERROR);
            return fail("计划操作失败（详见错误日志）");
        }
    }

    // ======================== 操作实现 ========================

    @SuppressWarnings("unchecked")
    private TLMsg doCreate(IObject board, Map<String, Object> input, String planName,
                           String scope, TLMsg sysMsg) {
        String goal = asString(input.get("goal"), "").trim();
        if (goal.isEmpty()) return fail("create 需要 goal");
        List<Task> tasks = parseTasks(input.get("tasks"));
        String err = validatePlan(tasks);
        if (err != null) return fail(err);

        boolean overwrite = parseBool(input.get("overwrite"), false);
        long expected = 0;
        if (overwrite) {
            TLMsg existing = boardCall(board, "boardGet", planName, scope, sysMsg);
            if (existing != null && existing.parseBoolean("found", false)) {
                expected = existing.getLongParam("version", 0L);
            }
        }
        Plan plan = new Plan();
        plan.goal = goal;
        plan.revision = 1;
        plan.createdAt = System.currentTimeMillis();
        plan.updatedAt = plan.createdAt;
        plan.tasks = tasks;
        plan.status = deriveStatus(plan);

        TLMsg saved = savePlan(board, planName, scope, sysMsg, plan, expected);
        if (!saved.parseBoolean(RESULT, false)) {
            if (saved.parseBoolean("conflict", false) && !overwrite) {
                return fail("计划 " + planName + " 已存在；如需重建请传 overwrite=true");
            }
            return fail(saved.getStringParam("error", "计划保存失败"));
        }
        publishEvent("create", planName, scope, plan, "", "", "", sysMsg);
        return ok("计划已创建（rev=1）\n" + formatPlan(planName, plan));
    }

    private TLMsg doGet(IObject board, String planName, String scope, TLMsg sysMsg) {
        TLMsg got = boardCall(board, "boardGet", planName, scope, sysMsg);
        if (got == null || !got.parseBoolean(RESULT, false))
            return fail(got != null ? got.getStringParam("error", "读取失败") : "黑板无响应");
        if (!got.parseBoolean("found", false))
            return ok("没有计划 " + planName + "（scope=" + scope + "）");
        Plan plan = parsePlan(got.getStringParam("value", ""));
        if (plan == null) return fail("计划数据损坏（无法解析 JSON）");
        return ok(formatPlan(planName, plan));
    }

    private TLMsg doList(IObject board, String scope, TLMsg sysMsg) {
        TLMsg list = boardCall(board, "boardList", "", scope, sysMsg);
        if (list == null || !list.parseBoolean(RESULT, false))
            return fail(list != null ? list.getStringParam("error", "列表读取失败") : "黑板无响应");
        Object entriesObj = list.getParam("entries");
        if (!(entriesObj instanceof List)) return ok("没有计划（scope=" + scope + "）");
        StringBuilder sb = new StringBuilder("计划列表（scope=" + scope + "）：");
        int count = 0;
        for (Object o : (List<?>) entriesObj) {
            if (!(o instanceof Map)) continue;
            String key = asString(((Map<?, ?>) o).get("key"), "");
            if (!key.startsWith(PLAN_PREFIX)) continue;
            String name = key.substring(PLAN_PREFIX.length());
            TLMsg got = boardCall(board, "boardGet", name, scope, sysMsg);
            if (got == null || !got.parseBoolean("found", false)) continue;
            Plan p = parsePlan(got.getStringParam("value", ""));
            if (p == null) {
                sb.append("\n- ").append(name).append("（数据损坏）");
            } else {
                sb.append("\n- ").append(name)
                        .append(" (rev=").append(p.revision)
                        .append(", status=").append(p.status)
                        .append(", ").append(doneCount(p)).append("/").append(p.tasks.size())
                        .append(") goal=").append(shorten(p.goal, 60));
            }
            if (++count >= 50) { sb.append("\n- ...（超过 50 个，已截断）"); break; }
        }
        if (count == 0) return ok("没有计划（scope=" + scope + "）");
        return ok(truncate(sb.toString()));
    }

    private TLMsg doUpdateTask(IObject board, Map<String, Object> input, String planName,
                               String scope, TLMsg sysMsg) {
        String taskId = asString(input.get("taskId"), "").trim();
        if (taskId.isEmpty()) return fail("update_task 需要 taskId");
        ReadResult rr = readPlan(board, planName, scope, sysMsg);
        if (rr.error != null) return fail(rr.error);

        Task task = rr.plan.findTask(taskId);
        if (task == null) return fail("任务不存在: " + taskId);
        boolean changed = false;
        String status = asString(input.get("status"), "").trim().toLowerCase();
        if (!status.isEmpty()) {
            if (!STATUSES.contains(status)) return fail("非法任务状态: " + status);
            task.status = status;
            changed = true;
        }
        if (input.containsKey("owner")) { task.owner = asString(input.get("owner"), ""); changed = true; }
        if (input.containsKey("outputKey")) { task.outputKey = asString(input.get("outputKey"), ""); changed = true; }
        if (input.containsKey("note")) { task.note = asString(input.get("note"), ""); changed = true; }
        if (!changed) return fail("update_task 至少提供 status/owner/outputKey/note 之一");

        task.updatedAt = System.currentTimeMillis();
        rr.plan.revision++;
        rr.plan.updatedAt = task.updatedAt;
        rr.plan.status = deriveStatus(rr.plan);
        String reason = asString(input.get("reason"), "").trim();
        if (!reason.isEmpty()) {
            Replan r = new Replan();
            r.at = task.updatedAt;
            r.revision = rr.plan.revision;
            r.op = "update_task";
            r.taskId = taskId;
            r.reason = reason;
            rr.plan.replans.add(r);
        }
        TLMsg saved = savePlan(board, planName, scope, sysMsg, rr.plan, rr.version);
        if (!saved.parseBoolean(RESULT, false))
            return fail(saved.getStringParam("error", "计划保存失败"));
        publishEvent("update_task", planName, scope, rr.plan, taskId, task.status, reason, sysMsg);
        return ok("任务已更新\n" + formatPlan(planName, rr.plan));
    }

    private TLMsg doAddTask(IObject board, Map<String, Object> input, String planName,
                            String scope, TLMsg sysMsg) {
        Map<String, Object> taskMap = asMap(input.get("task"));
        if (taskMap == null || taskMap.isEmpty()) return fail("add_task 需要 task 对象");
        Task task = parseTask(taskMap);
        if (task == null) return fail("task 不合法（id 必填）");

        ReadResult rr = readPlan(board, planName, scope, sysMsg);
        if (rr.error != null) return fail(rr.error);
        if (rr.plan.findTask(task.id) != null) return fail("任务 id 已存在: " + task.id);
        rr.plan.tasks.add(task);
        String err = validatePlan(rr.plan.tasks);
        if (err != null) {
            rr.plan.tasks.remove(task);
            return fail(err);
        }
        String reason = asString(input.get("reason"), "新增任务").trim();
        rr.plan.revision++;
        rr.plan.updatedAt = System.currentTimeMillis();
        rr.plan.status = deriveStatus(rr.plan);
        Replan r = new Replan();
        r.at = rr.plan.updatedAt;
        r.revision = rr.plan.revision;
        r.op = "add_task";
        r.taskId = task.id;
        r.reason = reason;
        rr.plan.replans.add(r);

        TLMsg saved = savePlan(board, planName, scope, sysMsg, rr.plan, rr.version);
        if (!saved.parseBoolean(RESULT, false))
            return fail(saved.getStringParam("error", "计划保存失败"));
        publishEvent("add_task", planName, scope, rr.plan, task.id, task.status, reason, sysMsg);
        return ok("任务已新增\n" + formatPlan(planName, rr.plan));
    }

    private TLMsg doCancelTask(IObject board, Map<String, Object> input, String planName,
                               String scope, TLMsg sysMsg) {
        String taskId = asString(input.get("taskId"), "").trim();
        if (taskId.isEmpty()) return fail("cancel_task 需要 taskId");
        ReadResult rr = readPlan(board, planName, scope, sysMsg);
        if (rr.error != null) return fail(rr.error);
        Task task = rr.plan.findTask(taskId);
        if (task == null) return fail("任务不存在: " + taskId);
        String reason = asString(input.get("reason"), "").trim();
        task.status = "skipped";
        task.note = reason.isEmpty() ? task.note : reason;
        task.updatedAt = System.currentTimeMillis();
        rr.plan.revision++;
        rr.plan.updatedAt = task.updatedAt;
        rr.plan.status = deriveStatus(rr.plan);
        Replan r = new Replan();
        r.at = task.updatedAt;
        r.revision = rr.plan.revision;
        r.op = "cancel_task";
        r.taskId = taskId;
        r.reason = reason;
        rr.plan.replans.add(r);

        TLMsg saved = savePlan(board, planName, scope, sysMsg, rr.plan, rr.version);
        if (!saved.parseBoolean(RESULT, false))
            return fail(saved.getStringParam("error", "计划保存失败"));
        publishEvent("cancel_task", planName, scope, rr.plan, taskId, "skipped", reason, sysMsg);
        return ok("任务已取消\n" + formatPlan(planName, rr.plan));
    }

    // ======================== 计划校验/解析 ========================

    @SuppressWarnings("unchecked")
    private List<Task> parseTasks(Object raw) {
        List<Task> tasks = new ArrayList<>();
        List<?> list = asObjectList(raw);
        if (list == null) return tasks;
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Task t = parseTask((Map<String, Object>) o);
            if (t != null) tasks.add(t);
        }
        return tasks;
    }

    @SuppressWarnings("unchecked")
    private Task parseTask(Map<String, Object> m) {
        if (m == null) return null;
        String id = asString(m.get("id"), "").trim();
        if (id.isEmpty()) return null;
        Task t = new Task();
        t.id = id;
        t.title = asString(m.get("title"), id);
        t.type = asString(m.get("type"), "");
        t.owner = asString(m.get("owner"), "");
        t.outputKey = asString(m.get("outputKey"), "");
        t.note = asString(m.get("note"), "");
        String status = asString(m.get("status"), "pending").trim().toLowerCase();
        t.status = STATUSES.contains(status) ? status : "pending";
        Object deps = m.get("deps");
        if (deps instanceof List) {
            for (Object d : (List<?>) deps) {
                String s = asString(d, "").trim();
                if (!s.isEmpty()) t.deps.add(s);
            }
        } else if (deps != null) {
            for (String s : String.valueOf(deps).split(","))
                if (!s.trim().isEmpty()) t.deps.add(s.trim());
        }
        t.updatedAt = System.currentTimeMillis();
        return t;
    }

    private String validatePlan(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) return null;
        Map<String, Task> byId = new LinkedHashMap<>();
        for (Task t : tasks) {
            if (t.id == null || t.id.isEmpty()) return "任务 id 不能为空";
            if (t.id.length() > 64) return "任务 id 过长: " + t.id;
            if (byId.containsKey(t.id)) return "任务 id 重复: " + t.id;
            if (!STATUSES.contains(t.status)) return "非法任务状态: " + t.status;
            byId.put(t.id, t);
        }
        for (Task t : tasks) {
            for (String d : t.deps) {
                if (d.equals(t.id)) return "任务 " + t.id + " 不能依赖自己";
                if (!byId.containsKey(d)) return "任务 " + t.id + " 的依赖不存在: " + d;
            }
        }
        // 环检测（DFS）
        Map<String, Integer> color = new HashMap<>(); // 0=未访问 1=访问中 2=完成
        for (Task t : tasks) {
            String cycle = dfsCycle(t.id, byId, color);
            if (cycle != null) return "计划依赖存在环: " + cycle;
        }
        return null;
    }

    private String dfsCycle(String id, Map<String, Task> byId, Map<String, Integer> color) {
        Integer c = color.getOrDefault(id, 0);
        if (c == 1) return id;
        if (c == 2) return null;
        color.put(id, 1);
        Task t = byId.get(id);
        if (t != null) {
            for (String d : t.deps) {
                String cyc = dfsCycle(d, byId, color);
                if (cyc != null) return id + "→" + cyc;
            }
        }
        color.put(id, 2);
        return null;
    }

    private String validatePlanName(String planName) {
        if (planName == null || planName.isEmpty()) return "planName 不能为空";
        if (planName.length() > 64) return "planName 过长（>64）";
        for (int i = 0; i < planName.length(); i++)
            if (Character.isISOControl(planName.charAt(i)))
                return "planName 含控制字符";
        return null;
    }

    private String deriveStatus(Plan p) {
        if (p == null || p.tasks == null || p.tasks.isEmpty()) return "active";
        for (Task t : p.tasks)
            if (!"done".equals(t.status) && !"skipped".equals(t.status)) return "active";
        return "done";
    }

    private int doneCount(Plan p) {
        int n = 0;
        if (p != null && p.tasks != null)
            for (Task t : p.tasks)
                if ("done".equals(t.status) || "skipped".equals(t.status)) n++;
        return n;
    }

    // ======================== 黑板读写 ========================

    private static final class ReadResult {
        Plan plan;
        long version;
        String error;
    }

    private ReadResult readPlan(IObject board, String planName, String scope, TLMsg sysMsg) {
        ReadResult r = new ReadResult();
        TLMsg got = boardCall(board, "boardGet", planName, scope, sysMsg);
        if (got == null || !got.parseBoolean(RESULT, false)) {
            r.error = got != null ? got.getStringParam("error", "读取计划失败") : "黑板无响应";
            return r;
        }
        if (!got.parseBoolean("found", false)) {
            r.error = "计划不存在: " + planName + "（先 create）";
            return r;
        }
        Plan p = parsePlan(got.getStringParam("value", ""));
        if (p == null) {
            r.error = "计划数据损坏（无法解析 JSON）";
            return r;
        }
        r.plan = p;
        r.version = got.getLongParam("version", 0L);
        return r;
    }

    private TLMsg savePlan(IObject board, String planName, String scope, TLMsg sysMsg,
                           Plan plan, long expectedVersion) {
        return boardCall(board, "boardPut", planName, scope, sysMsg,
                "value", gson.toJson(plan), "expectedVersion", expectedVersion);
    }

    private TLMsg boardCall(IObject board, String action, String planName, String scope,
                            TLMsg sysMsg, Object... extra) {
        TLMsg m = createMsg().setAction(action)
                .setParam("key", PLAN_PREFIX + planName)
                .setParam("scopeType", scope)
                .setParam("userId", system(sysMsg, AI_P_USERID, "default"))
                .setParam("sessionId", system(sysMsg, AI_P_SESSIONID, "default"))
                .setParam("rootSessionId", system(sysMsg, "rootSessionId", ""))
                .setParam("producer", system(sysMsg, AI_P_AGENTNAME,
                        system(sysMsg, AI_P_TOOLNAME, "plan")));
        for (int i = 0; i + 1 < extra.length; i += 2)
            m.setParam(String.valueOf(extra[i]), extra[i + 1]);
        return putMsg(board, m);
    }

    private Plan parsePlan(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            Plan p = gson.fromJson(json, Plan.class);
            if (p == null) return null;
            if (p.tasks == null) p.tasks = new ArrayList<>();
            if (p.replans == null) p.replans = new ArrayList<>();
            if (p.status == null || p.status.isEmpty()) p.status = deriveStatus(p);
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    /** 取共享黑板模块（测试/子类可覆写注入） */
    protected Object resolveBoardModule() {
        return moduleFactory != null ? moduleFactory.getModule(boardModule) : null;
    }

    // ======================== 输出与事件 ========================

    private String formatPlan(String planName, Plan p) {
        StringBuilder sb = new StringBuilder();
        sb.append("计划 ").append(planName)
                .append("（rev=").append(p.revision)
                .append(", status=").append(p.status)
                .append(", 完成 ").append(doneCount(p)).append("/").append(p.tasks.size())
                .append(", 更新=").append(ts(p.updatedAt))
                .append("）");
        if (p.goal != null && !p.goal.isEmpty())
            sb.append("\n目标：").append(p.goal);
        sb.append("\n任务：");
        if (p.tasks.isEmpty()) {
            sb.append("\n（空）");
        } else {
            for (Task t : p.tasks) {
                sb.append("\n- ").append(marker(t.status)).append(" ").append(t.id);
                if (t.title != null && !t.title.isEmpty() && !t.title.equals(t.id))
                    sb.append(" ").append(t.title);
                List<String> meta = new ArrayList<>();
                if (t.type != null && !t.type.isEmpty()) meta.add("type=" + t.type);
                if (t.owner != null && !t.owner.isEmpty()) meta.add("owner=" + t.owner);
                if (t.deps != null && !t.deps.isEmpty()) meta.add("deps=" + String.join(",", t.deps));
                if (t.outputKey != null && !t.outputKey.isEmpty()) meta.add("output=" + t.outputKey);
                if ("pending".equals(t.status)) meta.add("ready=" + ready(t, p));
                if (t.note != null && !t.note.isEmpty()) meta.add("note=" + shorten(t.note, 80));
                if (!meta.isEmpty()) sb.append(" (").append(String.join(", ", meta)).append(")");
            }
        }
        if (!p.replans.isEmpty()) {
            sb.append("\n最近重规划：");
            int from = Math.max(0, p.replans.size() - 4);
            for (int i = from; i < p.replans.size(); i++) {
                Replan r = p.replans.get(i);
                sb.append("\n- rev").append(r.revision).append(" ").append(r.op);
                if (r.taskId != null && !r.taskId.isEmpty()) sb.append(" ").append(r.taskId);
                if (r.reason != null && !r.reason.isEmpty()) sb.append("（").append(shorten(r.reason, 100)).append("）");
            }
        }
        return truncate(sb.toString());
    }

    private boolean ready(Task t, Plan p) {
        if (t.deps == null || t.deps.isEmpty()) return true;
        for (String d : t.deps) {
            Task dep = p.findTask(d);
            if (dep == null || !"done".equals(dep.status)) return false;
        }
        return true;
    }

    private static String marker(String status) {
        if (status == null) return "[ ]";
        switch (status) {
            case "running": return "[~]";
            case "done": return "[x]";
            case "failed": return "[!]";
            case "skipped": return "[-]";
            default: return "[ ]";
        }
    }

    private void publishEvent(String op, String planName, String scope, Plan p,
                              String taskId, String taskStatus, String reason, TLMsg sysMsg) {
        try {
            Object bus = getModuleInFactory("msgBus");
            if (!(bus instanceof IObject)) return;
            TLMsg evt = createMsg().setAction("planEvent").setDestination("planEvent")
                    .setParam("op", op)
                    .setParam("planName", planName)
                    .setParam("scopeType", scope)
                    .setParam("revision", p.revision)
                    .setParam("goal", p.goal != null ? p.goal : "")
                    .setParam("taskId", taskId != null ? taskId : "")
                    .setParam("taskStatus", taskStatus != null ? taskStatus : "")
                    .setParam("reason", reason != null ? reason : "")
                    .setParam("userId", system(sysMsg, AI_P_USERID, "default"))
                    .setParam("sessionId", system(sysMsg, AI_P_SESSIONID, "default"))
                    .setParam("rootSessionId", system(sysMsg, "rootSessionId", ""))
                    .setParam("ts", System.currentTimeMillis());
            putMsg((IObject) bus, evt);
        } catch (Throwable t) {
            putLog("plan event publish failed: " + t, LogLevel.DEBUG);
        }
    }

    private TLMsg ok(String text) {
        return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLOUTPUT, truncate(text));
    }

    private TLMsg fail(String message) {
        return createMsg().setParam(RESULT, false)
                .setParam(AI_P_SKILLOUTPUT, "计划操作失败：" + message);
    }

    private String truncate(String text) {
        if (text == null) return "";
        if (maxOutputChars <= 0 || text.length() <= maxOutputChars) return text;
        return text.substring(0, maxOutputChars) + "\n...[已截断]";
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String system(TLMsg msg, String key, String def) {
        Object v = msg != null ? msg.getSystemParam(key, def) : def;
        if (v == null || String.valueOf(v).trim().isEmpty()) return def;
        return String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static List<?> asObjectList(Object raw) {
        if (raw instanceof List) return (List<?>) raw;
        if (raw instanceof String && !((String) raw).trim().isEmpty()) {
            try { return new Gson().fromJson((String) raw, List.class); }
            catch (Exception e) { return null; }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        if (raw instanceof Map) return (Map<String, Object>) raw;
        if (raw instanceof String && !((String) raw).trim().isEmpty()) {
            try { return new Gson().fromJson((String) raw, Map.class); }
            catch (Exception e) { return null; }
        }
        return null;
    }

    private static boolean parseBool(Object raw, boolean def) {
        if (raw == null) return def;
        if (raw instanceof Boolean) return (Boolean) raw;
        return Boolean.parseBoolean(String.valueOf(raw));
    }

    private static String ts(long millis) {
        return millis <= 0 ? "-" : TS_FMT.format(Instant.ofEpochMilli(millis));
    }

    // ======================== 计划数据结构 ========================

    static final class Plan {
        String goal;
        String status = "active";
        int revision = 1;
        long createdAt;
        long updatedAt;
        List<Task> tasks = new ArrayList<>();
        List<Replan> replans = new ArrayList<>();

        Task findTask(String id) {
            if (id == null) return null;
            for (Task t : tasks) if (id.equals(t.id)) return t;
            return null;
        }
    }

    static final class Task {
        String id;
        String title;
        String type;
        String owner;
        List<String> deps = new ArrayList<>();
        String status = "pending";
        String outputKey;
        String note;
        long updatedAt;
    }

    static final class Replan {
        long at;
        int revision;
        String op;
        String taskId;
        String reason;
    }
}
