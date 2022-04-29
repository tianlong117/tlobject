package cn.tianlong.tlobject.network.server.distributed;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.network.client.websocket.TLSocketClientAgentPool;
import cn.tianlong.tlobject.utils.TLMapUtils;
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

    protected   boolean   isServer  =true;
     protected String clientMsgHandler ;
     protected String managerServer="managerServer" ;
     protected HashMap<String,String> serverStatus = new HashMap<>();

    public TLRouterModule(String name , TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void initProperty(){
        super.initProperty();
        if(params !=null && params.get("clientMsgHandler")!=null)
            clientMsgHandler = params.get("clientMsgHandler");
        if(params !=null && params.get("managerServer")!=null)
            managerServer = params.get("managerServer");
        isServer = TLMapUtils.parseBoolean(params,"isServer",true);
    }


    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case WEBSOCKET_PUT:
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
        String server = (String) msg.getParam(WEBSOCKET_R_CLIENTAGENT);
        if(server.equals(managerServer) )
        {
            if(status.equals(WEBSOCKET_R_OPEN))
                onManagerServerConnect();
        }
        else{
            if(!serverStatus.containsKey(server)){
                serverStatus.put(server,status);
                onServerConnect( server,status);
            }
            else {
                String nowStatus =serverStatus.get(server);
                if(!status.equals(nowStatus))
                {
                    serverStatus.put(server,status);
                    onServerConnect( server,status);
                }
            }
        }

        if (!server.equals(managerServer) && status.equals(WEBSOCKET_R_FAILURE))
        {
            Boolean authStatus =  msg.getBooleanParam(WEBSOCKET_R_AUTHSTATUS,true);
            if(authStatus ==false)
                getServerParam( server);
            return null;
        }
        return null;
    }

    private void onServerConnect(String server, String status) {
        TLMsg sMsg =createMsg().setMsgId("setConnectedServer")
                .setParam("server",server)
                .setParam(WEBSOCKET_P_STATUS,status);
        TLMsg routeMsg =createMsg().setSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME,managerServer)
                .setArgs(TLMsgUtils.msgToSocketDataMap(sMsg));
        putMsgToServer(this, routeMsg);
    }

    protected void getServerParam(String server) {
        TLMsg sMsg =createMsg().setMsgId("getServerParam").setParam("server",server);
        TLMsg routeMsg =createMsg().setSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME,managerServer).setArgs(TLMsgUtils.msgToSocketDataMap(sMsg));
        putMsgToServer(this, routeMsg);
    }

    protected void onManagerServerConnect() {

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
            if(sucessServers.containsKey(server))
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
        TLMsg routeMsg =createMsg().setAction(WEBSOCKET_PUT)
                .setParam(WEBSOCKET_P_CONTENT,msg.getParam("data") ).setParam(USERMANAGER_P_USERID,userid);
        TLMsg returnMsg =putMsg(clientMsgHandler,routeMsg) ;
        return returnMsg ;
    }
    private TLMsg toUser(Object fromWho, TLMsg msg) {
        if(!isServer)
            return super.putToServer(fromWho,msg);
        Object channel =msg.getSystemParam(USERMANAGER_P_USERCHANNEL);
        if(channel ==null){
            String userid = (String) msg.getSystemParam(USERMANAGER_P_USERID);
            channel =getUserChannel(userid);
        }
        if(channel !=null)
        {
           TLMsg returnMsg = putToLocalUser(channel, msg) ;
           return returnMsg.setParam("isUserLocal",true);
        }
        String userid = (String) msg.getSystemParam(USERMANAGER_P_USERID);
        String server = getUserServer(userid);
        if(server ==null )
            return createMsg().setParam(RESULT,0).setParam("isUserLocal",false);
        HashMap<String,Object> routeDate = new HashMap<>();
        routeDate.put(USERMANAGER_P_USERID,userid);
        routeDate.put("data",msg.getParam("content"));
        HashMap<String,Object> routeDateMap = TLMsgUtils.makeSocketDataMap("fromRouteServer",routeDate);
        TLMsg routeMsg =createMsg().setSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME,server).setArgs(routeDateMap);
        TLMsg resultMsg =putMsgToServer(this,routeMsg);
        int result =resultMsg.getBooleanParam(RESULT,false) ? 1:0 ;
        return resultMsg.setParam("isUserLocal",false).setParam(RESULT,result);
    }

    private Object getUserChannel(String userid) {
        if(clientMsgHandler==null)
            return null ;
        TLMsg getChannelMsg =createMsg().setAction(USERMANAGER_GETUSERCHANNELS)
                .setParam(USERMANAGER_P_USERID,userid);
        TLMsg  userChannelsMsg =putMsg(clientMsgHandler, getChannelMsg);
        return userChannelsMsg.getParam(USERMANAGER_R_USERCHANNEL) ;
    }

    private TLMsg putToLocalUser(Object channel ,TLMsg msg) {
        return putMsg(clientMsgHandler,msg.setSystemParam(USERMANAGER_P_USERCHANNEL,channel));
    }
    protected abstract String getUserServer(String userid);
}
