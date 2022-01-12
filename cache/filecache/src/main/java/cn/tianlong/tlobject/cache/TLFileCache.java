package cn.tianlong.tlobject.cache;


import cn.tianlong.tlobject.base.TLModuleConfig;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.lang3.StringUtils;
import org.xmlpull.v1.XmlPullParser;

import java.io.*;
import java.util.HashMap;

import static cn.tianlong.tlobject.cache.TLParamString.*;

public class TLFileCache extends TLBaseCache {

    protected HashMap<String, HashMap<String, String>> cacheTables ;
    protected String cachePath ;
    protected String defaultExptime ="0" ;
    protected   Gson gson = new Gson();
    public TLFileCache(){
        super();
    }
    public TLFileCache(String name ){
        super(name);
    }
    public TLFileCache(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }
    @Override
    protected void initProperty(){
        super.initProperty();
        if( params!=null && params.get("cachePath")!=null)
            cachePath=params.get("cachePath");
        else
            cachePath=System.getProperty("user.dir")+"\\cache\\";
        if( params!=null && params.get("defaultExptime")!=null)
            defaultExptime=params.get("defaultExptime");
    }

    protected Object setConfig(){
        myConfig config=new myConfig(configFile,moduleFactory.getConfigDir());;
        mconfig=config;
        super.setConfig();
        cacheTables=config.getCacheTables();
        return config;
    }
    public Object getCache(String cacheName,  String cacheKey){
        return  getCache(cacheName,  cacheKey,null) ;
    }
    @Override
    public Object getCache(String cacheName,  String cacheKey,String valueType){
        if(cacheName ==null ||  cacheTables ==null || !cacheTables.containsKey(cacheName))
            return this ;
        cacheKey =checkCacheKey(cacheKey);
        try {
            return get( cacheName, cacheKey);
        } catch (Exception e) {
            e.printStackTrace();
            putLog("cache get error,cacheName "+ cacheName+" cacheKey:"+cacheKey,LogLevel.ERROR,"getCache");
            return this ;
        }
    }

    @Override
    protected TLMsg getCache(Object fromWho, TLMsg msg)  {
        String cacheName=  msg.getStringParam(CACHE_P_CACHENAME,null);
        String cacheKey= msg.getStringParam(CACHE_P_KEY,null);
        cacheKey =checkCacheKey(cacheKey);
        Object cacheValue= getCache( cacheName, cacheKey,null);
        return createMsg().setParam(CACHE_P_VALUE,cacheValue);
    }

    public boolean writeCache(String cacheName,  String cacheKey,Object cacheValue ,int exptime ){
      return writeCache( cacheName,   cacheKey, cacheValue , exptime ,null);
    }
    @Override
    public boolean writeCache(String cacheName,  String cacheKey,Object cacheValue ,int exptime ,String valueType){
        if( cacheValue==null || cacheName==null || cacheName.isEmpty() ||  cacheTables ==null)
           return false ;
        HashMap<String, String> cacheParam=cacheTables.get(cacheName);
        if(cacheParam==null || cacheName.isEmpty())
           return  false ;
        cacheKey =checkCacheKey(cacheKey);
        if(exptime  < 0)
        {
           String  exptimeStr =cacheParam.get(CACHE_P_EXPTTIME);
            if(exptimeStr == null || exptimeStr.isEmpty())
                exptimeStr = defaultExptime;
            exptime =Integer.parseInt(exptimeStr);
        }
        try {
            cache(cacheName,cacheKey,cacheValue,exptime);
            return true ;
        } catch (Exception e) {
            e.printStackTrace();
            putLog("cache write error,cacheName "+ cacheName+" cacheKey:"+cacheKey,LogLevel.ERROR,"writeCache");
            return false ;
        }
    }

    @Override
    protected TLMsg writeCache(Object fromWho, TLMsg msg) {
        Object cacheValue=  msg.getParam(CACHE_P_VALUE);
        if( cacheValue==null)
            return createMsg().setParam(RESULT,false).setParam("isCache","false");
        String cacheName=  msg.getStringParam(CACHE_P_CACHENAME,null);
        String cacheKey= msg.getStringParam(CACHE_P_KEY,null);
        int exptime= msg.getIntParam(CACHE_P_EXPTTIME,-1);
        boolean result =writeCache( cacheName, cacheKey, cacheValue , exptime ,null);
        return createMsg().setParam(RESULT,result).setParam("isCache",result);
    }
    public boolean deleteCache(String cacheName,  String cacheKey){
       return deleteCache(cacheName, cacheKey,null) ;
    }
    @Override
    public boolean deleteCache(String cacheName,  String cacheKey,String valueType){
        if(cacheName ==null ||  cacheTables ==null || !cacheTables.containsKey(cacheName))
            return false ;
        cacheKey =checkCacheKey(cacheKey);
        try {
            delete(cacheName,cacheKey);
            return true ;
        } catch (Exception e) {
            e.printStackTrace();
            putLog("cache delete error,cacheName "+ cacheName+" cacheKey:"+cacheKey,LogLevel.ERROR,"deleteCache");
            return  false ;
        }
    }

    @Override
    protected TLMsg deleteCache(Object fromWho, TLMsg msg) {
        String cacheName=  msg.getStringParam(CACHE_P_CACHENAME,null);
        String cacheKey= msg.getStringParam(CACHE_P_KEY,null);
        Boolean result=deleteCache( cacheName,  cacheKey,null) ;
        return createMsg().setParam(RESULT,result).setParam("isCache",result);
    }

    protected String checkCacheKey(String cacheKey) {
        if(cacheKey==null)
            cacheKey="";
        else if(!cacheKey.isEmpty())
        {
            cacheKey=cacheKey.replace("/", "_");
            cacheKey=cacheKey.replace("?", "-");
            cacheKey=cacheKey.replace("\\", "_");
        }
        return cacheKey ;
    }

    protected    boolean  cache(String cacheName,String cacheKey,Object cacheValue,int time){

        HashMap<String,Object> cachedatas=new HashMap<>();
        Long cacheExptime ;
        if(time ==0)
            cacheExptime =0L ;
        else
            cacheExptime=System.currentTimeMillis()+(time*60*1000);
        cachedatas.put(CACHE_P_EXPTTIME,cacheExptime);
        cachedatas.put(CACHE_P_VALUE,cacheValue);
        GsonBuilder gsonBuilder = new GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss");
        String jsonString = gsonBuilder.serializeNulls().create().toJson(cachedatas);
        File file= null;//获取缓存文件
        try {
            file = getCacheFile( cacheName,cacheKey);
        } catch (Exception e) {
            putLog("缓存文件创建失败,cacheName:"+cacheName+"  cacheKey:"+cacheKey,LogLevel.ERROR);
            e.printStackTrace();
            return false ;
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
        } catch (FileNotFoundException e) {
            putLog("缓存文件没有发现,cacheName:"+cacheName+"  cacheKey:"+cacheKey,LogLevel.ERROR);
            e.printStackTrace();
            return false ;
        }
        try {
            out.write(jsonString.getBytes());
        } catch (IOException e) {
            putLog("缓存文件写错误,cacheName:"+cacheName+"  cacheKey:"+cacheKey,LogLevel.ERROR);
            e.printStackTrace();
            return false ;
        }
        try {
            out.close();
        } catch (IOException e) {
            putLog("缓存文件IO错误,cacheName:"+cacheName+"  cacheKey:"+cacheKey,LogLevel.ERROR);
            e.printStackTrace();
        }
        return true;
    }

    protected  Object get(String cacheName,String cacheKey)throws Exception{

        File file=new File(getCacheFileName( cacheName, cacheKey));//获取缓存文件
        if (file.exists())
        {//判断文件是否存在
            InputStream in = new FileInputStream(file);
            byte b[]=new byte[(int)file.length()];     //创建合适文件大小的数组
            in.read(b);    //读取文件中的内容到b[]数组
            in.close();
            String jsonStr=new String(b);
            HashMap<String, Object> cachedata = gson.fromJson(jsonStr,HashMap.class );
            Double cacheTime= (Double) cachedata.get(CACHE_P_EXPTTIME);//判断文件缓存是否过期
            long time =cacheTime.longValue();
            if (time ==0L )      // 0 为永不过期
                return cachedata.get(CACHE_R_VALUE);
            Long now =System.currentTimeMillis();
            if (time > now){
                return cachedata.get(CACHE_R_VALUE);
            }else{
                putLog("缓存过期:cacheName "+ cacheName+" cacheKey:"+cacheKey,LogLevel.DEBUG,"getCache");
                file.delete();//过期删除文件
                return this;
            }
        }
        putLog("缓存文件不存在:cacheName "+ cacheName+" cacheKey:"+cacheKey,LogLevel.DEBUG,"getCache");
        return this;
    }

    public void delete(String cacheName,String cacheKey){

        File file=new File(getCacheFileName( cacheName, cacheKey));
        if (file.exists()){
            file.delete();
        }
    }

    private  File getCacheFile(String cacheName,String cacheKey)throws Exception{

        File file=new File(getCacheFileName( cacheName, cacheKey));
        if (!file.getParentFile().exists()){
            file.getParentFile().mkdir();
        }
        if (!file.exists()){
            file.createNewFile();
        }else {
      //      file.delete();
      //      file.createNewFile();
        }
        return file;
    }

    protected   String getCacheFileName(String cacheName,String cacheKey){
        StringBuffer stringBuffer = new StringBuffer();
        stringBuffer.append(cachePath);
        stringBuffer.append(File.separator);
        stringBuffer.append(cacheName);
        stringBuffer.append(File.separator);
        stringBuffer.append(cacheName);
        stringBuffer.append("_");
        stringBuffer.append(cacheKey);
        stringBuffer.append(".json");
        String file =stringBuffer.toString();
        return file;
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