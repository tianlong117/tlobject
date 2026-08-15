package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;
import cn.tianlong.tlobject.utils.TLXmlConfigWriter;
import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 每 agent 私有的函数表管理模块（"agent 黑盒"的工具管理内脏）。
 * 代码类共享：所有 TLAiAgent 各持一个私有实例（TLAiAgent.runStartMsg 经 getMyModule 创建，
 * 家族名 = agent:toolManager），函数表数据互不干扰。
 *
 * 职责：function 的注册/注销/热加载/重载/启用开关/描述更新/列表，
 * 以及 LLM 函数定义（toolDefs）的缓存与构建、ToolTask 解析（resolveToolCalls）。
 *
 * 工具归工具模块管理：skill/agent 模块实例是本模块的私有子模块（家族名 =
 * agent:toolManager:tool），由本模块自己的 modules/modulesClass/modulesParams 管理，
 * 实例化走本模块的 getMyModule/getModule/getNewModule，注册表键由模块自身家族名决定。
 * agent 不参与工具模块表管理。
 *
 * 唯一例外是 msgTool：它不是模块，而是一条预定义消息路由，其消息的 destination 语义是
 * "工具模块的父 agent"——默认执行目标即 owner 本身，显式 dest 经 owner 解析。
 *
 * 配置：本模块的 configFile 由工厂注入（模块配置的 configfile 属性，即 owner 的配置文件
 * 路径），创建时框架自动调用 setConfig 用与 TLAiAgent 同款的解析类解析；
 * attachOwner 绑定 owner 后自初始化各工具。外部协议不变：调用方仍向所属 agent 发
 * AGENT_* 消息，由 agent 薄转发（putMsg）到本模块。
 */
public class TLToolManager extends TLBaseModule implements TLAiAgentParamString {

    // ======================== owner 与配置 ========================

    /** 所属 agent（attachOwner 注入）。仅用于 msgTool 的 destination 语义（父 agent）；
     *  未 attach 时所有入口返回错误/空值，防误用 */
    private TLAiAgent owner;

    /** 从 XML &lt;agents&gt; 解析出的子Agent配置（updateAgent 需要全量 map） */
    private HashMap<String, HashMap<String, String>> agentsConfig;

    // ======================== 函数表（原 TLAiAgent 字段整体搬入） ========================

    /** 统一函数表：function名 → {模块, 动作, 类型}，合并 skill/agent/msgTool */
    private final Map<String, FunctionEntry> functions = new ConcurrentHashMap<>();

    /** 函数表读写锁：写操作（register/unregister/reload）独占，读操作（list/getDefs）共享 */
    private final ReentrantReadWriteLock functionsLock = new ReentrantReadWriteLock();

    /** 函数条目：描述一个 LLM 可调用的 function */
    protected static class FunctionEntry {
        public final String name;        // function 名（LLM 可见）
        public final String action;      // 执行动作（SKILL_EXECUTE / AGENT_CHAT / 自定义 / _msgId_）
        public final String type;        // "skill" / "agent" / "msgTool" / "mcp"
        public final TLBaseModule module; // 目标模块
        public final String description;  // LLM 函数描述
        public final Map<String, Object> paramSchema; // LLM 参数 schema
        public final String nativeName;   // MCP 原生工具名（非 MCP 时 == name）
        public boolean enabled = true;
        public long timeoutMs = 0;        // 单次执行超时（毫秒），0 = 不限时

        public FunctionEntry(String name, String action, String type, TLBaseModule module,
                             String description, Map<String, Object> paramSchema) {
            this(name, action, type, module, description, paramSchema, name);
        }
        public FunctionEntry(String name, String action, String type, TLBaseModule module,
                             String description, Map<String, Object> paramSchema, String nativeName) {
            this.name = name;
            this.action = action;
            this.type = type;
            this.module = module;
            this.description = description;
            this.paramSchema = paramSchema;
            this.nativeName = nativeName;
        }
    }

    /** 缓存的 function definitions（不可变列表）；随工具集变更失效重建 */
    private volatile List<TLFunctionDefinition> cachedToolDefs;

    /** 工具定义缓存是否需要重建；初始 true，任何工具集变更置 true */
    private volatile boolean toolDefsDirty = true;

    // ======================== 构造 ========================

    public TLToolManager() { super(); }
    public TLToolManager(String name) { super(name); }
    public TLToolManager(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected TLBaseModule init() {
        return this;
    }

    /**
     * 框架在 getMyModule 创建本模块时自动调用（configFile 由工厂从模块配置的 configfile
     * 属性注入）。与 TLAiAgent 同款：用 myConfig 解析类解析配置文件（owner 的配置文件即
     * 本模块的配置文件），结果放进框架 mconfig。agent 的 msgTable 等段对本模块无副作用
     * （本模块只处理 AGENT_* 工具管理消息，不匹配 agent 的消息路由）。
     */
    @Override
    protected Object setConfig() {
        myConfig config = new myConfig(configFile, moduleFactory.getConfigDir());
        mconfig = config;
        super.setConfig();
        return config;
    }

    /** 绑定所属 agent（TLAiAgent.initToolManager 调用）。
     *  配置解析已在本模块创建时由框架完成（setConfig），此处绑定 owner 引用（供 msgTool 的
     *  父 agent destination 语义使用）并触发工具自初始化。 */
    public void attachOwner(TLAiAgent ownerAgent) {
        this.owner = ownerAgent;
        initFromConfig();
    }

    // ======================== 消息入口（agent 薄转发到此处） ========================

    @SuppressWarnings("unchecked")
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        if (owner == null) {
            return createMsg().setParam(RESULT, false).setParam("error", "toolManager not attached");
        }
        switch (msg.getAction()) {
            case AGENT_HOTLOADSKILL:    return hotLoadSkill(fromWho, msg);
            case AGENT_HOTUNLOADSKILL:  return hotUnloadSkill(fromWho, msg);
            case AGENT_RELOADSKILL:     return reloadFunction(fromWho, msg, "skill");
            case AGENT_REGISTERSKILL:   return registerFunction(fromWho, msg, "skill");
            case AGENT_UNREGISTERSKILL: return unregisterFunction(fromWho, msg, "skill");
            case AGENT_LISTSKILLS:      return listFunctions(fromWho, msg, "skill");
            case AGENT_SETSKILLENABLED: return setSkillEnabled(fromWho, msg);
            case AGENT_UPDATESKILL:     return updateSkill(fromWho, msg);
            case AGENT_UPDATEAGENT:     return updateAgent(fromWho, msg);
            case AGENT_REGISTERAGENT:   return registerFunction(fromWho, msg, "agent");
            case AGENT_UNREGISTERAGENT: return unregisterFunction(fromWho, msg, "agent");
            case AGENT_RELOADAGENT:     return reloadFunction(fromWho, msg, "agent");
            case AGENT_LISTAGENTS:      return listFunctions(fromWho, msg, "agent");
            // 黑盒消息接口之一：agent 索取 LLM 函数定义列表（给 LLM 用）
            case AGENT_GETFUNCTIONDEFS:
                return createMsg().setParam(RESULT, true)
                        .setParam(AI_P_FUNCTIONDEFS, getFunctionDefinitions());
            // 黑盒消息接口之二：agent 把 LLM 返回的 tool_calls 交给本模块，
            // 返回可执行的 ToolTask 列表（agent 转交执行模块）
            case AGENT_RESOLVETOOLCALLS: {
                List<TLToolCall> toolCalls = (List<TLToolCall>) msg.getListParam(AI_P_TOOLCALLS, null);
                List<TLToolExecutor.ToolTask> tasks = toolCalls != null
                        ? resolveToolCalls(toolCalls)
                        : new ArrayList<>();
                return createMsg().setParam(RESULT, true).setParam("tasks", tasks);
            }
            default: return null;
        }
    }

    // ======================== 初始化：配置 → functions 表 ========================

    /**
     * 工具管理内脏自初始化：statup 门控 → factoryShared 分支 → 实例化（经 owner 转发）→
     * registerToRegistry → functions 表。配置段本身就是类型声明——放进 &lt;skills&gt; 的就是 skill，
     * 放进 &lt;agents&gt; 的就是 agent。配置来自框架 mconfig（setConfig 时解析的 owner 配置文件）。
     */
    public void initFromConfig() {
        if (owner == null || !(mconfig instanceof myConfig)) return;
        myConfig config = (myConfig) mconfig;
        HashMap<String, HashMap<String, String>> skillConfigs = config.getSkills();
        HashMap<String, HashMap<String, String>> agentConfigs = config.getAgents();
        List<TLMsg> msgToolList = config.getMsgTools();

        // 工具归工具模块：把解析到的 skills/agents 配置注入本模块自己的 modulesClass/modulesParams，
        // getMyModule 在本模块内创建工具实例（工具是 toolManager 的私有子模块，家族名
        // = agent:toolManager:tool，与父 agent 的模块表无关）
        injectOwnConfigs(skillConfigs, agentConfigs);

        // skills → 实例化 + 注册 + functions
        if (skillConfigs != null) {
            for (String name : skillConfigs.keySet()) {
                HashMap<String, String> cfg = skillConfigs.get(name);
                if (!TLDataUtils.parseBoolean(cfg.get("statup"), true)) continue;
                try {
                    TLBaseModule module = createToolModule(name, cfg);
                    registerToRegistry(name, module, "skill");
                    putLog("skill initialized: " + name, LogLevel.DEBUG);
                    if (module instanceof TLBaseSkill) {
                        TLBaseSkill skill = (TLBaseSkill) module;
                        String fnName = skill.getSkillName();
                        FunctionEntry fe = new FunctionEntry(fnName, SKILL_EXECUTE, "skill", skill,
                                cfg.getOrDefault("skillDescription", fnName), null);
                        try { fe.timeoutMs = Long.parseLong(cfg.getOrDefault("timeout", "0")) * 1000; }
                        catch (NumberFormatException ignored) {}
                        functions.put(fnName, fe);
                    }
                } catch (Exception e) { putLog("postInit skill failed: " + name, LogLevel.WARN); }
            }
        }

        // agents → 实例化 + 注册 + functions
        agentsConfig = agentConfigs;
        if (agentsConfig != null) {
            for (String name : agentsConfig.keySet()) {
                HashMap<String, String> cfg = agentsConfig.get(name);
                if (!TLDataUtils.parseBoolean(cfg.get("statup"), true)) continue;
                try {
                    TLBaseModule module = createToolModule(name, cfg);
                    registerToRegistry(name, module, "agent");
                    putLog("agent initialized: " + name, LogLevel.DEBUG);
                    String desc = cfg.getOrDefault("description", name);
                    try {
                        TLMsg descMsg = putMsg(module, createMsg().setAction(AGENT_GETDESCRIPTION));
                        if (descMsg != null) {
                            String d = descMsg.getStringParam(AI_P_AGENTDESCRIPTION, null);
                            if (d != null && !d.isEmpty()) desc = d;
                        }
                    } catch (Exception ignored) {}
                    FunctionEntry fe = new FunctionEntry(name, SKILL_EXECUTE, "agent", module,
                            desc, buildDelegateParamSchema());
                    try { fe.timeoutMs = Long.parseLong(cfg.getOrDefault("timeout", "0")) * 1000; }
                    catch (NumberFormatException ignored) {}
                    functions.put(name, fe);
                } catch (Exception e) { putLog("postInit agent failed: " + name, LogLevel.WARN); }
            }
        }

        // msgTools → functions（单独处理，语义与 skill/agent 不同）：
        // msgTool 不是模块，是一条预定义消息路由——它的 destination 语义是"工具模块的父 agent"：
        //  - 未配 dest → 执行目标默认就是 owner（父 agent）本身
        //  - 配了 dest → 也经 owner 解析（父 agent 的模块，而非本 registry 的模块）
        // 执行时 ToolExecutor 把消息发给 fn.module（即父 agent 或其子模块），agent 的
        // executeMsgTool 用 fn.name（msgId）经 checkMsgId 按 agent 自己的 msgTable 路由
        if (msgToolList != null) {
            for (TLMsg msgTool : msgToolList) {
                if (!TLDataUtils.parseBoolean(msgTool.getStringParam("statup", null), true)) continue;
                String msgId = msgTool.getMsgId();
                if (msgId == null || msgId.isEmpty()) continue;
                String action = msgTool.getAction();
                String dest = msgTool.getDestination();
                String desc = msgTool.getDescription();
                if (desc == null || desc.isEmpty()) desc = msgId;
                TLBaseModule target = owner;   // 默认目标 = 父 agent
                if (dest != null && !dest.isEmpty()) {
                    try { target = owner.getModuleOwned(dest); } catch (Exception ignored) {}
                }
                Map<String, Object> props = new LinkedHashMap<>();
                String pfa = msgTool.getStringParam("paramsFromArgs", null);
                if (pfa != null && !pfa.isEmpty()) {
                    for (String k : pfa.split(";")) {
                        k = k.trim();
                        if (!k.isEmpty()) props.put(k, Map.of("type", "string", "description", k));
                    }
                }
                Map<String, Object> schema = !props.isEmpty() ? Map.of("type", "object", "properties", props) : null;
                String fnAction = (action != null && !action.isEmpty()) ? action : "msgTool";
                FunctionEntry fe = new FunctionEntry(msgId, fnAction, "msgTool", target, desc, schema);
                try { fe.timeoutMs = Long.parseLong(msgTool.getStringParam("timeout", "0")) * 1000; }
                catch (NumberFormatException ignored) {}
                functions.put(msgId, fe);
            }
        }
    }

    /**
     * 工具模块实例化（原 TLAiAgent.initModules 的 skill/agent 路径）：
     * factoryShared=true 取工厂单例，否则在本模块内创建私有子模块（家族名 = agent:toolManager:tool）。
     * 工具归工具模块管理，不经父 agent。
     */
    private TLBaseModule createToolModule(String moduleName, HashMap<String, String> cfg) {
        boolean factoryShared = "true".equals(cfg.get("factoryShared"));
        return factoryShared ? (TLBaseModule) getModule(moduleName) : (TLBaseModule) getMyModule(moduleName);
    }

    /** 将 skills/agents 配置注入本模块自己的 modulesClass/modulesParams（getMyModule 自动使用） */
    private void injectOwnConfigs(HashMap<String, HashMap<String, String>> skillConfigs,
                                  HashMap<String, HashMap<String, String>> agentConfigs) {
        if (skillConfigs != null) {
            for (String name : skillConfigs.keySet()) {
                modulesClass.putIfAbsent(name, skillConfigs.get(name));
                modulesParams.putIfAbsent(name, skillConfigs.get(name));
            }
        }
        if (agentConfigs != null) {
            for (String name : agentConfigs.keySet()) {
                modulesClass.putIfAbsent(name, agentConfigs.get(name));
                modulesParams.putIfAbsent(name, agentConfigs.get(name));
            }
        }
    }

    /**
     * 向全局 moduleRegistry 注册工具子模块，以模块自身家族名字为 key
     * （agent:toolManager:tool——工具是 toolManager 的私有子模块）。
     * registry 未配置时静默跳过（IGNOREMODULEISNULL）。
     */
    private void registerToRegistry(String subName, Object module, String moduleType) {
        if (module == null) return;
        String familyName = module instanceof TLBaseModule
                ? ((TLBaseModule) module).getFamilyName() : getFamilyName() + ":" + subName;
        TLMsg msg = createMsg().setAction(REGISTRY_REGISTER)
                .setParam(REGISTRY_P_KEY, familyName)
                .setParam(MODULENAME, subName)
                .setParam(INSTANCE, module);
        msg.setSystemParam(IGNOREMODULEISNULL, true);
        putMsg(DEFAULTMODULEREGISTRY, msg);
    }

    // ======================== Skill管理 ========================

    /**
     * 热加载第三方脚本 Skill：指定 skillDir 目录名，自动拼出 TLScriptExecutionSkill 配置并创建。
     */
    protected TLMsg hotLoadSkill(Object fromWho, TLMsg msg) {
        String skillDir = msg.getStringParam("skillDir", null);
        if (skillDir == null || skillDir.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "skillDir required");
        }
        // 目录名即模块名
        String moduleName = skillDir;
        String skillName = msg.getStringParam(AI_P_SKILLNAME, "script_execution");
        String interpreter = msg.getStringParam("interpreter", "python");
        int maxExecutionTime = msg.getIntParam("maxExecutionTime", 120);
        boolean persist = msg.parseBoolean(HOTLOAD_P_PERSIST, true);

        // 构建 skill 配置
        HashMap<String, String> cfg = new HashMap<>();
        cfg.put(MODULE_SameClassAs, "scriptExecutionSkill");
        cfg.put("statup", "true");
        cfg.put(AI_P_SKILLNAME, skillName);
        cfg.put("interpreter", interpreter);
        cfg.put("maxExecutionTime", String.valueOf(maxExecutionTime));
        cfg.put("allowedScriptDir", "skills/" + skillDir + "/scripts");

        // 校验脚本目录是否真实存在（与 TLScriptExecutionSkill.resolveScriptDir 路径解析一致）
        String base = moduleFactory.getConfigDir();
        // 修复 Windows "/D:/..." 问题
        if (base.startsWith("/") && base.length() > 3 && base.charAt(2) == ':')
            base = base.substring(1);
        String resolvedDir = base + "skills/" + skillDir + "/scripts";
        java.nio.file.Path scriptPath = java.nio.file.Paths.get(resolvedDir);
        if (!java.nio.file.Files.isDirectory(scriptPath)) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "脚本目录不存在: " + resolvedDir);
        }

        // 注入本模块自己的 modulesClass/modulesParams（工具归工具模块管理）
        modulesClass.put(moduleName, cfg);
        modulesParams.put(moduleName, new HashMap<>(cfg));

        // 创建为本模块的私有子模块（家族名 = agent:toolManager:moduleName）
        TLBaseModule module = (TLBaseModule) getMyModule(moduleName);
        if (module instanceof TLBaseSkill) {
            TLBaseSkill skill = (TLBaseSkill) module;
            registerToRegistry(moduleName, module, "skill");
            // 与 registerFunction 等路径一致：functions 变更 + 失效标记须在写锁内原子完成
            functionsLock.writeLock().lock();
            try {
                functions.put(skillName, new FunctionEntry(skillName, SKILL_EXECUTE, "skill", module, skillName, null));
                invalidateToolDefs();
            } finally {
                functionsLock.writeLock().unlock();
            }
            putLog("Hot-loaded skill: " + moduleName + " (dir=" + skillDir + ")", LogLevel.INFO, AGENT_HOTLOADSKILL);

            // 持久化（本模块的 configFile 即 owner 的配置文件，由工厂创建时注入）
            if (persist && configFile != null) {
                try {
                    writeSkillToConfig(moduleName, skillName, interpreter, maxExecutionTime, skillDir);
                } catch (Exception e) {
                    putLog("persist skill config failed", LogLevel.ERROR, AGENT_HOTLOADSKILL);
                }
            }
            return createMsg().setParam(RESULT, true).setParam(MODULENAME, moduleName);
        }
        return createMsg().setParam(RESULT, false).setParam("error", "create skill failed: " + moduleName);
    }

    /** 热卸载 skill：从 functions, owner 的 modulesClass/modulesParams 和配置文件中移除 */
    protected TLMsg hotUnloadSkill(Object fromWho, TLMsg msg) {
        String skillDir = msg.getStringParam("skillDir", null);
        String moduleName = msg.getStringParam(MODULENAME, null);
        boolean persist = msg.parseBoolean(HOTLOAD_P_PERSIST, true);

        // moduleName 优先；否则根据 skillDir 在本模块自己的 modulesParams 中搜索匹配的 allowedScriptDir
        if (moduleName == null || moduleName.isEmpty()) {
            if (skillDir == null || skillDir.isEmpty()) {
                return createMsg().setParam(RESULT, false).setParam("error", "skillDir or moduleName required");
            }
            String targetDir = "skills/" + skillDir + "/scripts";
            if (modulesParams != null) {
                for (Map.Entry<String, HashMap<String, String>> e : modulesParams.entrySet()) {
                    if (targetDir.equals(e.getValue().get("allowedScriptDir"))) {
                        moduleName = e.getKey();
                        break;
                    }
                }
            }
            if (moduleName == null) {
                return createMsg().setParam(RESULT, false).setParam("error", "skill not found for dir: " + skillDir);
            }
        }

        // 从 functions 中移除（按模块名查找 skill 类型）——遍历+删除+失效标记须在写锁内原子完成
        functionsLock.writeLock().lock();
        try {
            String foundFnName = null;
            for (FunctionEntry fe : functions.values()) {
                if ("skill".equals(fe.type) && moduleName.equals(fe.module.getName())) {
                    foundFnName = fe.name; break;
                }
            }
            if (foundFnName != null) functions.remove(foundFnName);
            invalidateToolDefs();
        } finally {
            functionsLock.writeLock().unlock();
        }

        // 从本模块的 modules/modulesClass/modulesParams 移除
        modules.remove(moduleName);
        if (modulesClass != null) modulesClass.remove(moduleName);
        if (modulesParams != null) modulesParams.remove(moduleName);

        // 从 registry 注销（本模块家族名前缀，键 = agent:toolManager:moduleName，与注册一致）
        String key = getFamilyName() + ":" + moduleName;
        putMsg(DEFAULTMODULEREGISTRY, createMsg().setAction(REGISTRY_UNREGISTER)
                .setParam(REGISTRY_P_KEY, key));

        putLog("Hot-unloaded skill: " + moduleName, LogLevel.INFO, AGENT_HOTUNLOADSKILL);

        // 从配置文件删除（本模块的 configFile 即 owner 的配置文件）
        if (persist && configFile != null) {
            try {
                removeSkillFromConfig(moduleName);
            } catch (Exception e) {
                putLog("remove skill from config failed", LogLevel.ERROR, AGENT_HOTUNLOADSKILL);
            }
        }

        return createMsg().setParam(RESULT, true).setParam(MODULENAME, moduleName);
    }

    /** 从 XML 配置文件中删除指定模块名的 &lt;skill&gt; 条目（DOM 操作） */
    private void removeSkillFromConfig(String moduleName) throws IOException {
        try {
            TLXmlConfigWriter.removeElement(configFile, "skills", "skill", moduleName);
        } catch (Exception e) {
            throw new IOException("remove from <skills> failed: " + moduleName, e);
        }
    }

    /** 将 skill 配置写入 agent XML（DOM 操作，替换已有同名条目） */
    private void writeSkillToConfig(String moduleName, String skillName, String interpreter,
                                     int maxExecutionTime, String skillDir) throws IOException {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("sameClassAs", "scriptExecutionSkill");
        attrs.put("statup", "true");
        attrs.put("skillName", skillName);
        if (interpreter != null) attrs.put("interpreter", interpreter);
        attrs.put("maxExecutionTime", String.valueOf(maxExecutionTime));
        attrs.put("allowedScriptDir", "skills/" + skillDir + "/scripts");

        try {
            TLXmlConfigWriter.addOrReplaceElement(configFile, "skills", "skill", moduleName, attrs);
        } catch (Exception e) {
            throw new IOException("write to <skills> failed: " + moduleName, e);
        }
    }

    /** 统一注销 function。type="skill"|"agent"，用于读取对应 msg 参数和持久化目标。 */
    protected TLMsg unregisterFunction(Object fromWho, TLMsg msg, String type) {
        functionsLock.writeLock().lock();
        try {
            String name = "skill".equals(type) ? msg.getStringParam(AI_P_SKILLNAME, "")
                    : msg.getStringParam(AI_P_AGENTNAME, "");
            if (name.isEmpty()) return createMsg().setParam(RESULT, false).setParam("error", type + " name required");
            FunctionEntry removedFn = functions.remove(name);
            modules.remove(name);
            if (removedFn != null) {
                String moduleName = removedFn.module.getName();
                if (modulesClass != null) modulesClass.remove(moduleName);
                if (modulesParams != null) modulesParams.remove(moduleName);
                invalidateToolDefs();
                String registryKey = getFamilyName() + ":" + name;
                putMsg(DEFAULTMODULEREGISTRY, createMsg().setAction(REGISTRY_UNREGISTER)
                        .setParam(REGISTRY_P_KEY, registryKey));
                boolean persist = msg.parseBoolean(HOTLOAD_P_PERSIST, true);
                if (persist && configFile != null) {
                    try {
                        TLXmlConfigWriter.removeElement(configFile, type + "s", type, moduleName);
                    } catch (Exception ex) {
                        putLog("remove " + type + " from config failed: " + ex, LogLevel.ERROR);
                    }
                }
            }
            return createMsg().setParam(RESULT, removedFn != null)
                    .setParam("error", removedFn != null ? null : type + " 不存在: " + name);
        } finally {
            functionsLock.writeLock().unlock();
        }
    }

    /** 统一列出 function。type="skill"|"agent" */
    protected TLMsg listFunctions(Object fromWho, TLMsg msg, String type) {
        List<String> names = new ArrayList<>();
        for (FunctionEntry fe : functions.values()) {
            if (!type.equals(fe.type)) continue;
            names.add(fe.name);
        }
        return createMsg().setParam(RESULT, true).setParam(type + "s", names);
    }

    /**
     * 运行时注册 function（INSTANCE 直接注入或按配置创建）。
     */
    protected TLMsg registerFunction(Object fromWho, TLMsg msg, String type) {
        functionsLock.writeLock().lock();
        try {
            // INSTANCE 直接注入模式：已有现成的模块实例，跳过 getMyModule 创建
            Object instance = msg.getParam(INSTANCE);
            if (instance instanceof TLBaseModule) {
                TLBaseModule module = (TLBaseModule) instance;
                String name = "skill".equals(type)
                        ? (module instanceof TLBaseSkill ? ((TLBaseSkill) module).getSkillName() : module.getName())
                        : module.getName();
                if (name == null || name.isEmpty())
                    return createMsg().setParam(RESULT, false).setParam("error", "module name empty");
                modules.put(name, module);
                registerToRegistry(name, module, type);
                String desc = "skill".equals(type) && module instanceof TLBaseSkill
                        ? ((TLBaseSkill) module).getSkillDescription() : module.getName();
                functions.put(name, new FunctionEntry(name, SKILL_EXECUTE, type, module,
                        desc, "agent".equals(type) ? buildDelegateParamSchema() : null));
                invalidateToolDefs();
                return createMsg().setParam(RESULT, true).setParam("name", name);
            }

            String name = "skill".equals(type) ? msg.getStringParam(AI_P_SKILLNAME, "")
                    : msg.getStringParam(AI_P_AGENTNAME, "");
            if (name.isEmpty()) return createMsg().setParam(RESULT, false).setParam("error", type + " name required");
            HashMap<String, String> cfg = new HashMap<>(msg.getMapParam(AI_P_AGENTCONFIG,
                    msg.getMapParam(MODULE_PARAMS, new HashMap<>())));
            cfg.putIfAbsent(MODULE_CLASSFILE, msg.getStringParam(MODULE_CLASSFILE, ""));
            // 注入本模块自己的 modulesClass/modulesParams，getMyModule 才能找到 class/config
            modulesClass.put(name, cfg);
            modulesParams.put(name, cfg);
            TLBaseModule module = (TLBaseModule) getMyModule(name);
            if (module == null) return createMsg().setParam(RESULT, false).setParam("error", "create failed: " + name);
            registerToRegistry(name, module, type);
            functions.put(name, new FunctionEntry(name, SKILL_EXECUTE, type, module,
                    cfg.getOrDefault("description", name), "agent".equals(type) ? buildDelegateParamSchema() : null));
            invalidateToolDefs();
            boolean persist = msg.parseBoolean(HOTLOAD_P_PERSIST, true);
            if (persist && configFile != null) {
                Map<String, String> attrs = new LinkedHashMap<>(cfg);
                TLXmlConfigWriter.addOrReplaceElement(configFile, type + "s", type, name, attrs);
            }
            return createMsg().setParam(RESULT, true).setParam("name", name);
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false).setParam("error", "register failed: " + e);
        } finally {
            functionsLock.writeLock().unlock();
        }
    }

    /** 统一重载 function。type="skill"|"agent" */
    protected TLMsg reloadFunction(Object fromWho, TLMsg msg, String type) {
        functionsLock.writeLock().lock();
        try {
            String name = "skill".equals(type) ? msg.getStringParam(AI_P_SKILLNAME, "")
                    : msg.getStringParam(AI_P_AGENTNAME, "");
            // 脚本 skill（/reload -s）传 skillDir：目录名即模块名，从 functions 反查函数名
            if (name.isEmpty() && "skill".equals(type)) {
                String skillDir = msg.getStringParam("skillDir", "");
                if (!skillDir.isEmpty()) {
                    for (Map.Entry<String, FunctionEntry> e : functions.entrySet()) {
                        FunctionEntry fe = e.getValue();
                        if ("skill".equals(fe.type) && fe.module instanceof TLBaseModule
                                && skillDir.equals(((TLBaseModule) fe.module).getName())) {
                            name = e.getKey();
                            break;
                        }
                    }
                    if (name.isEmpty())
                        return createMsg().setParam(RESULT, false)
                                .setParam("error", "skill not found by skillDir: " + skillDir);
                }
            }
            if (name.isEmpty()) return createMsg().setParam(RESULT, false).setParam("error", type + " name required");
            if (!functions.containsKey(name))
                return createMsg().setParam(RESULT, false).setParam("error", type + " not found: " + name);
            FunctionEntry old = functions.get(name);
            // 模块名可能与函数名不同（如 fileOperationSkill 的函数名是 file_operation），
            // 以旧实例的实际模块名为准重建；脚本 skill 的模块名即 skillDir（目录名）
            String moduleName = (old.module instanceof TLBaseModule)
                    ? ((TLBaseModule) old.module).getName() : name;
            TLBaseModule newModule = (TLBaseModule) getNewModule(moduleName);
            if (newModule == null) return createMsg().setParam(RESULT, false).setParam("error", "reload failed: " + moduleName);
            modules.put(moduleName, newModule);
            functions.put(name, new FunctionEntry(name, old.action, old.type, newModule, old.description, old.paramSchema));
            registerToRegistry(name, newModule, type);
            invalidateToolDefs();
            return createMsg().setParam(RESULT, true).setParam("name", name);
        } finally {
            functionsLock.writeLock().unlock();
        }
    }

    protected TLMsg setSkillEnabled(Object fromWho, TLMsg msg) {
        functionsLock.writeLock().lock();
        try {
            String skillName = msg.getStringParam(AI_P_SKILLNAME, "");
            FunctionEntry fe = skillName.isEmpty() ? null : functions.get(skillName);
            TLBaseSkill skill = (fe != null && "skill".equals(fe.type)) ? (TLBaseSkill) fe.module : null;
            if (skill == null) {
                return createMsg().setParam(RESULT, false).setParam("error", "skill not found: " + skillName);
            }
            boolean enabled = msg.parseBoolean(AI_P_ENABLED, true);
            skill.setEnabled(enabled);
            fe.enabled = enabled;
            invalidateToolDefs();
            return createMsg().setParam(RESULT, true)
                    .setParam(AI_P_SKILLNAME, skillName).setParam(AI_P_ENABLED, enabled);
        } finally {
            functionsLock.writeLock().unlock();
        }
    }

    /**
     * 更新已注册 skill 的 tool 定义（描述 / 参数 schema）。只改 tool 定义层，
     * 不涉及业务运行参数（需改业务参数走 unregisterSkill + registerSkill 重建）。
     * 传入哪个改哪个；有改动则失效工具定义缓存。
     */
    @SuppressWarnings("unchecked")
    protected TLMsg updateSkill(Object fromWho, TLMsg msg) {
        functionsLock.writeLock().lock();
        try {
            String skillName = msg.getStringParam(AI_P_SKILLNAME, "");
            FunctionEntry fe = skillName.isEmpty() ? null : functions.get(skillName);
            TLBaseSkill skill = (fe != null && "skill".equals(fe.type)) ? (TLBaseSkill) fe.module : null;
            if (skill == null) {
                return createMsg().setParam(RESULT, false).setParam("error", "skill not found: " + skillName);
            }
            boolean changed = false;
            if (msg.containsParam(AI_P_SKILLDESCRIPTION)) {
                skill.setSkillDescription(msg.getStringParam(AI_P_SKILLDESCRIPTION, ""));
                changed = true;
            }
            if (msg.containsParam(AI_P_SKILLPARAMS)) {
                skill.setParameterSchema((Map<String, Object>) msg.getMapParam(AI_P_SKILLPARAMS, new LinkedHashMap<>()));
                changed = true;
            }
            if (changed) invalidateToolDefs();
            return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLNAME, skillName);
        } finally {
            functionsLock.writeLock().unlock();
        }
    }

    /**
     * 更新已注册子 Agent 的描述。改后失效工具定义缓存。
     */
    protected TLMsg updateAgent(Object fromWho, TLMsg msg) {
        functionsLock.writeLock().lock();
        try {
            String agentName = msg.getStringParam(AI_P_AGENTNAME, "");
            if (agentName.isEmpty() || agentsConfig == null || !agentsConfig.containsKey(agentName)) {
                return createMsg().setParam(RESULT, false).setParam("error", "agent not found: " + agentName);
            }
            String description = msg.getStringParam(AI_P_AGENTDESCRIPTION, "");
            agentsConfig.get(agentName).put("description", description);
            invalidateToolDefs();
            return createMsg().setParam(RESULT, true).setParam(AI_P_AGENTNAME, agentName);
        } finally {
            functionsLock.writeLock().unlock();
        }
    }

    // ======================== 工具定义缓存 ========================

    /** 标记工具定义缓存失效，下次 getFunctionDefinitions 会重建。 */
    private void invalidateToolDefs() { toolDefsDirty = true; }

    /**
     * 对外入口（chat 循环调用）：返回缓存的 function definitions。
     * 首次调用或工具集变更（toolDefsDirty）后重建，否则直接返回缓存的不可变列表。
     * 双检锁与所有失效方法（register/unregister/update skill/agent）使用 functionsLock 协调。
     */
    public List<TLFunctionDefinition> getFunctionDefinitions() {
        if (owner == null) return Collections.emptyList();
        List<TLFunctionDefinition> local = cachedToolDefs;
        if (!toolDefsDirty && local != null) return local;
        functionsLock.writeLock().lock();
        try {
            if (toolDefsDirty || cachedToolDefs == null) {
                cachedToolDefs = Collections.unmodifiableList(rebuildFunctionDefinitions());
                toolDefsDirty = false;
            }
            return cachedToolDefs;
        } finally {
            functionsLock.writeLock().unlock();
        }
    }

    /**
     * 从已注册functions构建function definitions列表。
     * agent 类型会先探 AGENT_GETTOOLDEFS——MCP agent 可贡献多个工具定义。
     */
    @SuppressWarnings("unchecked")
    private List<TLFunctionDefinition> rebuildFunctionDefinitions() {
        List<TLFunctionDefinition> defs = new ArrayList<>();

        // 内建工具 request_clarification
        defs.add(TLFunctionDefinition.fromSkill(
                AGENT_REQUESTCLARITY,
                "当缺少必要信息无法完成任务时调用此工具请求用户确认。不要猜测或编造信息。",
                Map.of("question", Map.of("type", "string", "description", "需要用户确认的具体问题"))));

        // 已处理过的 function name（避免 MCP 贡献的工具和自身同名冲突）
        Set<String> added = new HashSet<>();
        added.add(AGENT_REQUESTCLARITY);

        // 每次重建前清除旧的 MCP 条目（由 agent 实时贡献，可能会变化）
        functions.entrySet().removeIf(e -> "mcp".equals(e.getValue().type));

        for (FunctionEntry fe : new ArrayList<>(functions.values())) {
            if (!fe.enabled) continue;

            // agent 类型先探 AGENT_GETTOOLDEFS——MCP agent 贡献多个工具
            if ("agent".equals(fe.type) && fe.module != null) {
                try {
                    TLMsg defsMsg = putMsg(fe.module, createMsg().setAction(AGENT_GETTOOLDEFS));
                    if (defsMsg != null) {
                        List<TLFunctionDefinition> contributed =
                                (List<TLFunctionDefinition>) defsMsg.getListParam(AI_P_FUNCTIONDEFS, null);
                        Map<String, String> routes =
                                (Map<String, String>) defsMsg.getMapParam(AI_P_TOOLROUTES, null);
                        if (contributed != null) {
                            for (TLFunctionDefinition toolDef : contributed) {
                                String toolName = toolDef.getName();
                                if (added.contains(toolName)) continue;
                                added.add(toolName);
                                defs.add(toolDef);
                                // MCP 工具注册到 functions：LLM 名=toolName，原生名=nativeTool
                                String nativeTool = routes != null ? routes.get(toolName) : toolName;
                                functions.put(toolName, new FunctionEntry(toolName, MCP_CALLTOOL, "mcp",
                                        fe.module, toolDef.getDescription() != null ? toolDef.getDescription() : toolName,
                                        null, nativeTool != null ? nativeTool : toolName));
                            }
                        }
                    }
                } catch (Exception e) {
                    putLog("AGENT_GETTOOLDEFS failed for " + fe.name + ": " + e, LogLevel.DEBUG);
                }
            }

            // agent 自身也作为一个 function（单任务委托）
            if (!added.contains(fe.name)) {
                added.add(fe.name);
                TLFunctionDefinition def;
                if (fe.paramSchema != null) {
                    def = TLFunctionDefinition.fromSkill(fe.name, fe.description, fe.paramSchema);
                } else if (fe.module instanceof TLBaseSkill) {
                    try { def = ((TLBaseSkill) fe.module).buildFunctionDefinition(); }
                    catch (Exception e) { def = TLFunctionDefinition.fromSkill(fe.name, fe.description, null); }
                } else {
                    def = TLFunctionDefinition.fromSkill(fe.name, fe.description, fe.paramSchema);
                }
                defs.add(def);
            }
        }

        return defs;
    }

    /**
     * 构建委托子Agent的参数schema（task描述）
     */
    protected Map<String, Object> buildDelegateParamSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("task", Map.of("type", "string", "description", "要交给专业Agent处理的任务描述"));
        return schema;
    }

    // ======================== ToolTask 解析（chat 循环 → ToolExecutor 的桥） ========================

    /**
     * 将 LLM 返回的 tool_calls 解析为 ToolTask 列表（查 functions 表 + 校验 + 路由）。
     * request_clarification 在此处理（不入 ToolExecutor），通过返回结果中的 clarified 标记告知 doChat。
     */
    @SuppressWarnings("unchecked")
    private List<TLToolExecutor.ToolTask> resolveToolCalls(List<TLToolCall> toolCalls) {
        if (owner == null) return new ArrayList<>();
        List<TLToolExecutor.ToolTask> tasks = new ArrayList<>();
        for (TLToolCall tc : toolCalls) {
            String functionName = tc.getFunctionName();

            // request_clarification 内建工具：预计算输出，ToolExecutor 直接返回
            if (AGENT_REQUESTCLARITY.equals(functionName)) {
                String question = "";
                if (tc.getArguments() instanceof Map) {
                    Object q = ((Map<?, ?>) tc.getArguments()).get("question");
                    if (q != null) question = q.toString();
                }
                putLog(">>> [Clarify] Agent 请求确认: " + question, LogLevel.INFO);
                TLToolExecutor.ToolTask clarifyTask = new TLToolExecutor.ToolTask(
                        tc.getId(), null, AGENT_REQUESTCLARITY, new LinkedHashMap<>());
                clarifyTask.precomputedOutput = "⚠️ 需要确认: " + question;
                tasks.add(clarifyTask);
                continue;
            }

            FunctionEntry fn = functions.get(functionName);
            if (fn == null || !fn.enabled) {
                putLog("Function not found: " + functionName, LogLevel.WARN);
                TLToolExecutor.ToolTask errTask = new TLToolExecutor.ToolTask(
                        tc.getId(), null, SKILL_EXECUTE, new LinkedHashMap<>());
                errTask.precomputedOutput = "Error: Function not found: " + functionName;
                tasks.add(errTask);
                continue;
            }

            Map<String, Object> toolArgs = tc.getArguments() instanceof Map
                    ? new LinkedHashMap<>((Map<String, Object>) tc.getArguments())
                    : new LinkedHashMap<>();

            // skill 类型：执行前校验参数
            if ("skill".equals(fn.type)) {
                try {
                    TLMsg vResult = putMsg(fn.module, createMsg().setAction(SKILL_VALIDATE)
                            .setParam(AI_P_SKILLINPUT, toolArgs));
                    if (vResult != null && !vResult.parseBoolean(RESULT, false)) {
                        putLog("Skill validation failed: " + functionName, LogLevel.WARN);
                        TLToolExecutor.ToolTask valFailTask = new TLToolExecutor.ToolTask(
                                tc.getId(), null, SKILL_EXECUTE, new LinkedHashMap<>());
                        valFailTask.precomputedOutput = "Validation error: "
                                + vResult.getStringParam("error", "unknown");
                        tasks.add(valFailTask);
                        continue;
                    }
                } catch (Exception e) {
                    putLog("Skill validation error: " + functionName + " " + e, LogLevel.WARN);
                }
            }

            // 解析 action：msgTool / MCP / 普通
            String action;
            if (MCP_CALLTOOL.equals(fn.action)) {
                action = MCP_CALLTOOL;
            } else {
                action = fn.action;
            }

            TLToolExecutor.ToolTask task = new TLToolExecutor.ToolTask(
                    tc.getId(), fn.module, action, toolArgs);
            task.timeoutMs = fn.timeoutMs;
            // 审批规则按 LLM 函数名匹配（如 file_operation:delete），模块名是部署实现细节
            task.functionName = functionName;
            if (MCP_CALLTOOL.equals(action)) {
                task.nativeName = fn.nativeName;
            }
            // msgTool: 用 fn.name (msgId) 覆写 moduleName，使 doToolExec 的 AI_P_TOOLNAME 传出正确的 msgId
            if ("msgTool".equals(fn.type)) {
                task.moduleName = fn.name;
            }
            tasks.add(task);
        }
        return tasks;
    }

    // ======================== 内部配置解析类 ========================

    /**
     * 解析 AI Agent 自定义 XML 配置段：providers, skills, memoryStores, agents, msgTools。
     * 与 TLAiAgent.myConfig 同款（owner 的配置文件即本模块的配置文件）；本模块只用
     * skills/agents/msgTools 三段。
     */
    protected class myConfig extends TLModuleConfig {
        protected HashMap<String, HashMap<String, String>> providers;
        protected HashMap<String, HashMap<String, String>> skills;
        protected HashMap<String, HashMap<String, String>> memoryStores;
        protected HashMap<String, HashMap<String, String>> agents;
        protected ArrayList<TLMsg> msgTools;

        public myConfig() {}

        public myConfig(String configFile, String configDir) {
            super(configFile, configDir);
        }

        public HashMap<String, HashMap<String, String>> getProviders() { return providers; }
        public HashMap<String, HashMap<String, String>> getSkills() { return skills; }
        public HashMap<String, HashMap<String, String>> getMemoryStores() { return memoryStores; }
        public HashMap<String, HashMap<String, String>> getAgents() { return agents; }
        public ArrayList<TLMsg> getMsgTools() { return msgTools; }

        @Override
        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if (xpp.getName().equals("providers")) {
                    providers = getHashMap(xpp, "providers", "provider");
                }
                if (xpp.getName().equals("skills")) {
                    skills = getHashMap(xpp, "skills", "skill");
                }
                if (xpp.getName().equals("memoryStores")) {
                    memoryStores = getHashMap(xpp, "memoryStores", "memoryStore");
                }
                if (xpp.getName().equals("agents")) {
                    agents = getHashMap(xpp, "agents", "agent");
                }
                if (xpp.getName().equals("msgTools")) {
                    msgTools = getMsgList(xpp, "msgTools");
                }
            } catch (Throwable t) {
                putLog("TLToolManager config parse error: " + t.toString(), LogLevel.WARN);
            }
        }
    }
}
