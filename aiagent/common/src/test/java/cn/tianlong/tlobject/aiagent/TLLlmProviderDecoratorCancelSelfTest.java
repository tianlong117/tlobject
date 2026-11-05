package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;
import okhttp3.Callback;

import java.lang.reflect.Field;

/**
 * 装饰器 Provider 的 cancel 转发自测（runnable main，无 JUnit）。
 *
 * <p>背景（2026-11-05）：intentCache/routing 装饰器不发 HTTP，父类 cancel 只扫自己的
 * 空 dispatcher；/stop 的 LLM_CANCEL 打到外层后静默失效，真实 SSE 继续跑。
 * 修复后装饰器必须把 cancel 原样转发给 delegate。
 *
 * <p>失败以退出码 1 结束。
 */
public class TLLlmProviderDecoratorCancelSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        // 1) RoutingProvider → delegate
        FakeProvider routingDelegate = new FakeProvider("routingDelegate");
        TLRoutingProvider routing = new TLRoutingProvider("testRouting");
        setDelegate(routing, "delegateProvider", routingDelegate);
        routing.cancel(null, new TLMsg().setAction(LLM_CANCEL)
                .setParam(AI_P_SESSIONID, "s1"));
        check("RoutingProvider 把 cancel 转发给 delegate", routingDelegate.cancelCalled);
        check("cancel 保留 sessionId",
                "s1".equals(routingDelegate.lastCancelSessionId));

        // 2) IntentCacheProvider → delegate
        FakeProvider cacheDelegate = new FakeProvider("cacheDelegate");
        TLIntentCacheProvider cache = new TLIntentCacheProvider("testCache");
        setDelegate(cache, "delegateProvider", cacheDelegate);
        cache.cancel(null, new TLMsg().setAction(LLM_CANCEL)
                .setParam(AI_P_SESSIONID, "s2"));
        check("IntentCacheProvider 把 cancel 转发给 delegate", cacheDelegate.cancelCalled);
        check("cancel 保留 sessionId",
                "s2".equals(cacheDelegate.lastCancelSessionId));

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static void setDelegate(Object target, String fieldName, Object delegate)
            throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, delegate);
    }

    /** 只记录 cancel 的假 Provider；不初始化 OkHttpClient。 */
    static class FakeProvider extends TLLlmProvider {
        boolean cancelCalled = false;
        String lastCancelSessionId = null;

        FakeProvider(String name) { super(name); }

        @Override
        public TLMsg getMsg(Object fromWho, TLMsg msg) {
            if (LLM_CANCEL.equals(msg.getAction())) {
                cancelCalled = true;
                lastCancelSessionId = msg.getStringParam(AI_P_SESSIONID, null);
                return createMsg().setParam(RESULT, true);
            }
            return super.getMsg(fromWho, msg);
        }

        @Override protected TLMsg completion(Object fromWho, TLMsg msg) { return null; }
        @Override protected TLMsg completionStream(Object fromWho, TLMsg msg) { return null; }
        @Override protected TLMsg listModels(Object fromWho, TLMsg msg) { return createMsg(); }
        @Override protected Callback createStreamCallback(String resultFor, String resultAction,
                                                           String sessionId, TLMsg msg) {
            return null;
        }
        @Override public String buildRequestBody(TLMsg msg,
            java.util.List<TLConversationHistory> messages,
            java.util.List<TLFunctionDefinition> tools, boolean stream) { return ""; }
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
