package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;
import okhttp3.Callback;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 摘要异步化回归自测（runnable main，无 JUnit）。
 *
 * <p>背景（2026-11-05）：collectSummaryFragment 原先在 store 调用线程同步调摘要 LLM，
 * 而 store 在 commitTurn → sendFinalFrame 之前，摘要慢会把一轮收尾/终帧卡住。
 * 默认 summaryAsync=true：store 只登记缓冲并起 daemon；summaryAsync=false 恢复同步。
 *
 * <p>失败以退出码 1 结束。
 */
public class TLBaseMemorySummaryAsyncSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        FakeMemory mem = new FakeMemory();
        mem.summaryEnabled = true;
        mem.summaryBatchSize = 1;
        mem.summaryAsync = true;

        long t0 = System.currentTimeMillis();
        mem.collectOne("s1", "u1", "agent", "用户说喜欢蓝色");
        long elapsed = System.currentTimeMillis() - t0;
        check("store 不被摘要 LLM 阻塞（<200ms）", elapsed < 200);
        check("摘要随后异步落盘", mem.stored.await(3, TimeUnit.SECONDS));
        check("摘要内容正确", "用户喜欢蓝色。".equals(mem.lastValue));

        // 显式同步模式仍会阻塞并立即落盘
        FakeMemory sync = new FakeMemory();
        sync.summaryEnabled = true;
        sync.summaryBatchSize = 1;
        sync.summaryAsync = false;
        long t1 = System.currentTimeMillis();
        sync.collectOne("s2", "u1", "agent", "同步摘要");
        long syncElapsed = System.currentTimeMillis() - t1;
        check("summaryAsync=false 同步落盘",
                syncElapsed >= 250 && sync.stored.getCount() == 0);

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    /** 只实现摘要测试需要的最小记忆模块。 */
    static class FakeMemory extends TLBaseMemory {
        final CountDownLatch stored = new CountDownLatch(1);
        volatile String lastValue;

        FakeMemory() { super("fakeSummaryMemory"); }

        void collectOne(String sessionId, String userId, String agentName, String fragment) {
            collectSummaryFragment(sessionId, userId, agentName, fragment, 1L);
        }

        @Override
        protected TLMsg store(Object fromWho, TLMsg msg) { return ok(); }
        @Override
        protected TLMsg retrieve(Object fromWho, TLMsg msg) { return ok(); }
        @Override
        protected TLMsg search(Object fromWho, TLMsg msg) { return ok(); }
        @Override
        protected TLMsg delete(Object fromWho, TLMsg msg) { return ok(); }
        @Override
        protected TLMsg clearAll(Object fromWho, TLMsg msg) { return ok(); }

        private TLMsg ok() { return createMsg().setParam(RESULT, true); }

        @Override
        protected TLLlmProvider resolveSummaryProvider(String agentName) {
            return new FakeProvider();
        }

        @Override
        protected void storeSummaryEntry(TLMemoryEntry entry) {
            lastValue = String.valueOf(entry.getValue());
            stored.countDown();
        }
    }

    static class FakeProvider extends TLLlmProvider {
        FakeProvider() { super("fakeSummaryProvider"); }

        @Override
        public TLMsg getMsg(Object fromWho, TLMsg msg) {
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return createMsg().setParam(RESULT, true).setParam(AI_P_RESPONSE, "用户喜欢蓝色。");
        }

        @Override protected TLMsg completion(Object fromWho, TLMsg msg) { return null; }
        @Override protected TLMsg completionStream(Object fromWho, TLMsg msg) { return null; }
        @Override protected TLMsg listModels(Object fromWho, TLMsg msg) { return createMsg(); }
        @Override protected Callback createStreamCallback(String resultFor, String resultAction,
                                                           String sessionId, TLMsg msg) {
            return null;
        }
        @Override public String buildRequestBody(TLMsg msg,
            List<TLConversationHistory> messages, List<TLFunctionDefinition> tools, boolean stream) {
            return "";
        }
        @Override public TLMsg parseResponse(String responseBody, TLMsg originalMsg) {
            return originalMsg;
        }
        @Override protected String getCompletionsPath() { return ""; }
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
