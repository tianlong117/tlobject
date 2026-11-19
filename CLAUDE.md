# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A Java-based **Unified Object Message Programming** framework. The core idea is replacing direct method calls with message passing between named objects managed by a unified factory. This allows loose coupling and runtime flexibility.

Blog/文档: https://blog.csdn.net/tianlong117

## Commands

```bash
# Build entire project (Java 17 required)
mvn clean install -DskipTests

# Build core module only
mvn clean install -pl core -DskipTests

# Package with sources
mvn clean package -DskipTests

# Single module build
mvn clean install -pl database -am -DskipTests
```

No JUnit test framework is configured. Instead there are two layers of self-testing: runnable `main()` examples in the demo module, and `TLAgentTestModule` (aiagent, Mock Provider 驱动) — 40 deterministic test scenarios runnable via `/test` console command or `runAllTests` message (config: `conf/demo/aitest/` and `conf/demo/aiagent/`).

## Architecture

### Core Layer (`core/`) — `cn.tianlong.tlobject.base`

The foundational message-passing framework:

- **`IObject`** — Core interface: `getName()`, `putMsg(IObject, TLMsg)`, `getMsg(Object, TLMsg)`
- **`TLMsg`** — Message class carrying `action` (method name), `args` (parameters), `systemArgs` (framework params), routing fields (`source`, `destination`, `previous`), and a `nextMsg` chain pointer
- **`TLBaseObject`** — Base implementation of `IObject`. Contains `putMsg()` (sync/async), `putMsgNoWait()` (async with ThreadTask), and the inner `ThreadTask` class
- **`TLBaseModule`** — Extends `TLBaseObject`. The main building block for all modules. Features: XML-driven configuration, message routing tables (`msgTable`, `beforeMsgTable`, `afterMsgTable`), module lifecycle (`init()` → `runInitMsg()` → `runStartMsg()`), method reflection (`invokeAction`), scheduling, logging, thread pool integration
- **`TLObjectFactory`** — Central factory that instantiates modules by name from XML config. Supports hierarchical factories (parent/child), singleton/ prototype modules, lazy loading
- **`TLModuleConfig`** — XML config parser using XmlPullParser. Parses `<modules>`, `<params>`, `<initMsg>`, `<startMsg>`, `<msgTable>`, `<beforeMsgTable>`, `<afterMsgTable>`, `<include>`
- **`TLParamString`** — Constants interface with all framework action names, parameter keys, module names, and string constants

### Module Layer (`core/` modules) — `cn.tianlong.tlobject.modules`

Built-in modules extending `TLBaseModule`:

| Module | Purpose |
|--------|---------|
| `TLAppStartUp` | Application entry point, CLI arg parsing, multi-app lifecycle |
| `TLThreadPool` | Thread pool for async message execution |
| `TLMsgRouter` | Message routing from `msgTable` config |
| `TLMsgBus` | Publish-subscribe message bus |
| `TLMsgBroadCast` | Broadcast messages to registered receivers |
| `TLMsgScanner` | Scheduled message scanning |
| `TLMsgBlockingQueue` | Blocking message queue |
| `TLModulePool` | Pool of reusable module instances |
| `TLMsgLog` / `TLMsgTaskConsole` | Logging / task management |
| `TLParamValidation` | Parameter validation |
| `TLReUsedModulePool` | Reusable module pool |
| `TLSessionData` / `TLBaseSessionData` | Session data management |
| `TLMonitorConfigModule` / `TLModuleMonitor` | Monitoring |
| `TLExecptionHandler` | Exception handling |
| `LogLevel` | Log level enum |

### Other Modules

| Module | Purpose |
|--------|---------|
| `database/` | Database access via message-oriented DAO pattern (CRUD, transactions, table management) |
| `network/` | Network layer: Jetty/Tomcat/Netty servers, WebSocket server/client, HTTP client, proxy, JWT auth |
| `cache/` | Caching: EhCache, Caffeine, file cache, memory cache |
| `redis/` | Redis integration via message actions |
| `crontab/` | Cron-style scheduled tasks |
| `connectpoolofdb/` | DB connection pooling (C3P0, HikariCP) |
| `log/` | Logging module (SLF4J bridge) |
| `servletutils/` | Servlet utilities |
| `utils/` | Utility classes |
| `appwebmanager/` | Web management app |
| `execl/` | Excel export |
| `tlobject-all/` | Aggregate artifact |
| `demo/` | Runnable example applications |
| `aiagent/` | AI Agent framework: LLM chat, tools/skills, memory, streaming |

- POI 统一 5.5.1（根 pom `<dependencyManagement>` 钉死）：`execl`（Excel）与 `aiagent/word-java`（Word）共用同一版本——同 classpath 上两个 POI 版本会同名类"先到先得"，行为不可预测。升级注意：`HSSFDateUtil` 在 POI 5.0 已删除（改 `org.apache.poi.ss.usermodel.DateUtil`）；`poi-ooxml-schemas` 改名为 `poi-ooxml-lite`；5 个 `aistart*.bat` 的 classpath 是**真正生效的运行时 classpath**（项目不 shade jar），改 pom 不改 bat 会导致启动时 `NoClassDefFoundError`。

### AI Agent Framework (`aiagent/`) — `cn.tianlong.tlobject.aiagent`

消息对象编程模型下的 AI 大模型智能体子框架。Agent 接收用户输入 → LLM 分析意图 → 调用 Skill 执行 → 返回结果。支持流式/非流式、多轮对话、记忆管理。

**核心模块:**

| Module | Class | Purpose |
|--------|-------|---------|
| `aiagent` | `TLAiAgent` | 主控编排器，chat() 统一实现流/非流 |
| `toolManager` | `TLToolManager` | 每 agent 私有工具管理模块（工具实例化/注册/热加载/重载/toolDefs 缓存；黑盒两条消息接口：getFunctionDefinitions + resolveToolCalls） |
| `toolExecutor` | `TLToolExecutor` | 工具执行器（工厂共享单例，executionId 隔离运行态；审批为全局门禁见 HITL 段） |
| `aiContext` | `TLAiContext` | 会话历史管理，自动裁剪 |
| `openAiProvider` | `TLOpenAiProvider` | OpenAI/DeepSeek 兼容 Provider，SSE 流式 |
| `claudeProvider` | `TLClaudeProvider` | Claude API Provider |
| `httpRequestSkill` | `TLHttpRequestSkill` | HTTP 请求 Skill (tool: http_request) |
| `fileOperationSkill` | `TLFileOperationSkill` | 文件读写 Skill (tool: file_operation) |
| `codeExecutionSkill` | `TLCodeExecutionSkill` | 代码执行 Skill (tool: code_execution) |
| `wordJavaSkill` | `TLWordJavaSkill` | Word 文档读/写/改 Skill (tool: word)；独立模块 `aiagent/word-java` |
| `shortTermMemory` | `TLShortTermMemoryModule` | 短期记忆（内存+TTL） |
| `longTermMemory` | `TLLongTermMemoryModule` | 长期记忆（文件持久化） |
| `streamCallback` | `TLStreamCallback` | 流式回调通用模块 |

**Maven 结构:** `aiagent/common` + `provider-openai` + `provider-claude` + `skill-builtin` + `memory-store`

**Demo:**
- `AiAgentDemoModule` — 8 场景自动化测试（全部通过）
- `ChatDemo` / `AIStart`（aistart.bat，config `conf/demo/aiagent/`）— 交互式对话，支持 `/stream` `/thinking` `/clear` `/quit`（只退控制台）`/login`（重登）`/shutdown`（停应用）`/test` `/eval` `/mcp` `/approve` `/sessions` `/continue` `/resume`

**控制中心（core `TLControlCenter` + aiagent `TLAgentControlCenter`）：**
- `TLControlCenter`（core）是通用机制：启动编排（配置文件 `<startMsg>`）、模块注册门面、配置重载白名单、审计。`TLAgentControlCenter`（aiagent/common，继承前者）承载 AI 专属策略（用户分级/命令授权、服务暂停、agent 编排等），三套 aiagent 应用配置使用该子类；其他应用可直接用 core 版本。
- 启动分层：`factoryBoot` 只创建基础设施（`logThreadPool`/`threadPool`/`database`）与控制中心；应用模块由控制中心的 `<startMsg>` 创建。控制中心把 `<startMsg>` 交给工厂的 `bootstrapModules` 以工厂为发送者执行，应用模块的 `familyName` 保持裸名（否则会被加上 `controlCenter:` 前缀，破坏注册键）。
- 注册目标由工厂 `commonParams` 的 `controlCenterName` 指定；未配置时不注册，配置后直接发给控制中心（不做工厂存在性检查——启动分层保证控制中心先启动）。控制中心名在各模块 `setModuleParams()` 解析一次存字段，注册点只读字段；字符串参数统一用 `TLBaseModule.getParamNonEmpty(key[, default])`（null/空白视为未配置并 trim，配置是人写的，容错但不改语义）；core 基类不感知控制中心。基础设施层（L1）模块不得自注册。
- 内部注册表是控制中心的私有子模块，外部只通过控制中心访问。配置重载白名单 `reloadableModules`（分号分隔的显式模块名；空全拒绝；**不支持 `*`**，配置为 `*` 时 WARN 并按全部禁止处理）。`/reloadconfig` 无参列出白名单条目及实时可用状态（是否已实例化/有无配置文件；控制中心 `reloadableList` → AI 服务 `reloadableModules`），WebUI 设置组「配置」Tab 同源。查看配置/参数另有 `viewableModules` 白名单（同样分号分隔、不支持 `*`、空=全部禁止，fail-closed）：`/config <模块名>`（别名 `/cfg`）读模块配置文件（基类 `getConfigFileInfo` 框架动作，返回路径/mtime/SHA-256/原文，内容 256KiB 截断），`/params <模块名>` 读内存 params；两者都经控制中心门禁并写审计（只记元数据，不记内容/参数值），响应附带最近一次 reload 审计。两份白名单以控制中心配置文件为权威：`/reloadconfig controlCenter` 时重新解析配置文件，**新增/修改/删除均生效**（普通模块 `reloadConfig` 仍是 params 只合并不清理）。旧 `/param` 更名为 `/toolparam`（查注册表内 Agent/Skill/Group 参数，不受 `viewableModules` 限制）。审计记录含 `allowed`（策略是否放行）与 `success`（执行是否成功），控制台 `/audit [条数]`、WebUI `/api/command`（action=audit）可查。控制中心显式配置了 configfile 但加载失败或缺少 `<startMsg>` 时拒绝启动（fail-fast）。
- 测试隔离：`TLTestAgentGroup` 直接构造、不走 `commonParams`，故意使用旧 `moduleRegistry`，避免假成员污染生产注册表。

**关键实现要点:**
- **流式/非流式统一编排**：`runChat → prepareChat → runChatLoop` 唯一一份；流式差异只在 `LlmCall`（`StreamLlmCall` 发起 SSE + 等 per-call `StreamHandle`）和 `StreamSink`（`ForwardStreamSink` 推 UI / `NullStreamSink` 丢弃）。`chatStream` 把同一 `runChat` 丢到工作线程后立即返回 null；`onStreamResult` 只做 chunk 转发与句柄唤醒，**不再承载工具循环**。工具迭代轮默认非流式（`STREAM_TOOL_ROUNDS=false`）。`chatStreamSync` 已删除（原仅测试调用且无工具循环）。
- **流式入口的轮次登记**：`chatStream` 在提交 worker **之前**同步 `sessionRoundBegin`（关闭 `injectTaskResult` 的空闲窗口），worker 用 `preBegan=true` 跳过重复 begin；提交失败由入口 `sessionRoundEnd` 收尾。
- **错误语义**：流式非人为错误保持 checkpoint（不发 chatFinished）；人为停止走 `chatAborted` + `streamFinal`；`handleRunError` 按 `ctx.cancelled()` / `sink.isNull()` 分流。
- **输出护栏与流式增量**：`guardOutput` 可能替换/截断正文，导致终答不再是流式文本的前缀；此时发 `AI_P_STREAMREPLACE`，webui 整体替换气泡文本，控制台打印"已修正 + 全文"。不要用长度差做增量；`TLStreamCallback`（测试/外部 sink）也按 replace 帧整体替换缓冲（场景 6 有契约断言）。
- **`TLStreamCallback` 不再被产品路径使用**：保留为测试/外部集成 sink；新代码做流式收集请实现 `StreamSink`。`_streamResultFor` 不能等于 agent 自身的 name（入口会 WARN 并丢弃，避免自转发死循环）。
- 直出（`directOutput`/finalAnswer）：终答必须作为 assistant 消息写入 history 再收尾才算完整——`runChatLoop` 的直出分支统一处理；只推前端不落 history 的后果是恢复/重登会话终答消失（只剩开场白+工具卡片，"诗没有了"），`/test streamDirect` 场景守此契约
- 推理（reasoning）四条铁律（都是实测踩过的坑）：① `TLOpenAiProvider` "空正文把推理提升为正文"兜底只在**正常收尾 stop + 未调工具**时成立——工具回合载荷在 tool_calls（正文可空，API 接受有先例）、截断回合（length）的推理是烧完预算的独白而非答案，提升会把英文思考当正文入库（重登可见、每轮回传）甚至当点评返回；② 终答 `finish_reason=length` 必须如实告知（agent 层补"被截断"提示 + 空正文占位说明），不再静默腰斩；③ 流式链路（chatStream 首包 + 续跑循环）与 doChat 一样要透传 reasoningMode/maxTokens——此前两处都不传，配 `disabled` 关不掉思考、maxTokens 不生效（`off` 对推理模型=照常推理，推理与正文抢同一份 max_tokens）；④ **DeepSeek thinking 模式的 reasoning_content 必须回传**：assistant 消息里出现过的 reasoning_content 要在下一次请求原样带回，否则工具回合后 400 "must be passed back"（2026-11-14 codeAgent 连续失败→master 改用 desktop 点终端的真机事故）。`TLOpenAiProvider.buildRequestBody` 已回传；`ChatTurn.extractReasoning` 在 `off` 模式也捕获并挂到 assistant（不展示、不注入独立 reasoning 条目）；任务类子 Agent（codeAgent/codingAgent/fileAgent 等）配 `reasoningMode="disabled"` 从源头关思考。`off` ≠ `disabled`：off 不干预，推理模型照常思考。守护：`PromotionGuardSelfTest`（出站 reasoning_content）+ `ChatTurnStreamFallbackSelfTest`（off 模式挂载）+ `/test` 场景 12/13/14；诗歌节点已配 `reasoningMode="disabled"`
- **截断的 tool arguments 不可执行**：`arguments` 字符串非空但 JSON 解析失败（典型 `finish_reason=length`）时，Provider 在 `TLToolCall` 打 `argumentsInvalid`；`TLToolManager.resolveToolCalls` 不执行，改为把「工具调用参数 JSON 不完整，请减少单次输出后重试」作为 tool 结果回喂。2026-10-26 真机事故：旧行为静默当空参数执行 → 子 agent 连续 `missing userMessage`、白烧 8 轮后撞上 25 轮上限，任务清单停在第 2 项。同轮修复：迭代上限提示作为 assistant 消息落 history（此前只回 UI，重登看不到停因）；单次请求可用 `maxToolCallIterations` 参数覆盖 agent 配置。守护：provider `PromotionGuardSelfTest` + `/test truncToolArgs`
- `beforeMsgTable` 的 `beforeResult` 在 `msg.systemArgs["beforeResult"]` 中，需在業務方法主動讀取。`afterMsgTable` 會覆蓋返回值，記憶保存改為內部調用
- Tool call 的 `arguments` 必須是 JSON 字符串（`gson.toJson()`），不能用 JSON 對象（`gson.toJsonTree()`）
- **身份字段只信 systemArgs**：`userId`/`sessionId`/`rootSessionId` 不能从 LLM 工具参数取值——`TLToolExecutor.doToolExec` 对非 MCP 工具做净化（伪造值覆盖为可信值，无可信值则剔除，不凭空注入）；`schedule_task`/`word` 等技能以 systemArgs 优先、args 仅作命令/测试路径兜底（2026-10-22 越权评估：模型被诱导带别人 userId 可跨用户 list/create/remove 定时任务）
- DeepSeek: tools 和 temperature 不能同時傳，model 名在 Provider 配置中指定
- provider 配置的 `apiBaseUrl` 语义 = **服务根**：框架固定自拼 `/v1/chat/completions`（embeddings/models 同）——厂商文档的 OpenAI base 若带 `/v1` 直接粘贴也可用（`TLLlmProvider.joinBaseUrl` 去重；修复前会双拼 `/v1/v1/...` → 404）；响应解析要防网关**显式返回 null 字段**（如 `"completion_tokens_details": null`，Gson 旧版 `getAsJsonObject` 强转 JsonNull → ClassCastException，parseResponse 已加 has+isJsonNull 守卫）；**纯文本网关须配 `supportVision="false"`**——历史里的图片会拼成 `image_url` 内容块，文本模型网关一律 400（流式路径丢弃 body，前端只看到"[流式错误] HTTP 400"），false 时发送端跳过图片块只留文字清单
- `browser` skill 三形态：ephemeral / 持久化（默认 userDataDir=data/browser_profile）/ CDP 接管真实浏览器（cdpEndpoint+cdpAutoLaunch，配合 agentbrowser.bat 人工登录；attach 回收只断连、不关用户浏览器；形态参数只在进程启动时生效）。**demo master 配置当前（2026-11-15）为③ CDP 接管本机浏览器**（人工登录测试）：`cdpEndpoint`/`cdpAutoLaunch`/`cdpProfileDir` 三行已启用，浏览器没开时自动拉起；测试完删掉这三行即回到②持久化 headless（独立无窗口 Chromium；两套 profile 不共享登录态，cdpProfileDir 只在③下必填）
- `browser` skill 两版实现：Python（默认，子进程）/ Java（`sameClassAs="browserJavaSkill"`，进程内 Playwright Java，独立模块 `aiagent/browser-java`，切换=改一行；首次运行装全部浏览器约 500MB；空闲回收与 Python 版一致——浏览器与 driver 一起释放，被 `/reload` 换掉的旧引擎最迟一个 `idleTimeoutSeconds` 后自愈回收）
- `desktop` skill 两版实现：Java（默认，`sameClassAs="desktopJavaSkill"`，进程内纯 JDK `java.awt.Robot`，独立模块 `aiagent/desktop-java`，零外部依赖）/ Python（备选，`sameClassAs="scriptExecutionSkill"` + `interpreter="python"` + `allowedScriptDir="skills/desktop/scripts"`，改配置一行回退）；`type` 仅 ASCII、中文输入走 `paste`；系统缩放 >100% 且 JVM 非 DPI 感知时点按/输入类动作明确拒绝（截图与坐标查询仍可用）
- 多模态（vision）：`TLConversationHistory` 有 `attachments`（资源引用，驱动发送时"清单"）/`images`（真正发给模型的图片块）两字段（`content` 保持 String）；附件统一经 `attachmentStore` 模块登记（上传件原地引用、截图/base64 内容哈希落盘去重）；**默认全按需**——模型看到清单后调 `view_image(路径)` 才真正拿到图；工具产图由**技能自己**在输出 JSON 里 `"image_for_model": true` 表态，agent 只搬运（且必须攒到整批 tool 消息之后注入，否则并行批次会被 `sanitizeToolPairs` 判成孤儿）；`buildSendList` 对发送视图做真拷贝（此前浅拷贝会写回活上下文）（Plan 2 已接入口：控制台 `/img` `/file`、webui 上传进对话 + 历史缩略图 + `/api/image` 出图、桌面/浏览器技能表态 `image_for_model`）。**图片注入白名单**（`AttachmentPipeline.isAllowedImagePath`）= 本会话已登记附件 **或** 本用户 `workspace/{uid}` 内既存文件（管理页上传/工具产物；realpath 防 symlink 逃逸），`data/{uid}` 下旧附件/自动附件仍必须已登记——管理页上传的图片不进 [附件] 清单，靠后者放行；`view_image` 支持 workspace 相对/绝对路径，裸文件名在 workspace 内唯一同名时自动定位（多个同名报错并列出相对路径）。守护：`AttachmentWorkspaceImageSelfTest`（9 项，含 `extractDeclaredImages` 注入链路）+ `ViewImagePathSelfTest`（9 项，含裸文件名兜底）
- **生成文件下载（2026-11-14）**：WebUI 新增 `/api/download?path=...`（`urlMap` → `webui.download` → `TLDownloadService.resolve`）：只允许本用户 workspace 内普通文件，`toRealPath` 防 `..`/软链/目录联接逃逸；`putStream` 对非图片扩展名自动 `application/octet-stream` + attachment，图片靠前端 `<a download>` 强制下载。前端 `linkifyDownloads` 自动把助手文本里的 `D:\...`/`workspace\...` + 常见扩展名链成「下载」，历史回放/流式/非流式都覆盖；收尾时还在答复下方渲染**独立下载框**（`TURN_FILES` 按轮收集工具输出路径；优先最终回答点名的文件，排除 `_tmp`/`_build`/脚本/备份；历史回放按真实 user 消息分轮，框落在该轮最后一条 assistant 下面），像工具进度框但保留在聊天流。不需要每个生成工具单独配置。**`view_image`（及任何输出 `image_for_model:true` 的工具）的 `image_path` 是输入图片，不进下载框**（`TURN_INPUT_FILES` 记录、`pickDeliverableFiles` 一律排除，失败信息里的路径也按输入处理；流式与历史回放同规则），否则分析图片后会在答复下误出“生成文件”框。历史回放靠 `TLConversationHistory.name` 识别 `view_image`，**`TLWebChatModule.toJsonable` 必须输出 name**（此前丢失 → 重登回放识别不到输入图、误出下载框）。**路径去重按 `normPathKey`（反斜杠→正斜杠 + 小写）**——工具消息与助手文本斜杠写法不同不会把同一文件列两次；回放只为每轮终答加行内链接（中间过程气泡不链），**已进下载框的文件不再链行内链接**（实时/非流式/回放同规则，避免同一文件两个下载入口）。守护：`TLDownloadServiceSelfTest`（6 项）+ `TLWebChatModuleHistorySelfTest`（4 项，含工具消息带 name）+ `smoke/download_regex_check.js` + `smoke/download_box_pick_check.js`（13 项，含输入图排除/真正生成文件保留/斜杠写法去重）
- **独立管理工作空间页面（2026-11-14）**：`/webui/manage.html`（聊天页顶栏「管理」，调试左侧，新标签打开；不嵌入聊天侧栏）。左侧管理菜单（当前「工作空间管理」），右侧**目录浏览器**：面包屑 + 「↑ 上一级」、「工作空间」根/文件夹逐层点开；只列**当前目录直接子项**（文件夹在前；表头点击排序：名称/类型/大小/修改时间，首点 name/type 升序、size/mtime 降序，再点切换升降序，类型按扩展名分组、文件夹恒在最前，排序后保留勾选；文件带大小/时间/类型），行内「下载」「删除」（文件夹行是「打开」+「删除」，删除为**递归**：连同其中全部内容，确认框明确提示；workspace 根不可删；文件夹不参与勾选批量删除）；**新建文件夹**（`/api/workspace/mkdir`，单段名，拒绝 `/ \ . ..` 与重名）；**上传到当前目录**（input multiple → FormData `file_0..` → `/api/workspace/upload?dir=<当前目录>`，dir 必须走 query——multipart body 配 `clientType` 会被 `jsonInInterface` 吃掉；单文件 50MB）；每行复选框 + 表头全选（**只选文件**）+ 「删除选中（N）」（`/api/workspace/delete` 接受 `paths` 数组，单条 `path` 兼容；删除文件夹必须带 `recursive:true`，**缺省仍只删文件、目录一律拒绝**；部分失败返回 deleted/total）；筛选按名称/相对路径、筛选即清勾选。后端 `/api/workspace/list|delete|mkdir|upload` → `TLWorkspaceService`：路径一律 `toRealPath` 校验在本用户 `workspace/{userId}` 内、目录项 `NOFOLLOW_LINKS` 跳过 symlink，文件删除复用 `TLDownloadService.resolve`，目录递归删除走 `resolveEntry`（根除外、不跟随 symlink 的 `walkFileTree`）；用户根目录不存在时列根返回空列表（不是错误），`ensureDir` 在根上建文件夹/上传时按需创建根；列表单层最多 2000 条。守护：`TLWorkspaceServiceSelfTest`（17 项，含目录直列/建夹/批量删除 2/4/新用户空列表/递归删夹/根不可删/越界拒删）+ `smoke/manage_page_check.py`（42 项，静态页 + Mock API + 目录进出/面包屑/上一级/建夹/按目录上传/筛选/全选仅文件/单选与批量删除/文件夹递归删除/**四种排序与升降序切换/排序保留勾选**）
- **会话侧栏（2026-11-15）**：会话行内只保留「切换 / 改名」+「更多」；「更多」菜单收纳 接管 / 置顶 / 清除上下文 / 删除（fixed 定位避免被滚动容器裁剪；ESC、点外部、侧栏滚动、窗口缩放时关闭）。**置顶**存在会话管理器里（不再单独文件）：DB 版写 `ai_sessions.pinned`（老库启动时幂等 `alter table` 加列，与 title 列同一套迁移；`database_config.xml` 建表 SQL 已含该列），文件版写 `_session_pins.idx` sidecar；`pinSession` 是 agentService action → `sessionManager.setSessionPinned`，`listSessions` 元数据带 `pinned`，删除会话时随会话一起清理。置顶组恒在最前（组内保持服务端最近活动顺序）、行首显示 📌、刷新/重登/**换设备登录**都保持；旧版浏览器 localStorage 置顶会在登录后一次性迁移到服务端（失败保留本地键到下次）。守护：`TLSessionManagerPinnedSelfTest`（10 项，文件版 sidecar/持久化/删除清理/用户隔离）+ `smoke/session_list_check.py`（20 项）
- 多任务/多会话：后台任务 = `kind=background`（LLM 工具 kind 参数 / webui"后台执行"开关 / 与定时任务同一套记录与引擎）——独立执行会话 `task_*` + chatStream 异步执行（TLAiAgent 新增：会话运行计数、任务结果注入队列 injectTaskResult、流式转发私有子模块实例 `_streamForwardInstance`）；进度/终态经 msgBus topic taskStarted/taskProgress/taskResult 推 webui；结果空闲直注发起会话/忙排队轮末 flush/离线消息箱；webui 侧栏多会话并行（每会话独立视图 box/busy/abort/草稿，切会话不打断后台流）；启动时 running→interrupted 收口（重启不可能续跑，如实标记并通知）
- 工作流节点进度：`TLWorkflowEngine.setStatus` 统一埋点（RUNNING/DONE/SKIPPED/FAILED:strategy/RETRY:n/m/TRUE/FALSE/FALLBACK_READY + `_workflow` 起止）→ 宿主 `TLAgentWorkflow` 每次运行一个 publisher 发 msgBus topic `workflowProgress`（runId/nodeId/nodeType/totalNodes；会话取 input，缺 rootSessionId 回落到 sessionId）→ 控制台/WebUI 独立渲染（**不复用工具进度面板**：executionId 交替会清屏；WebUI 按 sessionId→rootSessionId 兜底路由）。开关：工作流 `progressToBus`（默认 true）、UI `subscribeWorkflowProgress`（默认 true）。只传状态，不含节点产出。**WebUI 节点面板保留在聊天滚动流**（插在助手气泡前，回合结束不清；清上下文/重建视图时清），工具进度面板仍是瞬时的
- Agent 组进度：`TLAgentGroup` 在 chat/runSequential/runParallel/supervise 埋点，复用 msgBus topic `workflowProgress`（事件 `source="group"`，字段 `groupName/attempt/nodeType`）→ 控制台 `formatGroupProgress` / WebUI 沿用工作流节点面板（保留在聊天滚动流）。整体 `_group` 状态 `RUNNING/DONE/PARTIAL/FAILED/INTERRUPTED`；成员/监理 `RUNNING→DONE|FAILED:固定原因`，sequential 中止补 `SKIPPED`；监理重跑用 `attempt` 复位成员行。组参数 `progressToBus` 默认 true；parallel 成员无单成员耗时（框架只给整批结果）。守护：`/test groupProgress` + `TLAgentGroupProgressPublisherSelfTest` + `TLChatConsoleWorkflowProgressSelfTest` + `TLWebChatModuleProgressSelfTest` + `smoke/workflow_progress_check.py`
- 工具输出客观校验：`TLToolExecutor` 在结果交给 LLM 前调用可选 `outputValidator`（`toolExecutor_config.xml` 的 `outputValidator=...`；不配 = 不校验）。规则按 LLM 函数名/模块名匹配，不配规则默认通过；`checks` 支持类型/范围/跨字段算术（`total==quantity*unitPrice`）；`validator="self"` 直接调用本次技能/子 agent 实例的 `validateOutput`（私有模块可用），其他值为工厂模块名 + action；失败按 `retryable` 分级（默认 false，不自动重试），工具结果标 error 回喂；`final` 直出也校验，失败仍短路、终答用修正/错误文案（不再调 LLM）。一期只校验工具结果，agent 终答对拍二期。守护：`TLOutputValidatorSelfTest` + `/test outputValidation` + `/test finalOutputValidation` + hamburger `outputValidator_config.xml`。配置热加载：`/reloadconfig <模块>`（控制台/WebUI 同一 agentService action `reloadModuleConfig`；基类做文件/XML 预检，validator 用 pending 快照原子替换，失败保留旧规则）。
- **工作流节点级超时（2026-11-18）**：`TLWorkflowNode.timeout`（毫秒，0=不限）此前只解析未执行，现已由 `TLWorkflowEngine.expireNodeTimeouts()` 在调度循环强制执行（超时先 `NodeTask.cancel()`，`isOver()` 的完成任务不误判）。失败策略：`onTimeout=fail|skip` 优先，未配置沿用 `onFailure`（retry 会按次重排）；状态 `FAILED:timeout` / `FAILED:timeout(skip)` / `FAILED:retry(exhausted,timeout)`。工作流控制参数（timeout/onTimeout/onFailure/maxRetries/fallbackNodeId）不再透传给节点 Agent。守护：`TLWorkflowNodeTimeoutSelfTest`。
- **工具健康度/熔断（2026-11-18，默认关闭）**：`TLToolExecutor` 按 moduleName 统计跨轮传输级连续失败（null 响应/执行异常/单任务超时），达到 `healthFailureThreshold` 后进入 `healthCooldownMs` 冷却，期间短路为普通错误结果（`ToolResult.state=null`，LLM 可改用其他工具），冷却后只放行一个半开探针。**业务 `RESULT=false`、审批拒绝/待批、输出校验失败不计失败**；用户停止/批次执行超时也不计。`healthFallback.<module>=<tool>` 只生成提示不自动改派；`getToolHealth`/`resetToolHealth` 供排障。默认 disabled 时行为与之前完全一致。守护：`TLToolExecutorHealthSelfTest`。
- **Agent 组 `mode="vote"`（2026-11-18）**：同一任务并行发给全部成员，配置 `role="supervisor"` 时由它做一次 best-of-N 选择（只输出 `{"best":"成员名"}`，不触发既有 supervise 重跑）；评审缺失/非法/选未知成员自动回落多数表决（trim 后计数，平票取成员顺序最早）。空白与 `[错误]` 候选剔除；全部失败返回结构化失败，不把错误文本当前答。`maxReviewRounds` 在 vote 模式不生效。模板：`voteTeam_config.xml`（master 中默认注释）。守护：`TLAgentGroupVoteSelfTest`。
- **共享黑板（2026-11-18，可选）**：`TLSharedBoard`（`aiagent/common/.../board/`）+ `TLBoardSkill`（函数名默认 `board`）补"跨 Agent/跨任务可读写状态"。`scope=session`（userId+rootSessionId）或 `user`（同一用户跨会话）；操作 put/append/get/list/delete，版本递增；`boardPut` 支持可选 `expectedVersion`（0=要求 key 不存在，版本不匹配返回 conflict，供计划类读改写防并发覆盖）；身份**只读 systemArgs**（SKILLINPUT 伪造 userId 无效）；持久化 `{dataDir}/{safeUserId}/board/{sha256(scopeKey)}.json`（原子替换，损坏回落空板+WARN）；限额 TTL/条目/值长/作用域 LRU；变更发 msgBus `boardEvent`（bus 缺失静默）。注册：`cn.tianlong.tlobject.aiagent_config.xml` 的 `sharedBoard`/`boardSkill`；master 中默认注释，取消注释即接线。执行器会把调用方 `agentName` 放入 execMsg systemArgs 供 producer 记录。守护：`TLSharedBoardSelfTest` + `TLBoardSkillSelfTest` + `/test sharedBoard`/`boardTool`。
- **结构化计划（2026-11-18，可选）**：`TLPlanSkill`（函数名 `plan`）把任务拆解/依赖/状态/重规划记录存共享黑板（key=`plan:<name>`，session/user）。操作 `create/get/list/update_task/add_task/cancel_task`；依赖做未知依赖/自依赖/环校验；计划状态由任务推导（全部 done/skipped → done）；add/cancel/带 reason 的 update 记录 replan；变更发 msgBus `planEvent`；写入用 board `expectedVersion` 乐观锁。它不替代工作流引擎（不改运行中 DAG），是"可读写的计划状态"。注册 `planSkill`，master 中默认注释。守护：`TLPlanSkillSelfTest` + `/test planTool`。
- **updateAgent 描述热更新修复（2026-11-18）**：`TLToolManager.updateAgent` 过去只写 `agentsConfig`，工具定义重建读 `FunctionEntry.description`，导致 LLM 永远看到旧描述。现同步替换 FunctionEntry（继承 enabled/timeoutMs/nativeName）并失效缓存；对象不是已注册 agent 时保持原失败语义。守护：`TLToolManagerUpdateAgentSelfTest`。
- 长工具循环的目标锚定与收尾门禁（2026-11-14 `webchat_tianlong_1794627998443` 事故）：`buildSendList(full, memory, roundStartIdx)` 在 `contextViewSize` 尾部裁剪时**恒定保留本轮提问**；**顺序铁律：提问在窗口内时保持原时间顺序（不要搬到头部），只有被窗口裁掉（长工具循环，窗口内全是本轮工具结果）才补到窗口前**——搬到头部会变成 `[system][本轮提问][上一轮 assistant/tool...]`，模型按最后一条旧 assistant/tool 继续旧任务（2026-11-14 第 12 轮：用户问"肝癌/中国酒消耗"，请求末尾是上一轮"肺癌走 B"的 assistant，答成了图例/肺癌）；同时把本轮最新 todo 结果里的未完成项提炼成 system 提醒注入——否则 40 条窗口被搜索/浏览器结果冲掉后，模型丢掉目标、把别的搜索结果当交付物并自报完成。`ChatTurn` 终答分支加门禁：本轮写过 todo 且仍有 `- [ ]`/`- [~]` 时不收尾，注入提醒继续（`todoGateEnabled` 默认 true、`todoGateMaxRetries` 默认 2；重试用尽终答注明未完成）。`trimToContextBudget` 的保护对象从"最后一条 user"改为本轮提问（工具图片也是 user 角色，旧判据会被图片顶掉）。守护：`smoke/agent/RoundAnchorSmoke` + `TodoGateSmoke`
- 工具截图与浏览器点击语义：`browser` 的 `click` 返回 `pageChanged`/`tabOpened`（自动接管 `target=_blank` 新标签），同一 selector+起始 URL 连续 3 次无变化直接 `ok=false`（旧实现每次都回 ok:true，模型会连点 8 次死链）；`navigate` 也回 `pageChanged`。**聊天界面不显示页面截图**：`ToolResultAppender` 推给 UI 的 `toolEventSink` 一律 `stripImageKeys`，历史 tool 文本也不再存 base64，WebUI `renderToolResult` 不再从 `screenshot_base64` 提图；模型侧仍按 `image_for_model` 注入（不要为了 UI 关掉图片）。守护：`smoke/agent/ToolEventSanitizeSmoke` + `aiagent/browser-java/src/test/.../BrowserClickSemanticsSelfTest`
- `word` 技能（`.docx` 读/写/改，独立模块 `aiagent/word-java`）：**修改走"跨 run 最小重建"保格式**——Word 会把一段文字切成任意多个 run；只改命中区间的 run 文本、不新建不删 run，字体/加粗/字号才留得住。核心不变量：**`setRunText` 必须与 `XWPFRun.text()` 互逆**——`setText(v,0)` 只覆盖 `<w:t>[0]` 而 `text()` 拼接**全部** `<w:t>`，不删多余 `<w:t>` 就会死循环（实测 118K 字符/988MB）并让整段重写复制内容。"能否安全编辑"由**反编译 POI 5.5.1 `text()` 逐元素核对**得出：`br`/`cr`(→`
`)、`tab`/`ptab`(→`	`)、`noBreakHyphen`(→U+2011)、`delText`、`footnoteReference`、`fldChar`、`ruby`、以及 rPr 里的 **`caps`/`smallCaps`**（5.x 起 `text()` 会转大写）都**破坏互逆** → 跳过并**指名元素**上报；而 `lastRenderedPageBreak`/`softHyphen`/`drawing`/`sym`/`pict` 实测**惰性、可安全编辑**（别一并拉黑——Word 每个分页处都插 `lastRenderedPageBreak`，误判会让大量段落无谓地不可编辑）。`mode=rewrite`/`set_paragraph`/`set_table_cell` 走**整段重写**路径，命中有害 run 时**拒绝执行并报错**——它**不是**"必成"的逃生舱：曾把模型文本写进域代码区、清空域结果，还报 `ok=true`；含 `w:sdt` 的段落同理（`getRuns()` 看不见它但 `getText()` 会带出来）。三处硬约束：① 标题必须**三重设定**（`pStyle` + `outlineLvl` + 直接格式），因为 `new XWPFDocument()` 没有 styles part，只写 `pStyle` 是悬空引用、Word 里会退化成正文；② `.docx` 白名单，`.doc` 明确报错要求另存；③ **userId 必须三通道读**（args → systemArgs → sessionId），执行器把 userId 放 systemArgs，只读 args 会让多用户文档目录静默塌缩到 `default`。排版默认值：`layout="chinese"`（默认）→ 一级标题居中 + 正文段落首行缩进 2 字符（直接格式写 pPr：`w:jc` + `w:ind firstLineChars=200/firstLine=420`，新建文档无 styles part 依然生效）；`layout="plain"` 不加对齐/缩进（标题三重设定、列表符号等既有格式化不变；适用英文文档/诗歌/在他人文档插段）；XML 配默认、单次调用可覆盖；列表与表格单元格不缩进。既有部署升级后 create/append/insert_paragraph 的输出会开始带排版——要关就在 fileAgent_config.xml 的 word skill 上配 `layout="plain"`

**记忆体系（三层）:**
- `TLAiContext` — 会话对话记录（短期连续性），`getContextHistory()` 直接加载全部 messages
- `LongTermMemory` — 跨会话知识碎片（JSONL文件 / 数据库），通过 `recallAgentMemory()` + 文本匹配召回
- `ShortTermMemory` — 进程内 TTL 缓存，正常聊天流不使用，留给外部 skill 模块
- 记忆召回走 AOP：`beforeMsgTable` 拦截 chat action → `recallAgentMemory` → 结果注入 `msg.systemArgs["beforeResult"]` → `doChat` 读取后拼成 system 消息
- **记忆召回不注入当前会话的 chat_history**：`recallAgentMemory` 的"最近记忆"路径（空 query）返回的是用户最近 50 条，长期会话里几乎全是**本会话上一轮**的"用户消息 → 回答"；重复注入会把它变成一条 system 记忆，模型可能把上一轮用户指令当成当前任务（2026-11-14 会话 11 轮：上一轮"图例错位"记忆 + 6 条旧门禁提示，模型答非所问）。现在按 `entry.metadata.sessionId` 过滤当前会话 chat_history（DB 从 `session_id` 列补，文件版 store 时补），保留当前会话 `session_summary` 与别的会话记忆；记忆 prompt 明确"仅参考、不是当前指令，当前任务只以最后一条 user 消息为准"；master 对"任务清单/进度/为什么没完成/结果是否受影响"类过程问题要求直接回答、不新建任务/不执行旧任务。守护：`smoke/agent/MemoryRecallSessionFilterSmoke`

**数据库记忆模块 (`TLDatabaseMemoryModule`):**
- 继承 `TLBaseMemory`，实现 5 个抽象方法：`store/retrieve/search/delete/clearAll`
- **关键模式**: 必须通过 `DB_GETTABLE` 获取 table 引用，再直接发消息给 table 模块。`TLDatabase` 不代理表操作！
  ```java
  // 正确: 先拿引用再发消息
  TLMsg getMsg = createMsg().setAction(DB_GETTABLE).setParam(DB_P_TABLENAME, "aiMemory");
  TLMsg result = putMsg(DEFAULTDATABASE, getMsg);
  TLBaseModule table = (TLBaseModule) result.getParam(INSTANCE);
  putMsg(table, insertMsg);  // 直接发给table
  ```
- 表需设 `CHARSET=utf8mb4`，否则 emoji 等 4 字节 UTF-8 字符插入失败

**AI Agent 配置参数:**
- `defaultMemoryStore` — 指定长期记忆模块名（默认 `"longTermMemory"`），切换数据库版设为 `"dbMemory"`
- `defaultSystemMessage` — aiContext 的 system prompt，通过 `<modulesParams>` 传入
  ```xml
  <modulesParams>
      <module name="aiContext" defaultSystemMessage="你是一个有用的AI助手..."/>
  </modulesParams>
  ```
- **数据库用户模块（2026-11-16）**：独立模块 `aiagent/user-java`（`TLUserModule`，工厂名 `dbUserModule`），
  表 `ai_users`（`database_config.xml`），字段含登录名/昵称/PBKDF2 密码/多角色/group/一句话介绍/邮箱/头像/状态/最后登录时间与 IP。
  webui `/api/login|register|profile|profileUpdate|changePassword|avatar|users*|registerSettings*` 经消息调用；
  管理页「用户管理」仅 `adminRoles`（默认 admin）可见可用；禁用用户下一次请求 401（显式 `found=false/status=0` 才判禁用，
  查询故障按在线处理，避免全局登出）。
  `getUserInfo` 采用 userid/role/group 契约，供 `TLAppAuthModule` 使用；未知/禁用/待审核回落 `defaultRole`（guest）。
  **注册管理（2026-11-19）**：管理页新增「📝 注册管理」（`#registrationView`，仅 admin 可见）：可开关自助注册、开关「注册后需管理员审核」、
  开关「登录/注册需要验证码」，并列出待审核用户（通过→status=1 / 拒绝→status=0，复用 `/api/usersUpdate`）。
  用户状态三态：`0=禁用 / 1=启用 / 2=待审核`；登录 `status=2` 返回 `errorCode=pending_approval`（文案「账号正在等待管理员审核」，
  与 `disabled` 区分），`status!=1` 一律不能登录/无管理员权限；`updateUser` 归一化保留三态（未传 status 时保留原值，
  编辑待审核用户不会被静默启用），管理端 `createUser` 仍只产生 0/1。
  注册/登录三个开关（`allowRegister`/`registerNeedApproval`/`loginCaptcha`）持久化到 DB 新表 `ai_settings`
  （`setting_key/setting_value/updated_at/updated_by`，SQLite/MySQL 双声明，老库靠 `CREATE TABLE IF NOT EXISTS` 自动建表）；
  `TLUserModule` 用 `BeanTable`（`TLDatabase.getBeanTable(settingsTableName, "setting_key", this)`）
  读写：`getAll` 读、`replace` 幂等 upsert；表缺失/DB 不可用时**软失败**（模块照常启动、用模块参数
  `allowSelfRegister`/`registerNeedApproval`/`loginCaptcha` 兜底、管理页 `available=false` 禁保存）。
  DB 有记录时覆盖模块参数（配置文件只是无记录时的默认值）。设置表名参数 `settingsTableName`（默认 `aiSettings`）。
  action：`getRegisterPolicy`（匿名只读，供 `/api/session` 返回登录页策略）、`getRegisterSettings`/`setRegisterSettings`（管理员，
  后者 `synchronized` + 审计；三项均可部分更新）。`/api/session` 无论是否登录都返回
  `allowRegister`/`registerNeedApproval`/`loginCaptcha`；
  注册成功响应带 `pendingApproval`/`status`，前端据此不自动登录并提示等待审核。
  表为空时按 `bootstrapUsers` 种子初始化（demo 含 admin/tianlong/console_user/dongq/xiaoming，等价旧配置迁移）；
  表非空后忽略配置。删除用户只删 `ai_users` 记录，不清理其 workspace/data/会话（数据保留，需人工清理）。
  控制台 `-u` 保持免密但校验用户存在与启用；**校验失败不退出应用**（两种模式一致）：打印错误后进入
  「未登录」外壳提示符，可 `/login <用户ID>` 重新登录（同一用户保留当前 sessionId，换用户开新会话并清断点/附件）
  或 `/shutdown` 停止整个应用。`/quit`、`/exit` 只离开对话控制台（Web/Agent/定时与后台任务照常运行，回到未登录提示符）；
  `/shutdown` 走 `moduleFactory.shutdown(0)` 停止全部（运行中也可强制停止）；stdin EOF：控制台模式等同 `/shutdown`，
  Web 模式只退出控制台、Web 继续运行（避免 headless 启动 stdin=/dev/null 误杀 Web）。
  **配置注意**：实例必须直接写 `classfile`，
  不能写 `sameClassAs="dbUserModule"`（与类注册表同名会被 include 合并覆盖，解析回自身导致类名为空）；实例配 `necessary="yes"`。
  WebUI 已启用 CSRF 防护：**会话一旦发放 token（登录后 `/api/login` 或 `/api/session` 返回），所有状态变更
  web action 必须携带匹配的 `X-CSRF-Token`**——包括无 Origin/Referer 的 curl/脚本/顶层 GET 导航
  （跨站 GET 不带 Origin 且 `SameSite=Lax` 仍带 Cookie，只校验来源头会被绕过）；脚本登录后必须从响应读取 token
  并在后续请求带上。Origin/Referer 存在时另须同源（"null" 拒绝）；无来源头仅在 token 通过（或尚未发放 token，
  如首次登录）时放行。登录成功会轮换 token（旧 token 失效）。Jetty 会话 Cookie 显式 `HttpOnly; SameSite=Lax`
  （`sessionSameSite`/`sessionHttpOnly` 可配；配 `None` 但 connector 不含 https 时 WARN 并回退 `Lax`，
  浏览器拒绝无 Secure 的 SameSite=None）。DemoUserModule 已删除，aitest 不再声明用户模块。
  守护：`TLUserModuleSelfTest`（106 项，`aiagent/user-java/src/test`）、`smoke/user_manage_check.py`（61 项）、
  `smoke/chat_auth_check.py`（30 项）、`smoke/manage_page_check.py`（42 项回归）。
- **登录/注册验证码（2026-11-19）**：webui 登录页 4 位数字图片验证码，`TLCaptcha`（webui 模块内）用 JDK 自绘
  （`BufferedImage`/`Graphics2D`/`ImageIO`，零第三方依赖）。**不要复用 servletutils 的 jcaptcha `CapchaHelper`**
  （实际出图是词典单词、按 sessionId 存 key、API 老旧），也不要引入 Kaptcha/Hutool 等库——项目不 shade，
  加依赖必须同步 `aistart_args.txt` + 5 个 bat 的真实 classpath，成本大于收益。
  `/api/captcha` 匿名取图（二进制 PNG，无 clientType；`loginCaptcha=false` 时 404）；`/api/login` 与 `/api/register`
  都校验，失败 400 + `captcha_required/captcha_expired/captcha_invalid`，失败即作废（前端换图）。
  验证码存 HttpSession（`tlweb_captcha_code/expire`），默认 TTL 300s；`/api/session` 返回 `loginCaptcha`。
  **注册成功后写 60s 一次性 `tlweb_captcha_login_pass`**：注册时验证码已消费，紧随的自动登录凭该 pass 通过，不再要求验证码。
  **验证码开关在用户模块 `ai_settings`（`loginCaptcha`）**：管理页「注册管理」勾选/取消即时生效（无需重启）；
  配置默认值在 `dbUserModule` 的 `loginCaptcha` 参数（默认 true），DB 有记录时优先。webui 模块只有 `captchaTtlSeconds`
  （默认 300，30-1800）。关闭时前端隐藏整行、后端不校验、`/api/captcha` 404；`TLWebChatModule` 每次请求经
  `getRegisterPolicy` 向用户模块取当前开关。
  前端：登录/注册共用验证码行，点击图换图、空码不发请求、失败后换图并清空输入；`refreshCaptcha` 有 50ms 合并防并发错位。
  已知边界：验证码是 session 级单码，同 session 多标签后刷新会顶掉旧图；分布式部署应改用 Redis（当前单实例）。
  守护：`TLCaptchaSelfTest`（15 项）、`smoke/chat_auth_check.py`（30 项，含开关开/关路径）。
- **用户 workspace 与路径审批（2026-11-07）**：agent 生成文件默认落 `workspace/{userId}`（系统属性 `tlobject.workspaceRoot` 可覆盖）；
  `file_operation`/`word` 的 `workspaceMode`、`code_execution`/`script_execution` 的 `workspaceMode`、
  MCP 的 `userWorkspaceScoped` 默认关闭，demo 配置已开启。`TLToolExecutor` 的 `pathGuard` 在执行前判定：
  自己的 workspace/data 放行、跨用户直接拒绝、workspace 外通过 `path_access` 强制审批（支持仅本次/本轮/本会话）；
  `code_execution`/`script_execution` 每次执行审批。主 agent 提示词用 `{{userWorkspace}}`，禁止从历史记忆推断输出路径。
  守护：`TLUserWorkspaceSelfTest`、`TLToolExecutorPathGuardSelfTest`、`TLApprovalModuleForcedSelfTest`、
  `TLMcpAgentPathScopeSelfTest`、`FileWorkspaceModeSelfTest`、`WorkspaceModeExecutionSelfTest`、
  `WordWorkspaceModeSelfTest`、`TLAiAgentVarSelfTest`、`/test workspaceIsolation`。
- **上传件归属（2026-11-09）**：用户上传件（webui `/api/upload`、控制台 `/img` 导入）统一落
  `workspace/{userId}/uploads`；`data/{uid}/uploads` 仅作旧数据位置，启动时由 `TLUploadMigration`
  幂等迁移（目标同名不覆盖，失败保留源）。**迁移不能只放 `TLAttachmentStore.runStartMsg`**——它
  是懒加载模块（首次附件操作才实例化），已在 `TLAiAgent.runStartMsg` 调 `migrateOnce()`。
  旧会话里的绝对路径按文件名回退到新位置。
  上传件不再参与 TTL/LRU 清理——那套只作用于 `data/{uid}/attachments` 的系统自动附件。
  守护：`TLAttachmentStoreUploadsSelfTest`、`TLWebChatModuleUploadsSelfTest`、
  `TLChatConsoleUploadsSelfTest`、`ViewImagePathSelfTest`。

**HITL 审批 (`approvalGate`，全局单例):**
- 规则键是 **LLM 函数名**（`file_operation:delete`），不是模块名（`fileOperationSkill`）——审批门禁按 `ToolTask.functionName` 匹配
- 启用方式：**共享工具执行器**（TLToolExecutor 为工厂单例）自身配置 `toolExecutor_config.xml` 的 `<approvalModule value="approvalGate"/>`，全应用统一启用；规则按工具名匹配——危险工具（file_operation/code_execution）只在子 agent（fileAgent/codeAgent）上，天然审批下沉，master 无危险工具打不中规则
- 会话/用户隔离由审批请求消息携带的 `sessionId`/`userId` 保证（拒绝记忆按会话键）；执行器其余运行态按 `executionId` 隔离
- **拒绝是结构化标志**：`doChat` 返回 `rejected`/`rejectReason` → `ToolExecutor` 识别子模块拒绝 → 父 agent 回滚 + 停止循环（不靠 LLM 读文案）
- **会话级拒绝记忆**：同会话同参数被拒后自动拒绝，不再重复弹框
- 审批框经 `msgBus` topic `approvalEvent` 发布，控制台 `registBus` 订阅渲染（零直接依赖）；注意 `TLMsgBus.onBus` 路由后必须清 `msg.destination` 防二次路由
- **等待策略（阶段二，2026-10-17）**：`approvalTimeoutMs=0` = 一直等（默认）——**有人收**（控制台在跑 / webui 该用户 SSE 在线，判据 = `publishApprovalEvent` 的 ack）→ 阻塞等，回来批了原线程继续；**没人收** → 审查人返回 pending 不阻塞，上层落 `pending_approval` 断点（轮次带 roundId，**跳过 chatFinished**——否则断点被覆盖成 completed 查不到）+ 断点由登录重放接续；消息箱告知发生在退出登录与超期失效时。退出登录 = defer 转断点（非拒绝；`APPROVAL_DEFER` 取消创建定时器、保持 PENDING）；deferred 审批由 `approvalPendingTtlMs`（默认 24h）兜底失效。**待批断点黏住**：`findIncomplete`/`loadSession` 按"最新开态 pending_approval 轮"作答，其后任何新轮次写入（后台任务注入等）都不会把它藏起来
- **决策写回与续跑**：resume 时 Agent 向 gate 查 `decision`（approved/rejected/pending/lost）→ `approved` 重执行被审工具并带**免复批标志**（`AI_P_APPROVAL_DECISION="approved"` 走系统参数，仅单任务批次生效，LLM 参数无法伪造）；webui 批准/拒绝 deferred 审批后前端自动续跑（流式 resume，继承断点轮 roundId 收尾）；控制台提示 `/resume`；进程重启后审批内存态丢失 → resume 如实报"审批已失效"不假装续跑
- **恢复轮的落盘前缀**：resume 沿用断点轮 roundId（用于收口 pending/checkpoint），而同 roundId 写入是 last-wins（文件版追加覆盖 / DB 版删后插）——agent 落盘必须带 `stateRoundMessages`（loadSession 返回的状态轮已有消息块）作为前缀，只写恢复后的新增量会把原轮 messages 覆盖丢。守护：`/test checkpointResumeHistory`
- **webui 待批会话拦新消息**：「批准 / 拒绝 / 放弃后才能继续」，放弃 = 撤销审批 + 丢弃断点（不记拒绝记忆）
- **普通断点也可永久丢弃（2026-11-16）**：非待批的意外中断 checkpoint 在 web 断点弹框可点「丢弃断点」（`discardCheckpoint` → SessionManager `discardOpenRound`，把开态轮改为 completed、保留会话历史），控制台对应 `/discard`；「忽略」仅本次登录有效，下次登录仍会提示。`discardPendingRound` 保留为别名（待批优先，黏住语义不变）
- **已知行为（终审 2026-10-17）**：同一轮 LLM 发出多个受审工具调用时——**在线（阻塞）场景**前端审批弹框**排队逐个展示**（2026-10-17 事故修复：此前后到事件直接覆盖，只会看到一个框、其余工具无限等 → 会话 40 分钟 thinking；同会话多审批的自动续跑会等最后一单处理完再触发）；**离线转断点**仍只保存最后一个 `pendingToolCall`（`pickPendingToolCall`），恢复只重执行那一个；审批超期只改 gate 状态、**不关闭会话轮次**——黏住语义下过期待批断点会持续压过更新的普通断点，直到被 resume（按 lost 收口）或放弃
- **配置约定**：带审批的技能 `timeout` 必须配 0（审批等待算在工具执行时间内，工具超时会把"一直等"打断成"审批被中断"——2026-10-14 弹框卡死同族故障）；执行器 `staleStateTimeoutMs` 只清理**等待线程已消失**的泄漏状态，跳过正等审批的执行（`approvalWaiters` 守卫）与调用线程仍存活的长任务（2026-10-22 委派 codingAgent 601s 被按年龄误杀事故；单次工具时长上限用 `executionTimeoutMs`）；执行器门禁 fail-closed（审批模块配置了但不可用 → 拒绝执行）
- **已知边界**：子 agent 委托路径的 pending 不上浮（fileAgent/codeAgent `enableCheckpoint=false` + `executeAsTool` 只转发输出文本）→ 委托类危险工具的"离线转断点"不成立，现阶段只在 master 自有 gated 工具上生效

**单元测试 (`/test` 命令):**
- 测试目标为独立 `aiagent` 实例（与主控 aiagent_master 隔离，不污染 /sessions），Mock Provider 驱动 40 场景
- chat 应用接入：`conf/demo/aiagent/` 的 `aiagent_config.xml` + `agentTestModule_config.xml`；两 demo 配置目录（aiagent/aitest）禁止互相引用，只共享 `conf/tlobject/`

### Configuration

Modules are configured via XML files (parsed by `TLModuleConfig`). The config file pattern is `{moduleName}_config.xml`. Key XML elements:

- `<params>` — Module parameters
- `<modules>` — Sub-module definitions with `classfile`, `singleton`, `onfactory`, etc.
- `<modulesParams>` — Per-module parameter overrides（一个模块多个参数，如 `<module name="aiContext" defaultSystemMessage="..."/>`）
- `<paramsModules>` — Per-parameter multi-module assignment（一个参数多个模块，如 `<param name="loglevel" value="debug" modules="aiagent;openAiProvider"/>`）
- `<initMsg>` / `<startMsg>` — Message sequences run at init/start
- `<msgTable>` — Message routing table keyed by `msgid`
- `<beforeMsgTable>` / `<afterMsgTable>` — Pre/post action hooks keyed by action name
- `<include>` — Import other config files

### Message Flow

1. `putMsg("moduleName", msg)` → factory resolves the module → `getMsg(fromWho, msg)` on target
2. `getMsg()` checks destination routing → runs `beforeMsgTable` hooks → `runAction()` via reflection (action name → method) → `afterMsgTable` hooks → `nextMsg` chain

### Entry Points

Applications start via `TLAppStartUp.main()` with CLI args:
- `-d` config directory path
- `-m` factory config file name (default: `moduleFactory_config.xml`)
- `-f` app config file name
- `-n` app name

Or via `TLObjectFactory.getInstance()` + `startFactory()` + `boot()` in code.

### log4j2 配置要点

- 配置文件在 `conf/` 目录下，需在 `main()` 启动前设置系统属性：
  ```java
  System.setProperty("log4j.configurationFile", configPath + "log4j2.xml");
  ```
- `<configuration status="...">` — `status` 控制 log4j2 **自身**的启动日志级别（StatusConsoleListener），设 `"error"` 可消掉控制台噪音
- Console/File appender 的 `ThresholdFilter` 放在 `<Filters>` 内时做独占式分级（每文件只收恰好一个级别），直接放 appender 下做累计式阈值
