package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;

import java.util.HashMap;
import java.util.Map;

/**
 * 脚本技能 LLM 函数名自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>背景：hotLoadSkill 旧默认函数名固定 script_execution，同一 Agent 装第二个
 * 脚本技能会 functions.put 静默覆盖。现在默认用 skillDir（非法字符净化、最长 64），
 * 显式 AI_P_SKILLNAME 走校验，同名指向别的模块直接拒绝。</p>
 */
public class TLToolManagerSkillFunctionNameSelfTest {
    static int pass = 0, fail = 0;

    private static class StubModule extends TLBaseModule {
        StubModule(String n) { super(n); }
        @Override protected TLBaseModule init() { return this; }
        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) { return null; }
    }

    public static void main(String[] args) {
        testDefaultName();
        testValidateName();
        testConflict();
        System.out.println("\n结果: " + pass + " 通过, " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }

    static void testDefaultName() {
        check("目录名直接作函数名",
                "mermaid-flowchart".equals(TLToolManager.defaultFunctionName("mermaid-flowchart")));
        check("点号净化成下划线",
                "skill_v2".equals(TLToolManager.defaultFunctionName("skill.v2")));
        check("空格净化成下划线",
                "a_b".equals(TLToolManager.defaultFunctionName("a b")));
        check("空目录名回落 script_skill",
                "script_skill".equals(TLToolManager.defaultFunctionName("")));
        check("null 回落 script_skill",
                "script_skill".equals(TLToolManager.defaultFunctionName(null)));
        String longName = "x".repeat(80);
        check("超过 64 截断", TLToolManager.defaultFunctionName(longName).length() == 64);
    }

    static void testValidateName() {
        check("合法：连字符", TLToolManager.validateFunctionName("mermaid-flowchart") == null);
        check("合法：下划线", TLToolManager.validateFunctionName("my_skill") == null);
        check("合法：大写数字", TLToolManager.validateFunctionName("ABC123") == null);
        check("非法：点号", TLToolManager.validateFunctionName("a.b") != null);
        check("非法：空格", TLToolManager.validateFunctionName("a b") != null);
        check("非法：路径", TLToolManager.validateFunctionName("a/b") != null);
        check("非法：空", TLToolManager.validateFunctionName("") != null);
        check("非法：null", TLToolManager.validateFunctionName(null) != null);
        check("非法：超长", TLToolManager.validateFunctionName("x".repeat(65)) != null);
    }

    static void testConflict() {
        Map<String, TLToolManager.FunctionEntry> functions = new HashMap<>();
        StubModule other = new StubModule("weibo-collect");
        StubModule mine = new StubModule("mermaid-flowchart");
        functions.put("script_execution",
                new TLToolManager.FunctionEntry("script_execution", "skill_execute", "skill", other, "d", null));

        String conflict = TLToolManager.checkFunctionNameConflict("script_execution", "mermaid-flowchart", functions);
        check("同名不同模块 → 拒绝", conflict != null && conflict.contains("已被 skill「weibo-collect」占用"));
        check("同名同一模块（重装/重载）→ 放行",
                TLToolManager.checkFunctionNameConflict("script_execution", "weibo-collect", functions) == null);
        check("未占用 → 放行",
                TLToolManager.checkFunctionNameConflict("mermaid-flowchart", "mermaid-flowchart", functions) == null);
        check("空函数名 → 放行（由上游 required 校验）",
                TLToolManager.checkFunctionNameConflict("", "mermaid-flowchart", functions) == null);
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
