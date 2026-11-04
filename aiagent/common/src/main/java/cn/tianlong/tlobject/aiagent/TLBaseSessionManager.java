package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.*;

/**
 * 会话管理抽象基类——消息分发、参数提取、roundId 去重、history 组装。
 *
 * <p><b>轮次状态（state）生命周期</b>——三个状态共用一列，但查找语义不同，改动前先读这段：</p>
 * <ul>
 *   <li>{@code checkpoint}：轮次进行中的最新快照（聊天入口、每次 LLM 迭代前、每批工具执行后各写一次）。
 *       进程被杀时最后一行仍是 checkpoint → findIncomplete 视为"意外中断"，从该轮继续；
 *       正常收尾写 completed 后按同 roundId last-wins 覆盖。</li>
 *   <li>{@code pending_approval}：审批<b>开态</b>。审批拦截时写入，故意不写 completed
 *       （否则断点会被覆盖成 completed，findIncomplete 再也找不到）；直到 resume 收尾或
 *       discardPendingRound 丢弃才关闭。黏住语义：其后任何新轮次写入都不得把它藏起来
 *       （assembleSession/findIncompleteMeta 有独立的"开态待批轮"查找分支）。</li>
 *   <li>{@code completed}：轮次已关闭（正常完成 / 用户中断 / 断点被丢弃）。findIncomplete 不再返回。</li>
 * </ul>
 *
 * <p>落盘粒度是"轮"，不是"迭代"：同一 roundId 的多次写入按 last-wins 收敛（文件版追加、
 * DB 版删后插）。resume 沿用原 roundId 收尾，因此调用方必须带 {@code stateRoundMessages}
 * 前缀，只写恢复后的新增量会把原轮 messages 永久覆盖丢。</p>
 *
 * 子类只需实现 4 个存储相关方法，即可支持文件或数据库等不同后端。
 *
 * @author tianlong
 * @date 2026/8/2
 */
public abstract class TLBaseSessionManager extends TLBaseModule implements TLAiAgentParamString {

    /** 是否开启断点保存/恢复 */
    protected boolean enableCheckpoint = false;

    /** JSON 序列化 */
    protected com.google.gson.Gson gson = new com.google.gson.Gson();

    // ======================== 构造函数 ========================

    public TLBaseSessionManager() { super(); }
    public TLBaseSessionManager(String name) { super(name); }
    public TLBaseSessionManager(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected TLBaseModule init() { return this; }

    // ======================== 子类必须实现的 4 个抽象方法 ========================

    /** 存储一轮会话数据 */
    protected abstract void storeRound(java.util.Map<String, Object> roundData);

    /** 加载指定会话的所有轮次原始数据，返回 List<Map>（每条包含 roundId, roundSeq, messages 等） */
    protected abstract java.util.List<java.util.Map<String, Object>> loadRoundData(String sessionId, String userId);

    /** 查找最近会话的元数据，返回 null 表示无会话 */
    protected abstract java.util.Map<String, Object> findLatestMeta(String userId);

    /**
     * 查找该用户最近的未完成断点会话（state=checkpoint 或 pending_approval），返回 null 表示无断点。
     * 注意：断点会话不一定是最新会话（断点后用户可能又聊过其他会话）——
     * 必须按 state 过滤查最近一条，而不是"最近会话再判状态"。
     * sessionId 非空时只在该会话内查找（断点弹框的"放弃该操作"路径按会话精确定位）。
     */
    protected abstract java.util.Map<String, Object> findIncompleteMeta(String userId, String sessionId);

    /** 删除指定会话的所有数据 */
    protected abstract void deleteSessionData(String sessionId, String userId);

    /** 列出所有会话的元数据列表（每条含 title 字段，无标题为空串） */
    protected abstract java.util.List<java.util.Map<String, Object>> listSessionsMeta(String userId);

    /**
     * 设置会话标题。onlyIfEmpty=true 时仅在当前无标题时写入（agent 自动起名用）；
     * false 时直接覆盖（前端改名用）。返回是否真的写了（条件不满足/会话不存在/写失败 → false）。
     */
    protected abstract boolean setSessionTitle(String sessionId, String userId, String title, boolean onlyIfEmpty);

    // ======================== 消息分发 ========================

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case "sessionUpdated":  return doSessionUpdated(fromWho, msg);
            case "chatFinished":    return doChatFinished(fromWho, msg);
            case "chatAborted":     return doChatAborted(fromWho, msg);
            case "findIncomplete":  return doFindIncomplete(fromWho, msg);
            case "listSessions":    return doListSessions(fromWho, msg);
            case "loadSession":     return doLoadSession(fromWho, msg);
            case "loadRound":       return doLoadRound(fromWho, msg);
            case "continueSession": return doContinueSession(fromWho, msg);
            case "deleteSession":   return doDeleteSession(fromWho, msg);
            case "setSessionTitle": return doSetSessionTitle(fromWho, msg);
            case "discardPendingRound": return doDiscardPendingRound(fromWho, msg);
            default: return null;
        }
    }

    // ======================== Agent 通知处理 ========================

    @SuppressWarnings("unchecked")
    private TLMsg doSessionUpdated(Object fromWho, TLMsg msg) {
        if (!enableCheckpoint) return createMsg().setParam(RESULT, true);
        storeRound(buildRoundData(msg, msg.containsParam("pendingToolCall")
                ? SESSION_STATE_PENDING_APPROVAL : SESSION_STATE_CHECKPOINT));
        return createMsg().setParam(RESULT, true);
    }

    @SuppressWarnings("unchecked")
    private TLMsg doChatFinished(Object fromWho, TLMsg msg) {
        if (!enableCheckpoint) return createMsg().setParam(RESULT, true);
        storeRound(buildRoundData(msg, SESSION_STATE_COMPLETED));
        return createMsg().setParam(RESULT, true);
    }

    @SuppressWarnings("unchecked")
    private TLMsg doChatAborted(Object fromWho, TLMsg msg) {
        if (!enableCheckpoint) return createMsg().setParam(RESULT, true);
        // 人为停止（ESC/停止）存为 completed：本轮视为结束，不留断点。
        // 断点恢复只针对意外死机（进程被杀，agent 来不及收尾时 checkpoint 轮残留）。
        storeRound(buildRoundData(msg, SESSION_STATE_COMPLETED));
        return createMsg().setParam(RESULT, true);
    }

    /** 从消息中提取参数，构建轮次数据 Map */
    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> buildRoundData(TLMsg msg, String state) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("sessionId", msg.getStringParam("sessionId", ""));
        data.put("userId", msg.getStringParam("userId", ""));
        data.put("agentName", msg.getStringParam("agentName", ""));
        data.put("roundId", msg.getStringParam("roundId", ""));
        data.put("state", state);
        data.put("userMessage", msg.getStringParam("userMessage", ""));
        data.put("model", msg.getStringParam("model", ""));
        data.put("temperature", msg.getDoubleParam("temperature", 0.7));
        data.put("maxTokens", msg.getIntParam("maxTokens", 4096));
        data.put("messages", msg.getParam("messages"));
        if (msg.containsParam("pendingToolCall"))
            data.put("pendingToolCall", msg.getParam("pendingToolCall"));
        String approvalId = msg.getStringParam(AI_P_APPROVAL_ID, "");
        if (!approvalId.isEmpty()) data.put(AI_P_APPROVAL_ID, approvalId);
        return data;
    }

    // ======================== 控制台/agentService 查询 ========================

    /** 查找最近一个未完成的断点（checkpoint 或 pending_approval；可选按 sessionId 限定） */
    private TLMsg doFindIncomplete(Object fromWho, TLMsg msg) {
        // 查最近的未完成会话（按 state 过滤，而非"最近会话再判状态"——
        // 断点后用户可能又聊过其他会话，最近会话是 completed 也应能找到断点）
        String userId = msg.getStringParam("userId", null);
        String sessionId = msg.getStringParam("sessionId", null);
        java.util.Map<String, Object> meta = findIncompleteMeta(userId, sessionId);
        if (meta == null) return createMsg().setParam(RESULT, false);
        String state = String.valueOf(meta.getOrDefault("state", ""));
        // approvalId 统一从轮次明细取（会话汇总表无该列；取该会话最后一个待批轮）。
        // 必须与 findIncompleteMeta 同口径（roundId last-wins + roundSeq 最大）：文件版追加写，
        // 直接遍历原始行会取到已被 chatFinished 覆盖的旧 pending 行（可能带着已失效的 approvalId）。
        if (SESSION_STATE_PENDING_APPROVAL.equals(state)) {
            java.util.Map<String, Object> target = findOpenPendingRound(
                    loadRoundData(String.valueOf(meta.getOrDefault("sessionId", "")), userId));
            if (target != null) {
                Object aid = target.get(AI_P_APPROVAL_ID);
                if (aid != null && !String.valueOf(aid).isEmpty())
                    meta.put(AI_P_APPROVAL_ID, String.valueOf(aid));
            }
        }
        return createMsg()
                .setParam(RESULT, true)
                .setParam("sessionId", meta.getOrDefault("sessionId", ""))
                .setParam("agentName", meta.getOrDefault("agentName", ""))
                .setParam("userMessage", meta.getOrDefault("userMessage", ""))
                .setParam("savedAt", meta.getOrDefault("savedAt", 0L))
                .setParam("iteration", meta.getOrDefault("roundSeq", 0))
                .setParam("state", state)
                .setParam(AI_P_APPROVAL_ID, meta.getOrDefault(AI_P_APPROVAL_ID, ""));
    }

    /** 丢弃待批断点：把该会话最后一个 pending_approval 轮改为 completed（放弃该操作后会话可正常继续） */
    private TLMsg doDiscardPendingRound(Object fromWho, TLMsg msg) {
        if (!enableCheckpoint) return createMsg().setParam(RESULT, true);
        String sessionId = msg.getStringParam("sessionId", "");
        String userId = msg.getStringParam("userId", null);
        if (sessionId.isEmpty()) return createMsg().setParam(RESULT, false).setParam("error", "missing sessionId");
        java.util.List<java.util.Map<String, Object>> rounds = loadRoundData(sessionId, userId);
        // 与 findIncomplete / assembleSession 同口径：先按 roundId last-wins，再取 roundSeq 最大的
        // 开态待批轮。直接遍历原始行会挑到"已被 chatFinished 覆盖、但旧 pending 行还在文件里"的
        // 陈旧行（文件版 storeRound 是追加写），也可能在多开态待批轮时挑错轮次。
        java.util.Map<String, Object> target = findOpenPendingRound(rounds);
        if (target == null) {
            return createMsg().setParam(RESULT, false).setParam("error", "no pending_approval round");
        }
        target.put("state", SESSION_STATE_COMPLETED);
        // 残留的 pendingToolCall/approvalId 会被恢复路径（assembleSession/doLoadRound）重新暴露——被取消的工具调用不得复活
        target.remove("pendingToolCall");
        target.remove(AI_P_APPROVAL_ID);
        // 新鲜度校验：目标轮必须仍是该会话的待批轮——窗口内会话已继续的话，
        // 用陈旧快照重存会以 last-wins 覆盖新一轮（历史里终答丢失的故障类）
        java.util.Map<String, Object> freshTarget =
                findOpenPendingRound(loadRoundData(sessionId, userId));
        if (freshTarget == null
                || !String.valueOf(freshTarget.get("roundId")).equals(String.valueOf(target.get("roundId")))) {
            return createMsg().setParam(RESULT, false).setParam("error", "断点状态已变化（会话已继续），未执行丢弃");
        }
        storeRound(target);
        // 写后验证：storeRound 静默吞异常，不得在未落盘时谎报成功（假成功一族）
        java.util.Map<String, Object> after = findIncompleteMeta(userId, sessionId);
        if (after != null && SESSION_STATE_PENDING_APPROVAL.equals(String.valueOf(after.get("state")))) {
            return createMsg().setParam(RESULT, false).setParam("error", "丢弃未落盘（storeRound 失败），请重试");
        }
        return createMsg().setParam(RESULT, true);
    }

    /**
     * 同 roundId last-wins（文件版 storeRound 为追加写，DB 版为删后插）。
     * 断点丢弃/新鲜度校验必须与 assembleSession 用同一口径，否则会挑到被后续
     * chatFinished 覆盖的旧 pending 行。
     */
    private java.util.LinkedHashMap<String, java.util.Map<String, Object>> latestRoundsByRoundId(
            java.util.List<java.util.Map<String, Object>> rounds) {
        java.util.LinkedHashMap<String, java.util.Map<String, Object>> byId = new java.util.LinkedHashMap<>();
        if (rounds == null) return byId;
        for (java.util.Map<String, Object> r : rounds) {
            if (r == null) continue;
            byId.put(String.valueOf(r.getOrDefault("roundId", "")), r);
        }
        return byId;
    }

    /** 开态待批轮：去重后状态仍为 pending_approval、roundSeq 最大者；无则 null */
    private java.util.Map<String, Object> findOpenPendingRound(
            java.util.List<java.util.Map<String, Object>> rounds) {
        java.util.Map<String, Object> best = null;
        int bestSeq = Integer.MIN_VALUE;
        for (java.util.Map<String, Object> r : latestRoundsByRoundId(rounds).values()) {
            if (!SESSION_STATE_PENDING_APPROVAL.equals(String.valueOf(r.get("state")))) continue;
            int seq = roundSeqOf(r);
            if (seq > bestSeq) {
                bestSeq = seq;
                best = r;
            }
        }
        return best;
    }

    /** 兼容各后端 roundSeq 类型（文件版 Integer、DB 版 objToInt、旧数据 String/Double） */
    private static int roundSeqOf(java.util.Map<String, Object> r) {
        Object v = r.get("roundSeq");
        if (v instanceof Number) return ((Number) v).intValue();
        if (v == null) return 0;
        try { return (int) Double.parseDouble(String.valueOf(v).trim()); }
        catch (Exception ignored) { return 0; }
    }

    /** 列出所有历史会话 */
    @SuppressWarnings("unchecked")
    private TLMsg doListSessions(Object fromWho, TLMsg msg) {
        java.util.List<java.util.Map<String, Object>> sessions = listSessionsMeta(msg.getStringParam("userId", null));
        if (sessions == null) sessions = java.util.Collections.emptyList();
        return createMsg().setParam(RESULT, true).setParam("sessions", sessions);
    }

    /** 加载指定会话的完整数据 */
    @SuppressWarnings("unchecked")
    private TLMsg doLoadSession(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam("sessionId", "");
        String userId = msg.getStringParam("userId", null);
        TLMsg assembled = assembleSession(sessionId, userId);
        if (assembled == null)
            return createMsg().setParam(RESULT, false).setParam("error", "No checkpoint found");
        return assembled;
    }

    /**
     * 加载指定轮次（roundId + agentName）的完整消息——trace 定点重放的数据源。
     * 同 roundId 多行取最后一条（storeRound 对同 roundId 删后插，last-wins 语义与 assembleSession 一致）。
     * agentName 过滤防御：子 agent 若开了 checkpoint 会以相同 (session_id, round_id) 覆盖父行。
     */
    @SuppressWarnings("unchecked")
    private TLMsg doLoadRound(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam("sessionId", "");
        String userId = msg.getStringParam("userId", null);
        String roundId = msg.getStringParam("roundId", "");
        String agentName = msg.getStringParam("agentName", "");
        if (sessionId.isEmpty() || roundId.isEmpty())
            return createMsg().setParam(RESULT, false).setParam("error", "sessionId/roundId 必填");

        java.util.List<java.util.Map<String, Object>> rounds = loadRoundData(sessionId, userId);
        if (rounds == null || rounds.isEmpty())
            return createMsg().setParam(RESULT, false).setParam("error", "轮次消息未持久化: " + roundId);

        java.util.Map<String, Object> target = null;
        for (java.util.Map<String, Object> r : rounds) {
            if (roundId.equals(r.get("roundId"))
                    && (agentName.isEmpty() || agentName.equals(r.get("agentName")))) {
                target = r;  // last-wins
            }
        }
        if (target == null)
            return createMsg().setParam(RESULT, false).setParam("error", "轮次消息未持久化: " + roundId);

        List<TLConversationHistory> history = new ArrayList<>();
        Object msgs = target.get("messages");
        if (msgs instanceof List) {
            for (Object m : (List<?>) msgs) {
                if (m instanceof TLConversationHistory)
                    history.add((TLConversationHistory) m);
            }
        }

        TLMsg result = new TLMsg();
        result.setParam(RESULT, true);
        result.setParam("sessionId", target.getOrDefault("sessionId", ""));
        result.setParam("roundId", target.getOrDefault("roundId", ""));
        result.setParam("state", target.getOrDefault("state", ""));
        result.setParam("agentName", target.getOrDefault("agentName", ""));
        result.setParam("userMessage", target.getOrDefault("userMessage", ""));
        result.setParam("model", target.getOrDefault("model", ""));
        result.setParam("temperature", target.getOrDefault("temperature", 0.7));
        result.setParam("maxTokens", target.getOrDefault("maxTokens", 4096));
        result.setParam("history", history);
        result.setParam("count", history.size());
        return result;
    }

    /** 继续历史会话 */
    @SuppressWarnings("unchecked")
    private TLMsg doContinueSession(Object fromWho, TLMsg msg) {
        String targetId = msg.getStringParam("sessionId", null);
        String userId = msg.getStringParam("userId", null);

        if (targetId == null || targetId.isEmpty()) {
            java.util.Map<String, Object> latest = findLatestMeta(userId);
            if (latest != null) targetId = (String) latest.get("sessionId");
            if (targetId == null)
                return createMsg().setParam(RESULT, false).setParam("error", "No session to continue");
        }

        TLMsg assembled = assembleSession(targetId, userId);
        if (assembled == null)
            return createMsg().setParam(RESULT, false).setParam("error", "Session not found: " + targetId);

        List<TLConversationHistory> history =
                (List<TLConversationHistory>) assembled.getParam("history");
        return createMsg()
                .setParam(RESULT, true)
                .setParam("sessionId", targetId)
                .setParam("count", history != null ? history.size() : 0)
                .setParam("history", history);
    }

    /** 删除会话（先校验存在性，避免删除不存在的会话也返回成功） */
    @SuppressWarnings("unchecked")
    private TLMsg doDeleteSession(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam("sessionId", "");
        String userId = msg.getStringParam("userId", null);
        if (sessionId.isEmpty()) return createMsg().setParam(RESULT, false).setParam("error", "sessionId 必填");
        // 存在性校验：在用户会话列表中查找（只允许删自己的会话）
        boolean exists = false;
        try {
            java.util.List<java.util.Map<String, Object>> sessions = listSessionsMeta(userId);
            if (sessions != null) {
                for (java.util.Map<String, Object> s : sessions) {
                    if (sessionId.equals(s.get("sessionId"))) { exists = true; break; }
                }
            }
        } catch (Exception ignored) {}
        if (!exists) return createMsg().setParam(RESULT, false).setParam("error", "会话不存在: " + sessionId);
        deleteSessionData(sessionId, userId);
        return createMsg().setParam(RESULT, true);
    }

    // ======================== 会话标题 ========================

    /**
     * 设置/更新会话标题（webui 侧栏改名、agent 自动起名共用）。
     * 契约：RESULT=true + updated（是否真的写了）；sessionId 缺失为参数错误 → RESULT=false。
     */
    private TLMsg doSetSessionTitle(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam("sessionId", "");
        if (sessionId == null || sessionId.isEmpty())
            return createMsg().setParam(RESULT, false).setParam("error", "sessionId 必填");
        String userId = msg.getStringParam("userId", null);
        String title = msg.getStringParam("title", "");
        if (title == null) title = "";
        boolean onlyIfEmpty = msg.getBooleanParam("onlyIfEmpty", false);
        boolean updated = setSessionTitle(sessionId, userId, title, onlyIfEmpty);
        return createMsg().setParam(RESULT, true).setParam("updated", updated);
    }

    // ======================== 公共逻辑：轮次组装 ========================

    /**
     * 加载所有轮次，按 roundId 去重（同轮多次通知取最后一条），
     * 按 roundSeq 排序，拼接所有 messages 为完整 history。
     */
    @SuppressWarnings("unchecked")
    protected TLMsg assembleSession(String sessionId, String userId) {
        if (sessionId == null || sessionId.isEmpty()) return null;
        java.util.List<java.util.Map<String, Object>> rounds = loadRoundData(sessionId, userId);
        if (rounds == null || rounds.isEmpty()) return null;

        // roundId → 最后一条
        java.util.LinkedHashMap<String, java.util.Map<String, Object>> roundMap = new java.util.LinkedHashMap<>();
        java.util.Map<String, Object> lastRound = null;
        for (java.util.Map<String, Object> r : rounds) {
            String rid = (String) r.getOrDefault("roundId", "");
            roundMap.put(rid, r);
            lastRound = r;
        }
        if (lastRound == null) return null;

        // 按 roundSeq 排序
        java.util.List<java.util.Map<String, Object>> sorted = new java.util.ArrayList<>(roundMap.values());
        sorted.sort((a, b) -> {
            // 兼容不同后端：文件版已把 Double→Integer，DB 版 objToInt；未来新增后端
            // 若返回 Long/Double，instanceof Integer 会一律当 0，排序全乱。
            int sa = a.get("roundSeq") instanceof Number ? ((Number) a.get("roundSeq")).intValue() : 0;
            int sb = b.get("roundSeq") instanceof Number ? ((Number) b.get("roundSeq")).intValue() : 0;
            return Integer.compare(sa, sb);
        });

        // 拼接 messages；同时记录每个 roundId 贡献的块。
        // resume 落盘需要"原轮已有消息"作为前缀：同 roundId 的写入是 last-wins（文件版追加、
        // DB 版删后插），恢复轮只带新增量重写同一行会把原轮 messages 覆盖掉——断点前历史永久丢失。
        List<TLConversationHistory> history = new ArrayList<>();
        java.util.Map<String, List<TLConversationHistory>> roundBlocks = new java.util.HashMap<>();
        for (java.util.Map<String, Object> r : sorted) {
            String blockRoundId = String.valueOf(r.getOrDefault("roundId", ""));
            int blockStart = history.size();
            Object msgs = r.get("messages");
            // 恢复时把本轮 userMessage 补回数组头：存量轮次的 messages 数组从 assistant 开始
            // （user 消息只在 round 的 user_message 字段单独存），不补则恢复后：
            // ① 页面看不到自己的问话；② LLM 收到"无提问的答案"，上下文残缺。
            // 守卫：首条已是 user 的不补（防未来 save 侧改为含 user 时重复）。
            boolean firstIsUser = msgs instanceof List && !((List<?>) msgs).isEmpty()
                    && ((List<?>) msgs).get(0) instanceof TLConversationHistory
                    && ((TLConversationHistory) ((List<?>) msgs).get(0)).getRole() == TLConversationHistory.Role.user;
            Object um = r.get("userMessage");
            if (!firstIsUser && um != null && !String.valueOf(um).isEmpty()) {
                // 二次去重：历史末尾已是同一句 user 时不补（防某轮增量本身带 user 时重复——
                // 相邻重复 user 消息会触发 API 400）
                boolean dupAtTail = !history.isEmpty()
                        && history.get(history.size() - 1).getRole() == TLConversationHistory.Role.user
                        && String.valueOf(um).equals(history.get(history.size() - 1).getContent());
                if (!dupAtTail) {
                    history.add(new TLConversationHistory(TLConversationHistory.Role.user, String.valueOf(um)));
                }
            }
            if (msgs instanceof List) {
                for (Object m : (List<?>) msgs) {
                    if (m instanceof TLConversationHistory)
                        history.add((TLConversationHistory) m);
                }
            }
            roundBlocks.put(blockRoundId, new ArrayList<>(history.subList(blockStart, history.size())));
        }

        // 待批轮优先作为"状态载体"：pending_approval 是开态，直到被 resume 收尾——
        // 其后任何新轮次写入（后台任务注入/其他写入）都不得把断点从恢复入口藏起来
        java.util.Map<String, Object> stateRound = lastRound;
        java.util.Map<String, Object> openPending = null;
        for (java.util.Map<String, Object> r : roundMap.values()) {
            if (!SESSION_STATE_PENDING_APPROVAL.equals(String.valueOf(r.get("state")))) continue;
            // 与上面的排序同一兼容口径：后端可能返回 Long/Double，instanceof Integer 会一律当 0，
            // 多个待批轮时判不出"最后"的那个
            int seq = r.get("roundSeq") instanceof Number ? ((Number) r.get("roundSeq")).intValue() : 0;
            int bestSeq = openPending != null && openPending.get("roundSeq") instanceof Number
                    ? ((Number) openPending.get("roundSeq")).intValue() : -1;
            if (openPending == null || seq > bestSeq) openPending = r;
        }
        if (openPending != null) stateRound = openPending;

        TLMsg result = new TLMsg();
        result.setParam(RESULT, true);
        result.setParam("sessionId", stateRound.getOrDefault("sessionId", ""));
        result.setParam("state", stateRound.getOrDefault("state", ""));
        result.setParam("agentName", stateRound.getOrDefault("agentName", ""));
        result.setParam("userMessage", stateRound.getOrDefault("userMessage", ""));
        result.setParam("model", stateRound.getOrDefault("model", ""));
        result.setParam("temperature", stateRound.getOrDefault("temperature", 0.7));
        result.setParam("maxTokens", stateRound.getOrDefault("maxTokens", 4096));
        result.setParam("round", stateRound.getOrDefault("roundSeq", 0));
        result.setParam("roundId", stateRound.getOrDefault("roundId", ""));
        result.setParam("userId", stateRound.getOrDefault("userId", ""));
        result.setParam("history", history);
        result.setParam("count", history.size());
        if (stateRound.containsKey("pendingToolCall"))
            result.setParam("pendingToolCall", stateRound.get("pendingToolCall"));
        if (stateRound.containsKey(AI_P_APPROVAL_ID))
            result.setParam(AI_P_APPROVAL_ID, stateRound.get(AI_P_APPROVAL_ID));
        // 恢复入口（TLAgentService resume）把它作为本轮落盘前缀转给 agent，
        // 保证恢复轮按同 roundId 重写时原轮消息不丢
        List<TLConversationHistory> stateBlock =
                roundBlocks.get(String.valueOf(stateRound.getOrDefault("roundId", "")));
        if (stateBlock != null) result.setParam("stateRoundMessages", stateBlock);

        return result;
    }
}
