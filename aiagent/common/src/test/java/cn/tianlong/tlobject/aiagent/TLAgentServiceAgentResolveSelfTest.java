package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * targetAgent 短名回退自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>背景：子 Agent 注册在注册表里的 key 是完整家族名（如 aiagent_master:fileAgent），
 * 安装 Skill 时只填短名 fileAgent 原本找不到。回退规则：精确家族名优先；未命中时
 * 按 {@code <任意前缀>:<短名>} 匹配，且只认 IAgentCapable（排除 skill/tool）；
 * 唯一命中才用，多个同名 = 歧义拒绝。</p>
 */
public class TLAgentServiceAgentResolveSelfTest implements TLAiAgentParamString {
    static int pass = 0, fail = 0;

    private static class StubAgent extends TLBaseModule implements IAgentCapable {
        StubAgent(String n) { super(n); }
        @Override protected TLBaseModule init() { return this; }
        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) { return null; }
    }

    private static class StubSkill extends TLBaseModule {
        StubSkill(String n) { super(n); }
        @Override protected TLBaseModule init() { return this; }
        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) { return null; }
    }

    private static Map<String, Object> item(String key, Object inst) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(REGISTRY_P_KEY, key);
        m.put(INSTANCE, inst);
        return m;
    }

    public static void main(String[] args) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item("aiagent_master:fileAgent", new StubAgent("fileAgent")));
        items.add(item("aiagent_master:fileAgent:word", new StubSkill("word")));
        items.add(item("aiagent_master:fileAgent:filesystemMcp", new StubSkill("filesystemMcp")));
        items.add(item("aiagent_master:codeAgent", new StubAgent("codeAgent")));

        check("短名唯一命中",
                TLAgentService.matchAgentFamilyNames("fileAgent", items)
                        .equals(List.of("aiagent_master:fileAgent")));
        check("完整家族名直接命中",
                TLAgentService.matchAgentFamilyNames("aiagent_master:fileAgent", items)
                        .equals(List.of("aiagent_master:fileAgent")));
        check("skill/tool 不参与匹配（word 是 fileAgent 下的工具）",
                TLAgentService.matchAgentFamilyNames("word", items).isEmpty());
        check("未知短名无候选",
                TLAgentService.matchAgentFamilyNames("nope", items).isEmpty());

        List<Map<String, Object>> ambiguous = new ArrayList<>(items);
        ambiguous.add(item("priceTeam:fileAgent", new StubAgent("fileAgent")));
        check("同名多候选全部列出（调用方据此拒绝）",
                TLAgentService.matchAgentFamilyNames("fileAgent", ambiguous).size() == 2);

        List<Map<String, Object>> exactFirst = new ArrayList<>();
        exactFirst.add(item("aiagent_master:fileAgent", new StubAgent("fileAgent")));
        exactFirst.add(item("fileAgent", new StubAgent("fileAgent")));
        check("同名精确候选排最前", "fileAgent".equals(
                TLAgentService.matchAgentFamilyNames("fileAgent", exactFirst).get(0)));

        check("null 请求无候选", TLAgentService.matchAgentFamilyNames(null, items).isEmpty());
        check("null 列表无候选", TLAgentService.matchAgentFamilyNames("fileAgent", null).isEmpty());

        System.out.println("\n结果: " + pass + " 通过, " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }

    static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name);
        }
    }
}
