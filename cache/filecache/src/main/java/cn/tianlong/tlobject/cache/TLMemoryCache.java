package cn.tianlong.tlobject.cache;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import static cn.tianlong.tlobject.cache.TLParamString.CACHE_P_EXPTTIME;
import static cn.tianlong.tlobject.cache.TLParamString.CACHE_P_VALUE;
import static java.lang.Thread.sleep;

public class TLMemoryCache extends TLBaseCache {

    protected ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, Object>>> cacheDatas = new ConcurrentHashMap<>();

    public TLMemoryCache() {
        super();
    }

    public TLMemoryCache(String name) {
        super(name);
    }

    public TLMemoryCache(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected TLBaseModule init() {
        super.init();
        if(cacheTables !=null)
        {
            for (String cacheName : cacheTables.keySet()) {
                ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> cacheMap = new ConcurrentHashMap<>();
                cacheDatas.put(cacheName, cacheMap);
            }
        }
        Thread thread = new Thread(()->{
            while (true){
                try{
                    sleep(2000);
                    if(!cacheDatas.isEmpty())
                    {
                        for(ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> caches :cacheDatas.values()){

                            for(String key : caches.keySet()){
                                ConcurrentHashMap<String, Object> cacheData= caches.get(key);
                                Long time = (Long) cacheData.get(CACHE_P_EXPTTIME);
                                if (time == 0L || System.currentTimeMillis() < time)
                                    continue;
                                else
                                    caches.remove(key);
                            }
                        }
                    }
                }catch (Exception e){
                    putLog("缓存清理线程错误",LogLevel.ERROR,"init");
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        return this;
    }
    public boolean addCache(String cacheName,String exptime){
        if(cacheDatas.containsKey(cacheName))
            return false ;
        if(cacheTables !=null && cacheTables.containsKey(cacheName))
            return false ;
        HashMap<String,String> cacheParam =new HashMap<>() ;
        if(exptime !=null)
             cacheParam.put(CACHE_P_EXPTTIME,exptime);
        cacheTables.put(cacheName,cacheParam) ;
        ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> cacheMap = new ConcurrentHashMap<>();
        cacheDatas.put(cacheName, cacheMap);
        return true ;
    }
    @Override
    public Object getCache(String cacheName, String cacheKey, String valueType) {
        if (cacheDatas == null)
            return this;
        ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> cacheMap = cacheDatas.get(cacheName);
        if (cacheMap == null)
            return this;
        ConcurrentHashMap<String, Object> cacheData = cacheMap.get(cacheKey);
        if (cacheData == null || cacheData.isEmpty())
            return this;
        Long time = (Long) cacheData.get(CACHE_P_EXPTTIME);
        if (time == 0L)
            return cacheMap.get(CACHE_P_VALUE);
        if (System.currentTimeMillis() < time)
            return cacheData.get(CACHE_P_VALUE);
        else {
            cacheMap.remove(cacheKey);
            return this;
        }
    }

    @Override
    public boolean deleteCache(String cacheName, String cacheKey, String valueType) {
        if (cacheDatas == null)
            return false;
        ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> cacheMap = cacheDatas.get(cacheName);
        if (cacheMap == null)
            return false;
        if (cacheMap.containsKey(cacheKey))
            cacheMap.remove(cacheKey);
        return true;
    }

    @Override
    public boolean writeCache(String cacheName, String cacheKey, Object cacheValue, int exptime, String valueType) {

        long cacheExptime = takeExptime(cacheName, exptime);
        if (cacheExptime < 0L)
            return false;
        ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> cacheMap = cacheDatas.get(cacheName);
        if(cacheMap ==null)
        {
            cacheMap = new ConcurrentHashMap<>();
            Object result= cacheDatas.putIfAbsent(cacheName,cacheMap);
            if(result !=null)
                cacheMap= (ConcurrentHashMap<String, ConcurrentHashMap<String, Object>>) result;
        }
        ConcurrentHashMap<String, Object> cacheData = cacheMap.get(cacheKey);
        if (cacheData != null )
            return false;
        cacheData = new ConcurrentHashMap<>();
        cacheData.put(CACHE_P_EXPTTIME, cacheExptime);
        cacheData.put(CACHE_P_VALUE, cacheValue);
        Object value = cacheMap.putIfAbsent(cacheKey, cacheData);
        if(value !=null)
            return false;
        else
            return true;
    }

}
