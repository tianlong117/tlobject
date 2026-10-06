package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.aiagent.TLConversationHistory;
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
 *   到点由 agent 薄转发给 toolManager，再按函数名定位本技能落到 runScheduledTask()，
 *   再转异步执行引擎（chatStream + onTaskStream；2026-10-06 起与后台任务统一）。
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
    /** 启动恢复守卫（每进程一次） */
    private volatile boolean restored = false;

    /** 运行中任务表：fullId → 运行态（进度/输出/停止标志） */
    private final Map<String, RunState> runningTasks = new java.util.concurrent.ConcurrentHashMap<>();
    /** 执行会话 → 运行态二级索引（回调按 sessionId 直查，免线性扫描） */
    private final Map<String, RunState> runningBySession = new java.util.concurrent.ConcurrentHashMap<>();
    /** 后台任务并发上限（per-user；仅约束 kind=background 的用户主动创建） */
    private int maxConcurrentTasks = 3;
    /** 进度推送节流间隔（毫秒；工具事件不节流） */
    private long progressPushIntervalMs = 250;
    /** 单次执行超时（分钟）：运行态超过此时限未收尾则被看门狗强制收尾并按失败投递；0=关闭 */
    private int taskStaleMinutes = 30;
    /**
     * 后台任务承接父会话上下文：创建时取父会话末尾若干轮对话（user/assistant 文本）拼在任务指令前——
     * 让"把刚才聊到的那个…分析一下"这类指代可解。仅 kind=background（定时任务到点执行时上下文早已变化，不带）；
     * false 关闭；预算见 bgContextChars（从尾部向前累计）。
     */
    private boolean bgContextSnapshot = true;
    /** 上下文快照字符预算（尾部累计，超出即停） */
    private int bgContextChars = 4000;
    /**
     * 周期看门狗（daemon 单线程，懒启动）：executeAsync 的 stale 判定只在"同一任务被再次触发"时
     * 执行，background 一次性任务永不重触发 → 卡住（provider 回调丢失等）时永久 running，
     * 占并发额度、卡片永远"运行中"、结果不投递。本线程周期扫描强制收尾（规格 §5.4 整体超时承诺）。
     * 不加 destroy 钩子：daemon 线程随 JVM 退出，模块生命周期内单例常驻。
     */
    private java.util.concurrent.ScheduledExecutorService watchdog;

    private static class RunState {
        String fullId;
        String execSession;
        volatile long startedAt;             // 本运行态占位时刻（看门狗判超时用）
        final StringBuilder output = new StringBuilder();
        long lastPushAt;
        volatile boolean terminal;
        volatile boolean stopRequested;
        volatile String snapshot;            // 有界输出快照（最后 4KB），供中途打开详情拉全量（不落盘）
    }

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

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

        // ===== 多任务/后台任务扩展（2026-10-06；旧记录缺省字段为 null，按 schedule 兼容） =====
        public String kind;                 // schedule（定时）| background（后台立即）；null=schedule
        public String parentSessionId;      // 发起会话（background 必填）
        public String parentRootSessionId;  // 发起会话根（可空）
        public String parentAgent;          // 注入目标 agent（默认=技能所属 agent）
        public String name;                 // 任务名（缺省取 prompt 前 20 字）
        public String status;               // pending|running|done|failed|stopped|interrupted
        public String execSession;          // 本次执行会话
        public long startedAt;
        public long finishedAt;
        public String progressSnapshot;     // 有界输出快照（最后 4KB）
        public String resultText;           // 终态结果
        public String errorText;            // 终态错误
    }

    // ======================== 配置与信息 ========================

    @Override
    protected void setModuleParams() {
        // 先读自身参数：super 会触发 loadSkillMd（可能需要 ownerAgent 已就绪）
        if (params != null) {
            if (params.get("msgTaskModule") != null) msgTaskModule = params.get("msgTaskModule");
            if (params.get("storageRoot") != null) storageRoot = params.get("storageRoot");
            if (params.get("ownerAgent") != null) ownerAgent = params.get("ownerAgent");
            if (params.get("maxConcurrentTasks") != null)
                try { maxConcurrentTasks = Integer.parseInt(params.get("maxConcurrentTasks")); } catch (Exception ignored) {}
            if (params.get("progressPushIntervalMs") != null)
                try { progressPushIntervalMs = Long.parseLong(params.get("progressPushIntervalMs")); } catch (Exception ignored) {}
            if (params.get("bgContextSnapshot") != null)
                bgContextSnapshot = "true".equals(params.get("bgContextSnapshot"));
            if (params.get("bgContextChars") != null)
                try { bgContextChars = Integer.parseInt(params.get("bgContextChars")); } catch (Exception ignored) {}
            if (params.get("taskStaleMinutes") != null)
                try { taskStaleMinutes = Integer.parseInt(params.get("taskStaleMinutes")); } catch (Exception ignored) {}
        }
        super.setModuleParams();
        if (ownerAgent == null || ownerAgent.isEmpty()) ownerAgent = ownerName();

        if (skillName == null || skillName.isEmpty()) skillName = "schedule_task";
        if (skillDescription == null || skillDescription.isEmpty())
            skillDescription = "Create and manage tasks (定时任务/后台任务). Two kinds:"
                    + " (1) kind=background — run a LONG task immediately without blocking the conversation"
                    + " (e.g. 股票分析/长报告/批量处理), results come back into this session when done;"
                    + " (2) default schedule — fire at a future time or repeatedly (cron/delay/one_shot),"
                    + " results are reported back at each fire. op=create/list/remove/update/stop."
                    + " Use kind=background when a request needs long-running analysis or a long report and should not block the chat.";

        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();
            parameterSchema.put("op", prop("string",
                    "Operation: create | list | remove | update | stop", true));
            parameterSchema.put("kind", prop("string",
                    "Task kind: schedule (default) = fires later / repeats; background = run a long task NOW in the background"
                    + " while the chat stays free (result returns to this session automatically)."));
            parameterSchema.put("name", prop("string", "Short task name (optional; default = first 20 chars of prompt)"));
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

    /**
     * 动作分发：技能标准动作（execute/validate/getInfo）之外，额外接收引擎回调动作
     * runScheduledTask（经 agent→toolManager 薄转发到达；框架不做方法名反射，必须显式分发）。
     */
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        if ("runScheduledTask".equals(msg.getAction()))
            return runScheduledTask(fromWho, msg);
        if ("runScheduledMessage".equals(msg.getAction()))
            return runScheduledMessage(fromWho, msg);
        if ("tasksCmd".equals(msg.getAction()))
            return tasksCmd(fromWho, msg);
        if ("onTaskStream".equals(msg.getAction()))
            return onTaskStream(fromWho, msg);
        return super.checkMsgAction(fromWho, msg);
    }

    /**
     * 控制台 /tasks 命令入口（经 agent→toolManager→本技能 转发到达）。
     * 与 execute() 的区别：不绑定 LLM 轮次，用消息上的 sessionId/userId 身份；
     * 返回结构化数据（data=List&lt;Map&gt;）供命令层渲染。
     * reply 约定：{result, op, message, error?, data?}
     */
    private TLMsg tasksCmd(Object fromWho, TLMsg msg) {
        // 身份：命令路径 userId 走业务参数（控制台/服务层注入），回退 systemArgs 区
        String cmdUserId = str(msg.getArgs(), "userId", null);
        if (cmdUserId == null) {
            Object u = msg.getSystemParam("userId", null);
            cmdUserId = (u != null && !String.valueOf(u).isEmpty()) ? String.valueOf(u)
                    : String.valueOf(msg.getSystemParam(AI_P_SESSIONID, "default"));
        }
        String op = msg.getStringParam("op", "list");
        if (!"list".equals(op) && !"stop".equals(op) && !"resume".equals(op)
                && !"delete".equals(op) && !"createBackground".equals(op) && !"get".equals(op))
            return cmdReply(false, op, null,
                    "未知操作: " + op + "（可用: list | stop | resume | delete | createBackground | get）", null);
        if ("list".equals(op)) return tasksCmdList(cmdUserId);

        if ("createBackground".equals(op)) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("op", "create");
            input.put("kind", "background");
            input.put("prompt", msg.getStringParam("prompt", ""));
            input.put("name", msg.getStringParam("name", ""));
            // 命令路径 sessionId 可能只在 systemArgs 区（args 里没有）：先读 args，空/缺再回退
            String sid0 = msg.getStringParam(AI_P_SESSIONID, null);
            if (sid0 == null || sid0.isEmpty())
                sid0 = String.valueOf(msg.getSystemParam(AI_P_SESSIONID, "default"));
            input.put("sessionId", sid0);
            input.put("userId", cmdUserId);
            TLMsg r = execute(fromWho, msg.setParam(AI_P_SKILLINPUT, input));
            boolean okr = Boolean.TRUE.equals(r.getParam(RESULT));
            return okr ? cmdReply(true, op, r.getStringParam(AI_P_SKILLOUTPUT, ""), null, null)
                    : cmdReply(false, op, null, r.getStringParam(AI_P_SKILLOUTPUT, ""), null);
        }
        if ("get".equals(op)) {
            String tid = msg.getStringParam("task_id", null);
            if (tid == null || tid.isEmpty())
                return cmdReply(false, op, null, "get 需要 task_id", null);
            String fullId = ownerAgent + "/" + tid;
            TaskRecord rec = recordsOf(cmdUserId).get(fullId);
            if (rec == null) return cmdReply(false, op, null, "任务不存在: " + tid, null);
            Map<String, Object> d = recordToMap(rec);
            // status 与 list 同口径（用户可见文案；原始值在 localStatus）
            if ("background".equals(recKind(rec))) {
                d.put("status", bgStatusText(rec));
            } else {
                d.put("status", engineStatusText(engineInfo(fullId).get("status"), rec.enabled));
            }
            RunState rs = runningTasks.get(fullId);
            d.put("running", rs != null);
            d.put("liveSnapshot", rs != null && rs.snapshot != null ? rs.snapshot : "");
            return cmdReply(true, op, "任务 " + tid, null, d);
        }

        String taskId = msg.getStringParam("task_id", null);
        if (taskId == null || taskId.isEmpty())
            return cmdReply(false, op, null, "需要 task_id（先 /tasks 查看列表）", null);
        String replyOp = "delete".equals(op) ? "remove" : "update";   // 复用 execute 的 op 语义
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("op", replyOp);
        input.put("task_id", taskId);
        input.put("userId", cmdUserId);      // execute 优先读输入的 userId（否则回退 systemArgs/sessionId）
        if ("stop".equals(op)) {
            String fullId0 = ownerAgent + "/" + taskId;
            TaskRecord rec0 = recordsOf(cmdUserId).get(fullId0);
            if (rec0 != null && "background".equals(recKind(rec0)) && runningTasks.containsKey(fullId0)) {
                stopTaskRun(rec0);
                return cmdReply(true, op, "已请求停止任务 " + taskId, null, null);
            }
            input.put("enabled", "false");
        }
        if ("resume".equals(op)) input.put("enabled", "true");
        TLMsg r = execute(fromWho, msg.setParam(AI_P_SKILLINPUT, input));

        boolean okr = Boolean.TRUE.equals(r.getParam(RESULT));
        String out = r.getStringParam(AI_P_SKILLOUTPUT, "");
        return okr ? cmdReply(true, op, out, null, null) : cmdReply(false, op, null, out, null);
    }

    /** /tasks 列表：返回结构化数据（引擎缺失/技能缺失由命令层据此提示） */
    private TLMsg tasksCmdList(String userId) {

        Map<String, TaskRecord> mine = recordsOf(userId);
        TLMsg er = sendToEngine(createMsg().setAction("getTasks"));
        if (er == null)
            return cmdReply(true, "list",
                    "定时任务未配置（应用未声明 " + msgTaskModule + " 引擎）", null, null);
        Map<String, Object> engineTasks = new LinkedHashMap<>();
        if (er.getParam("tasks") instanceof Map) engineTasks = (Map<String, Object>) er.getParam("tasks");

        List<Map<String, Object>> data = new ArrayList<>();
        for (Map.Entry<String, TaskRecord> e : mine.entrySet()) {
            TaskRecord rec = e.getValue();
            Object infoObj = engineTasks.get(e.getKey());
            Map<String, Object> info = infoObj instanceof Map ? (Map<String, Object>) infoObj : new LinkedHashMap<>();
            boolean engineKnown = info.get("status") != null;
            boolean background = "background".equals(recKind(rec));
            String status = background
                    ? bgStatusText(rec) : engineStatusText(info.get("status"), rec.enabled);
            Object cnt = info.get("executedCount");
            String cntText = longText(cnt);
            long cntLong = Math.max(rec.executedCount, cntText == null ? 0L : Long.parseLong(cntText));

            // 基础字段走 recordToMap（与 /tasks get、任务卡片恢复同源），再叠加引擎实时字段
            Map<String, Object> d = recordToMap(rec);
            d.put("status", status);                 // 用户可见状态文案（与 get 同口径；原始值在 localStatus）
            d.put("module", rec.module == null ? "" : rec.module);
            d.put("action", rec.action == null ? "" : rec.action);
            d.put("executedCount", cntLong);
            // background 运行标记（前端「后台任务」面板据此出停止按钮/绿点；与 get 分支同款）
            if (background) d.put("running", runningTasks.containsKey(e.getKey()));
            // background 无调度：即使引擎里残留同名任务也不显示 nextDatetime
            if (!background && engineKnown && "运行中".equals(status))
                d.put("nextDatetime", fmtTime(info.get("nextDatetime")));
            data.add(d);
        }
        return cmdReply(true, "list",
                mine.isEmpty() ? "当前没有定时任务（用户 " + userId + "）"
                        : "定时任务（用户 " + userId + "，共 " + mine.size() + " 个）",
                null, data);
    }

    /** 记录 → UI 友好 Map（/tasks list/get、卡片恢复共用） */
    private Map<String, Object> recordToMap(TaskRecord rec) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("taskId", rec.taskId);
        d.put("name", rec.name == null ? rec.taskId : rec.name);
        d.put("kind", recKind(rec));
        d.put("type", rec.type);
        // 原始本地状态（pending/running/done/…）；面向用户的状态文案由 list/get 统一用
        // bgStatusText / engineStatusText 计算后放 "status"（同一 key 不允许两种含义）
        d.put("localStatus", rec.status == null ? "" : rec.status);
        d.put("schedule", scheduleText(rec));
        d.put("content", "agent".equals(rec.type) ? rec.prompt : rec.module + "." + rec.action);
        d.put("prompt", rec.prompt == null ? "" : rec.prompt);
        d.put("parentSessionId", rec.parentSessionId == null ? "" : rec.parentSessionId);
        d.put("execSession", rec.execSession == null ? "" : rec.execSession);
        // 启动/结束时刻（epoch 毫秒；未跑过为 0）——前端后台任务面板算运行时长
        d.put("startedAt", rec.startedAt);
        d.put("finishedAt", rec.finishedAt);
        d.put("executedCount", rec.executedCount);
        d.put("enabled", rec.enabled);
        d.put("progressSnapshot", rec.progressSnapshot == null ? "" : rec.progressSnapshot);
        d.put("resultText", rec.resultText == null ? "" : rec.resultText);
        d.put("errorText", rec.errorText == null ? "" : rec.errorText);
        return d;
    }

    /** 命令回执：成功走 message（可带 data），失败走 error（服务层按 error 判定失败） */
    private TLMsg cmdReply(boolean success, String op, String message, String error, Object data) {
        TLMsg r = createMsg().setParam(RESULT, success).setParam("op", op);
        if (success) {
            if (message != null) r.setParam("message", message);
        } else if (error != null) {
            r.setParam("error", error);
        }
        if (data != null) r.setParam("data", data);
        return r;
    }

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

        switch (op) {
            case "create": return createTask(input, sessionId, userId,
                    String.valueOf(msg.getSystemParam("rootSessionId", sessionId)));
            case "list":   return listTasks(userId);
            case "remove": return removeTask(input, userId);
            case "update": return updateTask(input, userId);
            default:       return fail("unknown op: " + op + " (use create/list/remove/update)");
        }
    }

    // ======================== 执行引擎（异步：chatStream + onTaskStream 回调） ========================

    /**
     * 停止运行中的后台任务：agent.stopChat(execSession) 走现有取消链；终态由 onTaskStream 回调收尾
     * （cancelFlags + 在途 LLM 取消 + 派生进程清理，与 /stop 同款）。
     */
    private void stopTaskRun(TaskRecord rec) {
        stopTaskRunById(rec.owner + "/" + rec.taskId);
    }

    /** 按 fullId 停止运行态（看门狗扫描无 TaskRecord 时用；agent 名取 fullId 的 owner 前缀） */
    private void stopTaskRunById(String fullId) {
        RunState rs = fullId == null ? null : runningTasks.get(fullId);
        if (rs == null) return;
        rs.stopRequested = true;
        int i = fullId.indexOf('/');
        if (i <= 0) return;
        Object agent = getModuleInFactory(fullId.substring(0, i));
        if (agent instanceof IObject) {
            // stopChat 从消息 args 读 sessionId（getStringParam 不回退 systemArgs）
            TLMsg stop = createMsg().setAction(AGENT_STOPCHAT)
                    .setParam(AI_P_SESSIONID, rs.execSession);
            stop.setSystemParam(IGNOREMODULEISNULL, true);
            putMsg((IObject) agent, stop);
        }
    }

    /** 看门狗懒启动：首次执行时拉起（daemon；taskStaleMinutes<=0 表示关闭，不拉起） */
    private synchronized void ensureWatchdog() {
        if (watchdog != null || taskStaleMinutes <= 0) return;
        watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "task-watchdog");
            t.setDaemon(true);
            return t;
        });
        watchdog.scheduleWithFixedDelay(this::sweepStaleTasks, 60, 60, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** 周期扫描：超时未收尾的运行中任务强制收尾（background 一次性任务不依赖再次触发） */
    private void sweepStaleTasks() {
        try {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, RunState> e : runningTasks.entrySet()) {
                RunState rs = e.getValue();
                if (rs.terminal) continue;
                long ageMs = now - rs.startedAt;
                if (taskStaleMinutes > 0 && ageMs > taskStaleMinutes * 60000L) {
                    putLog("任务运行超时（看门狗扫描），强制收尾: " + e.getKey(), LogLevel.WARN);
                    stopTaskRunById(e.getKey());
                    finishTask(e.getKey(), "failed", "执行超时（看门狗 " + taskStaleMinutes + " 分钟）");
                }
            }
        } catch (Exception ex) {
            putLog("看门狗扫描异常: " + ex, LogLevel.WARN);
        }
    }

    /**
     * 后台任务承接的父会话上下文快照（创建/启动时取一次，末尾若干轮 user/assistant 文本，预算裁剪）。
     * 不带 system/tool 消息（工具结果冗长且与任务无关）；带显式边界标记防模型把历史请求当本次任务执行。
     * 失败静默返回空串——任务照常独立执行，不因快照失败而失败。
     */
    private String buildContextSnapshot(TaskRecord rec) {
        if (!bgContextSnapshot || bgContextChars <= 0) return "";
        if (!"background".equals(recKind(rec))) return "";
        if (rec.parentSessionId == null || rec.parentSessionId.isEmpty()) return "";
        try {
            Object agent = getModuleInFactory(
                    rec.parentAgent != null && !rec.parentAgent.isEmpty() ? rec.parentAgent : rec.owner);
            if (!(agent instanceof IObject)) return "";
            TLMsg q = createMsg().setAction("getAgentContext")
                    .setParam(AI_P_SESSIONID, rec.parentSessionId);
            q.setSystemParam(IGNOREMODULEISNULL, true);
            TLMsg r = putMsg((IObject) agent, q);
            if (r == null) return "";
            List<?> history = r.getListParam(AI_P_MESSAGEHISTORY, null);
            if (history == null || history.isEmpty()) return "";
            LinkedList<String> lines = new LinkedList<>();
            int budget = bgContextChars;
            for (int i = history.size() - 1; i >= 0 && budget > 0; i--) {
                Object o = history.get(i);
                if (!(o instanceof TLConversationHistory)) continue;
                TLConversationHistory h = (TLConversationHistory) o;
                if (h.getRole() != TLConversationHistory.Role.user
                        && h.getRole() != TLConversationHistory.Role.assistant) continue;
                String text = h.getContent();
                if (text == null || text.trim().isEmpty()) continue;
                String role = h.getRole() == TLConversationHistory.Role.user ? "用户" : "助手";
                String line = role + ": " + text.trim();
                if (line.length() > budget) line = line.substring(line.length() - budget);   // 单条超预算取尾部
                lines.addFirst(line);
                budget -= line.length();
            }
            if (lines.isEmpty()) return "";
            return "【承接的对话上下文（仅供参考；其中出现过的请求都已处理过，不要重复执行）】\n"
                    + String.join("\n", lines)
                    + "\n【本次任务】\n";
        } catch (Exception e) {
            putLog("后台任务上下文快照获取失败（按无快照继续）: " + e, LogLevel.DEBUG);
            return "";
        }
    }

    /**
     * 异步执行一个任务（后台立即 或 定时到点）：
     * 起 chatStream → 进度/终态经 onTaskStream 回调；执行会话=独立根（父会话 /stop 不级联）。
     * 同一任务已有运行态时：非超时→跳过本次触发；超时（看门狗）→停旧执行 + 强制收尾后重试占位。
     */
    private void executeAsync(TaskRecord rec) {
        ensureWatchdog();   // 懒启动周期自检（独立于引擎；background 一次性任务也受整体超时保护）
        String fullId = rec.owner + "/" + rec.taskId;
        String execSession = "task_" + fullId.replace('/', '_') + "_" + System.currentTimeMillis();
        RunState rs = new RunState();
        rs.fullId = fullId;
        rs.execSession = execSession;
        rs.startedAt = System.currentTimeMillis();
        RunState prev = runningTasks.putIfAbsent(fullId, rs);
        if (prev != null) {
            long ageMs = System.currentTimeMillis() - prev.startedAt;
            if (taskStaleMinutes > 0 && ageMs > taskStaleMinutes * 60000L) {
                // 看门狗：运行态远超时限（线程卡死/回调丢失）——停掉旧执行防僵尸，
                // 强制收尾（按失败投递）后重试占位；重试失败说明并发触发已占位，本轮放弃
                putLog("任务运行超时（看门狗），强制收尾后重跑: " + fullId, LogLevel.WARN);
                TaskRecord stale = loadRecords().get(fullId);
                if (stale != null) stopTaskRun(stale);
                finishTask(fullId, "failed", "执行超时（看门狗 " + taskStaleMinutes + " 分钟）");
                // 收尾已把最新记录（含 executedCount+1）写盘——后续 persistRec 必须用重载对象，
                // 否则会用陈旧副本把收尾结果覆盖回去
                TaskRecord fresh = loadRecords().get(fullId);
                if (fresh == null) return;                 // 任务已被删除：不复活
                rec = fresh;
                if (runningTasks.putIfAbsent(fullId, rs) != null) {
                    putLog("看门狗收尾后占位失败（并发触发），本轮放弃: " + fullId, LogLevel.WARN);
                    return;
                }
            } else {
                putLog("任务已在运行，跳过本次触发: " + fullId, LogLevel.WARN);
                return;
            }
        }
        runningBySession.put(execSession, rs);

        rec.status = "running";
        rec.execSession = execSession;
        rec.startedAt = System.currentTimeMillis();
        rec.finishedAt = 0;
        rec.resultText = null;
        rec.errorText = null;
        persistRec(rec);

        Object agent = getModuleInFactory(rec.owner);
        if (!(agent instanceof IObject)) {
            putLog("任务 [" + fullId + "] 找不到 agent 模块: " + rec.owner, LogLevel.ERROR);
            finishTask(fullId, "failed", "找不到 agent 模块: " + rec.owner);
            return;
        }
        registerTaskSession(rec, execSession);      // webui 归属登记（审批/事件定位；无 webui 静默跳过）
        publishTaskEvent("taskStarted", rec, null, null);

        String snapshot = buildContextSnapshot(rec);   // background：承接父会话末尾若干轮（定时任务/关闭时为空串）
        TLMsg chat = createMsg()
                .setAction(AGENT_CHATSTREAM)
                .setParam("userMessage", snapshot + (rec.prompt == null ? "" : rec.prompt))
                .setParam("taskId", fullId)
                .setParam("_streamResultFor", rec.owner)        // 名字仅作兜底（实例优先）
                .setParam("_streamResultAction", "onTaskStream");
        chat.setSystemParam(AI_P_SESSIONID, execSession);
        chat.setSystemParam("userId", rec.userId == null ? "default" : rec.userId);
        chat.setSystemParam("rootSessionId", execSession);
        chat.setSystemParam(AI_P_STREAMFORWARDINSTANCE, this);  // 转发直投本技能实例（勿用 targetInstance：会被 checkMsgAction 拦截弹回）
        try {
            putMsg((IObject) agent, chat);
            putLog("任务开始执行: " + fullId + " execSession=" + execSession, LogLevel.INFO);
        } catch (Exception e) {
            finishTask(fullId, "failed", "启动失败: " + e.getMessage());
        }
    }

    /**
     * 任务流式回调（agent 直投实例；跑在 provider 回调线程）：
     * 进度节流推送 + 输出累积（有界）+ 终态收尾。
     */
    protected TLMsg onTaskStream(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        RunState rs = runningBySession.get(sessionId);
        if (rs == null || rs.terminal) return null;

        String chunk = msg.getStringParam(AI_P_CHUNK, null);
        String toolName = msg.getStringParam("toolName", null);
        String streamErr = msg.getStringParam(AI_P_STREAMERROR, null);
        boolean done = msg.parseBoolean(AI_P_STREAMDONE, false);

        if (chunk != null) {
            synchronized (rs.output) {
                rs.output.append(chunk);
                if (rs.output.length() > 65536) rs.output.delete(0, rs.output.length() - 32768);
            }
        }
        if (toolName != null) {
            publishTaskProgress(rs, "tool", toolName, msg.getStringParam("toolOutput", ""));
        } else if (chunk != null) {
            long now = System.currentTimeMillis();
            if (now - rs.lastPushAt >= progressPushIntervalMs) {
                rs.lastPushAt = now;
                publishTaskProgress(rs, "chunk", null, chunk);
            }
        }
        if (done || streamErr != null) {
            String status = rs.stopRequested ? "stopped"
                    : (streamErr != null ? "failed" : "done");
            finishTask(rs.fullId, status, streamErr);
        }
        return null;
    }

    /** 终态收尾：记录回写 + 结果投递 + 终态事件 + 运行表清理（幂等：runningTasks.remove 返回 null 即已收尾） */
    private void finishTask(String fullId, String status, String error) {
        RunState rs = runningTasks.remove(fullId);
        if (rs == null) return;                        // 已被收尾（或从未运行）
        rs.terminal = true;
        runningBySession.remove(rs.execSession);
        unregisterTaskSession(rs.execSession);         // 先注销：删除竞态下也不泄漏
        TaskRecord rec = loadRecords().get(fullId);
        if (rec == null) return;                       // 任务已被删除：不复活
        // 代际校验：记录里的 execSession 与本次执行不符（上一轮残留/删除后重建）→ 不覆盖
        // rec.execSession == null（如删除后同名重建尚未起流）也算代际不符，不放行
        if (rec.execSession == null || !rec.execSession.equals(rs.execSession)) return;
        rec.status = status;
        rec.finishedAt = System.currentTimeMillis();
        if ("done".equals(status)) {
            synchronized (rs.output) {
                rec.resultText = rs.output.toString();
            }
            rec.progressSnapshot = null;
        } else if ("stopped".equals(status)) {
            rec.errorText = null;                 // 用户主动停止：不是错误
            rec.resultText = null;                // 不冒充结果；部分产出落 snapshot
            rec.progressSnapshot = rs.snapshot;
        } else {
            rec.errorText = error == null || error.isEmpty() ? "执行失败" : error;
            rec.resultText = null;
            rec.progressSnapshot = rs.snapshot;
        }
        rec.executedCount++;
        rec.lastExecuteTime = rec.finishedAt;
        String timesText = longText(rec.schedule.get("times"));
        if (timesText != null && !"0".equals(timesText)) {
            try {
                if (rec.executedCount >= Long.parseLong(timesText)) rec.enabled = false;
            } catch (NumberFormatException e) {
                putLog("times 参数非法（按无限次处理）: " + timesText, LogLevel.WARN);
            }
        }
        persistRec(rec);

        String targetSession = deliverTaskResult(rec);
        publishTaskEvent("taskResult", rec, targetSession, null);
        putLog("任务结束: " + fullId + " status=" + status, LogLevel.INFO);
    }

    /**
     * 结果投递：有发起会话（后台任务）→ agent 注入（空闲直注/忙排队）；
     * 定时任务（无发起会话）→ 保持旧行为：结果事件落用户当前会话；
     * 离线 → 消息箱。返回结果事件的生效目标会话（供 publishTaskEvent 用）。
     */
    private String deliverTaskResult(TaskRecord rec) {
        String text;
        if ("done".equals(rec.status)) text = rec.resultText == null ? "" : rec.resultText;
        else if ("stopped".equals(rec.status)) text = "任务已停止";
        else text = "任务失败：" + (rec.errorText == null ? "" : rec.errorText);
        String effective = rec.parentSessionId == null ? "" : rec.parentSessionId;
        if ("agent".equals(rec.type) && !effective.isEmpty()) {
            try {
                Object agent = getModuleInFactory(
                        rec.parentAgent != null && !rec.parentAgent.isEmpty() ? rec.parentAgent : rec.owner);
                if (agent instanceof IObject) {
                    TLMsg inj = createMsg().setAction("injectTaskResult")
                            .setParam(AI_P_SESSIONID, rec.parentSessionId)
                            .setParam("taskId", rec.taskId)
                            .setParam("name", rec.name == null ? rec.taskId : rec.name)
                            .setParam("text", text)
                            .setParam(AI_P_USERID, rec.userId);
                    inj.setSystemParam(IGNOREMODULEISNULL, true);
                    putMsg((IObject) agent, inj);
                }
            } catch (Exception e) {
                putLog("注入任务结果失败（继续离线投递）: " + e, LogLevel.DEBUG);
            }
        }
        WebuiStatus st = queryUserStatus(rec.userId);
        if (!st.online) {
            deliverToInbox(rec.userId, rec.taskId, text);
        } else if (effective.isEmpty()) {
            effective = st.sessionId;   // 定时任务：结果事件落用户当前会话（旧行为）
        }
        return effective;
    }

    /**
     * 定时任务执行入口（引擎回调 action=runScheduledTask）。
     * 2026-10-06 起与后台任务统一走异步执行引擎：起 chatStream，进度/终态经 onTaskStream 回调；
     * 结果投递（父会话注入/在线推送/离线消息箱）在 finishTask 内统一处理。
     * "到点必执行"语义不变（异步启动）；顺带解决调度线程被整轮 LLM 阻塞；
     * 同一任务上轮未跑完时本轮触发被拒（putIfAbsent），不再产生互相孤儿化的并发副本。
     */
    protected TLMsg runScheduledTask(Object fromWho, TLMsg msg) {
        String taskId = msg.getStringParam("taskId", "?");
        // 权威身份：从持久化记录取（重启后消息携带的会话缓存都不可靠；taskId 即 fullId）
        TaskRecord rec = loadRecords().get(taskId);
        if (rec == null) {
            putLog("定时任务 [" + taskId + "] 记录不存在（跳过）", LogLevel.WARN);
            return null;
        }
        executeAsync(rec);
        return null;
    }

    /**
     * message 型任务回调（经 agent 通用投递到达）：转发原始消息到目标模块/动作（不经 LLM），
     * 然后回写计数/一次性停用（与 runScheduledTask 同款收尾）。
     */
    protected TLMsg runScheduledMessage(Object fromWho, TLMsg msg) {
        String taskId = msg.getStringParam("taskId", "?");
        String destModule = msg.getStringParam("destModule", "");
        String destAction = msg.getStringParam("destAction", "");
        if (destModule.isEmpty() || destAction.isEmpty()) {
            putLog("任务 [" + taskId + "] message 型缺少 module/action", LogLevel.ERROR);
            updateRecordAfterRun(taskId);
            return null;
        }
        TLMsg m = createMsg().setAction(destAction);
        String argsJson = msg.getStringParam("destArgsJson", "");
        if (argsJson != null && !argsJson.isEmpty()) {
            try {
                Map<String, Object> extra = gson.fromJson(argsJson, new TypeToken<Map<String, Object>>() {}.getType());
                if (extra != null) m.addArgs(extra);
            } catch (Exception e) {
                putLog("args JSON 解析失败: " + e.getMessage(), LogLevel.WARN);
            }
        }
        m.addSystemArgs(msg.getSystemArgs());
        Object target = getModuleFromFactory(destModule);
        TLMsg r = null;
        if (target instanceof IObject) {
            r = putMsg((IObject) target, m);
        } else {
            putLog("任务 [" + taskId + "] 目标模块不存在: " + destModule, LogLevel.ERROR);
        }
        updateRecordAfterRun(taskId);
        return r;
    }

    /** 用户在线状态（webui getUserStatus 查询）：online=有前端上报的当前会话 */
    private static class WebuiStatus {
        boolean online;
        String sessionId = "";
    }

    /** 查询用户在线状态与当前会话（无 webui 模块时视为离线） */
    private WebuiStatus queryUserStatus(String userId) {
        WebuiStatus st = new WebuiStatus();
        try {
            Object webui = getModuleInFactory("webui");
            if (!(webui instanceof IObject)) return st;
            TLMsg q = createMsg().setAction("getUserStatus").setParam("userId", userId);
            q.setSystemParam(IGNOREMODULEISNULL, true);
            TLMsg r = putMsg((IObject) webui, q);
            if (r == null) return st;
            st.online = r.parseBoolean("online", false);
            st.sessionId = r.getStringParam("sessionId", "");
            return st;
        } catch (Exception e) {
            putLog("查询用户在线状态失败（按离线处理）: " + e, LogLevel.DEBUG);
            return st;
        }
    }

    /** 离线投递：写入离线消息箱（webui inboxAdd），登录后未读提示可达 */
    private void deliverToInbox(String userId, String taskId, String answer) {
        try {
            Object webui = getModuleInFactory("webui");
            if (!(webui instanceof IObject)) return;
            TLMsg m = createMsg().setAction("inboxAdd")
                    .setParam("userId", userId)
                    .setParam("source", "task")
                    .setParam("taskId", taskId)
                    .setParam("text", answer);
            m.setSystemParam(IGNOREMODULEISNULL, true);
            putMsg((IObject) webui, m);
        } catch (Exception e) {
            putLog("离线投递失败: " + e, LogLevel.WARN);
        }
    }

    /** 任务事件推送（msgBus topic=type：taskStarted/taskProgress/taskResult；webui 订阅后按 userId 推 SSE） */
    private void publishTaskEvent(String type, TaskRecord rec, String targetSession, Map<String, Object> extra) {
        try {
            Object bus = getModuleInFactory("msgBus");
            if (!(bus instanceof IObject)) return;
            TLMsg evt = createMsg().setAction(type)
                    .setParam("userId", rec.userId)
                    .setParam("taskId", rec.taskId)
                    .setParam("name", rec.name == null ? rec.taskId : rec.name)
                    .setParam("kind", recKind(rec))
                    .setParam("status", rec.status == null ? "" : rec.status)
                    .setParam("sessionId", targetSession != null ? targetSession
                            : (rec.parentSessionId == null ? "" : rec.parentSessionId))
                    .setParam("parentSessionId", rec.parentSessionId == null ? "" : rec.parentSessionId)
                    .setParam("execSession", rec.execSession == null ? "" : rec.execSession)
                    .setParam("creationSessionId", rec.creationSessionId == null ? "" : rec.creationSessionId);
            if (extra != null) evt.addArgs(extra);
            if ("taskResult".equals(type)) {
                String text = "done".equals(rec.status)
                        ? (rec.resultText == null ? "" : rec.resultText)
                        : ("stopped".equals(rec.status)
                                ? "任务已停止"
                                : (rec.errorText == null ? "" : rec.errorText));
                evt.setParam("text", text.length() > 4000 ? text.substring(0, 4000) + "…" : text);
            }
            evt.setDestination(type);
            putMsg((IObject) bus, evt);
        } catch (Exception e) {
            putLog("推送任务事件失败: " + e, LogLevel.DEBUG);
        }
    }

    /** 进度推送（正文节流由调用方控制；工具事件即时） */
    private void publishTaskProgress(RunState rs, String kind, String toolName, String text) {
        try {
            TaskRecord rec = loadRecords().get(rs.fullId);
            if (rec == null) return;
            String t = text == null ? "" : text;
            if (t.length() > 4000) t = t.substring(0, 4000);
            synchronized (rs.output) {
                // 快照有界（最后 4KB）——供中途打开详情面板拉全量（P7 get 从 RunState 读，不落盘）
                rs.snapshot = rs.output.length() > 4096
                        ? rs.output.substring(rs.output.length() - 4096) : rs.output.toString();
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("progressKind", kind);        // chunk | tool
            extra.put("toolName", toolName == null ? "" : toolName);
            extra.put("text", t);
            publishTaskEvent("taskProgress", rec, null, extra);
        } catch (Exception e) {
            putLog("推送任务进度失败: " + e, LogLevel.DEBUG);
        }
    }

    /** 任务会话归属登记（webui：sessionOwner + execSession→父会话映射；无 webui 模块静默跳过） */
    private void registerTaskSession(TaskRecord rec, String execSession) {
        try {
            Object webui = getModuleInFactory("webui");
            if (!(webui instanceof IObject)) return;
            TLMsg m = createMsg().setAction("registerTaskSession")
                    .setParam("execSession", execSession)
                    .setParam("parentSessionId", rec.parentSessionId == null ? "" : rec.parentSessionId)
                    .setParam("taskId", rec.taskId)
                    .setParam("userId", rec.userId);
            m.setSystemParam(IGNOREMODULEISNULL, true);
            putMsg((IObject) webui, m);
        } catch (Exception e) {
            putLog("登记任务会话失败: " + e, LogLevel.DEBUG);
        }
    }

    private void unregisterTaskSession(String execSession) {
        try {
            Object webui = getModuleInFactory("webui");
            if (!(webui instanceof IObject) || execSession == null) return;
            TLMsg m = createMsg().setAction("unregisterTaskSession").setParam("execSession", execSession);
            m.setSystemParam(IGNOREMODULEISNULL, true);
            putMsg((IObject) webui, m);
        } catch (Exception e) {
            putLog("注销任务会话失败: " + e, LogLevel.DEBUG);
        }
    }

    // ======================== create ========================

    // 与 persistRec 同锁（实例级）：本方法/removeTask/updateTask/updateRecordAfterRun 都是
    // "recordsOf（读盘）→改→saveRecords（写盘）"的无锁 RMW，并行任务收尾与建/删/改交错会丢写
    // （状态回退）。synchronized 可重入，方法间嵌套调用无死锁；只读遍历（list/tasksCmdList）不加，允许脏读。
    private synchronized TLMsg createTask(Map<String, Object> input, String sessionId, String userId, String rootSessionId) {
        String prompt = str(input, "prompt", null);
        String module = str(input, "module", null);
        String action = str(input, "action", null);
        if (prompt != null && module != null)
            return fail("prompt (agent task) and module/action (message task) are mutually exclusive");
        if (prompt == null && (module == null || action == null))
            return fail("agent task needs 'prompt'; message task needs 'module' + 'action'");

        // ---- 任务种类：schedule（定时，缺省）| background（后台立即，不注册调度）----
        String kind = str(input, "kind", "schedule");
        if (!kind.equals("schedule") && !kind.equals("background"))
            return fail("kind must be schedule|background");
        boolean background = "background".equals(kind);
        if (background && (prompt == null || prompt.isEmpty()))
            return fail("kind=background 需要 prompt（后台任务执行 agent 轮次）");

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
        if (background) {
            cron = null; delay = null; times = null; begin = null; oneShot = false;
            // 后台任务不注册调度：创建即执行
        } else {
            if (cron == null && delay == null && !oneShot)
                return fail("需要明确调度：给 cron（周期）或 delay（间隔秒数）或 one_shot=true");
            if (oneShot && delay == null) delay = 0L;
            if (oneShot) times = 1L;
        }

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
        if (recordsOf(userId).containsKey(fullId))
            return fail("任务已存在: " + taskId + "（换一个 task_id 或用 update）");

        // ---- 记录 + 注册 ----
        TaskRecord rec = new TaskRecord();
        rec.taskId = taskId;
        rec.owner = ownerAgent;
        rec.userId = userId;
        rec.creationSessionId = sessionId;
        rec.type = prompt != null ? "agent" : "message";
        rec.kind = kind;
        rec.name = str(input, "name", null);
        if (rec.name == null || rec.name.isEmpty()) {
            String p = prompt != null ? prompt : (module + "." + action);
            rec.name = p.length() > 20 ? p.substring(0, 20) : p;
        }
        if (background) {
            rec.parentSessionId = sessionId;
            rec.parentRootSessionId = rootSessionId;
            rec.parentAgent = ownerAgent;
            rec.status = "pending";
        }
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

        if (background) {
            // 跨用户唯一性：运行表/记录表键都是 owner/taskId（不含用户），重名会互相覆盖/泄露
            TaskRecord clash = loadRecords().get(fullId);
            if (clash != null && !userId.equals(clash.userId))
                return fail("任务ID已被占用: " + taskId + "（换一个 task_id）");
            long running = runningCountOf(userId);
            if (running >= maxConcurrentTasks)
                return fail("后台任务已达上限（" + maxConcurrentTasks + " 个并行）；先停止部分任务再试");
        } else {
            // 引擎键是 fullId（不含用户），跨用户可能撞车——注册前查引擎，已存在则拒绝
            TLMsg engineTasks = sendToEngine(createMsg().setAction("getTasks"));
            if (engineTasks != null && engineTasks.getParam("tasks") instanceof Map
                    && ((Map<?, ?>) engineTasks.getParam("tasks")).containsKey(fullId))
                return fail("任务ID已被占用: " + taskId + "（换一个 task_id）");
            TLMsg reg = buildRegistMsg(rec);
            if (reg == null) return fail("任务参数构造失败（见日志）");
            TLMsg r = sendToEngine(reg);
            if (r == null || !Boolean.TRUE.equals(r.getParam(RESULT))) {
                String err = r == null ? "引擎无响应" : r.getStringParam("error", "unknown");
                return fail("注册失败: " + err + "（检查 msgTaskModule 配置: " + msgTaskModule + "）");
            }
        }

        Map<String, TaskRecord> map = recordsOf(userId);
        map.put(fullId, rec);
        String warn = saveRecords(userId, map);
        if (background) {
            executeAsync(rec);                        // 落盘后执行（finishTask 依赖记录可查）
            StringBuilder out2 = new StringBuilder("已创建后台任务并开始执行\n")
                    .append("- 任务ID: ").append(taskId).append("\n")
                    .append("- 名称: ").append(rec.name).append("\n")
                    .append("- 说明: 结果完成后自动回到本会话（可随时用 op=list 查看状态、op=stop 停止）");
            if (warn != null) out2.append("\n- ⚠ ").append(warn);
            return ok(out2.toString());
        }
        String next = nextFireText(fullId);
        StringBuilder out = new StringBuilder("已创建定时任务\n")
                .append("- 任务ID: ").append(taskId).append("\n")
                .append("- 类型: ").append(rec.type.equals("agent") ? "agent 轮次" : "固定消息")
                .append("  ").append(rec.type.equals("agent") ? rec.prompt : rec.module + "." + rec.action).append("\n")
                .append("- 调度: ").append(scheduleText(rec)).append("\n")
                .append("- 下次执行: ").append(next == null ? "（已注册，等待引擎排期）" : next);
        if (warn != null) out.append("\n- ⚠ ").append(warn);
        return ok(out.toString());
    }

    /** 某用户当前在跑的后台任务数（只数 kind=background；上限判定用） */
    private long runningCountOf(String userId) {
        Map<String, TaskRecord> all = loadRecords();
        long n = 0;
        for (RunState rs : runningTasks.values()) {
            TaskRecord r = all.get(rs.fullId);
            if (r != null && userId.equals(r.userId) && "background".equals(recKind(r))) n++;
        }
        return n;
    }

    /** 按记录构造引擎注册消息；记录不可构造（如目标模块缺失）返回 null */
    private TLMsg buildRegistMsg(TaskRecord rec) {
        String fullId = rec.owner + "/" + rec.taskId;
        TLMsg taskMsg;
        if ("agent".equals(rec.type)) {
            // destination=agent（引擎/框架按名路由）、systemArgs 带本技能实例引用——
            // 技能是 agent 私有子模块，外部按名够不着，实例由本技能自备；
            // 框架通用投递（dispatchToInstance）到 agent 后直投实例，到点落到本技能
            taskMsg = createMsg()
                    .setDestination(rec.owner)
                    .setAction("runScheduledTask")
                    .setParam("taskPrompt", rec.prompt)
                    .setParam("taskId", fullId)
                    .setParam("recordSession", rec.creationSessionId);
            taskMsg.setSystemParam(AI_P_TARGETINSTANCE, this);
        } else {
            // message 型：经技能回调（runScheduledMessage）再转发原始消息——
            // 引擎直接投递会绕过技能，导致计数/一次性停用不回写（重启后一次性任务又跑）；
            // 转发目标仍是记录的 module/action，语义不变（不经 LLM）
            taskMsg = createMsg()
                    .setDestination(rec.owner)
                    .setAction("runScheduledMessage")
                    .setParam("taskId", fullId)
                    .setParam("destModule", rec.module)
                    .setParam("destAction", rec.action)
                    .setParam("destArgsJson", rec.argsJson == null ? "" : rec.argsJson);
            taskMsg.setSystemParam(AI_P_TARGETINSTANCE, this);
        }
        taskMsg.setSystemParam(AI_P_SESSIONID, rec.creationSessionId == null ? "default" : rec.creationSessionId);
        taskMsg.setSystemParam("userId", rec.userId == null ? "default" : rec.userId);

        // 注册参数必须挂在内层任务消息上（引擎 doRegistTask/startTask 只读内层；
        // 外层仅承载 action=registTask + msg）
        taskMsg.setParam("taskid", fullId);
        taskMsg.setParam("status", "run");
        Object cron = rec.schedule.get("cron");
        if (cron != null) taskMsg.setParam("cronExp", String.valueOf(cron));
        Object delay = rec.schedule.get("delay");
        String delayText = longText(delay);
        // 注意：delay=0 必须显式下发（引擎缺省是 60 秒；one_shot 的"立即"语义依赖 0）
        if (delayText != null) taskMsg.setParam("delay", delayText);
        Object unit = rec.schedule.get("unit");
        if (unit != null) taskMsg.setParam("timeUnit", String.valueOf(unit));
        // 循环间隔是引擎的 period（缺省 60 秒，不是 delay）——固定间隔任务必须显式下发，
        // 否则"每 5 秒"实际变成每 60 秒（cron 任务不需要 period，留空）
        if (cron == null && delayText != null)
            taskMsg.setParam("period", delayText);
        Object times = rec.schedule.get("times");
        String timesText = longText(times);
        if (timesText != null && !"0".equals(timesText)) taskMsg.setParam("times", timesText);
        // begin：技能语义是"首触发前的额外延迟秒数"，引擎语义是绝对 epoch 毫秒
        // （TLMsgTask: begin>now 才算 initialDelay，过去值强制 0 并覆盖 delay）——换算后下发
        Object begin = rec.schedule.get("begin");
        String beginText = longText(begin);
        if (beginText != null) {
            try {
                long extraSec = Long.parseLong(beginText);
                if (extraSec > 0)
                    taskMsg.setParam("begin", String.valueOf(System.currentTimeMillis() + extraSec * 1000L));
            } catch (NumberFormatException e) {
                putLog("begin 参数非法（忽略）: " + beginText, LogLevel.WARN);
            }
        }

        // 到点由引擎直接 putMsg(内层消息)：destination 解析失败时不要触发 moduleFactory.shutdown(-1)
        taskMsg.setSystemParam(IGNOREMODULEISNULL, true);
        return createMsg().setAction("registTask").setParam("msg", taskMsg);
    }

    /** 调度描述文本；数字经 longText 归一化（Gson 读回是 Double，直接拼会渲染成 "5.0"） */
    private String scheduleText(TaskRecord rec) {
        if ("background".equals(recKind(rec))) return "后台立即执行";   // 无调度：避免拼出 "每 null s"
        Object cron = rec.schedule.get("cron");
        if (cron != null) return "cron: " + cron;
        String delayText = longText(rec.schedule.get("delay"));
        Object unit = rec.schedule.get("unit");
        String timesText = longText(rec.schedule.get("times"));
        StringBuilder s = new StringBuilder("每 ").append(delayText).append(" ").append(unit);
        if (timesText != null && !"0".equals(timesText)) s.append("，共 ").append(timesText).append(" 次");
        return s.toString();
    }

    /** 引擎里该任务的摘要 Map（不存在/引擎不可用返回空表） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> engineInfo(String fullId) {
        TLMsg r = sendToEngine(createMsg().setAction("getTasks"));
        if (r == null || !(r.getParam("tasks") instanceof Map)) return new LinkedHashMap<>();
        Object info = ((Map<?, ?>) r.getParam("tasks")).get(fullId);
        return info instanceof Map ? (Map<String, Object>) info : new LinkedHashMap<>();
    }

    /** 从引擎摘要取该任务的下次执行时间文本（引擎计算，非 LLM 推算） */
    private String nextFireText(String fullId) {
        return fmtTime(engineInfo(fullId).get("nextDatetime"));
    }

    private TLMsg sendToEngine(TLMsg m) {
        try {
            // putMsg(String) 内部 getModule 会创建目标模块（即"启动引擎"）并把消息发去，
            // 恢复时机引擎未起也能拉起；若模块确实不存在，IGNOREMODULEISNULL 让它
            // 返回空消息而不是 moduleFactory.shutdown(-1) 关掉整个进程
            m.setSystemParam(IGNOREMODULEISNULL, true);
            return putMsg(msgTaskModule, m);
        } catch (Exception e) {
            putLog("发送给定时任务引擎失败: " + e, LogLevel.ERROR);
            return null;
        }
    }

    // ======================== list / remove / update ========================

    /**
     * 列出当前用户在本 agent 下的任务：持久化记录 + 引擎实时状态合并
     * （状态/次数/下次执行以引擎为准；引擎不可用时降级为本地记录，不报错）。
     */
    /**
     * 状态文案：引擎里在跑 → 运行中；引擎里已停（或引擎里没有该任务）→ 已停止；
     * 引擎里没有且记录本身停用 → paused（用户主动暂停/一次性执行完）。
     * 引擎 status 常量是英文 run/stopped/error（引擎内部值，不直接露面）。
     */
    private String engineStatusText(Object engineStatus, boolean recEnabled) {
        String s = engineStatus == null ? null : String.valueOf(engineStatus);
        if ("run".equals(s)) return "运行中";
        if (s != null && !s.isEmpty()) return "已停止";          // stopped / error
        return recEnabled ? "已停止" : "paused";                 // 引擎无此任务：区分"记录残留"与"主动停用"
    }

    /** background 任务的本地状态文案（不走引擎）；未知/空按 pending */
    private String bgStatusText(TaskRecord rec) {
        String s = rec.status == null ? "" : rec.status;
        switch (s) {
            case "running":     return "运行中";
            case "done":        return "已完成";
            case "failed":      return "失败";
            case "stopped":     return "已停止";
            case "interrupted": return "已中断";
            default:            return "排队中";
        }
    }

    @SuppressWarnings("unchecked")
    private TLMsg listTasks(String userId) {
        // recordsOf 已按 userId 过滤，这里天然只含当前用户的任务
        Map<String, TaskRecord> mine = recordsOf(userId);
        if (mine.isEmpty()) return ok("当前没有定时任务（用户 " + userId + "）");

        // 引擎摘要：fullId → info（status/nextDatetime/executedCount）；引擎未起时为空表
        Map<String, Object> engineTasks = new LinkedHashMap<>();
        TLMsg r = sendToEngine(createMsg().setAction("getTasks"));
        if (r != null && r.getParam("tasks") instanceof Map)
            engineTasks = (Map<String, Object>) r.getParam("tasks");

        StringBuilder out = new StringBuilder("定时任务列表（用户 " + userId + "，共 ")
                .append(mine.size()).append(" 个）\n");
        int i = 1;
        for (Map.Entry<String, TaskRecord> e : mine.entrySet()) {
            TaskRecord rec = e.getValue();
            Object infoObj = engineTasks.get(e.getKey());
            Map<String, Object> info = infoObj instanceof Map
                    ? (Map<String, Object>) infoObj : new LinkedHashMap<>();
            // 引擎无该任务时：停用的显示 paused；启用但引擎里没有 → 已停止（记录残留，如重启未恢复），
            // 不能让 LLM 误读成"活着等调度"。
            // background 任务不在引擎里，状态取本地记录（否则恒"已停止"，正在跑的任务被误报）
            boolean engineKnown = info.get("status") != null;
            boolean background = "background".equals(recKind(rec));
            String status = background
                    ? bgStatusText(rec) : engineStatusText(info.get("status"), rec.enabled);
            // 只有引擎实际在跑才显示"下次执行"（残留/停止的旧时间会误导 LLM 判断任务状态）；
            // background 无调度，绝不显示
            String next = !background && engineKnown && "运行中".equals(status)
                    ? fmtTime(info.get("nextDatetime")) : null;
            // 引擎对已停止/已完成的有限次任务会丢 runtime，摘要回 executedCount=0；
            // 记录里的 executedCount 才是真值，取两者最大（longText 归一化 Gson 的 Double）
            Object cnt = info.get("executedCount");
            String cntText = longText(cnt);
            long cntLong = Math.max(rec.executedCount,
                    cntText == null ? 0L : Long.parseLong(cntText));
            out.append(i++).append(". ").append(rec.taskId)
                    .append("  [").append(status).append("]")
                    .append("  ").append(scheduleText(rec))
                    .append("  已执行 ").append(cntLong).append(" 次")
                    .append(next == null ? "" : "  下次 " + next)
                    .append("\n   ").append("agent".equals(rec.type) ? "prompt: " + rec.prompt
                            : "message: " + rec.module + "." + rec.action);
            if (engineKnown && !rec.enabled) out.append("  (已停用)");
            out.append("\n");
        }
        out.append("用 op=remove 删除、op=update 暂停/恢复或改调度");
        return ok(out.toString());
    }

    /**
     * 删除任务：校验归属 → 引擎 unRegistTask → 清持久化。
     * 归属校验由"能不能在当前用户的记录里找到"完成：找不到即不存在或不属于当前用户（不区分，防信息泄露）。
     */
    private synchronized TLMsg removeTask(Map<String, Object> input, String userId) {
        String taskId = str(input, "task_id", null);
        if (taskId == null || taskId.isEmpty()) return fail("remove 需要 task_id");
        String fullId = ownerAgent + "/" + taskId;
        Map<String, TaskRecord> mine = recordsOf(userId);
        TaskRecord rec = mine.get(fullId);
        if (rec == null) return fail("任务不存在: " + taskId + "（用 op=list 查看）");

        // 运行中的任务先停（否则删记录后流继续烧 token、收尾无记录可投递）
        if (runningTasks.containsKey(fullId)) stopTaskRun(rec);

        TLMsg r = sendToEngine(createMsg().setAction("unRegistTask").setParam("taskid", fullId));
        boolean engineOk = r != null && Boolean.TRUE.equals(r.getParam(RESULT));
        mine.remove(fullId);
        String warn = saveRecords(userId, mine);
        StringBuilder out = new StringBuilder("已删除定时任务: " + taskId);
        // 引擎 unRegistTask 对任何 taskid 都回 RESULT=true（只有调用失败/无响应才 false），
        // 所以这里只说明"引擎侧可能已无此任务"，不透传"未找到"结论
        if (!engineOk) out.append("\n（提示：引擎未响应或任务已不在引擎中，已清理本地记录）");
        if (warn != null) out.append("\n- ⚠ ").append(warn);
        return ok(out.toString());
    }

    /**
     * 更新任务：
     * - enabled=false → 引擎 stopTask（保留记录，重启不恢复）
     * - enabled=true  → 引擎已有该任务则 startTask；不在（重启后暂停/已跑完的）则重新注册
     *                   （registTask + status=run 即注册即启动，避免 startTask 静默失败）
     * - 改了 cron/delay/unit/times/begin → 用新调度重注册（unRegist + regist），记录同步
     */
    private synchronized TLMsg updateTask(Map<String, Object> input, String userId) {
        String taskId = str(input, "task_id", null);
        if (taskId == null || taskId.isEmpty()) return fail("update 需要 task_id");
        String fullId = ownerAgent + "/" + taskId;
        Map<String, TaskRecord> mine = recordsOf(userId);
        TaskRecord rec = mine.get(fullId);
        if (rec == null) return fail("任务不存在: " + taskId + "（用 op=list 查看）");

        if ("background".equals(recKind(rec)))
            return fail("后台任务是立即执行的一次性任务，不支持 update（可重新 create，或用 op=stop 停止运行中的任务）");

        boolean scheduleChanged = input.containsKey("cron") || input.containsKey("delay")
                || input.containsKey("unit") || input.containsKey("times") || input.containsKey("begin");
        String enabledStr = str(input, "enabled", null);
        if (enabledStr != null && enabledStr.isEmpty()) enabledStr = null;   // 空串＝未给（不是暂停）

        if (scheduleChanged) {
            String cron = str(input, "cron", null);
            Long delay = longOf(input, "delay");
            if (cron != null && !CronExpression.isValidExpression(cron))
                return fail("invalid cron expression: " + cron);
            // 只改 times/begin/unit 时沿用记录里的原调度：cron 或 delay 任一存在即可。
            // 记录里的数字 Gson 读回是 Double（也可能因不写 null 读回 null），这里只做 null 判定
            if (cron == null && delay == null && rec.schedule.get("cron") == null
                    && rec.schedule.get("delay") == null)
                return fail("改调度需要给 cron 或 delay");
            // cron 与固定间隔二选一：同给时 cron 优先
            if (cron != null) {
                rec.schedule.put("cron", cron);
                rec.schedule.put("delay", null);
            } else if (delay != null) {
                rec.schedule.put("delay", delay);
                rec.schedule.put("cron", null);
            }
            if (input.containsKey("unit")) {
                String u = str(input, "unit", "s");
                if (!u.equals("s") && !u.equals("m") && !u.equals("h")) return fail("unit must be s|m|h");
                rec.schedule.put("unit", u);
            }
            if (input.containsKey("times")) rec.schedule.put("times", longOf(input, "times"));
            if (input.containsKey("begin")) rec.schedule.put("begin", longOf(input, "begin"));
            rec.enabled = true;

            // 先落盘新调度（重启恢复按新值），再重注册
            String warn = saveRecords(userId, mine);

            sendToEngine(createMsg().setAction("unRegistTask").setParam("taskid", fullId));
            TLMsg reg = buildRegistMsg(rec);
            if (reg == null) return fail("任务参数构造失败（见日志）");
            TLMsg r = sendToEngine(reg);
            if (r == null || !Boolean.TRUE.equals(r.getParam(RESULT)))
                return fail("重注册失败: " + (r == null ? "引擎无响应" : r.getStringParam("error", "unknown"))
                        + "（记录已更新，重启后按新调度恢复）");

            String next = nextFireText(fullId);
            StringBuilder out = new StringBuilder("已更新任务 ").append(taskId).append(" 的调度：")
                    .append(scheduleText(rec))
                    .append("\n- 下次执行: ").append(next == null ? "（已注册，等待引擎排期）" : next);
            if (warn != null) out.append("\n- ⚠ ").append(warn);
            return ok(out.toString());
        }

        if (enabledStr != null) {
            boolean enabled = "true".equalsIgnoreCase(enabledStr);
            if (enabled) {
                rec.enabled = true;
                String warn = saveRecords(userId, mine);
                // 重启后暂停/一次性跑完的任务不在引擎里（runStartMsg 只注册 enabled 的），
                // 此时直接 startTask 会静默失败：doStartTask 无条件回 RESULT=true，内部
                // startTask 却因 taskConfigs 找不到配置只打 ERROR 日志、不调度。所以先查引擎。
                // 判据（TLMsgTask.doGetTask 带 taskid 分支）：任务存在时直接 copyFrom(config)——
                // 返回消息【没有 RESULT 参数】，但 taskid/destination 等配置项齐全（正向证据
                // 取 taskid==fullId）；任务不存在时回 RESULT=false + "任务不存在"；引擎模块缺失时
                // sendToEngine 回只带 ignoreModuleIsNull 的空消息。故：
                // inEngine = 非显式 false 且 taskid 对得上（避免把"引擎无响应"误判为在引擎里）
                boolean inEngine = false;
                TLMsg chk = sendToEngine(createMsg().setAction("getTasks").setParam("taskid", fullId));
                if (chk != null && !Boolean.FALSE.equals(chk.getParam(RESULT))
                        && fullId.equals(chk.getStringParam("taskid", null)))
                    inEngine = true;

                TLMsg r = null;
                if (inEngine) {
                    r = sendToEngine(createMsg().setAction("startTask").setParam("taskid", fullId));
                } else {
                    // 不在引擎里：重新注册（内层 status=run，registTask 会顺带启动）
                    TLMsg reg = buildRegistMsg(rec);
                    if (reg != null) r = sendToEngine(reg);
                }
                StringBuilder out = new StringBuilder();
                if (r == null || !Boolean.TRUE.equals(r.getParam(RESULT)))
                    out.append("已启用任务 ").append(taskId).append("（引擎无响应；将在下次重启恢复时注册）");
                else {
                    String next = nextFireText(fullId);
                    out.append("已恢复任务 ").append(taskId)
                            .append("，下次执行: ").append(next == null ? "（已注册，等待引擎排期）" : next);
                }
                if (warn != null) out.append("\n- ⚠ ").append(warn);
                return ok(out.toString());
            } else {
                sendToEngine(createMsg().setAction("stopTask").setParam("taskid", fullId));
                rec.enabled = false;
                String warn = saveRecords(userId, mine);
                String out = "已暂停任务 " + taskId + "（重启后不恢复；op=update enabled=true 恢复）";
                return warn == null ? ok(out) : ok(out + "\n- ⚠ " + warn);
            }
        }
        return fail("update 需要至少一个改动：enabled 或 cron/delay/unit/times/begin");
    }

    // ======================== 持久化 ========================

    /** 用户任务文件：<storageRoot>/<userId>/scheduled_tasks/<ownerAgent>.json */
    private File storageFile(String userId) {
        return new File(storageDir(userId), ownerAgent + ".json");
    }

    /** 全部用户任务文件的合并表（恢复/执行期用 fullId 直查）；saveRecords(userId,...) 写单个用户 */
    private synchronized Map<String, TaskRecord> loadRecords() {
        Map<String, TaskRecord> map = new LinkedHashMap<>();
        for (File f : allTaskFiles()) {
            for (TaskRecord r : readFile(f)) {
                if (r != null && r.owner != null && r.taskId != null) map.put(r.owner + "/" + r.taskId, r);
            }
        }
        return map;
    }

    /** 写某个用户的任务文件（原子替换）；成功返回 null，失败返回错误文本（调用方附在回执里） */
    private synchronized String saveRecords(String userId, Map<String, TaskRecord> map) {
        File f = storageFile(userId);
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try {
            Files.write(tmp.toPath(), gson.toJson(new ArrayList<>(map.values())).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFail) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return null;
        } catch (Exception e) {
            tmp.delete();   // 失败残留 .tmp 只会污染目录，清理掉
            putLog("写入任务持久化失败: " + e, LogLevel.WARN);
            return "任务已在内存中运行，但持久化失败（重启后不恢复）: " + e.getMessage();
        }
    }

    /** 该用户在内存里应持有的记录（按 userId 过滤全量表） */
    private Map<String, TaskRecord> recordsOf(String userId) {
        Map<String, TaskRecord> out = new LinkedHashMap<>();
        for (Map.Entry<String, TaskRecord> e : loadRecords().entrySet()) {
            TaskRecord r = e.getValue();
            if (r.userId != null && r.userId.equals(userId)) out.put(e.getKey(), r);
        }
        return out;
    }

    /** 解析 storageRoot 为绝对路径（相对 configDir；剥离 CLASSPATH/ 前缀） */
    private String resolveStorageRoot() {
        String root = storageRoot;
        if (root == null || root.isEmpty()) root = "./data/";
        if (!new File(root).isAbsolute() && moduleFactory != null && moduleFactory.getConfigDir() != null) {
            String base = moduleFactory.getConfigDir();
            if (base.startsWith("CLASSPATH/")) base = base.substring("CLASSPATH/".length());
            root = base + root;
        }
        return root;
    }

    private File storageDir(String userId) {
        File dir = new File(new File(resolveStorageRoot(), userId), "scheduled_tasks");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 扫描所有用户目录：<storageRoot>/* /scheduled_tasks/<ownerAgent>.json */
    private List<File> allTaskFiles() {
        List<File> files = new ArrayList<>();
        try {
            File rootDir = new File(resolveStorageRoot());
            File[] users = rootDir.listFiles(File::isDirectory);
            if (users == null) return files;
            for (File u : users) {
                File f = new File(new File(u, "scheduled_tasks"), ownerAgent + ".json");
                if (f.exists()) files.add(f);
            }
        } catch (Exception e) {
            putLog("扫描任务持久化目录失败: " + e, LogLevel.WARN);
        }
        return files;
    }

    private List<TaskRecord> readFile(File f) {
        try {
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            List<TaskRecord> list = gson.fromJson(json, new TypeToken<List<TaskRecord>>() {}.getType());
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            putLog("读取任务持久化失败(" + f.getName() + "): " + e, LogLevel.WARN);
            return new ArrayList<>();
        }
    }

    private synchronized void updateRecordAfterRun(String fullId) {
        TaskRecord rec = loadRecords().get(fullId);
        // 竞态防护：从读盘到写回之间任务可能被删除——删掉的记录不得被执行回写复活
        if (rec == null || !recordsOf(rec.userId).containsKey(fullId)) return;
        rec.executedCount++;
        rec.lastExecuteTime = System.currentTimeMillis();
        String timesText = longText(rec.schedule.get("times"));
        if (timesText != null && !"0".equals(timesText)) {
            try {
                // 一次性/有限次任务执行完保留记录、置停用（可 update 重新启用）
                if (rec.executedCount >= Long.parseLong(timesText)) rec.enabled = false;
            } catch (NumberFormatException e) {
                putLog("times 参数非法（按无限次处理）: " + timesText, LogLevel.WARN);
            }
        }
        if (rec.userId != null) {
            // recordsOf 会重新读盘返回新对象——必须把改过的 rec 放回要保存的 map，否则更新被静默丢弃
            Map<String, TaskRecord> m = recordsOf(rec.userId);
            m.put(fullId, rec);
            saveRecords(rec.userId, m);
        }
    }

    /** 旧记录无 kind → schedule（兼容） */
    private String recKind(TaskRecord rec) {
        return rec.kind == null || rec.kind.isEmpty() ? "schedule" : rec.kind;
    }

    /** 单条记录回写（读全表 → 放回 → 原子写；任务被删则静默不复活） */
    private synchronized void persistRec(TaskRecord rec) {
        if (rec == null || rec.userId == null || rec.taskId == null) return;
        Map<String, TaskRecord> m = recordsOf(rec.userId);
        String fullId = rec.owner + "/" + rec.taskId;
        if (!m.containsKey(fullId)) return;
        m.put(fullId, rec);
        saveRecords(rec.userId, m);
    }

    /** 启动恢复：agent 启动时把本 agent 名下 enabled 任务重新注册回引擎 */
    @Override
    public void runStartMsg() {
        super.runStartMsg();
        if (restored) return;
        restored = true;
        try {
            Map<String, TaskRecord> map = loadRecords();
            // 重启扫描：上次进程退出时在跑的 background 任务标记 interrupted 并通知
            //（background 是一次性立即执行，重启后不可能续跑；状态必须如实收口）
            for (TaskRecord rec : map.values()) {
                if (!"background".equals(recKind(rec))) continue;
                if (!"running".equals(rec.status)) continue;
                rec.status = "interrupted";
                rec.finishedAt = System.currentTimeMillis();
                rec.errorText = "应用重启，任务中断";
                persistRec(rec);
                publishTaskEvent("taskResult", rec, null, null);
                deliverTaskResult(rec);
            }
            int ok = 0;
            for (TaskRecord rec : map.values()) {
                if (rec.owner == null || !rec.owner.equals(ownerAgent)) continue;
                if (!rec.enabled) continue;
                if ("background".equals(recKind(rec))) continue;   // 后台任务是一次性立即执行，绝不注册调度
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

    /**
     * Gson 读回的数字统一是 Double（5 → 5.0），转成整洁的 long 文本（"5" 而非 "5.0"）。
     * 用于下发引擎参数与恢复时的数值判断。
     */
    private String longText(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return String.valueOf(((Number) v).longValue());
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return String.valueOf((long) Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return s;
        }
    }

    private String fmtTime(Object v) {
        if (v == null) return null;
        try {
            long ms;
            if (v instanceof Date) ms = ((Date) v).getTime();
            else if (v instanceof Number) ms = ((Number) v).longValue();
            else ms = Long.parseLong(String.valueOf(v));
            // SimpleDateFormat 非线程安全（定时任务回调在异步线程），每次新建
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ms));
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    private TLMsg ok(String text) { return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLOUTPUT, text); }

    private TLMsg fail(String text) { return createMsg().setParam(RESULT, false).setParam(AI_P_SKILLOUTPUT, "Error: " + text); }
}
