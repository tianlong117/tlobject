package cn.tianlong.tlobject.cache;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.Configuration;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.spi.loaderwriter.CacheWritingException;
import org.ehcache.xml.XmlConfiguration;

import java.io.File;
import java.net.MalformedURLException;
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
    protected TLBaseModule init() {
        super.init();
        //1、获取到XML文件位置的URL
        File file=new File(configFile);
        URL myUrl = null;
        try {
            myUrl = file.toURI().toURL();
        } catch (MalformedURLException e) {
            putLog("配置文件错误",LogLevel.ERROR,"init");
            e.printStackTrace();
            return null ;
        }
        //2、实例化一个XmlConfiguration，将XML文件URL传递给它
        Configuration xmlConfig = new XmlConfiguration(myUrl);
        //3、使用静态的org.ehcache.config.builders.CacheManagerBuilder.newCacheManager(org.ehcache.config.Configuration)
        //使用XmlConfiguration的Configuration创建你的CacheManager实例。
        macacheManagerager = CacheManagerBuilder.newCacheManager(xmlConfig);
        macacheManagerager.init();
        return this;
    }
    @Override
    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        if (macacheManagerager != null) {
            macacheManagerager.close();
            macacheManagerager = null;
        }
        return super.destroy(fromWho, msg);
    }

    @Override
    public Object getCache(String cacheName, String cacheKey,String valueType) {
        Class<?>  valueTypeClass =getValueType(valueType);
        Cache cache = macacheManagerager.getCache(cacheName,String.class,valueTypeClass);
        if(cache ==null)
            return this;
        if(cache.containsKey(cacheKey))
            return    cache.get(cacheKey);
        else
            return   this ;
    }

    @Override
    public boolean deleteCache(String cacheName, String cacheKey,String valueType) {
        Class<?>  valueTypeClass =getValueType(valueType);
        Cache cache = macacheManagerager.getCache(cacheName,String.class,valueTypeClass);
        if(cache ==null)
            return false;
        try {
            cache.remove(cacheKey);
            return true ;
        }catch ( CacheWritingException exception){
            putLog("删除缓存错误,cacheName"+cacheName+ "cacheKey:"+cacheKey,LogLevel.ERROR,"writeCache");
            exception.printStackTrace();
            return false ;
        }
    }

    @Override
    public boolean writeCache(String cacheName, String cacheKey, Object cacheValue, int exptime, String valueType) {
        Class<?>  valueTypeClass =getValueType(valueType);
        Cache cache = macacheManagerager.getCache(cacheName,String.class,valueTypeClass);
        if(cache ==null)
            return false ;
        try {
            cache.put( cacheKey,cacheValue);
            return true ;
        }catch ( CacheWritingException exception){
            putLog("写缓存错误,cacheName"+cacheName+ "cacheKey:"+cacheKey,LogLevel.ERROR,"writeCache");
            exception.printStackTrace();
            return false ;
        }

    }

}
