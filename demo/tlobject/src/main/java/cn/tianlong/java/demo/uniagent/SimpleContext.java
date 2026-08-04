package cn.tianlong.java.demo.uniagent;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.uniagent.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简易内存上下文模块，专为 uniagent Demo 使用。
 * 存储和检索对话历史。
 */
public class SimpleContext extends TLBaseModule implements UniAgentParamString {

    /** sessionId → List<TLConversationHistory> */
    private final Map<String, List<TLConversationHistory>> store = new ConcurrentHashMap<>();

    /** 最大历史轮次 */
    private int maxHistoryTurns = 50;

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null) {
            maxHistoryTurns = Integer.parseInt(params.getOrDefault("maxHistoryTurns", "50"));
        }
    }

    @Override
    protected TLBaseModule init() {
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        String action = msg.getAction();
        if (action == null) return null;

        switch (action) {
            case CONTEXT_GETMESSAGES: return getMessages(fromWho, msg);
            case CONTEXT_REPLACE:     return replaceContext(fromWho, msg);
            case CONTEXT_CLEAR:       return clearContext(fromWho, msg);
            case CONTEXT_ADDMESSAGE:  return addMessage(fromWho, msg);
            case CONTEXT_SETSYSTEM:   return setSystem(fromWho, msg);
            default: return null;
        }
    }

    @SuppressWarnings("unchecked")
    protected TLMsg getMessages(Object fromWho, TLMsg msg) {
        String sessionId = (String) msg.getParam(AI_P_SESSIONID);
        List<TLConversationHistory> history = store.getOrDefault(sessionId, new ArrayList<>());
        return createMsg().setParam(AI_P_MESSAGEHISTORY, new ArrayList<>(history));
    }

    @SuppressWarnings("unchecked")
    protected TLMsg replaceContext(Object fromWho, TLMsg msg) {
        String sessionId = (String) msg.getParam(AI_P_SESSIONID);
        List<TLConversationHistory> history = (List<TLConversationHistory>) msg.getParam(AI_P_MESSAGEHISTORY);
        if (history != null) {
            // 裁剪
            if (history.size() > maxHistoryTurns * 2) {
                history = history.subList(history.size() - maxHistoryTurns * 2, history.size());
            }
            store.put(sessionId, new ArrayList<>(history));
        }
        return createMsg();
    }

    protected TLMsg clearContext(Object fromWho, TLMsg msg) {
        String sessionId = (String) msg.getParam(AI_P_SESSIONID);
        store.remove(sessionId);
        return createMsg();
    }

    @SuppressWarnings("unchecked")
    protected TLMsg addMessage(Object fromWho, TLMsg msg) {
        String sessionId = (String) msg.getParam(AI_P_SESSIONID);
        TLConversationHistory entry = (TLConversationHistory) msg.getParam("message");
        if (sessionId != null && entry != null) {
            store.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(entry);
        }
        return createMsg();
    }

    protected TLMsg setSystem(Object fromWho, TLMsg msg) {
        // SimpleContext doesn't store system message separately - it's just another entry
        return createMsg();
    }
}
