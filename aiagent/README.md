# TLAgent — AI Agent Framework on TLObject

**English** | [中文](README.zh.md)

An AI Agent sub-framework built on the **TLObject Unified Object Message Programming model**. Everything is message-driven: all modules communicate through `TLMsg`, and their lifecycles are managed by the unified `TLObjectFactory`.

---

## Table of Contents

0. [Highlights: Why This Agent Framework](#highlights-why-this-agent-framework)
   - [Structural Advantages of the Message Architecture](#1-structural-advantages-of-the-message-architecture)
   - [Conversational Intelligence](#2-conversational-intelligence)
   - [Tool (Skill) Capabilities](#3-tool-skill-capabilities)
   - [Three-Layer Memory System](#4-three-layer-memory-system)
   - [Multi-Agent Orchestration](#5-multi-agent-orchestration)
   - [Engineering and Security](#6-engineering-and-security)
1. [Architecture Overview](#architecture-overview)
2. [Module Layout](#module-layout)
3. [Core Classes](#core-classes)
   - [TLAiAgentParamString — Constants Interface](#tlaiagentparamstring-constants-interface)
   - [TLAiAgent — Master Orchestrator ★](#tlaiagent-master-orchestrator)
   - [TLAiContext — Context Management](#tlaicontext-context-management)
   - [TLLlmProvider — Abstract LLM Provider](#tlllmprovider-abstract-llm-provider)
   - [TLOpenAiProvider — OpenAI-Compatible Provider](#tlopenaiprovider-openai-compatible-provider)
   - [TLClaudeProvider — Claude API Provider](#tlclaudeprovider-claude-api-provider)
   - [TLBaseSkill — Abstract Skill Base Class](#tlbaseskill-abstract-skill-base-class)
   - [Built-in Skills](#built-in-skills)
   - [TLBaseMemory — Abstract Memory Base Class](#tlbasememory-abstract-memory-base-class)
   - [Memory Implementations](#memory-implementations)
4. [Data POJOs](#data-pojos)
   - [TLConversationHistory — Conversation History Entry](#tlconversationhistory-conversation-history-entry)
   - [TLToolCall — Tool Call](#tltoolcall-tool-call)
   - [TLFunctionDefinition — Function Definition](#tlfunctiondefinition-function-definition)
   - [TLMemoryEntry — Memory Entry](#tlmemoryentry-memory-entry)
5. [Message Flow](#message-flow)
   - [Full Chat Flow](#full-chat-flow)
   - [Streaming Chat Flow](#streaming-chat-flow)
   - [Skill Registration Flow](#skill-registration-flow)
   - [Provider Switching Flow](#provider-switching-flow)
6. [XML Configuration](#xml-configuration)
   - [aiagent_config.xml](#aiagent_configxml)
   - [Registration in moduleFactory_config.xml](#registration-in-modulefactory_configxml)
7. [Usage Examples](#usage-examples)
   - [Example 1: Basic Chat Call](#example-1-basic-chat-call)
   - [Example 2: Multi-turn Conversation (with context)](#example-2-multi-turn-conversation-with-context)
   - [Example 3: Automatically Triggered Tool Call](#example-3-automatically-triggered-tool-call)
   - [Example 4: Streaming Chat](#example-4-streaming-chat)
   - [Example 5: Registering a Custom Skill at Runtime](#example-5-registering-a-custom-skill-at-runtime)
   - [Example 6: Managing Context and Memory](#example-6-managing-context-and-memory)
   - [Example 7: Switching Provider at Runtime](#example-7-switching-provider-at-runtime)
   - [Example 8: Async Call](#example-8-async-call)
8. [Extension Development](#extension-development)
   - [Adding a Custom LLM Provider](#adding-a-custom-llm-provider)
   - [Adding a Custom Skill](#adding-a-custom-skill)
   - [Adding a Custom Memory Store](#adding-a-custom-memory-store)
9. [New Feature Modules](#new-feature-modules)
   - [MCP Marketplace and Tool Integration](#mcp-marketplace-and-tool-integration)
   - [Evals Evaluation System](#evals-evaluation-system)
   - [Unit Tests /test](#unit-tests-test)
   - [HITL Human Approval](#hitl-human-approval)
   - [Session Management and Checkpoint Resume](#session-management-and-checkpoint-resume)
   - [Web Interaction Window (aiagent/webui)](#web-interaction-window-aiagentwebui)
   - [Workflow Orchestration and Field-level Merge (TLAgentWorkflow)](#workflow-orchestration-and-field-level-merge-tlagentworkflow)

---

## Highlights: Why This Agent Framework


### 1. Structural Advantages of the Message Architecture

- **Full-chain observability, instrumentable at will**: LLM calls, tool execution, and memory reads/writes all go through messages, so `beforeMsgTable` / `afterMsgTable` can inject cross-cutting logic (memory recall, approval, auditing, statistics) at any stage; `roundId` runs through the whole chain, and `/trace` can perform pinpoint replay analysis of every round
- **Zero special-case components**: Provider / Skill / Memory / sub-agent / Agent group / workflow — all are `TLBaseModule`, with lifecycles, hot-reload, and configuration identical to every other framework module: learn one, and you know them all
- **Declarative assembly**: System Prompt, tool list, memory strategy, and sub-agent topology are all written in XML; switching models (DeepSeek ⇄ OpenAI ⇄ Claude) or swapping memory stores (file ⇄ database) requires only config changes, not code changes


### 2. Conversational Intelligence

- **One codebase for streaming and non-streaming**: `chat()` is the unified entry point, with SSE streaming output (DeepSeek / OpenAI / Claude compatible Providers are plug-and-play)
- **Model routing + intent cache**: simple Q&A automatically goes to a lightweight model to save tokens, while complex tasks automatically escalate to a stronger model; high-frequency intents are learned and then answered instantly from cache
- **Chain-of-thought (ReAct), four modes**: `off / prompt / native / auto` — natively parsing DeepSeek `reasoning_content` and Claude Extended Thinking
- **Automatic context management**: multi-round trimming and automatic session history saving keep long conversations from getting out of hand


### 3. Tool (Skill) Capabilities

- **Four categories of tools, unified**: built-in Skills (HTTP / file / code execution / browser / desktop GUI) + custom Skills + MCP tool marketplace (install with a single command) + msgTool message tool (lets the LLM directly invoke any message route in the framework)
- **Parallel tool execution**: when the LLM returns multiple tool_calls at once, they execute concurrently and the results are put back in order
- **Hot-plug**: Skills / sub-agents can be registered, unregistered, and hot-reloaded at runtime (`/install` `/reload`)
- **Tools are agents**: inside a workflow, sub-agents and Agent groups stand on equal footing with ordinary tools, with zero special-casing in the master


### 4. Three-Layer Memory System

| Layer | Carrier | Purpose |
|----|------|------|
| Session context | `TLAiContext` | Continuity of the current round of dialogue, automatically trimmed |
| Short-term memory | In-process TTL cache | Temporary state for external Skills |
| Long-term memory | File JSONL / database | Cross-session knowledge fragments, layered summarization (fragment → summary) + optional vector recall |

Memory injection goes through **AOP message hooks**: automatic recall before chat → injected into the System context → automatically consolidated after chat — zero intrusion into business code, and it naturally follows session/user isolation.


### 5. Multi-Agent Orchestration

- **Sub-agent delegation**: description-based routing dispatches automatically, with no type special-casing whatsoever in the master agent
- **Agent group**: serial chain / parallel merge / supervisor review-and-summarize
- **Workflow DAG + expression DSL**: `&&` for parallel, `\|\|` for fallback, `->` for sequence, `if` for value branching — write orchestration as a one-line expression
- **Checkpoint resume / direct output**: stop and resume at any point (`/resume`), and sub-agent results can bypass LLM re-processing and be returned directly (directOutput)


### 6. Engineering and Security

- **HITL approval gate**: dangerous tools (file deletion, code execution) are approved by function name, with session-level rejection memory (the same operation no longer prompts repeatedly); the approval dialog is fully decoupled from the UI (pushed over the event bus)
- **Deterministic self-testing**: 12 test scenarios driven by a Mock Provider, with no network and no Key, and reproducible results — change code without fear of regressions
- **Evals evaluation system**: JSON cases + three types of Judge (exact / LLM / constraint), supporting batch regression
- **Session system**: multi-user data isolation, checkpoint resume, token statistics, `/trace` full-chain tracing
- **Web UI (webui)**: browser chat + screenshot visualization + session management, same-origin with the framework and zero extra dependencies

> From "calling an LLM once" to "enterprise-grade multi-agent orchestration", it is one gradual path: get it running with Mock first → go live with a real Key → add tools as needed → then add orchestration and approval. No step overturns the previous one.

---


## Architecture Overview

```
+------------------------------------------------------------------+
|  TLAiAgent (Master Orchestrator)                                 |
|  chat() loop:                                                    |
|    user input -> gather context -> collect Skill defs -> send LLM|
|    -> parse response:                                            |
|        has tool_calls -> run Skill -> results back to LLM (loop) |
|        plain text     -> return to user                          |
|    -> beforeMsgTable injects memory -> afterMsgTable persists it |
          +               +               +               +         
          |               |               |               |         
          v               v               v               v         
  +-----------------+  +-----------------+  +-----------------+  +-----------------+
  |   TLAiContext   |  |  TLLlmProvider  |  |   TLBaseSkill   |  |   TLBaseMemory  |
  |(session history)|  | (abstract base) |  | (abstract base) |  | (abstract base) |
  +-----------------+  +-----------------+  +-----------------+  +-----------------+
         |                 |                 |                 |
         v                 v                 v                 v
  +--------------+  +--------------+  +--------------+  +--------------+
  |    OpenAI    |  |    Claude    |  |   HTTP req   |  |  short-term  |
  |   Provider   |  |   Provider   |  |   file ops   |  |  long-term   |
  | (OkHttp+SSE) |  | (OkHttp+SSE) |  |  code exec   |  |(file-persist)|
  +--------------+  +--------------+  +--------------+  +--------------+
```


### Design Principles

- **Everything is a message**: modules communicate only through `TLMsg` — no direct method calls
- **Everything is a module**: every component extends `TLBaseModule` and gets the full lifecycle management
- **XML-driven configuration**: module definitions, routing tables and interceptors are all declarative
- **Reflective action dispatch**: `checkMsgAction()` → `invokeAction()` maps a message action to a Java method
- **AOP interception**: `beforeMsgTable` / `afterMsgTable` implement cross-cutting concerns (e.g. memory injection)
- **Provider pattern**: LLM providers are pluggable — swap vendors by configuration

---


## Module Layout

```
aiagent/
├── pom.xml                                     # aggregator POM
├── common/                                      # tlobject-aiagent-common
│   └── src/main/java/cn/tianlong/tlobject/aiagent/
│       ├── TLAiAgentParamString.java            # constants interface
│       ├── TLAiAgent.java                       # master orchestrator ★
│       ├── TLAiContext.java                     # context management
│       ├── TLLlmProvider.java                   # abstract LLM provider
│       ├── TLBaseSkill.java                     # abstract Skill base class
│       ├── TLBaseMemory.java                    # abstract memory base class
│       ├── TLConversationHistory.java           # conversation history POJO
│       ├── TLToolCall.java                      # tool call POJO
│       ├── TLFunctionDefinition.java            # function definition POJO
│       └── TLMemoryEntry.java                   # memory entry POJO
├── provider-openai/                             # tlobject-aiagent-provider-openai
│   └── src/.../provider/openai/
│       └── TLOpenAiProvider.java                # OpenAI-compatible provider
├── provider-claude/                             # tlobject-aiagent-provider-claude
│   └── src/.../provider/claude/
│       └── TLClaudeProvider.java                # Claude provider
├── skill-builtin/                               # tlobject-aiagent-skill-builtin
│   └── src/.../skill/builtin/
│       ├── TLHttpRequestSkill.java              # HTTP request
│       ├── TLFileOperationSkill.java            # file operations
│       └── TLCodeExecutionSkill.java            # code execution
└── memory-store/                                # tlobject-aiagent-memory-store
    └── src/.../aiagent/memory/
        ├── TLShortTermMemoryModule.java         # short-term memory
        └── TLLongTermMemoryModule.java          # long-term memory
```


### Maven Dependencies

| Module | artifactId | Depends on |
|------|-----------|------|
| common | `tlobject-aiagent-common` | `tlobject-core`, `gson` 2.10.1, `okhttp` 4.12.0 |
| provider-openai | `tlobject-aiagent-provider-openai` | `tlobject-aiagent-common` |
| provider-claude | `tlobject-aiagent-provider-claude` | `tlobject-aiagent-common` |
| skill-builtin | `tlobject-aiagent-skill-builtin` | `tlobject-aiagent-common` |
| memory-store | `tlobject-aiagent-memory-store` | `tlobject-aiagent-common` |

---

## Core Classes


### TLAiAgentParamString — Constants Interface

**Inherits**: `TLParamString`
**Package**: `cn.tianlong.tlobject.aiagent`

Defines the string constants for all message action names, parameter keys, and module names in the AI Agent framework. Every AI module automatically inherits both the core framework constants and the AI-specific constants through `implements TLAiAgentParamString`, avoiding magic strings.

#### Module Name Constants

| Constant | Value | Description |
|------|-----|------|
| `M_AIAGENT` | `"aiagent"` | Agent master module name |
| `M_AICONTEXT` | `"aiContext"` | Context module name |
| `M_LLMPROVIDER_OPENAI` | `"openAiProvider"` | OpenAI Provider name |
| `M_LLMPROVIDER_CLAUDE` | `"claudeProvider"` | Claude Provider name |
| `M_SHORTTERMMEMORY` | `"shortTermMemory"` | Short-term memory module name |
| `M_LONGTERMMEMORY` | `"longTermMemory"` | Long-term memory module name |

#### Agent Action Constants

| Constant | Value | Description |
|------|-----|------|
| `AGENT_CHAT` | `"chat"` | Full chat loop (including tool-call iterations) |
| `AGENT_CHATSTREAM` | `"chatStream"` | Streaming chat |
| `AGENT_REGISTERSKILL` | `"registerSkill"` | Register a Skill |
| `AGENT_UNREGISTERSKILL` | `"unregisterSkill"` | Unregister a Skill |
| `AGENT_LISTSKILLS` | `"listSkills"` | List all Skills |
| `AGENT_GETCONTEXT` | `"getAgentContext"` | Get the session context |
| `AGENT_CLEARCONTEXT` | `"clearAgentContext"` | Clear the session context |
| `AGENT_SAVEMEMORY` | `"saveAgentMemory"` | Save memory |
| `AGENT_RECALLMEMORY` | `"recallAgentMemory"` | Recall memory |
| `AGENT_SETSYSTEMMSG` | `"setSystemMsg"` | Set the system prompt |
| `AGENT_SETPROVIDER` | `"setLlmProvider"` | Switch the LLM Provider |

#### LLM Provider Action Constants

| Constant | Value | Description |
|------|-----|------|
| `LLM_COMPLETION` | `"completion"` | Non-streaming completion request |
| `LLM_COMPLETIONSTREAM` | `"completionStream"` | Streaming completion request |
| `LLM_LISTMODELS` | `"listModels"` | List available models |
| `LLM_CANCEL` | `"cancelLlm"` | Cancel the in-flight request |

#### Context Action Constants

| Constant | Value | Description |
|------|-----|------|
| `CONTEXT_ADDMESSAGE` | `"addMessage"` | Append a message to the history |
| `CONTEXT_GETMESSAGES` | `"getMessages"` | Get the message history |
| `CONTEXT_CLEAR` | `"clearContext"` | Clear the session |
| `CONTEXT_GETTURNCOUNT` | `"getTurnCount"` | Get the round count |
| `CONTEXT_SETSYSTEM` | `"setSystem"` | Set the system message |

#### Skill Action Constants

| Constant | Value | Description |
|------|-----|------|
| `SKILL_GETINFO` | `"getSkillInfo"` | Get Skill metadata |
| `SKILL_EXECUTE` | `"skillExecute"` | Execute a Skill |
| `SKILL_VALIDATE` | `"skillValidate"` | Validate input parameters |

#### Memory Action Constants

| Constant | Value | Description |
|------|-----|------|
| `MEMORY_STORE` | `"store"` | Store memory |
| `MEMORY_RETRIEVE` | `"retrieve"` | Retrieve by key |
| `MEMORY_SEARCH` | `"search"` | Search memory by text |
| `MEMORY_DELETE` | `"delete"` | Delete by key |
| `MEMORY_CLEARALL` | `"clearAll"` | Clear all memory |

#### Parameter Key Constants (Main)

| Constant | Key Name | Type | Description |
|------|------|------|------|
| `AI_P_SESSIONID` | `"sessionId"` | String | Session ID, used throughout the entire conversation |
| `AI_P_USERMESSAGE` | `"userMessage"` | String | User input text |
| `AI_P_SYSTEMMESSAGE` | `"systemMessage"` | String | System prompt |
| `AI_P_RESPONSE` | `"aiResponse"` | String | Text returned by the LLM |
| `AI_P_MESSAGEHISTORY` | `"messageHistory"` | List | Conversation history list |
| `AI_P_TOOLCALLS` | `"toolCalls"` | List | List of tool calls returned by the LLM |
| `AI_P_TOOLRESULTS` | `"toolResults"` | List | List of tool execution results |
| `AI_P_FUNCTIONDEFS` | `"functionDefinitions"` | List | Function definitions passed to the LLM |
| `AI_P_MODEL` | `"model"` | String | Model name |
| `AI_P_TEMPERATURE` | `"temperature"` | double | Sampling temperature |
| `AI_P_MAXTOKENS` | `"maxTokens"` | int | Maximum number of output tokens |
| `AI_P_SKILLNAME` | `"skillName"` | String | Name identifier of the Skill |
| `AI_P_SKILLINPUT` | `"skillInput"` | Map | Input parameters for Skill execution |
| `AI_P_SKILLOUTPUT` | `"skillOutput"` | String | Output result of the Skill execution |
| `AI_P_MEMORYKEY` | `"memoryKey"` | String | Memory key |
| `AI_P_MEMORYVALUE` | `"memoryValue"` | Object | Memory value |
| `AI_P_MEMORYQUERY` | `"memoryQuery"` | String | Memory search query |
| `AI_P_CHUNK` | `"chunk"` | String | Single text chunk of a streaming response |
| `AI_P_STREAMDONE` | `"streamDone"` | boolean | Streaming response completion flag |
| `AI_P_STREAMERROR` | `"streamError"` | String | Streaming error message |

---


### TLAiAgent — Master Orchestrator ★

**Extends**: `TLBaseModule`
**Implements**: `TLAiAgentParamString`
**Module name**: `"aiagent"`
**Analogy**: `TLDatabase` (entry point of the database module)

The core module of the Agent framework. It receives user input and orchestrates the message interactions among the LLM, Skill, Context, and Memory, implementing the complete closed loop of **user input → LLM analysis → Skill invocation → result return**.

#### Key Fields

| Field | Type | Default | Description |
|------|------|--------|------|
| `defaultLlmProvider` | String | configured value | Default LLM Provider module name |
| `llmProvider` | TLLlmProvider | null | Currently active Provider instance |
| `skills` | Map<String, TLBaseSkill> | ConcurrentHashMap | Map of registered Skills |
| `memoryStores` | Map<String, TLBaseMemory> | ConcurrentHashMap | Registered memory stores |
| `maxToolCallIterations` | int | 10 | Maximum number of tool-call iterations |
| `defaultModel` | String | `"gpt-4o"` | Default model |
| `defaultTemperature` | double | 0.7 | Default sampling temperature |
| `defaultMaxTokens` | int | 4096 | Default maximum tokens |
| `contextTokenLimit` | long | 1048576 | Token budget for the context sent to the LLM (0 = disabled). History is trimmed by message count, not by size, so an oversized single message can exceed the model context and return a 400; the framework automatically drops the oldest messages / truncates overly long single messages to fit the budget before sending (affects only the send for the current round, does not delete history) |

#### Action Methods

| Method | Action | Description |
|------|----------|------|
| `chat(fromWho, msg)` | `AGENT_CHAT` | **Core method**. Complete chat loop, including multi-round tool-call iterations |
| `chatStream(fromWho, msg)` | `AGENT_CHATSTREAM` | Streaming chat, sends text chunks via callbacks |
| `registerSkill(fromWho, msg)` | `AGENT_REGISTERSKILL` | Registers a Skill (accepts an instance or a class name) |
| `unregisterSkill(fromWho, msg)` | `AGENT_UNREGISTERSKILL` | Unregisters a Skill |
| `listSkills(fromWho, msg)` | `AGENT_LISTSKILLS` | Lists all registered Skills |
| `getAgentContext(fromWho, msg)` | `AGENT_GETCONTEXT` | Gets the session conversation history |
| `clearAgentContext(fromWho, msg)` | `AGENT_CLEARCONTEXT` | Clears the session conversation history |
| `saveAgentMemory(fromWho, msg)` | `AGENT_SAVEMEMORY` | Saves memory to the Memory Store |
| `recallAgentMemory(fromWho, msg)` | `AGENT_RECALLMEMORY` | Recalls memory from the Memory Store |
| `setSystemMsg(fromWho, msg)` | `AGENT_SETSYSTEMMSG` | Sets the session system prompt |
| `setLlmProvider(fromWho, msg)` | `AGENT_SETPROVIDER` | Switches the LLM Provider at runtime |

#### chat() Core Algorithm

```
Input: TLMsg { action:"chat", sessionId, userMessage, [model], [temperature], [maxTokens] }

1. Retrieve the history message list via TLAiContext
2. Append the new user message (Role.user)
3. Collect the FunctionDefinitions of all registered Skills
4. Loop (at most maxToolCallIterations times):
   a. Send the message list + function definitions to the LLM Provider
   b. Parse the LLM response:
      - Plain text → append an assistant message, return finalResponse, exit the loop
      - Contains tool_calls → append an assistant message (with tool_calls)
        → execute the Skills one by one to get results
        → append the tool result messages
        → go back to step a and continue
5. Save the full history to TLAiContext
6. afterMsgTable fires automatically → persist memory

Output: TLMsg { result:true, aiResponse, sessionId, iterations }
```

#### Inner Class myConfig

Parses the custom XML section:

```xml
<providers>    →  HashMap<String, HashMap<String, String>> providers
<skills>       →  HashMap<String, HashMap<String, String>> skills
<memoryStores> →  HashMap<String, HashMap<String, String>> memoryStores
```

Follows the `getHashMap(xpp, tag, subtag)` pattern of `TLDatabase.myConfig`.

---


### TLAiContext — Context Management

**Extends**: `TLBaseModule`
**Implements**: `TLAiAgentParamString`
**Module name**: `"aiContext"`

Manages the conversation history of each session (sessionId). Supports concurrent access from multiple sessions and automatic history trimming.

#### Key Fields

| Field | Type | Default | Description |
|------|------|--------|------|
| `sessions` | Map<String, List> | ConcurrentHashMap | sessionId → message history list |
| `maxHistoryTurns` | int | 50 | Maximum number of rounds retained; older ones are trimmed automatically |
| `toolResultCharLimit` | int | 20000 | Maximum number of characters for a tool result written to history (0 = unlimited). Truncation at the source: massive outputs such as a full-page browser fetch or a large file read are length-capped before being written to history, preventing context-overflow 400 errors and history bloat |
| `defaultSystemMessage` | String | null | Default system prompt for new sessions |

#### Action Methods

| Method | Action | Parameters | Return Value |
|------|----------|------|--------|
| `addMessage(msg)` | `CONTEXT_ADDMESSAGE` | sessionId, entry (TLConversationHistory) or role+content | result:true, turnCount |
| `getMessages(msg)` | `CONTEXT_GETMESSAGES` | sessionId | messageHistory (List) |
| `clear(msg)` | `CONTEXT_CLEAR` | sessionId | result:true |
| `getTurnCount(msg)` | `CONTEXT_GETTURNCOUNT` | sessionId | turnCount (int) |
| `setSystem(msg)` | `CONTEXT_SETSYSTEM` | sessionId, systemMessage | result:true |

#### History Trimming Strategy

When `session.size() > maxHistoryTurns`, all system messages are retained and removal starts from the earliest non-system message until the total number of rounds is ≤ maxHistoryTurns.

---


### TLLlmProvider — Abstract LLM Provider

**Inherits**: `TLBaseModule`
**Implements**: `TLAiAgentParamString`
**Analogous to**: `TLDBServer` (connection abstraction) + `TLHttpClient` (HTTP pattern)

Encapsulates HTTP communication with large language model APIs. Provides the OkHttp request template, authentication header construction, and timeout configuration. Concrete Providers (OpenAI, Claude, etc.) implement request building and response parsing.

#### Key Fields

| Field | Type | Default | Description |
|------|------|--------|------|
| `apiKey` | String | configured value | API key |
| `apiBaseUrl` | String | configured value | API base URL |
| `defaultModel` | String | configured value | Default model |
| `connTimeOut` | Long | 30 seconds | Connection timeout |
| `readTimeOut` | Long | 120 seconds | Read timeout (LLM responses are slow) |
| `writeTimeOut` | Long | 30 seconds | Write timeout |
| `okHttpClient` | OkHttpClient | created automatically | HTTP client instance |
| `gson` | Gson | created automatically | JSON serialization utility |

#### Abstract Methods (subclasses must implement)

| Method | Description |
|------|------|
| `completion(fromWho, msg)` | Non-streaming completion request |
| `completionStream(fromWho, msg)` | Streaming completion (asynchronous callback) |
| `listModels(fromWho, msg)` | List available models |
| `cancel(fromWho, msg)` | Cancel an in-flight request |
| `buildRequestBody(msg, messages, tools, stream)` | Build the Provider-specific request body JSON |
| `parseResponse(responseBody, msg)` | Parse the Provider-specific response JSON |
| `getCompletionsPath()` | API endpoint path |

#### Template Methods (shared implementation)

| Method | Description |
|------|------|
| `buildHttpRequest(path, jsonBody, headers)` | Build the OkHttp Request |
| `buildHeaders()` | Build authentication and common HTTP headers |
| `executeHttpRequest(request, fromWho, msg)` | Execute the HTTP request synchronously |
| `executeHttpRequestAsync(request, callback)` | Execute the HTTP request asynchronously |
| `getEffectiveModel(msg)` | Extract the model name from msg (overrides the default) |
| `getEffectiveTemperature(msg)` | Extract temperature from msg |
| `getEffectiveMaxTokens(msg)` | Extract maxTokens from msg |

---


### TLOpenAiProvider — OpenAI-Compatible Provider

**Inherits**: `TLLlmProvider`
**Module name**: `"openAiProvider"`
**API endpoint**: `/v1/chat/completions`

Compatible with every service that implements the OpenAI Chat Completions API: OpenAI, DeepSeek, Qwen, GLM, and others.

#### Features

- **Request format**: the OpenAI standard `{model, messages, tools, temperature, max_tokens, stream}`
- **Response parsing**: `choices[0].message.content` + `choices[0].message.tool_calls`
- **Streaming support**: SSE `data: [DONE]` detection, parsing `delta.content` and `delta.tool_calls` chunk by chunk
- **Authentication header**: `Authorization: Bearer {apiKey}`

#### Configuration Parameters

| Parameter | Description |
|------|------|
| `apiKey` | API key |
| `apiBaseUrl` | API address, e.g. `https://api.openai.com` |
| `defaultModel` | Default model, e.g. `gpt-4o` |
| `connTimeOut` | Connection timeout in seconds |
| `readTimeOut` | Read timeout in seconds |

---


### TLClaudeProvider — Claude API Provider

**Inherits**: `TLLlmProvider`
**Module name**: `"claudeProvider"`
**API endpoint**: `/v1/messages`

Implements the Anthropic Messages API format. Supports the `tool_use` feature of the Claude 3/4 model series.

#### Features

- **Request format**: the Anthropic `{model, max_tokens, messages, tools, system, stream}`
- **Response parsing**: the `content[]` array, distinguishing `text` and `tool_use` blocks
- **Streaming support**: the Anthropic SSE event format, handling `content_block_start/delta/stop` and `message_stop`
- **Authentication headers**: `x-api-key: {apiKey}` + `anthropic-version: 2023-06-01`
- **Separated System**: the system message is sent through a standalone `system` field rather than being placed in messages

#### Configuration Parameters

| Parameter | Description |
|------|------|
| `apiKey` | API key (format: `sk-ant-xxx`) |
| `apiBaseUrl` | API address, e.g. `https://api.anthropic.com` |
| `defaultModel` | Default model, e.g. `claude-sonnet-4-6` |
| `connTimeOut` | Connection timeout in seconds |
| `readTimeOut` | Read timeout in seconds |

---


### TLBaseSkill — Abstract Skill Base Class

**Inherits**: `TLBaseModule`
**Implements**: `TLAiAgentParamString`
**Analogous to**: `TLBaseCache` (abstract base class + concrete implementation registration)

Each Skill is a functional unit that the LLM can invoke, possessing a name, a natural-language description, and a JSON Schema parameter definition.

#### Key Fields

| Field | Type | Default | Description |
|------|------|--------|------|
| `skillName` | String | module name | The function name visible to the LLM (e.g. `"http_request"`) |
| `skillDescription` | String | module name + "skill" | NL description; the LLM decides when to invoke based on it |
| `parameterSchema` | Map | `{}` | Parameter definition in JSON Schema format |
| `enabled` | boolean | true | Whether enabled |

#### Action Methods

| Method | Corresponding Action | Description |
|------|----------|------|
| `getSkillInfo(msg)` | `SKILL_GETINFO` | Returns name, description, parameterSchema |
| `execute(msg)` | `SKILL_EXECUTE` | **Abstract method**; subclasses implement the core logic |
| `validate(msg)` | `SKILL_VALIDATE` | Validates whether the input parameters match the schema |

#### Helper Methods

| Method | Return Value | Description |
|------|--------|------|
| `buildFunctionDefinition()` | TLFunctionDefinition | Converts the Skill into an LLM function definition |

#### Parameter Schema Format

```java
parameterSchema = {
    "paramName": {
        "type": "string",        // parameter type
        "description": "...",    // parameter description
        "required": true,        // whether required
        "enum": ["a", "b"]       // optional: enum values
    },
    ...
}
```

---


### Built-in Skills

#### TLHttpRequestSkill — HTTP Request

**Function name**: `http_request`
**Module name**: `"httpRequestSkill"`

Allows the LLM to issue HTTP GET/POST requests to fetch external data.

| Input Parameter | Type | Required | Description |
|----------|------|------|------|
| `url` | string | Yes | Request URL |
| `method` | string | No | GET or POST, defaults to GET |
| `headers` | object | No | Request header key-value pairs |
| `body` | string | No | POST request body |

**Output**: JSON format `{status, body, headers}`

#### TLFileOperationSkill — File Operations

**Function name**: `file_operation`
**Module name**: `"fileOperationSkill"`

Allows the LLM to read and write the local file system.

| Input Parameter | Type | Required | Description |
|----------|------|------|------|
| `operation` | string | Yes | read/write/list/exists/delete |
| `path` | string | Yes | File or directory path |
| `content` | string | No | Content to write (required for write) |

**Security Restrictions**:
- `allowedRootPath`: The root directory allowed for operations (defaults to the current directory)
- `maxReadSize`: Maximum read size (defaults to 1MB)
- Path operations outside the root directory are rejected

#### TLCodeExecutionSkill — Code Execution

**Function name**: `code_execution`
**Module name**: `"codeExecutionSkill"`

Executes code snippets in a sandbox environment.

| Input Parameter | Type | Required | Description |
|----------|------|------|------|
| `language` | string | Yes | python or javascript |
| `code` | string | Yes | The code to execute |

**Restrictions**:
- `maxExecutionTime`: Maximum execution time (defaults to 30 seconds)
- `maxOutputSize`: Maximum output size (defaults to 100KB)
- Python: requires `python3` installed on the system
- JavaScript: uses the Java ScriptEngine (Nashorn/GraalJS)

---


### TLBaseMemory — Abstract Memory Base Class

**Inherits**: `TLBaseModule`
**Implements**: `TLAiAgentParamString`

Manages memory storage and retrieval for AI Agents. Supports two modes: short-term (in-memory) and long-term (file persistence).

#### Key Fields

| Field | Type | Default | Description |
|------|------|--------|------|
| `memoryType` | String | `"shortTerm"` | Memory type identifier |
| `defaultExptime` | int | 0 | Default expiration time (minutes), 0 = never expires |

#### Abstract Action Methods

| Method | Corresponding Action | Description |
|------|----------|------|
| `store(msg)` | `MEMORY_STORE` | Store a memory entry |
| `retrieve(msg)` | `MEMORY_RETRIEVE` | Retrieve by key |
| `search(msg)` | `MEMORY_SEARCH` | Search by text (fuzzy matching) |
| `delete(msg)` | `MEMORY_DELETE` | Delete by key |
| `clearAll(msg)` | `MEMORY_CLEARALL` | Clear (all, or a specified session) |

#### Utility Methods

| Method | Description |
|------|------|
| `calculateExpiresAt(exptimeMinutes)` | Compute the expiration timestamp |

---


### Memory Implementations

#### TLShortTermMemoryModule — Short-Term Memory

**Module name**: `"shortTermMemory"`
**Storage**: ConcurrentHashMap (in-memory)
**Features**: TTL auto-expiration, LRU capacity eviction, lost on process restart

| Config Parameter | Default | Description |
|----------|--------|------|
| `maxEntries` | 1000 | Maximum number of entries |
| `cleanupInterval` | 60 seconds | Interval for cleaning up expired entries |
| `defaultExptime` | 0 (never expires) | Default expiration time (minutes) |

**Key format**: `{sessionId}:{key}` — session-level namespace isolation

#### TLLongTermMemoryModule — Long-Term Memory

**Module name**: `"longTermMemory"`
**Storage**: JSON file + in-memory cache
**Features**: Cross-session persistence, retained across process restarts

| Config Parameter | Default | Description |
|----------|--------|------|
| `storagePath` | `./data/longterm_memory` | Storage directory |
| `maxCacheEntries` | 10000 | Maximum in-memory cache entries |
| `defaultExptime` | 0 (never expires) | Default expiration time (minutes) |

**File**: `{storagePath}/memory_store.json`
**Key format**: `{sessionId}:{tag}:{key}` — session+tag-level namespace

---

## Data POJOs


### TLConversationHistory — Conversation History Entry

Represents a single message in a conversation (corresponds to the OpenAI role: system/user/assistant/tool).

| Field | Type | Description |
|------|------|------|
| `role` | Role enum | system / user / assistant / tool |
| `content` | String | Text content (may be null) |
| `toolCalls` | List\<TLToolCall\> | assistant's tool calls (may be null) |
| `toolCallId` | String | Call ID corresponding to the tool result |
| `name` | String | Function name (tool message) |
| `timestamp` | long | Creation timestamp (epoch millis) |
| `metadata` | Map | Extended metadata |

**Convenience constructors**:
```java
new TLConversationHistory(Role.user, "Hello")
new TLConversationHistory(Role.assistant, "Hello! How can I help you?")
new TLConversationHistory(Role.assistant, toolCallsList)
new TLConversationHistory("call_123", "http_request", "{\"status\":200}")
```


### TLToolCall — Tool Call

The function/tool call data structure returned by the LLM.

| Field | Type | Description |
|------|------|------|
| `id` | String | Unique call ID |
| `type` | String | Type, defaults to "function" |
| `functionName` | String | Function name (mapped to the Skill's skillName) |
| `arguments` | Map\<String, Object\> | Function arguments |


### TLFunctionDefinition — Function Definition

A Skill converted into a function schema the LLM can understand.

| Field | Type | Description |
|------|------|------|
| `name` | String | Function name |
| `description` | String | Natural-language description |
| `parameters` | Map | Parameters in JSON Schema format |

**Factory method**:
```java
TLFunctionDefinition.fromSkill(skillName, skillDescription, parameterSchema)
```


### TLMemoryEntry — Memory Entry

The basic unit of memory storage.

| Field | Type | Description |
|------|------|------|
| `key` | String | Unique key |
| `value` | Object | Stored value |
| `type` | String | "shortTerm" or "longTerm" |
| `tag` | String | Category tag |
| `createdAt` | long | Creation time |
| `expiresAt` | long | Expiration time (0 = never expires) |
| `metadata` | Map | Extended metadata |
| `isExpired()` | boolean | Determines whether the entry has expired |

---

## Message Flow


### Full Chat Flow

```
External caller
  │
  │ putMsg("aiagent", {action:"chat", sessionId:"s1", userMessage:"What's the weather in Beijing?"})
  ▼
┌──────────────────────────────────────────────────────────────┐
│ TLAiAgent.chat()                                             │
│                                                              │
│ ① beforeMsgTable triggers →                                  │
│    putMsg("shortTermMemory", {action:MEMORY_SEARCH,          │
│          sessionId:"s1", memoryQuery:"Beijing weather"})     │
│    ← returns related memory                                  │
│                                                              │
│ ② Get context:                                               │
│    putMsg("aiContext", {action:CONTEXT_GETMESSAGES,          │
│          sessionId:"s1"})                                    │
│    ← [History: systemMsg, user:"Hello", assistant:"Hello!"]  │
│    + new message: user:"What's the weather in Beijing?"      │
│                                                              │
│ ③ Collect Skill definitions:                                 │
│    for each skill in skills:                                 │
│      putMsg(skill, {action:SKILL_GETINFO})                   │
│      → buildFunctionDefinition()                             │
│    → [{name:"http_request", description:"...", params:{}}]   │
│                                                              │
│ ④ Send to LLM:                                               │
│    putMsg("openAiProvider", {action:LLM_COMPLETION,          │
│          messageHistory:[...],                               │
│          functionDefinitions:[http_request],                 │
│          model:"gpt-4o"})                                    │
│                                                              │
│ ⑤ Provider HTTP request:                                     │
│    POST https://api.openai.com/v1/chat/completions           │
│    ← response: {tool_calls:[{name:"http_request",            │
│              args:{url:"https://api.weather.com/beijing"}}]} │
│                                                              │
│ ⑥ Execute Tool Call:                                         │
│    skill = skills.get("http_request")                        │
│    putMsg(skill, {action:SKILL_EXECUTE,                      │
│          skillInput:{url:"...", method:"GET"}})              │
│    ← {result:true, skillOutput:"{\"status\":200,...}"}       │
│                                                              │
│ ⑦ Second LLM request (with tool result):                     │
│    send: messages + tool result                              │
│    ← {content:"Sunny in Beijing, 25°C..."} (no tool_calls)   │
│                                                              │
│ ⑧ Save context:                                              │
│    putMsg("aiContext", {action:CONTEXT_ADDMESSAGE, ...})     │
│                                                              │
│ ⑨ afterMsgTable triggers →                                   │
│    putMsg("longTermMemory", {action:MEMORY_STORE,            │
│          memoryValue:"User asked about Beijing weather"})    │
│                                                              │
│ ⑩ Return result:                                             │
└──────────────────────────────────────────────────────────────┘
  │
  ▼
TLMsg {result:true, aiResponse:"Sunny in Beijing, 25°C...", sessionId:"s1", iterations:2}
```


### Streaming Chat Flow

```
External caller
  │ putMsg("aiagent", {action:"chatStream", sessionId, userMessage,
  │                    resultFor:"callerModule", resultAction:"onChunk"})
  ▼
TLAiAgent.chatStream()
  → build context + function defs
  → putMsg(provider, {action:LLM_COMPLETIONSTREAM, ...,
            resultFor:"aiagent", resultAction:"onStreamResult"})

Provider: OK HTTP async + SSE parsing
  → on each chunk received:
    putMsg("aiagent", {action:"onStreamResult",
          chunk:"Today", sessionId})

TLAiAgent.onStreamResult()
  → forward chunk to the original caller:
    putMsg("callerModule", {action:"onChunk", chunk:"Today"})

  → on streamDone received:
    putMsg("callerModule", {action:"onChunk", streamDone:true,
          aiResponse:"full text..."})
```


### Skill Registration Flow

```
External module
  │ putMsg("aiagent", {action:"registerSkill",
  │        moduleName:"mySkill", classfile:"com.example.MySkill",
  │        params:{skillName:"my_function", skillDescription:"..."}})
  ▼
TLAiAgent.registerSkill()
  → create the Skill instance through the factory
  → skills.put("my_function", skillInstance)
  → modules.put("mySkill", skillInstance)
  → return {result:true, skillName:"my_function"}
```


### Provider Switching Flow

```
External module
  │ putMsg("aiagent", {action:"setLlmProvider", llmProvider:"claudeProvider"})
  ▼
TLAiAgent.setLlmProvider()
  → llmProvider = getModule("claudeProvider")
  → defaultLlmProvider = "claudeProvider"
  → return {result:true}
  → subsequent chats automatically use the new provider
```

---

## XML Configuration


### aiagent_config.xml

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<moduleConfig>
    <!-- ===== Basic parameters ===== -->
    <params>
        <!-- Default LLM Provider module name -->
        <defaultLlmProvider value="openAiProvider"/>
        <!-- Maximum number of tool-call iterations -->
        <maxToolCallIterations value="10"/>
        <!-- Maximum number of history rounds for the context -->
        <maxHistoryTurns value="50"/>
        <!-- Skill package name prefix (for classpath shorthand) -->
        <defaultSkillPackageName value="cn.tianlong.tlobject.aiagent.skill.builtin"/>
        <!-- Memory package name prefix -->
        <defaultMemoryPackageName value="cn.tianlong.tlobject.aiagent.memory"/>
        <!-- Default model -->
        <defaultModel value="gpt-4o"/>
        <!-- Default temperature -->
        <defaultTemperature value="0.7"/>
        <!-- Default maxTokens -->
        <defaultMaxTokens value="4096"/>
    </params>

    <!-- ===== LLM Providers ===== -->
    <providers>
        <!-- OpenAI / compatible Provider -->
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

    <!-- ===== AOP interception: memory injection ===== -->
    <beforeMsgTable>
        <!-- Automatically recall short-term memory before chat -->
        <action name="chat">
            <msg action="recallAgentMemory" destination="aiagent"
                 paramsFromMsg="sessionId" systemArgs="usePreReturnMsg:true"/>
        </action>
    </beforeMsgTable>

    <afterMsgTable>
        <!-- Automatically save long-term memory after chat -->
        <action name="chat">
            <msg action="saveAgentMemory" destination="aiagent"
                 paramsFromMsg="sessionId;aiResponse" systemArgs="useActionReturn:true"/>
        </action>
    </afterMsgTable>

    <!-- ===== Startup initialization ===== -->
    <initMsg>
        <msg action="listSkills" destination="aiagent"/>
    </initMsg>
</moduleConfig>
```


### Registration in moduleFactory_config.xml

```xml
<modules>
    <!-- AI Agent core modules -->
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

## Usage Examples


### Example 1: Basic Chat Call

```java
// Start the factory
TLObjectFactory factory = TLObjectFactory.getInstance("conf/aiagent", "moduleFactory_config.xml");
factory.startFactory(null, null);
factory.boot();

// Get the agent module
TLAiAgent agent = (TLAiAgent) factory.getModule("aiagent");

// Send the chat message
TLMsg chatMsg = new TLMsg(AGENT_CHAT)
    .setParam(AI_P_SESSIONID, "user123")
    .setParam(AI_P_USERMESSAGE, "Explain the basic principles of quantum computing");

TLMsg response = agent.putMsg(agent, chatMsg);

if (response.parseBoolean(RESULT, false)) {
    System.out.println("AI reply: " + response.getStringParam(AI_P_RESPONSE, ""));
    System.out.println("Iterations: " + response.getIntParam("iterations", 0));
} else {
    System.out.println("Error: " + response.getStringParam(AI_P_RESPONSE, ""));
}
```


### Example 2: Multi-turn Conversation (with context)

```java
// First round
TLMsg msg1 = new TLMsg(AGENT_CHAT)
    .setParam(AI_P_SESSIONID, "session_001")
    .setParam(AI_P_USERMESSAGE, "My name is Zhang San, I am a Java developer");
TLMsg resp1 = agent.putMsg(agent, msg1);
System.out.println(resp1.getStringParam(AI_P_RESPONSE, ""));

// Second round (same sessionId, context is carried automatically)
TLMsg msg2 = new TLMsg(AGENT_CHAT)
    .setParam(AI_P_SESSIONID, "session_001")
    .setParam(AI_P_USERMESSAGE, "What did I say my name was?");
TLMsg resp2 = agent.putMsg(agent, msg2);
System.out.println(resp2.getStringParam(AI_P_RESPONSE, ""));
// The AI will answer: "Your name is Zhang San"
```


### Example 3: Automatically Triggered Tool Call

```java
// Once httpRequestSkill is configured on the Agent, the LLM will call it automatically
TLMsg msg = new TLMsg(AGENT_CHAT)
    .setParam(AI_P_SESSIONID, "demo")
    .setParam(AI_P_USERMESSAGE, "Fetch the info for https://api.github.com/users/torvalds");

TLMsg response = agent.putMsg(agent, msg);
// The LLM determines it needs http_request → the Agent automatically calls TLHttpRequestSkill
// → the result is sent back to the LLM → the LLM summarizes the response
// Returns a structured summary of the user information
System.out.println(response.getStringParam(AI_P_RESPONSE, ""));
```


### Example 4: Streaming Chat

```java
// Create a module to receive the streaming chunks...
// Streaming call
TLMsg streamMsg = new TLMsg(AGENT_CHATSTREAM)
    .setParam(AI_P_SESSIONID, "stream_demo")
    .setParam(AI_P_USERMESSAGE, "Write a five-character quatrain about spring")
    .setParam(RESULTFOR, "myModule")       // Callback target module
    .setParam(RESULTACTION, "onTextChunk"); // Callback action

agent.putMsg(agent, streamMsg);

// Handle it in myModule's checkMsgAction:
// case "onTextChunk":
//   if (msg.containsParam(AI_P_CHUNK))
//       output characters one by one: msg.getStringParam(AI_P_CHUNK, "")
//   if (msg.parseBoolean(AI_P_STREAMDONE, false))
//       get the full text: msg.getStringParam(AI_P_RESPONSE, "")
```


### Example 5: Registering a Custom Skill at Runtime

```java
// Option 1: register by class name
TLMsg registerMsg = new TLMsg(AGENT_REGISTERSKILL)
    .setParam(MODULENAME, "myCustomSkill")
    .setParam(MODULE_CLASSFILE, "com.example.MyCustomSkill")
    .setParam(MODULE_PARAMS, Map.of("skillName", "my_function",
                                     "skillDescription", "My custom function"));
agent.putMsg(agent, registerMsg);

// Option 2: pass an instance directly
MyCustomSkill skillInstance = new MyCustomSkill("myCustomSkill");
skillInstance.setSkillName("my_function");
skillInstance.setSkillDescription("My custom function");

TLMsg registerMsg2 = new TLMsg(AGENT_REGISTERSKILL)
    .setParam(INSTANCE, skillInstance);
agent.putMsg(agent, registerMsg2);
```


### Example 6: Managing Context and Memory

```java
// View the context
TLMsg ctxMsg = new TLMsg(AGENT_GETCONTEXT)
    .setParam(AI_P_SESSIONID, "session_001");
TLMsg ctxResp = agent.putMsg(agent, ctxMsg);
List<TLConversationHistory> history =
    (List<TLConversationHistory>) ctxResp.getListParam(AI_P_MESSAGEHISTORY, List.of());
System.out.println("History size: " + history.size());

// Clear the context
agent.putMsg(agent, new TLMsg(AGENT_CLEARCONTEXT)
    .setParam(AI_P_SESSIONID, "session_001"));

// Manually save memory
agent.putMsg(agent, new TLMsg(AGENT_SAVEMEMORY)
    .setParam(AI_P_SESSIONID, "user123")
    .setParam(AI_P_MEMORYKEY, "user_name")
    .setParam(AI_P_MEMORYVALUE, "Zhang San")
    .setParam(AI_P_MEMORYTAG, "user_fact"));

// Recall memory
agent.putMsg(agent, new TLMsg(AGENT_RECALLMEMORY)
    .setParam(AI_P_SESSIONID, "user123")
    .setParam(AI_P_MEMORYQUERY, "name"));
```


### Example 7: Switching Provider at Runtime

```java
// Switch from OpenAI to Claude
agent.putMsg(agent, new TLMsg(AGENT_SETPROVIDER)
    .setParam(AI_P_PROVIDER, "claudeProvider"));

// Subsequent chats automatically use Claude
```


### Example 8: Async Call

```java
// Use the framework's async message mechanism
TLMsg asyncMsg = new TLMsg(AGENT_CHAT)
    .setParam(AI_P_SESSIONID, "async_demo")
    .setParam(AI_P_USERMESSAGE, "Summarize today's news for me")
    .setWaitFlag(false);  // async

agent.putMsg(agent, asyncMsg);
// Returns immediately, without waiting for the LLM response
// Callback handling can be configured via msgTable or beforeMsgTable
```

---

## Extension Development


### Adding a Custom LLM Provider

1. Extend `TLLlmProvider`
2. Implement the abstract methods: `completion()`, `completionStream()`, `listModels()`, `cancel()`, `buildRequestBody()`, `parseResponse()`, `getCompletionsPath()`
3. Register it in the XML configuration

```java
public class MyCustomProvider extends TLLlmProvider {
    @Override
    protected String getCompletionsPath() { return "/v1/chat"; }

    @Override
    public String buildRequestBody(TLMsg msg, List<TLConversationHistory> messages,
                                    List<TLFunctionDefinition> tools, boolean stream) {
        // Build the JSON request body for the custom API
    }

    @Override
    public TLMsg parseResponse(String responseBody, TLMsg originalMsg) {
        // Parse the JSON response from the custom API
    }

    @Override
    protected TLMsg completion(Object fromWho, TLMsg msg) { /* implementation */ }
    @Override
    protected TLMsg completionStream(Object fromWho, TLMsg msg) { /* implementation */ }
    @Override
    protected TLMsg listModels(Object fromWho, TLMsg msg) { /* implementation */ }
    @Override
    protected TLMsg cancel(Object fromWho, TLMsg msg) { /* implementation */ }
}
```


### Adding a Custom Skill

1. Extend `TLBaseSkill`
2. Set `skillName`, `skillDescription`, `parameterSchema`
3. Implement the `execute(fromWho, msg)` method
4. Register it in XML `<skills>`, or at runtime via `registerSkill`

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

        // Execute the database query...
        // The database module can be invoked via message
        TLMsg dbMsg = createMsg().setAction("query")
            .setParam("sql", sql);
        TLMsg dbResult = putMsg("database", dbMsg);

        return createMsg()
            .setParam(RESULT, true)
            .setParam(AI_P_SKILLOUTPUT, dbResult.toString());
    }
}
```


### Adding a Custom Memory Store

1. Extend `TLBaseMemory`
2. Implement `store()`, `retrieve()`, `search()`, `delete()`, `clearAll()`
3. Register it in XML `<memoryStores>`

```java
public class MyRedisMemoryModule extends TLBaseMemory {
    // Redis-based distributed memory storage
    @Override protected TLMsg store(Object fromWho, TLMsg msg) { /* Redis SET */ }
    @Override protected TLMsg retrieve(Object fromWho, TLMsg msg) { /* Redis GET */ }
    @Override protected TLMsg search(Object fromWho, TLMsg msg) { /* Redis SCAN */ }
    @Override protected TLMsg delete(Object fromWho, TLMsg msg) { /* Redis DEL */ }
    @Override protected TLMsg clearAll(Object fromWho, TLMsg msg) { /* Redis FLUSHDB */ }
}
```

---


## Build and Run

```bash
# Build the entire AI Agent module
mvn clean install -pl aiagent -am -DskipTests

# Build only the common module
mvn clean install -pl aiagent/common -am -DskipTests

# Run the demo (the demo config must be created first)
mvn exec:java -pl demo/tlobject \
  -Dexec.mainClass="cn.tianlong.java.demo.aiagent.AiAgentDemo"
```

---


## Class Relationship Overview

```
TLParamString (core constants interface)
  └── TLAiAgentParamString (AI constants interface)
        ├── TLAiAgent (master orchestrator)     extends TLBaseModule
        │     └── myConfig (XML parsing)   extends TLModuleConfig
        ├── TLAiContext (context management)   extends TLBaseModule
        ├── TLLlmProvider (abstract Provider) extends TLBaseModule
        │     ├── TLOpenAiProvider     extends TLLlmProvider
        │     │     └── StreamCallback (SSE parsing)
        │     └── TLClaudeProvider     extends TLLlmProvider
        │           └── ClaudeStreamCallback (SSE parsing)
        ├── TLBaseSkill (abstract Skill)    extends TLBaseModule
        │     ├── TLHttpRequestSkill   extends TLBaseSkill
        │     ├── TLFileOperationSkill extends TLBaseSkill
        │     └── TLCodeExecutionSkill extends TLBaseSkill
        └── TLBaseMemory (abstract memory)    extends TLBaseModule
              ├── TLShortTermMemoryModule extends TLBaseMemory
              └── TLLongTermMemoryModule  extends TLBaseMemory

Data POJO (implements Serializable):
  TLConversationHistory, TLToolCall, TLFunctionDefinition, TLMemoryEntry
```

---

## New Feature Modules


### MCP Marketplace and Tool Integration

**Package**: `cn.tianlong.tlobject.aiagent.mcp` — `TLMcpRegistry`, `TLMcpAgent`, MCP client

MCP (Model Context Protocol) tool package marketplace: install external MCP servers as sub-agents, and the LLM delegates calls to them via `delegate_to_*`.

**Console Commands**:

| Command | Description |
|------|------|
| `/mcp search [keyword]` | Search MCP servers (with no arguments, lists all curated entries; curated index + npm online search) |
| `/mcp info <package>` | Show package details (features, tool list, homepage) |
| `/mcp install <package> [name] [--args ...]` | Install as a sub-agent (auto-named, detected at runtime) |
| `/mcp list` | List installed MCP Agents and their status |
| `/mcp remove <name>` | Uninstall an MCP Agent |

**Components**:
- `TLMcpRegistry` — curated index (built-in) + npm online search, the data query layer
- `TLMcpAgent` — MCP bridge Agent (no LLM): connects to an MCP server, auto-discovers tools → expands them into N function defs for the master to delegate to


### Evals Evaluation System

**Package**: `cn.tianlong.tlobject.aiagent.evals` — `TLEvalsModule`

Framework-level automated evaluation: JSON cases + reports, three kinds of Judge (exact match / LLM judge / constraint check).

**Console Commands**:

| Command | Description |
|------|------|
| `/eval suite` | Run all evaluation cases |
| `/eval list` | List available cases |
| `/eval quick` | Quick self-check |
| `/eval run <id>` | Run the specified case |
| `/eval cascade [agent]` | Cascade evaluation (multi-target, multi-call mode) |

`TLAgentService.doEval` only does routing + result-summary formatting; all evaluation logic lives inside the evals module.


### Unit Tests /test

**Package**: `cn.tianlong.tlobject.aiagent.test` — `TLAgentTestModule`, `TLMockProvider`, `TLEchoSkill`, `TLSleepSkill`

Deterministic unit tests (driven by the Mock Provider, zero network, reproducible results). Covers 9 scenarios: the doChat main loop, multi-round conversation, single/parallel tool calls, timeout, streaming, cancellation, batch timeout, and session recovery.

**Console Commands** (available once the chat application has the test module configured):

| Command | Description |
|------|------|
| `/test` | Run all tests (9 built-in scenarios + custom cases) |
| `/test list` | List available test cases |
| `/test <caseName>` | Run a single scenario (e.g. `/test basicChat`) |

**Key Points for Wiring the Chat Application** (`conf/demo/aiagent/`):
- The test target is an **independent `aiagent` instance** (`aiagent_config.xml`), fully isolated from the master `aiagent_master`: switching tests to mockProvider does not affect real conversations, and sessions use the file-based sessionManager without polluting `/sessions`
- `caseFile` in `agentTestModule_config.xml` points to `agentTest_cases.xml` (custom cases driven by XML configuration)
- The two demo config directories (`conf/demo/aiagent/` and `conf/demo/aitest/`) are independent of each other and share only the common `conf/tlobject/` layer


### HITL Human Approval

**Package**: `cn.tianlong.tlobject.aiagent.approval` — `TLApprovalModule` (approvalGate), `TLApprovalRequest`, `ConsoleReviewer`, `IApprovalReviewer`

High-risk tool operations require human approval before they are executed.

**Rule Configuration** (`approvalGate_config.xml`, global singleton):
```xml
<!-- The key is the LLM function name (e.g. file_operation), not the module name (fileOperationSkill)!
     tool:op → the specified operation of that tool requires approval; tool → all operations require approval -->
<approvalRules value="file_operation:delete, file_operation:write"/>
```

**How to Enable**: each Agent sets `<approvalModule value="approvalGate"/>` in its own config (enabled for fileAgent/codeAgent; not enabled for master — approval is pushed down to the sub-agents that perform sensitive operations).

**Console Interaction**: approval dialogs are published via `msgBus` (topic `approvalEvent`); the console subscribes with `registBus` at startup and renders them itself, so the approval module and the console have zero direct dependency. Commands:

```
/approve approve:ID        approve
/approve reject:ID:reason     reject
```

**Rejection Semantics (Important)**:
- Rejection is a **structured flag** propagated level by level along the delegation chain (`doChat` returns the `rejected`/`rejectReason` parameters → `ToolExecutor` recognizes the sub-module rejection → the parent agent rolls back and stops the loop); it does not rely on the LLM understanding the wording
- **Session-level rejection memory**: within the same session, once a given (tool + parameters) combination has been rejected, later requests are rejected directly without showing the dialog again; a different session or different parameters goes through normal approval


### Session Management and Checkpoint Resume

- `TLSessionManager` — file-based (JSONL incremental storage, isolated under `data/{userId}/`)
- `TLDatabaseSessionManager` — database-based (two tables: ai_sessions + ai_session_rounds)
- **Checkpoint resume**: the Agent side sets `enableCheckpoint=true` and the SessionManager persists to disk; after a mid-loop interruption the console shows the `⚠ prompt /resume`

**Console Commands**:

| Command | Description |
|------|------|
| `/sessions` | List all historical sessions |
| `/continue [id]` | Continue a historical session (without an id, resumes the most recent one) |
| `/resume` | Resume an unfinished mid-loop checkpoint session |


### Web Interaction Window (aiagent/webui)

`AIStart -web` (or `aistart-web.bat`) starts both the console and the Web window in the same process:
Open `http://localhost:8080/webui/chat.html` in a browser (change the port in the jettyServer parameter of `moduleFactory_chat_web_config.xml`).

- Login: the `passwords` parameter of the `webui` module (demo default admin/tianlong, password 123456); if no password is configured, any non-empty userId can log in
- Chat: streaming (fetch-SSE) / non-streaming, the reasoning process can be collapsed, and the final line shows token statistics
- Command panel (graphical; everything goes through the existing agentService actions):
  - Session: list/continue/switch/clear/checkpoint resume
  - Agent/Skill: list, /param parameter view, install (skill/agent/baseSkill), uninstall, reload
  - MCP marketplace: search/info/install/list/remove
  - Evaluation: suite/list/quick/run/cascade; tests: list/all/single case
  - Tracing: /trace, /stats, /stats all, /stats agent
- Approval: approval requests are pushed to a browser dialog via SSE; you can edit the parameters before approving or rejecting
- User isolation: the login identity is passed through to agentService, and sessions and memory under data/{uid}/ are isolated per user
- Implementation: TLWebChatModule (business logic) + TLWebChatServlet (IO adapter, mounted via TLJettyServer extraServlets), with static pages embedded in the jar classpath


### Workflow Orchestration and Field-level Merge (TLAgentWorkflow)

A **workflow** describes a multi-agent pipeline as a DAG: each node is an AGENT (corresponding to a private instance in `<modules>`),
edges declare dependencies, and the engine derives the execution order automatically. Nodes/edges have three sources (highest to lowest priority):

```
msg.expression > msg.nodes/edges > XML <expression> > XML <nodes>/<edges>
```

The expression form is the most common entry point and is more concise than hand-writing nodes/edges:

```xml
<params>
    <expression value="(poetA &amp;&amp; poetB) -> poetc"/>
</params>
```

| Syntax | Meaning |
|------|------|
| `A && B` | Parallel: both A and B run, and execution continues once both complete (a merge node is generated automatically) |
| `A \|\| B` | Fallback: B runs only if A fails |
| `A -> B` | Sequential: B waits for A to finish, and A's output body is merged into B's prompt |
| `if (A.score > 0.5) B else C` | Value branch |
| `(A && B) as m1` | Names the merge point so that field-level merge can target it (see below) |

#### A Node's Result Travels Over Three Channels

This is essential to understanding what merge can and cannot change:

| Channel | Content | Managed by | When it runs |
|------|------|--------|--------|
| ① args field merge | `issues` / `score` etc., **field by field** | **Configurable Reducer** | On every node entry |
| ② `upstreamResponses` | list of `【nodeName】\nbody` | Framework fixed logic | On every node entry |
| ③ Final summary `AI_P_RESPONSE` | Re-scans all node outputs and concatenates them | Framework fixed logic | Once, at workflow wrap-up |

**A merge point such as m1 always runs ② on every entry** — the bodies of both upstreams are collected into `upstreamResponses`, each carrying the `【nodeName】`
prefix to distinguish their sources, and downstream nodes concatenate them into the `userMessage`. This is hard-coded by the framework and is independent of whether a Reducer is configured.
**Ordinary agents such as poetA/poetB produce only `aiResponse`**, so their results actually travel over channel ②.

#### Field-level Merge: It Changes Channel ①

When multiple upstreams produce **parameters with the same name** (for example, two check nodes each returning `issues`), channel ① defaults to overwrite —
the later one wins over the earlier one. Declaring a merge strategy lets them accumulate:

```xml
<params>
    <expression value="(poetA &amp;&amp; poetB) as m1 -> poetc"/>
    <merge.m1 value="issues:append, score:min"/>
</params>
```

Format: `merge.<mergePointName> = <field>:<strategy>(, <field>:<strategy>)*`, where the merge point name is the name in `as`.

| Strategy | Behavior |
|------|------|
| `lastWriteWins` / `last` / `overwrite` | Last write wins (default) |
| `append` | Append to the list (a single value is added as one element) |
| `min` / `max` / `sum` | Numeric merge, **string numbers are accepted**, dirty values are ignored without throwing |
| `or` / `and` | Boolean merge |
| `mapMerge` | Shallow Map merge |
| `setUnion` | Deduplicated union |
| `adaptive` | Type-adaptive (default): List/Set are appended, everything else is overwritten |

**Fields that are not declared fall back to the default (adaptive)**: list-like values are appended without losing data, while scalars are still overwritten (unchanged from before the rework).

#### Which Fields Can Be Merged

The field name = **the parameter name in the message produced by the upstream node** (the args key of `TLMsg`). An ordinary LLM agent returns only
a single `aiResponse` body, so for field-level merge to be useful the upstream must be a node that carries business parameters in addition to `aiResponse` —
for example a skill node (its result goes into `AI_P_SKILLOUTPUT`), or a custom node (whatever `TLMsg.setParam` writes is what you get).

Reserved fields (configuring them produces a WARN and is ignored):

| Field | Reason |
|------|------|
| `aiResponse` / `upstreamResponses` | Framework body channel (②), exclusively owned by the framework |
| Final summary | Re-scanned from node outputs at wrap-up (③), does not go through merge |

#### Three Easy Pitfalls

1. **`as` names a merge point but no `merge.<name>` is configured** — naming is itself the declaration "I want to configure it"; naming without configuring
   is a wasted name, and a WARN is emitted at startup. If you don't need customization, don't write `as`.
2. **`as` can only name a merge point** — what follows must resolve to a `JOIN` (generated by `&&`/`||`/`if`),
   and naming a single node or a condition node causes a compile error.
3. **It works the same in expression mode and static node mode** — `as` names a generated node; in static mode you simply use
   the id of `<node id="...">` as the merge point name.

Configuration errors are all reported or warned about at **load time** (unknown strategy name / reserved field / no merge point with that name / wasted naming);
they never wait until runtime to surface as "a field was silently overwritten".
