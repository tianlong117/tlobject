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
            if (params.get("userManagerModule") != null)
                userManagerModule = params.get("userManagerModule");
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

    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg)
    {
        TLMsg returnMsg = null;
        String action = msg.getAction();
        switch (action) {
            case "fromClient":
                returnMsg = fromClient(fromWho, msg);
                break;
            case USERMANAGER_GETUSERBYCHANNEL:
                returnMsg = putMsg(userManagerModule,msg);
                break;
            case WEBSOCKET_PUT:
                returnMsg = putoClient(fromWho, msg);
                break;
            case WEBSOCKET_PUTMSG:
                returnMsg = putMsgToClient(fromWho, msg);
                break;
            case WEBSOCKET_PUTANDWAIT:
                returnMsg = putToClientAndWait(fromWho, msg);
                break;
            case WEBSOCKET_SENDFILE:
                returnMsg = sendFile(fromWho, msg);
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

    protected TLMsg fromClient(Object fromWho, TLMsg msg) {
        TLMsg clientMsg = (TLMsg) msg.getParam(USERMANAGER_P_CLIENTMSG);
        if(clientMsg ==null)
            return null ;
        if(!clientMsg.systemParamIsNull(WEBSOCKET_P_NOTIFYID))
        {
            String notifyId =(String) clientMsg.getSystemParam(WEBSOCKET_P_NOTIFYID);
            netSession.saveSesstiondata(notifyId,clientMsg);
            return null;
        }
        String clientMsgid =clientMsg.getMsgId();
        if(clientMsgid !=null && msgidInPool !=null
                && (msgidInPool.contains("*") || msgidInPool.contains(clientMsgid)))
        {
            despatchMsgBySessionPool(clientMsgid ,clientMsg);
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
        if(destination !=null){
           despatchMsgBySessionPool( clientMsg);
           return null ;
        }
        String sesstionId = (String) clientMsg.getSystemParam(WEBSOCKET_P_SESSION);
        String  arrivedMsgid = (String) clientMsg.getSystemParam(WEBSOCKET_P_SESSIONARRIVEDMSGID);
        TLMsg returnMsg = despatchMsg(clientMsg) ;
        if(sesstionId !=null)
        {
            returnMsg = TLMsgUtils.addSystemArgToMsg(WEBSOCKET_P_SESSION,sesstionId,returnMsg);
            if(arrivedMsgid !=null)
                returnMsg = TLMsgUtils.addSystemArgToMsg(WEBSOCKET_P_SESSIONARRIVEDMSGID, arrivedMsgid,returnMsg);
            returnMsg.setSystemParam(SOCKETSERVER_R_IFRETURN,true);
        }
        return  returnMsg ;
    }

    private void despatchMsgBySessionPool(TLMsg clientMsg) {
        HashMap<String,Object> clientSystemArgs = (HashMap<String, Object>) clientMsg.getSystemArgs();
        String channel = (String) clientSystemArgs.get(USERMANAGER_P_USERCHANNEL);
        TLBaseModule threadPool = (TLBaseModule) sessionPool.getModuleByIndex(channel);
        if(threadPool ==null)
            return  ;
        clientMsg.setSystemParam(TASKRESESSIONDATA,clientSystemArgs);
        clientMsg.setSystemParam(TASKRESULTFOR,this);
        clientMsg.setSystemParam(TASKRESULTACTION,"threadReturn");
        TLMsg  threadMsg =createMsg().setAction(THREADPOOL_EXECUTE).setParam(THREADPOOL_P_TASKMSG,clientMsg);
        putMsg(threadPool,threadMsg);
    }

    protected void despatchMsgBySessionPool(String clientMsgid  ,TLMsg clientMsg) {
        ArrayList<TLMsg> msgLists =msgTable.get(clientMsgid );
        if(msgLists==null || msgLists.isEmpty())
            return;
        HashMap<String,Object> clientSystemArgs = (HashMap<String, Object>) clientMsg.getSystemArgs();
        String channel = (String) clientSystemArgs.get(USERMANAGER_P_USERCHANNEL);
        TLBaseModule threadPool = (TLBaseModule) sessionPool.getModuleByIndex(channel);
        if(threadPool ==null)
            return  ;
        for(int i=0; i<msgLists.size();i++)
        {
            TLMsg rmsg =msgLists.get(i);
            TLMsg bmsg=createMsg().copyFrom(rmsg);
            bmsg.addArgs(clientMsg.getArgs());
            bmsg.setSystemParam(TASKRESESSIONDATA,clientSystemArgs);
            bmsg.setSystemParam(TASKRESULTFOR,this);
            bmsg.setSystemParam(TASKRESULTACTION,"threadReturn");
            TLMsg  threadMsg =createMsg().setAction(THREADPOOL_EXECUTE).setParam(THREADPOOL_P_TASKMSG,bmsg);
            putMsg(threadPool,threadMsg);
        }

    }

    protected TLMsg despatchMsg(TLMsg clientMsg) {
        return getMsg(this,clientMsg) ;
    }

    protected TLMsg putoClient(Object fromWho, TLMsg msg) {
        return putMsg(userManagerModule, msg.setAction(USERMANAGER_PUTTOUSER));
    }

    protected TLMsg putMsgToClient(Object fromWho, TLMsg msg) {
        TLMsg umsg = createMsg().setAction(USERMANAGER_PUTTOUSER)
                .setSystemParam(USERMANAGER_P_USERCHANNEL,msg.getSystemParam(USERMANAGER_P_USERCHANNEL))
                .setSystemParam(USERMANAGER_P_USERID,msg.getSystemParam(USERMANAGER_P_USERID))
               .setParam(WEBSOCKET_P_CONTENT, msg.getArgs());
        return putMsg(userManagerModule, umsg);
    }

    protected TLMsg putToClientAndWait(Object fromWho, TLMsg msg) {
        Object channel =msg.getSystemParam(USERMANAGER_P_USERCHANNEL);
        if(channel ==null)
        {
            TLMsg  userChannelsMsg =putMsg(userManagerModule,msg.setAction(USERMANAGER_GETUSERCHANNELS));
            channel =userChannelsMsg.getParam(USERMANAGER_R_USERCHANNEL) ;
            if( channel ==null)
                return createMsg().setParam(RESULT,false);

        }
        int waitTime = (int) msg.getSystemParam(NETSESSION_P_WAITTIME,this.waitTime);
        int retryTimes = (int) msg.getSystemParam(NETSESSION_P_RETRYTIMES,0);
        String sessionId = netSession.makeSessionId();
        Map<String,Object> systemArgs = (Map<String, Object>)msg.getParam(MSG_P_SYSTEMARGS);
        if(systemArgs ==null)
        {
            systemArgs =new HashMap<>();
            msg.setParam(MSG_P_SYSTEMARGS,systemArgs);
        }
        systemArgs.put(WEBSOCKET_P_NOTIFYID,sessionId);
        TLMsg cmsg=createMsg().setAction(USERMANAGER_PUTTOUSER)
                .setSystemParam(USERMANAGER_P_USERCHANNEL,channel)
                .setParam(WEBSOCKET_P_CONTENT, msg.getArgs());
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

    protected TLMsg sendFile(Object fromWho, TLMsg msg) {
        IObject  sendModule = (IObject) getModule(userManagerModule);
        return TLBaseWebSocketSendFile.sendFile(this,msg,netSession,sendModule);
    }

    protected TLMsg sendBinary(Object fromWho, TLMsg msg) {
        return putMsg(userManagerModule,msg.setDestination(null));
    }

    protected TLMsg receiveBinary(Object fromWho, TLMsg msg) {
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

    protected TLMsg sendFileError(Object fromWho, TLMsg msg) {
        return putMsg(userManagerModule,msg.setAction(USERMANAGER_SENDFILEERROR));
    }

    protected void receiveFileOver(TLMsg msg) {
        if(msg.isNull(WEBSOCKET_P_BINARYSESSION) && !(msg.getParam(WEBSOCKET_P_BINARYSESSION) instanceof  Integer ))
            return ;
        int sessionId = (int) msg.getParam(WEBSOCKET_P_BINARYSESSION);
        netSession.saveSesstiondata( String.valueOf(sessionId),msg);
        return ;
    }

    protected void onUserLogout(Object fromWho, TLMsg msg) {
        String channel =  msg.getStringParam(USERMANAGER_P_USERCHANNEL,null);
        if(channel ==null)
            return;
        sessionPool.removeUser(channel);
    }

    protected void threadReturn(Object fromWho, TLMsg msg) {
        sessionPool.useModuleOver();
        HashMap<String,Object> clientSystemArgs = (HashMap<String, Object>) msg.getSystemParam(TASKRESESSIONDATA,null);
        if(clientSystemArgs ==null)
            return;
        String sesstionId = (String) clientSystemArgs.get(WEBSOCKET_P_SESSION);
        if(sesstionId !=null)
        {
            msg = TLMsgUtils.addSystemArgToMsg(WEBSOCKET_P_SESSION,sesstionId,msg);
            String  arrivedMsgid = (String) clientSystemArgs.get(WEBSOCKET_P_SESSIONARRIVEDMSGID);
            if(arrivedMsgid !=null)
                msg = TLMsgUtils.addSystemArgToMsg(WEBSOCKET_P_SESSIONARRIVEDMSGID, arrivedMsgid,msg);
        }
        if(sesstionId !=null
                || (msg !=null && ((Boolean)msg.getSystemParam(SOCKETSERVER_R_IFRETURN,false))==true))
            putMsgToClient(this, msg.addSystemArgs(clientSystemArgs) );
    }


}
