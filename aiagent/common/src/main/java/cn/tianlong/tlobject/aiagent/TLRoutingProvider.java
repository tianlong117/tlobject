package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import okhttp3.Callback;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 带模型路由的 LLM Provider（装饰器模式）。
 * 对外表现为普通 Provider，内部根据任务复杂度自动选择便宜/昂贵模型。
 * TLAiAgent 零改动。
 *
 * <h3>三种策略</h3>
 * <ul>
 *   <li><b>rule_only</b>（默认）— 纯关键词匹配，零额外延迟</li>
 *   <li><b>hybrid</b>（推荐）— 规则优先，未命中时用 simpleModel 做元认知分类</li>
 *   <li><b>llm_judge</b> — 始终用 simpleModel 判断复杂度，最准确但增加延迟</li>
 * </ul>
 *
 * <h3>XML 配置示例</h3>
 * <pre>
 * &lt;provider name="openAiProvider"
 *           classfile="cn.tianlong.tlobject.aiagent.TLRoutingProvider"
 *           delegateProvider="openAiProvider"
 *           apiKey="sk-..." apiBaseUrl="https://api.deepseek.com"
 *           defaultModel="deepseek-v4-pro"
 *
 *           simpleModel="deepseek-chat"
 *           simpleKeywords="你好,hello,是什么,翻译,谢谢"
 *
 *           complexModel="deepseek-v4-pro"
 *           complexKeywords="写代码,编程,debug,推理,数学"
 *
 *           strategy="hybrid"/&gt;
 * </pre>
 *
 * 创建日期：2026/7/30
 * 作者:tianlong
 */
public class TLRoutingProvider extends TLLlmProvider {

    private static class RouteResult {
        final String name;
        final String model;
        RouteResult(String name, String model) { this.name = name; this.model = model; }
    }

    // ======================== 配置字段 ========================
    enum Strategy { RULE_ONLY, HYBRID, LLM_JUDGE }

    private Strategy strategy = Strategy.RULE_ONLY;
    private String delegateProviderName;
    private String simpleModel;
    private String complexModel;
    private List<String> simpleKeywords = Collections.emptyList();
    private List<String> complexKeywords = Collections.emptyList();

    // ======================== 运行时字段 ========================
    private TLLlmProvider delegateProvider;

    // ======================== 构造器 ========================
    public TLRoutingProvider() { super(); }
    public TLRoutingProvider(String name) { super(name); }
    public TLRoutingProvider(String name, TLObjectFactory factory) { super(name, factory); }

    // ======================== 生命周期 ========================
    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null) {
            if (params.get(AI_P_DELEGATEPROVIDER) != null)
                delegateProviderName = params.get(AI_P_DELEGATEPROVIDER);
            if (params.get(AI_P_ROUTER_STRATEGY) != null) {
                try { strategy = Strategy.valueOf(params.get(AI_P_ROUTER_STRATEGY).toUpperCase()); }
                catch (IllegalArgumentException e) { strategy = Strategy.RULE_ONLY; }
            }
            if (params.get("simpleModel") != null)
                simpleModel = params.get("simpleModel");
            if (params.get("complexModel") != null)
                complexModel = params.get("complexModel");
            if (params.get("simpleKeywords") != null)
                simpleKeywords = splitKeywords(params.get("simpleKeywords"));
            if (params.get("complexKeywords") != null)
                complexKeywords = splitKeywords(params.get("complexKeywords"));
        }
    }

    @Override
    protected TLBaseModule init() {
        super.init();
        if (delegateProviderName != null && !delegateProviderName.isEmpty()) {
            initDelegate();
        } else {
            putLog("TLRoutingProvider [" + name + "] delegateProvider not configured — passthrough",
                    LogLevel.WARN);
        }
        putLog("TLRoutingProvider [" + name + "] strategy=" + strategy
                + " simple=" + simpleModel + " complex=" + complexModel, LogLevel.DEBUG);
        return this;
    }

    private void initDelegate() {
        try {
            String delegateName = name + "_delegate";
            HashMap<String, String> cfg = new HashMap<>(params);
            cfg.remove(AI_P_DELEGATEPROVIDER);
            cfg.remove(AI_P_ROUTER_STRATEGY);
            cfg.remove("simpleModel");  cfg.remove("complexModel");
            cfg.remove("simpleKeywords"); cfg.remove("complexKeywords");
            cfg.remove("classfile");
            cfg.put("sameClassAs", delegateProviderName);

            modulesClass.put(delegateName, cfg);
            modulesParams.put(delegateName, cfg);

            TLBaseModule m = (TLBaseModule) getMyModule(delegateName);
            if (m instanceof TLLlmProvider) {
                delegateProvider = (TLLlmProvider) m;
                putLog("Delegate: " + delegateName + " → " + m.getClass().getSimpleName(), LogLevel.DEBUG);
            } else {
                putLog("Failed to create delegate: " + delegateProviderName, LogLevel.ERROR);
            }
        } catch (Exception e) {
            putLog("initDelegate error: " + e.toString(), LogLevel.ERROR);
        }
    }

    // ======================== 重写：拦截 + 分类 + 委托 ========================

    @Override
    protected TLMsg completion(Object fromWho, TLMsg msg) {
        return routeAndDelegate(msg);
    }

    @Override
    protected TLMsg completionStream(Object fromWho, TLMsg msg) {
        return routeAndDelegate(msg);
    }

    @SuppressWarnings("unchecked")
    private TLMsg routeAndDelegate(TLMsg msg) {
        if (delegateProvider == null) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "TLRoutingProvider: no delegate configured");
        }

        if (!msg.containsParam(AI_P_MODEL) || msg.getStringParam(AI_P_MODEL, "").isEmpty()) {
            List<TLConversationHistory> messages =
                    (List<TLConversationHistory>) msg.getListParam(AI_P_MESSAGEHISTORY, new ArrayList<>());
            String userMessage = extractLastUserMessage(messages);
            String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");

            if (!userMessage.isEmpty()) {
                RouteResult rr = classify(userMessage, sessionId);
                if (rr != null && rr.model != null) {
                    msg.setParam(AI_P_MODEL, rr.model);
                    msg.setParam(AI_P_ROUTER_TIER, rr.name);
                }
            }
        }
        return putMsg(delegateProvider, msg);
    }

    private String extractLastUserMessage(List<TLConversationHistory> messages) {
        if (messages == null) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            TLConversationHistory h = messages.get(i);
            if (h.getRole() == TLConversationHistory.Role.user) {
                String c = h.getContent();
                return c != null ? c : "";
            }
        }
        return "";
    }

    // ======================== 分类 ========================

    private RouteResult classify(String userMessage, String sessionId) {
        switch (strategy) {
            case LLM_JUDGE:
                return llmClassify(userMessage, sessionId);
            case HYBRID: {
                RouteResult r = ruleClassify(userMessage, sessionId);
                if (r != null) return r;
                return llmClassify(userMessage, sessionId);
            }
            case RULE_ONLY:
            default: {
                RouteResult r = ruleClassify(userMessage, sessionId);
                if (r != null) return r;
                RouteResult d = defaultResult();
                logRoute(sessionId, "default", d);
                return d;
            }
        }
    }

    private RouteResult ruleClassify(String userMessage, String sessionId) {
        if (simpleModel != null) {
            for (String kw : simpleKeywords) {
                if (userMessage.contains(kw)) {
                    RouteResult r = new RouteResult("simple", simpleModel);
                    logRoute(sessionId, "rule", r);
                    return r;
                }
            }
        }
        if (complexModel != null) {
            for (String kw : complexKeywords) {
                if (userMessage.contains(kw)) {
                    RouteResult r = new RouteResult("complex", complexModel);
                    logRoute(sessionId, "rule", r);
                    return r;
                }
            }
        }
        return null;
    }

    /**
     * LLM 元认知分类：用 simpleModel 判断用户消息复杂度。
     * simpleModel 本来就是最便宜最快的模型，做分类任务（输出 1 个词）开销可忽略。
     */
    private RouteResult llmClassify(String userMessage, String sessionId) {
        if (delegateProvider == null) return null;

        try {
            List<String> labels = new ArrayList<>();
            if (simpleModel != null) labels.add("simple: " + simpleModel);
            if (complexModel != null) labels.add("complex: " + complexModel);
            String tierDesc = String.join("\n", labels);

            List<TLConversationHistory> ctx = new ArrayList<>();
            ctx.add(new TLConversationHistory(TLConversationHistory.Role.system,
                    "你是任务复杂度分类器。分析用户消息，判断适合使用哪个模型。\n"
                    + "可选模型:\n" + tierDesc + "\n"
                    + "只回复层级名称（simple 或 complex），不要其他内容。"));
            ctx.add(new TLConversationHistory(TLConversationHistory.Role.user, userMessage));

            TLMsg classifyMsg = createMsg()
                    .setAction(LLM_COMPLETION)
                    .setParam(AI_P_MESSAGEHISTORY, ctx)
                    .setParam(AI_P_MODEL, simpleModel != null ? simpleModel : defaultModel)
                    .setParam(AI_P_MAXTOKENS, 10)
                    .setParam(AI_P_TEMPERATURE, 0.0);
            TLMsg result = putMsg(delegateProvider, classifyMsg);

            if (result == null || !result.parseBoolean(RESULT, false)) {
                logRoute(sessionId, "llm_fail", defaultResult());
                return null;
            }

            String text = result.getStringParam(AI_P_RESPONSE, "").trim().toLowerCase();
            if (text.contains("simple") && simpleModel != null) {
                RouteResult r = new RouteResult("simple", simpleModel);
                logRoute(sessionId, "llm", r);
                return r;
            }
            if (text.contains("complex") && complexModel != null) {
                RouteResult r = new RouteResult("complex", complexModel);
                logRoute(sessionId, "llm", r);
                return r;
            }
        } catch (Exception e) {
            putLog("LLM classify failed: " + e.toString(), LogLevel.WARN);
        }
        RouteResult d = defaultResult();
        logRoute(sessionId, "llm_fallback", d);
        return d;
    }

    private RouteResult defaultResult() {
        if (defaultModel != null) return new RouteResult("default", defaultModel);
        return new RouteResult("default", complexModel != null ? complexModel : simpleModel);
    }

    private void logRoute(String sessionId, String reason, RouteResult r) {
        putLog("Route [" + sessionId + "] " + reason + ":" + r.name + " → " + r.model, LogLevel.DEBUG);
    }

    // ======================== 工具方法 ========================

    private static List<String> splitKeywords(String s) {
        if (s == null || s.trim().isEmpty()) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        for (String kw : s.split(",")) {
            String t = kw.trim();
            if (!t.isEmpty()) list.add(t);
        }
        return list;
    }

    // ======================== 委托 / 桩方法 ========================

    @Override
    protected TLMsg listModels(Object fromWho, TLMsg msg) {
        if (delegateProvider != null) return putMsg(delegateProvider, msg);
        return createMsg().setParam(RESULT, true).setParam("models",
                Arrays.asList(defaultModel != null ? defaultModel : "unknown"));
    }

    @Override
    protected Callback createStreamCallback(String resultFor, String resultAction,
                                            String sessionId, TLMsg msg) {
        throw new UnsupportedOperationException("TLRoutingProvider delegates to real provider");
    }

    @Override
    public String buildRequestBody(TLMsg msg, List<TLConversationHistory> messages,
                                   List<TLFunctionDefinition> tools, boolean stream) {
        throw new UnsupportedOperationException("TLRoutingProvider delegates to real provider");
    }

    @Override
    public TLMsg parseResponse(String responseBody, TLMsg originalMsg) {
        throw new UnsupportedOperationException("TLRoutingProvider delegates to real provider");
    }

    @Override
    protected String getCompletionsPath() {
        throw new UnsupportedOperationException("TLRoutingProvider delegates to real provider");
    }
}
