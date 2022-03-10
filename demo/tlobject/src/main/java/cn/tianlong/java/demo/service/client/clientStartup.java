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

    public static TLObjectFactory   startModule (String[] args  ) {
        String[] appArgs = {CLASSPATH+"/conf/demo/service/client/","moduleFactory_client_config.xml",null,"demo"};
        clientStartup instance = new clientStartup("cominStartup");
        appFactory=  instance.startup(appArgs);
        return appFactory ;
    }
    @Override
    protected void run() {
        putMsg("clientInterfaceModule",createMsg().setAction("startRun"));
    }
}
