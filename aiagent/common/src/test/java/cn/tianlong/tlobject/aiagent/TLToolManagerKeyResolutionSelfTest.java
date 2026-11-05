package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * 工具注销/重载的名称解析回归自测（runnable main，无 JUnit——与仓库自测惯例一致）。
 *
 * <p>背景（2026-11-02）：functions 的 key 是 LLM 函数名（skillName，如 file_operation），
 * modules 的 key 是模块名（配置名，如 fileOperationSkill）——两者在 initFromConfig 路径下
 * 可以不同。/uninstall、/reload 传的是家族名末段（模块名），旧实现直接拿来当函数名查表，
 * 导致"卸载成功不了 / 模块实例与 registry 残留"。
 *
 * <p>判定：注销入参无论函数名还是模块名都必须命中；modules 按模块名清理；类型不匹配
 * （unregisterSkill 误传 agent 名）不得误删。
 */
public class TLToolManagerKeyResolutionSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    /** TLBaseModule 的最小具体子类（仅用于提供 getName()/getFamilyName()） */
    private static class StubModule extends TLBaseModule {
        StubModule(String n) { super(n); }
        @Override protected TLBaseModule init() { return this; }
        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) { return null; }
    }

    public static void main(String[] args) throws Exception {
        check("按模块名注销（模块名≠函数名）", unregisterWith("fileOperationSkill", "file_operation", "fileOperationSkill", "skill"));
        check("按函数名注销（模块名≠函数名）", unregisterWith("file_operation", "file_operation", "fileOperationSkill", "skill"));
        check("类型不匹配不误删", unregisterWith("same_name", "same_name", "same_name", "agent_then_skill"));
        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    /**
     * @param unregAs    注销入参（函数名或模块名）
     * @param fnKey      functions 的 key（函数名）
     * @param modulesKey modules 的 key（模块名）
     * @param mode       skill / agent_then_skill（后者注册 agent、按 skill 注销，应不命中）
     */
    private static boolean unregisterWith(String unregAs, String fnKey, String modulesKey,
                                          String mode) throws Exception {
        TLToolManager tm = new TLToolManager("tm");
        Map<String, Object> functions = field(tm, "functions");
        Map<String, Object> modules = field(tm, "modules");

        TLBaseModule mod = new StubModule(modulesKey);
        String type = "agent_then_skill".equals(mode) ? "agent" : mode;
        Constructor<?> fc = Class.forName("cn.tianlong.tlobject.aiagent.TLToolManager$FunctionEntry")
                .getDeclaredConstructor(String.class, String.class, String.class,
                        TLBaseModule.class, String.class, Map.class);
        fc.setAccessible(true);
        functions.put(fnKey, fc.newInstance(fnKey, "execute", type, mod, "desc", null));
        modules.put(modulesKey, mod);

        Method unreg = TLToolManager.class.getDeclaredMethod(
                "unregisterFunction", Object.class, TLMsg.class, String.class);
        unreg.setAccessible(true);
        TLMsg msg = new TLMsg().setParam(AI_P_SKILLNAME, unregAs).setParam("persist", false);
        TLMsg r = (TLMsg) unreg.invoke(tm, null, msg, "skill");

        if ("agent_then_skill".equals(mode)) {
            // 类型不匹配：应报告不存在，且条目原样保留
            return !r.parseBoolean(RESULT, false)
                    && functions.containsKey(fnKey) && modules.containsKey(modulesKey);
        }
        return r.parseBoolean(RESULT, false)
                && !functions.containsKey(fnKey)
                && !modules.containsKey(modulesKey);
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
