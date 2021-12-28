package cn.tianlong.tlobject.network.server.websocket;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.Map;

public  class TLBaseServiceModule extends TLBaseModule {
     protected String userManagerModule ="userManagerModule";

    public TLBaseServiceModule(String main, TLObjectFactory myfactory) {
        super(main, myfactory);
    }


    @Override
    protected void initProperty() {
        super.initProperty();
        if(params!=null  ){
            if( params.get("userManagerModule")!=null)
                userManagerModule =params.get("userManagerModule");
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

    protected String getUser(TLMsg msg) {
        TLMsg userReturnMsg = putMsg(userManagerModule, createMsg().setAction("getUserByChannel").setParam("channel", msg.getParam("channel")));
        return (String) userReturnMsg.getParam("userid");
    }

   protected int putDaTaToUser(String userid ,Map<String, Object> datas) {
        TLMsg umsg = createMsg().setAction("putToUser").setParam(WEBSOCKET_P_CONTENT, datas)
                .setParam(USERMANAGER_P_USERID,userid);
       TLMsg returnMsg = putMsg(userManagerModule, umsg);
       return (int) returnMsg.getParam(RESULT);
    }
}
