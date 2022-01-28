package cn.tianlong.java.jettyserver;

import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLAppStartUp;

/**
 jettyserver 演示嵌入jetty
 */
public class serverstart extends TLAppStartUp {
    public static TLObjectFactory appFactory ;
    public serverstart(String name) {
        super( name);
    }
    public static void  main (String[] args ) {
        startModule (args );
    }

    public static TLObjectFactory startModule (String[] args  ) {
        String[] appArgs = {CLASSPATH+"/conf/jettyserver/","jettyserver_factory_config.xml",null,"serverstart"};
        serverstart instance = new serverstart("serverstart");
        appFactory=  instance.startup(appArgs);
        return appFactory ;
    }
}
