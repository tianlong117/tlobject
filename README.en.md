# TLObject — Unified Object Message Programming Framework

**English** | [中文](README.md)

> **Every object is independent, free, and equal; everything is created by the Creator.**
> In TLObject's world, the "Creator" is the unified object factory — it creates every object, gives it a name, and grants it life.
> Objects never "call" each other; they only communicate through **messages**.

Author: tianlong · Blog (Chinese): https://blog.csdn.net/tianlong117

---

## Table of Contents

- [Philosophy](#philosophy)
- [Features & Advantages](#features--advantages)
- [Quick Start](#quick-start)
- [Configuration System](#configuration-system)
- [Modules & Sub-frameworks](#modules--sub-frameworks)
- [Demos & Self-testing](#demos--self-testing)
- [Logging](#logging)
- [License](#license)

---

## Philosophy

TLObject's architecture is inspired by how things work in nature and human society:

**I. Every object is independent, free, and equal**

In nature, everything exists on its own. Beings do not possess or dominate one another — they *communicate*: people talk, cells exchange signals, states send envoys. No one rules over anyone; each individual responds to the world by its own rules.

Traditional object-oriented programming is the opposite: one object *holds a reference* to another and calls its methods directly (`a.func()`). The caller sits above the callee, and the two are welded together by the reference — change one side and the other may break.

Message-object programming abolishes that relationship. Objects **do not hold references and do not call each other**; the only interaction is sending a **message**: a message carries intent (`action`) and content (parameters). Whether and how to respond is entirely the receiver's own decision. As a result:

- Every object is **independent** — it neither knows nor cares who sent it messages
- Every object is **free** — it can be replaced, reused, or unit-tested at any time, and its peers never notice
- Every object is **equal** — there is no hierarchy of caller and callee, only senders and receivers

**II. Everything is created by the Creator**

Nothing in this world pops into existence by itself, nor is it casually `new`-ed by some "user". All things are created by a unified Creator. In software, that role is played by **TLObjectFactory**:

- The factory decides from **configuration** (not code) which objects exist, which implementation classes they use, and whether they are singletons
- The factory gives every object a **name** — its address in this world
- The factory owns the full lifecycle: create → inject parameters → initialize → start → destroy

Objects need not know who uses them, and users need not know how objects are made. Both sides know only **names** and **messages**:

```java
putMsg("worker", msg);        // no `new` — the factory already created, named and started it
```

**Three practical principles follow:**

| Principle | Meaning | Code |
|-----------|---------|------|
| Interaction by messages | Objects cooperate only via messages, never direct method calls | `TLMsg msg; putMsg(A, msg);` |
| Interaction by names | Refer to objects by name; hold no instance references | `putMsg("worker", msg);` |
| Creation by the factory | Creation and lifecycle belong to the unified factory | declared entirely in `{xxx}_config.xml` |

> TLObject turns this philosophy into a runtime where the "object world" is configurable, observable, and hot-swappable.

---

## Features & Advantages

### Message programming vs. traditional calling

| Dimension | Traditional OOP (method call) | TLObject (message-object) |
|-----------|------------------------------|---------------------------|
| Interaction | hold reference, call `a.func()` | send message `putMsg("a", msg)` |
| Coupling | tight — changing implementation breaks callers | zero reference coupling — swap implementations via config only |
| Creation | callers `new` everywhere | unified factory, invisible to users |
| Dependency direction | high-level depends on concrete low-level classes | only names + messages; no compile-time dependency direction |
| Swapping implementations | edit code, recompile | change one line of XML (hot reload supported) |
| Cross-cutting logic | needs proxy / AOP frameworks | native `beforeMsgTable` / `afterMsgTable` hooks |
| Async & concurrency | hand-written threads | built-in: async messages, thread pools, queues, pub-sub bus |

### Framework-level capabilities

- **Extreme loose coupling**: modules have no compile-time dependencies. Swap the database, the cache implementation, or a third-party service — caller code stays untouched
- **Declarative assembly**: the whole object world is described in XML — module definitions, parameters, routing tables, interceptors, startup sequences. Readable, auditable, hot-reloadable
- **Rich message semantics**: one `TLMsg` natively supports sync/async/delayed send, chained continuation (`nextMsg`), routing tables (`msgTable`), publish-subscribe (`TLMsgBus`), broadcast and blocking queues — asynchrony and concurrency are first-class citizens, not afterthoughts
- **Unified lifecycle**: `init → runInitMsg → runStartMsg → run → destroy`, managed by the factory; modules just do business
- **Complete ecosystem**: database DAO, Jetty/Tomcat/Netty servers, WebSocket, HTTP, cache, Redis, cron jobs, logging, Excel export — 15 top-level modules and 40+ ready-to-use components (see [Modules & Sub-frameworks](#modules--sub-frameworks))
- **AI Agent sub-framework**: a full agent framework built on this messaging model — multi-turn chat, tool calling, memory, multi-subagent orchestration, MCP, HITL approval (see [aiagent/README.md](aiagent/README.md))
- **Engineering-friendly**: built-in leveled logging, runtime monitoring, parameter validation, session data, exception handling; config-as-documentation — newcomers understand a system by reading its XML

---

## Quick Start

**Prerequisites**: JDK 17+, Maven 3.6+ (Windows / macOS / Linux)

```bash
# 1. Build and install all modules (~1 minute)
mvn clean install -DskipTests
```

### Experience 1 — AI Agent automated self-test (zero configuration)

Mock-provider driven: **no API key, no network, no database** required:

```bash
mvn -pl demo/tlobject dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -Dfile.encoding=UTF-8 \
     -cp "demo/tlobject/target/classes;$(cat demo/tlobject/target/cp.txt)" \
     cn.tianlong.java.demo.aiagent.AiAgentDemoStartup
# The [TEST] summary lines in the log are the results of the 12 scenarios (all pass = framework OK)
```

### Experience 2 — the minimal messaging example

```bash
java -Dfile.encoding=UTF-8 \
     -cp "demo/tlobject/target/classes;$(cat demo/tlobject/target/cp.txt)" \
     cn.tianlong.java.demo.base.startup
```

### Experience 3 — see how the "object world" is configured

Everything starts with a config file. A minimal world looks like this (illustrative; full examples under `demo/tlobject/src/main/resources/conf/demo/`):

```xml
<!-- moduleFactory_config.xml: declare to the factory which objects to create -->
<moduleConfig>
    <modules>
        <module name="worker"
                classfile="cn.tianlong.demo.worker.Worker"
                singleton="true"/>
    </modules>
</moduleConfig>
```

Then talk to that object from code — **no `new`, no reference, just a message**:

```java
TLMsg msg = new TLMsg();
msg.setAction("work");                     // message: intent
msg.setParam("task", "carry the goods");   // message: content
TLMsg result = putMsg("worker", msg);      // send to the object named "worker" (sync)
String answer = result.getParam("result"); // the object replies by its own rules
```

Application entry point: extend `TLAppStartUp`, point it at a config directory and start (programmatic form of the CLI `-d configDir -m factoryConfig -f appConfig -n appName`):

```java
public class startup extends TLAppStartUp {
    public static void main(String[] args) {
        HashMap<String, String> argsMap = new HashMap<>();
        argsMap.put("appName", "demo0");
        argsMap.put("configPath", CLASSPATH + "/conf/demo/base/");
        argsMap.put("factoryConfigFile", "moduleFactory_config.xml");
        argsMap.put("configFile", "demoappstart.xml");
        TLAppStartUp.main0(argsMap);
        TLAppStartUp.shutdown();           // graceful shutdown: the factory destroys everything
    }
}
```

> During development, the easiest path is an IDE (IntelliJ IDEA / Eclipse): open the root `pom.xml` and run any demo `main` under `demo/tlobject`.

### Configuring an API Key (chat demos only)

LLM provider configs in this repository use the placeholder `apiKey="sk-your-api-key"`. Before running chat demos, replace every occurrence under `demo/tlobject/src/main/resources/conf/demo/aiagent/` with your own key (providers and model names are chosen in the same configs). **Never commit a real key to a public repository.**

---

## Configuration System

Config files are XML named `{moduleName}_config.xml`, parsed by `TLModuleConfig`:

| XML Element | Purpose |
|-------------|---------|
| `<modules>` | Define objects: `name`, `classfile`, `singleton`, `configfile` |
| `<params>` | Factory / module parameters |
| `<modulesParams>` | Inject parameters per module (e.g. `<module name="aiContext" maxContextMessages="50"/>`) |
| `<paramsModules>` | Assign modules per parameter (e.g. `<param name="loglevel" value="debug" modules="a;b"/>`) |
| `<initMsg>` / `<startMsg>` | Message sequences the factory sends automatically at init / start (objects start "talking" the moment they are born) |
| `<msgTable>` | Routing table: `msgid` → messages (sequential or parallel, with timeout) |
| `<beforeMsgTable>` / `<afterMsgTable>` | Per-action hooks (the home of memory injection, approval, auditing…) |
| `<include>` | Import other configs (`file="CLASSPATH/conf/..."`, unit-level merging supported) |

Config layout (e.g. under `demo/tlobject/src/main/resources/conf/`):

- `conf/tlobject/` — shared all-module configs for full applications
- `conf/demo/` — self-contained per-scenario config sets (aiagent / aitest / base / db / jettyserver / redis / service / task / tomcatserver), each its own little "world"

**Note**: DB passwords and login accounts are demo placeholders; DB demos need your own MySQL schemas (SQL scripts under `demo/`); HTTPS web demos need your own certificate.

---

## Modules & Sub-frameworks

### Top-level module map

| Module | Description |
|--------|-------------|
| `core` | **The heart of the messaging framework**: IObject / TLMsg / TLBaseModule / factory / lifecycle / routing / AOP hooks / thread pool / message bus / broadcast / queue / monitoring / session data / validation / exception handling / app launcher |
| `aiagent` | **AI Agent sub-framework**: common + providers (OpenAI / DeepSeek / Claude) + skills + memory + webui (see below) |
| `database` | Message-oriented data access: CRUD / transactions / table management / DAO (MySQL / PostgreSQL / SQLite) |
| `connectpoolofdb` | DB connection pools: C3P0, HikariCP, SQLite |
| `network` | Networking: Jetty / Tomcat / Netty servers, WebSocket client & server, HTTP client, proxy, JWT auth |
| `cache` | Caching: EhCache, Caffeine, file cache, memory cache |
| `redis` | Redis client wrapped as message actions |
| `servletutils` | Servlet utilities: web channel (upload / SSE / route dispatch) |
| `execl` | Excel export |
| `appwebmanager` | Web-based management console |
| `crontab` | Cron-scheduled tasks |
| `log` | Logging module (log4j2 + SLF4J bridge) |
| `utils` | General-purpose utilities |
| `tlobject-all` | Aggregate artifact — one dependency brings every module |
| `demo` | Runnable examples (`tlobject` messaging scenarios + `web` webapp) |

### AI Agent sub-framework (`aiagent/`)

A complete agent framework built on the message-object model: user input → LLM intent analysis → skill/tool execution → result.

- Streaming & non-streaming channels, multi-turn chat, model routing with intent caching
- Tool system: built-in skills (HTTP / file / code execution / browser / desktop GUI) + custom skills + MCP marketplace + message tools (msgTool)
- Three-tier memory: conversation context, short-term (TTL), long-term (file/DB, hierarchical summaries, optional embedding recall)
- Multi-agent orchestration: sub-agent delegation, agent groups, workflow DAGs, error-self-healing supervisor
- Engineering features: HITL approval gate, Evals harness, session persistence & checkpoint resume, token statistics, web UI (`webui`), `/test` mock regression
- Full documentation (~1,300 lines, in Chinese, with architecture diagrams / core classes / extension guide): **`aiagent/README.md`**

---

## Demos & Self-testing

| Scenario | Entry point | What |
|----------|-------------|------|
| Minimal messaging | `cn.tianlong.java.demo.base.startup` | messages / names / factory creation |
| Database | `demo/.../demo/db/` | message-based DAO (schemas `tldbdemo1/2.sql`) |
| Redis | `demo/.../demo/redis/` | Redis message actions |
| Web | `demo/web/` | Jetty / Tomcat webapp |
| AI Agent auto test | `cn.tianlong.java.demo.aiagent.AiAgentDemoStartup` | 12 Mock scenarios, zero config |
| AI Agent interactive | `cn.tianlong.java.demo.aiagent.AIStart` | console chat (`/stream` `/thinking` `/sessions` `/test` …) |

---

## Logging

log4j2 + SLF4J. Config lives under `conf/`; set the system property before `main()` runs:

```java
System.setProperty("log4j.configurationFile", configPath + "log4j2.xml");
```

`<configuration status="error">` silences log4j2's own startup noise; the per-level exclusive `ThresholdFilter` pattern is shown in the demo `log4j2.xml` files.

---

## License

[Apache License 2.0](LICENSE) · Coordinates: `cn.tianlong.tlobject:tlobject:1.0`
