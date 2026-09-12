package cn.tianlong.tlobject.aiagent.approval;

import cn.tianlong.tlobject.aiagent.IAgentCapable;
import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.aiagent.TLAgentMonitor;
import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLModuleConfig;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;

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
 *   approvalRules      — 审批规则，格式 "tool:op, tool, tool:*, tool*"
 *                        tool:op=指定操作需审批；tool=全部操作；tool:*=该工具通配；tool*=函数名前缀通配
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

    /** 通配规则前缀（tool:* → "tool:"，tool* → "tool"），按 startsWith 匹配函数名 */
    private final List<String> approvalRulePrefixes = new ArrayList<>();

    /** 规则名 → description（结构化 &lt;rules&gt; 配置的人可读说明，弹框展示用） */
    private final Map<String, String> ruleDescriptions = new LinkedHashMap<>();

    /** 结构化 &lt;rules&gt; 段解析结果（myConfig.setConfig 填充）：规则名 → 属性（statup/action/rule/description） */
    private HashMap<String, HashMap<String, String>> rulesConfig;

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
    /** pendingApprovals 的上限：超过后淘汰已决策的旧条目（PENDING 的不动） */
    private static final int MAX_PENDING_APPROVALS = 500;

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

    /**
     * 解析结构化 &lt;rules&gt; 段（与 TLAiAgent.myConfig 同款模式）：
     * <pre>{@code
     * <rules>
     *     <rule name="file_operation" statup="true" description="删除/写入文件要审批" action="delete,write"/>
     *     <rule name="priceTeam"      statup="true" description="写诗要审批"              rule="*"/>
     * </rules>
     * }</pre>
     * action="a,b" = 指定操作；rule="*" 或两者都无 = 该工具全部操作；
     * name 支持 tool:* / tool* 通配形式（同旧格式约定）。
     */
    @Override
    protected Object setConfig() {
        myConfig config = new myConfig(configFile, moduleFactory.getConfigDir());
        mconfig = config;
        super.setConfig();
        rulesConfig = config.getRules();
        return config;
    }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        // 规则优先级：结构化 <rules> 段 > 旧 approvalRules 字符串（向后兼容）
        if (rulesConfig != null && !rulesConfig.isEmpty()) {
            applyStructuredRules();
        } else if (params != null && params.get("approvalRules") != null) {
            parseApprovalRules(params.get("approvalRules"));
        }
        if (params != null) {
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
                Object instance = instantiateReviewer(clazz);
                if (instance instanceof IApprovalReviewer) {
                    this.reviewer = (IApprovalReviewer) instance;
                    injectReviewerOwner(instance);
                    putLog("Reviewer loaded: " + reviewerClass + " strategy=" + reviewerStrategy, LogLevel.INFO);
                } else {
                    putLog("reviewerClass does not implement IApprovalReviewer: " + reviewerClass, LogLevel.WARN);
                }
            } catch (Exception e) {
                putLog("Failed to load reviewer: " + reviewerClass + " - " + e.getMessage(), LogLevel.ERROR);
            }
        }
    }

    /**
     * 实例化审查人，优先支持框架依赖注入构造。
     * 构造优先级：(String, TLObjectFactory) → (TLObjectFactory) → 无参。
     * 审查人构造时只应保存引用（工厂服务在 start 后才完整可用）。
     */
    private Object instantiateReviewer(Class<?> clazz) throws Exception {
        try {
            return clazz.getDeclaredConstructor(String.class, TLObjectFactory.class)
                    .newInstance(name, moduleFactory);
        } catch (NoSuchMethodException ignored) {}
        try {
            return clazz.getDeclaredConstructor(TLObjectFactory.class)
                    .newInstance(moduleFactory);
        } catch (NoSuchMethodException ignored) {}
        return clazz.getDeclaredConstructor().newInstance();
    }

    /**
     * 宿主注入：审查人若有 setOwner(TLApprovalModule) 方法则回调注入，
     * 替代硬编码 instanceof ConsoleReviewer，自定义审查人同样受益。
     */
    private void injectReviewerOwner(Object instance) {
        try {
            instance.getClass().getMethod("setOwner", TLApprovalModule.class).invoke(instance, this);
        } catch (NoSuchMethodException ignored) {
            // 可选注入：无 setOwner 的审查人跳过
        } catch (Exception e) {
            putLog("Reviewer setOwner invoke failed: " + e.getMessage(), LogLevel.ERROR);
        }
    }

    @Override
    protected TLBaseModule init() {
        // 如果没有显式配置审查人，默认使用 ConsoleReviewer
        if (reviewer == null) {
            this.reviewer = new ConsoleReviewer();
            ((ConsoleReviewer) reviewer).setOwner(this);
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
            case APPROVAL_PENDINGLIST:
                return handlePendingList(fromWho, msg);
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
     * 订阅者（如 TLChatConsole、TLWebChatModule）自行渲染；text 为渲染好的展示文本，
     * 结构化参数（approvalId/toolName/description/sessionId/args）供 Web 端弹框按钮使用，
     * 控制台只读 text，向后兼容。
     */
    public boolean publishApprovalEvent(String text) {
        return publishApprovalEvent(text, null);
    }

    /**
     * 经消息总线发布审批事件（ConsoleReviewer 打印审批框前调用）。
     * 订阅者（如 TLChatConsole、TLWebChatModule）自行渲染；text 为渲染好的展示文本，
     * 结构化参数（approvalId/toolName/description/sessionId/args）供 Web 端弹框按钮使用，
     * 控制台只读 text，向后兼容。
     */
    public boolean publishApprovalEvent(String text, TLApprovalRequest request) {
        try {
            Object bus = getModuleInFactory("msgBus");
            if (!(bus instanceof IObject)) return false;
            TLMsg evt = createMsg().setAction("approvalEvent").setParam("text", text);
            if (request != null) {
                evt.setParam("approvalId", request.getApprovalId());
                evt.setParam("toolName", request.getToolName());
                evt.setParam("description", request.getDescription());
                evt.setParam("sessionId", request.getSessionId() != null ? request.getSessionId() : request.getRootSessionId());
                if (request.getToolArguments() != null) {
                    evt.setParam("args", new com.google.gson.Gson().toJson(request.getToolArguments()));
                }
            }
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
        String sessionId = String.valueOf(msg.getSystemParam(AI_P_SESSIONID, ""));
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
        request.setRoundId(String.valueOf(msg.getSystemParam(AI_P_ROUNDID, "")));
        request.setRootSessionId(String.valueOf(msg.getSystemParam("rootSessionId", sessionId)));
        // 命中规则的人可读说明（弹框展示）
        String ruleDesc = findMatchedRuleDescription(toolName);
        if (ruleDesc != null) request.setDescription(ruleDesc);
        request.setOperation(operation);
        request.setRiskLevel(riskLevel);
        request.setRationale(rationale);
        request.setTimeoutMs(approvalTimeoutMs);
        evictDecidedApprovals();       // 先腾地方，避免 pendingApprovals 只增不减
        pendingApprovals.put(request.getApprovalId(), request);

        // 4. 排期超时
        scheduleTimeout(request.getApprovalId(), approvalTimeoutMs);

        putLog("Approval request created: id=" + request.getApprovalId()
                + " tool=" + toolName + " risk=" + riskLevel + " session=" + sessionId, LogLevel.INFO);
        // 全链追踪：审批请求发起
        traceStage(sessionId, request.getRootSessionId(), request.getRoundId(), "approvalRequested",
                "id=" + request.getApprovalId() + " tool=" + toolName + " risk=" + riskLevel, 0);

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
        traceStage(request.getSessionId(), request.getRootSessionId(), request.getRoundId(),
                "approvalDecided", "id=" + approvalId + " approved", 0);

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
        traceStage(request.getSessionId(), request.getRootSessionId(), request.getRoundId(),
                "approvalDecided", "id=" + approvalId + " rejected", 0);

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

    /**
     * 查询全部未决审批（PENDING）的结构化列表。
     * 用户重连 /api/events 时 Web 端调用，重放审批弹框（审批事件只发布一次，断线后需补推）。
     */
    private TLMsg handlePendingList(Object fromWho, TLMsg msg) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TLApprovalRequest request : pendingApprovals.values()) {
            if (!TLApprovalRequest.PENDING.equals(request.getState())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("approvalId", request.getApprovalId());
            m.put("toolName", request.getToolName());
            m.put("description", request.getDescription() != null ? request.getDescription() : "");
            m.put("sessionId", request.getSessionId() != null ? request.getSessionId() : request.getRootSessionId());
            m.put("args", request.getToolArguments() != null
                    ? new com.google.gson.Gson().toJson(request.getToolArguments()) : "{}");
            m.put("text", buildPendingPrompt(request));
            list.add(m);
        }
        return createMsg().setParam("pendingList", list);
    }

    // ======================== 规则匹配 ========================

    /** 构建拒绝记忆键：sessionId|toolName|排序后的参数 */
    /** 全链追踪打点：发 recordStage 给监控模块（未配监控时静默忽略，IGNOREMODULEISNULL） */
    private void traceStage(String sessionId, String rootSessionId, String roundId,
                            String stage, String detail, long durationMs) {
        try {
            TLMsg traceMsg = createMsg().setAction("recordStage")
                    .setParam("agentName", getName())
                    .setParam(AI_P_SESSIONID, sessionId)
                    .setParam("rootSessionId", rootSessionId != null ? rootSessionId : sessionId)
                    .setParam(AI_P_ROUNDID, roundId != null ? roundId : "")
                    .setParam("stage", stage)
                    .setParam("detail", TLAgentMonitor.sanitizeDetail(detail))
                    .setParam("durationMs", durationMs);
            traceMsg.setSystemParam(IGNOREMODULEISNULL, true);
            putMsg(M_AGENTMONITOR, traceMsg);
        } catch (Exception ignored) {}
    }

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
        if (approvalRules.isEmpty() && approvalRulePrefixes.isEmpty()) {
            return false;  // 未配置规则，全部放行
        }

        // 1. 通配前缀匹配（tool:* 命中 "tool:xxx" 冒号风格函数名；tool* 命中任意前缀，如 MCP 的 agentName_toolName）
        for (String prefix : approvalRulePrefixes) {
            if (toolName.startsWith(prefix)) {
                return true;
            }
        }

        // 2. 精确匹配 toolName
        Set<String> ops = approvalRules.get(toolName);
        if (ops == null) {
            return false;  // 不在规则中
        }

        // 3. 空 Set = 该工具的所有操作都需审批
        if (ops.isEmpty()) {
            return true;
        }

        // 4. 按操作名匹配
        String operation = extractOperation(toolName, args);
        if (operation == null) {
            // 规则里配了具体操作、却提取不到操作名（如 script_execution/code_execution 的参数不在
            // operation/method/action 三个键里），原来的 `operation != null && ...` 会直接判成
            // "不需审批" —— 规则配了 action 反而等于放行。这里取安全侧：提取不到就当需要审批。
            putLog("审批规则命中了工具 " + toolName + "，但提取不到操作名，按需要审批处理", LogLevel.WARN);
            return true;
        }
        return ops.contains(operation);
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
                // http_request 的 method 参数：GET/POST/PUT/DELETE。
                // 规则侧统一转小写（见 parseRules），这里必须同样小写 —— 原来返回大写，
                // 导致 <rule name="http_request" action="POST"/> 永远匹配不上、请求被静默放行
                Object method = args.get("method");
                return method != null ? method.toString().toLowerCase() : null;
            case "script_execution":
            case "scriptExecutionSkill":
                // 无细分操作：返回 null，由调用方按"提取不到就需审批"处理（安全侧）
                return null;
            default:
                // 其他工具：尝试通用 operation/method/action 参数（统一小写，与规则侧一致）
                if (args.containsKey("operation")) {
                    return args.get("operation").toString().toLowerCase();
                }
                if (args.containsKey("method")) {
                    return args.get("method").toString().toLowerCase();
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
     * 通配形式：
     *   "file_operation:*" → 裸名 file_operation 全部操作 + "file_operation:" 前缀函数名
     *   "filesystemMcp*"   → 任意以 filesystemMcp 开头的函数名（如 filesystemMcp_read_file）
     * }</pre>
     */
    private void parseApprovalRules(String rulesStr) {
        approvalRules.clear();
        approvalRulePrefixes.clear();
        if (rulesStr == null || rulesStr.trim().isEmpty()) return;

        for (String rule : rulesStr.split(",")) {
            rule = rule.trim();
            if (rule.isEmpty()) continue;

            int colonIdx = rule.indexOf(':');
            if (colonIdx > 0) {
                String tool = rule.substring(0, colonIdx).trim();
                String op = rule.substring(colonIdx + 1).trim().toLowerCase();
                if ("*".equals(op)) {
                    // tool:* 通配：裸名全部操作 + 冒号风格函数名前缀
                    approvalRulePrefixes.add(tool + ":");
                    approvalRules.putIfAbsent(tool, Collections.emptySet());
                } else {
                    approvalRules.computeIfAbsent(tool, k -> new LinkedHashSet<>()).add(op);
                }
            } else if (rule.endsWith("*")) {
                // tool* 前缀通配（MCP 的 agentName_toolName 函数名风格）
                approvalRulePrefixes.add(rule.substring(0, rule.length() - 1));
            } else {
                // 无冒号 = 该工具全部操作都需审批
                approvalRules.put(rule, Collections.emptySet());
            }
        }

        putLog("Approval rules parsed: " + approvalRules
                + (approvalRulePrefixes.isEmpty() ? "" : " prefixes=" + approvalRulePrefixes), LogLevel.DEBUG);
    }

    /** 结构化 &lt;rules&gt; 段 → 匹配结构（复用旧结构的 approvalRules/approvalRulePrefixes + description 表） */
    private void applyStructuredRules() {
        approvalRules.clear();
        approvalRulePrefixes.clear();
        ruleDescriptions.clear();
        for (Map.Entry<String, HashMap<String, String>> e : rulesConfig.entrySet()) {
            String name = e.getKey().trim();
            HashMap<String, String> attrs = e.getValue();
            if (name.isEmpty()) continue;
            if (!TLDataUtils.parseBoolean(attrs.get("statup"), true)) continue;

            String desc = attrs.get("description");
            if (desc != null && !desc.trim().isEmpty()) {
                ruleDescriptions.put(name, desc.trim());
            }

            if (name.endsWith(":*")) {
                // tool:* 通配：裸名全部操作 + 冒号风格函数名前缀
                String tool = name.substring(0, name.length() - 2).trim();
                approvalRulePrefixes.add(tool + ":");
                approvalRules.putIfAbsent(tool, Collections.emptySet());
                continue;
            }
            if (name.endsWith("*")) {
                // tool* 前缀通配（MCP 的 agentName_toolName 函数名风格）
                approvalRulePrefixes.add(name.substring(0, name.length() - 1));
                continue;
            }
            String actionAttr = attrs.get("action");
            if (actionAttr != null && !actionAttr.trim().isEmpty()) {
                // action="delete,write" → 指定操作集合
                Set<String> ops = new LinkedHashSet<>();
                for (String op : actionAttr.split(",")) {
                    String t = op.trim().toLowerCase();
                    if (!t.isEmpty()) ops.add(t);
                }
                approvalRules.put(name, ops);
            } else {
                // rule="*" 或两者都无 → 该工具全部操作
                approvalRules.put(name, Collections.emptySet());
            }
        }
        putLog("Structured approval rules applied: " + approvalRules
                + (approvalRulePrefixes.isEmpty() ? "" : " prefixes=" + approvalRulePrefixes), LogLevel.DEBUG);
    }

    /** 查命中规则的人可读说明（弹框展示）：前缀规则优先，其次精确名 */
    private String findMatchedRuleDescription(String toolName) {
        for (String prefix : approvalRulePrefixes) {
            if (toolName.startsWith(prefix)) {
                String ruleName = prefix.endsWith(":") ? prefix.substring(0, prefix.length() - 1) : prefix;
                String d = ruleDescriptions.get(ruleName);
                if (d != null) return d;
            }
        }
        return ruleDescriptions.get(toolName);
    }

    // ======================== 内部配置解析类 ========================

    /** 解析 &lt;rules&gt; 段（getHashMap 以 name 属性为键、其余属性为值） */
    protected class myConfig extends TLModuleConfig {
        private HashMap<String, HashMap<String, String>> rules;

        public myConfig() {}
        public myConfig(String configFile, String configDir) { super(configFile, configDir); }

        public HashMap<String, HashMap<String, String>> getRules() { return rules; }

        @Override
        protected void myConfig(org.xmlpull.v1.XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if (xpp.getName().equals("rules")) {
                    rules = getHashMap(xpp, "rules", "rule");
                }
            } catch (Throwable t) {
                putLog("approvalGate rules parse error: " + t.toString(), LogLevel.WARN);
            }
        }
    }

    /**
     * 清理已决策的审批请求。
     * <p>
     * {@code pendingApprovals} 原来只增不减（handleApprove / handleReject / 超时任务都不 remove），
     * 每个请求永久占一条 entry、还带着完整的 toolArgs。这里在每次登记新请求时顺手淘汰一批
     * <b>非 PENDING</b> 的旧条目 —— 待审批的绝不淘汰，所以不会影响在途审批。
     */
    private void evictDecidedApprovals() {
        if (pendingApprovals.size() <= MAX_PENDING_APPROVALS)
            return;
        for (Map.Entry<String, TLApprovalRequest> e : pendingApprovals.entrySet()) {
            if (pendingApprovals.size() <= MAX_PENDING_APPROVALS)
                break;
            if (!TLApprovalRequest.PENDING.equals(e.getValue().getState()))
                pendingApprovals.remove(e.getKey(), e.getValue());
        }
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
                // 原来只改状态不唤醒：等待线程要一直挂到自己的 latch 超时才返回，
                // 两条超时路径各走各的（且都会把状态设一遍）
                signalDecision(approvalId);
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
        // 先把挂在 waitForDecision 上的线程全部唤醒：调度器被 shutdown 后超时任务不会再触发，
        // 不唤醒的话这些线程要一直等到自己的 latch 超时（默认 5 分钟）才返回，关停会卡住
        for (java.util.concurrent.CountDownLatch latch : decisionLatches.values()) {
            latch.countDown();
        }
        return super.destroy(fromWho, msg);
    }
}
