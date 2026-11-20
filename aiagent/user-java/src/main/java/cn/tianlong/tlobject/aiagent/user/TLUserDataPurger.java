package cn.tianlong.tlobject.aiagent.user;

import cn.tianlong.tlobject.aiagent.IUserDataPurgeable;
import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.aiagent.TLUserWorkspace;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 彻底删除用户数据的编排器。
 *
 * <p>顺序固定：预检 → 关入口 → 停任务 → 撤审批 → 停运行态 → 清记忆/Token →
 * 删会话 → 清消息箱/WebUI 内存 → 删文件 → <b>最后</b>删 ai_users。
 * 任一步失败即停止、保留用户行（已删数据可重试，步骤幂等）；成功后解除 purging 门禁。</p>
 *
 * <p>任务/运行态/记忆的具体清理由 {@link IUserDataPurgeable} 实现方承担，经控制中心注册表发现
 * （Agent / schedule_task 技能 / 记忆存储均以家族名自注册）。</p>
 */
public class TLUserDataPurger extends TLBaseModule implements TLAiAgentParamString {

    private String userModule = "dbUserModule";
    private String sessionManager = "dbSessionManager";
    private String approvalModule = "approvalGate";
    private String agentMonitor = "agentMonitor";
    private String webui = "webui";
    private String controlCenterName;
    private String dataRoot = "./data";
    private String documentsRoot = "";
    private long purgeWaitMs = 5000L;

    /** 单用户互斥：并发请求返回 purge_in_progress */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public TLUserDataPurger() { super(); }
    public TLUserDataPurger(String name) { super(name); }
    public TLUserDataPurger(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params == null) return;
        userModule = getParamNonEmpty("userModule", userModule);
        sessionManager = getParamNonEmpty("sessionManager", sessionManager);
        approvalModule = getParamNonEmpty("approvalModule", approvalModule);
        agentMonitor = getParamNonEmpty("agentMonitor", agentMonitor);
        webui = getParamNonEmpty("webui", webui);
        controlCenterName = getParamNonEmpty(CONTROLCENTER_NAME);
        dataRoot = getParamNonEmpty("dataRoot", dataRoot);
        documentsRoot = getParamNonEmpty("documentsRoot", documentsRoot);
        String wait = getParamNonEmpty("purgeWaitMs");
        if (wait != null) {
            try { purgeWaitMs = Long.parseLong(wait); } catch (NumberFormatException ignored) { }
        }
        if (purgeWaitMs < 0) purgeWaitMs = 0;
    }

    @Override
    protected TLBaseModule init() { return this; }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        if ("purgeUser".equals(msg.getAction())) return purgeUser(msg);
        return null;
    }

    // ======================== 编排 ========================

    @SuppressWarnings("unchecked")
    private TLMsg purgeUser(TLMsg msg) {
        String operator = msg.getStringParam("operatorId", "");
        String userId = msg.getStringParam("userId", "").trim();
        String confirm = msg.getStringParam("confirmUserId", "");
        if (userId.isEmpty()) return err("invalid_input", "缺少 userId");
        if (!userId.equals(confirm)) return err("confirm_mismatch", "确认用户名不匹配");
        if (TLUserDataFilePurger.isReservedUserName(userId))
            return err("ambiguous_user_id", "用户名与共享数据目录同名，无法自动彻底删除");

        TLMsg pre = call(userModule, "purgeCheck", map("operatorId", operator,
                "userId", userId, "confirmUserId", confirm));
        if (pre == null) return err("user_module_unavailable", "用户模块未启用");
        if (!pre.parseBoolean("success", false)) return copyError(pre);

        if (!inFlight.add(userId)) return err("purge_in_progress", "该用户正在被删除");
        Map<String, Object> counts = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        try {
            TLMsg wb = webuiLocal("begin", userId, null);
            if (wb == null || !wb.parseBoolean(RESULT, false)) {
                failures.add("webui_begin_failed");
                return partial(operator, userId, counts, warnings, failures, "webui_begin_failed");
            }

            List<String> sids = listSessions(userId);
            if (sids == null) {
                failures.add("session_list_failed");
                return partial(operator, userId, counts, warnings, failures, "session_list_failed");
            }

            // 1) 定时/后台任务（含 execSessions 并入后续清理）
            PhaseResult tasks = registryPhase("tasks", userId, sids);
            mergePhase(counts, "tasks", tasks.counts);
            sids = union(sids, tasks.execSessions);
            if (!tasks.ok) {
                failures.add("tasks:" + tasks.failure);
                return partial(operator, userId, counts, warnings, failures, "tasks_failed");
            }

            // 2) 审批：放弃 pending + 唤醒等待线程
            TLMsg ap = call(approvalModule, "approvalPurgeUser",
                    map("userId", userId, "sessionIds", sids));
            if (ap == null) warnings.add("approval_module_unavailable");
            else if (!ap.parseBoolean(RESULT, false)) {
                failures.add("approval_purge_failed");
                return partial(operator, userId, counts, warnings, failures, "approval_failed");
            } else {
                counts.put("approvals", countsOf(ap, "abandoned", "grantsRevoked",
                        "taskSessionsRemoved", "rejectionEntriesRemoved"));
            }

            // 3) 停 Agent 运行态（先按根会话 stopByRoot，再逐实例等 runCount 归零）
            for (String sid : new ArrayList<>(sids)) {
                call(agentMonitor, "stopByRoot", map("rootSessionId", sid));
            }
            PhaseResult runtime = registryPhase("runtime", userId, sids);
            mergePhase(counts, "runtime", runtime.counts);
            if (!runtime.ok) {
                failures.add("runtime:" + runtime.failure);
                return partial(operator, userId, counts, warnings, failures, "runtime_failed");
            }

            List<String> again = listSessions(userId);
            if (again != null) sids = union(sids, again);

            // 4) 记忆（DB 前缀 + 文件版 cache/pending/摘要）
            PhaseResult memory = registryPhase("memory", userId, sids);
            mergePhase(counts, "memory", memory.counts);
            if (!memory.ok) {
                failures.add("memory:" + memory.failure);
                return partial(operator, userId, counts, warnings, failures, "memory_failed");
            }

            // 5) 监控/Token 统计（必须在运行结束后：防 unregister 重插）
            TLMsg mon = call(agentMonitor, "purgeUser",
                    map("userId", userId, "sessionIds", sids, "waitMs", purgeWaitMs));
            if (mon == null) warnings.add("agent_monitor_unavailable");
            else if (!mon.parseBoolean(RESULT, false)) {
                failures.add("monitor_purge_failed");
                return partial(operator, userId, counts, warnings, failures, "monitor_failed");
            } else {
                counts.put("tokenStats", countsOf(mon, "rowsDeleted", "memoryEntriesRemoved"));
            }

            // 6) 会话记录（含 task_*）
            TLMsg sp = call(sessionManager, "purgeUserSessions",
                    map("userId", userId, "sessionIds", sids));
            if (sp == null || !sp.parseBoolean(RESULT, false)) {
                failures.add("session_purge_failed");
                return partial(operator, userId, counts, warnings, failures, "session_failed");
            }
            counts.put("sessions", countsOf(sp, "sessionsDeleted", "roundsDeleted"));

            // 7) 消息箱 + WebUI 内存表
            TLMsg wd = webuiLocal("data", userId, sids);
            if (wd == null || !wd.parseBoolean(RESULT, false)) {
                failures.add("webui_data_failed");
                return partial(operator, userId, counts, warnings, failures, "webui_data_failed");
            }
            counts.put("inbox", countsOf(wd, "inboxRows"));

            // 8) 文件（workspace / data / documents）
            TLUserDataFilePurger.Result fr = TLUserDataFilePurger.purgeUser(userId,
                    TLUserWorkspace.baseRoot(),
                    Paths.get(dataRoot),
                    documentsRoot.isEmpty() ? null : Paths.get(documentsRoot));
            warnings.addAll(fr.warnings);
            if (!fr.ok) {
                failures.addAll(fr.errors);
                return partial(operator, userId, counts, warnings, failures, "files_failed");
            }
            counts.put("files", map("filesDeleted", fr.filesDeleted,
                    "dirsDeleted", fr.dirsDeleted, "bytesDeleted", fr.bytesDeleted));

            // 9) 最后删用户行（重校验；并发已删按已删处理）
            TLMsg del = call(userModule, "purgeDeleteRecord", map("operatorId", operator,
                    "userId", userId, "confirmUserId", confirm));
            if (del == null) {
                failures.add("user_delete_no_response");
                return partial(operator, userId, counts, warnings, failures, "user_delete_failed");
            }
            if (!del.parseBoolean("success", false)) {
                if ("user_not_found".equals(del.getStringParam("errorCode", ""))) {
                    warnings.add("user_already_deleted");
                } else {
                    failures.add("user_delete_failed");
                    return partial(operator, userId, counts, warnings, failures, "user_delete_failed");
                }
            } else {
                counts.put("user", map("recordDeleted", 1));
            }

            webuiLocal("end", userId, null);
            audit(operator, userId, "ok", counts);
            TLMsg ok = createMsg().setParam("success", true).setParam("partial", false)
                    .setParam("userId", userId).setParam("counts", counts)
                    .setParam("warnings", warnings).setParam("failures", failures);
            return ok;
        } catch (Exception e) {
            failures.add("exception:" + e);
            return partial(operator, userId, counts, warnings, failures, "purge_exception");
        } finally {
            inFlight.remove(userId);
            // 失败时刻意不解除 webui purging 门禁：目标在重试成功或重启前不能进入半删状态
        }
    }

    // ======================== 注册表发现 ========================

    /** 供自测覆盖：返回注册表中的可清理实例。 */
    @SuppressWarnings("unchecked")
    protected List<Object> registryInstances() {
        List<Object> out = new ArrayList<>();
        if (controlCenterName == null || controlCenterName.isEmpty()) return out;
        TLMsg r = putMsg(controlCenterName, createMsg().setAction(REGISTRY_LIST));
        if (r == null) return out;
        Object list = r.getParam(RESULT);
        if (!(list instanceof List)) return out;
        for (Object o : (List<?>) list) {
            if (!(o instanceof Map)) continue;
            Object inst = ((Map<?, ?>) o).get(INSTANCE);
            if (inst instanceof IUserDataPurgeable) out.add(inst);
        }
        return out;
    }

    private static final class PhaseResult {
        boolean ok = true;
        String failure = "";
        final Map<String, Object> counts = new LinkedHashMap<>();
        final List<String> execSessions = new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private PhaseResult registryPhase(String phase, String userId, List<String> sessionIds) {
        PhaseResult pr = new PhaseResult();
        List<Object> instances = registryInstances();
        if (instances.isEmpty()) {
            pr.ok = false;
            pr.failure = "registry_unavailable";
            return pr;
        }
        for (Object inst : instances) {
            TLMsg req = createMsg().setAction("purgeUserData")
                    .setParam("phase", phase).setParam("userId", userId)
                    .setParam("sessionIds", sessionIds).setParam("waitMs", purgeWaitMs);
            TLMsg r;
            try {
                r = ((IUserDataPurgeable) inst).purgeUserData(req);
            } catch (Exception e) {
                pr.ok = false;
                pr.failure = "phase_exception:" + phase + ":" + e;
                continue;
            }
            if (r == null) {
                pr.ok = false;
                pr.failure = "phase_null:" + phase;
                continue;
            }
            if (!r.parseBoolean(RESULT, false)) {
                pr.ok = false;
                pr.failure = r.getStringParam("error", "phase_failed:" + phase);
                continue;
            }
            if (r.parseBoolean("skipped", false)) continue;
            if ("tasks".equals(phase)) {
                mergeInts(pr.counts, r, "stopped", "unregistered", "recordsRemoved");
                pr.execSessions.addAll(r.getListParam("execSessions", new ArrayList<>()));
            } else if ("runtime".equals(phase)) {
                mergeInts(pr.counts, r, "sessionsStopped", "stateCleared");
            } else if ("memory".equals(phase)) {
                mergeInts(pr.counts, r, "dbEntries", "cacheCleared", "fileRemoved", "pendingRemoved");
            }
        }
        return pr;
    }

    // ======================== 小工具 ========================

    private TLMsg call(String module, String action, Map<String, Object> params) {
        if (module == null || module.isEmpty()) return null;
        TLMsg m = createMsg().setAction(action).setSystemParam(IGNOREMODULEISNULL, true);
        if (params != null) for (Map.Entry<String, Object> e : params.entrySet()) m.setParam(e.getKey(), e.getValue());
        try { return putMsg(module, m); } catch (Exception e) { return null; }
    }

    private TLMsg webuiLocal(String phase, String userId, List<String> sessionIds) {
        if (webui == null || webui.isEmpty()) return null;
        TLMsg m = createMsg().setAction("purgeUserLocal")
                .setParam("phase", phase).setParam("userId", userId)
                .setSystemParam("_internalPurge", true);
        if (sessionIds != null) m.setParam("sessionIds", sessionIds);
        try { return putMsg(webui, m); } catch (Exception e) { return null; }
    }

    private List<String> listSessions(String userId) {
        TLMsg r = call(sessionManager, "listUserSessionIds", map("userId", userId));
        if (r == null || !r.parseBoolean(RESULT, false)) return null;
        return r.getListParam("sessionIds", new ArrayList<>());
    }

    private static List<String> union(List<String> a, List<String> b) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (a != null) for (String s : a) if (s != null && !s.isEmpty()) out.add(s);
        if (b != null) for (String s : b) if (s != null && !s.isEmpty()) out.add(s);
        return new ArrayList<>(out);
    }

    private static Map<String, Object> map(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static Map<String, Object> countsOf(TLMsg r, String... keys) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (String k : keys) if (r.containsParam(k)) m.put(k, r.getParam(k));
        return m;
    }

    private static void mergeInts(Map<String, Object> dst, TLMsg r, String... keys) {
        for (String k : keys) {
            if (!r.containsParam(k)) continue;
            long v = r.getLongParam(k, 0L);
            Object old = dst.get(k);
            long base = old instanceof Number ? ((Number) old).longValue() : 0L;
            dst.put(k, base + v);
        }
    }

    @SuppressWarnings("unchecked")
    private static void mergePhase(Map<String, Object> counts, String phase, Map<String, Object> src) {
        if (src == null || src.isEmpty()) {
            counts.put(phase, new LinkedHashMap<String, Object>());
            return;
        }
        counts.put(phase, src);
    }

    private TLMsg partial(String operator, String userId, Map<String, Object> counts,
                          List<String> warnings, List<String> failures, String errorCode) {
        String joined = failures.isEmpty() ? errorCode : String.join("; ", failures);
        audit(operator, userId, "partial:" + errorCode, counts);
        return createMsg().setParam("success", false).setParam("partial", true)
                .setParam("userId", userId).setParam("errorCode", errorCode)
                .setParam("error", "彻底删除未完成：" + joined)
                .setParam("counts", counts).setParam("warnings", warnings)
                .setParam("failures", failures);
    }

    private TLMsg err(String code, String message) {
        return createMsg().setParam("success", false).setParam("errorCode", code)
                .setParam("error", message);
    }

    private TLMsg copyError(TLMsg r) {
        return createMsg().setParam("success", false)
                .setParam("errorCode", r.getStringParam("errorCode", "failed"))
                .setParam("error", r.getStringParam("error", "操作失败"));
    }

    private void audit(String operator, String userId, String result, Map<String, Object> counts) {
        putLog("admin op=purgeUser operator=" + operator + " target=" + userId
                + " result=" + result + " counts=" + counts, LogLevel.INFO, "adminAudit");
    }
}
