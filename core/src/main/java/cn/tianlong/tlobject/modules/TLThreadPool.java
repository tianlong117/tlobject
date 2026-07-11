package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 创建日期：2018/4/23 on 21:00
 * 描述:
 * 作者:tianlong
 */

/**
 * 线程池模块，对于设置异步标志的消息，如果在参数中设置了使用线程池，则消息在线程池的线程中执行。
 */
public class TLThreadPool extends TLBaseModule {
    protected ExecutorService threadPool ;
    protected int poolSize = 5;
    protected String poolType ="fixed";
    protected int shutdownTimeout = 30; // 关闭时等待线程完成的超时秒数
    public TLThreadPool(){
        super();
    }
    public TLThreadPool(String name ){
        super(name);
    }
    public TLThreadPool(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected void setModuleParams() {
        if (params != null && params.get(THREADPOOL_P_POOLSIZE) != null)
            poolSize = Integer.parseInt(params.get(THREADPOOL_P_POOLSIZE));
        if (params != null && params.get(THREADPOOL_P_POOLTYPE) != null)
            poolType = params.get(THREADPOOL_P_POOLTYPE);
        if (params != null && params.get("shutdownTimeout") != null)
            shutdownTimeout = Integer.parseInt(params.get("shutdownTimeout"));
    }
    @Override
    protected TLBaseModule init() {
        switch (poolType) {
            case "fixed":
                threadPool = Executors.newFixedThreadPool(poolSize);
                break;
            case "cached":
                threadPool = Executors.newCachedThreadPool();
                break;
            case "single":
                threadPool = Executors.newSingleThreadExecutor();
                break;
            default:
                threadPool = Executors.newFixedThreadPool(poolSize);
        }
        return this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case THREADPOOL_EXECUTE:
                returnMsg=execute( fromWho,  msg);
                break;
            case THREADPOOL_GETPOOL:
                returnMsg=createMsg().setParam(THREADPOOL_POOL,threadPool);
                break;
            case THREADPOOL_SHUTDOWN:
                shutdownAndAwait();
                break;
            case MODULE_DESTROY:
                shutdownAndAwait();
                break;
            default:              ;
        }
        return returnMsg;
    }

    protected TLMsg execute(Object fromWho, TLMsg msg) {
        TLMsg  dmsg = (TLMsg) msg.getParam(THREADPOOL_P_TASKMSG,TLMsg.class);
        IObject toWho = (IObject) msg.getParam(THREADPOOL_P_TASKMODULE,IObject.class);
        if(toWho ==null )
        {
           String destination =dmsg.getDestination();
            if(destination !=null && !destination.isEmpty())
                toWho= (IObject) getModule(destination);
            else
                return null ;
        }
        if(toWho ==null)
            return null ;
        ThreadTask threadTask = new ThreadTask(toWho,dmsg, (IObject) fromWho);
        putLog("start thread task,source:"+((IObject) fromWho).getName()+" action:"+dmsg.getAction(),LogLevel.DEBUG);
        threadPool.execute(threadTask);
        return createMsg().setParam(THREADPOOL_TASK,threadTask);
    }
    private void shutdownAndAwait() {
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(shutdownTimeout, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
                if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    putLog("thread pool did not terminate", LogLevel.WARN);
                }
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        threadPool.shutdown();
       return  super.destroy(fromWho,msg);
    }
}
