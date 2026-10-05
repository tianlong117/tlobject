# 多模态图片输入（vision）设计

日期：2026-10-05
状态：已实施（Plan 1 核心链路 + Plan 2 入口接线均已落地；逐条验收状态见 §11，前端 DOM 渲染未实机验证）

## 1. 目标

让框架具备"模型能读图"的能力，并把**用户上传的文件（含图片）**统一成一种资源。

核心模型：**附件是资源，投喂是决策。**

- 用户上传的文件（图片 / CSV / 文本 / 其他）**不直接进上下文**，先落盘登记为**附件引用**
- 每次发送时本地生成一份**清单**（文件名/类型/大小/本地抽取的元数据/路径）给模型
- 模型根据用户指令**自行决定**要不要真的看内容：
  - 图片 → 调 `view_image(路径)` 才把图片作为视觉输入注入
  - 其他文件 → 把路径交给既有 `file_operation` / `code_execution` **本地处理**，内容根本不进上下文
- **例外：工具运行中产生的图，由技能自己表态是否给模型**（§5.5）。只有产生它的技能知道这张图是
  "这个动作的目的"还是"顺手带的"——**这个判断不该由 agent 用策略去猜，判断权属于技能**：
  - 桌面技能 `screenshot`：图就是这个动作的返回值，**必须给**。不给我等于模型说"让我看看屏幕"、
    我们回它一串它读不懂的 base64；而且桌面**没有 DOM 文本兜底，截图是唯一信息源**
    （桌面 12 个动作里只有 `screenshot` 返回图，其余都不带）
  - 浏览器 navigate/click/type/scroll 顺带的截图：由**浏览器技能自己定**（建议给——computer-use
    需要看到操作结果；技能可另加参数让模型自行关闭，属可选增强）
  - `view_image`：存在意义就是给模型看，表态给

非目标：图像生成；本地 OCR；PDF 渲染成图；自建 DeepSeek Files API 上传客户端（见 §3）。

## 2. 前提事实

### 2.1 模型侧（DeepSeek 官方手册 `api-docs.deepseek.com/zh-cn/guides/vision`）

| 项 | 事实 |
|---|---|
| 模型 | `deepseek-flash` 家族；本仓配置的 `deepseek-v4-flash` 已由 flash 接管，吃图 |
| 请求形态 | `content` 为**块数组**（非纯字符串）：`[{"type":"text","text":...},{"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}]` |
| 三种传图 | ① base64 data URL（计入 48 MiB 请求体）② 外链 http(s) URL（模型自行下载）③ Files API `file_id`（`{"type":"file","file_id":"file-api-xxx"}`，≤64 MiB） |
| **角色约束** | **图片仅支持出现在 `user` 消息中**；system / assistant 带图返回 400。tool 角色手册未点名，待探针（§12） |
| 格式 | JPEG / PNG / GIF / WebP，**按文件实际内容判定**，不看文件名或声明 MIME |
| 限制 | 单图 ≤32 MiB（内联/外链）、请求体 ≤48 MiB、外链 URL ≤8192 字符、单边 ≤8192px、单请求 ≤600 张 |
| token | 每张图按尺寸折算，**封顶 1024 token**（与字节数无关） |
| detail | `low`(512×512) / `high` / `original` / `auto`，可选 |
| Anthropic 兼容 | `/anthropic` 端点用 `{"type":"image","source":{"type":"base64","media_type":...,"data":...}}` |

### 2.2 代码侧硬约束（必须绕开或修正）

| # | 事实 | 影响 |
|---|---|---|
| C1 | `TLConversationHistory.content` 是 `String`，且被 gson 直接序列化存档（JSONL / DB 两个 SessionManager） | 塞非字符串 → gson `StringTypeAdapter` 遇 `BEGIN_ARRAY` 抛异常，**DB 版静默丢整轮、JSONL 版中断后续解析** |
| C2 | 五处对 content 做 String 操作：`trimToContextBudget` / `estimateTokens` / `capToolResult` / `stripImagePayload` / `toString` | content 改类型 → 全部要改 |
| C3 | `stripImagePayload` 现在**故意**把 ≥5000 字符的 base64 从历史抹成占位符 | 本设计要改变的就是"base64 LLM 读不懂"这个前提 |
| C4 | **`buildSendList` 是浅拷贝**：`getMessages` 返回 `new ArrayList<>(history)`，元素仍是活上下文 POJO；`trimToContextBudget` 的 `setContent(...)` 实际会写回活上下文，与"只作用本次发送拷贝"的注释矛盾 | 发送期的任何就地改写（含清单拼接、注入图保留策略）都会**永久污染活上下文** → 必须修正（§7.1） |
| C5 | 工具截图既有约定：技能输出 JSON 里的 `screenshot_base64`（`app.js` 已按同名 key 正则解析渲染） | 工具产图沿用此约定并扩展（§5.5） |
| C6 | webui 上传通道已有（`data/{uid}/uploads`，字段名 `file_i`，50MB 上限），但 `state.uploads` **从未进入对话请求** | 修缺口：上传的文件要登记成附件引用 |
| C7 | `TLWServModule.putStream(fileName, inputStream)` 已按扩展名自动设 `image/png` 等 content-type | 出图几乎零新代码 |
| C8 | `TLToolExecutor` 的设计原则是"盲执行"（executionId 隔离、不做策略） | 附件解析/落盘不放执行器，放 agent 侧 |
| C9 | `TLFileOperationSkill` 路径按 `allowedRootPath`（默认 `.`）解析 + `startsWith` 校验 | **"本地处理"不需要新功能**——清单里给路径，模型就能把路径交给 file_operation / code_execution |

## 3. 关键决断（选型记录）

| 决断 | 选择 | 理由 |
|---|---|---|
| 消息模型 | `content` 保持 `String`，**新增两个并列字段**：`attachments`（资源引用，驱动清单）/ `images`（真正要发出去的图片块） | C1/C2 五处 String 假设**零改动**；老存档反序列化为 null 天然兼容；改 content 类型收益为零 |
| 附件角色 | **资源**：上传不投喂，先登记引用 | 覆盖非图片文件与批量上传；让"本地处理"成为一等公民；避免把不需要的内容塞进上下文 |
| 投喂决策 | **默认全按需**（`attachmentFeedPolicy=ondemand`）；模型调 `view_image` 才注入图片 | 最省上下文、决策最纯粹；代价是"贴图即问"多一跳 → 保留 `auto` 档位可一行切回 |
| 决策依据 | 发送时本地生成**清单**（不调 LLM）：文件名+类型+大小+元数据+路径 | 模型必须先知道"有什么"才能决定"要不要看"；元数据全部本地可算，很多问题（多大/多少行/存哪了）清单直接答完 |
| 工具截图 | **保持自动投喂**（模型自己动作的返回值）+ 保留最近 K 张 | computer-use 每步都要看结果；做成按需则每个动作多一跳（§1 例外条款） |
| 图片存储 | 用户上传**原地引用**（已在 per-user 目录）；自动产物（截图/base64 输入）落 hash store | 复制是纯浪费（50MB 上传再拷一份）；hash 命名对截图有天然去重价值 |
| 传输方式 | **base64 内联为主**；URL 透传；`file_id` 只透传 | ① Files API 的"传一次多次引用"在截图像素逐轮都变的前提下不兑现；② 截图 100~300KB，离 48 MiB 差两个数量级，token 成本与字节数无关；③ 避免远端状态（映射/配额/删除时机）新失败模式；④ `file` block 是 DeepSeek 专有，base64 保持 provider 通用 |
| 工具产图识别 | **约定** key（配置化白名单），两种形态：`*_base64`（正文）/ `image_path`（引用） | Python 系技能（脚本 stdout 即 skillOutput）**无法设 param**，显式协议对它们不可达 → 会分裂成两套约定；该 key 已跨语言存在（4 个技能实现 + app.js） |
| `view_image` 归属 | 新增内置技能（`skill-builtin`），走既有工具链路 | 与其它技能同构；其产图走 §5.5 同一条注入机制，不是新链路 |

## 4. 组件

### 4.1 `TLAttachmentRef`（新增，`aiagent/common`，包 `cn.tianlong.tlobject.aiagent`）

```java
public class TLAttachmentRef implements Serializable {
    public enum Kind { IMAGE, FILE, URL, FILE_ID }   // URL/FILE_ID 仅图片语义
    private Kind kind;
    private String path;      // IMAGE/FILE：绝对路径（原地上传文件 或 attachments 目录）
    private String name;      // 原始文件名（清单展示 + 模型引用）
    private String mime;
    private long size;
    private Map<String, Object> meta;   // width/height · rows/columns · chars · pages（本地抽取）
    private String url;       // Kind.URL
    private String fileId;    // Kind.FILE_ID（DeepSeek Files API，仅透传）
    private String detail;    // low | high | original | auto（图片可选）
    private String origin;    // user | tool | api —— webui 渲染区分气泡样式
}
```

### 4.2 `TLConversationHistory`（改动：+2 字段 + 拷贝构造）

```java
private List<TLAttachmentRef> attachments;  // 资源引用：只驱动清单，不产生图片块
private List<TLAttachmentRef> images;       // 要作为图片块发送的（仅 Kind.IMAGE/URL/FILE_ID）
public boolean hasAttachments() / hasImages();
public TLConversationHistory copy();        // §7.1 真拷贝修正用
```

两个字段职责不重叠：**`attachments` 是"有什么"，`images` 是"发给模型看什么"**。
用户上传的消息只带 `attachments`；`view_image` / 工具截图注入的消息才带 `images`。

### 4.3 `TLAttachmentStore`（新增）：附件的唯一出入口

**本体是模块**（`TLBaseModule`，注册进 `cn.tianlong.tlobject.aiagent_config.xml`，工厂单例）——
调用方（agent 落工具产物、webui 登记上传）共用同一份 XML 配置；`runStartMsg` 承载启动清理。
登记不是高频操作（每附件一次），putMsg 往返可忽略。

| 实例方法（经 putMsg） | 职责 |
|---|---|
| `register(absolutePath, name, origin)` → `TLAttachmentRef` | **原地引用**已有文件（webui 上传件），本地抽取元数据，不复制 |
| `store(bytes, mime, name, origin)` → `TLAttachmentRef` | 落盘 `data/{uid}/attachments/auto/{hash[0:2]}/{hash}.{ext}`；**已存在则跳过写入**（去重） |
| `refFromUrl(url, origin)` / `refFromFileId(id, origin)` | 透传形态 |
| `cleanup(uid)` | `attachments/` + `uploads/` 两处：TTL 天 + 总量上限，按 mtime LRU；`runStartMsg` 全量跑一次 |

| 静态方法（**provider 热路径**用，零消息往返零配置） | 职责 |
|---|---|
| `readBytes(TLAttachmentRef)` → `byte[]` \| `null` | 按 `ref.path` 直读；文件缺失返回 `null` → 上层降级（§6）。两个 provider 共用 |
| `sniff(File)` → `{mime, width, height}` \| `null` | 魔数判定（PNG `89504E47` / JPEG `FFD8FF` / GIF `474946` / WebP `RIFF....WEBP`）+ 尺寸解析 |
| `extractMeta(File, mime)` → `Map` | 本地抽取：图片尺寸 / 文本行数字符数 / CSV 列名 / PDF 页数；失败静默返回空（**元数据是加分项，不是必需品**） |

### 4.4 `TLViewImageSkill`（新增内置技能）

输入 `path`（清单里给的路径）；输出 JSON `{"image_path": "...", "mime": "...", "name": "..."}`。
经 §5.5 约定被 agent 识别 → 注入独立 user 消息 → 模型真正看到图。

**安全**：派发前由 agent 用当前上下文里的路径集合做**白名单校验**——取 `attachments` 与 `images`
**两个字段的并集**（模型可能想重看某张已注入过的截图），模型只能看"已登记的附件"，不能凭空
指定任意路径；技能内部再加一道 `data/{uid}/` 前缀兜底。

### 4.5 清单（manifest）生成器

`TLAiAgent` 侧一个方法，**发送时**生成（不写回存储），拼在带 `attachments` 的消息文本前：

```
[附件]
1. report.png — image/png, 1920×1080, 284KB — 路径: data/u1/uploads/a1b2.png
2. sales.csv — text/csv, 12KB, 1043 行, 列: date,sku,amount — 路径: data/u1/uploads/c3d4.csv
查看图片内容请调用 view_image(路径)；其他文件可直接用路径本地处理。
```

文件名做**转义**（去换行/控制字符、限长 64、剔除路径分隔符）——防文件名注入指令（§8）。

## 5. 数据流

### 5.1 用户上传（webui）

```
app.js: 选择/拖拽/粘贴 → POST /api/upload（已有）→ data/{uid}/uploads/xxx.png
        ↓ state.uploads 文件名随 chat 请求带出（修 C6 缺口）
        body: { message, sessionId, attachments: [{path:"uploads/xxx.png", name:"report.png"}] }
        ↓
TLWebChatModule.chat / beginChatStream：校验路径落在 data/{uid}/ 内 → 魔数/类型判定
        → TLAttachmentStore.register（原地引用，不复制）→ msg.AI_P_ATTACHMENTS
        ↓ TLAgentService.doChat 转发（三段链：webui → agentService → agent）
        ↓
TLAiAgent.doChat：读出附件 → 挂在本轮首条 user 消息的 attachments 上
```

非图片文件（CSV/文本/其他）走**完全相同**的通道——差别只在清单里多抽几个元数据。

### 5.2 控制台

`/img <路径>` / `/file <路径>`：登记附件（`origin=user`），缓存到本轮，**随下一条消息发出后自动清空**；
无参列出当前已登记附件；可多次累积。

### 5.3 API 透传

`chat` / `chatStream` 消息新增 `AI_P_ATTACHMENTS`，取值 `List<Map>`，四种形态：

| 形态 | 处理 |
|---|---|
| `{path:"...", name:"..."}` | 读文件 → 类型判定 → `register`。**路径必须落在当前用户自己的 `data/{uid}/` 内**（只校验 `data/` 会让调用方指向别人的目录，已收严）；`{path:"d:/x.png"}` 这类本机任意路径不再被接受 |
| `{base64:"...", mime:"...", name:"..."}` | 解码 → 魔数校验 → `store` |
| `{url:"https://..."}` | `refFromUrl`，仅校验 scheme=http(s) 且长度 ≤8192 |
| `{fileId:"file-api-xxx"}` | `refFromFileId`，透传不校验 |

webui 的 `/api/chat` / `/api/chatStream` body 用同一套 `attachments` 数组，原样透传。

### 5.4 模型按需取图

```
模型看到清单 → 调 view_image(path)
        → TLViewImageSkill 返回 {"image_path": ...}
        → agent 侧白名单校验通过 → 命中 §5.5 约定
        → 注入独立 user 消息（images=[ref], content="[查看附件：report.png]"）
        → provider 发图片块
```

### 5.5 工具产图 → 注入（**技能声明 + agent 搬运**）

**⚠ 必须攒到整簇 tool 之后一次性注入**（2026-10-05 实测）：并行批次里若第一条结果就注入 user 图片消息，
历史会变成 `assistant(tool_calls A,B) → tool(A) → user(图) → tool(B)`；`sanitizeToolPairs` 的向后扫描
遇 user 就停，`tool(B)` 被判孤儿删除，进而 `assistant(A,B)+tool(A)` 也被判未应答而删除——
**整轮工具消息从模型视野里消失**（存储侧完好）。所以 `appendToolResult` 只把图攒进 `pendingImages`，
整批写完后由 `flushToolImages` 注入一条 user 消息。

技能不能直接往对话里塞消息（它只返回结果），所以"技能自己决定"落地为**技能在输出里声明、
agent 只做搬运**。声明放在技能输出的 JSON 里——**Java 技能与 Python 系脚本走同一条路**，
不存在"Python 无法表态"的分裂（`screenshot_base64` 本来就躺在那个 JSON 里）。

**声明协议**（两个部分，都在技能输出 JSON 里）：

```json
{ "ok": true, "screenshot_base64": "<正文>", "image_for_model": true }
```

| 部分 | 含义 | 默认值 |
|---|---|---|
| `image_for_model`（声明键，可配） | **技能表态"这张图给模型看"** | 缺省 = **不给**（保守默认，技能不表态就不投喂） |
| 图数据 key（`imageDataKeys` 白名单） | 图在哪：`screenshot_base64` / `image_base64`（正文）或 `image_path`（已落盘文件路径） | 取第一个命中的 |

```java
// TLAiAgent：三个写入 history 的点（正常路径 1335 / 直出路径 1319 / 流式路径）收敛为一个方法
private void appendToolResult(List<TLConversationHistory> history, TLToolExecutor.ToolResult r) {
    List<TLAttachmentRef> imgs = feedToolImages ? extractDeclaredImages(r.output) : null;
    //   ↑ feedToolImages 只是"安全阀"：agent 不做"该不该给"的判断，判断权在技能
    history.add(new TLConversationHistory(r.toolCallId, r.toolCallId,
            imgs != null ? stripImageKeys(r.output) : r.output));   // 文本里 base64 换占位符
    if (imgs != null && !imgs.isEmpty()) {
        TLConversationHistory m = new TLConversationHistory(Role.user, "[工具结果：图片]");
        pendingImages.addAll(imgs);            // 攒起来；不就地注入（见下）
})
// 整批工具结果写完后：flushToolImages(history, pendingImages);
    }
}
```

`extractDeclaredImages`：`image_path` 形态直接构造 ref（零拷贝，`view_image` 用这条）；
`*_base64` 形态解码后落盘得 ref。

**解析护栏**：

1. 输出必须以 `{` 开头且**包含声明键** `image_for_model` 才尝试解析——没声明就整段跳过
2. 输出长度超过 `maxParseKB`（默认 8192）→ **不解析**，照老路 `stripImagePayload` 降级成占位符
3. gson 解析失败 → log 一行忽略，原文照旧（**绝不因图片功能损坏工具结果**）
4. `feedToolImages=false` 时整个 helper 退化为原逻辑（安全阀）

**各技能的表态**（技能自己的事，不是 agent 策略）：

| 技能 / 动作 | 表态 | 说明 |
|---|---|---|
| 桌面 `screenshot` | `image_for_model: true` | **必须**——图就是该动作的返回值；无 DOM 文本兜底 |
| 桌面其余 11 动作 | — | 本来就不产图 |
| 浏览器 `screenshot` | `image_for_model: true` | 动作目的即看图 |
| 浏览器 navigate/click/type/scroll | 由浏览器技能定 | **建议表态给**（computer-use 要看操作结果）；可选增强：加 `capture` 参数让模型自行关闭某次 |
| `view_image` | `image_for_model: true` | 存在意义就是给模型看 |
| Python 版浏览器/桌面脚本 | 输出 JSON 里补同一字段 | 与 Java 版同一条路 |

**触发源对照**：

| 触发源 | 语义 | 结果 |
|---|---|---|
| 技能表态的工具产图 | 技能决定 | 注入（受 `maxImagesInContext` 约束） |
| 技能不表态的工具产图 | 技能决定 | 不注入（仅 webui 渲染用，走 `stripImagePayload` 老路） |
| 用户上传的附件 | 资源，可本地处理 | 默认不投喂（`ondemand`），模型按需 `view_image` |

## 6. 发送层（Provider）

`TLOpenAiProvider.buildRequestBody` 是唯一构建点（`completion` / `completionStream` 共用）：

```java
if (!h.hasImages()) {
    m.addProperty("content", h.getContent());          // 无图 → 老路径，一字不改
} else {
    JsonArray blocks = new JsonArray();
    if (content 非空) blocks.add(textBlock(h.getContent()));   // 文本块在前
    for (TLAttachmentRef r : h.getImages()) blocks.add(imageBlock(r));  // 只为 images 建块
    m.add("content", blocks);
}
```

判据是 `hasImages()` 而非块数量——「有图但文字为空」时按块数量判断会退回空字符串路径。

- `imageBlock`：`IMAGE` → `TLAttachmentStore.readBytes(ref)`（静态）→ `data:{mime};base64,...`；
  `URL` → 直接用；`FILE_ID` → `{"type":"file","file_id":...}`
- **读盘失败**（文件被清理）→ 丢弃该图 + 文本追加 `"(图片已过期)"`，**不炸不 400**
- **非 user 角色带图 → 丢弃图片并 log WARN**（防御性兜底，正常流程不该出现）
- `TLClaudeProvider` 同构：`{"type":"image","source":{"type":"base64","media_type":...,"data":...}}`
  （它的 content 本来就是块数组，插入点最顺）；`FILE_ID` 在 Claude 侧降级丢弃

## 7. 上下文卫生

### 7.1 真拷贝修正（C4）

`buildSendList` 改为对元素做 `copy()`：让"只作用本次发送拷贝，不写回 context"这句注释**成为事实**。
发送期新增的两处就地改写（清单拼接、注入图保留策略）都依赖它；顺带修正现有偏差——
`trimToContextBudget` 的截断此前会永久污染活上下文并经 `saveContextHistory` 持久化。

### 7.2 保留策略

- `attachments`（引用）**不参与保留裁剪**——它们只产生几十 token 的清单文本，且是"用户发过什么"的事实
- `images`（真图片块）按 `maxImagesInContext` 保留最近 **K 条带图消息**（默认 1），更早的 `setImages(null)`

### 7.3 token 估算

`estimateTokens` 每张保留图 **+1024**（手册每图上限，保守取满）；清单文本按普通文本计。

### 7.4 既有 String 操作

`capToolResult` / `stripImagePayload` 只动 `content`，**不碰两个新字段**；新旧行为不冲突。

## 8. 安全与清理

| 项 | 策略 |
|---|---|
| 路径穿越 | 所有 `path` 必须落在 `data/{uid}/` 内（`register` 用 canonical path 前缀校验，拒绝 `..` 与跨用户目录） |
| URL | 仅 `http(s)`，长度 ≤8192 |
| 类型 | **魔数判定**（不信扩展名/声明 MIME，与 DeepSeek 侧一致）；图片走白名单，其余归 FILE |
| 大小 | 单图 ≤`maxImageMB`(32) / 单条消息 ≤`maxAttachmentsPerMessage`(10) / 单轮请求合计 ≤48 MiB（模型侧上限，超了主动拒绝并提示） |
| **文件名注入** | 清单里的文件名**转义**：去换行/控制字符、限长 64、剔除 `[`/`]` 等结构字符——防"文件名里写指令" |
| 白名单 | `view_image` 只能看**当前上下文已登记**的附件路径（agent 侧校验）+ 技能内 `data/{uid}/` 兜底 |
| 清单规模 | 只列前 `manifestMaxEntries`(20) 条，其余折叠成"…还有 N 个" |
| 清理 | `TLAttachmentStore.cleanup`：启动时 + 每次写入后；TTL `attachmentRetentionDays`(7) + 单用户总容量 `attachmentStoreMaxMB`(500)，按 mtime LRU；覆盖 `attachments/` 与 `uploads/` |
| 隔离 | per-user 目录，与 session/记忆/断点同一隔离约定 |

## 9. 存档、恢复与 webui 渲染

- **存档**：两个新字段都是普通 POJO 列表，gson 直接序列化；JSONL/DB 两版 SessionManager
  **零改动**；老记录反序列化为 `null`
- **恢复**：resume 后附件引用仍在；`withContextSystem` / `assembleSession` / `deltaMessages`
  按引用复制 POJO，字段自动随行；清单在下次发送时重新生成
- **webui 历史回放**：history 接口返回 `attachments` 引用 → 前端渲染缩略图（复用现有
  `<img class="browser-shot">` + lightbox 样式）
- **出图路由**：新增 `/api/image`（urlMap + webui action）→ 校验路径属于当前登录用户 →
  `TLWServModule.putStream` 二进制输出（C7，几乎零新代码）
- **实时渲染不变**：`pushStreamToolEvent` 链路原样保留

## 10. 配置项汇总

```xml
<!-- agent 级 -->
attachmentFeedPolicy="ondemand"      <!-- ondemand | auto（auto = ≤maxAutoImages 张直接投喂） -->
maxAutoImages="2"                    <!-- 仅 auto 档位生效 -->
maxAttachmentsPerMessage="10"
manifestMaxEntries="20"
feedToolImages="true"                <!-- 安全阀：关掉则任何工具产图都不注入。
                                          ⚠ 判断权在技能（§5.5），这里只是"全禁"的兜底开关 -->
maxImagesInContext="1"               <!-- 注入图保留最近 K 条 -->
imageDeclareKey="image_for_model"    <!-- 技能表态键：值为 true 才投喂，缺省不投喂 -->
imageDataKeys="screenshot_base64;image_base64;image_path"
maxParseKB="8192"
<!-- store 级 -->
maxImageMB="32"
attachmentRetentionDays="7" / attachmentStoreMaxMB="500"
```

默认值即可工作（按需投喂默认开）；无需改任何现有配置文件。

## 11. 验收

| # | 方式 | 断言 | 状态 |
|---|---|---|---|
| 1 | 无 LLM：Mock Provider + 注册表取 agent 实例直接发带附件 chat | 发送的 JSON 里**附件消息只有清单文本、没有图片块**；`view_image` 之后才出现两块数组 | 已验（Plan 1 冒烟 76 项） |
| 2 | 无 LLM：`TLAttachmentStore` 单测 | 元数据抽取（尺寸/行数/列名）/ 魔数拒绝伪造扩展名 / 路径穿越拒绝 / 原地上传件不复制 / TTL+容量清理 | 已验（Plan 1 冒烟） |
| 3 | 无 LLM：清单生成 | 文件名转义（含换行/超长/结构字符）后仍可读；超 20 条折叠 | 已验（Plan 1 冒烟） |
| 4 | 无 LLM：`buildSendList` 拷贝语义 | 发送期裁剪/拼清单后活上下文 POJO **不被修改**（对 C4 的直接回归） | 已验（Plan 1 冒烟） |
| 5 | 无 LLM：工具产图声明 | ① 输出带 `screenshot_base64` 但**无** `image_for_model` → **不注入**（保守默认）；② 带声明 → history 出现 tool(占位符) + user(带图)；③ `image_path` 形态零拷贝走通；④ `feedToolImages=false` 全禁 | ①-③ 已验（Plan 1 冒烟）；④ **未执行** |
| 6 | **真 LLM**：造一张带字的图 | ① 贴图问"图里写什么" → 模型先看到清单、调 `view_image`、答对；② 无图问答行为不变 | 已验（Plan 1 真 LLM `vision_e2e_img`）；Plan 2 控制台 `/img` 复验（读出 `CONSOLE 5150`）、webui 非流式+流式复验（读出 `WEB 8241`） |
| 7 | **真 LLM**：本地处理 | 上传 CSV 问"多少行/列有哪些" → 清单直接答（无需读文件）；问"转成 JSON" → 模型用 `code_execution` 拿路径处理，**CSV 内容不进上下文** | 已验（Plan 1 真 LLM `vision_e2e_csv`） |
| 8 | **真 LLM**：浏览器 agent | 跑一轮带 browser 的任务，trace 中工具截图被自动注入、模型引用画面（而非占位符） | **未验证**：无专门浏览器轮次；Plan 2 只验了技能侧 `image_for_model` 表态（探针 6/6），webui 流式轮次里观察到 `view_image` toolEvent |
| 9 | 回归 | 老的无附件会话存档 → 加载 → 再发，行为不变；现有 `/test` 9 场景全过 | 已验（Plan 1 冒烟 + `/test`；Plan 2 收尾再跑 `/test` 通过=11, 失败=0） |
| 10 | 降级 | 发送前手工删掉图片文件 → 不炸、不 400，文本出现"(图片已过期)" | 已验（Plan 1 冒烟） |

**Plan 2 补充已验（真实运行）**：控制台 `/img` 送图；webui 上传进对话（非流式 + 流式）；
webui 历史 JSON 带 `attachments` 且 `/api/image` URL 实测出图（返回字节与上传件 md5 一致）；
`/api/image` 跨用户路径被拒；桌面/浏览器技能 `image_for_model` 表态探针 6/6。

**未验证项**：① 前端 DOM 渲染（上传缩略图/历史缩略图/点开大图）只做了代码走查与已 serve
前端资源检查，**未驱动浏览器实测**；② 行 5④ `feedToolImages=false` 从未执行；③ 行 8 浏览器
真机轮次（见上）。

## 12. tool 角色能否带图（**未执行探针，走了保守路线**）

> **实施结论（2026-10-05）：该探针未执行。** 按保守设计走了"注入独立 user 消息"，并在实施中修正为
> **整批 tool 消息之后统一注入**（并行批次里就地注入会被 `sanitizeToolPairs` 判成孤儿，见 §5.5）。
> 探针仍可补做——若 tool 角色能带图，可砍掉整段注入、链路更短。

**原探针设计：** 手册只点名 system/assistant 会 400，"仅支持 user 消息"的
表述是否覆盖 tool 未明说。

- 能带 → 砍掉 §5.5 的独立 user 消息注入，图片直接挂 tool 消息，链路更短
- 不能带 → 按本设计走注入

探针脚本：构造 `assistant(tool_calls) → tool(content 含 image_url 块)` 的最小请求，
看返回 200 还是 400。**结论回写进本文件。**

## 13. 影响面清单

| 模块 | 文件 | 改动 |
|---|---|---|
| common | `TLAttachmentRef.java` | 新增 |
| common | `TLAttachmentStore.java` | 新增（模块 + 静态读/嗅探/元数据） |
| skill-builtin | `TLViewImageSkill.java` | 新增内置技能 |
| desktop-java | `JavaDesktopEngine.java` / `TLDesktopJavaSkill.java` | `screenshot` 输出加 `image_for_model: true`（**唯一的必改技能**） |
| browser-java | `JavaBrowserEngine.java` / `TLBrowserJavaSkill.java` | `screenshot` 输出加声明；navigate/click/type/scroll 是否表态由技能定（建议表态） |
| skills（Python 系） | `skills/browser/scripts/*.py`、`skills/desktop/scripts/*.py` | 输出 JSON 里补同一字段（可选，不改则维持"不投喂"） |
| common | `TLConversationHistory.java` | +`attachments`/`images` 字段、+`copy()`、`toString` 防 NPE |
| common | `TLAiAgentParamString.java` | +`AI_P_ATTACHMENTS` 等常量 |
| common | `TLAiAgent.java` | doChat 读附件；清单生成；`appendToolResult` 收敛 3 处；`buildSendList` 真拷贝；`applyImageRetention`；`estimateTokens`；`view_image` 白名单校验 |
| common | `TLAgentService.java` | 转发 `AI_P_ATTACHMENTS` |
| common | `TLChatConsole.java` | `/img` `/file` 命令 |
| provider-openai | `TLOpenAiProvider.java` | `buildRequestBody` 图片块分支 |
| provider-claude | `TLClaudeProvider.java` | image block 分支 |
| webui | `TLWebChatModule.java` | chat/chatStream 收附件+校验登记；image 出图 action |
| webui | `urlMap_config.xml` | +`/image` 路由 |
| webui | `app.js` / `index.html` | 上传文件名随请求；粘贴；历史缩略图 |
| conf | `cn.tianlong.tlobject.aiagent_config.xml` | 注册 `attachmentStore` 模块 |
| conf | `aiagent_master_config.xml` 的 `<skills>` | 加 `viewImageSkill` 一行 |
| conf | 各 agent 配置 | 默认值即可，**无必改项** |

Maven 结构不变（无新模块）。
