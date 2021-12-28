package cn.tianlong.tlobject.cache;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.Configuration;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.xml.XmlConfiguration;

import java.net.URL;

/**
 * 创建日期：2018/6/1014:04
 * 描述:
 * 作者:tianlong
 */
public class TLEhcache extends TLBaseCache {

    private CacheManager macacheManagerager;
    public TLEhcache(){
        super();
    }
    public TLEhcache(String name ){
        super(name);
    }
    public TLEhcache(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        String configfile=moduleFactory.getConfigDir()+"/"+"ehcache.xml";
        //1、获取到XML文件位置的URL
        URL myUrl = this.getClass().getResource(configfile);
        //2、实例化一个XmlConfiguration，将XML文件URL传递给它
        Configuration xmlConfig = new XmlConfiguration(myUrl);
        //3、使用静态的org.ehcache.config.builders.CacheManagerBuilder.newCacheManager(org.ehcache.config.Configuration)
        //使用XmlConfiguration的Configuration创建你的CacheManager实例。
        macacheManagerager = CacheManagerBuilder.newCacheManager(xmlConfig);
        macacheManagerager.init();
    }

    @Override
    public Object getCache(String cacheName, String cacheKey,String valueType) {
        Class<?>  valueTypeClass =getValueType(valueType);
        Cache cache = macacheManagerager.getCache(cacheName,String.class,valueTypeClass);
        if(cache ==null)
            return null;
        return   cache.get(cacheKey);
    }

    @Override
    protected TLMsg getCache(Object fromWho, TLMsg msg) {
        String cacheName= (String) msg.getParam("cacheName");
        String key =(String) msg.getParam("cacheKey");
        Class<?>  valueType =getValueType((String) msg.getParam("valueType"));
        Cache cache = macacheManagerager.getCache(cacheName,String.class,valueType);
        if(cache ==null)
            return null;
        Object cacheValue =  cache.get(key);
        if(cacheValue==null)
            return null;
        return createMsg().setParam("cacheValue",cacheValue);
    }

    @Override
    public boolean deleteCache(String cacheName, String cacheKey,String valueType) {
        Class<?>  valueTypeClass =getValueType(valueType);
        Cache cache = macacheManagerager.getCache(cacheName,String.class,valueTypeClass);
        if(cache ==null)
            return false;
        cache.remove(cacheKey);
        return true ;
    }

    @Override
    protected TLMsg deleteCache(Object fromWho, TLMsg msg) {
        String cacheName= (String) msg.getParam("cacheName");
        String key =(String) msg.getParam("cacheKey");
        Class<?>  valueType =getValueType((String) msg.getParam("valueType"));
        Cache cache = macacheManagerager.getCache(cacheName,String.class,valueType);
        if(cache ==null)
            return null;
        cache.remove(key);
        return createMsg().setParam("isCache","false");
    }

    @Override
    public boolean writeCache(String cacheName, String cacheKey, Object cacheValue, int exptime, String valueType) {
        Class<?>  valueTypeClass =getValueType(valueType);
        Cache cache = macacheManagerager.getCache(cacheName,String.class,valueTypeClass);
         cache.put( cacheKey,cacheValue);
         return true ;
    }

    @Override
    protected TLMsg writeCache(Object fromWho, TLMsg msg) {
        String cacheName= (String) msg.getParam("cacheName");
        String key =(String) msg.getParam("cacheKey");
        Class<?>  valueType =getValueType((String) msg.getParam("valueType"));
        Cache cache = macacheManagerager.getCache(cacheName,String.class,valueType);
        if(cache ==null)
            return createMsg().setParam("isCache","false");
        cache.put(key,msg.getParam("cacheValue"));
        return createMsg().setParam("isCache","true");
    }

}
