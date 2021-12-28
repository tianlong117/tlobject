package cn.tianlong.tlobject.network.server.websocket;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.utils.TLMsgUtils;
import com.google.gson.internal.LinkedTreeMap;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;


/**
 * 创建日期：2020/2/1214:11
 * 描述:
 * 作者:tianlong
 */
public class TLServiceGateWayClientMsgHandler extends TLClientMsgHandler {

    protected String userManagerModule ;
    protected String serverUserManagerModule;
    protected String insideSocketServer;
    protected Map<String,CopyOnWriteArrayList<String>> msgidToChannel =new ConcurrentHashMap<>();
    protected Type type = new TypeToken<Map<String, Object>>() {  }.getType();

    public TLServiceGateWayClientMsgHandler(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }


    @Override
    protected void initProperty() {
        super.initProperty();
        if (params != null && params.get("serverUserManagerModule") != null)
            serverUserManagerModule = params.get("serverUserManagerModule");
        if(params!=null  ){
            if( params.get("userManagerModule")!=null)
                userManagerModule =params.get("userManagerModule");
        }
        if(params!=null  ){
            if( params.get("insideSocketServer")!=null)
                insideSocketServer =params.get("insideSocketServer");
        }
    }

    @Override
    protected TLBaseModule init() {
        return this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "setServiceMsgid":
                setServiceMsgid(fromWho,msg);
                break;
            case "fromService":
                 fromService(fromWho,msg);
                break;
            case "onLogout":
                onLogout( fromWho,  msg);
                break;
            default:
                super.checkMsgAction(fromWho, msg);
        }
        return returnMsg;
    }
    private void setServiceMsgid(Object fromWho, TLMsg msg) {
        String channel = (String) msg.getParam(USERMANAGER_P_USERCHANNEL);
        ArrayList<String> msgids = (ArrayList<String>) msg.getParam("msgids");
        for(String msgid : msgids){
            if(msgidToChannel.containsKey(msgid))
            {
                CopyOnWriteArrayList<String> channels = msgidToChannel.get(msgid);
                channels.add(channel);
            }
            else{
                CopyOnWriteArrayList<String> channels=new CopyOnWriteArrayList<>();
                channels.add(channel);
                msgidToChannel.put(msgid,channels);
            }
        }
    }

    private void onLogout(Object fromWho, TLMsg msg) {
        String serverChannel = (String) msg.getParam(USERMANAGER_P_USERCHANNEL);
        for(String key : msgidToChannel.keySet())
        {
            CopyOnWriteArrayList<String> channels =msgidToChannel.get(key);
            if(channels.contains(serverChannel))
            {
                channels.remove(serverChannel) ;
            }
        }
    }

    private void fromService(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam("userid");
        Map<String,Object> servicedatas = (Map<String, Object>) msg.getParam("servicedatas");
        TLMsg umsg = createMsg().setAction("putToUser").setParam(WEBSOCKET_P_CONTENT, servicedatas)
                .setParam(USERMANAGER_P_USERID,userid);
         putMsg(userManagerModule, umsg);
    }
    @Override
    protected TLMsg fromClient(Object fromWho, TLMsg msg) {
        String content = (String) msg.getParam(WEBSOCKET_P_CONTENT);
        LinkedTreeMap<String, Object> contentmap =TLMsgUtils.jsonStrToMap(content);
        if (contentmap == null)
            return null;
        String msgid = (String) contentmap.get("msgid");
        if (msgid == null || msgid.isEmpty())
            return null;
        String channel = getChannelByMsgid(msgid);
        if(channel ==null)
            return null;
        String userchannel = (String) msg.getParam(USERMANAGER_P_USERCHANNEL);
        String userid =getUser(userchannel,userManagerModule);
        HashMap<String,Object> userInfo =new HashMap<>();
        userInfo.put(USERMANAGER_P_USERID,userid) ;
        userInfo.put(USERMANAGER_P_USERIP,msg.getParam(USERMANAGER_P_USERIP)) ;
        userInfo.put(USERMANAGER_P_USERCHANNEL,userchannel) ;
        contentmap.put(SERVICEGATEMAY_P_USERINFO,userInfo);
        TLMsg routeMsg = createMsg().setAction(SOCKETSERVER_PUTTOCLIENT )
                .setParam(MSG_CONTENT, contentmap)
                .setParam(NETTY_CHANNEL, channel);
         putMsg(insideSocketServer, routeMsg);
        return null;
    }
    protected String getUser(String channel,String userManagerModule) {
        TLMsg userReturnMsg = putMsg(userManagerModule, createMsg().setAction("getUserByChannel").setParam("channel", channel));
        return (String) userReturnMsg.getParam("userid");
    }
    private String getChannelByMsgid(String msgid) {

        CopyOnWriteArrayList<String> channels = msgidToChannel.get(msgid);
        if(channels ==null)
            return null ;
        int size =channels.size();
        if(size <= 0)
            return null ;
        if(size ==1)
            return channels.get(0);
        Random random = new Random();
        int index = random.nextInt(size);
        return channels.get(index);
    }
}
