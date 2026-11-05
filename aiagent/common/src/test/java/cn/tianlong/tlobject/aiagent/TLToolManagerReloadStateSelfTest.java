package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * /reload 重建 FunctionEntry 时继承运行时状态的自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>背景（2026-11-02）：reloadFunction 用 6 参构造器重建条目，enabled/timeoutMs 回到默认值——
 * 用户 /enableskill 关掉的技能重载后"复活"，配置的单次执行超时也丢成 0（不限时）。
 *
 * <p>判定：重载后新条目必须保留旧条目的 enabled / timeoutMs / nativeName，并指向新模块实例。
 */
public class TLToolManagerReloadStateSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    /** TLBaseModule 的最小具体子类（仅用于提供 getName()/getFamilyName()） */
    private static class StubModule extends TLBaseModule {
        StubModule(String n) { super(n); }
        @Override protected TLBaseModule init() { return this; }
        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) { return null; }
    }

    /** 重载走 getNewModule 换实例；这里直接返回准备好的替代实例 */
    private static class ReloadManager extends TLToolManager {
        TLBaseModule replacement;
        ReloadManager(String n) { super(n); }
        @Override protected Object getNewModule(String moduleName) { return replacement; }
    }

    public static void main(String[] args) throws Exception {
        ReloadManager tm = new ReloadManager("tmReloadState");
        Map<String, Object> functions = field(tm, "functions");
        Map<String, Object> modules = field(tm, "modules");

        TLBaseModule oldMod = new StubModule("fileOperationSkill");
        TLBaseModule newMod = new StubModule("fileOperationSkill");
        tm.replacement = newMod;

        Constructor<?> fc = Class.forName("cn.tianlong.tlobject.aiagent.TLToolManager$FunctionEntry")
                .getDeclaredConstructor(String.class, String.class, String.class,
                        TLBaseModule.class, String.class, Map.class, String.class);
        fc.setAccessible(true);
        Object oldEntry = fc.newInstance("file_operation", SKILL_EXECUTE, "skill", oldMod,
                "desc", null, "native_tool");
        setField(oldEntry, "enabled", false);
        setField(oldEntry, "timeoutMs", 5000L);
        functions.put("file_operation", oldEntry);
        modules.put("fileOperationSkill", oldMod);

        Method reload = TLToolManager.class.getDeclaredMethod(
                "reloadFunction", Object.class, TLMsg.class, String.class);
        reload.setAccessible(true);
        TLMsg msg = new TLMsg().setParam(AI_P_SKILLNAME, "file_operation")
                .setParam(HOTLOAD_P_PERSIST, false);
        TLMsg r = (TLMsg) reload.invoke(tm, null, msg, "skill");

        Object newEntry = functions.get("file_operation");
        check("重载成功", r != null && r.parseBoolean(RESULT, false));
        check("重载后指向新实例",
                newEntry != null && fieldOf(newEntry, "module") == newMod);
        check("enabled 继承（重载不复活禁用技能）",
                newEntry != null && Boolean.FALSE.equals(fieldOf(newEntry, "enabled")));
        check("timeoutMs 继承（超时保护不失效）",
                newEntry != null && Long.valueOf(5000L).equals(fieldOf(newEntry, "timeoutMs")));
        check("nativeName 继承", newEntry != null && "native_tool".equals(fieldOf(newEntry, "nativeName")));

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
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
    private static Map<String, Object> field(Object o, String name) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return (Map<String, Object>) f.get(o);
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
