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
        argsMap.put("configPath",CLASSPATH+"/conf/demo/base/");
        argsMap.put("factoryConfigFile","moduleFactory_config.xml");
        argsMap.put("configFile","demoappstart.xml");
        if(configMap !=null)
            argsMap.putAll(configMap);
        cn.tianlong.java.demo.base.startup instance = new cn.tianlong.java.demo.base.startup("startup");
        appFactory=  instance.startup(argsMap);
        return appFactory ;
    }
}
