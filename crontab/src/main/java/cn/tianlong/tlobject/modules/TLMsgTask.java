package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLModuleConfig;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import org.quartz.CronExpression;
import org.xmlpull.v1.XmlPullParser;

import java.text.ParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * 消息计划任务模块（重构版）
 * <p>
 * 核心设计：
 * <ul>
 *   <li><b>统一调度</b>：固定延迟用 scheduleAtFixedRate，Cron 用 schedule 动态调度</li>
 *   <li><b>状态集中</b>：所有任务状态在 TaskRuntime 中管理，taskMsgTable 只存配置</li>
 *   <li><b>无控制消息</b>：直接调度任务执行，不需要 controlMsg 绕一圈</li>
 * </ul>
 *
 * @author tianlong
 */
public class TLMsgTask extends TLBaseModule {

    // ======================== 状态常量 ========================
    private static final String STATUS_INIT = "init";
    private static final String STATUS_RUNNING = "run";
    private static final String STATUS_STOPPED = "stopped";
    private static final String STATUS_ERROR = "error";

    // ======================== 配置字段 ========================
    private int poolSize = 4;
    private long defaultDelay = 60;           // 默认延迟（秒）
    private long defaultPeriod = 60;          // 默认周期（秒）
    private int defaultMaxTimes = 0;          // 默认执行次数（0=不限）
    private long cronCheckInterval = 1000;    // Cron 检查间隔（毫秒）

    // ======================== 运行时字段 ========================
    private final Map<String, TLMsg> taskConfigs = new ConcurrentHashMap<>();
    private final Map<String, TaskRuntime> taskRuntimes = new ConcurrentHashMap<>();
    private ScheduledExecutorService executor;
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    // ======================== 内部类 ========================

    /**
     * 任务运行时状态（所有字段都在这里，集中管理）
     */
    private static class TaskRuntime {
        final String taskId;
        final TLMsg config;                    // 配置引用
        final ScheduledFuture<?> future;       // 调度 Future
        final AtomicInteger executedCount = new AtomicInteger(0);
        volatile String status;
        volatile Date lastExecuteTime;
        volatile Date nextExecuteTime;
        volatile String lastError;

        TaskRuntime(String taskId, TLMsg config, ScheduledFuture<?> future, String status) {
            this.taskId = taskId;
            this.config = config;
            this.future = future;
            this.status = status;
        }

        boolean isRunning() {
            return STATUS_RUNNING.equals(status) && future != null && !future.isCancelled() && !future.isDone();
        }

        boolean isStopped() {
            return STATUS_STOPPED.equals(status) || STATUS_ERROR.equals(status);
        }

        void stop(boolean cancelFuture) {
            status = STATUS_STOPPED;
            if (cancelFuture && future != null && !future.isCancelled()) {
                future.cancel(false);
            }
        }

        void error(String errorMsg) {
            status = STATUS_ERROR;
            lastError = errorMsg;
            if (future != null && !future.isCancelled()) {
                future.cancel(false);
            }
        }
    }

    // ======================== 构造器 ========================

    public TLMsgTask() { super(); }
    public TLMsgTask(String name) { super(name); }
    public TLMsgTask(String name, TLObjectFactory factory) { super(name, factory); }

    // ======================== 生命周期 ========================

    @Override
    protected Object setConfig() {
        myConfig config = new myConfig(configFile, moduleFactory.getConfigDir());
        mconfig = config;
        super.setConfig();

        ArrayList<TLMsg> taskMsgs = config.getTaskMsgTable();
        if (taskMsgs != null) {
            for (TLMsg msg : taskMsgs) {
                String taskId = generateTaskId(msg);
                msg.setParam(TASK_P_TASKID, taskId);
                if (msg.getParam(TASK_P_STATUS) == null) {
                    msg.setParam(TASK_P_STATUS, STATUS_INIT);
                }
                taskConfigs.put(taskId, msg);
            }
        }
        return config;
    }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params == null) return;

        if (params.get("poolSize") != null) {
            try { poolSize = Math.max(1, Integer.parseInt(params.get("poolSize"))); }
            catch (NumberFormatException ignored) {}
        }
        if (params.get("defaultDelay") != null) {
            try { defaultDelay = Long.parseLong(params.get("defaultDelay")); }
            catch (NumberFormatException ignored) {}
        }
        if (params.get("defaultPeriod") != null) {
            try { defaultPeriod = Long.parseLong(params.get("defaultPeriod")); }
            catch (NumberFormatException ignored) {}
        }
        if (params.get("defaultMaxTimes") != null) {
            try { defaultMaxTimes = Integer.parseInt(params.get("defaultMaxTimes")); }
            catch (NumberFormatException ignored) {}
        }
        if (params.get("cronCheckInterval") != null) {
            try { cronCheckInterval = Long.parseLong(params.get("cronCheckInterval")); }
            catch (NumberFormatException ignored) {}
        }
    }

    @Override
    protected TLBaseModule init() {
        if (executor == null) {
            executor = Executors.newScheduledThreadPool(poolSize);
            putLog("任务调度器初始化，线程池大小: " + poolSize, LogLevel.INFO);
        }
        return this;
    }

    @Override
    public void runStartMsg() {
        super.runStartMsg();
        if (taskConfigs.isEmpty()) {
            putLog("无预定义任务", LogLevel.DEBUG);
            return;
        }

        putLog("启动预定义任务，共 " + taskConfigs.size() + " 个", LogLevel.INFO);
        for (Map.Entry<String, TLMsg> entry : taskConfigs.entrySet()) {
            String taskId = entry.getKey();
            TLMsg config = entry.getValue();
            String status = config.getStringParam(TASK_P_STATUS, STATUS_INIT);

            if (STATUS_RUNNING.equals(status) || STATUS_INIT.equals(status)) {
                startTask(taskId);
            }
        }
    }

    @Override
    protected void reConfig() {
        super.reConfig();
        if (params == null) return;

        // 全局关闭
        if ("shutdown".equals(params.get("status"))) {
            shutdownAllTasks();
            return;
        }

        if ("stop".equals(params.get("status"))) {
            stopAllTasks();
            return;
        }

        // 逐个任务处理
        for (Map.Entry<String, TLMsg> entry : taskConfigs.entrySet()) {
            String taskId = entry.getKey();
            TLMsg config = entry.getValue();
            String status = config.getStringParam(TASK_P_STATUS, null);
            if (status == null) continue;

            switch (status) {
                case "restart":
                    restartTask(taskId);
                    break;
                case STATUS_RUNNING:
                    if (!isTaskRunning(taskId)) startTask(taskId);
                    break;
                case STATUS_STOPPED:
                    stopTask(taskId);
                    break;
            }
        }
    }

    @Override
    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        if (destroyed.compareAndSet(false, true)) {
            shutdownAllTasks();
            if (executor != null) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                executor = null;
            }
            putLog("任务调度器已销毁", LogLevel.INFO);
        }
        return super.destroy(fromWho, msg);
    }

    // ======================== 消息分发 ========================

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        String action = msg.getAction();
        if (action == null) return null;

        switch (action) {
            case TASK_REGISTTASK:  return doRegistTask(msg);
            case TASK_UNREGISTTASK: return doUnregistTask(msg);
            case TASK_STARTTASK:   return doStartTask(msg);
            case TASK_STOPTASK:    return doStopTask(msg);
            case TASK_GETTASK:     return doGetTask(msg);
            case TASK_SHUTDOWN:    return doShutdown(msg);
            default:               return null;
        }
    }

    // ======================== 消息处理 ========================

    private TLMsg doRegistTask(TLMsg msg) {
        TLMsg config = (TLMsg) msg.getParam(TASK_P_TASKMSG);
        if (config == null) {
            return createMsg().setParam(RESULT, false).setParam("error", "缺少 TASK_P_TASKMSG");
        }

        String taskId = generateTaskId(config);
        config.setParam(TASK_P_TASKID, taskId);
        taskConfigs.put(taskId, config);

        if (STATUS_RUNNING.equals(config.getStringParam(TASK_P_STATUS, null))) {
            startTask(taskId);
        }

        return createMsg().setParam(RESULT, true).setParam(TASK_P_TASKID, taskId);
    }

    private TLMsg doUnregistTask(TLMsg msg) {
        String taskId = msg.getStringParam(TASK_P_TASKID, null);
        if (taskId == null) {
            return createMsg().setParam(RESULT, false).setParam("error", "缺少 taskId");
        }

        stopTask(taskId);
        taskConfigs.remove(taskId);
        taskRuntimes.remove(taskId);
        return createMsg().setParam(RESULT, true);
    }

    private TLMsg doStartTask(TLMsg msg) {
        String taskId = msg.getStringParam(TASK_P_TASKID, null);
        if (taskId != null) {
            startTask(taskId);
        } else {
            for (String id : taskConfigs.keySet()) startTask(id);
        }
        return createMsg().setParam(RESULT, true);
    }

    private TLMsg doStopTask(TLMsg msg) {
        String taskId = msg.getStringParam(TASK_P_TASKID, null);
        if (taskId != null) {
            stopTask(taskId);
        } else {
            stopAllTasks();
        }
        return createMsg().setParam(RESULT, true);
    }

    private TLMsg doGetTask(TLMsg msg) {
        String taskId = msg.getStringParam(TASK_P_TASKID, null);
        if (taskId != null) {
            TLMsg config = taskConfigs.get(taskId);
            if (config == null) {
                return createMsg().setParam(RESULT, false).setParam("error", "任务不存在");
            }
            TLMsg result = createMsg().copyFrom(config);
            TaskRuntime rt = taskRuntimes.get(taskId);
            if (rt != null) {
                result.setParam("runtimeStatus", rt.status);
                result.setParam("executedCount", rt.executedCount.get());
                if (rt.nextExecuteTime != null) result.setParam("nextExecuteTime", rt.nextExecuteTime);
                if (rt.lastExecuteTime != null) result.setParam("lastExecuteTime", rt.lastExecuteTime);
                if (rt.lastError != null) result.setParam("lastError", rt.lastError);
            }
            return result;
        }

        // 返回所有任务摘要
        Map<String, Object> all = new LinkedHashMap<>();
        for (Map.Entry<String, TLMsg> e : taskConfigs.entrySet()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("destination", e.getValue().getDestination());
            info.put("action", e.getValue().getAction());
            info.put("msgId", e.getValue().getMsgId());
            info.put("status", taskRuntimes.containsKey(e.getKey()) ?
                    taskRuntimes.get(e.getKey()).status : STATUS_STOPPED);
            all.put(e.getKey(), info);
        }
        return createMsg().setParam("tasks", all);
    }

    private TLMsg doShutdown(TLMsg msg) {
        String taskId = msg.getStringParam(TASK_P_TASKID, null);
        if (taskId != null) {
            stopTask(taskId);
        } else {
            shutdownAllTasks();
        }
        return createMsg().setParam(RESULT, true);
    }

    // ======================== 核心调度方法 ========================

    /**
     * 启动任务（核心入口）
     */
    private void startTask(String taskId) {
        if (destroyed.get()) {
            putLog("模块已销毁，无法启动任务: " + taskId, LogLevel.WARN);
            return;
        }

        TLMsg config = taskConfigs.get(taskId);
        if (config == null) {
            putLog("任务配置不存在: " + taskId, LogLevel.ERROR);
            return;
        }

        // 如果已在运行，先停止
        if (isTaskRunning(taskId)) {
            stopTask(taskId);
        }

        // 移除旧的运行时状态
        taskRuntimes.remove(taskId);

        // 解析调度参数
        String cronExp = config.getStringParam(TASK_P_CRON, null);
        long delay = getLongParam(config, TASK_P_DELAYTIME, defaultDelay);
        long period = getLongParam(config, "period", defaultPeriod);
        int maxTimes = getIntParam(config, TASK_P_RUNTIMES, defaultMaxTimes);
        TimeUnit timeUnit = parseTimeUnit(config.getStringParam(TASK_P_TIMEUNIT, TASK_V_TIMEUNIT_S));

        // 计算初始延迟
        long initialDelay = delay;
        String beginStr = config.getStringParam(TASK_P_BEGINTIME, null);
        if (beginStr != null) {
            try {
                long begin = Long.parseLong(beginStr);
                long now = System.currentTimeMillis();
                if (begin > now) initialDelay = Math.max(0, begin - now);
                else initialDelay = 0;
            } catch (NumberFormatException ignored) {}
        }

        ScheduledFuture<?> future;

        if (cronExp != null && !cronExp.isEmpty()) {
            // Cron 模式：动态调度
            future = scheduleCronTask(taskId, config, cronExp, initialDelay, timeUnit, maxTimes);
        } else {
            // 固定延迟模式
            if (period <= 0) period = 60;
            future = executor.scheduleAtFixedRate(
                    createTaskRunnable(taskId, config, maxTimes),
                    initialDelay, period, timeUnit
            );
        }

        // 保存运行时状态
        TaskRuntime rt = new TaskRuntime(taskId, config, future, STATUS_RUNNING);
        taskRuntimes.put(taskId, rt);

        config.setParam(TASK_P_STATUS, STATUS_RUNNING);
        putLog("任务 [" + taskId + "] 已启动" +
                (cronExp != null ? "，Cron: " + cronExp : "，周期: " + period + " " + timeUnit) +
                (maxTimes > 0 ? "，最多执行 " + maxTimes + " 次" : ""), LogLevel.INFO);
    }

    /**
     * Cron 模式调度：每次执行后重新调度下一次
     */
    private ScheduledFuture<?> scheduleCronTask(String taskId, TLMsg config,
                                                String cronExp, long initialDelay,
                                                TimeUnit timeUnit, int maxTimes) {
        CronExpression cron;
        try {
            cron = new CronExpression(cronExp);
        } catch (ParseException e) {
            putLog("Cron 表达式解析失败: " + cronExp, LogLevel.ERROR);
            config.setParam(TASK_P_STATUS, STATUS_ERROR);
            return null;
        }

        // 计算首次执行时间
        Date now = new Date();
        Date firstExec = cron.getTimeAfter(now);
        if (firstExec == null) {
            putLog("Cron 表达式永远不会触发: " + cronExp, LogLevel.ERROR);
            config.setParam(TASK_P_STATUS, STATUS_ERROR);
            return null;
        }

        long firstDelay = Math.max(0, firstExec.getTime() - System.currentTimeMillis());
        if (initialDelay > 0 && initialDelay < firstDelay) {
            firstDelay = initialDelay;
        }

        // 用单次调度 + 递归调度实现 Cron
        return executor.schedule(() -> {
            // 检查是否应该停止
            if (!isTaskRunning(taskId)) return;
            if (maxTimes > 0 && getExecutedCount(taskId) >= maxTimes) {
                stopTask(taskId);
                return;
            }

            // 执行任务
            TaskRuntime rt = taskRuntimes.get(taskId);
            if (rt == null) return;

            try {
                executeTask(taskId, config);
                rt.executedCount.incrementAndGet();
                rt.lastExecuteTime = new Date();

                // 计算下一次执行时间
                Date next = cron.getTimeAfter(rt.lastExecuteTime);
                if (next == null) {
                    putLog("任务 [" + taskId + "] 已完成所有调度", LogLevel.INFO);
                    stopTask(taskId);
                    return;
                }
                rt.nextExecuteTime = next;
                config.setParam("nextDatetime", next);

                // 如果任务还在运行，调度下一次
                if (isTaskRunning(taskId)) {
                    long delayMs = Math.max(cronCheckInterval, next.getTime() - System.currentTimeMillis());
                    executor.schedule(this::runCronCycle, delayMs, TimeUnit.MILLISECONDS);
                }

            } catch (Exception e) {
                handleTaskError(taskId, e);
            }
        }, firstDelay, timeUnit);
    }

    /**
     * Cron 单次执行循环
     */
    private void runCronCycle() {
        String taskId = null;
        // 遍历找到第一个需要执行的 Cron 任务
        for (Map.Entry<String, TaskRuntime> entry : taskRuntimes.entrySet()) {
            TaskRuntime rt = entry.getValue();
            if (rt.status.equals(STATUS_RUNNING) && rt.config.getStringParam(TASK_P_CRON, null) != null) {
                taskId = entry.getKey();
                break;
            }
        }
        if (taskId != null) {
            TLMsg config = taskConfigs.get(taskId);
            if (config != null) {
                String cronExp = config.getStringParam(TASK_P_CRON, null);
                int maxTimes = getIntParam(config, TASK_P_RUNTIMES, defaultMaxTimes);
                scheduleCronTask(taskId, config, cronExp, 0, TimeUnit.MILLISECONDS, maxTimes);
            }
        }
    }

    // ======================== 任务执行 ========================

    /**
     * 创建任务执行 Runnable
     */
    private Runnable createTaskRunnable(String taskId, TLMsg config, int maxTimes) {
        return () -> {
            if (!isTaskRunning(taskId)) return;
            if (maxTimes > 0 && getExecutedCount(taskId) >= maxTimes) {
                putLog("任务 [" + taskId + "] 达到执行次数上限 " + maxTimes + "，自动停止", LogLevel.INFO);
                stopTask(taskId);
                return;
            }

            try {
                executeTask(taskId, config);
                TaskRuntime rt = taskRuntimes.get(taskId);
                if (rt != null) {
                    rt.executedCount.incrementAndGet();
                    rt.lastExecuteTime = new Date();
                }
            } catch (Exception e) {
                handleTaskError(taskId, e);
            }
        };
    }

    /**
     * 执行实际任务
     */
    private void executeTask(String taskId, TLMsg config) {
        TLMsg taskMsg = createMsg();
        taskMsg.copyFrom(config);

        // 移除调度控制参数
        taskMsg.removeParam(TASK_P_TASKID);
        taskMsg.removeParam(TASK_P_DELAYTIME);
        taskMsg.removeParam(TASK_P_BEGINTIME);
        taskMsg.removeParam(TASK_P_RUNTIMES);
        taskMsg.removeParam(TASK_P_STATUS);
        taskMsg.removeParam(TASK_P_TIMEUNIT);
        taskMsg.removeParam(TASK_P_CRON);

        // 设置执行元数据
        taskMsg.setParam("execDatetime", new Date());
        taskMsg.setParam("execTimes", getExecutedCount(taskId) + 1);

        // 发送消息执行任务（使用非阻塞方式，防止任务阻塞调度）
        putMsgNoWait(this, taskMsg);

        putLog("任务 [" + taskId + "] 执行中，第 " + (getExecutedCount(taskId) + 1) + " 次",
                LogLevel.DEBUG);
    }

    // ======================== 任务管理 ========================

    private void stopTask(String taskId) {
        TaskRuntime rt = taskRuntimes.get(taskId);
        if (rt == null) return;

        rt.stop(true);
        TLMsg config = taskConfigs.get(taskId);
        if (config != null) {
            config.setParam(TASK_P_STATUS, STATUS_STOPPED);
            config.setParam("datetime", new Date());
            config.removeParam("nextDatetime");
        }
        taskRuntimes.remove(taskId);
        putLog("任务 [" + taskId + "] 已停止，共执行 " + rt.executedCount.get() + " 次", LogLevel.INFO);
    }

    private void stopAllTasks() {
        for (String taskId : new ArrayList<>(taskRuntimes.keySet())) {
            stopTask(taskId);
        }
        putLog("所有任务已停止", LogLevel.INFO);
    }

    private void shutdownAllTasks() {
        for (String taskId : new ArrayList<>(taskRuntimes.keySet())) {
            TaskRuntime rt = taskRuntimes.get(taskId);
            if (rt != null) {
                rt.stop(true);
                TLMsg config = taskConfigs.get(taskId);
                if (config != null) {
                    config.setParam(TASK_P_STATUS, STATUS_STOPPED);
                    config.setParam("datetime", new Date());
                }
            }
            taskRuntimes.remove(taskId);
        }
        putLog("所有任务已关闭", LogLevel.INFO);
    }

    private void restartTask(String taskId) {
        stopTask(taskId);
        // 重置配置状态
        TLMsg config = taskConfigs.get(taskId);
        if (config != null) {
            config.setParam(TASK_P_STATUS, STATUS_INIT);
        }
        startTask(taskId);
        putLog("任务 [" + taskId + "] 已重启", LogLevel.DEBUG);
    }

    private void handleTaskError(String taskId, Exception e) {
        putLog("任务 [" + taskId + "] 执行异常: " + e.getMessage(), LogLevel.ERROR);
        putLog(e, LogLevel.ERROR, "taskError");

        TLMsg config = taskConfigs.get(taskId);
        if (config != null) {
            config.setParam(TASK_P_STATUS, STATUS_ERROR);
            config.setParam("error", e.getMessage());
            config.setParam("errorTime", new Date());
        }

        TaskRuntime rt = taskRuntimes.get(taskId);
        if (rt != null) {
            rt.error(e.getMessage());
        }
        // 不自动停止，让用户决定是否重启
    }

    // ======================== 辅助方法 ========================

    private boolean isTaskRunning(String taskId) {
        TaskRuntime rt = taskRuntimes.get(taskId);
        return rt != null && rt.isRunning();
    }

    private int getExecutedCount(String taskId) {
        TaskRuntime rt = taskRuntimes.get(taskId);
        return rt != null ? rt.executedCount.get() : 0;
    }

    private String generateTaskId(TLMsg config) {
        String taskId = config.getStringParam(TASK_P_TASKID, null);
        if (taskId != null && !taskId.isEmpty()) return taskId;

        String dest = config.getDestination();
        if (dest == null || dest.isEmpty()) dest = "unknown";

        String action = config.getAction();
        String msgId = config.getMsgId();

        if (action != null && !action.isEmpty()) {
            taskId = dest + "_" + action;
        } else if (msgId != null && !msgId.isEmpty()) {
            taskId = dest + "_" + msgId;
        } else {
            taskId = dest + "_" + System.currentTimeMillis();
        }

        // 去重
        if (taskConfigs.containsKey(taskId) || taskRuntimes.containsKey(taskId)) {
            taskId = taskId + "_" + System.currentTimeMillis();
        }
        return taskId;
    }

    private long getLongParam(TLMsg msg, String key, long defaultValue) {
        String val = msg.getStringParam(key, null);
        if (val == null) return defaultValue;
        try { return Long.parseLong(val); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private int getIntParam(TLMsg msg, String key, int defaultValue) {
        String val = msg.getStringParam(key, null);
        if (val == null) return defaultValue;
        try { return Integer.parseInt(val); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private TimeUnit parseTimeUnit(String unit) {
        if (unit == null) return TimeUnit.SECONDS;
        switch (unit) {
            case TASK_V_TIMEUNIT_MS: return TimeUnit.MILLISECONDS;
            case TASK_V_TIMEUNIT_S:  return TimeUnit.SECONDS;
            case TASK_V_TIMEUNIT_M:  return TimeUnit.MINUTES;
            case TASK_V_TIMEUNIT_H:  return TimeUnit.HOURS;
            default:                 return TimeUnit.SECONDS;
        }
    }

    // ======================== 内部配置类 ========================

    protected class myConfig extends TLModuleConfig {
        private ArrayList<TLMsg> taskMsgTable;

        public myConfig() { super(); }
        public myConfig(String configFile, String configDir) { super(configFile, configDir); }

        public ArrayList<TLMsg> getTaskMsgTable() { return taskMsgTable; }

        @Override
        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if (xpp.getName().equals("taskMsgTable")) {
                    taskMsgTable = getMsgList(xpp, "taskMsgTable");
                }
            } catch (Throwable t) {
                putLog("解析 taskMsgTable 异常: " + t, LogLevel.WARN);
            }
        }
    }
}