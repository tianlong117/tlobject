# TLObject — Unified Object Message Programming Framework

**English** | [中文](README.md)

> A Java message-driven programming framework: replace direct method calls with **sending messages to named objects**, where every object is created and managed by a unified factory. Message interaction, name interaction, factory creation — flexibility and extensibility by design.

Author: tianlong · Blog (Chinese): https://blog.csdn.net/tianlong117

---

## Table of Contents

- [Core Idea](#core-idea)
- [Highlights](#highlights)
- [Quick Start](#quick-start)
- [Architecture](#architecture)
- [Module Map](#module-map)
- [AI Agent Sub-framework](#ai-agent-sub-framework-aiagent)
- [Configuration System](#configuration-system)
- [Demos & Self-testing](#demos--self-testing)
- [Logging](#logging)
- [License](#license)

---

## Core Idea

Unified Object Message Programming is not just a framework — it is a way of thinking. Instead of invoking an object's method directly (`A.func()`), you send a message to the object to trigger execution. This changes how objects interact and gives applications great flexibility:

**1. All objects interact through messages**

```java
TLMsg msg = new TLMsg();
msg.setAction("func");          // the message carries an action + parameters
msg.setParam("key", value);
putMsg(A, msg);                 // send the message to object A
```

**2. All objects interact by name**

Any object can be given a registered name. Send messages by name — no instance reference needed:

```java
putMsg("worker", msg);          // "worker" is the object's registered name
```

**3. All objects are created by a unified factory**

`TLObjectFactory` creates, initializes and starts objects from XML configuration. You only use names; lifecycle is not your concern:

```java
putMsg("worker", msg);          // no `new` — the factory creates and boots it
```

---

## Highlights

- **Message-driven architecture**: `TLMsg` (action + args + routing + chained `nextMsg`) unifies sync, async, delayed, multicast, bus and routed messaging
- **Declarative XML configuration**: module definitions, parameters, message routing tables, and `beforeMsgTable`/`afterMsgTable` AOP hooks are fully config-driven, hot-reloadable
- **Rich module ecosystem**: database DAO, Jetty/Tomcat/Netty servers, WebSocket, HTTP client, cache, Redis, cron jobs, logging, Excel export — 15 top-level modules, 40+ reusable modules
- **AI Agent sub-framework**: multi-turn chat, tool/skill calling, three-tier memory, streaming, multi-subagent orchestration, MCP integration, HITL approval, session persistence & checkpoint resume (see [aiagent/README.md](aiagent/README.md))
- **Built-in self-testing**: a deterministic Mock-Provider test suite — no real LLM, network or database needed (9 scenarios)

---

## Quick Start

**Prerequisites**: JDK 17+, Maven 3.6+ (Windows / macOS / Linux)

```bash
# 1. Build and install all modules (~1 minute)
mvn clean install -DskipTests

# 2. Run the AI Agent automated self-test (Mock provider — no API key, network or DB)
mvn -pl demo/tlobject exec:java \
    -Dexec.mainClass=cn.tianlong.java.demo.aiagent.AiAgentDemoStartup
# Look for [TEST] summary lines in the log — the result of the 9 test scenarios

# 3. Start the interactive chat console (fill in a real API key first — see below)
mvn -pl demo/tlobject exec:java \
    -Dexec.mainClass=cn.tianlong.java.demo.aiagent.AIStart
```

> An IDE (IntelliJ IDEA / Eclipse) opening the root `pom.xml` works too — just run any demo `main` under `demo/tlobject`.

**Configuring an API Key**

All LLM provider configs in this repository use the placeholder `apiKey="sk-your-api-key"`. Before running chat demos, replace every occurrence under `demo/tlobject/src/main/resources/conf/demo/aiagent/` with your own key (DeepSeek / OpenAI / Claude; providers and model names are also chosen in these configs). **Never commit a real key to a public repository.**

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                  TLAppStartUp (application entry)             │
│   CLI args: -d config dir / -m factory config / -f app cfg    │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
┌──────────────────────────────────────────────────────────────┐
│                  TLObjectFactory (unified factory)            │
│   Instantiates/starts/manages every named object from XML     │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
┌──────────────────────────────────────────────────────────────┐
│   IObject ── TLBaseObject ── TLBaseModule (everything is a    │
│   module): putMsg()/getMsg() · lifecycle init→runInit→runStart│
│   msgTable routing · before/afterMsgTable AOP · invokeAction  │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
          TLMsg (message: action + args + systemArgs + routing)
```

**Message flow**: `putMsg("moduleName", msg)` → factory resolves the module by name → target's `getMsg()` validates routing → `beforeMsgTable` hooks run → action dispatched to a Java method by reflection (`invokeAction`) → `afterMsgTable` hooks → result returned (`nextMsg` chaining supported).

**Built-in infrastructure modules** (inside `core`, all reusable via messages): thread pool `TLThreadPool`, router `TLMsgRouter`, pub-sub bus `TLMsgBus`, broadcaster `TLMsgBroadCast`, scheduled scanner `TLMsgScanner`, blocking queue `TLMsgBlockingQueue`, module pool `TLModulePool`, session data `TLSessionData`, validation `TLParamValidation`, monitor `TLModuleMonitor`, exception handler `TLExecptionHandler`, and more.

---

## Module Map

| Module | Description |
|--------|-------------|
| `core` | Framework core: IObject / TLMsg / TLBaseModule / factory / lifecycle / routing / AOP |
| `aiagent` | AI Agent sub-framework (common + providers + skills + memory + webui; see [aiagent/README.md](aiagent/README.md)) |
| `database` | Message-oriented data access: CRUD, transactions, table management (MySQL / PostgreSQL / SQLite) |
| `connectpoolofdb` | DB connection pools: C3P0, HikariCP, SQLite |
| `network` | Networking: Jetty / Tomcat / Netty servers, WebSocket client & server, HTTP client, proxy, JWT auth |
| `cache` | Caching: EhCache, Caffeine, file cache, memory cache |
| `redis` | Redis client wrapped as message actions |
| `servletutils` | Servlet utilities: web channel (upload / SSE / route dispatch), session helpers |
| `execl` | Excel export |
| `appwebmanager` | Web-based management console |
| `crontab` | Cron-scheduled tasks |
| `log` | Logging module (SLF4J bridge) |
| `utils` | General-purpose utilities |
| `tlobject-all` | Aggregate artifact (single dependency for everything) |
| `demo` | Runnable examples (`tlobject` messaging scenarios + `web` webapp) |

---

## AI Agent Sub-framework (aiagent)

An agent framework built on this messaging model: user input → LLM intent analysis → skill/tool execution → result. Streaming & non-streaming, multi-turn chat, three-tier memory (context / short-term / long-term), multi-subagent workflows, MCP tools, evals, HITL approval, session persistence & checkpoint resume, and a web UI (`webui`).

Full documentation — module layout, core classes, message flows, XML config, extension guide, new-feature modules — is in **`aiagent/README.md`** (~1,300 lines, in Chinese, with complete architecture diagrams and usage manual).

---

## Configuration System

Config files are XML, named `{moduleName}_config.xml`, parsed by `TLModuleConfig` (XmlPullParser):

| XML Element | Purpose |
|-------------|---------|
| `<modules>` | Sub-module definitions: `classfile`, `singleton`, parameters |
| `<params>` | Module parameters |
| `<modulesParams>` | Parameter injection into sub-modules (e.g. system prompt / context size for aiContext) |
| `<initMsg>` / `<startMsg>` | Message sequences sent at init / start |
| `<msgTable>` | Routing table: `msgid` → messages (sequential or parallel, with timeouts) |
| `<beforeMsgTable>` / `<afterMsgTable>` | Per-action AOP hooks |
| `<include>` | Import other config files |

Config files live under demo resource trees, e.g. `demo/tlobject/src/main/resources/conf/`:

- `conf/tlobject/` — combined all-module configs for full apps
- `conf/demo/` — per-scenario config sets (aiagent / aitest / base / chatroom / db / jettyserver / redis / service / task / tomcatserver)

**Note**: DB passwords and login accounts in this repo are demo placeholders; DB demos need your own MySQL schemas; HTTPS web demos require your own keystore (none is shipped).

---

## Demos & Self-testing

| Scenario | Where | What |
|----------|-------|------|
| Messaging basics | `demo/.../demo/base/startup.java` | Minimal message / name / factory example |
| Chatroom | `demo/.../demo/chatroom/` | Socket chat server + client |
| Database | `demo/.../demo/db/` | Message-based DAO demo (schemas `tldbdemo1/2`) |
| Redis | `demo/.../demo/redis/` | Redis message-action demo |
| Web | `demo/web/` | Jetty / Tomcat servlet webapp |
| AI Agent auto test | `cn.tianlong.java.demo.aiagent.AiAgentDemoStartup` | 9 Mock scenarios, no key required |
| AI Agent interactive | `cn.tianlong.java.demo.aiagent.AIStart` | Console chat (`/stream` `/thinking` `/sessions` `/test` …) |

---

## Logging

log4j2 + SLF4J. Config lives under `conf/`; set the system property before `main()` runs:

```java
System.setProperty("log4j.configurationFile", configPath + "log4j2.xml");
```

`<configuration status="error">` silences log4j2's own startup noise; exclusive per-level `ThresholdFilter` usage is demonstrated in the demo `log4j2.xml` files.

---

## License

[Apache License 2.0](LICENSE)

Coordinates: `cn.tianlong.tlobject:tlobject:1.0`
