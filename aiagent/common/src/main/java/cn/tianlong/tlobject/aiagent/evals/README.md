# Agent 评测体系（Evals）使用手册

> **创建日期**：2026-07-29  
> **作者**：tianlong  
> **版本**：1.1

---

## 目录

1. [概述](#1-概述)
2. [架构总览](#2-架构总览)
3. [快速上手](#3-快速上手)
4. [配置文件](#4-配置文件)
5. [控制台命令](#5-控制台命令)
6. [编写测试用例](#6-编写测试用例)
7. [三种调用模式](#7-三种调用模式)
   - [7.1 agent_chat — 评测 Agent](#71-agent_chat--评测-agent)
   - [7.2 skill_execute — 评测 Skill](#72-skill_execute--评测-skill)
8. [三种评判器详解](#8-三种评判器详解)
   - [8.1 精确匹配评判器 (exact_match)](#81-精确匹配评判器-exact_match)
   - [8.2 约束检查评判器 (constraint)](#82-约束检查评判器-constraint)
   - [8.3 LLM 裁判评判器 (llm_judge)](#83-llm-裁判评判器-llm_judge)
9. [评测报告](#9-评测报告)
10. [扩展开发](#10-扩展开发)
11. [最佳实践](#11-最佳实践)
12. [常见问题](#12-常见问题)

---

## 1. 概述

Agent 评测体系（Evals）是 tlobject AI Agent 框架的**内置自动化评测模块**。它通过预定义的 JSON 用例文件，自动向目标模块发送消息、收集回复、执行多项评判检查，最终生成结构化的评测报告（JSON 格式）。

### 1.1 核心能力

| 能力 | 说明 |
|------|------|
| **多目标评测** | 支持评测 Master Agent、子 Agent、Skill，不同用例可指定不同目标模块 |
| **两种调用模式** | `agent_chat`（评测 Agent 对话能力）和 `skill_execute`（评测 Skill 独立能力） |
| **三类评判器** | exact_match（精确匹配）、constraint（规则约束）、llm_judge（LLM 裁判） |
| **多评判组合** | 每条用例可配置多个评判器，全部通过才算 pass |
| **全维度指标** | 记录回复文本、Token 用量、迭代次数、Tool 调用、响应延迟 |
| **批量 + 单用例** | 支持全量 suite 运行、单用例运行、内置 quick 自检 |
| **控制台集成** | 通过 `/eval` 命令在 TLChatConsole 中直接操作 |
| **会话隔离** | 每个 agent_chat 用例使用独立 session，自动清理，互不干扰 |

### 1.2 适用场景

- **回归测试**：Agent 升级后跑全量用例，确保核心能力不受影响
- **质量评估**：对新 Prompt/新 Skill 做 LLM 裁判评估回答质量
- **性能监控**：通过约束检查监控 Token 用量、延迟、迭代次数是否在预期范围内
- **Skill 单元测试**：直接向 Skill 发送参数，验证 Skill 的输入→输出正确性
- **子 Agent 独立验证**：绕过 Master Agent，单独测试某个子 Agent 的能力
- **CI/CD 集成**：报告为机器可读 JSON，可接入自动化流水线

---

## 2. 架构总览

### 2.1 文件清单

所有核心文件位于包 `cn.tianlong.tlobject.aiagent.evals`：

```
aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/evals/
├── TLEvalsModule.java        # 主控编排器（加载用例 → 调用目标 → 评判 → 出报告）
├── TLEvalCase.java           # 评测用例 POJO
├── TLEvalJudge.java          # 评判器接口
├── TLExactMatchJudge.java    # 精确匹配评判器
├── TLLlmJudge.java           # LLM-as-Judge 评判器
├── TLConstraintJudge.java    # 规则约束评判器
├── TLEvalVerdict.java        # 评判裁决 POJO
├── TLEvalRunResult.java      # 单条用例运行结果 POJO
├── TLEvalReport.java         # 评测报告 POJO（含 Summary 内部类）
├── JudgeConfig.java          # 评判器配置 POJO
├── JudgeContext.java         # 评判上下文（模块引用）
└── README.md                 # 本文档
```

### 2.2 评测流程

```
用户输入 /eval suite
        │
        ▼
┌─────────────────────┐
│  TLChatConsole      │  发起评测命令
│  handleEval()       │
└────────┬────────────┘
         │ putMsg("evals", runEvalSuite)
         ▼
┌─────────────────────────────────────────────┐
│  TLEvalsModule.runEvalSuite()               │
│                                             │
│  1. scanCaseFiles() — 扫描 JSON 用例文件     │
│  2. for each case:                          │
│     ┌──────────────────────────────────┐    │
│     │  runSingleCase(case)              │    │
│     │                                   │    │
│     │  a. 解析 callType + targetAgent    │    │
│     │  b. agent_chat → runAgentChatCase()│    │
│     │     skill_execute → runSkillCase() │    │
│     │  c. 收集 response/tokens/latency   │    │
│     │  d. runAllJudges() — 执行所有评判   │    │
│     │  e. finally: 清理 session(如适用)   │    │
│     └──────────────────────────────────┘    │
│  3. buildReport() — 构建报告                │
│  4. saveReport() — 保存 JSON 报告           │
│  5. printSummary() — 打印控制台摘要          │
└─────────────────────────────────────────────┘
```

### 2.3 评判逻辑

- 每条用例可配置 **0 个或多个** 评判器
- 未配置评判器时默认 `passed = true`
- 所有评判器 **全部返回 pass** 且 **无 error**，该用例才判定为 `passed`
- 评判器按配置顺序依次执行，前一个失败不影响后续执行

---

## 3. 快速上手

### 3.1 前提条件

1. 项目已成功编译运行（`mvn clean install -DskipTests`）
2. `aiagent` 模块已正确配置并启动
3. evals 模块已在模块工厂中注册

### 3.2 三步开始评测

**第一步：确认模块已注册**

在 `moduleFactory_config.xml` 中确认已包含：

```xml
<msg action="getModule" destination="moduleFactory" moduleName="evals"/>
```

在 `aiagent_config.xml` 中确认模块定义：

```xml
<module name="evals" classfile="cn.tianlong.tlobject.aiagent.evals.TLEvalsModule"
        singleton="true" configfile="CLASSPATH/conf/demo/aiagent/evals_config.xml"/>
```

**第二步：启动 Demo 应用**

```bash
mvn exec:java -pl demo/tlobject
```

**第三步：在控制台中运行**

```
> /eval list          # 查看有哪些用例
> /eval quick         # 快速自检
> /eval suite         # 运行全部用例
> /eval run math-simple-001   # 运行指定用例
```

---

## 4. 配置文件

### 4.1 evals_config.xml

文件路径：`conf/demo/aiagent/evals_config.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<moduleConfig>
    <params>
        <!-- 评测用例 JSON 文件所在目录 -->
        <evalCaseDir value="conf/demo/aiagent/evals/cases/"/>

        <!-- 评测报告输出目录 -->
        <reportOutputDir value="data/evals/reports/"/>

        <!-- 被评测的默认目标模块名（可被用例级 targetAgent 覆盖） -->
        <targetAgent value="aiagent_master"/>

        <!-- LLM-as-Judge 使用的 Provider -->
        <judgeProvider value="myopenAiProvider"/>

        <!-- 上下文模块（用于提取 tool calls） -->
        <contextModule value="aiContext"/>
    </params>
</moduleConfig>
```

### 4.2 参数说明

| 参数 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `evalCaseDir` | 否 | `conf/demo/aiagent/evals/cases/` | JSON 用例文件目录，支持文件系统和 classpath 双路径 |
| `reportOutputDir` | 否 | `data/evals/reports/` | 评测报告输出目录，自动创建 |
| `targetAgent` | 否* | `aiagent` | **默认**被评测的模块名。每条 JSON 用例可通过 `targetAgent` 字段覆盖 |
| `judgeProvider` | 否* | `openAiProvider` | LLM 裁判使用的 Provider 模块名。仅在使用 `llm_judge` 时需要 |
| `contextModule` | 否 | `aiContext` | 上下文模块名，用于提取 tool calls 和执行后清理（仅 agent_chat 模式） |

> \* `targetAgent` 和 `judgeProvider` 可在 JSON 用例中覆盖，因此 XML 中的值仅作为默认值。

---

## 5. 控制台命令

评测通过 TLChatConsole 中的 `/eval` 命令进行操作。

### 5.1 命令一览

| 命令 | 说明 | 示例 |
|------|------|------|
| `/eval` 或 `/eval help` | 显示用法帮助 | `/eval` |
| `/eval suite` | 运行 `evalCaseDir` 下全部 JSON 用例 | `/eval suite` |
| `/eval list` | 列出所有可用用例（ID + 名称 + 评判器类型 + 目标模块） | `/eval list` |
| `/eval quick` | 运行内置快速自检（硬编码的 constraint 用例） | `/eval quick` |
| `/eval run <id>` | 按 ID 或名称运行指定单个用例 | `/eval run math-simple-001` |
| `/eval cascade [agent]` | **级联评测**：自动发现子 Agent/Skill，为每个生成基础用例并运行 | `/eval cascade` 或 `/eval cascade aiagent_master` |

### 5.2 命令输出示例

**`/eval list`**
```
===== 可用评测用例 (5 条) =====
  [basic-chat-001]      基本对话-自我介绍                    评判: constraint
  [suite-constraint-001] 约束检查-回复长度和迭代               评判: constraint
  [test-skill-001]      测试httpRequestSkill                 评判: exact_match,constraint
  [llm-judge-demo-001]   LLM-Judge 示例-回答质量评估          评判: constraint,llm_judge
  [math-simple-001]      简单数学计算                         评判: exact_match,constraint
```

**`/eval suite`**
```
========== 开始评测 5 条用例 ==========
[1/5] 运行: 基本对话-自我介绍 (basic-chat-001)
[PASS] 基本对话-自我介绍 (basic-chat-001) | tokens=2116 iterations=1 latency=2177ms
  [√] constraint: score=1.00 全部 2 项约束通过

...

========== 评测报告 ==========
总计: 5 | 通过: 4 | 失败: 1 | 通过率: 80.0%
平均 Tokens: 2226 | 平均迭代次数: 1.0 | 平均延迟: 3705ms
--- 失败用例 ---
  [FAIL] 简单数学计算 (math-simple-001)
==============================
报告已保存: D:\...\data\evals\reports\eval_report_20260729_095918.json
```

### 5.3 级联评测 `/eval cascade [agent]`

**自动发现 + 手写用例优先 + 自动兜底**。指定一个 Agent，系统自动发现其所有子模块，**优先匹配 `cases/` 目录中的手写用例，没有才自动生成冒烟测试**，最终输出合并报告。

#### 执行流程

```
/eval cascade aiagent_master
  │
  ├─ 1. 查询 moduleRegistry: REGISTRY_LIST ownerFamilyName="aiagent_master"
  │     → 过滤：仅保留 IAgentCapable + TLBaseSkill 类型
  │
  ├─ 2. 预扫描 cases/ 目录所有 JSON → 按 targetAgent 建索引
  │     Map<familyName, List<TLEvalCase>>
  │
  ├─ 3. 遍历每个目标:
  │     ├─ caseIndex 有匹配 → 使用手写用例（可能多条）
  │     └─ 没有匹配       → 自动生成冒烟用例
  │
  └─ 4. 合并报告
```

#### 用例匹配规则

手写用例的 `targetAgent` 字段与模块的家族名精确匹配即被选用：

```json
{
  "targetAgent": "aiagent_master:priceTeam",   // ← 匹配 priceTeam 模块
  "callType": "agent_chat",
  "input": "我要买一个汉堡",
  "judges": [...]
}
```

> **只需往 `cases/` 目录放 `.json` 文件**，`/eval cascade` 自动发现并匹配，无需额外配置。
```

**输出示例**：
```
========== 级联评测 8 个目标（2 有手写用例）==========
[1] aiagent_master/aiagent_master — 自动生成
[PASS] 级联-aiagent_master (...) | tokens=2116 iterations=1 latency=2177ms
[2] httpRequestSkill/aiagent_master:httpRequestSkill — 手写用例: 测试httpRequestSkill-百度首页
[PASS] 测试httpRequestSkill-百度首页 | tokens=0 iterations=0 latency=234ms
[PASS] 测试httpRequestSkill-百度首页 | tokens=0 iterations=0 latency=189ms (多条用例)
[3] priceTeam/aiagent_master:priceTeam — 手写用例: 价格计算-单商品
[PASS] 价格计算-单商品 | tokens=850 iterations=2 latency=3200ms

========== 评测报告 ==========
总计: 8 | 通过: 7 | 失败: 1 | 通过率: 87.5%
==============================
级联报告已保存: D:\...\data\evals\reports\eval_report_20260729_140000.json
```

> 每个目标块开头显示 `— 手写用例` 或 `— 自动生成`，一目了然。

#### 与其他命令的关系

| 命令 | 用例来源 | 用途 |
|------|----------|------|
| `/eval suite` | 手写 JSON 文件 | 精细化回归测试 |
| `/eval cascade` | 自动发现生成 | 快速冒烟测试，一键覆盖全链路 |
| `/eval run <id>` | 手写 JSON 文件 | 单用例调试 |

> **建议**：日常开发用 `/eval cascade` 快速验证全链路可用性；发版前用 `/eval suite` 跑完整的手写用例。

---

## 6. 编写测试用例

### 6.1 文件格式

用例文件为 JSON 格式，放在 `evalCaseDir` 配置的目录下。支持两种结构：

- **单用例文件**：一个 JSON 对象 → 一条用例
- **套件文件**：一个 JSON 数组 `[...]` → 多条用例

文件必须以 `.json` 结尾，加载时按文件名排序。

### 6.2 用例 JSON 结构

```json
{
  "id": "用例唯一标识",
  "name": "用例名称（人类可读）",
  "targetAgent": "目标模块名（可选，覆盖全局配置）",
  "callType": "agent_chat（默认）| skill_execute",
  "input": "发送的消息内容（agent_chat 为字符串，skill_execute 为 JSON 对象）",
  "expectedOutput": "期望的回复文本（exact_match 评判用，可选）",
  "expectedToolCalls": ["期望调用的工具名列表（可选）"],
  "metadata": {
    "category": "分类标签",
    "difficulty": "easy|medium|hard",
    "tags": ["自定义标签"]
  },
  "judges": [
    { "type": "exact_match", "config": { ... } },
    { "type": "constraint",   "config": { ... } },
    { "type": "llm_judge",    "config": { ... } }
  ]
}
```

### 6.3 字段详细说明

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | string | **是** | 用例唯一标识。用于 `/eval run <id>` 定位，建议使用 `kebab-case` |
| `name` | string | 建议填写 | 人类可读的名称，显示在用例列表和报告中 |
| `targetAgent` | string | 否 | 用例级目标模块名，覆盖全局配置。**工厂注册模块**用短名（如 `"aiagent_master"`）；**子Agent等自有模块**用家族名（如 `"aiagent_master:researchAgent"`）。详见下方 [目标解析规则](#631-目标解析规则) |
| `callType` | string | 否 |
| `callType` | string | 否 | 调用类型。`"agent_chat"`（默认）：发 `AGENT_CHAT` 消息；`"skill_execute"`：发 `SKILL_EXECUTE` 消息 |
| `input` | string/object | **是** | **agent_chat 模式**：用户消息字符串；**skill_execute 模式**：JSON 对象（Skill 的输入参数） |
| `expectedOutput` | string | 否 | 期望的回复文本，被 `exact_match` 评判器使用 |
| `expectedToolCalls` | string[] | 否 | 期望 Agent 调用的工具名称列表（仅供扩展使用） |
| `metadata` | object | 否 | 扩展元数据，自由键值对，可用于分类、标记难度等 |
| `judges` | array | 否 | 评判器配置列表。空或不填则默认通过 |

#### 6.3.1 目标解析规则

Evals 通过两级查找定位目标模块：

```
resolveTarget(name)
  1. moduleRegistry.get(familyName) → 找到实例 → putMsg(instance, msg)
  2. 未找到 → putMsg(name, msg) → 工厂按模块名查找/创建
```

| 目标类型 | targetAgent 写法 | 解析方式 |
|----------|-----------------|----------|
| Master Agent（工厂注册） | `"aiagent_master"` | 工厂 `putMsg(name, msg)` |
| 子 Agent（Master 的私有模块） | `"aiagent_master:researchAgent"` | 注册表按家族名获取实例 |
| AgentGroup（Master 的私有模块） | `"aiagent_master:myGroup"` | 注册表按家族名获取实例 |
| Skill（工厂注册的独立 Skill） | `"httpRequestSkill"` | 工厂 `putMsg(name, msg)` |
| Skill（Agent 的私有 Skill） | `"aiagent_master:planTask"` | 注册表按家族名获取实例 |

> **关键**：子 Agent 和 Agent 私有的 Skill 是 Master Agent 的**自有模块**（不在全局工厂注册），必须使用**家族名**（`"父:子"` 格式），Evals 会通过 `moduleRegistry` 获取实例后直接发送消息。



## 7. 三种调用模式

评测系统支持两种调用模式，通过 `callType` 字段指定。不同用例可以测试不同的目标模块。

### 7.1 agent_chat — 评测 Agent

**默认模式**，向目标 Agent 发送 `AGENT_CHAT` 消息，走完整的 LLM 对话流程。

#### 适用场景

- 评测 Master Agent 的端到端对话能力
- **独立评测子 Agent**：绕过 Master Agent，直接向子 Agent 发消息
- 测试 AgentGroup / AgentWorkflow 的整体表现

#### 消息协议

```
TLEvalsModule → putMsg(targetAgent, AGENT_CHAT)
    参数: sessionId, userMessage
    ← 返回: aiResponse, totalTokens, iterations, toolCalls, ...
```

#### 示例：评测 Master Agent（使用全局 targetAgent）

```json
{
  "id": "master-chat-001",
  "name": "Master Agent 对话测试",
  "callType": "agent_chat",
  "input": "你好，请介绍一下自己",
  "judges": [
    { "type": "constraint", "config": { "minResponseLength": 10, "maxIterations": 3 } }
  ]
}
```

#### 示例：独立评测子 Agent（用例级 targetAgent，使用家族名）

```json
{
  "id": "sub-agent-test-001",
  "name": "独立测试研究Agent",
  "targetAgent": "aiagent_master:researchAgent",
  "callType": "agent_chat",
  "input": "请搜索最新的AI新闻",
  "judges": [
    { "type": "constraint", "config": { "minResponseLength": 30, "maxIterations": 5 } }
  ]
}
```

> **关键点**：`targetAgent` 写的是子 Agent 的**家族名**（`"父Agent名:子Agent名"`），不是短名。Evals 通过 `moduleRegistry` 获取实例后直接发消息。

### 7.2 skill_execute — 评测 Skill

直接向 Skill 模块发送 `SKILL_EXECUTE` 消息，**绕过 Agent**，测试 Skill 的输入→输出正确性。

#### 适用场景

- 单元测试某个 Skill 的核心逻辑
- 验证 Skill 对非法参数的处理
- 测试 Skill 的性能（延迟、并发）

#### 消息协议

```
TLEvalsModule → putMsg(targetAgent, SKILL_EXECUTE)
    参数: skillInput (Map<String, Object>)
    ← 返回: skillOutput, result (boolean)
```

#### 与 agent_chat 的区别

| 特性 | agent_chat | skill_execute |
|------|-----------|---------------|
| 消息动作 | `AGENT_CHAT` | `SKILL_EXECUTE` |
| 输入格式 | 字符串（用户消息） | JSON 对象（Skill 参数） |
| LLM 参与 | ✅ 走完整 LLM 对话 | ❌ 不经过 LLM |
| Token 统计 | ✅ 记录 | ❌ 不记录（= 0） |
| Tool calls | ✅ 记录 | ❌ 不记录（= []） |
| 迭代次数 | ✅ 记录 | ❌ 不记录（= 0） |
| Session | ✅ 创建并清理 | ❌ 无 session |

#### 示例：测试 HTTP 请求 Skill

```json
{
  "id": "test-http-skill-001",
  "name": "测试httpRequestSkill-GET请求",
  "targetAgent": "httpRequestSkill",
  "callType": "skill_execute",
  "input": {
    "url": "https://api.example.com/data",
    "method": "GET"
  },
  "expectedOutput": "{\"status\": 200}",
  "judges": [
    {
      "type": "exact_match",
      "config": { "contains": true }
    },
    {
      "type": "constraint",
      "config": { "maxLatencyMs": 5000 }
    }
  ]
}
```

#### 示例：测试文件操作 Skill

```json
{
  "id": "test-file-skill-001",
  "name": "测试fileOperationSkill-读文件",
  "targetAgent": "fileOperationSkill",
  "callType": "skill_execute",
  "input": {
    "action": "read",
    "path": "/tmp/test.txt"
  },
  "judges": [
    {
      "type": "constraint",
      "config": {
        "minResponseLength": 1,
        "mustNotContain": ["错误", "异常", "不存在"]
      }
    }
  ]
}
```

#### 示例：完整套件文件（混合模式）

```json
[
  {
    "id": "agent-e2e-001",
    "name": "端到端对话",
    "targetAgent": "aiagent_master",
    "callType": "agent_chat",
    "input": "你好",
    "judges": [
      { "type": "constraint", "config": { "minResponseLength": 5, "maxIterations": 2 } }
    ]
  },
  {
    "id": "skill-unit-001",
    "name": "Skill单元测试",
    "targetAgent": "httpRequestSkill",
    "callType": "skill_execute",
    "input": { "url": "https://httpbin.org/get", "method": "GET" },
    "judges": [
      { "type": "constraint", "config": { "maxLatencyMs": 5000 } }
    ]
  },
  {
    "id": "sub-agent-001",
    "name": "子Agent独立测试",
    "targetAgent": "aiagent_master:codeAgent",
    "callType": "agent_chat",
    "input": "写一个Python函数计算斐波那契数列",
    "judges": [
      { "type": "constraint", "config": { "minResponseLength": 50, "maxIterations": 10 } }
    ]
  }
]
```

---

## 8. 三种评判器详解

### 8.1 精确匹配评判器 (exact_match)

**类型标识**：`"exact_match"`

**用途**：将实际回复与 `expectedOutput` 字段中的期望文本进行比较。

**实现类**：`TLExactMatchJudge`

#### 配置参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `ignoreCase` | boolean | `true` | 忽略大小写 |
| `normalizeWhitespace` | boolean | `true` | 将所有连续空白字符归一化为单个空格 |
| `trim` | boolean | `true` | 去除首尾空白 |
| `contains` | boolean | `false` | `true` = 子串包含匹配；`false` = 完全相等匹配 |
| `normalizeMode` | string | `"flexible"` | 归一化模式。`"flexible"` 将全角标点转为半角 |

#### 评分规则

- 通过 → `score = 1.0`
- 失败 → `score = 0.0`

#### 使用示例

```json
{
  "type": "exact_match",
  "config": {
    "ignoreCase": true,
    "trim": true,
    "contains": true
  }
}
```

### 8.2 约束检查评判器 (constraint)

**类型标识**：`"constraint"`

**用途**：基于纯规则对执行过程进行多维度约束检查，不需要 LLM 参与。

**实现类**：`TLConstraintJudge`

#### 配置参数

##### 性能约束

| 参数 | 类型 | 说明 |
|------|------|------|
| `maxTokens` | int | 最后一次请求的 Token 用量上限 |
| `maxTotalTokens` | int | 整个会话的累计 Token 用量上限 |
| `maxIterations` | int | Agent 最大循环迭代次数。`0` = 不允许迭代 |
| `maxLatencyMs` | int | 最大响应延迟（毫秒） |

##### 工具调用约束

| 参数 | 类型 | 说明 |
|------|------|------|
| `mustCallTools` | string[] 或 string | 必须调用的工具名列表 |
| `mustNotCallTools` | string[] 或 string | 禁止调用的工具名列表 |

##### 内容约束

| 参数 | 类型 | 说明 |
|------|------|------|
| `mustContain` | string[] 或 string | 回复中必须包含的关键词/子串 |
| `mustNotContain` | string[] 或 string | 回复中禁止包含的关键词/子串 |
| `minResponseLength` | int | 回复最小字符数 |
| `maxResponseLength` | int | 回复最大字符数 |

> **注意**：`maxTokens`、`maxIterations`、`mustCallTools` 等参数仅在 `agent_chat` 模式下有意义。`skill_execute` 模式下这些参数不会触发检查。

#### 评分规则

- `score = 通过的检查项数 / 总检查项数`
- 只有所有检查项全部通过，`passed` 才为 `true`

#### 使用示例

```json
{
  "type": "constraint",
  "config": {
    "minResponseLength": 10,
    "maxResponseLength": 2000,
    "maxIterations": 3,
    "maxTokens": 3000,
    "mustCallTools": ["http_request"],
    "mustNotCallTools": ["code_execution"],
    "mustContain": ["结果"],
    "mustNotContain": ["错误", "无法"]
  }
}
```

### 8.3 LLM 裁判评判器 (llm_judge)

**类型标识**：`"llm_judge"`

**用途**：使用另一个 LLM 来评估回复的**质量**。适合评估开放性问题的回答质量、语气、准确性等主观维度。

**实现类**：`TLLlmJudge`

#### 配置参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `prompt` | string | **必填** | 评判标准描述 |
| `passThreshold` | double | `0.7` | 及格分数阈值（0.0 ~ 1.0） |
| `model` | string | 无 | 覆盖裁判用的 LLM 模型名 |

#### 工作原理

1. 构建评测 prompt：用户输入 + Agent 回复 + 评判标准
2. 通过 `judgeProvider` 发送 LLM 请求（`temperature=0.1`，`maxTokens=512`）
3. 要求 LLM 返回 JSON：`{"pass": true/false, "score": 0.0~1.0, "reason": "理由"}`
4. 结合 `passThreshold` 判定

#### 评分规则

- `passed = (LLM pass=true) AND (score >= passThreshold)`

#### Prompt 编写指南

1. **明确评估维度**：准确性、完整性、流畅性、安全性
2. **给出评分锚点**："0.9-1.0 = 完美，0.7-0.8 = 基本正确，<0.5 = 错误"
3. **描述 pass/fail 边界**：什么情况算通过
4. **考虑陷阱**：Agent 逃避问题、模糊回答等

#### 使用示例

```json
{
  "type": "llm_judge",
  "config": {
    "prompt": "评估AI助手的回复是否准确、有帮助、语言流畅。\n评分标准：\n- 1.0: 完美准确，表达清晰\n- 0.8: 基本正确，有小瑕疵\n- 0.5: 部分正确但有错误\n- 0.0: 完全错误或答非所问",
    "passThreshold": 0.7
  }
}
```

---

## 9. 评测报告

### 9.1 报告文件

每次评测运行后，报告保存为 `eval_report_<yyyyMMdd_HHmmss>.json`，位于 `reportOutputDir` 目录下。

### 9.2 报告 JSON 结构

```json
{
  "timestamp": "2026-07-29T09:59:18",
  "summary": {
    "total": 5,
    "passed": 4,
    "failed": 1,
    "passRate": 0.8,
    "avgTokens": 2226.0,
    "avgIterations": 1.0,
    "avgLatencyMs": 3705.2
  },
  "results": [
    {
      "caseId": "math-simple-001",
      "caseName": "简单数学计算",
      "targetAgent": "aiagent_master",
      "callType": "agent_chat",
      "response": "4",
      "cancelled": false,
      "iterations": 1,
      "promptTokens": 2070,
      "completionTokens": 46,
      "totalTokens": 2116,
      "promptTokensTotal": 2070,
      "completionTokensTotal": 46,
      "totalTokensTotal": 2116,
      "toolCalls": [],
      "latencyMs": 1846,
      "error": null,
      "verdicts": [
        {
          "judgeType": "exact_match",
          "passed": true,
          "score": 1.0,
          "reason": "输出包含期望文本 (包含匹配)"
        }
      ],
      "passed": true
    }
  ]
}
```

### 9.3 字段说明

#### Summary

| 字段 | 说明 |
|------|------|
| `total` | 用例总数 |
| `passed` | 通过数 |
| `failed` | 失败数 |
| `passRate` | 通过率（0.0 ~ 1.0） |
| `avgTokens` | 平均 Token 用量（仅 agent_chat 用例参与计算） |
| `avgIterations` | 平均迭代次数 |
| `avgLatencyMs` | 平均延迟（毫秒） |

#### Result

| 字段 | 说明 |
|------|------|
| `caseId` / `caseName` | 用例 ID 和名称 |
| `targetAgent` | 实际调用的目标模块名 |
| `callType` | 实际使用的调用类型 |
| `response` | 回复文本 |
| `iterations` / `totalTokens` / `toolCalls` | Agent 执行指标（skill_execute 模式为 0） |
| `latencyMs` | 响应延迟 |
| `error` | 错误信息，null 表示无错误 |
| `verdicts` | 各评判器裁决 |
| `passed` | 该用例是否通过 |

---

## 10. 扩展开发

### 10.1 添加自定义评判器

**步骤 1：实现 `TLEvalJudge` 接口**

```java
package cn.tianlong.tlobject.aiagent.evals;

public class TLMyCustomJudge implements TLEvalJudge {

    @Override
    public String getType() { return "my_custom"; }

    @Override
    public TLEvalVerdict judge(TLEvalCase evalCase, TLEvalRunResult runResult, JudgeContext context) {
        JudgeConfig config = findMyConfig(evalCase);
        String myParam = config.getString("myParam");

        if (/* 通过 */) {
            return TLEvalVerdict.pass("my_custom", 1.0, "判断理由");
        } else {
            return TLEvalVerdict.fail("my_custom", 0.0, "失败原因");
        }
    }

    private JudgeConfig findMyConfig(TLEvalCase evalCase) {
        if (evalCase.judges != null) {
            for (JudgeConfig jc : evalCase.judges) {
                if ("my_custom".equals(jc.type)) return jc;
            }
        }
        return new JudgeConfig("my_custom", null);
    }
}
```

**步骤 2：在 `TLEvalsModule.init()` 中注册**

```java
judges.put("my_custom", new TLMyCustomJudge());
```

### 10.2 添加自定义调用模式

如需支持新的调用类型（如 `mcp_call`），在 `TLEvalsModule.runSingleCase()` 中添加新的分支：

```java
if ("mcp_call".equals(callType)) {
    return runMcpCase(evalCase, targetModule, callType);
}
```

然后实现对应的 `runMcpCase()` 方法。

---

## 11. 最佳实践

### 11.1 用例设计建议

1. **从简单开始**：先用 `constraint` 写基础用例，再逐步加 `exact_match` 和 `llm_judge`
2. **一个用例测一件事**：用多评判器组合比一个庞杂的 llm_judge prompt 更好调试
3. **先不设 Token 限制**：跑通后根据实际用量设置 2-3 倍的上限
4. **用 metadata 分类**：通过 `category` 和 `difficulty` 区分功能域和难度
5. **套件文件管理**：相关用例放一个 JSON 数组文件中
6. **用例 ID 命名规范**：`{功能域}-{编号}`，如 `weather-001`

### 11.2 评判器组合策略

| 场景 | 推荐组合 |
|------|----------|
| 简单 QA（固定答案） | `exact_match` + `constraint` |
| 开放性问答 | `constraint` + `llm_judge` |
| 工具调用验证 | `constraint`（`mustCallTools` + `mustNotCallTools`） |
| 性能回归测试 | `constraint`（`maxTokens` + `maxLatencyMs` + `maxIterations`） |
| **Skill 单元测试** | `exact_match` + `constraint`（`maxLatencyMs`） |
| **子 Agent 独立测试** | `constraint` + `llm_judge` |
| 安全审计 | `constraint`（`mustNotContain`）+ `llm_judge` |

### 11.3 评测策略推荐

| 评测层级 | callType | targetAgent | 测什么 |
|----------|----------|-------------|--------|
| E2E 端到端 | `agent_chat` | `aiagent_master` | Master Agent 完整对话链路 |
| 子 Agent 独立 | `agent_chat` | 子 Agent 名 | 单个子 Agent 的对话能力 |
| Skill 单元 | `skill_execute` | Skill 模块名 | Skill 输入→输出正确性 |
| Group 集成 | `agent_chat` | Group 模块名 | AgentGroup 编排逻辑 |

---

## 12. 常见问题

### Q1：`/eval suite` 提示 "目录中没有用例"

确保 `evalCaseDir` 路径正确且目录下存在 `.json` 文件。路径支持文件系统和 classpath 双查找，自动回退。

### Q2：LLM Judge 调用失败

常见原因：`judgeProvider` 配置不正确、Provider 未初始化、API Key 未配置。查看报告中的 `error` 字段获取具体原因。

### Q3：Token 用量超标

`constraint` 评判器中的 `maxTokens` 限制触发。首次写用例建议先不限制，跑通后根据实际用量调整。

### Q4：`exact_match` 内容一致却判定失败

检查 `contains` 是否设为 `true`、全角/半角标点差异、多余空白字符。默认的 `normalizeMode: "flexible"` 可处理全角标点。

### Q5：如何只运行部分用例？

- `/eval run <id>` — 单用例运行
- 将需要的用例放在独立 JSON 数组文件中，修改 `evalCaseDir` 指向该目录

### Q6：评测会修改会话数据吗？

不会。`agent_chat` 模式使用独立 session ID（`eval_<caseId>_<timestamp>`）并在 finally 块中自动清理。`skill_execute` 模式不创建 session。

### Q7：如何评测子 Agent？

子 Agent 是 Master Agent 的私有模块，不在全局工厂注册。需要使用**家族名**作为 `targetAgent`，Evals 会通过 `moduleRegistry` 获取实例：

```json
{
  "id": "test-sub-001",
  "name": "测试子Agent",
  "targetAgent": "aiagent_master:researchAgent",
  "callType": "agent_chat",
  "input": "搜索最新AI新闻",
  "judges": [...]
}
```

> 家族名格式：`"父Agent注册名:子Agent名"`。可以用 `/agents` 命令在控制台查看已注册的 Agent 及其家族名。

### Q8：如何评测 Skill？

使用 `callType: "skill_execute"` 模式，`input` 写 JSON 对象作为 Skill 参数：

```json
{
  "id": "test-skill-001",
  "name": "测试Skill",
  "targetAgent": "httpRequestSkill",
  "callType": "skill_execute",
  "input": { "url": "https://example.com", "method": "GET" },
  "judges": [...]
}
```

### Q9：skill_execute 模式为什么没有 Token 统计？

`skill_execute` 直接调用 Skill 模块的 `execute()` 方法，不经过 LLM，因此没有 Token 消耗。报告中的 Token 字段均为 0。

### Q10：级联评测和 suite 评测有什么区别？

- **`/eval cascade`**：自动发现目标 Agent 的所有子模块（子 Agent、Skill），为每个自动生成基础冒烟用例。**零配置、一键覆盖全链路**，适合日常快速验证。
- **`/eval suite`**：运行手写的 JSON 用例文件。用例可精细控制输入、期望输出、评判标准。适合**回归测试和质量门禁**。

两者可互补：日常用 cascade 快速冒烟，发版前用 suite 跑完整用例。

### Q11：targetAgent 不填时用哪个？

使用 `evals_config.xml` 中全局配置的 `targetAgent`。如果 XML 也未配置，默认为 `"aiagent"`。

---

## 附录 A：已有示例用例

| 文件 | 用例 ID | 说明 |
|------|---------|------|
| `basic_chat.json` | `basic-chat-001` | 基础对话 + constraint 约束 |
| `constraint_suite.json` | `suite-constraint-001` ~ `002` | 约束检查套件 |
| `llm_judge_demo.json` | `llm-judge-demo-001` | LLM Judge 示例 |
| `math_test.json` | `math-simple-001` | 数学计算：exact_match + constraint 组合 |

## 附录 B：完整用例模板

以下模板包含所有字段和三种评判器，可直接复制修改：

```json
{
  "id": "template-001",
  "name": "【模板】完整用例示例",
  "targetAgent": "aiagent_master",
  "callType": "agent_chat",
  "input": "请回答：1+1等于几？只输出数字。",
  "expectedOutput": "2",
  "expectedToolCalls": [],
  "metadata": {
    "category": "math",
    "difficulty": "easy",
    "tags": ["regression", "smoke"],
    "author": "your-name",
    "description": "验证基础数学计算能力"
  },
  "judges": [
    {
      "type": "exact_match",
      "config": {
        "ignoreCase": true,
        "normalizeWhitespace": true,
        "trim": true,
        "contains": true,
        "normalizeMode": "flexible"
      }
    },
    {
      "type": "constraint",
      "config": {
        "minResponseLength": 1,
        "maxResponseLength": 10,
        "maxIterations": 2,
        "maxTokens": 500,
        "maxTotalTokens": 1000,
        "maxLatencyMs": 10000,
        "mustCallTools": [],
        "mustNotCallTools": ["http_request", "code_execution"],
        "mustContain": ["2"],
        "mustNotContain": ["错误", "无法", "不知道"]
      }
    },
    {
      "type": "llm_judge",
      "config": {
        "prompt": "评估回复是否准确回答了数学问题。如果答案正确且格式符合要求（只输出数字），给满分。",
        "passThreshold": 0.7,
        "model": "gpt-4o"
      }
    }
  ]
}
```

### Skill 评测模板

```json
{
  "id": "skill-template-001",
  "name": "【模板】Skill评测示例",
  "targetAgent": "httpRequestSkill",
  "callType": "skill_execute",
  "input": {
    "url": "https://httpbin.org/get",
    "method": "GET"
  },
  "expectedOutput": "200",
  "metadata": {
    "category": "skill-test",
    "difficulty": "easy"
  },
  "judges": [
    {
      "type": "exact_match",
      "config": { "contains": true }
    },
    {
      "type": "constraint",
      "config": {
        "maxLatencyMs": 5000,
        "minResponseLength": 1,
        "mustNotContain": ["错误", "异常"]
      }
    }
  ]
}
```

---

> **文档维护**：本文档随 evals 模块一起维护。如有新增功能，请同步更新。
