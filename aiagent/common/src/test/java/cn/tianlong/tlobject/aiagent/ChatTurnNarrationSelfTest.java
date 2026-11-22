package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ChatTurn 过程旁白自测（runnable main，无 JUnit）。
 *
 * <p>守：工具轮先发旁白再执行工具；模型有正文用正文、无正文按工具名合成；
 * 终答轮不发旁白；旁白先于工具执行的顺序；NullStreamSink 不炸。
 * 用假 Provider/ToolManager/ToolExecutor 跑完整循环，不依赖真实技能。
 */
public class ChatTurnNarrationSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        runSynthesizedCase();
        runModelContentCase();
        runFinalOnlyCase();
        runNullSinkCase();
        runDirectAnswerTodoGateCase();
        runDirectAnswerClarificationCase();

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    /** 模型没写正文：按工具名 + query 合成，首轮"已流式"标记为 true，且旁白先于工具执行。 */
    private static void runSynthesizedCase() {
        boolean[] toolExecuted = {false};
        RecordingSink sink = new RecordingSink(toolExecuted);

        Map<String, Object> targs = new LinkedHashMap<>();
        targs.put("query", "肺癌 中国 酒消耗");
        TLToolCall tc = new TLToolCall("tc1", "search", targs);

        QueueProvider provider = new QueueProvider(
                toolCallsResponse(tc),
                finalResponse("完成"));
        FakeCommitter committer = new FakeCommitter();

        new ChatTurn("turn:test:nar1", seed(sink),
                deps(provider, committer, toolExecuted)).run();

        check("合成：收到一次旁白", sink.narrations.size() == 1);
        check("合成：文案含工具动作与 query", sink.narrations.size() == 1
                && sink.narrations.get(0).contains("正在搜索")
                && sink.narrations.get(0).contains("肺癌 中国 酒消耗"));
        check("合成：首轮标记已流式", sink.streamed.size() == 1 && sink.streamed.get(0));
        check("合成：轮次=1", sink.rounds.size() == 1 && sink.rounds.get(0) == 1);
        check("顺序：旁白先于工具执行", sink.toolExecutedAtNarration.size() == 1
                && !sink.toolExecutedAtNarration.get(0));
        check("合成：终态 Completed", committer.lastOutcome instanceof TurnOutcome.Completed);
        check("合成：终答正确", committer.lastOutcome instanceof TurnOutcome.Completed
                && "完成".equals(((TurnOutcome.Completed) committer.lastOutcome).response()));
        check("合成：toolCalls 消息落历史", committer.lastRecord != null
                && committer.lastRecord.history.stream()
                        .anyMatch(h -> h.isAssistantWithToolCalls()
                                && h.getToolCalls().get(0).getFunctionName().equals("search")));
    }

    /** 模型写了正文：旁白用模型文本，不再合成。 */
    private static void runModelContentCase() {
        boolean[] toolExecuted = {false};
        RecordingSink sink = new RecordingSink(toolExecuted);

        TLToolCall tc = new TLToolCall("tc2", "file_operation",
                new LinkedHashMap<>(Map.of("operation", "read", "path", "a.md")));
        TLMsg first = toolCallsResponse(tc);
        first.setParam(AI_P_RESPONSE, "我先读一下文件。");
        QueueProvider provider = new QueueProvider(first, finalResponse("读完了"));
        FakeCommitter committer = new FakeCommitter();

        new ChatTurn("turn:test:nar2", seed(sink),
                deps(provider, committer, toolExecuted)).run();

        check("模型文本：旁白用原文", sink.narrations.size() == 1
                && "我先读一下文件。".equals(sink.narrations.get(0)));
    }

    /** 只有终答、没有工具：不发旁白。 */
    private static void runFinalOnlyCase() {
        boolean[] toolExecuted = {false};
        RecordingSink sink = new RecordingSink(toolExecuted);
        QueueProvider provider = new QueueProvider(finalResponse("直接回答"));
        FakeCommitter committer = new FakeCommitter();

        new ChatTurn("turn:test:nar3", seed(sink),
                deps(provider, committer, toolExecuted)).run();

        check("终答轮：不发旁白", sink.narrations.isEmpty());
        check("终答轮：Completed", committer.lastOutcome instanceof TurnOutcome.Completed);
    }

    /** 非流式入口（NullStreamSink）：不炸，工具照常执行。 */
    private static void runNullSinkCase() {
        boolean[] toolExecuted = {false};
        TLToolCall tc = new TLToolCall("tc4", "todo", new LinkedHashMap<>());
        QueueProvider provider = new QueueProvider(
                toolCallsResponse(tc), finalResponse("好的"));
        FakeCommitter committer = new FakeCommitter();

        new ChatTurn("turn:test:nar4", seed(StreamSink.NullStreamSink.INSTANCE),
                deps(provider, committer, toolExecuted)).run();

        check("NullStreamSink：工具已执行", toolExecuted[0]);
        check("NullStreamSink：Completed", committer.lastOutcome instanceof TurnOutcome.Completed);
    }

    /** 直出工具带未完成清单：不能短路收尾，必须继续下一轮（2026-11-22 Word 事故）。 */
    private static void runDirectAnswerTodoGateCase() {
        boolean[] toolExecuted = {false};
        RecordingSink sink = new RecordingSink(toolExecuted);
        QueueProvider provider = new QueueProvider(
                toolCallsResponse(new TLToolCall("tc_direct", "poemWorkflow", new LinkedHashMap<>())),
                finalResponse("继续执行并生成 docx"));
        FakeCommitter committer = new FakeCommitter();
        List<TLConversationHistory> hist = todoHistory();

        new ChatTurn("turn:test:directgate", seed(sink, hist, 1),
                deps(provider, committer, toolExecuted,
                        new FakeDirectExecutor(toolExecuted, "诗文本"))).run();

        check("直出门禁：没有短路成 DirectAnswer",
                committer.lastOutcome instanceof TurnOutcome.Completed);
        check("直出门禁：继续了第二轮 LLM", provider.idx == 2);
        check("直出门禁：终答来自第二轮",
                committer.lastOutcome != null
                        && committer.lastOutcome.response().contains("继续执行并生成 docx"));
        check("直出门禁：未把直出文本重复写成 assistant 消息",
                committer.lastRecord != null && committer.lastRecord.history.stream()
                        .noneMatch(h -> h.getRole() == TLConversationHistory.Role.assistant
                                && "诗文本".equals(h.getContent())));
    }

    /** 直出内容是澄清问句：允许收尾，不硬续跑。 */
    private static void runDirectAnswerClarificationCase() {
        boolean[] toolExecuted = {false};
        RecordingSink sink = new RecordingSink(toolExecuted);
        QueueProvider provider = new QueueProvider(
                toolCallsResponse(new TLToolCall("tc_direct2", "fileAgent", new LinkedHashMap<>())));
        FakeCommitter committer = new FakeCommitter();
        List<TLConversationHistory> hist = todoHistory();

        new ChatTurn("turn:test:directclarify", seed(sink, hist, 1),
                deps(provider, committer, toolExecuted,
                        new FakeDirectExecutor(toolExecuted, "要生成 docx 吗？"))).run();

        check("直出澄清：按 DirectAnswer 正常收尾",
                committer.lastOutcome instanceof TurnOutcome.DirectAnswer);
        check("直出澄清：没有多余续跑", provider.idx == 1);
    }

    private static List<TLConversationHistory> todoHistory() {
        List<TLConversationHistory> hist = new ArrayList<>();
        hist.add(new TLConversationHistory(TLConversationHistory.Role.user,
                "来两首诗并输出到 word 文件"));
        List<TLToolCall> calls = new ArrayList<>();
        calls.add(new TLToolCall("call_todo", "todo", new LinkedHashMap<>()));
        hist.add(new TLConversationHistory(TLConversationHistory.Role.assistant, calls));
        hist.add(new TLConversationHistory("call_todo", "todo",
                "任务清单（2 项：1 进行中 / 1 待执行）：\n- [~] 写诗\n- [ ] 生成 docx\n"));
        return hist;
    }

    private static TurnSeed seed(StreamSink sink) {
        return seed(sink, new ArrayList<>(), 2);
    }

    private static TurnSeed seed(StreamSink sink, List<TLConversationHistory> history,
                                 int todoGateMaxRetries) {
        TLMsg req = new TLMsg().setParam(AI_P_USERMESSAGE, "hi");
        return new TurnSeed(req, "s1", "s1", "r1", "u1", "agent", "hi",
                TurnPlan.NORMAL, history, 0, null, null,
                new ArrayList<>(), "test-model", 0.0, 0,
                "off", false, false, false, 10, 0, true, todoGateMaxRetries,
                sink, () -> false, null);
    }

    private static TurnDeps deps(QueueProvider provider, FakeCommitter committer,
                                 boolean[] toolExecuted) {
        return deps(provider, committer, toolExecuted, new FakeToolExecutor(toolExecuted));
    }

    private static TurnDeps deps(QueueProvider provider, FakeCommitter committer,
                                 boolean[] toolExecuted, TLBaseModule executor) {
        AttachmentPipeline attachments = new AttachmentPipeline(null);
        return new TurnDeps(null, null, provider, new FakeToolManager(), executor,
                (history, memory, roundStartIdx) -> new ArrayList<>(history),
                committer, new FakeObserver(), attachments,
                new ToolResultAppender(attachments), null);
    }

    private static TLMsg toolCallsResponse(TLToolCall tc) {
        List<TLToolCall> calls = new ArrayList<>();
        calls.add(tc);
        return new TLMsg().setParam(RESULT, true)
                .setParam("hasToolCalls", true)
                .setParam(AI_P_TOOLCALLS, calls);
    }

    private static TLMsg finalResponse(String text) {
        return new TLMsg().setParam(RESULT, true).setParam(AI_P_RESPONSE, text);
    }

    /** 依次返回预置响应；耗尽返回失败（测试应当在此之前结束）。 */
    static class QueueProvider extends TLBaseModule {
        private final List<TLMsg> responses;
        private int idx;

        QueueProvider(TLMsg... responses) {
            super("fakeNarrationProvider");
            this.responses = List.of(responses);
        }

        @Override protected TLBaseModule init() { return this; }

        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
            if (idx >= responses.size())
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_RESPONSE, "（测试 Provider 响应已耗尽）");
            return responses.get(idx++);
        }
    }

    static class FakeToolManager extends TLBaseModule {
        FakeToolManager() { super("fakeNarrationToolManager"); }
        @Override protected TLBaseModule init() { return this; }

        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
            @SuppressWarnings("unchecked")
            List<TLToolCall> calls = (List<TLToolCall>) msg.getListParam(AI_P_TOOLCALLS, List.of());
            List<TLToolExecutor.ToolTask> tasks = new ArrayList<>();
            for (TLToolCall c : calls) {
                TLToolExecutor.ToolTask t = new TLToolExecutor.ToolTask();
                t.toolCallId = c.getId();
                t.moduleName = "fakeModule";
                t.functionName = c.getFunctionName();
                t.args = c.getArguments();
                tasks.add(t);
            }
            return createMsg().setParam("tasks", tasks);
        }
    }

    static class FakeToolExecutor extends TLBaseModule {
        private final boolean[] executed;
        FakeToolExecutor(boolean[] executed) { super("fakeNarrationToolExecutor"); this.executed = executed; }
        @Override protected TLBaseModule init() { return this; }

        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
            executed[0] = true;
            @SuppressWarnings("unchecked")
            List<TLToolExecutor.ToolTask> tasks =
                    (List<TLToolExecutor.ToolTask>) msg.getListParam("tasks", List.of());
            List<TLToolExecutor.ToolResult> results = new ArrayList<>();
            for (TLToolExecutor.ToolTask t : tasks)
                results.add(new TLToolExecutor.ToolResult(t.toolCallId, "工具输出"));
            return createMsg().setParam(RESULT, true).setParam("results", results);
        }
    }

    /** 模拟 directOutput 工具/子工作流：直接返回 finalAnswer + finalResponse。 */
    static class FakeDirectExecutor extends TLBaseModule {
        private final boolean[] executed;
        private final String text;
        FakeDirectExecutor(boolean[] executed, String text) {
            super("fakeDirectExecutor");
            this.executed = executed;
            this.text = text;
        }
        @Override protected TLBaseModule init() { return this; }

        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
            executed[0] = true;
            @SuppressWarnings("unchecked")
            List<TLToolExecutor.ToolTask> tasks =
                    (List<TLToolExecutor.ToolTask>) msg.getListParam("tasks", List.of());
            List<TLToolExecutor.ToolResult> results = new ArrayList<>();
            for (TLToolExecutor.ToolTask t : tasks)
                results.add(new TLToolExecutor.ToolResult(t.toolCallId, text));
            return createMsg().setParam(RESULT, true)
                    .setParam(AI_P_FINALANSWER, true)
                    .setParam("finalResponse", text)
                    .setParam("results", results);
        }
    }

    static class RecordingSink implements StreamSink {
        final List<String> narrations = new ArrayList<>();
        final List<Boolean> streamed = new ArrayList<>();
        final List<Integer> rounds = new ArrayList<>();
        final List<Boolean> toolExecutedAtNarration = new ArrayList<>();
        final boolean[] toolExecuted;
        String finalText;

        RecordingSink(boolean[] toolExecuted) { this.toolExecuted = toolExecuted; }

        @Override public void onChunk(String text) { }
        @Override public void onToolEvent(String toolName, String output) { }
        @Override public void onNarration(String text, boolean alreadyStreamed, int round) {
            narrations.add(text);
            streamed.add(alreadyStreamed);
            rounds.add(round);
            toolExecutedAtNarration.add(toolExecuted[0]);
        }
        @Override public void onFinalText(String answer, boolean alreadyStreamed) { finalText = answer; }
        @Override public void onDone() { }
        @Override public void onError(String message) { }
    }

    static class FakeCommitter implements TurnCommitter {
        TurnRecord lastRecord;
        TurnOutcome lastOutcome;

        @Override public void checkpoint(TurnRecord record) { }
        @Override public void checkpointPending(TurnRecord record, PendingInfo pending) { }
        @Override public CommitResult commit(TurnRecord record, TurnOutcome outcome) {
            lastRecord = record;
            lastOutcome = outcome;
            return new CommitResult(new TLMsg().setParam(RESULT, true), outcome.response());
        }
    }

    static class FakeObserver implements TurnObserver {
        @Override public void traceStage(String sessionId, String rootSid, String roundId,
                                         String userId, String stage, String detail,
                                         long durationMs, String payload) { }
        @Override public String formatHistory(List<TLConversationHistory> history) { return ""; }
        @Override public String formatResponse(String content, List<TLToolCall> toolCalls,
                                               String reasoning) { return ""; }
        @Override public String withTruncationNotice(String text, String finishReason) {
            return text == null ? "" : text;
        }
        @Override public String emptyHistoryPlaceholder(String finishReason) { return "（无输出）"; }
        @Override public void reportTokenUsage(String sessionId, String rootSid, String userId,
                                               long[] usage, long[] cache) { }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name);
        }
    }
}
