# 用户 workspace 与路径访问策略设计

日期：2026-11-07
状态：已与用户确认设计，待评审

## 1. 背景与问题

2026-11-07 会话 `webchat_tianlong_1794020965643` 中，主 agent 委托 `codingAgent` 分析美股并生成 Word 文档。产出的
`_election_tech_src1~4.txt`、`_build_election_tech_doc.py`、`美国中期选举后科技股大跌原因分析.docx` 全部落在仓库根目录
`D:\tlobjectapp\tlobject\`。

事实核对（trace `data/tianlong/traces/webchat_tianlong_1794020965643/r_1794029374061.jsonl`）：

- 主 agent 的委托任务里明确写了“在 `D:\tlobjectapp\tlobject\` 生成 Word 文档”；
- 子 agent 用 `file_operation`（`allowedRootPath="."`，即仓库根）写文本片段与脚本；
- 再用 `code_execution`（子进程 CWD = 仓库根，无路径限制）生成 docx；
- 主 agent 之所以带根目录路径，是长期记忆里召回了历史会话中“订单/Excel 写到 `D:\tlobjectapp\tlobject\`”的“老规矩”——
  那是一次性路径，被模型泛化成了通用规则。

同时确认：

- 2026-10-28 的 workspace 限制**只加在 MCP filesystem**（`filesystemMcp*` 的 `args` 根改为 `workspace`），
  `file_operation`、`code_execution`、`script_execution`、`word` 从未纳入；
- `workspace/` 是单一共享目录（`poet.txt`、订单文件都在根），没有 `{userId}` 隔离。

用户确认的新目标：

1. 正常文件读写默认在用户自己的 workspace 里；
2. 用户在对话里显式指定其他目录时也要能操作，不做一刀切硬限制；
3. workspace 按用户隔离；
4. 越界访问（读、写、执行都算）需要人工审批；跨用户访问直接拒绝，不弹审批。

## 2. 目录约定

- 用户工作目录：`<CWD>/workspace/{userId}/`
  - `{userId}` 经既有 `TLPathSafety.safeSegment` 净化；
  - 目录不存在时按需创建；
  - 基础根可用系统属性 `tlobject.workspaceRoot` 覆盖（默认 `workspace`，测试与特殊部署使用）。
- 用户自己的数据区（视为可信区）：
  - `data/{userId}/`
  - `data/documents/{userId}/`
- 跨用户保护区：
  - `workspace/{其他 userId}/...`
  - `data/{其他 userId}/uploads|attachments|longterm_memory|tasks|traces|session_store|scheduled_tasks/...`
  - `data/documents/{其他 userId}/...`
  - 注：workspace 根下的单段遗留文件/不存在的单段路径不算跨用户，按“workspace 之外”走审批（见第 5.1 节与实施偏差记录）。
- 其余路径：仓库源码、`D:\Reports` 等，可访问但需审批。

## 3. 路径访问矩阵

| 目标路径 | 读 | 写/改/删 | 执行 |
|---|---|---|---|
| 自己的 `workspace/{uid}` | 放行 | 放行 | 子进程 CWD 默认这里；执行本身另审 |
| 自己的 `data/{uid}`、`data/documents/{uid}` | 放行 | 放行 | — |
| 其他用户的 workspace/data | 直接拒绝 | 直接拒绝 | — |
| 其他任意路径 | 审批 | 审批 | — |

补充规则：

- 跨用户是硬拒绝：返回明确错误（“无权访问其他用户目录”），不创建审批请求。
- 越界审批复用 `approvalGate`，新增动态规则 `path_access`；审批参数携带 `operation`、`path`、`tool`，
  审批框沿用现有“仅本次 / 本轮免审 / 本会话免审”。授权键为 `(session, scope, path_access, operation)`，
  与现有授权机制一致。**含义**：选“本轮/本会话免审”后，该 op（read/write/delete）在授权范围内对所有越界路径
  都不再弹框；要逐路径控制就选“仅本次”。更细粒度的按路径授权不在本次范围内。
- `code_execution` 与 `script_execution` 每次执行都审批（“执行即审批”），子进程 CWD 指向 `workspace/{uid}`。
- 审批服务不可用时保持 fail-closed（现有语义）。

## 4. 各工具默认行为

### 4.1 `file_operation`（`TLFileOperationSkill`）

新增参数 `workspaceMode`（默认 `false`，保持旧部署行为）。

开启后：

- 相对路径按 `workspace/{uid}` 解析；
- 绝对路径直接使用，不再被 `allowedRootPath` 硬性拦截（是否越界由执行器路径守卫审批）；
- `search` / `glob` 的遍历根 = `workspace/{uid}`；
- 错误文案中的根路径改为用户 workspace。

关闭时行为不变。

### 4.2 `word`（`TLWordJavaSkill` / `JavaWordEngine`）

新增参数 `workspaceMode`（默认 `false`）。

开启后：

- 裸文件名与相对路径的根 = `workspace/{uid}`（`cfg.allowedRoot` 与 `cfg.workDir` 都指向用户 workspace）；
- 绝对路径允许，`JavaWordEngine` 新增 `allowOutsideRoot` 开关，开启时跳过 `startsWith(allowedRoot)` 拒绝；
- 越界由执行器路径守卫审批。

关闭时行为不变（含 `workDir` 的 userId 子目录语义）。

### 4.3 `code_execution`（`TLCodeExecutionSkill`）

新增参数 `workspaceMode`（默认 `false`）。

开启后：

- 临时脚本仍写系统临时目录；
- `ProcessBuilder.directory()` 设为 `workspace/{uid}`，相对路径读写默认落用户 workspace；
- 目录不存在时创建。

### 4.4 `script_execution`（`TLScriptExecutionSkill`）

新增参数 `workspaceMode`（默认 `false`）。

开启后：

- 脚本位置仍按 `allowedScriptDir` 查找（内置技能脚本不受影响）；
- **仅当解析后的脚本位于 `workspace/{uid}` 内时**，`ProcessBuilder.directory()` 设为 `workspace/{uid}`；
- 脚本在 workspace 之外（browser/desktop/联通云等内置技能脚本）时保持原 CWD，避免破坏依赖相对路径的既有脚本。

这样“在 workspace 里生成/运行的脚本”默认以 workspace 为 CWD；内置技能脚本行为不变。

### 4.5 MCP filesystem（`TLMcpAgent`）

新增参数：

- `userWorkspaceScoped`（默认 `false`）；
- `pathRoot`（默认取 `tlobject.workspaceRoot`，即 `workspace`）。

开启后、转发 `MCP_CALLTOOL` 之前：

- 对参数键 `path`（字符串）、`paths`（数组）以及 move_file 的 `source`/`destination` 逐个处理；
- 相对路径：首段已是当前 `{uid}` 则原样保留，否则重写为 `{uid}/<原路径>`（相对 MCP server 根，避免依赖
  server 对绝对路径的处理差异）；
- 绝对路径：先规范化；落在自己的 workspace 内 → 转成相对 `pathRoot` 的 `{uid}/...`；落在其他用户目录 → 直接拒绝；
  落在 `pathRoot` 之外 → 返回错误并提示改用 `file_operation`；
- 自己的 workspace 内路径照常转发。

这样 MCP 仍只启动一个共享 server（根 = `workspace`），无需每用户一个 node 进程，同时做到默认落用户子目录与
跨用户拒绝。`userWorkspaceScoped` 只对 filesystem 类 MCP 开启，其他 MCP server 保持默认 `false`。

## 5. 路径守卫实现

### 5.1 新增 `TLUserWorkspace`（`aiagent/common`）

职责：解析用户身份、计算用户 workspace、判定跨用户/可信路径、提取工具路径参数。

建议 API：

```java
public final class TLUserWorkspace {
    public static final String P_WORKSPACE_ROOT = "tlobject.workspaceRoot";

    public static String userId(TLMsg msg);                 // systemArgs → args → sessionId 兜底
    public static Path base();                              // 属性或默认 "workspace"，绝对化
    public static Path userRoot(String userId);             // base()/{safeUserId}，按需创建
    public static Path userRoot(TLMsg msg);
    public static Path resolveInput(TLMsg msg, String raw); // 相对 → userRoot，绝对原样
    public static boolean isInside(Path p, Path root);
    public static boolean isTrusted(Path p, String userId); // 自己 workspace + 自己的 data
    public static boolean isCrossUser(Path p, String userId);
    public static Decision evaluate(String toolName, Map<String,Object> args, TLMsg msg);
}
```

`Decision` 取值：

- `ALLOW`：无需审批；
- `DENY_CROSS_USER`：跨用户，硬拒绝；
- `NEED_APPROVAL`：需要 `path_access` 审批，附越界路径列表。

路径参数映射（工具名 → 参数名）：

- `file_operation` → `path`
- `word` → `path`、`output`

新文件工具必须在此登记；未登记的工具不受路径守卫约束（在类注释中写明）。

判定细节：

- 路径规范化：`toAbsolutePath().normalize()`；
- `workspace` 下第一个目录段不等于当前 userId：已存在的目录（或路径段数 ≥2）→ 跨用户硬拒绝；
  单段遗留文件或不存在的单段路径 → 越界走审批（不是跨用户）；
- 路径等于 `workspace` 根本身 → 不算跨用户，但属于“workspace 之外”，走审批；
- `data` 的跨用户判定只覆盖已知 per-user 形态（见第 2 节）；共享 data 目录（如 `data/traces`）按“workspace 外”走审批；
- 不解析符号链接（与仓库现有路径安全检查同口径）；作为已知边界记录。

### 5.2 `TLToolExecutor` 集成

新增参数 `pathGuard`（默认 `false`）。

在 `doToolExec` 中、**静态审批之前**：

1. `pathGuard` 未开启或工具未登记 → 走原有流程；
2. `evaluate(...)`：
   - `DENY_CROSS_USER` → 返回错误结果，不执行、不审批；
   - `NEED_APPROVAL` → 用 `toolName="path_access"`、`toolArgs={operation,path,paths,tool}` 调
     `checkApprovalGate(...)`；`approved` 继续，`pending/rejected/unavailable` 按现有分支收口；
   - `ALLOW` → 继续；
3. 之后仍执行原有静态审批（`code_execution` / `script_execution` / `priceTeam` 等规则照常生效）。

`operation` 取值：`read`、`write`、`delete`、`execute`；`file_operation` 由 `args.operation` 映射
（`read/list/exists/search/glob` → read；`write/edit` → write；`delete` → delete），`word` 的写类动作统一为
`write`，MCP 由 `TLMcpAgent` 自处理。

### 5.3 提示词与模板变量

`TLAiAgent.resolveVar` 新增内置变量：

- `{{userId}}`：当前用户；
- `{{userWorkspace}}`：用户 workspace 绝对路径。

主 agent system prompt 增加三条规则：

1. 新生成文件默认写 `{{userWorkspace}}`；
2. 只有用户在**本轮消息**中明确给出其他目录时才写到别处；
3. 不得从历史记忆/过往会话推断输出路径；委托子 agent 时把工作目录写进任务描述。

不删除、不改写现有长期记忆数据。

## 6. 配置改动

### 6.1 `approvalGate_config.xml`

- 删除：`file_operation` 规则、`word` 规则、`filesystemMcp_write_file`、`filesystemMcpWrite_write_file`。
- 新增：
  - `path_access`（动态触发，描述“访问工作目录之外的文件/目录需要审批”）；
  - `code_execution`（全部操作）；
  - `script_execution`（全部操作）。
- 保留：`priceTeam` 业务审批。

### 6.2 demo 配置开启项

- `toolExecutor_config.xml`：`pathGuard=true`。
- `fileAgent_config.xml`：`file_operation workspaceMode=true`；`word workspaceMode=true`、`workDir="workspace"`；
  `filesystemMcp userWorkspaceScoped=true`。
- `codingAgent_config.xml`：`file_operation`、`code_execution`、`script_execution` 均 `workspaceMode=true`。
- `codeAgent_config.xml`：`code_execution`、`script_execution` 均 `workspaceMode=true`。
- `hamburgerAgent_config.xml` / `pizzaAgent_config.xml` / `poetc_config.xml`：MCP `userWorkspaceScoped=true`。
- 主 agent system prompt：加入第 5.3 节三条规则。
- **不开启**：`cloudRevenueAgent` 的 `script_execution`（联通云脚本依赖其自身工作目录语义）、主 agent 的
  `liantongyun` 脚本技能、`browser`/`desktop` 等内置技能。它们的脚本位于 workspace 之外，`script_execution`
  的动态 CWD 规则本身也会保持原行为。

### 6.3 生效

- 同步 `demo/tlobject/target/classes` 下的配置副本（`mvn -pl demo/tlobject process-resources`）；
- 不新增模块/依赖，不改 `aistart*.bat` 的 classpath；
- 审批规则改动按现有 `approvalGate` 重载能力处理（必要时重启）。

## 7. 测试与验收

新增/扩展自测（仓库风格：runnable `main`）：

1. `TLUserWorkspaceSelfTest`
   - userId 净化与兜底；
   - 相对/绝对路径解析；
   - `workspace/{other}` 判定跨用户；
   - `data/{other}/uploads|tasks|...` 判定跨用户；
   - 自己的 `data/{uid}` 判定可信；
   - `workspace` 根本身判定为“需审批”而非跨用户。
2. 执行器路径守卫自测（Mock 审批模块）
   - workspace 内 `file_operation` 读写 → 不进审批；
   - workspace 外读 → 触发 `path_access`；
   - workspace 外写 → 触发 `path_access`；
   - 跨用户 → 直接拒绝、不发审批请求；
   - 自己的 data → 放行；
   - `code_execution` / `script_execution` → 命中静态审批。
3. `FileSkillSelfTest` 扩展
   - `workspaceMode` 下相对路径落 `workspace/{uid}`；
   - 绝对路径保留；
   - `search`/`glob` 根为用户 workspace。
4. word 自测扩展
   - `workspaceMode` 下裸文件名/相对路径落 `workspace/{uid}`；
   - 绝对路径在 `allowOutsideRoot` 下可解析。
5. `TLMcpAgent` 参数预处理自测（不启动 server）
   - 相对路径重写为 `workspace/{uid}/...`；
   - 跨用户绝对路径拒绝；
   - `pathRoot` 之外拒绝。
6. `/test` 新增 `workspaceIsolation` 场景（消息级策略测试，不调 LLM/不写文件）
   - 相对路径解析到 `workspace/{testUser}`（`evaluate` 判定 ALLOW）；
   - 自己的 data 放行；`workspace/{other}` → DENY；
   - 外部路径 → NEED_APPROVAL；
   - 使用真实 workspace 根（默认值），不修改全局 `tlobject.workspaceRoot`、不污染真实用户 workspace；
     执行器集成由 `TLToolExecutorPathGuardSelfTest` 覆盖。

测试隔离：`TLUserWorkspace` 的根由系统属性 `tlobject.workspaceRoot` 覆盖；自测在 `try/finally` 中设置并恢复该属性，
避免污染同一 JVM 内其他测试与真实 workspace。

回归：

- 全量 `/test`；
- `FileSkillSelfTest`、word 自测、审批授权/断点相关自测；
- `TLToolExecutor` 既有审批与中断自测。

验收标准：

- 对话中让 agent“生成一个 docx”，默认落在 `workspace/{userId}/`；
- 用户明确说“写到 `D:\Reports\x.docx`”时，弹一次审批，批准后成功；
- agent 尝试读 `workspace/{otherUser}/x` 时直接报错，无审批弹框；
- 现有 codingAgent 改仓库源码时，每次越界写触发审批（可按“本轮/本会话”授权）；
- 根目录不再新增生成物。

## 8. 边界与不做

- 不做 OS 级沙箱：`code_execution` 内的 Python/JS 仍可能绕过路径检查；控制手段是“执行即审批”。
- MCP 根之外不开放；外部路径统一走 `file_operation` + 审批。
- 符号链接不解析，恶意构造的链接可绕过路径判断（与现有安全检查同口径）。
- 不迁移、不删除现有根目录文件；如需归档到 `workspace/tianlong/` 另行确认。
- 共享 data 目录（`data/traces`、`data/evals` 等）不按跨用户处理，按“workspace 外”走审批。
- 未在路径参数映射中登记的新文件工具不受路径守卫约束，需要随工具一起登记。

## 9. 风险

- 放宽现有 `file_operation` 写/改/删与 word 覆盖类审批后，workspace 内误删/误覆盖不再有二次确认；这是用户明确选择的
  “工作区内全部免审”。
- codingAgent 修改仓库源码属于越界操作，会频繁触发审批；可用“本轮/本会话免审”缓解，必要时后续增加“项目根预授权”。
- MCP 相对路径自动前缀到 `workspace/{uid}` 会改变现有 demo 的落盘位置（从 `workspace/poet.txt` 变为
  `workspace/{uid}/poet.txt`），属于预期变化。

## 实施偏差记录（2026-11-08）

以下为最终实现与上文设计的差异，冲突时以本节为准：

- **`isCrossUser` 的判定更细**：已存在的 `workspace/{其他 uid}` 目录及其下内容、以及 `workspace/{其他 uid}/...`
  路径段 → 跨用户硬拒绝；workspace 根下的单段遗留文件或不存在的单段路径 → 不算跨用户，按“workspace 之外”
  走审批。路径段比较遵循文件系统大小写语义（Windows 大小写不敏感，POSIX 敏感）。
- **data 的 per-user 子目录**除 `uploads|attachments|longterm_memory|tasks|traces` 外，还包含
  `session_store`（`TLSessionManager`）与 `scheduled_tasks`（`TLScheduleTaskSkill`）。
- **`path_access` 审批携带真实工具参数**（不再是设计中的合成 `{operation,path,paths,tool}`）：
  `operation/ruleId/description` 等元数据通过 `forceApproval`/`ruleId`/`operation`/`description` 传递。
  断点恢复用 `approvalOrigin`（`path_access`/`static`）只跳过发起断点的那道门禁（空 origin 按 `static`，
  兼容旧调用方）；否则路径审批断点会绕过静态规则。
- **`script_execution` 不主动创建用户 workspace**：仅当解析后的脚本位于 workspace 内时才把子进程 CWD
  切到 workspace；`code_execution` 显式创建目录，创建失败返回准确错误而不是静默回退。
- **`/test workspaceIsolation` 是消息级策略测试**：使用真实 workspace 根（默认值）做
  `TLUserWorkspace.evaluate` 矩阵断言，不调 LLM、不写文件、不修改全局 `tlobject.workspaceRoot`；
  执行器集成由 `TLToolExecutorPathGuardSelfTest` 覆盖。
- **配置细节**：`codeAgent` 的 `allowedScriptDir` 显式配为 workspace 绝对路径（`scriptExecutionSkill`
  的相对路径按配置目录解析）；aitest 的 `fileOperationSkill` 配 `workspaceMode=true` 以匹配 `pathGuard`。
- **MCP 路径参数键**：除 `path`/`paths` 外还覆盖 `source`/`destination`（`move_file`）；新增路径键必须显式登记。
- **`TLUserWorkspace.userId`**：systemArgs/args 的字面量 `"null"`（大小写不敏感，trim 后）与空值同样
  视为未提供，继续按 sessionId 兜底（对齐 `TLMsgUtils.getSystemParamNonEmpty` 的“空值即未配置”语义）。
- **MCP scoped 模式为新用户创建根目录**：`userWorkspaceScoped=true` 时 `handleCallTool` 在改写参数前先
  `createDirectories(pathRoot/{uid})`（filesystem MCP 的 `write_file` 不能 chdir 进不存在的父目录），
  创建失败返回 `Error: 无法创建用户工作目录: ...`。只建用户根，嵌套目录由模型用 `file_operation` 创建。
- **`script_execution` 跨用户硬拒绝**：解析后的脚本若位于其他用户的 workspace 目录
  （`TLUserWorkspace.isCrossUser`），无论 `workspaceMode` 如何一律返回
  `Error: 无权访问其他用户目录: ...`；workspace 外的内置脚本不受影响。
- **非法/盘符相对路径收口为 DENY 而非异常**：`TLUserWorkspace.evaluate` 与 `TLMcpAgent.scopeOne`
  对 `InvalidPathException` / `java.io.IOError`（Windows 盘符相对路径 `a:b.py` 的 `toAbsolutePath`）
  返回 `非法路径: ...`（MCP 侧抛 `Exception`，由 `handleCallTool` 转成 RESULT=false），
  不让 Error 族逃出执行器的 `catch (Exception)` 杀死工作线程。
- **`pathGuard` 未配置 `approvalModule`**：越界路径的 `NEED_APPROVAL` 退化为放行
  （未配置审批 = 功能未启用），跨用户 `DENY` 仍然硬拒绝；启动 WARN 文案如实说明该语义。
