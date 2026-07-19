package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;
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
public class TLAiAgent extends TLBaseModule implements TLAiAgentParamString {

    // ======================== 配置字段 ========================

    /** 默认LLM Provider模块名 */
    protected String defaultLlmProvider;

    /** 默认长期记忆存储模块名 */
    protected String defaultMemoryStore = M_LONGTERMMEMORY;

    /** 当前活跃的Provider实例引用 */
    protected TLLlmProvider llmProvider;

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

    /** 已注册的Skill：skillName → skillModule */
    protected Map<String, TLBaseSkill> skills;

    /** 已注册的Memory：storeName → memoryModule */
    protected Map<String, TLBaseMemory> memoryStores;

    /** 默认模型 */
    protected String defaultModel = "gpt-4o";

    /** 默认temperature */
    protected double defaultTemperature = 0.7;

    /** 默认maxTokens */
    protected int defaultMaxTokens = 4096;

    /** 记忆召回默认条数（contains 粗筛后注入上下文，LLM 自行判断相关性） */
    protected int defaultMemoryTopK = 50;

    // ======================== Agent管理（主控模式） ========================

    /** 从XML <agents> 解析出的子Agent配置 */
    protected HashMap<String, HashMap<String, String>> agentsConfig;

    /** 已初始化的子Agent实例：agentName → agentModule */
    protected Map<String, TLBaseModule> subAgents;

    /** 是否主控模式（配置了<agents>即为true） */
    protected boolean isMaster = false;

    /** 子 agent 贡献的工具路由表：functionName → (agentName, toolName)，经 AGENT_GETTOOLDEFS 登记，调用发 callTool 消息 */
    protected Map<String, String[]> agentToolRoutes;

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

    /** LLM 可调用的预定义消息列表（从 XML msgTools 段解析） */
    protected List<TLMsg> msgTools;

    // ======================== Session 持久化/断点恢复 ========================

    /** 是否开启断点保存/恢复（从 XML params 读取，默认 false） */
    protected boolean enableCheckpoint = false;

    /** 启动时检查 LLM Provider 是否可用（默认 false） */
    protected boolean checkProviderOnStartup = false;

    /** 会话检查点文件存储路径 */
    protected String sessionStorePath = "./data/session_store/";

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
            if (params.get("sessionStorePath") != null)
                sessionStorePath = params.get("sessionStorePath");
            if (params.get("checkProviderOnStartup") != null)
                checkProviderOnStartup = "true".equals(params.get("checkProviderOnStartup"));
        }

        // 把 providers/agents/skills/memoryStores 注入 modulesClass + modulesParams
        // 必须放在 setModuleParams() 而非 setConfig()，因为 initProperty() 会覆盖
        myConfig config = (myConfig) mconfig;
        if (modulesClass == null) modulesClass = new ConcurrentHashMap<>();
        if (modulesParams == null) modulesParams = new ConcurrentHashMap<>();
        String namespace = params != null ? params.get("agentNamespace") : null;
        injectConfigs(config.getProviders(), namespace, false);
        injectConfigs(config.getAgents(), namespace, false);
        injectConfigs(config.getSkills(), namespace, false);
        injectConfigs(config.getMemoryStores(), namespace, true);

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
        skills = new ConcurrentHashMap<>();
        memoryStores = new ConcurrentHashMap<>();
        gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
        return this;
    }

    @Override
    public void runStartMsg() {
        System.out.println("=== [TLAiAgent] runStartMsg name=" + name + " configFile=" + configFile + " ===");
        // 1. 加载 LLM Provider（每个 Agent 独立实例）
        initProvider();
        // 1.5 私有 context 实例（与 provider/skill/memory 对称），本 agent 的 defaultSystemMessage/md 正文才能生效
        initContext();
        // 2. 启动时检查 LLM Provider 连通性（失败仅告警，不中断——skills/memory/agents 仍初始化，可事后 setLlmProvider 恢复）
        if (checkProviderOnStartup && !checkProvider()) {
            putLog("LLM Provider 不可用，skills/memory/agents 仍会初始化，可事后发 setLlmProvider 恢复", LogLevel.WARN);
        }
        // 3. 加载 Skills / Memory / Agents
        initSkills();
        initMemoryStores();
        initAgents();
        super.runStartMsg();
        // 4. 启动后自动续跑 mid-loop 断点（state=checkpoint，异常退出留下的）。
        // L1 完成态会话不在启动时全量装载——doChat 每轮本就 loadSessionCheckpoint 文件优先，
        // resumeSession/findLatestSession 也按需读文件，启动全量恢复是冗余且随文件数无限膨胀
        if (enableCheckpoint) {
            autoResumeCheckpoints();
        }
    }

    /**
     * 初始化 LLM Provider。
     * defaultLlmProvider="openAiProvider" → 创建本 agent 私有实例（独立配置，保持隔离）
     * defaultLlmProvider="masterAgent:openAiProvider" → 从 master 借引用（共享，免重复配置）
     */
    protected void initProvider() {
        if (defaultLlmProvider == null || defaultLlmProvider.isEmpty()) return;

        try {
            int colon = defaultLlmProvider.indexOf(':');
            if (colon > 0) {
                // 共享模式：从指定 module 借 llmProvider 引用
                String ownerName = defaultLlmProvider.substring(0, colon);
                String provName = defaultLlmProvider.substring(colon + 1);
                TLMsg refResult = putMsg(ownerName,
                        createMsg().setAction("getLlmProvider").setParam("provName", provName));
                Object ref = refResult != null ? refResult.getParam("provider") : null;
                if (ref instanceof TLLlmProvider) {
                    llmProvider = (TLLlmProvider) ref;
                    putLog("LLM Provider borrowed: " + defaultLlmProvider
                            + " model=" + llmProvider.getDefaultModel(), LogLevel.DEBUG);
                    return;
                }
                putLog("Failed to borrow provider: " + defaultLlmProvider + ", fallback to create", LogLevel.WARN);
            }
            // 自建模式
            TLBaseModule module = (TLBaseModule) getMyModule(defaultLlmProvider);
            registerToRegistry(defaultLlmProvider, module, "provider");
            if (module instanceof TLLlmProvider) {
                llmProvider = (TLLlmProvider) module;
                putLog("LLM Provider initialized: " + defaultLlmProvider
                        + " model=" + llmProvider.getDefaultModel(), LogLevel.DEBUG);
            } else {
                putLog("Provider not TLLlmProvider: " + defaultLlmProvider, LogLevel.ERROR);
            }
        } catch (Exception e) {
            putLog("Failed to init provider: " + e.toString(), LogLevel.ERROR);
        }
    }

    /**
     * 初始化私有 context 实例（与 provider/skill/memory 对称）。
     * getMyModule = 新建实例（不注册工厂）+ 存入本地 modules map，
     * 后续 putMsg(contextModuleName, ...) 先查本地 map 即命中私有实例，
     * 本 agent 的 modulesParams defaultSystemMessage / md 正文注入由此生效。
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

    /**
     * 初始化Skills（从config或运行时注册）。
     * 框架自动根据classfile解析类名，无需手动addPackage。
     */
    protected void initSkills() {
        myConfig config = (myConfig) mconfig;
        if (config == null || config.getSkills() == null) return;

        HashMap<String, HashMap<String, String>> skillConfigs = config.getSkills();
        for (String skillName : skillConfigs.keySet()) {
            HashMap<String, String> skillParams = skillConfigs.get(skillName);
            boolean startup = TLDataUtils.parseBoolean(skillParams.get("statup"), true);
            if (!startup) continue;

            try {
                TLBaseModule module = (TLBaseModule) getMyModule(skillName);
                registerToRegistry(skillName, module, "skill");
                if (module instanceof TLBaseSkill) {
                    skills.put(((TLBaseSkill) module).getSkillName(), (TLBaseSkill) module);
                    putLog("Skill registered: " + skillName, LogLevel.DEBUG);
                }
            } catch (Exception e) {
                putLog("Failed to init skill: " + skillName + " error: " + e.toString(), LogLevel.ERROR);
            }
        }
    }

    /**
     * 初始化Memory Stores。
     * 框架自动根据classfile解析类名，自动注入agentNamespace用于多Agent记忆隔离。
     */
    @SuppressWarnings("unchecked")
    protected void initMemoryStores() {
        myConfig config = (myConfig) mconfig;
        if (config == null || config.getMemoryStores() == null) return;

        HashMap<String, HashMap<String, String>> memoryConfigs = config.getMemoryStores();
        for (String storeName : memoryConfigs.keySet()) {
            HashMap<String, String> storeParams = memoryConfigs.get(storeName);
            boolean startup = TLDataUtils.parseBoolean(storeParams.get("statup"), true);
            if (!startup) continue;

            try {
                TLBaseModule module = (TLBaseModule) getMyModule(storeName);
                registerToRegistry(storeName, module, "memory");
                if (module instanceof TLBaseMemory) {
                    memoryStores.put(storeName, (TLBaseMemory) module);
                    // 按配置创建专用的 embedding provider 实例并注入
                    String embName = storeParams.get(AI_P_EMBEDDINGPROVIDER);
                    if (embName != null && config.getProviders() != null) {
                        HashMap<String, String> embCfg = config.getProviders().get(embName);
                        if (embCfg != null) {
                            modulesClass.putIfAbsent(embName, embCfg);
                            modulesParams.putIfAbsent(embName, embCfg);
                            TLBaseModule embM = (TLBaseModule) getMyModule(embName);
                            if (embM instanceof TLLlmProvider)
                                putMsg(module, createMsg().setAction("setEmbeddingProvider")
                                        .setParam("provider", embM));
                        }
                    }
                    putLog("Memory store registered: " + storeName, LogLevel.DEBUG);
                }
            } catch (Exception e) {
                putLog("Failed to init memory store: " + storeName + " error: " + e.toString(), LogLevel.ERROR);
            }
        }
    }

    /**
     * 初始化子Agent（仅主控模式）。
     * 读取<agents>配置，一律走 getMyModule 创建——类由配置决定（classfile/sameClassAs），
     * 无任何 type 特判。group 也是普通子 agent（自己读配置初始化成员，见 TLAgentGroup）。
     * 框架自动加载 {agentName}_config.xml 作为配置文件。
     */
    protected void initAgents() {
        myConfig config = (myConfig) mconfig;
        if (config == null) return;
        agentsConfig = config.getAgents();
        System.out.println("=== [initAgents] configFile=" + configFile
                + " agentsConfig=" + (agentsConfig != null ? agentsConfig.size() + " entries" : "null") + " ===");
        if (agentsConfig == null || agentsConfig.isEmpty()) return;

        isMaster = true;
        subAgents = new ConcurrentHashMap<>();
        agentToolRoutes = new ConcurrentHashMap<>();

        for (String agentName : agentsConfig.keySet()) {
            HashMap<String, String> agentCfg = agentsConfig.get(agentName);
            boolean startup = TLDataUtils.parseBoolean(agentCfg.get("statup"), true);
            if (!startup) continue;

            try {
                // 框架 getMyModule 自动从 modulesClass 取配置、解析 sameClassAs、加载类
                TLBaseModule module = (TLBaseModule) getMyModule(agentName);
                registerToRegistry(agentName, module, "agent");
                subAgents.put(agentName, module);
                putLog("Sub-agent initialized: " + agentName + " (" + module.getClass().getSimpleName() + ")", LogLevel.DEBUG);
            } catch (Exception e) {
                putLog("Failed to init sub-agent: " + agentName + " error: " + e.toString(), LogLevel.ERROR);
            }
        }

        int agentCount = subAgents.size();
        System.out.println("★★★ 主控Agent模式已激活, Agent数量: " + agentCount + " ★★★");
        for (String name : agentsConfig.keySet()) {
            String agentType = agentsConfig.get(name).getOrDefault(AI_P_AGENTTYPE, AGENT_TYPE_AGENT);
            if (subAgents.containsKey(name)) {
                if (AGENT_TYPE_MCP.equals(agentType))
                    System.out.println("  ▸ MCP Agent: " + name);
                else
                    System.out.println("  ▸ 子Agent: delegate_to_" + name);
            }
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
            case AGENT_REGISTERSKILL:
                returnMsg = registerSkill(fromWho, msg);
                break;
            case AGENT_UNREGISTERSKILL:
                returnMsg = unregisterSkill(fromWho, msg);
                break;
            case AGENT_LISTSKILLS:
                returnMsg = listSkills(fromWho, msg);
                break;
            case AGENT_GETCONTEXT:
                returnMsg = getAgentContext(fromWho, msg);
                break;
            case AGENT_CLEARCONTEXT:
                returnMsg = clearAgentContext(fromWho, msg);
                break;
            case "resumeSession":
                returnMsg = resumeSession(fromWho, msg);
                break;
            case "findLatestSession":
                returnMsg = findLatestSession(fromWho, msg);
                break;
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
                returnMsg = createMsg().setParam(RESULT, checkProvider());
                break;
            case "getLlmProvider":
                returnMsg = createMsg().setParam(RESULT, llmProvider != null)
                        .setParam("provider", llmProvider);
                break;
            case AGENT_GETTOKENUSAGE:
                returnMsg = getTokenUsage(fromWho, msg);
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
                returnMsg = registerAgent(fromWho, msg);
                break;
            case AGENT_UNREGISTERAGENT:
                returnMsg = unregisterAgent(fromWho, msg);
                break;
            case AGENT_LISTAGENTS:
                returnMsg = listAgents(fromWho, msg);
                break;
            case AGENT_GETDESCRIPTION:
                returnMsg = createMsg().setParam(RESULT, true)
                        .setParam(AI_P_AGENTDESCRIPTION, agentDescription != null ? agentDescription : "");
                break;
            case "onStreamResult":
                returnMsg = onStreamResult(fromWho, msg);
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
        String userMessage = msg.getStringParam(AI_P_USERMESSAGE, "");
        String model = msg.getStringParam(AI_P_MODEL, llmProvider.getDefaultModel());
        double temperature = msg.getDoubleParam(AI_P_TEMPERATURE, defaultTemperature);
        int maxTokens = msg.getIntParam(AI_P_MAXTOKENS, defaultMaxTokens);

        if (userMessage.isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, "Error: missing userMessage");
        }

        putLog("Chat start: sessionId=" + sessionId + " stream=" + stream, LogLevel.DEBUG);
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
            boolean resume = msg.parseBoolean("resume", enableCheckpoint);
            TLMsg checkpoint = resume ? loadSessionCheckpoint(sessionId) : null;
            List<TLConversationHistory> history;
            int iteration = 0;
            boolean resumedFromCheckpoint = false;
            long[] turn = {0, 0, 0};   // 本次 chat 的 token 用量 {prompt, completion, total}
            boolean truncated = false; // 是否因到达 maxToolCallIterations 而截断
            boolean aborted = false;   // 是否被 /stop 协作式中断
            boolean clarified = false; // 是否调用了 request_clarification 工具

            if (checkpoint != null && SESSION_STATE_CHECKPOINT.equals(checkpoint.getStringParam("state", ""))) {
                // L2: 从 mid-loop 断点恢复，跳过预处理
                history = (List<TLConversationHistory>) checkpoint.getParam("history");
                iteration = checkpoint.getIntParam("iteration", 0);
                if (checkpoint.containsParam("model"))
                    model = checkpoint.getStringParam("model", model);
                temperature = checkpoint.getDoubleParam("temperature", temperature);
                maxTokens = checkpoint.getIntParam("maxTokens", maxTokens);
                resumedFromCheckpoint = true;
                putLog("Resumed from checkpoint: sessionId=" + sessionId + " iter=" + iteration
                        + " historySize=" + (history != null ? history.size() : 0), LogLevel.INFO);
            } else {
                // 正常流程 / L1 恢复
                if (checkpoint != null && SESSION_STATE_COMPLETED.equals(checkpoint.getStringParam("state", ""))) {
                    history = (List<TLConversationHistory>) checkpoint.getParam("history");
                    putLog("Restored completed session: " + sessionId, LogLevel.DEBUG);
                } else {
                    history = getContextHistory(sessionId);
                }
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

            // ==== LLM请求 ====
            String finalResponse;

            if (stream) {
                // 流式: 单次请求，无tool-call循环
                finalResponse = doStreamCall(history, toolDefs, sessionId, model);
                if (cancelled.get()) {
                    aborted = true;
                } else if (finalResponse != null) {
                    history.add(new TLConversationHistory(TLConversationHistory.Role.assistant, finalResponse));
                }
            } else {
                // 非流式: tool-call循环
                finalResponse = null;
                while (iteration < maxToolCallIterations) {
                    if (cancelled.get()) { aborted = true; break; }
                    iteration++;
                    // L2 checkpoint: 每次迭代前保存（覆盖 LLM 直接返回 / 中途中断等所有场景）
                    if (enableCheckpoint) {
                        persistSession(sessionId, history, SESSION_STATE_CHECKPOINT,
                                iteration, model, temperature, maxTokens, userMessage);
                    }
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

                    TLMsg llmResponse = putMsg(llmProvider, llmMsg);
                    // 取消优先：/stop 置标志 或 Provider 报告 HTTP 被取消 → 干净退出，不当错误处理
                    if (cancelled.get() || llmResponse.parseBoolean(AI_P_CANCELLED, false)) {
                        aborted = true;
                        break;
                    }
                    if (!llmResponse.parseBoolean(RESULT, false)) {
                        int hs = llmResponse.getIntParam(AI_P_HTTPSTATUS, 0);
                        String body = llmResponse.getStringParam(AI_P_RESPONSEBODY, "");
                        return createMsg().setParam(RESULT, false)
                                .setParam(AI_P_RESPONSE, "Error: HTTP " + hs + " body=" + body);
                    }
                    // Token 用量累加（Provider parseResponse 已解析 usage）
                    turn[0] += llmResponse.getIntParam(AI_P_PROMPTTOKENS, 0);
                    turn[1] += llmResponse.getIntParam(AI_P_COMPLETIONTOKENS, 0);
                    turn[2] += llmResponse.getIntParam(AI_P_TOTALTOKENS, 0);

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

                    for (TLToolCall tc : toolCalls) {
                        if (cancelled.get()) { aborted = true; break; }
                        TLMsg tr = executeToolCall(tc, fromWho, sessionId);
                        history.add(new TLConversationHistory(tc.getId(), tc.getFunctionName(),
                                tr.getStringParam(AI_P_SKILLOUTPUT, tr.getStringParam("error", ""))));
                        // request_clarification：LLM 主动要求用户确认 → 提前结束循环
                        if (tr.parseBoolean(AI_P_NEEDSCLARIFICATION, false)) {
                            clarified = true;
                            finalResponse = tr.getStringParam(AI_P_CLARIFICATIONQUESTION, "")
                                    + "\n\n请提供更多信息后重新提交。";
                            break;
                        }
                    }
                    if (aborted || clarified) break;
                    // L2 checkpoint: 每轮工具调用后保存断点
                    if (enableCheckpoint) {
                        persistSession(sessionId, history, SESSION_STATE_CHECKPOINT,
                                iteration, model, temperature, maxTokens, userMessage);
                    }
                }
                if (finalResponse == null) {
                    truncated = true;
                    finalResponse = "（已达最大迭代次数 " + maxToolCallIterations + "，可能未完成）";
                }
            }

            // ==== 中断分支：丢弃半截结果，不写 aiContext / 长期记忆，保持历史干净 ====
            if (aborted) {
                if (enableCheckpoint) {
                    // 断点落成 COMPLETED，避免 /resume 捡到半截 tool-loop
                    persistSession(sessionId, history, SESSION_STATE_COMPLETED,
                            iteration, model, temperature, maxTokens, userMessage);
                }
                putLog("Chat aborted by user: sessionId=" + sessionId, LogLevel.INFO);
                long[] accCancel = accumulateTokenUsage(sessionId, turn);
                return createMsg().setParam(RESULT, true).setParam(AI_P_CANCELLED, true)
                        .setParam(AI_P_RESPONSE, "⏹ 已中断").setParam(AI_P_SESSIONID, sessionId)
                        .setParam("iterations", iteration)
                        .setParam(AI_P_TOTALTOKENS, (int) turn[2])
                        .setParam(AI_P_TOTALTOKENS_TOTAL, (int) accCancel[2]);
            }

            // ==== 后处理: 保存上下文 + 长期记忆 + L1持久化 ====
            saveContextHistory(sessionId, history);
            if (enableCheckpoint) {
                persistSession(sessionId, history, SESSION_STATE_COMPLETED,
                        iteration, model, temperature, maxTokens, userMessage);
            }
            try {
                TLMsg saveMsg = createMsg().setAction(AGENT_SAVEMEMORY)
                        .setParam(AI_P_SESSIONID, sessionId).setParam("storeName", defaultMemoryStore)
                        .setParam("userId", msg.getStringParam("userId", sessionId))
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
                    .setParam(AI_P_TOTALTOKENS_TOTAL, (int) acc[2]);
            if (truncated) ret.setParam(AI_P_TRUNCATED, true);
            return ret;

        } catch (Exception e) {
            // 中断/取消路径（如工具被 interrupt 后异常上抛）→ 当作中断，干净收尾
            if (cancelled.get() || e instanceof InterruptedException) {
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
            Thread.interrupted();
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
        String resultFor = msg.getStringParam(RESULTFOR,
                fromWho instanceof String ? (String) fromWho : "caller");
        String resultAction = msg.getStringParam(RESULTACTION, "onStreamChunk");

        if (llmProvider == null) {
            TLMsg errMsg = createMsg().setAction(resultAction)
                    .setParam(AI_P_STREAMERROR, "No LLM provider configured");
            putMsg(resultFor, errMsg);
            return null;
        }

        // 获取上下文并构建流式请求
        List<TLConversationHistory> history = getContextHistory(sessionId);
        history.add(new TLConversationHistory(TLConversationHistory.Role.user, userMessage));
        List<TLFunctionDefinition> toolDefs = getFunctionDefinitions();

        // 直接让Provider回调到最终目标
        TLMsg streamMsg = createMsg()
                .setAction(LLM_COMPLETIONSTREAM)
                .setParam(AI_P_MESSAGEHISTORY, history)
                .setParam(AI_P_FUNCTIONDEFS, toolDefs)
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam(RESULTFOR, resultFor)
                .setParam(RESULTACTION, resultAction);

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
        String resultFor = msg.getStringParam("_streamResultFor", "caller");
        String resultAction = msg.getStringParam("_streamResultAction", "onStreamChunk");
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");

        // 转发chunk或完成信号给原始调用者
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

                    // 执行tool calls
                    for (TLToolCall tc : toolCalls) {
                        TLMsg tr = executeToolCall(tc, fromWho, sessionId);
                        history.add(new TLConversationHistory(tc.getId(), tc.getFunctionName(),
                                tr.getStringParam(AI_P_SKILLOUTPUT, tr.getStringParam("error", ""))));
                    }

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

                        for (TLToolCall tc : moreTCs) {
                            TLMsg tr = executeToolCall(tc, fromWho, sessionId);
                            history.add(new TLConversationHistory(tc.getId(), tc.getFunctionName(),
                                    tr.getStringParam(AI_P_SKILLOUTPUT, tr.getStringParam("error", ""))));
                        }
                    }
                    if (finalResponse == null) finalResponse = "Reached max iterations (" + maxToolCallIterations + ")";

                    // 保存上下文和长期记忆
                    saveContextHistory(sessionId, history);
                    try {
                        TLMsg saveMsg = createMsg().setAction(AGENT_SAVEMEMORY)
                                .setParam(AI_P_SESSIONID, sessionId).setParam("storeName", defaultMemoryStore)
                                .setParam("userId", msg.getStringParam("userId", sessionId))
                                .setParam(AI_P_MEMORYKEY, "chat_" + System.currentTimeMillis())
                                .setParam(AI_P_MEMORYVALUE, streamedText + " → " + finalResponse)
                                .setParam(AI_P_MEMORYTAG, "chat_history");
                        saveAgentMemory(fromWho, saveMsg);
                    } catch (Exception e) {
                        putLog("Save memory failed: " + e.toString(), LogLevel.ERROR);
                    }

                    // 转发最终完成信号
                    TLMsg doneMsg = createMsg()
                            .setAction(resultAction)
                            .setParam(AI_P_STREAMDONE, true)
                            .setParam(AI_P_RESPONSE, streamedText + finalResponse)
                            .setParam(AI_P_SESSIONID, sessionId);
                    putMsg(resultFor, doneMsg);

                } catch (Exception e) {
                    putLog("Stream tool call continuation error: " + e.toString(), LogLevel.ERROR);
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
                        .setParam(AI_P_RESPONSE, streamedText)
                        .setParam(AI_P_SESSIONID, sessionId);
                putMsg(resultFor, doneMsg);
            }
        } else if (msg.containsParam(AI_P_STREAMERROR)) {
            TLMsg errMsg = createMsg()
                    .setAction(resultAction)
                    .setParam(AI_P_STREAMERROR, msg.getParam(AI_P_STREAMERROR));
            putMsg(resultFor, errMsg);
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
        // 目录名转驼峰 → moduleName，如 "unicom-cloud-revenue" → "cloudRevenueScript"
        String moduleName = dirToModuleName(skillDir) + "Script";
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

        // 注入配置
        modulesClass.put(moduleName, cfg);
        modulesParams.put(moduleName, new HashMap<>(cfg));

        // 创建
        TLBaseModule module = (TLBaseModule) getMyModule(moduleName);
        if (module instanceof TLBaseSkill) {
            TLBaseSkill skill = (TLBaseSkill) module;
            skills.put(skill.getSkillName(), skill);
            registerToRegistry(moduleName, module, "skill");
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

    /** "unicom-cloud-revenue" → "cloudRevenue" */
    private String dirToModuleName(String dir) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (int i = 0; i < dir.length(); i++) {
            char c = dir.charAt(i);
            if (c == '-' || c == '_') { upper = true; continue; }
            if (i == 0 || upper) { sb.append(Character.toUpperCase(c)); upper = false; }
            else sb.append(c);
        }
        return sb.toString();
    }

    /** 将 skill 配置写入 agent XML，在 </skills> 或 </moduleConfig> 前插入 */
    private void writeSkillToConfig(String moduleName, String skillName, String interpreter,
                                     int maxExecutionTime, String skillDir) throws IOException {
        File file = new File(configFile);
        if (!file.exists()) return;

        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) content.append(line).append("\n");
        }
        String xml = content.toString();

        String skillTag = "\t\t<skill name=\"" + moduleName
                + "\" sameClassAs=\"scriptExecutionSkill\" statup=\"true\""
                + " skillName=\"" + skillName + "\""
                + " interpreter=\"" + interpreter + "\""
                + " maxExecutionTime=\"" + maxExecutionTime + "\""
                + " allowedScriptDir=\"skills/" + skillDir + "/scripts\"/>";

        int pos = xml.indexOf("</skills>");
        if (pos != -1) {
            xml = xml.substring(0, pos) + skillTag + "\n" + xml.substring(pos);
        } else {
            pos = xml.indexOf("</moduleConfig>");
            if (pos != -1) {
                xml = xml.substring(0, pos) + "\t<skills>\n" + skillTag + "\n\t</skills>\n" + xml.substring(pos);
            }
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(xml);
        }
    }

    protected synchronized TLMsg registerSkill(Object fromWho, TLMsg msg) {
        // 支持通过instance直接注册
        TLBaseSkill instance = (TLBaseSkill) msg.getParam(INSTANCE, TLBaseSkill.class);
        if (instance != null) {
            skills.put(instance.getSkillName(), instance);
            modules.put(instance.getName(), instance);
            putLog("Skill registered by instance: " + instance.getSkillName(), LogLevel.DEBUG);
            invalidateToolDefs();
            return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLNAME, instance.getSkillName());
        }

        // 通过模块名和类名注册
        String skillModuleName = msg.getStringParam(MODULENAME, "");
        String classfile = msg.getStringParam(MODULE_CLASSFILE, "");
        HashMap<String, String> skillParams = new HashMap<>(msg.getMapParam(MODULE_PARAMS, new HashMap<>()));

        if (!skillModuleName.isEmpty() && !classfile.isEmpty()) {
            TLBaseModule module = (TLBaseModule) getNewModule(skillModuleName, classfile, skillParams);
            registerToRegistry(skillModuleName, module, "skill");
            if (module instanceof TLBaseSkill) {
                TLBaseSkill skill = (TLBaseSkill) module;
                skills.put(skill.getSkillName(), skill);
                modules.put(skillModuleName, skill);
                invalidateToolDefs();
                return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLNAME, skill.getSkillName());
            }
        }

        return createMsg().setParam(RESULT, false).setParam("error", "Invalid skill registration");
    }

    protected synchronized TLMsg unregisterSkill(Object fromWho, TLMsg msg) {
        String skillName = msg.getStringParam(AI_P_SKILLNAME, "");
        if (!skillName.isEmpty()) {
            TLBaseSkill removed = skills.remove(skillName);
            modules.remove(skillName);
            if (removed != null) invalidateToolDefs();
            return createMsg().setParam(RESULT, removed != null);
        }
        return createMsg().setParam(RESULT, false).setParam("error", "skillName required");
    }

    protected TLMsg listSkills(Object fromWho, TLMsg msg) {
        List<Map<String, Object>> skillInfos = new ArrayList<>();
        for (TLBaseSkill skill : skills.values()) {
            if (!skill.isEnabled()) continue;
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", skill.getSkillName());
            info.put("description", skill.getSkillDescription());
            info.put("moduleName", skill.getName());
            skillInfos.add(info);
        }
        return createMsg().setParam(RESULT, true).setParam("skills", skillInfos);
    }

    /**
     * 运行时切换 skill 启用状态（不删实例）。
     * 参数：skillName、enabled(bool)。切换后失效工具定义缓存。
     */
    protected synchronized TLMsg setSkillEnabled(Object fromWho, TLMsg msg) {
        String skillName = msg.getStringParam(AI_P_SKILLNAME, "");
        TLBaseSkill skill = skillName.isEmpty() ? null : skills.get(skillName);
        if (skill == null) {
            return createMsg().setParam(RESULT, false).setParam("error", "skill not found: " + skillName);
        }
        boolean enabled = msg.parseBoolean(AI_P_ENABLED, true);
        skill.setEnabled(enabled);
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
        TLBaseSkill skill = skillName.isEmpty() ? null : skills.get(skillName);
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
     * 向全局 moduleRegistry 注册子模块，key=ownerName:moduleName。
     * registry 未配置时静默跳过（IGNOREMODULEISNULL）。
     */
    private void registerToRegistry(String subName, Object module, String moduleType) {
        if (module == null) return;
        TLMsg msg = createMsg().setAction(REGISTRY_REGISTER)
                .setParam(REGISTRY_P_KEY, getName() + ":" + subName)
                .setParam(MODULENAME, subName)
                .setParam(REGISTRY_P_OWNERNAME, getName())
                .setParam(REGISTRY_P_TYPE, moduleType)
                .setParam(INSTANCE, module);
        msg.setSystemParam(IGNOREMODULEISNULL, true);
        putMsg(DEFAULTMODULEREGISTRY, msg);
    }

    // ======================== Agent管理 ========================

    @SuppressWarnings("unchecked")
    protected synchronized TLMsg registerAgent(Object fromWho, TLMsg msg) {
        String agentName = msg.getStringParam(AI_P_AGENTNAME, "");
        if (agentName.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "agentName required");
        }
        // 注册消息只带 agentName + 一个装好该 agent 全部参数的 cfg map，直接注入，
        // 与 initAgents 走同一套 getMyModule 机制。默认值由调用方在 cfg 里备好。
        HashMap<String, String> cfg = new HashMap<>(msg.getMapParam(AI_P_AGENTCONFIG, new HashMap<>()));
        try {
            modulesClass.put(agentName, cfg);
            modulesParams.put(agentName, cfg);
            if (subAgents == null) subAgents = new ConcurrentHashMap<>();
            if (agentsConfig == null) agentsConfig = new HashMap<>();
            isMaster = true;
            agentsConfig.put(agentName, cfg);
            // 一律 getMyModule 创建（内部已 modules.put），类由配置决定（classfile/sameClassAs），
            // group 也是普通子 agent，无需特判
            TLBaseModule module = (TLBaseModule) getMyModule(agentName);
            registerToRegistry(agentName, module, "agent");
            if (module == null) {
                modulesClass.remove(agentName);
                modulesParams.remove(agentName);
                agentsConfig.remove(agentName);
                return createMsg().setParam(RESULT, false).setParam("error", "create failed: " + agentName);
            }
            subAgents.put(agentName, module);
            putLog("Agent registered: " + agentName, LogLevel.DEBUG);
            invalidateToolDefs();
            return createMsg().setParam(RESULT, true).setParam(AI_P_AGENTNAME, agentName);
        } catch (Exception e) {
            putLog("Failed to register agent: " + agentName + " error: " + e.toString(), LogLevel.ERROR);
            return createMsg().setParam(RESULT, false).setParam("error", "register failed: " + e);
        }
    }

    protected synchronized TLMsg unregisterAgent(Object fromWho, TLMsg msg) {
        String agentName = msg.getStringParam(AI_P_AGENTNAME, "");
        if (!agentName.isEmpty() && subAgents != null) {
            TLBaseModule removed = subAgents.remove(agentName);
            modules.remove(agentName);
            if (agentsConfig != null) agentsConfig.remove(agentName);   // 对称清理，防残留
            // group 也是实例（在 subAgents 中），空了即退出主控模式
            if (subAgents.isEmpty()) isMaster = false;
            if (removed != null) invalidateToolDefs();
            return createMsg().setParam(RESULT, removed != null);
        }
        return createMsg().setParam(RESULT, false).setParam("error", "agentName required");
    }

    protected TLMsg listAgents(Object fromWho, TLMsg msg) {
        List<Map<String, Object>> agentInfos = new ArrayList<>();
        if (subAgents != null) {
            for (String agentName : subAgents.keySet()) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", agentName);
                HashMap<String, String> agentCfg = agentsConfig != null ? agentsConfig.get(agentName) : null;
                info.put("description", agentCfg != null ? agentCfg.getOrDefault("description", "") : "");
                info.put("moduleName", agentName);
                agentInfos.add(info);
            }
        }
        return createMsg().setParam(RESULT, true).setParam(AI_P_SUBAGENTS, agentInfos);
    }

    /**
     * 更新已注册子 Agent 的描述（影响 delegate_to_xxx 的 tool 描述）。
     * group agent 同在 agentsConfig，通用。改后失效工具定义缓存。
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
        return putMsg(contextModuleName, ctxMsg);
    }

    @SuppressWarnings("unchecked")
    protected TLMsg resumeSession(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        TLMsg checkpoint = loadSessionCheckpoint(sessionId);
        if (checkpoint == null) {
            return createMsg().setParam(RESULT, false).setParam("error", "No checkpoint found");
        }
        List<TLConversationHistory> history =
                (List<TLConversationHistory>) checkpoint.getParam("history");
        if (history == null || history.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "Empty history");
        }
        putMsg(contextModuleName, createMsg()
                .setAction(CONTEXT_REPLACE)
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam(AI_P_MESSAGEHISTORY, history));
        putLog("Session resumed: " + sessionId + " (" + history.size() + " msgs)", LogLevel.DEBUG);
        return createMsg().setParam(RESULT, true).setParam("count", history.size());
    }

    protected TLMsg findLatestSession(Object fromWho, TLMsg msg) {
        try {
            java.io.File dir = new java.io.File(sessionStorePath);
            if (!dir.exists() || !dir.isDirectory()) {
                return createMsg().setParam("sessionId", (String) null);
            }
            java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
            if (files == null || files.length == 0) {
                return createMsg().setParam("sessionId", (String) null);
            }
            // 排除当前会话，找最近修改的
            String currentId = msg.getStringParam(AI_P_SESSIONID, "");
            java.io.File latest = null;
            long latestTime = 0;
            for (java.io.File f : files) {
                String id = f.getName().replace(".json", "");
                if (id.equals(currentId)) continue;
                if (f.lastModified() > latestTime) {
                    latestTime = f.lastModified();
                    latest = f;
                }
            }
            if (latest == null) {
                return createMsg().setParam("sessionId", (String) null);
            }
            String sessionId = latest.getName().replace(".json", "");
            return createMsg().setParam("sessionId", sessionId)
                    .setParam("lastModified", latestTime);
        } catch (Exception e) {
            return createMsg().setParam("sessionId", (String) null);
        }
    }

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
     * 从已注册skills构建function definitions列表（真正的构建逻辑，仅由 getFunctionDefinitions 调用）。
     * 主控模式下，对所有子 agent 统一两步消息协议，不研判模块类型：
     * 1. AGENT_GETTOOLDEFS 问工具贡献——实现者（如 MCP）返回 N 个 defs + 路由，全部纳入；
     * 2. 未实现者生成单个 delegate_to_xxx，描述经 AGENT_GETDESCRIPTION 消息获取。
     */
    @SuppressWarnings("unchecked")
    private List<TLFunctionDefinition> rebuildFunctionDefinitions() {
        List<TLFunctionDefinition> defs = new ArrayList<>();
        // 重建前清空 MCP 路由，避免 unregisterAgent 后旧路由残留
        if (agentToolRoutes != null) agentToolRoutes.clear();

        // 内建工具 request_clarification：agent 可主动要求用户确认，不猜测
        // 始终可用，不依赖 skill 配置。当 LLM 调此工具时直接结束 tool-call 循环
        defs.add(TLFunctionDefinition.fromSkill(
                AGENT_REQUESTCLARITY,
                "当缺少必要信息无法完成任务时调用此工具请求用户确认。不要猜测或编造信息。",
                Map.of("question", Map.of("type", "string", "description", "需要用户确认的具体问题"))));

        // 始终包含自身的skills（主控也可以有自己的skill）
        for (TLBaseSkill skill : skills.values()) {
            if (skill.isEnabled()) {
                try {
                    defs.add(skill.buildFunctionDefinition());
                } catch (Exception e) {
                    putLog("Build function def error for " + skill.getName() + ": " + e.toString(), LogLevel.WARN);
                }
            }
        }

        // msgTools：LLM 可调用的预定义消息
        if (msgTools != null) {
            for (TLMsg msgTool : msgTools) {
                // statup="false" 跳过（与 agent/skill/store 一致）
                if (!TLDataUtils.parseBoolean(msgTool.getStringParam("statup", null), true))
                    continue;
                String name = msgTool.getMsgId();
                if (name == null || name.isEmpty()) continue;

                TLFunctionDefinition def = new TLFunctionDefinition();
                def.setName(name);
                String desc = msgTool.getDescription();
                def.setDescription(desc != null && !desc.isEmpty() ? desc : name);

                // 从 paramsFromArgs 声明 LLM 可传参数（分号分隔），不盲目暴露所有内部属性
                Map<String, Object> properties = new LinkedHashMap<>();
                String paramsFromArgs = msgTool.getStringParam("paramsFromArgs", null);
                if (paramsFromArgs != null && !paramsFromArgs.isEmpty()) {
                    for (String key : paramsFromArgs.split(";")) {
                        key = key.trim();
                        if (key.isEmpty()) continue;
                        Map<String, Object> prop = new LinkedHashMap<>();
                        prop.put("type", "string");
                        prop.put("description", key);
                        properties.put(key, prop);
                    }
                }
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "object");
                schema.put("properties", properties);
                def.setParameters(schema);

                defs.add(def);
            }
        }

        // 主控模式额外添加子Agent委托tools——统一消息协议，不研判类型
        if (isMaster && subAgents != null) {
            for (String agentName : subAgents.keySet()) {
                HashMap<String, String> agentCfg = agentsConfig.get(agentName);
                TLBaseModule sub = subAgents.get(agentName);

                // 1. 问子 agent 是否自带工具定义（AGENT_GETTOOLDEFS，MCP 等实现者返回 defs+路由）
                List<TLFunctionDefinition> contributed = null;
                Map<String, String> routes = null;
                try {
                    TLMsg defsMsg = putMsg(sub, createMsg().setAction(AGENT_GETTOOLDEFS));
                    if (defsMsg != null) {
                        contributed = (List<TLFunctionDefinition>) defsMsg.getListParam(AI_P_FUNCTIONDEFS, null);
                        routes = (Map<String, String>) defsMsg.getMapParam(AI_P_TOOLROUTES, null);
                    }
                } catch (Exception e) {
                    putLog("getToolDefinitions failed: " + agentName + " " + e, LogLevel.DEBUG);
                }
                if (contributed != null && !contributed.isEmpty()) {
                    for (TLFunctionDefinition toolDef : contributed) {
                        defs.add(toolDef);
                        String toolName = routes != null ? routes.get(toolDef.getName()) : null;
                        if (toolName != null) {
                            if (agentToolRoutes == null) agentToolRoutes = new ConcurrentHashMap<>();
                            agentToolRoutes.put(toolDef.getName(), new String[]{agentName, toolName});
                        }
                    }
                    putLog("Sub-agent [" + agentName + "] contributed "
                            + contributed.size() + " tools", LogLevel.DEBUG);
                    continue;
                }

                // 2. 默认：单个 delegate_to_xxx def。描述经消息向子 agent 获取
                // （AGENT_GETDESCRIPTION，实例自己已合并 XML+md），符合消息框架规则，
                // 无需研判模块类型；未实现该 action 或为空则回落 agentCfg.description
                String desc = null;
                try {
                    TLMsg descMsg = putMsg(sub, createMsg().setAction(AGENT_GETDESCRIPTION));
                    if (descMsg != null)
                        desc = descMsg.getStringParam(AI_P_AGENTDESCRIPTION, null);
                } catch (Exception e) {
                    putLog("getAgentDescription failed: " + agentName + " " + e, LogLevel.DEBUG);
                }
                if (desc == null || desc.isEmpty())
                    desc = agentCfg != null ? agentCfg.getOrDefault("description", agentName) : agentName;
                TLFunctionDefinition def = TLFunctionDefinition.fromSkill(
                        "delegate_to_" + agentName, desc, buildDelegateParamSchema());
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
     * 执行单个tool call。
     * 主控模式：路由到子Agent（delegate_to_xxx）；
     * 独立模式：路由到对应skill。
     */
    protected TLMsg executeToolCall(TLToolCall tc, Object fromWho, String sessionId) {
        String functionName = tc.getFunctionName();

        // 内建工具 request_clarification：agent 向用户请求确认，不走 skill 路由
        if (AGENT_REQUESTCLARITY.equals(functionName)) {
            String question = "";
            if (tc.getArguments() instanceof java.util.Map) {
                Object q = ((java.util.Map<?, ?>) tc.getArguments()).get("question");
                if (q != null) question = q.toString();
            }
            System.out.println(">>> [Clarify] Agent 请求确认: " + question);
            return createMsg().setParam(RESULT, true)
                    .setParam(AI_P_NEEDSCLARIFICATION, true)
                    .setParam(AI_P_CLARIFICATIONQUESTION, question)
                    .setParam(AI_P_SKILLOUTPUT, "⚠️ 需要确认: " + question);
        }

        // 主控模式：子 agent 贡献的工具路由（查路由表 → 发 callTool 消息，不研判类型）
        if (isMaster && agentToolRoutes != null && agentToolRoutes.containsKey(functionName)) {
            String[] route = agentToolRoutes.get(functionName);
            String routeAgentName = route[0];
            String routeToolName = route[1];
            TLBaseModule routeAgent = subAgents.get(routeAgentName);
            if (routeAgent == null) {
                putLog("Tool route agent not found: " + routeAgentName, LogLevel.WARN);
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "Error: agent not found: " + routeAgentName);
            }

            try {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> toolArgs = tc.getArguments() instanceof java.util.Map
                        ? (java.util.Map<String, Object>) tc.getArguments()
                        : new java.util.LinkedHashMap<>();

                System.out.println(">>> [主控-tool路由] 调用 [" + routeAgentName + "." + routeToolName + "]");
                System.out.println("    参数: " + toolArgs);

                TLMsg result = putMsg(routeAgent, createMsg().setAction(MCP_CALLTOOL)
                        .setParam(AI_P_TOOLNAME, routeToolName)
                        .setParam(AI_P_TOOLARGUMENTS, toolArgs));
                if (result == null) {
                    return createMsg().setParam(RESULT, false)
                            .setParam(AI_P_SKILLOUTPUT, "Error: no response from " + routeAgentName);
                }
                String output = result.getStringParam(AI_P_SKILLOUTPUT,
                        result.parseBoolean(RESULT, false) ? "OK" : "Failed");

                System.out.println("<<< [主控-tool路由] [" + routeAgentName + "." + routeToolName
                        + "] 返回 (前200字): " + (output != null ? output.substring(0, Math.min(200, output.length())) : "null"));

                return result;
            } catch (Exception e) {
                putLog("!!! [主控-tool路由] [" + routeAgentName + "." + routeToolName + "] 异常: " + e.toString(), LogLevel.ERROR);
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "Error executing tool: " + e.getMessage());
            }
        }

        // msgTool 路由：查找匹配 msgId 的预定义消息。
        // 有 action：直接执行到 destination（缺省发给本 agent 自己）；
        // 只有 msgid 无 action：走框架 msgid 路由——目标模块的 getMsg() 会
        // 调用 checkMsgId() 查其 msgTable（一个 msgid 可挂多条 msg），doMsgList 顺序执行。
        TLMsg matchedMsg = findMsgToolByMsgId(functionName);
        if (matchedMsg != null) {
            try {
                TLMsg execMsg = new TLMsg();
                execMsg.copyFrom(matchedMsg);
                execMsg.setSource(getName());

                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> toolArgs = tc.getArguments() instanceof java.util.Map
                        ? (java.util.Map<String, Object>) tc.getArguments()
                        : new java.util.LinkedHashMap<>();
                // 注入 LLM 参数（覆盖/追加到消息 args 中）
                if (toolArgs != null) {
                    for (java.util.Map.Entry<String, Object> entry : toolArgs.entrySet()) {
                        execMsg.setParam(entry.getKey(), entry.getValue());
                    }
                }
                // 自动注入当前会话上下文参数（如果消息模板未预设）
                if (!execMsg.containsParam(AI_P_SESSIONID) && sessionId != null) {
                    execMsg.setParam(AI_P_SESSIONID, sessionId);
                }

                String dest = execMsg.getDestination();
                String action = execMsg.getAction();
                String msgId = execMsg.getMsgId();
                if (action != null && !action.isEmpty())
                    System.out.println(">>> [msgTool] [" + functionName + "] action=" + action
                            + " dest=" + (dest != null && !dest.isEmpty() ? dest : "self"));
                else
                    System.out.println(">>> [msgTool] [" + functionName + "] msgid=" + (msgId != null ? msgId : "-")
                            + " dest=" + (dest != null && !dest.isEmpty() ? dest : "self") + " (路由查 msgTable)");
                putLog("Executing msgTool: " + functionName + " -> " + (dest != null && !dest.isEmpty() ? dest : "self"), LogLevel.DEBUG);

                TLMsg result = (dest != null && !dest.isEmpty()) ? putMsg(dest, execMsg) : putMsg(this, execMsg);
                String output;
                if (result == null) {
                    output = "done";
                } else if (result.getParam(RESULT) instanceof java.util.List) {
                    // 并行结果集（doMsgListParallel 返回）：逐条提取标签+内容
                    @SuppressWarnings("unchecked")
                    java.util.List<TLMsg> results = (java.util.List<TLMsg>) result.getParam(RESULT);
                    StringBuilder sb = new StringBuilder();
                    for (TLMsg r : results) {
                        if (sb.length() > 0) sb.append("\n");
                        String label = r.getDestination();
                        if (label == null) label = r.getAction();
                        if (label == null) label = r.getMsgId();
                        if (label == null) label = "msg";
                        String body;
                        if (r == null) {
                            body = "no response";
                        } else if (r.containsParam(AI_P_SKILLOUTPUT)) {
                            body = r.getStringParam(AI_P_SKILLOUTPUT, "");
                        } else {
                            HashMap<String, Object> args = r.getArgs();
                            if (args != null && !args.isEmpty()) {
                                StringBuilder asb = new StringBuilder();
                                for (java.util.Map.Entry<String, Object> e : args.entrySet()) {
                                    if (asb.length() > 0) asb.append(", ");
                                    asb.append(e.getKey()).append("=").append(e.getValue());
                                }
                                body = asb.toString();
                            } else {
                                body = r.toString();
                            }
                        }
                        sb.append("【").append(label).append("】").append(body);
                    }
                    output = sb.toString();
                } else if (result.containsParam(AI_P_SKILLOUTPUT)) {
                    // Skill 风格返回（显式设置了 AI_P_SKILLOUTPUT）
                    output = result.getStringParam(AI_P_SKILLOUTPUT, "");
                } else {
                    // 通用返回：从 args 中拼出所有业务参数
                    HashMap<String, Object> resultArgs = result.getArgs();
                    if (resultArgs != null && !resultArgs.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (java.util.Map.Entry<String, Object> entry : resultArgs.entrySet()) {
                            if (sb.length() > 0) sb.append(", ");
                            sb.append(entry.getKey()).append("=").append(entry.getValue());
                        }
                        output = sb.toString();
                    } else {
                        output = result.toString();
                    }
                }

                System.out.println("<<< [msgTool] 消息 [" + functionName + "] 返回: " + output);

                return createMsg().setParam(RESULT, true)
                        .setParam(AI_P_SKILLOUTPUT, output);
            } catch (Exception e) {
                putLog("msgTool execution error: " + functionName + " -> " + e.toString(), LogLevel.ERROR);
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "Error executing msgTool " + functionName + ": " + e.getMessage());
            }
        }

        // 主控模式：路由到子Agent（group 也是 subAgents 中的普通子 agent，无需特判）
        if (isMaster && functionName.startsWith("delegate_to_")) {
            String agentName = functionName.substring("delegate_to_".length());

            TLBaseModule subAgent = subAgents.get(agentName);
            if (subAgent == null) {
                putLog("Sub-agent not found: " + agentName, LogLevel.WARN);
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "Error: Agent not found: " + agentName)
                        .setParam(AI_P_AGENTERROR, AGENT_ERR_NOTFOUND);
            }

            try {
                String task = parseDelegateArgs(tc.getArguments());
                String childSessionId = agentName + ":"
                        + (tc.getId() != null ? tc.getId() : System.currentTimeMillis());

                TLMsg chatMsg = createMsg()
                        .setAction(AGENT_CHAT)
                        .setParam(AI_P_USERMESSAGE, task)
                        .setParam(AI_P_SESSIONID, childSessionId)
                        .setParam("rootSessionId", currentRootSessionId.get());

                System.out.println(">>> [主控] 委托任务给子Agent [" + agentName + "]");
                System.out.println("    任务: " + task);

                TLMsg result = putMsg(subAgent, chatMsg);
                boolean needsClarify = result != null && result.parseBoolean(AI_P_NEEDSCLARIFICATION, false);
                String agentOutput;
                if (needsClarify) {
                    String question = result.getStringParam(AI_P_CLARIFICATIONQUESTION, "");
                    agentOutput = "⚠️ Agent [" + agentName + "] requests clarification: " + question
                            + "\n\nAsk the user before re-delegating.";
                } else {
                    agentOutput = result != null
                            ? result.getStringParam(AI_P_RESPONSE, result.toString())
                            : "No response from agent " + agentName;
                }

                System.out.println("<<< [主控] 子Agent [" + agentName + "] 返回结果 (前200字): "
                        + (agentOutput != null ? agentOutput.substring(0, Math.min(200, agentOutput.length())) : "null"));

                return createMsg().setParam(RESULT, true)
                        .setParam(AI_P_SKILLOUTPUT, agentOutput);
            } catch (Exception e) {
                putLog("!!! [主控] 子Agent [" + agentName + "] 执行异常: " + e.toString(), LogLevel.ERROR);
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "Error in agent " + agentName + ": " + e.getMessage())
                        .setParam(AI_P_AGENTERROR, e.getMessage());
            }
        }

        // 独立模式：原有skill路由
        TLBaseSkill skill = skills.get(functionName);

        if (skill == null) {
            putLog("Skill not found for tool call: " + functionName, LogLevel.WARN);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error: Skill not found: " + functionName)
                    .setParam("error", "Skill not found");
        }

        try {
            // 输入校验：参数不合法则返回错误让 LLM 自修正
            TLMsg validateMsg = createMsg().setAction(SKILL_VALIDATE)
                    .setParam(AI_P_SKILLINPUT, tc.getArguments() != null
                            ? tc.getArguments() : new LinkedHashMap<>());
            TLMsg vResult = putMsg(skill, validateMsg);
            if (!vResult.parseBoolean(RESULT, false)) {
                putLog("Skill validation failed: " + functionName + " - " + vResult.getStringParam("error", ""), LogLevel.WARN);
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "Validation error: " + vResult.getStringParam("error", "unknown"));
            }

            TLMsg executeMsg = createMsg()
                    .setAction(SKILL_EXECUTE)
                    .setParam(AI_P_SKILLINPUT, tc.getArguments() != null
                            ? tc.getArguments() : new LinkedHashMap<>())
                    .setParam(AI_P_TOOLID, tc.getId())
                    .setParam(AI_P_TOOLNAME, functionName);

            putLog("Executing skill: " + functionName + " toolCallId=" + tc.getId(), LogLevel.DEBUG);

            return putMsg(skill, executeMsg);
        } catch (Exception e) {
            putLog("Skill execution error: " + functionName + " -> " + e.toString(), LogLevel.ERROR);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error executing " + functionName + ": " + e.getMessage());
        }
    }

    /**
     * 解析委托参数，提取task描述字符串
     */
    @SuppressWarnings("unchecked")
    protected String parseDelegateArgs(Object args) {
        if (args instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) args;
            Object task = map.get("task");
            if (task != null) return task.toString();
        }
        if (args instanceof String) return (String) args;
        return args != null ? args.toString() : "";
    }

    /**
     * 根据 msgId 查找 msgTools 列表中的匹配消息。
     * @param msgId LLM tool call 中的 function name（对应 msg 的 msgid 属性）
     * @return 匹配的 TLMsg，未找到返回 null
     */
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

    protected TLMsg findMsgToolByMsgId(String msgId) {
        if (msgTools == null || msgId == null) return null;
        for (TLMsg msg : msgTools) {
            if (msgId.equals(msg.getMsgId())) {
                return msg;
            }
        }
        return null;
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

    // ======================== Provider 启动检查 ========================

    /**
     * 启动时检查 LLM Provider 是否可用。发送最小化请求验证 API Key / 余额。
     */
    protected boolean checkProvider() {
        if (llmProvider == null) {
            System.err.println("!!! [启动检查] LLM Provider 未加载！");
            return false;
        }
        try {
            List<TLConversationHistory> testHistory = new ArrayList<>();
            testHistory.add(new TLConversationHistory(TLConversationHistory.Role.user, "ping"));
            TLMsg testMsg = createMsg().setAction(LLM_COMPLETION)
                    .setParam(AI_P_MESSAGEHISTORY, testHistory)
                    .setParam(AI_P_MODEL, llmProvider.getDefaultModel())
                    .setParam(AI_P_MAXTOKENS, 1);
            TLMsg result = putMsg(llmProvider, testMsg);
            if (result.parseBoolean(RESULT, false)) {
                System.out.println("=== [启动检查] LLM Provider 可用: " + llmProvider.getDefaultModel() + " ===");
                return true;
            } else {
                int status = result.getIntParam(AI_P_HTTPSTATUS, 0);
                String body = result.getStringParam(AI_P_RESPONSEBODY, "");
                System.err.println("!!! [启动检查] LLM Provider 不可用！HTTP " + status + " body=" + body);
                System.err.println("!!! 请检查 API Key 和余额，或切换 Provider");
                llmProvider = null;  // 置空，chat() 调用时直接返回错误
                return false;
            }
        } catch (Exception e) {
            System.err.println("!!! [启动检查] LLM Provider 连接失败: " + e.getMessage());
            llmProvider = null;
            return false;
        }
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

    // ======================== Session 持久化/断点恢复 ========================

    /**
     * 保存会话到 JSON 文件。
     * @param state "checkpoint"（mid-loop 断点）或 "completed"（已完成）
     */
    @SuppressWarnings("unchecked")
    protected void persistSession(String sessionId, List<TLConversationHistory> history,
                                   String state, int iteration, String model,
                                   double temperature, int maxTokens, String userMessage) {
        if (!enableCheckpoint) return;
        try {
            java.io.File dir = new java.io.File(sessionStorePath);
            if (!dir.exists()) dir.mkdirs();

            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("sessionId", sessionId);
            data.put("state", state);
            data.put("iteration", iteration);
            data.put("model", model);
            data.put("temperature", temperature);
            data.put("maxTokens", maxTokens);
            data.put("userMessage", userMessage);
            data.put("history", history);
            data.put("savedAt", System.currentTimeMillis());

            String json = gson.toJson(data);
            java.io.File file = new java.io.File(dir, sessionId + ".json");
            java.nio.file.Files.write(file.toPath(), json.getBytes("UTF-8"));
        } catch (Exception e) {
            putLog("persistSession failed: " + e.toString(), LogLevel.WARN);
        }
    }

    /**
     * 加载会话检查点文件，返回 TLMsg 包含所有保存的字段。
     * @return null 表示文件不存在或加载失败
     */
    @SuppressWarnings("unchecked")
    protected TLMsg loadSessionCheckpoint(String sessionId) {
        if (!enableCheckpoint || sessionId == null) return null;
        try {
            java.io.File file = new java.io.File(sessionStorePath, sessionId + ".json");
            if (!file.exists()) return null;

            String json = new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");
            com.google.gson.JsonObject obj = gson.fromJson(json, com.google.gson.JsonObject.class);

            TLMsg result = new TLMsg();
            result.setParam("sessionId", obj.get("sessionId").getAsString());
            result.setParam("state", obj.get("state").getAsString());
            result.setParam("iteration", obj.get("iteration").getAsInt());
            result.setParam("model", obj.has("model") ? obj.get("model").getAsString() : "");
            result.setParam("temperature", obj.has("temperature") ? obj.get("temperature").getAsDouble() : 0.7);
            result.setParam("maxTokens", obj.has("maxTokens") ? obj.get("maxTokens").getAsInt() : 4096);
            result.setParam("userMessage", obj.has("userMessage") ? obj.get("userMessage").getAsString() : "");

            // 反序列化 history 列表
            com.google.gson.JsonArray histArray = obj.getAsJsonArray("history");
            List<TLConversationHistory> history = new ArrayList<>();
            for (int i = 0; i < histArray.size(); i++) {
                TLConversationHistory h = gson.fromJson(histArray.get(i), TLConversationHistory.class);
                history.add(h);
            }
            result.setParam("history", history);
            return result;
        } catch (Exception e) {
            putLog("loadSessionCheckpoint failed for " + sessionId + ": " + e.toString(), LogLevel.WARN);
            return null;
        }
    }

    /**
     * 删除会话持久化文件（清理用，如重置会话）
     */
    protected void deleteSessionFile(String sessionId) {
        try {
            java.io.File file = new java.io.File(sessionStorePath, sessionId + ".json");
            if (file.exists()) file.delete();
        } catch (Exception e) {
            putLog("deleteSessionFile failed: " + e.toString(), LogLevel.WARN);
        }
    }

    /**
     * 程序启动时自动恢复未完成的检查点（state=checkpoint），
     * 异步发送 resume 消息给自己，触发 doChat 从断点继续。
     */
    protected void autoResumeCheckpoints() {
        if (!enableCheckpoint) return;
        try {
            java.io.File dir = new java.io.File(sessionStorePath);
            if (!dir.exists() || !dir.isDirectory()) return;
            java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
            if (files == null) return;

            for (java.io.File f : files) {
                try {
                    TLMsg checkpoint = loadSessionCheckpoint(
                            f.getName().substring(0, f.getName().length() - 5));
                    if (checkpoint == null) continue;
                    String state = checkpoint.getStringParam("state", "");
                    if (!SESSION_STATE_CHECKPOINT.equals(state)) continue;

                    String sessionId = checkpoint.getStringParam("sessionId", "");
                    String userMessage = checkpoint.getStringParam("userMessage", "");

                    putLog("Auto-resuming checkpoint: " + sessionId, LogLevel.INFO);

                    TLMsg resumeMsg = createMsg()
                            .setAction(AGENT_CHAT)
                            .setParam(AI_P_SESSIONID, sessionId)
                            .setParam(AI_P_USERMESSAGE, userMessage)
                            .setParam("resume", true)
                            .setSystemParam(INTHREADPOOL, true);
                    putMsg(getName(), resumeMsg);
                } catch (Exception e) {
                    putLog("autoResumeCheckpoints skip " + f.getName() + ": " + e.toString(), LogLevel.WARN);
                }
            }
        } catch (Exception e) {
            putLog("autoResumeCheckpoints error: " + e.toString(), LogLevel.WARN);
        }
    }

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
