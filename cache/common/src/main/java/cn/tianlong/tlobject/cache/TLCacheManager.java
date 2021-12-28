package cn.tianlong.tlobject.cache;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;
import org.xmlpull.v1.XmlPullParser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLCacheManager extends TLBaseModule {
    protected String cache;
    protected HashMap<String, HashMap<String, String>> caches;
    protected HashMap<String, HashMap<String, String>> modulesForCache;

    public TLCacheManager() {
        super();
    }

    public TLCacheManager(String name) {
        super(name);
    }

    public TLCacheManager(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        if (params != null) {
            if (params.get("cacheClass") != null)
                cache = params.get("cacheClass");
        }
    }

    protected Object setConfig() {
        myConfig config = new myConfig(configFile,moduleFactory.getConfigDir());;
        mconfig = config;
        super.setConfig();
        caches = config.getCaches();
        modulesForCache = config.getModulesForCache();
        return config;
    }

    @Override
    protected TLBaseModule init() {
        return this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "getCacheBeforeModle":
                returnMsg = getCacheBeforeModle(fromWho, msg);
                break;
            case "writeCacheAfterModle":
                returnMsg = writeCacheAfterModle(fromWho, msg);
                break;
            case "writeCache":
                returnMsg = writeCache(fromWho, msg);
                break;
            case "deleteCacheAfterModle":
                returnMsg = deleteCacheAfterModle(fromWho, msg);
                break;
            default:
        }
        return returnMsg;
    }

    private TLMsg writeCache(Object fromWho, TLMsg msg) {
        HashMap<String, String> moduleCacheParam = (HashMap<String, String>) msg.getParam("cacheParam");
        if (moduleCacheParam == null ||moduleCacheParam.isEmpty())
            return null;
        String cacheValue = moduleCacheParam.get("cacheValue");
        if(cacheValue==null)
        {
            String cacheValueName = moduleCacheParam.get("cacheValueName");
            cacheValue = (String) msg.getParam(cacheValueName);
        }
        if(cacheValue==null)
            return null ;
        String cacheName = moduleCacheParam.get("cacheName");
        String cacheKey = moduleCacheParam.get("cacheKey");
        String exptime = moduleCacheParam.get("exptime");
        String valueType = moduleCacheParam.get("valueType");
        TLMsg returnMsg = cache("writeCache", cacheName, cacheKey, exptime, cacheValue, valueType);
        if (returnMsg != null && returnMsg.getParam("isCache").equals("true"))
            putLog("write cache sucess:" + cacheName + "_" + cacheKey, LogLevel.DEBUG);
        else
            putLog("write cache failure:" + cacheName + "_" + cacheKey, LogLevel.WARN);
        return returnMsg;
    }

    protected TLMsg deleteCacheAfterModle(Object fromWho, TLMsg msg) {
        TLMsg smsg = (TLMsg) msg.getParam(DOWITHMAG);
        HashMap<String, String> moduleCacheParam = getCacheParams(fromWho, smsg);
        if (moduleCacheParam == null ||moduleCacheParam.isEmpty())
            return (TLMsg) msg.getParam(PRERESULT);
        String cacheName = moduleCacheParam.get("cacheName");
        String cacheKey = moduleCacheParam.get("cacheKey");
        String valueType = moduleCacheParam.get("valueType");
        TLMsg returnMsg = cache("deleteCache", cacheName, cacheKey, null, null, valueType);
        if (returnMsg != null)
            putLog("delete cache sucess:" + cacheName + "_" + cacheKey, LogLevel.DEBUG);
        else
            putLog("delete cache failure:" + cacheName + "_" + cacheKey, LogLevel.WARN);
        return (TLMsg) msg.getParam(PRERESULT);
    }

    protected TLMsg writeCacheAfterModle(Object fromWho, TLMsg msg) {
        TLMsg smsg = (TLMsg) msg.getParam(DOWITHMAG);
        HashMap<String, String> moduleCacheParam = getCacheParams(fromWho, smsg);
        if (moduleCacheParam == null ||moduleCacheParam.isEmpty())
            return (TLMsg) msg.getParam(PRERESULT);
        String cacheValueName = moduleCacheParam.get("cacheValueName");
        Object cacheValue = ((TLMsg) msg.getParam(PRERESULT)).getParam(cacheValueName);
        String cacheName = moduleCacheParam.get("cacheName");
        String cacheKey = moduleCacheParam.get("cacheKey");
        String exptime = moduleCacheParam.get("exptime");
        String valueType = moduleCacheParam.get("valueType");
        TLMsg returnMsg = cache("writeCache", cacheName, cacheKey, exptime, cacheValue, valueType);
        if (returnMsg != null && returnMsg.getParam("isCache").equals("true"))
            putLog("write cache sucess:" + cacheName + "_" + cacheKey, LogLevel.DEBUG);
        else
            putLog("write cache failure:" + cacheName + "_" + cacheKey, LogLevel.WARN);
        return (TLMsg) msg.getParam(PRERESULT);
    }

    protected TLMsg getCacheBeforeModle(Object fromWho, TLMsg msg) {
        TLMsg smsg = (TLMsg) msg.getParam(DOWITHMAG);
        HashMap<String, String> moduleCacheParam = getCacheParams(fromWho, smsg);
        if (moduleCacheParam == null)
            return null;
        String cacheName = moduleCacheParam.get("cacheName");
        String cacheKey = moduleCacheParam.get("cacheKey");
        String exptime = moduleCacheParam.get("exptime");
        String valueType = moduleCacheParam.get("valueType");
        TLMsg returnMsg = cache("getCache", cacheName, cacheKey, exptime, null, valueType);
        if (returnMsg == null)
            return null;
        if (returnMsg != null)
            putLog("get  cache:" + cacheName + "_" + cacheKey, LogLevel.DEBUG);
        String cacheValueName = moduleCacheParam.get("cacheValueName");
        Object cacheValue = returnMsg.getParam("cacheValue");
        return createMsg().setParam(cacheValueName, cacheValue).setParam(MODULE_DONEXTMSG, "false");
    }
    protected TLMsg cache(String cacheAction, String cacheName, String cacheKey, String exptime, Object cacheValue, String valueType) {

        TLMsg cacheMsg = createMsg().setAction(cacheAction)
                .setParam("cacheName", cacheName)
                .setParam("cacheKey", cacheKey)
                .setParam("exptime", exptime)
                .setParam("valueType", valueType)
                .setParam("cacheValue", cacheValue);
        return putMsg(cache, cacheMsg);
    }

    protected HashMap<String, String> getCacheParams(Object fromWho, TLMsg msg) {
        HashMap<String, String> moduleCacheParam = new HashMap<>();
        String cacheForModle = msg.getDestination();
        if (cacheForModle == null)
            cacheForModle = msg.getNowObject();
        String action = msg.getAction();
        HashMap<String, String> cacheModle = modulesForCache.get(cacheForModle);
        if (cacheModle == null)
            return null;
        String cacheName = cacheModle.get(action);
        if (cacheName == null)
            return null;
        HashMap<String, String> cacheParams = caches.get(cacheName);
        if (cacheParams == null)
            return null;
        String exptime = cacheParams.get("exptime");
        String cacheKey = cacheParams.get("cacheKey");
        String cacheKeyVar = cacheParams.get("cacheKeyVar");
        String cacheKeyModule = cacheParams.get("cacheKeyModule");
        String cacheKeyMsg = cacheParams.get("cacheKeyMsg");
        String cacheValueName = cacheParams.get("cacheValueName");
        String valueType = cacheParams.get("valueType");
        if (cacheKeyVar != null && !cacheKeyVar.isEmpty()) {
            if (cacheKey == null)
                cacheKey = "";
            StringBuffer stringBuffer = new StringBuffer();
            stringBuffer.append(cacheKey);
            String cacheKeyVarlist[] = cacheKeyVar.split(";");
            for (int i = 0; i < cacheKeyVarlist.length; i++) {
                stringBuffer.append("_");
                stringBuffer.append(objectToString(msg.getParam(cacheKeyVarlist[i])));
            }
            cacheKey = stringBuffer.toString();
        } else {
            if (cacheKeyModule == null || cacheKeyModule.isEmpty())
                cacheKeyModule = cacheForModle;
            if (cacheKeyMsg != null && !cacheKeyMsg.isEmpty()) {
                TLMsg keyMsg = putMsg(cacheKeyModule, createMsg().setAction(cacheKeyMsg));
                cacheKey = (String) keyMsg.getParam("cacheKey");
            }
        }
        moduleCacheParam.put("cacheName", cacheName);
        moduleCacheParam.put("cacheKey", cacheKey);
        moduleCacheParam.put("exptime", exptime);
        moduleCacheParam.put("cacheValueName", cacheValueName);
        moduleCacheParam.put("valueType", valueType);
        return moduleCacheParam;
    }
    protected String  objectToString(Object object){
        String result="";
        if(object instanceof  String)
            result= (String)object;
        else if(object instanceof Map)
        {
            Map map =(Map)object;
            for(Object key : map.keySet()){
                if(map.get(key) !=null)
                result =result+key.toString()+"="+map.get(key).toString();
            }
        }
        else if ( object instanceof List){
            for(int i=0 ;i < ((List)object).size() ;i++){
                result =result+((List)object).get(i).toString();
            }
        }
        else
            result = object.toString();
        return  result;
    }
    protected class myConfig extends TLModuleConfig {
        protected HashMap<String, HashMap<String, String>> caches;
        protected HashMap<String, HashMap<String, String>> modulesForCache;
        public myConfig(String configFile ,String configDir) {
            super(configFile,configDir);
        }
        public myConfig() {

        }

        public HashMap getCaches() {
            return caches;
        }

        public HashMap getModulesForCache() {
            return modulesForCache;
        }

        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if (xpp.getName().equals("servers")) {
                    caches = getHashMap(xpp, "servers", "cache");
                }
                if (xpp.getName().equals("modulesForCache")) {
                    modulesForCache = getHashMap(xpp, "modulesForCache", "module");
                }
            } catch (Throwable t) {

            }
        }

    }
}
