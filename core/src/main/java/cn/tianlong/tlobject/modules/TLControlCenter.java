package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工厂级控制中心：启动编排、模块注册门面、配置重载治理、审计。
 *
 * 启动序列由本模块配置文件的 &lt;startMsg&gt; 承载，执行时交给工厂的 bootstrapModules
 * 以工厂为发送者逐条创建应用模块（保持 familyName 裸名）；工厂只负责创建，
 * 控制中心负责启动顺序与治理策略。
 */
public class TLControlCenter extends TLBaseModule {

    public static final String PARAM_RELOADABLE = "reloadableModules";
    public static final String PARAM_AUDIT_LIMIT = "auditLimit";
    public static final int DEFAULT_AUDIT_LIMIT = 200;

    /** 内部私有注册表（familyName = 控制中心:moduleRegistry） */
    protected Object registryModule;
    /** 可重载配置的模块白名单，分号分隔；* = 全部，空 = 全部禁止 */
    protected String reloadableModules;
    protected int auditLimit = DEFAULT_AUDIT_LIMIT;
    protected final Deque<Map<String, Object>> auditRecords = new ArrayDeque<>();
    /** 是否显式指定了配置文件（由工厂创建时传入）；用于配置加载失败的 fail-fast */
    protected boolean configRequested;

    public TLControlCenter() {
        super();
    }

    public TLControlCenter(String name) {
        super(name);
    }

    public TLControlCenter(String name, TLObjectFactory moduleFactory) {
        super(name, moduleFactory);
    }

    @Override
    protected Object setConfig() {
        // configure() 先调 setConfig；此时 configFile 还是工厂传入的原始值（缺失时 super 会置 null）
        configRequested = configFile != null && !configFile.isEmpty();
        return super.setConfig();
    }

    @Override
    protected TLBaseModule init() {
        if (params != null) {
            reloadableModules = params.get(PARAM_RELOADABLE);
            String limit = params.get(PARAM_AUDIT_LIMIT);
            if (limit != null && !limit.trim().isEmpty()) {
                try {
                    auditLimit = Integer.parseInt(limit.trim());
                } catch (NumberFormatException e) {
                    auditLimit = DEFAULT_AUDIT_LIMIT;
                    putLog("auditLimit 配置非法，使用默认值 " + DEFAULT_AUDIT_LIMIT + ": " + limit,
                            LogLevel.WARN);
                }
                if (auditLimit <= 0) {
                    auditLimit = DEFAULT_AUDIT_LIMIT;
                    putLog("auditLimit 必须为正数，使用默认值 " + DEFAULT_AUDIT_LIMIT + ": " + limit,
                            LogLevel.WARN);
                }
            }
        }
        // 显式配置了 configfile 却没加载成功：不能静默以空启动序列运行
        if (configRequested && configFile == null) {
            putLog("控制中心配置加载失败（configfile 不可用），拒绝启动", LogLevel.ERROR);
            return null;
        }
        if (configRequested && (startMsgTable == null || startMsgTable.isEmpty())) {
            putLog("控制中心配置缺少 <startMsg>，拒绝以空启动序列运行", LogLevel.ERROR);
            return null;
        }
        registryModule = getMyModule(DEFAULTMODULEREGISTRY);
        if (registryModule == null) {
            putLog("内部注册表创建失败: " + DEFAULTMODULEREGISTRY, LogLevel.ERROR);
            return null;
        }
        return this;
    }

    /**
     * 启动序列不在本地执行：把 <startMsg> 交给工厂，以工厂为发送者逐条执行。
     * 否则工厂会把控制中心当父模块，给应用模块加 "controlCenter:" 家族名前缀，
     * 破坏 aiagent_master 等注册键。
     */
    @Override
    public void runStartMsg() {
        if (startMsgTable == null || startMsgTable.isEmpty()) return;
        if (moduleFactory == null) {
            super.runStartMsg();
            return;
        }
        putMsg(moduleFactory, createMsg()
                .setAction(FACTORY_BOOTSTRAPMODULES)
                .setParam("msgList", startMsgTable));
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case REGISTRY_REGISTER:
            case REGISTRY_UNREGISTER:
            case REGISTRY_LIST:
            case REGISTRY_GET:
                return forwardToRegistry(msg);
            case "reloadModuleConfig":
                return reloadModuleConfig(fromWho, msg);
            case "auditList":
                return auditList(msg);
            default:
                return null;
        }
    }

    /**
     * 注册门面：构造新消息转发，避免原消息的 destination 造成二次路由。
     * 注意：转发参数固定为 key/instance/ownerName；TLModuleRegistry 新增参数时需要同步这里。
     */
    protected TLMsg forwardToRegistry(TLMsg msg) {
        if (!(registryModule instanceof IObject)) {
            return createMsg().setParam(RESULT, false).setParam(ERRORSTR, "内部注册表不可用");
        }
        TLMsg forward = createMsg().setAction(msg.getAction());
        if (msg.containsParam(REGISTRY_P_KEY))
            forward.setParam(REGISTRY_P_KEY, msg.getParam(REGISTRY_P_KEY));
        if (msg.containsParam(INSTANCE))
            forward.setParam(INSTANCE, msg.getParam(INSTANCE));
        if (msg.containsParam(REGISTRY_P_OWNERNAME))
            forward.setParam(REGISTRY_P_OWNERNAME, msg.getParam(REGISTRY_P_OWNERNAME));
        return putMsg((IObject) registryModule, forward);
    }

    /**
     * 重载模块配置：目标存在 + 有配置文件 + 命中白名单才执行。
     * 返回 RESULT / error / message / data（data 含 module、rules、file）。
     */
    protected TLMsg reloadModuleConfig(Object fromWho, TLMsg msg) {
        String name = msg.getStringParam("name", msg.getStringParam("module", ""));
        String userId = msg.getStringParam("userId", "");
        if (name == null || name.isEmpty()) {
            return reloadFail(name, userId, "缺少参数: name");
        }
        TLBaseModule target = resolveReloadTarget(name);
        if (target == null) {
            return reloadFail(name, userId, "模块未运行或不存在: " + name
                    + "（未实例化的模块会在首次创建时读取新配置）");
        }
        if (target.getConfigFile() == null) {
            return reloadFail(name, userId, "模块未加载配置文件: " + name
                    + "（无配置文件可重载；请检查模块声明/配置路径）");
        }
        if (!isReloadAllowed(name, target)) {
            return reloadFail(name, userId, "模块未在可重载白名单中: " + displayName(name, target));
        }
        TLMsg result;
        try {
            result = putMsg(target, createMsg().setAction("reloadConfig"));
        } catch (Throwable t) {
            putLog("reloadModuleConfig: " + name + " 异常: " + t, LogLevel.ERROR);
            return reloadExecFail(name, userId, "模块配置重载异常: " + t);
        }
        if (result == null) {
            String message = "已发送重载请求（模块未返回结果）";
            audit(userId, "reloadModuleConfig", name, true, true, message);
            return createMsg().setParam(RESULT, true)
                    .setParam("message", message)
                    .setParam("data", dataOf(name, null));
        }
        if (!result.parseBoolean(RESULT, true)) {
            Object errObj = result.getParam(ERRORSTR);
            String err = errObj != null ? String.valueOf(errObj) : "未知错误";
            if (err.isEmpty()) err = "未知错误";
            return reloadExecFail(name, userId, "模块配置重载失败: " + err);
        }
        String message = "配置已重载: " + name;
        if (result.containsParam("rules"))
            message += "（rules=" + result.getParam("rules") + "）";
        audit(userId, "reloadModuleConfig", name, true, true, message);
        return createMsg().setParam(RESULT, true)
                .setParam("message", message)
                .setParam("data", dataOf(name, result));
    }

    /** 解析重载目标：registry 精确家族名 → 工厂已存在实例；不隐式创建。 */
    protected TLBaseModule resolveReloadTarget(String name) {
        TLBaseModule registered = findRegistered(name);
        if (registered != null) return registered;
        Object existing = getModuleInFactory(name);
        return existing instanceof TLBaseModule ? (TLBaseModule) existing : null;
    }

    protected TLBaseModule findRegistered(String familyName) {
        if (!(registryModule instanceof IObject)
                || familyName == null || familyName.isEmpty()) return null;
        TLMsg result = putMsg((IObject) registryModule,
                createMsg().setAction(REGISTRY_GET).setParam(REGISTRY_P_KEY, familyName));
        if (result == null) return null;
        Object instance = result.getParam(INSTANCE);
        return instance instanceof TLBaseModule ? (TLBaseModule) instance : null;
    }

    /** 白名单匹配：请求名 / familyName / name 任一命中；* 全放行；空全拒绝。 */
    protected boolean isReloadAllowed(String requestedName, TLBaseModule target) {
        String list = reloadableModules;
        if (list == null || list.trim().isEmpty()) return false;
        if ("*".equals(list.trim())) return true;
        for (String item : list.split(";")) {
            String value = item.trim();
            if (value.isEmpty()) continue;
            if (value.equals(requestedName)) return true;
            if (value.equals(target.getFamilyName())) return true;
            if (value.equals(target.getName())) return true;
        }
        return false;
    }

    protected String displayName(String requestedName, TLBaseModule target) {
        if (target == null) return requestedName;
        String familyName = target.getFamilyName();
        if (familyName == null || familyName.isEmpty() || familyName.equals(requestedName))
            return requestedName;
        return familyName + "（请求名: " + requestedName + "）";
    }

    /** 策略/前置条件未通过：allowed=false、success=false。 */
    protected TLMsg reloadFail(String target, String userId, String error) {
        audit(userId, "reloadModuleConfig", target, false, false, error);
        return createMsg().setParam(RESULT, false).setParam(ERRORSTR, error);
    }

    /** 策略已放行但执行失败：allowed=true、success=false。 */
    protected TLMsg reloadExecFail(String target, String userId, String error) {
        audit(userId, "reloadModuleConfig", target, true, false, error);
        return createMsg().setParam(RESULT, false).setParam(ERRORSTR, error);
    }

    protected Map<String, Object> dataOf(String name, TLMsg result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("module", name);
        if (result != null) {
            if (result.containsParam("rules")) data.put("rules", result.getParam("rules"));
            if (result.containsParam("file")) data.put("file", result.getParam("file"));
        }
        return data;
    }

    protected TLMsg auditList(TLMsg msg) {
        int limit = msg.getIntParam("limit", auditLimit);
        if (limit <= 0) limit = auditLimit;
        List<Map<String, Object>> result = new ArrayList<>();
        synchronized (auditRecords) {
            Iterator<Map<String, Object>> it = auditRecords.descendingIterator();
            while (it.hasNext() && result.size() < limit)
                result.add(new LinkedHashMap<>(it.next()));   // 返回副本，调用方不能改审计历史
        }
        return createMsg().setParam(RESULT, result);
    }

    /**
     * 审计：allowed=策略是否放行，success=执行是否成功；
     * 结构化日志 + 有界内存环形记录。
     */
    protected void audit(String userId, String action, String target,
                         boolean allowed, boolean success, String result) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("time", System.currentTimeMillis());
        record.put("userId", userId == null ? "" : userId);
        record.put("action", action);
        record.put("target", target == null ? "" : target);
        record.put("allowed", allowed);
        record.put("success", success);
        record.put("result", result == null ? "" : result);
        synchronized (auditRecords) {
            auditRecords.addLast(record);
            while (auditRecords.size() > auditLimit) auditRecords.removeFirst();
        }
        putLog("controlCenter " + action + " user=" + userId + " target=" + target
                + " allowed=" + allowed + " success=" + success + " result=" + result,
                LogLevel.INFO, "audit");
    }
}
