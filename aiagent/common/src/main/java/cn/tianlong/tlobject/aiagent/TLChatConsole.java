package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
 * 消息接口:
 *   startChat — 启动交互循环（当前线程阻塞，/exit 时返回）
 *   stopChat  — 外部停止循环
 *   onChatDone    — 非流式 chat 完成回调（框架任务结果回调触发，非主线程）
 *   onStreamChunk — 流式 chunk 回调（Provider 触发，非主线程）
 *
 * 配置参数 (XML params):
 *   agentModule — 目标 Agent 模块名，默认 "aiagent"
 *   sessionId   — 默认会话 ID
 *   streamMode  — 默认流式模式，默认 "false"
 *   prompt      — 输入提示符，默认 "你 > "
 *
 * 命令:
 *   /exit    — 退出
 *   /stop    — 中断当前正在运行的对话
 *   /clear   — 清除当前会话上下文
 *   /resume  — 恢复最近会话
 *   /stream  — 切换流式/非流式模式
 *   /session <id> — 切换会话ID
 *   ESC      — 快捷中断（效果同 /stop）
 *   Ctrl+C   — 退出
 *
 * @author tianlong
 * @date 2026/7/12
 */
public class TLChatConsole extends TLBaseModule implements TLAiAgentParamString {

    private String agentModule = "aiagent";
    private String userId = "console_user";
    private String sessionId = "chat_" + System.currentTimeMillis();
    private boolean streamMode = false;
    private String prompt = "你 > ";
    private volatile boolean running = false;

    // ======================== 事件循环状态（仅主线程访问） ========================
    private enum EventType { INPUT, RESULT, CHUNK, STREAM_END, STOP }

    private static final class ConsoleEvent {
        final EventType type;
        final String text;   // INPUT: 输入行; CHUNK: 文本块; STREAM_END: 错误信息(可空); STOP: null
        final TLMsg msg;     // RESULT: chat 返回消息
        ConsoleEvent(EventType type, String text, TLMsg msg) {
            this.type = type; this.text = text; this.msg = msg;
        }
    }

    private final BlockingQueue<ConsoleEvent> eventQueue = new LinkedBlockingQueue<>();
    private boolean busy = false;        // 是否有对话在后台运行（仅主线程读写）
    private boolean drainInput = false;  // STOP 后忽略残留 INPUT（ESC 前已入队的行，主线程标记）
    private long currentStart = 0L;      // 当前对话开始时间戳
    private Thread readerThread;

    public TLChatConsole() { super(); }
    public TLChatConsole(String name) { super(name); }
    public TLChatConsole(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected TLBaseModule init() { return this; }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null) {
            if (params.get("agentModule") != null) agentModule = params.get("agentModule");
            if (params.get("sessionId") != null) sessionId = params.get("sessionId");
            if (params.get("streamMode") != null) streamMode = "true".equals(params.get("streamMode"));
            if (params.get("prompt") != null) prompt = params.get("prompt");
        }
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case "startChat":
                startConsole();
                break;
            case "stopChat":
                running = false;
                break;
            case "onChatDone":
                // 非流式 chat 完成（在后台 worker 线程执行）——只入队，打印交给主线程
                offer(new ConsoleEvent(EventType.RESULT, null, msg));
                break;
            case STREAM_ONCHUNK:
                // 流式 chunk（在 OkHttp 线程执行）——只入队
                onStreamChunkEvent(msg);
                break;
        }
        return null;
    }

    // ======================== 事件循环 ========================

    private void startConsole() {
        System.out.println("╔══════════════════════════════════╗");
        System.out.println("║   AI Agent 交互式 Chat          ║");
        System.out.println("║   输入消息开始对话，ESC 中断     ║");
        System.out.println("╚══════════════════════════════════╝\n");

        printSkills();
        System.out.println("\nAI Agent 就绪，开始对话吧！（ESC 中断，/exit 退出）\n");

        running = true;

        // JLine Terminal：跨平台 raw mode，逐字节读 stdin
        Terminal terminal = null;
        try {
            terminal = TerminalBuilder.builder()
                    .system(true)
                    .streams(System.in, System.out)
                    .build();
            terminal.enterRawMode();
        } catch (IOException e) {
            System.out.println("终端初始化失败，回退到行模式（ESC 不可用）: " + e.getMessage());
            // 退化为兼容模式：用 Scanner 读行，无法检测 ESC
            startConsoleFallback();
            return;
        }

        final Terminal term = terminal;
        final NonBlockingReader termReader = term.reader();

        // 读取线程：raw mode 逐字符读，ESC → STOP，Enter → INPUT
        readerThread = new Thread(() -> {
            StringBuilder line = new StringBuilder();
            try {
                while (running) {
                    int ch = termReader.read();   // 阻塞读一个字节
                    if (ch < 0) {                  // EOF
                        if (running) offer(new ConsoleEvent(EventType.INPUT, "/exit", null));
                        break;
                    }

                    // Ctrl+C (3) / Ctrl+D (4) → 退出
                    if (ch == 3 || ch == 4) {
                        if (busy) {
                            // 运行中先尝试中断再退出
                            sendStopSignal();
                        }
                        running = false;
                        break;
                    }

                    // ESC (27)：区分 standalone 和转义序列（方向键等）
                    if (ch == 27) {
                        int peeked;
                        try {
                            peeked = termReader.peek(50);   // 50ms 内还有后续字节？
                        } catch (Exception ignore) {
                            peeked = -1;
                        }
                        if (peeked <= 0) {
                            // 超时无后续字节 → standalone ESC → 中断，同时丢弃已输入内容
                            line.setLength(0);
                            sendStopSignal();
                        } else {
                            // 转义序列 → 全部吞掉
                            consumeEscapeSequence(termReader);
                        }
                        continue;
                    }

                    // Enter (\r 或 \n)：提交当前行
                    if (ch == '\r' || ch == '\n') {
                        if (ch == '\r') {
                            try { if (termReader.peek(50) == '\n') termReader.read(); } catch (Exception ignore) {}
                        }
                        String input = line.toString();
                        line.setLength(0);
                        System.out.println();
                        System.out.flush();
                        offer(new ConsoleEvent(EventType.INPUT, input, null));
                        continue;
                    }

                    // Backspace (127 / 8)：删一个字符，全角字符需退 2 列
                    if (ch == 127 || ch == 8) {
                        if (line.length() > 0) {
                            char lastChar = line.charAt(line.length() - 1);
                            line.setLength(line.length() - 1);
                            if (isFullWidthChar(lastChar)) {
                                System.out.print("\b\b  \b\b");
                            } else {
                                System.out.print("\b \b");
                            }
                            System.out.flush();
                        }
                        continue;
                    }

                    // 可打印字符 / Tab：回显并积累
                    if (ch >= 32 || ch == '\t') {
                        line.append((char) ch);
                        System.out.print((char) ch);
                        System.out.flush();
                    }
                    // 其余控制字符静默忽略
                }
            } catch (Exception ignore) {
                // 关闭/IO异常：静默退出
            } finally {
                try { term.close(); } catch (Exception ignore) {}
            }
        }, "chat-console-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        // 主循环：终端的唯一主人（用 poll 代替 take，可响应 running=false 退出）
        printPrompt();
        System.out.flush();
        while (running) {
            ConsoleEvent e;
            try {
                e = eventQueue.poll(300, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                break;
            }
            if (e == null) continue;  // 超时，回到循环检查 running

            switch (e.type) {
                case INPUT:      handleInput(e.text); break;
                case STOP:       handleStopSignal(); break;
                case RESULT:     onChatResult(e.msg); break;
                case CHUNK:      System.out.print(e.text); System.out.flush(); break;
                case STREAM_END: onStreamEnd(e.text); break;
            }
        }

        // 清理
        running = false;
        try { term.close(); } catch (Exception ignore) {}
        System.out.println("再见！");
    }

    /** JLine raw mode 不可用时的降级方案：标准行模式，无 ESC 检测 */
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
                case STREAM_END: onStreamEnd(e.text); break;
            }
        }
        System.out.println("再见！");
    }

    /**
     * 判断字符在终端中是否占 2 列宽度（CJK 全角字符）。
     * 覆盖常用中文、日文、韩文及全角标点范围。
     */
    private static boolean isFullWidthChar(char c) {
        // CJK Radicals Supplement .. CJK Compatibility Ideographs Supplement
        return (c >= 0x2E80 && c <= 0x2EFF)   // CJK Radicals Supplement
            || (c >= 0x3000 && c <= 0x303F)   // CJK Symbols and Punctuation（含 、）
            || (c >= 0x3200 && c <= 0x32FF)   // Enclosed CJK
            || (c >= 0x3400 && c <= 0x4DBF)   // CJK Extension A
            || (c >= 0x4E00 && c <= 0x9FFF)   // CJK Unified Ideographs
            || (c >= 0xF900 && c <= 0xFAFF)   // CJK Compatibility Ideographs
            || (c >= 0xFE10 && c <= 0xFE1F)   // Vertical Forms
            || (c >= 0xFE30 && c <= 0xFE4F)   // CJK Compatibility Forms
            || (c >= 0xFF01 && c <= 0xFF60)   // Fullwidth Forms
            || (c >= 0xFFE0 && c <= 0xFFE6)   // Fullwidth Signs
            // Full-width digits/letters (FF01-FF5E already covered above), em dashes etc.
            || c == 0x2014 || c == 0x2015;    // EM DASH / HORIZONTAL BAR
    }

    /** 吞掉转义序列剩余字节（ESC 已被消费，吃直到中间/结尾字节） */
    private void consumeEscapeSequence(NonBlockingReader reader) {
        try {
            int b;
            // 转义序列格式：ESC [ A/B/C/D（方向键）或 ESC [ 1~ （功能键）
            // 结束字节范围 0x40–0x7E（@ 到 ~）
            while ((b = reader.read(50)) >= 0) {
                if (b >= 0x40 && b <= 0x7E) break;   // 最终字节
            }
        } catch (Exception ignore) {}
    }

    /** 处理一行输入（主线程） */
    private void handleInput(String raw) {
        // STOP 后吞掉残留 INPUT（ESC 前已入队或 reader 线程 buffer 残留的行）
        if (drainInput) {
            drainInput = false;
            return;
        }
        String input = raw == null ? "" : raw.trim();

        if (busy) {
            if (input.equalsIgnoreCase("/stop")) {
                sendStop();
            } else if (!input.isEmpty()) {
                System.out.println("⏳ 运行中，按 ESC 或输入 /stop 可中断当前对话");
            }
            printPrompt();
            System.out.flush();
            return;
        }

        if (input.isEmpty()) { printPrompt(); System.out.flush(); return; }

        if (input.startsWith("/")) {
            if (handleCommand(input)) { running = false; return; }
            printPrompt();
            System.out.flush();
            return;
        }

        busy = true;
        currentStart = System.currentTimeMillis();
        submitChat(input);
    }

    /** ESC 中断信号（主线程） */
    private void handleStopSignal() {
        if (busy) {
            sendStop();
            drainInput = true;   // 中断后吞掉已入队的残留 INPUT（ESC 前敲的字）
        }
    }

    /** 读取线程使用（非主线程）：入队中断信号，避免跨线程 putMsg */
    private void sendStopSignal() {
        offer(new ConsoleEvent(EventType.STOP, null, null));
    }

    /** 异步提交 chat：非流式经任务结果回调，流式让 chunk 直接回本模块 */
    private void submitChat(String input) {
        if (streamMode) {
            System.out.print("AI > ");
            System.out.flush();
            // 确保 streamCallback 已复位
            putMsg("streamCallback", createMsg().setAction(STREAM_RESET));
            putMsg(agentModule,
                    createMsg().setAction(AGENT_CHATSTREAM)
                            .setParam(AI_P_SESSIONID, sessionId)
                            .setParam("userId", userId)
                            .setParam(AI_P_USERMESSAGE, input)
                            .setParam(RESULTFOR, getName())
                            .setParam(RESULTACTION, STREAM_ONCHUNK));
        } else {
            TLMsg m = createMsg().setAction(AGENT_CHAT)
                    .setParam(AI_P_SESSIONID, sessionId)
                    .setParam("userId", userId)
                    .setParam(AI_P_USERMESSAGE, input);
            m.setSystemParam(TASKRESULTFOR, this);
            m.setSystemParam(TASKRESULTACTION, "onChatDone");
            IObject target = (IObject) getModule(agentModule);
            if (target != null) {
                putMsgNoWait(target, m);
            } else {
                System.out.println("AI > [错误] 找不到 Agent 模块: " + agentModule);
                busy = false;
                System.out.println();
                printPrompt();
                System.out.flush();
            }
        }
    }

    /** 发送中断信号：监控模块级联停止子/孙子 agent，主 agent 补 cancelLlm */
    private void sendStop() {
        // 1) 监控模块级联停止整个会话树（含子 agent、孙子 agent）
        putMsg(M_AGENTMONITOR, createMsg().setAction("stopByRoot")
                .setParam("rootSessionId", sessionId));
        // 2) 主 agent 直接 stopChat（不带 cascade），确保 cancelLlm 生效
        putMsg(agentModule, createMsg().setAction(AGENT_STOPCHAT)
                .setParam(AI_P_SESSIONID, sessionId));
    }

    /** 非流式结果事件（主线程打印） */
    private void onChatResult(TLMsg response) {
        if (response != null) {
            String aiResponse = response.getStringParam(AI_P_RESPONSE, "");
            boolean cancelled = response.parseBoolean(AI_P_CANCELLED, false);
            if (response.parseBoolean(RESULT, false) && !aiResponse.isEmpty()) {
                System.out.println("AI > " + aiResponse);
                if (cancelled) {
                    System.out.println("    (" + (System.currentTimeMillis() - currentStart) + "ms)");
                } else {
                    int pt = response.getIntParam(AI_P_PROMPTTOKENS, 0);
                    int ct = response.getIntParam(AI_P_COMPLETIONTOKENS, 0);
                    int tt = response.getIntParam(AI_P_TOTALTOKENS, 0);
                    int accTotal = response.getIntParam(AI_P_TOTALTOKENS_TOTAL, 0);
                    String tokenInfo = tt > 0
                            ? "，tokens 输入 " + pt + "/输出 " + ct + "/合计 " + tt + "，会话累计 " + accTotal
                            : "";
                    System.out.println("    (" + (System.currentTimeMillis() - currentStart) + "ms" + tokenInfo + ")");
                }
            } else {
                System.out.println("AI > [错误] " + (aiResponse.isEmpty() ? "空响应" : aiResponse));
            }
        }
        busy = false;
        System.out.println();
        printPrompt();
        System.out.flush();
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
            offer(new ConsoleEvent(EventType.STREAM_END, err, null));
        }
    }

    /** 流式结束事件（主线程收尾） */
    private void onStreamEnd(String error) {
        System.out.println();
        if (error != null && !error.isEmpty()) {
            System.out.println("    (⏹ 已结束: " + (error.length() > 80 ? error.substring(0, 80) + "..." : error) + ")");
        } else {
            TLMsg usage = putMsg(agentModule, createMsg().setAction(AGENT_GETTOKENUSAGE)
                    .setParam(AI_P_SESSIONID, sessionId));
            int accTotal = usage != null ? usage.getIntParam(AI_P_TOTALTOKENS_TOTAL, 0) : 0;
            String tokenInfo = accTotal > 0 ? "，会话累计 tokens " + accTotal : "";
            System.out.println("    (" + (System.currentTimeMillis() - currentStart) + "ms" + tokenInfo + ")");
        }
        busy = false;
        System.out.println();
        printPrompt();
        System.out.flush();
    }

    private void printPrompt() {
        System.out.print(prompt);
    }

    private void offer(ConsoleEvent e) {
        try {
            eventQueue.put(e);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void printSkills() {
        TLMsg listResult = putMsg(agentModule, createMsg().setAction(AGENT_LISTSKILLS));
        java.util.List<?> skills = (java.util.List<?>) listResult.getListParam("skills", java.util.List.of());
        if (skills.isEmpty()) {
            System.out.println("⚠ 警告: 没有注册任何Skill！Tool Call功能不可用。");
        } else {
            System.out.println("已注册的Skill (" + skills.size() + "个):");
            for (Object s : skills) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> info = (java.util.Map<String, Object>) s;
                System.out.println("  - " + info.get("name") + ": " + info.get("description"));
            }
        }
    }

    private boolean handleCommand(String cmd) {
        switch (cmd.toLowerCase()) {
            case "/exit":
            case "/quit":
                running = false;
                return true;

            case "/stop":
                System.out.println("当前没有运行中的对话");
                break;

            case "/clear":
                putMsg(agentModule, createMsg()
                        .setAction(AGENT_CLEARCONTEXT)
                        .setParam(AI_P_SESSIONID, sessionId));
                System.out.println("✓ 上下文已清除");
                break;

            case "/resume":
                TLMsg latestResult = putMsg(agentModule, createMsg().setAction("findLatestSession"));
                String latestId = latestResult.getStringParam("sessionId", null);
                if (latestId == null || latestId.equals(sessionId)) {
                    System.out.println("✗ 没有可恢复的之前会话");
                    break;
                }
                sessionId = latestId;
                TLMsg resumeResult = putMsg(agentModule, createMsg()
                        .setAction("resumeSession")
                        .setParam(AI_P_SESSIONID, sessionId));
                if (resumeResult.parseBoolean(RESULT, false)) {
                    int count = resumeResult.getIntParam("count", 0);
                    System.out.println("✓ 会话 " + sessionId + " 已恢复 (" + count + " 条):");
                    TLMsg ctxResult = putMsg(agentModule, createMsg()
                            .setAction(AGENT_GETCONTEXT)
                            .setParam(AI_P_SESSIONID, sessionId));
                    java.util.List<?> history = (java.util.List<?>) ctxResult.getListParam(AI_P_MESSAGEHISTORY, null);
                    if (history != null) {
                        for (Object h : history) {
                            if (h instanceof cn.tianlong.tlobject.aiagent.TLConversationHistory) {
                                cn.tianlong.tlobject.aiagent.TLConversationHistory msg
                                        = (cn.tianlong.tlobject.aiagent.TLConversationHistory) h;
                                String role = msg.getRole().name().toLowerCase();
                                String content = msg.getContent();
                                if (!"system".equals(role) && content != null) {
                                    System.out.println((role.equals("user") ? "你" : "AI") + " > " + content);
                                }
                            }
                        }
                    }
                } else {
                    System.out.println("✗ 恢复失败");
                }
                break;

            case "/stream":
                streamMode = !streamMode;
                System.out.println("✓ 流式模式: " + (streamMode ? "开启" : "关闭"));
                break;

            default:
                if (cmd.startsWith("/session ")) {
                    sessionId = cmd.substring(9).trim();
                    System.out.println("✓ 会话ID切换为: " + sessionId);
                } else {
                    System.out.println("未知命令: " + cmd);
                    System.out.println("可用: /exit /stop /clear /resume /stream /session <id>  ESC=中断");
                }
                break;
        }
        return false;
    }
}
