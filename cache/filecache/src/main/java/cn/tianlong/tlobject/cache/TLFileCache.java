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

    protected String cachePath ;
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

    public boolean writeCache(String cacheName,  String cacheKey,Object cacheValue ,int exptime ){
      return writeCache( cacheName,   cacheKey, cacheValue , exptime ,null);
    }
    @Override
    public boolean writeCache(String cacheName,  String cacheKey,Object cacheValue ,int exptime ,String valueType){
        if(cacheName==null || cacheName.isEmpty() ||  cacheTables ==null)
           return false ;
        cacheKey =checkCacheKey(cacheKey);
        Long cacheExptime =takeExptime(cacheName,exptime);
        if(cacheExptime  < 0L)
             return false ;
        try {
            cache(cacheName,cacheKey,cacheValue,cacheExptime);
            return true ;
        } catch (Exception e) {
            e.printStackTrace();
            putLog("cache write error,cacheName "+ cacheName+" cacheKey:"+cacheKey,LogLevel.ERROR,"writeCache");
            return false ;
        }
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

    protected    boolean  cache(String cacheName,String cacheKey,Object cacheValue,Long cacheExptime){

        HashMap<String,Object> cachedatas=new HashMap<>();
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

}