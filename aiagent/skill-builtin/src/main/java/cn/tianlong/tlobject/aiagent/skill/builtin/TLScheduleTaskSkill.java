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
 *   到点由 agent 薄转发给 toolManager，再按函数名定位本技能落到 runScheduledTask()，
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

    /**
     * 动作分发：技能标准动作（execute/validate/getInfo）之外，额外接收引擎回调动作
     * runScheduledTask（经 agent→toolManager 薄转发到达；框架不做方法名反射，必须显式分发）。
     */
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        if ("runScheduledTask".equals(msg.getAction()))
            return runScheduledTask(fromWho, msg);
        return super.checkMsgAction(fromWho, msg);
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
     * 定时任务执行入口（引擎回调 action=runScheduledTask，经 agent→toolManager 薄转发到达）。
     * 此刻解析目标会话（当前活动会话，失败回退创建时会话），再转发给 agent 的 chat。
     */
    protected TLMsg runScheduledTask(Object fromWho, TLMsg msg) {
        String prompt = msg.getStringParam("taskPrompt", "");
        String recSession = msg.getStringParam("recordSession", null);
        String taskId = msg.getStringParam("taskId", "?");
        String sessionId = msg.getStringParam("sessionId", null);
        // 权威身份：优先从持久化记录取（重启后 msg/sessionUsers 都不可靠；
        // taskId 即 fullId，记录里有创建时的 userId）
        String userId = null;
        TaskRecord rec = loadRecords().get(taskId);
        if (rec != null && rec.userId != null && !rec.userId.isEmpty()) userId = rec.userId;
        if (userId == null || userId.isEmpty())
            userId = recSession != null ? sessionUsers.getOrDefault(recSession, recSession) : "default";
        if (sessionId == null || sessionId.isEmpty())
            sessionId = recSession != null ? recSession : "default";

        // 执行时解析目标会话：用户当前活动会话可用则用，否则回退创建时会话（保证必达不丢）
        String target = resolveTargetSession(userId);
        if (target == null || target.isEmpty()) target = sessionId;

        TLMsg chat = createMsg()
                .setAction("chat")                       // 缺 action 会走 defaultAction 返回 null，任务永远不产生回复
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
        if (recordsOf(userId).containsKey(fullId))
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

        Map<String, TaskRecord> map = recordsOf(userId);
        map.put(fullId, rec);
        String warn = saveRecords(userId, map);
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
            // destination=agent、action=runScheduledTask：
            // agent 转发给 toolManager → 按 skillFunctionName 定位技能模块 → 技能 runScheduledTask
            taskMsg = createMsg()
                    .setDestination(rec.owner)
                    .setAction("runScheduledTask")
                    .setParam(AI_P_TOOLNAME, "schedule_task")
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

        // 注册参数必须挂在内层任务消息上（引擎 doRegistTask/startTask 只读内层；
        // 外层仅承载 action=registTask + msg）
        taskMsg.setParam("taskid", fullId);
        taskMsg.setParam("status", "run");
        Object cron = rec.schedule.get("cron");
        if (cron != null) taskMsg.setParam("cronExp", String.valueOf(cron));
        Object delay = rec.schedule.get("delay");
        // 注意：delay=0 必须显式下发（引擎缺省是 60 秒；one_shot 的"立即"语义依赖 0）
        if (delay != null) taskMsg.setParam("delay", String.valueOf(delay));
        Object unit = rec.schedule.get("unit");
        if (unit != null) taskMsg.setParam("timeUnit", String.valueOf(unit));
        // 循环间隔是引擎的 period（缺省 60 秒，不是 delay）——固定间隔任务必须显式下发，
        // 否则"每 5 秒"实际变成每 60 秒（cron 任务不需要 period，留空）
        if (cron == null && delay != null)
            taskMsg.setParam("period", String.valueOf(delay));
        Object times = rec.schedule.get("times");
        if (times != null && !"0".equals(String.valueOf(times))) taskMsg.setParam("times", String.valueOf(times));
        Object begin = rec.schedule.get("begin");
        if (begin != null) taskMsg.setParam("begin", String.valueOf(begin));

        return createMsg().setAction("registTask").setParam("msg", taskMsg);
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

    /** 用户任务文件：<storageRoot>/<userId>/scheduled_tasks/<ownerAgent>.json */
    private File storageFile(String userId) {
        return new File(storageDir(userId), ownerAgent + ".json");
    }

    /** 全部用户任务文件的合并表（恢复/执行期用 fullId 直查）；saveRecords(userId,...) 写单个用户 */
    @SuppressWarnings({"unchecked", "rawtypes"})
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
        try {
            File f = storageFile(userId);
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

    /** 该用户在内存里应持有的记录（按 userId 过滤全量表） */
    private Map<String, TaskRecord> recordsOf(String userId) {
        Map<String, TaskRecord> out = new LinkedHashMap<>();
        for (Map.Entry<String, TaskRecord> e : loadRecords().entrySet()) {
            TaskRecord r = e.getValue();
            if (r.userId != null && r.userId.equals(userId)) out.put(e.getKey(), r);
        }
        return out;
    }

    private File storageDir(String userId) {
        String root = storageRoot;
        if (root == null || root.isEmpty()) root = "./data/";
        if (!new File(root).isAbsolute() && moduleFactory != null && moduleFactory.getConfigDir() != null) {
            String base = moduleFactory.getConfigDir();
            if (base.startsWith("CLASSPATH/")) base = base.substring("CLASSPATH/".length());
            root = base + root;
        }
        File dir = new File(new File(root, userId), "scheduled_tasks");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 扫描所有用户目录：<storageRoot>/* /scheduled_tasks/<ownerAgent>.json */
    private List<File> allTaskFiles() {
        List<File> files = new ArrayList<>();
        try {
            String root = storageRoot;
            if (root == null || root.isEmpty()) root = "./data/";
            if (!new File(root).isAbsolute() && moduleFactory != null && moduleFactory.getConfigDir() != null) {
                String base = moduleFactory.getConfigDir();
                if (base.startsWith("CLASSPATH/")) base = base.substring("CLASSPATH/".length());
                root = base + root;
            }
            File rootDir = new File(root);
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

    private void updateRecordAfterRun(String fullId, String targetSession) {
        TaskRecord rec = loadRecords().get(fullId);
        if (rec == null) return;
        rec.executedCount++;
        rec.lastExecuteTime = System.currentTimeMillis();
        if (rec.schedule.get("times") != null && !"0".equals(String.valueOf(rec.schedule.get("times")))
                && rec.executedCount >= Long.parseLong(String.valueOf(rec.schedule.get("times")))) {
            rec.enabled = false;   // 一次性/有限次任务执行完保留记录、置停用（可 update 重新启用）
        }
        if (rec.userId != null) saveRecords(rec.userId, recordsOf(rec.userId));
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
