# TLAgent — AI Agent Framework on TLObject

**English** | [中文](README.md)

An AI Agent sub-framework built on the **TLObject Unified Object Message Programming model**. Everything is message-driven: all modules communicate through `TLMsg`, and their lifecycles are managed by the unified `TLObjectFactory`.

---

## Highlights: Why This Agent Framework

The agent world inherits the parent framework's philosophy: **every component is an independent, free, and equal object** — created by the unified factory, communicating only through messages. There is no "glue code": every stage of an agent run (reasoning, tool calling, memory access, orchestration) is an observable, interceptable, replaceable message.

### 1. Structural advantages of a message architecture

- **Fully observable and interceptable**: LLM calls, tool executions and memory reads are all messages. `beforeMsgTable` / `afterMsgTable` hooks can inject cross-cutting logic (memory recall, approval, audit, metrics) at any stage; a `roundId` spans the whole chain and `/trace` allows point-in-time replay of any round
- **Zero special-case components**: Providers, Skills, Memories, sub-Agents, Agent groups and Workflows are *all* `TLBaseModule` — identical lifecycle, hot-reload and configuration semantics as every other module in the framework. Learn one, know them all
- **Declarative assembly**: system prompts, tool inventories, memory policies and sub-agent topology all live in XML. Switching models (DeepSeek ⇄ OpenAI ⇄ Claude) or memory stores (files ⇄ database) is a config change, not a code change

### 2. Conversational intelligence

- **One code path for streaming & non-streaming**: a unified `chat()` entry with SSE streaming across DeepSeek / OpenAI / Claude-compatible providers (pluggable)
- **Model routing + intent cache**: simple questions are auto-routed to lightweight models to save tokens; complex tasks are escalated to stronger ones. Learned high-frequency intents answer from cache instantly
- **Reasoning (ReAct) in four modes**: `off / prompt / native / auto` — natively parses DeepSeek `reasoning_content` and Claude Extended Thinking
- **Automatic context management**: multi-turn trimming and automatic session history — long conversations stay under control

### 3. Tool (Skill) capabilities

- **Four tool kinds in one registry**: built-in skills (HTTP / file / code execution / browser / desktop GUI) + custom skills + MCP marketplace (install tools with one command) + msgTool (let the LLM call any framework message route directly)
- **Parallel tool execution**: multiple `tool_calls` from one response run concurrently and results are re-ordered faithfully
- **Hot pluggability**: skills and sub-agents can be registered, unregistered and reloaded at runtime (`/install`, `/reload`)
- **Tools-as-agents**: sub-agents and agent groups are first-class tools in the workflow — the master agent contains no type-specific code

### 4. Three-tier memory

| Tier | Backing | Purpose |
|------|---------|---------|
| Conversation context | `TLAiContext` | continuity of the current dialogue, auto-trimmed |
| Short-term memory | in-process TTL cache | transient state for external skills |
| Long-term memory | file JSONL / database | cross-session knowledge fragments with hierarchical summaries (fragments → summaries) and optional embedding recall |

Memory flows through **AOP message hooks**: auto-recall before a chat → inject into system context → auto-consolidate afterwards. Zero business-code intrusion, and session/user isolation comes for free.

### 5. Multi-agent orchestration

- **Sub-agent delegation**: intent-based description routing; the master agent has no type special-casing
- **Agent groups**: sequential chains / parallel merge / supervisor review & summary
- **Workflow DAG + expression DSL**: `&&` parallel, `||` fallback, `->` sequence, `if` value-branch — express an orchestration in one line
- **Checkpoint resume / direct output**: pause & resume mid-run (`/resume`); sub-agent results can bypass LLM re-processing (directOutput)

### 6. Engineering & safety

- **HITL approval gate**: dangerous tools (file deletion, code execution) require human approval by function name; session-level refusal memory (the same operation never re-prompts); approval UI fully decoupled via an event bus
- **Deterministic self-testing**: 12 Mock-provider scenarios — no network, no API key, reproducible results; refactor without fear of regressions
- **Evals harness**: JSON test cases with three judge types (exact / LLM / constraint) for batch regression
- **Session system**: multi-user data isolation, checkpoint recovery, token statistics, full-chain `/trace`
- **Web UI (`webui`)**: browser chat with tool-result visualization and session management, sharing the same framework — no extra dependencies

> From "calling an LLM once" to "enterprise-grade multi-agent orchestration" is one gradual path: run with Mock → plug in a real key → add tools on demand → enable orchestration and approval. No step invalidates the previous one.

---

## Table of Contents

- [Architecture](#architecture)
- [Module Layout](#module-layout)
- [Core Classes](#core-classes)
- [Message Flow](#message-flow)
- [XML Configuration](#xml-configuration)
- [Quick Start](#quick-start)
- [Key Concepts](#key-concepts)
- [Full Chinese Manual](#full-chinese-manual)

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                      TLAiAgent (master orchestrator)              │
│  chat() loop:                                                     │
│   user input → fetch context → collect skill defs → send to LLM   │
│   → parse response:                                               │
│      tool_calls present → execute skills → feed results back      │
│      plain text        → return to user                           │
│   → beforeMsgTable injects memory → afterMsgTable persists it     │
└──────┬──────────┬───────────┬───────────┬────────────────────────┘
       │          │           │           │
       ▼          ▼           ▼           ▼
┌──────────┐ ┌────────────┐ ┌──────────┐ ┌──────────────┐
│TLAiContext│ │TLLlmProvider│ │TLBaseSkill│ │TLBaseMemory   │
│ (history) │ │ (abstract) │ │(abstract) │ │  (abstract)   │
└──────────┘ └───┬───┬─────┘ └───┬───┬───┘ └───┬───┬───────┘
                 │   │           │   │         │   │
          ┌──────┘   └──────┐    │   │    ┌────┘   └──────┐
          ▼                 ▼    ▼   ▼    ▼               ▼
   ┌────────────┐ ┌────────────┐ ┌──────────┐ ┌──────────────┐
   │ OpenAI     │ │ Claude     │ │ HTTP     │ │ short-term   │
   │ Provider   │ │ Provider   │ │ file ops │ │ memory       │
   │ (SSE)      │ │ (SSE)      │ │ code exec│ │ long-term    │
   └────────────┘ └────────────┘ └──────────┘ └──────────────┘
```

Design principles: everything is a message · everything is a module · XML-driven configuration · reflective action dispatch (`checkMsgAction()` → `invokeAction()`) · AOP via `before/afterMsgTable` · pluggable Provider pattern.

---

## Module Layout

```
aiagent/
├── pom.xml                               # aggregator POM
├── common/                               # tlobject-aiagent-common
│   └── .../aiagent/
│       ├── TLAiAgent.java                # master orchestrator ★
│       ├── TLAiContext.java              # context management
│       ├── TLLlmProvider.java            # abstract LLM provider
│       ├── TLBaseSkill.java              # abstract skill base
│       ├── TLBaseMemory.java             # abstract memory base
│       └── evals/                        # Evals harness
├── provider-openai/                      # OpenAI/DeepSeek-compatible provider
├── provider-claude/                      # Claude provider
├── skill-builtin/                        # HTTP / file / code-execution skills
├── agent-builtin/                        # built-in agents (planTask etc.)
├── memory-store/                         # short-term & long-term memory
└── webui/                                # browser chat UI
```

### Maven dependencies

| Module | artifactId | Depends on |
|--------|-----------|------------|
| common | `tlobject-aiagent-common` | `tlobject-core`, gson, okhttp |
| provider-openai | `tlobject-aiagent-provider-openai` | common |
| provider-claude | `tlobject-aiagent-provider-claude` | common |
| skill-builtin | `tlobject-aiagent-skill-builtin` | common |
| agent-builtin | `tlobject-aiagent-agent-builtin` | common |
| memory-store | `tlobject-aiagent-memory-store` | common |
| webui | `tlobject-aiagent-webui` | common |

---

## Core Classes

| Class | Role |
|-------|------|
| `TLAiAgent` | Master orchestrator. Unified `chat()` for streaming & non-streaming; tool-call iteration loop; stop/pause support; session checkpointing |
| `TLAiContext` | Conversation history with auto-trimming; per-agent system prompt |
| `TLLlmProvider` | Abstract LLM provider (stream & non-stream) |
| `TLOpenAiProvider` | OpenAI / DeepSeek compatible provider (SSE) |
| `TLClaudeProvider` | Claude API provider |
| `TLBaseSkill` | Abstract skill base (execute contract + function-definition export) |
| `TLBaseMemory` | Abstract memory base |
| `TLToolExecutor` | Shared tool executor with HITL approval gate |
| `TLSessionManager` | Session persistence & checkpoint resume |

---

## Message Flow

`chat` messages are routed to `TLAiAgent` via the factory. In the `beforeMsgTable`, memory-recall hooks inject context; inside `doChat`, the agent loops: gather function definitions → call the LLM → if `tool_calls` arrive, execute each skill/agent through the shared executor → feed results back → repeat until a plain-text final answer; `afterMsgTable` hooks persist memories and session checkpoints. The full chain (user console → agent → LLM → tool → memory) can be replayed round-by-round with `/trace`.

---

## XML Configuration

Modules are configured like everything else in TLObject — XML files parsed by `TLModuleConfig`. Key elements: `<providers>` (LLM providers & model routing), `<agents>` (sub-agents, groups, workflows), `<skills>`, `<memoryStores>`, `<msgTable>` / `<msgTools>` (message routes the LLM may call), `<beforeMsgTable>` / `<afterMsgTable>` (AOP hooks), and `<modulesParams>` (e.g. `maxContextMessages`, `defaultSystemMessage`).

Full reference — see the Chinese manual below.

---

## Quick Start

**Prerequisites**: JDK 17+, Maven 3.6+; clone the repository root.

```bash
# 1. Build and install all modules
mvn clean install -DskipTests

# 2. Automated self-test (Mock provider — no key, no network, no DB)
mvn -pl demo/tlobject dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -Dfile.encoding=UTF-8 \
     -cp "demo/tlobject/target/classes;$(cat demo/tlobject/target/cp.txt)" \
     cn.tianlong.java.demo.aiagent.AiAgentDemoStartup
# The [TEST] summary shows 12 scenarios — all PASS means the framework works

# 3. Interactive chat console (needs a real API key — see below)
java -Dfile.encoding=UTF-8 \
     -cp "demo/tlobject/target/classes;$(cat demo/tlobject/target/cp.txt)" \
     cn.tianlong.java.demo.aiagent.AIStart
```

**API key**: all provider configs under `demo/tlobject/src/main/resources/conf/demo/aiagent/` use the placeholder `apiKey="sk-your-api-key"`. Replace it with your own DeepSeek / OpenAI / Claude key before chatting (providers & models are chosen in the same configs). Never commit a real key.

Console commands: `/stream` (toggle streaming) · `/thinking off|prompt|native|auto` · `/sessions` · `/continue [id]` · `/resume` · `/clear` · `/test` (unit tests) · `/eval` (evals) · `/mcp search|install|list` · `/approve` · `/trace` · `/exit`

---

## Key Concepts

- **Model routing**: `TLRoutingProvider` decorator picks light/heavy models per task keywords; `TLIntentCacheProvider` learns frequent intents and answers from cache
- **Skills**: implement `TLBaseSkill`, register via XML or runtime `/install`; MCP servers integrate through the marketplace (npm search + install)
- **Memory**: three tiers (see above); long-term memory stores fragments with hierarchical summaries, optionally embedded for vector recall
- **Multi-agent**: sub-agents (own LLM/skills/memory), agent groups (sequential/parallel/supervisor), workflow DAGs and expression DSLs; everything is a tool to the master
- **Approval (HITL)**: the shared `TLToolExecutor` enforces rules by function name (`file_operation:delete`, `code_execution:*`); rejections are structured flags, and same-session-same-args refusals are remembered
- **Testing**: Mock-provider unit suite (`/test`, 12 scenarios) + JSON Evals with three judge types

---

## Full Chinese Manual

The complete manual (≈1,400 lines, in Chinese) lives in **`aiagent/README.md`**: architecture deep-dive, all core classes with fields/actions/algorithms, message flows, XML reference, usage examples, extension development, and the new-feature modules (MCP, Evals, unit tests, HITL, sessions).
