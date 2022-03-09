package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Thread.sleep;

/**
 * 创建日期：2018/4/23 on 21:00
 * 描述:
 * 作者:tianlong
 */

/**
 * 可重用模块池，对应每个请求模块者分配固定的池中模块对象。
 */
public   class TLReUsedModulePool extends TLBaseModule {
    protected boolean autoInit=true ;
    protected int  moduleNameIndex=0;
    protected int initModuleNumbs =20;
    protected int  maxModuleNumbs=200;
    protected int  waitPoolTime=5000;
    protected int  maxUserNumber =0;
    protected String modueInPool;
    protected AtomicInteger nowModuePoolIndex = new AtomicInteger(0);
    protected AtomicInteger nowUseingModulesSize = new AtomicInteger(0);
    protected int nowUserNumber;
    protected CopyOnWriteArrayList<Object> modulePool = new CopyOnWriteArrayList<>() ;
    protected ConcurrentHashMap<String, Object> useingModules = new ConcurrentHashMap<>();
    public TLReUsedModulePool(){
        super();
    }
    public TLReUsedModulePool(String name ){
        super(name);
    }
    public TLReUsedModulePool(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }
    @Override
    protected void setModuleParams() {
        if(params!=null)
            setPoolParam(params);
    }
    private void  setPoolParam(HashMap<String,String> params) {
        if ( params.get("autoInit") != null)
            autoInit = Boolean.parseBoolean(params.get("autoInit"));
        if ( params.get("initModuleNumbs") != null)
            initModuleNumbs = Integer.parseInt(params.get("initModuleNumbs"));
        if (params.get("maxModuleNumbs") != null)
            maxModuleNumbs = Integer.parseInt(params.get("maxModuleNumbs"));
        if ( params.get("waitPoolTime") != null)
            waitPoolTime = Integer.parseInt(params.get("waitPoolTime"));
        if ( params.get("maxUserNumber") != null)
            maxUserNumber = Integer.parseInt(params.get("maxUserNumber"));
        if ( params.get("modueInPool") != null)
            modueInPool = params.get("modueInPool");
    }

    @Override
    protected TLBaseModule init() {
        if(autoInit==false)
            return this ;
        if(modulePool !=null && !modulePool.isEmpty())
             makePool(initModuleNumbs, modueInPool);
        return this ;
    }
    protected synchronized void makePool(int moduleNumber,String modueName){
        for(int i =0 ; i < moduleNumber ;i++)
        {
            Object module=getNewModule(modueName+moduleNameIndex,modueName);
            if(module!=null)
            {
                modulePool.add( module);
                moduleNameIndex ++ ;
            }
             else
            {
                putLog("模块池创建失败,模块名："+modueName,LogLevel.ERROR,"getModuleInPool");
                return;
            }
        }
        putLog("模块池创建新模块，,模块名："+modueName+" 创建数量:"+moduleNumber,LogLevel.DEBUG,"getModuleInPool");
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case MODULEPOOL_MAKEPOOL:
                returnMsg=makePool(fromWho,msg);
                break;
            case MODULEPOOL_GETMODULE:
                returnMsg= getModuleInPool(fromWho,msg);
                break;
            case MODULEPOOL_REMOVEUSER:
                returnMsg= removeUser(fromWho,msg);
                break;
            case MODULEPOOL_GETPOOLNUMB:
                returnMsg=getPoolNumb(fromWho,msg);
                break;
            default:
        }
        return returnMsg;
    }
    public  void shutdownModuleByName(String name ){
        for(String  key: useingModules.keySet())
        {
            Object  module =useingModules.get( key);
            String moduleName =((IObject)module).getName();
            if(moduleName.equals(name))
                useingModules.remove(key);
        }
        for (Object module : modulePool){
            String moduleName =((IObject)module).getName();
            if(moduleName.equals(name))
            {
                shutdownModule(module) ;
                break;
            }
        }
    }
    public  void shutdownModuleByUser(String user ){
        Object  module =useingModules.get(user);
        if(module !=null)
        {
            useingModules.remove(user);
            shutdownModule(module) ;
        }
    }
    protected  void shutdownModule(Object module){
        putMsg((IObject)module,createMsg().setAction(MODULE_DESTROY));
        modulePool.remove(module);

    }
    public boolean removeUser(String user){
        if(useingModules.containsKey(user))
        {
            useingModules.remove(user);
            putLog("模块放弃使用，user:"+user,LogLevel.DEBUG,"getModuleInPool");
            return true ;
        }
        else
            return false ;
    }
    private TLMsg removeUser(Object fromWho, TLMsg msg) {
        String  user= (String) msg.getParam(MODULEPOOL_P_MODULEUSER);
        if(user==null || user.isEmpty())
            return createMsg().setParam(RESULT,false);
        if(removeUser( user))
            return createMsg().setParam(RESULT,true);
        else
            return createMsg().setParam(RESULT,false);
    }

    protected TLMsg makePool(Object fromWho, TLMsg msg) {
        if(!modulePool.isEmpty())
            return createMsg().setParam(RESULT,false) ;
        modueInPool = (String) msg.getParam(MODULENAME);
        setPoolParam(msg.getArgs());
        makePool(initModuleNumbs, modueInPool);
        return createMsg().setParam(RESULT,true) ;
    }

    private TLMsg getPoolNumb(Object fromWho, TLMsg msg) {
        return createMsg().setParam("result",modulePool.size());
    }

    protected TLMsg getModuleInPool(Object fromWho, TLMsg msg) {
        String  user= (String) msg.getParam(MODULEPOOL_P_MODULEUSER);
        if(user==null || user.isEmpty())
            return createMsg().setParam(RESULT,false);
        Object module =getModuleInPool(user);
        if(module!=null)
           return  createMsg().setParam(MODULEPOOL_P_MODULE,module) ;
        return createMsg().setParam(RESULT,false);
    }
    public Object  getModuleByIndex(String user){
        if(ifReachMaxUser())
            return null;
        int hashCode = user.hashCode() & Integer.MAX_VALUE;   // 防止hashcode负值
        int moduleNumber = modulePool.size();
        if(moduleNumber ==0)
            return null ;
        int serverPos = hashCode % moduleNumber;
        nowUserNumber= nowUseingModulesSize.incrementAndGet();
        putLog("模块使用，user:"+user+".当前使用数量:" + nowUserNumber,LogLevel.DEBUG,"getModuleByIndex");
        return modulePool.get(serverPos);
    }
    public void useModuleOver(){
        nowUserNumber= nowUseingModulesSize.decrementAndGet();
        putLog("回收模块，当前使用数量:" + nowUserNumber,LogLevel.DEBUG,"getModuleInPool");
    }
    public Object  getModuleInPool(String user){
        Object  module =useingModules.get(user);
        if(module ==null)
        {
            if(ifReachMaxUser())
                return null;
            synchronized (modulePool)
            {
                module =useingModules.get(user);
                if(module ==null)
                {
                    int index  =  nowModuePoolIndex.getAndIncrement();
                    int modulePoolSize =modulePool.size();
                    if(index >= modulePoolSize)
                    {
                        if(modulePoolSize <maxModuleNumbs)
                        {
                            int moduleNumber =index-modulePoolSize+1;
                            makePool(moduleNumber, modueInPool);
                        }
                        else {
                            index =0;
                            nowModuePoolIndex.set(0);
                        }
                    }
                    module =modulePool.get(index);
                    useingModules.put(user,module);
                }
            }
        }
        nowUserNumber= nowUseingModulesSize.incrementAndGet();
        putLog("模块使用，user:"+user+".当前使用数量:" + nowUserNumber,LogLevel.DEBUG,"getModuleInPool");
        return module ;
    }
    public boolean  ifUseModule(String user){
        return useingModules.containsKey(user);
    }
    public Object  getExistUserModuleInPool(String user){
        nowUserNumber= nowUseingModulesSize.incrementAndGet();
        return useingModules.get(user);
    }
    private boolean ifReachMaxUser() {
        if (maxUserNumber ==0)
            return false ;
        if (nowUserNumber >= maxUserNumber)
        {
            try {
                sleep(waitPoolTime);
            } catch (InterruptedException e) {
                e.printStackTrace();
                return true;
            }
            if (nowUserNumber >= maxUserNumber)
                return true;
            else
                return false;
        }
        return false ;
    }
}
