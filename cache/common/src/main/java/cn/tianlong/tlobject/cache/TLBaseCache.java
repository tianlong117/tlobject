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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static cn.tianlong.tlobject.cache.TLParamString.*;
import static java.lang.Thread.sleep;


public abstract class TLBaseCache extends TLBaseModule {
    protected HashMap<String, HashMap<String, String>> cacheTables ;
    /**
     * 缓存过期时间。0为永久不过期
     */
    protected String defaultExptime ="0" ;
    /**
     * 缓存过期突破时， 为限制只有一个去查询数据库，存放已经查询的缓存key
     */
    protected CopyOnWriteArrayList<Object> cacheIndexList = new CopyOnWriteArrayList<>() ;
    /**
     * 缓存过期突破时， 允许发生查询数据库的并发量 。0为不限制
     */
    protected int  concurrentNumber=0;
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
        if( params!=null && params.get("concurrentNumber")!=null)
            concurrentNumber=Integer.parseInt(params.get("concurrentNumber")) ;
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

    /**
     *  当缓存过期，防止数据库被多查询冲击，只允许一次查询
     * @param cacheName
     * @param cacheKey
     * @param valueType
     * @param exptime
     * @param getDataMsg
     * @param valueKey
     * @return
     */
    public Object  getAndWrite(String cacheName,  String cacheKey,String valueType,int exptime,TLMsg getDataMsg,String valueKey) {
        Object cacheValue =getCache( cacheName, cacheKey, valueType) ;
        if(!isCacheValue(cacheValue))
        {
           String cacheIndex =cacheName+cacheKey;
           if(cacheIndexList.contains(cacheIndex))
           {
               int i=1;
               do {
                   if(i>=5)
                   try {
                       sleep(2);
                       i++ ;
                       if(!cacheIndexList.contains(cacheIndex))
                           return getCache( cacheName, cacheKey, valueType) ;
                   } catch (InterruptedException e) {
                       e.printStackTrace();
                       return this ;
                   }
               }while (cacheIndexList.contains(cacheIndex));
           }
           if(concurrentNumber >0 && cacheIndexList.size()>=concurrentNumber) {
              do {
                  try {
                      sleep(5);
                  } catch (InterruptedException e) {
                      e.printStackTrace();
                      return this ;
                  }
              }while (cacheIndexList.size() >= concurrentNumber) ;
           }
           cacheIndexList.add(cacheIndex) ;
           TLMsg returnMsg= getMsg(this,getDataMsg);
           cacheValue =returnMsg.getParam(valueKey);
           if(cacheValue !=null)
               writeCache( cacheName,  cacheKey,cacheValue ,exptime ,valueType);
           cacheIndexList.remove(cacheIndex);
        }
        return cacheValue ;
    }
    public boolean containsCacheKey(String cacheName,  String cacheKey){
        return false ;
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
                return CharSequence.class;
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