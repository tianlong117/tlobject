package cn.tianlong.java.demo.service.client;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.network.client.websocket.TLSocketClientAgentPool;
import cn.tianlong.tlobject.utils.TLMsgUtils;

import java.util.HashMap;

import static java.lang.Thread.sleep;

public class clientModule  extends TLSocketClientAgentPool {
    private  int failureNumber =0;
    public clientModule(String name , TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        String action =msg.getAction();
        TLMsg returnMsg = null;
        switch (action) {
            case "startRun" :
            case "fromServer" :
                return invokeAction(action,fromWho,msg);
            default:
                super.checkMsgAction(fromWho,msg);
        }
        return returnMsg;
    }

    private void startRun(Object fromWho, TLMsg msg) {
        defaultServer="server0";
        connectToServer(defaultServer);
    }
    @Override
    protected void fromAgent(Object fromWho, TLMsg msg) {
        super.fromAgent(fromWho,msg);
        String status = (String) msg.getParam(WEBSOCKET_P_STATUS);
        if (status.equals(WEBSOCKET_R_OPEN))
        {
            invokeActionInThread("startWork", this, null);
          //  startWork( fromWho,  msg);
            return;
        }
        if (status.equals(WEBSOCKET_R_FAILURE))
        {
            failureNumber ++ ;
            println("failure :"+failureNumber);
            return;
        }
    }

    private void startWork(Object fromWho, TLMsg msg) {
        for(int i=0 ;i <1 ;i ++)
        {
            TLMsg smsg =createMsg().setParam("data","wakeup1")
                    .setParam(MSG_P_MSGID,"fromXiaoMing");
            TLMsg returnMsg = putToServerAndWait(this,smsg);
            println(i+" 服务返回：");
            TLMsgUtils.printMsg(returnMsg);
        }
    }

    private void fromServer(Object fromWho, TLMsg msg) {
    }
}
