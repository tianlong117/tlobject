package cn.tianlong.tlobject.network.client.websocket;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import okhttp3.Response;

import java.util.HashMap;
import java.util.Map;

public class TLWebSocketClientAgent extends TLBaseModule {
    protected boolean autoConnect =true ;
    protected TLWebSocketClient mclient ;
    protected Boolean connected= false ;
    protected Boolean authState= false ;
    protected String userType="token";
    protected String cerFile ;
    protected String url ;
    protected String severName;
    protected String userName;
    protected String userPasswd;
    protected  Gson gson =new GsonBuilder().serializeNulls().create();
    protected String token ;
    protected String resultFor ;
    protected String resultAction ;
    protected String connectNotifyMsgId;
    protected TLMsg connectNotifyMsg;

    public TLWebSocketClientAgent(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }
    @Override
    protected void setModuleParams() {
         parseParams(params);
    }
    private  void parseParams(Map<String,String> params){
        if (params != null && !params.isEmpty()) {
            if (params.get("autoConnect") != null)
                autoConnect = Boolean.parseBoolean(params.get("autoConnect"));
            if (params.get("connectNotifyMsgId") != null)
                connectNotifyMsgId = params.get("connectNotifyMsgId");
            if (params.get("cerFile") != null)
                cerFile = params.get("cerFile");
            if (params.get("serverName") != null)
                severName = params.get("serverName");
            if (params.get("token") != null)
                token = params.get("token");
            if (params.get(URL) != null)
                url = params.get(URL);
            if (params.get("userType") != null)
                userType = params.get("userType");
            if (params.get("userName") != null)
                userName = params.get("userName");
            if (params.get("userPasswd") != null)
                userPasswd = params.get("userPasswd");
            if (params.get(RESULTFOR) != null)
                resultFor = params.get(RESULTFOR);
            if (params.get(RESULTACTION) != null)
                resultAction = params.get(RESULTACTION);
        }
    }
    @Override
    protected TLBaseModule init() {
        if(autoConnect)
        {
            if(params !=null && params.get(URL)!=null)
               connect(params);
        }
        return this ;
    }
    protected void connect(HashMap<String, String> params) {
        TLWebSocketClient mclient = (TLWebSocketClient) getNewModule("webSocketClient","webSocketClient");
        String curl =(params.get(URL)!=null)? params.get(URL):url ;
        String ccerFile =(params.get("cerFile")!=null)? params.get("cerFile"):cerFile ;
        TLMsg connectMsg =createMsg().setAction(WEBSOCKET_CONNECT)
                .setParam(URL,curl)
                .setParam(RESULTFOR,this)
                .setParam(RESULTACTION,"loginResult");
        if(ccerFile!=null){
            connectMsg .setParam(SSL_SCERFILE,ccerFile);
            connectMsg.setParam(HTTP_P_ISHTTPS ,true);
        }
        String cuserType =(params.get("userType")!=null)? params.get("userType"):userType ;
        if(cuserType.equals("token"))
        {
            String ctoken =(params.get("token")!=null)? params.get("token"):token ;
            connectMsg .setParam("token",ctoken);
        }
        else
        {
            String cuserName =(params.get("userName")!=null)? params.get("userName"):userName ;
            String cuserPasswd =(params.get("userPasswd")!=null)? params.get("userPasswd"):userPasswd ;
            connectMsg .setParam("username",cuserName);
            connectMsg .setParam("passwd",cuserPasswd);
        }
        putMsg(mclient ,connectMsg);
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case WEBSOCKET_CONNECT:
                returnMsg =connect( fromWho,  msg);
                break;
            case WEBSOCKETCLIENTAGENT_PUTTOSOCKET:
                returnMsg =postToSocket( fromWho,  msg);
                break;
            case WEBSOCKET_SENDFILE:
                returnMsg=sendFile( fromWho,   msg);
                break;
            case "loginResult":
                returnMsg =loginResult(fromWho,msg);
                break;
            case WEBSOCKETCLIENTAGENT_GETLOGINSTATE:
                returnMsg =getLoginState(fromWho,msg);
                break;
            case WEBSOCKET_CANCEL:
                cancel( fromWho,   msg);
                break;
            case WEBSOCKET_CLOSE:
                close( fromWho,   msg);
                break;
            default:
                returnMsg =toClient(fromWho,msg);
        }
        return returnMsg;
    }

    private TLMsg toClient(Object fromWho, TLMsg msg) {
        if(mclient==null)
            return createMsg().setParam(RESULT,false) ;
        return   putMsg(mclient ,msg);
    }

    private TLMsg sendFile(Object fromWho, TLMsg msg) {
        if(mclient==null)
            return createMsg().setParam(RESULT,false) ;
        if(connected == false)
            return createMsg().setParam(RESULT,false) ;
        return   putMsg(mclient ,msg);
    }

    protected void cancel(Object fromWho, TLMsg msg) {
        if(mclient==null)
            return;
        TLMsg cancelMsg =createMsg().setAction(WEBSOCKET_CANCEL);
         putMsg(mclient ,cancelMsg);
    }
    protected void close(Object fromWho, TLMsg msg) {
        if(mclient==null)
            return;
        TLMsg closeSocketMsg =createMsg().setAction(WEBSOCKET_CLOSE).setParam("int",1000).setParam("string","bye");
        putMsg(mclient ,closeSocketMsg);
    }
    protected TLMsg getLoginState(Object fromWho, TLMsg msg) {
        return  createMsg().setParam(RESULT,connected).setParam("authState",authState);
    }

    protected TLMsg connect(Object fromWho, TLMsg msg) {
        HashMap<String,Object> msgParams =msg.getArgs() ;
        HashMap<String,String> cparams =new HashMap<>() ;
        for(String key :msgParams.keySet()){
            Object value =msgParams.get(key) ;
            if(value instanceof  String)
                cparams.put(key, (String) value);
        }
        parseParams(cparams);
        if(!msg.isNull(WEBSOCKET_P_CONNNECTNOTIFYMSG))
            connectNotifyMsg = (TLMsg) msg.getParam(WEBSOCKET_P_CONNNECTNOTIFYMSG);
        connect(cparams);
        return  createMsg().setParam("mclient", mclient );
    }

    protected TLMsg postToSocket(Object fromWho, TLMsg msg) {
        if(mclient==null)
            return createMsg().setParam(RESULT,false) ;
        if(connected == false)
            return createMsg().setParam(RESULT,false) ;
        String content =gson.toJson(msg.getParam(WEBSOCKET_P_CONTENT));
        TLMsg clientMsg =createMsg().setAction(WEBSOCKET_SEND)
                .setParam(WEBSOCKET_P_CONTENT,content);
        if(!msg.isNull(RESULTFOR))
        {
            clientMsg .setParam(RESULTFOR,msg.getParam(RESULTFOR));
            clientMsg.setParam(RESULTACTION,msg.getParam(RESULTACTION));
        }
       return   putMsg(mclient ,clientMsg);
    }

    protected TLMsg  loginResult(Object fromWho, TLMsg msg) {
        switch ((String)msg.getParam("status")) {
            case "open":
                mclient = (TLWebSocketClient) msg.getParam("socketClient");
                connected =true ;
                authState =true ;
                if(connectNotifyMsg !=null)
                   putMsg(this,connectNotifyMsg.setParam(WEBSOCKET_P_STATUS,WEBSOCKET_R_OPEN).setParam(WEBSOCKET_R_CLIENTAGENT,name));
                else if(connectNotifyMsgId !=null)
                    putMsg(this,createMsg().setMsgId(connectNotifyMsgId)
                            .setParam(WEBSOCKET_P_STATUS,WEBSOCKET_R_OPEN).setParam(WEBSOCKET_R_CLIENTAGENT,name));
                putLog("登陆成功",LogLevel.DEBUG,"login");
                String mchannel =Thread.currentThread().getId()+"";
                TLMsg mmsg = createMsg().setDestination("msgBroadCast").setAction(MSGBROADCAST_BROADCAST)
                        .setParam(MSGBROADCAST_P_MESSAGETYPE, C_MESSAGETYPE_CLIENTLOGIN )
                        .setParam(USERMANAGER_P_USERID, userName)
                        .setParam(USERMANAGER_P_USERCHANNEL, mchannel);
                putMsg(M_MSGBROADCAST, mmsg);
                break;
            case "message":
                if(resultFor!=null )
                   return putMsg(resultFor,msg.setAction(resultAction).setParam(WEBSOCKET_R_CLIENTAGENT,name));
                 break;
            case "failure":
                connected =false ;
                String channel =Thread.currentThread().getId()+"";
                TLMsg bmsg = createMsg().setDestination("msgBroadCast").setAction(MSGBROADCAST_BROADCAST)
                        .setParam(MSGBROADCAST_P_MESSAGETYPE, C_MESSAGETYPE_CLIENTLOGOUT)
                        .setParam(USERMANAGER_P_USERID, userName)
                        .setParam(USERMANAGER_P_USERCHANNEL, channel);
                putMsg(M_MSGBROADCAST, bmsg);
                Response response = (Response) msg.getParam(WEBRESPONSE);
                if(response !=null)
                {
                    String message = response.message();
                    if(message.equals("Forbidden"))
                    {
                        authState=false;
                        putLog("auth failure;",LogLevel.DEBUG,"failure");
                    }
                }
                if(connectNotifyMsg !=null)
                    return   putMsg(this,connectNotifyMsg.setParam(WEBSOCKET_P_STATUS,WEBSOCKET_R_FAILURE).setParam(WEBSOCKET_R_CLIENTAGENT,name));
                else if(connectNotifyMsgId !=null)
                    return   putMsg(this,createMsg().setMsgId(connectNotifyMsgId).setParam(WEBSOCKET_P_STATUS,WEBSOCKET_R_FAILURE).setParam(WEBSOCKET_R_CLIENTAGENT,name));
                break;
            default:
                ;
        }
        return null;
    }
}
