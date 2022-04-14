package cn.tianlong.tlobject.network.server.distributed;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.network.common.TLJWT;
import cn.tianlong.tlobject.network.server.websocket.TLBaseServiceModule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLServerManagerModule extends TLBaseServiceModule {
    protected String tokenSecret = "sdfsdfsdfsdfsdfwervdgert";
    protected String tokenIssure = "qinqin";
    protected int tokenExpireMinute = 30;
    protected String msgBroadCast ;
    protected ConcurrentLinkedQueue serverPool = new ConcurrentLinkedQueue();
    public TLServerManagerModule(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        if (params != null) {
            if (params.get("tokenSecret") != null)
                tokenSecret = params.get("tokenSecret");
            if (params.get("tokenIssure") != null)
                tokenIssure = params.get("tokenIssure");
            if (params.get("tokenExpireMinute") != null)
                tokenExpireMinute = Integer.parseInt(params.get("tokenExpireMinute"));
            if (params.get("msgBroadCast") != null)
                msgBroadCast = params.get("msgBroadCast");
        }
    }
    @Override
    protected TLBaseModule init() {
        TLMsg onUserLogInMsg = createMsg().setDestination(name).setAction("onLogin");
        putMsg(msgBroadCast, createMsg().setAction(MSGBROADCAST_REGISTRECEIVER)
                .setParam(MSGBROADCAST_P_MESSAGETYPE, C_MESSAGETYPE_LOGIN).setParam(MSGBROADCAST_P_RECEIVEMSG, onUserLogInMsg));
        TLMsg onUserLogoutMsg = createMsg().setDestination(name).setAction("onLogout");
        putMsg(msgBroadCast, createMsg().setAction(MSGBROADCAST_REGISTRECEIVER)
                .setParam(MSGBROADCAST_P_MESSAGETYPE, C_MESSAGETYPE_LOGOUT).setParam(MSGBROADCAST_P_RECEIVEMSG, onUserLogoutMsg));

        return this ;
    }
    @Override
    public void runStartMsg()  {
        super.runStartMsg();
        runActionWithFixedDelay("serverlogin",5);
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "onLogin":
                returnMsg=onLogin( fromWho,  msg);
                break;
            case "onLogout":
                onLogout( fromWho,  msg);
                break;
            default:
               ;
        }
        return returnMsg;
    }
    private void onLogout(Object fromWho, TLMsg msg) {
        String server = (String) msg.getParam(USERMANAGER_P_USERID);
        TLMsg umsg = createMsg().setAction("updateServerStatus")
                   .setParam("server",server).setParam("status", 0);
        putMsg("serverConfigInDBModle", umsg);
        TLMsg cmsg = createMsg().setAction("deleteByServer")
                .setParam(USERMANAGER_P_SERVERNAME,server);
        putMsg("userLoginModle", cmsg);
    }

    private TLMsg onLogin(Object fromWho, TLMsg msg)
    {
        String server = (String) msg.getParam(USERMANAGER_P_USERID);
        serverPool.add(server);
        return  null;
    }
    private void serverlogin(Object fromWho, TLMsg msg) {
        String loginServer = (String) serverPool.poll();
        if(loginServer ==null)
           return;
        TLMsg qmsg = createMsg().setAction("getServer")
                .setParam("server",loginServer);
        TLMsg returnMsg =putMsg("serverConfigInDBModle", qmsg);
        HashMap<String,Object> serverInfo = (HashMap<String, Object>) returnMsg.getParam(DB_R_RESULT);
        if(serverInfo ==null || serverInfo.isEmpty())
            return  ;
        String serverType = (String) serverInfo.get("serverType");
        switch (serverType){
            case "notify":
                notifyServerAction(serverInfo) ;
                break;
            case "service":
                serviceServerAction(loginServer) ;
                break;
        }

    }
    private void notifyServerAction( HashMap<String,Object> serverInfo) {
        String loginServer = (String) serverInfo.get("server");
        String url = (String) serverInfo.get("ip_server");
        putLog("set server: "+loginServer,LogLevel.DEBUG);
        TLMsg qMsg =createMsg().setAction("getNotifyForServer").setParam("server",loginServer) ;
        TLMsg returnMsg = putMsg("serverConfigInDBModle", qMsg);
        List<Map<String,Object>> serversParams = returnMsg.getListParam(DB_R_RESULT,null);
        if(serversParams !=null && !serversParams.isEmpty())
        {
            putServerParamToServer(loginServer,serversParams);
            if(url !=null && !url.isEmpty())
            {
                ArrayList<Map<String,Object>> loginServerData = new ArrayList<>();
                loginServerData.add(serverInfo)  ;
                for(Map<String,Object> serverDaTa : serversParams){
                    String server = (String) serverDaTa.get("server");
                    putServerParamToServer(server,loginServerData);
                }
            }
        }
        TLMsg insertmsg = createMsg().setAction("updateServerStatus")
                .setParam("server",loginServer).setParam("status", 1);
        putMsg("serverConfigInDBModle", insertmsg);
    }
    private void putServerParamToServer(String server, List<Map<String,Object>>serversParams) {
        HashMap<String ,Object> datas =new HashMap<>();
        datas.put("servers",serversParams) ;
        datas.put("token",createToken(server,"", server));
        putDaTaToUser(server,"setServersParam",datas) ;
    }

    private String createToken(String userid, String role,String ip) {
        HashMap<String, String> claims = new HashMap<>();
        claims.put("userid", userid);
        claims.put("ip", ip);
        claims.put("role", role);
        return TLJWT.getToken(claims, tokenSecret, tokenExpireMinute, tokenIssure);
    }
    private void serviceServerAction(String server) {
        TLMsg insertmsg = createMsg().setAction("updateServerStatus")
                .setParam("server",server).setParam("status", 1);
        putMsg("serverConfigInDBModle", insertmsg);
    }
}
