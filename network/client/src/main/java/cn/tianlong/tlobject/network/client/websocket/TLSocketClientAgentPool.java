package cn.tianlong.tlobject.network.client.websocket;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.network.common.TLBaseWebSocketSendFile;
import cn.tianlong.tlobject.network.common.TLNetSession;
import cn.tianlong.tlobject.utils.TLMsgUtils;
import org.xmlpull.v1.XmlPullParser;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLSocketClientAgentPool extends TLBaseModule {
    protected int  waitTime=20000 ;
    protected String defaultServer ;
    protected Boolean ifAutoConnect =true ;
    protected String onMessageModule;
    protected String onMessageModuleAction ;
    protected String onServerStatusModule ;
    protected String onServerStatusAction ;
    protected String onMessageMsgid ;
    protected HashMap<String, HashMap<String, String>> servers;
    protected HashMap<String, TLBaseModule> serversModule =new HashMap<>();
    protected ConcurrentHashMap<String, TLBaseModule> sucessServers =new ConcurrentHashMap<>() ;
    protected TLNetSession netSession ;

    public TLSocketClientAgentPool() {
        super();
    }

    public TLSocketClientAgentPool(String name) {
        super(name);
    }

    public TLSocketClientAgentPool(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void  setModuleParams(){
        if(params !=null && params.get("defaultServer")!=null) {
            defaultServer=params.get("defaultServer");
        }
        if(params !=null && params.get("ifAutoConnect")!=null) {
            ifAutoConnect=Boolean.parseBoolean(params.get("ifAutoConnect"));
        }
        if(params !=null && params.get("onMessageModule")!=null) {
            onMessageModule=params.get("onMessageModule");
        }
        if(params !=null && params.get("onMessageModuleAction")!=null) {
            onMessageModuleAction=params.get("onMessageModuleAction");
        }
        if(params !=null && params.get("onMessageMsgid")!=null) {
            onMessageMsgid=params.get("onMessageMsgid");
        }
        if(params !=null && params.get("onServerStatusAction")!=null) {
            onServerStatusAction=params.get("onServerStatusAction");
        }
        if(params !=null && params.get("onServerStatusModule")!=null) {
            onServerStatusModule=params.get("onServerStatusModule");
        }
        if(params !=null && params.get("waitTime")!=null) {
            waitTime=Integer.parseInt(params.get("waitTime"));
        }
    }
    @Override
    protected Object setConfig() {
        if( mconfig ==null)
            mconfig = new myConfig(configFile,moduleFactory.getConfigDir());;
        super.setConfig();
        servers = (( myConfig)mconfig).getServers();
        return mconfig;
    }

    @Override
    protected TLBaseModule init() {
        netSession =new TLNetSession(name+"_session",moduleFactory);
        netSession.start(null,params);
        return this ;
    }
    @Override
    public void runStartMsg(){
        if(ifAutoConnect ==false)
            return  ;
        if(servers ==null || servers.isEmpty())
            return  ;
        for(String serverName :servers.keySet()){
            HashMap<String, String> serverParams =servers.get(serverName) ;
            addAndConnectToServer(serverName,serverParams) ;
        }
    }
    protected void addAndConnectToServer(String serverName , HashMap<String, String> serverParams){
        addServer( serverName , serverParams);
        connectToServer(serverName);
    }
    protected void addServer(String serverName ,HashMap<String, String> serverParams){
        if(serverParams.get(RESULTFOR) ==null)
            serverParams.put(RESULTFOR,name);
        if(serverParams.get(RESULTACTION) ==null)
            serverParams.put(RESULTACTION,"onMessage");
        String module =(serverParams.get("module")==null ||serverParams.get("module").isEmpty() )? "webSocketClientAgent" :serverParams.get("module") ;
        TLBaseModule serverObj = (TLBaseModule) getNewModule(serverName,module,serverParams);
        serversModule.put(serverName,serverObj) ;
    }
    protected void connectToServer(String serverName){
        TLBaseModule serverObj = serversModule.get(serverName);
        if(serverObj ==null)
            return;
        TLMsg nmsg =createMsg().setAction("fromAgent").setDestination(name);
        TLMsg cmsg =createMsg().setAction(WEBSOCKET_CONNECT).setParam(WEBSOCKET_P_CONNNECTNOTIFYMSG,nmsg) ;
        putMsg(serverObj,cmsg) ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case WEBSOCKETCLIENTAGENT_PUTTOSOCKET:
                returnMsg = putToServer(fromWho, msg);
                break;
            case SOCKETCLIENTAGENTPOOL_PUTTOSERVERANDWAIT:
                returnMsg = putToServerAndWait(fromWho, msg);
                break;
            case SOCKETCLIENTAGENTPOOL_PUTMSGTOSERVER:
                returnMsg = putMsgToServer(fromWho, msg);
                break;
            case WEBSOCKET_SENDFILE:
                returnMsg=sendFile( fromWho,   msg);
                break;
            case WEBSOCKET_SENDBINARY:
                returnMsg = sendBinary(fromWho, msg);
                break;
            case USERMANAGER_RECEIVEBINARY:
                returnMsg = receiveBinary(fromWho, msg);
                break;
            case "fromAgent":
                fromAgent(fromWho, msg);
                break;
            case SOCKETCLIENTAGENTPOOL_GETSERVER :
                returnMsg =getServer(fromWho, msg);
                break;
            case SOCKETCLIENTAGENTPOOL_ADDSERVER :
                addServer(fromWho, msg);
                break;
            case SOCKETCLIENTAGENTPOOL_REMOVESERVER :
                removeServer(fromWho, msg);
                break;
            case SOCKETCLIENTAGENTPOOL_CLOSESERVER :
                closeServer(fromWho, msg);
                break;
            case "onMessage" :
                returnMsg =onMessage(fromWho,msg) ;
                break;
            default:
                returnMsg =proxyPut(fromWho, msg);
        }
        return returnMsg;
    }
    protected TLMsg receiveBinary(Object fromWho, TLMsg msg) {
        if (msg.isNull(WEBSOCKET_P_BINARYCMDCODE))
            return createMsg().setParam(RESULT, false);
        byte cmdCode = (byte) msg.getParam(WEBSOCKET_P_BINARYCMDCODE);
        switch (cmdCode) {
            case WEBSOCKET_V_BINARYMFILEDATACMDERRORCODE:
                return sendFileError( msg);
            case WEBSOCKET_V_BINARYMFILRECEIVEOVERCODE:
                 receiveFileOver( msg);
                 break;
            default:
                return null;
        }
        return null ;
    }

    protected void receiveFileOver(TLMsg msg) {
        if(msg.isNull(WEBSOCKET_P_BINARYSESSION) && !(msg.getParam(WEBSOCKET_P_BINARYSESSION) instanceof  Integer ))
            return ;
        int sessionId = (int) msg.getParam(WEBSOCKET_P_BINARYSESSION);
        netSession.saveSesstiondata( String.valueOf(sessionId),msg);
        return ;
    }

    protected TLMsg sendFileError(TLMsg msg) {
        String serverName = (String) msg.getParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        IObject server =getServer(serverName);
        if(server ==null)
            return createMsg().setParam(RESULT,0) ;
        msg.removeParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        if(!msg.isNull(DOWITHMAG))
            msg.removeParam(DOWITHMAG) ;
        TLMsg smsg =createMsg().setAction(USERMANAGER_SENDFILEERROR).setArgs(msg.getArgs());
        return putMsg(server,smsg);
    }

    protected TLMsg sendBinary(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        IObject server =getServer(serverName);
        if(server ==null)
            return createMsg().setParam(RESULT,0) ;
        msg.removeParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        if(!msg.isNull(DOWITHMAG))
            msg.removeParam(DOWITHMAG) ;
        TLMsg smsg =createMsg().setAction(WEBSOCKET_SENDBINARY).setArgs(msg.getArgs());
        return putMsg(server,smsg);
    }

    protected TLMsg sendFile(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        IObject server =getServer(serverName);
        if(server ==null)
            return createMsg().setParam(RESULT,0) ;
        msg.removeParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        if(!msg.isNull(DOWITHMAG))
            msg.removeParam(DOWITHMAG) ;
        return TLBaseWebSocketSendFile.sendFile(this,msg,netSession,server);
    }

    protected TLMsg putMsgToServer(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        IObject server =getServer(serverName);
        if(server ==null)
            return createMsg().setParam(RESULT,0) ;
        msg.removeParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        Map<String,Object> content =msg.getArgs();
        TLMsg serverMsg =createMsg().setAction(WEBSOCKETCLIENTAGENT_PUTTOSOCKET)
                .setParam(WEBSOCKET_P_CONTENT,TLMsgUtils.mapToWebsocketJsonMap(content));
        return putMsg(server,serverMsg);
    }

    protected TLMsg putToServerAndWait(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        IObject server =getServer(serverName);
        if(server ==null)
            return createMsg().setParam(RESULT,0) ;
        int waitTime =msg.getIntParam(NETSESSION_P_WAITTIME,this.waitTime );
        msg.removeParam(NETSESSION_P_WAITTIME);
        int retryTimes =msg.getIntParam(ETSESSION_P_RETRYTIMES,0);
        msg.removeParam(ETSESSION_P_RETRYTIMES) ;
        msg.removeParam(DOWITHMAG) ;
        msg.removeParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        msg.removeParam(WEBSOCKETCLIENTAGENT_ISWAIT);
        Map<String,Object> content =msg.getArgs();
        String sessionId = netSession.makeSessionId();
        content.put(WEBSOCKET_P_SESSION,sessionId);
        TLMsg serverMsg =createMsg().setAction(WEBSOCKETCLIENTAGENT_PUTTOSOCKET)
                .setParam(WEBSOCKET_P_CONTENT,TLMsgUtils.mapToWebsocketJsonMap(content));
        TLMsg resultMsg=putMsg(server,serverMsg);
        Boolean result = (Boolean) resultMsg.getParam(RESULT);
        if(result ==false)
            return resultMsg ;
        TLMsg serverReturnMsg = netSession.waitServerReturnUntilTimeOut(sessionId,server,serverMsg,waitTime,retryTimes) ;
        if(serverReturnMsg.getMsgId() !=null)
            return getMsg(this,serverReturnMsg);
        else
            return serverReturnMsg ;
    }

    protected TLMsg proxyPut(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        IObject server =getServer(serverName);
        if(server ==null)
            return createMsg().setParam(RESULT,0) ;
        String module = (String) msg.getParam(MSG_P_MODULE);
        if(module ==null)
        {
            module =msg.getNowObject() ;
            msg.setParam(MSG_P_MODULE,module);
        }
        msg.removeParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        int waitTime =msg.getIntParam(NETSESSION_P_WAITTIME,this.waitTime );
        msg.removeParam(NETSESSION_P_WAITTIME);
        int retryTimes =msg.getIntParam(ETSESSION_P_RETRYTIMES,0);
        msg.removeParam(ETSESSION_P_RETRYTIMES) ;
        msg.removeParam(DOWITHMAG) ;
        HashMap<String,Object> sdatas =TLMsgUtils.msgToMap(msg);
        String sessionId = netSession.makeSessionId();
        sdatas.put(WEBSOCKET_P_SESSION,sessionId);
        TLMsg serverMsg =createMsg().setAction(WEBSOCKETCLIENTAGENT_PUTTOSOCKET)
                .setParam(WEBSOCKET_P_CONTENT,sdatas);
        TLMsg resultMsg=putMsg(server,serverMsg);
        Boolean result = (Boolean) resultMsg.getParam(RESULT);
        if(result ==false)
            return resultMsg ;
        TLMsg serverReturnMsg = netSession.waitServerReturnUntilTimeOut(sessionId,server,serverMsg,waitTime,retryTimes) ;
        if(serverReturnMsg.getMsgId() !=null)
            return getMsg(this,serverReturnMsg);
        else
            return serverReturnMsg ;
    }
    protected TLMsg getServer(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        if(serverName !=null)
            return createMsg().setParam(RESULT,sucessServers.get(serverName));
         else
            return createMsg().setParam(RESULT,sucessServers);
    }

    protected TLMsg onMessage(Object fromWho, TLMsg msg) {
        TLMsg smsg  = (TLMsg) msg.getParam(USERMANAGER_P_CLIENTMSG);
        if(smsg ==null)
        {
            String content = (String) msg.getParam(WEBRESPONSE);
            if(content.equals("otherlogin"))
                return createMsg().setParam("reConnect",false);
            smsg =  TLMsgUtils.websocketJsonStrToMsg(content);
            if(smsg ==null)
            {
                putLog("非json字符:"+content, LogLevel.WARN);
                return null ;
            }
        }
        if(!smsg.isNull(WEBSOCKET_P_SESSION)){
            String sessionId =(String) smsg.getParam(WEBSOCKET_P_SESSION);
            smsg.removeParam(WEBSOCKET_P_SESSION);
            netSession.saveSesstiondata( sessionId,smsg);
            return null;
        }
        String notifyId = (String) smsg.getParam( WEBSOCKET_P_NOTIFYID);
        String server = (String) msg.getParam(WEBSOCKET_R_CLIENTAGENT);
        smsg.setParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME,server);
        smsg.removeParam( WEBSOCKET_P_NOTIFYID);
        TLMsg returnMsg ;
        if(onMessageModule !=null )
            returnMsg= putMsg(onMessageModule, smsg);
        else  if(onMessageMsgid !=null )
            returnMsg=getMsg(this, createMsg().setMsgId(onMessageMsgid).addArgs(smsg.getArgs()));
        else
            returnMsg=  getMsg(this, smsg);
        if(notifyId!=null)
        {
            if(returnMsg !=null)
            {
                Map<String,Object>  params =returnMsg.getArgs();
                if(params !=null && !params.isEmpty())
                {
                    HashMap<String,Object> rparams =new HashMap<>();
                    rparams.putAll(params);
                    returnMsg.clearParam();
                    returnMsg.setParam(MSG_P_PARAMS,rparams);
                }
                returnMsg.setParam(WEBSOCKET_P_NOTIFYID,notifyId);
            }
            else
                returnMsg= createMsg().setParam(WEBSOCKET_P_NOTIFYID,notifyId);
        }
        if (notifyId!=null ||(returnMsg != null  && returnMsg.parseBoolean(SOCKETSERVER_R_IFRETURN,false)))
        {
            TLMsg toUserMsg =createMsg().setAction(WEBSOCKETCLIENTAGENT_PUTTOSOCKET);
            toUserMsg.setParam(WEBSOCKET_P_CONTENT,returnMsg.getArgs()).setParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME,server);
            return putToServer(this,toUserMsg);
        }
        return null ;
    }
    protected TLMsg closeServer(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        return closeServer(  serverName);
    }
    protected TLMsg closeServer(String serverName) {
        IObject server =sucessServers.get(serverName);
        if(server ==null)
            return null ;
        return putMsg(server,createMsg().setAction(WEBSOCKET_CLOSE)) ;
    }
    protected void removeServer(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        closeServer(serverName);
        sucessServers.remove(serverName) ;
    }

    protected void addServer(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        if(sucessServers.containsKey(serverName))
            return;
        HashMap<String,String> serverParams = (HashMap<String, String>) msg.getParam(SOCKETCLIENTAGENTPOOL_P_SERVERPARAM);
        addAndConnectToServer(serverName,serverParams) ;
    }
    protected void fromAgent(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getParam(WEBSOCKET_R_CLIENTAGENT);
        String status = (String) msg.getParam(WEBSOCKET_P_STATUS);
        if (status.equals(WEBSOCKET_R_OPEN))
            sucessServers.put(serverName,serversModule.get(serverName));
        else if(status.equals(WEBSOCKET_R_FAILURE))
             sucessServers.remove(serverName) ;
        if(onServerStatusModule !=null )
            putMsg(onServerStatusModule, createMsg().setAction(onServerStatusAction)
                    .setParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME,serverName).setParam(WEBSOCKET_P_STATUS,status));
    }

    protected TLMsg putToServer(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        IObject server =getServer(serverName);
        if(server ==null)
            return createMsg().setParam(RESULT,0) ;
        return putMsg(server,msg);
    }
    protected IObject getServer( String serverName){
         if(serverName ==null)
             serverName =defaultServer ;
         if(serverName ==null)
             return null ;
         return  sucessServers.get(serverName);
     }
    protected class myConfig extends TLModuleConfig {
        protected HashMap<String, HashMap<String, String>> servers;
         public myConfig(String configFile ,String configDir) {
             super(configFile,configDir);
         }
        public myConfig() {

        }
        public HashMap getServers() {
            return servers;
        }
        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if (xpp.getName().equals("servers")) {
                    servers = getHashMap(xpp, "servers", "server");
                }
            } catch (Throwable t) {

            }
        }

    }
}
