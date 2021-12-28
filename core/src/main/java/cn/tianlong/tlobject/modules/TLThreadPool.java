package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 创建日期：2018/4/23 on 21:00
 * 描述:
 * 作者:tianlong
 */

public class TLThreadPool extends TLBaseModule {
    protected ExecutorService threadPool ;
    protected int poolSize = 5;
    protected String poolType ="fixed";
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
                threadPool.shutdown();
                break;
            case MODULE_DESTROY:
                threadPool.shutdown();
                break;
            default:              ;
        }
        return returnMsg;
    }

    protected TLMsg execute(Object fromWho, TLMsg msg) {
        TLMsg  dmsg = (TLMsg) msg.getParam(DOWITHMAG);
        IObject toWho = (IObject) msg.getParam(THREADPOOL_P_TASKMODULE);
        if(toWho ==null )
        {
           String destination =dmsg.getDestination();
            if(destination !=null)
                toWho= (IObject) getModule((String) destination);
            else
                return null ;
        }
        ThreadTask threadTask = new ThreadTask(toWho,dmsg, (IObject) fromWho);
        putLog("start thread task,source:"+((IObject) fromWho).getName()+" action:"+dmsg.getAction(),LogLevel.DEBUG);
        threadPool.execute(threadTask);
        return createMsg().setParam(THREADPOOL_TASK,threadTask);
    }
    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        threadPool.shutdown();
       return  super.destroy(fromWho,msg);
    }
}
