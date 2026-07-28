package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import java.util.HashMap;
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
 *   Tab      — 命令自动补全（输入 / 后按 Tab 显示匹配命令及解释）
 *   Ctrl+C   — 退出
 *
 * @author tianlong
 * @date 2026/7/12
 */
public class TLChatConsole extends TLBaseModule implements TLAiAgentParamString {

    private String agentModule = "aiagent";
    private String userId = "console_user";
    private String sessionId = "chat_" + System.currentTimeMillis();
    /** 待恢复的 mid-loop 检查点（启动时检测到未完成会话，由 /resume 触发恢复） */
    private String pendingCheckpointSessionId = null;
    private String pendingCheckpointUserMessage = null;
    private boolean streamMode = false;
    private String prompt = "你 > ";
    private volatile boolean running = false;
    /** 推理展示：是否折叠推理内容（默认折叠，展开后可查看完整思考链） */
    private boolean reasoningCollapsed = true;

    /** 命令注册表：命令名 → 描述，用于 Tab 补全和帮助提示 */
    private static final java.util.LinkedHashMap<String, String> COMMAND_REGISTRY = new java.util.LinkedHashMap<>();
    static {
        COMMAND_REGISTRY.put("/exit", "退出控制台");
        COMMAND_REGISTRY.put("/quit", "退出控制台");
        COMMAND_REGISTRY.put("/stop", "中断当前对话");
        COMMAND_REGISTRY.put("/clear", "清除会话上下文");
        COMMAND_REGISTRY.put("/resume", "恢复最近会话");
        COMMAND_REGISTRY.put("/stream", "切换流式/非流式");
        COMMAND_REGISTRY.put("/session", "切换会话ID");
        COMMAND_REGISTRY.put("/thinking", "切换推理折叠/展开");
        COMMAND_REGISTRY.put("/help", "显示帮助信息");
        COMMAND_REGISTRY.put("/?", "显示帮助信息");
        COMMAND_REGISTRY.put("/agents", "列出已注册Agent");
        COMMAND_REGISTRY.put("/skills", "列出已注册Skill");
        COMMAND_REGISTRY.put("/install", "安装Skill/Agent");
        COMMAND_REGISTRY.put("/uninstall", "卸载Skill/Agent");
        COMMAND_REGISTRY.put("/reload", "重载Skill/Agent");
        COMMAND_REGISTRY.put("/approve", "审批操作: /approve approve:ID 或 reject:ID:原因");
        COMMAND_REGISTRY.put("/sessions", "列出所有历史会话");
        COMMAND_REGISTRY.put("/continue", "继续历史会话: /continue [id]");
        COMMAND_REGISTRY.put("/eval", "Agent评测: /eval suite|list|quick|run <id>");
    }

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

        // 检测启动前未正常结束的断点会话，提示用户是否继续
        try {
            TLMsg incompleteResult = putMsg(agentModule, createMsg()
                    .setAction(FIND_INCOMPLETE_CHECKPOINTS));
            if (incompleteResult != null && incompleteResult.parseBoolean(RESULT, false)) {
                pendingCheckpointSessionId = incompleteResult.getStringParam("sessionId", null);
                pendingCheckpointUserMessage = incompleteResult.getStringParam("userMessage", "");
                long savedAt = incompleteResult.getLongParam("savedAt", 0L);
                int iteration = incompleteResult.getIntParam("iteration", 0);
                String timeStr = savedAt > 0
                        ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                            .format(new java.util.Date(savedAt))
                        : "未知";
                System.out.println();
                System.out.println("⚠═══════════════════════════════════");
                System.out.println("  检测到上次未正常结束的会话:");
                System.out.println("  会话ID:   " + pendingCheckpointSessionId);
                System.out.println("  用户消息: " + pendingCheckpointUserMessage);
                System.out.println("  中断时间: " + timeStr + " (第 " + iteration + " 轮)");
                System.out.println("  输入 /resume 继续执行，或直接输入新消息开始新对话");
                System.out.println("═══════════════════════════════════");
                System.out.println();
            }
        } catch (Exception e) {
            System.out.println("（检查断点失败: " + e.getMessage() + "）");
        }

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

                    // Tab：命令补全
                    if (ch == '\t') {
                        handleTabComplete(line);
                        continue;
                    }

                    // 可打印字符：回显并积累
                    if (ch >= 32) {
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
                case STREAM_END: onStreamEnd(e.text,
                        e.msg != null ? e.msg.getStringParam(AI_P_REASONING, null) : null); break;
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
                case STREAM_END: onStreamEnd(e.text,
                        e.msg != null ? e.msg.getStringParam(AI_P_REASONING, null) : null); break;
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
            } else if (input.toLowerCase().startsWith("/approve")) {
                handleApproveCommand(input);
            } else if (!input.isEmpty()) {
                System.out.println("⏳ 运行中，按 ESC 或输入 /stop 可中断当前对话");
            }
            printPrompt();
            System.out.flush();
            return;
        }

        if (input.isEmpty()) { printPrompt(); System.out.flush(); return; }

        if (input.startsWith("/")) {
            // /approve 带参数时走审批命令处理（非 busy 状态也支持，例如审批异步请求）
            if (input.toLowerCase().startsWith("/approve") && input.length() > "/approve".length()) {
                handleApproveCommand(input);
            } else if (handleCommand(input)) { running = false; return; }
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

    /** 异步提交 chat：非流式经任务结果回调，流式经 Agent 的 onStreamResult 转发（支持 tool-call） */
    private void submitChat(String input) {
        if (streamMode) {
            System.out.print("AI > ");
            System.out.flush();
            // 流式请求走 Agent 的 onStreamResult：Agent 转发 chunk 到本模块 + 处理 tool-call
            // RESULTFOR=agent → 回调发给 Agent；_streamResultFor=本模块 → Agent 再转发 chunk/结果
            putMsg(agentModule,
                    createMsg().setAction(AGENT_CHATSTREAM)
                            .setParam(AI_P_SESSIONID, sessionId)
                            .setParam("userId", userId)
                            .setParam(AI_P_USERMESSAGE, input)
                            .setParam(RESULTFOR, agentModule)
                            .setParam(RESULTACTION, "onStreamResult")
                            .setParam("_streamResultFor", getName())
                            .setParam("_streamResultAction", STREAM_ONCHUNK));
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

    /**
     * 处理 /approve 命令，将审批决策发给审批模块。
     * 格式: /approve approve:ID  或  /approve reject:ID:原因
     */
    private void handleApproveCommand(String input) {
        // 去掉 "/approve " 前缀（不区分大小写）
        String payload = input.substring("/approve".length()).trim();
        if (payload.isEmpty()) {
            System.out.println("格式: /approve approve:ID  或  /approve reject:ID:原因");
            return;
        }

        if (payload.startsWith("approve:")) {
            String[] parts = payload.substring("approve:".length()).split(":", 2);
            String approvalId = parts[0].trim();
            java.util.Map<String, Object> modifiedArgs = null;
            if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                try {
                    modifiedArgs = new com.google.gson.Gson().fromJson(parts[1].trim(), java.util.Map.class);
                } catch (Exception ignored) {}
            }
            TLMsg approveMsg = createMsg().setAction(APPROVAL_APPROVE)
                    .setParam("approvalId", approvalId);
            if (modifiedArgs != null) {
                approveMsg.setParam("approvalModifiedArguments", modifiedArgs);
            }
            putMsg("approvalGate", approveMsg);
            System.out.println("✓ 已批准: " + approvalId);

        } else if (payload.startsWith("reject:")) {
            String[] parts = payload.substring("reject:".length()).split(":", 2);
            String approvalId = parts[0].trim();
            String reason = parts.length > 1 ? parts[1].trim() : "用户拒绝";
            putMsg("approvalGate", createMsg().setAction(APPROVAL_REJECT)
                    .setParam("approvalId", approvalId)
                    .setParam("approvalRejectReason", reason));
            System.out.println("✗ 已拒绝: " + approvalId + " (" + reason + ")");

        } else {
            System.out.println("未知审批操作: " + payload + "  (可用: approve:ID, reject:ID:原因)");
        }
    }

    /** 非流式结果事件（主线程打印） */
    private void onChatResult(TLMsg response) {
        if (response != null) {
            String aiResponse = response.getStringParam(AI_P_RESPONSE, "");
            boolean cancelled = response.parseBoolean(AI_P_CANCELLED, false);
            if (response.parseBoolean(RESULT, false) && !aiResponse.isEmpty()) {
                // 先显示推理过程（如果有）
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
        // 推理块：流式模式下静默收集，在流结束时统一展示
        boolean done = msg.parseBoolean(AI_P_STREAMDONE, false);
        boolean hasErr = msg.containsParam(AI_P_STREAMERROR);
        if (done || hasErr) {
            String err = hasErr ? msg.getStringParam(AI_P_STREAMERROR, "") : null;
            // 将 reasoning 信息打包到 STREAM_END 事件的 msg 字段中
            TLMsg endMsg = null;
            if (msg.containsParam(AI_P_REASONING)) {
                endMsg = createMsg().setParam(AI_P_REASONING, msg.getStringParam(AI_P_REASONING, ""));
            }
            offer(new ConsoleEvent(EventType.STREAM_END, err, endMsg));
        }
    }

    /** 流式结束事件（主线程收尾） */
    private void onStreamEnd(String error, String reasoning) {
        // 先显示推理（如果有的话）
        if (reasoning != null && !reasoning.isEmpty()) {
            displayReasoning(reasoning);
        }
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

    /** 列出所有历史会话 */
    private void listAllSessions() {
        TLMsg result = putMsg(agentModule, createMsg().setAction(LIST_SESSIONS));
        if (result == null || !result.parseBoolean(RESULT, false)) {
            System.out.println("✗ 无法获取会话列表");
            return;
        }
        java.util.List<?> sessions = result.getListParam("sessions", null);
        if (sessions == null || sessions.isEmpty()) {
            System.out.println("（无历史会话）");
            return;
        }
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
        System.out.println();
        int i = 1;
        for (Object obj : sessions) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> s = (java.util.Map<String, Object>) obj;
            String sid = s.get("sessionId") != null ? s.get("sessionId").toString() : "?";
            String state = s.get("state") != null ? s.get("state").toString() : "?";
            String userMsg = s.get("userMessage") != null ? s.get("userMessage").toString() : "";
            long savedAt = 0;
            if (s.get("savedAt") instanceof Long) savedAt = (Long) s.get("savedAt");
            int count = 0;
            if (s.get("count") instanceof Integer) count = (Integer) s.get("count");
            String timeStr = savedAt > 0 ? sdf.format(new java.util.Date(savedAt)) : "未知";
            // 截断过长的用户消息
            String preview = userMsg.length() > 40 ? userMsg.substring(0, 40) + "..." : userMsg;
            String marker = sid.equals(sessionId) ? " ← 当前" : "";
            String stateTag = "completed".equals(state) ? "" : " [" + state + "]";
            System.out.println("  " + i + ". " + sid + "  " + timeStr + "  \"" + preview
                    + "\"  " + count + "条" + stateTag + marker);
            i++;
        }
        System.out.println();
        System.out.println("使用 /continue <id> 继续某个会话，或 /continue 恢复最近会话");
    }

    /** 继续历史会话：加载历史到 aiContext 并切换 sessionId */
    private void continueSession(String cmd) {
        String targetId;
        if (cmd.length() > "/continue".length()) {
            // /continue <id>
            targetId = cmd.substring("/continue".length()).trim();
        } else {
            // /continue → 找最近一个（排除当前）
            TLMsg latestResult = putMsg(agentModule, createMsg()
                    .setAction("findLatestSession")
                    .setParam(AI_P_SESSIONID, sessionId));
            targetId = latestResult.getStringParam("sessionId", null);
            if (targetId == null) {
                System.out.println("✗ 没有可恢复的之前会话");
                return;
            }
        }

        // 加载会话历史到 aiContext
        TLMsg resumeResult = putMsg(agentModule, createMsg()
                .setAction("resumeSession")
                .setParam(AI_P_SESSIONID, targetId));
        if (resumeResult == null || !resumeResult.parseBoolean(RESULT, false)) {
            String err = resumeResult != null ? resumeResult.getStringParam("error", "未知") : "无响应";
            System.out.println("✗ 恢复失败: " + err);
            return;
        }

        int count = resumeResult.getIntParam("count", 0);
        sessionId = targetId;
        System.out.println("✓ 已恢复会话 " + targetId + " (" + count + " 条历史)，继续聊吧");

        // 打印历史记录
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
    }

    private void printPrompt() {
        System.out.print(prompt);
    }

    /**
     * Tab 命令补全（由读取线程调用，直接操作终端）。
     * 当前输入以 / 开头时，匹配命令注册表并自动补全或展示候选项。
     */
    private void handleTabComplete(StringBuilder line) {
        String current = line.toString();
        if (current.isEmpty() || !current.startsWith("/")) return;

        String prefix = current.toLowerCase();
        java.util.List<java.util.Map.Entry<String, String>> matches = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> e : COMMAND_REGISTRY.entrySet()) {
            if (e.getKey().toLowerCase().startsWith(prefix)) {
                matches.add(e);
            }
        }

        if (matches.isEmpty()) return;

        if (matches.size() == 1) {
            // 唯一匹配 → 自动补全
            String full = matches.get(0).getKey();
            if (full.equals(current)) return;  // 已完整，不操作
            String suffix = full.substring(current.length());
            line.setLength(0);
            line.append(full);
            System.out.print(suffix);
            System.out.flush();
        } else {
            // 多项匹配 → 展示列表，含命令解释
            System.out.print("\r\n");
            int maxLen = 0;
            for (java.util.Map.Entry<String, String> m : matches) {
                if (m.getKey().length() > maxLen) maxLen = m.getKey().length();
            }
            for (java.util.Map.Entry<String, String> m : matches) {
                System.out.print("  " + padRight(m.getKey(), maxLen + 2) + m.getValue() + "\r\n");
            }
            // 重新显示提示符和当前输入
            System.out.print("\r\n");
            System.out.print(prompt);
            System.out.print(current);
            System.out.flush();
        }
    }

    /** 右填充空格至指定长度 */
    private static String padRight(String s, int n) {
        if (s.length() >= n) return s;
        StringBuilder sb = new StringBuilder(n);
        sb.append(s);
        for (int i = s.length(); i < n; i++) sb.append(' ');
        return sb.toString();
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

    /** /eval [suite|list|quick|run <id>] — Agent 评测 */
    private void handleEval(String cmd) {
        String[] parts = cmd.split("\\s+", 2);
        String subCmd = parts.length > 1 ? parts[1].trim() : "";

        if (subCmd.isEmpty() || subCmd.equals("help")) {
            System.out.println("用法:");
            System.out.println("  /eval suite    运行全部用例");
            System.out.println("  /eval list     列出可用用例");
            System.out.println("  /eval quick    快速自检（内置用例）");
            System.out.println("  /eval run <id>  运行指定用例");
            return;
        }

        TLMsg result;
        if (subCmd.equals("suite")) {
            System.out.println("⏳ 正在运行全部评测用例...");
            result = putMsg("evals", createMsg().setAction("runEvalSuite"));
        } else if (subCmd.equals("list")) {
            result = putMsg("evals", createMsg().setAction("listEvalCases"));
        } else if (subCmd.equals("quick")) {
            System.out.println("⏳ 正在运行快速评测...");
            result = putMsg("evals", createMsg().setAction("runQuickEval"));
        } else if (subCmd.startsWith("run ")) {
            String caseId = subCmd.substring(4).trim();
            if (caseId.isEmpty()) {
                System.out.println("✗ 请指定用例ID: /eval run <id>");
                return;
            }
            System.out.println("⏳ 正在运行用例: " + caseId + " ...");
            result = putMsg("evals", createMsg().setAction("runEvalByName")
                    .setParam("caseId", caseId));
        } else {
            System.out.println("✗ 未知子命令: " + subCmd);
            System.out.println("用法: /eval suite|list|quick|run <id>");
            return;
        }

        if (result != null && result.parseBoolean(RESULT, false)) {
            int passed = result.getIntParam("passed", 0);
            int failed = result.getIntParam("failed", 0);
            int total = result.getIntParam("total", passed + failed);
            double passRate = 0;
            try { passRate = Double.parseDouble(result.getStringParam("passRate", "0")); } catch (Exception ignored) {}
            System.out.println(String.format("✓ 评测完成: %d/%d 通过 (%.1f%%)",
                    passed, total, passRate * 100));
            String reportPath = result.getStringParam("reportPath", "");
            if (!reportPath.isEmpty()) System.out.println("  报告: " + reportPath);
        } else if (result != null) {
            System.out.println("✗ 评测执行出错: " + result.getStringParam("error", "未知错误"));
        }
    }

    /** /agents [/skills] [ownerFamilyName] — 从 moduleRegistry 列出模块，应用层按类型过滤 */
    private void handleListModules(String cmd) {
        String[] parts = cmd.split("\\s+", 2);
        String action = parts[0];           // "/agents" or "/skills"
        String ownerFamilyName = parts.length > 1 ? parts[1].trim() : null;
        boolean listAgents = action.equals("/agents");

        TLMsg listMsg = createMsg().setAction(REGISTRY_LIST);
        if (ownerFamilyName != null && !ownerFamilyName.isEmpty())
            listMsg.setParam(REGISTRY_P_OWNERNAME, ownerFamilyName);

        TLMsg result = putMsg(DEFAULTMODULEREGISTRY, listMsg);
        if (result == null) {
            System.out.println("✗ moduleRegistry 未配置或未启动");
            return;
        }
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> modules =
                (java.util.List<java.util.Map<String, Object>>) result.getParam(RESULT);
        if (modules == null || modules.isEmpty()) {
            System.out.println("(无)");
            return;
        }
        // 应用层按实例类型过滤
        String label = listAgents ? "Agent" : "Skill";
        java.util.List<java.util.Map<String, Object>> filtered = new java.util.ArrayList<>();
        for (java.util.Map<String, Object> m : modules) {
            Object inst = m.get(INSTANCE);
            boolean match = listAgents ? (inst instanceof IAgentCapable) : (inst instanceof TLBaseSkill);
            if (match) filtered.add(m);
        }
        System.out.println(label + " (" + filtered.size() + "):");
        for (java.util.Map<String, Object> m : filtered) {
            String key = (String) m.get(REGISTRY_P_KEY);
            String clazz = m.get(INSTANCE) != null ? m.get(INSTANCE).getClass().getSimpleName() : "?";
            System.out.println("  " + key + "  [" + clazz + "]");
        }
    }

    /** /install skillDir [agentName] — 向指定 agent 热加载脚本 skill */
    /** 从 moduleRegistry 按家族名精确查找 agent 实例，找不到返回 null。 */
    private TLBaseModule findAgentInstance(String familyName) {
        TLMsg result = putMsg(DEFAULTMODULEREGISTRY, createMsg().setAction(REGISTRY_GET)
                .setParam(REGISTRY_P_KEY, familyName));
        if (result != null) {
            Object inst = result.getParam(INSTANCE);
            if (inst instanceof TLBaseModule) return (TLBaseModule) inst;
        }
        return null;
    }

    // ======================== 共享安装/卸载方法 ========================

    /** 安装脚本 Skill（热加载目录型 skill） */
    private void installScriptSkill(String skillDir, String targetAgent) {
        TLBaseModule agent = findAgentInstance(targetAgent);
        if (agent == null) {
            System.out.println("✗ Agent 未找到: " + targetAgent);
            return;
        }
        TLMsg msg = createMsg().setAction(AGENT_HOTLOADSKILL).setParam("skillDir", skillDir);
        msg.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg result = putMsg(agent, msg);
        if (result != null && result.parseBoolean(RESULT, false)) {
            System.out.println("✓ Skill 已安装: " + skillDir + " → " + targetAgent);
        } else {
            String err = result != null ? result.getStringParam("error", "未知错误") : "agent 无响应";
            System.out.println("✗ 安装失败: " + err);
        }
    }

    /** 卸载脚本 Skill */
    /** 卸载脚本 Skill。skillDir 可以是短名或家族名 */
    private void uninstallScriptSkill(String skillDir, String targetAgent) {
        TLBaseModule agent;
        String shortName;
        if (skillDir.contains(":")) {
            Object[] resolved = resolveByPath(skillDir);
            if (resolved == null) { System.out.println("✗ 路径解析失败: " + skillDir); return; }
            agent = (TLBaseModule) resolved[0];
            shortName = (String) resolved[1];
        } else {
            agent = findAgentInstance(targetAgent);
            shortName = skillDir;
        }
        if (agent == null) {
            System.out.println("✗ Agent 未找到: " + (skillDir.contains(":") ? skillDir : targetAgent));
            return;
        }
        TLMsg msg = createMsg().setAction(AGENT_HOTUNLOADSKILL).setParam("skillDir", shortName);
        msg.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg result = putMsg(agent, msg);
        if (result != null && result.parseBoolean(RESULT, false)) {
            System.out.println("✓ Skill 已卸载: " + shortName + " ← " + agent.getFamilyName());
        } else {
            String err = result != null ? result.getStringParam("error", "未知错误") : "agent 无响应";
            System.out.println("✗ 卸载失败: " + err);
        }
    }

    /** 安装 Agent（子 agent） */
    private void installAgent(String agentName, String classRef, String targetAgent) {
        TLBaseModule parent = findAgentInstance(targetAgent);
        if (parent == null) {
            System.out.println("✗ Agent 未找到: " + targetAgent);
            return;
        }
        HashMap<String, String> cfg = new HashMap<>();
        if (classRef.contains(".")) {
            cfg.put("classfile", classRef);
        } else {
            cfg.put("sameClassAs", classRef);
        }
        cfg.put("statup", "true");

        TLMsg msg = createMsg().setAction(AGENT_REGISTERAGENT)
                .setParam(AI_P_AGENTNAME, agentName)
                .setParam(AI_P_AGENTCONFIG, cfg)
                .setParam(HOTLOAD_P_PERSIST, "true");
        msg.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg result = putMsg(parent, msg);
        if (result != null && result.parseBoolean(RESULT, false)) {
            System.out.println("✓ Agent 已安装: " + agentName + " (" + classRef + ") → " + targetAgent);
        } else {
            String err = result != null ? result.getStringParam("error", "未知错误") : "无响应";
            System.out.println("✗ 安装失败: " + err);
        }
    }

    /** 卸载 Agent。agentName 可以是短名或家族名 */
    private void uninstallAgent(String agentName, String targetAgent) {
        TLBaseModule parent;
        String shortName;
        // 家族名：直接解析出父和目标
        if (agentName.contains(":")) {
            Object[] resolved = resolveByPath(agentName);
            if (resolved == null) {
                System.out.println("✗ 路径解析失败: " + agentName);
                return;
            }
            parent = (TLBaseModule) resolved[0];
            shortName = (String) resolved[1];
        } else {
            parent = findAgentInstance(targetAgent);
            shortName = agentName;
        }
        if (parent == null) {
            System.out.println("✗ Agent 未找到: " + (agentName.contains(":") ? agentName : targetAgent));
            return;
        }
        TLMsg msg = createMsg().setAction(AGENT_UNREGISTERAGENT)
                .setParam(AI_P_AGENTNAME, shortName)
                .setParam(HOTLOAD_P_PERSIST, "true");
        msg.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg result = putMsg(parent, msg);
        if (result != null && result.parseBoolean(RESULT, false)) {
            System.out.println("✓ Agent 已卸载: " + shortName + " ← " + parent.getFamilyName());
        } else {
            String err = result != null ? result.getStringParam("error", "未知错误") : "无响应";
            System.out.println("✗ 卸载失败: " + err);
        }
    }

    // ======================== /help 命令 ========================

    private void handleHelp() {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║            AI Agent 控制台命令帮助              ║");
        System.out.println("╚══════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("对话命令:");
        System.out.println("  /exit, /quit   退出控制台");
        System.out.println("  /stop          中断当前正在运行的对话");
        System.out.println("  ESC            快捷中断（效果同 /stop）");
        System.out.println("  /clear         清除当前会话上下文");
        System.out.println("  /resume        恢复未完成的 mid-loop 断点会话");
        System.out.println("  /continue [id] 继续某个历史会话（不带 id 则恢复最近）");
        System.out.println("  /sessions      列出所有历史会话");
        System.out.println("  /stream        切换流式/非流式模式");
        System.out.println("  /session <id>  切换会话ID");
        System.out.println("  /thinking      切换推理过程折叠/展开（/thinking off|prompt|native|auto）");
        System.out.println();
        System.out.println("安装命令:");
        System.out.println("  /install -s <skillDir> [家族名]              安装脚本型Skill（目录）");
        System.out.println("  /install -a <name> <classRef> [家族名]       安装Agent");
        System.out.println("  /install -bs <skillName> <classFile> [家族名] 安装Java BaseSkill");
        System.out.println();
        System.out.println("卸载命令:");
        System.out.println("  /uninstall -s <skillDir> [家族名]            卸载脚本Skill");
        System.out.println("  /uninstall -bs <skillName> [家族名]          卸载Java BaseSkill");
        System.out.println("  /uninstall -a <name> [家族名]                卸载Agent");
        System.out.println();
        System.out.println("查询命令:");
        System.out.println("  /agents [ownerName]    列出已注册的Agent");
        System.out.println("  /skills [ownerName]    列出已注册的Skill");
        System.out.println("  /help                  显示此帮助");
        System.out.println();
        System.out.println("重载命令:");
        System.out.println("  /reload -s <家族名>        重载脚本Skill  (例如 app:skillDir)");
        System.out.println("  /reload -a <家族名>        重载Agent      (例如 app:myAgent)");
        System.out.println("  /reload -bs <家族名>       重载Java BaseSkill (例如 app:mySkill)");
        System.out.println();
        System.out.println("评测命令:");
        System.out.println("  /eval suite             运行全部评测用例");
        System.out.println("  /eval list              列出可用评测用例");
        System.out.println("  /eval quick             快速自检");
        System.out.println("  /eval run <id>          运行指定用例");
        System.out.println();
    }

    // ======================== 新统一命令处理器 ========================

    /** /install -s|-a|-bs ... — 统一安装入口 */
    private void handleInstall(String cmd) {
        String[] parts = cmd.split("\\s+");
        if (parts.length < 2) {
            printInstallUsage();
            return;
        }
        String flag = parts[1];
        switch (flag) {
            case "-s":
                // /install -s <skillDir> [家族名]
                if (parts.length < 3) {
                    System.out.println("用法: /install -s <skillDir> [家族名]");
                    return;
                }
                installScriptSkill(parts[2], parts.length > 3 ? parts[3] : agentModule);
                break;
            case "-a":
                // /install -a <agentName> <classFile|sameClassAs> [家族名]
                if (parts.length < 4) {
                    System.out.println("用法: /install -a <agentName> <classFile|sameClassAs> [家族名]");
                    return;
                }
                if (parts[2].contains(":") || parts[2].contains(".")) {
                    System.out.println("✗ Agent 名称不能包含 ':' 或 '.'，请使用短名。");
                    System.out.println("  家族名（target）请放在最后参数。");
                    System.out.println("  例: /install -a priceTeam aiagent " + agentModule);
                    return;
                }
                installAgent(parts[2], parts[3], parts.length > 4 ? parts[4] : agentModule);
                break;
            case "-bs":
                // /install -bs <skillName> <classFile> [家族名]
                if (parts.length < 4) {
                    System.out.println("用法: /install -bs <skillName> <classFile> [家族名]");
                    System.out.println("  例: /install -bs mySkill cn.tianlong.java.demo.aiagent.skills.MyDemoSkill");
                    return;
                }
                if (parts[2].contains(":") || parts[2].contains(".")) {
                    System.out.println("✗ Skill 名称不能包含 ':' 或 '.'，请使用短名。");
                    return;
                }
                installBaseSkill(parts[2], parts[3], parts.length > 4 ? parts[4] : agentModule);
                break;
            default:
                System.out.println("未知flag: " + flag + "，可用: -s (脚本Skill), -a (Agent), -bs (JavaSkill)");
                break;
        }
    }

    /** /uninstall -s|-a ... — 统一卸载入口 */
    private void handleUninstall(String cmd) {
        String[] parts = cmd.split("\\s+");
        if (parts.length < 2) {
            printUninstallUsage();
            return;
        }
        String flag = parts[1];
        switch (flag) {
            case "-s":
                // /uninstall -s <skillDir> [家族名]
                if (parts.length < 3) {
                    System.out.println("用法: /uninstall -s <skillDir> [家族名]");
                    return;
                }
                uninstallScriptSkill(parts[2], parts.length > 3 ? parts[3] : agentModule);
                break;
            case "-bs":
                // /uninstall -bs <skillName> [家族名]
                if (parts.length < 3) {
                    System.out.println("用法: /uninstall -bs <skillName> [家族名]");
                    return;
                }
                uninstallBaseSkill(parts[2], parts.length > 3 ? parts[3] : agentModule);
                break;
            case "-a":
                // /uninstall -a <agentName> [家族名]
                if (parts.length < 3) {
                    System.out.println("用法: /uninstall -a <agentName> [家族名]");
                    return;
                }
                uninstallAgent(parts[2], parts.length > 3 ? parts[3] : agentModule);
                break;
            default:
                System.out.println("未知flag: " + flag + "，可用: -s (脚本Skill), -bs (JavaSkill), -a (Agent)");
                break;
        }
    }

    /** 安装 Java BaseSkill 类（/install -bs） */
    private void installBaseSkill(String skillName, String classFile, String targetAgent) {
        TLBaseModule agent = findAgentInstance(targetAgent);
        if (agent == null) {
            System.out.println("✗ Agent 未找到: " + targetAgent);
            return;
        }
        TLMsg msg = createMsg()
                .setAction(AGENT_REGISTERSKILL)
                .setParam(MODULENAME, skillName)
                .setParam(MODULE_CLASSFILE, classFile)
                .setParam(HOTLOAD_P_PERSIST, "true");
        msg.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg result = putMsg(agent, msg);
        if (result != null && result.parseBoolean(RESULT, false)) {
            System.out.println("✓ JavaSkill 已安装: " + skillName + " (" + classFile + ") → " + targetAgent);
        } else {
            String err = result != null ? result.getStringParam("error", "未知错误") : "agent 无响应";
            System.out.println("✗ 安装失败: " + err);
        }
    }

    /** 卸载 Java BaseSkill（/uninstall -bs）。skillName 可以是短名或家族名 */
    private void uninstallBaseSkill(String skillName, String targetAgent) {
        TLBaseModule agent;
        String shortName;
        if (skillName.contains(":")) {
            Object[] resolved = resolveByPath(skillName);
            if (resolved == null) { System.out.println("✗ 路径解析失败: " + skillName); return; }
            agent = (TLBaseModule) resolved[0];
            shortName = (String) resolved[1];
        } else {
            agent = findAgentInstance(targetAgent);
            shortName = skillName;
        }
        if (agent == null) {
            System.out.println("✗ Agent 未找到: " + (skillName.contains(":") ? skillName : targetAgent));
            return;
        }
        TLMsg msg = createMsg()
                .setAction(AGENT_UNREGISTERSKILL)
                .setParam(AI_P_SKILLNAME, shortName)
                .setParam(HOTLOAD_P_PERSIST, "true");
        msg.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg result = putMsg(agent, msg);
        if (result != null && result.parseBoolean(RESULT, false)) {
            System.out.println("✓ JavaSkill已卸载: " + shortName + " ← " + agent.getFamilyName());
        } else {
            String err = result != null ? result.getStringParam("error", "未知错误") : "agent 无响应";
            System.out.println("✗ 卸载失败: " + err);
        }
    }

    private void printInstallUsage() {
        System.out.println("用法: /install -s <skillDir> [家族名]  (安装脚本Skill)");
        System.out.println("      /install -a <name> <classRef> [家族名]  (安装Agent)");
        System.out.println("      /install -bs <skillName> <classFile> [家族名]  (安装JavaSkill)");
    }

    private void printUninstallUsage() {
        System.out.println("用法: /uninstall -s <skillDir> [家族名]  (卸载脚本Skill)");
        System.out.println("      /uninstall -bs <skillName> [家族名]  (卸载JavaSkill)");
        System.out.println("      /uninstall -a <name> [家族名]  (卸载Agent)");
    }

    private void printReloadUsage() {
        System.out.println("用法: /reload -s <家族名>  (重载脚本Skill，如 aiagent:mySkillDir)");
        System.out.println("      /reload -a <家族名>  (重载Agent，如 app:myAgent)");
        System.out.println("      /reload -bs <家族名> (重载Java BaseSkill，如 aiagent:mySkill)");
    }

    // ======================== /reload 命令 ========================

    private void handleReload(String cmd) {
        String[] parts = cmd.split("\\s+");
        if (parts.length < 2) {
            printReloadUsage();
            return;
        }
        switch (parts[1]) {
            case "-s":
                if (parts.length < 3) {
                    System.out.println("用法: /reload -s <家族名>  例: /reload -s aiagent:mySkillDir");
                    return;
                }
                reloadScriptSkill(parts[2]);
                break;
            case "-a":
                if (parts.length < 3) {
                    System.out.println("用法: /reload -a <家族名>  例: /reload -a app:myAgent");
                    return;
                }
                reloadAgent(parts[2]);
                break;
            case "-bs":
                if (parts.length < 3) {
                    System.out.println("用法: /reload -bs <家族名>  例: /reload -bs aiagent:mySkill");
                    return;
                }
                reloadBaseSkill(parts[2]);
                break;
            default:
                System.out.println("未知flag: " + parts[1] + "，可用: -s (脚本Skill), -a (Agent), -bs (JavaSkill)");
                break;
        }
    }

    private void reloadScriptSkill(String path) {
        Object[] resolved = resolveByPath(path);
        if (resolved == null) { System.out.println("✗ 路径解析失败: " + path); return; }
        TLBaseModule parent = (TLBaseModule) resolved[0];
        String skillDir = (String) resolved[1];

        TLMsg msg = createMsg()
                .setAction(AGENT_RELOADSKILL)
                .setParam("skillDir", skillDir);
        msg.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg result = putMsg(parent, msg);
        if (result != null && result.parseBoolean(RESULT, false)) {
            System.out.println("✓ Skill 已重载: " + path + " (parent=" + parent.getName() + ")");
        } else {
            String err = result != null ? result.getStringParam("error", "未知错误") : "无响应";
            System.out.println("✗ 重载失败: " + err);
        }
    }

    private void reloadAgent(String path) {
        Object[] resolved = resolveByPath(path);
        if (resolved == null) { System.out.println("✗ 路径解析失败: " + path); return; }
        TLBaseModule parent = (TLBaseModule) resolved[0];
        String agentName = (String) resolved[1];

        TLMsg msg = createMsg()
                .setAction(AGENT_RELOADAGENT)
                .setParam(AI_P_AGENTNAME, agentName);
        msg.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg result = putMsg(parent, msg);
        if (result != null && result.parseBoolean(RESULT, false)) {
            System.out.println("✓ Agent 已重载: " + path + " (parent=" + parent.getName() + ")");
        } else {
            String err = result != null ? result.getStringParam("error", "未知错误") : "无响应";
            System.out.println("✗ 重载失败: " + err);
        }
    }

    private void reloadBaseSkill(String path) {
        Object[] resolved = resolveByPath(path);
        if (resolved == null) { System.out.println("✗ 路径解析失败: " + path); return; }
        TLBaseModule parent = (TLBaseModule) resolved[0];
        String skillName = (String) resolved[1];

        TLMsg msg = createMsg()
                .setAction(AGENT_RELOADSKILL)
                .setParam(AI_P_SKILLNAME, skillName);
        msg.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg result = putMsg(parent, msg);
        if (result != null && result.parseBoolean(RESULT, false)) {
            System.out.println("✓ BaseSkill 已重载: " + path + " (parent=" + parent.getName() + ")");
        } else {
            String err = result != null ? result.getStringParam("error", "未知错误") : "无响应";
            System.out.println("✗ 重载失败: " + err);
        }
    }

    // ======================== 家族名解析 ========================

    /** 家族名 "moduleFactory:aiagent:myAgent" → [直接父实例, 目标短名]。单段时父=master */
    private Object[] resolveByPath(String path) {
        if (path == null || path.isEmpty()) return null;
        int lastColon = path.lastIndexOf(':');
        // 单段：父=master agent
        if (lastColon < 0) {
            TLBaseModule parent = findAgentInstance(agentModule);
            if (parent == null) return null;
            return new Object[]{parent, path};
        }
        String targetName = path.substring(lastColon + 1);
        String parentPath = path.substring(0, lastColon);
        TLBaseModule parent = lookupByFamilyName(parentPath);
        if (parent == null) return null;
        return new Object[]{parent, targetName};
    }

    /** 按家族名从 registry 精确查找模块实例。工厂根节点需自行注册到 registry。 */
    private TLBaseModule lookupByFamilyName(String familyName) {
        TLMsg result = putMsg(DEFAULTMODULEREGISTRY, createMsg().setAction(REGISTRY_GET)
                .setParam(REGISTRY_P_KEY, familyName));
        if (result != null) {
            Object inst = result.getParam(INSTANCE);
            if (inst instanceof TLBaseModule) return (TLBaseModule) inst;
        }
        return null;
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

            case "/approve":
                System.out.println("当前没有等待审批的操作");
                break;

            case "/clear":
                putMsg(agentModule, createMsg()
                        .setAction(AGENT_CLEARCONTEXT)
                        .setParam(AI_P_SESSIONID, sessionId));
                System.out.println("✓ 上下文已清除");
                break;

            case "/resume":
                // 优先处理 mid-loop 断点恢复（启动时检测到的未完成会话）
                if (pendingCheckpointSessionId != null) {
                    String resumeSid = pendingCheckpointSessionId;
                    String resumeMsg2 = pendingCheckpointUserMessage;
                    pendingCheckpointSessionId = null;
                    pendingCheckpointUserMessage = null;
                    sessionId = resumeSid;
                    // 走和普通 chat 相同的异步回调模式：putMsgNoWait + onChatDone
                    TLMsg m = createMsg()
                            .setAction(AGENT_CHAT)
                            .setParam(AI_P_SESSIONID, resumeSid)
                            .setParam("userId", userId)
                            .setParam(AI_P_USERMESSAGE, resumeMsg2)
                            .setParam("resume", true);
                    m.setSystemParam(TASKRESULTFOR, this);
                    m.setSystemParam(TASKRESULTACTION, "onChatDone");
                    IObject target = (IObject) getModule(agentModule);
                    if (target != null) {
                        busy = true;
                        currentStart = System.currentTimeMillis();
                        putMsgNoWait(target, m);
                        System.out.println("✓ 正在从断点恢复会话 " + resumeSid + " ...");
                    } else {
                        System.out.println("✗ 找不到 Agent 模块: " + agentModule);
                    }
                    break;
                }
                // 原有逻辑：恢复已完成的会话上下文
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

            case "/sessions":
                listAllSessions();
                break;

            case "/continue":
                continueSession(cmd);
                break;

            case "/stream":
                streamMode = !streamMode;
                System.out.println("✓ 流式模式: " + (streamMode ? "开启" : "关闭"));
                break;

            case "/thinking":
                reasoningCollapsed = !reasoningCollapsed;
                System.out.println("✓ 推理展示: " + (reasoningCollapsed ? "折叠" : "展开"));
                break;

            case "/?":
            case "/help":
                handleHelp();
                break;

            case "/install":
                printInstallUsage();
                break;
            case "/uninstall":
                printUninstallUsage();
                break;
            case "/reload":
                printReloadUsage();
                break;

            case "/agents":
            case "/skills":
                handleListModules(cmd);
                break;

            default:
                if (cmd.startsWith("/eval")) {
                    handleEval(cmd);
                } else if (cmd.startsWith("/?") || cmd.startsWith("/help ")) {
                    handleHelp();
                } else if (cmd.startsWith("/install ")) {
                    handleInstall(cmd);
                } else if (cmd.startsWith("/uninstall ")) {
                    handleUninstall(cmd);
                } else if (cmd.startsWith("/reload ")) {
                    handleReload(cmd);
                } else if (cmd.startsWith("/agents ")) {
                    handleListModules(cmd);
                } else if (cmd.startsWith("/skills ")) {
                    handleListModules(cmd);
                } else if (cmd.startsWith("/session ")) {
                    sessionId = cmd.substring(9).trim();
                    System.out.println("✓ 会话ID切换为: " + sessionId);
                } else if (cmd.startsWith("/continue ")) {
                    continueSession(cmd);
                } else if (cmd.startsWith("/thinking ")) {
                    String mode = cmd.substring(10).trim();
                    if (mode.equals("off") || mode.equals("prompt") || mode.equals("native") || mode.equals("auto")) {
                        // 通过设置 Agent 的 params 来切换推理模式（运行时覆盖）
                        System.out.println("✓ 推理模式切换为: " + mode + "（下次对话生效）");
                    } else {
                        System.out.println("用法: /thinking [off|prompt|native|auto]");
                    }
                } else {
                    System.out.println("未知命令: " + cmd);
                    // 前缀模糊匹配相近命令
                    String prefix = cmd.toLowerCase();
                    java.util.List<String> suggestions = new java.util.ArrayList<>();
                    java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
                    for (String c : COMMAND_REGISTRY.keySet()) {
                        if (c.toLowerCase().startsWith(prefix) && seen.add(c)) {
                            suggestions.add(c);
                        }
                    }
                    if (!suggestions.isEmpty()) {
                        System.out.println("相近命令: " + String.join(", ", suggestions));
                    }
                    System.out.println("输入 /help 查看所有命令");
                }
                break;
        }
        return false;
    }
}
