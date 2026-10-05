# vision 多模态（Plan 1/2 · 框架核心）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让框架核心具备"附件资源化 + 按需投喂"能力——消息可携带附件、附件落盘登记、发送时生成清单、模型按需 `view_image` 取图、Provider 发出图片块、工具产图按技能声明注入。

**Architecture:** `content` 保持 String，`TLConversationHistory` 新增 `attachments`（资源引用）/`images`（要发出去的图）两个并列字段，五处 String 假设零改动。附件统一由 `TLAttachmentStore` 模块登记（原地上传件引用 / 自动产物哈希落盘）。发送前 `buildSendList` 做**真拷贝**后拼清单、按 K 保留注入图。Provider 只按 `images` 字段决定是否发块数组。

**Tech Stack:** Java 17 · Maven · gson · TLBaseModule 消息框架 · DeepSeek `deepseek-flash`（`deepseek-v4-flash` 已接管）

**Spec:** `docs/superpowers/specs/2026-10-05-vision-image-input-design.md`

**范围说明:** 本计划 = 框架核心层（无 UI 入口）。控制台 `/img`、webui 上传打通、桌面/浏览器技能表态属 **Plan 2**。本计划完成后，任何调用方（其它模块、API、测试）都能通过 `attachments` 参数带图跑通全链路。

**⚠ 本仓无 JUnit。** 验证走"临时同包 smoke 测试"惯例：一个用 `javac` 直接编译、`java` 运行、**最后一个任务删除**的临时类。

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `aiagent/common/.../aiagent/TLAttachmentRef.java`（新建） | 附件引用 POJO：kind/path/name/mime/size/meta/url/fileId/detail/origin |
| `aiagent/common/.../aiagent/TLAttachmentStore.java`（新建） | 附件登记模块 + 静态读盘/魔数嗅探/元数据抽取/清理 |
| `aiagent/common/.../aiagent/TLConversationHistory.java`（改） | +`attachments`/`images` 字段、`hasXxx()`、`copy()` |
| `aiagent/common/.../aiagent/TLAiAgentParamString.java`（改） | +常量 |
| `aiagent/common/.../aiagent/TLAiAgent.java`（改） | 读附件、拼清单、真拷贝、保留策略、token 估算、工具产图注入 |
| `aiagent/common/.../aiagent/TLAgentService.java`（改） | 透传 `AI_P_ATTACHMENTS` |
| `aiagent/provider-openai/.../TLOpenAiProvider.java`（改） | `buildRequestBody` 图片块分支 |
| `aiagent/provider-claude/.../TLClaudeProvider.java`（改） | image block 分支 |
| `aiagent/skill-builtin/.../builtin/TLViewImageSkill.java`（新建） | `view_image` 技能 |
| `demo/tlobject/.../conf/tlobject/cn.tianlong.tlobject.aiagent_config.xml`（改） | 注册 `attachmentStore` / `viewImageSkill` |
| `demo/tlobject/.../conf/demo/aiagent/aiagent_master_config.xml`（改） | master `<skills>` 加 `viewImageSkill` |
| `smoke/VisionSmokeTest.java`（临时，最后删） | 全部验证 |

---

## 环境准备（每个任务复用）

**classpath 抽取**（Git Bash，仓库根目录）：

```bash
cd /d/tlobjectapp/tlobject
CP=$(sed -n '2p' aistart.bat | sed 's/.*-classpath "//; s/" .*//')
echo "${#CP}"   # 应 > 3000
```

**编译 + 运行 smoke 测试**（cwd 必须是 `demo/tlobject`，conf 是相对路径）：

```bash
cd /d/tlobjectapp/tlobject
CP=$(sed -n '2p' aistart.bat | sed 's/.*-classpath "//; s/" .*//')
/d/maven/bin/mvn -q -f pom.xml -pl aiagent/common,aiagent/provider-openai,aiagent/provider-claude,aiagent/skill-builtin,demo/tlobject compile
javac -encoding UTF-8 -cp "$CP" -d smoke/classes smoke/VisionSmokeTest.java
cd demo/tlobject && java -Dfile.encoding=UTF-8 -cp "$CP;D:\tlobjectapp\tlobject\smoke\classes" cn.tianlong.tlobject.aiagent.VisionSmokeTest
```

⚠ **`smoke/classes` 必须放在 classpath 最后**：工厂按 classpath 上第一个含 `conf/` 的目录解析配置，
放最前会让它去 `smoke/classes/conf/` 找，启动时 StackOverflow。
⚠ **`mvn` 不在 PATH 上**，用绝对路径 `/d/maven/bin/mvn`。
⚠ 本仓 Maven 增量编译漏编是已知问题——改了代码但行为没变时，先 `rm -rf <module>/target/classes` 再编。
⚠ 发消息给模块必须用**模块名路由** `factory.putMsg(M_ATTACHMENTSTORE, msg)`。
`factory.putMsg(factory, msg)` 是把消息发给**工厂本身**：未知 action 返回 **null**，紧随其后的
`s1.getParam("ref")` 抛 NPE，再被 `finally { factory.shutdown(...) }` 变成 `System.exit(0)`——
现象是"跑到一半进程静默退出、没有汇总行"，很容易误判成框架 shutdown。
（区别于另一个已知陷阱：`TLBaseModule.putMsg("不存在的模块名", msg)` 会真的 `shutdown(-1)`。）
⚠ `factory.shutdown(int)` 内部会 `System.exit`——汇总行要打在它**之前**（或放进 `finally` 的开头）。

---

## Task 0（可选）：探针 — `tool` 角色能否带图

**为什么：** 手册只点名 system/assistant 带图会 400，"图片仅支持出现在 user 消息中"是否覆盖 `tool` 角色未明说。能带 → Task 6 不用注入独立 user 消息，链路更短；不能带 → 按本计划走注入。**不执行探针就按保守设计走，不影响任何其它任务。**

**Files:**
- Create: `smoke/probe_tool_image.py`（临时）

- [ ] **Step 1: 写探针脚本**

```python
# 用 conf 里的 apiKey 构造最小请求：assistant(tool_calls) -> tool(content 含 image_url 块)
import base64, io, json, re, urllib.request, urllib.error
from PIL import Image

cfg = open(r"demo/tlobject/src/main/resources/conf/demo/aiagent/moduleFactory_chat_config.xml",
           encoding="utf-8").read()
key = re.search(r'apiKey="([^"]+)"', cfg).group(1)

im = Image.new('RGB', (200, 80), 'white'); buf = io.BytesIO(); im.save(buf, 'PNG')
b64 = base64.b64encode(buf.getvalue()).decode()

body = {"model": "deepseek-v4-flash", "max_tokens": 50, "messages": [
    {"role": "user", "content": "调用工具"},
    {"role": "assistant", "content": None, "tool_calls": [
        {"id": "call_1", "type": "function",
         "function": {"name": "probe", "arguments": "{}"}}]},
    {"role": "tool", "tool_call_id": "call_1", "content": [
        {"type": "text", "text": "工具结果"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64," + b64}}]},
]}
req = urllib.request.Request("https://api.deepseek.com/chat/completions",
    data=json.dumps(body).encode(),
    headers={"Content-Type": "application/json", "Authorization": "Bearer " + key})
try:
    r = urllib.request.urlopen(req, timeout=60)
    print("TOOL-IMAGE: OK (200) ->", r.read().decode()[:200])
except urllib.error.HTTPError as e:
    print("TOOL-IMAGE: HTTP", e.code, "->", e.read().decode()[:300])
```

- [ ] **Step 2: 运行并记录结论**

Run: `python smoke/probe_tool_image.py`

- [ ] **Step 3: 把结论写进 spec**

编辑 `docs/superpowers/specs/2026-10-05-vision-image-input-design.md` 的 §12，把"待验证"改为实测结论（HTTP 200 = 能带，Task 6 可省注入；400 = 按本计划）。

- [ ] **Step 4: 提交**

```bash
git add smoke/probe_tool_image.py docs/superpowers/specs/2026-10-05-vision-image-input-design.md
git commit -m "vision 探针：tool 角色带图实测结论"
```

---

## Task 1: 附件模型（TLAttachmentRef + TLConversationHistory 扩展）

**Files:**
- Create: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAttachmentRef.java`
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLConversationHistory.java`
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAiAgentParamString.java`
- Test: `smoke/VisionSmokeTest.java`

- [ ] **Step 1: 写失败测试**

Create `smoke/VisionSmokeTest.java`（package 与 `TLAiAgentParamString` 同包，方便用常量）：

```java
package cn.tianlong.tlobject.aiagent;

import com.google.gson.Gson;
import java.util.*;

/** vision 多模态临时冒烟测试（跑完即删）。cwd = demo/tlobject */
public class VisionSmokeTest implements TLAiAgentParamString {
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        System.setProperty("log4j.configurationFile", "target/classes/conf/demo/aiagent/log4j2.xml");
        testAttachmentModel();
        System.out.println("\n===== pass=" + pass + " fail=" + fail + " =====");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (ok) pass++; else fail++;
    }

    // ===== Task 1 =====
    static void testAttachmentModel() {
        // 1.1 copy() 是独立对象：改副本的 images 不影响原件
        TLConversationHistory orig = new TLConversationHistory(TLConversationHistory.Role.user, "看图");
        orig.setImages(new ArrayList<>(List.of(
                TLAttachmentRef.forFile("d:/x/a.png", "a.png", "image/png", "user"))));
        orig.setAttachments(new ArrayList<>(List.of(
                TLAttachmentRef.forFile("d:/x/a.png", "a.png", "image/png", "user"))));
        TLConversationHistory cp = orig.copy();
        cp.setImages(null);
        cp.setContent("被改了");
        check("1.1 copy() 独立：改副本 images 不影响原件", orig.hasImages());
        check("1.1 copy() 独立：改副本 content 不影响原件", "看图".equals(orig.getContent()));
        check("1.1 copy() 保留字段", cp.getRole() == TLConversationHistory.Role.user
                && cp.getAttachments() != null && cp.getAttachments().size() == 1);

        // 1.2 hasXxx 判空
        TLConversationHistory empty = new TLConversationHistory(TLConversationHistory.Role.user, "t");
        check("1.2 hasImages/hasAttachments 空列表为 false",
                !empty.hasImages() && !empty.hasAttachments());
        empty.setImages(new ArrayList<>());
        check("1.2 空 List 也算 false", !empty.hasImages());

        // 1.3 老存档（无新字段的 JSON）反序列化不炸且为 null
        Gson gson = new Gson();
        TLConversationHistory back = gson.fromJson(
                "{\"role\":\"user\",\"content\":\"历史消息\"}", TLConversationHistory.class);
        check("1.3 老 JSON 反序列化：新字段为 null",
                back != null && !back.hasImages() && !back.hasAttachments()
                        && "历史消息".equals(back.getContent()));

        // 1.4 新字段 gson 往返
        TLConversationHistory round = gson.fromJson(gson.toJson(orig), TLConversationHistory.class);
        check("1.4 images gson 往返", round.hasImages() && round.getImages().size() == 1
                && "d:/x/a.png".equals(round.getImages().get(0).getPath()));
        check("1.4 attachments gson 往返", round.hasAttachments()
                && "a.png".equals(round.getAttachments().get(0).getName()));
    }
}
```

- [ ] **Step 2: 运行测试确认编译失败**

Run:
```bash
cd /d/tlobjectapp/tlobject && CP=$(sed -n '2p' aistart.bat | sed 's/.*-classpath "//; s/" .*//')
mkdir -p smoke/classes && javac -encoding UTF-8 -cp "$CP" -d smoke/classes smoke/VisionSmokeTest.java
```
Expected: FAIL — `找不到符号: 类 TLAttachmentRef`

- [ ] **Step 3: 实现 TLAttachmentRef**

Create `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAttachmentRef.java`：

```java
package cn.tianlong.tlobject.aiagent;

import java.io.Serializable;
import java.util.Map;

/**
 * 附件引用。表示一条消息上挂着的资源（图片或文件），历史里只存引用不存内容。
 * attachments 字段驱动"清单"文本；images 字段驱动 provider 的图片块。
 * 作为POJO在TLMsg.args中传输并随会话存档 gson 序列化，不继承TLMsg。
 *
 * 创建日期：2026/10/5
 * 作者:tianlong
 */
public class TLAttachmentRef implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** 磁盘上的图片文件 */
        IMAGE,
        /** 磁盘上的普通文件（CSV/文本/…），不产生图片块 */
        FILE,
        /** 外链图片 URL，透传给模型自行下载 */
        URL,
        /** DeepSeek Files API 的 file-api-xxx，仅透传 */
        FILE_ID
    }

    private Kind kind;
    /** 绝对路径（IMAGE/FILE） */
    private String path;
    /** 原始文件名，清单展示与模型引用用 */
    private String name;
    private String mime;
    private long size;
    /** 本地抽取的元数据：width/height · rows/columns · chars · pages */
    private Map<String, Object> meta;
    private String url;
    private String fileId;
    /** low | high | original | auto（图片可选） */
    private String detail;
    /** user | tool | api —— webui 渲染区分气泡样式 */
    private String origin;

    public TLAttachmentRef() {}

    public static TLAttachmentRef forFile(String path, String name, String mime, String origin) {
        return forFile(path, name, mime, origin, Kind.FILE);
    }

    public static TLAttachmentRef forImage(String path, String name, String mime, String origin) {
        return forFile(path, name, mime, origin, Kind.IMAGE);
    }

    private static TLAttachmentRef forFile(String path, String name, String mime,
                                           String origin, Kind kind) {
        TLAttachmentRef r = new TLAttachmentRef();
        r.kind = kind;
        r.path = path;
        r.name = name;
        r.mime = mime;
        r.origin = origin;
        return r;
    }

    public static TLAttachmentRef forUrl(String url, String origin) {
        TLAttachmentRef r = new TLAttachmentRef();
        r.kind = Kind.URL;
        r.url = url;
        r.origin = origin;
        return r;
    }

    public static TLAttachmentRef forFileId(String fileId, String origin) {
        TLAttachmentRef r = new TLAttachmentRef();
        r.kind = Kind.FILE_ID;
        r.fileId = fileId;
        r.origin = origin;
        return r;
    }

    /** 是否会作为图片块发给模型 */
    public boolean isImageLike() {
        return kind == Kind.IMAGE || kind == Kind.URL || kind == Kind.FILE_ID;
    }

    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getMime() { return mime; }
    public void setMime(String mime) { this.mime = mime; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public Map<String, Object> getMeta() { return meta; }
    public void setMeta(Map<String, Object> meta) { this.meta = meta; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
}
```

- [ ] **Step 4: 扩展 TLConversationHistory**

Modify `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLConversationHistory.java` —— 在 `reasoningContent` 字段（第 32 行）后加两个字段：

```java
    /** 资源引用：只驱动发送时的"附件清单"文本，不产生图片块 */
    private List<TLAttachmentRef> attachments;
    /** 要作为图片块发给模型的图（view_image 结果 / 技能表态的工具产图注入消息） */
    private List<TLAttachmentRef> images;
```

在 `isTextOnly()`（第 102-104 行）后加方法：

```java
    public boolean hasAttachments() {
        return attachments != null && !attachments.isEmpty();
    }

    public boolean hasImages() {
        return images != null && !images.isEmpty();
    }

    /**
     * 发送视图专用浅拷贝：发送期的清单拼接 / 保留策略裁剪只作用副本。
     * 列表与 metadata 共享引用——发送期只整体 setXxx(null)，绝不改列表内容。
     */
    public TLConversationHistory copy() {
        TLConversationHistory h = new TLConversationHistory();
        h.role = this.role;
        h.content = this.content;
        h.toolCalls = this.toolCalls;
        h.toolCallId = this.toolCallId;
        h.name = this.name;
        h.timestamp = this.timestamp;
        h.seq = this.seq;
        h.metadata = this.metadata;
        h.reasoningContent = this.reasoningContent;
        h.attachments = this.attachments;
        h.images = this.images;
        return h;
    }
```

在 getter/setter 区（第 96 行 `setReasoningContent` 后）加：

```java
    public List<TLAttachmentRef> getAttachments() { return attachments; }
    public void setAttachments(List<TLAttachmentRef> attachments) { this.attachments = attachments; }

    public List<TLAttachmentRef> getImages() { return images; }
    public void setImages(List<TLAttachmentRef> images) { this.images = images; }
```

- [ ] **Step 5: 加常量**

Modify `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAiAgentParamString.java` —— 在 `AI_P_MESSAGEHISTORY`（第 191 行）后加：

```java
    /** 调用方传入的附件清单：List<Map>（path/base64/url/fileId 四形态），见 TLAttachmentStore 归一化 */
    String AI_P_ATTACHMENTS = "attachments";
    /** 附件登记模块名（框架注册表注册） */
    String M_ATTACHMENTSTORE = "attachmentStore";
    /** 附件登记 action：{path,name,userId,origin} → {ref:TLAttachmentRef} */
    String ATTACH_REGISTER = "attachRegister";
    /** 附件落盘 action：{base64,mime,name,userId,origin} → {ref:TLAttachmentRef} */
    String ATTACH_STOREBASE64 = "attachStoreBase64";
    /** 附件清理 action：{userId} → {removed:n} */
    String ATTACH_CLEANUP = "attachCleanup";
```

- [ ] **Step 6: 运行测试确认通过**

Run: 环境准备的"编译 + 运行 smoke 测试"命令
Expected: `1.1`~`1.4` 全 `[PASS]`，`===== pass=8 fail=0 =====`

- [ ] **Step 7: 提交**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAttachmentRef.java \
        aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLConversationHistory.java \
        aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAiAgentParamString.java \
        smoke/VisionSmokeTest.java
git commit -m "vision：附件模型 TLAttachmentRef + TLConversationHistory 双字段与 copy()"
```

---

## Task 2: TLAttachmentStore（附件登记模块）

**Files:**
- Create: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAttachmentStore.java`
- Modify: `demo/tlobject/src/main/resources/conf/tlobject/cn.tianlong.tlobject.aiagent_config.xml`
- Test: `smoke/VisionSmokeTest.java`

- [ ] **Step 1: 写失败测试**

在 `VisionSmokeTest` 里加（并在 `main` 里 `testAttachmentModel();` 后调用 `testStore(factory);`，同时把 `main` 改成先起工厂）：

```java
    // ===== 工厂启动（Task 2 起需要）=====
    static TLObjectFactory bootFactory() {
        System.setProperty("log4j.configurationFile", "target/classes/conf/demo/aiagent/log4j2.xml");
        TLObjectFactory factory = TLObjectFactory.getInstance(
                "target/classes/conf/demo/aiagent", "moduleFactory_chat_config.xml");
        factory.startFactory(null, null);
        return factory;
    }

    // ===== Task 2 =====
    static void testStore(TLObjectFactory factory) throws Exception {
        String uid = "smoke_u1";
        java.io.File tmp = new java.io.File("target/smoke_att").getAbsoluteFile();
        // 造一张 32x16 的 PNG
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(32, 16,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.File png = new java.io.File(tmp, "pic.png");
        png.getParentFile().mkdirs();
        javax.imageio.ImageIO.write(img, "png", png);

        // 2.1 魔数嗅探 + 尺寸
        Map<String, Object> sn = TLAttachmentStore.sniff(png);
        check("2.1 嗅探 PNG mime+尺寸", sn != null && "image/png".equals(sn.get("mime"))
                && Integer.valueOf(32).equals(sn.get("width"))
                && Integer.valueOf(16).equals(sn.get("height")));

        // 2.2 伪造扩展名被识破（内容仍是 PNG）
        java.io.File fake = new java.io.File(tmp, "fake.jpg");
        java.nio.file.Files.copy(png.toPath(), fake.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        check("2.2 魔数判定不看扩展名", "image/png".equals(TLAttachmentStore.sniff(fake).get("mime")));

        // 2.3 非图片文件 sniff 返回 null
        java.io.File txt = new java.io.File(tmp, "d.csv");
        java.nio.file.Files.write(txt.toPath(),
                "date,sku,amount\n2026-01-01,A,10\n2026-01-02,B,20\n".getBytes("UTF-8"));
        check("2.3 非图片 sniff 为 null", TLAttachmentStore.sniff(txt) == null);

        // 2.4 CSV 元数据：行数 + 列名
        Map<String, Object> meta = TLAttachmentStore.extractMeta(txt, "text/plain");
        check("2.4 CSV 元数据 rows/columns", meta != null
                && Integer.valueOf(3).equals(meta.get("rows"))
                && String.valueOf(meta.get("columns")).contains("sku"));

        // 2.5 register 原地引用（不复制）
        Map<String, Object> reg = new HashMap<>();
        reg.put("path", png.getAbsolutePath()); reg.put("name", "pic.png");
        reg.put("userId", uid); reg.put("origin", "user");
        TLMsg r = factory.putMsg(M_ATTACHMENTSTORE, factory.createMsg().setAction(ATTACH_REGISTER)
                .setParam("config", reg).setParam(AI_P_USERID, uid));
        TLAttachmentRef ref = (TLAttachmentRef) r.getParam("ref");
        check("2.5 register 原地引用不复制",
                ref != null && png.getAbsolutePath().equals(ref.getPath())
                        && "IMAGE".equals(ref.getKind().name()));

        // 2.6 路径穿越拒绝（含跨用户目录）
        Map<String, Object> bad = new HashMap<>();
        bad.put("path", "../../windows/win.ini"); bad.put("name", "x"); bad.put("userId", uid);
        TLMsg rBad = factory.putMsg(M_ATTACHMENTSTORE, factory.createMsg().setAction(ATTACH_REGISTER)
                .setParam("config", bad).setParam(AI_P_USERID, uid));
        check("2.6 路径穿越被拒", rBad == null || rBad.getParam("ref") == null);

        // 2.9 跨用户目录拒绝（userId=smoke_u1 不得登记 data/smoke_u2/ 下的文件）
        java.io.File other = new java.io.File("data/smoke_u2/uploads/x.png").getAbsoluteFile();
        other.getParentFile().mkdirs();
        javax.imageio.ImageIO.write(img, "png", other);
        TLMsg rX = factory.putMsg(M_ATTACHMENTSTORE, factory.createMsg().setAction(ATTACH_REGISTER)
                .setParam("config", new HashMap<>(Map.of("path", other.getAbsolutePath(),
                        "name", "x.png", "origin", "api"))).setParam(AI_P_USERID, uid));
        check("2.9 跨用户目录被拒", rX == null || rX.getParam("ref") == null);

        // 2.7 store 落盘 + 内容哈希去重
        byte[] bytes = java.nio.file.Files.readAllBytes(png.toPath());
        String b64 = Base64.getEncoder().encodeToString(bytes);
        TLMsg s1 = factory.putMsg(M_ATTACHMENTSTORE, factory.createMsg().setAction(ATTACH_STOREBASE64)
                .setParam("base64", b64).setParam("mime", "image/png")
                .setParam("name", "a.png").setParam(AI_P_USERID, uid).setParam("origin", "tool"));
        TLMsg s2 = factory.putMsg(M_ATTACHMENTSTORE, factory.createMsg().setAction(ATTACH_STOREBASE64)
                .setParam("base64", b64).setParam("mime", "image/png")
                .setParam("name", "b.png").setParam(AI_P_USERID, uid).setParam("origin", "tool"));
        TLAttachmentRef r1 = (TLAttachmentRef) s1.getParam("ref");
        TLAttachmentRef r2 = (TLAttachmentRef) s2.getParam("ref");
        check("2.7 内容哈希去重：两次 store 同一路径",
                r1 != null && r2 != null && r1.getPath().equals(r2.getPath()));

        // 2.8 readBytes 往返 + 缺失文件降级
        check("2.8 readBytes 往返", Arrays.equals(bytes, TLAttachmentStore.readBytes(r1)));
        TLAttachmentRef ghost = TLAttachmentRef.forImage(
                new java.io.File(tmp, "nope.png").getAbsolutePath(), "nope.png", "image/png", "tool");
        check("2.8 文件缺失 readBytes 返回 null", TLAttachmentStore.readBytes(ghost) == null);
    }
```

⚠ **2.5 的图片必须放在 `data/{uid}/` 下**——`register` 有 `isUnder(dataBasePath)` 越界检查（2.6 正是靠它）。
`target/smoke_att` 只适用于 `sniff`/`extractMeta`/`readBytes` 这类**不做越界检查**的静态方法（2.1~2.4、2.8）；
`register` 那份要拷到 `data/smoke_u1/uploads/pic.png`，断言引用回的是拷贝后的绝对路径（"原地引用不复制"的语义不变）。

`main` 改成（⚠ 汇总行必须在 `factory.shutdown(...)` **之前**——它内部会 `System.exit`，放后面永远不会执行）：

```java
    public static void main(String[] args) throws Exception {
        System.setProperty("log4j.configurationFile", "target/classes/conf/demo/aiagent/log4j2.xml");
        testAttachmentModel();
        testCopyCompleteness();
        TLObjectFactory factory = bootFactory();
        try {
            testStore(factory);
        } finally {
            System.out.println("\n===== pass=" + pass + " fail=" + fail + " =====");
            factory.shutdown(fail == 0 ? 0 : 1);
        }
    }
```

（`TLObjectFactory` 需 `import cn.tianlong.tlobject.base.TLObjectFactory;`、`TLMsg` 需 `import cn.tianlong.tlobject.base.TLMsg;`）

- [ ] **Step 2: 运行确认失败**

Expected: 编译失败 `找不到符号: 类 TLAttachmentStore`

- [ ] **Step 3: 实现 TLAttachmentStore**

Create `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAttachmentStore.java`：

```java
package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/**
 * 附件登记模块：图片/文件的落盘、去重、元数据本地抽取与清理。
 * 本体是模块（配置进 XML，工厂单例）；两个静态方法供 provider 热路径零往返调用。
 *
 * 存储布局：
 *   data/{uid}/attachments/auto/{hash[0:2]}/{hash}.{ext}   自动产物（截图/base64 输入），内容哈希去重
 *   data/{uid}/uploads/...                                 用户上传件原地引用，不复制
 *
 * 创建日期：2026/10/5
 * 作者:tianlong
 */
public class TLAttachmentStore extends TLBaseModule implements TLAiAgentParamString {

    private String dataBasePath = "./data/";
    private int maxImageMB = 32;
    private int retentionDays = 7;
    private int storeMaxMB = 500;
    /** 文本类元数据最多读多少字节（行数/列名抽取用） */
    private int metaReadKB = 256;

    public TLAttachmentStore() { super(); }
    public TLAttachmentStore(String name) { super(name); }
    public TLAttachmentStore(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("dataBasePath") != null) dataBasePath = params.get("dataBasePath");
            maxImageMB = intParam("maxImageMB", maxImageMB);
            retentionDays = intParam("attachmentRetentionDays", retentionDays);
            storeMaxMB = intParam("attachmentStoreMaxMB", storeMaxMB);
        }
    }

    private int intParam(String key, int def) {
        if (params == null || params.get(key) == null) return def;
        try { return Integer.parseInt(params.get(key)); } catch (NumberFormatException e) { return def; }
    }

    @Override
    protected TLBaseModule init() { return this; }

    @Override
    public void runStartMsg() {          // ⚠ 基类签名为无参 void，不是 TLMsg
        super.runStartMsg();
        // 启动清理：所有用户目录各清一次（失败不影响启动）
        try {
            File root = new File(dataBasePath);
            File[] users = root.listFiles(File::isDirectory);
            if (users != null) for (File u : users) cleanupUser(u.getName());
        } catch (Exception e) {
            putLog("attachment cleanup on start failed: " + e, LogLevel.WARN);
        }
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case ATTACH_REGISTER:    return register(fromWho, msg);
            case ATTACH_STOREBASE64: return storeBase64(fromWho, msg);
            case ATTACH_CLEANUP:     return cleanup(fromWho, msg);
            default: return null;
        }
    }

    // ======================== 登记 ========================

    @SuppressWarnings("unchecked")
    protected TLMsg register(Object fromWho, TLMsg msg) {
        Map<String, Object> cfg = (Map<String, Object>) msg.getParam("config", new LinkedHashMap<>());
        String userId = msg.getStringParam(AI_P_USERID, "default");
        String path = str(cfg.get("path"));
        String name = str(cfg.get("name"));
        String origin = str(cfg.get("origin"));
        if (path.isEmpty()) return fail("path 为空");

        File f = new File(path);
        if (!f.isAbsolute()) f = new File(userRoot(userId), path);
        if (!f.isFile()) return fail("文件不存在: " + path);
        // 越界判定必须收在本用户目录内（data/{uid}/）：只校验 data/ 会让调用方指向别人的目录
        if (!isUnder(f, userRoot(userId))) return fail("路径越界: " + path);
        if (name.isEmpty()) name = f.getName();

        Map<String, Object> sn = sniff(f);
        String mime = sn != null ? (String) sn.get("mime") : guessMime(f.getName());
        long limit = (long) maxImageMB * 1024 * 1024;
        if (sn != null && f.length() > limit) return fail("图片超过 " + maxImageMB + "MB: " + name);

        TLAttachmentRef ref = sn != null
                ? TLAttachmentRef.forImage(f.getAbsolutePath(), name, mime, origin)
                : TLAttachmentRef.forFile(f.getAbsolutePath(), name, mime, origin);
        ref.setSize(f.length());
        Map<String, Object> meta = new LinkedHashMap<>();
        if (sn != null) { meta.put("width", sn.get("width")); meta.put("height", sn.get("height")); }
        Map<String, Object> extra = extractMeta(f, mime);
        if (extra != null) meta.putAll(extra);
        if (!meta.isEmpty()) ref.setMeta(meta);

        putLog("attach register: " + name + " (" + mime + ", " + f.length() + "B) -> " + ref.getKind(),
                LogLevel.DEBUG);
        return createMsg().setParam(RESULT, true).setParam("ref", ref);
    }

    protected TLMsg storeBase64(Object fromWho, TLMsg msg) {
        String userId = msg.getStringParam(AI_P_USERID, "default");
        String b64 = msg.getStringParam("base64", "");
        String name = msg.getStringParam("name", "image");
        String origin = msg.getStringParam("origin", "api");
        if (b64.isEmpty()) return fail("base64 为空");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(stripDataUrl(b64)); }
        catch (IllegalArgumentException e) { return fail("base64 解码失败"); }
        long limit = (long) maxImageMB * 1024 * 1024;
        if (bytes.length > limit) return fail("图片超过 " + maxImageMB + "MB");
        try {
            String hash = sha256(bytes);
            Map<String, Object> sn = sniffBytes(bytes);
            if (sn == null) return fail("不是支持的图片格式（PNG/JPEG/GIF/WebP）");
            String ext = extFor((String) sn.get("mime"));
            File dir = new File(userRoot(userId), "attachments/auto/" + hash.substring(0, 2));
            File f = new File(dir, hash + "." + ext);
            if (!f.exists()) {   // 内容寻址：同内容天然去重
                dir.mkdirs();
                Files.write(f.toPath(), bytes);
            }
            TLAttachmentRef ref = TLAttachmentRef.forImage(f.getAbsolutePath(), name,
                    (String) sn.get("mime"), origin);
            ref.setSize(bytes.length);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("width", sn.get("width")); meta.put("height", sn.get("height"));
            ref.setMeta(meta);
            return createMsg().setParam(RESULT, true).setParam("ref", ref);
        } catch (Exception e) {
            putLog("attach store failed: " + e, LogLevel.ERROR);
            return fail("落盘失败: " + e.getMessage());
        }
    }

    protected TLMsg cleanup(Object fromWho, TLMsg msg) {
        int n = cleanupUser(msg.getStringParam(AI_P_USERID, "default"));
        return createMsg().setParam(RESULT, true).setParam("removed", n);
    }

    // ======================== 静态工具（provider 热路径） ========================

    /** 读附件字节；文件缺失返回 null（调用方降级，不抛） */
    public static byte[] readBytes(TLAttachmentRef ref) {
        if (ref == null || ref.getPath() == null) return null;
        File f = new File(ref.getPath());
        if (!f.isFile()) return null;
        try { return Files.readAllBytes(f.toPath()); }
        catch (IOException e) { return null; }
    }

    /** 魔数嗅探：PNG/JPEG/GIF/WebP；返回 {mime,width,height}，非图片返回 null。尺寸读不到时为 0 */
    public static Map<String, Object> sniff(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] head = new byte[16];
            int n = raf.read(head);
            if (n < 12) return null;
            String mime = magicMime(head);
            if (mime == null) return null;
            int w = 0, h = 0;
            try {
                BufferedImage img = ImageIO.read(f);   // JDK 内置：PNG/JPEG/GIF 可读，WebP 不可
                if (img != null) { w = img.getWidth(); h = img.getHeight(); }
            } catch (Exception ignored) {}
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mime", mime); m.put("width", w); m.put("height", h);
            return m;
        } catch (IOException e) {
            return null;
        }
    }

    private static Map<String, Object> sniffBytes(byte[] b) {
        if (b.length < 12) return null;
        byte[] head = Arrays.copyOf(b, 16);
        String mime = magicMime(head);
        if (mime == null) return null;
        int w = 0, h = 0;
        try {
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(b));
            if (img != null) { w = img.getWidth(); h = img.getHeight(); }
        } catch (Exception ignored) {}
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mime", mime); m.put("width", w); m.put("height", h);
        return m;
    }

    private static String magicMime(byte[] h) {
        if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G')
            return "image/png";
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF)
            return "image/jpeg";
        if (h.length >= 6 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F')
            return "image/gif";
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P')
            return "image/webp";
        return null;
    }

    /**
     * 本地抽取元数据（不调 LLM，失败静默返回 null）：
     * 文本/CSV → rows/chars/columns；PDF → pages（粗估）。
     */
    public static Map<String, Object> extractMeta(File f, String mime) {
        try {
            String lower = f.getName().toLowerCase();
            boolean textish = (mime != null && mime.startsWith("text/"))
                    || lower.endsWith(".csv") || lower.endsWith(".txt") || lower.endsWith(".md")
                    || lower.endsWith(".json") || lower.endsWith(".log") || lower.endsWith(".tsv");
            if (textish && f.length() < 256L * 1024 * 1024) {
                String content = new String(Files.readAllBytes(f.toPath()), "UTF-8");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("chars", content.length());
                // 不用 -1 上限：尾部换行产生的空串不计入行数
                String[] lines = content.isEmpty() ? new String[0] : content.split("\r?\n");
                m.put("rows", lines.length);
                if ((lower.endsWith(".csv") || lower.endsWith(".tsv")) && lines.length > 0) {
                    String sep = lower.endsWith(".tsv") ? "\t" : ",";
                    String[] cols = lines[0].split(java.util.regex.Pattern.quote(sep), -1);
                    if (cols.length > 1 && cols.length <= 64) m.put("columns", String.join(",", cols));
                }
                return m;
            }
            if (lower.endsWith(".pdf")) {
                byte[] all = Files.readAllBytes(f.toPath());
                String s = new String(all, "ISO-8859-1");
                int pages = 0, idx = 0;
                while ((idx = s.indexOf("/Type", idx)) >= 0) {
                    if (s.startsWith("/Page", idx + 5) && !s.startsWith("/Pages", idx + 5)) pages++;
                    idx += 5;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("pages", pages);   // 粗估：无 xref 解析
                return m;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ======================== 清理 ========================

    private int cleanupUser(String userId) {
        int removed = 0;
        long cutoff = System.currentTimeMillis() - (long) retentionDays * 24 * 3600 * 1000;
        for (String sub : new String[]{"attachments", "uploads"}) {
            File dir = new File(userRoot(userId), sub);
            List<File> files = new ArrayList<>();
            collect(dir, files);
            for (File f : files)
                if (f.lastModified() < cutoff && f.delete()) removed++;
        }
        // 总量上限：按 mtime LRU 淘汰（只统计两个附件目录）
        List<File> all = new ArrayList<>();
        collect(new File(userRoot(userId), "attachments"), all);
        collect(new File(userRoot(userId), "uploads"), all);
        long total = 0;
        for (File f : all) total += f.length();
        long cap = (long) storeMaxMB * 1024 * 1024;
        if (total > cap) {
            all.sort(Comparator.comparingLong(File::lastModified));
            for (File f : all) {
                if (total <= cap) break;
                long len = f.length();
                if (f.delete()) { total -= len; removed++; }
            }
        }
        return removed;
    }

    private static void collect(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out);
            else out.add(f);
        }
    }

    // ======================== 辅助 ========================

    private File userRoot(String userId) {
        return new File(dataBasePath, safe(userId));
    }

    private static String safe(String s) {
        if (s == null || s.isEmpty()) return "default";
        return s.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static boolean isUnder(File f, File root) {
        try {
            return f.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator);
        } catch (IOException e) { return false; }
    }

    private static String sha256(byte[] b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest(b)) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String extFor(String mime) {
        switch (mime) {
            case "image/png": return "png";
            case "image/jpeg": return "jpg";
            case "image/gif": return "gif";
            case "image/webp": return "webp";
            default: return "bin";
        }
    }

    private static String guessMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".md")) return "text/markdown";
        if (n.endsWith(".txt") || n.endsWith(".log")) return "text/plain";
        if (n.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    private static String stripDataUrl(String b64) {
        int comma = b64.indexOf(',');
        return b64.startsWith("data:") && comma > 0 ? b64.substring(comma + 1) : b64;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private TLMsg fail(String reason) {
        putLog("attach: " + reason, LogLevel.WARN);
        return createMsg().setParam(RESULT, false).setParam("error", reason);
    }
}
```

- [ ] **Step 4: 注册模块**

Modify `demo/tlobject/src/main/resources/conf/tlobject/cn.tianlong.tlobject.aiagent_config.xml` —— 在 `</modules>` 前加（紧邻其它 aiagent 模块声明处）：

```xml
        <!-- 附件登记：落盘/去重/元数据/清理。agent 与其调用方都经消息访问 -->
        <module name="attachmentStore" classfile="cn.tianlong.tlobject.aiagent.TLAttachmentStore"
                singleton="true"/>
```

- [ ] **Step 5: 运行测试确认通过**

Run: 环境准备的"编译 + 运行 smoke 测试"命令
Expected: `2.1`~`2.8` 全 `[PASS]`

- [ ] **Step 6: 提交**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAttachmentStore.java \
        demo/tlobject/src/main/resources/conf/tlobject/cn.tianlong.tlobject.aiagent_config.xml \
        smoke/VisionSmokeTest.java
git commit -m "vision：TLAttachmentStore 附件登记模块（魔数嗅探/哈希去重/元数据/清理）"
```

---

## Task 3: Provider 图片块（OpenAI + Claude）

**Files:**
- Modify: `aiagent/provider-openai/src/main/java/cn/tianlong/tlobject/aiagent/provider/openai/TLOpenAiProvider.java:62-75`
- Modify: `aiagent/provider-claude/src/main/java/cn/tianlong/tlobject/aiagent/provider/claude/TLClaudeProvider.java:105-142`
- Test: `smoke/VisionSmokeTest.java`

- [ ] **Step 1: 写失败测试**

在 `VisionSmokeTest` 加：

```java
    // ===== Task 3 =====
    static void testProvider(TLObjectFactory factory) {
        HashMap<String, String> cfg = new HashMap<>();
        cfg.put("classfile", "cn.tianlong.tlobject.aiagent.provider.openai.TLOpenAiProvider");
        cfg.put("singleton", "true");
        cfg.put("apiKey", "test-key");
        cfg.put("apiBaseUrl", "https://api.deepseek.com");
        cfg.put("defaultModel", "deepseek-v4-flash");
        TLMsg r = factory.putMsg(factory, factory.createMsg().setAction("getModule")
                .setParam("moduleName", "smokeOpenAiProvider").setParam("moduleConfig", cfg));
        Object provider = r == null ? null : r.getParam("instance");
        check("3.0 provider 实例创建", provider != null);
        if (provider == null) return;

        // 造一张 4x4 PNG 的文件
        try {
            java.io.File tmp = new java.io.File("target/smoke_att").getAbsoluteFile();
            tmp.mkdirs();
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(4, 4,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.io.File png = new java.io.File(tmp, "p3.png");
            javax.imageio.ImageIO.write(img, "png", png);
            String b64 = Base64.getEncoder().encodeToString(
                    java.nio.file.Files.readAllBytes(png.toPath()));

            // 3.1 无图 → content 仍是纯字符串（老路径）
            List<TLConversationHistory> noImg = new ArrayList<>();
            noImg.add(new TLConversationHistory(TLConversationHistory.Role.user, "纯文本"));
            String body1 = invokeBuild(provider, noImg);
            check("3.1 无图：content 为纯字符串",
                    body1.contains("\"content\":\"纯文本\"") && !body1.contains("image_url"));

            // 3.2 有图 → content 为块数组，text 块在前、image_url 块在后
            List<TLConversationHistory> withImg = new ArrayList<>();
            TLConversationHistory h = new TLConversationHistory(TLConversationHistory.Role.user, "这是什么");
            h.setImages(new ArrayList<>(List.of(
                    TLAttachmentRef.forImage(png.getAbsolutePath(), "p3.png", "image/png", "user"))));
            withImg.add(h);
            String body2 = invokeBuild(provider, withImg);
            check("3.2 有图：content 是块数组",
                    body2.contains("\"type\":\"image_url\"")
                            && body2.contains("data:image/png;base64," + b64.substring(0, 24)));
            check("3.2 文本块在前", body2.indexOf("\"type\":\"text\"") < body2.indexOf("\"type\":\"image_url\""));

            // 3.3 文件缺失 → 不炸、不发该图
            TLConversationHistory h3 = new TLConversationHistory(TLConversationHistory.Role.user, "x");
            h3.setImages(new ArrayList<>(List.of(TLAttachmentRef.forImage(
                    new java.io.File(tmp, "nope.png").getAbsolutePath(), "nope.png", "image/png", "tool"))));
            String body3 = invokeBuild(provider, new ArrayList<>(List.of(h3)));
            check("3.3 文件缺失：不炸且不发块", body3 != null && !body3.contains("image_url"));

            // 3.4 非 user 角色带图 → 丢弃图片（防 DeepSeek 400）
            TLConversationHistory h4 = new TLConversationHistory(TLConversationHistory.Role.assistant, "答");
            h4.setImages(new ArrayList<>(List.of(
                    TLAttachmentRef.forImage(png.getAbsolutePath(), "p3.png", "image/png", "tool"))));
            String body4 = invokeBuild(provider, new ArrayList<>(List.of(h4)));
            check("3.4 非 user 角色带图：不发块", body4 != null && !body4.contains("image_url"));

            // 3.5 URL / FILE_ID 透传
            TLConversationHistory h5 = new TLConversationHistory(TLConversationHistory.Role.user, "看");
            h5.setImages(new ArrayList<>(List.of(
                    TLAttachmentRef.forUrl("https://example.com/a.jpg", "api"),
                    TLAttachmentRef.forFileId("file-api-abc", "api"))));
            String body5 = invokeBuild(provider, new ArrayList<>(List.of(h5)));
            check("3.5 URL 透传", body5.contains("\"url\":\"https://example.com/a.jpg\""));
            check("3.5 FILE_ID 透传", body5.contains("\"file_id\":\"file-api-abc\""));

            // 3.6 Kind.FILE 混进 images → 不发块（免得把普通文件当图片 base64 发出去）
            TLConversationHistory h6 = new TLConversationHistory(TLConversationHistory.Role.user, "x");
            h6.setImages(new ArrayList<>(List.of(
                    TLAttachmentRef.forFile(png.getAbsolutePath(), "p3.png", "image/png", "user"))));
            String body6 = invokeBuild(provider, new ArrayList<>(List.of(h6)));
            check("3.6 Kind.FILE 不发图块", body6 != null && !body6.contains("image_url"));
        } catch (Exception e) {
            check("3.x 异常: " + e, false);
        }
    }

    static String invokeBuild(Object provider, List<TLConversationHistory> msgs) {
        try {
            java.lang.reflect.Method m = provider.getClass().getMethod("buildRequestBody",
                    TLMsg.class, List.class, List.class, boolean.class);
            return (String) m.invoke(provider, new TLMsg(), msgs, null, false);
        } catch (Exception e) {
            return "ERROR: " + e;
        }
    }
```

在 `main` 里 `testStore(factory);` 后加 `testProvider(factory);`。

- [ ] **Step 2: 运行确认失败**

Expected: `3.1` PASS（老路径不变），`3.2` FAIL（当前发不出块）

- [ ] **Step 3: 改 TLOpenAiProvider**

Modify `aiagent/provider-openai/src/main/java/cn/tianlong/tlobject/aiagent/provider/openai/TLOpenAiProvider.java` —— 把 `buildRequestBody` 里这段（第 68-72 行）：

```java
            if (h.getContent() != null && !h.getContent().isEmpty()) {
                m.addProperty("content", h.getContent());
            }
```

替换为：

```java
            if (h.hasImages() && h.getRole() == TLConversationHistory.Role.user) {
                // 图片块数组（DeepSeek/OpenAI 兼容）。图片只能出现在 user 消息中，其余角色丢弃防 400
                JsonArray blocks = new JsonArray();
                if (h.getContent() != null && !h.getContent().isEmpty()) {
                    JsonObject tb = new JsonObject();
                    tb.addProperty("type", "text");
                    tb.addProperty("text", h.getContent());
                    blocks.add(tb);
                }
                for (TLAttachmentRef ref : h.getImages()) {
                    JsonObject ib = buildImageBlock(ref);
                    if (ib != null) blocks.add(ib);
                }
                if (blocks.size() > 0) m.add("content", blocks);
            } else if (h.getContent() != null && !h.getContent().isEmpty()) {
                m.addProperty("content", h.getContent());
            }
```

在类中加方法（`buildRequestBody` 之后）：

```java
    /** 单个附件 → image_url / file 块；文件缺失、类型非图片返回 null（调用方丢弃该图） */
    private JsonObject buildImageBlock(TLAttachmentRef ref) {
        if (ref == null || !ref.isImageLike()) return null;   // Kind.FILE 不该被当图 base64 发出去
        if (ref.getKind() == TLAttachmentRef.Kind.URL) {
            JsonObject b = new JsonObject();
            b.addProperty("type", "image_url");
            JsonObject u = new JsonObject();
            u.addProperty("url", ref.getUrl());
            if (ref.getDetail() != null) u.addProperty("detail", ref.getDetail());
            b.add("image_url", u);
            return b;
        }
        if (ref.getKind() == TLAttachmentRef.Kind.FILE_ID) {
            JsonObject b = new JsonObject();
            b.addProperty("type", "file");
            b.addProperty("file_id", ref.getFileId());
            return b;
        }
        byte[] bytes = TLAttachmentStore.readBytes(ref);
        if (bytes == null) {
            putLog("[VISION] 图片文件缺失，已丢弃: " + ref.getPath(), LogLevel.WARN);
            return null;
        }
        String mime = ref.getMime() != null ? ref.getMime() : "image/png";
        JsonObject b = new JsonObject();
        b.addProperty("type", "image_url");
        JsonObject u = new JsonObject();
        u.addProperty("url", "data:" + mime + ";base64," + java.util.Base64.getEncoder().encodeToString(bytes));
        if (ref.getDetail() != null) u.addProperty("detail", ref.getDetail());
        b.add("image_url", u);
        return b;
    }
```

在文件顶部 import 区加：`import cn.tianlong.tlobject.aiagent.TLAttachmentRef;`（若已有 `cn.tianlong.tlobject.aiagent.*` 则不重复）。

- [ ] **Step 4: 改 TLClaudeProvider**

Modify `aiagent/provider-claude/src/main/java/cn/tianlong/tlobject/aiagent/provider/claude/TLClaudeProvider.java` —— 在构建 text block 之后（第 111-116 行区块后）插入图片块：

```java
            if (h.hasImages() && h.getRole() == TLConversationHistory.Role.user) {
                for (TLAttachmentRef ref : h.getImages()) {
                    JsonObject ib = buildClaudeImageBlock(ref);
                    if (ib != null) content.add(ib);
                }
            }
```

在类中加：

```java
    /** 附件 → Anthropic image 块；FILE_ID 在 Claude 侧无对应形态、Kind.FILE 非图片，均降级丢弃 */
    private JsonObject buildClaudeImageBlock(TLAttachmentRef ref) {
        if (ref == null || !ref.isImageLike()) return null;
        if (ref.getKind() == TLAttachmentRef.Kind.FILE_ID) return null;
        JsonObject b = new JsonObject();
        b.addProperty("type", "image");
        JsonObject src = new JsonObject();
        if (ref.getKind() == TLAttachmentRef.Kind.URL) {
            src.addProperty("type", "url");
            src.addProperty("url", ref.getUrl());
        } else {
            byte[] bytes = TLAttachmentStore.readBytes(ref);
            if (bytes == null) {
                putLog("[VISION] 图片文件缺失，已丢弃: " + ref.getPath(), LogLevel.WARN);
                return null;
            }
            src.addProperty("type", "base64");
            src.addProperty("media_type", ref.getMime() != null ? ref.getMime() : "image/png");
            src.addProperty("data", java.util.Base64.getEncoder().encodeToString(bytes));
        }
        b.add("source", src);
        return b;
    }
```

- [ ] **Step 5: 运行测试确认通过**

Run: 环境准备的命令
Expected: `3.1`~`3.5` 全 `[PASS]`

- [ ] **Step 6: 提交**

```bash
git add aiagent/provider-openai aiagent/provider-claude smoke/VisionSmokeTest.java
git commit -m "vision：provider 图片块（OpenAI image_url/file + Claude image block）"
```

---

## Task 4: 发送层（真拷贝 + 清单 + 保留策略 + token 估算）

**Files:**
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAiAgent.java:2633-2683`（buildSendList）、`:2686-2690`（stripImagePayloads 附近）、`:2732-2757`（estimateTokens）
- Test: `smoke/VisionSmokeTest.java`

- [ ] **Step 1: 写失败测试**

在 `VisionSmokeTest` 加（用反射直接测 buildSendList，避免起整个 agent）：

```java
    // ===== Task 4 =====
    static void testSendList(TLObjectFactory factory) throws Exception {
        // 用运行时配置建一个独立 agent 实例（不干扰 conf）
        HashMap<String, String> cfg = new HashMap<>();
        cfg.put("classfile", "cn.tianlong.tlobject.aiagent.TLAiAgent");
        cfg.put("singleton", "true");
        TLMsg r = factory.putMsg(factory, factory.createMsg().setAction("getModule")
                .setParam("moduleName", "smokeAgent").setParam("moduleConfig", cfg));
        Object agent = r == null ? null : r.getParam("instance");
        check("4.0 agent 实例创建", agent != null);
        if (agent == null) return;

        java.lang.reflect.Method m = agent.getClass().getDeclaredMethod("buildSendList",
                List.class, long.class, String.class);
        m.setAccessible(true);

        // 造 3 条带附件/图的历史
        List<TLConversationHistory> full = new ArrayList<>();
        TLConversationHistory u1 = new TLConversationHistory(TLConversationHistory.Role.user, "第一张图");
        u1.setAttachments(new ArrayList<>(List.of(
                TLAttachmentRef.forImage("d:/a/1.png", "1.png", "image/png", "user"))));
        u1.setImages(new ArrayList<>(List.of(
                TLAttachmentRef.forImage("d:/a/1.png", "1.png", "image/png", "tool"))));
        full.add(u1);
        full.add(new TLConversationHistory(TLConversationHistory.Role.assistant, "看到了"));
        TLConversationHistory u2 = new TLConversationHistory(TLConversationHistory.Role.user, "第二张");
        u2.setImages(new ArrayList<>(List.of(
                TLAttachmentRef.forImage("d:/a/2.png", "2.png", "image/png", "tool"))));
        full.add(u2);

        @SuppressWarnings("unchecked")
        List<TLConversationHistory> sent = (List<TLConversationHistory>) m.invoke(agent, full, 0L, null);

        // 4.1 清单拼进带 attachments 的消息
        TLConversationHistory s1 = sent.get(0);
        check("4.1 清单已拼接", s1.getContent() != null
                && s1.getContent().contains("[附件]") && s1.getContent().contains("1.png")
                && s1.getContent().contains("第一张图"));
        // 4.2 保留策略：只留最近 K=1 张注入图
        check("4.2 旧注入图被裁剪", !sent.get(0).hasImages());
        check("4.2 最新注入图保留", sent.get(2).hasImages());
        // 4.3 真拷贝：活上下文 POJO 未被污染
        check("4.3 原件 content 未被清单污染", "第一张图".equals(u1.getContent()));
        check("4.3 原件 images 未被裁剪", u1.hasImages() && u2.hasImages());
        // 4.4 token 估算计入图片
        java.lang.reflect.Method est = agent.getClass().getDeclaredMethod("estimateTokens", List.class);
        est.setAccessible(true);
        long withImages = (Long) est.invoke(agent, sent);
        List<TLConversationHistory> noImages = new ArrayList<>();
        for (TLConversationHistory h : sent) { TLConversationHistory c = h.copy(); c.setImages(null); noImages.add(c); }
        long without = (Long) est.invoke(agent, noImages);
        check("4.4 图片计入 token（≥1024）", withImages - without >= 1024);

        // 4.5 超窗口分支（>contextViewSize）：孤儿 tool 的前缀段也必须拷贝，不能写回活上下文
        java.lang.reflect.Field vsF = agent.getClass().getDeclaredField("contextViewSize");
        vsF.setAccessible(true);
        int VIEW = vsF.getInt(agent);            // 不硬编码：默认值可能变
        List<TLConversationHistory> longHist = new ArrayList<>();
        longHist.add(new TLConversationHistory(TLConversationHistory.Role.assistant,
                new ArrayList<>(List.of(new TLToolCall("call_x", "some_tool", new LinkedHashMap<>())))));
        longHist.add(new TLConversationHistory("call_x", "some_tool", "工具结果"));   // 下标 1 = 窗口头
        while (longHist.size() < VIEW) {
            longHist.add(new TLConversationHistory(TLConversationHistory.Role.user, "填充"));
        }
        TLConversationHistory tail = new TLConversationHistory(TLConversationHistory.Role.user, "尾部");
        tail.setAttachments(new ArrayList<>(List.of(
                TLAttachmentRef.forImage("d:/a/t.png", "t.png", "image/png", "user"))));
        longHist.add(tail);                      // 共 VIEW+1 条 → startIdx 正好落在下标 1 的 tool 上

        @SuppressWarnings("unchecked")
        List<TLConversationHistory> sent2 =
                (List<TLConversationHistory>) m.invoke(agent, longHist, 0L, null);
        check("4.5 超窗口：窗口头补回 assistant 前缀",
                !sent2.isEmpty() && sent2.get(0).getRole() == TLConversationHistory.Role.assistant);
        check("4.5 超窗口：活上下文未被写回污染",
                "尾部".equals(tail.getContent()) && tail.hasAttachments() && !tail.hasImages());
        check("4.5 超窗口：清单仍被拼接",
                sent2.get(sent2.size() - 1).getContent().contains("[附件]"));
    }
```

⚠ `buildSendList` 是 `protected`、`estimateTokens` 是 `private`——反射已 `setAccessible(true)`；`buildSendList` 用 `getDeclaredMethod` 而非 `getMethod`。

在 `main` 里加 `testSendList(factory);`。

- [ ] **Step 2: 运行确认失败**

Expected: `4.1` FAIL（清单未拼接）、`4.3` FAIL（原件被污染）

- [ ] **Step 3: buildSendList 真拷贝**

Modify `TLAiAgent.java` —— 第 2635-2644 行：

```java
        if (full.size() <= contextViewSize) {
            List<TLConversationHistory> all = new ArrayList<>(full);
            if (memoryContext != null && !memoryContext.isEmpty()) {
                all.add(new TLConversationHistory(TLConversationHistory.Role.system, memoryContext));
            }
            sanitizeToolPairs(all);
            stripImagePayloads(all);
            trimToContextBudget(all);
            return all;
        }
```

改为（`new ArrayList<>(full)` → 逐元素 copy）：

```java
        if (full.size() <= contextViewSize) {
            // 逐元素真拷贝：发送期的清单拼接/裁剪/剥离只作用副本，不写回活上下文
            List<TLConversationHistory> all = copyList(full);
            if (memoryContext != null && !memoryContext.isEmpty()) {
                all.add(new TLConversationHistory(TLConversationHistory.Role.system, memoryContext));
            }
            applyManifest(all);
            applyImageRetention(all);
            sanitizeToolPairs(all);
            stripImagePayloads(all);
            trimToContextBudget(all);
            return all;
        }
```

第 2654 行附近 `int startIdx = Math.max(0, rest.size() - contextViewSize);` 之前的循环 —— `systems` / `rest` 两个列表用 `getContextHistory` 来的原对象，改成拷贝**并同时记下每个元素在 `full` 里的原始下标**：

```java
        List<TLConversationHistory> systems = new ArrayList<>();
        List<TLConversationHistory> rest = new ArrayList<>();
        List<Integer> restOrigIdx = new ArrayList<>();     // rest[i] 对应 full 里的下标
        for (int i = 0; i < full.size(); i++) {
            TLConversationHistory h = full.get(i);
            if (h.getRole() == TLConversationHistory.Role.system) {
                systems.add(h.copy());
            } else {
                rest.add(h.copy());
                restOrigIdx.add(i);
            }
        }
```

⚠ `full.indexOf(first)`（第 2663 行）依赖对象身份——`rest` 里换成拷贝后 `indexOf` 必然失配。
**不要用 seq 匹配来补救**：`setSeq` 只在 `TLAiContext.addMessage` 里调用，而 `CONTEXT_ADDMESSAGE`
全仓零调用（agent 走 `CONTEXT_REPLACE`，`replace()` 只读 seq 不赋值），所以活上下文里 agent 造的消息
seq 恒为 0，按 seq 匹配会命中最旧的那条而不是窗口边界那条。改用上面记下的原始下标：

```java
            TLConversationHistory first = rest.get(startIdx);
            int idxInFull = restOrigIdx.get(startIdx);     // ← 直接用记下的原始下标
            if (idxInFull > 0) {
                for (int i = idxInFull - 1; i >= 0; i--) {
                    TLConversationHistory h = full.get(i);
                    if (h.isAssistantWithToolCalls()) {
                        List<TLConversationHistory> prefix = copyList(full.subList(i, idxInFull));
                        rest.addAll(startIdx, prefix);
                        for (int k = 0; k < prefix.size(); k++) restOrigIdx.add(startIdx + k, i + k);
                        // ⚠ 不要加回 startIdx += prefix.size()（2026-10-05 实测结论）：
                        //   加上它，result.subList(startIdx,...) 正好跳过刚插入的前缀 → 整段借回变成空操作，
                        //   孤儿 tool 反而被 sanitizeToolPairs 删掉；而且它是 9/5 死循环事故的前置条件
                        //   （if 被改回 while 时 startIdx 会重新落在同一 tool 上无限插入）。保持不加。
                        break;
                    }
                }
            }
```

⚠ 前缀段也必须 `copyList(...)`（原代码是 `new ArrayList<>(full.subList(...))`，取的是活对象）——
否则后续 `applyManifest` / `applyImageRetention` / `trimToContextBudget` 会就地改写它们，
**C4 就在这条分支上被绕过**。

结果列表构造处（第 2677-2682 行）加两行：

```java
        List<TLConversationHistory> result = new ArrayList<>(systems);
        result.addAll(rest.subList(Math.max(0, startIdx), rest.size()));
        applyManifest(result);
        applyImageRetention(result);
        sanitizeToolPairs(result);
        stripImagePayloads(result);
        trimToContextBudget(result);
        return result;
```

加辅助方法（放在 `buildSendList` 之后）：

```java
    private static List<TLConversationHistory> copyList(List<TLConversationHistory> src) {
        List<TLConversationHistory> out = new ArrayList<>(src.size());
        for (TLConversationHistory h : src) out.add(h.copy());
        return out;
    }

    /**
     * 把 attachments 渲染成清单文本拼在消息正文前（只在发送副本上做，不写回存储）。
     * 清单是"模型必须先知道有什么"的唯一来源；元数据全部本地抽取，不调 LLM。
     */
    private void applyManifest(List<TLConversationHistory> list) {
        for (TLConversationHistory h : list) {
            if (!h.hasAttachments()) continue;
            String manifest = buildManifest(h.getAttachments());
            if (manifest.isEmpty()) continue;
            String body = h.getContent() == null ? "" : h.getContent();
            h.setContent(manifest + "\n" + body);
        }
    }

    private String buildManifest(List<TLAttachmentRef> refs) {
        if (refs == null || refs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("[附件]\n");
        int shown = 0;
        for (TLAttachmentRef r : refs) {
            if (shown >= manifestMaxEntries) {
                sb.append("…还有 ").append(refs.size() - shown).append(" 个\n");
                break;
            }
            shown++;
            sb.append(shown).append(". ").append(sanitizeFileName(r.getName()))
              .append(" — ").append(r.getMime() == null ? "未知类型" : r.getMime())
              .append(", ").append(humanSize(r.getSize()));
            Map<String, Object> meta = r.getMeta();
            if (meta != null) {
                // ⚠ gson 往返后所有数字都变成 Double（ObjectTypeAdapter）——一律经 num() 取整，
                //   否则恢复会话后清单会渲染成 "1043.0 行"
                Integer w = num(meta.get("width")), hgt = num(meta.get("height"));
                if (w != null && w > 0) sb.append(", ").append(w).append("×").append(hgt);
                Integer rows = num(meta.get("rows"));
                if (rows != null) sb.append(", ").append(rows).append(" 行");
                if (meta.get("columns") != null) sb.append(", 列: ").append(meta.get("columns"));
                Integer chars = num(meta.get("chars"));
                if (chars != null) sb.append(", ").append(chars).append(" 字符");
                Integer pages = num(meta.get("pages"));
                if (pages != null) sb.append(", ").append(pages).append(" 页");
            }
            sb.append(" — 路径: ").append(r.getPath() == null ? r.getUrl() : r.getPath());
            sb.append("\n");
        }
        sb.append(refs.size() == 1 ? "查看图片内容请调用 view_image(路径)；其他文件可直接用路径本地处理。" :
                "需要查看某张图片时调用 view_image(路径)；其他文件可直接用路径本地处理。");
        return sb.toString();
    }

    /** 文件名进清单前转义：防"文件名里写指令" */
    private static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "未命名";
        String s = name.replaceAll("[\\r\\n\\t\\u0000-\\u001f\\[\\]]", " ");
        return s.length() > 64 ? s.substring(0, 64) + "…" : s;
    }

    private static String humanSize(long size) {
        if (size < 1024) return size + "B";
        if (size < 1024 * 1024) return (size / 1024) + "KB";
        return String.format("%.1fMB", size / 1024.0 / 1024.0);
    }

    /** meta 值本轮是 Integer、gson 反序列化后是 Double，统一经 Number 取整 */
    private static Integer num(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : null;
    }

    /** 注入图保留策略：只保留最近 K 条带图消息的图片，更早的置空（文本保留） */
    private void applyImageRetention(List<TLConversationHistory> list) {
        int seen = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            TLConversationHistory h = list.get(i);
            if (!h.hasImages()) continue;
            seen++;
            if (seen > maxImagesInContext) h.setImages(null);
        }
    }
```

- [ ] **Step 4: estimateTokens 计入图片**

Modify `TLAiAgent.java` `estimateTokens`（第 2732-2757 行）—— 在 `if (h.getName() != null)` 之前插入：

```java
            if (h.hasImages()) {
                // 手册：每张图折算封顶 1024 token，按上限保守计
                toks += (long) h.getImages().size() * 1024L;
            }
```

- [ ] **Step 5: 加配置字段**

Modify `TLAiAgent.java` 字段区（`dataBasePath` 附近，第 167 行）加：

```java
    /** 清单最多列几条附件 */
    protected int manifestMaxEntries = 20;
    /** 发送视图保留最近 K 条带图消息 */
    protected int maxImagesInContext = 1;
```

在 `setModuleParams()` 里加（与其它 `params.get(...)` 并列）：

```java
            if (params.get("manifestMaxEntries") != null) {
                try { manifestMaxEntries = Integer.parseInt(params.get("manifestMaxEntries")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("maxImagesInContext") != null) {
                try { maxImagesInContext = Integer.parseInt(params.get("maxImagesInContext")); }
                catch (NumberFormatException ignored) {}
            }
```

- [ ] **Step 6: 运行测试确认通过**

Run: 环境准备的命令
Expected: `4.1`~`4.4` 全 `[PASS]`

- [ ] **Step 7: 全量回归**

Run: `mvn -q -f pom.xml -pl aiagent/common,aiagent/provider-openai,aiagent/provider-claude compile`
Expected: BUILD SUCCESS（无编译错误）
再手动跑一次 aistart 控制台，输入 `/test`，Expected: 9/9 场景通过（确认无回归）

- [ ] **Step 8: 提交**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAiAgent.java smoke/VisionSmokeTest.java
git commit -m "vision：发送层真拷贝 + 附件清单 + 注入图保留策略 + 图片 token 估算

顺带修正 buildSendList 浅拷贝——此前 trimToContextBudget 的截断会写回活上下文"
```

---

## Task 5: doChat 接入附件 + agentService 透传

**Files:**
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAiAgent.java:1006`
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAgentService.java:310-312`
- Test: `smoke/VisionSmokeTest.java`

- [ ] **Step 1: 写失败测试**

在 `VisionSmokeTest` 加（反射直测 `normalizeAttachments` 四形态归一化——`doChat` 里那 3 行接线由 Task 8 的真 LLM 端到端覆盖）：

```java
    // ===== Task 5 =====
    static void testNormalizeAttachments(TLObjectFactory factory) throws Exception {
        HashMap<String, String> cfg = new HashMap<>();
        cfg.put("classfile", "cn.tianlong.tlobject.aiagent.TLAiAgent");
        cfg.put("singleton", "true");
        TLMsg ra = factory.putMsg(factory, factory.createMsg().setAction("getModule")
                .setParam("moduleName", "smokeAgent5").setParam("moduleConfig", cfg));
        Object agent = ra == null ? null : ra.getParam("instance");
        check("5.0 agent 实例创建", agent != null);
        if (agent == null) return;

        java.io.File tmp = new java.io.File("target/smoke_att").getAbsoluteFile();
        tmp.mkdirs();
        java.io.File png = new java.io.File(tmp, "t5.png");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4, 4,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png);

        java.lang.reflect.Method m = agent.getClass().getDeclaredMethod(
                "normalizeAttachments", TLMsg.class, String.class);
        m.setAccessible(true);

        TLMsg msg = ((TLBaseModule) agent).createMsg();
        List<Object> raw = new ArrayList<>();
        raw.add(new LinkedHashMap<>(Map.of("path", png.getAbsolutePath(), "name", "t5.png", "origin", "api")));
        raw.add(new LinkedHashMap<>(Map.of("url", "https://example.com/a.jpg", "name", "a.jpg")));
        raw.add(new LinkedHashMap<>(Map.of("fileId", "file-api-xyz")));
        raw.add(new LinkedHashMap<>(Map.of("url", "ftp://bad/x.jpg")));          // 非 http(s) 应被忽略
        raw.add(new LinkedHashMap<>(Map.of("path", "no/such/file.png")));        // 不存在应被忽略
        msg.setParam(AI_P_ATTACHMENTS, raw);

        @SuppressWarnings("unchecked")
        List<TLAttachmentRef> refs = (List<TLAttachmentRef>) m.invoke(agent, msg, "smoke_u1");

        check("5.1 四形态归一化出 3 条", refs != null && refs.size() == 3);
        check("5.2 path → IMAGE（魔数判定）", refs.get(0).getKind() == TLAttachmentRef.Kind.IMAGE
                && png.getAbsolutePath().equals(refs.get(0).getPath()));
        check("5.3 url → URL", refs.get(1).getKind() == TLAttachmentRef.Kind.URL);
        check("5.4 fileId → FILE_ID", refs.get(2).getKind() == TLAttachmentRef.Kind.FILE_ID);
        check("5.5 非 http(s) URL 与不存在路径被忽略", refs.size() == 3);
    }
```

在 `main` 里加 `testNormalizeAttachments(factory);`。

- [ ] **Step 2: 运行确认失败**

Expected: 反射找不到方法 `normalizeAttachments`（`NoSuchMethodException`），或编译期报错

- [ ] **Step 3: 三个 user 消息构造点接入附件**

⚠ **必须改 3 处**——`chatStream` 是**独立入口**，它构造 user 消息的位置与 `doChat` 不同。
只改 `doChat` 会让流式对话（webui / 控制台 `/stream`，即主路径）完全拿不到附件。

先确认全部站点：

```bash
grep -n "new TLConversationHistory(TLConversationHistory.Role.user, userMessage)" \
  aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAiAgent.java
```
Expected: 3 处 —— `doChat` 的 1006、`chatStream` 的 1683 与 1686。

⓪ 加 agent 级配置字段（附件条数上限在**这里**强制，`attachmentStore` 不掺和——
它的同名配置项已按"不养死旋钮"原则删掉）：

```java
    /** 单条消息最多接受几条附件 */
    protected int maxAttachmentsPerMessage = 10;
```
并在 `setModuleParams()` 里加：
```java
            if (params.get("maxAttachmentsPerMessage") != null) {
                try { maxAttachmentsPerMessage = Integer.parseInt(params.get("maxAttachmentsPerMessage")); }
                catch (NumberFormatException ignored) {}
            }
```

① 在 `doChat` 里读取本轮附件（第 782 行 `String userMessage = msg.getStringParam(AI_P_USERMESSAGE, "");` 之后）：

```java
        List<TLAttachmentRef> roundAttachments = normalizeAttachments(msg, sessionId);
```

② 加统一构造方法（放在 `normalizeAttachments` 旁）：

```java
    /** 构造本轮用户消息条目并挂上附件（三个入口共用：doChat / chatStream 发送列表 / chatStream 保存列表） */
    private TLConversationHistory buildUserEntry(String userMessage, List<TLAttachmentRef> atts) {
        TLConversationHistory e =
                new TLConversationHistory(TLConversationHistory.Role.user, userMessage);
        if (atts != null && !atts.isEmpty()) e.setAttachments(atts);
        return e;
    }
```

③ 三处替换（缩进按各自上下文）：
- `doChat` 第 1006 行 → `history.add(buildUserEntry(userMessage, roundAttachments));`
- `chatStream` 第 1683 行 → `if (!resume) history.add(buildUserEntry(userMessage, roundAttachments));`
- `chatStream` 第 1686 行 → `if (!resume) saved.add(buildUserEntry(userMessage, roundAttachments));`

`chatStream` 方法作用域里也要有 `roundAttachments`——在该方法读取 `userMessage` 的地方之后加同样一行
（`grep -n "AI_P_USERMESSAGE" aiagent/common/.../TLAiAgent.java` 找到两处读取点）。

⚠ 1686 那处是**写回 context 的保存列表**（`saveContextHistory`）——漏了它附件不会持久化，resume 后清单就丢了。

加方法（放在 `buildManifest` 附近）：

```java
    /** 把调用方传的 AI_P_ATTACHMENTS（List<Map> 四形态）归一化成 ref 列表；解析失败逐条忽略 */
    @SuppressWarnings("unchecked")
    protected List<TLAttachmentRef> normalizeAttachments(TLMsg msg, String sessionId) {
        List<TLAttachmentRef> out = new ArrayList<>();
        Object raw = msg.getParam(AI_P_ATTACHMENTS);
        if (!(raw instanceof List)) return out;
        String userId = sessionUserIds.getOrDefault(sessionId, "default");
        int n = 0;
        for (Object o : (List<Object>) raw) {
            if (!(o instanceof Map)) continue;
            if (++n > maxAttachmentsPerMessage) break;
            Map<String, Object> m = (Map<String, Object>) o;
            try {
                String url = strOf(m.get("url"));
                String fileId = strOf(m.get("fileId"));
                String b64 = strOf(m.get("base64"));
                String path = strOf(m.get("path"));
                String name = strOf(m.get("name"));
                String origin = m.get("origin") == null ? "api" : strOf(m.get("origin"));
                if (!fileId.isEmpty()) {
                    out.add(TLAttachmentRef.forFileId(fileId, origin));
                } else if (!url.isEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        putLog("[VISION] 附件 URL 非 http(s)，已忽略: " + url, LogLevel.WARN);
                        continue;
                    }
                    if (url.length() > 8192) { putLog("[VISION] 附件 URL 过长，已忽略", LogLevel.WARN); continue; }
                    out.add(TLAttachmentRef.forUrl(url, origin));
                } else if (!b64.isEmpty()) {
                    TLMsg r = putMsg(M_ATTACHMENTSTORE, createMsg().setAction(ATTACH_STOREBASE64)
                            .setParam("base64", b64).setParam("mime", strOf(m.get("mime")))
                            .setParam("name", name.isEmpty() ? "image" : name)
                            .setParam("origin", origin).setParam(AI_P_USERID, userId));
                    if (r != null && r.getParam("ref") != null)
                        out.add((TLAttachmentRef) r.getParam("ref"));
                } else if (!path.isEmpty()) {
                    TLMsg r = putMsg(M_ATTACHMENTSTORE, createMsg().setAction(ATTACH_REGISTER)
                            .setParam("config", m).setParam(AI_P_USERID, userId));
                    if (r != null && r.getParam("ref") != null)
                        out.add((TLAttachmentRef) r.getParam("ref"));
                    else putLog("[VISION] 附件登记失败: " + path, LogLevel.WARN);
                }
            } catch (Exception e) {
                putLog("[VISION] 附件解析失败: " + e, LogLevel.WARN);
            }
        }
        return out;
    }

    private static String strOf(Object o) { return o == null ? "" : String.valueOf(o); }
```

- [ ] **Step 4: agentService 透传**

Modify `TLAgentService.java` —— 在第 310-312 行（`if (msg.containsParam(AI_P_MODEL)) ...` 那组透传）后加：

```java
        if (msg.containsParam(AI_P_ATTACHMENTS)) chatMsg.setParam(AI_P_ATTACHMENTS, msg.getParam(AI_P_ATTACHMENTS));
```

（同一处理也要加到 `doChatStream` 对应的透传处——先 `grep -n "AI_P_REASONING_MODE" TLAgentService.java` 找到两处透传点。）

- [ ] **Step 5: 运行测试确认通过**

Run: 环境准备的命令
Expected: `5.1`~`5.3` 全 `[PASS]`

- [ ] **Step 6: 提交**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAiAgent.java \
        aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAgentService.java \
        smoke/VisionSmokeTest.java
git commit -m "vision：doChat 接入附件（四形态归一化）+ agentService 透传"
```

---

## Task 6: 工具产图声明链路（技能表态 + agent 搬运）

**Files:**
- Modify: `aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAiAgent.java:1318-1321`、`:1334-1337`、`:2887-2894`
- Test: `smoke/VisionSmokeTest.java`

- [ ] **Step 1: 写失败测试**

在 `VisionSmokeTest` 加：

```java
    // ===== Task 6 =====
    static void testToolImageDeclaration(TLObjectFactory factory) throws Exception {
        HashMap<String, String> cfg = new HashMap<>();
        cfg.put("classfile", "cn.tianlong.tlobject.aiagent.TLAiAgent");
        cfg.put("singleton", "true");
        TLMsg ra = factory.putMsg(factory, factory.createMsg().setAction("getModule")
                .setParam("moduleName", "smokeAgent6").setParam("moduleConfig", cfg));
        Object agent = ra == null ? null : ra.getParam("instance");
        if (agent == null) { check("6.0 agent 创建", false); return; }

        // ⚠ image_path 形态会经 register，而 register 有 isUnder(dataBasePath) 越界检查 →
        //    文件必须落在 data/{uid}/ 下；顺带 target/smoke_att 留一个同名文件做"白名单外"反例
        java.io.File dataDir = new java.io.File("data/smoke_u1/uploads").getAbsoluteFile();
        dataDir.mkdirs();
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(4, 4,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.File png = new java.io.File(dataDir, "shot.png");
        javax.imageio.ImageIO.write(img, "png", png);
        java.io.File tmp = new java.io.File("target/smoke_att").getAbsoluteFile();
        tmp.mkdirs();
        String b64 = Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(png.toPath()));

        java.lang.reflect.Method ex = agent.getClass().getDeclaredMethod("extractDeclaredImages",
                String.class, String.class, List.class);
        ex.setAccessible(true);

        // 白名单历史：shot.png 是本会话已登记的附件
        List<TLConversationHistory> hist = new ArrayList<>();
        TLConversationHistory uh = new TLConversationHistory(TLConversationHistory.Role.user, "看图");
        uh.setAttachments(new ArrayList<>(List.of(
                TLAttachmentRef.forImage(png.getAbsolutePath(), "shot.png", "image/png", "user"))));
        hist.add(uh);

        // 6.1 无声明 → 不提取
        String noDecl = "{\"ok\":true,\"screenshot_base64\":\"" + b64 + "\"}";
        check("6.1 无声明不提取", ex.invoke(agent, noDecl, "smoke_u1", hist) == null);

        // 6.2 有声明 → 提取成 ref 并落盘
        String decl = "{\"ok\":true,\"screenshot_base64\":\"" + b64 + "\",\"image_for_model\":true}";
        Object got = ex.invoke(agent, decl, "smoke_u1", hist);
        check("6.2 声明后提取到图片", got instanceof List && ((List<?>) got).size() == 1);

        // 6.3 声明 + image_path（在白名单内）→ 零拷贝引用原文件
        String pathDecl = "{\"image_path\":\"" + png.getAbsolutePath().replace("\\", "\\\\")
                + "\",\"image_for_model\":true}";
        Object got2 = ex.invoke(agent, pathDecl, "smoke_u1", hist);
        check("6.3 image_path 零拷贝", got2 instanceof List && ((List<?>) got2).size() == 1
                && png.getAbsolutePath().equals(((TLAttachmentRef) ((List<?>) got2).get(0)).getPath()));

        // 6.3b 白名单外的路径 → 拒绝注入（view_image 越权到此为止）
        java.io.File outside = new java.io.File(tmp, "outside.png");
        javax.imageio.ImageIO.write(img, "png", outside);
        String badDecl = "{\"image_path\":\"" + outside.getAbsolutePath().replace("\\", "\\\\")
                + "\",\"image_for_model\":true}";
        check("6.3b 白名单外路径被拒绝", ex.invoke(agent, badDecl, "smoke_u1", hist) == null);

        // 6.4 超大输出不解析（护栏）
        StringBuilder huge = new StringBuilder("{\"image_for_model\":true,\"image_base64\":\"");
        huge.append("A".repeat(9 * 1024 * 1024)).append("\"}");
        check("6.4 超 maxParseKB 不解析", ex.invoke(agent, huge.toString(), "smoke_u1", hist) == null);

        // 6.5 文本里 base64 被替换成占位符，其余保留
        java.lang.reflect.Method strip = agent.getClass().getDeclaredMethod("stripImageKeys", String.class);
        strip.setAccessible(true);
        String stripped = (String) strip.invoke(agent, decl);
        check("6.5 base64 被剥离", stripped != null && !stripped.contains(b64)
                && stripped.contains("占位") || stripped.contains("["));
    }
```

在 `main` 里加 `testToolImageDeclaration(factory);`。

- [ ] **Step 2: 运行确认失败**

Expected: 编译失败 `找不到符号: 方法 extractDeclaredImages`

- [ ] **Step 3: 实现提取与剥离**

Modify `TLAiAgent.java` —— 加字段与配置：

```java
    /** 工具产图安全阀：false = 任何工具产图都不注入（判断权在技能，这里只是兜底全禁） */
    protected boolean feedToolImages = true;
    /** 技能输出 JSON 里的表态键：值为 true 才投喂 */
    protected String imageDeclareKey = "image_for_model";
    /** 图数据 key 白名单：以 _path 结尾的按路径引用，其余按 base64 正文 */
    protected List<String> imageDataKeys =
            new ArrayList<>(Arrays.asList("screenshot_base64", "image_base64", "image_path"));
    /** 超过此 KB 数的输出不做图片解析 */
    protected int maxParseKB = 8192;
```

在 `setModuleParams()` 加：

```java
            if (params.get("feedToolImages") != null)
                feedToolImages = !"false".equals(params.get("feedToolImages"));
            if (params.get("imageDeclareKey") != null) imageDeclareKey = params.get("imageDeclareKey");
            if (params.get("imageDataKeys") != null) {
                imageDataKeys = new ArrayList<>();
                for (String k : params.get("imageDataKeys").split(";")) if (!k.trim().isEmpty()) imageDataKeys.add(k.trim());
            }
            if (params.get("maxParseKB") != null) {
                try { maxParseKB = Integer.parseInt(params.get("maxParseKB")); }
                catch (NumberFormatException ignored) {}
            }
```

加方法：

```java
    /**
     * 从工具输出里提取"技能表态要给模型看"的图片。
     * 缺省不给（保守默认）——技能必须在输出 JSON 里写 "image_for_model": true。
     * 返回 null 表示"未声明或解析不可用"，调用方保持原文本不动。
     */
    @SuppressWarnings("unchecked")
    protected List<TLAttachmentRef> extractDeclaredImages(String output, String userId,
                                                          List<TLConversationHistory> history) {
        if (!feedToolImages || output == null) return null;
        if (output.length() > (long) maxParseKB * 1024) return null;         // 护栏 2
        if (output.isEmpty() || output.charAt(0) != '{') return null;        // 护栏 1
        if (output.indexOf(imageDeclareKey) < 0) return null;                // 护栏 1
        try {
            com.google.gson.JsonObject o = gson.fromJson(output, com.google.gson.JsonObject.class);
            if (o == null || !o.has(imageDeclareKey)) return null;
            if (!o.get(imageDeclareKey).getAsBoolean()) return null;
            for (String key : imageDataKeys) {
                if (!o.has(key) || o.get(key).isJsonNull()) continue;
                String v = o.get(key).getAsString();
                if (key.endsWith("_path")) {
                    // 引用型：只能看"本会话已登记的附件"（白名单），防模型被诱导去读任意文件
                    if (!isRegisteredPath(history, v)) {
                        putLog("[VISION] 路径不在本会话附件白名单内，拒绝注入: " + v, LogLevel.WARN);
                        return null;
                    }
                    TLMsg r = putMsg(M_ATTACHMENTSTORE, createMsg().setAction(ATTACH_REGISTER)
                            .setParam("config", new LinkedHashMap<>(Map.of(
                                    "path", v, "name", new File(v).getName(), "origin", "tool")))
                            .setParam(AI_P_USERID, userId));
                    if (r != null && r.getParam("ref") != null)
                        return new ArrayList<>(List.of((TLAttachmentRef) r.getParam("ref")));
                    return null;
                }
                TLMsg r = putMsg(M_ATTACHMENTSTORE, createMsg().setAction(ATTACH_STOREBASE64)
                        .setParam("base64", v).setParam("name", "tool_image")
                        .setParam("origin", "tool").setParam(AI_P_USERID, userId));
                if (r != null && r.getParam("ref") != null)
                    return new ArrayList<>(List.of((TLAttachmentRef) r.getParam("ref")));
                return null;
            }
        } catch (Exception e) {
            putLog("[VISION] 工具产图解析失败（忽略，原文照旧）: " + e, LogLevel.DEBUG);  // 护栏 3
        }
        return null;
    }

    /** 把已提取的图数据 key 从文本里换成占位符（避免同一份 base64 在上下文重复占位） */
    protected String stripImageKeys(String output) {
        if (output == null) return null;
        String out = output;
        for (String key : imageDataKeys) {
            if (key.endsWith("_path")) continue;   // 路径保留：模型/其它工具还要用
            int i = out.indexOf("\"" + key + "\"");
            while (i >= 0) {
                int start = out.indexOf('"', out.indexOf(':', i) + 1);
                if (start < 0) break;
                int end = start + 1;
                while (end < out.length()) {
                    char c = out.charAt(end);
                    if (c == '\\') { end += 2; continue; }
                    if (c == '"') break;
                    end++;
                }
                if (end >= out.length()) break;
                out = out.substring(0, start) + "\"[已提取为图片]\"";
                i = out.indexOf("\"" + key + "\"", out.length() - (out.length() - (start + 1)));
                if (i < 0) break;
            }
        }
        return out;
    }
```

⚠ `stripImageKeys` 的字符串定位逻辑易错——实现时用 gson 解析后重新序列化更稳：**改用它**（更简单且正确）：

```java
    /** 把已提取的图数据 key 从文本里换成占位符（避免同一份 base64 在上下文重复占位） */
    protected String stripImageKeys(String output) {
        if (output == null) return null;
        try {
            com.google.gson.JsonObject o = gson.fromJson(output, com.google.gson.JsonObject.class);
            if (o == null) return output;
            boolean changed = false;
            for (String key : imageDataKeys) {
                if (key.endsWith("_path")) continue;   // 路径保留：模型/其它工具还要用
                if (o.has(key) && !o.get(key).isJsonNull()) {
                    o.addProperty(key, "[已提取为图片]");
                    changed = true;
                }
            }
            return changed ? gson.toJson(o) : output;
        } catch (Exception e) {
            return output;
        }
    }

    /** 白名单：本会话历史里出现过的附件路径（attachments ∪ images 两个字段的并集） */
    private static boolean isRegisteredPath(List<TLConversationHistory> history, String path) {
        if (history == null || path == null) return false;
        String target = canonical(path);
        for (TLConversationHistory h : history) {
            for (List<TLAttachmentRef> list : Arrays.asList(h.getAttachments(), h.getImages())) {
                if (list == null) continue;
                for (TLAttachmentRef r : list) {
                    if (r.getPath() != null && canonical(r.getPath()).equals(target)) return true;
                }
            }
        }
        return false;
    }

    private static String canonical(String p) {
        try { return new File(p).getCanonicalPath(); }
        catch (Exception e) { return new File(p).getAbsolutePath(); }
    }
```

- [ ] **Step 4: 收敛三处写入点**

Modify `TLAiAgent.java` —— 三处 `history.add(new TLConversationHistory(r.toolCallId, r.toolCallId, r.output));`（1319 / 1335 / 2889 行）**全部**替换为：

```java
                                appendToolResult(history, r, sessionId);
```

（1319 与 1335 行缩进为 32 空格，2889 行为 16 空格——按各自上下文保持缩进；`sessionId` 用该作用域内已有的会话变量。）

加方法：

```java
    /** 工具结果写 history 的唯一点：按技能声明决定是否把图注入为独立 user 消息 */
    private void appendToolResult(List<TLConversationHistory> history,
                                  TLToolExecutor.ToolResult r, String sessionId) {
        String userId = sessionUserIds.getOrDefault(sessionId, "default");
        List<TLAttachmentRef> imgs = extractDeclaredImages(r.output, userId, history);
        history.add(new TLConversationHistory(r.toolCallId, r.toolCallId,
                imgs != null ? stripImageKeys(r.output) : r.output));
        if (imgs != null && !imgs.isEmpty()) {
            // 图片只能出现在 user 消息中（DeepSeek 400）→ 注入独立 user 消息
            TLConversationHistory m = new TLConversationHistory(
                    TLConversationHistory.Role.user, "[工具结果：图片]");
            m.setImages(imgs);
            history.add(m);
        }
    }
```

⚠ 2889 行那处之后紧跟 `pushStreamToolEvent(sessionId, ..., r.output)` —— **保持传 `r.output`（原始含 base64）不变**，实时渲染链路依赖它。

- [ ] **Step 5: 运行测试确认通过**

Run: 环境准备的命令
Expected: `6.1`~`6.5` 全 `[PASS]`

- [ ] **Step 6: 提交**

```bash
git add aiagent/common/src/main/java/cn/tianlong/tlobject/aiagent/TLAiAgent.java smoke/VisionSmokeTest.java
git commit -m "vision：工具产图按技能声明注入（image_for_model 表态 + appendToolResult 收敛三处）"
```

---

## Task 7: TLViewImageSkill（模型按需取图）

**Files:**
- Create: `aiagent/skill-builtin/src/main/java/cn/tianlong/tlobject/aiagent/skill/builtin/TLViewImageSkill.java`
- Modify: `demo/tlobject/src/main/resources/conf/tlobject/cn.tianlong.tlobject.aiagent_config.xml`
- Modify: `demo/tlobject/src/main/resources/conf/demo/aiagent/aiagent_master_config.xml`
- Test: `smoke/VisionSmokeTest.java`

- [ ] **Step 1: 写失败测试**

```java
    // ===== Task 7 =====
    static void testViewImageSkill(TLObjectFactory factory) throws Exception {
        // 图片放进"假 data 根"的 default 用户目录下，避免污染真实 data/
        java.io.File root = new java.io.File("target/smoke_data/default").getAbsoluteFile();
        root.mkdirs();
        java.io.File png = new java.io.File(root, "view.png");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(8, 6,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png);

        HashMap<String, String> cfg = new HashMap<>();
        cfg.put("classfile", "cn.tianlong.tlobject.aiagent.skill.builtin.TLViewImageSkill");
        cfg.put("singleton", "true");
        HashMap<String, String> prm = new HashMap<>();
        prm.put("allowedRootPath", "target/smoke_data");
        TLMsg r = factory.putMsg(factory, factory.createMsg().setAction("getModule")
                .setParam("moduleName", "smokeViewImageSkill").setParam("moduleConfig", cfg)
                .setParam("params", prm));
        Object skill = r == null ? null : r.getParam("instance");
        check("7.0 技能实例创建", skill != null);
        if (skill == null) return;

        Map<String, Object> in = new LinkedHashMap<>();
        in.put("path", png.getAbsolutePath());
        TLMsg out = ((TLBaseModule) skill).putMsg((TLBaseModule) skill,
                ((TLBaseModule) skill).createMsg().setAction(SKILL_EXECUTE)
                        .setParam(AI_P_SKILLINPUT, in));
        String so = out == null ? null : out.getStringParam(AI_P_SKILLOUTPUT, "");
        check("7.1 输出含 image_path 与声明", so != null
                && so.contains("\"image_for_model\":true")
                && so.contains(png.getAbsolutePath().replace("\\", "\\\\")));

        // 7.2 不存在的路径报错而非抛异常
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("path", new java.io.File(root, "nope.png").getAbsolutePath());
        TLMsg out2 = ((TLBaseModule) skill).putMsg((TLBaseModule) skill,
                ((TLBaseModule) skill).createMsg().setAction(SKILL_EXECUTE)
                        .setParam(AI_P_SKILLINPUT, bad));
        check("7.2 缺文件返回错误", out2 != null
                && out2.getStringParam(AI_P_SKILLOUTPUT, "").toLowerCase().contains("not found"));

        // 7.2b 用户目录之外的路径被拒（该用户目录外的文件）
        java.io.File outside = new java.io.File("target/smoke_att/outside7.png").getAbsoluteFile();
        outside.getParentFile().mkdirs();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4, 4,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", outside);
        Map<String, Object> esc = new LinkedHashMap<>();
        esc.put("path", outside.getAbsolutePath());
        TLMsg out3 = ((TLBaseModule) skill).putMsg((TLBaseModule) skill,
                ((TLBaseModule) skill).createMsg().setAction(SKILL_EXECUTE)
                        .setParam(AI_P_SKILLINPUT, esc));
        check("7.2b 用户目录外被拒", out3 != null
                && out3.getStringParam(AI_P_SKILLOUTPUT, "").toLowerCase().contains("outside"));

        // 7.3 技能信息可查（供 LLM 发现）
        TLMsg info = ((TLBaseModule) skill).putMsg((TLBaseModule) skill,
                ((TLBaseModule) skill).createMsg().setAction(SKILL_GETINFO));
        check("7.3 skillName=view_image", info != null
                && "view_image".equals(info.getStringParam(AI_P_SKILLNAME, "")));
    }
```

- [ ] **Step 2: 运行确认失败**

Expected: 编译失败 `找不到符号: 类 TLViewImageSkill`

- [ ] **Step 3: 实现技能**

Create `aiagent/skill-builtin/src/main/java/cn/tianlong/tlobject/aiagent/skill/builtin/TLViewImageSkill.java`：

```java
package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.*;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * view_image：把已落盘的图片"给模型看"。
 * 技能只返回引用（image_path）+ 表态（image_for_model），由 agent 侧注入为独立 user 消息；
 * 与工具截图回灌走的是同一条机制（§5.5）。
 *
 * 安全：agent 侧会用当前上下文的附件路径白名单校验；技能内再兜底限定在 data/ 目录下。
 *
 * 创建日期：2026/10/5
 * 作者:tianlong
 */
public class TLViewImageSkill extends TLBaseSkill {

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    /** 附件根目录（其下的 {uid}/ 子目录才是可读范围） */
    private String allowedRootPath = "./data";

    public TLViewImageSkill() { super(); }
    public TLViewImageSkill(String name) { super(name); }
    public TLViewImageSkill(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null && params.get("allowedRootPath") != null)
            allowedRootPath = params.get("allowedRootPath");
        if (skillName == null || skillName.isEmpty() || skillName.equals(name))
            skillName = "view_image";
        if (skillDescription == null || skillDescription.isEmpty() || skillDescription.endsWith(" skill"))
            skillDescription = "View an image attachment's actual content. Call with the path shown in the "
                    + "attachment manifest. Only images that were uploaded or produced in this conversation can be viewed.";
        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();
            Map<String, Object> pathProp = new LinkedHashMap<>();
            pathProp.put("type", "string");
            pathProp.put("description", "Image file path (as shown in the [附件] manifest)");
            pathProp.put("required", true);
            parameterSchema.put("path", pathProp);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        Map<String, Object> input =
                (Map<String, Object>) msg.getMapParam(AI_P_SKILLINPUT, new LinkedHashMap<>());
        String path = input.get("path") == null ? "" : String.valueOf(input.get("path"));
        if (path.isEmpty()) return err("Error: path is required");

        File f = new File(path);
        if (!f.isAbsolute()) f = f.getAbsoluteFile();
        if (!f.isFile()) return err("Error: file not found: " + path);
        // 技能内兜底：只允许 当前用户 data/{uid}/ 下的文件
        //（agent 侧白名单更严：只放行本会话已登记的附件路径）
        String userId = String.valueOf(msg.getSystemParam(AI_P_USERID, "default"));
        if (!underUserData(f, userId)) {
            putLog("view_image 拒绝 " + allowedRootPath + "/" + userId + " 之外的路径: "
                    + f.getAbsolutePath(), LogLevel.WARN);
            return err("Error: path is outside the allowed directory");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("image_path", f.getAbsolutePath());
        out.put("image_for_model", true);          // 技能表态：这张图给模型看
        out.put("name", f.getName());
        out.put("ok", true);
        return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLOUTPUT, gson.toJson(out));
    }

    private TLMsg err(String text) {
        return createMsg().setParam(RESULT, false).setParam(AI_P_SKILLOUTPUT, text);
    }

    private boolean underUserData(File f, String userId) {
        try {
            String root = new File(allowedRootPath, userId).getCanonicalPath() + File.separator;
            return f.getCanonicalPath().startsWith(root);
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 4: 注册（两处）**

① `cn.tianlong.tlobject.aiagent_config.xml` 的 `<modules>` 里加：

```xml
        <module name="viewImageSkill" classfile="cn.tianlong.tlobject.aiagent.skill.builtin.TLViewImageSkill"
                singleton="true"/>
```

② `aiagent_master_config.xml` 的 `<skills>` 段加：

```xml
        <skill name="viewImageSkill"
               sameClassAs="viewImageSkill"
               statup="true"/>
```

- [ ] **Step 5: 运行测试确认通过**

Run: 环境准备的命令
Expected: `7.0`~`7.3` 全 `[PASS]`

- [ ] **Step 6: 提交**

```bash
git add aiagent/skill-builtin demo/tlobject/src/main/resources/conf smoke/VisionSmokeTest.java
git commit -m "vision：view_image 技能（按需取图，与工具截图共用注入机制）"
```

---

## Task 8: 端到端验收（真 LLM）

**Files:**
- Test: `smoke/VisionE2E.java`（临时）

- [ ] **Step 1: 写端到端脚本**

Create `smoke/VisionE2E.java` —— 用真实 DeepSeek 跑通"带图问答"：

```java
package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

/** 真 LLM 端到端：造一张带字的图 → 通过 attachments 参数提问 → 断言模型答对图上的字 */
public class VisionE2E implements TLAiAgentParamString {
    public static void main(String[] args) throws Exception {
        File tmp = new File("target/smoke_att").getAbsoluteFile();
        tmp.mkdirs();
        // 造图：白底黑字 "VISION OK 7391"
        BufferedImage img = new BufferedImage(420, 120, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, 420, 120);
        g.setColor(java.awt.Color.BLACK);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 40));
        g.drawString("VISION OK 7391", 20, 75);
        g.dispose();
        File png = new File(tmp, "e2e_text.png");
        javax.imageio.ImageIO.write(img, "png", png);

        // 造 CSV：3 行数据 + 表头
        File csv = new File(tmp, "e2e_sales.csv");
        java.nio.file.Files.write(csv.toPath(),
                "date,sku,amount\n2026-01-01,A,10\n2026-01-02,B,20\n".getBytes("UTF-8"));

        System.setProperty("log4j.configurationFile", "target/classes/conf/demo/aiagent/log4j2.xml");
        TLObjectFactory factory = TLObjectFactory.getInstance(
                "target/classes/conf/demo/aiagent", "moduleFactory_chat_config.xml");
        factory.startFactory(null, null);
        boolean ok = true;
        try {
            // ---- 场景 1：自带图片 → 模型必须真的看到图 ----
            TLMsg chat = factory.createMsg().setAction("chat")
                    .setSystemParam(AI_P_SESSIONID, "vision_e2e_img")
                    .setSystemParam(AI_P_USERID, "e2e")
                    .setParam(AI_P_USERMESSAGE, "附件里那张图写的什么字？只回答字。")
                    .setParam(AI_P_ATTACHMENTS, new ArrayList<>(List.of(
                            new LinkedHashMap<>(Map.of("path", png.getAbsolutePath(),
                                    "name", "e2e_text.png", "origin", "api")))));
            TLMsg resp = factory.putMsg("agentService", chat);
            String answer = resp == null ? "" : resp.getStringParam(AI_P_RESPONSE, "");
            System.out.println("=== 场景1 模型回答 ===\n" + answer);
            boolean ok1 = answer.contains("7391");
            System.out.println(ok1 ? "[PASS] 模型读出了图上的字" : "[FAIL] 未读出 7391");
            ok = ok && ok1;

            // ---- 场景 2：CSV 本地处理 → 模型从清单元数据答行数/列名，不必读文件 ----
            TLMsg chat2 = factory.createMsg().setAction("chat")
                    .setSystemParam(AI_P_SESSIONID, "vision_e2e_csv")
                    .setSystemParam(AI_P_USERID, "e2e")
                    .setParam(AI_P_USERMESSAGE, "附件这个 CSV 有多少行？列名有哪些？")
                    .setParam(AI_P_ATTACHMENTS, new ArrayList<>(List.of(
                            new LinkedHashMap<>(Map.of("path", csv.getAbsolutePath(),
                                    "name", "e2e_sales.csv", "origin", "api")))));
            TLMsg resp2 = factory.putMsg("agentService", chat2);
            String answer2 = resp2 == null ? "" : resp2.getStringParam(AI_P_RESPONSE, "");
            System.out.println("=== 场景2 模型回答 ===\n" + answer2);
            boolean ok2 = (answer2.contains("3") || answer2.contains("三"))
                    && answer2.contains("sku") && answer2.contains("amount");
            System.out.println(ok2 ? "[PASS] 清单元数据足够回答（3 行 / date,sku,amount）"
                    : "[FAIL] 未从清单答出行数与列名");
            ok = ok && ok2;
        } catch (Exception e) {
            e.printStackTrace();
            ok = false;
        } finally {
            factory.shutdown(ok ? 0 : 1);
        }
    }
}
```

- [ ] **Step 2: 编译运行**

```bash
cd /d/tlobjectapp/tlobject
CP=$(sed -n '2p' aistart.bat | sed 's/.*-classpath "//; s/" .*//')
mvn -q -f pom.xml -pl aiagent/common,aiagent/skill-builtin,demo/tlobject compile
javac -encoding UTF-8 -cp "$CP" -d smoke/classes smoke/VisionE2E.java
cd demo/tlobject && java -Dfile.encoding=UTF-8 -cp "D:\tlobjectapp\tlobject\smoke\classes;$CP" cn.tianlong.tlobject.aiagent.VisionE2E
```

Expected: 两个场景都 `[PASS]` —— 场景 1 答出 `7391`；场景 2 答出 3 行 + `date,sku,amount`（**且全程没有把 CSV 内容塞进上下文**，模型是从清单的元数据答的）

⚠ 这会真实调用 DeepSeek API（用 `moduleFactory_chat_config.xml` 里的 key）。

- [ ] **Step 3: 反例验收（不带图时行为不变）**

同一脚本改一次 `AI_P_USERMESSAGE` 为"1+1等于几"、去掉 `AI_P_ATTACHMENTS`，重跑。
Expected: 正常回答，且**没有任何 `image_url` 请求**（可看日志确认）。

- [ ] **Step 4: 回归 `/test`**

```bash
cd /d/tlobjectapp/tlobject && ./aistart.bat   # 或双击；控制台输入 /test
```
Expected: 9/9 场景通过

- [ ] **Step 5: 提交验收记录**

```bash
git add docs/superpowers/specs/2026-10-05-vision-image-input-design.md
git commit -m "vision：真 LLM 端到端验收通过（带图问答 + 无图回归）"
```

---

## Task 9: 清理临时测试 + 文档

- [ ] **Step 1: 删除临时冒烟测试**

```bash
cd /d/tlobjectapp/tlobject
rm -rf smoke/VisionSmokeTest.java smoke/VisionE2E.java smoke/classes smoke/probe_tool_image.py target/smoke_att
rm -rf aiagent/common/target/classes/cn/tianlong/tlobject/aiagent/VisionSmokeTest*.class
```

- [ ] **Step 2: 更新 CLAUDE.md**

在 `CLAUDE.md` 的 `aiagent` 段落加一条要点：

```
- 多模态：`TLConversationHistory` 有 `attachments`（资源引用，驱动发送时清单）/`images`（真正发给模型的图）两字段；
  附件统一经 `attachmentStore` 登记（上传件原地引用、截图哈希落盘）；模型按需调 `view_image` 取图；
  工具产图由**技能自己**在输出 JSON 里 `"image_for_model": true` 表态，agent 只搬运
```

- [ ] **Step 3: 提交**

```bash
git add CLAUDE.md
git commit -m "vision：清理临时冒烟测试 + CLAUDE.md 补多模态要点"
```

---

## 完成标准（Plan 1）

- [ ] 全部 9 个任务完成，smoke 测试全绿后删除
- [ ] `/test` 9 场景回归通过
- [ ] 真 LLM 带图问答答对图上的字；无图路径行为与改动前一致
- [ ] 活上下文不再被发送期改写污染（Task 4 的 4.3 断言）

## Plan 2 待办（不在本计划内）

- ⚠ **webui 历史会把新字段丢掉**：`TLWebChatModule.toJsonable`（约 1071-1081 行）目前只重建
  `{role, content}`，`TLAttachmentRef` 会掉进 `String.valueOf` 变成 `cn.tianlong...@1a2b`。
  Plan 2 必须给 `toJsonable` 补 `attachments`/`images` 的序列化分支（仿它已有的 `toolCalls` 特判写法）
- 控制台 `/img` `/file` 命令（`TLChatConsole.parseCommand` + `TLAgentService`）
- webui：`app.js` 上传文件名随请求带出 + 粘贴 + 历史缩略图；`TLWebChatServlet.handleChat/handleChatStream` 读 `attachments`；`/api/image` 出图路由
- 桌面技能 `screenshot` 表态；浏览器技能 navigate/click/type/scroll 表态
- Python 版浏览器/桌面脚本输出补 `image_for_model` 字段
