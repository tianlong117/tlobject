package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;
import cn.tianlong.tlobject.utils.TLXmlConfigWriter;
import org.xmlpull.v1.XmlPullParser;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI Agent主控模块——智能体框架的核心编排器。
 * 接收用户输入，通过LLM分析意图，调用Skill执行任务，管理上下文和记忆。
 *
 * 核心职责：
 * 1. 管理LLM Provider、Context、Skill、Memory等子模块
 * 2. 实现chat()主循环：用户输入 -> LLM分析 -> Skill调用 -> 结果返回
 * 3. 支持tool-call多轮迭代（maxToolCallIterations限制）
 * 4. 管理会话生命周期
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public class TLAiAgent extends TLBaseModule implements TLAiAgentParamString, IAgentCapable {

    // ======================== 配置字段 ========================

    /** 默认LLM Provider模块名 */
    protected String defaultLlmProvider;

    /** 默认长期记忆存储模块名 */
    protected String defaultMemoryStore = M_LONGTERMMEMORY;

    /** 当前活跃的Provider实例引用 */
    protected TLLlmProvider llmProvider;

    /** 工具执行模块（私有实例，盲执行解析好的 ToolTask） */
    protected TLToolExecutor toolExecutor;

    /** 上下文模块名 */
    protected String contextModuleName = M_AICONTEXT;

    /** 本Agent描述（XML description 参数 + md frontmatter 合并），master 生成 delegate_to 时读取 */
    protected String agentDescription;

    /** Agent md 文件路径（XML 显式配置 agentMd，未配则自动发现 {configDir}md/{name}.md） */
    protected String agentMdPath;

    /** 最大tool-call迭代次数 */
    protected int maxToolCallIterations = 10;

    /** Skill包名前缀（用于类名解析） */
    protected String defaultSkillPackageName;

    /** Memory包名前缀 */
    protected String defaultMemoryPackageName;

    // ======================== 运行时字段 ========================

    /** 从XML <providers> 解析出的配置 */
    protected HashMap<String, HashMap<String, String>> providersConfig;

    /** 已注册的Memory：storeName → memoryModule */
    protected Map<String, TLBaseMemory> memoryStores;

    /** 统一函数表：function名 → {模块, 动作, 类型}，合并 skill/agent/msgTool。
     *  替代旧 skills / subAgents / msgTools 三个 map */
    protected final Map<String, FunctionEntry> functions = new ConcurrentHashMap<>();

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

    /** 默认模型 */
    protected String defaultModel = "gpt-4o";

    /** 默认temperature */
    protected double defaultTemperature = 0.7;

    /** 默认maxTokens */
    protected int defaultMaxTokens = 4096;

    /** 记忆召回默认条数（contains 粗筛后注入上下文，LLM 自行判断相关性） */
    protected int defaultMemoryTopK = 50;

    // ======================== 推理/思考链 (ReAct) ========================

    /** 推理模式：off | prompt | native | auto */
    protected String reasoningMode = "off";
    /** 推理内容是否暴露给调用方（capture 后仍可控制可见性） */
    protected boolean reasoningVisible = true;
    /** Claude Extended Thinking token 预算（仅 native 模式生效） */
    protected int thinkingBudget = 4000;

    // ======================== Agent管理（主控模式） ========================

    /** 从XML <agents> 解析出的子Agent配置 */
    protected HashMap<String, HashMap<String, String>> agentsConfig;

    /** 缓存的 function definitions（不可变列表）；随工具集变更失效重建 */
    private volatile List<TLFunctionDefinition> cachedToolDefs;

    /** 工具定义缓存是否需要重建；初始 true，任何工具集变更置 true */
    private volatile boolean toolDefsDirty = true;

    /** 会话级 token 累计：sessionId → {prompt, completion, total} */
    private final Map<String, long[]> sessionTokenUsage = new ConcurrentHashMap<>();

    /** 会话级取消标志：sessionId → cancelled；/stop 置 true，chat 循环协作式退出 */
    private final Map<String, java.util.concurrent.atomic.AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    /** 会话级 worker 线程：sessionId → 执行 doChat 的线程；stopChat 用于 interrupt + 定位其派生进程 */
    private final Map<String, Thread> chatThreads = new ConcurrentHashMap<>();

    /** 当前线程正在执行的 doChat 的根会话 ID（ThreadLocal，供 spawn 点透传给子 agent） */
    private final ThreadLocal<String> currentRootSessionId = new ThreadLocal<>();
    /** 当前 chat 的 userId（供 sessionUpdated 等消息传给 SessionManager，实现用户隔离） */
    private final ThreadLocal<String> currentChatUserId = new ThreadLocal<>();

    /** 流式回调转发目标: sessionId → [forwardTarget, forwardAction]（供 onStreamResult 查表转发 chunk/结果给最终调用方） */
    private final Map<String, String[]> streamForwardMap = new ConcurrentHashMap<>();

    /** 会话级 roundId: sessionId → 当前 doChat 的 roundId（供 onStreamResult 使用） */
    private final Map<String, String> sessionRoundIds = new ConcurrentHashMap<>();

    /** 会话级 msgStartIdx: sessionId → 本轮消息起始位置（供 onStreamResult 使用） */
    private final Map<String, Integer> sessionMsgStartIdx = new ConcurrentHashMap<>();

    /** LLM 可调用的预定义消息列表（从 XML msgTools 段解析） */
    protected List<TLMsg> msgTools;

    // ======================== Session 管理 ========================

    /** 会话管理模块名（SessionManager），Agent 通过发消息报告会话状态 */
    protected String sessionManagerName = "sessionManager";

    /** 是否启用会话通知（发 sessionUpdated/chatFinished/chatAborted 给 SessionManager） */
    protected boolean enableCheckpoint = false;


    /** 数据存储基础路径（供 context/memory 等使用） */
    protected String dataBasePath = "./data/";

    /** JSON 序列化（复用 Gson，aiagent 已依赖） */
    protected com.google.gson.Gson gson;

    // ======================== 构造函数 ========================

    public TLAiAgent() {
        super();
    }

    public TLAiAgent(String name) {
        super(name);
    }

    public TLAiAgent(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    // ======================== 生命周期 ========================

    @Override
    protected Object setConfig() {
        myConfig config = new myConfig(configFile, moduleFactory.getConfigDir());
        mconfig = config;
        super.setConfig();
        // super.setConfig() 可能会用 TLModuleConfig 覆盖 mconfig（自动配置），
        // 如果没配 configFile 且 autoConfig 失败，mconfig 会是 null/TLModuleConfig。
        // 这里始终从我们自己的 myConfig 取 providers/msgTools（可能为空，但不会崩）
        providersConfig = config.getProviders();
        msgTools = config.getMsgTools();
        return config;
    }

    /**
     * 将一组配置注入 modulesClass 和 modulesParams，供 getMyModule 自动使用。
     * @param configs  配置 map（name → 属性）
     * @param namespace Agent 命名空间（仅 memoryStores 需要）
     * @param isMemory 是否为 memoryStores（需要注入 namespace）
     */
    private void injectConfigs(HashMap<String, HashMap<String, String>> configs,
                               String namespace, boolean isMemory) {
        if (configs == null) return;
        for (String name : configs.keySet()) {
            HashMap<String, String> cfg = configs.get(name);
            if (isMemory && namespace != null && !namespace.isEmpty()) {
                cfg.putIfAbsent("agentNamespace", namespace);
            }
            modulesClass.putIfAbsent(name, cfg);
            modulesParams.putIfAbsent(name, cfg);
        }
    }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("defaultLlmProvider") != null)
                defaultLlmProvider = params.get("defaultLlmProvider");
            if (params.get("defaultMemoryStore") != null)
                defaultMemoryStore = params.get("defaultMemoryStore");
            if (params.get("contextModuleName") != null)
                contextModuleName = params.get("contextModuleName");
            if (params.get("description") != null)
                agentDescription = params.get("description");
            if (params.get("agentMd") != null)
                agentMdPath = params.get("agentMd");
            if (params.get("maxToolCallIterations") != null) {
                try { maxToolCallIterations = Integer.parseInt(params.get("maxToolCallIterations")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("defaultSkillPackageName") != null)
                defaultSkillPackageName = params.get("defaultSkillPackageName");
            if (params.get("defaultMemoryPackageName") != null)
                defaultMemoryPackageName = params.get("defaultMemoryPackageName");
            if (params.get("defaultModel") != null)
                defaultModel = params.get("defaultModel");
            if (params.get("defaultTemperature") != null) {
                try { defaultTemperature = Double.parseDouble(params.get("defaultTemperature")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("defaultMaxTokens") != null) {
                try { defaultMaxTokens = Integer.parseInt(params.get("defaultMaxTokens")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("defaultMemoryTopK") != null) {
                try { defaultMemoryTopK = Integer.parseInt(params.get("defaultMemoryTopK")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("enableCheckpoint") != null)
                enableCheckpoint = "true".equals(params.get("enableCheckpoint"));
            if (params.get("sessionManagerName") != null)
                sessionManagerName = params.get("sessionManagerName");
            if (params.get("dataBasePath") != null)
                dataBasePath = params.get("dataBasePath");
            else if (params.get("sessionStorePath") != null)
                dataBasePath = params.get("sessionStorePath"); // 兼容旧配置
            // 推理/思考链参数
            if (params.get("reasoningMode") != null)
                reasoningMode = params.get("reasoningMode");
            if (params.get("reasoningVisible") != null)
                reasoningVisible = "true".equals(params.get("reasoningVisible"));
            if (params.get("thinkingBudget") != null) {
                try { thinkingBudget = Integer.parseInt(params.get("thinkingBudget")); }
                catch (NumberFormatException ignored) {}
            }
        }

        // 把 providers/agents/skills/memoryStores 注入 modulesClass + modulesParams
        // 必须放在 setModuleParams() 而非 setConfig()，因为 initProperty() 会覆盖
        // 没有配置文件时（mconfig 非 myConfig 实例）跳过——作为子模块从父级继承 provider 等
        if (mconfig instanceof myConfig) {
            myConfig config = (myConfig) mconfig;
            if (modulesClass == null) modulesClass = new ConcurrentHashMap<>();
            if (modulesParams == null) modulesParams = new ConcurrentHashMap<>();
            String namespace = params != null ? params.get("agentNamespace") : null;
            injectConfigs(config.getProviders(), namespace, false);
            injectConfigs(config.getAgents(), namespace, false);
            injectConfigs(config.getSkills(), namespace, false);
            injectConfigs(config.getMemoryStores(), namespace, true);
        }

        // 自动加载本 Agent 的 md 文件（须在最后：依赖 contextModuleName 和 modulesParams 已就位）
        loadAgentMd();
    }

    /**
     * 自动加载本 Agent 的 md 文件（与 TLBaseSkill.loadSkillMd 同构，每个 agent 只读自己的 md）。
     * 查找顺序：
     * 1. XML 显式配置 agentMd 路径
     * 2. 配置目录下默认 md/ 文件夹：{configDir}md/{agentName}.md
     * 内容映射：frontmatter description → agentDescription（master 生成 delegate_to 用）；
     * 正文 → 注入 contextModuleName 的 defaultSystemMessage（等价于 XML modulesParams 配置）。
     * XML 已配置时 md 内容追加在后面（contains 去重）——XML 描述只是简单说明，详细内容以 md 为主。
     */
    protected void loadAgentMd() {
        String content = null;

        // 1. XML 显式配置
        if (agentMdPath != null && !agentMdPath.isEmpty())
            content = TLMdFileLoader.readFileOrResource(agentMdPath, this.getClass());

        // 2. 配置目录下默认 md/ 文件夹（configDir 已带尾分隔符）
        if (content == null)
            content = TLMdFileLoader.readFileOrResource(
                    moduleFactory.getConfigDir() + "md/" + name + ".md", this.getClass());

        if (content == null || content.trim().isEmpty()) return;

        String fmDescription = TLMdFileLoader.parseFrontmatterDescription(content);
        String body = TLMdFileLoader.parseBody(content);

        // frontmatter description → agentDescription（md 追加到 XML 之后，contains 去重）
        if (fmDescription != null && !fmDescription.isEmpty()) {
            if (agentDescription == null || agentDescription.isEmpty())
                agentDescription = fmDescription;
            else if (!agentDescription.contains(fmDescription))
                agentDescription = agentDescription + "\n" + fmDescription;
        }

        // 正文 → 注入 context 模块的 defaultSystemMessage（context 懒加载，此时改 modulesParams 即生效）
        if (body != null && !body.isEmpty()) {
            HashMap<String, String> ctxParams =
                    modulesParams.computeIfAbsent(contextModuleName, k -> new HashMap<>());
            String existing = ctxParams.get("defaultSystemMessage");
            if (existing == null || existing.isEmpty())
                ctxParams.put("defaultSystemMessage", body);
            else if (!existing.contains(body))
                ctxParams.put("defaultSystemMessage", existing + "\n\n" + body);
            putLog("Agent md loaded: " + name + " (systemMessage " + body.length() + " chars)", LogLevel.DEBUG);
        }
    }

    /** 本 Agent 描述（XML description + md frontmatter 合并），master 经 AGENT_GETDESCRIPTION 消息读取 */
    public String getAgentDescription() {
        return agentDescription;
    }

    @Override
    protected TLBaseModule init() {
        memoryStores = new ConcurrentHashMap<>();
        gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
        return this;
    }

    @Override
    public void runStartMsg() {
        System.out.println("=== [TLAiAgent] runStartMsg name=" + name + " configFile=" + configFile + " ===");

        // 统一初始化所有模块（配置段 = 类型，不走 instanceof 检查）
        if (mconfig instanceof myConfig) {
            myConfig config = (myConfig) mconfig;
            initModules(config.getProviders(), "provider");

            // Provider 必须先就绪 —— skill/memory/agent 可能通过 owner:name 借引用
            resolveLlmProvider();

            initModules(config.getSkills(), "skill");
            initModules(config.getMemoryStores(), "memory");
            initModules(config.getAgents(), "agent");
        }

        // 无配置文件的子 agent（mconfig==null）也需要 resolve
        resolveLlmProvider();

        // 后处理：每类模块独有的逻辑
        postInitFunctions();
        postInitMemories();

        // 私有 context 实例
        initContext();

        // 私有工具执行模块（审批模块名从 Agent params 继承）
        try {
            toolExecutor = (TLToolExecutor) getMyModule("toolExecutor");
            if (params != null && params.containsKey("approvalModule")) {
                toolExecutor.setApprovalModule(params.get("approvalModule"));
            }
        } catch (Exception e) {
            putLog("Failed to init toolExecutor: " + e.toString(), LogLevel.WARN);
        }

        super.runStartMsg();
        registerToRegistry(name, this, "agent");
    }

    /**
     * 统一模块初始化：statup 门控 → factoryShared 路径 → getModule/getMyModule → registerToRegistry。
     * 配置段本身就是类型声明——放进 &lt;skills&gt; 的就是 skill，放进 &lt;providers&gt; 的就是 provider，
     * 不需要 instanceof 判断。
     */
    private void initModules(HashMap<String, HashMap<String, String>> configs, String registryType) {
        if (configs == null) return;
        for (String moduleName : configs.keySet()) {
            HashMap<String, String> cfg = configs.get(moduleName);
            if (!TLDataUtils.parseBoolean(cfg.get("statup"), true)) continue;
            try {
                boolean factoryShared = "true".equals(cfg.get("factoryShared"));
                TLBaseModule module = (TLBaseModule) (factoryShared ? getModule(moduleName) : getMyModule(moduleName));
                registerToRegistry(moduleName, module, registryType);
                putLog(registryType + " initialized: " + moduleName, LogLevel.DEBUG);
            } catch (Exception e) {
                putLog("Failed to init " + registryType + ": " + moduleName + " error: " + e.toString(), LogLevel.ERROR);
            }
        }
    }

    /** 从已创建的 provider 中匹配 defaultLlmProvider，设为 llmProvider */
    protected void resolveLlmProvider() {
        if (defaultLlmProvider == null || defaultLlmProvider.isEmpty()) return;
        try {
            int colon = defaultLlmProvider.indexOf(':');
            if (colon > 0) {
                // owner:name → 借引用
                String ownerName = defaultLlmProvider.substring(0, colon);
                String provName = defaultLlmProvider.substring(colon + 1);
                TLMsg refResult = putMsg(ownerName,
                        createMsg().setAction("getLlmProvider").setParam("provName", provName));
                Object ref = refResult != null ? refResult.getParam("provider") : null;
                if (ref instanceof TLLlmProvider) {
                    llmProvider = (TLLlmProvider) ref;
                    putLog("LLM Provider borrowed: " + defaultLlmProvider, LogLevel.DEBUG);
                    return;
                }
                // borrow 失败 → fallback 用冒号后的名字自建
                putLog("Failed to borrow: " + defaultLlmProvider + ", fallback to create " + provName, LogLevel.WARN);
                TLBaseModule module = (TLBaseModule) getModule(provName);
                if (module instanceof TLLlmProvider) {
                    llmProvider = (TLLlmProvider) module;
                }
                return;
            }
            // 无冒号 → 直接取
            TLBaseModule module = (TLBaseModule) getModule(defaultLlmProvider);
            if (module instanceof TLLlmProvider) {
                llmProvider = (TLLlmProvider) module;
                putLog("LLM Provider resolved: " + defaultLlmProvider, LogLevel.DEBUG);
            } else {
                putLog("Provider not TLLlmProvider: " + defaultLlmProvider, LogLevel.ERROR);
            }
        } catch (Exception e) {
            putLog("Failed to resolve provider: " + e.toString(), LogLevel.ERROR);
        }
    }

    /** 将已创建的 skill 模块放入 skills + functions map。配置段即是类型，信任配置直接转型。 */
    @SuppressWarnings("unchecked")
    /** 统一注册所有 function：skill + agent + msgTool → functions 表。配置段不同，注册逻辑相同。 */
    protected void postInitFunctions() {
        if (!(mconfig instanceof myConfig)) return;
        myConfig config = (myConfig) mconfig;

        // skills → functions
        HashMap<String, HashMap<String, String>> skillConfigs = config.getSkills();
        if (skillConfigs != null) {
            for (String name : skillConfigs.keySet()) {
                if (!TLDataUtils.parseBoolean(skillConfigs.get(name).get("statup"), true)) continue;
                try {
                    TLBaseSkill skill = (TLBaseSkill) getModule(name);
                    if (skill != null) {
                        String fnName = skill.getSkillName();
                        functions.put(fnName, new FunctionEntry(fnName, SKILL_EXECUTE, "skill", skill,
                                skillConfigs.get(name).getOrDefault("skillDescription", fnName), null));
                    }
                } catch (Exception e) { putLog("postInit skill failed: " + name, LogLevel.WARN); }
            }
        }

        // agents → functions
        agentsConfig = config.getAgents();
        if (agentsConfig != null) {
            for (String name : agentsConfig.keySet()) {
                HashMap<String, String> cfg = agentsConfig.get(name);
                if (!TLDataUtils.parseBoolean(cfg.get("statup"), true)) continue;
                try {
                    TLBaseModule module = (TLBaseModule) getModule(name);
                    if (module != null) {
                        String desc = cfg.getOrDefault("description", name);
                        try {
                            TLMsg descMsg = putMsg(module, createMsg().setAction(AGENT_GETDESCRIPTION));
                            if (descMsg != null) {
                                String d = descMsg.getStringParam(AI_P_AGENTDESCRIPTION, null);
                                if (d != null && !d.isEmpty()) desc = d;
                            }
                        } catch (Exception ignored) {}
                        functions.put(name, new FunctionEntry(name, SKILL_EXECUTE, "agent", module,
                                desc, buildDelegateParamSchema()));
                    }
                } catch (Exception e) { putLog("postInit agent failed: " + name, LogLevel.WARN); }
            }
        }

        // msgTools → functions
        if (msgTools != null) {
            for (TLMsg msgTool : msgTools) {
                if (!TLDataUtils.parseBoolean(msgTool.getStringParam("statup", null), true)) continue;
                String msgId = msgTool.getMsgId();
                if (msgId == null || msgId.isEmpty()) continue;
                String action = msgTool.getAction();
                String dest = msgTool.getDestination();
                String desc = msgTool.getDescription();
                if (desc == null || desc.isEmpty()) desc = msgId;
                TLBaseModule target = this;
                if (dest != null && !dest.isEmpty()) {
                    try { target = (TLBaseModule) getModule(dest); } catch (Exception ignored) {}
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
                String fnAction = (action != null && !action.isEmpty()) ? action : "_msgId_";
                functions.put(msgId, new FunctionEntry(msgId, fnAction, "msgTool", target, desc, schema));
            }
        }
    }

    /** 将已创建的 memory 模块放入 memoryStores map，注入 embedding provider。 */
    @SuppressWarnings("unchecked")
    protected void postInitMemories() {
        if (!(mconfig instanceof myConfig)) return;
        myConfig config = (myConfig) mconfig;
        HashMap<String, HashMap<String, String>> memCfgs = config.getMemoryStores();
        if (memCfgs == null) return;
        for (String storeName : memCfgs.keySet()) {
            HashMap<String, String> storeParams = memCfgs.get(storeName);
            if (!TLDataUtils.parseBoolean(storeParams.get("statup"), true)) continue;
            try {
                TLBaseMemory memory = (TLBaseMemory) getModule(storeName);
                if (memory != null) {
                    memoryStores.put(storeName, memory);
                    String embName = storeParams.get(AI_P_EMBEDDINGPROVIDER);
                    if (embName != null && config.getProviders() != null) {
                        HashMap<String, String> embCfg = config.getProviders().get(embName);
                        if (embCfg != null) {
                            TLBaseModule embM = (TLBaseModule) getModule(embName);
                            if (embM instanceof TLLlmProvider)
                                putMsg(memory, createMsg().setAction("setEmbeddingProvider")
                                        .setParam("provider", embM));
                        }
                    }
                }
            } catch (Exception e) { putLog("postInit memory failed: " + storeName, LogLevel.ERROR); }
        }
    }

    /**
     * 初始化私有 context 实例。
     * getMyModule = 新建实例（不注册工厂）+ 存入本地 modules map。
     */
    protected void initContext() {
        try {
            TLBaseModule module = (TLBaseModule) getMyModule(contextModuleName);
            registerToRegistry(contextModuleName, module, "context");
            if (module != null)
                putLog("Private context initialized: " + contextModuleName, LogLevel.DEBUG);
        } catch (Exception e) {
            putLog("Failed to init context: " + contextModuleName + " error: " + e.toString(), LogLevel.WARN);
        }
    }

    // ======================== 消息分发 ========================

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case AGENT_CHAT:
                returnMsg = chat(fromWho, msg);
                break;
            case AGENT_CHATSTREAM:
                returnMsg = chatStream(fromWho, msg);
                break;
            case "chatStreamSync":
                returnMsg = doChat(fromWho, msg, true);
                break;
            case AGENT_HOTLOADSKILL:
                returnMsg = hotLoadSkill(fromWho, msg);
                break;
            case AGENT_HOTUNLOADSKILL:
                returnMsg = hotUnloadSkill(fromWho, msg);
                break;
            case AGENT_RELOADSKILL:
                returnMsg = reloadFunction(fromWho, msg, "skill");
                break;
            case AGENT_REGISTERSKILL:
                returnMsg = registerFunction(fromWho, msg, "skill");
                break;
            case AGENT_UNREGISTERSKILL:
                returnMsg = unregisterFunction(fromWho, msg, "skill");
                break;
            case AGENT_LISTSKILLS:
                returnMsg = listFunctions(fromWho, msg, "skill");
                break;
            case AGENT_GETCONTEXT:
                returnMsg = getAgentContext(fromWho, msg);
                break;
            case AGENT_CLEARCONTEXT:
                returnMsg = clearAgentContext(fromWho, msg);
                break;
            case "loadHistory":
                returnMsg = loadHistory(fromWho, msg);
                break;
            // resumeSession / findLatestSession / findIncompleteCheckpoints / listSessions
            // 已移到 TLSessionManager，通过 agentService 路由
            case AGENT_SAVEMEMORY:
                returnMsg = saveAgentMemory(fromWho, msg);
                break;
            case AGENT_RECALLMEMORY:
                returnMsg = recallAgentMemory(fromWho, msg);
                break;
            case AGENT_SETSYSTEMMSG:
                returnMsg = setSystemMsg(fromWho, msg);
                break;
            case AGENT_SETPROVIDER:
                returnMsg = setLlmProvider(fromWho, msg);
                break;
            case AGENT_CHECKPROVIDER:
                returnMsg = llmProvider != null
                        ? putMsg(llmProvider, msg)
                        : createMsg().setParam(RESULT, false).setParam("error", "No LLM Provider configured");
                break;
            case "getLlmProvider":
                returnMsg = createMsg().setParam(RESULT, llmProvider != null)
                        .setParam("provider", llmProvider);
                break;
            case AGENT_GETTOKENUSAGE:
                returnMsg = getTokenUsage(fromWho, msg);
                break;
            case "getPromptCacheStats":
                returnMsg = getPromptCacheStats(fromWho, msg);
                break;
            case AGENT_SETSKILLENABLED:
                returnMsg = setSkillEnabled(fromWho, msg);
                break;
            case AGENT_UPDATESKILL:
                returnMsg = updateSkill(fromWho, msg);
                break;
            case AGENT_UPDATEAGENT:
                returnMsg = updateAgent(fromWho, msg);
                break;
            case AGENT_REGISTERAGENT:
                returnMsg = registerFunction(fromWho, msg, "agent");
                break;
            case AGENT_UNREGISTERAGENT:
                returnMsg = unregisterFunction(fromWho, msg, "agent");
                break;
            case AGENT_RELOADAGENT:
                returnMsg = reloadFunction(fromWho, msg, "agent");
                break;
            case AGENT_LISTAGENTS:
                returnMsg = listFunctions(fromWho, msg, "agent");
                break;
            case AGENT_GETDESCRIPTION:
                returnMsg = createMsg().setParam(RESULT, true)
                        .setParam(AI_P_AGENTDESCRIPTION, agentDescription != null ? agentDescription : "");
                break;
            case "onStreamResult":
                returnMsg = onStreamResult(fromWho, msg);
                break;
            case SKILL_EXECUTE:
                // agent 作为工具被调用：提取 task → 内部 chat → 结果放入 AI_P_SKILLOUTPUT
                returnMsg = executeAsTool(fromWho, msg);
                break;
            case AGENT_STOPCHAT:
                returnMsg = stopChat(fromWho, msg);
                break;
            default:
                returnMsg = null;
        }
        return returnMsg;
    }

    // ======================== 核心：Chat循环 ========================

    /**
     * 中断指定 session 正在进行的 chat（协作式取消）。
     * 置会话取消标志 + 取消在途 HTTP 请求。可被控制台的 /stop、外部模块调用。
     * 在与 doChat 不同的线程上执行，靠线程安全的 cancelFlags / OkHttp dispatcher 衔接。
     */
    /** agent 作为工具被调用：提取输入 → 跑一次 chat → 结果放入 AI_P_SKILLOUTPUT */
    @SuppressWarnings("unchecked")
    protected TLMsg executeAsTool(Object fromWho, TLMsg msg) {
        Map<String, Object> args = (Map<String, Object>) msg.getParam(AI_P_SKILLINPUT, Map.class);
        String task = null;
        if (args != null && args.containsKey("task")) {
            task = args.get("task") != null ? args.get("task").toString() : "";
        }
        if (task == null || task.isEmpty()) {
            task = msg.getStringParam(AI_P_USERMESSAGE, "");
        }
        TLMsg chatMsg = createMsg().setAction(AGENT_CHAT).setParam(AI_P_USERMESSAGE, task);
        // 转发会话上下文，保持级联停止、用户数据隔离、会话追踪
        if (msg.containsParam(AI_P_SESSIONID))
            chatMsg.setParam(AI_P_SESSIONID, msg.getStringParam(AI_P_SESSIONID, ""));
        if (msg.containsParam("rootSessionId"))
            chatMsg.setParam("rootSessionId", msg.getStringParam("rootSessionId", ""));
        if (msg.containsParam("userId"))
            chatMsg.setParam("userId", msg.getStringParam("userId", ""));
        TLMsg result = chat(fromWho, chatMsg);
        String output = result != null ? result.getStringParam(AI_P_RESPONSE, "") : "";
        return createMsg().setParam(AI_P_SKILLOUTPUT, output);
    }

    protected TLMsg stopChat(Object fromWho, TLMsg msg) {
        String sid = msg.getStringParam(AI_P_SESSIONID, "default");
        boolean cascade = msg.parseBoolean("cascade", false);
        cancelFlags.computeIfAbsent(sid, k -> new java.util.concurrent.atomic.AtomicBoolean()).set(true);
        // 1) 取消在途 LLM HTTP（级联模式跳过：子 agent 共享 provider，误杀会波及其他会话）
        if (!cascade && llmProvider != null) {
            putMsg(llmProvider, createMsg().setAction(LLM_CANCEL));
        }
        // 2) C：杀掉该会话 worker 线程派生的外部进程
        Thread worker = chatThreads.get(sid);
        int killed = TLProcessRegistry.killByThread(worker);
        if (killed > 0) {
            putLog("stopChat killed " + killed + " process(es): sessionId=" + sid, LogLevel.INFO);
        }
        // 3) B：中断 worker 线程
        if (worker != null) {
            worker.interrupt();
        }
        // 4) 取消并行工具线程（委托给 ToolExecutor）
        if (toolExecutor != null) {
            TLMsg cancelMsg = createMsg().setAction(TODOOLECANCEL)
                    .setParam(AI_P_SESSIONID, sid);
            String usr = msg.getStringParam("userId", null);
            if (usr != null) cancelMsg.setParam("userId", usr);
            putMsg(toolExecutor, cancelMsg);
        }
        putLog("stopChat requested: sessionId=" + sid + " cascade=" + cascade, LogLevel.INFO);
        return createMsg().setParam(RESULT, true).setParam(AI_P_SESSIONID, sid);
    }

    /**
     * 完整的chat循环：
     * 1. 获取/构建上下文
     * 2. 收集skill function定义
     * 3. 发送到LLM
     * 4. 如果有tool_calls → 执行skills → 结果送回LLM（循环）
     * 5. 返回最终文本响应
     */
    @SuppressWarnings("unchecked")
    protected TLMsg chat(Object fromWho, TLMsg msg) {
        return doChat(fromWho, msg, false);
    }

    /**
     * 统一chat实现。stream=true时LLM请求走流式(无tool-call循环)，
     * stream=false时走非流式(支持tool-call多轮迭代)。
     * 记忆召回、上下文、长期记忆保存等生命周期处理完全相同。
     */
    @SuppressWarnings("unchecked")
    protected TLMsg doChat(Object fromWho, TLMsg msg, boolean stream) {
        // 最优先检查 Provider 可用性
        if (llmProvider == null) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, "LLM Provider 未就绪，请检查 API Key 和余额后重启");
        }
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        // 根会话 ID：控制台会话标识，在 spawn 子 agent 时透传，供监控模块级联停止
        String rootSid = msg.getStringParam("rootSessionId", sessionId);
        currentRootSessionId.set(rootSid);
        currentChatUserId.set(msg.getStringParam("userId", sessionId));
        String userMessage = msg.getStringParam(AI_P_USERMESSAGE, "");
        String model = msg.getStringParam(AI_P_MODEL, llmProvider.getDefaultModel());
        double temperature = msg.getDoubleParam(AI_P_TEMPERATURE, defaultTemperature);
        int maxTokens = msg.getIntParam(AI_P_MAXTOKENS, defaultMaxTokens);

        // 推理模式：请求级覆盖 > 全局默认
        String effectiveReasoningMode = msg.getStringParam(AI_P_REASONING_MODE, reasoningMode);
        boolean effectiveReasoningVisible = msg.parseBoolean(AI_P_REASONING_VISIBLE, reasoningVisible);
        if ("auto".equals(effectiveReasoningMode)) {
            effectiveReasoningMode = resolveAutoMode(model);
        }

        if (userMessage.isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, "Error: missing userMessage");
        }

        putLog("Chat start: sessionId=" + sessionId + " stream=" + stream
                + " reasoningMode=" + effectiveReasoningMode, LogLevel.DEBUG);
        // 取消标志：每次 chat 开始复位；/stop 经 stopChat() 置 true
        final java.util.concurrent.atomic.AtomicBoolean cancelled =
                cancelFlags.computeIfAbsent(sessionId, k -> new java.util.concurrent.atomic.AtomicBoolean());
        cancelled.set(false);
        // 登记 worker 线程，供 stopChat 中断 + 杀其派生进程
        chatThreads.put(sessionId, Thread.currentThread());
        // 向监控模块登记本执行实例（放在 try 内，finally 负责注销，避免空消息提前 return 泄漏）
        putMsg(M_AGENTMONITOR, createMsg().setAction("register")
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam("rootSessionId", rootSid)
                .setParam("agentName", getName()));
        try {
            // ==== 预处理: 记忆召回 + 上下文 ====
            TLMsg beforeResult = (TLMsg) msg.getSystemParam(PRERESULT);
            String memoryContext = null;
            if (beforeResult != null && beforeResult.containsParam(AI_P_MEMORYRESULT)) {
                List<TLMemoryEntry> entries = (List<TLMemoryEntry>)
                        beforeResult.getListParam(AI_P_MEMORYRESULT, null);
                if (entries != null && !entries.isEmpty()) {
                    StringBuilder ctx = new StringBuilder("以下是你过往的历史记忆，请根据当前对话自行判断哪些相关：\n");
                    for (TLMemoryEntry e : entries) ctx.append("- ").append(e.getValue()).append("\n");
                    memoryContext = ctx.toString();
                }
            }

            // ==== 断点恢复检查 ====
            // 历史数据由调用方（agentService）从 SessionManager 加载好，通过 msg 参数传入。
            // Agent 不直接操作会话文件——只接收已加载的数据。
            boolean resume = msg.parseBoolean("resume", false);
            List<TLConversationHistory> history;
            int iteration = 0;
            boolean resumedFromCheckpoint = false;
            long[] turn = {0, 0, 0};   // 本次 chat 的 token 用量 {prompt, completion, total}
            long[] cacheTurn = {0, 0, 0}; // 本次 chat 的缓存统计 {cacheCreation, cacheHit, cacheMiss}
            boolean truncated = false; // 是否因到达 maxToolCallIterations 而截断
            boolean aborted = false;   // 是否被 /stop 协作式中断
            boolean clarified = false; // 是否调用了 request_clarification 工具
            boolean pendingApproval = false; // 是否需要人工审批
            boolean rejected = false;       // 审批是否被拒绝
            // 本轮唯一标识：恢复时继承保存的 roundId，新会话生成新的
            String roundId = msg.getStringParam("resumeRoundId",
                    "r_" + System.currentTimeMillis());
            sessionRoundIds.put(sessionId, roundId);
            int msgStartIdx;
            // 存储到 map 供 onStreamResult 使用，finally 中清理
            sessionMsgStartIdx.remove(sessionId);

            if (resume && msg.containsParam("history")) {
                // resume=true 且调用方已传入 history → 从断点/历史会话恢复
                @SuppressWarnings("unchecked")
                List<TLConversationHistory> loadedHistory =
                        (List<TLConversationHistory>) msg.getParam("history");
                String resumeState = msg.getStringParam("resumeState", SESSION_STATE_CHECKPOINT);

                if (SESSION_STATE_PENDING_APPROVAL.equals(resumeState)) {
                    // ==== pending_approval 断点恢复 ====
                    history = loadedHistory;
                    msgStartIdx = history.size();
                    sessionMsgStartIdx.put(sessionId, msgStartIdx);
                    model = msg.getStringParam("resumeModel", model);
                    temperature = msg.getDoubleParam("resumeTemperature", temperature);
                    maxTokens = msg.getIntParam("resumeMaxTokens", maxTokens);
                    resumedFromCheckpoint = true;

                    TLToolCall savedTc = msg.containsParam("pendingToolCall")
                            ? (TLToolCall) msg.getParam("pendingToolCall") : null;
                    String savedApprovalId = msg.getStringParam(AI_P_APPROVAL_ID, "");

                    String decision = msg.getStringParam(AI_P_APPROVAL_DECISION, "pending");
                    if ("approved".equals(decision) && savedTc != null) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> modifiedArgs =
                                msg.getMapParam(AI_P_APPROVAL_MODIFIEDARGS, null);
                        if (modifiedArgs != null && !modifiedArgs.isEmpty()) {
                            savedTc.setArguments(modifiedArgs);
                        }
                        java.util.List<TLToolCall> singleTc = new java.util.ArrayList<>();
                        singleTc.add(savedTc);
                        history.add(new TLConversationHistory(
                                TLConversationHistory.Role.assistant, singleTc));
                        // 解析并委托 ToolExecutor 执行单个恢复的 tool
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> savedArgs = savedTc.getArguments() instanceof java.util.Map
                                ? new java.util.LinkedHashMap<>((java.util.Map<String, Object>) savedTc.getArguments())
                                : new java.util.LinkedHashMap<>();
                        List<TLToolExecutor.ToolTask> singleTask = resolveToolCalls(singleTc, sessionId,
                                msg.getStringParam("userId", "default"));
                        String execId2 = sessionId + "_resume_" + System.nanoTime();
                        TLMsg singleExecResult = singleTask.isEmpty() ? null
                                : putMsg(toolExecutor, createMsg().setAction(TODOOLEXECUTE)
                                        .setParam("tasks", singleTask)
                                        .setParam("executionId", execId2)
                                        .setParam(AI_P_SESSIONID, sessionId)
                                        .setParam("userId", msg.getStringParam("userId", "default"))
                                        .setParam("rootSessionId", rootSid));
                        String singleOutput;
                        if (singleExecResult != null) {
                            @SuppressWarnings("unchecked")
                            List<TLToolExecutor.ToolResult> singleResults =
                                    (List<TLToolExecutor.ToolResult>) singleExecResult.getListParam("results", null);
                            singleOutput = (singleResults != null && !singleResults.isEmpty())
                                    ? singleResults.get(0).output : "";
                        } else {
                            singleOutput = "";
                        }
                        history.add(new TLConversationHistory(savedTc.getId(),
                                savedTc.getFunctionName(), singleOutput));
                        putLog("Approval resumed: approved → tool executed: " + savedTc.getFunctionName(),
                                LogLevel.INFO);
                    } else if ("rejected".equals(decision)) {
                        String reason = msg.getStringParam(AI_P_APPROVAL_REJECTREASON, "用户拒绝");
                        history.add(new TLConversationHistory(
                                TLConversationHistory.Role.user,
                                "（上一操作已被拒绝：" + reason + "。请寻找替代方案。）"));
                        putLog("Approval resumed: rejected → reason=" + reason, LogLevel.INFO);
                    } else {
                        putLog("Approval still pending on resume: approvalId=" + savedApprovalId, LogLevel.WARN);
                        return createMsg().setParam(RESULT, false)
                                .setParam(AI_P_RESPONSE, "审批请求仍在等待中，approvalId=" + savedApprovalId);
                    }
                } else {
                    // L2: mid-loop checkpoint 恢复 / L1: completed 会话恢复
                    history = loadedHistory;
                    msgStartIdx = history.size();
                    sessionMsgStartIdx.put(sessionId, msgStartIdx);
                    model = msg.getStringParam("resumeModel", model);
                    temperature = msg.getDoubleParam("resumeTemperature", temperature);
                    maxTokens = msg.getIntParam("resumeMaxTokens", maxTokens);
                    resumedFromCheckpoint = true;
                    putLog("Resumed from checkpoint: sessionId=" + sessionId + " iter=" + iteration
                            + " historySize=" + (history != null ? history.size() : 0), LogLevel.INFO);
                }
            } else {
                // 正常流程：从 context 构建历史
                history = getContextHistory(sessionId);
                msgStartIdx = history.size();
                sessionMsgStartIdx.put(sessionId, msgStartIdx);
                if (memoryContext != null && !memoryContext.isEmpty()) {
                    history.add(new TLConversationHistory(TLConversationHistory.Role.system, memoryContext));
                }
                history.add(new TLConversationHistory(TLConversationHistory.Role.user, userMessage));
            }
            // 模板变量替换：{{key}} 占位符，查找规则：msg 参数 → agent params → 内置值
            java.text.SimpleDateFormat dateFmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String nowStr = dateFmt.format(new java.util.Date());
            java.util.regex.Pattern varPattern = java.util.regex.Pattern.compile("\\{\\{(\\w+)\\}\\}");
            for (TLConversationHistory h : history) {
                if (h.getContent() == null || h.getContent().isEmpty()) continue;
                String c = h.getContent();
                java.util.regex.Matcher m = varPattern.matcher(c);
                StringBuffer sb = new StringBuffer();
                while (m.find()) {
                    String key = m.group(1);
                    String value = resolveVar(key, msg, sessionId, nowStr);
                    m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(value));
                }
                m.appendTail(sb);
                h.setContent(sb.toString());
            }
            List<TLFunctionDefinition> toolDefs = getFunctionDefinitions();

            // ==== prompt 模式：注入 💭 推理引导到 system prompt ====
            if ("prompt".equals(effectiveReasoningMode) && toolDefs != null && !toolDefs.isEmpty()) {
                String reasoningPrompt = "\n\n## 推理规则\n"
                        + "当需要调用工具时，请先用 💭 开头写一行简短推理，说明你为什么要调用这个工具。\n"
                        + "格式: 💭 <一句话推理>\n"
                        + "然后正常调用工具。不需要在最终回复中保留 💭 内容。";
                boolean injected = false;
                for (TLConversationHistory h : history) {
                    if (h.getRole() == TLConversationHistory.Role.system && h.getContent() != null) {
                        h.setContent(h.getContent() + reasoningPrompt);
                        injected = true;
                        break;
                    }
                }
                if (!injected) {
                    history.add(0, new TLConversationHistory(TLConversationHistory.Role.system, reasoningPrompt));
                }
            }

            // ==== reasoning 累积（供最终返回） ====
            StringBuilder allReasoning = new StringBuilder();

            // ==== LLM请求 ====
            String finalResponse;

            if (stream) {
                // 流式: 单次请求，无tool-call循环
                // 推理参数透传
                if (!"off".equals(effectiveReasoningMode)) {
                    // 流式参数将在 doStreamCall 构建消息时注入
                }
                finalResponse = doStreamCall(history, toolDefs, sessionId, model);
                if (cancelled.get()) {
                    aborted = true;
                } else if (finalResponse != null) {
                    history.add(new TLConversationHistory(TLConversationHistory.Role.assistant, finalResponse));
                }
                // 收集流式推理内容
                TLBaseModule cb = getModule("streamCallback") instanceof TLBaseModule
                        ? (TLBaseModule) getModule("streamCallback") : null;
                if (cb != null) {
                    String streamReasoning = ((TLStreamCallback) cb).getReasoning();
                    if (streamReasoning != null && !streamReasoning.isEmpty()) {
                        allReasoning.append(streamReasoning);
                    }
                }
            } else {
                // 非流式: tool-call循环
                finalResponse = null;
                while (iteration < maxToolCallIterations) {
                    ThreadTask.checkPauseHere();
                    if (cancelled.get()) { aborted = true; break; }
                    iteration++;
                    // L2 checkpoint: 每次迭代前通知 SessionManager
                    notifySessionManager(createMsg()
                            .setAction("sessionUpdated")
                            .setParam("sessionId", sessionId)
                            .setParam("userId", msg.getStringParam("userId", "default"))
                            .setParam("agentName", name)
                            .setParam("roundId", roundId)
                            .setParam("messages", deltaMessages(history, msgStartIdx))
                            .setParam("model", model)
                            .setParam("temperature", temperature)
                            .setParam("maxTokens", maxTokens)
                            .setParam("userMessage", userMessage));
                    TLMsg llmMsg = createMsg().setAction(LLM_COMPLETION)
                            .setParam(AI_P_MESSAGEHISTORY, history).setParam(AI_P_MODEL, model)
                            .setParam(AI_P_TEMPERATURE, temperature).setParam(AI_P_MAXTOKENS, maxTokens);
                    if (toolDefs != null && !toolDefs.isEmpty()) {
                        llmMsg.setParam(AI_P_FUNCTIONDEFS, new ArrayList<>(toolDefs));
                    }
                    // 结构化输出：透传 response_format（json_object / json_schema）
                    if (msg.containsParam(AI_P_RESPONSEFORMAT)) {
                        llmMsg.setParam(AI_P_RESPONSEFORMAT, msg.getParam(AI_P_RESPONSEFORMAT));
                    }
                    // 推理参数透传给 Provider
                    if (!"off".equals(effectiveReasoningMode)) {
                        llmMsg.setParam(AI_P_REASONING_MODE, effectiveReasoningMode);
                        llmMsg.setParam(AI_P_THINKING_BUDGET, msg.getIntParam(AI_P_THINKING_BUDGET, thinkingBudget));
                    }

                    TLMsg llmResponse = putMsg(llmProvider, llmMsg);
                    // 取消优先：/stop 置标志 或 Provider 报告 HTTP 被取消 → 干净退出，不当错误处理
                    if (cancelled.get() || llmResponse.parseBoolean(AI_P_CANCELLED, false)) {
                        aborted = true;
                        break;
                    }
                    if (!llmResponse.parseBoolean(RESULT, false)) {
                        String errMsg = llmResponse.getStringParam(AI_P_RESPONSE, null);
                        if (errMsg == null || errMsg.isEmpty()) {
                            errMsg = "LLM 返回错误：HTTP " + llmResponse.getIntParam(AI_P_HTTPSTATUS, 0);
                        }
                        return createMsg().setParam(RESULT, false)
                                .setParam(AI_P_RESPONSE, errMsg);
                    }
                    // Token 用量累加（Provider parseResponse 已解析 usage）
                    turn[0] += llmResponse.getIntParam(AI_P_PROMPTTOKENS, 0);
                    turn[1] += llmResponse.getIntParam(AI_P_COMPLETIONTOKENS, 0);
                    turn[2] += llmResponse.getIntParam(AI_P_TOTALTOKENS, 0);

                    // 缓存统计累加
                    cacheTurn[0] += llmResponse.getLongParam(AI_P_CACHECREATIONTOKENS, 0L);
                    cacheTurn[1] += llmResponse.getLongParam(AI_P_CACHEHITTOKENS, 0L);
                    cacheTurn[2] += llmResponse.getLongParam(AI_P_CACHEMISSTOKENS, 0L);

                    // ==== 提取推理内容 ====
                    String reasoningText = null;
                    if (!"off".equals(effectiveReasoningMode)) {
                        // 路线 B：原生 reasoning（Provider 已解析）
                        if (llmResponse.containsParam(AI_P_REASONING)) {
                            reasoningText = llmResponse.getStringParam(AI_P_REASONING, "");
                        }
                        // 路线 A：解析 💭 前缀（prompt 模式）
                        if ("prompt".equals(effectiveReasoningMode) && reasoningText == null) {
                            String respText = llmResponse.getStringParam(AI_P_RESPONSE, "");
                            if (respText != null && respText.startsWith("💭")) {
                                int endIdx = respText.indexOf('\n');
                                if (endIdx > 0) {
                                    reasoningText = respText.substring(0, endIdx).trim();
                                    // 移除 💭 前缀，保留纯净回复
                                    String cleanResponse = respText.substring(endIdx).trim();
                                    llmResponse.setParam(AI_P_RESPONSE, cleanResponse);
                                }
                            }
                        }
                        if (reasoningText != null && !reasoningText.isEmpty()) {
                            history.add(TLConversationHistory.createReasoning(reasoningText));
                            allReasoning.append(reasoningText).append("\n");
                            putLog("Reasoning captured: " + reasoningText.substring(0, Math.min(80, reasoningText.length())),
                                    LogLevel.DEBUG);
                        }
                    }

                    boolean hasToolCalls = llmResponse.parseBoolean("hasToolCalls", false);
                    List<TLToolCall> toolCalls = (List<TLToolCall>) llmResponse.getListParam(AI_P_TOOLCALLS, null);

                    if (!hasToolCalls || toolCalls == null || toolCalls.isEmpty()) {
                        finalResponse = llmResponse.getStringParam(AI_P_RESPONSE, "");
                        history.add(new TLConversationHistory(TLConversationHistory.Role.assistant, finalResponse));
                        break;
                    }

                    putLog("Tool calls: " + toolCalls.size() + " (iter " + iteration + ")", LogLevel.DEBUG);
                    TLConversationHistory aMsg = new TLConversationHistory(
                            TLConversationHistory.Role.assistant, new ArrayList<>(toolCalls));
                    if (llmResponse.containsParam(AI_P_RESPONSE))
                        aMsg.setContent(llmResponse.getStringParam(AI_P_RESPONSE, null));
                    history.add(aMsg);
                    int aMsgIdx = history.size() - 1; // 记录位置，审批/拒绝时精确回滚

                    // ── 解析 + 委托工具执行 ──
                    List<TLToolExecutor.ToolTask> tasks = resolveToolCalls(toolCalls, sessionId,
                            msg.getStringParam("userId", "default"));
                    if (tasks.isEmpty()) {
                        // 全部工具被跳过（校验失败等），回滚 assistant 消息直接 break
                        history.remove(aMsgIdx);
                        break;
                    }

                    String execId = sessionId + "_" + System.nanoTime();
                    String usrId = msg.getStringParam("userId", "default");
                    TLMsg execResult = putMsg(toolExecutor,
                            createMsg().setAction(TODOOLEXECUTE)
                                    .setParam("tasks", tasks)
                                    .setParam("executionId", execId)
                                    .setParam(AI_P_SESSIONID, sessionId)
                                    .setParam("rootSessionId", rootSid)
                                    .setParam("userId", usrId));

                    if (execResult == null) {
                        putLog("toolExecutor returned null, skipping tool results", LogLevel.ERROR);
                        break;
                    }
                    boolean execAborted = execResult.parseBoolean("aborted", false);
                    boolean execRejected = execResult.parseBoolean("rejected", false);
                    boolean execPending = execResult.parseBoolean("pendingApproval", false);
                    boolean execClarified = execResult.parseBoolean("clarified", false);

                    if (execAborted || cancelled.get()) { aborted = true; break; }
                    if (execRejected || execPending || execClarified) {
                        history.remove(aMsgIdx); // 精确回滚 assistant 消息
                    }
                    if (execRejected) {
                        history.add(new TLConversationHistory(TLConversationHistory.Role.user,
                                "（操作已被用户拒绝：" + execResult.getStringParam("rejectReason", "")
                                        + "。不要重试此操作。）"));
                        rejected = true;
                        finalResponse = execResult.getStringParam("finalResponse", "");
                        break;
                    }
                    if (execPending) {
                        pendingApproval = true;
                        finalResponse = execResult.getStringParam("finalResponse", "");
                        notifySessionManager(createMsg()
                                .setAction("sessionUpdated")
                                .setParam("sessionId", sessionId)
                                .setParam("userId", msg.getStringParam("userId", "default"))
                            .setParam("agentName", name)
                                .setParam("messages", deltaMessages(history, msgStartIdx))
                                .setParam("model", model)
                                .setParam("temperature", temperature)
                                .setParam("maxTokens", maxTokens)
                                .setParam("userMessage", userMessage)
                                .setParam("pendingToolCall", toolCalls.get(0))
                                .setParam(AI_P_APPROVAL_ID, execResult.getStringParam(AI_P_APPROVAL_ID, "")));
                        break;
                    }
                    if (execClarified) {
                        clarified = true;
                        finalResponse = execResult.getStringParam("finalResponse", "");
                        break;
                    }
                    // 全部正常：按顺序写入 history
                    @SuppressWarnings("unchecked")
                    List<TLToolExecutor.ToolResult> execResults =
                            (List<TLToolExecutor.ToolResult>) execResult.getListParam("results", null);
                    if (execResults != null) {
                        for (TLToolExecutor.ToolResult r : execResults) {
                            history.add(new TLConversationHistory(r.toolCallId, r.toolCallId, r.output));
                        }
                    }
                    if (aborted || clarified || pendingApproval || rejected) break;
                    // L2 checkpoint: 每轮工具调用后通知 SessionManager
                    notifySessionManager(createMsg()
                            .setAction("sessionUpdated")
                            .setParam("sessionId", sessionId)
                            .setParam("userId", msg.getStringParam("userId", "default"))
                            .setParam("agentName", name)
                            .setParam("roundId", roundId)
                            .setParam("messages", deltaMessages(history, msgStartIdx))
                            .setParam("model", model)
                            .setParam("temperature", temperature)
                            .setParam("maxTokens", maxTokens)
                            .setParam("userMessage", userMessage));
                }
                if (finalResponse == null) {
                    truncated = true;
                    finalResponse = "（已达最大迭代次数 " + maxToolCallIterations + "，可能未完成）";
                }
            }

            // ==== 中断分支：丢弃半截结果，不写 aiContext / 长期记忆，保持历史干净 ====
            if (aborted) {
                // 先清除中断标志，防止后续 putLog → log4j RollingFileManager 因线程中断而报错
                Thread.interrupted();
                // 通知 SessionManager 会话中断，存为 completed 避免残留 checkpoint
                notifySessionManager(createMsg()
                        .setAction("chatAborted")
                        .setParam("sessionId", sessionId)
                        .setParam("userId", msg.getStringParam("userId", "default"))
                            .setParam("agentName", name)
                        .setParam("roundId", roundId)
                        .setParam("messages", deltaMessages(history, msgStartIdx))
                        .setParam("userMessage", userMessage));
                putLog("Chat aborted by user: sessionId=" + sessionId, LogLevel.INFO);
                long[] accCancel = accumulateTokenUsage(sessionId, turn);
                return createMsg().setParam(RESULT, true).setParam(AI_P_CANCELLED, true)
                        .setParam(AI_P_RESPONSE, "⏹ 已中断").setParam(AI_P_SESSIONID, sessionId)
                        .setParam("iterations", iteration)
                        .setParam(AI_P_TOTALTOKENS, (int) turn[2])
                        .setParam(AI_P_TOTALTOKENS_TOTAL, (int) accCancel[2]);
            }

            // ==== 后处理: 保存上下文 + 长期记忆 + 通知 SessionManager ====
            saveContextHistory(sessionId, history);
            notifySessionManager(createMsg()
                    .setAction("chatFinished")
                    .setParam("sessionId", sessionId)
                    .setParam("userId", msg.getStringParam("userId", "default"))
                            .setParam("agentName", name)
                    .setParam("roundId", roundId)
                    .setParam("messages", deltaMessages(history, msgStartIdx))
                    .setParam("userMessage", userMessage)
                    .setParam("response", finalResponse));
            try {
                TLMsg saveMsg = createMsg().setAction(AGENT_SAVEMEMORY)
                        .setParam(AI_P_SESSIONID, sessionId).setParam("storeName", defaultMemoryStore)
                        .setParam("userId", msg.getStringParam("userId", "default"))
                            .setParam("agentName", name)
                        .setParam(AI_P_MEMORYKEY, "chat_" + System.currentTimeMillis())
                        .setParam(AI_P_MEMORYVALUE, userMessage + " → " + finalResponse)
                        .setParam(AI_P_MEMORYTAG, "chat_history");
                saveAgentMemory(fromWho, saveMsg);
            } catch (Exception e) {
                putLog("Save memory failed: " + e.toString(), LogLevel.ERROR);
            }

            putLog("Chat completed: sessionId=" + sessionId, LogLevel.DEBUG);
            // 输出护栏：检查并净化回复
            finalResponse = guardOutput(finalResponse);
            long[] acc = accumulateTokenUsage(sessionId, turn);
            TLMsg ret = createMsg().setParam(RESULT, true).setParam(AI_P_RESPONSE, finalResponse)
                    .setParam(AI_P_SESSIONID, sessionId).setParam("iterations", iteration)
                    .setParam(AI_P_NEEDSCLARIFICATION, clarified)
                    .setParam(AI_P_PROMPTTOKENS, (int) turn[0])
                    .setParam(AI_P_COMPLETIONTOKENS, (int) turn[1])
                    .setParam(AI_P_TOTALTOKENS, (int) turn[2])
                    .setParam(AI_P_PROMPTTOKENS_TOTAL, (int) acc[0])
                    .setParam(AI_P_COMPLETIONTOKENS_TOTAL, (int) acc[1])
                    .setParam(AI_P_TOTALTOKENS_TOTAL, (int) acc[2])
                    // 缓存统计（本次 chat）
                    .setParam(AI_P_CACHECREATIONTOKENS, (int) cacheTurn[0])
                    .setParam(AI_P_CACHEHITTOKENS, (int) cacheTurn[1])
                    .setParam(AI_P_CACHEMISSTOKENS, (int) cacheTurn[2]);
            // 会话累计缓存统计（从 Provider 读取快照）
            if (llmProvider != null) {
                long[] sessionCache = llmProvider.getSessionCacheStats(sessionId);
                ret.setParam(AI_P_CACHECREATIONTOKENS_TOTAL, (int) sessionCache[0]);
                ret.setParam(AI_P_CACHEHITTOKENS_TOTAL, (int) sessionCache[1]);
                ret.setParam(AI_P_CACHEMISSTOKENS_TOTAL, (int) sessionCache[2]);
            }
            if (truncated) ret.setParam(AI_P_TRUNCATED, true);
            // 推理内容：仅当开启且 visible 时暴露
            if (!"off".equals(effectiveReasoningMode) && effectiveReasoningVisible
                    && allReasoning.length() > 0) {
                ret.setParam(AI_P_REASONING, allReasoning.toString().trim());
            }
            return ret;

        } catch (Exception e) {
            // 中断/取消路径（如工具被 interrupt 后异常上抛）→ 当作中断，干净收尾
            if (cancelled.get() || e instanceof InterruptedException) {
                // 先清除中断标志，防止后续 putLog → log4j RollingFileManager 因线程中断而报错
                Thread.interrupted();
                putLog("Chat aborted (exception path): sessionId=" + sessionId, LogLevel.INFO);
                return createMsg().setParam(RESULT, true).setParam(AI_P_CANCELLED, true)
                        .setParam(AI_P_RESPONSE, "⏹ 已中断").setParam(AI_P_SESSIONID, sessionId);
            }
            putLog("Chat error: " + e.toString(), LogLevel.ERROR);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, "Agent error: " + e.getMessage());
        } finally {
            chatThreads.remove(sessionId);
            // 从监控模块注销
            putMsg(M_AGENTMONITOR, createMsg().setAction("unregister")
                    .setParam(AI_P_SESSIONID, sessionId));
            currentRootSessionId.remove();
            currentChatUserId.remove();
            sessionRoundIds.remove(sessionId);
            // 清除中断标志（可能来自 worker.interrupt() 或 provider 回调），
            // 防止返回 ThreadTask 后继续传播导致后续模块误抛 InterruptedException
            while (Thread.interrupted()) { /* drain all pending interrupt flags */ }
        }
    }

    /** 流式LLM调用——使用配置文件中注册的streamCallback模块 */
    private String doStreamCall(List<TLConversationHistory> history,
                                 List<TLFunctionDefinition> toolDefs,
                                 String sessionId, String model) {
        TLBaseModule cb = getModule("streamCallback") instanceof TLBaseModule
                ? (TLBaseModule) getModule("streamCallback") : null;
        if (cb == null) return null;

        putMsg(cb, createMsg().setAction(STREAM_RESET));
        TLMsg sm = createMsg().setAction(LLM_COMPLETIONSTREAM)
                .setParam(AI_P_MESSAGEHISTORY, history).setParam(AI_P_FUNCTIONDEFS, toolDefs)
                .setParam(AI_P_MODEL, model).setParam(AI_P_SESSIONID, sessionId)
                .setParam(RESULTFOR, "streamCallback").setParam(RESULTACTION, STREAM_ONCHUNK);
        putMsg(llmProvider, sm);

        TLMsg wr = putMsg(cb, createMsg().setAction(STREAM_WAITFORSTREAM).setParam("timeout", 120));
        // 提取流式 reasoning 内容
        if (wr.containsParam(AI_P_REASONING)) {
            String streamReasoning = wr.getStringParam(AI_P_REASONING, "");
            if (!streamReasoning.isEmpty()) {
                history.add(TLConversationHistory.createReasoning(streamReasoning));
            }
        }
        return wr.getStringParam("content", null);
    }

    /**
     * 流式chat（异步，通过回调发送chunks）。
     * 直接将调用者的回调目标传给Provider，避免中间转发。
     */
    @SuppressWarnings("unchecked")
    protected TLMsg chatStream(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        String userMessage = msg.getStringParam(AI_P_USERMESSAGE, "");
        // 最终回调目标（显示层）
        String resultFor = msg.getStringParam(RESULTFOR,
                fromWho instanceof String ? (String) fromWho : "caller");
        String resultAction = msg.getStringParam(RESULTACTION, "onStreamChunk");
        // 流式转发目标：onStreamResult 将 chunk/完成信号转发到此
        String fwdTarget = msg.getStringParam("_streamResultFor", resultFor);
        String fwdAction = msg.getStringParam("_streamResultAction", resultAction);

        // 保存转发目标，供 onStreamResult 查表
        if (!fwdTarget.equals(getName())) {
            streamForwardMap.put(sessionId, new String[]{fwdTarget, fwdAction});
        }

        if (llmProvider == null) {
            TLMsg errMsg = createMsg().setAction(fwdAction)
                    .setParam(AI_P_STREAMERROR, "No LLM provider configured");
            putMsg(fwdTarget, errMsg);
            return null;
        }

        // 获取上下文并构建流式请求
        List<TLConversationHistory> history = getContextHistory(sessionId);
        history.add(new TLConversationHistory(TLConversationHistory.Role.user, userMessage));
        List<TLFunctionDefinition> toolDefs = getFunctionDefinitions();

        // 回调目标设为本 agent（走 onStreamResult），最终调用方通过 _streamResultFor 指定
        TLMsg streamMsg = createMsg()
                .setAction(LLM_COMPLETIONSTREAM)
                .setParam(AI_P_MESSAGEHISTORY, history)
                .setParam(AI_P_FUNCTIONDEFS, toolDefs)
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam(RESULTFOR, getName())
                .setParam(RESULTACTION, "onStreamResult");

        if (msg.containsParam(AI_P_MODEL))
            streamMsg.setParam(AI_P_MODEL, msg.getParam(AI_P_MODEL));
        if (msg.containsParam(AI_P_TEMPERATURE))
            streamMsg.setParam(AI_P_TEMPERATURE, msg.getParam(AI_P_TEMPERATURE));

        putMsg(llmProvider, streamMsg);
        return null; // 异步
    }

    /**
     * 处理流式LLM响应结果（回调）。
     * 当流式响应包含tool_calls时，自动执行skills并继续chat循环，
     * 最终将完整的文本响应转发给原始调用者。
     */
    @SuppressWarnings("unchecked")
    protected TLMsg onStreamResult(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        // 查表获取转发目标（chatStream 存、此处取；若调用方未设则 fallback 到 msg 参数）
        String[] fwd = streamForwardMap.get(sessionId);
        String resultFor = fwd != null ? fwd[0]
                : msg.getStringParam("_streamResultFor", "caller");
        String resultAction = fwd != null ? fwd[1]
                : msg.getStringParam("_streamResultAction", "onStreamChunk");

        // 转发chunk或完成信号给最终调用方（TLChatConsole 等）
        if (msg.parseBoolean(AI_P_STREAMDONE, false)) {
            String streamedText = msg.getStringParam(AI_P_RESPONSE, "");
            boolean hasToolCalls = msg.parseBoolean("hasToolCalls", false);
            List<TLToolCall> toolCalls = (List<TLToolCall>) msg.getListParam(AI_P_TOOLCALLS, null);

            if (hasToolCalls && toolCalls != null && !toolCalls.isEmpty()) {
                // 流式响应包含tool calls: 执行skills并继续chat循环
                try {
                    List<TLConversationHistory> history = getContextHistory(sessionId);

                    // 添加assistant消息（流式文本 + tool calls）
                    TLConversationHistory aMsg = new TLConversationHistory(
                            TLConversationHistory.Role.assistant, new ArrayList<>(toolCalls));
                    if (streamedText != null && !streamedText.isEmpty()) {
                        aMsg.setContent(streamedText);
                    }
                    history.add(aMsg);

                    // 并行执行tool calls
                    execToolsViaExecutor(toolCalls, fromWho, sessionId, history, msg.getStringParam("userId", "default"));

                    // 非流式继续LLM循环（支持后续tool calls）
                    String finalResponse = null;
                    int iteration = 1;
                    while (iteration < maxToolCallIterations) {
                        iteration++;
                        List<TLFunctionDefinition> toolDefs = getFunctionDefinitions();
                        TLMsg llmMsg = createMsg().setAction(LLM_COMPLETION)
                                .setParam(AI_P_MESSAGEHISTORY, history)
                                .setParam(AI_P_MODEL, msg.getStringParam(AI_P_MODEL, llmProvider.getDefaultModel()))
                                .setParam(AI_P_TEMPERATURE, defaultTemperature)
                                .setParam(AI_P_MAXTOKENS, defaultMaxTokens);
                        if (toolDefs != null && !toolDefs.isEmpty()) {
                            llmMsg.setParam(AI_P_FUNCTIONDEFS, new ArrayList<>(toolDefs));
                        }

                        TLMsg llmResponse = putMsg(llmProvider, llmMsg);
                        if (!llmResponse.parseBoolean(RESULT, false)) break;
                        // Token 累加（流式后续的非流式循环；首个流式响应的 usage 需 include_usage，暂不覆盖）
                        accumulateTokenUsage(sessionId, new long[]{
                                llmResponse.getIntParam(AI_P_PROMPTTOKENS, 0),
                                llmResponse.getIntParam(AI_P_COMPLETIONTOKENS, 0),
                                llmResponse.getIntParam(AI_P_TOTALTOKENS, 0)});

                        boolean moreToolCalls = llmResponse.parseBoolean("hasToolCalls", false);
                        List<TLToolCall> moreTCs = (List<TLToolCall>) llmResponse.getListParam(AI_P_TOOLCALLS, null);

                        if (!moreToolCalls || moreTCs == null || moreTCs.isEmpty()) {
                            finalResponse = llmResponse.getStringParam(AI_P_RESPONSE, "");
                            history.add(new TLConversationHistory(TLConversationHistory.Role.assistant, finalResponse));
                            break;
                        }

                        TLConversationHistory aMsg2 = new TLConversationHistory(
                                TLConversationHistory.Role.assistant, new ArrayList<>(moreTCs));
                        if (llmResponse.containsParam(AI_P_RESPONSE))
                            aMsg2.setContent(llmResponse.getStringParam(AI_P_RESPONSE, null));
                        history.add(aMsg2);

                        execToolsViaExecutor(moreTCs, fromWho, sessionId, history, msg.getStringParam("userId", "default"));
                    }
                    if (finalResponse == null) finalResponse = "Reached max iterations (" + maxToolCallIterations + ")";

                    // 保存上下文 + 通知 SessionManager + 长期记忆
                    saveContextHistory(sessionId, history);
                    notifySessionManager(createMsg()
                            .setAction("chatFinished")
                            .setParam("sessionId", sessionId)
                            .setParam("userId", msg.getStringParam("userId", "default"))
                            .setParam("agentName", name)
                            .setParam("roundId", sessionRoundIds.getOrDefault(sessionId, ""))
                            .setParam("messages", deltaMessages(history, sessionMsgStartIdx.getOrDefault(sessionId, 0)))
                            .setParam("response", finalResponse != null ? finalResponse : streamedText));
                    try {
                        TLMsg saveMsg = createMsg().setAction(AGENT_SAVEMEMORY)
                                .setParam(AI_P_SESSIONID, sessionId).setParam("storeName", defaultMemoryStore)
                                .setParam("userId", msg.getStringParam("userId", "default"))
                            .setParam("agentName", name)
                                .setParam(AI_P_MEMORYKEY, "chat_" + System.currentTimeMillis())
                                .setParam(AI_P_MEMORYVALUE, streamedText + " → " + finalResponse)
                                .setParam(AI_P_MEMORYTAG, "chat_history");
                        saveAgentMemory(fromWho, saveMsg);
                    } catch (Exception e) {
                        putLog("Save memory failed: " + e.toString(), LogLevel.ERROR);
                    }

                    // 先发送非流式续段的文本 chunk（流式部分已在之前逐块转发）
                    if (finalResponse != null && !finalResponse.isEmpty()) {
                        TLMsg contChunk = createMsg()
                                .setAction(resultAction)
                                .setParam(AI_P_CHUNK, finalResponse)
                                .setParam(AI_P_SESSIONID, sessionId);
                        putMsg(resultFor, contChunk);
                    }
                    // 再发送完成信号
                    TLMsg doneMsg = createMsg()
                            .setAction(resultAction)
                            .setParam(AI_P_STREAMDONE, true)
                            .setParam(AI_P_SESSIONID, sessionId);
                    putMsg(resultFor, doneMsg);

                } catch (Exception e) {
                    putLog("Stream tool call continuation error: " + e.toString(), LogLevel.ERROR);
                    streamForwardMap.remove(sessionId); sessionRoundIds.remove(sessionId);
                    TLMsg errMsg = createMsg()
                            .setAction(resultAction)
                            .setParam(AI_P_STREAMERROR, "Tool call processing error: " + e.getMessage());
                    putMsg(resultFor, errMsg);
                }
            } else {
                // 无tool calls: 直接转发完成信号
                TLMsg doneMsg = createMsg()
                        .setAction(resultAction)
                        .setParam(AI_P_STREAMDONE, true)
                        .setParam(AI_P_SESSIONID, sessionId);
                putMsg(resultFor, doneMsg);
            }
            streamForwardMap.remove(sessionId); sessionRoundIds.remove(sessionId);
        } else if (msg.containsParam(AI_P_STREAMERROR)) {
            TLMsg errMsg = createMsg()
                    .setAction(resultAction)
                    .setParam(AI_P_STREAMERROR, msg.getParam(AI_P_STREAMERROR));
            putMsg(resultFor, errMsg);
            streamForwardMap.remove(sessionId); sessionRoundIds.remove(sessionId);
        } else if (msg.containsParam(AI_P_CHUNK)) {
            // 转发chunk
            TLMsg chunkMsg = createMsg()
                    .setAction(resultAction)
                    .setParam(AI_P_CHUNK, msg.getParam(AI_P_CHUNK))
                    .setParam(AI_P_SESSIONID, sessionId);
            putMsg(resultFor, chunkMsg);
        }

        return null;
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

        // 注入配置
        modulesClass.put(moduleName, cfg);
        modulesParams.put(moduleName, new HashMap<>(cfg));

        // 创建
        TLBaseModule module = (TLBaseModule) getMyModule(moduleName);
        if (module instanceof TLBaseSkill) {
            TLBaseSkill skill = (TLBaseSkill) module;
            registerToRegistry(moduleName, module, "skill");
            functions.put(skillName, new FunctionEntry(skillName, SKILL_EXECUTE, "skill", module, skillName, null));
            invalidateToolDefs();
            putLog("Hot-loaded skill: " + moduleName + " (dir=" + skillDir + ")", LogLevel.INFO, AGENT_HOTLOADSKILL);

            // 持久化
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

    /** 热卸载 skill：从 skills/map, modulesClass/modulesParams 和配置文件中移除 */
    protected TLMsg hotUnloadSkill(Object fromWho, TLMsg msg) {
        String skillDir = msg.getStringParam("skillDir", null);
        String moduleName = msg.getStringParam(MODULENAME, null);
        boolean persist = msg.parseBoolean(HOTLOAD_P_PERSIST, true);

        // moduleName 优先；否则根据 skillDir 在 skills map 中搜索匹配的 allowedScriptDir
        if (moduleName == null || moduleName.isEmpty()) {
            if (skillDir == null || skillDir.isEmpty()) {
                return createMsg().setParam(RESULT, false).setParam("error", "skillDir or moduleName required");
            }
            String targetDir = "skills/" + skillDir + "/scripts";
            // 在自己的 modulesParams 中找匹配 allowedScriptDir 的 skill
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

        // 从 functions 中移除（按模块名查找 skill 类型）
        String foundFnName = null;
        for (FunctionEntry fe : functions.values()) {
            if ("skill".equals(fe.type) && moduleName.equals(fe.module.getName())) {
                foundFnName = fe.name; break;
            }
        }
        if (foundFnName != null) functions.remove(foundFnName);

        // 从本地 modules 移除
        modules.remove(moduleName);

        // 从 modulesClass/modulesParams 移除
        if (modulesClass != null) modulesClass.remove(moduleName);
        if (modulesParams != null) modulesParams.remove(moduleName);

        // 从 registry 注销（用家族名字）
        String key = getFamilyName() + ":" + moduleName;
        putMsg(DEFAULTMODULEREGISTRY, createMsg().setAction(REGISTRY_UNREGISTER)
                .setParam(REGISTRY_P_KEY, key));

        invalidateToolDefs();
        putLog("Hot-unloaded skill: " + moduleName, LogLevel.INFO, AGENT_HOTUNLOADSKILL);

        // 从配置文件删除
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
    protected synchronized TLMsg unregisterFunction(Object fromWho, TLMsg msg, String type) {
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
     * 运行时切换 function 启用状态。
     */
    protected synchronized TLMsg registerFunction(Object fromWho, TLMsg msg, String type) {
        String name = "skill".equals(type) ? msg.getStringParam(AI_P_SKILLNAME, "")
                                           : msg.getStringParam(AI_P_AGENTNAME, "");
        if (name.isEmpty()) return createMsg().setParam(RESULT, false).setParam("error", type + " name required");
        try {
            HashMap<String, String> cfg = new HashMap<>(msg.getMapParam(AI_P_AGENTCONFIG,
                    msg.getMapParam(MODULE_PARAMS, new HashMap<>())));
            cfg.putIfAbsent(MODULE_CLASSFILE, msg.getStringParam(MODULE_CLASSFILE, ""));
            // 注入 modulesClass/modulesParams，getMyModule 才能找到 class/config
            if (modulesClass == null) modulesClass = new ConcurrentHashMap<>();
            if (modulesParams == null) modulesParams = new ConcurrentHashMap<>();
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
        }
    }

    /** 统一重载 function。type="skill"|"agent" */
    protected synchronized TLMsg reloadFunction(Object fromWho, TLMsg msg, String type) {
        String name = "skill".equals(type) ? msg.getStringParam(AI_P_SKILLNAME, "")
                                           : msg.getStringParam(AI_P_AGENTNAME, "");
        if (name.isEmpty()) return createMsg().setParam(RESULT, false).setParam("error", type + " name required");
        if (!functions.containsKey(name))
            return createMsg().setParam(RESULT, false).setParam("error", type + " not found: " + name);
        FunctionEntry old = functions.get(name);
        TLBaseModule newModule = (TLBaseModule) getNewModule(name);
        if (newModule == null) return createMsg().setParam(RESULT, false).setParam("error", "reload failed: " + name);
        modules.put(name, newModule);
        functions.put(name, new FunctionEntry(name, old.action, old.type, newModule, old.description, old.paramSchema));
        registerToRegistry(name, newModule, type);
        invalidateToolDefs();
        return createMsg().setParam(RESULT, true).setParam("name", name);
    }

    protected synchronized TLMsg setSkillEnabled(Object fromWho, TLMsg msg) {
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
    }

    /**
     * 更新已注册 skill 的 tool 定义（描述 / 参数 schema）。只改 tool 定义层，
     * 不涉及业务运行参数（需改业务参数走 unregisterSkill + registerSkill 重建）。
     * 传入哪个改哪个；有改动则失效工具定义缓存。
     */
    @SuppressWarnings("unchecked")
    protected synchronized TLMsg updateSkill(Object fromWho, TLMsg msg) {
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
    }

    /**
     * 向全局 moduleRegistry 注册子模块，以家族名字为 key。
     * registry 未配置时静默跳过（IGNOREMODULEISNULL）。
     */
    private void registerToRegistry(String subName, Object module, String moduleType) {
        if (module == null) return;
        String familyName = module instanceof TLBaseModule
                ? ((TLBaseModule) module).getFamilyName() : getName() + ":" + subName;
        TLMsg msg = createMsg().setAction(REGISTRY_REGISTER)
                .setParam(REGISTRY_P_KEY, familyName)
                .setParam(MODULENAME, subName)
                .setParam(INSTANCE, module);
        // owner 由 familyName 自描述，type 由 instanceof 判断，无需额外存储
        msg.setSystemParam(IGNOREMODULEISNULL, true);
        putMsg(DEFAULTMODULEREGISTRY, msg);
    }

    /**
     * 更新已注册子 Agent 的描述。改后失效工具定义缓存。
     */
    protected synchronized TLMsg updateAgent(Object fromWho, TLMsg msg) {
        String agentName = msg.getStringParam(AI_P_AGENTNAME, "");
        if (agentName.isEmpty() || agentsConfig == null || !agentsConfig.containsKey(agentName)) {
            return createMsg().setParam(RESULT, false).setParam("error", "agent not found: " + agentName);
        }
        String description = msg.getStringParam(AI_P_AGENTDESCRIPTION, "");
        agentsConfig.get(agentName).put("description", description);
        invalidateToolDefs();
        return createMsg().setParam(RESULT, true).setParam(AI_P_AGENTNAME, agentName);
    }

    // ======================== Context操作 ========================

    protected TLMsg getAgentContext(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        TLMsg ctxMsg = createMsg()
                .setAction(CONTEXT_GETMESSAGES)
                .setParam(AI_P_SESSIONID, sessionId);
        return putMsg(contextModuleName, ctxMsg);
    }

    protected TLMsg clearAgentContext(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        TLMsg ctxMsg = createMsg()
                .setAction(CONTEXT_CLEAR)
                .setParam(AI_P_SESSIONID, sessionId);
        // 清除缓存统计
        if (llmProvider != null) {
            llmProvider.clearSessionCacheStats(sessionId);
        }
        return putMsg(contextModuleName, ctxMsg);
    }

    /**
     * 加载历史到上下文（供 agentService 在 /continue 时调用）。
     * Agent 负责转发给内部的 context 模块，外部不需要知道 context 模块名。
     */
    @SuppressWarnings("unchecked")
    protected TLMsg loadHistory(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        List<TLConversationHistory> history =
                (List<TLConversationHistory>) msg.getParam(AI_P_MESSAGEHISTORY);
        if (history == null || history.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "Empty history");
        }
        TLMsg ctxMsg = createMsg()
                .setAction(CONTEXT_REPLACE)
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam(AI_P_MESSAGEHISTORY, history);
        putMsg(contextModuleName, ctxMsg);
        putLog("History loaded: sessionId=" + sessionId + " msgs=" + history.size(), LogLevel.DEBUG);
        return createMsg().setParam(RESULT, true).setParam("count", history.size());
    }

    // resumeSession / findLatestSession / listIncompleteCheckpoints / findIncompleteCheckpoints
    // / listSessions / logIncompleteCheckpoints 已移到 TLSessionManager

    protected TLMsg setSystemMsg(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        String systemMsg = msg.getStringParam(AI_P_SYSTEMMESSAGE, "");
        TLMsg ctxMsg = createMsg()
                .setAction(CONTEXT_SETSYSTEM)
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam(AI_P_SYSTEMMESSAGE, systemMsg);
        return putMsg(contextModuleName, ctxMsg);
    }

    // ======================== Memory操作 ========================

    protected TLMsg saveAgentMemory(Object fromWho, TLMsg msg) {
        String storeName = msg.getStringParam("storeName", defaultMemoryStore);
        TLBaseMemory memory = memoryStores.get(storeName);
        if (memory == null) {
            return createMsg().setParam(RESULT, false).setParam("error", "Memory store not found: " + storeName);
        }
        TLMsg memMsg = createMsg()
                .setAction(MEMORY_STORE)
                .setParam(AI_P_SESSIONID, msg.getStringParam(AI_P_SESSIONID, "default"))
                .setParam("userId", msg.getStringParam("userId", msg.getStringParam(AI_P_SESSIONID, "default")))
                .setParam(AI_P_MEMORYKEY, msg.getStringParam(AI_P_MEMORYKEY, ""))
                .setParam(AI_P_MEMORYVALUE, msg.getParam(AI_P_MEMORYVALUE))
                .setParam(AI_P_MEMORYTAG, msg.getStringParam(AI_P_MEMORYTAG, null))
                .setParam(AI_P_MEMORYEXPTIME, msg.getIntParam(AI_P_MEMORYEXPTIME, -1));
        return putMsg(memory, memMsg);
    }

    @SuppressWarnings("unchecked")
    protected TLMsg recallAgentMemory(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        String userId = msg.getStringParam("userId", sessionId);
        String userMessage = msg.getStringParam(AI_P_MEMORYQUERY,
                msg.getStringParam(AI_P_USERMESSAGE, ""));
        int topK = msg.getIntParam(AI_P_TOPK, defaultMemoryTopK);
        String tag = msg.getStringParam(AI_P_MEMORYTAG, null);
        Set<String> seenKeys = new java.util.HashSet<>();
        List<TLMemoryEntry> allEntries = new ArrayList<>();

        for (String storeName : memoryStores.keySet()) {
            TLBaseMemory memory = memoryStores.get(storeName);
            if (memory == null) continue;

            // 路1: contains() 关键词精准匹配，搜索范围放大，关键词本身已做过滤
            if (!userMessage.isEmpty()) {
                collectSearch(memory, sessionId, userId, userMessage, tag, 100, allEntries, seenKeys);
            }

            // 路2: 最近记忆（无关键词过滤），补上 contains() 漏掉的语义相关记忆
            collectSearch(memory, sessionId, userId, "", tag, topK, allEntries, seenKeys);
        }

        // 按时间倒序，截取 topK 条注入 LLM
        allEntries.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));
        if (allEntries.size() > topK) {
            allEntries = new ArrayList<>(allEntries.subList(0, topK));
        }
        return createMsg().setParam(RESULT, !allEntries.isEmpty())
                .setParam(AI_P_MEMORYRESULT, allEntries);
    }

    @SuppressWarnings("unchecked")
    private void collectSearch(TLBaseMemory memory, String sessionId, String userId,
                                String query, String tag, int topK,
                                List<TLMemoryEntry> allEntries, Set<String> seenKeys) {
        TLMsg memMsg = createMsg()
                .setAction(MEMORY_SEARCH)
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam("userId", userId)
                .setParam(AI_P_MEMORYQUERY, query)
                .setParam(AI_P_MEMORYTAG, tag)
                .setParam(AI_P_TOPK, topK);
        TLMsg result = putMsg(memory, memMsg);
        List<TLMemoryEntry> entries = (List<TLMemoryEntry>)
                result.getListParam(AI_P_MEMORYRESULT, null);
        if (entries != null) {
            for (TLMemoryEntry e : entries) {
                if (seenKeys.add(e.getKey())) {
                    allEntries.add(e);
                }
            }
        }
    }

    // ======================== Provider管理 ========================

    protected TLMsg setLlmProvider(Object fromWho, TLMsg msg) {
        String providerName = msg.getStringParam(AI_P_PROVIDER, "");
        if (providerName.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "provider name required");
        }
        TLBaseModule module = getModule(providerName) instanceof TLLlmProvider
                ? (TLBaseModule) getModule(providerName) : null;
        if (module instanceof TLLlmProvider) {
            llmProvider = (TLLlmProvider) module;
            defaultLlmProvider = providerName;

            // 从providersConfig注入参数
            if (providersConfig != null) {
                HashMap<String, String> pConf = providersConfig.get(providerName);
                if (pConf != null) {
                    if (pConf.get("apiKey") != null)
                        llmProvider.setApiKey(pConf.get("apiKey"));
                    if (pConf.get("apiBaseUrl") != null)
                        llmProvider.setApiBaseUrl(pConf.get("apiBaseUrl"));
                    if (pConf.get("defaultModel") != null)
                        llmProvider.setDefaultModel(pConf.get("defaultModel"));
                }
            }

            modules.put(providerName, llmProvider);
            putLog("LLM Provider switched to: " + providerName, LogLevel.DEBUG);
            return createMsg().setParam(RESULT, true);
        }
        return createMsg().setParam(RESULT, false).setParam("error", "Provider not found: " + providerName);
    }

    // ======================== 内部辅助方法 ========================

    /**
     * 获取会话上下文历史
     */
    @SuppressWarnings("unchecked")
    /** 发送会话通知给 SessionManager。由 Agent 的 enableCheckpoint 决定是否通知。 */
    private void notifySessionManager(TLMsg notificationMsg) {
        if (enableCheckpoint) putMsg(sessionManagerName, notificationMsg);
    }

    /** 从 fullHistory 中截取本轮增量消息（从 startIdx 开始到末尾） */
    private List<TLConversationHistory> deltaMessages(List<TLConversationHistory> fullHistory, int startIdx) {
        if (startIdx >= fullHistory.size()) return new ArrayList<>();
        return new ArrayList<>(fullHistory.subList(startIdx, fullHistory.size()));
    }

    protected List<TLConversationHistory> getContextHistory(String sessionId) {
        TLMsg ctxMsg = createMsg()
                .setAction(CONTEXT_GETMESSAGES)
                .setParam(AI_P_SESSIONID, sessionId);
        TLMsg response = putMsg(contextModuleName, ctxMsg);
        if (response != null && response.containsParam(AI_P_MESSAGEHISTORY)) {
            return (List<TLConversationHistory>) response.getListParam(AI_P_MESSAGEHISTORY, new ArrayList<>());
        }
        return new ArrayList<>();
    }

    /**
     * 保存上下文历史（批量替换，O(1)次消息传递）
     */
    protected void saveContextHistory(String sessionId, List<TLConversationHistory> history) {
        TLMsg replaceMsg = createMsg()
                .setAction(CONTEXT_REPLACE)
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam(AI_P_MESSAGEHISTORY, history);
        putMsg(contextModuleName, replaceMsg);
    }

    /** 标记工具定义缓存失效，下次 getFunctionDefinitions 会重建。 */
    private void invalidateToolDefs() { toolDefsDirty = true; }

    /**
     * 对外入口：返回缓存的 function definitions。
     * 首次调用或工具集变更（toolDefsDirty）后重建，否则直接返回缓存的不可变列表。
     * 双检锁与所有失效方法（register/unregister/update skill/agent，均 synchronized(this)）同锁。
     */
    protected List<TLFunctionDefinition> getFunctionDefinitions() {
        List<TLFunctionDefinition> local = cachedToolDefs;
        if (!toolDefsDirty && local != null) return local;
        synchronized (this) {
            if (toolDefsDirty || cachedToolDefs == null) {
                cachedToolDefs = Collections.unmodifiableList(rebuildFunctionDefinitions());
                toolDefsDirty = false;
            }
            return cachedToolDefs;
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
     * 输出护栏：若 params 中配置了 outputGuard 模块名，校验并净化 LLM 回复。
     */
    private String guardOutput(String response) {
        if (response == null || response.isEmpty()) return response;
        String guardModuleName = params != null ? params.get("outputGuard") : null;
        if (guardModuleName == null || guardModuleName.isEmpty()) return response;
        Object found = getModule(guardModuleName);
        TLBaseModule guard = found instanceof TLBaseModule ? (TLBaseModule) found : null;
        if (guard == null) return response;
        try {
            TLMsg guardMsg = createMsg().setAction("validateOutput")
                    .setParam(AI_P_RESPONSE, response);
            TLMsg result = putMsg(guard, guardMsg);
            if (result != null && result.containsParam(AI_P_RESPONSE)) {
                return result.getStringParam(AI_P_RESPONSE, response);
            }
        } catch (Exception e) {
            putLog("Output guard error: " + e.toString(), LogLevel.WARN);
        }
        return response;
    }

    /**
     * 构建委托子Agent的参数schema（task描述）
     */
    protected Map<String, Object> buildDelegateParamSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("task", Map.of("type", "string", "description", "要交给专业Agent处理的任务描述"));
        return schema;
    }

    /**
     * 将 LLM 返回的 tool_calls 解析为 ToolTask 列表（查 functions 表 + 校验 + 路由）。
     * request_clarification 在此处理（不入 ToolExecutor），通过返回结果中的 clarified 标记告知 doChat。
     */
    @SuppressWarnings("unchecked")
    private List<TLToolExecutor.ToolTask> resolveToolCalls(List<TLToolCall> toolCalls,
                                                            String sessionId, String userId) {
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
                System.out.println(">>> [Clarify] Agent 请求确认: " + question);
                TLToolExecutor.ToolTask clarifyTask = new TLToolExecutor.ToolTask(
                        tc.getId(), null, AGENT_REQUESTCLARITY, new LinkedHashMap<>(), userId);
                clarifyTask.precomputedOutput = "⚠️ 需要确认: " + question;
                tasks.add(clarifyTask);
                continue;
            }

            FunctionEntry fn = functions.get(functionName);
            if (fn == null || !fn.enabled) {
                putLog("Function not found: " + functionName, LogLevel.WARN);
                TLToolExecutor.ToolTask errTask = new TLToolExecutor.ToolTask(
                        tc.getId(), null, SKILL_EXECUTE, new LinkedHashMap<>(), userId);
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
                            .setParam(AI_P_SKILLINPUT, toolArgs)
                            .setParam("userId", userId));
                    if (vResult != null && !vResult.parseBoolean(RESULT, false)) {
                        putLog("Skill validation failed: " + functionName, LogLevel.WARN);
                        TLToolExecutor.ToolTask valFailTask = new TLToolExecutor.ToolTask(
                                tc.getId(), null, SKILL_EXECUTE, new LinkedHashMap<>(), userId);
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
            if ("_msgId_".equals(fn.action)) {
                action = "_msgId_:" + fn.name; // msgTool: 前缀标记，doToolExec 识别后走 msgId 路由
            } else if (MCP_CALLTOOL.equals(fn.action)) {
                action = MCP_CALLTOOL;
            } else {
                action = fn.action;
            }

            TLToolExecutor.ToolTask task = new TLToolExecutor.ToolTask(
                    tc.getId(), fn.module, action, toolArgs, userId);
            if (MCP_CALLTOOL.equals(action)) {
                task.nativeName = fn.nativeName;
            }
            tasks.add(task);
        }
        return tasks;
    }

    /**
     * 委托 ToolExecutor 批量执行工具，结果直接写入 history（供 onStreamResult 等简单场景）。
     */
    @SuppressWarnings("unchecked")
    private void execToolsViaExecutor(List<TLToolCall> toolCalls, Object fromWho,
                                       String sessionId, List<TLConversationHistory> history, String userId) {
        if (toolCalls == null || toolCalls.isEmpty()) return;
        List<TLToolExecutor.ToolTask> tasks = resolveToolCalls(toolCalls, sessionId, userId);
        if (tasks.isEmpty()) return;

        String execId = sessionId + "_" + System.nanoTime();
        TLMsg execResult = putMsg(toolExecutor,
                createMsg().setAction(TODOOLEXECUTE)
                        .setParam("tasks", tasks)
                        .setParam("executionId", execId)
                        .setParam(AI_P_SESSIONID, sessionId)
                        .setParam("userId", userId != null ? userId : "default")
                        .setParam("rootSessionId", currentRootSessionId.get()));

        List<TLToolExecutor.ToolResult> results =
                (List<TLToolExecutor.ToolResult>) execResult.getListParam("results", null);
        if (results != null) {
            for (TLToolExecutor.ToolResult r : results) {
                history.add(new TLConversationHistory(r.toolCallId, r.toolCallId, r.output));
            }
        }
    }

    /** 解析模板变量 {{key}}：msg 参数 → agent params → 内置值 → 原样保留 */
    private String resolveVar(String key, TLMsg msg, String sessionId, String nowStr) {
        // 1. 从消息参数查找
        if (msg != null && msg.containsParam(key)) {
            Object v = msg.getParam(key);
            return v != null ? v.toString() : "";
        }
        // 2. 从 agent 自身 params 查找
        if (params != null && params.containsKey(key))
            return params.get(key);
        // 3. 内置变量
        switch (key) {
            case "date": case "time": return nowStr;
            case "sessionId": return sessionId;
            case "agentName": return name;
            case "agentDescription": return agentDescription != null ? agentDescription : "";
        }
        // 4. 未匹配 → 原样保留，不破坏模板
        return "{{" + key + "}}";
    }

    /**
     * 添加包名前缀（与TLDataBase.addPackage相同的模式）
     */
    protected String addPackage(String name, String packageName) {
        if (packageName == null || packageName.isEmpty())
            return name;
        if (name == null || name.isEmpty())
            return name;
        String firstCha = name.substring(0, 1);
        if (firstCha.equals(".")) {
            return packageName + name;
        }
        return name;
    }

    // ======================== 推理模式自动判断 ========================

    /**
     * auto 模式：根据模型名自动判断走原生 reasoning 还是 prompt 引导。
     * @return "native" 或 "prompt"
     */
    protected String resolveAutoMode(String model) {
        if (model == null) return "prompt";
        String m = model.toLowerCase();
        // DeepSeek-R1 / Reasoner / V3.1+ / V4+ (支持原生 reasoning_content)
        if (m.contains("r1") || m.contains("reasoner")) return "native";
        if ((m.contains("v3") || m.contains("v4") || m.contains("v5")) && m.contains("deepseek"))
            return "native";
        // Claude Extended Thinking 模型
        if (m.contains("fable-5") || m.contains("opus-4")) return "native";
        // OpenAI o-series
        if (m.contains("o1") || m.contains("o3") || m.contains("o4")) return "native";
        // 其余模型走 prompt 引导
        return "prompt";
    }

    /** 把本次 turn 用量累加进 session 累计，返回累计后的 {prompt, completion, total} */
    private long[] accumulateTokenUsage(String sessionId, long[] turn) {
        return sessionTokenUsage.compute(sessionId, (k, v) -> {
            long[] a = (v == null) ? new long[3] : v;
            a[0] += turn[0]; a[1] += turn[1]; a[2] += turn[2];
            return a;
        }).clone();
    }

    /** 查询指定 session 的累计 token 用量 */
    protected TLMsg getTokenUsage(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        long[] a = sessionTokenUsage.getOrDefault(sessionId, new long[3]);
        return createMsg().setParam(RESULT, true).setParam(AI_P_SESSIONID, sessionId)
                .setParam(AI_P_PROMPTTOKENS_TOTAL, (int) a[0])
                .setParam(AI_P_COMPLETIONTOKENS_TOTAL, (int) a[1])
                .setParam(AI_P_TOTALTOKENS_TOTAL, (int) a[2]);
    }

    /**
     * 查询指定 session 的 prompt caching 统计。
     * 返回累计 cache creation tokens、cache hit tokens、cache miss tokens 和命中率。
     */
    protected TLMsg getPromptCacheStats(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        if (llmProvider == null) {
            return createMsg().setParam(RESULT, false).setParam("error", "No LLM Provider configured");
        }
        long[] stats = llmProvider.getSessionCacheStats(sessionId);
        long totalHitMiss = stats[1] + stats[2];
        double hitRate = totalHitMiss > 0 ? (100.0 * stats[1] / totalHitMiss) : 0.0;
        return createMsg().setParam(RESULT, true)
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam(AI_P_CACHECREATIONTOKENS_TOTAL, (int) stats[0])
                .setParam(AI_P_CACHEHITTOKENS_TOTAL, (int) stats[1])
                .setParam(AI_P_CACHEMISSTOKENS_TOTAL, (int) stats[2])
                .setParam("cacheHitRate", String.format("%.1f%%", hitRate));
    }

    // persistSession / persistSessionWithApproval / loadSessionCheckpoint / deleteSessionFile
    // / autoResumeCheckpoints / sanitizeFileName / getSessionStorePath 已移到 TLSessionManager

    // ======================== 内部配置解析类 ========================

    /**
     * 解析AI Agent自定义XML配置段：providers, skills, memoryStores。
     * 遵循TLDataBase.myConfig模式。
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
                putLog("TLAiAgent config parse error: " + t.toString(), LogLevel.WARN);
            }
        }
    }

}
