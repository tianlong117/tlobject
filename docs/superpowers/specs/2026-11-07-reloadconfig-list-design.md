# 可重载配置模块查询（/reloadconfig 无参列表）设计

- 日期：2026-11-07
- 状态：已实现（2026-11-07），自测 46/46 + 39/39、WebUI 静态检查 36/36
- 相关：控制中心一期（`2026-11-06-control-center-design.md`）

## 1. 背景

`/reloadconfig <模块名>`（别名 `/rcfg`）可原地重载模块配置，但用户不知道哪些模块可重载：

- 白名单在控制中心配置 `<params><reloadableModules value="outputValidator"/></params>`，分号分隔，界面上不可见；
- 无参 `/reloadconfig` 当前只打印用法（`TLChatConsole.java:1208`）；
- 白名单里的名字可能已失效（模块未实例化 / 无配置文件），仅看配置串无法判断"现在能不能重载"。

同时，现有门禁支持 `reloadableModules="*"` 全放行，等于把"哪些配置可以被任意重载"的决定权交给调用方，误操作风险大。本设计一并移除通配符。

## 2. 目标与非目标

**目标**

1. 无参 `/reloadconfig`（`/rcfg`）列出白名单条目及其实时可用状态。
2. WebUI 设置组新增「配置」Tab，展示同一份数据，支持行内重载。
3. 移除 `*` 通配符：白名单只接受显式模块名，`*` 按"全部禁止"处理（fail-closed）。
4. 成功与失败都有明确回执，控制台与 WebUI 使用同一份结构化结果。

**非目标**

- 不枚举工厂内全部模块（当前 `TLObjectFactory` 无公开枚举接口），因此 `*` 无法展开成列表——这也是移除通配符的附带收益。
- 不做 WebUI 手输模块名重载（白名单可见后无必要）。
- 不做角色门禁（与 `/audit` 同级，登录即可查看）；如以后需要，在 `TLAgentControlCenter` 覆写钩子，core 不感知。
- 不改变控制中心自身配置的热加载方式。

## 3. 现状与约束

- 门禁判定在 core `TLControlCenter.isReloadAllowed(requestedName, target)`：白名单按 `;` 拆分、`trim`，命中请求名 / `familyName` / `name` 任一即放行；当前另有 `*` 全放行分支。
- 可重载的隐含条件：模块已实例化（`resolveReloadTarget` 非空）且 `getConfigFile() != null`。
- `TLAgentService.doReloadConfig` 在有控制中心时委托 `reloadModuleConfig`；无控制中心时走本地直接重载。
- 控制台动作分发在 `TLChatConsole`；WebUI 所有命令走 `/api/command` → `TLWebChatModule.handleCommand` → `TLAgentService`。

## 4. 接口设计

### 4.1 控制中心：`reloadableList`（新增，只读）

请求：无参数。

成功响应：

```
result    = true
mode      = "list" | "none"          // none = 白名单为空/仅 *（全部禁止）
whitelist = ["a", "b"]               // 解析后的名单：split(";") → trim → 跳空 → 去重，保持顺序
data      = [                        // mode=list 时与 whitelist 同序同长；mode=none 时为空
  {
    "name": "a",                     // 白名单原文
    "familyName": "cc:a",            // 解析到的家族名；未运行时为 ""
    "running": true,                 // 是否已实例化
    "configFile": ".../a_config.xml",// 未加载时为 ""
    "reloadable": true,              // 现在能否直接重载成功
    "reason": ""                     // 不可重载原因
  }
]
```

失败响应：`result=false` + `error`（控制中心不可用/内部异常/格式错误）。

判定规则（与 `reloadModuleConfig` 的既有门禁同源）：

| 情况 | running | reloadable | reason |
|---|---|---|---|
| 已实例化 + `configFile != null` | true | true | "" |
| 未实例化 | false | false | 模块未运行或不存在（未实例化的模块会在首次创建时读取新配置） |
| 已实例化但 `getConfigFile()==null` | true | false | 模块未加载配置文件 |

只读查询不写审计。

### 4.2 AgentService：`reloadableModules`（新增，公开）

- 有控制中心：转发 `reloadableList`，把结果映射为 UI 契约；同时生成控制台可读的多行 `message`。
- 无控制中心：`success=false`，error=`控制中心未配置：直接模式无法枚举，任何已实例化且带配置文件的模块均可直接重载`。
- 控制中心无响应 / 返回失败 / 格式错误：`success=false` + 透传或如实说明的 error。
- 加入 `ACTION_REGISTRY`：`列出可重载配置的模块及实时可用状态`。

成功响应（供控制台与 WebUI 共用）：

```
success = true
message = 多行控制台文案
data = {
  "mode": "list" | "none",
  "whitelist": ["a", "b"],
  "modules": [ ...同 4.1 的 data 元素... ]
}
```

`data` 做成对象而非数组，保证 WebUI 能同时拿到 mode/whitelist；`message` 只用于控制台。

### 4.3 控制台

- `/reloadconfig`、`/rcfg` 无参数 → 发送 `action=reloadableModules`，成功时原样打印 `message`，失败走 `✗ <error>`。
- 带模块名 → 行为不变（`action=reloadModuleConfig`，name=第一个参数）。
- 帮助文本更新：`/reloadconfig [模块名]  无参=列出可重载配置模块；带模块名=重载配置（不重启）`。
- 文案：

```
可重载配置模块（白名单 2 个）:
  ✓ outputValidator         可重载    .../outputValidator_config.xml
  ✗ someOtherModule         不可用    模块未运行或不存在（未实例化的模块会在首次创建时读取新配置）
```

白名单为空或 `*`：

```
没有可重载配置的模块（白名单为空；请在控制中心配置文件的 reloadableModules 中配置）
```

### 4.4 WebUI：设置组新增「配置」Tab

- Tab：`<button data-tab="tab-config" data-group="settings">配置</button>`，与 Agent/Skill、MCP 并列。
- 面板：顶部「刷新」按钮 + 列表容器；切到 Tab 时自动加载。
- 数据源：`apiCommand('reloadableModules', {})`。
- `success=false` → 面板显示 `✗ <error>`，不清成白板。
- `mode=none` → 显示"没有可重载配置的模块"提示。
- `mode=list` → 表格列：模块（name，familyName 不同则附注）、状态（✓ 可重载 / ✗ reason）、配置文件、操作。
- 「重载」按钮：`apiCommand('reloadModuleConfig', {name})`；成功 toast 后刷新列表；失败 toast 且保留当前列表。

## 5. 移除 `*` 通配符

- `isReloadAllowed` 删除 `"*"` 全放行分支。
- `init()` 中若白名单 trim 后恰为 `*`：打 WARN
  `reloadableModules 不支持通配符 *，已按"全部禁止"处理；请显式列出模块名`
  并将白名单置空（fail-closed）。
- 非纯 `*` 的条目（如 `"*;a"`）按字面名字处理：`*` 不会解析到任何模块，列表里如实显示为"未运行"。
- 同步更新 `CLAUDE.md`：删去"`*` 全放行"，改为"不支持通配符（`*` 按全部禁止处理）"。
- 仓库现有配置均未使用 `*`（web：`outputValidator`；aitest：空），无部署兼容问题。

## 6. 错误与边界

| 场景 | 结果 |
|---|---|
| 白名单 null / 空串 / 全空白 | success=true，mode=none，提示"没有可重载配置的模块" |
| 白名单 `*` | 启动 WARN + 白名单置空；查询返回 mode=none |
| 列表条目未实例化 | reloadable=false，reason 说明首次创建会读新配置 |
| 列表条目无 configFile | reloadable=false，reason 说明无配置文件 |
| 控制中心未配置 | success=false + error |
| 控制中心无响应 | success=false + error |
| 控制中心返回失败 | success=false + 透传 error |
| 控制中心返回格式错误 | success=false + "控制中心返回格式错误" |
| `reloadableList` 内部异常 | catch 后 success=false + 异常摘要（不向上抛，避免框架异常路径关停应用） |

- 查询不加锁：白名单 init 后不变；模块实例并发创建，返回的是即时状态。
- 列表查询不写审计；真正的重载仍由 `reloadModuleConfig` 记录 `allowed/success`。

## 7. 测试与验证

**单元自测（runnable main，仓库惯例）**

`TLControlCenterSelfTest`：

- 三种条目状态（可重载 / 未运行 / 无配置）；
- 解析：`" a ; b ;; a "` → `["a","b"]`，顺序保持、去重；
- familyName 别名条目；
- `mode=none`：空串、全空白、`*` 三种输入；
- `*` 门禁：init 后白名单为空，`isReloadAllowed` 返回 false（替换原"通配符放行"用例）；
- 列表查询前后 `auditRecords` 数量不变。

`TLAgentServiceReloadConfigSelfTest`：

- 委托 action 为 `reloadableList`；
- 成功映射（data/message/mode/whitelist）；
- 控制中心失败透传；
- 无控制中心 / 无响应 → 失败。

**真机验证**

- 控制台：`/reloadconfig` 列出 `outputValidator`；`/reloadconfig outputValidator` 可重载；`/audit` 可见记录。
- WebUI：⚙ → 配置 Tab 自动加载；行内重载成功/失败（改坏配置）各一次；失败时列表仍在；aitest（空白名单）显示"没有可重载配置的模块"。
- 回归：`mvn -f pom.xml -pl core,aiagent/common,aiagent/webui -am -DskipTests package`，并运行两个 SelfTest 的 `main`。

## 8. 改动文件

| 文件 | 改动 |
|---|---|
| `core/src/main/java/cn/tianlong/tlobject/modules/TLControlCenter.java` | 删 `*` 分支；`init` fail-closed；新增 `reloadableList` |
| `core/src/test/java/cn/tianlong/tlobject/modules/TLControlCenterSelfTest.java` | 上述用例 |
| `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAgentService.java` | 新增 `reloadableModules`；`ACTION_REGISTRY` |
| `aiagent/common/src/test/java/cn/tianlong/tlobject/aiagent/TLAgentServiceReloadConfigSelfTest.java` | 委托/失败用例 |
| `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLChatConsole.java` | 无参列名单；帮助文案 |
| `aiagent/webui/src/main/resources/webui/chat.html` | 新增「配置」Tab 与面板 |
| `aiagent/webui/src/main/resources/webui/app.js` | 自动加载、表格渲染、行内重载 |
| `CLAUDE.md` | 控制中心段落更新（去 `*`，补查询能力） |

无新增依赖，无配置格式变化。
