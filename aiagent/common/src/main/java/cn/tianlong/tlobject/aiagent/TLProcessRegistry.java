package cn.tianlong.tlobject.aiagent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行中子进程注册表（按执行线程索引）。
 *
 * 用途：让"中断当前 chat"能够杀掉工具/skill 派生的外部进程。
 * 由于工具在 doChat 的 worker 线程上同步执行，skill 无需知道 sessionId 或 agent，
 * 只需以 {@code Thread.currentThread()} 为键登记自己启动的 {@link Process}；
 * Agent 的 stopChat 拿到该会话的 worker 线程后 {@link #killByThread(Thread)} 即可。
 *
 * 线程安全：全部基于 ConcurrentHashMap / newKeySet，可跨线程并发访问。
 *
 * 创建日期：2026/7/14
 * 作者:tianlong
 */
public final class TLProcessRegistry {

    private static final Map<Thread, Set<Process>> BY_THREAD = new ConcurrentHashMap<>();

    private TLProcessRegistry() {}

    /** 登记：某线程启动了一个外部进程。 */
    public static void register(Thread owner, Process process) {
        if (owner == null || process == null) return;
        BY_THREAD.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet()).add(process);
    }

    /** 注销：进程已正常结束（或已由本方调用方处理）。 */
    public static void unregister(Thread owner, Process process) {
        if (owner == null || process == null) return;
        Set<Process> set = BY_THREAD.get(owner);
        if (set != null) {
            set.remove(process);
            if (set.isEmpty()) BY_THREAD.remove(owner);
        }
    }

    /**
     * 强杀指定线程登记的所有仍存活进程。
     * @return 实际强杀的进程数
     */
    public static int killByThread(Thread owner) {
        if (owner == null) return 0;
        Set<Process> set = BY_THREAD.remove(owner);
        if (set == null) return 0;
        int killed = 0;
        for (Process p : set) {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
                killed++;
            }
        }
        return killed;
    }
}
