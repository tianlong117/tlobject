package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

/**
 * 技能层统一兜底自测（backlog ⑥，runnable main，无 JUnit——仓库自测惯例）。
 *
 * 验证 TLBaseSkill.checkMsgAction 把任何逃出技能（getSkillInfo/execute/validate，以及各技能
 * 在 checkMsgAction 里自行分发的自定义动作——如 TLScheduleTaskSkill 的引擎回调）的 Throwable
 * 转成"优雅工具报错"：RESULT=false + 不含内部细节的 AI_P_SKILLOUTPUT；ThreadDeath 必须重抛；
 * 兜底日志（含堆栈）尽力落盘；匿名异常类简名为空时退化用全限定名。
 *
 * 运行：
 *   /d/maven/bin/mvn -q -pl aiagent/common -am compile
 *   /d/maven/bin/mvn -q -pl aiagent/common dependency:build-classpath -Dmdep.outputFile=cp.txt
 *   java -cp "aiagent/common/target/classes;core/target/classes;$(cat aiagent/common/cp.txt)" \
 *        cn.tianlong.tlobject.aiagent.SkillGuardSelfTest
 * 失败退出码 1。
 */
public class SkillGuardSelfTest extends TLBaseSkill {

    private static int passed = 0, failed = 0;

    /** execute 要抛的 Throwable（null=正常返回 RESULT=true）。全部用例的异常均为 unchecked，
     *  故 Runnable 即可（无需 sneaky-throw 泛型）。 */
    private Runnable execThrower;
    /** SKILL_VALIDATE 时抛 NPE */
    private boolean validateThrows;
    /** SKILL_GETINFO 时抛 NPE */
    private boolean getInfoThrows;
    /** 引擎回调动作 onTaskStream 时抛 NPE */
    private boolean streamThrows;

    /** 捕获最后一次兜底日志，验证堆栈确实落盘 */
    private String lastLog;

    public SkillGuardSelfTest() { super(); }

    // 静默日志（未初始化工厂时真 putLog 会 NPE），同时捕获内容供断言
    @Override public void putLog(String content, LogLevel logLevel) { lastLog = content; }
    @Override public void putLog(String content, LogLevel logLevel, String tag) { lastLog = content; }
    @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }

    @Override
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        if (execThrower != null) execThrower.run();
        return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLOUTPUT, "OK");
    }

    @Override
    protected TLMsg validate(Object fromWho, TLMsg msg) {
        if (validateThrows) throw new NullPointerException("validate-boom-secret");
        return super.validate(fromWho, msg);
    }

    @Override
    protected TLMsg getSkillInfo(Object fromWho, TLMsg msg) {
        if (getInfoThrows) throw new NullPointerException("info-boom-secret");
        return super.getSkillInfo(fromWho, msg);
    }

    /**
     * 自定义动作分发（模拟 TLScheduleTaskSkill 的引擎回调形态）：execute 之外的动作不经基类
     * switch，必须同样经 guard()——backlog ⑥ 验证者发现的覆盖缺口正是该形态（onTaskStream
     * 是逐 chunk 的热回调）。本类以同款接线自证守卫可用；真实技能的接线由变异探针守。
     */
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        if ("onTaskStream".equals(msg.getAction()))
            return guard(msg.getAction(), () -> onTaskStream(fromWho, msg));
        return super.checkMsgAction(fromWho, msg);
    }

    /** 引擎热回调：正常路径返回 null；streamThrows 时抛异常模拟回调内故障 */
    protected TLMsg onTaskStream(Object fromWho, TLMsg msg) {
        if (streamThrows) throw new RuntimeException("stream-boom-secret");
        return null;
    }

    // ======================== 自测脚手架 ========================

    static void check(String name, boolean cond, String extra) {
        if (cond) { passed++; System.out.println("PASS " + name); }
        else { failed++; System.out.println("FAIL " + name + " | " + extra); }
    }

    /** 经基类分发口（checkMsgAction）调用，而非直接调 execute——这正是被测的接线 */
    static TLMsg call(SkillGuardSelfTest s, String action) {
        return s.checkMsgAction(null, new TLMsg().setAction(action));
    }

    static String out(TLMsg r) {
        return r == null ? "<null msg>" : r.getStringParam(AI_P_SKILLOUTPUT, "");
    }

    /** 兜底报错的统一形态：RESULT=false 且文案含 "Skill internal error: <简单类名>" */
    static boolean isGuarded(TLMsg r, String simpleClassName) {
        return r != null && !r.parseBoolean(RESULT, true)
                && out(r).contains("Skill internal error: " + simpleClassName);
    }

    public static void main(String[] args) throws Exception {
        SkillGuardSelfTest s = new SkillGuardSelfTest();
        s.skillName = "guard_test";

        // ---- 1. 正常路径不受影响：RESULT=true 的 msg 原样返回 ----
        try {
            TLMsg r = call(s, SKILL_EXECUTE);
            check("1 正常路径原样返回", r != null && r.parseBoolean(RESULT, false)
                    && "OK".equals(out(r)), String.valueOf(r));
        } catch (Throwable t) {
            check("1 正常路径原样返回", false, "异常逃逸: " + t);
        }

        // ---- 2. RuntimeException（NPE）→ RESULT=false，不抛；堆栈落盘 ----
        s.execThrower = () -> { throw new NullPointerException("boom-NPE"); };
        try {
            TLMsg r = call(s, SKILL_EXECUTE);
            check("2 NPE 被兜底为优雅报错", isGuarded(r, "NullPointerException"), out(r));
            check("2b 兜底日志含异常与堆栈(落盘)",
                    s.lastLog != null && s.lastLog.contains("NullPointerException")
                            && s.lastLog.contains("SkillGuardSelfTest"),
                    String.valueOf(s.lastLog));
        } catch (Throwable t) {
            check("2 NPE 被兜底为优雅报错", false, "异常逃逸: " + t);
            check("2b 兜底日志含异常与堆栈(落盘)", false, "异常逃逸: " + t);
        }

        // ---- 3. Error 族：IOError（Error 无 Exception 型堆栈入口，仍必须被兜底） ----
        s.execThrower = () -> { throw new java.io.IOError(new java.io.IOException("boom-io")); };
        try {
            TLMsg r = call(s, SKILL_EXECUTE);
            check("3 IOError 被兜底(Error 族)", isGuarded(r, "IOError"), out(r));
        } catch (Throwable t) {
            check("3 IOError 被兜底(Error 族)", false, "异常逃逸: " + t);
        }

        // ---- 4. StackOverflowError（直接 new，不真爆栈） ----
        s.execThrower = () -> { throw new StackOverflowError(); };
        try {
            TLMsg r = call(s, SKILL_EXECUTE);
            check("4 StackOverflowError 被兜底", isGuarded(r, "StackOverflowError"), out(r));
        } catch (Throwable t) {
            check("4 StackOverflowError 被兜底", false, "异常逃逸: " + t);
        }

        // ---- 5. ThreadDeath 必须重抛（保持线程终止语义，不被安全网吞掉） ----
        s.execThrower = () -> { throw new ThreadDeath(); };
        boolean rethrown = false;
        try {
            call(s, SKILL_EXECUTE);
        } catch (ThreadDeath td) {
            rethrown = true;
        } catch (Throwable t) {
            // 被转成优雅报错或其它异常 = 不满足契约
        }
        check("5 ThreadDeath 必须重抛", rethrown, "未抛出 ThreadDeath");

        // ---- 6. 三段 switch 覆盖：SKILL_VALIDATE / SKILL_GETINFO 抛出同样被兜底 ----
        s.execThrower = null;
        s.validateThrows = true;
        try {
            TLMsg r = call(s, SKILL_VALIDATE);
            check("6a validate 抛异常被兜底", isGuarded(r, "NullPointerException"), out(r));
        } catch (Throwable t) {
            check("6a validate 抛异常被兜底", false, "异常逃逸: " + t);
        } finally {
            s.validateThrows = false;
        }

        s.getInfoThrows = true;
        try {
            TLMsg r = call(s, SKILL_GETINFO);
            check("6b getSkillInfo 抛异常被兜底", isGuarded(r, "NullPointerException"), out(r));
        } catch (Throwable t) {
            check("6b getSkillInfo 抛异常被兜底", false, "异常逃逸: " + t);
        } finally {
            s.getInfoThrows = false;
        }

        // ---- 7. 不泄内部文案：异常 message（甚至嵌套 cause）不得出现在给 LLM 的输出里 ----
        s.execThrower = () -> { throw new NullPointerException("secret-token-xyz"); };
        try {
            TLMsg r = call(s, SKILL_EXECUTE);
            String o = out(r);
            check("7 输出不泄异常 message", o.contains("NullPointerException")
                    && !o.contains("secret-token-xyz"), o);
        } catch (Throwable t) {
            check("7 输出不泄异常 message", false, "异常逃逸: " + t);
        }
        s.execThrower = null;

        // ---- 8. 自定义动作分发（引擎回调形态）同样经 guard：抛异常被兜底、正常路径 null 原样 ----
        s.streamThrows = true;
        try {
            TLMsg r = call(s, "onTaskStream");
            check("8a 引擎动作抛异常被兜底", isGuarded(r, "RuntimeException"), out(r));
        } catch (Throwable t) {
            check("8a 引擎动作抛异常被兜底", false, "异常逃逸: " + t);
        }
        s.streamThrows = false;
        try {
            TLMsg r = call(s, "onTaskStream");
            check("8b 引擎动作正常路径 null 原样透传", r == null, String.valueOf(r));
        } catch (Throwable t) {
            check("8b 引擎动作正常路径 null 原样透传", false, "异常逃逸: " + t);
        }

        // ---- 9. 匿名异常类 getSimpleName() 为空 → 退化用全限定名（否则文案成 "error:  ——"） ----
        s.execThrower = () -> { throw new RuntimeException("anon-boom") { }; };
        try {
            TLMsg r = call(s, SKILL_EXECUTE);
            String o = out(r);
            check("9 匿名异常类退化用全名", r != null && !r.parseBoolean(RESULT, true)
                    && o.contains("SkillGuardSelfTest$") && !o.contains("error:  ——"), o);
        } catch (Throwable t) {
            check("9 匿名异常类退化用全名", false, "异常逃逸: " + t);
        }
        s.execThrower = null;

        System.out.println("\n== " + passed + " passed, " + failed + " failed ==");
        System.exit(failed == 0 ? 0 : 1);
    }
}
