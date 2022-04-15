package cn.tianlong.tlobject.network.server.distributed;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.network.client.websocket.TLSocketClientAgentPool;
import cn.tianlong.tlobject.utils.TLMsgUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.Thread.sleep;

/**
 * 创建日期：2020/2/1214:11
 * 描述:
 * 作者:tianlong
 */
public abstract class TLRouterModule extends TLSocketClientAgentPool {

     protected String clientUserManagerModule ;
     protected String managerServer="managerServer" ;

    public TLRouterModule(String name , TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void initProperty(){
        super.initProperty();
        if(params !=null && params.get("clientUserManagerModule")!=null)
            clientUserManagerModule = params.get("clientUserManagerModule");
        if(params !=null && params.get("managerServer")!=null)
            managerServer = params.get("managerServer");
    }


    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case USERMANAGER_PUTTOUSER:
                returnMsg = toUser(fromWho, msg);
                break;
            case "fromRouteServer":
                returnMsg =fromRouteServer( fromWho,  msg);
                break;
            case "setServersParam":
                returnMsg =  setServersParam( fromWho,  msg);
                break;
            default:
                returnMsg =super.checkMsgAction(fromWho,msg);
        }
        return returnMsg;
    }
    @Override
    protected TLMsg fromAgent(Object fromWho, TLMsg msg) {
        super.fromAgent(fromWho,msg);
        String status = (String) msg.getParam(WEBSOCKET_P_STATUS);
        if (status.equals(WEBSOCKET_R_OPEN))
        {
            String server = (String) msg.getParam(WEBSOCKET_R_CLIENTAGENT);
            if(server.equals(managerServer))
                onManagerServerConnect( fromWho,  msg);
            return null;
        } 
        return null;
    }
    protected void onManagerServerConnect(Object fromWho, TLMsg msg) {

    }

    private synchronized TLMsg setServersParam(Object fromWho, TLMsg msg) {
        String token = (String) msg.getParam("token");
        List<Map<String,Object>> serversParam = (List<Map<String, Object>>) msg.getParam("servers");
        if(serversParam.isEmpty())
            return null;
        HashMap<String,String> manager =servers.get("managerServer");
        String cerFile =manager.get("cerFile");
        for(Map<String,Object> p :serversParam) {
            String server = (String) p.get("server");
            String url =(String) p.get("url");
            if(url ==null || url.isEmpty())
                continue;
            if(servers.containsKey(server))
                continue;
            HashMap<String ,String> sparam =new HashMap<>() ;
            sparam.put("url", url);
            sparam.put("token",token);
            sparam.put("cerFile",cerFile) ;
            sparam.put("autoConnect","false") ;
            servers.put(server,sparam);
            addAndConnectToServer(server,sparam);
        }
        return null ;
    }
    protected TLMsg fromRouteServer(Object fromWho, TLMsg msg) {
        String userid= (String) msg.getParam(USERMANAGER_P_USERID);
        TLMsg routeMsg =createMsg().setAction(USERMANAGER_PUTTOUSER)
                .setParam(WEBSOCKET_P_CONTENT,msg.getParam("data") ).setParam(USERMANAGER_P_USERID,userid);
        TLMsg returnMsg =putMsg(clientUserManagerModule,routeMsg) ;
        return returnMsg ;
    }
    private TLMsg toUser(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getSystemParam(USERMANAGER_P_USERID);
        Object channel =getUserChannel(userid);
        if(channel !=null)
        {
           Boolean result = putToLocalUser(channel, msg) ;
           return createMsg().setParam(RESULT,result).setParam("isUserLocal",true);
        }
        String server = getUserServer(userid);
        if(server ==null )
            return createMsg().setParam(RESULT,false).setParam("isUserLocal",false);
        HashMap<String,Object> routeDate = new HashMap<>();
        routeDate.put(USERMANAGER_P_USERID,userid);
        routeDate.put("data",msg.getArgs());
        HashMap<String,Object> routeDateMap = TLMsgUtils.makeSocketDataMap("fromRouteServer",routeDate);
        TLMsg routeMsg =createMsg().setSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME,server).setArgs(routeDateMap);
        TLMsg resultMsg =putMsgToServer(this,routeMsg);
        return resultMsg.setParam("isUserLocal",false);
    }

    private Object getUserChannel(String userid) {
        if(clientUserManagerModule==null)
            return null ;
        TLMsg getChannelMsg =createMsg().setAction(USERMANAGER_GETUSERCHANNELS)
                .setParam(USERMANAGER_P_USERID,userid);
        TLMsg  userChannelsMsg =putMsg(clientUserManagerModule, getChannelMsg);
        return userChannelsMsg.getParam(USERMANAGER_R_USERCHANNEL) ;
    }

    private Boolean putToLocalUser(Object channel ,TLMsg msg) {

        TLMsg cmsg=createMsg().setAction(USERMANAGER_PUTTOUSER)
                .setSystemParam(USERMANAGER_P_USERCHANNEL,channel)
                .setParam(WEBSOCKET_P_CONTENT, msg.getArgs());
        TLMsg resultMsg= putMsg(clientUserManagerModule,cmsg);
        Boolean result =  resultMsg.parseBoolean(RESULT,false);
        if(result ==false)
            return false ;
        return true ;
    }
    protected abstract String getUserServer(String userid);
}
