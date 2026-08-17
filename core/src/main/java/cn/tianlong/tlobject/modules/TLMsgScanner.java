package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.*;
import org.xmlpull.v1.XmlPullParser;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通用控制台消息输入模块。
 * 从控制台读取用户输入，解析为 TLMsg 并发送到目标模块。
 *
 * 支持两种输入模式：
 * 1. 快捷命令（cmds 配置表）：如 "list" → 映射到预定义消息
 * 2. 自由格式（key=value 参数）：如 "m=module a=action key1=value1"
 *
 * <p>配置示例：
 * <pre>
 * &lt;msgToModules&gt;
 *     &lt;module name="msgTask" actions="console;list;start;stop"/&gt;
 * &lt;/msgToModules&gt;
 * &lt;cmds&gt;
 *     &lt;cmd name="list" module="msgTask" action="getTasks"/&gt;
 * &lt;/cmds&gt;
 * </pre>
 *
 * @author tianlong
 */
public class TLMsgScanner extends TLBaseModule {

    // ======================== 配置字段 ========================
    /** 模块 → {actions} 映射表 */
    protected HashMap<String, HashMap<String, String>> msgToModules;

    /** 命令 → {module, action, ...} 映射表 */
    protected HashMap<String, HashMap<String, String>> cmds;

    /** 默认模块名（当输入中没有 m=module 时使用） */
    protected String defaultModule;

    /** 默认动作名（当输入中没有 a=action 时使用） */
    protected String defaultAction;

    /** 密码（非空时需要登录） */
    protected String passwd;

    /** 是否已登录 */
    protected volatile boolean ifLogin = true;

    /** 扫描线程是否运行 */
    protected final AtomicBoolean running = new AtomicBoolean(false);

    /** 系统命令列表 */
    protected final List<String> systemCmd = Arrays.asList("help", "?", "quit", "shutdown");

    /** 线程引用，用于中断 */
    private Thread scanThread;

    // ======================== 构造器 ========================

    public TLMsgScanner() {
        super();
    }

    public TLMsgScanner(String name) {
        super(name);
    }

    public TLMsgScanner(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    // ======================== 生命周期 ========================

    @Override
    protected TLBaseModule init() {
        ifExceptionHandle = false;
        return this;
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        if (params != null) {
            if (params.get("defaultModule") != null) {
                defaultModule = params.get("defaultModule");
            }
            if (params.get("defaultAction") != null) {
                defaultAction = params.get("defaultAction");
            }
            if (params.get("passwd") != null) {
                passwd = params.get("passwd");
                ifLogin = false;
            }
        }
    }

    @Override
    protected Object setConfig() {
        myConfig config = new myConfig(configFile, moduleFactory.getConfigDir());
        mconfig = config;
        super.setConfig();
        msgToModules = config.getMsgToModules();
        cmds = config.getCmds();
        return config;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        if ("startScan".equals(msg.getAction())) {
            startScan(fromWho, msg);
        }
        return null;
    }

    @Override
    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        stopScan();
        return super.destroy(fromWho, msg);
    }

    // ======================== 核心方法 ========================

    /**
     * 启动扫描线程（异步，不阻塞调用线程）
     */
    private void startScan(Object fromWho, TLMsg msg) {
        if (running.compareAndSet(false, true)) {
            scanThread = new Thread(this::scanLoop, "MsgScanner-" + name);
            scanThread.setDaemon(true);
            scanThread.start();
            putLog("消息扫描器已启动", LogLevel.INFO);
        } else {
            putLog("扫描器已在运行", LogLevel.WARN);
        }
    }

    /**
     * 停止扫描线程
     */
    private void stopScan() {
        if (running.compareAndSet(true, false)) {
            if (scanThread != null) {
                scanThread.interrupt();
                try {
                    scanThread.join(2000);
                } catch (InterruptedException ignored) {
                }
                scanThread = null;
            }
            putLog("消息扫描器已停止", LogLevel.INFO);
        }
    }

    /**
     * 主扫描循环（在独立线程中运行）
     */
    private void scanLoop() {
        if (msgToModules == null || msgToModules.isEmpty()) {
            System.out.println("警告: 没有配置 msgToModules，无法解析输入");
            putLog("没有配置 msgToModules", LogLevel.WARN);
            running.set(false);
            return;
        }

        System.out.println(name + " 已启动，输入 help 查看帮助");
        if (!ifLogin) {
            System.out.print("密码: ");
        }

        try (Scanner sc = new Scanner(System.in)) {
            while (running.get() && sc.hasNextLine()) {
                String line = sc.nextLine();
                if (line == null) {
                    break;
                }
                String input = line.trim();
                if (input.isEmpty()) {
                    continue;
                }

                // 登录检查
                if (!checkLogin(input)) {
                    continue;
                }

                // 系统命令
                if (handleSystemCommand(input)) {
                    continue;
                }

                // 尝试解析为命令
                TLMsg cmdMsg = parseCommand(input);
                if (cmdMsg != null) {
                    putMsg(this, cmdMsg);
                    continue;
                }

                // 尝试解析为自由格式消息
                TLMsg inputMsg = parseInput(input);
                if (inputMsg != null && validateMessage(inputMsg)) {
                    IObject module = (IObject) getModuleFromFactory(inputMsg.getDestination());
                    if (module == null) {
                        System.out.println("❌ 模块未实例化: " + inputMsg.getDestination());
                    } else {
                        putMsg(module, inputMsg);
                    }
                } else {
                    System.out.println("❌ 输入格式错误，请检查参数");
                }
            }
        } catch (Exception e) {
            putLog("扫描循环异常: " + e.getMessage(), LogLevel.ERROR);
        } finally {
            running.set(false);
            putLog("扫描循环结束", LogLevel.DEBUG);
        }
    }

    // ======================== 登录 ========================

    private boolean checkLogin(String input) {
        if (ifLogin) {
            return true;
        }
        if (input.equals(passwd)) {
            ifLogin = true;
            System.out.println("✅ 登录成功！");
            System.out.print("> ");
            return false;
        } else {
            System.out.println("❌ 密码错误");
            System.out.print("密码: ");
            return false;
        }
    }

    // ======================== 系统命令 ========================

    private boolean handleSystemCommand(String input) {
        if (!systemCmd.contains(input.toLowerCase())) {
            return false;
        }

        switch (input.toLowerCase()) {
            case "help":
            case "?":
                printHelp();
                break;
            case "quit":
                logout();
                break;
            case "shutdown":
                doShutdown();
                break;
            default:
                break;
        }
        return true;
    }

    protected void printHelp() {
        if (msgToModules == null || msgToModules.isEmpty()) {
            System.out.println("未配置 msgToModules");
            return;
        }
        System.out.println("========== 可用模块 ==========");
        for (String moduleName : msgToModules.keySet()) {
            HashMap<String, String> config = msgToModules.get(moduleName);
            String actions = config.getOrDefault("actions", "");
            System.out.println("  module: " + moduleName + "  actions: " + actions);
        }

        if (cmds != null && !cmds.isEmpty()) {
            System.out.println("========== 快捷命令 ==========");
            for (String cmd : cmds.keySet()) {
                HashMap<String, String> config = cmds.get(cmd);
                String module = config.getOrDefault("module", "");
                String action = config.getOrDefault("action", "");
                String msgId = config.getOrDefault("msgid", "");
                System.out.printf("  %-12s → module: %s, action: %s, msgId: %s%n",
                        cmd, module, action, msgId);
            }
        }

        System.out.println("========== 输入格式 ==========");
        System.out.println("  自由格式: m=module a=action key1=value1 key2=value2");
        System.out.println("  快捷命令: 直接输入命令名");
        System.out.println("  系统命令: help, quit, shutdown");
        System.out.println("  提示: 使用 'm=xxx' 指定模块，'a=xxx' 指定动作");
        if (defaultModule != null) {
            System.out.println("  默认模块: " + defaultModule);
        }
        if (defaultAction != null) {
            System.out.println("  默认动作: " + defaultAction);
        }
    }

    protected void logout() {
        if (passwd != null && !passwd.isEmpty()) {
            ifLogin = false;
            System.out.println("已登出");
        } else {
            System.out.println("未启用密码，无需登出");
        }
        System.out.print("密码: ");
    }

    /**
     * 优雅关闭：停止扫描并通知工厂关闭
     */
    protected void doShutdown() {
        System.out.println("正在关闭系统...");
        stopScan();
        if (moduleFactory != null) {
            moduleFactory.shutdown();
        } else {
            System.exit(0);
        }
    }

    // ======================== 输入解析 ========================

    /**
     * 解析快捷命令
     */
    protected TLMsg parseCommand(String input) {
        if (cmds == null || cmds.isEmpty()) {
            return null;
        }

        // 支持带参数的命令：如 "stop task1"
        String[] parts = input.split("\\s+");
        String cmdName = parts[0];

        if (!cmds.containsKey(cmdName)) {
            return null;
        }

        HashMap<String, String> cmdConfig = cmds.get(cmdName);
        String module = cmdConfig.get("module");
        if (module == null || module.isEmpty()) {
            return null;
        }

        TLMsg cmdMsg = createMsg().setDestination(module);

        String action = cmdConfig.get("action");
        String msgId = cmdConfig.get("msgid");
        if (action != null && !action.isEmpty()) {
            cmdMsg.setAction(action);
        } else if (msgId != null && !msgId.isEmpty()) {
            cmdMsg.setMsgId(msgId);
        } else {
            return null;
        }

        // 解析剩余参数
        for (int i = 1; i < parts.length; i++) {
            String param = parts[i];
            if (param.isEmpty()) {
                continue;
            }
            int eqIdx = param.indexOf('=');
            if (eqIdx > 0) {
                String key = param.substring(0, eqIdx).trim();
                String value = param.substring(eqIdx + 1).trim();
                if (!key.isEmpty()) {
                    cmdMsg.setParam(key, value);
                }
            } else {
                // 无等号，作为无值参数
                cmdMsg.setParam(param.trim(), null);
            }
        }

        return cmdMsg;
    }

    /**
     * 解析自由格式输入：key=value 空格分隔
     * 支持两种特殊前缀：m= 和 a= 分别表示 destination 和 action
     */
    protected TLMsg parseInput(String input) {
        TLMsg msg = createMsg();
        String[] parts = input.split("\\s+");

        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            int eqIdx = part.indexOf('=');
            if (eqIdx > 0) {
                String key = part.substring(0, eqIdx).trim();
                String value = part.substring(eqIdx + 1).trim();
                if (key.isEmpty()) {
                    continue;
                }
                // 特殊处理 m= 和 a=
                if ("m".equals(key)) {
                    msg.setDestination(value);
                } else if ("a".equals(key)) {
                    msg.setAction(value);
                } else {
                    msg.setParam(key, value);
                }
            } else {
                // 无等号：作为无值参数
                msg.setParam(part.trim(), null);
            }
        }

        // 补充默认模块/动作
        if (msg.getDestination() == null || msg.getDestination().isEmpty()) {
            if (defaultModule != null && !defaultModule.isEmpty()) {
                msg.setDestination(defaultModule);
            } else {
                System.out.println("⚠️ 未指定模块，请使用 m=module 或配置 defaultModule");
                return null;
            }
        }

        if ((msg.getAction() == null || msg.getAction().isEmpty()) &&
                (msg.getMsgId() == null || msg.getMsgId().isEmpty())) {
            if (defaultAction != null && !defaultAction.isEmpty()) {
                msg.setAction(defaultAction);
            } else {
                System.out.println("⚠️ 未指定动作，请使用 a=action 或 msgId=xxx");
                return null;
            }
        }

        return msg;
    }

    /**
     * 校验消息是否允许发送到目标模块
     */
    protected boolean validateMessage(TLMsg msg) {
        String module = msg.getDestination();
        if (module == null || module.isEmpty()) {
            return false;
        }

        HashMap<String, String> moduleConfig = msgToModules.get(module);
        if (moduleConfig == null) {
            System.out.println("❌ 未知模块: " + module);
            return false;
        }

        String action = msg.getAction();
        String msgId = msg.getMsgId();

        // 如果配置了 actions，校验 action 是否在列表中
        String actionsStr = moduleConfig.get("actions");
        if (actionsStr != null && !actionsStr.isEmpty()) {
            if (action == null && msgId == null) {
                System.out.println("❌ 缺少 action 或 msgId");
                return false;
            }
            String[] allowed = actionsStr.split(";");
            boolean found = false;
            for (String a : allowed) {
                if (a.equals(action) || a.equals(msgId)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                System.out.println("❌ 动作 " + (action != null ? action : msgId) +
                        " 不在模块 " + module + " 的允许列表: " + actionsStr);
                return false;
            }
        }

        return true;
    }

    // ======================== 内部配置类 ========================

    protected class myConfig extends TLModuleConfig {
        protected HashMap<String, HashMap<String, String>> msgToModules;
        protected HashMap<String, HashMap<String, String>> cmds;

        public myConfig(String configFile, String configDir) {
            super(configFile, configDir);
        }

        public myConfig() {
        }

        public HashMap<String, HashMap<String, String>> getMsgToModules() {
            return msgToModules;
        }

        public HashMap<String, HashMap<String, String>> getCmds() {
            return cmds;
        }

        @Override
        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if (xpp.getName().equals("msgToModules")) {
                    msgToModules = getHashMap(xpp, "msgToModules", "module");
                }
                if (xpp.getName().equals("cmds")) {
                    cmds = getHashMap(xpp, "cmds", "cmd");
                }
            } catch (Throwable t) {
                putLog("解析配置异常: " + t.toString(), LogLevel.WARN);
            }
        }
    }
}