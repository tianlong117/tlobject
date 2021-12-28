package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 创建日期：2018/4/23 on 21:00
 * 描述:
 * 作者:tianlong
 */

public class TLMsgBroadCast extends TLBaseModule {
    protected String threadPool ;
    protected ConcurrentHashMap<String,ArrayList<TLMsg>> receivers= new ConcurrentHashMap<>();
    protected ConcurrentHashMap<String,TLMsg> lastBroadcastMsg = new ConcurrentHashMap <>();
    public TLMsgBroadCast(String name ){
        super(name);
    }
    public TLMsgBroadCast(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }
    @Override
    protected void initProperty(){
        super.initProperty();
        if(params!=null )
        {
           if(params.get("threadPool") !=null)
               threadPool = params.get("threadPool") ;
        }
    }
    @Override
    protected TLBaseModule init() {
        if(msgTable !=null && !msgTable.isEmpty())
             receivers.putAll(msgTable);
        return  this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case MSGBROADCAST_REGISTRECEIVER:
                returnMsg =registReceiver( fromWho,  msg);
                break;
            case MSGBROADCAST_UNREGISTRECEIVER:
                unregistReceiver( fromWho,  msg);
                break;
            case MSGBROADCAST_BROADCAST:
                broadcast( fromWho,  msg);
                break;
            default:
                   ;
        }
        return returnMsg;
    }

    private synchronized void unregistReceiver(Object fromWho, TLMsg msg) {
        String receiver = (String) msg.getParam(MSGBROADCAST_P_RECEIVER);
        if(receiver ==null)
            receiver=((IObject)fromWho).getName();
        modules.remove(receiver);
        String messageType = (String) msg.getParam(MSGBROADCAST_P_MESSAGETYPE);
        if(messageType !=null){
            ArrayList<TLMsg> msgList =receivers.get(messageType);
            if(msgList==null || msgList.isEmpty())
                return;
            removeReceiver( receiver,  msgList );
            return;
        }
        for(ArrayList<TLMsg> msgList :receivers.values()){
            removeReceiver( receiver,  msgList );
        }
    }
    private void  removeReceiver(String receiver, ArrayList<TLMsg> msgList ){
        for(int i=0; i<msgList.size();i++)
        {
            TLMsg rmsg =msgList.get(i);
            String des =rmsg.getDestination();
            if(receiver.equals(des))
            {
                msgList.remove(i) ;
                return;
            }
        }
    }
    private void broadcast(Object fromWho, TLMsg msg) {
        String messageType = (String) msg.getParam(MSGBROADCAST_P_MESSAGETYPE);
        if(messageType ==null)
            return;
        lastBroadcastMsg.put(messageType,createMsg().copyFrom(msg)) ;
        ArrayList<TLMsg> msgLists =receivers.get(messageType);
        if(msgLists==null || msgLists.isEmpty())
            return;
        for(int i=0; i<msgLists.size();i++){
            TLMsg rmsg =msgLists.get(i);
            TLMsg bmsg=createMsg().copyFrom(rmsg);
            bmsg.addArgs(msg.getArgs());
            bmsg.setWaitFlag(false);
            putLog("broadCastMsg broadcasting, receiver:" + bmsg.getDestination()+ "  messageType:" + messageType , LogLevel.DEBUG, "broadcast");
            if(threadPool !=null)
                bmsg.setParam(INTHREADPOOL,true).setParam(THREADPOOLNAME,threadPool) ;
            putMsg(this,bmsg);
        }
    }

    protected synchronized TLMsg registReceiver(Object fromWho, TLMsg msg) {
        String messageType = (String) msg.getParam(MSGBROADCAST_P_MESSAGETYPE);
        TLMsg receiverMsg ;
        String receiver ;
        if(!msg.isNull(MSGBROADCAST_P_RECEIVEACTION))
        {
            String receiveAction = (String) msg.getParam(MSGBROADCAST_P_RECEIVEACTION);
            IObject receiverObj ;
            if(msg.isNull(MSGBROADCAST_P_RECEIVER))
            {
                receiverObj  = (IObject) fromWho;
                receiver= receiverObj.getName();
            }
            else {
                if(msg.getParam(MSGBROADCAST_P_RECEIVER) instanceof  String){
                    receiver = (String) msg.getParam(MSGBROADCAST_P_RECEIVER);
                    receiverObj  = (IObject) fromWho;
                }
                else if(msg.getParam(MSGBROADCAST_P_RECEIVER) instanceof  IObject){
                    receiverObj = (IObject) msg.getParam(MSGBROADCAST_P_RECEIVER);
                    receiver= receiverObj.getName();
                }
                else
                    return createMsg().setParam(RESULT,false);
            }
            modules.put(receiver,receiverObj);
            receiverMsg =createMsg().setDestination(receiver).setAction(receiveAction);
        }
        else
        {
            receiverMsg= (TLMsg) msg.getParam(MSGBROADCAST_P_RECEIVEMSG);
            if(receiverMsg ==null)
                return createMsg().setParam(RESULT,false);
            receiver =receiverMsg.getDestination();
            modules.remove(receiver);
        }
        ArrayList<TLMsg> msgList =receivers.get(messageType);
        if(msgList==null)
        {
            msgList =new ArrayList<>();
            receivers.put(messageType,msgList);
        }
        else {
            for(TLMsg bmsg : msgList)
            {
                String des =bmsg.getDestination();
                if(receiver.equals(des))          //检查是否已经注册过
                    return createMsg().setParam(RESULT,false);
            }
        }
        msgList.add(receiverMsg);
        putLog("broadCastMsg regist, receiver:" + receiver + "  messageType:" + messageType , LogLevel.DEBUG, "registReceiver");
        TLMsg mmsg = lastBroadcastMsg.get(messageType);
        if(mmsg ==null)
            return createMsg().setParam(RESULT,true);
        else
        {
            TLMsg bmsg=createMsg().copyFrom(receiverMsg);
            bmsg.addArgs(mmsg.getArgs());
            putMsg(this,bmsg);
            return mmsg ;
        }
    }
}
