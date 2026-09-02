# TLObject — 统一对象消息编程框架

[English](README.en.md) | **中文**

> 一个 Java 消息驱动编程框架：用「给命名对象发送消息」取代「直接调用对象方法」，所有对象由统一工厂创立与管理。消息互动、名字互动、工厂创立，让应用获得极大的灵活性与可扩展性。

作者：天珑（tianlong117） · 博客：https://blog.csdn.net/tianlong117

---

## 目录

- [核心思想](#核心思想)
- [框架能力](#框架能力)
- [快速开始](#快速开始)
- [架构分层](#架构分层)
- [模块矩阵](#模块矩阵)
- [AI Agent 智能体子框架](#ai-agent-智能体子框架aiagent)
- [配置体系](#配置体系)
- [Demo 与自测](#demo-与自测)
- [日志](#日志)
- [许可证](#许可证)

---

## 核心思想

统一对象消息编程从根本上说不是一个编程或应用框架，而是一种编程思想：传统编程直接调用对象的方法（`A.func()`）；消息编程则通过向对象发送消息来触发方法执行，改变对象间互动的模式，从而获得极大的灵活性。三个特点：

**1. 所有对象通过消息互动**

```java
TLMsg msg = new TLMsg();
msg.setAction("func");          // 消息携带要执行的动作与参数
msg.setParam("key", value);
putMsg(A, msg);                 // 向对象 A 发送消息
```

**2. 所有对象通过名字互动**

每个对象都可以被赋予一个名字，通过名字即可向对象发消息，无需持有实例引用：

```java
putMsg("worker", msg);          // "worker" 即对象的注册名
```

**3. 所有对象通过统一工厂创立**

对象的创建、初始化、启动全部由 `TLObjectFactory` 依据 XML 配置完成。开发者只需使用名字，无需关心对象的生命周期：

```java
putMsg("worker", msg);          // 无需 new，工厂按配置创建并启动
```

---

## 框架能力

- **消息驱动架构**：`TLMsg`（动作+参数+路由+链式下一条消息）统一了同步/异步/延时/组播/总线/路由全部消息形态
- **声明式 XML 配置**：模块定义、参数、消息路由表、`beforeMsgTable`/`afterMsgTable` AOP 拦截钩子全部配置化，支持热加载
- **模块生态丰富**：数据库 DAO、Jetty/Tomcat/Netty 网络服务、WebSocket、HTTP、缓存、Redis、定时任务、日志、Excel 导出等 15 个顶层模块、40+ 可复用模块
- **AI Agent 子框架**：多轮对话、工具/Skill 调用、三级记忆、流式输出、多子 Agent 编排、MCP 集成、HITL 人工审批、会话持久化与断点恢复（详见 [aiagent/README.md](aiagent/README.md)）
- **自测体系**：Mock Provider 驱动的确定性测试套件，无需真实 LLM/网络即可回归（9 个场景）

---

## 快速开始

**前置要求**：JDK 17+、Maven 3.6+（Windows/macOS/Linux 均可）

```bash
# 1. 编译并安装全部模块（44 个模块约 1 分钟）
mvn clean install -DskipTests

# 2. 运行 AI Agent 自动化自测（Mock Provider，无需 API Key、无需联网、无需数据库）
mvn -pl demo/tlobject exec:java \
    -Dexec.mainClass=cn.tianlong.java.demo.aiagent.AiAgentDemoStartup
# 日志中出现的 [TEST] 汇总标记即为 9 个测试场景结果

# 3. 启动交互式聊天控制台（需先在配置中填入真实 API Key，见下文「配置 API Key」）
mvn -pl demo/tlobject exec:java \
    -Dexec.mainClass=cn.tianlong.java.demo.aiagent.AIStart
```

> 也推荐用 IDE（IDEA/Eclipse）打开根 `pom.xml`，直接运行 `demo/tlobject` 下各场景的 `main` 方法。

**配置 API Key**

仓库内所有 LLM Provider 配置的 `apiKey` 均为占位符 `sk-your-api-key`。使用聊天 Demo 前，请将 `demo/tlobject/src/main/resources/conf/demo/aiagent/` 下各 `*_config.xml` 中出现的 `apiKey="sk-your-api-key"` 替换为你自己的密钥（DeepSeek/OpenAI/Claude 均可，Provider 与模型名也在这些配置中指定）。**切勿将真实密钥提交到公开仓库。**

---

## 架构分层

```
┌──────────────────────────────────────────────────────────────┐
│                    TLAppStartUp（应用入口）                     │
│  解析命令行 -d 配置目录 / -m 工厂配置 / -f 应用配置 / -n 应用名   │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
┌──────────────────────────────────────────────────────────────┐
│                 TLObjectFactory（统一对象工厂）                 │
│  按 XML 配置实例化/启动/管理全部命名对象，支持父子工厂层次        │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
┌──────────────────────────────────────────────────────────────┐
│   IObject ── TLBaseObject ── TLBaseModule（一切皆模块）         │
│   putMsg()/getMsg() 消息收发 · 生命周期 init→runInit→runStart   │
│   msgTable 路由 · before/afterMsgTable AOP 钩子 · invokeAction │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
           TLMsg（消息：action + args + systemArgs + 路由）
```

**消息流动**：`putMsg("模块名", msg)` → 工厂按名字解析模块 → 目标 `getMsg()` 校验路由 → 执行 `beforeMsgTable` 钩子 → 反射分发动作到方法（`invokeAction`）→ `afterMsgTable` 钩子 → 返回结果（支持 `nextMsg` 链式延续）。

**内置基础设施模块**（`core` 内，均可通过消息复用）：线程池 `TLThreadPool`、消息路由器 `TLMsgRouter`、发布订阅总线 `TLMsgBus`、广播 `TLMsgBroadCast`、定时扫描 `TLMsgScanner`、阻塞队列 `TLMsgBlockingQueue`、模块池 `TLModulePool`、会话数据 `TLSessionData`、参数校验 `TLParamValidation`、监控 `TLModuleMonitor`、异常处理 `TLExecptionHandler` 等。

---

## 模块矩阵

| 模块 | 说明 |
|------|------|
| `core` | 消息框架核心：IObject/TLMsg/TLBaseModule/工厂/生命周期/路由/AOP（上文架构分层） |
| `aiagent` | AI Agent 智能体子框架（common + provider + skill + memory + webui，详见 [aiagent/README.md](aiagent/README.md)） |
| `database` | 消息化数据访问：CRUD、事务、建表、DAO 模式（MySQL/PostgreSQL/SQLite 皆可） |
| `connectpoolofdb` | DB 连接池封装：C3P0、HikariCP、SQLite |
| `network` | 网络层：Jetty/Tomcat/Netty 服务端、WebSocket 客户端/服务端、HTTP 客户端、代理、JWT 鉴权 |
| `cache` | 缓存：EhCache、Caffeine、文件缓存、内存缓存 |
| `redis` | Redis 客户端消息动作化封装 |
| `servletutils` | Servlet 工具：Web 通道（上传/SSE/路由分发）、会话工具 |
| `execl` | Excel 导出 |
| `appwebmanager` | 基于 Web 的管理端应用 |
| `crontab` | Cron 表达式定时任务 |
| `log` | 日志模块（SLF4J 桥接） |
| `utils` | 通用工具类 |
| `tlobject-all` | 聚合发布件（单依赖引入全部模块） |
| `demo` | 可运行示例工程（`tlobject` 消息场景 + `web` Web 应用场景） |

---

## AI Agent 智能体子框架（aiagent）

基于本框架消息模型构建的 AI Agent 框架：Agent 接收用户输入 → LLM 分析意图 → 调用 Skill/工具执行 → 返回结果。支持流式/非流式、多轮对话、三级记忆（会话上下文/短期/长期）、多子 Agent 编排与工作流、MCP 工具、Evals 评测、HITL 人工审批、会话持久化与断点恢复、Web 交互界面（webui）。

详细文档（模块结构/核心类/消息流/XML 配置/扩展开发/新功能模块）见 **`aiagent/README.md`**（约 1300 行，含完整架构图与使用手册）。

---

## 配置体系

全部配置文件以 XML 编写，约定命名 `{模块名}_config.xml`，由 `TLModuleConfig` 解析（XmlPullParser）：

| XML 元素 | 作用 |
|----------|------|
| `<modules>` | 定义子模块：`classfile` 类路径、`singleton` 单例、`params` 参数等 |
| `<params>` | 模块自身参数 |
| `<modulesParams>` | 子模块参数注入（如 aiContext 的 system prompt、上下文条数） |
| `<initMsg>` / `<startMsg>` | 初始化/启动时自动发送的消息序列 |
| `<msgTable>` | 消息路由表：`msgid` → 多条消息（支持顺序/并行、超时） |
| `<beforeMsgTable>` / `<afterMsgTable>` | 按 action 名的前置/后置 AOP 钩子 |
| `<include>` | 引入其它配置文件 |

配置文件位于各 demo 的资源目录，例如 `demo/tlobject/src/main/resources/conf/`：

- `conf/tlobject/` — 一应用全模块的组合配置（按应用名 `xxx_config.xml` 组织）
- `conf/demo/` — 按场景的独立配置目录（aiagent / aitest / base / chatroom / db / jettyserver / redis / service / task / tomcatserver）

**说明**：仓库内数据库口令、登录账号等凭据均为演示占位值；DB 类 Demo 需自行准备 MySQL 并建库；Web Demo 的 HTTPS 需自备证书（仓库不再附带 keystore）。

---

## Demo 与自测

| 场景 | 位置 | 说明 |
|------|------|------|
| 消息基础 | `demo/.../demo/base/startup.java` | 对象消息/名字互动/工厂创立最小示例 |
| 聊天室 | `demo/.../demo/chatroom/` | socket 聊天室 server + client |
| 数据库 | `demo/.../demo/db/` | DB 消息化 DAO 示例（建库 `tldbdemo1/2`） |
| Redis | `demo/.../demo/redis/` | Redis 消息动作示例 |
| Web | `demo/web/` | Jetty/Tomcat servlet Web 应用 |
| AI Agent 自动化测试 | `cn.tianlong.java.demo.aiagent.AiAgentDemoStartup` | 9 场景 Mock 测试（无 Key 可跑） |
| AI Agent 交互聊天 | `cn.tianlong.java.demo.aiagent.AIStart` | 控制台聊天（`/stream` `/thinking` `/sessions` `/test` 等命令） |

---

## 日志

使用 log4j2 + SLF4J。配置文件在 `conf/` 目录内，需在 `main()` 启动前设置系统属性：

```java
System.setProperty("log4j.configurationFile", configPath + "log4j2.xml");
```

`<configuration status="error">` 可消除 log4j2 自身启动噪音；多文件分级的 ThresholdFilter 独占式用法见各 demo 的 log4j2.xml 示例。

---

## 许可证

[Apache License 2.0](LICENSE)

项目坐标：`cn.tianlong.tlobject:tlobject:1.0`
