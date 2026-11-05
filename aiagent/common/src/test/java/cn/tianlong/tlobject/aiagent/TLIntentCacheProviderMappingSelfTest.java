package cn.tianlong.tlobject.aiagent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图缓存数字槽位映射自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>背景（2026-11-02）：学习时按"模板参数出现顺序"记录槽位，回填时也按该顺序取新消息数字。
 * 若 LLM 生成的参数顺序与消息数字顺序不同（例如参数 {a:3,b:2} 对应用户消息里的 2、3），
 * 命中缓存后会把 a/b 的数字交换。修复：学习时记录每个槽位对应的消息数字下标，回填按该下标取值；
 * 同一数字重复出现且无法确定下标时整条不缓存，避免部分替换。
 */
public class TLIntentCacheProviderMappingSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        TLIntentCacheProvider p = new TLIntentCacheProvider("intentMappingSelfTest");

        // LLM 把消息里的 2、3 反序映射到 a、b：回填必须保持"a 来自第 2 个数字"的关系
        Map<String, Object> swapArgs = new LinkedHashMap<>();
        swapArgs.put("a", 3);
        swapArgs.put("b", 2);
        Object entry = makeTemplate(p, "swap", swapArgs, List.of("2", "3"));
        check("槽位记录原数字下标", List.of(1, 0).equals(field(entry, "slotNumIndexes")));

        List<TLToolCall> filled = fill(entry, List.of("4", "5"));
        Map<String, Object> fa = filled.get(0).getArguments();
        check("a 回填第 2 个新数字", ((Number) fa.get("a")).longValue() == 5);
        check("b 回填第 1 个新数字", ((Number) fa.get("b")).longValue() == 4);

        // 正常顺序不受影响
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("a", 2);
        ordered.put("b", 3);
        Object entry2 = makeTemplate(p, "ordered", ordered, List.of("2", "3"));
        Map<String, Object> fo = fill(entry2, List.of("4", "5")).get(0).getArguments();
        check("顺序一致时保持原映射",
                ((Number) fo.get("a")).longValue() == 4 && ((Number) fo.get("b")).longValue() == 5);

        // 同一数字重复出现、下标耗尽：无法映射 → 整条不缓存，宁可不命中也不能填错
        Map<String, Object> dup = new LinkedHashMap<>();
        dup.put("count", 2);
        dup.put("note", "写2首");
        Object entry3 = makeTemplate(p, "dup", dup, List.of("2"));
        check("重复数字无法映射时不缓存", !((Boolean) field(entry3, "cacheable")));

        // 无参工具调用：arguments 为 null（部分 OpenAI 兼容网关省略 arguments 字段、Claude input 缺失）
        // 不得 NPE，按空参数模板处理
        Object entry4 = makeTemplate(p, "noargs", null, List.of());
        List<?> tpl = (List<?>) field(entry4, "template");
        check("空参数模板不 NPE 且可缓存",
                Boolean.TRUE.equals(field(entry4, "cacheable"))
                        && ((TLToolCall) tpl.get(0)).getArguments().isEmpty());
        check("空参数模板回填正常", fill(entry4, List.of()).size() == 1);

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static Object makeTemplate(TLIntentCacheProvider p, String id,
                                       Map<String, Object> args, List<String> nums) throws Exception {
        List<TLToolCall> calls = new ArrayList<>();
        calls.add(new TLToolCall(id, "fn_" + id, args));
        Method m = TLIntentCacheProvider.class.getDeclaredMethod("makeTemplate", List.class, List.class);
        m.setAccessible(true);
        return m.invoke(p, calls, nums);
    }

    @SuppressWarnings("unchecked")
    private static List<TLToolCall> fill(Object entry, List<String> newNums) throws Exception {
        Method m = TLIntentCacheProvider.class.getDeclaredMethod("fillArgsSlots",
                List.class, List.class, List.class, List.class);
        m.setAccessible(true);
        return (List<TLToolCall>) m.invoke(null, field(entry, "template"), newNums,
                field(entry, "numericSlots"), field(entry, "slotNumIndexes"));
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
