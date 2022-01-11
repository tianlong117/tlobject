package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.*;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Thread.sleep;

/**
 * 创建日期：2018/4/23 on 21:00
 * 描述:
 * 作者:tianlong
 */
/**
 模块池。数据库类似连接池。
 */

public   class TLModulePool extends TLBaseModule {
    protected boolean autoInit=true ;
    protected int  initNumbs=20;
    protected int  maxNumbs=100;
    protected int  waitTime=2000;
    protected String modueName;
    protected AtomicInteger nowNumbs = new AtomicInteger(0);
    protected ConcurrentLinkedQueue modulePool = new ConcurrentLinkedQueue();
    public TLModulePool(){
        super();
    }
    public TLModulePool(String name ){
        super(name);
    }
    public TLModulePool(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }
    @Override
    protected void initProperty(){
        super.initProperty();
        if(params!=null) {
            if ( params.get("autoInit") != null)
                autoInit = Boolean.parseBoolean(params.get("autoInit"));
            if ( params.get("initNumbs") != null)
                initNumbs = Integer.parseInt(params.get("initNumbs"));
            if (params.get("maxNumbs") != null)
                maxNumbs = Integer.parseInt(params.get("maxNumbs"));
            if ( params.get("waitTime") != null)
                waitTime = Integer.parseInt(params.get("waitTime"));
            if ( params.get("moduleName") != null)
                modueName = params.get("moduleName");
        }
    }
    @Override
    protected TLBaseModule init() {
        if(autoInit==false)
            return this ;
        makePool();
        return this ;
    }
    protected void makePool(){
        for(int i =0 ; i < initNumbs ;i++)
        {
            IObject module= makeModuleInPool();
            if(module!=null)
                poolAdd( module);
        }
    }
    protected  void poolAdd(IObject  module){
        modulePool.offer( module);
        nowNumbs.incrementAndGet();
        putLog("对象加入池",LogLevel.DEBUG,"pooladd");
    }
    protected   IObject makeModuleInPool() {
        if(modueName !=null && !modueName.isEmpty())
          return  (IObject)getNewModule(modueName,modueName);
        return null ;
    };

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case MODULEPOOL_MAKEPOOL:
                makePool();
                break;
            case MODULEPOOL_DOMSGUSEPOOL:
                 returnMsg =doMsgUsePool(fromWho,msg) ;
                break;
            case MODULEPOOL_GETMODULE:
                returnMsg= getModuleInPool(fromWho,msg);
                break;
            case MODULEPOOL_PUTMODULE:
                putModuleInPool(fromWho,msg);
                break;
            case MODULEPOOL_GETPOOLNUMB:
                returnMsg=getPoolNumb(fromWho,msg);
                break;
            default:
        }
        return returnMsg;
    }

    private TLMsg doMsgUsePool(Object fromWho, TLMsg msg) {
        TLMsg domsg = (TLMsg) msg.getParam(MSG);
        TLMsg returnMsg =getModuleInPool(fromWho,null);
        IObject module = (IObject) returnMsg.getParam(MODULEPOOL_P_MODULE);
        if(module !=null)
        {
            TLMsg doreturnMsg =putMsg(module,domsg);
            putModuleInPool(null,msg.setParam(MODULEPOOL_P_MODULE,module));
            return doreturnMsg ;
        }
        else
            return null ;
    }

    private TLMsg getPoolNumb(Object fromWho, TLMsg msg) {
        return createMsg().setParam("result",modulePool.size());
    }

    private void putModuleInPool(Object fromWho, TLMsg msg) {
        IObject  module= (IObject) msg.getParam(MODULEPOOL_P_MODULE);
        if(module !=null)
            modulePool.offer(module);
    }

    protected TLMsg getModuleInPool(Object fromWho, TLMsg msg) {
        IObject  module= (IObject) modulePool.poll();
        if(module ==null){
            if(nowNumbs.intValue() < maxNumbs)
            {
                nowNumbs.incrementAndGet();
                module= makeModuleInPool();
                putLog("创建新对象",LogLevel.DEBUG,"createModule");
            }
            else
            {
                try {
                    sleep(waitTime);
                    module= (IObject) modulePool.poll();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        return  createMsg().setParam(MODULEPOOL_P_MODULE,module) ;
    }
}
