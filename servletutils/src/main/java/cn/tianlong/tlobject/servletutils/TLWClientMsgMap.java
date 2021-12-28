package cn.tianlong.tlobject.servletutils;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLModuleConfig;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.HashMap;

public class TLWClientMsgMap extends TLWServModule {
    protected HashMap<String, ArrayList<TLMsg>> msgMapTable;

    public TLWClientMsgMap(){
        super();
    }
    public TLWClientMsgMap(String name ){
        super(name);
    }
    public TLWClientMsgMap(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected TLBaseModule init() {
        return this ;
    }
    @Override
    protected Object setConfig(){
        myConfig config=new myConfig(configFile,moduleFactory.getConfigDir());;
        mconfig=config;
        super.setConfig();
        msgMapTable=config.getMsgMapTalbe();
        return config;
    }


    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case"service":
                msgMap( fromWho, msg);
                break;
            default:
                msgMap( fromWho, msg);
        }
        return returnMsg ;
    }

    protected void msgMap(Object fromWho, TLMsg msg) {
        TLMsg cmsg=getUserMsg();
        if(cmsg==null){
             error("1000","内容为空");
            return;
        }
        String msgId =cmsg.getMsgId();
        if(msgId==null || msgId.isEmpty())
        {
            error("1001","没有msgid");
            return;
        }
        ArrayList msgList = msgMapTable.get(msgId);
        if (msgList == null || msgList.isEmpty())
        {
            error("1002","没有服务msg");
            return;
        }
        doMsgList(msgList, cmsg,null);
    }
    protected void error(String number,String content){
        TLMsg emsg=createMsg();
        emsg.setParam("number",number);
        emsg.setParam("content",content);
        putUserMsg(emsg);
    }
    private class myConfig extends TLModuleConfig {
        protected HashMap<String, ArrayList<TLMsg>> msgMapTable;
        public myConfig(String configFile ,String configDir) {
            super(configFile,configDir);
        }

        public HashMap getMsgMapTalbe() {
            return msgMapTable;
        }

        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {

                if (xpp.getName().equals("msg-mapping")) {
                    msgMapTable= getMsgTable(xpp,"msg-mapping","msgid");
                }

            } catch (Throwable t) {

            }
        }
    }
}
