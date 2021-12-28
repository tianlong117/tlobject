package cn.tianlong.java.demo.service.client;

import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLAppStartUp;

/**
 * 创建日期：2021/2/1010:29
 * 描述:
 * 作者:tianlong
 */
public class clientStartup extends TLAppStartUp {
    public static   TLObjectFactory appFactory ;
    public clientStartup(String name) {
        super( name);
    }
    public static void  main (String[] args ) {
        startModule (args );
    }
    @Override
    protected void run() {
  //      moduleFactory.shutdown(1);
  //      putMsg("wife",createMsg().setAction("sing"));
    }
    public static TLObjectFactory   startModule (String[] args  ) {
        String[] appArgs = {CLASSPATH+"/conf/demo/service/client/","moduleFactory_config.xml","demoappstart.xml","demo"};
        clientStartup instance = new clientStartup("clientStartup");
        appFactory=  instance.startup(appArgs);
        return appFactory ;
    }
}
