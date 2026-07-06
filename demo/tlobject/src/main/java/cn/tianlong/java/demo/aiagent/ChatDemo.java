package cn.tianlong.java.demo.aiagent;

import cn.tianlong.java.demo.task.Main;
import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLAppStartUp;

import java.util.HashMap;
import java.util.Scanner;

/**
 * 交互式Chat Demo — 人与Agent对话。
 * 启动后进入控制台对话模式，输入消息即可与AI交流。
 *
 * 命令:
 *   /exit   — 退出
 *   /clear  — 清除当前会话上下文
 *   /stream — 切换流式/非流式模式
 *   /model <name> — 切换模型
 *
 * 运行方式: 直接运行 main()
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public class ChatDemo implements TLAiAgentParamString {

    private static String sessionId = "console_user";
    private static boolean streamMode = false;
    private static int streamDelayMs = 30;  // 流式每字延迟(毫秒)，0=最快
    private static final String AGENT_MODULE = "aiagent_master";

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════╗");
        System.out.println("║   AI Agent 交互式 Chat Demo     ║");
        System.out.println("║   输入消息开始对话，/exit 退出   ║");
        System.out.println("╚══════════════════════════════════╝\n");
        String configPath = "/conf/demo/aiagent/" ;
        // 指定log4j2配置文件
        String factoryConfigPath = Main.class.getResource(configPath).getPath();
        System.setProperty("log4j.configurationFile", factoryConfigPath+"log4j2.xml");



        // 启动框架
        String configPathStr = CLASSPATH+ configPath;
        HashMap<String, String> argsMap = new HashMap<>();
        argsMap.put("appName", "chatDemo");
        argsMap.put("configPath", configPathStr);
        argsMap.put("factoryConfigFile", "moduleFactory_chat_config.xml");
        argsMap.put("configFile", "startup_config.xml");

        TLAppStartUp startup = new TLAppStartUp("chatDemoStartup");
        startup.startup(argsMap);
        TLObjectFactory factory = startup.getFactory();

        // 打印已注册的Skill
        TLMsg listMsg = factory.createMsg().setAction(AGENT_LISTSKILLS);
        TLMsg listResult = factory.putMsg(AGENT_MODULE, listMsg);
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
        System.out.println("\nAI Agent 就绪，开始对话吧！\n");

        // 对话循环
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("你 > ");
                String input = scanner.nextLine().trim();

                if (input.isEmpty()) continue;

                // 处理命令
                if (input.startsWith("/")) {
                    if (handleCommand(input, factory)) break;
                    continue;
                }

                // 发送消息
                long start = System.currentTimeMillis();

                if (streamMode) {
                    // 流式：发异步请求，轮询逐字打印
                    TLMsg streamMsg = factory.createMsg()
                            .setAction(AGENT_CHATSTREAM)
                            .setParam(AI_P_SESSIONID, sessionId)
                            .setParam(AI_P_USERMESSAGE, input)
                            .setParam(RESULTFOR, "streamCallback")
                            .setParam(RESULTACTION, "onStreamChunk");
                    factory.putMsg("streamCallback",
                            factory.createMsg().setAction("resetStream"));
                    factory.putMsg(AGENT_MODULE, streamMsg);

                    System.out.print("AI > ");
                    int printed = 0;
                    while (true) {
                        TLMsg buf = factory.putMsg("streamCallback",
                                factory.createMsg().setAction("getStreamBuffer"));
                        String content = buf.getStringParam("content", "");
                        boolean done = buf.parseBoolean("streamDone", false);
                        // 逐字打印新增内容
                        while (printed < content.length()) {
                            System.out.print(content.charAt(printed));
                            printed++;
                            if (streamDelayMs > 0) {
                                try { Thread.sleep(streamDelayMs); } catch (InterruptedException e) { break; }
                            }
                        }
                        if (done && printed >= content.length()) break;
                        try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                    }
                    System.out.println("\n    (" + (System.currentTimeMillis() - start) + "ms)");
                } else {
                    TLMsg response = factory.putMsg(AGENT_MODULE,
                            factory.createMsg().setAction(AGENT_CHAT)
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
                System.out.println();
            }
        }

        System.out.println("再见！");
    }

    private static boolean handleCommand(String cmd, TLObjectFactory factory) {
        switch (cmd.toLowerCase()) {
            case "/exit":
            case "/quit":
                return true;

            case "/clear":
                TLMsg clearMsg = factory.createMsg()
                        .setAction(AGENT_CLEARCONTEXT)
                        .setParam(AI_P_SESSIONID, sessionId);
                factory.putMsg(AGENT_MODULE, clearMsg);
                System.out.println("✓ 上下文已清除\n");
                break;

            case "/stream":
                streamMode = !streamMode;
                System.out.println("✓ 流式模式: " + (streamMode ? "开启" : "关闭") + "\n");
                break;

            case "/speed":
                System.out.println("✓ 当前流式延迟: " + streamDelayMs + "ms/字\n");
                break;

            default:
                if (cmd.startsWith("/model ")) {
                    String model = cmd.substring(7).trim();
                    TLMsg modelMsg = factory.createMsg()
                            .setAction(AGENT_SETPROVIDER)
                            .setParam(AI_P_PROVIDER, M_LLMPROVIDER_OPENAI);
                    factory.putMsg(AGENT_MODULE, modelMsg);
                    System.out.println("✓ 模型切换请求已发送\n");
                } else if (cmd.startsWith("/session ")) {
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
