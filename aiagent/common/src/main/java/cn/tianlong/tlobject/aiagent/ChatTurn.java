package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * 一轮 chat 的执行主体（瞬态 TLBaseModule：new，不 start/不注册/不读配置）。
 *
 * <p>职责：LLM 调用（同步/流式）→ tool_calls 解析/执行 → 结果入史 → 终态判定，
 * 然后交 TurnCommitter 做 checkpoint/commit。会话锁/取消/运行计数仍在 Agent/SessionRegistry。</p>
 *
 * <p>名字：{@code turn:<agent>:<roundId>}，不注册；工具执行消息显式带 AI_P_AGENTNAME，
 * 工具进度回到本实例后透传父 Agent。</p>
 */
final class ChatTurn extends TLBaseModule implements TLAiAgentParamString {

    private static final boolean STREAM_TOOL_ROUNDS = false;

    enum Phase { CREATED, RUNNING, COMMITTED, CLOSED }

    private final TurnSeed seed;
    private final TurnDeps deps;

    private final List<TLConversationHistory> history;
    private final int msgStartIdx;
    private final String memoryContext;
    private final List<TLFunctionDefinition> toolDefs;

    private Phase phase = Phase.CREATED;
    private int iteration;
    private final long[] turnTokens = {0, 0, 0};
    private final long[] cacheTurn = {0, 0, 0};
    private final StringBuilder allReasoning = new StringBuilder();
    private boolean finalAnswerStreamed;
    private boolean streamFinished;
    private int finalAssistantIdx = -1;
    /** 本轮因"清单未完成"被强制续跑的次数（上限 seed.todoGateMaxRetries） */
    private int todoGateRetries;

    /** 生产构造器：带 factory（putLog 走日志模块）。 */
    ChatTurn(String name, TLObjectFactory factory, TurnSeed seed, TurnDeps deps) {
        super(name, factory);
        this.seed = seed;
        this.deps = deps;
        this.history = new ArrayList<>(seed.history);
        this.msgStartIdx = seed.msgStartIdx;
        this.memoryContext = seed.memoryContext;
        this.toolDefs = seed.toolDefs == null ? new ArrayList<>() : new ArrayList<>(seed.toolDefs);
    }

    /** 单测构造器：不带 factory（putLog 不落日志模块）。 */
    ChatTurn(String name, TurnSeed seed, TurnDeps deps) {
        super(name);
        this.seed = seed;
        this.deps = deps;
        this.history = new ArrayList<>(seed.history);
        this.msgStartIdx = seed.msgStartIdx;
        this.memoryContext = seed.memoryContext;
        this.toolDefs = seed.toolDefs == null ? new ArrayList<>() : new ArrayList<>(seed.toolDefs);
    }

    @Override
    protected TLBaseModule init() {
        // 瞬态对象：不从工厂/配置初始化；构造器已完成状态装配
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        // 工具执行器把进度回推给 fromWho（本 Turn 实例）；无状态透传给父 Agent。
        if (TOOL_PROGRESS.equals(msg.getAction()) && deps.parentAgent != null) {
            return putMsg(deps.parentAgent, msg);
        }
        return null;
    }

    TLMsg run() {
        if (phase != Phase.CREATED)
            throw new IllegalStateException("ChatTurn.run called twice: " + seed.roundId);
        phase = Phase.RUNNING;

        int maxIterations = seed.maxToolCallIterations > 0 ? seed.maxToolCallIterations : 10;
        while (iteration < maxIterations) {
            ThreadTask.checkPauseHere();
            if (isCancelled() || ThreadTask.isCurrentCancelled()) {
                boolean byUser = isCancelled();
                return finish(new TurnOutcome.Aborted(byUser ? "user" : "threadTaskCancelled",
                        byUser, tokens(), iteration));
            }
            iteration++;

            deps.committer.checkpoint(record());
            List<TLConversationHistory> sendList =
                    deps.viewBuilder.build(history, memoryContext, msgStartIdx);
            TLMsg llmMsg = buildLlmMsg(sendList);
            deps.observer.traceStage(seed.sessionId, seed.rootSessionId, seed.roundId, seed.userId,
                    "llmRequest", "iter=" + iteration + " model=" + seed.model, 0,
                    deps.observer.formatHistory(sendList));
            long llmStartTs = System.currentTimeMillis();
            TLMsg llmResponse = invokeLlm(llmMsg);

            if (llmResponse == null) {
                if (isCancelled())
                    return finish(new TurnOutcome.Aborted("user", true, tokens(), iteration));
                return finish(new TurnOutcome.Failed(
                        "LLM Provider 无响应（provider 内部错误或提交失败）"));
            }
            if (isCancelled() || llmResponse.parseBoolean(AI_P_CANCELLED, false)) {
                boolean byUser = isCancelled();
                return finish(new TurnOutcome.Aborted(byUser ? "user" : "llmCancelled",
                        byUser, tokens(), iteration));
            }
            if (!llmResponse.parseBoolean(RESULT, false)) {
                String errMsg = llmResponse.getStringParam(AI_P_RESPONSE, null);
                if (errMsg == null || errMsg.isEmpty())
                    errMsg = "LLM 返回错误：HTTP " + llmResponse.getIntParam(AI_P_HTTPSTATUS, 0);
                return finish(new TurnOutcome.Failed(errMsg));
            }

            accumulateTokens(llmResponse);
            deps.observer.reportTokenUsage(seed.sessionId, seed.rootSessionId, seed.userId,
                    lastUsage, lastCache);
            deps.observer.traceStage(seed.sessionId, seed.rootSessionId, seed.roundId, seed.userId,
                    "llmResponse",
                    "tokens=" + turnTokens[1] + "/" + turnTokens[2]
                            + " cache=" + cacheTurn[1] + "/" + cacheTurn[0],
                    System.currentTimeMillis() - llmStartTs,
                    deps.observer.formatResponse(
                            llmResponse.getStringParam(AI_P_RESPONSE, ""),
                            llmResponse.parseBoolean("hasToolCalls", false)
                                    ? (List<TLToolCall>) llmResponse.getListParam(AI_P_TOOLCALLS, null) : null,
                            llmResponse.getStringParam(AI_P_REASONING, "")));

            // 推理文本要挂到 assistant 消息上回传（DeepSeek thinking 模式要求），
            // 与"是否展示给用户"无关——off 模式也要保留，只是不打印/不注入独立 reasoning 条目。
            String assistantReasoning = extractReasoning(llmResponse);
            boolean hasToolCalls = llmResponse.parseBoolean("hasToolCalls", false);
            @SuppressWarnings("unchecked")
            List<TLToolCall> toolCalls =
                    (List<TLToolCall>) llmResponse.getListParam(AI_P_TOOLCALLS, null);

            if (!hasToolCalls || toolCalls == null || toolCalls.isEmpty()) {
                finalAnswerStreamed = isStreaming();
                String turnFinish = llmResponse.getStringParam(AI_P_FINISH_REASON, "");
                String rawResponse = llmResponse.getStringParam(AI_P_RESPONSE, "");
                if (rawResponse.isEmpty() && seed.streaming() && seed.sink != null) {
                    // Provider 的 done 契约未强制携带完整正文：缺失时回退到 UI 已收到的 chunk，
                    // 否则历史落「（无输出）」且 streamReplace 会把 UI 正文整体覆盖成占位符。
                    String collected = seed.sink.collectedText();
                    if (collected != null && !collected.isEmpty()) {
                        rawResponse = collected;
                        putLog("Stream done missing response; fallback to sink buffer ("
                                + collected.length() + " chars)", LogLevel.WARN);
                    }
                }
                String finalResponse = deps.observer.withTruncationNotice(rawResponse, turnFinish);
                if (finalResponse.isEmpty())
                    finalResponse = deps.observer.emptyHistoryPlaceholder(turnFinish);
                // 清单门禁：本轮写过 todo 且仍有 pending/in_progress → 不接收尾，提醒继续。
                // 只在正文未流式发出时拦截（迭代轮默认非流式；首轮流式终答没有清单可谈）。
                String todoReminder = unfinishedTodoReminder();
                if (todoReminder != null && !finalAnswerStreamed
                        && todoGateRetries < seed.todoGateMaxRetries) {
                    todoGateRetries++;
                    history.add(new TLConversationHistory(TLConversationHistory.Role.system, todoReminder));
                    putLog("[turn:" + seed.roundId + "] todo gate 触发：清单未完成，继续执行 ("
                            + todoGateRetries + "/" + seed.todoGateMaxRetries + ")", LogLevel.INFO);
                    continue;
                }
                if (todoReminder != null && !finalAnswerStreamed) {
                    // 重试用尽：不伪装完成，终答明确标注仍有未完成项
                    finalResponse = finalResponse + "\n\n（注意：任务清单仍有未完成项，详见任务清单。）";
                    putLog("[turn:" + seed.roundId + "] todo gate 重试用尽，收尾但标注未完成", LogLevel.WARN);
                }
                // 流式/非流式统一存带截断提示的 finalResponse：否则非流式的下一轮 LLM
                // 不知道自己上一答被截断，与流式上下文语义分叉（返回给调用方的已是 finalResponse）。
                String stored = finalResponse;
                TLConversationHistory finalMsg = new TLConversationHistory(
                        TLConversationHistory.Role.assistant,
                        stored.isEmpty() ? deps.observer.emptyHistoryPlaceholder(turnFinish) : stored);
                if (assistantReasoning != null && !assistantReasoning.isEmpty())
                    finalMsg.setReasoningContent(assistantReasoning);
                history.add(finalMsg);
                finalAssistantIdx = history.size() - 1;
                return finish(new TurnOutcome.Completed(finalResponse, iteration, tokens(),
                        reasoningOut(), finalAssistantIdx));
            }

            putLog("[turn:" + seed.roundId + "] Tool calls: " + toolCalls.size()
                    + " (iter " + iteration + ")", LogLevel.DEBUG);
            TLConversationHistory aMsg = new TLConversationHistory(
                    TLConversationHistory.Role.assistant, new ArrayList<>(toolCalls));
            if (llmResponse.containsParam(AI_P_RESPONSE))
                aMsg.setContent(llmResponse.getStringParam(AI_P_RESPONSE, null));
            if (assistantReasoning != null && !assistantReasoning.isEmpty())
                aMsg.setReasoningContent(assistantReasoning);
            history.add(aMsg);
            int aMsgIdx = history.size() - 1;

            TLMsg resolveMsg = createMsg().setAction(AGENT_RESOLVETOOLCALLS)
                    .setParam(AI_P_TOOLCALLS, toolCalls);
            resolveMsg.setSource(seed.agentName);
            TLMsg frResolveMsg = putMsg(deps.toolManager, resolveMsg);
            if (frResolveMsg == null)
                putLog("Resolve toolCalls returned null, no tool executed", LogLevel.WARN);
            @SuppressWarnings("unchecked")
            List<TLToolExecutor.ToolTask> tasks = frResolveMsg != null
                    ? (List<TLToolExecutor.ToolTask>) frResolveMsg.getListParam("tasks", new ArrayList<>())
                    : new ArrayList<>();
            if (tasks.isEmpty()) {
                history.remove(aMsgIdx);
                String fail = "（工具解析失败：本轮未执行任何工具）";
                history.add(new TLConversationHistory(TLConversationHistory.Role.assistant, fail));
                finalAssistantIdx = history.size() - 1;
                return finish(new TurnOutcome.Completed(fail, iteration, tokens(),
                        reasoningOut(), finalAssistantIdx));
            }

            TLMsg execResult = executeTasks(tasks);
            if (execResult == null) {
                putLog("toolExecutor returned null, no tool executed", LogLevel.ERROR);
                history.remove(aMsgIdx);
                String fail = "（工具执行器无响应，本轮未完成）";
                history.add(new TLConversationHistory(TLConversationHistory.Role.assistant, fail));
                finalAssistantIdx = history.size() - 1;
                return finish(new TurnOutcome.Completed(fail, iteration, tokens(),
                        reasoningOut(), finalAssistantIdx));
            }

            boolean execAborted = execResult.parseBoolean("aborted", false);
            boolean execRejected = execResult.parseBoolean("rejected", false);
            boolean execPending = execResult.parseBoolean("pendingApproval", false);
            boolean execClarified = execResult.parseBoolean("clarified", false);
            boolean execTimeout = execResult.parseBoolean("hasTimeout", false);
            boolean execFinal = execResult.parseBoolean(AI_P_FINALANSWER, false);
            String execAbortReason = execResult.getStringParam("abortReason", null);

            if (execAborted || isCancelled()) {
                boolean byUser = isCancelled();
                String reason = byUser ? "user"
                        : (execAbortReason != null && !execAbortReason.isEmpty()
                                ? execAbortReason : "toolExecutorAborted");
                return finish(new TurnOutcome.Aborted(reason, byUser, tokens(), iteration));
            }
            if (execRejected || execPending || execClarified || execTimeout) {
                history.remove(aMsgIdx);
            }
            if (execRejected) {
                String rejectedReason = execResult.getStringParam("rejectReason", "");
                history.add(new TLConversationHistory(TLConversationHistory.Role.user,
                        "（操作已被用户拒绝：" + rejectedReason + "。不要重试此操作。）"));
                String finalResponse = execResult.getStringParam("finalResponse", "");
                if (finalResponse.isEmpty()) {
                    finalResponse = "（操作已被用户拒绝"
                            + (rejectedReason.isEmpty() ? "" : "：" + rejectedReason) + "）";
                }
                history.add(new TLConversationHistory(TLConversationHistory.Role.assistant,
                        finalResponse));
                finalAssistantIdx = history.size() - 1;
                return finish(new TurnOutcome.Rejected(rejectedReason, finalResponse, iteration,
                        tokens(), finalAssistantIdx));
            }
            if (execPending) {
                String approvalId = execResult.getStringParam(AI_P_APPROVAL_ID, "");
                TLToolCall pendingTc = pickPendingToolCall(toolCalls, execResult);
                if (pendingTc == null) {
                    // 无法定位具体工具：仍落待批断点（供 UI 丢弃/审批），但恢复时不能执行
                    putLog("Pending approval without locatable toolCall: approvalId=" + approvalId,
                            LogLevel.ERROR);
                }
                deps.committer.checkpointPending(record(),
                        new PendingInfo(approvalId, pendingTc));
                String finalResponse = execResult.getStringParam("finalResponse", "");
                return finish(new TurnOutcome.PendingApproval(approvalId,
                        execResult.getStringParam("pendingToolCallId", ""),
                        finalResponse, iteration, tokens()));
            }
            if (execClarified) {
                String finalResponse = execResult.getStringParam("finalResponse", "");
                if (finalResponse.isEmpty()) finalResponse = "（需要澄清，请回答上述问题）";
                history.add(new TLConversationHistory(TLConversationHistory.Role.assistant,
                        finalResponse));
                finalAssistantIdx = history.size() - 1;
                return finish(new TurnOutcome.Clarified(
                        execResult.getStringParam("clarificationQuestion", ""),
                        finalResponse, iteration, tokens(), finalAssistantIdx));
            }
            if (execTimeout) {
                String finalResponse = execResult.getStringParam("finalResponse", "");
                history.add(new TLConversationHistory(TLConversationHistory.Role.user,
                        "（工具执行超时：" + finalResponse + "，请根据已有信息继续或重试。）"));
                return finish(new TurnOutcome.Timeout(finalResponse, iteration, tokens()));
            }
            if (execFinal && toolCalls.size() == 1) {
                @SuppressWarnings("unchecked")
                List<TLToolExecutor.ToolResult> execResultsF =
                        (List<TLToolExecutor.ToolResult>) execResult.getListParam("results", null);
                appendToolResults(tasks, execResultsF);
                String finalResponse = execResult.getStringParam("finalResponse", "");
                history.add(new TLConversationHistory(TLConversationHistory.Role.assistant,
                        finalResponse.isEmpty() ? "（无输出）" : finalResponse));
                finalAssistantIdx = history.size() - 1;
                return finish(new TurnOutcome.DirectAnswer(finalResponse, iteration, tokens(),
                        finalAssistantIdx));
            }

            @SuppressWarnings("unchecked")
            List<TLToolExecutor.ToolResult> execResults =
                    (List<TLToolExecutor.ToolResult>) execResult.getListParam("results", null);
            appendToolResults(tasks, execResults);
            deps.committer.checkpoint(record());
        }

        return finish(new TurnOutcome.Truncated(
                "（已达最大迭代次数 " + maxIterations + "，可能未完成）", iteration, tokens()));
    }

    // ==================== 单步 ====================

    private TLMsg buildLlmMsg(List<TLConversationHistory> sendList) {
        TLMsg llmMsg = createMsg().setAction(LLM_COMPLETION)
                .setParam(AI_P_MESSAGEHISTORY, sendList)
                .setParam(AI_P_MODEL, seed.model)
                .setParam(AI_P_TEMPERATURE, seed.temperature)
                .setParam(AI_P_MAXTOKENS, seed.maxTokens)
                .setParam(AI_P_SESSIONID, seed.sessionId)
                .setParam(AI_P_ROUNDID, seed.roundId);
        llmMsg.setSource(seed.agentName);
        if (!seed.intentCacheEnabled) llmMsg.setParam(AI_P_NOCACHE, true);
        if (toolDefs != null && !toolDefs.isEmpty()) {
            llmMsg.setParam(AI_P_FUNCTIONDEFS, new ArrayList<>(toolDefs));
        }
        if (seed.msg.containsParam(AI_P_RESPONSEFORMAT)) {
            llmMsg.setParam(AI_P_RESPONSEFORMAT, seed.msg.getParam(AI_P_RESPONSEFORMAT));
        }
        if (!"off".equals(seed.reasoningMode)) {
            llmMsg.setParam(AI_P_REASONING_MODE, seed.reasoningMode);
            llmMsg.setParam(AI_P_THINKING_BUDGET,
                    seed.msg.getIntParam(AI_P_THINKING_BUDGET, seed.thinkingBudget));
        }
        return llmMsg;
    }

    private TLMsg invokeLlm(TLMsg llmMsg) {
        if (!isStreaming()) return putMsg(deps.llmProvider, llmMsg);

        TLAiAgent owner = deps.parentAgent instanceof TLAiAgent ? (TLAiAgent) deps.parentAgent : null;
        if (owner == null) return putMsg(deps.llmProvider, llmMsg);
        StreamHandle handle = new StreamHandle(owner, seed.sessionId, seed.roundId, seed.sink);
        deps.streams.set(seed.sessionId, handle);
        llmMsg.setAction(LLM_COMPLETIONSTREAM)
                .setParam(RESULTFOR, seed.agentName)
                .setParam(RESULTACTION, "onStreamResult")
                .setParam(AI_P_ROUNDID, seed.roundId);
        llmMsg.setSource(seed.agentName);
        putMsg(deps.llmProvider, llmMsg);
        try {
            TLMsg done = handle.await();
            if (done != null && done.parseBoolean("timedOut", false)) {
                putMsg(deps.llmProvider, createMsg().setAction(LLM_CANCEL)
                        .setParam(AI_P_SESSIONID, seed.sessionId));
            }
            return done;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_CANCELLED, true)
                    .setParam(AI_P_RESPONSE, TurnOutcome.ABORTED_TEXT);
        }
    }

    private TLMsg executeTasks(List<TLToolExecutor.ToolTask> tasks) {
        String execId = seed.sessionId + "_" + System.nanoTime();
        TLMsg execMsg = createMsg().setAction(TODOOLEXECUTE)
                .setParam("tasks", tasks)
                .setSystemParam("executionId", execId)
                .setSystemParam(AI_P_SESSIONID, seed.sessionId)
                .setSystemParam("rootSessionId", seed.rootSessionId)
                .setSystemParam("userId", seed.userId)
                .setSystemParam(AI_P_ROUNDID, seed.roundId)
                .setSystemParam(AI_P_AGENTNAME, seed.agentName);
        execMsg.setSource(seed.agentName);
        return putMsg(deps.toolExecutor, execMsg);
    }

    private void appendToolResults(List<TLToolExecutor.ToolTask> tasks,
                                   List<TLToolExecutor.ToolResult> results) {
        deps.appender.appendAll(history, tasks, results, seed.sessionId, seed.userId,
                seed.sink == null ? null : seed.sink::onToolEvent);
    }

    /**
     * 本轮（msgStartIdx 之后）最新 todo 工具结果里的未完成项；没有/全部完成返回 null。
     * 只认本轮，避免上一轮遗留清单干扰当前话题。
     */
    private String unfinishedTodoReminder() {
        if (!seed.todoGateEnabled || seed.todoGateMaxRetries <= 0) return null;
        String items = TLTodoReminder.latestUnfinishedItems(history, msgStartIdx);
        if (items == null) return null;
        return "【任务清单未完成】\n" + items
                + "请继续执行，不要现在收尾。若确实无法完成，请把对应项标为 failed 并在最终答复里说明原因。";
    }

    private long[] lastUsage = new long[3];
    private long[] lastCache = new long[3];

    private void accumulateTokens(TLMsg resp) {
        long p = resp.getIntParam(AI_P_PROMPTTOKENS, 0);
        long c = resp.getIntParam(AI_P_COMPLETIONTOKENS, 0);
        long t = resp.getIntParam(AI_P_TOTALTOKENS, 0);
        turnTokens[0] += p; turnTokens[1] += c; turnTokens[2] += t;
        long cc = resp.getLongParam(AI_P_CACHECREATIONTOKENS, 0L);
        long ch = resp.getLongParam(AI_P_CACHEHITTOKENS, 0L);
        long cm = resp.getLongParam(AI_P_CACHEMISSTOKENS, 0L);
        cacheTurn[0] += cc; cacheTurn[1] += ch; cacheTurn[2] += cm;
        lastUsage = new long[]{p, c, t};
        lastCache = new long[]{cc, ch, cm};
    }

    /**
     * 捕获 LLM 推理文本。
     *
     * @return 需要回传给 API 的 reasoning_content（挂到 assistant 消息上）；
     *         prompt 模式返回 null（推理在正文里，不是原生 reasoning_content）。
     *
     * <p>⚠ off 模式也必须捕获并回传：off 只是"不主动开关 thinking"，DeepSeek 推理模型
     * 仍会思考并返回 reasoning_content；下一次请求不回传它 → HTTP 400
     * "The reasoning_content in the thinking mode must be passed back"（2026-11-14 事故）。
     * off 模式不注入独立 reasoning 条目（发送窗口/展示保持原样），只在 assistant 消息上带回。
     */
    private String extractReasoning(TLMsg resp) {
        String reasoningText = null;
        if (resp.containsParam(AI_P_REASONING)) {
            reasoningText = resp.getStringParam(AI_P_REASONING, "");
        }
        boolean promptMode = "prompt".equals(seed.reasoningMode);
        if (promptMode && reasoningText == null) {
            String respText = resp.getStringParam(AI_P_RESPONSE, "");
            if (respText != null && respText.startsWith("💭")) {
                int endIdx = respText.indexOf('\n');
                if (endIdx > 0) {
                    reasoningText = respText.substring(0, endIdx).trim();
                    resp.setParam(AI_P_RESPONSE, respText.substring(endIdx).trim());
                }
            }
        }
        if (reasoningText != null && !reasoningText.isEmpty()
                && !"off".equals(seed.reasoningMode)) {
            history.add(TLConversationHistory.createReasoning(reasoningText));
            allReasoning.append(reasoningText).append("\n");
            putLog("Reasoning captured: "
                            + reasoningText.substring(0, Math.min(80, reasoningText.length())),
                    LogLevel.DEBUG);
        }
        return promptMode ? null : reasoningText;
    }

    /**
     * 定位被审批拦截的那条 tool call。定位不到时不猜：猜 first 会让“批准 A”在恢复时
     * 重放 B（且恢复路径带免复批标志）。只有批次仅一条工具时，缺 id 才允许兜底。
     * 包级可见，供 ChatTurnPendingPickSelfTest 直接验证。
     */
    TLToolCall pickPendingToolCall(List<TLToolCall> toolCalls, TLMsg execResult) {
        if (toolCalls == null || toolCalls.isEmpty()) return null;
        String pendingId = execResult != null
                ? execResult.getStringParam("pendingToolCallId", "") : "";
        if (!pendingId.isEmpty()) {
            for (TLToolCall tc : toolCalls) {
                if (tc != null && pendingId.equals(tc.getId())) return tc;
            }
            putLog("pendingToolCallId not found in batch; refusing to guess: " + pendingId,
                    LogLevel.WARN);
            return null;
        }
        if (toolCalls.size() == 1) {
            putLog("pendingToolCallId missing; single-tool batch, using the only tool",
                    LogLevel.WARN);
            return toolCalls.get(0);
        }
        putLog("pendingToolCallId missing; multi-tool batch cannot locate pending tool",
                LogLevel.WARN);
        return null;
    }

    // ==================== 终态 ====================

    private TLMsg finish(TurnOutcome outcome) {
        phase = Phase.COMMITTED;
        CommitResult cr = deps.committer.commit(record(), outcome);
        sendFinalFrame(outcome, cr.finalText);
        phase = Phase.CLOSED;
        return cr.returnMsg;
    }

    private void sendFinalFrame(TurnOutcome outcome, String finalText) {
        if (seed.sink == null || streamFinished) return;
        if (outcome instanceof TurnOutcome.Failed f) {
            streamFinished = true;
            seed.sink.onError(f.error());
            return;
        }
        if (outcome instanceof TurnOutcome.EarlyReturn) return;
        streamFinished = true;
        try {
            seed.sink.onFinalText(finalText, finalAnswerStreamed);
        } finally {
            seed.sink.onDone();
        }
    }

    private TurnRecord record() {
        return new TurnRecord(seed.identity(), seed.plan, seed.userMessage, history, msgStartIdx,
                seed.resumeRoundMessages, seed.noHistory, seed.model, seed.temperature,
                seed.maxTokens, seed.reasoningMode, seed.reasoningVisible,
                iteration, tokens(), finalAssistantIdx, reasoningOut());
    }

    private TurnOutcome.Tokens tokens() {
        return new TurnOutcome.Tokens((int) turnTokens[0], (int) turnTokens[1],
                (int) turnTokens[2], (int) cacheTurn[0], (int) cacheTurn[1],
                (int) cacheTurn[2]);
    }

    private String reasoningOut() {
        if ("off".equals(seed.reasoningMode) || !seed.reasoningVisible
                || allReasoning.length() == 0) return null;
        return allReasoning.toString().trim();
    }

    private boolean isCancelled() {
        return seed.cancel != null && seed.cancel.isCancelled();
    }

    private boolean isStreaming() {
        return seed.streaming() && (iteration == 1 || STREAM_TOOL_ROUNDS);
    }
}
