package cn.tianlong.tlobject.uniagent;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;

import java.util.*;

/**
 * 工具工厂 — 统一管理所有工具模块的生命周期。
 *
 * 核心理念：工具 = 模块，工具名 = 模块名 = putMsg 的 destination。
 * 不区分 skill/agent/msgtool/mcp，只管「生」和「养」，不管「用」。
 *
 * XML 配置格式:
 * <pre>{@code
 * <tools>
 *     <tool name="httpRequestSkill" enabled="true" statup="true" />
 *     <tool name="fileAgent" enabled="true" statup="true" />
 * </tools>
 * }</pre>
 */
public class ToolFactory extends TLBaseModule implements UniAgentParamString {

    /** 工具注册表: toolName → ToolEntry */
    private final Map<String, ToolEntry> tools = new LinkedHashMap<>();

    /** 工具定义缓存 */
    private volatile List<TLFunctionDefinition> cachedToolDefs;
    private volatile boolean toolDefsDirty = true;

    // ======================== 内部类 ========================

    /** 工具条目 */
    static class ToolEntry {
        final String name;       // 工具名 = 模块名
        boolean enabled;         // 是否启用
        TLBaseModule module;     // 模块引用（延迟创建）

        ToolEntry(String name, boolean enabled) {
            this.name = name;
            this.enabled = enabled;
        }
    }

    /** 自定义XML配置解析：增加 <tools> 段 */
    protected class ToolConfig extends TLModuleConfig {
        protected HashMap<String, HashMap<String, String>> toolsConfig;

        public ToolConfig() {}
        public ToolConfig(String configFile, String configDir) {
            super(configFile, configDir);
        }

        public HashMap<String, HashMap<String, String>> getToolsConfig() { return toolsConfig; }

        @Override
        protected void myConfig(org.xmlpull.v1.XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if ("tools".equals(xpp.getName())) {
                    toolsConfig = getHashMap(xpp, "tools", "tool");
                }
            } catch (Throwable t) {
                putLog("ToolFactory config parse error: " + t.toString(), LogLevel.WARN);
            }
        }
    }

    // ======================== 生命周期 ========================

    @Override
    protected TLBaseModule init() {
        return this;
    }

    @Override
    public void runStartMsg() {
        super.runStartMsg();
        // 延迟解析 <tools> 配置（此时 moduleFactory 已就绪）
        parseToolsConfig();
        initTools();
    }

    /** 解析 <tools> 配置段（在 runStartMsg 中调用，此时 moduleFactory 已设置） */
    private void parseToolsConfig() {
        if (mconfig instanceof ToolConfig) return; // 已解析过
        if (configFile == null) return;

        String configDir = moduleFactory != null ? moduleFactory.getConfigDir() : null;
        ToolConfig tc = new ToolConfig(configFile, configDir);
        tc.setFactory(moduleFactory);
        tc = (ToolConfig) tc.parse(configFile);
        if (tc != null) {
            mconfig = tc;
        }
    }

    /** 从 XML 配置初始化所有工具模块 */
    protected void initTools() {
        if (!(mconfig instanceof ToolConfig)) return;
        ToolConfig config = (ToolConfig) mconfig;
        HashMap<String, HashMap<String, String>> toolsConfig = config.getToolsConfig();
        if (toolsConfig == null || toolsConfig.isEmpty()) return;

        for (String toolName : toolsConfig.keySet()) {
            HashMap<String, String> toolParams = toolsConfig.get(toolName);
            boolean startup = TLDataUtils.parseBoolean(toolParams.get("statup"), true);
            if (!startup) continue;

            boolean enabled = TLDataUtils.parseBoolean(toolParams.get("enabled"), true);
            try {
                ToolEntry entry = new ToolEntry(toolName, enabled);
                // 工具名 = 模块名，走框架 getMyModule 创建
                entry.module = (TLBaseModule) getMyModule(toolName);
                tools.put(toolName, entry);
                registerToRegistry(toolName, entry.module, "tool");
                putLog("Tool registered: " + toolName + " enabled=" + enabled, LogLevel.INFO);
            } catch (Exception e) {
                putLog("Failed to init tool: " + toolName + " error: " + e.toString(), LogLevel.ERROR);
            }
        }
        invalidateToolDefs();
    }

    // ======================== 消息分发 ========================

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        String action = msg.getAction();
        if (action == null) return null;

        switch (action) {
            case TOOL_REGISTER:    return registerTool(fromWho, msg);
            case TOOL_UNREGISTER:  return unregisterTool(fromWho, msg);
            case TOOL_LIST:        return listTools(fromWho, msg);
            case TOOL_SETENABLED:  return setToolEnabled(fromWho, msg);
            case TOOL_RELOAD:      return reloadTool(fromWho, msg);
            case AGENT_GETTOOLDEFS: return getToolDefs(fromWho, msg);
            default: break;
        }
        return null;
    }

    // ======================== 工具管理 ========================

    /** 注册工具模块 */
    protected TLMsg registerTool(Object fromWho, TLMsg msg) {
        String toolName = (String) msg.getParam(AI_P_TOOLNAME_PARAM);
        if (toolName == null || toolName.isEmpty()) {
            return createMsg().setParam(AI_P_RESPONSE, "Missing toolName");
        }

        if (tools.containsKey(toolName)) {
            return createMsg().setParam(AI_P_RESPONSE, "Tool already exists: " + toolName);
        }

        try {
            boolean enabled = TLDataUtils.parseBoolean((String) msg.getParam(AI_P_ENABLED), true);
            ToolEntry entry = new ToolEntry(toolName, enabled);
            entry.module = (TLBaseModule) getMyModule(toolName);
            tools.put(toolName, entry);
            registerToRegistry(toolName, entry.module, "tool");
            invalidateToolDefs();
            putLog("Tool registered: " + toolName, LogLevel.INFO);
            return createMsg().setParam(AI_P_RESPONSE, "Tool registered: " + toolName);
        } catch (Exception e) {
            putLog("Failed to register tool: " + toolName + " error: " + e.toString(), LogLevel.ERROR);
            return createMsg().setParam(AI_P_RESPONSE, "Failed: " + e.toString());
        }
    }

    /** 注销工具模块 */
    protected TLMsg unregisterTool(Object fromWho, TLMsg msg) {
        String toolName = (String) msg.getParam(AI_P_TOOLNAME_PARAM);
        if (toolName == null || toolName.isEmpty()) {
            return createMsg().setParam(AI_P_RESPONSE, "Missing toolName");
        }

        ToolEntry entry = tools.remove(toolName);
        if (entry == null) {
            return createMsg().setParam(AI_P_RESPONSE, "Tool not found: " + toolName);
        }

        // 从 registry 注销
        unregisterFromRegistry(toolName);
        invalidateToolDefs();
        putLog("Tool unregistered: " + toolName, LogLevel.INFO);
        return createMsg().setParam(AI_P_RESPONSE, "Tool unregistered: " + toolName);
    }

    /** 列出所有工具 */
    protected TLMsg listTools(Object fromWho, TLMsg msg) {
        List<String> toolNames = new ArrayList<>();
        for (ToolEntry entry : tools.values()) {
            toolNames.add(entry.name + (entry.enabled ? "" : " [disabled]"));
        }
        return createMsg().setParam(AI_P_RESPONSE, String.join(", ", toolNames));
    }

    /** 启用/禁用工具 */
    protected TLMsg setToolEnabled(Object fromWho, TLMsg msg) {
        String toolName = (String) msg.getParam(AI_P_TOOLNAME_PARAM);
        boolean enabled = TLDataUtils.parseBoolean((String) msg.getParam(AI_P_ENABLED), true);

        ToolEntry entry = tools.get(toolName);
        if (entry == null) {
            return createMsg().setParam(AI_P_RESPONSE, "Tool not found: " + toolName);
        }

        entry.enabled = enabled;
        invalidateToolDefs();
        putLog("Tool " + toolName + " enabled=" + enabled, LogLevel.INFO);
        return createMsg()                .setParam(AI_P_RESPONSE, "Tool " + toolName + " enabled=" + enabled);
    }

    /** 重载工具模块（重新构建） */
    protected TLMsg reloadTool(Object fromWho, TLMsg msg) {
        String toolName = (String) msg.getParam(AI_P_TOOLNAME_PARAM);
        if (toolName == null || toolName.isEmpty()) {
            return createMsg().setParam(AI_P_RESPONSE, "Missing toolName");
        }

        ToolEntry oldEntry = tools.get(toolName);
        if (oldEntry == null) {
            return createMsg().setParam(AI_P_RESPONSE, "Tool not found: " + toolName);
        }

        try {
            // 重建模块
            TLBaseModule newModule = (TLBaseModule) getNewModule(toolName,
                    oldEntry.module.getClass().getName(), null);
            oldEntry.module = newModule;
            registerToRegistry(toolName, newModule, "tool");
            invalidateToolDefs();
            putLog("Tool reloaded: " + toolName, LogLevel.INFO);
            return createMsg().setParam(AI_P_RESPONSE, "Tool reloaded: " + toolName);
        } catch (Exception e) {
            putLog("Failed to reload tool: " + toolName + " error: " + e.toString(), LogLevel.ERROR);
            return createMsg().setParam(AI_P_RESPONSE, "Failed: " + e.toString());
        }
    }

    // ======================== 工具定义 ========================

    /** 获取所有已启用工具的定义列表（给LLM的function definitions） */
    protected TLMsg getToolDefs(Object fromWho, TLMsg msg) {
        List<TLFunctionDefinition> defs = getFunctionDefinitions();
        return createMsg().setParam(AI_P_FUNCTIONDEFS, defs);
    }

    /** 构建所有已启用工具的函数定义（带缓存） */
    public List<TLFunctionDefinition> getFunctionDefinitions() {
        if (!toolDefsDirty && cachedToolDefs != null) {
            return cachedToolDefs;
        }
        synchronized (this) {
            if (!toolDefsDirty && cachedToolDefs != null) {
                return cachedToolDefs;
            }
            cachedToolDefs = rebuildFunctionDefinitions();
            toolDefsDirty = false;
            return cachedToolDefs;
        }
    }

    /** 向每个工具模块查询其函数定义 */
    private List<TLFunctionDefinition> rebuildFunctionDefinitions() {
        List<TLFunctionDefinition> defs = new ArrayList<>();
        for (ToolEntry entry : tools.values()) {
            if (!entry.enabled || entry.module == null) continue;
            try {
                // 向工具模块发消息，获取它的 function definitions
                TLMsg query = createMsg().setAction(AGENT_GETTOOLDEFS);
                TLMsg result = putMsg(entry.module, query);
                if (result != null && result.getParam(AI_P_FUNCTIONDEFS) instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<TLFunctionDefinition> toolDefs = (List<TLFunctionDefinition>) result.getParam(AI_P_FUNCTIONDEFS);
                    if (toolDefs != null) {
                        defs.addAll(toolDefs);
                    }
                }
                // 如果模块不支持 AGENT_GETTOOLDEFS，尝试 getAgentDescription 生成一个基础定义
                else if (result != null) {
                    String desc = null;
                    TLMsg descResult = putMsg(entry.module, createMsg().setAction(AGENT_GETDESCRIPTION));
                    if (descResult != null) {
                        desc = (String) descResult.getParam(AI_P_RESPONSE);
                    }
                    if (desc == null) desc = entry.name;
                    defs.add(TLFunctionDefinition.create(entry.name, desc, new LinkedHashMap<>()));
                }
            } catch (Exception e) {
                putLog("Failed to get tool defs from: " + entry.name + " error: " + e.toString(), LogLevel.WARN);
            }
        }
        return defs;
    }

    /** 使工具定义缓存失效 */
    public void invalidateToolDefs() {
        toolDefsDirty = true;
    }

    // ======================== 查询 ========================

    /** 根据名称获取工具模块 */
    public TLBaseModule getToolModule(String toolName) {
        ToolEntry entry = tools.get(toolName);
        return entry != null ? entry.module : null;
    }

    /** 检查工具是否已启用 */
    public boolean isToolEnabled(String toolName) {
        ToolEntry entry = tools.get(toolName);
        return entry != null && entry.enabled;
    }

    /** 获取所有已启用的工具名称 */
    public Set<String> getEnabledToolNames() {
        Set<String> names = new LinkedHashSet<>();
        for (ToolEntry entry : tools.values()) {
            if (entry.enabled) names.add(entry.name);
        }
        return names;
    }

    // ======================== 辅助 ========================

    /** 注册到全局模块注册表 */
    private void registerToRegistry(String toolName, TLBaseModule module, String type) {
        try {
            TLMsg regMsg = createMsg()
                    .setAction(REGISTRY_REGISTER)
                    .setParam(INSTANCE, module)
                    .setParam("type", type);
            putMsg(DEFAULTMODULEREGISTRY, regMsg);
        } catch (Exception e) {
            putLog("Failed to register to registry: " + toolName, LogLevel.WARN);
        }
    }

    /** 从全局模块注册表注销 */
    private void unregisterFromRegistry(String toolName) {
        try {
            TLMsg unregMsg = createMsg()
                    .setAction(REGISTRY_UNREGISTER)
                    .setParam("moduleName", toolName);
            putMsg(DEFAULTMODULEREGISTRY, unregMsg);
        } catch (Exception e) {
            putLog("Failed to unregister from registry: " + toolName, LogLevel.WARN);
        }
    }
}
