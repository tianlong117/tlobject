# ChatTurn 轮执行对象设计（消息对象版）

- 日期：2026-11-04
- 状态：Phase 1/2 已实现（Phase 3/4 待做）
- 涉及模块：`aiagent/common`（新增轮对象与协作者；`TLAiAgent` 收口）
- 关联：
  - `deepseek对于ageng核心的分析1104-1.txt`
  - `agent拆分/` 评估（仅作参考，不直接采用）

## 1. 背景与目标

`TLAiAgent` 当前约 3800 行，混合了：配置解析、子模块装配、消息分发、作为工具适配、会话锁/取消/运行计数、历史构建、记忆召回、LLM 循环、工具执行、终态持久化、护栏、trace、起名、token 统计。  
DeepSeek 的 `agent拆分/` 只做了"代码分组"：`ChatTurn` 仍是被动数据袋，真正执行逻辑仍散在 `ChatLoop`/`TurnFinalizer`，且存在编译错误与行为回退，不能直接采用。

本设计的目标不是"再拆几个类"，而是把**一轮的执行主体**做成一个真正的消息对象：

1. 每轮创建一个 `ChatTurn` 实例，负责本轮的执行闭环（LLM 调用 → 工具解析/执行 → 结果入史 → 终态）。
2. `ChatTurn` 采用 `TLBaseModule` 的**瞬态用法**：可以 `new(name, factory)`，直接用内置的 `putLog`、`putMsg`，但不 `start()`、不注册、不读 XML、不参与模块生命周期。
3. 记忆、会话历史等内容由 Agent 在轮开始前注入；轮内不调用 `aiContext` / memory 的读取接口。
4. 会话锁、取消标志、运行计数、待注入队列、流式句柄登记仍归 `SessionRegistry`，不搬进轮对象。
5. 持久化/护栏/起名等策略集中在 Agent 侧 `TurnCommitter`，轮对象只负责"把本轮记录交回去"。
6. 第一版**行为完全保持当前 HEAD**，包括 `2026-11-04` 的中断轮 context/会话同口径修复与 1103 系列的修复；不借机改策略。

非目标：

- 不做完整状态机、不把 round 改成不可变、不调整会话存储格式；
- 不做每轮模块池化/注册/`start()`；Turn 是瞬态模块，不是长期模块；
- 不修改 Provider 流式回调协议；
- 不改 `guardOutput` 作用范围、记忆粒度、起名策略等产品行为；
- 不改 WebUI/控制台协议。

## 2. 概念模型

| 概念 | 生命周期 | 承载 |
|---|---|---|
| Session | 长期，跨多轮 | sessionId、context、SessionManager 记录、会话锁、取消标志、运行计数、待注入队列 |
| Round | 持久化键，跨 resume | roundId、checkpoint/pending_approval/completed 状态、stateRoundMessages |
| Turn | 一次 chat 请求 | 本轮工作历史、msgStartIdx、toolDefs、memoryContext、token、终态、StreamSink |

**决策：Turn 是消息对象，采用 `TLBaseModule` 的瞬态用法。**

| 层次 | 能力 | 结论 |
|---|---|---|
| `IObject` | `getMsg/putMsg` 契约 | 可实现，但需自己实现消息基础设施 |
| `TLBaseObject` | `createMsg`、`putMsg(IObject,msg)`、`putMsgNoWait`、`ThreadTask` 暂停/取消 | 不具备 `putLog`；本设计不直接采用 |
| `TLBaseModule` | 上面全部 + `putLog`、`putMsg(String)`、配置/生命周期/路由表 | **采用瞬态用法**：`new(name, factory)`，不 `start()`、不注册、不读配置；只用安全子集 |

瞬态模块的安全子集：

- 可用：`createMsg`、`putMsg(IObject, msg)`、`putLog`、`ThreadTask` 暂停/取消；
- 不用：`putMsg(String, msg)`、`getModule`、`params/mconfig`、`msgTable/beforeMsgTable`、`reloadConfig`、注册表/监控；
- 服务全部注入为 `IObject` 引用，不靠名字查；
- 不接收普通入站消息；仅实现 `checkMsgAction` 的 `TOOL_PROGRESS` 分支，把工具进度透传给父 Agent，其余返回 null；
- **Turn 有自己的唯一名字**：`turn:<agentName>:<roundId>`，不注册，仅作日志/实例标签；
- 下游依赖"调用方 Agent 名"的地方，不靠对象名，靠**显式参数**：
  - 工具执行消息带 `AI_P_AGENTNAME=seed.agentName`；`TLToolExecutor` 优先读它（缺省回退 `fromWho.getName()`），输出校验/trace 仍归到 Agent；
  - LLM 请求 `msg.setSource(seed.agentName)`，Provider trace 仍记为 Agent；
  - 工具进度事件回到 Turn 实例，Turn 透传给父 Agent，父 Agent 的 `handleToolProgress` 用自己的名字发 msgBus。

Turn 直接 `putMsg(toolExecutor/toolManager/llmProvider, msg)`；工具进度事件会回到 Turn 实例，Turn 在 `checkMsgAction` 中 `putMsg(parentAgent, evt)` 透传，父 Agent 继续走现有 `handleToolProgress` → msgBus 逻辑。

## 3. 总体结构

```
TLAiAgent（TLBaseModule，长期存活）
  ├─ 共享服务：TLLlmProvider / TLToolManager / TLToolExecutor / TLAiContext /
  │             MemoryStore / SessionManager
  ├─ SessionRegistry / SessionState：会话锁、取消标志、运行计数、streamHandle、待注入队列
  ├─ TurnFactory（Agent 内部）：loadHistory、recallMemory、附件归一化、toolDefs、
  │                             模板替换、prompt 注入、配置快照 → TurnSeed
  └─ TurnCommitter（Agent 实现）：context / SessionManager / 长期记忆 / 护栏 / 起名 / trace

        │ 注入 TurnSeed（内容）+ TurnDeps（服务引用）
        ▼
     ChatTurn extends TLBaseModule（每轮一个，瞬态：new，不 start/不注册）
       ├─ 直接 putMsg：toolManager / toolExecutor / llmProvider
       ├─ checkMsgAction(TOOL_PROGRESS) → putMsg(parentAgent, evt) 透传
       ├─ TurnViewBuilder（发送视图；一期复用现有 buildSendList）
       ├─ AttachmentPipeline / ToolResultAppender（纯辅助）
       ├─ TurnObserver（trace/token 上报）
       ├─ 内置 putLog（模块基类能力，日志带 roundId 前缀）
       ├─ 工作状态：history、msgStartIdx、toolDefs、memoryContext、token、outcome、phase
       ├─ run()：LLM 循环 → 工具执行 → 结果入史 → 终态
       └─ finish()：调用 TurnCommitter.commit(record, outcome) → 发终帧 → 返回
```

## 4. 核心类型

### 4.1 TurnSeed（不可变，Agent 注入）

```java
final class TurnSeed {
    // 身份
    final String sessionId;
    final String rootSessionId;
    final String roundId;
    final String userId;
    final String agentName;
    final String userMessage;

    final TurnPlan plan;              // NORMAL / RESUME_CHECKPOINT / RESUME_PENDING / LLM_INPUT

    // 内容（由 Agent 在轮前准备）
    final List<TLConversationHistory> history;   // 已含 context system、用户条目、state 块
    final int msgStartIdx;                       // 本轮增量起始下标
    final List<TLConversationHistory> resumeRoundMessages; // 可空；同 roundId last-wins 前缀
    final String memoryContext;
    final List<TLFunctionDefinition> toolDefs;

    // 请求参数快照
    final String model;
    final double temperature;
    final int maxTokens;
    final String reasoningMode;
    final boolean reasoningVisible;
    final boolean noHistory;
    final boolean intentCacheEnabled;
    final int maxToolCallIterations;

    // 运行时出口
    final StreamSink sink;             // 非流式为 NullStreamSink
    final CancelSignal cancel;         // 引用 Agent 的会话取消标志
    final ApprovalResume approvalResume; // 仅 RESUME_PENDING；含 decision/approvalId/pendingToolCall
}
```

约定：

- 列表字段在构造时 defensive copy；Turn 内部可修改自己的 working copy，不写回 Seed。
- `history`/`memoryContext`/`toolDefs` 都由 Agent 准备；Turn 不调用 `aiContext`、memory 读取。
- `ApprovalResume` 由 Agent 查询 `approvalGate` 后填入；Turn 不依赖审批模块。

### 4.2 TurnPlan

| 类型 | 历史来源 | 是否追加用户条目 | 特殊处理 |
|---|---|---|---|
| `NORMAL` | Agent 从 context 复制 | 是 | 无 |
| `RESUME_CHECKPOINT` | Agent 从 loadSession 复制（含 state 块） | 否 | 从原轮继续 |
| `RESUME_PENDING` | Agent 从 loadSession 复制（含 state 块） | 否 | 按 decision 执行/拒绝/失效/仍等待 |
| `LLM_INPUT` | 调用方提供完整 messages | 否 | trace 重放/外部驱动 |

### 4.3 TurnDeps（服务引用）

```java
final class TurnDeps {
    final TLObjectFactory factory;      // 传给 TLBaseModule 构造器（putLog/消息基础设施）
    final IObject parentAgent;          // TOOL_PROGRESS 透传目标
    final IObject llmProvider;          // Turn 直接 putMsg
    final IObject toolManager;          // Turn 直接 putMsg（resolve）
    final IObject toolExecutor;         // Turn 直接 putMsg（execute）
    final TurnViewBuilder viewBuilder;  // 一期复用 TLAiAgent.buildSendList
    final TurnCommitter committer;
    final TurnObserver observer;        // traceStage / reportTokenUsage / 格式化
    final AttachmentPipeline attachments;
    final ToolResultAppender appender;
    final StreamHandleRegistry streams; // set/clear streamHandle（SessionRegistry）
}
```

- 构造：生产用 `super("turn:" + seed.agentName + ":" + seed.roundId, deps.factory)`；**Turn 的名字唯一**（瞬态、不注册），日志/实例标签用它，不与 Agent 混淆。
- 单测可用 no-factory 构造器（`super(turnName)`；不做工厂注册、putLog 不落日志模块），避免为了跑 Turn 单测去搭整个工厂。注意 `TLBaseModule(name, null)` 会 NPE，所以不能直接把 null 传给带 factory 的构造器。
- Turn 直接 `putMsg(deps.toolManager, ...)` / `putMsg(deps.toolExecutor, ...)` / `putMsg(deps.llmProvider, ...)`，无额外适配层。
- 工具执行消息显式带 `AI_P_AGENTNAME=seed.agentName`；`TLToolExecutor` 增加 `ExecutionState.agentName`（优先读该参数，缺省回退 `fromWho.getName()`），输出校验和 trace 用它，避免看到 `turn:xxx`。
- LLM 请求 `msg.setSource(seed.agentName)`；Provider trace 的 source 仍记为 Agent。
- 工具进度事件回到 Turn 实例（`fromWho=turn`）；`ChatTurn.checkMsgAction` 收到 `TOOL_PROGRESS` 后 `putMsg(deps.parentAgent, evt)` 透传，父 Agent 继续走现有 `handleToolProgress` → msgBus 逻辑。
- 流式句柄仍通过 `StreamHandleRegistry` 登记，Provider 回调地址仍是父 Agent（`RESULTFOR=seed.agentName`）。
- `putLog` 直接使用模块基类能力；日志内容带 `[turn:<roundId>]` 前缀便于区分。

接口最小集（只保留不依赖模块内部状态的协作者）：

```java
interface TurnViewBuilder {
    List<TLConversationHistory> build(List<TLConversationHistory> history, String memoryContext);
}
interface TurnCommitter {
    void checkpoint(TurnRecord record);
    void checkpointPending(TurnRecord record, PendingInfo pending);
    CommitResult commit(TurnRecord record, TurnOutcome outcome);
}
interface TurnObserver {
    void traceStage(String stage, String detail, long durationMs, String payload);
    String formatHistory(List<TLConversationHistory> history);
    String formatResponse(String content, List<TLToolCall> toolCalls, String reasoning);
    void reportTokenUsage(long[] usage, long[] cache);
}
interface CancelSignal { boolean isCancelled(); }
interface StreamHandleRegistry {
    void set(String sessionId, StreamHandle handle);
    void clear(String sessionId, StreamHandle expect);
}
```

### 4.4 ChatTurn 生命周期

```
CREATED → PREPARING → RUNNING → COMMITTING → CLOSED
                 └──────────────┴→ FAILED/CLOSED
```

- `run()` 是唯一执行入口；`close()` 幂等。
- Phase 只用于内部防御与清理，不替代外层 SessionRegistry 的会话锁/轮次计数。
- 会话锁、monitor 注册、runCount 的获取/释放仍由 Agent 的 `runChat` 外壳完成（chatStream 需要在 worker 启动前登记轮次）；Turn 不碰这些结构。
- `checkMsgAction` 只处理 `TOOL_PROGRESS`：转发给 `deps.parentAgent`；其余入站消息返回 null。Turn 不注册、不按名字寻址，外部只能拿实例引用投递。

### 4.5 TurnOutcome

所有 break 点产出显式终态，且携带足够元数据以保持返回参数兼容：

```java
sealed interface TurnOutcome {
    record Tokens(int prompt, int completion, int total,
                  int cacheCreate, int cacheHit, int cacheMiss) {}
    record Completed(String response, int iterations, Tokens tokens,
                     String reasoning, int finalAssistantIdx) {}
    record Aborted(String reason, boolean byUser, Tokens tokens, int iterations) {}
    record Rejected(String reason, String response, int iterations,
                    Tokens tokens, int finalAssistantIdx) {}
    record PendingApproval(String approvalId, String pendingToolCallId,
                           String response, int iterations, Tokens tokens) {}
    record Clarified(String question, String response, int iterations,
                     Tokens tokens, int finalAssistantIdx) {}
    record Timeout(String response, int iterations, Tokens tokens) {}
    record Truncated(String response, int iterations, Tokens tokens) {}
    record DirectAnswer(String response, int iterations, Tokens tokens,
                        int finalAssistantIdx) {}
    record Failed(String error) {}
    record EarlyReturn(String response, boolean asFinal) {}
}
```

注意：`Clarified`/`Timeout`/`PendingApproval` 也必须携带 tokens/iterations；DeepSeek 拆分漏掉这些，导致返回参数和会话 token 统计回退，本设计明确禁止。

### 4.6 辅助类型

```java
record TurnIdentity(String sessionId, String rootSessionId, String roundId,
                    String userId, String agentName) {}

record ApprovalResume(String state,            // pending_approval
                      TLToolCall pendingToolCall,
                      String approvalId,
                      String decision,         // approved/rejected/lost/pending
                      Map<String, Object> modifiedArgs,
                      String rejectReason) {}

record PendingInfo(String approvalId, TLToolCall pendingToolCall) {}

/** 一次 checkpoints/commit 所需的完整快照；由 ChatTurn 在调用时构造 */
final class TurnRecord {
    final TurnIdentity id;
    final String userMessage;
    final List<TLConversationHistory> history;       // 工作副本
    final int msgStartIdx;
    final List<TLConversationHistory> resumeRoundMessages; // 可空
    final boolean noHistory;
    final String model;
    final double temperature;
    final int maxTokens;
    final String reasoningMode;
    final boolean reasoningVisible;
    final int iterations;
    final TurnOutcome.Tokens tokens;
    final int finalAssistantIdx;                     // 无终答下标时为 -1

    /** = resumeRoundMessages + delta(history, msgStartIdx)；同 roundId last-wins 前缀 */
    List<TLConversationHistory> roundMessages() { ... }
}

final class CommitResult {
    final TLMsg returnMsg;
    final String finalText;   // 护栏后的最终文本；Failed 时为 error 文案
}
```

`TurnRecord` 是快照，不是长期状态；`ChatTurn` 在每次 checkpoint/commit 时构造，避免外部持有可变列表。

## 5. 执行时序

### 5.1 正常轮

1. Agent 完成会话准入（锁、monitor、runCount），准备 `TurnSeed`：
   - context/loadSession → history；
   - memory 召回 → memoryContext；
   - 附件归一化 + 用户条目；
   - toolDefs；
   - 模板变量替换、prompt reasoning 注入；
   - 配置/参数快照。
2. Agent 在 history/用户条目构建完成后、模板替换/toolDefs 注入之前调用一次 `committer.checkpoint(initialRecord)`，对齐当前 `prepareChat` 入口快照；随后完成 seed 构建，`new ChatTurn("turn:" + seed.agentName + ":" + seed.roundId, factory, seed, deps)`，调用 `run()`。
3. Turn 循环：
   - 检查取消/暂停；
   - `committer.checkpoint(record)`（sessionUpdated，`messages = resumeRoundMessages + delta`）；
   - `deps.viewBuilder.build(history, memoryContext)`；
   - `putMsg(deps.llmProvider, ...)`；trace llmRequest/llmResponse；token 累加；
   - 无 tool_calls → `Completed`；
   - 有 tool_calls → assistant 入史 → `putMsg(deps.toolManager, resolve)` → `putMsg(deps.toolExecutor, execute)`；
   - 工具结果入史（ToolResultAppender）、图片攒批注入、toolEvent 推送；
   - 控制态（rejected/pending/clarified/timeout/direct）立即产出终态；
   - 正常 → `committer.checkpoint(record)` 后继续。
4. 循环耗尽 → `Truncated`。
5. `finish(outcome)`：
   - `CommitResult cr = committer.commit(record, outcome)`；
   - 用 `cr.finalText` 发终帧（final/error）；
   - 返回 `cr.returnMsg`。

### 5.2 中断（/stop 或执行器取消）

- Turn 检测到取消/执行器 `aborted` → `Aborted`。
- `TurnCommitter.commit` 必须保持当前 HEAD 行为：
  - 对 `roundMessages` 副本 `sanitizeToolPairs`；
  - 追加 assistant「⏹ 已中断」；
  - 写 context（`noHistory` 跳过）；
  - 发 `chatAborted`（state=completed）；
  - 不写长期记忆；
  - trace roundEnd = `aborted: <reason>`（不带上限 payload）；
  - 返回字段按当前 abort 分支原样：`RESULT=true, cancelled=true, response=⏹ 已中断, sessionId, abortReason, iterations, totalTokens, totalTokensTotal`；
  - 不要顺手加当前没有的 key，也不要少任何一个。
- 这是 2026-11-04 修复的核心不变量；新增测试场景必须红控验证。

### 5.3 待审批（pending）

- 工具执行返回 pending：
  - 从 history 精确回滚 assistant(tool_calls)；
  - `committer.checkpointPending(record, new PendingInfo(approvalId, pendingToolCall))` 发 `sessionUpdated`（同一 roundId，带 pendingToolCall/approvalId）；
  - 产出 `PendingApproval`；
  - `commit` 写 context、**不发 chatFinished**、不写记忆、trace `pendingApproval`；
  - 终帧按当前行为发送。
- 恢复（RESUME_PENDING）：
  - Agent 查询 decision 后填入 `ApprovalResume`；
  - Turn 按 approved/rejected/lost/pending 处理：
    - approved：assistant(pendingToolCall) 入史 → resolve/execute；执行时系统参数带 `AI_P_APPROVAL_DECISION="approved"`（免复批，仅单任务批次），并按当前实现处理多任务告警；工具结果入史；
    - rejected/lost：追加对应的 user 文案；
    - pending：`EarlyReturn("审批请求仍在等待中…", asFinal=true)`，不进入 LLM 循环。

### 5.4 失败与早退

- LLM 无响应/HTTP 错误/Provider 错误 → `Failed`：
  - 不写 context、不发 chatFinished、保持 checkpoint；
  - 发 `streamError`；trace `error`；
  - 返回 `RESULT=false, response=<error>`。
- 会话准入/seed 构建失败（Provider 未就绪、缺 userMessage、会话忙、断点数据失败）由 Agent 在创建 Turn 前处理：
  - `terminalAsFinal=true` → `streamFinal`，否则 `streamError`；
  - 返回 terminalResult。
- Turn 内的 `EarlyReturn`（仅审批仍等待）也按上述规则发终帧，不进入 commit。

### 5.5 流式

- Turn 直接 `putMsg(deps.llmProvider, ...)` 发 LLM 请求；流式时 `RESULTFOR=seed.agentName`、`RESULTACTION=onStreamResult`，回调地址仍是父 Agent。
- Turn 创建 `StreamHandle` 并通过 `StreamHandleRegistry` 登记到 SessionRegistry；Agent 的 `onStreamResult` 按 sessionId 找到 handle 后唤醒 Turn。
- Turn 发 chunk/toolEvent/final/done/error 到注入的 `StreamSink`。
- 不改 Provider 的 `RESULTFOR` 字符串协议。
- `finalAnswerStreamed` 语义按当前实现：首轮流式无工具轮为 true；工具轮/控制态为 false；终帧按 sink 契约补差值或整体替换。

### 5.6 提交顺序

必须保持：

```
guardOutput → 写 context → 通知 SessionManager（chatFinished/chatAborted）→ 写长期记忆 → trace roundEnd → 起名 → 返回
```

护栏范围按当前 HEAD 保持：`Completed / Truncated / Rejected / Clarified / Timeout / DirectAnswer / PendingApproval` 都过 `guardOutput`；`Failed / EarlyReturn / Aborted` 不过。能定位到本轮终答下标（`finalAssistantIdx >= 0`）时，护栏后的文本要回写 history；直出（DirectAnswer）也必须回写，不能只改返回值/UI。

然后 Turn 用 `CommitResult.finalText` 发终帧。这样 UI 看到的与落盘的一致；护栏前的原文只用于 trace payload。

## 6. 边界与不变量

### 6.1 留在 Agent 的职责

- 配置解析、Provider/ToolManager/ToolExecutor/Context/Memory/SessionManager 生命周期；
- 消息分发、作为工具执行（SKILL_EXECUTE）、群组/工作流适配；
- 会话准入：同会话锁、monitor 注册、runCount begin/end、chatStream preBegan；
- `TurnSeed` 构建：历史加载、记忆召回、附件归一化、模板替换、prompt 注入、toolDefs；
- `TLToolExecutor` 小改：`ExecutionState.agentName` 优先读 `AI_P_AGENTNAME`，输出校验/trace 使用它；缺省回退 `fromWho.getName()`，现有 Agent 调用不变；
- `TurnCommitter` 实现：context/会话/记忆/护栏/起名/trace roundEnd；
- 流式回调地址 `onStreamResult`；
- 后台任务注入、审批查询、token 总账上报。

### 6.2 留在 SessionRegistry 的职责

- 会话锁（worker 线程）、取消标志、runCount、streamHandle、pendingInjections；
- `stopChat` 的 interrupt/进程清理/工具取消。

### 6.3 搬进 ChatTurn 的职责

- LLM 同步/流循环（原 `runChatLoop`）；
- tool_calls 解析/执行/结果入史（直接 `putMsg(toolManager/toolExecutor)`）；
- `TOOL_PROGRESS` 入站透传给父 Agent；
- rejected/pending/clarified/timeout/direct/truncated/aborted 的终态判定；
- 本轮 token/cache 累加；
- llmRequest/llmResponse trace 打点（通过 observer）；
- 终帧发送；
- 调用 committer 做 checkpoint/commit。

### 6.4 必须保持的兼容项

- 所有 action 名、返回 param key；返回字段以当前各分支的 `ret` 构造为准（Phase 3 用测试逐 key 锁定，不靠记忆）；
- `sessionUpdated`/`chatFinished`/`chatAborted` 的触发时机与 roundId；
- pending 不发 chatFinished、不写记忆；
- failed 不发 chatFinished、不写 context；
- 中断轮写 context + 追加「⏹ 已中断」；
- noHistory 不读写 context；`task_*` 不写长期记忆；
- `resumeRoundMessages` 前缀、同 roundId last-wins；
- `llmInput` 的 `msgStartIdx = history.size()`；
- 空 sessionId/rootSessionId/roundId/model/reasoningMode 的归一化（1103 系列）；
- 输出护栏作用范围与写回下标；
- 返回参数中的 iterations/tokens/cache/reasoning（所有非 abort 终态）；
- 会话起名 eligibility（按当前行为，包括 pending 首轮）；
- 流式终帧与 `finalAnswerStreamed` 语义。

### 6.5 终态持久化矩阵（一期必须逐项对齐）

| 终态 | context | SessionManager | 长期记忆 | 终帧 | 返回 |
|---|---|---|---|---|---|
| Completed | 写（noHistory 跳过） | chatFinished | 写 | final | RESULT=true + 全量统计 |
| Truncated | 写（noHistory 跳过）+ 截断提示入史 | chatFinished | 写 | final | RESULT=true + truncated |
| Rejected | 写（noHistory 跳过） | chatFinished | 写 | final | RESULT=true + rejected |
| Clarified | 写（noHistory 跳过） | chatFinished | 写 | final | RESULT=true + needsClarification |
| Timeout | 写（noHistory 跳过） | chatFinished | 写 | final | RESULT=true |
| DirectAnswer | 写（noHistory 跳过） | chatFinished | 写 | final | RESULT=true + finalAnswer |
| PendingApproval | 写（noHistory 跳过） | 仅 sessionUpdated（带 pendingToolCall/approvalId），**不 chatFinished** | 不写 | final | RESULT=true + 统计 |
| Aborted | 写（noHistory 跳过）+ sanitize + 「⏹ 已中断」 | chatAborted（completed） | 不写 | final | RESULT=true + cancelled |
| Failed | 不写 | 不通知（保留 checkpoint） | 不写 | error | RESULT=false |
| EarlyReturn | 不写 | 不通知 | 不写 | asFinal ? final : error | terminalResult |

## 7. 迁移计划（增量，每步可编译/可回滚）

### Phase 1：纯辅助抽取（低风险）

- 新增 `AttachmentPipeline`、`ToolResultAppender`；
- 从 `TLAiAgent` 原样搬入附件/工具结果方法，行为逐字保持；
- 更新调用点；跑 36 场景。

### Phase 2：ChatTurn 骨架 + 主循环迁移

- 新增 `ChatTurn`（瞬态 `TLBaseModule`，`turn:<agent>:<roundId>`、不 start/注册）、`TurnSeed`、`TurnDeps`、`TurnPlan`、`TurnOutcome`；
- Agent 的 `runChat` 仍负责准入与 seed 构建；构造 Turn 并调用 `run()`；
- 把 `runChatLoop` 的循环/工具执行/终态判定搬入 `ChatTurn`；Turn 直接 `putMsg(toolManager/toolExecutor/llmProvider)`；
- `TLToolExecutor` 增加 `agentName` 参数化（`AI_P_AGENTNAME` 优先，回退 `fromWho.getName()`），outputValidator/trace 用新的 `state.agentName`；
- Turn 发工具消息带 `AI_P_AGENTNAME=seed.agentName`；LLM 请求 `msg.setSource(seed.agentName)`；
- 实现 `checkMsgAction(TOOL_PROGRESS)` → 父 Agent 透传；用场景 15（工具实时进度）守；
- 终态持久化暂由 Agent 现有收尾段实现（通过 `TurnCommitter` 适配器），逐步替换；
- 跑 36 场景 + `checkpointResumeHistory`/`abortContext` 红控。

### Phase 3：TurnCommitter 收口

- 把现有收尾段（guard/context/chatFinished/chatAborted/memory/title/trace/return keys）搬入 `TurnCommitter`；
- Turn 只调用 `checkpoint`/`checkpointPending`/`commit`；
- 增加终态矩阵测试（见 §8）。

### Phase 4：清理

- 删除 `ChatRunContext`、旧 `runChatLoop`、旧 finalize 方法；
- `TLAiAgent` 只保留装配、准入、seed 构建、committer、分发、工具适配；
- 更新 CLAUDE.md/README 中关于 Agent 编排的描述（如涉及）。

每个 Phase 必须先编译通过、36 场景全绿，再进入下一步；任何行为差异必须在 Phase 内修正，不允许跨阶段累积。

## 8. 测试计划

### 8.1 回归

- `/test` 全部 36 场景（含场景 34/35）；
- 现有流式/审批/断点/工具/记忆场景不降级。

### 8.2 新增针对性测试

1. **中断轮持久化**：场景 35（已有）——abort 后 context 与会话都以「⏹ 已中断」收尾；红控：去掉写 context 必须 FAIL。
2. **早退流式终帧**：chatStream 缺 userMessage / Provider 未就绪 → 必须收到 streamError（或 streamFinal），不能挂 busy。
3. **直出护栏写回**：directAnswer 被 guardOutput 改写后，context/会话与返回/UI 一致。
4. **返回参数矩阵**：Completed/Aborted/Rejected/Pending/Clarified/Timeout/Truncated/Direct/Failed 的 `iterations`、token、cache、reasoning、rejected、finalAnswer 等 key 与当前一致。
5. **pending 恢复**：approved/rejected/lost/pending 四条分支；pending 不发 chatFinished、不写记忆。
6. **Turn 单测**：用 fake `IObject` provider/toolManager/toolExecutor + fake `TurnCommitter` 直接驱动 `ChatTurn`（用 no-factory 构造器；测试不依赖 putLog 的输出）；覆盖取消、工具解析失败、执行器无响应、迭代上限、TOOL_PROGRESS 透传。
7. **agentName 归属**：Turn 发工具调用带 `AI_P_AGENTNAME` 时，outputValidator/trace 看到的是 Agent 名而不是 `turn:...`；现有 Agent 调用不带该参数时，仍回退 `fromWho.getName()`，行为不变。

### 8.3 验证纪律

- 新守护断言必须做红控（临时还原修复必须 FAIL）；
- 每次提交前跑完整 36 场景；
- 发布库同步时排除测试文档与开发库专属文件（按既有双推惯例）。

## 9. 风险与缓解

| 风险 | 缓解 |
|---|---|
| Turn 变成新上帝对象 | 只保留执行闭环；附件/发送视图/工具结果/提交/观察者全部为协作者；设定 ChatTurn 目标上限（约 500–700 行） |
| TLBaseModule 瞬态但半初始化 | 只使用安全子集（createMsg/putMsg(IObject)/putLog/ThreadTask）；不 start/注册/按名寻址/读配置；构造器注释 + code review + 测试守卫 |
| 下游靠 `fromWho.getName()` 取 Agent 名 | Turn 用唯一名，不混用 Agent 名；工具执行带 `AI_P_AGENTNAME`，ToolExecutor 优先读它并回退旧路径；Provider trace 用 `msg.setSource(agentName)`；测试锁归属 |
| 工具进度回推到 Turn | `checkMsgAction(TOOL_PROGRESS)` 透传 `parentAgent`；场景 15 守 |
| 流式回调无法直达 Turn | 保持 Agent `onStreamResult` 为地址；Turn 持有 StreamHandle |
| 行为漂移 | 兼容清单 + 返回 key 矩阵 + 36 场景；Phase 3 前先补测试 |
| 会话锁/计数被误搬入 Turn | 明确留在 SessionRegistry；Turn 只拿取消令牌/句柄 |
| 每轮创建对象成本 | 瞬态 TLBaseModule 不 start/注册，构造成本仍低；对象只含引用/状态字段；不池化 |
| 配置热更新 | Seed 每轮快照；Turn 不缓存跨轮配置 |

## 10. 验收标准

1. `mvn -pl aiagent/common -am -DskipTests compile` 通过；整仓构建通过。
2. `/test` 36/36 通过；新增测试通过，红控有效。
3. 所有 action/param/流式帧/会话存储格式不变。
4. 中断轮、pending、failed、noHistory、resume、llmInput、输出护栏行为与当前 HEAD 逐项一致。
5. `TLAiAgent` 行数明显下降；`ChatTurn` 只保留执行闭环，不直接依赖 context/memory 模块。
6. `agent拆分/` 目录不被引用，不进入编译。
