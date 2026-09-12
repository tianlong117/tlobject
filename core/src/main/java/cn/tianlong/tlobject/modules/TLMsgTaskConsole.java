package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 定时任务 MsgTask 控制台命令模块。
 * <p>
 * 提供命令行方式管理 TLMsgTask 模块的定时任务。
 * 支持的命令：
 * <ul>
 *   <li>list                      — 列出所有任务</li>
 *   <li>start all|taskId          — 启动任务</li>
 *   <li>stop all|taskId           — 停止任务</li>
 *   <li>restart all|taskId        — 重启任务</li>
 *   <li>shutdown all|taskId|pool  — 关闭任务或线程池</li>
 *   <li>t=taskId s=status         — 设置任务状态（status: start/stop/restart）</li>
 * </ul>
 * <p>
 * 兼容性说明：
 * <ul>
 *   <li>命令格式与旧版保持一致</li>
 *   <li>内部使用新版 TLMsgTask 的接口（taskId 参数、startTask action）</li>
 *   <li>list 命令显示更详细的任务信息</li>
 * </ul>
 *
 * @author tianlong
 */
public class TLMsgTaskConsole extends TLMsgScanner {

    /** 被管理的 MsgTask 模块名 */
    protected String msgTaskModule;

    /** 任务参数列表（用于 setTaskStatus） */
    protected String[] taskParams = {"delay", "begin", "times"};

    /** 任务列表缓存 */
    protected Map<String, TLMsg> taskMsgTable;

    public TLMsgTaskConsole(String name) {
        super(name);
    }

    public TLMsgTaskConsole(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void initProperty() {
        super.initProperty();

        if (params != null && params.get("msgTaskModule") != null) {
            msgTaskModule = params.get("msgTaskModule");
        }

        // 设置默认模块为自身（控制台命令入口）
        params.put("defaultModule", name);
        params.put("defaultAction", "console");
        defaultModule = name;
        defaultAction = "console";

        // 注册 console action
        HashMap<String, String> mparams = new HashMap<>();
        mparams.put("actions", "console");
        if (msgToModules == null) {
            msgToModules = new HashMap<>();
        }
        msgToModules.put(name, mparams);
    }

    @Override
    protected TLBaseModule init() {
        super.init();
        listTask();
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case "console":
                console(fromWho, msg);
                break;
            default:
                super.checkMsgAction(fromWho, msg);
        }
        return null;
    }

    // ======================== 帮助信息 ========================

    protected void help() {
        System.out.println("========== TLMsgTask 控制台命令 ==========");
        System.out.println("  list                             列出所有任务");
        System.out.println("  start all|<taskId>              启动任务");
        System.out.println("  stop all|<taskId>               停止任务");
        System.out.println("  restart all|<taskId>            重启任务");
        System.out.println("  shutdown all|<taskId>|pool      关闭任务或线程池");
        System.out.println("  t=<taskId> s=<status>           设置任务状态");
        System.out.println("    status: start | stop | restart");
        System.out.println("  help                             显示此帮助");
        System.out.println("=============================================");
    }

    // ======================== 控制台入口 ========================

    private void console(Object fromWho, TLMsg msg) {
        if (msgTaskModule == null) {
            System.err.println("错误: msgTaskModule 未配置");
            return;
        }
        execMsg(msg);
    }

    /**
     * 执行控制台命令。
     * 支持两种格式：
     * 1. 简单命令：list、start all、stop taskId
     * 2. 键值对：t=taskId s=start
     */
    private boolean execMsg(TLMsg msg) {
        HashMap<String, Object> cmds = msg.getArgs();

        if (cmds == null || cmds.isEmpty()) {
            help();
            return true;
        }

        // 单个命令：如 "list" 或 "start all"
        if (cmds.size() == 1) {
            for (String cmd : cmds.keySet()) {
                if (cmds.get(cmd) == null) {
                    if (execCmd(cmd)) {
                        return true;
                    } else {
                        System.out.println("未知命令: " + cmd + "，输入 help 查看帮助");
                        return false;
                    }
                }
            }
        }

        // 键值对命令：如 "t=task1 s=start"
        if (setTaskStatus(msg)) {
            return true;
        }

        System.out.println("无法解析命令: " + cmds.toString());
        System.out.println("输入 help 查看帮助");
        return false;
    }

    // ======================== 命令解析 ========================

    private boolean execCmd(String cmd) {
        // 处理带参数的命令：如 "start task1"
        if (cmd.contains(" ")) {
            String[] parts = cmd.split(" ");
            if (parts.length != 2) {
                System.out.println("需要参数: all 或 taskId");
                return false;
            }
            String command = parts[0].toLowerCase();
            String target = parts[1];

            switch (command) {
                case "start":
                case "run":                    // 兼容旧版习惯
                    if (checkTaskId(target)) start(target);
                    return true;
                case "stop":
                    if (checkTaskId(target)) stop(target);
                    return true;
                case "restart":
                    if (checkTaskId(target)) restart(target);
                    return true;
                case "shutdown":
                    if (shutdownCheck(target)) shutdown(target);
                    return true;
                default:
                    return false;
            }
        }

        // 无参数命令
        switch (cmd.toLowerCase()) {
            case "list":
                listTask();
                return true;
            case "help":
            case "?":
                help();
                return true;
            default:
                return false;
        }
    }

    // ======================== 任务操作 ========================

    /** 启动任务（使用 startTask action） */
    private void start(String taskId) {
        TLMsg msg = createMsg().setAction("startTask");
        if (!"all".equals(taskId)) {
            msg.setParam("taskId", taskId);
        }
        putMsg(msgTaskModule, msg);
        System.out.println("✅ 已发送启动命令: " + taskId);
    }

    /** 停止任务（使用 stopTask action） */
    private void stop(String taskId) {
        TLMsg msg = createMsg().setAction("stopTask");
        if (!"all".equals(taskId)) {
            msg.setParam("taskId", taskId);
        }
        putMsg(msgTaskModule, msg);
        System.out.println("✅ 已发送停止命令: " + taskId);
    }

    /** 重启任务（新版 startTask 已包含重启逻辑） */
    private void restart(String taskId) {
        TLMsg msg = createMsg().setAction("startTask");
        if (!"all".equals(taskId)) {
            msg.setParam("taskId", taskId);
        }
        putMsg(msgTaskModule, msg);
        System.out.println("✅ 已发送重启命令: " + taskId);
    }

    /** 关闭任务/线程池（使用 shutdown action） */
    private void shutdown(String target) {
        TLMsg msg = createMsg().setAction("shutdown");
        if (!"all".equals(target)) {
            if ("pool".equals(target)) {
                msg.setParam("pool", "true");
            } else {
                msg.setParam("taskId", target);
            }
        }
        putMsg(msgTaskModule, msg);
        System.out.println("✅ 已发送关闭命令: " + target);
    }

    // ======================== 设置任务状态 ========================

    private boolean setTaskStatus(TLMsg msg) {
        String taskId = (String) msg.getParam("t");
        if (taskId == null || taskMsgTable == null || !taskMsgTable.containsKey(taskId)) {
            return false;
        }

        String status = (String) msg.getParam("s");
        if (status == null) {
            System.out.println("错误: 缺少状态参数 s (start/stop/restart)");
            return false;
        }

        TLMsg statusMsg = createMsg()
                .setAction("setTaskStatus")
                .setParam("taskId", taskId)
                .setParam("status", status);

        // 传递可选参数
        String cron = (String) msg.getParam("cron");
        if (cron != null) {
            statusMsg.setParam("cronExp", cron);
        }
        for (String param : taskParams) {
            Object value = msg.getParam(param);
            if (value != null) {
                statusMsg.setParam(param, value);
            }
        }

        putMsg(msgTaskModule, statusMsg);
        System.out.println("✅ 已发送状态设置: taskId=" + taskId + ", status=" + status);
        return true;
    }

    // ======================== 任务列表 ========================

    @SuppressWarnings("unchecked")
    private void listTask() {
        System.out.println("========== 任务列表 ==========");

        TLMsg returnMsg = putMsg(msgTaskModule, createMsg().setAction("getTasks"));
        if (returnMsg == null) {
            System.out.println("错误: msgTaskModule 无响应");
            return;
        }

        Object tasksObj = returnMsg.getParam("tasks");
        if (tasksObj == null) {
            System.out.println("  (无任务)");
            return;
        }

        // 兼容新旧两种返回格式
        if (tasksObj instanceof Map) {
            Map<String, Object> tasks = (Map<String, Object>) tasksObj;
            // 新版 TLMsgTask 恒返回 Map，必须在这里登记 taskId：
            // 否则 taskMsgTable 恒为 null，checkTaskId/shutdownCheck/setTaskStatus 一律判"任务不存在"，
            // start/stop/restart 与 t=x s=status 全部失效
            taskMsgTable = new HashMap<>();
            if (tasks.isEmpty()) {
                System.out.println("  (无任务)");
                return;
            }

            int i = 1;
            for (Map.Entry<String, Object> entry : tasks.entrySet()) {
                String taskId = entry.getKey();
                Map<String, Object> info = (Map<String, Object>) entry.getValue();
                taskMsgTable.put(taskId, createMsg().setParam("status", info.get("status")));
                printTaskInfo(i, taskId, info);
                i++;
            }
        } else {
            // 旧版兼容：直接是任务配置 Map
            taskMsgTable = (Map<String, TLMsg>) tasksObj;
            int i = 1;
            for (Map.Entry<String, TLMsg> entry : taskMsgTable.entrySet()) {
                String taskId = entry.getKey();
                TLMsg tmsg = entry.getValue();
                Map<String, Object> info = new HashMap<>();
                info.put("status", tmsg.getParam("status"));
                info.put("destination", tmsg.getDestination());
                info.put("action", tmsg.getAction());
                info.put("execTimes", tmsg.getParam("execTimes"));
                info.put("execDatetime", tmsg.getParam("execDatetime"));
                info.put("nextDatetime", tmsg.getParam("nextDatetime"));
                printTaskInfo(i, taskId, info);
                i++;
            }
        }

        System.out.println("-- 提示: 使用 start/stop/restart [taskId|all] 控制任务");
        System.out.println("-- 使用 t=taskId s=status 设置任务状态");
    }

    private void printTaskInfo(int number, String taskId, Map<String, Object> info) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        String status = getString(info, "status", "-");
        String destination = getString(info, "destination", "-");
        String action = getString(info, "action", "-");
        String execTimes = getString(info, "execTimes", "0");

        String lastTime = formatTime(info.get("execDatetime"), format);
        String nextTime = formatTime(info.get("nextDatetime"), format);

        // 计算倒计时
        String countdown = "";
        Object nextObj = info.get("nextDatetime");
        if (nextObj instanceof Date) {
            long diff = ((Date) nextObj).getTime() - System.currentTimeMillis();
            if (diff > 0) {
                countdown = String.format(" (%.1fs)", diff / 1000.0);
            }
        }

        System.out.printf("%d. %-20s | %-8s | %s → %s | %s次 | 上次: %s | 下次: %s%s%n",
                number,
                taskId != null ? taskId : "?",
                status,
                destination,
                action,
                execTimes,
                lastTime,
                nextTime,
                countdown);
    }

    // ======================== 辅助方法 ========================

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        return v != null ? v.toString() : defaultValue;
    }

    private String formatTime(Object timeObj, SimpleDateFormat format) {
        if (timeObj instanceof Date) {
            return format.format((Date) timeObj);
        }
        return "-";
    }

    private boolean checkTaskId(String target) {
        if ("all".equals(target)) return true;
        if (taskMsgTable == null || !taskMsgTable.containsKey(target)) {
            System.out.println("错误: 任务不存在: " + target);
            System.out.println("提示: 使用 list 查看所有任务");
            return false;
        }
        return true;
    }

    private boolean shutdownCheck(String target) {
        if ("all".equals(target) || "pool".equals(target)) return true;
        if (taskMsgTable == null || !taskMsgTable.containsKey(target)) {
            System.out.println("错误: 任务不存在: " + target);
            System.out.println("提示: 使用 list 查看所有任务");
            return false;
        }
        return true;
    }
}