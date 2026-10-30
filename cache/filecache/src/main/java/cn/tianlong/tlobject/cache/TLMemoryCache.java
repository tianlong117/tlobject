package cn.tianlong.tlobject.cache;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import static cn.tianlong.tlobject.cache.TLParamString.CACHE_P_EXPTTIME;
import static cn.tianlong.tlobject.cache.TLParamString.CACHE_P_VALUE;
import static java.lang.Thread.sleep;

public class TLMemoryCache extends TLBaseCache {

    protected ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, Object>>> cacheDatas = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<String>> cacheKeysOrder = new ConcurrentHashMap<>();
    protected int maxSize = 0;  // 每个 cacheName 最大条目数，0=不限制
    /** 清理线程退出标志：模块 destroy/reload 时必须停止，否则旧线程随实例泄漏 */
    private volatile boolean cleanupRunning = true;
    private Thread cleanupThread;

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
        if (params != null && params.get("maxSize") != null)
            maxSize = Integer.parseInt(params.get("maxSize"));
        if(cacheTables !=null)
        {
            for (String cacheName : cacheTables.keySet()) {
                ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> cacheMap = new ConcurrentHashMap<>();
                cacheDatas.put(cacheName, cacheMap);
                cacheKeysOrder.put(cacheName, new ConcurrentLinkedDeque<>());
            }
        }
        cleanupRunning = true;
        cleanupThread = new Thread(()->{
            while (cleanupRunning){
                try{
                    sleep(2000);
                    if (!cleanupRunning) break;
                    if(!cacheDatas.isEmpty())
                    {
                        for(Map.Entry<String, ConcurrentHashMap<String, ConcurrentHashMap<String, Object>>> entry : cacheDatas.entrySet()){
                            String cacheName = entry.getKey();
                            ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> caches = entry.getValue();
                            ConcurrentLinkedDeque<String> keyDeque = cacheKeysOrder.get(cacheName);
                            for(String key : caches.keySet()){
                                ConcurrentHashMap<String, Object> cacheData = caches.get(key);
                                if (cacheData == null) continue;
                                Object timeObj = cacheData.get(CACHE_P_EXPTTIME);
                                if (!(timeObj instanceof Number)) { caches.remove(key); if (keyDeque != null) keyDeque.remove(key); continue; }
                                long time = ((Number) timeObj).longValue();
                                if (time == 0L || System.currentTimeMillis() < time)
                                    continue;
                                else {
                                    caches.remove(key);
                                    if (keyDeque != null) keyDeque.remove(key);
                                }
                            }
                        }
                    }
                }catch (InterruptedException e){
                    Thread.currentThread().interrupt();
                    break;
                }catch (Exception e){
                    putLog("缓存清理线程错误",LogLevel.ERROR,"init");
                }
            }
        });
        cleanupThread.setDaemon(true);
        cleanupThread.start();
        return this;
    }

    @Override
    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        cleanupRunning = false;
        if (cleanupThread != null)
            cleanupThread.interrupt();
        return super.destroy(fromWho, msg);
    }
    public boolean addCache(String cacheName,String exptime){
        if(cacheDatas.containsKey(cacheName))
            return false ;
        if(cacheTables !=null && cacheTables.containsKey(cacheName))
            return false ;
        HashMap<String,String> cacheParam =new HashMap<>() ;
        if(exptime !=null)
             cacheParam.put(CACHE_P_EXPTTIME,exptime);
        if (cacheTables == null)
            cacheTables = new ConcurrentHashMap<>();
        cacheTables.put(cacheName,cacheParam) ;
        ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> cacheMap = new ConcurrentHashMap<>();
        cacheDatas.put(cacheName, cacheMap);
        cacheKeysOrder.putIfAbsent(cacheName, new ConcurrentLinkedDeque<>());
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
        Object timeObj = cacheData.get(CACHE_P_EXPTTIME);
        if (!(timeObj instanceof Number)) {
            cacheMap.remove(cacheKey);
            return this;
        }
        long time = ((Number) timeObj).longValue();
        if (time == 0L)
            return cacheData.get(CACHE_P_VALUE);
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
        if (cacheMap.containsKey(cacheKey)) {
            cacheMap.remove(cacheKey);
            ConcurrentLinkedDeque<String> keyDeque = cacheKeysOrder.get(cacheName);
            if (keyDeque != null) keyDeque.remove(cacheKey);
        }
        return true;
    }

    @Override
    public boolean writeCache(String cacheName, String cacheKey, Object cacheValue, int exptime, String valueType) {

        if(cacheValue ==null)
            cacheValue = this  ;
        long cacheExptime = takeExptime(cacheName, exptime);
        if (cacheExptime < 0L)
            return false;
        ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> cacheMap = cacheDatas.get(cacheName);
        ConcurrentLinkedDeque<String> keyDeque = cacheKeysOrder.get(cacheName);
        if(cacheMap ==null)
        {
            cacheMap = new ConcurrentHashMap<>();
            cacheDatas.put(cacheName,cacheMap);
            if (keyDeque == null) {
                keyDeque = new ConcurrentLinkedDeque<>();
                cacheKeysOrder.put(cacheName, keyDeque);
            }
        }
        ConcurrentHashMap<String, Object> cacheData = cacheMap.get(cacheKey);
        if (cacheData == null) {
            cacheData = new ConcurrentHashMap<>();
            cacheMap.put(cacheKey, cacheData);
            // 新 key，维护 LRU 队列
            if (keyDeque != null) {
                keyDeque.addLast(cacheKey);
                // 超过上限时淘汰最旧的
                if (maxSize > 0 && cacheMap.size() > maxSize) {
                    String oldestKey = keyDeque.pollFirst();
                    // 并发下 poll 到的可能正是刚插入的 key，误删会把自己淘汰
                    if (oldestKey != null && !oldestKey.equals(cacheKey))
                        cacheMap.remove(oldestKey);
                }
            }
        } else {
            // 已存在的 key，提到队列末尾（最近使用）
            if (keyDeque != null) {
                keyDeque.remove(cacheKey);
                keyDeque.addLast(cacheKey);
            }
        }
        cacheData.put(CACHE_P_EXPTTIME, cacheExptime);
        cacheData.put(CACHE_P_VALUE, cacheValue);
        return true;
    }

}
