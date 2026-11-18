package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * TLToolManager.updateAgent 描述刷新自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>背景：updateAgent 过去只写 agentsConfig，而工具定义重建读 FunctionEntry.description，
 * 热更新描述对 LLM 完全不生效。判定：调用后 FunctionEntry 被替换、描述更新、
 * enabled/timeoutMs/nativeName 继承；skill 名不能被误更新；未实例化 agent 不报错。</p>
 */
public class TLToolManagerUpdateAgentSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    private static class StubModule extends TLBaseModule {
        StubModule(String n) { super(n); }
        @Override protected TLBaseModule init() { return this; }
        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) { return null; }
    }

    public static void main(String[] args) throws Exception {
        agentDescriptionRefreshesFunctionEntry();
        skillNameIsNotMutated();
        uninstantiatedAgentStillUpdatesConfig();

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static void agentDescriptionRefreshesFunctionEntry() throws Exception {
        TLToolManager tm = new TLToolManager("tmUpdateAgent");
        Map<String, Object> functions = field(tm, "functions");
        Map<String, Object> modules = field(tm, "modules");
        setAgentsConfig(tm, "agentX", "old description");

        TLBaseModule module = new StubModule("agentX");
        modules.put("agentX", module);
        Object oldEntry = newEntry("agentX", "agent", module, "old description", "nativeAgent");
        setField(oldEntry, "enabled", false);
        setField(oldEntry, "timeoutMs", 5000L);
        functions.put("agentX", oldEntry);
        setField(tm, "toolDefsDirty", false);

        TLMsg r = invokeUpdate(tm, "agentX", "new description");

        Object newEntry = functions.get("agentX");
        check("updateAgent 成功", r != null && r.parseBoolean(RESULT, false));
        check("FunctionEntry 被替换", newEntry != null && newEntry != oldEntry);
        check("description 已更新",
                newEntry != null && "new description".equals(fieldOf(newEntry, "description")));
        check("module 保持", newEntry != null && fieldOf(newEntry, "module") == module);
        check("enabled 继承", newEntry != null && Boolean.FALSE.equals(fieldOf(newEntry, "enabled")));
        check("timeoutMs 继承", newEntry != null && Long.valueOf(5000L).equals(fieldOf(newEntry, "timeoutMs")));
        check("nativeName 继承",
                newEntry != null && "nativeAgent".equals(fieldOf(newEntry, "nativeName")));
        check("toolDefs 缓存失效", Boolean.TRUE.equals(fieldOf(tm, "toolDefsDirty")));
        check("agentsConfig 同步更新",
                "new description".equals(agentsConfigOf(tm, "agentX", "description")));
    }

    private static void skillNameIsNotMutated() throws Exception {
        TLToolManager tm = new TLToolManager("tmUpdateSkill");
        Map<String, Object> functions = field(tm, "functions");
        setAgentsConfig(tm, "agentX", "agent desc");

        TLBaseModule skill = new StubModule("skillX");
        Object skillEntry = newEntry("skillX", "skill", skill, "skill desc", "skillX");
        functions.put("skillX", skillEntry);
        setField(tm, "toolDefsDirty", false);

        TLMsg r = invokeUpdate(tm, "skillX", "trying to change skill");

        check("skill 名 updateAgent 失败", r != null && !r.parseBoolean(RESULT, true));
        check("skill 条目不变",
                functions.get("skillX") == skillEntry
                        && "skill desc".equals(fieldOf(functions.get("skillX"), "description")));
        check("失败不失效缓存", Boolean.FALSE.equals(fieldOf(tm, "toolDefsDirty")));
    }

    private static void uninstantiatedAgentStillUpdatesConfig() throws Exception {
        TLToolManager tm = new TLToolManager("tmUpdateLazy");
        setAgentsConfig(tm, "lazyAgent", "old");
        setField(tm, "toolDefsDirty", false);

        TLMsg r = invokeUpdate(tm, "lazyAgent", "new");

        check("未实例化 agent 不报错", r != null && r.parseBoolean(RESULT, false));
        check("未实例化 agent 配置仍更新",
                "new".equals(agentsConfigOf(tm, "lazyAgent", "description")));
        check("未实例化 agent 也失效缓存",
                Boolean.TRUE.equals(fieldOf(tm, "toolDefsDirty")));
    }

    // ======================== 辅助 ========================

    private static TLMsg invokeUpdate(TLToolManager tm, String agentName, String description) throws Exception {
        Method m = TLToolManager.class.getDeclaredMethod("updateAgent", Object.class, TLMsg.class);
        m.setAccessible(true);
        TLMsg msg = new TLMsg()
                .setParam(AI_P_AGENTNAME, agentName)
                .setParam(AI_P_AGENTDESCRIPTION, description);
        return (TLMsg) m.invoke(tm, null, msg);
    }

    private static Object newEntry(String name, String type, TLBaseModule module,
                                   String description, String nativeName) throws Exception {
        Constructor<?> c = Class.forName("cn.tianlong.tlobject.aiagent.TLToolManager$FunctionEntry")
                .getDeclaredConstructor(String.class, String.class, String.class,
                        TLBaseModule.class, String.class, Map.class, String.class);
        c.setAccessible(true);
        return c.newInstance(name, SKILL_EXECUTE, type, module, description, null, nativeName);
    }

    @SuppressWarnings("unchecked")
    private static void setAgentsConfig(TLToolManager tm, String agentName, String description) throws Exception {
        Field f = TLToolManager.class.getDeclaredField("agentsConfig");
        f.setAccessible(true);
        HashMap<String, HashMap<String, String>> cfg =
                (HashMap<String, HashMap<String, String>>) f.get(tm);
        if (cfg == null) {
            cfg = new HashMap<>();
            f.set(tm, cfg);
        }
        HashMap<String, String> entry = new HashMap<>();
        entry.put("description", description);
        cfg.put(agentName, entry);
    }

    @SuppressWarnings("unchecked")
    private static String agentsConfigOf(TLToolManager tm, String agentName, String key) throws Exception {
        Field f = TLToolManager.class.getDeclaredField("agentsConfig");
        f.setAccessible(true);
        HashMap<String, HashMap<String, String>> cfg =
                (HashMap<String, HashMap<String, String>>) f.get(tm);
        return cfg != null && cfg.get(agentName) != null ? cfg.get(agentName).get(key) : null;
    }

    private static Object fieldOf(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void setField(Object o, String name, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return (T) f.get(o);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
