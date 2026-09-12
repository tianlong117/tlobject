package cn.tianlong.tlobject.network.client.websocket;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;
import org.xmlpull.v1.XmlPullParser;

import java.util.*;


/**
 * 创建日期：2020/2/1214:11
 * 描述:
 * 作者:tianlong
 */
public class TLServiceClientInterface extends TLSocketClientAgentPool {
    protected HashMap<String,  String> msgidToServiceMap;
    protected HashMap<String,List<String>> msgToService =new HashMap<>() ;

    public TLServiceClientInterface(String name , TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        if(msgidToServiceMap != null && !msgidToServiceMap.isEmpty()){
            for(String msgid : msgidToServiceMap.keySet()){
                String services =msgidToServiceMap.get(msgid);
                List<String> serviceList =TLDataUtils.splitStrToList(services,";");
                msgToService.put(msgid,serviceList) ;
            }
        }
    }
    @Override
    protected Object setConfig() {
        if( mconfig ==null)
            mconfig =  new mySelfConfig();
        super.setConfig();
        msgidToServiceMap =((mySelfConfig)mconfig).getMsgidToServiceMap();
        return mconfig;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case WEBSOCKETCLIENTAGENT_PUTTOSERVICE :
                returnMsg = putToService(fromWho,msg) ;
                break;
            default:
                super.checkMsgAction(fromWho,msg);
        }
        return returnMsg;
    }

    private TLMsg putToService(Object fromWho, TLMsg msg) {
        String msgid = (String) msg.getParam(MSG_P_MSGID);
        if(msgid ==null)
            return null;
         List<String> services = msgToService.get(msgid);
        if (services == null &&  defaultServer !=null)
        {
            msg.setSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME,defaultServer);
            return toService(fromWho,msg) ;
        }
        // msgid 不在映射表且未配 defaultServer 时 services 仍为 null，
        // 原代码直接 services.size() 会 NPE
        if (services == null)
            return createMsg().setParam(RESULT, false);
        int serviceSize =services.size();
        if( serviceSize==1 )
        {
            msg.setSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME,services.get(0));
            return toService(fromWho,msg) ;
        }
        ArrayList<TLMsg> returnList = new ArrayList<>();
        for (String server : services) {
            TLMsg smsg =createMsg().copyFrom(msg);
            smsg.setSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME,server);
            TLMsg returnMsg = toService(fromWho,smsg);
            returnList.add(returnMsg);
        }
        return createMsg().setParam(RESULT,returnList) ;
    }

    private TLMsg toService(Object fromWho, TLMsg msg) {
        if((Boolean)msg.getSystemParam(WEBSOCKETCLIENTAGENT_ISWAIT,true) ==true)
          return   putToServerAndWait(fromWho,msg);
        else
          return   putMsgToServer(fromWho,msg);
    }

    protected class mySelfConfig extends TLSocketClientAgentPool.myConfig {
        protected HashMap<String, String> msgidToServiceMap;

        public mySelfConfig() {

        }
        public HashMap getMsgidToServiceMap() {
            return msgidToServiceMap;
        }
        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if (xpp.getName().equals("msgidToService")) {
                    HashMap cparams=getParam(xpp,"msgidToService");
                    if(cparams !=null)
                    {
                        if(msgidToServiceMap ==null)
                            msgidToServiceMap =new HashMap<>();
                        msgidToServiceMap.putAll(cparams);
                    }
                }
            } catch (Throwable t) {
              putLog("configfile is wrong",LogLevel.ERROR);
            }
        }

    }
}
