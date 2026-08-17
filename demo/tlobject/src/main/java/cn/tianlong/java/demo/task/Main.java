package cn.tianlong.java.demo.task;
import cn.tianlong.tlobject.modules.TLAppStartUp;
import java.util.HashMap;

/**
 * 创建日期：2021/2/1010:29
 * 描述:
 * 作者:tianlong
 */
public class Main extends TLAppStartUp {

    public static void  main (String[] args ) {
        if(args !=null && args.length >0){
            TLAppStartUp.main(args);
            return;
        }
        HashMap<String,String> argsMap =new HashMap<>() ;
        argsMap.put("appName","demo0");
        argsMap.put("configPath",CLASSPATH+"/conf/demo/task/");
        argsMap.put("factoryConfigFile","moduleFactory_config.xml");
        argsMap.put("configFile","");
        TLAppStartUp.main0(argsMap);
        TLAppStartUp.shutdown();
    }

}
