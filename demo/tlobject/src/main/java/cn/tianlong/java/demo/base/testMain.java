package cn.tianlong.java.demo.base;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLAppStartUp;
import cn.tianlong.tlobject.utils.TLDataUtils;

import java.util.HashMap;

import static java.lang.Thread.sleep;

/**
 * 创建日期：2021/2/1010:29
 * 描述:
 * 作者:tianlong
 */
public class testMain extends TLAppStartUp {
    public static   TLObjectFactory appFactory ;
    public testMain(String name) {
        super( name);
    }
    public static void  main (String[] args ) {
        HashMap<String,String> argsMap = null;
        if(args !=null && args.length >0){
            TLAppStartUp.main(args);
          return;
        }
        startModule (argsMap );
    }

    public static TLObjectFactory   startModule (HashMap<String,String> configMap  ) {
        HashMap<String,Object> argsMap =new HashMap<>() ;
        argsMap.put("appName","demo0");
        argsMap.put("configPath",CLASSPATH+"/conf/demo/base/");
        argsMap.put("factoryConfigFile","moduleFactory_config.xml");
        if(configMap !=null)
            argsMap.putAll(configMap);
        testMain instance = new testMain("startup");
        appFactory=  instance.startup(argsMap);
        return appFactory ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "testtask":
                returnMsg=testtask( fromWho,  msg);
                break;
            default:
                returnMsg=super.checkMsgAction(fromWho,msg);
        }
        return returnMsg;
    }

    private TLMsg testtask(Object fromWho, TLMsg msg) {
        println(Thread.currentThread().getName()+" testtask run ");
        try {
            sleep(10*1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        println(Thread.currentThread().getName()+" task is over");
        return createMsg().setParam(RESULT,"helo");
    }

    @Override
    protected void run() {
        TLMsg msg =createMsg().setAction("testtask")
                .setWaitFlag(false)
             .setSystemParam(TASKWAITTIME,1*1000)
              .setSystemParam(INTHREADPOOL,true);
        TLMsg returnMsg =putMsg(name,msg);
        println(Thread.currentThread().getName()+" tase result : "+ returnMsg.getStringParam(RESULT,""));
        println(Thread.currentThread().getName()+" main session run over");

    }

    private void testMsg() {
        TLMsg msg =createMsg();
        Double i =100D;
        msg.setParam("p1",i) ;
        int  p1 =msg.getIntParam("p1",  0);
       int  p2 =TLDataUtils.parseInt(msg.getParam("p1"),  0);
       println("helo");
    }
}
