package cn.tianlong.java.demo.jettyserver;

import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLAppStartUp;

/**
 * 创建日期：2021/2/1010:29
 * 描述:
 * 作者:tianlong
 */
public class startup extends TLAppStartUp {
    public static TLObjectFactory appFactory ;
    public startup(String name) {
        super( name);
    }
    public static void  main (String[] args ) {
        startModule (args );
    }
    @Override
    protected void run() {
  //      moduleFactory.shutdown(1);
  //      putMsg("wife@startup1",createMsg().setAction("sing"));
    }
    public static TLObjectFactory startModule (String[] args  ) {
       String[] appArgs = {CLASSPATH+"/conf/demo/jetty/","moduleFactory_config.xml",null,"jettyserver"};
        startup instance = new startup("serverStartup");
        appFactory=  instance.startup(appArgs);
        return appFactory ;
    }
}
