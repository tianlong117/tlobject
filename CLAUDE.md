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

No test framework is configured (no test directories exist). The demo module (`demo/tlobject`) contains runnable `main()` examples instead.

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

### AI Agent Framework (`aiagent/`) — `cn.tianlong.tlobject.aiagent`

消息对象编程模型下的 AI 大模型智能体子框架。Agent 接收用户输入 → LLM 分析意图 → 调用 Skill 执行 → 返回结果。支持流式/非流式、多轮对话、记忆管理。

**核心模块:**

| Module | Class | Purpose |
|--------|-------|---------|
| `aiagent` | `TLAiAgent` | 主控编排器，chat() 统一实现流/非流 |
| `aiContext` | `TLAiContext` | 会话历史管理，自动裁剪 |
| `openAiProvider` | `TLOpenAiProvider` | OpenAI/DeepSeek 兼容 Provider，SSE 流式 |
| `claudeProvider` | `TLClaudeProvider` | Claude API Provider |
| `httpRequestSkill` | `TLHttpRequestSkill` | HTTP 请求 Skill (tool: http_request) |
| `fileOperationSkill` | `TLFileOperationSkill` | 文件读写 Skill (tool: file_operation) |
| `codeExecutionSkill` | `TLCodeExecutionSkill` | 代码执行 Skill (tool: code_execution) |
| `shortTermMemory` | `TLShortTermMemoryModule` | 短期记忆（内存+TTL） |
| `longTermMemory` | `TLLongTermMemoryModule` | 长期记忆（文件持久化） |
| `streamCallback` | `TLStreamCallback` | 流式回调通用模块 |

**Maven 结构:** `aiagent/common` + `provider-openai` + `provider-claude` + `skill-builtin` + `memory-store`

**Demo:**
- `AiAgentDemoModule` — 8 场景自动化测试（全部通过）
- `ChatDemo` — 交互式对话，支持 `/stream` `/clear` `/exit`

**关键实现要点:**
- `doChat(msg, stream)` — 流/非流统一入口，预处理(记忆召回+上下文)和後処理(保存上下文+長期記憶)完全共享
- `beforeMsgTable` 的 `beforeResult` 在 `msg.systemArgs["beforeResult"]` 中，需在業務方法主動讀取。`afterMsgTable` 會覆蓋返回值，記憶保存改為內部調用
- Tool call 的 `arguments` 必須是 JSON 字符串（`gson.toJson()`），不能用 JSON 對象（`gson.toJsonTree()`）
- DeepSeek: tools 和 temperature 不能同時傳，model 名在 Provider 配置中指定

**记忆体系（三层）:**
- `TLAiContext` — 会话对话记录（短期连续性），`getContextHistory()` 直接加载全部 messages
- `LongTermMemory` — 跨会话知识碎片（JSONL文件 / 数据库），通过 `recallAgentMemory()` + 文本匹配召回
- `ShortTermMemory` — 进程内 TTL 缓存，正常聊天流不使用，留给外部 skill 模块
- 记忆召回走 AOP：`beforeMsgTable` 拦截 chat action → `recallAgentMemory` → 结果注入 `msg.systemArgs["beforeResult"]` → `doChat` 读取后拼成 system 消息

**数据库记忆模块 (`TLDatabaseMemoryModule`):**
- 继承 `TLBaseMemory`，实现 5 个抽象方法：`store/retrieve/search/delete/clearAll`
- **关键模式**: 必须通过 `DB_GETTABLE` 获取 table 引用，再直接发消息给 table 模块。`TLDataBase` 不代理表操作！
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
