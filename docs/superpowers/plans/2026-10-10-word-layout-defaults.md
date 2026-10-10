# word 技能排版默认值 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** word 技能生成的 docx 默认套中文排版——H1 居中、正文段落首行缩进 2 字符；`layout` 参数（XML 默认 + 单次调用覆盖）可切 `plain` 关闭。

**Architecture:** 格式全部走**段落属性直接格式**（`w:jc` / `w:ind` 写进 pPr）——新建文档没有 styles part，样式定义那条路走不通；开关是 boolean `chineseLayout`，从技能壳解析后经 `JavaWordEngine.Config` 传到 `WordMarkdownWriter`（create/append）与 `WordTextEditor.insertParagraph`。仅作用于新写段落，编辑类动作与文本层零变化。

**Tech Stack:** Java 17 + Apache POI 5.5.1（poi-ooxml）；测试=仓库惯例的 runnable main 自测（`WordEngineSelfTest`，无 JUnit）。

**Spec:** `docs/superpowers/specs/2026-10-10-word-layout-defaults-design.md`

---

## 前置事实（已实测验证，照抄即可）

- `p.setAlignment(ParagraphAlignment.CENTER)` → `getAlignment()==CENTER`、`isAlignmentSet()==true`；**未设置时 `getAlignment()` 返回 LEFT 但 `isAlignmentSet()==false`**——"没有格式"的判据一律用 `isAlignmentSet()`。
- `getIndentationFirstLine()`（twips）未设置时返回 **-1**，设置 420 后返回 420。
- 写缩进的 API（已跑通）：
  ```java
  CTPPr pPr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
  CTInd ind = pPr.isSetInd() ? pPr.getInd() : pPr.addNewInd();
  ind.setFirstLineChars(java.math.BigInteger.valueOf(200));
  ind.setFirstLine(java.math.BigInteger.valueOf(420));
  ```
- 落盘重载后 `jc`/`ind`/文本全部保持。
- 自测基线：**219 passed, 0 failed**（改动前已跑过）。

**运行命令（每步复用，一律在仓库根目录执行）：**

```bash
# 编译 word 模块
/d/maven/bin/mvn -q -pl aiagent/word-java compile

# 跑自测（iconv 把 GBK 控制台输出转 UTF-8，中文描述才可读；末尾是 passed/failed 计数）
java -cp "aiagent/word-java/target/classes;$(cat aiagent/word-java/cp.txt)" \
     cn.tianlong.tlobject.aiagent.word.WordEngineSelfTest 2>&1 | iconv -f GBK -t UTF-8 | tail -8
```

---

## 文件结构（全部要动的文件）

| 文件 | 责任 |
|---|---|
| `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordMarkdownWriter.java` | markdown→docx 写入器：`writeBlocks` 带开关；`applyHeading(p,lv,开关)`；新增 `applyFirstLineIndent(p)` |
| `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordTextEditor.java` | `insertParagraph` 加开关参数（无样式→缩进，HeadingN→居中，其他样式→仅 setStyle） |
| `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/JavaWordEngine.java` | `Config.chineseLayout`（默认 true）+ 3 个调用点传参 |
| `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/TLWordJavaSkill.java` | XML `layout` 参数、`normalizeLayout`/`resolveLayout`、schema、平铺 key 列表、skillDescription |
| `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordEngineSelfTest.java` | 更新既有调用点 + 三组新断言块 |
| `demo/tlobject/src/main/resources/conf/demo/aiagent/fileAgent_config.xml` | `layout="chinese"` + 注释 + skillDescription 增补 |
| `CLAUDE.md` | word 条目补排版默认值说明 |

**调用点清单（已 grep 全量，只有这些）：**
- `writeBlocks`：`JavaWordEngine.java:183`、`:193`；自测 `:596`、`:625`
- `insertParagraph`：`JavaWordEngine.java:234`；自测 `:237`、`:260`、`:264`
- `applyHeading`：`WordTextEditor.java:419`（自身定义处 `WordMarkdownWriter.java:85`）
- `ensureEngine`：`TLWordJavaSkill.execute` 内 1 处 + 自测 F4b 反射 4 处（改签名后 4 处都要补参数）

---

### Task 1: writer 排版能力 + 引擎接线（create/append 路径）

**Files:**
- Modify: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordMarkdownWriter.java`
- Modify: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/JavaWordEngine.java`（Config + 2 处 writeBlocks）
- Test: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordEngineSelfTest.java`

- [ ] **Step 1: 自测先加断言（红）**

在 `WordEngineSelfTest.java` 的 d12 块之后（`// 落盘重载后标题仍是标题` 注释之前）插入：

```java
        // ================= 排版默认值：chinese = H1 居中 + 正文首行缩进 2 字符 =================
        // 判据落在段落属性（XML）上；未设置时 getAlignment() 返回 LEFT 但 isAlignmentSet()=false，
        // getIndentationFirstLine() 返回 -1——所以"没有格式"的判据用 isAlignmentSet/getInd
        org.apache.poi.xwpf.usermodel.XWPFDocument dLayW = newDoc();
        WordMarkdownWriter.writeBlocks(dLayW, WordMarkdown.parse(
                "# 报告\n\n正文一段\n\n## 一、背景\n\n- 甲\n\n| 月 | 值 |\n| 1 | 100 |"), true);
        org.apache.poi.xwpf.usermodel.XWPFParagraph layH1 = dLayW.getParagraphs().get(0);
        org.apache.poi.xwpf.usermodel.XWPFParagraph layBody = dLayW.getParagraphs().get(1);
        org.apache.poi.xwpf.usermodel.XWPFParagraph layH2 = dLayW.getParagraphs().get(2);
        org.apache.poi.xwpf.usermodel.XWPFParagraph layBullet = dLayW.getParagraphs().get(3);
        check("排版: H1 居中（jc 写进段落属性）",
                layH1.getAlignment() == org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER);
        check("排版: H2 不居中（jc 根本没设）", !layH2.isAlignmentSet());
        check("排版: 正文段首行缩进 = 2 字符（firstLineChars=200 + 兜底 firstLine=420）",
                layBody.getCTP().getPPr() != null && layBody.getCTP().getPPr().getInd() != null
                        && layBody.getCTP().getPPr().getInd().getFirstLineChars() != null
                        && layBody.getCTP().getPPr().getInd().getFirstLineChars().intValue() == 200
                        && "420".equals(String.valueOf(layBody.getCTP().getPPr().getInd().getFirstLine())));
        check("排版: 列表项无缩进（字面 • 已占位，再缩进是双缩进）",
                layBullet.getCTP().getPPr() == null || layBullet.getCTP().getPPr().getInd() == null);
        check("排版: 表格单元格段无缩进",
                dLayW.getTables().get(0).getRow(0).getCell(0).getParagraphs().get(0)
                        .getCTP().getPPr() == null
                        || dLayW.getTables().get(0).getRow(0).getCell(0).getParagraphs().get(0)
                        .getCTP().getPPr().getInd() == null);
        org.apache.poi.xwpf.usermodel.XWPFDocument dPlainW = newDoc();
        WordMarkdownWriter.writeBlocks(dPlainW, WordMarkdown.parse("# 报告\n\n正文一段"), false);
        check("排版: plain 下 H1 无 jc（plain = 什么都不加）",
                !dPlainW.getParagraphs().get(0).isAlignmentSet());
        check("排版: plain 下正文无缩进",
                dPlainW.getParagraphs().get(1).getIndentationFirstLine() == -1);
        java.nio.file.Path layWPath = saveTmp(dLayW, "layout.docx");
        try (java.io.InputStream layIn = java.nio.file.Files.newInputStream(layWPath);
             org.apache.poi.xwpf.usermodel.XWPFDocument layRe =
                     new org.apache.poi.xwpf.usermodel.XWPFDocument(layIn)) {
            org.apache.poi.xwpf.usermodel.XWPFParagraph lr0 = layRe.getParagraphs().get(0);
            org.apache.poi.xwpf.usermodel.XWPFParagraph lr1 = layRe.getParagraphs().get(1);
            check("排版: 落盘重载后 H1 仍居中",
                    lr0.getAlignment() == org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER);
            check("排版: 落盘重载后正文缩进仍在（chars=200 / tw=420）",
                    lr1.getCTP().getPPr() != null && lr1.getCTP().getPPr().getInd() != null
                            && lr1.getCTP().getPPr().getInd().getFirstLineChars().intValue() == 200
                            && "420".equals(String.valueOf(lr1.getCTP().getPPr().getInd().getFirstLine())));
            check("排版: read 文本 = 「[1] [Normal] 正文一段」（缩进不进文本层）",
                    WordTextExtractor.read(layRe, 1, 1).trim().equals("[1] [Normal] 正文一段"));
        }
```

同时把同文件既有的两处旧签名调用补上 `, true`（保持"中文排版默认"语义）：

```java
        WordMarkdownWriter.writeBlocks(d11, WordMarkdown.parse(
                "# 报告\n\n正文一段\n\n- 甲\n- 乙\n\n| 月 | 值 |\n| 1 | 100 |"), true);
```
```java
        WordMarkdownWriter.writeBlocks(d12, WordMarkdown.parse("这**很粗**啊"), true);
```

- [ ] **Step 2: 跑编译确认红**

```bash
/d/maven/bin/mvn -q -pl aiagent/word-java compile
```
Expected: 编译失败——`writeBlocks(XWPFDocument,List)` 已不存在 / 3 参方法未定义（错误指向自测与引擎的调用行）。

- [ ] **Step 3: 实现 WordMarkdownWriter**

文件顶部 import 区改为（新增两个 schema 类与 BigInteger）：

```java
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTInd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;

import java.math.BigInteger;
import java.util.List;
```

方法签名与两个分支改为：

```java
    /**
     * @param chineseLayout true = 中文排版默认值（H1 居中 + 正文段落首行缩进 2 字符）；
     *                      false = plain，新段落不加任何对齐/缩进（= 旧行为）
     */
    public static void writeBlocks(XWPFDocument doc, List<WordMarkdown.Block> blocks, boolean chineseLayout) {
        for (WordMarkdown.Block b : blocks) {
            switch (b.type) {
                case HEADING: {
                    XWPFParagraph p = doc.createParagraph();
                    int lv = Math.max(1, Math.min(6, b.level));
                    applyHeading(p, lv, chineseLayout);
                    writeSpans(p, b.spans, lv);
                    break;
                }
                case PARAGRAPH: {
                    XWPFParagraph p = doc.createParagraph();
                    if (chineseLayout) applyFirstLineIndent(p);
                    writeSpans(p, b.spans);
                    break;
                }
                // BULLET / NUMBERED / TABLE / PAGEBREAK 分支保持原样，一字不动
```

`applyHeading` 改为（原有注释保留，只加参数与居中一行）：

```java
    static void applyHeading(XWPFParagraph p, int lv, boolean chineseLayout) {
        p.setStyle("Heading" + lv);
        if (chineseLayout && lv == 1) p.setAlignment(ParagraphAlignment.CENTER);
        try {
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr pPr = p.getCTP().isSetPPr()
                    ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
            pPr.addNewOutlineLvl().setVal(java.math.BigInteger.valueOf(lv - 1));
        } catch (Throwable ignored) { }
    }
```

新增方法（放在 `headingFontSize` 之后）：

```java
    /**
     * 正文段落首行缩进 2 字符。
     *
     * firstLineChars（字符单位）是 Word/WPS 的正主——段落对话框显示"首行缩进 2 字符"，
     * 随字号自适应；firstLine（twips）兜底给不认字符单位的渲染器：
     * 2 × 五号 10.5pt = 21pt = 420 twips ≈ 0.74cm（中文 Word 默认字号下的"2 字符"）。
     * 不吞异常：写不进去就报错，不静默丢格式（与"拒绝而非改坏"哲学一致）。
     */
    static void applyFirstLineIndent(XWPFParagraph p) {
        CTPPr pPr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        CTInd ind = pPr.isSetInd() ? pPr.getInd() : pPr.addNewInd();
        ind.setFirstLineChars(BigInteger.valueOf(200));
        ind.setFirstLine(BigInteger.valueOf(420));
    }
```

- [ ] **Step 4: 实现 JavaWordEngine（本 Task 只动 create/append）**

`Config` 加字段：

```java
    public static class Config {
        public Path allowedRoot;
        public Path workDir;
        public boolean backup;            // 改动前是否留 .bak.docx（默认关，原子写已够安全）
        /** 中文排版（layout="chinese"，默认）：H1 居中 + 正文段落首行缩进 2 字符；false = plain */
        public boolean chineseLayout = true;
    }
```

`doCreate`（约 183 行）与 `doAppend`（约 193 行）改为：

```java
            WordMarkdownWriter.writeBlocks(doc, WordMarkdown.parse(content), cfg.chineseLayout);
```

- [ ] **Step 5: 编译并跑自测（绿）**

```bash
/d/maven/bin/mvn -q -pl aiagent/word-java compile
java -cp "aiagent/word-java/target/classes;$(cat aiagent/word-java/cp.txt)" \
     cn.tianlong.tlobject.aiagent.word.WordEngineSelfTest 2>&1 | iconv -f GBK -t UTF-8 | tail -8
```
Expected: `failed=0`，passed 从基线 219 增加（新增约 11 条）。

- [ ] **Step 6: 提交**

```bash
git add aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordMarkdownWriter.java \
        aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/JavaWordEngine.java \
        aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordEngineSelfTest.java
git commit -m "word 排版: create/append 默认套中文排版——H1 居中 + 正文首行缩进 2 字符（直接格式，含落盘回归断言）"
```

---

### Task 2: insert_paragraph 同源排版

**Files:**
- Modify: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordTextEditor.java`
- Modify: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/JavaWordEngine.java`（doInsertParagraph）
- Test: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordEngineSelfTest.java`

- [ ] **Step 1: 自测加断言（红）**

在 F5 段落（`// 漏传 text 对 insert_paragraph 同样报错` 那两条 check 之后）插入：

```java
        // ================= 排版: insert_paragraph 与 writer 同源 =================
        JavaWordEngine.Result cLayE = eng.execute("create",
                map("path", "排版插入.docx", "content", "正文甲\n\n正文乙"));
        check("排版插入前置: 文档建好", cLayE.ok);
        check("排版: insert 无 style → ok",
                eng.execute("insert_paragraph",
                        map("path", "排版插入.docx", "index", 0, "text", "新正文")).ok);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("排版插入.docx"))) {
            check("排版: insert 无 style 的段落带首行缩进 2 字符",
                    d.getParagraphs().get(0).getIndentationFirstLine() == 420);
        }
        check("排版: insert Heading1 → ok",
                eng.execute("insert_paragraph",
                        map("path", "排版插入.docx", "index", 0, "text", "新标题",
                                "style", "Heading1")).ok);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("排版插入.docx"))) {
            check("排版: insert Heading1 居中（与 writer 同源）",
                    d.getParagraphs().get(0).getAlignment()
                            == org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER);
        }
        check("排版: insert Heading2 → ok",
                eng.execute("insert_paragraph",
                        map("path", "排版插入.docx", "index", 0, "text", "二级",
                                "style", "Heading2")).ok);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("排版插入.docx"))) {
            check("排版: insert Heading2 不居中（只有 H1 居中）",
                    !d.getParagraphs().get(0).isAlignmentSet());
        }
        check("排版: insert ListParagraph → ok",
                eng.execute("insert_paragraph",
                        map("path", "排版插入.docx", "index", 0, "text", "列表",
                                "style", "ListParagraph")).ok);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("排版插入.docx"))) {
            check("排版: insert 显式样式段无缩进无居中（显式样式优先，反面守）",
                    d.getParagraphs().get(0).getIndentationFirstLine() == -1
                            && !d.getParagraphs().get(0).isAlignmentSet());
        }
        // plain 引擎：create 与 insert 全链路都不加格式（端到端，不是只看 Config 字段）
        JavaWordEngine.Config cfgPlainE = new JavaWordEngine.Config();
        cfgPlainE.allowedRoot = root;
        cfgPlainE.workDir = work;
        cfgPlainE.chineseLayout = false;
        JavaWordEngine engPlainE = new JavaWordEngine(cfgPlainE);
        check("排版: plain 引擎 create ok",
                engPlainE.execute("create",
                        map("path", "排版plain.docx", "content", "# 报告\n\n正文")).ok);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("排版plain.docx"))) {
            check("排版: plain 引擎 create 的 H1 无 jc 且正文无缩进",
                    !d.getParagraphs().get(0).isAlignmentSet()
                            && d.getParagraphs().get(1).getIndentationFirstLine() == -1);
        }
        check("排版: plain 引擎 insert 无 style → ok",
                engPlainE.execute("insert_paragraph",
                        map("path", "排版plain.docx", "index", 0, "text", "裸段")).ok);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("排版plain.docx"))) {
            check("排版: plain 引擎 insert 无 style 不缩进",
                    d.getParagraphs().get(0).getIndentationFirstLine() == -1);
        }
```

同时给既有的 3 处直接调用补参数（传 `false`——它们只测位置/顺序，保持旧语义当回归守）：

```java
        WordTextEditor.insertParagraph(d9, 1, "插入", null, true, false);
        WordTextEditor.insertParagraph(d9b, 1, "MID", null, false, false);
        WordTextEditor.insertParagraph(d9b, d9b.getParagraphs().size() - 1, "TAIL", null, false, false);
```

- [ ] **Step 2: 跑编译确认红**

```bash
/d/maven/bin/mvn -q -pl aiagent/word-java compile
```
Expected: 编译失败——`insertParagraph` 6 参重载不存在。

- [ ] **Step 3: 实现 WordTextEditor.insertParagraph**

签名与样式分支改为（其余代码——越界检查、before/after 游标、run 写入——一字不动）：

```java
    public static void insertParagraph(XWPFDocument doc, int index, String text,
                                       String style, boolean before, boolean chineseLayout) {
        // ...前面越界检查与 np 创建保持原样...

        int headingLv = 0;
        if (style != null && !style.isEmpty()) {
            java.util.regex.Matcher hm =
                    java.util.regex.Pattern.compile("(?i)^heading\\s*([1-6])$").matcher(style.trim());
            if (hm.matches()) {
                headingLv = Integer.parseInt(hm.group(1));
                WordMarkdownWriter.applyHeading(np, headingLv, chineseLayout);
            } else {
                np.setStyle(style);
            }
        } else if (chineseLayout) {
            // 无样式 = 正文段落：与 writer 的 PARAGRAPH 分支同口径
            WordMarkdownWriter.applyFirstLineIndent(np);
        }
        XWPFRun run = np.createRun();
        run.setText(text);
        if (headingLv > 0) {
            run.setBold(true);
            run.setFontSize(WordMarkdownWriter.headingFontSize(headingLv));
        }
    }
```

- [ ] **Step 4: 实现 JavaWordEngine.doInsertParagraph**

```java
            WordTextEditor.insertParagraph(doc, oi(in, "index", -1),
                    requiredText(in), od(in, "style"), before, cfg.chineseLayout);
```

- [ ] **Step 5: 编译并跑自测（绿）**

同 Task 1 Step 5 两条命令。Expected: `failed=0`。

- [ ] **Step 6: 提交**

```bash
git add aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordTextEditor.java \
        aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/JavaWordEngine.java \
        aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordEngineSelfTest.java
git commit -m "word 排版: insert_paragraph 与 writer 同源——无样式段落缩进、Heading1 居中、显式样式不插手"
```

---

### Task 3: 壳层 layout 参数（XML 默认 + 单次覆盖 + 校验）

**Files:**
- Modify: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/TLWordJavaSkill.java`
- Test: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordEngineSelfTest.java`

- [ ] **Step 1: 自测加断言（红）**

在 F4b 段结束（`check("F4b: 平铺参数 backup=true 端到端产出 壳测.bak.docx"...)` 的 try/catch 之后）插入：

```java
        // ================= 排版: layout 参数接线（XML 默认 + 单次覆盖 + 校验）=================
        check("排版接线: normalizeLayout 大小写/空白容错，非法返回 null",
                "plain".equals(TLWordJavaSkill.normalizeLayout(" PLAIN "))
                        && "chinese".equals(TLWordJavaSkill.normalizeLayout("Chinese"))
                        && TLWordJavaSkill.normalizeLayout("foo") == null
                        && TLWordJavaSkill.normalizeLayout(null) == null);

        TLWordJavaSkill skLayP = new TLWordJavaSkill("word");
        java.util.HashMap<String, String> spLayP = new java.util.HashMap<>();
        spLayP.put("allowedRootPath", root.toString());
        spLayP.put("layout", "plain");
        pf.set(skLayP, spLayP);
        skLayP.setModuleParams();

        java.lang.reflect.Method rlM = TLWordJavaSkill.class.getDeclaredMethod(
                "resolveLayout", java.util.Map.class);
        rlM.setAccessible(true);
        check("排版接线: 未传 → 走 XML（plain）",
                Boolean.FALSE.equals(rlM.invoke(skLayP, map("action", "create"))));
        check("排版接线: 空串视为未传 → 走 XML（plain）",
                Boolean.FALSE.equals(rlM.invoke(skLayP, map("layout", ""))));
        check("排版接线: 单次 layout=chinese 覆盖 XML",
                Boolean.TRUE.equals(rlM.invoke(skLayP, map("layout", "chinese"))));
        check("排版接线: 大小写/空白容错（\" PLAIN \" → false）",
                Boolean.FALSE.equals(rlM.invoke(skLayP, map("layout", " PLAIN "))));
        String layErr = null;
        try { rlM.invoke(skLayP, map("layout", "foo")); }
        catch (java.lang.reflect.InvocationTargetException e) { layErr = String.valueOf(e.getCause()); }
        check("排版接线: 非法值抛 IllegalArgumentException（消息含合法取值，模型可自纠）",
                layErr != null && layErr.contains("unknown layout") && layErr.contains("chinese | plain"));

        check("排版接线: parameterSchema 里有 layout（LLM 才看得见这个参数）",
                ((java.util.Map<?, ?>) scf.get(skLayP)).containsKey("layout"));

        // 端到端：XML plain 真的落到产出文件上
        try {
            cn.tianlong.tlobject.base.TLMsg mLayP = new cn.tianlong.tlobject.base.TLMsg();
            mLayP.setParam("action", "create");
            mLayP.setParam("path", "data/default/documents/排版壳plain.docx");
            mLayP.setParam("content", "# 报告\n\n正文");
            check("排版接线: XML layout=plain 端到端 create 成功",
                    Boolean.TRUE.equals(skLayP.execute(null, mLayP)
                            .getParam(cn.tianlong.tlobject.base.TLParamString.RESULT)));
            try (org.apache.poi.xwpf.usermodel.XWPFDocument d =
                         openDocx(work.resolve("排版壳plain.docx"))) {
                check("排版接线: XML plain → 产出文档 H1 无 jc、正文无缩进",
                        !d.getParagraphs().get(0).isAlignmentSet()
                                && d.getParagraphs().get(1).getIndentationFirstLine() == -1);
            }
        } catch (Throwable t) {
            check("排版接线: XML layout=plain 端到端 create（抛异常: " + t + "）", false);
        }
        // 端到端：单次参数覆盖 XML（走平铺 key 列表——这条同时守"平铺列表漏写 layout"）
        try {
            cn.tianlong.tlobject.base.TLMsg mLayO = new cn.tianlong.tlobject.base.TLMsg();
            mLayO.setParam("action", "create");
            mLayO.setParam("path", "data/default/documents/排版壳chinese.docx");
            mLayO.setParam("content", "# 报告\n\n正文");
            mLayO.setParam("layout", "chinese");
            check("排版接线: 单次 layout=chinese 覆盖 XML plain（端到端）",
                    Boolean.TRUE.equals(skLayP.execute(null, mLayO)
                            .getParam(cn.tianlong.tlobject.base.TLParamString.RESULT)));
            try (org.apache.poi.xwpf.usermodel.XWPFDocument d =
                         openDocx(work.resolve("排版壳chinese.docx"))) {
                check("排版接线: 覆盖后 H1 居中且正文有缩进",
                        d.getParagraphs().get(0).getAlignment()
                                == org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER
                                && d.getParagraphs().get(1).getIndentationFirstLine() == 420);
            }
        } catch (Throwable t) {
            check("排版接线: 单次覆盖端到端（抛异常: " + t + "）", false);
        }
        // 端到端：非法值 → 干净回执 + 文档未被创建（走 execute 的参数错误分支，不落日志）
        try {
            cn.tianlong.tlobject.base.TLMsg mLayBad = new cn.tianlong.tlobject.base.TLMsg();
            mLayBad.setParam("action", "create");
            mLayBad.setParam("path", "data/default/documents/非法排版.docx");
            mLayBad.setParam("content", "甲");
            mLayBad.setParam("layout", "foo");
            cn.tianlong.tlobject.base.TLMsg outBad = skLayP.execute(null, mLayBad);
            String errBad = String.valueOf(
                    outBad.getParam(cn.tianlong.tlobject.base.TLParamString.AI_P_SKILLOUTPUT));
            check("排版接线: 非法 layout → ok=false 且报错点名取值",
                    Boolean.FALSE.equals(outBad.getParam(cn.tianlong.tlobject.base.TLParamString.RESULT))
                            && errBad.contains("unknown layout") && errBad.contains("foo"));
            check("排版接线: 非法 layout 时文档未被创建",
                    !java.nio.file.Files.exists(work.resolve("非法排版.docx")));
        } catch (Throwable t) {
            check("排版接线: 非法 layout 报错（抛异常: " + t + "）", false);
        }
```

同时把 F4b 的反射查找与 4 处 invoke 更新为新签名（`ensureEngine` 从 2 参变 3 参）：

```java
        java.lang.reflect.Method me = TLWordJavaSkill.class.getDeclaredMethod(
                "ensureEngine", cn.tianlong.tlobject.base.TLMsg.class,
                java.util.Map.class, boolean.class);
        me.setAccessible(true);
```
```java
        cf.get(me.invoke(sk, msg0, map("action", "replace"), true));
```
```java
        cf.get(me.invoke(sk, msg0, map("action", "replace", "backup", "false"), true));
```
```java
        cf.get(me.invoke(sk, msg0, map("action", "replace", "backup", true), true));
```
```java
        cf.get(me.invoke(sk2, msg0, map("action", "replace"), true));
```

- [ ] **Step 2: 跑编译确认红**

```bash
/d/maven/bin/mvn -q -pl aiagent/word-java compile
```
Expected: 编译失败——`normalizeLayout`/`resolveLayout` 不存在、`ensureEngine` 还是 2 参。

- [ ] **Step 3: 实现 TLWordJavaSkill**

字段区加（`backup` 字段之后）：

```java
    /** 排版默认值：chinese = H1 居中 + 正文首行缩进 2 字符；plain = 不加任何对齐/缩进 */
    private String layout = "chinese";
```

`setModuleParams` 的 params 读取区加：

```java
            if (params.get("layout") != null) {
                String lv = normalizeLayout(params.get("layout"));
                if (lv != null) layout = lv;
                else putLog("word skill: unknown layout \"" + params.get("layout")
                        + "\" in XML params, fallback to chinese", LogLevel.WARN);
            }
```

`execute` 的平铺 key 列表加 `"layout"`：

```java
            for (String k : new String[]{"action", "path", "content", "find", "replace", "index",
                    "mode", "text", "style", "position", "table", "row", "col", "values", "data",
                    "output", "overwrite", "from", "to", "backup", "layout"}) {
```

`execute` 在 `String action = ...` 判空之后、主 `try` 之前加：

```java
        boolean chineseLayout;
        try {
            chineseLayout = resolveLayout(input);
        } catch (IllegalArgumentException e) {
            // 参数错误是"模型的锅"：与引擎层 Result.fail 同口径，干净回执、不落 WARN 日志，
            // 模型读得到就能自纠。若走下面通用 catch 会先 putLog——无工厂的自测环境还会 NPE。
            return createMsg().setParam(RESULT, false).setParam(AI_P_SKILLOUTPUT,
                    "{\"ok\":false,\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
```
主 `try` 里第一行改为：

```java
            JavaWordEngine eng = ensureEngine(msg, input, chineseLayout);
```

新增两个方法（放在 `ensureEngine` 之前）：

```java
    /** 单次调用 layout 覆盖 XML 默认；空/空白视为未传；非法值抛（execute 转成 JSON 错误）。 */
    private boolean resolveLayout(Map<String, Object> input) {
        Object v = (input == null) ? null : input.get("layout");
        String s = (v == null) ? null : String.valueOf(v).trim();
        if (s == null || s.isEmpty()) return !"plain".equals(layout);
        String n = normalizeLayout(s);
        if (n == null)
            throw new IllegalArgumentException(
                    "unknown layout: \"" + s + "\" (expected chinese | plain)");
        return "chinese".equals(n);
    }

    /** 归一化 layout 取值（大小写不敏感、去空白）；null = 非法 */
    static String normalizeLayout(String v) {
        if (v == null) return null;
        String s = v.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.equals("chinese")) return "chinese";
        if (s.equals("plain")) return "plain";
        return null;
    }
```

`ensureEngine` 签名与 Config 赋值：

```java
    private JavaWordEngine ensureEngine(TLMsg msg, Map<String, Object> input, boolean chineseLayout) {
        JavaWordEngine.Config cfg = new JavaWordEngine.Config();
        cfg.allowedRoot = Paths.get(allowedRootPath);
        cfg.chineseLayout = chineseLayout;
        // ...其余 userId / workDir / backup 逻辑一字不动...
```

`parameterSchema` 加（`backup` 那行之后）：

```java
            parameterSchema.put("layout", prop("string", "Document layout: chinese (default) = H1 "
                    + "headings centered + 2-character first-line indent on body paragraphs; "
                    + "plain = no alignment/indent added (use for English docs, poetry, code blocks)"));
```

skillDescription 增加一段（类里那份是 **XML 未配时的活兜底**，不是死代码——基类已不再预填，`effectiveSkillDescription()` 直接取子类值；两份都要改，语义一致即可）：

```java
                    + "CREATE/APPEND/INSERT apply a layout automatically: with layout=\"chinese\" "
                    + "(default) level-1 headings are centered and body paragraphs get a 2-character "
                    + "first-line indent - do NOT add leading spaces or indent characters yourself. "
                    + "Pass layout=\"plain\" for English documents, poetry, or content where no "
                    + "formatting is wanted. "
```

- [ ] **Step 4: 编译并跑自测（绿）**

同 Task 1 Step 5 两条命令。Expected: `failed=0`。若 `非法 layout` 那条红且报 NPE，说明走错了通用 catch（校验没提前到 resolveLayout 的局部 catch）——回查 Step 3 的插入位置。

- [ ] **Step 5: 提交**

```bash
git add aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/TLWordJavaSkill.java \
        aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordEngineSelfTest.java
git commit -m "word 排版: layout 参数接线——XML 默认 chinese、单次调用可覆盖 plain、非法值干净回执不落日志"
```

---

### Task 4: 配置与文档

**Files:**
- Modify: `demo/tlobject/src/main/resources/conf/demo/aiagent/fileAgent_config.xml`
- Modify: `CLAUDE.md`

- [ ] **Step 1: fileAgent_config.xml**

word 技能的注释块末尾追加一段（紧挨原有 backup 注释之后）：

```xml
             layout 参数：排版默认值。chinese（默认，也可显式写上）= 一级标题（#）居中 +
             正文段落首行缩进 2 字符（w:ind firstLineChars=200 兜底 firstLine=420，直接格式，
             新建文档无 styles part 也能生效）；plain = 新段落不加任何对齐/缩进，适合英文文档、
             诗歌、代码块。单次调用可传 layout 参数覆盖这里的配置（模型自己判断文档类型）。 -->
```

`<skill name="word" ...>` 加一个属性（放在 `workDir` 之后）：

```xml
               layout="chinese"
```

skillDescription 末尾（`...tell the user which paragraph number could not be changed.` 之后、收尾引号之前）追加：

```
 CREATE/APPEND/INSERT apply a layout automatically: with layout=chinese (default) level-1 headings are centered and body paragraphs get a 2-character first-line indent - do NOT add leading spaces or indent characters yourself. Pass layout=plain for English documents, poetry, or content where no formatting is wanted.
```

**注意**：XML 属性值内不能出现双引号，所以上面写 `layout=chinese`（不带引号）；属性值不要换行。

- [ ] **Step 2: 同步到运行目录并确认落位**

```bash
/d/maven/bin/mvn -q -pl demo/tlobject process-resources
grep -c "layout=\"chinese\"" demo/tlobject/target/classes/conf/demo/aiagent/fileAgent_config.xml
grep -o "layout=plain for English documents" demo/tlobject/target/classes/conf/demo/aiagent/fileAgent_config.xml
```
Expected: `1` 与命中一行（运行目录用的是 target/classes 下的副本，不 install）。

- [ ] **Step 3: CLAUDE.md**

在 word 技能那条 bullet（`- \`word\` 技能（\`.docx\` 读/写/改…）`）末尾追加：

```
排版默认值：`layout="chinese"`（默认）→ 一级标题居中 + 正文段落首行缩进 2 字符（直接格式写 pPr：`w:jc` + `w:ind firstLineChars=200/firstLine=420`，新建文档无 styles part 依然生效）；`layout="plain"` 不加任何格式（英文文档/诗歌）；XML 配默认、单次调用可覆盖；列表与表格单元格不缩进
```

- [ ] **Step 4: 提交**

```bash
git add demo/tlobject/src/main/resources/conf/demo/aiagent/fileAgent_config.xml CLAUDE.md
git commit -m "word 排版: fileAgent 配置显式 layout=chinese + 描述告知模型不要手工加空格；CLAUDE.md 补排版默认值"
```

---

### Task 5: 端到端验收与交付

**Files:**
- 复用（不提交）：`_wordprobe/Probe.java`（第一次验收生成的探针）

- [ ] **Step 1: 生成验收用 docx（两种 layout 各一份，交用户 Word 打开）**

修改 `_wordprobe/Probe.java` 的 main 为（或照抄现有内容加第二份 plain）：

```java
        JavaWordEngine.Result r = eng.execute("create", map("path","验收_中文排版.docx","content",md));
        System.out.println("chinese ok=" + r.ok);
        cfg.chineseLayout = false;
        JavaWordEngine eng2 = new JavaWordEngine(cfg);
        JavaWordEngine.Result r2 = eng2.execute("create", map("path","验收_plain.docx","content",md));
        System.out.println("plain ok=" + r2.ok);
```
其中 `md = "# 关于年度工作的报告\n\n这是第一段正文，用来观察首行缩进。\n\n## 一、背景\n\n第二段正文。\n\n- 列表项甲\n"`。

```bash
javac -encoding UTF-8 -cp "aiagent/word-java/target/classes;$(cat aiagent/word-java/cp.txt)" \
      -d _wordprobe _wordprobe/Probe.java
java -cp "_wordprobe;aiagent/word-java/target/classes;$(cat aiagent/word-java/cp.txt)" Probe
```
Expected: 两份文件生成于 `_wordprobe/data/default/documents/`。

- [ ] **Step 2: 解包核对 XML（把表格结果贴给用户）**

```bash
cd _wordprobe && for f in 验收_中文排版 验收_plain; do
  rm -rf "ex_$f" && unzip -o -q "data/default/documents/$f.docx" word/document.xml -d "ex_$f"
  echo "== $f =="
  PYTHONUTF8=1 python -X utf8 -c "
import re
xml=open('ex_$f/word/document.xml',encoding='utf-8').read()
for m in re.finditer(r'<w:p\b.*?</w:p>',xml,re.S):
    p=m.group(0)
    txt=''.join(re.findall(r'<w:t[^>]*>(.*?)</w:t>',p))
    ppr=re.search(r'<w:pPr>.*?</w:pPr>',p,re.S); ppr=ppr.group(0) if ppr else ''
    jc=re.search(r'<w:jc[^/]*/>',ppr); ind=re.search(r'<w:ind[^/]*/>',ppr)
    print('%-30s| jc:%-22s| ind:%s'%(txt[:28], jc.group(0) if jc else '无', ind.group(0) if ind else '无'))
"
done; cd /d/tlobjectapp/tlobject
```
Expected：`验收_中文排版` 的标题行 `jc:<w:jc w:val="center"/>`、正文行 `ind:<w:ind w:firstLine="420" w:firstLineChars="200"/>`；`验收_plain` 全"无"。**把两份文件路径给用户，请他 Word 打开亲眼确认。**

- [ ] **Step 3: 真 LLM 冒烟（agent 实际走一次 create 链路）**

```bash
/d/maven/bin/mvn -q -pl aiagent/word-java compile
printf '用 word 技能生成一份文档：文件名 冒烟_%s.docx，标题《排版冒烟测试》，正文写两段话，每段两三句\n/exit\n' \
  "$(date +%H%M)" | cmd //c aistart.bat
```
（timeout 给足 300 秒；输出落 `_run_out.txt` 供查）

Expected: 控制台出现工具调用（word create）与完成回执。然后：

```bash
ls -t data/*/documents/*.docx | head -3
```
找到新文件后解包核对（同 Step 2 的 python 片段），确认 jc/ind 与文本都在。
**若管道 stdin 进不了 jline 控制台**（终端无回显/直接退出）：不要硬折腾——把上面那条中文 prompt 原样给用户，请他在自己的终端跑 `aistart.bat` 手工发一次，再回来验产物（这是既有约定的 fallback）。

- [ ] **Step 4: 对抗性评审（子 agent 一轮）**

派一个 general-purpose 子 agent，任务是"挑刺"：
- 给它 spec、plan、`git diff 7bd1490^..HEAD -- aiagent/word-java` 与自测输出；
- 要求它重点核：`applyFirstLineIndent` 的互逆/回归风险（是否影响 read/replace 文本层）、`insert_paragraph` 对**已有文档**的副作用面、`layout` 解析的边界（大小写/空白/非字符串值如数字）、`plain` 的"不加 ≠ 清除"语义、自测断言是否有假绿（例如只断言内存不看落盘、断言太弱）。
- 它报的每条由我逐条核实（属实的修，不属实的说明理由），修完重跑自测。

- [ ] **Step 5: 交付确认**

- 汇总：自测计数（基线 219 → 新数）、两份验收 docx 路径、冒烟产物、评审结论；
- 请用户确认 Word 里视觉效果（标题居中、正文缩进两格）；
- 问用户是否按惯例同步 `D:\tlobjectapp\tlobject-publish`（双推）；
- 删除或保留 `_wordprobe/`（未跟踪，不提交；验收文件建议留着给用户看）。

---

## Self-Review 记录（写计划时已做）

- **Spec 覆盖**：§3 行为规格 → Task 1/2/3 全覆盖（H1 居中、正文缩进、列表/表格例外、insert 三态、plain、非法值两级、空串视为未传）；§4 接线 → Task 1–4；§5 测试 → Task 1/2/3 断言块 + Task 5 验收；§6 不做 → 计划中无对应改动（无 styles part/numbering/字体字号改动）。
- **占位扫描**：无 TBD/TODO；每个代码步骤都是完整可抄的代码块。
- **签名一致性**：`writeBlocks(doc, blocks, boolean)`、`applyHeading(p, lv, boolean)`、`applyFirstLineIndent(p)`、`insertParagraph(..., before, boolean)`、`ensureEngine(msg, input, boolean)`、`resolveLayout(Map)`（私有，反射名一致）、`normalizeLayout(String)`（包内静态）——全计划统一。
- **已知取舍**：自测里 `resolveLayout` 走反射（私有方法，无工厂环境不能端到端触发 putLog 分支）；XML 非法值的 WARN+回退由 `normalizeLayout` 单测 + 代码审阅覆盖，不做端到端（无工厂 putLog 会 NPE，F4b 同款约束）。
