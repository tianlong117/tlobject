package cn.tianlong.tlobject.aiagent.approval;

import cn.tianlong.tlobject.aiagent.IAgentCapable;
import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.*;
import java.util.concurrent.*;

/**
 * 人工审批模块。负责工具调用的风险检查与审批调度。
 *
 * <h3>职责</h3>
 * <ol>
 *   <li>规则匹配：根据 XML 配置的 {@code approvalRules} 判断一个工具调用是否需要审批</li>
 *   <li>审批调度：创建审批请求，委托给可插拔的 {@link IApprovalReviewer} 处理</li>
 *   <li>状态管理：维护审批请求生命周期（PENDING → APPROVED/REJECTED/EXPIRED）</li>
 *   <li>超时处理：超时自动拒绝（安全优先）</li>
 * </ol>
 *
 * <h3>配置参数（XML params）</h3>
 * <pre>{@code
 *   approvalRules      — 审批规则，格式 "tool:op, tool:op, tool"（无:op=全部操作）
 *   reviewerClass      — IApprovalReviewer 实现类全名
 *   reviewerStrategy   — "sync"(同步阻塞) / "async"(异步回调)，默认 "sync"
 *   approvalTimeoutMs  — 超时毫秒数，默认 300000（5分钟）
 * }</pre>
 *
 * <h3>消息接口</h3>
 * <pre>{@code
 *   approvalRequest(toolName, toolArgs, sessionId, toolCallId) → approved/rejected/pending
 *   approvalApprove(approvalId, approvalModifiedArguments?)        → 批准
 *   approvalReject(approvalId, approvalRejectReason?)             → 拒绝
 *   approvalQuery(approvalId)                                     → 查询状态
 * }</pre>
 *
 * 创建日期：2026/7/25
 * 作者：tianlong
 */
public class TLApprovalModule extends TLBaseModule implements TLAiAgentParamString, IAgentCapable {

    // ======================== 规则相关 ========================

    /** 工具名 → 需审批的操作集合（空 Set = 全部操作都审） */
    private final Map<String, Set<String>> approvalRules = new LinkedHashMap<>();

    // ======================== 审查人 ========================

    /** 审查人实例 */
    private IApprovalReviewer reviewer;

    /** 审查人策略：sync / async */
    private String reviewerStrategy = "sync";

    /** 审查人类全名（XML 配置） */
    private String reviewerClass;

    /** 审批超时毫秒数（默认 5 分钟） */
    private long approvalTimeoutMs = 300_000L;

    // ======================== 审批请求管理 ========================

    /** 审批请求注册表：approvalId → TLApprovalRequest */
    private final ConcurrentHashMap<String, TLApprovalRequest> pendingApprovals = new ConcurrentHashMap<>();

    /**
     * 会话级拒绝记忆：key = sessionId|toolName|argsKey → 拒绝原因。
     * 同一会话内被拒绝的（工具+参数）组合再次请求时直接返回已拒绝，不再重复弹审批，
     * 防止 LLM 在委派/续跑回环中重试被拒操作造成连环审批。上限 200，超出驱逐最旧。
     */
    private final Map<String, String> rejectedOps = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    /** 拒绝记忆上限 */
    private static final int MAX_REJECTED_OPS = 200;

    /** 决策信号：approvalId → CountDownLatch（同步审查人阻塞在此，handleApprove/handleReject 信号唤醒） */
    private final ConcurrentHashMap<String, java.util.concurrent.CountDownLatch> decisionLatches = new ConcurrentHashMap<>();

    /** 超时调度器（daemon 线程） */
    private ScheduledExecutorService timeoutScheduler;

    // ======================== 构造器 ========================

    public TLApprovalModule() { super(); }
    public TLApprovalModule(String name) { super(name); }
    public TLApprovalModule(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    // ======================== 生命周期 ========================

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null) {
            // 解析审批规则
            if (params.get("approvalRules") != null) {
                parseApprovalRules(params.get("approvalRules"));
            }
            if (params.get("reviewerClass") != null) {
                this.reviewerClass = params.get("reviewerClass");
            }
            if (params.get("reviewerStrategy") != null) {
                this.reviewerStrategy = params.get("reviewerStrategy");
            }
            if (params.get("approvalTimeoutMs") != null) {
                try {
                    this.approvalTimeoutMs = Long.parseLong(params.get("approvalTimeoutMs"));
                } catch (NumberFormatException ignored) {}
            }
        }

        // 创建审查人实例
        if (reviewerClass != null && !reviewerClass.isEmpty()) {
            try {
                Class<?> clazz = Class.forName(reviewerClass);
                Object instance = clazz.getDeclaredConstructor().newInstance();
                if (instance instanceof IApprovalReviewer) {
                    this.reviewer = (IApprovalReviewer) instance;
                    if (instance instanceof ConsoleReviewer) {
                        ((ConsoleReviewer) instance).setOwner(this);
                    }
                    putLog("Reviewer loaded: " + reviewerClass + " strategy=" + reviewerStrategy, LogLevel.INFO);
                } else {
                    putLog("reviewerClass does not implement IApprovalReviewer: " + reviewerClass, LogLevel.WARN);
                }
            } catch (Exception e) {
                putLog("Failed to load reviewer: " + reviewerClass + " - " + e.getMessage(), LogLevel.ERROR);
            }
        }
    }

    @Override
    protected TLBaseModule init() {
        // 如果没有显式配置审查人，默认使用 ConsoleReviewer
        if (reviewer == null) {
            this.reviewer = new ConsoleReviewer();
            putLog("Using default ConsoleReviewer", LogLevel.INFO);
        }
        // 启动超时调度器（单线程 daemon）
        timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "approval-timeout");
            t.setDaemon(true);
            return t;
        });
        return this;
    }

    // ======================== 消息路由 ========================

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case APPROVAL_REQUEST:
                return handleApprovalRequest(fromWho, msg);
            case APPROVAL_APPROVE:
                return handleApprove(fromWho, msg);
            case APPROVAL_REJECT:
                return handleReject(fromWho, msg);
            case APPROVAL_QUERY:
                return handleQuery(fromWho, msg);
            default:
                return null;
        }
    }

    // ======================== 同步等待（供 ConsoleReviewer 调用） ========================

    /**
     * 阻塞等待审批决策（ConsoleReviewer 调用，代替原先的读 stdin）。
     * 由 {@link #handleApprove} / {@link #handleReject} 信号唤醒。
     */
    public TLMsg waitForDecision(TLApprovalRequest request) {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        decisionLatches.put(request.getApprovalId(), latch);
        try {
            boolean ok = latch.await(request.getTimeoutMs() > 0 ? request.getTimeoutMs() : approvalTimeoutMs,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!ok) {
                request.setState(TLApprovalRequest.EXPIRED);
                request.setRejectionReason("审批超时（" + (approvalTimeoutMs / 1000) + "秒）");
                putLog("Approval expired: id=" + request.getApprovalId(), LogLevel.INFO);
            } else if (Thread.currentThread().isInterrupted()) {
                request.setState(TLApprovalRequest.REJECTED);
                request.setRejectionReason("审批被中断");
            }
        } catch (InterruptedException e) {
            request.setState(TLApprovalRequest.REJECTED);
            request.setRejectionReason("审批被中断");
            Thread.currentThread().interrupt();
        }
        decisionLatches.remove(request.getApprovalId());
        return buildApprovalResponse(request, null);
    }

    /** 信号唤醒等待中的审查人 */
    private void signalDecision(String approvalId) {
        java.util.concurrent.CountDownLatch latch = decisionLatches.get(approvalId);
        if (latch != null) latch.countDown();
    }

    /**
     * 经消息总线发布审批事件（ConsoleReviewer 打印审批框前调用）。
     * 订阅者（如 TLChatConsole，按 destination="approvalEvent" 注册到 msgBus）自行渲染，
     * 审批模块不直接依赖控制台。无总线/订阅者时返回 false，调用方回退为直接打印。
     */
    public boolean publishApprovalEvent(String text) {
        try {
            Object bus = getModuleInFactory("msgBus");
            if (!(bus instanceof IObject)) return false;
            TLMsg evt = createMsg().setAction("approvalEvent").setParam("text", text);
            evt.setDestination("approvalEvent");  // 总线按 destination 路由到订阅者
            TLMsg ack = putMsg((IObject) bus, evt);
            return ack != null;  // 订阅者返回 ack 表示已处理
        } catch (Exception e) {
            putLog("publish approval event failed: " + e, LogLevel.DEBUG);
            return false;
        }
    }

    // ======================== 审批请求处理 ========================

    /**
     * 处理审批请求。Agent 调用 {@code executeToolCall} 时发来。
     *
     * <p>逻辑：
     * <ol>
     *   <li>提取 toolName、toolArgs、sessionId、toolCallId</li>
     *   <li>查规则决定是否需审批 → 不需要则返回空（放行）</li>
     *   <li>创建 TLApprovalRequest，登记到 pendingApprovals</li>
     *   <li>排期超时任务</li>
     *   <li>委托给 reviewer.requestApproval()</li>
     *   <li>返回结果给 Agent</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private TLMsg handleApprovalRequest(Object fromWho, TLMsg msg) {
        String toolName = msg.getStringParam("toolName", "");
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "");
        String toolCallId = msg.getStringParam("toolCallId", "");
        String rationale = msg.getStringParam("rationale", "");

        // 提取参数
        Map<String, Object> toolArgs;
        Object argsObj = msg.getMapParam("toolArgs", null);
        if (argsObj instanceof Map) {
            toolArgs = new LinkedHashMap<>((Map<String, Object>) argsObj);
        } else {
            toolArgs = new LinkedHashMap<>();
        }

        // 0. 会话级拒绝记忆：该（会话+工具+参数）组合已被拒绝过 → 直接返回拒绝，不再弹审批
        String rejectedKey = rejectionKey(sessionId, toolName, toolArgs);
        String rejectedReason = rejectedOps.get(rejectedKey);
        if (rejectedReason != null) {
            putLog("Approval auto-rejected (previously rejected in this session): "
                    + toolName + " session=" + sessionId, LogLevel.INFO);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_APPROVAL_STATE, TLApprovalRequest.REJECTED)
                    .setParam(AI_P_APPROVAL_REJECTREASON, rejectedReason)
                    .setParam(AI_P_SKILLOUTPUT, buildRejectedOutput(rejectedReason));
        }

        // 1. 检查是否需要审批
        if (!isApprovalRequired(toolName, toolArgs)) {
            // 放行：返回空（Agent 据此判断无需审批）
            return createMsg().setParam(AI_P_APPROVAL_STATE, "approved");
        }

        // 2. 提取操作名 + 确定风险级别
        String operation = extractOperation(toolName, toolArgs);
        String riskLevel = resolveRiskLevel(toolName, operation);

        // 3. 创建审批请求
        TLApprovalRequest request = new TLApprovalRequest(sessionId, toolName, toolArgs, toolCallId);
        request.setOperation(operation);
        request.setRiskLevel(riskLevel);
        request.setRationale(rationale);
        request.setTimeoutMs(approvalTimeoutMs);
        pendingApprovals.put(request.getApprovalId(), request);

        // 4. 排期超时
        scheduleTimeout(request.getApprovalId(), approvalTimeoutMs);

        putLog("Approval request created: id=" + request.getApprovalId()
                + " tool=" + toolName + " risk=" + riskLevel + " session=" + sessionId, LogLevel.INFO);

        // 5. 委托给审查人
        TLMsg reviewerResult = reviewer.requestApproval(request);

        // 6. 同步审查人已直接返回结果，更新状态并返回
        String state = reviewerResult.getStringParam(AI_P_APPROVAL_STATE, TLApprovalRequest.PENDING);

        if (TLApprovalRequest.APPROVED.equals(state)) {
            request.setState(TLApprovalRequest.APPROVED);
            request.setModifiedArguments(
                    (Map<String, Object>) reviewerResult.getMapParam(AI_P_APPROVAL_MODIFIEDARGS, null));
            putLog("Approval approved: id=" + request.getApprovalId(), LogLevel.INFO);

        } else if (TLApprovalRequest.REJECTED.equals(state)) {
            request.setState(TLApprovalRequest.REJECTED);
            request.setRejectionReason(
                    reviewerResult.getStringParam(AI_P_APPROVAL_REJECTREASON, "用户拒绝"));
            putLog("Approval rejected: id=" + request.getApprovalId()
                    + " reason=" + request.getRejectionReason(), LogLevel.INFO);

        } else {
            // pending: 异步审查人已接受，等待回调
            putLog("Approval pending (async): id=" + request.getApprovalId(), LogLevel.INFO);
        }

        // 构造返回给 Agent 的 TLMsg
        return buildApprovalResponse(request, reviewerResult);
    }

    /**
     * 批准审批请求（外部回调，如 Web 审批回调、控制台 /approve 命令等）。
     */
    @SuppressWarnings("unchecked")
    private TLMsg handleApprove(Object fromWho, TLMsg msg) {
        String approvalId = msg.getStringParam(AI_P_APPROVAL_ID, "");
        if (approvalId.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "missing approvalId");
        }

        TLApprovalRequest request = pendingApprovals.get(approvalId);
        if (request == null) {
            return createMsg().setParam(RESULT, false).setParam("error", "approval not found: " + approvalId);
        }

        if (!TLApprovalRequest.PENDING.equals(request.getState())) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "approval already " + request.getState());
        }

        // 用户可修改参数
        Map<String, Object> modifiedArgs =
                (Map<String, Object>) msg.getMapParam(AI_P_APPROVAL_MODIFIEDARGS, null);
        request.setModifiedArguments(modifiedArgs);
        request.setState(TLApprovalRequest.APPROVED);
        signalDecision(approvalId);  // 唤醒 ConsoleReviewer
        putLog("Approval approved: id=" + approvalId, LogLevel.INFO);

        return createMsg().setParam(RESULT, true).setParam(AI_P_APPROVAL_STATE, TLApprovalRequest.APPROVED);
    }

    /**
     * 拒绝审批请求（外部回调）。
     */
    private TLMsg handleReject(Object fromWho, TLMsg msg) {
        String approvalId = msg.getStringParam(AI_P_APPROVAL_ID, "");
        String reason = msg.getStringParam(AI_P_APPROVAL_REJECTREASON, "用户拒绝");
        if (approvalId.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "missing approvalId");
        }

        TLApprovalRequest request = pendingApprovals.get(approvalId);
        if (request == null) {
            return createMsg().setParam(RESULT, false).setParam("error", "approval not found: " + approvalId);
        }

        if (!TLApprovalRequest.PENDING.equals(request.getState())) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "approval already " + request.getState());
        }

        request.setRejectionReason(reason);
        request.setState(TLApprovalRequest.REJECTED);
        // 记入会话级拒绝记忆，防止 LLM 重试同一操作造成连环审批
        rememberRejection(request.getSessionId(), request.getToolName(),
                request.getToolArguments(), reason);
        signalDecision(approvalId);  // 唤醒 ConsoleReviewer
        putLog("Approval rejected: id=" + approvalId + " reason=" + reason, LogLevel.INFO);

        return createMsg().setParam(RESULT, true).setParam(AI_P_APPROVAL_STATE, TLApprovalRequest.REJECTED);
    }

    /**
     * 查询审批状态（断点恢复时 Agent 调用）。
     */
    private TLMsg handleQuery(Object fromWho, TLMsg msg) {
        String approvalId = msg.getStringParam(AI_P_APPROVAL_ID, "");
        if (approvalId.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "missing approvalId");
        }

        TLApprovalRequest request = pendingApprovals.get(approvalId);
        if (request == null) {
            return createMsg().setParam(RESULT, false).setParam("error", "approval not found: " + approvalId);
        }

        return buildApprovalResponse(request, null);
    }

    // ======================== 规则匹配 ========================

    /** 构建拒绝记忆键：sessionId|toolName|排序后的参数 */
    private String rejectionKey(String sessionId, String toolName, Map<String, Object> args) {
        String argsKey = args != null && !args.isEmpty() ? new TreeMap<>(args).toString() : "{}";
        return sessionId + "|" + toolName + "|" + argsKey;
    }

    /** 记录一次拒绝（会话级），超上限驱逐最旧 */
    private void rememberRejection(String sessionId, String toolName,
                                   Map<String, Object> args, String reason) {
        synchronized (rejectedOps) {
            if (rejectedOps.size() >= MAX_REJECTED_OPS) {
                String firstKey = rejectedOps.keySet().iterator().next();
                rejectedOps.remove(firstKey);
            }
            rejectedOps.put(rejectionKey(sessionId, toolName, args),
                    reason != null ? reason : "用户拒绝");
        }
    }

    /** 拒绝结果文案：明确告知 LLM 不得重试 */
    private String buildRejectedOutput(String reason) {
        return "⚠️ 用户已拒绝此操作：" + (reason != null ? reason : "用户拒绝")
                + "。请勿重试或以其他方式绕过，直接向用户说明拒绝原因。";
    }

    /**
     * 判断一个工具调用是否需要审批。
     *
     * @param toolName 工具名（function name）
     * @param args     工具参数
     * @return true 需要审批
     */
    public boolean isApprovalRequired(String toolName, Map<String, Object> args) {
        if (approvalRules.isEmpty()) {
            return false;  // 未配置规则，全部放行
        }

        // 1. 精确匹配 toolName
        Set<String> ops = approvalRules.get(toolName);
        if (ops == null) {
            return false;  // 不在规则中
        }

        // 2. 空 Set = 该工具的所有操作都需审批
        if (ops.isEmpty()) {
            return true;
        }

        // 3. 按操作名匹配
        String operation = extractOperation(toolName, args);
        return operation != null && ops.contains(operation);
    }

    /**
     * 从参数中提取"操作名"。不同工具的操作藏在不同参数中。
     */
    private String extractOperation(String toolName, Map<String, Object> args) {
        if (args == null) return null;
        switch (toolName) {
            case "file_operation":
            case "fileOperationSkill":
                // file_operation 的 operation 参数：read/write/delete/list/exists
                Object op = args.get("operation");
                return op != null ? op.toString().toLowerCase() : null;
            case "http_request":
            case "httpRequestSkill":
                // http_request 的 method 参数：GET/POST/PUT/DELETE
                Object method = args.get("method");
                return method != null ? method.toString().toUpperCase() : null;
            case "script_execution":
            case "scriptExecutionSkill":
                // script_execution 无细分操作，全部需审批
                return null;
            default:
                // 其他工具：尝试通用 operation/method/action 参数
                if (args.containsKey("operation")) {
                    return args.get("operation").toString().toLowerCase();
                }
                if (args.containsKey("method")) {
                    return args.get("method").toString().toUpperCase();
                }
                if (args.containsKey("action")) {
                    return args.get("action").toString().toLowerCase();
                }
                return null;
        }
    }

    /**
     * 确定风险级别。目前简单映射：在规则中 = CRITICAL。
     * 未来可扩展为按 tool 和 operation 分别配置。
     */
    private String resolveRiskLevel(String toolName, String operation) {
        // 默认：被规则匹配到的至少是 HIGH
        return TLApprovalRequest.HIGH;
    }

    // ======================== 规则解析 ========================

    /**
     * 解析审批规则字符串。
     *
     * <pre>{@code
     *   "file_operation:delete, file_operation:write, code_execution, http_request:POST"
     *   → file_operation → {"delete", "write"}
     *   → code_execution → {}  (空 Set = 全部操作)
     *   → http_request   → {"POST"}
     * }</pre>
     */
    private void parseApprovalRules(String rulesStr) {
        approvalRules.clear();
        if (rulesStr == null || rulesStr.trim().isEmpty()) return;

        for (String rule : rulesStr.split(",")) {
            rule = rule.trim();
            if (rule.isEmpty()) continue;

            int colonIdx = rule.indexOf(':');
            if (colonIdx > 0) {
                String tool = rule.substring(0, colonIdx).trim();
                String op = rule.substring(colonIdx + 1).trim().toLowerCase();
                approvalRules.computeIfAbsent(tool, k -> new LinkedHashSet<>()).add(op);
            } else {
                // 无冒号 = 该工具全部操作都需审批
                approvalRules.put(rule, Collections.emptySet());
            }
        }

        putLog("Approval rules parsed: " + approvalRules, LogLevel.DEBUG);
    }

    // ======================== 超时处理 ========================

    private void scheduleTimeout(String approvalId, long timeoutMs) {
        if (timeoutMs <= 0) return;
        timeoutScheduler.schedule(() -> {
            TLApprovalRequest request = pendingApprovals.get(approvalId);
            if (request != null && TLApprovalRequest.PENDING.equals(request.getState())) {
                request.setState(TLApprovalRequest.EXPIRED);
                request.setRejectionReason("审批超时（" + (timeoutMs / 1000) + "秒）");
                putLog("Approval expired: id=" + approvalId, LogLevel.INFO);
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    // ======================== 响应构造 ========================

    /**
     * 构造返回给 Agent 的审批响应。
     */
    @SuppressWarnings("unchecked")
    private TLMsg buildApprovalResponse(TLApprovalRequest request, TLMsg reviewerResult) {
        TLMsg result = createMsg();
        result.setParam(AI_P_APPROVAL_STATE, request.getState());
        result.setParam(AI_P_APPROVAL_ID, request.getApprovalId());
        result.setParam(AI_P_APPROVAL_TOOLNAME, request.getToolName());
        result.setParam(AI_P_APPROVAL_TOOLARGS, request.getToolArguments());

        switch (request.getState()) {
            case TLApprovalRequest.APPROVED:
                result.setParam(RESULT, true);
                // 传回修改后的参数（如果有）
                if (request.getModifiedArguments() != null) {
                    result.setParam(AI_P_APPROVAL_MODIFIEDARGS, new LinkedHashMap<>(request.getModifiedArguments()));
                }
                result.setParam(AI_P_SKILLOUTPUT, "审批通过");
                break;

            case TLApprovalRequest.REJECTED:
                result.setParam(RESULT, false);
                result.setParam(AI_P_APPROVAL_REJECTREASON,
                        request.getRejectionReason() != null ? request.getRejectionReason() : "用户拒绝");
                result.setParam(AI_P_SKILLOUTPUT, buildRejectedOutput(request.getRejectionReason()));
                break;

            case TLApprovalRequest.PENDING:
                result.setParam(RESULT, true);
                result.setParam(AI_P_SKILLOUTPUT, buildPendingPrompt(request));
                break;

            case TLApprovalRequest.EXPIRED:
                result.setParam(RESULT, false);
                result.setParam(AI_P_APPROVAL_REJECTREASON, request.getRejectionReason());
                result.setParam(AI_P_SKILLOUTPUT,
                        "⚠️ 审批已超时: " + request.getRejectionReason());
                break;
        }

        return result;
    }

    /**
     * 构造 pending 状态下的用户提示文本。
     */
    private String buildPendingPrompt(TLApprovalRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 等待人工审批 ===\n");
        sb.append("工具: ").append(request.getToolName()).append("\n");
        sb.append("风险级别: ").append(request.getRiskLevel() != null ? request.getRiskLevel() : "-").append("\n");
        if (request.getToolArguments() != null && !request.getToolArguments().isEmpty()) {
            sb.append("参数: ").append(request.getToolArguments()).append("\n");
        }
        sb.append("审批ID: ").append(request.getApprovalId()).append("\n");
        sb.append("状态: 等待用户审批中...\n");
        return sb.toString();
    }

    // ======================== 公共方法 ========================

    /** 获取审批请求（供外部查询） */
    public TLApprovalRequest getRequest(String approvalId) {
        return pendingApprovals.get(approvalId);
    }

    /** 获取当前待审批的请求数 */
    public int getPendingCount() {
        return (int) pendingApprovals.values().stream()
                .filter(r -> TLApprovalRequest.PENDING.equals(r.getState())).count();
    }

    // ======================== 清理 ========================

    @Override
    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        if (timeoutScheduler != null && !timeoutScheduler.isShutdown()) {
            timeoutScheduler.shutdownNow();
        }
        return super.destroy(fromWho, msg);
    }
}
