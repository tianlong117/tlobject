package cn.tianlong.java.demo.service.server;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLAppStartUp;

public class serverStartup extends TLAppStartUp {
    public static   TLObjectFactory appFactory ;
    public serverStartup(String name) {
        super( name);
    }
    public static void  main (String[] args ) {
        startModule (args );
    }
    public static TLObjectFactory startModule (String[] args  ) {
        String[] appArgs = {CLASSPATH+"/conf/demo/service/server/","service_moduleFactory_config.xml",null,"service"};
        serverStartup main = new serverStartup("service");
        appFactory = main.startup(appArgs);
        return appFactory ;
    }
}
