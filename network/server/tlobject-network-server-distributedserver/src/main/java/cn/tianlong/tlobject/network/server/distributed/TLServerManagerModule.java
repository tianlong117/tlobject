package cn.tianlong.tlobject.network.server.distributed;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.network.common.TLJWT;
import cn.tianlong.tlobject.network.server.websocket.TLBaseServiceModule;

import java.util.HashMap;
import java.util.List;
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
            case "onLogin":
                returnMsg=onLogin( fromWho,  msg);
                break;
            case "onLogout":
                onLogout( fromWho,  msg);
                break;
            case "login":
                returnMsg=login( fromWho,  msg);
                break;
            default:
               ;
        }
        return returnMsg;
    }
    private void onLogout(Object fromWho, TLMsg msg) {
        String server = (String) msg.getSystemParam(USERMANAGER_P_USERID);
        TLMsg umsg = createMsg().setAction("updateServerStatus")
                   .setParam("server",server).setParam("status", 0);
        putMsg("serverConfigInDBModle", umsg);
        TLMsg cmsg = createMsg().setAction("deleteByServer")
                .setParam(USERMANAGER_P_SERVERNAME,server);
        putMsg("userLoginModle", cmsg);
    }
    private  void setNotifyServer(String server){
        TLMsg qMsg =createMsg().setAction("getNotifyForServer").setParam("server",server) ;
        TLMsg returnMsg = putMsg("serverConfigInDBModle", qMsg);
        List serversParams = (List) returnMsg.getParam(DB_R_RESULT);
        if(serversParams !=null && !serversParams.isEmpty())
        {
            HashMap<String ,Object> datas =new HashMap<>();
            datas.put("servers",serversParams) ;
            datas.put("token",createToken(server,"", server));
            HashMap<String,Object> sdatas =new HashMap<>();
            sdatas.put("msgid","setServersParam");
            sdatas.put("datas",datas);
            putDaTaToUser(server,sdatas) ;
        }
        TLMsg insertmsg = createMsg().setAction("updateServerStatus")
                .setParam("server",server).setParam("status", 1);
        putMsg("serverConfigInDBModle", insertmsg);
    }
    private String createToken(String userid, String role,String ip) {
        HashMap<String, String> claims = new HashMap<>();
        claims.put("userid", userid);
        claims.put("ip", ip);
        claims.put("role", role);
        return TLJWT.getToken(claims, tokenSecret, tokenExpireMinute, tokenIssure);
    }
    private TLMsg onLogin(Object fromWho, TLMsg msg) {
       if(msg.isNull(USERMANAGER_R_LOGINRESULT))
           return null ;
       String code = (String) msg.getParam(USERMANAGER_R_LOGINRESULT);
       if(!code.equals("0000"))
          return  null  ;
        return login( fromWho, msg);
    }
    private TLMsg login(Object fromWho, TLMsg msg)
    {
        String server = (String)  msg.getSystemParam(USERMANAGER_P_USERID);
        TLMsg qmsg = createMsg().setAction("getServer")
                .setParam("server",server);
        TLMsg returnMsg =putMsg("serverConfigInDBModle", qmsg);
        HashMap<String,Object> serverInfo = (HashMap<String, Object>) returnMsg.getParam(DB_R_RESULT);
        if(serverInfo ==null || serverInfo.isEmpty())
            return null ;
        String serverType = (String) serverInfo.get("serverType");
        serverActionByType(server,serverType) ;
        return  null;
    }

    private void serverActionByType(String server,String serverType) {
         switch (serverType){
             case "notify":
                 setNotifyServer(server);
                 break;
             case "service":
                 serviceServerAction(server) ;
                 break;
         }
    }

    private void serviceServerAction(String server) {
        TLMsg insertmsg = createMsg().setAction("updateServerStatus")
                .setParam("server",server).setParam("status", 1);
        putMsg("serverConfigInDBModle", insertmsg);
    }
}
