package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;

/**
 * skillDir 校验自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>背景：2026-11-21 安装技能时把技能列表里的家族名
 * fileAgents:mermaid-flowchart 填进 skillDir，TLToolManager.hotLoadSkill
 * 拼路径后 Paths.get 抛 InvalidPathException（TLToolManager.java:385），
 * 安装动作以未捕获异常结束。修复要求：拼路径前拒绝非单段名，返回结构化错误。</p>
 */
public class TLToolManagerSkillDirSelfTest implements TLAiAgentParamString {
    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        testValid();
        testInvalid();
        testHotLoadReturnsStructuredError();
        System.out.println("\n结果: " + pass + " 通过, " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }

    static void testValid() {
        for (String s : new String[]{"mermaid-flowchart", "my_skill", "skill.v2", "A1", "browser"})
            check("合法: " + s, TLToolManager.validateSkillDir(s) == null);
    }

    static void testInvalid() {
        String[] cases = {
                "", " fileAgents:mermaid-flowchart", "fileAgents:mermaid-flowchart",
                "../x", "..", ".env", "a/b", "a\\b", "a b", "a.", "-abc",
                "CON", "com1", "LPT9"
        };
        for (String s : cases)
            check("非法: '" + s + "'", TLToolManager.validateSkillDir(s) != null);
        check("null → skillDir required",
                "skillDir required".equals(TLToolManager.validateSkillDir(null)));
        String colonErr = TLToolManager.validateSkillDir("fileAgents:mermaid-flowchart");
        check("冒号错误提示 targetAgent",
                colonErr != null && colonErr.contains("targetAgent"));
        check("冒号错误提示目标Agent框",
                colonErr != null && colonErr.contains("目标Agent"));
    }

    static void testHotLoadReturnsStructuredError() {
        TLToolManager tm = new TLToolManager("tmSkillDirTest");
        TLMsg r = null;
        boolean threw = false;
        try {
            r = tm.hotLoadSkill(null, new TLMsg().setParam("skillDir", "fileAgents:mermaid-flowchart"));
        } catch (Throwable e) {
            threw = true;
            System.out.println("[FAIL] hotLoadSkill 抛出异常: " + e);
        }
        check("非法 skillDir 不抛异常", !threw);
        check("非法 skillDir 返回 RESULT=false",
                r != null && !r.parseBoolean(RESULT, true));
        check("错误信息含'不合法'",
                r != null && String.valueOf(r.getParam("error")).contains("不合法"));
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
