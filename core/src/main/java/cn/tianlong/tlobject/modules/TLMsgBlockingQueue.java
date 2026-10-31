package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
    protected volatile boolean running = true;
    /** autoTask 的 worker 线程：destroy 时主动 interrupt，否则会永久挂在 poll/take 上 */
    protected final CopyOnWriteArrayList<Thread> workers = new CopyOnWriteArrayList<>();
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
                    .setSystemParam(INTHREADPOOL,true).setSystemParam(THREADPOOLNAME,threadPoolName);
            putMsg(this,msg) ;
        }
        return this ;
    }

    @Override
    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        running = false;
        // 主动唤醒阻塞在 poll/take 的 worker（共享线程池不一定会替本模块中断它们）
        for (Thread t : workers) t.interrupt();
        return super.destroy(fromWho, msg);
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
                returnMsg= getMsgInQueue(fromWho,msg);
                break;
            case "autoTask":
                autoTask(fromWho,msg);
                break;
            default:
        }
        return returnMsg;
    }

    private TLMsg beforeModuleAction(Object fromWho, TLMsg msg) {
        TLMsg qmsg = (TLMsg) msg.getSystemParam(DOWITHMSG);
        // DOWITHMSG 只在 doMsgList 的默认分支注入；直接发 action 或钩子用 useMsg="true" 时为 null。
        // 此时没有"被拦截的原始消息"，放行后续动作（返回 null = 不改写返回值、不阻断）。
        if (qmsg == null) {
            putLog("beforeModuleAction: missing DOWITHMSG, skip", LogLevel.WARN, "beforeModuleAction");
            return null;
        }
        addMsgInQueue( qmsg.setDestination(msg.getPrevious()),true);
        return createMsg().setParam(MODULE_DONEXTMSG ,false);
    }

    private TLMsg getMsgInQueue(Object fromWho, TLMsg msg) {
        // 与 autoTask 同款：带超时 poll 周期检查 running，destroy 后调用方不会被 take 永久挂住
        while (running) {
            TLMsg qmsg;
            try {
                qmsg = msgQueue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (qmsg != null) {
                putLog("take a queue", LogLevel.DEBUG, "take");
                return qmsg;
            }
        }
        return null;
    }

    private void autoTask(Object fromWho, TLMsg msg) {
        workers.add(Thread.currentThread());
        try {
            while (running){
                TLMsg qmsg;
                try {
                    // 带超时 poll：周期醒来检查 running，避免 destroy 后永久卡在 take()
                    qmsg = msgQueue.poll(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    putLog("autoTask interrupted",LogLevel.WARN,"task");
                    break;
                }
                if (qmsg == null) continue;
                putLog("take a queue",LogLevel.DEBUG,"task");
                // 必须走 systemArgs：TLBaseModule.getMsg 用 getSystemParam(IGNOREBEFORE) 判定，
                // 写成 setParam 会无效，before 钩子会把消息再次入队（无限重入）
                putMsg(qmsg.getDestination(),qmsg.setSystemParam(IGNOREBEFORE,true)) ;
            }
        } finally {
            workers.remove(Thread.currentThread());
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
