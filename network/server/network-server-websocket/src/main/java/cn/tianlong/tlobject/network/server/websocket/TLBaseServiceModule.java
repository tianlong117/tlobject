package cn.tianlong.tlobject.network.server.websocket;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.utils.TLMsgUtils;

import java.util.HashMap;
import java.util.Map;

public  class TLBaseServiceModule extends TLBaseModule {
     protected String clientMsgHandler ="clientMsgHandler";

    public TLBaseServiceModule(String main, TLObjectFactory myfactory) {
        super(main, myfactory);
    }


    @Override
    protected void initProperty() {
        super.initProperty();
        if(params!=null  ){
            if( params.get("clientMsgHandler")!=null)
                clientMsgHandler =params.get("clientMsgHandler");
        }
    }
    @Override
    protected TLBaseModule init() {
        return this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        return null;
    }

    protected String getUserIdByChannel(String channel) {
        TLMsg userReturnMsg = putMsg(clientMsgHandler, createMsg().setAction(USERMANAGER_GETUSERBYCHANNEL).setParam("channel", channel));
        return (String) userReturnMsg.getParam(USERMANAGER_P_USERID);
    }
   protected int putDaTaToUser(String userid ,String msgid ,Map<String, Object> datas) {
       HashMap<String,Object> socketData=TLMsgUtils.makeSocketDataMap(msgid,datas);
        TLMsg umsg = createMsg().setAction(WEBSOCKET_PUT).setParam(WEBSOCKET_P_CONTENT, socketData)
                .setSystemParam(USERMANAGER_P_USERID,userid);
       TLMsg returnMsg = putMsg(clientMsgHandler, umsg);
       return (int) returnMsg.getParam(RESULT);
    }
    protected int putSocketDaTaToUser(String userid ,Map<String, Object>  socketData) {
      TLMsg umsg = createMsg().setAction(WEBSOCKET_PUT).setParam(WEBSOCKET_P_CONTENT, socketData)
              .setSystemParam(USERMANAGER_P_USERID,userid);
        TLMsg returnMsg = putMsg(clientMsgHandler, umsg);
        return (int) returnMsg.getParam(RESULT);
    }
    protected int putDaTaByChannel(String channel ,String msgid, Map<String, Object> datas) {
        HashMap<String,Object> socketData=TLMsgUtils.makeSocketDataMap(msgid,datas);
        TLMsg umsg = createMsg().setAction(WEBSOCKET_PUT).setParam(WEBSOCKET_P_CONTENT, socketData)
                .setSystemParam(USERMANAGER_P_USERCHANNEL, channel);
        TLMsg returnMsg = putMsg(clientMsgHandler, umsg);
        return (int) returnMsg.getParam(RESULT);
    }
    protected int putSocketDaTaByChannel(String channel , Map<String, Object> socketData) {
       TLMsg umsg = createMsg().setAction(WEBSOCKET_PUT).setParam(WEBSOCKET_P_CONTENT, socketData)
                .setSystemParam(USERMANAGER_P_USERCHANNEL, channel);
        TLMsg returnMsg = putMsg(clientMsgHandler, umsg);
        return (int) returnMsg.getParam(RESULT);
    }
    protected String getUserid(TLMsg clientMsg) {
        Map<String,Object> taskDatas = (Map<String, Object>) clientMsg.getSystemParam(TASKRESESSIONDATA);
        return  (String) taskDatas.get(USERMANAGER_P_USERID);
    }
    protected String getChannel(TLMsg clientMsg) {
        Map<String,Object> taskDatas = (Map<String, Object>) clientMsg.getSystemParam(TASKRESESSIONDATA);
        return (String) taskDatas.get(USERMANAGER_P_USERCHANNEL);
    }
}
