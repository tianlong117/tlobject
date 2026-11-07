# 控制中心（TLControlCenter）一期实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增工厂级控制中心，收口启动编排、模块注册门面、配置重载白名单与审计；聊天链路保持直连不变。

**Architecture:** `TLAppStartUp` 保持进程/应用编排；每个工厂新增一个单例 `TLControlCenter`。`factoryBoot` 只创建基础设施（`logThreadPool`/`threadPool`/`database`）与控制中心，应用模块由控制中心的 `<startMsg>` 创建。所有注册调用通过 `registryTarget()` 解析到控制中心（未配置时回落旧 `moduleRegistry`）。控制中心的内部注册表是私有子模块，外部只通过控制中心访问。

**Tech Stack:** Java 17、Maven 多模块、XML 配置、仓库惯例的 runnable main 自测（无 JUnit）。

**Spec:** `docs/superpowers/specs/2026-11-06-control-center-design.md`

---

## 前置条件

- [ ] 确认基线干净并建分支

```bash
git status --short
git checkout -b feature/control-center
```

- [ ] 基线构建与回归（失败则停止，不要带着红基线往下走）

```bash
/d/maven/bin/mvn -q clean install -DskipTests
cmd //c run_aitest.bat
```

预期：aitest 输出 `[TEST] 汇总: 通过=36, 失败=0, 跳过=0`。

控制台基线：

```bash
printf '/test\n/exit\n' | "C:/Program Files/Java/jdk-17.0.1/bin/java.exe" @aistart_args.txt 2>&1 | grep "\[TEST\] 汇总"
```

预期：`[TEST] 汇总: 通过=22, 失败=0`。

---

## 文件结构

**新建：**
- `core/src/main/java/cn/tianlong/tlobject/modules/TLControlCenter.java` — 控制中心
- `core/src/test/java/cn/tianlong/tlobject/base/RegistryTargetSelfTest.java` — 注册目标解析自测
- `core/src/test/java/cn/tianlong/tlobject/modules/TLControlCenterSelfTest.java` — 控制中心自测
- `demo/tlobject/src/main/resources/conf/demo/aiagent/controlCenter_config.xml` — chat 启动序列
- `demo/tlobject/src/main/resources/conf/demo/aiagent/controlCenter_web_config.xml` — web 启动序列
- `demo/tlobject/src/main/resources/conf/demo/aitest/controlCenter_aitest_config.xml` — aitest 启动序列

**修改：**
- `core/src/main/java/cn/tianlong/tlobject/base/TLParamString.java` — 新增参数键
- `core/src/main/java/cn/tianlong/tlobject/base/TLBaseModule.java` — 注册目标解析
- `TLAiAgent.java`、`TLToolManager.java`、`TLAgentGroup.java`、`TLAgentWorkflow.java`、`TLAgentService.java`、`TLEvalsModule.java`、`TLEvalCaseGenerator.java` — 注册调用迁移
- `aiagent/common/src/test/java/cn/tianlong/tlobject/aiagent/TLAgentServiceReloadConfigSelfTest.java` — 委托用例
- `conf/demo/aiagent/moduleFactory_chat_config.xml`、`moduleFactory_chat_web_config.xml`、`conf/demo/aitest/moduleFactory_config.xml` — 启动序列与公共参数
- `CLAUDE.md` — 架构说明

---

## Task 1: 注册目标解析（兼容回退）

**Files:**
- Modify: `core/src/main/java/cn/tianlong/tlobject/base/TLParamString.java`
- Modify: `core/src/main/java/cn/tianlong/tlobject/base/TLBaseModule.java`
- Test: `core/src/test/java/cn/tianlong/tlobject/base/RegistryTargetSelfTest.java`

- [ ] **Step 1: 写失败的自测**

创建 `core/src/test/java/cn/tianlong/tlobject/base/RegistryTargetSelfTest.java`：

```java
package cn.tianlong.tlobject.base;

import cn.tianlong.tlobject.modules.LogLevel;

/**
 * 注册目标解析自测（runnable main，无 JUnit——仓库惯例）。
 * 覆盖：未配置、配置且存在、配置但模块缺失三种情况。
 */
public class RegistryTargetSelfTest {
    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        TestModule noParam = new TestModule();
        check("未配置 controlCenterName 回退旧注册表",
                "moduleRegistry".equals(noParam.registryTarget()));

        TestModule configured = new TestModule();
        configured.params.put("controlCenterName", "controlCenter");
        configured.exists = true;
        check("配置且存在时指向控制中心",
                "controlCenter".equals(configured.registryTarget()));
        check("配置且存在时 controlCenterTarget 非空",
                "controlCenter".equals(configured.controlCenterTarget()));

        TestModule missing = new TestModule();
        missing.params.put("controlCenterName", "controlCenterX");
        missing.exists = false;
        check("配置但不存在时回退旧注册表",
                "moduleRegistry".equals(missing.registryTarget()));
        check("配置但不存在时 controlCenterTarget 为 null",
                missing.controlCenterTarget() == null);

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    static class TestModule extends TLBaseModule {
        boolean exists = false;

        TestModule() {
            super("registryTargetTest");
            params = new java.util.HashMap<>();
        }

        @Override protected TLBaseModule init() { return this; }
        @Override protected boolean moduleExists(String moduleName) { return exists; }
        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) { return null; }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
    }

    private static void check(String desc, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + desc); }
        else { failed++; System.out.println("[FAIL] " + desc); }
    }
}
```

- [ ] **Step 2: 运行，确认失败**

```bash
/d/maven/bin/mvn -q -pl core test-compile
```

预期：编译失败，`cannot find symbol: method registryTarget()` / `controlCenterTarget()`。

- [ ] **Step 3: 加常量**

在 `core/src/main/java/cn/tianlong/tlobject/base/TLParamString.java` 的 `DEFAULTMODULEREGISTRY` 之后加：

```java
    /** 工厂级控制中心模块名参数键（由工厂 commonParams 注入；未配置回退旧注册表） */
    final String CONTROLCENTER_NAME = "controlCenterName";
```

- [ ] **Step 4: 在 TLBaseModule 中加解析方法**

在 `core/src/main/java/cn/tianlong/tlobject/base/TLBaseModule.java` 的 `getModuleInFactory` 方法之后加：

```java
    /**
     * 注册目标：配置了 controlCenterName 且模块存在时走控制中心，否则回退旧注册表。
     * 三种情况都安全：接入控制中心、未配置（旧行为）、配置了名字但模块缺失。
     */
    protected String registryTarget() {
        String controlCenter = controlCenterTarget();
        return controlCenter != null ? controlCenter : DEFAULTMODULEREGISTRY;
    }

    /** 控制中心目标；未配置或未创建返回 null。 */
    protected String controlCenterTarget() {
        if (params == null) return null;
        String controlCenter = params.get(CONTROLCENTER_NAME);
        if (controlCenter == null || controlCenter.trim().isEmpty()) return null;
        controlCenter = controlCenter.trim();
        if (moduleExists(controlCenter)) return controlCenter;
        putLog("controlCenterName=" + controlCenter + " 未在工厂中创建，回退 "
                + DEFAULTMODULEREGISTRY, LogLevel.WARN);
        return null;
    }

    /** 可覆盖，供自测注入；生产路径查工厂已存在实例。 */
    protected boolean moduleExists(String moduleName) {
        return getModuleInFactory(moduleName) != null;
    }
```

- [ ] **Step 5: 运行自测**

```bash
/d/maven/bin/mvn -q -pl core test-compile
/d/maven/bin/mvn -q -pl core dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "core/target/classes;core/target/test-classes;$(cat core/cp.txt)" cn.tianlong.tlobject.base.RegistryTargetSelfTest
```

预期：`结果: 5 通过, 0 失败`。

- [ ] **Step 6: 提交**

```bash
git add core/src/main/java/cn/tianlong/tlobject/base/TLParamString.java \
        core/src/main/java/cn/tianlong/tlobject/base/TLBaseModule.java \
        core/src/test/java/cn/tianlong/tlobject/base/RegistryTargetSelfTest.java
git commit -m "feat(control-center): 注册目标解析与兼容回退"
```

---

## Task 2: TLControlCenter 核心

**Files:**
- Create: `core/src/main/java/cn/tianlong/tlobject/modules/TLControlCenter.java`
- Test: `core/src/test/java/cn/tianlong/tlobject/modules/TLControlCenterSelfTest.java`

- [ ] **Step 1: 写失败的自测**

创建 `core/src/test/java/cn/tianlong/tlobject/modules/TLControlCenterSelfTest.java`：

```java
package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;

import java.util.List;
import java.util.Map;

/**
 * 控制中心自测（runnable main，无 JUnit——仓库惯例）。
 * 覆盖：注册门面、reload 白名单/目标校验/失败透传、审计。
 */
public class TLControlCenterSelfTest {
    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        TestControlCenter cc = new TestControlCenter();
        TestRegistry registry = new TestRegistry("moduleRegistry");
        cc.registryModule = registry;
        cc.reloadableModules = "allowed;cc:allowed;bad";

        FakeModule allowed = new FakeModule("allowed", "allowed.xml",
                new TLMsg().setParam("result", true).setParam("rules", 2).setParam("file", "a.xml"));
        TLMsg reg = cc.checkMsgAction(null, new TLMsg().setAction("register")
                .setParam("key", "allowed").setParam("instance", allowed));
        check("注册返回成功", reg != null && reg.parseBoolean("result", false));

        TLMsg got = cc.checkMsgAction(null, new TLMsg().setAction("get").setParam("key", "allowed"));
        check("按家族名查询实例", got != null && got.getParam("instance") == allowed);

        TLMsg list = cc.checkMsgAction(null, new TLMsg().setAction("list"));
        check("列表返回一项", list != null && list.getParam("result") instanceof List
                && ((List<?>) list.getParam("result")).size() == 1);

        TLMsg r1 = cc.checkMsgAction(null, reloadCmd("allowed", "u1"));
        check("白名单内重载成功", r1.parseBoolean("result", false));
        check("重载结果带 rules", String.valueOf(r1.getParam("data")).contains("rules=2"));

        FakeModule denied = new FakeModule("denied", "denied.xml",
                new TLMsg().setParam("result", true));
        cc.checkMsgAction(null, new TLMsg().setAction("register")
                .setParam("key", "denied").setParam("instance", denied));
        TLMsg r2 = cc.checkMsgAction(null, reloadCmd("denied", "u1"));
        check("白名单外拒绝", !r2.parseBoolean("result", true));
        check("拒绝原因含白名单", r2.getStringParam("error", "").contains("白名单"));

        cc.reloadableModules = "*";
        TLMsg r2b = cc.checkMsgAction(null, reloadCmd("denied", "u1"));
        check("通配符放行", r2b.parseBoolean("result", false));
        cc.reloadableModules = "allowed;cc:allowed;bad";

        TLMsg r3 = cc.checkMsgAction(null, reloadCmd("missing", "u1"));
        check("目标不存在返回失败", !r3.parseBoolean("result", true));
        check("不存在原因", r3.getStringParam("error", "").contains("不存在"));

        FakeModule noConfig = new FakeModule("noConfig", null, new TLMsg().setParam("result", true));
        cc.checkMsgAction(null, new TLMsg().setAction("register")
                .setParam("key", "noConfig").setParam("instance", noConfig));
        TLMsg r4 = cc.checkMsgAction(null, reloadCmd("noConfig", "u1"));
        check("无配置文件拒绝", !r4.parseBoolean("result", true));
        check("无配置文件原因", r4.getStringParam("error", "").contains("配置文件"));

        FakeModule bad = new FakeModule("bad", "bad.xml",
                new TLMsg().setParam("result", false).setParam("error", "boom"));
        cc.checkMsgAction(null, new TLMsg().setAction("register")
                .setParam("key", "bad").setParam("instance", bad));
        TLMsg r5 = cc.checkMsgAction(null, reloadCmd("bad", "u1"));
        check("模块失败透传", !r5.parseBoolean("result", true)
                && r5.getStringParam("error", "").contains("boom"));

        TLMsg audit = cc.checkMsgAction(null, new TLMsg().setAction("auditList").setParam("limit", 20));
        List<?> records = (List<?>) audit.getParam("result");
        check("审计有记录", records != null && records.size() >= 5);
        boolean deniedRecorded = false;
        for (Object o : records) {
            Map<?, ?> m = (Map<?, ?>) o;
            if ("denied".equals(m.get("target")) && Boolean.FALSE.equals(m.get("allowed")))
                deniedRecorded = true;
        }
        check("拒绝操作被审计", deniedRecorded);

        TLMsg unreg = cc.checkMsgAction(null, new TLMsg().setAction("unregister")
                .setParam("key", "allowed"));
        check("注销返回成功", unreg != null && unreg.parseBoolean("result", false));
        TLMsg got2 = cc.checkMsgAction(null, new TLMsg().setAction("get").setParam("key", "allowed"));
        check("注销后查询为空", got2 == null || got2.getParam("instance") == null);

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static TLMsg reloadCmd(String name, String userId) {
        return new TLMsg().setAction("reloadModuleConfig")
                .setParam("name", name).setParam("userId", userId);
    }

    static class TestControlCenter extends TLControlCenter {
        TestControlCenter() { super("controlCenterSelfTest"); }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
    }

    static class TestRegistry extends TLModuleRegistry {
        TestRegistry(String name) { super(name); }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
    }

    static class FakeModule extends TLBaseModule {
        private final TLMsg reloadResponse;

        FakeModule(String name, String configFile, TLMsg reloadResponse) {
            super(name, configFile);
            this.reloadResponse = reloadResponse;
        }

        @Override protected TLMsg reloadConfig(Object fromWho, TLMsg msg) { return reloadResponse; }
        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) { return null; }
        @Override protected TLBaseModule init() { return this; }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
    }

    private static void check(String desc, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + desc); }
        else { failed++; System.out.println("[FAIL] " + desc); }
    }
}
```

- [ ] **Step 2: 运行，确认失败**

```bash
/d/maven/bin/mvn -q -pl core test-compile
```

预期：编译失败，`cannot find symbol: class TLControlCenter`。

- [ ] **Step 3: 实现控制中心**

创建 `core/src/main/java/cn/tianlong/tlobject/modules/TLControlCenter.java`：

```java
package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工厂级控制中心：启动编排、模块注册门面、配置重载治理、审计。
 *
 * 启动序列由本模块配置文件的 &lt;startMsg&gt; 承载，向 moduleFactory 发 getModule
 * 创建应用模块；工厂只负责创建，控制中心负责启动顺序。
 */
public class TLControlCenter extends TLBaseModule {

    public static final String PARAM_RELOADABLE = "reloadableModules";
    public static final String PARAM_AUDIT_LIMIT = "auditLimit";
    public static final int DEFAULT_AUDIT_LIMIT = 200;

    /** 内部私有注册表（familyName = 控制中心:moduleRegistry） */
    protected Object registryModule;
    /** 可重载配置的模块白名单，分号分隔；* = 全部，空 = 全部禁止 */
    protected String reloadableModules;
    protected int auditLimit = DEFAULT_AUDIT_LIMIT;
    protected final Deque<Map<String, Object>> auditRecords = new ArrayDeque<>();

    public TLControlCenter() {
        super();
    }

    public TLControlCenter(String name) {
        super(name);
    }

    public TLControlCenter(String name, TLObjectFactory moduleFactory) {
        super(name, moduleFactory);
    }

    @Override
    protected TLBaseModule init() {
        if (params != null) {
            reloadableModules = params.get(PARAM_RELOADABLE);
            String limit = params.get(PARAM_AUDIT_LIMIT);
            if (limit != null && !limit.trim().isEmpty()) {
                try {
                    auditLimit = Integer.parseInt(limit.trim());
                } catch (NumberFormatException e) {
                    putLog("auditLimit 配置非法，使用默认值 " + DEFAULT_AUDIT_LIMIT + ": " + limit,
                            LogLevel.WARN);
                }
            }
        }
        registryModule = getMyModule(DEFAULTMODULEREGISTRY);
        if (registryModule == null) {
            putLog("内部注册表创建失败: " + DEFAULTMODULEREGISTRY, LogLevel.ERROR);
            return null;
        }
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case REGISTRY_REGISTER:
            case REGISTRY_UNREGISTER:
            case REGISTRY_LIST:
            case REGISTRY_GET:
                return forwardToRegistry(msg);
            case "reloadModuleConfig":
                return reloadModuleConfig(fromWho, msg);
            case "auditList":
                return auditList(msg);
            default:
                return null;
        }
    }

    /** 注册门面：构造新消息转发，避免原消息的 destination 造成二次路由。 */
    protected TLMsg forwardToRegistry(TLMsg msg) {
        if (!(registryModule instanceof IObject)) {
            return createMsg().setParam(RESULT, false).setParam(ERRORSTR, "内部注册表不可用");
        }
        TLMsg forward = createMsg().setAction(msg.getAction());
        if (msg.containsParam(REGISTRY_P_KEY))
            forward.setParam(REGISTRY_P_KEY, msg.getParam(REGISTRY_P_KEY));
        if (msg.containsParam(INSTANCE))
            forward.setParam(INSTANCE, msg.getParam(INSTANCE));
        if (msg.containsParam(REGISTRY_P_OWNERNAME))
            forward.setParam(REGISTRY_P_OWNERNAME, msg.getParam(REGISTRY_P_OWNERNAME));
        return putMsg((IObject) registryModule, forward);
    }

    /**
     * 重载模块配置：目标存在 + 有配置文件 + 命中白名单才执行。
     * 返回 RESULT / error / message / data（data 含 module、rules、file）。
     */
    protected TLMsg reloadModuleConfig(Object fromWho, TLMsg msg) {
        String name = msg.getStringParam("name", msg.getStringParam("module", ""));
        String userId = msg.getStringParam("userId", "");
        if (name == null || name.isEmpty()) {
            return reloadFail(name, userId, "缺少参数: name");
        }
        TLBaseModule target = resolveReloadTarget(name);
        if (target == null) {
            return reloadFail(name, userId, "模块未运行或不存在: " + name
                    + "（未实例化的模块会在首次创建时读取新配置）");
        }
        if (target.getConfigFile() == null) {
            return reloadFail(name, userId, "模块未加载配置文件: " + name
                    + "（无配置文件可重载；请检查模块声明/配置路径）");
        }
        if (!isReloadAllowed(name, target)) {
            return reloadFail(name, userId, "模块未在可重载白名单中: " + displayName(name, target));
        }
        TLMsg result;
        try {
            result = putMsg(target, createMsg().setAction("reloadConfig"));
        } catch (Throwable t) {
            putLog("reloadModuleConfig: " + name + " 异常: " + t, LogLevel.ERROR);
            return reloadFail(name, userId, "模块配置重载异常: " + t);
        }
        if (result == null) {
            String message = "已发送重载请求（模块未返回结果）";
            audit(userId, "reloadModuleConfig", name, true, message);
            return createMsg().setParam(RESULT, true)
                    .setParam("message", message)
                    .setParam("data", dataOf(name, null));
        }
        if (!result.parseBoolean(RESULT, true)) {
            Object errObj = result.getParam(ERRORSTR);
            String err = errObj != null ? String.valueOf(errObj) : "未知错误";
            if (err.isEmpty()) err = "未知错误";
            return reloadFail(name, userId, "模块配置重载失败: " + err);
        }
        String message = "配置已重载: " + name;
        if (result.containsParam("rules"))
            message += "（rules=" + result.getParam("rules") + "）";
        audit(userId, "reloadModuleConfig", name, true, message);
        return createMsg().setParam(RESULT, true)
                .setParam("message", message)
                .setParam("data", dataOf(name, result));
    }

    /** 解析重载目标：registry 精确家族名 → 工厂已存在实例；不隐式创建。 */
    protected TLBaseModule resolveReloadTarget(String name) {
        TLBaseModule registered = findRegistered(name);
        if (registered != null) return registered;
        Object existing = getModuleInFactory(name);
        return existing instanceof TLBaseModule ? (TLBaseModule) existing : null;
    }

    protected TLBaseModule findRegistered(String familyName) {
        if (registryModule == null || familyName == null || familyName.isEmpty()) return null;
        TLMsg result = putMsg((IObject) registryModule,
                createMsg().setAction(REGISTRY_GET).setParam(REGISTRY_P_KEY, familyName));
        if (result == null) return null;
        Object instance = result.getParam(INSTANCE);
        return instance instanceof TLBaseModule ? (TLBaseModule) instance : null;
    }

    /** 白名单匹配：请求名 / familyName / name 任一命中；* 全放行；空全拒绝。 */
    protected boolean isReloadAllowed(String requestedName, TLBaseModule target) {
        String list = reloadableModules;
        if (list == null || list.trim().isEmpty()) return false;
        if ("*".equals(list.trim())) return true;
        for (String item : list.split(";")) {
            String value = item.trim();
            if (value.isEmpty()) continue;
            if (value.equals(requestedName)) return true;
            if (value.equals(target.getFamilyName())) return true;
            if (value.equals(target.getName())) return true;
        }
        return false;
    }

    protected String displayName(String requestedName, TLBaseModule target) {
        if (target != null && target.getFamilyName() != null && !target.getFamilyName().isEmpty())
            return target.getFamilyName() + "（请求名: " + requestedName + "）";
        return requestedName;
    }

    protected TLMsg reloadFail(String target, String userId, String error) {
        audit(userId, "reloadModuleConfig", target, false, error);
        return createMsg().setParam(RESULT, false).setParam(ERRORSTR, error);
    }

    protected Map<String, Object> dataOf(String name, TLMsg result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("module", name);
        if (result != null) {
            if (result.containsParam("rules")) data.put("rules", result.getParam("rules"));
            if (result.containsParam("file")) data.put("file", result.getParam("file"));
        }
        return data;
    }

    protected TLMsg auditList(TLMsg msg) {
        int limit = msg.getIntParam("limit", auditLimit);
        if (limit <= 0) limit = auditLimit;
        List<Map<String, Object>> result = new ArrayList<>();
        synchronized (auditRecords) {
            Iterator<Map<String, Object>> it = auditRecords.descendingIterator();
            while (it.hasNext() && result.size() < limit) result.add(it.next());
        }
        return createMsg().setParam(RESULT, result);
    }

    /** 审计：结构化日志 + 有界内存环形记录。 */
    protected void audit(String userId, String action, String target, boolean allowed, String result) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("time", System.currentTimeMillis());
        record.put("userId", userId == null ? "" : userId);
        record.put("action", action);
        record.put("target", target == null ? "" : target);
        record.put("allowed", allowed);
        record.put("result", result == null ? "" : result);
        synchronized (auditRecords) {
            auditRecords.addLast(record);
            while (auditRecords.size() > auditLimit) auditRecords.removeFirst();
        }
        putLog("controlCenter " + action + " user=" + userId + " target=" + target
                + " allowed=" + allowed + " result=" + result, LogLevel.INFO, "audit");
    }
}
```

- [ ] **Step 4: 运行自测**

```bash
/d/maven/bin/mvn -q -pl core test-compile
/d/maven/bin/mvn -q -pl core dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "core/target/classes;core/target/test-classes;$(cat core/cp.txt)" cn.tianlong.tlobject.modules.TLControlCenterSelfTest
```

预期：`结果: 17 通过, 0 失败`（若断言数与预期不同，以 0 失败为准）。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/tianlong/tlobject/modules/TLControlCenter.java \
        core/src/test/java/cn/tianlong/tlobject/modules/TLControlCenterSelfTest.java
git commit -m "feat(control-center): 新增控制中心模块与注册/reload 门面"
```

---

## Task 3: 注册调用点迁移（行为等价）

统一把 `DEFAULTMODULEREGISTRY` 换成 `registryTarget()`。此时所有应用配置还没设置 `controlCenterName`，`registryTarget()` 返回旧注册表——**运行时行为不变**，可独立验证。

**Files:**
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAiAgent.java`
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLToolManager.java`
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAgentGroup.java`
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/workflow/TLAgentWorkflow.java`
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAgentService.java`
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/evals/TLEvalsModule.java`
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/evals/TLEvalCaseGenerator.java`

- [ ] **Step 1: 执行替换**

逐处把 `putMsg(DEFAULTMODULEREGISTRY, ...)` 的第一个参数改为 `registryTarget()`：

| 文件 | 位置 |
|---|---|
| `TLAiAgent.java` | `registerToRegistry` 内 `putMsg(DEFAULTMODULEREGISTRY, msg)` |
| `TLToolManager.java` | 工具注册；卸载（两处，含 `createMsg()` 链式调用） |
| `TLAgentGroup.java` | 成员注册；注销 |
| `TLAgentWorkflow.java` | 节点模块注册 |
| `TLAgentService.java` | `findAgentInstance`、agents 列表、tasks 定位（两处）、skills 列表、param 查询、mcp 列表 |
| `TLEvalsModule.java` | 模块列表、`registryGet` |
| `TLEvalCaseGenerator.java` | 按 key 查找、列表 |

示例（`TLToolManager.java:333`）：

```java
// 前
putMsg(DEFAULTMODULEREGISTRY, msg);
// 后
putMsg(registryTarget(), msg);
```

示例（`TLAgentService.java:757`）：

```java
// 前
TLMsg result = putMsg(DEFAULTMODULEREGISTRY, listMsg);
// 后
TLMsg result = putMsg(registryTarget(), listMsg);
```

同时把 `TLAgentService` 的两处错误文案 `"moduleRegistry 未配置"` 改成 `"注册表未配置"`。

- [ ] **Step 2: 编译**

```bash
/d/maven/bin/mvn -q -pl aiagent/common -am install -DskipTests
```

预期：BUILD SUCCESS。

- [ ] **Step 3: 静态验收**

```bash
grep -rn "putMsg(DEFAULTMODULEREGISTRY" --include="*.java" core aiagent | grep -v "/target/"
```

预期：无输出。

- [ ] **Step 4: 回归（行为等价）**

```bash
/d/maven/bin/mvn -q -pl demo/tlobject -am install -DskipTests
printf '/test\n/exit\n' | "C:/Program Files/Java/jdk-17.0.1/bin/java.exe" @aistart_args.txt 2>&1 | grep "\[TEST\] 汇总"
```

预期：`[TEST] 汇总: 通过=22, 失败=0`。

- [ ] **Step 5: 提交**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent
git commit -m "refactor(control-center): 注册调用统一走 registryTarget（暂回退旧注册表）"
```

---

## Task 3A: 工厂代执行启动序列（保 familyName 语义）

**为什么需要这个任务：** 控制中心用自己的 `<startMsg>` 创建应用模块时，`doMsgList` 以控制中心为发送者，`TLObjectFactory.getModule` 会把控制中心当父模块，给应用模块加上 `controlCenter:` 家族名前缀。这样 `aiagent_master` 会以 `controlCenter:aiagent_master` 注册，破坏 `TLAgentService.findAgentInstance("aiagent_master")`、`agentModule + ":schedule_task"`、评测目标解析等依赖裸家族名的路径。

**修法：** 控制中心仍拥有 `<startMsg>` 配置，但执行交给工厂：新增工厂 action `bootstrapModules`，以工厂自身为发送者逐条执行消息列表；控制中心覆盖 `runStartMsg`，把 `startMsgTable` 发给工厂。工厂仍是创建者，家族名保持裸名。

**Files:**
- Modify: `core/src/main/java/cn/tianlong/tlobject/base/TLParamString.java`
- Modify: `core/src/main/java/cn/tianlong/tlobject/base/TLObjectFactory.java`
- Modify: `core/src/main/java/cn/tianlong/tlobject/modules/TLControlCenter.java`
- Test: `core/src/test/java/cn/tianlong/tlobject/base/FactoryBootstrapSelfTest.java`

- [ ] **Step 1: 写失败自测**

创建 `core/src/test/java/cn/tianlong/tlobject/base/FactoryBootstrapSelfTest.java`：

```java
package cn.tianlong.tlobject.base;

import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.modules.TLModuleRegistry;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;

/**
 * 工厂代执行启动序列自测（runnable main，无 JUnit——仓库惯例）。
 * bootstrapModules 以工厂为发送者执行 getModule：
 * 直接创建的模块保持裸 familyName；经其他模块转发的才带父前缀。
 */
public class FactoryBootstrapSelfTest {
    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("factoryBootstrap").toFile();
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<moduleConfig>\n"
                + "  <params><ifLog value=\"false\"/></params>\n"
                + "  <commonParams><ifLog value=\"false\"/></commonParams>\n"
                + "  <modules>\n"
                + "    <module name=\"bootstrapChild\" classfile=\"cn.tianlong.tlobject.modules.TLModuleRegistry\"/>\n"
                + "    <module name=\"relayChild\" classfile=\"cn.tianlong.tlobject.modules.TLModuleRegistry\"/>\n"
                + "    <module name=\"relay\" classfile=\"cn.tianlong.tlobject.base.FactoryBootstrapSelfTest$RelayModule\"/>\n"
                + "  </modules>\n"
                + "</moduleConfig>\n";
        Files.write(new File(dir, "moduleFactory_config.xml").toPath(),
                xml.getBytes(StandardCharsets.UTF_8));

        TLObjectFactory factory = TLObjectFactory.getInstance(dir.getAbsolutePath(),
                "moduleFactory_config.xml");
        factory.startFactory(null, null);
        factory.boot();

        ArrayList<TLMsg> list = new ArrayList<>();
        list.add(new TLMsg().setAction("getModule").setDestination("moduleFactory")
                .setParam("moduleName", "bootstrapChild"));
        list.add(new TLMsg().setAction("getModule").setDestination("moduleFactory")
                .setParam("moduleName", "relay"));
        TLMsg result = factory.getMsg(null, new TLMsg()
                .setAction("bootstrapModules").setParam("msgList", list));
        check("bootstrapModules 返回成功", result != null && result.parseBoolean("result", false));

        TLBaseModule direct = existing(factory, "bootstrapChild");
        check("直接创建模块已实例化", direct != null);
        check("直接创建模块 familyName 无前缀",
                direct != null && "bootstrapChild".equals(direct.getFamilyName()));

        TLBaseModule relay = existing(factory, "relay");
        check("中继模块已实例化", relay != null);
        check("中继模块 familyName 无前缀",
                relay != null && "relay".equals(relay.getFamilyName()));

        TLMsg made = relay == null ? null
                : factory.putMsg(relay, new TLMsg().setAction("makeChild"));
        Object relayChild = made == null ? null : made.getParam("instance");
        check("中继创建子模块", relayChild instanceof TLBaseModule);
        check("中继子模块带父前缀",
                relayChild instanceof TLBaseModule
                        && "relay:relayChild".equals(((TLBaseModule) relayChild).getFamilyName()));

        factory.destroyModule();
        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        System.exit(failed > 0 ? 1 : 0);
    }

    private static TLBaseModule existing(TLObjectFactory factory, String name) {
        TLMsg got = factory.getMsg(null, new TLMsg()
                .setAction("getModuleInFactory").setParam("moduleName", name));
        Object instance = got == null ? null : got.getParam("instance");
        return instance instanceof TLBaseModule ? (TLBaseModule) instance : null;
    }

    public static class RelayModule extends TLBaseModule {
        public RelayModule(String name, TLObjectFactory factory) { super(name, factory); }

        @Override protected TLBaseModule init() { return this; }

        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
            if ("makeChild".equals(msg.getAction())) {
                Object child = getModuleFromFactory("relayChild");
                return createMsg().setParam("instance", child);
            }
            return null;
        }

        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
    }

    private static void check(String desc, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + desc); }
        else { failed++; System.out.println("[FAIL] " + desc); }
    }
}
```

- [ ] **Step 2: 运行，确认失败**

```bash
/d/maven/bin/mvn -q -pl core test-compile
java -cp "core/target/classes;core/target/test-classes;$(cat core/cp.txt)" cn.tianlong.tlobject.base.FactoryBootstrapSelfTest
```

预期：`bootstrapModules` 尚未实现，`工厂代执行` 相关检查失败（或返回 null）。

- [ ] **Step 3: 加工厂 action 常量**

在 `TLParamString` 的 `FACTORY_GETMODULE` 附近加：

```java
    /** 工厂代执行启动序列：以工厂为发送者逐条执行消息列表（保 familyName 语义） */
    final String FACTORY_BOOTSTRAPMODULES = "bootstrapModules";
```

- [ ] **Step 4: 工厂实现 `bootstrapModules`**

在 `TLObjectFactory.checkMsgAction` 的 switch 中加：

```java
            case FACTORY_BOOTSTRAPMODULES:
                returnMsg = bootstrapModules(fromWho, msg);
                break;
```

在 `getRunModulesInFactory` 方法之后加：

```java
    /**
     * 代模块执行启动序列：以工厂自身为发送者逐条执行，保持 familyName 语义。
     * 由控制中心用 <startMsg> 发起；工厂仍是模块创建者。
     */
    @SuppressWarnings("unchecked")
    protected TLMsg bootstrapModules(Object fromWho, TLMsg msg) {
        Object list = msg.getParam("msgList");
        if (!(list instanceof ArrayList)) {
            return createMsg().setParam(RESULT, false)
                    .setParam(ERRORSTR, "msgList 缺失或类型错误");
        }
        doMsgList((ArrayList<TLMsg>) list, null, null);
        return createMsg().setParam(RESULT, true);
    }
```

- [ ] **Step 5: 控制中心覆盖 `runStartMsg`**

在 `TLControlCenter` 中加入：

```java
    /**
     * 启动序列不在本地执行：把 <startMsg> 交给工厂，以工厂为发送者逐条执行。
     * 否则工厂会把控制中心当父模块，给应用模块加 "controlCenter:" 家族名前缀，
     * 破坏 aiagent_master 等注册键。
     */
    @Override
    public void runStartMsg() {
        if (startMsgTable == null || startMsgTable.isEmpty()) return;
        if (moduleFactory == null) {
            super.runStartMsg();
            return;
        }
        putMsg(moduleFactory, createMsg()
                .setAction(FACTORY_BOOTSTRAPMODULES)
                .setParam("msgList", startMsgTable));
    }
```

- [ ] **Step 6: 运行自测**

```bash
/d/maven/bin/mvn -q -pl core test-compile
/d/maven/bin/mvn -q -pl core dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "core/target/classes;core/target/test-classes;$(cat core/cp.txt)" cn.tianlong.tlobject.base.FactoryBootstrapSelfTest
```

预期：`结果: 7 通过, 0 失败`。

- [ ] **Step 7: 提交**

```bash
git add core/src/main/java/cn/tianlong/tlobject/base/TLParamString.java \
        core/src/main/java/cn/tianlong/tlobject/base/TLObjectFactory.java \
        core/src/main/java/cn/tianlong/tlobject/modules/TLControlCenter.java \
        core/src/test/java/cn/tianlong/tlobject/base/FactoryBootstrapSelfTest.java
git commit -m "fix(control-center): 启动序列由工厂代执行，保持 familyName 裸名"
```

---

## Task 4: chat 应用接入控制中心

**Files:**
- Create: `demo/tlobject/src/main/resources/conf/demo/aiagent/controlCenter_config.xml`
- Modify: `demo/tlobject/src/main/resources/conf/demo/aiagent/moduleFactory_chat_config.xml`

- [ ] **Step 1: 创建 chat 版控制中心配置**

创建 `demo/tlobject/src/main/resources/conf/demo/aiagent/controlCenter_config.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<moduleConfig>
    <params>
        <!-- 可重载配置的模块白名单；* = 全部，空 = 全部禁止 -->
        <reloadableModules value="outputValidator"/>
        <auditLimit value="200"/>
    </params>
    <modules>
        <!-- 私有注册表：外部只能通过控制中心访问 -->
        <module name="moduleRegistry"
                classfile="cn.tianlong.tlobject.modules.TLModuleRegistry"/>
    </modules>
    <startMsg>
        <msg action="getModule" destination="moduleFactory" moduleName="dbSessionManager"/>
        <msg action="getModule" destination="moduleFactory" moduleName="myopenAiProvider"/>
        <msg action="getModule" destination="moduleFactory" moduleName="agentService"/>
        <msg action="getModule" destination="moduleFactory" moduleName="chatConsole"/>
        <msg action="getModule" destination="moduleFactory" moduleName="outputGuard"/>
        <msg action="getModule" destination="moduleFactory" moduleName="errorSupervisor"/>
        <msg action="getModule" destination="moduleFactory" moduleName="approvalGate"/>
        <msg action="getModule" destination="moduleFactory" moduleName="appAuthModule"/>
        <msg action="getModule" destination="moduleFactory" moduleName="outputValidator"/>
        <msg action="getModule" destination="moduleFactory" moduleName="toolExecutor"/>
        <msg action="getModule" destination="moduleFactory" moduleName="auth"/>
        <msg action="getModule" destination="moduleFactory" moduleName="demoUserModule"/>
        <msg action="getModule" destination="moduleFactory" moduleName="aiagent_master"/>
        <msg action="getModule" destination="moduleFactory" moduleName="mockProvider"/>
        <msg action="getModule" destination="moduleFactory" moduleName="testEchoSkill"/>
        <msg action="getModule" destination="moduleFactory" moduleName="testSleepSkill"/>
        <msg action="getModule" destination="moduleFactory" moduleName="agentTestModule"/>
    </startMsg>
</moduleConfig>
```

- [ ] **Step 2: 修改 chat 工厂配置**

修改 `demo/tlobject/src/main/resources/conf/demo/aiagent/moduleFactory_chat_config.xml`：

1）`<commonParams>` 内加：

```xml
        <controlCenterName value="controlCenter"/>
```

2）`<modules>` 内加：

```xml
        <!-- 工厂级控制中心：启动编排、注册门面、reload 白名单、审计 -->
        <module name="controlCenter"
                classfile="cn.tianlong.tlobject.modules.TLControlCenter"
                singleton="true"
                necessary="yes"
                configfile="controlCenter_config.xml"/>
```

3）整个 `<factoryBoot>` 替换为：

```xml
    <factoryBoot>
        <msg action="getModule" destination="moduleFactory" moduleName="logThreadPool"/>
        <msg action="getModule" destination="moduleFactory" moduleName="threadPool" usePreReturnMsg="false"/>
        <msg action="getModule" destination="moduleFactory" moduleName="database" usePreReturnMsg="false"/>
        <msg action="getModule" destination="moduleFactory" moduleName="controlCenter"/>
    </factoryBoot>
```

原来的应用模块序列已全部移入 `controlCenter_config.xml` 的 `<startMsg>`。

- [ ] **Step 3: 构建并验证启动顺序**

```bash
/d/maven/bin/mvn -q -pl demo/tlobject -am install -DskipTests
printf '/exit\n' | "C:/Program Files/Java/jdk-17.0.1/bin/java.exe" @aistart_args.txt >/dev/null 2>&1
grep "创建模块" D:/tllog4j/debug.log | tail -25
```

注意：控制台 appender 只收 info 及以上，`创建模块` 是 DEBUG，必须看 `D:/tllog4j/debug.log`。

预期顺序：`logThreadPool → threadPool → database → controlCenter → dbSessionManager → myopenAiProvider → agentService → ... → aiagent_master`。

- [ ] **Step 4: 回归**

```bash
printf '/test\n/exit\n' | "C:/Program Files/Java/jdk-17.0.1/bin/java.exe" @aistart_args.txt 2>&1 | grep "\[TEST\] 汇总"
```

预期：`[TEST] 汇总: 通过=22, 失败=0`（注册已走控制中心私有注册表）。

- [ ] **Step 5: 提交**

```bash
git add demo/tlobject/src/main/resources/conf/demo/aiagent/controlCenter_config.xml \
        demo/tlobject/src/main/resources/conf/demo/aiagent/moduleFactory_chat_config.xml
git commit -m "feat(control-center): chat 应用启动序列迁入控制中心"
```

---

## Task 5: agentService 的 reload 委托

**Files:**
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAgentService.java`
- Test: `aiagent/common/src/test/java/cn/tianlong/tlobject/aiagent/TLAgentServiceReloadConfigSelfTest.java`

- [ ] **Step 1: 扩展自测（先失败）**

在 `TLAgentServiceReloadConfigSelfTest.main` 的最后一个检查之后、打印汇总之前，插入：

```java
        // 控制中心委托：成功
        TestService delegated = new TestService();
        delegated.ccName = "controlCenter";
        delegated.ccResponse = new TLMsg().setParam("result", true)
                .setParam("message", "配置已重载: fromCC")
                .setParam("data", java.util.Map.of("module", "fromCC"));
        TLMsg rc1 = delegated.checkMsgAction(null, cmd("fromCC"));
        check("委托控制中心成功", rc1.parseBoolean("success", false));
        check("委托返回消息", rc1.getStringParam("message", "").contains("fromCC"));

        // 控制中心委托：拒绝
        TestService denied = new TestService();
        denied.ccName = "controlCenter";
        denied.ccResponse = new TLMsg().setParam("result", false)
                .setParam("error", "模块未在可重载白名单中");
        TLMsg rc2 = denied.checkMsgAction(null, cmd("anyModule"));
        check("委托拒绝透传", !rc2.parseBoolean("success", true));
        check("委托拒绝原因", rc2.getStringParam("error", "").contains("白名单"));
```

并给 `TestService` 增加字段与覆写：

```java
    static class TestService extends TLAgentService {
        TLBaseModule target;
        String ccName;
        TLMsg ccResponse;
        TestService() { super("reloadConfigTest"); }
        @Override protected TLBaseModule resolveReloadTarget(String name) { return target; }
        @Override protected String controlCenterTarget() { return ccName; }
        @Override protected TLMsg putToControlCenter(String controlCenter, TLMsg msg) { return ccResponse; }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
    }
```

- [ ] **Step 2: 运行，确认失败**

```bash
/d/maven/bin/mvn -q -pl aiagent/common -am test-compile
```

预期：编译失败（`putToControlCenter` 尚不存在）或运行失败（委托未实现）。

- [ ] **Step 3: 实现委托**

修改 `TLAgentService.doReloadConfig`，在缺少 name 校验之后、`resolveReloadTarget` 之前插入：

```java
        String controlCenter = controlCenterTarget();
        if (controlCenter != null)
            return delegateReloadConfig(controlCenter, name, msg);
```

在 `doReloadConfig` 之后加：

```java
    /** 委托控制中心执行 reload 治理，结果映射回本服务的 success/message/error/data。 */
    private TLMsg delegateReloadConfig(String controlCenter, String name, TLMsg msg) {
        TLMsg delegated = createMsg()
                .setAction("reloadModuleConfig")
                .setParam("name", name)
                .setParam("userId", msg.getStringParam("userId", ""));
        TLMsg result;
        try {
            result = putToControlCenter(controlCenter, delegated);
        } catch (Throwable t) {
            putLog("reloadModuleConfig(controlCenter): " + name + " 异常: " + t, LogLevel.ERROR);
            return fail("模块配置重载异常: " + t);
        }
        if (result == null)
            return fail("控制中心未返回结果: " + name);
        if (!result.parseBoolean(RESULT, true)) {
            Object errObj = result.getParam("error");
            String err = errObj != null ? String.valueOf(errObj) : "未知错误";
            if (err.isEmpty()) err = "未知错误";
            return fail(err);
        }
        String message = result.getStringParam("message", "配置已重载: " + name);
        return ok(message, result.getParam("data"));
    }

    /** 可覆盖：自测注入控制中心响应。 */
    protected TLMsg putToControlCenter(String controlCenter, TLMsg msg) {
        return putMsg(controlCenter, msg);
    }
```

- [ ] **Step 4: 运行自测**

```bash
/d/maven/bin/mvn -q -pl aiagent/common -am test-compile
/d/maven/bin/mvn -q -pl aiagent/common -am dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "aiagent/common/target/classes;aiagent/common/target/test-classes;core/target/classes;$(cat aiagent/common/cp.txt)" cn.tianlong.tlobject.aiagent.TLAgentServiceReloadConfigSelfTest
```

预期：全部 PASS，0 失败。

- [ ] **Step 5: 控制台验证白名单**

```bash
/d/maven/bin/mvn -q -pl demo/tlobject -am install -DskipTests
printf '/reloadconfig outputValidator\n/reloadconfig aiagent_master\n/exit\n' | "C:/Program Files/Java/jdk-17.0.1/bin/java.exe" @aistart_args.txt 2>&1 | grep -E "配置已重载|白名单"
```

预期：

- `outputValidator` → `配置已重载: outputValidator`
- `aiagent_master` → `模块未在可重载白名单中`

- [ ] **Step 6: 提交**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAgentService.java \
        aiagent/common/src/test/java/cn/tianlong/tlobject/aiagent/TLAgentServiceReloadConfigSelfTest.java
git commit -m "feat(control-center): reloadModuleConfig 委托控制中心治理"
```

---

## Task 6: chat_web 应用接入控制中心

**Files:**
- Create: `demo/tlobject/src/main/resources/conf/demo/aiagent/controlCenter_web_config.xml`
- Modify: `demo/tlobject/src/main/resources/conf/demo/aiagent/moduleFactory_chat_web_config.xml`

- [ ] **Step 1: 创建 web 版控制中心配置**

创建 `demo/tlobject/src/main/resources/conf/demo/aiagent/controlCenter_web_config.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<moduleConfig>
    <params>
        <reloadableModules value="outputValidator"/>
        <auditLimit value="200"/>
    </params>
    <modules>
        <module name="moduleRegistry"
                classfile="cn.tianlong.tlobject.modules.TLModuleRegistry"/>
    </modules>
    <startMsg>
        <msg action="getModule" destination="moduleFactory" moduleName="dbSessionManager"/>
        <msg action="getModule" destination="moduleFactory" moduleName="myopenAiProvider"/>
        <msg action="getModule" destination="moduleFactory" moduleName="agentService"/>
        <msg action="getModule" destination="moduleFactory" moduleName="chatConsole"/>
        <msg action="getModule" destination="moduleFactory" moduleName="outputGuard"/>
        <msg action="getModule" destination="moduleFactory" moduleName="errorSupervisor"/>
        <msg action="getModule" destination="moduleFactory" moduleName="approvalGate"/>
        <msg action="getModule" destination="moduleFactory" moduleName="appAuthModule"/>
        <msg action="getModule" destination="moduleFactory" moduleName="toolExecutor"/>
        <msg action="getModule" destination="moduleFactory" moduleName="auth"/>
        <msg action="getModule" destination="moduleFactory" moduleName="demoUserModule"/>
        <msg action="getModule" destination="moduleFactory" moduleName="aiagent_master"/>
        <msg action="getModule" destination="moduleFactory" moduleName="mockProvider"/>
        <msg action="getModule" destination="moduleFactory" moduleName="testEchoSkill"/>
        <msg action="getModule" destination="moduleFactory" moduleName="testSleepSkill"/>
        <msg action="getModule" destination="moduleFactory" moduleName="agentTestModule"/>
        <msg action="getModule" destination="moduleFactory" moduleName="webui"/>
        <msg action="startWeb" destination="webui" waitFlag="false" usePreReturnMsg="false"/>
        <msg action="getModule" destination="moduleFactory" moduleName="jettyServer" usePreReturnMsg="false"/>
        <msg action="run" destination="jettyServer" waitFlag="false" usePreReturnMsg="false"/>
    </startMsg>
</moduleConfig>
```

- [ ] **Step 2: 修改 web 工厂配置**

修改 `demo/tlobject/src/main/resources/conf/demo/aiagent/moduleFactory_chat_web_config.xml`：

1）`<commonParams>` 内加：

```xml
        <controlCenterName value="controlCenter"/>
```

2）`<modulesParams>` 内加：

```xml
        <module name="controlCenter" configfile="controlCenter_web_config.xml"/>
```

3）整个 `<factoryBoot>` 替换为：

```xml
    <factoryBoot>
        <msg action="getModule" destination="moduleFactory" moduleName="logThreadPool"/>
        <msg action="getModule" destination="moduleFactory" moduleName="threadPool" usePreReturnMsg="false"/>
        <msg action="getModule" destination="moduleFactory" moduleName="database" usePreReturnMsg="false"/>
        <msg action="getModule" destination="moduleFactory" moduleName="controlCenter"/>
    </factoryBoot>
```

- [ ] **Step 3: 构建并验证 web 启动**

```bash
/d/maven/bin/mvn -q -pl demo/tlobject -am install -DskipTests
CP=$(sed -n '3p' aistart_args.txt | tr -d '\r')
"C:/Program Files/Java/jdk-17.0.1/bin/java.exe" -Dfile.encoding=UTF-8 -cp "$CP" cn.tianlong.java.demo.aiagent.AIStart -web
```

另起终端查看启动顺序（DEBUG 日志在文件里，不在控制台）：

```bash
grep "创建模块" D:/tllog4j/debug.log | tail -30
```

预期顺序：基础设施 → controlCenter → 应用模块 → webui → jettyServer；无 `module create failure`。

另起终端确认静态页可访问：

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/webui/chat.html
```

预期：`200`。

- [ ] **Step 4: 提交**

```bash
git add demo/tlobject/src/main/resources/conf/demo/aiagent/controlCenter_web_config.xml \
        demo/tlobject/src/main/resources/conf/demo/aiagent/moduleFactory_chat_web_config.xml
git commit -m "feat(control-center): web 应用启动序列迁入控制中心"
```

---

## Task 7: aitest 应用接入控制中心

**Files:**
- Create: `demo/tlobject/src/main/resources/conf/demo/aitest/controlCenter_aitest_config.xml`
- Modify: `demo/tlobject/src/main/resources/conf/demo/aitest/moduleFactory_config.xml`

- [ ] **Step 1: 创建 aitest 版控制中心配置**

创建 `demo/tlobject/src/main/resources/conf/demo/aitest/controlCenter_aitest_config.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<moduleConfig>
    <params>
        <reloadableModules value=""/>
        <auditLimit value="200"/>
    </params>
    <modules>
        <module name="moduleRegistry"
                classfile="cn.tianlong.tlobject.modules.TLModuleRegistry"/>
    </modules>
    <startMsg>
        <msg action="getModule" destination="moduleFactory" moduleName="aiagent"/>
        <msg action="getModule" destination="moduleFactory" moduleName="aiContext"/>
        <msg action="getModule" destination="moduleFactory" moduleName="sessionManager"/>
        <msg action="getModule" destination="moduleFactory" moduleName="openAiProvider"/>
        <msg action="getModule" destination="moduleFactory" moduleName="myopenAiProvider"/>
        <msg action="getModule" destination="moduleFactory" moduleName="httpRequestSkill"/>
        <msg action="getModule" destination="moduleFactory" moduleName="fileOperationSkill"/>
        <msg action="getModule" destination="moduleFactory" moduleName="shortTermMemory"/>
        <msg action="getModule" destination="moduleFactory" moduleName="longTermMemory"/>
        <msg action="getModule" destination="moduleFactory" moduleName="aiAgentDemo"/>
        <msg action="getModule" destination="moduleFactory" moduleName="demoWorkflow"/>
        <msg action="getModule" destination="moduleFactory" moduleName="agentMonitor"/>
        <msg action="getModule" destination="moduleFactory" moduleName="streamCallback"/>
        <msg action="getModule" destination="moduleFactory" moduleName="errorSupervisor"/>
        <msg action="getModule" destination="moduleFactory" moduleName="approvalGate"/>
        <msg action="getModule" destination="moduleFactory" moduleName="auth"/>
        <msg action="getModule" destination="moduleFactory" moduleName="appAuthModule"/>
        <msg action="getModule" destination="moduleFactory" moduleName="demoUserModule"/>
        <msg action="getModule" destination="moduleFactory" moduleName="evals"/>
        <msg action="getModule" destination="moduleFactory" moduleName="mockProvider"/>
        <msg action="getModule" destination="moduleFactory" moduleName="testEchoSkill"/>
        <msg action="getModule" destination="moduleFactory" moduleName="testSleepSkill"/>
        <msg action="getModule" destination="moduleFactory" moduleName="agentTestModule"/>
        <msg action="runAllTests" destination="agentTestModule"/>
    </startMsg>
</moduleConfig>
```

- [ ] **Step 2: 修改 aitest 工厂配置**

修改 `demo/tlobject/src/main/resources/conf/demo/aitest/moduleFactory_config.xml`：

1）在 `<modulesParams>` 之前加公共参数（该文件当前没有 `<commonParams>`）：

```xml
    <commonParams>
        <controlCenterName value="controlCenter"/>
    </commonParams>
```

2）`<modules>` 内加：

```xml
        <module name="controlCenter"
                classfile="cn.tianlong.tlobject.modules.TLControlCenter"
                singleton="true"
                necessary="yes"
                configfile="controlCenter_aitest_config.xml"/>
```

3）整个 `<factoryBoot>` 替换为：

```xml
    <factoryBoot>
        <msg action="getModule" destination="moduleFactory" moduleName="logThreadPool"/>
        <msg action="getModule" destination="moduleFactory" moduleName="controlCenter"/>
    </factoryBoot>
```

- [ ] **Step 3: 构建并跑 aitest**

```bash
/d/maven/bin/mvn -q -pl demo/tlobject -am install -DskipTests
cmd //c run_aitest.bat 2>&1 | grep "\[TEST\] 汇总"
```

预期：`[TEST] 汇总: 通过=36, 失败=0, 跳过=0`。

- [ ] **Step 4: 提交**

```bash
git add demo/tlobject/src/main/resources/conf/demo/aitest/controlCenter_aitest_config.xml \
        demo/tlobject/src/main/resources/conf/demo/aitest/moduleFactory_config.xml
git commit -m "feat(control-center): aitest 应用启动序列迁入控制中心"
```

---

## Task 8: 文档、遗留小修与全量回归

**Files:**
- Modify: `CLAUDE.md`
- Modify: `demo/tlobject/src/main/resources/conf/demo/aiagent/moduleFactory_chat_web_config.xml`
- Modify: `demo/tlobject/src/main/resources/conf/demo/aiagent/controlCenter_web_config.xml`
- Modify: `demo/tlobject/src/main/resources/conf/demo/aitest/controlCenter_aitest_config.xml`
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLChatConsole.java`
- Modify: `aiagent/common/src/test/java/cn/tianlong/tlobject/aiagent/TLAgentServiceReloadConfigSelfTest.java`

- [ ] **Step 1: 修正配置注释**

1) `moduleFactory_chat_web_config.xml` 顶部注释已过时，替换为：

```xml
<!--
    AI Agent 控制台 + Web 交互窗口 双入口配置（AIStart -web 使用）
    通过 include 合并 moduleFactory_chat_config.xml 的模块声明；
    include 不合并 commonParams/factoryBoot，因此本文件自带控制中心参数与启动序列。
-->
```

2) `controlCenter_web_config.xml` 的 `<params>` 加白名单说明：

```xml
    <params>
        <!-- 可重载配置的模块白名单；* = 全部，空 = 全部禁止。
             outputValidator 由 toolExecutor 首次输出校验时懒创建，首次工具调用前 reload 会报"未运行" -->
        <reloadableModules value="outputValidator"/>
        <auditLimit value="200"/>
    </params>
```

3) `controlCenter_aitest_config.xml` 的 `<params>` 加：

```xml
    <params>
        <!-- 测试应用不做配置热加载：空 = 全部禁止 -->
        <reloadableModules value=""/>
        <auditLimit value="200"/>
    </params>
```

- [ ] **Step 2: 控制台 reload 带上审计用户**

`TLChatConsole.java` 的 `/reloadconfig` 分支（约 1206-1213 行）当前只设 `name`，控制中心审计的 `userId` 为空。改为：

```java
                msg.setAction("reloadModuleConfig")
                        .setParam("name", parts[1])
                        .setParam("userId", userId);
```

`userId` 已存在（`private String userId = "console_user";`）。

- [ ] **Step 3: 补强 reload 委托自测**

在 `TLAgentServiceReloadConfigSelfTest.TestService` 中加 `TLMsg ccRequest;`，并让 `putToControlCenter` 记录入参：

```java
        @Override protected TLMsg putToControlCenter(String controlCenter, TLMsg msg) {
            ccRequest = msg;
            return ccResponse;
        }
```

在委托成功用例的 `rc1` 检查后加：

```java
        check("委托消息 action", delegated.ccRequest != null
                && "reloadModuleConfig".equals(delegated.ccRequest.getAction()));
        check("委托消息 name", delegated.ccRequest != null
                && "fromCC".equals(delegated.ccRequest.getStringParam("name", "")));
```

再加控制中心无响应用例：

```java
        TestService nullResp = new TestService();
        nullResp.ccName = "controlCenter";
        nullResp.ccResponse = null;
        TLMsg rc3 = nullResp.checkMsgAction(null, cmd("anyModule"));
        check("控制中心无响应返回失败", !rc3.parseBoolean("success", true)
                && rc3.getStringParam("error", "").contains("未返回结果"));
```

- [ ] **Step 4: 更新 CLAUDE.md**

在 "AI Agent Framework" 的 `**关键实现要点:**` 列表前加一节：

```markdown
**控制中心（`TLControlCenter`，core 模块）：**
- 工厂级单例，负责启动编排（配置文件 `<startMsg>`）、模块注册门面、配置重载白名单、审计。
- 启动分层：`factoryBoot` 只创建基础设施（`logThreadPool`/`threadPool`/`database`）与控制中心；应用模块由控制中心的 `<startMsg>` 创建。控制中心把 `<startMsg>` 交给工厂的 `bootstrapModules` 以工厂为发送者执行，应用模块的 `familyName` 保持裸名（否则会被加上 `controlCenter:` 前缀，破坏注册键）。
- 注册目标由工厂 `commonParams` 的 `controlCenterName` 指定；未配置时回退旧 `moduleRegistry`（旧应用行为不变）。基础设施层（L1）模块不得自注册，否则会回退旧注册表造成分裂。
- 内部注册表是控制中心的私有子模块，外部只通过控制中心访问。配置重载白名单 `reloadableModules`（分号分隔，`*` 全放行，空全拒绝），操作经 `auditList` 可查。
- 测试隔离：`TLTestAgentGroup` 直接构造、不走 `commonParams`，故意使用旧 `moduleRegistry`，避免假成员污染生产注册表。
```

- [ ] **Step 5: 全量构建**

```bash
/d/maven/bin/mvn -q clean install -DskipTests
```

预期：BUILD SUCCESS，所有模块编译通过。

- [ ] **Step 6: 控制台回归 + 审计归因**

```bash
printf '/test\n/exit\n' | "C:/Program Files/Java/jdk-17.0.1/bin/java.exe" @aistart_args.txt 2>&1 | grep "\[TEST\] 汇总"
printf '/reloadconfig outputValidator\n/reloadconfig aiagent_master\n/exit\n' | "C:/Program Files/Java/jdk-17.0.1/bin/java.exe" @aistart_args.txt 2>&1 | grep -E "配置已重载|白名单"
grep "controlCenter reloadModuleConfig" D:/tllog4j/info.log | tail -2
```

预期：

- `[TEST] 汇总: 通过=22, 失败=0`；
- `配置已重载: outputValidator` 与 `模块未在可重载白名单中: aiagent_master`；
- 审计行 `user=console_user`（不再是空）。

- [ ] **Step 7: 自测回归**

```bash
/d/maven/bin/mvn -q -pl aiagent/common -am test-compile
/d/maven/bin/mvn -q -pl aiagent/common -am dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "aiagent/common/target/classes;aiagent/common/target/test-classes;core/target/classes;$(cat aiagent/common/cp.txt)" cn.tianlong.tlobject.aiagent.TLAgentServiceReloadConfigSelfTest
```

预期：0 失败（新增 3 个检查）。

- [ ] **Step 8: aitest 回归**

```bash
cmd //c run_aitest.bat 2>&1 | grep "\[TEST\] 汇总"
```

预期：`[TEST] 汇总: 通过=36, 失败=0`（skipped 为 0 时不单独打印）。

- [ ] **Step 9: Web 启动回归**

```bash
CP=$(sed -n '3p' aistart_args.txt | tr -d '\r')
(sleep 60 | "C:/Program Files/Java/jdk-17.0.1/bin/java.exe" -Dfile.encoding=UTF-8 -cp "$CP" cn.tianlong.java.demo.aiagent.AIStart -web > /tmp/tl_web_final.out 2>&1 &)
sleep 15
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/webui/chat.html
```

预期：`200`；随后停止该 Java 进程。

- [ ] **Step 10: 静态验收**

```bash
grep -rn "putMsg(DEFAULTMODULEREGISTRY" --include="*.java" core aiagent | grep -v "/target/"
grep -rn "DEFAULTMODULEREGISTRY" --include="*.java" aiagent | grep -v "/target/"
grep -rn "DEFAULTMODULEREGISTRY" --include="*.java" core/src/main/java | grep -v "/target/"
```

预期：

- 前两条无输出（业务代码不再直连旧注册表）；
- 第三条只允许出现 `TLParamString`（常量）、`TLBaseModule`（回退）、`TLControlCenter`（私有子模块名）三处。

- [ ] **Step 11: 提交**

```bash
git add CLAUDE.md \
        demo/tlobject/src/main/resources/conf/demo/aiagent/moduleFactory_chat_web_config.xml \
        demo/tlobject/src/main/resources/conf/demo/aiagent/controlCenter_web_config.xml \
        demo/tlobject/src/main/resources/conf/demo/aitest/controlCenter_aitest_config.xml \
        aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLChatConsole.java \
        aiagent/common/src/test/java/cn/tianlong/tlobject/aiagent/TLAgentServiceReloadConfigSelfTest.java
git commit -m "docs+test(control-center): 注释/审计归因/测试补强与全量回归"
```

- [ ] **Step 12: 结束分支**

使用 `superpowers:finishing-a-development-branch` 决定合并/PR/清理。

---

## 自审

### 规格覆盖

| Spec 章节 | 对应任务 |
|---|---|
| 两层责任划分（TLAppStartUp 不动） | 全篇；无 TLAppStartUp 修改 |
| 启动分层 L0-L4 | Task 4、6、7 |
| 模块契约（registry/reload/audit） | Task 2 |
| 配置形态（commonParams/startMsg/覆盖） | Task 4、6、7 |
| 注册收口与兼容回退 | Task 1、3、4 |
| reload 治理 | Task 2、5 |
| 审计 | Task 2 |
| 验证方案 | Task 3-8 |

### 关键不变式

- `registryTarget()` 在三类部署下都安全：控制中心存在、未配置、配置但模块缺失。
- 控制中心是单例；创建完成并进入工厂模块表后才执行 `runStartMsg`，启动序列中的重入安全。
- 控制中心的私有注册表只在 `init()` 创建一次；外部裸名访问不会创建第二个实例（懒加载 + 无调用方）。
- 所有注册调用在 Task 3 已切到 `registryTarget()`，Task 4 切换配置后才真正走控制中心，避免半迁移。
- reload 结果在控制中心用 `RESULT/error/message/data`，agentService 映射回 `success/error/message/data`。
