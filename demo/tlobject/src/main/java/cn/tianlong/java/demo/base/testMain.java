package cn.tianlong.java.demo.base;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLAppStartUp;
import cn.tianlong.tlobject.utils.TLDataUtils;
import cn.tianlong.tlobject.utils.TLMapUtils;
import cn.tianlong.tlobject.utils.TLMsgUtils;

import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.cli.*;

import static java.lang.System.exit;
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
        HashMap<String,Object> argsMap = parseArgs( args);
        TLMsgUtils.printMap(argsMap);
        exit(0);
        if(args !=null && args.length >0){
            TLAppStartUp.main(args);
          return;
        }
        startModule (null );
    }
    public static HashMap<String,Object>  parseArgs(String[] args){
        // create Options object
        Options options = new Options();
        // Create a Parser
        CommandLineParser parser = new BasicParser( );
        options.addOption("d", "configPath", true, "配置文件目录");
        options.addOption("n", "appname ", false, "应用名称" );
        options.addOption("m", "factoryConfigFile", true, "模块工厂配置文件名称");
        options.addOption("f", "app configFile ", false, "应用配置文件" );
        options.addOption("h", "help", true, "帮助");
        // Parse the program arguments

        CommandLine commandLine ;
        try {
             commandLine = parser.parse( options, args );
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
        if( commandLine.hasOption('h') ) {
            System.out.println( "Help Message") ;
            exit(0);
        }
        HashMap<String,Object> argsMap =new HashMap<>() ;
        if(commandLine.hasOption('d'))
            argsMap.put("configPath",commandLine.getOptionValue('d'));
        if(commandLine.hasOption('n'))
            argsMap.put("appName",commandLine.getOptionValue('n'));
        if(commandLine.hasOption('m'))
            argsMap.put("factoryConfigFile",commandLine.getOptionValue('m'));
        if(commandLine.hasOption('f'))
            argsMap.put("configFile",commandLine.getOptionValue('f'));
        return argsMap ;
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
        int i =msg.getIntParam("time",5);
        try {
            sleep(i*1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        println(Thread.currentThread().getName()+" task is over");
        return createMsg().setParam(RESULT,"helo"+i);
    }

    @Override
    protected void run() {
    //    testTask();
       // testTask1();
       testTask2();
      //  testTask3();
    }

    private void testTask2() {
        ArrayList<TLMsg> msgGroup =new ArrayList<>();
        for(int i=6 ; i<12 ;i=i+2){
            TLMsg msg =createMsg().setAction("testtask")
                    .setParam("time",i);
            msg.setSystemParam(INTHREADPOOL ,true);
            msgGroup.add(msg) ;
        }
        TLMsg returnMsg = putMsgGroupByThread(msgGroup,7*1000);
        ArrayList<TLMsg> resultMsg = (ArrayList<TLMsg>) returnMsg.getParam(RESULT);
        for (TLMsg msg: resultMsg ) {
            TLMsgUtils.printMsg(msg);
        }
    }

    private void testTask1() {
        TLMsg returnMsg =invokeActionInThreadAndWait("testtask",this,createMsg().setAction("testTask"));
        println(Thread.currentThread().getName()+" tase result : "+ returnMsg.getStringParam(RESULT,""));
        println(Thread.currentThread().getName()+" main session run over");
    }

    private void testTask() {
        TLMsg msg =createMsg().setAction("testtask")
                .setSystemParam(IFTASKRESULT,true)    //通过 msg 返回 task 结果
                .setWaitFlag(false) ;
             msg .setSystemParam(TASKWAITTIME,10*1000);
             msg.setSystemParam(INTHREADPOOL,true);
        TLMsg returnMsg =putMsg(msg);
        if(!returnMsg.isNull(TASKRESULTTIMEOUT))
        {
            println(" main is time out   ,and have not wait task ");
            return;
        }
        println(Thread.currentThread().getName()+" task result : "+ returnMsg.getStringParam(RESULT,""));
        println(Thread.currentThread().getName()+" main session run over");

    }
    private void testTask3() {
        TLMsg msg =createMsg().setAction("testtask")
                .setSystemParam(IFTASKRESULT,true)    //通过 msg 返回 task 结果
                .setWaitFlag(false) ;
        msg.setSystemParam(INTHREADPOOL,true);
        TLMsg returnMsg =putMsg(name,msg);
        ThreadTask task = (ThreadTask) returnMsg.getParam(THREADPOOL_TASK);
        boolean over  ;
        do {
            try {
                sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            over =task.isThreadOver();
        }  while (!over) ;
       TLMsg  mresult = (TLMsg) msg.getSystemParam(TASKRESULT); //通过 msg 返回 task 结果
        println(Thread.currentThread().getName()+" task result : "+ mresult.getStringParam(RESULT,""));
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
