package cn.tianlong.tlobject.aiagent.workflow;

import cn.tianlong.tlobject.utils.TLDataUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内置合并策略集 + 名字到策略的映射。
 *
 * <p>策略实例全部无状态，可跨节点跨工作流共用（见 {@link TLStateReducer} 契约）。
 * 数值比较统一走 {@link TLDataUtils#getDoubleParam}——工作流上游的值来源很杂
 * （Java 传 Integer/Long、webui JSON 经 Gson 变 Double、agent 可能返回字符串数字），
 * 裸 {@code (Number)} 强转会抛 ClassCastException 并让整个工作流 FAILED。
 *
 * @author tianlong
 * @since 2026/9/13
 */
public final class TLReducers {

    private TLReducers() {}

    /** 覆盖：后到值赢（框架默认行为，保持向后兼容） */
    public static final TLStateReducer LAST_WRITE_WINS = (existing, incoming) -> incoming;

    /**
     * 列表追加：双方是 List 则合并元素，单值则作为元素加入。
     * 返回新 List，不改动入参（existing 可能是上游产出对象的引用，被多个下游共享）。
     */
    public static final TLStateReducer APPEND = (existing, incoming) -> {
        if (incoming == null) return existing;
        List<Object> result = new ArrayList<>();
        collectInto(result, existing);
        collectInto(result, incoming);
        return result;
    };

    /**
     * 数值取小。无法解析为数字的值被忽略（不抛异常）——宁可少一个数，
     * 不让一个脏值把整条工作流打挂。
     */
    public static final TLStateReducer MIN = (existing, incoming) -> {
        Double a = toNumber(existing);
        Double b = toNumber(incoming);
        if (a == null) return b != null ? incoming : existing;
        if (b == null) return existing;
        return Math.min(a, b);
    };

    /** 数值取大，容错同 {@link #MIN} */
    public static final TLStateReducer MAX = (existing, incoming) -> {
        Double a = toNumber(existing);
        Double b = toNumber(incoming);
        if (a == null) return b != null ? incoming : existing;
        if (b == null) return existing;
        return Math.max(a, b);
    };

    /** 数值累加，容错同 {@link #MIN} */
    public static final TLStateReducer SUM = (existing, incoming) -> {
        Double a = toNumber(existing);
        Double b = toNumber(incoming);
        if (a == null) return b != null ? incoming : existing;
        if (b == null) return existing;
        return a + b;
    };

    /** 布尔或：任一为 true 即 true（"任何一方要求人工复审"这类门） */
    public static final TLStateReducer OR_BOOL = (existing, incoming) ->
            toBool(existing) || toBool(incoming);

    /** 布尔与：任一为 false 即 false */
    public static final TLStateReducer AND_BOOL = (existing, incoming) ->
            toBool(existing) && toBool(incoming);

    /** Map 浅合并：后到值覆盖同 key。返回新 Map，不改动入参。 */
    public static final TLStateReducer MAP_MERGE = (existing, incoming) -> {
        if (incoming == null) return existing;
        Map<Object, Object> result = new LinkedHashMap<>();
        if (existing instanceof Map) result.putAll((Map<?, ?>) existing);
        if (incoming instanceof Map) result.putAll((Map<?, ?>) incoming);
        else if (existing == null) return incoming;
        return result;
    };

    /** Set 并集：去重合并（如多个上游各自报出的标签）。返回新 Set。 */
    public static final TLStateReducer SET_UNION = (existing, incoming) -> {
        if (incoming == null) return existing;
        Set<Object> result = new LinkedHashSet<>();
        collectInto(result, existing);
        collectInto(result, incoming);
        return result;
    };

    /**
     * vote/bestOf 的候选累积器。reduce 每次返回新实例，不修改入参
     * （候选可能来自被多个下游共享的上游产出对象）。
     */
    static final class Candidates {
        final List<Object> values;

        Candidates(List<Object> values) {
            this.values = values;
        }
    }

    /**
     * 多数表决：reduce 阶段累积全部候选，finalize 时按 trim 后的文本计数。
     * 空白候选忽略；平票取最早出现；全空白/全 null 返回 null（字段被移除）。
     * 返回值保留最早出现的原始对象（不强行转成 String）。
     */
    public static final TLStateReducer VOTE = new TLStateReducer() {
        @Override
        public Object reduce(Object existing, Object incoming) {
            if (incoming == null) return existing;
            List<Object> values = new ArrayList<>();
            collectCandidates(values, existing);
            values.add(incoming);
            return new Candidates(values);
        }

        @Override
        public Object finalizeValue(Object accumulated) {
            List<Object> values = candidateValues(accumulated);
            LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
            LinkedHashMap<String, Object> first = new LinkedHashMap<>();
            for (Object v : values) {
                if (v == null) continue;
                String key = String.valueOf(v).trim();
                if (key.isEmpty()) continue;
                counts.merge(key, 1, Integer::sum);
                first.putIfAbsent(key, v);
            }
            String bestKey = null;
            int bestCount = -1;
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getValue() > bestCount) {
                    bestCount = e.getValue();
                    bestKey = e.getKey();
                }
            }
            return bestKey == null ? null : first.get(bestKey);
        }
    };

    /**
     * 择优（确定性启发式）：取 trim 后最长的非空白候选；平票取最早出现。
     * 不调用 LLM——需要语义评审请用 Agent 组的 mode=vote（supervisor 评审）。
     */
    public static final TLStateReducer BEST_OF = new TLStateReducer() {
        @Override
        public Object reduce(Object existing, Object incoming) {
            if (incoming == null) return existing;
            List<Object> values = new ArrayList<>();
            collectCandidates(values, existing);
            values.add(incoming);
            return new Candidates(values);
        }

        @Override
        public Object finalizeValue(Object accumulated) {
            List<Object> values = candidateValues(accumulated);
            Object best = null;
            int bestLen = -1;
            for (Object v : values) {
                if (v == null) continue;
                String s = String.valueOf(v).trim();
                if (s.isEmpty()) continue;
                if (s.length() > bestLen) {
                    bestLen = s.length();
                    best = v;
                }
            }
            return best;
        }
    };

    private static void collectCandidates(List<Object> target, Object value) {
        if (value instanceof Candidates) target.addAll(((Candidates) value).values);
        else if (value != null) target.add(value);
    }

    private static List<Object> candidateValues(Object value) {
        if (value instanceof Candidates) return ((Candidates) value).values;
        if (value == null) return List.of();
        return List.of(value);
    }

    /**
     * 类型自适应（未显式配置字段时使用）：List/Set 追加，其余覆盖。
     * 让不写任何配置的现有工作流在「多上游都产出列表」时自动不丢数据。
     *
     * <p><b>上游类型契约</b>：同名字段在各上游之间应保持同类型——集合对集合、标量对标量。
     * 这是本策略成立的前提（引擎侧对不一致的情况打 WARN，见
     * {@code TLWorkflowEngine#warnTypeMismatch}）。类型不一致时的行为由 {@code existing}
     * 的类型决定，同一对类型换个到达顺序结果就不同，属于契约被破坏——应当去上游修，
     * 不要指望靠合并策略兜。</p>
     */
    public static final TLStateReducer ADAPTIVE = (existing, incoming) ->
            (existing instanceof List || existing instanceof Set) ? APPEND.reduce(existing, incoming) : incoming;

    @SuppressWarnings("unchecked")
    private static void collectInto(java.util.Collection<Object> target, Object value) {
        if (value == null) return;
        if (value instanceof java.util.Collection) target.addAll((java.util.Collection<Object>) value);
        else target.add(value);
    }

    /** 数字解析：Number 直取，字符串尝试解析，其余忽略 */
    private static Double toNumber(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean toBool(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean(((String) v).trim());
        return false;
    }

    /** 名字 → 策略（大小写不敏感）。未知名返回 null，由调用方决定报错还是回退默认。 */
    public static TLStateReducer byName(String name) {
        if (name == null) return null;
        switch (name.trim().toLowerCase()) {
            case "lastwritewins":
            case "last":
            case "overwrite":      return LAST_WRITE_WINS;
            case "append":         return APPEND;
            case "min":            return MIN;
            case "max":            return MAX;
            case "sum":            return SUM;
            case "or":             return OR_BOOL;
            case "and":            return AND_BOOL;
            case "mapmerge":       return MAP_MERGE;
            case "setunion":       return SET_UNION;
            case "vote":           return VOTE;
            case "bestof":
            case "best":           return BEST_OF;
            case "adaptive":       return ADAPTIVE;
            default:               return null;
        }
    }

    /** 已注册策略名（错误提示用） */
    public static String knownNames() {
        return "lastWriteWins|append|min|max|sum|or|and|mapMerge|setUnion|vote|bestOf|adaptive";
    }
}
