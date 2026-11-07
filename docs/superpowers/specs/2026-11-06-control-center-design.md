# 控制中心（TLControlCenter）一期设计

- 日期：2026-11-06
- 状态：待评审
- 范围：一期（基础设施收口），不含用户授权、服务状态、编排能力

## 1. 背景与目标

当前框架里：

- `TLObjectFactory` 负责创建模块并执行 `factoryBoot`，但"启动序列"和"创建"耦合在一起，没有责任主体；
- `TLModuleRegistry` 是被动注册表，谁都可以直接注册/查询；
- `TLAgentService` 是事实上的转发器（39 个 action），但每个 handler 自己做决定，没有统一策略与审计；
- 配置热加载 `reloadModuleConfig` 的判据只有"模块存在且 configFile 非空"，没有任何可加载范围限定；
- `TLAuthModule` 只覆盖工具批执行，没有覆盖控制类操作。

一期目标是引入**工厂级控制中心**，把"启动编排、模块注册、配置重载、审计"四件事收口到一个责任主体，同时不改变消息寻址模型：聊天仍走 `agentService → agent` 直连，控制中心只做**门与归属**，不做消息中转。

### 非目标（后续阶段）

- 用户角色与命令授权（复用 `TLAuthModule`）
- 服务状态 `RUNNING / DRAINING / PAUSED`
- 模块自述 reload 契约（manifest）
- Agent 健康检查、限流、依赖编排
- `TLAppStartUp` 改造、create/start 生命周期拆分

## 2. 架构与启动分层

采用两层责任划分：

- **进程/应用层**：`TLAppStartUp` 保持不变，负责创建工厂、解析应用配置、多应用拓扑；
- **工厂层**：新增 `TLControlCenter`（core，每工厂一个，单例），负责该工厂内的启动编排、注册门面、reload 治理与审计。应用专属策略不放进 core：AI 应用使用 `aiagent/common` 的 `TLAgentControlCenter extends TLControlCenter` 承载用户分级、服务暂停、agent 编排等策略；其他应用可直接使用 core 版本或自建子类。

### 启动分层

| 层 | 内容 | 位置 |
|---|---|---|
| L0 | 工厂自身：配置解析、ClassLoader、日志模块 | 工厂初始化 |
| L1 | 基础设施：`logThreadPool` → `threadPool` → `database` | `<factoryBoot>` |
| L2 | 控制中心 | `<factoryBoot>` 最后一个 |
| L3 | 应用模块：`dbSessionManager`、provider、`agentService`、`chatConsole`、`aiagent_master` 等 | 控制中心的 `<startMsg>` |
| L4 | Web 追加：`webui` → `startWeb` → `jettyServer` | web 版控制中心的 `<startMsg>` |

**依赖方向单向**：控制中心可以依赖 L0/L1；应用模块可以依赖控制中心与基础设施；反向不允许。将来若控制中心需要某个应用模块，说明它实际属于基础设施，应提升到 L1。

### 启动机制

1. 工厂执行 `factoryBoot`：先创建基础设施模块，再创建 `controlCenter`；
2. `controlCenter` 作为单例创建后已进入工厂模块表；
3. 控制中心覆盖 `runStartMsg()`：把自身的 `<startMsg>` 列表交给工厂的 `bootstrapModules` action，由**工厂以自身为发送者**逐条执行 `getModule`。这样应用模块的 `familyName` 保持裸名（如 `aiagent_master`），不会因为经控制中心转发而被加上 `controlCenter:` 前缀；
4. 工厂创建各模块并执行它们的 `runStartMsg`——**创建职责仍在工厂，启动序列责任在控制中心**；
5. 单例命中时 `getModule` 直接返回、不会重复执行 `startMsg`；控制中心在执行启动序列时已可被寻址，重入安全；
6. 失败语义不变（`isNecessary=yes` 仍走工厂的启动失败路径）。

不修改 `TLObjectFactory`、`TLAppStartUp`。

## 3. 模块与消息契约

- 类：`cn.tianlong.tlobject.modules.TLControlCenter`
- 模块名：由工厂 `commonParams` 的 `controlCenterName` 指定，缺省 `controlCenter`
- 单例：`singleton="true"`
- 内部持有私有子模块 `moduleRegistry`（familyName = `controlCenter:moduleRegistry`）

### Actions

| Action | 参数 | 行为 |
|---|---|---|
| `register` | `key`、`instance` | 转发给内部 registry |
| `get` | `key` | 转发给内部 registry |
| `list` | `ownerName`（可选） | 转发给内部 registry |
| `unregister` | `key` | 转发给内部 registry |
| `reloadModuleConfig` | `name`、`userId` | 白名单校验后转发 `reloadConfig`，记录审计 |
| `auditList` | `limit`（可选） | 返回最近的控制操作审计记录 |

动作名与现有 registry 保持一致，调用方只改消息 destination，不改 action。

`reloadModuleConfig` 与 `auditList` 由 `TLAgentService` 暴露给 UI：控制台 `/reloadconfig`、`/audit [条数]`；WebUI 走 `/api/command`（action=`reloadModuleConfig` / `audit`）。

## 4. 配置形态

### 4.1 工厂公共参数

```xml
<commonParams>
    ...
    <controlCenterName value="controlCenter"/>
</commonParams>
```

所有模块创建时都会收到该参数（`getModuleParam` 会合并 `commonParams`）。

**兼容规则**：只有采用控制中心的应用才配置 `controlCenterName`。参数缺失时模块不注册（不再回落旧 `moduleRegistry`）；配置了就直接发给控制中心。

### 4.2 工厂启动序列（chat）

```xml
<factoryBoot>
    <msg action="getModule" destination="moduleFactory" moduleName="logThreadPool"/>
    <msg action="getModule" destination="moduleFactory" moduleName="threadPool" usePreReturnMsg="false"/>
    <msg action="getModule" destination="moduleFactory" moduleName="database" usePreReturnMsg="false"/>
    <msg action="getModule" destination="moduleFactory" moduleName="controlCenter"/>
</factoryBoot>
```

### 4.3 控制中心配置

```xml
<moduleConfig>
    <params>
        <!-- 可重载配置的模块白名单；* = 全部，空 = 全部禁止 -->
        <reloadableModules value="outputValidator"/>
    </params>
    <modules>
        <!-- 由控制中心 init() 通过 getMyModule 创建为私有实例（familyName=controlCenter:moduleRegistry），
             singleton 属性不生效；不注册到工厂，外部只能经控制中心访问 -->
        <module name="moduleRegistry"
                classfile="cn.tianlong.tlobject.modules.TLModuleRegistry"/>
    </modules>
    <startMsg>
        <msg action="getModule" destination="moduleFactory" moduleName="dbSessionManager"/>
        <msg action="getModule" destination="moduleFactory" moduleName="myopenAiProvider"/>
        <msg action="getModule" destination="moduleFactory" moduleName="agentService"/>
        <msg action="getModule" destination="moduleFactory" moduleName="chatConsole"/>
        ...
        <msg action="getModule" destination="moduleFactory" moduleName="aiagent_master"/>
    </startMsg>
</moduleConfig>
```

### 4.4 Chat / Web 两套启动序列

- chat：`controlCenter_config.xml`（控制台应用模块）
- chat_web：`controlCenter_web_config.xml`（在 chat 应用模块后追加 `webui`、`startWeb`、`jettyServer`）
- web 工厂配置通过覆盖配置文件选择 web 版：

```xml
<modulesParams>
    <module name="controlCenter" configfile="controlCenter_web_config.xml"/>
</modulesParams>
```

已验证 `getModuleParam` 会合并 `modulesParams`，且 `configfile` 取值优先使用合并后的参数。

### 4.5 aitest

aitest 没有 `threadPool` 与 `database`：

- `factoryBoot` = `logThreadPool` + `controlCenter`
- 其余模块（含 `runAllTests`）进入 `controlCenter_aitest_config.xml` 的 `<startMsg>`

## 5. 注册收口

### 5.1 统一寻址方式

新增参数键常量（`TLParamString`）：

```java
String CONTROLCENTER_NAME = "controlCenterName";
```

注册是**部分模块**的需求：需要注册的 aiagent 模块在自己的注册方法里直接检查 commonParams（多处调用的类内部各有一个私有小封装，单次调用的直接内联）：

```
name = params.get(CONTROLCENTER_NAME)
if name 非空: putMsg(trim(name), msg)
else: 不注册
```

控制中心名在各模块 `setModuleParams()` 中解析一次存字段，注册点只读字段（避免每次注册重复查 param）。字符串参数统一用 `TLBaseModule.getParamNonEmpty(key[, default])`：null/空白视为未配置，返回 trim 后的值——配置是人写的，容错但不改语义。不做工厂存在性检查——启动分层保证控制中心在 `factoryBoot` 里先于所有会注册的模块启动。core 基类不感知控制中心。

依据：启动分层保证控制中心在 `factoryBoot` 里先于所有会注册的模块启动；未配置控制中心的应用不注册。

### 5.2 调用点迁移

调用方只把消息 destination 从 `moduleRegistry` 改为控制中心名，action 保持不变：

| 文件 | 位置（方法） |
|---|---|
| `TLAiAgent.java` | `registerToRegistry`（自注册、provider、memory、context） |
| `TLToolManager.java` | 工具注册、热加载注册、卸载（两处） |
| `TLAgentGroup.java` | 成员注册、注销 |
| `TLAgentWorkflow.java` | 节点模块注册 |
| `TLAgentService.java` | 家族名查找、agents/skills 列表、tasks 定位、mcp 列表、param 查询 |
| `TLEvalsModule.java` | 模块列表、按 key 查找 |
| `TLEvalCaseGenerator.java` | 按 key 查找、列表 |

约 19 处，集中在一个变更内完成。迁移后用 grep 静态验收：业务代码不再出现 `putMsg(DEFAULTMODULEREGISTRY, ...)`（控制中心内部转发与测试除外）。

### 5.3 关于共享配置中的顶层注册表声明

**保留** `cn.tianlong.tlobject.modules_config.xml:21` 的 `moduleRegistry` 声明，不删除：

- 模块是懒创建的；只要没有调用方按裸名 `moduleRegistry` 发消息，顶层实例不会被创建；
- 控制中心的私有子注册表是唯一被实例化的注册表，不存在"两个注册表"；
- 保留声明对旧配置/第三方部署更安全。

### 5.4 约束

- 注册只能通过控制中心，这是编程约定；
- 不增加运行时守卫（`ownerFamily` 校验）；
- 风险：业务代码在注册点直接检查 `controlCenterName`；若遗漏某处直接访问裸名 `moduleRegistry`，会创建顶层旧注册表。用 grep 验收 + 启动冒烟覆盖，不引入额外运行时机制。

## 6. 配置重载治理

### 6.1 流程

```
console / webui
    → agentService.doReloadConfig
        → controlCenter.reloadModuleConfig(name, userId)
            1. 解析目标（registry 家族名 → 工厂已存在实例，不隐式创建）
            2. 校验：目标存在 / 有 configFile / 命中白名单
            3. 转发 reloadConfig，汇总结果
            4. 写审计（允许 / 拒绝 / 结果）
    ← 保持现有成功/失败返回结构
```

### 6.2 白名单

- 配置：控制中心 `<params><reloadableModules value="..."/></params>`
- 匹配：请求名、目标 `familyName`、目标 `name` 三者任一命中；`familyName` 优先
- `*`：全部放行（开发模式）
- 空值：全部拒绝
- 保留现有校验：模块必须已运行、必须有配置文件

### 6.3 兼容与旁路

- `agentService` 读取 `controlCenterName`；配置了就委托控制中心，未配置保留旧路径（不做存在性检查——启动分层保证控制中心先启动）；
- 已知旁路：`TLMonitorConfigModule` 的自动 reload 直接发送 `reloadConfig`。一期记录该边界，二期统一收口。

## 7. 审计

- 每次配置重载操作写一条结构化 INFO 日志，并进入内存环形缓冲（默认 200 条）；
- 字段：时间、userId、action、target、allowed（策略是否放行）、success（执行是否成功）、result；
- `auditList` 返回最近记录（副本）；控制台 `/audit [条数]` 与 WebUI `/api/command`（action=audit）可直接查询；
- 一期不落库。控制中心启动时 L1 的日志线程池与数据库已就绪，二期落库不需要调整启动顺序。

## 8. 兼容与迁移顺序

1. 新增 `TLControlCenter` 与配置；
2. 移动 chat / chat_web / aitest 的启动序列（基础设施留在 `factoryBoot`，应用模块进 `<startMsg>`）；
3. 迁移注册调用点到控制中心；
4. `agentService.doReloadConfig` 委托控制中心；
5. 增加自测与回归。

步骤 2–4 需在同一个变更内完成（注册表分裂问题），其余可独立评审。

未配置 `controlCenter` 的应用：`factoryBoot` 原样执行，注册调用直接跳过（不注册），reload/audit 走旧路径或报未配置。

## 9. 验证方案

### 新增自测

1. 注册门面：`register/get/list/unregister` 往返与现有语义一致；
2. reload 治理：白名单允许、拒绝、目标不存在、无 configFile；
3. 启动：`<startMsg>` 顺序执行；单例不重复 start；
4. 兼容：未配置控制中心的应用照常启动；
5. 审计：允许/拒绝均产生记录，`auditList` 可读。

### 回归

- `/test` 22/22
- aitest 36/36
- chat 控制台启动冒烟：模块创建顺序正确、无 shutdown
- chat web 冒烟：登录 → 对话 → reload 白名单内/外各一次
- 静态验收：grep 无残留直连 `moduleRegistry` 的业务调用

## 10. 风险

| 风险 | 对策 |
|---|---|
| 启动重入（控制中心在 startMsg 中回调工厂创建模块） | 单例已在工厂模块表后再执行 startMsg；自测覆盖启动顺序 |
| 漏改注册调用点导致注册分裂 | 原子迁移 + grep 验收 + 启动冒烟 |
| chat/web 启动序列配置重复 | web 通过 `modulesParams` 覆盖 configfile；两份配置只保留差异 |
| 控制中心成为新的 God object | 一期只做四件事；授权/状态/编排明确留到后续阶段 |

## 11. 后续路线

- **二期**：用户角色与命令授权（复用 `TLAuthModule`）；模块自述 reload 契约（manifest）
- **三期**：服务状态机（`RUNNING / DRAINING / PAUSED`）与 UI 通知
- **四期**：健康检查、限流、依赖编排
