# TLObject — 统一对象消息编程框架

[English](README.md) | **中文**

> **万物皆独立、自由、平等；万物皆由造物主所创。**
> 在 TLObject 的世界里，"造物主"就是统一对象工厂——每个对象由它创建、由它命名、由它赋予生命。
> 对象之间从不"调用"彼此，只通过**消息**交流。

作者：天龙 · 博客：https://blog.csdn.net/tianlong117

---

## 目录

- [哲学思想](#哲学思想)
- [特点与优势](#特点与优势)
- [快速开始](#快速开始)
- [配置体系](#配置体系)
- [模块与子框架](#模块与子框架)
- [Demo 与自测](#demo-与自测)
- [日志](#日志)
- [许可证](#许可证)

---

## 哲学思想

TLObject 的架构思想源于对自然与人类社会关系的观察：

**一、万物皆独立、自由、平等**

自然界中，万物各自独立存在——它们不互相持有、不互相支配，而是通过"交流"协作：人与人对话、细胞间传递信号、国家之间互通文书。没有谁凌驾于谁之上，每个个体都按自己的规则回应外界。

传统面向对象编程恰恰相反：一个对象**持有**另一个对象的引用并直接调用其方法（`a.func()`），调用者凌驾于被调用者之上，二者被"引用"牢牢捆绑——改一方，另一方就可能崩坏。

消息对象编程推翻了这种关系。对象之间**不互相持有、不直接调用**，唯一的互动方式是发送**消息**：消息承载着意图（`action`）与内容（参数）发给对方；接不接、怎么回应，由对方自己决定。于是：

- 每个对象都是**独立**的——不知道也不关心谁给自己发过消息
- 每个对象都是**自由**的——可以随时被替换、被复用、被单独测试，而它的"伙伴"毫无感知
- 每个对象都是**平等**的——没有调用者与被调用者的等级，只有发消息者与收消息者

**二、万物皆由造物主创造**

万物不是自己冒出来的，也不是被某个"使用者"随手 new 出来的，而是由统一的创造者所创。在软件中，这个角色由 **TLObjectFactory（统一对象工厂）** 扮演：

- 工厂按**配置**（而非代码）决定创建哪些对象、用什么实现类、是单例还是多例
- 工厂为每个对象**命名**——名字是对象在这个世界的地址
- 工厂负责对象的完整生命周期：创建 → 注入参数 → 初始化 → 启动 → 销毁

对象不需要知道"我是被谁使用的"，使用者也不需要知道"对象是怎么被创建的"。双方都只认**名字**和**消息**：

```java
putMsg("worker", msg);        // 无需 new —— 工厂早已把它创好、命名好、启动好
```

**由此推导出的三条实践原则：**

| 原则 | 含义 | 代码形态 |
|------|------|----------|
| 消息互动 | 对象之间只通过消息协作，无直接方法调用 | `TLMsg msg; putMsg(A, msg);` |
| 名字互动 | 用名字指代对象，无需持有实例引用 | `putMsg("worker", msg);` |
| 工厂创立 | 对象的创建与生命周期交给统一工厂 | 全部写在 `{xxx}_config.xml` |

> 哲学落地为框架，即是 TLObject：一个把"对象世界"变成可配置、可观察、可热替换的运行时。

---

## 特点与优势

### 与传统调用式编程的对比

| 维度 | 传统面向对象（方法调用） | TLObject（消息对象编程） |
|------|--------------------------|--------------------------|
| 互动方式 | 持有引用，直接 `a.func()` | 发送消息 `putMsg("a", msg)` |
| 耦合度 | 强耦合：改实现必须改调用方 | 零引用耦合：换实现只改配置 |
| 对象创建 | 调用方到处 `new` | 统一工厂创建，调用方零感知 |
| 依赖方向 | 高层依赖低层具体类 | 全凭名字+消息，无编译期依赖方向 |
| 更换实现 | 动代码、重新编译 | 改一行 XML（支持运行期热加载） |
| 拦截扩展 | 需要代理/AOP 框架 | 原生 `beforeMsgTable` / `afterMsgTable` 钩子 |
| 异步并发 | 自行写线程 | 框架原生：异步消息、线程池、队列、总线 |

### 框架级能力

- **松耦合到极致**：模块间无编译依赖。换数据库、换缓存实现、换第三方服务，调用方代码一行不改
- **声明式装配**：对象世界全部由 XML 描述——模块定义、参数、消息路由表、拦截钩子、启动序列，一目了然、可审计、可热加载
- **消息的富语义**：一条 `TLMsg` 原生支持同步/异步/延时发送、链式延续（`nextMsg`）、路由表分发（`msgTable`）、发布订阅（`TLMsgBus`）、广播、阻塞队列——异步与并发是框架的内建能力，而非事后补丁
- **统一生命周期**：`init → runInitMsg → runStartMsg → 运行 → destroy`，工厂托管一切，模块自己只管业务
- **完整生态**：数据库 DAO、Jetty/Tomcat/Netty、WebSocket、HTTP、缓存、Redis、定时任务、日志、Excel……15 个顶层模块、40+ 开箱即用的组件（见[模块与子框架](#模块与子框架)）
- **AI Agent 子框架**：在消息模型之上构建的完整智能体框架——多轮对话、工具调用、记忆、多子 Agent 编排、MCP、HITL 审批（见 [aiagent/README.zh.md](aiagent/README.zh.md)）
- **工程友好**：内置日志分级、运行监控、参数校验、会话数据、异常处理；配置即文档，新人看 XML 就能理解系统结构

---

## 快速开始

**前置要求**：JDK 17+、Maven 3.6+（Windows / macOS / Linux）

```bash
# 1. 编译并安装全部模块（约 1 分钟）
mvn clean install -DskipTests
```

### 体验一：跑 AI Agent 自动化自测（零配置）

Mock Provider 驱动，**无需 API Key、无需联网、无需数据库**，直接验证框架可用性：

```bash
# 生成运行 classpath 并启动（JDK 17 环境）
mvn -pl demo/tlobject dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -Dfile.encoding=UTF-8 \
     -cp "demo/tlobject/target/classes;$(cat demo/tlobject/target/cp.txt)" \
     cn.tianlong.java.demo.aiagent.AiAgentDemoStartup
# 日志中 [TEST] 汇总行即为 12 个测试场景结果（全部通过=框架正常）
```

### 体验二：跑最小消息示例

```bash
java -Dfile.encoding=UTF-8 \
     -cp "demo/tlobject/target/classes;$(cat demo/tlobject/target/cp.txt)" \
     cn.tianlong.java.demo.base.startup
```

### 体验三：看"对象世界"是如何被配置出来的

消息框架的一切都始于配置文件。一个最简"世界"长这样（示意，完整示例见 `demo/tlobject/src/main/resources/conf/demo/`）：

```xml
<!-- moduleFactory_config.xml：向工厂声明要创建哪些对象 -->
<moduleConfig>
    <modules>
        <module name="worker"
                classfile="cn.tianlong.demo.worker.Worker"
                singleton="true"/>
    </modules>
</moduleConfig>
```

然后，在代码里与这个对象交流——**不 new、不持有引用，只发消息**：

```java
TLMsg msg = new TLMsg();
msg.setAction("work");                    // 消息：意图
msg.setParam("task", "搬运货物");          // 消息：内容
TLMsg result = putMsg("worker", msg);     // 发给名为 worker 的对象（同步等待返回）
String answer = result.getParam("result"); // 对象按自己的规则回应
```

应用入口：继承 `TLAppStartUp`，指定配置目录后启动（可传参版，等价于命令行 `-d 配置目录 -m 工厂配置 -f 应用配置 -n 应用名`）：

```java
public class startup extends TLAppStartUp {
    public static void main(String[] args) {
        HashMap<String, String> argsMap = new HashMap<>();
        argsMap.put("appName", "demo0");
        argsMap.put("configPath", CLASSPATH + "/conf/demo/base/");
        argsMap.put("factoryConfigFile", "moduleFactory_config.xml");
        argsMap.put("configFile", "demoappstart.xml");
        TLAppStartUp.main0(argsMap);
        TLAppStartUp.shutdown();           // 优雅关闭：工厂统一销毁全部对象
    }
}
```

> 开发期推荐直接用 IDE（IDEA / Eclipse）打开根 `pom.xml`，运行 `demo/tlobject` 下各场景的 `main` 即可。

### 配置 API Key（仅聊天类 Demo 需要）

仓库内 LLM Provider 配置的 `apiKey` 均为占位符 `sk-your-api-key`。运行聊天 Demo 前，请把 `demo/tlobject/src/main/resources/conf/demo/aiagent/` 下各 `*_config.xml` 中的占位符替换为你自己的密钥（Provider 与模型名同样在这些配置中指定）。**切勿将真实密钥提交到公开仓库。**

---

## 配置体系

配置文件均为 XML，约定命名 `{模块名}_config.xml`，由 `TLModuleConfig` 解析：

| XML 元素 | 作用 |
|----------|------|
| `<modules>` | 定义对象：`name` 对象名、`classfile` 实现类、`singleton` 单例、`configfile` 子配置 |
| `<params>` | 工厂/模块参数 |
| `<modulesParams>` | 按模块注入参数（如 `<module name="aiContext" maxContextMessages="50"/>`） |
| `<paramsModules>` | 按参数批量指派模块（如 `<param name="loglevel" value="debug" modules="a;b"/>`） |
| `<initMsg>` / `<startMsg>` | 初始化/启动时工厂自动发送的消息序列（对象一出生就开始"交流"） |
| `<msgTable>` | 消息路由表：`msgid` → 多条消息（顺序或并行、可设超时） |
| `<beforeMsgTable>` / `<afterMsgTable>` | 按动作名的前置/后置钩子（记忆注入、审批、审计等横切逻辑的家） |
| `<include>` | 引入其它配置（`file="CLASSPATH/conf/..."`，支持按单元合并） |

配置的组织（以 `demo/tlobject/src/main/resources/conf/` 为例）：

- `conf/tlobject/` — 各应用共享的"全模块"组合配置
- `conf/demo/` — 按场景自包含的配置目录（aiagent / aitest / base / db / jettyserver / redis / service / task / tomcatserver），每个场景一个独立"世界"

**说明**：仓库内 DB 口令、登录账号均为演示占位值；DB 类 Demo 需自备 MySQL 建库（SQL 见 `demo/`）；Web Demo 的 HTTPS 需自备证书。

---

## 模块与子框架

### 顶层模块矩阵

| 模块 | 说明 |
|------|------|
| `core` | **消息框架心脏**：IObject / TLMsg / TLBaseModule / 工厂 / 生命周期 / 路由 / AOP 钩子 / 线程池 / 消息总线 / 广播 / 队列 / 监控 / 会话数据 / 参数校验 / 异常处理 / 应用启动器 |
| `aiagent` | **AI Agent 子框架**：common + provider（OpenAI/DeepSeek/Claude）+ skill + memory + webui（见下） |
| `database` | 消息化数据访问：CRUD / 事务 / 建表 / DAO 模式，支持 MySQL / PostgreSQL / SQLite |
| `connectpoolofdb` | 数据库连接池：C3P0、HikariCP、SQLite |
| `network` | 网络层：Jetty / Tomcat / Netty 服务端、WebSocket 客户端与服务端、HTTP 客户端、代理、JWT 鉴权 |
| `cache` | 缓存：EhCache、Caffeine、文件缓存、内存缓存 |
| `redis` | Redis 客户端，消息动作化封装 |
| `servletutils` | Servlet 工具：Web 通道（上传 / SSE / 路由分发） |
| `execl` | Excel 导出 |
| `appwebmanager` | 基于 Web 的管理端应用 |
| `crontab` | Cron 表达式定时任务 |
| `log` | 日志模块（log4j2 + SLF4J 桥接） |
| `utils` | 通用工具 |
| `tlobject-all` | 聚合件：一个依赖引入全部模块 |
| `demo` | 可运行示例（`tlobject` 消息场景 + `web` Web 应用场景） |

### AI Agent 子框架（`aiagent/`）

在消息对象模型之上构建的**完整智能体框架**：Agent 接收用户输入 → LLM 分析意图 → 调用 Skill/工具执行 → 返回结果。

- 流式 / 非流式双通道、多轮对话、三类模型路由与意图缓存
- 工具体系：内置 Skill（HTTP / 文件 / 代码执行 / 浏览器 / 桌面 GUI）+ 自定义 Skill + MCP 工具市场 + 消息工具（msgTool）
- 三级记忆：会话上下文、短期记忆（TTL）、长期记忆（文件/DB，摘要分层 + 向量召回可选）
- 多 Agent 编排：子 Agent 委托、Agent 组、工作流 DAG、错误自愈监管
- 工程能力：HITL 人工审批门禁、Evals 评测体系、会话持久化与断点恢复、Token 统计、Web 交互界面（webui）、`/test` Mock 回归测试
- 详细文档（约 1300 行，含架构图/核心类/扩展开发）：**`aiagent/README.zh.md`**

---

## Demo 与自测

| 场景 | 位置/入口 | 说明 |
|------|-----------|------|
| 消息最小示例 | `cn.tianlong.java.demo.base.startup` | 消息互动/名字互动/工厂创立 |
| 数据库 | `demo/.../demo/db/` | 消息化 DAO（建库脚本 `tldbdemo1/2.sql`） |
| Redis | `demo/.../demo/redis/` | Redis 消息动作 |
| Web | `demo/web/` | Jetty / Tomcat Web 应用 |
| AI Agent 自测 | `cn.tianlong.java.demo.aiagent.AiAgentDemoStartup` | 12 场景 Mock 测试，零配置可跑 |
| AI Agent 交互 | `cn.tianlong.java.demo.aiagent.AIStart` | 控制台聊天（`/stream` `/thinking` `/sessions` `/test` 等命令） |

---

## 日志

log4j2 + SLF4J。配置在 `conf/` 内，启动前设置系统属性：

```java
System.setProperty("log4j.configurationFile", configPath + "log4j2.xml");
```

`<configuration status="error">` 可消除 log4j2 自身启动噪音；每级别独占文件的 ThresholdFilter 写法见各 demo 的 `log4j2.xml`。

---

## 许可证

[Apache License 2.0](LICENSE) · 项目坐标：`cn.tianlong.tlobject:tlobject:1.0`
