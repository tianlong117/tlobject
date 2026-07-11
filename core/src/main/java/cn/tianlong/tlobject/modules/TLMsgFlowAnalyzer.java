package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static cn.tianlong.tlobject.base.TLParamString.*;

/**
 * 消息流分析模块：读取 checkpoint 历史 JSONL 文件，生成 Mermaid 时序图。
 *
 * <p>数据来源: TLCheckpointModule 在 {@code data/checkpoint/*.history.jsonl} 中的记录，
 * 每条记录 = {@code previous → module : action} + msg 内部字段。
 *
 * <p>支持三级详细度:
 * <ul>
 *   <li><b>simple</b> — 仅 from→to: action</li>
 *   <li><b>normal</b> — + args、description、destination、msgId</li>
 *   <li><b>detailed</b> — + nextMsg 链、source、时间戳 Note</li>
 * </ul>
 *
 * <p>输出: Mermaid sequenceDiagram 文本，可保存为 .md 直接渲染。
 *
 * @author tianlong
 */
public class TLMsgFlowAnalyzer extends TLBaseModule {

    private String checkpointDir = "data/checkpoint/";
    private String detailLevel = "normal";
    private final Gson gson = new Gson();

    public TLMsgFlowAnalyzer() {
        super();
    }

    public TLMsgFlowAnalyzer(String name) {
        super(name);
    }

    public TLMsgFlowAnalyzer(String name, cn.tianlong.tlobject.base.TLObjectFactory moduleFactory) {
        super(name, moduleFactory);
    }

    @Override
    protected TLBaseModule init() {
        if (params != null) {
            if (params.get(CHECKPOINT_P_DIR) != null) {
                checkpointDir = params.get(CHECKPOINT_P_DIR);
            }
            if (params.get(FLOW_P_DETAILLEVEL) != null) {
                detailLevel = params.get(FLOW_P_DETAILLEVEL);
            }
        }
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case FLOW_ANALYZE:
                return analyzeFlow(fromWho, msg);
            case FLOW_GENERATE:
                return generateDiagram(fromWho, msg);
            case FLOW_SAVE:
                return saveDiagram(fromWho, msg);
            case FLOW_ANALYZE_AND_SAVE:
                return analyzeAndSave(fromWho, msg);
            case FLOW_SAVEHTML:
                return saveHtml(fromWho, msg);
            case FLOW_ANALYZE_AND_SAVEHTML:
                return analyzeAndSaveHtml(fromWho, msg);
            default:
                return null;
        }
    }

    // ======================== FlowRecord ========================

    /**
     * 单条消息流记录，从 JSONL 行解析而来。
     */
    public static class FlowRecord {
        public int seq;
        public String time;
        public String from;         // previous — 消息来源
        public String to;           // module — 处理者
        public String action;       // msg.action
        public String description;  // msg.description
        public String destination;  // msg.destination（路由目标）
        public String msgId;        // msg.msgId（具名消息）
        public String source;       // msg.source（原始发起者）
        public Map<String, Object> args;      // msg.args
        public Map<String, Object> systemArgs; // msg.systemArgs
        public boolean hasNextMsg;  // msg.nextMsg != null

        @Override
        public String toString() {
            return String.format("[%d] %s: %s --%s--> %s",
                    seq, time, from, action, to);
        }
    }

    // ======================== Action: analyzeFlow ========================

    /**
     * 扫描 checkpointDir 下所有 *.history.jsonl，解析为 FlowRecord 列表。
     *
     * <p>入参:
     * <ul>
     *   <li>{@code filterModule} — 可选，只返回涉及该模块的记录（作为 from 或 to）</li>
     * </ul>
     *
     * <p>出参: {@code flowRecords} — List&lt;FlowRecord&gt; 按时间排序
     */
    @SuppressWarnings("unchecked")
    public TLMsg analyzeFlow(Object fromWho, TLMsg msg) {
        String filterModule = msg.getStringParam(FLOW_P_MODULE, null);
        List<FlowRecord> records = new ArrayList<>();

        File dir = new File(checkpointDir);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".history.jsonl"));
        if (files == null || files.length == 0) {
            return createMsg().setParam(RESULT, false)
                    .setParam("message", "No history files found in " + checkpointDir);
        }

        for (File file : files) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    FlowRecord record = parseLine(line);
                    if (record == null) continue;

                    // 模块过滤
                    if (filterModule != null && !filterModule.isEmpty()) {
                        if (!filterModule.equals(record.from) && !filterModule.equals(record.to)) {
                            continue;
                        }
                    }
                    records.add(record);
                }
            } catch (IOException e) {
                putLog("analyzeFlow read failed: " + file.getName() + " - " + e.getMessage(),
                        LogLevel.WARN);
            }
        }

        // 按时间排序
        records.sort(Comparator.comparing(r -> r.time));

        putLog("analyzeFlow: " + records.size() + " records from "
                + files.length + " files", LogLevel.INFO);

        return createMsg().setParam(RESULT, true)
                .setParam(FLOW_P_FLOWRECORDS, records);
    }

    // ======================== Action: generateDiagram ========================

    /**
     * 基于 FlowRecord 列表生成 Mermaid 时序图。
     *
     * <p>入参:
     * <ul>
     *   <li>{@code flowRecords} — analyzeFlow 的输出</li>
     *   <li>{@code detailLevel} — "simple" | "normal" | "detailed"</li>
     * </ul>
     *
     * <p>出参: {@code diagram} — Mermaid 文本
     */
    @SuppressWarnings("unchecked")
    public TLMsg generateDiagram(Object fromWho, TLMsg msg) {
        List<FlowRecord> records = (List<FlowRecord>) msg.getListParam(FLOW_P_FLOWRECORDS, null);
        if (records == null || records.isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam("message", "No flow records. Run analyzeFlow first.");
        }

        String level = msg.getStringParam(FLOW_P_DETAILLEVEL, detailLevel);

        StringBuilder sb = new StringBuilder();
        sb.append("```mermaid\n");
        sb.append("sequenceDiagram\n");

        // 1. 收集所有参与者
        LinkedHashSet<String> participants = new LinkedHashSet<>();
        for (FlowRecord r : records) {
            if (r.from != null && !r.from.isEmpty()) participants.add(r.from);
            if (r.to != null && !r.to.isEmpty()) participants.add(r.to);
            if (!"simple".equals(level)) {
                if (r.destination != null && !r.destination.isEmpty()) participants.add(r.destination);
                if (r.source != null && !r.source.isEmpty()) participants.add(r.source);
            }
        }

        for (String p : participants) {
            sb.append("    participant ").append(p).append("\n");
        }
        sb.append("\n");

        // 2. 生成消息流
        String lastDate = "";
        boolean isDetailed = "detailed".equals(level);
        boolean isSimple = "simple".equals(level);

        for (FlowRecord r : records) {
            // detailed: 每条消息前加时间 Note
            if (isDetailed) {
                sb.append("    Note over ").append(r.to != null ? r.to : r.from)
                        .append(": seq=").append(r.seq).append(" ").append(r.time).append("\n");
            } else {
                // normal/simple: 按秒分组加 Note
                String datePart = r.time.length() >= 19 ? r.time.substring(0, 19) : r.time;
                if (!datePart.equals(lastDate)) {
                    lastDate = datePart;
                    // 使用第一个和最后一个参与者做 Note 范围
                    String first = participants.isEmpty() ? r.from
                            : participants.iterator().next();
                    String last = first;
                    Iterator<String> it = participants.iterator();
                    while (it.hasNext()) last = it.next();
                    sb.append("    Note over ").append(first).append(",").append(last)
                            .append(": === ").append(datePart).append(" ===\n");
                }
            }

            // 3. 构建箭头标签
            StringBuilder label = new StringBuilder();

            // description
            if (!isSimple && r.description != null && !r.description.isEmpty()) {
                label.append("\"").append(r.description).append("\" ");
            }

            // action
            label.append(r.action != null ? r.action : "(no action)");

            // args
            if (!isSimple && r.args != null && !r.args.isEmpty()) {
                label.append("(");
                boolean first = true;
                for (Map.Entry<String, Object> e : r.args.entrySet()) {
                    if (!first) label.append(", ");
                    label.append(e.getKey()).append("=").append(e.getValue());
                    first = false;
                }
                label.append(")");
            }

            // msgId
            if (!isSimple && r.msgId != null && !r.msgId.isEmpty()) {
                label.append(" [msgId=").append(r.msgId).append("]");
            }

            // 4. 主箭头
            String from = r.from != null && !r.from.isEmpty() ? r.from : "?";
            String to = r.to != null && !r.to.isEmpty() ? r.to : "?";
            sb.append("    ").append(from).append("->>").append(to)
                    .append(": ").append(label).append("\n");

            // 5. detailed: 附加信息 Note
            if (isDetailed) {
                if (r.msgId != null && !r.msgId.isEmpty()) {
                    sb.append("    Note right of ").append(to)
                            .append(": msgId=").append(r.msgId).append("\n");
                }
                if (r.hasNextMsg) {
                    sb.append("    Note right of ").append(to)
                            .append(": ⤏ nextMsg 链\n");
                }
                if (r.source != null && !r.source.isEmpty()
                        && !r.source.equals(r.from)) {
                    sb.append("    Note left of ").append(from)
                            .append(": source=").append(r.source).append("\n");
                }
            }

            // 6. normal/detailed: destination 路由转发
            if (!isSimple && r.destination != null && !r.destination.isEmpty()
                    && !r.destination.equals(r.to)) {
                sb.append("    ").append(r.to).append("-->>").append(r.destination)
                        .append(": 转发 ").append(r.action).append("\n");
            }
        }

        sb.append("```\n");

        String diagram = sb.toString();
        putLog("generateDiagram: " + diagram.length() + " chars, detailLevel=" + level, LogLevel.INFO);

        return createMsg().setParam(RESULT, true)
                .setParam(FLOW_P_DIAGRAM, diagram);
    }

    // ======================== Action: saveDiagram ========================

    /**
     * 将 Mermaid 时序图保存为 .md 文件。
     *
     * <p>入参:
     * <ul>
     *   <li>{@code diagram} — generateDiagram 的输出</li>
     *   <li>{@code filePath} — 可选，默认 {@code {checkpointDir}message_flow.md}</li>
     * </ul>
     *
     * <p>出参: {@code filePath} — 实际保存路径
     */
    public TLMsg saveDiagram(Object fromWho, TLMsg msg) {
        String diagram = msg.getStringParam(FLOW_P_DIAGRAM, null);
        if (diagram == null || diagram.isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam("message", "No diagram to save. Run generateDiagram first.");
        }

        String filePath = msg.getStringParam(FLOW_P_FILEPATH, null);
        if (filePath == null || filePath.isEmpty()) {
            filePath = checkpointDir + "message_flow.md";
        }

        try {
            StringBuilder content = new StringBuilder();
            content.append("# 消息流时序图\n\n");
            content.append("> 自动生成于 ").append(new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss").format(new java.util.Date())).append("\n");
            content.append("> 数据来源: ").append(checkpointDir).append("*.history.jsonl\n\n");
            content.append(diagram);

            Files.write(Paths.get(filePath), content.toString().getBytes(StandardCharsets.UTF_8));

            putLog("Diagram saved to: " + filePath + " ("
                    + content.length() + " bytes)", LogLevel.INFO);

            return createMsg().setParam(RESULT, true)
                    .setParam(FLOW_P_FILEPATH, filePath);
        } catch (IOException e) {
            putLog("saveDiagram failed: " + e.getMessage(), LogLevel.ERROR);
            return createMsg().setParam(RESULT, false)
                    .setParam("message", "Save failed: " + e.getMessage());
        }
    }

    // ======================== Action: analyzeAndSave ========================

    /**
     * 组合操作：analyzeFlow → generateDiagram → saveDiagram，一步到位。
     *
     * <p>入参（全部可选）:
     * <ul>
     *   <li>{@code filterModule} — 过滤模块名</li>
     *   <li>{@code detailLevel} — "simple"|"normal"(默认)|"detailed"</li>
     *   <li>{@code filePath} — 输出路径，默认 {@code {checkpointDir}message_flow.md}</li>
     * </ul>
     */
    public TLMsg analyzeAndSave(Object fromWho, TLMsg msg) {
        // Step 1: analyze
        TLMsg analyzeResult = analyzeFlow(fromWho, msg);
        if (!analyzeResult.parseBoolean(RESULT, false)) {
            return analyzeResult;
        }
        List<FlowRecord> records = (List<FlowRecord>) analyzeResult.getListParam(FLOW_P_FLOWRECORDS, null);
        if (records == null || records.isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam("message", "No flow records found");
        }

        // Step 2: generate
        TLMsg genMsg = createMsg()
                .setParam(FLOW_P_FLOWRECORDS, records)
                .setParam(FLOW_P_DETAILLEVEL, msg.getStringParam(FLOW_P_DETAILLEVEL, detailLevel));
        TLMsg genResult = generateDiagram(fromWho, genMsg);
        if (!genResult.parseBoolean(RESULT, false)) {
            return genResult;
        }
        String diagram = genResult.getStringParam(FLOW_P_DIAGRAM, null);

        // Step 3: save
        TLMsg saveMsg = createMsg()
                .setParam(FLOW_P_DIAGRAM, diagram)
                .setParam(FLOW_P_FILEPATH, msg.getStringParam(FLOW_P_FILEPATH, null));
        TLMsg saveResult = saveDiagram(fromWho, saveMsg);

        putLog("analyzeAndSave done: " + records.size() + " records → "
                + saveResult.getStringParam(FLOW_P_FILEPATH, "?"), LogLevel.INFO);

        return saveResult;
    }

    // ======================== Action: saveHtml ========================

    /**
     * 基于 FlowRecord 列表生成纯 SVG 时序图 HTML，无需任何外部依赖。
     *
     * <p>入参:
     * <ul>
     *   <li>{@code flowRecords} — FlowRecord 列表</li>
     *   <li>{@code filePath} — 可选，默认 {@code {checkpointDir}message_flow.html}</li>
     *   <li>{@code title} — 可选，页面标题，默认 "消息流时序图"</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public TLMsg saveHtml(Object fromWho, TLMsg msg) {
        List<FlowRecord> records = (List<FlowRecord>) msg.getListParam(FLOW_P_FLOWRECORDS, null);
        if (records == null || records.isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam("message", "No flow records. Run analyzeFlow first.");
        }

        String filePath = msg.getStringParam(FLOW_P_FILEPATH, null);
        if (filePath == null || filePath.isEmpty()) {
            filePath = checkpointDir + "message_flow.html";
        }
        String title = msg.getStringParam("title", "消息流时序图");
        String time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());

        // ---- 布局参数 ----
        final int HEADER_W = 160;        // 参与者头部宽度
        final int HEADER_H = 36;         // 参与者头部高度
        final int GAP_X = 60;            // 参与者水平间距
        final int PADDING_TOP = 30;      // 顶部留白
        final int ROW_H = 56;            // 每条消息行高
        final int PADDING_BOTTOM = 30;   // 底部留白
        final int LINE_EXTRA = 20;       // 生命线底部超出

        // 1. 收集参与者（保持首次出现顺序）
        LinkedHashMap<String, Integer> participantIdx = new LinkedHashMap<>();
        for (FlowRecord r : records) {
            if (r.from != null && !r.from.isEmpty()) participantIdx.putIfAbsent(r.from, participantIdx.size());
            if (r.to != null && !r.to.isEmpty()) participantIdx.putIfAbsent(r.to, participantIdx.size());
        }
        int n = participantIdx.size();
        if (n == 0) {
            return createMsg().setParam(RESULT, false).setParam("message", "No participants found");
        }

        // 2. 计算 SVG 尺寸
        int svgW = n * HEADER_W + (n - 1) * GAP_X + 60;
        int svgH = PADDING_TOP + HEADER_H + ROW_H * records.size() + PADDING_BOTTOM + LINE_EXTRA;

        // 参与者 X 中心坐标
        int[] pX = new int[n];
        for (int i = 0; i < n; i++) {
            pX[i] = 30 + HEADER_W / 2 + i * (HEADER_W + GAP_X);
        }

        int lifelineTop = PADDING_TOP + HEADER_H;
        int lifelineBot = svgH - PADDING_BOTTOM;
        int firstMsgY = lifelineTop + ROW_H / 2;

        // 3. 构建 SVG
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
                .append(svgW).append(" ").append(svgH)
                .append("\" style=\"font-family: -apple-system, 'Microsoft YaHei', sans-serif;\">\n");

        // 定义箭头标记
        svg.append("  <defs>\n");
        svg.append("    <marker id=\"arrow\" markerWidth=\"10\" markerHeight=\"7\" refX=\"9\" refY=\"3.5\" orient=\"auto\">\n");
        svg.append("      <polygon points=\"0 0, 10 3.5, 0 7\" fill=\"#4a5568\"/>\n");
        svg.append("    </marker>\n");
        svg.append("  </defs>\n");

        // 背景
        svg.append("  <rect width=\"100%\" height=\"100%\" fill=\"#f7fafc\"/>\n");

        // 4. 绘制参与者头部（圆角矩形 + 文字）
        String[] colors = {"#3182ce","#e53e3e","#38a169","#805ad5","#dd6b20","#319795","#d53f8c","#2b6cb0"};
        int idx = 0;
        for (Map.Entry<String, Integer> e : participantIdx.entrySet()) {
            int i = e.getValue();
            int x = pX[i] - HEADER_W / 2;
            String color = colors[i % colors.length];
            svg.append("  <rect x=\"").append(x).append("\" y=\"").append(PADDING_TOP)
                    .append("\" width=\"").append(HEADER_W).append("\" height=\"").append(HEADER_H)
                    .append("\" rx=\"6\" fill=\"").append(color).append("\"/>\n");
            svg.append("  <text x=\"").append(pX[i]).append("\" y=\"").append(PADDING_TOP + HEADER_H / 2 + 5)
                    .append("\" text-anchor=\"middle\" fill=\"#fff\" font-size=\"13\" font-weight=\"bold\">")
                    .append(escapeXml(e.getKey())).append("</text>\n");

            // 生命线（虚线）
            svg.append("  <line x1=\"").append(pX[i]).append("\" y1=\"").append(lifelineTop)
                    .append("\" x2=\"").append(pX[i]).append("\" y2=\"").append(lifelineBot)
                    .append("\" stroke=\"#cbd5e0\" stroke-width=\"1.5\" stroke-dasharray=\"6,4\"/>\n");
            idx++;
        }

        // 5. 绘制消息箭头
        for (int ri = 0; ri < records.size(); ri++) {
            FlowRecord r = records.get(ri);
            int msgY = firstMsgY + ri * ROW_H;

            Integer fromI = participantIdx.get(r.from);
            Integer toI = participantIdx.get(r.to);
            if (fromI == null) fromI = toI;
            if (toI == null) toI = fromI;

            int x1 = pX[fromI];
            int x2 = pX[toI];

            // 构建标签
            StringBuilder label = new StringBuilder();
            if (r.description != null && !r.description.isEmpty()) {
                label.append(r.description).append(": ");
            }
            label.append(r.action != null ? r.action : "(action)");
            if (r.args != null && !r.args.isEmpty()) {
                label.append("(");
                boolean first = true;
                for (Map.Entry<String, Object> ae : r.args.entrySet()) {
                    if (!first) label.append(",");
                    label.append(ae.getKey()).append("=").append(ae.getValue());
                    first = false;
                }
                label.append(")");
            }

            String labelText = label.toString();
            String color = "#4a5568";

            if (fromI.equals(toI)) {
                // 自循环：弧线在参与者右侧
                int arcX = x1 + HEADER_W / 2 + 20;
                svg.append("  <path d=\"M").append(x1).append(",").append(msgY)
                        .append(" C").append(arcX).append(",").append(msgY - 15)
                        .append(" ").append(arcX).append(",").append(msgY - 15)
                        .append(" ").append(x1).append(",").append(msgY)
                        .append("\" fill=\"none\" stroke=\"").append(color)
                        .append("\" stroke-width=\"1.8\" marker-end=\"url(#arrow)\"/>\n");
                // 标签在弧线上方
                svg.append("  <text x=\"").append(arcX + 5).append("\" y=\"").append(msgY - 20)
                        .append("\" fill=\"").append(color).append("\" font-size=\"11\">")
                        .append(escapeXml(labelText)).append("</text>\n");
            } else {
                // 跨对象箭头
                int arrowLen = Math.abs(x2 - x1);
                int dir = x2 > x1 ? 1 : -1;
                int ax1 = x1 + dir * (HEADER_W / 2);
                int ax2 = x2 - dir * (HEADER_W / 2);
                int midX = (ax1 + ax2) / 2;

                svg.append("  <line x1=\"").append(ax1).append("\" y1=\"").append(msgY)
                        .append("\" x2=\"").append(ax2).append("\" y2=\"").append(msgY)
                        .append("\" stroke=\"").append(color)
                        .append("\" stroke-width=\"1.8\" marker-end=\"url(#arrow)\"/>\n");

                // 标签在箭头上方
                svg.append("  <text x=\"").append(midX).append("\" y=\"").append(msgY - 8)
                        .append("\" text-anchor=\"middle\" fill=\"").append(color)
                        .append("\" font-size=\"11\">")
                        .append(escapeXml(labelText)).append("</text>\n");
            }
        }

        svg.append("</svg>\n");

        // 6. 组装 HTML
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"zh-CN\">\n");
        html.append("<head>\n");
        html.append("  <meta charset=\"UTF-8\">\n");
        html.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("  <title>").append(title).append("</title>\n");
        html.append("  <style>\n");
        html.append("    * { margin: 0; padding: 0; box-sizing: border-box; }\n");
        html.append("    body { font-family: -apple-system, 'Microsoft YaHei', sans-serif; ");
        html.append("background: #edf2f7; color: #2d3748; padding: 24px; }\n");
        html.append("    .container { max-width: 100%; margin: 0 auto; }\n");
        html.append("    h1 { font-size: 20px; margin-bottom: 6px; color: #1a202c; }\n");
        html.append("    .meta { font-size: 12px; color: #718096; margin-bottom: 20px; ");
        html.append("padding: 10px 16px; background: #fff; border-radius: 6px; ");
        html.append("border-left: 3px solid #3182ce; line-height: 1.6; }\n");
        html.append("    .diagram-box { background: #fff; border-radius: 8px; ");
        html.append("box-shadow: 0 1px 3px rgba(0,0,0,0.08); padding: 16px; overflow-x: auto; }\n");
        html.append("  </style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("<div class=\"container\">\n");
        html.append("  <h1>").append(title).append("</h1>\n");
        html.append("  <div class=\"meta\">\n");
        html.append("    生成时间: ").append(time).append(" &nbsp;|&nbsp; ");
        html.append("数据来源: ").append(checkpointDir).append("*.history.jsonl &nbsp;|&nbsp; ");
        html.append("消息记录: ").append(records.size()).append(" 条 &nbsp;|&nbsp; ");
        html.append("参与者: ").append(n).append(" 个\n");
        html.append("  </div>\n");
        html.append("  <div class=\"diagram-box\">\n");
        html.append(svg);
        html.append("\n  </div>\n");
        html.append("</div>\n");
        html.append("</body>\n");
        html.append("</html>\n");

        try {
            Files.write(Paths.get(filePath), html.toString().getBytes(StandardCharsets.UTF_8));
            putLog("HTML (SVG) saved to: " + filePath + " (" + html.length() + " bytes)", LogLevel.INFO);
            return createMsg().setParam(RESULT, true)
                    .setParam(FLOW_P_FILEPATH, filePath);
        } catch (IOException e) {
            putLog("saveHtml failed: " + e.getMessage(), LogLevel.ERROR);
            return createMsg().setParam(RESULT, false)
                    .setParam("message", "Save HTML failed: " + e.getMessage());
        }
    }

    /** XML 转义 */
    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    // ======================== Action: analyzeAndSaveHtml ========================

    /**
     * 组合操作：analyzeFlow → generateDiagram → saveHtml，一步到位输出 HTML。
     */
    @SuppressWarnings("unchecked")
    public TLMsg analyzeAndSaveHtml(Object fromWho, TLMsg msg) {
        // Step 1: analyze
        TLMsg analyzeResult = analyzeFlow(fromWho, msg);
        if (!analyzeResult.parseBoolean(RESULT, false)) {
            return analyzeResult;
        }
        List<FlowRecord> records = (List<FlowRecord>) analyzeResult.getListParam(FLOW_P_FLOWRECORDS, null);
        if (records == null || records.isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam("message", "No flow records found");
        }

        // Step 2: generate
        TLMsg genMsg = createMsg()
                .setParam(FLOW_P_FLOWRECORDS, records)
                .setParam(FLOW_P_DETAILLEVEL, msg.getStringParam(FLOW_P_DETAILLEVEL, detailLevel));
        TLMsg genResult = generateDiagram(fromWho, genMsg);
        if (!genResult.parseBoolean(RESULT, false)) {
            return genResult;
        }
        String diagram = genResult.getStringParam(FLOW_P_DIAGRAM, null);

        // Step 3: save HTML (pure SVG) + MD
        TLMsg saveHtmlMsg = createMsg()
                .setParam(FLOW_P_FLOWRECORDS, records)
                .setParam(FLOW_P_FILEPATH, msg.getStringParam(FLOW_P_FILEPATH, null));
        TLMsg saveResult = saveHtml(fromWho, saveHtmlMsg);

        // 同时保存 .md 文件
        TLMsg saveMdMsg = createMsg()
                .setParam(FLOW_P_DIAGRAM, diagram)
                .setParam(FLOW_P_FILEPATH, msg.getStringParam(FLOW_P_FILEPATH,
                        checkpointDir + "message_flow.md"));
        saveDiagram(fromWho, saveMdMsg);

        putLog("analyzeAndSaveHtml done: " + records.size() + " records → "
                + saveResult.getStringParam(FLOW_P_FILEPATH, "?"), LogLevel.INFO);

        return saveResult;
    }

    // ======================== 内部工具方法 ========================

    /**
     * 从单行 JSONL 解析 FlowRecord。
     * 使用 Gson 解析整行，然后逐字段提取。
     */
    private FlowRecord parseLine(String line) {
        try {
            JsonObject root = JsonParser.parseString(line).getAsJsonObject();
            FlowRecord r = new FlowRecord();

            r.seq = getInt(root, "seq");
            r.time = getString(root, "time");
            r.from = getString(root, "previous");
            r.to = getString(root, "module");
            r.action = getString(root, "action");

            // 内层 msg 对象
            JsonObject msgObj = root.getAsJsonObject("msg");
            if (msgObj != null) {
                r.action = getString(msgObj, "action", r.action); // msg.action 覆盖外层
                r.description = getString(msgObj, "description");
                r.destination = getString(msgObj, "destination");
                r.msgId = getString(msgObj, "msgId");
                r.source = getString(msgObj, "source");

                // nextMsg 链
                JsonElement nextMsg = msgObj.get("nextMsg");
                r.hasNextMsg = (nextMsg != null && !nextMsg.isJsonNull());

                // args
                JsonElement argsEl = msgObj.get("args");
                if (argsEl != null && argsEl.isJsonObject()) {
                    r.args = gson.fromJson(argsEl, Map.class);
                }

                // systemArgs
                JsonElement sysArgsEl = msgObj.get("systemArgs");
                if (sysArgsEl != null && sysArgsEl.isJsonObject()) {
                    r.systemArgs = gson.fromJson(sysArgsEl, Map.class);
                }
            }

            return r;
        } catch (Exception e) {
            putLog("parseLine failed: " + e.getMessage() + " line="
                    + (line.length() > 80 ? line.substring(0, 80) + "..." : line),
                    LogLevel.WARN);
            return null;
        }
    }

    private String getString(JsonObject obj, String key) {
        return getString(obj, key, null);
    }

    private String getString(JsonObject obj, String key, String defaultVal) {
        if (obj == null) return defaultVal;
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return defaultVal;
        return el.getAsString();
    }

    private int getInt(JsonObject obj, String key) {
        if (obj == null) return 0;
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return 0;
        return el.getAsInt();
    }
}
