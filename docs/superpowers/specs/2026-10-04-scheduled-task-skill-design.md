# 定时任务技能（schedule_task）设计

日期：2026-10-04
状态：已确认，待实施

## 1. 目标与背景

用户在聊天窗口里说一句自然语言要求（如"每天早上 8 点查一下上证指数并告诉我"），
LLM 把它转成结构化参数，调用新技能 `schedule_task` 注册到框架的定时任务模块
`TLMsgTask`（crontab 模块，框架注册表名为 `msgTask`）。到点后任务执行，结果
**写回用户当前活动会话并实时推送到聊天窗口**。

已确认的需求决策：

| 决策点 | 结论 |
|---|---|
| 任务类型 | 两种都要：`agent`（自然语言 → 完整 LLM 轮次）与 `message`（固定消息，不经 LLM） |
| 结果可见性 | 写回会话（持久化）+ 实时推送（web SSE + 控制台） |
| 持久化 | 要：进程重启后自动恢复存活任务 |
| 恢复时机 | 启动时自动（技能 `runStartMsg`） |
| 用户隔离 | 按用户隔离（`data/<userId>/`，与会话/记忆/断点同约定） |
| 执行落点会话 | 用户当前活动会话；无法确定（离线/会话忙）时回退创建时会话 |

## 2. 现有设施（已核实）

- **`TLMsgTask`**（`crontab/src/main/java/cn/tianlong/tlobject/modules/TLMsgTask.java`）：
  消息调度器。任务 = 一条 `TLMsg` + 调度参数。动作：`registTask` / `unRegistTask`
  / `startTask` / `stopTask` / `getTasks` / `shutdown`。参数：`msg`（TLMsg）、`taskid`、
  `cronExp`、`delay`、`timeUnit`（ms/s/m/h）、`begin`、`times`、`status`。
- **`TLBaseSkill`**（`aiagent/common/.../TLBaseSkill.java`）：技能基类。函数名
  `skillName`、参数 schema `parameterSchema`、入口 `skillExecute`、输入
  `AI_P_SKILLINPUT`、输出 `AI_P_SKILLOUTPUT`。
- **技能实例是 per-agent 的**：`TLAiAgent.initModules` 用 `getMyModule` 创建
  （不工厂注册），创建后工厂立即调 `runStartMsg()`。
- **身份透传**：`TLToolExecutor` 把 `sessionId` / `userId` 作为系统参数透传给技能
  （`TLToolExecutor.java:378-382`）；agent 侧 `TLAiAgent.doChat` 读
  `getSystemParam("userId", sessionId)`（`:772,776`，缺省回退 sessionId）。
- **模块懒加载**：`putMsg("taskScheduler", …)` → `getModule()`，不存在即创建
  → "启动定时任务模块"天然发生，无需显式启动。
- **web 推送设施**：`/api/events` 是用户级 SSE 长连接
  （`TLWebChatModule.registerEventsChannel`，多标签页并存）；事件经 msgBus topic
  发布（如 `approvalEvent`），web 按 `sessionId → userId` 定位属主推送；前端
  `app.js` 的 `EventSource` 按 `evt.type` 分发。
- **会话表**（`TLWebChatModule`）：`sessionOwner`（sessionId→userId）、
  `streamWriters`（userId:sessionId→流式 writer）、`sessionLogin`（sessionId→loginId）。

## 3. 总体架构

```
聊天窗口："每天早上8点查上证指数告诉我"
   ↓  LLM 转成参数
TLScheduleTaskSkill（per-agent，函数名 schedule_task）
   ├─ 校验参数（cron 合法性等）
   ├─ 构造任务项（agent 型：destination=<agent>、systemArgs.targetInstance=本技能实例）
   ├─ putMsg("taskScheduler", registTask + startTask)     ← 懒加载，模块自启
   └─ 写持久化文件 data/<userId>/scheduled_tasks/<agent>.json
   ↓ 到点
TLMsgTask.executeTask → putMsg 任务消息
   → 框架通用投递（消息带实例引用时 agent 直投子模块）→ 本技能 → chat 轮次（或固定消息）
   ↓ 结果
写回目标会话（dbSessionManager 落盘）
   ↓
msgBus topic "taskResult" → web SSE 推送（当前窗口追加）+ 控制台订阅打印
```

> **子模块投递（通用机制，非技能专属）**：技能是 agent 的私有子模块，外部**按名寻址够不着**。
> 发起方（技能自身 / agentService）把**目标实例引用**放进消息 systemArgs（`targetInstance`），
> agent 见到引用即直投该实例（`TLAiAgent.dispatchToInstance`），动作名原样保留、子模块自行分发。
> agent 与 toolManager **不含任何技能专属逻辑**；实例不可序列化，故入库的任务消息不带引用，
> 但恢复路径（runStartMsg→buildRegistMsg）由技能重建消息、自动补上引用。

## 4. 技能设计

### 4.1 类与模块

- 新类：`cn.tianlong.tlobject.aiagent.skill.builtin.TLScheduleTaskSkill
  extends TLBaseSkill`，放 `aiagent/skill-builtin`。
- 函数名（LLM 可见）：`schedule_task`。
- 配置参数：`msgTaskModule`（默认 `"taskScheduler"`）、`storageRoot`（默认 `"./data/"`）。

### 4.2 操作（op）

| op | 说明 | 必填 |
|---|---|---|
| `create` | 创建并注册任务 | `description` + 调度参数 + 任务内容（`prompt` 或 `module`+`action`） |
| `list` | 列出当前用户在本 agent 下的任务（含状态、上次/下次执行时间、执行次数） | — |
| `remove` | 删除任务（unRegistTask + 清持久化） | `task_id` |
| `update` | 改调度（重建）或暂停/恢复（`enabled`） | `task_id` + 要改的字段 |

### 4.3 create 参数

| 参数 | 说明 |
|---|---|
| `description` | 任务描述（人类可读；未给 task_id 时用于生成） |
| `task_id` | 可选，自定义 id |
| `prompt` | agent 型任务内容（自然语言） |
| `module` + `action` + `args` | message 型任务内容（固定消息） |
| `cron` | cron 表达式（6 位，quartz 风格，如 `0 0 8 * * ?`） |
| `delay` + `unit` | 固定间隔（unit: s/m/h，默认 s） |
| `times` | 执行次数（0/缺省 = 不限） |
| `begin` | 首次执行前延迟（秒） |
| `one_shot` | 一次性任务（等价 delay + times=1） |

**调度模式判定**（技能显式处理，不依赖引擎缺省）：
- 给了 `cron`（无论是否同时给 `delay`）→ cron 周期任务；`delay` 同时给时作首触发下限
- 未给 `cron`、给了 `delay`/`times`/`begin` → 固定间隔任务
- **两者都没有 → 技能直接报错**（引擎在无 cron 无 delay 时默认 `delay=60` 秒、
  周期 60 秒，静默兜底对用户不友好，技能层拒绝并要求明确调度）

类型判定：给了 `prompt` → `agent` 型；给了 `module`+`action` → `message` 型。
两者都给则报错（避免歧义）。

### 4.4 任务 id 规则

`<ownerAgent>/<taskId>`：
- `ownerAgent`：技能的家族名最后一段（即所属 agent 名）。
- `taskId`：用户指定或自动生成（`task_<时间戳>` 或按 description 生成短名）。
- 前缀化的原因：`taskScheduler` 是工厂单例，多 agent 共用，防跨 agent 撞车；
  `list`/`remove`/`update` 按前缀过滤只看自己的。

### 4.5 任务内容构造

**agent 型**（任务消息 = "回到技能"的回调信封，实例引用由本技能自备）：
```java
TLMsg taskMsg = createMsg()
    .setDestination(ownerAgent)          // 如 "aiagent_master"（框架按名路由到 agent）
    .setAction("runScheduledTask")       // 技能动作名，agent/toolManager 原样透传
    .setParam("taskPrompt", prompt)
    .setParam("taskId", fullId)
    .setParam("recordSession", creationSessionId);
taskMsg.setSystemParam("targetInstance", this);   // 通用投递：agent 见到引用直投本技能
taskMsg.setSystemParam("sessionId", ...);         // 身份（systemArgs 区）
taskMsg.setSystemParam("userId", ...);
```
说明：技能是 agent 私有子模块、按名够不着，故用**实例引用**寻址（见 §3 注 与 §11.4）。
`sessionId`/`userId` 必须放 **systemArgs 区**，与 `TLToolExecutor` 透传、
`TLAiAgent.doChat` 读取的层级一致；`TLMsg.copyFrom` 复制 systemArgs（`TLMsg.java:520`），
到点时引用与身份原样保留。

**message 型**：
```java
TLMsg taskMsg = createMsg()
    .setDestination(module)
    .setAction(action);
taskMsg.addArgs(args);                   // 可选的固定参数
```

注册参数**必须挂在内层任务消息上**（引擎 `doRegistTask`/`startTask` 只读内层；
外层仅承载 `action=registTask` + `msg`）：
```java
taskMsg.setParam("taskid", fullId).setParam("status", "run")
       .setParam("cronExp"|"delay"|"timeUnit"|"period"|"times"|"begin", ...);
TLMsg regist = createMsg().setAction("registTask").setParam("msg", taskMsg);
```

## 5. 持久化与启动恢复

### 5.1 文件

- 路径：`data/<userId>/scheduled_tasks/<ownerAgent>.json`（`userId` 缺省回退 sessionId，
  与 `TLAiAgent.doChat:776` 的缺省规则一致）。
- 格式：JSON 数组，每条记录：

```json
{
  "taskId": "task_1730000000",
  "owner": "aiagent_master",
  "userId": "tianlong",
  "creationSessionId": "webchat_tianlong_1730...",
  "type": "agent",
  "prompt": "查一下今天上证指数并汇报",
  "module": null, "action": null, "args": null,
  "schedule": { "cron": "0 0 8 * * ?", "delay": 0, "unit": "s", "times": 0, "begin": 0 },
  "enabled": true,
  "createdAt": 1730000000000,
  "executedCount": 0, "lastExecuteTime": 0
}
```

- 写入时机：每次 `create` / `remove` / `update` 后全量覆写（任务量小，简单可靠）。
- 写失败：任务照常在内存中运行，`skillOutput` 里附警告（不因持久化失败丢任务）。

### 5.2 启动恢复

- 挂载点：技能 `runStartMsg()`（工厂创建技能后立即调用，即 agent 启动时）。
- 流程：读本 agent 文件 → `enabled=true` 且 `owner` 匹配的记录逐条
  `registTask` + `startTask` → 幂等守卫（每进程只执行一次）。
- 一次性任务（`times=1`）执行完：记录保留但 `enabled` 置 false（**不自动删**），
  可用 `update` 重新启用；`update` 改调度 = unRegistTask 旧 + registTask 新 + 覆写文件。

## 6. 结果送达（执行目标会话解析 + 推送）

### 6.1 目标会话解析（任务触发时）

```
任务记录里带 userId
  → putMsg("webui", action="getCurrentSession")   ← 新增的只读接口
       在线（currentSessionByUser 命中）→ 得到真实值
  → 拿到 → 在该会话执行 chat
  → 拿不到（无 webui 模块 / 用户不在线）→ 回退 creationSessionId
  → 会话忙（用户正在该会话对话，会话互斥锁会拒）→ 回退 creationSessionId
```

### 6.2 推送

任务执行完由技能发事件到 msgBus（技能 `putMsg("aiagent_master", chatMsg)` 同步
拿到返回消息，取最终回复文本作摘要——任务在调度线程里跑，同步等待不影响交互线程）：

```java
TLMsg evt = createMsg()
    .setDestination("taskResult")           // 总线按 destination 路由到订阅者
    .setParam("userId", userId)
    .setParam("sessionId", <结果所在会话>)
    .setParam("taskId", ...)
    .setParam("text", <结果摘要>);
putMsg("msgBus", evt);
```

- **web 端**（`TLWebChatModule`）：订阅 `taskResult`，按 `sessionOwner` /
  `currentSessionByUser` 定位属主 → 推给该用户的 SSE 连接（复用现有
  `eventChannels`）。前端 `app.js` 加分支：若 `evt.sessionId === state.sessionId`
  → 把结果追加进消息列表；否则提示"结果在会话 xxx"可一键切换。
- **控制台**（`TLChatConsole`）：订阅 `taskResult`，打印一行结果摘要。

### 6.3 新增接口：当前活动会话上报

- **前端**（`app.js`）：页面加载 / 切会话 / 开新会话时上报当前 sessionId
  （新增 action `setCurrentSession`），约 10 行。
- **后端**（`TLWebChatModule`）：新增 `currentSessionByUser`（userId → sessionId）
  Map + `setCurrentSession` / `getCurrentSession` 两个 action。
- **控制台模式**：无 webui 模块，`getCurrentSession` 查不到 → 回退创建会话；
  控制台打印由 msgBus 订阅覆盖（控制台只订阅、不发布；发布方是技能的 taskResult 事件）。

## 7. 配置改动

| # | 文件 | 改动 |
|---|---|---|
| 1 | `demo/tlobject/src/main/resources/conf/demo/aiagent/moduleFactory_chat_config.xml` | 新增 `<module name="taskScheduler" sameClassAs="msgTask" configfile="taskScheduler_config.xml" singleton="true"/>`；boot 可按需加 `getModule` |
| 2 | 新增 `conf/demo/aiagent/taskScheduler_config.xml` | `<params><poolSize value="4"/></params>`（仅参数，无预定义任务） |
| 3 | `aiagent_master_config.xml` 的 `<skills>` | `<skill name="schedule_task" sameClassAs="scheduleTaskSkill" statup="true" msgTaskModule="taskScheduler" skillDescription="…"/>` |
| 4 | `demo/tlobject/src/main/resources/conf/tlobject/cn.tianlong.tlobject.aiagent_config.xml` | 注册技能类：`<module name="scheduleTaskSkill" classfile="cn.tianlong.tlobject.aiagent.skill.builtin.TLScheduleTaskSkill" singleton="true"/>`（与 `scriptExecutionSkill` 等同款） |
| 5 | `aiagent/skill-builtin/pom.xml` | 加 `tlobject-crontab` 依赖（拿 `TLMsgTask` 引用与 `TASK_*` 常量；quartz `CronExpression` 做 cron 本地校验） |
| 6 | `aiagent/webui`（模块 + `app.js`） | `currentSessionByUser` + `setCurrentSession`/`getCurrentSession` action + `taskResult` 前端分支 |
| 7 | `TLChatConsole` | 订阅 `taskResult` + 打印一行 |
| 8 | `crontab/.../TLMsgTask.java`（引擎小补丁） | ① `startTask` 结束时在 config 上设 `nextDatetime`（两类任务都有，见第 10 节）；② `doGetTask` 摘要分支补 `nextDatetime` + `executedCount` 两字段 |

> 说明：不使用框架注册表里的裸名 `msgTask`——裸名是无配置空壳（只声明了 classfile），
> 应用自声明 `taskScheduler` 实例可显式控制池大小并符合既有约定。

## 8. 错误处理

- **参数校验**：cron 用 `org.quartz.CronExpression` 本地校验（技能已依赖 crontab），
  非法直接返回 `Result:false` + 说明，**不注册**；缺必填参数同理。
- **注册失败**：检查 `registTask` 返回的 `RESULT` 参数，失败时回传错误信息。
- **msgTask 模块不可用**：返回明确的配置错误提示（提示检查 `msgTaskModule` 配置）。
- **持久化失败**：任务照常注册（内存运行），`skillOutput` 附警告。
- **回执**：`create` 成功后 `skillOutput` 返回任务 id +**下次执行时间**+ 调度描述，
  LLM 据复述。下次执行时间由引擎计算并返回（见第 10 节），不由 LLM 推算。

## 9. 验证方式

1. **技能级验证（无 LLM 或 mock）**：
   - `skillExecute` 直接驱动技能：`create` 一个短 delay 任务 → `getTasks` 断言任务存在、
     文件字段正确；`remove` → 断言消失；重启恢复（新技能实例读回文件 + 引擎中出现）。
   - 引擎补丁：`create` 后 `getTasks` 摘要含 `nextDatetime`（首次执行前也有）；
     执行一次后 `executedCount` 递增、`nextDatetime` 前进。
   - LLM 侧：`/test`（aitest mock provider）加一个用例，mock 返回 `schedule_task` 的
     toolCall（args 为 create 参数），断言回复含任务 id；随后用 `getTasks` 断言注册成功。
2. **真实端到端**：chat 里说"5 秒后提醒我喝水"→ 等 5 秒 → 确认：
   - web 端聊天窗口出现结果（`taskResult` 推送）
   - 会话历史里能看到该轮次（`/sessions` 或恢复会话）
   - 控制台打印一行结果摘要
3. **恢复验证**：创建任务 → 重启进程 → 确认任务自动重新注册且到点执行。

## 10. 引擎小补丁：下次执行时间对外可见

背景（已核实）：引擎**自己算了下次执行时间**，单任务查询路径已暴露
（`TLMsgTask.doGetTask:315-323`：`nextExecuteTime` / `executedCount` /
`lastExecuteTime` / `lastError`），但**摘要分支只返回四字段**（`:327-338`），
且定时任务的 `nextDatetime` 目前只写 runtime、不写 config
（cron 分支每次执行后才写 config，首触发前 config 里没有）。

两处补丁（`crontab/src/main/java/cn/tianlong/tlobject/modules/TLMsgTask.java`）：

1. **`startTask` 结束时**：在 config 上设 `nextDatetime`（仅当 `future != null` 且为未来时间）——
   - cron 任务：cron 的首次触发时间
   - 固定间隔任务：`now + initialDelay`（首触发在 `initialDelay` 后，**不含 period**；
     `initialDelay` 为 delay 时间单位值、`begin` 路径已转毫秒）
   - 之后两类任务都在**每次执行后刷新**（cron 由调度循环写、固定间隔由 Runnable 写；
     达到次数上限的最后一轮不刷新）
2. **`doGetTask` 摘要分支**：每条 info 补 `nextDatetime`（从 config 取）
   与 `executedCount`（from runtime，缺省 0）

> 技能侧注意：`delay` 只决定首触发与"无周期时"的兜底周期，**循环间隔是引擎的
> `period`（缺省 60 秒）**。所以固定间隔任务注册时必须同时下发 `period=delay`
> （技能 `buildRegistMsg` 中体现），否则"每 5 秒"会变成每 60 秒。

技能侧取值：
- **create 回执**：注册后 `getTasks`（无参）从摘要取该任务 id 的 `nextDatetime`；
  或 `taskid=<id>` 单查（`nextExecuteTime`）。两者等价，择一实现
- **list**：一次 `getTasks` 摘要即含全部任务的时间/次数信息，无需逐条单查

## 11. 用户隔离与安全边界

### 11.1 四层隔离（2026-10-04 实施后核实）

```
① 存储    data/<userId>/scheduled_tasks/<agent>.json   按用户分目录
② 可见性  create/list/remove/update 全走 recordsOf(userId)
          （remove/update 找不到即报"任务不存在"，与"不存在"同文案，防跨用户探测）
③ 执行    agent 型任务的目标会话在触发时解析（webui getCurrentSession 按 userId 查），
          结果只写该用户名下的会话
④ 推送    taskResult 事件按 userId 取 SSE 通道（eventChannels.get(userId)），
          只进该用户自己的连接
⑤ 订阅端  控制台订阅方按 userId 过滤（`evt.userId != 本控制台 userId` 即丢弃）——
          事件总线是进程级的，不过滤会把别的用户（如 web 用户）的任务结果打到控制台
          （2026-10-04 实测修复，提交 `0fb3798`；web SSE 侧本就走 ④ 的按 userId 取通道）
```

### 11.2 边界声明（重要，实施后补充）

- **这是"应用层隔离"，不是"进程级隔离"**：同一进程内换个 userId 参数即可查询另一个用户的
  任务（批量驱动方如评测正是这样按命名空间并行使用的）。隔离的**可信边界是"谁拿得到另一个
  用户的登录态"**——与会话本身的安全模型一致，不是定时任务引入的新边界。
- 任务结果落在创建者**自己的会话**里；他人要可见，前提是能打开那个会话（web 端另有会话归属
  校验 `ensureSessionOwner` 兜底）。
- **残余缺口 1**：消息不带 userId 时框架以 sessionId 顶替（`TLAiAgent.doChat`），持久化路径
  会按会话 id 铺目录——批量驱动方应显式下发 userId（与会话/记忆的既有约定相同）。
- **残余缺口 2**：执行期会话归属校验（`sessionOwner`）是**内存态**，进程重启后未重新登录的
  用户命中为空 → 该守卫暂不生效；但推送仍按事件里的 userId 路由，**不会把结果推给他人**。
- **残余缺口 3**：非 `webchat_` 前缀的 sessionId 无归属校验（会话层的既有约定，非本功能引入）。

### 11.3 实施后补记：删除竞态与状态显示（2026-10-04）

实测发现并修复两处（提交 `0c7dc46`）：

- **删除竞态（记录"复活"）**：任务到点执行后的计数回写（`updateRecordAfterRun`）是
  "读全表 → 改本记录 → 整表写回"；若用户删除与该回写并发，回写的旧快照会把已删记录写回。
  修复：回写前以持久化存储为准二次校验（记录不在当前用户存储里即放弃写回）——
  语义："执行回写不得复活已删除的记录；任务删除优先于结果回收"。
- **状态显示误导**：`list` 对"引擎里没有该任务"的降级文案原为"（等待引擎调度）"，
  会被 LLM 误读成"活着等调度"；且会给已停止任务显示残留的旧"下次执行"。
  修复：引擎无运行时 → 显示 `[已停止]`（记录 enabled=false 才显示 `[paused]`）；
  "下次执行"仅当引擎实际在跑（status=run）时显示。

### 11.4 架构修订：技能实例通用投递（2026-10-04，用户评审后）

原实现把技能专属动作（`runScheduledTask`/`tasksCmd`）硬编码进 `TLAiAgent` 与 `TLToolManager`
的 switch（"agent 因某个技能而改代码"）——架构上错误。已改为**通用机制**（提交 `884c1f3`）：

- 消息 systemArgs 携带 `targetInstance`（目标子模块实例引用）；agent 见到引用即直投
  （`dispatchToInstance` 早返回分支），动作名原样保留；toolManager 同款。
- 技能注册任务时把**自身实例引用**写进任务消息（`buildRegistMsg`）；到点重放该消息即直落技能。
- `/tasks` 命令经模块注册表（`REGISTRY_GET` 按家族名）取实例引用后同款投递。
- 实例引用不可序列化：入库的任务记录不带引用，但**恢复路径重建消息时自动补上**；
  旧数据（重构前入库的任务）重启时被恢复路径自动升级，无需迁移。
- agent/toolManager 现仅含"带引用即投递"的框架级能力，与任何技能无关。

### 11.5 架构修订：执行/投递解耦 + 离线消息箱（2026-10-04，用户定调）

**问题**：原"结果写回用户当前会话"的设计使任务与用户的会话互斥锁强耦合——
用户忙/会话被占时任务退避或失败（"会话使用中"），**把定时机制阉割了**；且高频任务
与用户聊天、任务与任务之间互相堵塞；离线结果无人可见。

**用户定调**："定时任务到时就执行，无论当前有没有会话，否则定时就没有意义了。
结果可以推送到当前会话；如当前没有会话就存在消息箱里，用户登录后提示未读。"

**实现**（提交 `db4dc15`）：

| 环节 | 设计 |
|---|---|
| 执行 | 任务跑在**专用会话** `task_<ownerAgent>_<taskId>_<时间戳>`（每次新建、无记忆、不抢锁），**无条件执行**，不依赖用户在线 |
| 投递 | 执行完查 `getUserStatus`：**在线** → `taskResult` 事件推当前会话（前端 `⏰` 追加）；**离线** → `inboxAdd` 入离线消息箱 |
| 消息箱 | 新表 `ai_inbox`（user_id/source/task_id/text/is_read/created_at）；webui 本地 action `inboxAdd/inboxList/inboxRead`；SSE 事件 `inbox` 实时更新未读 badge |
| 前端 | 顶栏右侧 **📋 定时任务** 与 **🔔 消息箱**（未读红标）→ **浮层**（右侧面板保留给调试/测试功能）；任务浮层含提示词列/暂停/恢复/删除（与控制台 `/tasks` 同语义）；消息箱支持全部已读 |
| 会话列表 | `listSessionsMeta` 过滤 `task_` 前缀内部会话——否则任务会话 last_active 常新，会刷满用户会话列表且 `autoResumeLast` 会"接续"到任务会话上（实测踩中） |

**语义变化**：任务轮次不再进用户会话历史（离线时在消息箱、在线时仅实时推送 + 专用会话留存）；
需要跨次记忆的任务应通过外部存储（文件/DB）或另建任务合并来实现。

**通用性**：消息箱是通用离线收件箱（`source` 字段区分来源），不止服务定时任务；
将来"离线审批通知/系统公告"可复用同一通道。

## 12. 已知边界（不在本次范围）

- 多个 agent 各自创建一个同名任务：id 前缀不同，互不干扰（引擎为工厂共享单例，
  技能为 per-agent 私有实例）。
- 用户跨会话（sessionId 不同）看到的是自己全部任务（按用户隔离，非按会话隔离）。
- 结果事件不带附件（截图等由会话内容自身承载）；在线推送仅实时展示，历史在专用任务会话。
- 任务专用会话不出现在会话列表（内部会话），其轮次仅供排查（DB 直查）。
