# 定时任务技能（schedule_task）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户在聊天窗口用自然语言创建定时任务，任务到点经 agent 完整跑一轮（或发固定消息），结果落回用户当前活动会话并实时推送到 web 聊天窗口/控制台。

**Architecture:** 新技能 `TLScheduleTaskSkill` 对接框架定时任务模块 `TLMsgTask`（`taskScheduler` 实例）。技能负责：参数校验、任务消息构造（destination=所属 agent、action=`runScheduledTask`）、持久化（`data/<userId>/scheduled_tasks/<agent>.json`）、启动恢复。执行期：`TLMsgTask` 把任务消息发给 agent → agent 反射落到技能方法 → 技能解析目标会话（webui `getCurrentSession`，失败回退创建时会话）→ 转发 `chat` → 结果落库 + msgBus 发 `taskResult` 事件 → web SSE / 控制台推送。

**Tech Stack:** Java 17、Maven、quartz `CronExpression`（crontab 已有依赖）、Gson、msgBus（框架消息总线）、原生 JS `EventSource`。

**设计文档:** `docs/superpowers/specs/2026-10-04-scheduled-task-skill-design.md`

**结论快照（核实过的机制，实施时不要再改）：**
- 技能家族名 = `agent:skill`（如 `aiagent_master:schedule_task`）；任务消息 destination 必须用 **agent 名**（`aiagent_master`），action 用技能方法名 → agent 的 `invokeAction` 反射找不到会落到父类再向上找，最终命中技能类的 `runScheduledTask(Object, TLMsg)`。
- `TLMsg.copyFrom` 复制 systemArgs（`TLMsg.java:520`）→ 会话/用户放 `setSystemParam` 才能随任务消息留存。
- `TLMsgTask.doGetTask` 单任务分支已暴露 `nextExecuteTime`/`executedCount`；摘要分支缺字段（本计划 Task 1 补）。
- 引擎无 cron 无 delay 时默认 `delay=60, period=60` 静默兜底 → 技能层必须显式拒绝。
- `putMsg("不存在的模块")` 默认 `moduleFactory.shutdown(-1)` 关进程 → 凡"可能是可选模块"的调用一律 `setSystemParam(IGNOREMODULEISNULL, true)` 并用 `getModuleInFactory` 判空。
- 技能 vs agent 互发消息的 action 名若与 agent 自有 switch 冲突会被抢（如 `chat`）→ 本设计用 `runScheduledTask`（agent 无此 case）。

---

### Task 1: 引擎小补丁（TLMsgTask：nextDatetime 写 config + 摘要补字段）

**Files:**
- Modify: `crontab/src/main/java/cn/tianlong/tlobject/modules/TLMsgTask.java`（`startTask` 约 :395-416、`doGetTask` 摘要分支 :327-338）

- [ ] **Step 1: 给 startTask 补 nextDatetime**

在 `startTask` 里，`ScheduledFuture<?> future;`（现 :395）之前插入：

```java
        // 下次执行时间（对外可见：getTask 摘要/详情都读 config 的 nextDatetime）
        long nextFire;
```

把现在的：

```java
        ScheduledFuture<?> future;

        if (cronExp != null && !cronExp.isEmpty()) {
            // Cron 模式：动态调度
            future = scheduleCronTask(taskId, config, cronExp, initialDelay, timeUnit, maxTimes);
        } else {
            // 固定延迟模式
            if (period <= 0) period = 60;
            future = executor.scheduleAtFixedRate(
                    createTaskRunnable(taskId, config, maxTimes),
                    initialDelay, period, timeUnit
            );
        }
```

改为：

```java
        ScheduledFuture<?> future;

        if (cronExp != null && !cronExp.isEmpty()) {
            // Cron 模式：动态调度
            future = scheduleCronTask(taskId, config, cronExp, initialDelay, timeUnit, maxTimes);
            nextFire = nextCronFire(cronExp, config);
        } else {
            // 固定延迟模式
            if (period <= 0) period = 60;
            future = executor.scheduleAtFixedRate(
                    createTaskRunnable(taskId, config, maxTimes),
                    initialDelay, period, timeUnit
            );
            long periodMs = TimeUnit.MILLISECONDS.convert(period, timeUnit);
            nextFire = System.currentTimeMillis() + initialDelay + periodMs;
        }
```

在 `startTask` 方法**末尾**（`putLog("任务 [" + taskId + "] 已启动" ...)` 之后）追加：

```java
        config.setParam("nextDatetime", new Date(nextFire));
```

在 `startTask` 方法后新增私有方法：

```java
    /**
     * 取 cron 的下次触发时间；解析失败返回首次调度时间（scheduleCronTask 已报错并置状态）。
     * 用于把"下次执行时间"写到 config，供 getTask 对外暴露。
     */
    private long nextCronFire(String cronExp, TLMsg config) {
        long fallback = System.currentTimeMillis();
        try {
            CronExpression cron = new CronExpression(cronExp);
            Date next = cron.getTimeAfter(new Date());
            if (next != null) return next.getTime();
        } catch (ParseException ignored) {
        }
        Object nd = config.getParam("nextDatetime");
        if (nd instanceof Date) return ((Date) nd).getTime();
        return fallback;
    }
```

- [ ] **Step 2: 给 doGetTask 摘要分支补字段**

把 `doGetTask` 摘要循环（现 :329-337）：

```java
        for (Map.Entry<String, TLMsg> e : taskConfigs.entrySet()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("destination", e.getValue().getDestination());
            info.put("action", e.getValue().getAction());
            info.put("msgId", e.getValue().getMsgId());
            info.put("status", taskRuntimes.containsKey(e.getKey()) ?
                    taskRuntimes.get(e.getKey()).status : STATUS_STOPPED);
            all.put(e.getKey(), info);
        }
```

改为：

```java
        for (Map.Entry<String, TLMsg> e : taskConfigs.entrySet()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("destination", e.getValue().getDestination());
            info.put("action", e.getValue().getAction());
            info.put("msgId", e.getValue().getMsgId());
            TaskRuntime rt = taskRuntimes.get(e.getKey());
            info.put("status", rt != null ? rt.status : STATUS_STOPPED);
            // 下次执行时间：固定间隔任务由 startTask 写入、cron 任务每次执行时刷新（:474 附近）
            if (e.getValue().getParam("nextDatetime") != null)
                info.put("nextDatetime", e.getValue().getParam("nextDatetime"));
            info.put("executedCount", rt != null ? rt.executedCount.get() : 0);
            all.put(e.getKey(), info);
        }
```

- [ ] **Step 3: 编译 crontab 模块**

Run: `mvn -f D:/tlobjectapp/tlobject/pom.xml clean install -pl crontab -am -DskipTests`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: 提交**

```bash
git add crontab/src/main/java/cn/tianlong/tlobject/modules/TLMsgTask.java
git commit -m "TLMsgTask 补丁：startTask 写 nextDatetime（两类调度），getTask 摘要暴露 nextDatetime/executedCount"
```

---

### Task 2: 技能骨架 + 持久化 + create 操作

**Files:**
- Create: `aiagent/skill-builtin/src/main/java/cn/tianlong/tlobject/aiagent/skill/builtin/TLScheduleTaskSkill.java`
- Modify: `aiagent/skill-builtin/pom.xml`（加 crontab 依赖）

- [ ] **Step 1: pom 加 crontab 依赖**

在 `aiagent/skill-builtin/pom.xml` 的 `<dependencies>` 中，`tlobject-aiagent-common` 之后加：

```xml
        <!-- 定时任务引擎（TLMsgTask）与 quartz CronExpression（cron 本地校验） -->
        <dependency>
            <groupId>cn.tianlong.tlobject</groupId>
            <artifactId>tlobject-crontab</artifactId>
            <version>${project.version}</version>
        </dependency>
```

- [ ] **Step 2: 写技能类（骨架 + 持久化 + create + runScheduledTask）**

创建 `aiagent/skill-builtin/src/main/java/cn/tianlong/tlobject/aiagent/skill/builtin/TLScheduleTaskSkill.java`：

```java
package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.quartz.CronExpression;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 定时任务技能（函数名 schedule_task）：聊天里用自然语言创建定时任务。
 *
 * 对接框架定时任务模块 TLMsgTask（配置为应用实例 taskScheduler）：
 * - agent 型任务：destination=所属 agent、action=runScheduledTask；
 *   到点由 agent 反射落到本技能 runScheduledTask()，
 *   此时解析目标会话（webui getCurrentSession，失败回退创建时会话）再转发 chat。
 * - message 型任务：destination=目标模块、action=目标动作，到点直接发出，不经 LLM。
 *
 * 持久化：data/&lt;userId&gt;/scheduled_tasks/&lt;ownerAgent&gt;.json，进程启动（runStartMsg）自动恢复。
 * 用户隔离：list/remove/update 只操作当前用户记录（按 userId + owner 过滤）。
 *
 * 创建日期：2026/10/04 作者:tianlong
 */
public class TLScheduleTaskSkill extends TLBaseSkill {

    /** 定时任务引擎模块名（应用工厂里声明的 TLMsgTask 实例） */
    private String msgTaskModule = "taskScheduler";
    /** 持久化根目录（相对 configDir；绝对路径原样使用） */
    private String storageRoot = "./data/";
    /** 所属 agent 名（任务 destination；家族名 = agent:技能名） */
    private String ownerAgent;
    /** 当前 sessionId → userId 缓存（runScheduledTask 无 userId 时回查） */
    private final Map<String, String> sessionUsers = new java.util.concurrent.ConcurrentHashMap<>();
    /** 启动恢复守卫（每进程一次） */
    private volatile boolean restored = false;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public TLScheduleTaskSkill() { super(); }
    public TLScheduleTaskSkill(String name) { super(name); }
    public TLScheduleTaskSkill(String name, TLObjectFactory factory) { super(name, factory); }

    /** 持久化记录（Gson 直序列化，字段用 public） */
    public static class TaskRecord {
        public String taskId;
        public String owner;
        public String userId;
        public String creationSessionId;
        public String type;               // agent | message
        public String prompt;             // agent 型
        public String module;             // message 型
        public String action;
        public String argsJson;
        public Map<String, Object> schedule = new LinkedHashMap<>();
        public boolean enabled = true;
        public long createdAt;
        public long executedCount;
        public long lastExecuteTime;
    }

    // ======================== 配置与信息 ========================

    @Override
    protected void setModuleParams() {
        // 先读自身参数：super 会触发 loadSkillMd（可能需要 ownerAgent 已就绪）
        if (params != null) {
            if (params.get("msgTaskModule") != null) msgTaskModule = params.get("msgTaskModule");
            if (params.get("storageRoot") != null) storageRoot = params.get("storageRoot");
            if (params.get("ownerAgent") != null) ownerAgent = params.get("ownerAgent");
        }
        super.setModuleParams();
        if (ownerAgent == null || ownerAgent.isEmpty()) ownerAgent = ownerName();

        if (skillName == null || skillName.isEmpty()) skillName = "schedule_task";
        if (skillDescription == null || skillDescription.isEmpty())
            skillDescription = "Create and manage scheduled tasks. The task fires later and either runs a full agent"
                    + " round (natural-language prompt) or sends a fixed message. Use op=create/list/remove/update.";

        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();
            parameterSchema.put("op", prop("string",
                    "Operation: create | list | remove | update", true));
            parameterSchema.put("prompt", prop("string",
                    "For create, agent-type: the natural-language instruction executed at each fire (e.g. '查一下今天上证指数并汇报')"));
            parameterSchema.put("module", prop("string", "For create, message-type: target module name (no LLM involved)"));
            parameterSchema.put("action", prop("string", "For create, message-type: target action on that module"));
            parameterSchema.put("args", prop("string", "For create, message-type: optional JSON object of fixed params"));
            parameterSchema.put("cron", prop("string",
                    "Quartz 6-field cron for recurring schedule, e.g. '0 0 8 * * ?' (daily 8:00)"));
            parameterSchema.put("delay", prop("string",
                    "Fixed interval value (with unit); or first-fire delay when cron is also given"));
            parameterSchema.put("unit", prop("string", "Interval unit: s | m | h (default s)"));
            parameterSchema.put("times", prop("string", "Max executions, 0 or absent = unlimited"));
            parameterSchema.put("begin", prop("string", "Extra delay before first fire (seconds)"));
            parameterSchema.put("one_shot", prop("string", "true = run exactly once (delay + times=1)"));
            parameterSchema.put("task_id", prop("string", "Optional task id (letters/digits/_ only)"));
            parameterSchema.put("description", prop("string", "Human-readable description (also used to auto-generate task id)"));
            parameterSchema.put("enabled", prop("string", "For update: true=resume, false=pause"));
        }
    }

    private Map<String, Object> prop(String type, String desc) { return prop(type, desc, false); }

    private Map<String, Object> prop(String type, String desc, boolean required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", desc);
        if (required) m.put("required", true);
        return m;
    }

    /** 家族名（agent:技能名）最后一段即所属 agent */
    private String ownerName() {
        String fn = getFamilyName();
        if (fn == null || fn.isEmpty()) return name;
        int i = fn.lastIndexOf(':');
        return i >= 0 ? fn.substring(0, i) : fn;
    }

    // ======================== 入口 ========================

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        Map<String, Object> input = (Map<String, Object>) msg.getMapParam(AI_P_SKILLINPUT, new LinkedHashMap<>());
        String op = str(input, "op", null);
        if (op == null) return fail("missing required parameter: op");

        // 身份（系统参数区；与 TLAiAgent.doChat 同款的 userId 回退 sessionId 规则）
        String sessionId = str(input, "sessionId", null);
        if (sessionId == null) sessionId = String.valueOf(msg.getSystemParam(AI_P_SESSIONID, "default"));
        String userId = str(input, "userId", null);
        if (userId == null) {
            Object u = msg.getSystemParam("userId", null);
            userId = (u != null && !String.valueOf(u).isEmpty()) ? String.valueOf(u) : sessionId;
        }
        sessionUsers.put(sessionId, userId);

        switch (op) {
            case "create": return createTask(input, sessionId, userId);
            case "list":   return listTasks(userId);
            case "remove": return removeTask(input, userId);
            case "update": return updateTask(input, userId);
            default:       return fail("unknown op: " + op + " (use create/list/remove/update)");
        }
    }

    /**
     * 定时任务执行入口（agent 反射调用）。
     * 任务消息 destination=agent、action=runScheduledTask → 落到这里；
     * 此刻解析目标会话（当前活动会话，失败回退创建时会话），再转发给 agent 的 chat。
     */
    protected TLMsg runScheduledTask(Object fromWho, TLMsg msg) {
        String prompt = msg.getStringParam("taskPrompt", "");
        String recSession = msg.getStringParam("recordSession", null);
        String taskId = msg.getStringParam("taskId", "?");
        String sessionId = msg.getStringParam("sessionId", null);
        String userId = msg.getStringParam("userId", null);
        if (userId == null || userId.isEmpty())
            userId = recSession != null ? sessionUsers.getOrDefault(recSession, recSession) : "default";
        if (sessionId == null || sessionId.isEmpty())
            sessionId = recSession != null ? recSession : "default";

        // 执行时解析目标会话：用户当前活动会话可用则用，否则回退创建时会话（保证必达不丢）
        String target = resolveTargetSession(userId);
        if (target == null || target.isEmpty()) target = sessionId;

        TLMsg chat = createMsg()
                .setParam("userMessage", prompt)
                .setParam("taskId", taskId);
        chat.setSystemParam(AI_P_SESSIONID, target);
        chat.setSystemParam("userId", userId);

        Object agent = getModuleInFactory(ownerAgent);
        if (!(agent instanceof IObject)) {
            putLog("定时任务 [" + taskId + "] 找不到 agent 模块: " + ownerAgent, LogLevel.ERROR);
            return null;
        }
        TLMsg r = putMsg((IObject) agent, chat);
        String answer = r == null ? null : r.getStringParam(AI_P_RESPONSE, null);
        if (answer == null || answer.isEmpty()) answer = "(无文本回复)";
        updateRecordAfterRun(taskId, target);
        publishTaskResult(userId, target, taskId, answer, recSession);
        return r;
    }

    /** 解析用户当前活动会话：经 webui 查询；无 webui 模块/用户不在线返回 null（调用方回退） */
    private String resolveTargetSession(String userId) {
        try {
            Object webui = getModuleInFactory("webui");
            if (!(webui instanceof IObject)) return null;
            TLMsg q = createMsg().setAction("getCurrentSession").setParam("userId", userId);
            q.setSystemParam(IGNOREMODULEISNULL, true);
            TLMsg r = putMsg((IObject) webui, q);
            if (r == null) return null;
            String sid = r.getStringParam("sessionId", "");
            return sid.isEmpty() ? null : sid;
        } catch (Exception e) {
            putLog("查询当前活动会话失败（回退创建时会话）: " + e, LogLevel.DEBUG);
            return null;
        }
    }

    /** 结果推送：msgBus topic taskResult → web SSE / 控制台（订阅方自行渲染） */
    private void publishTaskResult(String userId, String sessionId, String taskId,
                                   String answer, String creationSessionId) {
        try {
            Object bus = getModuleInFactory("msgBus");
            if (!(bus instanceof IObject)) return;
            String text = answer.length() > 500 ? answer.substring(0, 500) + "…" : answer;
            TLMsg evt = createMsg().setAction("taskResult")
                    .setParam("userId", userId)
                    .setParam("sessionId", sessionId)
                    .setParam("taskId", taskId)
                    .setParam("text", text)
                    .setParam("creationSessionId", creationSessionId == null ? "" : creationSessionId);
            evt.setDestination("taskResult");   // 总线按 destination 路由到订阅者
            putMsg((IObject) bus, evt);
        } catch (Exception e) {
            putLog("推送任务结果失败: " + e, LogLevel.DEBUG);
        }
    }

    // ======================== create ========================

    private TLMsg createTask(Map<String, Object> input, String sessionId, String userId) {
        String prompt = str(input, "prompt", null);
        String module = str(input, "module", null);
        String action = str(input, "action", null);
        if (prompt != null && module != null)
            return fail("prompt (agent task) and module/action (message task) are mutually exclusive");
        if (prompt == null && (module == null || action == null))
            return fail("agent task needs 'prompt'; message task needs 'module' + 'action'");

        // ---- 调度参数（技能层显式判定，不依赖引擎缺省）----
        String cron = str(input, "cron", null);
        Long delay = longOf(input, "delay");
        Long times = longOf(input, "times");
        Long begin = longOf(input, "begin");
        boolean oneShot = "true".equalsIgnoreCase(str(input, "one_shot", "false"));
        String unit = str(input, "unit", "s");
        if (!unit.equals("s") && !unit.equals("m") && !unit.equals("h"))
            return fail("unit must be s|m|h");
        if (cron != null && !CronExpression.isValidExpression(cron))
            return fail("invalid cron expression: " + cron);
        if (cron == null && delay == null && !oneShot)
            return fail("需要明确调度：给 cron（周期）或 delay（间隔秒数）或 one_shot=true");
        if (oneShot && delay == null) delay = 0L;
        if (oneShot) times = 1L;

        // ---- 任务 id：<ownerAgent>/<taskId> 前缀化，防多个 agent 共用引擎时撞车 ----
        String taskId = str(input, "task_id", null);
        if (taskId == null || taskId.isEmpty()) {
            String desc = str(input, "description", "task");
            String slug = desc.replaceAll("[^0-9A-Za-z\\u4e00-\\u9fa5]+", "");
            if (slug.length() > 12) slug = slug.substring(0, 12);
            taskId = "task_" + slug + "_" + System.currentTimeMillis();
        }
        if (!taskId.matches("[0-9A-Za-z_\\u4e00-\\u9fa5-]+"))
            return fail("task_id 只允许字母/数字/下划线/中划线");
        String fullId = ownerAgent + "/" + taskId;
        if (loadRecords().containsKey(fullId))
            return fail("任务已存在: " + taskId + "（换一个 task_id 或用 update）");

        // ---- 记录 + 注册 ----
        TaskRecord rec = new TaskRecord();
        rec.taskId = taskId;
        rec.owner = ownerAgent;
        rec.userId = userId;
        rec.creationSessionId = sessionId;
        rec.type = prompt != null ? "agent" : "message";
        rec.prompt = prompt;
        rec.module = module;
        rec.action = action;
        rec.argsJson = str(input, "args", null);
        rec.schedule.put("cron", cron);
        rec.schedule.put("delay", delay);
        rec.schedule.put("unit", unit);
        rec.schedule.put("times", times);
        rec.schedule.put("begin", begin);
        rec.enabled = true;
        rec.createdAt = System.currentTimeMillis();

        TLMsg reg = buildRegistMsg(rec);
        if (reg == null) return fail("任务参数构造失败（见日志）");
        TLMsg r = sendToEngine(reg);
        if (r == null || !Boolean.TRUE.equals(r.getParam(RESULT))) {
            String err = r == null ? "引擎无响应" : r.getStringParam("error", "unknown");
            return fail("注册失败: " + err + "（检查 msgTaskModule 配置: " + msgTaskModule + "）");
        }

        Map<String, TaskRecord> map = loadRecords();
        map.put(fullId, rec);
        String warn = saveRecords(map);
        String next = nextFireText(fullId);
        StringBuilder out = new StringBuilder("已创建定时任务\n")
                .append("- 任务ID: ").append(taskId).append("\n")
                .append("- 类型: ").append(rec.type.equals("agent") ? "agent 轮次" : "固定消息")
                .append("  ").append(rec.type.equals("agent") ? rec.prompt : rec.module + "." + rec.action).append("\n")
                .append("- 调度: ").append(scheduleText(rec)).append("\n")
                .append("- 下次执行: ").append(next == null ? "（等待引擎调度）" : next);
        if (warn != null) out.append("\n- ⚠ ").append(warn);
        return ok(out.toString());
    }

    /** 按记录构造引擎注册消息；记录不可构造（如目标模块缺失）返回 null */
    private TLMsg buildRegistMsg(TaskRecord rec) {
        String fullId = rec.owner + "/" + rec.taskId;
        TLMsg taskMsg;
        if ("agent".equals(rec.type)) {
            taskMsg = createMsg()
                    .setDestination(rec.owner)
                    .setAction("runScheduledTask")
                    .setParam("taskPrompt", rec.prompt)
                    .setParam("taskId", fullId)
                    .setParam("recordSession", rec.creationSessionId);
        } else {
            taskMsg = createMsg().setDestination(rec.module).setAction(rec.action);
            if (rec.argsJson != null && !rec.argsJson.isEmpty()) {
                try {
                    Map<String, Object> extra = gson.fromJson(rec.argsJson, new TypeToken<Map<String, Object>>() {}.getType());
                    if (extra != null) taskMsg.addArgs(extra);
                } catch (Exception e) {
                    putLog("args JSON 解析失败: " + e.getMessage(), LogLevel.WARN);
                }
            }
        }
        taskMsg.setSystemParam(AI_P_SESSIONID, rec.creationSessionId == null ? "default" : rec.creationSessionId);
        taskMsg.setSystemParam("userId", rec.userId == null ? "default" : rec.userId);

        TLMsg reg = createMsg().setAction("registTask").setParam("msg", taskMsg).setParam("status", "run");
        reg.setParam("taskid", fullId);
        Object cron = rec.schedule.get("cron");
        if (cron != null) reg.setParam("cronExp", String.valueOf(cron));
        Object delay = rec.schedule.get("delay");
        // 注意：delay=0 必须显式下发（引擎缺省是 60 秒；one_shot 的"立即"语义依赖 0）
        if (delay != null) reg.setParam("delay", String.valueOf(delay));
        Object unit = rec.schedule.get("unit");
        if (unit != null) reg.setParam("timeUnit", String.valueOf(unit));
        Object times = rec.schedule.get("times");
        if (times != null && !"0".equals(String.valueOf(times))) reg.setParam("times", String.valueOf(times));
        Object begin = rec.schedule.get("begin");
        if (begin != null) reg.setParam("begin", String.valueOf(begin));
        return reg;
    }

    private String scheduleText(TaskRecord rec) {
        Object cron = rec.schedule.get("cron");
        if (cron != null) return "cron: " + cron;
        Object delay = rec.schedule.get("delay");
        Object unit = rec.schedule.get("unit");
        Object times = rec.schedule.get("times");
        StringBuilder s = new StringBuilder("每 ").append(delay).append(" ").append(unit);
        if (times != null && !"0".equals(String.valueOf(times))) s.append("，共 ").append(times).append(" 次");
        return s.toString();
    }

    /** 从引擎摘要取该任务的下次执行时间文本（引擎计算，非 LLM 推算） */
    private String nextFireText(String fullId) {
        try {
            TLMsg r = sendToEngine(createMsg().setAction("getTasks"));
            if (r == null) return null;
            Object tasksObj = r.getParam("tasks");
            if (!(tasksObj instanceof Map)) return null;
            Object info = ((Map<?, ?>) tasksObj).get(fullId);
            if (!(info instanceof Map)) return null;
            return fmtTime(((Map<?, ?>) info).get("nextDatetime"));
        } catch (Exception e) {
            return null;
        }
    }

    private TLMsg sendToEngine(TLMsg m) {
        try {
            Object engine = getModuleInFactory(msgTaskModule);
            if (!(engine instanceof IObject)) {
                putLog("定时任务模块不存在: " + msgTaskModule + "（检查工厂配置）", LogLevel.ERROR);
                return null;
            }
            return putMsg((IObject) engine, m);
        } catch (Exception e) {
            putLog("发送给定时任务引擎失败: " + e, LogLevel.ERROR);
            return null;
        }
    }

    // ======================== list / remove / update ========================
    // （Task 3、Task 4 填充）

    private TLMsg listTasks(String userId) { return fail("not implemented"); }

    private TLMsg removeTask(Map<String, Object> input, String userId) { return fail("not implemented"); }

    private TLMsg updateTask(Map<String, Object> input, String userId) { return fail("not implemented"); }

    // ======================== 持久化 ========================

    private File storageFile() {
        String root = storageRoot;
        if (root == null || root.isEmpty()) root = "./data/";
        if (!new File(root).isAbsolute() && moduleFactory != null && moduleFactory.getConfigDir() != null) {
            String base = moduleFactory.getConfigDir();
            if (base.startsWith("CLASSPATH/")) base = base.substring("CLASSPATH/".length());
            root = base + root;
        }
        File dir = new File(root, "scheduled_tasks");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, ownerAgent + ".json");
    }

    /** 读持久化：Map<fullId, TaskRecord>；文件不存在/损坏返回空表 */
    private synchronized Map<String, TaskRecord> loadRecords() {
        Map<String, TaskRecord> map = new LinkedHashMap<>();
        try {
            File f = storageFile();
            if (!f.exists()) return map;
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            List<TaskRecord> list = gson.fromJson(json, new TypeToken<List<TaskRecord>>() {}.getType());
            if (list != null) for (TaskRecord r : list) {
                if (r != null && r.owner != null && r.taskId != null) map.put(r.owner + "/" + r.taskId, r);
            }
        } catch (Exception e) {
            putLog("读取任务持久化失败: " + e, LogLevel.WARN);
        }
        return map;
    }

    /** 写持久化（原子替换）；成功返回 null，失败返回错误文本（调用方附在回执里） */
    private synchronized String saveRecords(Map<String, TaskRecord> map) {
        try {
            File f = storageFile();
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            Files.write(tmp.toPath(), gson.toJson(new ArrayList<>(map.values())).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFail) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return null;
        } catch (Exception e) {
            putLog("写入任务持久化失败: " + e, LogLevel.WARN);
            return "任务已在内存中运行，但持久化失败（重启后不恢复）: " + e.getMessage();
        }
    }

    private void updateRecordAfterRun(String fullId, String targetSession) {
        Map<String, TaskRecord> map = loadRecords();
        TaskRecord rec = map.get(fullId);
        if (rec == null) return;
        rec.executedCount++;
        rec.lastExecuteTime = System.currentTimeMillis();
        if (rec.schedule.get("times") != null && !"0".equals(String.valueOf(rec.schedule.get("times")))
                && rec.executedCount >= Long.parseLong(String.valueOf(rec.schedule.get("times")))) {
            rec.enabled = false;   // 一次性/有限次任务执行完保留记录、置停用（可 update 重新启用）
        }
        saveRecords(map);
    }

    /** 启动恢复：agent 启动时把本 agent 名下 enabled 任务重新注册回引擎 */
    @Override
    public void runStartMsg() {
        super.runStartMsg();
        if (restored) return;
        restored = true;
        try {
            Map<String, TaskRecord> map = loadRecords();
            int ok = 0;
            for (TaskRecord rec : map.values()) {
                if (rec.owner == null || !rec.owner.equals(ownerAgent)) continue;
                if (!rec.enabled) continue;
                TLMsg reg = buildRegistMsg(rec);
                if (reg == null) continue;
                TLMsg r = sendToEngine(reg);
                if (r != null && Boolean.TRUE.equals(r.getParam(RESULT))) ok++;
                else putLog("任务恢复失败: " + rec.taskId, LogLevel.WARN);
            }
            if (ok > 0) putLog("定时任务恢复完成，共 " + ok + " 个（owner=" + ownerAgent + "）", LogLevel.INFO);
        } catch (Exception e) {
            putLog("启动恢复定时任务异常: " + e, LogLevel.WARN);
        }
    }

    // ======================== 工具方法 ========================

    /** 取输入参数；键名与框架注入的 systemArgs（sessionId/userId）同名 */
    private String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : String.valueOf(v).trim();
    }

    private Long longOf(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null || String.valueOf(v).trim().isEmpty()) return null;
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String fmtTime(Object v) {
        if (v == null) return null;
        try {
            long ms;
            if (v instanceof Date) ms = ((Date) v).getTime();
            else if (v instanceof Number) ms = ((Number) v).longValue();
            else ms = Long.parseLong(String.valueOf(v));
            return df.format(new Date(ms));
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    private TLMsg ok(String text) { return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLOUTPUT, text); }

    private TLMsg fail(String text) { return createMsg().setParam(RESULT, false).setParam(AI_P_SKILLOUTPUT, "Error: " + text); }
}
```

- [ ] **Step 3: 编译 skill-builtin**

Run: `mvn -f D:/tlobjectapp/tlobject/pom.xml clean install -pl aiagent/skill-builtin -am -DskipTests`
Expected: `BUILD SUCCESS`（注意：`TLBaseModule` 里 `getModuleInFactory` 为 protected，同类继承可用）

- [ ] **Step 4: 提交**

```bash
git add aiagent/skill-builtin/pom.xml aiagent/skill-builtin/src/main/java/cn/tianlong/tlobject/aiagent/skill/builtin/TLScheduleTaskSkill.java
git commit -m "新增 schedule_task 技能：create 注册 + 持久化 + 启动恢复 + 执行期会话解析/结果推送（骨架）"
```

---

### Task 3: list / remove 操作

**Files:**
- Modify: `aiagent/skill-builtin/src/main/java/cn/tianlong/tlobject/aiagent/skill/builtin/TLScheduleTaskSkill.java`（替换 `listTasks`/`removeTask` 占位）

- [ ] **Step 1: 实现 listTasks**

把占位 `listTasks` 替换为：

```java
    /** 列出当前用户在本 agent 下的任务：持久化记录 + 引擎实时状态 */
    @SuppressWarnings("unchecked")
    private TLMsg listTasks(String userId) {
        Map<String, TaskRecord> map = loadRecords();
        if (map.isEmpty()) return ok("当前没有定时任务（用户 " + userId + "）");

        // 引擎摘要：fullId → info（状态/下次执行/次数）
        Map<String, Object> engineTasks = new LinkedHashMap<>();
        TLMsg r = sendToEngine(createMsg().setAction("getTasks"));
        if (r != null && r.getParam("tasks") instanceof Map)
            engineTasks = (Map<String, Object>) r.getParam("tasks");

        StringBuilder out = new StringBuilder("定时任务列表（用户 " + userId + "，共 ")
                .append(countOwned(map, userId)).append(" 个）\n");
        int i = 1;
        for (Map.Entry<String, TaskRecord> e : map.entrySet()) {
            TaskRecord rec = e.getValue();
            if (rec.userId == null || !rec.userId.equals(userId)) continue;
            Map<String, Object> info = engineTasks.get(e.getKey()) instanceof Map
                    ? (Map<String, Object>) engineTasks.get(e.getKey()) : new LinkedHashMap<>();
            String status = String.valueOf(info.getOrDefault("status", rec.enabled ? "stopped" : "paused"));
            String next = fmtTime(info.get("nextDatetime"));
            Object cnt = info.get("executedCount");
            out.append(i++).append(". ").append(rec.taskId)
                    .append("  [").append(status).append("]")
                    .append("  ").append(scheduleText(rec))
                    .append("  已执行 ").append(cnt == null ? rec.executedCount : cnt).append(" 次")
                    .append(next == null ? "" : "  下次 " + next)
                    .append("\n   ").append("agent".equals(rec.type) ? "prompt: " + rec.prompt
                            : "message: " + rec.module + "." + rec.action);
            if (!rec.enabled) out.append("  (已停用)");
            out.append("\n");
        }
        out.append("用 op=remove 删除、op=update 暂停/恢复或改调度");
        return ok(out.toString());
    }

    private int countOwned(Map<String, TaskRecord> map, String userId) {
        int n = 0;
        for (TaskRecord r : map.values()) if (userId.equals(r.userId)) n++;
        return n;
    }
```

- [ ] **Step 2: 实现 removeTask**

把占位 `removeTask` 替换为：

```java
    /** 删除任务：校验归属 → 引擎 unRegistTask → 清持久化 */
    private TLMsg removeTask(Map<String, Object> input, String userId) {
        String taskId = str(input, "task_id", null);
        if (taskId == null || taskId.isEmpty()) return fail("remove 需要 task_id");
        String fullId = ownerAgent + "/" + taskId;
        Map<String, TaskRecord> map = loadRecords();
        TaskRecord rec = map.get(fullId);
        if (rec == null) return fail("任务不存在: " + taskId + "（用 op=list 查看）");
        if (rec.userId == null || !rec.userId.equals(userId))
            return fail("任务不属于当前用户，无法删除");

        TLMsg r = sendToEngine(createMsg().setAction("unRegistTask").setParam("taskid", fullId));
        boolean engineOk = r != null && Boolean.TRUE.equals(r.getParam(RESULT));
        map.remove(fullId);
        String warn = saveRecords(map);
        StringBuilder out = new StringBuilder("已删除定时任务: " + taskId);
        if (!engineOk) out.append("\n（提示：引擎中未找到该任务，已清理持久化记录）");
        if (warn != null) out.append("\n- ⚠ ").append(warn);
        return ok(out.toString());
    }
```

- [ ] **Step 3: 编译**

Run: `mvn -f D:/tlobjectapp/tlobject/pom.xml clean install -pl aiagent/skill-builtin -am -DskipTests`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: 提交**

```bash
git add aiagent/skill-builtin/src/main/java/cn/tianlong/tlobject/aiagent/skill/builtin/TLScheduleTaskSkill.java
git commit -m "schedule_task 技能：list（持久化+引擎状态合并）与 remove（归属校验+引擎注销）"
```

---

### Task 4: update 操作（暂停/恢复、改调度）

**Files:**
- Modify: `aiagent/skill-builtin/src/main/java/cn/tianlong/tlobject/aiagent/skill/builtin/TLScheduleTaskSkill.java`（替换 `updateTask` 占位）

- [ ] **Step 1: 实现 updateTask**

把占位 `updateTask` 替换为：

```java
    /**
     * 更新任务：
     * - enabled=false → 引擎 stopTask（保留记录，重启不恢复）
     * - enabled=true  → 引擎 startTask（记录恢复启用）
     * - 改了 cron/delay/unit/times/begin → 用新调度重注册（unRegist + regist），记录同步
     */
    private TLMsg updateTask(Map<String, Object> input, String userId) {
        String taskId = str(input, "task_id", null);
        if (taskId == null || taskId.isEmpty()) return fail("update 需要 task_id");
        String fullId = ownerAgent + "/" + taskId;
        Map<String, TaskRecord> map = loadRecords();
        TaskRecord rec = map.get(fullId);
        if (rec == null) return fail("任务不存在: " + taskId + "（用 op=list 查看）");
        if (rec.userId == null || !rec.userId.equals(userId))
            return fail("任务不属于当前用户，无法修改");

        boolean scheduleChanged = input.containsKey("cron") || input.containsKey("delay")
                || input.containsKey("unit") || input.containsKey("times") || input.containsKey("begin");
        String enabledStr = str(input, "enabled", null);

        if (scheduleChanged) {
            String cron = str(input, "cron", null);
            Long delay = longOf(input, "delay");
            if (cron != null && !CronExpression.isValidExpression(cron))
                return fail("invalid cron expression: " + cron);
            if (cron == null && delay == null && !"0".equals(String.valueOf(rec.schedule.get("cron"))))
                return fail("需要明确调度：给 cron 或 delay");
            if (cron != null) rec.schedule.put("cron", cron);
            if (delay != null) rec.schedule.put("delay", delay);
            if (input.containsKey("unit")) rec.schedule.put("unit", str(input, "unit", "s"));
            if (input.containsKey("times")) rec.schedule.put("times", longOf(input, "times"));
            if (input.containsKey("begin")) rec.schedule.put("begin", longOf(input, "begin"));
            if (cron != null) rec.schedule.put("delay", null);

            // 重注册：先注销旧的，再按新调度注册
            sendToEngine(createMsg().setAction("unRegistTask").setParam("taskid", fullId));
            TLMsg reg = buildRegistMsg(rec);
            if (reg == null) return fail("任务参数构造失败（见日志）");
            TLMsg r = sendToEngine(reg);
            if (r == null || !Boolean.TRUE.equals(r.getParam(RESULT)))
                return fail("重注册失败: " + (r == null ? "引擎无响应" : r.getStringParam("error", "unknown")));
            rec.enabled = true;
            String warn = saveRecords(map);
            String out = "已更新任务 " + taskId + " 的调度：" + scheduleText(rec)
                    + "\n- 下次执行: " + (nextFireText(fullId) == null ? "（等待引擎调度）" : nextFireText(fullId));
            return warn == null ? ok(out) : ok(out + "\n- ⚠ " + warn);
        }

        if (enabledStr != null) {
            boolean enabled = "true".equalsIgnoreCase(enabledStr);
            if (enabled) {
                rec.enabled = true;
                saveRecords(map);
                TLMsg r = sendToEngine(createMsg().setAction("startTask").setParam("taskid", fullId));
                if (r == null || !Boolean.TRUE.equals(r.getParam(RESULT)))
                    return fail("恢复失败: " + (r == null ? "引擎无响应" : "任务不在引擎中，可改调度重建"));
                return ok("已恢复任务 " + taskId + "，下次执行: " + (nextFireText(fullId) == null ? "（等待引擎调度）" : nextFireText(fullId)));
            } else {
                sendToEngine(createMsg().setAction("stopTask").setParam("taskid", fullId));
                rec.enabled = false;
                String warn = saveRecords(map);
                return warn == null ? ok("已暂停任务 " + taskId + "（重启后不恢复；op=update enabled=true 恢复）")
                        : ok("已暂停任务 " + taskId + "\n- ⚠ " + warn);
            }
        }
        return fail("update 需要至少一个改动：enabled 或 cron/delay/unit/times/begin");
    }
```

- [ ] **Step 2: 编译**

Run: `mvn -f D:/tlobjectapp/tlobject/pom.xml clean install -pl aiagent/skill-builtin -am -DskipTests`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: 提交**

```bash
git add aiagent/skill-builtin/src/main/java/cn/tianlong/tlobject/aiagent/skill/builtin/TLScheduleTaskSkill.java
git commit -m "schedule_task 技能：update（暂停/恢复/改调度重注册）"
```

---

### Task 5: 技能级冒烟验证（无 LLM，直接驱动技能）

**Files:**
- Create: `demo/tlobject/src/main/java/cn/tianlong/java/demo/task/TaskSkillSmokeTest.java`（临时测试类，验证后删除）

目的：在真实工厂里建出技能实例（与 aiagent 相同的 familyName 语义），直接发 `skillExecute` 驱动
create/list/remove/update，并断言引擎侧状态——这是无 LLM 下能对技能做的最强验证。

- [ ] **Step 1: 写冒烟测试**

创建 `demo/tlobject/src/main/java/cn/tianlong/java/demo/task/TaskSkillSmokeTest.java`：

```java
package cn.tianlong.java.demo.task;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.*;

/**
 * 定时任务技能冒烟测试（无 LLM）：直接构造/驱动技能实例，断言引擎与持久化。
 * 临时测试，验证后删除。运行：
 *   cd demo/tlobject
 *   java -cp "target/classes;../../core/target/classes;../../aiagent/common/target/classes;../../aiagent/skill-builtin/target/classes;../../crontab/target/classes;<gson-jar>" cn.tianlong.java.demo.task.TaskSkillSmokeTest
 */
public class TaskSkillSmokeTest {

    private static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        System.setProperty("log4j.configurationFile", "target/classes/conf/demo/aiagent/log4j2.xml");
        TLObjectFactory factory = TLObjectFactory.getInstance(
                "target/classes/conf/demo/aiagent", "moduleFactory_chat_config.xml");
        if (factory == null) { System.out.println("工厂启动失败"); System.exit(1); }
        factory.startFactory();
        factory.boot();

        // 1. 取得引擎实例（应用声明的 taskScheduler；不存在即创建）
        Object engine = ((TLBaseModule) factory.getModule("taskScheduler"));
        check("引擎实例可用", engine != null);

        // 2. 取技能（通过 agent 的家族名机制直接实例化：与 aiagent.initModules 同款 getMyModule 语义）
        TLBaseModule skill = (TLBaseModule) factory.getModule("scheduleTaskSkill");
        // 冒烟里没有父子层级，familyName 就是短名 → ownerName()=scheduleTaskSkill；
        // 为了模拟 aiagent_master 的层级，给技能注入 ownerAgent 参数
        // （真实部署里由 agent 配置文件传 ownerAgent="aiagent_master"）
        // 这里用 msg 方式配置不可行（参数在创建时已定），改为断言短名语义 + 引擎侧任务 id 前缀
        check("技能实例可用", skill != null);

        // 3. create：5 秒后一次性任务（message 型，目标模块选一个必然存在的日志模块）
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("op", "create");
        in.put("task_id", "smoke1");
        in.put("module", "log4j");
        in.put("action", "openLog");
        in.put("delay", "5");
        in.put("unit", "s");
        in.put("one_shot", "true");
        in.put("description", "冒烟测试任务");
        TLMsg r = execSkill(skill, in, "smoke_session");
        check("create 成功: " + r.getStringParam("skillOutput", ""), Boolean.TRUE.equals(r.getParam("result")));

        // 4. 引擎侧存在（taskid 规则 = <owner>/<taskId>，owner=家族名首段）
        String fullId = skillFamilyOwner(skill) + "/smoke1";
        Map<String, Object> tasks = engineTasks(engine);
        check("引擎中已注册: " + fullId, tasks.containsKey(fullId));

        // 5. 引擎摘要含 nextDatetime（Task 1 补丁）
        Object info = tasks.get(fullId);
        check("摘要含 nextDatetime", info instanceof Map && ((Map<?, ?>) info).get("nextDatetime") != null);
        check("摘要含 executedCount", info instanceof Map && ((Map<?, ?>) info).get("executedCount") != null);

        // 6. list
        in = new LinkedHashMap<>();
        in.put("op", "list");
        r = execSkill(skill, in, "smoke_session");
        check("list 含 smoke1: " + r.getStringParam("skillOutput", ""),
                r.getStringParam("skillOutput", "").contains("smoke1"));

        // 7. 持久化文件存在
        java.io.File f = new java.io.File("target/classes/conf/./data/scheduled_tasks/"
                + skillFamilyOwner(skill) + ".json");
        check("持久化文件存在", f.exists());

        // 8. update 暂停
        in = new LinkedHashMap<>();
        in.put("op", "update");
        in.put("task_id", "smoke1");
        in.put("enabled", "false");
        r = execSkill(skill, in, "smoke_session");
        check("update 暂停", r.getStringParam("skillOutput", "").contains("已暂停"));

        // 9. remove
        in = new LinkedHashMap<>();
        in.put("op", "remove");
        in.put("task_id", "smoke1");
        r = execSkill(skill, in, "smoke_session");
        check("remove 成功", r.getStringParam("skillOutput", "").contains("已删除"));
        check("引擎中已注销", !engineTasks(engine).containsKey(fullId));

        // 10. 非法 cron 被拒
        in = new LinkedHashMap<>();
        in.put("op", "create");
        in.put("task_id", "bad");
        in.put("prompt", "x");
        in.put("cron", "not a cron");
        r = execSkill(skill, in, "smoke_session");
        check("非法 cron 被拒", !Boolean.TRUE.equals(r.getParam("result"))
                && r.getStringParam("skillOutput", "").contains("invalid cron"));

        System.out.println("\n===== 冒烟结果: pass=" + pass + " fail=" + fail + " =====");
        factory.shutdown(0);
        System.exit(fail == 0 ? 0 : 1);
    }

    /** 与技能 ownerName() 同语义：家族名冒号前一段 */
    private static String skillFamilyOwner(TLBaseModule skill) {
        String fn = skill.getFamilyName();
        int i = fn.lastIndexOf(':');
        return i >= 0 ? fn.substring(0, i) : fn;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> engineTasks(Object engine) {
        TLMsg r = ((TLBaseModule) engine).putMsg(((TLBaseModule) engine),
                ((TLBaseModule) engine).createMsg().setAction("getTasks"));
        Object t = r == null ? null : r.getParam("tasks");
        return t instanceof Map ? (Map<String, Object>) t : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static TLMsg execSkill(TLBaseModule skill, Map<String, Object> input, String sessionId) {
        TLMsg m = skill.createMsg().setAction("skillExecute");
        m.setParam("skillInput", input);
        for (Map.Entry<String, Object> e : input.entrySet()) m.setParam(e.getKey(), e.getValue());
        m.setSystemParam("sessionId", sessionId);
        m.setSystemParam("userId", "smoke_user");
        return skill.putMsg(skill, m);
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (ok) pass++; else fail++;
    }
}
```

> 注意：`factory.getModule(...)` 返回的是 `TLObjectFactory.getModule`（工厂侧），冒烟里技能不在父子层级中，
> 所以 owner 解析出来就是短名 `scheduleTaskSkill`——这正是要验证的"前缀规则"，用 `skillFamilyOwner()`
> 动态取，勿写死。真实部署中 owner 由 `ownerAgent` 参数或家族名给出（Task 6 配 `aiagent_master`）。

- [ ] **Step 2: 编译并运行**

```bash
cd D:/tlobjectapp/tlobject
mvn -f pom.xml clean install -pl demo/tlobject -am -DskipTests
cd demo/tlobject
java -cp "target/classes;../../core/target/classes;../../aiagent/common/target/classes;../../aiagent/skill-builtin/target/classes;../../crontab/target/classes;C:/Users/Administrator/.m2/repository/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar;../../cache/target/classes;../../log/target/classes;../../utils/target/classes;../../servletutils/target/classes" cn.tianlong.java.demo.task.TaskSkillSmokeTest
```
Expected: 全部 `[PASS]`，末尾 `pass=N fail=0`（若 classpath 缺条目，按运行时 `NoClassDefFoundError` 提示补 target/classes 路径）

- [ ] **Step 3: 修复问题直到全 PASS**（失败时按日志修正技能实现；不要改断言语义）

- [ ] **Step 4: 提交**（测试类保留到 Task 8 端到端验证后再删）

```bash
git add demo/tlobject/src/main/java/cn/tianlong/java/demo/task/TaskSkillSmokeTest.java
git commit -m "定时任务技能冒烟测试（无 LLM）：create/list/update/remove + 引擎断言"
```

---

### Task 6: webui 当前活动会话上报 + 配置接线

**Files:**
- Modify: `aiagent/webui/src/main/java/cn/tianlong/tlobject/aiagent/webui/TLWebChatModule.java`（加 Map + 两个 action）
- Modify: `aiagent/webui/src/main/resources/webui/app.js`（上报当前会话）
- Modify: `demo/tlobject/src/main/resources/conf/demo/aiagent/moduleFactory_chat_config.xml`（taskScheduler 声明）
- Create: `demo/tlobject/src/main/resources/conf/demo/aiagent/taskScheduler_config.xml`
- Modify: `demo/tlobject/src/main/resources/conf/tlobject/cn.tianlong.tlobject.aiagent_config.xml`（注册技能类）
- Modify: `demo/tlobject/src/main/resources/conf/demo/aiagent/aiagent_master_config.xml`（声明技能）

- [ ] **Step 1: webui 加 currentSessionByUser（Map + action）**

在 `TLWebChatModule` 字段区（`sessionOwner` 声明附近）加：

```java
    /** userId → 前端当前打开的会话（setCurrentSession 上报；无 web 模块时自然为空） */
    private final Map<String, String> currentSessionByUser = new java.util.concurrent.ConcurrentHashMap<>();
```

在 `checkMsgAction` 的 switch（:120-133）里加两个 case：

```java
            case "setCurrentSession":
                return onSetCurrentSession(msg);
            case "getCurrentSession":
                return onGetCurrentSession(msg);
```

在类中新增方法（放在 `onApprovalEvent` 附近）：

```java
    /** 前端上报当前打开的会话（页面加载/切会话/开新会话时调用） */
    private TLMsg onSetCurrentSession(TLMsg msg) {
        String userId = msg.getStringParam("userId", "");
        String sid = msg.getStringParam("sessionId", "");
        if (!userId.isEmpty() && !sid.isEmpty()) currentSessionByUser.put(userId, sid);
        return createMsg().setParam(RESULT, true);
    }

    /** 查询用户当前活动会话（定时任务执行期解析目标会话用）；未知返回空串 */
    private TLMsg onGetCurrentSession(TLMsg msg) {
        String userId = msg.getStringParam("userId", "");
        String sid = currentSessionByUser.getOrDefault(userId, "");
        return createMsg().setParam(RESULT, true).setParam("sessionId", sid);
    }
```

- [ ] **Step 2: app.js 上报当前会话**

在 `app.js` 里新增函数（放在 `openEvents()` 前面；`apiCommand` 已存在于 :1293）：

```javascript
// 上报当前打开的会话（定时任务执行期需要知道"用户现在在看哪个会话"）
function reportCurrentSession() {
  if (!state.sessionId) return;
  apiCommand('setCurrentSession', { sessionId: state.sessionId }).catch(() => {});
}
```

在以下三处调用 `reportCurrentSession()`：
1. `enterChat()` 末尾（页面加载/登录后进入聊天时；会话恢复是异步的，用
   `setTimeout(reportCurrentSession, 500)` 保证在 `autoResumeLast()` 完成后上报）
2. `newSession()` 末尾（`state.sessionId` 赋值之后）
3. `switchSession(sid)` 成功分支里，`state.sessionId` 被更新之后

- [ ] **Step 3: 配置接线**

（a）`moduleFactory_chat_config.xml` 的 `<modules>` 中加：

```xml
        <!-- 定时任务引擎（schedule_task 技能对接；poolSize 决定并发执行的任务数） -->
        <module name="taskScheduler" sameClassAs="msgTask" configfile="taskScheduler_config.xml" singleton="true"/>
```

（b）新建 `conf/demo/aiagent/taskScheduler_config.xml`：

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<moduleConfig>
    <params>
        <poolSize value="4"/>
    </params>
    <!-- 无预定义任务：任务由 schedule_task 技能经 registTask 动态注册 -->
</moduleConfig>
```

（c）`conf/tlobject/cn.tianlong.tlobject.aiagent_config.xml` 的 `<modules>` 中加（与 `scriptExecutionSkill` 同款）：

```xml
        <module name="scheduleTaskSkill" classfile="cn.tianlong.tlobject.aiagent.skill.builtin.TLScheduleTaskSkill"
                singleton="true"/>
```

（d）`aiagent_master_config.xml` 的 `<skills>` 中加：

```xml
        <skill name="schedule_task"
               sameClassAs="scheduleTaskSkill"
               statup="true"
               ownerAgent="aiagent_master"
               msgTaskModule="taskScheduler"
               skillDescription="Create and manage scheduled tasks (定时任务). Use when the user asks to do something at a future time or repeatedly: '5秒后提醒我喝水', '每天早上8点查一下上证指数', 'every hour check X'. op=create with a natural-language prompt runs a full agent round at each fire and reports back into the chat; module+action sends a fixed message without the LLM. op=list/remove/update manage existing tasks."/>
```

> 说明：`ownerAgent` 显式配置为 `aiagent_master`——任务消息 destination 必须是 agent 名
> （技能家族名 `aiagent_master:schedule_task` 的 `putMsg` 不做注册表解析），显式配置最稳。

- [ ] **Step 4: 编译**

Run: `mvn -f D:/tlobjectapp/tlobject/pom.xml clean install -pl aiagent/webui -am -DskipTests`
Expected: `BUILD SUCCESS`

- [ ] **Step 5: 提交**

```bash
git add aiagent/webui/src/main/java/cn/tianlong/tlobject/aiagent/webui/TLWebChatModule.java aiagent/webui/src/main/resources/webui/app.js demo/tlobject/src/main/resources/conf/demo/aiagent/moduleFactory_chat_config.xml demo/tlobject/src/main/resources/conf/demo/aiagent/taskScheduler_config.xml demo/tlobject/src/main/resources/conf/tlobject/cn.tianlong.tlobject.aiagent_config.xml demo/tlobject/src/main/resources/conf/demo/aiagent/aiagent_master_config.xml
git commit -m "webui 当前活动会话上报（setCurrentSession/getCurrentSession）+ 定时任务引擎与技能配置接线"
```

---

### Task 7: taskResult 推送（web SSE + 前端渲染 + 控制台）

**Files:**
- Modify: `aiagent/webui/src/main/java/cn/tianlong/tlobject/aiagent/webui/TLWebChatModule.java`（订阅 + onTaskResultEvent）
- Modify: `aiagent/webui/src/main/resources/webui/app.js`（taskResult 分支）
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLChatConsole.java`（订阅 + 打印）

- [ ] **Step 1: webui 订阅 taskResult 并推送**

（a）在 bus 事件分发处（`onBus`/`checkMsgAction` 收到 bus 消息的 case 区，与 `approvalEvent` 并列）加：

```java
            case "taskResult":
                return onTaskResultEvent(msg) ? createMsg().setParam(RESULT, true) : null;
```

（b）新增方法（与 `onApprovalEvent` 同款结构）：

```java
    /**
     * 定时任务结果事件（msgBus 回调）：按 userId 推给其 SSE 连接。
     * 前端收到后：结果会话 == 当前打开会话 → 追加进消息列表；否则提示可切换。
     */
    private boolean onTaskResultEvent(TLMsg msg) {
        String userId = msg.getStringParam("userId", "");
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("type", "taskResult");
        evt.put("taskId", msg.getStringParam("taskId", ""));
        evt.put("sessionId", msg.getStringParam("sessionId", ""));
        evt.put("creationSessionId", msg.getStringParam("creationSessionId", ""));
        evt.put("text", msg.getStringParam("text", ""));
        String json = GSON.toJson(evt);
        boolean sent = false;
        java.util.concurrent.CopyOnWriteArrayList<TLWebChannel> list = eventChannels.get(userId);
        if (list != null) for (TLWebChannel c : list) {
            if (c.isOpen()) { c.write(json); sent = true; }
        }
        return sent;
    }
```

（c）在订阅审批事件的地方（`subscribeApprovalEvents`）一并订阅 taskResult（可复制该方法改为两行订阅）：

```java
            TLMsg regMsg = putMsg("msgBus", createMsg().setAction("registBus")
                    .setParam("destination", "taskResult").setParam("object", this));
```

- [ ] **Step 2: app.js taskResult 分支**

在 `openEvents()` 的 `es.onmessage` 里，`kicked` 分支后加：

```javascript
    if (evt.type === 'taskResult') {
      const sameSession = evt.sessionId === state.sessionId;
      const head = '[定时任务 ' + (evt.taskId || '') + '] ';
      if (sameSession && !state.busy) {
        // 结果就在当前会话：提示 + 重新拉历史（任务轮次一并渲染出来）
        appendSysMsg('⏰ ' + head + evt.text);
        continueSession(state.sessionId, true);
      } else if (sameSession) {
        appendSysMsg('⏰ ' + head + '有新结果（本轮对话结束后刷新可见）');
      } else {
        toast('⏰ ' + head + '有新结果，在会话 ' + (evt.sessionId || '') + '（右侧会话列表可切换）', 'ok');
        appendSysMsg('⏰ ' + head + '结果已写入会话 ' + (evt.sessionId || ''));
      }
    }
```

> `continueSession(sid, silent)` 是 app.js 已有的会话恢复函数（:678，负责拉历史并渲染）；
> `state.busy` 为真时不重拉（避免打断进行中的对话渲染）。

- [ ] **Step 3: 控制台订阅打印**

（a）`TLChatConsole` 的 `checkMsgAction` switch（:137-163）加：

```java
            case "taskResult":
                // 结果渲染走既有 RESULT 事件路径（主循环 onChatResult 读 AI_P_RESPONSE，:1718）
                offer(new ConsoleEvent(EventType.RESULT, null, createMsg()
                        .setParam(AI_P_RESPONSE, "⏰ [定时任务 " + msg.getStringParam("taskId", "")
                                + "] " + msg.getStringParam("text", ""))
                        .setParam("success", true)));
                return running ? createMsg().setParam(RESULT, true) : null;
```

> 已核实：主循环 `case RESULT: onChatResult(e.msg)`（:395/:436），`onChatResult` 读
> `AI_P_RESPONSE` 且需 `success=true` 才打印（:1718-1726）。事件入队由主循环渲染，
> 但任务可能在主循环阻塞等待输入时到达——`offer` 是向事件队列投放，
> 输入等待循环（`:333` 附近的 `offer(new ConsoleEvent(EventType.INPUT, ...))`）消费队列时一并渲染，
> 表现为"下一条提示前打印"。可接受。

（b）订阅（与 `:236` 的审批订阅并列，同一处方法里再加一段）：

```java
            putMsg("msgBus", createMsg().setAction("registBus")
                    .setParam("destination", "taskResult").setParam("object", this));
```

（c）`destroy` 里加注销（与 `:115` 的 unRegistBus 并列）：

```java
            putMsg("msgBus", createMsg().setAction("unRegistBus")
                    .setParam("destination", "taskResult").setParam("object", this));
```

- [ ] **Step 4: 编译**

Run: `mvn -f D:/tlobjectapp/tlobject/pom.xml clean install -pl aiagent/webui,aiagent/common -am -DskipTests`
Expected: `BUILD SUCCESS`

- [ ] **Step 5: 提交**

```bash
git add aiagent/webui/src/main/java/cn/tianlong/tlobject/aiagent/webui/TLWebChatModule.java aiagent/webui/src/main/resources/webui/app.js aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLChatConsole.java
git commit -m "taskResult 推送：web SSE 按 userId 推送 + 前端聊天窗口渲染 + 控制台打印订阅"
```

---

### Task 8: 端到端验证（真实应用）

**Files:** 无新增（验证 + 清理）

- [ ] **Step 1: 启动聊天应用**

Run:
```bash
cd D:/tlobjectapp/tlobject
mvn -f pom.xml install -DskipTests
cd demo/tlobject
java -cp "target/classes;../../core/target/classes;../../aiagent/common/target/classes;../../aiagent/skill-builtin/target/classes;../../crontab/target/classes;../../aiagent/webui/target/classes;../../servletutils/target/classes;../../network/network-server-jettyEE8/target/classes;../../log/target/classes;../../utils/target/classes;C:/Users/Administrator/.m2/repository/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar" cn.tianlong.java.demo.aiagent.ChatDemo
```
（实际 classpath 以 `demo/tlobject` 下启动脚本/aistart.bat 的组装为准；本仓库约定 `java -cp` 用 Windows 分号分隔）
Expected: 控制台出现 `>` 提示符

- [ ] **Step 2: 创建任务（真实 LLM）**

在控制台输入：
```
5秒后提醒我喝水
```
Expected: LLM 调用 `schedule_task`（op=create，prompt="提醒我喝水"，one_shot=true/delay=5），
回复含 `已创建定时任务` + 任务ID + 下次执行时间。

- [ ] **Step 3: 等 5 秒，验证结果落库与打印**

Expected:
- 控制台打印 `⏰ [定时任务 ...] ...`（Task 7 的订阅）
- 输入 `/sessions` 能看到该会话；`/continue <id>` 能看到追加的任务轮次

- [ ] **Step 4: web 端验证（可选但推荐）**

启动 web 版（moduleFactory_chat_web_config.xml 的启动脚本），浏览器打开 → 登录 →
聊天里创建"10秒后说一句你好"→ 保持页面打开 → 10 秒后聊天窗口出现 `⏰ ...` 提示与结果轮次。

- [ ] **Step 5: 重启恢复验证**

创建"每天 8 点..."类 cron 任务 → 重启进程 → 查看启动日志 `定时任务恢复完成` →
在控制台问"列出我的定时任务"/`/sessions` 侧确认任务在引擎中（技能 list 可见）。

- [ ] **Step 6: 删除临时冒烟测试并提交**

```bash
rm demo/tlobject/src/main/java/cn/tianlong/java/demo/task/TaskSkillSmokeTest.java
git add -A
git commit -m "端到端验证通过：定时任务技能（聊天创建→执行→结果回会话/推送→重启恢复）；删除临时冒烟测试"
```

---

## 自检记录（写计划时已核对）

- **spec 覆盖**：技能 4 个 op（Task 2/3/4）、持久化与恢复（Task 2）、用户隔离（Task 2/3/4 的 userId 校验）、
  执行期会话解析（Task 2 `resolveTargetSession`）、推送（Task 7）、webui 上报（Task 6）、
  引擎补丁（Task 1）、配置 7 处（Task 6）、验证（Task 5/8）——全部有对应任务。
- **占位符**：无 TBD/TODO；Task 3/4 的"占位方法"是刻意分步（Task 2 先留桩，Task 3/4 替换为完整代码）。
- **类型一致性**：`TaskRecord` 字段、`buildRegistMsg`、`sendToEngine`、`ownerName`、`loadRecords/saveRecords`
  在 Task 3/4 的代码里直接复用 Task 2 的定义；`fullId` 规则（`ownerAgent + "/" + taskId`）三处一致。
- **已知实施风险**（执行时若撞上，按此处理）：
  1. 技能 `setModuleParams` 读到 `ownerAgent` 前 `super.setModuleParams()` 会触发 `loadSkillMd`——本技能无 md 文件，无影响。
  2. 冒烟测试的 classpath 可能缺 log4j/servlet 依赖——按 `NoClassDefFoundError` 逐个补 `*/target/classes`。
  3. `TLChatConsole` 的 RESULT 事件渲染参数名以实际代码为准（Task 7 Step 3 注释已说明）。
  4. 控制台 app（chat_config）没有 webui 模块 → `resolveTargetSession` 返回 null → 回退创建会话（符合设计）。
