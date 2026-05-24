package cn.tianlong.tlobject.cache;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLModuleConfig;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLReUsedModulePool;
import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static cn.tianlong.tlobject.cache.TLParamString.*;



public abstract class TLBaseCache extends TLBaseModule {
    protected HashMap<String, HashMap<String, String>> cacheTables ;
    /**
     * 缓存过期时间。0为永久不过期
     */
    protected String defaultExptime ="0" ;
    /**
     * 是否使用锁池，默认不使用
     */
    protected boolean  ifUseLock =false;
    /**
     * 缓存锁池，当缓存读写时，从缓存池取锁。为平衡资源，多个缓存共享一把锁
     */
    protected TLReUsedModulePool locksPool;
    /**
     * 缓存锁数量。根据缓存的数量适当配置相应锁的数量。
     */
    protected String fileLockNumber="200";
    public TLBaseCache(){
        super();
    }
    public TLBaseCache(String name ){
        super(name);
    }
    public TLBaseCache(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected Object setConfig(){
        myConfig config=new myConfig(configFile,moduleFactory.getConfigDir());
        mconfig=config;
        super.setConfig();
        cacheTables=config.getCacheTables();
        return config;
    }
    @Override
    protected void initProperty(){
        super.initProperty();
        if( params!=null && params.get("ifUseLock")!=null)
            ifUseLock=Boolean.parseBoolean(params.get("ifUseLock")) ;
        if(cacheTables==null)
            cacheTables=new HashMap<>() ;
    }
    @Override
    protected TLBaseModule init(){
        if(ifUseLock==false)
            return this ;
        locksPool = (TLReUsedModulePool) getNewModule(name+"_lockspool","reUsedModulePool");
        HashMap<String,String> poolParams=new HashMap<>();
        poolParams.put(MODULENAME,"java.util.concurrent.locks.ReentrantReadWriteLock");
        poolParams.put("initModuleNumbs",fileLockNumber);
        putMsg(locksPool,createMsg().setAction(MODULEPOOL_MAKEPOOL).addMap(poolParams));
        return this ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case CACHE_WRITECACHE:
                returnMsg= writeCache(fromWho,msg);
                break;
            case CACHE_GETCACHE:
                 returnMsg= getCache(fromWho,msg);
                break;
            case CACHE_DELETECACHE:
                returnMsg= deleteCache(fromWho,msg);
                break;
            default:
        }
        return returnMsg ;
    }
    public boolean isCacheValue(Object cacheValue){
        if(this.equals(cacheValue))
            return false ;
        else
            return true ;
    }
    protected ReentrantReadWriteLock getLock(String lockIndex){
        return (ReentrantReadWriteLock) locksPool.getModuleByIndex(lockIndex);
    }
    protected void  reBackLock(){
        locksPool.useModuleOver() ;
    }


    public  abstract Object getCache(String cacheName,  String cacheKey,String valueType) ;

    protected TLMsg getCache(Object fromWho, TLMsg msg)  {
        String cacheName=  msg.getStringParam(CACHE_P_CACHENAME,null);
        if(cacheName ==null || cacheName.isEmpty())
            return createMsg().setParam(CACHE_P_VALUE,this);
        String cacheKey= msg.getStringParam(CACHE_P_KEY,null);
        String valueType= msg.getStringParam(CACHE_P_VALUETYPE,null);
        Object cacheValue= getCache( cacheName, cacheKey,valueType);
        return createMsg().setParam(CACHE_P_VALUE,cacheValue);
    }

    public abstract boolean deleteCache(String cacheName,  String cacheKey,String valueType) ;

    protected TLMsg deleteCache(Object fromWho, TLMsg msg) {
        String cacheName=  msg.getStringParam(CACHE_P_CACHENAME,null);
        String cacheKey= msg.getStringParam(CACHE_P_KEY,null);
        String valueType= msg.getStringParam(CACHE_P_VALUETYPE,null);
        Boolean result=deleteCache( cacheName,  cacheKey,valueType) ;
        return createMsg().setParam(RESULT,result).setParam("isCache",result);
    }

    public abstract boolean writeCache(String cacheName,  String cacheKey,Object cacheValue ,int exptime ,String valueType);

    protected TLMsg writeCache(Object fromWho, TLMsg msg) {
        String cacheName=  msg.getStringParam(CACHE_P_CACHENAME,null);
        if(cacheName ==null || cacheName.isEmpty())
            return createMsg().setParam(RESULT,false).setParam("isCache",false);
        String cacheKey= msg.getStringParam(CACHE_P_KEY,null);
        String valueType= msg.getStringParam(CACHE_P_VALUETYPE,null);
        Object cacheValue=  msg.getParam(CACHE_P_VALUE);
        int exptime= msg.getIntParam(CACHE_P_EXPTTIME,-1);
        boolean result =writeCache( cacheName, cacheKey, cacheValue , exptime ,valueType);
        return createMsg().setParam(RESULT,result).setParam("isCache",result);
    }

    protected Long takeExptime(String cacheName ,int exptime){
        if(exptime == 0)
            return 0L ;
        if(exptime  < 0)
        {
            HashMap<String, String> cacheParam=cacheTables.get(cacheName);
            if(cacheParam ==null )
                return -1L;
            String  exptimeStr =cacheParam.get(CACHE_P_EXPTTIME);
            if(exptimeStr == null || exptimeStr.isEmpty())
                exptimeStr = defaultExptime;
            exptime =Integer.parseInt(exptimeStr);
        }
        Long cacheExptime ;
        if(exptime ==0)
            cacheExptime =0L ;
        else
            cacheExptime=System.currentTimeMillis()+(exptime*60*1000);
        return cacheExptime ;
    }

    protected Class<?> getValueType (String valueType){
        if (valueType==null)
            return ArrayList.class;
        switch (valueType) {
            case C_VARTYPE_STRING:
                return String.class;
            case C_VARTYPE_LIST:
                return ArrayList.class;
            case C_VARTYPE_MAP:
                return LinkedHashMap.class;
            case C_VARTYPE_HASHMAP:
                return HashMap.class;
            case C_VARTYPE_INT:
                return Integer.class;
            case C_VARTYPE_LONG:
                return Long.class;
            case C_VARTYPE_DOUBLE:
                return Double.class;
            case C_VARTYPE_CHAR:
                return Character.class;
            default:
                return ArrayList.class;
        }
    }

    protected class myConfig extends TLModuleConfig {
        protected HashMap<String, HashMap<String, String>> cacheTables ;
        public myConfig(String configFile ,String configDir) {
            super(configFile,configDir);
        }
        public myConfig() {

        }
        public HashMap getCacheTables() {
            return cacheTables;
        }

        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if (xpp.getName().equals("caches")) {
                    cacheTables= getHashMap(xpp,"caches","cacheName");
                }

            } catch (Throwable t) {

            }
        }

    }
}