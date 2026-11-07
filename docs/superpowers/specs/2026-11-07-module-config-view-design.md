# 模块配置/运行参数查看（/config、/params）设计

- 日期：2026-11-07
- 状态：已实现，自测 70/70 + 52/52、WebUI 静态检查 40/40
- 相关：`2026-11-06-control-center-design.md`、`2026-11-07-reloadconfig-list-design.md`

## 1. 背景与目标

`/reloadconfig` 已能重载模块配置并列出可重载模块，但重载后无法确认"模块到底吃进了什么配置"。目标：

1. `/config <模块名>` 查看模块配置文件原文及元信息；
2. `/params <模块名>` 查看模块内存运行参数；
3. 两者都附最近一次重载审计，便于对账"文件改了且重载成功"；
4. 安全限制放在控制中心：用模块级白名单控制可查看范围，不依赖字段名脱敏。

## 2. 关键决策

- **消息传递，不直接调方法**：读取动作挂在 `TLBaseModule.runAction` 的框架动作层。该层是基类公共动作统一入口（与 `getParam`、`reloadConfig` 同级），所有模块天然支持；`checkMsgAction` 是具体模块个性动作，`TLOutputValidator`/`TLAiAgent` 等对未知动作返回 null 且不 delegate，不能放这里。
- **白名单 fail-closed**：`viewableModules` 显式列允许查看的模块；空 = 全部禁止；不支持 `*`（配置为 `*` 时 WARN 并按全部禁止）。命中请求名 / `familyName` / `name`。
- **配置与运行参数分属两个命令**；一份白名单同时约束两者。
- **旧的 `/param` 更名 `/toolparam`**：它只查注册表内 Agent/Skill/Group，保留为旧的、不受白名单约束的入口（WebUI Agent/Skill 的"参数"按钮仍走 `param` action）。新的 `/params` 才受控。
- **不脱敏**：可见性完全由模块级白名单决定；审计只记元数据，不写配置内容/参数值。

## 3. 接口契约

### 3.1 基类新增框架动作

`TLParamString`：`MODULE_GETCONFIGFILE = "getConfigFileInfo"`。

`TLBaseModule.runAction`：

```java
case MODULE_GETCONFIGFILE:
    returnMsg = getConfigFileInfo(fromWho, msg);
    break;
```

`getConfigFileInfo` 返回：

```
result=true
configFile, size, modifiedAt(epoch ms), sha256, content, truncated
```

- 无 `configFile` → `result=false + error="模块未加载配置文件"`；
- 文件不存在/不可读 → 失败；
- 文件 > 8 MiB → 拒绝读取；内容只展示前 256 KiB，`truncated=true`；
- SHA-256 按完整文件计算；UTF-8；去掉开头 BOM。

运行参数复用现有 `MODULE_GETPARAM`（同样在 `runAction` 里，无参数名时返回全部 `params`）。

`TLXmlConfigWriter.readFileBytes(String)`：只读原始字节，复用 `normalizePath`，不备份、不改文件。

### 3.2 控制中心（core）

新增配置参数：`<params><viewableModules value="outputValidator;..."/></params>`。

新增 action：

| action | 处理 |
|---|---|
| `configView` | 解析模块 → 白名单 → 发 `getConfigFileInfo` → 附 `lastReload` → 审计 → 返回 |
| `paramsView` | 解析模块 → 白名单 → 发 `getParam` → 附 `lastReload` → 审计 → 返回 |

- 模块解析复用 `resolveReloadTarget`（registry 精确家族名 → 工厂已存在实例），未实例化直接失败。
- 审计：`action=configView/paramsView`、`allowed`、`success`、`result`（"内容 N 字节"/"参数 N 个"/拒绝原因）；**不记内容与参数值**。
- `lastReload`：审计环中最近一条 target 匹配的 `reloadModuleConfig` 记录（time/userId/allowed/success/result）。
- `reloadableList` 每个条目新增 `viewable` 布尔值。
- 无控制中心时，服务层直接失败："控制中心未配置：查看需要 viewableModules 白名单"。

### 3.3 AI 服务（aiagent）

新增公开 action `configView` / `paramsView`，委托控制中心，成功返回：

```
success=true
message=控制台多行文案
data= configView: {module, familyName, configFile, size, modifiedAt, sha256, content, truncated, lastReload?}
      paramsView: {module, familyName, params, lastReload?}
```

`ACTION_REGISTRY`：`param` 键改为 `toolparam`（补全不再出现旧命令）；新增命令形态 `config`、`params`。

### 3.4 控制台

| 命令 | 行为 |
|---|---|
| `/config <模块名>`（别名 `/cfg`） | `configView`，受白名单 |
| `/params <模块名>` | `paramsView`，受同一白名单 |
| `/toolparam <家族名>` | 旧 `/param` 逻辑，查注册表内 Agent/Skill/Group，不受白名单 |
| `/param` | 打印更名提示，不发消息 |

`/config` 输出：模块、配置文件路径、修改时间、大小 + SHA-256、最近重载、`--- 文件内容 ---` 原文；超限/含 `<include>` 时追加说明。
`/params` 输出：模块、最近重载、按键名排序的 `key = value`；无参数为成功态"无运行参数"。

### 3.5 WebUI

- 设置组「配置」Tab 表格操作列：`重载`、`查看配置`、`查看参数`。
- `viewable=false` 时两个查看按钮禁用（title 提示白名单）。
- 点击查看 → `apiCommand('configView'|'paramsView')`，复用 `#paramModal`，正文用 `<pre class="out">` 展示；失败在弹框内显示 `✗ error`。
- 已知边界：该 Tab 只列可重载模块，可查看但不可重载的模块用控制台 `/config`。

## 4. 错误与边界

| 场景 | 结果 |
|---|---|
| 白名单空 / `*` | success=false（fail-closed） |
| 模块未实例化 | 失败"模块未运行或不存在" |
| 无配置文件 | `/config` 失败；`/params` 仍可用 |
| 文件不可读/过大 | `/config` 失败并说明 |
| 模块未响应 | 失败"模块未响应查看请求" |
| 控制中心未配置/无响应/格式错误 | 失败并透传/说明 |
| 内容 >256 KiB | 成功 + 截断标记 |
| 含 `<include>` | 成功 + 未展开提示 |

## 5. 测试与验证

- `TLControlCenterSelfTest`：64/64（含查看成功/拒绝/无配置/参数/`viewable` 标记/审计/lastReload/通配符 fail-closed）。
- `TLAgentServiceReloadConfigSelfTest`：52/52（含委托、文案、排序、失败透传、无控制中心、格式错误）。
- `smoke/webui_panel_check.py`：40/40（含配置 Tab 三个按钮、查看配置/参数弹框内容、空名单提示）。
- `node --check app.js` 通过；`mvn -pl core,aiagent/common,aiagent/webui -am test-compile` 通过。

## 6. 改动文件

| 文件 | 改动 |
|---|---|
| `core .../TLParamString.java` | 新增 `MODULE_GETCONFIGFILE` |
| `core .../TLBaseModule.java` | `runAction` case + `getConfigFileInfo` |
| `core .../utils/TLXmlConfigWriter.java` | `readFileBytes` |
| `core .../modules/TLControlCenter.java` | `viewableModules`、`configView`/`paramsView`、lastReload、审计、`viewable` 标记 |
| `core .../TLControlCenterSelfTest.java` | 查看/白名单/审计用例 |
| `aiagent .../TLAgentService.java` | `configView`/`paramsView`、文案、ACTION_REGISTRY |
| `aiagent .../TLAgentServiceReloadConfigSelfTest.java` | 查看委托用例 |
| `aiagent .../TLChatConsole.java` | `/config`、`/params`、`/toolparam`、帮助 |
| `aiagent/webui/.../app.js`、`chat.html` | 行内查看按钮 + 弹框 |
| `smoke/webui_panel_check.py` | 查看按钮/弹框断言 |
| 三套 `controlCenter_*_config.xml` | `viewableModules` |
| `CLAUDE.md` | 控制中心段落更新 |

## 7. 已知边界

- **白名单以控制中心配置文件为权威**：`/reloadconfig controlCenter` 时重新解析控制中心自己的配置文件，新增/修改/删除白名单项都生效；不受框架 `reloadConfig` "只合并不清理"影响（该语义仍适用于其它普通模块的 params）。
- 白名单只管新命令；`/toolparam` 与 WebUI Agent/Skill"参数"按钮是旧路径，不受约束（按需求保留）。
- 配置原文中的 `<include>` 不展开；只看本文件。
- 可查看但不可重载的模块不在 WebUI「配置」Tab 列表中。
- 查看内容不做脱敏，依赖模块级白名单控制可见性。
