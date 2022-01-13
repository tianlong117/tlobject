package cn.tianlong.tlobject.cache;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLModuleConfig;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.HashMap;

import static cn.tianlong.tlobject.cache.TLParamString.*;


public abstract class TLBaseCache extends TLBaseModule {
    protected HashMap<String, HashMap<String, String>> cacheTables ;
    protected String defaultExptime ="0" ;
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
    public boolean isCacheValue(Object cacheValue){
        if(this.equals(cacheValue))
            return false ;
        else
            return true ;
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
        HashMap<String, String> cacheParam=cacheTables.get(cacheName);
        if(cacheParam ==null )
            return -1L;
        if(exptime == 0)
            return 0L ;
        if(exptime  < 0)
        {
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