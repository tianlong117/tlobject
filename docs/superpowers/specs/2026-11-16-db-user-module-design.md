# 数据库用户模块设计

日期：2026-11-16
状态：已确认（方案 C：独立模块 `aiagent/user-java`）

## 背景

当前用户体系是两个互不相通的半套：

| 层 | 现状 | 问题 |
|---|---|---|
| 登录 | `TLWebChatModule` 用 `passwords="admin:123456;tianlong:123456"` 明文配置校验，只写 `HttpSession.userId` | 无哈希、无状态、无昵称/头像；登录与用户数据脱节 |
| 用户数据 | `DemoUserModule`（demo 模块）从 XML 读 `users/groups`，仅 `getUserInfo`（userId→role/group） | 无密码、昵称、邮箱、头像；未知用户回落 `default` |
| 授权 | `TLAppAuthModule` → `DemoUserModule.getUserInfo` → `TLAuthModule` | 只覆盖工具执行；webui 管理接口无角色校验 |

昵称、头像、一句话介绍、最后登录时间/ IP 在全仓库均不存在。本次把用户数据落到数据库，并提供完整用户管理能力。

## 目标

1. 用户数据存数据库（SQLite 活动配置，附 MySQL 变体），字段覆盖：登录名、昵称、密码哈希、多角色、group、一句话介绍、邮箱、头像、状态、最后登录时间/IP、创建/更新时间。
2. webui 登录改为走用户模块校验（PBKDF2），登录成功更新最后登录时间/IP；禁用即时生效。
3. 管理页新增「用户管理」：列表/搜索/分页/新建/编辑/重置密码/禁用·启用/删除，仅管理员可用。
4. 登录页支持自助注册；聊天页支持个人资料（昵称/介绍/邮箱/头像/改密码）。
5. 旧配置用户（webui `passwords` + `DemoUserModule` 角色表）首次启动一次性迁入数据库，旧账号密码与角色不变。
6. 实现完成后已删除 `DemoUserModule`（demo 模块）；aitest 配置不再声明它，`/test` 36 场景不受影响。

## 非目标

- 邮件找回密码（无邮件服务）
- 登录失败次数限制、验证码
- 头像存 OSS 或独立目录（本期存 DB base64）
- webui 其他管理接口（install/unload/MCP 等）的角色校验——本次只覆盖用户管理接口
- 多实例部署的一致性（模块直查 DB，无缓存，单 JVM 为前提）
- 控制台密码登录（`-u` 保持免密）

## 方案选择

**选定方案 C：独立 Maven 模块 `aiagent/user-java`。**

| 方案 | 结论 |
|---|---|
| A：`aiagent/common` 新增类 | 可行，但用户体系边界与公共 AI 基础设施混在一起 |
| B：就地改造 `DemoUserModule` | 否决：demo 定位不符；aitest 引用会被波及 |
| **C：独立模块 `aiagent/user-java`** | **选定**：边界最清晰，可独立复用；仿 `word-java`/`browser-java` 惯例 |

用户模块只有一个类 + 一张表，独立模块的额外成本是 pom、classpath、启动脚本；由实施计划集中处理（见「风险」）。

## 模块结构

```
aiagent/user-java/                     artifactId: tlobject-aiagent-user-java
  pom.xml                              depends: tlobject-aiagent-common（已含 core/database/gson）
  src/main/java/cn/tianlong/tlobject/aiagent/user/TLUserModule.java
  src/test/java/cn/tianlong/tlobject/aiagent/user/TLUserModuleSelfTest.java
```

- 包名：`cn.tianlong.tlobject.aiagent.user`
- 工厂注册名：**`dbUserModule`**（与 `dbSessionManager`/`dbMemory` 命名一致）
- 类注册：`conf/tlobject/cn.tianlong.tlobject.aiagent_config.xml`
  ```xml
  <module name="dbUserModule" classfile="cn.tianlong.tlobject.aiagent.user.TLUserModule" singleton="true"/>
  ```
- 实例声明：`conf/demo/aiagent/moduleFactory_chat_config.xml`（web 配置 include 它）
  ```xml
  <module name="dbUserModule" sameClassAs="dbUserModule" singleton="true"
          tableName="aiUsers"
          bootstrapUsers="admin:member,admin:123456;tianlong:member,guest:123456;console_user:guest:"
          bootstrapGroups="admin:0"/>
  ```
- 控制中心启动序列：`controlCenter_config.xml` / `controlCenter_web_config.xml` 的 `startMsg` 用 `dbUserModule` 替换 `demoUserModule`
- aitest 不再声明 `demoUserModule`（该 demo 模块已删除，`/test` 不依赖用户模块）

## 数据表 `ai_users`

`conf/demo/aiagent/database_config.xml` 声明（SQLite 活动，MySQL 变体 `statup="false"`）：

```sql
CREATE TABLE IF NOT EXISTS ai_users (
  user_id       TEXT PRIMARY KEY,      -- 登录名；限 [A-Za-z0-9_-]{2,32}（同时是 workspace 目录名，必须路径安全）
  nickname      TEXT DEFAULT '',       -- 昵称
  password_hash TEXT DEFAULT '',       -- pbkdf2$迭代$盐$哈希；空 = 不能 web 登录
  roles         TEXT DEFAULT 'member', -- 逗号分隔多角色，如 "member,admin"
  user_group    TEXT DEFAULT '',       -- TLAuthModule 的 group 维度（group 是 SQL 保留字，列名加前缀）
  bio           TEXT DEFAULT '',       -- 一句话介绍
  email         TEXT DEFAULT '',
  avatar        TEXT DEFAULT '',       -- data URL（image/png|jpeg|webp base64，解码后 ≤200KB）
  status        INTEGER DEFAULT 1,     -- 1=启用 0=禁用
  created_at    INTEGER,
  updated_at    INTEGER,
  last_login_at INTEGER DEFAULT 0,
  last_login_ip TEXT DEFAULT ''
);
```

MySQL 变体：`user_id VARCHAR(64) PRIMARY KEY`、`avatar LONGTEXT`、时间列 `BIGINT`、`status TINYINT`、`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`。

表模块名 `aiUsers`（`dbtable="ai_users"`），模块参数 `tableName` 默认 `aiUsers`。

## 模块参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `tableName` | `aiUsers` | 表模块名 |
| `defaultRole` | `guest` | 未知/禁用用户 `getUserInfo` 的回落角色 |
| `selfRegisterRole` | `member` | 自助注册默认角色 |
| `adminRoles` | `admin` | 管理员角色（逗号分隔可多值） |
| `allowSelfRegister` | `true` | 是否允许自助注册 |
| `minPasswordLength` | `6` | 密码最短长度（上限 64） |
| `maxAvatarBytes` | `204800` | 头像解码后字节上限 |
| `pbkdf2Iterations` | `120000` | PBKDF2 迭代次数 |
| `bootstrapUsers` | 空 | 表为空时的种子，格式 `userId:roles:password;…`（密码不能含 `;` 与 `:`） |
| `bootstrapGroups` | 空 | 种子 group，格式 `userId:group;…` |

## 种子/迁移机制（合并为一套）

- `runStartMsg` 中检查 `ai_users` 是否为空：
  - 空 → 按 `bootstrapUsers` 写入（密码 PBKDF2 哈希、`status=1`、`created_at=now`）；`bootstrapGroups` 合并 group；
  - 非空 → 完全忽略配置，不做双轨、不覆盖已有数据（幂等）。
- demo 种子即「旧数据迁移」：`admin:member,admin:123456`、`tianlong:member,guest:123456`（来自旧 `passwords` + `DemoUserModule`），`console_user:guest:`（无密码，不可 web 登录），`bootstrapGroups="admin:0"`。
- 启动日志对弱初始密码（如 `123456`/`admin123`）打 WARN，提示登录后修改。
- 表声明缺失或数据库不可用 → ERROR 日志并拒绝服务（fail-fast），不静默建错表。

## 消息接口

所有 action 同步返回 `Map`。通用错误键 `success=false` + `error`（面向用户的中文文案）+ `errorCode`（程序判据）。

| action | 权限 | 入参 | 出参要点 |
|---|---|---|---|
| `login` | 公开 | `userId/password/clientIP` | `success/userId/nickname/roles(List)/group/bio/email/avatar/isAdmin`；失败 `errorCode=user_not_found\|wrong_password\|disabled\|empty_password`；成功更新 `last_login_at/ip` |
| `register` | 公开（受 `allowSelfRegister`） | `userId/password/nickname/bio/email` | 成功返回新用户资料；重复登录名明确提示；默认角色 `selfRegisterRole` |
| `getUserInfo` | 内部（AppAuthModule） | `userId` | `userid/role(List)/group`——**采用 userid/role/group 契约（供 TLAppAuthModule 消费）**；未知或禁用回落 `defaultRole`；`role` 为空时返回单元素 `[defaultRole]`，不返回密码 |
| `getUserDetail` | 内部 | `userId` | 脱敏资料（无 `password_hash`）；不存在返回 `found=false`（不返回密码） |
| `listUsers` | 管理员 | `keyword/status/page/pageSize`、`operatorId` | `total/users(List<脱敏>)`；`keyword` 匹配登录名/昵称/邮箱；`pageSize` 上限 100 |
| `createUser` | 管理员 | 全字段 + `operatorId` | 校验 userId 格式/唯一、密码长度、角色非空 |
| `updateUser` | 管理员 | `userId + nickname/roles/group/bio/email/status + operatorId` | 不含密码；管理员保护规则见下 |
| `deleteUser` | 管理员 | `userId/operatorId` | 管理员保护规则见下 |
| `resetPassword` | 管理员 | `userId/newPassword/operatorId` | 直接设置新哈希 |
| `updateProfile` | 本人 | `userId/nickname/bio/email/avatar` | 不能改自己的 roles/status；avatar 服务端校验 |
| `changePassword` | 本人 | `userId/oldPassword/newPassword` | 旧密码必须匹配 |
| `getAvatar` | 本人或管理员 | `userId/operatorId` | 返回头像 data URL；无头像返回空 |
| `isAdmin` | 内部 | `userId` | boolean，供 webui/console 判权限 |

**权限双层**：webui 先按 session 身份校验；模块内管理员 action 再用 `operatorId` 复核（防御性，防止其他调用方误用）。

**管理员保护**：不可删除/禁用自己；不可删除或禁用最后一个启用的管理员（`roles` 含 `adminRoles` 且 `status=1` 的仅剩一人时拒绝）。

## 密码哈希

- 算法：`PBKDF2WithHmacSHA256`，每用户 16 字节 `SecureRandom` 盐，32 字节密钥，默认 120000 次迭代。
- 存储格式：`pbkdf2$<iterations>$<saltBase64>$<hashBase64>`；空串表示不能 web 登录。
- 校验用 `MessageDigest.isEqual` 常数时间比较；哈希不出 API、不进日志；解析失败视为密码错误。
- 密码长度 6–64；注册/改密/重置统一校验。

## 登录与授权接入

### webui（`TLWebChatModule`）

- `userModule` 参数真正启用：非空时 `/api/login` 发 `login` 消息（带 `clientIP`），成功返回用户资料；为空时保留旧 `passwords` 逻辑（向后兼容）。
- demo 配置：`webui userModule="dbUserModule"`，**删除 `passwords`**。
- 登录成功仍写 `HttpSession.userId`（现有会话/审批/任务路由全部不变），响应增加 `nickname/roles/group/bio/email/avatar/isAdmin`。
- **禁用即时生效**：所有需登录的 `/api/*` action 入口统一 `requireActiveUser`——按 session userId 查模块，用户不存在或 `status=0` → 401（`errorCode=account_disabled`），前端清会话回登录页。已在跑的后台任务不强制中断（已知边界，文档记录）。
- 管理接口：`requireAdmin`（`isAdmin`）→ 否则 403。
- **模块缺失保护**：向 `userModule` 发消息一律带 `IGNOREMODULEISNULL=true` 并判空——框架对不存在的模块默认 `shutdown`（历史陷阱）；为空时回退旧行为并 WARN。
- 新增 action：`register/profile/profileUpdate/changePassword/avatar/listUsers/createUser/updateUser/deleteUser/resetPassword`。
  - 本人 action 的 userId 只取 session（忽略 body）；管理员 action 的 `operatorId` 只取 session。
  - `/api/avatar` 二进制输出，仿 `/api/image`：解析 data URL 前缀定 Content-Type，`putStream` 输出，无头像 404；仅本人或管理员可取。

### 控制台（`TLChatConsole`）

- `-u` 保持免密；新增 `userModule` 参数（默认空 = 不校验）。
- 配置后启动时校验：用户不存在或禁用 → 报错退出并提示可用用户；数据库查询异常 → WARN 后继续（避免数据库故障时控制台完全不可用）。
- `console_user` 由种子创建（guest、无密码），现有 `-u console_user` 用法不变。

### AppAuthModule

- 配置 `userModule="dbUserModule"`；`getUserInfo` 契约不变，代码不改。
- 禁用用户的 `getUserInfo` 回落 `defaultRole`（guest），其工具授权按 guest 策略收敛。

## URL 映射（`urlMap_config.xml`）

| URL | action | 权限 |
|---|---|---|
| `/register` | `register` | 未登录可访问 |
| `/profile` | `profile` | 登录（本人） |
| `/profileUpdate` | `updateProfile` | 登录（本人） |
| `/changePassword` | `changePassword` | 登录（本人） |
| `/avatar` | `avatar` | 登录（本人或管理员） |
| `/users` | `listUsers` | 管理员 |
| `/usersCreate` | `createUser` | 管理员 |
| `/usersUpdate` | `updateUser` | 管理员 |
| `/usersDelete` | `deleteUser` | 管理员 |
| `/usersResetPassword` | `resetPassword` | 管理员 |

均配 `clientType="jsonClient"`；`/avatar` 不配（模块自行 `putStream`，同 `/api/image` 惯例）。

表中「action」是 **webui 模块的 action**，由其内部转发到用户模块：`profile` → `getUserDetail`，`profileUpdate` → `updateProfile`，`usersResetPassword` → `resetPassword`，以此类推。

## 前端

### 聊天页（`chat.html` / `app.js` / `app.css`）

- 登录页增加「登录 / 注册」切换：注册字段＝登录名、密码、确认密码、昵称；注册成功自动登录。
- 顶栏显示头像 + 昵称（无昵称回落 userId，无头像显示昵称首字符圆形占位）；点击打开「个人资料」弹框。
- 个人资料弹框：头像上传（前端 canvas 裁切压缩 256×256 → dataURL）、昵称、一句话介绍、邮箱、修改密码（旧密码 + 新密码 + 确认）。
- 禁用/删除导致的 401：清本地会话、回登录页并提示原因。

### 管理页（`manage.html` / `manage.js` / `manage.css`）

- 左侧菜单新增「👤 用户管理」（现有结构已预留多菜单，`data-view` 切换面板）。
- 列表：头像、登录名、昵称、角色、状态、最后登录时间、最后登录 IP；关键词搜索（登录名/昵称/邮箱）、状态筛选、分页（服务端）。
- 操作：新建用户、编辑（昵称/角色/group/邮箱/介绍/状态）、重置密码、禁用·启用、删除（二次确认）。
- 非管理员：菜单隐藏；接口兜底 403（菜单显隐取自登录响应的 `isAdmin`，角色变化以接口判定为准）。

## 错误处理与安全

- 登录失败统一「用户ID或密码错误」（不泄露账号存在性）；禁用账号明确提示「账号已被禁用」；注册重复登录名明确提示。
  webui 边界将账号存在性类错误码收敛为 `login_failed`（仅透传 `disabled`/`storage_error`），前端只按收敛后的码提示。
- userId 白名单 `^[A-Za-z0-9_-]{2,32}$`（workspace 目录安全）；昵称/介绍/邮箱长度上限（昵称 32、介绍 200、邮箱 128）。
- 头像：仅 `data:image/(png|jpeg|webp);base64,` 前缀；Base64 解码后校验字节数 ≤ `maxAvatarBytes`；非法一律拒绝。
- 管理员保护（见接口节）；本人接口不接受 body 中的 userId。
- 所有 SQL 走表模块参数绑定（`?` 占位符），列名/排序字段固定。
- 管理员写操作 INFO 审计日志：操作者、目标、动作、结果（不含密码）。
- CSRF 不在本期（现有 webui 也没有；文档记录为已知缺口）。

## 需要改动的文件

| 模块 | 文件 | 改动 |
|---|---|---|
| 新模块 | `aiagent/user-java/pom.xml` | 新建，依赖 aiagent-common |
| 新模块 | `.../user/TLUserModule.java` | 新建：表访问、哈希、种子、全部 action |
| 新模块 | `.../user/TLUserModuleSelfTest.java` | 新建：runnable main 自测 |
| 聚合 | `aiagent/pom.xml`、`tlobject-all/pom.xml` | 加入新模块 |
| 启动 | `aistart_args.txt`、`aistart-web.bat`、`aistart_dq.bat`、`aistart_tl.bat`、`aistart_xm.bat`、`run_aitest.bat` | 加 `aiagent/user-java/target/classes`（aistart.bat 走 args 文件，本身不用改） |
| 注册 | `conf/tlobject/cn.tianlong.tlobject.aiagent_config.xml` | 注册 `dbUserModule` 类 |
| 配置 | `conf/demo/aiagent/moduleFactory_chat_config.xml` | 声明 dbUserModule 实例；appAuthModule/chatConsole 指向它 |
| 配置 | `conf/demo/aiagent/moduleFactory_chat_web_config.xml` | webui userModule=dbUserModule，删 passwords |
| 配置 | `conf/demo/aiagent/controlCenter_config.xml`、`controlCenter_web_config.xml` | startMsg 换 dbUserModule |
| 配置 | `conf/demo/aiagent/database_config.xml` | `ai_users` 表（SQLite + MySQL 变体） |
| 配置 | `conf/demo/aiagent/urlMap_config.xml` | 新增 10 条路由 |
| webui | `TLWebChatModule.java` | 登录委托、状态/权限校验、新增 action、avatar 输出 |
| 控制台 | `TLChatConsole.java` | userModule 参数与启动校验 |
| 前端 | `chat.html`、`app.js`、`app.css` | 注册、顶栏昵称头像、个人资料 |
| 前端 | `manage.html`、`manage.js`、`manage.css` | 用户管理面板 |
| 测试 | `smoke/user_manage_check.py` | 新增静态页 + Mock API 检查 |
| 文档 | `CLAUDE.md`、`aiagent/README.md` | 用户体系说明与配置 |

## 测试

自动化：

1. `TLUserModuleSelfTest`（runnable main，无 JUnit；用户模块的表访问点做成可注入，测试注入一个内存表桩，与现有 SelfTest 直接 new 对象 + 反射设字段的风格一致，不依赖真实数据库）：
   - PBKDF2 往返、错误密码、畸形哈希；userId 校验；角色解析；
   - 注册默认角色/重复拒绝；登录更新 last_login；失败登录不更新；
   - 禁用用户登录失败、getUserInfo 回落 defaultRole；
   - 改密旧密码校验；重置密码；管理员保护（删自己/最后一个管理员）；
   - 种子幂等（非空不再写）；头像大小/类型校验；listUsers 筛选分页；
   - `getUserInfo` 键名保持 userid/role/group 契约。
2. `smoke/user_manage_check.py`：静态页 + Mock API——菜单、列表、新建、编辑、重置密码、禁用、删除、非管理员隐藏。
3. 回归：`/test` 36 场景（aitest 已不声明 DemoUserModule，该 demo 模块已删除）；`smoke/auth/AuthConfigProbe`。

真实链路（手工 E2E）：

1. `aistart-web.bat` 启动 → 种子创建日志（WARN 弱密码）；
2. admin/123456 登录 → 顶栏昵称/头像 → 管理页看到 admin/tianlong/console_user；
3. 注册新用户 → 自动登录 → 角色 member；
4. 管理页新建/编辑/重置密码/禁用 → 被禁用用户下一次请求 401 回登录页；
5. 个人资料改昵称/介绍/邮箱/头像 → 重登后保持；
6. 改密码后用新密码登录；
7. 控制台 `-u admin` 正常，`-u 不存在` 报错退出；
8. 登录后工具授权按 DB 角色生效（admin 域工具可调、guest 受限）。

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| 新模块漏加 classpath → `NoClassDefFoundError` | 实施清单覆盖 `aiagent/pom.xml`、`tlobject-all`、所有含 classpath 的 `*.bat`/`aistart_args.txt`；启动冒烟验证 |
| `putMsg` 到不存在的 userModule 触发框架 shutdown | 发送统一带 `IGNOREMODULEISNULL=true` 并判空回退；模块未配置时 login 走旧逻辑 |
| 首次升级后种子密码仍是弱密码 | 启动 WARN；文档提示改密；管理页可重置 |
| 禁用后已在跑的后台任务继续 | 已知边界；下一请求即拒绝；文档记录 |
| 头像 base64 撑大 DB | 服务端限 200KB，前端压到 256×256；演示规模足够 |
| 旧会话在迁移前存在 | 迁移用户与旧账号一一对应，旧 HttpSession 的 userId 仍有效 |
| `ai_users` 表未声明/数据库异常 | 模块 fail-fast 报错，不静默降级 |
