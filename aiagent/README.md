# AI Agent 智能体框架

基于 **TLObject 统一消息对象编程模型** 构建的 AI 大模型智能体（Agent）子框架。遵循消息驱动架构，所有模块通过 TLMsg 消息进行通信，由 TLObjectFactory 统一管理生命周期。

---

## 目录

1. [架构概览](#架构概览)
2. [模块结构](#模块结构)
3. [核心类说明](#核心类说明)
   - [TLAiAgentParamString — 常量接口](#tlaiagentparamstring--常量接口)
   - [TLAiAgent — 主控编排器](#tlaiagent--主控编排器)
   - [TLAiContext — 上下文管理](#tlaicontext--上下文管理)
   - [TLLlmProvider — 抽象LLM Provider](#tlllmprovider--抽象llm-provider)
   - [TLOpenAiProvider — OpenAI兼容Provider](#tlopenaiprovider--openai兼容provider)
   - [TLClaudeProvider — Claude API Provider](#tlclaudeprovider--claude-api-provider)
   - [TLBaseSkill — 抽象Skill基类](#tlbaseskill--抽象skill基类)
   - [内置Skill实现](#内置skill实现)
   - [TLBaseMemory — 抽象记忆基类](#tlbasememory--抽象记忆基类)
   - [记忆实现](#记忆实现)
4. [数据POJO](#数据pojo)
5. [消息流](#消息流)
6. [XML配置](#xml配置)
7. [使用示例](#使用示例)
8. [扩展开发](#扩展开发)
9. [新功能模块](#新功能模块)
   - [MCP 市场与工具集成](#mcp-市场与工具集成)
   - [Evals 评测体系](#evals-评测体系)
   - [单元测试 /test](#单元测试-test)
   - [HITL 人工审批](#hitl-人工审批)
   - [会话管理与断点恢复](#会话管理与断点恢复)

---

## 架构概览

```
┌──────────────────────────────────────────────────────────────────┐
│                      TLAiAgent (主控编排器)                       │
│  chat() 循环:                                                    │
│  用户输入 → 获取上下文 → 收集Skill定义 → 发送LLM                  │
│  → 解析响应:                                                      │
│     有 tool_calls → 执行对应Skill → 结果送回LLM (循环)            │
│     纯文本      → 返回给用户                                      │
│  → beforeMsgTable注入记忆 → afterMsgTable持久化记忆               │
└──────┬──────────┬───────────┬───────────┬────────────────────────┘
       │          │           │           │
       ▼          ▼           ▼           ▼
┌──────────┐ ┌────────┐ ┌──────────┐ ┌──────────────┐
│TLAiContext│ │TLLlmProvider│ │TLBaseSkill│ │TLBaseMemory  │
│(会话历史) │ │(抽象基类) │ │(抽象基类) │ │(抽象基类)    │
└──────────┘ └───┬──┬──┘ └───┬──┬───┘ └───┬──┬───────┘
                 │  │         │  │         │  │
          ┌──────┘  └──────┐  │  │    ┌────┘  └──────┐
          ▼                ▼  ▼  ▼    ▼               ▼
   ┌────────────┐ ┌────────────┐ ┌──────────┐ ┌──────────────┐
   │OpenAI      │ │Claude      │ │HTTP请求   │ │短期记忆      │
   │Provider    │ │Provider    │ │文件操作   │ │长期记忆      │
   │(OkHttp+SSE)│ │(OkHttp+SSE)│ │代码执行   │ │(文件持久化)  │
   └────────────┘ └────────────┘ └──────────┘ └──────────────┘
```

### 设计原则

- **一切皆消息**：模块间通信统一通过 `TLMsg` 消息传递，无直接方法调用
- **一切皆模块**：所有组件继承 `TLBaseModule`，享有完整的生命周期管理
- **XML驱动配置**：模块定义、路由表、拦截器全部声明式配置
- **反射动作分发**：`checkMsgAction()` → `invokeAction()` 将消息动作映射到Java方法
- **AOP拦截**：`beforeMsgTable` / `afterMsgTable` 实现横切关注点（如记忆注入）
- **Provider模式**：LLM Provider可插拔切换，支持多家厂商API

---

## 模块结构

```
aiagent/
├── pom.xml                                     # 聚合POM
├── common/                                      # tlobject-aiagent-common
│   └── src/main/java/cn/tianlong/tlobject/aiagent/
│       ├── TLAiAgentParamString.java            # 常量接口
│       ├── TLAiAgent.java                       # 主控编排器 ★
│       ├── TLAiContext.java                     # 上下文管理
│       ├── TLLlmProvider.java                   # 抽象LLM Provider
│       ├── TLBaseSkill.java                     # 抽象Skill基类
│       ├── TLBaseMemory.java                    # 抽象记忆基类
│       ├── TLConversationHistory.java           # 对话历史POJO
│       ├── TLToolCall.java                      # 工具调用POJO
│       ├── TLFunctionDefinition.java            # 函数定义POJO
│       └── TLMemoryEntry.java                   # 记忆条目POJO
├── provider-openai/                             # tlobject-aiagent-provider-openai
│   └── src/.../provider/openai/
│       └── TLOpenAiProvider.java                # OpenAI兼容Provider
├── provider-claude/                             # tlobject-aiagent-provider-claude
│   └── src/.../provider/claude/
│       └── TLClaudeProvider.java                # Claude Provider
├── skill-builtin/                               # tlobject-aiagent-skill-builtin
│   └── src/.../skill/builtin/
│       ├── TLHttpRequestSkill.java              # HTTP请求
│       ├── TLFileOperationSkill.java            # 文件操作
│       └── TLCodeExecutionSkill.java            # 代码执行
└── memory-store/                                # tlobject-aiagent-memory-store
    └── src/.../aiagent/memory/
        ├── TLShortTermMemoryModule.java         # 短期记忆
        └── TLLongTermMemoryModule.java          # 长期记忆
```

### Maven依赖

| 模块 | artifactId | 依赖 |
|------|-----------|------|
| common | `tlobject-aiagent-common` | `tlobject-core`, `gson` 2.10.1, `okhttp` 4.12.0 |
| provider-openai | `tlobject-aiagent-provider-openai` | `tlobject-aiagent-common` |
| provider-claude | `tlobject-aiagent-provider-claude` | `tlobject-aiagent-common` |
| skill-builtin | `tlobject-aiagent-skill-builtin` | `tlobject-aiagent-common` |
| memory-store | `tlobject-aiagent-memory-store` | `tlobject-aiagent-common` |

---

## 核心类说明

### TLAiAgentParamString — 常量接口

**继承**: `TLParamString`
**包**: `cn.tianlong.tlobject.aiagent`

定义AI Agent框架所有消息动作名、参数键、模块名的字符串常量。所有AI模块通过 `implements TLAiAgentParamString` 自动继承核心框架常量 + AI特定常量，避免魔法字符串。

#### 模块名常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `M_AIAGENT` | `"aiagent"` | Agent主控模块名 |
| `M_AICONTEXT` | `"aiContext"` | 上下文模块名 |
| `M_LLMPROVIDER_OPENAI` | `"openAiProvider"` | OpenAI Provider名 |
| `M_LLMPROVIDER_CLAUDE` | `"claudeProvider"` | Claude Provider名 |
| `M_SHORTTERMMEMORY` | `"shortTermMemory"` | 短期记忆模块名 |
| `M_LONGTERMMEMORY` | `"longTermMemory"` | 长期记忆模块名 |

#### Agent动作常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `AGENT_CHAT` | `"chat"` | 完整chat循环（含tool-call迭代） |
| `AGENT_CHATSTREAM` | `"chatStream"` | 流式chat |
| `AGENT_REGISTERSKILL` | `"registerSkill"` | 注册Skill |
| `AGENT_UNREGISTERSKILL` | `"unregisterSkill"` | 注销Skill |
| `AGENT_LISTSKILLS` | `"listSkills"` | 列出所有Skill |
| `AGENT_GETCONTEXT` | `"getAgentContext"` | 获取会话上下文 |
| `AGENT_CLEARCONTEXT` | `"clearAgentContext"` | 清除会话上下文 |
| `AGENT_SAVEMEMORY` | `"saveAgentMemory"` | 保存记忆 |
| `AGENT_RECALLMEMORY` | `"recallAgentMemory"` | 召回记忆 |
| `AGENT_SETSYSTEMMSG` | `"setSystemMsg"` | 设置系统提示词 |
| `AGENT_SETPROVIDER` | `"setLlmProvider"` | 切换LLM Provider |

#### LLM Provider动作常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `LLM_COMPLETION` | `"completion"` | 非流式completion请求 |
| `LLM_COMPLETIONSTREAM` | `"completionStream"` | 流式completion请求 |
| `LLM_LISTMODELS` | `"listModels"` | 列出可用模型 |
| `LLM_CANCEL` | `"cancelLlm"` | 取消进行中的请求 |

#### Context动作常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `CONTEXT_ADDMESSAGE` | `"addMessage"` | 追加消息到历史 |
| `CONTEXT_GETMESSAGES` | `"getMessages"` | 获取消息历史 |
| `CONTEXT_CLEAR` | `"clearContext"` | 清除会话 |
| `CONTEXT_GETTURNCOUNT` | `"getTurnCount"` | 获取轮次计数 |
| `CONTEXT_SETSYSTEM` | `"setSystem"` | 设置系统消息 |

#### Skill动作常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `SKILL_GETINFO` | `"getSkillInfo"` | 获取Skill元信息 |
| `SKILL_EXECUTE` | `"skillExecute"` | 执行Skill |
| `SKILL_VALIDATE` | `"skillValidate"` | 校验输入参数 |

#### Memory动作常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `MEMORY_STORE` | `"store"` | 存储记忆 |
| `MEMORY_RETRIEVE` | `"retrieve"` | 按键检索 |
| `MEMORY_SEARCH` | `"search"` | 文本搜索记忆 |
| `MEMORY_DELETE` | `"delete"` | 按键删除 |
| `MEMORY_CLEARALL` | `"clearAll"` | 清除所有记忆 |

#### 参数键常量（主要）

| 常量 | 键名 | 类型 | 说明 |
|------|------|------|------|
| `AI_P_SESSIONID` | `"sessionId"` | String | 会话ID，贯穿整个对话 |
| `AI_P_USERMESSAGE` | `"userMessage"` | String | 用户输入文本 |
| `AI_P_SYSTEMMESSAGE` | `"systemMessage"` | String | 系统提示词 |
| `AI_P_RESPONSE` | `"aiResponse"` | String | LLM返回的文本 |
| `AI_P_MESSAGEHISTORY` | `"messageHistory"` | List | 对话历史列表 |
| `AI_P_TOOLCALLS` | `"toolCalls"` | List | LLM返回的工具调用列表 |
| `AI_P_TOOLRESULTS` | `"toolResults"` | List | 工具执行结果列表 |
| `AI_P_FUNCTIONDEFS` | `"functionDefinitions"` | List | 传递给LLM的函数定义 |
| `AI_P_MODEL` | `"model"` | String | 模型名称 |
| `AI_P_TEMPERATURE` | `"temperature"` | double | 采样温度 |
| `AI_P_MAXTOKENS` | `"maxTokens"` | int | 最大输出token数 |
| `AI_P_SKILLNAME` | `"skillName"` | String | Skill的名称标识 |
| `AI_P_SKILLINPUT` | `"skillInput"` | Map | Skill执行的输入参数 |
| `AI_P_SKILLOUTPUT` | `"skillOutput"` | String | Skill执行的输出结果 |
| `AI_P_MEMORYKEY` | `"memoryKey"` | String | 记忆键 |
| `AI_P_MEMORYVALUE` | `"memoryValue"` | Object | 记忆值 |
| `AI_P_MEMORYQUERY` | `"memoryQuery"` | String | 记忆搜索查询 |
| `AI_P_CHUNK` | `"chunk"` | String | 流式响应的单个文本块 |
| `AI_P_STREAMDONE` | `"streamDone"` | boolean | 流式响应完成标志 |
| `AI_P_STREAMERROR` | `"streamError"` | String | 流式错误信息 |

---

### TLAiAgent — 主控编排器 ★

**继承**: `TLBaseModule`
**实现**: `TLAiAgentParamString`
**模块名**: `"aiagent"`
**类比**: `TLDataBase` (数据库模块的入口)

Agent框架的核心模块。接收用户输入，编排LLM、Skill、Context、Memory之间的消息交互，实现完整的 **用户输入 → LLM分析 → Skill调用 → 结果返回** 闭环。

#### 关键字段

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `defaultLlmProvider` | String | 配置值 | 默认LLM Provider模块名 |
| `llmProvider` | TLLlmProvider | null | 当前活跃的Provider实例 |
| `skills` | Map<String, TLBaseSkill> | ConcurrentHashMap | 已注册的Skill映射 |
| `memoryStores` | Map<String, TLBaseMemory> | ConcurrentHashMap | 已注册的记忆存储 |
| `maxToolCallIterations` | int | 10 | tool-call最大迭代次数 |
| `defaultModel` | String | `"gpt-4o"` | 默认模型 |
| `defaultTemperature` | double | 0.7 | 默认采样温度 |
| `defaultMaxTokens` | int | 4096 | 默认最大token |

#### 动作方法

| 方法 | 对应动作 | 说明 |
|------|----------|------|
| `chat(fromWho, msg)` | `AGENT_CHAT` | **核心方法**。完整chat循环，含tool-call多轮迭代 |
| `chatStream(fromWho, msg)` | `AGENT_CHATSTREAM` | 流式chat，通过回调发送文本块 |
| `registerSkill(fromWho, msg)` | `AGENT_REGISTERSKILL` | 注册Skill（支持实例或类名） |
| `unregisterSkill(fromWho, msg)` | `AGENT_UNREGISTERSKILL` | 注销Skill |
| `listSkills(fromWho, msg)` | `AGENT_LISTSKILLS` | 列出所有已注册Skill |
| `getAgentContext(fromWho, msg)` | `AGENT_GETCONTEXT` | 获取session对话历史 |
| `clearAgentContext(fromWho, msg)` | `AGENT_CLEARCONTEXT` | 清除session对话历史 |
| `saveAgentMemory(fromWho, msg)` | `AGENT_SAVEMEMORY` | 保存记忆到Memory Store |
| `recallAgentMemory(fromWho, msg)` | `AGENT_RECALLMEMORY` | 从Memory Store召回记忆 |
| `setSystemMsg(fromWho, msg)` | `AGENT_SETSYSTEMMSG` | 设置session系统提示词 |
| `setLlmProvider(fromWho, msg)` | `AGENT_SETPROVIDER` | 运行时切换LLM Provider |

#### chat() 核心算法

```
输入: TLMsg { action:"chat", sessionId, userMessage, [model], [temperature], [maxTokens] }

1. 通过TLAiContext获取历史消息列表
2. 添加新的用户消息 (Role.user)
3. 收集所有已注册Skill的FunctionDefinition
4. 循环 (最多 maxToolCallIterations 次):
   a. 发送消息列表 + 函数定义到 LLM Provider
   b. 解析LLM响应:
      - 纯文本 → 添加assistant消息，返回finalResponse，退出循环
      - 含tool_calls → 添加assistant消息(含tool_calls)
        → 逐一执行Skill获取结果
        → 添加tool结果消息
        → 回到步骤a继续
5. 保存完整历史到TLAiContext
6. afterMsgTable自动触发 → 持久化记忆

输出: TLMsg { result:true, aiResponse, sessionId, iterations }
```

#### 内部类 myConfig

解析XML自定义段：

```xml
<providers>    →  HashMap<String, HashMap<String, String>> providers
<skills>       →  HashMap<String, HashMap<String, String>> skills
<memoryStores> →  HashMap<String, HashMap<String, String>> memoryStores
```

遵循 `TLDataBase.myConfig` 的 `getHashMap(xpp, tag, subtag)` 模式。

---

### TLAiContext — 上下文管理

**继承**: `TLBaseModule`
**实现**: `TLAiAgentParamString`
**模块名**: `"aiContext"`

管理每个会话（sessionId）的对话历史记录。支持多会话并发访问和自动历史裁剪。

#### 关键字段

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `sessions` | Map<String, List> | ConcurrentHashMap | sessionId → 消息历史列表 |
| `maxHistoryTurns` | int | 50 | 最大保留轮次，超出自动裁剪 |
| `defaultSystemMessage` | String | null | 新会话的默认系统提示词 |

#### 动作方法

| 方法 | 对应动作 | 参数 | 返回值 |
|------|----------|------|--------|
| `addMessage(msg)` | `CONTEXT_ADDMESSAGE` | sessionId, entry (TLConversationHistory) 或 role+content | result:true, turnCount |
| `getMessages(msg)` | `CONTEXT_GETMESSAGES` | sessionId | messageHistory (List) |
| `clear(msg)` | `CONTEXT_CLEAR` | sessionId | result:true |
| `getTurnCount(msg)` | `CONTEXT_GETTURNCOUNT` | sessionId | turnCount (int) |
| `setSystem(msg)` | `CONTEXT_SETSYSTEM` | sessionId, systemMessage | result:true |

#### 历史裁剪策略

当 `session.size() > maxHistoryTurns` 时，保留所有system消息，从最早的非system消息开始移除，直到总轮次 ≤ maxHistoryTurns。

---

### TLLlmProvider — 抽象LLM Provider

**继承**: `TLBaseModule`
**实现**: `TLAiAgentParamString`
**类比**: `TLDBServer`（连接抽象）+ `TLHttpClient`（HTTP模式）

封装与大语言模型API的HTTP通信。提供OkHttp请求模板、认证头构建、超时配置。具体Provider（OpenAI、Claude等）实现请求构建和响应解析。

#### 关键字段

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `apiKey` | String | 配置值 | API密钥 |
| `apiBaseUrl` | String | 配置值 | API基础URL |
| `defaultModel` | String | 配置值 | 默认模型 |
| `connTimeOut` | Long | 30秒 | 连接超时 |
| `readTimeOut` | Long | 120秒 | 读取超时（LLM响应慢） |
| `writeTimeOut` | Long | 30秒 | 写入超时 |
| `okHttpClient` | OkHttpClient | 自动创建 | HTTP客户端实例 |
| `gson` | Gson | 自动创建 | JSON序列化工具 |

#### 抽象方法（子类必须实现）

| 方法 | 说明 |
|------|------|
| `completion(fromWho, msg)` | 非流式completion请求 |
| `completionStream(fromWho, msg)` | 流式completion（异步回调） |
| `listModels(fromWho, msg)` | 列出可用模型 |
| `cancel(fromWho, msg)` | 取消进行中请求 |
| `buildRequestBody(msg, messages, tools, stream)` | 构建Provider特定的请求体JSON |
| `parseResponse(responseBody, msg)` | 解析Provider特定的响应JSON |
| `getCompletionsPath()` | API端点路径 |

#### 模板方法（共享实现）

| 方法 | 说明 |
|------|------|
| `buildHttpRequest(path, jsonBody, headers)` | 构建OkHttp Request |
| `buildHeaders()` | 构建认证和通用HTTP头 |
| `executeHttpRequest(request, fromWho, msg)` | 同步执行HTTP请求 |
| `executeHttpRequestAsync(request, callback)` | 异步执行HTTP请求 |
| `getEffectiveModel(msg)` | 从msg提取模型名（覆盖默认值） |
| `getEffectiveTemperature(msg)` | 从msg提取temperature |
| `getEffectiveMaxTokens(msg)` | 从msg提取maxTokens |

---

### TLOpenAiProvider — OpenAI兼容Provider

**继承**: `TLLlmProvider`
**模块名**: `"openAiProvider"`
**API端点**: `/v1/chat/completions`

兼容所有OpenAI Chat Completions API的服务：OpenAI、DeepSeek、Qwen、GLM等。

#### 特性

- **请求格式**: OpenAI标准 `{model, messages, tools, temperature, max_tokens, stream}`
- **响应解析**: `choices[0].message.content` + `choices[0].message.tool_calls`
- **流式支持**: SSE `data: [DONE]` 检测，逐块解析 `delta.content` 和 `delta.tool_calls`
- **认证头**: `Authorization: Bearer {apiKey}`

#### 配置参数

| 参数 | 说明 |
|------|------|
| `apiKey` | API密钥 |
| `apiBaseUrl` | API地址，如 `https://api.openai.com` |
| `defaultModel` | 默认模型，如 `gpt-4o` |
| `connTimeOut` | 连接超时秒数 |
| `readTimeOut` | 读取超时秒数 |

---

### TLClaudeProvider — Claude API Provider

**继承**: `TLLlmProvider`
**模块名**: `"claudeProvider"`
**API端点**: `/v1/messages`

实现Anthropic Messages API格式。支持Claude 3/4系列模型的 `tool_use` 功能。

#### 特性

- **请求格式**: Anthropic `{model, max_tokens, messages, tools, system, stream}`
- **响应解析**: `content[]` 数组，区分 `text` 和 `tool_use` 块
- **流式支持**: Anthropic SSE事件格式，处理 `content_block_start/delta/stop` 和 `message_stop`
- **认证头**: `x-api-key: {apiKey}` + `anthropic-version: 2023-06-01`
- **分离System**: system消息通过独立的 `system` 字段发送，不放在messages中

#### 配置参数

| 参数 | 说明 |
|------|------|
| `apiKey` | API密钥（格式: `sk-ant-xxx`） |
| `apiBaseUrl` | API地址，如 `https://api.anthropic.com` |
| `defaultModel` | 默认模型，如 `claude-sonnet-4-6` |
| `connTimeOut` | 连接超时秒数 |
| `readTimeOut` | 读取超时秒数 |

---

### TLBaseSkill — 抽象Skill基类

**继承**: `TLBaseModule`
**实现**: `TLAiAgentParamString`
**类比**: `TLBaseCache`（抽象基类 + 具体实现注册）

每个Skill是一个可被LLM调用的功能单元，拥有名称、自然语言描述和JSON Schema参数定义。

#### 关键字段

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `skillName` | String | 模块名 | LLM可见的函数名（如 `"http_request"`） |
| `skillDescription` | String | 模块名+"skill" | NL描述，LLM据此决定何时调用 |
| `parameterSchema` | Map | `{}` | JSON Schema格式的参数定义 |
| `enabled` | boolean | true | 是否启用 |

#### 动作方法

| 方法 | 对应动作 | 说明 |
|------|----------|------|
| `getSkillInfo(msg)` | `SKILL_GETINFO` | 返回 name, description, parameterSchema |
| `execute(msg)` | `SKILL_EXECUTE` | **抽象方法**，子类实现核心逻辑 |
| `validate(msg)` | `SKILL_VALIDATE` | 校验输入参数是否匹配schema |

#### 辅助方法

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `buildFunctionDefinition()` | TLFunctionDefinition | 将Skill转换为LLM function定义 |

#### 参数Schema格式

```java
parameterSchema = {
    "paramName": {
        "type": "string",        // 参数类型
        "description": "...",    // 参数描述
        "required": true,        // 是否必填
        "enum": ["a", "b"]       // 可选：枚举值
    },
    ...
}
```

---

### 内置Skill实现

#### TLHttpRequestSkill — HTTP请求

**函数名**: `http_request`
**模块名**: `"httpRequestSkill"`

允许LLM发起HTTP GET/POST请求获取外部数据。

| 输入参数 | 类型 | 必填 | 说明 |
|----------|------|------|------|
| `url` | string | 是 | 请求URL |
| `method` | string | 否 | GET或POST，默认GET |
| `headers` | object | 否 | 请求头键值对 |
| `body` | string | 否 | POST请求体 |

**输出**: JSON格式 `{status, body, headers}`

#### TLFileOperationSkill — 文件操作

**函数名**: `file_operation`
**模块名**: `"fileOperationSkill"`

允许LLM读写本地文件系统。

| 输入参数 | 类型 | 必填 | 说明 |
|----------|------|------|------|
| `operation` | string | 是 | read/write/list/exists/delete |
| `path` | string | 是 | 文件或目录路径 |
| `content` | string | 否 | 写入内容（write时必填） |

**安全限制**:
- `allowedRootPath`: 允许操作的根目录（默认当前目录）
- `maxReadSize`: 最大读取大小（默认1MB）
- 超出根目录的路径操作被拒绝

#### TLCodeExecutionSkill — 代码执行

**函数名**: `code_execution`
**模块名**: `"codeExecutionSkill"`

在沙箱环境中执行代码片段。

| 输入参数 | 类型 | 必填 | 说明 |
|----------|------|------|------|
| `language` | string | 是 | python 或 javascript |
| `code` | string | 是 | 要执行的代码 |

**限制**:
- `maxExecutionTime`: 最大执行时间（默认30秒）
- `maxOutputSize`: 最大输出大小（默认100KB）
- Python: 需要系统安装 `python3`
- JavaScript: 使用 Java ScriptEngine（Nashorn/GraalJS）

---

### TLBaseMemory — 抽象记忆基类

**继承**: `TLBaseModule`
**实现**: `TLAiAgentParamString`

管理AI Agent的记忆存储和检索。支持短期（内存）和长期（文件持久化）两种模式。

#### 关键字段

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `memoryType` | String | `"shortTerm"` | 记忆类型标识 |
| `defaultExptime` | int | 0 | 默认过期时间（分钟），0=永不过期 |

#### 抽象动作方法

| 方法 | 对应动作 | 说明 |
|------|----------|------|
| `store(msg)` | `MEMORY_STORE` | 存储记忆条目 |
| `retrieve(msg)` | `MEMORY_RETRIEVE` | 按键检索 |
| `search(msg)` | `MEMORY_SEARCH` | 按文本搜索（模糊匹配） |
| `delete(msg)` | `MEMORY_DELETE` | 按键删除 |
| `clearAll(msg)` | `MEMORY_CLEARALL` | 清除（全部或指定session） |

#### 工具方法

| 方法 | 说明 |
|------|------|
| `calculateExpiresAt(exptimeMinutes)` | 计算过期时间戳 |

---

### 记忆实现

#### TLShortTermMemoryModule — 短期记忆

**模块名**: `"shortTermMemory"`
**存储方式**: ConcurrentHashMap（内存）
**特性**: TTL自动过期，LRU容量淘汰，进程重启丢失

| 配置参数 | 默认值 | 说明 |
|----------|--------|------|
| `maxEntries` | 1000 | 最大条目数 |
| `cleanupInterval` | 60秒 | 过期清理间隔 |
| `defaultExptime` | 0（永不过期） | 默认过期时间（分钟） |

**Key格式**: `{sessionId}:{key}` — session级别的命名空间隔离

#### TLLongTermMemoryModule — 长期记忆

**模块名**: `"longTermMemory"`
**存储方式**: JSON文件 + 内存缓存
**特性**: 跨会话持久化，进程重启保留

| 配置参数 | 默认值 | 说明 |
|----------|--------|------|
| `storagePath` | `./data/longterm_memory` | 存储目录 |
| `maxCacheEntries` | 10000 | 内存缓存最大条目 |
| `defaultExptime` | 0（永不过期） | 默认过期时间（分钟） |

**文件**: `{storagePath}/memory_store.json`
**Key格式**: `{sessionId}:{tag}:{key}` — session+tag级别命名空间

---

## 数据POJO

### TLConversationHistory — 对话历史条目

表示一次对话中的一条消息（对应OpenAI role: system/user/assistant/tool）。

| 字段 | 类型 | 说明 |
|------|------|------|
| `role` | Role枚举 | system / user / assistant / tool |
| `content` | String | 文本内容（可null） |
| `toolCalls` | List\<TLToolCall\> | assistant的tool calls（可null） |
| `toolCallId` | String | tool结果对应的call ID |
| `name` | String | 函数名（tool消息） |
| `timestamp` | long | 创建时间戳（epoch millis） |
| `metadata` | Map | 扩展元数据 |

**快捷构造器**:
```java
new TLConversationHistory(Role.user, "你好")
new TLConversationHistory(Role.assistant, "你好！有什么可以帮助你的？")
new TLConversationHistory(Role.assistant, toolCallsList)
new TLConversationHistory("call_123", "http_request", "{\"status\":200}")
```

### TLToolCall — 工具调用

LLM返回的function/tool call数据结构。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 唯一调用ID |
| `type` | String | 类型，默认"function" |
| `functionName` | String | 函数名（映射到Skill的skillName） |
| `arguments` | Map\<String, Object\> | 函数参数 |

### TLFunctionDefinition — 函数定义

Skill转换为LLM可理解的函数schema。

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | String | 函数名 |
| `description` | String | NL描述 |
| `parameters` | Map | JSON Schema格式参数 |

**工厂方法**:
```java
TLFunctionDefinition.fromSkill(skillName, skillDescription, parameterSchema)
```

### TLMemoryEntry — 记忆条目

记忆存储的基本单元。

| 字段 | 类型 | 说明 |
|------|------|------|
| `key` | String | 唯一键 |
| `value` | Object | 存储的值 |
| `type` | String | "shortTerm" 或 "longTerm" |
| `tag` | String | 分类标签 |
| `createdAt` | long | 创建时间 |
| `expiresAt` | long | 过期时间（0=永不过期） |
| `metadata` | Map | 扩展元数据 |
| `isExpired()` | boolean | 判断是否过期 |

---

## 消息流

### 完整Chat流程

```
外部调用者
  │
  │ putMsg("aiagent", {action:"chat", sessionId:"s1", userMessage:"北京天气怎么样?"})
  ▼
┌──────────────────────────────────────────────────────────────┐
│ TLAiAgent.chat()                                             │
│                                                              │
│ ① beforeMsgTable触发 →                                      │
│    putMsg("shortTermMemory", {action:MEMORY_SEARCH,          │
│          sessionId:"s1", memoryQuery:"北京天气"})             │
│    ← 返回相关记忆                                             │
│                                                              │
│ ② 获取上下文:                                                 │
│    putMsg("aiContext", {action:CONTEXT_GETMESSAGES,          │
│          sessionId:"s1"})                                    │
│    ← [History: systemMsg, user:"你好", assistant:"你好!"]    │
│    + 新消息: user:"北京天气怎么样?"                           │
│                                                              │
│ ③ 收集Skill定义:                                              │
│    for each skill in skills:                                 │
│      putMsg(skill, {action:SKILL_GETINFO})                   │
│      → buildFunctionDefinition()                             │
│    → [{name:"http_request", description:"...", params:{}}]   │
│                                                              │
│ ④ 发送LLM:                                                    │
│    putMsg("openAiProvider", {action:LLM_COMPLETION,          │
│          messageHistory:[...],                                │
│          functionDefinitions:[http_request],                  │
│          model:"gpt-4o"})                                    │
│                                                              │
│ ⑤ Provider HTTP请求:                                          │
│    POST https://api.openai.com/v1/chat/completions           │
│    ← 响应: {tool_calls:[{name:"http_request",                │
│              args:{url:"https://api.weather.com/beijing"}}]} │
│                                                              │
│ ⑥ 执行Tool Call:                                              │
│    skill = skills.get("http_request")                        │
│    putMsg(skill, {action:SKILL_EXECUTE,                      │
│          skillInput:{url:"...", method:"GET"}})              │
│    ← {result:true, skillOutput:"{\"status\":200,...}"}      │
│                                                              │
│ ⑦ 第二次LLM请求（带tool结果）:                                 │
│    发送: messages + tool result                              │
│    ← {content:"北京今天晴，25°C..."} (无tool_calls)           │
│                                                              │
│ ⑧ 保存上下文:                                                 │
│    putMsg("aiContext", {action:CONTEXT_ADDMESSAGE, ...})     │
│                                                              │
│ ⑨ afterMsgTable触发 →                                       │
│    putMsg("longTermMemory", {action:MEMORY_STORE,            │
│          memoryValue:"用户查询过北京天气"})                    │
│                                                              │
│ ⑩ 返回结果:                                                   │
└──────────────────────────────────────────────────────────────┘
  │
  ▼
TLMsg {result:true, aiResponse:"北京今天晴，25°C...", sessionId:"s1", iterations:2}
```

### 流式Chat流程

```
外部调用者
  │ putMsg("aiagent", {action:"chatStream", sessionId, userMessage,
  │                    resultFor:"callerModule", resultAction:"onChunk"})
  ▼
TLAiAgent.chatStream()
  → 构建context + function defs
  → putMsg(provider, {action:LLM_COMPLETIONSTREAM, ...,
            resultFor:"aiagent", resultAction:"onStreamResult"})

Provider: OK HTTP异步 + SSE解析
  → 每收到一个chunk:
    putMsg("aiagent", {action:"onStreamResult",
          chunk:"今天", sessionId})

TLAiAgent.onStreamResult()
  → 转发chunk到原始调用者:
    putMsg("callerModule", {action:"onChunk", chunk:"今天"})

  → 收到streamDone:
    putMsg("callerModule", {action:"onChunk", streamDone:true,
          aiResponse:"完整文本..."})
```

### Skill注册流程

```
外部模块
  │ putMsg("aiagent", {action:"registerSkill",
  │        moduleName:"mySkill", classfile:"com.example.MySkill",
  │        params:{skillName:"my_function", skillDescription:"..."}})
  ▼
TLAiAgent.registerSkill()
  → 通过工厂创建Skill实例
  → skills.put("my_function", skillInstance)
  → modules.put("mySkill", skillInstance)
  → 返回 {result:true, skillName:"my_function"}
```

### Provider切换流程

```
外部模块
  │ putMsg("aiagent", {action:"setLlmProvider", llmProvider:"claudeProvider"})
  ▼
TLAiAgent.setLlmProvider()
  → llmProvider = getModule("claudeProvider")
  → defaultLlmProvider = "claudeProvider"
  → 返回 {result:true}
  → 后续chat自动使用新provider
```

---

## XML配置

### aiagent_config.xml

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<moduleConfig>
    <!-- ===== 基础参数 ===== -->
    <params>
        <!-- 默认LLM Provider模块名 -->
        <defaultLlmProvider value="openAiProvider"/>
        <!-- tool-call最大迭代次数 -->
        <maxToolCallIterations value="10"/>
        <!-- 上下文最大历史轮次 -->
        <maxHistoryTurns value="50"/>
        <!-- Skill包名前缀（用于类路径简写） -->
        <defaultSkillPackageName value="cn.tianlong.tlobject.aiagent.skill.builtin"/>
        <!-- Memory包名前缀 -->
        <defaultMemoryPackageName value="cn.tianlong.tlobject.aiagent.memory"/>
        <!-- 默认模型 -->
        <defaultModel value="gpt-4o"/>
        <!-- 默认temperature -->
        <defaultTemperature value="0.7"/>
        <!-- 默认maxTokens -->
        <defaultMaxTokens value="4096"/>
    </params>

    <!-- ===== LLM Providers ===== -->
    <providers>
        <!-- OpenAI / 兼容Provider -->
        <provider name="openAiProvider"
                  classfile="cn.tianlong.tlobject.aiagent.provider.openai.TLOpenAiProvider"
                  statup="true"
                  apiKey="sk-your-api-key"
                  apiBaseUrl="https://api.openai.com"
                  defaultModel="gpt-4o"
                  connTimeOut="30"
                  readTimeOut="120"/>

        <!-- Claude Provider -->
        <provider name="claudeProvider"
                  classfile="cn.tianlong.tlobject.aiagent.provider.claude.TLClaudeProvider"
                  statup="false"
                  apiKey="sk-ant-your-api-key"
                  apiBaseUrl="https://api.anthropic.com"
                  defaultModel="claude-sonnet-4-6"
                  connTimeOut="30"
                  readTimeOut="120"/>
    </providers>

    <!-- ===== Skills ===== -->
    <skills>
        <skill name="httpRequestSkill"
               classfile="cn.tianlong.tlobject.aiagent.skill.builtin.TLHttpRequestSkill"
               statup="true"
               skillName="http_request"
               skillDescription="Send HTTP requests to fetch web content or call APIs. Supports GET and POST methods."/>

        <skill name="fileOperationSkill"
               classfile="cn.tianlong.tlobject.aiagent.skill.builtin.TLFileOperationSkill"
               statup="true"
               skillName="file_operation"
               skillDescription="Read and write files on the local filesystem. Operations: read, write, list, exists, delete."/>
    </skills>

    <!-- ===== Memory Stores ===== -->
    <memoryStores>
        <memoryStore name="shortTermMemory"
                     classfile="cn.tianlong.tlobject.aiagent.memory.TLShortTermMemoryModule"
                     statup="true"
                     memoryType="shortTerm"
                     defaultExptime="60"
                     maxEntries="1000"/>

        <memoryStore name="longTermMemory"
                     classfile="cn.tianlong.tlobject.aiagent.memory.TLLongTermMemoryModule"
                     statup="true"
                     memoryType="longTerm"
                     defaultExptime="0"
                     storagePath="./data/longterm_memory"/>
    </memoryStores>

    <!-- ===== AOP拦截: 记忆注入 ===== -->
    <beforeMsgTable>
        <!-- chat前自动召回短期记忆 -->
        <action name="chat">
            <msg action="recallAgentMemory" destination="aiagent"
                 paramsFromMsg="sessionId" systemArgs="usePreReturnMsg:true"/>
        </action>
    </beforeMsgTable>

    <afterMsgTable>
        <!-- chat后自动保存长期记忆 -->
        <action name="chat">
            <msg action="saveAgentMemory" destination="aiagent"
                 paramsFromMsg="sessionId;aiResponse" systemArgs="useActionReturn:true"/>
        </action>
    </afterMsgTable>

    <!-- ===== 启动初始化 ===== -->
    <initMsg>
        <msg action="listSkills" destination="aiagent"/>
    </initMsg>
</moduleConfig>
```

### moduleFactory_config.xml 中的注册

```xml
<modules>
    <!-- AI Agent核心模块 -->
    <module name="aiagent"
            classfile="cn.tianlong.tlobject.aiagent.TLAiAgent"
            configfile="conf/aiagent/aiagent_config.xml"/>

    <module name="aiContext"
            classfile="cn.tianlong.tlobject.aiagent.TLAiContext"/>

    <module name="openAiProvider"
            classfile="cn.tianlong.tlobject.aiagent.provider.openai.TLOpenAiProvider"/>

    <module name="claudeProvider"
            classfile="cn.tianlong.tlobject.aiagent.provider.claude.TLClaudeProvider"/>

    <module name="httpRequestSkill"
            classfile="cn.tianlong.tlobject.aiagent.skill.builtin.TLHttpRequestSkill"/>

    <module name="fileOperationSkill"
            classfile="cn.tianlong.tlobject.aiagent.skill.builtin.TLFileOperationSkill"/>

    <module name="shortTermMemory"
            classfile="cn.tianlong.tlobject.aiagent.memory.TLShortTermMemoryModule"/>

    <module name="longTermMemory"
            classfile="cn.tianlong.tlobject.aiagent.memory.TLLongTermMemoryModule"/>
</modules>
```

---

## 使用示例

### 示例1: 基本Chat调用

```java
// 启动工厂
TLObjectFactory factory = TLObjectFactory.getInstance("conf/aiagent", "moduleFactory_config.xml");
factory.startFactory(null, null);
factory.boot();

// 获取agent模块
TLAiAgent agent = (TLAiAgent) factory.getModule("aiagent");

// 发送chat消息
TLMsg chatMsg = new TLMsg(AGENT_CHAT)
    .setParam(AI_P_SESSIONID, "user123")
    .setParam(AI_P_USERMESSAGE, "用中文解释一下量子计算的基本原理");

TLMsg response = agent.putMsg(agent, chatMsg);

if (response.parseBoolean(RESULT, false)) {
    System.out.println("AI回复: " + response.getStringParam(AI_P_RESPONSE, ""));
    System.out.println("迭代次数: " + response.getIntParam("iterations", 0));
} else {
    System.out.println("错误: " + response.getStringParam(AI_P_RESPONSE, ""));
}
```

### 示例2: 多轮对话（带上下文）

```java
// 第一轮
TLMsg msg1 = new TLMsg(AGENT_CHAT)
    .setParam(AI_P_SESSIONID, "session_001")
    .setParam(AI_P_USERMESSAGE, "我叫张三，是一名Java开发者");
TLMsg resp1 = agent.putMsg(agent, msg1);
System.out.println(resp1.getStringParam(AI_P_RESPONSE, ""));

// 第二轮（同一个sessionId，自动携带上下文）
TLMsg msg2 = new TLMsg(AGENT_CHAT)
    .setParam(AI_P_SESSIONID, "session_001")
    .setParam(AI_P_USERMESSAGE, "我刚才说我叫什么名字？");
TLMsg resp2 = agent.putMsg(agent, msg2);
System.out.println(resp2.getStringParam(AI_P_RESPONSE, ""));
// AI会回答: "你叫张三"
```

### 示例3: Tool Call自动触发

```java
// Agent配置了httpRequestSkill后，LLM会自动调用
TLMsg msg = new TLMsg(AGENT_CHAT)
    .setParam(AI_P_SESSIONID, "demo")
    .setParam(AI_P_USERMESSAGE, "帮我获取 https://api.github.com/users/torvalds 的信息");

TLMsg response = agent.putMsg(agent, msg);
// LLM分析需要http_request → Agent自动调用TLHttpRequestSkill
// → 结果送回LLM → LLM总结响应
// 返回结构化的用户信息总结
System.out.println(response.getStringParam(AI_P_RESPONSE, ""));
```

### 示例4: 流式Chat

```java
// 创建接收流式chunks的模块...
// 流式调用
TLMsg streamMsg = new TLMsg(AGENT_CHATSTREAM)
    .setParam(AI_P_SESSIONID, "stream_demo")
    .setParam(AI_P_USERMESSAGE, "写一首关于春天的五言绝句")
    .setParam(RESULTFOR, "myModule")       // 回调目标模块
    .setParam(RESULTACTION, "onTextChunk"); // 回调动作

agent.putMsg(agent, streamMsg);

// 在myModule的checkMsgAction中处理:
// case "onTextChunk":
//   if (msg.containsParam(AI_P_CHUNK))
//       逐个输出字符: msg.getStringParam(AI_P_CHUNK, "")
//   if (msg.parseBoolean(AI_P_STREAMDONE, false))
//       获取完整文本: msg.getStringParam(AI_P_RESPONSE, "")
```

### 示例5: 运行时注册自定义Skill

```java
// 方式1: 通过类名注册
TLMsg registerMsg = new TLMsg(AGENT_REGISTERSKILL)
    .setParam(MODULENAME, "myCustomSkill")
    .setParam(MODULE_CLASSFILE, "com.example.MyCustomSkill")
    .setParam(MODULE_PARAMS, Map.of("skillName", "my_function",
                                     "skillDescription", "My custom function"));
agent.putMsg(agent, registerMsg);

// 方式2: 直接传入实例
MyCustomSkill skillInstance = new MyCustomSkill("myCustomSkill");
skillInstance.setSkillName("my_function");
skillInstance.setSkillDescription("My custom function");

TLMsg registerMsg2 = new TLMsg(AGENT_REGISTERSKILL)
    .setParam(INSTANCE, skillInstance);
agent.putMsg(agent, registerMsg2);
```

### 示例6: 管理上下文和记忆

```java
// 查看上下文
TLMsg ctxMsg = new TLMsg(AGENT_GETCONTEXT)
    .setParam(AI_P_SESSIONID, "session_001");
TLMsg ctxResp = agent.putMsg(agent, ctxMsg);
List<TLConversationHistory> history =
    (List<TLConversationHistory>) ctxResp.getListParam(AI_P_MESSAGEHISTORY, List.of());
System.out.println("历史消息数: " + history.size());

// 清除上下文
agent.putMsg(agent, new TLMsg(AGENT_CLEARCONTEXT)
    .setParam(AI_P_SESSIONID, "session_001"));

// 手动保存记忆
agent.putMsg(agent, new TLMsg(AGENT_SAVEMEMORY)
    .setParam(AI_P_SESSIONID, "user123")
    .setParam(AI_P_MEMORYKEY, "user_name")
    .setParam(AI_P_MEMORYVALUE, "张三")
    .setParam(AI_P_MEMORYTAG, "user_fact"));

// 召回记忆
agent.putMsg(agent, new TLMsg(AGENT_RECALLMEMORY)
    .setParam(AI_P_SESSIONID, "user123")
    .setParam(AI_P_MEMORYQUERY, "名字"));
```

### 示例7: 运行时切换Provider

```java
// 从OpenAI切换到Claude
agent.putMsg(agent, new TLMsg(AGENT_SETPROVIDER)
    .setParam(AI_P_PROVIDER, "claudeProvider"));

// 后续chat自动使用Claude
```

### 示例8: 异步调用

```java
// 使用框架的异步消息机制
TLMsg asyncMsg = new TLMsg(AGENT_CHAT)
    .setParam(AI_P_SESSIONID, "async_demo")
    .setParam(AI_P_USERMESSAGE, "帮我总结一下今天的新闻")
    .setWaitFlag(false);  // 异步

agent.putMsg(agent, asyncMsg);
// 立即返回，不等待LLM响应
// 可通过msgTable或beforeMsgTable配置回调处理
```

---

## 扩展开发

### 添加自定义LLM Provider

1. 继承 `TLLlmProvider`
2. 实现抽象方法：`completion()`, `completionStream()`, `listModels()`, `cancel()`, `buildRequestBody()`, `parseResponse()`, `getCompletionsPath()`
3. 在XML配置中注册

```java
public class MyCustomProvider extends TLLlmProvider {
    @Override
    protected String getCompletionsPath() { return "/v1/chat"; }

    @Override
    public String buildRequestBody(TLMsg msg, List<TLConversationHistory> messages,
                                    List<TLFunctionDefinition> tools, boolean stream) {
        // 构建自定义API的JSON请求体
    }

    @Override
    public TLMsg parseResponse(String responseBody, TLMsg originalMsg) {
        // 解析自定义API的JSON响应
    }

    @Override
    protected TLMsg completion(Object fromWho, TLMsg msg) { /* 实现 */ }
    @Override
    protected TLMsg completionStream(Object fromWho, TLMsg msg) { /* 实现 */ }
    @Override
    protected TLMsg listModels(Object fromWho, TLMsg msg) { /* 实现 */ }
    @Override
    protected TLMsg cancel(Object fromWho, TLMsg msg) { /* 实现 */ }
}
```

### 添加自定义Skill

1. 继承 `TLBaseSkill`
2. 设置 `skillName`, `skillDescription`, `parameterSchema`
3. 实现 `execute(fromWho, msg)` 方法
4. 在XML `<skills>` 中注册 或 运行时通过 `registerSkill` 注册

```java
public class MyDatabaseQuerySkill extends TLBaseSkill {
    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        skillName = "database_query";
        skillDescription = "Query the database with SQL";

        parameterSchema = Map.of(
            "sql", Map.of("type", "string", "description", "SQL query", "required", true)
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        Map<String, Object> input = (Map<String, Object>) msg.getMapParam(AI_P_SKILLINPUT, Map.of());
        String sql = (String) input.get("sql");

        // 执行数据库查询...
        // 可以通过消息调用database模块
        TLMsg dbMsg = createMsg().setAction("query")
            .setParam("sql", sql);
        TLMsg dbResult = putMsg("database", dbMsg);

        return createMsg()
            .setParam(RESULT, true)
            .setParam(AI_P_SKILLOUTPUT, dbResult.toString());
    }
}
```

### 添加自定义Memory Store

1. 继承 `TLBaseMemory`
2. 实现 `store()`, `retrieve()`, `search()`, `delete()`, `clearAll()`
3. 在XML `<memoryStores>` 中注册

```java
public class MyRedisMemoryModule extends TLBaseMemory {
    // 基于Redis的分布式记忆存储
    @Override protected TLMsg store(Object fromWho, TLMsg msg) { /* Redis SET */ }
    @Override protected TLMsg retrieve(Object fromWho, TLMsg msg) { /* Redis GET */ }
    @Override protected TLMsg search(Object fromWho, TLMsg msg) { /* Redis SCAN */ }
    @Override protected TLMsg delete(Object fromWho, TLMsg msg) { /* Redis DEL */ }
    @Override protected TLMsg clearAll(Object fromWho, TLMsg msg) { /* Redis FLUSHDB */ }
}
```

---

## 构建与运行

```bash
# 构建整个AI Agent模块
mvn clean install -pl aiagent -am -DskipTests

# 仅构建common模块
mvn clean install -pl aiagent/common -am -DskipTests

# 运行Demo（需先创建demo配置）
mvn exec:java -pl demo/tlobject \
  -Dexec.mainClass="cn.tianlong.java.demo.aiagent.AiAgentDemo"
```

---

## 类关系总览

```
TLParamString (核心常量接口)
  └── TLAiAgentParamString (AI常量接口)
        ├── TLAiAgent (主控编排器)     extends TLBaseModule
        │     └── myConfig (XML解析)   extends TLModuleConfig
        ├── TLAiContext (上下文管理)   extends TLBaseModule
        ├── TLLlmProvider (抽象Provider) extends TLBaseModule
        │     ├── TLOpenAiProvider     extends TLLlmProvider
        │     │     └── StreamCallback (SSE解析)
        │     └── TLClaudeProvider     extends TLLlmProvider
        │           └── ClaudeStreamCallback (SSE解析)
        ├── TLBaseSkill (抽象Skill)    extends TLBaseModule
        │     ├── TLHttpRequestSkill   extends TLBaseSkill
        │     ├── TLFileOperationSkill extends TLBaseSkill
        │     └── TLCodeExecutionSkill extends TLBaseSkill
        └── TLBaseMemory (抽象记忆)    extends TLBaseModule
              ├── TLShortTermMemoryModule extends TLBaseMemory
              └── TLLongTermMemoryModule  extends TLBaseMemory

数据POJO (implements Serializable):
  TLConversationHistory, TLToolCall, TLFunctionDefinition, TLMemoryEntry
```

---

## 新功能模块

以下模块在核心框架之上扩展，均为消息驱动、控制台有对应命令。

### MCP 市场与工具集成

**包**: `cn.tianlong.tlobject.aiagent.mcp` — `TLMcpRegistry`、`TLMcpAgent`、MCP 客户端

MCP（Model Context Protocol）工具包市场：将外部 MCP 服务器安装为子 Agent，LLM 通过 `delegate_to_*` 委托调用。

**控制台命令**:

| 命令 | 说明 |
|------|------|
| `/mcp search [keyword]` | 搜索 MCP 服务器（空参数列出全部精选，精选索引 + npm 在线搜索） |
| `/mcp info <package>` | 查看包详情（功能、工具列表、主页） |
| `/mcp install <package> [name] [--args ...]` | 安装为子 Agent（自动命名，运行时检测） |
| `/mcp list` | 列出已安装的 MCP Agent 及状态 |
| `/mcp remove <name>` | 卸载 MCP Agent |

**组件**:
- `TLMcpRegistry` — 精选索引（内置）+ npm 在线搜索，数据查询层
- `TLMcpAgent` — MCP 桥接 Agent（无 LLM）：连接 MCP 服务器，自动发现 tools → 展开为 N 个 function defs，供 master 委托

### Evals 评测体系

**包**: `cn.tianlong.tlobject.aiagent.evals` — `TLEvalsModule`

框架级自动化评测：JSON 用例 + 报告，三类 Judge（精确匹配 / LLM 裁判 / 约束检查）。

**控制台命令**:

| 命令 | 说明 |
|------|------|
| `/eval suite` | 运行全部评测用例 |
| `/eval list` | 列出可用用例 |
| `/eval quick` | 快速自检 |
| `/eval run <id>` | 运行指定用例 |
| `/eval cascade [agent]` | 级联评测（多目标多调用模式） |

`TLAgentService.doEval` 只做路由 + 结果汇总格式化，评测逻辑全部在 evals 模块内。

### 单元测试 /test

**包**: `cn.tianlong.tlobject.aiagent.test` — `TLAgentTestModule`、`TLMockProvider`、`TLEchoSkill`、`TLSleepSkill`

确定性单元测试（Mock Provider 驱动，零网络、结果可重复）。覆盖 doChat 主循环、多轮对话、单/并行 Tool、超时、流式、取消、批次超时、会话恢复 9 个场景。

**控制台命令**（chat 应用配置好测试模块后可用）:

| 命令 | 说明 |
|------|------|
| `/test` | 运行全部测试（9 内置场景 + 自定义用例） |
| `/test list` | 列出可用测试用例 |
| `/test <用例名>` | 运行单个场景（如 `/test basicChat`） |

**chat 应用接入要点**（`conf/demo/aiagent/`）:
- 测试目标为**独立 `aiagent` 实例**（`aiagent_config.xml`），与主控 `aiagent_master` 完全隔离：测试切 mockProvider 不影响真实对话，会话走文件版 sessionManager，不污染 `/sessions`
- `agentTestModule_config.xml` 的 `caseFile` 指向 `agentTest_cases.xml`（XML 配置驱动的自定义用例）
- 两 demo 配置目录（`conf/demo/aiagent/` 与 `conf/demo/aitest/`）互相独立，仅共享 `conf/tlobject/` 公共层

### HITL 人工审批

**包**: `cn.tianlong.tlobject.aiagent.approval` — `TLApprovalModule`（approvalGate）、`TLApprovalRequest`、`ConsoleReviewer`、`IApprovalReviewer`

高风险工具操作需人工批准后才执行。

**规则配置**（`approvalGate_config.xml`，全局单例）:
```xml
<!-- 键是 LLM 函数名（如 file_operation），不是模块名（fileOperationSkill）！
     tool:op → 该工具的指定操作需审批；tool → 全部操作需审批 -->
<approvalRules value="file_operation:delete, file_operation:write"/>
```

**启用方式**: 各 Agent 在自身 config 中设 `<approvalModule value="approvalGate"/>`（fileAgent/codeAgent 已启用；master 不启用——审批下沉到执行敏感操作的子 Agent）。

**控制台交互**: 审批框经 `msgBus` 发布（topic `approvalEvent`），控制台启动时 `registBus` 订阅并自行渲染，审批模块与控制台零直接依赖。命令：

```
/approve approve:ID        批准
/approve reject:ID:原因     拒绝
```

**拒绝语义（重要）**:
- 拒绝是**结构化标志**沿委托链逐级传播（`doChat` 返回 `rejected`/`rejectReason` 参数 → `ToolExecutor` 识别子模块拒绝 → 父 agent 回滚并停止循环），不依赖 LLM 读懂文案
- **会话级拒绝记忆**：同会话内（工具+参数）被拒绝后再次请求直接返回拒绝，不再重复弹框；换会话或换参数正常审批

### 会话管理与断点恢复

- `TLSessionManager` — 文件版（JSONL 增量存储，`data/{userId}/` 隔离）
- `TLDatabaseSessionManager` — 数据库版（ai_sessions + ai_session_rounds 双表）
- **断点续传**: Agent 侧 `enableCheckpoint=true` 通知 + SessionManager 落盘；mid-loop 中断后控制台 `⚠ 提示 /resume`

**控制台命令**:

| 命令 | 说明 |
|------|------|
| `/sessions` | 列出所有历史会话 |
| `/continue [id]` | 继续历史会话（不带 id 恢复最近） |
| `/resume` | 恢复未完成的 mid-loop 断点会话 |
