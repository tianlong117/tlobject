# word-docx 技能（Java 版）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 aiagent 加一个 Word `.docx` 读/写/改技能，作为独立模块 `aiagent/word-java`，并把全工程 POI 从 4.1.2 升到 5.5.1。

**Architecture:** 技能壳 `TLWordJavaSkill`（框架接线、路径安全、schema）+ 纯 POI 引擎 `JavaWordEngine`（action 分发）分开；引擎侧再按职责拆 `WordTextExtractor`（读）/ `WordMarkdownWriter`（写）/ `WordTextEditor`（改）/ `WordMarkdown`（markdown 解析，无 POI）。引擎不依赖框架，用 `WordEngineSelfTest` 的 runnable main 迭代。

**Tech Stack:** Java 17、Apache POI 5.5.1（XWPF）、Gson（框架已有）、Maven（`/d/maven/bin/mvn`，本地仓库 `D:\repository`）

**Spec:** `docs/superpowers/specs/2026-10-07-word-docx-skill-design.md`

---

## 关键约定（全程遵守，不要各写各的）

**段落号**：一律是 `doc.getParagraphs()` 的下标（**只数段落，不数表格**）。`read` 的输出中
表格也带 `[表格 N]` 编号，但那是**表格序号**（`doc.getTables()` 的下标），两套编号独立。
所有 `index` / `table` 参数按此定义。

**引擎返回**：

```java
public static class Result {
    public boolean ok;
    public boolean textMode;          // true=读类，AI_P_SKILLOUTPUT 放原始文本
    public String text;               // textMode 时有效
    public Map<String,Object> json;   // !textMode 时有效（写/改类回执）
}
```

**动作名**（13 个，全部小写）：`read` `outline` `tables` `info` `create` `append`
`replace` `set_paragraph` `insert_paragraph` `delete_paragraph` `set_table_cell`
`add_table_row` `fill_template`

**引擎不 import 任何 `cn.tianlong.tlobject.*`** —— 这是能快速自测的前提。

---

## Phase A：POI 全局升级

先做这一阶段，因为它是后面一切的前提；且它独立可回归（Excel 链路）。

### Task 1: 下载 POI 5.5.1 依赖到本地仓库

**Files:**
- 无源码改动（填充 `D:\repository`）

- [ ] **Step 1: 下载全部所需 artifact**

```bash
/d/maven/bin/mvn -q dependency:get -Dartifact=org.apache.poi:poi:5.5.1
/d/maven/bin/mvn -q dependency:get -Dartifact=org.apache.poi:poi-ooxml:5.5.1
/d/maven/bin/mvn -q dependency:get -Dartifact=org.apache.poi:poi-ooxml-lite:5.5.1
/d/maven/bin/mvn -q dependency:get -Dartifact=org.apache.xmlbeans:xmlbeans:5.3.0
/d/maven/bin/mvn -q dependency:get -Dartifact=org.apache.commons:commons-compress:1.28.0
/d/maven/bin/mvn -q dependency:get -Dartifact=commons-io:commons-io:2.21.0
/d/maven/bin/mvn -q dependency:get -Dartifact=com.github.virtuald:curvesapi:1.08
/d/maven/bin/mvn -q dependency:get -Dartifact=org.apache.commons:commons-collections4:4.5.0
/d/maven/bin/mvn -q dependency:get -Dartifact=commons-codec:commons-codec:1.20.0
/d/maven/bin/mvn -q dependency:get -Dartifact=com.zaxxer:SparseBitSet:1.3
```

- [ ] **Step 2: 确认全部落地**

```bash
ls D:/repository/org/apache/poi/poi/5.5.1/poi-5.5.1.jar \
   D:/repository/org/apache/poi/poi-ooxml/5.5.1/poi-ooxml-5.5.1.jar \
   D:/repository/org/apache/poi/poi-ooxml-lite/5.5.1/poi-ooxml-lite-5.5.1.jar \
   D:/repository/org/apache/xmlbeans/xmlbeans/5.3.0/xmlbeans-5.3.0.jar \
   D:/repository/org/apache/commons/commons-compress/1.28.0/commons-compress-1.28.0.jar \
   D:/repository/commons-io/commons-io/2.21.0/commons-io-2.21.0.jar \
   D:/repository/com/github/virtuald/curvesapi/1.08/curvesapi-1.08.jar \
   D:/repository/org/apache/commons/commons-collections4/4.5.0/commons-collections4-4.5.0.jar \
   D:/repository/commons-codec/commons-codec/1.20.0/commons-codec-1.20.0.jar \
   D:/repository/com/zaxxer/SparseBitSet/1.3/SparseBitSet-1.3.jar
```

Expected: 10 个路径全部列出，无 "No such file"。
若某个下载失败（aliyun 镜像偶发缺件），把该 artifact 单独重跑一次。

### Task 2: 根 pom 加 dependencyManagement + execl 升版

**Files:**
- Modify: `pom.xml`（根，`</build>` 之后、`</project>` 之前插入）
- Modify: `execl/pom.xml:19-33`

- [ ] **Step 1: 根 pom 插入 dependencyManagement**

在根 `pom.xml` 的 `</build>`（第 63 行）**之后**、`</project>` 之前插入：

```xml
    <!-- POI 版本统一：execl（Excel）与 aiagent/word-java（Word）必须同版本，
         同 classpath 上两个 POI 版本会同名类"先到先得"，行为不可预测 -->
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.apache.poi</groupId>
                <artifactId>poi</artifactId>
                <version>5.5.1</version>
            </dependency>
            <dependency>
                <groupId>org.apache.poi</groupId>
                <artifactId>poi-ooxml</artifactId>
                <version>5.5.1</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
```

- [ ] **Step 2: execl/pom.xml 去掉写死版本**

把 `execl/pom.xml` 第 20-33 行两个 dependency 的 `<version>4.1.2</version>` 两行**删掉**
（改吃父 pom 的 dependencyManagement），并更新注释：

```xml
        <!-- Used to work with the older excel file format - `.xls` -->
        <!-- 版本由根 pom 的 dependencyManagement 统一为 5.5.1（与 aiagent/word-java 共用） -->
        <dependency>
            <groupId>org.apache.poi</groupId>
            <artifactId>poi</artifactId>
        </dependency>

        <!-- Used to work with the newer excel file format - `.xlsx` -->
        <dependency>
            <groupId>org.apache.poi</groupId>
            <artifactId>poi-ooxml</artifactId>
        </dependency>
```

- [ ] **Step 3: 验证 pom 结构合法**

Run: `/d/maven/bin/mvn -q -pl execl help:effective-pom -Doutput=/tmp/eff.xml && grep -A2 "poi-ooxml" /tmp/eff.xml | head -20`
Expected: 输出中 poi-ooxml 的 version 是 `5.5.1`。XML 不合法会直接报 parse error。

### Task 3: TLExeclFileUtils 一行 API 迁移

**Files:**
- Modify: `execl/src/main/java/cn/tianlong/tlobject/execl/TLExeclFileUtils.java`（import 区 + 156 行）

- [ ] **Step 1: 加显式 import**

在 `import org.apache.poi.ss.usermodel.Cell;`（第 9 行）**之后**插入一行：

```java
import org.apache.poi.ss.usermodel.DateUtil;
```

- [ ] **Step 2: 替换被删除的 API**

第 156 行 `if(HSSFDateUtil.isCellDateFormatted(cell)) {` 改为：

```java
                    if(DateUtil.isCellDateFormatted(cell)) {     // POI 5.0 起 HSSFDateUtil 已删除
```

- [ ] **Step 3: 编译**

Run: `/d/maven/bin/mvn -q -pl execl -am clean install -DskipTests`
Expected: BUILD SUCCESS。

若报 `cannot find symbol: class HSSFDateUtil` 之外的错，说明还有别处用了 5.x 删除的 API——
按报错逐个修（`HSSFDateUtil` 是本次唯一已知的），**不要**用 `-Dmaven.compiler.failOnError=false` 绕过。

- [ ] **Step 4: 确认无残留旧 API**

Run: `grep -rn "HSSFDateUtil\." --include=*.java . | grep -v target`
Expected: 无输出。

⚠️ 模式里的 `\.` 是故意的：Step 2 的注释里含有 "HSSFDateUtil" 这个词（作为说明文字，
有价值，保留），不带点才 grep 会命中它造成假失败。带点意味着"还在调用这个类的方法"。

- [ ] **Step 5: Commit**

```bash
git add pom.xml execl/pom.xml execl/src/main/java/cn/tianlong/tlobject/execl/TLExeclFileUtils.java
git commit -m "POI 4.1.2→5.5.1：根 pom 统一版本 + execl 迁移 HSSFDateUtil（5.0 已删除）"
```

### Task 4: 5 个 aistart*.bat classpath 升级

**Files:**
- Modify: `aistart.bat`、`aistart-web.bat`（**入库**）
- Modify 但**不入库**：`aistart_dq.bat`、`aistart_tl.bat`、`aistart_xm.bat`

5 个文件的 POI 段**完全相同**，改动也完全一致。

⚠️ **后 3 个被 `.gitignore:59-62` 忽略**（注释写着"个人变体启动脚本（机器相关路径）"，
`git check-ignore` 已确认）。它们**必须在本机改**（否则那 3 个入口启动即 `NoClassDefFoundError`），
但**不要 `git add -f` 强行入库**——那会把本机路径塞进公共仓库，违背 ignore 规则的意图。
提交时只 `git add aistart.bat aistart-web.bat`。

- [ ] **Step 1: 逐个替换 POI 段**

每个 bat 内，把这 7 条：

```
D:\repository\org\apache\poi\poi\4.1.2\poi-4.1.2.jar;
D:\repository\com\zaxxer\SparseBitSet\1.2\SparseBitSet-1.2.jar;
D:\repository\org\apache\poi\poi-ooxml\4.1.2\poi-ooxml-4.1.2.jar;
D:\repository\org\apache\poi\poi-ooxml-schemas\4.1.2\poi-ooxml-schemas-4.1.2.jar;
D:\repository\org\apache\xmlbeans\xmlbeans\3.1.0\xmlbeans-3.1.0.jar;
D:\repository\org\apache\commons\commons-compress\1.19\commons-compress-1.19.jar;
D:\repository\com\github\virtuald\curvesapi\1.06\curvesapi-1.06.jar;
```

替换为这 7 条（注意 `poi-ooxml-schemas` 在 5.x 已改名 `poi-ooxml-lite`）：

```
D:\repository\org\apache\poi\poi\5.5.1\poi-5.5.1.jar;
D:\repository\com\zaxxer\SparseBitSet\1.3\SparseBitSet-1.3.jar;
D:\repository\org\apache\poi\poi-ooxml\5.5.1\poi-ooxml-5.5.1.jar;
D:\repository\org\apache\poi\poi-ooxml-lite\5.5.1\poi-ooxml-lite-5.5.1.jar;
D:\repository\org\apache\xmlbeans\xmlbeans\5.3.0\xmlbeans-5.3.0.jar;
D:\repository\org\apache\commons\commons-compress\1.28.0\commons-compress-1.28.0.jar;
D:\repository\com\github\virtuald\curvesapi\1.08\curvesapi-1.08.jar;
```

再替换三个 commons（POI 5.5.1 声明的新版本）：

```
D:\repository\commons-codec\commons-codec\1.15\commons-codec-1.15.jar
    → D:\repository\commons-codec\commons-codec\1.20.0\commons-codec-1.20.0.jar

D:\repository\commons-io\commons-io\2.4\commons-io-2.4.jar
    → D:\repository\commons-io\commons-io\2.21.0\commons-io-2.21.0.jar

D:\repository\org\apache\commons\commons-collections4\4.4\commons-collections4-4.4.jar
    → D:\repository\org\apache\commons\commons-collections4\4.5.0\commons-collections4-4.5.0.jar
```

- [ ] **Step 2: 确认 5 个文件都改到了、无残留**

Run (python 比 grep 可靠，反斜杠不会骗人)：

```bash
python -c "
for f in ['aistart.bat','aistart-web.bat','aistart_dq.bat','aistart_tl.bat','aistart_xm.bat']:
    s=open(f,encoding='utf-8',errors='replace').read()
    old=[x for x in ['poi-4.1.2.jar','SparseBitSet-1.2.jar','poi-ooxml-4.1.2.jar','poi-ooxml-schemas-4.1.2.jar','xmlbeans-3.1.0.jar','commons-compress-1.19.jar','curvesapi-1.06.jar','commons-io-2.4.jar','commons-collections4-4.4.jar','commons-codec-1.15.jar'] if x in s]
    new=[x for x in ['poi-5.5.1','xmlbeans-5.3.0','poi-ooxml-lite-5.5.1','SparseBitSet-1.3','curvesapi-1.08','commons-compress-1.28.0','commons-io-2.21.0','commons-collections4-4.5.0','commons-codec-1.20.0'] if x in s]
    print(f, '残留=', old or 'none', '| 新=', len(new), '/9')
"
```

Expected: 5 行，每行 `残留= none | 新= 9/9`。

⚠️ **残留项必须用带版本号的完整 jar 文件名，不能用裸版本号**。裸 `4.1.2` 会**假阳性**
——它是 `netty-all-4.1.25.Final.jar` 的子串。这个坑在 Task 4 首次执行时实测踩到过。
用文件名（而非完整路径）也顺带避开了反斜杠转义问题。

⚠️ 同理，编辑时**只替换完整路径子串**，绝不做全局版本号替换——classpath 里还有
jetty `12.0.16`、jna `5.14.0`、jline `3.26.3` 等大量其他 jar。

- [ ] **Step 3: Commit**

```bash
git add aistart.bat aistart-web.bat aistart_dq.bat aistart_tl.bat aistart_xm.bat
git commit -m "5 个启动脚本 POI classpath 升 5.5.1（schemas→lite，commons 三件同步）"
```

### Task 5: Excel 链路回归（确认升级没改坏）

**Files:**
- 无改动（验证任务）

- [ ] **Step 1: 找出现有 Excel 使用入口**

Run: `grep -rn "parseExeclFileToList\|listToExeclFile" --include=*.java . | grep -v target`
Expected: 列出调用点，记下其中一个可跑的 demo `main`。

- [ ] **Step 2: 跑一个真实 Excel**

用仓库根那个真实文件跑读写往返。

- [ ] **Step 2a: 建临时冒烟类**

写到 `/tmp/ExcelSmoke.java`（**不提交**）：

```java
import cn.tianlong.tlobject.execl.TLExeclFileUtils;
import java.util.*;

public class ExcelSmoke {
    public static void main(String[] args) throws Exception {
        String src = args[0], dst = args[1];
        List<HashMap<String, Object>> rows = TLExeclFileUtils.parseExeclFileToList(src, true, null);
        if (rows == null || rows.isEmpty()) { System.out.println("FAIL: 读出来是空的"); System.exit(1); }
        System.out.println("读出行数 = " + rows.size());
        System.out.println("首行列名 = " + rows.get(0).keySet());
        System.out.println("首行 = " + rows.get(0));
        LinkedHashMap<String, String> titles = new LinkedHashMap<>();
        for (String k : rows.get(0).keySet()) titles.put(k, k);
        TLExeclFileUtils.listToExeclFile(new ArrayList<Map<String, Object>>(rows), dst, titles);
        List<HashMap<String, Object>> back = TLExeclFileUtils.parseExeclFileToList(dst, true, null);
        if (back == null || back.size() != rows.size()) {
            System.out.println("FAIL: 回读行数 " + (back == null ? "null" : back.size())
                    + " != " + rows.size()); System.exit(1);
        }
        System.out.println("回读行数 = " + back.size() + " — PASS");
    }
}
```

- [ ] **Step 2b: 编译并运行**

```bash
/d/maven/bin/mvn -q -pl execl dependency:build-classpath -Dmdep.outputFile=/tmp/execl-cp.txt
javac -encoding UTF-8 -cp "execl/target/classes;$(cat /tmp/execl-cp.txt)" -d /tmp /tmp/ExcelSmoke.java
java -Dfile.encoding=UTF-8 -cp "/tmp;execl/target/classes;$(cat /tmp/execl-cp.txt)" ExcelSmoke "常用云桌面资费.xlsx" /tmp/roundtrip.xls
```

Expected: 打印 `读出行数 = N`、`首行列名 = [...]`、`回读行数 = N — PASS`。

**这一步是 POI 升级的真正风险闸门**（`commons-io` 2.4→2.21、`commons-compress` 1.19→1.28
都在 `XSSFWorkbook` 读 xlsx 的路径上）。

⚠️ **目标文件名必须是 `.xls`，不能是 `.xlsx`。** 实测发现：`listToExeclFile` 永远构造
`HSSFWorkbook`（写 OLE2/.xls 二进制），与目标文件名的扩展名无关；而 `parseSheet` 是**按扩展名**
分派的。所以写到 `.xlsx` 名字的文件回读时抛 `OLE2NotOfficeXmlFileException`（还是 RuntimeException，
`parseSheet` 的 `catch (IOException)` 兜不住）。

这是**既存缺陷，不是 POI 升级引入的**——已用未升级的 4.1.2 classpath 做 A/B 对照，行为逐字节一致。
**不在本次范围内修**，但如果将来要修，两个方向：让 `listToExclFile` 按目标扩展名选 XSSF，
或让 `parseSheet` 嗅探内容而非信扩展名。

⚠️ 若 `常用云桌面资费.xlsx` 不在仓库根或不是 `hasTitle` 结构（实测它不是——首行是合并标题行，
真表头在第 1 行），换任意一个真实 xlsx 即可，重点是**读写往返**而不是这个特定文件。

- [ ] **Step 2c: 必须覆盖"日期格式单元格"这一条路径**

Task 3 改的那一行（`DateUtil.isCellDateFormatted`）只在**数值型且被标记为日期格式**的
单元格上才会走到。上面那个文件如果全是文本列，这一行根本没被执行——**编译通过不能证明它没坏**。

所以额外造一个含日期列的文件来跑：

```bash
python -c "
import zipfile,shutil,os
# 最省事的做法：用 LibreOffice 或 Excel 手工存一个带日期列的 xlsx 太麻烦，
# 直接用 python openpyxl 若无则跳过并如实报告
try:
    import openpyxl
except ImportError:
    print('SKIP: openpyxl 不可用，需手工造一个含日期列的 xlsx'); raise SystemExit(0)
wb=openpyxl.Workbook(); ws=wb.active
ws.append(['名称','日期','金额'])
import datetime
ws.append(['甲', datetime.date(2026,1,15), 100.5])
ws.append(['乙', datetime.date(2026,2,20), 200.25])
for c in ws['B']: c.number_format='yyyy-mm-dd'
wb.save('/tmp/datecells.xlsx'); print('WROTE /tmp/datecells.xlsx')
"
```

然后用同一个 `ExcelSmoke` 跑它，**检查输出里日期列的值**。

Expected: 日期列读出形如 `2026-01-15`（`TLExeclFileUtils` 走
`TLDateUtils.dateToStr(date, null)`），而**不是** `45672.0` 这种裸序列号。

⚠️ 若读到的是裸数字而不是日期字符串，说明 `isCellDateFormatted` 这条路径**在 5.5.1 下行为变了**
——这是本次升级最需要盯的一处，必须停下来查清根因，**不要**改成"反正能跑就行"。
若 `openpyxl` 装不上、也不方便手工造文件，就**如实报告"日期路径未验证"**，
不要假装覆盖到了。

- [ ] **Step 3: 记录结果**

若通过，无需 commit（纯验证）。若失败，**不要**继续 Phase B——先修升级，
因为后面的 Word 功能建在同一个 POI 上。

---

## Phase B：word-java 引擎

**本阶段所有自测都不需要框架、不需要 LLM、不需要配置文件。** 这是刻意的设计：
引擎不 import `cn.tianlong.tlobject.*`，所以迭代一圈只要几秒。

### Task 6: 模块骨架

**Files:**
- Create: `aiagent/word-java/pom.xml`
- Create: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/JavaWordEngine.java`
- Create: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordEngineSelfTest.java`
- Modify: `aiagent/pom.xml:15-25`
- Modify: `tlobject-all/pom.xml`（在 `tlobject-aiagent-desktop-java` 那条之后，约 140 行）

- [ ] **Step 1: 写 pom**

`aiagent/word-java/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <parent>
        <artifactId>tlobject-aiagent</artifactId>
        <groupId>cn.tianlong.tlobject</groupId>
        <version>1.0</version>
    </parent>
    <modelVersion>4.0.0</modelVersion>

    <artifactId>tlobject-aiagent-word-java</artifactId>

    <dependencies>
        <dependency>
            <groupId>cn.tianlong.tlobject</groupId>
            <artifactId>tlobject-aiagent-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <!-- Word .docx 读写。版本由根 pom dependencyManagement 统一为 5.5.1
             （与 execl 共用同一版本，同 classpath 不能有两个 POI） -->
        <dependency>
            <groupId>org.apache.poi</groupId>
            <artifactId>poi-ooxml</artifactId>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <artifactId>maven-compiler-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 注册到聚合 pom**

`aiagent/pom.xml` 的 `<modules>` 里，`<module>desktop-java</module>` 之后加一行：

```xml
        <module>word-java</module>
```

`tlobject-all/pom.xml` 里，`tlobject-aiagent-desktop-java` 那个 dependency **之后**加：

```xml
        <dependency>
            <groupId>cn.tianlong.tlobject</groupId>
            <artifactId>tlobject-aiagent-word-java</artifactId>
            <version>1.0</version>
        </dependency>
```

- [ ] **Step 3: 写最小骨架（先只有 Result/Config 和空 execute）**

`JavaWordEngine.java`：

```java
package cn.tianlong.tlobject.aiagent.word;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Word .docx 引擎（纯 POI，不依赖框架）。
 * 技能壳 TLWordJavaSkill 负责把消息翻成 (action, input)，本类只做文档操作。
 *
 * 创建日期：2026/10/07 作者:tianlong
 */
public class JavaWordEngine {

    public static class Config {
        public Path allowedRoot;
        public Path workDir;
        public boolean backup;            // 改动前是否留 .bak.docx（默认关，原子写已够安全）
    }

    public static class Result {
        public boolean ok;
        public boolean textMode;          // true=读类，输出原始文本
        public String text;
        public Map<String, Object> json = new LinkedHashMap<>();

        public static Result text(String t) {
            Result r = new Result();
            r.ok = true; r.textMode = true; r.text = t;
            return r;
        }
        public static Result fail(String msg) {
            Result r = new Result();
            r.ok = false;
            r.json.put("ok", false);
            r.json.put("error", msg);
            return r;
        }
    }

    private final Config cfg;

    public JavaWordEngine(Config cfg) { this.cfg = cfg; }

    public Result execute(String action, Map<String, Object> input) {
        return Result.fail("not implemented: " + action);
    }
}
```

- [ ] **Step 4: 写自测骨架**

`WordEngineSelfTest.java`（**这就是本工程的自测惯例**，仿
`aiagent/provider-openai/.../PromotionGuardSelfTest.java`：src/main/java 下的 runnable main、
`check()` 计数、失败退出码 1）：

```java
package cn.tianlong.tlobject.aiagent.word;

/**
 * word 引擎自测（runnable main，无 JUnit——与仓库自测惯例一致）。
 *
 * 运行：
 *   /d/maven/bin/mvn -q -pl aiagent/word-java dependency:build-classpath -Dmdep.outputFile=aiagent/word-java/cp.txt
 *   java -cp "aiagent/word-java/target/classes;$(cat aiagent/word-java/cp.txt)" \
 *        cn.tianlong.tlobject.aiagent.word.WordEngineSelfTest
 * 失败以退出码 1 结束。
 */
public class WordEngineSelfTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        check("骨架可实例化", true);

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void check(String desc, boolean ok) {
        if (ok) { passed++; System.out.println("  [PASS] " + desc); }
        else    { failed++; System.out.println("  [FAIL] " + desc); }
    }
}
```

- [ ] **Step 5: 编译并跑通自测**

```bash
/d/maven/bin/mvn -q -pl aiagent/word-java -am install -DskipTests
/d/maven/bin/mvn -q -pl aiagent/word-java dependency:build-classpath -Dmdep.outputFile=aiagent/word-java/cp.txt
java -cp "aiagent/word-java/target/classes;$(cat aiagent/word-java/cp.txt)" cn.tianlong.tlobject.aiagent.word.WordEngineSelfTest
```

Expected: `1 passed, 0 failed`。

⚠️ `cp.txt` 加进 `.gitignore` 或提交前删掉——它是本机路径，不该进库。
先确认：`grep -n "cp.txt" .gitignore`，没有就加一行。

- [ ] **Step 6: Commit**

```bash
git add aiagent/pom.xml tlobject-all/pom.xml aiagent/word-java/pom.xml aiagent/word-java/src .gitignore
git commit -m "word-java 模块骨架：引擎/自测空壳 + 聚合 pom 注册 + POI 5.5.1 依赖"
```

### Task 7: WordMarkdown（markdown → Block，纯字符串，无 POI）

**Files:**
- Create: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordMarkdown.java`
- Modify: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordEngineSelfTest.java`

- [ ] **Step 1: 先写失败的自测**

在 `WordEngineSelfTest.main` 的 `check("骨架可实例化", true);` **之后**插入：

```java
        // ================= WordMarkdown 解析 =================
        java.util.List<WordMarkdown.Block> bl =
                WordMarkdown.parse("# 标题\n\n正文一段\n\n- 甲\n- 乙\n\n| 月 | 值 |\n| 1 | 100 |\n\n---\n\n**粗体**开头的段落");
        // 7 块：# 标题 / 正文一段 / -甲 / -乙 / 表格 / --- / **粗体**开头
        check("markdown: 块数量 = 7", bl.size() == 7);
        check("markdown: # → HEADING level1",
                bl.get(0).type == WordMarkdown.Block.Type.HEADING && bl.get(0).level == 1);
        check("markdown: 标题文本", "标题".equals(bl.get(0).spans.get(0).text));
        check("markdown: 普通段落",
                bl.get(1).type == WordMarkdown.Block.Type.PARAGRAPH
                        && "正文一段".equals(bl.get(1).spans.get(0).text));
        check("markdown: - → BULLET 两条", bl.get(2).type == WordMarkdown.Block.Type.BULLET
                && "甲".equals(bl.get(2).spans.get(0).text));
        check("markdown: - 的第二条", "乙".equals(bl.get(3).spans.get(0).text));
        check("markdown: 表格 2行2列",
                bl.get(4).type == WordMarkdown.Block.Type.TABLE
                        && bl.get(4).rows.size() == 2 && bl.get(4).rows.get(0).size() == 2);
        check("markdown: 表格内容", "月".equals(bl.get(4).rows.get(0).get(0))
                && "100".equals(bl.get(4).rows.get(1).get(1)));
        check("markdown: --- → PAGEBREAK", bl.get(5).type == WordMarkdown.Block.Type.PAGEBREAK);
        check("markdown: 第 7 块是粗体段落，首 span 为粗",
                bl.get(6).type == WordMarkdown.Block.Type.PARAGRAPH
                        && bl.get(6).spans.get(0).bold
                        && "粗体".equals(bl.get(6).spans.get(0).text));
        check("markdown: 粗体后的普通文字不粗",
                !WordMarkdown.parse("**粗体**普通").get(0).spans.get(1).bold);
        check("markdown: 多级标题 ###### → level 6",
                WordMarkdown.parse("###### 深").get(0).level == 6);
        check("markdown: 有序列表 1. → NUMBERED",
                WordMarkdown.parse("1. 首项").get(0).type == WordMarkdown.Block.Type.NUMBERED);
```

Run: 见 Task 6 Step 5 的三条命令
Expected: 编译失败（`WordMarkdown` 不存在）——**这一步就是要它失败**。

- [ ] **Step 2: 实现 WordMarkdown**

```java
package cn.tianlong.tlobject.aiagent.word;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 子集 → 块模型。纯字符串处理，不依赖 POI，可独立单测。
 *
 * 支持：
 *   标题   # ~ ######
 *   列表   - / * 开头（无序）、1. 开头（有序）
 *   表格   | a | b | 连续行
 *   分页   --- （独占一行）
 *   段落   其余非空行，空行分段
 *   行内   **粗体**
 *
 * 创建日期：2026/10/07 作者:tianlong
 */
public final class WordMarkdown {

    public static class Span {
        public String text;
        public boolean bold;
        public Span(String t, boolean b) { text = t; bold = b; }
    }

    public static class Block {
        public enum Type { HEADING, PARAGRAPH, BULLET, NUMBERED, TABLE, PAGEBREAK }
        public Type type;
        public int level;                    // HEADING 的级别 1-6
        public List<Span> spans = new ArrayList<>();
        public List<List<String>> rows = new ArrayList<>();
    }

    private WordMarkdown() {}

    public static List<Block> parse(String md) {
        List<Block> out = new ArrayList<>();
        if (md == null) return out;
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) { i++; continue; }

            // 分页
            if (trimmed.equals("---")) {
                out.add(newBlock(Block.Type.PAGEBREAK));
                i++; continue;
            }
            // 表格：连续以 | 开头的行
            if (trimmed.startsWith("|")) {
                List<List<String>> rows = new ArrayList<>();
                while (i < lines.length && lines[i].trim().startsWith("|")) {
                    List<String> cells = splitRow(lines[i].trim());
                    if (!isSeparatorRow(cells)) rows.add(cells);
                    i++;
                }
                Block b = newBlock(Block.Type.TABLE);
                b.rows = rows;
                out.add(b);
                continue;
            }
            // 标题
            if (trimmed.startsWith("#")) {
                int lv = 0;
                while (lv < trimmed.length() && trimmed.charAt(lv) == '#') lv++;
                if (lv <= 6 && lv < trimmed.length() && trimmed.charAt(lv) == ' ') {
                    Block b = newBlock(Block.Type.HEADING);
                    b.level = lv;
                    b.spans = inline(trimmed.substring(lv + 1).trim());
                    out.add(b);
                    i++; continue;
                }
            }
            // 无序列表
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                Block b = newBlock(Block.Type.BULLET);
                b.spans = inline(trimmed.substring(2).trim());
                out.add(b);
                i++; continue;
            }
            // 有序列表
            if (isOrdered(trimmed)) {
                int dot = trimmed.indexOf('.');
                Block b = newBlock(Block.Type.NUMBERED);
                b.spans = inline(trimmed.substring(dot + 1).trim());
                out.add(b);
                i++; continue;
            }
            // 段落：吃到空行为止（同段多行用空格接）
            StringBuilder sb = new StringBuilder(trimmed);
            i++;
            while (i < lines.length) {
                String nxt = lines[i].trim();
                if (nxt.isEmpty() || nxt.startsWith("|") || nxt.startsWith("#")
                        || nxt.startsWith("- ") || nxt.startsWith("* ")
                        || nxt.equals("---") || isOrdered(nxt)) break;
                sb.append(' ').append(nxt);
                i++;
            }
            Block b = newBlock(Block.Type.PARAGRAPH);
            b.spans = inline(sb.toString());
            out.add(b);
        }
        return out;
    }

    private static Block newBlock(Block.Type t) {
        Block b = new Block();
        b.type = t;
        return b;
    }

    private static boolean isOrdered(String s) {
        int dot = s.indexOf('.');
        if (dot <= 0 || dot + 1 >= s.length() || s.charAt(dot + 1) != ' ') return false;
        for (int k = 0; k < dot; k++) if (!Character.isDigit(s.charAt(k))) return false;
        return true;
    }

    /** | a | b | → ["a","b"]；首尾空段因起止竖线被丢弃 */
    private static List<String> splitRow(String s) {
        List<String> cells = new ArrayList<>();
        for (String c : s.split("\\|", -1)) cells.add(c.trim());
        if (!cells.isEmpty() && cells.get(0).isEmpty()) cells.remove(0);
        if (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) cells.remove(cells.size() - 1);
        return cells;
    }

    /** 表格分隔行 |---|---| 要丢弃 */
    private static boolean isSeparatorRow(List<String> cells) {
        if (cells.isEmpty()) return false;
        for (String c : cells) {
            String t = c.replace("-", "").replace(":", "").trim();
            if (!t.isEmpty()) return false;
        }
        return true;
    }

    /** 行内 **粗体** → spans */
    private static List<Span> inline(String s) {
        List<Span> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            int open = s.indexOf("**", i);
            if (open < 0) { out.add(new Span(s.substring(i), false)); break; }
            int close = s.indexOf("**", open + 2);
            if (close < 0) { out.add(new Span(s.substring(i), false)); break; }
            if (open > i) out.add(new Span(s.substring(i, open), false));
            out.add(new Span(s.substring(open + 2, close), true));
            i = close + 2;
        }
        if (out.isEmpty()) out.add(new Span("", false));
        return out;
    }
}
```

- [ ] **Step 3: 跑自测**

Run: Task 6 Step 5 的三条命令
Expected: 全部 PASS。

- [ ] **Step 4: Commit**

```bash
git add aiagent/word-java/src
git commit -m "WordMarkdown：markdown 子集解析（标题/列表/表格/分页/行内粗体）"
```

### Task 8: WordTextExtractor（读：read / outline / tables / info）

**Files:**
- Create: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordTextExtractor.java`
- Modify: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordEngineSelfTest.java`

- [ ] **Step 1: 先写失败的自测**

在自测类里加一个造文档的辅助方法（后续 Task 反复用），以及读取断言：

```java
    // ================= 共用的造文档工具 =================

    /** 用一个全新文档跑 body，返回它 */
    static org.apache.poi.xwpf.usermodel.XWPFDocument newDoc() {
        return new org.apache.poi.xwpf.usermodel.XWPFDocument();
    }

    static org.apache.poi.xwpf.usermodel.XWPFParagraph addPara(
            org.apache.poi.xwpf.usermodel.XWPFDocument d, String text, String style) {
        org.apache.poi.xwpf.usermodel.XWPFParagraph p = d.createParagraph();
        if (style != null) p.setStyle(style);
        org.apache.poi.xwpf.usermodel.XWPFRun r = p.createRun();
        r.setText(text);
        return p;
    }

    static java.nio.file.Path saveTmp(org.apache.poi.xwpf.usermodel.XWPFDocument d, String name)
            throws Exception {
        java.nio.file.Path p = java.nio.file.Files.createTempDirectory("wordsf")
                .resolve(name);
        try (java.io.OutputStream o = java.nio.file.Files.newOutputStream(p)) { d.write(o); }
        return p;
    }
```

然后在 `main` 里加：

```java
        // ================= WordTextExtractor =================
        org.apache.poi.xwpf.usermodel.XWPFDocument d1 = newDoc();
        addPara(d1, "项目报告", "Heading1");
        addPara(d1, "本报告统计了收入。", null);
        org.apache.poi.xwpf.usermodel.XWPFTable t1 = d1.createTable(2, 2);
        t1.getRow(0).getCell(0).setText("月份");
        t1.getRow(0).getCell(1).setText("收入");
        t1.getRow(1).getCell(0).setText("1月");
        t1.getRow(1).getCell(1).setText("100");

        String txt = WordTextExtractor.read(d1, 0, -1);
        check("read: 含段落号 [0] 与样式 Heading1", txt.contains("[0] [Heading1] 项目报告"));
        check("read: 普通段落无样式标注为空", txt.contains("[1] "));
        check("read: 内嵌表格标记", txt.contains("[表格 0]"));
        check("read: 表格单元格内容入文本", txt.contains("月份") && txt.contains("100"));

        check("read: from/to 切片只取第二段",
                WordTextExtractor.read(d1, 1, 1).contains("[1] ")
                        && !WordTextExtractor.read(d1, 1, 1).contains("项目报告"));

        String ol = WordTextExtractor.outline(d1);
        check("outline: 只出标题", ol.contains("[0] 项目报告") && !ol.contains("本报告统计"));

        String tb = WordTextExtractor.tables(d1, -1);
        check("tables: 二维内容", tb.contains("月份") && tb.contains("1月"));

        java.util.Map<String, Object> inf = WordTextExtractor.info(d1);
        check("info: 段落数 = 2", Integer.valueOf(2).equals(inf.get("paragraphs")));
        check("info: 表格数 = 1", Integer.valueOf(1).equals(inf.get("tables")));
        check("info: 标题数 = 1", Integer.valueOf(1).equals(inf.get("headings")));
```

Run: Task 6 Step 5 命令
Expected: 编译失败（`WordTextExtractor` 不存在）。

- [ ] **Step 2: 实现 WordTextExtractor**

```java
package cn.tianlong.tlobject.aiagent.word;

import org.apache.poi.xwpf.usermodel.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读：结构化文本 / 大纲 / 表格 / 元数据。
 *
 * 输出里的 [N] 是段落号 = doc.getParagraphs() 下标（只数段落）；
 * [表格 N] 的 N 是 doc.getTables() 下标。两套编号独立，供后续修改动作定位。
 *
 * 创建日期：2026/10/07 作者:tianlong
 */
public final class WordTextExtractor {

    private WordTextExtractor() {}

    public static String read(XWPFDocument doc, int from, int to) {
        StringBuilder sb = new StringBuilder();
        int pIdx = 0, tIdx = 0;
        for (IBodyElement el : doc.getBodyElements()) {
            if (el instanceof XWPFParagraph) {
                XWPFParagraph p = (XWPFParagraph) el;
                int cur = pIdx++;
                if (cur < from) continue;
                if (to >= 0 && cur > to) break;
                sb.append('[').append(cur).append("] [")
                  .append(styleOf(p)).append("] ").append(p.getText()).append('\n');
            } else if (el instanceof XWPFTable) {
                XWPFTable t = (XWPFTable) el;
                int cur = tIdx++;
                // 表格不受 from/to 段落区间约束：区间只切段落，表格整块带上
                sb.append("[表格 ").append(cur).append("] ")
                  .append(t.getRows().size()).append("行×")
                  .append(t.getRow(0) == null ? 0 : t.getRow(0).getTableCells().size())
                  .append("列\n");
                for (XWPFTableRow r : t.getRows()) {
                    sb.append("  |");
                    for (XWPFTableCell c : r.getTableCells())
                        sb.append(' ').append(c.getText().replace("\n", " ")).append(" |");
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    public static String outline(XWPFDocument doc) {
        StringBuilder sb = new StringBuilder();
        List<XWPFParagraph> ps = doc.getParagraphs();
        boolean any = false;
        for (int i = 0; i < ps.size(); i++) {
            XWPFParagraph p = ps.get(i);
            if (!isHeading(p)) continue;
            any = true;
            sb.append('[').append(i).append("] ").append(p.getText()).append('\n');
        }
        if (!any) return "(no headings)";
        return sb.toString();
    }

    public static String tables(XWPFDocument doc, int index) {
        List<XWPFTable> ts = doc.getTables();
        if (ts.isEmpty()) return "(no tables)";
        StringBuilder sb = new StringBuilder();
        int from = index < 0 ? 0 : index;
        int to = index < 0 ? ts.size() - 1 : index;
        if (from >= ts.size())
            return "(no table " + index + "; document has " + ts.size() + ")";
        for (int k = from; k <= to; k++) {
            XWPFTable t = ts.get(k);
            sb.append("[表格 ").append(k).append("]\n");
            for (XWPFTableRow r : t.getRows()) {
                sb.append("  |");
                for (XWPFTableCell c : r.getTableCells())
                    sb.append(' ').append(c.getText().replace("\n", " ")).append(" |");
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public static Map<String, Object> info(XWPFDocument doc) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<XWPFParagraph> ps = doc.getParagraphs();
        int headings = 0, chars = 0;
        for (XWPFParagraph p : ps) {
            if (isHeading(p)) headings++;
            chars += p.getText() == null ? 0 : p.getText().length();
        }
        m.put("paragraphs", ps.size());
        m.put("tables", doc.getTables().size());
        m.put("headings", headings);
        m.put("pictures", doc.getAllPictures().size());
        m.put("chars", chars);
        return m;
    }

    static boolean isHeading(XWPFParagraph p) {
        String s = p.getStyle();
        return s != null && s.toLowerCase().startsWith("heading");
    }

    static String styleOf(XWPFParagraph p) {
        String s = p.getStyle();
        if (s == null || s.isEmpty()) {
            // 部分文档不写 pStyle，靠 outlineLvl 表示标题
            try {
                if (p.getCTP().getPPr() != null && p.getCTP().getPPr().getOutlineLvl() != null)
                    return "Heading" + (p.getCTP().getPPr().getOutlineLvl().getVal().intValue() + 1);
            } catch (Exception ignored) {}
            return "Normal";
        }
        return s;
    }
}
```

- [ ] **Step 3: 跑自测**

Run: Task 6 Step 5 命令
Expected: 全 PASS。

⚠️ 若 `info` 的 `headings` 断言失败：新建文档调 `p.setStyle("Heading1")` 在部分 POI 版本下
会因为 `styles.xml` 里没有该 style 定义而不生效。**先打印 `p.getStyle()` 看实际值**再改断言，
不要直接改断言迁就。

- [ ] **Step 4: Commit**

```bash
git add aiagent/word-java/src
git commit -m "WordTextExtractor：结构化读/大纲/表格/元数据"
```

### Task 9: 跨 run 匹配核心（本技能的技术心脏）

**Files:**
- Create: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordTextEditor.java`
- Modify: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordEngineSelfTest.java`

**这是整个技能唯一真正难的地方。** 慢一点，把自测跑透。

- [ ] **Step 1: 先写失败的自测（含构造跨 run 文档的工具）**

先在自测类加造"跨 run"文档的工具：

```java
    /** 造一个把 ${name} 拆成 4 个 run 的段落（模拟真实 Word 的 run 碎片化） */
    static org.apache.poi.xwpf.usermodel.XWPFParagraph addSplitPara(
            org.apache.poi.xwpf.usermodel.XWPFDocument d,
            String[] pieces, boolean boldFirst) {
        org.apache.poi.xwpf.usermodel.XWPFParagraph p = d.createParagraph();
        for (int i = 0; i < pieces.length; i++) {
            org.apache.poi.xwpf.usermodel.XWPFRun r = p.createRun();
            if (i == 0 && boldFirst) r.setBold(true);
            r.setFontSize(14);
            r.setText(pieces[i]);
        }
        return p;
    }
```

再加断言：

```java
        // ================= 跨 run 匹配核心 =================
        // 段落 = "订单号：${orderNo}"，切成 ["订单号：","${","orderNo","}"]
        org.apache.poi.xwpf.usermodel.XWPFDocument d2 = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFParagraph p2 = addSplitPara(d2,
                new String[]{"订单号：", "${", "orderNo", "}"}, true);

        int n2 = WordTextEditor.replaceInParagraph(p2, "${orderNo}", "A123");
        check("跨run: 替换计数 = 1", n2 == 1);
        check("跨run: 段落文本已是替换后", "订单号：A123".equals(p2.getText()));
        check("跨run: 首 run 的 bold 保住了", p2.getRuns().get(0).isBold());
        check("跨run: 字号保住 = 14", p2.getRuns().get(0).getFontSize() == 14);
        check("跨run: run 数量未增加（最小重建，不新建 run）", p2.getRuns().size() == 4);

        // 替换后再次替换，验证可重复
        check("跨run: 可再次替换", WordTextEditor.replaceInParagraph(p2, "A123", "B456") == 1
                && "订单号：B456".equals(p2.getText()));

        // 单个 run 内替换
        org.apache.poi.xwpf.usermodel.XWPFDocument d3 = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFParagraph p3 = addPara(d3, "金额：100 元", null);
        check("同run: 替换成功", WordTextEditor.replaceInParagraph(p3, "100", "200") == 1
                && "金额：200 元".equals(p3.getText()));

        // 不安全 run 要被跳过而不是改坏
        org.apache.poi.xwpf.usermodel.XWPFDocument d4 = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFParagraph p4 = d4.createParagraph();
        org.apache.poi.xwpf.usermodel.XWPFRun r4 = p4.createRun();
        r4.setText("第一行");
        r4.addBreak();                      // 段落内换行 → 不安全
        org.apache.poi.xwpf.usermodel.XWPFRun r4b = p4.createRun();
        r4b.setText("含 ${x} 的模板块");
        check("不安全run: 命中不安全 run 时返回 -2（跳过）",
                WordTextEditor.replaceInParagraph(p4, "${x}", "OK") == -2);
        check("不安全run: 文本未被改动", p4.getText().contains("${x}"));

        // 匹配不到
        check("无匹配: 返回 0", WordTextEditor.replaceInParagraph(p3, "不存在的词", "x") == 0);
```

Run: Task 6 Step 5 命令
Expected: 编译失败（`WordTextEditor` 不存在）。

- [ ] **Step 2: 实现 WordTextEditor 的匹配核心**

```java
package cn.tianlong.tlobject.aiagent.word;

import org.apache.poi.xwpf.usermodel.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 改：跨 run 精确替换 + 段落/表格操作。
 *
 * 核心难点：Word 会把一段文字任意切碎成多个 run（编辑历史/拼写检查/输入法都会造成），
 * "订单号：${orderNo}" 在 XML 里可能是 4 个 run。直接对单个 run 做字符串查找必然搜不到。
 *
 * 解法（最小重建）：把段落所有 run 文本拼成 full 做匹配，定位覆盖区间后只改这几个 run
 * 的**文本**，不新建不删除 run 对象 —— 字体/加粗/颜色/字号挂在 run 上原地不动，格式必然保留。
 *
 * 安全策略：命中含超链接/域/br/tab/drawing 的 run 时**跳过并上报**，绝不静默改坏。
 *
 * 创建日期：2026/10/07 作者:tianlong
 */
public final class WordTextEditor {

    /** 替换计数返回值：>=0 为成功替换处数；-2 表示命中不安全 run 被跳过 */
    public static final int SKIPPED_UNSAFE = -2;

    private WordTextEditor() {}

    private static class Span {
        int start, end;
        XWPFRun run;
        Span(int s, int e, XWPFRun r) { start = s; end = e; run = r; }
    }

    /** 段落里是否含"重写会丢东西"的 run */
    static boolean isUnsafeRun(XWPFRun r) {
        if (r instanceof XWPFHyperlinkRun) return true;   // 文本由 relationship 管理
        if (r instanceof XWPFFieldRun) return true;       // PAGE/NUMPAGES 等域
        try {
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR ctr = r.getCTR();
            if (ctr.sizeOfBrArray() > 0) return true;         // 段内换行，run.text() 不含它
            if (ctr.sizeOfTabArray() > 0) return true;        // 制表符同理
            if (ctr.sizeOfDrawingArray() > 0) return true;    // 内嵌图片
        } catch (Throwable ignored) {
            return true;                                      // 探测不了就当不安全，保守
        }
        return false;
    }

    /**
     * 段落内替换。find 一律按**字面量**处理——占位符里带 ${} 会干扰正则，本技能不需要正则替换。
     *
     * @return >=0 替换处数；-2 命中不安全 run
     */
    public static int replaceInParagraph(XWPFParagraph p, String find, String replace) {
        if (find == null || find.isEmpty()) return 0;
        List<XWPFRun> runs = p.getRuns();
        if (runs.isEmpty()) return 0;

        List<Span> spans = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        for (XWPFRun r : runs) {
            String t = r.text();
            if (t == null) t = "";
            spans.add(new Span(full.length(), full.length() + t.length(), r));
            full.append(t);
        }
        String hay = full.toString();

        int count = 0;
        int searchFrom = 0;
        while (true) {
            int ms = hay.indexOf(find, searchFrom);
            if (ms < 0) break;
            int me = ms + find.length();

            int ri = -1, rj = -1;
            for (int k = 0; k < spans.size(); k++) {
                Span s = spans.get(k);
                if (ri < 0 && s.start <= ms && ms < s.end) ri = k;
                if (s.start < me && me <= s.end) { rj = k; break; }
            }
            // 匹配落在零长度 run 边界等异常情形：放弃该处，往后挪一格避免死循环
            if (ri < 0 || rj < 0 || ri > rj) { searchFrom = ms + 1; continue; }

            for (int k = ri; k <= rj; k++) {
                if (isUnsafeRun(spans.get(k).run)) return SKIPPED_UNSAFE;
            }

            String prefix = hay.substring(spans.get(ri).start, ms);
            String suffix = hay.substring(me, spans.get(rj).end);
            if (ri == rj) {
                spans.get(ri).run.setText(prefix + replace + suffix, 0);
            } else {
                spans.get(ri).run.setText(prefix + replace, 0);
                for (int k = ri + 1; k < rj; k++) spans.get(k).run.setText("", 0);
                spans.get(rj).run.setText(suffix, 0);
            }
            count++;

            // 就地把 hay / spans 的区间更新，支持同段多处替换
            int delta = replace.length() - find.length();
            int newEnd = spans.get(ri).start + prefix.length() + replace.length();
            for (int k = ri; k <= rj; k++) {
                Span s = spans.get(k);
                if (k == ri) { s.end = newEnd; }
                else if (k == rj) {
                    s.start = newEnd;
                    s.end = s.end + delta;
                } else {
                    s.start = newEnd; s.end = newEnd;
                }
            }
            hay = hay.substring(0, ms) + replace + hay.substring(me);
            searchFrom = ms + replace.length();
        }
        return count;
    }
}
```

- [ ] **Step 3: 跑自测**

Run: Task 6 Step 5 命令
Expected: 全 PASS。

特别盯这三条——它们证明"保格式"这个核心承诺成立：
- `跨run: 首 run 的 bold 保住了`
- `跨run: 字号保住 = 14`
- `跨run: run 数量未增加（最小重建，不新建 run）`

- [ ] **Step 4: 补一个"不安全 run 的探测真的有效"的证伪用例**

上面 `p4` 的用例其实测的是"检测到了 br"。再补一条验证**普通 run 不会被误判**，
否则会过度跳过导致功能形同虚设：

```java
        org.apache.poi.xwpf.usermodel.XWPFParagraph p5 = newDoc().createParagraph();
        org.apache.poi.xwpf.usermodel.XWPFRun r5 = p5.createRun();
        r5.setBold(true); r5.setText("普通的 ${y} 文字");
        check("误判检查: 普通 run 不被当作不安全",
                WordTextEditor.replaceInParagraph(p5, "${y}", "Z") == 1);
```

Run: Task 6 Step 5 命令
Expected: PASS。**若这条 FAIL，说明 `isUnsafeRun` 过宽**，必须收窄——
一个把所有 run 都当不安全的实现"很安全"但毫无用处。

- [ ] **Step 5: Commit**

```bash
git add aiagent/word-java/src
git commit -m "WordTextEditor：跨 run 匹配 + 最小重建保格式替换核心（含不安全 run 跳过）"
```

### Task 10: 文档级 replace + fill_template

**Files:**
- Modify: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordTextEditor.java`
- Modify: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordEngineSelfTest.java`

- [ ] **Step 1: 先写失败的自测**

```java
        // ================= 文档级 replace / fill_template =================
        org.apache.poi.xwpf.usermodel.XWPFDocument d6 = newDoc();
        addSplitPara(d6, new String[]{"客户：", "${", "cust", "}"}, false);
        addPara(d6, "金额：${amount} 元", null);
        org.apache.poi.xwpf.usermodel.XWPFTable t6 = d6.createTable(1, 2);
        t6.getRow(0).getCell(0).setText("${amount}");
        t6.getRow(0).getCell(1).setText("备注");

        WordTextEditor.ReplaceReport rep = WordTextEditor.fillTemplate(d6,
                new java.util.LinkedHashMap<String, String>() {{
                    put("cust", "张三"); put("amount", "100");
                }});
        check("fill: 命中 3 处（正文2 + 表格1）", rep.replaced == 3);
        check("fill: 无跳过", rep.skipped == 0);
        check("fill: 跨run 占位符被替换", d6.getParagraphs().get(0).getText().equals("客户：张三"));
        check("fill: 同run 占位符被替换", d6.getParagraphs().get(1).getText().equals("金额：100 元"));
        check("fill: 表格单元格被替换",
                d6.getTables().get(0).getRow(0).getCell(0).getText().equals("100"));

        // 数据里没有的键 → 保持原样（不静默清空）
        org.apache.poi.xwpf.usermodel.XWPFDocument d7 = newDoc();
        addPara(d7, "保留 ${unknown} 原样", null);
        WordTextEditor.ReplaceReport rep2 = WordTextEditor.fillTemplate(d7,
                new java.util.LinkedHashMap<String, String>());
        check("fill: 缺失键不替换、不报错", rep2.replaced == 0 && d7.getParagraphs().get(0).getText().contains("${unknown}"));

        // 段落限定替换
        org.apache.poi.xwpf.usermodel.XWPFDocument d8 = newDoc();
        addPara(d8, "目标 目标", null);
        addPara(d8, "目标", null);
        check("replace: index 限定只改指定段落",
                WordTextEditor.replaceInDocument(d8, "目标", "改后", 1, "preserve").replaced == 1
                        && d8.getParagraphs().get(0).getText().equals("目标 目标")
                        && d8.getParagraphs().get(1).getText().equals("改后"));
```

Run: Task 6 Step 5 命令
Expected: 编译失败（`ReplaceReport` / `fillTemplate` / `replaceInDocument` 不存在）。

- [ ] **Step 2: 实现**

在 `WordTextEditor` 里加：

```java
    /** 替换回执 */
    public static class ReplaceReport {
        public int replaced;
        public int skipped;
        public List<String> skippedReasons = new ArrayList<>();
    }

    /**
     * 全文替换（正文段落 + 表格单元格）。
     *
     * @param pIndex   >=0 时只处理该段落号；<0 处理全文
     * @param mode     "preserve"（默认，保格式）/ "rewrite"（整段重写，丢段内格式但必成）
     */
    public static ReplaceReport replaceInDocument(XWPFDocument doc, String find, String replace,
                                                  int pIndex, String mode) {
        ReplaceReport rep = new ReplaceReport();
        boolean rewrite = "rewrite".equalsIgnoreCase(mode);
        List<XWPFParagraph> ps = doc.getParagraphs();
        for (int i = 0; i < ps.size(); i++) {
            if (pIndex >= 0 && i != pIndex) continue;
            XWPFParagraph p = ps.get(i);
            if (rewrite) {
                String t = p.getText();
                if (t != null && t.contains(find)) {
                    int before = countOccurrences(t, find);
                    rewriteParagraph(p, t.replace(find, replace));
                    rep.replaced += before;
                }
            } else {
                int n = replaceInParagraph(p, find, replace);
                if (n == SKIPPED_UNSAFE) {
                    rep.skipped++;
                    rep.skippedReasons.add("paragraph " + i + " contains hyperlink/field/break run, skipped");
                } else if (n > 0) {
                    rep.replaced += n;
                }
            }
        }
        // 表格：单元格内也有段落，按表格定位，不做 skip 计数（单元格里少见超链接/域）
        for (XWPFTable t : doc.getTables()) {
            for (XWPFTableRow r : t.getRows()) {
                for (XWPFTableCell c : r.getTableCells()) {
                    for (XWPFParagraph p : c.getParagraphs()) {
                        int n = replaceInParagraph(p, find, replace);
                        if (n > 0) rep.replaced += n;
                        else if (n == SKIPPED_UNSAFE) rep.skipped++;
                    }
                }
            }
        }
        return rep;
    }

    /** 整段重写：清空所有 run，用第一个 run 的格式写回（丢段内混合格式） */
    static void rewriteParagraph(XWPFParagraph p, String newText) {
        List<XWPFRun> runs = p.getRuns();
        if (runs.isEmpty()) {
            p.createRun().setText(newText);
            return;
        }
        runs.get(0).setText(newText, 0);
        for (int k = 1; k < runs.size(); k++) runs.get(k).setText("", 0);
    }

    static int countOccurrences(String hay, String needle) {
        if (needle.isEmpty()) return 0;
        int c = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }

    /**
     * 模板填充：对每个键做一次全文替换（正文 + 表格），占位符 ${key} 与 {{key}} 都认。
     * 数据里没有的键保持原样——不静默清空。
     */
    public static ReplaceReport fillTemplate(XWPFDocument doc, Map<String, String> data) {
        ReplaceReport total = new ReplaceReport();
        if (data == null || data.isEmpty()) return total;
        for (Map.Entry<String, String> e : data.entrySet()) {
            if (e.getKey() == null || e.getKey().isEmpty()) continue;
            String v = e.getValue() == null ? "" : e.getValue();
            for (String ph : new String[]{"${" + e.getKey() + "}", "{{" + e.getKey() + "}}"}) {
                ReplaceReport r = replaceInDocument(doc, ph, v, -1, "preserve");
                total.replaced += r.replaced;
                total.skipped += r.skipped;
                total.skippedReasons.addAll(r.skippedReasons);
            }
        }
        return total;
    }
```

顶部补 import：`import java.util.Map;`（`List` / `ArrayList` 已有）。

- [ ] **Step 3: 跑自测**

Run: Task 6 Step 5 命令
Expected: 全 PASS。

⚠️ `fill: 命中 3 处` 若实际是 6：说明 `${cust}` 和 `{{cust}}` 两种写法都被试了一遍，
但同一个 `amount` 在正文出现 1 次 + 表格 1 次 = 2，`cust` 1 次，共 3。若你看到 6，
检查是不是把两种占位符语法的计数重复累加了。**按实际算清楚再改断言。**

- [ ] **Step 4: Commit**

```bash
git add aiagent/word-java/src
git commit -m "WordTextEditor：文档级 replace（含段落限定/rewrite 逃生舱）+ fill_template"
```

### Task 11: 段落增删改 + 表格增删改

**Files:**
- Modify: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordTextEditor.java`
- Modify: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordEngineSelfTest.java`

- [ ] **Step 1: 先写失败的自测**

```java
        // ================= 段落增删改 =================
        org.apache.poi.xwpf.usermodel.XWPFDocument d9 = newDoc();
        addPara(d9, "甲", null);
        addPara(d9, "乙", null);
        addPara(d9, "丙", null);

        WordTextEditor.setParagraphText(d9, 1, "乙改");
        check("set_paragraph: 内容被改", d9.getParagraphs().get(1).getText().equals("乙改"));
        check("set_paragraph: 段落总数不变", d9.getParagraphs().size() == 3);
        check("set_paragraph: 相邻段落未受影响",
                d9.getParagraphs().get(0).getText().equals("甲")
                        && d9.getParagraphs().get(2).getText().equals("丙"));

        WordTextEditor.insertParagraph(d9, 1, "插入", null, true);
        check("insert_paragraph: 前插后位置正确",
                d9.getParagraphs().get(1).getText().equals("插入")
                        && d9.getParagraphs().get(2).getText().equals("乙改"));
        check("insert_paragraph: 总数 +1", d9.getParagraphs().size() == 4);

        WordTextEditor.deleteParagraph(d9, 0);
        check("delete_paragraph: 删除后首段是插入的那条",
                d9.getParagraphs().get(0).getText().equals("插入"));
        check("delete_paragraph: 总数 -1", d9.getParagraphs().size() == 3);

        // 越界必须报错而不是静默乱改
        boolean threw = false;
        try { WordTextEditor.setParagraphText(d9, 99, "x"); }
        catch (IndexOutOfBoundsException e) { threw = true; }
        check("set_paragraph: 越界抛 IndexOutOfBounds（不静默）", threw);

        // ================= 表格增删改 =================
        org.apache.poi.xwpf.usermodel.XWPFDocument d10 = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFTable t10 = d10.createTable(2, 2);
        t10.getRow(0).getCell(0).setText("A");
        t10.getRow(1).getCell(0).setText("B");

        WordTextEditor.setTableCell(d10, 0, 1, 0, "B改");
        check("set_table_cell: 内容被改",
                d10.getTables().get(0).getRow(1).getCell(0).getText().equals("B改"));
        check("set_table_cell: 同列上行不受影响",
                d10.getTables().get(0).getRow(0).getCell(0).getText().equals("A"));

        WordTextEditor.addTableRow(d10, 0, new String[]{"C", "D"});
        check("add_table_row: 行数 +1", d10.getTables().get(0).getRows().size() == 3);
        check("add_table_row: 新行内容正确",
                d10.getTables().get(0).getRow(2).getCell(1).getText().equals("D"));

        boolean threw2 = false;
        try { WordTextEditor.setTableCell(d10, 9, 0, 0, "x"); }
        catch (IndexOutOfBoundsException e) { threw2 = true; }
        check("set_table_cell: 表格越界抛异常", threw2);
```

Run: Task 6 Step 5 命令
Expected: 编译失败。

- [ ] **Step 2: 实现**

在 `WordTextEditor` 加：

```java
    // ================= 段落操作 =================

    public static void setParagraphText(XWPFDocument doc, int index, String text) {
        List<XWPFParagraph> ps = doc.getParagraphs();
        if (index < 0 || index >= ps.size())
            throw new IndexOutOfBoundsException("paragraph index " + index
                    + " out of range (document has " + ps.size() + " paragraphs)");
        rewriteParagraph(ps.get(index), text);
    }

    public static void insertParagraph(XWPFDocument doc, int index, String text,
                                       String style, boolean before) {
        List<XWPFParagraph> ps = doc.getParagraphs();
        if (index < 0 || index >= ps.size())
            throw new IndexOutOfBoundsException("paragraph index " + index
                    + " out of range (document has " + ps.size() + " paragraphs)");
        XWPFParagraph anchor = ps.get(index);
        XWPFParagraph np = doc.insertNewParagraph(
                before ? anchor.getCTP().newCursor() : nextCursor(anchor));
        if (np == null) throw new IllegalStateException("insertNewParagraph returned null");
        if (style != null && !style.isEmpty()) np.setStyle(style);
        np.createRun().setText(text);
    }

    private static org.apache.xmlbeans.XmlCursor nextCursor(XWPFParagraph anchor) {
        org.apache.xmlbeans.XmlCursor c = anchor.getCTP().newCursor();
        c.toNextSibling();
        return c;
    }

    public static void deleteParagraph(XWPFDocument doc, int index) {
        List<XWPFParagraph> ps = doc.getParagraphs();
        if (index < 0 || index >= ps.size())
            throw new IndexOutOfBoundsException("paragraph index " + index
                    + " out of range (document has " + ps.size() + " paragraphs)");
        doc.removeBodyElement(doc.getPosOfParagraph(ps.get(index)));
    }

    // ================= 表格操作 =================

    public static void setTableCell(XWPFDocument doc, int table, int row, int col, String text) {
        XWPFTable t = tableAt(doc, table);
        if (row < 0 || row >= t.getRows().size())
            throw new IndexOutOfBoundsException("row " + row + " out of range (table has "
                    + t.getRows().size() + " rows)");
        XWPFTableRow r = t.getRow(row);
        if (col < 0 || col >= r.getTableCells().size())
            throw new IndexOutOfBoundsException("col " + col + " out of range (row has "
                    + r.getTableCells().size() + " cells)");
        XWPFTableCell c = r.getCell(col);
        // setText 会重建单元格内容；保留首个段落对象以外的都清掉
        List<XWPFParagraph> cps = c.getParagraphs();
        if (cps.isEmpty()) { c.addParagraph().createRun().setText(text); return; }
        rewriteParagraph(cps.get(0), text);
        for (int k = cps.size() - 1; k >= 1; k--) c.removeParagraph(k);
    }

    public static void addTableRow(XWPFDocument doc, int table, String[] values) {
        XWPFTable t = tableAt(doc, table);
        XWPFTableRow r = t.createRow();
        int cols = r.getTableCells().size();
        if (values != null) {
            for (int i = 0; i < values.length && i < cols; i++) {
                XWPFTableCell c = r.getCell(i);
                if (c.getParagraphs().isEmpty()) c.addParagraph();
                rewriteParagraph(c.getParagraphs().get(0),
                        values[i] == null ? "" : values[i]);
            }
        }
    }

    static XWPFTable tableAt(XWPFDocument doc, int table) {
        List<XWPFTable> ts = doc.getTables();
        if (table < 0 || table >= ts.size())
            throw new IndexOutOfBoundsException("table index " + table
                    + " out of range (document has " + ts.size() + " tables)");
        return ts.get(table);
    }
```

顶部补 import：`import java.util.List;`（已有）、`import java.util.Map;`（已有）。

- [ ] **Step 3: 跑自测**

Run: Task 6 Step 5 命令
Expected: 全 PASS。

⚠️ `XWPFTableCell.removeParagraph(int)` 返回 `boolean` 而非 `void`；若编译报错说
"cannot be applied"，改用 `c.removeParagraph(cps.get(k))`（传对象的重载）。

- [ ] **Step 4: Commit**

```bash
git add aiagent/word-java/src
git commit -m "WordTextEditor：段落增删改 + 表格单元格/加行（越界一律抛异常）"
```

### Task 12: WordMarkdownWriter（create / append）

**Files:**
- Create: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordMarkdownWriter.java`
- Modify: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordEngineSelfTest.java`

- [ ] **Step 1: 先写失败的自测**

```java
        // ================= WordMarkdownWriter =================
        org.apache.poi.xwpf.usermodel.XWPFDocument d11 = newDoc();
        WordMarkdownWriter.writeBlocks(d11, WordMarkdown.parse(
                "# 报告\n\n正文一段\n\n- 甲\n- 乙\n\n| 月 | 值 |\n| 1 | 100 |"));
        check("writer: 标题段落已建", d11.getParagraphs().get(0).getText().equals("报告"));
        check("writer: 标题用了 Heading1",
                "Heading1".equals(d11.getParagraphs().get(0).getStyle()));
        check("writer: 正文段落", d11.getParagraphs().get(1).getText().equals("正文一段"));
        check("writer: 列表项", d11.getParagraphs().get(2).getText().equals("甲"));
        check("writer: 表格已建 2行2列",
                d11.getTables().size() == 1 && d11.getTables().get(0).getRows().size() == 2);
        check("writer: 表头内容", d11.getTables().get(0).getRow(0).getCell(0).getText().equals("月"));
        check("writer: 表体内容", d11.getTables().get(0).getRow(1).getCell(1).getText().equals("100"));

        // 行内粗体真的产生 bold run
        org.apache.poi.xwpf.usermodel.XWPFDocument d12 = newDoc();
        WordMarkdownWriter.writeBlocks(d12, WordMarkdown.parse("这**很粗**啊"));
        org.apache.poi.xwpf.usermodel.XWPFParagraph p12 = d12.getParagraphs().get(0);
        check("writer: 行内粗体拆成多 run", p12.getRuns().size() == 3);
        check("writer: 中间 run 是粗体", p12.getRuns().get(1).isBold()
                && p12.getRuns().get(1).text().equals("很粗"));
        check("writer: 首尾 run 不粗", !p12.getRuns().get(0).isBold()
                && !p12.getRuns().get(2).isBold());
```

Run: Task 6 Step 5 命令
Expected: 编译失败。

- [ ] **Step 2: 实现**

```java
package cn.tianlong.tlobject.aiagent.word;

import org.apache.poi.xwpf.usermodel.*;

import java.util.List;

/**
 * 写：Block 列表 → docx 内容（追加语义，新建=对空文档写）。
 *
 * 创建日期：2026/10/07 作者:tianlong
 */
public final class WordMarkdownWriter {

    private WordMarkdownWriter() {}

    public static void writeBlocks(XWPFDocument doc, List<WordMarkdown.Block> blocks) {
        for (WordMarkdown.Block b : blocks) {
            switch (b.type) {
                case HEADING: {
                    XWPFParagraph p = doc.createParagraph();
                    p.setStyle("Heading" + Math.max(1, Math.min(6, b.level)));
                    writeSpans(p, b.spans);
                    break;
                }
                case PARAGRAPH: {
                    XWPFParagraph p = doc.createParagraph();
                    writeSpans(p, b.spans);
                    break;
                }
                case BULLET: {
                    XWPFParagraph p = doc.createParagraph();
                    p.setStyle("ListParagraph");
                    p.setNumID(null);              // 无编号定义时退化为普通缩进段落
                    XWPFRun r = p.createRun();
                    r.setText("• ");
                    writeSpans(p, b.spans);
                    break;
                }
                case NUMBERED: {
                    XWPFParagraph p = doc.createParagraph();
                    XWPFRun r = p.createRun();
                    r.setText("• ");
                    writeSpans(p, b.spans);
                    break;
                }
                case TABLE: {
                    if (b.rows.isEmpty()) break;
                    int rows = b.rows.size();
                    int cols = 0;
                    for (List<String> r : b.rows) cols = Math.max(cols, r.size());
                    XWPFTable t = doc.createTable(rows, cols);
                    for (int i = 0; i < rows; i++) {
                        List<String> row = b.rows.get(i);
                        for (int j = 0; j < cols; j++) {
                            String v = j < row.size() ? row.get(j) : "";
                            XWPFTableCell c = t.getRow(i).getCell(j);
                            if (c.getParagraphs().isEmpty()) c.addParagraph();
                            WordTextEditor.rewriteParagraph(c.getParagraphs().get(0), v);
                        }
                    }
                    break;
                }
                case PAGEBREAK: {
                    XWPFParagraph p = doc.createParagraph();
                    p.createRun().addBreak(BreakType.PAGE);
                    break;
                }
                default: break;
            }
        }
    }

    private static void writeSpans(XWPFParagraph p, List<WordMarkdown.Span> spans) {
        for (WordMarkdown.Span s : spans) {
            XWPFRun r = p.createRun();
            r.setBold(s.bold);
            r.setText(s.text);
        }
    }
}
```

- [ ] **Step 3: 跑自测**

Run: Task 6 Step 5 命令
Expected: 全 PASS，其中 `writer: 标题用了 Heading1` 若 FAIL——新建文档的 styles.xml 里
可能没有 `Heading1` 定义。先打印 `p.getStyle()` 看实际值再决定：如果 POI 把它保留成
`"Heading1"` 就没问题；如果返回 null，改用 `p.setStyle("Heading1")` 前先
`doc.createStyles()` 或直接建带 outlineLvl 的段落。**不要直接删掉这条断言**——
标题样式是"结构级"的核心承诺之一。

- [ ] **Step 4: Commit**

```bash
git add aiagent/word-java/src
git commit -m "WordMarkdownWriter：markdown 块 → docx（标题/段落/列表/表格/分页/行内粗体）"
```

### Task 13: JavaWordEngine 门面（路径安全 / 原子写 / 错误映射 / 13 动作分发）

**Files:**
- Modify: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/JavaWordEngine.java`
- Modify: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/WordEngineSelfTest.java`

- [ ] **Step 1: 先写失败的自测**

```java
        // ================= JavaWordEngine 门面 =================
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("wordroot");
        java.nio.file.Path work = root.resolve("data/default/documents");
        JavaWordEngine.Config cfg = new JavaWordEngine.Config();
        cfg.allowedRoot = root;
        cfg.workDir = work;
        cfg.backup = true;              // 顺带验证 .bak 分支
        JavaWordEngine eng = new JavaWordEngine(cfg);

        // 裸文件名落到工作目录
        JavaWordEngine.Result rc = eng.execute("create",
                map("path", "报告.docx", "content", "# 标题\n\n正文"));
        check("engine: create 成功", rc.ok);
        check("engine: 裸文件名落到 workDir",
                work.resolve("报告.docx").toFile().isFile());
        check("engine: create 回执带 bytes", rc.json.get("bytes") != null);

        // 读回来
        JavaWordEngine.Result rr = eng.execute("read", map("path", "报告.docx"));
        check("engine: read 是文本模式", rr.textMode);
        check("engine: read 含标题内容", rr.text.contains("标题"));

        // replace 回执
        JavaWordEngine.Result rp = eng.execute("replace",
                map("path", "报告.docx", "find", "正文", "replace", "已改"));
        check("engine: replace 回执 replaced=1", Integer.valueOf(1).equals(rp.json.get("replaced")));
        check("engine: backup=true 时写了 .bak.docx",
                work.resolve("报告.bak.docx").toFile().isFile());

        // 占用/原子写：改完文件仍可读
        JavaWordEngine.Result rr2 = eng.execute("read", map("path", "报告.docx"));
        check("engine: 改后仍能读且内容已变", rr2.text.contains("已改"));

        // 不存在的文件
        JavaWordEngine.Result r404 = eng.execute("read", map("path", "没有这个.docx"));
        check("engine: 文件不存在 → ok=false", !r404.ok);
        check("engine: 错误文案含 not found",
                String.valueOf(r404.json.get("error")).toLowerCase().contains("not found"));

        // .doc 拒绝
        java.nio.file.Files.write(root.resolve("老格式.doc"), new byte[]{1, 2, 3});
        JavaWordEngine.Result rdoc = eng.execute("read", map("path", "老格式.doc"));
        check("engine: .doc 被拒", !rdoc.ok);
        check("engine: .doc 提示另存为 docx",
                String.valueOf(rdoc.json.get("error")).contains(".docx"));

        // 越界拒绝
        JavaWordEngine.Result rout = eng.execute("read",
                map("path", java.nio.file.Paths.get("C:/Windows/win.ini").toString()));
        check("engine: 越界路径被拒", !rout.ok);

        // 未知 action
        JavaWordEngine.Result rx = eng.execute("bogus", map("path", "报告.docx"));
        check("engine: 未知 action 报错", !rx.ok);
```

同时加个小工具（放辅助区）：

```java
    static java.util.Map<String, Object> map(Object... kv) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
```

Run: Task 6 Step 5 命令
Expected: 编译失败 / 断言失败。

- [ ] **Step 2: 实现门面**

把 `JavaWordEngine.execute` 及配套方法补全：

```java
    public Result execute(String action, Map<String, Object> input) {
        try {
            switch (action) {
                case "read":  return doRead(input);
                case "outline": return doText(input, "outline");
                case "tables":  return doText(input, "tables");
                case "info":    return doInfo(input);
                case "create":  return doCreate(input);
                case "append":  return doAppend(input);
                case "replace": return doReplace(input);
                case "set_paragraph":    return doSetParagraph(input);
                case "insert_paragraph": return doInsertParagraph(input);
                case "delete_paragraph": return doDeleteParagraph(input);
                case "set_table_cell":   return doSetTableCell(input);
                case "add_table_row":    return doAddTableRow(input);
                case "fill_template":    return doFillTemplate(input);
                default: return Result.fail("unknown action: " + action);
            }
        } catch (Exception e) {
            return Result.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ================= 路径 =================

    private Path resolve(String raw, boolean mustExist) throws IOException {
        if (raw == null || raw.trim().isEmpty()) throw new IOException("path is required");
        String s = raw.trim();
        boolean bare = s.indexOf('/') < 0 && s.indexOf('\\') < 0;
        Path p = bare ? cfg.workDir.resolve(s) : cfg.allowedRoot.resolve(s);
        p = p.normalize().toAbsolutePath();
        if (!p.startsWith(cfg.allowedRoot.toAbsolutePath().normalize()))
            throw new IOException("path outside allowed root (" + cfg.allowedRoot + "): " + raw);
        String lower = p.getFileName().toString().toLowerCase();
        if (!lower.endsWith(".docx"))
            throw new IOException("only .docx is supported: " + p.getFileName()
                    + (lower.endsWith(".doc") ? " — please save as .docx first" : ""));
        if (mustExist && !Files.isRegularFile(p))
            throw new IOException("file not found: " + p);
        return p;
    }

    private static String od(Map<String, Object> in, String k) {
        Object v = in.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static int oi(Map<String, Object> in, String k, int def) {
        Object v = in.get(k);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Exception e) { return def; }
    }
```

各 action 实现（每个都要先 `resolve` 再打开文档）：

```java
    private Result doRead(Map<String, Object> in) throws IOException {
        Path p = resolve(od(in, "path"), true);
        try (XWPFDocument doc = open(p)) {
            return Result.text(WordTextExtractor.read(doc, oi(in, "from", 0), oi(in, "to", -1)));
        }
    }

    private Result doText(Map<String, Object> in, String kind) throws IOException {
        Path p = resolve(od(in, "path"), true);
        try (XWPFDocument doc = open(p)) {
            if ("outline".equals(kind)) return Result.text(WordTextExtractor.outline(doc));
            return Result.text(WordTextExtractor.tables(doc, oi(in, "index", -1)));
        }
    }

    private Result doInfo(Map<String, Object> in) throws IOException {
        Path p = resolve(od(in, "path"), true);
        try (XWPFDocument doc = open(p)) {
            Result r = new Result();
            r.ok = true;
            r.json.putAll(WordTextExtractor.info(doc));
            r.json.put("ok", true);
            r.json.put("path", p.toString());
            return r;
        }
    }

    private Result doCreate(Map<String, Object> in) throws IOException {
        Path p = resolve(od(in, "path"), false);
        boolean overwrite = Boolean.parseBoolean(String.valueOf(in.getOrDefault("overwrite", "false")));
        if (Files.exists(p) && !overwrite)
            return Result.fail("file exists (pass overwrite=true): " + p);
        Files.createDirectories(p.getParent());
        String content = String.valueOf(in.getOrDefault("content", ""));
        try (XWPFDocument doc = new XWPFDocument()) {
            WordMarkdownWriter.writeBlocks(doc, WordMarkdown.parse(content));
            save(doc, p);
        }
        return receipt("create", p, null);
    }

    private Result doAppend(Map<String, Object> in) throws IOException {
        Path p = resolve(od(in, "path"), true);
        String content = String.valueOf(in.getOrDefault("content", ""));
        try (XWPFDocument doc = open(p)) {
            WordMarkdownWriter.writeBlocks(doc, WordMarkdown.parse(content));
            save(doc, p);
        }
        return receipt("append", p, null);
    }

    private Result doReplace(Map<String, Object> in) throws IOException {
        Path p = resolve(od(in, "path"), true);
        String find = od(in, "find");
        if (find == null || find.isEmpty()) return Result.fail("find is required");
        String repl = String.valueOf(in.getOrDefault("replace", ""));
        try (XWPFDocument doc = open(p)) {
            WordTextEditor.ReplaceReport rep = WordTextEditor.replaceInDocument(
                    doc, find, repl, oi(in, "index", -1),
                    od(in, "mode") == null ? "preserve" : od(in, "mode"));
            if (rep.replaced == 0 && rep.skipped == 0)
                return Result.fail("no match for: " + find);
            save(doc, p);
            return receipt("replace", p, rep);
        }
    }

    private Result doSetParagraph(Map<String, Object> in) throws IOException {
        Path p = resolve(od(in, "path"), true);
        try (XWPFDocument doc = open(p)) {
            WordTextEditor.setParagraphText(doc, oi(in, "index", -1), String.valueOf(in.getOrDefault("text", "")));
            save(doc, p);
        }
        return receipt("set_paragraph", p, null);
    }

    private Result doInsertParagraph(Map<String, Object> in) throws IOException {
        Path p = resolve(od(in, "path"), true);
        boolean before = !"after".equalsIgnoreCase(String.valueOf(in.getOrDefault("position", "before")));
        try (XWPFDocument doc = open(p)) {
            WordTextEditor.insertParagraph(doc, oi(in, "index", -1),
                    String.valueOf(in.getOrDefault("text", "")), od(in, "style"), before);
            save(doc, p);
        }
        return receipt("insert_paragraph", p, null);
    }

    private Result doDeleteParagraph(Map<String, Object> in) throws IOException {
        Path p = resolve(od(in, "path"), true);
        try (XWPFDocument doc = open(p)) {
            WordTextEditor.deleteParagraph(doc, oi(in, "index", -1));
            save(doc, p);
        }
        return receipt("delete_paragraph", p, null);
    }

    private Result doSetTableCell(Map<String, Object> in) throws IOException {
        Path p = resolve(od(in, "path"), true);
        try (XWPFDocument doc = open(p)) {
            WordTextEditor.setTableCell(doc, oi(in, "table", -1), oi(in, "row", -1),
                    oi(in, "col", -1), String.valueOf(in.getOrDefault("text", "")));
            save(doc, p);
        }
        return receipt("set_table_cell", p, null);
    }

    private Result doAddTableRow(Map<String, Object> in) throws IOException {
        Path p = resolve(od(in, "path"), true);
        Object v = in.get("values");
        String[] vals = null;
        if (v instanceof List) {
            List<?> l = (List<?>) v;
            vals = new String[l.size()];
            for (int i = 0; i < l.size(); i++) vals[i] = l.get(i) == null ? "" : String.valueOf(l.get(i));
        } else if (v != null && !String.valueOf(v).trim().isEmpty()) {
            vals = String.valueOf(v).split(",", -1);
        }
        try (XWPFDocument doc = open(p)) {
            WordTextEditor.addTableRow(doc, oi(in, "table", -1), vals);
            save(doc, p);
        }
        return receipt("add_table_row", p, null);
    }

    @SuppressWarnings("unchecked")
    private Result doFillTemplate(Map<String, Object> in) throws IOException {
        Path src = resolve(od(in, "path"), true);
        Path dst = od(in, "output") == null ? src : resolve(od(in, "output"), false);
        Map<String, String> data = new LinkedHashMap<>();
        Object raw = in.get("data");
        if (raw instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) raw).entrySet())
                data.put(e.getKey(), e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
        try (XWPFDocument doc = open(src)) {
            WordTextEditor.ReplaceReport rep = WordTextEditor.fillTemplate(doc, data);
            save(doc, dst);
            return receipt("fill_template", dst, rep);
        }
    }

    // ================= 文件生命周期 =================

    private XWPFDocument open(Path p) throws IOException {
        try {
            return new XWPFDocument(new ByteArrayInputStream(Files.readAllBytes(p)));
        } catch (org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException e) {
            throw new IOException("not a .docx file (corrupt, or a .doc renamed?): " + p);
        } catch (org.apache.poi.ooxml.POIXMLException e) {
            throw new IOException("document is corrupt: " + p + " (" + e.getMessage() + ")");
        }
    }

    /** 原子写：先写 .tmp 再 move，保证要么改成功要么原文件不动 */
    private void save(XWPFDocument doc, Path p) throws IOException {
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        try {
            if (cfg.backup && Files.isRegularFile(p)) {
                String n = p.getFileName().toString();
                Path bak = p.resolveSibling(n.substring(0, n.length() - 5) + ".bak.docx");
                Files.copy(p, bak, StandardCopyOption.REPLACE_EXISTING);
            }
            try (OutputStream o = Files.newOutputStream(tmp)) { doc.write(o); }
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (FileSystemException e) {
            throw new IOException("cannot write " + p + " — the file is locked (is it open in Word?), "
                    + "please close it and retry");
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    private Result receipt(String action, Path p, WordTextEditor.ReplaceReport rep) {
        Result r = new Result();
        r.ok = true;
        r.json.put("ok", true);
        r.json.put("action", action);
        r.json.put("path", p.toString());
        try { r.json.put("bytes", Files.size(p)); } catch (IOException ignored) {}
        if (rep != null) {
            r.json.put("replaced", rep.replaced);
            r.json.put("skipped", rep.skipped);
            if (!rep.skippedReasons.isEmpty()) r.json.put("skippedReasons", rep.skippedReasons);
        }
        return r;
    }
```

顶部补 import：

```java
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
```

- [ ] **Step 3: 跑自测**

Run: Task 6 Step 5 命令
Expected: 全 PASS。

- [ ] **Step 4: 补一条原子写的证伪用例**

证明"写失败时原文件不动"——用只读目录或写一个占位 `.tmp` 制造失败：

```java
        // 原子写：目标不可写时原文件必须原封不动
        java.nio.file.Path ro = root.resolve("只读.docx");
        java.nio.file.Files.copy(work.resolve("报告.docx"), ro);
        byte[] before = java.nio.file.Files.readAllBytes(ro);
        try {
            java.nio.file.Path tmp = ro.resolveSibling(ro.getFileName() + ".tmp");
            java.nio.file.Files.createDirectory(tmp);   // 用目录占住 .tmp 路径让写入失败
            JavaWordEngine.Result rf = eng.execute("replace",
                    map("path", "只读.docx", "find", "已改", "replace", "又改"));
            check("原子写: 失败时 ok=false", !rf.ok);
        } catch (Exception ignore) { }
        check("原子写: 原文件字节未变",
                java.util.Arrays.equals(before, java.nio.file.Files.readAllBytes(ro)));
```

Run: Task 6 Step 5 命令
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add aiagent/word-java/src
git commit -m "JavaWordEngine：13 动作分发 + 路径安全 + 原子写 + .docx 白名单 + 错误映射"
```

---

## Phase C：技能壳与接线

### Task 14: TLWordJavaSkill

**Files:**
- Create: `aiagent/word-java/src/main/java/cn/tianlong/tlobject/aiagent/word/TLWordJavaSkill.java`

- [ ] **Step 1: 实现技能壳**

照 `TLDesktopJavaSkill` 的结构（`setModuleParams` 建 schema、`execute` 取
`AI_P_SKILLINPUT`、整体 catch `Exception | LinkageError`）：

```java
package cn.tianlong.tlobject.aiagent.word;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Word 文档处理 Skill（Java 版）。函数名默认 word。
 * 读/写/改 .docx：正文、标题层级、表格、模板占位符填充。仅支持 .docx（.doc 请先另存）。
 *
 * 配置参数（XML params）：
 *   allowedRootPath  允许操作的根目录（默认 "."，按进程 CWD 解析）
 *   workDir          裸文件名落到的工作目录（默认 data/documents）
 *
 * 创建日期：2026/10/07 作者:tianlong
 */
public class TLWordJavaSkill extends TLBaseSkill {

    private String allowedRootPath = ".";
    private String workDir = "data/documents";

    public TLWordJavaSkill() { super(); }
    public TLWordJavaSkill(String name) { super(name); }
    public TLWordJavaSkill(String name, TLObjectFactory factory) { super(name, factory); }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("allowedRootPath") != null) allowedRootPath = params.get("allowedRootPath");
            if (params.get("workDir") != null) workDir = params.get("workDir");
        }
        super.setModuleParams();

        if (skillName == null || skillName.isEmpty()) skillName = "word";
        if (skillDescription == null || skillDescription.isEmpty())
            skillDescription = "Read, create and modify Microsoft Word .docx documents. "
                    + "Actions: read (structured text with paragraph numbers and styles), outline "
                    + "(headings only — use this FIRST on a long document), tables, info, "
                    + "create/append (content is Markdown: # headings, - bullets, |a|b| tables, "
                    + "**bold**, --- page break), replace (find/replace, preserves formatting by "
                    + "default; mode=rewrite if a paragraph is skipped), set_paragraph, "
                    + "insert_paragraph, delete_paragraph, set_table_cell, add_table_row, "
                    + "fill_template (replaces ${key}/{{key}} placeholders in body and tables). "
                    + "ONLY .docx is supported — .doc must be re-saved as .docx first. "
                    + "WORKFLOW: call outline or read first to get PARAGRAPH NUMBERS, then use "
                    + "those numbers with the modify actions — never guess. Paragraph numbers "
                    + "SHIFT after insert_paragraph/delete_paragraph, so re-read before the next "
                    + "edit. replace reports \"skipped\" when a paragraph contains hyperlinks or "
                    + "field codes (those cannot be edited in place without damage) — if skipped>0, "
                    + "retry that edit with mode=rewrite.";

        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();
            parameterSchema.put("action", prop("string",
                    "read(from?,to?) | outline | tables(index?) | info | create(path,content,overwrite?) | "
                    + "append(path,content) | replace(path,find,replace,index?,mode?) | "
                    + "set_paragraph(path,index,text) | insert_paragraph(path,index,text,style?,position?) | "
                    + "delete_paragraph(path,index) | set_table_cell(path,table,row,col,text) | "
                    + "add_table_row(path,table,values?) | fill_template(path,data,output?)", true));
            parameterSchema.put("path", prop("string", "File path: bare name → work dir; relative → allowed root; absolute must be inside allowed root"));
            parameterSchema.put("content", prop("string", "Markdown content for create/append"));
            parameterSchema.put("find", prop("string", "Text to find (literal) for replace"));
            parameterSchema.put("replace", prop("string", "Replacement text"));
            parameterSchema.put("index", prop("number", "Paragraph number from read/outline; for `tables` = table number (-1 = all)"));
            parameterSchema.put("mode", prop("string", "replace mode: preserve (default, keeps formatting) | rewrite"));
            parameterSchema.put("text", prop("string", "Text for set_paragraph / insert_paragraph / set_table_cell"));
            parameterSchema.put("style", prop("string", "Paragraph style for insert_paragraph, e.g. Heading1"));
            parameterSchema.put("position", prop("string", "insert_paragraph: before (default) | after"));
            parameterSchema.put("table", prop("number", "Table number from read/tables"));
            parameterSchema.put("row", prop("number", "0-based row index"));
            parameterSchema.put("col", prop("number", "0-based column index"));
            parameterSchema.put("values", prop("string", "Comma-separated cell values for add_table_row"));
            parameterSchema.put("data", prop("object", "Key→value map for fill_template placeholders"));
            parameterSchema.put("output", prop("string", "fill_template output path (default: overwrite input)"));
            parameterSchema.put("overwrite", prop("boolean", "create: allow overwriting an existing file"));
            parameterSchema.put("from", prop("number", "read: first paragraph number (default 0)"));
            parameterSchema.put("to", prop("number", "read: last paragraph number (-1 = end)"));
        }
    }

    private Map<String, Object> prop(String type, String desc) { return prop(type, desc, false); }

    private Map<String, Object> prop(String type, String desc, boolean required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", desc);
        if (required) m.put("required", true);
        return m;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        Map<String, Object> input = (Map<String, Object>) msg.getMapParam(AI_P_SKILLINPUT, new LinkedHashMap<>());
        if (input.isEmpty()) {
            input = new LinkedHashMap<>();
            for (String k : new String[]{"action", "path", "content", "find", "replace", "index",
                    "mode", "text", "style", "position", "table", "row", "col", "values", "data",
                    "output", "overwrite", "from", "to"}) {
                if (msg.containsParam(k)) input.put(k, msg.getParam(k));
            }
        }
        String action = input.get("action") != null ? String.valueOf(input.get("action")) : "";
        if (action.isEmpty())
            return createMsg().setParam(RESULT, false).setParam(AI_P_SKILLOUTPUT,
                    "Error: action is required");

        try {
            JavaWordEngine eng = ensureEngine(msg);
            JavaWordEngine.Result r = eng.execute(action.toLowerCase(), input);
            if (r.textMode)
                return createMsg().setParam(RESULT, r.ok).setParam(AI_P_SKILLOUTPUT, r.text);
            return createMsg().setParam(RESULT, r.ok).setParam(AI_P_SKILLOUTPUT, GSON.toJson(r.json));
        } catch (Exception e) {
            putLog("Word action failed: " + e, LogLevel.WARN);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "{\"ok\":false,\"error\":\"" + esc(String.valueOf(e)) + "\"}");
        } catch (LinkageError e) {
            putLog("Word action failed (linkage): " + e, LogLevel.WARN);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "{\"ok\":false,\"error\":\"missing classes (POI not on classpath?): "
                            + esc(String.valueOf(e)) + "\"}");
        }
    }

    /** 每用户独立工作目录；userId 缺省 default（与 attachmentStore 的 data/{uid}/ 约定一致） */
    private JavaWordEngine ensureEngine(TLMsg msg) {
        JavaWordEngine.Config cfg = new JavaWordEngine.Config();
        cfg.allowedRoot = Paths.get(allowedRootPath);
        String userId = msg.getStringParam(AI_P_USERID, "default");
        cfg.workDir = Paths.get(workDir).resolve(userId);
        return new JavaWordEngine(cfg);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 与 browser-java/desktop-java 同款：base64 的 "=" 不能被 HTML 转义 */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
}
```

- [ ] **Step 2: 编译**

Run: `/d/maven/bin/mvn -q -pl aiagent/word-java -am install -DskipTests`
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add aiagent/word-java/src
git commit -m "TLWordJavaSkill：技能壳（schema/输入解析/JSON 回执/LinkageError 防护）"
```

### Task 15: 接线（7 处）

**Files:**
- Modify: `conf/tlobject/cn.tianlong.tlobject.aiagent_config.xml`
- Modify: `demo/tlobject/src/main/resources/conf/demo/aiagent/fileAgent_config.xml`
- Modify: `demo/tlobject/src/main/resources/conf/demo/aiagent/aiagent_master_config.xml`
- Modify: 5 个 `aistart*.bat`

⚠️ 路径确认：`cn.tianlong.tlobject.aiagent_config.xml` 的**源**在
`demo/tlobject/src/main/resources/conf/tlobject/`，上面 Tasks 里读的是这份。

- [ ] **Step 1: 框架注册表注册模块**

`conf/tlobject/cn.tianlong.tlobject.aiagent_config.xml` 里，`desktopJavaSkill` 那个 module
**之后**插入：

```xml
        <!-- Word 文档处理技能（进程内 POI，无子进程）；仅 .docx，.doc 需先另存 -->
        <module name="wordJavaSkill" classfile="cn.tianlong.tlobject.aiagent.word.TLWordJavaSkill"
                singleton="true"/>
```

- [ ] **Step 2: fileAgent 挂技能**

`fileAgent_config.xml` 的 `<skills>` 里，`fileOperationSkill` **之后**加：

```xml
        <skill name="word"
               sameClassAs="wordJavaSkill"
               statup="true"
               skillName="word"
               allowedRootPath="."
               workDir="data/documents"/>
```

- [ ] **Step 3: 补主 agent 对 fileAgent 的描述（易漏点）**

`aiagent_master_config.xml` 里 `fileAgent` 的 `description`，把：

```
description="文件操作专家，读写、列表、删除文件。仅用于本地文件操作任务。网页下载请用 browser，代码执行请用 codeAgent。"
```

改成：

```
description="文件操作专家，读写、列表、删除文件，并支持 Word 文档（.docx）的读取、生成与修改（含表格、模板占位符填充）。仅用于本地文件操作任务。网页下载请用 browser，代码执行请用 codeAgent。"
```

- [ ] **Step 4: 5 个 bat 加 word-java classpath**

每个 bat 里，把：

```
D:\tlobjectapp\tlobject\aiagent\desktop-java\target\classes;
```

替换为：

```
D:\tlobjectapp\tlobject\aiagent\desktop-java\target\classes;D:\tlobjectapp\tlobject\aiagent\word-java\target\classes;
```

- [ ] **Step 5: 校验 XML 与 bat**

Run:

```bash
python -c "
import xml.etree.ElementTree as ET
for f in ['demo/tlobject/src/main/resources/conf/tlobject/cn.tianlong.tlobject.aiagent_config.xml',
          'demo/tlobject/src/main/resources/conf/demo/aiagent/fileAgent_config.xml',
          'demo/tlobject/src/main/resources/conf/demo/aiagent/aiagent_master_config.xml']:
    ET.parse(f); print('OK', f)
for f in ['aistart.bat','aistart-web.bat','aistart_dq.bat','aistart_tl.bat','aistart_xm.bat']:
    s=open(f,encoding='utf-8',errors='replace').read()
    print(('OK ' if 'aiagent\\\\word-java\\\\target\\\\classes' in s else 'MISSING ')+f)
"
```

Expected: 3 行 `OK` + 5 行 `OK`。**XML 必须先校验后落盘**——这是踩过的坑。

- [ ] **Step 6: 重新构建 + 同步资源**

```bash
/d/maven/bin/mvn -q -pl demo/tlobject -am install -DskipTests
```

⚠️ demo 的 config 走 `target\classes`，`process-resources` 即可生效，不必完整 install——
但这里用了新的模块依赖，稳妥起见走 install。

- [ ] **Step 7: Commit**

```bash
git add demo/tlobject/src/main/resources/conf aistart.bat aistart-web.bat aistart_dq.bat aistart_tl.bat aistart_xm.bat
git commit -m "接线：注册 wordJavaSkill + fileAgent 挂载 + master 描述补 Word + 5 bat classpath"
```

### Task 16: 无 LLM 端到端（走真实框架注册表）

**Files:**
- 无改动（验证任务）

- [ ] **Step 1: 起应用**

Run: `./aistart.bat`（保持 stdin——后台启动会被杀掉）

- [ ] **Step 2: 用控制台验证技能可见**

在控制台输入 `/skills`
Expected: 列表中出现 `word`，描述含 "Read, create and modify Microsoft Word"。

- [ ] **Step 3: 建临时直连冒烟（不经过 LLM，不提交）**

在 `demo/tlobject` 的 demo 包下建 `WordSkillSmoke.java`（**验证完删掉**）：

```java
package cn.tianlong.java.demo.aiagent;

import cn.tianlong.tlobject.base.*;
import java.util.*;

/** 临时冒烟：绕过 LLM，直接给 fileAgent 的 word 技能发消息。验证完删除。 */
public class WordSkillSmoke {
    public static void main(String[] args) throws Exception {
        TLObjectFactory factory = TLObjectFactory.getInstance(args);
        factory.startFactory();
        factory.boot();

        Map<String, Object> in = new LinkedHashMap<>();
        in.put("action", "create");
        in.put("path", "smoke.docx");
        in.put("content", "# 冒烟\n\n正文\n\n| A | B |\n| 1 | 2 |");

        TLMsg m = TLMsg.newMsg().setAction("skillExecute")
                .setParam("skillInput", in)
                .setSystemParam("userId", "aitest");     // 固定 userId，别污染 default 目录
        TLMsg r = factory.putMsg("fileAgent:word", m);
        System.out.println("create 回执 = " + r.getParam("skillOutput"));

        in.clear();
        in.put("action", "read");
        in.put("path", "smoke.docx");
        TLMsg m2 = TLMsg.newMsg().setAction("skillExecute")
                .setParam("skillInput", in).setSystemParam("userId", "aitest");
        System.out.println("read 输出 = " + factory.putMsg("fileAgent:word", m2).getParam("skillOutput"));

        factory.shutdown(0);
    }
}
```

⚠️ 上面 `TLObjectFactory.getInstance(args)` / `boot()` / `TLMsg.newMsg()` / 家族名
`"fileAgent:word"` 的**确切签名以实际代码为准**——动手前先
`grep -n "getInstance\|public.*boot\|newMsg" core/src/main/java/cn/tianlong/tlobject/base/*.java`
确认。参数名 `skillInput` 对应 `AI_P_SKILLINPUT`；`fileAgent:word` 是家族名
（见记忆「模块名解析的两个坑」：家族名只有走注册表才解析得到）。

- [ ] **Step 4: 跑并核对**

用 aistart.bat 抽出的 classpath + `demo/tlobject/target/classes` 跑它。

Expected:
- 文件出现在 `data/documents/aitest/smoke.docx`（**在 CWD 下，即仓库根**）
- `read` 输出含 `[0] [Heading1] 冒烟` 与 `[表格 0]`

- [ ] **Step 5: 删除临时类并记录结果**

```bash
rm demo/tlobject/src/main/java/cn/tianlong/java/demo/aiagent/WordSkillSmoke.java
```

通过则无需 commit。失败则按现象定位：参数没透传 → 查 `AI_P_SKILLINPUT` 解包；
模块找不到 → 查 Step 1 的注册与 bat。

---

## Phase D：冒烟与文档

### Task 17: 真 LLM 冒烟

**Files:** 无改动（验证任务）

- [ ] **Step 1: 让 master 转交 fileAgent 写文档**

在控制台输入：
`请让文件专家帮我生成一份 Word 文档：D:/tmp/冒烟报告.docx，标题"季度报告"，正文两段，再带一个两行三列的表格`

Expected: master 转交 fileAgent；fileAgent 调 `word(create)`；回执含路径。
**人工打开该 .docx 确认**：标题是 Heading 样式、段落正常、表格是真实 Word 表格。

- [ ] **Step 2: 让 agent 改文档（跨 run 保格式的真实验证）**

先用 Word/WPS 手工造一个模板：一段带格式的文字里写上 `${name}`（在 Word 里正常输入，
run 会被切碎），保存。

然后输入：`把 D:/tmp/模板.docx 里的 ${name} 填成"张三"，另存为 D:/tmp/填好.docx`

Expected: `fill_template` 回执 `replaced>=1, skipped=0`；打开 `填好.docx` 确认
**占位符位置的字体/字号/加粗没变**。这是整个技能最核心的承诺，必须在真实 Word 文件上验一次。

- [ ] **Step 3: webui 上传链路**

在 webui 里上传一个 .docx，让 agent 读它并总结。
Expected: agent 从附件清单拿到路径 → 调 `word(read)` → 正确总结。

- [ ] **Step 4: 回归 web 上传（POI 升级的连带风险）**

在 webui 上传一个 .xlsx。
Expected: 上传与后续处理正常（`commons-io` 2.4→2.21 的回归点）。

### Task 18: 文档更新

**Files:**
- Modify: `CLAUDE.md`
- Modify: `aiagent/README.md`（若有模块清单/技能表）

- [ ] **Step 1: CLAUDE.md 补条目**

在模块表附近加一行 word 技能说明与「POI 5.5.1 全局统一」的注记；在 AI Agent 关键实现
要点里补一条：**`.docx` 修改走跨 run 最小重建保格式；命中超链接/域/换行 run 时跳过并上报，
不静默改坏**。

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md aiagent/README.md
git commit -m "文档：补 word 技能与 POI 5.5.1 升级说明"
```

---

## 完成标准

- [ ] `WordEngineSelfTest` 全绿（引擎级）
- [ ] Excel 读写往返正常（POI 升级未破坏既有功能）
- [ ] 控制台 `/skills` 出现 `word`
- [ ] 真实 Word 文件上 `fill_template` 保格式（Task 17 Step 2 人工确认）
- [ ] webui 上传 .docx 能读、上传 .xlsx 不回归
- [ ] 5 个 bat 都能起（classpath 无遗漏）
