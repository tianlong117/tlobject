# 审批授权（阶段二：等待策略）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把审批从"同步阻塞 5 分钟超时"改造为"人在就无限等、人不在就转断点、回来批了再续跑"——阻塞态一直等（`approvalTimeoutMs=0`）、无人接收/退出登录转断点（defer + 消息箱）、断点审批有 TTL 兜底、批准后自动续跑（决策写回 `AI_P_APPROVAL_DECISION`）。

**Architecture:** 投递判据放在 `ConsoleReviewer`（`publishApprovalEvent` 的 ack = "有人在收"）；"转断点" = 审查人返回 `pending` 不阻塞 → 现有断点链路（agent 存 `pending_approval` 轮）原样接管；决策写回放在 **TLAiAgent**（resume 装载时向 approvalGate 发 `APPROVAL_QUERY`，gate 返回 `decision` 映射）——比 spec 原定放 `TLAgentService` 更内聚且可在 aitest 覆盖（spec §5.5 说"唯一缺失的接线"，位置是实施细节）。续跑触发：webui 批准 deferred 审批后前端驱动（复用流式 resume 链路）；控制台提示 `/resume`。

**Tech Stack:** Java 17、无 JUnit（测试 = `/test` Mock 驱动场景 + 前端 stub 冒烟 + 真机 E2E）、Gson、原生 JS（webui 无框架）。

**Spec:** `docs/superpowers/specs/2026-10-12-approval-grants-design.md` §5（§4 阶段一已落地，提交区间 `7e849b67..16a7d4bf`）

**测试约定（本仓库特有，务必遵守）：**
- 无单测框架；核心逻辑用 `TLAgentTestModule` 的消息级场景验证（Mock Provider 驱动，`/test` 运行）
- 测试 app 入口：`run_aitest.bat`（配置目录 `conf/demo/aitest/`，factoryBoot 里自动 `runAllTests`）
- 编译用绝对路径 Maven：`D:/maven/bin/mvn -f D:/tlobjectapp/tlobject/pom.xml install -pl <子模块> -am -DskipTests`
- **改代码必须重新编译 + 重启进程**（进程加载的是 target/classes 旧 class）
- **Git Bash 陷阱**：`NoDefaultCurrentDirectoryInExePath` 生效，跑 bat 用 `cmd //c ".\run_aitest.bat"`；管道喂 stdin 要 `sleep N |` 保持 stdin 打开（立即 EOF 应用会自行关闭）
- webui 前端静态验证：`python -m http.server` 指向 `aiagent/webui/target/classes/webui`（不用 install，process-resources 即可）

---

## 文件结构

| 文件 | 动作 | 职责 |
|------|------|------|
| `aiagent/common/.../aiagent/TLAiAgentParamString.java` | 修改 | +APPROVAL_DEFER/APPROVAL_ABANDON/AI_P_APPROVAL_DEFERRED 常量 |
| `aiagent/common/.../aiagent/approval/TLApprovalRequest.java` | 修改 | +deferred/deferredAt/ttlArmed 字段 |
| `aiagent/common/.../aiagent/approval/TLApprovalModule.java` | 修改 | 核心：投递判据配套（defer/TTL/QUERY 映射/ABANDON）、waitForDecision 无限等、默认超时 0 |
| `aiagent/common/.../aiagent/approval/ConsoleReviewer.java` | 修改 | 投递判据（ack → 阻塞；无 ack → pending 不阻塞；去掉 stdout 兜底） |
| `aiagent/common/.../aiagent/TLToolExecutor.java` | 修改 | fail-closed；resume 已批准免复批；stale 清理跳过"正等审批"的执行 |
| `aiagent/common/.../aiagent/TLBaseSessionManager.java` | 修改 | findIncomplete 覆盖 pending_approval + sessionId 过滤 + discardPendingRound |
| `aiagent/common/.../aiagent/TLSessionManager.java` | 修改 | findIncompleteMeta 新签名（文件版） |
| `aiagent/common/.../aiagent/TLDatabaseSessionManager.java` | 修改 | findIncompleteMeta 新签名（DB 版） |
| `aiagent/common/.../aiagent/TLAiAgent.java` | 修改 | resume 决策查询写回 + "lost" 分支（非流式+流式）+ 免复批标志透传 |
| `aiagent/common/.../aiagent/TLAgentService.java` | 修改 | doChatStream resume 透传 pendingToolCall/approvalId；doApprove 透传 deferred/sessionId；+doAbandonApproval |
| `aiagent/common/.../aiagent/TLChatConsole.java` | 修改 | /approve 后 deferred 提示 |
| `aiagent/webui/.../webui/TLWebChatModule.java` | 修改 | resume 透传；退出转 defer；新消息拦截；重放带 deferred/createdAt；透传 deferred |
| `aiagent/webui/.../webui/TLWebChatServlet.java` | 修改 | 回退登出路径同样转 defer |
| `aiagent/webui/src/main/resources/webui/app.js` | 修改 | 陈旧提示、批准/拒绝后自动续跑、放弃该操作、拦截提示 |
| `aiagent/webui/src/main/resources/webui/chat.html` | 修改 | 断点弹框 +「放弃该操作」按钮 + 待批说明行 |
| `aiagent/common/.../aiagent/test/TLAgentTestModule.java` | 修改 | 新增场景 21-27（含场景 20 文案断言更新） |
| `demo/tlobject/.../conf/demo/aiagent/approvalGate_config.xml` | 修改 | approvalTimeoutMs=0 + approvalPendingTtlMs |
| `demo/tlobject/.../conf/demo/aiagent/fileAgent_config.xml` | 修改 | file_operation timeout 360→0 |
| `demo/tlobject/.../conf/demo/aiagent/codingAgent_config.xml` | 修改 | file_operation timeout 360→0 |
| `demo/tlobject/.../conf/demo/aiagent/toolExecutor_config.xml` | 修改 | 注释更新（staleState/超时分层语义） |
| `demo/tlobject/.../conf/demo/aitest/approvalGate_config.xml` | 修改 | +approvalPendingTtlMs=5000 |
| `demo/tlobject/.../conf/demo/aitest/toolExecutor_config.xml` | **新建** | 测试端启用执行器审批（approvalModule=approvalGate） |
| `smoke/approval_phase2_check.py` | **新建** | 前端 stub 冒烟（陈旧提示/自动续跑/放弃按钮/拦截） |
| `smoke/approval_defer_e2e.py` | **新建** | 真机 E2E（defer→重登→重放→批准→自动续跑） |
| `CLAUDE.md` | 修改 | HITL 段更新等待策略语义 |

---

## Task 1: 常量与数据类

**Files:**
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAiAgentParamString.java`（审批常量区 `~:433-467`）
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/approval/TLApprovalRequest.java`（字段区 `~:43` 与 getter 区 `~:139`）

- [ ] **Step 1: 加常量**（`TLAiAgentParamString.java`，紧接 `APPROVAL_PENDINGLIST` 定义行后）

```java
    /** 退出登录/无人在线时把未决审批转为断点等待（保持 PENDING，唤醒阻塞线程以 pending 返回） */
    String APPROVAL_DEFER = "approvalDefer";
    /** 放弃审批（撤销待批 + 丢弃断点；不记拒绝记忆——放弃 ≠ 拒绝） */
    String APPROVAL_ABANDON = "approvalAbandon";
```

并在 `AI_P_APPROVAL_DECISION` 定义行（`:463`）后加：

```java
    /** 审批是否处于断点态（defer 后为 true；弹框陈旧提示/自动续跑判据） */
    String AI_P_APPROVAL_DEFERRED = "approvalDeferred";
```

- [ ] **Step 2: 请求对象加字段**（`TLApprovalRequest.java`，`userId` 字段 `~:47` 后）

```java
    /** 是否已转为断点等待（无人在线 / 退出登录 defer）。阻塞态不会置位 */
    private boolean deferred;
    /** 转为断点态的时刻（陈旧提示"发起于 X 前"用；0 = 未转） */
    private long deferredAt;
    /** TTL 定时任务是否已排期（幂等守卫，防重复排期） */
    private boolean ttlArmed;
```

getter 区（`getUserId()` 后 `~:140`）：

```java
    public boolean isDeferred() { return deferred; }
    /** 置断点态；重复置位不刷新 deferredAt（保留"最初转入"的时刻） */
    public void setDeferred(boolean deferred) {
        this.deferred = deferred;
        if (deferred && deferredAt == 0) deferredAt = System.currentTimeMillis();
    }
    public long getDeferredAt() { return deferredAt; }
    public boolean isTtlArmed() { return ttlArmed; }
    public void setTtlArmed(boolean ttlArmed) { this.ttlArmed = ttlArmed; }
```

- [ ] **Step 3: toMap/fromMap 带上新字段**（`toMap()` `~:159` 的 `userId` 行后、`fromMap()` `~:203` 的 `setUserId` 行后）

```java
        // toMap 内：
        map.put("deferred", deferred);
        map.put("deferredAt", deferredAt);
```

```java
        // fromMap 内：
        if (Boolean.TRUE.equals(map.get("deferred"))) req.setDeferred(true);
        if (map.get("deferredAt") instanceof Number) req.setDeferredAt(((Number) map.get("deferredAt")).longValue());
```

同时给 `setDeferredAt` 补一个 setter（fromMap 用；放在 `getDeferredAt` 旁）：

```java
    public void setDeferredAt(long deferredAt) { this.deferredAt = deferredAt; }
```

- [ ] **Step 4: 编译**

Run: `D:/maven/bin/mvn -f D:/tlobjectapp/tlobject/pom.xml install -pl aiagent/common -am -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAiAgentParamString.java aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/approval/TLApprovalRequest.java
git commit -m "审批授权 13a：常量与请求字段（defer/abandon/deferred）"
```

---

## Task 2: 投递判据 + 无限等待（spec §5.1/§5.2）

**Files:**
- Modify: `aiagent/common/.../approval/ConsoleReviewer.java`（`requestApproval` `:50-61`、`printPrompt` `:142-159`）
- Modify: `aiagent/common/.../approval/TLApprovalModule.java`（默认值 `:84`、`waitForDecision` `:275-298`）
- Test: `aiagent/common/.../test/TLAgentTestModule.java`（场景 21/22）

- [ ] **Step 1: 写失败测试——场景 21（无限等待）+ 22（无订阅者转断点）**

`TLAgentTestModule.java` 注册表（`~:469` `approvalBlockTimeout` 行后）：

```java
        TEST_CASES.put("approvalInfiniteWait", new String[]{"21-审批无限等待", null});
        TEST_CASES.put("approvalConsoleDefer", new String[]{"22-无人接收转断点", null});
```

`resolveTestCase` 的 switch（`~:548` `approvalblocktimeout` 后）：

```java
            case "approvalinfinitewait": return this::testApprovalInfiniteWait;
            case "approvalconsoledefer": return this::testApprovalConsoleDefer;
```

场景实现（加在场景 20 方法后）：

```java
    /**
     * 无限等待：approvalTimeoutMs=0（反射临时置 0）时阻塞路径不超时——
     * 超过原配置值（3000ms）仍不返回，批准后才以 approved 醒来。
     */
    protected TLMsg testApprovalInfiniteWait(Object fromWho, TLMsg msg) {
        TLTestApprovalReviewer.reset();
        resetApprovalGrants();
        TLTestApprovalReviewer.setBlockMode(true);
        Object gateObj = getModuleInFactory("approvalGate");
        if (!(gateObj instanceof cn.tianlong.tlobject.aiagent.approval.TLApprovalModule)) {
            return createMsg().setParam(RESULT, false).setParam("error", "approvalGate 非 TLApprovalModule，探针未执行");
        }
        java.lang.reflect.Field timeoutField = null;
        long savedTimeout = 0;
        try {
            timeoutField = gateObj.getClass().getDeclaredField("approvalTimeoutMs");
            timeoutField.setAccessible(true);
            savedTimeout = timeoutField.getLong(gateObj);
            timeoutField.setLong(gateObj, 0L);
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false).setParam("error", "反射设置 approvalTimeoutMs 失败: " + e);
        }
        final java.util.concurrent.atomic.AtomicReference<TLMsg> holder = new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(() -> holder.set(
                requestApprovalFor("file_operation", "{\"operation\":\"delete\",\"path\":\"D:/tmp/inf.txt\"}")));
        try {
            t.start();
            String id = "";
            for (int i = 0; i < 50 && id.isEmpty(); i++) {
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                List<cn.tianlong.tlobject.aiagent.approval.TLApprovalRequest> reqs =
                        TLTestApprovalReviewer.snapshot();
                if (!reqs.isEmpty()) id = reqs.get(reqs.size() - 1).getApprovalId();
            }
            if (id.isEmpty()) {
                return createMsg().setParam(RESULT, false).setParam("error", "阻塞请求未登记（超时）");
            }
            try { Thread.sleep(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (holder.get() != null) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "approvalTimeoutMs=0 应一直等，却在 4 秒内返回了");
            }
            TLMsg ap = putMsg("approvalGate", createMsg().setAction(APPROVAL_APPROVE)
                    .setParam(AI_P_APPROVAL_ID, id));
            if (!ap.parseBoolean(RESULT, false)) {
                return createMsg().setParam(RESULT, false).setParam("error", "批准失败: " + ap.getStringParam("error", ""));
            }
            t.join(5000);
            TLMsg ret = holder.get();
            if (ret == null || !"approved".equals(ret.getStringParam(AI_P_APPROVAL_STATE, ""))) {
                return createMsg().setParam(RESULT, false).setParam("error", "应醒来返回 approved，实为 "
                        + (ret == null ? "null" : ret.getStringParam(AI_P_APPROVAL_STATE, "")));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return createMsg().setParam(RESULT, false).setParam("error", "被中断");
        } finally {
            try { if (timeoutField != null) timeoutField.setLong(gateObj, savedTimeout); } catch (Exception ignored) {}
            TLTestApprovalReviewer.setBlockMode(false);
            resetApprovalGrants();
        }
        log("[TEST]   审批无限等待: approvalTimeoutMs=0 一直等 通过");
        return createMsg().setParam(RESULT, true);
    }

    /**
     * 投递判据：ConsoleReviewer 在无订阅者（无控制台/webui SSE）时不阻塞，
     * 立即返回 pending（上层转断点）——直调探针，不经消息接口。
     */
    protected TLMsg testApprovalConsoleDefer(Object fromWho, TLMsg msg) {
        Object gateObj = getModuleInFactory("approvalGate");
        if (!(gateObj instanceof cn.tianlong.tlobject.aiagent.approval.TLApprovalModule)) {
            return createMsg().setParam(RESULT, false).setParam("error", "approvalGate 非 TLApprovalModule，探针未执行");
        }
        cn.tianlong.tlobject.aiagent.approval.ConsoleReviewer cr =
                new cn.tianlong.tlobject.aiagent.approval.ConsoleReviewer();
        cr.setOwner((cn.tianlong.tlobject.aiagent.approval.TLApprovalModule) gateObj);
        cn.tianlong.tlobject.aiagent.approval.TLApprovalRequest probe =
                new cn.tianlong.tlobject.aiagent.approval.TLApprovalRequest(
                        SID_APPROVAL, "file_operation", new java.util.HashMap<>(), "");
        long start = System.currentTimeMillis();
        TLMsg r = cr.requestApproval(probe);
        long elapsed = System.currentTimeMillis() - start;
        String st = r != null ? r.getStringParam(AI_P_APPROVAL_STATE, "") : "null";
        if (!"pending".equals(st)) {
            return createMsg().setParam(RESULT, false).setParam("error", "无订阅者应返回 pending，实为 " + st);
        }
        if (elapsed > 2000) {
            return createMsg().setParam(RESULT, false).setParam("error", "无订阅者不应阻塞，耗时 " + elapsed + "ms");
        }
        log("[TEST]   无人接收转断点: 不阻塞返回 pending（" + elapsed + "ms） 通过");
        return createMsg().setParam(RESULT, true);
    }
```

说明：场景 22 依赖"aitest 进程内 approvalEvent 无订阅者"——TLChatConsole 只声明在控制台应用配置里，TLWebChatModule 不在 aitest（`msgBus` 存在但该 topic 无接收者 → `publishApprovalEvent` 返回 false ✓）。若未来 aitest 引入订阅者，此场景会失败——这是期望（判据本身变了就该重审）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cmd //c ".\run_aitest.bat"`（或已有的编译+运行组合）
Expected: 场景 21 FAIL（4 秒内返回了超时）、场景 22 FAIL（无订阅者仍阻塞 3 秒后 expired 或耗时 >2000ms）

- [ ] **Step 3: 实现 ConsoleReviewer 投递判据**

`ConsoleReviewer.java`：删掉 `printPrompt`（`:142-159`），`requestApproval` 改为：

```java
    @Override
    public TLMsg requestApproval(TLApprovalRequest request) {
        // 1. 渲染审批框文本（交付给订阅者渲染；控制台/webui 各取所需）
        String text = renderPromptText(request);

        // 2. 投递判据（"人在不在"）：有订阅者 ack（控制台在跑 / webui 该用户的 SSE 在线）
        //    → 阻塞等决策；没人接收 → 返回 pending 不阻塞，上层落断点等人回来再批。
        //    原"publish 失败 → stdout 兜底打印"已删除：无 UI 部署打印也没人看。
        if (owner == null) {
            return buildResult(request.getApprovalId(), TLApprovalRequest.REJECTED, null, "审查人未就绪");
        }
        if (!owner.publishApprovalEvent(text, request)) {
            return buildResult(request.getApprovalId(), TLApprovalRequest.PENDING, null, null);
        }
        return owner.waitForDecision(request);
    }
```

新增 `renderPromptText`（原 `printPrompt` 去掉发布/打印部分，保留模板渲染）：

```java
    /** 模板渲染为展示文本（不发布、不打印；发布与回退由 requestApproval 决定） */
    String renderPromptText(TLApprovalRequest request) {
        String id = request.getApprovalId() != null ? request.getApprovalId() : "-";
        java.util.Map<String, String> vars = new java.util.LinkedHashMap<>();
        vars.put("toolName", request.getToolName() != null ? request.getToolName() : "-");
        vars.put("riskLevel", request.getRiskLevel() != null ? request.getRiskLevel() : "-");
        vars.put("toolArgs", formatArgs(request.getToolArguments()));
        vars.put("approvalId", id);
        vars.put("id", id);
        vars.put("description", request.getDescription() != null && !request.getDescription().isEmpty()
                ? request.getDescription() : "需要您的确认");
        return renderPrompt(promptTemplate, vars);
    }
```

- [ ] **Step 4: 实现无限等待 + 默认 0**

`TLApprovalModule.java` `:84`：

```java
    /** 阻塞等待上限（毫秒）。0 = 一直等（默认——在线挂着的审批等人回来，设计语义）；断点态兜底另见 approvalPendingTtlMs */
    private long approvalTimeoutMs = 0L;
```

新增秒格式化辅助（放 `waitForDecision` 前）：

```java
    /** 时长文案：整秒不带小数（"3秒"），毫秒级保留一位（"0.3秒"）——探针文案与真实等待一致 */
    private static String fmtSeconds(long ms) {
        return ms % 1000 == 0 ? (ms / 1000) + "秒"
                : String.format(java.util.Locale.ROOT, "%.1f秒", ms / 1000.0);
    }
```

`waitForDecision`（`:275-298`）改等待分支：

```java
    public TLMsg waitForDecision(TLApprovalRequest request) {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        decisionLatches.put(request.getApprovalId(), latch);
        try {
            // 等待时长：请求级优先，缺省用模块级；0 = 一直等（latch.await() 无超时）
            long waitMs = request.getTimeoutMs() > 0 ? request.getTimeoutMs() : approvalTimeoutMs;
            boolean ok;
            if (waitMs > 0) {
                ok = latch.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } else {
                latch.await();
                ok = true;
            }
            if (!ok) {
                markExpired(request, "审批超时（" + fmtSeconds(waitMs) + "）");
            } else if (Thread.currentThread().isInterrupted()) {
                request.setState(TLApprovalRequest.REJECTED);
                request.setRejectionReason("审批被中断");
            }
        } catch (InterruptedException e) {
            request.setState(TLApprovalRequest.REJECTED);
            request.setRejectionReason("审批被中断");
            Thread.currentThread().interrupt();
        }
        decisionLatches.remove(request.getApprovalId());
        return buildApprovalResponse(request, null);
    }
```

同文件场景 20 依赖的文案断言要同步更新：`TLAgentTestModule.testApprovalBlockTimeout` 中

```java
                if (!preason.contains("3秒")) {
```
改为

```java
                if (!preason.contains("0.3秒")) {   // 探针 request.timeoutMs=300 → 文案与真实等待一致（原实现错用模块值"3秒"）
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cmd //c ".\run_aitest.bat"`
Expected: 21/22 PASS，其余场景（含 20 改文案后）全绿

- [ ] **Step 6: Commit**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/approval/ConsoleReviewer.java aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/approval/TLApprovalModule.java aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/test/TLAgentTestModule.java
git commit -m "审批授权 13b：投递判据（无人在线转断点）+ 阻塞态无限等待（approvalTimeoutMs=0）"
```

---

## Task 3: 退出转断点（defer）+ 断点审批 TTL（spec §5.4/§5.6）

**Files:**
- Modify: `aiagent/common/.../approval/TLApprovalModule.java`（`setModuleParams` `:149-188`、`checkMsgAction` `:240-271`、`handleApprovalRequest` 尾部 `:495-514`、新增 `handleDefer`/`armPendingTtl`）
- Test: `TLAgentTestModule.java`（场景 23/24）

- [ ] **Step 1: 写失败测试——场景 23（defer 唤醒）+ 24（TTL）**

注册表 + resolveTestCase：

```java
        TEST_CASES.put("approvalDeferWakeup", new String[]{"23-审批退出转断点", null});
        TEST_CASES.put("approvalPendingTtl", new String[]{"24-断点审批TTL", null});
```

```java
            case "approvaldeferwakeup": return this::testApprovalDeferWakeup;
            case "approvalpendingttl": return this::testApprovalPendingTtl;
```

```java
    /**
     * 退出转断点：阻塞中的请求被 APPROVAL_DEFER 唤醒 → 线程以 pending 返回（非 rejected）；
     * 审批保持 PENDING 且 deferred=true；迟到的批准仍有效（defer 不是终结）。
     */
    protected TLMsg testApprovalDeferWakeup(Object fromWho, TLMsg msg) {
        TLTestApprovalReviewer.reset();
        resetApprovalGrants();
        TLTestApprovalReviewer.setBlockMode(true);
        final java.util.concurrent.atomic.AtomicReference<TLMsg> holder = new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(() -> holder.set(
                requestApprovalFor("file_operation", "{\"operation\":\"delete\",\"path\":\"D:/tmp/defer.txt\"}")));
        try {
            t.start();
            String id = "";
            for (int i = 0; i < 50 && id.isEmpty(); i++) {
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                List<cn.tianlong.tlobject.aiagent.approval.TLApprovalRequest> reqs =
                        TLTestApprovalReviewer.snapshot();
                if (!reqs.isEmpty()) id = reqs.get(reqs.size() - 1).getApprovalId();
            }
            if (id.isEmpty()) {
                return createMsg().setParam(RESULT, false).setParam("error", "阻塞请求未登记（超时）");
            }
            TLMsg d = putMsg("approvalGate", createMsg().setAction(APPROVAL_DEFER)
                    .setParam(AI_P_APPROVAL_ID, id));
            if (!d.parseBoolean(RESULT, false)) {
                return createMsg().setParam(RESULT, false).setParam("error", "defer 失败: " + d.getStringParam("error", ""));
            }
            t.join(3000);
            TLMsg ret = holder.get();
            if (ret == null || !"pending".equals(ret.getStringParam(AI_P_APPROVAL_STATE, ""))) {
                return createMsg().setParam(RESULT, false).setParam("error", "defer 唤醒应以 pending 返回，实为 "
                        + (ret == null ? "null(未返回)" : ret.getStringParam(AI_P_APPROVAL_STATE, "")));
            }
            // 审批保持 PENDING 且 deferred=true（QUERY 决策映射）
            TLMsg q = putMsg("approvalGate", createMsg().setAction(APPROVAL_QUERY).setParam(AI_P_APPROVAL_ID, id));
            if (!"pending".equals(q.getStringParam("decision", ""))
                    || !q.parseBoolean(AI_P_APPROVAL_DEFERRED, false)) {
                return createMsg().setParam(RESULT, false).setParam("error",
                        "defer 后应保持 PENDING+deferred，实为 decision=" + q.getStringParam("decision", ""));
            }
            // 迟到的批准仍有效（defer 只是释放等待线程，不是终结）
            TLMsg ap = putMsg("approvalGate", createMsg().setAction(APPROVAL_APPROVE)
                    .setParam(AI_P_APPROVAL_ID, id));
            if (!ap.parseBoolean(RESULT, false)) {
                return createMsg().setParam(RESULT, false).setParam("error", "defer 后批准应有效: " + ap.getStringParam("error", ""));
            }
            // 幂等：已决策后 defer 无副作用且不报错
            TLMsg d2 = putMsg("approvalGate", createMsg().setAction(APPROVAL_DEFER)
                    .setParam(AI_P_APPROVAL_ID, id));
            if (!d2.parseBoolean(RESULT, false)) {
                return createMsg().setParam(RESULT, false).setParam("error", "已决策后 defer 应幂等成功");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return createMsg().setParam(RESULT, false).setParam("error", "被中断");
        } finally {
            TLTestApprovalReviewer.setBlockMode(false);
            resetApprovalGrants();
        }
        log("[TEST]   审批退出转断点: defer 唤醒 pending + 保持待批 + 迟到批准有效 通过");
        return createMsg().setParam(RESULT, true);
    }

    /** 断点审批 TTL（aitest 配 5000ms）：超期未处理 → decision=lost + reason 含"失效" */
    protected TLMsg testApprovalPendingTtl(Object fromWho, TLMsg msg) {
        TLTestApprovalReviewer.reset();
        resetApprovalGrants();
        // 非阻塞请求（审查人立即返回 pending）→ 转断点 → 排期 TTL
        TLMsg r1 = requestApprovalFor("file_operation", "{\"operation\":\"delete\",\"path\":\"D:/tmp/ttl.txt\"}");
        String id = r1.getStringParam(AI_P_APPROVAL_ID, "");
        if (!"pending".equals(r1.getStringParam(AI_P_APPROVAL_STATE, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "应 pending");
        }
        TLMsg q1 = putMsg("approvalGate", createMsg().setAction(APPROVAL_QUERY).setParam(AI_P_APPROVAL_ID, id));
        if (!"pending".equals(q1.getStringParam("decision", ""))) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "TTL 未到期应 decision=pending，实为 " + q1.getStringParam("decision", ""));
        }
        try { Thread.sleep(5500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        TLMsg q2 = putMsg("approvalGate", createMsg().setAction(APPROVAL_QUERY).setParam(AI_P_APPROVAL_ID, id));
        String decision = q2.getStringParam("decision", "");
        if (!"lost".equals(decision)) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "TTL（5000ms）到期应 decision=lost，实为 " + decision);
        }
        String reason = q2.getStringParam(AI_P_APPROVAL_REJECTREASON, "");
        if (!reason.contains("失效")) {
            return createMsg().setParam(RESULT, false).setParam("error", "TTL 失效 reason 应含'失效'，实为 " + reason);
        }
        resetApprovalGrants();
        log("[TEST]   断点审批TTL: 到期 decision=lost 通过");
        return createMsg().setParam(RESULT, true);
    }
```

注意：场景 18（`approvalExpire`，approvalTimeoutMs=3000、sleep 4000）在 TTL=5000 下仍走"审批超时"路径（3s < 5s）✓，但断言不变；既有场景 16/17 中遗留的 pending 请求会在 5s 后自行 EXPIRED，无害（无人断言）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cmd //c ".\run_aitest.bat"`
Expected: 23 FAIL（`APPROVAL_DEFER` 未注册 → action 未识别，`d.parseBoolean(RESULT)` false）；24 FAIL（decision 参数尚不存在）

- [ ] **Step 3: 实现 defer + TTL**

`TLApprovalModule.java`：

字段区（`approvalTimeoutMs` 下）：

```java
    /** 断点态审批 TTL（毫秒，默认 24h，仅作用于 deferred 审批；阻塞态不受影响）。0 = 不设 TTL */
    private long approvalPendingTtlMs = 86_400_000L;
```

`setModuleParams` 读入（`approvalTimeoutMs` 读入行后，`~:160`）：

```java
            if (params.get("approvalPendingTtlMs") != null) {
                try { approvalPendingTtlMs = Long.parseLong(params.get("approvalPendingTtlMs").toString()); }
                catch (NumberFormatException e) {
                    putLog("approvalPendingTtlMs 非法（需为毫秒数）: " + params.get("approvalPendingTtlMs"), LogLevel.WARN);
                }
            }
```

`checkMsgAction` switch 加（`APPROVAL_PENDINGLIST` case 附近）：

```java
            case APPROVAL_DEFER:   return handleDefer(fromWho, msg);
            case APPROVAL_ABANDON: return handleAbandon(fromWho, msg);   // handleAbandon 在 Task 4 实现
```

> 若本任务先于 Task 4 执行：本步只加 `APPROVAL_DEFER`，`APPROVAL_ABANDON` 行留待 Task 4。

`handleApprovalRequest` 的 pending 分支（`:509-512` 的 else 分支）改为：

```java
        } else {
            // pending：无人接收（转断点）或异步审查人已接受——等待登录重放/后续决策
            request.setDeferred(true);
            armPendingTtl(request);
            putLog("Approval pending (deferred): id=" + request.getApprovalId(), LogLevel.INFO);
        }
```

新增方法（`markExpired` 附近）：

```java
    /**
     * 退出登录 / 无人在线：把未决审批转为断点态——保持 PENDING，唤醒阻塞线程（若有）以 pending 返回，
     * 上层据此落断点（线程/会话释放）。幂等：未决才置位；已决策/已失效返回成功（无副作用）。
     */
    private TLMsg handleDefer(Object fromWho, TLMsg msg) {
        String approvalId = msg.getStringParam(AI_P_APPROVAL_ID, "");
        if (approvalId.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "missing approvalId");
        }
        TLApprovalRequest request = pendingApprovals.get(approvalId);
        if (request == null) {
            return createMsg().setParam(RESULT, false).setParam("error", "approval not found: " + approvalId);
        }
        if (!TLApprovalRequest.PENDING.equals(request.getState())) {
            return createMsg().setParam(RESULT, true);   // 已决策/已失效：defer 无意义，幂等成功
        }
        request.setDeferred(true);
        armPendingTtl(request);
        signalDecision(approvalId);
        putLog("Approval deferred: id=" + approvalId + " tool=" + request.getToolName(), LogLevel.INFO);
        return createMsg().setParam(RESULT, true);
    }

    /** 为断点态审批排期 TTL（幂等：请求级 ttlArmed 守卫；ttl<=0 不排队） */
    private void armPendingTtl(TLApprovalRequest request) {
        if (approvalPendingTtlMs <= 0 || request.isTtlArmed()) return;
        request.setTtlArmed(true);
        final String approvalId = request.getApprovalId();
        long ttl = approvalPendingTtlMs;
        String ttlText = ttl >= 3_600_000 ? (ttl / 3_600_000) + "小时" : fmtSeconds(ttl);
        timeoutScheduler.schedule(() -> {
            TLApprovalRequest r = pendingApprovals.get(approvalId);
            if (r != null && TLApprovalRequest.PENDING.equals(r.getState())) {
                markExpired(r, "审批已失效（超过" + ttlText + "未处理）");
            }
        }, ttl, TimeUnit.MILLISECONDS);
    }
```

`markExpired` 的消息箱文案改为带原因（`~:1184`）：

```java
        notifyInbox(request, "审批未处理已失效（" + request.getRejectionReason() + "）");
```

- [ ] **Step 4: aitest 配置加 TTL**

`demo/tlobject/src/main/resources/conf/demo/aitest/approvalGate_config.xml` `<params>` 内：

```xml
        <!-- 断点态审批 TTL：场景 approvalPendingTtl 用秒级值；须大于 approvalTimeoutMs(3000)
             以免抢在"审批超时"路径前面（场景 18 的路径断言会失真） -->
        <approvalPendingTtlMs value="5000"/>
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cmd //c ".\run_aitest.bat"`
Expected: 21-24 全 PASS，1-20 不回归

- [ ] **Step 6: Commit**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/approval/TLApprovalModule.java aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/test/TLAgentTestModule.java demo/tlobject/src/main/resources/conf/demo/aitest/approvalGate_config.xml
git commit -m "审批授权 13c：退出转断点（APPROVAL_DEFER）+ 断点审批 TTL"
```

---

## Task 4: APPROVAL_QUERY 决策映射 + 重放字段 + APPROVAL_ABANDON（spec §5.5/§5.8 数据面）

**Files:**
- Modify: `aiagent/common/.../approval/TLApprovalModule.java`（`handleQuery` `:617-629`、`handlePendingList` `:635-650`、新增 `handleAbandon`、`handleApprove` 返回 `:566`）
- Test: `TLAgentTestModule.java`（场景 23 已断言 decision/deferred；本任务补 abandon 断言进场景 23? 否——单独放场景 27 一并验，见 Task 6）

- [ ] **Step 1: handleQuery 加决策映射**

```java
    /**
     * 查询单个审批。除状态外附带 resume 决策映射（decision=approved/rejected/pending/lost）——
     * 断点恢复时 Agent 据此续跑，不自行解释状态语义。记录不在册（进程重启/被驱逐）按 lost 如实报。
     */
    private TLMsg handleQuery(Object fromWho, TLMsg msg) {
        String approvalId = msg.getStringParam(AI_P_APPROVAL_ID, "");
        if (approvalId.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "missing approvalId");
        }
        TLApprovalRequest request = pendingApprovals.get(approvalId);
        if (request == null) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_APPROVAL_STATE, "lost")
                    .setParam("decision", "lost")
                    .setParam(AI_P_APPROVAL_REJECTREASON, "审批记录已不存在（服务重启或已清理）");
        }
        TLMsg r = buildApprovalResponse(request, null);
        r.setParam("decision", decisionOf(request));
        if (TLApprovalRequest.PENDING.equals(request.getState())) {
            r.setParam(AI_P_APPROVAL_DEFERRED, request.isDeferred());
        }
        return r;
    }

    /** 审批状态 → resume 决策映射（Agent 侧只认这四种取值） */
    private static String decisionOf(TLApprovalRequest request) {
        switch (request.getState()) {
            case TLApprovalRequest.APPROVED: return "approved";
            case TLApprovalRequest.REJECTED: return "rejected";
            case TLApprovalRequest.EXPIRED:  return "lost";
            default:                          return "pending";
        }
    }
```

- [ ] **Step 2: handlePendingList 加重放字段**

`handlePendingList` 内 `m.put("text", ...)` 行后：

```java
            m.put("createdAt", request.getCreatedAt());
            m.put("deferred", request.isDeferred());
```

- [ ] **Step 3: handleApprove 返回 deferred + sessionId**

`handleApprove` 的成功返回（`:566`）改为：

```java
        return createMsg().setParam(RESULT, true).setParam(AI_P_APPROVAL_STATE, TLApprovalRequest.APPROVED)
                .setParam(AI_P_APPROVAL_DEFERRED, request.isDeferred())
                .setParam("sessionId", request.getSessionId() != null ? request.getSessionId() : request.getRootSessionId());
```

`handleReject` 的成功返回同样加这两行（前端拒绝后同样自动续跑）：

```java
        return createMsg().setParam(RESULT, true).setParam(AI_P_APPROVAL_STATE, TLApprovalRequest.REJECTED)
                .setParam(AI_P_APPROVAL_DEFERRED, request.isDeferred())
                .setParam("sessionId", request.getSessionId() != null ? request.getSessionId() : request.getRootSessionId());
```

- [ ] **Step 4: handleAbandon**

```java
    /**
     * 放弃审批（用户主动丢弃该操作）：置 REJECTED + 唤醒/关框，但【不记拒绝记忆】——
     * 放弃 ≠ 拒绝：拒绝记忆会让后续同参数操作永不弹框，放弃只是本次不做。
     */
    private TLMsg handleAbandon(Object fromWho, TLMsg msg) {
        String approvalId = msg.getStringParam(AI_P_APPROVAL_ID, "");
        if (approvalId.isEmpty()) {
            return createMsg().setParam(RESULT, false).setParam("error", "missing approvalId");
        }
        TLApprovalRequest request = pendingApprovals.get(approvalId);
        if (request == null) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "approval not found: " + approvalId)
                    .setParam("approvalEnded", true);
        }
        if (!TLApprovalRequest.PENDING.equals(request.getState())) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "approval already " + request.getState())
                    .setParam("approvalEnded", true);
        }
        request.setRejectionReason("用户放弃该操作");
        request.setState(TLApprovalRequest.REJECTED);
        signalDecision(approvalId);
        publishApprovalResolved(request);   // 其他窗口的弹框立即关闭
        putLog("Approval abandoned: id=" + approvalId + " tool=" + request.getToolName(), LogLevel.INFO);
        return createMsg().setParam(RESULT, true).setParam(AI_P_APPROVAL_STATE, TLApprovalRequest.REJECTED);
    }
```

并在 `checkMsgAction` 补 `case APPROVAL_ABANDON: return handleAbandon(fromWho, msg);`（若 Task 3 未提前加）。

- [ ] **Step 5: 编译 + 跑测试**

Run: `D:/maven/bin/mvn -f D:/tlobjectapp/tlobject/pom.xml install -pl aiagent/common -am -DskipTests && cmd //c ".\run_aitest.bat"`
Expected: 21-24 全绿（23 的 decision/deferred 断言依赖本任务）

- [ ] **Step 6: Commit**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/approval/TLApprovalModule.java
git commit -m "审批授权 13d：QUERY 决策映射（approved/rejected/pending/lost）+ 重放字段 + APPROVAL_ABANDON"
```

---

## Task 5: 执行器——fail-closed + 免复批 + stale 清理跳过（硬化①）

**Files:**
- Modify: `aiagent/common/.../TLToolExecutor.java`（`checkApprovalGate` `:456-497`、`doToolExec` `:327-348`、`executeTools` execMsg 构造 `:195-205`、`ExecutionState` `~:100`、`cleanupStaleStates` `:526-540`）
- Create: `demo/tlobject/src/main/resources/conf/demo/aitest/toolExecutor_config.xml`
- Test: 场景 25（Task 7）依赖本任务的免复批

- [ ] **Step 1: fail-closed（checkApprovalGate 三个放行分支改拒绝）**

`TLToolExecutor.java` `:452-497` 整个方法替换为（保留原有懒解析结构与消息构造，只把"解析失败/无应答/异常"三条放行改为拒绝）：

```java
    /**
     * 审批门禁：在执行工具前发送审批请求到审批模块。
     * @return null = 放行；非 null = 被拦截（pending/rejected）
     *
     * <p>安全默认（fail-closed）：未配置审批模块（功能未启用）→ 放行；
     * 配置了但模块解析不到 / 无应答 / 抛异常 → 一律拒绝执行，不放行——否则审批形同虚设。
     */
    private TLMsg checkApprovalGate(String toolCallId, Map<String, Object> toolArgs,
                                     String toolName, String sessionId, String userId, String roundId,
                                     String rootSessionId) {
        if (approvalModule == null && approvalModuleName != null && !approvalModuleName.isEmpty()) {
            synchronized (this) {
                if (approvalModule == null) {
                    Object m = moduleFactory != null ? moduleFactory.getModule(approvalModuleName) : null;
                    if (m == null && modules != null) m = modules.get(approvalModuleName);
                    if (m instanceof IObject) {
                        approvalModule = (IObject) m;
                        putLog("Approval module resolved: " + approvalModuleName, LogLevel.INFO);
                    } else {
                        // 配置了审批却解析不到模块：拒绝执行（fail-closed）。
                        // 原实现是"置 approvalModuleName=null 并放行"——审批静默失效，比报错危险。
                        // 不置 null（保留名字，模块稍后就绪时下次调用可自愈重试）。
                        putLog("Approval module not found: " + approvalModuleName + " → 拒绝执行（安全默认）", LogLevel.ERROR);
                        return buildApprovalUnavailable("审批服务不可用（模块 " + approvalModuleName + " 未找到）");
                    }
                }
            }
        }
        if (approvalModule == null) return null;   // 未配置审批模块 = 功能未启用（与"配置了但不可用"区分）

        try {
            TLMsg result = putMsg(approvalModule,
                    createMsg().setAction(APPROVAL_REQUEST)
                            .setParam("toolName", toolName)
                            .setParam("toolArgs", toolArgs != null
                                    ? new LinkedHashMap<>(toolArgs) : new LinkedHashMap<>())
                            .setSystemParam(AI_P_SESSIONID, sessionId)
                            .setSystemParam("userId", userId)
                            .setParam("toolCallId", toolCallId)
                            .setSystemParam(AI_P_ROUNDID, roundId)
                            .setSystemParam("rootSessionId", rootSessionId));

            if (result == null) {
                putLog("Approval gate no response → 拒绝执行（安全默认）", LogLevel.ERROR);
                return buildApprovalUnavailable("审批服务无应答");
            }
            String state = result.getStringParam(AI_P_APPROVAL_STATE, "");
            if ("approved".equals(state)) return null;
            return result;
        } catch (Exception e) {
            putLog("checkApprovalGate error: " + e.toString() + " → 拒绝执行（安全默认）", LogLevel.ERROR);
            return buildApprovalUnavailable("审批门禁异常");
        }
    }

    /** 审批不可用时的拒绝结果（fail-closed；与 rejected 同形，上游无需新分支） */
    private TLMsg buildApprovalUnavailable(String reason) {
        return createMsg().setParam(RESULT, false)
                .setParam(AI_P_APPROVAL_STATE, "rejected")
                .setParam(AI_P_APPROVAL_REJECTREASON, reason)
                .setParam(AI_P_SKILLOUTPUT, "⚠️ 操作被拒绝：" + reason + "（安全默认：审批不可用时拒绝执行）");
    }
```

- [ ] **Step 2: 免复批 + stale 计数守卫**

(a) `executeTools` 的每任务 execMsg 构造（`:195-205`，`TASKTIMEOUT` 设置旁）加标志透传：

```java
                // 断点恢复的"已批准"执行：人工已就本次调用做过决策，不得再次过门禁
                if ("approved".equals(String.valueOf(msg.getSystemParam(AI_P_APPROVAL_DECISION, "")))) {
                    execMsg.setSystemParam(AI_P_APPROVAL_DECISION, "approved");
                }
```

(b) `ExecutionState`（`:96-105` 字段区）加：

```java
        /** 正在等人工审批的任务数（>0 = 合法长跑，cleanupStaleStates 不得按超龄强杀） */
        volatile int approvalWaiters = 0;
```

(c) `doToolExec` 门禁段（`:327-348`）替换为：

```java
        try {
            // 审批门禁（按 LLM 函数名匹配规则，如 file_operation:delete；无函数名时回退模块名）。
            // 断点恢复且决策=approved：跳过门禁——否则 resume 重执行会再次弹框/转断点（死循环）
            boolean preApproved = "approved".equals(msg.getSystemParam(AI_P_APPROVAL_DECISION, ""));
            String approvalToolName = task.functionName != null && !task.functionName.isEmpty()
                    ? task.functionName : task.moduleName;
            TLMsg approvalResult = null;
            if (!preApproved) {
                state.approvalWaiters++;   // 等审批期间禁止 stale 清理按超龄强杀（见 cleanupStaleStates）
                try {
                    approvalResult = checkApprovalGate(task.toolCallId, task.args, approvalToolName,
                            String.valueOf(msg.getSystemParam(AI_P_SESSIONID, "default")),
                            String.valueOf(msg.getSystemParam("userId", "default")),
                            String.valueOf(msg.getSystemParam(AI_P_ROUNDID, "")),
                            String.valueOf(msg.getSystemParam("rootSessionId",
                                    String.valueOf(msg.getSystemParam(AI_P_SESSIONID, "default")))));
                } finally {
                    state.approvalWaiters--;
                }
            }
            if (approvalResult != null) {
                String approvalState = approvalResult.getStringParam(AI_P_APPROVAL_STATE, "");
                ToolResult tr = new ToolResult(task.toolCallId,
                        approvalResult.getStringParam(AI_P_SKILLOUTPUT, ""));
                tr.state = approvalState;
                if ("rejected".equals(approvalState))
                    tr.stateExtra = approvalResult.getStringParam(AI_P_APPROVAL_REJECTREASON, "用户拒绝");
                if ("pending".equals(approvalState))
                    tr.approvalId = approvalResult.getStringParam(AI_P_APPROVAL_ID, "");
                state.results.put(idx, tr);
                return null;
            }
```

（其余 `doToolExec` 体不动。）

(d) `cleanupStaleStates`（`:526-540`）扫描循环内：

```java
            if (now - s.createdAt > staleStateTimeoutMs) {
                // 有人正等人工审批：审批等待是设计语义内的合法长跑（approvalTimeoutMs=0 可无限），
                // 按超龄强杀会把"一直等"变成 600 秒后"审批被中断"（与 2026-10-14 修复同一故障类）
                if (s.approvalWaiters > 0) continue;
                stale.add(entry.getKey());
            }
```

- [ ] **Step 4: aitest 启用执行器审批**

新建 `demo/tlobject/src/main/resources/conf/demo/aitest/toolExecutor_config.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<moduleConfig>
    <params>
        <!-- 测试端启用执行器审批门禁（生产同款接线：approvalModule=approvalGate），
             场景 approvalResumeExecute 依赖"执行器真的会问审批"来验证免复批。
             未启用时该场景会假绿（门禁根本不会被问到）。 -->
        <approvalModule value="approvalGate"/>
        <!-- 与生产默认一致：0=不限时；staleState 清理阈值保持默认 600000（含 approvalWaiters 跳过守卫） -->
        <executionTimeoutMs value="0"/>
        <staleStateTimeoutMs value="600000"/>
    </params>
</moduleConfig>
```

- [ ] **Step 5: 编译 + 跑全量回归**

Run: `D:/maven/bin/mvn -f D:/tlobjectapp/tlobject/pom.xml install -pl aiagent/common -am -DskipTests && cmd //c ".\run_aitest.bat"`
Expected: 全绿（1-9 经执行器的场景：测试技能不在审批规则内 → 门禁放行，无影响）

- [ ] **Step 6: Commit**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLToolExecutor.java demo/tlobject/src/main/resources/conf/demo/aitest/toolExecutor_config.xml
git commit -m "审批授权 13e：执行器 fail-closed + 断点恢复免复批 + stale 清理跳过审批等待"
```

---

## Task 6: 会话管理——findIncomplete 覆盖待批断点 + 丢弃断点（spec §5.7/§5.8 数据面）

**Files:**
- Modify: `aiagent/common/.../TLBaseSessionManager.java`（抽象签名 `:51`、`doFindIncomplete` `:134-147`、新增 `doDiscardPendingRound`、`checkMsgAction` `:70-79`）
- Modify: `aiagent/common/.../TLSessionManager.java`（`findIncompleteMeta` `:159-190`）
- Modify: `aiagent/common/.../TLDatabaseSessionManager.java`（`findIncompleteMeta` `:172-185`）
- Test: 场景 27

- [ ] **Step 1: 写失败测试——场景 27（待批断点可见 + 可丢弃）**

注册表 + resolveTestCase：

```java
        TEST_CASES.put("approvalCheckpointDiscard", new String[]{"27-待批断点可见与丢弃", null});
```

```java
            case "approvalcheckpointdiscard": return this::testApprovalCheckpointDiscard;
```

```java
    /**
     * 待批断点：findIncomplete 覆盖 pending_approval（含 state/approvalId、支持 sessionId 过滤）；
     * discardPendingRound 后不再可见（放弃该操作 → 会话可正常继续）。
     */
    protected TLMsg testApprovalCheckpointDiscard(Object fromWho, TLMsg msg) {
        Object sm = getModuleInFactory("sessionManager");
        if (!(sm instanceof IObject)) {
            return createMsg().setParam(RESULT, false).setParam("error", "sessionManager 未配置");
        }
        String sid = "test_approval_discard";
        // 1. 造一个 pending_approval 轮（带 pendingToolCall + approvalId）
        TLToolCall tc = new TLToolCall("call_discard_1", "file_operation",
                new java.util.HashMap<String, Object>() {{ put("operation", "delete"); put("path", "D:/tmp/never.txt"); }});
        TLMsg upd = createMsg().setAction("sessionUpdated")
                .setParam("sessionId", sid)
                .setParam("userId", TEST_USER_ID)
                .setParam("agentName", "aiagent")
                .setParam("roundId", "r_discard_1")
                .setParam("messages", new java.util.ArrayList<>())
                .setParam("userMessage", "删除一个不存在的文件")
                .setParam("model", "mock")
                .setParam("temperature", 0.7)
                .setParam("maxTokens", 1024)
                .setParam("pendingToolCall", tc)
                .setParam(AI_P_APPROVAL_ID, "probe_discard_1");
        putMsg((IObject) sm, upd);
        // 2. findIncomplete（带 sessionId）应命中且带 state/approvalId
        TLMsg q = putMsg((IObject) sm, createMsg().setAction("findIncomplete")
                .setParam("userId", TEST_USER_ID).setParam("sessionId", sid));
        if (!q.parseBoolean(RESULT, false)
                || !SESSION_STATE_PENDING_APPROVAL.equals(q.getStringParam("state", ""))) {
            return createMsg().setParam(RESULT, false).setParam("error",
                    "findIncomplete 应命中 pending_approval，实为 state=" + q.getStringParam("state", ""));
        }
        if (!"probe_discard_1".equals(q.getStringParam(AI_P_APPROVAL_ID, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error",
                    "应带回 approvalId，实为 " + q.getStringParam(AI_P_APPROVAL_ID, ""));
        }
        // 3. 丢弃后不再可见
        TLMsg d = putMsg((IObject) sm, createMsg().setAction("discardPendingRound")
                .setParam("sessionId", sid).setParam("userId", TEST_USER_ID));
        if (!d.parseBoolean(RESULT, false)) {
            return createMsg().setParam(RESULT, false).setParam("error", "discardPendingRound 失败: " + d.getStringParam("error", ""));
        }
        TLMsg q2 = putMsg((IObject) sm, createMsg().setAction("findIncomplete")
                .setParam("userId", TEST_USER_ID).setParam("sessionId", sid));
        // 允许查到同会话其它断点（本场景没有）；但若查到还是 pending_approval 则失败
        if (q2.parseBoolean(RESULT, false)
                && SESSION_STATE_PENDING_APPROVAL.equals(q2.getStringParam("state", ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "丢弃后不应再命中 pending_approval");
        }
        log("[TEST]   待批断点可见与丢弃: findIncomplete + discardPendingRound 通过");
        return createMsg().setParam(RESULT, true);
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cmd //c ".\run_aitest.bat"`
Expected: 27 FAIL（state 为空——目前 findIncomplete 只认 checkpoint；且无 sessionId 参数/无 discard 动作）

- [ ] **Step 3: SessionManager 实现**

`TLBaseSessionManager.java`：

(a) 抽象签名（`:51`）：

```java
    protected abstract java.util.Map<String, Object> findIncompleteMeta(String userId, String sessionId);
```

(b) `checkMsgAction` 加 case（`:79` 后）：`case "discardPendingRound": return doDiscardPendingRound(fromWho, msg);`

(c) `doFindIncomplete`（`:134-147`）改为：

```java
    /** 查找最近一个未完成的断点（checkpoint 或 pending_approval；可选按 sessionId 限定） */
    private TLMsg doFindIncomplete(Object fromWho, TLMsg msg) {
        String userId = msg.getStringParam("userId", null);
        String sessionId = msg.getStringParam("sessionId", null);
        java.util.Map<String, Object> meta = findIncompleteMeta(userId, sessionId);
        if (meta == null) return createMsg().setParam(RESULT, false);
        String state = String.valueOf(meta.getOrDefault("state", ""));
        // approvalId 统一从轮次明细取（会话汇总表无该列；取该会话最后一个待批轮）
        if (SESSION_STATE_PENDING_APPROVAL.equals(state)) {
            java.util.List<java.util.Map<String, Object>> rounds =
                    loadRoundData(String.valueOf(meta.getOrDefault("sessionId", "")), userId);
            for (java.util.Map<String, Object> r : rounds) {
                if (SESSION_STATE_PENDING_APPROVAL.equals(String.valueOf(r.get("state")))) {
                    Object aid = r.get(AI_P_APPROVAL_ID);
                    if (aid != null && !String.valueOf(aid).isEmpty()) meta.put(AI_P_APPROVAL_ID, String.valueOf(aid));
                }
            }
        }
        return createMsg()
                .setParam(RESULT, true)
                .setParam("sessionId", meta.getOrDefault("sessionId", ""))
                .setParam("agentName", meta.getOrDefault("agentName", ""))
                .setParam("userMessage", meta.getOrDefault("userMessage", ""))
                .setParam("savedAt", meta.getOrDefault("savedAt", 0L))
                .setParam("iteration", meta.getOrDefault("roundSeq", 0))
                .setParam("state", state)
                .setParam(AI_P_APPROVAL_ID, meta.getOrDefault(AI_P_APPROVAL_ID, ""));
    }

    /** 丢弃待批断点：把该会话最后一个 pending_approval 轮改为 completed（放弃该操作后会话可正常继续） */
    private TLMsg doDiscardPendingRound(Object fromWho, TLMsg msg) {
        if (!enableCheckpoint) return createMsg().setParam(RESULT, true);
        String sessionId = msg.getStringParam("sessionId", "");
        String userId = msg.getStringParam("userId", null);
        if (sessionId.isEmpty()) return createMsg().setParam(RESULT, false).setParam("error", "missing sessionId");
        java.util.List<java.util.Map<String, Object>> rounds = loadRoundData(sessionId, userId);
        java.util.Map<String, Object> target = null;
        for (java.util.Map<String, Object> r : rounds) {
            if (SESSION_STATE_PENDING_APPROVAL.equals(String.valueOf(r.get("state")))) target = r;
        }
        if (target == null) {
            return createMsg().setParam(RESULT, false).setParam("error", "no pending_approval round");
        }
        target.put("state", SESSION_STATE_COMPLETED);
        target.remove("pendingToolCall");   // 残留会让 doSessionUpdated 再次判 pending
        target.remove(AI_P_APPROVAL_ID);
        storeRound(target);
        return createMsg().setParam(RESULT, true);
    }
```

注意：文件版 `storeRound` 是**追加**一行（last-wins 组装）；DB 版是"同 roundId 先删再插"——两条路径都靠"同 roundId 新行覆盖旧行"生效，无需新抽象方法。

`TLSessionManager.java`（`:159`）签名与过滤改：

```java
    @Override
    protected java.util.Map<String, Object> findIncompleteMeta(String userId, String sessionId) {
        try {
            java.io.File dir = new java.io.File(getSessionStorePath(userId));
            if (!dir.exists() || !dir.isDirectory()) return null;
            java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
            if (files == null || files.length == 0) return null;

            // 可选按会话限定（断点弹框的"放弃该操作"路径按 sessionId 精确定位）
            java.io.File want = (sessionId != null && !sessionId.isEmpty())
                    ? findSessionFile(getSessionStorePath(userId), sessionId) : null;

            // 按 state 过滤（checkpoint=意外中断 / pending_approval=待批转断点），取最近活跃的一个
            java.io.File best = null;
            TLMsg bestMeta = null;
            for (java.io.File f : files) {
                if (want != null && !f.equals(want)) continue;
                TLMsg meta = readLastLineMeta(f);
                if (meta == null) continue;
                String st = meta.getStringParam("state", "");
                if (SESSION_STATE_CHECKPOINT.equals(st) || SESSION_STATE_PENDING_APPROVAL.equals(st)) {
                    if (best == null || f.lastModified() > best.lastModified()) {
                        best = f;
                        bestMeta = meta;
                    }
                }
            }
```

（`result.put(...)` 区不变；`readLastLineMeta` 已含 `state`/`approvalId`，无需改。）

`TLDatabaseSessionManager.java`（`:172`）：

```java
    @Override
    @SuppressWarnings("unchecked")
    protected java.util.Map<String, Object> findIncompleteMeta(String userId, String sessionId) {
        if (ensureSessTable() == null) return null;
        try {
            LinkedHashMap<String, Object> p = new LinkedHashMap<>();
            p.put("user_id", userId != null ? userId : "");
            p.put("s_checkpoint", SESSION_STATE_CHECKPOINT);
            p.put("s_pending", SESSION_STATE_PENDING_APPROVAL);
            String sql = "select * from [table] where user_id=? and state in (?,?)";
            if (sessionId != null && !sessionId.isEmpty()) {
                sql += " and session_id=?";
                p.put("session_id", sessionId);
            }
            sql += " order by last_active desc limit 1";
            TLMsg result = putMsg(sessTable, createMsg().setAction(DB_QUERY)
                    .setParam(DB_P_SQL, sql)
                    .setParam(DB_P_PARAMS, p));
            java.util.List<java.util.Map<String, Object>> rows = getResultList(result);
            if (rows == null || rows.isEmpty()) return null;
            return metaFromSessRow(rows.get(0));
        } catch (Exception e) { return null; }
    }
```

（DB 版 `metaFromSessRow` 已带 `state`；`approvalId` 由基类 `doFindIncomplete` 从轮次明细补齐 ✓。）

- [ ] **Step 4: 跑测试确认通过**

Run: `D:/maven/bin/mvn -f D:/tlobjectapp/tlobject/pom.xml install -pl aiagent/common -am -DskipTests && cmd //c ".\run_aitest.bat"`
Expected: 27 PASS，全量不回归

- [ ] **Step 5: Commit**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLBaseSessionManager.java aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLSessionManager.java aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLDatabaseSessionManager.java aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/test/TLAgentTestModule.java
git commit -m "审批授权 13f：findIncomplete 覆盖待批断点（sessionId 过滤）+ discardPendingRound"
```

---

## Task 7: Agent——决策写回 + "lost" 分支（spec §5.5/§5.8）

**Files:**
- Modify: `aiagent/common/.../TLAiAgent.java`（非流式 resume 分支 `:1078-1152`、流式 `:1837-1886`、新增 `queryApproval`）
- Test: 场景 25/26

- [ ] **Step 1: 写失败测试——场景 25（批准 → 真执行 + 免复批）/ 26（pending 文案 + lost 不执行）**

注册表 + resolveTestCase：

```java
        TEST_CASES.put("approvalResumeExecute", new String[]{"25-断点恢复批准执行", null});
        TEST_CASES.put("approvalResumeLost", new String[]{"26-断点恢复失效不执行", null});
```

```java
            case "approvalresumeexecute": return this::testApprovalResumeExecute;
            case "approvalresumelost": return this::testApprovalResumeLost;
```

```java
    /** 断点恢复测试用的临时文件（相对启动目录；file_operation 默认根 "."） */
    protected static final String RESUME_PROBE_FILE = "data/aitest_resume_probe.txt";

    private TLMsg buildResumeChat(String approvalId, Object decision, List<TLConversationHistory> history,
                                  TLToolCall tc, String sessionId) {
        TLMsg chatMsg = createMsg().setAction(AGENT_CHAT)
                .setSystemParam(AI_P_SESSIONID, sessionId)
                .setSystemParam(AI_P_USERID, TEST_USER_ID)
                .setParam(AI_P_USERMESSAGE, "删除测试文件")
                .setParam("resume", true)
                .setParam("history", history)
                .setParam("resumeState", SESSION_STATE_PENDING_APPROVAL)
                .setParam(AI_P_APPROVAL_ID, approvalId)
                .setParam("pendingToolCall", tc);
        if (decision != null) chatMsg.setParam(AI_P_APPROVAL_DECISION, decision);
        return chatMsg;
    }

    /**
     * 断点恢复（批准）：Agent 向 gate 查回 decision=approved → 真正重执行工具（文件被删），
     * 且执行器免复批（审查人计数不增）——否则 resume → 再弹框 → 死循环。
     */
    protected TLMsg testApprovalResumeExecute(Object fromWho, TLMsg msg) {
        Object gate = getModuleInFactory("approvalGate");
        if (!(gate instanceof IObject)) return createMsg().setParam(RESULT, false).setParam("error", "approvalGate 未配置");
        TLMockProvider mp = getMockProvider();
        if (mp == null) return createMsg().setParam(RESULT, false).setParam("error", "mockProvider 未配置");
        TLTestApprovalReviewer.reset();
        resetApprovalGrants();
        TLTestApprovalReviewer.setBlockMode(false);

        java.io.File f = new java.io.File(RESUME_PROBE_FILE);
        try {
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), "resume-probe".getBytes("UTF-8"));
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false).setParam("error", "写测试文件失败: " + e);
        }
        // 1. 造真实待批请求并批准（scope=once）
        TLMsg r1 = requestApprovalFor("file_operation",
                "{\"operation\":\"delete\",\"path\":\"" + RESUME_PROBE_FILE + "\"}");
        String approvalId = r1.getStringParam(AI_P_APPROVAL_ID, "");
        if (!"pending".equals(r1.getStringParam(AI_P_APPROVAL_STATE, ""))) {
            return createMsg().setParam(RESULT, false).setParam("error", "应 pending");
        }
        int countBefore = TLTestApprovalReviewer.count();
        TLMsg ap = putMsg("approvalGate", createMsg().setAction(APPROVAL_APPROVE)
                .setParam(AI_P_APPROVAL_ID, approvalId));
        if (!ap.parseBoolean(RESULT, false)) {
            return createMsg().setParam(RESULT, false).setParam("error", "批准失败: " + ap.getStringParam("error", ""));
        }
        // 2. resume（不带 decision → Agent 自己查 gate 写回）
        mp.clearAllResponses();
        mp.enqueueResponse(mp.textResponse("已删除测试文件。"));
        TLToolCall tc = new TLToolCall("call_resume_1", "file_operation",
                new java.util.HashMap<String, Object>() {{
                    put("operation", "delete"); put("path", RESUME_PROBE_FILE);
                }});
        List<TLConversationHistory> history = new java.util.ArrayList<>();
        history.add(new TLConversationHistory(TLConversationHistory.Role.user, "删除测试文件"));
        TLMsg response = putMsg(M_AIAGENT, buildResumeChat(approvalId, null, history, tc, "test_approval_resume"));
        if (!response.parseBoolean(RESULT, false)) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "resume 应成功，实为: " + response.getStringParam(AI_P_RESPONSE, ""));
        }
        if (f.exists()) {
            return createMsg().setParam(RESULT, false).setParam("error", "decison=approved 应重执行删除，文件仍存在");
        }
        if (TLTestApprovalReviewer.count() != countBefore) {
            return createMsg().setParam(RESULT, false).setParam("error",
                    "断点恢复的已批准执行不应再过门禁（审查人计数 " + countBefore + " → " + TLTestApprovalReviewer.count() + "）");
        }
        resetApprovalGrants();
        log("[TEST]   断点恢复批准执行: 真删除 + 免复批 通过");
        return createMsg().setParam(RESULT, true);
    }

    /**
     * 断点恢复（pending 仍在等 / lost 已失效）：pending → 返回"仍在等待中"不执行；
     * lost → 不执行、注入失效说明后继续对话（项目不假装续跑）。
     */
    protected TLMsg testApprovalResumeLost(Object fromWho, TLMsg msg) {
        Object gate = getModuleInFactory("approvalGate");
        if (!(gate instanceof IObject)) return createMsg().setParam(RESULT, false).setParam("error", "approvalGate 未配置");
        TLMockProvider mp = getMockProvider();
        if (mp == null) return createMsg().setParam(RESULT, false).setParam("error", "mockProvider 未配置");
        TLTestApprovalReviewer.reset();
        resetApprovalGrants();

        java.io.File f = new java.io.File(RESUME_PROBE_FILE);
        try {
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), "resume-probe".getBytes("UTF-8"));
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false).setParam("error", "写测试文件失败: " + e);
        }
        TLToolCall tc = new TLToolCall("call_lost_1", "file_operation",
                new java.util.HashMap<String, Object>() {{
                    put("operation", "delete"); put("path", RESUME_PROBE_FILE);
                }});
        List<TLConversationHistory> history = new java.util.ArrayList<>();
        history.add(new TLConversationHistory(TLConversationHistory.Role.user, "删除测试文件"));

        // A. pending：造一个真实的待批请求（不批准）→ resume 应拒绝执行并返回等待文案
        TLMsg r1 = requestApprovalFor("file_operation",
                "{\"operation\":\"delete\",\"path\":\"" + RESUME_PROBE_FILE + "\"}");
        String pendingId = r1.getStringParam(AI_P_APPROVAL_ID, "");
        TLMsg respA = putMsg(M_AIAGENT, buildResumeChat(pendingId, null, history, tc, "test_approval_lost"));
        if (f.exists() == false) {
            return createMsg().setParam(RESULT, false).setParam("error", "pending 恢复不应执行删除");
        }
        String respAText = respA.getStringParam(AI_P_RESPONSE, "");
        if (!respAText.contains("等待")) {
            return createMsg().setParam(RESULT, false).setParam("error", "pending 恢复应提示仍在等待，实为: " + respAText);
        }
        // B. lost：不存在的 approvalId（进程重启语义）→ 不执行，注入失效说明后正常回答
        mp.clearAllResponses();
        mp.enqueueResponse(mp.textResponse("已跳过该操作，请问还需要什么？"));
        TLMsg respB = putMsg(M_AIAGENT, buildResumeChat("no_such_approval_id", null, history, tc, "test_approval_lost"));
        if (f.exists() == false) {
            return createMsg().setParam(RESULT, false).setParam("error", "lost 恢复不应执行删除");
        }
        if (!respB.parseBoolean(RESULT, false) || !respB.getStringParam(AI_P_RESPONSE, "").contains("还需要什么")) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "lost 恢复应注入失效说明并继续对话，实为: " + respB.getStringParam(AI_P_RESPONSE, ""));
        }
        // 收尾：清理探针文件与遗留待批
        try { java.nio.file.Files.deleteIfExists(f.toPath()); } catch (Exception ignored) {}
        resetApprovalGrants();
        log("[TEST]   断点恢复失效不执行: pending 等待文案 + lost 继续对话 通过");
        return createMsg().setParam(RESULT, true);
    }
```

（`AGENT_CHAT` 常量在测试模块已用于场景 1；`SESSION_STATE_PENDING_APPROVAL` 来自 `TLAiAgentParamString`。）

- [ ] **Step 2: 跑测试确认失败**

Run: `cmd //c ".\run_aitest.bat"`
Expected: 25 FAIL（decision 无写入方 → 落入 pending 分支 → 文件未删 + 返回"等待"）；26 的 A 段可能通过（pending 文案已存在）、B 段 FAIL（lost 走 pending 分支报等待）

- [ ] **Step 3: 实现决策查询写回 + lost 分支（非流式）**

`TLAiAgent.java` 新增方法（放 resume 相关代码附近，类内私有）：

```java
    /**
     * 断点恢复时向审批模块查本次审批的终态（approved/rejected/pending/lost）。
     * 返回审批响应 TLMsg；无 approvalGate（未启用审批）或查询失败返回 null（调用方按 pending 处理）。
     */
    private TLMsg queryApproval(String approvalId) {
        if (approvalId == null || approvalId.isEmpty()) return null;
        try {
            TLMsg q = createMsg().setAction(APPROVAL_QUERY).setParam(AI_P_APPROVAL_ID, approvalId);
            q.setSystemParam(IGNOREMODULEISNULL, true);   // 缺模块静默（默认行为会关停整个应用）
            return putMsg("approvalGate", q);
        } catch (Exception e) {
            putLog("query approval decision failed: " + e, LogLevel.WARN);
            return null;
        }
    }
```

非流式分支（`:1089-1152`）改为：

```java
                    TLToolCall savedTc = msg.containsParam("pendingToolCall")
                            ? (TLToolCall) msg.getParam("pendingToolCall") : null;
                    String savedApprovalId = msg.getStringParam(AI_P_APPROVAL_ID, "");

                    // 决策写回：调用方带了 decision（外部驱动）直接用；否则向审批模块查终态
                    String decision = msg.getStringParam(AI_P_APPROVAL_DECISION, "");
                    TLMsg approvalState = null;
                    if (decision.isEmpty()) {
                        approvalState = queryApproval(savedApprovalId);
                        decision = approvalState != null
                                ? approvalState.getStringParam("decision", "pending") : "pending";
                    }
                    if ("approved".equals(decision) && savedTc != null) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> modifiedArgs =
                                msg.getMapParam(AI_P_APPROVAL_MODIFIEDARGS,
                                        approvalState != null
                                                ? approvalState.getMapParam(AI_P_APPROVAL_MODIFIEDARGS, null) : null);
```

（其余 approved 分支不动；executor 调用处加免复批标志，见下。）

```java
                        TLMsg singleExecResult = singleTask.isEmpty() ? null
                                : putMsg(toolExecutor, createMsg().setAction(TODOOLEXECUTE)
                                        .setParam("tasks", singleTask)
                                        .setSystemParam("executionId", execId2)
                                        .setSystemParam(AI_P_SESSIONID, sessionId)
                                        .setSystemParam("userId", msg.getSystemParam("userId", "default"))
                                        .setSystemParam("rootSessionId", rootSid)
                                        .setSystemParam(AI_P_ROUNDID, roundId)
                                        .setSystemParam(AI_P_APPROVAL_DECISION, "approved"));   // 免复批：本次调用已获人工批准
```

rejected 分支的 reason 兜底改为也看 approvalState：

```java
                    } else if ("rejected".equals(decision)) {
                        String reason = msg.getStringParam(AI_P_APPROVAL_REJECTREASON,
                                approvalState != null
                                        ? approvalState.getStringParam(AI_P_APPROVAL_REJECTREASON, "用户拒绝") : "用户拒绝");
                        history.add(new TLConversationHistory(
                                TLConversationHistory.Role.user,
                                "（上一操作已被拒绝：" + reason + "。请寻找替代方案。）"));
                        putLog("Approval resumed: rejected → reason=" + reason, LogLevel.INFO);
                    } else if ("lost".equals(decision)) {
                        String reason = approvalState != null
                                ? approvalState.getStringParam(AI_P_APPROVAL_REJECTREASON, "审批已失效") : "审批已失效";
                        history.add(new TLConversationHistory(
                                TLConversationHistory.Role.user,
                                "（上一操作未执行：审批已失效——" + reason + "。如需执行请重新发起。）"));
                        putLog("Approval resumed: lost → reason=" + reason, LogLevel.WARN);
                    } else {
                        putLog("Approval still pending on resume: approvalId=" + savedApprovalId, LogLevel.WARN);
                        return createMsg().setParam(RESULT, false)
                                .setParam(AI_P_RESPONSE, "审批请求仍在等待中，approvalId=" + savedApprovalId);
                    }
```

（原 `String decision = msg.getStringParam(AI_P_APPROVAL_DECISION, "pending")` 一行删除，由上面替换。）

- [ ] **Step 4: 流式分支同样改造（`:1837-1886`）**

`chatStream` 内 `SESSION_STATE_PENDING_APPROVAL.equals(resumeState)` 块（`:1837-1886`）替换为：

```java
                if (SESSION_STATE_PENDING_APPROVAL.equals(resumeState)) {
                    // ==== 审批断点恢复（流式）：context system 置顶（DB 历史不含，恢复必须显式补） ====
                    fullHistory = withContextSystem(loadedHistory);
                    TLToolCall savedTc = msg.containsParam("pendingToolCall")
                            ? (TLToolCall) msg.getParam("pendingToolCall") : null;
                    String savedApprovalId2 = msg.getStringParam(AI_P_APPROVAL_ID, "");
                    // 决策写回：调用方带了 decision 直接用；否则向审批模块查终态（与非流式 doChat 同构）
                    String decision = msg.getStringParam(AI_P_APPROVAL_DECISION, "");
                    TLMsg approvalState2 = null;
                    if (decision.isEmpty()) {
                        approvalState2 = queryApproval(savedApprovalId2);
                        decision = approvalState2 != null
                                ? approvalState2.getStringParam("decision", "pending") : "pending";
                    }
                    if ("approved".equals(decision) && savedTc != null) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> modifiedArgs =
                                msg.getMapParam(AI_P_APPROVAL_MODIFIEDARGS,
                                        approvalState2 != null
                                                ? approvalState2.getMapParam(AI_P_APPROVAL_MODIFIEDARGS, null) : null);
                        if (modifiedArgs != null && !modifiedArgs.isEmpty()) {
                            savedTc.setArguments(modifiedArgs);
                        }
                        // 批准：重新执行被审批的工具，结果入历史（与 doChat 审批恢复一致）
                        TLMsg frResolveMsg = putMsg(toolManager, createMsg().setAction(AGENT_RESOLVETOOLCALLS)
                                .setParam(AI_P_TOOLCALLS, savedTc));
                        List<TLToolExecutor.ToolTask> singleTask = (List<TLToolExecutor.ToolTask>)
                                frResolveMsg.getListParam("tasks", new ArrayList<>());
                        String execId2 = sessionId + "_resume_" + System.nanoTime();
                        TLMsg singleExecResult = singleTask.isEmpty() ? null
                                : putMsg(toolExecutor, createMsg().setAction(TODOOLEXECUTE)
                                        .setParam("tasks", singleTask)
                                        .setSystemParam("executionId", execId2)
                                        .setSystemParam(AI_P_SESSIONID, sessionId)
                                        .setSystemParam("userId", msg.getSystemParam("userId", "default"))
                                        .setSystemParam("rootSessionId", rootSid)
                                        .setSystemParam(AI_P_ROUNDID, roundId)
                                        .setSystemParam(AI_P_APPROVAL_DECISION, "approved"));   // 免复批：本次调用已获人工批准
                        // 与其它工具结果写点一致：经 appendToolResult/flushToolImages 走产图管线
                        //（审批后执行的工具同样可能声明投图；直接 fullHistory.add 会丢掉声明的那张图）
                        List<TLAttachmentRef> pendingImages = new ArrayList<>();
                        TLToolExecutor.ToolResult singleResult = null;
                        if (singleExecResult != null && singleExecResult.parseBoolean("success", false)) {
                            List<TLToolExecutor.ToolResult> singleResults = (List<TLToolExecutor.ToolResult>)
                                    singleExecResult.getListParam("results", null);
                            if (singleResults != null && !singleResults.isEmpty())
                                singleResult = singleResults.get(0);
                        }
                        appendToolResult(fullHistory,
                                singleResult != null ? singleResult
                                        : new TLToolExecutor.ToolResult(savedTc.getId(), ""),
                                sessionId, pendingImages);
                        flushToolImages(fullHistory, pendingImages);
                        putLog("Approval resumed (stream): approved → tool executed: " + savedTc.getFunctionName(),
                                LogLevel.INFO);
                    } else if ("rejected".equals(decision)) {
                        String reason = msg.getStringParam(AI_P_APPROVAL_REJECTREASON,
                                approvalState2 != null
                                        ? approvalState2.getStringParam(AI_P_APPROVAL_REJECTREASON, "用户拒绝") : "用户拒绝");
                        fullHistory.add(new TLConversationHistory(
                                TLConversationHistory.Role.user,
                                "（上一操作已被拒绝：" + reason + "。请寻找替代方案。）"));
                        putLog("Approval resumed (stream): rejected → reason=" + reason, LogLevel.INFO);
                    } else if ("lost".equals(decision)) {
                        String reason = approvalState2 != null
                                ? approvalState2.getStringParam(AI_P_APPROVAL_REJECTREASON, "审批已失效") : "审批已失效";
                        fullHistory.add(new TLConversationHistory(
                                TLConversationHistory.Role.user,
                                "（上一操作未执行：审批已失效——" + reason + "。如需执行请重新发起。）"));
                        putLog("Approval resumed (stream): lost → reason=" + reason, LogLevel.WARN);
                    } else {
                        putLog("Approval still pending on stream resume: approvalId="
                                + msg.getStringParam(AI_P_APPROVAL_ID, ""), LogLevel.WARN);
                        cleanupStreamState(sessionId);
                        return null;
                    }
                } else {
```

（注意流式路径比非流式多了 `success` 门禁与 `flushToolImages`——保留；流式与非流式的 approved 分支差异见 `:1862` 与 `:1117-1139` 的既有实现，本次不改。）

- [ ] **Step 5: 跑测试确认通过**

Run: `D:/maven/bin/mvn -f D:/tlobjectapp/tlobject/pom.xml install -pl aiagent/common -am -DskipTests && cmd //c ".\run_aitest.bat"`
Expected: 25/26 PASS，全量不回归

- [ ] **Step 6: Commit**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAiAgent.java aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/test/TLAgentTestModule.java
git commit -m "审批授权 13g：断点恢复决策写回（查 gate）+ lost 分支（失效不假装续跑）"
```

---

## Task 8: TLAgentService——流式 resume 透传 + abandon 动作

**Files:**
- Modify: `aiagent/common/.../TLAgentService.java`（`doChatStream` resume 块 `:373-388`、`doApprove` `:952-996`、新 `doAbandonApproval`、action switch `:110-178` + `ACTION_REGISTRY` `:42-83`）

- [ ] **Step 1: doChatStream 补 pendingToolCall/approvalId 透传（与 doChat 对齐）**

`TLAgentService.java` `:380-387` 的 `if (loaded != null && ...)` 块内，`resumeState` 行后加：

```java
                // 透传审批信息（若存在）——非流式 doChat 同款；此前流式漏了这两行，
                // 断点恢复在流式路径直接落入"仍在等待中"（找不到 pendingToolCall）
                if (loaded.containsParam("pendingToolCall")) {
                    streamMsg.setParam("pendingToolCall", loaded.getParam("pendingToolCall"));
                }
                if (loaded.containsParam(AI_P_APPROVAL_ID)) {
                    streamMsg.setParam(AI_P_APPROVAL_ID, loaded.getStringParam(AI_P_APPROVAL_ID, ""));
                }
```

- [ ] **Step 2: doApprove 透传 deferred/sessionId**

`doApprove` 批准分支的成功返回（`:979`）改为：

```java
            TLMsg okMsg = ok("已批准: " + approvalId);
            if (r.parseBoolean(AI_P_APPROVAL_DEFERRED, false)) {
                okMsg.setParam(AI_P_APPROVAL_DEFERRED, true);   // 前端据此自动续跑
                okMsg.setParam("sessionId", r.getStringParam("sessionId", ""));
            }
            return okMsg;
```

拒绝分支（`:992`）同样处理：

```java
            TLMsg okMsg = ok("已拒绝: " + approvalId + " (" + reason + ")");
            if (r.parseBoolean(AI_P_APPROVAL_DEFERRED, false)) {
                okMsg.setParam(AI_P_APPROVAL_DEFERRED, true);
                okMsg.setParam("sessionId", r.getStringParam("sessionId", ""));
            }
            return okMsg;
```

- [ ] **Step 3: doAbandonApproval**

```java
    /**
     * 放弃该操作（断点弹框入口）：撤销该会话的待批审批（不记拒绝记忆）+ 丢弃 pending_approval 断点。
     * approvalId 缺省时按 sessionId 从 gate 的未决清单补齐（前端断点弹框只有 sessionId）。
     */
    private TLMsg doAbandonApproval(Object fromWho, TLMsg msg) {
        String sessionId = msg.getStringParam("sessionId", "");
        String approvalId = msg.getStringParam("approvalId", "");
        if (sessionId.isEmpty() && approvalId.isEmpty()) {
            return fail("缺少参数: sessionId 或 approvalId");
        }
        String ownErr = checkSessionOwnership(msg, sessionId);
        if (ownErr != null) return fail(ownErr);

        List<String> ids = new ArrayList<>();
        if (!approvalId.isEmpty()) {
            ids.add(approvalId);
        } else {
            TLMsg q = createMsg().setAction(APPROVAL_PENDINGLIST);
            q.setSystemParam(IGNOREMODULEISNULL, true);
            TLMsg r = putMsg("approvalGate", q);
            Object listObj = r != null ? r.getParam("pendingList") : null;
            if (listObj instanceof List) {
                for (Object o : (List<?>) listObj) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> m = (Map<?, ?>) o;
                    if (sessionId.equals(String.valueOf(m.get("sessionId")))) {
                        ids.add(String.valueOf(m.get("approvalId")));
                    }
                }
            }
        }
        int abandoned = 0;
        for (String id : ids) {
            TLMsg ab = createMsg().setAction(APPROVAL_ABANDON).setParam(AI_P_APPROVAL_ID, id);
            ab.setSystemParam(IGNOREMODULEISNULL, true);
            TLMsg r = putMsg("approvalGate", ab);
            if (r != null && r.parseBoolean(RESULT, false)) abandoned++;
        }
        // 丢弃断点（把该会话的 pending_approval 轮改为 completed）
        if (!sessionId.isEmpty()) {
            TLMsg d = createMsg().setAction("discardPendingRound")
                    .setParam("sessionId", sessionId)
                    .setParam("userId", resolveUid(msg));
            d.setSystemParam(IGNOREMODULEISNULL, true);
            putMsg(targetSessionManager(msg), d);
        }
        return ok("已放弃该操作" + (abandoned > 0 ? "（撤销 " + abandoned + " 条待批审批）" : ""));
    }
```

action switch 加 `case "abandonApproval": return doAbandonApproval(fromWho, msg);`；`ACTION_REGISTRY` 加：

```java
        ACTION_REGISTRY.put("abandonApproval", "放弃待批操作（撤销审批 + 丢弃断点）");
```

- [ ] **Step 4: 编译**

Run: `D:/maven/bin/mvn -f D:/tlobjectapp/tlobject/pom.xml install -pl aiagent/common -am -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAgentService.java
git commit -m "审批授权 13h：流式 resume 透传审批信息 + approve 透传 deferred + abandonApproval 动作"
```

---

## Task 9: webui 后端——退出转断点 + 输入拦截 + 重放字段

**Files:**
- Modify: `aiagent/webui/.../webui/TLWebChatModule.java`（`doLogout` `:479-502`、`rejectPendingApprovals` `:727-749`、`replayPendingApprovals` `:778-800`、`doChat` `:514-539`、`doChatStream` `:620-670`、command 透传 `:312-321`）
- Modify: `aiagent/webui/.../webui/TLWebChatServlet.java`（`handleLogout` `:200-208`）

- [ ] **Step 1: doChatStream 补 resume 透传（框架路由路径）**

`doChatStream` 的 submit 构造（`:649-657`，`reasoningMode` 设置行旁）加——**这是既有缺口**：框架路由 `/api/chatStream` 的 `resume` 参数被丢弃（只有 servlet 回退路径 `beginChatStream:402` 传了），前端「▶ 恢复执行」发的 `{resume:true}` 到不了 agent；决策写回后的自动续跑依赖本修复：

```java
            // 断点恢复透传（framework 路由路径此前漏了这行——resume 请求会被当成新对话重跑）
            if (msg.parseBoolean("resume", false)) submit.setParam("resume", true);
```

- [ ] **Step 2: doLogout 改 defer**

`doLogout` 里调用点改名：

```java
        deferPendingApprovals(userId);    // 退出转断点：待批审批保持待批，等重登后处理（取代"退出即拒"）
```

`rejectPendingApprovals`（`:727-749`）整体替换为：

```java
    /** 退出登录：把该用户的未决审批转为断点态（保持待批 + 唤醒阻塞线程以 pending 返回），并写消息箱告知 */
    private void deferPendingApprovals(String userId) {
        for (Map<String, Object> m : pendingApprovalsOf(userId)) {
            try {
                TLMsg defer = createMsg().setAction(APPROVAL_DEFER)
                        .setParam(AI_P_APPROVAL_ID, String.valueOf(m.get("approvalId")));
                defer.setSystemParam(IGNOREMODULEISNULL, true);   // 缺模块时静默返回（默认行为会关停整个应用）
                putMsg("approvalGate", defer);
            } catch (Exception e) {
                putLog("审批转断点失败: " + e, LogLevel.WARN);
            }
            // 消息箱告知（转断点口径：审批没被拒绝，回来后仍可批准/放弃）
            try {
                String note = "⏳ 你退出登录时有一项审批未处理，已转为待批断点：工具 "
                        + String.valueOf(m.get("toolName")) + "，会话 " + String.valueOf(m.get("sessionId"))
                        + "。重新登录后可批准或放弃该操作。";
                inboxStore.add(userId, "approval", String.valueOf(m.get("approvalId")), note);
                pushInboxEvent(userId, inboxStore.unreadCount(userId));
            } catch (Exception e) {
                putLog("审批转断点告知写入消息箱失败: " + e, LogLevel.DEBUG);
            }
        }
    }
```

`TLWebChatServlet.handleLogout`（`:200-208`）：`mod.closeEventsChannel(userId);` 后加 `mod.deferPendingApprovals(userId);`（同包可见即可，方法保持 private 时改为包私有——去掉 `private`）。

- [ ] **Step 3: 重放事件带 deferred/createdAt**

`replayPendingApprovals` 内 `evt.put("args", ...)` 行后：

```java
            // 断点态 + 发起时间：前端弹框显示"发起于 X 前"，避免误当成刚刚的操作
            evt.put("deferred", Boolean.TRUE.equals(m.get("deferred")));
            evt.put("createdAt", m.get("createdAt") != null ? m.get("createdAt") : 0L);
```

- [ ] **Step 4: 新消息拦截（doChat + doChatStream）**

新增辅助方法（`pendingApprovalsOf` 附近）：

```java
    /** 该会话是否有未决审批（有 → 拦截新消息：批准/拒绝/放弃后才能继续） */
    private boolean hasPendingApproval(String userId, String sessionId) {
        for (Map<String, Object> m : pendingApprovalsOf(userId)) {
            if (sessionId.equals(String.valueOf(m.get("sessionId")))) return true;
        }
        return false;
    }
```

`doChat` 的占用校验块（`:530`）后、`doChatStream` 的占用校验块（`:636`）后各加：

```java
        // 待批操作守卫：会话有未决审批时拦新消息（批准/拒绝/放弃三选，见审批弹框与断点弹框）
        if (hasPendingApproval(userId, sessionId)) {
            return outJson(failMap("该会话有待批操作（批准 / 拒绝 / 放弃后才能继续）。"
                    + "请在审批弹框中处理，或在断点提示中选择「放弃该操作」"), null);
        }
```

注意：`doChatStream` 里必须在 `openSseChannel` **之前**（fail fast，别开通道再拒）。

- [ ] **Step 5: approve 结果透传 deferred/sessionId**

命令结果包装（`:317-318` approvalEnded 旁）加：

```java
        // 断点态审批的决策：前端据此自动续跑该会话（批准/拒绝后立即 resume）
        if (result.parseBoolean(AI_P_APPROVAL_DEFERRED, false)) {
            out.put("deferred", true);
            if (result.containsParam("sessionId")) out.put("sessionId", result.getStringParam("sessionId", ""));
        }
```

- [ ] **Step 6: 编译**

Run: `D:/maven/bin/mvn -f D:/tlobjectapp/tlobject/pom.xml install -pl aiagent/webui -am -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add aiagent/webui/src/main/java/cn/tianlong/tlobject/aiagent/webui/TLWebChatModule.java aiagent/webui/src/main/java/cn/tianlong/tlobject/aiagent/webui/TLWebChatServlet.java
git commit -m "审批授权 13i：webui 退出转断点 + 待批会话拦截新消息 + 重放带 deferred/createdAt"
```

---

## Task 10: webui 前端——陈旧提示 + 自动续跑 + 放弃该操作

**Files:**
- Modify: `aiagent/webui/src/main/resources/webui/app.js`（`showApprovalModal` `:2152-2172`、`approveAction` `:2188-2210`、`rejectAction` `:2211-2223`、`closeApproval` `:2227-2237`、`promptCheckpoint` `:1678-1695`、新增 `autoResumeSession`）
- Modify: `aiagent/webui/src/main/resources/webui/chat.html`（确认弹框 `#checkpointModal` `:262-271`、审批弹框 `#approvalModal` `:163-179`）
- Test: `smoke/approval_phase2_check.py`（Task 13 写）

- [ ] **Step 1: 审批弹框陈旧提示（deferred + createdAt）**

`showApprovalModal(evt)` 内（`apDesc` 渲染行后）加：

```js
  // 断点态补批：标注发起时间（避免把几小时前的请求当成刚刚的操作确认）
  if (evt.deferred) {
    const ago = fmtAgo(evt.createdAt);
    const hint = document.createElement('div');
    hint.className = 'ap-stale';
    hint.textContent = '⏳ 该请求发起于' + ago + '（你不在线时转为了待批断点，批准后将继续执行）';
    $('#apDesc').after(hint);
  }
```

新增 `fmtAgo`（放 `fmtTs` 附近）：

```js
/** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前 */
function fmtAgo(ts) {
  const n = Number(ts) || 0;
  if (!n) return '刚才';
  const s = Math.max(0, Math.floor((Date.now() - n) / 1000));
  if (s < 60) return '刚刚';
  if (s < 3600) return Math.floor(s / 60) + ' 分钟前';
  if (s < 86400) return Math.floor(s / 3600) + ' 小时前';
  return Math.floor(s / 86400) + ' 天前';
}
```

样式（`style.css` 末尾）：`.ap-stale { color:#fbbf24; font-size:12px; margin:4px 0; }`

- [ ] **Step 2: 批准/拒绝后自动续跑（deferred）**

`approveAction` 成功分支（`:2205`）改为：

```js
    const r = await apiCommand('approve', payload);
    if (r.success) {
      closeApprovalModal();
      toast(r.message, 'ok');
      if (r.deferred && (r.sessionId || evt.sessionId)) {
        autoResumeSession(r.sessionId || evt.sessionId);
      }
    }
```

`rejectAction` 成功分支同样在 `toast` 后加：

```js
    if (r.deferred && (r.sessionId || evt.sessionId)) {
      autoResumeSession(r.sessionId || evt.sessionId);
    }
```

新增：

```js
/** 断点态审批决策后自动续跑：查该会话断点 → 流式 resume（与「▶ 恢复执行」同链路） */
async function autoResumeSession(sid) {
  try {
    const r = await apiCommand('resume', { sessionId: sid });
    if (!r.success) { toast('已处理审批；续跑失败：' + (r.error || '未找到断点'), 'err'); return; }
    const d = r.data || {};
    switchSessionView(d.sessionId || sid);
    appendMsg('user', d.userMessage || '（断点消息）');
    toast('正在继续执行…', 'info');
    await streamChat(d.userMessage || '', { sessionId: d.sessionId || sid, resume: true });
  } catch (e) { toast('续跑失败: ' + e.message, 'err'); }
}
```

- [ ] **Step 3: 断点弹框加「放弃该操作」**

`chat.html` `#checkpointModal` 的按钮行（`:262-271`）加：

```html
    <button id="cpAbandonBtn" class="no hidden" title="撤销该待批操作并丢弃断点，之后可正常对话">🗑 放弃该操作</button>
```

`app.js` `promptCheckpoint`（`:1678-1695`）：拿到 `d.state` 后：

```js
    // pending_approval 断点：显示「放弃该操作」（批准/拒绝走重放的审批弹框）
    const isPending = (d.state === 'pending_approval');
    $('#cpAbandonBtn').classList.toggle('hidden', !isPending);
```

绑定（`:1035-1039` 旁）：

```js
  $('#cpAbandonBtn').onclick = async () => {
    const sid = state.pendingAbandonSessionId;
    if (!sid) return;
    try {
      const r = await apiCommand('abandonApproval', { sessionId: sid });
      if (!r.success) { toast(r.error || r.message || '放弃失败', 'err'); return; }
      $('#checkpointModal').classList.add('hidden');
      toast(r.message || '已放弃该操作，可继续对话', 'ok');
    } catch (e) { toast('放弃失败: ' + e.message, 'err'); }
  };
```

`promptCheckpoint` 里把该会话 id 存起来（`state.pendingAbandonSessionId = d.sessionId || null;`，非 pending 时置 null）。

- [ ] **Step 4: 前端回归（阶段一脚本）+ 肉眼核对**

Run（先 `mvn process-resources` 让 target/classes 拿到最新静态资源，再跑阶段一前端脚本确认没弄坏）：

```bash
D:/maven/bin/mvn -f D:/tlobjectapp/tlobject/pom.xml process-resources -pl aiagent/webui
python smoke/perm_auto_check.py
```

Expected: 31/31（阶段一回归不破）；新增三条 UI 的完整断言脚本在 Task 13 写。

- [ ] **Step 5: Commit**

```bash
git add aiagent/webui/src/main/resources/webui/app.js aiagent/webui/src/main/resources/webui/chat.html aiagent/webui/src/main/resources/webui/style.css
git commit -m "审批授权 13j：webui 前端（陈旧提示 + 决策后自动续跑 + 放弃该操作）"
```

---

## Task 11: 控制台——deferred 提示

**Files:**
- Modify: `aiagent/common/.../TLChatConsole.java`（approve 结果渲染 `:1426-1428`）

- [ ] **Step 1: /approve 成功后提示 /resume**

`:1426` 的 `case "approve":` 改为：

```java
                case "approve": {
                    System.out.println("✓ " + message);
                    // 断点态审批：本次决策不会立即执行（原等待线程已释放），需 /resume 续跑
                    Object deferred = data instanceof Map ? ((Map<?, ?>) data).get("deferred") : null;
                    if (Boolean.TRUE.equals(deferred)) {
                        System.out.println("⏳ 该审批处于断点态：输入 /resume 继续执行本轮");
                    }
                    break;
                }
```

（`data` / `message` 在该 switch 的取值方式与 `case "auto"`/`"permissions"` 相同，实现时对照 `:1426-1445` 上下文。）

- [ ] **Step 2: 编译 + 手工冒烟**

Run: `D:/maven/bin/mvn -f D:/tlobjectapp/tlobject/pom.xml install -pl aiagent/common -am -DskipTests`
Expected: BUILD SUCCESS；控制台里 `/resume` 在有待批断点时能查出会话（findIncomplete 已覆盖，Task 6）

- [ ] **Step 3: Commit**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLChatConsole.java
git commit -m "审批授权 13k：控制台 deferred 审批提示 /resume"
```

---

## Task 12: 配置——超时语义收口（含"一直等"与文件工具超时）

**Files:**
- Modify: `demo/tlobject/.../conf/demo/aiagent/approvalGate_config.xml`（`:26`）
- Modify: `demo/tlobject/.../conf/demo/aiagent/fileAgent_config.xml`（`:47-57`）
- Modify: `demo/tlobject/.../conf/demo/aiagent/codingAgent_config.xml`（`:17-25`）
- Modify: `demo/tlobject/.../conf/demo/aiagent/toolExecutor_config.xml`（`:8-15` 注释）

- [ ] **Step 1: approvalGate 超时与 TTL**

`approvalGate_config.xml` `:26` 的注释与值替换为：

```xml
        <!-- 审批超时（毫秒）：0 = 一直等（默认，等待策略改造后语义）。
             有人在收（控制台在跑 / webui SSE 在线）→ 阻塞等，回来批了原线程继续；
             没人收 → 立即转断点，不占线程。断点态兜底看 approvalPendingTtlMs（不会无限期滞留）。 -->
        <approvalTimeoutMs value="0"/>

        <!-- 断点态审批 TTL（毫秒，默认 24h）：仅作用于转为断点的审批——
             用户长时间不回来 → EXPIRED + 消息箱告知；阻塞态不受它影响。0 = 不设 TTL。 -->
        <approvalPendingTtlMs value="86400000"/>
```

- [ ] **Step 2: file_operation 工具超时 360 → 0**

`fileAgent_config.xml` `:47-57`：**只改注释块与 `timeout` 属性**，`<skill name="fileOperationSkill" ... skillDescription="...长文本..."/>` 的属性行整体原样不动（长 skillDescription 不要动、不要重排）。注释块替换为：

```xml
        <!-- timeout=0：工具不限时。审批等待算在工具执行时间内，而等待策略改造后
             审批可无限期（approvalTimeoutMs=0，"一直等"）——工具级超时会把它打断成
             "审批被中断"（2026-10-14 弹框卡死修复的同一故障类，只是时限不同）。
             file_operation 是本地文件操作，无真实挂死风险；真正的挂死兜底由
             toolExecutor 的 staleStateTimeoutMs（跳过"正等审批"的执行）承担。 -->
```

属性行只把 `timeout="360"` 改成 `timeout="0"`（行内其余属性一字不动）。

`codingAgent_config.xml` `:17-25` 同样处理：注释块替换为：

```xml
        <!-- timeout=0：见 fileAgent_config 同名注释——审批可无限期（approvalTimeoutMs=0），
             工具级超时会中断审批等待成"审批被中断"；挂死兜底归 toolExecutor 的 staleStateTimeoutMs -->
```

属性行只把 `timeout="360"` 改成 `timeout="0"`（`allowedRootPath="."` 等其余属性不动）。

- [ ] **Step 3: toolExecutor 注释同步**

`toolExecutor_config.xml` `:8-15` 注释改为：

```xml
        <!-- ===== 工具执行超时控制 =====
             executionTimeoutMs: 单次工具执行超时（毫秒），0 = 一直等待。
             staleStateTimeoutMs: 残留状态清理阈值（毫秒），0 = 禁用。
                 ... 默认 600000（10分钟）：清理"孤儿执行状态"（调用线程异常消失的残留）。
                 注意：正等人工审批的执行不会被它清理（approvalWaiters 守卫，2026-10-16），
                 审批等待是设计语义内的合法长跑（approvalTimeoutMs=0 可无限）。
                 带审批的技能应配 timeout=0（不再用工具超时兜审批窗口）。 ... -->
```

- [ ] **Step 4: 编译 + 起真机确认不崩**

Run: `D:/maven/bin/mvn -f D:/tlobjectapp/tlobject/pom.xml install -pl aiagent/common,aiagent/webui,demo/tlobject -am -DskipTests`
Expected: BUILD SUCCESS；`cmd //c ".\aistart.bat"` 能正常起来（审批配置解析无报错）

- [ ] **Step 5: Commit**

```bash
git add demo/tlobject/src/main/resources/conf/demo/aiagent/approvalGate_config.xml demo/tlobject/src/main/resources/conf/demo/aiagent/fileAgent_config.xml demo/tlobject/src/main/resources/conf/demo/aiagent/codingAgent_config.xml demo/tlobject/src/main/resources/conf/demo/aiagent/toolExecutor_config.xml
git commit -m "审批授权 13l：超时语义收口（approvalTimeoutMs=0 一直等 + TTL 24h + file_operation timeout=0）"
```

---

## Task 13: 验收测试——aitest 汇总 + 前端 stub + 真机 E2E

**Files:**
- Test: `TLAgentTestModule.java`（场景 21-27 已随各任务加入，本任务跑全量）
- Create: `smoke/approval_phase2_check.py`
- Create: `smoke/approval_defer_e2e.py`

- [ ] **Step 1: aitest 全量**

Run: `cmd //c ".\run_aitest.bat"`
Expected: 全部 PASS（含 1-27），无 SKIP 增加（pass→skip 视为回归）

- [ ] **Step 2: 前端 stub 冒烟**

新建 `smoke/approval_phase2_check.py`（骨架复制 `smoke/perm_auto_check.py`：静态服务 + Playwright + `/api/*` 路由拦截）。核心接线（其余用例同一模式）：

```python
import json
from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:8899"

def run():
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page()
        calls = []                       # 记录前端发出的 /api/command
        decided = {"approve": {"success": True, "message": "已批准", "deferred": True, "sessionId": "s1"}}

        def on_command(route):
            body = json.loads(route.request.post_data or "{}")
            calls.append(body)
            action = body.get("action")
            if action == "approve":
                route.fulfill(status=200, content_type="application/json", body=json.dumps(decided["approve"]))
            elif action == "resume":
                route.fulfill(status=200, content_type="application/json",
                              body=json.dumps({"success": True, "data": {
                                  "sessionId": "s1", "userMessage": "删掉文件", "state": "pending_approval"}}))
            else:
                route.fulfill(status=200, content_type="application/json", body=json.dumps({"success": True}))

        page.route("**/api/command", on_command)
        page.route("**/api/**", lambda r: r.fulfill(status=404, body=""))
        page.goto(BASE + "/chat.html")
        page.evaluate("loginStub && loginStub()")   # 按 perm_auto_check.py 的既有登录桩方式进聊天页

        # 用例 1：deferred 重放事件 → 陈旧提示
        page.evaluate("""handleEventForTest({type:'approval', approvalId:'a1', toolName:'file_operation',
            deferred:true, createdAt: Date.now() - 3*3600*1000, sessionId:'s1',
            text:'x', description:'d', args:'{}'})""")
        page.wait_for_selector("#approvalModal .ap-stale")
        assert "3 小时前" in page.inner_text("#approvalModal .ap-stale")

        # 用例 3：批准 deferred → 触发 resume
        calls.clear()
        page.click("#apApproveBtn")
        page.wait_for_timeout(300)
        assert any(c.get("action") == "resume" and c["params"]["sessionId"] == "s1" for c in calls), calls

        # 用例 7：普通批准（deferred 缺省）→ 不触发 resume
        calls.clear()
        decided["approve"] = {"success": True, "message": "已批准"}
        page.evaluate("""handleEventForTest({type:'approval', approvalId:'a2', toolName:'file_operation',
            sessionId:'s1', text:'x', description:'d', args:'{}'})""")
        page.click("#apApproveBtn")
        page.wait_for_timeout(300)
        assert not any(c.get("action") == "resume" for c in calls), calls

        browser.close()

run()
```

用例清单（每条一个断言）：
1. 重放事件 `{type:'approval', deferred:true, createdAt: Date.now()-3*3600*1000}` → 弹框出现 `.ap-stale` 且文本含"3 小时前"
2. `deferred:false` 事件 → 无 `.ap-stale`
3. 批准响应 `{success:true, deferred:true, sessionId:'s1'}` → 触发 `POST /api/command {action:'resume', params:{sessionId:'s1'}}`
4. 拒绝响应同上 → 同样触发 resume
5. 断点弹框（`resume` 返回 `{state:'pending_approval', sessionId:'s1', userMessage:'x'}`）→ `#cpAbandonBtn` 可见；非 pending → 隐藏
6. 点「放弃该操作」→ 发出 `{action:'abandonApproval', params:{sessionId:'s1'}}` 且弹框关闭
7. 普通（非 deferred）批准 → **不**触发 resume

（`handleEventForTest` 不存在时按 `perm_auto_check.py` 的既有做法调用页面全局的 SSE 事件处理函数——实现时以该脚本为准。）

Run: `python smoke/approval_phase2_check.py`
Expected: 7/7 PASS

- [ ] **Step 3: 真机 E2E（真 LLM）**

新建 `smoke/approval_defer_e2e.py`（骨架复制 `smoke/approval_close_e2e.py`：真登录 + SSE + 双窗）：

流程（提示词必须写"**委托 fileAgent** 删除 …"——主 agent 无 file_operation 工具，直发会让 LLM 自己找替代路径）：
1. 登录 → 发"委托 fileAgent 删除 D:/tmp/e2e_defer.txt"（先手工建文件；路径取 `allowedRootPath` 内的相对路径更稳）→ 审批弹框出现
2. **不决策**，调 logout（确定性 defer 路径）→ 服务器侧审批转断点：日志 `Approval deferred: id=...`，该会话落 `pending_approval` 断点
3. 重登录 → 审批弹框自动重放（含"发起于 X 前"）+ 消息箱未读角标 + 断点提示
4. 点「✓ 批准」→ 弹框关闭 → **自动续跑**：对话流里出现工具卡片与后续回答
5. 断言：文件已被删除（真执行）；审批弹框不再出现（免复批）
6. 再发一条普通消息 → 正常对话（拦截已解除）

变体（可选，验 §5.2 自愈路径）：步骤 2 改为"直接断开 SSE（关标签页）不 logout"——审批保持在阻塞态；重登录后重放弹框 → 批准 → 原阻塞线程被唤醒继续执行（日志无 `Approval deferred`，工具照常执行）。

Run: `python smoke/approval_defer_e2e.py`
Expected: 全过

- [ ] **Step 4: Commit**

```bash
git add smoke/approval_phase2_check.py smoke/approval_defer_e2e.py
git commit -m "审批授权 13m：前端 stub 冒烟 7/7 + 真机 defer→重登→批准→续跑 E2E"
```

---

## Task 14: 文档 + 全量验收

**Files:**
- Modify: `CLAUDE.md`（HITL 审批段）

- [ ] **Step 1: CLAUDE.md HITL 段补等待策略**

HITL 段落追加要点（按实际实现措辞）：

```markdown
- **等待策略（阶段二）**：`approvalTimeoutMs=0` = 一直等（默认）——有人收（控制台在跑/webui SSE 在线）阻塞等，没人收立即转断点（`pending_approval` 轮 + 消息箱告知）；退出登录 = defer 转断点（非拒绝）；断点审批 TTL `approvalPendingTtlMs`（默认 24h）到期失效；批准/拒绝 deferred 审批 → 前端自动续跑，控制台提示 `/resume`；断点恢复的已批准执行**免复批**（`AI_P_APPROVAL_DECISION` 透传）；进程重启后审批内存态丢失 → resume 如实报"审批已失效"不假装续跑
- 带审批的技能 `timeout` 必须配 0（审批等待算在工具执行时间内，工具超时会把"一直等"打断成"审批被中断"）；执行器 `staleStateTimeoutMs` 不清理正等审批的执行（approvalWaiters 守卫）
```

- [ ] **Step 2: 全量回归 + 真机**

Run:
1. `cmd //c ".\run_aitest.bat"` → 全绿
2. `python smoke/approval_phase2_check.py` → 7/7
3. `python smoke/approval_defer_e2e.py` → 全过
4. `python smoke/perm_auto_check.py`（阶段一前端回归）→ 31/31

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "审批授权 13n：文档（等待策略语义）+ 全量验收"
```

- [ ] **Step 4: 双推**（沿用既定机制：dev 直推 gitee；publish 用 git apply 补丁，剔除 smoke/ 与脱敏行）

```bash
git push origin master   # gitee
# publish：生成不含 smoke/ 的补丁 → 目标仓库 git apply → 核脱敏行（apiKey 占位/MCP 路径 "."/allowedScriptDir "."）→ push
```

---

## 验收口径（对照 spec §5）

| spec 条目 | 落点 | 验证 |
|---|---|---|
| §5.1 投递判据 | Task 2（ConsoleReviewer） | 场景 22 + 真机（关浏览器后审批转断点） |
| §5.2 无限等待 | Task 2（waitForDecision + 默认 0） | 场景 21 |
| §5.3 不可投递→断点 | Task 2（reviewer 返回 pending → 现有断点链路） | 场景 22 + E2E |
| §5.4 退出转断点 | Task 3（APPROVAL_DEFER）+ Task 9（doLogout） | 场景 23 + E2E |
| §5.5 决策写回与续跑 | Task 4（QUERY 映射）+ Task 7（agent 写回）+ Task 8/9/10（续跑触发） | 场景 25/26 + E2E |
| §5.6 断点 TTL | Task 3（armPendingTtl） | 场景 24 |
| §5.7 会话并发规则 | Task 6（discard）+ Task 8（abandon）+ Task 9（拦截）+ Task 10（放弃按钮） | 场景 27 + stub + E2E |
| §5.8 进程重启语义 | Task 4（lost 映射）+ Task 7（lost 分支） | 场景 26B |
| §5.9 陈旧补批提示 | Task 4（createdAt）+ Task 9（重放）+ Task 10（fmtAgo） | stub 用例 1 |
| 硬化① fail-closed | Task 5 | 评审 + 生产配置下有效（真机起服不崩即基线） |

## 滚动修复清单（评审遗留，随对应任务带走）

- [ ] **T3 带**：`scheduleTimeout` 超时文案改用 `fmtSeconds(timeoutMs)`（现仍是 `timeoutMs/1000`，非整秒配置会与 latch 路径文案不一致）；`TLApprovalModule.destroy()` 注释更新（默认 0 后该循环是唯一释放路径）；场景 21 的 `setBlockMode(true)` 移入 try/finally 内（两条 early-return 会漏复位）；场景 21 失败路径清理阻塞 worker（finally 里对已登记的 id 发 APPROVAL_REJECT 再 join）；场景 22 探针加 `probe.setTimeoutMs(1000)` 界定失败上界
- [ ] **T14 带（T8b 评审残余，文档级）**：DB 后端（dbSessionManager）的黏住语义只编译验证、无测试覆盖——真机 E2E 走 DB 时顺带观察；`doDiscardPendingRound` 的"写后验证"在同一会话存在两条开态待批时会误报"未落盘"（当前流程单待批，无触发）；`doAbandonApproval` 在 `discardPendingRound` 返回 null / sessionId 为空时 `checkpointDiscarded` 恒 true（无失败证据，可接受）；流式 pending 分支跳过 `maybeGenerateSessionTitle`（autoSessionTitle 两配置均 false，无影响）；被回滚的兄弟工具结果只在日志层面丢失（SSE 卡片已推、side-effect 已发生）；pending 期间 `notifyChatError` 仍会写 chatFinished（低概率）；**子 agent 委托路径的 pending 不上浮**（fileAgent/codeAgent enableCheckpoint=false + executeAsTool 只转发 output）→ 委托类危险工具的"离线转断点"不成立，现阶段只在 master 自有 gated 工具上生效——T13 E2E 必须用 master 级 gated 工具（word 覆盖类动作）；如需覆盖委托路径要另立项（pending 上浮 + bypass 透传子 agent）
- [ ] **T13 带（T7 评审 Minor 批）**：`queryApproval` 门禁模块名硬编码（`"approvalGate"`）——配置改名会静默降级 pending，加注释/后续可挂 agent 参数；resume 返回契约只有自然语言（"等待"/lost/正常难区分）——T9/T10 目前只做渲染不需要，若后续要据此分支再加结构化字段；流式/非流式 pending-approval 恢复块重复三份（drift 已被 T8 修过一次）——收敛为共享 helper 留作后续
- [ ] **T13 带（T4 评审 Minor 批）**：`TLApprovalModule` 的 `"sessionId"` 字面量 → `AI_P_SESSIONID`（:604/:645）；`AI_P_APPROVAL_STATE="lost"` 第五值 —— 扩展 `TLAiAgentParamString:459` 该常量的 javadoc 明示 lost（或查询侧只给 decision，不给 state）；场景 23 在 ABANDON 前断言 r2 为 pending（诊断精度）；场景 23 的 defer-then-approve 处补断言 `AI_P_APPROVAL_DEFERRED`（approve 响应字段覆盖）；补一条未知 id 的 `APPROVAL_QUERY` → not-found→lost 断言；`TLApprovalRequest.deferredAt` javadoc 改指 `createdAt`（陈旧提示实际用 createdAt）
- [ ] **T9 带**：`TLWebChatModule` 两处注释（`:135`/`:1008`）"回退到控制台直接打印"已不成立 → 改为"发布方判定无人接收 → 转断点"
- [ ] **T11 带**：`TLChatConsole` 两处注释（`:167`/`:170`）同样更新；`ConsoleReviewer.renderPromptText` 可见性收回 private 或仅供场景使用；`ConsoleReviewer` 类 javadoc 补"无订阅者 → pending 转断点"一句

## 已知边界（本阶段不做）

- **硬化②** round 授权行回收/按会话配额（现 200 FIFO 跨会话）——方向安全（只会重新要审批），留待观察
- **硬化③** 热重载与授权契约（被删规则留死行、规则加回复活授权）——独立小课题
- **阶段一残留**：`checkSessionOwnership` 的 uid 前缀碰撞 / 非 webchat_ 自定义会话名 / doApprove 无归属校验——根治要查 webui sessionOwner 注册表，未纳入本阶段
- 控制台 revoke「含冒号规则名」提示不可用（潜伏，当前配置无此形态）
- task_* 执行会话的断点审批在 webui 的归属/续跑（`sessionBelongsTo` 已覆盖展示，续跑未接线）
- 「忽略，开始新对话」不丢弃普通 checkpoint（既有行为，未纳入本次）
