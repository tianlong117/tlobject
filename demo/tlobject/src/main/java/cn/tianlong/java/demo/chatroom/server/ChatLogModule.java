package cn.tianlong.java.demo.chatroom.server;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

/**
 * 聊天日志模块 — 通过 beforeMsgTable 自动拦截 send 动作，记录聊天日志
 */
public class ChatLogModule extends TLBaseModule {

    public ChatLogModule() {
        super();
    }

    public ChatLogModule(String name) {
        super(name);
    }

    public ChatLogModule(String name, TLObjectFactory factory) {
        super(name, factory);
    }

    @Override
    protected TLBaseModule init() {
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case "logMessage":
                logMessage(msg);
                break;
            default:
        }
        return null;
    }

    private void logMessage(TLMsg msg) {
        String fromUser = msg.getStringParam("fromUser", "unknown");
        String toUser = msg.getStringParam("toUser", "all");
        String content = msg.getStringParam("content", "");
        putLog("[" + fromUser + " -> " + toUser + "] " + content, LogLevel.INFO, "chat");
    }
}
