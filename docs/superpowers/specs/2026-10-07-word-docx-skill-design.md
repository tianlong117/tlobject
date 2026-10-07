# word-docx 技能（Java 版）设计

日期：2026-10-07
状态：设计已确认，待实施

## 1. 目标

给 aiagent 加一个 **Word 文档处理技能**（`.docx` 读 / 写 / 改），Java 实现，对称
`desktop-java` / `browser-java` 的独立模块结构：

- **能力面**：富读（段落+样式+表格+大纲）、新建/追加、结构级修改（段落/表格增删改）、
  模板填充（占位符保格式替换）
- **保格式优先**：修改走 run 级最小重建，不是"整段推倒重写"
- **依赖干净**：POI 封在独立模块内，不污染 `skill-builtin`

不做的（明确排除）：`.doc` 老格式、渲染成图片/PDF、HTML 预览、`image_for_model` 表态。

## 2. 决策记录

brainstorming 阶段逐项确认，记录理由以备回溯：

| 决策点 | 结论 | 理由 |
|---|---|---|
| 修改深度 | 文本级 + 结构级 + 模板填充 + 富读，全要 | 用户明确要求 |
| 输入来源 | 本地路径 + webui 上传附件 + 工作目录，三条都要 | — |
| 附件接线 | **零特殊接线** | 框架已把本地路径写进模型可见的附件清单（`TLAiAgent.java:3181`），末尾明说"其他文件可直接用路径本地处理"。模型把清单里的路径直接传给技能即可，只需 `allowedRootPath` 覆盖上传目录 |
| 结果呈现 | 路径 + 文本摘要 | 用户选定。排除 HTML 预览与 LibreOffice 渲染——本机无 Word 无 LibreOffice，真渲染要额外装软件，投入产出比低 |
| `.doc` 老格式 | 不支持，明确报错 | 需额外下 `poi-scratchpad`；且 HWPF 改写能力弱、易损坏文件。现在几乎都是 `.docx` |
| 挂载位置 | `fileAgent` | 与既有 `fileOperationSkill` 同处，文件处理归一处；主 agent 描述已是"文件操作专家" |
| 模块归属 | 新独立模块 `aiagent/word-java` | 仿 desktop-java/browser-java 先例；POI 不污染 skill-builtin；引擎可脱离框架独立测试 |
| 工具粒度 | 单工具 `word` + `action` 分发 | 仿 `desktop`。三个工具会让 fileAgent 工具数翻三倍，且"读到的段落号接着用来改"的上下文被割裂 |
| POI 版本 | **5.5.1**（全局升级） | 用户选定。4.1.2 已是旧版；不能只让新模块用新版——同 classpath 同名类"先到先得"，不可预测 |

## 3. 模块结构

```
aiagent/word-java/
  pom.xml                            （依赖 common + POI 5.5.1）
  src/main/java/cn/tianlong/tlobject/aiagent/word/
    TLWordJavaSkill.java             技能壳：schema / 参数解析 / 路径安全 / 结果包装
    JavaWordEngine.java              引擎门面：action 分发 + 文件生命周期（打开/原子保存）
    WordTextExtractor.java           读：结构化文本 / 大纲 / 表格 / 元数据
    WordMarkdownWriter.java          写：markdown 块模型 → XWPF（新建 / 追加）
    WordTextEditor.java              改：跨 run 精确替换核心 + 段落增删改 + 表格增删改
    WordMarkdown.java                纯字符串：markdown → 块列表（无 POI，独立可测）
```

拆分理由：`WordTextEditor` 里那颗"跨 run 保格式替换"是全局技术核心，`replace` 和
`fill_template` 都调它，单独成文件边界清楚；`WordMarkdown` 不碰 POI，可脱离一切依赖单测。
引擎（`JavaWordEngine` 及其下游）不含框架代码，带 `main()` 自测。

### 接线（7 处）

| 位置 | 改动 |
|---|---|
| `aiagent/pom.xml` | `<module>word-java</module>` |
| `tlobject-all/pom.xml` | 加 `tlobject-aiagent-word-java` 依赖 |
| 根 `pom.xml` | `<dependencyManagement>` 钉死 POI 5.5.1，防 execl 与新模块版本漂移 |
| `cn.tianlong.tlobject.aiagent_config.xml` | `<module name="wordJavaSkill" classfile="...TLWordJavaSkill" singleton="true"/>` |
| `fileAgent_config.xml` | `<skill name="word" sameClassAs="wordJavaSkill" statup="true" allowedRootPath="." .../>` |
| `aiagent_master_config.xml` | `fileAgent` 的 `description` 补一句"支持 Word 文档读写修改"（见下方⚠️） |
| 5 个 `aistart*.bat` | classpath 加 `aiagent/word-java/target/classes`（**各一处，漏一个那个入口就没这功能**） |

⚠️ **易漏点**：`aiagent_master_config.xml` 的描述那句不是可选装饰。主 agent 是靠
`description` 决定把任务转交给谁的——不补这句话，技能等于不存在。

## 4. 动作集（13 个）

工具名 `word`，单工具 + `action` 分发。

### 读（返回纯文本）

| action | 参数 | 说明 |
|---|---|---|
| `read` | `path`, `from?`, `to?` | 结构化文本，每行 `[序号] [样式] 文本`，表格以 `[表格 N] R行×C列` 标记内嵌 |
| `outline` | `path` | 仅标题层级（Heading N），带段落号。长文档定位入口 |
| `tables` | `path`, `index?` | 表格内容（二维） |
| `info` | `path` | 段落数 / 表格数 / 图片数 / 字数 / 标题数 |

### 写（`content` 为 markdown，引擎转 docx）

| action | 参数 | 说明 |
|---|---|---|
| `create` | `path`, `content`, `overwrite?` | `#`→Heading、`-`→列表、`**`→加粗、`\|a\|b\|`→表格、`---`→分页 |
| `append` | `path`, `content` | 追加到已有文档末尾 |

### 改

| action | 参数 | 说明 |
|---|---|---|
| `replace` | `path`, `find`, `replace`, `index?`, `mode?` | 全文或指定段落替换；`mode=preserve`(默认)/`rewrite`；返回替换处数 |
| `set_paragraph` | `path`, `index`, `text` | 整段重写（丢该段 run 级格式） |
| `insert_paragraph` | `path`, `index`, `text`, `style?`, `position?` | 前插/后插 |
| `delete_paragraph` | `path`, `index` | 删段落 |
| `set_table_cell` | `path`, `table`, `row`, `col`, `text` | 改单元格 |
| `add_table_row` | `path`, `table`, `values?` | 加一行 |

### 模板

| action | 参数 | 说明 |
|---|---|---|
| `fill_template` | `path`, `data`, `output?` | 占位符 `${key}` / `{{key}}` 跨 run 替换，保格式；扫正文+表格；`output` 缺省=覆盖原文件 |

`outline` / `info` / `tables` / `read` 是"先侦察、再动手"的组合拳——长文档靠 `outline` 定位、
靠**段落号**操作，这是整套动作可用的前提。

**`replace` 不单设"段落内替换"动作**：`index` 参数可选即为段落限定，少一个动作。

## 5. 技术核心：跨 run 保格式替换

### 问题

Word 会把一段文字任意切碎成多个 run（编辑历史 / 拼写检查 / 输入法都会造成）。
`订单号：${orderNo}` 在 XML 里可能是 4 个 run：

```xml
<w:r><w:t>订单号：</w:t></w:r>
<w:r><w:t>${</w:t></w:r>
<w:r><w:t>orderNo</w:t></w:r>
<w:r><w:t>}</w:t></w:r>
```

**直接对单个 run 做字符串查找必然搜不到**——这是所有"用 POI 改 Word"翻车的地方，
也是模板填充能不能"保真"的分水岭。

### 算法（`WordTextEditor` 核心）

1. 拼接段落内所有 run 的文本 → `full`，同时建 `runs[i] → [start,end)` 区间索引
2. 在 `full` 上做匹配 → 得替换区间 `[ms,me)`
3. 映射回 run 范围 `[ri, rj]`
4. **最小重建**：
   - `runs[ri]` 文本 = `原前缀 + 新文本`
   - `runs[ri+1 .. rj-1]` 文本 = `""`
   - `runs[rj]` 文本 = `原后缀`（若 `ri == rj` 则合并为 `前缀+新文本+后缀`）

匹配不跨段落（占位符不会跨段）。

### 为什么这样就保住了格式

**不新建、不删除 run 对象，只改它们的文本**。字体 / 加粗 / 颜色 / 字号 / 高亮全挂在 run
对象上，原地不动，所以格式必然保留。新插入的文字继承 `runs[ri]` 的格式——正好是占位符
原本所在位置的格式，符合直觉。

### 永不静默损坏（比算法本身更重要）

改写前先检查 `[ri,rj]` 范围内的**不安全 run**：

- 超链接 run（`XWPFHyperlinkRun`）——文本由 relationship 管理，改文本会破坏链接
- 域 run（`XWPFFieldRun`）——PAGE / NUMPAGES 等域
- 含 `<w:br/>` / `<w:tab/>` / `<w:drawing>` 的 run——`run.text()` 不含这些标记，
  重写会丢

命中就**跳过该处并计数上报**，不硬改：

```json
{"ok":true,"replaced":3,"skipped":1,"skippedReasons":["段落 7 含超链接，已跳过"]}
```

模型看到 `skipped>0` 可以改用 `mode=rewrite`（整段重写：清空该段 run，用首个 run 的格式
写回新文本，丢段内混合格式但绝对能改）或 `set_paragraph`。

**逃生舱永远在，但绝不悄悄改坏。**

> 实现期必做实证：`XWPFRun.setText()` 对含 `w:br`/`w:tab` 的 run 的确切行为需用真实
> 文件验证，不能只靠读 API 文档推断。验证结果若与上述假设不符，按"跳过并上报"处理。

## 6. 保存与并发

- **原子写**：POI 写入 `xxx.docx.tmp` → `Files.move(REPLACE_EXISTING, ATOMIC_MOVE)`。
  保证"要么改成功、要么原文件纹丝不动"，不会写一半留个坏文件
- **文件被 Word/WPS 占用**：Windows 上的高频真实场景。捕获 `FileSystemException` →
  明确报"文件正被 Word 占用，请关闭后重试"。
  **读不受影响**——Word 开着文档也能读，因为它是共享读
- 打开文档一律 `try-with-resources`：`XWPFDocument` 持有 zip 流，不关会锁住文件
- 备份：**默认不做**（原子写已够安全），修改类 action 给可选 `backup=true`。
  不默认开是为了不把用户目录塞满 `.bak`

## 7. 路径与安全

- `allowedRootPath`，默认 `.`（进程 CWD）。**启动方式决定 CWD**——双击 bat 是仓库根，
  IDE 是模块目录，终端是当前目录（这条已踩过，配置注释必须写明）
- 解析规则：
  - **裸文件名**（不含 `/` `\`）→ 落到工作目录 `data/{userId}/documents/`，自动创建。
    用户说"生成个报告.docx"直接就能写，不用拼绝对路径
  - **相对路径** → 按 root 解析
  - **绝对路径** → 在 root 内则允许，**越界直接报错**并列出允许的根
- `userId` 取自消息 `AI_P_USERID`，缺省 `default`（与 `attachmentStore` 的 `data/{uid}/`
  约定一致）
- 扩展名白名单**只有 `.docx`**：
  - `.doc` → "请先用 Word/WPS 另存为 .docx"
  - `.docm` → 拒绝，理由写进文案：写回会丢宏
  - `.rtf` / `.odt` → 拒绝

⚠️ **有意与 `fileOperationSkill` 不一致**：那个 skill 越界时会把 `D:/other/x.docx` 悄悄
重定向成 `./x.docx`。这个"就近重定向"行为不可预测，word 这边选择**明确报错**。

## 8. 结果契约

**读类返回纯文本**（内容要进上下文给人看，纯文本比 JSON 少一层 `\n` 转义），段落号做定位锚：

```
[1] [Heading1] 项目报告
[2] [Normal] 本报告统计了...
[3] [表格 1] 3行×4列
  | 月份 | 收入 | 成本 | 利润 |
  | 1月  | 100  | 60   | 40   |
```

**写/改类返回 JSON 回执**：

```json
{"ok":true,"path":"D:/.../报告.docx","action":"replace","replaced":3,"skipped":0,"bytes":12345}
```

**文案语言**：沿用现有 5 个 skill 的惯例（工具输出英文，如 `File not found:`）。
面向模型的 skillDescription 亦为英文。

## 9. 错误处理

| 异常 | 文案 |
|---|---|
| `NotOfficeXmlFileException` | 不是 .docx（可能是 .doc 改名或已损坏） |
| `POIXMLException` | 文件损坏 |
| `FileSystemException`（保存时） | 文件被 Word 占用，请关闭后重试 |
| `FileNotFoundException` | 文件不存在 |

`execute()` 整体 `catch (Exception | LinkageError)`，且 **`ensureEngine()` 必须写在 try 内**
——这是 `browser-java` / `desktop-java` 上踩过的坑：classpath 缺类时抛的是 `LinkageError`，
框架工具执行器不兜 Error，放 try 外工具结果会**凭空消失**。

## 10. POI 5.5.1 升级方案

### 为什么必须全局升级

`execl` 与 `word-java` 在同一 classpath 上。两个 POI 版本共存 → 同名类"先到先得"，
行为不可预测。必须统一。

### 破坏面（已核实）

`TLExeclFileUtils.java:156` 的 `HSSFDateUtil.isCellDateFormatted(cell)` —— `HSSFDateUtil`
在 POI 5.0 被**删除**，须改为 `org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)`。

其余用法在 5.x 均保留：枚举 switch（`case NUMERIC:` 等无 import 亦可编译）、
`POIFSFileSystem`、`HSSFWorkbook(POIFSFileSystem)`、`Row.createCell(int)`、
`CellStyle.setAlignment(HorizontalAlignment)`。**受影响只有这一行**。

### 依赖清单

`poi-ooxml` 5.5.1 的重依赖（xmlsec / BouncyCastle / PDFBox / Batik / graphics2d）
**全部是 `optional=true`**，Maven 不传递拉取。实际需要的 jar：

```
poi-5.5.1.jar                  poi-ooxml-5.5.1.jar
poi-ooxml-lite-5.5.1.jar       ← 注意：5.x 里 poi-ooxml-schemas 已改名 lite
xmlbeans-5.3.0.jar             commons-compress-1.28.0.jar
commons-io-2.21.0.jar          curvesapi-1.08.jar
commons-collections4-4.5.0.jar commons-codec-1.20.0.jar
SparseBitSet-1.3.jar           commons-math3-3.6.1.jar（已是此版本）
```

`poi-ooxml-lite` 是精简 schema 包，覆盖 docx 段落/表格/图片，够用；
`poi-ooxml-full` 体积大得多，不需要。

### bat classpath 增删

```
删：poi-4.1.2 / poi-ooxml-4.1.2 / poi-ooxml-schemas-4.1.2 / xmlbeans-3.1.0
    / SparseBitSet-1.2 / commons-compress-1.19 / curvesapi-1.06

增：poi-5.5.1 / poi-ooxml-5.5.1 / poi-ooxml-lite-5.5.1 / xmlbeans-5.3.0
    / SparseBitSet-1.3 / commons-compress-1.28.0 / curvesapi-1.08
    / commons-io-2.21.0 / commons-collections4-4.5.0 / commons-codec-1.20.0
```

用 `mvn dependency:build-classpath` 生成确切路径再填，**不手抄**。本地仓库缺上述新版本，
需联网下载（网络已验证可达 Maven Central）。

### 两个已知风险

**① `log4j-api` 版本错配** —— POI 5.5.1 编译基准 2.24.3，工程内是 2.15.0。
**决定：先不动**。POI 只用它打日志，用到的方法自 2.0 稳定。
**不能只升 `log4j-api` 不升 `log4j-core`**（core 实现 api 的 SPI，版本必须配对），
真要升就是整个日志栈一起动——不塞进本次范围。
若运行时报 `NoSuchMethodError` 涉及 log4j，再单独排一轮日志栈升级。

**② `commons-io` 2.4 → 2.21 跨度大** —— `commons-fileupload` 与 web 上传链路也在用。
commons-io 2.x 向后兼容性一向不错，但这条链路要**回归测一次**（webui 传文件）。

## 11. 验证方案

### 第一层：引擎自测（`JavaWordEngine.main()`，无框架无 LLM）

- **跨 run 保格式**（核心用例）：程序化造一个把 `${name}` 拆成 3 个 run 的文档 →
  `fill_template` → 断言**目标 run 的 bold/color 未变**、文本已替换
- `replace` preserve / rewrite 双路径
- 段落增删改后段落号正确
- 表格读写往返
- `.doc` 拒绝、越界路径拒绝
- 边界：空文档、只有表格没有段落、末段落定位

### 第二层：无 LLM 端到端

注册表取 `wordJavaSkill` 实例 → `skillExecute` 发消息 → 验回执 JSON 与文本输出格式
（`browser-java` 验证时用过的手法）。

### 第三层：真 LLM 冒烟

`aistart.bat` 起应用 → 让 master 转交 fileAgent 写一份文档 → 人看结果；
再走一次 webui 上传 `.docx` 让 agent 读的处理链。

## 12. 明确不做

| 不做 | 理由 |
|---|---|
| `.doc` / `.docm` / `.rtf` / `.odt` | 见 §2 决策记录 |
| 渲染成图片 / PDF | 本机无 Word 无 LibreOffice；额外依赖与安装成本 |
| docx → HTML 预览 | `poi-ooxml` 4.1.2/5.5.1 均无内置转换器；手搓 HTML 保真度低、收益小 |
| `image_for_model` 表态 | 用户未选；且无渲染能力 |
| `.bak` 默认备份 | 原子写已保证不写坏原文件；避免污染用户目录 |

## 13. 已知边界

- **跨段落查找替换不支持**：占位符与常规修改都不跨段（与 Word 实际使用习惯一致）
- **`rewrite` 模式丢段内混合格式**：这是逃生舱的代价，已在回执中文案说明
- **段落号在多次修改间会漂移**：`insert_paragraph` / `delete_paragraph` 后，后续段落号
  整体位移。回执中返回受影响范围，模型需重新 `outline` 确认后再操作
- **`.docx` 内嵌图片只能读不能改**：能读出图片数据与数量（`info`/`read` 标记），
  但本轮不做图片增删（属结构级里未列入的部分）
