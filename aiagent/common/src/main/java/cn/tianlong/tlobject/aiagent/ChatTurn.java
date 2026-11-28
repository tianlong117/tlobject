package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** 本轮是否调用过文件类工具（fileAgent/word 等）：文件交付门禁用 */
    private boolean fileToolCalled;
    /** 文件交付门禁：用户要求文件但直出/终答未生成文件时的续跑次数 */
    private int fileDeliverableGateRetries;
    /**
     * 文件交付门禁上限：directOutput 工具（如 poemWorkflow）会让本轮立即收尾，
     * 系统提示里"返回后继续委托 fileAgent"在机制上不可能执行；用户明确要文件时
     * 最多强制续跑两次，仍无文件工具就如实标注未生成（2026-11-28 Word 事故）。
     */
    private static final int FILE_DELIVERABLE_GATE_MAX_RETRIES = 2;
    /** 视为"能产出文件"的工具名（小写；另含任何名称里带 file 的工具） */
    private static final Set<String> FILE_DELIVERABLE_TOOL_NAMES = new HashSet<>(Arrays.asList(
            "fileagent", "word", "file_operation", "codeagent", "codingagent"));
    /** 本轮开始时间/工具错误计数：供 turnResult 输出契约（耗时、toolErrorCount） */
    private final long turnStartTs = System.currentTimeMillis();
    private int toolErrorCount;
    private int toolCallCount;
    /** 收敛门禁：相同 tool+args+result 连续重复 → 先提醒、再 no_progress 收尾 */
    private boolean convergenceGateEnabled = true;
    private int convergenceRepeatThreshold = 3;
    private int convergenceMaxRepeats = 5;
    private String lastBatchSignature;
    private int convergenceRepeats;
    private boolean convergenceReminded;
    /** schema 失败预算：同一工具连续参数不合法 → 提醒 / no_progress */
    private boolean schemaFailureGateEnabled = true;
    private int schemaFailureWarnThreshold = 2;
    private int schemaFailureMax = 3;
    private final Map<String, Integer> schemaFailureCounts = new HashMap<>();
    private final Set<String> schemaWarnedTools = new HashSet<>();
    /** 本轮瞬态 system 提醒（todo 收尾门禁）；只进发送视图，不落 history/会话存储 */
    private final List<TLConversationHistory> transientSystems = new ArrayList<>();

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

    /** 由 Agent 在 run 前注入收敛门禁参数（测试可用默认值）。 */
    void setConvergenceConfig(boolean enabled, int threshold, int maxRepeats) {
        convergenceGateEnabled = enabled;
        convergenceRepeatThreshold = Math.max(2, threshold);
        convergenceMaxRepeats = Math.max(0, maxRepeats);
        if (convergenceMaxRepeats > 0 && convergenceMaxRepeats < convergenceRepeatThreshold)
            convergenceMaxRepeats = convergenceRepeatThreshold;
    }

    /** 工具参数连续校验失败预算（0 = 只提醒不硬停）。 */
    void setSchemaFailureConfig(boolean enabled, int warnThreshold, int maxFailures) {
        schemaFailureGateEnabled = enabled;
        schemaFailureWarnThreshold = Math.max(1, warnThreshold);
        schemaFailureMax = Math.max(0, maxFailures);
        if (schemaFailureMax > 0 && schemaFailureMax < schemaFailureWarnThreshold)
            schemaFailureMax = schemaFailureWarnThreshold;
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
                    deps.viewBuilder.build(history, memoryContext, msgStartIdx, transientSystems);
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
                // 澄清问句例外：模型需要用户拍板（如"用哪版"）时是合法收尾，硬续跑只会
                // 反复问同一句并追加"清单未完成"（2026-11-14 肺癌转 Word 轮）。
                String todoReminder = unfinishedTodoReminder();
                boolean clarification = looksLikeClarification(finalResponse);
                if (todoReminder != null && !finalAnswerStreamed && !clarification
                        && todoGateRetries < seed.todoGateMaxRetries) {
                    todoGateRetries++;
                    // 只进本轮发送视图：落 history 会跨轮累积，旧"清单未完成"会把后续提问带偏
                    transientSystems.clear();
                    transientSystems.add(new TLConversationHistory(
                            TLConversationHistory.Role.system, todoReminder));
                    putLog("[turn:" + seed.roundId + "] todo gate 触发：清单未完成，继续执行 ("
                            + todoGateRetries + "/" + seed.todoGateMaxRetries + ")", LogLevel.INFO);
                    continue;
                }
                // 文件交付门禁：用户明确要文件、但本轮还没调用过文件工具 → 草稿入史后续跑。
                // 典型：directOutput 工具刚结束一轮后模型直接写"已生成"却没有文件工具调用。
                // 清单未完成时由 todo 门禁优先（清单里通常已含文件项），避免两套门禁叠加续跑。
                String fileReminder = fileDeliverableReminder();
                boolean fileGateApplies = fileReminder != null && todoReminder == null
                        && !finalAnswerStreamed && !clarification;
                if (fileGateApplies && fileDeliverableGateRetries < FILE_DELIVERABLE_GATE_MAX_RETRIES) {
                    fileDeliverableGateRetries++;
                    // 草稿必须进 history：续跑后模型要在它的基础上生成文件，不能丢内容
                    history.add(new TLConversationHistory(
                            TLConversationHistory.Role.assistant, finalResponse));
                    transientSystems.clear();
                    transientSystems.add(new TLConversationHistory(
                            TLConversationHistory.Role.system, fileReminder));
                    putLog("[turn:" + seed.roundId + "] file gate 触发：要求文件但未调用文件工具，继续执行 ("
                            + fileDeliverableGateRetries + "/" + FILE_DELIVERABLE_GATE_MAX_RETRIES + ")", LogLevel.INFO);
                    continue;
                }
                if (todoReminder != null && !finalAnswerStreamed && !clarification) {
                    // 重试用尽：不伪装完成，终答明确标注仍有未完成项
                    finalResponse = finalResponse + "\n\n（注意：任务清单仍有未完成项，详见任务清单。）";
                    putLog("[turn:" + seed.roundId + "] todo gate 重试用尽，收尾但标注未完成", LogLevel.WARN);
                }
                if (fileGateApplies) {
                    // 重试用尽：不把纯文本伪装成已交付文件
                    finalResponse = finalResponse + "\n\n（注意：用户要求输出文件，但本轮未生成文件。）";
                    putLog("[turn:" + seed.roundId + "] file gate 重试用尽，收尾但标注未生成文件", LogLevel.WARN);
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

            // 过程旁白要在工具执行前发出：首轮流式的正文已流进 UI 气泡，旁白事件让前端
            // 把它"封印"为过程气泡，终答另起气泡，不再混在一条里。
            emitNarration(llmResponse, toolCalls);

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
            // 文件交付门禁：记录本轮是否已调用文件类工具（跨迭代累计）
            for (TLToolExecutor.ToolTask t : tasks) {
                if (t != null && isFileDeliverableTool(t.functionName)) {
                    fileToolCalled = true;
                    break;
                }
            }

            TLMsg execResult = executeTasks(tasks);
            if (execResult != null) {
                toolErrorCount += countToolErrors(execResult);
                toolCallCount += countToolCalls(execResult);
            }
            if (execResult == null) {
                putLog("toolExecutor returned null, no tool executed", LogLevel.ERROR);
                history.remove(aMsgIdx);
                String fail = "（工具执行器无响应，本轮未完成）";
                history.add(new TLConversationHistory(TLConversationHistory.Role.assistant, fail));
                finalAssistantIdx = history.size() - 1;
                return finish(new TurnOutcome.Completed(fail, iteration, tokens(),
                        reasoningOut(), finalAssistantIdx));
            }

            // 参数失败预算：同一工具连续 schema 不合法（模型换非法参数也能绕开收敛签名时兜底）
            TurnOutcome schemaStop = checkSchemaFailures(execResult, tasks);
            if (schemaStop != null) {
                appendToolResults(tasks, resultsOf(execResult));
                return finish(schemaStop);
            }

            // 收敛门禁：同一批 tool+args+result 连续重复，说明模型在原地打转。
            // 到阈值先注入提醒；到上限直接 no_progress 收尾。maxRepeats=0 只提醒不硬停。
            if (convergenceGateEnabled && tasks != null && !tasks.isEmpty()) {
                String batchSig = toolBatchSignature(tasks, execResult);
                if (batchSig.equals(lastBatchSignature)) {
                    convergenceRepeats++;
                } else {
                    lastBatchSignature = batchSig;
                    convergenceRepeats = 1;
                    convergenceReminded = false;
                    // 行为已变化：撤掉上一条"无进展"提醒，避免旧提示继续误导模型
                    transientSystems.removeIf(h -> h != null && h.getContent() != null
                            && h.getContent().startsWith("【工具循环无进展】"));
                }
                if (convergenceRepeats >= convergenceRepeatThreshold && !convergenceReminded) {
                    convergenceReminded = true;
                    transientSystems.add(new TLConversationHistory(
                            TLConversationHistory.Role.system,
                            "【工具循环无进展】连续 " + convergenceRepeats
                                    + " 次用相同参数调用相同工具且结果无变化。"
                                    + "请停止重复：换参数/换工具，或基于已有信息作答。"));
                    putLog("[turn:" + seed.roundId + "] convergence reminder at "
                            + convergenceRepeats, LogLevel.INFO);
                }
                if (convergenceMaxRepeats > 0 && convergenceRepeats >= convergenceMaxRepeats) {
                    String msg = "（检测到工具循环无进展：连续 " + convergenceRepeats
                            + " 次相同参数调用相同工具且结果无变化，已停止。"
                            + "请换一种方法或补充信息后重试。）";
                    putLog("[turn:" + seed.roundId + "] convergence stop at "
                            + convergenceRepeats, LogLevel.WARN);
                    appendToolResults(tasks, resultsOf(execResult));
                    return finish(new TurnOutcome.NoProgress(msg, iteration, tokens()));
                }
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
                String finalResponse = execResult.getStringParam("finalResponse", "");
                // 清单门禁：directOutput 工具/子工作流（如 poemWorkflow）不能在清单未完成时
                // 把整轮短路成直出——否则 master 计划里的后续步骤（如生成 docx）永远不会执行。
                // 2026-11-22 webchat_tianlong_1795259265324：写诗直出后停在第 1 项，Word 未生成。
                String todoReminder = unfinishedTodoReminder();
                boolean clarification = looksLikeClarification(finalResponse);
                if (todoReminder != null && !clarification
                        && todoGateRetries < seed.todoGateMaxRetries) {
                    todoGateRetries++;
                    transientSystems.clear();
                    transientSystems.add(new TLConversationHistory(
                            TLConversationHistory.Role.system, todoReminder));
                    appendToolResults(tasks, execResultsF);
                    deps.committer.checkpoint(record());
                    putLog("[turn:" + seed.roundId + "] todo gate 拦截直出：清单未完成，继续执行 ("
                            + todoGateRetries + "/" + seed.todoGateMaxRetries + ")", LogLevel.INFO);
                    continue;
                }
                // 文件交付门禁：即使用户没建清单，直出也不能吞掉"输出到 Word/文件"的第二步。
                // directOutput 让本轮立即结束，模型没机会再调 fileAgent；这里回注直出结果并续跑。
                // 清单未完成时由 todo 门禁优先（清单里通常已含文件项），避免两套门禁叠加续跑。
                // 2026-11-28 webchat_tianlong_1795439788398：poemWorkflow 直出诗，Word 未生成。
                String fileReminder = fileDeliverableReminder();
                boolean fileGateApplies = fileReminder != null && todoReminder == null && !clarification;
                if (fileGateApplies && fileDeliverableGateRetries < FILE_DELIVERABLE_GATE_MAX_RETRIES) {
                    fileDeliverableGateRetries++;
                    transientSystems.clear();
                    transientSystems.add(new TLConversationHistory(
                            TLConversationHistory.Role.system, fileReminder));
                    appendToolResults(tasks, execResultsF);
                    deps.committer.checkpoint(record());
                    putLog("[turn:" + seed.roundId + "] file gate 拦截直出：要求文件但未调用文件工具，继续执行 ("
                            + fileDeliverableGateRetries + "/" + FILE_DELIVERABLE_GATE_MAX_RETRIES + ")", LogLevel.INFO);
                    continue;
                }
                if (todoReminder != null && !clarification) {
                    finalResponse = finalResponse
                            + "\n\n（注意：任务清单仍有未完成项，详见任务清单。）";
                    putLog("[turn:" + seed.roundId + "] todo gate 重试用尽，直出但标注未完成",
                            LogLevel.WARN);
                }
                if (fileGateApplies) {
                    // 重试用尽：不能把纯文本伪装成已交付文件
                    finalResponse = finalResponse
                            + "\n\n（注意：用户要求输出文件，但本轮未生成文件。）";
                    putLog("[turn:" + seed.roundId + "] file gate 重试用尽，直出但标注未生成文件",
                            LogLevel.WARN);
                }
                appendToolResults(tasks, execResultsF);
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
        // 模型更新过清单后，上一轮的"清单未完成"瞬态提醒立即失效，避免它继续误导本轮
        if (hasTodoTask(tasks)) transientSystems.clear();
        deps.appender.appendAll(history, tasks, results, seed.sessionId, seed.userId,
                seed.sink == null ? null : seed.sink::onToolEvent);
    }

    private static boolean hasTodoTask(List<TLToolExecutor.ToolTask> tasks) {
        if (tasks == null) return false;
        for (TLToolExecutor.ToolTask t : tasks) {
            if (t != null && t.functionName != null
                    && t.functionName.toLowerCase(java.util.Locale.ROOT).contains("todo"))
                return true;
        }
        return false;
    }

    /**
     * 本轮（msgStartIdx 之后）最新 todo 工具结果里的未完成项；没有/全部完成返回 null。
     * 只认本轮，避免上一轮遗留清单干扰当前话题。
     */
    /**
     * 终答是否在向用户请求确认（而不是任务完成）。用于清单门禁豁免：
     * 模型需要用户拍板时是合法收尾，硬续跑只会重复问句并追加"清单未完成"。
     * 只认强信号，避免把"要不要删你说"这类完成后的顺带建议误判。
     */
    static boolean looksLikeClarification(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.isEmpty()) return false;
        if (t.endsWith("？") || t.endsWith("?")) return true;
        String[] cues = {"请你回一句", "请你确认", "请确认", "等你确认", "需要你确认",
                "用哪版", "哪一版", "请回", "你说一句", "告诉我用哪"};
        for (String c : cues) if (t.contains(c)) return true;
        return false;
    }

    private String unfinishedTodoReminder() {
        if (!seed.todoGateEnabled || seed.todoGateMaxRetries <= 0) return null;
        String items = TLTodoReminder.latestUnfinishedItems(history, msgStartIdx);
        if (items == null) return null;
        return "【任务清单未完成】\n" + items
                + "请继续执行，不要现在收尾。若确实无法完成，请把对应项标为 failed 并在最终答复里说明原因。";
    }

    /**
     * 本轮用户是否明确要求把内容交付成文件（"输出/导出/保存/写到…" + word/docx/excel/pdf/文件等）。
     * 只认强信号：普通的"读取这个 word 文件"不算；明确的否定（不要文件）也不算。
     * 用于文件交付门禁——directOutput 工具收尾时检查是否漏了文件步骤。
     */
    static boolean looksLikeFileDeliverableRequest(String text) {
        if (text == null || text.isEmpty()) return false;
        String t = text.toLowerCase();
        if (t.contains("不要文件") || t.contains("不用文件") || t.contains("无需文件")
                || t.contains("不需要文件") || t.contains("不要输出到文件")
                || t.contains("别生成文件")) return false;
        return java.util.regex.Pattern.compile(
                "(输出|导出|保存|写入|写到|放到|存到|生成|做成|转成|整理成|汇总成|总结成|另存为|下载)"
                        + "[^。！？\\n]{0,15}(word|docx|excel|xlsx|ppt|pptx|pdf|txt|md|csv|文档|文件)"
                        + "|(save|export|write|output|generate|convert)[^.!?\\n]{0,30}"
                        + "(word|docx|excel|xlsx|ppt|pptx|pdf|txt|file|document)")
                .matcher(t).find();
    }

    /** 是否文件类工具（fileAgent / word / file_operation / codeAgent / 任何名字带 file 的工具）。 */
    private static boolean isFileDeliverableTool(String functionName) {
        if (functionName == null || functionName.isEmpty()) return false;
        String n = functionName.toLowerCase();
        return FILE_DELIVERABLE_TOOL_NAMES.contains(n) || n.contains("file");
    }

    /** 文件交付门禁提醒：仅在"用户要文件 + 本轮没调过文件工具 + 本 agent 有文件工具"时有值。 */
    private String fileDeliverableReminder() {
        if (fileToolCalled) return null;
        if (!looksLikeFileDeliverableRequest(seed.userMessage)) return null;
        if (!hasFileDeliverableTool()) return null;   // 无文件工具的子 agent 不硬续跑
        return "【文件交付未完成】用户本轮明确要求输出文件（Word/Excel/文档等），"
                + "但还没有调用任何文件工具。请立即使用 fileAgent（或 word 技能）把上面的内容"
                + "写成用户要求的文件；生成成功后再终答，并在终答中给出文件路径。"
                + "不要把纯文本当作已交付文件。";
    }

    /** 本 agent 的可用工具里是否有文件类工具（避免在无文件能力的子 agent 里空转两次）。 */
    private boolean hasFileDeliverableTool() {
        for (TLFunctionDefinition def : toolDefs) {
            if (def != null && isFileDeliverableTool(def.getName())) return true;
        }
        return false;
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
                iteration, tokens(), finalAssistantIdx, reasoningOut(),
                System.currentTimeMillis() - turnStartTs, toolErrorCount, toolCallCount);
    }

    /**
     * 一轮工具批次的稳定签名：每个 tool 的 function+args+结果（终态/长度/hash）。
     * 简单重复检测用它；结果内容变化（如轮询到新内容）会改变签名，不会被误判无进展。
     */
    private static String toolBatchSignature(List<TLToolExecutor.ToolTask> tasks, TLMsg execResult) {
        Map<String, String> byCall = new HashMap<>();
        Object resObj = execResult.getListParam("results", null);
        if (resObj instanceof List) {
            for (Object o : (List<?>) resObj) {
                if (!(o instanceof TLToolExecutor.ToolResult)) continue;
                TLToolExecutor.ToolResult tr = (TLToolExecutor.ToolResult) o;
                String out = tr.output == null ? "" : tr.output;
                byCall.put(tr.toolCallId,
                        tr.finalStatus + "|" + out.length() + "|" + out.hashCode());
            }
        }
        List<String> parts = new ArrayList<>();
        for (TLToolExecutor.ToolTask t : tasks) {
            String name = t.functionName != null && !t.functionName.isEmpty()
                    ? t.functionName : (t.moduleName == null ? "" : t.moduleName);
            parts.add(name + "|" + argSignature(t.args) + "|"
                    + byCall.getOrDefault(t.toolCallId, "missing"));
        }
        Collections.sort(parts);
        return String.join(";;", parts);
    }

    /** 参数规范化签名：Map 按 key 排序，避免键顺序不同导致相同调用漏检。 */
    private static String argSignature(Object v) {
        if (v == null) return "null";
        if (v instanceof Map) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet())
                parts.add(String.valueOf(e.getKey()) + "=" + argSignature(e.getValue()));
            Collections.sort(parts);
            return "{" + String.join(",", parts) + "}";
        }
        if (v instanceof List) {
            List<String> parts = new ArrayList<>();
            for (Object o : (List<?>) v) parts.add(argSignature(o));
            return "[" + String.join(",", parts) + "]";
        }
        return String.valueOf(v);
    }

    /**
     * 工具参数连续 schema 失败预算。与收敛门禁互补：模型换不同非法参数时签名会变，
     * 但同一工具连续被 schema 拒绝仍是无效循环——2 次提醒，3 次（默认）no_progress。
     * 工具成功/业务失败会清零该工具计数。
     */
    private TurnOutcome checkSchemaFailures(TLMsg execResult,
                                            List<TLToolExecutor.ToolTask> tasks) {
        if (!schemaFailureGateEnabled || tasks == null || tasks.isEmpty()) return null;
        Map<String, String> fnByCall = new HashMap<>();
        for (TLToolExecutor.ToolTask t : tasks) {
            if (t == null || t.toolCallId == null) continue;
            String fn = t.functionName != null && !t.functionName.isEmpty()
                    ? t.functionName : t.moduleName;
            if (fn != null && !fn.isEmpty()) fnByCall.put(t.toolCallId, fn);
        }
        for (TLToolExecutor.ToolResult tr : resultsOf(execResult)) {
            if (tr == null) continue;
            String fn = fnByCall.get(tr.toolCallId);
            if (fn == null || fn.isEmpty()) continue;
            if (ERRORCODE_INVALID_ARGUMENTS.equals(tr.errorCode)) {
                int n = schemaFailureCounts.merge(fn, 1, Integer::sum);
                if (n >= schemaFailureWarnThreshold && schemaWarnedTools.add(fn)) {
                    transientSystems.add(new TLConversationHistory(
                            TLConversationHistory.Role.system,
                            "【工具参数无效】工具 " + fn + " 已连续 " + n
                                    + " 次参数校验失败。请不要再用相同/类似的错误参数重试；"
                                    + "换工具、换方法，或基于已有信息作答。"));
                    putLog("[turn:" + seed.roundId + "] schema failure reminder: "
                            + fn + " x" + n, LogLevel.INFO);
                }
                if (schemaFailureMax > 0 && n >= schemaFailureMax) {
                    String msg = "（工具 " + fn + " 的参数连续 " + n
                            + " 次校验失败，已停止本轮。请修正工具选择/参数后重试。）";
                    putLog("[turn:" + seed.roundId + "] schema failure stop: "
                            + fn + " x" + n, LogLevel.WARN);
                    return new TurnOutcome.NoProgress(msg, iteration, tokens());
                }
            } else {
                schemaFailureCounts.remove(fn);
                if (schemaWarnedTools.remove(fn)) {
                    transientSystems.removeIf(h -> h != null && h.getContent() != null
                            && h.getContent().startsWith("【工具参数无效】工具 " + fn));
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<TLToolExecutor.ToolResult> resultsOf(TLMsg execResult) {
        Object v = execResult.getListParam("results", null);
        return v instanceof List ? (List<TLToolExecutor.ToolResult>) v : new ArrayList<>();
    }

    /** 本轮实际产生的工具结果条数（成功率分母；不含 pending/rejected 等控制态）。 */
    private static int countToolCalls(TLMsg execResult) {
        Object v = execResult.getListParam("results", null);
        return v instanceof List ? ((List<?>) v).size() : 0;
    }

    /** 统计本轮工具结果里的错误/超时/中断条数（成功、待批、拒绝、跳过不计）。 */
    private static int countToolErrors(TLMsg execResult) {
        Object v = execResult.getListParam("results", null);
        if (!(v instanceof List)) return 0;
        int n = 0;
        for (Object o : (List<?>) v) {
            if (!(o instanceof TLToolExecutor.ToolResult)) continue;
            String st = ((TLToolExecutor.ToolResult) o).finalStatus;
            if (TOOL_STATUS_ERROR.equals(st) || TOOL_STATUS_TIMEOUT.equals(st)
                    || TOOL_STATUS_ABORTED.equals(st)) n++;
        }
        return n;
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

    /**
     * 工具轮过程解说：模型写了正文就用模型的，没写按工具名 + 白名单参数合成。
     * 不写额外 metadata——toolCalls 已随 assistant 消息持久化，回放按同规则重算。
     */
    private void emitNarration(TLMsg llmResponse, List<TLToolCall> toolCalls) {
        if (seed.sink == null || seed.sink.isNull()) return;
        String content = llmResponse != null ? llmResponse.getStringParam(AI_P_RESPONSE, "") : "";
        String narration = content == null ? "" : content.trim();
        if (narration.isEmpty()) narration = TLProcessNarration.forToolCalls(toolCalls);
        if (narration.isEmpty()) return;
        narration = TLProcessNarration.clip(narration, TLProcessNarration.MAX_NARRATION_CHARS);
        seed.sink.onNarration(narration, isStreaming(), iteration);
    }

    private boolean isCancelled() {
        return seed.cancel != null && seed.cancel.isCancelled();
    }

    private boolean isStreaming() {
        return seed.streaming() && (iteration == 1 || STREAM_TOOL_ROUNDS);
    }
}
