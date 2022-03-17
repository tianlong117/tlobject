package cn.tianlong.tlobject.network.common;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLMsgUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.Thread.sleep;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLNetSession extends TLBaseModule {
    protected int  waitTime=20000 ;
    protected int  initialCapacity=3200 ;
    protected String onFailMsgid ;
    protected String clientIp ;
    protected static Map<Integer, Long> sessionsId ;
    protected static Map<String, TLMsg> msgSessions ;
    protected static Map<String, Thread> threads ;

    public TLNetSession() {
        super();
    }

    public TLNetSession(String name) {
        super(name);
    }

    public TLNetSession(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
        String uuid = UUID.randomUUID().toString();
        uuid = uuid.replace("-", "");
        this.name =this.name+"_"+uuid;
    }

    @Override
    protected void  setModuleParams(){
        if(params !=null && params.get("initialCapacity")!=null) {
            initialCapacity=Integer.parseInt(params.get("initialCapacity"));
        }
        if(params !=null && params.get("clientIp")!=null) {
            clientIp=params.get("clientIp");
        }
        if(params !=null && params.get("waitTime")!=null) {
            waitTime=Integer.parseInt(params.get("waitTime"));
        }
        if(params !=null && params.get("onFailMsgid")!=null) {
            onFailMsgid=params.get("onFailMsgid");
        }
    }

    @Override
    protected TLBaseModule init() {
        if(clientIp ==null || clientIp .isEmpty())
        {
            InetAddress addr = null;
            try {
                addr = InetAddress.getLocalHost();
                clientIp =addr.getHostAddress() ;
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }
        }
        sessionsId = new ConcurrentHashMap<>(initialCapacity);
        msgSessions = new ConcurrentHashMap<>(initialCapacity);
        threads = new ConcurrentHashMap<>(initialCapacity);
        return this ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
         return null;
    }

    public String makeSessionId() {
      return name+"_"+Thread.currentThread().getId()+"_"+System.currentTimeMillis() ;
    }
    public synchronized int makeBinSessionId( ) {
       int start =1;
       int end =Integer.MAX_VALUE;
       int id ;
       do {
           id = (int)(Math.random() * (end-start+1) + start);
       }while (sessionsId.containsKey(id));
       sessionsId.put(id,System.currentTimeMillis());
       return id ;
   }
    public  synchronized void removeBinSessionId(int id ) {
        sessionsId.remove(id);
    }
    public TLMsg waitServerReturnUntilTimeOut(String sessionId,IObject agent,TLMsg agentMsg ,int waitTime,int retryTimes){
        if(waitTime ==0)
            waitTime =this.waitTime ;
        threads.put(sessionId,Thread.currentThread());
        boolean ifWait  ;
         do {
             try {
                 ifWait =false;
                 sleep(waitTime);
                 if(retryTimes >0)
                 {
                    ifWait =true ;
                     retryTimes =retryTimes -1;
                     TLMsg resultMsg=putMsg(agent,agentMsg);
                     Boolean result =  resultMsg.parseBoolean(RESULT,false);
                     if(result ==false)
                        return  onWaitFail(sessionId,agentMsg) ;
                 }
                 else
                     return  onWaitFail(sessionId,agentMsg) ;
             } catch (InterruptedException e) {
                 return returnServerMsg( sessionId);
             }
         }while (ifWait);
         return  onWaitFail(sessionId,agentMsg) ;
     }
    public TLMsg waitUntilServerNoReturn(String sessionId,int waitTime){
        if(waitTime ==0)
            waitTime =this.waitTime ;
        threads.put(sessionId,Thread.currentThread());
        boolean ifWait =true ;
        do {
            try {
                sleep(waitTime);
                ifWait =false;
            } catch (InterruptedException e) {
                ifWait =true ;
            }
        }while (ifWait);
        return returnServerMsg( sessionId);
    }
    public boolean containsSessionId (String sessionId){
        return  threads.containsKey(sessionId);
    }
    public void saveSessionId(String sessionId) {
        threads.put(sessionId,Thread.currentThread());
    }
    public void removeSessionId(String sessionId) {
        threads.remove(sessionId);
    }
    private TLMsg onWaitFail(String sessionId,TLMsg serverMsg) {
        if(onFailMsgid !=null)
        {
            TLMsg msg =createMsg().setMsgId(onFailMsgid).setParam(DOWITHMSG,serverMsg);
            getMsg(this,msg);
        }
        threads.remove(sessionId) ;
        String content = null;
        if(serverMsg !=null)
           content =TLMsgUtils.msgToStr(serverMsg);
        putLog("wait fail,content:"+content,LogLevel.ERROR,"onWaitFail");
        return createMsg().setParam(RESULT,false);
    }

    public TLMsg  returnServerMsg(String sessionId){
        if(msgSessions.containsKey(sessionId))
        {
            threads.remove(sessionId) ;
            TLMsg returnMsg =msgSessions.get(sessionId);
            msgSessions.remove(sessionId) ;
            return returnMsg;
        }
        return null ;
    }
    public  void saveSesstiondata(String sessionId,TLMsg msg){
          Thread thread =threads.get(sessionId);
          if(thread ==null)
              return;
          msgSessions.put(sessionId,msg);
          thread.interrupt();
          return;
      }
}
