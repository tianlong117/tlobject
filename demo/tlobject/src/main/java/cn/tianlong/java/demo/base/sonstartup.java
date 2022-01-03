package cn.tianlong.java.demo.base;

import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLAppStartUp;

import java.util.HashMap;

/**
 * 创建日期：2021/2/1010:29
 * 描述:
 * 作者:tianlong
 */
public class sonstartup extends TLAppStartUp {
    public static   TLObjectFactory appFactory ;
    public sonstartup(String name) {
        super( name);
    }
    public sonstartup(String name, TLObjectFactory moduleFactory) {
        super(name, moduleFactory);
    }
    public static void  main (String[] args ) {
        HashMap<String,String> argsMap = null;
        if(args !=null && args.length >0){
            TLAppStartUp.main(args);
            return;
        }
        startModule (argsMap );
    }
    public static TLObjectFactory   startModule (HashMap<String,String> configMap  ) {
        HashMap<String,Object> argsMap =new HashMap<>() ;
        argsMap.put("appName","demo1");
        argsMap.put("configPath",CLASSPATH+"/conf/demo/base/");
        argsMap.put("factoryConfigFile","moduleFactory1_config.xml");
        argsMap.put("configFile","demoappstart1.xml");
        if(configMap !=null)
            argsMap.putAll(configMap);
        startup instance = new startup("startup");
        appFactory=  instance.startup(argsMap);
        return appFactory ;
    }
}
