package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式 done 缺正文时的兜底自测（runnable main，无 JUnit）。
 *
 * <p>背景（2026-11-05）：ChatTurn 只从 done 的 AI_P_RESPONSE 取终答；某 Provider 的
 * done 不带正文时，历史落「（无输出）」且 streamReplace 会把 UI 已收到的正文覆盖成占位符。
 * 修复后 done 缺正文时回退到 sink 已成功转发的 chunk；done 有正文时仍以 done 为准。
 *
 * <p>失败以退出码 1 结束。
 */
public class ChatTurnStreamFallbackSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        // 1) done 无正文 → 用 sink 累积文本
        runCase("done 缺正文时回退 sink 累积", null,
                "streamed answer", "streamed answer", true);

        // 2) done 有正文 → 不覆盖
        runCase("done 有正文时以 done 为准", "full answer",
                "partial", "full answer", false);

        // 3) 非流式 length → 截断提示必须进 history（与流式同口径）
        runTruncationCase();

        // 4) off 模式也要把原生 reasoning 挂到 assistant 消息上（DeepSeek 下一请求需回传）
        runReasoningCase();

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static void runCase(String name, String providerResponse, String sinkCollected,
                                String expected, boolean expectFallback) {
        FakeProvider provider = new FakeProvider(providerResponse);
        FakeSink sink = new FakeSink(sinkCollected);
        FakeCommitter committer = new FakeCommitter();
        FakeObserver observer = new FakeObserver();

        TLMsg req = new TLMsg().setParam(AI_P_USERMESSAGE, "hi");
        TurnSeed seed = new TurnSeed(req, "s1", "s1", "r1", "u1", "agent", "hi",
                TurnPlan.NORMAL, new ArrayList<>(), 0, null, null,
                new ArrayList<>(), "test-model", 0.0, 0,
                "off", false, false, false, 10, 0, true, 2,
                sink, () -> false, null);
        TurnDeps deps = new TurnDeps(null, null, provider, null, null,
                (history, memory, roundStartIdx) -> new ArrayList<>(history),
                committer, observer, null, null, null);

        ChatTurn turn = new ChatTurn("turn:test:r1", seed, deps);
        turn.run();

        boolean completed = committer.lastOutcome instanceof TurnOutcome.Completed;
        String response = completed
                ? ((TurnOutcome.Completed) committer.lastOutcome).response() : "";
        check(name + "：终态 Completed", completed);
        check(name + "：终答正确", expected.equals(response));
        check(name + "：历史终答正确", committer.lastRecord != null
                && !committer.lastRecord.history.isEmpty()
                && expected.equals(committer.lastRecord.history
                        .get(committer.lastRecord.history.size() - 1).getContent()));
        if (expectFallback) {
            check(name + "：sink 收到 fallback 终帧", expected.equals(sink.finalText));
        } else {
            check(name + "：sink 收到 done 终帧", expected.equals(sink.finalText));
        }
    }

    private static void runTruncationCase() {
        FakeProvider provider = new FakeProvider("partial", "length");
        FakeCommitter committer = new FakeCommitter();
        TurnObserver observer = new FakeObserver() {
            @Override public String withTruncationNotice(String text, String finishReason) {
                return "length".equals(finishReason) ? text + "（回答被截断）" : text;
            }
        };

        TLMsg req = new TLMsg().setParam(AI_P_USERMESSAGE, "hi");
        TurnSeed seed = new TurnSeed(req, "s2", "s2", "r2", "u2", "agent", "hi",
                TurnPlan.NORMAL, new ArrayList<>(), 0, null, null,
                new ArrayList<>(), "test-model", 0.0, 0,
                "off", false, false, false, 10, 0, true, 2,
                StreamSink.NullStreamSink.INSTANCE, () -> false, null);
        TurnDeps deps = new TurnDeps(null, null, provider, null, null,
                (history, memory, roundStartIdx) -> new ArrayList<>(history),
                committer, observer, null, null, null);

        new ChatTurn("turn:test:r2", seed, deps).run();
        String last = committer.lastRecord == null || committer.lastRecord.history.isEmpty()
                ? "" : committer.lastRecord.history
                        .get(committer.lastRecord.history.size() - 1).getContent();
        check("非流式 length 截断提示进 history", last.contains("被截断"));
        check("非流式 length 返回带提示", committer.lastOutcome instanceof TurnOutcome.Completed
                && ((TurnOutcome.Completed) committer.lastOutcome).response().contains("被截断"));
    }

    /** 只回一个无 tool_calls 的 completion。response 为 null 时模拟 done 缺正文。 */
    static class FakeProvider extends TLBaseModule {
        private final String response;
        private final String finishReason;
        private final String reasoning;

        FakeProvider(String response) { this(response, null, null); }

        FakeProvider(String response, String finishReason) {
            this(response, finishReason, null);
        }

        FakeProvider(String response, String finishReason, String reasoning) {
            super("fakeProvider");
            this.response = response;
            this.finishReason = finishReason;
            this.reasoning = reasoning;
        }

        @Override protected TLBaseModule init() { return this; }

        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
            TLMsg r = createMsg().setParam(RESULT, true);
            if (response != null) r.setParam(AI_P_RESPONSE, response);
            if (finishReason != null) r.setParam(AI_P_FINISH_REASON, finishReason);
            if (reasoning != null) r.setParam(AI_P_REASONING, reasoning);
            return r;
        }
    }

    private static void runReasoningCase() {
        FakeProvider provider = new FakeProvider("answer", null, "think-token");
        FakeCommitter committer = new FakeCommitter();
        FakeObserver observer = new FakeObserver();

        TLMsg req = new TLMsg().setParam(AI_P_USERMESSAGE, "hi");
        TurnSeed seed = new TurnSeed(req, "s3", "s3", "r3", "u3", "agent", "hi",
                TurnPlan.NORMAL, new ArrayList<>(), 0, null, null,
                new ArrayList<>(), "test-model", 0.0, 0,
                "off", false, false, false, 10, 0, true, 2,
                StreamSink.NullStreamSink.INSTANCE, () -> false, null);
        TurnDeps deps = new TurnDeps(null, null, provider, null, null,
                (history, memory, roundStartIdx) -> new ArrayList<>(history),
                committer, observer, null, null, null);

        new ChatTurn("turn:test:r3", seed, deps).run();

        boolean completed = committer.lastOutcome instanceof TurnOutcome.Completed;
        check("off 模式：原生 reasoning 挂到 assistant 消息上（API 回传）", completed
                && committer.lastRecord != null && !committer.lastRecord.history.isEmpty()
                && "think-token".equals(committer.lastRecord.history
                        .get(committer.lastRecord.history.size() - 1).getReasoningContent()));
    }

    static class FakeSink implements StreamSink {
        final String collected;
        String finalText;
        FakeSink(String collected) { this.collected = collected; }
        @Override public String collectedText() { return collected; }
        @Override public void onChunk(String text) { }
        @Override public void onToolEvent(String toolName, String output) { }
        @Override public void onFinalText(String answer, boolean alreadyStreamed) {
            finalText = answer;
        }
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
            return new CommitResult(createReturn(), outcome.response());
        }
        private TLMsg createReturn() { return new TLMsg().setParam(RESULT, true); }
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
