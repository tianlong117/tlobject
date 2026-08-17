package cn.tianlong.tlobject.aiagent.webui;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.aiagent.TLConversationHistory;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI Agent Web 交互模块——浏览器 UI 与 Agent 框架之间的桥接层。
 *
 * <p>职责：登录校验、命令分发（JSON → TLMsg → agentService）、流式聊天转发（SSE writer）、
 * 审批事件经 msgBus 订阅后按 sessionId 定位属主推送给对应 SSE 连接。</p>
 *
 * <p>IO 适配由 TLWebChatServlet 完成（HTTP ↔ 本模块方法调用）；本模块只与框架消息交互。</p>
 *
 * <p>配置参数 (XML params):</p>
 * <ul>
 *   <li>serviceModule — agentService 模块名，默认 "agentService"</li>
 *   <li>userModule    — 用户信息模块（getUserInfo 契约），默认 "demoUserModule"</li>
 *   <li>passwords     — 登录密码表 "uid:pwd;uid:pwd"（配置后登录必须匹配；不配则任意非空 userId 可登录）</li>
 * </ul>
 *
 * @author tianlong
 * @date 2026/8/17
 */
public class TLWebChatModule extends TLBaseModule implements TLAiAgentParamString {

    /** SSE 写入通道（Servlet 端实现，包装 HttpServletResponse writer） */
    public interface TLWebChannel {
        void write(String data);
        boolean isOpen();
        void close();
    }

    private static final Gson GSON = new Gson();

    private String serviceModule = "agentService";
    private String userModule = "demoUserModule";
    /** uid:pwd 表（配置了才做密码校验） */
    private final Map<String, String> passwords = new HashMap<>();
    /** userId:sessionId → 流式 writer */
    private final Map<String, TLWebChannel> streamWriters = new ConcurrentHashMap<>();
    /** userId → 事件通道列表（多标签页并存，全部收到推送） */
    private final Map<String, java.util.concurrent.CopyOnWriteArrayList<TLWebChannel>> eventChannels = new ConcurrentHashMap<>();
    /** sessionId → userId（审批事件定位属主） */
    private final Map<String, String> sessionOwner = new ConcurrentHashMap<>();

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
                onApprovalEvent(msg);
                return createMsg().setParam(RESULT, true);  // ack：发布方据此确认有订阅者处理
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

    /** 流式聊天：注册 writer → 提交 chatStream。失败时关闭 writer 并返回错误 */
    public Map<String, Object> beginChatStream(String userId, String sessionId, String message,
                                               String reasoningMode, TLWebChannel channel) {
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

    /** 审批事件（msgBus 回调）：按 sessionId 定位属主推送给其 SSE 连接；未知则广播 */
    private void onApprovalEvent(TLMsg msg) {
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
        if (owner != null) {
            java.util.concurrent.CopyOnWriteArrayList<TLWebChannel> list = eventChannels.get(owner);
            if (list != null) for (TLWebChannel c : list) {
                if (c.isOpen()) c.write(json);
            }
        } else {
            for (java.util.concurrent.CopyOnWriteArrayList<TLWebChannel> list : eventChannels.values()) {
                for (TLWebChannel c : list) {
                    if (c.isOpen()) c.write(json);
                }
            }
        }
    }

    private void subscribeApprovalEvents() {
        try {
            putMsg("msgBus", createMsg().setAction("registBus")
                    .setParam("destination", "approvalEvent").setParam("object", this));
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
