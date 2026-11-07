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

    /** 审批门禁模块名（断点恢复查决策用）。需与 toolExecutor 的 approvalModule 配置一致；
     *  默认 approvalGate，改名部署时在 agent <params> 里同步配置 approvalModule */
    protected String approvalModuleName = "approvalGate";

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

    /** 中断轮的终态文案：既回 UI，也作为 assistant 消息落盘（重登视图/下一轮上下文都能看到本轮已中断） */
    private static final String ABORTED_FINAL = "⏹ 已中断";

    /** 是否把工具进度状态发布到 msgBus（UI 订阅）；默认关闭，主 agent 配 true，子 agent 不配 */
    protected boolean toolProgressToBus = false;

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

    /**
     * 会话级 token 累计（sessionId → {prompt, completion, total}）。
     * agent 自身维护的一份，与 agentMonitor 的进程级/会话级统计独立。
     * <p><b>已知取舍</b>：本 map 以 sessionId 为键且从不删除——每个聊过天的会话永久留一条
     * （约 100 字节：三个 long）。同类 map 里 {@code chatThreads} 等 9 个都在终态清理里 remove，
     * 只有本 map 与 {@code cancelFlags} 没有。按当前规模（万级会话 ≈ 2MB）接受不处理；
     * 若将来单进程要服务百万级会话，再按 agentMonitor.evictOldest 的思路加界。</p>
     */
    private final Map<String, long[]> sessionTokenUsage = new ConcurrentHashMap<>();

    /**
     * 会话级取消标志（sessionId → cancelled）：/stop 置 true，chat 循环协作式退出。
     * 本 map 同样从不删除（见上面 sessionTokenUsage 的取舍说明）——一条 AtomicBoolean
     * 约 80 字节，万级会话无感；百万级会话时再按 evictOldest 的思路加界。
     */
    private final Map<String, java.util.concurrent.atomic.AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    /** 会话级 worker 线程：sessionId → 执行 doChat 的线程；stopChat 用于 interrupt + 定位其派生进程 */
    private final Map<String, Thread> chatThreads = new ConcurrentHashMap<>();

    /** 当前线程正在执行的 doChat 的根会话 ID（ThreadLocal，供 spawn 点透传给子 agent） */
    private final ThreadLocal<String> currentRootSessionId = new ThreadLocal<>();
    /** 当前 chat 的 userId（供 sessionUpdated 等消息传给 SessionManager，实现用户隔离） */
    private final ThreadLocal<String> currentChatUserId = new ThreadLocal<>();

    /** 会话 → 本轮流句柄（同一会话互斥，一次只有一个活跃流）。回调线程据此转发/唤醒。 */
    private final Map<String, StreamHandle> streamHandles = new ConcurrentHashMap<>();

    /**
     * 会话运行轮次计数（doChat + chatStream 统一登记；0=空闲）。多会话并行/后台任务注入排队的基础。
     * 与 sessionRoundLocks 配合：计数变更与"空闲判定+注入写 context"在同一把会话锁内，防 saveContextHistory
     * 的 REPLACE 语义互相覆盖。计数归零即从 map 移除；锁对象永不删除（与 cancelFlags 同取舍，量级=会话数）。
     */
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> sessionRunCounts = new ConcurrentHashMap<>();
    /** 会话级互斥锁（轮次开始/结束 vs 注入写入互斥） */
    private final Map<String, Object> sessionRoundLocks = new ConcurrentHashMap<>();
    /** 待注入父会话的任务结果（父会话忙时排队，轮末 flush）；key=父会话 sessionId */
    private final Map<String, List<TLMsg>> pendingTaskInjections = new ConcurrentHashMap<>();

    /** 注入轮 roundId 唯一序号（防同毫秒两条互相覆盖） */
    private final java.util.concurrent.atomic.AtomicLong injectSeq = new java.util.concurrent.atomic.AtomicLong();

    /** 会话锁对象（永不删除，量级=会话数） */
    private Object sessionLock(String sessionId) {
        return sessionRoundLocks.computeIfAbsent(sessionId, k -> new Object());
    }

    /** 轮次开始：计数 +1（持锁——与注入的"空闲判定+写入"原子化） */
    private void sessionRoundBegin(String sessionId) {
        synchronized (sessionLock(sessionId)) {
            sessionRunCounts.computeIfAbsent(sessionId,
                    k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
        }
    }

    /**
     * 轮次结束：计数 -1；归零时 flush 待注入的任务结果（仍持锁——与注入写入互斥，防 REPLACE 覆盖）。
     * 已知限制：同会话并发两条流属"上层已防"的假设（webui 按 user:session 加锁）；若被击穿，
     * 该会话的 per-session 状态（roundId/forwardMap 等）本就会错乱，计数残留偏大（fail-safe 方向）。
     */
    private void sessionRoundEnd(String sessionId) {
        synchronized (sessionLock(sessionId)) {
            java.util.concurrent.atomic.AtomicInteger c = sessionRunCounts.get(sessionId);
            if (c != null && c.decrementAndGet() <= 0) {
                sessionRunCounts.remove(sessionId);
            }
            // 只在真正归零时 flush：count>0 说明同会话还有在跑轮次（上层已防，但这里保持 fail-safe）
            if (getSessionRunCount(sessionId) == 0) {
                flushPendingInjections(sessionId);
            }
        }
    }

    /** 轮末 flush 待注入结果（调用方须持 sessionLock）；返回是否有注入发生 */
    private boolean flushPendingInjections(String sessionId) {
        List<TLMsg> queued = pendingTaskInjections.remove(sessionId);
        if (queued == null || queued.isEmpty()) return false;
        for (TLMsg item : queued) doInjectTaskResult(sessionId, item);
        return true;
    }

    /**
     * 后台任务结果注入（任务模块终态调用）：父会话空闲 → 立即写入 context；
     * 忙 → 入队，轮末由 sessionRoundEnd 自动 flush。
     * 消息 role=user 系统通知包装（与"（上一操作已被拒绝：…）"注入风格一致），后续 LLM 轮次可见。
     *
     * 锁语义：持会话锁做"空闲判定 + 写入"——与 sessionRoundBegin 互斥，保证新轮开始时
     * 一定看得到已注入的消息；写入内含 putMsg（context/sessionManager），锁序单向（本文件不回调），无环。
     */
    protected TLMsg injectTaskResult(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID,
                String.valueOf(msg.getSystemParam(AI_P_SESSIONID, "")));
        if (sessionId.isEmpty())
            return createMsg().setParam(RESULT, false).setParam("error", "missing sessionId");
        TLMsg item = createMsg()
                .setParam("taskId", msg.getStringParam("taskId", ""))
                .setParam("name", msg.getStringParam("name", msg.getStringParam("taskId", "")))
                .setParam("text", msg.getStringParam("text", ""))
                .setParam("userId", msg.getStringParam(AI_P_USERID, "default"));
        synchronized (sessionLock(sessionId)) {
            if (getSessionRunCount(sessionId) == 0) {
                doInjectTaskResult(sessionId, item);
                return createMsg().setParam(RESULT, true).setParam("injected", true);
            }
            // 所有增删都在会话锁内，无须 COW；每会话上限 50，超限丢最旧
            List<TLMsg> q = pendingTaskInjections.computeIfAbsent(sessionId, k -> new ArrayList<>());
            if (q.size() >= 50) {
                q.remove(0);
                putLog("注入队列超限，丢弃最旧一条: sessionId=" + sessionId, LogLevel.WARN);
            }
            q.add(item);
            return createMsg().setParam(RESULT, true).setParam("queued", true);
        }
    }

    /** 真正写入：追加 context + 持久化（调用方须持 sessionLock） */
    private void doInjectTaskResult(String sessionId, TLMsg item) {
        try {
            String taskName = item.getStringParam("name", item.getStringParam("taskId", ""));
            String wrapped = "【后台任务·" + taskName + "】已完成，结果如下：\n"
                    + item.getStringParam("text", "");
            TLConversationHistory entry =
                    new TLConversationHistory(TLConversationHistory.Role.user, wrapped);
            List<TLConversationHistory> history = getContextHistory(sessionId);
            history.add(entry);
            saveContextHistory(sessionId, history);
            String roundId = "taskinject_" + System.currentTimeMillis() + "_" + injectSeq.incrementAndGet();
            notifySessionManager(createMsg()
                    .setAction("sessionUpdated")
                    .setParam("sessionId", sessionId)
                    .setParam(AI_P_USERID, item.getStringParam("userId", "default"))
                    .setParam("agentName", name)
                    .setParam("roundId", roundId)
                    .setParam("messages", new ArrayList<>(List.of(entry)))
                    .setParam("userMessage", wrapped));
            // 同 roundId 立即标记 completed：注入轮不是断点，防止下次登录/恢复弹假 checkpoint 告警
            notifySessionManager(createMsg()
                    .setAction("chatFinished")
                    .setParam("sessionId", sessionId)
                    .setParam(AI_P_USERID, item.getStringParam("userId", "default"))
                    .setParam("agentName", name)
                    .setParam("roundId", roundId)
                    .setParam("messages", new ArrayList<>(List.of(entry)))
                    .setParam("userMessage", wrapped));
            putLog("Injected task result into session " + sessionId
                    + " taskId=" + item.getStringParam("taskId", ""), LogLevel.DEBUG);
        } catch (Exception e) {
            putLog("注入任务结果失败: " + e, LogLevel.WARN);
        }
    }

    /** 本会话在跑几轮（0=空闲；供 /sessions running、注入判定） */
    public int getSessionRunCount(String sessionId) {
        java.util.concurrent.atomic.AtomicInteger c = sessionRunCounts.get(sessionId);
        return c == null ? 0 : Math.max(0, c.get());
    }

    // ======================== Session 管理 ========================

    /** 会话管理模块名（SessionManager），Agent 通过发消息报告会话状态 */
    protected String sessionManagerName = "sessionManager";
    /** SessionManager 缺失只告警一次，避免每个 checkpoint 刷屏 */
    private volatile boolean sessionManagerWarned = false;

    /** 是否启用会话通知（发 sessionUpdated/chatFinished/chatAborted 给 SessionManager） */
    protected boolean enableCheckpoint = false;

    /** 首轮完成后是否自动请 LLM 生成会话标题（配置 autoSessionTitle=false 关闭） */
    private boolean autoSessionTitle = true;


    /** 数据存储基础路径（供 context/memory 等使用） */
    protected String dataBasePath = "./data/";

    /** 清单最多列几条附件 */
    protected int manifestMaxEntries = 20;
    /** 发送视图保留最近 K 条带图消息 */
    protected int maxImagesInContext = 1;
    /** 单条消息最多接受几条附件 */
    protected int maxAttachmentsPerMessage = 10;

    /** 工具产图安全阀：false = 任何工具产图都不注入（判断权在技能，这里只是兜底全禁） */
    protected boolean feedToolImages = true;
    /** 技能输出 JSON 里的表态键：值为 true 才投喂 */
    protected String imageDeclareKey = "image_for_model";
    /** 图数据 key 白名单：以 _path 结尾的按路径引用，其余按 base64 正文 */
    protected List<String> imageDataKeys =
            new ArrayList<>(Arrays.asList("screenshot_base64", "image_base64", "image_path"));
    /** 超过此 KB 数的输出不做图片解析 */
    protected int maxParseKB = 8192;

    /** JSON 序列化（复用 Gson，aiagent 已依赖） */
    protected com.google.gson.Gson gson;

    /** Phase 1：附件领域（归一化/清单/图片/白名单）与工具结果写入 */
    private final AttachmentPipeline attachments = new AttachmentPipeline(this);
    private final ToolResultAppender toolResultAppender = new ToolResultAppender(attachments);

    // ======================== 编排上下文 ========================

    /** 一轮聊天的编排上下文：跨阶段状态集中存放；回调线程不直接访问。 */
    private static final class ChatRunContext {
        final Object fromWho;
        final TLMsg msg;
        final String sessionId;
        final String rootSid;
        final String roundId;
        final String userId;
        String userMessage;   // prepareChat 会前置配置的 task 指令

        String model;
        double temperature;
        int maxTokens;
        String reasoningMode;
        boolean reasoningVisible;

        boolean resume;
        boolean resumedFromCheckpoint;
        boolean noHistory;

        List<TLConversationHistory> history;
        int msgStartIdx;
        /**
         * resume 时状态轮（待批/检查点）在 loadSession 里的已有消息块。
         * 落盘时作为本轮前缀（原轮消息 + 新增量）：同 roundId 写入是 last-wins，
         * 只写新增量会把原轮 messages 覆盖成"恢复后的部分"。
         */
        List<TLConversationHistory> resumeRoundMessages;
        String memoryContext;
        List<TLFunctionDefinition> toolDefs;

        // 生命周期标志：cleanup 只清理本上下文真正建立过的资源
        boolean lockAcquired;
        boolean monitorRegistered;
        boolean roundBegan;
        java.util.concurrent.atomic.AtomicBoolean cancelled;

        // 流式（Task 4 使用；非流式路径为 NullStreamSink）
        final StreamSink sink;
        final boolean preBegan;   // true = 入口已同步 sessionRoundBegin，prepareChat 不得重复 begin
        boolean streamFinished;
        boolean finalAnswerStreamed;

        // 预处理提前返回
        TLMsg terminalResult;
        boolean terminalAsFinal;

        boolean cancelled() {
            return cancelled != null && cancelled.get();
        }

        ChatRunContext(Object fromWho, TLMsg msg, String sessionId, String rootSid,
                       String roundId, StreamSink sink, boolean preBegan) {
            this.fromWho = fromWho;
            this.msg = msg;
            this.sessionId = sessionId;
            this.rootSid = rootSid;
            this.roundId = roundId;
            this.userId = TLMsgUtils.getSystemParamNonEmpty(msg, "userId", sessionId);
            this.userMessage = msg.getStringParam(AI_P_USERMESSAGE, "");
            this.sink = sink;
            this.preBegan = preBegan;
            this.roundBegan = preBegan;
        }
    }


    /** 把流事件转发给最终调用方：私有实例优先，否则按名投递。 */
    private final class ForwardStreamSink implements StreamSink {
        private final IObject forwardInstance;
        private final String resultFor;
        private final String resultAction;
        private final String sessionId;
        /** 帧必须自带 userId：webui 按 userId+sessionId 找 SSE 通道，不能依赖 sessionOwner 旁路状态 */
        private final String userId;
        /**
         * UI 实际收到的流式文本（chunk 累积）。不能依赖 Provider 的 done 消息是否带完整响应。
         * 前提：onChunk 之间串行（SSE 是串行字节流，Provider 在同一回调线程顺序 putMsg）；
         * 所有 onChunk 完成 → handle.complete → latch → worker 才读 buffer/发 onDone（happens-before）。
         * StringBuffer 只保证单次 append 原子，不保证跨 chunk 的 forward/append 顺序；
         * 若未来 Provider 改成并发回调，需把 forward + append 包进 synchronized(this)。
         */
        private final StringBuffer streamedBuffer = new StringBuffer();

        ForwardStreamSink(IObject forwardInstance, String resultFor,
                          String resultAction, String sessionId, String userId) {
            this.forwardInstance = forwardInstance;
            this.resultFor = resultFor;
            this.resultAction = resultAction;
            this.sessionId = sessionId;
            this.userId = userId;
        }

        /**
         * @return true = putMsg 调用未抛异常（目标模块内部静默失败无法感知；
         *         那种情况下 UI 已不可用，补发也无意义）
         */
        private boolean forward(TLMsg evt) {
            evt.setAction(resultAction);
            // 流式 UI 目标不可达属于可降级场景：静默丢弃，不能让 putMsg 关停整个应用
            evt.setSystemParam(IGNOREMODULEISNULL, true);
            if (userId != null && !userId.isEmpty()) {
                evt.setParam(AI_P_USERID, userId);
            }
            try {
                if (forwardInstance != null) putMsg(forwardInstance, evt);
                else putMsg(resultFor, evt);
                return true;
            } catch (Throwable t) {
                // UI 目标/路由异常不能杀死流式回调线程，更不能冒泡进框架异常链。
                // 用 System.err：putLog 本身走 putMsg，出错路径上再调用它可能二次抛。
                System.err.println("[ForwardStreamSink] forward failed: " + t);
                return false;
            }
        }

        @Override
        public String collectedText() {
            return streamedBuffer.toString();
        }

        public void onChunk(String text) {
            if (text == null || text.isEmpty()) return;
            // 顺序依赖 onChunk 串行前提（见 streamedBuffer 注释）：forward 与 append 必须同序
            boolean delivered = forward(createMsg().setParam(AI_P_CHUNK, text)
                    .setParam(AI_P_SESSIONID, sessionId));
            // 只有 putMsg 未抛异常才计入"UI 已收到"；失败时不记，让 onFinalText 补发
            //（宁重复勿缺失——失败却记下来会让 UI 永久少一段）。
            if (delivered) {
                streamedBuffer.append(text);
            }
        }

        public void onToolEvent(String toolName, String output) {
            if (output == null) return;
            forward(createMsg().setParam("toolEvent", true)
                    .setParam("toolName", toolName != null ? toolName : "tool")
                    .setParam("toolOutput", output)
                    .setParam(AI_P_SESSIONID, sessionId));
        }

        public void onFinalText(String answer, boolean alreadyStreamed) {
            if (answer == null || answer.isEmpty()) return;
            if (!alreadyStreamed) {
                forward(createMsg().setParam(AI_P_CHUNK, answer)
                        .setParam(AI_P_SESSIONID, sessionId));
                return;
            }
            // 用自身累积，而不是 Provider done 消息里的正文：
            // 流式终答的增量判断应该以"UI 到底收到了什么"为准。
            String seen = streamedBuffer.toString();
            if (answer.startsWith(seen)) {
                if (answer.length() == seen.length()) return;
                forward(createMsg().setParam(AI_P_CHUNK, answer.substring(seen.length()))
                        .setParam(AI_P_SESSIONID, sessionId));
                return;
            }
            // guardOutput 替换/截断/追加导致前缀关系不成立：
            // 不能按长度差做增量（会静默丢弃净化文本），发 replace 让 UI 用净化后的全文替换。
            forward(createMsg().setParam(AI_P_STREAMREPLACE, answer)
                    .setParam(AI_P_SESSIONID, sessionId));
        }

        public void onDone() {
            forward(createMsg().setParam(AI_P_STREAMDONE, true)
                    .setParam(AI_P_SESSIONID, sessionId));
        }

        public void onError(String message) {
            forward(createMsg().setParam(AI_P_STREAMERROR, message)
                    .setParam(AI_P_SESSIONID, sessionId));
        }
    }

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
            if (params.get("approvalModule") != null && !params.get("approvalModule").isEmpty())
                approvalModuleName = params.get("approvalModule");
            if (params.get("description") != null)
                agentDescription = params.get("description");
            if (params.get("agentMd") != null)
                agentMdPath = params.get("agentMd");
            if (params.get("maxToolCallIterations") != null) {
                try {
                    int v = Integer.parseInt(params.get("maxToolCallIterations").trim());
                    // 0/负数会让 ChatTurn 的 while 一次都不进，直接返回"已达上限 0"；
                    // 视为配置错误，保留默认值并留痕。
                    if (v > 0)
                        maxToolCallIterations = v;
                    else
                        putLog("maxToolCallIterations must be > 0, keep default "
                                + maxToolCallIterations + " (got " + v + ")", LogLevel.WARN);
                } catch (NumberFormatException ignored) {
                }
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
            if (params.get("toolProgressToBus") != null)
                toolProgressToBus = "true".equals(params.get("toolProgressToBus"));
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
            if (params.get("autoSessionTitle") != null)
                autoSessionTitle = !"false".equals(params.get("autoSessionTitle"));
            if (params.get("sessionManagerName") != null)
                sessionManagerName = params.get("sessionManagerName");
            if (params.get("dataBasePath") != null)
                dataBasePath = params.get("dataBasePath");
            else if (params.get("sessionStorePath") != null)
                dataBasePath = params.get("sessionStorePath"); // 兼容旧配置
            if (params.get("manifestMaxEntries") != null) {
                try { manifestMaxEntries = Integer.parseInt(params.get("manifestMaxEntries")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("maxImagesInContext") != null) {
                try { maxImagesInContext = Integer.parseInt(params.get("maxImagesInContext")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("maxAttachmentsPerMessage") != null) {
                try { maxAttachmentsPerMessage = Integer.parseInt(params.get("maxAttachmentsPerMessage")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("feedToolImages") != null)
                feedToolImages = !"false".equals(params.get("feedToolImages"));
            if (params.get("imageDeclareKey") != null) imageDeclareKey = params.get("imageDeclareKey");
            if (params.get("imageDataKeys") != null) {
                imageDataKeys = new ArrayList<>();
                for (String k : params.get("imageDataKeys").split(";"))
                    if (!k.trim().isEmpty()) imageDataKeys.add(k.trim());
            }
            if (params.get("maxParseKB") != null) {
                try { maxParseKB = Integer.parseInt(params.get("maxParseKB")); }
                catch (NumberFormatException ignored) {}
            }
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
        // Phase 1：附件配置快照；AttachmentPipeline 只读自己的副本，后续调用不再回读父字段
        attachments.refreshConfig(manifestMaxEntries, maxAttachmentsPerMessage,
                feedToolImages, imageDeclareKey, imageDataKeys, maxParseKB);
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
                // 缺模块必须静默：默认 putMsg 会 moduleFactory.shutdown(-1)，
                // owner 名拼错就把整个应用退掉，borrow 失败应走下面的 fallback。
                TLMsg borrowMsg = createMsg().setAction("getLlmProvider").setParam("provName", provName);
                borrowMsg.setSystemParam(IGNOREMODULEISNULL, true);
                TLMsg refResult = putMsg(ownerName, borrowMsg);
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
        // 通用投递：消息带子模块实例引用（systemArgs: targetInstance）→ 直接投给该实例。
        // 私有子模块外部按名够不着，实例由发起方自备；动作名原样保留（子模块自行分发）。
        Object dispatchTarget = msg.getSystemParam(AI_P_TARGETINSTANCE, null);
        if (dispatchTarget != null) return dispatchToInstance(msg);
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case AGENT_CHAT:
                returnMsg = chat(fromWho, msg);
                break;
            case AGENT_CHATSTREAM:
                returnMsg = chatStream(fromWho, msg);
                break;
            case "getSessionRunState":
                returnMsg = createMsg().setParam(RESULT, true)
                        .setParam("running", getSessionRunCount(
                                msg.getStringParam(AI_P_SESSIONID,
                                        String.valueOf(msg.getSystemParam(AI_P_SESSIONID, "default")))));
                break;
            case "injectTaskResult":
                returnMsg = injectTaskResult(fromWho, msg);
                break;
            case AGENT_HOTLOADSKILL:
            case AGENT_HOTUNLOADSKILL:
            case AGENT_RELOADSKILL:
            case AGENT_REGISTERSKILL:
            case AGENT_UNREGISTERSKILL:
            case AGENT_LISTSKILLS:
                // 工具管理内脏：薄转发给私有 toolManager，外部消息协议不变
                returnMsg = forwardToToolManager(msg);
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
                returnMsg = forwardToToolManager(msg);
                break;
            case AGENT_GETDESCRIPTION:
                returnMsg = createMsg().setParam(RESULT, true)
                        .setParam(AI_P_AGENTDESCRIPTION, agentDescription != null ? agentDescription : "");
                break;
            case "onStreamResult":
                returnMsg = onStreamResult(fromWho, msg);
                break;
            case "runChatEngineAsync":
                returnMsg = runChatEngineAsync(fromWho, msg);
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
            case TOOL_PROGRESS:
                returnMsg = handleToolProgress(fromWho, msg);
                break;
            default:
                returnMsg = null;
        }
        return returnMsg;
    }

    /**
     * 工具管理消息转发：toolManager 未初始化时返回结构化错误。
     * 不能直接 putMsg(null, msg)——同步路径会 NPE，框架异常链最终可能 shutdown(-1)
     * （与 runChat 里的 toolManager 守卫保持同一契约）。
     */
    private TLMsg forwardToToolManager(TLMsg msg) {
        if (toolManager == null) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "toolManager not initialized for agent " + name);
        }
        return putMsg(toolManager, msg);
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
        // 身份参数已在 systemArgs 里（执行器传入，msgTable 路由时 doMsgList 的 addSystemArgs 会继续传播），
        // 无需再"存在则取原值 set 回原键"——那是空操作
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
        // 与工具业务参数分层——上游经执行器以 systemArgs 传来，此处原样透传。
        // 只在值非空时复制：present-but-empty 会让下游把 "" 当会话 id（跳过"缺省生成"）。
        TLMsgUtils.copySystemParamIfNotEmpty(msg, chatMsg, AI_P_SESSIONID);
        TLMsgUtils.copySystemParamIfNotEmpty(msg, chatMsg, "rootSessionId");
        TLMsgUtils.copySystemParamIfNotEmpty(msg, chatMsg, "userId");
        // 全链追踪：上游 roundId 透传——子 agent 的一轮就是上游的一轮
        TLMsgUtils.copySystemParamIfNotEmpty(msg, chatMsg, AI_P_ROUNDID);
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
        String sid = resolveSessionId(msg, "default");
        boolean cascade = msg.parseBoolean("cascade", false);
        cancelFlags.computeIfAbsent(sid, k -> new java.util.concurrent.atomic.AtomicBoolean()).set(true);
        // 1) 取消在途 LLM HTTP（级联模式跳过：子 agent 共享 provider，误杀会波及其他会话）
        // 带上 sessionId：provider 跨会话共享，不指定的话 cancel 会把别的会话在途调用一起打断
        if (!cascade && llmProvider != null) {
            putMsg(llmProvider, createMsg().setAction(LLM_CANCEL).setParam(AI_P_SESSIONID, sid));
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
        return doChat(fromWho, msg);
    }

    /** 一轮聊天的上下文工厂：ID 一次算定（上游透传 > resumeRoundId > 新生成）。 */
    private ChatRunContext newContext(Object fromWho, TLMsg msg,
                                      StreamSink sink, boolean preBegan) {
        // 防御：调用方漏带 _chatRequest 时构造器里的 msg.getSystemParam 会 NPE。异常若逃出
        // try/finally，chatStream 已 sessionRoundBegin 的计数就永远漏掉（会话永久"忙"，
        // 后台任务结果再也不会 flush）。空 msg 交给 prepareChat 的 "missing userMessage" 出口收尾。
        if (msg == null) {
            putLog("runChat: null chat request (missing _chatRequest), treated as empty message",
                    LogLevel.WARN);
            msg = createMsg();
        }
        String sessionId = resolveSessionId(msg, "default");
        String rootSid = TLMsgUtils.getSystemParamNonEmpty(msg, "rootSessionId", sessionId);
        String upstreamRoundId = TLMsgUtils.getSystemParamNonEmpty(msg, AI_P_ROUNDID, "");
        // resumeRoundId 为空串（老数据无 roundId）不能直接当本轮 id：空 roundId 会让该会话
        // 所有轮次按 roundId="" 落盘，assembleSession 的 last-wins 把它们覆盖成一轮
        String resumeRoundId = msg.getStringParam("resumeRoundId", "");
        if ("null".equals(resumeRoundId)) resumeRoundId = "";
        String roundId = !upstreamRoundId.isEmpty() ? upstreamRoundId
                : (!resumeRoundId.isEmpty() ? resumeRoundId : "r_" + System.currentTimeMillis());
        ChatRunContext ctx = new ChatRunContext(fromWho, msg, sessionId, rootSid, roundId,
                sink, preBegan);
        ctx.noHistory = noHistory;
        return ctx;
    }

    /** 可空字符串参数回落：null/空串视为未配置 */
    private static String nonEmpty(String v, String def) {
        return (v == null || v.isEmpty()) ? def : v;
    }

    /**
     * 统一 sessionId 取值：systemArgs 优先（chat 的既有契约），缺失/空串再回落 args
     * （stopChat 的既有契约），最后用默认值。避免 doChat/chatStream 用 getSystemParam、
     * stopChat 用 getStringParam 时，同一消息在两条路径算出不同 key。
     */
    static String resolveSessionId(TLMsg msg, String defaultValue) {
        if (msg == null) return defaultValue;
        String sid = TLMsgUtils.getSystemParamNonEmpty(msg, AI_P_SESSIONID, null);
        if (sid == null || sid.isEmpty()) sid = msg.getStringParam(AI_P_SESSIONID, null);
        if (sid == null || sid.isEmpty() || "null".equals(sid)) return defaultValue;
        return sid;
    }

    /**
     * 预处理：会话/锁/监控/记忆召回/断点恢复/模板替换/toolDefs/推理 prompt。
     * 流式与非流式共用；只把结果写进 ctx，不执行 LLM 循环。
     */
    private void prepareChat(ChatRunContext ctx) {
        TLMsg msg = ctx.msg;
        String sessionId = ctx.sessionId;

        if (llmProvider == null) {
            ctx.terminalResult = createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, "LLM Provider 未就绪，请检查 API Key 和余额后重启");
            return;
        }
        // 固定任务前缀：普通 agent / 工作流下游节点都经此生效
        if (params != null && params.get("task") != null && !params.get("task").trim().isEmpty()) {
            ctx.userMessage = params.get("task").trim() + "\n\n" + ctx.userMessage;
        }
        // getStringParam 对 present-but-empty 返回空串：空 model 会让请求体 model="" 直接 400，
        // 空 reasoningMode 会绕过下面的 "off" 判定。空值一律按"未配置"回落默认。
        ctx.model = nonEmpty(msg.getStringParam(AI_P_MODEL, ""), llmProvider.getDefaultModel());
        ctx.temperature = msg.getDoubleParam(AI_P_TEMPERATURE, defaultTemperature);
        ctx.maxTokens = msg.getIntParam(AI_P_MAXTOKENS, defaultMaxTokens);
        ctx.reasoningMode = nonEmpty(msg.getStringParam(AI_P_REASONING_MODE, ""), reasoningMode);
        ctx.reasoningVisible = msg.parseBoolean(AI_P_REASONING_VISIBLE, reasoningVisible);
        if ("auto".equals(ctx.reasoningMode)) {
            ctx.reasoningMode = resolveAutoMode(ctx.model);
        }
        if (ctx.userMessage.isEmpty()) {
            ctx.terminalResult = createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, "Error: missing userMessage");
            return;
        }

        putLog("Chat start: sessionId=" + sessionId
                + " reasoningMode=" + ctx.reasoningMode, LogLevel.DEBUG);
        // 取消标志：复位在入口（doChat / chatStream），这里只取引用
        ctx.cancelled = cancelFlags.computeIfAbsent(sessionId,
                k -> new java.util.concurrent.atomic.AtomicBoolean());

        // 同会话锁：先拿锁，再写任何会话状态（防止锁失败的并发方覆盖活跃方）。
        // 原实现 putIfAbsent + 发现死线程后 put 覆盖不是原子的：两个并发方都看到同一个
        // 死线程时会各自 put 成功，双双认为自己拿到锁，同会话被两条流水线同时处理。
        // compute 把"判活 + 抢占"压成一个原子操作。
        final Thread me = Thread.currentThread();
        // 同线程重入（如 msgTool 路由回自身 chat、自引用 executeAsTool）：锁已被本线程持有，
        // compute 会返回 me。此时本层是"继承"外层锁而非真正获取，收尾时不得释放——否则内层
        // chatThreads.remove 会把外层还在使用的锁删掉，其他线程即可并发进入同一会话。
        boolean alreadyOwned = chatThreads.get(sessionId) == me;
        Thread winner = chatThreads.compute(sessionId, (k, cur) ->
                (cur == null || !cur.isAlive()) ? me : cur);
        if (winner != me) {
            ctx.terminalResult = createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, "会话使用中：该会话已有对话正在进行，请等待其完成")
                    .setParam(AI_P_SESSIONID, sessionId);
            return;
        }
        ctx.lockAcquired = !alreadyOwned;

        currentRootSessionId.set(ctx.rootSid);
        currentChatUserId.set(ctx.userId);

        // IGNOREMODULEISNULL：监控是可选模块，未配置时不能走 putMsg 的默认
        // "模块不存在即 shutdown(-1)" 分支把整个应用带走
        putMsg(M_AGENTMONITOR, createMsg().setAction("register")
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam("rootSessionId", ctx.rootSid)
                .setParam(AI_P_USERID, ctx.userId)
                .setParam("agentName", getName())
                .setSystemParam(IGNOREMODULEISNULL, true));
        ctx.monitorRegistered = true;

        traceStage(sessionId, ctx.rootSid, ctx.roundId, "roundStart",
                ctx.userMessage, 0, ctx.userMessage, null);
        if (!ctx.preBegan) {
            sessionRoundBegin(sessionId);   // preBegan=true 时由入口（chatStream）已登记
        }
        ctx.roundBegan = true;

        // ==== 记忆召回 ====
        TLMsg beforeResult = (TLMsg) msg.getSystemParam(PRERESULT);
        if (beforeResult != null && beforeResult.containsParam(AI_P_MEMORYRESULT)) {
            List<TLMemoryEntry> entries = (List<TLMemoryEntry>)
                    beforeResult.getListParam(AI_P_MEMORYRESULT, null);
            if (entries != null && !entries.isEmpty()) {
                StringBuilder mc = new StringBuilder("以下是你过往的历史记忆，请根据当前对话自行判断哪些相关：\n");
                for (TLMemoryEntry e : entries) mc.append("- ").append(e.getValue()).append("\n");
                ctx.memoryContext = mc.toString();
            }
        }

        prepareHistory(ctx);
        if (ctx.terminalResult != null) return;

        // 对话开始即保存 checkpoint 轮（含用户消息）
        notifySessionManager(createMsg()
                .setAction("sessionUpdated")
                .setParam("sessionId", sessionId)
                .setParam("userId", ctx.userId)
                .setParam("agentName", name)
                .setParam("roundId", ctx.roundId)
                .setParam("messages", roundMessages(ctx))
                .setParam("model", ctx.model)
                .setParam("temperature", ctx.temperature)
                .setParam("maxTokens", ctx.maxTokens)
                .setParam("userMessage", ctx.userMessage));

        // 模板变量替换（LLMINPUT 分支也覆盖）
        java.text.SimpleDateFormat dateFmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String nowStr = dateFmt.format(new java.util.Date());
        java.util.regex.Pattern varPattern = java.util.regex.Pattern.compile("\\{\\{(\\w+)\\}\\}");
        for (TLConversationHistory h : ctx.history) {
            if (h.getContent() == null || h.getContent().isEmpty()) continue;
            java.util.regex.Matcher m = varPattern.matcher(h.getContent());
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String value = resolveVar(m.group(1), msg, sessionId, nowStr);
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(value));
            }
            m.appendTail(sb);
            h.setContent(sb.toString());
        }

        // 黑盒消息接口之一：向工具模块索取 LLM 函数定义列表。
        // putMsg 在异常/模块不可用时可能返回 null——空工具集也比 NPE 收尾好。
        TLMsg frDefsMsg = putMsg(toolManager, createMsg().setAction(AGENT_GETFUNCTIONDEFS));
        if (frDefsMsg == null)
            putLog("AGENT_GETFUNCTIONDEFS returned null, proceed without tools", LogLevel.WARN);
        ctx.toolDefs = frDefsMsg != null
                ? (List<TLFunctionDefinition>) frDefsMsg.getListParam(AI_P_FUNCTIONDEFS, new ArrayList<>())
                : new ArrayList<>();

        // ==== prompt 模式：注入 💭 推理引导到 system prompt ====
        if ("prompt".equals(ctx.reasoningMode) && ctx.toolDefs != null && !ctx.toolDefs.isEmpty()) {
            String reasoningPrompt = "\n\n## 推理规则\n"
                    + "当需要调用工具时，请先用 💭 开头写一行简短推理，说明你为什么要调用这个工具。\n"
                    + "格式: 💭 <一句话推理>\n"
                    + "然后正常调用工具。不需要在最终回复中保留 💭 内容。";
            boolean injected = false;
            for (TLConversationHistory h : ctx.history) {
                if (h.getRole() == TLConversationHistory.Role.system && h.getContent() != null) {
                    h.setContent(h.getContent() + reasoningPrompt);
                    injected = true;
                    break;
                }
            }
            if (!injected) {
                ctx.history.add(0, new TLConversationHistory(TLConversationHistory.Role.system, reasoningPrompt));
            }
        }
    }

    /**
     * 构建本轮 history：断点恢复 / LLM 直入 / 正常三分支。
     * 审批恢复的重工具执行仍在这里（与非流式语义一致）。
     */
    @SuppressWarnings("unchecked")
    private void prepareHistory(ChatRunContext ctx) {
        TLMsg msg = ctx.msg;
        String sessionId = ctx.sessionId;
        List<TLConversationHistory> history;
        int msgStartIdx = 0;
        boolean resumedFromCheckpoint = false;
        String model = ctx.model;
        double temperature = ctx.temperature;
        int maxTokens = ctx.maxTokens;

        boolean resume = msg.parseBoolean("resume", false);
        ctx.resume = resume;

        // resume 语义成立的前提：调用方已把断点历史载入。TLAgentService 在 loadSession
        // 失败时不设置 history——此前会静默落入正常分支（context 为空 → 当新对话跑），
        // 恢复失败被伪装成成功。llmInput 直入是另一条独立通道，不在此守卫内。
        if (resume && !msg.containsParam("history") && !msg.containsParam(AI_P_LLMINPUT)) {
            ctx.terminalResult = createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, "断点恢复失败：会话数据不可用");
            return;
        }

        // 原轮已有消息块（service 从 loadSession 透传）：恢复轮的落盘前缀，防 last-wins 丢历史
        if (msg.containsParam("resumeRoundMessages")) {
            Object rm = msg.getParam("resumeRoundMessages");
            if (rm instanceof List) ctx.resumeRoundMessages = (List<TLConversationHistory>) rm;
        }

        if (resume && msg.containsParam("history")) {
            // resume=true 且调用方已传入 history → 从断点/历史会话恢复
            @SuppressWarnings("unchecked")
            List<TLConversationHistory> loadedHistory =
                    (List<TLConversationHistory>) msg.getParam("history");
            // containsParam 只看 key 是否存在：调用方可能显式传 history=null。
            // withContextSystem(null) 返回 null，后续 history.size() 会 NPE 成 "Agent error: null"。
            if (loadedHistory == null) {
                ctx.terminalResult = createMsg().setParam(RESULT, false)
                        .setParam(AI_P_RESPONSE, "断点恢复失败：history 为空");
                return;
            }
            String resumeState = msg.getStringParam("resumeState", SESSION_STATE_CHECKPOINT);
            if (SESSION_STATE_PENDING_APPROVAL.equals(resumeState)) {
                // ==== pending_approval 断点恢复 ====
                history = withContextSystem(loadedHistory);
                msgStartIdx = history.size();
                model = nonEmpty(msg.getStringParam("resumeModel", ""), model);
                temperature = msg.getDoubleParam("resumeTemperature", temperature);
                maxTokens = msg.getIntParam("resumeMaxTokens", maxTokens);
                resumedFromCheckpoint = true;

                TLToolCall savedTc = msg.containsParam("pendingToolCall")
                        ? (TLToolCall) msg.getParam("pendingToolCall") : null;
                String savedApprovalId = msg.getStringParam(AI_P_APPROVAL_ID, "");

                // 决策写回：调用方带了 decision（外部驱动）直接用；否则向审批模块查终态
                String decision = msg.getStringParam(AI_P_APPROVAL_DECISION, "");
                TLMsg approvalState = null;
                if (decision.isEmpty()) {
                    approvalState = queryApproval(savedApprovalId);
                    decision = approvalState != null
                            ? approvalState.getStringParam("decision", "pending") : "pending";
                }
                if ("approved".equals(decision) && savedTc != null) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> modifiedArgs =
                            msg.getMapParam(AI_P_APPROVAL_MODIFIEDARGS,
                                    approvalState != null
                                            ? approvalState.getMapParam(AI_P_APPROVAL_MODIFIEDARGS, null) : null);
                    if (modifiedArgs != null && !modifiedArgs.isEmpty()) {
                        savedTc.setArguments(modifiedArgs);
                    }
                    java.util.List<TLToolCall> singleTc = new java.util.ArrayList<>();
                    singleTc.add(savedTc);
                    history.add(new TLConversationHistory(
                            TLConversationHistory.Role.assistant, singleTc));
                    TLMsg frResolveMsg = putMsg(toolManager, createMsg().setAction(AGENT_RESOLVETOOLCALLS)
                            .setParam(AI_P_TOOLCALLS, singleTc));
                    if (frResolveMsg == null)
                        putLog("Approval resume resolveToolCalls returned null", LogLevel.WARN);
                    List<TLToolExecutor.ToolTask> singleTask = frResolveMsg != null
                            ? (List<TLToolExecutor.ToolTask>) frResolveMsg.getListParam("tasks", new ArrayList<>())
                            : new ArrayList<>();
                    if (singleTask.size() > 1) {
                        putLog("Approval resume resolved to " + singleTask.size()
                                + " tasks; 免复批 bypass covers single-task batches only", LogLevel.WARN);
                    }
                    String execId2 = sessionId + "_resume_" + System.nanoTime();
                    TLMsg singleExecResult = singleTask.isEmpty() ? null
                            : putMsg(toolExecutor, createMsg().setAction(TODOOLEXECUTE)
                                    .setParam("tasks", singleTask)
                                    .setSystemParam("executionId", execId2)
                                    .setSystemParam(AI_P_SESSIONID, sessionId)
                                    .setSystemParam("userId", ctx.userId)
                                    .setSystemParam("rootSessionId", ctx.rootSid)
                                    .setSystemParam(AI_P_ROUNDID, ctx.roundId)
                                    .setSystemParam(AI_P_APPROVAL_DECISION, "approved"));   // 免复批
                    List<TLAttachmentRef> pendingImages = new ArrayList<>();
                    TLToolExecutor.ToolResult singleResult = null;
                    if (singleExecResult != null) {
                        @SuppressWarnings("unchecked")
                        List<TLToolExecutor.ToolResult> singleResults =
                                (List<TLToolExecutor.ToolResult>) singleExecResult.getListParam("results", null);
                        if (singleResults != null && !singleResults.isEmpty())
                            singleResult = singleResults.get(0);
                    }
                    TLToolExecutor.ToolResult restoreResult = singleResult;
                    if (restoreResult == null) {
                        // 空 output 会让模型以为"工具成功但没内容"；给明确失败原因，
                        // 至少能判断是执行器无响应/任务被取消，而不是继续编造结果
                        restoreResult = new TLToolExecutor.ToolResult(savedTc.getId(),
                                "[审批恢复] 工具 " + savedTc.getFunctionName()
                                        + " 执行未返回结果（执行器无响应或任务被取消）");
                    }
                    toolResultAppender.appendOne(history, savedTc.getFunctionName(), restoreResult,
                            sessionId, ctx.userId, pendingImages);
                    attachments.flushToolImages(history, pendingImages);
                    putLog("Approval resumed: approved → tool executed: " + savedTc.getFunctionName(),
                            LogLevel.INFO);
                } else if ("approved".equals(decision)) {
                    putLog("Approval resumed but pendingToolCall missing: approvalId=" + savedApprovalId, LogLevel.ERROR);
                    history.add(new TLConversationHistory(
                            TLConversationHistory.Role.user,
                            "（上一操作无法恢复：审批已通过但断点工具调用缺失。如需执行请重新发起。）"));
                } else if ("rejected".equals(decision)) {
                    String reason = msg.getStringParam(AI_P_APPROVAL_REJECTREASON,
                            approvalState != null
                                    ? approvalState.getStringParam(AI_P_APPROVAL_REJECTREASON, "用户拒绝") : "用户拒绝");
                    history.add(new TLConversationHistory(
                            TLConversationHistory.Role.user,
                            "（上一操作已被拒绝：" + reason + "。请寻找替代方案。）"));
                    putLog("Approval resumed: rejected → reason=" + reason, LogLevel.INFO);
                } else if ("lost".equals(decision)) {
                    String reason = approvalState != null
                            ? approvalState.getStringParam(AI_P_APPROVAL_REJECTREASON, "审批已失效") : "审批已失效";
                    history.add(new TLConversationHistory(
                            TLConversationHistory.Role.user,
                            "（上一操作未执行：审批已失效——" + reason + "。如需执行请重新发起。）"));
                    putLog("Approval resumed: lost → reason=" + reason, LogLevel.WARN);
                } else {
                    putLog("Approval still pending on resume: approvalId=" + savedApprovalId, LogLevel.WARN);
                    ctx.terminalResult = createMsg().setParam(RESULT, false)
                            .setParam(AI_P_RESPONSE, "审批请求仍在等待中，approvalId=" + savedApprovalId);
                    ctx.terminalAsFinal = true;
                    return;
                }
            } else {
                // L2: mid-loop checkpoint 恢复 / L1: completed 会话恢复
                history = withContextSystem(loadedHistory);
                msgStartIdx = history.size();
                model = nonEmpty(msg.getStringParam("resumeModel", ""), model);
                temperature = msg.getDoubleParam("resumeTemperature", temperature);
                maxTokens = msg.getIntParam("resumeMaxTokens", maxTokens);
                resumedFromCheckpoint = true;
                putLog("Resumed from checkpoint: sessionId=" + sessionId
                        + " iter=0 historySize=" + history.size(), LogLevel.INFO);
            }
        } else if (msg.containsParam(AI_P_LLMINPUT)) {
            // LLM 消息直入：调用方已提供完整 messages（如 trace 定点重放）。
            // 输入是"重放起点的消息前缀"；本轮落盘只应记重放新产生的消息——
            // 从 0 记会把前缀整段当成新一轮增量，下次 continue 时与原轮重复膨胀。
            @SuppressWarnings("unchecked")
            List<TLConversationHistory> input =
                    (List<TLConversationHistory>) msg.getParam(AI_P_LLMINPUT);
            history = withContextSystem(input);
            msgStartIdx = history.size();
        } else {
            // 正常流程：从 context 构建历史（全量，摘要模式不 trim）。
            // noHistory 模式：不载入历史（每次白纸）
            history = ctx.noHistory ? new ArrayList<>() : getContextHistory(sessionId);
            msgStartIdx = history.size();
            // 附件归一化只在此处做：只有本分支才真正构建用户条目
            List<TLAttachmentRef> roundAttachments = attachments.normalize(msg, sessionId, ctx.userId);
            history.add(attachments.buildUserEntry(ctx.userMessage, roundAttachments));
        }

        ctx.history = history;
        ctx.msgStartIdx = msgStartIdx;
        ctx.model = model;
        ctx.temperature = temperature;
        ctx.maxTokens = maxTokens;
        ctx.resumedFromCheckpoint = resumedFromCheckpoint;
    }

    /** 生命周期收尾：只清理本上下文真正建立过的资源。 */
    private void cleanupChatRun(ChatRunContext ctx) {
        if (ctx.lockAcquired) {
            // 只删仍属于本线程的锁：重入层（lockAcquired=false）不会走到这里，外层锁得以保留
            chatThreads.compute(ctx.sessionId,
                    (k, cur) -> (cur == Thread.currentThread()) ? null : cur);
            // 只有拿到锁的 worker 才拥有这些会话状态；锁失败的并发方不能删活跃方的 map
            // 放锁守卫内：块外会让锁失败的并发方删掉活跃方的 handle，直接打断对方的流。
            // 按 roundId 原子删除：旧轮收尾不能误删恰好在放锁后注册的新轮 handle。
            StreamHandle currentHandle = streamHandles.get(ctx.sessionId);
            if (currentHandle != null && ctx.roundId.equals(currentHandle.roundId)) {
                streamHandles.remove(ctx.sessionId, currentHandle);
            }
            // monitor 注册、ThreadLocal 上下文同样属于"持锁的这一层"：重入层不能替外层注销，
            // 否则外层后续循环拿不到 rootSessionId/userId、监控也提前消失
            if (ctx.monitorRegistered) {
                putMsg(M_AGENTMONITOR, createMsg().setAction("unregister")
                        .setParam(AI_P_SESSIONID, ctx.sessionId)
                        .setSystemParam(IGNOREMODULEISNULL, true));
            }
            // 同一线程还嵌套着别的会话（本线程仍持有其他会话锁）时，ThreadLocal 归最外层清理，
            // 内层提前 remove 会让外层后半程读不到 rootSessionId/userId
            if (!chatThreads.containsValue(Thread.currentThread())) {
                currentRootSessionId.remove();
                currentChatUserId.remove();
            }
        }
        while (Thread.interrupted()) { /* drain all pending interrupt flags */ }
        if (ctx.roundBegan) sessionRoundEnd(ctx.sessionId);
    }

    /**
     * 统一chat实现：非流式 tool-call 多轮迭代。
     * 记忆召回、上下文、长期记忆保存等生命周期处理在 chatStream 中复用（见 runChat）。
     */
    protected TLMsg doChat(Object fromWho, TLMsg msg) {
        // 取消标志只在同步入口复位；异步入口在 chatStream 提交 worker 前复位。
        // runChat 里绝不能再复位：worker 启动会晚于 chatStream 返回，
        // 二次复位会抹掉这期间到达的 /stop（interrupt 因 chatThreads 尚未登记而落空）。
        String sid = resolveSessionId(msg, "default");
        cancelFlags.computeIfAbsent(sid,
                k -> new java.util.concurrent.atomic.AtomicBoolean()).set(false);
        return runChat(fromWho, msg, StreamSink.NullStreamSink.INSTANCE, false);
    }

    /** 一轮聊天的唯一生命周期外壳：准备 → ChatTurn 执行 → 收尾。 */
    private TLMsg runChat(Object fromWho, TLMsg msg,
                          StreamSink sink, boolean preBegan) {
        ChatRunContext ctx = null;
        try {
            // newContext 放进 try：流式入口（chatStream）已同步 sessionRoundBegin，若这里在
            // try 外抛异常，finally 覆盖不到 → 会话计数永久 >0，后台任务结果永远不进 context
            ctx = newContext(fromWho, msg, sink, preBegan);
            // initToolManager 失败时只打 WARN、toolManager 保持 null；继续跑会在
            // putMsg(toolManager, ...) 处 NPE（prepareHistory 恢复分支 / prepareChat 取函数定义）。
            // 放在 try 内：cleanupChatRun 负责释放会话轮次，streamError 负责给流式前端发终帧。
            if (toolManager == null) {
                String error = "Agent [" + name + "] 的工具管理模块未初始化（initToolManager 失败），拒绝执行 chat";
                putLog(error, LogLevel.ERROR);
                streamError(ctx, error);
                return createMsg().setParam(RESULT, false).setParam(AI_P_RESPONSE, error);
            }
            if (toolExecutor == null) {
                String error = "Agent [" + name + "] 的工具执行模块未初始化（toolExecutor 未配置），拒绝执行 chat";
                putLog(error, LogLevel.ERROR);
                streamError(ctx, error);
                return createMsg().setParam(RESULT, false).setParam(AI_P_RESPONSE, error);
            }
            prepareChat(ctx);
            if (ctx.terminalResult != null) {
                if (ctx.terminalAsFinal)
                    streamFinal(ctx, ctx.terminalResult.getStringParam(AI_P_RESPONSE, ""));
                else
                    streamError(ctx, ctx.terminalResult.getStringParam(AI_P_RESPONSE, "Agent error"));
                return ctx.terminalResult;
            }
            final ChatRunContext runCtx = ctx;
            TurnPlan plan;
            if (msg != null && msg.containsParam(AI_P_LLMINPUT)) {
                plan = TurnPlan.LLM_INPUT;
            } else if (ctx.resume) {
                plan = SESSION_STATE_PENDING_APPROVAL.equals(
                        msg.getStringParam("resumeState", SESSION_STATE_CHECKPOINT))
                        ? TurnPlan.RESUME_PENDING : TurnPlan.RESUME_CHECKPOINT;
            } else {
                plan = TurnPlan.NORMAL;
            }
            int reqMax = msg.getIntParam(AI_P_MAXTOOLCALLITERATIONS, 0);
            int turnMax = reqMax > 0 ? reqMax : maxToolCallIterations;
            TurnSeed seed = new TurnSeed(msg, ctx.sessionId, ctx.rootSid, ctx.roundId,
                    ctx.userId, name, ctx.userMessage, plan,
                    ctx.history, ctx.msgStartIdx, ctx.resumeRoundMessages,
                    ctx.memoryContext, ctx.toolDefs,
                    ctx.model, ctx.temperature, ctx.maxTokens,
                    ctx.reasoningMode, ctx.reasoningVisible,
                    ctx.noHistory, intentCacheEnabled, turnMax, thinkingBudget,
                    sink, () -> runCtx.cancelled.get(), null);
            TurnRoles roles = new TurnRoles(ctx);
            TurnDeps turnDeps = new TurnDeps(getFactory(), this, llmProvider,
                    toolManager, toolExecutor, roles, roles, roles,
                    attachments, toolResultAppender, roles);
            return new ChatTurn("turn:" + name + ":" + ctx.roundId,
                    getFactory(), seed, turnDeps).run();
        } catch (Exception e) {
            if (ctx == null) {
                // 上下文构造失败（ctx 尚未建立）：没有会话状态可清理，错误按入口约定告知前端
                String error = "Agent error: " + e.getMessage();
                putLog("Chat context init error: " + e, LogLevel.ERROR);
                if (sink != null) {
                    try { sink.onError(error); } catch (Throwable ignored) { /* sink 自带兜底 */ }
                }
                return createMsg().setParam(RESULT, false).setParam(AI_P_RESPONSE, error);
            }
            if (ctx.cancelled() || e instanceof InterruptedException) {
                Thread.interrupted();
                putLog("Chat aborted (exception path): sessionId=" + ctx.sessionId, LogLevel.INFO);
                streamFinal(ctx, "⏹ 已中断");
                return createMsg().setParam(RESULT, true).setParam(AI_P_CANCELLED, true)
                        .setParam(AI_P_RESPONSE, "⏹ 已中断").setParam(AI_P_SESSIONID, ctx.sessionId);
            }
            putLog("Chat error: " + e.toString(), LogLevel.ERROR);
            handleRunError(ctx, "Agent error: " + e.getMessage());
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, "Agent error: " + e.getMessage());
        } finally {
            if (ctx != null) {
                cleanupChatRun(ctx);
            } else if (preBegan) {
                // newContext 抛异常（ctx 未建立）时 chatStream 已登记轮次，本 finally 必须归还，
                // 否则会话计数永久 >0，后台任务结果永远不进 context
                sessionRoundEnd(msg == null ? "default"
                        : String.valueOf(msg.getSystemParam(AI_P_SESSIONID, "default")));
            }
        }
    }

    /**
     * 一轮的 Agent 侧角色：把 checkpoint/commit、trace、发送视图接到现有实现上。
     * 只服务当前这一轮（持有 ChatRunContext，用于 fromWho 等上下文）。
     */
    private final class TurnRoles implements TurnCommitter, TurnObserver,
            TurnViewBuilder, StreamHandleRegistry {

        private final ChatRunContext ctx;

        TurnRoles(ChatRunContext ctx) {
            this.ctx = ctx;
        }

        // ===== TurnViewBuilder =====
        @Override
        public List<TLConversationHistory> build(List<TLConversationHistory> history,
                                                 String memoryContext) {
            return buildSendList(history, memoryContext);
        }

        // ===== TurnObserver =====
        @Override
        public void traceStage(String sessionId, String rootSid, String roundId, String userId,
                               String stage, String detail, long durationMs, String payload) {
            TLAiAgent.this.traceStage(sessionId, rootSid, roundId, stage, detail,
                    durationMs, payload, userId);
        }

        @Override
        public String formatHistory(List<TLConversationHistory> history) {
            return formatHistoryForTrace(history);
        }

        @Override
        public String formatResponse(String content, List<TLToolCall> toolCalls, String reasoning) {
            return formatResponseForTrace(content, toolCalls, reasoning);
        }

        @Override
        public String withTruncationNotice(String text, String finishReason) {
            return TLAiAgent.this.withTruncationNotice(text, finishReason);
        }

        @Override
        public String emptyHistoryPlaceholder(String finishReason) {
            return TLAiAgent.this.emptyHistoryPlaceholder(finishReason);
        }

        @Override
        public void reportTokenUsage(String sessionId, String rootSid, String userId,
                                     long[] usage, long[] cache) {
            reportProcessTokenUsage(usage, cache, sessionId, rootSid, userId);
        }

        // ===== StreamHandleRegistry =====
        @Override
        public void set(String sessionId, StreamHandle handle) {
            streamHandles.put(sessionId, handle);
        }

        @Override
        public void clear(String sessionId, StreamHandle expect) {
            streamHandles.remove(sessionId, expect);
        }

        // ===== TurnCommitter =====
        @Override
        public void checkpoint(TurnRecord record) {
            sendCheckpoint(record, null);
        }

        @Override
        public void checkpointPending(TurnRecord record, PendingInfo pending) {
            sendCheckpoint(record, pending);
        }

        @Override
        public CommitResult commit(TurnRecord record, TurnOutcome outcome) {
            return commitTurn(ctx, record, outcome);
        }

        private void sendCheckpoint(TurnRecord record, PendingInfo pending) {
            TLMsg m = createMsg().setAction("sessionUpdated")
                    .setParam("sessionId", record.id.sessionId())
                    .setParam("userId", record.id.userId())
                    .setParam("agentName", name)
                    .setParam("roundId", record.id.roundId())
                    .setParam("messages", record.roundMessages())
                    .setParam("model", record.model)
                    .setParam("temperature", record.temperature)
                    .setParam("maxTokens", record.maxTokens)
                    .setParam("userMessage", record.userMessage);
            if (pending != null) {
                m.setParam("pendingToolCall", pending.pendingToolCall());
                m.setParam(AI_P_APPROVAL_ID, pending.approvalId());
            }
            notifySessionManager(m);
        }
    }

    /** 终态收敛：Aborted/Failed/Pending/其他四类策略见 spec §5.6/§6.5。 */
    private CommitResult commitTurn(ChatRunContext ctx, TurnRecord r, TurnOutcome outcome) {
        String sid = r.id.sessionId();
        String rid = r.id.roundId();

        if (outcome instanceof TurnOutcome.Aborted a) {
            // 先清除中断标志，防止 log4j 在中断线程上写日志报错
            Thread.interrupted();
            List<TLConversationHistory> abortedMsgs = r.roundMessages();
            sanitizeToolPairs(abortedMsgs);
            abortedMsgs.add(new TLConversationHistory(
                    TLConversationHistory.Role.assistant, TurnOutcome.ABORTED_TEXT));
            if (!r.noHistory) {
                int end = Math.min(r.msgStartIdx, r.history.size());
                List<TLConversationHistory> ctxMsgs = new ArrayList<>(r.history.subList(0, end));
                List<TLConversationHistory> ctxRound =
                        new ArrayList<>(r.history.subList(end, r.history.size()));
                sanitizeToolPairs(ctxRound);
                ctxRound.add(new TLConversationHistory(
                        TLConversationHistory.Role.assistant, TurnOutcome.ABORTED_TEXT));
                ctxMsgs.addAll(ctxRound);
                saveContextHistory(sid, ctxMsgs);
            }
            notifySessionManager(createMsg().setAction("chatAborted")
                    .setParam("sessionId", sid)
                    .setParam("userId", r.id.userId())
                    .setParam("agentName", name)
                    .setParam("roundId", rid)
                    .setParam("messages", abortedMsgs)
                    .setParam("userMessage", r.userMessage));
            if (a.byUser())
                putLog("Chat aborted by user: sessionId=" + sid, LogLevel.INFO);
            else
                putLog("Chat aborted (" + a.reason() + "): sessionId=" + sid, LogLevel.WARN);
            traceStage(sid, r.id.rootSessionId(), rid, "roundEnd",
                    "aborted: " + a.reason(), 0);
            long[] tk = tokensToLong(a.tokens());
            long[] acc = accumulateTokenUsage(sid, tk);
            TLMsg ret = createMsg().setParam(RESULT, true).setParam(AI_P_CANCELLED, true)
                    .setParam(AI_P_RESPONSE, TurnOutcome.ABORTED_TEXT)
                    .setParam(AI_P_SESSIONID, sid)
                    .setParam("abortReason", a.reason())
                    .setParam("iterations", a.iterations())
                    .setParam(AI_P_TOTALTOKENS, (int) tk[2])
                    .setParam(AI_P_TOTALTOKENS_TOTAL, (int) acc[2]);
            return new CommitResult(ret, TurnOutcome.ABORTED_TEXT);
        }

        if (outcome instanceof TurnOutcome.Failed f) {
            putLog("LLM error kept as checkpoint: sessionId=" + sid + " err=" + f.error(),
                    LogLevel.WARN);
            traceStage(sid, r.id.rootSessionId(), rid, "roundEnd", "error", 0);
            TLMsg ret = createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, f.error());
            return new CommitResult(ret, f.error());
        }

        if (outcome instanceof TurnOutcome.EarlyReturn e) {
            TLMsg ret = createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, e.response());
            return new CommitResult(ret, e.response());
        }

        // ==== 非中断/失败：guard → context → chatFinished → 记忆 → trace → 返回 ====
        String raw = outcome.response();
        String guarded = guardOutput(raw);
        if (guarded == null) guarded = raw;
        if (guarded != null && !guarded.equals(raw)
                && r.finalAssistantIdx >= 0 && r.finalAssistantIdx < r.history.size()
                && r.history.get(r.finalAssistantIdx).getRole() == TLConversationHistory.Role.assistant) {
            r.history.get(r.finalAssistantIdx).setContent(guarded);
        }
        if (outcome instanceof TurnOutcome.Truncated) {
            appendTruncatedFinalToHistory(r.history, guarded);
        }

        if (!r.noHistory) saveContextHistory(sid, r.history);

        boolean pending = outcome instanceof TurnOutcome.PendingApproval;
        if (!pending) {
            notifySessionManager(createMsg().setAction("chatFinished")
                    .setParam("sessionId", sid)
                    .setParam("userId", r.id.userId())
                    .setParam("agentName", name)
                    .setParam("roundId", rid)
                    .setParam("messages", r.roundMessages())
                    .setParam("userMessage", r.userMessage)
                    .setParam("response", guarded));
        }

        String roundOutcome = "completed";
        if (outcome instanceof TurnOutcome.Rejected) roundOutcome = "rejected";
        else if (pending) roundOutcome = "pendingApproval";
        else if (outcome instanceof TurnOutcome.Clarified) roundOutcome = "clarified";
        else if (outcome instanceof TurnOutcome.Truncated) roundOutcome = "truncated";
        traceStage(sid, r.id.rootSessionId(), rid, "roundEnd", roundOutcome, 0,
                raw != null ? raw : "", null);

        if (!pending) {
            try {
                TLMsg saveMsg = createMsg().setAction(AGENT_SAVEMEMORY)
                        .setParam(AI_P_SESSIONID, sid)
                        .setParam("storeName", defaultMemoryStore)
                        .setParam("userId", r.id.userId())
                        .setParam("agentName", name)
                        .setParam(AI_P_MEMORYKEY, "chat_" + System.currentTimeMillis())
                        .setParam(AI_P_MEMORYVALUE, r.userMessage + " → " + guarded)
                        .setParam(AI_P_MEMORYTAG, "chat_history")
                        .setParam("maxSeq", getMaxSeq(sid));
                saveAgentMemory(ctx.fromWho, saveMsg);
            } catch (Exception e) {
                putLog("Save memory failed: " + e.toString(), LogLevel.ERROR);
            }
        }

        long[] tk = tokensToLong(outcome.tokens());
        long[] acc = accumulateTokenUsage(sid, tk);
        TLMsg ret = createMsg().setParam(RESULT, true)
                .setParam(AI_P_RESPONSE, guarded)
                .setParam(AI_P_SESSIONID, sid)
                .setParam("iterations", r.iterations)
                .setParam(AI_P_NEEDSCLARIFICATION, outcome instanceof TurnOutcome.Clarified)
                .setParam("rejected", outcome instanceof TurnOutcome.Rejected)
                .setParam("rejectReason", outcome instanceof TurnOutcome.Rejected rej
                        && rej.reason() != null ? rej.reason() : "")
                .setParam(AI_P_PROMPTTOKENS, (int) tk[0])
                .setParam(AI_P_COMPLETIONTOKENS, (int) tk[1])
                .setParam(AI_P_TOTALTOKENS, (int) tk[2])
                .setParam(AI_P_PROMPTTOKENS_TOTAL, (int) acc[0])
                .setParam(AI_P_COMPLETIONTOKENS_TOTAL, (int) acc[1])
                .setParam(AI_P_TOTALTOKENS_TOTAL, (int) acc[2])
                .setParam(AI_P_CACHECREATIONTOKENS, (int) tk[3])
                .setParam(AI_P_CACHEHITTOKENS, (int) tk[4])
                .setParam(AI_P_CACHEMISSTOKENS, (int) tk[5])
                .setParam(AI_P_ROUNDID, rid);
        if (llmProvider != null) {
            long[] sessionCache = llmProvider.getSessionCacheStats(sid);
            ret.setParam(AI_P_CACHECREATIONTOKENS_TOTAL, (int) sessionCache[0])
                    .setParam(AI_P_CACHEHITTOKENS_TOTAL, (int) sessionCache[1])
                    .setParam(AI_P_CACHEMISSTOKENS_TOTAL, (int) sessionCache[2]);
        }
        if (outcome instanceof TurnOutcome.Truncated) ret.setParam(AI_P_TRUNCATED, true);
        if (outcome instanceof TurnOutcome.DirectAnswer) ret.setParam(AI_P_FINALANSWER, true);
        if (r.reasoning != null && !r.reasoning.isEmpty())
            ret.setParam(AI_P_REASONING, r.reasoning);

        maybeGenerateSessionTitle(sid, r.id.userId(), r.userMessage,
                !r.noHistory && r.plan == TurnPlan.NORMAL && r.msgStartIdx <= 1);
        return new CommitResult(ret, guarded);
    }

    private static long[] tokensToLong(TurnOutcome.Tokens t) {
        if (t == null) return new long[]{0, 0, 0, 0, 0, 0};
        return new long[]{t.prompt(), t.completion(), t.total(),
                t.cacheCreate(), t.cacheHit(), t.cacheMiss()};
    }


    /** 成功/待批/拒绝/直出/中断的统一终帧：文本增量 + done。幂等。 */
    private void streamFinal(ChatRunContext ctx, String answer) {
        if (ctx.sink == null || ctx.streamFinished) return;
        ctx.streamFinished = true;   // 先置位：onFinalText 抛异常也不会被重入/双发
        try {
            ctx.sink.onFinalText(answer, ctx.finalAnswerStreamed);
        } finally {
            // 终帧必须发：onFinalText 抛异常时前端 busy 指示也要能结束
            ctx.sink.onDone();
        }
    }

    /** 错误终帧：只发 error，不发 done（与当前流式语义一致）。幂等。 */
    private void streamError(ChatRunContext ctx, String message) {
        if (ctx.sink == null || ctx.streamFinished) return;
        ctx.streamFinished = true;
        ctx.sink.onError(message);
    }

    /**
     * LLM 错误收尾：人为停止不留断点；非人为错误（流式/非流式同语义）保持 checkpoint，
     * 交给 findIncomplete 恢复。此前非流式发 chatFinished 把轮次收成 completed，
     * 控制台默认路径反而丢断点，与流式自相矛盾。
     */
    private void handleRunError(ChatRunContext ctx, String errMsg) {
        if (ctx.cancelled()) {
            // 防御性短路：正常路径在 LLM 错误分支之前已检查 cancelled
            if (ctx.history != null) {
                // 与中断分支同一收尾：净化悬空 tool 对 + 终态标记 + context/会话同口径
                persistAbortedRound(ctx, ctx.history, ctx.msgStartIdx, ctx.noHistory);
            }
            streamFinal(ctx, ABORTED_FINAL);
            return;
        }
        putLog("LLM error kept as checkpoint: sessionId=" + ctx.sessionId
                + " err=" + errMsg, LogLevel.WARN);
        traceStage(ctx.sessionId, ctx.rootSid, ctx.roundId, "roundEnd", "error", 0);
        streamError(ctx, errMsg);
    }

    /**
     * 中断轮收尾：净化悬空 tool 对 + 补「⏹ 已中断」终态，然后 context 与 SessionManager 同口径落盘。
     * <p>此前只写会话不写 context：进程内下一轮看不到被中断的这一轮，重登 continue 又会把它
     * 载入 context——同一个"下一轮"两套历史。中断轮不再"完全丢弃"，而是以合法终态结束；
     * 长期记忆仍不保存（"已中断"不是可记忆的回复）。</p>
     * <p>noHistory 只跳过 context；会话通知由 enableCheckpoint 控制（notifySessionManager 内判）。</p>
     */
    private void persistAbortedRound(ChatRunContext ctx, List<TLConversationHistory> history,
                                     int msgStartIdx, boolean noHistory) {
        // 先清除中断标志，防止后续 putLog → log4j RollingFileManager 因线程中断而报错
        Thread.interrupted();
        // 会话落盘：同 roundId last-wins，messages 必须是"本轮完整块"（resume 前缀 + 增量）
        List<TLConversationHistory> abortedMsgs = roundMessages(ctx);
        sanitizeToolPairs(abortedMsgs);
        abortedMsgs.add(new TLConversationHistory(TLConversationHistory.Role.assistant, ABORTED_FINAL));
        if (!noHistory && history != null) {
            // context 用"本轮起点之前的全量 + 净化后的本轮增量"重建：resumeRoundMessages
            // 已包含在 ctx.history 的前缀里，直接拿 roundMessages 会重复原轮块。
            int end = Math.min(msgStartIdx, history.size());
            List<TLConversationHistory> ctxMsgs = new ArrayList<>(history.subList(0, end));
            List<TLConversationHistory> ctxRound = deltaMessages(history, msgStartIdx);
            sanitizeToolPairs(ctxRound);
            ctxRound.add(new TLConversationHistory(TLConversationHistory.Role.assistant, ABORTED_FINAL));
            ctxMsgs.addAll(ctxRound);
            saveContextHistory(ctx.sessionId, ctxMsgs);
        }
        // 通知 SessionManager 会话中断，存为 completed 避免残留 checkpoint
        notifySessionManager(createMsg()
                .setAction("chatAborted")
                .setParam("sessionId", ctx.sessionId)
                .setParam("userId", ctx.userId)
                .setParam("agentName", name)
                .setParam("roundId", ctx.roundId)
                .setParam("messages", abortedMsgs)
                .setParam("userMessage", ctx.userMessage));
    }

    /**
     * 异步流式入口：创建转发 sink → 把统一的 runChat 丢到工作线程 → 立即返回 null。
     * 编排、审批、断点、保存全部在 runChat 里；本方法不再做预处理。
     */
    protected TLMsg chatStream(Object fromWho, TLMsg msg) {
        String sessionId = resolveSessionId(msg, "default");
        // 与 doChat 同语义：复位取消标志，避免上次 /stop 的残留 true 影响本轮
        cancelFlags.computeIfAbsent(sessionId,
                k -> new java.util.concurrent.atomic.AtomicBoolean()).set(false);

        // 兜底目标 "caller"：同名模块不存在时由 ForwardStreamSink.forward 的
        // IGNOREMODULEISNULL 静默丢弃事件（调用方配置错误，不应关停应用）
        String resultFor = msg.getStringParam(RESULTFOR,
                fromWho instanceof String ? (String) fromWho : "caller");
        String resultAction = msg.getStringParam(RESULTACTION, "onStreamChunk");
        String fwdTarget = msg.getStringParam("_streamResultFor", resultFor);
        String fwdAction = msg.getStringParam("_streamResultAction", resultAction);
        Object fwdInst = msg.getSystemParam(AI_P_STREAMFORWARDINSTANCE, null);
        IObject forwardInstance = fwdInst instanceof IObject ? (IObject) fwdInst : null;

        // 防自转发死循环：目标就是本 agent 且没有显式实例时，丢弃事件（调用方配置错误）
        if (forwardInstance == null && getName().equals(fwdTarget)) {
            putLog("chatStream: forward target is the agent itself, events dropped", LogLevel.WARN);
            return null;
        }

        // sink 不依赖轮次状态，先构造：这样 begin 之后的任何异常都能用它通知 UI。
        // userId 随每一帧下发：webui 按 userId+sessionId 定位 SSE 通道，不依赖 sessionOwner 旁路状态。
        String userId = String.valueOf(msg.getSystemParam("userId", sessionId));
        StreamSink sink = new ForwardStreamSink(forwardInstance, fwdTarget, fwdAction, sessionId, userId);

        // P0-1：同步登记轮次，关闭 "chatStream 已返回、worker 尚未 begin" 的窗口。
        // 否则 injectTaskResult 会看到 count==0 立即写入 context，而 worker 随后
        // 读快照 + save 会把注入覆盖掉（后台任务结果永久丢失）。
        //
        // 边界：putMsgNoWait 内部 Thread.start() 成功、但返回前 createMsg() 抛 OOM 级异常时，
        // worker 已启动 + 入口 catch 会双重 end；后果仅是 count 短暂为负，
        // sessionRoundEnd 的 remove/flush 都幂等，可忽略。
        sessionRoundBegin(sessionId);
        try {
            TLMsg task = createMsg().setAction("runChatEngineAsync")
                    // 注意：_chatRequest 是调用时的快照引用；调用方在本方法返回后不得再改 msg
                    .setParam("_chatRequest", msg)
                    .setParam("_chatFromWho", fromWho)   // 保留原调用方：saveAgentMemory/委托链语义一致
                    .setParam("_streamSink", sink)
                    .setParam("_preBegan", true);
            TLMsg submitted = putMsgNoWait(this, task);
            if (submitted == null || submitted.getParam(THREADPOOL_TASK) == null) {
                // P0-6：线程池拒绝/未配置/提交失败——worker 根本没启动，必须自己收尾并通知 UI
                putLog("chatStream: failed to submit worker task", LogLevel.ERROR);
                sessionRoundEnd(sessionId);
                sink.onError("Agent error: worker submit failed");
            }
        } catch (Throwable t) {
            putLog("chatStream: worker submit exception: " + t, LogLevel.ERROR);
            sessionRoundEnd(sessionId);
            sink.onError("Agent error: worker submit failed: " + t.getMessage());
        }
        return null;
    }

    /**
     * 工作线程上的统一编排入口。所有终帧由 runChat 内部的 streamFinal/streamError 负责；
     * 这里只兜底 runChat 之外的意外 Throwable。
     */
    protected TLMsg runChatEngineAsync(Object fromWho, TLMsg msg) {
        TLMsg chatMsg = (TLMsg) msg.getParam("_chatRequest");
        StreamSink sink = (StreamSink) msg.getParam("_streamSink");
        Object chatFromWho = msg.getParam("_chatFromWho");
        if (chatFromWho == null) chatFromWho = fromWho;
        boolean preBegan = msg.parseBoolean("_preBegan", false);
        try {
            return runChat(chatFromWho, chatMsg, sink, preBegan);
        } catch (Throwable t) {
            // 兜底路径本身不能再抛：否则异常逃出 worker，回到框架的 exception()/shutdown 链
            try { putLog("Async chat engine failed: " + t, LogLevel.ERROR); }
            catch (Throwable ignored) { System.err.println("[runChatEngineAsync] " + t); }
            if (sink != null) {
                try { sink.onError("Agent error: " + t.getMessage()); }
                catch (Throwable ignored) { /* sink 自己的 forward 已有兜底 */ }
            }
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, "Agent error: " + t.getMessage());
        }
    }

    /**
     * 执行器回调：按配置决定是否把工具进度发布到 msgBus。
     * 默认丢弃（子 agent 不配 toolProgressToBus 就不外发）。
     */
    protected TLMsg handleToolProgress(Object fromWho, TLMsg msg) {
        if (!toolProgressToBus) return null;
        try {
            Object bus = getModuleInFactory("msgBus");
            if (!(bus instanceof IObject)) return null;
            TLMsg evt = createMsg()
                    .setAction(TOOL_PROGRESS_TOPIC)
                    .setParam("agentName", getName())
                    .setParam("agentFamilyName", getFamilyName())
                    .setParam("executionId", msg.getStringParam("executionId", ""))
                    .setParam("roundId", msg.getStringParam("roundId", ""))
                    .setParam("sessionId", String.valueOf(msg.getSystemParam(AI_P_SESSIONID, "")))
                    .setParam("userId", String.valueOf(msg.getSystemParam("userId", "")))
                    .setParam("rootSessionId", String.valueOf(msg.getSystemParam("rootSessionId", "")))
                    .setParam("toolCallId", msg.getStringParam("toolCallId", ""))
                    .setParam("toolName", msg.getStringParam("toolName", ""))
                    .setParam("moduleName", msg.getStringParam("moduleName", ""))
                    .setParam("index", msg.getIntParam("index", 0))
                    .setParam("total", msg.getIntParam("total", 0))
                    .setParam("status", msg.getStringParam("status", ""))
                    .setParam("durationMs", msg.getLongParam("durationMs", 0L))
                    .setParam("message", msg.getStringParam("message", ""));
            evt.setDestination(TOOL_PROGRESS_TOPIC);
            putMsg((IObject) bus, evt);
        } catch (Throwable t) {
            try { putLog("toolProgress publish failed: " + t, LogLevel.DEBUG); } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * Provider 流式回调入口（跑在 OkHttp 回调线程）：
     * 只做三件事——chunk 转发给 sink、done 唤醒等待线程、error 唤醒并带错误。
     * 不再执行任何工具循环/审批/保存逻辑。
     */
    protected TLMsg onStreamResult(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "default");
        StreamHandle handle = streamHandles.get(sessionId);
        if (handle == null) return null;   // 已收尾/迟到事件：丢弃
        // 事件带 roundId 时按 roundId 过滤：/stop 后旧请求若仍在途，迟到的 chunk/done
        // 不能命中同会话新轮次的 handle（handle 在 cleanup 时已按旧 roundId 移除）。
        String eventRound = msg.getStringParam(AI_P_ROUNDID, null);
        if (eventRound != null && !eventRound.isEmpty()
                && !eventRound.equals(handle.roundId)) {
            putLog("Dropped stale stream event: session=" + sessionId
                    + " eventRound=" + eventRound + " handleRound=" + handle.roundId,
                    LogLevel.DEBUG);
            return null;
        }
        if (msg.parseBoolean(AI_P_STREAMDONE, false)) {
            handle.complete(msg);
        } else if (msg.containsParam(AI_P_STREAMERROR)) {
            handle.fail(msg.getStringParam(AI_P_STREAMERROR, "流式错误"));
        } else if (msg.containsParam(AI_P_CHUNK) && !handle.isCompleted()) {
            // done 之后、streamHandles.remove 之前的迟到 chunk 不能推给 UI
            handle.sink.onChunk(msg.getStringParam(AI_P_CHUNK, ""));
        }
        // reasoning chunk 保持现状：不推 UI；推理内容随 done 消息进入 history/trace
        return null;
    }

    /**
     * 向注册表（控制中心/旧 moduleRegistry）注册子模块，以家族名字为 key。
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
        String controlCenter = params == null ? null : params.get(CONTROLCENTER_NAME);
        if (controlCenter != null && !controlCenter.trim().isEmpty())
            putMsg(controlCenter.trim(), msg);
    }

    /**
     * 通用实例投递：消息 systemArgs 里带目标实例引用时，直接投给该实例
     * （用于 agent 私有子模块的外部事件回调——按名寻址够不着，实例由发起方自备）。
     * 消息原样透传（含动作名与参数），子模块自行分发。
     */
    private TLMsg dispatchToInstance(TLMsg msg) {
        Object target = msg.getSystemParam(AI_P_TARGETINSTANCE, null);
        if (!(target instanceof IObject)) return null;
        TLMsg m = createMsg().setAction(msg.getAction());
        m.addArgs(msg.getArgs());
        m.addSystemArgs(msg.getSystemArgs());
        m.removeSystemParam(AI_P_TARGETINSTANCE);   // 防止再转投
        return putMsg((IObject) target, m);
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
     * 断点恢复时向审批模块查本次审批的终态（approved/rejected/pending/lost）。
     * 返回审批响应 TLMsg；无审批模块（未启用审批）或查询失败返回 null（调用方按 pending 处理）。
     */
    private TLMsg queryApproval(String approvalId) {
        if (approvalId == null || approvalId.isEmpty()) return null;
        try {
            TLMsg q = createMsg().setAction(APPROVAL_QUERY).setParam(AI_P_APPROVAL_ID, approvalId);
            q.setSystemParam(IGNOREMODULEISNULL, true);   // 缺模块静默（默认行为会关停整个应用）
            TLMsg r = putMsg(approvalModuleName, q);
            // 缺模块时 putMsg 返回的是带 IGNOREMODULEISNULL 的空壳（非 null）——按"无 decision 即无 gate"兜住
            if (r == null || r.getStringParam("decision", "").isEmpty()) return null;   // 无 gate / 非审批响应
            return r;
        } catch (Exception e) {
            putLog("query approval decision failed: " + e, LogLevel.WARN);
            return null;
        }
    }

    /**
     * 恢复历史构造：context 默认 system 置顶 + 历史。
     * DB 增量保存不含 index 0 的 context system，恢复必须显式补（每次从 DB 历史重新构造
     * 且 REPLACE 全量替换，不会累积重复）。
     * 始终返回调用方独占的新列表：resume/llmInput 之后 history 会被就地增删（工具结果、
     * 模板替换），直接返回调用方列表会让调用方持有的数据被污染。
     * 历史首条已是 system 时替换而非再插一条（llmInput 可能自带 system，否则双 system 发给模型）。
     */
    private List<TLConversationHistory> withContextSystem(List<TLConversationHistory> loadedHistory) {
        if (loadedHistory == null) return null;
        HashMap<String, String> ctxParams = modulesParams.get(contextModuleName);
        String sysTpl = ctxParams != null ? ctxParams.get("defaultSystemMessage") : null;
        boolean hasSys = sysTpl != null && !sysTpl.trim().isEmpty();
        List<TLConversationHistory> h = new ArrayList<>(loadedHistory.size() + 1);
        if (hasSys) {
            h.add(new TLConversationHistory(TLConversationHistory.Role.system, sysTpl));
            if (!loadedHistory.isEmpty()
                    && loadedHistory.get(0).getRole() == TLConversationHistory.Role.system) {
                h.addAll(loadedHistory.subList(1, loadedHistory.size()));
                return h;
            }
        }
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
        // 任务执行会话（task_*，定时/后台任务的机器触发轮次）不写长期记忆：
        // 其"提问 → 回答"碎片对用户记忆无价值，且会被下一轮召回，形成"坏输出回流"闭环
        //（2026-10-06 事故：一次复读记忆的输出被存为碎片，后续每轮召回再喂回，越滚越坏）
        String sid = msg.getStringParam(AI_P_SESSIONID, "");
        if (sid != null && sid.startsWith("task_")) {
            return createMsg().setParam(RESULT, true).setParam("skipped", true);
        }
        // 防"摘要器思考文本"回流：这类脏内容入库后会被反复召回、模型再复读，形成闭环（2026-10-06 复读事故）
        Object mvObj = msg.getParam(AI_P_MEMORYVALUE);
        String mv = mvObj == null ? "" : String.valueOf(mvObj);
        if (mv.contains("压缩为一段简洁的中文摘要") || mv.contains("我们需要回答用户")
                || mv.contains("对话历史碎片")) {
            putLog("Skip polluted memory fragment", LogLevel.WARN);
            return createMsg().setParam(RESULT, true).setParam("skipped", true);
        }
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
        // getModule 有创建副作用：取一次即可，instanceof 与强转用同一个实例
        Object providerModule = getModule(providerName);
        TLBaseModule module = providerModule instanceof TLLlmProvider
                ? (TLBaseModule) providerModule : null;
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
    /** 发送会话通知给 SessionManager。由 Agent 的 enableCheckpoint 决定是否通知。返回应答（未发送时为 null） */
    private TLMsg notifySessionManager(TLMsg notificationMsg) {
        if (!enableCheckpoint) return null;
        // 可选基础设施：模块名写错/模块创建失败时静默降级，不能按框架默认 shutdown(-1)
        // 把整个应用退掉（一次 sessionUpdated 就足以杀死进程）。
        notificationMsg.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg r = putMsg(sessionManagerName, notificationMsg);
        // 缺模块分支返回 IGNOREMODULEISNULL=false；正常应答不含该键。
        boolean missing = r != null && r.containsParam(IGNOREMODULEISNULL)
                && !r.parseBoolean(IGNOREMODULEISNULL, true);
        if (missing && !sessionManagerWarned) {
            sessionManagerWarned = true;
            putLog("SessionManager [" + sessionManagerName + "] 不可用：会话不会落盘/恢复，"
                    + "请检查 enableCheckpoint 与 sessionManagerName 配置", LogLevel.WARN);
        } else if (!missing) {
            sessionManagerWarned = false;
        }
        return r;
    }

    // ======================== 会话标题 ========================

    /** 会话标题：首轮完成后异步请 LLM 起名（失败静默、不阻塞收尾）；结果落库 + 事件推前端 */
    private void maybeGenerateSessionTitle(String sessionId, String userId,
                                           String userMessage, boolean firstRound) {
        if (!autoSessionTitle || !firstRound) return;
        // 与落库通道同门禁：子 agent（executeAsTool 共享父 sid，会用子任务文案给父会话起名）/
        // 工作流节点/group 成员默认 enableCheckpoint=false，天然豁免；noHistory 节点也不会每轮空转
        if (!enableCheckpoint) return;
        if (sessionId == null || sessionId.startsWith("task_")) return;   // 任务执行会话不参与
        if (userMessage == null || userMessage.trim().isEmpty()) return;
        final String um = userMessage;
        final String uid = userId == null ? "default" : userId;
        Thread t = new Thread(() -> {
            try {
                String title = generateSessionTitle(um);
                if (title == null || title.isEmpty()) return;
                // 落库：仅当为空时写（不覆盖用户手动改名）；事件只在真的写库后发，
                // 否则前端显示与下次列表拉取会不一致（评审 I3）
                TLMsg stored = notifySessionManager(createMsg().setAction("setSessionTitle")
                        .setParam("sessionId", sessionId)
                        .setParam("userId", uid)
                        .setParam("title", title)
                        .setParam("onlyIfEmpty", true));
                if (stored == null || !stored.parseBoolean("updated", false)) {
                    putLog("Session title not stored (updated=false), skip event: " + sessionId, LogLevel.DEBUG);
                    return;
                }
                // 事件：webui 即时刷新侧栏
                try {
                    Object bus = getModuleInFactory("msgBus");
                    if (bus instanceof IObject) {
                        TLMsg evt = createMsg().setAction("sessionTitle")
                                .setParam("userId", uid)
                                .setParam("sessionId", sessionId)
                                .setParam("title", title);
                        evt.setDestination("sessionTitle");
                        putMsg((IObject) bus, evt);
                    }
                } catch (Exception ignored) { }
                putLog("Session title generated: " + sessionId + " -> " + title, LogLevel.DEBUG);
            } catch (Exception e) {
                putLog("会话起名失败（忽略）: " + e, LogLevel.DEBUG);
            }
        }, "session-title-" + sessionId);
        t.setDaemon(true);
        t.start();
    }

    /** 直接调 provider 生成标题（同摘要器模式：非流式 completion，不经过会话） */
    private String generateSessionTitle(String firstUserMessage) {
        try {
            if (llmProvider == null) return null;
            List<TLConversationHistory> input = new ArrayList<>();
            input.add(new TLConversationHistory(TLConversationHistory.Role.system,
                    "你是会话标题生成器。根据用户的第一条消息生成 6~12 个字的中文会话标题，概括主题；"
                            + "只输出标题本身，不要引号、标点、序号或任何解释。"));
            String um = firstUserMessage.length() > 300 ? firstUserMessage.substring(0, 300) : firstUserMessage;
            input.add(new TLConversationHistory(TLConversationHistory.Role.user, um));
            TLMsg llmMsg = createMsg().setAction(LLM_COMPLETION)
                    .setParam(AI_P_MESSAGEHISTORY, input)
                    // 推理模型（deepseek-v4-flash）的 reasoning 会吃光小 max_tokens，导致 content 为空、
                    // 被"reasoning 提升为正文"兜底成残片标题（真机 trace：21/25 finish_reason=length）。
                    // 与 evals 内部调用同款处理：关推理 + 放宽 maxTokens（评审 I1）
                    .setParam(AI_P_REASONING_MODE, AI_P_REASONING_MODE_DISABLED)
                    .setParam(AI_P_MAXTOKENS, 128);
            TLMsg r = putMsg(llmProvider, llmMsg);
            if (r == null) return null;
            String title = r.getStringParam(AI_P_RESPONSE, null);
            if (title == null) return null;
            title = title.trim().replaceAll("[\\r\\n]+", " ");
            title = title.replaceAll("^[\"“”'‘’《》【】#\\s]+", "").replaceAll("[\"“”'‘’《》【】#\\s]+$", "");
            if (title.length() > 20) title = title.substring(0, 20);
            // 防模型输出英文思考文本（如 "The user wants a title..." 曾被截成 20 字存进标题）：
            // 标题至少含 2 个汉字，否则按失败处理——上层不写库、前端回退显示首句
            int cjk = 0;
            for (int i = 0; i < title.length(); i++) {
                char c = title.charAt(i);
                if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
            }
            if (cjk < 2) return null;
            return title;
        } catch (Exception e) {
            putLog("会话起名 LLM 调用失败（忽略）: " + e, LogLevel.DEBUG);
            return null;
        }
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

    /**
     * 本轮要落盘的完整消息 = 恢复轮原消息前缀 + 本轮新增量。
     * 同 roundId 的写入是 last-wins（文件版追加覆盖、DB 版删后插）：resume 只写新增量
     * 会把原轮 messages（含 assistant(tool_calls) 等）永久覆盖丢——断点恢复后历史残缺。
     * 返回新列表，调用方可安全增删。
     */
    private List<TLConversationHistory> roundMessages(ChatRunContext ctx) {
        List<TLConversationHistory> delta = ctx.history == null
                ? new ArrayList<>() : deltaMessages(ctx.history, ctx.msgStartIdx);
        if (ctx.resumeRoundMessages == null || ctx.resumeRoundMessages.isEmpty()) return delta;
        List<TLConversationHistory> out =
                new ArrayList<>(ctx.resumeRoundMessages.size() + delta.size());
        out.addAll(ctx.resumeRoundMessages);
        out.addAll(delta);
        return out;
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
     * 构建发送给 LLM 的视图列表：
     * system 区（默认 system + 记忆 system）恒保留；其余取尾部 contextViewSize 条原样。
     * 历史未超视图 → 直接全量（兼容现状）。
     * 孤儿 tool 修正：视图开头是 tool 消息时向前补其 assistant(tool_calls)，保证 OpenAI 消息顺序约束。
     */
    protected List<TLConversationHistory> buildSendList(List<TLConversationHistory> full,
                                                        String memoryContext) {
        if (full.size() <= contextViewSize) {
            List<TLConversationHistory> all = copyList(full);
            if (memoryContext != null && !memoryContext.isEmpty()) {
                insertMemorySystem(all, memoryContext);
            }
            attachments.applyManifest(all);
            attachments.applyImageRetention(all);
            sanitizeToolPairs(all);
            attachments.stripImagePayloads(all);
            trimToContextBudget(all);
            return all;
        }
        List<TLConversationHistory> systems = new ArrayList<>();
        List<TLConversationHistory> rest = new ArrayList<>();
        List<Integer> restOrigIdx = new ArrayList<>();     // rest[i] 对应 full 里的下标
        for (int i = 0; i < full.size(); i++) {
            TLConversationHistory h = full.get(i);
            if (h.getRole() == TLConversationHistory.Role.system) {
                systems.add(h.copy());
            } else {
                rest.add(h.copy());
                restOrigIdx.add(i);
            }
        }
        if (memoryContext != null && !memoryContext.isEmpty()) {
            systems.add(new TLConversationHistory(TLConversationHistory.Role.system, memoryContext));
        }
        int startIdx = Math.max(0, rest.size() - contextViewSize);
        // 孤儿 tool 修正（单次补簇）：尾部截取把窗口边界切在 tool 簇中间时，窗口头是 tool、
        // 其 assistant(tool_calls) 在窗外——向 full 借回 [assistant..簇起点) 段，插到该 tool 前。
        // 簇内其余 tool 随前置段一并覆盖，只需补一次；助手更早被裁丢时下方 sanitizeToolPairs 兜底删除。
        // ⚠ 勿改回循环补：addAll(0,prefix)+startIdx 同幅前进会使 startIdx 永远停在同一个 tool 上，
        // 同一前缀无限插入 → 死循环（线程 100% CPU，round 卡在 roundStart 后无任何输出）。
        if (startIdx < rest.size()
                && rest.get(startIdx).getRole() == TLConversationHistory.Role.tool) {
            int idxInFull = restOrigIdx.get(startIdx);     // ← 记下的原始下标（拷贝后 indexOf 按身份找不到了）
            if (idxInFull > 0) {
                for (int i = idxInFull - 1; i >= 0; i--) {
                    TLConversationHistory h = full.get(i);
                    if (h.isAssistantWithToolCalls()) {
                        List<TLConversationHistory> prefix = copyList(full.subList(i, idxInFull));
                        rest.addAll(startIdx, prefix);
                        // 与 rest 同位置一次性插入整段下标；逐项 add(startIdx+k) 会
                        // 把已有元素不断后移，导致 restOrigIdx 与 rest 错位。
                        List<Integer> prefixIdx = new ArrayList<>(prefix.size());
                        for (int k = 0; k < prefix.size(); k++) prefixIdx.add(i + k);
                        restOrigIdx.addAll(startIdx, prefixIdx);
                        // ⚠ 不推进 startIdx：补回的前缀必须留在窗口内——推进会把刚借回的 assistant
                        //   又切出视图，孤儿 tool 被下方 sanitizeToolPairs 删掉，等于白补。
                        //   单次 if（非循环）不存在补簇死循环，安全。
                        break;
                    }
                }
            }
        }
        List<TLConversationHistory> result = new ArrayList<>(systems);
        result.addAll(rest.subList(Math.max(0, startIdx), rest.size()));
        attachments.applyManifest(result);
        attachments.applyImageRetention(result);
        sanitizeToolPairs(result);
        attachments.stripImagePayloads(result);
        trimToContextBudget(result);
        return result;
    }

    /**
     * 记忆 system 的注入位置：若最后一条是 user（非流式 doChat 先入列了用户消息），插到它之前——
     * 记忆 dump 落在提问之后会被小模型当作续写目标：记忆碎片是"提示 → 回答"格式，
     * 模型直接复读其中旧答案（2026-10-06 定时任务把记忆摘要当提醒内容输出的事故根因）。
     * 否则追加到末尾：流式路径 buildSendList 先于本轮用户消息入列，末尾即"用户消息之前"，语义一致。
     */
    private void insertMemorySystem(List<TLConversationHistory> list, String memoryContext) {
        TLConversationHistory mem =
                new TLConversationHistory(TLConversationHistory.Role.system, memoryContext);
        int idx = list.size();
        if (!list.isEmpty() && list.get(list.size() - 1).getRole() == TLConversationHistory.Role.user) {
            idx = list.size() - 1;
        }
        list.add(idx, mem);
    }

    private static List<TLConversationHistory> copyList(List<TLConversationHistory> src) {
        List<TLConversationHistory> out = new ArrayList<>(src.size());
        for (TLConversationHistory h : src) out.add(h.copy());
        return out;
    }

    /** 兼容旧调用：userId 缺省按 sessionId（框架约定）。 */
    protected List<TLAttachmentRef> normalizeAttachments(TLMsg msg, String sessionId) {
        return attachments.normalize(msg, sessionId);
    }

    /** 把调用方传的 AI_P_ATTACHMENTS（List<Map> 四形态）归一化成 ref 列表；解析失败逐条忽略 */
    protected List<TLAttachmentRef> normalizeAttachments(TLMsg msg, String sessionId, String userId) {
        return attachments.normalize(msg, sessionId, userId);
    }

    /**
     * 从工具输出里提取"技能表态要给模型看"的图片。
     * 缺省不给（保守默认）——技能必须在输出 JSON 里写 "image_for_model": true。
     * 返回 null 表示"未声明或解析不可用"，调用方保持原文本不动。
     */
    protected List<TLAttachmentRef> extractDeclaredImages(String output, String userId,
                                                          List<TLConversationHistory> history) {
        return attachments.extractDeclaredImages(output, userId, history);
    }

    /** 把已提取的图数据 key 从文本里换成占位符（避免同一份 base64 在上下文重复占位） */
    protected String stripImageKeys(String output) {
        return attachments.stripImageKeys(output);
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
            if (h.hasImages()) {
                // 手册：每张图折算封顶 1024 token，按上限保守计
                toks += (long) h.getImages().size() * 1024L;
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

    // ======================== 截断如实告知 ========================

    /** 终答被 max_tokens 截断（finish_reason=length）时的告知后缀。此前截断是静默的：用户只能看到半句话 */
    private static final String TRUNCATED_NOTICE =
            "\n\n（⚠ 本条回复因长度上限被截断，未写完——可回复「继续」让我接着写；"
                    + "长任务可调大该 agent 的 maxTokens 或关闭其推理）";

    /** finish_reason=length 时给终答/入史文本补截断提示；其余原样返回（幂等：已含提示不重复追加） */
    private String withTruncationNotice(String text, String finishReason) {
        if (!"length".equals(finishReason)) return text == null ? "" : text;
        String t = text == null ? "" : text;
        return t.contains(TRUNCATED_NOTICE) ? t : t + TRUNCATED_NOTICE;
    }

    /**
     * 空响应入史占位符。finish_reason=length 与普通空响应要分开：
     * 前者是"推理吃光预算、正文一个字没写出来"（配了推理的 agent 常见），如实说明比"（无输出）"可操作
     */
    private String emptyHistoryPlaceholder(String finishReason) {
        return "length".equals(finishReason)
                ? "（⚠ 推理占满长度上限，正文未写出——可回复「继续」重试，或调大该 agent 的 maxTokens / 关闭其推理）"
                : "（无输出）";
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
            // llmJudge 的 apiKey 在 agent 的 <providers> 段，按名字 getMyModule 只能拿到
            // 工厂层声明（无 apiKey）。把本 agent 已解析的 provider 实例带过去，避免静默失效。
            if (llmProvider != null)
                guardMsg.setSystemParam("llmProviderInstance", llmProvider);
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
     * 迭代上限截断提示落 history（assistant 消息）。
     * 安全性：末条若是带 tool_calls 的 assistant（没有对应 tool 结果），直接追加 assistant
     * 会破坏 tool 配对（下轮 400），此时放弃追加，保历史可发送。
     * @return 追加后的终答在 history 中的下标；未追加（空文本/悬空 tool_calls）返回 -1
     */
    private int appendTruncatedFinalToHistory(List<TLConversationHistory> history, String text) {
        if (text == null || text.isEmpty()) return -1;
        if (!history.isEmpty()) {
            TLConversationHistory last = history.get(history.size() - 1);
            if (last.getRole() == TLConversationHistory.Role.assistant
                    && last.getToolCalls() != null && !last.getToolCalls().isEmpty()) {
                putLog("Skip truncation notice in history: dangling assistant tool_calls", LogLevel.WARN);
                return -1;
            }
        }
        history.add(new TLConversationHistory(TLConversationHistory.Role.assistant, text));
        return history.size() - 1;
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
     * 添加包名前缀（与TLDatabase.addPackage相同的模式）
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
     * 遵循TLDatabase.myConfig模式。
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
