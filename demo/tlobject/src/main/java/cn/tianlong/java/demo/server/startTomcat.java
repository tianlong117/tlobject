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
        HashMap<String,String> argsMap = null;
        if(args !=null && args.length >0){
            TLAppStartUp.main(args);
            return;
        }
        startModule (argsMap );
    }
    @Override
    protected void run() {

    }
    public static TLObjectFactory   startModule (HashMap<String,String> configMap  ) {
        HashMap<String,Object> argsMap =new HashMap<>() ;
        argsMap.put("appName","demo0");
        argsMap.put("configPath",CLASSPATH+"/conf/demo/tomcatserver/");
        argsMap.put("factoryConfigFile","tomcatserver_factory_config.xml");
        if(configMap !=null)
            argsMap.putAll(configMap);
        startTomcat instance = new startTomcat("tomcatServer");
        appFactory=  instance.startup(argsMap);
        return appFactory ;
    }
}
