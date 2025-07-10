package cn.tianlong.java.demo.base;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLAppStartUp;
import cn.tianlong.tlobject.utils.TLDataUtils;
import cn.tianlong.tlobject.utils.TLMapUtils;
import cn.tianlong.tlobject.utils.TLMsgUtils;

import java.util.ArrayList;
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

        startModule (null );
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
            case "chain1":
                returnMsg=chain1( fromWho,  msg);
                break;
            case "chain2":
                returnMsg=chain2( fromWho,  msg);
                break;
            case "chain3":
                returnMsg=chain3( fromWho,  msg);
                break;
            default:
                returnMsg=super.checkMsgAction(fromWho,msg);
        }
        return returnMsg;
    }

    private TLMsg chain1(Object fromWho, TLMsg msg) {
        println("this chain1 run ");
        String result = " chain1  result" ;
        TLMsg returnMsg =createMsg().setParam(RESULT,result);
        return returnMsg ;
    }

    private TLMsg chain2(Object fromWho, TLMsg msg) {
        println("this chain2 run ");
        String input =(String)msg.getParam("param1",INPUT,"");
        String result = input +" :chain2  result" ;
        println(result);
        TLMsg returnMsg =createMsg().setParam(RESULT,result);
       // returnMsg.setSystemParam(MODULE_DONEXTMSG,false);
        return returnMsg ;
    }

    private TLMsg chain3(Object fromWho, TLMsg msg) {
        println("this chain3 run ");
        String input = (String) msg.getSingleParam(String.class,"input is error");
        String result = input +" :chain3  result" ;
        println(result);
        TLMsg returnMsg =createMsg().setParam(RESULT,result);
        return returnMsg ;
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
      // testTask2();
          testMsgChain();
      //  testTask3();
        moduleFactory.shutdown();
    }

    private void testMsgChain() {
        TLMsg msgChain1 = createMsg().setAction("chain1");
        TLMsg msgChain2 = createMsg().setAction("chain2");
        TLMsg msgChain3 = createMsg().setAction("chain3");
        msgChain1.setNextMsgReturnNextMsg(msgChain2).setNextMsgReturnNextMsg(msgChain3);
        putMsg(name,msgChain1);
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
    private void testMsgchain(){

    }
}
