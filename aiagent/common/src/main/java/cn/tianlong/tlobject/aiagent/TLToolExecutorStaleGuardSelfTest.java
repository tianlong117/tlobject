package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.modules.LogLevel;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TLToolExecutor.cleanupStaleStates 回归自测（runnable main，无 JUnit——与仓库自测惯例一致）。
 *
 * <p>事故背景（2026-10-22，会话 webchat_tianlong_1792671447143）：
 * 主 agent 委派 codingAgent 的单次工具调用跑了 601 秒，被 codingAgent 自己下一次工具执行
 * 触发的 stale 清理判成"孤儿"强杀（warn.log "Cleaned up stale ExecutionState ... age=601s"），
 * 主 agent 走中断分支、界面显示"已中断"，而子 agent 其实又继续跑了 6 分钟。
 *
 * <p>根因：清理只比较状态年龄，不检查仍在等待它的调用线程是否存活。
 * 判定：
 * <ol>
 *   <li>调用线程存活 → 是合法长任务，超龄也不得清理；</li>
 *   <li>调用线程已结束 → 真孤儿，超龄必须清理并置 aborted；</li>
 *   <li>等待人工审批的状态 → 沿用既有豁免；</li>
 *   <li>未超龄 → 不清理。</li>
 * </ol>
 *
 * <p>运行（classpath 见 aistart_args.txt）：
 * <pre>java -cp "aiagent/common/target/classes;core/target/classes;..." \
 *     cn.tianlong.tlobject.aiagent.TLToolExecutorStaleGuardSelfTest</pre>
 * 失败以退出码 1 结束。
 */
public class TLToolExecutorStaleGuardSelfTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        TestExecutor executor = new TestExecutor();
        setTimeout(executor, 600_000L);
        Map<Object, Object> states = states(executor);

        // 1) 调用线程存活 + 已超龄：合法长任务，必须保留
        Object live = newState("live");
        setCreatedAt(live, System.currentTimeMillis() - 601_000L);
        states.put("live", live);
        cleanup(executor);
        check("调用线程仍存活的长任务超龄也不清理", states.containsKey("live"));

        // 2) 调用线程已结束 + 已超龄：真孤儿，必须清理并置 aborted
        AtomicReference<Object> deadRef = new AtomicReference<>();
        Thread deadCaller = new Thread(() -> {
            try {
                Object s = newState("dead");
                setCreatedAt(s, System.currentTimeMillis() - 601_000L);
                states.put("dead", s);
                deadRef.set(s);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "dead-caller");
        deadCaller.start();
        deadCaller.join();
        cleanup(executor);
        check("调用线程已消失的孤儿状态被清理", !states.containsKey("dead"));
        check("孤儿状态被标记 aborted", Boolean.TRUE.equals(aborted(deadRef.get())));
        check("孤儿状态带出清理原因", String.valueOf(abortReason(deadRef.get())).startsWith("staleStateCleanup"));

        // 3) 等待人工审批 + 调用线程已结束：审批守卫仍须豁免（否则审批等待会被强杀）
        //    放在已结束的线程上创建，确保这条只可能由 approvalWaiters 守卫保住，而不是存活守卫
        Thread approvalCaller = new Thread(() -> {
            try {
                Object s = newState("approval");
                setCreatedAt(s, System.currentTimeMillis() - 601_000L);
                setApprovalWaiters(s, 1);
                states.put("approval", s);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "dead-approval-caller");
        approvalCaller.start();
        approvalCaller.join();
        cleanup(executor);
        check("等待人工审批的合法长跑不被清理", states.containsKey("approval"));

        // 4) 未超龄：不清理
        Object young = newState("young");
        setCreatedAt(young, System.currentTimeMillis());
        states.put("young", young);
        cleanup(executor);
        check("未超龄状态不被清理", states.containsKey("young"));

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name);
        }
    }

    /** 无工厂/日志环境的执行器：只绕过 putLog，其余走真实实现 */
    static class TestExecutor extends TLToolExecutor {
        TestExecutor() { super("staleGuardSelfTest"); }
        @Override public void putLog(String content, LogLevel logLevel) { /* 静默 */ }
        @Override public void putLog(Exception e, LogLevel logLevel, String tag) { /* 静默 */ }
    }

    private static final String STATE_CLASS = "cn.tianlong.tlobject.aiagent.TLToolExecutor$ExecutionState";

    private static Object newState(String id) throws Exception {
        Class<?> c = Class.forName(STATE_CLASS);
        Constructor<?> ctor = c.getDeclaredConstructor(String.class, String.class, CountDownLatch.class);
        ctor.setAccessible(true);
        return ctor.newInstance("exec_" + id, "sid_" + id, new CountDownLatch(1));
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> states(TLToolExecutor executor) throws Exception {
        Field f = TLToolExecutor.class.getDeclaredField("states");
        f.setAccessible(true);
        return (Map<Object, Object>) f.get(executor);
    }

    private static void setTimeout(TLToolExecutor executor, long ms) throws Exception {
        Field f = TLToolExecutor.class.getDeclaredField("staleStateTimeoutMs");
        f.setAccessible(true);
        f.setLong(executor, ms);
    }

    private static void cleanup(TLToolExecutor executor) throws Exception {
        Method m = TLToolExecutor.class.getDeclaredMethod("cleanupStaleStates");
        m.setAccessible(true);
        m.invoke(executor);
    }

    private static void setCreatedAt(Object state, long ts) throws Exception {
        Field f = state.getClass().getDeclaredField("createdAt");
        f.setAccessible(true);
        f.setLong(state, ts);
    }

    private static Object aborted(Object state) throws Exception {
        Field f = state.getClass().getDeclaredField("aborted");
        f.setAccessible(true);
        return f.get(state);
    }

    private static Object abortReason(Object state) throws Exception {
        Field f = state.getClass().getDeclaredField("abortReason");
        f.setAccessible(true);
        return f.get(state);
    }

    private static void setApprovalWaiters(Object state, int n) throws Exception {
        Field f = state.getClass().getDeclaredField("approvalWaiters");
        f.setAccessible(true);
        ((AtomicInteger) f.get(state)).set(n);
    }
}
