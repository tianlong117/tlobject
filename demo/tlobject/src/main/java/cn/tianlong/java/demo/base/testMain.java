package cn.tianlong.java.demo.base;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLAppStartUp;

import java.util.HashMap;

/**
 * 创建日期：2021/2/1010:29
 * 描述:
 * 作者:tianlong
 */
public class testMain extends TLAppStartUp {
    public static   TLObjectFactory appFactory ;
    public testMain(String name) {
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

    public static TLObjectFactory   startModule (HashMap<String,String> configMap  ) {
        HashMap<String,Object> argsMap =new HashMap<>() ;
        argsMap.put("appName","demo0");
        argsMap.put("configPath",CLASSPATH+"/conf/demo/base/");
        argsMap.put("factoryConfigFile","moduleFactory_config.xml");
        if(configMap !=null)
            argsMap.putAll(configMap);
        testMain instance = new testMain("startup");
        appFactory=  instance.startup(argsMap);
        return appFactory ;
    }
    @Override
    protected void run() {
        testMsg();
    }

    private void testMsg() {
        TLMsg msg =createMsg();
        Double i =100D;
        msg.setParam("p1",i) ;
        int  p1 =msg.getIntParam("p1",  0);
       int  p2 =msg.parseInt("p1",  0);
    }
}
