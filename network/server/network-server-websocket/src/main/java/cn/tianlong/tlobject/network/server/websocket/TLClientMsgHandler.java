package cn.tianlong.tlobject.network.server.websocket;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLReUsedModulePool;
import cn.tianlong.tlobject.network.common.TLBaseWebSocketSendFile;
import cn.tianlong.tlobject.network.common.TLNetSession;
import cn.tianlong.tlobject.utils.TLDataUtils;
import cn.tianlong.tlobject.utils.TLMsgUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;


/**
 * 创建日期：2020/2/1214:11
 * 描述:
 * 作者:tianlong
 */
public class TLClientMsgHandler extends TLBaseModule {
    protected int  waitTime=20000 ;
    protected String userManagerModule ="userManagerModule";
    protected String clientSessionPoolName ="reUsedSingleThreadPoolGroup";
    protected TLNetSession netSession ;
    protected ArrayList<String> msgidInPool  ;
    protected TLReUsedModulePool  sessionPool ;
    protected String msgBroadCast =M_MSGBROADCAST;
    protected ArrayList<String>  directToModule ;
    public TLClientMsgHandler(String name , TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }
    @Override
    protected void initProperty(){
        super.initProperty();
        if(params!=null)
        {
            if(params.get("msgidInPool")!=null)
                msgidInPool =TLDataUtils.splitStrToList(params.get("msgidInPool"),";") ;
            if(params.get("directToModule")!=null)
                directToModule =TLDataUtils.splitStrToList(params.get("directToModule"),";");
            if(params.get("clientSessionPoolName")!=null && !params.get("clientSessionPoolName").isEmpty())
                clientSessionPoolName =params.get("clientSessionPoolName");
            if( params.get("waitTime")!=null)
                waitTime=Integer.parseInt(params.get("waitTime"));
            if (params.get("msgBroadCast") != null)
                msgBroadCast = params.get("msgBroadCast");
        }
    }
    @Override
    protected TLBaseModule init() {
        netSession =new TLNetSession(name+"_session",moduleFactory);
        netSession.start(null,params);
        sessionPool = (TLReUsedModulePool) getNewModule(clientSessionPoolName,clientSessionPoolName);
        TLMsg receivermsg = createMsg().setDestination(name).setAction("onUserLogout");
        putMsg(msgBroadCast, createMsg().setAction(MSGBROADCAST_REGISTRECEIVER)
                .setParam(MSGBROADCAST_P_MESSAGETYPE, C_MESSAGETYPE_LOGOUT).setParam(MSGBROADCAST_P_RECEIVEMSG, receivermsg));
        return this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "fromClient":
                returnMsg = fromClient(fromWho, msg);
                break;
            case WEBSOCKET_SENDFILE:
                returnMsg = sendFile(fromWho, msg);
                break;
            case CLIENTMSGHANDLER_TOCLIENT:
                returnMsg = toClient(fromWho, msg);
                break;
            case CLIENTMSGHANDLER_TOCLIENTANDWWAIT:
                returnMsg = toClientAndWait(fromWho, msg);
                break;
            case USERMANAGER_SENDBINARY:
                returnMsg = sendBinary(fromWho, msg);
                break;
            case USERMANAGER_RECEIVEBINARY:
                returnMsg = receiveBinary(fromWho, msg);
                break;
            case "onUserLogout":
                onUserLogout(fromWho, msg);
                break;
            case "threadReturn":
                 threadReturn(fromWho, msg);
                break;
            default:
                fromClient(fromWho, msg);
        }
        return returnMsg;
    }

    private TLMsg receiveBinary(Object fromWho, TLMsg msg) {
        if (msg.isNull(WEBSOCKET_P_BINARYCMDCODE))
            return createMsg().setParam(RESULT, false);
        byte cmdCode = (byte) msg.getParam(WEBSOCKET_P_BINARYCMDCODE);
        switch (cmdCode) {
            case WEBSOCKET_V_BINARYMFILEDATACMDERRORCODE:
                return sendFileError( fromWho,msg);
            case WEBSOCKET_V_BINARYMFILRECEIVEOVERCODE:
                receiveFileOver( msg);
                break;
            default:
                return null;
        }
        return null ;
    }

    private TLMsg sendFileError(Object fromWho, TLMsg msg) {
        return putMsg(userManagerModule,msg.setAction(USERMANAGER_SENDFILEERROR));
    }
    private void receiveFileOver(TLMsg msg) {
        if(msg.isNull(WEBSOCKET_P_BINARYSESSION) && !(msg.getParam(WEBSOCKET_P_BINARYSESSION) instanceof  Integer ))
            return ;
        int sessionId = (int) msg.getParam(WEBSOCKET_P_BINARYSESSION);
        netSession.saveSesstiondata( String.valueOf(sessionId),msg);
        return ;
    }
    private void onUserLogout(Object fromWho, TLMsg msg) {
        String channel =  msg.getStringParam(USERMANAGER_P_USERCHANNEL,null);
        if(channel ==null)
            return;
        sessionPool.removeUser(channel);
    }

    private void threadReturn(Object fromWho, TLMsg msg) {
        sessionPool.useModuleOver();
        HashMap<String,Object> channelData = (HashMap<String, Object>) msg.getMapParam(TASKRESESSIONDATA,null);
        if(channelData ==null)
            return;
        String sesstionId = (String) channelData.get(WEBSOCKET_P_SESSION);
        if(sesstionId !=null && !sesstionId.isEmpty())
            toClient(this,msg.addMap(channelData));
    }

    private TLMsg sendBinary(Object fromWho, TLMsg msg) {
        return putMsg(userManagerModule,msg);
    }
    private TLMsg sendFile(Object fromWho, TLMsg msg) {
        IObject  sendModule = (IObject) getModule(userManagerModule);
        return TLBaseWebSocketSendFile.sendFile(this,msg,netSession,sendModule);
    }
    private TLMsg toClient(Object fromWho, TLMsg msg) {
        Map<String,Object> content =msg.getArgs();
        TLMsg umsg = createMsg().setAction(USERMANAGER_PUTTOUSER)
                .setParam(USERMANAGER_P_USERID,msg.getParam(USERMANAGER_P_USERID))
                .setParam(USERMANAGER_P_USERCHANNEL,msg.getParam(USERMANAGER_P_USERCHANNEL));
         msg.removeParam(USERMANAGER_P_USERCHANNEL);
         umsg.setParam(WEBSOCKET_P_CONTENT, TLMsgUtils.mapToWebsocketJsonMap(content));
        return putMsg(userManagerModule, umsg);
    }

    private TLMsg toClientAndWait(Object fromWho, TLMsg msg) {
        Object channel =msg.getParam(USERMANAGER_P_USERCHANNEL);
        if(channel ==null)
        {
            TLMsg  userChannelsMsg =putMsg(userManagerModule,msg.setAction(USERMANAGER_GETUSERCHANNELS));
            if(userChannelsMsg.isNull(USERMANAGER_R_USERCHANNEL))
                return createMsg().setParam(RESULT,false);
            channel =userChannelsMsg.getParam(USERMANAGER_R_USERCHANNEL) ;
        }
        else
            msg.removeParam(USERMANAGER_P_USERCHANNEL);
        int waitTime =msg.getIntParam(NETSESSION_P_WAITTIME,this.waitTime) ;
        msg.removeParam(NETSESSION_P_WAITTIME);
        int retryTimes =msg.getIntParam(ETSESSION_P_RETRYTIMES,0) ;
        msg.removeParam(ETSESSION_P_RETRYTIMES);
        String sessionId = netSession.makeSessionId();
        Map<String,Object> content =msg.getArgs();
        content.put(WEBSOCKET_P_NOTIFYID,sessionId);
        TLMsg cmsg=createMsg().setAction(USERMANAGER_PUTTOUSER)
                .setParam(USERMANAGER_P_USERCHANNEL,channel)
                .setParam(WEBSOCKET_P_CONTENT, TLMsgUtils.mapToWebsocketJsonMap(content));
        TLMsg resultMsg= putMsg(userManagerModule,cmsg);
        Boolean result =  resultMsg.parseBoolean(RESULT,false);
        if(result ==false)
            return resultMsg ;
        TLBaseModule umodule = (TLBaseModule) getModule(userManagerModule);
        TLMsg clientReturnMsg = netSession.waitServerReturnUntilTimeOut(sessionId,umodule,cmsg,waitTime,retryTimes) ;
        if(clientReturnMsg.getMsgId() !=null)
            return getMsg(this,clientReturnMsg);
        else
            return clientReturnMsg ;
    }

    protected TLMsg fromClient(Object fromWho, TLMsg msg) {
        TLMsg clientMsg = (TLMsg) msg.getParam(USERMANAGER_P_CLIENTMSG);
        if(clientMsg ==null)
            return null ;
        if(!clientMsg.isNull(WEBSOCKET_P_NOTIFYID))
        {
            String notifyId =(String) clientMsg.getParam(WEBSOCKET_P_NOTIFYID);
            clientMsg.removeParam(WEBSOCKET_P_NOTIFYID);
            netSession.saveSesstiondata(notifyId,clientMsg);
            return null;
        }
        String channel  = (String) msg.getParam(USERMANAGER_P_USERCHANNEL);
        String clientMsgid =clientMsg.getMsgId();
        String sesstionId = (String) clientMsg.getParam(WEBSOCKET_P_SESSION);
        if(clientMsgid !=null && msgidInPool !=null && msgidInPool.contains(clientMsgid))
        {
            despatchMsgBySessionPool(clientMsgid ,clientMsg,channel, sesstionId);
            return null ;
        }
        String destination =clientMsg.getDestination();
        if(destination !=null)
        {
            if(directToModule ==null)
                return null ;
            if( !directToModule.contains(destination) && !directToModule.contains("*"))
                return null ;
        }
        TLMsg returnMsg = despatchMsg(clientMsg) ;
        if(sesstionId ==null)
             return returnMsg ;
        if(returnMsg !=null)
        {
            HashMap<String,Object>  websocketJsonMap = TLMsgUtils.mapToWebsocketJsonMap(returnMsg.getArgs());
            returnMsg.setArgs(websocketJsonMap)
                      .setParam(WEBSOCKET_P_SESSION,sesstionId)
                      .setParam(SOCKETSERVER_R_IFRETURN,true);
            return returnMsg;
        }
        else
            return createMsg().setParam(WEBSOCKET_P_SESSION,sesstionId).setParam(SOCKETSERVER_R_IFRETURN,true);
    }

    protected void despatchMsgBySessionPool(String clientMsgid  ,TLMsg clientMsg, String channel, String sesstionId) {
        ArrayList<TLMsg> msgLists =msgTable.get(clientMsgid );
        if(msgLists==null || msgLists.isEmpty())
            return;
        TLBaseModule threadPool = (TLBaseModule) sessionPool.getModuleByIndex(channel);
        if(threadPool ==null)
            return  ;
        for(int i=0; i<msgLists.size();i++)
        {
            TLMsg rmsg =msgLists.get(i);
            TLMsg bmsg=createMsg().copyFrom(rmsg);
            bmsg.addArgs(clientMsg.getArgs());
            HashMap<String,Object> channelData =new HashMap<>();
            channelData.put(USERMANAGER_P_USERCHANNEL, channel);
            channelData.put(WEBSOCKET_P_SESSION,sesstionId);
            bmsg.setParam(TASKRESESSIONDATA,channelData);
            bmsg.setParam(TASKRESULTFOR,this);
            bmsg.setParam(TASKRESULTACTION,"threadReturn");
            TLMsg  threadMsg =createMsg().setAction(THREADPOOL_EXECUTE).setParam(DOWITHMAG,bmsg);
            putMsg(threadPool,threadMsg);
        }

    }

    protected TLMsg despatchMsg(TLMsg clientMsg) {
        return getMsg(this,clientMsg) ;
    }

}
