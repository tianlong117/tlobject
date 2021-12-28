package cn.tianlong.java.demo.base;

import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLAppStartUp;

/**
 * 创建日期：2021/2/1010:29
 * 描述:
 * 作者:tianlong
 */
public class startup1 extends TLAppStartUp {
    public static   TLObjectFactory appFactory ;
    public startup1(String name) {
        super( name);
    }
    public startup1(String name, TLObjectFactory moduleFactory) {
        super(name, moduleFactory);
    }
    public static void  main (String[] args ) {
        startModule (args );
    }
    public static TLObjectFactory   startModule (String[] args  ) {
        String[] appArgs = {CLASSPATH+"/conf/demo/base/","moduleFactory_config.xml","demoappstart1.xml"};
        startup1 instance = new startup1("startup1");
        appFactory=  instance.startup(appArgs);
        return appFactory ;
    }
}
