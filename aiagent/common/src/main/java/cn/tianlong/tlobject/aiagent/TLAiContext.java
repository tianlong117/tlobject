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

    /** 最大保留消息数（-1 = 全量保留不 trim；兼容旧配置 maxHistoryTurns） */
    protected int maxContextMessages = -1;

    /** tool 结果入史最大字符数（源头截断：browser 抓整页/大文件读取等巨量输出不落历史，
     *  否则累积数 MB → 超模型上下文 400 且恢复/每轮发送都背巨块。0 = 不限制） */
    protected int toolResultCharLimit = 20000;

    /** sessionId -> 消息序号计数器（线性递增 id，摘要 coverSeq 对齐边界） */
    protected Map<String, Long> seqCounter;

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
            // maxContextMessages 优先（-1=全量保留）；未配置时兼容旧参数 maxHistoryTurns
            if (params.get("maxContextMessages") != null) {
                try {
                    maxContextMessages = Integer.parseInt(params.get("maxContextMessages"));
                } catch (NumberFormatException ignored) {}
            } else if (params.get("maxHistoryTurns") != null) {
                maxContextMessages = maxHistoryTurns;
            }
            if (params.get("toolResultCharLimit") != null) {
                try {
                    toolResultCharLimit = Integer.parseInt(params.get("toolResultCharLimit"));
                } catch (NumberFormatException ignored) {}
            }
            defaultSystemMessage = params.get("defaultSystemMessage");
        }
    }

    @Override
    protected TLBaseModule init() {
        sessions = new ConcurrentHashMap<>();
        seqCounter = new ConcurrentHashMap<>();
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
            case CONTEXT_GETVIEW:
                returnMsg = getView(fromWho, msg);
                break;
            case CONTEXT_GETMAXSEQ:
                returnMsg = getMaxSeq(fromWho, msg);
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
            capToolResult(entry);
            entry.setSeq(nextSeq(sessionId));
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
            capToolResult(h);
            if (msg.containsParam("toolCalls")) {
                h.setToolCalls((List<TLToolCall>) msg.getListParam("toolCalls", null));
            }
            if (msg.containsParam("toolCallId")) {
                h.setToolCallId(msg.getStringParam("toolCallId", null));
                h.setName(msg.getStringParam("name", null));
            }
            h.setSeq(nextSeq(sessionId));
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
     * 批量替换session历史（替代先CLEAR再逐条ADD的O(n²)模式）。
     * 保留消息自带 seq（恢复 REPLACE 时），计数器取 max(seq) 续号。
     */
    @SuppressWarnings("unchecked")
    protected TLMsg replace(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        List<TLConversationHistory> newHistory = (List<TLConversationHistory>)
                msg.getListParam(AI_P_MESSAGEHISTORY, null);
        if (newHistory != null) {
            List<TLConversationHistory> copy = new ArrayList<>(newHistory);
            if (toolResultCharLimit > 0) {
                for (TLConversationHistory h : copy) capToolResult(h);
            }
            sessions.put(sessionId, copy);
            trimHistory(sessions.get(sessionId));
            // 更新 seq 计数器为 max(seq)，之后的递增从恢复点继续
            long maxSeq = 0;
            for (TLConversationHistory h : sessions.get(sessionId)) {
                if (h.getSeq() > maxSeq) maxSeq = h.getSeq();
            }
            seqCounter.put(sessionId, maxSeq);
        } else {
            sessions.remove(sessionId);
            seqCounter.remove(sessionId);
        }
        return createMsg().setParam(RESULT, true);
    }

    /**
     * 获取session对话轮次
     */
    protected TLMsg getTurnCount(Object fromWho, TLMsg msg) {
        // msgTool 链路：executeMsgTool 已将 sessionId 复制进 systemArgs（msgTable 路由时 systemArgs 会传播）
        String sessionId = String.valueOf(msg.getSystemParam(AI_P_SESSIONID, "default"));
        List<TLConversationHistory> history = sessions.get(sessionId);
        int count = (history != null) ? history.size() : 0;
        return createMsg().setParam("turnCount", count);
    }

    /**
     * 获取视图：seq > fromSeq 的最新 limit 条消息（摘要 coverSeq 衔接）。
     * 边界修正：视图开头若为 tool 消息（其 assistant(tool_calls) 被 fromSeq 截掉），
     * 向前在完整历史中补入其 assistant，保证 OpenAI 消息顺序约束。
     */
    protected TLMsg getView(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        long fromSeq = msg.getIntParam("fromSeq", 0);
        int limit = msg.getIntParam("limit", 40);
        if (limit <= 0) limit = 40;
        List<TLConversationHistory> history = getOrCreateSession(sessionId);

        List<TLConversationHistory> filtered = new ArrayList<>();
        for (TLConversationHistory h : history) {
            if (h.getSeq() > fromSeq) filtered.add(h);
        }
        int startIdx = Math.max(0, filtered.size() - limit);
        // 孤儿 tool 修正：视图开头是 tool 时，向前补其 assistant(tool_calls)
        while (startIdx < filtered.size()
                && filtered.get(startIdx).getRole() == TLConversationHistory.Role.tool) {
            TLConversationHistory first = filtered.get(startIdx);
            int idxInFull = history.indexOf(first);
            if (idxInFull <= 0) break;
            boolean extended = false;
            for (int i = idxInFull - 1; i >= 0; i--) {
                TLConversationHistory h = history.get(i);
                if (h.isAssistantWithToolCalls()) {
                    List<TLConversationHistory> prefix =
                            new ArrayList<>(history.subList(i, idxInFull));
                    filtered.addAll(0, prefix);
                    startIdx += prefix.size();
                    extended = true;
                    break;
                }
            }
            if (!extended) break;
        }
        List<TLConversationHistory> result =
                new ArrayList<>(filtered.subList(Math.max(0, startIdx), filtered.size()));
        return createMsg().setParam(AI_P_MESSAGEHISTORY, result);
    }

    /** 获取会话当前最大 seq（记忆摘要 coverSeq 用） */
    protected TLMsg getMaxSeq(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        Long maxSeq = seqCounter.get(sessionId);
        return createMsg().setParam("maxSeq", maxSeq != null ? maxSeq : 0L);
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

    /** 会话内线性递增 seq（第一条消息 = 1；默认 system 消息保持 seq=0） */
    protected long nextSeq(String sessionId) {
        return seqCounter.merge(sessionId, 1L, Long::sum);
    }

    /** tool 结果源头限长（toolResultCharLimit，0=不限制）。仅作用于 tool 角色消息，
     *  截断后追加标记说明。history 一旦写入即定长，后续恢复/每轮发送/摘要都基于定长内容。 */
    protected void capToolResult(TLConversationHistory h) {
        if (h == null || toolResultCharLimit <= 0) return;
        if (h.getRole() != TLConversationHistory.Role.tool) return;
        String c = h.getContent();
        if (c == null || c.length() <= toolResultCharLimit) return;
        h.setContent(c.substring(0, toolResultCharLimit)
                + "\n…[tool 结果过长，已截断保留前 " + toolResultCharLimit + " 字符，如需完整内容请缩小操作范围]");
    }

    /**
     * 裁剪超长历史。保留system消息 + 最近N条。
     * maxContextMessages = -1 时全量保留（摘要 coverSeq 衔接，不丢信息）。
     * 保证不破坏 OpenAI API 消息顺序约束：tool 消息必须紧跟其 assistant(tool_calls)。
     */
    protected void trimHistory(List<TLConversationHistory> history) {
        if (maxContextMessages < 0) return;   // 全量保留模式
        if (history.size() <= maxContextMessages) return;

        // 收集system消息
        List<TLConversationHistory> systemMsgs = new ArrayList<>();
        for (TLConversationHistory h : history) {
            if (h.getRole() == TLConversationHistory.Role.system) {
                systemMsgs.add(h);
            }
        }

        int nonSystemCount = history.size() - systemMsgs.size();
        if (nonSystemCount <= maxContextMessages) return;

        int removeCount = nonSystemCount - maxContextMessages;
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
