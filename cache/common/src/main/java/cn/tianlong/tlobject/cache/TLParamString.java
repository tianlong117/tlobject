package cn.tianlong.tlobject.cache;

/**
 * 创建日期：2018/4/5 on 8:47
 * 描述:
 * 作者:tianlong
 */

public interface TLParamString {


    final String M_FILECACHE = "fileCache";
    final String M_MEMORYCACHE = "memoryCache";
    /**    模块名 *      */
    final String CACHE_GETCACHE = "getCache";
    final String CACHE_WRITECACHE = "writeCache";
    final String CACHE_DELETECACHE = "eleteCache";

    final String CACHE_P_CACHENAME = "cacheName";
    final String CACHE_P_KEY = "cacheKey";
    final String CACHE_P_EXPTTIME = "exptime";
    final String CACHE_P_VALUE = "cacheValue";
    final String CACHE_P_VALUETYPE = "valueType";
    final String CACHE_R_VALUE = "cacheValue";

    final String M_URLFILECACHE = "urlFileCache";
    final String M_URLIMAGECACHE = "urlImageCache";



}
