# vision 多模态（Plan 2/2 · 入口与技能接线）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Plan 1 的框架能力接到用户真正能用的地方——控制台贴图、webui 上传进对话与历史回放、桌面/浏览器技能表态。

**Architecture:** 框架侧已就绪（`attachments`/`images` 双字段、`attachmentStore` 登记、清单、`view_image`、工具产图注入）。本计划只做**接线**：让三个入口把附件交给 agent，让两个技能在输出里表态。

**Spec:** `docs/superpowers/specs/2026-10-05-vision-image-input-design.md` · **Plan 1:** `docs/superpowers/plans/2026-10-05-vision-core.md`

**前置事实（Plan 1 已落地，勿重复实现）：**
- `TLConversationHistory` 有 `attachments` / `images`；`copy()`；gson 往返已测
- `attachmentStore` 模块：`attachRegister`（**路径必须在 `data/{uid}/` 内**）、`attachStoreBase64`、静态 `readBytes`
- agent：`AI_P_ATTACHMENTS` 四形态归一化（path / base64 / url / fileId）、清单、`view_image` 技能、工具产图按 `image_for_model` 表态注入
- `TLAgentService` 已在 **chat 与 chatStream 两条路径**透传 `AI_P_ATTACHMENTS`

**⚠ 环境铁律（Plan 1 血泪，每条都真实踩过）**
```bash
cd /d/tlobjectapp/tlobject
CP=$(sed -n '2p' aistart.bat | sed 's/.*-classpath "//; s/" .*//')
```
- `mvn` 不在 PATH → `/d/maven/bin/mvn`；`-pl` 必须带 `aiagent/common`
- 手工 javac 的临时类：`smoke/classes` 必须放 classpath **最后**，否则工厂去 `smoke/classes/conf/` 找配置 → StackOverflow
- 手工 javac 时工厂要传**绝对 configDir** + `factory.boot()`：相对路径会被再拼一次 → skills 全空 → 模型"幻觉"调工具（Plan 1 实测）
- `factory.shutdown(int)` 内部 `System.exit`——汇总行必须打在它之前
- `factory.putMsg(factory, msg)` 未知 action 返回 **null** → 随后 NPE → 被 finally 的 shutdown 变成假"静默退出"；发消息一律用模块名 `factory.putMsg("模块名", msg)`
- 发消息给**不存在的模块**会 `shutdown(-1)` 杀掉整个应用（已有 `IGNOREMODULEISNULL` 逃生舱）

---

## Task 1: 控制台 `/img` `/file`（本地贴图）

**为什么先做这个：** 不依赖 webui，且**是后续所有真 LLM 验证最快的手段**——控制台一条命令就能把图送进对话。

**Files:**
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLChatConsole.java`
- Test: 手动（管道 stdin 驱动控制台）

- [ ] **Step 1: 加待发附件字段**

在 `TLChatConsole` 字段区（`pendingCheckpointUserMessage` 附近，约 48-51 行）加：
```java
    /** 待发附件绝对路径（/img 累积，随下一条消息发出后自动清空） */
    private final List<String> pendingAttachments = new ArrayList<>();
```
（`java.util.*` 已导入；若没有 `List`/`ArrayList` 的导入按文件现有风格补齐。）

- [ ] **Step 2: `parseCommand` 加两个命令**

在 `case "param":` 之后插入：
```java
            case "img":
            case "file": {
                String arg = parts.length > 1
                        ? input.substring(input.indexOf(' ') + 1).trim().replace("\"", "")
                        : "";
                if (arg.isEmpty() || arg.equalsIgnoreCase("list")) {
                    if (pendingAttachments.isEmpty()) {
                        System.out.println("用法: /img <路径>（可多次累积，随下一条消息发出后自动清空）");
                    } else {
                        System.out.println("已登记附件 " + pendingAttachments.size() + " 个:");
                        for (String p : pendingAttachments) System.out.println("  " + p);
                    }
                    return null;
                }
                java.io.File src = new java.io.File(arg);
                if (!src.isFile()) {
                    System.out.println("✗ 文件不存在: " + src.getAbsolutePath());
                    return null;
                }
                try {
                    // ⚠ 必须拷进 data/{userId}/uploads/：attachmentStore.register 只接受
                    //    本用户目录内的路径（跨用户越权防护），控制台的本机任意路径要先导入
                    java.io.File dir = new java.io.File("data/" + userId + "/uploads");
                    dir.mkdirs();
                    java.io.File dst = new java.io.File(dir,
                            System.currentTimeMillis() + "_" + src.getName());
                    java.nio.file.Files.copy(src.toPath(), dst.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    pendingAttachments.add(dst.getAbsolutePath());
                    System.out.println("✓ 已登记附件 (" + pendingAttachments.size() + "): "
                            + src.getName());
                } catch (Exception e) {
                    System.out.println("✗ 附件导入失败: " + e.getMessage());
                }
                return null;
            }
```

- [ ] **Step 3: `submitChat` 带上附件**

在 `submitChat` 构造完 `msg` 之后（`if (reasoningMode != null)` 那行之后）加：
```java
        if (!pendingAttachments.isEmpty()) {
            List<Object> atts = new ArrayList<>();
            for (String p : pendingAttachments) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", p);
                m.put("name", new java.io.File(p).getName());
                m.put("origin", "user");
                atts.add(m);
            }
            msg.setParam(AI_P_ATTACHMENTS, atts);
            msg.setSystemParam(AI_P_ATTACHMENTS, atts);   // 框架参数区也放一份：上游透传链看 systemArgs
            pendingAttachments.clear();                    // 发完自动清空
        }
```
⚠ `submitChat` 的两个分支（stream / 非 stream）都用同一个 `msg` 变量——确认这行在两个分支**之后**，别只加在其中一个里面。

- [ ] **Step 4: 补 Tab 补全与帮助**

`COMMAND_REGISTRY`/帮助文本里加 `/img` 一行（照 `populateCommandCache` 里既有条目的写法）。控制台本地命令是硬编码在那里的，找到 `/param` 或 `/clear` 的条目照抄格式。

- [ ] **Step 5: 编译**

```bash
/d/maven/bin/mvn -q -f pom.xml -pl aiagent/common,demo/tlobject compile
```
Expected: BUILD SUCCESS

- [ ] **Step 6: 端到端验证（真 LLM，1 次调用）**

先在 `data/console_user/uploads/` 下造一张带字的图（用 python + PIL，字写 `CONSOLE 5150`），然后：
```bash
cd /d/tlobjectapp/tlobject
CP=$(sed -n '2p' aistart.bat | sed 's/.*-classpath "//; s/" .*//')
printf '/img data/console_user/uploads/probe.png\n附件里那张图写的什么字？只回答字。\n/exit\n' \
  | (cd demo/tlobject && java -Dfile.encoding=UTF-8 -cp "$CP" cn.tianlong.java.demo.aiagent.AIStart 2>&1 | tail -30)
```
Expected: 模型答出 `5150`；且日志里能看到 `[附件]` 清单。**若答不出**，读 trace（`demo/tlobject/data/<uid>/traces/...`）确认：清单有没有进请求、模型有没有调 `view_image`。如实报告。

- [ ] **Step 7: 提交**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLChatConsole.java
git commit -m "vision：控制台 /img /file 贴图（导入 data/{uid}/uploads 后随下一条消息发出）"
```

---

## Task 2: 桌面技能表态（**唯一必改的技能**）

**为什么：** 桌面技能的 `screenshot` 是模型**主动调用**的动作，图就是它的返回值——不投喂等于回给模型一串它读不懂的 base64。桌面**没有 DOM 文本兜底，截图是唯一信息源**（12 个动作里只有 `screenshot` 返回图）。

**Files:**
- Modify: `aiagent/desktop-java/src/main/java/cn/tianlong/tlobject/aiagent/desktop/JavaDesktopEngine.java:177-187`
- Modify: `demo/tlobject/src/main/resources/conf/demo/aiagent/skills/desktop/scripts/desktop_agent.py:38-46`
- Test: 手动 / 真 LLM

- [ ] **Step 1: Java 版加表态**

`JavaDesktopEngine.screenshot(Map)` 里，`out.put("screenshot_base64", toPngBase64(img));` 之后加：
```java
        out.put("image_for_model", true);   // 技能表态：这张图给模型看（agent 侧据此注入）
```

- [ ] **Step 2: Python 版加表态**

`desktop_agent.py` 的 `screenshot(region=None, **kwargs)` 返回字典加 `"image_for_model": True`：
```python
def screenshot(region=None, **kwargs):
    b64 = _screenshot(region)
    w, h = pyautogui.size()
    return {"ok": True, "screenshot_base64": b64, "width": w, "height": h,
            "image_for_model": True}
```
⚠ **只改 `screenshot`，不要改 `click`**——Python 版 `click` 顺手也带截图（`desktop_agent.py:43-46`），那属于"顺手附带"；桌面 Python 版的 `click` 与 Java 版行为不同，本轮不改它，保持现状（不表态＝不投喂）。要不要一并表态由后续决定。

- [ ] **Step 3: 编译 + JSON 形态自检**

```bash
/d/maven/bin/mvn -q -f pom.xml -pl aiagent/desktop-java,demo/tlobject compile
```
确保 skill 输出的 Gson 仍是 `disableHtmlEscaping`（`TLDesktopJavaSkill.java:172-173` 已有，勿动），否则 base64 的 `=` 会被转义、前端截图正则失配。

- [ ] **Step 4: 验证——投喂链路真的生效**

写一个临时冒烟类（`smoke/DesktopFeedProbe.java`，**跑完删**），用 Mock/真实 provider 均可，断言：给定一段含 `"image_for_model":true` 与 `screenshot_base64` 的工具输出，agent 的 history 里多出一条带 `images` 的 user 消息。可直接反射调 `TLAiAgent.extractDeclaredImages(output, userId, history)` —— 与 Plan 1 冒烟测试 6.2 同款手法（那段测试已删，可 `git show 0833a4d^:smoke/VisionSmokeTest.java` 取回参考）。

Expected: `image_for_model:true` → 返回 1 个 ref；去掉该字段 → 返回 `null`。

- [ ] **Step 5: 提交**

```bash
git add aiagent/desktop-java demo/tlobject/src/main/resources/conf/demo/aiagent/skills/desktop/scripts/desktop_agent.py
git commit -m "vision：桌面技能 screenshot 表态 image_for_model（图是唯一信息源，必须给模型）"
```

---

## Task 3: 浏览器技能表态

**决策：navigate / click / type / scroll 顺带的截图**——由**浏览器技能自己定**。本轮定：**表态给**。理由是 computer-use 需要看到操作结果，而保留策略（`maxImagesInContext=1`）已经把成本封顶在每轮一张；`text`（DOM 文本）仍照常返回，图像是叠加信息。

**Files:**
- Modify: `aiagent/browser-java/src/main/java/cn/tianlong/tlobject/aiagent/browser/JavaBrowserEngine.java`（`navigate` / `click` / `typeText` / `screenshot` / `scroll` 五处）
- Modify: `demo/tlobject/src/main/resources/conf/demo/aiagent/skills/browser/scripts/browser_agent.py`（同五个 action）

- [ ] **Step 1: Java 版五处加表态**

每个 `m.put("screenshot_base64", shot(...));` 之后加：
```java
        m.put("image_for_model", true);   // 技能表态：图给模型看（agent 侧据此注入）
```
⚠ `extract` 不产图，不要动。

- [ ] **Step 2: Python 版五处加表态**

`browser_agent.py` 的 `navigate` / `click` / `type_text` / `screenshot` / `scroll` 返回字典各加 `"image_for_model": True`（`extract` 不动）。

⚠ **`browser_agent.py --serve` 模式的 stdout 不干净**（有多处 `print(...)` 诊断，如 `cdp: adopted tab …`）。Plan 1 的 `extractDeclaredImages` 有护栏「输出必须以 `{` 开头」——若 serve 模式下带前导日志行，声明会被静默忽略。**本轮不改护栏**，但在浏览器 Python 版的验证里明确记录：走 `--serve` 时首字符是否是 `{`；若不是，记为遗留项（Plan 3 或单独修）。

- [ ] **Step 3: 编译**

```bash
/d/maven/bin/mvn -q -f pom.xml -pl aiagent/browser-java,demo/tlobject compile
```

- [ ] **Step 4: 验证**

复用 Task 2 Step 4 的探针思路，断言浏览器技能输出同样能被提取。若方便，跑一次真 LLM 的 browser 任务（`/test` 之外的单次交互），在 trace 里确认：注入的 user 消息带图、模型引用画面而非占位符。

- [ ] **Step 5: 提交**

```bash
git add aiagent/browser-java demo/tlobject/src/main/resources/conf/demo/aiagent/skills/browser/scripts/browser_agent.py
git commit -m "vision：浏览器技能截图表态 image_for_model（computer-use 要看操作结果，K=1 封顶成本）"
```

---

## Task 4: webui —— 上传的文件真正进对话

**修 C6 缺口：** `state.uploads` 只是画了个 chip 条，文件从未进过对话请求。

**Files:**
- Modify: `aiagent/webui/src/main/resources/webui/app.js`（`uploadFiles` / `plainChat` / `streamChat`）
- Modify: `aiagent/webui/src/main/java/cn/tianlong/tlobject/aiagent/webui/TLWebChatServlet.java`（`handleChat` / `handleChatStream`）
- Modify: `aiagent/webui/src/main/java/cn/tianlong/tlobject/aiagent/webui/TLWebChatModule.java`（`chat` / `beginChatStream`）

- [ ] **Step 1: 前端记住文件真名**

`app.js` 的 `state.uploads` 目前只存 `{name}`（`uploadFiles` 里 `saved[i] != null` 时 push）。改成存服务端返回的**保存名**：
```js
  const saved = data.filenames || [];
  okFiles.forEach((f, i) => { if (saved[i] != null) state.uploads.push({ name: f.name, saved: saved[i] }); });
```
`renderUploadBar` 不变（仍显示 `u.name`）。发消息后清空——在 `sendMessage` 成功路径里（`plainChat`/`streamChat` 返回之后）加：
```js
  state.uploads = []; renderUploadBar();
```

- [ ] **Step 2: 请求体带上附件**

`plainChat` 与 `streamChat` 的请求体各加一项（两处都要改，`streamChat` 在 `JSON.stringify({...})` 里）：
```js
        attachments: state.uploads.map(u => ({ path: 'uploads/' + u.saved, name: u.name, origin: 'user' })),
```
⚠ `uploads/` 是相对用户目录的写法——后端按下述规则解析。

- [ ] **Step 3: servlet 读出附件并透传**

`TLWebChatServlet.handleChat` 与 `handleChatStream` 各加：
```java
        java.util.List<Object> attachments = body != null && body.get("attachments") instanceof java.util.List
                ? (java.util.List<Object>) body.get("attachments") : null;
```
并把它传进模块（两个方法各改一行调用）：
```java
        Map<String, Object> r = mod.chat(userId, sessionId, message, reasoningMode, resume, attachments);
        // 以及 beginChatStream(..., attachments, channel)
```

- [ ] **Step 4: 模块侧归一化并透传**

`TLWebChatModule.chat(...)` 与 `beginChatStream(...)` 各加一个 `List<Object> attachments` 参数；在构造发往 `serviceModule` 的 msg 时（`beginChatStream` 里是 `createMsg().setAction("chatStream")...`）加：
```java
        if (attachments != null && !attachments.isEmpty()) {
            // 相对路径补成 data/{userId}/ 下的路径：agent 侧 register 只接受本用户目录内的路径
            java.util.List<Object> norm = new java.util.ArrayList<>();
            for (Object o : attachments) {
                if (!(o instanceof java.util.Map)) continue;
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>((java.util.Map<String, Object>) o);
                Object p = m.get("path");
                if (p != null && !new java.io.File(String.valueOf(p)).isAbsolute()) {
                    // ⚠ 必须给绝对路径：register 会把相对路径再按 userRoot(uid) 解析一次，
                    //   传 "data/{uid}/x" 会变成 data/{uid}/data/{uid}/x → 文件不存在（实测）
                    m.put("path", new java.io.File("data/" + userId + "/" + p).getAbsolutePath());
                }
                m.put("origin", "user");
                norm.add(m);
            }
            msg.setParam(AI_P_ATTACHMENTS, norm);
        }
```
⚠ 两个方法都要加；`chat` 是同步返回 Map，`beginChatStream` 是流式。找到各自构造 msg 的位置（`grep -n "AI_P_USERMESSAGE" aiagent/webui/.../TLWebChatModule.java` 会返回两处）。

⚠⚠ **只改 servlet 不生效**（2026-10-05 实测）：本配置里 `/api/*` 挂在 `TLServletDispatch` + `urlMap_config.xml` 上，
`TLWebChatServlet` 只挂 `/` 与 `/webui/*`。所以 `handleChat`/`handleChatStream` 那两处虽然也要改（保留正确性），
但**真正生效的是 `TLWebChatModule.doChat` / `doChatStream`** 这两个 action（urlMap 把它们路由过来）。
判据：`/api/login` 返回的是框架 `jsonDataOutInterface` 的 `text/html`，不是 servlet 的 `application/json`。
建议抽一个 `normalizeAttachments(...)` 私有方法供 `chat` 与 `beginChatStream` 共用，别复制两遍 15 行。

⚠ 构建：`-pl aiagent/webui` 会用到 `D:epository` 里的**旧 `tlobject-aiagent-common` jar**（没有 `AI_P_ATTACHMENTS`）——
加 `-am` 让 reactor 用当前源码，或先 `mvn install -pl aiagent/common`。

- [ ] **Step 5: 编译**

```bash
/d/maven/bin/mvn -q -f pom.xml -pl aiagent/webui,demo/tlobject compile
```

- [ ] **Step 6: 用 curl 做端到端（不用开浏览器，可脚本化）**

启动 webui 应用（后台，保持 stdin 打开），然后：
```bash
# 1) 登录拿 cookie
curl -s -c /tmp/ck.txt -X POST http://localhost:8080/api/login \
  -H 'Content-Type: application/json' -d '{"userId":"admin","password":"123456"}'
# 2) 上传图（multipart，字段名 file_0）
curl -s -b /tmp/ck.txt -X POST http://localhost:8080/api/upload -F 'file_0=@/tmp/probe.png'
# 3) 带附件提问
curl -s -b /tmp/ck.txt -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"附件里那张图写的什么字？只回答字。","sessionId":"vision_web_probe","attachments":[{"path":"uploads/<上一步返回的保存名>","name":"probe.png","origin":"user"}]}'
```
Expected: 回答含图上的字（造图时写 `WEB 8241`）。端口/登录口令以 `moduleFactory_chat_web_config.xml` 里的实际配置为准（`passwords="admin:123456;tianlong:123456"`）。

**若答不出**：先确认 login 成功（401 就说明口令或字段名不对），再读 trace 确认清单是否进请求、模型是否调 `view_image`。如实报告。

- [ ] **Step 7: 提交**

```bash
git add aiagent/webui
git commit -m "vision：webui 上传文件进对话（前端带出保存名 + servlet 透传 + 模块补全 data/{uid} 路径）"
```

---

## Task 5: webui 历史回放 + `/api/image` 出图

**两个问题：** ① `toJsonable` 会把 `attachments`/`images` 丢成 `String.valueOf`（`cn.tianlong...@1a2b`）；② 前端要显示图，得有个能取到图片字节的接口。

**Files:**
- Modify: `aiagent/webui/src/main/java/cn/tianlong/tlobject/aiagent/webui/TLWebChatModule.java`（`toJsonable` + 新增 `file` action）
- Modify: `demo/tlobject/src/main/resources/conf/demo/aiagent/urlMap_config.xml`（加 `/image` 路由 ← **按 Step 3 的实际命名**）
- Modify: `aiagent/webui/src/main/resources/webui/app.js`（历史渲染）

- [ ] **Step 1: `toJsonable` 序列化新字段**

`TLWebChatModule.toJsonable` 的 `TLConversationHistory` 分支（约 1075-1081 行）改为：
```java
        if (o instanceof TLConversationHistory) {
            TLConversationHistory ch = (TLConversationHistory) o;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", String.valueOf(ch.getRole()).toLowerCase());
            m.put("content", ch.getContent());
            if (ch.hasAttachments()) m.put("attachments", toJsonable(ch.getAttachments()));
            if (ch.hasImages()) m.put("images", toJsonable(ch.getImages()));
            return m;
        }
```
再给 `TLAttachmentRef` 加一个分支（放在 `TLConversationHistory` 分支之前或之后）：
```java
        if (o instanceof TLAttachmentRef) {
            TLAttachmentRef r = (TLAttachmentRef) o;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", String.valueOf(r.getKind()));
            m.put("name", r.getName());
            m.put("mime", r.getMime());
            if (r.getPath() != null) m.put("url", "/api/image?path=" + java.net.URLEncoder.encode(r.getPath(), "UTF-8"));
            else if (r.getUrl() != null) m.put("url", r.getUrl());
            m.put("size", r.getSize());
            m.put("meta", r.getMeta());
            return m;
        }
```
⚠ `URLEncoder.encode` 会抛 `UnsupportedEncodingException`——按文件现有风格处理（方法签名可能不便加 throws，那就 try/catch 回落 `r.getPath()`）。另外 `URLEncoder` 把空格编成 `+`，后端解析时要注意；若嫌麻烦可改成只对 `\` 与 `&` 做替换。

- [ ] **Step 2: 加出图 action**

`TLWebChatModule.checkMsgAction` 里加 `case "image": return doImage(msg);`，并实现：
```java
    /** 附件出图：路径必须在当前用户的 data/{userId}/ 内；走 putStream 二进制输出（不走 outJson） */
    private TLMsg doImage(TLMsg msg) {
        String userId = currentUserId();
        if (userId == null) return notLogin();
        String path = msg.getStringParam("path", "");
        if (path.isEmpty()) return null;
        try {
            java.io.File f = new java.io.File(path);
            if (!f.isAbsolute()) f = new java.io.File("data/" + userId + "/" + path);
            String root = new java.io.File("data/" + userId).getCanonicalPath() + java.io.File.separator;
            if (!(f.getCanonicalPath().startsWith(root))) return null;   // 越权：直接断连不输出
            if (!f.isFile()) return null;
            try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                putStream(f.getName(), in);        // 继承自 TLWServModule，按扩展名自动设 image/* content-type
            }
        } catch (Exception e) {
            putLog("image serve failed: " + e, cn.tianlong.tlobject.modules.LogLevel.WARN);
        }
        return null;   // 自己写输出，不走 outJson（先例：doChatStream）
    }
```
⚠ 不要调 `outJson`（它会 `setThreadData("client","jsonClient")` 抢输出）。参考 `TLWServModule.putStream`（`servletutils/.../TLWServModule.java:210`）与 `putFile`（`:198`）。
⚠ `path` 参数从 GET query 来：确认 `jsonInInterface` 对无 body 的 GET 是否会把 query 填进 msg 参数（`/events` 是 GET+无 clientVars 的先例）。**若 GET 拿不到 path，就把前端改成 POST**，并在报告里写明。

- [ ] **Step 3: 加路由**

`urlMap_config.xml` 里仿 `/upload` 加（**不配 clientType**，理由同上：不能让它走 JSON 输出）：
```xml
        <!-- 附件出图：二进制输出，模块自行 putStream；不配 clientType（输出不走 jsonClient） -->
        <url  value="/image">
            <msg action="image" destination="webui"/>
        </url>
```
⚠ 顺带确认 `TLWebChatServlet.doPost` 的 switch 是否需要加 `/api/image`——**不需要**（它是 GET，走 servlet 的 `doGet` 或框架 urlMap 之一；按 Step 2 的实际结果定，并在报告里写清走的是哪条路）。

- [ ] **Step 4: 前端渲染附件缩略图**

`app.js` 里用户消息的渲染分支加：若消息带 `attachments`，在气泡里渲染缩略图，复用现有截图样式（`renderToolResult` 里的 `<img class="browser-shot">` + 双击 lightbox）：
```js
  if (attachments && attachments.length) {
    attachments.forEach(a => {
      if (!a.url) return;
      const img = document.createElement('img');
      img.className = 'browser-shot';
      img.src = a.url;
      el.appendChild(img);
    });
  }
```
历史回放路径（`if (h.role === 'user')`）也要带上同一段——找到现有 `h.role === 'tool'` 渲染截图的那行（约 850 行）作参考。

- [ ] **Step 5: 编译 + curl 验证**

```bash
/d/maven/bin/mvn -q -f pom.xml -pl aiagent/webui,demo/tlobject compile
```
重启应用，取一张已登记附件的 `url`，验证：
```bash
curl -s -o /tmp/got.png -w '%{http_code} %{content_type} %{size_download}\n' \
  -b /tmp/ck.txt "http://localhost:8080/api/image?path=<urlencoded 绝对路径>"
```
Expected: `200 image/png <字节数>`，且 `/tmp/got.png` 与源图字节一致（`cmp` 或 `md5sum`）。
再验越权：把 path 指到别的用户目录 → Expected: 非 200 或空响应。

- [ ] **Step 6: 提交**

```bash
git add aiagent/webui demo/tlobject/src/main/resources/conf/demo/aiagent/urlMap_config.xml
git commit -m "vision：webui 历史回放附件（toJsonable 序列化）+ /api/image 出图（per-user 越权校验）"
```

---

## Task 6: 收尾——文档、回归、清场

- [ ] **Step 1: 全面回归**

```bash
cd /d/tlobjectapp/tlobject
CP=$(sed -n '2p' aistart.bat | sed 's/.*-classpath "//; s/" .*//')
/d/maven/bin/mvn -q -f pom.xml -pl aiagent/common,aiagent/provider-openai,aiagent/provider-claude,aiagent/skill-builtin,aiagent/browser-java,aiagent/desktop-java,aiagent/webui,demo/tlobject compile
(cd demo/tlobject && printf '/test\n/exit\n' | java -Dfile.encoding=UTF-8 -cp "$CP" cn.tianlong.java.demo.aiagent.AIStart 2>&1 | tail -8)
```
Expected: `通过=11, 失败=0`。

- [ ] **Step 2: 三个入口各跑一次真实验证**（控制台 `/img`、webui curl、浏览器 agent 一轮），把结论写进 spec：给 `2026-10-05-vision-image-input-design.md` 的 §11 验收表加一列"状态"，逐条标注 已验/未验，未验的写清原因。

- [ ] **Step 3: 清理临时物**

删掉本计划产生的探针类/脚本与测试数据（`smoke/`、`data/*/uploads` 里的探针图、`/tmp` 的 curl 产物）。**逐项确认是本次生成的再删**——Plan 1 的清理教训：`demo/tlobject/data/default/` 里有 10/4 的真实控制台会话，**不是**测试产物。

- [ ] **Step 4: 更新 CLAUDE.md**

在 Plan 1 加的那条多模态要点后面补一句入口状态（控制台 `/img`、webui 上传进对话、桌面/浏览器技能已表态）。

- [ ] **Step 5: 提交**

```bash
git add -A CLAUDE.md docs/ smoke 2>/dev/null
git status --short   # ⚠ 确认只暂存了本次该提交的东西（仓库根有一批与本次无关的未跟踪文件，别误提）
git commit -m "vision Plan 2：入口接线完成（控制台/webui/技能表态）+ 验收记录 + 清理"
```

---

## 完成标准（Plan 2）

- [ ] 控制台 `/img` 能把本机图片送进对话，模型答得出图上的字
- [ ] webui 上传的图片随消息发出，模型答得出图上的字；刷新页面后历史里能看到缩略图且能点开
- [ ] `/api/image` 对**其他用户目录**的路径返回非 200
- [ ] 桌面技能 `screenshot` 的图真正进模型视野；浏览器技能同理（含 `--serve` stdout 干净性结论）
- [ ] `/test` 11/11；8 个模块整体编译通过

## 已知遗留（不在本计划内，写进 spec 备注）

- `extractDeclaredImages` 的「输出必须以 `{` 开头」护栏对带前导日志的 Python 生产者过严（Task 3 会给出实测结论）
- `ATTACH_CLEANUP` 无生产者：长驻进程只能靠重启触发清理（`runStartMsg`）
- `extractMeta` 读文本最多 256MB、PDF 无上限，均在请求路径上
- `sniff(File)` 与 `sniffBytes(byte[])` 近似重复，magic 表可能漂移
- `TLWebChatModule.login` 在 `passwords` 为空时接受任意非空 userId——多用户部署必须配 `passwords`
