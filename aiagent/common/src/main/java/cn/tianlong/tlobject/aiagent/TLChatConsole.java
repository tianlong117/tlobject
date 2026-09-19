package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

/**
 * AI 通用命令行交互模块。通过消息触发，可被任何应用引用。
 *
 * 线程模型（事件循环）:
 *   - 主线程：独占终端。只从 eventQueue poll 事件，串行处理输入/结果/流式块并打印。
 *   - 读取线程(daemon)：JLine raw mode 逐字符读 stdin；ESC → STOP 事件；Enter → INPUT 事件。
 *   - 后台执行：chat 经 putMsgNoWait 异步跑，完成后经任务结果回调(onChatDone)入队 RESULT；
 *     流式 chunk 直接发回本模块(onStreamChunk)入队 CHUNK/STREAM_END。
 *   这样 agent 运行时主线程仍空闲，可随时响应 /stop 或 ESC 中断。
 *
 * 命令处理已委托给 TLAgentService：Console 负责文本→消息的解析和输出渲染，
 * TLAgentService 负责所有命令的实际执行。
 *
 * 配置参数 (XML params):
 *   agentModule   — 目标 Agent 模块名，默认 "aiagent"
 *   serviceModule — Agent 公共服务模块名，默认 "agentService"
 *   sessionId     — 默认会话 ID
 *   streamMode    — 默认流式模式，默认 "false"
 *   prompt        — 输入提示符，默认 "你 > "
 *
 * @author tianlong
 * @date 2026/7/12
 */
public class TLChatConsole extends TLBaseModule implements TLAiAgentParamString {

    private String agentModule = "aiagent";
    private String serviceModule = "agentService";
    private String userId = "console_user";
    private String sessionId;
    /** 待恢复的 mid-loop 检查点（启动时检测到未完成会话，由 /resume 触发恢复） */
    private String pendingCheckpointSessionId = null;
    private String pendingCheckpointUserMessage = null;
    private boolean streamMode = false;
    private String prompt = "你 > ";
    private volatile boolean running = false;
    /** 推理展示：是否折叠推理内容（默认折叠，展开后可查看完整思考链） */
    private boolean reasoningCollapsed = true;
    /** 推理模式：off | prompt | native | auto（null=使用 agent 默认值） */
    private String reasoningMode = null;

    /** 命令缓存：从 agentService 获取，用于 Tab 补全和帮助提示。key="/xxx", value=描述 */
    private LinkedHashMap<String, String> commandCache = new LinkedHashMap<>();

    /** 输入历史：上下箭头翻阅，最多保留 200 条 */
    private final java.util.LinkedList<String> inputHistory = new java.util.LinkedList<>();
    /** 当前历史翻阅位置：-1 = 输新内容；0..size-1 = 历史条目 */
    private int historyIndex = -1;
    /** 翻阅前正在编辑的内容（用于"返回"时恢复） */
    private String historyDraft = "";
    private static final int MAX_HISTORY = 200;

    // ======================== 事件循环状态（仅主线程访问） ========================
    private enum EventType { INPUT, RESULT, CHUNK, STREAM_END, STOP, APPROVAL }

    private static final class ConsoleEvent {
        final EventType type;
        final String text;   // INPUT: 输入行; CHUNK: 文本块; STREAM_END: 错误信息(可空); STOP: null
        final TLMsg msg;     // RESULT: chat 返回消息
        ConsoleEvent(EventType type, String text, TLMsg msg) {
            this.type = type; this.text = text; this.msg = msg;
        }
    }

    private final BlockingQueue<ConsoleEvent> eventQueue = new LinkedBlockingQueue<>();
    // busy 会被 reader 线程读（Ctrl-C/Ctrl-D 分支）、主线程写 —— 同文件的 running/thinking 都是
    // volatile，这里原来漏了；不加的话 reader 可能读到陈旧的 false 而漏发停止信号
    private volatile boolean busy = false;
    private boolean drainInput = false;
    private long currentStart = 0L;
    private Thread readerThread;
    private ThreadTask currentTask;
    private boolean paused = false;

    /** 思考动画（非流式聊天等待期间显示，busy 结束/中断时停止） */
    private volatile boolean thinking = false;
    private Thread thinkingThread;

    /** JLine 终端引用（异步输出后强制 flush，修复 raw 模式下"结果按回车才出来"的显示延迟） */
    private Terminal terminal;
    private long lastEscTime = 0;
    private static final long DOUBLE_ESC_WINDOW = 500;
    /** readEscapeAction 返回值：Delete 键 */
    private static final int KEY_DELETE = 1000;

    public TLChatConsole() { super(); }
    public TLChatConsole(String name) { super(name); }
    public TLChatConsole(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected TLBaseModule init() { return this; }

    @Override
    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        // 从总线注销订阅，否则模块卸载/重载后旧实例仍留在 receivers 里，继续 ack 审批事件
        try {
            putMsg("msgBus", createMsg().setAction("unRegistBus")
                    .setParam("destination", "approvalEvent").setParam("object", this));
        } catch (Exception e) {
            putLog("msgBus 注销审批事件订阅失败: " + e, cn.tianlong.tlobject.modules.LogLevel.DEBUG);
        }
        return super.destroy(fromWho, msg);
    }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null) {
            if (params.get("agentModule") != null) agentModule = params.get("agentModule");
            if (params.get("serviceModule") != null) serviceModule = params.get("serviceModule");
            if (params.get("userId") != null) userId = params.get("userId");
            if (params.get("sessionId") != null) sessionId = params.get("sessionId");
            if (params.get("streamMode") != null) streamMode = "true".equals(params.get("streamMode"));
            if (params.get("prompt") != null) prompt = params.get("prompt");
        }
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case "startChat":
                // 命令行 -u 参数优先于配置/默认值
                if (msg.containsParam("userId")) {
                    String cmdUser = msg.getStringParam("userId", null);
                    if (cmdUser != null && !cmdUser.isEmpty()) userId = cmdUser;
                }
                startConsole();
                break;
            case "stopChat":
                running = false;
                break;
            case "onChatDone":
                offer(new ConsoleEvent(EventType.RESULT, null, msg));
                break;
            case STREAM_ONCHUNK:
                onStreamChunkEvent(msg);
                break;
            case "approvalEvent":
                // 审批事件（经 msgBus 订阅）：主循环渲染审批框并重打提示符，无需回车。
                // ack 只在控制台真的在跑时才给（非 null=已处理），否则事件排进没人消费的队列，
                // 发布方却以为有人渲染了 —— 返回 null 让它回退到直接打印
                offer(new ConsoleEvent(EventType.APPROVAL, msg.getStringParam("text", ""), null));
                return running ? createMsg().setParam(RESULT, true) : null;
        }
        return null;
    }

    // ======================== 事件循环 ========================

    private void startConsole() {
        if (sessionId == null) sessionId = "chat_" + userId + "_" + System.currentTimeMillis();
        System.out.println("╔══════════════════════════════════╗");
        System.out.println("║   AI Agent 交互式 Chat          ║");
        System.out.println("║   输入消息开始对话，ESC 中断     ║");
        System.out.println("╚══════════════════════════════════╝\n");

        // 从 agentService 获取命令列表（供 Tab 补全/帮助）
        populateCommandCache();
        printSkills();

        // 检测断点会话
        try {
            TLMsg incompleteResult = putMsg(serviceModule, createMsg().setAction("resume")
                    .setParam("userId", userId));
            if (incompleteResult != null && incompleteResult.parseBoolean("success", false)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) incompleteResult.getParam("data");
                if (data != null) {
                    pendingCheckpointSessionId = (String) data.get("sessionId");
                    pendingCheckpointUserMessage = (String) data.get("userMessage");
                    String checkpointAgent = (String) data.get("agentName");
                    long savedAt = data.get("savedAt") instanceof Long ? (Long) data.get("savedAt") : 0L;
                    int iteration = data.get("iteration") instanceof Integer ? (Integer) data.get("iteration") : 0;
                    String timeStr = savedAt > 0
                            ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                                .format(new java.util.Date(savedAt))
                            : "未知";
                    System.out.println();
                    System.out.println("⚠═══════════════════════════════════");
                    System.out.println("  检测到上次未正常结束的会话:");
                    System.out.println("  Agent:    " + (checkpointAgent != null ? checkpointAgent : "未知"));
                    System.out.println("  会话ID:   " + pendingCheckpointSessionId);
                    System.out.println("  用户消息: " + pendingCheckpointUserMessage);
                    System.out.println("  中断时间: " + timeStr + " (第 " + iteration + " 轮)");
                    System.out.println("  输入 /resume 继续执行，或直接输入新消息开始新对话");
                    System.out.println("═══════════════════════════════════");
                    System.out.println();
                }
            }
        } catch (Exception e) {
            System.out.println("（检查断点失败: " + e.getMessage() + "）");
        }

        prompt = "你(" + userId + ") > ";
        System.out.println("\nAI Agent 就绪，开始对话吧！（ESC 中断，/exit 退出）\n");

        running = true;

        // JLine Terminal
        terminal = null;
        try {
            terminal = TerminalBuilder.builder()
                    .system(true)
                    .streams(System.in, System.out)
                    .build();
            terminal.enterRawMode();
        } catch (Exception e) {
            System.out.println("终端初始化失败，回退到行模式（ESC 不可用）: " + e.getMessage());
            startConsoleFallback();
            return;
        }

        final Terminal term = terminal;
        final NonBlockingReader termReader = term.reader();

        // 订阅审批事件：审批模块经消息总线发布，控制台自行渲染（解耦，不直接依赖审批模块）
        try {
            TLMsg regMsg = putMsg("msgBus", createMsg().setAction("registBus")
                    .setParam("destination", "approvalEvent").setParam("object", this));
            if (regMsg == null || !Boolean.TRUE.equals(regMsg.getParam(RESULT)))
                putLog("msgBus 订阅审批事件被拒绝", cn.tianlong.tlobject.modules.LogLevel.WARN);
        } catch (Exception e) {
            putLog("msgBus 订阅审批事件失败: " + e, cn.tianlong.tlobject.modules.LogLevel.WARN);
        }

        // 读取线程
        readerThread = new Thread(() -> {
            StringBuilder line = new StringBuilder();
            int cursorPos = 0; // 光标在 line 中的位置（0 = 行首）
            try {
                while (running) {
                    int ch = termReader.read();
                    if (ch < 0) {
                        if (running) offer(new ConsoleEvent(EventType.INPUT, "/exit", null));
                        break;
                    }
                    if (ch == 3 || ch == 4) {
                        if (busy) sendStopSignal();
                        running = false;
                        break;
                    }
                    if (ch == 27) {
                        int peeked;
                        try { peeked = termReader.peek(50); } catch (Exception ignore) { peeked = -1; }
                        if (peeked <= 0) {
                            line.setLength(0);
                            cursorPos = 0;
                            sendStopSignal();
                        } else {
                            int action = readEscapeAction(termReader);
                            switch (action) {
                                case 'A': // 上箭头：翻历史
                                    handleHistoryUp(line);
                                    cursorPos = line.length();
                                    break;
                                case 'B': // 下箭头：翻历史
                                    handleHistoryDown(line);
                                    cursorPos = line.length();
                                    break;
                                case 'C': // 右箭头
                                    if (cursorPos < line.length()) {
                                        int w = isFullWidthChar(line.charAt(cursorPos)) ? 2 : 1;
                                        System.out.printf("\033[%dC", w);
                                        System.out.flush();
                                        cursorPos++;
                                    }
                                    break;
                                case 'D': // 左箭头
                                    if (cursorPos > 0) {
                                        int w = isFullWidthChar(line.charAt(cursorPos - 1)) ? 2 : 1;
                                        System.out.printf("\033[%dD", w);
                                        System.out.flush();
                                        cursorPos--;
                                    }
                                    break;
                                case 'H': // Home
                                {
                                    int cols = visualWidth(line, 0, cursorPos);
                                    if (cols > 0) System.out.printf("\033[%dD", cols);
                                    System.out.flush();
                                    cursorPos = 0;
                                    break;
                                }
                                case 'F': // End
                                {
                                    int cols = visualWidth(line, cursorPos, line.length());
                                    if (cols > 0) System.out.printf("\033[%dC", cols);
                                    System.out.flush();
                                    cursorPos = line.length();
                                    break;
                                }
                                case KEY_DELETE: // Delete 键
                                    if (cursorPos < line.length()) {
                                        line.deleteCharAt(cursorPos);
                                        redrawLine(line, cursorPos);
                                    }
                                    break;
                            }
                        }
                        continue;
                    }
                    if (ch == '\r' || ch == '\n') {
                        if (ch == '\r') {
                            try { if (termReader.peek(50) == '\n') termReader.read(); } catch (Exception ignore) {}
                        }
                        String input = line.toString();
                        line.setLength(0);
                        cursorPos = 0;
                        if (!input.isEmpty()) {
                            addToHistory(input.trim());
                        }
                        historyIndex = -1;
                        System.out.println();
                        System.out.flush();
                        offer(new ConsoleEvent(EventType.INPUT, input, null));
                        continue;
                    }
                    if (ch == 127 || ch == 8) {
                        if (cursorPos > 0) {
                            char removed = line.charAt(cursorPos - 1);
                            line.deleteCharAt(cursorPos - 1);
                            cursorPos--;
                            if (isFullWidthChar(removed)) {
                                // 全角字符删除后重绘整行以确保对齐
                                redrawLine(line, cursorPos);
                            } else if (cursorPos == line.length()) {
                                // 在末尾删除，只需 \b \b
                                System.out.print("\b \b");
                                System.out.flush();
                            } else {
                                // 在中间删除，重绘
                                redrawLine(line, cursorPos);
                            }
                        }
                        continue;
                    }
                    if (ch == '\t') {
                        handleTabComplete(line);
                        cursorPos = line.length();
                        continue;
                    }
                    if (ch >= 32) {
                        if (cursorPos == line.length()) {
                            // 在末尾追加：快速路径，直接输出
                            line.append((char) ch);
                            cursorPos++;
                            System.out.print((char) ch);
                            System.out.flush();
                        } else {
                            // 在中间插入：需要重绘
                            line.insert(cursorPos, (char) ch);
                            cursorPos++;
                            redrawLine(line, cursorPos);
                        }
                    }
                }
            } catch (Exception ignore) {
            } finally {
                try { term.close(); } catch (Exception ignore) {}
            }
        }, "chat-console-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        // 主循环
        printPrompt();
        System.out.flush();
        while (running) {
            ConsoleEvent e;
            try { e = eventQueue.poll(300, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ie) { break; }
            if (e == null) continue;

            switch (e.type) {
                case INPUT:      handleInput(e.text); break;
                case STOP:       handleStopSignal(); break;
                case RESULT:     onChatResult(e.msg); break;
                case CHUNK:      System.out.print(e.text); System.out.flush(); break;
                case STREAM_END: onStreamEnd(e.text,
                        e.msg != null ? e.msg.getStringParam(AI_P_REASONING, null) : null); break;
                case APPROVAL:
                    // 审批框 + 紧随其后的输入提示符（用户可直接输入 /approve 命令）
                    System.out.println();
                    System.out.println(e.text);
                    printPrompt();
                    System.out.flush();
                    break;
            }
        }

        running = false;
        try { term.close(); } catch (Exception ignore) {}
        System.out.println("再见！");
    }

    /** JLine raw mode 不可用时的降级方案 */
    private void startConsoleFallback() {
        java.util.Scanner scanner = new java.util.Scanner(System.in);
        readerThread = new Thread(() -> {
            try {
                while (running && scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    offer(new ConsoleEvent(EventType.INPUT, line, null));
                }
            } catch (Exception ignore) {}
        }, "chat-console-reader-fb");
        readerThread.setDaemon(true);
        readerThread.start();

        printPrompt();
        while (running) {
            ConsoleEvent e;
            try { e = eventQueue.poll(300, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ie) { break; }
            if (e == null) continue;
            switch (e.type) {
                case INPUT:      handleInput(e.text); break;
                case RESULT:     onChatResult(e.msg); break;
                case CHUNK:      System.out.print(e.text); System.out.flush(); break;
                case STREAM_END: onStreamEnd(e.text,
                        e.msg != null ? e.msg.getStringParam(AI_P_REASONING, null) : null); break;
            }
        }
        System.out.println("再见！");
    }

    private static boolean isFullWidthChar(char c) {
        return (c >= 0x2E80 && c <= 0x2EFF)
            || (c >= 0x3000 && c <= 0x303F)
            || (c >= 0x3200 && c <= 0x32FF)
            || (c >= 0x3400 && c <= 0x4DBF)
            || (c >= 0x4E00 && c <= 0x9FFF)
            || (c >= 0xF900 && c <= 0xFAFF)
            || (c >= 0xFE10 && c <= 0xFE1F)
            || (c >= 0xFE30 && c <= 0xFE4F)
            || (c >= 0xFF01 && c <= 0xFF60)
            || (c >= 0xFFE0 && c <= 0xFFE6)
            || c == 0x2014 || c == 0x2015;
    }

    /**
     * 读取 ESC 后续的转义序列，返回语义化动作码。
     *   'A'..'D' = 方向键, 'H' = Home, 'F' = End, KEY_DELETE = Delete
     */
    private int readEscapeAction(NonBlockingReader reader) {
        try {
            int b = reader.read(50);
            if (b < 0) return -1;
            // CSI 序列 (ESC [ 或 ESC O)
            if (b == 0x5B /* '[' */ || b == 0x4F /* 'O' */) {
                // 读取第一个参数字节（可能是数字，也可能直接是终止符）
                b = reader.read(50);
                if (b < 0) return -1;
                int param = 0;
                // 积累参数数字（如 Home = ESC [ 1 ~，Delete = ESC [ 3 ~）
                while (b >= 0x30 && b <= 0x39) { // '0'..'9'
                    param = param * 10 + (b - 0x30);
                    b = reader.read(50);
                    if (b < 0) return -1;
                }
                // b 现在是终止符或中间字节
                if (b == 0x7E /* '~' */) {
                    // 带参数的 ~ 序列
                    if (param == 1 || param == 7) return 'H';   // Home
                    if (param == 4 || param == 8) return 'F';   // End
                    if (param == 3) return KEY_DELETE;          // Delete
                    return -1;
                }
                if (b >= 0x40 && b <= 0x7E) return b; // A/B/C/D/H/F 等
                // 跳过中间字节（分号等 0x20-0x3F），读终止符
                while (b >= 0x20 && b < 0x40) {
                    b = reader.read(50);
                    if (b < 0) return -1;
                }
                if (b >= 0x40 && b <= 0x7E) return b;
                return -1;
            }
            // 非 CSI 序列：当前字节即动作码
            if (b >= 0x40 && b <= 0x7E) return b;
        } catch (Exception ignore) {}
        return -1;
    }

    /** 上箭头：翻之前的输入 */
    private void handleHistoryUp(StringBuilder line) {
        if (inputHistory.isEmpty()) return;
        if (historyIndex == -1) {
            historyDraft = line.toString(); // 保存当前草稿
            historyIndex = inputHistory.size() - 1;
        } else if (historyIndex > 0) {
            historyIndex--;
        }
        replaceLine(line, inputHistory.get(historyIndex));
    }

    /** 下箭头：翻之后的输入，到底则恢复草稿 */
    private void handleHistoryDown(StringBuilder line) {
        if (historyIndex == -1) return;
        if (historyIndex < inputHistory.size() - 1) {
            historyIndex++;
            replaceLine(line, inputHistory.get(historyIndex));
        } else {
            historyIndex = -1;
            replaceLine(line, historyDraft);
        }
    }

    /** 用新文本替换当前行显示和 line */
    private void replaceLine(StringBuilder line, String newText) {
        // 清除当前显示：按显示列宽回退（全角字符占 2 列，逐字符清会残留半行，
        // 表现为"上一条中文内容不消失、新历史跟在后面"）
        int width = visualWidth(line, 0, line.length());
        for (int i = 0; i < width; i++) {
            System.out.print("\b \b");
        }
        // 设置新内容
        line.setLength(0);
        line.append(newText);
        System.out.print(newText);
        System.out.flush();
    }

    /**
     * 计算 StringBuilder 指定区间的显示列宽（全角=2列，半角=1列）。
     */
    private int visualWidth(StringBuilder sb, int from, int to) {
        int w = 0;
        for (int i = from; i < to && i < sb.length(); i++) {
            w += isFullWidthChar(sb.charAt(i)) ? 2 : 1;
        }
        return w;
    }

    /**
     * 重绘整行（内容有变化时使用）。
     * 用 \r 回行首 + ANSI 清屏 + 重打 prompt + 内容 + 光标定位。
     */
    private void redrawLine(StringBuilder line, int cursorPos) {
        System.out.print("\r\033[K");  // 回行首 + 清除到行尾
        System.out.print(prompt);
        System.out.print(line.toString());
        // 把光标移回正确位置（按显示列宽计算）
        int afterCursor = visualWidth(line, cursorPos, line.length());
        if (afterCursor > 0) {
            System.out.printf("\033[%dD", afterCursor);
        }
        System.out.flush();
    }

    /** 加入历史（去相邻重复，限制长度） */
    private void addToHistory(String input) {
        if (inputHistory.isEmpty() || !inputHistory.getLast().equals(input)) {
            inputHistory.addLast(input);
            while (inputHistory.size() > MAX_HISTORY) inputHistory.removeFirst();
        }
    }

    // ======================== 输入处理 ========================

    /** 处理一行输入（主线程） */
    private void handleInput(String raw) {
        if (drainInput) {
            drainInput = false;
            return;
        }
        String input = raw == null ? "" : raw.trim();

        // busy 状态下只响应 /stop 和 /approve
        if (busy) {
            if (input.equalsIgnoreCase("/stop")) {
                stopThinking();
                if (paused && currentTask != null) {
                    currentTask.cancelTask();
                    paused = false;
                }
                stopChat();
                busy = false;
                currentTask = null;
                paused = false;
            } else if (input.toLowerCase().startsWith("/approve")) {
                executeServiceCommand(parseCommand(input));
            } else if (!input.isEmpty()) {
                System.out.println("⏳ 运行中，按 ESC 或输入 /stop 可中断当前对话");
            }
            printPrompt();
            System.out.flush();
            return;
        }

        if (input.isEmpty()) { printPrompt(); System.out.flush(); return; }

        if (input.startsWith("/")) {
            // 本地命令：/exit, /quit, /stream, /thinking
            String cmdLower = input.toLowerCase().split("\\s+")[0];
            switch (cmdLower) {
                case "/exit":
                case "/quit":
                    running = false;
                    return;
                case "/stream":
                    streamMode = !streamMode;
                    System.out.println("✓ 流式模式: " + (streamMode ? "开启" : "关闭"));
                    printPrompt();
                    System.out.flush();
                    return;
                case "/thinking":
                    if (input.length() > "/thinking".length()) {
                        String mode = input.substring("/thinking".length()).trim();
                        if (mode.equals("off") || mode.equals("prompt") || mode.equals("native") || mode.equals("auto")) {
                            reasoningMode = mode;
                            putMsg(serviceModule, createMsg().setAction("setParam")
                                    .setParam("param", AI_P_REASONING_MODE)
                                    .setParam("value", mode));
                            System.out.println("✓ 推理模式: " + mode);
                        } else {
                            System.out.println("用法: /thinking [off|prompt|native|auto]");
                        }
                    } else {
                        reasoningCollapsed = !reasoningCollapsed;
                        System.out.println("✓ 推理展示: " + (reasoningCollapsed ? "折叠" : "展开"));
                    }
                    printPrompt();
                    System.out.flush();
                    return;
                case "/help":
                case "/?":
                    handleHelp();
                    printPrompt();
                    System.out.flush();
                    return;
            }
            // 其他命令 → 解析后委托给 agentService
            TLMsg cmdMsg = parseCommand(input);
            if (cmdMsg != null) {
                executeServiceCommand(cmdMsg);
            }
            printPrompt();
            System.out.flush();
            return;
        }

        // 普通消息 → chat
        busy = true;
        currentStart = System.currentTimeMillis();
        submitChat(input);
    }

    /**
     * 文本命令 → 结构化 TLMsg。
     * Console 负责格式解析，agentService 负责执行。
     */
    private TLMsg parseCommand(String input) {
        if (input == null || !input.startsWith("/")) return null;
        String[] parts = input.substring(1).split("\\s+");
        if (parts.length == 0) return null;
        String cmd = parts[0].toLowerCase();
        TLMsg msg = createMsg();

        switch (cmd) {
            case "stop":
                // /stop → stopChat
                msg.setAction("stopChat").setParam(AI_P_SESSIONID, sessionId);
                break;

            case "clear":
                msg.setAction("clear").setParam(AI_P_SESSIONID, sessionId);
                break;

            case "param":
                if (parts.length < 2) {
                    System.out.println("用法: /param <工具名>");
                    return null;
                }
                msg.setAction("param").setParam("toolName", parts[1]);
                break;

            case "resume":
                // /resume → 恢复断点（非 busy 时）
                if (pendingCheckpointSessionId != null) {
                    // 有断点：通过 agentService 异步恢复（agentService 向 SessionManager 加载数据后发给 Agent）
                    String resumeSid = pendingCheckpointSessionId;
                    String resumeMsg2 = pendingCheckpointUserMessage;
                    pendingCheckpointSessionId = null;
                    pendingCheckpointUserMessage = null;
                    sessionId = resumeSid;
                    busy = true;
                    currentStart = System.currentTimeMillis();
                    IObject target = (IObject) getModule(serviceModule);
                    if (target == null) {
                        System.out.println("✗ 找不到 agentService 模块");
                        busy = false;
                        return null;
                    }
                    System.out.println("✓ 正在从断点恢复会话 " + resumeSid + " ...");
                    if (streamMode) {
                        // 流式恢复：与 submitChat 流式分支一致（chatStream + 同步 putMsg，chunk 经 STREAM_ONCHUNK 渲染）
                        System.out.print("AI > ");
                        System.out.flush();
                        TLMsg m = createMsg()
                                .setAction("chatStream")
                                .setParam(AI_P_SESSIONID, resumeSid)
                                .setParam("userId", userId)
                                .setParam(AI_P_USERMESSAGE, resumeMsg2)
                                .setParam("resume", true)
                                .setParam("streamTarget", getName())
                                .setParam("streamAction", STREAM_ONCHUNK);
                        putMsg(serviceModule, m);
                    } else {
                        TLMsg m = createMsg()
                                .setAction("chat")
                                .setParam(AI_P_SESSIONID, resumeSid)
                                .setParam("userId", userId)
                                .setParam(AI_P_USERMESSAGE, resumeMsg2)
                                .setParam("resume", true);
                        m.setSystemParam(TASKRESULTFOR, this);
                        m.setSystemParam(TASKRESULTACTION, "onChatDone");
                        TLMsg taskResult = putMsgNoWait(target, m);
                        currentTask = (ThreadTask) taskResult.getParam(THREADPOOL_TASK);
                    }
                    return null;
                }
                // 无断点 → 通过 agentService 查询 SessionManager
                msg.setAction("resume").setParam("userId", userId);
                break;

            case "sessions":
                msg.setAction("sessions").setParam("userId", userId);
                break;

            case "continue":
                msg.setAction("continue").setParam("userId", userId);
                if (parts.length > 1) msg.setParam(AI_P_SESSIONID, parts[1]);
                break;

            case "session":
                if (parts.length > 1) {
                    msg.setAction("session").setParam(AI_P_SESSIONID, parts[1]);
                } else {
                    System.out.println("用法: /session <id>");
                    return null;
                }
                break;

            case "sessiondel":
            case "sd":
                if (parts.length < 2) {
                    System.out.println("用法: /sessiondel <会话ID>");
                    return null;
                }
                msg.setAction("deleteSession")
                        .setParam(AI_P_SESSIONID, parts[1])
                        .setParam("userId", userId);
                break;

            case "agents":
                msg.setAction("agents");
                if (parts.length > 1) msg.setParam("filter", parts[1]);
                break;

            case "skills":
                msg.setAction("skills");
                if (parts.length > 1) msg.setParam("filter", parts[1]);
                break;

            case "install":
                if (parts.length < 2) {
                    System.out.println("用法: /install -s <skillDir> [家族名]");
                    System.out.println("      /install -a <name> [sameClassAs] [家族名]  (classRef默认aiagent)");
                    System.out.println("      /install -bs <name> <classFile> [家族名]");
                    return null;
                }
                msg.setAction("install");
                parseInstallUninstall(parts, msg);
                break;

            case "uninstall":
                if (parts.length < 2) {
                    System.out.println("用法: /uninstall -s <name> | -a <name> | -bs <name> [家族名]");
                    return null;
                }
                msg.setAction("uninstall");
                parseInstallUninstall(parts, msg);
                break;

            case "reload":
                if (parts.length < 2) {
                    System.out.println("用法: /reload -s <家族名> | -a <家族名> | -bs <家族名>");
                    return null;
                }
                msg.setAction("reload");
                parseInstallUninstall(parts, msg);
                break;

            case "approve":
                // /approve approve:ID[:modifiedArgs] 或 /approve reject:ID:原因
                if (parts.length < 2) {
                    System.out.println("格式: /approve approve:ID 或 /approve reject:ID:原因");
                    return null;
                }
                return parseApproveCommand(input);

            case "eval":
                msg.setAction("eval");
                parseEvalCommand(parts, msg);
                break;

            case "test":
                msg.setAction("test");
                parseTestCommand(parts, msg);
                break;

            case "trace":
                // /trace —— 环节骨架；/trace llm —— 完整 LLM 链路；/trace replay <N> —— 从第 N 个环节断点重放
                if (parts.length > 1 && "replay".equalsIgnoreCase(parts[1])) {
                    if (parts.length < 3) {
                        System.out.println("用法: /trace replay <环节序号N>（N 为 /trace 表格的 # 列，先 /trace 查看）");
                        return null;
                    }
                    int idx;
                    try {
                        idx = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException e) {
                        System.out.println("环节序号必须是数字: /trace replay <N>");
                        return null;
                    }
                    if (idx < 1) {
                        System.out.println("环节序号从 1 开始（/trace 表格的 # 列）");
                        return null;
                    }
                    return startTraceReplay(idx);
                }
                if (parts.length > 1 && "llm".equalsIgnoreCase(parts[1])) {
                    msg.setAction("traceLlm").setParam(AI_P_SESSIONID, sessionId);
                } else {
                    msg.setAction("trace").setParam(AI_P_SESSIONID, sessionId);
                }
                break;

            case "stats":
                // /stats —— Token 统计三段；/stats all —— 内存会话明细；/stats agent(s) —— 最后一轮各 agent 用量
                if (parts.length > 1 && "all".equalsIgnoreCase(parts[1])) {
                    msg.setAction("statsAll").setParam(AI_P_USERID, userId);
                } else if (parts.length > 1
                        && ("agent".equalsIgnoreCase(parts[1]) || "agents".equalsIgnoreCase(parts[1]))) {
                    msg.setAction("statsAgent").setParam(AI_P_SESSIONID, sessionId).setParam(AI_P_USERID, userId);
                } else if (parts.length > 1) {
                    System.out.println("未知子命令: /stats " + parts[1] + "（可用: /stats、/stats all、/stats agent）");
                    return null;
                } else {
                    msg.setAction("stats").setParam(AI_P_SESSIONID, sessionId).setParam(AI_P_USERID, userId);
                }
                break;

            case "mcp":
                msg = parseMcpCommand(parts);
                if (msg == null) return null;
                break;

            default:
                System.out.println("未知命令: /" + cmd);
                // 前缀匹配建议
                List<String> suggestions = new ArrayList<>();
                java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
                for (String c : commandCache.keySet()) {
                    if (c.toLowerCase().startsWith("/" + cmd) && seen.add(c)) {
                        suggestions.add(c);
                    }
                }
                if (!suggestions.isEmpty()) {
                    System.out.println("相近命令: " + String.join(", ", suggestions));
                }
                System.out.println("输入 /help 查看所有命令");
                return null;
        }
        return msg;
    }

    /** 解析 install/uninstall/reload 的公共 flag 参数 */
    /** 解析 install/uninstall/reload 的公共参数。
     *  -s: name [targetAgent]
     *  -a: name [classRef] [targetAgent]   classRef 默认 "aiagent"，含 "." 的 arg 视为 classRef
     *  -bs: name [classFile] [targetAgent]  classFile 默认需要显式指定
     */
    private void parseInstallUninstall(String[] parts, TLMsg msg) {
        String flag = parts[1];
        switch (flag) {
            case "-s":
                msg.setParam("type", "skill");
                if (parts.length > 2) msg.setParam("name", parts[2]);
                if (parts.length > 3) msg.setParam("targetAgent", parts[3]);
                break;
            case "-a": {
                msg.setParam("type", "agent");
                if (parts.length < 3) break;
                msg.setParam("name", parts[2]);
                // 智能解析剩余参数：含 "." 的是 classRef，否则是 targetAgent
                String classRef = "aiagent";  // 默认 sameClassAs 为 aiagent 基类
                String targetAgent = null;
                for (int i = 3; i < parts.length; i++) {
                    if (parts[i].contains(".")) {
                        classRef = parts[i];
                    } else {
                        targetAgent = parts[i];
                    }
                }
                msg.setParam("classRef", classRef);
                if (targetAgent != null) msg.setParam("targetAgent", targetAgent);
                break;
            }
            case "-bs": {
                msg.setParam("type", "baseSkill");
                if (parts.length < 3) break;
                msg.setParam("name", parts[2]);
                // 同 -a：含 "." 是 classFile，否则是 targetAgent
                String classFile = null;
                String targetAgent = null;
                for (int i = 3; i < parts.length; i++) {
                    if (parts[i].contains(".")) {
                        classFile = parts[i];
                    } else {
                        targetAgent = parts[i];
                    }
                }
                if (classFile != null) msg.setParam("classFile", classFile);
                if (targetAgent != null) msg.setParam("targetAgent", targetAgent);
                break;
            }
            default:
                System.out.println("未知flag: " + flag + "，可用: -s (Skill), -a (Agent), -bs (BaseSkill)");
                break;
        }
    }

    /** 解析 /approve approve:ID[:jsonArgs] 或 /approve reject:ID:原因 */
    private TLMsg parseApproveCommand(String input) {
        String payload = input.substring("/approve".length()).trim();
        if (payload.isEmpty()) return null;

        TLMsg msg = createMsg().setAction("approve");

        if (payload.startsWith("approve:")) {
            String[] subParts = payload.substring("approve:".length()).split(":", 2);
            msg.setParam("subAction", "approve");
            msg.setParam("approvalId", subParts[0].trim());
            if (subParts.length > 1 && !subParts[1].trim().isEmpty()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> modifiedArgs = new com.google.gson.Gson().fromJson(subParts[1].trim(), Map.class);
                    msg.setParam("approvalModifiedArguments", modifiedArgs);
                } catch (Exception ignored) {}
            }
        } else if (payload.startsWith("reject:")) {
            String[] subParts = payload.substring("reject:".length()).split(":", 2);
            msg.setParam("subAction", "reject");
            msg.setParam("approvalId", subParts[0].trim());
            msg.setParam("reason", subParts.length > 1 ? subParts[1].trim() : "用户拒绝");
        } else {
            System.out.println("未知审批操作: " + payload + "  (可用: approve:ID, reject:ID:原因)");
            return null;
        }
        return msg;
    }

    /** 解析 /eval suite|list|quick|run <id>|cascade [agent] */
    private void parseEvalCommand(String[] parts, TLMsg msg) {
        if (parts.length < 2) {
            msg.setParam("subAction", "help");
            return;
        }
        switch (parts[1]) {
            case "suite":
                msg.setParam("subAction", "suite");
                break;
            case "list":
                msg.setParam("subAction", "list");
                break;
            case "quick":
                msg.setParam("subAction", "quick");
                break;
            case "run":
                msg.setParam("subAction", "run");
                if (parts.length > 2) msg.setParam("caseId", parts[2]);
                break;
            case "cascade":
                msg.setParam("subAction", "cascade");
                if (parts.length > 2) msg.setParam("agent", parts[2]);
                break;
            default:
                System.out.println("用法: /eval suite|list|quick|run <id>|cascade [agent]");
                break;
        }
    }

    /**
     * 解析 /test 命令：/test [agent] [list|<用例名>]，空参数运行全部。
     * 不带 caseName 时 agentService 端运行 runAllTests。
     */
    private void parseTestCommand(String[] parts, TLMsg msg) {
        String arg = null;
        if (parts.length > 1) {
            arg = parts[1].toLowerCase();
            // /test agent <用例名> 与 /test <用例名> 等价
            if ("agent".equals(arg) && parts.length > 2) arg = parts[2].toLowerCase();
        }
        if (arg != null && !"agent".equals(arg)) msg.setParam("caseName", arg);
    }

    /**
     * 解析 /mcp 命令。
     * /mcp search [keyword]
     * /mcp install &lt;package&gt; [agentName] [--args ...]
     * /mcp list
     * /mcp remove &lt;name&gt;
     */
    private TLMsg parseMcpCommand(String[] parts) {
        TLMsg msg = createMsg();
        if (parts.length < 2) {
            System.out.println("MCP 服务器市场 — 搜索、安装、管理 MCP 工具包");
            System.out.println();
            System.out.println("  /mcp search [keyword]   搜索 MCP 服务器（空参数列出全部精选）");
            System.out.println("  /mcp info <package>     查看包详细信息（功能说明、工具列表、主页等）");
            System.out.println("  /mcp install <package> [name] [--args ...]  安装 MCP 服务器为子 Agent");
            System.out.println("  /mcp list               列出已安装的 MCP Agent 及其状态");
            System.out.println("  /mcp remove <name>      卸载 MCP Agent");
            System.out.println();
            System.out.println("  示例:");
            System.out.println("    /mcp search filesystem         # 搜索文件系统相关 MCP");
            System.out.println("    /mcp info @modelcontextprotocol/server-filesystem  # 查看这个包有什么工具");
            System.out.println("    /mcp install @modelcontextprotocol/server-filesystem  # 安装（自动命名）");
            System.out.println("    /mcp install mcp-server-fetch myFetch  # 安装并指定 Agent 名称");
            System.out.println("    /mcp list                      # 看看装了哪些");
            System.out.println("    /mcp remove filesystemMcp      # 卸载");
            return null;
        }
        String subCmd = parts[1].toLowerCase();
        switch (subCmd) {
            case "search":
                msg.setAction(MCP_SEARCH);
                if (parts.length > 2) msg.setParam(AI_P_MCPKEYWORD, parts[2]);
                break;
            case "install":
                if (parts.length < 3) {
                    System.out.println("用法: /mcp install <package> [agentName] [--args ...]");
                    return null;
                }
                msg.setAction(MCP_INSTALL);
                parseMcpInstall(parts, msg);
                break;
            case "list":
                msg.setAction(MCP_LIST);
                break;
            case "remove":
                msg.setAction(MCP_REMOVE);
                if (parts.length > 2) msg.setParam(AI_P_AGENTNAME, parts[2]);
                break;
            case "info":
                if (parts.length < 3) {
                    System.out.println("用法: /mcp info <package>");
                    return null;
                }
                msg.setAction(MCP_INFO);
                msg.setParam(AI_P_MCPPACKAGE, parts[2]);
                break;
            default:
                System.out.println("未知 mcp 子命令: " + subCmd);
                System.out.println("可用: search, install, list, remove, info");
                return null;
        }
        return msg;
    }

    /**
     * 解析 /mcp install &lt;package&gt; [agentName] [--args arg1 arg2 ...]
     */
    private void parseMcpInstall(String[] parts, TLMsg msg) {
        msg.setParam(AI_P_MCPPACKAGE, parts[2]);
        int argsFlagIdx = -1;
        for (int i = 3; i < parts.length; i++) {
            if ("--args".equals(parts[i])) {
                argsFlagIdx = i;
                break;
            }
            // 第一个非 flag 参数作为 agentName
            if (!parts[i].startsWith("-") && i == 3) {
                msg.setParam(AI_P_AGENTNAME, parts[i]);
            }
        }
        if (argsFlagIdx >= 0 && argsFlagIdx + 1 < parts.length) {
            StringBuilder extra = new StringBuilder();
            for (int i = argsFlagIdx + 1; i < parts.length; i++) {
                if (extra.length() > 0) extra.append(" ");
                extra.append(parts[i]);
            }
            msg.setParam(AI_P_MCPEXTRAARGS, extra.toString());
        }
    }

    /** 发送命令到 agentService 并打印结果 */
    private void executeServiceCommand(TLMsg msg) {
        if (msg == null) return;
        // 命令执行前的运行提示（/test /eval 等可能耗时）
        System.out.println("⏳ 执行中...");
        System.out.flush();

        // 特殊处理：/resume（无断点，回退到恢复最近会话）
        if ("resume".equals(msg.getAction()) && pendingCheckpointSessionId == null) {
            // 无断点：恢复最近已完成的会话
            TLMsg result = putMsg(serviceModule, createMsg().setAction("continue"));
            if (result != null && result.parseBoolean("success", false)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) result.getParam("data");
                if (data != null) {
                    String targetId = (String) data.get("sessionId");
                    int count = data.get("count") instanceof Integer ? (Integer) data.get("count") : 0;
                    sessionId = targetId;
                    System.out.println("✓ 会话 " + sessionId + " 已恢复 (" + count + " 条):");
                    // 打印历史
                    @SuppressWarnings("unchecked")
                    List<?> history = (List<?>) data.get("history");
                    if (history != null) {
                        for (Object h : history) {
                            if (h instanceof TLConversationHistory) {
                                TLConversationHistory ch = (TLConversationHistory) h;
                                String role = ch.getRole().name().toLowerCase();
                                String content = ch.getContent();
                                if (!"system".equals(role) && content != null) {
                                    System.out.println((role.equals("user") ? "你" : "AI") + " > " + content);
                                }
                            }
                        }
                    }
                    return;
                }
            }
            System.out.println("✗ 没有可恢复的之前会话");
            return;
        }

        TLMsg result = putMsg(serviceModule, msg);

        if (result == null) {
            System.out.println("✗ agentService 无响应");
            return;
        }

        boolean success = result.parseBoolean("success", false);
        String message = result.getStringParam("message", "");
        String error = result.getStringParam("error", null);

        if (success) {
            Object data = result.getParam("data");

            // 根据 action 类型格式化输出
            switch (msg.getAction()) {
                case "stopChat":
                    System.out.println("✓ " + message);
                    break;
                case "clear":
                    System.out.println("✓ 上下文已清除");
                    break;
                case "agents":
                case "skills":
                    printModuleList(data, msg.getAction());
                    break;
                case "trace":
                case "traceLlm":
                    // 结构化 StageRecord 列表，UI 层渲染表格（traceLlm 额外展示 payload 分块）
                    System.out.println(message);
                    if (data instanceof List) {
                        printTraceTable((List<?>) data, "traceLlm".equals(msg.getAction()));
                    }
                    break;
                case "stats":
                case "statsAll":
                case "statsAgent":
                    if (data instanceof List) {
                        for (Object l : (List<?>) data) System.out.println(l);
                    } else {
                        System.out.println("✓ " + message);
                    }
                    break;
                case "sessions":
                    printSessionList(data);
                    break;
                case "continue":
                    printContinueResult(data);
                    break;
                case "session":
                    String newId = data instanceof String ? (String) data : null;
                    if (newId != null) {
                        sessionId = newId;
                        System.out.println("✓ 会话ID切换为: " + sessionId);
                    }
                    break;
                case "approve":
                    System.out.println("✓ " + message);
                    break;
                case "eval":
                    printEvalResult(data);
                    break;
                case "test":
                    printTestResult(data);
                    break;
                case "param":
                    System.out.println(message);
                    if (data instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> params = (Map<String, Object>) data;
                        if (params.isEmpty()) {
                            System.out.println("  (无参数)");
                        } else {
                            for (Map.Entry<String, Object> e : params.entrySet()) {
                                System.out.println("  " + e.getKey() + " = " + e.getValue());
                            }
                        }
                    }
                    break;
                case "install":
                case "uninstall":
                case "reload":
                    System.out.println("✓ " + message);
                    break;
                case "mcpSearch":
                    printMcpSearchResult(data, message);
                    break;
                case "mcpList":
                    printMcpListResult(data, message);
                    break;
                case "mcpInfo":
                    printMcpInfoResult(data, message);
                    break;
                case "mcpInstall":
                case "mcpRemove":
                    System.out.println("✓ " + message);
                    break;
                default:
                    System.out.println("✓ " + message);
                    break;
            }
        } else {
            System.out.println("✗ " + (error != null ? error : message));
        }
    }

    @SuppressWarnings("unchecked")
    private void printModuleList(Object data, String type) {
        String label = "agents".equals(type) ? "Agent" : "Skill";
        if (!(data instanceof List)) return;
        List<?> rawList = (List<?>) data;
        if (rawList.isEmpty()) { System.out.println("(" + label + " 0)"); return; }
        System.out.println(label + " (" + rawList.size() + "):");

        // 兼容两种格式: agent的 {name, description} 和 registry的 {key, instance}
        Object first = rawList.get(0);
        if (first instanceof Map && ((Map<?, ?>) first).containsKey("name")) {
            // agent skill 格式
            for (Object obj : rawList) {
                Map<String, Object> m = (Map<String, Object>) obj;
                System.out.println("  - " + m.get("name") + ": " + m.getOrDefault("description", ""));
            }
        } else {
            // registry 格式
            for (Object obj : rawList) {
                Map<String, Object> m = (Map<String, Object>) obj;
                String key = (String) m.get(REGISTRY_P_KEY);
                String clazz = m.get(INSTANCE) != null ? m.get(INSTANCE).getClass().getSimpleName() : "?";
                System.out.println("  " + key + "  [" + clazz + "]");
            }
        }
    }

    /** 打印 MCP 搜索结果 */
    @SuppressWarnings("unchecked")
    private void printMcpSearchResult(Object data, String message) {
        System.out.println(message);
        if (!(data instanceof List)) return;
        List<?> list = (List<?>) data;
        if (list.isEmpty()) { System.out.println("  (无结果)"); return; }
        System.out.println("─".repeat(60));
        int idx = 1;
        for (Object obj : list) {
            if (!(obj instanceof Map)) continue;
            Map<String, Object> item = (Map<String, Object>) obj;
            String pkg = (String) item.get("package");
            String name = (String) item.get("name");
            String desc = (String) item.get("description");
            String cat = (String) item.get("category");
            String runtime = (String) item.get("runtime");
            String installCmd = (String) item.get("installCmd");
            String env = (String) item.get("env");

            System.out.printf("  %d. %s  [%s/%s]%n", idx++, name, runtime, cat);
            if (desc != null && !desc.isEmpty()) {
                System.out.println("     " + desc);
            }
            System.out.println("     包: " + pkg + "    安装: " + installCmd);
            if (env != null) {
                System.out.println("     环境变量: " + env);
            }
        }
        System.out.println("─".repeat(60));
        System.out.println("  使用 /mcp info <package> 查看详细功能说明");
    }

    /** 打印 MCP 包详情 */
    @SuppressWarnings("unchecked")
    private void printMcpInfoResult(Object data, String message) {
        if (!(data instanceof Map)) {
            System.out.println(message);
            return;
        }
        Map<String, Object> info = (Map<String, Object>) data;
        if (message != null && !message.isEmpty()) {
            System.out.println(message);
        }
        System.out.println("─".repeat(60));
        System.out.printf("  %s%n", info.getOrDefault("name", info.get("package")));
        System.out.printf("  包: %s%n", info.get("package"));
        if (info.containsKey("version")) {
            System.out.printf("  版本: %s%n", info.get("version"));
        }
        System.out.printf("  运行时: %s / %s%n",
                info.getOrDefault("runtime", "?"),
                info.getOrDefault("command", "?"));
        System.out.println();

        // 详细描述（可能多行）
        String desc = (String) info.get("description");
        if (desc != null && !desc.isEmpty()) {
            System.out.println("  【功能说明】");
            for (String line : desc.split("\n")) {
                System.out.println("  " + line);
            }
            System.out.println();
        }

        // 关键词
        String keywords = (String) info.get("keywords");
        if (keywords != null && !keywords.isEmpty()) {
            System.out.printf("  关键词: %s%n", keywords);
        }

        // 工具列表
        String tools = (String) info.get("tools");
        if (tools != null && !tools.isEmpty()) {
            System.out.println("  【主要工具】");
            for (String line : tools.split("\n")) {
                System.out.println("    " + line);
            }
        }

        // 链接
        if (info.containsKey("homepage")) {
            System.out.printf("  主页: %s%n", info.get("homepage"));
        }
        if (info.containsKey("repository")) {
            System.out.printf("  仓库: %s%n", info.get("repository"));
        }

        // 环境变量
        if (info.containsKey("env")) {
            System.out.printf("  环境变量: %s%n", info.get("env"));
        }
        System.out.println("─".repeat(60));
    }

    /** 打印已安装 MCP Agent 列表 */
    @SuppressWarnings("unchecked")
    private void printMcpListResult(Object data, String message) {
        System.out.println(message);
        if (!(data instanceof List)) return;
        List<?> list = (List<?>) data;
        if (list.isEmpty()) { System.out.println("  (无已安装的 MCP Agent)"); return; }
        for (Object obj : list) {
            if (!(obj instanceof Map)) continue;
            Map<String, Object> item = (Map<String, Object>) obj;
            // 展示 familyName 而非短名，让用户能区分不同父 Agent 下的同名 MCP
            String displayName = (String) item.getOrDefault("familyName", item.get("name"));
            boolean initialized = item.get("initialized") instanceof Boolean
                    && (boolean) item.get("initialized");
            System.out.printf("  %s  [%s]  %s%n",
                    displayName,
                    initialized ? "OK" : "--",
                    item.get("description"));
        }
    }

    @SuppressWarnings("unchecked")
    private void printSessionList(Object data) {
        if (!(data instanceof List)) { System.out.println("（无历史会话）"); return; }
        List<?> sessions = (List<?>) data;
        if (sessions.isEmpty()) { System.out.println("（无历史会话）"); return; }
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
        System.out.println();
        int i = 1;
        for (Object obj : sessions) {
            Map<String, Object> s = (Map<String, Object>) obj;
            String sid = s.get("sessionId") != null ? s.get("sessionId").toString() : "?";
            String agent = s.get("agentName") != null ? s.get("agentName").toString() : "";
            String state = s.get("state") != null ? s.get("state").toString() : "?";
            String userMsg = s.get("userMessage") != null ? s.get("userMessage").toString() : "";
            long savedAt = 0;
            if (s.get("savedAt") instanceof Long) savedAt = (Long) s.get("savedAt");
            int count = 0;
            if (s.get("count") instanceof Integer) count = (Integer) s.get("count");
            String timeStr = savedAt > 0 ? sdf.format(new java.util.Date(savedAt)) : "未知";
            String preview = userMsg.length() > 40 ? userMsg.substring(0, 40) + "..." : userMsg;
            String marker = sid.equals(sessionId) ? " ← 当前" : "";
            String stateTag = "completed".equals(state) ? "" : " [" + state + "]";
            String agentTag = !agent.isEmpty() ? " [" + agent + "]" : "";
            System.out.println("  " + i + ". " + sid + agentTag + "  " + timeStr + "  \"" + preview
                    + "\"  " + count + "条" + stateTag + marker);
            i++;
        }
        System.out.println();
        System.out.println("使用 /continue <id> 继续某个会话，或 /continue 恢复最近会话");
    }

    @SuppressWarnings("unchecked")
    private void printContinueResult(Object data) {
        if (!(data instanceof Map)) return;
        Map<String, Object> d = (Map<String, Object>) data;
        String targetId = (String) d.get("sessionId");
        int count = d.get("count") instanceof Integer ? (Integer) d.get("count") : 0;
        sessionId = targetId;
        System.out.println("✓ 已恢复会话 " + targetId + " (" + count + " 条历史)，继续聊吧");
        List<?> history = (List<?>) d.get("history");
        if (history != null) {
            for (Object h : history) {
                if (h instanceof TLConversationHistory) {
                    TLConversationHistory ch = (TLConversationHistory) h;
                    String role = ch.getRole().name().toLowerCase();
                    String content = ch.getContent();
                    if (!"system".equals(role) && content != null) {
                        System.out.println((role.equals("user") ? "你" : "AI") + " > " + content);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void printEvalResult(Object data) {
        // /eval list 返回的是用例清单数组（不是 Map），直接逐行打
        if (data instanceof List) {
            for (Object line : (List<Object>) data) System.out.println("  " + line);
            return;
        }
        if (!(data instanceof Map)) return;
        Map<String, Object> d = (Map<String, Object>) data;
        Object passed = d.get("passed");
        Object total = d.get("total");
        Object passRate = d.get("passRate");
        String reportPath = (String) d.get("reportPath");
        System.out.println("✓ 评测完成: " + passed + "/" + total + " 通过"
                + (passRate instanceof Double ? String.format(" (%.1f%%)", (Double) passRate * 100) : ""));
        if (reportPath != null && !reportPath.isEmpty()) System.out.println("  报告: " + reportPath);
        printBaseline(d);
    }

    /** 基线对比一行：让"新挂了哪几条"直接显示出来，而不是只知道通过率变低了 */
    @SuppressWarnings("unchecked")
    private void printBaseline(Map<String, Object> d) {
        // Boolean true 与字符串 "true" 都认（走 webui 的 JSON 通道时类型会变）
        if (!"true".equals(String.valueOf(d.get("baselineFound")))) {
            System.out.println("  基线: 未找到历史报告，本次无基准可比");
            return;
        }
        System.out.println("  基线: " + d.get("baselineFile")
                + " | 可比 " + d.get("baselineCompared") + " 条"
                + " | 回归 " + d.get("regressionCount")
                + " | 改善 " + d.get("improvementCount"));
        Object regressions = d.get("regressions");
        if (regressions instanceof List) {
            for (Object r : (List<Object>) regressions) System.out.println("    [回归] " + r);
        }
    }

    /** 打印 /test 结果：list 为 List<String>，运行为 {passed, failed, total} */
    @SuppressWarnings("unchecked")
    private void printTestResult(Object data) {
        if (data instanceof List) {
            List<String> cases = (List<String>) data;
            System.out.println("可用测试用例 (" + cases.size() + "):");
            for (String c : cases) System.out.println("  " + c);
        } else if (data instanceof Map) {
            Map<String, Object> r = (Map<String, Object>) data;
            Object passed = r.get("passed");
            Object failed = r.get("failed");
            Object total = r.get("total");
            System.out.println("✓ 测试完成: " + passed + "/" + total + " 通过"
                    + ((failed instanceof Integer && (Integer) failed > 0) ? "，失败 " + failed : ""));
        }
    }

    // ======================== ESC / 暂停 / 中断 ========================

    /** ESC 三段式（主线程） */
    private void handleStopSignal() {
        if (!busy) return;
        long now = System.currentTimeMillis();
        if (!paused) {
            stopThinking();
            if (currentTask != null) currentTask.pauseTask();
            paused = true;
            lastEscTime = now;
            System.out.println();
            System.out.print("⏸ 已暂停（再按 ESC 恢复，连按两下中断）");
            System.out.flush();
        } else if (now - lastEscTime <= DOUBLE_ESC_WINDOW) {
            stopThinking();
            paused = false;
            if (currentTask != null) currentTask.cancelTask();
            stopChat();
            drainInput = true;
        } else {
            if (currentTask != null) currentTask.resumeTask();
            paused = false;
            System.out.println();
            System.out.print("▶ 已恢复");
            System.out.flush();
        }
    }

    private void sendStopSignal() {
        offer(new ConsoleEvent(EventType.STOP, null, null));
    }

    // ======================== Chat 交互 ========================

    /** 异步提交 chat */
    /** /trace replay <N>：从最新一轮第 N 个环节断点重放（异步执行，结果经 onChatDone 渲染） */
    private TLMsg startTraceReplay(int idx) {
        if (busy) {
            System.out.println("⏳ 运行中，请等待当前任务完成");
            return null;
        }
        busy = true;
        currentStart = System.currentTimeMillis();
        IObject target = (IObject) getModule(serviceModule);
        if (target == null) {
            System.out.println("✗ 找不到 agentService 模块");
            busy = false;
            return null;
        }
        System.out.println("✓ 正在从环节 " + idx + " 重放...");
        TLMsg m = createMsg()
                .setAction("traceReplay")
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam("userId", userId)
                .setParam("index", idx);
        m.setSystemParam(TASKRESULTFOR, this);
        m.setSystemParam(TASKRESULTACTION, "onChatDone");
        TLMsg taskResult = putMsgNoWait(target, m);
        currentTask = (ThreadTask) taskResult.getParam(THREADPOOL_TASK);
        // 等待期间显示思考动画（结果到达时 stopThinking 清行）
        startThinking("AI >");
        return null;
    }

    private void submitChat(String input) {
        TLMsg msg;
        if (streamMode) {
            System.out.print("AI > ");
            System.out.flush();
            msg = createMsg().setAction("chatStream")
                    .setParam(AI_P_SESSIONID, sessionId)
                    .setParam("userId", userId)
                    .setParam(AI_P_USERMESSAGE, input)
                    .setParam("streamTarget", getName())
                    .setParam("streamAction", STREAM_ONCHUNK);
        } else {
            msg = createMsg().setAction("chat")
                    .setParam(AI_P_SESSIONID, sessionId)
                    .setParam("userId", userId)
                    .setParam(AI_P_USERMESSAGE, input);
            msg.setSystemParam(TASKRESULTFOR, this);
            msg.setSystemParam(TASKRESULTACTION, "onChatDone");
        }
        if (reasoningMode != null) msg.setParam(AI_P_REASONING_MODE, reasoningMode);

        if (streamMode) {
            putMsg(serviceModule, msg);
        } else {
            IObject target = (IObject) getModule(serviceModule);
            if (target != null) {
                TLMsg taskResult = putMsgNoWait(target, msg);
                currentTask = (ThreadTask) taskResult.getParam(THREADPOOL_TASK);
                // 等待期间显示思考动画（结果到达时 stopThinking 清行）
                startThinking("AI >");
            } else {
                System.out.println("AI > [错误] 找不到 agentService 模块");
                busy = false;
                printPrompt();
                System.out.flush();
            }
        }
    }

    // ======================== 思考动画 ========================

    /** 启动旋转思考动画（\r 重写当前行；busy 结束/中断时调用 stopThinking 停止并清行） */
    private void startThinking(String prefix) {
        stopThinking();
        thinking = true;
        final String[] frames = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
        thinkingThread = new Thread(() -> {
            int i = 0;
            try {
                while (thinking) {
                    System.out.print("\r" + prefix + " " + frames[i % frames.length] + " 思考中");
                    System.out.flush();
                    i++;
                    Thread.sleep(200);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }, "chat-thinking");
        thinkingThread.setDaemon(true);
        thinkingThread.start();
    }

    /** 停止思考动画并清空当前行 */
    private void stopThinking() {
        thinking = false;
        if (thinkingThread != null) {
            thinkingThread.interrupt();
            thinkingThread = null;
        }
        System.out.print("\r\033[K");
        System.out.flush();
    }

    /** 停止对话 */
    private void stopChat() {
        putMsg(serviceModule, createMsg().setAction("stopChat")
                .setParam(AI_P_SESSIONID, sessionId));
    }

    // ======================== 结果渲染 ========================

    /** 非流式结果事件（主线程打印） */
    private void onChatResult(TLMsg response) {
        stopThinking();
        if (response != null) {
            String aiResponse = response.getStringParam(AI_P_RESPONSE, "");
            boolean cancelled = response.parseBoolean(AI_P_CANCELLED, false);
            boolean success = response.parseBoolean("success", false);
            if (success && !aiResponse.isEmpty()) {
                String reasoning = response.getStringParam(AI_P_REASONING, null);
                if (reasoning != null && !reasoning.isEmpty()) {
                    displayReasoning(reasoning);
                }
                System.out.println("AI > " + aiResponse);
                if (cancelled) {
                    System.out.println("    (" + (System.currentTimeMillis() - currentStart) + "ms)");
                } else {
                    int pt = response.getIntParam(AI_P_PROMPTTOKENS, 0);
                    int ct = response.getIntParam(AI_P_COMPLETIONTOKENS, 0);
                    int tt = response.getIntParam(AI_P_TOTALTOKENS, 0);
                    int accTotal = response.getIntParam(AI_P_TOTALTOKENS_TOTAL, 0);
                    int cacheHit = response.getIntParam(AI_P_CACHEHITTOKENS, 0);
                    int cacheMiss = response.getIntParam(AI_P_CACHEMISSTOKENS, 0);
                    int cacheCreate = response.getIntParam(AI_P_CACHECREATIONTOKENS, 0);
                    // 本轮完整用量（含 workflow/group 成员）：monitor 最后一轮汇总优先，不可用/stale 时回退 agent 自身值。
                    // 注意 monitor 存的是 Long——getIntParam 只认 Integer 会静默返回 0，必须用 getLongParam
                    TLMsg roundUsage = queryLastRoundUsage();
                    if (roundUsage != null && roundUsage.parseBoolean("found", false)
                            && !roundUsage.parseBoolean("stale", false)) {
                        pt = roundUsage.getLongParam(AI_P_PROMPTTOKENS, 0L).intValue();
                        ct = roundUsage.getLongParam(AI_P_COMPLETIONTOKENS, 0L).intValue();
                        tt = roundUsage.getLongParam(AI_P_TOTALTOKENS, 0L).intValue();
                        cacheHit = roundUsage.getLongParam(AI_P_CACHEHITTOKENS, 0L).intValue();
                        cacheMiss = roundUsage.getLongParam(AI_P_CACHEMISSTOKENS, 0L).intValue();
                    }
                    // 会话累计统一根会话口径（含成员消耗，与 /stats 当前会话一致）；monitor 不可用时回退 agent 自身累计
                    TLMsg sessUsage = querySessionTokenUsage();
                    if (sessUsage != null && sessUsage.parseBoolean("found", false)) {
                        accTotal = sessUsage.getLongParam(AI_P_TOTALTOKENS, 0L).intValue();
                    }
                    String tokenInfo = tt > 0
                            ? "，tokens 输入 " + pt + "/输出 " + ct + "/合计 " + tt + "，会话累计 " + accTotal
                            : "";
                    StringBuilder cacheInfo = new StringBuilder();
                    long cacheTotal = cacheHit + cacheMiss;
                    if (cacheTotal > 0) {
                        cacheInfo.append(" | 缓存: 命中 ").append(cacheHit)
                                .append("/未命中 ").append(cacheMiss)
                                .append(" (").append(String.format("%.0f%%", 100.0 * cacheHit / cacheTotal)).append(")");
                    } else if (cacheCreate > 0) {
                        cacheInfo.append(" | 缓存: 写入 ").append(cacheCreate);
                    } else {
                        cacheInfo.append(" | 缓存: —");
                    }
                    if (cacheCreate > 0 && cacheTotal > 0) {
                        cacheInfo.append(" 写入 ").append(cacheCreate);
                    }
                    TLMsg procUsage = queryProcessTokenUsage();
                    if (procUsage != null) {
                        tokenInfo += "，总累计 " + procUsage.getLongParam(AI_P_TOTALTOKENS_PROCESS, 0L);
                    }
                    System.out.println("    (" + (System.currentTimeMillis() - currentStart) + "ms" + tokenInfo + cacheInfo + ")");
                }
            } else {
                System.out.println("AI > [错误] " + (aiResponse.isEmpty() ? "空响应" : aiResponse));
            }
        }
        busy = false;
        currentTask = null;
        paused = false;
        System.out.println();
        printPrompt();
        System.out.flush();
        flushTerminal();
    }

    /**
     * 强制 JLine 终端刷新显示。raw 模式下 reader 线程阻塞在终端输入时，
     * 异步打印的内容可能被 Windows 控制台搁置，直到下次按键事件才刷出
     * （表现为"结果按回车才出来"）——此处强制 push 待显示的输出。
     */
    private void flushTerminal() {
        try {
            if (terminal != null) terminal.flush();
        } catch (Exception ignore) {}
    }

    /** 查询进程级 token 总累计（agentMonitor 未注册时返回 null，静默降级） */
    private TLMsg queryProcessTokenUsage() {
        try {
            TLMsg m = createMsg().setAction(MONITOR_GETPROCESSTOKENUSAGE);
            m.setSystemParam(IGNOREMODULEISNULL, true);
            return putMsg(M_AGENTMONITOR, m);
        } catch (Exception e) { return null; }
    }

    /** 查询当前会话的 token 累计（根会话口径：主 agent + workflow/group 成员，与 /stats 当前会话一致；不可用时返回 null 静默降级） */
    private TLMsg querySessionTokenUsage() {
        try {
            TLMsg m = createMsg().setAction(MONITOR_GETSESSIONTOKENUSAGE)
                    .setParam(AI_P_SESSIONID, sessionId);
            m.setSystemParam(IGNOREMODULEISNULL, true);
            return putMsg(M_AGENTMONITOR, m);
        } catch (Exception e) { return null; }
    }

    /** 查询当前会话最后一轮的完整用量（根会话口径，含成员；found=false/stale 时返回 null 表示降级用 agent 自身值） */
    private TLMsg queryLastRoundUsage() {
        try {
            TLMsg m = createMsg().setAction(MONITOR_GETLASTROUNDAGENTUSAGE)
                    .setParam(AI_P_ROOTSESSIONID, sessionId);
            m.setSystemParam(IGNOREMODULEISNULL, true);
            TLMsg r = putMsg(M_AGENTMONITOR, m);
            return (r != null && r.parseBoolean("found", false)) ? r : null;
        } catch (Exception e) { return null; }
    }

    /** 流式 chunk 回调（非主线程）：拆成 CHUNK / STREAM_END 事件入队 */
    private void onStreamChunkEvent(TLMsg msg) {
        if (msg.containsParam(AI_P_CHUNK)) {
            String piece = msg.getStringParam(AI_P_CHUNK, "");
            if (!piece.isEmpty()) offer(new ConsoleEvent(EventType.CHUNK, piece, null));
        }
        boolean done = msg.parseBoolean(AI_P_STREAMDONE, false);
        boolean hasErr = msg.containsParam(AI_P_STREAMERROR);
        if (done || hasErr) {
            String err = hasErr ? msg.getStringParam(AI_P_STREAMERROR, "") : null;
            TLMsg endMsg = null;
            if (msg.containsParam(AI_P_REASONING)) {
                endMsg = createMsg().setParam(AI_P_REASONING, msg.getStringParam(AI_P_REASONING, ""));
            }
            offer(new ConsoleEvent(EventType.STREAM_END, err, endMsg));
        }
    }

    /** 流式结束事件（主线程收尾） */
    private void onStreamEnd(String error, String reasoning) {
        stopThinking();
        if (reasoning != null && !reasoning.isEmpty()) {
            displayReasoning(reasoning);
        }
        System.out.println();
        if (error != null && !error.isEmpty()) {
            System.out.println("    (⏹ 已结束: " + (error.length() > 80 ? error.substring(0, 80) + "..." : error) + ")");
        } else {
            TLMsg usage = putMsg(serviceModule, createMsg().setAction("getTokenUsage")
                    .setParam(AI_P_SESSIONID, sessionId));
            int accTotal = 0;
            if (usage != null && usage.parseBoolean("success", false)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ud = (Map<String, Object>) usage.getParam("data");
                if (ud != null && ud.get(AI_P_TOTALTOKENS_TOTAL) instanceof Integer) {
                    accTotal = (Integer) ud.get(AI_P_TOTALTOKENS_TOTAL);
                }
            }
            // 会话累计统一根会话口径（含成员消耗，与 /stats 当前会话一致）；monitor 不可用时回退 agent 自身累计
            TLMsg sessUsage = querySessionTokenUsage();
            if (sessUsage != null && sessUsage.parseBoolean("found", false)) {
                accTotal = sessUsage.getLongParam(AI_P_TOTALTOKENS, 0L).intValue();
            }
            String tokenInfo = accTotal > 0 ? "，会话累计 tokens " + accTotal : "";
            TLMsg procUsage = queryProcessTokenUsage();
            if (procUsage != null) {
                tokenInfo += "，总累计 " + procUsage.getLongParam(AI_P_TOTALTOKENS_PROCESS, 0L);
            }

            TLMsg cacheStats = putMsg(serviceModule, createMsg().setAction("getCacheStats")
                    .setParam(AI_P_SESSIONID, sessionId));
            StringBuilder cacheInfo = new StringBuilder();
            if (cacheStats != null && cacheStats.parseBoolean("success", false)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cd = (Map<String, Object>) cacheStats.getParam("data");
                if (cd != null) {
                    int ch = cd.get(AI_P_CACHEHITTOKENS_TOTAL) instanceof Integer ? (Integer) cd.get(AI_P_CACHEHITTOKENS_TOTAL) : 0;
                    int cm = cd.get(AI_P_CACHEMISSTOKENS_TOTAL) instanceof Integer ? (Integer) cd.get(AI_P_CACHEMISSTOKENS_TOTAL) : 0;
                    long cacheTotal = ch + cm;
                    if (cacheTotal > 0) {
                        cacheInfo.append(" | 缓存: ").append(cd.getOrDefault("cacheHitRate", "0%"))
                                .append(" (命中").append(ch).append("/未命中").append(cm).append(")");
                    } else {
                        cacheInfo.append(" | 缓存: —");
                    }
                }
            } else {
                cacheInfo.append(" | 缓存: —");
            }
            System.out.println("    (" + (System.currentTimeMillis() - currentStart) + "ms" + tokenInfo + cacheInfo + ")");
        }
        busy = false;
        currentTask = null;
        paused = false;
        System.out.println();
        printPrompt();
        System.out.flush();
        flushTerminal();
    }

    /** 显示推理过程（折叠/展开） */
    private void displayReasoning(String reasoning) {
        if (reasoning == null || reasoning.isEmpty()) return;
        String[] lines = reasoning.split("\n");
        int stepCount = 0;
        for (String line : lines) {
            if (line.trim().startsWith("💭")) stepCount++;
        }
        if (stepCount == 0) stepCount = lines.length;
        if (reasoningCollapsed) {
            String preview = reasoning.length() > 80
                    ? reasoning.substring(0, 80).replace("\n", " ") + "..."
                    : reasoning.replace("\n", " ");
            System.out.println("   💭 推理过程 (" + stepCount + "步): " + preview + "  [/thinking 展开]");
        } else {
            System.out.println("   💭 推理过程 (" + stepCount + "步):");
            for (String line : lines) {
                System.out.println("      " + line.trim());
            }
            System.out.println("   ---");
        }
    }

    // ======================== 终端辅助 ========================

    private void printPrompt() { System.out.print(prompt); }

    /** Tab 命令补全 */
    private void handleTabComplete(StringBuilder line) {
        String current = line.toString();
        if (current.isEmpty() || !current.startsWith("/")) return;

        String prefix = current.toLowerCase();
        List<Map.Entry<String, String>> matches = new ArrayList<>();
        for (Map.Entry<String, String> e : commandCache.entrySet()) {
            if (e.getKey().toLowerCase().startsWith(prefix)) {
                matches.add(e);
            }
        }
        if (matches.isEmpty()) return;

        if (matches.size() == 1) {
            String full = matches.get(0).getKey();
            if (full.equals(current)) return;
            String suffix = full.substring(current.length());
            line.setLength(0);
            line.append(full);
            System.out.print(suffix);
            System.out.flush();
        } else {
            System.out.print("\r\n");
            int maxLen = 0;
            for (Map.Entry<String, String> m : matches) {
                if (m.getKey().length() > maxLen) maxLen = m.getKey().length();
            }
            for (Map.Entry<String, String> m : matches) {
                System.out.print("  " + padRight(m.getKey(), maxLen + 2) + m.getValue() + "\r\n");
            }
            System.out.print("\r\n");
            System.out.print(prompt);
            System.out.print(current);
            System.out.flush();
        }
    }

    private static String padRight(String s, int n) {
        if (s.length() >= n) return s;
        StringBuilder sb = new StringBuilder(n);
        sb.append(s);
        for (int i = s.length(); i < n; i++) sb.append(' ');
        return sb.toString();
    }

    // ======================== /trace 表格渲染（UI 层表现，服务层只给结构化 StageRecord） ========================

    /** 环节英文 → 中文标签 */
    private static final java.util.Map<String, String> STAGE_CN = new java.util.HashMap<>();
    static {
        STAGE_CN.put("roundStart", "轮次开始");
        STAGE_CN.put("llmRequest", "发送LLM");
        STAGE_CN.put("llmResponse", "LLM返回");
        STAGE_CN.put("toolStart", "工具开始");
        STAGE_CN.put("toolEnd", "工具结束");
        STAGE_CN.put("approvalRequested", "请求审批");
        STAGE_CN.put("roundEnd", "轮次结束");
    }

    /** 渲染最新一轮环节表格（显示宽度对齐）；withPayload=true 时表格下方按环节编号分块展示完整内容载荷 */
    private static void printTraceTable(List<?> stages, boolean withPayload) {
        if (stages == null || stages.isEmpty()) return;
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss.SSS");
        List<TLAgentMonitor.StageRecord> recs = new java.util.ArrayList<>();
        List<String> timeStrs = new java.util.ArrayList<>();
        List<String> stageStrs = new java.util.ArrayList<>();
        List<String> durStrs = new java.util.ArrayList<>();
        for (Object o : stages) {
            if (!(o instanceof TLAgentMonitor.StageRecord)) continue;
            TLAgentMonitor.StageRecord rec = (TLAgentMonitor.StageRecord) o;
            recs.add(rec);
            timeStrs.add(sdf.format(new java.util.Date(rec.ts)));
            stageStrs.add(STAGE_CN.getOrDefault(rec.stage, rec.stage));
            durStrs.add(rec.durationMs > 0 ? rec.durationMs + "ms" : "");
        }
        int n = recs.size();
        if (n == 0) return;

        // 列宽：序号/时间固定，其余按内容自适应（设上限，防超长拖垮表格）
        String[] headers = {"#", "时间", "环节", "Agent", "耗时", "摘要"};
        int[] w = new int[6];
        w[0] = Math.max(headers[0].length(), String.valueOf(n).length());
        w[1] = 12; // HH:mm:ss.SSS
        int maxStage = 0, maxAgent = 0, maxDur = 0, maxDetail = 0;
        for (int i = 0; i < n; i++) {
            maxStage = Math.max(maxStage, displayWidth(stageStrs.get(i)));
            maxAgent = Math.max(maxAgent, displayWidth(recs.get(i).agentName));
            maxDur = Math.max(maxDur, durStrs.get(i).length());
            maxDetail = Math.max(maxDetail, displayWidth(recs.get(i).detail));
        }
        w[2] = Math.max(displayWidth(headers[2]), Math.min(maxStage, 12));
        w[3] = Math.max(displayWidth(headers[3]), Math.min(maxAgent, 14));
        w[4] = Math.max(displayWidth(headers[4]), Math.min(maxDur, 10));
        w[5] = Math.max(displayWidth(headers[5]), Math.min(maxDetail, 60));

        // 框线（与启动 banner 的 Unicode 风格一致）
        StringBuilder top = new StringBuilder("┌"), mid = new StringBuilder("├"), bot = new StringBuilder("└");
        for (int col = 0; col < 6; col++) {
            String dash = "─".repeat(w[col] + 2);
            top.append(dash).append(col < 5 ? "┬" : "┐");
            mid.append(dash).append(col < 5 ? "┼" : "┤");
            bot.append(dash).append(col < 5 ? "┴" : "┘");
        }
        System.out.println(top);
        StringBuilder head = new StringBuilder("│");
        for (int col = 0; col < 6; col++) {
            head.append(' ').append(padDisplay(headers[col], w[col])).append(' ').append('│');
        }
        System.out.println(head);
        System.out.println(mid);
        for (int i = 0; i < n; i++) {
            TLAgentMonitor.StageRecord rec = recs.get(i);
            String[] cells = {
                    padDisplay(String.valueOf(i + 1), w[0]),
                    padDisplay(timeStrs.get(i), w[1]),
                    padDisplay(stageStrs.get(i), w[2]),
                    padDisplay(rec.agentName == null ? "" : rec.agentName, w[3]),
                    padDisplay(durStrs.get(i), w[4]),
                    padDisplay(truncateDisplay(rec.detail == null ? "" : rec.detail, w[5]), w[5])
            };
            StringBuilder row = new StringBuilder("│");
            for (int col = 0; col < 6; col++) {
                row.append(' ').append(cells[col]).append(' ').append('│');
            }
            System.out.println(row);
        }
        System.out.println(bot);

        // payload 分块（完整内容不截断，保持链路可见）
        if (withPayload) {
            for (int i = 0; i < n; i++) {
                TLAgentMonitor.StageRecord rec = recs.get(i);
                if (rec.payload == null || rec.payload.isEmpty()) continue;
                System.out.println();
                System.out.println("[" + (i + 1) + "] " + stageStrs.get(i) + " - " + rec.detail + ":");
                for (String pl : rec.payload.split("\n", -1)) {
                    System.out.println("    " + pl);
                }
            }
        }
    }

    /** 显示宽度：全角计 2、半角计 1（复用 isFullWidthChar 的 Unicode 区间） */
    private static int displayWidth(String s) {
        if (s == null) return 0;
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += isFullWidthChar(s.charAt(i)) ? 2 : 1;
        }
        return w;
    }

    /** 按显示宽度补空格到 w（不足时；超宽不截断） */
    private static String padDisplay(String s, int w) {
        int cur = displayWidth(s);
        if (cur >= w) return s;
        StringBuilder sb = new StringBuilder(s);
        for (int i = cur; i < w; i++) sb.append(' ');
        return sb.toString();
    }

    /** 按显示宽度截断到 w（末尾省略号，避免劈半全角字符） */
    private static String truncateDisplay(String s, int w) {
        if (displayWidth(s) <= w) return s;
        StringBuilder sb = new StringBuilder();
        int cur = 0;
        for (int i = 0; i < s.length() && cur < w - 1; i++) {
            char c = s.charAt(i);
            int cw = isFullWidthChar(c) ? 2 : 1;
            if (cur + cw > w - 1) break;
            sb.append(c);
            cur += cw;
        }
        return sb + "…";
    }

    private void offer(ConsoleEvent e) {
        try { eventQueue.put(e); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    // ======================== 初始化 ========================

    /** 从 agentService 获取命令列表用于 Tab 补全 */
    @SuppressWarnings("unchecked")
    private void populateCommandCache() {
        // 本地命令（不在 agentService 的 ACTION_REGISTRY 中，控制台自己处理）
        commandCache.put("/exit", "退出控制台");
        commandCache.put("/quit", "退出控制台");
        commandCache.put("/stream", "切换流式/非流式模式");
        commandCache.put("/thinking", "设置推理模式 (off|prompt|native|auto)");
        commandCache.put("/help", "显示帮助信息");
        commandCache.put("/?", "显示帮助信息");
        commandCache.put("/mcp", "MCP 服务器市场 (search/info/install/list/remove)");
        commandCache.put("/sessiondel", "删除会话记录（不可恢复）");

        try {
            TLMsg result = putMsg(serviceModule, createMsg().setAction("listCommands"));
            if (result != null && result.parseBoolean("success", false)) {
                List<Map<String, String>> cmds = (List<Map<String, String>>) result.getParam("data");
                if (cmds != null) {
                    for (Map<String, String> c : cmds) {
                        String action = c.get("action");
                        String desc = c.get("description");
                        if (action != null) {
                            commandCache.put("/" + action, desc != null ? desc : "");
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 获取失败时本地命令仍可用
        }
    }

    /** 打印已注册 Skill */
    @SuppressWarnings("unchecked")
    /** 启动时打印已注册 Skill 的友好描述（直接查 agent，不走 registry 格式） */
    private void printSkills() {
        TLMsg result = putMsg(agentModule, createMsg().setAction(AGENT_LISTSKILLS));
        if (result != null) {
            java.util.List<String> skills = (java.util.List<String>) result.getListParam("skills", java.util.List.of());
            if (skills.isEmpty()) {
                System.out.println("⚠ 警告: 没有注册任何Skill！Tool Call功能不可用。");
            } else {
                System.out.println("已注册的Skill (" + skills.size() + "个):");
                for (String name : skills) {
                    System.out.println("  - " + name);
                }
            }
        }
    }

    /** 打印帮助 */
    private void handleHelp() {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║            AI Agent 控制台命令帮助              ║");
        System.out.println("╚══════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("对话命令:");
        System.out.println("  /exit, /quit   退出控制台");
        System.out.println("  /stop          中断当前正在运行的对话");
        System.out.println("  ESC            按一下暂停，再按恢复；快速连按两下（500ms 内）中断（同 /stop）");
        System.out.println("  ↑/↓ 方向键     翻阅输入历史（命令与会话内容均可回翻）");
        System.out.println("  意图缓存       重复任务首次由 LLM 路由并自动缓存，之后直接执行（provider 配 intentCacheProvider）");
        System.out.println("  /stream        切换流式/非流式模式");
        System.out.println("  /thinking      切换推理过程折叠/展开（/thinking off|prompt|native|auto）");
        System.out.println();
        System.out.println("会话命令:");
        System.out.println("  /sessions          列出所有历史会话");
        System.out.println("  /continue [id]     继续某个历史会话（加载历史记忆，接着上次聊；不带 id 则恢复最近）");
        System.out.println("  /session <id>      切换会话ID（只换记录归属，不加载历史；新 id=开新会话，已存在的 id 会追加写入）");
        System.out.println("  /sessiondel <id>   删除会话记录（DB/文件，不可恢复；别名 /sd）");
        System.out.println("  /clear             清除当前会话上下文");
        System.out.println("  /resume            恢复未完成的 mid-loop 断点会话");
        System.out.println();
        System.out.println("安装命令:");
        System.out.println("  /install -s <skillDir> [家族名]              安装脚本型Skill（目录）");
        System.out.println("  /install -a <name> [sameClassAs] [家族名]    安装Agent（classRef默认aiagent）");
        System.out.println("  /install -bs <skillName> <classFile> [家族名] 安装Java BaseSkill");
        System.out.println();
        System.out.println("卸载命令:");
        System.out.println("  /uninstall -s <skillDir> [家族名]            卸载脚本Skill");
        System.out.println("  /uninstall -bs <skillName> [家族名]          卸载Java BaseSkill");
        System.out.println("  /uninstall -a <name> [家族名]                卸载Agent");
        System.out.println();
        System.out.println("重载命令:");
        System.out.println("  /reload -s <家族名>        重载脚本Skill  (例如 app:skillDir)");
        System.out.println("  /reload -a <家族名>        重载Agent      (例如 app:myAgent)");
        System.out.println("  /reload -bs <家族名>       重载Java BaseSkill (例如 app:mySkill)");
        System.out.println();
        System.out.println("查询命令:");
        System.out.println("  /agents [ownerName]    列出已注册的Agent");
        System.out.println("  /skills [ownerName]    列出已注册的Skill");
        System.out.println("  /param <工具名>        查看工具模块的参数");
        System.out.println("  /help                  显示此帮助");
        System.out.println();
        System.out.println("评测命令:");
        System.out.println("  /eval suite             运行全部评测用例");
        System.out.println("  /eval list              列出可用评测用例");
        System.out.println("  /eval quick             快速自检");
        System.out.println("  /eval run <id>          运行指定用例");
        System.out.println("  /eval cascade [agent]   级联评测");
        System.out.println();
        System.out.println("测试命令:");
        System.out.println("  /test                   运行全部单元测试（Mock Provider 驱动）");
        System.out.println("  /test list              列出可用测试用例");
        System.out.println("  /test <用例名>          运行单个测试场景 (例: /test basicChat)");
        System.out.println();
        System.out.println("全链追踪:");
        System.out.println("  /trace                  查看当前会话最新一轮的环节记录（agentMonitor 内存）");
        System.out.println("  /trace llm              查看最新一轮完整链路内容（messages → LLM 响应 → 最终输出）");
        System.out.println("  /stats                  当前会话 Token 统计 + 进程合计 + DB 历史合计");
        System.out.println("  /stats all              列出内存中所有会话的 Token 明细");
        System.out.println("  /stats agent            当前会话最后一轮各 Agent 的 token 用量");
        System.out.println();
        System.out.println("MCP 市场命令:");
        System.out.println("  /mcp search [keyword]   搜索 MCP 服务器，空参数列出全部精选");
        System.out.println("  /mcp info <package>     查看包的详细功能说明和工具列表");
        System.out.println("  /mcp install <package> [name] [--args ...]  安装为子 Agent");
        System.out.println("  /mcp list               列出已安装的 MCP Agent 及状态");
        System.out.println("  /mcp remove <name>      卸载 MCP Agent");
    }
}
