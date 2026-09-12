package cn.tianlong.tlobject.network.proxy;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.network.client.http.TLUrlUtils;
import cn.tianlong.tlobject.utils.TLDataUtils;
import cn.tianlong.tlobject.utils.TLMsgUtils;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Connection;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static cn.tianlong.tlobject.cache.TLParamString.*;
import static cn.tianlong.tlobject.cache.TLParamString.CACHE_R_VALUE;
import static cn.tianlong.tlobject.cache.TLParamString.M_URLFILECACHE;
import static cn.tianlong.tlobject.cache.TLParamString.M_URLIMAGECACHE;


/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLHttpProxy extends TLBaseModule {
   protected  String  urlHost ;
    protected ArrayList<String> staticFileType =new ArrayList<>();
    public TLHttpProxy() {
        super();
    }

    public TLHttpProxy(String name) {
        super(name);
    }

    public TLHttpProxy(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        // staticFileType 原来声明后从未被填充 → urlIsStatic 恒返回 false → getUrlFile 的缓存分支永不生效。
        // 从配置读（分号分隔的扩展名列表，如 "jpg;png;css;js"）
        if (params != null && params.get("staticFileType") != null) {
            String[] types = TLDataUtils.splitStrToArray(params.get("staticFileType"), ";");
            if (types != null) {
                for (String t : types) {
                    if (t != null && !t.trim().isEmpty())
                        staticFileType.add(t.trim());
                }
            }
        }
    }

    @Override
    protected TLBaseModule init() {

        return this ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "getUrlFile":
                getUrlFile( fromWho,  msg);
                break;
            case "getUrlImageAndCompress":
                getUrlImageAndCompress( fromWho,  msg);
                break;
            case "postReturnDoc":
                returnMsg = postReturnDoc( fromWho,  msg);
                break;
            case "postFormReturnDoc":
                returnMsg =postFormReturnDoc( fromWho,  msg);
                break;
            default:                    ;
        }
        return returnMsg;
    }

    private void getUrlImageAndCompress(Object fromWho, TLMsg msg) {
        String  url = (String) msg.getParam("url");
        TLMsg gMsg =createMsg()
                .setParam(CACHE_P_CACHENAME,"imagecache")
                .setAction(CACHE_GETCACHE)
                .setParam("url",url);
        TLMsg returnMsg = putMsg(M_URLIMAGECACHE,gMsg);
        if(returnMsg ==null)
            return;
        InputStream inputStream = (InputStream) returnMsg.getParam(CACHE_R_VALUE);
        if (inputStream ==null)
            return;
        msg.setParam("fileName",url);
        putFile(inputStream,msg);
        return;
    }

    private TLMsg postFormReturnDoc(Object fromWho, TLMsg msg) {
        Map<String,Object> postData = (Map<String, Object>) msg.getParam(MSG_P_PARAMS);
        ArrayList<HashMap<String,Object>> fileList = (ArrayList<HashMap<String, Object>>) msg.getParam(WEBSOCKET_R_RECEIVEDFILEGROUP);
        HashMap<String,String> fileMap =new HashMap<>();
        for(HashMap<String,Object> file :fileList){
            String filename = (String) file.get("fileName");
            String realfilename = (String) file.get("realFileName");
            fileMap.put(realfilename,filename);
        }
        TLMsg pmsg=createMsg().addMap(postData).setParam("files",fileMap);
        return postReturnDoc(this,pmsg);
    }

    private TLMsg postReturnDoc(Object fromWho, TLMsg msg) {
        String url = (String) msg.getParam("url");
        if(url ==null)
            return null;
        Connection.Response response = TLUrlUtils.postToHost(url, msg);
        if(response ==null)
            return null;
        HashMap<String,Object> datas = new HashMap<>();
        datas.put("cookie",response.cookies());
        datas.put("statusCode",response.statusCode());
        try {
            String body =response.parse().toString();
            datas.put("body",body);
        } catch (IOException e) {
            e.printStackTrace();
        }
        TLMsg resultMsg = createMsg().setArgs(datas);
        return createMsg().setArgs(TLMsgUtils.msgToSocketDataMap(resultMsg));
    }

    private void putData(HashMap<String,Object> datas) {
        TLMsg gmsg =createMsg().setAction(WEBSOCKET_SENDFILE).setArgs(datas);
        putMsg(M_SOCKETCLIENTAGENTPOOL,gmsg);
    }

    private void getUrlFile(Object fromWho, TLMsg msg) {
        String url = (String) msg.getParam("url");
        if(url ==null)
            return;
        if(!TLUrlUtils.isHttpURL(url))
            return;
        String host = (String) msg.getParam("host");
        url =makeUrl(url,host);
        boolean isStatic =urlIsStatic(url);
        // 原来 https 时会调 TLUrlUtils.trustEveryoneForHttps()：那是 JVM 级全局设置，
        // 一旦调用，本进程内所有 HttpsURLConnection 都不再校验证书与主机名（影响面远超本模块）。
        // 自签名证书应通过 truststore 配置解决，不能全局关校验。
        InputStream inputStream ;
        if(isStatic)
           inputStream = getCacheFile(url);
        else
           inputStream =TLUrlUtils.getUrlStream(url,msg);
        if(inputStream ==null)
            return;
        msg.setParam("fileName",url);
        putFile(inputStream,msg);
        return;
    }

    private String makeUrl(String url, String host) {
        if(host !=null)
            url =host+ url ;
        else if(urlHost !=null)
            url =urlHost+ url ;
        return url ;
    }

    private boolean urlIsStatic(String url) {
       String urlType =StringUtils.substringAfterLast(url,".");
       if(urlType ==null || urlType.isEmpty())
           return false ;
        return  staticFileType.contains(urlType);
    }

    private InputStream getCacheFile(String url) {
        TLMsg returnMsg = putMsg(M_URLFILECACHE,createMsg()
                .setAction(CACHE_GETCACHE).setParam("url",url));
        return (InputStream) returnMsg.getParam(CACHE_R_VALUE);
    }
    private void putFile(InputStream cacheFileInputStream, TLMsg msg) {
        TLMsg gmsg =createMsg().setAction(WEBSOCKET_SENDFILE).setArgs(msg.getArgs()).setParam(WEBSOCKET_P_SENDINPUTSTREAM,cacheFileInputStream);
         putMsg(M_SOCKETCLIENTAGENTPOOL,gmsg);
    }

}
