package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.aiagent.approval.TLApprovalRequest;
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
 * - 工厂共享单例，所有 Agent 共用（工具执行无状态，任务随消息传入）
 * - executionId 隔离同一实例内的并发/嵌套调用（master → sub-agent → 同一 ToolExecutor），互不覆盖
 * - TODOOLECANCEL 按 sessionId 批量匹配，遍历 states 取消所有匹配的执行
 * - 审批为全局公共门禁：审批模块名来自执行器自身配置，规则按工具名匹配（危险工具只在
 *   子 agent，天然审批下沉）；会话/用户隔离由审批请求消息携带的 sessionId/userId 保证
 *
 * 创建日期：2026/8/8
 * 作者:tianlong
 */
public class TLToolExecutor extends TLBaseModule implements TLAiAgentParamString {

    /** 单次工具执行超时（毫秒），0 = 一直等待。可通过 XML params 的 executionTimeoutMs 配置 */
    private long executionTimeoutMs = 0;
    /**
     * 残留状态清理阈值（毫秒），0 = 禁用。只清理"等待线程已消失"的泄漏状态：超过此时间
     * 且调用 executeTools 的线程已结束（调用方线程被异常终止、finally 未执行等），才在
     * 下次 executeTools 入口扫描时强制终止（abort + latch 排空 + 取消执行任务）。
     * 调用线程仍存活 = 正在合法等待，绝不清理——委派子 agent 的长任务可以远超 10 分钟
     * （2026-10-22：codingAgent 委派 601s 被按年龄误杀，主 agent 误判为"用户中断"）。
     * 单次工具执行时长上限由 executionTimeoutMs 控制（0 = 不限）。可通过 XML params
     * 的 staleStateTimeoutMs 覆盖（0 = 禁用）。
     */
    private long staleStateTimeoutMs = 600000;

    // ======================== 数据结构 ========================

    /** Agent 解析好的单条执行任务 */
    public static class ToolTask {
        public String toolCallId;           // LLM 的 tool_call id
        public String moduleName;           // 目标模块名
        /** LLM 函数名（如 file_operation）。审批规则按此匹配，与模块名（fileOperationSkill）不同 */
        public String functionName;
        public TLBaseModule module;         // 目标模块引用（Agent 已解析，null = 内建/错误）
        public String action;               // SKILL_EXECUTE / MCP_CALLTOOL / 自定义
        public Map<String, Object> args;    // LLM 传入的参数
        public String nativeName;           // MCP 原生工具名（非 MCP 时为 null，与 LLM 函数名可能不同）
        /** 单次执行超时（毫秒），0 = 不限时。通过 ThreadTask 的 taskTimeout 系统参数传递给执行线程 */
        public long timeoutMs = 0;
        /** 预设输出（非 null 时 ToolExecutor 直接返回，不执行。用于 error/clarification 等已处理的 tool） */
        public String precomputedOutput;

        public ToolTask() {}
        /** userId 不经 ToolTask 携带——一批任务同一用户，执行/授权都从 TODOOLEXECUTE 消息读取 */
        public ToolTask(String toolCallId, TLBaseModule module, String action,
                        Map<String, Object> args) {
            this.toolCallId = toolCallId;
            this.module = module;
            this.moduleName = module != null ? module.getName() : "";
            this.action = action;
            this.args = args;
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
        /** 每个任务的**进度终态**（done/skipped/error/...），供 traceStage 使用。
         *  正常结果的 ToolResult.state 为 null（历史上按 "ok" 记），会把"参数校验不过、
         *  根本没执行"的 skipped 任务也显示成 ok，排查时误导。 */
        final Map<Integer, String> finalStatuses = new ConcurrentHashMap<>();
        volatile boolean aborted;
        /** 中断原因（staleStateCleanup / executionTimeout / callerInterrupted / sessionCancel），
         *  随结果带回 Agent——不能只靠布尔 aborted 让上层一律记成"用户中断" */
        volatile String abortReason;
        // add 在 executeTools 的 fire 循环里（worker 线程），遍历在 cancelSession（用户按停止的线程）
        // 与 cleanupStaleStates（别的会话线程）—— 普通 ArrayList 会 CME 或静默丢元素
        final List<ThreadTask> activeTasks = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile long createdAt;
        /** 正在等人工审批的任务数（>0 = 合法长跑，cleanupStaleStates 不得按超龄强杀）。原子计数：
         *  同批次多个任务并发进门禁时，++/-- 丢更新会让本已非零的计数瞬时归零 */
        final java.util.concurrent.atomic.AtomicInteger approvalWaiters = new java.util.concurrent.atomic.AtomicInteger();
        /** 创建本状态、正阻塞在 latch 上的调用线程。存活 = 合法等待（不是孤儿）。 */
        final Thread callerThread;

        // 进度事件上下文（executeTools 构造后填充；不参与并发控制）
        Object fromWho;
        String userId;
        String rootSessionId;
        String roundId;
        int total;

        ExecutionState(String executionId, String sessionId, CountDownLatch latch) {
            this.executionId = executionId;
            this.sessionId = sessionId;
            this.latch = latch;
            this.createdAt = System.currentTimeMillis();
            this.callerThread = Thread.currentThread();
        }
    }

    /** executionId → 执行上下文 */
    private final Map<String, ExecutionState> states = new ConcurrentHashMap<>();

    // ======================== 审批 ========================

    /** 审批模块引用（null = 未启用审批；按名懒解析，approvalGate 为工厂单例故可缓存） */
    private volatile IObject approvalModule;
    /** 审批模块名（共享执行器自身 XML 配置的全局默认，未配置 = 全 app 不启用审批） */
    private String approvalModuleName;

    // ======================== 输出校验 ========================

    /** 输出校验模块名（未配置 = 不校验；配置了才懒解析） */
    private String outputValidatorName;
    private volatile IObject outputValidator;

    // ======================== 构造函数 ========================

    public TLToolExecutor() { super(); }
    public TLToolExecutor(String name) { super(name); }
    public TLToolExecutor(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("approvalModule") != null)
                approvalModuleName = params.get("approvalModule");
            if (params.get("outputValidator") != null)
                outputValidatorName = params.get("outputValidator");
            if (params.get("executionTimeoutMs") != null)
                executionTimeoutMs = Long.parseLong(params.get("executionTimeoutMs"));
            if (params.get("staleStateTimeoutMs") != null)
                staleStateTimeoutMs = Long.parseLong(params.get("staleStateTimeoutMs"));
        }
    }

    @Override
    protected TLBaseModule init() { return this; }

    /** 懒解析输出校验模块（按名；模块不存在时记录 ERROR 并跳过） */
    private IObject resolveOutputValidator() {
        if (outputValidator != null) return outputValidator;
        if (outputValidatorName == null || outputValidatorName.isEmpty()) return null;
        synchronized (this) {
            if (outputValidator == null) {
                Object m = moduleFactory != null ? moduleFactory.getModule(outputValidatorName) : null;
                if (m instanceof IObject) {
                    outputValidator = (IObject) m;
                    putLog("Output validator resolved: " + outputValidatorName, LogLevel.INFO);
                } else {
                    putLog("Output validator not found: " + outputValidatorName
                            + " → 跳过输出校验", LogLevel.ERROR);
                    return null;
                }
            }
        }
        return outputValidator;
    }

    private static class OutputCheck {
        boolean valid = true;
        String reason = "";
        boolean retryable = false;
        String correctedOutput;
    }

    /**
     * 工具结果客观校验：把完整 result TLMsg + 任务元数据交给 outputValidator。
     * 校验器缺失/异常返回 null（fail-open，保持原行为）。
     */
    private OutputCheck validateToolOutput(ExecutionState state, ToolTask task, TLMsg result) {
        // 执行失败（RESULT=false）已有明确错误语义，不再用校验规则覆盖它
        if (result.containsParam(RESULT) && !result.parseBoolean(RESULT, true)) return null;
        IObject validator = resolveOutputValidator();
        if (validator == null) return null;
        try {
            String agentName = state.fromWho instanceof IObject
                    ? ((IObject) state.fromWho).getName() : "";
            TLMsg req = createMsg().setAction(OUTPUT_VALIDATE_ACTION)
                    .setParam("functionName", task.functionName != null ? task.functionName : "")
                    .setParam("moduleName", task.moduleName != null ? task.moduleName : "")
                    .setParam("toolCallId", task.toolCallId)
                    .setParam("args", task.args)
                    .setParam("result", result)
                    .setParam("agentName", agentName)
                    .setParam("targetModule", task.module);
            TLMsg resp = putMsg(validator, req);
            if (resp == null) return null;
            OutputCheck c = new OutputCheck();
            if (resp.containsParam("valid")) c.valid = resp.parseBoolean("valid", true);
            else if (resp.containsParam(RESULT)) c.valid = resp.parseBoolean(RESULT, true);
            c.reason = resp.getStringParam("reason", "");
            c.retryable = resp.parseBoolean("retryable", false);
            c.correctedOutput = resp.getStringParam("correctedOutput", null);
            return c;
        } catch (Throwable t) {
            putLog("output validator exception: " + t, LogLevel.ERROR);
            return null;
        }
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
        String sessionId = String.valueOf(msg.getSystemParam(AI_P_SESSIONID, "default"));
        String userId = String.valueOf(msg.getSystemParam("userId", "default"));
        String executionId = String.valueOf(msg.getSystemParam("executionId", sessionId + "_" + System.nanoTime()));
        String rootSessionId = String.valueOf(msg.getSystemParam("rootSessionId", sessionId));
        String roundId = String.valueOf(msg.getSystemParam(AI_P_ROUNDID, ""));

        if (tasks == null || tasks.isEmpty()) {
            return createMsg().setParam(RESULT, true)
                    .setParam("results", Collections.emptyList());
        }

        // 防御性清理：扫描并移除残留的孤儿状态
        cleanupStaleStates();

        int n = tasks.size();
        CountDownLatch latch = new CountDownLatch(n);
        ExecutionState state = new ExecutionState(executionId, sessionId, latch);
        state.fromWho = fromWho;
        state.userId = userId;
        state.rootSessionId = rootSessionId;
        state.roundId = roundId;
        state.total = n;
        states.put(executionId, state);

        try {
            // 并行 fire ThreadTask
            Map<Integer, Long> taskStartTs = new HashMap<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                final ToolTask task = tasks.get(i);
                TLMsg execMsg = createMsg().setAction("_toolExecInternal")
                        .setParam("_task", task).setParam("_idx", idx)
                        .setParam("_fromWho", fromWho)
                        .setSystemParam("executionId", executionId)
                        .setSystemParam(AI_P_SESSIONID, sessionId)
                        .setSystemParam("userId", userId)
                        .setSystemParam("rootSessionId", rootSessionId)
                        .setSystemParam(AI_P_ROUNDID, roundId);
                // 每个 task 可单独设超时，传给 ThreadTask
                if (task.timeoutMs > 0)
                    execMsg.setSystemParam(TASKTIMEOUT, task.timeoutMs);
                // 断点恢复的"已批准"执行：人工已就本次调用做过决策，不得再次过门禁。
                // 仅单任务批次生效——多任务批次里"批准一个"不等于"批准全部"（T7 的恢复路径就是单任务）
                if (tasks.size() <= 1
                        && TLApprovalRequest.APPROVED.equals(String.valueOf(msg.getSystemParam(AI_P_APPROVAL_DECISION, "")))) {
                    execMsg.setSystemParam(AI_P_APPROVAL_DECISION, TLApprovalRequest.APPROVED);
                }
                TLMsg taskResult = putMsgNoWait(this, execMsg);
                Object ttObj = taskResult == null ? null : taskResult.getParam(THREADPOOL_TASK);
                if (ttObj instanceof ThreadTask) {
                    state.activeTasks.add((ThreadTask) ttObj);
                } else {
                    // 线程池拒绝/销毁竞态时 TLThreadPool.execute 显式 return null。
                    // 不兜底会 NPE，且该任务的 latch 永远不会 countDown → 整批挂到执行超时。
                    String failText = "工具任务提交失败（线程池不可用，应用可能正在关闭）";
                    putLog(failText + ", executionId=" + executionId + " idx=" + idx
                            + " tool=" + task.functionName, LogLevel.ERROR, "executeTools");
                    state.results.put(idx, new ToolResult(task.toolCallId, failText));
                    state.latch.countDown();
                }
                // 全链追踪：工具开始执行（内建/预计算任务可能两个名字都为空，别拼出 "null execId="）
                taskStartTs.put(idx, System.currentTimeMillis());
                String toolDisplay = task.functionName != null && !task.functionName.isEmpty()
                        ? task.functionName
                        : (task.moduleName != null && !task.moduleName.isEmpty()
                                ? task.moduleName : "(internal)");
                traceStage(fromWho, sessionId, rootSessionId, roundId, "toolStart",
                        toolDisplay + " execId=" + executionId, 0);
            }

            // 等待全部完成（executionTimeoutMs=0 则一直等待，>0 超时后中断剩余任务）
            if (executionTimeoutMs > 0) {
                boolean completed = latch.await(executionTimeoutMs, TimeUnit.MILLISECONDS);
                if (!completed) {
                    putLog("Tool execution timeout after " + (executionTimeoutMs / 1000)
                            + "s, executionId=" + executionId + ", aborting " + latch.getCount()
                            + " remaining task(s)", LogLevel.WARN);
                    state.aborted = true;
                    state.abortReason = "executionTimeout(" + (executionTimeoutMs / 1000) + "s)";
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
            boolean hasFinal = false;
            String finalResponse = null, rejectReason = null, clarificationQuestion = null;
            String pendingApprovalId = null, pendingToolCallId = null;

            for (int i = 0; i < n; i++) {
                ToolResult tr = state.results.get(i);
                if (tr == null) {
                    // 超时强制唤醒 / Error 级异常等路径可能没写结果。缺一条会让上层按 index 配对整体错位，
                    // 下一次请求还会被 sanitizeToolPairs 把整轮工具消息丢弃。补一条**普通结果**
                    //（state 留空，不能标 "timeout"——那会让上层把整轮当超时分支丢弃）。
                    ToolTask missTask = tasks.get(i);
                    String missName = missTask.functionName != null && !missTask.functionName.isEmpty()
                            ? missTask.functionName
                            : (missTask.moduleName != null && !missTask.moduleName.isEmpty()
                                    ? missTask.moduleName : "tool");
                    String missReason = state.aborted
                            ? (state.abortReason != null && !state.abortReason.isEmpty()
                                    ? state.abortReason : "执行中断")
                            : "执行未返回结果";
                    tr = new ToolResult(missTask.toolCallId,
                            "[未完成] " + missName + " 未返回结果（" + missReason + "）");
                    finishTask(state, missTask, i, tr, TOOL_STATUS_ERROR, 0L, "未返回结果");
                }
                if (tr.state == null) {
                    results.add(tr);
                } else if ("pending".equals(tr.state)) {
                    hasPending = true;
                    finalResponse = tr.output;
                    pendingApprovalId = tr.approvalId;
                    pendingToolCallId = tr.toolCallId;
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
                } else if ("final".equals(tr.state)) {
                    hasFinal = true;
                    finalResponse = tr.output;
                    results.add(tr);   // 直出结果也要进 results（history 写入/会话恢复依赖完整结果列表）
                }
                // 全链追踪：工具执行结束（含工具名/状态与耗时）
                // tr.state 只覆盖 pending/rejected/clarified/timeout/final 等控制态；
                // 正常完成/预校验跳过/执行报错都在 finalStatus（done/skipped/error）。
                // 不能因为 tr.state==null 就一律记 ok——skipped 的任务从未执行。
                String finalStatus = state.finalStatuses.get(i);
                String toolState = tr.state != null
                        ? tr.state
                        : (finalStatus == null || TOOL_STATUS_DONE.equals(finalStatus) ? "ok" : finalStatus);
                long startTs = taskStartTs.getOrDefault(i, System.currentTimeMillis());
                ToolTask doneTask = i < tasks.size() ? tasks.get(i) : null;
                String doneTool = "?";
                if (doneTask != null) {
                    if (doneTask.functionName != null && !doneTask.functionName.isEmpty())
                        doneTool = doneTask.functionName;
                    else if (doneTask.moduleName != null && !doneTask.moduleName.isEmpty())
                        doneTool = doneTask.moduleName;
                }
                traceStage(fromWho, sessionId, rootSessionId, roundId, "toolEnd",
                        "tool=" + doneTool + " state=" + toolState, System.currentTimeMillis() - startTs);
            }

            response.setParam("results", results);
            response.setParam("aborted", state.aborted);
            if (state.abortReason != null) response.setParam("abortReason", state.abortReason);
            response.setParam("pendingApproval", hasPending);
            response.setParam("rejected", hasRejected);
            response.setParam("clarified", hasClarified);
            response.setParam("hasTimeout", hasTimeout);
            response.setParam(AI_P_FINALANSWER, hasFinal);
            if (finalResponse != null) response.setParam("finalResponse", finalResponse);
            if (rejectReason != null) response.setParam("rejectReason", rejectReason);
            if (clarificationQuestion != null) response.setParam("clarificationQuestion", clarificationQuestion);
            if (pendingApprovalId != null) response.setParam(AI_P_APPROVAL_ID, pendingApprovalId);
            // 被审批拦截的那一个 tool 的 id：多工具批次里批准恢复必须按 id 定位，
            // 否则会错拿批次首个 tool 重放（审批属于哪个工具，只有执行器知道）
            if (pendingToolCallId != null) response.setParam("pendingToolCallId", pendingToolCallId);

            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 确保 latch 被释放，防止活跃任务永远等待
            state.aborted = true;
            state.abortReason = "callerInterrupted";
            while (state.latch.getCount() > 0) state.latch.countDown();
            for (ThreadTask t : state.activeTasks) { t.cancelTask(); t.interrupt(); }
            return createMsg().setParam(RESULT, false).setParam("aborted", true)
                    .setParam("error", "Interrupted");
        } finally {
            states.remove(executionId);
        }
    }

    /**
     * 工具参数的身份字段净化：userId/sessionId/rootSessionId 只允许来自执行器注入的
     * systemArgs，LLM 工具参数里的同名字段一律覆盖（systemArgs 缺失则剔除）。
     *
     * <p>背景（2026-10-22）：LLM 的 function arguments 原样透传给技能；schedule_task 等
     * 技能又优先读 args 里的 userId。用户只要让模型在参数里带上别人的 userId，就能越权
     * list/create/remove 对方任务（confused deputy）。身份必须只信框架注入的通道。
     *
     * <p>只处理调用方确实带了的身份字段：覆盖为可信值或剔除，不凭空注入——避免改变
     * 普通业务参数/MCP 工具参数的形状（MCP 的 userId 是工具自己的业务参数，不在此列）。
     *
     * @return 防御性副本（不改 LLM 原始 args，便于日志/追踪保留原文）
     */
    static Map<String, Object> sanitizeIdentityArgs(Map<String, Object> args, TLMsg execMsg) {
        Map<String, Object> safe = args == null ? new LinkedHashMap<>() : new LinkedHashMap<>(args);
        scrubIdentity(safe, AI_P_USERID, execMsg.getSystemParam("userId", null));
        scrubIdentity(safe, AI_P_SESSIONID, execMsg.getSystemParam(AI_P_SESSIONID, null));
        scrubIdentity(safe, "rootSessionId", execMsg.getSystemParam("rootSessionId", null));
        return safe;
    }

    /** 仅当 args 里存在该身份字段时才处理：有可信值则覆盖，无可信值则剔除；不凭空注入 */
    private static void scrubIdentity(Map<String, Object> args, String key, Object trusted) {
        if (!args.containsKey(key)) return;
        if (trusted == null || String.valueOf(trusted).isEmpty()) args.remove(key);
        else args.put(key, trusted);
    }

    /**
     * 向调用方 Agent 实例回推一条工具进度。进度是旁路：
     * 任何异常都必须吞掉，不能影响工具执行结果。
     */
    private void notifyProgress(ExecutionState st, ToolTask task, int idx,
                                String status, long durationMs, String message) {
        try {
            if (st == null || st.fromWho == null) return;
            String toolName = task.functionName != null && !task.functionName.isEmpty()
                    ? task.functionName
                    : (task.moduleName != null ? task.moduleName : "");
            TLMsg evt = createMsg()
                    .setAction(TOOL_PROGRESS)
                    .setParam("executionId", st.executionId)
                    .setParam("roundId", st.roundId)
                    .setParam("toolCallId", task.toolCallId)
                    .setParam("toolName", toolName)
                    .setParam("moduleName", task.moduleName != null ? task.moduleName : "")
                    .setParam("index", idx)
                    .setParam("total", st.total)
                    .setParam("status", status)
                    .setParam("durationMs", durationMs)
                    .setParam("message", message != null
                            ? (message.length() > 200 ? message.substring(0, 200) : message) : "")
                    .setSystemParam(AI_P_SESSIONID, st.sessionId)
                    .setSystemParam("userId", st.userId)
                    .setSystemParam("rootSessionId", st.rootSessionId)
                    .setSystemParam(AI_P_ROUNDID, st.roundId);
            evt.setSystemParam(IGNOREMODULEISNULL, true);
            if (st.fromWho instanceof IObject) putMsg((IObject) st.fromWho, evt);
            else if (st.fromWho instanceof String) putMsg((String) st.fromWho, evt);
        } catch (Throwable t) {
            try { putLog("tool progress notify failed: " + t, LogLevel.DEBUG); } catch (Throwable ignored) {}
        }
    }

    /** 写结果 + 发终态事件（同一入口，保证不会漏发/重发） */
    private void finishTask(ExecutionState st, ToolTask task, int idx, ToolResult result,
                            String status, long durationMs, String message) {
        st.results.put(idx, result);
        if (status != null) st.finalStatuses.put(idx, status);
        notifyProgress(st, task, idx, status, durationMs, message);
    }

    /**
     * 单个工具执行回调（ThreadTask 上运行）。
     */
    private TLMsg doToolExec(Object fromWho, TLMsg msg) {
        ToolTask task = (ToolTask) msg.getParam("_task");
        int idx = msg.getIntParam("_idx", -1);
        String executionId = String.valueOf(msg.getSystemParam("executionId", ""));
        ExecutionState state = states.get(executionId);
        if (state == null || task == null) {
            if (state == null) return null;
            return null;
        }

        if (!MCP_CALLTOOL.equals(task.action)) {
            task.args = sanitizeIdentityArgs(task.args, msg);
        }

        if (state.aborted) {
            // 中断也要留一条结果：保证 results 与 tasks 一一对应。
            // 控制态留空（超时/中断由批次级 aborted 标志收口），否则调用方可能把整轮当超时丢弃。
            String abortedName = task.functionName != null && !task.functionName.isEmpty()
                    ? task.functionName
                    : (task.moduleName != null && !task.moduleName.isEmpty() ? task.moduleName : "tool");
            finishTask(state, task, idx,
                    new ToolResult(task.toolCallId, "[未完成] " + abortedName + " 未执行（已中断）"),
                    TOOL_STATUS_ABORTED, 0L, "已中断");
            state.latch.countDown();
            return null;
        }

        boolean started = false;
        long startTs = System.currentTimeMillis();
        try {
            boolean preApproved = TLApprovalRequest.APPROVED.equals(
                    msg.getSystemParam(AI_P_APPROVAL_DECISION, ""));
            String approvalToolName = task.functionName != null && !task.functionName.isEmpty()
                    ? task.functionName : task.moduleName;
            TLMsg approvalResult = null;
            if (!preApproved) {
                state.approvalWaiters.incrementAndGet();
                try {
                    approvalResult = checkApprovalGate(task.toolCallId, task.args, approvalToolName,
                            String.valueOf(msg.getSystemParam(AI_P_SESSIONID, "default")),
                            String.valueOf(msg.getSystemParam("userId", "default")),
                            String.valueOf(msg.getSystemParam(AI_P_ROUNDID, "")),
                            String.valueOf(msg.getSystemParam("rootSessionId",
                                    String.valueOf(msg.getSystemParam(AI_P_SESSIONID, "default")))));
                } finally {
                    state.approvalWaiters.decrementAndGet();
                }
            } else {
                putLog("Approval bypassed (pre-approved resume): " + approvalToolName
                        + " toolCallId=" + task.toolCallId, LogLevel.INFO);
            }
            if (approvalResult != null) {
                String approvalState = approvalResult.getStringParam(AI_P_APPROVAL_STATE, "");
                ToolResult tr = new ToolResult(task.toolCallId,
                        approvalResult.getStringParam(AI_P_SKILLOUTPUT, ""));
                tr.state = approvalState;
                if ("rejected".equals(approvalState))
                    tr.stateExtra = approvalResult.getStringParam(AI_P_APPROVAL_REJECTREASON, "用户拒绝");
                if ("pending".equals(approvalState))
                    tr.approvalId = approvalResult.getStringParam(AI_P_APPROVAL_ID, "");
                String status = "pending".equals(approvalState) ? TOOL_STATUS_PENDING
                        : ("rejected".equals(approvalState) ? TOOL_STATUS_REJECTED : TOOL_STATUS_ERROR);
                String text = "pending".equals(approvalState) ? "等待审批"
                        : ("rejected".equals(approvalState) ? tr.stateExtra : approvalState);
                finishTask(state, task, idx, tr, status, 0L, text);
                return null;
            }

            if (task.precomputedOutput != null) {
                finishTask(state, task, idx,
                        new ToolResult(task.toolCallId, task.precomputedOutput),
                        TOOL_STATUS_SKIPPED, 0L, "未执行（预校验）");
                return null;
            }

            if (task.module == null) {
                finishTask(state, task, idx,
                        new ToolResult(task.toolCallId,
                                "Internal error: no module for " + task.moduleName),
                        TOOL_STATUS_ERROR, 0L, "无执行模块");
                return null;
            }

            String functionName = task.moduleName;
            String toolName = MCP_CALLTOOL.equals(task.action) && task.nativeName != null
                    ? task.nativeName : functionName;
            putLog(">>> [Function] " + functionName + " → " + task.module.getName(), LogLevel.DEBUG);

            // ==== 真正开始执行：先发 starting ====
            startTs = System.currentTimeMillis();
            started = true;
            notifyProgress(state, task, idx, TOOL_STATUS_STARTING, 0L, "");

            TLMsg execMsg = createMsg();
            execMsg.setAction(task.action);
            execMsg.setParam(AI_P_TOOLNAME, toolName);
            if (MCP_CALLTOOL.equals(task.action)) {
                execMsg.setParam(AI_P_TOOLARGUMENTS, task.args);
            }
            execMsg.setParam(AI_P_SKILLINPUT, task.args).setParam(AI_P_TOOLID, task.toolCallId);
            for (Map.Entry<String, Object> entry : task.args.entrySet()) {
                execMsg.setParam(entry.getKey(), entry.getValue());
            }
            Object toolUserId = msg.getSystemParam("userId", null);
            if (toolUserId != null) execMsg.setSystemParam("userId", toolUserId);
            execMsg.setSystemParam(AI_P_SESSIONID, msg.getSystemParam(AI_P_SESSIONID, "default"));
            execMsg.setSystemParam("rootSessionId", msg.getSystemParam("rootSessionId",
                    msg.getSystemParam(AI_P_SESSIONID, "default")));
            execMsg.setSystemParam(AI_P_ROUNDID, msg.getSystemParam(AI_P_ROUNDID, ""));

            TLMsg result = putMsg(task.module, execMsg);
            long elapsed = System.currentTimeMillis() - startTs;

            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                String st = state.aborted ? TOOL_STATUS_ABORTED : TOOL_STATUS_TIMEOUT;
                finishTask(state, task, idx,
                        new ToolResult(task.toolCallId,
                                "[超时] " + task.moduleName + " 执行超时", "timeout", "执行超时"),
                        st, elapsed, state.aborted ? "已中断" : "执行超时");
                return null;
            }
            // parseBoolean 兼容 Boolean 与 String（Mock/外部技能可能传 "true"）
            if (result != null && result.parseBoolean(TASKTIMEOUT, false)) {
                String st = state.aborted ? TOOL_STATUS_ABORTED : TOOL_STATUS_TIMEOUT;
                finishTask(state, task, idx,
                        new ToolResult(task.toolCallId,
                                "[超时] " + task.moduleName + " 执行超时", "timeout", "执行超时"),
                        st, elapsed, state.aborted ? "已中断" : "执行超时");
                return null;
            }

            if (result == null) {
                // 目标模块无响应（崩溃/超时/不可达）不能记成 "done"：LLM 会以为操作成功
                String target = task.moduleName != null && !task.moduleName.isEmpty()
                        ? task.moduleName : task.module.getName();
                String noResp = "[无响应] " + target + " 未返回结果（模块可能崩溃/不可用）";
                putLog("<<< [Function] " + functionName + " 无响应: " + target, LogLevel.ERROR);
                finishTask(state, task, idx,
                        new ToolResult(task.toolCallId, noResp, "error", "no response"),
                        TOOL_STATUS_ERROR, elapsed, "工具模块无响应");
                return null;
            }

            String output;
            if (result.containsParam(AI_P_SKILLOUTPUT)) {
                output = result.getStringParam(AI_P_SKILLOUTPUT, "");
            } else {
                output = result.getStringParam(AI_P_RESPONSE, "");
            }
            if (output.isEmpty()) output = TLMsgUtils.msgToSimpleStr(result);

            if (result != null && result.parseBoolean("rejected", false)) {
                String reason = result.getStringParam("rejectReason", "用户拒绝");
                putLog("<<< [Function] " + functionName + " 返回: REJECTED (" + reason + ")", LogLevel.WARN);
                finishTask(state, task, idx,
                        new ToolResult(task.toolCallId, output, "rejected", reason),
                        TOOL_STATUS_REJECTED, elapsed, reason);
                return null;
            }
            boolean finalResult = result != null
                    && result.parseBoolean(AI_P_FINALANSWER, false);

            OutputCheck check = validateToolOutput(state, task, result);
            if (check != null && !check.valid) {
                String reason = check.reason != null && !check.reason.isEmpty()
                        ? check.reason : "未知原因";
                String failure = "输出校验失败：" + reason
                        + (check.retryable
                            ? "（可修正后重试）"
                            : "（不可重试；请检查输入或实现，不要用相同参数重试）");
                putLog("<<< [Function] " + functionName + " 输出校验失败: " + reason, LogLevel.WARN);
                // final 也要校验：失败时仍按直出短路，把明确错误作为终答给用户，不再调 LLM
                ToolResult tr = finalResult
                        ? new ToolResult(task.toolCallId, failure, "final", null)
                        : new ToolResult(task.toolCallId, failure);
                finishTask(state, task, idx, tr, TOOL_STATUS_ERROR, elapsed, "输出校验失败");
                return null;
            }
            if (check != null && check.correctedOutput != null
                    && !check.correctedOutput.isEmpty()) {
                output = check.correctedOutput;
                putLog("<<< [Function] " + functionName + " 输出已被校验器修正", LogLevel.WARN);
            }

            if (finalResult) {
                putLog("<<< [Function] " + functionName + " 返回: FINAL(直出)", LogLevel.DEBUG);
                finishTask(state, task, idx,
                        new ToolResult(task.toolCallId, output, "final", null),
                        TOOL_STATUS_DONE, elapsed, "");
                return null;
            }

            putLog("<<< [Function] " + functionName + " 返回 (前200字): "
                    + (output.length() > 200 ? output.substring(0, 200) : output), LogLevel.DEBUG);
            finishTask(state, task, idx, new ToolResult(task.toolCallId, output),
                    TOOL_STATUS_DONE, elapsed, "");
        } catch (Exception e) {
            boolean isTimeout = Thread.currentThread().isInterrupted()
                    || e instanceof InterruptedException
                    || e.getClass().getName().contains("Interrupted");
            long elapsed = System.currentTimeMillis() - startTs;
            if (isTimeout) {
                Thread.currentThread().interrupt();
                putLog("Tool execution timeout: " + task.moduleName, LogLevel.WARN);
                String st = state.aborted ? TOOL_STATUS_ABORTED : TOOL_STATUS_TIMEOUT;
                finishTask(state, task, idx,
                        new ToolResult(task.toolCallId,
                                "[超时] " + task.moduleName + " 执行超时", "timeout", "执行超时"),
                        st, elapsed, state.aborted ? "已中断" : "执行超时");
            } else {
                putLog("Tool execution error: " + task.moduleName + " -> " + e.toString(), LogLevel.ERROR);
                finishTask(state, task, idx,
                        new ToolResult(task.toolCallId,
                                "Error executing " + task.moduleName + ": " + e.getMessage()),
                        TOOL_STATUS_ERROR, elapsed, e.getMessage());
            }
        } finally {
            if (started && !state.results.containsKey(idx)) {
                notifyProgress(state, task, idx,
                        state.aborted ? TOOL_STATUS_ABORTED : TOOL_STATUS_ERROR,
                        System.currentTimeMillis() - startTs,
                        state.aborted ? "已中断" : "执行异常结束");
            }
            state.latch.countDown();
        }
        return null;
    }

    // ======================== 审批门禁 ========================

    /**
     * 审批门禁：在执行工具前发送审批请求到审批模块。
     * @return null = 放行；非 null = 被拦截（pending/rejected）
     *
     * <p>安全默认（fail-closed）：未配置审批模块（功能未启用）→ 放行；
     * 配置了但模块解析不到 / 无应答 / 抛异常 → 一律拒绝执行，不放行——否则审批形同虚设。
     */
    private TLMsg checkApprovalGate(String toolCallId, Map<String, Object> toolArgs,
                                     String toolName, String sessionId, String userId, String roundId,
                                     String rootSessionId) {
        if (approvalModule == null && approvalModuleName != null && !approvalModuleName.isEmpty()) {
            // 模块创建（getModule 触发构造/init）本身可能抛异常——与请求/putMsg 一样按 fail-closed
            // 收口，否则异常逃到 doToolExec 外层 catch，会变成不具 rejected 形状的通用执行错误
            try {
                synchronized (this) {
                    if (approvalModule == null) {
                        Object m = moduleFactory != null ? moduleFactory.getModule(approvalModuleName) : null;
                        if (m == null && modules != null) m = modules.get(approvalModuleName);
                        if (m instanceof IObject) {
                            approvalModule = (IObject) m;
                            putLog("Approval module resolved: " + approvalModuleName, LogLevel.INFO);
                        } else {
                            // 配置了审批却解析不到模块：拒绝执行（fail-closed）。
                            // 原实现是"置 approvalModuleName=null 并放行"——审批静默失效，比报错危险。
                            // 不置 null（保留名字，模块稍后就绪时下次调用可自愈重试）。
                            putLog("Approval module not found: " + approvalModuleName + " → 拒绝执行（安全默认）", LogLevel.ERROR);
                            return buildApprovalUnavailable("审批服务不可用（模块 " + approvalModuleName + " 未找到）（非用户拒绝，可稍后重试）");
                        }
                    }
                }
            } catch (Exception e) {
                putLog("Approval module resolve error: " + e + " → 拒绝执行（安全默认）", LogLevel.ERROR);
                return buildApprovalUnavailable("审批服务不可用（非用户拒绝，可稍后重试）");
            }
        }
        if (approvalModule == null) return null;   // 未配置审批模块 = 功能未启用（与"配置了但不可用"区分）

        try {
            TLMsg result = putMsg(approvalModule,
                    createMsg().setAction(APPROVAL_REQUEST)
                            .setParam("toolName", toolName)
                            .setParam("toolArgs", toolArgs != null
                                    ? new LinkedHashMap<>(toolArgs) : new LinkedHashMap<>())
                            .setSystemParam(AI_P_SESSIONID, sessionId)
                            .setSystemParam("userId", userId)
                            .setParam("toolCallId", toolCallId)
                            .setSystemParam(AI_P_ROUNDID, roundId)
                            .setSystemParam("rootSessionId", rootSessionId));

            if (result == null) {
                putLog("Approval gate no response → 拒绝执行（安全默认）", LogLevel.ERROR);
                return buildApprovalUnavailable("审批服务无应答（非用户拒绝，可稍后重试）");
            }
            String state = result.getStringParam(AI_P_APPROVAL_STATE, "");
            if ("approved".equals(state)) return null;
            return result;
        } catch (Exception e) {
            putLog("checkApprovalGate error: " + e.toString() + " → 拒绝执行（安全默认）", LogLevel.ERROR);
            return buildApprovalUnavailable("审批门禁异常（非用户拒绝，可稍后重试）");
        }
    }

    /** 审批不可用时的拒绝结果（fail-closed；与 rejected 同形，上游无需新分支） */
    private TLMsg buildApprovalUnavailable(String reason) {
        return createMsg().setParam(RESULT, false)
                .setParam(AI_P_APPROVAL_STATE, TLApprovalRequest.REJECTED)
                .setParam(AI_P_APPROVAL_REJECTREASON, reason)
                .setParam(AI_P_SKILLOUTPUT, "⚠️ 操作被拒绝：" + reason + "（安全默认：审批不可用时拒绝执行）");
    }

    // ======================== 全链追踪打点 ========================

    /** 发 recordStage 给监控模块（未配监控时静默忽略，IGNOREMODULEISNULL） */
    private void traceStage(Object fromWho, String sessionId, String rootSessionId, String roundId,
                            String stage, String detail, long durationMs) {
        try {
            String agentName = fromWho instanceof TLBaseModule
                    ? ((TLBaseModule) fromWho).getName() : "toolExecutor";
            TLMsg traceMsg = createMsg().setAction("recordStage")
                    .setParam("agentName", agentName)
                    .setParam(AI_P_SESSIONID, sessionId)
                    .setParam("rootSessionId", rootSessionId)
                    .setParam(AI_P_ROUNDID, roundId)
                    .setParam("stage", stage)
                    .setParam("detail", TLAgentMonitor.sanitizeDetail(detail))
                    .setParam("durationMs", durationMs);
            traceMsg.setSystemParam(IGNOREMODULEISNULL, true);
            putMsg(M_AGENTMONITOR, traceMsg);
        } catch (Exception ignored) {}
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
                // 调用线程仍活着 = 正在合法等待本次执行，不是孤儿。长工具/委派子 agent 没有
                // 框架内的时长上限（executionTimeoutMs=0 时本就不限时），按年龄强杀会误中断
                // 正在正常工作的一轮（2026-10-22 codingAgent 委派 601s 被误杀事故）。
                // 真要限制单次工具时长，配 executionTimeoutMs，而不是靠残留清理。
                if (s.callerThread != null && s.callerThread.isAlive()) continue;
                // 有人正等人工审批：审批等待是设计语义内的合法长跑（approvalTimeoutMs=0 可无限），
                // 按超龄强杀会把"一直等"变成 600 秒后"审批被中断"（与 2026-10-14 修复同一故障类）
                if (s.approvalWaiters.get() > 0) continue;
                stale.add(entry.getKey());
            }
        }
        for (String id : stale) {
            ExecutionState s = states.remove(id);
            if (s != null) {
                s.aborted = true;
                s.abortReason = "staleStateCleanup(age=" + ((now - s.createdAt) / 1000) + "s)";
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
                state.abortReason = "sessionCancel";
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
