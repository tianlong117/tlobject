package cn.tianlong.tlobject.network.server.websocket;

import cn.tianlong.tlobject.base.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


/**
 * 创建日期：2020/2/1214:11
 * 描述:
 * 作者:tianlong
 */
public class TLUserRouterModule extends TLBaseModule {

    protected String serverUserManagerModule;
    protected String userLoginModle;
    protected String clientUserManagerModule ;
    protected String socketClientAgentPool;
    protected Set<String> clientServers =new HashSet<>();
    protected String defaultServer ;
    protected ConcurrentHashMap<String, TLBaseModule> servers  ;

    public TLUserRouterModule(String name , TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void initProperty(){
        super.initProperty();
        if(params !=null && params.get("serverUserManagerModule")!=null)
            serverUserManagerModule = params.get("serverUserManagerModule");
        if(params !=null && params.get("clientUserManagerModule")!=null)
            clientUserManagerModule = params.get("clientUserManagerModule");
        if(params !=null && params.get("userLoginModle")!=null)
            userLoginModle = params.get("userLoginModle");
        if(params !=null && params.get("socketClientAgentPool")!=null)
            socketClientAgentPool = params.get("socketClientAgentPool");
        if(params !=null && params.get("defaultServer")!=null)
            defaultServer = params.get("defaultServer");
    }

    @Override
    protected TLBaseModule init() {
        TLMsg serverMsg =createMsg().setAction(SOCKETCLIENTAGENTPOOL_GETSERVER);
        TLMsg returnMsg = putMsg(socketClientAgentPool,serverMsg);
        servers = (ConcurrentHashMap<String, TLBaseModule>) returnMsg.getParam(RESULT);
        return  this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case USERROUTE_ROUSER:
                returnMsg = toUser(fromWho, msg);
                break;
            case USERROUTE_TOSERVER:
                returnMsg = toServer(fromWho, msg);
                break;
            case USERROUTE_SET:
                set(fromWho, msg);
                break;
            case "onLogin":
               onLogin( fromWho,  msg);
                break;
            case "fromRouteServerToRouter":
                fromRouteServerToRouter( fromWho,  msg);
                break;
            default:
                ;
        }
        return returnMsg;
    }

    private void fromRouteServerToRouter(Object fromWho, TLMsg msg) {
        String userid= (String) msg.getParam(USERMANAGER_P_USERID);
        TLMsg routeMsg =createMsg().setAction(USERMANAGER_PUTTOUSER)
                .setParam(WEBSOCKET_P_CONTENT,msg.getArgs() ).setParam(USERMANAGER_P_USERID,userid);
        putMsg(clientUserManagerModule,routeMsg) ;
    }

    private void set(Object fromWho, TLMsg msg) {
        if(!msg.isNull(USERROUTE_P_SERVERUSERMANAGERMODULE))
                  serverUserManagerModule= (String) msg.getParam(USERROUTE_P_SERVERUSERMANAGERMODULE);
        if(!msg.isNull(USERROUTE_P_USERLOGINMODLE))
                userLoginModle= (String) msg.getParam(USERROUTE_P_USERLOGINMODLE);
    }
    private TLMsg toServer(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam(USERMANAGER_P_USERID);
        String server = getUserServer(userid);
        if(server ==null )
            return null;
        HashMap<String,Object> sdatas = (HashMap<String, Object>) msg.getParam(WEBSOCKET_P_CONTENT);
        return route( server, sdatas, (String) msg.getParam("msgid")) ;
    }
    private TLMsg toUser(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam(USERMANAGER_P_USERID);
        String server = getUserServer(userid);
        if(server ==null )
            return null;
        HashMap<String,Object> sdatas =new HashMap<>();
        sdatas.put("datas",msg.getParam(WEBSOCKET_P_CONTENT));
        return route( server, sdatas, null) ;
    }
    protected String getUserServer(String userid){
        TLMsg qmsg =createMsg().setAction(DB_QUERY).setParam("userid",userid);
        TLMsg returnMsg =putMsg(userLoginModle,qmsg);
        Map<String ,Object> loginInfo = (Map<String, Object>) returnMsg.getParam(DB_R_RESULT);
        if(loginInfo ==null || loginInfo.isEmpty())
            return null;
         return  (String) loginInfo.get("server");
    }
    protected TLMsg route(String server, HashMap<String,Object> sdatas,String msgid){
        if(clientServers.contains(server))
        {
            if(msgid ==null)
             msgid ="fromRouteServerToRouter" ;
            sdatas.put("msgid",msgid);
            TLMsg routeMsg =createMsg().setAction(USERMANAGER_PUTTOUSER)
                    .setParam(WEBSOCKET_P_CONTENT,sdatas).setParam(USERMANAGER_P_USERID,server);
            return  putMsg(serverUserManagerModule,routeMsg) ;
        }
       if (servers.containsKey(server)){
            if(msgid ==null)
                msgid ="fromRouteServer" ;
            sdatas.put("msgid",msgid);
            IObject serverObj =servers.get(server) ;
            TLMsg serverMsg =createMsg().setAction(WEBSOCKETCLIENTAGENT_PUTTOSOCKET).setParam(WEBSOCKET_P_CONTENT,sdatas);
            return putMsg(serverObj,serverMsg);
        }
       if(defaultServer !=null){
           if(msgid ==null)
               msgid ="fromRouteServer" ;
           sdatas.put("msgid",msgid);
           sdatas.put("server",server);
           TLMsg serverMsg =createMsg().setAction(WEBSOCKETCLIENTAGENT_PUTTOSOCKET)
                   .setParam(WEBSOCKET_P_CONTENT,sdatas).setSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME,defaultServer);
          return putMsg(socketClientAgentPool,serverMsg);
       }
       return null ;
    }
    private void onLogin(Object fromWho, TLMsg msg) {
        int status ;
        if(!msg.isNull("fromServer"))
        {
            String action = (String) msg.getParam("fromServer");
            if(action.equals("login"))
            {
                if(msg.isNull(USERMANAGER_R_LOGINRESULT))
                    return  ;
                String code = (String) msg.getParam(USERMANAGER_R_LOGINRESULT);
                if(!code.equals("0000"))
                    return   ;
                status =1;
            }
            else if(action.equals("logout"))
                status = 0 ;
            else
                return ;
        }
        else
            return  ;
        setClientToServer((String) msg.getParam(USERMANAGER_P_USERID),status);
    }
    private void setClientToServer(String userid, int status)
    {
        if(status ==1)
            clientServers.add(userid);
        else
            clientServers.remove(userid);
    }
}
