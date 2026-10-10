# word 技能排版默认值设计（H1 居中 + 正文首行缩进 2 字符）

日期：2026-10-10
状态：设计已确认，待实施

## 1. 由来与现状（实测）

用户报告：word 技能输出的 docx，**标题不居中、段落开头没有缩进**。

实测确认（当前代码生成 docx 后解包 `word/document.xml`）：

| 段落 | style | 对齐 `w:jc` | 缩进 `w:ind` |
|---|---|---|---|
| 关于年度工作的报告 | Heading1 | 无（左对齐） | 无 |
| 这是第一段正文…… | – | 无 | 无 |
| 一、背景 | Heading2 | 无 | 无 |
| • 列表项甲 | ListParagraph | 无 | 无 |

根因：`WordMarkdownWriter` 写标题只做「三重设定」（pStyle + outlineLvl + 粗体/字号），
正文段落不设任何段落属性；**对齐与缩进从未有任何代码写过**——初版 word spec 未涉及排版默认值。

前置约束：本技能 `new XWPFDocument()` 建出的文档**没有 styles part**（标题"三重设定"的立论基础），
所以排版只能走**段落属性直接格式**（写进 pPr），靠样式定义的路走不通。

## 2. 决策记录

| 决策点 | 结论 | 理由 |
|---|---|---|
| 居中范围 | 仅 H1（`#`）；H2~H6 保持左对齐 | 中文报告/公文惯例：大标题居中、章节标题左起。用户确认 |
| 缩进形式 | 段落属性 `w:ind firstLineChars="200" firstLine="420"` | 正统 Word 排版（段落对话框可见"首行缩进 2 字符"）；不污染文本层。否掉字面全角空格：伪排版，干扰 replace 全文匹配、复制带出、宽度不随字号 |
| 缩进量 420 twips | 2 × 五号（10.5pt）= 21pt = 420 twips | 中文 Word 默认五号；`firstLineChars` 为主（Word/WPS 认字符单位），`firstLine` 只兜底给不认字符单位的渲染器（≈0.74cm，与 Word 显示一致） |
| 实现路线 | 方案 A：直接格式写 pPr | 与现有"三重设定 + 直接格式兜底"同源；跨 Word/WPS 稳。否掉方案 B（生成 styles part：POI 建出空样式表要手工拼 styles.xml，追加/另存即丢，兼容风险高）与方案 C（字面空格） |
| 适用范围 | create / append / insert_paragraph | 用户选定：技能自己造出的段落（含后插的）排版一致；`set_paragraph`/`replace`/`set_table_cell`/`fill_template` 等编辑动作维持"保原格式" |
| 开关 | XML 技能参数 `layout`（默认 `chinese`）+ 单次调用可覆盖 | 用户选定：写英文文档/诗歌/代码清单时模型传 `layout="plain"` 保持无格式 |
| 列表/表格 | 不加缩进 | 列表沿用"• "字面符号方案（加了会叠出双缩进）；表格单元格按列宽排版 |

## 3. 行为规格

**layout 取值**（大小写不敏感、去空白）：

| 取值 | 含义 |
|---|---|
| `chinese`（默认） | 套中文排版：H1 居中 + 正文首行缩进 2 字符 |
| `plain` | 新段落完全不碰对齐/缩进（= 现状）。注意是"不加格式"，不是"清除格式" |

**chinese 下逐块行为**：

| 内容 | 行为 |
|---|---|
| `# 一级标题` | **居中**（`w:jc val="center"`），保留三重设定（pStyle/outlineLvl/粗体/20 磅） |
| `## ~ ######` | 不变：左对齐、无缩进 |
| 正文段落（markdown PARAGRAPH） | **首行缩进 2 字符** |
| 列表（BULLET/NUMBERED）、表格、分页符 | 不变 |
| `insert_paragraph` 不带 style（或空串） | 首行缩进 2 字符 |
| `insert_paragraph` style=Heading1 | 居中（与 writer 同源，复用 `applyHeading`） |
| `insert_paragraph` style=Heading2~6 | 不居不缩（现状） |
| `insert_paragraph` style=其他（如 ListParagraph） | 只 setStyle，不加缩进（显式样式优先） |

**硬边界**：

- **只影响新写的段落**。append 到已有文档，已有段落一律不动；编辑类动作（含
  `set_paragraph` 改写别人段落）不套排版。
- **文本层零变化**：缩进是对齐属性不是字符——`read`/`outline` 输出、`replace` 全文匹配、
  `fill_template` 全不受影响。
- **非法值**：单次调用 `layout="foo"` → 明确报错（含合法取值，模型可自纠）；XML 配错 →
  WARN 日志 + 回退 `chinese`（配置错误不该让调用崩，与 backup 的宽容口径一致）。
- 单次调用传空串/空白 `layout` → 视为未传，走 XML 默认。

## 4. 实现与接线

| 文件 | 改动 |
|---|---|
| `TLWordJavaSkill` | 新增字段 `layout="chinese"`，`setModuleParams` 读 XML（非法值 WARN+回退）；`execute` 平铺 key 列表补 `layout`；`parameterSchema` 加 `layout`；`ensureEngine` 解析（调用级覆盖 XML 级）→ `cfg.chineseLayout`；skillDescription 补排版说明 |
| `JavaWordEngine` | `Config` 加 `boolean chineseLayout = true`；`doCreate`/`doAppend`/`doInsertParagraph` 把开关传给下层 |
| `WordMarkdownWriter` | `writeBlocks(doc, blocks, boolean chineseLayout)`；`applyHeading(p, lv, boolean chineseLayout)`（lv==1 且 chinese 时 `setAlignment(CENTER)`）；新增 `applyFirstLineIndent(p)` 小助手写 `w:ind` |
| `WordTextEditor` | `insertParagraph(..., boolean chineseLayout)`：无样式→缩进；HeadingN→`applyHeading`（带开关）；其他样式→只 setStyle |
| `WordEngineSelfTest` | 更新调用点 + 新增断言块（见 §5） |
| `fileAgent_config.xml` / `CLAUDE.md` | 配置显式 `layout="chinese"` + 注释可选 plain；skillDescription 同步（XML 那份才是真生效的——类里那份是死代码，基类先填了 `name+" skill"`；同步改一份防误导）；CLAUDE.md word 条目补排版默认值 |

**skillDescription 给模型补两句**（关键）：

> CREATE/APPEND/INSERT apply a layout automatically: with `layout="chinese"` (default) level-1
> headings are centered and body paragraphs get a 2-character first-line indent — do **NOT** add
> leading spaces or indent characters yourself. Pass `layout="plain"` for English documents,
> poetry, or content where no formatting is wanted.

不加这句，模型很可能自作主张塞全角空格，变成双重缩进。

**schema 里 `layout` 的 description**：

> Document layout: chinese (default) = H1 centered + 2-character first-line indent on body
> paragraphs; plain = no alignment/indent added (for English docs, poetry, code blocks).

**健壮性**：`applyFirstLineIndent` 不吞异常（写不进去就报错，别静默丢格式——与"拒绝而非改坏"
哲学一致）；`applyHeading` 里原有的 outlineLvl try/catch 保持不动。

## 5. 测试与验收

仓库无 JUnit，按惯例扩 `WordEngineSelfTest`（runnable main）：

1. **writer / chinese**：H1 段 pPr 有 `jc=center`；正文段有 `ind firstLineChars=200 firstLine=420`；
   H2 无 jc；列表项无 ind；表格单元格段无 ind（后两条为反面守）——**全部落盘重载后再验一遍**。
2. **writer / plain**：同样输入 → H1 无 jc、正文无 ind。
3. **insert_paragraph**：无 style → 有 ind；Heading1 → 居中且 outlineLvl/粗体照旧；
   ListParagraph → 无 ind 无 jc（反面守）；plain 引擎下无 style 插入也不缩进。
4. **壳接线**（仿 F4b 反射手法）：XML `layout="plain"` → `cfg.chineseLayout=false`；
   单次 `layout="chinese"` 覆盖 XML 的 plain；`"PLAIN"`/带空白容错；空串视为未传；
   非法值 `"foo"` → 端到端报错且**文档未被创建**。
5. **文本层不回归**：`read` 输出逐字节等于加格式前（现有"编号一致"用例 + 断言正文段首字符
   不是空格）；现有全部断言保持绿。

**功能之外的三步验收**（老规矩）：

- 生成真实 docx 解包核对 XML（对齐/缩进落盘），文件交用户用 Word 打开确认；
- 真 LLM 冒烟：agent 实走一次"写一份中文文档"链路，人工看输出；
- 对抗性评审一轮（子 agent 挑刺）。

**提交**：dev 仓库提交；`tlobject-publish` 副本同步按惯例在完成时与用户确认。

## 6. 明确不做

- 不建 styles part / 不引入 numbering 定义（列表仍是"• "字面符号）；
- 不给正文设字体/字号（本次只解决对齐与缩进）；
- 不居中 H2~H6（用户选定）；
- 不动编辑类动作的格式行为（保格式是它们的契约）；
- 不做"自动清除模型手工加的全角空格"——只在描述里明确告知不要加（自动清除会破坏
  合法内容，如引文；模型不听话时人工可纠）。
