package cn.tianlong.tlobject.servletutils;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.utils.TLMsgUtils;

import static cn.tianlong.tlobject.servletutils.TLParamString.M_DIRECTININTERFACE;

public class TLWWebServiceProxy extends TLBaseModule {
    public TLWWebServiceProxy() {
        super();
    }

    public TLWWebServiceProxy(String name) {
        super(name);
    }

    public TLWWebServiceProxy(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected TLBaseModule init() {
        return this ;
    }

    @Override
    protected Object setConfig() {
       return null;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "fromClient":
                return fromClient(fromWho, msg);
            case "toClient":
                toClient(fromWho, msg);
                break;
            default:

        }
        return returnMsg;
    }

    private TLMsg fromClient(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=  putMsg(M_DIRECTININTERFACE,createMsg().setAction("getContentFromUser"));
         String jstr = (String) returnMsg.getParam("content");
        try {
           TLMsg jMsg =TLMsgUtils.jsonToMsg(jstr);
           return jMsg ;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void toClient(Object fromWho, TLMsg msg) {
        String jstr = TLMsgUtils.msgToJson((TLMsg) msg.getParam("clientMsg"));
       TLMsg cmsg= createMsg().setAction("putContentToUser").setParam("content", jstr);
        putMsg("driectToUserInterface",cmsg);
    }
}

