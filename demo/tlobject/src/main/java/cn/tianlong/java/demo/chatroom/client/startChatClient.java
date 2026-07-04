package cn.tianlong.java.demo.chatroom.client;

import cn.tianlong.tlobject.modules.TLAppStartUp;

import java.util.HashMap;

/**
 * ChatRoom Client 入口
 * 启动参数: -d conf/demo/chatroom/client/ -m chatroom_client_moduleFactory_config.xml
 */
public class startChatClient extends TLAppStartUp {

    public startChatClient(String name) {
        super(name);
    }

    public static void main(String[] args) {

        HashMap<String,String> argsMap =new HashMap<>() ;
        argsMap.put("appName","chatClient");
        argsMap.put("configPath",CLASSPATH+"/conf/demo/chatroom/client/");
        argsMap.put("factoryConfigFile","chatroom_client_moduleFactory_config.xml");
      //  argsMap.put("configFile","demoappstart.xml");
        TLAppStartUp.main0(argsMap);
    }
}
