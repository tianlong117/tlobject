package cn.tianlong.tlobject.servletutils.utils;


import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.cache.TLFileCache;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.network.common.FileClass;
import cn.tianlong.tlobject.network.client.http.TLUrlUtils;
import org.apache.commons.io.FileUtils;


import java.io.*;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Map;

import static cn.tianlong.tlobject.cache.TLParamString.*;
import static cn.tianlong.tlobject.servletutils.utils.TLParamString.*;

public class TLUrlFileCache extends TLFileCache {

    protected String getFileMsgid ;
    protected String msgidOnProxy ="getUrlFile" ;
    public TLUrlFileCache() {
        super();
    }

    public TLUrlFileCache(String name) {
        super(name);
    }

    public TLUrlFileCache(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }
    @Override
    protected void initProperty(){
        super.initProperty();
        if( params!=null && params.get("getFileMsgid")!=null)
            getFileMsgid=params.get("getFileMsgid");
        if( params!=null && params.get("msgidOnProxy")!=null)
            msgidOnProxy=params.get("msgidOnProxy");
    }
    @Override
    protected TLMsg getCache(Object fromWho, TLMsg msg) {
        String urlStr = (String) msg.getParam(URLFILE_P_URL);
        String cacheName = (String) msg.getParam(CACHE_P_CACHENAME);
        java.net.URL url = null;
        try {
            url = new java.net.URL(urlStr);
        } catch (MalformedURLException e) {
            putLog("url error:" + urlStr, LogLevel.WARN, "getCache");
            return null;
        }
        String host = url.getHost();// 获取主机名
        String filePath = cachePath + File.separator + host ;
        String pfilePath = (String) msg.getParam(URLFILE_P_FILEPATH);
        if(pfilePath!=null)
            filePath = filePath + File.separator + pfilePath ;
        String urlFile = (String) msg.getParam(URLFILE_P_FILENAME);
        if(urlFile==null){
            urlFile = url.getFile();
            if(urlFile ==null || urlFile.isEmpty())
                urlFile=urlStr;
            else
                urlFile=urlFile.substring(1);
            urlFile=urlFile.replace("/", "_");
        }
        String cacheFileName =urlFile  ;
        if (cacheName != null)
            cacheFileName = cacheName + "_" + cacheFileName;
        cacheFileName =filePath +File.separator+cacheFileName ;
        File cacheFile = new File(cacheFileName);//获取缓存文件
        if (!cacheFile.exists() || cacheFile.length()==0L)              //判断文件是否存在
           return getFileFromHost(urlStr, filePath,cacheFileName, msg).setParam(URLFILE_P_FILEPATH,(String) msg.getParam(URLFILE_P_FILENAME));
         if (cacheName == null)
        {
            InputStream inputStream = null;
            try {
                inputStream = new FileInputStream(cacheFile);
            } catch (FileNotFoundException e) {
                return getFileFromHost(urlStr, filePath,cacheFileName, msg);
            }
            return createMsg().setParam(CACHE_P_VALUE, inputStream).setParam(URLFILE_R_CACHEFILE,cacheFileName)
                    .setParam(URLFILE_P_FILEPATH,(String) msg.getParam(URLFILE_P_FILENAME));
        }
        String cacheConfFile = cacheFileName + ".conf";
        File conffile = new File(cacheConfFile);//获取缓存文件
        if (conffile.exists())
        {
            String exptimeStr = null;
            try {
                exptimeStr = FileUtils.readFileToString(conffile, "UTF-8");
            } catch (IOException e) {
                return getFileFromHost(urlStr, filePath,cacheFileName, msg);
            }
            Long now = System.currentTimeMillis();
            Long exptime = Long.valueOf(exptimeStr);//判断文件缓存是否过期
            if (exptimeStr.equals("0") || exptime > now)
            {
                InputStream inputStream = null;
                try {
                    inputStream = new FileInputStream(cacheFile);
                } catch (FileNotFoundException e) {
                    return  getFileFromHost(urlStr, filePath,cacheFileName, msg);
                }
                return createMsg().setParam(CACHE_P_VALUE, inputStream).setParam(URLFILE_R_CACHEFILE,cacheFileName)
                        .setParam(URLFILE_P_FILEPATH,(String) msg.getParam(URLFILE_P_FILENAME));
            }
            else
                cacheFile.delete() ;
        }
         return  getFileFromHost(urlStr, filePath,cacheFileName, msg)
                 .setParam(URLFILE_P_FILEPATH,(String) msg.getParam(URLFILE_P_FILENAME));
    }

    protected TLMsg getFileFromHost(String urlStr,String filePath, String cacheFileName, TLMsg msg) {
        InputStream inputStream = getFileFromHost(urlStr, (Map<String, String>) msg.getParam("httpHeader"), (Map<String, String>) msg.getParam("cookie"), (Map<String, String>) msg.getParam("datas"));
        if (inputStream == null)
        {
            putLog(" get url file error:" + urlStr, LogLevel.WARN, "getFileFromHost");
            return createMsg().setParam(CACHE_P_VALUE, null);
        }
        String saveFileName=null ;
        try {
            saveFileName=saveFile(inputStream,filePath,cacheFileName);
            if( saveFileName !=null)
            {
                saveFileName=afterSaveFile(saveFileName,msg);
                String cacheName = (String) msg.getParam(CACHE_P_CACHENAME);
                if(saveFileName !=null && cacheName !=null)
                    saveConfFile(cacheName,saveFileName);
            }
        } catch (IOException e) {
            putLog("save cache file error:" + cacheFileName, LogLevel.ERROR);
            deleteCache(cacheFileName);
        }
        if(saveFileName ==null)
        //    return createMsg().setParam(CACHE_P_VALUE, inputStream);
            return createMsg().setParam(CACHE_P_VALUE, null);
        File cacheFile = new File(cacheFileName);//获取缓存文件
        InputStream inputStreamOfCache = null;
        try {
            inputStreamOfCache = new FileInputStream(cacheFile);
        } catch (FileNotFoundException e) {
            return createMsg().setParam(CACHE_P_VALUE, null);
        }
        return createMsg().setParam(CACHE_P_VALUE, inputStreamOfCache).setParam(URLFILE_R_CACHEFILE,cacheFileName);
    }

    protected InputStream getFileFromHost(String url, Map<String,String> httpHeader, Map<String,String> cookie, Map<String,String> datas) {
        if(getFileMsgid ==null )
                return TLUrlUtils.getUrlStream(url,httpHeader,cookie,datas);
        TLMsg gmsg =createMsg().setMsgId(getFileMsgid)
                .setParam("url",url)
                .setParam(MSG_P_MSGID,msgidOnProxy)
                .setParam("fileName",url)
                .setParam(WEBSOCKET_P_BINARYDATAIFRETURNSTREAM,true)
                .setParam("httpHeader",httpHeader);
        if(cookie !=null)
            gmsg.setParam("cookie",cookie);
        if(datas !=null)
            gmsg.setParam("datas",datas);
        TLMsg returnMsg = getMsg(this,gmsg);
        FileClass file = (FileClass) returnMsg.getParam("file");
        if(file ==null)
            return null ;
        InputStream inputStream = file.getInputStream();
        if(returnMsg ==null)
            return null ;
        return inputStream ;
    }

    private void deleteCache(String cacheFileName) {
        File file=new File(cacheFileName);
        if (file.exists()){
            file.delete();
        }
    }

    protected String afterSaveFile(String cacheFileName, TLMsg msg) {
        return cacheFileName;
    }

    protected void saveConfFile(String cacheName, String cacheFile) {
        String cacheConfFile = cacheFile + ".conf";
        String exptime="0";
        if(cacheTables !=null)
        {
            HashMap<String, String> dataHash=cacheTables.get(cacheName);
            if(dataHash!=null)
            {
                if(dataHash.get(CACHE_P_EXPTTIME)!= null && !dataHash.get(CACHE_P_EXPTTIME).isEmpty())
                  exptime=dataHash.get(CACHE_P_EXPTTIME);
            }
        }
        if(!exptime.equals("0"))
           exptime=String.valueOf(System.currentTimeMillis()+(Integer.parseInt(exptime)*60*1000));
        File conffile = new File(cacheConfFile);
        try {
            FileUtils.writeStringToFile(conffile,exptime);
        } catch (IOException e) {
            putLog("save cache conf file error:" + cacheConfFile, LogLevel.ERROR);
        }
    }

    protected String  saveFile(InputStream inputStream, String filePath, String fileName) throws IOException {

        File dir = new File(filePath);
        if (!dir.exists())      
            FileUtils.forceMkdir(dir);    
        byte[] buffer = new byte[4096];
        int readLenghth;
        FileOutputStream fileOutputStream = null;
        File file =new File(fileName);
        try {
            fileOutputStream = new FileOutputStream(file);
        } catch (FileNotFoundException e) {
            putLog("file dont create:"+fileName,LogLevel.ERROR);
            return null;
        }
        //创建的一个写出的缓冲流
        BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(fileOutputStream);
        //文件逐步写入本地
        try {
            while ((readLenghth = inputStream.read(buffer, 0, 4096)) != -1) {//先读出来，保存在buffer数组中
                bufferedOutputStream.write(buffer, 0, readLenghth);//再从buffer中取出来保存到本地
            }
        }catch (Exception e){
            putLog("write cache file error"+fileName,LogLevel.WARN,"saveFile");
            bufferedOutputStream.close();
            fileOutputStream.close();
            inputStream.close();
            if (file.exists()){
                file.delete();
            }
            return null ;
        }
        bufferedOutputStream.close();
        fileOutputStream.close();
        inputStream.close();
        return fileName;
    }
}