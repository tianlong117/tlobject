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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.lang.Thread.sleep;

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
    protected ConcurrentHashMap<String, TLBaseModule> serversModule =new ConcurrentHashMap<>();
    protected ConcurrentHashMap<String, TLBaseModule> sucessServers =new ConcurrentHashMap<>() ;
    protected TLNetSession netSession ;
    private final ConcurrentHashMap<String, CountDownLatch> connectLatches = new ConcurrentHashMap<>();

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
        for(String serverName :servers.keySet())
        {
            HashMap<String, String> serverParams =servers.get(serverName) ;
            boolean autoConnect =true ;
            if (serverParams.get("autoConnect") != null)
                autoConnect = Boolean.parseBoolean(serverParams.get("autoConnect"));
            if(autoConnect )
            {
                TLBaseModule serverObj =addServer( serverName , serverParams);
                connectToServer(serverObj) ;
            }

        }
    }
    protected void connectToServer(TLBaseModule serverObj ){

        TLMsg nmsg =createMsg().setAction("fromAgent").setDestination(name);
        TLMsg msg =createMsg().setAction(WEBSOCKET_CONNECT).setParam(WEBSOCKET_P_CONNNECTNOTIFYMSG,nmsg) ;
        putMsg(serverObj,msg) ;
    }
    protected TLBaseModule addServer(String serverName ,HashMap<String, String> serverParams){
        serverParams.put("autoConnect","false") ;
        if(serverParams.get(RESULTFOR) ==null)
            serverParams.put(RESULTFOR,name);
        if(serverParams.get(RESULTACTION) ==null)
            serverParams.put(RESULTACTION,"onMessage");
        String module =(serverParams.get("module")==null ||serverParams.get("module").isEmpty() )? "webSocketClientAgent" :serverParams.get("module") ;
        TLBaseModule serverObj = (TLBaseModule) getNewModule(serverName,module,serverParams);
        serversModule.put(serverName,serverObj) ;
        return serverObj;
    }
    protected void connectToServer(String serverName){
        TLBaseModule serverObj = serversModule.get(serverName);
        if(serverObj ==null)
        {
            HashMap<String, String> serverParams =servers.get(serverName) ;
            if(serverParams ==null || serverName.isEmpty())
                return;
            serverObj = addServer(serverName,serverParams) ;
        }
        connectToServer(serverObj) ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case WEBSOCKET_CONNECT:
                returnMsg = connectServer(fromWho, msg);
                break;
            case WEBSOCKET_PUT:
                returnMsg = putToServer(fromWho, msg);
                break;
            case WEBSOCKET_PUTANDWAIT:
                returnMsg = putToServerAndWait(fromWho, msg);
                break;
            case WEBSOCKET_PUTMSG:
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
                returnMsg = fromAgent(fromWho, msg);
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

    private TLMsg connectServer(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        HashMap<String,String>    serverParams =servers.get(serverName) ;
        if(serverParams ==null || serverName.isEmpty())
            return createMsg().setParam(RESULT,false);
        CountDownLatch latch = new CountDownLatch(1);
        connectLatches.put(serverName, latch);
        connectToServer(serverName) ;
        boolean success = false;
        try {
            success = latch.await(4000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        connectLatches.remove(serverName);
        return createMsg().setParam(RESULT, success);
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
        String serverName = (String) msg.getSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        IObject server =getServer(serverName);
        if(server ==null)
            return createMsg().setParam(RESULT,0) ;
        TLMsg smsg =createMsg().setAction(USERMANAGER_SENDFILEERROR).setArgs(msg.getArgs());
        return putMsg(server,smsg);
    }

    protected TLMsg sendBinary(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        IObject server =getServer(serverName);
        if(server ==null)
            return createMsg().setParam(RESULT,0) ;
        TLMsg smsg =createMsg().setAction(WEBSOCKET_SENDBINARY).setArgs(msg.getArgs());
        return putMsg(server,smsg);
    }

    protected TLMsg sendFile(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        IObject server =getServer(serverName);
        if(server ==null)
            return createMsg().setParam(RESULT,0) ;
        return TLBaseWebSocketSendFile.sendFile(this,msg,netSession,server);
    }

    protected TLMsg putToServer(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        IObject server =getServer(serverName);
        if(server ==null)
            return createMsg().setParam(RESULT,0) ;
        return putMsg(server,msg);
    }

    protected TLMsg putMsgToServer(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        IObject server =getServer(serverName);
        if(server ==null)
            return createMsg().setParam(RESULT,false) ;
        TLMsg serverMsg =createMsg().setAction(WEBSOCKET_PUT)
                .setParam(WEBSOCKET_P_CONTENT,msg.getArgs());
        return putMsg(server,serverMsg);
    }

    protected TLMsg putToServerAndWait(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        IObject server =getServer(serverName);
        if(server ==null)
            return createMsg().setParam(RESULT,0) ;
        int waitTime = (int) msg.getSystemParam(NETSESSION_P_WAITTIME,this.waitTime );
        int retryTimes = (int) msg.getSystemParam(NETSESSION_P_RETRYTIMES,0);
        Map<String,Object> content =msg.getArgs();
        Map<String,Object> systemArgs = (Map<String, Object>) content.get(MSG_P_SYSTEMARGS);
        if(systemArgs ==null)
        {
            systemArgs =new HashMap<>();
            content.put(MSG_P_SYSTEMARGS,systemArgs);
        }
        String sessionId = netSession.makeSessionId();
        systemArgs.put(WEBSOCKET_P_SESSION,sessionId);
        TLMsg serverMsg =createMsg().setAction(WEBSOCKET_PUT)
                .setParam(WEBSOCKET_P_CONTENT,content);
        TLMsg resultMsg=putMsg(server,serverMsg);
        if(!resultMsg.parseBoolean(RESULT, true))
            return resultMsg ;
        TLMsg serverReturnMsg = netSession.waitServerReturnUntilTimeOut(sessionId,server,serverMsg,waitTime,retryTimes) ;
        if(serverReturnMsg.getMsgId() !=null)
            return getMsg(this,serverReturnMsg);
        else
        {
            String arrivedMsgId= (String) serverReturnMsg.getSystemParam(WEBSOCKET_P_SESSIONARRIVEDMSGID);
            if(arrivedMsgId !=null)
                return getMsg(this,serverReturnMsg.setMsgId(arrivedMsgId));
        }
         return serverReturnMsg ;
    }

    protected TLMsg proxyPut(Object fromWho, TLMsg msg) {
        msg.setArgs(TLMsgUtils.msgToSocketDataMap(msg));
        return putToServerAndWait( fromWho, msg) ;
    }

    protected TLMsg getServer(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        if(serverName !=null)
            return createMsg().setParam(RESULT,sucessServers.get(serverName));
         else
            return createMsg().setParam(RESULT,sucessServers);
    }

    protected TLMsg onMessage(Object fromWho, TLMsg msg) {
        TLMsg serverMsg  = (TLMsg) msg.getParam(USERMANAGER_P_CLIENTMSG);
        String server = (String) msg.getParam(WEBSOCKET_R_CLIENTAGENT);
        if(serverMsg ==null)
        {
            String content = (String) msg.getParam(WEBRESPONSE);
            if(content.equals("otherlogin"))
                return createMsg().setParam("reConnect",false);
            serverMsg =  TLMsgUtils.websocketJsonStrToMsg(content);
            if(serverMsg ==null)
            {
                putLog("非json字符:"+content, LogLevel.WARN);
                return null ;
            }
        }
        serverMsg.setSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME,server);
        if(!serverMsg.systemParamIsNull(WEBSOCKET_P_SESSION)){
           String sessionId =(String) serverMsg.getSystemParam(WEBSOCKET_P_SESSION);
            netSession.saveSesstiondata( sessionId,serverMsg);
            return null;
        }
        String notifyId = (String) serverMsg.getSystemParam( WEBSOCKET_P_NOTIFYID);
        String  arrivedMsgid = (String) serverMsg.getSystemParam(WEBSOCKET_P_SESSIONARRIVEDMSGID);
        boolean socketSessionIsarrived = (boolean) serverMsg.getSystemParam(WEBSOCKET_P_SESSIONISARRIVED,false);
        if(socketSessionIsarrived ==true && notifyId !=null)
        {
            HashMap<String,Object> echodata = new HashMap<>();
            echodata.put(MSG_P_SYSTEMARGS,serverMsg.getSystemArgs());
            TLMsg echoMsg =createMsg().setAction(WEBSOCKET_PUT).setParam(WEBSOCKET_P_CONTENT,echodata)
                    .setSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME,server);
            putToServer(this,echoMsg);
        }
        TLMsg returnMsg ;
        if(onMessageModule !=null )
            returnMsg= putMsg(onMessageModule, serverMsg);
        else  if(onMessageMsgid !=null )
        {
            TLMsg mmsg =createMsg().setMsgId(onMessageMsgid).setSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME,server).addArgs(serverMsg.getArgs());
            returnMsg=getMsg(this, mmsg );
        }
        else
            returnMsg=  getMsg(this, serverMsg);
        if(notifyId !=null)
        {
            returnMsg = TLMsgUtils.addSystemArgToMsg(WEBSOCKET_P_NOTIFYID,notifyId,returnMsg);
            if( arrivedMsgid !=null)
                returnMsg = TLMsgUtils.addSystemArgToMsg(WEBSOCKET_P_SESSIONARRIVEDMSGID,  arrivedMsgid,returnMsg);
        }

        if(notifyId!=null
           || (returnMsg !=null && ((Boolean)returnMsg.getSystemParam(SOCKETSERVER_R_IFRETURN,false))==true))
        {
            TLMsg toUserMsg =createMsg().setAction(WEBSOCKET_PUT).setParam(WEBSOCKET_P_CONTENT,returnMsg.getArgs())
                            .setSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME,server);
            return putToServer(this,toUserMsg);
        }
        return null ;
    }

    protected TLMsg closeServer(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        return closeServer(  serverName);
    }

    protected TLMsg closeServer(String serverName) {
        IObject server =sucessServers.get(serverName);
        if(server ==null)
            return null ;
        return putMsg(server,createMsg().setAction(WEBSOCKET_CLOSE)) ;
    }

    protected void removeServer(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        closeServer(serverName);
        sucessServers.remove(serverName) ;
    }

    protected void addServer(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        if(sucessServers.containsKey(serverName))
            return;
        HashMap<String,String> serverParams = (HashMap<String, String>) msg.getParam(SOCKETCLIENTAGENTPOOL_P_SERVERPARAM);
        if(serverParams ==null)
            serverParams =servers.get(serverName) ;
        if(serverParams ==null || serverName.isEmpty())
            return;
        TLBaseModule serverObj =addServer( serverName , serverParams);
        connectToServer(serverObj) ;
    }

    protected TLMsg fromAgent(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getParam(WEBSOCKET_R_CLIENTAGENT);
        String status = (String) msg.getParam(WEBSOCKET_P_STATUS);
        if (status.equals(WEBSOCKET_R_OPEN)) {
            sucessServers.put(serverName, serversModule.get(serverName));
            CountDownLatch latch = connectLatches.get(serverName);
            if (latch != null) latch.countDown();
        } else if(status.equals(WEBSOCKET_R_FAILURE)) {
             sucessServers.remove(serverName);
             CountDownLatch latch = connectLatches.get(serverName);
             if (latch != null) latch.countDown();
        }
        if(onServerStatusModule !=null )
            putMsg(onServerStatusModule, createMsg().setAction(onServerStatusAction)
                    .setSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME,serverName).setParam(WEBSOCKET_P_STATUS,status));
        return null;
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
