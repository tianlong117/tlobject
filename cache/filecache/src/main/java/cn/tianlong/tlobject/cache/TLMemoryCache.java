package cn.tianlong.tlobject.cache;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

public class TLMemoryCache extends TLBaseCache {
    protected String cacheName ;
    protected ConcurrentHashMap<String, HashMap<String, Object>> cacheDatas = new ConcurrentHashMap<>();
    private int exptime;

    public TLMemoryCache(){
        super();
    }
    public TLMemoryCache(String name ){
        super(name);
        cacheName =name ;
    }
    public TLMemoryCache(int exptime ){
        this.exptime =exptime *60*1000;
    }
    public TLMemoryCache(String name ,int exptime ){
        this.name =name ;
        this.exptime =exptime *60*1000;
    }
    public TLMemoryCache(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
        cacheName =name ;
    }
    @Override
    protected void initProperty() {
        super.initProperty();
        if(params!=null && params.get("exptime")!=null)
            exptime=Integer.parseInt(params.get("primaryKey"))*60*1000;
    }
    public String getCacheName(){
       return  cacheName ;
    }
    public void setExptime(int exptime){
        this.exptime =exptime *60*1000;
    }
    public Object getCache( String cacheKey) {
       return  getCache(null,  cacheKey, null) ;
    }
    @Override
    public Object getCache(String cacheName, String cacheKey, String valueType) {
        if(cacheDatas ==null )
            return this;
        HashMap<String, Object> cacheMap =cacheDatas.get(cacheKey);
        if(cacheMap ==null )
            return this;
        Long time = (Long) cacheMap.get("time");
        if(System.currentTimeMillis() > time)
            return  this ;
        Object value = cacheMap.get("value");
        return value ;
    }

    @Override
    protected TLMsg getCache(Object fromWho, TLMsg msg) {
        return null;
    }
    public boolean deleteCache( String cacheKey) {

        return deleteCache(null, cacheKey, null) ;
    }
    @Override
    public boolean deleteCache(String cacheName, String cacheKey, String valueType) {
        if(cacheDatas ==null )
            return false;
        cacheDatas.remove(cacheKey);
        return true;
    }

    @Override
    protected TLMsg deleteCache(Object fromWho, TLMsg msg) {
        return null;
    }
    public void writeCache(String cacheKey, Object cacheValue) {
       writeCache(null,  cacheKey,  cacheValue, this.exptime, null);
    }
    @Override
    public boolean writeCache(String cacheName, String cacheKey, Object cacheValue, int exptime, String valueType) {

        Long time = System.currentTimeMillis() +exptime ;
        HashMap<String ,Object> cacheMap =new HashMap<>() ;
        cacheMap.put("time",time) ;
        cacheMap.put("value", cacheValue) ;
        cacheDatas.put(cacheKey,cacheMap);
        return true ;
    }

    @Override
    protected TLMsg writeCache(Object fromWho, TLMsg msg) {
        return null;
    }
}
