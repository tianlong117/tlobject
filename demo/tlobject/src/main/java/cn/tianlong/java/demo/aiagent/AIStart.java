package cn.tianlong.java.demo.aiagent;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLAppStartUp;

import java.util.HashMap;

/**
 * AI 聊天启动入口。继承 TLAppStartUp，run() 中触发 TLChatConsole 模块。
 *
 * 运行方式: 直接运行 main()
 * - 默认: 仅启动控制台交互（chatConsole）
 * - -web: 同时启动 Web 交互窗口（http://localhost:8080/webui/chat.html），
 *         使用 moduleFactory_chat_web_config.xml（include chat 配置 + webui/jettyServer）
 * - -u userId: 指定控制台登录用户
 *
 * @author tianlong
 * @date 2026/7/12
 */
public class AIStart extends TLAppStartUp implements TLAiAgentParamString {

    public AIStart(String name) {
        super(name);
    }

    private String consoleUser = null;

    public static void main(String[] args) {
        String configPath = "/conf/demo/aiagent/";
        String factoryConfigPath = AIStart.class.getResource(configPath).getPath();
        System.setProperty("log4j.configurationFile", factoryConfigPath + "log4j2.xml");

        String user = null;
        boolean web = false;
        for (int i = 0; i < args.length; i++) {
            if ("-u".equals(args[i]) && i + 1 < args.length) {
                user = args[++i];
            } else if ("-web".equals(args[i])) {
                web = true;
            }
        }

        HashMap<String, String> argsMap = new HashMap<>();
        argsMap.put("appName", "aiStart");
        argsMap.put("configPath", CLASSPATH + configPath);
        argsMap.put("factoryConfigFile", web ? "moduleFactory_chat_web_config.xml" : "moduleFactory_chat_config.xml");

        AIStart instance = new AIStart("aiStart");
        instance.consoleUser = user;
        TLObjectFactory factory = instance.startup(argsMap);
        factory.shutdown();
    }

    @Override
    protected void run() {
        TLMsg startMsg = createMsg().setAction("startChat");
        if (consoleUser != null) startMsg.setParam("userId", consoleUser);
        putMsg("chatConsole", startMsg);
    }
}
