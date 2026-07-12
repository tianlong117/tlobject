package cn.tianlong.java.demo.aiagent;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLAppStartUp;

import java.util.HashMap;

/**
 * AI 聊天命令行启动入口。继承 TLAppStartUp，run() 中触发 TLChatConsole 模块。
 *
 * 运行方式: 直接运行 main()
 *
 * @author tianlong
 * @date 2026/7/12
 */
public class AIStart extends TLAppStartUp implements TLAiAgentParamString {

    public AIStart(String name) {
        super(name);
    }

    public static void main(String[] args) {
        String configPath = "/conf/demo/aiagent/";
        String factoryConfigPath = AIStart.class.getResource(configPath).getPath();
        System.setProperty("log4j.configurationFile", factoryConfigPath + "log4j2.xml");

        HashMap<String, String> argsMap = new HashMap<>();
        argsMap.put("appName", "aiStart");
        argsMap.put("configPath", CLASSPATH + configPath);
        argsMap.put("factoryConfigFile", "moduleFactory_chat_config.xml");

        AIStart instance = new AIStart("aiStart");
        TLObjectFactory factory = instance.startup(argsMap);
        factory.shutdown();
    }

    @Override
    protected void run() {
        putMsg("chatConsole", createMsg().setAction("startChat"));
    }
}
