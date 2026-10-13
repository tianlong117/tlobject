# 审批授权（阶段一）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 approvalGate 内建"授权记忆"，实现本轮/本会话同规则免审、自动运行模式、`/permissions` 撤销管理、审批未受理终结时的消息箱兜底。

**Architecture:** 授权表与现有 `rejectedOps` 完全镜像（`TLApprovalModule` 内一处状态）；授权匹配发生在 `handleApprovalRequest` 规则判定之后、弹框之前；"弹框时选免审"与"事先打开自动运行"是同一张表的两种写入时机（后者即 `ruleId="*"` 通配授权）。`TLToolExecutor`/`TLAiAgent` 零改动；控制台与 webui 经 `TLAgentService` 纯转发到达 approvalGate。

**Tech Stack:** Java 17、无 JUnit（测试 = `/test` Mock 驱动场景 + 真实应用冒烟）、Gson、原生 JS（webui 无框架）。

**Spec:** `docs/superpowers/specs/2026-10-12-approval-grants-design.md`（阶段一 = §4；阶段二 §5 不在本计划内）

**测试约定（本仓库特有，务必遵守）：**
- 无单测框架；核心逻辑用 `TLAgentTestModule` 的消息级场景验证（Mock Provider 驱动，`/test` 运行）
- 测试 app 入口：`cn.tianlong.java.demo.aiagent.AiAgentDemoStartup`（配置目录 `conf/demo/aitest/`，factoryBoot 里自动 `runAllTests`）
- 编译用绝对路径 Maven：`D:/maven/bin/mvn -f D:/tlobjectapp/tlobject/pom.xml install -pl <子模块> -am -DskipTests`
- **改代码必须重新编译 + 重启进程**（进程加载的是 target/classes 旧 class）

---

## 文件结构

| 文件 | 动作 | 职责 |
|------|------|------|
| `aiagent/common/.../aiagent/TLAiAgentParamString.java` | 修改 | 新增 5 个动作常量 + 1 个参数字段常量 |
| `aiagent/common/.../aiagent/approval/ApprovalGrant.java` | **新建** | 一条授权记录的数据类（含 toMap） |
| `aiagent/common/.../aiagent/approval/TLApprovalRequest.java` | 修改 | +ruleId +userId 字段 |
| `aiagent/common/.../aiagent/approval/TLApprovalModule.java` | 修改 | 核心：授权表/规则 id/新动作/超时收口/消息箱 |
| `aiagent/common/.../aiagent/test/TLTestApprovalReviewer.java` | **新建** | 测试审查人：立即 pending、记录请求供断言 |
| `aiagent/common/.../aiagent/test/TLAgentTestModule.java` | 修改 | 新增审批场景（消息级，不经 LLM） |
| `aiagent/skill-builtin/.../TLScheduleTaskSkill.java` | 修改 | 任务会话→发起会话映射登记/注销 |
| `aiagent/common/.../aiagent/TLAgentService.java` | 修改 | doApprove 扩展 scope；+doPermissions +doAuto |
| `aiagent/common/.../aiagent/TLChatConsole.java` | 修改 | /approve 两动词、/auto、/permissions、提示符、help、busy 白名单 |
| `aiagent/webui/src/main/resources/webui/chat.html` | 修改 | 弹框三批准按钮、自动运行开关、授权面板 |
| `aiagent/webui/src/main/resources/webui/app.js` | 修改 | 上述 UI 的接线与渲染 |
| `demo/tlobject/src/main/resources/conf/demo/aitest/approvalGate_config.xml` | **新建** | 测试审批配置（测试审查人 + 规则） |
| `demo/tlobject/src/main/resources/conf/demo/aitest/moduleFactory_config.xml` | 修改 | 解注 approvalGate 启动获取 |
| `run_aitest.bat`（仓库根） | **新建** | 跑测试 app 的启动脚本（classpath 复制自 aistart.bat，入口换 AiAgentDemoStartup） |

---

## Task 1: 常量与数据类

**Files:**
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAiAgentParamString.java`（在 `APPROVAL_PENDINGLIST` 定义 ~:441 之后插入）
- Create: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/approval/ApprovalGrant.java`
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/approval/TLApprovalRequest.java`（字段区 ~:43 与 getter 区）

- [ ] **Step 1: 加常量**（`TLAiAgentParamString.java`，紧接 `APPROVAL_PENDINGLIST` 行后）

```java
    /** 列出授权记录（会话级/轮次级免审 + 自动运行） */
    String APPROVAL_GRANTS = "approvalGrants";
    /** 撤销授权 */
    String APPROVAL_REVOKE = "approvalRevoke";
    /** 主动预授权单项（不弹框直接授予） */
    String APPROVAL_GRANT = "approvalGrant";
    /** 任务执行会话 → 发起会话 映射登记/注销（授权归一化到对话根用） */
    String APPROVAL_TASKSESSION = "approvalTaskSession";
    /** 审批模块自述（审查人/规则数，测试与排障用） */
    String APPROVAL_INFO = "approvalInfo";
    /** 批准的授权范围：once(默认)/round/session */
    String AI_P_APPROVAL_SCOPE = "approvalScope";
```

- [ ] **Step 2: 新建 ApprovalGrant.java**

```java
package cn.tianlong.tlobject.aiagent.approval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条授权记录（会话级/轮次级免审）。key 由 TLApprovalModule.grantKey() 统一构造。
 */
public class ApprovalGrant {

    public static final String SCOPE_ROUND = "round";
    public static final String SCOPE_SESSION = "session";
    /** 通配：ruleId/operation 为 "*" = 匹配任意（自动运行即 *+*） */
    public static final String WILDCARD = "*";

    public String sessionId;   // 归一化后的对话根会话 id
    public String scope;       // "round" | "session"
    public String roundId;     // round 级必填；session 级为 ""
    public String ruleId;      // 规则唯一标识；"*" = 自动运行
    public String operation;   // 命中时的操作名；提取不到 = ""；"*" = 全部操作
    public String toolName;    // 授予时命中的工具名（列表展示）
    public long grantedAt;

    public boolean isWildcard() { return WILDCARD.equals(ruleId); }

    /** 列表展示文本：自动运行 / 规则[:操作] */
    public String display() {
        if (isWildcard()) return "全部规则（自动运行）";
        String r = ruleId == null || ruleId.isEmpty() ? "(未知规则)" : ruleId;
        if (operation == null || operation.isEmpty()) return r;
        if (WILDCARD.equals(operation)) return r + "（全部操作）";
        return r + ":" + operation;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId == null ? "" : sessionId);
        m.put("scope", scope == null ? "" : scope);
        m.put("roundId", roundId == null ? "" : roundId);
        m.put("ruleId", ruleId == null ? "" : ruleId);
        m.put("operation", operation == null ? "" : operation);
        m.put("toolName", toolName == null ? "" : toolName);
        m.put("grantedAt", grantedAt);
        m.put("display", display());
        return m;
    }
}
```

- [ ] **Step 3: TLApprovalRequest 加两字段**（字段区 `private String toolCallId;` 之后；getter/setter 区 `getToolCallId()` 之后；`toMap()`/`fromMap()` 同步）

```java
    /** 命中的审批规则唯一标识（授权记忆的键之一） */
    private String ruleId;
    /** 发起用户（消息箱通知定位用；审批请求消息携带） */
    private String userId;
```
```java
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
```
`toMap()` 里加 `map.put("ruleId", ruleId); map.put("userId", userId);`；`fromMap()` 里加 `req.setRuleId((String) map.get("ruleId")); req.setUserId((String) map.get("userId"));`

- [ ] **Step 4: 编译**

Run: `D:/maven/bin/mvn -f D:/tlobjectapp/tlobject/pom.xml install -pl aiagent/common -am -DskipTests -q`
Expected: BUILD SUCCESS，无编译错误

- [ ] **Step 5: Commit**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAiAgentParamString.java \
        aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/approval/ApprovalGrant.java \
        aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/approval/TLApprovalRequest.java
git commit -m "审批授权 1/12：常量与数据类（ApprovalGrant + request 增 ruleId/userId）"
```

---

## Task 2: 测试床（测试审查人 + aitest 审批配置 + 跑测试脚本）

**Files:**
- Create: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/test/TLTestApprovalReviewer.java`
- Create: `demo/tlobject/src/main/resources/conf/demo/aitest/approvalGate_config.xml`
- Modify: `demo/tlobject/src/main/resources/conf/demo/aitest/moduleFactory_config.xml:98`（解注）
- Create: `run_aitest.bat`（仓库根）

- [ ] **Step 1: 新建测试审查人**

```java
package cn.tianlong.tlobject.aiagent.test;

import cn.tianlong.tlobject.aiagent.approval.IApprovalReviewer;
import cn.tianlong.tlobject.aiagent.approval.TLApprovalRequest;
import cn.tianlong.tlobject.base.TLMsg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 测试用审查人：不阻塞、立即返回 pending（异步语义），并记录收到的全部请求供场景断言。
 * 由 aitest 的 approvalGate_config.xml 以 reviewerClass 指定；
 * 生产配置（ConsoleReviewer）下审批场景自动跳过（见 TLAgentTestModule.skipReasonFor）。
 */
public class TLTestApprovalReviewer implements IApprovalReviewer {

    private static final List<TLApprovalRequest> received =
            Collections.synchronizedList(new ArrayList<TLApprovalRequest>());

    public static void reset() { received.clear(); }
    public static int count() { return received.size(); }

    public static List<TLApprovalRequest> snapshot() {
        synchronized (received) { return new ArrayList<>(received); }
    }

    @Override
    public TLMsg requestApproval(TLApprovalRequest request) {
        received.add(request);
        return new TLMsg().setParam("_approvalState", TLApprovalRequest.PENDING);
    }
}
```

- [ ] **Step 2: 新建 aitest 审批配置**（`conf/demo/aitest/approvalGate_config.xml`）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<moduleConfig>
    <!-- ===== 测试用审批配置（独立 aiagent 测试实例专用） =====
         审查人 = 测试专用：立即返回 pending、不阻塞、记录请求供场景断言。
         生产配置见 conf/demo/aiagent/approvalGate_config.xml（ConsoleReviewer 阻塞式）。 -->
    <params>
        <reviewerClass value="cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer"/>
        <reviewerStrategy value="sync"/>
        <!-- 场景 approvalExpire 需要秒级超时（sleep 后断言 EXPIRED） -->
        <approvalTimeoutMs value="3000"/>
    </params>
    <rules>
        <rule name="file_operation"
              statup="true"
              description="删除/写入文件需要审批"
              action="delete,write"/>
        <!-- 前缀规则（通配）样例：mcp* 命中 mcpEchoTool 等 -->
        <rule name="mcp*"
              statup="true"
              description="MCP 工具需要审批"
              rule="*"/>
    </rules>
</moduleConfig>
```

- [ ] **Step 3: 解注 aitest 工厂启动获取**

`conf/demo/aitest/moduleFactory_config.xml` 第 98 行附近，把：
```xml
        <!-- 获取审批模块（可选，启用审批时解注） -->
        <!-- <msg action="getModule" destination="moduleFactory" moduleName="approvalGate"/> -->
```
改为：
```xml
        <!-- 获取审批模块（测试床：审查人=TLTestApprovalReviewer，见 approvalGate_config.xml） -->
        <msg action="getModule" destination="moduleFactory" moduleName="approvalGate"/>
```

- [ ] **Step 4: 新建 run_aitest.bat**（复制 `aistart.bat`，仅把末尾入口类 `cn.tianlong.java.demo.aiagent.AIStart` 换成 `cn.tianlong.java.demo.aiagent.AiAgentDemoStartup`）

```bash
# 生成方式（保持 classpath 与 aistart.bat 完全一致，只换入口类）：
sed 's/cn\.tianlong\.java\.demo\.aiagent\.AIStart/cn.tianlong.java.demo.aiagent.AiAgentDemoStartup/' aistart.bat > run_aitest.bat
```

- [ ] **Step 5: 编译 + 跑测试 app 验证审查人加载**

```bash
D:/maven/bin/mvn -f D:/tlobjectapp/tlobject/pom.xml install -pl aiagent/common,demo/tlobject -am -DskipTests -q
cd D:/tlobjectapp/tlobject && cmd //c run_aitest.bat > _run_aitest.log 2>&1 &
sleep 25 && grep -E "Reviewer loaded|\[TEST\].*通过=|BUILD|Exception" _run_aitest.log | head -20
```
Expected: 日志出现 `Reviewer loaded: cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer`；全部既有场景通过（通过=N, 失败=0）；无 Exception。跑完 `taskkill //F //IM java.exe` 或 Ctrl+C 结束（应用常驻）。
若测试 app 不退出，用 `grep "\[TEST\] 用例\|通过=" _run_aitest.log` 看结果后再杀进程。

- [ ] **Step 6: Commit**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/test/TLTestApprovalReviewer.java \
        demo/tlobject/src/main/resources/conf/demo/aitest/approvalGate_config.xml \
        demo/tlobject/src/main/resources/conf/demo/aitest/moduleFactory_config.xml run_aitest.bat
git commit -m "审批授权 2/12：测试床（TLTestApprovalReviewer + aitest 审批配置 + run_aitest.bat）"
```

---

## Task 3: 规则匹配重构（ruleId 推导，纯重构不改行为）

**Files:**
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/approval/TLApprovalModule.java`
  - 字段区（~:52）：+`prefixRuleNames`
  - `isApprovalRequired`（:570）：改为薄包装 + 新增 `matchRule`
  - `parseApprovalRules`（:666）与 `applyStructuredRules`（:700）：填充 `prefixRuleNames`

- [ ] **Step 1: 加字段**（`approvalRulePrefixes` 定义之后）

```java
    /** 前缀 → 配置里的规则名（ruleId 归一用；解析规则时填充，与 approvalRulePrefixes 同步） */
    private final Map<String, String> prefixRuleNames = new LinkedHashMap<>();
```

- [ ] **Step 2: 新增 RuleMatch + matchRule，isApprovalRequired 改薄包装**（替换现 `isApprovalRequired` 方法体，保留原 javadoc）

```java
    /** 规则匹配结果：是否需审批 + 命中的规则 id + 提取到的操作名（授权记忆的键） */
    public static class RuleMatch {
        public boolean required;
        public String ruleId = "";
        public String operation = "";
    }

    /**
     * 一次匹配返回完整结果（规则判定与操作提取本是一趟逻辑，供审批与授权共用）。
     * 语义与旧 isApprovalRequired 逐条对齐：前缀命中即需审批；精确规则按操作集合；
     * 提取不到操作名按安全侧（需审批）。
     */
    public RuleMatch matchRule(String toolName, Map<String, Object> args) {
        RuleMatch r = new RuleMatch();
        if (approvalRules.isEmpty() && approvalRulePrefixes.isEmpty()) {
            return r;  // 未配置规则，全部放行
        }
        for (String prefix : approvalRulePrefixes) {
            if (toolName.startsWith(prefix)) {
                r.required = true;
                r.ruleId = prefixRuleNames.getOrDefault(prefix, prefix);
                String op = extractOperation(toolName, args);
                r.operation = op != null ? op : "";
                return r;
            }
        }
        Set<String> ops = approvalRules.get(toolName);
        if (ops == null) return r;
        r.ruleId = toolName;
        if (ops.isEmpty()) {
            r.required = true;
            String op = extractOperation(toolName, args);
            r.operation = op != null ? op : "";
            return r;
        }
        String operation = extractOperation(toolName, args);
        if (operation == null) {
            putLog("审批规则命中了工具 " + toolName + "，但提取不到操作名，按需要审批处理", LogLevel.WARN);
            r.required = true;
            r.operation = "";
            return r;
        }
        if (ops.contains(operation)) {
            r.required = true;
            r.operation = operation;
        }
        return r;
    }

    /**
     * 判断一个工具调用是否需要审批（公开 API，保留签名；实现委托 matchRule）。
     */
    public boolean isApprovalRequired(String toolName, Map<String, Object> args) {
        return matchRule(toolName, args).required;
    }
```

- [ ] **Step 3: 两个解析器填充 prefixRuleNames**

`parseApprovalRules`：开头 `approvalRulePrefixes.clear();` 后加 `prefixRuleNames.clear();`；
- `tool:*` 分支：`approvalRulePrefixes.add(tool + ":");` 之后加 `prefixRuleNames.put(tool + ":", rule);`（rule = 去空格后的原始 token）
- `tool*` 分支：`approvalRulePrefixes.add(rule.substring(0, rule.length() - 1));` 之后加 `prefixRuleNames.put(rule.substring(0, rule.length() - 1), rule);`

`applyStructuredRules`：开头 `approvalRulePrefixes.clear();` 后加 `prefixRuleNames.clear();`；
- `name.endsWith(":*")` 分支：`approvalRulePrefixes.add(tool + ":");` 之后加 `prefixRuleNames.put(tool + ":", name);`
- `name.endsWith("*")` 分支：`approvalRulePrefixes.add(name.substring(0, name.length() - 1));` 之后加 `prefixRuleNames.put(name.substring(0, name.length() - 1), name);`

- [ ] **Step 4: 回归验证（纯重构，行为应完全不变）**

```bash
D:/maven/bin/mvn -f D:/tlobjectapp/tlobject/pom.xml install -pl aiagent/common,demo/tlobject -am -DskipTests -q
cd D:/tlobjectapp/tlobject && cmd //c run_aitest.bat > _run_aitest.log 2>&1 & sleep 25
grep -E "失败=|FAIL|Exception" _run_aitest.log | head; taskkill //F //IM java.exe 2>/dev/null; true
```
Expected: 全部既有场景仍通过，失败=0

- [ ] **Step 5: Commit**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/approval/TLApprovalModule.java
git commit -m "审批授权 3/12：规则匹配重构（matchRule 返回 ruleId+operation，isApprovalRequired 薄包装）"
```

---

## Task 4: 授权表与核心流（命中自动批准 + scope 写入 + 场景验证）

**Files:**
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/approval/TLApprovalModule.java`
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/test/TLAgentTestModule.java`
- Test: 场景 `approvalSessionGrant`（/test 运行）

- [ ] **Step 1: 先写失败场景**（`TLAgentTestModule.java`）
  a) `TEST_CASES` 静态块（:460 `streamSyncTrunc` 之后）加：
```java
        TEST_CASES.put("approvalSessionGrant", new String[]{"16-审批会话免审", "approvalGate 非测试审查人（需 aitest 配置）"});
```
  b) `resolveTestCase` switch（:527 `streamsyncTrunc` 行后）加：
```java
            case "approvalsessiongrant": return this::testApprovalSessionGrant;
```
  b2) **跳过机制**（关键：生产 app 的 approvalGate 是阻塞式 ConsoleReviewer，审批场景在那儿跑会把测试线程挂死 5 分钟）——在 `skipReasonFor(def, skillsOk)` 方法内、现有判定分支之后插入：
```java
        // 审批场景：必须跑在测试审查人环境下（aitest 配置）；生产 ConsoleReviewer 会阻塞测试线程
        if (name != null && name.toLowerCase().startsWith("approval")) {
            Object gate = getModuleInFactory("approvalGate");
            if (!(gate instanceof IObject)) return "approvalGate 未配置";
            TLMsg info = putMsg((IObject) gate, createMsg().setAction(APPROVAL_INFO));
            String rc = info != null ? info.getStringParam("reviewerClass", "") : "";
            if (!rc.endsWith("TLTestApprovalReviewer")) return "非测试审查人（" + rc + "），审批场景需 aitest 配置";
        }
```
  （`skipReasonFor` 的实参名以实际代码为准；`skipTest(reason)` 会把它记为"跳过"而非"失败"——aiagent 主应用的 /test 因此不会误报失败）
  c) 场景方法（放文件末尾 `testStreamSyncTrunc` 之后）：
```java
    // ======================== 场景 16: 审批授权（消息级，不经 LLM） ========================

    /** 审批授权测试用会话 */
    protected static final String SID_APPROVAL = "test_approval_grant";

    /**
     * 会话级免审：首请求 pending → 批准(scope=session) → 同规则再请求自动批准（不再问审查人）；
     * 不同操作仍问（粒度隔离）。
     */
    protected TLMsg testApprovalSessionGrant(Object fromWho, TLMsg msg) {
        // 防御性守卫：模块须存在（skipReasonFor 已兜底，这里是第二道）
        Object gate = getModuleInFactory("approvalGate");
        if (!(gate instanceof IObject)) {
            return createMsg().setParam(RESULT, false).setParam("error", "approvalGate 未配置");
        }
        cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer.reset();
        resetApprovalGrants();   // 套件重入性：同一进程二次 /test 时授权不能残留

        // 1. 首请求：file_operation:delete → pending，审查人被问一次
        TLMsg r1 = requestApprovalFor("file_operation", "{\"operation\":\"delete\",\"path\":\"D:/tmp/a.txt\"}");
        if (!"pending".equals(r1.getStringParam(AI_P_APPROVAL_STATE, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "首请求应 pending，实为 "
                    + r1.getStringParam(AI_P_APPROVAL_STATE, ""));
        }
        // 2. 批准并授予"本会话"
        TLMsg ap = putMsg("approvalGate", createMsg().setAction(APPROVAL_APPROVE)
                .setParam(AI_P_APPROVAL_ID, r1.getStringParam(AI_P_APPROVAL_ID, ""))
                .setParam(AI_P_APPROVAL_SCOPE, "session"));
        if (!ap.parseBoolean(RESULT, false)) {
            return createMsg().setParam(RESULT, false).setParam("error", "批准失败: " + ap.getStringParam("error", ""));
        }
        // 3. 同规则再请求 → 自动批准，审查人计数不变
        TLMsg r2 = requestApprovalFor("file_operation", "{\"operation\":\"delete\",\"path\":\"D:/tmp/b.txt\"}");
        if (!"approved".equals(r2.getStringParam(AI_P_APPROVAL_STATE, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "授权命中应 approved，实为 "
                    + r2.getStringParam(AI_P_APPROVAL_STATE, ""));
        }
        if (cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer.count() != 1) {
            return createMsg().setParam(RESULT, false).setParam("error",
                    "授权命中不应再问审查人，计数=" + cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer.count());
        }
        // 4. 粒度隔离：同规则不同操作（write）仍需审批
        TLMsg r3 = requestApprovalFor("file_operation", "{\"operation\":\"write\",\"path\":\"D:/tmp/c.txt\"}");
        if (!"pending".equals(r3.getStringParam(AI_P_APPROVAL_STATE, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "write 应仍 pending（粒度隔离），实为 "
                    + r3.getStringParam(AI_P_APPROVAL_STATE, ""));
        }
        log("[TEST]   审批授权: 会话免审/粒度隔离 通过（审查人计数="
                + cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer.count() + "）");
        return createMsg().setParam(RESULT, true);
    }

    /**
     * 清掉审批场景用会话的全部授权。授权表在 approvalGate 进程内驻留，
     * 不像会话上下文那样有 resetTestSessions 兜底——不清则同一进程二次 /test 时场景必挂。
     */
    private void resetApprovalGrants() {
        putMsg("approvalGate", createMsg().setAction(APPROVAL_REVOKE)
                .setParam("sessionId", SID_APPROVAL).setParam("all", true));
        putMsg("approvalGate", createMsg().setAction(APPROVAL_REVOKE)
                .setParam("sessionId", "test_approval_parent").setParam("all", true));
    }

    /** 向 approvalGate 发一条审批请求（消息级，不经执行器/LLM；调用方须先守卫模块存在） */
    private TLMsg requestApprovalFor(String toolName, String argsJson) {
        java.util.Map<String, Object> args = new com.google.gson.Gson().fromJson(argsJson, java.util.Map.class);
        return putMsg("approvalGate", createMsg().setAction(APPROVAL_REQUEST)
                .setParam("toolName", toolName)
                .setParam("toolArgs", args)
                .setSystemParam(AI_P_SESSIONID, SID_APPROVAL)
                .setSystemParam("userId", TEST_USER_ID)
                .setSystemParam(AI_P_ROUNDID, "r_test_1"));
    }
```
  ⚠️ **模块名陷阱（必读）**：`M_APPROVALMODULE` 常量值 = `"approvalModule"`，**不是**模块名；工厂里的模块名是 `approvalGate`（见 `TLWebChatModule:703` 用法）。且本框架 **putMsg 到不存在的模块会关掉整个应用**——场景里必须用字面量 `"approvalGate"`，并且在发消息前先守卫：
```java
        Object gate = getModuleInFactory("approvalGate");
        if (!(gate instanceof IObject)) {
            return createMsg().setParam(RESULT, false).setParam("error", "approvalGate 未配置");
        }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
D:/maven/bin/mvn -f D:/tlobjectapp/tlobject/pom.xml install -pl aiagent/common,demo/tlobject -am -DskipTests -q
cd D:/tlobjectapp/tlobject && cmd //c run_aitest.bat > _run_aitest.log 2>&1 & sleep 25
grep -E "approvalSessionGrant|16-审批" _run_aitest.log | head; taskkill //F //IM java.exe 2>/dev/null; true
```
Expected: `approvalSessionGrant` FAIL（error 含 "审查人不是" 或 "批准失败"，因 APPROVAL_INFO/scope 尚未实现）

- [ ] **Step 3: 实现授权表与核心流**（`TLApprovalModule.java`）

  a) 字段（`rejectedOps` 定义 ~:90 之后）：
```java
    // ======================== 授权记忆（会话/轮次级免审） ========================

    /** 授权表：key = grantKey(resolve(sessionId), scope, roundId, ruleId, operation) */
    private final Map<String, ApprovalGrant> approvedGrants =
            java.util.Collections.synchronizedMap(new LinkedHashMap<String, ApprovalGrant>());
    /** 授权表上限（LRU 驱逐最旧；同 rejectedOps 的既有模式） */
    private static final int MAX_APPROVED_GRANTS = 200;

    /** 任务执行会话 → 发起会话（TLScheduleTaskSkill 登记；授权归一化到对话根用） */
    private final Map<String, String> taskSessionParents =
            java.util.Collections.synchronizedMap(new LinkedHashMap<String, String>());
    private static final int MAX_TASK_SESSIONS = 200;
```
  b) 消息路由（`checkMsgAction` :220 switch）加：
```java
            case APPROVAL_INFO:
                return createMsg().setParam(RESULT, true)
                        .setParam("reviewerClass", reviewer != null ? reviewer.getClass().getName() : "");
```
  c) 工具方法（放"规则匹配"区附近）：
```java
    /** 会话归一化：任务执行会话解析为其发起会话（授权归一化到对话根），其他会话返回自身 */
    private String resolveSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return "";
        String parent = taskSessionParents.get(sessionId);
        return parent != null && !parent.isEmpty() ? parent : sessionId;
    }

    /** 授权键（写入与查询必须走同一构造） */
    private String grantKey(String sessionId, String scope, String roundId, String ruleId, String operation) {
        return sessionId + "|" + scope + "|" + (roundId == null ? "" : roundId) + "|"
                + (ruleId == null ? "" : ruleId) + "|" + (operation == null ? "" : operation);
    }

    /**
     * 授权命中判定：先查通配（自动运行），再精确 规则+操作，最后规则级（预授权不指定操作 = 全操作）；
     * 会话级优先，轮次级需 roundId 非空。返回命中的授权或 null。
     */
    private ApprovalGrant findGrant(String sessionId, String roundId, String ruleId, String operation) {
        String sid = resolveSession(sessionId);
        String[] scopes = { ApprovalGrant.SCOPE_SESSION, ApprovalGrant.SCOPE_ROUND };
        for (String scope : scopes) {
            boolean isRound = ApprovalGrant.SCOPE_ROUND.equals(scope);
            if (isRound && (roundId == null || roundId.isEmpty())) continue;
            String rd = isRound ? roundId : "";
            ApprovalGrant g = approvedGrants.get(
                    grantKey(sid, scope, rd, ApprovalGrant.WILDCARD, ApprovalGrant.WILDCARD));
            if (g != null) return g;
            g = approvedGrants.get(grantKey(sid, scope, rd, ruleId, operation));
            if (g != null) return g;
            g = approvedGrants.get(grantKey(sid, scope, rd, ruleId, ApprovalGrant.WILDCARD));
            if (g != null) return g;
        }
        return null;
    }

    /** 写入一条授权（幂等覆盖；超上限驱逐最旧） */
    private void addGrant(String scope, TLApprovalRequest request) {
        ApprovalGrant g = new ApprovalGrant();
        g.sessionId = resolveSession(request.getSessionId());
        g.scope = scope;
        g.roundId = ApprovalGrant.SCOPE_ROUND.equals(scope)
                ? (request.getRoundId() == null ? "" : request.getRoundId()) : "";
        g.ruleId = request.getRuleId() == null ? "" : request.getRuleId();
        g.operation = request.getOperation() == null ? "" : request.getOperation();
        g.toolName = request.getToolName();
        g.grantedAt = System.currentTimeMillis();
        synchronized (approvedGrants) {
            if (approvedGrants.size() >= MAX_APPROVED_GRANTS) {
                String eldest = approvedGrants.keySet().iterator().next();
                approvedGrants.remove(eldest);
            }
            approvedGrants.put(grantKey(g.sessionId, g.scope, g.roundId, g.ruleId, g.operation), g);
        }
        putLog("Grant added: scope=" + scope + " rule=" + g.ruleId + " op=" + g.operation
                + " session=" + g.sessionId, LogLevel.INFO);
    }

    /** 惰性清理：清掉该会话下 roundId ≠ 当前轮次的轮次授权（一轮完毕自然消亡） */
    private void pruneRoundGrants(String sessionId, String currentRoundId) {
        String sid = resolveSession(sessionId);
        String cur = currentRoundId == null ? "" : currentRoundId;
        synchronized (approvedGrants) {
            java.util.Iterator<Map.Entry<String, ApprovalGrant>> it = approvedGrants.entrySet().iterator();
            while (it.hasNext()) {
                ApprovalGrant g = it.next().getValue();
                if (ApprovalGrant.SCOPE_ROUND.equals(g.scope) && sid.equals(g.sessionId)
                        && !cur.equals(g.roundId)) {
                    it.remove();
                }
            }
        }
    }
```
  d) `handleApprovalRequest`（:327）改造——替换规则判定段（现 `if (!isApprovalRequired(toolName, toolArgs)) {...}`）：
```java
        String roundIdStr = String.valueOf(msg.getSystemParam(AI_P_ROUNDID, ""));
        String userId = String.valueOf(msg.getSystemParam("userId", ""));
        pruneRoundGrants(sessionId, roundIdStr);

        // 1. 规则判定 + 2. 授权命中自动批准（规则匹配与操作提取共用一趟）
        RuleMatch match = matchRule(toolName, toolArgs);
        if (!match.required) {
            return createMsg().setParam(AI_P_APPROVAL_STATE, "approved");
        }
        ApprovalGrant hit = findGrant(sessionId, roundIdStr, match.ruleId, match.operation);
        if (hit != null) {
            putLog("Approval auto-approved by grant: tool=" + toolName + " rule=" + match.ruleId
                    + " op=" + match.operation + " scope=" + hit.scope + " session=" + sessionId, LogLevel.INFO);
            traceStage(sessionId, String.valueOf(msg.getSystemParam("rootSessionId", sessionId)),
                    roundIdStr, "approvalDecided",
                    "auto-approved(" + hit.scope + (hit.isWildcard() ? "/wildcard" : "")
                            + ") rule=" + match.ruleId, 0);
            return createMsg().setParam(RESULT, true).setParam(AI_P_APPROVAL_STATE, "approved")
                    .setParam(AI_P_SKILLOUTPUT, "审批通过（已授权）");
        }
```
   原"2. 提取操作名 + 确定风险级别"段改为复用 `match`：
```java
        String operation = match.operation;
        String riskLevel = resolveRiskLevel(toolName, operation);
```
   （原 `String operation = extractOperation(toolName, toolArgs);` 一行删除）
   创建请求段（:365）加：
```java
        request.setRuleId(match.ruleId);
        request.setUserId(userId);
```
  e) `handleApprove`（:419）写授权——在 `request.setState(TLApprovalRequest.APPROVED);` 之前插入：
```java
        String scope = msg.getStringParam(AI_P_APPROVAL_SCOPE, "once");
        if (!"once".equals(scope)) {
            if (!ApprovalGrant.SCOPE_ROUND.equals(scope) && !ApprovalGrant.SCOPE_SESSION.equals(scope)) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "未知授权范围: " + scope + "（可用 once/round/session）");
            }
            if (ApprovalGrant.SCOPE_ROUND.equals(scope)
                    && (request.getRoundId() == null || request.getRoundId().isEmpty())) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "该请求无轮次信息，无法按轮授权（可用 once/session）");
            }
            addGrant(scope, request);
        }
```

- [ ] **Step 4: 跑测试确认通过**

```bash
D:/maven/bin/mvn -f D:/tlobjectapp/tlobject/pom.xml install -pl aiagent/common,demo/tlobject -am -DskipTests -q
cd D:/tlobjectapp/tlobject && cmd //c run_aitest.bat > _run_aitest.log 2>&1 & sleep 25
grep -E "16-审批|失败=|FAIL" _run_aitest.log | head; taskkill //F //IM java.exe 2>/dev/null; true
```
Expected: 16-审批会话免审 PASS，全量失败=0

- [ ] **Step 5: Commit**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/approval/TLApprovalModule.java \
        aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/test/TLAgentTestModule.java
git commit -m "审批授权 4/12：授权表+命中自动批准+scope 写入；场景 16 会话免审/粒度隔离"
```

---

## Task 5: 管理动作（列表/撤销/预授权/任务映射）+ 场景

**Files:**
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/approval/TLApprovalModule.java`
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/test/TLAgentTestModule.java`（场景 17）

- [ ] **Step 1: 先写失败场景**（`TEST_CASES` 加 `approvalGrantAdmin` = `{"17-审批授权管理", "approvalGate 非测试审查人"}`；`resolveTestCase` 加 `case "approvalgrantadmin": return this::testApprovalGrantAdmin;`）

```java
    /**
     * 管理动作：预授权（grant）→ 直接放行；列表可见；撤销后恢复弹框；
     * 通配预授权（自动运行等价）；任务会话继承（登记 task→parent）。
     */
    protected TLMsg testApprovalGrantAdmin(Object fromWho, TLMsg msg) {
        Object gate = getModuleInFactory("approvalGate");
        if (!(gate instanceof IObject)) {
            return createMsg().setParam(RESULT, false).setParam("error", "approvalGate 未配置");
        }
        cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer.reset();
        resetApprovalGrants();

        // 1. 预授权 file_operation:write（不弹框）
        TLMsg g = putMsg("approvalGate", createMsg().setAction(APPROVAL_GRANT)
                .setParam("sessionId", SID_APPROVAL)
                .setParam("ruleId", "file_operation").setParam("operation", "write"));
        if (!g.parseBoolean(RESULT, false)) {
            return createMsg().setParam(RESULT, false).setParam("error", "预授权失败");
        }
        TLMsg r1 = requestApprovalFor("file_operation", "{\"operation\":\"write\",\"path\":\"D:/tmp/w.txt\"}");
        if (!"approved".equals(r1.getStringParam(AI_P_APPROVAL_STATE, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "预授权后应直接 approved");
        }
        // 2. 列表可见
        TLMsg list = putMsg("approvalGate", createMsg().setAction(APPROVAL_GRANTS)
                .setParam("sessionId", SID_APPROVAL));
        List<?> grants = list.getListParam("grants", new ArrayList<>());
        if (grants.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "授权列表为空");
        }
        // 3. 撤销后恢复弹框
        TLMsg rv = putMsg("approvalGate", createMsg().setAction(APPROVAL_REVOKE)
                .setParam("sessionId", SID_APPROVAL)
                .setParam("ruleId", "file_operation").setParam("operation", "write"));
        TLMsg r2 = requestApprovalFor("file_operation", "{\"operation\":\"write\",\"path\":\"D:/tmp/w2.txt\"}");
        if (!"pending".equals(r2.getStringParam(AI_P_APPROVAL_STATE, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "撤销后应恢复 pending");
        }
        // 4. 通配预授权（自动运行等价）：任意规则直接放行
        putMsg("approvalGate", createMsg().setAction(APPROVAL_GRANT)
                .setParam("sessionId", SID_APPROVAL)
                .setParam("ruleId", "*").setParam("operation", "*"));
        TLMsg r3 = requestApprovalFor("mcpEchoTool", "{\"x\":1}");
        if (!"approved".equals(r3.getStringParam(AI_P_APPROVAL_STATE, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "自动运行（通配）应直接 approved");
        }
        // 5. 任务继承：登记 task→parent 后，父会话授权对任务会话请求生效
        String parent = "test_approval_parent";
        putMsg("approvalGate", createMsg().setAction(APPROVAL_GRANT)
                .setParam("sessionId", parent)
                .setParam("ruleId", "file_operation").setParam("operation", "delete"));
        putMsg("approvalGate", createMsg().setAction(APPROVAL_TASKSESSION)
                .setParam("execSession", "task_x_1").setParam("parentSessionId", parent));
        java.util.Map<String, Object> dArgs = new com.google.gson.Gson()
                .fromJson("{\"operation\":\"delete\",\"path\":\"D:/tmp/d.txt\"}", java.util.Map.class);
        TLMsg r4 = putMsg("approvalGate", createMsg().setAction(APPROVAL_REQUEST)
                .setParam("toolName", "file_operation").setParam("toolArgs", dArgs)
                .setSystemParam(AI_P_SESSIONID, "task_x_1")
                .setSystemParam("userId", TEST_USER_ID)
                .setSystemParam(AI_P_ROUNDID, "r_task_1"));
        if (!"approved".equals(r4.getStringParam(AI_P_APPROVAL_STATE, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "任务会话应继承父会话授权，实为 "
                    + r4.getStringParam(AI_P_APPROVAL_STATE, ""));
        }
        log("[TEST]   审批授权管理: 预授权/列表/撤销/通配/任务继承 通过");
        return createMsg().setParam(RESULT, true);
    }
```

- [ ] **Step 2: 跑测试确认失败**（同 Task 4 Step 2 命令，grep `17-审批`）

- [ ] **Step 3: 实现四个动作**（`TLApprovalModule.java`）

  a) `checkMsgAction` 加 case：
```java
            case APPROVAL_GRANTS:
                return handleGrants(fromWho, msg);
            case APPROVAL_REVOKE:
                return handleRevoke(fromWho, msg);
            case APPROVAL_GRANT:
                return handleGrantPregrant(fromWho, msg);
            case APPROVAL_TASKSESSION:
                return handleTaskSession(fromWho, msg);
```
  b) 处理方法（放 handlePendingList 之后）：
```java
    /** 列出授权（sessionId 可选过滤；all=true 或无 sessionId = 全部） */
    private TLMsg handleGrants(Object fromWho, TLMsg msg) {
        String filter = msg.getStringParam("sessionId", "");
        List<Map<String, Object>> list = new ArrayList<>();
        synchronized (approvedGrants) {
            for (ApprovalGrant g : approvedGrants.values()) {
                if (!filter.isEmpty() && !filter.equals(g.sessionId)) continue;
                list.add(g.toMap());
            }
        }
        return createMsg().setParam(RESULT, true).setParam("grants", list);
    }

    /**
     * 撤销授权：sessionId 必填；all=true 清该会话全部；
     * 否则按 ruleId(+operation) 删（operation 缺省 = 该规则下全部操作，含规则级/通配记录）。
     * 返回删除条数。
     */
    private TLMsg handleRevoke(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam("sessionId", "");
        if (sessionId.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "missing sessionId");
        }
        boolean all = msg.parseBoolean("all", false);
        boolean hasRule = msg.containsParam("ruleId");
        if (!all && !hasRule) {
            return createMsg().setParam(RESULT, false).setParam("error", "需要 ruleId 或 all=true");
        }
        String ruleId = msg.getStringParam("ruleId", "");
        boolean hasOp = msg.containsParam("operation");
        String operation = msg.getStringParam("operation", "");
        int removed = 0;
        synchronized (approvedGrants) {
            java.util.Iterator<Map.Entry<String, ApprovalGrant>> it = approvedGrants.entrySet().iterator();
            while (it.hasNext()) {
                ApprovalGrant g = it.next().getValue();
                if (!sessionId.equals(g.sessionId)) continue;
                if (!all) {
                    if (!ruleId.equals(g.ruleId)) continue;
                    if (hasOp && !operation.equals(g.operation)) continue;
                }
                it.remove();
                removed++;
            }
        }
        putLog("Grants revoked: session=" + sessionId + " all=" + all
                + " rule=" + ruleId + " removed=" + removed, LogLevel.INFO);
        return createMsg().setParam(RESULT, true).setParam("removed", removed);
    }

    /** 主动预授权：不弹框直接写入授权表（ruleId 必填；operation 缺省 = 该规则全部操作） */
    private TLMsg handleGrantPregrant(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam("sessionId", "");
        String ruleId = msg.getStringParam("ruleId", "");
        if (sessionId.isEmpty() || ruleId.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "需要 sessionId 与 ruleId");
        }
        ApprovalGrant g = new ApprovalGrant();
        g.sessionId = resolveSession(sessionId);
        g.scope = ApprovalGrant.SCOPE_SESSION;
        g.roundId = "";
        g.ruleId = ruleId;
        g.operation = msg.containsParam("operation")
                ? msg.getStringParam("operation", "")
                : ApprovalGrant.WILDCARD;
        g.toolName = "-";
        g.grantedAt = System.currentTimeMillis();
        synchronized (approvedGrants) {
            if (approvedGrants.size() >= MAX_APPROVED_GRANTS) {
                approvedGrants.remove(approvedGrants.keySet().iterator().next());
            }
            approvedGrants.put(grantKey(g.sessionId, g.scope, g.roundId, g.ruleId, g.operation), g);
        }
        putLog("Grant pre-granted: rule=" + ruleId + " op=" + g.operation + " session=" + g.sessionId, LogLevel.INFO);
        return createMsg().setParam(RESULT, true);
    }

    /** 任务会话 → 发起会话 映射登记/注销（parentSessionId 为空 = 注销） */
    private TLMsg handleTaskSession(Object fromWho, TLMsg msg) {
        String execSession = msg.getStringParam("execSession", "");
        if (execSession.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "missing execSession");
        }
        String parent = msg.getStringParam("parentSessionId", "");
        if (parent.isEmpty()) {
            taskSessionParents.remove(execSession);
        } else {
            synchronized (taskSessionParents) {
                if (taskSessionParents.size() >= MAX_TASK_SESSIONS) {
                    taskSessionParents.remove(taskSessionParents.keySet().iterator().next());
                }
                taskSessionParents.put(execSession, parent);
            }
        }
        return createMsg().setParam(RESULT, true);
    }
```

- [ ] **Step 4: 跑测试确认通过**（grep `17-审批` 与 `失败=`）
- [ ] **Step 5: Commit**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/approval/TLApprovalModule.java \
        aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/test/TLAgentTestModule.java
git commit -m "审批授权 5/12：管理动作（列表/撤销/预授权/任务映射）+ 场景 17"
```

---

## Task 6: 超时收口 + 消息箱兜底 + 场景

**Files:**
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/approval/TLApprovalModule.java`
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/test/TLAgentTestModule.java`（场景 18）
- Modify: `aiagent/webui/src/main/java/cn/tianlong/tlobject/aiagent/webui/TLWebChatModule.java`（退出即拒补消息箱，过渡行为）

- [ ] **Step 1: 先写失败场景**（`TEST_CASES.put("approvalExpire", new String[]{"18-审批超时收口", "aitest 审批测试床"});`；`resolveTestCase` 加 `case "approvalexpire": return this::testApprovalExpire;`）

```java
    /** 超时收口：请求发出后超过 approvalTimeoutMs（aitest 配 3000ms）→ EXPIRED，且审批提交返回失败 */
    protected TLMsg testApprovalExpire(Object fromWho, TLMsg msg) {
        cn.tianlong.tlobject.aiagent.test.TLTestApprovalReviewer.reset();
        resetApprovalGrants();
        TLMsg r1 = requestApprovalFor("file_operation", "{\"operation\":\"delete\",\"path\":\"D:/tmp/exp.txt\"}");
        String id = r1.getStringParam(AI_P_APPROVAL_ID, "");
        if (!"pending".equals(r1.getStringParam(AI_P_APPROVAL_STATE, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "应 pending");
        }
        try { Thread.sleep(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        TLMsg q = putMsg("approvalGate", createMsg().setAction(APPROVAL_QUERY)
                .setParam(AI_P_APPROVAL_ID, id));
        String state = q.getStringParam(AI_P_APPROVAL_STATE, "");
        if (!"expired".equals(state)) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "应 expired（两条超时路径收口），实为 " + state);
        }
        // 超时后再批准应被拒绝（状态非 pending）
        TLMsg ap = putMsg("approvalGate", createMsg().setAction(APPROVAL_APPROVE)
                .setParam(AI_P_APPROVAL_ID, id));
        if (ap.parseBoolean(RESULT, false)) {
            return createMsg().setParam(RESULT, false).setParam("error", "已超时的审批不应可再批准");
        }
        log("[TEST]   审批超时收口: EXPIRED + 拒绝迟到批准 通过");
        return createMsg().setParam(RESULT, true);
    }
```

- [ ] **Step 2: 跑一遍留基线**（同 Task 4 Step 2 命令，grep `18-审批`）

此场景是**重构守卫**而非红绿驱动：现有 `scheduleTimeout` 已能置 EXPIRED，所以重构前它就该通过。
Expected: PASS（若 FAIL，说明对现有超时语义的理解有误，先查清再重构）。Step 3 重构（收口 + 消息箱）后必须**仍然通过**——收口不得改变外部行为。

- [ ] **Step 3: 实现收口 + 消息箱**

  a) `handleApprovalRequest` 中创建请求后（`scheduleTimeout(...)` 调用处）保持不变；改造 `scheduleTimeout` 的超时任务与 `waitForDecision` 的超时分支，统一走：
```java
    /**
     * 统一的"超时失效"收口：改状态 + 唤醒等待线程 + 消息箱告知。
     * 两条超时路径（scheduleTimeout 定时任务 / waitForDecision 的 latch 自超时）都必须走这里。
     */
    private void markExpired(TLApprovalRequest request, String reason) {
        if (request == null || !TLApprovalRequest.PENDING.equals(request.getState())) return;
        request.setState(TLApprovalRequest.EXPIRED);
        if (request.getRejectionReason() == null || request.getRejectionReason().isEmpty()) {
            request.setRejectionReason(reason);
        }
        signalDecision(request.getApprovalId());
        putLog("Approval expired: id=" + request.getApprovalId() + " reason=" + request.getRejectionReason(),
                LogLevel.INFO);
        notifyInbox(request, "审批超时未处理，已按拒绝处理");
    }

    /** 审批未被受理而终结时写消息箱（无 webui 模块 / 无 userId 时静默跳过） */
    private void notifyInbox(TLApprovalRequest request, String note) {
        if (request.getUserId() == null || request.getUserId().isEmpty()
                || "null".equals(request.getUserId())) return;
        try {
            Object webui = getModuleInFactory("webui");
            if (!(webui instanceof IObject)) return;
            String text = "⚠️ " + note + "：工具 " + request.getToolName()
                    + (request.getDescription() != null && !request.getDescription().isEmpty()
                            ? "（" + request.getDescription() + "）" : "")
                    + "，会话 " + (request.getSessionId() != null ? request.getSessionId() : "");
            TLMsg m = createMsg().setAction("inboxAdd")
                    .setParam("userId", request.getUserId())
                    .setParam("source", "approval")
                    .setParam("taskId", request.getApprovalId())
                    .setParam("text", text);
            m.setSystemParam(IGNOREMODULEISNULL, true);
            putMsg((IObject) webui, m);
        } catch (Exception e) {
            putLog("审批消息箱通知失败: " + e, LogLevel.DEBUG);
        }
    }
```
  b) `scheduleTimeout`（:801）的任务体改为：
```java
        timeoutScheduler.schedule(() -> {
            TLApprovalRequest request = pendingApprovals.get(approvalId);
            if (request != null && TLApprovalRequest.PENDING.equals(request.getState())) {
                markExpired(request, "审批超时（" + (timeoutMs / 1000) + "秒）");
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
```
  c) `waitForDecision`（:243）的 `if (!ok) {...}` 分支改为：
```java
            if (!ok) {
                markExpired(request, "审批超时（" + (approvalTimeoutMs / 1000) + "秒）");
            } else if (Thread.currentThread().isInterrupted()) {
```
  （原来该分支里的三行 setState/setRejectionReason/putLog 删除）
  d) `TLWebChatModule.rejectPendingApprovals`（:721）循环体内，`putMsg("approvalGate", ...APPROVAL_REJECT...)` 之后补写消息箱（过渡行为；阶段二将改为"退出转断点"）：
```java
                // 审批未被受理而终结 → 消息箱兜底告知（与 approvalGate 的超时告知同口径）
                // 注意：本方法就在 TLWebChatModule 内，直接调私有成员，不要 putMsg 给自己
                try {
                    String note = "⚠️ 你退出登录时有一项审批未处理，已拒绝：工具 "
                            + String.valueOf(m.get("toolName")) + "，会话 " + String.valueOf(m.get("sessionId"));
                    inboxStore.add(userId, "approval", String.valueOf(m.get("approvalId")), note);
                    pushInboxEvent(userId, inboxStore.unreadCount(userId));
                } catch (Exception e) {
                    putLog("审批退出告知写入消息箱失败: " + e, LogLevel.DEBUG);
                }
```
  （`inboxStore.add(userId, source, taskId, text)` 与 `pushInboxEvent` 均为同文件私有成员，签参照 `onInboxAdd` :1146 与 :1173）

- [ ] **Step 4: 跑测试确认通过**（grep `18-审批` 与 `失败=`）；确认全量回归绿
- [ ] **Step 5: Commit**

```bash
git add -u
git commit -m "审批授权 6/12：超时双路径收口 markExpired + 消息箱兜底；场景 18"
```

---

## Task 7: 任务会话登记接线（TLScheduleTaskSkill）

**Files:**
- Modify: `aiagent/skill-builtin/src/main/java/cn/tianlong/tlobject/aiagent/skill/builtin/TLScheduleTaskSkill.java`
  - `registerTaskSession`（:861）扩展
  - `unregisterTaskSession`（:633 调用的那个方法）扩展

- [ ] **Step 1: 加辅助方法**（`registerTaskSession` 方法之后）

```java
    /**
     * 审批授权继承登记：任务执行会话 → 发起会话（approvalGate 未配置时静默跳过，
     * 同 registerTaskSession 对 webui 的处理）。parentSessionId 为空 = 注销。
     */
    private void notifyApprovalGate(String execSession, String parentSessionId) {
        try {
            Object gate = getModuleInFactory("approvalGate");
            if (!(gate instanceof IObject)) return;
            TLMsg m = createMsg().setAction("approvalTaskSession")
                    .setParam("execSession", execSession)
                    .setParam("parentSessionId", parentSessionId == null ? "" : parentSessionId);
            m.setSystemParam(IGNOREMODULEISNULL, true);
            putMsg((IObject) gate, m);
        } catch (Exception e) {
            putLog("审批任务会话登记失败: " + e, LogLevel.DEBUG);
        }
    }
```
（`IGNOREMODULEISNULL` 若该文件未 import，加 `import cn.tianlong.tlobject.base.TLParamString;` 并用 `TLParamString.IGNOREMODULEISNULL`；该文件已有 `getModuleInFactory` 用法可参照 :479）

- [ ] **Step 2: 两个调用点挂上**
  - `registerTaskSession(rec, execSession)`（:568）方法体内末尾（webui 通知之后）加：`notifyApprovalGate(execSession, rec.parentSessionId);`
  - `unregisterTaskSession`（方法定义在 registerTaskSession 附近）末尾加：`notifyApprovalGate(execSession, "");`

- [ ] **Step 3: 编译 + 回归**

```bash
D:/maven/bin/mvn -f D:/tlobjectapp/tlobject/pom.xml install -pl aiagent/skill-builtin,demo/tlobject -am -DskipTests -q
cd D:/tlobjectapp/tlobject && cmd //c run_aitest.bat > _run_aitest.log 2>&1 & sleep 25
grep -E "失败=|Exception" _run_aitest.log | head; taskkill //F //IM java.exe 2>/dev/null; true
```
Expected: 编译通过、全量测试绿（技能侧接线由"任务继承"场景 + 真机冒烟共同覆盖；消息层已在场景 17 Step 5 覆盖）

- [ ] **Step 4: Commit**

```bash
git add aiagent/skill-builtin/src/main/java/cn/tianlong/tlobject/aiagent/skill/builtin/TLScheduleTaskSkill.java
git commit -m "审批授权 7/12：任务会话→发起会话登记（授权继承接线）"
```

---

## Task 8: TLAgentService 转发（doApprove scope + doPermissions + doAuto）

**Files:**
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAgentService.java`
  - `ACTION_REGISTRY`（:59 `approve` 行后）：+permissions/+auto
  - `checkMsgAction`（:136）：+两个 case
  - `doApprove`（:948）：scope 透传
  - 新增 `doPermissions` / `doAuto`

- [ ] **Step 1: 注册表 + 分发**

```java
        ACTION_REGISTRY.put("permissions", "审批授权管理（list|-a|revoke|grant|clear）");
        ACTION_REGISTRY.put("auto", "自动运行开关（on|off，本会话工具免审批）");
```
```java
            case "permissions":     return doPermissions(fromWho, msg);
            case "auto":            return doAuto(fromWho, msg);
```

- [ ] **Step 2: doApprove 透传 scope**（:956 `if ("approve".equals(subAction))` 块内，`approveMsg` 构造后）

```java
            String scope = msg.getStringParam("approvalScope", "once");
            if (!"once".equals(scope)) approveMsg.setParam("approvalScope", scope);
```

- [ ] **Step 3: 新增两方法**（`doApprove` 之后）

```java
    /** /permissions：list | -a | revoke | grant | clear —— 纯转发 approvalGate */
    @SuppressWarnings("unchecked")
    private TLMsg doPermissions(Object fromWho, TLMsg msg) {
        String sub = msg.getStringParam("sub", "list");
        String sid = msg.getStringParam(AI_P_SESSIONID, "");
        String ruleId = msg.getStringParam("ruleId", "");
        String operation = msg.getStringParam("operation", "");
        boolean all = msg.parseBoolean("all", false);
        TLMsg f;
        switch (sub) {
            case "list": {
                f = createMsg().setAction(APPROVAL_GRANTS);
                if (!all && !sid.isEmpty()) f.setParam("sessionId", sid);
                TLMsg r = putMsg("approvalGate", f);
                List<?> grants = r != null ? r.getListParam("grants", new ArrayList<>()) : new ArrayList<>();
                return ok(grants.isEmpty() ? "当前无授权" : "授权 " + grants.size() + " 条", grants);
            }
            case "revoke": {
                f = createMsg().setAction(APPROVAL_REVOKE).setParam("sessionId", sid).setParam("all", all);
                if (!ruleId.isEmpty()) f.setParam("ruleId", ruleId);
                if (!operation.isEmpty()) f.setParam("operation", operation);
                TLMsg r = putMsg("approvalGate", f);
                if (r == null || !r.parseBoolean(RESULT, false)) {
                    return fail("撤销失败: " + (r != null ? r.getStringParam("error", "") : "无响应"));
                }
                return ok("已撤销 " + r.getIntParam("removed", 0) + " 条授权");
            }
            case "grant": {
                if (ruleId.isEmpty()) return fail("grant 需要 ruleId（如 /permissions grant file_operation:delete）");
                f = createMsg().setAction(APPROVAL_GRANT).setParam("sessionId", sid).setParam("ruleId", ruleId);
                if (!operation.isEmpty()) f.setParam("operation", operation);
                TLMsg r = putMsg("approvalGate", f);
                if (r == null || !r.parseBoolean(RESULT, false)) {
                    return fail("预授权失败: " + (r != null ? r.getStringParam("error", "") : "无响应"));
                }
                return ok("已预授权: " + ruleId + (operation.isEmpty() ? "（全部操作）" : ":" + operation), null);
            }
            case "clear": {
                f = createMsg().setAction(APPROVAL_REVOKE).setParam("sessionId", sid).setParam("all", true);
                TLMsg r = putMsg("approvalGate", f);
                return ok("已清空 " + (r != null ? r.getIntParam("removed", 0) : 0) + " 条授权");
            }
            default:
                return fail("未知子命令: " + sub + "（可用: list、-a、revoke、grant、clear）");
        }
    }

    /** /auto：on|off|status —— 自动运行 = 本会话通配授权（ruleId="*"） */
    @SuppressWarnings("unchecked")
    private TLMsg doAuto(Object fromWho, TLMsg msg) {
        String sub = msg.getStringParam("sub", "status");
        String sid = msg.getStringParam(AI_P_SESSIONID, "");
        if (sid.isEmpty()) return fail("缺少 sessionId");
        if ("on".equals(sub) || "off".equals(sub)) {
            TLMsg f = "on".equals(sub)
                    ? createMsg().setAction(APPROVAL_GRANT).setParam("sessionId", sid)
                            .setParam("ruleId", "*").setParam("operation", "*")
                    : createMsg().setAction(APPROVAL_REVOKE).setParam("sessionId", sid)
                            .setParam("ruleId", "*");
            TLMsg r = putMsg("approvalGate", f);
            if (r == null || !r.parseBoolean(RESULT, false)) {
                return fail("自动运行切换失败: " + (r != null ? r.getStringParam("error", "") : "无响应"));
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("on", "on".equals(sub));
            return ok("on".equals(sub) ? "🔓 已开启自动运行（本会话工具免审批）" : "🔒 已关闭自动运行", d);
        }
        // status：查该会话通配授权是否存在
        TLMsg r = putMsg("approvalGate", createMsg().setAction(APPROVAL_GRANTS).setParam("sessionId", sid));
        boolean on = false;
        if (r != null) {
            List<?> grants = r.getListParam("grants", new ArrayList<>());
            for (Object o : grants) {
                if (o instanceof Map && "*".equals(String.valueOf(((Map<String, Object>) o).get("ruleId")))) {
                    on = true; break;
                }
            }
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("on", on);
        return ok(on ? "🔓 自动运行：开" : "🔒 自动运行：关", d);
    }
```
（`ok(...)`/`fail(...)` 为文件内既有辅助方法，参照 :963 用法）

- [ ] **Step 4: 编译 + 回归**（同 Task 7 Step 3 命令）
- [ ] **Step 5: Commit**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAgentService.java
git commit -m "审批授权 8/12：agentService 转发（approve scope + permissions + auto）"
```

---

## Task 9: 控制台（/approve 动词、/auto、/permissions、提示符、help、busy 白名单）

**Files:**
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLChatConsole.java`

- [ ] **Step 1: parseApproveCommand 加两动词**（:1068 `if (payload.startsWith("approve:"))` 之前插入分支）

```java
        if (payload.startsWith("round:") || payload.startsWith("session:")) {
            boolean isRound = payload.startsWith("round:");
            String[] subParts = payload.substring(payload.indexOf(':') + 1).split(":", 2);
            msg.setParam("subAction", "approve");
            msg.setParam("approvalId", subParts[0].trim());
            msg.setParam("approvalScope", isRound ? "round" : "session");
            if (subParts.length > 1 && !subParts[1].trim().isEmpty()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> modifiedArgs = new com.google.gson.Gson().fromJson(subParts[1].trim(), Map.class);
                    msg.setParam("approvalModifiedArguments", modifiedArgs);
                } catch (Exception ignored) {}
            }
            return msg;
        }
```
并把 :912 的用法提示改为：
```java
                    System.out.println("格式: /approve approve:ID | round:ID | session:ID | reject:ID:原因");
```

- [ ] **Step 2: 命令 switch 加 /auto 与 /permissions**（:915 `case "approve"` 块之后）

```java
            case "auto": {
                if (parts.length > 1 && ("on".equalsIgnoreCase(parts[1]) || "off".equalsIgnoreCase(parts[1]))) {
                    msg.setAction("auto").setParam("sub", parts[1].toLowerCase());
                } else if (parts.length == 1) {
                    msg.setAction("auto").setParam("sub", "status");
                } else {
                    System.out.println("用法: /auto on | /auto off | /auto（查看状态）");
                    return null;
                }
                msg.setParam(AI_P_SESSIONID, sessionId).setParam("userId", userId);
                break;
            }

            case "permissions": {
                // /permissions [-a] | revoke <规则>[:操作] [-s 会话] | grant <规则>[:操作] | clear [-a]
                String sub = "list";
                boolean all = false;
                String rule = "", op = "", targetSid = sessionId;
                for (int i = 1; i < parts.length; i++) {
                    String p = parts[i];
                    if ("-a".equals(p)) { all = true; }
                    else if ("-s".equals(p) && i + 1 < parts.length) { targetSid = parts[++i]; }
                    else if ("list".equals(p) || "revoke".equals(p) || "grant".equals(p) || "clear".equals(p)) { sub = p; }
                    else if (!p.startsWith("-")) {
                        int c = p.indexOf(':');
                        if (c > 0) { rule = p.substring(0, c); op = p.substring(c + 1); }
                        else rule = p;
                    }
                }
                msg.setAction("permissions").setParam("sub", sub).setParam("all", all)
                        .setParam("ruleId", rule).setParam("operation", op)
                        .setParam(AI_P_SESSIONID, targetSid).setParam("userId", userId);
                break;
            }
```

- [ ] **Step 3: 结果渲染**（reply switch :1332 `case "approve"` 之后）

```java
                case "auto": {
                    System.out.println(message);
                    Object onObj = data instanceof Map ? ((Map<?, ?>) data).get("on") : null;
                    autoRunOn = Boolean.TRUE.equals(onObj);
                    break;
                }
                case "permissions": {
                    System.out.println(message);
                    if (data instanceof List) printGrantList((List<?>) data);
                    break;
                }
```

- [ ] **Step 4: 提示符标识 + 字段**（`printPrompt` :2095 附近）

```java
    /** 本会话自动运行状态（/auto 结果驱动；仅影响提示符显示） */
    private boolean autoRunOn = false;

    private void printPrompt() {
        System.out.print(autoRunOn ? "🔓自动 " + prompt : prompt);
    }
```
① `/session <id>` 切换成功处（:1329）加 `autoRunOn = false;`（新会话状态未知，保守复位；用户可 `/auto` 查询）
② 审批框模板（`ConsoleReviewer.promptTemplate`）改为四行命令：
```java
                + "║  /approve approve:{id}     批准本次          ║\n"
                + "║  /approve round:{id}       本轮同规则免审     ║\n"
                + "║  /approve session:{id}     本会话同规则免审   ║\n"
                + "║  /approve reject:{id}:原因  拒绝             ║\n"
```
（框线宽度按最长行重新对齐；`ConsoleReviewer.java` :26-38）

- [ ] **Step 5: 列表渲染方法**（`printSessionList` 附近新增）

```java
    /** /permissions 列表渲染：范围/规则/操作/会话/时间 + 可复制 revoke 命令 */
    @SuppressWarnings("unchecked")
    private void printGrantList(List<?> data) {
        if (data.isEmpty()) { System.out.println("  (无授权)"); return; }
        int i = 1;
        for (Object o : data) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> g = (Map<String, Object>) o;
            String scope = "session".equals(String.valueOf(g.get("scope"))) ? "本会话" : "本轮";
            String time = new java.text.SimpleDateFormat("MM-dd HH:mm")
                    .format(new java.util.Date(((Number) g.getOrDefault("grantedAt", 0L)).longValue()));
            String rule = String.valueOf(g.get("ruleId"));
            String op = String.valueOf(g.get("operation"));
            System.out.println("  " + (i++) + ". [" + scope + "] " + g.get("display")
                    + "  session=" + g.get("sessionId") + "  " + time);
            System.out.println("     revoke: /permissions revoke " + rule
                    + (op.isEmpty() || "*".equals(op) ? "" : ":" + op)
                    + " -s " + g.get("sessionId"));
        }
    }
```

- [ ] **Step 6: busy 白名单 + help**（:658 附近 busy 分支；:2407 help 文本）

busy 分支加：
```java
            } else if (input.toLowerCase().startsWith("/auto")
                    || input.toLowerCase().startsWith("/permissions")) {
                executeServiceCommand(parseCommand(input));
```
help 文本（:2407 区块）加两行：
```java
        System.out.println("  /auto on|off           自动运行：本会话内所有工具调用免审批");
        System.out.println("  /permissions [revoke|grant|clear]  查看/管理本会话的审批授权");
```

- [ ] **Step 7: 编译 + 控制台冒烟（真机）**

```bash
D:/maven/bin/mvn -f D:/tlobjectapp/tlobject/pom.xml install -pl aiagent/common -am -DskipTests -q
# 启动主应用（另开窗口/后台），走 stdin 管道跑命令
cd D:/tlobjectapp/tlobject && (echo "/auto"; echo "/auto on"; echo "/permissions"; echo "/auto off"; echo "/permissions"; echo "/exit") | cmd //c aistart.bat > _run_console.log 2>&1
grep -E "自动运行|授权|revoke|无授权" _run_console.log | head -20
```
Expected: `/auto` 显示"关"→ `/auto on` 显示"已开启…"→ `/permissions` 列表出现"全部规则（自动运行）"行 → off 后清空。（`/exit` 退出；若管道方式无回显，改用人工交互验证）

- [ ] **Step 8: Commit**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLChatConsole.java \
        aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/approval/ConsoleReviewer.java
git commit -m "审批授权 9/12：控制台（/approve round|session、/auto、/permissions、提示符与help）"
```

---

## Task 10: webui 审批弹框三按钮

**Files:**
- Modify: `aiagent/webui/src/main/resources/webui/chat.html`（:167-170 按钮行）
- Modify: `aiagent/webui/src/main/resources/webui/app.js`（:1049-1050 绑定；:2148-2164 approveAction）

- [ ] **Step 1: chat.html 按钮行**（`#apApproveBtn` 行后加两个按钮）

```html
      <button class="ok" id="apApproveBtn">✓ 批准</button>
      <button id="apApproveRoundBtn" title="本轮内同规则不再询问">✓ 本轮不再问</button>
      <button id="apApproveSessionBtn" title="本会话内同规则不再询问">✓ 本会话不再问</button>
      <button class="no" id="apRejectBtn">✗ 拒绝</button>
```

- [ ] **Step 2: app.js 接线**（:1049-1050 两行绑定处）

```js
  $('#apApproveBtn').onclick = () => approveAction('once');
  $('#apApproveRoundBtn').onclick = () => approveAction('round');
  $('#apApproveSessionBtn').onclick = () => approveAction('session');
  $('#apRejectBtn').onclick = rejectAction;
```

- [ ] **Step 3: approveAction 带 scope**（:2148 方法改造）

```js
async function approveAction(scope) {
  const evt = currentApproval;
  if (!evt) return;
  let modifiedArgs = null;
  try {
    const raw = $('#apArgs').value.trim();
    if (raw && raw !== (evt.args || '{}')) modifiedArgs = JSON.parse(raw);
  } catch (e) {
    $('#apMsg').textContent = '参数不是合法 JSON: ' + e.message;
    $('#apMsg').classList.remove('hidden');
    return;
  }
  try {
    const payload = { subAction: 'approve', approvalId: evt.approvalId, approvalModifiedArguments: modifiedArgs };
    if (scope && scope !== 'once') payload.approvalScope = scope;
    const r = await apiCommand('approve', payload);
    toast(r.message || r.error, r.success ? 'ok' : 'err');
    if (r.success) $('#approvalModal').classList.add('hidden');
  } catch (e) { toast(e.message, 'err'); }
}
```

- [ ] **Step 4: 无后端前端验证**（静态服务器 + Playwright/人工）

```bash
cd D:/tlobjectapp/tlobject/aiagent/webui/src/main/resources/webui && python -m http.server 8123 &
# 浏览器打开 http://localhost:8123/chat.html，手动执行 window.showApprovalModal({approvalId:'x',toolName:'file_operation',args:'{}'})
# 断言：四个按钮渲染；点"本会话不再问"触发 apiCommand('approve', {approvalScope:'session'})（Network 面板确认）
```
（既有手法：静态 http.server + Playwright，/api 全 404 无碍，见 memory `webui-frontend-no-backend-verification`）

- [ ] **Step 5: Commit**

```bash
git add aiagent/webui/src/main/resources/webui/chat.html aiagent/webui/src/main/resources/webui/app.js
git commit -m "审批授权 10/12：webui 弹框三批准按钮（本次/本轮/本会话）"
```

---

## Task 11: webui 自动运行开关 + 授权管理面板

**Files:**
- Modify: `aiagent/webui/src/main/resources/webui/chat.html`（顶栏 :31-35 之间加按钮；文件尾部加浮层）
- Modify: `aiagent/webui/src/main/resources/webui/app.js`

- [ ] **Step 1: chat.html 顶栏按钮**（`#inboxBtn` 行之前）

```html
    <button id="autoBtn" class="ghost" title="自动运行：本会话内工具调用免审批">🔒 自动运行</button>
    <button id="permBtn" class="ghost" title="审批授权管理（查看/撤销）">🛡 授权</button>
```
浮层（`#inboxModal` 之后，同款结构）：
```html
<!-- ===== 审批授权面板（顶栏 🛡 打开）===== -->
<div id="permModal" class="modal hidden">
  <div class="modal-box wide">
    <h3>🛡 审批授权</h3>
    <div class="btn-row">
      <button class="primary" onclick="loadPermissions()">刷新</button>
      <button onclick="clearPermissions()">全部清空</button>
    </div>
    <div id="permBox" style="max-height:60vh;overflow:auto;"></div>
    <div class="btn-row">
      <button class="ok" onclick="closePermissions()">关 闭</button>
    </div>
  </div>
</div>
```

- [ ] **Step 2: app.js 逻辑**（放审批区块 :2174 之后）

```js
// ======================== 自动运行 & 授权面板 ========================
function openPermissions() { $('#permModal').classList.remove('hidden'); loadPermissions(); }
function closePermissions() { $('#permModal').classList.add('hidden'); }

async function toggleAuto() {
  const on = state.autoRun === true;             // 当前状态 → 点一下取反
  const r = await apiCommand('auto', { sub: on ? 'off' : 'on', sessionId: state.sessionId });
  toast(r.message || r.error, r.success ? 'ok' : 'err');
  if (r.success) { state.autoRun = !on; renderAutoBtn(); }
}
function renderAutoBtn() {
  const b = $('#autoBtn');
  if (!b) return;
  b.textContent = state.autoRun ? '🔓 自动运行' : '🔒 自动运行';
  b.classList.toggle('on', state.autoRun === true);
}
async function refreshAutoState() {
  try {
    const r = await apiCommand('auto', { sub: 'status', sessionId: state.sessionId });
    if (r.success && r.data) { state.autoRun = r.data.on === true; renderAutoBtn(); }
  } catch (e) {}
}
async function loadPermissions() {
  const box = $('#permBox');
  try {
    const r = await apiCommand('permissions', { sub: 'list', userId: state.userId });
    if (!r.success) { box.innerHTML = '<div class="muted">加载失败: ' + esc(r.error || '') + '</div>'; return; }
    const list = r.data || [];
    if (!list.length) { box.innerHTML = '<div class="muted">当前无授权</div>'; return; }
    box.innerHTML = '';
    list.forEach(g => {
      const row = document.createElement('div');
      row.style.cssText = 'border-bottom:1px solid #2a2f3d;padding:8px 4px;';
      const scope = g.scope === 'session' ? '本会话' : '本轮';
      const revokeCmd = { sub: 'revoke', userId: state.userId, sessionId: g.sessionId, ruleId: g.ruleId };
      if (g.operation && g.operation !== '*') revokeCmd.operation = g.operation;
      row.innerHTML = '<span class="muted">[' + scope + ']</span> ' + esc(g.display || g.ruleId)
        + ' <span class="muted">session=' + esc(g.sessionId) + '</span> ';
      const btn = document.createElement('button');
      btn.textContent = '撤销';
      btn.onclick = async () => {
        const rr = await apiCommand('permissions', revokeCmd);
        toast(rr.message || rr.error, rr.success ? 'ok' : 'err');
        loadPermissions(); refreshAutoState();
      };
      row.appendChild(btn);
      box.appendChild(row);
    });
  } catch (e) { box.innerHTML = '<div class="muted">加载异常: ' + esc(e.message) + '</div>'; }
}
async function clearPermissions() {
  const r = await apiCommand('permissions', { sub: 'clear', userId: state.userId, sessionId: state.sessionId });
  toast(r.message || r.error, r.success ? 'ok' : 'err');
  loadPermissions(); refreshAutoState();
}
```

- [ ] **Step 3: 绑定与生命周期**
  - `bindEvents`（:1054 附近）：`$('#autoBtn').onclick = toggleAuto; $('#permBtn').onclick = openPermissions;`
  - 遮罩关闭 + ESC 链（:1062/:1068 同款）：`$('#permModal').addEventListener('click', e => { if (e.target === $('#permModal')) closePermissions(); });`；ESC 链加 `if (!$('#permModal').classList.contains('hidden')) { closePermissions(); return; }`
  - 登录后拉状态：`showView('chat')` 之后（:284 `refreshInboxBadge()` 附近）加 `refreshAutoState();`
  - 切会话后刷新：`continueSession`/`switchSession` 成功处加 `refreshAutoState();`

- [ ] **Step 4: 无后端前端验证**（同 Task 10 Step 4 手法）：开关点击切换文案/高亮；面板渲染列表与撤销按钮

- [ ] **Step 5: Commit**

```bash
git add aiagent/webui/src/main/resources/webui/chat.html aiagent/webui/src/main/resources/webui/app.js
git commit -m "审批授权 11/12：webui 自动运行开关 + 授权管理面板"
```

---

## Task 12: 全量验收（回归 + 真机冒烟）

- [ ] **Step 1: 全量单元回归**

```bash
D:/maven/bin/mvn -f D:/tlobjectapp/tlobject/pom.xml install -pl aiagent/common,aiagent/webui,demo/tlobject -am -DskipTests -q
cd D:/tlobjectapp/tlobject && cmd //c run_aitest.bat > _run_aitest.log 2>&1 & sleep 30
grep -E "通过=.*失败=|FAIL" _run_aitest.log | tail -5; taskkill //F //IM java.exe 2>/dev/null; true
```
Expected: 全部通过、失败=0（含 16/17/18 三个新场景）

- [ ] **Step 2: 真机控制台冒烟（真 LLM）**

```bash
cd D:/tlobjectapp/tlobject && cmd //c aistart.bat
# 人工步骤：
#  1) 让 LLM 写文件（会弹审批）→ 输入 /approve session:<ID>
#  2) 再让它写另一个文件 → 不再弹审批（会话免审生效）
#  3) /permissions → 看到 file_operation:write 与显示；/permissions revoke file_operation:write → 再写又弹
#  4) /auto on → 提示符出现 [🔓自动]；让它删除文件 → 不弹；/auto off 恢复
```
Expected: 每步行为与设计一致；`/permissions` 行尾可直接复制 revoke 命令

- [ ] **Step 3: webui 人工冒烟（aistart-web.bat，真 LLM）**
  - 弹框出现四按钮；点「本轮不再问」→ 本轮内第二次调用同规则不再弹；新消息（新一轮）再调 → 重新弹
  - 点「本会话不再问」→ 后续轮次同规则不弹；🛡 面板可见该行并可撤销
  - 🔓 开关：开启后任意工具调用不弹；🛡 面板出现"全部规则（自动运行）"行；开关关闭后该行消失
  - 退出登录后（有未决审批时）重新登录 → 🔔 消息箱有未读审批告知条目

- [ ] **Step 4: 提交收尾**（若冒烟中有小修，逐条提交；无修改则跳过）

```bash
git status --short
git add -u && git commit -m "审批授权 12/12：验收修正" # 仅当有改动
```

---

## 验收口径（对照 spec §4）

| spec 条目 | 落地任务 | 验证 |
|-----------|---------|------|
| 授权粒度 = 规则+操作 | T4 | 场景 16（粒度隔离） |
| 本次/本轮/本会话三档 | T4（round/session 写入）、T9/T10（入口） | 场景 16 + 控制台/webui 冒烟 |
| 自动运行（通配授权） | T5（预授权）、T8（/auto）、T11（开关） | 场景 17 + 冒烟 |
| /permissions 列表/撤销/清空 | T5、T8、T9、T11 | 场景 17 + 冒烟 |
| 任务会话继承（归一到对话根） | T4（resolve）、T5（映射）、T7（登记） | 场景 17 Step 5 + 真机（后台任务） |
| 拒绝记忆优先 | 现有逻辑（顺序不变） | 现有回归 + 场景 16/17 不破坏 |
| 消息箱兜底（EXPIRED/退出即拒） | T6 | 场景 18 + webui 冒烟 |
| 对 LLM 透明 | T4 | 场景 16（approved 无弹框文案进 LLM 路径由现有语义保证） |
| 零改动 | `TLToolExecutor`、`TLAiAgent`、`IApprovalReviewer` | `git diff --stat` 确认未触碰 |

## 已知边界（本阶段不做，属阶段二）

- 退出登录仍是"退出即拒"（仅补消息箱告知）；"退出转断点"、无限阻塞、决策写回、TTL 在阶段二
- 轮次级授权的 roundId 为空时拒绝写入（安全侧）
- 定时任务（无 parentSessionId）不继承会话授权
