package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;
import cn.tianlong.tlobject.utils.TLMsgUtils;
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

    /** 工具执行模块（工厂共享单例，盲执行解析好的 ToolTask） */
    protected TLToolExecutor toolExecutor;

    /** 函数表管理模块（每 agent 私有实例，负责 function 注册/热加载/toolDefs 构建） */
    protected TLToolManager toolManager;

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

    /** 默认模型 */
    protected String defaultModel = "gpt-4o";

    /** 默认temperature */
    protected double defaultTemperature = 0.7;

    /** 无历史模式：不向 context 载入/保存会话消息（每次白纸）。
     *  会话链（sid/rootSessionId/级联停止）不受影响；适合无记忆召回的独立任务子 agent */
    protected boolean noHistory = false;
    /** 直出选项：作为工具被调用时，结果不需上游 LLM 再加工，原样直达最终用户（配置 directOutput，默认 false） */
    protected boolean directOutput = false;

    /** 是否走意图缓存（TLIntentCacheProvider）。带审批门禁工具的 agent 配置 intentCache=false，
     *  防止被拒/危险操作的轮次被学习并在重复请求时命中缓存 */
    protected boolean intentCacheEnabled = true;

    /** 默认maxTokens */
    protected int defaultMaxTokens = 4096;

    /** 记忆召回默认条数（contains 粗筛后注入上下文，LLM 自行判断相关性） */
    protected int defaultMemoryTopK = 50;

    /** 组装视图大小：发送给 LLM 的 context 原始消息条数（摘要 coverSeq 衔接后取尾部） */
    protected int contextViewSize = 40;

    /** 发送视图 token 预算（0=不限制）。消息按条数裁剪不按体量，超大单条/恢复巨量历史会超模型
     *  上下文 → 400 "maximum context length"；buildSendList 据此做发送视图级裁剪（不落 context）。
     *  默认 1048576（DeepSeek 1M），小上下文模型按 agent <params> contextTokenLimit 调小。 */
    protected long contextTokenLimit = 1048576L;

    // ======================== 推理/思考链 (ReAct) ========================

    /** 推理模式：off | prompt | native | auto */
    protected String reasoningMode = "off";
    /** 推理内容是否暴露给调用方（capture 后仍可控制可见性） */
    protected boolean reasoningVisible = true;
    /** Claude Extended Thinking token 预算（仅 native 模式生效） */
    protected int thinkingBudget = 4000;

    // ======================== Agent管理（主控模式） ========================

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

    /** 会话级 userId: sessionId → userId（供 onStreamResult 回调线程查表；doChat/chatStream 入口写入） */
    private final Map<String, String> sessionUserIds = new ConcurrentHashMap<>();
    /** 会话级 userMessage: sessionId → 本轮用户消息（供 onStreamResult 收尾 chatFinished 使用，与 sessionUserIds 对称） */
    private final Map<String, String> sessionUserMessages = new ConcurrentHashMap<>();
    /** 会话级 rootSessionId: sessionId → 根会话 ID（供 onStreamResult 回调线程查表；chatStream 入口写入，cleanupStreamState 清除） */
    private final Map<String, String> sessionRootIds = new ConcurrentHashMap<>();
    /** 会话级 coverSeq: sessionId → 本会话最新摘要段的 coverSeq（组装视图衔接；0=无摘要） */
    private final Map<String, Long> sessionCoverSeq = new ConcurrentHashMap<>();
    /** 会话级 memoryContext: sessionId → 本轮记忆召回注入文本（供 onStreamResult 续跑轮注入，与入口一致） */
    private final Map<String, String> sessionMemoryContext = new ConcurrentHashMap<>();

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
        // 这里始终从我们自己的 myConfig 取 providers（可能为空，但不会崩）。
        // skills/agents/msgTools 段由私有 toolManager 自行解析（同款解析类），agent 不存
        providersConfig = config.getProviders();
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
            if (params.get("noHistory") != null)
                noHistory = "true".equals(params.get("noHistory"));
            if (params.get("directOutput") != null)
                directOutput = "true".equals(params.get("directOutput"));
            if (params.get("intentCache") != null)
                intentCacheEnabled = "true".equals(params.get("intentCache"));
            if (params.get("defaultTemperature") != null) {
                try { defaultTemperature = Double.parseDouble(params.get("defaultTemperature")); }
                catch (NumberFormatException ignored) {}
            } else if (params.get("temperature") != null) {
                // temperature 别名（工作流 <modules> 条目等场景的常用写法）
                try { defaultTemperature = Double.parseDouble(params.get("temperature")); }
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
            if (params.get("contextViewSize") != null) {
                try { contextViewSize = Integer.parseInt(params.get("contextViewSize")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("contextTokenLimit") != null) {
                try { contextTokenLimit = Long.parseLong(params.get("contextTokenLimit")); }
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
            String namespace = params != null ? params.get("agentNamespace") : null;
            // skills/agents 归工具管理模块（toolManager）注入其自己的 modulesClass，
            // agent 只注入 providers 与 memoryStores
            injectConfigs(config.getProviders(), namespace, false);
            injectConfigs(config.getMemoryStores(), namespace, true);
        }

        // 自动加载本 Agent 的 md 文件（须在最后：依赖 contextModuleName 和 modulesParams 已就位）
        loadAgentMd();
        // XML systemMessage 配置注入 context 的 defaultSystemMessage（显式配置优先于 md 正文）
        injectConfigSystemMessage();
    }

    /**
     * XML/模块条目 systemMessage 配置注入：与 loadAgentMd 同机制，
     * 写入 context 模块的 defaultSystemMessage（context 懒加载，此时改 modulesParams 即生效）。
     * 显式配置为主提示，md 正文追加其后。
     */
    private void injectConfigSystemMessage() {
        if (params == null) return;
        String sm = params.get("systemMessage");
        if (sm == null || sm.trim().isEmpty()) return;
        HashMap<String, String> ctxParams =
                modulesParams.computeIfAbsent(contextModuleName, k -> new HashMap<>());
        String existing = ctxParams.get("defaultSystemMessage");
        if (existing == null || existing.isEmpty()) {
            ctxParams.put("defaultSystemMessage", sm);
        } else if (!existing.startsWith(sm)) {
            ctxParams.put("defaultSystemMessage", sm + "\n\n" + existing);
        }
        putLog("Agent systemMessage injected: " + name, LogLevel.DEBUG);
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
        putLog("=== [TLAiAgent] runStartMsg name=" + name + " configFile=" + configFile + " ===", LogLevel.INFO);

        // 统一初始化所有模块（配置段 = 类型，不走 instanceof 检查）
        if (mconfig instanceof myConfig) {
            myConfig config = (myConfig) mconfig;
            initModules(config.getProviders(), "provider");

            // Provider 必须先就绪 —— skill/memory/agent 可能通过 owner:name 借引用
            resolveLlmProvider();

            initModules(config.getMemoryStores(), "memory");
        }

        // 无配置文件的子 agent（mconfig==null）也需要 resolve
        resolveLlmProvider();

        // 后处理：每类模块独有的逻辑
        postInitMemories();

        // 私有 context 实例
        initContext();

        // 工具执行模块：工厂共享单例。审批为全局公共门禁（执行器自身配置 approvalModule），
        // 会话/用户隔离由每次执行的 TODOOLEXECUTE 消息携带的 sessionId/userId 保证
        try {
            toolExecutor = (TLToolExecutor) getModule("toolExecutor");
        } catch (Exception e) {
            putLog("Failed to init toolExecutor: " + e.toString(), LogLevel.WARN);
        }

        // 工具管理最后创建：子工具（skill/agent）可能依赖 agent 已就绪的参数（如 provider 借引用）。
        // 配置文件经模块配置传入，registry 在创建时由框架自动解析（setConfig，与 agent 同款
        // 解析类）；attachOwner 绑定 owner 后自初始化各工具
        initToolManager();

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

    /** 创建本 agent 私有的函数表管理模块（家族名 = agent:toolManager），并绑定 owner 引用。
     *  agent 的配置文件经 configfile 属性传入——工厂注入后，框架在创建 registry 时自动调用
     *  其 setConfig 解析（与 agent 同款解析类），attachOwner 时 registry 自初始化各工具。 */
    private void initToolManager() {
        try {
            HashMap<String, String> frCfg = new HashMap<>();
            frCfg.put(MODULE_SameClassAs, "toolManager");
            if (configFile != null && !configFile.isEmpty()) {
                frCfg.put(MODULE_CONFIGFILE, configFile);
            }
            modulesClass.put("toolManager", frCfg);
            modulesParams.put("toolManager", new HashMap<>(frCfg));
            toolManager = (TLToolManager) getMyModule("toolManager");
            toolManager.attachOwner(this);
            putLog("Private toolManager initialized", LogLevel.DEBUG);
        } catch (Exception e) {
            putLog("Failed to init toolManager: " + e.toString(), LogLevel.WARN);
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
            case AGENT_HOTUNLOADSKILL:
            case AGENT_RELOADSKILL:
            case AGENT_REGISTERSKILL:
            case AGENT_UNREGISTERSKILL:
            case AGENT_LISTSKILLS:
                // 工具管理内脏：薄转发给私有 toolManager，外部消息协议不变
                returnMsg = putMsg(toolManager, msg);
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
            case AGENT_UPDATESKILL:
            case AGENT_UPDATEAGENT:
            case AGENT_REGISTERAGENT:
            case AGENT_UNREGISTERAGENT:
            case AGENT_RELOADAGENT:
            case AGENT_LISTAGENTS:
                // 工具管理内脏：薄转发给私有 toolManager，外部消息协议不变
                returnMsg = putMsg(toolManager, msg);
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
            case "msgTool":
                returnMsg = executeMsgTool(fromWho, msg);
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
    /**
     * 执行 msgTool：从 AI_P_TOOLNAME 取 msgId → checkMsgId → 结果包成 AI_P_SKILLOUTPUT。
     * 解决 msgId 路由返回的 RESULT 不符合 ToolExecutor 的 AI_P_SKILLOUTPUT 约定问题。
     */
    protected TLMsg executeMsgTool(Object fromWho, TLMsg msg) {
        String msgId = msg.getStringParam(AI_P_TOOLNAME, "");
        if (msgId.isEmpty()) {
            return createMsg().setParam(AI_P_SKILLOUTPUT, "Error: msgId missing");
        }
        // 将关键参数注入 systemArgs，确保 msgTable 路由时 doMsgList 的 addSystemArgs 能传播给目标模块
        if (msg.containsSystemParam(AI_P_SESSIONID))
            msg.setSystemParam(AI_P_SESSIONID, msg.getSystemParam(AI_P_SESSIONID, ""));
        if (msg.containsSystemParam("userId"))
            msg.setSystemParam("userId", msg.getSystemParam("userId", ""));

        TLMsg result = checkMsgId(msgId, fromWho, msg);
        String output;
        if (result == null) {
            output = "done";
        } else if (result.containsParam(RESULT)) {
            output = TLMsgUtils.formatMsgToolResult(result.getParam(RESULT));
        } else {
            output = TLMsgUtils.msgToSimpleStr(result);
        }
        return createMsg().setParam(AI_P_SKILLOUTPUT, output);
    }

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
        // 转发会话上下文（框架系统参数区）：保持级联停止、用户数据隔离、会话追踪，
        // 与工具业务参数分层——上游经执行器以 systemArgs 传来，此处原样透传
        if (msg.containsSystemParam(AI_P_SESSIONID))
            chatMsg.setSystemParam(AI_P_SESSIONID, msg.getSystemParam(AI_P_SESSIONID, ""));
        if (msg.containsSystemParam("rootSessionId"))
            chatMsg.setSystemParam("rootSessionId", msg.getSystemParam("rootSessionId", ""));
        if (msg.containsSystemParam("userId"))
            chatMsg.setSystemParam("userId", msg.getSystemParam("userId", ""));
        // 全链追踪：上游 roundId 透传——子 agent 的一轮就是上游的一轮
        if (msg.containsSystemParam(AI_P_ROUNDID))
            chatMsg.setSystemParam(AI_P_ROUNDID, msg.getSystemParam(AI_P_ROUNDID, ""));
        TLMsg result = chat(fromWho, chatMsg);
        String output = result != null ? result.getStringParam(AI_P_RESPONSE, "") : "";
        TLMsg ret = createMsg().setParam(AI_P_SKILLOUTPUT, output);
        // 直出标志：自身配置 directOutput，或下游已传 finalAnswer（链式传播——a→b→c 委托链中 c 的直出意图逐层上递）
        if (directOutput || (result != null && result.parseBoolean(AI_P_FINALANSWER, false))) {
            ret.setParam(AI_P_FINALANSWER, true);
        }
        return ret;
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
        // 框架系统参数区：会话/用户/轮次 ID 由上游经 systemArgs 透传（未设置即上游没传——约定一致）
        String sessionId = String.valueOf(msg.getSystemParam(AI_P_SESSIONID, "default"));
        // 根会话 ID：控制台会话标识，在 spawn 子 agent 时透传，供监控模块级联停止
        String rootSid = String.valueOf(msg.getSystemParam("rootSessionId", sessionId));
        currentRootSessionId.set(rootSid);
        currentChatUserId.set(String.valueOf(msg.getSystemParam("userId", sessionId)));
        sessionUserIds.put(sessionId, currentChatUserId.get());
        String userMessage = msg.getStringParam(AI_P_USERMESSAGE, "");
        sessionUserMessages.put(sessionId, userMessage);
        // 固定任务前缀（配置的 task 参数）：普通 agent 配置后每次收到的消息都前置该任务指令；
        // 工作流下游节点同样经此生效（user 层指令权重最高，systemMessage 管人设，task 管本次工作）
        if (params != null && params.get("task") != null && !params.get("task").trim().isEmpty()) {
            userMessage = params.get("task").trim() + "\n\n" + userMessage;
        }
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
        // 同会话并发锁：同一 sessionId 同时只允许一个 chat 执行（多浏览器/多入口防串台）。
        // 锁按 sessionId 隔离——不同用户不同 sessionId 互不影响；isAlive 兜底死亡残留线程。
        Thread existing = chatThreads.putIfAbsent(sessionId, Thread.currentThread());
        if (existing != null && existing.isAlive()) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, "会话使用中：该会话已有对话正在进行，请等待其完成")
                    .setParam(AI_P_SESSIONID, sessionId);
        }
        if (existing != null) {
            // 死亡残留线程：putIfAbsent 不更新 map——覆盖登记，保证 stopChat 拿到正确 worker
            chatThreads.put(sessionId, Thread.currentThread());
        }
        // 登记 worker 线程，供 stopChat 中断 + 杀其派生进程
        // 向监控模块登记本执行实例（放在 try 内，finally 负责注销，避免空消息提前 return 泄漏）
        putMsg(M_AGENTMONITOR, createMsg().setAction("register")
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam("rootSessionId", rootSid)
                .setParam(AI_P_USERID, currentChatUserId.get())
                .setParam("agentName", getName()));
        // 声明在 try 外：catch 收尾需访问；history 构建前异常时为 null，notifyChatError 内部判空
        List<TLConversationHistory> history = null;
        int msgStartIdx = 0;
        // 本会话最新摘要段 coverSeq（发送视图衔接；0=无摘要）——声明在分支外，发送点共用
        long coverSeq = 0L;
        // 一轮 = 控制台发起对话到返回结果：上游（主 agent/工作流/组）传入的 roundId 沿用，
        // 断点恢复用 resumeRoundId 继承，只有源头（控制台）才生成新的。
        // 注意用 Object 判断存在性：String.valueOf(null) 会得到字面量 "null"
        Object upstreamObj = msg.getSystemParam(AI_P_ROUNDID, null);
        String upstreamRoundId = upstreamObj != null ? String.valueOf(upstreamObj) : "";
        String roundId = !upstreamRoundId.isEmpty()
                ? upstreamRoundId
                : msg.getStringParam("resumeRoundId", "r_" + System.currentTimeMillis());
        sessionRoundIds.put(sessionId, roundId);
        // 全链追踪：轮次开始打点（payload = 本轮用户输入全文）
        traceStage(sessionId, rootSid, roundId, "roundStart", userMessage, 0, userMessage, null);
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
            int iteration = 0;
            boolean resumedFromCheckpoint = false;
            long[] turn = {0, 0, 0};   // 本次 chat 的 token 用量 {prompt, completion, total}
            long[] cacheTurn = {0, 0, 0}; // 本次 chat 的缓存统计 {cacheCreation, cacheHit, cacheMiss}
            boolean truncated = false; // 是否因到达 maxToolCallIterations 而截断
            boolean aborted = false;   // 是否被 /stop 协作式中断
            boolean clarified = false; // 是否调用了 request_clarification 工具
            boolean pendingApproval = false; // 是否需要人工审批
            boolean rejected = false;       // 审批是否被拒绝
            boolean finalDirect = false;    // 工具直出短路（下游 finalAnswer → 本层不再 LLM 加工）
            String rejectedReason = null;   // 拒绝原因（随返回消息传播给上游，供 ToolExecutor 识别）
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
                    // context system 置顶（DB 历史不含，恢复必须显式补）
                    history = withContextSystem(loadedHistory);
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
                        // 黑盒消息接口之二：tool_calls 交工具模块解析为可执行 ToolTask 列表
                        TLMsg frResolveMsg = putMsg(toolManager, createMsg().setAction(AGENT_RESOLVETOOLCALLS)
                                .setParam(AI_P_TOOLCALLS, singleTc));
                        List<TLToolExecutor.ToolTask> singleTask = (List<TLToolExecutor.ToolTask>)
                                frResolveMsg.getListParam("tasks", new ArrayList<>());
                        String execId2 = sessionId + "_resume_" + System.nanoTime();
                        TLMsg singleExecResult = singleTask.isEmpty() ? null
                                : putMsg(toolExecutor, createMsg().setAction(TODOOLEXECUTE)
                                        .setParam("tasks", singleTask)
                                        .setSystemParam("executionId", execId2)
                                        .setSystemParam(AI_P_SESSIONID, sessionId)
                                        .setSystemParam("userId", msg.getSystemParam("userId", "default"))
                                        .setSystemParam("rootSessionId", rootSid)
                                        .setSystemParam(AI_P_ROUNDID, roundId));
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
                    // context system 置顶（DB/checkpoint 增量不含，恢复必须显式补）
                    history = withContextSystem(loadedHistory);
                    msgStartIdx = history.size();
                    sessionMsgStartIdx.put(sessionId, msgStartIdx);
                    // 恢复会话：摘要段在记忆库持久化，召回自动带回 → coverSeq 衔接视图，无空窗
                    coverSeq = computeSessionCoverSeq(beforeResult, sessionId);
                    sessionCoverSeq.put(sessionId, coverSeq);
                    if (memoryContext != null && !memoryContext.isEmpty()) {
                        sessionMemoryContext.put(sessionId, memoryContext);
                    }
                    model = msg.getStringParam("resumeModel", model);
                    temperature = msg.getDoubleParam("resumeTemperature", temperature);
                    maxTokens = msg.getIntParam("resumeMaxTokens", maxTokens);
                    resumedFromCheckpoint = true;
                    putLog("Resumed from checkpoint: sessionId=" + sessionId + " iter=" + iteration
                            + " historySize=" + (history != null ? history.size() : 0), LogLevel.INFO);
                }
            } else if (msg.containsParam(AI_P_LLMINPUT)) {
                // LLM 消息直入：调用方已提供完整 messages（如 trace 定点重放），
                // 不再追加用户消息——以注入 messages 为历史直接进入迭代循环
                @SuppressWarnings("unchecked")
                List<TLConversationHistory> input =
                        (List<TLConversationHistory>) msg.getParam(AI_P_LLMINPUT);
                history = withContextSystem(input);   // 补 index0 system（会话增量不含）
                msgStartIdx = 0;                      // 通知落盘全量（新 roundId 新行，无覆盖）
                sessionMsgStartIdx.put(sessionId, 0);
                // 与正常分支一致：摘要衔接 + 记忆注入（只进发送视图）
                coverSeq = computeSessionCoverSeq(beforeResult, sessionId);
                sessionCoverSeq.put(sessionId, coverSeq);
                if (memoryContext != null && !memoryContext.isEmpty()) {
                    sessionMemoryContext.put(sessionId, memoryContext);
                }
            } else {
                // 正常流程：从 context 构建历史（全量，摘要模式不 trim）。
                // noHistory 模式：不载入历史（每次白纸）——子 agent 无记忆召回时
                // 跨轮历史只膨胀提示词、拖慢响应，会话链（sid/stopByRoot）保持完整
                history = noHistory ? new ArrayList<>() : getContextHistory(sessionId);
                msgStartIdx = history.size();
                sessionMsgStartIdx.put(sessionId, msgStartIdx);
                // 记忆召回注入（原始设计）：上下文完整时 LLM 能区分历史记忆与当前指令
                //（"写诗被算进下一轮"的根因是流式直出跳过 saveContextHistory，非记忆注入本身）
                // 记忆 system 只进发送视图（buildSendList 注入），不写回 context——
                // 摘要模式全量保留下避免每轮记忆 system 在 context 累积污染
                coverSeq = computeSessionCoverSeq(beforeResult, sessionId);
                sessionCoverSeq.put(sessionId, coverSeq);
                if (memoryContext != null && !memoryContext.isEmpty()) {
                    sessionMemoryContext.put(sessionId, memoryContext);
                }
                history.add(new TLConversationHistory(TLConversationHistory.Role.user, userMessage));
            }
            // 对话开始即保存 checkpoint 轮（含用户消息）：进程意外死亡时轮次残留，重启可恢复；
            // 正常完成/人为停止由 chatFinished/chatAborted 同 roundId 覆盖为 completed
            notifySessionManager(createMsg()
                    .setAction("sessionUpdated")
                    .setParam("sessionId", sessionId)
                    .setParam("userId", sessionUserIds.getOrDefault(sessionId, "default"))
                    .setParam("agentName", name)
                    .setParam("roundId", roundId)
                    .setParam("messages", deltaMessages(history, msgStartIdx))
                    .setParam("model", model)
                    .setParam("temperature", temperature)
                    .setParam("maxTokens", maxTokens)
                    .setParam("userMessage", userMessage));
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
            // 黑盒消息接口之一：向工具模块索取 LLM 函数定义列表
            TLMsg frDefsMsg = putMsg(toolManager, createMsg().setAction(AGENT_GETFUNCTIONDEFS));
            List<TLFunctionDefinition> toolDefs = (List<TLFunctionDefinition>)
                    frDefsMsg.getListParam(AI_P_FUNCTIONDEFS, new ArrayList<>());

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
            String finalResponse = null; // 流式分支仅成功路径赋值；错误路径提前 return

            if (stream) {
                // 流式: 单次请求，无tool-call循环
                // 推理参数透传
                if (!"off".equals(effectiveReasoningMode)) {
                    // 流式参数将在 doStreamCall 构建消息时注入
                }
                TLMsg streamResult = doStreamCall(buildSendList(history, coverSeq, memoryContext),
                        toolDefs, sessionId, model);
                if (cancelled.get() || ThreadTask.isCurrentCancelled()) {
                    aborted = true;
                } else if (streamResult == null || !streamResult.parseBoolean(RESULT, false)) {
                    // 流式失败（Provider 中断/超时/回调未注册）：按错误返回，不提交空回合
                    String streamErr = streamResult != null
                            ? streamResult.getStringParam(AI_P_RESPONSE, "流式请求失败")
                            : "流式请求失败";
                    putLog("Stream failed: " + streamErr, LogLevel.ERROR);
                    notifyChatError(sessionId, msg, history, msgStartIdx, roundId, userMessage, streamErr);
                    return createMsg().setParam(RESULT, false).setParam(AI_P_RESPONSE, streamErr);
                } else {
                    finalResponse = streamResult.getStringParam(AI_P_RESPONSE, "");
                    // 空响应放占位符（空 assistant 消息会被 DeepSeek 拒绝，且随上下文存续污染后续回合）
                    history.add(new TLConversationHistory(TLConversationHistory.Role.assistant,
                            finalResponse.isEmpty() ? "（无输出）" : finalResponse));
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
                            .setParam("userId", sessionUserIds.getOrDefault(sessionId, "default"))
                            .setParam("agentName", name)
                            .setParam("roundId", roundId)
                            .setParam("messages", deltaMessages(history, msgStartIdx))
                            .setParam("model", model)
                            .setParam("temperature", temperature)
                            .setParam("maxTokens", maxTokens)
                            .setParam("userMessage", userMessage));
                    TLMsg llmMsg = createMsg().setAction(LLM_COMPLETION)
                            .setParam(AI_P_MESSAGEHISTORY, buildSendList(history, coverSeq, memoryContext))
                            .setParam(AI_P_MODEL, model)
                            .setParam(AI_P_TEMPERATURE, temperature).setParam(AI_P_MAXTOKENS, maxTokens)
                            .setParam(AI_P_SESSIONID, sessionId).setParam(AI_P_ROUNDID, roundId);
                    // per-agent 缓存开关：本 agent 不走意图缓存时在请求上带标志（provider 透传）
                    if (!intentCacheEnabled) llmMsg.setParam(AI_P_NOCACHE, true);
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

                    // 全链追踪：LLM 请求/响应打点（payload = 实际发送给 LLM 的 messages）
                    traceStage(sessionId, rootSid, roundId, "llmRequest",
                            "iter=" + iteration + " model=" + model, 0,
                            formatHistoryForTrace(history), null);
                    long llmStartTs = System.currentTimeMillis();
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
                        // 收尾 SessionManager，避免已发 sessionUpdated 的 checkpoint 轮次残留
                        notifyChatError(sessionId, msg, history, msgStartIdx, roundId, userMessage, errMsg);
                        return createMsg().setParam(RESULT, false)
                                .setParam(AI_P_RESPONSE, errMsg);
                    }
                    // Token 用量累加（Provider parseResponse 已解析 usage）+ 进程级上报（每次 LLM 调用恰一次）
                    long p = llmResponse.getIntParam(AI_P_PROMPTTOKENS, 0);
                    long c = llmResponse.getIntParam(AI_P_COMPLETIONTOKENS, 0);
                    long t = llmResponse.getIntParam(AI_P_TOTALTOKENS, 0);
                    turn[0] += p; turn[1] += c; turn[2] += t;

                    // 缓存统计累加
                    long cc = llmResponse.getLongParam(AI_P_CACHECREATIONTOKENS, 0L);
                    long ch = llmResponse.getLongParam(AI_P_CACHEHITTOKENS, 0L);
                    long cm = llmResponse.getLongParam(AI_P_CACHEMISSTOKENS, 0L);
                    cacheTurn[0] += cc; cacheTurn[1] += ch; cacheTurn[2] += cm;
                    reportProcessTokenUsage(new long[]{p, c, t}, new long[]{cc, ch, cm},
                            sessionId, rootSid, currentChatUserId.get());
                    traceStage(sessionId, rootSid, roundId, "llmResponse",
                            "tokens=" + turn[1] + "/" + turn[2]
                                    + " cache=" + cacheTurn[1] + "/" + cacheTurn[0],
                            System.currentTimeMillis() - llmStartTs,
                            formatResponseForTrace(
                                    llmResponse.getStringParam(AI_P_RESPONSE, ""),
                                    llmResponse.parseBoolean("hasToolCalls", false)
                                            ? (List<TLToolCall>) llmResponse.getListParam(AI_P_TOOLCALLS, null) : null,
                                    llmResponse.getStringParam(AI_P_REASONING, "")),
                            null);

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
                        // 空响应：历史里放占位符（空 assistant 消息会被 DeepSeek 拒绝，
                        // 且会随上下文存续污染后续回合），返回给调用方的 finalResponse 仍保持原值
                        history.add(new TLConversationHistory(TLConversationHistory.Role.assistant,
                                finalResponse.isEmpty() ? "（无输出）" : finalResponse));
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
                    // 黑盒消息接口之二：tool_calls 交工具模块解析为可执行 ToolTask 列表
                    TLMsg frResolveMsg = putMsg(toolManager, createMsg().setAction(AGENT_RESOLVETOOLCALLS)
                            .setParam(AI_P_TOOLCALLS, toolCalls));
                    List<TLToolExecutor.ToolTask> tasks = (List<TLToolExecutor.ToolTask>)
                            frResolveMsg.getListParam("tasks", new ArrayList<>());
                    if (tasks.isEmpty()) {
                        // 全部工具被跳过（校验失败等），回滚 assistant 消息直接 break
                        history.remove(aMsgIdx);
                        break;
                    }

                    String execId = sessionId + "_" + System.nanoTime();
                    String usrId = String.valueOf(msg.getSystemParam("userId", "default"));
                    TLMsg execResult = putMsg(toolExecutor,
                            createMsg().setAction(TODOOLEXECUTE)
                                    .setParam("tasks", tasks)
                                    .setSystemParam("executionId", execId)
                                    .setSystemParam(AI_P_SESSIONID, sessionId)
                                    .setSystemParam("rootSessionId", rootSid)
                                    .setSystemParam("userId", usrId)
                                    .setSystemParam(AI_P_ROUNDID, roundId));

                    if (execResult == null) {
                        putLog("toolExecutor returned null, skipping tool results", LogLevel.ERROR);
                        break;
                    }
                    boolean execAborted = execResult.parseBoolean("aborted", false);
                    boolean execRejected = execResult.parseBoolean("rejected", false);
                    boolean execPending = execResult.parseBoolean("pendingApproval", false);
                    boolean execClarified = execResult.parseBoolean("clarified", false);
                    boolean execTimeout = execResult.parseBoolean("hasTimeout", false);
                    boolean execFinal = execResult.parseBoolean(AI_P_FINALANSWER, false);

                    if (execAborted || cancelled.get()) { aborted = true; break; }
                    if (execRejected || execPending || execClarified || execTimeout) {
                        history.remove(aMsgIdx); // 精确回滚 assistant 消息
                    }
                    if (execRejected) {
                        rejectedReason = execResult.getStringParam("rejectReason", "");
                        history.add(new TLConversationHistory(TLConversationHistory.Role.user,
                                "（操作已被用户拒绝：" + rejectedReason
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
                                .setParam("userId", sessionUserIds.getOrDefault(sessionId, "default"))
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
                    if (execTimeout) {
                        history.add(new TLConversationHistory(TLConversationHistory.Role.user,
                                "（工具执行超时：" + execResult.getStringParam("finalResponse", "")
                                        + "，请根据已有信息继续或重试。）"));
                        finalResponse = execResult.getStringParam("finalResponse", "");
                        break;
                    }
                    // 直出短路：工具结果带 finalAnswer（下游 agent 配置 directOutput）——
                    // 仅单工具轮生效（多工具并行轮维持正常汇总，不丢其他结果）；
                    // 工具结果照常写 history + 直出文本作为本轮 assistant 最终消息（会话恢复/下轮上下文完整性），
                    // 不再调 LLM 加工
                    if (execFinal && toolCalls.size() == 1) {
                        @SuppressWarnings("unchecked")
                        List<TLToolExecutor.ToolResult> execResultsF =
                                (List<TLToolExecutor.ToolResult>) execResult.getListParam("results", null);
                        if (execResultsF != null) {
                            for (TLToolExecutor.ToolResult r : execResultsF) {
                                history.add(new TLConversationHistory(r.toolCallId, r.toolCallId, r.output));
                            }
                        }
                        finalResponse = execResult.getStringParam("finalResponse", "");
                        // 空响应放占位符（空 assistant 消息会被 DeepSeek 拒绝，且随上下文存续污染后续回合）
                        history.add(new TLConversationHistory(TLConversationHistory.Role.assistant,
                                finalResponse.isEmpty() ? "（无输出）" : finalResponse));
                        finalDirect = true;
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
                    if (aborted || clarified || pendingApproval || rejected || execTimeout) break;
                    // L2 checkpoint: 每轮工具调用后通知 SessionManager
                    notifySessionManager(createMsg()
                            .setAction("sessionUpdated")
                            .setParam("sessionId", sessionId)
                            .setParam("userId", sessionUserIds.getOrDefault(sessionId, "default"))
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
                        .setParam("userId", sessionUserIds.getOrDefault(sessionId, "default"))
                            .setParam("agentName", name)
                        .setParam("roundId", roundId)
                        .setParam("messages", deltaMessages(history, msgStartIdx))
                        .setParam("userMessage", userMessage));
                putLog("Chat aborted by user: sessionId=" + sessionId, LogLevel.INFO);
                traceStage(sessionId, rootSid, roundId, "roundEnd", "aborted", 0);
                long[] accCancel = accumulateTokenUsage(sessionId, turn);
                return createMsg().setParam(RESULT, true).setParam(AI_P_CANCELLED, true)
                        .setParam(AI_P_RESPONSE, "⏹ 已中断").setParam(AI_P_SESSIONID, sessionId)
                        .setParam("iterations", iteration)
                        .setParam(AI_P_TOTALTOKENS, (int) turn[2])
                        .setParam(AI_P_TOTALTOKENS_TOTAL, (int) accCancel[2]);
            }

            // ==== 后处理: 保存上下文 + 长期记忆 + 通知 SessionManager ====
            if (!noHistory) saveContextHistory(sessionId, history);   // noHistory 模式不往 context 存消息
            notifySessionManager(createMsg()
                    .setAction("chatFinished")
                    .setParam("sessionId", sessionId)
                    .setParam("userId", sessionUserIds.getOrDefault(sessionId, "default"))
                            .setParam("agentName", name)
                    .setParam("roundId", roundId)
                    .setParam("messages", deltaMessages(history, msgStartIdx))
                    .setParam("userMessage", userMessage)
                    .setParam("response", finalResponse));
            // 全链追踪：轮次结束打点
            String roundOutcome = "completed";
            if (rejected) roundOutcome = "rejected";
            else if (pendingApproval) roundOutcome = "pendingApproval";
            else if (clarified) roundOutcome = "clarified";
            else if (truncated) roundOutcome = "truncated";
            // payload = agent 最终输出（finalResponse 各分支恒赋值；guardOutput 在打点之后，此处为原始值）
            traceStage(sessionId, rootSid, roundId, "roundEnd", roundOutcome, 0,
                    finalResponse != null ? finalResponse : "", null);
            try {
                TLMsg saveMsg = createMsg().setAction(AGENT_SAVEMEMORY)
                        .setParam(AI_P_SESSIONID, sessionId).setParam("storeName", defaultMemoryStore)
                        .setParam("userId", sessionUserIds.getOrDefault(sessionId, "default"))
                            .setParam("agentName", name)
                        .setParam(AI_P_MEMORYKEY, "chat_" + System.currentTimeMillis())
                        .setParam(AI_P_MEMORYVALUE, userMessage + " → " + finalResponse)
                        .setParam(AI_P_MEMORYTAG, "chat_history")
                        .setParam("maxSeq", getMaxSeq(sessionId));
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
                    // 拒绝标志：供 ToolExecutor/父 agent 结构化识别，不依赖 LLM 读懂文案
                    .setParam("rejected", rejected)
                    .setParam("rejectReason", rejectedReason != null ? rejectedReason : "")
                    .setParam(AI_P_PROMPTTOKENS, (int) turn[0])
                    .setParam(AI_P_COMPLETIONTOKENS, (int) turn[1])
                    .setParam(AI_P_TOTALTOKENS, (int) turn[2])
                    .setParam(AI_P_PROMPTTOKENS_TOTAL, (int) acc[0])
                    .setParam(AI_P_COMPLETIONTOKENS_TOTAL, (int) acc[1])
                    .setParam(AI_P_TOTALTOKENS_TOTAL, (int) acc[2])
                    // 缓存统计（本次 chat）
                    .setParam(AI_P_CACHECREATIONTOKENS, (int) cacheTurn[0])
                    .setParam(AI_P_CACHEHITTOKENS, (int) cacheTurn[1])
                    .setParam(AI_P_CACHEMISSTOKENS, (int) cacheTurn[2])
                    .setParam(AI_P_ROUNDID, roundId);
            // 会话累计缓存统计（从 Provider 读取快照）
            if (llmProvider != null) {
                long[] sessionCache = llmProvider.getSessionCacheStats(sessionId);
                ret.setParam(AI_P_CACHECREATIONTOKENS_TOTAL, (int) sessionCache[0]);
                ret.setParam(AI_P_CACHEHITTOKENS_TOTAL, (int) sessionCache[1]);
                ret.setParam(AI_P_CACHEMISSTOKENS_TOTAL, (int) sessionCache[2]);
            }
            if (truncated) ret.setParam(AI_P_TRUNCATED, true);
            // 直出短路轮：返回消息重打标志，把下游直出意图沿委托链向上传播（executeAsTool 透传）
            if (finalDirect) ret.setParam(AI_P_FINALANSWER, true);
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
            // 收尾 SessionManager，避免已发 sessionUpdated 的 checkpoint 轮次残留
            notifyChatError(sessionId, msg, history, msgStartIdx, roundId, userMessage,
                    "Agent error: " + e.getMessage());
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
            sessionUserIds.remove(sessionId);
            // 清除中断标志（可能来自 worker.interrupt() 或 provider 回调），
            // 防止返回 ThreadTask 后继续传播导致后续模块误抛 InterruptedException
            while (Thread.interrupted()) { /* drain all pending interrupt flags */ }
        }
    }

    /** 流式LLM调用——使用配置文件中注册的streamCallback模块。返回 RESULT=false + 错误文案表示失败 */
    private TLMsg doStreamCall(List<TLConversationHistory> history,
                               List<TLFunctionDefinition> toolDefs,
                               String sessionId, String model) {
        TLBaseModule cb = getModule("streamCallback") instanceof TLBaseModule
                ? (TLBaseModule) getModule("streamCallback") : null;
        if (cb == null) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, "流式回调模块未注册（streamCallback）");
        }

        putMsg(cb, createMsg().setAction(STREAM_RESET));
        TLMsg sm = createMsg().setAction(LLM_COMPLETIONSTREAM)
                .setParam(AI_P_MESSAGEHISTORY, history).setParam(AI_P_FUNCTIONDEFS, toolDefs)
                .setParam(AI_P_MODEL, model).setParam(AI_P_SESSIONID, sessionId)
                .setParam(AI_P_ROUNDID, sessionRoundIds.getOrDefault(sessionId, ""))
                .setParam(RESULTFOR, "streamCallback").setParam(RESULTACTION, STREAM_ONCHUNK);
        // 全链追踪：流式 LLM 请求打点（本方法跑在 doChat 线程，ThreadLocal 有效；payload = 实际发送的 messages）
        traceStage(sessionId, currentRootSessionId.get() != null ? currentRootSessionId.get() : sessionId,
                sessionRoundIds.getOrDefault(sessionId, ""), "llmRequest",
                "stream model=" + model, 0, formatHistoryForTrace(history), null);
        putMsg(llmProvider, sm);

        TLMsg wr = putMsg(cb, createMsg().setAction(STREAM_WAITFORSTREAM).setParam("timeout", 120));
        if (wr == null) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, "流式回调无响应");
        }
        // Provider 中途出错：waitForStream 的 streamError 携带错误信息
        String streamErr = wr.getStringParam(AI_P_STREAMERROR, "");
        if (!streamErr.isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, "流式中断: " + streamErr);
        }
        // 等待超时或未收到 done 信号
        if (wr.parseBoolean("timedOut", false) || !wr.parseBoolean("streamDone", false)) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, "流式超时/未完成");
        }
        // 提取流式 reasoning 内容
        if (wr.containsParam(AI_P_REASONING)) {
            String streamReasoning = wr.getStringParam(AI_P_REASONING, "");
            if (!streamReasoning.isEmpty()) {
                history.add(TLConversationHistory.createReasoning(streamReasoning));
            }
        }
        // 全链追踪：流式 LLM 响应打点
        traceStage(sessionId, currentRootSessionId.get() != null ? currentRootSessionId.get() : sessionId,
                sessionRoundIds.getOrDefault(sessionId, ""), "llmResponse",
                "stream done", 0,
                formatResponseForTrace(wr.getStringParam("content", ""), null,
                        wr.containsParam(AI_P_REASONING) ? wr.getStringParam(AI_P_REASONING, "") : null),
                null);
        return createMsg().setParam(RESULT, true)
                .setParam(AI_P_RESPONSE, wr.getStringParam("content", ""));
    }

    /**
     * 流式chat（异步，通过回调发送chunks）。
     * 直接将调用者的回调目标传给Provider，避免中间转发。
     */
    @SuppressWarnings("unchecked")
    protected TLMsg chatStream(Object fromWho, TLMsg msg) {
        String sessionId = String.valueOf(msg.getSystemParam(AI_P_SESSIONID, "default"));
        // 取消标志复位（与 doChat 同语义）：/stop 经 stopChat() 置 true，onStreamResult 回调线程读取；
        // 不复位的话，上次停止的残留 true 会把后续正常对话误判为已停止
        cancelFlags.computeIfAbsent(sessionId, k -> new java.util.concurrent.atomic.AtomicBoolean()).set(false);
        // 根会话 ID：与 doChat 同语义（上游透传，默认=sessionId）；回调线程打点经 sessionRootIds 查表
        String rootSid = String.valueOf(msg.getSystemParam("rootSessionId", sessionId));
        String userMessage = msg.getStringParam(AI_P_USERMESSAGE, "");
        // 最终回调目标（显示层）
        String resultFor = msg.getStringParam(RESULTFOR,
                fromWho instanceof String ? (String) fromWho : "caller");
        String resultAction = msg.getStringParam(RESULTACTION, "onStreamChunk");
        // 流式转发目标：onStreamResult 将 chunk/完成信号转发到此
        String fwdTarget = msg.getStringParam("_streamResultFor", resultFor);
        String fwdAction = msg.getStringParam("_streamResultAction", resultAction);

        // 生成 roundId + 保存转发目标，供 onStreamResult 使用
        String roundId = sessionId + "_" + System.currentTimeMillis();
        sessionRoundIds.put(sessionId, roundId);
        // userId 入会话表（onStreamResult 跑在 provider 回调线程，ThreadLocal 不可用）
        sessionUserIds.put(sessionId, String.valueOf(msg.getSystemParam(AI_P_USERID,
                msg.getStringParam(AI_P_USERID, "default"))));
        // 本轮用户消息入会话表（收尾 chatFinished 保存会话时用）
        sessionUserMessages.put(sessionId, userMessage);
        sessionRootIds.put(sessionId, rootSid);
        // 全链追踪：流式轮次开始打点（payload = 用户输入全文；回调线程打点需显式 userId）
        traceStage(sessionId, rootSid, roundId, "roundStart", userMessage, 0,
                userMessage, sessionUserIds.get(sessionId));
        if (!fwdTarget.equals(getName())) {
            streamForwardMap.put(sessionId, new String[]{fwdTarget, fwdAction});
        }

        if (llmProvider == null) {
            cleanupStreamState(sessionId);
            TLMsg errMsg = createMsg().setAction(fwdAction)
                    .setParam(AI_P_STREAMERROR, "No LLM provider configured")
                    .setParam(AI_P_SESSIONID, sessionId);
            putMsg(fwdTarget, errMsg);
            return null;
        }

        // ==== 断点恢复检查（与 doChat 同语义；resume 参数由 agentService 透传） ====
        // 历史数据由调用方（agentService）从 SessionManager 加载好，通过 msg 参数传入。
        // 流式入口此前不支持 resume：恢复请求实际从空/残留 context 起聊，等于没有断点续传。
        boolean resume = msg.parseBoolean("resume", false);
        List<TLConversationHistory> fullHistory;
        if (resume && msg.containsParam("history")) {
            @SuppressWarnings("unchecked")
            List<TLConversationHistory> loadedHistory =
                    (List<TLConversationHistory>) msg.getParam("history");
            String resumeState = msg.getStringParam("resumeState", SESSION_STATE_CHECKPOINT);
            if (SESSION_STATE_PENDING_APPROVAL.equals(resumeState)) {
                // ==== 审批断点恢复（流式）：context system 置顶（DB 历史不含，恢复必须显式补） ====
                fullHistory = withContextSystem(loadedHistory);
                TLToolCall savedTc = msg.containsParam("pendingToolCall")
                        ? (TLToolCall) msg.getParam("pendingToolCall") : null;
                String decision = msg.getStringParam(AI_P_APPROVAL_DECISION, "pending");
                if ("approved".equals(decision) && savedTc != null) {
                    // 批准：重新执行被审批的工具，结果入历史（与 doChat 审批恢复一致）
                    TLMsg frResolveMsg = putMsg(toolManager, createMsg().setAction(AGENT_RESOLVETOOLCALLS)
                            .setParam(AI_P_TOOLCALLS, savedTc));
                    List<TLToolExecutor.ToolTask> singleTask = (List<TLToolExecutor.ToolTask>)
                            frResolveMsg.getListParam("tasks", new ArrayList<>());
                    String execId2 = sessionId + "_resume_" + System.nanoTime();
                    TLMsg singleExecResult = singleTask.isEmpty() ? null
                            : putMsg(toolExecutor, createMsg().setAction(TODOOLEXECUTE)
                                    .setParam("tasks", singleTask)
                                    .setSystemParam("executionId", execId2)
                                    .setSystemParam(AI_P_SESSIONID, sessionId)
                                    .setSystemParam("userId", msg.getSystemParam("userId", "default"))
                                    .setSystemParam("rootSessionId", rootSid)
                                    .setSystemParam(AI_P_ROUNDID, roundId));
                    String singleOutput = "";
                    if (singleExecResult != null && singleExecResult.parseBoolean("success", false)) {
                        List<TLToolExecutor.ToolResult> singleResults = (List<TLToolExecutor.ToolResult>)
                                singleExecResult.getListParam("results", null);
                        singleOutput = (singleResults != null && !singleResults.isEmpty())
                                ? singleResults.get(0).output : "";
                    }
                    fullHistory.add(new TLConversationHistory(savedTc.getId(),
                            savedTc.getFunctionName(), singleOutput));
                    putLog("Approval resumed (stream): approved → tool executed: " + savedTc.getFunctionName(),
                            LogLevel.INFO);
                } else if ("rejected".equals(decision)) {
                    String reason = msg.getStringParam(AI_P_APPROVAL_REJECTREASON, "用户拒绝");
                    fullHistory.add(new TLConversationHistory(
                            TLConversationHistory.Role.user,
                            "（上一操作已被拒绝：" + reason + "。请寻找替代方案。）"));
                    putLog("Approval resumed (stream): rejected → reason=" + reason, LogLevel.INFO);
                } else {
                    putLog("Approval still pending on stream resume: approvalId="
                            + msg.getStringParam(AI_P_APPROVAL_ID, ""), LogLevel.WARN);
                    cleanupStreamState(sessionId);
                    return null;
                }
            } else {
                // L2: mid-loop checkpoint 恢复 / L1: completed 会话恢复
                // context system 置顶（DB/checkpoint 增量不含，恢复必须显式补）
                fullHistory = withContextSystem(loadedHistory);
            }
            // 本轮增量起点 = 加载历史大小（收尾 chatFinished 的 messages 按此截取）
            sessionMsgStartIdx.put(sessionId, fullHistory.size());
            putLog("Stream resumed from checkpoint: sessionId=" + sessionId
                    + " historySize=" + fullHistory.size(), LogLevel.INFO);
        } else {
            // 正常流程：从 context 构建历史（全量；摘要模式不 trim）
            fullHistory = getContextHistory(sessionId);
            // 记录本轮增量起点（chatFinished 的 messages 只存本轮新增，避免全量累积导致恢复重复）
            sessionMsgStartIdx.put(sessionId, fullHistory.size());
        }
        // ==== 记忆召回注入（流式路径；非流式在 doChat 同逻辑） ====
        // beforeMsgTable 钩子（chatStream → recallAgentMemory）的返回经 PRERESULT 进入 systemArgs
        // 记忆每轮注入（原始设计，同 doChat）；"写诗被算进下一轮"的根因是直出跳过上下文保存，已单独修复
        // 记忆 system 只进发送视图（buildSendList 注入），不写回 context——全量保留下避免累积污染
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
        long coverSeq = computeSessionCoverSeq(beforeResult, sessionId);
        sessionCoverSeq.put(sessionId, coverSeq);
        if (memoryContext != null && !memoryContext.isEmpty()) {
            sessionMemoryContext.put(sessionId, memoryContext);
        }
        // 发送视图 = 摘要 coverSeq 衔接的原始视图 + 记忆 system；保存列表 = 全量 + user
        List<TLConversationHistory> history = buildSendList(fullHistory, coverSeq, memoryContext);
        // 恢复场景：断点历史已含断点用户消息（loadSession 返回），不重复添加
        if (!resume) history.add(new TLConversationHistory(TLConversationHistory.Role.user, userMessage));
        // 立即保存全量+用户消息的上下文（msgTool 如 getTurnCount 读当前会话；恢复/续轮不丢全量）
        List<TLConversationHistory> saved = new ArrayList<>(fullHistory);
        if (!resume) saved.add(new TLConversationHistory(TLConversationHistory.Role.user, userMessage));
        saveContextHistory(sessionId, saved);
        // 对话开始即保存 checkpoint 轮（含用户消息）：进程意外死亡时轮次残留，
        // 重启后可恢复本会话历史（纯文本/首响应阶段被杀不再丢失最后一条消息）；
        // 正常完成/人为停止由 chatFinished/chatAborted 同 roundId 覆盖为 completed
        notifySessionManager(createMsg()
                .setAction("sessionUpdated")
                .setParam("sessionId", sessionId)
                .setParam("userId", sessionUserIds.getOrDefault(sessionId, "default"))
                .setParam("agentName", name)
                .setParam("roundId", sessionRoundIds.getOrDefault(sessionId, ""))
                .setParam("messages", deltaMessages(saved, sessionMsgStartIdx.getOrDefault(sessionId, 0)))
                .setParam("model", msg.getStringParam(AI_P_MODEL, llmProvider.getDefaultModel()))
                .setParam("temperature", defaultTemperature)
                .setParam("maxTokens", defaultMaxTokens)
                .setParam("userMessage", sessionUserMessages.getOrDefault(sessionId, "")));
        // 黑盒消息接口之一：向工具模块索取 LLM 函数定义列表
        TLMsg frDefsMsg = putMsg(toolManager, createMsg().setAction(AGENT_GETFUNCTIONDEFS));
        List<TLFunctionDefinition> toolDefs = (List<TLFunctionDefinition>)
                frDefsMsg.getListParam(AI_P_FUNCTIONDEFS, new ArrayList<>());

        // 回调目标设为本 agent（走 onStreamResult），最终调用方通过 _streamResultFor 指定
        TLMsg streamMsg = createMsg()
                .setAction(LLM_COMPLETIONSTREAM)
                .setParam(AI_P_MESSAGEHISTORY, history)
                .setParam(AI_P_FUNCTIONDEFS, toolDefs)
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam(AI_P_ROUNDID, sessionRoundIds.getOrDefault(sessionId, ""))
                .setParam(RESULTFOR, getName())
                .setParam(RESULTACTION, "onStreamResult");

        if (msg.containsParam(AI_P_MODEL))
            streamMsg.setParam(AI_P_MODEL, msg.getParam(AI_P_MODEL));
        if (msg.containsParam(AI_P_TEMPERATURE))
            streamMsg.setParam(AI_P_TEMPERATURE, msg.getParam(AI_P_TEMPERATURE));
        // 断点恢复参数（agentService loadSession 透传）：resumeModel/resumeTemperature 优先于默认值
        if (resume && msg.containsParam("resumeModel"))
            streamMsg.setParam(AI_P_MODEL, msg.getStringParam("resumeModel", ""));
        if (resume && msg.containsParam("resumeTemperature"))
            streamMsg.setParam(AI_P_TEMPERATURE, msg.getDoubleParam("resumeTemperature", 0.7));

        // 全链追踪：流式 LLM 请求打点（payload = 实际发送给 LLM 的 messages，含记忆注入与用户消息）
        traceStage(sessionId, rootSid, roundId, "llmRequest",
                "stream model=" + (msg.containsParam(AI_P_MODEL)
                        ? msg.getStringParam(AI_P_MODEL, "") : llmProvider.getDefaultModel()),
                0, formatHistoryForTrace(history), sessionUserIds.get(sessionId));
        try {
            putMsg(llmProvider, streamMsg);
        } catch (Exception e) {
            // 流启动失败：清理状态并转发错误（与 Provider 错误路径一致，避免泄漏）
            cleanupStreamState(sessionId);
            try {
                TLMsg errMsg = createMsg().setAction(fwdAction)
                        .setParam(AI_P_STREAMERROR, "Stream start failed: " + e.getMessage())
                        .setParam(AI_P_SESSIONID, sessionId);
                putMsg(fwdTarget, errMsg);
            } catch (Exception ignored) {
                // 转发失败不再抛出，调用方通过无回调获知
            }
        }
        return null; // 异步
    }

    /** 统一清理流式会话状态（streamForwardMap/sessionRoundIds/sessionMsgStartIdx/sessionUserIds），异常路径也不泄漏 */
    private void cleanupStreamState(String sessionId) {
        streamForwardMap.remove(sessionId);
        sessionRoundIds.remove(sessionId);
        sessionMsgStartIdx.remove(sessionId);
        sessionUserIds.remove(sessionId);
        sessionUserMessages.remove(sessionId);
        sessionRootIds.remove(sessionId);
        sessionCoverSeq.remove(sessionId);
        sessionMemoryContext.remove(sessionId);
        // 流式轮收尾：注销即触发 monitor 端该会话的 DB flush（chatStream 不 register，不影响 runningAgents 语义）
        putMsg(M_AGENTMONITOR, createMsg().setAction("unregister")
                .setParam(AI_P_SESSIONID, sessionId));
    }

    /**
     * 处理流式LLM响应结果（回调）。
     * 当流式响应包含tool_calls时，自动执行skills并继续chat循环，
     * 最终将完整的文本响应转发给原始调用者。
     */
    @SuppressWarnings("unchecked")
    protected TLMsg onStreamResult(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        // 人为停止标志（stopChat 置 true；与 doChat 的 cancelled 同一引用，回调线程读取）
        java.util.concurrent.atomic.AtomicBoolean cancelled =
                cancelFlags.computeIfAbsent(sessionId, k -> new java.util.concurrent.atomic.AtomicBoolean());
        // 查表获取转发目标（chatStream 存、此处取；若调用方未设则 fallback 到 msg 参数）
        String[] fwd = streamForwardMap.get(sessionId);
        String resultFor = fwd != null ? fwd[0]
                : msg.getStringParam("_streamResultFor", "caller");
        String resultAction = fwd != null ? fwd[1]
                : msg.getStringParam("_streamResultAction", "onStreamChunk");
        // userId 来源：sessionUserIds 表（chatStream/doChat 入口写入；本方法跑在 provider 回调线程，ThreadLocal 不可用）
        String streamUserId = sessionUserIds.getOrDefault(sessionId,
                msg.getStringParam(AI_P_USERID, "default"));

        // 转发chunk或完成信号给最终调用方（TLChatConsole 等）
        if (msg.parseBoolean(AI_P_STREAMDONE, false)) {
            String streamedText = msg.getStringParam(AI_P_RESPONSE, "");
            boolean hasToolCalls = msg.parseBoolean("hasToolCalls", false);
            List<TLToolCall> toolCalls = (List<TLToolCall>) msg.getListParam(AI_P_TOOLCALLS, null);

            // 全链追踪：流式 LLM 响应打点（回调线程：rootSid/roundId 查表，userId 显式传）
            String rootSid = sessionRootIds.getOrDefault(sessionId, sessionId);
            String roundId = sessionRoundIds.getOrDefault(sessionId, "");
            traceStage(sessionId, rootSid, roundId, "llmResponse", "stream done", 0,
                    formatResponseForTrace(streamedText, hasToolCalls ? toolCalls : null,
                            msg.containsParam(AI_P_REASONING) ? msg.getStringParam(AI_P_REASONING, "") : null),
                    streamUserId);

            try {
                if (hasToolCalls && toolCalls != null && !toolCalls.isEmpty()) {
                    // 人为停止检查：停止后不执行工具，收尾为 completed（断点只用于意外死机）
                    if (cancelled.get()) {
                        notifySessionManager(createMsg()
                                .setAction("chatAborted")
                                .setParam("sessionId", sessionId)
                                .setParam("userId", streamUserId)
                                .setParam("agentName", name)
                                .setParam("roundId", sessionRoundIds.getOrDefault(sessionId, ""))
                                .setParam("messages", deltaMessages(getContextHistory(sessionId),
                                        sessionMsgStartIdx.getOrDefault(sessionId, 0)))
                                .setParam("userMessage", sessionUserMessages.getOrDefault(sessionId, "")));
                        forwardStreamFinal(resultAction, resultFor, sessionId, "⏹ 已中断");
                        return null;
                    }
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

                        // 并行执行tool calls（含拒绝标志：被拒则停止续跑并转发拒绝文案）
                        TLMsg execResult = execToolsViaExecutor(toolCalls, fromWho, sessionId,
                                history, streamUserId);
                        if (handleStreamRejected(execResult, history, sessionId)) {
                            // 拒绝收尾：保存上下文，避免本轮 tool 结果丢失导致下一轮上下文不完整
                            saveContextHistory(sessionId, history);
                            // 全链追踪：拒绝轮收尾（payload = 拒绝文案）
                            traceStage(sessionId, rootSid, roundId, "roundEnd", "rejected", 0,
                                    execResult.getStringParam("finalResponse", "⚠️ 操作已被用户拒绝。"), streamUserId);
                            notifyStreamChatFinished(fromWho, sessionId, history, streamUserId,
                                    execResult.getStringParam("finalResponse", "⚠️ 操作已被用户拒绝。"));
                            forwardStreamFinal(resultAction, resultFor, sessionId,
                                    execResult.getStringParam("finalResponse", "⚠️ 操作已被用户拒绝。"));
                            return null;
                        }
                        // L2 checkpoint: 首个工具执行后通知 SessionManager（与续跑循环内对称）——
                        // 单工具轮是常见场景：kill 时若无此轮残留，断点无法恢复
                        notifySessionManager(createMsg()
                                .setAction("sessionUpdated")
                                .setParam("sessionId", sessionId)
                                .setParam("userId", streamUserId)
                                .setParam("agentName", name)
                                .setParam("roundId", sessionRoundIds.getOrDefault(sessionId, ""))
                                .setParam("messages", deltaMessages(history,
                                        sessionMsgStartIdx.getOrDefault(sessionId, 0)))
                                .setParam("model", msg.getStringParam(AI_P_MODEL, llmProvider.getDefaultModel()))
                                .setParam("temperature", defaultTemperature)
                                .setParam("maxTokens", defaultMaxTokens)
                                .setParam("userMessage", sessionUserMessages.getOrDefault(sessionId, "")));
                        // 直出短路（流式路径）：单工具轮且带 finalAnswer → 全文直达，不再续跑 LLM
                        if (execResult != null && execResult.parseBoolean(AI_P_FINALANSWER, false)
                                && (toolCalls == null || toolCalls.size() == 1)) {
                            // 直出前保存上下文（此前缺失：直出直接 return 跳过保存，
                            // 导致下一轮 getContextHistory 拿不到本轮完整 history——LLM 只能靠记忆，
                            // 把上轮任务（如写诗）误当新指令）
                            saveContextHistory(sessionId, history);
                            // 全链追踪：直出轮收尾（payload = 直出全文）
                            traceStage(sessionId, rootSid, roundId, "roundEnd", "completed", 0,
                                    execResult.getStringParam("finalResponse", ""), streamUserId);
                            // 收尾通知 SessionManager + 记忆（直出路径此前缺 chatFinished → 恢复会话时该轮缺失）
                            notifyStreamChatFinished(fromWho, sessionId, history, streamUserId,
                                    execResult.getStringParam("finalResponse", ""));
                            forwardStreamFinal(resultAction, resultFor, sessionId,
                                    execResult.getStringParam("finalResponse", ""));
                            return null;
                        }

                        // 非流式继续LLM循环（支持后续tool calls）
                        String finalResponse = null;
                        int iteration = 1;
                        while (iteration < maxToolCallIterations) {
                            iteration++;
                            // 人为停止检查：stopChat 后不再续跑，收尾为 completed
                            //（断点恢复只针对意外死机；人为停止本轮视为结束，不留断点）
                            if (cancelled.get()) {
                                notifySessionManager(createMsg()
                                        .setAction("chatAborted")
                                        .setParam("sessionId", sessionId)
                                        .setParam("userId", streamUserId)
                                        .setParam("agentName", name)
                                        .setParam("roundId", sessionRoundIds.getOrDefault(sessionId, ""))
                                        .setParam("messages", deltaMessages(history,
                                                sessionMsgStartIdx.getOrDefault(sessionId, 0)))
                                        .setParam("userMessage", sessionUserMessages.getOrDefault(sessionId, "")));
                                traceStage(sessionId, rootSid, roundId, "roundEnd", "aborted", 0,
                                        "⏹ 已中断", streamUserId);
                                forwardStreamFinal(resultAction, resultFor, sessionId, "⏹ 已中断");
                                return null;
                            }
                            // 黑盒消息接口之一：向工具模块索取 LLM 函数定义列表
            TLMsg frDefsMsg = putMsg(toolManager, createMsg().setAction(AGENT_GETFUNCTIONDEFS));
            List<TLFunctionDefinition> toolDefs = (List<TLFunctionDefinition>)
                    frDefsMsg.getListParam(AI_P_FUNCTIONDEFS, new ArrayList<>());
                            TLMsg llmMsg = createMsg().setAction(LLM_COMPLETION)
                                    .setParam(AI_P_MESSAGEHISTORY, buildSendList(history,
                                            sessionCoverSeq.getOrDefault(sessionId, 0L),
                                            sessionMemoryContext.get(sessionId)))
                                    .setParam(AI_P_MODEL, msg.getStringParam(AI_P_MODEL, llmProvider.getDefaultModel()))
                                    .setParam(AI_P_TEMPERATURE, defaultTemperature)
                                    .setParam(AI_P_MAXTOKENS, defaultMaxTokens);
                            if (toolDefs != null && !toolDefs.isEmpty()) {
                                llmMsg.setParam(AI_P_FUNCTIONDEFS, new ArrayList<>(toolDefs));
                            }

                            // 全链追踪：流式续跑循环的 LLM 请求打点（payload = 含工具结果的 messages）
                            traceStage(sessionId, rootSid, roundId, "llmRequest",
                                    "iter=" + iteration + " model=" + msg.getStringParam(AI_P_MODEL, llmProvider.getDefaultModel()),
                                    0, formatHistoryForTrace(history), streamUserId);
                            TLMsg llmResponse = putMsg(llmProvider, llmMsg);
                            if (!llmResponse.parseBoolean(RESULT, false)) break;
                            // Token 累加（流式后续的非流式循环；首个流式响应的 usage 需 include_usage，暂不覆盖）
                            long sp = llmResponse.getIntParam(AI_P_PROMPTTOKENS, 0);
                            long sc = llmResponse.getIntParam(AI_P_COMPLETIONTOKENS, 0);
                            long st = llmResponse.getIntParam(AI_P_TOTALTOKENS, 0);
                            accumulateTokenUsage(sessionId, new long[]{sp, sc, st});
                            reportProcessTokenUsage(new long[]{sp, sc, st},
                                    new long[]{llmResponse.getLongParam(AI_P_CACHECREATIONTOKENS, 0L),
                                            llmResponse.getLongParam(AI_P_CACHEHITTOKENS, 0L),
                                            llmResponse.getLongParam(AI_P_CACHEMISSTOKENS, 0L)},
                                    sessionId, sessionId,
                                    sessionUserIds.getOrDefault(sessionId,
                                            msg.getStringParam(AI_P_USERID, "default")));

                            boolean moreToolCalls = llmResponse.parseBoolean("hasToolCalls", false);
                            List<TLToolCall> moreTCs = (List<TLToolCall>) llmResponse.getListParam(AI_P_TOOLCALLS, null);

                            // 全链追踪：流式续跑循环的 LLM 响应打点
                            traceStage(sessionId, rootSid, roundId, "llmResponse",
                                    "tokens=" + sc + "/" + st, 0,
                                    formatResponseForTrace(
                                            llmResponse.getStringParam(AI_P_RESPONSE, ""),
                                            moreToolCalls ? moreTCs : null,
                                            llmResponse.getStringParam(AI_P_REASONING, "")),
                                    streamUserId);

                            if (!moreToolCalls || moreTCs == null || moreTCs.isEmpty()) {
                                finalResponse = llmResponse.getStringParam(AI_P_RESPONSE, "");
                                // 空响应：历史里放占位符（与 doChat 流式/非流式分支一致——空 assistant
                                // 消息会被 DeepSeek 400 拒绝，且随上下文存续污染后续回合）
                                history.add(new TLConversationHistory(TLConversationHistory.Role.assistant,
                                        finalResponse.isEmpty() ? "（无输出）" : finalResponse));
                                break;
                            }

                            TLConversationHistory aMsg2 = new TLConversationHistory(
                                    TLConversationHistory.Role.assistant, new ArrayList<>(moreTCs));
                            if (llmResponse.containsParam(AI_P_RESPONSE))
                                aMsg2.setContent(llmResponse.getStringParam(AI_P_RESPONSE, null));
                            history.add(aMsg2);

                            TLMsg moreExecResult = execToolsViaExecutor(moreTCs, fromWho, sessionId,
                                    history, streamUserId);
                            // L2 checkpoint: 流式续跑每轮工具执行后通知 SessionManager
                            //（意外死机时 checkpoint 轮残留 → 重启可断点恢复；
                            //  人为停止由后续 chatAborted 覆盖为 completed）
                            notifySessionManager(createMsg()
                                    .setAction("sessionUpdated")
                                    .setParam("sessionId", sessionId)
                                    .setParam("userId", streamUserId)
                                    .setParam("agentName", name)
                                    .setParam("roundId", sessionRoundIds.getOrDefault(sessionId, ""))
                                    .setParam("messages", deltaMessages(history,
                                            sessionMsgStartIdx.getOrDefault(sessionId, 0)))
                                    .setParam("model", msg.getStringParam(AI_P_MODEL, llmProvider.getDefaultModel()))
                                    .setParam("temperature", defaultTemperature)
                                    .setParam("maxTokens", defaultMaxTokens)
                                    .setParam("userMessage", sessionUserMessages.getOrDefault(sessionId, "")));
                            if (handleStreamRejected(moreExecResult, history, sessionId)) {
                                saveContextHistory(sessionId, history);
                                // 全链追踪：续跑循环拒绝收尾
                                traceStage(sessionId, rootSid, roundId, "roundEnd", "rejected", 0,
                                        moreExecResult.getStringParam("finalResponse", "⚠️ 操作已被用户拒绝。"), streamUserId);
                                notifyStreamChatFinished(fromWho, sessionId, history, streamUserId,
                                        moreExecResult.getStringParam("finalResponse", "⚠️ 操作已被用户拒绝。"));
                                forwardStreamFinal(resultAction, resultFor, sessionId,
                                        moreExecResult.getStringParam("finalResponse", "⚠️ 操作已被用户拒绝。"));
                                return null;
                            }
                            // 直出短路（流式续跑循环）：单工具轮且带 finalAnswer → 全文直达
                            if (moreExecResult != null && moreExecResult.parseBoolean(AI_P_FINALANSWER, false)
                                    && (moreTCs == null || moreTCs.size() == 1)) {
                                saveContextHistory(sessionId, history);
                                // 全链追踪：续跑循环直出收尾
                                traceStage(sessionId, rootSid, roundId, "roundEnd", "completed", 0,
                                        moreExecResult.getStringParam("finalResponse", ""), streamUserId);
                                notifyStreamChatFinished(fromWho, sessionId, history, streamUserId,
                                        moreExecResult.getStringParam("finalResponse", ""));
                                forwardStreamFinal(resultAction, resultFor, sessionId,
                                        moreExecResult.getStringParam("finalResponse", ""));
                                return null;
                            }
                        }
                        if (finalResponse == null) finalResponse = "Reached max iterations (" + maxToolCallIterations + ")";

                        // 保存上下文 + 通知 SessionManager + 长期记忆
                        saveContextHistory(sessionId, history);
                        notifySessionManager(createMsg()
                                .setAction("chatFinished")
                                .setParam("sessionId", sessionId)
                                .setParam("userId", streamUserId)
                                .setParam("agentName", name)
                                .setParam("roundId", sessionRoundIds.getOrDefault(sessionId, ""))
                                .setParam("messages", deltaMessages(history, sessionMsgStartIdx.getOrDefault(sessionId, 0)))
                                .setParam("userMessage", sessionUserMessages.getOrDefault(sessionId, ""))
                                .setParam("response", finalResponse != null ? finalResponse : streamedText));
                        try {
                            TLMsg saveMsg = createMsg().setAction(AGENT_SAVEMEMORY)
                                    .setParam(AI_P_SESSIONID, sessionId).setParam("storeName", defaultMemoryStore)
                                    .setParam("userId", streamUserId)
                                .setParam("agentName", name)
                                    .setParam(AI_P_MEMORYKEY, "chat_" + System.currentTimeMillis())
                                    .setParam(AI_P_MEMORYVALUE, streamedText + " → " + finalResponse)
                                    .setParam(AI_P_MEMORYTAG, "chat_history")
                                    .setParam("maxSeq", getMaxSeq(sessionId));
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
                        // 全链追踪：流式工具轮收尾（payload = 最终输出，优先续跑循环结果）
                        traceStage(sessionId, rootSid, roundId, "roundEnd", "completed", 0,
                                finalResponse != null && !finalResponse.isEmpty() ? finalResponse : streamedText,
                                streamUserId);
                        // 再发送完成信号
                        TLMsg doneMsg = createMsg()
                                .setAction(resultAction)
                                .setParam(AI_P_STREAMDONE, true)
                                .setParam(AI_P_SESSIONID, sessionId);
                        putMsg(resultFor, doneMsg);

                    } catch (Exception e) {
                        putLog("Stream tool call continuation error: " + e.toString(), LogLevel.ERROR);
                        TLMsg errMsg = createMsg()
                                .setAction(resultAction)
                                .setParam(AI_P_STREAMERROR, "Tool call processing error: " + e.getMessage())
                                .setParam(AI_P_SESSIONID, sessionId);
                        putMsg(resultFor, errMsg);
                    }
                } else {
                    // 无tool calls: 保存assistant回复到上下文，再转发完成信号
                    List<TLConversationHistory> currentHistory = getContextHistory(sessionId);
                    if (streamedText != null && !streamedText.isEmpty()) {
                        currentHistory.add(new TLConversationHistory(
                                TLConversationHistory.Role.assistant, streamedText));
                        saveContextHistory(sessionId, currentHistory);
                    }
                    // 收尾：通知 SessionManager 保存会话（与 tool call 路径对称）+ 保存长期记忆
                    notifySessionManager(createMsg()
                            .setAction("chatFinished")
                            .setParam("sessionId", sessionId)
                            .setParam("userId", streamUserId)
                            .setParam("agentName", name)
                            .setParam("roundId", sessionRoundIds.getOrDefault(sessionId, ""))
                            .setParam("messages", deltaMessages(currentHistory,
                                    sessionMsgStartIdx.getOrDefault(sessionId, 0)))
                            .setParam("userMessage", sessionUserMessages.getOrDefault(sessionId, ""))
                            .setParam("response", streamedText));
                    try {
                        TLMsg saveMsg = createMsg().setAction(AGENT_SAVEMEMORY)
                                .setParam(AI_P_SESSIONID, sessionId).setParam("storeName", defaultMemoryStore)
                                .setParam("userId", streamUserId)
                                .setParam("agentName", name)
                                .setParam(AI_P_MEMORYKEY, "chat_" + System.currentTimeMillis())
                                .setParam(AI_P_MEMORYVALUE, sessionUserMessages.getOrDefault(sessionId, "") + " → " + streamedText)
                                .setParam(AI_P_MEMORYTAG, "chat_history")
                                .setParam("maxSeq", getMaxSeq(sessionId));
                        saveAgentMemory(fromWho, saveMsg);
                    } catch (Exception e) {
                        putLog("Save memory failed: " + e.toString(), LogLevel.ERROR);
                    }
                    // 全链追踪：流式无工具轮收尾（payload = 最终输出）
                    traceStage(sessionId, rootSid, roundId, "roundEnd", "completed", 0,
                            streamedText, streamUserId);
                    TLMsg doneMsg = createMsg()
                            .setAction(resultAction)
                            .setParam(AI_P_STREAMDONE, true)
                            .setParam(AI_P_SESSIONID, sessionId);
                    putMsg(resultFor, doneMsg);
                }
            } finally {
                // 统一清理流式会话状态，确保异常路径也不泄漏
                cleanupStreamState(sessionId);
            }
        } else if (msg.containsParam(AI_P_STREAMERROR)) {
            // Provider 流式错误。人为停止（stopChat 置 cancelFlags）时 LLM 流被 cancel
            // 触发此分支：收尾为 completed 不留断点（主动停止=放弃执行，重新进入不再提示
            // 恢复）；非人为错误（网络故障/Provider 报错）保持 checkpoint，供恢复。
            if (cancelled.get()) {
                notifySessionManager(createMsg()
                        .setAction("chatAborted")
                        .setParam("sessionId", sessionId)
                        .setParam("userId", streamUserId)
                        .setParam("agentName", name)
                        .setParam("roundId", sessionRoundIds.getOrDefault(sessionId, ""))
                        .setParam("messages", deltaMessages(getContextHistory(sessionId),
                                sessionMsgStartIdx.getOrDefault(sessionId, 0)))
                        .setParam("userMessage", sessionUserMessages.getOrDefault(sessionId, "")));
                putLog("Chat aborted by user (stream cancel): sessionId=" + sessionId, LogLevel.INFO);
            }
            // 清理状态并转发
            cleanupStreamState(sessionId);
            TLMsg errMsg = createMsg()
                    .setAction(resultAction)
                    .setParam(AI_P_STREAMERROR, msg.getParam(AI_P_STREAMERROR))
                    .setParam(AI_P_SESSIONID, sessionId);
            putMsg(resultFor, errMsg);
        } else if (msg.containsParam(AI_P_CHUNK)) {
            // 转发chunk（中间事件，无生命周期状态需清理）
            TLMsg chunkMsg = createMsg()
                    .setAction(resultAction)
                    .setParam(AI_P_CHUNK, msg.getParam(AI_P_CHUNK))
                    .setParam(AI_P_SESSIONID, sessionId);
            putMsg(resultFor, chunkMsg);
        }

        return null;
    }

    /**
     * 向全局 moduleRegistry 注册子模块，以家族名字为 key。
     * registry 未配置时静默跳过（IGNOREMODULEISNULL）。
     * public：TLToolManager（工具管理内脏）经此注册 skill/agent。
     */
    public void registerToRegistry(String subName, Object module, String moduleType) {
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
     * 恢复历史构造：context 默认 system 置顶 + DB 历史。
     * DB 增量保存不含 index 0 的 context system，恢复必须显式补（先插再放，无需去重——
     * 每次从 DB 历史重新构造且 REPLACE 全量替换，不会累积重复）。
     * 子 agent（无 defaultSystemMessage，如 poetA/poetB）返回原列表，保持现状。
     */
    private List<TLConversationHistory> withContextSystem(List<TLConversationHistory> loadedHistory) {
        if (loadedHistory == null || loadedHistory.isEmpty()) return loadedHistory;
        HashMap<String, String> ctxParams = modulesParams.get(contextModuleName);
        String sysTpl = ctxParams != null ? ctxParams.get("defaultSystemMessage") : null;
        if (sysTpl == null || sysTpl.trim().isEmpty()) return loadedHistory;
        List<TLConversationHistory> h = new ArrayList<>(loadedHistory.size() + 1);
        h.add(new TLConversationHistory(TLConversationHistory.Role.system, sysTpl));
        h.addAll(loadedHistory);
        return h;
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
        // context system 置顶 + DB 历史（DB 不含 context system，直接替换会永久丢失）
        history = withContextSystem(history);
        // 恢复的历史可能含中断产生的残缺 tool 对（旧版本保存的）——先净化再入 context
        sanitizeToolPairs(history);
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
                .setParam(AI_P_MEMORYEXPTIME, msg.getIntParam(AI_P_MEMORYEXPTIME, -1))
                // 透传：agentName（摘要 LLM 借用目标）与 maxSeq（摘要 coverSeq 衔接）
                .setParam("agentName", msg.getStringParam("agentName", null))
                .setParam("maxSeq", msg.getIntParam("maxSeq", 0));
        return putMsg(memory, memMsg);
    }

    @SuppressWarnings("unchecked")
    protected TLMsg recallAgentMemory(Object fromWho, TLMsg msg) {
        // beforeMsgTable 钩子消息：doMsgList 会复制原 AGENT_CHAT 的 systemArgs，框架 ID 从系统参数区提
        String sessionId = String.valueOf(msg.getSystemParam(AI_P_SESSIONID, "default"));
        String userId = String.valueOf(msg.getSystemParam("userId", sessionId));
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
        putLog("Memory recall: session=" + sessionId + " entries=" + allEntries.size()
                + " stores=" + memoryStores.size(), LogLevel.DEBUG);
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

    /**
     * 流式直出/拒绝路径的收尾：通知 SessionManager 保存本轮 + 保存长期记忆。
     * 正常收尾在 onStreamResult 主流程；直出/拒绝直接 return 会跳过，导致该轮不入 rounds
     * （恢复会话时历史缺失）。
     */
    private void notifyStreamChatFinished(Object fromWho, String sessionId,
                                          List<TLConversationHistory> history,
                                          String streamUserId, String response) {
        notifySessionManager(createMsg()
                .setAction("chatFinished")
                .setParam("sessionId", sessionId)
                .setParam("userId", streamUserId)
                .setParam("agentName", name)
                .setParam("roundId", sessionRoundIds.getOrDefault(sessionId, ""))
                .setParam("messages", deltaMessages(history, sessionMsgStartIdx.getOrDefault(sessionId, 0)))
                .setParam("userMessage", sessionUserMessages.getOrDefault(sessionId, ""))
                .setParam("response", response));
        try {
            TLMsg saveMsg = createMsg().setAction(AGENT_SAVEMEMORY)
                    .setParam(AI_P_SESSIONID, sessionId).setParam("storeName", defaultMemoryStore)
                    .setParam("userId", streamUserId)
                    .setParam("agentName", name)
                    .setParam(AI_P_MEMORYKEY, "chat_" + System.currentTimeMillis())
                    .setParam(AI_P_MEMORYVALUE, sessionUserMessages.getOrDefault(sessionId, "") + " → " + response)
                    .setParam(AI_P_MEMORYTAG, "chat_history")
                    .setParam("maxSeq", getMaxSeq(sessionId));
            saveAgentMemory(fromWho, saveMsg);
        } catch (Exception e) {
            putLog("Save memory failed: " + e.toString(), LogLevel.ERROR);
        }
    }

    /** LLM 失败/异常路径收尾：把本轮存为 completed（含错误文案），避免已发 sessionUpdated 的 checkpoint 轮次残留 */
    private void notifyChatError(String sessionId, TLMsg msg, List<TLConversationHistory> history,
                                 int msgStartIdx, String roundId, String userMessage, String errMsg) {
        if (history == null) return; // 异常发生在 history 构建前：无 checkpoint 可残留，无需收尾
        notifySessionManager(createMsg()
                .setAction("chatFinished")
                .setParam("sessionId", sessionId)
                .setParam("userId", sessionUserIds.getOrDefault(sessionId, "default"))
                .setParam("agentName", name)
                .setParam("roundId", roundId)
                .setParam("messages", deltaMessages(history, msgStartIdx))
                .setParam("userMessage", userMessage)
                .setParam("response", errMsg));
        traceStage(sessionId, currentRootSessionId.get(), roundId, "roundEnd", "error", 0);
    }

    /** 轮次环节打点：发 recordStage 给监控模块（未配监控时静默忽略，IGNOREMODULEISNULL） */
    private void traceStage(String sessionId, String rootSid, String roundId,
                            String stage, String detail, long durationMs) {
        traceStage(sessionId, rootSid, roundId, stage, detail, durationMs, null, null);
    }

    /**
     * 轮次环节打点（带完整内容载荷）。payload 为多行可读文本（发送给 LLM 的 messages / LLM 返回等），
     * 不经 sanitizeDetail 截断，仅做防御性上限；userId 显式传入——流式回调线程无 ThreadLocal。
     */
    private void traceStage(String sessionId, String rootSid, String roundId,
                            String stage, String detail, long durationMs,
                            String payload, String userId) {
        try {
            TLMsg traceMsg = createMsg().setAction("recordStage")
                    .setParam("agentName", name)
                    .setParam(AI_P_SESSIONID, sessionId)
                    .setParam(AI_P_USERID, userId != null ? userId : currentChatUserId.get())
                    .setParam("rootSessionId", rootSid != null ? rootSid : sessionId)
                    .setParam(AI_P_ROUNDID, roundId)
                    .setParam("stage", stage)
                    .setParam("detail", TLAgentMonitor.sanitizeDetail(detail))
                    .setParam("durationMs", durationMs);
            // payload 非空才挂参（防空消息膨胀）；防御性再截断
            if (payload != null && !payload.isEmpty()) {
                traceMsg.setParam("payload",
                        payload.length() > 8000 ? payload.substring(0, 8000) + "…(已截断)" : payload);
            }
            traceMsg.setSystemParam(IGNOREMODULEISNULL, true);
            putMsg(M_AGENTMONITOR, traceMsg);
        } catch (Exception ignored) {}
    }

    // ======================== /trace llm 完整链路内容格式化 ========================

    /** 单条消息 content 展示上限（字符） */
    private static final int TRACE_MSG_MAX = 500;
    /** 单条 payload 总量上限（字符） */
    private static final int TRACE_TOTAL_MAX = 6000;

    private static String truncateText(String s, int max) {
        if (s == null || s.isEmpty()) return "";
        return s.length() > max ? s.substring(0, max) + "…(已截断)" : s;
    }

    /** 把发送给 LLM 的 messages 渲染为多行可读文本（/trace llm 的 llmRequest payload） */
    private String formatHistoryForTrace(List<TLConversationHistory> history) {
        if (history == null || history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (TLConversationHistory h : history) {
            if (h == null) continue;
            String line;
            switch (h.getRole()) {
                case system:
                    line = "[system] " + truncateText(h.getContent(), TRACE_MSG_MAX);
                    break;
                case user:
                    line = "[user] " + truncateText(h.getContent(), TRACE_MSG_MAX);
                    break;
                case assistant:
                    if (h.isAssistantWithToolCalls()) {
                        StringBuilder b = new StringBuilder("[assistant]");
                        if (h.getContent() != null && !h.getContent().isEmpty()) {
                            b.append(" ").append(truncateText(h.getContent(), TRACE_MSG_MAX));
                        }
                        b.append("\n");
                        appendToolCalls(b, h.getToolCalls(), "    ");
                        line = b.toString();
                    } else {
                        line = "[assistant] " + truncateText(h.getContent(), TRACE_MSG_MAX);
                    }
                    break;
                case tool:
                    line = "[tool:" + (h.getName() != null && !h.getName().isEmpty() ? h.getName() : h.getToolCallId())
                            + "] " + truncateText(h.getContent(), TRACE_MSG_MAX);
                    break;
                case reasoning:
                    line = "[reasoning] " + truncateText(h.getReasoningContent(), TRACE_MSG_MAX);
                    break;
                default:
                    continue;
            }
            sb.append(line).append("\n");
            if (sb.length() > TRACE_TOTAL_MAX) {
                sb.setLength(TRACE_TOTAL_MAX);
                sb.append("\n…(已截断，完整消息共 ").append(history.size()).append(" 条)");
                break;
            }
        }
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == '\n') sb.setLength(len - 1);
        return sb.toString();
    }

    /** 把 LLM 返回（content + tool_calls + reasoning）渲染为多行可读文本（/trace llm 的 llmResponse payload） */
    private String formatResponseForTrace(String content, List<TLToolCall> toolCalls, String reasoning) {
        StringBuilder sb = new StringBuilder();
        if (content != null && !content.isEmpty()) {
            sb.append("[response] ").append(truncateText(content, TRACE_MSG_MAX)).append("\n");
        }
        if (toolCalls != null && !toolCalls.isEmpty()) {
            sb.append("[tool_calls]\n");
            appendToolCalls(sb, toolCalls, "  ");
        }
        if (reasoning != null && !reasoning.isEmpty()) {
            sb.append("[reasoning] ").append(truncateText(reasoning, TRACE_MSG_MAX));
        }
        if (sb.length() > TRACE_TOTAL_MAX) {
            sb.setLength(TRACE_TOTAL_MAX);
            sb.append("\n…(已截断)");
        }
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == '\n') sb.setLength(len - 1);
        return sb.toString();
    }

    private static void appendToolCalls(StringBuilder sb, List<TLToolCall> toolCalls, String indent) {
        for (TLToolCall tc : toolCalls) {
            if (tc == null) continue;
            sb.append(indent).append("tool_call ")
                    .append(tc.getFunctionName() != null ? tc.getFunctionName() : "?");
            if (tc.getArguments() != null && !tc.getArguments().isEmpty()) {
                sb.append(" ").append(truncateText(String.valueOf(tc.getArguments()), 200));
            }
            sb.append("\n");
        }
    }

    /** 进程级+会话级 token 上报：每次真实 LLM 响应调用一次；全 0（意图缓存命中/mock fallback）视为非 LLM 调用，跳过 */
    private void reportProcessTokenUsage(long[] usage, long[] cache, String sessionId, String rootSid, String userId) {
        if (usage[0] + usage[1] + usage[2] + cache[0] + cache[1] + cache[2] <= 0) return;
        try {
            TLMsg m = createMsg().setAction(MONITOR_RECORDTOKENUSAGE)
                    .setParam(AI_P_PROMPTTOKENS, usage[0])
                    .setParam(AI_P_COMPLETIONTOKENS, usage[1])
                    .setParam(AI_P_TOTALTOKENS, usage[2])
                    .setParam(AI_P_CACHECREATIONTOKENS, cache[0])
                    .setParam(AI_P_CACHEHITTOKENS, cache[1])
                    .setParam(AI_P_CACHEMISSTOKENS, cache[2])
                    .setParam(AI_P_SESSIONID, sessionId)
                    .setParam(AI_P_ROOTSESSIONID, rootSid != null ? rootSid : sessionId)
                    .setParam(AI_P_USERID, userId != null ? userId : "");
            m.setSystemParam(IGNOREMODULEISNULL, true);
            putMsg(M_AGENTMONITOR, m);
        } catch (Exception ignored) {}
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
     * 保存上下文历史（批量替换，O(1)次消息传递）。
     * 保存前净化残缺 tool 对（stop 中断等产生的孤儿 assistant(tool_calls)/tool 消息）——
     * 从 context 源头清除，避免每轮发送时反复净化、且污染后续恢复。
     */
    protected void saveContextHistory(String sessionId, List<TLConversationHistory> history) {
        List<TLConversationHistory> clean = new ArrayList<>(history);
        sanitizeToolPairs(clean);
        TLMsg replaceMsg = createMsg()
                .setAction(CONTEXT_REPLACE)
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam(AI_P_MESSAGEHISTORY, clean);
        putMsg(contextModuleName, replaceMsg);
    }

    /**
     * 从记忆召回结果计算本会话摘要的最大 coverSeq（0 = 无摘要，视图取最新 contextViewSize 条）。
     * 摘要条目：tag=session_summary 且 metadata.sessionId == 当前会话。
     */
    @SuppressWarnings("unchecked")
    protected long computeSessionCoverSeq(TLMsg beforeResult, String sessionId) {
        if (beforeResult == null || !beforeResult.containsParam(AI_P_MEMORYRESULT)) return 0L;
        try {
            List<TLMemoryEntry> entries = (List<TLMemoryEntry>)
                    beforeResult.getListParam(AI_P_MEMORYRESULT, null);
            if (entries == null) return 0L;
            long maxCover = 0L;
            for (TLMemoryEntry e : entries) {
                if (e == null || !"session_summary".equals(e.getTag())) continue;
                Map<String, Object> meta = e.getMetadata();
                if (meta == null) continue;
                if (!sessionId.equals(meta.get("sessionId"))) continue;
                Object cs = meta.get("coverSeq");
                if (cs instanceof Number) {
                    long v = ((Number) cs).longValue();
                    if (v > maxCover) maxCover = v;
                }
            }
            return maxCover;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 构建发送给 LLM 的视图列表：
     * system 区（默认 system + 记忆 system）恒保留；其余取尾部 contextViewSize 条原样。
     * 历史未超视图 → 直接全量（兼容现状）。
     * 注意：不按 coverSeq 过滤——恢复/接管会话时摘要 coverSeq 紧跟最新会导致视图为空、
     * LLM 看不到近期历史（"像重新开始"）；尾部截取天然只取最近内容，
     * 摘要覆盖的更早内容本就不在尾部，无需排除。
     * 孤儿 tool 修正：视图开头是 tool 消息时向前补其 assistant(tool_calls)，保证 OpenAI 消息顺序约束。
     */
    protected List<TLConversationHistory> buildSendList(List<TLConversationHistory> full,
                                                        long coverSeq, String memoryContext) {
        if (full.size() <= contextViewSize) {
            List<TLConversationHistory> all = new ArrayList<>(full);
            if (memoryContext != null && !memoryContext.isEmpty()) {
                all.add(new TLConversationHistory(TLConversationHistory.Role.system, memoryContext));
            }
            sanitizeToolPairs(all);
            stripImagePayloads(all);
            trimToContextBudget(all);
            return all;
        }
        List<TLConversationHistory> systems = new ArrayList<>();
        List<TLConversationHistory> rest = new ArrayList<>();
        for (TLConversationHistory h : full) {
            if (h.getRole() == TLConversationHistory.Role.system) systems.add(h);
            else rest.add(h);
        }
        if (memoryContext != null && !memoryContext.isEmpty()) {
            systems.add(new TLConversationHistory(TLConversationHistory.Role.system, memoryContext));
        }
        int startIdx = Math.max(0, rest.size() - contextViewSize);
        // 孤儿 tool 修正：视图开头是 tool 时，向前在 full 中找其 assistant(tool_calls) 并入
        while (startIdx < rest.size()
                && rest.get(startIdx).getRole() == TLConversationHistory.Role.tool) {
            TLConversationHistory first = rest.get(startIdx);
            int idxInFull = full.indexOf(first);
            if (idxInFull <= 0) break;
            boolean extended = false;
            for (int i = idxInFull - 1; i >= 0; i--) {
                TLConversationHistory h = full.get(i);
                if (h.isAssistantWithToolCalls()) {
                    List<TLConversationHistory> prefix =
                            new ArrayList<>(full.subList(i, idxInFull));
                    rest.addAll(0, prefix);
                    startIdx += prefix.size();
                    extended = true;
                    break;
                }
            }
            if (!extended) break;
        }
        List<TLConversationHistory> result = new ArrayList<>(systems);
        result.addAll(rest.subList(Math.max(0, startIdx), rest.size()));
        sanitizeToolPairs(result);
        stripImagePayloads(result);
        trimToContextBudget(result);
        return result;
    }

    /** 发送视图兜底：截图 base64 载荷降级为占位符（同一轮内 tool 刚执行完、尚未入史裁剪的本地列表也挡） */
    private static void stripImagePayloads(List<TLConversationHistory> list) {
        for (TLConversationHistory h : list) {
            TLAiContext.stripImagePayload(h);
        }
    }

    /**
     * 发送视图 token 预算裁剪（只作用本次发送拷贝，不写回 context，不影响保存/恢复）：
     * 超预算时从最旧的非 system 消息开始丢（工具链完整性交给 sanitizeToolPairs 兜底——
     * 被丢的 assistant(tool_calls) 其后续 tool 响应会成为孤儿被一并清理）；
     * 丢到只剩单条仍超（单条消息自身巨量，如大文件读取/整段粘贴）则按 0.8 比例循环截断内容。
     * token 为无 tokenizer 粗估（CJK≈1.25/字、ASCII≈0.35/字符 + 结构开销），预算已留 10% 裕量。
     */
    private void trimToContextBudget(List<TLConversationHistory> list) {
        if (contextTokenLimit <= 0 || list.size() <= 1) return;
        long budget = (long) (contextTokenLimit * 0.9) - defaultMaxTokens;
        if (budget < 2000) budget = 2000;
        while (estimateTokens(list) > budget && list.size() > 1) {
            int idx = 0;
            while (idx < list.size() && list.get(idx).getRole() == TLConversationHistory.Role.system) idx++;
            if (idx >= list.size()) break;
            list.remove(idx);
        }
        sanitizeToolPairs(list);
        if (list.size() <= 1) return;
        // 单条消息自身超预算：循环按 0.8 比例截断最长的非 system 消息（下限 200 字符防死循环）
        int guard = 0;
        while (estimateTokens(list) > budget && guard++ < 8) {
            int maxI = -1;
            long maxLen = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).getRole() == TLConversationHistory.Role.system) continue;
                String c = list.get(i).getContent();
                long l = c == null ? 0 : c.length();
                if (l > maxLen) { maxLen = l; maxI = i; }
            }
            if (maxI < 0 || maxLen <= 0) break;
            TLConversationHistory h = list.get(maxI);
            String content = h.getContent();
            if (content == null || content.length() <= 400) break;
            int keep = Math.max(200, (int) (content.length() * 0.8));
            h.setContent(content.substring(0, keep) + "\n…(内容过长，发送时已截断)");
        }
    }

    /** 无 tokenizer 的粗估：CJK≈1.25 token/字、ASCII≈0.35 token/字符，外加每条消息/工具调用结构开销 */
    private long estimateTokens(List<TLConversationHistory> list) {
        long toks = 0;
        for (TLConversationHistory h : list) {
            toks += 6;
            String c = h.getContent();
            if (c != null) {
                long cjk = 0;
                for (int i = 0; i < c.length(); i++) {
                    if (c.charAt(i) > 127) cjk++;
                }
                toks += (long) ((c.length() - cjk) * 0.35 + cjk * 1.25) + 2;
            }
            if (h.getName() != null) toks += h.getName().length() / 4 + 2;
            if (h.getToolCalls() != null) {
                for (TLToolCall tc : h.getToolCalls()) {
                    toks += 10;
                    if (tc.getFunctionName() != null) toks += tc.getFunctionName().length() / 4;
                    if (tc.getArguments() != null) {
                        toks += tc.getArguments().toString().length() / 4;
                    }
                }
            }
            if (h.getToolCallId() != null) toks += 8;
        }
        return toks;
    }

    /**
     * 发送前净化残缺的 tool 对（stop 中断等可能产生）：
     * ① 孤立 tool 消息（其 assistant(tool_calls) 不在列表内）→ 删除
     * ② assistant(tool_calls) 未被后续 tool 消息完整响应（工具执行被打断）→ 删除该 assistant 及其 tool 响应
     * 否则 LLM 报 400 "assistant message with tool_calls must be followed by tool messages"。
     */
    private static void sanitizeToolPairs(List<TLConversationHistory> list) {
        for (int i = 0; i < list.size(); i++) {
            TLConversationHistory h = list.get(i);
            if (h.getRole() == TLConversationHistory.Role.tool) {
                // 孤立 tool：向前找不到覆盖其 toolCallId 的 assistant(tool_calls)
                boolean covered = false;
                for (int j = i - 1; j >= 0; j--) {
                    TLConversationHistory a = list.get(j);
                    if (a.getRole() == TLConversationHistory.Role.user) break;
                    if (a.isAssistantWithToolCalls()) {
                        for (TLToolCall tc : a.getToolCalls()) {
                            if (tc.getId() != null && tc.getId().equals(h.getToolCallId())) {
                                covered = true;
                                break;
                            }
                        }
                        break;
                    }
                }
                if (!covered) {
                    list.remove(i);
                    i--;
                }
            } else if (h.isAssistantWithToolCalls()) {
                // 未被完整响应：检查其后 tool 消息是否覆盖全部 tool_call_id
                java.util.Set<String> need = new java.util.HashSet<>();
                for (TLToolCall tc : h.getToolCalls()) {
                    if (tc.getId() != null) need.add(tc.getId());
                }
                for (int j = i + 1; j < list.size(); j++) {
                    TLConversationHistory t = list.get(j);
                    if (t.getRole() == TLConversationHistory.Role.tool && t.getToolCallId() != null) {
                        need.remove(t.getToolCallId());
                    } else if (t.getRole() == TLConversationHistory.Role.user
                            || t.getRole() == TLConversationHistory.Role.assistant) {
                        break;
                    }
                }
                if (!need.isEmpty()) {
                    // 删除该 assistant 及其 tool 响应
                    java.util.Set<String> ids = new java.util.HashSet<>();
                    for (TLToolCall tc : h.getToolCalls()) {
                        if (tc.getId() != null) ids.add(tc.getId());
                    }
                    int k = i + 1;
                    while (k < list.size() && list.get(k).getRole() == TLConversationHistory.Role.tool
                            && ids.contains(list.get(k).getToolCallId())) {
                        list.remove(k);
                    }
                    list.remove(i);
                    i--;
                }
            }
        }
    }

    /** 获取会话当前最大 seq（记忆摘要 coverSeq 用；失败返回 0） */
    protected long getMaxSeq(String sessionId) {
        try {
            TLMsg resp = putMsg(contextModuleName, createMsg()
                    .setAction(CONTEXT_GETMAXSEQ).setParam(AI_P_SESSIONID, sessionId));
            if (resp == null || !resp.containsParam("maxSeq")) return 0L;
            Object v = resp.getParam("maxSeq");
            return v instanceof Number ? ((Number) v).longValue() : 0L;
        } catch (Exception e) {
            return 0L;
        }
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
     * 委托 ToolExecutor 批量执行工具，结果直接写入 history（供 onStreamResult 等简单场景）。
     * @return ToolExecutor 的执行结果消息（rejected 等标志供调用方处理），无执行时为 null
     */
    @SuppressWarnings("unchecked")
    private TLMsg execToolsViaExecutor(List<TLToolCall> toolCalls, Object fromWho,
                                       String sessionId, List<TLConversationHistory> history, String userId) {
        if (toolCalls == null || toolCalls.isEmpty()) return null;
        // 黑盒消息接口之二：tool_calls 交工具模块解析为可执行 ToolTask 列表
        TLMsg frResolveMsg = putMsg(toolManager, createMsg().setAction(AGENT_RESOLVETOOLCALLS)
                .setParam(AI_P_TOOLCALLS, toolCalls));
        List<TLToolExecutor.ToolTask> tasks = (List<TLToolExecutor.ToolTask>)
                frResolveMsg.getListParam("tasks", new ArrayList<>());
        if (tasks.isEmpty()) return null;

        String execId = sessionId + "_" + System.nanoTime();
        TLMsg execResult = putMsg(toolExecutor,
                createMsg().setAction(TODOOLEXECUTE)
                        .setParam("tasks", tasks)
                        .setSystemParam("executionId", execId)
                        .setSystemParam(AI_P_SESSIONID, sessionId)
                        .setSystemParam("userId", userId != null ? userId : "default")
                        // rootSid 查表优先：流式路径跑在回调线程，ThreadLocal 为 null 会导致工具打点被 monitor 丢弃
                        .setSystemParam("rootSessionId", sessionRootIds.getOrDefault(sessionId, sessionId))
                        .setSystemParam(AI_P_ROUNDID, sessionRoundIds.get(sessionId)));
        if (execResult == null) return null;

        List<TLToolExecutor.ToolResult> results =
                (List<TLToolExecutor.ToolResult>) execResult.getListParam("results", null);
        if (results != null) {
            for (int i = 0; i < results.size(); i++) {
                TLToolExecutor.ToolResult r = results.get(i);
                history.add(new TLConversationHistory(r.toolCallId, r.toolCallId, r.output));
                // 实时工具事件：流式调用方（webui）据此渲染工具结果/截图；tasks 与 results 按序对齐
                pushStreamToolEvent(sessionId,
                        i < tasks.size() ? tasks.get(i).functionName : r.toolCallId, r.output);
            }
        }
        return execResult;
    }

    /** 向流式调用方推送工具完成事件（webui 渲染工具结果卡/截图；非流式调用方无转发表则静默跳过） */
    private void pushStreamToolEvent(String sessionId, String toolName, String output) {
        String[] fwd = streamForwardMap.get(sessionId);
        if (fwd == null || output == null) return;
        try {
            TLMsg evt = createMsg().setAction(fwd[1])
                    .setParam("toolEvent", true)
                    .setParam("toolName", toolName != null ? toolName : "tool")
                    .setParam("toolOutput", output)
                    .setParam(AI_P_SESSIONID, sessionId);
            putMsg(fwd[0], evt);
        } catch (Exception e) {
            putLog("pushStreamToolEvent error: " + e.toString(), LogLevel.WARN);
        }
    }

    /**
     * 流式路径的拒绝处理：工具执行结果含 rejected 标志 → 回滚刚写入的
     * assistant(tool_calls) 与 tool 结果消息，注入"不要重试"提示。
     * @return true = 已处理拒绝（调用方应停止续跑并转发拒绝文案）
     */
    private boolean handleStreamRejected(TLMsg execResult, List<TLConversationHistory> history, String sessionId) {
        if (execResult == null || !execResult.parseBoolean("rejected", false)) return false;
        String reason = execResult.getStringParam("rejectReason", "");
        // 回滚：本次写入的 tool 结果（results 列表条数）+ 1 个 assistant(tool_calls) 消息
        int written = execResult.getListParam("results", java.util.List.of()).size();
        for (int i = 0; i < written + 1 && !history.isEmpty(); i++) {
            history.remove(history.size() - 1);
        }
        history.add(new TLConversationHistory(TLConversationHistory.Role.user,
                "（操作已被用户拒绝：" + reason + "。不要重试此操作。）"));
        saveContextHistory(sessionId, history);
        return true;
    }

    /** 转发流式最终结果给调用方：先发文本 chunk 再发完成信号 */
    private void forwardStreamFinal(String resultAction, String resultFor, String sessionId, String text) {
        TLMsg chunk = createMsg().setAction(resultAction)
                .setParam(AI_P_CHUNK, text).setParam(AI_P_SESSIONID, sessionId);
        putMsg(resultFor, chunk);
        TLMsg doneMsg = createMsg().setAction(resultAction)
                .setParam(AI_P_STREAMDONE, true).setParam(AI_P_SESSIONID, sessionId);
        putMsg(resultFor, doneMsg);
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
