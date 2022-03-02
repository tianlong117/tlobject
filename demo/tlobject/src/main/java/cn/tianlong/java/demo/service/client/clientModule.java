package cn.tianlong.java.demo.service.client;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.network.client.websocket.TLSocketClientAgentPool;

import java.util.HashMap;

public class clientModule  extends TLSocketClientAgentPool {
    private  int failureNumber =0;
    public clientModule(String name , TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "startRun" :
                startRun(fromWho,msg) ;
                break;
            case "work" :
                work(fromWho,msg) ;
                break;
            case WEBSOCKETCLIENTAGENT_PUTTOSERVICE :
                 fromServer(fromWho,msg) ;
                break;
            default:
                super.checkMsgAction(fromWho,msg);
        }
        return returnMsg;
    }

    private void work(Object fromWho, TLMsg msg) {
        startWork() ;
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
      //      putMsg(this,createMsg().setAction("work").setWaitFlag(false));
         startWork() ;
            return;
        }
        if (status.equals(WEBSOCKET_R_FAILURE))
        {
            failureNumber ++ ;
            println("failure :"+failureNumber);
            return;
        }
    }

    private void startWork() {
        TLMsg msg =createMsg().setParam("data","wakeup1")
                .setParam(MSG_P_MSGID,"fromXiaoMing");
       // putToServerAndWait(this,msg) ;
        putMsgToServer(this,msg) ;
    }

    private void fromServer(Object fromWho, TLMsg msg) {
    }
}
