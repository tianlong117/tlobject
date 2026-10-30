package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import okhttp3.Callback;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图路由缓存 Provider（装饰器模式，模仿 LLM）。
 * 对外表现为普通 Provider——Agent 零改动、完全透明：
 * 首次某消息由 LLM 路由出 tool_calls 后自动学习"消息→工具调用组"映射，
 * 后续相同意图（数字参数可不同）直接返回缓存的 tool_calls，跳过 LLM。
 * 未命中时原样转发 delegateProvider。
 *
 * <h3>语义</h3>
 * <ul>
 *   <li>整句匹配、整组回放：缓存键 = 整句用户消息（数字归一化为 {num} 槽位）；
 *       多意图句子是独立键，不做意图拆分</li>
 *   <li>数字槽位：args 中来自消息的数字同步占位，命中时按新消息数字顺序回填
 *       （"写2首爱情诗"缓存后 "写4首爱情诗" 命中并传 4）；字符串不同 → miss 回 LLM</li>
 *   <li>安全门：仅"单轮工具任务"学习（首轮有工具、次轮无工具）；多轮规划不缓存；
 *       澄清（request_clarification）不缓存；命中时校验数字个数与函数仍存在，不符即弃</li>
 * </ul>
 *
 * <h3>XML 配置示例</h3>
 * <pre>
 * // 直接包装真实 Provider：
 * &lt;provider name="openAiProvider" sameClassAs="intentCacheProvider"
 *           delegateProvider="openAiProvider" .../&gt;
 *
 * // 链式装饰（cache → routing → openAi）：缓存层目标用 cacheDelegateProvider，
 * // delegateProvider 保留给内层 routing 使用：
 * &lt;provider name="openAiProvider" sameClassAs="intentCacheProvider"
 *           cacheDelegateProvider="routingProvider"
 *           delegateProvider="openAiProvider" .../&gt;
 * </pre>
 * 未配置 delegateProvider 时直通（无缓存行为）。流式请求一律直通，v1 不参与。
 *
 * <p>收到 {@code clearCache} 动作会清空缓存与学习暂存（运行态复位，便于重跑）。</p>
 *
 * 创建日期：2026/8/14
 * 作者:tianlong
 */
public class TLIntentCacheProvider extends TLLlmProvider {

    /** 数字槽位占位符 */
    private static final String NUM_SLOT = "{num}";
    private static final Pattern NUM_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?");

    /** 缓存条目：工具调用模板 + 槽位数 + 每个槽位的原值类型（true=JSON number） */
    private static class CacheEntry {
        final List<TLToolCall> template;
        final int slotCount;
        final List<Boolean> numericSlots;
        CacheEntry(List<TLToolCall> template, int slotCount, List<Boolean> numericSlots) {
            this.template = template;
            this.slotCount = slotCount;
            this.numericSlots = numericSlots;
        }
    }

    /** 学习暂存（会话 → 归一化键 + 模板），Phase 2 确认单轮后提交 */
    private static class PendingEntry {
        final String key;
        final CacheEntry entry;
        PendingEntry(String key, CacheEntry entry) { this.key = key; this.entry = entry; }
    }

    // ======================== 配置字段 ========================
    private String delegateProviderName;
    /** 缓存层目标是否来自 delegateProvider 参数（链式装饰时用 cacheDelegateProvider，delegateProvider 留给内层） */
    private boolean delegateFromLegacyParam = false;
    /** 直接借用工厂单例作 delegate（测试/共享场景），否则按 routing 模式克隆创建 */
    private boolean borrowDelegate = false;
    private int intentCacheMax = 200;

    // ======================== 运行时字段 ========================
    private TLLlmProvider delegateProvider;

    /** 意图缓存：归一化键 → 模板（accessOrder=true 即 LRU） */
    private final LinkedHashMap<String, CacheEntry> cache =
            new LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > intentCacheMax;
                }
            };

    /** Phase 1 待学习暂存：归一化消息键 → 条目。
     *  键刻意用消息文本（数字归一化为槽位）而不是 sessionId：master/工作流节点共享
     *  同一缓存实例，模板级共享才有效命中的价值。代价是跨会话共享模板——
     *  同结构但数字含义不同的请求会互相命中（已有设计取舍，见类注释）。
     *  注：llmMsg 本身是带 sessionId 的（TLAiAgent 发请求时 setParam），
     *  要做会话隔离随时可用，只是当前版本没有这么做。 */
    private final ConcurrentHashMap<String, PendingEntry> pendingLearn = new ConcurrentHashMap<>();

    /** Phase 1 缓存命中的消息键（Phase 2 据此合成终版响应，零 LLM） */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** pendingLearn 上限防护（并发/异常残留） */
    private static final int MAX_PENDING = 1000;

    /** inFlight 上限：phaseTwo 未到达（中断/异常/进程静默）时条目会残留，加硬上限防止无限增长 */
    private static final int MAX_IN_FLIGHT = 1000;

    // ======================== 构造器 ========================
    public TLIntentCacheProvider() { super(); }
    public TLIntentCacheProvider(String name) { super(name); }
    public TLIntentCacheProvider(String name, TLObjectFactory factory) { super(name, factory); }

    // ======================== 生命周期 ========================

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null) {
            // 链式装饰（cache → routing → openAi）用 cacheDelegateProvider 指定缓存层目标，
            // delegateProvider 参数保留给内层 routing 使用
            if (params.get("cacheDelegateProvider") != null) {
                delegateProviderName = params.get("cacheDelegateProvider");
                delegateFromLegacyParam = false;
            } else if (params.get(AI_P_DELEGATEPROVIDER) != null) {
                delegateProviderName = params.get(AI_P_DELEGATEPROVIDER);
                delegateFromLegacyParam = true;
            }
            if (params.get("intentCacheMax") != null) {
                try { intentCacheMax = Integer.parseInt(params.get("intentCacheMax")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("borrowDelegate") != null)
                borrowDelegate = "true".equals(params.get("borrowDelegate"));
        }
    }

    @Override
    protected TLBaseModule init() {
        super.init();
        if (delegateProviderName != null && !delegateProviderName.isEmpty()) {
            initDelegate();
        } else {
            putLog("TLIntentCacheProvider [" + name + "] delegateProvider not configured — passthrough",
                    LogLevel.WARN);
        }
        putLog("TLIntentCacheProvider [" + name + "] delegate=" + delegateProviderName
                + " max=" + intentCacheMax, LogLevel.DEBUG);
        return this;
    }

    /** 克隆创建 delegate（与 TLRoutingProvider 同模式）；borrowDelegate 时直接借用工厂单例 */
    private void initDelegate() {
        if (borrowDelegate) {
            try {
                Object m = getModuleInFactory(delegateProviderName);
                if (m instanceof TLLlmProvider) {
                    delegateProvider = (TLLlmProvider) m;
                    putLog("Borrow delegate: " + delegateProviderName, LogLevel.DEBUG);
                    return;
                }
                putLog("Factory delegate not found: " + delegateProviderName + " — fallback to clone", LogLevel.WARN);
            } catch (Exception e) {
                putLog("Borrow delegate error: " + e.toString(), LogLevel.WARN);
            }
        }
        try {
            String delegateName = name + "_delegate";
            HashMap<String, String> cfg = new HashMap<>(params);
            cfg.remove("cacheDelegateProvider");
            if (delegateFromLegacyParam) cfg.remove(AI_P_DELEGATEPROVIDER);
            cfg.remove("intentCacheMax");
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

    // ======================== 消息分发 ========================

    /**
     * 在父类分发前拦一个 clearCache：缓存是模块实例态、随工厂单例驻留进程，
     * 谁要"从头再来一遍"（测试的第二遍、调试重跑）就得有入口把它清掉，
     * 否则第二遍的"学习"环节直接命中第一遍的条目，行为与第一遍不一致。
     * 学习暂存与在途标记同属运行态，一并清——只清 cache 会留下一半状态。
     */
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        if (LLM_CLEARCACHE.equals(msg.getAction())) return clearCache();
        return super.checkMsgAction(fromWho, msg);
    }

    private TLMsg clearCache() {
        int cleared;
        synchronized (this) {
            cleared = cache.size();
            cache.clear();
        }
        pendingLearn.clear();
        inFlight.clear();
        putLog("TLIntentCacheProvider [" + name + "] cache cleared (" + cleared + " entries)", LogLevel.DEBUG);
        return createMsg().setParam(RESULT, true).setParam("cleared", cleared);
    }

    // ======================== 核心：completion 拦截 ========================

    @Override
    protected TLMsg completion(Object fromWho, TLMsg msg) {
        if (delegateProvider == null) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "TLIntentCacheProvider: no delegate configured");
        }
        // per-agent 缓存开关：带 _noCache 标志的请求直接透传 delegate
        // （不查缓存、不学习、不留 pendingLearn/inFlight——带审批门禁的 agent 配置 intentCache=false）
        if (msg.parseBoolean(AI_P_NOCACHE, false)) return forward(msg);
        @SuppressWarnings("unchecked")
        List<TLConversationHistory> messages =
                (List<TLConversationHistory>) msg.getListParam(AI_P_MESSAGEHISTORY, new ArrayList<>());

        // 阶段判定：仅看"本轮"（最后一条 user 消息之后）是否已有工具结果。
        // 多轮会话里历史包含前几轮的 tool_result，若按全历史判断会把新一轮误判为 phase TWO，
        // 导致跳过查找、永远无法命中
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == TLConversationHistory.Role.user) { lastUserIdx = i; break; }
        }
        boolean hasToolResults = false;
        for (int i = Math.max(lastUserIdx, 0); i < messages.size(); i++) {
            TLConversationHistory h = messages.get(i);
            if (h.getToolCallId() != null && !h.getToolCallId().isEmpty()) { hasToolResults = true; break; }
        }
        return hasToolResults ? phaseTwo(msg, messages) : phaseOne(msg, messages);
    }

    /** Phase 1：无工具结果——查缓存，命中返回缓存的 tool_calls，未命中转发并观察学习 */
    @SuppressWarnings("unchecked")
    private TLMsg phaseOne(TLMsg msg, List<TLConversationHistory> messages) {
        String lastUser = extractLastUserMessage(messages);
        if (lastUser.isEmpty()) return forward(msg);   // 异常形态直通

        String key = normalizeIntents(lastUser);
        List<String> nums = extractNumbers(lastUser);

        CacheEntry entry = cacheGet(key);
        if (entry != null) {
            // 槽位数校验 + 函数有效性校验。不符只按 miss 处理、不删条目：
            // 共享缓存实例下其他 agent 的校验失败不应销毁本 agent 学到的映射
            if (entry.slotCount == nums.size() && functionsStillValid(entry.template, msg)) {
                List<TLToolCall> calls = fillArgsSlots(entry.template, nums, entry.numericSlots);
                // 超过上限不再记录：phaseTwo 的 remove 会返回 false，走正常 forward，不影响正确性
                if (inFlight.size() < MAX_IN_FLIGHT)
                    inFlight.add(key);
                else
                    putLog("[INTENT-CACHE] inFlight 达到上限 " + MAX_IN_FLIGHT
                            + "，本次命中不再登记（phaseTwo 将走正常转发）", LogLevel.WARN);
                putLog("[INTENT-CACHE] hit key=" + key + " → " + calls.size() + " tool call(s)", LogLevel.DEBUG);
                return createMsg().setParam(RESULT, true)
                        .setParam("hasToolCalls", true)
                        .setParam(AI_P_TOOLCALLS, calls)
                        .setParam(AI_P_RESPONSE, "")
                        .setParam(AI_P_PROMPTTOKENS, 0)
                        .setParam(AI_P_COMPLETIONTOKENS, 0)
                        .setParam(AI_P_TOTALTOKENS, 0);
            }
        }

        // miss：转发并观察（首轮有工具且非澄清 → 暂存学习）
        TLMsg result = forward(msg);
        if (result != null && result.parseBoolean(RESULT, false)) {
            boolean hasToolCalls = result.parseBoolean("hasToolCalls", false);
            List<TLToolCall> calls = (List<TLToolCall>) result.getListParam(AI_P_TOOLCALLS, null);
            if (hasToolCalls && calls != null && !calls.isEmpty()) {
                boolean clarify = false;
                for (TLToolCall tc : calls) {
                    if (AGENT_REQUESTCLARITY.equals(tc.getFunctionName())) { clarify = true; break; }
                }
                if (!clarify) {
                    if (pendingLearn.size() < MAX_PENDING) {
                        pendingLearn.put(key, new PendingEntry(key, makeTemplate(calls, nums)));
                    }
                    putLog("[INTENT-CACHE] pending learn key=" + key, LogLevel.DEBUG);
                }
            }
            // 纯文本响应：不清 pending（共享实例下其他会话可能正在学习同键），
            // 由 Phase 2 提交/丢弃，超限靠 MAX_PENDING 兜底
        }
        return result;
    }

    /** Phase 2：含工具结果——命中路径合成终版（零 LLM）；正常路径转发并提交/丢弃学习 */
    private TLMsg phaseTwo(TLMsg msg, List<TLConversationHistory> messages) {
        String lastUser = extractLastUserMessage(messages);
        String key = lastUser.isEmpty() ? null : normalizeIntents(lastUser);

        if (key != null && inFlight.remove(key)) {
            StringBuilder sb = new StringBuilder();
            // 只拼接本轮（最后一条 user 消息之后）的工具结果——
            // 多轮会话的历史含前几轮结果，全拼会越拼越多且混入无关旧内容
            int lastUserIdx = -1;
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i).getRole() == TLConversationHistory.Role.user) { lastUserIdx = i; break; }
            }
            for (int i = Math.max(lastUserIdx, 0); i < messages.size(); i++) {
                TLConversationHistory h = messages.get(i);
                if (h.getToolCallId() != null && !h.getToolCallId().isEmpty()
                        && h.getContent() != null && !h.getContent().isEmpty()) {
                    if (sb.length() > 0) sb.append("\n\n");
                    sb.append(h.getContent());
                }
            }
            if (sb.length() > 0) {
                putLog("[INTENT-CACHE] final synthesized key=" + key
                        + " (" + sb.length() + " chars)", LogLevel.DEBUG);
                return createMsg().setParam(RESULT, true)
                        .setParam("hasToolCalls", false)
                        .setParam(AI_P_RESPONSE, sb.toString())
                        .setParam(AI_P_PROMPTTOKENS, 0)
                        .setParam(AI_P_COMPLETIONTOKENS, 0)
                        .setParam(AI_P_TOTALTOKENS, 0);
            }
            // 空合成（历史里没有可拼的工具结果）→ 回退转发，绝不返回空响应污染历史
            putLog("[INTENT-CACHE] synth empty — fallback to forward key=" + key, LogLevel.WARN);
        }

        TLMsg result = forward(msg);
        PendingEntry pending = key != null ? pendingLearn.remove(key) : null;
        if (pending != null && result != null && result.parseBoolean(RESULT, false)) {
            if (!result.parseBoolean("hasToolCalls", false)) {
                // 单轮工具任务确认 → 提交缓存
                cachePut(pending.key, pending.entry);
                putLog("[INTENT-CACHE] learned key=" + pending.key
                        + " (" + pending.entry.slotCount + " slots)", LogLevel.DEBUG);
            } else {
                // 多轮规划 → 不缓存（防丢步骤）
                putLog("[INTENT-CACHE] multi-round plan — not cached key=" + key, LogLevel.DEBUG);
            }
        }
        return result;
    }

    @Override
    protected TLMsg completionStream(Object fromWho, TLMsg msg) {
        // 流式一律直通，v1 不参与
        return delegateProvider != null ? putMsg(delegateProvider, msg)
                : createMsg().setParam(RESULT, false)
                        .setParam("error", "TLIntentCacheProvider: no delegate configured");
    }

    private TLMsg forward(TLMsg msg) {
        return putMsg(delegateProvider, msg);
    }

    // ======================== 工具方法 ========================

    /** 数字序列 → {num} 占位（键与 args 共用） */
    private static String normalizeIntents(String s) {
        if (s == null) return "";
        return NUM_PATTERN.matcher(s).replaceAll(NUM_SLOT);
    }

    /** 提取消息中的数字（按出现顺序） */
    private static List<String> extractNumbers(String s) {
        List<String> nums = new ArrayList<>();
        if (s == null) return nums;
        Matcher m = NUM_PATTERN.matcher(s);
        while (m.find()) nums.add(m.group());
        return nums;
    }

    /** 学习模板：深拷贝 tool calls，args 数字归一化 + 统计槽位数（记录每个槽位原值是否为 JSON number） */
    private CacheEntry makeTemplate(List<TLToolCall> calls, List<String> nums) {
        List<TLToolCall> tpl = new ArrayList<>();
        int slots = 0;
        List<Boolean> numericSlots = new ArrayList<>();
        for (TLToolCall tc : calls) {
            Map<String, Object> args = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : tc.getArguments().entrySet()) {
                Object v = e.getValue();
                if (v instanceof String) {
                    Matcher m = NUM_PATTERN.matcher((String) v);
                    StringBuffer sb = new StringBuffer();
                    while (m.find()) {
                        m.appendReplacement(sb, NUM_SLOT);
                        slots++;
                        numericSlots.add(Boolean.FALSE);
                    }
                    m.appendTail(sb);
                    args.put(e.getKey(), sb.toString());
                } else if (v instanceof Number) {
                    // 结构化输出常见原生数字（{"count":3}）。只有确实来自本轮消息的数字才做槽位，
                    // 否则把一个与消息无关的字面量也替换掉，会改变工具语义/槽位对齐。
                    String s = numberToString((Number) v);
                    if (nums != null && nums.contains(s)) {
                        args.put(e.getKey(), NUM_SLOT);
                        slots++;
                        numericSlots.add(Boolean.TRUE);
                    } else {
                        args.put(e.getKey(), v);
                    }
                } else {
                    args.put(e.getKey(), v);
                }
            }
            tpl.add(new TLToolCall(tc.getId(), tc.getFunctionName(), args));
        }
        return new CacheEntry(tpl, slots, numericSlots);
    }

    /** 命中回填：模板 args 中的占位符按序替换为新消息数字；数字槽位回填为原生 JSON number */
    private static List<TLToolCall> fillArgsSlots(List<TLToolCall> template, List<String> nums,
                                                  List<Boolean> numericSlots) {
        List<TLToolCall> filled = new ArrayList<>();
        int globalSlot = 0;   // 跨 tool call / 跨参数共用，与 makeTemplate 的登记顺序一致
        for (TLToolCall tc : template) {
            Map<String, Object> args = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : tc.getArguments().entrySet()) {
                Object v = e.getValue();
                if (v instanceof String && ((String) v).contains(NUM_SLOT)) {
                    String s = (String) v;
                    StringBuilder sb = new StringBuilder();
                    boolean wholeNumeric = false;
                    int pos = 0, occ = 0;
                    while (true) {
                        int p = s.indexOf(NUM_SLOT, pos);
                        if (p < 0) { sb.append(s.substring(pos)); break; }
                        sb.append(s, pos, p);
                        boolean numeric = numericSlots != null && globalSlot < numericSlots.size()
                                && Boolean.TRUE.equals(numericSlots.get(globalSlot));
                        if (occ == 0 && NUM_SLOT.equals(s)) wholeNumeric = numeric;
                        sb.append(globalSlot < nums.size() ? nums.get(globalSlot) : NUM_SLOT);
                        globalSlot++;
                        occ++;
                        pos = p + NUM_SLOT.length();
                    }
                    // 整个值就是单个数字槽位 → 还原成 number，避免把 schema 的 number 变成 string
                    if (wholeNumeric && !NUM_SLOT.equals(sb.toString())) {
                        args.put(e.getKey(), toNumber(sb.toString()));
                    } else {
                        args.put(e.getKey(), sb.toString());
                    }
                } else {
                    args.put(e.getKey(), v);
                }
            }
            filled.add(new TLToolCall(tc.getId(), tc.getFunctionName(), args));
        }
        return filled;
    }

    /** 数字文本 → 原生 Number（整数 Long，小数 Double）；解析失败保留字符串 */
    private static Object toNumber(String raw) {
        try {
            if (raw.matches("-?\\d+"))
                return Long.parseLong(raw);
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    /** JSON number 去掉无意义的 .0（3.0 → "3"），与消息里提取的数字文本对齐 */
    private static String numberToString(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (!Double.isInfinite(d) && !Double.isNaN(d) && d == Math.rint(d))
                return String.valueOf((long) d);
        }
        return String.valueOf(n);
    }

    /** 函数失效保护：模板中的 functionName 必须仍在当前工具定义里 */
    @SuppressWarnings("unchecked")
    private boolean functionsStillValid(List<TLToolCall> template, TLMsg msg) {
        List<TLFunctionDefinition> defs =
                (List<TLFunctionDefinition>) msg.getListParam(AI_P_FUNCTIONDEFS, null);
        if (defs == null) return false;
        Set<String> names = new HashSet<>();
        for (TLFunctionDefinition d : defs) names.add(d.getName());
        for (TLToolCall tc : template) {
            if (!names.contains(tc.getFunctionName())) return false;
        }
        return true;
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

    // ======================== 缓存操作（LRU，同步） ========================

    private synchronized CacheEntry cacheGet(String key) { return cache.get(key); }
    private synchronized void cachePut(String key, CacheEntry e) { cache.put(key, e); }
    private synchronized void cacheRemove(String key) { cache.remove(key); }

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
        throw new UnsupportedOperationException("TLIntentCacheProvider delegates to real provider");
    }

    @Override
    public String buildRequestBody(TLMsg msg, List<TLConversationHistory> messages,
                                   List<TLFunctionDefinition> tools, boolean stream) {
        throw new UnsupportedOperationException("TLIntentCacheProvider delegates to real provider");
    }

    @Override
    public TLMsg parseResponse(String responseBody, TLMsg originalMsg) {
        throw new UnsupportedOperationException("TLIntentCacheProvider delegates to real provider");
    }

    @Override
    protected String getCompletionsPath() {
        throw new UnsupportedOperationException("TLIntentCacheProvider delegates to real provider");
    }
}
