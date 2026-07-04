package cn.tianlong.java.demo.aiagent;

import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLAppStartUp;

import java.util.HashMap;

/**
 * AI Agent框架Demo启动入口。
 * 遵循标准TLAppStartUp模式，启动后自动运行所有测试。
 *
 * 使用方式：
 * 1. 先修改 conf/demo/aiagent/aiagent_config.xml 中的 apiKey
 * 2. 运行本类的 main() 方法
 * 3. 观察控制台输出的 [TEST] 日志
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public class AiAgentDemoStartup extends TLAppStartUp {

    public static TLObjectFactory appFactory;

    public AiAgentDemoStartup() {
        super();
    }

    public AiAgentDemoStartup(String name) {
        super(name);
    }

    public static void main(String[] args) {
        if (args != null && args.length > 0) {
            TLAppStartUp.main(args);
            return;
        }

        HashMap<String, String> argsMap = new HashMap<>();
        argsMap.put("appName", "aiAgentDemo");
        argsMap.put("configPath", CLASSPATH + "/conf/demo/aiagent/");
        argsMap.put("factoryConfigFile", "moduleFactory_config.xml");
        argsMap.put("configFile", "startup_config.xml");

        AiAgentDemoStartup startup = new AiAgentDemoStartup("aiAgentDemoStartup");
        startup.startup(argsMap);

        // 保存工厂引用供外部使用
        appFactory = startup.getFactory();

        System.out.println("\n========================================");
        System.out.println("  AI Agent Demo 启动完成");
        System.out.println("  查看日志中的 [TEST] 标记获取测试结果");
        System.out.println("========================================\n");
    }
}
