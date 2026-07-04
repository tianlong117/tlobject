package cn.tianlong.java.demo.chatroom.server;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天室服务端核心模块
 * - login: 记录用户在线状态（username -> channel），返回登录结果
 * - send: 转发消息给目标用户（通过 userManagerModule 按 channel 路由）
 * - listUsers: 返回在线用户列表
 */
public class ChatRoomServerModule extends TLBaseModule {

    /** 在线用户: username -> channel（Netty channel ID） */
    private final ConcurrentHashMap<String, String> onlineUsers = new ConcurrentHashMap<>();

    public ChatRoomServerModule() {
        super();
    }

    public ChatRoomServerModule(String name) {
        super(name);
    }

    public ChatRoomServerModule(String name, TLObjectFactory factory) {
        super(name, factory);
    }

    @Override
    protected TLBaseModule init() {
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "login":
                returnMsg = login(fromWho, msg);
                break;
            case "send":
                returnMsg = send(fromWho, msg);
                break;
            case "listUsers":
                returnMsg = listUsers(fromWho, msg);
                break;
            default:
        }
        return returnMsg;
    }

    /**
     * 用户登录 — 从系统参数中获取 channel，建立 username -> channel 映射
     */
    private TLMsg login(Object fromWho, TLMsg msg) {
        String username = msg.getStringParam("username", null);
        if (username == null || username.isEmpty()) {
            putLog("login failed: no username", LogLevel.WARN);
            TLMsg err = createMsg().setParam("error", "username required");
            err.setSystemParam(SOCKETSERVER_R_IFRETURN, true);
            return err;
        }

        // 从 WebSocket 消息的系统参数中获取 channel
        String channel = (String) msg.getSystemParam(USERMANAGER_P_USERCHANNEL);
        if (channel != null) {
            onlineUsers.put(username, channel);
        }

        putLog("user login: " + username + " channel=" + channel + ", online: " + onlineUsers.size(), LogLevel.INFO, "chat");

        // 记录用户上线日志
        putLog(username + " joined the chat", LogLevel.INFO, "chat");

        // 返回登录成功，设置 ifReturnClient=true 以便 handleClientMsg 将结果发回客户端
        TLMsg result = createMsg()
                .setParam("msgid", "loginResult")
                .setParam("username", username)
                .setParam("online", true);
        result.setSystemParam(SOCKETSERVER_R_IFRETURN, true);
        return result;
    }

    /**
     * 转发消息给目标用户
     * msg 参数: toUser, content, fromUser
     */
    private TLMsg send(Object fromWho, TLMsg msg) {
        String toUser = msg.getStringParam("toUser", null);
        String content = msg.getStringParam("content", "");
        String fromUser = msg.getStringParam("fromUser", "unknown");

        if (toUser == null || toUser.isEmpty()) {
            TLMsg err = createMsg().setParam("msgid", "error").setParam("error", "no target user");
            err.setSystemParam(SOCKETSERVER_R_IFRETURN, true);
            return err;
        }

        String targetChannel = onlineUsers.get(toUser);
        if (targetChannel == null) {
            // 目标用户不在线，通知发送者
            putLog("send: user " + toUser + " offline, notifying " + fromUser, LogLevel.WARN, "chat");
            String senderChannel = onlineUsers.get(fromUser);
            if (senderChannel != null) {
                // 构造错误消息发给发送者
                HashMap<String, Object> errData = new HashMap<>();
                errData.put("msgid", "receive");
                errData.put("fromUser", "system");
                errData.put("content", "User " + toUser + " is offline");
                TLMsg errMsg = createMsg()
                        .setAction(WEBSOCKET_PUT)
                        .setParam(WEBSOCKET_P_CONTENT, errData);
                errMsg.setSystemParam(USERMANAGER_P_USERCHANNEL, senderChannel);
                putMsg((IObject) fromWho, errMsg);
            }
            TLMsg err = createMsg()
                    .setParam("msgid", "sendResult")
                    .setParam("sent", false)
                    .setParam("error", "user offline");
            err.setSystemParam(SOCKETSERVER_R_IFRETURN, true);
            return err;
        }

        // 构造消息发给目标用户（通过 clientMsgHandler → userManagerModule → webSocketServer）
        HashMap<String, Object> forwardData = new HashMap<>();
        forwardData.put("msgid", "receive");
        forwardData.put("fromUser", fromUser);
        forwardData.put("content", content);
        TLMsg forwardMsg = createMsg()
                .setAction(WEBSOCKET_PUT)
                .setParam(WEBSOCKET_P_CONTENT, forwardData);
        forwardMsg.setSystemParam(USERMANAGER_P_USERCHANNEL, targetChannel);
        putMsg((IObject) fromWho, forwardMsg);

        putLog("message: " + fromUser + " -> " + toUser + ": " + content, LogLevel.INFO, "chat");

        // 返回发送成功给发送者
        TLMsg result = createMsg()
                .setParam("msgid", "sendResult")
                .setParam("sent", true);
        result.setSystemParam(SOCKETSERVER_R_IFRETURN, true);
        return result;
    }

    /**
     * 返回在线用户列表
     */
    private TLMsg listUsers(Object fromWho, TLMsg msg) {
        String fromUser = msg.getStringParam("fromUser", null);
        String senderChannel = null;
        if (fromUser != null) {
            senderChannel = onlineUsers.get(fromUser);
        }
        if (senderChannel == null) {
            senderChannel = (String) msg.getSystemParam(USERMANAGER_P_USERCHANNEL);
        }

        List<String> users = new ArrayList<>(onlineUsers.keySet());
        StringBuilder sb = new StringBuilder();
        for (String u : users) {
            if (sb.length() > 0) sb.append(",");
            sb.append(u);
        }

        // 通过 clientMsgHandler → userManagerModule 发回给请求者
        HashMap<String, Object> replyData = new HashMap<>();
        replyData.put("msgid", "receiveUserlist");
        replyData.put("users", sb.toString());
        TLMsg reply = createMsg()
                .setAction(WEBSOCKET_PUT)
                .setParam(WEBSOCKET_P_CONTENT, replyData);
        reply.setSystemParam(USERMANAGER_P_USERCHANNEL, senderChannel);
        putMsg((IObject) fromWho, reply);

        putLog("listUsers: " + users.size() + " online, requested by " + fromUser, LogLevel.INFO, "chat");

        // 返回结果
        TLMsg result = createMsg()
                .setParam("msgid", "listUsersResult")
                .setParam("users", users);
        result.setSystemParam(SOCKETSERVER_R_IFRETURN, true);
        return result;
    }
}
