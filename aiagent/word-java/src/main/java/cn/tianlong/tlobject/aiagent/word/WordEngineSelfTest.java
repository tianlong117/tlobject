package cn.tianlong.tlobject.aiagent.word;

/**
 * word 引擎自测（runnable main，无 JUnit——与仓库自测惯例一致）。
 *
 * 运行：
 *   /d/maven/bin/mvn -q -pl aiagent/word-java dependency:build-classpath -Dmdep.outputFile=cp.txt
 *   java -cp "aiagent/word-java/target/classes;$(cat aiagent/word-java/cp.txt)" \
 *        cn.tianlong.tlobject.aiagent.word.WordEngineSelfTest
 * 失败以退出码 1 结束。
 */
public class WordEngineSelfTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        check("骨架可实例化", true);

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
        check("read: 普通段落标注 Normal", txt.contains("[1] [Normal]"));
        check("read: 内嵌表格标记", txt.contains("[表格 0]"));
        check("read: 表格单元格内容入文本", txt.contains("月份") && txt.contains("100"));
        check("read: from/to 切片只取第二段",
                WordTextExtractor.read(d1, 1, 1).contains("[1] ")
                        && !WordTextExtractor.read(d1, 1, 1).contains("项目报告"));

        String ol = WordTextExtractor.outline(d1);
        check("outline: 只出标题", ol.contains("[0] 项目报告") && !ol.contains("本报告统计"));

        // 不写 pStyle、只靠 outlineLvl 表示标题的文档（read 与 outline 必须一致认它）
        org.apache.poi.xwpf.usermodel.XWPFDocument d2 = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFParagraph op = d2.createParagraph();
        op.getCTP().addNewPPr().addNewOutlineLvl().setVal(java.math.BigInteger.valueOf(0));
        op.createRun().setText("靠 outlineLvl 的标题");
        addPara(d2, "正文", null);
        check("outline: 认 outlineLvl 标题（与 read 的 [Heading1] 一致）",
                WordTextExtractor.outline(d2).contains("[0] 靠 outlineLvl 的标题")
                        && WordTextExtractor.read(d2, 0, 0).contains("[0] [Heading1]")
                        && Integer.valueOf(1).equals(WordTextExtractor.info(d2).get("headings")));

        String tb = WordTextExtractor.tables(d1, -1);
        check("tables: 二维内容", tb.contains("月份") && tb.contains("1月"));

        java.util.Map<String, Object> inf = WordTextExtractor.info(d1);
        check("info: 段落数 = 2", Integer.valueOf(2).equals(inf.get("paragraphs")));
        check("info: 表格数 = 1", Integer.valueOf(1).equals(inf.get("tables")));
        check("info: 标题数 = 1", Integer.valueOf(1).equals(inf.get("headings")));

        // ================= 跨 run 匹配核心 =================
        // 段落 = "订单号：${orderNo}"，切成 ["订单号：","${","orderNo","}"]
        // 注：doc 变量名用 d2b——d2 已被上面的 outlineLvl 用例占用
        org.apache.poi.xwpf.usermodel.XWPFDocument d2b = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFParagraph p2 = addSplitPara(d2b,
                new String[]{"订单号：", "${", "orderNo", "}"}, true);

        int n2 = WordTextEditor.replaceInParagraph(p2, "${orderNo}", "A123");
        check("跨run: 替换计数 = 1", n2 == 1);
        check("跨run: 段落文本已是替换后", "订单号：A123".equals(p2.getText()));
        check("跨run: 首 run 的 bold 保住了", p2.getRuns().get(0).isBold());
        check("跨run: 字号保住 = 14", p2.getRuns().get(0).getFontSize() == 14);
        check("跨run: run 数量未增加（最小重建，不新建 run）", p2.getRuns().size() == 4);
        // 上一条查的是没被动的 run[0]；真正接住新文本的是 run[1]，它的格式才是承诺所在
        check("跨run: 接收新文本的 run[1] 字号也保住 = 14", p2.getRuns().get(1).getFontSize() == 14);

        // 替换后再次替换，验证可重复
        check("跨run: 可再次替换", WordTextEditor.replaceInParagraph(p2, "A123", "B456") == 1
                && "订单号：B456".equals(p2.getText()));

        // 落盘重载：内存里的结果要能在 XML 里活下来（rPr 不被序列化丢掉才算真保住）
        java.nio.file.Path p2f = saveTmp(d2b, "crossrun.docx");
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(p2f);
             org.apache.poi.xwpf.usermodel.XWPFDocument re =
                     new org.apache.poi.xwpf.usermodel.XWPFDocument(in)) {
            org.apache.poi.xwpf.usermodel.XWPFParagraph rp = re.getParagraphs().get(0);
            check("跨run: 落盘重载后文本/格式/run 数都在",
                    "订单号：B456".equals(rp.getText()) && rp.getRuns().size() == 4
                            && rp.getRuns().get(0).isBold() && rp.getRuns().get(1).getFontSize() == 14);
        }

        // 同段多处替换：区间表必须在每次替换后跟上（长度变化的替换最容易露馅——
        // 增量更新会留下空隙/重叠，表现为漏替换或把文本写串）
        org.apache.poi.xwpf.usermodel.XWPFDocument dMulti = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFParagraph pMulti = dMulti.createParagraph();
        pMulti.createRun().setText("X");
        pMulti.createRun().setText("a-b");
        pMulti.createRun().setText("c-d");
        check("多处: 同段两处都替换且文本不错位",
                WordTextEditor.replaceInParagraph(pMulti, "-", "--") == 2
                        && "Xa--bc--d".equals(pMulti.getText()));

        // 单个 run 内替换
        org.apache.poi.xwpf.usermodel.XWPFDocument d3 = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFParagraph p3 = addPara(d3, "金额：100 元", null);
        check("同run: 替换成功", WordTextEditor.replaceInParagraph(p3, "100", "200") == 1
                && "金额：200 元".equals(p3.getText()));

        // 不安全 run 要被跳过而不是改坏。
        // 判据是"匹配区间 [ri,rj] 碰到不安全 run"（spec §5），不是"段落里有不安全 run"：
        // 落在含 <w:br/> 的 run 上时，hay 里含 run.text() 补出的 '\n'，重写会把这个换行标志
        // 当普通字符写进 <w:t>（凭空多一个换行）——所以占位符必须真的压在这个 run 上才触发。
        org.apache.poi.xwpf.usermodel.XWPFDocument d4 = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFParagraph p4 = d4.createParagraph();
        org.apache.poi.xwpf.usermodel.XWPFRun r4 = p4.createRun();
        r4.setText("第一行有 ${x} 占位符");
        r4.addBreak();                      // 段落内换行 → 该 run 不安全
        org.apache.poi.xwpf.usermodel.XWPFRun r4b = p4.createRun();
        r4b.setText("的模板块");
        check("不安全run: 匹配落在含 br 的 run 上返回 -2（跳过）",
                WordTextEditor.replaceInParagraph(p4, "${x}", "OK") == -2);
        check("不安全run: 文本未被改动", p4.getText().contains("${x}"));
        // 反面守：同段落里没被不安全 run 波及的匹配照常替换——判据过宽会让整段不可编辑，
        // "很安全但没用"，而这里改的 run 是干净的，没有任何东西会被写坏
        check("不安全run: 同段落在干净 run 上的匹配不被殃及",
                WordTextEditor.replaceInParagraph(p4, "模板块", "模板") == 1
                        && p4.getText().contains("的模板"));

        // 匹配不到
        check("无匹配: 返回 0", WordTextEditor.replaceInParagraph(p3, "不存在的词", "x") == 0);

        org.apache.poi.xwpf.usermodel.XWPFParagraph p5 = newDoc().createParagraph();
        org.apache.poi.xwpf.usermodel.XWPFRun r5 = p5.createRun();
        r5.setBold(true); r5.setText("普通的 ${y} 文字");
        check("误判检查: 普通 run 不被当作不安全",
                WordTextEditor.replaceInParagraph(p5, "${y}", "Z") == 1);

        // 原子性：一处可改、一处不可改时，整段不动
        org.apache.poi.xwpf.usermodel.XWPFDocument dAtom = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFParagraph pAtom = dAtom.createParagraph();
        org.apache.poi.xwpf.usermodel.XWPFRun rA = pAtom.createRun();
        rA.setText("目标");
        org.apache.poi.xwpf.usermodel.XWPFRun rB = pAtom.createRun();
        rB.setText("目标");
        rB.addBreak();                    // 第二个 run 不安全
        String atomBefore = pAtom.getText();
        check("原子性: 段内后一处不可改 → 整段放弃（-2）",
                WordTextEditor.replaceInParagraph(pAtom, "目标", "改后") == -2);
        check("原子性: 前一处也没被改（不是半改状态）", atomBefore.equals(pAtom.getText()));

        // ================= 文档级 replace / fill_template =================
        org.apache.poi.xwpf.usermodel.XWPFDocument d6 = newDoc();
        addSplitPara(d6, new String[]{"客户：", "${", "cust", "}"}, false);
        addPara(d6, "金额：${amount} 元", null);
        org.apache.poi.xwpf.usermodel.XWPFTable t6 = d6.createTable(1, 2);
        t6.getRow(0).getCell(0).setText("${amount}");
        t6.getRow(0).getCell(1).setText("备注");

        java.util.Map<String, String> tpl = new java.util.LinkedHashMap<>();
        tpl.put("cust", "张三");
        tpl.put("amount", "100");
        WordTextEditor.ReplaceReport rep = WordTextEditor.fillTemplate(d6, tpl);
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
        check("fill: 缺失键不替换、不报错", rep2.replaced == 0
                && d7.getParagraphs().get(0).getText().contains("${unknown}"));

        // 段落限定替换
        org.apache.poi.xwpf.usermodel.XWPFDocument d8 = newDoc();
        addPara(d8, "目标 目标", null);
        addPara(d8, "目标", null);
        check("replace: index 限定只改指定段落",
                WordTextEditor.replaceInDocument(d8, "目标", "改后", 1, "preserve").replaced == 1
                        && d8.getParagraphs().get(0).getText().equals("目标 目标")
                        && d8.getParagraphs().get(1).getText().equals("改后"));

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

        // 后插（before=false）：普通位置走游标，锚点是 body 最后一个元素时没有下一兄弟，
        // 走 createParagraph 兜底——实测不兜底会把新段落插到文档开头（Probe4/见报告）
        org.apache.poi.xwpf.usermodel.XWPFDocument d9b = newDoc();
        addPara(d9b, "A", null);
        addPara(d9b, "B", null);
        addPara(d9b, "C", null);
        WordTextEditor.insertParagraph(d9b, 1, "MID", null, false);
        check("insert_paragraph: 中间段后插位置正确",
                d9b.getParagraphs().get(2).getText().equals("MID")
                        && d9b.getParagraphs().get(3).getText().equals("C"));
        WordTextEditor.insertParagraph(d9b, d9b.getParagraphs().size() - 1, "TAIL", null, false);
        check("insert_paragraph: 末段后插落在末尾（而非文档开头）",
                d9b.getParagraphs().size() == 5
                        && d9b.getParagraphs().get(4).getText().equals("TAIL"));
        java.nio.file.Path p9b = saveTmp(d9b, "insertafter.docx");
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(p9b);
             org.apache.poi.xwpf.usermodel.XWPFDocument re =
                     new org.apache.poi.xwpf.usermodel.XWPFDocument(in)) {
            check("insert_paragraph: 落盘重载后顺序不变（XML 里位置也对）",
                    re.getParagraphs().size() == 5
                            && re.getParagraphs().get(2).getText().equals("MID")
                            && re.getParagraphs().get(4).getText().equals("TAIL"));
        }

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

        // 行/列越界同样必须抛——两级边界各自判定，漏一个就是静默改错格
        boolean threw3 = false;
        try { WordTextEditor.setTableCell(d10, 0, 9, 0, "x"); }
        catch (IndexOutOfBoundsException e) { threw3 = true; }
        check("set_table_cell: 行越界抛异常", threw3);
        boolean threw4 = false;
        try { WordTextEditor.setTableCell(d10, 0, 0, 9, "x"); }
        catch (IndexOutOfBoundsException e) { threw4 = true; }
        check("set_table_cell: 列越界抛异常", threw4);
        boolean threw5 = false;
        try { WordTextEditor.deleteParagraph(d10, 99); }
        catch (IndexOutOfBoundsException e) { threw5 = true; }
        check("delete_paragraph: 越界抛异常", threw5);

        // 跨模块契约：编辑动作的段落号与 WordTextExtractor 的 [N] 必须同一套。
        // 逐行核对（用编辑器自己的下标去查 extractor 输出），避免把序号写死写错
        String[] rl = WordTextExtractor.read(d9b, 0, -1).split("\n");
        boolean sameNumbering = rl.length == d9b.getParagraphs().size();
        for (int i = 0; sameNumbering && i < rl.length; i++) {
            if (!rl[i].equals("[" + i + "] [Normal] "
                    + d9b.getParagraphs().get(i).getText())) sameNumbering = false;
        }
        check("编号一致: 增删后 extractor 的 [N] 与编辑动作同号（逐行核对）", sameNumbering);

        // ================= rewrite 逃生舱：不得重复换行（Task 10 遗留）=================
        // 缺陷实测（HEAD 版）：getText 把 br 合成 \n 写回 <w:t>（字面 \n）而 br 仍在
        //   → "金额：${amount}\n尾注" 替换后变成 "金额：100\n尾注\n"（多一个换行，落盘重载仍在）
        // 计划 Fix1 明说 rewrite 语义=丢段内格式「先拉平」，Fix2 清残留 br，
        // 两者合力后实测=「金额：100 尾注」——空格，一个 \n 都没有（Fix1+Fix2 的必然结果）。
        // 故此处期望串按计划给定的修复代码实测值写，而非计划里那句按「仅 Fix2」推出的
        // "金额：100\n尾注"（那个字面量与 Fix1 互斥，二者同时应用时不可能出现）。
        org.apache.poi.xwpf.usermodel.XWPFDocument dRw = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFParagraph pRw = dRw.createParagraph();
        org.apache.poi.xwpf.usermodel.XWPFRun rRw = pRw.createRun();
        rRw.setText("金额：${amount}");
        rRw.addBreak();
        org.apache.poi.xwpf.usermodel.XWPFRun rRw2 = pRw.createRun();
        rRw2.setText("尾注");
        WordTextEditor.replaceInDocument(dRw, "${amount}", "100", -1, "rewrite");
        check("rewrite: 整段文本被拉平（无重复换行）",
                dRw.getParagraphs().get(0).getText().equals("金额：100 尾注"));
        check("rewrite: 文本里没有残留 \\n（不重复也不尾随）",
                !dRw.getParagraphs().get(0).getText().contains("\n"));
        check("rewrite: 残留 br 已清掉",
                dRw.getParagraphs().get(0).getRuns().stream()
                        .noneMatch(r -> r.getCTR().sizeOfBrArray() > 0));
        // 原文缺陷「survives save→reload」——重载后必须还是干净的
        java.nio.file.Path pRwf = saveTmp(dRw, "rewrite.docx");
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(pRwf);
             org.apache.poi.xwpf.usermodel.XWPFDocument re =
                     new org.apache.poi.xwpf.usermodel.XWPFDocument(in)) {
            check("rewrite: 落盘重载后仍无重复换行",
                    re.getParagraphs().get(0).getText().equals("金额：100 尾注"));
        }

        // ================= WordMarkdownWriter =================
        org.apache.poi.xwpf.usermodel.XWPFDocument d11 = newDoc();
        WordMarkdownWriter.writeBlocks(d11, WordMarkdown.parse(
                "# 报告\n\n正文一段\n\n- 甲\n- 乙\n\n| 月 | 值 |\n| 1 | 100 |"));
        check("writer: 标题段落已建", d11.getParagraphs().get(0).getText().equals("报告"));
        check("writer: 标题用了 Heading1",
                "Heading1".equals(d11.getParagraphs().get(0).getStyle()));
        // 标题必须是"真标题"：直接格式（粗体）与 outlineLvl 都要落盘，
        // 否则 Word 打开可能退化成正文、导航窗格不认（Task 8 实测发现 new XWPFDocument()
        // 没有 styles part，只写 pStyle 是悬空引用）
        check("writer: 标题 run 是粗体（直接格式兜底）",
                d11.getParagraphs().get(0).getRuns().get(0).isBold());
        check("writer: 标题设了 outlineLvl=0",
                d11.getParagraphs().get(0).getCTP().getPPr() != null
                        && d11.getParagraphs().get(0).getCTP().getPPr().getOutlineLvl() != null
                        && d11.getParagraphs().get(0).getCTP().getPPr().getOutlineLvl().getVal().intValue() == 0);
        check("writer: 正文段落", d11.getParagraphs().get(1).getText().equals("正文一段"));
        // 计划期望 "甲"，与计划给的实现互斥——实测（字符级探针）：该段文本 = U+2022 U+0020 + "甲"。
        // 判定：**实现对、断言错**。保留字面项目符号是有意的兜底：新建文档既无 numbering part
        // 也无 styles part，做不出自动编号，pStyle="ListParagraph" 就是悬空引用；去掉 "• " 的后果是
        // `- 甲` 在 Word/WPS 里退化成普通正文——与"标题只写 pStyle 会退化成正文"同一类静默损失
        // （本任务标题三重设定的立论基础）。故按实测值钉死：谁把符号去掉，这条立刻红。
        check("writer: 列表项（带字面项目符号，无编号定义时的可见性兜底）",
                d11.getParagraphs().get(2).getText().equals("• 甲"));
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

        // 落盘重载后标题仍是标题（内存断言骗不了文件）
        java.nio.file.Path hPath = saveTmp(d11, "heading.docx");
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(hPath);
             org.apache.poi.xwpf.usermodel.XWPFDocument re =
                     new org.apache.poi.xwpf.usermodel.XWPFDocument(in)) {
            org.apache.poi.xwpf.usermodel.XWPFParagraph hp = re.getParagraphs().get(0);
            check("writer: 落盘后 style 仍是 Heading1",
                    "Heading1".equals(hp.getStyle()));
            check("writer: 落盘后 outlineLvl 仍在",
                    hp.getCTP().getPPr() != null
                            && hp.getCTP().getPPr().getOutlineLvl() != null
                            && hp.getCTP().getPPr().getOutlineLvl().getVal().intValue() == 0);
            check("writer: 落盘后粗体仍在", hp.getRuns().get(0).isBold());
            check("writer: 落盘后 outline() 认得出这个标题",
                    WordTextExtractor.outline(re).contains("报告"));
        }

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

        // 改完文件仍可读
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

        // 原子写：目标不可写时原文件必须原封不动。
        // 【与计划脚本的唯一偏差，理由】计划脚本把副本放在 root/只读.docx，却用裸名 "只读.docx"
        // 发起替换；裸名一律落 workDir，引擎实际打开的是 work/只读.docx（不存在）——
        // 失败原因是 "file not found"，两条原子写断言会因此"通过"而与原子写毫无关系（实测确认）。
        // 故把副本与陷阱都放在 work/ 下（引擎真正会写的那个路径），并加两条判据堵死假绿：
        // ①错误必须来自写路径（cannot write）②拆掉陷阱后同一替换必须成功（对照）。
        java.nio.file.Path ro = work.resolve("只读.docx");
        java.nio.file.Files.copy(work.resolve("报告.docx"), ro);
        byte[] beforeBytes = java.nio.file.Files.readAllBytes(ro);
        java.nio.file.Path tmpTrap = ro.resolveSibling(ro.getFileName() + ".tmp");
        java.nio.file.Files.createDirectory(tmpTrap);   // 用目录占住 .tmp 路径让写入失败
        JavaWordEngine.Result rf = eng.execute("replace",
                map("path", "只读.docx", "find", "已改", "replace", "又改"));
        check("原子写: 失败时 ok=false", !rf.ok);
        check("原子写: 失败来自写路径而非找不到文件（防空转假绿）",
                String.valueOf(rf.json.get("error")).toLowerCase().contains("cannot write"));
        check("原子写: 原文件字节未变",
                java.util.Arrays.equals(beforeBytes, java.nio.file.Files.readAllBytes(ro)));

        // 对照：拆掉陷阱后同一替换必须成功——证明上面的失败确实由陷阱造成，不是"什么都失败"
        java.nio.file.Files.deleteIfExists(tmpTrap);
        JavaWordEngine.Result rf2 = eng.execute("replace",
                map("path", "只读.docx", "find", "已改", "replace", "又改"));
        JavaWordEngine.Result rr3 = eng.execute("read", map("path", "只读.docx"));
        check("原子写对照: 无陷阱时同一替换成功且内容已变",
                rf2.ok && rr3.ok && rr3.text.contains("又改"));

        // fill_template 带 output
        JavaWordEngine.Result rft = eng.execute("fill_template",
                map("path", "报告.docx", "data", new java.util.LinkedHashMap<String, Object>() {{
                    put("x", "y");
                }}, "output", "填好.docx"));
        check("engine: fill_template 支持 output 另存", rft.ok
                && work.resolve("填好.docx").toFile().isFile());

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

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

    /** 造一个把 ${name} 拆成多个 run 的段落（模拟真实 Word 的 run 碎片化） */
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

    static java.nio.file.Path saveTmp(org.apache.poi.xwpf.usermodel.XWPFDocument d, String name)
            throws Exception {
        java.nio.file.Path p = java.nio.file.Files.createTempDirectory("wordsf")
                .resolve(name);
        try (java.io.OutputStream o = java.nio.file.Files.newOutputStream(p)) { d.write(o); }
        return p;
    }

    /** 小号 map 字面量：map("k1", v1, "k2", v2) */
    static java.util.Map<String, Object> map(Object... kv) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static void check(String desc, boolean ok) {
        if (ok) { passed++; System.out.println("  [PASS] " + desc); }
        else    { failed++; System.out.println("  [FAIL] " + desc); }
    }
}
