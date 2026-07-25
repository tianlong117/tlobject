# ReAct / 思考链可视化 — 详细解释与实施计划

## Context

这是 AI Agent 框架改进路线图中的第 1 梯队高优先级改进项（#2）。当前 `doChat()` 和 Provider 层完全不处理 LLM 的推理/思考内容，导致 Agent 的推理过程（thought → action → observation → ...）对用户完全不可见。本方案让推理过程可被捕获、存储和展示。

---

## 一、详细概念解释

### 1.1 什么是 ReAct 思考链？

ReAct（Reasoning + Acting）是 LLM Agent 的核心工作模式：

```
用户提问
  ↓
[Thought] 我需要先查数据库获取用户信息...
  ↓
[Action] 调用 tool: db_query("SELECT * FROM users WHERE id=123")
  ↓
[Observation] 返回: {name: "张三", balance: 500}
  ↓
[Thought] 用户余额足够，接下来计算折扣...
  ↓
[Action] 调用 tool: calculate_discount(500, "VIP")
  ↓
[Observation] 返回: {finalPrice: 425}
  ↓
[Final Answer] 您好张三，您的VIP折扣后价格为425元
```

这个 thought → action → observation 的循环就是"思考链"。目前框架中，这些中间推理过程完全隐藏在 LLM 和 tool 的来回调用中，用户只看到最终的 `aiResponse`。

### 1.2 两种推理来源

做思考链可视化有两种互补的技术路线：

#### 路线 A：System Prompt 引导（通用方案，适用于所有模型）

在 system prompt 中要求 LLM 用特定格式输出思考过程：

```
在每次调用工具前，请用 <thinking>...</thinking> 标签包裹你的推理过程。
格式示例：
<thinking>用户询问订单状态，我需要先查询订单表...</thinking>
然后调用 tool: query_order(...)
```

优点：**所有模型都支持**（GPT-4、DeepSeek-V3、Qwen 等），不需要模型原生 reasoning 能力。
缺点：额外消耗 output tokens（思考内容算在输出里），推理质量不如原生 reasoning。

#### 路线 B：LLM 原生 Reasoning（特定模型，高质量）

现代推理模型在 API 层面区分"内部思考"和"外部输出"：

| 模型 | 原生机制 | API 字段 |
|------|---------|---------|
| **DeepSeek-R1** | 强制 CoT 推理 | 非流式: `choices[0].message.reasoning_content`<br>流式: `choices[0].delta.reasoning_content` |
| **DeepSeek-V3.1/V4** | 可选 thinking 模式 | 同上，需请求参数开启 |
| **Claude Fable 5 / Opus 4.8** | Extended Thinking | 响应: `content[]` 中的 `"type": "thinking"` 块<br>流式: `thinking_delta` / `signature_delta` 事件<br>需请求参数: `"thinking": {"type": "enabled", "budget_tokens": 4000}` |
| **OpenAI o-series** | 内部 reasoning | `reasoning_tokens` 用量字段，reasoning 内容通常不暴露给 API |

**关键区别**：
- 原生 reasoning 的 thinking token **不占用** `max_tokens` 预算，由模型内部管理
- 原生 reasoning 质量通常更高（模型专门训练过）
- DeepSeek-R1 默认开启且**不可关闭**，`reasoning_content` 会一直返回
- Claude Extended Thinking **必须显式传 `thinking` 参数**，否则 API 报错
- Claude 同时支持 `thinking` 参数 + system prompt 级别的思考引导

### 1.3 当前状态：两条路线都未实现

经过全面代码审查，确认当前框架中：

| 组件 | 状态 |
|------|------|
| `TLOpenAiProvider.parseResponse()` | ❌ 只解析 `message.content` 和 `message.tool_calls`，忽略 `reasoning_content` |
| `TLOpenAiProvider.StreamCallback` | ❌ 只处理 `delta.content` 和 `delta.tool_calls`，忽略 `delta.reasoning_content` |
| `TLClaudeProvider.parseResponse()` | ❌ 只处理 `"text"` 和 `"tool_use"` 块类型，遇到 `"thinking"` 块走进 default 被静默丢弃 |
| `TLClaudeProvider.ClaudeStreamCallback` | ❌ 只处理 `text_delta` 和 `input_json_delta`，不处理 `thinking_delta` / `signature_delta` |
| `TLClaudeProvider.buildRequestBody()` | ❌ 没有 `thinking` 参数，用 Fable 5 会直接报错 |
| `TLConversationHistory.Role` | ❌ 只有 `system/user/assistant/tool`，没有 `reasoning` 类型 |
| `TLAiAgent.doChat()` | ❌ 不解析推理内容，不存储到历史 |
| `TLChatConsole` | ❌ 只显示 `AI > ` 文本，无法区分推理和最终回复 |
| `TLStreamCallback` | ❌ 不区分 reasoning 和 content chunk |

模型列表中有 `claude-fable-5-20250619`，但**实际无法使用**（缺 `thinking` 参数会导致 API 报错）。

### 1.4 什么情况下用哪种推理？

| 场景 | 推荐方案 | 原因 |
|------|---------|------|
| **复杂多步推理**（数学、逻辑、规划） | 原生 reasoning（路线 B） | 质量高，不占用 output token 配额 |
| **简单对话 / 单步 tool call** | System prompt 引导（路线 A） | 够用，不依赖特定模型 |
| **DeepSeek-R1 模型** | 必须用路线 B | R1 强制返回 `reasoning_content`，不处理会丢数据 |
| **Claude Fable 5 / Opus 4.8** | 必须用路线 B | 扩展思考模型**强制**要求 `thinking` 参数 |
| **DeepSeek-V3/V4, GPT-4** | 路线 A 或用户选择 | 这些模型不支持原生 reasoning，只能靠 prompt 引导 |
| **流式场景下想看实时思考** | 两条路线都支持 | 都可以在流式回调中实时推送思考内容到 UI |

---

## 二、配置开关设计（核心）

### 2.0 推理模式配置体系

用户的核心诉求：**推理功能必须有开关，默认关闭，按需选择 A/B 路线**。

#### 三层开关架构

```
XML 配置（全局默认）
    ↓ 可被覆盖
Session 级（/thinking 命令切换）
    ↓ 可被覆盖
单次请求（TLMsg 参数）
```

#### 配置参数定义

| 参数名 | 可选值 | 默认值 | 说明 |
|--------|-------|--------|------|
| `reasoningMode` | `"off"` / `"prompt"` / `"native"` / `"auto"` | `"off"` | 推理模式选择 |
| `reasoningVisible` | `true` / `false` | `true` | 推理内容是否暴露给用户（即使 capture 了也可以不展示） |
| `thinkingBudget` | 整数（token 数） | `4000` | Claude Extended Thinking 预算，仅 native 模式生效 |

#### `reasoningMode` 四种模式详解

| 模式 | 行为 | 适用模型 |
|------|------|---------|
| **`"off"`** | 完全不处理推理。system prompt 不加引导，Provider 忽略 reasoning 字段。**默认值。** | 所有模型，追求省 token / 快速响应 |
| **`"prompt"`** | 路线 A：在 system prompt 中注入 `💭` 引导指令，LLM 在文本中输出思考。`doChat()` 解析 `💭` 前缀分离思考和回复。 | DeepSeek-V3/V4、GPT-4、Qwen 等普通模型 |
| **`"native"`** | 路线 B：Provider 层解析原生 reasoning（`reasoning_content` / `thinking` 块），不走 prompt 引导。 | DeepSeek-R1、Claude Fable 5/Opus 4.8 |
| **`"auto"`** | 自动判断：模型支持原生 reasoning → 走 native；否则 → 走 prompt。判断逻辑在 Agent 层。 | 混合模型场景，一劳永逸 |

#### 特殊边界情况

| 情况 | 处理方式 |
|------|---------|
| **DeepSeek-R1 + reasoningMode=off** | R1 **强制**返回 `reasoning_content`，Provider 仍需解析（否则丢数据），但解析后不入历史/不传给用户，只打 debug 日志 |
| **Claude Fable 5 + reasoningMode=off** | Fable 5 **强制**要求 `thinking` 参数，此时自动发送 `thinking: {type: "enabled", budget_tokens: 最小}`，但响应中的 thinking 块不入历史。**这是模型硬约束，无法绕过。** |
| **普通模型 + reasoningMode=native** | 普通模型不返回 `reasoning_content`，native 模式静默退化（不报错），等价于 off |
| **reasoningMode=prompt + 模型已有原生 reasoning** | prompt 引导和原生 reasoning 可能**叠加**（两个来源的思考都出现）。建议用 auto 避免此情况 |

#### XML 配置示例

```xml
<!-- TLAiAgent 模块参数：全局默认关闭 -->
<modulesParams>
    <module name="aiagent" reasoningMode="off" reasoningVisible="false" thinkingBudget="4000"/>
</modulesParams>

<!-- 某个特定 Agent 开启 prompt 模式 -->
<modulesParams>
    <module name="myDeepThinkingAgent" reasoningMode="prompt" reasoningVisible="true"/>
</modulesParams>

<!-- Claude Agent 开启原生 reasoning -->
<modulesParams>
    <module name="myClaudeAgent" reasoningMode="native" thinkingBudget="8000"/>
</modulesParams>
```

#### 运行时切换（TLChatConsole 命令）

```
/thinking off      → 关闭推理
/thinking prompt   → 切换到 prompt 引导模式
/thinking native   → 切换到原生 reasoning 模式
/thinking auto     → 自动判断
/thinking          → 查看当前模式
```

#### 单次请求覆盖（TLMsg 参数）

```java
TLMsg msg = createMsg()
    .setAction(AGENT_CHAT)
    .setParam("userMessage", "帮我分析这份财报")
    .setParam("reasoningMode", "native")   // 本次请求强制使用原生推理
    .setParam("reasoningVisible", true);
putMsg("aiagent", msg);
```

---

## 三、实施计划

### 概览

分四层实现：**配置层（开关）→ Provider 层（解析）→ Agent 层（存储）→ 展示层（UI）**。路线 A 和 B 通过 `reasoningMode` 开关统一控制。

### 3.1 配置层：开关参数定义与传递

#### 3.1.1 `TLAiAgentParamString` — 新增常量和默认值解析

**文件**: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAiAgentParamString.java`

新增常量：
```java
// 推理模式
String AI_P_REASONING_MODE = "reasoningMode";        // off | prompt | native | auto
String AI_P_REASONING_VISIBLE = "reasoningVisible";   // true | false
String AI_P_THINKING_BUDGET = "thinkingBudget";       // Claude thinking token 预算
// 推理内容
String AI_P_REASONING = "reasoning";                  // 推理文本
String AI_P_REASONING_CHUNK = "reasoningChunk";       // 流式推理块
String AI_P_REASONING_STEPS = "reasoningSteps";       // 结构化推理步骤 List
```

### 3.2 数据结构层

#### 3.2.1 `TLConversationHistory` — 新增 `reasoning` 角色

**文件**: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLConversationHistory.java`

- `Role` 枚举新增: `reasoning`
- 新增字段: `String reasoningContent` — 存储思考文本
- 新增便捷构造器: `TLConversationHistory(Role.reasoning, String reasoningContent)`

### 3.3 Provider 层：原生 reasoning 解析（路线 B）

Provider 层**只负责解析**，不判断开关。开关逻辑在 Agent 层统一处理——Agent 根据 `reasoningMode` 决定是否将 reasoning 存入历史/返回给调用方。Provider 只忠实地把 API 返回的 reasoning 字段填入 TLMsg 参数。

#### 3.3.1 `TLOpenAiProvider` — DeepSeek reasoning_content 支持

**文件**: `aiagent/provider-openai/src/main/java/.../TLOpenAiProvider.java`

**非流式 `parseResponse()`** (第 150-214 行)：
在解析 `message.content` 后新增：
```java
// 解析 reasoning_content (DeepSeek R1/V3.1/V4)
if (message.has("reasoning_content") && !message.get("reasoning_content").isJsonNull()) {
    result.setParam(AI_P_REASONING, message.get("reasoning_content").getAsString());
}
```

**流式 `StreamCallback.onResponse()`** (第 402-513 行)：
在 `delta` 处理中新增：
```java
// 思考块 (DeepSeek R1 reasoning_content)
if (delta != null && delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
    String reasoningText = delta.get("reasoning_content").getAsString();
    reasoningBuilder.append(reasoningText);
    TLMsg reasoningChunkMsg = createMsg()
            .setAction(resultAction)
            .setParam(AI_P_REASONING_CHUNK, reasoningText)
            .setParam(AI_P_SESSIONID, sessionId);
    putMsg(resultFor, reasoningChunkMsg);
}
```
并在完成信号中包含: `doneMsg.setParam(AI_P_REASONING, reasoningBuilder.toString())`

#### 3.3.2 `TLClaudeProvider` — Claude Extended Thinking 支持

**文件**: `aiagent/provider-claude/src/main/java/.../TLClaudeProvider.java`

**请求体 `buildRequestBody()`** (第 70-178 行)：
- 读取 `AI_P_REASONING_MODE`，若为 `"native"` 或 `"auto"`（且模型支持）时添加 `thinking` 参数
- **特殊处理**：检测模型名包含 `claude-fable-5` 或 `claude-opus-4` 时，**强制**发送最小 `thinking` 参数（即使模式为 off），因为这是 API 硬约束
- Claude 的 `thinking` 参数与 `temperature`/`top_p` 冲突，开启时移除这些字段

**非流式 `parseResponse()`** (第 183-248 行)：
- `content[]` 循环中新增 case: `"thinking"` → 设置 `AI_P_REASONING`
- `"redacted_thinking"` → 设置 `AI_P_REASONING` + 标记 `redacted=true`

**流式 `ClaudeStreamCallback`** (第 311-464 行)：
- `content_block_start` 事件：检测 `"thinking"` 类型，开始累积
- `content_block_delta` 事件：处理 `"thinking_delta"` 和 `"signature_delta"`
- `content_block_stop` 事件：完成一个 thinking 块
- 实时推送 `AI_P_REASONING_CHUNK` 消息

#### 3.3.3 `TLStreamCallback` — 区分 reasoning 和 content chunk

**文件**: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLStreamCallback.java`

新增 `StringBuilder reasoningBuffer`，在 `STREAM_ONCHUNK` 消息处理中区分 `AI_P_CHUNK`（普通文本）和 `AI_P_REASONING_CHUNK`（思考内容）。

### 3.4 Agent 层：开关判断 + 推理存储（路线 A + B 统一入口）

这是开关逻辑的核心所在。`TLAiAgent` 在 `doChat()` 入口处读取 `reasoningMode`，根据模式执行不同的推理获取策略，Provider 只负责解析、Agent 负责开关决策。

#### 3.4.1 `TLAiAgent` — 模式读取与开关逻辑

**文件**: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAiAgent.java`

新增成员变量（从 XML params 初始化）:
```java
private String reasoningMode = "off";      // off | prompt | native | auto
private boolean reasoningVisible = true;
private int thinkingBudget = 4000;
```

**`doChat()` 中的开关判断逻辑**（伪代码）:
```java
// 1. 获取本次请求的推理模式（请求级覆盖 > session级 > 全局默认）
String effectiveMode = msg.getParam("reasoningMode", this.reasoningMode);
boolean effectiveVisible = msg.getParam("reasoningVisible", this.reasoningVisible);

// 2. 根据模式决定行为
switch (effectiveMode) {
    case "off":
        // 不注入 prompt，Provider 返回的 reasoning 字段不入历史
        break;
    case "prompt":
        // 注入 💭 引导到 system prompt（通过修改 context 首条 system 消息）
        // doChat() 解析响应中 💭 前缀 → 分离 reasoning 和 content
        break;
    case "native":
        // 传递 AI_P_REASONING_MODE=native 给 Provider
        // Provider 解析原生 reasoning_content/thinking 块
        break;
    case "auto":
        // 检测当前模型是否支持原生 reasoning → native，否则 prompt
        break;
}

// 3. LLM 调用后，统一处理 reasoning 的存储和返回
if (effectiveMode != "off") {
    // 从 LLM 返回中提取 AI_P_REASONING
    // 创建 TLConversationHistory(Role.reasoning, text)
    // 插入到该轮 assistant 消息之前
}
if (!effectiveVisible) {
    // reasoning 存入了历史但不放入最终返回的 TLMsg
    // 调用方拿不到 reasoning，只能通过 /thinking 命令在控制台看
}
```

**`auto` 模式的判断逻辑**（放在 TLAiAgent 中）:
```java
private String resolveAutoMode(String model) {
    // 模型名包含这些关键词 → 支持原生 reasoning
    if (model.contains("r1") || model.contains("reasoner")) return "native";     // DeepSeek-R1
    if (model.contains("fable-5") || model.contains("opus-4")) return "native";  // Claude thinking
    if (model.contains("o1") || model.contains("o3") || model.contains("o4")) return "native"; // OpenAI o-series
    // 其余模型走 prompt 引导
    return "prompt";
}
```

#### 3.4.2 路线 A：System Prompt 引导（prompt 模式）

当 `reasoningMode=prompt` 时，在 system prompt 末尾追加引导：

```
## 推理规则
当需要调用工具时，请先用 💭 开头写一行简短推理，说明你为什么要调用这个工具。
格式: 💭 <一句话推理>
然后正常调用工具。不需要在最终回复中保留 💭 内容。
```

> 这段引导通过修改 `aiContext` 的首条 system 消息内容来注入。不在配置中写死，而是 `doChat()` 动态拼接。

**解析逻辑**：`doChat()` 每次收到 LLM 响应后，检查文本是否以 `💭` 开头——若是，提取以 `💭` 开头的所有连续行作为 reasoning，剩余部分为正式 content。

#### 3.4.3 推理内容汇总返回

最终返回的 TLMsg 新增参数（仅当 `reasoningMode != off` 且 `reasoningVisible=true`）:

| Key | 类型 | 说明 |
|-----|------|------|
| `reasoning` | String | 全部推理文本汇总 |
| `reasoningSteps` | List | 结构化推理步骤，每步含 `thought`/`action`/`observation` |

### 3.5 展示层：控制台折叠/展开（受开关控制）

#### 3.5.1 `TLChatConsole` — 显示格式化和 `/thinking` 命令

**文件**: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLChatConsole.java`

- 新增配置项: `showReasoning`（默认 `true`，即遵循 Agent 返回的 reasoningVisible）
- 新增命令: `/thinking [off|prompt|native|auto]` — 切换推理模式，无参数时显示当前状态
- `/thinking` 命令实际上是通过发消息给 Agent 修改 session 级 `reasoningMode`，不直接改全局配置
- 显示效果（折叠时，reasoning 默认折叠，用户可展开）：
  ```
  AI > 💭 推理过程 (3步) [展开↓]
       您好张三，您的VIP折扣后价格为425元
  ```
- 显示效果（展开时）：
  ```
  AI > 💭 Step 1: 查询用户信息 → db_query → 返回张三/VIP
       💭 Step 2: 计算折扣 → calculate_discount → 425元
       💭 Step 3: 确认结果合理，准备回复
       ---
       您好张三，您的VIP折扣后价格为425元
  ```
- 当 `reasoningMode=off` 时，不显示任何推理相关内容，`/thinking` 命令输出 `推理模式: 关闭`

#### 3.5.2 响应结构扩展（供未来 Web UI 等消费方使用）

在 `TLAiAgent.doChat()` 的最终返回 TLMsg 中新增（仅 reasoningMode != off 且 reasoningVisible=true 时）：

| Key | 类型 | 说明 |
|-----|------|------|
| `reasoning` | String | 全部推理文本汇总 |
| `reasoningSteps` | List | 结构化的推理步骤列表，每步含 `thought`/`action`/`observation` |

### 3.6 验证方案

1. **开关验证**：
   - 默认模式 `off` → 发一次带 tool call 的对话 → 确认返回的 TLMsg **不含** `reasoning` 字段
   - `/thinking prompt` → 再次对话 → 确认 LLM 输出了 `💭` 内容且被正确分离
   - `/thinking native`（用 DeepSeek-R1）→ 确认 `reasoning_content` 被正确捕获
   - `/thinking off` → 确认不再显示推理

2. **Provider 层验证**：
   - 用 DeepSeek-R1 模型发非流式 chat → 确认 `AI_P_REASONING` 在 Provider 返回的 TLMsg 中不为空
   - 用 DeepSeek-R1 模型发流式 chat → 确认 `AI_P_REASONING_CHUNK` 逐块到达
   - 用 Claude Fable 5 + `reasoningMode=native` → 确认 `thinking` 块被正确解析
   - 用 Claude Fable 5 + `reasoningMode=off` → 确认仍能正常调用（自动发最小 thinking 参数满足 API 约束）

3. **Agent 层验证**：
   - 跑 `AiAgentDemoModule` 场景1+6，分别用 off/prompt/native 三种模式 → 确认 reasoning 字段的出现/消失符合预期
   - 查看 `TLAiContext` 的历史消息 → 确认 `Role.reasoning` 条目在开启时存在、关闭时不存在

4. **展示层验证**：
   - 启动 `TLChatConsole` → `/thinking` 查看当前模式 → `/thinking prompt` → 对话 → 确认 `💭` 折叠/展开
   - 确认 reasoning 折叠时不干扰正常回复阅读

---

## 四、文件变更清单

| 文件 | 变更类型 | 内容 |
|------|---------|------|
| `aiagent/common/.../TLAiAgentParamString.java` | 修改 | 新增 `reasoningMode`/`reasoningVisible`/`thinkingBudget`/`reasoning`/`reasoningChunk`/`reasoningSteps` 常量 |
| `aiagent/common/.../TLConversationHistory.java` | 修改 | 新增 `reasoning` 角色 + `reasoningContent` 字段 |
| `aiagent/common/.../TLAiAgent.java` | 修改 | **核心变更**：读取 `reasoningMode` 开关 → 分发 prompt/native 路线 → 解析 💭 → 存储 reasoning 历史 → 汇总返回；`auto` 模式判断逻辑 |
| `aiagent/common/.../TLStreamCallback.java` | 修改 | 区分 `AI_P_CHUNK` vs `AI_P_REASONING_CHUNK` |
| `aiagent/common/.../TLChatConsole.java` | 修改 | `/thinking [off\|prompt\|native\|auto]` 命令 + 推理折叠/展开显示 |
| `aiagent/provider-openai/.../TLOpenAiProvider.java` | 修改 | `parseResponse()` + `StreamCallback` 解析 `reasoning_content`（不做开关判断） |
| `aiagent/provider-claude/.../TLClaudeProvider.java` | 修改 | `buildRequestBody()` 按模型名自动/手动发 `thinking` 参数；`parseResponse()` + `ClaudeStreamCallback` 解析 thinking 块 |
