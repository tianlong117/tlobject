package cn.tianlong.tlobject.cache;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static cn.tianlong.tlobject.cache.TLParamString.*;

public class TLFileCache extends TLBaseCache {


    protected  String cachePath ;
    protected  Gson gson = new Gson();
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
        if( params!=null && params.get("fileLockNumber")!=null)
            fileLockNumber=params.get("fileLockNumber");
        ifUseLock=true ;
    }

    @Override
    protected TLBaseModule init() {
        super.init();
        File path=new File(cachePath);
        if(!path.exists())
        {
            boolean created = path.mkdirs();
            if(!created)
            {
                putLog("cachePath create failure : "+cachePath,LogLevel.ERROR);
                return null ;
            }
        }
        putLog("cachePath create : "+cachePath,LogLevel.DEBUG);
        return this;
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
            putLog("缓存读错误,cacheName "+ cacheName+" cacheKey:"+cacheKey,LogLevel.ERROR,"getCache");
            return this ;
        }
    }

    protected  Object get(String cacheName,String cacheKey)throws Exception{

        String filePath =getCacheFileName( cacheName, cacheKey) ;
        ReentrantReadWriteLock lock= getLock(filePath);
        ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
        readLock.lock();
        File file=new File(filePath);//获取缓存文件
        if (!file.exists())
        {
            putLog("缓存文件不存在: "+ filePath,LogLevel.DEBUG,"getCache");
            readLock.unlock();
            reBackLock();
            return this;
        }
        InputStream in = new FileInputStream(file);
        byte b[]=new byte[(int)file.length()];     //创建合适文件大小的数组
        in.read(b);    //读取文件中的内容到b[]数组
        in.close();
        readLock.unlock();
        reBackLock();
        String jsonStr=new String(b);
        HashMap<String, Object> cachedata ;
        try{
            cachedata = gson.fromJson(jsonStr,HashMap.class );
        }catch (Exception e){
            putLog("json 解析错误"+ cacheName+" cacheKey:"+cacheKey,LogLevel.ERROR,"getCache");
            return this ;
        }
        Double cacheTime= (Double) cachedata.get(CACHE_P_EXPTTIME);//判断文件缓存是否过期
        long expTime =cacheTime.longValue();
        if (expTime > System.currentTimeMillis() ||expTime ==0L )      // 0 为永不过期
            return cachedata.get(CACHE_R_VALUE);
        else {
            putLog("缓存过期:cacheName "+ cacheName+" cacheKey:"+cacheKey,LogLevel.DEBUG,"getCache");
            ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
            writeLock.lock();
            try{
                if(file.exists())
                {
                    try{
                        file.delete();//过期删除文件
                    }catch (Exception e){
                        e.printStackTrace();
                        putLog("缓存文件删除失败:"+filePath,LogLevel.ERROR,"getCache");
                    }
                }
                return this;
            }finally {
                writeLock.unlock();
                reBackLock();
            }
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
        return cache(cacheName,cacheKey,cacheValue,cacheExptime);
    }
    protected    boolean  cache(String cacheName,String cacheKey,Object cacheValue,Long cacheExptime)
    {
        String filePath =getCacheFileName( cacheName, cacheKey) ;
        File file=new File(filePath);
        File fileParent = file.getParentFile();
        if(!fileParent.exists()){
            fileParent.mkdirs();
        }
        ReentrantReadWriteLock lock= getLock(filePath);
        ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
        writeLock.lock();
        try{
            if (file.exists())
            {
                putLog("缓存文件已经存在,写缓存失败:"+filePath,LogLevel.DEBUG);
                return  false ;
            }
            try {
                file.createNewFile();
            } catch (IOException e) {
                putLog("缓存文件创建失败:"+filePath,LogLevel.ERROR);
                e.printStackTrace();
                return  false ;
            }
            FileOutputStream out;
            try {
                out = new FileOutputStream(file);
            } catch (FileNotFoundException e) {
                putLog("缓存文件没有发现:"+filePath,LogLevel.ERROR,"cache");
                e.printStackTrace();
                return false ;
            }
            HashMap<String,Object> cachedatas=new HashMap<>();
            cachedatas.put(CACHE_P_EXPTTIME,cacheExptime);
            cachedatas.put(CACHE_P_VALUE,cacheValue);
            GsonBuilder gsonBuilder = new GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss");
            String jsonString = gsonBuilder.serializeNulls().create().toJson(cachedatas);
            Boolean result=true ;
            try {
                out.write(jsonString.getBytes());
            } catch (IOException e) {
                putLog("缓存文件写错误:"+filePath,LogLevel.ERROR,"cache");
                e.printStackTrace();
                result=false;
            }
            try {
                out.close();
            } catch (IOException e) {
                putLog("缓存文件关闭错误:"+filePath,LogLevel.ERROR,"cache");
                e.printStackTrace();
                result=false;
            }
            return result;
        }finally {
            writeLock.unlock();
            reBackLock();
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
        String filePath =getCacheFileName( cacheName, cacheKey) ;
        ReentrantReadWriteLock lock= getLock(filePath);
        ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
        writeLock.lock();
        try{
            File file=new File(filePath);
            if (file.exists()){
                try{
                    file.delete();//过期删除文件
                    return true ;
                }catch (Exception e){
                    e.printStackTrace();
                    putLog("缓存文件删除失败:"+filePath,LogLevel.ERROR,"getCache");
                    return false ;
                }
            }
            return true ;
        }finally {
            writeLock.unlock();
            reBackLock();
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
        return stringBuffer.toString();
    }

}