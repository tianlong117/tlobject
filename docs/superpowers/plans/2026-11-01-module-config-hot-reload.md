# 运行期模块配置热加载 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 运行中修改模块配置后无需重启：新增通用 `/reloadconfig <模块>`，首个接入 `TLOutputValidator`（规则原子替换、失败保留旧规则并报错）。

**Architecture:** 命令经 `TLChatConsole`/`TLWebChatModule` → `TLAgentService` → 目标模块实例的 `reloadConfig`。基类先做文件/XML 级预检（不运行子类自定义解析器）再 `configure()`，返回结构化结果；validator 用 `pendingRules` 快照实现子类级原子替换。

**Tech Stack:** Java 17、Maven、kxml2（XmlPullParser）、Gson；自测为 runnable main（无 JUnit）。

---

## 文件地图

| 文件 | 改动 |
|---|---|
| `core/.../base/TLModuleConfig.java` | 增加 `parseOk`/`parseError` 与 getter，解析失败可观测 |
| `core/.../base/TLBaseModule.java` | `resolveConfigFile()`、`probeConfigFile()`、`reloadConfig` 预检 + 结构化返回 |
| `core/.../base/TLConfigReloadSelfTest.java` | 新增：parseOk + 基类预检自测 |
| `aiagent/common/.../aiagent/TLOutputValidator.java` | 规则快照、pending 替换、`reloadConfig` 覆写、参数复位 |
| `aiagent/common/.../aiagent/TLOutputValidatorReloadSelfTest.java` | 新增：原子重载/失败保留/并发自测 |
| `aiagent/common/.../aiagent/TLAgentService.java` | `reloadModuleConfig` action、目标解析、结果映射 |
| `aiagent/common/.../aiagent/TLAgentServiceReloadConfigSelfTest.java` | 新增：命令层结果映射自测 |
| `aiagent/common/.../aiagent/TLChatConsole.java` | `/reloadconfig`（`/rcfg`）命令解析、帮助、结果渲染 |
| `demo/.../conf/demo/aiagent/outputValidator_config.xml` | 注释补充热加载用法 |
| `CLAUDE.md` | 输出校验小节补一行热加载说明 |

---

### Task 1: TLModuleConfig 解析状态可观测

> 实施修正：本任务最终以提交 `eaf6edf3` 为准。Step 1/3 的代码块是初始版本；实施中额外增加了 `isWellFormed()`（截断/空/多根/根外文本/BOM）与“缺文件 parseOk=false”，自测最终 18 PASS。不要按旧代码块重写。

**Files:**
- Modify: `core/src/main/java/cn/tianlong/tlobject/base/TLModuleConfig.java:30-56,129-293`
- Test: `core/src/main/java/cn/tianlong/tlobject/base/TLConfigReloadSelfTest.java`（新建）

- [ ] **Step 1: 写失败测试（parseOk 部分）**

新建 `core/src/main/java/cn/tianlong/tlobject/base/TLConfigReloadSelfTest.java`：

```java
package cn.tianlong.tlobject.base;

import cn.tianlong.tlobject.modules.LogLevel;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 配置热加载基座自测（runnable main，无 JUnit——仓库惯例）。
 * 覆盖：TLModuleConfig.parseOk、基类 reloadConfig 的文件/XML 预检。
 */
public class TLConfigReloadSelfTest {
    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        // 1) 合法 XML（含未知标签）：parseOk=true
        String good = writeTemp("good",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<moduleConfig><params><a value=\"1\"/></params>"
                + "<rules><rule tool=\"x\"/></rules></moduleConfig>\n");
        TLModuleConfig c1 = new TLModuleConfig(good, "");
        c1.parse(good);
        check("合法 XML parseOk=true", c1.isParseOk());

        // 2) 坏 XML：parse 仍返回非 null，但 parseOk=false
        String bad = writeTemp("bad", "<moduleConfig><rules><rule tool=\"x\">");
        TLModuleConfig c2 = new TLModuleConfig(bad, "");
        TLModuleConfig parsedBad = c2.parse(bad);
        check("坏 XML parse 仍返回对象", parsedBad != null);
        check("坏 XML parseOk=false", !c2.isParseOk());
        check("坏 XML 有错误信息", c2.getParseError() != null && !c2.getParseError().isEmpty());

        // 3) 文件不存在：parse 返回 null
        TLModuleConfig c3 = new TLModuleConfig(good + ".missing", "");
        check("缺文件 parse 返回 null", c3.parse(good + ".missing") == null);

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static String writeTemp(String prefix, String content) throws Exception {
        File f = File.createTempFile(prefix, ".xml");
        f.deleteOnExit();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f.getAbsolutePath();
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd D:/tlobjectapp/tlobject
/d/maven/bin/mvn -q -f pom.xml -pl core -am -DskipTests compile
/d/maven/bin/mvn -q -f core/pom.xml dependency:build-classpath -Dmdep.outputFile=D:/tlobjectapp/tlobject/_cp_core.txt
java -cp "core/target/classes;$(cat _cp_core.txt)" cn.tianlong.tlobject.base.TLConfigReloadSelfTest
```

Expected: 编译失败（`isParseOk()` 不存在）。

- [ ] **Step 3: 实现 parseOk**

在 `TLModuleConfig` 字段区（`protected String configFile;` 之后）加：

```java
    /** 最近一次解析是否成功。parse() 在 XML 异常时仍返回 this，必须看此标记 */
    protected boolean parseOk = true;
    /** 最近一次解析失败原因；成功时为 null */
    protected String parseError;
```

在构造函数后加 getter：

```java
    public boolean isParseOk() { return parseOk; }
    public String getParseError() { return parseError; }
```

`parse(String configFile)` 开头重置（覆盖“文件不存在导致 parseconfig 未执行”的情况）：

```java
    public TLModuleConfig parse(String configFile){
        parseOk = true;
        parseError = null;
        if(configFile !=null)
            this.configFile=configFile;
         return (TLModuleConfig) init();
    }
```

`parseconfig` 开头也重置：

```java
    protected Object parseconfig(InputStream xmlData ,String paramName) {
        parseOk = true;
        parseError = null;
        try {
```

三个 catch 分支置失败（保持原有日志不变）：

```java
        } catch (XmlPullParserException e) {
            parseOk = false;
            parseError = "Xml解析错误: " + e.getMessage();
            putLog("解析配置错误,Xml解析错误:"+configFile,LogLevel.ERROR);
            e.printStackTrace();
        } catch (IOException e) {
            parseOk = false;
            parseError = "IO错误: " + e.getMessage();
            e.printStackTrace();
            putLog("解析配置错误，IO错误:"+configFile,LogLevel.ERROR);
        } catch (Throwable throwable) {
            parseOk = false;
            parseError = "解析错误: " + throwable;
            throwable.printStackTrace();
            putLog("解析配置错误:"+configFile,LogLevel.ERROR);
        }
```

- [ ] **Step 4: 运行测试确认通过**

运行 Step 2 的命令。Expected: 18 个 PASS，退出码 0。

> 实施修正（2026-11-01）：kxml2 对 EOF 处未闭合的标签**不抛异常**（只留下 depth>0），所以额外增加了 `isWellFormed()` 严格检查（标签栈 + 恰好一个根元素 + 根外无文本 + 容忍一个 UTF-8 BOM）；`parse()` 在文件不可读时返回 null 并置 `parseOk=false`。自测最终 18 PASS。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/tianlong/tlobject/base/TLModuleConfig.java core/src/main/java/cn/tianlong/tlobject/base/TLConfigReloadSelfTest.java
git commit -m "feat(core): TLModuleConfig 暴露 parseOk/parseError，解析失败可观测"
```

---

### Task 2: 基类 reloadConfig 预检 + 结构化返回

**Files:**
- Modify: `core/src/main/java/cn/tianlong/tlobject/base/TLBaseModule.java:1176-1198`
- Test: `core/src/main/java/cn/tianlong/tlobject/base/TLConfigReloadSelfTest.java`（追加用例）

- [ ] **Step 1: 追加失败测试**

在 `TLConfigReloadSelfTest.main` 的 `System.out.println` 之前插入：

```java
        // 4) 基类 reloadConfig 预检：坏 XML 不进入 configure
        ProbeModule badModule = new ProbeModule("probeBad", bad);
        TLMsg badR = badModule.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("坏 XML 重载失败", badR != null && !badR.parseBoolean("result", true));
        check("坏 XML 未执行 configure", badModule.configureCount == 0);

        // 5) 正常 XML：configure 执行，返回成功
        ProbeModule goodModule = new ProbeModule("probeGood", good);
        TLMsg goodR = goodModule.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("正常 XML 重载成功", goodR != null && goodR.parseBoolean("result", false));
        check("正常 XML 执行 configure", goodModule.configureCount == 1);

        // 6) 缺文件：失败且不 configure
        ProbeModule missModule = new ProbeModule("probeMiss", good + ".missing");
        TLMsg missR = missModule.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("缺文件重载失败", missR != null && !missR.parseBoolean("result", true));
        check("缺文件未执行 configure", missModule.configureCount == 0);

        // 7) 截断 XML（kxml2 不抛异常）：严格检查拦截，不进入 configure
        String trunc = writeTemp("truncReload", "<moduleConfig><rules><rule tool=\"x\">");
        ProbeModule truncModule = new ProbeModule("probeTrunc", trunc);
        TLMsg truncR = truncModule.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("截断 XML 重载失败", truncR != null && !truncR.parseBoolean("result", true));
        check("截断 XML 未执行 configure", truncModule.configureCount == 0);

        // 8) include 配置：probe 必须带 factory，否则 include 解析 NPE
        String incDir = Files.createTempDirectory("cfgInclude").toFile().getAbsolutePath() + File.separator;
        File inc = new File(incDir + "inc.xml");
        Files.write(inc.toPath(), "<moduleConfig><params><b value=\"2\"/></params></moduleConfig>"
                .getBytes(StandardCharsets.UTF_8));
        String incMain = incDir + "main.xml";
        Files.write(new File(incMain).toPath(),
                "<moduleConfig><include file=\"inc.xml\"/></moduleConfig>".getBytes(StandardCharsets.UTF_8));
        ProbeModule incModule = new ProbeModule("probeInclude", incMain);
        incModule.setFactory(new TLObjectFactory("cfgTestFactory", incDir + "moduleFactory_config.xml", incDir));
        check("include 配置预检通过", incModule.probeConfigFile(incMain) == null);
```

并在类末尾（`writeTemp` 之前）加测试模块：

```java
    static class ProbeModule extends TLBaseModule {
        int configureCount;
        ProbeModule(String name, String configFile) { super(name, configFile); }
        @Override protected void configure() { configureCount++; super.configure(); }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
    }
```

- [ ] **Step 2: 运行测试确认失败**

运行 Task 1 Step 2 的命令。Expected: FAIL（`reloadConfig` 返回 null，断言失败）。

- [ ] **Step 3: 实现预检与结构化返回**

在 `TLBaseModule` 中 `reloadConfig` 前增加两个辅助方法：

```java
    /** 按 setConfig 的规则解析本模块配置文件路径；无配置文件返回 null。 */
    protected String resolveConfigFile() {
        if (configFile != null && !configFile.isEmpty())
            return configFile;
        if (!autoConfig || moduleFactory == null)
            return null;
        return moduleFactory.getConfigDir() + name + "_config.xml";
    }

    /**
     * 文件/XML 级预检：用默认 TLModuleConfig 解析，不运行子类自定义 myConfig。
     * @return null=通过；否则错误文本（文件不可读/XML 非法）
     */
    protected String probeConfigFile(String parseFile) {
        if (parseFile == null || parseFile.isEmpty())
            return null;
        String configDir = moduleFactory != null ? moduleFactory.getConfigDir() : "";
        TLModuleConfig probe = new TLModuleConfig(parseFile, configDir);
        if (moduleFactory != null)
            probe.setFactory(moduleFactory);
        if (!probe.isWellFormed())
            return "配置文件不可读或 XML 不合法: " + parseFile;
        TLModuleConfig parsed = probe.parse(parseFile);
        if (parsed == null)
            return "配置文件不可读: " + parseFile;
        if (!parsed.isParseOk())
            return "配置解析失败: " + parseFile
                    + (parsed.getParseError() != null ? " (" + parsed.getParseError() + ")" : "");
        return null;
    }
```

将 `reloadConfig` 整体替换为（含 Javadoc）：

```java
    /**
     * 重新加载本模块配置：文件/XML 级预检通过后重新执行 configure()。
     * 注意：
     * - 不执行 init()/runStartMsg()，不重建子模块；
     * - params 只合并、不清理已删除的键；
     * - 预检不运行子类自定义 myConfig，子类语义由模块自己的 reload 逻辑保证；
     * - params.reload=true 的工厂重建路径不保证外部缓存引用更新。
     */
    protected TLMsg reloadConfig(Object fromWho, TLMsg msg) {
        try {
            String parseFile = resolveConfigFile();
            String probeError = probeConfigFile(parseFile);
            if (probeError != null) {
                putLog(name + " " + probeError, LogLevel.ERROR, "reloadConfig");
                return createMsg().setParam(RESULT, false).setParam("error", probeError);
            }
            preReConfig();
            if (moduleFactory != null) {
                HashMap<String, String> cparams = moduleFactory.getModuleParam(name, (HashMap<String, String>) null);
                if (params == null) params = new HashMap<>();
                params.putAll(cparams);
            }
            configure();
            reload();
            reConfig();
            putLog(name + " 重新加载配置", LogLevel.DEBUG, "reloadConfig");
            return createMsg().setParam(RESULT, true);
        } catch (Throwable t) {
            putLog(name + " 重新加载配置失败: " + t, LogLevel.ERROR, "reloadConfig");
            return createMsg().setParam(RESULT, false).setParam("error", t.toString());
        }
    }
```

- [ ] **Step 4: 运行测试确认通过**

运行 Task 1 Step 2 的命令。Expected: 36 个 PASS，退出码 0。

> 实施修正：`<include>` 失败会传播到父配置 `parseOk` 并打 ERROR 日志；自动派生配置文件缺失时预检失败（防止 validator fail-open）；重复重载的 `initMsg/startMsg` 累积列为已知边界。最终 36 PASS。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/tianlong/tlobject/base/TLBaseModule.java core/src/main/java/cn/tianlong/tlobject/base/TLConfigReloadSelfTest.java
git commit -m "feat(core): reloadConfig 增加配置文件预检与结构化返回"
```

---

### Task 3: TLOutputValidator 原子重载

**Files:**
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLOutputValidator.java`
- Test: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLOutputValidatorReloadSelfTest.java`（新建）

- [ ] **Step 1: 写失败测试**

新建 `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLOutputValidatorReloadSelfTest.java`：

```java
package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TLOutputValidator 运行期规则重载自测（runnable main）。
 * 覆盖：成功替换、坏 XML 保留旧规则、空 rules、参数复位、并发校验。
 */
public class TLOutputValidatorReloadSelfTest implements TLAiAgentParamString {
    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        String file = writeTemp("ruleA", config("<rule tool=\"calculate_price\" checks=\"total&lt;100\"/>"));
        TestValidator v = new TestValidator("ovReloadTest", file);

        TLMsg r1 = v.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("合法规则重载成功", r1 != null && r1.parseBoolean("result", false));
        check("规则数为 1", v.rules.size() == 1);
        check("新规则生效（200 被拒）", !valid(v, 200.0));

        writeFile(file, "<moduleConfig><rules><rule tool=\"calculate_price\"");
        TLMsg r2 = v.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("坏 XML 重载失败", r2 != null && !r2.parseBoolean("result", true));
        check("坏 XML 后旧规则仍生效", !valid(v, 200.0));

        writeFile(file, config("<rule tool=\"calculate_price\" checks=\"total:number\"/>"));
        TLMsg r3 = v.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("新规则重载成功", r3 != null && r3.parseBoolean("result", false));
        check("新规则生效（200 通过）", valid(v, 200.0));

        writeFile(file, "<moduleConfig><rules/></moduleConfig>");
        TLMsg r4 = v.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("空规则重载成功", r4 != null && r4.parseBoolean("result", false));
        check("空规则条数为 0", "0".equals(String.valueOf(r4.getParam("rules"))));
        check("空规则全部放行", valid(v, 200.0));

        writeFile(file, "<moduleConfig><params><failClosedOnError value=\"true\"/></params>"
                + "<rules><rule tool=\"calculate_price\" checks=\"total:number\"/></rules></moduleConfig>");
        v.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("failClosedOnError 读取为 true", v.failClosedOnError);

        writeFile(file, config("<rule tool=\"calculate_price\" checks=\"total:number\"/>"));
        v.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("删除配置后 failClosedOnError 复位 false", !v.failClosedOnError);

        String a = config("<rule tool=\"calculate_price\" checks=\"total&lt;100\"/>");
        String b = config("<rule tool=\"calculate_price\" checks=\"total:number\"/>");
        writeFile(file, a);
        v.reloadConfig(null, new TLMsg().setAction("reloadConfig"));

        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int n = 0; n < 500; n++) v.validateRequest(request("calculate_price", 200.0));
                } catch (Throwable ex) {
                    error.compareAndSet(null, ex);
                }
            });
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
        start.countDown();
        for (int i = 0; i < 30; i++) {
            writeFile(file, i % 2 == 0 ? b : a);
            v.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        }
        for (Thread t : threads) t.join(5000);
        check("并发校验+重载无异常", error.get() == null);

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    static class TestValidator extends TLOutputValidator {
        TestValidator(String name, String configFile) { super(name, configFile); }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
        @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }
    }

    private static boolean valid(TestValidator v, double total) {
        return v.validateRequest(request("calculate_price", total)).parseBoolean("valid", false);
    }

    private static TLMsg request(String functionName, double total) {
        TLMsg result = new TLMsg().setParam("total", total)
                .setParam("quantity", 2).setParam("unitPrice", total / 2);
        return new TLMsg().setAction(OUTPUT_VALIDATE_ACTION)
                .setParam("functionName", functionName)
                .setParam("moduleName", "priceCalc")
                .setParam("agentName", "testAgent")
                .setParam("result", result);
    }

    private static String config(String rules) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<moduleConfig><rules>" + rules + "</rules></moduleConfig>\n";
    }

    private static String writeTemp(String prefix, String content) throws Exception {
        File f = File.createTempFile(prefix, ".xml");
        f.deleteOnExit();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f.getAbsolutePath();
    }

    private static void writeFile(String path, String content) throws Exception {
        Files.write(new File(path).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd D:/tlobjectapp/tlobject
/d/maven/bin/mvn -q -f pom.xml -pl aiagent/common -am -DskipTests compile
/d/maven/bin/mvn -q -f aiagent/common/pom.xml dependency:build-classpath -Dmdep.outputFile=D:/tlobjectapp/tlobject/_cp_aiagent.txt
java -cp "aiagent/common/target/classes;core/target/classes;database/target/classes;$(cat _cp_aiagent.txt)" cn.tianlong.tlobject.aiagent.TLOutputValidatorReloadSelfTest
```

Expected: 编译失败（`TestValidator` 构造器不存在、`rules` 不是测试期望的类型等）。

- [ ] **Step 3: 实现 validator 原子重载**

`TLOutputValidator` 字段（原 `protected final List<Rule> rules = new ArrayList<>();`）替换为：

```java
    /** 规则快照（volatile + 不可变）：重载线程整体替换，校验线程只读 */
    protected volatile List<Rule> rules = java.util.Collections.emptyList();

    /** configure 期间解析出的候选规则；成功后才替换 rules */
    protected List<Rule> pendingRules;

    /** 本次配置解析错误；null 表示成功 */
    protected String parseError;

    /** 最近一次 setConfig 是否可用 */
    protected boolean lastConfigOk;
```

构造函数增加：

```java
    public TLOutputValidator(String name, String configFile) { super(name, configFile); }
```

`setConfig` 替换为：

```java
    @Override
    protected Object setConfig() {
        pendingRules = new ArrayList<>();
        parseError = null;
        lastConfigOk = false;
        String configDir = moduleFactory != null ? moduleFactory.getConfigDir() : "";
        myConfig config = new myConfig(configFile, configDir);
        mconfig = config;
        super.setConfig();
        if (mconfig == null) {
            parseError = "配置文件不可用: " + configFile;
        } else if (!mconfig.isParseOk()) {
            parseError = "配置解析失败: " + mconfig.getParseError();
        }
        lastConfigOk = parseError == null;
        return config;
    }
```

`setModuleParams` 替换为：

```java
    @Override
    protected void setModuleParams() {
        failClosedOnError = false;
        if (params != null && params.get("failClosedOnError") != null)
            failClosedOnError = "true".equals(params.get("failClosedOnError"));
    }
```

`init` 替换为：

```java
    @Override
    protected TLBaseModule init() {
        if (lastConfigOk && pendingRules != null) {
            rules = java.util.Collections.unmodifiableList(new ArrayList<>(pendingRules));
        } else {
            rules = java.util.Collections.emptyList();
            if (parseError != null)
                putLog("输出校验规则加载失败，校验将全部放行: " + parseError, LogLevel.ERROR);
        }
        putLog("TLOutputValidator [" + name + "] rules=" + rules.size()
                + " failClosedOnError=" + failClosedOnError, LogLevel.DEBUG);
        return this;
    }
```

在 `init` 后增加覆写：

```java
    /**
     * 运行期重载：基类完成预检与 configure（会重入 setConfig），
     * 全部成功后才把 pendingRules 换入 rules；任何失败保留旧快照。
     */
    @Override
    protected TLMsg reloadConfig(Object fromWho, TLMsg msg) {
        synchronized (this) {
            List<Rule> old = rules;
            parseError = null;
            pendingRules = null;
            TLMsg r = super.reloadConfig(fromWho, msg);
            boolean superOk = r != null && r.parseBoolean(RESULT, false);
            if (superOk && lastConfigOk && pendingRules != null) {
                rules = java.util.Collections.unmodifiableList(new ArrayList<>(pendingRules));
                putLog("输出校验规则已重载: " + rules.size() + " 条 (" + configFile + ")", LogLevel.INFO);
                return createMsg().setParam(RESULT, true)
                        .setParam("rules", rules.size())
                        .setParam("file", configFile);
            }
            rules = old;
            String err = parseError != null ? parseError
                    : (r != null ? r.getStringParam("error", "") : "");
            if (err == null || err.isEmpty()) err = "重载失败";
            putLog("输出校验规则重载失败，保留旧规则: " + err, LogLevel.ERROR);
            return createMsg().setParam(RESULT, false).setParam("error", err);
        }
    }
```

`matchRule` 开头加快照，并用快照迭代：

```java
    protected Rule matchRule(String functionName, String moduleName, String agentName) {
        List<Rule> snapshot = rules;
        Rule best = null;
        int bestScore = -1;
        for (Rule r : snapshot) {
```

`myConfig.myConfig` 的 catch 改为记录 `parseError`：

```java
        @Override
        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if ("rules".equals(xpp.getName())) parseRules(xpp);
            } catch (Throwable t) {
                parseError = "rules 解析失败: " + t;
                putLog("TLOutputValidator config parse error: " + t, LogLevel.ERROR);
            }
        }
```

`parseRules` 里原来的 `rules.add(r)` 改为：

```java
                    if (pendingRules == null) pendingRules = new ArrayList<>();
                    pendingRules.add(r);
```

- [ ] **Step 4: 运行测试确认通过**

运行 Step 2 的命令。Expected: 34 个 PASS，退出码 0（含新增快照/无配置/回滚/外部参数用例）。

- [ ] **Step 5: 提交**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLOutputValidator.java aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLOutputValidatorReloadSelfTest.java
git commit -m "feat(aiagent): 输出校验规则支持运行期原子重载（失败保留旧规则）"
```

---

### Task 4: TLAgentService 重载命令

**Files:**
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAgentService.java`
- Test: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAgentServiceReloadConfigSelfTest.java`（新建）

- [ ] **Step 1: 写失败测试**

新建 `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAgentServiceReloadConfigSelfTest.java`：

```java
package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.Map;

/**
 * reloadModuleConfig 命令层结果映射自测（runnable main）。
 * 覆盖：未运行、成功带 rules、模块失败、模块返回 null。
 */
public class TLAgentServiceReloadConfigSelfTest {
    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        TestService service = new TestService();

        service.target = null;
        TLMsg r1 = service.checkMsgAction(null, cmd("notRunning"));
        check("未运行模块返回失败", !r1.parseBoolean("success", true));
        check("错误信息包含模块名", r1.getStringParam("error", "").contains("notRunning"));

        service.target = new FakeModule("outputValidator", "dummy.xml",
                new TLMsg().setParam("result", true).setParam("rules", 3).setParam("file", "x.xml"));
        TLMsg r2 = service.checkMsgAction(null, cmd("outputValidator"));
        check("成功返回 success", r2.parseBoolean("success", false));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r2.getParam("data");
        check("返回 rules", data != null && "3".equals(String.valueOf(data.get("rules"))));

        service.target = new FakeModule("bad", "dummy.xml",
                new TLMsg().setParam("result", false).setParam("error", "boom"));
        TLMsg r3 = service.checkMsgAction(null, cmd("bad"));
        check("模块失败返回失败", !r3.parseBoolean("success", true));
        check("透传错误", r3.getStringParam("error", "").contains("boom"));

        service.target = new FakeModule("nullResult", "dummy.xml", null);
        TLMsg r4 = service.checkMsgAction(null, cmd("nullResult"));
        check("null 结果按已发送处理", r4.parseBoolean("success", false));

        service.target = new FakeModule("noConfig", null, null);
        TLMsg r5 = service.checkMsgAction(null, cmd("noConfig"));
        check("无配置文件模块返回失败", !r5.parseBoolean("success", true));

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    static class TestService extends TLAgentService {
        TLBaseModule target;
        TestService() { super("reloadConfigTest"); }
        @Override protected TLBaseModule resolveReloadTarget(String name) { return target; }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
    }

    static class FakeModule extends TLBaseModule {
        private final TLMsg response;
        FakeModule(String name, String configFile, TLMsg response) { super(name, configFile); this.response = response; }
        @Override protected TLMsg reloadConfig(Object fromWho, TLMsg msg) { return response; }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
    }

    private static TLMsg cmd(String name) {
        return new TLMsg().setAction("reloadModuleConfig").setParam("name", name);
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd D:/tlobjectapp/tlobject
/d/maven/bin/mvn -q -f pom.xml -pl aiagent/common -am -DskipTests compile
java -cp "aiagent/common/target/classes;core/target/classes;database/target/classes;$(cat _cp_aiagent.txt)" cn.tianlong.tlobject.aiagent.TLAgentServiceReloadConfigSelfTest
```

Expected: 编译失败（`resolveReloadTarget` 不存在 / action 未处理）。

- [ ] **Step 3: 实现命令**

`TLAgentService` 顶部 import 增加：

```java
import cn.tianlong.tlobject.modules.LogLevel;
```

`ACTION_REGISTRY` 在 `reload` 之后加：

```java
        ACTION_REGISTRY.put("reloadModuleConfig", "重载模块配置（如 outputValidator，不重启）");
```

`checkMsgAction` 的 `case "reload"` 之后加：

```java
            case "reloadModuleConfig":
                return doReloadConfig(fromWho, msg);
```

在 `doReload` / `reloadBaseSkill` 附近增加两个方法：

```java
    /** 重载指定模块的配置文件（原地 reloadConfig，不重建实例） */
    protected TLMsg doReloadConfig(Object fromWho, TLMsg msg) {
        String name = msg.getStringParam("name", msg.getStringParam("module", ""));
        if (name == null || name.isEmpty()) return fail("缺少参数: name");

        TLBaseModule target = resolveReloadTarget(name);
        if (target == null) {
            return fail("模块未运行或不存在: " + name
                    + "（未实例化的模块会在首次创建时读取新配置）");
        }
        if (target.getConfigFile() == null) {
            return fail("模块未加载配置文件: " + name
                    + "（无配置文件可重载；请检查模块声明/配置路径）");
        }
        TLMsg result;
        try {
            result = putMsg(target, createMsg().setAction("reloadConfig"));
        } catch (Throwable t) {
            return fail("模块配置重载异常: " + t);
        }
        if (result == null) {
            putLog("reloadModuleConfig: " + name + " 未返回结果", LogLevel.WARN);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("module", name);
            return ok("已发送重载请求（模块未返回结果）", data);
        }
        if (!result.parseBoolean(RESULT, true)) {
            return fail("模块配置重载失败: " + result.getStringParam("error", name));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("module", name);
        if (result.containsParam("rules")) data.put("rules", result.getParam("rules"));
        if (result.containsParam("file")) data.put("file", result.getParam("file"));
        String message = "配置已重载: " + name;
        if (result.containsParam("rules")) message += "（rules=" + result.getParam("rules") + "）";
        return ok(message, data);
    }

    /** 解析重载目标：registry 精确家族名 → 工厂已存在实例；不隐式创建 */
    protected TLBaseModule resolveReloadTarget(String name) {
        TLBaseModule target = findAgentInstance(name);
        if (target != null) return target;
        Object existing = getModuleInFactory(name);
        return existing instanceof TLBaseModule ? (TLBaseModule) existing : null;
    }
```

- [ ] **Step 4: 运行测试确认通过**

运行 Step 2 的命令。Expected: 15 个 PASS，退出码 0。

- [ ] **Step 5: 提交**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAgentService.java aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAgentServiceReloadConfigSelfTest.java
git commit -m "feat(aiagent): 新增 reloadModuleConfig 通用模块配置重载命令"
```

---

### Task 5: 控制台命令与帮助

**Files:**
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLChatConsole.java`

- [ ] **Step 1: 加命令解析**

在 `parseCommand` 的 `case "reload"` 之后加：

```java
            case "reloadconfig":
            case "rcfg":
                if (parts.length < 2) {
                    System.out.println("用法: /reloadconfig <模块名>（例如 outputValidator）");
                    return null;
                }
                msg.setAction("reloadModuleConfig").setParam("name", parts[1]);
                break;
```

- [ ] **Step 2: 加结果渲染**

在 `executeServiceCommand` 的结果分支里，把：

```java
                case "install":
                case "uninstall":
                case "reload":
                    System.out.println("✓ " + message);
                    break;
```

改为：

```java
                case "install":
                case "uninstall":
                case "reload":
                case "reloadModuleConfig":
                    System.out.println("✓ " + message);
                    break;
```

- [ ] **Step 3: 加帮助**

在 `/help` 的“重载命令”段（`/reload -bs ...` 之后）加：

```java
        System.out.println("  /reloadconfig <模块名>      重载模块配置（如 outputValidator），不重启");
```

- [ ] **Step 4: 编译验证**

```bash
cd D:/tlobjectapp/tlobject
/d/maven/bin/mvn -q -f pom.xml -pl aiagent/common -am -DskipTests compile
```

Expected: 退出码 0。

- [ ] **Step 5: 提交**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLChatConsole.java
git commit -m "feat(console): 增加 /reloadconfig 模块配置热加载命令"
```

---

### Task 6: 文档、配置注释与端到端验收

**Files:**
- Modify: `demo/tlobject/src/main/resources/conf/demo/aiagent/outputValidator_config.xml`
- Modify: `CLAUDE.md`

- [ ] **Step 1: 更新 validator 配置注释**

在 `outputValidator_config.xml` 头部注释的“由 toolExecutor_config.xml 的 outputValidator=outputValidator 启用”之后加：

```xml
         运行中修改本文件后，控制台执行 /reloadconfig outputValidator（WebUI 同 action）
         即可生效；解析失败会保留旧规则并返回错误，不会静默清空校验。
```

- [ ] **Step 2: 更新 CLAUDE.md**

在输出校验那条 bullet 末尾追加：

```markdown
- 配置热加载：`/reloadconfig <模块>`（控制台/WebUI 同一 agentService action `reloadModuleConfig`；基类做文件/XML 预检，validator 用 pending 快照原子替换，失败保留旧规则）
```

- [ ] **Step 3: 全量编译 + 三个自测**

```bash
cd D:/tlobjectapp/tlobject
/d/maven/bin/mvn -q -f pom.xml -pl core,aiagent/common -am -DskipTests compile
java -cp "core/target/classes;$(cat _cp_core.txt)" cn.tianlong.tlobject.base.TLConfigReloadSelfTest
java -cp "aiagent/common/target/classes;core/target/classes;database/target/classes;$(cat _cp_aiagent.txt)" cn.tianlong.tlobject.aiagent.TLOutputValidatorReloadSelfTest
java -cp "aiagent/common/target/classes;core/target/classes;database/target/classes;$(cat _cp_aiagent.txt)" cn.tianlong.tlobject.aiagent.TLOutputValidatorSelfTest
java -cp "aiagent/common/target/classes;core/target/classes;database/target/classes;$(cat _cp_aiagent.txt)" cn.tianlong.tlobject.aiagent.TLAgentServiceReloadConfigSelfTest
```

Expected: 四个自测退出码均为 0（旧 validator 自测 15/15 不回归）。

- [ ] **Step 4: 端到端手动验收（控制台）**

1. 启动应用：仓库根执行 `aistart.bat`（保持窗口运行）。
2. 编辑运行期配置 `demo/tlobject/target/classes/conf/demo/aiagent/outputValidator_config.xml`，增加一条规则（例如 `total<100`）。
3. 控制台执行 `/reloadconfig outputValidator`。
   Expected: `✓ 配置已重载: outputValidator（rules=N）`。
4. 触发一次 `calculate_price`（如让 Agent 计算总额 ≥100）。
   Expected: 工具结果返回 `输出校验失败：total < 100 不成立...`。
5. 把 XML 改成非法（例如删掉闭合标签），再执行 `/reloadconfig outputValidator`。
   Expected: `✗ 模块配置重载失败: ...`；重复第 4 步，旧规则仍然生效。
6. 修复 XML，重载恢复成功。

- [ ] **Step 5: 端到端手动验收（WebUI）**

1. 打开 WebUI 并登录。
2. 在命令输入框发送 `reloadModuleConfig`（或前端控制台等价入口），参数 `name=outputValidator`；
   等价 HTTP：`POST /api/command`，body `{"action":"reloadModuleConfig","params":{"name":"outputValidator"}}`。
   Expected: 返回 `{"success":true,"message":"配置已重载: outputValidator（rules=N）",...}`。

- [ ] **Step 6: 提交**

```bash
git add demo/tlobject/src/main/resources/conf/demo/aiagent/outputValidator_config.xml CLAUDE.md
git commit -m "docs(aiagent): 补充 /reloadconfig 模块配置热加载说明"
```

---

### Task 7: 最终回归

**Files:** 无新增

- [ ] **Step 1: 检查工作区**

```bash
cd D:/tlobjectapp/tlobject
git status --short
```

Expected: 仅剩本次改动；`_cp_core.txt`、`_cp_aiagent.txt` 是临时文件，删除：

```bash
rm -f _cp_core.txt _cp_aiagent.txt
```

- [ ] **Step 2: 再跑一遍完整自测**

运行 Task 6 Step 3 的四条 java 命令（必要时先重新生成 cp 文件），Expected: 全部退出码 0。

- [ ] **Step 3: 汇总**

记录：
- 新命令与 action；
- 三个自测结果；
- 控制台/WebUI 验收结果；
- 未覆盖边界（未实例化模块、子模块不重建、webui 权限沿用现状）。

---

## 已知边界（实现时不要越界）

- 基类预检只覆盖文件/XML 级错误，不运行子类自定义解析器；
- 不重建子模块、不跑 `init/runStartMsg`；
- `params` 删除项在基类层仍残留（validator 已对 `failClosedOnError` 复位）；
- 未实例化模块不隐式创建；
- WebUI 权限沿用现有 `/api/command` 口径（所有登录用户），本次不加管理员白名单。

## 实施结果（2026-11-01）

- Task 1 TLModuleConfig parseOk/isWellFormed：18 PASS
- Task 2 基类 reloadConfig 预检/结构化返回：36 PASS
- Task 3 TLOutputValidator 原子重载：36 PASS（既有 TLOutputValidatorSelfTest 15 PASS）
- Task 4 reloadModuleConfig 命令：15 PASS
- Task 5 控制台 /reloadconfig：编译通过
- Task 6 全量编译 + 四个自测通过（36 / 36 / 15 / 15）；控制台/WebUI 手动验收待执行
