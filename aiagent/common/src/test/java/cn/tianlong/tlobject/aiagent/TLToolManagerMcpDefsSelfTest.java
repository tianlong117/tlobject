package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具定义重建自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>背景（2026-11-02）：旧实现每次重建前先 removeIf 清掉所有 type=mcp 条目，再逐个 agent
 * 探 AGENT_GETTOOLDEFS。MCP agent 临时失败（server 抖动/重启）时旧工具被删且 dirty 已清，
 * 不会自动重试——下一次 LLM 请求就少了一组工具。修复：先收集探活结果，成功的 agent 才替换，
 * 失败的保留上一版。
 */
public class TLToolManagerMcpDefsSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    /** 可切换成功/失败的 MCP agent 桩 */
    static class StubAgent extends TLBaseModule {
        boolean fail;
        StubAgent(String n) { super(n); }
        @Override protected TLBaseModule init() { return this; }
        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
            if (AGENT_GETTOOLDEFS.equals(msg.getAction())) {
                if (fail) return null;
                TLMsg r = createMsg().setParam(AI_P_FUNCTIONDEFS,
                        List.of(TLFunctionDefinition.fromSkill("mcpTool", "NEW", null)));
                Map<String, String> routes = new LinkedHashMap<>();
                routes.put("mcpTool", "nativeTool");
                r.setParam(AI_P_TOOLROUTES, routes);
                return r;
            }
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        TLToolManager tm = new TLToolManager("mcpDefsSelfTest");
        Field ownerF = TLToolManager.class.getDeclaredField("owner");
        ownerF.setAccessible(true);
        ownerF.set(tm, new TLAiAgent("mcpDefsSelfTestOwner"));

        @SuppressWarnings("unchecked")
        Map<String, Object> functions = (Map<String, Object>) field(tm, "functions");
        StubAgent agent = new StubAgent("mcpAgent");
        functions.put("mcpAgent", newEntry("mcpAgent", "execute", "agent", agent, "d"));
        functions.put("mcpTool", newEntry("mcpTool", MCP_CALLTOOL, "mcp", agent, "OLD"));

        tm.getFunctionDefinitions();
        check("探活成功：旧 MCP 被新贡献替换",
                functions.containsKey("mcpTool") && "NEW".equals(desc(functions.get("mcpTool"))));

        // MCP agent 抖动：探活返回 null（不可用）
        setDirty(tm);
        agent.fail = true;
        tm.getFunctionDefinitions();
        check("探活失败：上一版 MCP 工具保留",
                functions.containsKey("mcpTool") && "NEW".equals(desc(functions.get("mcpTool"))));

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static Object newEntry(String name, String action, String type,
                                   TLBaseModule module, String desc) throws Exception {
        Class<?> fc = Class.forName("cn.tianlong.tlobject.aiagent.TLToolManager$FunctionEntry");
        return fc.getDeclaredConstructor(String.class, String.class, String.class,
                        TLBaseModule.class, String.class, Map.class)
                .newInstance(name, action, type, module, desc, null);
    }

    private static String desc(Object fe) throws Exception {
        Field f = fe.getClass().getDeclaredField("description");
        f.setAccessible(true);
        return (String) f.get(fe);
    }

    private static void setDirty(TLToolManager tm) throws Exception {
        Field f = TLToolManager.class.getDeclaredField("toolDefsDirty");
        f.setAccessible(true);
        f.setBoolean(tm, true);
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
