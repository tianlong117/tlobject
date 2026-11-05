package cn.tianlong.tlobject.base;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ThreadTask.interrupt() 回归自测（runnable main，无 JUnit——与仓库自测惯例一致）。
 *
 * <p>背景：{@code ThreadTask extends Thread}，但线程池路径（TLBaseModule.putMsgNoWait →
 * TLThreadPool.execute）是把它当 Runnable 交给 ExecutorService，在池线程上执行 {@code run()}。
 * ThreadTask 自身从未 {@code start()}，所以默认的 {@code Thread.interrupt()} 打断的是一个
 * 未启动的 Thread 对象，池线程收不到任何信号。
 *
 * <p>2026-10-22 实测后果：stale 清理/取消打断了"ThreadTask 对象"，被委派的 codingAgent
 * 仍然继续跑了 6 分钟（僵尸续跑）。
 *
 * <p>判定：
 * <ol>
 *   <li>线程池路径（run() 在外部 worker 上执行）→ interrupt() 必须打断该 worker；</li>
 *   <li>直接 start() 路径 → interrupt() 行为保持原样。</li>
 * </ol>
 *
 * 失败以退出码 1 结束。
 */
public class TLThreadTaskInterruptSelfTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        TestOwner owner = new TestOwner("threadTaskInterruptSelfTest");

        // ---- 1) 线程池路径：run() 由外部 worker 线程执行（与 TLThreadPool.execute 一致）----
        BlockingTarget poolTarget = new BlockingTarget();
        TLBaseObject.ThreadTask poolTask = owner.new ThreadTask(poolTarget, new TLMsg().setAction("work"), owner);
        Thread worker = new Thread(poolTask, "pool-worker-sim");
        worker.setDaemon(true);
        worker.start();
        check("任务确实跑在外部 worker 线程上", poolTarget.awaitStarted(2000)
                && poolTarget.workerThread == worker);

        poolTask.interrupt();
        boolean poolStopped = poolTarget.awaitStopped(2000);
        check("线程池路径：ThreadTask.interrupt() 打断实际 worker", poolStopped);
        check("线程池路径：worker 收到中断标志", poolTarget.sawInterrupt);
        if (!poolStopped) worker.interrupt();   // 失败时清理，避免测试进程挂住
        worker.join(2000);
        check("线程池路径：worker 已结束", !worker.isAlive());

        // ---- 2) 直接 start() 路径：不能回退 ----
        BlockingTarget directTarget = new BlockingTarget();
        TLBaseObject.ThreadTask directTask = owner.new ThreadTask(directTarget, new TLMsg().setAction("work"), owner);
        directTask.setDaemon(true);
        directTask.start();
        check("直接 start()：任务已启动", directTarget.awaitStarted(2000));
        directTask.interrupt();
        boolean directStopped = directTarget.awaitStopped(2000);
        check("直接 start()：interrupt() 仍能打断", directStopped);
        if (!directStopped) directTask.interrupt();
        try { directTask.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

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

    /** 仅为构造 ThreadTask 提供外层实例（ThreadTask 是非静态内部类） */
    static class TestOwner extends TLBaseModule {
        TestOwner(String name) { super(name); }
        @Override protected TLBaseModule init() { return this; }
        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) { return null; }
    }

    /** 在 getMsg 里自旋阻塞，只有收到中断才退出 */
    static class BlockingTarget implements IObject {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);
        volatile Thread workerThread;
        volatile boolean sawInterrupt;

        @Override public String getName() { return "blockingTarget"; }
        @Override public TLMsg putMsg(IObject toWho, TLMsg msg) { return null; }

        @Override public TLMsg getMsg(Object fromWho, TLMsg msg) {
            workerThread = Thread.currentThread();
            started.countDown();
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        sawInterrupt = true;
                        break;
                    }
                }
            } finally {
                stopped.countDown();
            }
            return new TLMsg();
        }

        boolean awaitStarted(long ms) throws InterruptedException {
            return started.await(ms, TimeUnit.MILLISECONDS);
        }

        boolean awaitStopped(long ms) throws InterruptedException {
            return stopped.await(ms, TimeUnit.MILLISECONDS);
        }
    }
}
