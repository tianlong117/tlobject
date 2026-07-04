package cn.tianlong.java.demo.chatroom.server;

import cn.tianlong.tlobject.modules.TLAppStartUp;

import java.util.HashMap;

/**
 * ChatRoom Server 入口
 * 启动参数: -d conf/demo/chatroom/server/ -m chatroom_moduleFactory_config.xml
 */
public class startChatServer extends TLAppStartUp {

    public startChatServer(String name) {
        super(name);
    }

    public static void main(String[] args) {

        HashMap<String,String> argsMap =new HashMap<>() ;
        argsMap.put("appName","chatServer");
        argsMap.put("configPath",CLASSPATH+"/conf/demo/chatroom/server/");
        argsMap.put("factoryConfigFile","chatroom_moduleFactory_config.xml");
        argsMap.put("configFile","demoappstart.xml");
        TLAppStartUp.main0(argsMap);
    }
}
