package cn.tianlong.tlobject.network.server.distributed;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.dbdata.BeanTable;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.network.common.TLJWT;
import cn.tianlong.tlobject.network.server.websocket.TLBaseServiceModule;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLServerManagerModule extends TLBaseServiceModule {
    /** 代码内置的默认密钥，仅作兜底；生产部署必须在配置里用 tokenSecret 覆盖（见 initProperty 的告警） */
    protected static final String DEFAULT_TOKEN_SECRET = "sdfsdfsdfsdfsdfwervdgert";
    protected String tokenSecret = DEFAULT_TOKEN_SECRET;
    protected String tokenIssure = "qinqin";
    protected int tokenExpireMinute = 30;
    protected String msgBroadCast ;
    protected ConcurrentLinkedQueue serverPool = new ConcurrentLinkedQueue();
    protected BeanTable  server_connectedTable ;
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
            // 默认密钥写死在代码里，看过源码即可伪造任意 userid 的 token
            if (DEFAULT_TOKEN_SECRET.equals(tokenSecret))
                putLog("tokenSecret 仍在使用代码内置的默认值，任何拿到源码的人都能伪造 token，请务必在配置里显式设置",
                        LogLevel.ERROR, "initProperty");
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
        server_connectedTable =new BeanTable("server_connected",moduleFactory);
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
            case "getServerParam":
                returnMsg=getServerParam( fromWho,  msg);
                break;
            case "setConnectedServer":
               setConnectedServer( fromWho,  msg);
                break;
            default:
               ;
        }
        return returnMsg;
    }

    private void setConnectedServer(Object fromWho, TLMsg msg) {
        String loginServer = getUserid(msg);
        String server = (String) msg.getParam("server");
        String status = (String) msg.getParam(WEBSOCKET_P_STATUS);
        Long statustime = System.currentTimeMillis();
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("server",loginServer);
        data.put("connectedserver",server);
        data.put("status",status);
        data.put("statustime",statustime);
        server_connectedTable.replace(data);
    }

    protected TLMsg getServerParam(Object fromWho, TLMsg msg) {
        String server = (String) msg.getParam("server");
        String loginServer = getUserid(msg);
        List<Map<String,Object>> serverData;
        if(server != null)
            serverData =getServerParamForConnect(server) ;
        else {
            String serverType =msg.getStringParam("serverType","socket");
            serverData =getServersParam(loginServer ,serverType) ;
        }
        if(serverData !=null && !serverData.isEmpty())
           putServerParamToServer(loginServer,serverData);
        return null;
    }

    private List<Map<String,Object>> getServersParam(String loginServer, String serverType) {
        TLMsg qMsg =createMsg().setAction("getOnLineServer").setParam("server",loginServer)
                .setParam("serverType",serverType) ;
        TLMsg returnMsg = putMsg("serverConfigInDB", qMsg);
         return returnMsg.getListParam(DB_R_RESULT,null);
    }
    private Map<String,Object> getServerParam(String server) {
        TLMsg qmsg = createMsg().setAction("getServer")
                .setParam("server",server);
        TLMsg returnMsg =putMsg("serverConfigInDB", qmsg);
        // 不能强转 HashMap：dbutils 的 MapHandler 产出的是 CaseInsensitiveHashMap（extends LinkedHashMap），
        // 强转必 ClassCastException；按 Map 接口用即可
        Map<String,Object> serverInfo = returnMsg.getMapParam(DB_R_RESULT,null);
        return  serverInfo ;
    }
    private List<Map<String,Object>> getServerParamForConnect(String server) {
        Map<String,Object> serverInfo = getServerParam( server);
        if(serverInfo ==null || serverInfo.isEmpty())
            return null ;
        return  getServerParamForConnect(serverInfo) ;
    }
    private List<Map<String,Object>> getServerParamForConnect( Map<String,Object> serverInfo) {
        serverInfo.put("url",serverInfo.get("routerserver"));
        List<Map<String,Object>> serverData = new ArrayList<>();
        serverData.add(serverInfo)  ;
        return  serverData ;
    }
    private void onLogout(Object fromWho, TLMsg msg) {
        String server = (String) msg.getParam(USERMANAGER_P_USERID);
        TLMsg umsg = createMsg().setAction("updateServerStatus")
                   .setParam("server",server).setParam("status", 0);
        putMsg("serverConfigInDB", umsg);
        TLMsg cmsg = createMsg().setAction("deleteByServer")
                .setParam(USERMANAGER_P_SERVERNAME,server);
        putMsg("userLoginModle", cmsg);
        String sql ="update [table] set status='failure' where server=?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>() ;
        sqlparams.put("server",server);
        server_connectedTable.updateBySql(sql ,sqlparams);
    }

    private TLMsg onLogin(Object fromWho, TLMsg msg)
    {
        String server = (String) msg.getParam(USERMANAGER_P_USERID);
        // 队列允许重复：服务器频繁重连会堆积同名条目，定时任务会反复处理同一个 server。
        // 已在队列里就不再入队（消费端 poll 后即可重新入队）。
        if(server !=null && !serverPool.contains(server))
            serverPool.add(server);
        return  null;
    }
    private void serverlogin(Object fromWho, TLMsg msg) {
        String loginServer = (String) serverPool.poll();
        if(loginServer ==null)
           return;
        Map<String,Object> serverInfo =getServerParam(loginServer);
        if(serverInfo ==null || serverInfo.isEmpty())
            return  ;
        String serverType = (String) serverInfo.get("serverType");
        switch (serverType){
            case  "web":
            case "socket":
                notifyServerAction(serverInfo,serverType) ;
                break;
            case "service":
                serviceServerAction(loginServer) ;
                break;
        }

    }
    private void notifyServerAction(Map<String, Object> serverInfo, String serverType) {
        String loginServer = (String) serverInfo.get("server");
        putLog("set server: "+loginServer,LogLevel.DEBUG);
        List<Map<String,Object>> serversParams = getServersParam(loginServer ,serverType) ;
        if(serversParams !=null && !serversParams.isEmpty())
        {
            putServerParamToServer(loginServer,serversParams);
            if(serverType.equals("socket"))
            {
                List<Map<String,Object>> loginServerData = getServerParamForConnect(serverInfo);
                for(Map<String,Object> serverDaTa : serversParams)
                {
                    String server = (String) serverDaTa.get("server");
                    putServerParamToServer(server,loginServerData);
                }
            }
        }
        TLMsg insertmsg = createMsg().setAction("updateServerStatus")
                .setParam("server",loginServer).setParam("status", 1);
        putMsg("serverConfigInDB", insertmsg);
    }
    private void putServerParamToServer(String server, List<Map<String,Object>>serversParams) {
        putLog("put server params,server:"+server,LogLevel.DEBUG);
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
        putMsg("serverConfigInDB", insertmsg);
    }
}
