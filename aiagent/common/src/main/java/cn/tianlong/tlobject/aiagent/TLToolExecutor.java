package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLMsgUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 工具执行模块——接收解析好的任务清单，并行执行并返回结果。
 *
 * 职责边界：
 * - Agent 负责：查 functions 表 → 解析 ToolCall → 构建 ToolTask（{module, action, args}）
 * - ToolExecutor 负责：并行 fire → 审批门禁 → latch 等待 → 返回 ToolResult[]
 * - ToolExecutor 不持有 functions 表，不关心 tool 是 skill 还是 agent
 *
 * 线程安全：
 * - 所有 Agent 共用一个实例，通过 executionId 隔离不同调用
 * - 嵌套执行（master → sub-agent → ToolExecutor）各自独立 executionId，互不覆盖
 * - TODOOLECANCEL 按 sessionId 批量匹配，遍历 states 取消所有匹配的执行
 *
 * 创建日期：2026/8/8
 * 作者:tianlong
 */
public class TLToolExecutor extends TLBaseModule implements TLAiAgentParamString {

    /** 单次工具执行超时（毫秒），0 = 一直等待。可通过 XML params 的 executionTimeoutMs 配置 */
    private long executionTimeoutMs = 0;
    /** 残留状态清理阈值（毫秒），0 = 禁用。超过此时间的 ExecutionState 视为孤儿。可通过 XML params 的 staleStateTimeoutMs 配置 */
    private long staleStateTimeoutMs = 0;

    // ======================== 数据结构 ========================

    /** Agent 解析好的单条执行任务 */
    public static class ToolTask {
        public String toolCallId;           // LLM 的 tool_call id
        public String moduleName;           // 目标模块名
        public TLBaseModule module;         // 目标模块引用（Agent 已解析，null = 内建/错误）
        public String action;               // SKILL_EXECUTE / MCP_CALLTOOL / 自定义
        public Map<String, Object> args;    // LLM 传入的参数
        public String userId;               // 用户隔离
        public String nativeName;           // MCP 原生工具名（非 MCP 时为 null，与 LLM 函数名可能不同）
        /** 单次执行超时（毫秒），0 = 不限时。通过 ThreadTask 的 taskTimeout 系统参数传递给执行线程 */
        public long timeoutMs = 0;
        /** 预设输出（非 null 时 ToolExecutor 直接返回，不执行。用于 error/clarification 等已处理的 tool） */
        public String precomputedOutput;

        public ToolTask() {}
        public ToolTask(String toolCallId, TLBaseModule module, String action,
                        Map<String, Object> args, String userId) {
            this.toolCallId = toolCallId;
            this.module = module;
            this.moduleName = module != null ? module.getName() : "";
            this.action = action;
            this.args = args;
            this.userId = userId;
        }
    }

    /** 单条工具执行结果 */
    public static class ToolResult {
        public String toolCallId;
        public String output;               // AI_P_SKILLOUTPUT / AI_P_RESPONSE
        public String state;                // null=正常, "pending", "rejected", "clarified"
        public String stateExtra;           // rejectReason / clarificationQuestion
        public String approvalId;           // 审批请求 ID（pending 状态）

        public ToolResult() {}
        public ToolResult(String toolCallId, String output) {
            this.toolCallId = toolCallId;
            this.output = output;
        }
        public ToolResult(String toolCallId, String output, String state, String stateExtra) {
            this.toolCallId = toolCallId;
            this.output = output;
            this.state = state;
            this.stateExtra = stateExtra;
        }
    }

    // ======================== 运行时状态 ========================

    /** 单次执行的上下文（key = executionId） */
    private static class ExecutionState {
        final String executionId;
        final String sessionId;
        final CountDownLatch latch;
        final Map<Integer, ToolResult> results = new ConcurrentHashMap<>();
        volatile boolean aborted;
        final List<ThreadTask> activeTasks = new ArrayList<>();
        volatile long createdAt;

        ExecutionState(String executionId, String sessionId, CountDownLatch latch) {
            this.executionId = executionId;
            this.sessionId = sessionId;
            this.latch = latch;
            this.createdAt = System.currentTimeMillis();
        }
    }

    /** executionId → 执行上下文 */
    private final Map<String, ExecutionState> states = new ConcurrentHashMap<>();

    // ======================== 审批 ========================

    /** 审批模块引用（null = 未启用审批） */
    private volatile IObject approvalModule;
    /** 审批模块名（从 XML params 读取） */
    private String approvalModuleName;

    // ======================== 构造函数 ========================

    public TLToolExecutor() { super(); }
    public TLToolExecutor(String name) { super(name); }
    public TLToolExecutor(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("approvalModule") != null)
                approvalModuleName = params.get("approvalModule");
            if (params.get("executionTimeoutMs") != null)
                executionTimeoutMs = Long.parseLong(params.get("executionTimeoutMs"));
            if (params.get("staleStateTimeoutMs") != null)
                staleStateTimeoutMs = Long.parseLong(params.get("staleStateTimeoutMs"));
        }
    }

    @Override
    protected TLBaseModule init() { return this; }

    /** 设置审批模块名（由 TLAiAgent 在创建后注入） */
    public void setApprovalModule(String name) {
        this.approvalModuleName = name;
    }

    // ======================== 消息分发 ========================

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case TODOOLEXECUTE:
                return executeTools(fromWho, msg);
            case TODOOLECANCEL:
                return cancelSession(fromWho, msg);
            case "_toolExecInternal":
                return doToolExec(fromWho, msg);
            default:
                return null;
        }
    }

    // ======================== 核心：批量执行 ========================

    /**
     * 批量执行工具调用。同步阻塞，全部完成后返回。
     */
    @SuppressWarnings("unchecked")
    private TLMsg executeTools(Object fromWho, TLMsg msg) {
        List<ToolTask> tasks = (List<ToolTask>) msg.getListParam("tasks", null);
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        String userId = msg.getStringParam("userId", "default");
        String executionId = msg.getStringParam("executionId", sessionId + "_" + System.nanoTime());
        String rootSessionId = msg.getStringParam("rootSessionId", sessionId);

        if (tasks == null || tasks.isEmpty()) {
            return createMsg().setParam(RESULT, true)
                    .setParam("results", Collections.emptyList());
        }

        // 防御性清理：扫描并移除残留的孤儿状态
        cleanupStaleStates();

        int n = tasks.size();
        CountDownLatch latch = new CountDownLatch(n);
        ExecutionState state = new ExecutionState(executionId, sessionId, latch);
        states.put(executionId, state);

        try {
            // 并行 fire ThreadTask
            for (int i = 0; i < n; i++) {
                final int idx = i;
                final ToolTask task = tasks.get(i);
                task.userId = userId;
                TLMsg execMsg = createMsg().setAction("_toolExecInternal")
                        .setParam("_task", task).setParam("_idx", idx)
                        .setParam("_fromWho", fromWho)
                        .setParam("executionId", executionId)
                        .setParam(AI_P_SESSIONID, sessionId)
                        .setParam("userId", userId)
                        .setParam("rootSessionId", rootSessionId);
                // 每个 task 可单独设超时，传给 ThreadTask
                if (task.timeoutMs > 0)
                    execMsg.setSystemParam(TASKTIMEOUT, task.timeoutMs);
                TLMsg taskResult = putMsgNoWait(this, execMsg);
                ThreadTask tt = (ThreadTask) taskResult.getParam(THREADPOOL_TASK);
                if (tt != null) state.activeTasks.add(tt);
            }

            // 等待全部完成（executionTimeoutMs=0 则一直等待，>0 超时后中断剩余任务）
            if (executionTimeoutMs > 0) {
                boolean completed = latch.await(executionTimeoutMs, TimeUnit.MILLISECONDS);
                if (!completed) {
                    putLog("Tool execution timeout after " + (executionTimeoutMs / 1000)
                            + "s, executionId=" + executionId + ", aborting " + latch.getCount()
                            + " remaining task(s)", LogLevel.WARN);
                    state.aborted = true;
                    while (state.latch.getCount() > 0) state.latch.countDown();
                    for (ThreadTask t : state.activeTasks) {
                        t.cancelTask();
                        t.interrupt();
                    }
                }
            } else {
                latch.await();
            }

            // 收集结果
            TLMsg response = createMsg().setParam(RESULT, true);
            List<ToolResult> results = new ArrayList<>();
            boolean hasPending = false, hasRejected = false, hasClarified = false, hasTimeout = false;
            String finalResponse = null, rejectReason = null, clarificationQuestion = null;
            String pendingApprovalId = null;

            for (int i = 0; i < n; i++) {
                ToolResult tr = state.results.get(i);
                if (tr == null) continue;
                if (tr.state == null) {
                    results.add(tr);
                } else if ("pending".equals(tr.state)) {
                    hasPending = true;
                    finalResponse = tr.output;
                    pendingApprovalId = tr.approvalId;
                } else if ("rejected".equals(tr.state)) {
                    hasRejected = true;
                    finalResponse = tr.output;
                    rejectReason = tr.stateExtra;
                } else if ("clarified".equals(tr.state)) {
                    hasClarified = true;
                    finalResponse = tr.output;
                    clarificationQuestion = tr.stateExtra;
                } else if ("timeout".equals(tr.state)) {
                    hasTimeout = true;
                    finalResponse = tr.output;
                }
            }

            response.setParam("results", results);
            response.setParam("aborted", state.aborted);
            response.setParam("pendingApproval", hasPending);
            response.setParam("rejected", hasRejected);
            response.setParam("clarified", hasClarified);
            response.setParam("hasTimeout", hasTimeout);
            if (finalResponse != null) response.setParam("finalResponse", finalResponse);
            if (rejectReason != null) response.setParam("rejectReason", rejectReason);
            if (clarificationQuestion != null) response.setParam("clarificationQuestion", clarificationQuestion);
            if (pendingApprovalId != null) response.setParam(AI_P_APPROVAL_ID, pendingApprovalId);

            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 确保 latch 被释放，防止活跃任务永远等待
            state.aborted = true;
            while (state.latch.getCount() > 0) state.latch.countDown();
            for (ThreadTask t : state.activeTasks) { t.cancelTask(); t.interrupt(); }
            return createMsg().setParam(RESULT, false).setParam("aborted", true)
                    .setParam("error", "Interrupted");
        } finally {
            states.remove(executionId);
        }
    }

    /**
     * 单个工具执行回调（ThreadTask 上运行）。
     */
    private TLMsg doToolExec(Object fromWho, TLMsg msg) {
        ToolTask task = (ToolTask) msg.getParam("_task");
        int idx = msg.getIntParam("_idx", -1);
        String executionId = msg.getStringParam("executionId", "");
        ExecutionState state = states.get(executionId);
        if (state == null || task == null) {
            if (state == null) return null; // 已被 cancel 清理
            return null;
        }

        // 已被取消/超时 → 快速失败，不执行
        if (state.aborted) {
            state.latch.countDown();
            return null;
        }

        try {
            // 审批门禁
            TLMsg approvalResult = checkApprovalGate(task.toolCallId, task.args, task.moduleName,
                    msg.getStringParam(AI_P_SESSIONID, "default"),
                    msg.getStringParam("userId", "default"));
            if (approvalResult != null) {
                String approvalState = approvalResult.getStringParam(AI_P_APPROVAL_STATE, "");
                ToolResult tr = new ToolResult(task.toolCallId,
                        approvalResult.getStringParam(AI_P_SKILLOUTPUT, ""));
                tr.state = approvalState;
                if ("rejected".equals(approvalState))
                    tr.stateExtra = approvalResult.getStringParam(AI_P_APPROVAL_REJECTREASON, "用户拒绝");
                if ("pending".equals(approvalState))
                    tr.approvalId = approvalResult.getStringParam(AI_P_APPROVAL_ID, "");
                state.results.put(idx, tr);
                return null;
            }

            // 预计算输出（内建工具、校验失败、函数未找到等）：直接返回，不执行
            if (task.precomputedOutput != null) {
                state.results.put(idx, new ToolResult(task.toolCallId, task.precomputedOutput));
                return null;
            }

            // 无 module 且无预计算输出 → 不应该出现，兜底
            if (task.module == null) {
                state.results.put(idx, new ToolResult(task.toolCallId, "Internal error: no module for " + task.moduleName));
                return null;
            }

            // 执行工具
            String functionName = task.moduleName;
            String toolName = MCP_CALLTOOL.equals(task.action) && task.nativeName != null
                    ? task.nativeName : functionName;
            System.out.println(">>> [Function] " + functionName + " → " + task.module.getName());
            TLMsg execMsg = createMsg();
            execMsg.setAction(task.action);
            execMsg.setParam(AI_P_TOOLNAME, toolName);
            if (MCP_CALLTOOL.equals(task.action)) {
                execMsg.setParam(AI_P_TOOLARGUMENTS, task.args);
            }
            execMsg.setParam(AI_P_SKILLINPUT, task.args).setParam(AI_P_TOOLID, task.toolCallId);
            // 注入 LLM 参数
            for (Map.Entry<String, Object> entry : task.args.entrySet()) {
                execMsg.setParam(entry.getKey(), entry.getValue());
            }
            String toolUserId = msg.getStringParam("userId", null);
            if (toolUserId != null) execMsg.setParam("userId", toolUserId);
            // 会话信息透传（工具模块、before/after 钩子、级联停止可用）
            execMsg.setParam(AI_P_SESSIONID, msg.getStringParam(AI_P_SESSIONID, "default"));
            execMsg.setParam("rootSessionId", msg.getStringParam("rootSessionId",
                    msg.getStringParam(AI_P_SESSIONID, "default")));
            TLMsg result = putMsg(task.module, execMsg);
            // 线程被中断 = Future 超时，即使工具返回了部分结果也标记为超时
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt(); // 恢复中断标志，让上游也能感知
                state.results.put(idx, new ToolResult(task.toolCallId,
                        "[超时] " + task.moduleName + " 执行超时",
                        "timeout", "执行超时"));
                return null;
            }
            // 检查执行结果自身是否标记为超时
            if (result != null && Boolean.TRUE.equals(result.getParam(TASKTIMEOUT))) {
                state.results.put(idx, new ToolResult(task.toolCallId,
                        "[超时] " + task.moduleName + " 执行超时",
                        "timeout", "执行超时"));
                return null;
            }
            String output;
            if (result == null) {
                output = "done";
            } else if (result.containsParam(AI_P_SKILLOUTPUT)) {
                output = result.getStringParam(AI_P_SKILLOUTPUT, "");
            } else {
                output = result.getStringParam(AI_P_RESPONSE, "");
            }
            if (output.isEmpty()) output = result != null ? TLMsgUtils.msgToSimpleStr(result) : "";
            System.out.println("<<< [Function] " + functionName + " 返回 (前200字): "
                    + (output.length() > 200 ? output.substring(0, 200) : output));

            state.results.put(idx, new ToolResult(task.toolCallId, output));
        } catch (Exception e) {
            // 判断是否因超时中断：InterruptedIOException / InterruptedException / 线程中断标志
            boolean isTimeout = Thread.currentThread().isInterrupted()
                    || e instanceof InterruptedException
                    || e.getClass().getName().contains("Interrupted");
            if (isTimeout) {
                Thread.currentThread().interrupt(); // 恢复中断标志
                putLog("Tool execution timeout: " + task.moduleName, LogLevel.WARN);
                state.results.put(idx, new ToolResult(task.toolCallId,
                        "[超时] " + task.moduleName + " 执行超时",
                        "timeout", "执行超时"));
            } else {
                putLog("Tool execution error: " + task.moduleName + " -> " + e.toString(), LogLevel.ERROR);
                state.results.put(idx, new ToolResult(task.toolCallId,
                        "Error executing " + task.moduleName + ": " + e.getMessage()));
            }
        } finally {
            state.latch.countDown();
        }
        return null;
    }

    // ======================== 审批门禁 ========================

    /**
     * 审批门禁：在执行工具前发送审批请求到审批模块。
     * @return null = 放行；非 null = 被拦截（pending/rejected）
     */
    private TLMsg checkApprovalGate(String toolCallId, Map<String, Object> toolArgs,
                                     String toolName, String sessionId, String userId) {
        if (approvalModule == null && approvalModuleName != null && !approvalModuleName.isEmpty()) {
            synchronized (this) {
                if (approvalModule == null) {
                    Object m = moduleFactory != null ? moduleFactory.getModule(approvalModuleName) : null;
                    if (m == null && modules != null) m = modules.get(approvalModuleName);
                    if (m instanceof IObject) {
                        approvalModule = (IObject) m;
                        putLog("Approval module resolved: " + approvalModuleName, LogLevel.INFO);
                    } else {
                        putLog("Approval module not found: " + approvalModuleName + " → approval disabled", LogLevel.WARN);
                        approvalModuleName = null;
                        return null;
                    }
                }
            }
        }
        if (approvalModule == null) return null;

        try {
            TLMsg result = putMsg(approvalModule,
                    createMsg().setAction(APPROVAL_REQUEST)
                            .setParam("toolName", toolName)
                            .setParam("toolArgs", toolArgs != null
                                    ? new LinkedHashMap<>(toolArgs) : new LinkedHashMap<>())
                            .setParam(AI_P_SESSIONID, sessionId)
                            .setParam("userId", userId)
                            .setParam("toolCallId", toolCallId));

            if (result == null) return null;
            String state = result.getStringParam(AI_P_APPROVAL_STATE, "");
            if ("approved".equals(state)) return null;
            return result;
        } catch (Exception e) {
            putLog("checkApprovalGate error: " + e.toString() + " → bypassing approval", LogLevel.ERROR);
            return null;
        }
    }

    // ======================== 残留状态清理 ========================

    /**
     * 防御性清理：扫描并移除超时的孤儿 ExecutionState。
     * 在每次 executeTools 前调用，确保即使 finally 因极端情况未执行也不会永久泄漏。
     */
    private void cleanupStaleStates() {
        if (staleStateTimeoutMs <= 0) return; // 未配置则不启用
        long now = System.currentTimeMillis();
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, ExecutionState> entry : states.entrySet()) {
            ExecutionState s = entry.getValue();
            if (now - s.createdAt > staleStateTimeoutMs) {
                stale.add(entry.getKey());
            }
        }
        for (String id : stale) {
            ExecutionState s = states.remove(id);
            if (s != null) {
                s.aborted = true;
                while (s.latch.getCount() > 0) s.latch.countDown();
                for (ThreadTask t : s.activeTasks) { t.cancelTask(); t.interrupt(); }
                putLog("Cleaned up stale ExecutionState: " + id + " (age="
                        + ((now - s.createdAt) / 1000) + "s)", LogLevel.WARN);
            }
        }
    }

    // ======================== 取消 ========================

    /**
     * 取消指定 session 的所有进行中工具执行。
     */
    private TLMsg cancelSession(Object fromWho, TLMsg msg) {
        String sid = msg.getStringParam(AI_P_SESSIONID, "default");
        int cancelled = 0;
        // 用副本迭代，避免在遍历中 remove 的并发问题
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, ExecutionState> entry : states.entrySet()) {
            ExecutionState state = entry.getValue();
            if (sid.equals(state.sessionId)) {
                state.aborted = true;
                while (state.latch.getCount() > 0) state.latch.countDown();
                for (ThreadTask t : state.activeTasks) { t.cancelTask(); t.interrupt(); }
                toRemove.add(entry.getKey());
                cancelled++;
            }
        }
        for (String id : toRemove) states.remove(id);
        if (cancelled > 0) {
            putLog("cancelSession: cancelled " + cancelled + " execution(s) for sessionId=" + sid,
                    LogLevel.INFO);
        }
        return createMsg().setParam(RESULT, true).setParam("cancelled", cancelled);
    }
}
