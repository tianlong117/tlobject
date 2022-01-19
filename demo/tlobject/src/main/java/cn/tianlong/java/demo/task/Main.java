package cn.tianlong.java.demo.task;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

public class Main  {

    public static void main(String[] args)
    {
        String configdir = "/conf/demo/task/";
        String factoryConfigPath =Main.class.getResource(configdir).getPath();
        System.setProperty("log4j.configurationFile", factoryConfigPath+"log4j2.xml");
        TLObjectFactory myfactory = TLObjectFactory.getInstance( factoryConfigPath,"moduleFactory_config.xml");
        myfactory.startFactory(null,null);
        myfactory.boot();
       TLMsg taksMsg =new TLMsg().setAction("getModule").setParam("moduleName","myTaskManger");
        myfactory.putMsg(myfactory,taksMsg);
        myfactory.putMsg("mymsgTaskConsole",new TLMsg().setAction("startScan"));

}
}
