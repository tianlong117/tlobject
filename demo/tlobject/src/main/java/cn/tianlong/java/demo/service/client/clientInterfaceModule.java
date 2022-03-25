package cn.tianlong.java.demo.service.client;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.network.client.websocket.TLSocketClientAgentPool;
import cn.tianlong.tlobject.utils.TLMsgUtils;

import static java.lang.Thread.sleep;

public class clientInterfaceModule extends TLSocketClientAgentPool {
    private  int failureNumber =0;
    public clientInterfaceModule(String name , TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        String action =msg.getAction();
        TLMsg returnMsg = null;
        switch (action) {
            case "startRun" :
                startRun( fromWho,  msg);
            default:
                returnMsg =super.checkMsgAction(fromWho,msg);
        }
        return returnMsg;
    }

    private void startRun(Object fromWho, TLMsg msg) {
        defaultServer="server0";
        connectToServer(defaultServer);
    }
    @Override
    protected TLMsg fromAgent(Object fromWho, TLMsg msg) {
        super.fromAgent(fromWho,msg);
        String status = (String) msg.getParam(WEBSOCKET_P_STATUS);
        if (status.equals(WEBSOCKET_R_OPEN))
        {
        //    invokeActionInThread("startWork", this, null);
          startWork( fromWho,  msg);
            return msg;
        }
        if (status.equals(WEBSOCKET_R_FAILURE))
        {
            failureNumber ++ ;
            println("failure :"+failureNumber);
            return msg;
        }
        return msg;
    }

    private void startWork(Object fromWho, TLMsg msg) {
        putMsg("clientModule",createMsg().setMsgId("start"));
    }
    private void startWork_old(Object fromWho, TLMsg msg) {
        for(int i=0 ;i <1 ;i ++)
        {
            TLMsg smsg =createMsg().setMsgId("fromXiaoMing").setParam("data","wakeup1")
                        .setSystemParam(WEBSOCKET_P_SESSIONISARRIVED,true)
                       .setSystemParam(WEBSOCKET_P_SESSIONARRIVEDMSGID,"fromServerWife");   // 服务器收到后理解返回通知
            TLMsg pmsg =createMsg().setArgs(TLMsgUtils.msgToSocketDataMap(smsg));
            TLMsg returnMsg = putToServerAndWait(this,pmsg);
            println(i+" 服务返回：");
            TLMsgUtils.printMsg(returnMsg);
        }
    }

}
