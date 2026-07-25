# AI Agent 框架总结与改进建议

> 分析日期：2026-07-21

---

## 一、框架特点总结

### 1. 架构特点

**消息驱动的统一对象模型**
- 所有组件（Agent、Provider、Skill、Memory、Guard）继承 `TLBaseModule`，通过 `TLMsg` 消息通信
- 无直接方法调用，完全解耦，模块可独立开发、测试、替换
- `TLObjectFactory` 统一管理生命周期（创建、初始化、启动、销毁）

**XML 声明式配置 + AOP 拦截**
- 模块定义、路由表（`msgTable`）、前置/后置拦截器（`beforeMsgTable`/`afterMsgTable`）全部 XML 配置
- `beforeMsgTable` 实现记忆召回注入（AOP 织入），`afterMsgTable` 实现记忆持久化
- `msgTools` 将内部消息调用暴露为 LLM 可见的 function definitions
- 反射动作分发：`checkMsgAction()` → `invokeAction()` 自动映射消息→方法

**Provider 可插拔**
- 抽象 `TLLlmProvider` 定义统一接口，支持 OpenAI 兼容系（OpenAI/DeepSeek/Qwen/GLM）+ Anthropic Claude
- 运行时切换 Provider，无需重启

### 2. 功能特点

**完整的 Agent 循环**
- `doChat()` 统一实现流式/非流式，预处理（记忆召回+上下文）和后处理（保存+持久化）完全共享
- Tool-call 多轮迭代（`maxToolCallIterations`，默认 10 轮），支持 LLM 自动选择调用工具 → 结果回传 → 继续推理
- 内置 `request_clarification` 工具，LLM 需要澄清时可主动请求用户确认

**三层记忆体系**
- `TLAiContext`：会话对话记录（短期连续性），按 session 隔离，自动修剪（`maxHistoryTurns`）
- `ShortTermMemory`：进程内 TTL 缓存，LRU 淘汰，适合临时数据
- `LongTermMemory`：JSONL 文件持久化 + 数据库版（MySQL），支持 embedding 向量语义搜索

**主控/子 Agent 两级编排**
- Agent 可拥有子 Agent（regular/mcp/group），通过 `delegate_to_{name}` 暴露为工具
- `TLAgentGroup`：顺序/并行调度 + 可选的 supervisor 审核+重跑机制
- 子 Agent 工具定义通过 `AGENT_GETTOOLDEFS` 统一协议获取，master 零特判

**MCP 协议集成**
- `TLMcpAgent` 作为轻量桥接器（无 LLM/上下文/记忆），连接 MCP Server
- stdio（本地子进程）+ SSE（远程 HTTP）双 transport
- 自动 `tools/list` → function definitions，`tools/call` 转发
- 工具白名单过滤

**流式支持**
- 同步流式（`TLStreamCallback` + `CountDownLatch` 等待）
- 异步流式（`AGENT_CHATSTREAM` 直接路由回调给调用者）
- 流式 tool-call 自动切换到非流式循环继续执行

**四层中断体系**
1. 软取消：`cancelFlags` AtomicBoolean，协作式检查
2. HTTP 取消：OkHttp dispatcher 取消所有请求
3. 进程强杀：`TLProcessRegistry` 按线程 kill 子进程
4. 级联停止：`TLAgentMonitor` stopByRoot 递归停止整棵 Agent 树

**安全护栏**
- `TLOutputGuard`：金额限制 + 禁用词替换 + 最大长度截断 + 可选的 LLM-as-Judge 审查（幻觉/不相关/有害）
- Skill 路径安全（文件操作限制 rootPath，脚本执行限制 allowedScriptDir）
- 输入校验：`executeToolCall()` 调 skill 前先 `validate()`

**运行时热管理**
- `/install -s|-a|-bs` 和 `/uninstall` 命令，运行时增删 Skill/Agent
- Skill md 自动发现加载（YAML frontmatter → 描述，正文 → system prompt）
- 工具定义缓存 + 失效契约（7 个失效点触发 `invalidateToolDefs`）

**会话管理**
- Session 隔离（独立 sessionId，并发支持）
- 断点续传：L1 会话保存 + L2 mid-loop 断点
- 模板变量：`{{date}}`、`{{sessionId}}`、`{{userId}}` 等自动替换

**可观测性**
- LLM 调用 trace（`.trace` 文件，含请求/响应/耗时）
- Token 用量追踪（按 session 累计，每次 chat 返回本次+累计）
- `TLMsgFlowAnalyzer`：读取 checkpoint JSONL 生成 SVG 时序图

### 3. 核心模块清单（33 个 Java 文件）

| 层级 | 模块 | 说明 |
|------|------|------|
| 编排 | `TLAiAgent` | 主控编排器，chat() 循环核心 |
| 上下文 | `TLAiContext` | 对话历史管理，按 session 隔离 |
| Provider | `TLLlmProvider` | 抽象 LLM Provider（OkHttp + 重试 + trace） |
| | `TLOpenAiProvider` | OpenAI/DeepSeek/Qwen/GLM 兼容 |
| | `TLClaudeProvider` | Anthropic Claude Messages API |
| Skill | `TLBaseSkill` | 抽象 Skill 基类（md 自动加载） |
| | `TLHttpRequestSkill` | HTTP 请求 |
| | `TLFileOperationSkill` | 文件操作（路径安全） |
| | `TLCodeExecutionSkill` | 代码执行（Python/JS） |
| | `TLScriptExecutionSkill` | 脚本执行（外部脚本文件） |
| 记忆 | `TLBaseMemory` | 抽象记忆基类 |
| | `TLShortTermMemoryModule` | 短期记忆（HashMap + TTL + LRU） |
| | `TLLongTermMemoryModule` | 长期记忆（JSONL 持久化 + 向量搜索） |
| | `TLDatabaseMemoryModule` | 数据库记忆（MySQL） |
| 编排 | `TLAgentGroup` | 组 Agent（sequential/parallel + supervisor） |
| | `TLAgentMonitor` | 运行时监控 + 级联停止 |
| MCP | `TLMcpAgent` | MCP 桥接器 |
| | `McpTransport` | MCP 传输接口 |
| | `StdioMcpTransport` | stdio 传输（本地子进程） |
| | `SseMcpTransport` | SSE/HTTP 传输（远程） |
| | `McpJsonRpc` | JSON-RPC 2.0 工具类 |
| | `McpTool` | MCP 工具 POJO |
| 安全 | `TLOutputGuard` | 输出护栏（规则 + LLM 审查） |
| | `TLProcessRegistry` | 子进程注册表（中断时强杀） |
| 工具 | `TLStreamCallback` | 流式回调模块 |
| | `TLChatConsole` | 交互式命令行界面（JLine） |
| | `TLMdFileLoader` | Markdown + Frontmatter 加载器 |
| | `TLLlmTrace` | LLM 调用追踪记录 |
| POJO | `TLConversationHistory` | 对话历史 |
| | `TLToolCall` | 工具调用 |
| | `TLFunctionDefinition` | 函数定义 |
| | `TLMemoryEntry` | 记忆条目 |
| | `TLAiAgentParamString` | 常量接口 |

---

## 二、已完成的改进项

| 状态 | 项目 | 日期 | 说明 |
|------|------|------|------|
| ✅ | MCP Client | 2026-07-07 | stdio + SSE，自动工具发现 |
| ✅ | Structured Output | 2026-07-13 | OpenAI json_object / json_schema |
| ✅ | Token 追踪 | 2026-07-13 | 按 session 累计 |
| ✅ | Group Agent | 2026-07-09 | sequential / parallel + supervisor |
| ✅ | Output Guard | 2026-07-09 | 规则 + LLM-as-Judge |
| ✅ | 输入校验 | 2026-07-09 | executeToolCall 前 validate |
| ✅ | Skill 热加载 | 2026-07-21 | 运行时 install/uninstall |
| ✅ | Session 断点续传 | - | L1 + L2 checkpoint |
| ✅ | Embedding 记忆搜索 | - | 余弦相似度 |
| ✅ | Agent 可中断 | - | 四层中断 + ESC 快捷键 |
| ✅ | md 自动加载 | - | Skill/Agent 描述自动发现 |

---

## 三、进一步改进建议（结合 2026 年 Agent 技术趋势）

### 🔴 高优先级（第 1 梯队）

#### 1. 子 Agent 流式透传 ⭐

**问题**：当前主控 delegate 给子 Agent 后，流式输出无法实时传给用户（只能等完成后拿结果），UX 影响大。

**技术方案**：
- 子 Agent 流式模式下，回调链从 子 Agent → 主控 → 用户 逐级透传
- `TLStreamCallback` 支持链式转发模式（不存 buffer，直接 forward）
- 主控 `doStreamCall` 检测到子 Agent delegate 时，转发流式回调而非等待

#### 2. ReAct / 思考链可视化

**问题**：Agent 的推理过程（thought → action → observation → ...）对用户不透明。

**技术方案**：
- 在 system prompt 中引导 LLM 输出推理过程
- `doChat()` 解析响应中的思考段，单独存储为 `reasoning` 类型
- 控制台 / UI 层支持折叠/展开思考过程
- 利用 LLM 原生 reasoning（DeepSeek-R1 `reasoning_content`、Claude thinking）

#### 3. 错误自修复循环（Self-Correction）

**问题**：Skill 执行失败时只是把错误返回给 LLM，缺乏结构化的重试+反思机制。

**技术方案**：
- Skill 执行失败后自动注入反思提示："上一步失败，原因：{error}，请分析原因并调整参数重试"
- 可配置 maxRetries（默认 3），超限后终止并返回友好错误
- 对 HTTP 超时、文件不存在等常见错误预设修复建议

#### 4. Human-in-the-Loop 增强

**问题**：当前只有 `request_clarification` 基础支持。

**技术方案**：
- 增加 `require_approval` 工具：高风险操作前（文件删除、HTTP POST、代码执行）请求用户批准
- 审批状态机：pending → approved/rejected → executed
- 支持超时自动拒绝（安全优先）
- 审批可带修改建议（用户修正参数后批准）

---

### 🟡 中优先级（第 2 梯队）

#### 5. 图工作流编排（DAG Workflow）

**问题**：Group 只支持顺序/并行，缺乏条件分支、汇聚等复杂编排。

**技术方案**：
- 新增 `TLAgentWorkflow` 模块，接受 DAG 配置
- 节点类型：Agent 调用、条件判断、并行扇出、汇聚等待、循环
- 执行引擎：拓扑排序 → 并行调度就绪节点
- 可与 `TLAgentGroup` 互补

#### 6. Agent 评测体系（Evals）

**问题**：没有自动化评测，改代码靠人工验证。

**技术方案**：
- 评测用例格式：`{input, expectedOutput/expectedToolCalls, judgePrompt}`
- 三种评测维度：精确匹配、LLM-as-Judge 评分、约束检查
- 评测报告：通过率、平均迭代次数、token 用量

#### 7. 提示词缓存（Prompt Caching）

**问题**：每次 chat 都发送完整 system prompt + 工具定义，浪费 token 和延迟。

**技术方案**：
- 利用 Anthropic prompt caching 和 OpenAI automatic caching
- system message + 工具定义标记为可缓存段
- Provider 层自动识别并设置 cache breakpoints
- 统计缓存命中率

#### 8. A2A 协议支持（Agent-to-Agent）

**问题**：框架内的 Agent 无法与外部 Agent 框架（LangChain、AutoGen 等）互操作。

**技术方案**：
- 新增 `TLA2aAgent`，支持 Google A2A 协议的 Agent Card 发现
- 远程 A2A Agent 映射为本地子 Agent
- 同时可将本地 Agent 暴露为 A2A Server

#### 9. RAG 增强记忆检索

**问题**：记忆召回使用文本包含匹配或简单余弦相似度，精度有限。

**技术方案**：
- 记忆存储时自动切分 + embedding（可配置分块策略）
- 召回：embedding 检索 top-K + 重排序（reranker）
- 混合检索：关键词 BM25 + 向量相似度融合
- 新增 `TLMemoryRetriever` 模块封装检索管道

---

### 🟢 低优先级 / 前瞻性（第 3/4 梯队）

#### 10. Computer Use / GUI Agent

Claude Computer Use 和 OpenAI Operator 代表了 Agent 与 GUI 交互的趋势。

**技术方案**：新增 `TLBrowserSkill`（Playwright/Selenium）+ `TLDesktopSkill`（截图+键鼠操作），作为可选模块。

#### 11. 多模态支持（Vision）

当前纯文本，无法处理图片输入。

**技术方案**：`TLConversationHistory` 扩展 `images` 字段，Provider 层适配多模态消息格式，新增 `TLImageSkill`。

#### 12. 模型路由（Model Router）

根据任务复杂度自动选择不同模型（便宜模型处理简单任务，强模型处理复杂推理）。

**技术方案**：新增 `TLModelRouter` 模块，根据任务特征路由 + LLM 自主判断（meta-cognition）。

#### 13. 对话导出/导入

**技术方案**：支持 JSON/Markdown/纯文本导出，与 checkpoint 机制共享序列化逻辑。

#### 14. 插件市场 / Skill 生态

**技术方案**：Skill 打包规范（jar + SKILL.md + skill_config.xml），Git-based registry。

---

## 四、优先级排序总览

```
第1梯队（立即做，用户体验影响最大）：
  子Agent流式透传 + ReAct思考链 + 错误自修复 + Human-in-the-Loop

第2梯队（近期，提升工程化和可靠性）：
  DAG Workflow + Agent Evals + Prompt Caching + A2A + RAG增强

第3梯队（前瞻布局，依赖外部生态）：
  Computer Use + 多模态 + 模型路由 + 对话导出 + 插件市场
```

### 判断依据

- **第1梯队**：影响用户体验最大（流式透传消除等待感、思考链增加可信度、自修复减少失败率、人机协作安全）
- **第2梯队**：提升工程化和可靠性（复杂编排能力、质量度量、降本增效、生态互通）
- **第3梯队**：前瞻布局，依赖外部生态成熟度

---

## 五、框架技术竞争力分析

与当前主流 Agent 框架对比：

| 能力 | tlobject-aiagent | LangChain | AutoGen | CrewAI | Dify |
|------|:---:|:---:|:---:|:---:|:---:|
| 消息驱动架构 | ✅ 原生 | ❌ | ❌ | ❌ | ❌ |
| XML 声明式配置 | ✅ 原生 | ❌ | ❌ | ❌ | ❌ |
| AOP 拦截器 | ✅ 原生 | ❌ | ❌ | ❌ | ❌ |
| 多 Provider 可插拔 | ✅ | ✅ | ✅ | ✅ | ✅ |
| Tool Call | ✅ | ✅ | ✅ | ✅ | ✅ |
| MCP 协议 | ✅ | ✅ | ❌ | ❌ | ✅ |
| 流式响应 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 多 Agent 编排 | ✅ Group | ✅ | ✅ | ✅ | ✅ |
| 记忆体系 | ✅ 三层 | ✅ | ❌ | ✅ | ✅ |
| 运行时热加载 | ✅ | ❌ | ❌ | ❌ | ❌ |
| 级联中断 | ✅ 四层 | ❌ | ❌ | ❌ | ❌ |
| 会话断点续传 | ✅ | ❌ | ❌ | ❌ | ❌ |
| ReAct 思考链 | ❌ 待做 | ✅ | ❌ | ✅ | ✅ |
| Human-in-the-Loop | 🟡 基础 | ✅ | ✅ | ✅ | ✅ |
| DAG 工作流 | ❌ 待做 | ✅ LangGraph | ❌ | ❌ | ✅ |
| Agent Evals | ❌ 待做 | ✅ | ❌ | ❌ | ❌ |
| Prompt Caching | ❌ 待做 | ✅ | ❌ | ❌ | ❌ |
| A2A 协议 | ❌ 待做 | ❌ | ✅ | ❌ | ❌ |

**核心竞争力**：消息对象编程模型的架构优势——极致解耦、XML 配置驱动、AOP 拦截、运行时热管理。这些在主流框架中都是缺失或需要额外插件实现的。
