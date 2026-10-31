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
 *   approvalTimeoutMs  — 阻塞等待上限毫秒数，默认 0（一直等，0 = 无超时）
 * }</pre>
 *
 * <h3>消息接口</h3>
 * <pre>{@code
 *   approvalRequest(toolName, toolArgs, sessionId, toolCallId) → approved/rejected/pending
 *   approvalApprove(approvalId, approvalModifiedArguments?)        → 批准
 *   approvalReject(approvalId, approvalRejectReason?)             → 拒绝
 *   approvalQuery(approvalId)                                     → 查询状态
 *   approvalGrants(sessionId?)                                    → 列出授权
 *   approvalRevoke(sessionId, ruleId? | all=true)                 → 撤销授权
 *   approvalGrant(sessionId, ruleId, operation?)                  → 主动预授权
 *   approvalTaskSession(execSession, parentSessionId?)            → 任务会话→发起会话 映射登记/注销
 *   approvalInfo()                                                → 审查人/配置自述
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

    /** 前缀 → 配置里的规则名（ruleId 归一用；解析规则时填充，与 approvalRulePrefixes 同步） */
    private final Map<String, String> prefixRuleNames = new LinkedHashMap<>();

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

    /** 阻塞等待上限（毫秒）。0 = 一直等（默认——在线挂着的审批等人回来，设计语义）；断点态兜底另见 approvalPendingTtlMs */
    private long approvalTimeoutMs = 0L;

    /** 断点态审批 TTL（毫秒，默认 24h，仅作用于 deferred 审批；阻塞态不受影响）。0 = 不设 TTL */
    private long approvalPendingTtlMs = 86_400_000L;

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

    /** 拒绝记忆键的序列化器：保留值类型（Integer 1 与 String "1" 不能混成同一个键） */
    private static final com.google.gson.Gson KEY_GSON = new com.google.gson.Gson();

    // ======================== 授权记忆（会话/轮次级免审） ========================

    /** 授权表：key = grantKey(resolve(sessionId), scope, roundId, ruleId, operation) */
    private final Map<String, ApprovalGrant> approvedGrants =
            java.util.Collections.synchronizedMap(new LinkedHashMap<String, ApprovalGrant>());
    /** 授权表上限（超限驱逐最旧（插入序 FIFO）；同 rejectedOps 的既有模式） */
    private static final int MAX_APPROVED_GRANTS = 200;

    /** 任务执行会话 → 发起会话（TLScheduleTaskSkill 登记；授权归一化到对话根用） */
    private final Map<String, String> taskSessionParents =
            java.util.Collections.synchronizedMap(new LinkedHashMap<String, String>());
    private static final int MAX_TASK_SESSIONS = 200;

    /** 决策信号：approvalId → CountDownLatch（同步审查人阻塞在此，handleApprove/handleReject 信号唤醒） */
    private final ConcurrentHashMap<String, java.util.concurrent.CountDownLatch> decisionLatches = new ConcurrentHashMap<>();

    /** 创建时排期的审批超时任务：defer（转断点）后阻塞等待已结束，该计时器不再适用——必须取消，
     *  否则非零 approvalTimeoutMs 配置下断点审批仍会被创建时限杀掉（TTL 才应交由 approvalPendingTtlMs） */
    private final ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<?>> timeoutTasks = new ConcurrentHashMap<>();

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
            if (params.get("approvalPendingTtlMs") != null) {
                try { approvalPendingTtlMs = Long.parseLong(params.get("approvalPendingTtlMs").toString()); }
                catch (NumberFormatException e) {
                    putLog("approvalPendingTtlMs 非法（需为毫秒数）: " + params.get("approvalPendingTtlMs"), LogLevel.WARN);
                }
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
            case APPROVAL_DEFER:
                return handleDefer(fromWho, msg);
            case APPROVAL_ABANDON:
                return handleAbandon(fromWho, msg);
            case APPROVAL_INFO:
                // infoOk 是"接线探针"：测试层据此区分"审批模块不可用（接线回归）"与"环境不匹配"，
                // 返回 null 会让用例静默 SKIP —— 而基线比较器忽略跳过，"全绿"就不再证明授权可用
                return createMsg().setParam(RESULT, true)
                        .setParam("infoOk", true)
                        .setParam("reviewerClass", reviewerClass != null ? reviewerClass : "")
                        .setParam("reviewerInstance", reviewer != null ? reviewer.getClass().getName() : "");
            case APPROVAL_GRANTS:
                return handleGrants(fromWho, msg);
            case APPROVAL_REVOKE:
                return handleRevoke(fromWho, msg);
            case APPROVAL_GRANT:
                return handleGrantPregrant(fromWho, msg);
            case APPROVAL_TASKSESSION:
                return handleTaskSession(fromWho, msg);
            default:
                return null;
        }
    }

    // ======================== 同步等待（供 ConsoleReviewer 调用） ========================

    /** 时长文案：整秒不带小数（"3秒"），毫秒级保留一位（"0.3秒"）——探针文案与真实等待一致 */
    private static String fmtSeconds(long ms) {
        return ms % 1000 == 0 ? (ms / 1000) + "秒"
                : String.format(java.util.Locale.ROOT, "%.1f秒", ms / 1000.0);
    }

    /**
     * 阻塞等待审批决策（ConsoleReviewer 调用，代替原先的读 stdin）。
     * 由 {@link #handleApprove} / {@link #handleReject} 信号唤醒。
     */
    public TLMsg waitForDecision(TLApprovalRequest request) {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        decisionLatches.put(request.getApprovalId(), latch);
        try {
            // 注册后先复查状态：决策可能在本 latch 注册前就已发生（publish 与 wait 之间的窗口），
            // 那次 signalDecision 会打空——不复查就要白等到超时（或永远）
            boolean decided;
            synchronized (request) {
                decided = !TLApprovalRequest.PENDING.equals(request.getState());
            }
            if (decided) return buildApprovalResponse(request, null);

            // 等待时长：请求级优先，缺省用模块级；0 = 一直等（latch.await() 无超时）
            long waitMs = request.getTimeoutMs() > 0 ? request.getTimeoutMs() : approvalTimeoutMs;
            boolean ok;
            if (waitMs > 0) {
                ok = latch.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } else {
                latch.await();
                ok = true;
            }
            if (!ok) {
                markExpired(request, "审批超时（" + fmtSeconds(waitMs) + "）");
            } else if (Thread.currentThread().isInterrupted()) {
                markInterrupted(request);
            }
        } catch (InterruptedException e) {
            markInterrupted(request);
            Thread.currentThread().interrupt();
        } finally {
            decisionLatches.remove(request.getApprovalId());
        }
        return buildApprovalResponse(request, null);
    }

    /** 中断收口：只有仍未决才置 REJECTED——已批准/已超时的请求不能被"审批被中断"覆盖 */
    private void markInterrupted(TLApprovalRequest request) {
        synchronized (request) {
            if (!TLApprovalRequest.PENDING.equals(request.getState())) return;
            request.setState(TLApprovalRequest.REJECTED);
            request.setRejectionReason("审批被中断");
        }
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
                // 2026-10-13：事件带 userId 供订阅方按用户过滤。此前不带 userId，webui 端只能按
                // sessionOwner 反查属主——控制台会话不在 webui 的 sessionOwner 里 → 属主为空 →
                // 旧实现回退"广播给所有用户"，控制台的审批会串到所有 webui 用户（不同账号）。
                if (request.getUserId() != null && !request.getUserId().isEmpty()) {
                    evt.setParam("userId", request.getUserId());
                }
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

    /**
     * 审批结束（批准/拒绝/超时）后经消息总线发布关框事件。
     *
     * <p>审批弹框此前只有"自己点击成功"一条关闭路径：请求被别处解决（另一窗口、
     * 退出登录即拒）或超时后，弹框仍留在屏幕上，再点任何按钮都报 already/not found，
     * 只能刷新页面。本事件让所有打开该审批弹框的页面立即自行关闭。
     *
     * <p>与 approvalEvent 同口径带 userId 供订阅方按用户路由；无 msgBus / 无订阅者时静默
     * （控制台没有常驻弹框，不消费该事件）。
     */
    private void publishApprovalResolved(TLApprovalRequest request) {
        if (request == null || request.getApprovalId() == null) return;
        try {
            Object bus = getModuleInFactory("msgBus");
            if (!(bus instanceof IObject)) return;
            TLMsg evt = createMsg().setAction("approvalResolved")
                    .setParam("approvalId", request.getApprovalId())
                    .setParam("state", request.getState() == null ? "" : request.getState());
            // 结束原因随事件带出（如"审批被中断"/超时秒数）：前端弹框提示据此说清死因，
            // 而不是把"被工具超时中断"笼统显示成"已在其他窗口拒绝"
            if (request.getRejectionReason() != null && !request.getRejectionReason().isEmpty()) {
                evt.setParam("reason", request.getRejectionReason());
            }
            if (request.getSessionId() != null && !request.getSessionId().isEmpty()) {
                evt.setParam("sessionId", request.getSessionId());
            }
            if (request.getUserId() != null && !request.getUserId().isEmpty()) {
                evt.setParam("userId", request.getUserId());
            }
            evt.setDestination("approvalResolved");
            putMsg((IObject) bus, evt);
        } catch (Exception e) {
            putLog("publish approval resolved event failed: " + e, LogLevel.DEBUG);
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

        // 用户可见的根会话：子 agent 的 sessionId 是 "组_成员:根会话"，授权/拒绝记忆必须按根会话，
        // 否则主会话里做的"本会话免审/拒绝记忆"对子 agent 永远不命中
        String rootSessionId = String.valueOf(msg.getSystemParam("rootSessionId", sessionId));

        // 0. 会话级拒绝记忆：该（会话+工具+参数）组合已被拒绝过 → 直接返回拒绝，不再弹审批
        String rejectedKey = rejectionKey(resolveSession(rootSessionId), toolName, toolArgs);
        String rejectedReason = rejectedOps.get(rejectedKey);
        if (rejectedReason != null) {
            putLog("Approval auto-rejected (previously rejected in this session): "
                    + toolName + " session=" + rootSessionId, LogLevel.INFO);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_APPROVAL_STATE, TLApprovalRequest.REJECTED)
                    .setParam(AI_P_APPROVAL_REJECTREASON, rejectedReason)
                    .setParam(AI_P_SKILLOUTPUT, buildRejectedOutput(rejectedReason));
        }

        String roundIdStr = String.valueOf(msg.getSystemParam(AI_P_ROUNDID, ""));
        String userId = String.valueOf(msg.getSystemParam("userId", ""));

        // 1. 规则判定 + 2. 授权命中自动批准（规则匹配与操作提取共用一趟）
        RuleMatch match = matchRule(toolName, toolArgs);
        if (!match.required) {
            return createMsg().setParam(AI_P_APPROVAL_STATE, "approved");
        }
        ApprovalGrant hit = findGrant(rootSessionId, roundIdStr, match.ruleId, match.operation);
        if (hit != null) {
            putLog("Approval auto-approved by grant: tool=" + toolName + " rule=" + match.ruleId
                    + " op=" + match.operation + " scope=" + hit.scope + " session=" + rootSessionId, LogLevel.INFO);
            traceStage(sessionId, rootSessionId,
                    roundIdStr, "approvalDecided",
                    "auto-approved(" + hit.scope + (hit.isAutoRun() ? "/wildcard" : "")
                            + ") rule=" + match.ruleId, 0);
            return createMsg().setParam(RESULT, true).setParam(AI_P_APPROVAL_STATE, "approved")
                    .setParam(AI_P_SKILLOUTPUT, "审批通过（已授权）");
        }
        String operation = match.operation;
        String riskLevel = resolveRiskLevel(toolName, operation);

        // 3. 创建审批请求
        TLApprovalRequest request = new TLApprovalRequest(sessionId, toolName, toolArgs, toolCallId);
        request.setRoundId(String.valueOf(msg.getSystemParam(AI_P_ROUNDID, "")));
        request.setRootSessionId(rootSessionId);
        request.setRuleId(match.ruleId);
        request.setUserId(userId);
        // 命中规则的人可读说明（弹框展示）
        String ruleDesc = findMatchedRuleDescription(toolName);
        if (ruleDesc != null) request.setDescription(ruleDesc);
        request.setOperation(operation);
        request.setRiskLevel(riskLevel);
        request.setRationale(rationale);
        request.setTimeoutMs(approvalTimeoutMs);
        evictDecidedApprovals();       // 先腾地方，避免 pendingApprovals 只增不减
        // 全是 PENDING 时 evict 清不掉任何东西（approvalTimeoutMs=0 时也没有 TTL），
        // 达到硬上限后拒绝新请求，防止大量"只创建不决策"把内存撑爆
        if (pendingApprovals.size() >= MAX_PENDING_APPROVALS) {
            String busy = "审批请求过多，请稍后重试";
            putLog("pendingApprovals 达到上限 " + MAX_PENDING_APPROVALS
                    + "，拒绝新请求 tool=" + toolName + " session=" + sessionId, LogLevel.WARN);
            // 这是系统侧过载，不是用户拒绝：REJECTED 会让 agent 往 history 写
            // "用户已拒绝，不要重试"，把本可重试的调用永久放弃。用 UNAVAILABLE
            // 走普通工具错误结果（执行器不认识该控制态 → 结果回喂、LLM 可重试）。
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_APPROVAL_STATE, TLApprovalRequest.UNAVAILABLE)
                    .setParam(AI_P_APPROVAL_REJECTREASON, busy)
                    .setParam(AI_P_SKILLOUTPUT,
                            "⚠️ 审批系统繁忙，本次未执行（不是用户拒绝），请稍后重试。");
        }
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
            if (applyReviewerState(request, state, reviewerResult)) {
                putLog("Approval approved: id=" + request.getApprovalId(), LogLevel.INFO);
            }

        } else if (TLApprovalRequest.REJECTED.equals(state)) {
            if (applyReviewerState(request, state, reviewerResult)) {
                putLog("Approval rejected: id=" + request.getApprovalId()
                        + " reason=" + request.getRejectionReason(), LogLevel.INFO);
            }

        } else if (TLApprovalRequest.PENDING.equals(state)) {
            // pending：无人接收（转断点）或异步审查人已接受——等待登录重放/后续决策。
            // 只有仍未决才转断点：审查人返回的是快照，期间外部决策/超时可能已给出终态
            boolean deferred = false;
            synchronized (request) {
                if (TLApprovalRequest.PENDING.equals(request.getState())) {
                    request.setDeferred(true);
                    // 转断点态：阻塞等待已结束，创建时限不再适用（只由 TTL 兜底）——
                    // 不取消的话非零 approvalTimeoutMs 配置下断点审批仍会被创建时限杀掉
                    java.util.concurrent.ScheduledFuture<?> creationTimer =
                            timeoutTasks.remove(request.getApprovalId());
                    if (creationTimer != null) creationTimer.cancel(false);
                    armPendingTtl(request);
                    deferred = true;
                }
            }
            if (deferred) {
                putLog("Approval pending (deferred): id=" + request.getApprovalId(), LogLevel.INFO);
            }
        }

        if (!TLApprovalRequest.PENDING.equals(state)) {
            // 审查人在这条路径上直接给出/带出了结果（同步审查人决策、执行被中断——用户停止/
            // 工具超时等）——不经 handleApprove/handleReject，必须在此补发关框事件，否则
            // 请求已终结而弹框残留，再点任何按钮都报 already。历史实况：工具 timeout(120s)
            // < 审批窗口时，用户还在看弹框决策就被 TASKTIMEOUT 中断成"审批被中断"，正是此
            // 路径（现 file_operation timeout=0 与 approvalTimeoutMs=0，两者均不限时，
            // 该截断已不可能；但用户停止本轮等中断途径仍会走到这里）。重复发布（handleApprove
            // 已发过）无害：前端对已关闭弹框的二次事件是 no-op。
            publishApprovalResolved(request);
        }

        // 构造返回给 Agent 的 TLMsg
        return buildApprovalResponse(request, reviewerResult);
    }

    /**
     * 应用同步审查人返回的终态（APPROVED/REJECTED）。
     * reviewerResult 是审查人返回时刻的快照：外部决策/超时可能已抢先给出**另一个**终态
     * （如超时任务正好落 EXPIRED），此时不得用旧快照覆盖——只在仍未决或快照与现态一致时写入。
     *
     * @return true = 状态已按审查人结果落定（幂等重写同值也算）
     */
    @SuppressWarnings("unchecked")
    private boolean applyReviewerState(TLApprovalRequest request, String state, TLMsg reviewerResult) {
        synchronized (request) {
            String cur = request.getState();
            if (!TLApprovalRequest.PENDING.equals(cur) && !cur.equals(state)) return false;
            if (TLApprovalRequest.APPROVED.equals(state)) {
                request.setState(TLApprovalRequest.APPROVED);
                request.setModifiedArguments(
                        (Map<String, Object>) reviewerResult.getMapParam(AI_P_APPROVAL_MODIFIEDARGS, null));
            } else if (TLApprovalRequest.REJECTED.equals(state)) {
                request.setState(TLApprovalRequest.REJECTED);
                request.setRejectionReason(
                        reviewerResult.getStringParam(AI_P_APPROVAL_REJECTREASON, "用户拒绝"));
            }
            return true;
        }
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
            // approvalEnded：请求已不在册（进程重启/被驱逐）。弹框侧据此自愈关闭——此前只报错
            // 不关框，弹框就永远卡在屏幕上（点任何按钮都报 already/not found，只能刷新页面）
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "approval not found: " + approvalId)
                    .setParam("approvalEnded", true);
        }

        // 检查-修改必须原子：两个窗口同时点批准、或批准与拒绝并发时，不加锁会让两边都通过
        // PENDING 检查（拒绝后仍写入授权 = 授权与用户拒绝意图矛盾）。同一把 request 锁贯穿
        // handleApprove/handleReject/handleAbandon/markExpired/中断收口。
        ApprovalGrant granted = null;
        synchronized (request) {
            if (!TLApprovalRequest.PENDING.equals(request.getState())) {
                // approvalEnded：已决策/已超时——同上，让弹框自愈关闭
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "approval already " + request.getState())
                        .setParam("approvalEnded", true);
            }

            // 用户可修改参数
            Map<String, Object> modifiedArgs =
                    (Map<String, Object>) msg.getMapParam(AI_P_APPROVAL_MODIFIEDARGS, null);
            request.setModifiedArguments(modifiedArgs);
            String scope = msg.getStringParam(AI_P_APPROVAL_SCOPE, "once");
            if (!"once".equals(scope)) {
                // 授权只记 rule+operation，改后的参数不会用于后续调用：非 once 授权下必须禁止改参数，
                // 否则被改掉的危险参数会在下一次自动批准时"复活"，用户还以为改后的才是被批准的。
                // 提前返回保持请求 PENDING，用户可改用 once 重新批准（语义正确）
                if (modifiedArgs != null && !modifiedArgs.isEmpty()) {
                    return createMsg().setParam(RESULT, false)
                            .setParam("error", "带参数修改的批准仅支持本次（once）：授权只记规则+操作，改后的参数不会用于后续调用");
                }
                if (!ApprovalGrant.SCOPE_ROUND.equals(scope) && !ApprovalGrant.SCOPE_SESSION.equals(scope)) {
                    return createMsg().setParam(RESULT, false)
                            .setParam("error", "未知授权范围: " + scope + "（可用 once/round/session）");
                }
                if (ApprovalGrant.SCOPE_ROUND.equals(scope)
                        && (request.getRoundId() == null || request.getRoundId().isEmpty())) {
                    return createMsg().setParam(RESULT, false)
                            .setParam("error", "该请求无轮次信息，无法按轮授权（可用 once/session）");
                }
                granted = addGrant(scope, request);
            }
            request.setState(TLApprovalRequest.APPROVED);
        }
        timeoutTasks.remove(approvalId);   // 已决策：创建时限任务作废（任务体里还有 PENDING 守卫兜底）
        signalDecision(approvalId);  // 唤醒 ConsoleReviewer
        publishApprovalResolved(request);  // 其他窗口/页面打开的弹框立即关闭
        // 同批多个工具/子 agent 同时申请审批时，后一单在第一单决策前就已排队；
        // 授权写入后把同规则、同会话（轮次授权还要求同轮）的排队请求一并收口，避免"选了免审还弹"
        sweepPendingByGrant(granted, approvalId);
        putLog("Approval approved: id=" + approvalId, LogLevel.INFO);
        traceStage(request.getSessionId(), request.getRootSessionId(), request.getRoundId(),
                "approvalDecided", "id=" + approvalId + " approved", 0);

        // deferred + sessionId 回带：断点审批被重放批准后，调用方据此自动续跑原会话
        return createMsg().setParam(RESULT, true).setParam(AI_P_APPROVAL_STATE, TLApprovalRequest.APPROVED)
                .setParam(AI_P_APPROVAL_DEFERRED, request.isDeferred())
                .setParam("sessionId", request.getSessionId() != null ? request.getSessionId() : request.getRootSessionId());
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
            // approvalEnded：与 handleApprove 同口径，弹框侧自愈关闭
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "approval not found: " + approvalId)
                    .setParam("approvalEnded", true);
        }

        // 与 handleApprove 同一把锁：批准/拒绝并发时只允许先到者生效（见该处注释）
        synchronized (request) {
            if (!TLApprovalRequest.PENDING.equals(request.getState())) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "approval already " + request.getState())
                        .setParam("approvalEnded", true);
            }

            request.setRejectionReason(reason);
            request.setState(TLApprovalRequest.REJECTED);
            // 记入会话级拒绝记忆，防止 LLM 重试同一操作造成连环审批。
            // 放在锁内：只有真正完成 PENDING→REJECTED 迁移民才记（并发批准已生效时不记）
            String rejectSid = request.getRootSessionId() != null && !request.getRootSessionId().isEmpty()
                    ? request.getRootSessionId() : request.getSessionId();
            rememberRejection(resolveSession(rejectSid), request.getToolName(),
                    request.getToolArguments(), reason);
        }
        timeoutTasks.remove(approvalId);   // 已决策：创建时限任务作废（同 handleApprove）
        signalDecision(approvalId);  // 唤醒 ConsoleReviewer
        publishApprovalResolved(request);  // 其他窗口/页面打开的弹框立即关闭
        putLog("Approval rejected: id=" + approvalId + " reason=" + reason, LogLevel.INFO);
        traceStage(request.getSessionId(), request.getRootSessionId(), request.getRoundId(),
                "approvalDecided", "id=" + approvalId + " rejected", 0);

        // deferred + sessionId 回带（与 handleApprove 同口径）：前端拒绝后同样自动续跑原会话
        return createMsg().setParam(RESULT, true).setParam(AI_P_APPROVAL_STATE, TLApprovalRequest.REJECTED)
                .setParam(AI_P_APPROVAL_DEFERRED, request.isDeferred())
                .setParam("sessionId", request.getSessionId() != null ? request.getSessionId() : request.getRootSessionId());
    }

    /**
     * 放弃审批（用户主动丢弃该操作）：置 REJECTED + 唤醒/关框，但【不记拒绝记忆】——
     * 放弃 ≠ 拒绝：拒绝记忆会让后续同参数操作永不弹框，放弃只是本次不做。
     */
    private TLMsg handleAbandon(Object fromWho, TLMsg msg) {
        String approvalId = msg.getStringParam(AI_P_APPROVAL_ID, "");
        if (approvalId.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "missing approvalId");
        }
        TLApprovalRequest request = pendingApprovals.get(approvalId);
        if (request == null) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "approval not found: " + approvalId)
                    .setParam("approvalEnded", true);
        }
        synchronized (request) {
            if (!TLApprovalRequest.PENDING.equals(request.getState())) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "approval already " + request.getState())
                        .setParam("approvalEnded", true);
            }
            request.setRejectionReason("用户放弃该操作");
            request.setState(TLApprovalRequest.REJECTED);
        }
        timeoutTasks.remove(approvalId);   // 已决策：创建时限任务作废（同 handleApprove/Reject）
        signalDecision(approvalId);
        publishApprovalResolved(request);   // 其他窗口的弹框立即关闭
        putLog("Approval abandoned: id=" + approvalId + " tool=" + request.getToolName(), LogLevel.INFO);
        traceStage(request.getSessionId(), request.getRootSessionId(), request.getRoundId(),
                "approvalDecided", "id=" + approvalId + " abandoned", 0);
        return createMsg().setParam(RESULT, true).setParam(AI_P_APPROVAL_STATE, TLApprovalRequest.REJECTED);
    }

    /**
     * 查询审批状态（断点恢复时 Agent 调用）。
     * 除状态外附带 resume 决策映射（decision=approved/rejected/pending/lost）——
     * 断点恢复时 Agent 据此续跑，不自行解释状态语义。记录不在册（进程重启/被驱逐）按 lost 如实报。
     */
    private TLMsg handleQuery(Object fromWho, TLMsg msg) {
        String approvalId = msg.getStringParam(AI_P_APPROVAL_ID, "");
        if (approvalId.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "missing approvalId");
        }

        TLApprovalRequest request = pendingApprovals.get(approvalId);
        if (request == null) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_APPROVAL_STATE, "lost")
                    .setParam("decision", "lost")
                    .setParam(AI_P_APPROVAL_REJECTREASON, "审批记录已不存在（服务重启或已清理）");
        }

        TLMsg result = buildApprovalResponse(request, null);
        // 决策映射：expired → lost —— 断点恢复方（agent）据此判断"审批失联/失效"，不得重放该工具；
        // 其余与状态同名（pending/approved/rejected）。deferred 供弹框陈旧提示 / 自动续跑判据
        result.setParam("decision", decisionOf(request));
        if (TLApprovalRequest.PENDING.equals(request.getState())) {
            result.setParam(AI_P_APPROVAL_DEFERRED, request.isDeferred());
        }
        return result;
    }

    /** 审批状态 → resume 决策映射（Agent 侧只认这四种取值） */
    private static String decisionOf(TLApprovalRequest request) {
        switch (request.getState()) {
            case TLApprovalRequest.APPROVED: return "approved";
            case TLApprovalRequest.REJECTED: return "rejected";
            case TLApprovalRequest.EXPIRED:  return "lost";
            default:                          return "pending";
        }
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
            // 重放字段（webui 重连补弹框用）：发起时刻供"陈旧提示"，deferred 供断点态标注
            m.put("createdAt", request.getCreatedAt());
            m.put("deferred", request.isDeferred());
            // 事件自带 userId：webui 端归属过滤据此命中（自定义会话名退出后 sessionOwner 已清，只靠前缀会漏）
            m.put("userId", request.getUserId() != null ? request.getUserId() : "");
            list.add(m);
        }
        return createMsg().setParam("pendingList", list);
    }

    // ======================== 授权管理动作 ========================

    /** 列出授权（sessionId 可选过滤，过滤值做会话归一化；为空 = 全部） */
    private TLMsg handleGrants(Object fromWho, TLMsg msg) {
        // 显式传参必须是"非空字符串"：非 String 会被 getStringParam 静默回落成 ""，
        // 过滤条件消失、列出所有会话的授权（与 revoke 的硬报错方向相反）；显式空串同理
        //（列出全部请直接省略参数）。两种情况错误文案要能区分，别把空串报成"类型非法"。
        if (msg.containsParam("sessionId")) {
            Object rawObj = msg.getParam("sessionId");
            if (!(rawObj instanceof String)) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "sessionId 类型非法（需为字符串）");
            }
            if (((String) rawObj).isEmpty()) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "sessionId 不能为空串（列出全部请省略该参数）");
            }
        }
        String rawFilter = msg.getStringParam("sessionId", "");
        String filter = rawFilter.isEmpty() ? "" : resolveSession(rawFilter);
        List<Map<String, Object>> list = new ArrayList<>();
        synchronized (approvedGrants) {
            for (ApprovalGrant g : approvedGrants.values()) {
                if (!filter.isEmpty() && !filter.equals(g.sessionId)) continue;
                list.add(g.toMap());
            }
        }
        return createMsg().setParam(RESULT, true).setParam("grants", list);
    }

    /**
     * 撤销授权：sessionId 必填（归一化后比较）；all=true 清该会话全部；
     * 否则按 ruleId(+operation) 删（operation 缺省 = 该规则下全部操作，含规则级/通配记录）。
     * 返回删除条数。
     */
    private TLMsg handleRevoke(Object fromWho, TLMsg msg) {
        String rawSid = msg.getStringParam("sessionId", "");
        if (rawSid.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "missing sessionId");
        }
        String sessionId = resolveSession(rawSid);
        boolean all = msg.parseBoolean("all", false);
        boolean hasRule = msg.containsParam("ruleId");
        // all=true 时 ruleId 会被静默忽略（日志还照打 rule=xxx）——误传会整会话清空，
        // 这种组合没有合法调用方（清整会话用 all、清清单规则不带 all），直接拒绝
        if (all && hasRule) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "all=true 时不要带 ruleId（清整会话用 all，清单规则不带 all）");
        }
        if (!all && !hasRule) {
            return createMsg().setParam(RESULT, false).setParam("error", "需要 ruleId 或 all=true");
        }
        String ruleId = msg.getStringParam("ruleId", "");
        boolean hasOp = msg.containsParam("operation");
        String operation = msg.getStringParam("operation", "");
        int removed = 0;
        synchronized (approvedGrants) {
            java.util.Iterator<Map.Entry<String, ApprovalGrant>> it = approvedGrants.entrySet().iterator();
            while (it.hasNext()) {
                ApprovalGrant g = it.next().getValue();
                if (!sessionId.equals(g.sessionId)) continue;
                if (!all) {
                    if (!ruleId.equals(g.ruleId)) continue;
                    if (hasOp && !operation.equals(g.operation)) continue;
                }
                it.remove();
                removed++;
            }
        }
        putLog("Grants revoked: session=" + sessionId + " all=" + all
                + " rule=" + ruleId + " removed=" + removed, LogLevel.INFO);
        return createMsg().setParam(RESULT, true).setParam("removed", removed);
    }

    /** 主动预授权：不弹框直接写入授权表（ruleId 必填；operation 缺省 = 该规则全部操作） */
    private TLMsg handleGrantPregrant(Object fromWho, TLMsg msg) {
        String rawSid = msg.getStringParam("sessionId", "");
        String ruleId = msg.getStringParam("ruleId", "");
        if (rawSid.isEmpty() || ruleId.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "需要 sessionId 与 ruleId");
        }
        String operation = msg.containsParam("operation")
                ? msg.getStringParam("operation", "")
                : ApprovalGrant.WILDCARD;
        if (ApprovalGrant.WILDCARD.equals(ruleId) && !ApprovalGrant.WILDCARD.equals(operation)) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "通配规则（ruleId=*）只支持 operation=*（自动运行），否则该授权无法被查询命中");
        }
        // 死路守卫：matchRule 返回的 ruleId 只可能取自 approvalRules.keySet() 或 prefixRuleNames.values()，
        // 拼错（如写成工具名/配置里没有的规则）的授权永不命中、却在清单里报成功——直接拒绝
        if (!ApprovalGrant.WILDCARD.equals(ruleId)) {
            boolean known = approvalRules.containsKey(ruleId) || prefixRuleNames.containsValue(ruleId);
            if (!known) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "规则不在册: " + ruleId + "（永不命中的授权，已拒绝）");
            }
        }
        // 操作名与 extractOperation/规则解析一致统一小写（operation="WRITE" 原样入库会永不命中）
        if (operation != null && !operation.isEmpty()) operation = operation.toLowerCase();
        // 操作在册校验：精确规则配了操作集合时，操作名必须在集合内（拼错=永不命中的假成功）。
        // 跳过：ruleId/operation 为通配（*）、或规则是前缀/通配形态（approvalRules 里查无集合）、
        // 或规则没配操作集合（空 Set = 全部操作，任意操作名都合法）。用小写归一后的值比较，
        // 与 findGrant 侧操作名（matchRule 提取时已小写）同一口径。
        if (!ApprovalGrant.WILDCARD.equals(ruleId) && !ApprovalGrant.WILDCARD.equals(operation)) {
            Set<String> ruleOps = approvalRules.get(ruleId);
            if (ruleOps != null && !ruleOps.isEmpty() && !ruleOps.contains(operation)) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "操作不在规则的 action 集合内: " + ruleId + ":" + operation
                                + "（永不命中的授权，已拒绝）");
            }
        }
        ApprovalGrant g = new ApprovalGrant();
        g.sessionId = resolveSession(rawSid);
        g.scope = ApprovalGrant.SCOPE_SESSION;
        g.roundId = "";
        g.ruleId = ruleId;
        g.operation = operation;
        g.toolName = "";   // toMap 的占位约定：空串（预授权没有"授予时命中的工具名"）
        g.grantedAt = System.currentTimeMillis();
        putGrant(g);
        // 预授权（/auto、/permissions grant）同样回收已排队的 pending：否则用户点了"自动运行"，
        // 屏幕上已弹出的审批框仍会要求逐单决策
        sweepPendingByGrant(g, null);
        putLog("Grant pre-granted: rule=" + ruleId + " op=" + g.operation + " session=" + g.sessionId, LogLevel.INFO);
        return createMsg().setParam(RESULT, true);
    }

    /** 任务会话 → 发起会话 映射登记/注销（parentSessionId 为空 = 注销） */
    private TLMsg handleTaskSession(Object fromWho, TLMsg msg) {
        String execSession = msg.getStringParam("execSession", "");
        if (execSession.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "missing execSession");
        }
        String parent = msg.getStringParam("parentSessionId", "");
        if (parent.isEmpty()) {
            taskSessionParents.remove(execSession);
        } else {
            synchronized (taskSessionParents) {
                if (taskSessionParents.size() >= MAX_TASK_SESSIONS) {
                    taskSessionParents.remove(taskSessionParents.keySet().iterator().next());
                }
                taskSessionParents.put(execSession, parent);
            }
        }
        return createMsg().setParam(RESULT, true);
    }

    // ======================== 授权表 ========================

    /**
     * 会话归一化：任务执行会话解析为其发起会话（授权归一化到对话根），其他会话返回自身。
     * 前提：映射须在授权写入前已登记（写入侧已归一化；事后登记不会迁移既有记录的键）。
     */
    private String resolveSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return "";
        String parent = taskSessionParents.get(sessionId);
        if (parent != null && !parent.isEmpty())
            return parent;
        // 派生会话统一归到用户可见的根会话：组/工作流成员用 "组成员:根会话"，
        // 授权若按派生会话存取，主会话里点的"本会话免审"对子 agent 永远不命中
        int idx = sessionId.lastIndexOf(':');
        if (idx >= 0 && idx < sessionId.length() - 1)
            return sessionId.substring(idx + 1);
        return sessionId;
    }

    /** 授权键（写入与查询必须走同一构造） */
    private String grantKey(String sessionId, String scope, String roundId, String ruleId, String operation) {
        return sessionId + "|" + scope + "|" + (roundId == null ? "" : roundId) + "|"
                + (ruleId == null ? "" : ruleId) + "|" + (operation == null ? "" : operation);
    }

    /** 授权入表：超上限驱逐最旧（插入序）；key 统一由 grantKey 构造 */
    private void putGrant(ApprovalGrant g) {
        synchronized (approvedGrants) {
            if (approvedGrants.size() >= MAX_APPROVED_GRANTS) {
                approvedGrants.remove(approvedGrants.keySet().iterator().next());
            }
            approvedGrants.put(grantKey(g.sessionId, g.scope, g.roundId, g.ruleId, g.operation), g);
        }
    }

    /**
     * 授权命中判定：先查通配（自动运行），再精确 规则+操作，最后规则级（预授权不指定操作 = 全操作）；
     * 会话级优先，轮次级需 roundId 非空。返回命中的授权或 null。
     */
    private ApprovalGrant findGrant(String sessionId, String roundId, String ruleId, String operation) {
        String sid = resolveSession(sessionId);
        String[] scopes = { ApprovalGrant.SCOPE_SESSION, ApprovalGrant.SCOPE_ROUND };
        for (String scope : scopes) {
            boolean isRound = ApprovalGrant.SCOPE_ROUND.equals(scope);
            if (isRound && (roundId == null || roundId.isEmpty())) continue;
            String rd = isRound ? roundId : "";
            ApprovalGrant g = approvedGrants.get(
                    grantKey(sid, scope, rd, ApprovalGrant.WILDCARD, ApprovalGrant.WILDCARD));
            if (g != null) return g;
            g = approvedGrants.get(grantKey(sid, scope, rd, ruleId, operation));
            if (g != null) return g;
            g = approvedGrants.get(grantKey(sid, scope, rd, ruleId, ApprovalGrant.WILDCARD));
            if (g != null) return g;
        }
        return null;
    }

    /** 写入一条授权（幂等覆盖；超上限驱逐最旧）。返回写入的授权，供回收已排队 pending 用。 */
    private ApprovalGrant addGrant(String scope, TLApprovalRequest request) {
        ApprovalGrant g = new ApprovalGrant();
        // 授权按根会话存：子 agent（priceTeam_pizzaAgent:root）与主 agent 共用同一份授权
        String base = request.getRootSessionId();
        if (base == null || base.isEmpty()) base = request.getSessionId();
        g.sessionId = resolveSession(base);
        g.scope = scope;
        g.roundId = ApprovalGrant.SCOPE_ROUND.equals(scope)
                ? (request.getRoundId() == null ? "" : request.getRoundId()) : "";
        g.ruleId = request.getRuleId() == null ? "" : request.getRuleId();
        g.operation = request.getOperation() == null ? "" : request.getOperation();
        g.toolName = request.getToolName();
        g.grantedAt = System.currentTimeMillis();
        putGrant(g);
        putLog("Grant added: scope=" + scope + " rule=" + g.ruleId + " op=" + g.operation
                + " session=" + g.sessionId, LogLevel.INFO);
        return g;
    }

    /**
     * 授权写入后回收同批已排队的 pending：
     * 同一轮里多个工具/子 agent 同时申请审批时，后一单往往在第一单被批准前就已创建。
     * 用户对第一单选择"本轮/本会话"后，这些已排队的同规则请求应直接收口，不再弹第二个框。
     * 离线转断点（deferred）的请求不在这里收口——它们由登录后的重放路径处理。
     */
    private void sweepPendingByGrant(ApprovalGrant g, String excludeApprovalId) {
        if (g == null) return;
        List<TLApprovalRequest> hits = new ArrayList<>();
        for (TLApprovalRequest r : pendingApprovals.values()) {
            if (r == null || r.getApprovalId() == null) continue;
            if (r.getApprovalId().equals(excludeApprovalId)) continue;
            if (r.isDeferred()) continue;
            if (matchesGrant(g, r)) hits.add(r);
        }
        for (TLApprovalRequest r : hits) {
            synchronized (r) {
                if (!TLApprovalRequest.PENDING.equals(r.getState())) continue;
                r.setState(TLApprovalRequest.APPROVED);
            }
            timeoutTasks.remove(r.getApprovalId());
            signalDecision(r.getApprovalId());
            publishApprovalResolved(r);
            putLog("Approval auto-approved (queued pending, scope=" + g.scope + "): id="
                    + r.getApprovalId() + " tool=" + r.getToolName()
                    + " rule=" + r.getRuleId(), LogLevel.INFO);
            traceStage(r.getSessionId(), r.getRootSessionId(), r.getRoundId(), "approvalDecided",
                    "id=" + r.getApprovalId() + " approved(pending-grant)", 0);
        }
    }

    /** 授权是否覆盖一条已排队请求（会话/轮次 + 规则 + 操作，与 findGrant 同口径） */
    private boolean matchesGrant(ApprovalGrant g, TLApprovalRequest r) {
        String rBase = r.getRootSessionId();
        if (rBase == null || rBase.isEmpty()) rBase = r.getSessionId();
        if (!g.sessionId.equals(resolveSession(rBase))) return false;
        if (ApprovalGrant.SCOPE_ROUND.equals(g.scope)) {
            String rRound = r.getRoundId() == null ? "" : r.getRoundId();
            if (!rRound.equals(g.roundId)) return false;
        }
        if (!ApprovalGrant.WILDCARD.equals(g.ruleId)
                && !g.ruleId.equals(r.getRuleId() == null ? "" : r.getRuleId())) return false;
        if (!ApprovalGrant.WILDCARD.equals(g.operation)
                && !g.operation.equals(r.getOperation() == null ? "" : r.getOperation())) return false;
        return true;
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
        // TreeMap.toString() 不区分值类型（Integer 1 与 String "1" 都变 "1"），嵌套 Map 的
        // toString 顺序也不稳定——会造成不同类型的参数误命中拒绝记忆（下次同工具直接拒）。
        // Gson 保留类型；TreeMap 保证顶层键序稳定（写入与查询走同一构造）。
        String argsKey = args != null && !args.isEmpty()
                ? KEY_GSON.toJson(new TreeMap<>(args)) : "{}";
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

    /** 规则匹配结果：是否需审批 + 命中的规则 id + 提取到的操作名（授权记忆的键）。
     *  required=false 时 ruleId/operation 不构成授权依据（ruleId 非空仅表示规则匹配过）。*/
    public static class RuleMatch {
        public boolean required;
        public String ruleId = "";
        public String operation = "";
    }

    /**
     * 一次匹配返回完整结果（规则判定与操作提取本是一趟逻辑，供审批与授权共用）。
     * 语义与旧 isApprovalRequired 逐条对齐：前缀命中即需审批；精确规则按操作集合；
     * 提取不到操作名按安全侧（需审批）。
     */
    public RuleMatch matchRule(String toolName, Map<String, Object> args) {
        RuleMatch r = new RuleMatch();
        if (approvalRules.isEmpty() && approvalRulePrefixes.isEmpty()) {
            return r;  // 未配置规则，全部放行
        }
        // 配了规则但拿不到工具名：无法判断危险等级，按安全侧放行到审批（不能静默绕过）
        if (toolName == null || toolName.isEmpty()) {
            r.required = true;
            r.ruleId = "(unknown)";
            r.operation = "";
            return r;
        }
        // 1. 通配前缀匹配（tool:* 命中 "tool:xxx" 冒号风格函数名；tool* 命中任意前缀，如 MCP 的 agentName_toolName）
        for (String prefix : approvalRulePrefixes) {
            if (toolName.startsWith(prefix)) {
                r.required = true;
                r.ruleId = prefixRuleNames.getOrDefault(prefix, prefix);
                String op = extractOperation(toolName, args);
                // 冒号风格函数名（tool:action）：从函数名里取 action 作为操作名
                if ((op == null || op.isEmpty()) && prefix.endsWith(":")) {
                    String suffix = toolName.substring(prefix.length());
                    if (!suffix.isEmpty()) op = suffix.toLowerCase();
                }
                // 仍取不到操作名（如 MCP 的 agentName_toolName，args 里没有 operation/method/action）：
                // 用完整函数名当区分键。否则同前缀下所有工具共享 (ruleId, "") 授权——
                // 用户批准 filesystemMcp_read_file 后，filesystemMcp_write_file 会被静默放行（授权串联）。
                if (op == null || op.isEmpty()) op = toolName.toLowerCase();
                r.operation = op;
                return r;
            }
        }
        // 2. 精确匹配 toolName
        Set<String> ops = approvalRules.get(toolName);
        if (ops == null) return r;
        r.ruleId = toolName;
        // 3. 空 Set = 该工具的所有操作都需审批
        if (ops.isEmpty()) {
            r.required = true;
            String op = extractOperation(toolName, args);
            r.operation = op != null ? op : "";
            return r;
        }
        // 4. 按操作名匹配
        String operation = extractOperation(toolName, args);
        if (operation == null) {
            // 规则里配了具体操作、却提取不到操作名（如 script_execution/code_execution 的参数不在
            // operation/method/action 三个键里），原来的 `operation != null && ...` 会直接判成
            // "不需审批" —— 规则配了 action 反而等于放行。这里取安全侧：提取不到就当需要审批。
            putLog("审批规则命中了工具 " + toolName + "，但提取不到操作名，按需要审批处理", LogLevel.WARN);
            r.required = true;
            r.operation = "";
            return r;
        }
        if (ops.contains(operation)) {
            r.required = true;
            r.operation = operation;
        }
        return r;
    }

    /**
     * 判断一个工具调用是否需要审批（公开 API，保留签名；实现委托 matchRule）。
     *
     * @param toolName 工具名（function name）
     * @param args     工具参数
     * @return true 需要审批
     */
    public boolean isApprovalRequired(String toolName, Map<String, Object> args) {
        return matchRule(toolName, args).required;
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
                // 其他工具：尝试通用 operation/method/action 参数（统一小写，与规则侧一致）。
                // 值判空而非 containsKey：显式 JSON null（{"operation": null}）在 containsKey 下为 true，
                // 直接 toString() 会 NPE，并被执行器 catch 成"绕开审批"（fail-open）——取安全侧
                Object opVal = args.get("operation");
                if (opVal != null) return opVal.toString().toLowerCase();
                Object methodVal = args.get("method");
                if (methodVal != null) return methodVal.toString().toLowerCase();
                Object actionVal = args.get("action");
                if (actionVal != null) return actionVal.toString().toLowerCase();
                return null;
        }
    }

    /**
     * 确定风险级别。当前进入审批的请求统一为 HIGH（弹框/查询展示该值）；
     * 按规则/操作细分（LOW/MEDIUM/CRITICAL）是预留扩展点，尚未接线——别把它当已生效的配置。
     */
    private String resolveRiskLevel(String toolName, String operation) {
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
        prefixRuleNames.clear();
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
                    prefixRuleNames.put(tool + ":", rule);
                    approvalRules.putIfAbsent(tool, Collections.emptySet());
                } else {
                    approvalRules.computeIfAbsent(tool, k -> new LinkedHashSet<>()).add(op);
                }
            } else if (rule.endsWith("*")) {
                // tool* 前缀通配（MCP 的 agentName_toolName 函数名风格）
                approvalRulePrefixes.add(rule.substring(0, rule.length() - 1));
                prefixRuleNames.put(rule.substring(0, rule.length() - 1), rule);
            } else {
                // 无冒号 = 该工具全部操作都需审批
                approvalRules.put(rule, Collections.emptySet());
            }
        }

        // 最长前缀优先：同时配了 file_ 与 file_op_ 时，file_op_delete 应命中更具体的
        // file_op_（此前命中顺序由配置/哈希顺序决定，谁排前面谁赢）
        approvalRulePrefixes.sort(Comparator.comparingInt(String::length).reversed());
        putLog("Approval rules parsed: " + approvalRules
                + (approvalRulePrefixes.isEmpty() ? "" : " prefixes=" + approvalRulePrefixes), LogLevel.DEBUG);
    }

    /** 结构化 &lt;rules&gt; 段 → 匹配结构（复用旧结构的 approvalRules/approvalRulePrefixes + description 表） */
    private void applyStructuredRules() {
        approvalRules.clear();
        approvalRulePrefixes.clear();
        prefixRuleNames.clear();
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
                prefixRuleNames.put(tool + ":", name);
                approvalRules.putIfAbsent(tool, Collections.emptySet());
                continue;
            }
            if (name.endsWith("*")) {
                // tool* 前缀通配（MCP 的 agentName_toolName 函数名风格）
                approvalRulePrefixes.add(name.substring(0, name.length() - 1));
                prefixRuleNames.put(name.substring(0, name.length() - 1), name);
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
        // 最长前缀优先（同 parseApprovalRules）：rulesConfig 是 HashMap，不排序时命中哪条前缀规则
        // 取决于哈希顺序，更具体的规则可能被更宽的前缀抢走
        approvalRulePrefixes.sort(Comparator.comparingInt(String::length).reversed());
        putLog("Structured approval rules applied: " + approvalRules
                + (approvalRulePrefixes.isEmpty() ? "" : " prefixes=" + approvalRulePrefixes), LogLevel.DEBUG);
    }

    /** 查命中规则的人可读说明（弹框展示）：前缀规则优先，其次精确名 */
    private String findMatchedRuleDescription(String toolName) {
        for (String prefix : approvalRulePrefixes) {
            if (toolName.startsWith(prefix)) {
                String ruleName = prefixRuleNames.getOrDefault(prefix, prefix);
                String d = ruleDescriptions.get(ruleName);
                if (d == null && prefix.endsWith(":")) {
                    d = ruleDescriptions.get(prefix.substring(0, prefix.length() - 1));
                }
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

    /**
     * 统一的"超时失效"收口：改状态 + 唤醒等待线程 + 消息箱告知。
     * 两条超时路径（scheduleTimeout 定时任务 / waitForDecision 的 latch 自超时）都必须走这里。
     */
    private void markExpired(TLApprovalRequest request, String reason) {
        if (request == null) return;
        // 与 handleApprove/handleReject 同一把锁：超时任务与用户决策并发时只允许先到者生效，
        // 否则可能出现"用户看到批准成功但状态已 EXPIRED"，或超时把刚批准的状态覆盖掉
        synchronized (request) {
            if (!TLApprovalRequest.PENDING.equals(request.getState())) return;
            request.setState(TLApprovalRequest.EXPIRED);
            if (request.getRejectionReason() == null || request.getRejectionReason().isEmpty()) {
                request.setRejectionReason(reason);
            }
        }
        timeoutTasks.remove(request.getApprovalId());   // 已失效：创建时限任务作废（任务体里还有 PENDING 守卫兜底）
        signalDecision(request.getApprovalId());
        publishApprovalResolved(request);  // 超时：弹框自行关闭（此前弹框会一直留着，再点就是 already expired）
        putLog("Approval expired: id=" + request.getApprovalId() + " reason=" + request.getRejectionReason(),
                LogLevel.INFO);
        notifyInbox(request, "审批未处理已失效（" + request.getRejectionReason() + "）");
    }

    // ======================== 退出转断点（defer） ========================

    /**
     * 退出登录 / 无人在线：把未决审批转为断点态——保持 PENDING，唤醒阻塞线程（若有）以 pending 返回，
     * 上层据此落断点（线程/会话释放）。幂等：未决才置位；已决策/已失效返回成功（无副作用）。
     */
    private TLMsg handleDefer(Object fromWho, TLMsg msg) {
        String approvalId = msg.getStringParam(AI_P_APPROVAL_ID, "");
        if (approvalId.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "missing approvalId");
        }
        TLApprovalRequest request = pendingApprovals.get(approvalId);
        if (request == null) {
            return createMsg().setParam(RESULT, false).setParam("error", "approval not found: " + approvalId);
        }
        // 检查-修改必须与 handleApprove/handleReject/handleAbandon/markExpired 用同一把
        // request 锁：否则 defer 可能读到 PENDING 后，批准线程把状态改成 APPROVED，
        // defer 再把 deferred 置上（或反之），断点态/批准态互相覆盖。
        synchronized (request) {
            if (!TLApprovalRequest.PENDING.equals(request.getState())) {
                return createMsg().setParam(RESULT, true);   // 已决策/已失效：defer 无意义，幂等成功
            }
            request.setDeferred(true);
            // 创建时排期的超时任务随 defer 作废：阻塞等待已结束，残留的创建时限会把断点态审批
            // 在 approvalTimeoutMs 到点杀掉（重登后可批准的时间就只剩该窗口；现配置为 0=不限时、
            // 本不排期，但非零配置下这一步仍是必要的）；断点期改由 TTL 管
            java.util.concurrent.ScheduledFuture<?> creationTimer = timeoutTasks.remove(approvalId);
            if (creationTimer != null) creationTimer.cancel(false);
            armPendingTtl(request);
        }
        signalDecision(approvalId);   // 唤醒等待线程放到锁外，避免锁内回调
        putLog("Approval deferred: id=" + approvalId + " tool=" + request.getToolName(), LogLevel.INFO);
        return createMsg().setParam(RESULT, true);
    }

    /** 为断点态审批排期 TTL（幂等：请求级 ttlArmed 守卫；ttl<=0 不排队） */
    private void armPendingTtl(TLApprovalRequest request) {
        if (approvalPendingTtlMs <= 0 || request.isTtlArmed()) return;
        if (timeoutScheduler == null || timeoutScheduler.isShutdown()) return;   // destroy 后不再排期
        request.setTtlArmed(true);
        final String approvalId = request.getApprovalId();
        long ttl = approvalPendingTtlMs;
        String ttlText = ttl >= 3_600_000 ? (ttl / 3_600_000) + "小时" : fmtSeconds(ttl);
        try {
            timeoutScheduler.schedule(() -> {
                TLApprovalRequest r = pendingApprovals.get(approvalId);
                if (r != null && TLApprovalRequest.PENDING.equals(r.getState())) {
                    markExpired(r, "超过" + ttlText + "未处理");
                }
            }, ttl, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // destroy 与排期的竞态窗口：调度器已关闭，等待线程由 destroy 的 latch 释放路径唤醒
        }
    }

    /** 审批未被受理而终结时写消息箱（无 webui 模块 / 无 userId 时静默跳过） */
    private void notifyInbox(TLApprovalRequest request, String note) {
        if (request.getUserId() == null || request.getUserId().isEmpty()
                || "null".equals(request.getUserId())) return;
        try {
            Object webui = getModuleInFactory("webui");
            if (!(webui instanceof IObject)) return;
            String text = "⚠️ " + note + "：工具 " + request.getToolName()
                    + (request.getDescription() != null && !request.getDescription().isEmpty()
                            ? "（" + request.getDescription() + "）" : "")
                    + "，会话 " + (request.getSessionId() != null ? request.getSessionId() : "");
            TLMsg m = createMsg().setAction("inboxAdd")
                    .setParam("userId", request.getUserId())
                    .setParam("source", "approval")
                    .setParam("taskId", request.getApprovalId())
                    .setParam("text", text);
            m.setSystemParam(IGNOREMODULEISNULL, true);
            putMsg((IObject) webui, m);
        } catch (Exception e) {
            putLog("审批消息箱通知失败: " + e, LogLevel.DEBUG);
        }
    }

    private void scheduleTimeout(String approvalId, long timeoutMs) {
        if (timeoutMs <= 0) return;
        // 极短超时下任务可能先于 put 执行完：任务体若按 key 直接 remove，会先删空、再被 put
        // 放回一个已完成的 future（表泄漏）。用"任务末尾按 key+自身 future 条件删除"兜住两种
        // 交错：任务先完成 → put 后的 isDone 补删；任务后完成 → finally 自己删。
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>> self =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.ScheduledFuture<?> future = timeoutScheduler.schedule(() -> {
            try {
                TLApprovalRequest request = pendingApprovals.get(approvalId);
                if (request != null && TLApprovalRequest.PENDING.equals(request.getState())) {
                    markExpired(request, "审批超时（" + fmtSeconds(timeoutMs) + "）");
                }
            } finally {
                java.util.concurrent.ScheduledFuture<?> f = self.get();
                if (f != null) timeoutTasks.remove(approvalId, f);
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        self.set(future);
        timeoutTasks.put(approvalId, future);
        if (future.isDone()) timeoutTasks.remove(approvalId, future);
    }

    // ======================== 响应构造 ========================

    /**
     * 构造返回给 Agent 的审批响应。
     */
    @SuppressWarnings("unchecked")
    private TLMsg buildApprovalResponse(TLApprovalRequest request, TLMsg reviewerResult) {
        TLMsg result = createMsg();
        // 只读一次：状态可能被其他线程迁移，两次读之间变化会出现
        // "返回 state=pending 却走 EXPIRED 分支"（RESULT/reason 与 state 自相矛盾）
        String state = request.getState();
        result.setParam(AI_P_APPROVAL_STATE, state);
        result.setParam(AI_P_APPROVAL_ID, request.getApprovalId());
        result.setParam(AI_P_APPROVAL_TOOLNAME, request.getToolName());
        result.setParam(AI_P_APPROVAL_TOOLARGS, request.getToolArguments());

        switch (state) {
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
        // 先把仍 PENDING 的请求终结为 EXPIRED，再唤醒等待线程：否则等待方醒来看到 PENDING，
        // 会在应用关闭过程中按"待审批"路径继续落断点/写会话记录。
        for (TLApprovalRequest req : pendingApprovals.values()) {
            synchronized (req) {
                if (TLApprovalRequest.PENDING.equals(req.getState())) {
                    req.setState(TLApprovalRequest.EXPIRED);
                    req.setRejectionReason("应用关闭");
                }
            }
        }
        // 先把挂在 waitForDecision 上的线程全部唤醒：调度器被 shutdown 后超时任务不会再触发。
        // 默认配置（approvalTimeoutMs=0）下 latch 是无超时的，本循环是这些线程唯一的释放路径——
        // 缺了它，阻塞的审批线程永不返回、关停直接卡死。非 0 配置下线程池 shutdownNow 的中断
        // 只是兜底（线程醒来会把请求标成"审批被中断"，语义不如本循环干净）。
        for (java.util.concurrent.CountDownLatch latch : decisionLatches.values()) {
            latch.countDown();
        }
        return super.destroy(fromWho, msg);
    }
}
