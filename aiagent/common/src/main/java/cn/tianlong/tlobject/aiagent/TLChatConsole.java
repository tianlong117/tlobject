package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.Scanner;

/**
 * AI 通用命令行交互模块。通过消息触发，可被任何应用引用。
 *
 * 消息接口:
 *   startChat — 启动交互循环（当前线程阻塞，/exit 时返回）
 *   stopChat  — 外部停止循环
 *
 * 配置参数 (XML params):
 *   agentModule — 目标 Agent 模块名，默认 "aiagent"
 *   sessionId   — 默认会话 ID，默认 "console_user"
 *   streamMode  — 默认流式模式，默认 "false"
 *   prompt      — 输入提示符，默认 "你 > "
 *
 * 命令:
 *   /exit    — 退出
 *   /clear   — 清除当前会话上下文
 *   /stream  — 切换流式/非流式模式
 *   /session <id> — 切换会话ID
 *
 * @author tianlong
 * @date 2026/7/12
 */
public class TLChatConsole extends TLBaseModule implements TLAiAgentParamString {

    private String agentModule = "aiagent";
    private String sessionId = "console_user";
    private boolean streamMode = false;
    private String prompt = "你 > ";
    private volatile boolean running = false;

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
        }
        return null;
    }

    private void startConsole() {
        System.out.println("╔══════════════════════════════════╗");
        System.out.println("║   AI Agent 交互式 Chat          ║");
        System.out.println("║   输入消息开始对话，/exit 退出   ║");
        System.out.println("╚══════════════════════════════════╝\n");

        printSkills();
        System.out.println("\nAI Agent 就绪，开始对话吧！\n");

        running = true;
        try (Scanner scanner = new Scanner(System.in)) {
            while (running) {
                System.out.print(prompt);
                String input = scanner.nextLine().trim();

                if (input.isEmpty()) continue;

                if (input.startsWith("/")) {
                    if (handleCommand(input)) break;
                    continue;
                }

                long start = System.currentTimeMillis();

                if (streamMode) {
                    doStreamChat(input, start);
                } else {
                    doChat(input, start);
                }
                System.out.println();
            }
        }

        System.out.println("再见！");
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

    private void doChat(String input, long start) {
        TLMsg response = putMsg(agentModule,
                createMsg().setAction(AGENT_CHAT)
                        .setParam(AI_P_SESSIONID, sessionId)
                        .setParam(AI_P_USERMESSAGE, input));
        if (response != null) {
            String aiResponse = response.getStringParam(AI_P_RESPONSE, "");
            if (response.parseBoolean(RESULT, false) && !aiResponse.isEmpty()) {
                System.out.println("AI > " + aiResponse);
                System.out.println("    (" + (System.currentTimeMillis() - start) + "ms)");
            } else {
                System.out.println("AI > [错误] " + (aiResponse.isEmpty() ? "空响应" : aiResponse));
            }
        }
    }

    private void doStreamChat(String input, long start) {
        putMsg("streamCallback", createMsg().setAction("resetStream"));
        putMsg(agentModule,
                createMsg().setAction(AGENT_CHATSTREAM)
                        .setParam(AI_P_SESSIONID, sessionId)
                        .setParam(AI_P_USERMESSAGE, input)
                        .setParam(RESULTFOR, "streamCallback")
                        .setParam(RESULTACTION, "onStreamChunk"));

        System.out.print("AI > ");
        int printed = 0;
        while (true) {
            TLMsg buf = putMsg("streamCallback", createMsg().setAction("getStreamBuffer"));
            String content = buf.getStringParam("content", "");
            boolean done = buf.parseBoolean("streamDone", false);
            while (printed < content.length()) {
                System.out.print(content.charAt(printed));
                printed++;
            }
            if (done && printed >= content.length()) break;
            try { Thread.sleep(10); } catch (InterruptedException e) { break; }
        }
        System.out.println("\n    (" + (System.currentTimeMillis() - start) + "ms)");
    }

    private boolean handleCommand(String cmd) {
        switch (cmd.toLowerCase()) {
            case "/exit":
            case "/quit":
                running = false;
                return true;

            case "/clear":
                putMsg(agentModule, createMsg()
                        .setAction(AGENT_CLEARCONTEXT)
                        .setParam(AI_P_SESSIONID, sessionId));
                System.out.println("✓ 上下文已清除\n");
                break;

            case "/stream":
                streamMode = !streamMode;
                System.out.println("✓ 流式模式: " + (streamMode ? "开启" : "关闭") + "\n");
                break;

            default:
                if (cmd.startsWith("/session ")) {
                    sessionId = cmd.substring(9).trim();
                    System.out.println("✓ 会话ID切换为: " + sessionId + "\n");
                } else {
                    System.out.println("未知命令: " + cmd);
                    System.out.println("可用: /exit /clear /stream /session <id>\n");
                }
                break;
        }
        return false;
    }
}
