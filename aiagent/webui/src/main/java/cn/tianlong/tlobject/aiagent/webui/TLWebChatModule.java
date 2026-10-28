package cn.tianlong.tlobject.aiagent.webui;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.aiagent.TLAgentMonitor;
import cn.tianlong.tlobject.aiagent.TLAttachmentRef;
import cn.tianlong.tlobject.aiagent.TLConversationHistory;
import cn.tianlong.tlobject.aiagent.TLPathSafety;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.servletutils.TLWServModule;
import cn.tianlong.tlobject.servletutils.clientinterface.TLWebChannel;
import com.google.gson.Gson;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static cn.tianlong.tlobject.servletutils.TLParamString.UPLOADFILE_P_FILESIZE;
import static cn.tianlong.tlobject.servletutils.TLParamString.UPLOADFILE_P_FILEPATH;
import static cn.tianlong.tlobject.servletutils.TLParamString.UPLOADFILE_R_ERROR;
import static cn.tianlong.tlobject.servletutils.TLParamString.UPLOADFILE_R_FILENAMES;
import static cn.tianlong.tlobject.servletutils.TLParamString.UPLOADFILE_R_FILETYPE;
import static cn.tianlong.tlobject.servletutils.TLParamString.UPLOADFILE_R_FILESIZEMAX;
import static cn.tianlong.tlobject.servletutils.TLParamString.UPLOADFILE_UPLOAD;

/**
 * AI Agent Web 交互模块——浏览器 UI 与 Agent 框架之间的桥接层。
 *
 * <p>职责：登录校验、命令分发（JSON → TLMsg → agentService）、流式聊天转发（SSE writer）、
 * 审批事件经 msgBus 订阅后按 sessionId 定位属主推送给对应 SSE 连接。</p>
 *
 * <p>入口：框架路由（TLServletDispatch → TLWUrlMap → checkMsgAction 的
 * session/login/logout/command/chat/stopChat/upload/chatStream/events action，
 * 输出经 sseClient/jsonClient → out-interface）。静态页（/、/webui/*）由
 * TLWebChatServlet 直接服务，不经过本模块。</p>
 *
 * <p>配置参数 (XML params):</p>
 * <ul>
 *   <li>serviceModule — agentService 模块名，默认 "agentService"</li>
 *   <li>userModule    — 用户信息模块（getUserInfo 契约），默认 "demoUserModule"</li>
 *   <li>passwords     — 登录密码表 "uid:pwd;uid:pwd"（配置后登录必须匹配；不配则任意非空 userId 可登录）</li>
 *   <li>uploadSizeMaxMB — 上传大小上限（MB），默认 50；请求参数覆盖 uploadFile 模块的 fileSizeMax</li>
 * </ul>
 *
 * @author tianlong
 * @date 2026/8/17
 */
public class TLWebChatModule extends TLWServModule implements TLAiAgentParamString {

    private static final Gson GSON = new Gson();

    /** 待批操作拦截文案：doChat / doChatStream 共用一份，防两份手抄文本漂移 */
    private static final String PENDING_APPROVAL_BLOCK_MSG = "该会话有待批操作（批准 / 拒绝 / 放弃后才能继续）。请在审批弹框中处理，或在断点提示中选择「放弃该操作」";

    private String serviceModule = "agentService";
    private String userModule = "demoUserModule";
    /** 是否订阅工具实时进度（false = 不注册总线订阅） */
    private boolean subscribeToolProgress = true;
    /** 是否订阅工作流节点进度（false = 不注册总线订阅） */
    private boolean subscribeWorkflowProgress = true;

    /**
     * 进度写 SSE 的专用单线程 writer：
     * 工具线程只入队，慢浏览器不会阻塞工具执行；队列满丢最旧（进度是易失数据）。
     */
    private final java.util.concurrent.ThreadPoolExecutor toolProgressWriter =
            new java.util.concurrent.ThreadPoolExecutor(
                    1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(256),
                    r -> {
                        Thread t = new Thread(r, "webui-tool-progress");
                        t.setDaemon(true);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    /** 工作流节点进度专用 writer：与工具进度分开，避免高频工具事件挤掉节点事件。 */
    private final java.util.concurrent.ThreadPoolExecutor workflowProgressWriter =
            new java.util.concurrent.ThreadPoolExecutor(
                    1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(256),
                    r -> {
                        Thread t = new Thread(r, "webui-workflow-progress");
                        t.setDaemon(true);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    /** 上传大小上限（MB），请求参数覆盖 uploadFile 模块的 fileSizeMax 配置 */
    private int uploadSizeMaxMB = 50;
    /** uid:pwd 表（配置了才做密码校验） */
    private final Map<String, String> passwords = new HashMap<>();
    /** userId:sessionId → 流式 writer */
    private final Map<String, TLWebChannel> streamWriters = new ConcurrentHashMap<>();
    /** userId → 事件通道列表（多标签页并存，全部收到推送） */
    private final Map<String, java.util.concurrent.CopyOnWriteArrayList<TLWebChannel>> eventChannels = new ConcurrentHashMap<>();
    /*
     * 会话级引用表——生命周期各不相同，不要"统一清理"：
     *  - sessionOwner：执行中会话的属主（chat/stream 开始 put，流结束/任务结束 remove）。
     *    非流式 chat 的条目会保留到进程重启，量级=会话数（短字符串），不是消息数。
     *  - taskSessions：后台任务执行会话的归属（finishTask → unregisterTaskSession 清理）。
     * 登出只清 eventChannels / currentSessionByUser / sessionLogin / todo，刻意保留
     * taskSessions / sessionOwner——退出后后台任务仍在跑，审批路由与结果投递还需要它们
     * （详见 doLogout 的注释）。任务运行态（runningTasks/RunState/agent 线程）
     * 在 TLScheduleTaskSkill 里，与这些引用表无关。
     */
    /** sessionId → userId（审批事件定位属主） */
    private final Map<String, String> sessionOwner = new ConcurrentHashMap<>();
    /** 任务执行会话 → 发起信息（审批/事件路由到发起会话）；unregisterTaskSession 清理 */
    private static class TaskSessionInfo { String parentSessionId; String taskId; String userId; }
    private final Map<String, TaskSessionInfo> taskSessions = new ConcurrentHashMap<>();
    /** 会话登录占用：sessionId → loginId（登录级互斥——同一会话同时只允许一个登录继续；null 兼容旧前端不校验） */
    private final Map<String, String> sessionLogin = new ConcurrentHashMap<>();
    /** userId → 前端当前打开的会话（setCurrentSession 上报；定时任务执行期解析目标会话用） */
    private final Map<String, String> currentSessionByUser = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * sessionId → 该会话最近一次任务清单事件（todoEvent 的 evt，前端直接渲染用）。
     * 事件是一次性推送：页面刷新/SSE 断线重连期间发布的清单若不留档就再也见不到，
     * 故与未决审批同思路做"通道建立时重放"；同一会话只留最新（后到覆盖先到）。
     * 有界（只保最近 {@link #TODO_CACHE_MAX} 个会话）：清单是小事件但会话可无限新建，
     * 不设上限等于随会话数无限增长；LinkedHashMap 插入序淘汰最老的，重放只要最新值不受影响。
     */
    private static final int TODO_CACHE_MAX = 200;
    private final Map<String, Map<String, Object>> lastTodoBySession =
            Collections.synchronizedMap(new LinkedHashMap<String, Map<String, Object>>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                    return size() > TODO_CACHE_MAX;
                }
            });
    /** 离线消息箱存储（DB 后端，表声明见 database_config.xml 的 aiInbox） */
    private final InboxStore inboxStore = new InboxStore(this);

    public TLWebChatModule() { super(); }
    public TLWebChatModule(String name) { super(name); }
    public TLWebChatModule(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected TLBaseModule init() { return this; }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params == null) return;
        if (params.get("subscribeToolProgress") != null)
            subscribeToolProgress = "true".equals(params.get("subscribeToolProgress"));
        if (params.get("subscribeWorkflowProgress") != null)
            subscribeWorkflowProgress = "true".equals(params.get("subscribeWorkflowProgress"));
        if (params.get("serviceModule") != null) serviceModule = params.get("serviceModule");
        if (params.get("userModule") != null) userModule = params.get("userModule");
        if (params.get("uploadSizeMaxMB") != null) {
            try { uploadSizeMaxMB = Integer.parseInt(params.get("uploadSizeMaxMB")); } catch (NumberFormatException ignored) {}
        }
        if (params.get("passwords") != null) {
            for (String entry : params.get("passwords").split(";")) {
                int c = entry.indexOf(':');
                if (c > 0) passwords.put(entry.substring(0, c).trim(), entry.substring(c + 1).trim());
            }
        }
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case "startWeb":
                subscribeApprovalEvents();
                subscribeToolProgressBus();
                subscribeWorkflowProgressBus();
                break;
            case STREAM_ONCHUNK:
                onStreamChunk(msg);
                break;
            case "approvalEvent":
                // ack 只在真正推送出去时才返回（非 null=已处理）；没推成功就返回 null/false，
                // 让发布方判定无人接收 → 转断点（不再有 stdout 兜底打印）
                return onApprovalEvent(msg) ? createMsg().setParam(RESULT, true) : null;
            // 审批结束（决策/超时后发布）：推关框指令，让残留弹框自行关闭
            case "approvalResolved":
                return onApprovalResolved(msg) ? createMsg().setParam(RESULT, true) : null;
            // ===== 后台任务（TLScheduleTaskSkill：会话归属登记 + msgBus 生命周期事件）=====
            case "registerTaskSession":
                return onRegisterTaskSession(msg);
            case "unregisterTaskSession":
                return onUnregisterTaskSession(msg);
            case "taskStarted":
            case "taskProgress":
            case "taskResult":
                // 任务生命周期事件（msgBus 订阅）：按 userId 推给其全部 SSE 连接
                return onTaskLifecycleEvent(msg) ? createMsg().setParam(RESULT, true) : null;
            // ===== 会话标题（agent 首轮自动起名 / 手动改名后广播）=====
            case "sessionTitle":
                return onSessionTitleEvent(msg) ? createMsg().setParam(RESULT, true) : null;
            // ===== 任务清单变更（TLTodoSkill 每次 write 发布；前端输入区上方清单条实时刷新）=====
            case "todoEvent":
                return onTodoEvent(msg) ? createMsg().setParam(RESULT, true) : null;
            // ===== 工具实时进度（执行器 → Agent → msgBus；只要状态，不含 output）=====
            case TOOL_PROGRESS_TOPIC:
                return onToolProgress(msg);
            // ===== 工作流节点进度（引擎 → 宿主 → msgBus；节点状态，不含产出）=====
            case WORKFLOW_PROGRESS_TOPIC:
                return onWorkflowProgress(msg);
            // ===== 框架路由入口（TLServletDispatch → TLWUrlMap → 本模块）=====
            case "session":
                return doSession();
            case "login":
                return doLogin(msg);
            case "logout":
                return doLogout(msg);
            case "command":
                return doCommand(msg);
            case "chat":
                return doChat(msg);
            case "stopChat":
                return doStopChat(msg);
            case "upload":
                return doUpload();
            case "image":
                return doImage(msg);
            case "chatStream":
                return doChatStream(msg);
            case "events":
                return doEvents();
            case "registerSseChannel":
                return onRegisterSseChannel(msg);
            // ===== 当前活动会话（前端上报 / 定时任务技能查询）=====
            case "setCurrentSession":
                return onSetCurrentSession(msg);
            case "getCurrentSession":
                return onGetCurrentSession(msg);
            // ===== 用户在线状态 / 离线消息箱（技能投递与前端共用）=====
            case "getUserStatus":
                return onGetUserStatus(msg);
            case "inboxAdd":
                return onInboxAdd(msg);
            case "inboxList":
                return onInboxList(msg);
            case "inboxRead":
                return onInboxRead(msg);
            case "inboxDelete":
                return onInboxDelete(msg);
        }
        return null;
    }

    // ======================== 登录 ========================

    /** 登录校验。passwords 配置了则必须匹配；否则任意非空 userId 可登录（角色由 userModule 决定） */
    public Map<String, Object> login(String userId, String password) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (userId == null || userId.trim().isEmpty()) {
            out.put("success", false);
            out.put("error", "请输入用户ID");
            return out;
        }
        userId = userId.trim();
        if (!passwords.isEmpty()) {
            String pwd = passwords.get(userId);
            if (pwd == null) {
                out.put("success", false);
                out.put("error", "你不是注册用户");
                return out;
            }
            if (password == null || !pwd.equals(password)) {
                out.put("success", false);
                out.put("error", "密码错误");
                return out;
            }
        }
        out.put("success", true);
        out.put("userId", userId);
        return out;
    }

    // ======================== 命令分发 ========================

    /**
     * 通用命令入口：action + params → agentService → {success, message, [error], [data]}。
     * data 递归转换为 JSON 友好结构（框架对象 → 字符串）。
     */
    public Map<String, Object> handleCommand(String userId, String action, Map<String, Object> params) {
        if (action == null || action.isEmpty()) return failMap("缺少 action");
        // 会话占用声明（webui 自身逻辑，不转发 agentService）：
        // 无 owner / 同 login → 登记占用；被其他 login 占用且未 force → occupied；force → 踢旧 + 转移
        if ("claimSession".equals(action)) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (userId == null) return failMap("未登录");
            String sessionId = params != null ? String.valueOf(params.getOrDefault("sessionId", "")) : "";
            String loginId = params != null ? String.valueOf(params.getOrDefault("loginId", "")) : "";
            boolean force = params != null && Boolean.parseBoolean(String.valueOf(params.getOrDefault("force", false)));
            if (sessionId.isEmpty() || loginId.isEmpty()) return failMap("sessionId/loginId 必填");
            String owner = sessionLogin.get(sessionId);
            boolean occupied = owner != null && !owner.equals(loginId);
            putLog("claimSession: session=" + sessionId + " login=" + loginId
                    + " owner=" + owner + " force=" + force + " occupied=" + occupied, LogLevel.INFO);
            // 注意：data 必须用普通 LinkedHashMap——双花括号匿名子类（new LinkedHashMap(){{...}}）
            // 会被 Gson 序列化成 null（jsonDataOutInterface），前端 `!r.data` 静默返回，
            // 接管/被占用两个分支全失效（被踢的浏览器永远解不了锁，历史事故）
            if (occupied && !force) {
                out.put("success", true);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("occupied", true);
                d.put("owner", owner);
                out.put("data", d);
                return out;
            }
            if (occupied) pushKicked(userId, sessionId, owner);   // 接管：旧登录下线（定向踢）
            sessionLogin.put(sessionId, loginId);
            out.put("success", true);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("occupied", false);
            d.put("kicked", occupied);
            out.put("data", d);
            return out;
        }
        // 当前活动会话上报（webui 自身逻辑，不转发 agentService——前端 /api/command 的消息
        // 默认发往 serviceModule，故必须在本地处理，否则 checkMsgAction 的 case 永远收不到）
        if ("setCurrentSession".equals(action)) {
            if (userId == null) return failMap("未登录");
            String sessionId = params != null && params.get("sessionId") != null
                    ? String.valueOf(params.get("sessionId")) : "";
            onSetCurrentSession(createMsg().setParam("userId", userId).setParam("sessionId", sessionId));
            return okMap("ok");
        }
        // 离线消息箱（webui 自身逻辑，不转发 agentService）；
        // getUserStatus 亦为技能端在线判定入口（技能直接 putMsg 本模块，不经此处）
        if ("inboxList".equals(action) || "inboxRead".equals(action)
                || "inboxDelete".equals(action) || "getUserStatus".equals(action)) {
            if (userId == null) return failMap("未登录");
            TLMsg r;
            if ("inboxList".equals(action)) {
                r = onInboxList(createMsg().setParam("userId", userId)
                        .setParam("category", params != null ? params.get("category") : "all"));
            } else if ("inboxRead".equals(action)) {
                r = onInboxRead(createMsg().setParam("userId", userId)
                        .setParam("id", params != null ? params.get("id") : null));
            } else if ("inboxDelete".equals(action)) {
                r = onInboxDelete(createMsg().setParam("userId", userId)
                        .setParam("id", params != null ? params.get("id") : null)
                        .setParam("scope", params != null ? params.get("scope") : ""));
            } else {
                r = onGetUserStatus(createMsg().setParam("userId", userId));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", r.parseBoolean(RESULT, false));
            out.put("message", r.getStringParam("message", "ok"));
            Object data = r.getParam("data");
            if (data != null) out.put("data", toJsonable(data));
            return out;
        }
        TLMsg msg = createMsg().setAction(action);
        if (params != null) {
            for (Map.Entry<String, Object> e : params.entrySet()) msg.setParam(e.getKey(), e.getValue());
        }
        // 身份以登录态为准：无条件覆盖客户端 params 里的 userId（webui 是单登录界面，
        // 任何 action 都不允许代他人发起）。此前"缺省才补"——客户端只要在 params 里带上
        // userId（如 createBackgroundTask），就能以他人身份建任务/读他人会话（越权）。
        // userId 来自 doLogin 写入 HttpSession（servlet sessionUserId / 本模块 currentUserId）。
        if (userId != null) msg.setParam("userId", userId);
        TLMsg result;
        try {
            result = putMsg(serviceModule, msg);
        } catch (Exception e) {
            return failMap("命令执行异常: " + e.getMessage());
        }
        if (result == null) return failMap("agentService 无响应");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", result.parseBoolean("success", false));
        out.put("message", result.getStringParam("message", ""));
        if (result.containsParam("error")) out.put("error", result.getStringParam("error", ""));
        if (result.containsParam("data")) out.put("data", toJsonable(result.getParam("data")));
        // 审批已结束（不在册/已决策/超时）：前端据此关闭残留弹框（此前点任何按钮都报 already 且不关框）
        if (result.containsParam("approvalEnded")) out.put("approvalEnded", result.parseBoolean("approvalEnded", false));
        // 断点态审批的决策：前端据此自动续跑该会话（批准/拒绝后立即 resume）
        if (result.parseBoolean(AI_P_APPROVAL_DEFERRED, false)) {
            out.put("deferred", true);
            if (result.containsParam("sessionId")) out.put("sessionId", result.getStringParam("sessionId", ""));
        }
        // 放弃操作的断点丢弃结果：失败时前端要如实提示（卡片可能仍存在）
        if (result.containsParam("checkpointDiscarded")) {
            out.put("checkpointDiscarded", result.parseBoolean("checkpointDiscarded", true));
        }
        if (result.containsParam("checkpointError")) {
            out.put("checkpointError", result.getStringParam("checkpointError", ""));
        }
        // 透传最终回答（如 traceReplay 重放结果），供前端直接展示
        if (result.containsParam(AI_P_RESPONSE)) out.put("response", result.getStringParam(AI_P_RESPONSE, ""));
        return out;
    }

    // ======================== 聊天 ========================

    /**
     * 用户目录名净化：userId 来自登录输入，拼进文件路径前必须去掉路径分隔符等字符，
     * 防止 "alice/../bob" 这类 userId 让 data/{userId} 落到别人的目录。
     */
    private static String safeUserId(String userId) {
        return TLPathSafety.safeSegment(userId, "default");
    }

    /**
     * 附件归一化：前端传的是相对 data/{userId}/ 的路径（如 uploads/saved.png），
     * 这里补成绝对路径再交 agent —— agent 侧 attachmentStore.register 只接受本用户 data/{uid}/ 内的真实文件。
     * ⚠ 不能只补成相对路径 "data/{uid}/uploads/xxx"：register 对相对路径会再拼一次 userRoot（data/{uid}），
     * 变成 data/{uid}/data/{uid}/... 而报"文件不存在"。
     */
    @SuppressWarnings("unchecked")
    private static List<Object> normalizeAttachments(List<Object> attachments, String userId) {
        List<Object> norm = new ArrayList<>();
        for (Object o : attachments) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) o);
            Object p = m.get("path");
            if (p != null && !new java.io.File(String.valueOf(p)).isAbsolute()) {
                m.put("path", new java.io.File("data/" + safeUserId(userId) + "/" + p).getAbsolutePath());
            }
            m.put("origin", "user");
            norm.add(m);
        }
        return norm;
    }

    /** 非流式聊天（阻塞）。resume=true 时从断点恢复（消息 = 断点时的用户消息）。
     *  attachments 为前端附件条目（path 为相对 data/{userId}/ 的路径，如 uploads/xxx.png），归一化后交 agent 登记 */
    public Map<String, Object> chat(String userId, String sessionId, String message,
                                    String reasoningMode, boolean resume, List<Object> attachments) {
        TLMsg msg = createMsg().setAction("chat")
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam("userId", userId)
                .setParam(AI_P_USERMESSAGE, message);
        if (resume) msg.setParam("resume", true);
        if (reasoningMode != null && !reasoningMode.isEmpty()) msg.setParam(AI_P_REASONING_MODE, reasoningMode);
        if (attachments != null && !attachments.isEmpty()) msg.setParam(AI_P_ATTACHMENTS, normalizeAttachments(attachments, userId));
        sessionOwner.put(sessionId, userId);
        TLMsg result;
        try {
            result = putMsg(serviceModule, msg);
        } catch (Exception e) {
            return failMap("聊天异常: " + e.getMessage());
        }
        if (result == null) return failMap("agentService 无响应");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", result.parseBoolean("success", false));
        out.put("response", result.getStringParam(AI_P_RESPONSE, ""));
        out.put("sessionId", result.getStringParam(AI_P_SESSIONID, sessionId));
        out.put("cancelled", result.parseBoolean(AI_P_CANCELLED, false));
        if (result.containsParam(AI_P_REASONING)) out.put("reasoning", result.getStringParam(AI_P_REASONING, ""));
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("prompt", result.getIntParam(AI_P_PROMPTTOKENS, 0));
        tokens.put("completion", result.getIntParam(AI_P_COMPLETIONTOKENS, 0));
        tokens.put("total", result.getIntParam(AI_P_TOTALTOKENS, 0));
        out.put("tokens", tokens);
        return out;
    }

    /** 流式聊天：注册 writer → 提交 chatStream。resume=true 时从断点恢复（消息 = 断点时的用户消息）。
     *  失败时关闭 writer 并返回错误 */
    public Map<String, Object> beginChatStream(String userId, String sessionId, String message,
                                               String reasoningMode, boolean resume,
                                               List<Object> attachments, TLWebChannel channel) {
        String key = streamKey(userId, sessionId);
        TLWebChannel existing = streamWriters.get(key);
        if (existing != null && existing.isOpen()) {
            channel.close();
            return failMap("该会话已有进行中的对话，请先停止");
        }
        streamWriters.put(key, channel);
        sessionOwner.put(sessionId, userId);
        try {
            TLMsg msg = createMsg().setAction("chatStream")
                    .setParam(AI_P_SESSIONID, sessionId)
                    .setParam("userId", userId)
                    .setParam(AI_P_USERMESSAGE, message)
                    .setParam("streamTarget", getName())
                    .setParam("streamAction", STREAM_ONCHUNK);
            if (reasoningMode != null && !reasoningMode.isEmpty()) msg.setParam(AI_P_REASONING_MODE, reasoningMode);
            if (resume) msg.setParam("resume", true);
            if (attachments != null && !attachments.isEmpty()) msg.setParam(AI_P_ATTACHMENTS, normalizeAttachments(attachments, userId));
            TLMsg result = putMsg(serviceModule, msg);
            if (result == null || !result.parseBoolean("success", false)) {
                streamWriters.remove(key);
                channel.close();
                return failMap(result != null ? result.getStringParam("error", "") : "agentService 无响应");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            return out;
        } catch (Exception e) {
            streamWriters.remove(key);
            channel.close();
            return failMap("流式提交异常: " + e.getMessage());
        }
    }

    /** 停止对话：发给 agentService + 清理流 writer */
    public Map<String, Object> stopChat(String userId, String sessionId) {
        try {
            putMsg(serviceModule, createMsg().setAction("stopChat").setParam(AI_P_SESSIONID, sessionId));
        } catch (Exception ignored) {}
        TLWebChannel c = streamWriters.remove(streamKey(userId, sessionId));
        if (c != null) c.close();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("message", "已发送停止信号");
        return out;
    }

    // ======================== 框架路由（TLServletDispatch → TLWUrlMap）=======================

    /** 当前登录用户：HttpSession attribute "userId"（登录 action 写入，与前端会话共用） */
    private String currentUserId() {
        HttpServletRequest req = getRequest();
        if (req == null) return null;
        HttpSession s = req.getSession(false);
        return s != null ? (String) s.getAttribute("userId") : null;
    }

    /** 统一 JSON 输出：outData → jsonClient → jsonDataOutInterface；httpStatus 可选（如 401） */
    private TLMsg outJson(Map<String, Object> data, Integer httpStatus) {
        setThreadData("client", "jsonClient");   // 未配 clientType 的 url（如 /upload）也走 JSON 输出
        outData od = creatOutDataMsg();
        od.addMapData(data);
        TLMsg moduleMsg = createMsg().setAction("putOutData").setParam("outData", od);
        if (httpStatus != null) moduleMsg.setParam("httpStatus", httpStatus);
        return putMsg(getClient(), moduleMsg);
    }

    private TLMsg notLogin() {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("success", false);
        e.put("error", "未登录");
        return outJson(e, 401);
    }

    private TLMsg doSession() {
        String userId = currentUserId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("loggedIn", userId != null);
        if (userId != null) out.put("userId", userId);
        return outJson(out, null);
    }

    private TLMsg doLogin(TLMsg msg) {
        String userId = msg.getParam("userId") != null ? String.valueOf(msg.getParam("userId")) : null;
        String password = msg.getParam("password") != null ? String.valueOf(msg.getParam("password")) : null;
        Map<String, Object> r = login(userId, password);
        if (Boolean.TRUE.equals(r.get("success"))) {
            getRequest().getSession(true).setAttribute("userId", r.get("userId"));
            return outJson(r, null);
        }
        return outJson(r, 401);
    }

    private TLMsg doLogout(TLMsg msg) {
        String userId = currentUserId();
        if (userId == null) return notLogin();
        closeEventsChannel(userId);
        // 清除在线标记：离线判定（getUserStatus）依据 currentSessionByUser，
        // 不清则退出后任务仍判"在线"，离线消息箱永远收不到东西
        currentSessionByUser.remove(userId);
        deferPendingApprovals(userId);    // 退出转断点：待批审批保持待批，等重登后处理（取代"退出即拒"）
        clearTodoCacheOf(userId);         // 任务清单缓存同理：换用户/重登不串上一用户的清单
        // 刻意不清 taskSessions / sessionOwner：退出后后台任务仍在运行——
        // 任务期间的审批路由、"来自后台任务"标记、重登后的断点重放都依赖它们。
        // 任务结束由 TLScheduleTaskSkill.finishTask → unregisterTaskSession 清理；
        // 会话级 sessionOwner 在流结束时由 onStreamChunk 清理。
        // 释放本登录占用的会话（loginId 为空则释放该用户全部占用，兼容旧前端）；
        // 只动属于当前用户的会话（sessionOwner 归属校验），其他登录/用户的占用保留
        String loginId = msg.getStringParam("loginId", "");
        sessionLogin.entrySet().removeIf(e -> {
            String ownerUser = sessionOwner.get(e.getKey());
            if (ownerUser != null && !ownerUser.equals(userId)) return false;  // 其他用户的会话
            return loginId.isEmpty() || loginId.equals(e.getValue());
        });
        HttpSession s = getRequest().getSession(false);
        if (s != null) s.invalidate();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("message", "已退出");
        return outJson(out, null);
    }

    @SuppressWarnings("unchecked")
    private TLMsg doCommand(TLMsg msg) {
        String userId = currentUserId();
        if (userId == null) return notLogin();
        String action = msg.getParam("action") != null ? String.valueOf(msg.getParam("action")) : null;
        Map<String, Object> params = msg.getParam("params") instanceof Map
                ? (Map<String, Object>) msg.getParam("params") : new LinkedHashMap<>();
        return outJson(handleCommand(userId, action, params), null);
    }

    private TLMsg doChat(TLMsg msg) {
        String userId = currentUserId();
        if (userId == null) return notLogin();
        String message = msg.getParam("message") != null ? String.valueOf(msg.getParam("message")) : "";
        String sessionId = msg.getParam("sessionId") != null ? String.valueOf(msg.getParam("sessionId")) : null;
        if (sessionId == null || sessionId.isEmpty())
            sessionId = "webchat_" + userId + "_" + System.currentTimeMillis();
        sessionId = ensureSessionOwner(sessionId, userId);   // 防串用户：webchat_ 前缀会话必须属于当前用户
        // 会话占用校验（登录级互斥）：被其他登录占用 → 拒绝；空闲/同登录 → 登记
        String loginId = msg.getStringParam("loginId", "");
        if (!loginId.isEmpty()) {
            String owner = sessionLogin.get(sessionId);
            if (owner != null && !owner.equals(loginId)) {
                return outJson(failMap("会话正被另一登录使用。请在左侧『会话』栏该会话行点击『接管』夺回，或开启新会话"), null);
            }
            sessionLogin.put(sessionId, loginId);
        }
        // 待批操作守卫：会话有未决审批时拦新消息（批准/拒绝/放弃三选，见审批弹框与断点弹框）
        if (hasPendingApproval(userId, sessionId)) {
            return outJson(failMap(PENDING_APPROVAL_BLOCK_MSG), null);
        }
        boolean resume = msg.parseBoolean("resume", false);
        String reasoningMode = msg.getParam("reasoningMode") != null ? String.valueOf(msg.getParam("reasoningMode")) : null;
        @SuppressWarnings("unchecked")
        List<Object> attachments = msg.getParam("attachments") instanceof List
                ? (List<Object>) msg.getParam("attachments") : null;
        Map<String, Object> r = chat(userId, sessionId, message, reasoningMode, resume, attachments);
        r.put("sessionId", sessionId);
        return outJson(r, null);
    }

    private TLMsg doStopChat(TLMsg msg) {
        String userId = currentUserId();
        if (userId == null) return notLogin();
        String sessionId = msg.getParam("sessionId") != null ? String.valueOf(msg.getParam("sessionId")) : "";
        return outJson(stopChat(userId, sessionId), null);
    }

    /** 上传：multipart 由 uploadFile 模块解析（TLServletDispatch 已绑定请求线程），此处仅包装输出 */
    private TLMsg doUpload() {
        String userId = currentUserId();
        if (userId == null) return notLogin();
        try {
            String filePath = "data/" + safeUserId(userId) + "/uploads";   // 相对启动目录；目录名净化防路径穿越
            TLMsg r = putMsg("uploadFile", createMsg().setAction(UPLOADFILE_UPLOAD)
                    .setParam(UPLOADFILE_P_FILESIZE, uploadSizeMaxMB * 1024 * 1024)   // int 字节，覆盖模块默认 1MB
                    .setParam(UPLOADFILE_P_FILEPATH, filePath));                     // 不传 fileTypes → 无类型限制（内置危险扩展名黑名单仍生效）
            if (r == null) return outJson(failMap("上传模块无响应"), null);
            if (r.parseBoolean(UPLOADFILE_R_ERROR, false)) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("success", false);
                if (r.containsParam(UPLOADFILE_R_FILESIZEMAX))
                    e.put("error", "文件超过大小限制(" + uploadSizeMaxMB + "MB)");
                else if (r.containsParam(UPLOADFILE_R_FILETYPE))
                    e.put("error", "forbidden".equals(r.getStringParam(UPLOADFILE_R_FILETYPE, ""))
                            ? "文件类型不允许（危险扩展名）" : "文件类型不允许: " + r.getStringParam(UPLOADFILE_R_FILETYPE, ""));
                else if (r.containsParam(UPLOADFILE_P_FILEPATH))
                    e.put("error", "保存目录不可用: " + r.getStringParam(UPLOADFILE_P_FILEPATH, ""));
                else e.put("error", "上传失败");
                return outJson(e, null);
            }
            Map<String, String> filenames = r.getMapParam(UPLOADFILE_R_FILENAMES, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("filenames", sortedFilenames(filenames));
            return outJson(out, null);
        } catch (Exception ex) {
            return outJson(failMap("上传失败: " + ex.getMessage()), null);
        }
    }

    /**
     * 附件出图：路径必须在当前用户的 data/{userId}/ 内；走 putStream 二进制输出（不走 outJson）。
     * <p>path 来源：urlMap 未配 clientType/clientVars（二进制输出不能走 jsonClient），
     * 因此 GET query 不会进 msg 参数——优先取 msg 参数（兼容 POST/程序内调用），
     * 取不到再直接读 {@code request.getParameter("path")}（Servlet 容器按 application/x-www-form-urlencoded
     * 解码 query：{@code +}→空格、{@code %XX}→字节，与 URLEncoder.encode 成对）。</p>
     */
    private TLMsg doImage(TLMsg msg) {
        String userId = currentUserId();
        if (userId == null) return notLogin();
        String path = msg.getStringParam("path", "");
        if (path.isEmpty()) {
            HttpServletRequest req = getRequest();
            if (req != null) {
                String p = req.getParameter("path");
                if (p != null) path = p;
            }
        }
        if (path.isEmpty()) return null;
        try {
            String safeUid = safeUserId(userId);
            java.io.File f = new java.io.File(path);
            if (!f.isAbsolute()) f = new java.io.File("data/" + safeUid + "/" + path);
            String root = new java.io.File("data/" + safeUid).getCanonicalPath() + java.io.File.separator;
            if (!(f.getCanonicalPath().startsWith(root))) return null;   // 越权：直接断连不输出
            if (!f.isFile()) return null;
            try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                putStream(f.getName(), in);        // 继承自 TLWServModule，按扩展名自动设 image/* content-type
            }
        } catch (Exception e) {
            putLog("image serve failed: " + e, LogLevel.WARN);
        }
        return null;   // 自己写输出，不走 outJson（先例：doChatStream）
    }

    /**
     * 流式聊天（框架路由）：先经 sseOutInterface 注册 SSE 长连接通道，再提交 agent 任务。
     * 注册同步完成后提交，保证 agent 首帧回调时通道已在 map 中（无丢帧）。
     * 提交后返回 null（不写普通输出），请求线程释放、连接由通道持有。
     */
    private TLMsg doChatStream(TLMsg msg) {
        String userId = currentUserId();
        if (userId == null) return notLogin();
        String message = msg.getParam("message") != null ? String.valueOf(msg.getParam("message")) : "";
        String sessionId = msg.getParam("sessionId") != null ? String.valueOf(msg.getParam("sessionId")) : null;
        if (sessionId == null || sessionId.isEmpty())
            sessionId = "webchat_" + userId + "_" + System.currentTimeMillis();
        sessionId = ensureSessionOwner(sessionId, userId);   // 防串用户：webchat_ 前缀会话必须属于当前用户
        // 会话占用校验（登录级互斥）：被其他登录占用 → 拒绝；空闲/同登录 → 登记
        String loginId = msg.getStringParam("loginId", "");
        if (!loginId.isEmpty()) {
            String owner = sessionLogin.get(sessionId);
            if (owner != null && !owner.equals(loginId)) {
                return outJson(failMap("会话正被另一登录使用。请在左侧『会话』栏该会话行点击『接管』夺回，或开启新会话"), null);
            }
            sessionLogin.put(sessionId, loginId);
        }
        // 待批操作守卫：会话有未决审批时拦新消息（批准/拒绝/放弃三选，见审批弹框与断点弹框）
        // 必须在 openSseChannel 之前：fail fast，别开了 SSE 通道再拒
        if (hasPendingApproval(userId, sessionId)) {
            return outJson(failMap(PENDING_APPROVAL_BLOCK_MSG), null);
        }
        String reasoningMode = msg.getParam("reasoningMode") != null ? String.valueOf(msg.getParam("reasoningMode")) : null;
        @SuppressWarnings("unchecked")
        List<Object> attachments = msg.getParam("attachments") instanceof List
                ? (List<Object>) msg.getParam("attachments") : null;
        String key = streamKey(userId, sessionId);
        TLMsg reg = openSseChannel(getName(), key, "stream");
        if (reg == null || !reg.parseBoolean(RESULT, false)) {
            String err = reg != null ? reg.getStringParam("error", "SSE 通道注册失败") : "SSE 通道注册失败";
            return outJson(failMap(err), null);
        }
        TLWebChannel channel = (TLWebChannel) reg.getParam("channel");
        sessionOwner.put(sessionId, userId);
        try {
            TLMsg submit = createMsg().setAction("chatStream")
                    .setParam(AI_P_SESSIONID, sessionId)
                    .setParam("userId", userId)
                    .setParam(AI_P_USERMESSAGE, message)
                    .setParam("streamTarget", getName())
                    .setParam("streamAction", STREAM_ONCHUNK);
            if (reasoningMode != null && !reasoningMode.isEmpty()) submit.setParam(AI_P_REASONING_MODE, reasoningMode);
            // 断点恢复透传（framework 路由路径此前漏了这行——resume 请求会被当成新对话重跑）
            if (msg.parseBoolean("resume", false)) submit.setParam("resume", true);
            if (attachments != null && !attachments.isEmpty()) submit.setParam(AI_P_ATTACHMENTS, normalizeAttachments(attachments, userId));
            TLMsg result = putMsg(serviceModule, submit);
            if (result == null || !result.parseBoolean("success", false)) {
                closeStreamChannel(key);
                return outJson(failMap(result != null ? result.getStringParam("error", "") : "agentService 无响应"), null);
            }
        } catch (Exception e) {
            closeStreamChannel(key);
            return outJson(failMap("流式提交异常: " + e.getMessage()), null);
        }
        // 请求线程挂起等待流结束（done 帧 → onStreamChunk close 通道 → latch 释放），
        // 保持 HTTP 连接不提交；期间 SSE 帧由 agent 线程经 STREAM_ONCHUNK 写入
        if (channel != null) channel.awaitClosed();
        return null;
    }

    /** 审批事件推送（框架路由）：注册 SSE 长连接通道，重放未决审批，挂起等待关闭（登出时 closeEventsChannel 触发） */
    private TLMsg doEvents() {
        String userId = currentUserId();
        if (userId == null) return notLogin();
        TLMsg reg = openSseChannel(getName(), userId, "event");
        if (reg == null || !reg.parseBoolean(RESULT, false)) {
            return outJson(failMap("SSE 通道注册失败"), null);
        }
        TLWebChannel channel = (TLWebChannel) reg.getParam("channel");
        try {
            // 重进/重连：审批事件只发布一次，断线后补推该用户的未决审批（弹框重现）
            replayPendingApprovals(userId, channel);
            // 任务清单同理：todoEvent 只推一次，刷新/重连后按缓存补推该用户各会话的最新清单
            replayTodoStates(userId, channel);
            if (channel != null) channel.awaitClosed();
        } finally {
            // 请求线程退出一律注销：否则关闭/断线的 channel 会永远留在 eventChannels 里（只增不减）
            if (channel != null) unregisterEventsChannel(userId, channel);
        }
        return null;
    }

    /**
     * 会话是否属于该用户：webchat_ 前缀按命名规则；task_* 等执行会话查归属表
     * （taskSessions 由技能启动任务时登记，sessionOwner 由 chat/任务登记写入）。
     * 两者都是 ConcurrentHashMap，直接查。
     */
    private boolean sessionBelongsTo(String sid, String userId) {
        if (sid == null || sid.isEmpty() || userId == null || userId.isEmpty()) return false;
        TaskSessionInfo tsi = taskSessions.get(sid);
        if (tsi != null) return userId.equals(tsi.userId);
        String owner = sessionOwner.get(sid);
        if (owner != null) return userId.equals(owner);
        return sid.startsWith("webchat_" + userId + "_");
    }

    /** 查询 approvalGate 未决审批并按会话归属过滤，返回结构化列表 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pendingApprovalsOf(String userId) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            TLMsg query = createMsg().setAction(APPROVAL_PENDINGLIST);
            query.setSystemParam(IGNOREMODULEISNULL, true);   // 缺模块时静默返回（默认行为会关停整个应用）
            TLMsg r = putMsg("approvalGate", query);
            Object listObj = r != null ? r.getParam("pendingList") : null;
            if (!(listObj instanceof List)) return result;
            for (Object o : (List<?>) listObj) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) o;
                String sid = String.valueOf(m.get("sessionId"));
                // 归属过滤：事件自带 userId 命中（自定义会话名退出后 sessionOwner 已清，只靠前缀会漏）
                // 或 webchat_{userId}_ 前缀 + task_* 执行会话（sessionOwner/taskSessions）；未知会话不动
                String evtUser = String.valueOf(m.get("userId"));
                if (!userId.equals(evtUser) && !sessionBelongsTo(sid, userId)) continue;
                result.add(m);
            }
        } catch (Exception e) {
            putLog("查询未决审批失败: " + e, LogLevel.WARN);
        }
        return result;
    }

    /** 该会话是否有未决审批（有 → 拦截新消息：批准/拒绝/放弃后才能继续） */
    private boolean hasPendingApproval(String userId, String sessionId) {
        for (Map<String, Object> m : pendingApprovalsOf(userId)) {
            if (sessionId.equals(String.valueOf(m.get("sessionId")))) return true;
        }
        return false;
    }

    /** 退出登录：把该用户的未决审批转为断点态（保持待批 + 唤醒阻塞线程以 pending 返回），并写消息箱告知 */
    void deferPendingApprovals(String userId) {
        for (Map<String, Object> m : pendingApprovalsOf(userId)) {
            boolean ok = false;
            try {
                TLMsg defer = createMsg().setAction(APPROVAL_DEFER)
                        .setParam(AI_P_APPROVAL_ID, String.valueOf(m.get("approvalId")));
                defer.setSystemParam(IGNOREMODULEISNULL, true);   // 缺模块时静默返回（默认行为会关停整个应用）
                TLMsg dr = putMsg("approvalGate", defer);
                ok = dr != null && dr.parseBoolean(RESULT, false);
            } catch (Exception e) {
                putLog("审批转断点失败: " + e, LogLevel.WARN);
            }
            // 消息箱告知只写一次：转断点成功且此前未 deferred（重复退出登录不再重复写）
            if (!ok || Boolean.TRUE.equals(m.get("deferred"))) continue;
            // 消息箱告知（转断点口径：审批没被拒绝，回来后仍可批准/放弃）
            try {
                String note = "⏳ 你退出登录时有一项审批未处理，已转为待批断点：工具 "
                        + String.valueOf(m.get("toolName")) + "，会话 " + String.valueOf(m.get("sessionId"))
                        + "。重新登录后可批准或放弃该操作。";
                inboxStore.add(userId, "approval", String.valueOf(m.get("approvalId")), note);
                pushInboxEvent(userId, inboxStore.unreadCount(userId));
            } catch (Exception e) {
                putLog("审批转断点告知写入消息箱失败: " + e, LogLevel.DEBUG);
            }
        }
    }

    /** 退出登录：清掉该用户的任务清单缓存（按事件自带 userId 与 sessionBelongsTo 双重匹配） */
    private void clearTodoCacheOf(String userId) {
        synchronized (lastTodoBySession) {
            lastTodoBySession.entrySet().removeIf(e ->
                    userId.equals(String.valueOf(e.getValue().get("userId"))) || sessionBelongsTo(e.getKey(), userId));
        }
    }

    /**
     * 事件通道建立后重放该用户各会话最近一次任务清单（前端清单条立即重现；无清单的会话不发）。
     * 归属过滤用"事件自带 userId 或 sessionBelongsTo 二者其一命中"（与 {@link #clearTodoCacheOf} 同一口径）：
     * 不能只认 sessionBelongsTo——自定义会话ID（顶栏手输，非 webchat_ 前缀）在流式回合结束后
     * sessionOwner 条目已被清理，单靠它会重放不出来（清单缓存白留，用户刷新后清单条消失）。
     */
    private void replayTodoStates(String userId, TLWebChannel channel) {
        if (channel == null) return;
        List<Map<String, Object>> snapshot;
        synchronized (lastTodoBySession) {   // synchronizedMap 的遍历需手动加锁
            snapshot = new ArrayList<>(lastTodoBySession.values());
        }
        for (Map<String, Object> evt : snapshot) {
            if (!userId.equals(String.valueOf(evt.get("userId")))
                    && !sessionBelongsTo(String.valueOf(evt.get("sessionId")), userId)) continue;
            channel.write(GSON.toJson(evt));
        }
    }

    /** 事件通道建立后重放该用户未决审批（与 onApprovalEvent 同格式，前端弹框重现） */
    private void replayPendingApprovals(String userId, TLWebChannel channel) {
        if (channel == null) return;
        for (Map<String, Object> m : pendingApprovalsOf(userId)) {
            Map<String, Object> evt = new LinkedHashMap<>();
            evt.put("type", "approval");
            evt.put("text", String.valueOf(m.get("text")));
            evt.put("approvalId", String.valueOf(m.get("approvalId")));
            evt.put("toolName", String.valueOf(m.get("toolName")));
            evt.put("description", String.valueOf(m.get("description")));
            String sid = String.valueOf(m.get("sessionId"));
            evt.put("sessionId", sid);
            // 来自后台任务执行会话的审批：与 onApprovalEvent 对齐（前端提示"来自后台任务"并支持跳转）
            TaskSessionInfo tsi = taskSessions.get(sid);
            if (tsi != null) {
                evt.put("parentSessionId", tsi.parentSessionId);
                evt.put("taskId", tsi.taskId);
                evt.put("fromTask", true);
            }
            evt.put("args", String.valueOf(m.get("args")));
            // 断点态 + 发起时间：前端弹框显示"发起于 X 前"，避免误当成刚刚的操作
            evt.put("deferred", Boolean.TRUE.equals(m.get("deferred")));
            evt.put("createdAt", m.get("createdAt") != null ? m.get("createdAt") : 0L);
            channel.write(GSON.toJson(evt));
        }
    }

    /** 经 sseClient → sseOutInterface 创建 SSE 通道并注册回本模块（同步完成） */
    private TLMsg openSseChannel(String owner, String key, String type) {
        setThreadData("client", "sseClient");
        TLMsg moduleMsg = createMsg().setAction("putOutData")
                .setParam("sseRegister", true)
                .setParam("sseOwner", owner)
                .setParam("sseKey", key)
                .setParam("sseType", type);
        return putMsg(getClient(), moduleMsg);
    }

    /** TLWSSEOutInterface 回调：接收创建的 SSE 通道，按类型注册到对应 map；返回 channel 供调用方 awaitClosed */
    private TLMsg onRegisterSseChannel(TLMsg msg) {
        String key = (String) msg.getParam("sseKey");
        TLWebChannel channel = (TLWebChannel) msg.getParam("channel");
        if (key == null || channel == null) return createMsg().setParam(RESULT, false);
        String type = (String) msg.getParam("sseType");
        if ("event".equals(type)) {
            addEventChannel(key, channel);
        } else {
            // 同 key 已有 open 的流通道 → 拒绝新通道（覆盖会让旧请求的 awaitClosed 挂死到超时）
            TLWebChannel existing = streamWriters.putIfAbsent(key, channel);
            if (existing != null && existing.isOpen()) {
                channel.close();
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "该会话已有进行中的对话，请先停止");
            }
            if (existing != null) {
                // 旧通道已关闭：putIfAbsent 不会替换，显式覆盖
                streamWriters.put(key, channel);
            }
        }
        return createMsg().setParam(RESULT, true).setParam("channel", channel);
    }

    private void closeStreamChannel(String key) {
        TLWebChannel c = streamWriters.remove(key);
        if (c != null) c.close();
    }

    /**
     * 模块关闭：先释放全部 SSE 长连接（chatStream 请求线程 awaitClosed 挂起中）。
     * 若不先关闭，Jetty 停止时强杀挂起请求 → SessionStreamWrapper.failed →
     * completeSessions → session store 已 Not started → "Unable to release Session" WARN。
     */
    @Override
    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        // 先从总线注销订阅。否则销毁后实例仍留在 receivers 列表里：approvalEvent 会被投给
        // 这个死实例，它还返回 ack，发布方（ConsoleReviewer）据此以为有人渲染了审批框，
        // 于是跳过控制台兜底打印 —— 审批提示直接消失；模块重载时新旧实例还会各投一遍
        try {
            putMsg("msgBus", createMsg().setAction("unRegistBus")
                    .setParam("destination", "approvalEvent").setParam("object", this));
            putMsg("msgBus", createMsg().setAction("unRegistBus")
                    .setParam("destination", "taskStarted").setParam("object", this));
            putMsg("msgBus", createMsg().setAction("unRegistBus")
                    .setParam("destination", "taskProgress").setParam("object", this));
            putMsg("msgBus", createMsg().setAction("unRegistBus")
                    .setParam("destination", "taskResult").setParam("object", this));
            putMsg("msgBus", createMsg().setAction("unRegistBus")
                    .setParam("destination", "sessionTitle").setParam("object", this));
            putMsg("msgBus", createMsg().setAction("unRegistBus")
                    .setParam("destination", "todoEvent").setParam("object", this));
            putMsg("msgBus", createMsg().setAction("unRegistBus")
                    .setParam("destination", "approvalResolved").setParam("object", this));
            putMsg("msgBus", createMsg().setAction("unRegistBus")
                    .setParam("destination", TOOL_PROGRESS_TOPIC).setParam("object", this));
            putMsg("msgBus", createMsg().setAction("unRegistBus")
                    .setParam("destination", WORKFLOW_PROGRESS_TOPIC).setParam("object", this));
        } catch (Exception e) {
            putLog("msgBus 注销事件订阅失败: " + e, LogLevel.DEBUG);
        }
        toolProgressWriter.shutdownNow();
        workflowProgressWriter.shutdownNow();
        for (TLWebChannel c : streamWriters.values()) {
            try { c.close(); } catch (Exception ignored) {}
        }
        streamWriters.clear();
        for (java.util.concurrent.CopyOnWriteArrayList<TLWebChannel> list : eventChannels.values()) {
            if (list == null) continue;
            for (TLWebChannel c : list) {
                try { c.close(); } catch (Exception ignored) {}
            }
        }
        eventChannels.clear();
        return super.destroy(fromWho, msg);
    }

    /**
     * 防串用户：webui 生成的会话 ID 固定为 webchat_{userId}_ 前缀（历史/断点会话同格式）。
     * 前端传入的 sessionId 若带 webchat_ 前缀但不属于当前用户，说明沿用了上一用户的
     * 残留会话（换用户登录/直接调 API），重置为新会话；非 webchat_ 前缀（用户自定义）不校验。
     */
    private static String ensureSessionOwner(String sessionId, String userId) {
        if (sessionId.startsWith("webchat_") && !sessionId.startsWith("webchat_" + userId + "_")) {
            return "webchat_" + userId + "_" + System.currentTimeMillis();
        }
        return sessionId;
    }

    /** filenames 是 Map<fieldName, savedName>（HashMap 无序），按 file_ 下标排序还原前端选择顺序 */
    private static List<String> sortedFilenames(Map<String, String> filenames) {
        List<String> names = new ArrayList<>();
        if (filenames != null) {
            filenames.entrySet().stream()
                    .sorted(java.util.Comparator.comparingInt(e -> {
                        String k = e.getKey();
                        int idx = k.lastIndexOf('_');
                        try {
                            return idx >= 0 ? Integer.parseInt(k.substring(idx + 1)) : 0;
                        } catch (NumberFormatException nfe) {
                            return 0;
                        }
                    }))
                    .forEach(e -> names.add(e.getValue()));
        }
        return names;
    }

    // ======================== 事件通道（SSE）=======================

    /** 事件通道入表：先清理死通道再 add（正常由 doEvents finally 注销，此处兜底） */
    private void addEventChannel(String userId, TLWebChannel channel) {
        java.util.concurrent.CopyOnWriteArrayList<TLWebChannel> list =
                eventChannels.computeIfAbsent(userId, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        list.removeIf(c -> c == null || !c.isOpen());
        list.add(channel);
    }

    /** 注册 /api/events 通道（userId 级，多标签页并存） */
    public void registerEventsChannel(String userId, TLWebChannel channel) {
        addEventChannel(userId, channel);
    }

    public void unregisterEventsChannel(String userId, TLWebChannel channel) {
        java.util.concurrent.CopyOnWriteArrayList<TLWebChannel> list = eventChannels.get(userId);
        if (list != null) { list.remove(channel); if (list.isEmpty()) eventChannels.remove(userId); }
    }

    /**
     * 向该用户全部打开的 SSE 事件通道广播。逐通道兜底：
     * 一条通道写失败不能中断对其他标签页的投递；已关闭/写失败的通道顺手摘除。
     *
     * @return 是否至少成功写入一条连接（供审批等需要 ack 的调用方判定"有人接收"）
     */
    private boolean broadcastToUser(String userId, String json) {
        if (userId == null || userId.isEmpty() || "null".equals(userId)) return false;
        java.util.concurrent.CopyOnWriteArrayList<TLWebChannel> list = eventChannels.get(userId);
        if (list == null || list.isEmpty()) return false;
        boolean sent = false;
        for (TLWebChannel c : list) {
            if (c == null || !c.isOpen()) continue;
            try {
                c.write(json);
                sent = true;
            } catch (Exception e) {
                // 单条通道异常：关掉它，继续推给其他标签页（否则一个坏页面会拖死整轮广播）
                try { c.close(); } catch (Exception ignored) {}
            }
        }
        // COW 的 removeIf 是单次重建（不是遍历中逐个 remove）
        list.removeIf(c -> c == null || !c.isOpen());
        if (list.isEmpty()) eventChannels.remove(userId);
        return sent;
    }

    /** 退出登录：关闭该用户的全部事件通道 */
    public void closeEventsChannel(String userId) {
        java.util.concurrent.CopyOnWriteArrayList<TLWebChannel> list = eventChannels.remove(userId);
        if (list != null) for (TLWebChannel c : list) c.close();
    }

    /**
     * 踢下线通知：会话被其他登录接管时推送 kicked 事件。
     * 事件通道按 userId 聚合（多浏览器共享），故事件携带被踢的 loginId——
     * 前端只有 loginId 匹配自己时才响应（禁用输入），接管方不受影响。
     */
    private void pushKicked(String userId, String sessionId, String kickedLoginId) {
        String json = GSON.toJson(java.util.Map.of(
                "type", "kicked", "sessionId", sessionId,
                "loginId", kickedLoginId != null ? kickedLoginId : "",
                "text", "该会话已被其他设备接管，你已下线"));
        broadcastToUser(userId, json);
    }

    // ======================== 内部 ========================

    private static String streamKey(String userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    /** 流式 chunk 回调（agent 线程）：查 writer 写 SSE 帧；结束/错误时带 token 汇总收尾 */
    private void onStreamChunk(TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        // 帧自带 userId 优先（ForwardStreamSink 每帧都带）；旧路径 fallback 到 sessionOwner
        String userId = msg.getStringParam(AI_P_USERID, null);
        if (userId == null || userId.isEmpty()) {
            userId = sessionOwner.getOrDefault(sessionId, "");
        }
        String key = streamKey(userId, sessionId);
        TLWebChannel channel = streamWriters.get(key);
        if (channel == null) return;
        if (!channel.isOpen()) {
            // 浏览器已断开：清掉死通道，避免 streamWriters/sessionOwner 泄漏、awaitClosed 挂死
            if (streamWriters.remove(key, channel)) {
                sessionOwner.remove(sessionId, userId);
                channel.close();
            }
            return;
        }

        boolean done = msg.parseBoolean(AI_P_STREAMDONE, false);
        boolean hasErr = msg.containsParam(AI_P_STREAMERROR);
        Map<String, Object> evt = new LinkedHashMap<>();
        // chunk 与 replace 互斥：replace 是整段覆盖，chunk 是追加；同帧同时携带时前端 replace 优先
        if (msg.containsParam(AI_P_CHUNK)) evt.put("chunk", msg.getStringParam(AI_P_CHUNK, ""));
        // 输出护栏改写：前端用该全文替换当前 assistant 气泡（不是追加）
        if (msg.containsParam(AI_P_STREAMREPLACE))
            evt.put("replace", msg.getStringParam(AI_P_STREAMREPLACE, ""));
        // 工具完成事件（TLAiAgent pushStreamToolEvent 推来）：前端渲染工具结果/截图
        if (msg.containsParam("toolEvent")) {
            evt.put("toolEvent", true);
            evt.put("toolName", msg.getStringParam("toolName", ""));
            evt.put("toolOutput", msg.getStringParam("toolOutput", ""));
        }
        if (done || hasErr) {
            evt.put("done", true);
            if (hasErr) evt.put("error", msg.getStringParam(AI_P_STREAMERROR, ""));
            if (msg.containsParam(AI_P_REASONING)) evt.put("reasoning", msg.getStringParam(AI_P_REASONING, ""));
            Map<String, Object> tokens = new LinkedHashMap<>();
            tokens.put("sessionTotal", querySessionTokenTotal(sessionId));
            tokens.put("processTotal", queryProcessTokenTotal());
            evt.put("tokens", tokens);
            try {
                channel.write(GSON.toJson(evt));
            } finally {
                // write 抛异常也要清理并唤醒 awaitClosed，否则请求线程挂到超时
                streamWriters.remove(key, channel);
                sessionOwner.remove(sessionId, userId);
                channel.close();
            }
            return;
        }
        if (!evt.isEmpty()) channel.write(GSON.toJson(evt));
    }

    /**
     * 审批事件（msgBus 回调）：事件自带 userId 优先，缺省按 sessionId 定位属主推送给其 SSE 连接；
     * 两者都定不了 → 不推（缺 userId 且无属主宁缺勿串，不广播）。
     *
     * @return 是否真的推给了至少一条打开的连接（供调用方决定要不要返回 ack）
     */
    private boolean onApprovalEvent(TLMsg msg) {
        String sid = msg.getStringParam("sessionId", "");
        String owner = sid.isEmpty() ? null : sessionOwner.get(sid);
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("type", "approval");
        evt.put("text", msg.getStringParam("text", ""));
        evt.put("approvalId", msg.getStringParam("approvalId", ""));
        evt.put("toolName", msg.getStringParam("toolName", ""));
        evt.put("description", msg.getStringParam("description", ""));
        evt.put("sessionId", sid);
        // 来自后台任务执行会话的审批：带上发起会话与任务标识（前端提示"来自后台任务"并支持跳转）
        TaskSessionInfo tsi = sid.isEmpty() ? null : taskSessions.get(sid);
        if (tsi != null) {
            evt.put("parentSessionId", tsi.parentSessionId);
            evt.put("taskId", tsi.taskId);
            evt.put("fromTask", true);
        }
        evt.put("args", msg.getStringParam("args", "{}"));
        String json = GSON.toJson(evt);
        // 2026-10-13：按事件自带 userId 路由（与 task/sessionTitle 同口径）。
        // 此前只用 sessionOwner 反查属主，命中不了就"广播给所有用户"——控制台会话不在
        // sessionOwner 里，控制台的审批因此串给全部 webui 用户（不同账号可见、可误操作）。
        // 缺 userId 的旧事件回退按会话属主；两者都定不了 → 不推（宁缺勿串），
        // 返回 null/false 让发布方判定无人接收 → 转断点（不再有 stdout 兜底打印）。
        String evtUser = msg.getStringParam("userId", "");
        String targetUser = !evtUser.isEmpty() ? evtUser : owner;
        return broadcastToUser(targetUser, json);
    }

    /**
     * 审批已结束（决策/超时后 approvalGate 发布）：给该用户所有打开的页面推关框指令，
     * 命中的弹框立即自行关闭——此前审批被别处解决（另一窗口已决策/已被放弃）或超时失效后，
     * 弹框残留在屏幕上，再点任何按钮都报 already/not found，只能刷新页面。
     *
     * @return 是否真的推给了至少一条打开的连接（供调用方决定要不要返回 ack）
     */
    private boolean onApprovalResolved(TLMsg msg) {
        String approvalId = msg.getStringParam("approvalId", "");
        if (approvalId.isEmpty()) return false;
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("type", "approvalResolved");
        evt.put("approvalId", approvalId);
        evt.put("state", msg.getStringParam("state", ""));
        evt.put("reason", msg.getStringParam("reason", ""));   // 结束原因（被中断/超时秒数/拒绝原因）
        String json = GSON.toJson(evt);
        // 与 onApprovalEvent 同口径：事件自带 userId 优先，缺省按会话属主回退；都定不了不推（宁缺勿串）
        String evtUser = msg.getStringParam("userId", "");
        String sid = msg.getStringParam("sessionId", "");
        String targetUser = !evtUser.isEmpty() ? evtUser : (sid.isEmpty() ? null : sessionOwner.get(sid));
        return broadcastToUser(targetUser, json);
    }

    /**
     * 任务会话归属登记（技能在任务启动时调用）：审批/事件定位到发起会话与属主。
     * execSession/userId 缺一则拒绝——缺 userId 的事件无法按用户路由。
     */
    private TLMsg onRegisterTaskSession(TLMsg msg) {
        String execSession = msg.getStringParam("execSession", "");
        String userId = msg.getStringParam("userId", "");
        if (execSession.isEmpty() || userId.isEmpty())
            return createMsg().setParam(RESULT, false);
        TaskSessionInfo info = new TaskSessionInfo();
        info.parentSessionId = msg.getStringParam("parentSessionId", "");
        info.taskId = msg.getStringParam("taskId", "");
        info.userId = userId;
        taskSessions.put(execSession, info);
        sessionOwner.put(execSession, userId);       // 审批/流事件按 sessionId 找属主用
        return createMsg().setParam(RESULT, true);
    }

    private TLMsg onUnregisterTaskSession(TLMsg msg) {
        String execSession = msg.getStringParam("execSession", "");
        if (!execSession.isEmpty()) {
            taskSessions.remove(execSession);
            sessionOwner.remove(execSession);
        }
        return createMsg().setParam(RESULT, true);
    }

    /**
     * 工具进度（msgBus 回调，跑在工具 worker 线程）：
     * 只做组包 + 入队，绝不在这里写 SSE——慢客户端不能拖住工具执行。
     */
    private TLMsg onToolProgress(TLMsg msg) {
        if (!subscribeToolProgress) return null;
        String userId = msg.getStringParam("userId", "");
        if (userId.isEmpty()) return null;
        java.util.concurrent.CopyOnWriteArrayList<TLWebChannel> list = eventChannels.get(userId);
        if (list == null || list.isEmpty()) return null;

        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("type", TOOL_PROGRESS_TOPIC);
        evt.put("agentName", msg.getStringParam("agentName", ""));
        evt.put("agentFamilyName", msg.getStringParam("agentFamilyName", ""));
        evt.put("executionId", msg.getStringParam("executionId", ""));
        evt.put("roundId", msg.getStringParam("roundId", ""));
        evt.put("sessionId", msg.getStringParam("sessionId", ""));
        evt.put("userId", userId);
        evt.put("toolCallId", msg.getStringParam("toolCallId", ""));
        evt.put("toolName", msg.getStringParam("toolName", ""));
        evt.put("moduleName", msg.getStringParam("moduleName", ""));
        evt.put("index", msg.getIntParam("index", 0));
        evt.put("total", msg.getIntParam("total", 0));
        evt.put("status", msg.getStringParam("status", ""));
        evt.put("durationMs", msg.getLongParam("durationMs", 0L));
        evt.put("message", msg.getStringParam("message", ""));
        String json = GSON.toJson(evt);
        try {
            toolProgressWriter.execute(() -> writeToEventChannels(userId, json));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return null;
        }
        return createMsg().setParam(RESULT, true);
    }

    /**
     * 工作流节点进度：只入队到有界 writer，慢 SSE 不阻塞引擎线程。
     * sessionId 可能是节点的合成会话；前端用 rootSessionId 兜底找父会话视图。
     */
    private TLMsg onWorkflowProgress(TLMsg msg) {
        if (!subscribeWorkflowProgress) return null;
        String userId = msg.getStringParam("userId", "");
        if (userId.isEmpty()) return null;
        java.util.concurrent.CopyOnWriteArrayList<TLWebChannel> list = eventChannels.get(userId);
        if (list == null || list.isEmpty()) return null;

        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("type", WORKFLOW_PROGRESS_TOPIC);
        evt.put("source", "workflow");
        evt.put("workflowName", msg.getStringParam("workflowName", ""));
        evt.put("workflowFamilyName", msg.getStringParam("workflowFamilyName", ""));
        evt.put("runId", msg.getStringParam("runId", ""));
        evt.put("nodeId", msg.getStringParam("nodeId", ""));
        evt.put("nodeType", msg.getStringParam("nodeType", ""));
        evt.put("status", msg.getStringParam("status", ""));
        evt.put("durationMs", msg.getLongParam("durationMs", 0L));
        evt.put("detail", msg.getStringParam("detail", ""));
        evt.put("totalNodes", msg.getIntParam("totalNodes", 0));
        evt.put("sessionId", msg.getStringParam("sessionId", ""));
        evt.put("userId", userId);
        evt.put("rootSessionId", msg.getStringParam("rootSessionId", ""));
        evt.put("roundId", msg.getStringParam("roundId", ""));
        String json = GSON.toJson(evt);
        try {
            workflowProgressWriter.execute(() -> writeToEventChannels(userId, json));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return null;
        }
        return createMsg().setParam(RESULT, true);
    }

    private void writeToEventChannels(String userId, String json) {
        broadcastToUser(userId, json);
    }

    /**
     * 任务生命周期事件（msgBus 回调：taskStarted/taskProgress/taskResult）：
     * 按 userId 推给其全部 SSE 连接（多标签/多端共享）；前端按 sessionId 决定渲染到哪个会话。
     *
     * @return 是否真的推给了至少一条打开的连接（供调用方决定要不要返回 ack）
     */
    private boolean onTaskLifecycleEvent(TLMsg msg) {
        String userId = msg.getStringParam("userId", "");
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("type", msg.getAction());            // taskStarted | taskProgress | taskResult
        evt.put("taskId", msg.getStringParam("taskId", ""));
        evt.put("name", msg.getStringParam("name", ""));
        evt.put("kind", msg.getStringParam("kind", ""));
        evt.put("status", msg.getStringParam("status", ""));
        evt.put("sessionId", msg.getStringParam("sessionId", ""));           // 渲染目标（发起会话）
        evt.put("parentSessionId", msg.getStringParam("parentSessionId", ""));
        evt.put("creationSessionId", msg.getStringParam("creationSessionId", ""));
        if (msg.containsParam("progressKind")) evt.put("progressKind", msg.getStringParam("progressKind", ""));
        if (msg.containsParam("toolName")) evt.put("toolName", msg.getStringParam("toolName", ""));
        if (msg.containsParam("text")) evt.put("text", msg.getStringParam("text", ""));
        String json = GSON.toJson(evt);
        return broadcastToUser(userId, json);
    }

    /**
     * 会话标题事件（msgBus 回调：agent 首轮自动起名后发布）：
     * 按 userId 推给其全部 SSE 连接，前端据此刷新侧栏标题。
     *
     * @return 是否真的推给了至少一条打开的连接（供调用方决定要不要返回 ack）
     */
    private boolean onSessionTitleEvent(TLMsg msg) {
        String userId = msg.getStringParam("userId", "");
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("type", "sessionTitle");
        evt.put("sessionId", msg.getStringParam("sessionId", ""));
        evt.put("title", msg.getStringParam("title", ""));
        String json = GSON.toJson(evt);
        return broadcastToUser(userId, json);
    }

    /**
     * 任务清单事件（msgBus 回调：TLTodoSkill 每次 write 发布）：
     * 按事件自带的 userId 推给其全部 SSE 连接（与 sessionTitle/taskStarted 同口径；userId 缺失时
     * 回退用 sessionOwner 定位属主）；同时留档到 {@link #lastTodoBySession} 供断线重连重放。
     * 路由用 userId 而非 streamWriters 的 streamKey：事件通道（/api/events）按 userId 建、与流式通道无关。
     *
     * @return 是否真的推给了至少一条打开的连接（无人打开页面时事件静默丢弃，靠重放兜底）
     */
    private boolean onTodoEvent(TLMsg msg) {
        String sid = msg.getStringParam("sessionId", "");
        String userId = msg.getStringParam("userId", "");
        if (userId.isEmpty() && !sid.isEmpty()) userId = sessionOwner.getOrDefault(sid, "");
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("type", "todo");
        evt.put("sessionId", sid);
        evt.put("userId", userId);
        evt.put("text", msg.getStringParam("text", ""));
        evt.put("pendingCount", msg.getIntParam("pendingCount", 0));
        evt.put("inProgressCount", msg.getIntParam("inProgressCount", 0));
        evt.put("completedCount", msg.getIntParam("completedCount", 0));
        evt.put("failedCount", msg.getIntParam("failedCount", 0));
        if (!sid.isEmpty()) lastTodoBySession.put(sid, evt);   // 先留档再推送：推送失败（无通道）也得能重放
        return broadcastToUser(userId, GSON.toJson(evt));
    }

    /** 前端上报当前打开的会话（页面加载/切会话/开新会话时调用；只记内存，不落盘） */
    private TLMsg onSetCurrentSession(TLMsg msg) {
        String userId = msg.getStringParam("userId", "");
        String sid = msg.getStringParam("sessionId", "");
        if (!userId.isEmpty() && !sid.isEmpty()) currentSessionByUser.put(userId, sid);
        return createMsg().setParam(RESULT, true);
    }

    /** 查询用户当前活动会话（定时任务执行期解析目标会话用）；未知返回空串 */
    private TLMsg onGetCurrentSession(TLMsg msg) {
        String userId = msg.getStringParam("userId", "");
        return createMsg().setParam(RESULT, true)
                .setParam("sessionId", currentSessionByUser.getOrDefault(userId, ""));
    }

    // ======================== 离线消息箱 ========================

    /**
     * 用户在线状态（定时任务投递前查询）：online = 有前端上报的当前会话；
     * currentSession 供在线时把结果写回该会话。
     */
    private TLMsg onGetUserStatus(TLMsg msg) {
        String userId = msg.getStringParam("userId", "");
        String sid = currentSessionByUser.getOrDefault(userId, "");
        return createMsg().setParam(RESULT, true)
                .setParam("online", !sid.isEmpty())
                .setParam("sessionId", sid);
    }

    /**
     * 离线投递：写入消息箱 + 推 inbox 事件（在线端实时更新未读 badge）。
     * 调用方：定时任务技能（用户离线时）；未来其他离线通知可复用。
     */
    private TLMsg onInboxAdd(TLMsg msg) {
        String userId = msg.getStringParam("userId", "");
        String text = msg.getStringParam("text", "");
        String source = msg.getStringParam("source", "task");
        String taskId = msg.getStringParam("taskId", "");
        if (userId.isEmpty() || text.isEmpty()) return createMsg().setParam(RESULT, false);
        inboxStore.add(userId, source, taskId, text);
        // 推事件：在线端（另一标签页）立即更新未读计数
        pushInboxEvent(userId, inboxStore.unreadCount(userId));
        return createMsg().setParam(RESULT, true);
    }

    /** 消息箱列表（含未读数 + 各类别计数）；category 为空/all = 全部，task/approval/system 按类别筛选 */
    private TLMsg onInboxList(TLMsg msg) {
        String userId = msg.getStringParam("userId", "");
        String category = msg.getStringParam("category", "all");
        Map<String, Map<String, Object>> counts = inboxStore.categoryCounts(userId);
        int total = 0;
        for (Map<String, Object> c : counts.values()) total += num(c.get("total"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("unread", inboxStore.unreadCount(userId));
        data.put("category", category);
        data.put("total", total);
        data.put("counts", counts);
        data.put("list", inboxStore.list(userId, category, 50));
        return createMsg().setParam(RESULT, true).setParam("data", data);
    }

    /** 标记已读：id 为空 = 全部标记已读；返回剩余未读数 */
    private TLMsg onInboxRead(TLMsg msg) {
        String userId = msg.getStringParam("userId", "");
        Object id = msg.getParam("id");
        inboxStore.markRead(userId, id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("unread", inboxStore.unreadCount(userId));
        return createMsg().setParam(RESULT, true).setParam("data", data);
    }

    /** 删除消息：id 非空 = 删单条；scope=read = 删全部已读；scope=all = 清空（必须显式指定，防误删） */
    private TLMsg onInboxDelete(TLMsg msg) {
        String userId = msg.getStringParam("userId", "");
        Object id = msg.getParam("id");
        String scope = msg.getStringParam("scope", "");
        boolean ok = inboxStore.delete(userId, id, scope);
        if (ok) pushInboxEvent(userId, inboxStore.unreadCount(userId));   // 删未读时角标立即回落
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("unread", inboxStore.unreadCount(userId));
        return createMsg().setParam(RESULT, ok)
                .setParam("message", ok ? "ok" : "缺少 id 或 scope")
                .setParam("data", data);
    }

    private static int num(Object o) { return o instanceof Number ? ((Number) o).intValue() : 0; }

    /** 推 inbox 事件给该用户全部 SSE 连接（复用 eventChannels，与 taskResult 同路） */
    private void pushInboxEvent(String userId, int unread) {
        try {
            Map<String, Object> evt = new LinkedHashMap<>();
            evt.put("type", "inbox");
            evt.put("unread", unread);
            String json = GSON.toJson(evt);
            broadcastToUser(userId, json);
        } catch (Exception e) {
            putLog("推送 inbox 事件失败: " + e, LogLevel.DEBUG);
        }
    }

    /**
     * 消息箱存储：复用框架 DB 模块（TLDatabase.getTable），表声明见 database_config.xml 的 aiInbox。
     * 惰性取表 + 失败降级（DB 未配置时静默空结果，不影响其它功能）。
     */
    private static class InboxStore {
        private final TLWebChatModule owner;
        private TLBaseModule table;

        InboxStore(TLWebChatModule owner) { this.owner = owner; }

        private TLBaseModule tbl() {
            if (table == null) {
                try { table = cn.tianlong.tlobject.db.TLDatabase.getTable("aiInbox", owner); }
                catch (Exception e) { owner.putLog("inbox 取表失败: " + e, LogLevel.DEBUG); }
            }
            return table;
        }

        void add(String userId, String source, String taskId, String text) {
            TLBaseModule t = tbl();
            if (t == null) return;
            LinkedHashMap<String, Object> p = new LinkedHashMap<>();
            p.put("user_id", userId);
            p.put("source", source);
            p.put("task_id", taskId);
            p.put("text", text);
            p.put("is_read", 0);
            p.put("created_at", System.currentTimeMillis());
            owner.putMsg(t, owner.createMsg().setAction(cn.tianlong.tlobject.base.TLParamString.DB_INSERT)
                    .setParam(cn.tianlong.tlobject.base.TLParamString.DB_P_SQL,
                            "insert into [table] (user_id,source,task_id,text,is_read,created_at) values (?,?,?,?,?,?)")
                    .setParam(cn.tianlong.tlobject.base.TLParamString.DB_P_PARAMS, p));
        }

        int unreadCount(String userId) {
            TLBaseModule t = tbl();
            if (t == null || userId == null || userId.isEmpty()) return 0;
            LinkedHashMap<String, Object> p = new LinkedHashMap<>();
            p.put("__uid", userId);
            TLMsg r = owner.putMsg(t, owner.createMsg()
                    .setAction(cn.tianlong.tlobject.base.TLParamString.DB_QUERY)
                    .setParam(cn.tianlong.tlobject.base.TLParamString.DB_P_SQL,
                            "select count(*) as cnt from [table] where user_id=? and is_read=0")
                    .setParam(cn.tianlong.tlobject.base.TLParamString.DB_P_PARAMS, p));
            Object row = firstRow(r);
            if (row instanceof Map) {
                Object c = ((Map<?, ?>) row).get("cnt");
                if (c instanceof Number) return ((Number) c).intValue();
            }
            return 0;
        }

        java.util.List<Map<String, Object>> list(String userId, String category, int limit) {
            java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
            TLBaseModule t = tbl();
            if (t == null || userId == null || userId.isEmpty()) return out;
            LinkedHashMap<String, Object> p = new LinkedHashMap<>();
            p.put("__uid", userId);
            String where = categoryWhere(category, p);
            TLMsg r = owner.putMsg(t, owner.createMsg()
                    .setAction(cn.tianlong.tlobject.base.TLParamString.DB_QUERY)
                    .setParam(cn.tianlong.tlobject.base.TLParamString.DB_P_SQL,
                            "select `id`, `source`, `task_id`, `text`, `is_read`, `created_at` from [table] "
                                    + "where user_id=?" + where + " order by created_at desc limit " + Math.max(1, limit))
                    .setParam(cn.tianlong.tlobject.base.TLParamString.DB_P_PARAMS, p));
            if (r != null && r.getParam(cn.tianlong.tlobject.base.TLParamString.DB_R_RESULT) instanceof java.util.List) {
                for (Object o : (java.util.List<?>) r.getParam(cn.tianlong.tlobject.base.TLParamString.DB_R_RESULT)) {
                    if (o instanceof Map) out.add((Map<String, Object>) o);
                }
            }
            return out;
        }

        /** 类别 → SQL 片段。类别是固定枚举，不拼接用户输入（unknown 一律当全部）。 */
        private static String categoryWhere(String category, LinkedHashMap<String, Object> p) {
            if ("task".equals(category) || "approval".equals(category)) {
                p.put("__cat", category);
                return " and source=?";
            }
            if ("system".equals(category)) return " and (source is null or source not in ('task','approval'))";
            return "";
        }

        /**
         * 各类别消息数：source → 类别（task/approval，其余归 system）。
         * 返回 {task:{total,unread}, approval:{...}, system:{...}}，三类恒存在（前端无需兜底）。
         */
        Map<String, Map<String, Object>> categoryCounts(String userId) {
            Map<String, Map<String, Object>> out = new LinkedHashMap<>();
            for (String c : new String[]{"task", "approval", "system"}) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("total", 0);
                m.put("unread", 0);
                out.put(c, m);
            }
            TLBaseModule t = tbl();
            if (t == null || userId == null || userId.isEmpty()) return out;
            LinkedHashMap<String, Object> p = new LinkedHashMap<>();
            p.put("__uid", userId);
            TLMsg r = owner.putMsg(t, owner.createMsg()
                    .setAction(cn.tianlong.tlobject.base.TLParamString.DB_QUERY)
                    .setParam(cn.tianlong.tlobject.base.TLParamString.DB_P_SQL,
                            "select source, count(*) as cnt, sum(case when is_read=0 then 1 else 0 end) as unread "
                                    + "from [table] where user_id=? group by source")
                    .setParam(cn.tianlong.tlobject.base.TLParamString.DB_P_PARAMS, p));
            if (r != null && r.getParam(cn.tianlong.tlobject.base.TLParamString.DB_R_RESULT) instanceof java.util.List) {
                for (Object o : (java.util.List<?>) r.getParam(cn.tianlong.tlobject.base.TLParamString.DB_R_RESULT)) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> row = (Map<?, ?>) o;
                    String cat = categoryOf(row.get("source"));
                    Map<String, Object> m = out.get(cat);
                    m.put("total", num(m.get("total")) + num(row.get("cnt")));
                    m.put("unread", num(m.get("unread")) + num(row.get("unread")));
                }
            }
            return out;
        }

        private static String categoryOf(Object source) {
            String s = source == null ? "" : String.valueOf(source);
            return "task".equals(s) || "approval".equals(s) ? s : "system";
        }

        /** 删除：单条 / 已读 / 全部。返回是否已发起删除（id 与 scope 都缺省 = 不删，防误清空）。 */
        boolean delete(String userId, Object id, String scope) {
            TLBaseModule t = tbl();
            if (t == null || userId == null || userId.isEmpty()) return false;
            LinkedHashMap<String, Object> p = new LinkedHashMap<>();
            p.put("__uid", userId);
            String sql;
            if (id != null && !String.valueOf(id).isEmpty()) {
                p.put("__id", id);
                sql = "delete from [table] where user_id=? and id=?";
            } else if ("read".equals(scope)) {
                sql = "delete from [table] where user_id=? and is_read=1";
            } else if ("all".equals(scope)) {
                sql = "delete from [table] where user_id=?";
            } else {
                return false;
            }
            owner.putMsg(t, owner.createMsg()
                    .setAction(cn.tianlong.tlobject.base.TLParamString.DB_DELETE)
                    .setParam(cn.tianlong.tlobject.base.TLParamString.DB_P_SQL, sql)
                    .setParam(cn.tianlong.tlobject.base.TLParamString.DB_P_PARAMS, p));
            return true;
        }

        void markRead(String userId, Object id) {
            TLBaseModule t = tbl();
            if (t == null || userId == null || userId.isEmpty()) return;
            LinkedHashMap<String, Object> p = new LinkedHashMap<>();
            p.put("__uid", userId);
            String sql;
            if (id != null && !String.valueOf(id).isEmpty()) {
                p.put("__id", id);
                sql = "update [table] set is_read=1 where user_id=? and id=?";
            } else {
                sql = "update [table] set is_read=1 where user_id=?";
            }
            owner.putMsg(t, owner.createMsg().setAction(cn.tianlong.tlobject.base.TLParamString.DB_UPDATE)
                    .setParam(cn.tianlong.tlobject.base.TLParamString.DB_P_SQL, sql)
                    .setParam(cn.tianlong.tlobject.base.TLParamString.DB_P_PARAMS, p));
        }

        @SuppressWarnings("unchecked")
        private Object firstRow(TLMsg r) {
            if (r == null) return null;
            Object res = r.getParam(cn.tianlong.tlobject.base.TLParamString.DB_R_RESULT);
            if (res instanceof java.util.List && !((java.util.List<?>) res).isEmpty())
                return ((java.util.List<?>) res).get(0);
            return null;
        }
    }

    private void subscribeToolProgressBus() {
        if (!subscribeToolProgress) return;
        try {
            TLMsg reg = putMsg("msgBus", createMsg().setAction("registBus")
                    .setParam("destination", TOOL_PROGRESS_TOPIC).setParam("object", this));
            if (reg == null || !Boolean.TRUE.equals(reg.getParam(RESULT)))
                putLog("webui 订阅工具进度事件被拒绝", LogLevel.WARN);
            else
                putLog("webui 已订阅工具进度事件（msgBus toolProgress）", LogLevel.INFO);
        } catch (Exception e) {
            putLog("webui 订阅工具进度事件失败: " + e, LogLevel.WARN);
        }
    }

    private void subscribeWorkflowProgressBus() {
        if (!subscribeWorkflowProgress) return;
        try {
            TLMsg reg = putMsg("msgBus", createMsg().setAction("registBus")
                    .setParam("destination", WORKFLOW_PROGRESS_TOPIC).setParam("object", this));
            if (reg == null || !Boolean.TRUE.equals(reg.getParam(RESULT)))
                putLog("webui 订阅工作流节点进度事件被拒绝", LogLevel.WARN);
            else
                putLog("webui 已订阅工作流节点进度事件（msgBus workflowProgress）", LogLevel.INFO);
        } catch (Exception e) {
            putLog("webui 订阅工作流节点进度事件失败: " + e, LogLevel.WARN);
        }
    }

    private void subscribeApprovalEvents() {
        try {
            TLMsg regMsg = putMsg("msgBus", createMsg().setAction("registBus")
                    .setParam("destination", "approvalEvent").setParam("object", this));
            if (regMsg == null || !Boolean.TRUE.equals(regMsg.getParam(RESULT)))
                putLog("webui 订阅审批事件被拒绝", LogLevel.WARN);
            else
                putLog("webui 已订阅审批事件（msgBus approvalEvent）", LogLevel.INFO);
            // 定时任务结果事件（TLScheduleTaskSkill 到点执行后发布）
            TLMsg taskRegMsg = putMsg("msgBus", createMsg().setAction("registBus")
                    .setParam("destination", "taskResult").setParam("object", this));
            if (taskRegMsg == null || !Boolean.TRUE.equals(taskRegMsg.getParam(RESULT)))
                putLog("webui 订阅定时任务结果事件被拒绝", LogLevel.WARN);
            else
                putLog("webui 已订阅定时任务结果事件（msgBus taskResult）", LogLevel.INFO);
            // 任务生命周期事件·启动（TLScheduleTaskSkill 任务开跑时发布）
            TLMsg taskStartRegMsg = putMsg("msgBus", createMsg().setAction("registBus")
                    .setParam("destination", "taskStarted").setParam("object", this));
            if (taskStartRegMsg == null || !Boolean.TRUE.equals(taskStartRegMsg.getParam(RESULT)))
                putLog("webui 订阅任务启动事件被拒绝", LogLevel.WARN);
            else
                putLog("webui 已订阅任务启动事件（msgBus taskStarted）", LogLevel.INFO);
            // 任务生命周期事件·进度（流式 chunk / 工具事件）
            TLMsg taskProgRegMsg = putMsg("msgBus", createMsg().setAction("registBus")
                    .setParam("destination", "taskProgress").setParam("object", this));
            if (taskProgRegMsg == null || !Boolean.TRUE.equals(taskProgRegMsg.getParam(RESULT)))
                putLog("webui 订阅任务进度事件被拒绝", LogLevel.WARN);
            else
                putLog("webui 已订阅任务进度事件（msgBus taskProgress）", LogLevel.INFO);
            // 会话标题事件（agent 首轮自动起名 / 改名后发布）
            TLMsg titleRegMsg = putMsg("msgBus", createMsg().setAction("registBus")
                    .setParam("destination", "sessionTitle").setParam("object", this));
            if (titleRegMsg == null || !Boolean.TRUE.equals(titleRegMsg.getParam(RESULT)))
                putLog("webui 订阅会话标题事件被拒绝", LogLevel.WARN);
            else
                putLog("webui 已订阅会话标题事件（msgBus sessionTitle）", LogLevel.INFO);
            // 任务清单变更事件（TLTodoSkill 每次 write 发布）
            TLMsg todoRegMsg = putMsg("msgBus", createMsg().setAction("registBus")
                    .setParam("destination", "todoEvent").setParam("object", this));
            if (todoRegMsg == null || !Boolean.TRUE.equals(todoRegMsg.getParam(RESULT)))
                putLog("webui 订阅任务清单事件被拒绝", LogLevel.WARN);
            else
                putLog("webui 已订阅任务清单事件（msgBus todoEvent）", LogLevel.INFO);
            // 审批结束事件（approvalGate 决策/超时后发布：弹框自愈关闭）
            TLMsg resolvedRegMsg = putMsg("msgBus", createMsg().setAction("registBus")
                    .setParam("destination", "approvalResolved").setParam("object", this));
            if (resolvedRegMsg == null || !Boolean.TRUE.equals(resolvedRegMsg.getParam(RESULT)))
                putLog("webui 订阅审批结束事件被拒绝", LogLevel.WARN);
            else
                putLog("webui 已订阅审批结束事件（msgBus approvalResolved）", LogLevel.INFO);
        } catch (Exception e) {
            putLog("msgBus 订阅事件失败: " + e, LogLevel.WARN);
        }
    }

    /** 查询会话累计 tokens（agentMonitor 未注册时返回 0，静默降级） */
    private long querySessionTokenTotal(String sessionId) {
        try {
            TLMsg m = createMsg().setAction(MONITOR_GETSESSIONTOKENUSAGE)
                    .setParam(AI_P_SESSIONID, sessionId);
            m.setSystemParam(IGNOREMODULEISNULL, true);
            TLMsg r = putMsg(M_AGENTMONITOR, m);
            return (r != null && r.parseBoolean("found", false)) ? r.getLongParam(AI_P_TOTALTOKENS, 0L) : 0L;
        } catch (Exception e) { return 0L; }
    }

    /** 查询进程级 token 总累计 */
    private long queryProcessTokenTotal() {
        try {
            TLMsg m = createMsg().setAction(MONITOR_GETPROCESSTOKENUSAGE);
            m.setSystemParam(IGNOREMODULEISNULL, true);
            TLMsg r = putMsg(M_AGENTMONITOR, m);
            return r != null ? r.getLongParam(AI_P_TOTALTOKENS_PROCESS, 0L) : 0L;
        } catch (Exception e) { return 0L; }
    }

    /** 框架对象 → JSON 友好结构（TLConversationHistory 特殊处理，其余 toString） */
    private Object toJsonable(Object o) {
        if (o == null) return null;
        if (o instanceof String || o instanceof Number || o instanceof Boolean) return o;
        if (o instanceof TLConversationHistory) {
            TLConversationHistory ch = (TLConversationHistory) o;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", String.valueOf(ch.getRole()).toLowerCase());
            m.put("content", ch.getContent());
            if (ch.hasAttachments()) m.put("attachments", toJsonable(ch.getAttachments()));
            if (ch.hasImages()) m.put("images", toJsonable(ch.getImages()));
            return m;
        }
        // ⚠ 必须在通用 Map/List 兜底之前：否则会被 String.valueOf 拍成 cn.tianlong...@1a2b
        if (o instanceof TLAttachmentRef) {
            TLAttachmentRef r = (TLAttachmentRef) o;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", String.valueOf(r.getKind()));
            m.put("name", r.getName());
            m.put("mime", r.getMime());
            if (r.getPath() != null) {
                String q = r.getPath();
                try { q = java.net.URLEncoder.encode(q, "UTF-8"); } catch (Exception ignored) {}
                m.put("url", "/api/image?path=" + q);
            } else if (r.getUrl() != null) {
                m.put("url", r.getUrl());
            }
            m.put("size", r.getSize());
            m.put("meta", r.getMeta());
            return m;
        }
        if (o instanceof TLAgentMonitor.StageRecord) {
            TLAgentMonitor.StageRecord rec = (TLAgentMonitor.StageRecord) o;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", rec.ts);
            m.put("agentName", rec.agentName);
            m.put("sessionId", rec.sessionId);
            m.put("roundId", rec.roundId);
            m.put("stage", rec.stage);
            m.put("detail", rec.detail);
            m.put("durationMs", rec.durationMs);
            m.put("payload", rec.payload);
            return m;
        }
        if (o instanceof Map) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                m.put(String.valueOf(e.getKey()), toJsonable(e.getValue()));
            }
            return m;
        }
        if (o instanceof List) {
            List<Object> l = new ArrayList<>();
            for (Object i : (List<?>) o) l.add(toJsonable(i));
            return l;
        }
        return String.valueOf(o);
    }

    private Map<String, Object> okMap(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("message", message);
        return out;
    }

    private Map<String, Object> failMap(String error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("error", error);
        return out;
    }
}
