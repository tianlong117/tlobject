# 用户上传件迁入 workspace 设计

日期：2026-11-09
状态：已确认（方案 A）

## 背景

当前有两个目录存放用户可见文件：

- `workspace/{userId}/` —— 技能工作区（file_operation / word / code 等），属于用户自己的文件空间；
- `data/` —— 系统数据根。

问题：`data/{uid}/` 下混放了两类内容：

| 位置 | 内容 | 性质 |
|---|---|---|
| `data/{uid}/uploads` | webui 上传件、控制台 `/img` 导入件 | **用户自己的文件** |
| `data/{uid}/attachments/auto` | 截图、base64 入参等 | 系统生成（7 天 TTL / LRU） |
| `data/{uid}/longterm_memory`、`tasks`、`traces`、`session_store`、`scheduled_tasks` | 记忆、任务、追踪、会话、定时记录 | 系统数据 |
| `data/aiagent.db`、`data/traces/`、浏览器 profile 等 | 全局系统数据 | 系统数据 |

本设计把「用户上传件」从 `data/{uid}/uploads` 迁到 `workspace/{uid}/uploads`，让 `data/` 只承载系统生成/系统管理的数据。

## 目标

1. 新上传件一律写 `workspace/{uid}/uploads`（webui 上传 + 控制台 `/img` 导入）。
2. 存量 `data/{uid}/uploads` 启动时幂等迁移到新位置；旧会话历史中的绝对路径仍能正常显示。
3. 上传件不再被自动清理（用户文件由用户自己管理）。
4. 路径解析、访问控制、跨用户防护保持单一事实来源，避免各模块各自拼路径。

## 非目标（范围外）

- `data/documents/{uid}`（Word 技能旧默认目录）保持原位——已配置 `workspaceMode` 的产物本来就在 workspace，本次不做迁移。
- `data/{uid}/attachments/auto`（系统自动附件）保持原位与现有 TTL/LRU 策略。
- `data/{uid}/tasks`、`scheduled_tasks`、`session_store`、`traces`、`longterm_memory` 保持原位。
- 不新增配置项；沿用 `tlobject.workspaceRoot` 系统属性。
- 不改写数据库/JSONL 历史消息里的附件路径（靠读时回退兼容）。

## 方案选择

**选定方案 A：路径统一收口到 `TLUserWorkspace`。**

- 新增 `uploadsDir` / `ensureUploadsDir` / `resolveUploadPath` 等辅助，作为「用户上传件在哪」的唯一事实来源；
- webui、控制台、附件模块、出图、`view_image` 全部走它；
- 迁移逻辑由附件模块在启动时执行。

被否方案：

- 各模块加 `uploadBasePath` 配置项：配置易漂移、守卫也得跟着配，不推荐。
- 用符号链接把 `workspace/{uid}/uploads` 指到 `data/{uid}/uploads`：Windows 权限、跨平台、清理语义都更差，不推荐。

## 目录布局

```
workspace/{userId}/            ← 用户空间：一切属于用户的文件
  uploads/                     ← 用户上传件（webui /api/upload、控制台 /img 导入）
  ...                          ← 技能产物（报告、图表、文档等，已有）

data/{userId}/                 ← 系统数据（保持不变）
  attachments/auto/...         ← 系统自动附件：截图、base64 入参（TTL/LRU 仍生效）
  longterm_memory/ tasks/ traces/ session_store/ scheduled_tasks/
data/aiagent.db、data/traces/、浏览器 profile ... （不变）
```

## 路径规则

### 新增辅助（`TLUserWorkspace`）

| 能力 | 语义 |
|---|---|
| `uploadsDir(String userId)` | `userRoot(userId).resolve("uploads")`，绝对、规范化 |
| `ensureUploadsDir(TLMsg msg)` | 按需创建，失败不抛 |
| `resolveUploadPath(String userId, String raw)` | 相对路径按 `workspace/{uid}` 解析（`uploads/xxx` → `workspace/{uid}/uploads/xxx`）；绝对路径原样；返回候选路径（不保证存在） |
| `resolveExistingUpload(String userId, String raw)` | 在候选位置查找；相对路径依次尝试 workspace 用户目录、旧 `data/{uid}`；旧绝对路径不存在时按文件名回退到 workspace 上传目录 |

- 前端相对路径语义：`uploads/xxx` 由「相对 `data/{uid}`」改为「相对 `workspace/{uid}`」。
- `tlobject.workspaceRoot` 对所有新路径生效。

### 各入口

1. **webui 上传**（`TLWebChatModule.doUpload`）
   - 保存目录由 `"data/" + uid + "/uploads"` 改为 `TLUserWorkspace.uploadsDir(userId)`（绝对路径）。
   - `normalizeAttachments`：相对路径按 workspace 解析为绝对路径，保持现有「绝对路径交给 register」的行为。
2. **webui 出图**（`TLWebChatModule.doImage`）
   - 相对路径 → `workspace/{uid}/...`；
   - 绝对路径 → 允许本用户 `workspace/{uid}` 或本用户 `data/{uid}`（含旧上传件、自动附件）；
   - 旧历史中 `data/{uid}/uploads/xxx` 不存在时，按**文件名**回退到 `workspace/{uid}/uploads/xxx`；
   - canonical path 校验（containment）保持不变，只允许本用户根内。
3. **控制台 `/img` 导入**（`TLChatConsole`）
   - 拷贝目标由 `dataBasePath + uid + "/uploads"` 改为 `TLUserWorkspace.uploadsDir(userId)`；
   - `dataBasePath` 参数不再参与上传件路径；配置了该参数时启动 WARN 提示已废弃（仓库内无配置使用）。
4. **附件登记**（`TLAttachmentStore.register`）
   - 可信根从「仅 `data/{uid}`」扩为「`workspace/{uid}` 或 `data/{uid}`」；canonical path 校验与错误文案保持。
   - 覆盖两类调用方：用户上传件（workspace）、工具产图/旧附件（data）。
5. **`view_image` 技能**（`TLViewImageSkill`）
   - 默认允许根改为「本用户 workspace + 本用户 data」（复用 `TLUserWorkspace.isTrusted`）；
   - 显式配置的 `allowedRootPath` 保留为附加旧根，避免破坏已有部署；
   - agent 侧「本会话已登记附件」白名单仍是主判据。

### 路径守卫

`workspace/{uid}/uploads` 本来就在 `TLUserWorkspace` 可信区内，`workspaceMode` 技能用相对路径 `uploads/xxx` 即可访问，无需扩权。`USER_DATA_SUBDIRS` 中的 `uploads` 保留（旧数据跨用户防护），注释标注为 legacy。

## 存量迁移

- 触发点：`TLAiAgent.runStartMsg()` 调 `TLUploadMigration.migrateOnce()`（默认 `data/` 根，每 JVM 一次）；
  `TLAttachmentStore.runStartMsg()` 用自定义 `dataBasePath` 再调一次（幂等），在现有启动清理**之前**执行。
  > 实施偏差（2026-11-10）：`attachmentStore` 是懒加载模块（首次附件操作才实例化），仅靠其
  > `runStartMsg` 无法保证"启动时迁移"。迁移逻辑抽到 `TLUploadMigration`，由 agent 启动阶段触发；
  > `attachmentStore` 保留自定义数据根的迁移入口。
- 范围：遍历 `dataBasePath` 下的用户目录，把 `{uid}/uploads/**` 逐文件移到 `workspace/{uid}/uploads`，保留子目录结构。
- 规则：
  - 目标已存在同名文件 → 跳过不覆盖，保留源文件并 WARN（仍可经读回退访问）；
  - 跨卷移动失败 → 复制 + 删源兜底；
  - 源与目标 canonical 相同 → 跳过（防 `workspaceRoot` 被配成 `data`）；
  - 迁空后删除遗留空目录；有残留文件则保留目录并 WARN；
  - 每个用户/文件独立 try/catch，失败只 WARN，绝不阻断启动；
  - 每次启动扫描一遍，已迁完的部署零开销（目录不存在即跳过）。
- userId 取目录名前先经 `TLPathSafety.safeSegment` 净化，源/目标 canonical 均校验在各自根内。

## 清理策略

- TTL / LRU 只作用于 `data/{uid}/attachments`（系统自动附件）。
- `workspace/{uid}/uploads` 永不自动删除；迁移失败的遗留文件也不删（用户文件不静默丢），启动日志提示。
- `attachmentRetentionDays` / `attachmentStoreMaxMB` 语义收窄为「自动附件」。

## 兼容性

- **旧会话历史**：附件记录里存的是绝对路径，靠 `doImage` 的文件名回退继续显示，无需改写数据库。
- **编程式调用方**：继续传 `data/{uid}/uploads/...` 绝对路径时，register 仍接受 `data/{uid}`，行为兼容。
- **前端**：URL 仍为 `/api/image?path=uploads/xxx`，服务端语义改变，前端无需改动。
- **配置**：无新增配置；控制台 `dataBasePath` 参数废弃（配了只 WARN）。

## 安全

- 跨用户：`workspace/{他人}` 硬拒绝；`data/{他人}/uploads` 保留在 `USER_DATA_SUBDIRS` 中（legacy）。
- `/api/image` 与附件登记做 canonical path 校验，只允许本用户 workspace / data；旧路径回退只取文件名，不信任旧路径的目录部分。
- 上传目录用净化后的 userId 段，防路径穿越。

## 需要改动的文件

| 模块 | 文件 | 改动 |
|---|---|---|
| common | `TLUserWorkspace.java` | 新增 uploads 路径辅助、旧路径回退；注释更新 |
| common | `TLUploadMigration.java` | 新增：旧上传件迁移（幂等、冲突不覆盖、跨卷兜底） |
| common | `TLAiAgent.java` | 启动阶段触发 `TLUploadMigration.migrateOnce()` |
| common | `TLAttachmentStore.java` | 登记边界扩为 workspace+data；用自定义 dataBasePath 迁移；清理去掉 uploads；类注释 |
| common | `TLChatConsole.java` | `/img` 导入目标；`dataBasePath` 废弃处理 |
| webui | `TLWebChatModule.java` | `doUpload`、`normalizeAttachments`、`doImage` |
| skill-builtin | `TLViewImageSkill.java` | 允许根改 workspace+data |
| tests | `TLUserWorkspaceSelfTest.java`、`TLToolExecutorPathGuardSelfTest.java`、`TLAgentTestModule.java` | 路径与新语义 |
| tests | 新增 `TLAttachmentStoreUploadsSelfTest.java` | 迁移/回退/登记边界/清理 |
| smoke | `smoke/upload_multi_check.py` | 清理目录改为 workspace |
| docs | `CLAUDE.md`、`aiagent/README.md` | 路径矩阵与目录说明 |

## 测试与验收

自动化：

1. `TLUserWorkspaceSelfTest`：`uploadsDir`、相对/绝对解析、旧路径回退、跨用户判定。
2. `TLToolExecutorPathGuardSelfTest`：本人 `workspace/{uid}/uploads` 放行；他人 workspace 上传件拒绝；旧 `data/{他人}/uploads` 仍拒绝。
3. 新增 `TLAttachmentStoreUploadsSelfTest`：
   - 迁移搬文件、保留子目录、幂等；
   - 同名冲突不覆盖、源保留；
   - register 接受 workspace 文件，拒绝他人路径；
   - cleanup 不删除 workspace 上传件；
   - 旧绝对路径按文件名回退。
4. `TLAgentTestModule` 用例路径与 smoke 脚本更新。

真实链路（启动真实服务）：

1. 启动时迁移日志与文件落位正确；
2. webui 上传 → `/api/image` 显示 → 带图聊天；
3. 重登后历史缩略图仍显示（旧路径回退）；
4. 控制台 `/img` 导入 → 发送 → `view_image` 可读；
5. 上传件超过 TTL 后仍存在（不再自动清理）。

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| 迁移移动用户文件 | 幂等、目标冲突不覆盖、失败保留源；读回退保证可用 |
| 旧历史缩略图失效 | 文件名回退；验收覆盖重登场景 |
| 清理策略变化导致磁盘增长 | 用户文件本应由用户管理；文档明确；后续可提供手动清理工具（范围外） |
| 控制台 `dataBasePath` 外部配置失效 | 启动 WARN 提示；仓库内无使用 |
