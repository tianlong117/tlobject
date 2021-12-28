package cn.tianlong.tlobject.cache;


import cn.tianlong.tlobject.base.TLModuleConfig;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.xmlpull.v1.XmlPullParser;

import java.io.*;
import java.util.HashMap;

import static cn.tianlong.tlobject.cache.TLParamString.*;

public class TLFileCache extends TLBaseCache {

    protected HashMap<String, HashMap<String, String>> cacheTables ;
    protected String cachePath ;
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
        try {
            return get( cacheName, cacheKey);
        } catch (Exception e) {
            e.printStackTrace();
            putLog("cache error,key: "+ cacheName+cacheKey,LogLevel.ERROR,"getCache");
            return this ;
        }
    }
    public boolean writeCache(String cacheName,  String cacheKey,Object cacheValue ,int exptime ){
      return writeCache( cacheName,   cacheKey, cacheValue , exptime ,null);
    }
    @Override
    public boolean writeCache(String cacheName,  String cacheKey,Object cacheValue ,int exptime ,String valueType){
        try {
            cache(cacheName,cacheKey,cacheValue,exptime);
            return true ;
        } catch (Exception e) {
            e.printStackTrace();
            putLog("cache error,key: "+ cacheName+cacheKey,LogLevel.ERROR,"writeCache");
            return false ;
        }
    }
    public boolean deleteCache(String cacheName,  String cacheKey){
       return deleteCache(cacheName, cacheKey,null) ;
    }
    @Override
    public boolean deleteCache(String cacheName,  String cacheKey,String valueType){
        try {
            delete(cacheName,cacheKey);
            return true ;
        } catch (Exception e) {
            e.printStackTrace();
            putLog("cache error,key: "+ cacheName+cacheKey,LogLevel.ERROR,"deleteCache");
            return  false ;
        }
    }
    @Override
    protected TLMsg getCache(Object fromWho, TLMsg msg)  {
        String[] cacheParam=getCacheParam(msg);
        String cacheName=cacheParam[0];
        String cacheKey=cacheParam[1];
        if(cacheName.equals("false"))
            return createMsg().setParam(CACHE_P_VALUE,null);
        try {
            Object cacheValue= get( cacheName, cacheKey);
            return createMsg().setParam(CACHE_P_VALUE,cacheValue);
        } catch (Exception e) {
            return createMsg().setParam(CACHE_P_VALUE,this);
        }
    }

    @Override
    protected TLMsg deleteCache(Object fromWho, TLMsg msg) {
        String[] cacheParam=getCacheParam(msg);
        String cacheName=cacheParam[0];
        String cacheKey=cacheParam[1];
        if(cacheName.equals("false"))
            return null ;
        try {
            delete(cacheName,cacheKey);
            return createMsg().setParam("isCache","false");
        } catch (Exception e) {
           return  null;
        }
    }

    private String[] getCacheParam(TLMsg msg){
        String cacheName= (String) msg.getParam(CACHE_P_CACHENAME);
        String cacheKey= (String) msg.getParam(CACHE_P_KEY);
        String exptime=(String) msg.getParam(CACHE_P_EXPTTIME);
        if(cacheName==null || cacheName.isEmpty())
            return new String[]{"false","false"};
        if(cacheKey==null)
            cacheKey="";
        else if(!cacheKey.isEmpty())
        {
            cacheKey=cacheKey.replace("/", "_");
            cacheKey=cacheKey.replace("?", "-");
            cacheKey=cacheKey.replace("\\", "_");
        }
        if(cacheTables !=null && (exptime==null || exptime.isEmpty()))
        {
            HashMap<String, String> dataHash=cacheTables.get(cacheName);
            if(dataHash==null)
                return new String[]{"false","0"};
            exptime=dataHash.get(CACHE_P_EXPTTIME);
            if(exptime==null )
            {
                if( params!=null && params.get(CACHE_P_EXPTTIME)!=null)
                    exptime=params.get(CACHE_P_EXPTTIME);
                else
                    exptime="600";
            }
        }
        return new String[]{cacheName,cacheKey,exptime}  ;
    }
    @Override
    protected TLMsg writeCache(Object fromWho, TLMsg msg) {
        String[] cacheParam=getCacheParam(msg);
        String cacheName=cacheParam[0];
        String cacheKey=cacheParam[1];
        if(cacheName.equals("false"))
            return null ;
        int exptime= Integer.parseInt(cacheParam[2]);
        Object cacheValue=  msg.getParam(CACHE_P_VALUE);
        if( cacheValue==null)
            return createMsg().setParam("isCache","false");
        try {
            cache(cacheName,cacheKey,cacheValue,exptime);
            return createMsg().setParam("isCache","true");
        } catch (Exception e) {
            return createMsg().setParam("isCache","false");
        }
    }

    private    boolean  cache(String cacheName,String cacheKey,Object cacheValue,int time) throws Exception{

        HashMap<String,Object> cachedatas=new HashMap<>();
        String exptime=System.currentTimeMillis()+(time*60*1000)+"";
        cachedatas.put(CACHE_P_EXPTTIME,exptime);
        cachedatas.put(CACHE_P_VALUE,cacheValue);
        GsonBuilder gsonBuilder = new GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss");
        String jsonString = gsonBuilder.serializeNulls().create().toJson(cachedatas);
        File file=getCacheFile( cacheName,cacheKey);//获取缓存文件
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        try {
            out.write(jsonString.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return true;
    }

    protected  Object get(String cacheName,String cacheKey)throws Exception{
        Object cacheValue;
        File file=new File(getCacheFileName( cacheName, cacheKey));//获取缓存文件
        if (file.exists())
        {//判断文件是否存在
            InputStream in = new FileInputStream(file);
            byte b[]=new byte[(int)file.length()];     //创建合适文件大小的数组
            in.read(b);    //读取文件中的内容到b[]数组
            in.close();
            String jsonStr=new String(b);
            Gson gson = new Gson();
            HashMap<String, String> cachedata ;
            cachedata = gson.fromJson(jsonStr,HashMap.class );
            Long now =System.currentTimeMillis();
            Long time= Long.valueOf(cachedata.get(CACHE_P_EXPTTIME));//判断文件缓存是否过期
            if (time>now){
                cacheValue=cachedata.get(CACHE_R_VALUE);
                return cacheValue;
            }else{
         //       file.delete();;//过期删除文件
                return this;
            }
        }
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