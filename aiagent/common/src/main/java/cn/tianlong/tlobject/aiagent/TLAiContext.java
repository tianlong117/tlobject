package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI上下文管理模块。管理每个session的对话历史记录。
 * 支持多会话并发访问，自动裁剪超长历史。
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public class TLAiContext extends TLBaseModule implements TLAiAgentParamString {

    /** sessionId -> 消息历史列表 */
    protected Map<String, List<TLConversationHistory>> sessions;

    /** 最大保留轮次 */
    protected int maxHistoryTurns = 50;

    /** 默认系统消息 */
    protected String defaultSystemMessage;

    public TLAiContext() {
        super();
    }

    public TLAiContext(String name) {
        super(name);
    }

    public TLAiContext(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("maxHistoryTurns") != null) {
                try {
                    maxHistoryTurns = Integer.parseInt(params.get("maxHistoryTurns"));
                } catch (NumberFormatException ignored) {}
            }
            defaultSystemMessage = params.get("defaultSystemMessage");
        }
    }

    @Override
    protected TLBaseModule init() {
        sessions = new ConcurrentHashMap<>();
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case CONTEXT_ADDMESSAGE:
                returnMsg = addMessage(fromWho, msg);
                break;
            case CONTEXT_GETMESSAGES:
                returnMsg = getMessages(fromWho, msg);
                break;
            case CONTEXT_CLEAR:
                returnMsg = clear(fromWho, msg);
                break;
            case CONTEXT_REPLACE:
                returnMsg = replace(fromWho, msg);
                break;
            case CONTEXT_GETTURNCOUNT:
                returnMsg = getTurnCount(fromWho, msg);
                break;
            case CONTEXT_SETSYSTEM:
                returnMsg = setSystem(fromWho, msg);
                break;
            default:
                returnMsg = null;
        }
        return returnMsg;
    }

    /**
     * 向session追加一条消息
     */
    @SuppressWarnings("unchecked")
    protected TLMsg addMessage(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        List<TLConversationHistory> history = getOrCreateSession(sessionId);

        // 支持直接传入TLConversationHistory对象
        TLConversationHistory entry = (TLConversationHistory) msg.getParam("entry", TLConversationHistory.class);
        if (entry != null) {
            history.add(entry);
        } else {
            // 从args中构造
            String roleStr = msg.getStringParam("role", "user");
            TLConversationHistory.Role role;
            try {
                role = TLConversationHistory.Role.valueOf(roleStr);
            } catch (IllegalArgumentException e) {
                role = TLConversationHistory.Role.user;
            }
            String content = msg.getStringParam("content", "");
            TLConversationHistory h = new TLConversationHistory(role, content);
            if (msg.containsParam("toolCalls")) {
                h.setToolCalls((List<TLToolCall>) msg.getListParam("toolCalls", null));
            }
            if (msg.containsParam("toolCallId")) {
                h.setToolCallId(msg.getStringParam("toolCallId", null));
                h.setName(msg.getStringParam("name", null));
            }
            history.add(h);
        }

        // 裁剪超长历史
        trimHistory(history);
        return createMsg().setParam(RESULT, true).setParam("turnCount", history.size());
    }

    /**
     * 获取session的完整消息历史
     */
    protected TLMsg getMessages(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        List<TLConversationHistory> history = getOrCreateSession(sessionId);
        return createMsg().setParam(AI_P_MESSAGEHISTORY, new ArrayList<>(history));
    }

    /**
     * 清除session历史
     */
    protected TLMsg clear(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        sessions.remove(sessionId);
        putLog("Context cleared for session: " + sessionId, LogLevel.DEBUG);
        return createMsg().setParam(RESULT, true);
    }

    /**
     * 批量替换session历史（替代先CLEAR再逐条ADD的O(n²)模式）
     */
    @SuppressWarnings("unchecked")
    protected TLMsg replace(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        List<TLConversationHistory> newHistory = (List<TLConversationHistory>)
                msg.getListParam(AI_P_MESSAGEHISTORY, null);
        if (newHistory != null) {
            sessions.put(sessionId, new ArrayList<>(newHistory));
            trimHistory(sessions.get(sessionId));
        } else {
            sessions.remove(sessionId);
        }
        return createMsg().setParam(RESULT, true);
    }

    /**
     * 获取session对话轮次
     */
    protected TLMsg getTurnCount(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        List<TLConversationHistory> history = sessions.get(sessionId);
        int count = (history != null) ? history.size() : 0;
        return createMsg().setParam("turnCount", count);
    }

    /**
     * 设置session的system消息
     */
    protected TLMsg setSystem(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        String systemMsg = msg.getStringParam(AI_P_SYSTEMMESSAGE, null);
        if (systemMsg != null) {
            List<TLConversationHistory> history = getOrCreateSession(sessionId);
            // 移除旧的system消息
            history.removeIf(h -> h.getRole() == TLConversationHistory.Role.system);
            // 在开头插入新的system消息
            history.add(0, new TLConversationHistory(TLConversationHistory.Role.system, systemMsg));
        }
        return createMsg().setParam(RESULT, true);
    }

    // ======================== 内部辅助方法 ========================

    protected List<TLConversationHistory> getOrCreateSession(String sessionId) {
        return sessions.computeIfAbsent(sessionId, k -> {
            List<TLConversationHistory> history = new ArrayList<>();
            // 添加默认system消息
            if (defaultSystemMessage != null && !defaultSystemMessage.isEmpty()) {
                history.add(new TLConversationHistory(
                        TLConversationHistory.Role.system, defaultSystemMessage));
            }
            return history;
        });
    }

    /**
     * 裁剪超长历史。保留system消息 + 最近N轮。
     * 保证不破坏 OpenAI API 消息顺序约束：tool 消息必须紧跟其 assistant(tool_calls)。
     */
    protected void trimHistory(List<TLConversationHistory> history) {
        if (history.size() <= maxHistoryTurns) return;

        // 收集system消息
        List<TLConversationHistory> systemMsgs = new ArrayList<>();
        for (TLConversationHistory h : history) {
            if (h.getRole() == TLConversationHistory.Role.system) {
                systemMsgs.add(h);
            }
        }

        int nonSystemCount = history.size() - systemMsgs.size();
        if (nonSystemCount <= maxHistoryTurns) return;

        int removeCount = nonSystemCount - maxHistoryTurns;
        int removed = 0;
        // 记录被删除的 assistant(tool_calls) 的 tool_call_id，后续 tool 消息一并清理
        java.util.Set<String> removedToolCallIds = new java.util.HashSet<>();

        java.util.Iterator<TLConversationHistory> it = history.iterator();
        while (it.hasNext() && removed < removeCount) {
            TLConversationHistory h = it.next();
            if (h.getRole() == TLConversationHistory.Role.system) continue;

            it.remove();
            removed++;

            // 记录被删 assistant 的 tool_call_id
            if (h.getToolCalls() != null) {
                for (TLToolCall tc : h.getToolCalls()) {
                    if (tc.getId() != null) removedToolCallIds.add(tc.getId());
                }
            }
            // 被删的恰好是 tool 消息，从待清理集合移除（已一并删除）
            if (h.getToolCallId() != null) {
                removedToolCallIds.remove(h.getToolCallId());
            }
        }

        // 清理孤立的 tool 消息：assistant(tool_calls) 已删除但这些 tool 还在
        while (!removedToolCallIds.isEmpty() && it.hasNext()) {
            TLConversationHistory h = it.next();
            if (h.getRole() == TLConversationHistory.Role.tool
                    && h.getToolCallId() != null
                    && removedToolCallIds.contains(h.getToolCallId())) {
                it.remove();
                removedToolCallIds.remove(h.getToolCallId());
            } else {
                break; // 遇到非孤立的 tool 消息，停止
            }
        }
    }
}
