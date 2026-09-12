package cn.tianlong.tlobject.aiagent.webui;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.aiagent.TLAgentMonitor;
import cn.tianlong.tlobject.aiagent.TLConversationHistory;
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
 * <p>双入口：</p>
 * <ul>
 *   <li>框架路由（TLServletDispatch → TLWUrlMap → checkMsgAction 的
 *       session/login/logout/command/chat/stopChat/upload/chatStream/events action，
 *       输出经 sseClient/jsonClient → out-interface）</li>
 *   <li>servlet 直连（TLWebChatServlet 调用 login/handleCommand/chat/beginChatStream 等方法，
 *       回退路径，保留不动）</li>
 * </ul>
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

    private String serviceModule = "agentService";
    private String userModule = "demoUserModule";
    /** 上传大小上限（MB），请求参数覆盖 uploadFile 模块的 fileSizeMax 配置 */
    private int uploadSizeMaxMB = 50;
    /** uid:pwd 表（配置了才做密码校验） */
    private final Map<String, String> passwords = new HashMap<>();
    /** userId:sessionId → 流式 writer */
    private final Map<String, TLWebChannel> streamWriters = new ConcurrentHashMap<>();
    /** userId → 事件通道列表（多标签页并存，全部收到推送） */
    private final Map<String, java.util.concurrent.CopyOnWriteArrayList<TLWebChannel>> eventChannels = new ConcurrentHashMap<>();
    /** sessionId → userId（审批事件定位属主） */
    private final Map<String, String> sessionOwner = new ConcurrentHashMap<>();
    /** 会话登录占用：sessionId → loginId（登录级互斥——同一会话同时只允许一个登录继续；null 兼容旧前端不校验） */
    private final Map<String, String> sessionLogin = new ConcurrentHashMap<>();

    public TLWebChatModule() { super(); }
    public TLWebChatModule(String name) { super(name); }
    public TLWebChatModule(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected TLBaseModule init() { return this; }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params == null) return;
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
                break;
            case STREAM_ONCHUNK:
                onStreamChunk(msg);
                break;
            case "approvalEvent":
                // ack 只在真正推送出去时才返回（非 null=已处理）；没推成功就返回 null，
                // 让发布方回退到控制台直接打印，避免"以为有人渲染"而审批提示消失
                return onApprovalEvent(msg) ? createMsg().setParam(RESULT, true) : null;
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
            case "chatStream":
                return doChatStream(msg);
            case "events":
                return doEvents();
            case "registerSseChannel":
                return onRegisterSseChannel(msg);
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
            if (occupied && !force) {
                out.put("success", true);
                out.put("data", new java.util.LinkedHashMap<String, Object>() {{
                    put("occupied", true);
                    put("owner", owner);
                }});
                return out;
            }
            if (occupied) pushKicked(userId, sessionId, owner);   // 接管：旧登录下线（定向踢）
            sessionLogin.put(sessionId, loginId);
            out.put("success", true);
            out.put("data", new java.util.LinkedHashMap<String, Object>() {{
                put("occupied", false);
                put("kicked", occupied);
            }});
            return out;
        }
        TLMsg msg = createMsg().setAction(action);
        if (params != null) {
            for (Map.Entry<String, Object> e : params.entrySet()) msg.setParam(e.getKey(), e.getValue());
        }
        if (!msg.containsParam("userId") && userId != null) msg.setParam("userId", userId);
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
        // 透传最终回答（如 traceReplay 重放结果），供前端直接展示
        if (result.containsParam(AI_P_RESPONSE)) out.put("response", result.getStringParam(AI_P_RESPONSE, ""));
        return out;
    }

    // ======================== 聊天 ========================

    /** 非流式聊天（阻塞）。resume=true 时从断点恢复（消息 = 断点时的用户消息） */
    public Map<String, Object> chat(String userId, String sessionId, String message,
                                    String reasoningMode, boolean resume) {
        TLMsg msg = createMsg().setAction("chat")
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam("userId", userId)
                .setParam(AI_P_USERMESSAGE, message);
        if (resume) msg.setParam("resume", true);
        if (reasoningMode != null && !reasoningMode.isEmpty()) msg.setParam(AI_P_REASONING_MODE, reasoningMode);
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
                                               String reasoningMode, boolean resume, TLWebChannel channel) {
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

    /** 当前登录用户：HttpSession attribute "userId"（与 TLWebChatServlet 同一会话约定） */
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
        rejectPendingApprovals(userId);   // 退出即拒该用户未决审批（不等 5 分钟超时，安全默认）
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
                return outJson(failMap("会话正被另一登录使用。请在右侧『会话』面板点击该会话的『继续』按钮接管，或开启新会话"), null);
            }
            sessionLogin.put(sessionId, loginId);
        }
        boolean resume = msg.parseBoolean("resume", false);
        String reasoningMode = msg.getParam("reasoningMode") != null ? String.valueOf(msg.getParam("reasoningMode")) : null;
        Map<String, Object> r = chat(userId, sessionId, message, reasoningMode, resume);
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
            String filePath = "data/" + userId + "/uploads";   // 相对启动目录，uploadFile 的 getRealPath 回退修复后可用
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
                return outJson(failMap("会话正被另一登录使用。请在右侧『会话』面板点击该会话的『继续』按钮接管，或开启新会话"), null);
            }
            sessionLogin.put(sessionId, loginId);
        }
        String reasoningMode = msg.getParam("reasoningMode") != null ? String.valueOf(msg.getParam("reasoningMode")) : null;
        String key = streamKey(userId, sessionId);
        TLMsg reg = openSseChannel(getName(), key, "stream");
        if (reg == null || !reg.parseBoolean(RESULT, false)) {
            return outJson(failMap("SSE 通道注册失败"), null);
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
        // 重进/重连：审批事件只发布一次，断线后补推该用户的未决审批（弹框重现）
        replayPendingApprovals(userId, channel);
        if (channel != null) channel.awaitClosed();
        return null;
    }

    /** 查询 approvalGate 未决审批并按会话归属过滤（webchat_{userId}_ 前缀），返回结构化列表 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pendingApprovalsOf(String userId) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            TLMsg r = putMsg("approvalGate", createMsg().setAction(APPROVAL_PENDINGLIST));
            Object listObj = r != null ? r.getParam("pendingList") : null;
            if (!(listObj instanceof List)) return result;
            for (Object o : (List<?>) listObj) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) o;
                String sid = String.valueOf(m.get("sessionId"));
                // 归属过滤：仅处理 webchat_ 前缀会话（webchat_{userId}_）；自定义会话无法归属，不动
                if (!sid.startsWith("webchat_")) continue;
                if (!sid.startsWith("webchat_" + userId + "_")) continue;
                result.add(m);
            }
        } catch (Exception e) {
            putLog("查询未决审批失败: " + e, LogLevel.WARN);
        }
        return result;
    }

    /** 退出登录：拒绝该用户的全部未决审批（agent 立即继续，不用等超时） */
    private void rejectPendingApprovals(String userId) {
        for (Map<String, Object> m : pendingApprovalsOf(userId)) {
            try {
                putMsg("approvalGate", createMsg().setAction(APPROVAL_REJECT)
                        .setParam(AI_P_APPROVAL_ID, String.valueOf(m.get("approvalId")))
                        .setParam(AI_P_APPROVAL_REJECTREASON, "用户退出登录"));
            } catch (Exception e) {
                putLog("拒绝审批失败: " + e, LogLevel.WARN);
            }
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
            evt.put("sessionId", String.valueOf(m.get("sessionId")));
            evt.put("args", String.valueOf(m.get("args")));
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
            eventChannels.computeIfAbsent(key, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(channel);
        } else {
            streamWriters.put(key, channel);
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
        } catch (Exception e) {
            putLog("msgBus 注销审批事件订阅失败: " + e, LogLevel.DEBUG);
        }
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

    /** 注册 /api/events 通道（userId 级，多标签页并存） */
    public void registerEventsChannel(String userId, TLWebChannel channel) {
        eventChannels.computeIfAbsent(userId, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(channel);
    }

    public void unregisterEventsChannel(String userId, TLWebChannel channel) {
        java.util.concurrent.CopyOnWriteArrayList<TLWebChannel> list = eventChannels.get(userId);
        if (list != null) { list.remove(channel); if (list.isEmpty()) eventChannels.remove(userId); }
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
        java.util.concurrent.CopyOnWriteArrayList<TLWebChannel> list = eventChannels.get(userId);
        if (list == null) return;
        for (TLWebChannel c : list) {
            try { if (c.isOpen()) c.write(json); } catch (Exception ignored) {}
        }
    }

    // ======================== 内部 ========================

    private static String streamKey(String userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    /** 流式 chunk 回调（agent 线程）：查 writer 写 SSE 帧；结束/错误时带 token 汇总收尾 */
    private void onStreamChunk(TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        String userId = msg.getStringParam(AI_P_USERID, sessionOwner.getOrDefault(sessionId, ""));
        TLWebChannel channel = streamWriters.get(streamKey(userId, sessionId));
        if (channel == null || !channel.isOpen()) return;

        boolean done = msg.parseBoolean(AI_P_STREAMDONE, false);
        boolean hasErr = msg.containsParam(AI_P_STREAMERROR);
        Map<String, Object> evt = new LinkedHashMap<>();
        if (msg.containsParam(AI_P_CHUNK)) evt.put("chunk", msg.getStringParam(AI_P_CHUNK, ""));
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
            channel.write(GSON.toJson(evt));
            streamWriters.remove(streamKey(userId, sessionId));
            sessionOwner.remove(sessionId);
            channel.close();
            return;
        }
        if (!evt.isEmpty()) channel.write(GSON.toJson(evt));
    }

    /**
     * 审批事件（msgBus 回调）：按 sessionId 定位属主推送给其 SSE 连接；未知则广播。
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
        evt.put("args", msg.getStringParam("args", "{}"));
        String json = GSON.toJson(evt);
        boolean sent = false;
        if (owner != null) {
            java.util.concurrent.CopyOnWriteArrayList<TLWebChannel> list = eventChannels.get(owner);
            if (list != null) for (TLWebChannel c : list) {
                if (c.isOpen()) { c.write(json); sent = true; }
            }
        } else {
            for (java.util.concurrent.CopyOnWriteArrayList<TLWebChannel> list : eventChannels.values()) {
                for (TLWebChannel c : list) {
                    if (c.isOpen()) { c.write(json); sent = true; }
                }
            }
        }
        return sent;
    }

    private void subscribeApprovalEvents() {
        try {
            TLMsg regMsg = putMsg("msgBus", createMsg().setAction("registBus")
                    .setParam("destination", "approvalEvent").setParam("object", this));
            if (regMsg == null || !Boolean.TRUE.equals(regMsg.getParam(RESULT)))
                putLog("webui 订阅审批事件被拒绝", LogLevel.WARN);
            else
                putLog("webui 已订阅审批事件（msgBus approvalEvent）", LogLevel.INFO);
        } catch (Exception e) {
            putLog("msgBus 订阅审批事件失败: " + e, LogLevel.WARN);
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
