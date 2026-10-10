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

    private static final org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType.Enum
            BEGIN = org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType.BEGIN;
    private static final org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType.Enum
            SEPARATE = org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType.SEPARATE;
    private static final org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType.Enum
            END = org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType.END;

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

        WordTextEditor.insertParagraph(d9, 1, "插入", null, true, false);
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
        WordTextEditor.insertParagraph(d9b, 1, "MID", null, false, false);
        check("insert_paragraph: 中间段后插位置正确",
                d9b.getParagraphs().get(2).getText().equals("MID")
                        && d9b.getParagraphs().get(3).getText().equals("C"));
        WordTextEditor.insertParagraph(d9b, d9b.getParagraphs().size() - 1, "TAIL", null, false, false);
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

        // ================= rewrite：段落不安全就**拒绝**（不再"拉平 + 清 br"）=================
        // 历史：Task 10 的缺陷是 rewrite 把 br 合成的 \n 写回 <w:t> 造成换行重复，当时的修法是
        // 「拉平（\n→空格）+ 清残留 br」，本文件也从那时起钉住了"金额：100 尾注"这个期望值。
        // N1 复审推翻了那个修法，故本块断言**整体改写**为拒绝语义，理由：
        //   ① 清 br 本身就是内容损失（段内换行被抹掉，用户没要求删换行）；
        //   ② 同一条路径对域/超链接是毁灭性的（实测模型文本落进域代码区、域结果被清空，回执 ok）；
        //   ③ rewritten 段落里既有 br 又有文本时，"拉平"只是让损失看起来小一点，本质仍是整段重造。
        // 现在的契约：跳过 + 原因点名元素 + 正文一字不动（拒绝发生在任何改动之前）。
        org.apache.poi.xwpf.usermodel.XWPFDocument dRw = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFParagraph pRw = dRw.createParagraph();
        org.apache.poi.xwpf.usermodel.XWPFRun rRw = pRw.createRun();
        rRw.setText("金额：${amount}");
        rRw.addBreak();
        org.apache.poi.xwpf.usermodel.XWPFRun rRw2 = pRw.createRun();
        rRw2.setText("尾注");
        check("rewrite 前置: 段落 text 是 br 合成的 \"金额：${amount}\\n尾注\"",
                pRw.getText().equals("金额：${amount}\n尾注"));
        WordTextEditor.ReplaceReport repRw =
                WordTextEditor.replaceInDocument(dRw, "${amount}", "100", -1, "rewrite");
        check("rewrite: 含 br 的段落被拒绝（replaced=0, skipped=1）",
                repRw.replaced == 0 && repRw.skipped == 1);
        check("rewrite: 跳过原因点名元素 w:br（不再是笼统的 hyperlink/field/break）",
                repRw.skippedReasons.size() == 1
                        && repRw.skippedReasons.get(0).contains("w:br")
                        && repRw.skippedReasons.get(0).startsWith("paragraph 0"));
        check("rewrite: 段落文本一字未改（不拉平、不清 br、换行不重复也不尾随）",
                dRw.getParagraphs().get(0).getText().equals("金额：${amount}\n尾注"));
        check("rewrite: 占位符原样保留（不是「改了一半」的半改态）",
                dRw.getParagraphs().get(0).getText().contains("${amount}"));
        check("rewrite: br 仍在（拒绝发生在改动之前）",
                dRw.getParagraphs().get(0).getRuns().stream()
                        .anyMatch(r -> r.getCTR().sizeOfBrArray() > 0));
        // 落盘重载后仍是原样：拒绝=什么都没发生，落盘的 XML 也得是原样
        java.nio.file.Path pRwf = saveTmp(dRw, "rewrite_refused.docx");
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(pRwf);
             org.apache.poi.xwpf.usermodel.XWPFDocument re =
                     new org.apache.poi.xwpf.usermodel.XWPFDocument(in)) {
            check("rewrite: 落盘重载后段落仍是原样（拒绝不是内存态假象）",
                    re.getParagraphs().get(0).getText().equals("金额：${amount}\n尾注")
                            && re.getParagraphs().get(0).getRuns().stream()
                                    .anyMatch(r -> r.getCTR().sizeOfBrArray() > 0));
        }

        // ================= Critical 回归：setText(v,0) 只写 t[0] =================
        // 一个 run 挂多个 <w:t>（Word 在 softHyphen/lastRenderedPageBreak 等处会这样切）
        org.apache.poi.xwpf.usermodel.XWPFDocument dT = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFParagraph pT = dT.createParagraph();
        org.apache.poi.xwpf.usermodel.XWPFRun rT = pT.createRun();
        rT.setText("abc");
        rT.getCTR().addNewT().setStringValue("def");
        check("多<t>: 前置条件——run.text() 确实是拼接结果", "abcdef".equals(rT.text()));
        int nT = WordTextEditor.replaceInParagraph(pT, "abc", "X");
        check("多<t>: 替换计数 = 1", nT == 1);
        check("多<t>: 结果不是 'Xdefdef'（后缀不得重复）", "Xdef".equals(pT.getText()));
        check("多<t>: run 只余一个 <w:t>", rT.getCTR().sizeOfTArray() == 1);

        org.apache.poi.xwpf.usermodel.XWPFDocument dT2 = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFParagraph pT2 = dT2.createParagraph();
        org.apache.poi.xwpf.usermodel.XWPFRun rT2 = pT2.createRun();
        rT2.setText("AAA");
        rT2.getCTR().addNewT().setStringValue("BBB");
        WordTextEditor.setParagraphText(dT2, 0, "NEW");
        check("多<t>: set_paragraph 不得残留 'NEWBBB'", "NEW".equals(pT2.getText()));

        org.apache.poi.xwpf.usermodel.XWPFDocument dT3 = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFTable tT3 = dT3.createTable(1, 1);
        org.apache.poi.xwpf.usermodel.XWPFTableCell cT3 = tT3.getRow(0).getCell(0);
        org.apache.poi.xwpf.usermodel.XWPFRun rT3 = cT3.getParagraphs().get(0).createRun();
        rT3.setText("旧");
        rT3.getCTR().addNewT().setStringValue("内容");
        WordTextEditor.setTableCell(dT3, 0, 0, 0, "新");
        check("多<t>: set_table_cell 不得残留 '新内容'", "新".equals(cT3.getText()));

        // C1 死循环：多<t> 且 <t> 里就有 find 时必须能终止
        //（不设超时——修复前这条会挂住；修复后必须瞬间返回。若你的自测卡住，
        //  说明修复没生效，直接 Ctrl-C 并报告）
        org.apache.poi.xwpf.usermodel.XWPFDocument dL = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFParagraph pL = dL.createParagraph();
        org.apache.poi.xwpf.usermodel.XWPFRun rL = pL.createRun();
        rL.setText("订单号：");
        rL.getCTR().addNewT().setStringValue("${orderNo}");
        int nL = WordTextEditor.replaceInParagraph(pL, "${orderNo}", "A123");
        check("死循环回归: 返回而非挂死", nL == 1);
        check("死循环回归: 文本正确", "订单号：A123".equals(pL.getText()));

        // rewrite 模式在多<t> 段落下不得复制内容
        org.apache.poi.xwpf.usermodel.XWPFDocument dW = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFParagraph pW = dW.createParagraph();
        org.apache.poi.xwpf.usermodel.XWPFRun rW = pW.createRun();
        rW.setText("前缀 ");
        rW.getCTR().addNewT().setStringValue("${k} 后缀");
        WordTextEditor.replaceInDocument(dW, "${k}", "V", -1, "rewrite");
        check("rewrite 回归: 不复制内容", "前缀 V 后缀".equals(dW.getParagraphs().get(0).getText()));

        // ================= N2 真值表：不安全判定 = "text() 会为它合成/变换字符" =================
        // 判据来自实测而非直觉：逐条对照 POI 5.5.1 的 XWPFRun.text()/_getText()（javap -c 反编译
        // D:\repository\org\apache\poi\poi-ooxml\5.5.1\poi-ooxml-5.5.1.jar），并逐元素构造 run 跑过。
        // 关键实测：run 里 [rPr, X, t("X")] 时 X 会不会给 text() 添字——
        //   br/cr → '\n'；tab/ptab → '\t'；**noBreakHyphen → U+2011（反直觉！）**；
        //   delText → 追加内容；footnoteReference → "[footnoteRef:N]"；ruby → 注音基字；
        //   而 lastRenderedPageBreak/softHyphen/sym/drawing/pict/object 一个字符都不添。
        // 旧白名单"只许 w:t 与 w:rPr"把最后一类也判成危险，代价是整份文档大面积改不动
        // （复审实测 186 篇语料：跳过段落 263→287，仅 lastRenderedPageBreak 就占 24 段/5 篇）。
        check("真值表: w:br 不安全（text() 合成 '\\n'）",
                "w:br".equals(WordTextEditor.unsafeRunReason(
                        probeRun(c -> c.addNewBr()))));
        check("真值表: w:cr 不安全（CTEmpty 分支同样合成 '\\n'）",
                "w:cr".equals(WordTextEditor.unsafeRunReason(
                        probeRun(c -> c.addNewCr()))));
        check("真值表: w:tab 不安全（合成 '\\t'）",
                "w:tab".equals(WordTextEditor.unsafeRunReason(
                        probeRun(c -> c.addNewTab()))));
        check("真值表: w:ptab 不安全（CTPTab 分支合成 '\\t'）",
                "w:ptab".equals(WordTextEditor.unsafeRunReason(
                        probeRun(c -> c.addNewPtab()))));
        check("真值表: w:noBreakHyphen 不安全（实测 text() = \"‑X\"，多出一个 U+2011）",
                "w:noBreakHyphen".equals(WordTextEditor.unsafeRunReason(
                        probeRun(c -> c.addNewNoBreakHyphen()))));
        check("真值表: w:delText 不安全（CTText 分支只排除 instrText/delInstrText，delText 会追加）",
                "w:delText".equals(WordTextEditor.unsafeRunReason(
                        probeRun(c -> { c.addNewDelText().setStringValue("DEL"); return null; }))));
        check("真值表: w:footnoteReference 不安全（合成 '[footnoteRef:N]'）",
                "w:footnoteReference".equals(WordTextEditor.unsafeRunReason(
                        probeRun(c -> c.addNewFootnoteReference()))));
        check("真值表: w:fldChar 不安全（域结构必拒；BEGIN+ffData 还会合成 '|X|'）",
                "w:fldChar".equals(WordTextEditor.unsafeRunReason(
                        probeRun(c -> c.addNewFldChar()))));
        check("真值表: w:instrText 不安全（域代码：整段重写会把新文本写进域代码区）",
                "w:instrText".equals(WordTextEditor.unsafeRunReason(
                        probeRun(c -> { c.addNewInstrText().setStringValue(" HYPERLINK \"x\" "); return null; }))));
        check("真值表: w:ruby 不安全（handleRuby 取注音，顶层 <w:t> 却是空）",
                "w:ruby".equals(WordTextEditor.unsafeRunReason(
                        probeRun(c -> c.addNewRuby()))));
        check("真值表: caps 格式不安全（text() 会 toUpperCase，写回把大写烘进正文 = 不互逆）",
                "w:caps".equals(WordTextEditor.unsafeRunReason(
                        probeRun(null, r -> r.setCapitalized(true)))));
        // 安全侧：这一组是 N2 的立论所在，谁把它们改回"危险"，语料可用性立刻塌回去
        check("真值表: w:lastRenderedPageBreak 安全（Windows Word 每个分页处都会插，text() 零贡献）",
                WordTextEditor.unsafeRunReason(probeRun(c -> c.addNewLastRenderedPageBreak())) == null);
        check("真值表: w:softHyphen 安全（实测零贡献、写回 round-trip 精确）",
                WordTextEditor.unsafeRunReason(probeRun(c -> c.addNewSoftHyphen())) == null);
        check("真值表: w:drawing 安全（内联图片原位保留，text() 零贡献）",
                WordTextEditor.unsafeRunReason(probeRun(c -> c.addNewDrawing())) == null);
        check("真值表: w:sym 安全（text() 零贡献）",
                WordTextEditor.unsafeRunReason(probeRun(c -> c.addNewSym())) == null);

        // N2 核心回归：真实形态 [rPr, lastRenderedPageBreak, t] 既能改、又 round-trip 精确
        org.apache.poi.xwpf.usermodel.XWPFDocument dLR = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFParagraph pLR = dLR.createParagraph();
        org.apache.poi.xwpf.usermodel.XWPFRun rLR = pLR.createRun();
        rLR.setBold(true);
        rLR.getCTR().addNewLastRenderedPageBreak();
        rLR.getCTR().addNewT().setStringValue("订单号：${orderNo}");
        check("lastRenderedPageBreak: 段落不再被跳过（旧白名单下这里返回 -2）",
                WordTextEditor.replaceInParagraph(pLR, "${orderNo}", "A123") == 1);
        check("lastRenderedPageBreak: 文本 round-trip 正确（元素不给 text() 添字）",
                "订单号：A123".equals(pLR.getText()));
        check("lastRenderedPageBreak: 元素与格式原位保住（不是被清掉）",
                rLR.getCTR().sizeOfLastRenderedPageBreakArray() == 1 && rLR.isBold()
                        && rLR.getCTR().sizeOfTArray() == 1);
        check("lastRenderedPageBreak: 落盘重载后仍如此",
                roundTripText(dLR, "lrpb.docx").equals("订单号：A123"));

        // 反例：普通 run 绝不能被误判（否则整个技能形同虚设）
        org.apache.poi.xwpf.usermodel.XWPFParagraph pOK = newDoc().createParagraph();
        org.apache.poi.xwpf.usermodel.XWPFRun rOK = pOK.createRun();
        rOK.setBold(true); rOK.setFontSize(14); rOK.setText("普通 run");
        check("不安全判定: 普通 run 不被误判", !WordTextEditor.isUnsafeRun(rOK));
        org.apache.poi.xwpf.usermodel.XWPFParagraph pOK2 = newDoc().createParagraph();
        org.apache.poi.xwpf.usermodel.XWPFRun rOK2 = pOK2.createRun();
        rOK2.setText("两个 t");
        rOK2.getCTR().addNewT().setStringValue("也算安全");
        check("不安全判定: 多 <w:t> 的 run 是安全的（已能正确写回）",
                !WordTextEditor.isUnsafeRun(rOK2));

        // ================= N1 回归：整段重写必须拒绝，不许把域/超链接改坏 =================
        // 复审实测的损伤形态（修复前）：带 HYPERLINK 域的段落上 set_paragraph 把模型文本写进了
        // **域代码区**（fldChar begin 那个 run 里多了 <w:t>NEW-TEXT-FROM-MODEL</w:t>）、并清空了
        // 域结果；回执 {"ok":true}、read 还报 "[0] [Normal] NEW-TEXT-FROM-MODEL"，而 Word 显示的是
        // 一个坏掉的域。set_table_cell / add_table_row / replace 的 rewrite 分支同源。
        org.apache.poi.xwpf.usermodel.XWPFDocument dFld = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFParagraph pFld = dFld.createParagraph();
        pFld.createRun().getCTR().addNewFldChar().setFldCharType(BEGIN);
        pFld.createRun().getCTR().addNewInstrText()
                .setStringValue(" HYPERLINK \"https://example.com/x\" ");
        pFld.createRun().getCTR().addNewFldChar().setFldCharType(SEPARATE);
        pFld.createRun().setText("click here");
        pFld.createRun().getCTR().addNewFldChar().setFldCharType(END);
        check("N1 前置: 域段落 text = \"click here\"（域代码/域标志不进文本）",
                "click here".equals(pFld.getText()));
        check("N1 前置: 段落整段重写判定为不安全且点名 w:fldChar",
                "w:fldChar".equals(WordTextEditor.paragraphUnsafeReason(pFld))
                        && WordTextEditor.paragraphHasUnsafeRuns(pFld));
        String fldXmlBefore = pFld.getCTP().xmlText();
        String fldErr = null;
        try { WordTextEditor.setParagraphText(dFld, 0, "NEW-TEXT-FROM-MODEL"); }
        catch (IllegalStateException e) { fldErr = e.getMessage(); }
        check("N1: set_paragraph 在含域的段落上拒绝（抛 IllegalStateException）", fldErr != null);
        check("N1: 报错点名元素并给出可执行的下一步（replace / 手工删域）",
                fldErr != null && fldErr.contains("w:fldChar")
                        && fldErr.contains("cannot be fully rewritten safely")
                        && fldErr.contains("edit it with replace"));
        check("N1: 段落 XML 逐字节未变（拒绝发生在任何改动之前，不是半改）",
                fldXmlBefore.equals(pFld.getCTP().xmlText()));
        check("N1: 文本仍是 click here（模型文本没落进域代码区）",
                "click here".equals(pFld.getText()));
        // rewrite 模式同源：只记 skipped + 原因，不把整个操作丢掉、也不动段落
        WordTextEditor.ReplaceReport fldRep =
                WordTextEditor.replaceInDocument(dFld, "click here", "X", -1, "rewrite");
        check("N1: rewrite 模式 skipped=1 且原因点名 w:fldChar（段落号 + 元素）",
                fldRep.replaced == 0 && fldRep.skipped == 1
                        && fldRep.skippedReasons.size() == 1
                        && fldRep.skippedReasons.get(0).startsWith("paragraph 0")
                        && fldRep.skippedReasons.get(0).contains("w:fldChar"));
        check("N1: rewrite 拒绝后段落未被破坏", "click here".equals(pFld.getText()));
        // 对照：preserve（默认）模式只动命中区间里的 <w:t> 那个 run，同一段落可以安全替换
        // ——这正是修好后的技能描述给模型的指路（"用 replace，别硬来"）
        check("N1 对照: preserve 模式可安全替换域结果文本",
                WordTextEditor.replaceInParagraph(pFld, "click here", "点这里") == 1
                        && "点这里".equals(pFld.getText()));
        check("N1 对照: 替换后域结构仍是 3 个 fldChar + 1 段 instrText（没被顺手清掉）",
                countFldChar(pFld) == 3 && countInstrText(pFld) == 1);

        // N1 同族（内联内容控件 w:sdt）：getRuns() 看不到、getText()/Word 却会显示，
        // 整段重写只会把新文本与旧内容粘在一起——实测后 getText() = "WHOLE-NEWSECRET-INVISIBLE"。
        // 注：sdt 必须**落盘重载后**才进 POI 的 iruns（内存里刚手工加进去的，POI 的列表是旧的）
        org.apache.poi.xwpf.usermodel.XWPFDocument dSdt = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFParagraph pSdt = dSdt.createParagraph();
        pSdt.createRun().setText("前缀");
        sdtContent(pSdt.getCTP()).addNewR().addNewT().setStringValue("SECRET-INVISIBLE");
        java.nio.file.Path pSdtF = saveTmp(dSdt, "sdt.docx");
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(pSdtF);
             org.apache.poi.xwpf.usermodel.XWPFDocument re =
                     new org.apache.poi.xwpf.usermodel.XWPFDocument(in)) {
            org.apache.poi.xwpf.usermodel.XWPFParagraph rpSdt = re.getParagraphs().get(0);
            check("N1 前置: sdt 内容对 getText() 可见（getRuns() 却只有 1 个）",
                    "前缀SECRET-INVISIBLE".equals(rpSdt.getText())
                            && rpSdt.getRuns().size() == 1);
            String sdtErr = null;
            try { WordTextEditor.setParagraphText(re, 0, "WHOLE-NEW"); }
            catch (IllegalStateException e) { sdtErr = e.getMessage(); }
            check("N1: 含内联内容控件的段落整段重写被拒绝（点名 w:sdt）",
                    sdtErr != null && sdtErr.contains("w:sdt"));
            check("N1: 拒绝后新旧内容没有粘连（仍是 前缀SECRET-INVISIBLE）",
                    "前缀SECRET-INVISIBLE".equals(rpSdt.getText()));
        }

        // N1: 单元格路径——拒绝时单元格必须**原封不动**，不能半清（当前实现先改首段再删多余段）
        org.apache.poi.xwpf.usermodel.XWPFDocument dCell = newDoc();
        org.apache.poi.xwpf.usermodel.XWPFTable tCell = dCell.createTable(1, 1);
        org.apache.poi.xwpf.usermodel.XWPFTableCell cCell = tCell.getRow(0).getCell(0);
        org.apache.poi.xwpf.usermodel.XWPFRun cRun = cCell.getParagraphs().get(0).createRun();
        cRun.setText("第一段");
        cRun.addBreak();                       // 首段有 br → 整段重写不安全
        cCell.addParagraph().createRun().setText("第二段");
        int cellParas = cCell.getParagraphs().size();
        check("N1 前置: 单元格有 2 段、首段含 br", cellParas == 2
                && cCell.getParagraphs().get(0).getRuns().get(0).getCTR().sizeOfBrArray() > 0);
        boolean cellRefused = false;
        try { WordTextEditor.setTableCell(dCell, 0, 0, 0, "新文本"); }
        catch (IllegalStateException e) { cellRefused = true; }
        check("N1: set_table_cell 在含 br 的单元格上拒绝", cellRefused);
        check("N1: 拒绝时单元格一段未删（两段都在，没走到删除循环）",
                cCell.getParagraphs().size() == cellParas);
        check("N1: 首段文本与 br 都原样（不是半清）",
                cCell.getParagraphs().get(0).getText().startsWith("第一段")
                        && cCell.getParagraphs().get(0).getRuns().get(0).getCTR().sizeOfBrArray() > 0);
        check("N1: 第二段原样（没被当作多余段落删掉）",
                "第二段".equals(cCell.getParagraphs().get(1).getText()));

        // ================= WordMarkdownWriter =================
        org.apache.poi.xwpf.usermodel.XWPFDocument d11 = newDoc();
        WordMarkdownWriter.writeBlocks(d11, WordMarkdown.parse(
                "# 报告\n\n正文一段\n\n- 甲\n- 乙\n\n| 月 | 值 |\n| 1 | 100 |"), true);
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
        WordMarkdownWriter.writeBlocks(d12, WordMarkdown.parse("这**很粗**啊"), true);
        org.apache.poi.xwpf.usermodel.XWPFParagraph p12 = d12.getParagraphs().get(0);
        check("writer: 行内粗体拆成多 run", p12.getRuns().size() == 3);
        check("writer: 中间 run 是粗体", p12.getRuns().get(1).isBold()
                && p12.getRuns().get(1).text().equals("很粗"));
        check("writer: 首尾 run 不粗", !p12.getRuns().get(0).isBold()
                && !p12.getRuns().get(2).isBold());

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
        WordMarkdownWriter.writeBlocks(dPlainW, WordMarkdown.parse(
                "# 报告\n\n正文一段\n\n## 一、背景\n\n- 甲\n\n| 月 | 值 |\n| 1 | 100 |"), false);
        check("排版: plain 下 H1 无 jc（plain = 什么都不加）",
                !dPlainW.getParagraphs().get(0).isAlignmentSet());
        check("排版: plain 下正文无缩进",
                dPlainW.getParagraphs().get(1).getIndentationFirstLine() == -1);
        check("排版: plain H1 仍是真标题（pStyle/outlineLvl/粗体都在——plain 是「不加」不是「清除」）",
                "Heading1".equals(dPlainW.getParagraphs().get(0).getStyle())
                        && dPlainW.getParagraphs().get(0).getCTP().getPPr() != null
                        && dPlainW.getParagraphs().get(0).getCTP().getPPr().getOutlineLvl() != null
                        && dPlainW.getParagraphs().get(0).getRuns().get(0).isBold());
        check("排版: plain 下 H2 也不居中", !dPlainW.getParagraphs().get(2).isAlignmentSet());
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

        // ================= 排版: 引擎 create/append 真的把开关传到 writer（评审变异守）=================
        // 直接调 writeBlocks 的断言证明不了"Config.chineseLayout 接线"——把 doCreate/doAppend 的
        // 实参改成 false 也全绿（评审变异实测）。这里从 execute 走真链路：两处接线任一写反即红。
        JavaWordEngine.Config cfgLayE = new JavaWordEngine.Config();
        cfgLayE.allowedRoot = root;
        cfgLayE.workDir = work;                     // 默认 chineseLayout = true
        JavaWordEngine engLayE = new JavaWordEngine(cfgLayE);
        check("排版接线(引擎): chinese create ok",
                engLayE.execute("create", map("path", "排版引擎.docx",
                        "content", "# 报告\n\n正文一段")).ok);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("排版引擎.docx"))) {
            check("排版接线(引擎): chinese create → H1 居中 + 正文缩进（doCreate 实参没被写反）",
                    d.getParagraphs().get(0).getAlignment()
                            == org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER
                            && d.getParagraphs().get(1).getIndentationFirstLine() == 420);
        }
        check("排版接线(引擎): chinese append ok",
                engLayE.execute("append", map("path", "排版引擎.docx",
                        "content", "追加的正文段")).ok);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("排版引擎.docx"))) {
            check("排版接线(引擎): chinese append → 新段有缩进（doAppend 实参没被写反）",
                    d.getParagraphs().get(2).getIndentationFirstLine() == 420);
        }
        JavaWordEngine.Config cfgLayE2 = new JavaWordEngine.Config();
        cfgLayE2.allowedRoot = root;
        cfgLayE2.workDir = work;
        cfgLayE2.chineseLayout = false;
        JavaWordEngine engLayE2 = new JavaWordEngine(cfgLayE2);
        check("排版接线(引擎): plain create ok",
                engLayE2.execute("create", map("path", "排版引擎plain.docx",
                        "content", "# 报告\n\n正文一段")).ok);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("排版引擎plain.docx"))) {
            check("排版接线(引擎): plain create → 无 jc 无缩进",
                    !d.getParagraphs().get(0).isAlignmentSet()
                            && d.getParagraphs().get(1).getIndentationFirstLine() == -1);
        }
        // append 的硬边界（spec §3）：只给新段落加格式，已有段落一律不动
        check("排版接线(引擎): 已有 chinese 文档上 plain append ok",
                engLayE2.execute("append", map("path", "排版引擎.docx",
                        "content", "不再缩进的追加段")).ok);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("排版引擎.docx"))) {
            check("排版接线(引擎): append 只影响新段——旧 H1 仍居中、旧正文仍缩进、chinese 追加段缩进、plain 追加段无缩进",
                    d.getParagraphs().get(0).getAlignment()
                            == org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER
                            && d.getParagraphs().get(1).getIndentationFirstLine() == 420
                            && d.getParagraphs().get(2).getIndentationFirstLine() == 420
                            && d.getParagraphs().get(3).getIndentationFirstLine() == -1);
        }

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

        // ================= F2 回归：漏传 text 不得静默清空 =================
        // 缺陷实测（修复前）：set_paragraph(index=1) 不带 text → ok=true，段落变 ""（内容被毁）；
        // set_table_cell 同款把单元格写空。根因是 String.valueOf(getOrDefault("text",""))——
        // 把"模型漏参数"和"显式清空"当成同一件事。漏参数必须报错，显式空串仍合法。
        JavaWordEngine.Result cF2 = eng.execute("create",
                map("path", "漏参.docx", "content", "# 标题\n\n正文甲\n\n正文乙"));
        check("F2 前置: 测试文档建好（3 段）", cF2.ok);
        String f2BeforeP = eng.execute("read", map("path", "漏参.docx")).text;
        check("F2 前置: 第 1 段读得到正文甲", f2BeforeP.contains("正文甲"));

        JavaWordEngine.Result missP = eng.execute("set_paragraph",
                map("path", "漏参.docx", "index", 1));            // 故意不带 text
        check("F2: set_paragraph 缺 text → ok=false", !missP.ok);
        check("F2: 错误文案说明 text 必填（模型读得到就能自纠）",
                String.valueOf(missP.json.get("error")).contains("text is required"));
        String f2AfterP = eng.execute("read", map("path", "漏参.docx")).text;
        check("F2: 缺 text 时**整篇文本逐字未变**（段落没被清空）", f2BeforeP.equals(f2AfterP));
        check("F2: 段落里仍有正文甲（不是空串）", f2AfterP.contains("正文甲"));

        // 反面守：显式 text="" 是合法意图（清空段落），不能被同一个守卫误伤
        JavaWordEngine.Result emptyP = eng.execute("set_paragraph",
                map("path", "漏参.docx", "index", 1, "text", ""));
        check("F2: 显式 text=\"\" 仍允许（清空是合法操作）", emptyP.ok);
        check("F2: 显式空串确实把段落清空了（守卫只挡「缺 key」）",
                !eng.execute("read", map("path", "漏参.docx")).text.contains("正文甲"));

        JavaWordEngine.Result cF2t = eng.execute("create",
                map("path", "漏参表.docx", "content", "| A | B |\n| C | D |"));
        check("F2 前置: 带表格文档建好", cF2t.ok);
        String cellBeforeF2;
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("漏参表.docx"))) {
            cellBeforeF2 = d.getTables().get(0).getRow(0).getCell(1).getText();
        }
        JavaWordEngine.Result missC = eng.execute("set_table_cell",
                map("path", "漏参表.docx", "table", 0, "row", 0, "col", 1));   // 故意不带 text
        check("F2: set_table_cell 缺 text → ok=false", !missC.ok);
        String cellAfterF2;
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("漏参表.docx"))) {
            cellAfterF2 = d.getTables().get(0).getRow(0).getCell(1).getText();
        }
        check("F2: 单元格值原样保留（\"" + cellBeforeF2 + "\" → \"" + cellAfterF2 + "\"，没被写空）",
                "B".equals(cellBeforeF2) && cellBeforeF2.equals(cellAfterF2));

        // ================= F3 回归：Gson Double 不得写成 100.0 =================
        // 真链路：provider 用 gson.fromJson(argsStr, Map.class) 解析工具参数，JSON 整数一律变
        // Double。自测若手搓 String 值就永远看不见这个坑，故这里照真链路造 Double。
        JavaWordEngine.Result cF3 = eng.execute("create",
                map("path", "数字.docx", "content", "金额：${amount} 元\n\n数量：${qty}\n\n费率：${rate}"));
        check("F3 前置: 模板文档建好", cF3.ok);
        java.util.LinkedHashMap<String, Object> numData = new java.util.LinkedHashMap<>();
        numData.put("amount", 100.0d);      // JSON 100 经 gson.fromJson(Map.class) 就是这个
        numData.put("qty", 3.0d);
        numData.put("rate", 2.5d);
        JavaWordEngine.Result rf3 = eng.execute("fill_template",
                map("path", "数字.docx", "data", numData));
        check("F3: fill_template 成功", rf3.ok);
        String gotF3 = eng.execute("read", map("path", "数字.docx")).text;
        check("F3: 整值 100.0d 写成 100 而非 100.0", gotF3.contains("金额：100 元"));
        // 注意不能只查 contains("数量：3")——"数量：3.0" 里也含这个子串，那样断言在缺陷态照样绿。
        // 必须整行精确比对，才真的看得见尾部那个 ".0"。
        String[] f3Lines = gotF3.split("\n", -1);
        check("F3: 第 1 段整行 = 「[1] [Normal] 数量：3」（不是 数量：3.0）",
                f3Lines.length > 1 && f3Lines[1].equals("[1] [Normal] 数量：3"));
        check("F3: 真小数 2.5d 整行 = 「[2] [Normal] 费率：2.5」（不失精度也不拖尾零）",
                f3Lines.length > 2 && f3Lines[2].equals("[2] [Normal] 费率：2.5"));
        check("F3: 文档里没有任何 \".0\" 脏串",
                !gotF3.contains("100.0") && !gotF3.contains("3.0") && !gotF3.contains("2.50"));

        JavaWordEngine.Result cF3t = eng.execute("create",
                map("path", "数字表.docx", "content", "| 名称 | 数量 |\n| 甲 | 0 |"));
        check("F3 前置: 表格文档建好", cF3t.ok);
        java.util.List<Object> rowVals = new java.util.ArrayList<>();
        rowVals.add("乙");
        rowVals.add(100.0d);                // List 里的 Double（provider 解析 JSON 数组的结果）
        JavaWordEngine.Result rt3 = eng.execute("add_table_row",
                map("path", "数字表.docx", "table", 0, "values", rowVals));
        check("F3: add_table_row 成功", rt3.ok);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("数字表.docx"))) {
            org.apache.poi.xwpf.usermodel.XWPFTable t = d.getTables().get(0);
            int lastRow = t.getRows().size() - 1;
            check("F3: 新行数 = 3", t.getRows().size() == 3);
            check("F3: 表格单元格里的 100.0d 写成 \"100\"（不是 \"100.0\"）",
                    "100".equals(t.getRow(lastRow).getCell(1).getText()));
            check("F3: 同行的字符串值照常写入", "乙".equals(t.getRow(lastRow).getCell(0).getText()));
        }

        // ================= N1 端到端：拒绝必须经引擎翻成 JSON 错误，而不是 ok=true 的坏域 ==========
        java.nio.file.Path fldPath = work.resolve("域.docx");
        try (org.apache.poi.xwpf.usermodel.XWPFDocument fd = newDoc()) {
            org.apache.poi.xwpf.usermodel.XWPFParagraph fp = fd.createParagraph();
            fp.createRun().getCTR().addNewFldChar().setFldCharType(BEGIN);
            fp.createRun().getCTR().addNewInstrText().setStringValue(" HYPERLINK \"x\" ");
            fp.createRun().getCTR().addNewFldChar().setFldCharType(SEPARATE);
            fp.createRun().setText("点我");
            fp.createRun().getCTR().addNewFldChar().setFldCharType(END);
            try (java.io.OutputStream o = java.nio.file.Files.newOutputStream(fldPath)) { fd.write(o); }
        }
        byte[] fldBytesBefore = java.nio.file.Files.readAllBytes(fldPath);
        JavaWordEngine.Result n1Engine = eng.execute("set_paragraph",
                map("path", "域.docx", "index", 0, "text", "NEW"));
        check("N1 端到端: 含域的文档 set_paragraph → ok=false", !n1Engine.ok);
        check("N1 端到端: 错误文案含安全说明与元素名（模型能自纠）",
                String.valueOf(n1Engine.json.get("error")).contains("cannot be fully rewritten safely")
                        && String.valueOf(n1Engine.json.get("error")).contains("w:fldChar"));
        check("N1 端到端: 文件字节未变（拒绝后什么都没落盘）",
                java.util.Arrays.equals(fldBytesBefore, java.nio.file.Files.readAllBytes(fldPath)));
        check("N1 端到端: 同一文件用 replace（preserve）能改域结果",
                eng.execute("replace", map("path", "域.docx", "find", "点我", "replace", "点这里")).ok
                        && eng.execute("read", map("path", "域.docx")).text.contains("点这里"));

        // ================= N3 回归：text / replace 也要过 toDocString（F3 只补了一半） =================
        // F3 当时只把 toDocString 接到了 fill_template 与 add_table_row；requiredText（set_paragraph /
        // insert_paragraph / set_table_cell）与 doReplace 的 replace 仍是 String.valueOf——
        // 实测 set_paragraph(text=100.0d) 往文档里写出 "100.0"，replace(replace=2.0d) 写出 "2.0"。
        // 判据一律用**整行比对**，防 "100.0" 里也含 "100" 的假绿。
        JavaWordEngine.Result cN3 = eng.execute("create",
                map("path", "数字文本.docx", "content", "占位"));
        check("N3 前置: 文本文档建好", cN3.ok);
        JavaWordEngine.Result n3a = eng.execute("set_paragraph",
                map("path", "数字文本.docx", "index", 0, "text", 100.0d));
        String n3Read = eng.execute("read", map("path", "数字文本.docx")).text;
        check("N3: set_paragraph(text=100.0d) 写成 \"100\"", n3a.ok
                && n3Read.split("\n", -1)[0].equals("[0] [Normal] 100"));
        JavaWordEngine.Result n3b = eng.execute("replace",
                map("path", "数字文本.docx", "find", "100", "replace", 2.0d));
        check("N3: replace(replace=2.0d) 写成 \"2\"（不是 2.0）", n3b.ok
                && eng.execute("read", map("path", "数字文本.docx")).text
                        .split("\n", -1)[0].equals("[0] [Normal] 2"));
        JavaWordEngine.Result n3c = eng.execute("insert_paragraph",
                map("path", "数字文本.docx", "index", 0, "text", 7.0d));
        check("N3: insert_paragraph(text=7.0d) 写成 \"7\"", n3c.ok
                && eng.execute("read", map("path", "数字文本.docx")).text
                        .split("\n", -1)[0].equals("[0] [Normal] 7"));
        check("N3 前置: 带表格文档建好（复用 F3 的数字表）",
                eng.execute("create", map("path", "数字格.docx", "content", "| A |")).ok);
        JavaWordEngine.Result n3d = eng.execute("set_table_cell",
                map("path", "数字格.docx", "table", 0, "row", 0, "col", 0, "text", 9.0d));
        String n3Cell = eng.execute("read", map("path", "数字格.docx")).text;
        check("N3: set_table_cell(text=9.0d) 写成 \"9\" 且文档里没有 \".0\" 脏串",
                n3d.ok && n3Cell.contains("9") && !n3Cell.contains("9.0"));

        // ================= N4 回归：replace 缺 replace 参数不得静默删除 =================
        // 缺陷实测（修复前）：doReplace 用 getOrDefault("replace","")，于是 replace(find="DELETEME")
        // 回执 {"ok":true,"replaced":1} 而匹配的文字已被删空——与 F2（漏传 text 清空段落）同一类：
        // 把"模型漏参数"和"显式删掉这段文字"当成同一件事。
        JavaWordEngine.Result cN4 = eng.execute("create",
                map("path", "缺参r.docx", "content", "DELETEME 后面的字"));
        check("N4 前置: 文档建好", cN4.ok);
        String n4Before = eng.execute("read", map("path", "缺参r.docx")).text;
        JavaWordEngine.Result n4 = eng.execute("replace",
                map("path", "缺参r.docx", "find", "DELETEME"));      // 故意不带 replace
        check("N4: 缺 replace 参数 → ok=false", !n4.ok);
        check("N4: 报错说明 replace 必填（模型读得到就能自纠）",
                String.valueOf(n4.json.get("error")).contains("replace is required"));
        check("N4: 匹配处一个字没少（不再静默删除整段匹配）",
                n4Before.equals(eng.execute("read", map("path", "缺参r.docx")).text));
        check("N4: 文档里 DELETEME 仍在",
                eng.execute("read", map("path", "缺参r.docx")).text.contains("DELETEME"));
        // 反面守：显式 replace="" 是合法意图（删除匹配），不能被同一个守卫误伤
        JavaWordEngine.Result n4b = eng.execute("replace",
                map("path", "缺参r.docx", "find", "DELETEME", "replace", ""));
        check("N4: 显式 replace=\"\" 仍然允许（删除是合法操作）", n4b.ok
                && Integer.valueOf(1).equals(n4b.json.get("replaced"))
                && !eng.execute("read", map("path", "缺参r.docx")).text.contains("DELETEME"));

        // ================= F5 回归：insert_paragraph 标题不得悬空 =================
        // 缺陷实测（修复前）：style="Heading1" 只做了 setStyle，而本技能 create 出来的文档没有
        // styles part → pStyle 是悬空引用。对照 markdown writer 的标题（三重设定）：
        // pStyle=Heading1 outlineLvl=0 bold=true vs 修复前的 outlineLvl=null bold=false。
        JavaWordEngine.Result cF5 = eng.execute("create",
                map("path", "标题插入.docx", "content", "正文一段\n\n正文二段"));
        check("F5 前置: 文档建好（2 段）", cF5.ok);
        JavaWordEngine.Result ri5 = eng.execute("insert_paragraph",
                map("path", "标题插入.docx", "index", 0, "text", "新标题", "style", "Heading1"));
        check("F5: insert_paragraph 成功", ri5.ok);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("标题插入.docx"))) {
            org.apache.poi.xwpf.usermodel.XWPFParagraph hp = d.getParagraphs().get(0);
            check("F5: 新段落文本与 pStyle 正确",
                    "新标题".equals(hp.getText()) && "Heading1".equals(hp.getStyle()));
            check("F5: 标题写了 outlineLvl=0（pStyle 不再是悬空引用）",
                    hp.getCTP().getPPr() != null
                            && hp.getCTP().getPPr().getOutlineLvl() != null
                            && hp.getCTP().getPPr().getOutlineLvl().getVal().intValue() == 0);
            check("F5: 标题 run 是粗体（直接格式兜底，与 writer 一致）",
                    !hp.getRuns().isEmpty() && hp.getRuns().get(0).isBold());
            check("F5: 标题 run 字号 = 20（headingFontSize(1)）",
                    hp.getRuns().get(0).getFontSize() == WordMarkdownWriter.headingFontSize(1));
            check("F5: outline() 认得出插入的标题（读侧与写侧一致）",
                    WordTextExtractor.outline(d).contains("新标题"));
            check("F5: 正文段未被波及", "正文一段".equals(d.getParagraphs().get(1).getText()));
        }
        // 级别从串里解析：Heading2 → outlineLvl=1，字号也要跟着走
        JavaWordEngine.Result ri5b = eng.execute("insert_paragraph",
                map("path", "标题插入.docx", "index", 0, "text", "二级", "style", "Heading2"));
        check("F5: Heading2 插入成功", ri5b.ok);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("标题插入.docx"))) {
            org.apache.poi.xwpf.usermodel.XWPFParagraph hp2 = d.getParagraphs().get(0);
            // 判据要 null 安全：缺陷态下 getOutlineLvl() 就是 null，直接取值会抛 NPE 把整个自测打断，
            // 那样红的是"崩"，不是"这条断言失败"——红态也得看得懂
            boolean hp2Outline1 = hp2.getCTP().getPPr() != null
                    && hp2.getCTP().getPPr().getOutlineLvl() != null
                    && hp2.getCTP().getPPr().getOutlineLvl().getVal().intValue() == 1;
            check("F5: Heading2 → outlineLvl=1 且粗体且字号 16",
                    hp2Outline1 && hp2.getRuns().get(0).isBold()
                            && hp2.getRuns().get(0).getFontSize() == WordMarkdownWriter.headingFontSize(2));
        }
        // 反面守：非 HeadingN 的样式仍走原路（不能被标题分支吞掉）
        JavaWordEngine.Result ri5c = eng.execute("insert_paragraph",
                map("path", "标题插入.docx", "index", 0, "text", "列表项", "style", "ListParagraph"));
        check("F5: 非标题样式照常 setStyle", ri5c.ok);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("标题插入.docx"))) {
            org.apache.poi.xwpf.usermodel.XWPFParagraph lp = d.getParagraphs().get(0);
            check("F5: ListParagraph 段没被强加粗体/outlineLvl",
                    "ListParagraph".equals(lp.getStyle())
                            && (lp.getCTP().getPPr() == null
                                || lp.getCTP().getPPr().getOutlineLvl() == null)
                            && !lp.getRuns().get(0).isBold());
        }

        // 漏传 text 对 insert_paragraph 同样报错（同一类错误要同一套反馈）
        JavaWordEngine.Result missI = eng.execute("insert_paragraph",
                map("path", "标题插入.docx", "index", 0));
        check("F2: insert_paragraph 缺 text → ok=false", !missI.ok);
        check("F2: insert_paragraph 缺 text 时文档未被改动",
                eng.execute("read", map("path", "标题插入.docx")).text.contains("列表项"));

        // ================= 排版: insert_paragraph 与 writer 同源 =================
        JavaWordEngine.Result cLayIns = eng.execute("create",
                map("path", "排版插入.docx", "content", "正文甲\n\n正文乙"));
        check("排版插入前置: 文档建好", cLayIns.ok);
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
        JavaWordEngine.Config cfgPlainI = new JavaWordEngine.Config();
        cfgPlainI.allowedRoot = root;
        cfgPlainI.workDir = work;
        cfgPlainI.chineseLayout = false;
        JavaWordEngine engPlainI = new JavaWordEngine(cfgPlainI);
        check("排版: plain 引擎 create ok",
                engPlainI.execute("create",
                        map("path", "排版plain.docx", "content", "# 报告\n\n正文")).ok);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("排版plain.docx"))) {
            check("排版: plain 引擎 create 的 H1 无 jc 且正文无缩进",
                    !d.getParagraphs().get(0).isAlignmentSet()
                            && d.getParagraphs().get(1).getIndentationFirstLine() == -1);
        }
        check("排版: plain 引擎 insert 无 style → ok",
                engPlainI.execute("insert_paragraph",
                        map("path", "排版plain.docx", "index", 0, "text", "裸段")).ok);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("排版plain.docx"))) {
            check("排版: plain 引擎 insert 无 style 不缩进",
                    d.getParagraphs().get(0).getIndentationFirstLine() == -1);
        }
        check("排版: plain 引擎 insert Heading1 → ok",
                engPlainI.execute("insert_paragraph",
                        map("path", "排版plain.docx", "index", 0, "text", "裸标题",
                                "style", "Heading1")).ok);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("排版plain.docx"))) {
            org.apache.poi.xwpf.usermodel.XWPFParagraph hp = d.getParagraphs().get(0);
            check("排版: plain insert Heading1 不居中但仍是真标题（outlineLvl+粗体）",
                    !hp.isAlignmentSet()
                            && hp.getCTP().getPPr() != null
                            && hp.getCTP().getPPr().getOutlineLvl() != null
                            && hp.getRuns().get(0).isBold());
        }
        check("排版: style 只有空白 → 视为无样式（trim 后按正文缩进）",
                eng.execute("insert_paragraph",
                        map("path", "排版插入.docx", "index", 0, "text", "空白样式段",
                                "style", " ")).ok);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("排版插入.docx"))) {
            org.apache.poi.xwpf.usermodel.XWPFParagraph sp = d.getParagraphs().get(0);
            check("排版: 空白 style 不写空白 pStyle，按正文缩进 420",
                    sp.getStyle() == null && sp.getIndentationFirstLine() == 420);
        }
        // 相邻同型 boolean（before, chineseLayout）的守护：四象限里只有两象限能区分写反，
        // 恰好这两象限此前都没测——对抗性评审变异实证：swap 实参后 273 条全绿（I1）。
        check("排版: chinese 引擎 position=after 插入 → ok",
                eng.execute("insert_paragraph",
                        map("path", "排版插入.docx", "index", 0, "text", "锚点后插",
                                "position", "after")).ok);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("排版插入.docx"))) {
            check("排版: position=after 插在锚点之后（不是之前）且带缩进（swap 实参即红）",
                    "锚点后插".equals(d.getParagraphs().get(1).getText())
                            && d.getParagraphs().get(1).getIndentationFirstLine() == 420);
        }
        check("排版: plain 引擎默认 before 插入 → ok",
                engPlainI.execute("insert_paragraph",
                        map("path", "排版plain.docx", "index", 0, "text", "锚点前插")).ok);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument d = openDocx(work.resolve("排版plain.docx"))) {
            org.apache.poi.xwpf.usermodel.XWPFParagraph p0 = d.getParagraphs().get(0);
            check("排版: plain 默认 before 插在锚点之前（不是之后）且不缩进（swap 实参即红）",
                    "锚点前插".equals(p0.getText()) && p0.getIndentationFirstLine() == -1);
        }

        // ================= F4b：backup 参数接线（技能壳 → 引擎 Config） =================
        // 引擎层的 .bak 分支上面已断言过（cfg.backup=true → 报告.bak.docx）。这里补的是壳这一层：
        // XML params / 单次调用参数怎么落到 cfg.backup 上。缺陷态（修复前）是 JavaWordEngine.Config
        // 的 backup 字段根本没人写——配置里写 backup="true" 也永远不生效（spec 的"可选 backup"不可达）。
        //
        // 不启框架的实例化方式：TLWordJavaSkill 走 (String) 构造器（(String,TLObjectFactory) 会
        // 在 registFactory 上 NPE），params 用反射塞（protected，且声明在 base 包里，同包也读不到），
        // 只调 setModuleParams() 与私有的 ensureEngine(msg, input, chineseLayout)——两个都不碰工厂、不发消息、不落日志。
        TLWordJavaSkill sk = new TLWordJavaSkill("word");
        java.lang.reflect.Field pf = Class.forName("cn.tianlong.tlobject.base.TLBaseModule")
                .getDeclaredField("params");
        pf.setAccessible(true);
        java.util.HashMap<String, String> sp = new java.util.HashMap<>();
        sp.put("backup", "true");
        pf.set(sk, sp);
        sk.setModuleParams();   // protected，与自测同类同包，直接调

        java.lang.reflect.Method me = TLWordJavaSkill.class.getDeclaredMethod(
                "ensureEngine", cn.tianlong.tlobject.base.TLMsg.class,
                java.util.Map.class, boolean.class);
        me.setAccessible(true);
        java.lang.reflect.Field cf = JavaWordEngine.class.getDeclaredField("cfg");
        cf.setAccessible(true);
        cn.tianlong.tlobject.base.TLMsg msg0 = new cn.tianlong.tlobject.base.TLMsg();

        JavaWordEngine.Config c1 = (JavaWordEngine.Config)
                cf.get(me.invoke(sk, msg0, map("action", "replace"), true));
        check("F4b: XML params backup=true → 引擎 cfg.backup=true（接通了）", c1.backup);

        JavaWordEngine.Config c2 = (JavaWordEngine.Config)
                cf.get(me.invoke(sk, msg0, map("action", "replace", "backup", "false"), true));
        check("F4b: 单次调用 backup=false 能覆盖 XML 的 true", !c2.backup);

        JavaWordEngine.Config c3 = (JavaWordEngine.Config)
                cf.get(me.invoke(sk, msg0, map("action", "replace", "backup", true), true));
        check("F4b: 单次调用 backup=true（真 Boolean）同样生效", c3.backup);

        // 默认必须是关：别把"接通"顺手改成"默认开"（原子写已够安全，备份只为手工回退）
        TLWordJavaSkill sk2 = new TLWordJavaSkill("word");
        pf.set(sk2, new java.util.HashMap<String, String>());   // XML 没配 backup
        sk2.setModuleParams();
        JavaWordEngine.Config c4 = (JavaWordEngine.Config)
                cf.get(me.invoke(sk2, msg0, map("action", "replace"), true));
        check("F4b: XML 未配 backup → 默认 false", !c4.backup);

        java.lang.reflect.Field scf = Class.forName("cn.tianlong.tlobject.aiagent.TLBaseSkill")
                .getDeclaredField("parameterSchema");
        scf.setAccessible(true);
        Object schemaObj = scf.get(sk);
        check("F4b: parameterSchema 里有 backup（LLM 才看得见这个参数）",
                schemaObj instanceof java.util.Map && ((java.util.Map<?, ?>) schemaObj).containsKey("backup"));

        // 端到端（真调 execute，含 input 组装和平铺参数兜底）：这条才是"平铺列表里漏写 backup"的守门员——
        // 上面几条走的是反射直取 ensureEngine，绕过了 execute 里那段 key 列表。
        TLWordJavaSkill sk3 = new TLWordJavaSkill("word");
        java.util.HashMap<String, String> sp3 = new java.util.HashMap<>();
        sp3.put("allowedRootPath", root.toString());
        pf.set(sk3, sp3);
        sk3.setModuleParams();
        // 路径带分隔符 → 按 allowedRoot 解析。裸名才会落 workDir，而 workDir 约定是相对目录，
        // 绝对路径会被 Paths.get(workDir).resolve(userId) 再拼一层 userId，故这里走相对路径这条
        String rel = "data/default/documents/壳测.docx";
        try {
            check("F4b 前置: 壳测.docx 建好",
                    eng.execute("create", map("path", "壳测.docx", "content", "甲段\n\n乙段")).ok);
            cn.tianlong.tlobject.base.TLMsg flat = new cn.tianlong.tlobject.base.TLMsg();
            flat.setParam("action", "replace");       // 平铺参数（不套 SKILLINPUT 包）
            flat.setParam("path", rel);
            flat.setParam("find", "甲段");
            flat.setParam("replace", "丙段");
            flat.setParam("backup", "true");          // 该键若不在兜底列表里会被丢掉 → 不产出 .bak
            cn.tianlong.tlobject.base.TLMsg out = sk3.execute(null, flat);
            check("F4b: 平铺参数 backup=true 端到端产出 壳测.bak.docx",
                    out != null && Boolean.TRUE.equals(out.getParam(cn.tianlong.tlobject.base.TLParamString.RESULT))
                            && work.resolve("壳测.bak.docx").toFile().isFile());
            check("F4b: 端到端替换本身也成功（.bak 不是失败路径的残留）",
                    eng.execute("read", map("path", "壳测.docx")).text.contains("丙段"));
        } catch (Throwable t) {
            // execute() 的错误路径会 putLog → putMsg(log) → 无工厂 NPE，故整段兜住并如实报红
            check("F4b: 平铺参数 backup=true 端到端产出 壳测.bak.docx（抛异常: " + t + "）", false);
        }

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

        // XML 非法值：不抛（告警路径自守护）+ 回退 chinese（不是"保持旧值"——setModuleParams 可重入）
        TLWordJavaSkill skLayBad = new TLWordJavaSkill("word");
        java.util.HashMap<String, String> spBad1 = new java.util.HashMap<>();
        spBad1.put("layout", "plain");
        pf.set(skLayBad, spBad1);
        skLayBad.setModuleParams();                       // 先让 plain 生效
        java.util.HashMap<String, String> spBad2 = new java.util.HashMap<>();
        spBad2.put("layout", "bogus");
        pf.set(skLayBad, spBad2);
        boolean badXmlNoThrow = true;
        try { skLayBad.setModuleParams(); }               // 模拟 reload/setParam 重入
        catch (Throwable t) { badXmlNoThrow = false; }
        check("排版接线: XML 非法 layout 不抛（告警路径不把链路搞崩）", badXmlNoThrow);
        check("排版接线: 已生效 plain 后再配错 → 回退 chinese（不是保持旧值）",
                Boolean.TRUE.equals(rlM.invoke(skLayBad, map("action", "create"))));

        Object layPropBad = ((java.util.Map<?, ?>) scf.get(skLayBad)).get("layout");
        check("排版接线: 重入 setModuleParams 后 schema 默认值刷新（不滞留首建值）",
                layPropBad instanceof java.util.Map
                        && String.valueOf(((java.util.Map<?, ?>) layPropBad).get("description"))
                                .contains("Current default: chinese"));

        check("排版接线: parameterSchema 里有 layout（LLM 才看得见这个参数）",
                ((java.util.Map<?, ?>) scf.get(skLayP)).containsKey("layout"));

        Object layProp = ((java.util.Map<?, ?>) scf.get(skLayP)).get("layout");
        check("排版接线: schema 描述按实际配置显示默认值（plain）",
                layProp instanceof java.util.Map
                        && String.valueOf(((java.util.Map<?, ?>) layProp).get("description"))
                                .contains("Current default: plain"));

        TLWordJavaSkill skLayDef = new TLWordJavaSkill("word");
        pf.set(skLayDef, new java.util.HashMap<String, String>());   // XML 未配 layout
        skLayDef.setModuleParams();
        Object layPropDef = ((java.util.Map<?, ?>) scf.get(skLayDef)).get("layout");
        check("排版接线: 未配 layout 的实例 schema 描述显示 chinese（插值而非字面量）",
                layPropDef instanceof java.util.Map
                        && String.valueOf(((java.util.Map<?, ?>) layPropDef).get("description"))
                                .contains("Current default: chinese"));

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
            // 【与计划脚本的偏差，理由】AI_P_SKILLOUTPUT 声明在 TLAiAgentParamString（TLBaseSkill 实现它），
            // 不在 TLParamString —— 计划写的 TLParamString.AI_P_SKILLOUTPUT 编译不过。
            String errBad = String.valueOf(
                    outBad.getParam(cn.tianlong.tlobject.aiagent.TLAiAgentParamString.AI_P_SKILLOUTPUT));
            check("排版接线: 非法 layout → ok=false 且报错点名取值",
                    Boolean.FALSE.equals(outBad.getParam(cn.tianlong.tlobject.base.TLParamString.RESULT))
                            && errBad.contains("unknown layout") && errBad.contains("foo"));
            check("排版接线: 非法 layout 时文档未被创建",
                    !java.nio.file.Files.exists(work.resolve("非法排版.docx")));
        } catch (Throwable t) {
            check("排版接线: 非法 layout 报错（抛异常: " + t + "）", false);
        }

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

    /** 打开引擎写出的落盘文件（断言要落在 XML 上，内存态骗不了文件） */
    static org.apache.poi.xwpf.usermodel.XWPFDocument openDocx(java.nio.file.Path p)
            throws Exception {
        return new org.apache.poi.xwpf.usermodel.XWPFDocument(
                java.nio.file.Files.newInputStream(p));
    }

    static java.nio.file.Path saveTmp(org.apache.poi.xwpf.usermodel.XWPFDocument d, String name)
            throws Exception {
        java.nio.file.Path p = java.nio.file.Files.createTempDirectory("wordsf")
                .resolve(name);
        try (java.io.OutputStream o = java.nio.file.Files.newOutputStream(p)) { d.write(o); }
        return p;
    }

    /** 落盘重载后第 0 段的文本（断言不能只活在内存里） */
    static String roundTripText(org.apache.poi.xwpf.usermodel.XWPFDocument d, String name)
            throws Exception {
        java.nio.file.Path p = saveTmp(d, name);
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(p);
             org.apache.poi.xwpf.usermodel.XWPFDocument re =
                     new org.apache.poi.xwpf.usermodel.XWPFDocument(in)) {
            return re.getParagraphs().get(0).getText();
        }
    }

    /** 给 run 的 CTR 添子元素的 lambda（返回值忽略，用 Object 以兼容各种 CT*） */
    interface ElementAdder {
        Object add(org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR ctr);
    }

    /** 造 [rPr(bold), 指定元素, t("X")] 的 run——Windows Word 里最常见的元素形态 */
    static org.apache.poi.xwpf.usermodel.XWPFRun probeRun(ElementAdder add) {
        return probeRun(add, null);
    }

    static org.apache.poi.xwpf.usermodel.XWPFRun probeRun(ElementAdder add,
                        java.util.function.Consumer<org.apache.poi.xwpf.usermodel.XWPFRun> tweak) {
        org.apache.poi.xwpf.usermodel.XWPFRun r =
                newDoc().createParagraph().createRun();
        r.setBold(true);
        if (tweak != null) tweak.accept(r);
        if (add != null) add.add(r.getCTR());
        r.getCTR().addNewT().setStringValue("X");
        return r;
    }

    /** 段落级内联内容控件：返回内容容器供挂 run（sdt 只在落盘重载后才进 POI 的 iruns） */
    static org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtContentRun sdtContent(
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP ctp) {
        return ctp.addNewSdt().addNewSdtContent();
    }

    static int countFldChar(org.apache.poi.xwpf.usermodel.XWPFParagraph p) {
        int n = 0;
        for (org.apache.poi.xwpf.usermodel.XWPFRun r : p.getRuns()) n += r.getCTR().sizeOfFldCharArray();
        return n;
    }

    static int countInstrText(org.apache.poi.xwpf.usermodel.XWPFParagraph p) {
        int n = 0;
        for (org.apache.poi.xwpf.usermodel.XWPFRun r : p.getRuns()) n += r.getCTR().sizeOfInstrTextArray();
        return n;
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
