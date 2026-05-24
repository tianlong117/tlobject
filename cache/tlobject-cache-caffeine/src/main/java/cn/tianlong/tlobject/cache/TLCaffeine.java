package cn.tianlong.tlobject.cache;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLObjectFactory;

import cn.tianlong.tlobject.utils.TLDataUtils;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

public class TLCaffeine extends TLBaseCache {

    private HashMap<String ,Cache<Object, Object>> caches =new HashMap<>();
    public TLCaffeine(){
        super();
    }
    public TLCaffeine(String name ){
        super(name);
    }
    public TLCaffeine(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected TLBaseModule init() {
        super.init();
        for(String cacheName :cacheTables.keySet()){
            HashMap<String, String> config =cacheTables.get(cacheName) ;
            if(config !=null && !config.isEmpty() )
            {
                Cache<Object, Object> caffeineModule =creatCaffeine(config);
                caches.put(cacheName,caffeineModule);
            }
        }
        return this;
    }

    private Cache<Object, Object> creatCaffeine(HashMap<String,String> config) {
        Caffeine<Object, Object>  caffeine = Caffeine.newBuilder();
        int maximumSize =TLDataUtils.parseInt(config.get("maximumSize"),0) ;
        if(maximumSize >0) caffeine.maximumSize(maximumSize);
        int expireAfterAccess =TLDataUtils.parseInt(config.get("expireAfterAccess"),0) ;
        if(expireAfterAccess >0)
            caffeine.expireAfterAccess(expireAfterAccess,TimeUnit.SECONDS);
        else {
            int expireAfterWrite =TLDataUtils.parseInt(config.get("expireAfterWrite"),0) ;
            if(expireAfterWrite >0)
                caffeine.expireAfterWrite(expireAfterWrite,TimeUnit.SECONDS);
            else {
                int refreshAfterWrite =TLDataUtils.parseInt(config.get("refreshAfterWrite"),0) ;
                if(refreshAfterWrite >0)
                    caffeine.refreshAfterWrite(refreshAfterWrite,TimeUnit.SECONDS);
            }
        }
        return caffeine.build() ;
    }

    @Override
    public Object getCache(String cacheName, String cacheKey, String valueType) {
        Cache<Object, Object>  caffeine =caches.get(cacheName) ;
        if ( caffeine ==null )
            return this ;
        Object value = caffeine.getIfPresent(cacheKey) ;
        if (value ==null)
            return this ;
        return value ;
    }

    @Override
    public boolean deleteCache(String cacheName, String cacheKey, String valueType) {
        Cache<Object, Object>  caffeine =caches.get(cacheName) ;
        if ( caffeine ==null )
            return false ;
         caffeine.invalidate(cacheKey);
         return true ;
    }

    @Override
    public boolean writeCache(String cacheName, String cacheKey, Object cacheValue, int exptime, String valueType) {
        Cache<Object, Object>  caffeine =caches.get(cacheName) ;
        if ( caffeine ==null )
            return false ;
         caffeine.put(cacheKey,cacheValue);
         return true ;
    }
}
