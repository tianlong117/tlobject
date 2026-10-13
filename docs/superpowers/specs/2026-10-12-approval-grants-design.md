# 审批授权设计（本轮/本会话免审 + 自动运行 + 等待策略）

日期：2026-10-12
状态：设计已确认，待实施（分两阶段）

## 1. 由来与现状

**用户痛点**：HITL 审批目前是"每次工具调用都要批一次"——一个会话里 LLM 连续调 5 次工具就要批 5 次。
参考 Claude Code：可以设置"本轮/本会话都同意"（弹框时选），也可以事先打开"自动运行"模式。

**现状机制**（代码核实）：

| 环节 | 现状 | 位置 |
|------|------|------|
| 审批触发 | 每次工具执行前发 `APPROVAL_REQUEST` 给 approvalGate | `TLToolExecutor.checkApprovalGate`（:456） |
| 规则匹配 | 按 **LLM 函数名 + 操作** 匹配 `<rules>`，命中即审批 | `TLApprovalModule.isApprovalRequired`（:570） |
| 弹框与决策 | ConsoleReviewer 阻塞 latch；控制台 `/approve approve:ID`，webui 弹框经 msgBus `approvalEvent` 订阅渲染 | `ConsoleReviewer`、`TLWebChatModule.onApprovalEvent` |
| 拒绝记忆 | 会话级"同会话同参数被拒后自动拒"（`rejectedOps`，上限 200 LRU） | `TLApprovalModule:90` |
| 超时 | 默认 5 分钟（`approvalTimeoutMs=300000`）→ EXPIRED → 拒绝 | `approvalGate_config.xml:26` |
| 退出登录 | **退出即拒**该用户全部未决审批 | `TLWebChatModule.doLogout:481` |
| 登录/重连 | `pushPendingApprovals` 重放未决审批弹框 | `TLWebChatModule` |
| 断点地基 | 工具结果 `pending` → 存 `pendingToolCall`+`approvalId` 断点、本轮结束；恢复时 `approved` → **重新执行该工具调用** | `TLAiAgent:1481-1497`（非流式）、`:1836-1848`（流式）、`:1094-1141`（恢复执行） |
| 决策写回 | `AI_P_APPROVAL_DECISION` **全库无写入方**（读侧就绪、写侧缺失）——这是"批准→续跑"链路上唯一断点 | 全局 grep 仅 definition + 2 处读 |
| 离线消息箱 | `inboxAdd/inboxList/inboxRead` + 登录即拉未读角标（`app.js:284`） | `TLWebChatModule` InboxStore |
| 任务会话 | 独立根（`rootSessionId=task_*` 自己）；task→父会话映射由技能登记给 webui | `TLScheduleTaskSkill.executeAsync:578-580`、`registerTaskSession:861` |

## 2. 需求与决策记录

| 决策点 | 结论 | 说明 |
|--------|------|------|
| 免审粒度 | **规则 + 操作**（ruleId + operation） | 用户选定。批准 `file_operation:delete` 不影响 `write`、不影响其他规则 |
| 授权时长档位 | 本次（现状）/ **本轮** / **本会话** | 弹框三选一（+拒绝） |
| 主动预授权 | **自动运行**（通配授权，`/auto on\|off`）+ **单项预授权**（`/permissions grant`） | 对齐 Claude Code 的 auto-accept |
| 存续 | **内存、随进程**（重登/刷新有效，重启失效） | 用户选定；与 `rejectedOps` 同款生命周期 |
| 撤销入口 | 控制台 `/permissions` + webui 🛡 授权面板 | 用户选定两个都要 |
| 任务继承 | 会话授权**归一化到"对话根"**：任务继承会话的授权，反向亦然 | 用户选定。经 task→parent 映射解析（任务会话 rootSessionId 是自己，不是父会话——据此选型） |
| 拒绝 vs 授权 | **拒绝记忆优先**（含自动运行） | 安全侧；且 LLM 已被明确告知"勿重试" |
| 等待策略 | **在线（审批框可投递到人）→ 无限阻塞等；不可投递 → 断点开始；退出登录 → 转断点** | 用户选定。判据用"投递得到吗"而非"登录态"（覆盖"没退出但浏览器关了"的悬挂死角） |
| 超时语义 | 阻塞态：`approvalTimeoutMs` 语义改为 **0 = 一直等**（默认改 0）；断点态：另设 TTL（默认 24h）兜底 | |
| 决策写回 | resume 装载断点时**向 approvalGate 查询状态**填充 decision；批准后触发续跑 | 补上唯一缺失的接线 |
| 进程重启 | 审批是内存态 → 重启后断点恢复时审批已失效，**如实告知**，用户选"放弃该操作"或重新发起 | |
| 会话并发 | 会话存在未决审批时：webui 输入区**拦截新消息**并提示（批准/拒绝/放弃三选一）；控制台 `/continue` 如实提示"审批仍在等待中" | 与阻塞态"会话忙"的体感保持一致 |
| 不做 | 跨会话/全局自动运行（要永久免审请改 XML 规则）；超时后延迟执行（大工程，断点式已覆盖窗口内补批）；按参数模式的授权；webui 单项预授权入口 | YAGNI |

## 3. 总体设计

**核心**：在 `approvalGate` 内建"授权记忆"，与现有 `rejectedOps` 完全镜像；授权匹配发生在**请求进来时**，因此"弹框时选免审"与"事先打开自动运行"是同一张表的两种写入时机。

- **阶段一**：授权记忆 + 自动运行 + `/permissions` + 消息箱兜底（在现有 5 分钟超时下）。
- **阶段二**：等待策略改造（投递判据 / 无限阻塞 / 退出转断点 / 决策写回 / TTL）。

`TLToolExecutor`、`TLAiAgent`、`IApprovalReviewer` 接口在阶段一**零改动**；阶段二仅"决策写回"触及 `TLAgentService`（resume 装载处）。

## 4. 阶段一：授权记忆 + 自动运行

### 4.1 数据模型

```java
/** 授权记录：key = resolve(sessionId) + "|" + scope + "|" + roundId + "|" + ruleId + "|" + operation */
private final Map<String, ApprovalGrant> approvedGrants = Collections.synchronizedMap(new LinkedHashMap<>());
private static final int MAX_APPROVED_GRANTS = 200;   // LRU 上限，同 rejectedOps 既有模式

class ApprovalGrant {
    String scope;      // "round" | "session"
    String roundId;    // round 级必填；session 级为 ""
    String ruleId;     // 规则唯一标识；"*" = 自动运行（通配）
    String operation;  // 命中时的操作名；提取不到 = ""；"*" = 全部操作（随 ruleId="*"）
    String toolName;   // 授予时命中的工具名（列表展示用）
    long   grantedAt;
}
```

**会话归一化**：写入与匹配都用 `resolve(sessionId)`：
- `resolve(sid)` = `taskSessionParents.getOrDefault(sid, sid)`（task→父会话映射，见 4.7）
- 普通会话 resolve = 自己；任务会话 resolve = 发起会话。
- 效果：会话里给的授权覆盖其派生的全部后台任务；任务里给的授权归于该对话根（两个方向对称）。
- **round 级不做跨会话继承**：roundId 属于各自执行流，task 的 roundId 与 master 互不相干，键天然隔离。

**清理**：
- 轮次级：每次请求进来时，顺手清掉 `resolve(sid)` 名下 `roundId ≠ 当前 roundId` 的 round 授权（惰性即"一轮完毕自然消亡"，无定时器）。
- 会话级：LRU 上限兜底；进程退出即清零。
- 重复授予：幂等覆盖（刷新 grantedAt）。

### 4.2 规则唯一 id（ruleId）

把 `isApprovalRequired` 重构为**一次匹配返回三元组** `{required, ruleId, operation}`（匹配规则与提取操作本来就是同一趟逻辑）：

- **ruleId = 配置里的规则名原样**：
  - 精确规则 → `<rule name="word">` 的 name
  - 前缀规则 `tool:*` / `tool*` → 配置名（如 `file_operation:*`、`filesystemMcp*`）；实现上加一个 `前缀 → 规则名` 平行映射
  - 旧 `approvalRules` 字符串格式 → 去空白后的 token 原样
- operation：沿用 `extractOperation`；提取不到（如 script_execution，走安全侧判需审批）→ `""`，键仍成立
- 原 `public boolean isApprovalRequired(toolName, args)` 保留为薄包装（现仅内部一处调用，但属公开 API，签名不动）

### 4.3 匹配顺序与自动批准

`handleApprovalRequest` 判定链（只加一环）：

```
1. 拒绝记忆命中            → 直接拒（现状不变，拒绝优先）
2. 规则判定"不需审批"      → 放行（现状不变）
3. 【新增】授权命中         → 自动批准：记日志 + trace，不弹框，直接返回 approved
4. 否则                    → 正常弹框（现状不变）
```

授权命中判定：
1. 会话级：key(`resolve(sid)`, "session", "", ruleId, op)
2. 轮次级：roundId 非空时 key(`resolve(sid)`, "round", roundId, ruleId, op)；roundId 为空则轮次级不参与匹配
3. 通配：`grant.ruleId=="*"` 命中任意 ruleId；`grant.operation=="*"` 命中任意 operation（自动运行即 `*`/`*`）

**对 LLM 透明**：自动批准走的是"executor 正常放行"路径，工具照常执行返回结果；"审批通过"字样只在 pending/rejected 时进 history，自动批准不产生任何 LLM 可见差异。

### 4.4 消息接口（`TLAiAgentParamString:433` 常量区新增）

| 动作 | 参数 | 说明 |
|------|------|------|
| `APPROVAL_APPROVE`（扩展） | 新增 `approvalScope`：`once`(默认)/`round`/`session` | once=现状；round/session 额外写授权记录。带 `round` 但请求无 roundId → 返回错误"无轮次信息" |
| `APPROVAL_GRANTS`（新） | `sessionId?` 可选过滤 | 列出授权（结构化 grants 列表），控制台/webui 共用 |
| `APPROVAL_REVOKE`（新） | `sessionId`+`ruleId`(+`operation`?)；或 `sessionId`+`all=true` | 撤销，返回删除条数 |
| `APPROVAL_GRANT`（新） | `sessionId`+`ruleId`(+`operation`?) | 主动预授权（`/permissions grant`） |
| `APPROVAL_TASKSESSION`（新） | 登记：`execSession`+`parentSessionId`；注销：`execSession` | task→父会话映射登记/注销（见 4.7） |

`TLApprovalRequest` 增字段：`ruleId`（创建请求时由匹配结果填入）、`userId`（审批请求消息本就携带，写入消息箱通知用）。

### 4.5 控制台交互

**`/approve` 四个动词**：

| 命令 | 含义 |
|------|------|
| `/approve approve:ID[:jsonArgs]` | 批准本次（现状不变） |
| `/approve round:ID` | 本轮内同规则免审 |
| `/approve session:ID` | 本会话内同规则免审 |
| `/approve reject:ID:原因` | 拒绝（现状不变） |

审批框模板（`ConsoleReviewer.promptTemplate`）增加两行命令提示，框宽重新对齐：

```
║  /approve approve:{id}      批准本次        ║
║  /approve round:{id}        本轮同规则免审   ║
║  /approve session:{id}      本会话同规则免审 ║
║  /approve reject:{id}:原因   拒绝           ║
```

**`/auto`（新命令）**：`on` / `off` / 无参=显示状态。开启时输入提示符带 `[🔓自动]` 标识（防止开着忘了）。

**`/permissions`（新命令）**：

| 命令 | 含义 |
|------|------|
| `/permissions` | 列当前会话的授权（含"全部规则（自动运行）"行） |
| `/permissions -a` | 列全部会话的授权 |
| `/permissions revoke <ruleId>[:<operation>] [-s <sessionId>]` | 撤销（默认当前会话） |
| `/permissions grant <ruleId>[:<operation>]` | 预授权单项（本会话） |
| `/permissions clear [-a]` | 清空（当前会话 / 全部） |

每行列表尾部打印可直接复制的 revoke 命令。

**接线**：`/help`、Tab 补全注册表同步新增；busy 态白名单（`TLChatConsole:646` 目前只放 `/stop` `/approve`）加入 `/auto` `/permissions`。
链路：TLChatConsole → `TLAgentService`（`doApprove` 扩展 scope；新增 `doPermissions`/`doAuto`，纯转发零业务）→ approvalGate。

### 4.6 webui 交互

只动 `app.js` + `chat.html`（`/api/command` 默认转发 agentService，`TLWebChatModule` 无需加 case）：

- **审批弹框按钮行**：`✓ 批准` ｜ `✓ 本轮不再问` ｜ `✓ 本会话不再问` ｜ `✗ 拒绝`（保留拒绝原因输入框、参数可编辑后批准）。三个批准共用 `approveAction`，多带一个 `approvalScope`。fromTask 的弹框同样三档。
- **顶栏 🔓/🔒 自动运行开关**（与 📋 定时任务同区，符合"顶栏=应用级功能"布局约定）：点击切换、状态高亮回显；开关即通配授权，与 🛡 面板里那一行是同一份状态。
- **顶栏 🛡 授权管理浮层**：列出当前用户会话的授权行 `[会话 | 范围(本轮/本会话/全部规则) | 规则 | 操作 | 授予时间 | 撤销]` + 「全部清空」。

### 4.7 任务会话继承（登记映射）

`TLScheduleTaskSkill` 已有一对对称的登记/注销调用点（`executeAsync:568`、`finishTask:633`），现在只发 webui；**扩展为同时向 approvalGate 发送 `APPROVAL_TASKSESSION`**：

- 登记：`execSession=task_*` → `parentSessionId`（background 任务必填；定时任务 parentSessionId 为空 → 不登记，就地生效）
- 注销：任务结束时对称注销；漏注销由 approvalGate 侧映射表 LRU 上限（200）兜底
- approvalGate 未配置时静默跳过（同 webui 的既有模式：`getModuleInFactory` + `IGNOREMODULEISNULL`）

### 4.8 消息箱兜底

**规则**：审批在"未被受理而终结"时，给用户写一条消息箱条目（复用 `inboxAdd`，`source="approval"`）。

- **阶段一写入点**（现有 5 分钟超时下的两个终止点）：
  1. **超时 EXPIRED**：`TLApprovalModule` 侧统一 `markExpired(request, reason)` 收口——⚠️ 现有**两条超时路径**（`scheduleTimeout` 定时任务 + `waitForDecision` latch 自超时）都要走它，避免漏
  2. **退出即拒**（过渡行为，阶段二改为"退出转断点"）：`TLWebChatModule.rejectPendingApprovals` 内直接写（它自己就是 webui）
- **阶段二写入点**：断点审批 TTL 到期失效时（用户不在场，靠消息箱告知）；恢复时发现"审批已失效"的场合用户本人在场，走界面提示（5.8），不重复写消息箱
- **实现**：approvalGate 经可选 webui 写入（`getModuleInFactory("webui")` + `IGNOREMODULEISNULL`，无 webui 静默跳过，同 `TLScheduleTaskSkill.deliverToInbox` 模式）
- **文案**：工具名 + 命中规则说明 + 会话 + "已按拒绝/失效处理"；用户登录后未读角标提示（`refreshInboxBadge` 现成）

### 4.9 日志与追踪

- 自动批准：`putLog` INFO + `traceStage`（复用 `approvalDecided`，detail 标注 `auto(session|round|wildcard)`）
- 授权写入/撤销：`putLog` INFO
- `/auto` 开/关：trace 留审计

## 5. 阶段二：等待策略改造

### 5.1 投递判据（"人在不在"）

`ConsoleReviewer.requestApproval`：
- `publishApprovalEvent(...) == true`（有订阅者 ack 并渲染：控制台在跑 / webui 有在线连接）→ **阻塞等**
- 否则（无人接收）→ **返回 `pending`，不阻塞** → 上层自然落断点

依据：ack 语义本来就存在（`TLChatConsole` 只在 running 时 ack；`TLWebChatModule` 只在真正推到 SSE 时 ack）。这自动覆盖"没退出但浏览器关了/断网"的悬挂死角。原"publish 失败 → 打印 stdout 兜底"改为转断点（无 UI 部署打印也没人看）。

**已知行为变化**：完全无订阅者的环境（如外部程序化审批，靠发 approve 消息应答）从"阻塞等外部决策"变为"转断点"；如需保留阻塞语义，保持任一订阅者（控制台/webui）在线。

### 5.2 阻塞态：无限等待

- `waitForDecision`：`timeout <= 0` → `latch.await()` 无限等待
- `scheduleTimeout`：`timeout <= 0` → 不排期
- `approvalTimeoutMs` 语义改为"**阻塞等待上限，0 = 一直等**"，默认值改 0；配置样例与注释同步更新（现配置显式写 300000，注释说明）
- 自愈路径：在线挂着等期间，即使标签页关闭（连接断了），用户回来重连 → 审批框重放 → 决策 → latch 唤醒 → 原线程继续

### 5.3 不可投递 → 断点开始

reviewer 返回 `pending` 后，现状代码链已完整（无需改动）：
`handleApprovalRequest` 返回 pending → `TLToolExecutor` ToolResult.state="pending" + approvalId → agent 存断点（`TLAiAgent:1481-1497` / 流式 `:1836+`）→ 本轮结束，**不占线程、不占会话**。
审批条目保持 PENDING 存活（登录重放依赖它）。

### 5.4 退出登录 → 转断点（取代"退出即拒"）

`TLWebChatModule.doLogout` 的 `rejectPendingApprovals` 改为 `deferPendingApprovals`：

- 对每个未决审批发送新动作 `APPROVAL_DEFER`（或等价信号）→ approvalGate 将该请求标记 `deferred=true`（状态保持 PENDING）并 `signalDecision` 唤醒阻塞线程
- `waitForDecision` 被 defer 唤醒时返回 **pending 状态**（而非 rejected）→ reviewer 返回 pending → 上层落断点、线程释放
- 幂等：本就是断点态的审批再 defer 无副作用

### 5.5 决策写回与续跑（补上唯一缺失的接线）

1. **写回**：`TLAgentService` resume 装载断点处（:295-309 已透传 `pendingToolCall`/`approvalId`）增加——向 approvalGate 发 `APPROVAL_QUERY`：
   - approved → 置 `AI_P_APPROVAL_DECISION="approved"`，透传 `approvalModifiedArguments`
   - rejected → 置 `"rejected"` + 原因
   - 查不到 / EXPIRED → **如实告知"审批已失效"**，不恢复续跑（见 5.8）
2. **续跑触发**：
   - **webui**：批准一个 deferred 审批成功后（approve 响应带 `deferred` 标志），若该会话存在 pending-approval 断点 → 自动触发续跑（复用现有 `continueSession`/`resumeCheckpoint` 链路）；会话忙则提示用户手动"继续"
   - **控制台**：批准后提示"可 `/continue` 续跑"（或直接触发）

### 5.6 断点审批 TTL

- 新配置 `approvalPendingTtlMs`（默认 24h，仅作用于 deferred 审批；阻塞态不受它影响）
- 到期 → 状态 EXPIRED + 消息箱告知（4.8）
- 清理调度复用现有 `timeoutScheduler`

### 5.7 会话并发规则（断点式下会话不再"忙"）

会话存在未决审批（deferred）时：

- 登录 / 进入该会话：审批框自动重放（`pushPendingApprovals` 现成）+ 会话视图显示"待批操作"卡片
- **发送新消息 → 拦截并提示**："该会话有待批操作（批准 / 拒绝 / 放弃后才能继续）"；提供「放弃该操作」= 撤销审批 + 丢弃断点（此后可正常对话）
- 控制台：`/continue` 如实返回"审批仍等待中"（现状 `TLAiAgent:1149-1151`），提示用 `/approve`

### 5.8 进程重启语义

审批与授权都是内存态，重启即失；DB 断点仍在。下次 resume 时 approvalId 查不到 → 界面如实提示"**审批已失效（服务重启）**"，用户选：放弃该操作（丢弃断点继续对话）/ 重新发起。

### 5.9 陈旧补批提示

deferred 审批重放时，弹框标注"该请求发起于 X 前"（用 `createdAt`）；避免用户以为是对刚刚操作的确认。

## 6. 改动面清单

**阶段一**：

| 文件 | 改动 |
|------|------|
| `TLApprovalModule` | 授权表 / 规则 id 重构 / 新消息动作 / markExpired 收口 + 消息箱 |
| `TLApprovalRequest` | +`ruleId` +`userId` |
| `TLAiAgentParamString` | 新常量 |
| `TLAgentService` | doApprove 扩展 scope；+doPermissions +doAuto（纯转发） |
| `TLChatConsole` | /approve 动词、/auto、/permissions、提示符、help/Tab/busy 白名单 |
| `TLScheduleTaskSkill` | 登记/注销扩展为同时发 approvalGate |
| webui `app.js`/`chat.html` | 弹框三按钮、🔓 开关、🛡 面板 |
| `conf/demo/aiagent/approvalGate_config.xml` | 注释更新（无功能改动） |
| `conf/demo/aitest/` | 新增审批测试配置 + 场景（现无 approvalGate 配置，`moduleFactory_config.xml:98` 注释未启用） |

**阶段二**：`TLApprovalModule`（defer/TTL/查询带 deferred/超时语义）、`ConsoleReviewer`（投递判据）、`TLWebChatModule`（logout→defer、deferPending、消息箱）、`TLAgentService`（resume 决策填充）、webui（自动续跑、输入拦截、卡片）、conf（超时注释/`approvalPendingTtlMs`）

**零改动**：`TLToolExecutor`、`TLAiAgent`（阶段二不新增触发点，续跑复用现有 resume 入口）、`IApprovalReviewer` 接口

## 7. 硬边界

- 拒绝记忆 > 授权（含自动运行）；拒绝是具体参数级精确意图，授权是类级粗意图
- 自动批准对 LLM 透明（无审批文案进 history）
- 授权与审批均内存态：进程重启全失；授权重登/刷新仍有效（服务端内存）
- 会话级授权归一化到对话根（任务双向继承）；round 级不继承
- ruleId 一律取"配置里的规则名"，不做工具名归一（前缀规则/别名折叠到规则身份上）
- roundId 为空时：`approvalScope=round` 报错；轮次级授权不参与匹配（安全侧）
- 审批请求的两条超时路径必须收口到同一 `markExpired`

## 8. 测试计划

**单元（/test，Mock 驱动，新增代码驱动场景）**：
1. 会话授权：请求 → approve(scope=session) → 同规则二次请求自动批准（无弹框）
2. 轮次授权：同轮自动批准；新 roundId 后重新弹框
3. 粒度隔离：授权 `file_operation:delete` 后 `file_operation:write` 仍弹框
4. 拒绝优先：先拒后授权 → 仍自动拒
5. 撤销：revoke 后重新弹框；`clear` 清空
6. 自动运行：`/auto on` 等价通配授权，全规则放行；off 恢复
7. 任务继承：登记 task→parent 后，父会话授权对 task 会话请求生效
8. 消息箱：EXPIRED → inbox 条目写入（mock webui）
9. （阶段二）deferred 流：reviewer 不可投递 → pending → 断点落库 → QUERY 返回 approved → resume 真正重新执行工具
10. （阶段二）退出转断点：defer 唤醒后线程以 pending 返回，审批保持 PENDING

**接口冒烟（无 LLM，直接发消息）**：approvalGrants/approvalRevoke/approvalGrant/approvalTaskSession 的消息级验证

**控制台冒烟**：`/auto on|off`、`/permissions`、`/approve round:ID|session:ID`、提示符标识

**webui**：无后端前端验证（按钮接线、面板渲染）+ 真 LLM E2E（弹框三档、自动运行开关、退出→登录→重放→批准→续跑）

**回归**：现有 `/test` 场景全绿

## 9. 风险与已知取舍

- **在线但人不在**（标签开着、人走了）：线程与会话一直占着直到用户回来决策——用户明确选定的语义；如需兜底可把 `approvalTimeoutMs` 配非 0
- **补批的陈旧性**：断点式下批准可能发生在数小时后，执行的是历史操作——用 5.9 的时长提示如实呈现
- **UI 叠加**：登录时 `pushPendingApprovals`（审批框）与 `promptCheckpoint`（断点提示）可能同时出现，需注意不打架
- **双超时路径**：历史上已经"各走各的"，本次必须收口，否则消息箱/状态会漏
