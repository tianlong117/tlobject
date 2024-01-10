package cn.tianlong.java.demo.server;

import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLAppStartUp;

/**
 * 创建日期：2021/2/1010:29
 * 描述:
 * 作者:tianlong
 */
public class startJetty extends TLAppStartUp {
    public static TLObjectFactory appFactory ;
    public startJetty(String name) {
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
        String[] appArgs = {CLASSPATH+"/conf/demo/jettyserver/","jettyserver_factory_config.xml",null,"serverstart"};
        startJetty instance = new startJetty("serverstart");
        appFactory=  instance.startup(appArgs);
        return appFactory ;
    }
}
