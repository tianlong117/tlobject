package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.concurrent.LinkedBlockingQueue;

import static java.lang.Thread.sleep;

/**
 * 创建日期：2018/4/23 on 21:00
 * 描述:
 * 作者:tianlong
 */
/**
 消息阻塞序列
 */

public   class TLMsgBlockingQueue extends TLBaseModule {
    protected String threadPoolName =DEFAULTTHREADPOOL;
    protected int  maxNumbs=100;
    protected int  taskNumbs=10;
    protected LinkedBlockingQueue<TLMsg> msgQueue ;
    public TLMsgBlockingQueue(){
        super();
    }
    public TLMsgBlockingQueue(String name ){
        super(name);
    }
    public TLMsgBlockingQueue(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }
    @Override
    protected void initProperty(){
        super.initProperty();
        if(params!=null) {
            if (params.get("maxNumbs") != null)
                maxNumbs = Integer.parseInt(params.get("maxNumbs"));
            if ( params.get("taskNumbs") != null)
                taskNumbs = Integer.parseInt(params.get("taskNumbs"));
            if ( params.get("threadPoolName") != null)
                threadPoolName = params.get("threadPoolName");
        }
    }
    @Override
    protected TLBaseModule init() {
        msgQueue = new LinkedBlockingQueue<TLMsg>(maxNumbs) ;
        for (int i =0 ;i < taskNumbs ;i ++)
        {
            TLMsg msg =createMsg().setAction("autoTask").setWaitFlag(false)
                    .setParam(INTHREADPOOL,true).setParam(THREADPOOLNAME,threadPoolName);
            putMsg(this,msg) ;
        }
        return this ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case "beforeModuleAction":
                return beforeModuleAction(fromWho,msg);
            case "addMsgInQueue":
                returnMsg= addMsgInQueue(fromWho, msg);
                break;
            case "getMsgInQueue":
                getMsgInQueue(fromWho,msg);
                break;
            case "autoTask":
                autoTask(fromWho,msg);
                break;
            default:
        }
        return returnMsg;
    }

    private TLMsg beforeModuleAction(Object fromWho, TLMsg msg) {
        TLMsg qmsg = (TLMsg) msg.getParam(DOWITHMAG);
        addMsgInQueue( qmsg.setDestination(msg.getPrevious()),true);
        return createMsg().setParam(MODULE_DONEXTMSG ,false);
    }

    private TLMsg getMsgInQueue(Object fromWho, TLMsg msg) {
        try {
            TLMsg qmsg =msgQueue.take();
            putLog("take a queue",LogLevel.DEBUG,"take");
            return  qmsg;
        } catch (InterruptedException e) {
            e.printStackTrace();
            return null ;
        }
    }

    private void autoTask(Object fromWho, TLMsg msg) {
        while (true){
            try {
                TLMsg qmsg =msgQueue.take();
                putLog("take a queue",LogLevel.DEBUG,"task");
                putMsg(qmsg.getDestination(),qmsg.setParam(IGNOREBEFORE,true)) ;
            } catch (InterruptedException e) {
                e.printStackTrace();
                putLog("take a queue error",LogLevel.WARN,"task");
            }
        }
    }
    private TLMsg addMsgInQueue(Object fromWho, TLMsg msg) {
        TLMsg domsg=(TLMsg) msg.getParam(MSG);
        Boolean ifWait =msg.parseBoolean("ifWait",true) ;
        return addMsgInQueue(domsg,ifWait) ;
    }
    private TLMsg addMsgInQueue( TLMsg domsg, Boolean ifWait)
    {
        if(ifWait)
        {
            try {
                msgQueue.put(domsg);
                putLog("add a queue",LogLevel.DEBUG,"add");
                return  createMsg().setParam(RESULT,true) ;
            } catch (InterruptedException e) {
                // e.printStackTrace();
                putLog("add a queue faile",LogLevel.ERROR,"add");
                return  createMsg().setParam(RESULT,false) ;
            }
        }
        else {
            Boolean result =msgQueue.offer(domsg);
            return  createMsg().setParam(RESULT,result) ;
        }
    }
}
