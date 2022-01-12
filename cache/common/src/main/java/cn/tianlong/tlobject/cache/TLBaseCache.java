package cn.tianlong.tlobject.cache;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.ArrayList;
import java.util.HashMap;

import static cn.tianlong.tlobject.cache.TLParamString.*;


public abstract class TLBaseCache extends TLBaseModule {
    public TLBaseCache(){
        super();
    }
    public TLBaseCache(String name ){
        super(name);
    }
    public TLBaseCache(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    protected TLBaseModule init(){
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
    public  abstract Object getCache(String cacheName,  String cacheKey,String valueType) ;
    protected abstract TLMsg getCache(Object fromWho, TLMsg msg);

    public abstract boolean deleteCache(String cacheName,  String cacheKey,String valueType) ;

    protected abstract TLMsg deleteCache(Object fromWho, TLMsg msg);

    public abstract boolean writeCache(String cacheName,  String cacheKey,Object cacheValue ,int exptime ,String valueType);

    protected abstract TLMsg writeCache(Object fromWho, TLMsg msg);

    protected Class<?> getValueType (String valueType){
        if (valueType==null)
            return ArrayList.class;
        switch (valueType) {
            case "string":
                return String.class;
            case "arraylist":
                return ArrayList.class;
            case "hashmap":
                return HashMap.class;
            case "int":
                return Integer.class;
            default:
                return ArrayList.class;
        }
    }

}