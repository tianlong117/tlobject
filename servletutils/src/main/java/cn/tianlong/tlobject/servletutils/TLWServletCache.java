package cn.tianlong.tlobject.servletutils;


import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.cache.TLCacheManager;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.HashMap;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLWServletCache extends TLCacheManager {

    public TLWServletCache(){
        super();
    }
    public TLWServletCache(String name ){
        super(name);
    }
    public TLWServletCache(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case "getCacheBeforeUrlMap":
                returnMsg= getCacheBeforeUrlMap(fromWho,msg);
                break;
            default:
                returnMsg=super.checkMsgAction(fromWho, msg)   ;
        }
        return returnMsg ;
    }

    private TLMsg getCacheBeforeUrlMap(Object fromWho, TLMsg msg) {
        TLMsg smsg=(TLMsg) msg.getParam(DOWITHMAG);
        TLMsg domsg = (TLMsg) smsg.getParam("domsg");
        HashMap<String, String> moduleCacheParam = getCacheParams(fromWho, domsg);
        if (moduleCacheParam == null)
            return null;
        String cacheName = moduleCacheParam.get("cacheName");
        String cacheKey = moduleCacheParam.get("cacheKey");
        String exptime = moduleCacheParam.get("exptime");
        String valueType = moduleCacheParam.get("valueType");
        TLMsg returnMsg = cache("getCache", cacheName, cacheKey, exptime, null, valueType);
        if (returnMsg == null)
        {
           TLMsg cacheMsg =createMsg().setDestination(name).setAction("writeCache").setParam("cacheParam",moduleCacheParam);
           TLMsg returMsg = putMsg("appCenter",createMsg().setAction("getSessionData")
                   .setParam("varname","outmsg"));
           TLMsg outMsg = (TLMsg) returMsg.getParam("value");
           if(outMsg == null)
               outMsg=cacheMsg;
           else
               outMsg.setNextMsg(cacheMsg);
            putMsg("appCenter",createMsg().setAction("setSessionData")
                    .setParam("varname","outmsg").setParam("value",outMsg));
            return null;
        }
        if (returnMsg != null)
            putLog("get  cache:" + cacheName + "_" + cacheKey, LogLevel.DEBUG);
        String cacheValueName = moduleCacheParam.get("cacheValueName");
        Object cacheValue = returnMsg.getParam("cacheValue");
        if (cacheValue == null)
            return null ;
        putMsg("directOutInterface",
                createMsg().setAction("putContentToUser").setParam("content",cacheValue));
        return createMsg().setParam(MODULE_DONEXTMSG,"false");
    }

}
