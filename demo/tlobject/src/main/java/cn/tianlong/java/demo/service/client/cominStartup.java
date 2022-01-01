package cn.tianlong.java.demo.service.client;

import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLAppStartUp;

/**
 * 创建日期：2021/2/1010:29
 * 描述:
 * 作者:tianlong
 */
public class cominStartup extends TLAppStartUp {
    public static   TLObjectFactory appFactory ;
    public cominStartup(String name) {
        super( name);
    }
    public static void  main (String[] args ) {
        startModule (args );
    }
    @Override
    protected void run() {

    }
    public static TLObjectFactory   startModule (String[] args  ) {
        String[] appArgs = {CLASSPATH+"/conf/demo/service/client/","moduleFactory_config.xml","type/demoappstart_comin.xml","demo"};
        cominStartup instance = new cominStartup("cominStartup");
        appFactory=  instance.startup(appArgs);
        return appFactory ;
    }
}
