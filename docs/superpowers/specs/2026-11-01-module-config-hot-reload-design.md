# 运行期模块配置热加载设计（通用命令 + TLOutputValidator）

- 日期：2026-11-01
- 状态：已实现（自测通过；控制台/WebUI 手工验收待执行）
- 涉及模块：`core`（TLBaseModule、TLModuleConfig）、`aiagent/common`（TLAgentService、TLChatConsole、TLOutputValidator）、`aiagent/webui`（仅复用现有命令通道）

## 1. 背景与目标

生产环境修改模块配置文件后需要重启应用。以 `TLOutputValidator` 为例：在 `outputValidator_config.xml` 里新增/调整 rules 后，必须重启才能生效，影响生产可用性。

目标：

1. 新增通用命令 `/reloadconfig <模块>`，对**已运行**的模块执行 `reloadConfig`，无需重启；
2. 首个接入模块 `TLOutputValidator`：新增规则原子生效；解析失败**保留旧规则并报错**；
3. 控制台与 WebUI 共用同一条 agentService 路径；
4. 不改变现有启动流程和未调用命令时的行为。

非目标（本次不做）：

- 文件监听自动重载（`TLMonitorConfigModule` 改造，二期可选）；
- 工厂实例重建与缓存引用重绑（如 `TLToolExecutor` 缓存的 `outputValidator` 引用）；
- 子模块级联重建；
- 配置语义级校验（如 `checks` 表达式是否合法，目前只在执行时暴露）。

## 2. 现状分析（关键结论）

### 2.1 `hotLoadModule` 不适用

`TLBaseModule.hotLoadModule`（`:1261`）是“动态声明并创建模块”：

- 写入当前模块的 `modulesClass/modulesParams`；
- `toFactory=true` 走工厂单例，`false` 走私有实例；
- 默认持久化 XML；
- **工厂已有同名单例时直接返回旧实例**，不会替换，也不会更新 agent 的 `functions`/registry/toolDefs。

`outputValidator` 是工厂单例（`conf/tlobject/cn.tianlong.tlobject.aiagent_config.xml:80`），调用它没有重载效果。

### 2.2 `reloadConfig` 可作载体，但有缺口

`TLBaseModule.reloadConfig`（`:1176`）：

```java
preReConfig();
params.putAll(moduleFactory.getModuleParam(name, null));
configure();   // setConfig → setSystemParams → initProperty → setfieldFromModule → reMakeProperty → setModuleParams
reload();      // params.reload=true 时让工厂重建实例并广播 RESETMODULE
reConfig();
return null;
```

`configure()` 中的 `setConfig()` 会重新从磁盘解析配置文件（`TLModuleConfig.parse` 每次 `new FileInputStream`，`TLModuleConfig.java:41-56`），因此**新增配置能被读到**。缺口：

| 缺口 | 影响 |
|---|---|
| 返回 `null` | 调用方无法知道成功/失败 |
| 不执行 `init()/runStartMsg()` | 启动期构建的运行时状态不重建 |
| 子模块不重建 | `modulesClass` 已替换，但 `modules` 中旧子实例仍在 |
| `params` 只 `putAll` 不清理 | 配置中删除的键会残留 |
| 解析失败先 `clear()` 再解析 | 规则可能为空/半套 → 校验静默失效（fail-open） |
| `TLModuleConfig.parse` 解析失败仍返回非 null | 异常在 `parseconfig` 内部被吞掉（`TLModuleConfig.java:282-292`），`init()` 忽略返回值直接 `return this`（`:46-56`）→ **不能靠 null 判断 XML 是否合法** |
| 无并发保护 | 重载与工具校验并发时可能 CME（低频管理员操作，可接受；快照是附带收益） |
| `params.reload=true` 走工厂重建 | `TLToolExecutor` 缓存的引用不会自动更新 |

### 2.3 现有 `TLMonitorConfigModule` 不可直接用于生产

- 用 `TLBaseModule.class.getResource(configFile)` 定位文件，绝对路径返回 null；
- `putMsg(moduleName, reloadMsg)` 在模块不存在时可能触发 `shutdown(-1)`；
- 无防抖、无“先解析成功再应用”的保护。

### 2.4 命令 action 名不能叫 `reloadConfig`

`TLBaseModule.runAction` 的 `switch` 中已有 `case MODULE_RELOADCONFIG: reloadConfig(...)`（`:1090`），会在 `checkMsgAction` 之前拦截。若 agentService 的命令 action 也叫 `reloadConfig`，会变成重载 agentService 自己的配置。**命令 action 定为 `reloadModuleConfig`。**

## 3. 决策记录

| 决策点 | 结论 |
|---|---|
| 触发方式 | 显式命令（不做自动文件监听） |
| 目标实例 | 仅已实例化模块；不隐式创建（未运行 = 首次创建时自然读新配置） |
| 重载语义 | 原地 `reloadConfig`，不做工厂实例重建 |
| 失败策略 | 保留旧配置 + 结构化报错 |
| 基类通用性 | `reloadConfig` 先做**文件/XML 级预检**（失败不进入 `configure()`，旧状态原样保留）+ 结构化返回；预检不运行子类自定义解析器，子类语义仍由模块自己的重载逻辑保证 |
| 解析状态可观测 | `TLModuleConfig` 增加 `parseOk`/`parseError`，供预检与调用方判断解析是否成功 |
| validator | 临时表解析、成功才原子替换、`reloadConfig` 返回规则数与错误 |
| 命令 action | `reloadModuleConfig`（避开基类 `reloadConfig`） |
| 控制台命令 | `/reloadconfig <模块名>`（别名 `/rcfg`） |
| WebUI | `/api/command {"action":"reloadModuleConfig","params":{"name":"..."}}` |
| 权限 | 沿用现状（登录用户可执行），后续可加管理员白名单 |

## 4. 总体链路

```
控制台 /reloadconfig outputValidator
WebUI  /api/command {action:"reloadModuleConfig", params:{name:"outputValidator"}}
        │
        ▼
TLAgentService.doReloadConfig
  ├─ 解析目标：registry 精确家族名 → 工厂已存在实例（getModuleInFactory）
  ├─ 未运行/不存在 → 明确报错，不创建
  ├─ putMsg(target, {action:"reloadConfig"})，IGNOREMODULEISNULL=true
  └─ 返回 {success, message, data:{module,file,rules?}}
        │
        ▼
目标模块 reloadConfig（基类）
  └─ TLOutputValidator 覆写：临时表解析 → 成功原子替换 → 返回 {RESULT,rules,file} / {RESULT:false,error}
```

## 5. 基类改动

### 5.1 TLModuleConfig：解析状态可观测

- 增加 `parseOk`（默认 `true`）与 `parseError`，在 `parseconfig` 的三个 catch 分支置 `false` 并记录原因（`:282-292`）；
- 增加 `isParseOk()` / `getParseError()`；
- `parse()` 在文件不可读/不存在时返回 null，并置 `parseOk=false` + `parseError`；
- 增加 `isWellFormed()` 严格检查：标签栈配对 + 恰好一个根元素 + 根外无文本；覆盖 kxml2 “EOF 处未闭合标签不抛异常”的截断文件场景；不修改 `parseOk/parseError`；
- 不改 `parse()` 的返回对象语义（成功时仍返回 `this`），避免影响既有调用方。

> 为什么必须加这个：`TLModuleConfig.parse()` 在 XML 解析异常时仍返回非 null（`init()` 忽略 `parseconfig` 的返回值直接 `return this`，`TLModuleConfig.java:46-56`），只检查 null 无法发现坏 XML；而 kxml2 对 EOF 处未闭合的标签连异常都不抛，需要 `isWellFormed()` 的严格检查兜底。

### 5.2 TLBaseModule.reloadConfig

1. **文件/XML 级预检（通用）**：在 `configure()` 之前，先按 `setConfig()` 相同的规则解析出配置文件路径（抽 `resolveConfigFile()` 辅助方法，避免两处逻辑分叉），用**临时 `TLModuleConfig`**（默认 `myConfig`，**不运行子类自定义解析器**）解析：
   - `moduleFactory != null` 时给 probe `setFactory(moduleFactory)`（否则含 `<include>` 的配置会因拿不到 `getConfigRealPath` 而 NPE）；
   - 先 `!probe.isWellFormed()` → 失败（拦截截断/空文件/多根/根外文本）；
   - 再检查 `probe.parse(parseFile) != null && probe.isParseOk()`；
   - 任一步失败 → 返回 `{RESULT:false,error}`，**不进入 `configure()`**，旧状态原样保留；
   - **预检只覆盖文件可读 + XML 结构合法**；未知/自定义标签（如 `<rules>`）会被默认解析器忽略（`TLModuleConfig.java:272-276`），`<rules>` 的语义正确性由 validator 自己的重载逻辑保证；
   - `<include>` 失败（无工厂/路径解析失败/缺失/损坏/嵌套失败）会传播为父配置 `parseOk=false` 并打 ERROR 日志，预检失败；
   - `resolveConfigFile()` 返回候选路径（不保证存在）：自动派生文件缺失时预检失败、不进入 `configure()`（避免 validator 把未解析配置当成功而清空规则）；
   - 无配置文件（`configFile` 为空且 `autoConfig=false`）时跳过预检，仍执行 `configure()`（模块可能只靠内存参数）。

2. `reloadConfig` 返回值从 `null` 改为结构化，并捕获异常：

```java
protected TLMsg reloadConfig(Object fromWho, TLMsg msg) {
    try {
        String parseFile = resolveConfigFile();
        if (parseFile != null) {
            String configDir = moduleFactory != null ? moduleFactory.getConfigDir() : "";
            TLModuleConfig probe = new TLModuleConfig(parseFile, configDir);
            if (moduleFactory != null)
                probe.setFactory(moduleFactory);
            String probeError = null;
            if (!probe.isWellFormed())
                probeError = "配置文件不可读或 XML 不合法: " + parseFile;
            else {
                TLModuleConfig parsed = probe.parse(parseFile);
                if (parsed == null)
                    probeError = "配置文件不可读: " + parseFile;
                else if (!parsed.isParseOk())
                    probeError = "配置解析失败: " + parseFile
                            + " (" + parsed.getParseError() + ")";
            }
            if (probeError != null) {
                putLog(name + " " + probeError, LogLevel.ERROR, "reloadConfig");
                return createMsg().setParam(RESULT, false).setParam("error", probeError);
            }
        }
        preReConfig();
        if (moduleFactory != null) {
            HashMap<String, String> cparams = moduleFactory.getModuleParam(name, null);
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

3. 保留原有流程与 `preReConfig/reConfig` 钩子；`moduleFactory == null` 时跳过工厂参数合并与 `setFactory`，但**显式 configFile 仍可预检**（便于单测；含 `<include>` 的配置在没有工厂时无法解析，会返回预检失败）。
4. 在方法 Javadoc 中写明边界：不跑 `init()/runStartMsg()`、不重建子模块、`params` 删除项不清理、`params.reload=true` 的工厂重建路径不保证缓存引用更新；预检只覆盖文件/XML 级错误，子类语义与 `configure()` 中途异常后的完整回滚由模块自己保证。
5. 不改 `hotLoadModule`。

## 6. TLOutputValidator 改动

### 6.1 规则快照

- `rules` 改为 `volatile List<Rule>`，只存不可变快照；
- `matchRule` 开头取本地快照再迭代，避免读到半套规则/CME；
- 并发不是本需求的主要动机（管理员低频操作），快照是低成本附带收益。

### 6.2 原子重载

基类预检已挡住“文件不可读/坏 XML”这一层；这里的 pending 交换是子类层的第二道防线，负责 `<rules>` 语义解析错误（`parseRules` 内部异常）和规则计数。

- `setConfig()` 只解析到临时 `pendingRules`，记录 `parseError`，**不直接改 `rules`**；
- `super.setConfig()` 返回后检查 `mconfig == null || !mconfig.isParseOk()`（覆盖绕过基类预检的直接调用路径），失败则记 `parseError`；
- 启动路径：`init()` 里若解析成功（且 `mconfig != null`）才 `rules = List.copyOf(pendingRules)`；
- 重载路径：覆写 `reloadConfig()`，调用 `super.reloadConfig()` **返回 RESULT=true 后**才应用 `pendingRules`——保证 `configure()` 全流程成功才换表，后续步骤抛异常也不会留下半新半旧的状态；
- 解析失败或 `configure()` 失败：保留旧 `rules`，写 ERROR 日志（文件名 + 原因）；
- `setModuleParams()` 每次先把 `failClosedOnError` 复位为默认 `false`，再读配置，避免删除配置项后残留。

### 6.3 重载结果

覆写 `reloadConfig()`：

```java
protected TLMsg reloadConfig(Object fromWho, TLMsg msg) {
    TLMsg r = super.reloadConfig(fromWho, msg);   // 基类已捕获异常并返回 RESULT
    if (r == null || !r.parseBoolean(RESULT, false)) {
        // 旧规则继续生效
        return createMsg().setParam(RESULT, false)
                .setParam("error", lastReloadError != null ? lastReloadError : "重载失败");
    }
    rules = List.copyOf(pendingRules);            // 全流程成功才替换
    return createMsg().setParam(RESULT, true)
            .setParam("rules", rules.size())
            .setParam("file", configFile);
}
```

- `<rules>` 为空是合法配置（= 全部放行），但结果必须明确 `rules=0`，避免静默全放行不可见；
- 重载串行（validator 内加锁），校验线程只读快照，不阻塞工具执行。

### 6.4 可测试性

把“解析文件 → 生成规则列表”和“替换快照”拆成包级可见方法（不依赖 `moduleFactory`），供自测直接调用；`setConfig/reloadConfig` 复用同一方法。

## 7. 命令层

### 7.1 agentService

- `ACTION_REGISTRY` 增加 `reloadModuleConfig`；
- `checkMsgAction` 增加 `case "reloadModuleConfig"`；
- `doReloadConfig`：
  - `name = msg.getStringParam("name", msg.getStringParam("module", null))`；
  - 解析顺序：`findAgentInstance(name)`（registry 精确家族名）→ `getModuleInFactory(name)`（工厂已存在实例）；
  - 都找不到 → `fail("模块未运行或不存在: " + name + "（未实例化的模块会在首次创建时读取新配置）")`；
  - `target.getConfigFile() == null` → `fail("模块未加载配置文件: ...")`（无配置文件可重载，避免把“缺失自动配置”报成底层的“不可读”）；
  - `putMsg(target, cmd)`，`IGNOREMODULEISNULL=true`，捕获异常；
  - 返回值：
    - 模块返回 `RESULT=true` → `ok("配置已重载: name" + [rules=N])`；
    - 模块返回 `RESULT=false` → `fail(error)`；
    - 返回 `null` → `ok("已发送重载请求（模块未返回结果）")` 并打 WARN（兼容旧模块）。

### 7.2 控制台

- `parseCommand` 增加 `case "reloadconfig"` / `case "rcfg"`：`setAction("reloadModuleConfig").setParam("name", parts[1])`；
- 帮助文本加入 ` /reloadconfig <模块>  重载模块配置（如 outputValidator）`；
- 结果渲染沿用 `install/uninstall/reload` 分支：`✓ message` / `✗ error`。

### 7.3 WebUI

- 无需前端改动：`/api/command` 已把任意 action 转发 agentService；
- 前端可发送 `{"action":"reloadModuleConfig","params":{"name":"outputValidator"}}`；
- 如需按钮，后续单独加。

### 7.4 权限

- 沿用现状：WebUI 登录用户均可执行（现有 install/uninstall/reload 同口径）；
- 设计上标注这是管理员级操作；后续可加 userId 白名单或管理员角色。

## 8. 错误处理与日志

| 情况 | 行为 |
|---|---|
| 模块未运行/不存在 | 命令返回失败，不创建、不 shutdown |
| 配置文件不存在 | validator 返回失败，旧规则保留 |
| XML 解析失败 | validator 返回失败，旧规则保留，ERROR 日志 |
| 规则解析中途异常 | 不替换快照，旧规则保留 |
| 成功 | INFO：`输出校验规则已重载: N 条 (file)` |
| 模块返回 null | 命令按“已发送”处理并 WARN（兼容未改造模块） |

## 9. 测试与验收

### 9.1 单元（TLOutputValidator + 基类预检）

- 基类预检：坏 XML/截断 XML/空文件时 `configure()` 不被调用、旧状态不变（用一个测试模块验证）；含 `<include>` 的配置在传入 factory 时预检通过；
- `TLModuleConfig`：坏 XML/缺文件时 `isParseOk()==false` 或 parse 返回 null；合法 XML（含未知标签、UTF-8 BOM）为 true；截断/空/仅声明/多根/根外文本时 `isWellFormed()==false`；
- 合法新配置 → 重载后新规则生效（rules 条数增加，命中新规则失败）；
- 坏 XML → 返回失败，旧规则仍生效；
- 空 `<rules>` → 成功且 `rules=0`；
- 并发校验 + 连续重载 → 无异常、结果只可能是新旧两套之一；
- 删除 `failClosedOnError` 后重载 → 恢复默认 false。

### 9.2 命令层

- 未运行模块 → 明确错误；
- 不存在模块 → 明确错误；
- 无配置文件模块 → 明确错误（`getConfigFile()==null`）；
- 成功模块 → 返回 `rules` 条数；
- 控制台与 WebUI 各验证一次。

### 9.3 端到端验收

1. 启动应用，确认 `calculate_price` 的 `total<100` 规则生效；
2. 修改 `outputValidator_config.xml` 增加一条规则；
3. 执行 `/reloadconfig outputValidator` → 返回 `rules=N`；
4. 下一次工具调用按新规则校验，无需重启；
5. 写坏 XML 再重载 → 返回失败；旧规则仍然生效。

## 10. 已知边界

- 只重载模块自身配置；子模块/私有实例不重建；
- `params` 中已删除的键在基类层仍会残留（validator 已对自身参数复位，其他模块需自行处理）；
- 重复重载含 `<initMsg>/<startMsg>` 的配置沿用 `mconfig` 复用行为，列表可能累积（既有边界，本次不修）；
- 无配置文件/自动派生文件缺失的模块重载返回失败（命令层给出“模块未加载配置文件”的友好错误）；
- 未实例化模块无法“重载”，但其首次创建会读取新配置；
- 命令可作用于任意模块，但原子性/失败回滚由各模块自己保证（validator 已实现，其他模块沿用基类语义）；
- 基类预检只覆盖文件/XML 结构错误（含截断/空文件/多根/根外文本），**不运行子类自定义解析器**；子类语义错误、`configure()` 中途异常后的完整回滚未引入快照/恢复契约（如需对所有模块做完整事务，需要额外的 `snapshotForReload/restoreForReload` 钩子，本次不做）；
- `params.reload=true` 的工厂重建路径与缓存引用重绑不在本次范围。

## 11. 发布与回滚

- 除 `<include>` 严格化（见下）外，未执行命令时行为不变；
- 回滚 = 回退代码，无配置迁移；
- 首次上线建议先在测试环境验证控制台与 WebUI 两条入口，再开放给管理员。
- `<include>` 现要求目标为单根、严格合法的 XML；仓库内配置已全部验证，第三方 fragment 风格 include 需先调整。
