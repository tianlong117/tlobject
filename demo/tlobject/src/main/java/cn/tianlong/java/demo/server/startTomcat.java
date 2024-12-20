package cn.tianlong.java.demo.server;

import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLAppStartUp;

import java.util.HashMap;

public class startTomcat extends TLAppStartUp {
    public static TLObjectFactory appFactory ;
    public startTomcat(String name) {
        super( name);
    }
    public static void  main (String[] args ) {
        HashMap<String,Object> argsMap =new HashMap<>() ;
        argsMap.put("appName","demo0");
        argsMap.put("configPath",CLASSPATH+"/conf/demo/tomcatserver/");
        argsMap.put("factoryConfigFile","tomcatserver_factory_config.xml");
        startTomcat instance = new startTomcat("tomcatServer");
        appFactory=  instance.startup(argsMap);
    }

}
