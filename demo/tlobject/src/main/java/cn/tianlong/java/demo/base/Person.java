package cn.tianlong.java.demo.base;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.utils.TLDateUtils;
import cn.tianlong.tlobject.utils.TLMapUtils;
import cn.tianlong.tlobject.utils.TLMsgUtils;

import java.util.HashMap;

import static java.lang.Thread.sleep;

public class Person extends DemoCommon {
    boolean   sleep = false;
    boolean ifputFile =false ;
    public Person(String name) {
        super(name);
    }
    public Person(String name, TLObjectFactory moduleFactory) {
        super(name, moduleFactory);
    }
    @Override
    protected void initProperty() {
        super.initProperty();
        String mood =TLMapUtils.getStringParam(params,"mood",null);
        if(mood !=null)
            printState(mood);
        ifputFile =TLMapUtils.parseBoolean(params,"ifputFile",false);
    }

    @Override
    protected TLBaseModule init() {
       return this ;
    }

    @Override
    public void runStartMsg()  {
        super.runStartMsg();
      //  runActionWithFixedDelay("cook",5);
    //      putMsgWithFixedDelay(name,createMsg().setAction("cook"),5);
        if(ifputFile)
        {
            TLMsg receivermsg = createMsg().setDestination(name).setAction("onUserLogin");
            putMsg(M_MSGBROADCAST, createMsg().setAction(MSGBROADCAST_REGISTRECEIVER)
                    .setParam(MSGBROADCAST_P_MESSAGETYPE, C_MESSAGETYPE_LOGIN )
                    .setParam(MSGBROADCAST_P_RECEIVEMSG, receivermsg));
        }

    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        printAction(msg);
        TLMsg returnMsg =null ;
        switch (msg.getAction()) {
            case "study":
                study(msg);
                break;
            case "toWife":
                toWife();
                break;
            case "onHouse":
                onHouse(fromWho, msg);
                break;
            case "onLight":
                returnMsg = onLight(fromWho, msg);
                break;
            case "cook":
                returnMsg=cook(fromWho, msg);
                break;
            case "psleep":
                ssleep(fromWho, msg);
                break;
            case "comein":
                comein(fromWho, msg);
                break;
            case "sing":
                returnMsg= sing(fromWho, msg);
                break;
            case "fromXiaoMing":
                returnMsg=fromXiaoMing(fromWho, msg);
                break;
            default:
        }
        return  returnMsg;
    }

    private void study(TLMsg msg) {
        printState(" 应用id:"+applicationId+"  ;"+name+" 学习中。。。");
    }

    private void onHouse(Object fromWho, TLMsg msg) {
        //注册到广播接收者
        printState(" 在屋里。注册接受广播消息,消息类型：light");
        TLMsg receivermsg = createMsg().setDestination(name).setAction("onLight");
        HashMap<String,Object> param = new HashMap<>();
        param.put(MSGBROADCAST_P_MESSAGETYPE, "light");
        param.put(MSGBROADCAST_P_RECEIVEMSG, receivermsg);
        putMsg(MSG_MSGBROADCASTREGIST,param);
    }

    private TLMsg sing(Object fromWho, TLMsg msg) {
        printState(" 开心的唱起了歌....");
        TLMsg wmsg =createMsg().setAction(WEBSOCKET_PUTANDWAIT).setParam("content","来自小明的消息")
                .setParam(MSG_P_MSGID,"fromXiaoMing");
        putMsg("wife",wmsg);
        say(" 给老婆发送个消息");
        return  createMsg().setParam(RESULT,"from client "+name+" sing");
    }

    private TLMsg cook(Object fromWho, TLMsg msg) {
        try {
            sleep(10*1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        println(TLDateUtils.getNowDateStr(null));
        printState(" applicationid:"+applicationId+"  ;"+name+" 做饭..."+ " 进程id: " + Thread.currentThread().getName() );

        return createMsg().setParam("content","return from "+name+" cook");
    }

    private void comein(Object fromWho, TLMsg msg) {
        say("我回家了，开灯啦 ");
        putMsg("light", createMsg().setAction("on"));
    }
    private void ssleep(Object fromWho, TLMsg msg) {
        sleep = true;
        System.out.println(" applicationid:"+applicationId+"  ;"+name + " 在睡觉 ");
    }
    private TLMsg onLight(Object fromWho, TLMsg msg) {
        String housestatus = (String) msg.getParam("status");
        String thread = " (进程id: " + Thread.currentThread().getName() + ")";
        if (housestatus.equals("light"))
        {
            if (sleep == true)
            {
                say( "喊:关灯，我在睡觉呢。  " + thread);
                try {
                    sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                TLMsg cmsg =createMsg().setMsgId("fromServerWife");
                putToClient(cmsg,true,msg.getSystemArgs());
            //    TLMsg returnMsg =createMsg().setArgs(TLMsgUtils.msgToSocketDataMap(cmsg)) ;
           //     return  returnMsg ;
            }
            else {
                say( "屋子亮啦，回家的感觉真好。 上上网吧 " + thread);
                webclient("http://www.baidu.com");
                TLBaseModule module1Fac= moduleFactory.getFactory("module1");
                if(module1Fac!=null)
                {
                    printState("给增加的模块程序发送消息");
                    say( "儿子做饭吧!");
                    TLMsg returnMsg= putMsg("son@module1",createMsg().setAction("cook"));
                    String content =returnMsg.getStringParam("content",null) ;
                    if(content !=null)
                        say("收到儿子返回的消息："+content);
                }
                moduleFactory.shutdown();
            }
        }
        return  null ;
    }

    private void putToClient(TLMsg cmsg, boolean wait, HashMap systemArgs) {
        TLMsg msg =createMsg().setSystemArgs(systemArgs).setArgs(TLMsgUtils.msgToSocketDataMap(cmsg));
        if( !wait)
            msg.setAction(WEBSOCKET_PUTMSG);
        else
            msg.setAction(WEBSOCKET_PUTANDWAIT);
       TLMsg returnMsg =  putMsg("clientMsgHandler",msg);
       TLMsgUtils.printMsg(returnMsg);
    }

    private void webclient(String url) {
      //  TLMsg msg =createMsg().setAction("get") .setParam("url", url);
     //   TLMsg resultMsg =putMsg("httpClient", msg);
        HashMap<String,Object> param = new HashMap<>() ;
        param.put("url", url);
        TLMsg resultMsg =putMsg(MSG_HTTPGET,param);
        if(resultMsg.parseBoolean(HTTP_ERROR,true)==true)
        {
            System.out.println( "网站打不开啊"+ url);
            return;
        }
        say( "看看百度!");
        String response = resultMsg.getStringParam(WEBRESPONSE,"");
        System.out.println(response);
    }
    protected void say(String message){
        System.out.println(name +" 说:"+message);
    }

    private TLMsg fromXiaoMing(Object fromWho, TLMsg msg) {
        System.out.print("收到小明的Msg：");
         TLMsgUtils.printMsg(msg);
         TLMsg clientMsg= createMsg().setMsgId("fromServerWife")
                 .setParam("data"," yes") ;
         return createMsg().setSystemParam(SOCKETSERVER_R_IFRETURN,true)
                 .setArgs(TLMsgUtils.msgToSocketDataMap(clientMsg));
    }

    private void toWife() {
        TLMsg msg =createMsg().setAction(WEBSOCKET_PUTANDWAIT).setParam("data","wakeup1")
                .setParam(MSG_P_MSGID,"fromXiaoMing");
       TLMsg returnMsg= putMsg("socketClientAgentPool",msg);
       System.out.print("1......");
       TLMsgUtils.printMsg(returnMsg);

         for(int i =0 ; i<1 ; i++){
             TLMsg msg1 =createMsg().setAction(WEBSOCKET_PUTANDWAIT).setParam("data","wakeup"+i)
                     .setParam(MSG_P_MSGID,"fromXiaoMing").setWaitFlag(false).setSystemParam(INTHREADPOOL,true);
             TLMsg returnMsg1= putMsg("socketClientAgentPool",msg1);
             System.out.print(i+"......");
             TLMsgUtils.printMsg(returnMsg1);
         }

    }


}
