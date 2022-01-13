package cn.tianlong.tlobject.cache;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import static cn.tianlong.tlobject.cache.TLParamString.CACHE_P_EXPTTIME;
import static cn.tianlong.tlobject.cache.TLParamString.CACHE_P_VALUE;

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
        for (String cacheName : cacheTables.keySet()) {
            ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> cacheMap = new ConcurrentHashMap<>();
            cacheDatas.put(cacheName, cacheMap);
        }
        return this;
    }
    public boolean addCache(String cacheName,String exptime){
        if(cacheDatas.containsKey(cacheName))
            return false ;
        if(cacheTables.containsKey(cacheName))
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
        if (cacheData == null)
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
            return false ;
        synchronized (cacheMap) {
            ConcurrentHashMap<String, Object> cacheData = cacheMap.get(cacheKey);
            if (cacheData != null)
                return false;
            cacheData = new ConcurrentHashMap<>();
            cacheMap.put(cacheKey, cacheData);
            cacheData.put(CACHE_P_EXPTTIME, cacheExptime);
            cacheData.put(CACHE_P_VALUE, cacheValue);
            return true;
        }
    }

}
