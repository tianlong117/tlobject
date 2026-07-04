package cn.tianlong.java.demo.chatroom.client;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * 聊天室客户端核心模块
 * - 启动 Scanner 线程读取控制台输入
 * - 将命令转为 WebSocket 消息通过 serverProxy 发送
 * - 处理服务端推送的消息
 */
public class ChatClientModule extends TLBaseModule {

    private String username;
    private boolean running = true;
    private IObject serverProxy;

    public ChatClientModule() {
        super();
    }

    public ChatClientModule(String name) {
        super(name);
    }

    public ChatClientModule(String name, TLObjectFactory factory) {
        super(name, factory);
    }

    @Override
    protected TLBaseModule init() {
        // 获取 serverProxy 模块
        serverProxy = (IObject) getModule("serverProxy");
        if (serverProxy == null) {
            putLog("serverProxy not found!", LogLevel.ERROR);
            return null;
        }

        // 启动控制台输入线程
        Thread inputThread = new Thread(this::consoleLoop, "chat-input");
        inputThread.setDaemon(true);
        inputThread.start();

        return this;
    }

    /** 控制台输入循环 */
    private void consoleLoop() {
        printHelp();
        Scanner scanner = new Scanner(System.in);
        while (running) {
            try {
                System.out.print("> ");
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;
                handleCommand(line);
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
        scanner.close();
    }

    /** 解析并执行命令 */
    private void handleCommand(String line) {
        if (line.startsWith("/")) {
            String[] parts = line.substring(1).split("\\s+", 3);
            String cmd = parts[0].toLowerCase();

            switch (cmd) {
                case "login":
                    if (parts.length < 2) {
                        System.out.println("Usage: /login <username>");
                        return;
                    }
                    username = parts[1];
                    Map<String, Object> loginData = new HashMap<>();
                    loginData.put("msgid", "login");
                    loginData.put("username", username);
                    sendToServer(loginData);
                    System.out.println("Logging in as: " + username);
                    break;

                case "send":
                    if (username == null) {
                        System.out.println("Please /login first");
                        return;
                    }
                    if (parts.length < 3) {
                        System.out.println("Usage: /send <user> <message>");
                        return;
                    }
                    Map<String, Object> sendData = new HashMap<>();
                    sendData.put("msgid", "send");
                    sendData.put("fromUser", username);
                    sendData.put("toUser", parts[1]);
                    sendData.put("content", parts[2]);
                    sendToServer(sendData);
                    break;

                case "users":
                    if (username == null) {
                        System.out.println("Please /login first");
                        return;
                    }
                    Map<String, Object> listData = new HashMap<>();
                    listData.put("msgid", "listUsers");
                    listData.put("fromUser", username);
                    sendToServer(listData);
                    break;

                case "quit":
                case "exit":
                    running = false;
                    System.out.println("Goodbye!");
                    break;

                case "help":
                    printHelp();
                    break;

                default:
                    System.out.println("Unknown command: " + cmd);
                    printHelp();
            }
        } else if (username != null) {
            // 无前缀的文本当作发送给自己（回声测试）
            Map<String, Object> echoData = new HashMap<>();
            echoData.put("msgid", "send");
            echoData.put("fromUser", username);
            echoData.put("toUser", username);
            echoData.put("content", line);
            sendToServer(echoData);
        } else {
            System.out.println("Please /login first, or type /help");
        }
    }

    /** 发送消息到服务端（使用 Map，框架会自动转为 JSON） */
    private void sendToServer(Map<String, Object> data) {
        putMsg(serverProxy, createMsg()
                .setAction(WEBSOCKET_PUT)
                .setParam(WEBSOCKET_P_CONTENT, data));
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case "receiveMsg":
                handleReceive(msg);
                break;
            case "receiveUserlist":
                handleUserlist(msg);
                break;
            case "receiveSystem":
                handleSystem(msg);
                break;
            default:
        }
        return null;
    }

    /** 处理收到的消息（框架已解析 JSON 为 Map，直接从 msg 参数读取） */
    private void handleReceive(TLMsg msg) {
        String fromUser = msg.getStringParam("fromUser", null);
        String text = msg.getStringParam("content", null);
        if (fromUser != null && text != null) {
            System.out.println("\r[" + fromUser + "] " + text);
        } else {
            // fallback: try to get content as raw string
            String content = msg.getStringParam(WEBSOCKET_P_CONTENT, "");
            System.out.println("\r[message] " + content);
        }
        System.out.print("> ");
    }

    /** 处理用户列表 */
    private void handleUserlist(TLMsg msg) {
        String users = msg.getStringParam("users", null);
        if (users != null) {
            System.out.println("\rOnline users: " + users);
        } else {
            // fallback: try from content
            String content = msg.getStringParam(WEBSOCKET_P_CONTENT, "");
            System.out.println("\rOnline users: " + content);
        }
        System.out.print("> ");
    }

    /** 处理系统消息 */
    private void handleSystem(TLMsg msg) {
        String text = msg.getStringParam("content", null);
        if (text != null) {
            System.out.println("\r[system] " + text);
        } else {
            String content = msg.getStringParam(WEBSOCKET_P_CONTENT, "");
            System.out.println("\r[system] " + content);
        }
        System.out.print("> ");
    }

    private void printHelp() {
        System.out.println("===== TLObject Chat Client =====");
        System.out.println("  /login <name>   - Login with username");
        System.out.println("  /send <user> <msg> - Send message to user");
        System.out.println("  /users          - List online users");
        System.out.println("  /quit           - Exit");
        System.out.println("  /help           - Show this help");
        System.out.println("================================");
    }
}
