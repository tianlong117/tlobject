package cn.tianlong.java.demo.uniagent;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.TLAppStartUp;
import cn.tianlong.tlobject.uniagent.*;

import java.util.*;

/**
 * UniAgent 交互式对话 Demo。
 *
 * 启动后自动加载工厂配置，进入交互式对话循环。
 * 工具名 = 模块名，全部通过 putMsg 消息通信。
 *
 * 命令:
 *   直接输入文本 → 发送给 Agent
 *   /stream       → 切换流式/非流式
 *   /clear        → 清除上下文
 *   /tools        → 列出可用工具
 *   /session <id> → 切换会话
 *   /model <name> → 切换模型
 *   /exit         → 退出
 */
public class UniAgentChatDemo extends TLAppStartUp implements UniAgentParamString {

    private String agentModule = "uniagent";
    private String sessionId = "demo-" + System.currentTimeMillis() % 100000;
    private boolean streamMode = false;
    private String model = null;
    private final Scanner scanner = new Scanner(System.in);

    public UniAgentChatDemo(String name) {
        super(name);
    }

    public static void main(String[] args) {
        String configPath = "/conf/demo/uniagent/";
        String fullPath = UniAgentChatDemo.class.getResource(configPath).getPath();
        System.setProperty("log4j.configurationFile", fullPath + "log4j2.xml");

        HashMap<String, String> argsMap = new HashMap<>();
        argsMap.put("appName", "uniagentDemo");
        argsMap.put("configPath", CLASSPATH + configPath);
        argsMap.put("factoryConfigFile", "moduleFactory_uniagent_config.xml");


        UniAgentChatDemo demo = new UniAgentChatDemo("uniagentDemo");
        demo.startup(argsMap);

    }

    @Override
    protected void run() {
        printBanner();
        listTools();

        while (true) {
            System.out.print("You > ");
            String input = scanner.nextLine();
            if (input == null || input.isEmpty()) continue;

            if (input.startsWith("/")) {
                if (handleCommand(input)) break;
            } else {
                doChat(input);
            }
        }

        System.out.println("再见！");
    }

    private void printBanner() {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║       UniAgent 交互式对话 Demo        ║");
        System.out.println("║   统一 Agent — 一切皆消息, 一切皆模块  ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println("Session: " + sessionId + " | 输入 /help 查看帮助");
        System.out.println();
    }

    private boolean handleCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "/exit": case "/quit": return true;
            case "/clear":
                putMsg(agentModule, createMsg().setAction(CONTEXT_CLEAR).setParam(AI_P_SESSIONID, sessionId));
                System.out.println("上下文已清除。");
                break;
            case "/stream":
                streamMode = !streamMode;
                System.out.println("流式模式: " + (streamMode ? "ON" : "OFF"));
                break;
            case "/tools": listTools(); break;
            case "/session":
                if (!arg.isEmpty()) { sessionId = arg; System.out.println("Session: " + sessionId); }
                else System.out.println("Session: " + sessionId);
                break;
            case "/model":
                if (!arg.isEmpty()) { model = arg; System.out.println("Model: " + model); }
                else System.out.println("Model: " + (model != null ? model : "default"));
                break;
            case "/help":
                System.out.println("命令: /exit /clear /stream /tools /session <id> /model <name> /help");
                break;
            default:
                System.out.println("未知: " + cmd + " (输入 /help)");
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void doChat(String userMessage) {
        try {
            TLMsg msg = createMsg()
                    .setAction(streamMode ? AGENT_CHATSTREAM : AGENT_CHAT)
                    .setParam(AI_P_SESSIONID, sessionId)
                    .setParam(AI_P_USERMESSAGE, userMessage);
            if (model != null) msg.setParam(AI_P_MODEL, model);

            TLMsg reply = putMsg(agentModule, msg);
            printReply(reply);
        } catch (Exception e) {
            System.out.println("错误: " + e.getMessage());
        }
    }

    private void printReply(TLMsg reply) {
        if (reply == null) { System.out.println("AI > [无回复]"); return; }

        String response = (String) reply.getParam(AI_P_RESPONSE);
        if (response != null) {
            if (streamMode) {
                System.out.print("AI > ");
                for (char c : response.toCharArray()) {
                    System.out.print(c);
                    try { Thread.sleep(15); } catch (InterruptedException e) { break; }
                }
                System.out.println();
            } else {
                System.out.println("AI > " + response);
            }
        }

        if (Boolean.TRUE.equals(reply.getParam(AI_P_TRUNCATED)))
            System.out.println("  ⚠ 达到最大工具调用轮次");

        Object total = reply.getParam(AI_P_TOTALTOKENS);
        if (total != null)
            System.out.printf("  [Tokens: p=%s c=%s t=%s]%n",
                    reply.getParam(AI_P_PROMPTTOKENS),
                    reply.getParam(AI_P_COMPLETIONTOKENS), total);
    }

    private void listTools() {
        try {
            TLMsg reply = putMsg("toolFactory", createMsg().setAction(TOOL_LIST));
            if (reply != null && reply.getParam(AI_P_RESPONSE) != null)
                System.out.println("可用工具: " + reply.getParam(AI_P_RESPONSE));
        } catch (Exception e) {
            System.out.println("(工具列表不可用)");
        }
    }
}
