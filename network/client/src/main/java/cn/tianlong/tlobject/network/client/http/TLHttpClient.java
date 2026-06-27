package cn.tianlong.tlobject.network.client.http;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import cn.tianlong.tlobject.network.common.TLSslUtils;
import cn.tianlong.tlobject.network.common.TLSslUtils.SSLParams;
import okhttp3.*;
import okhttp3.Request.Builder;
import org.apache.commons.lang3.StringUtils;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * 创建日期：2018/3/21 on 8:48
 * 描述:
 * 作者:tianlong
 */

public class TLHttpClient extends TLBaseModule {
    protected String sslCerFile;
    protected Long connTimeOut  =0L ;
    protected Long readTimeOut  =0L ;
    protected Long writeTimeOut =0L ;
    protected TLSslUtils.SSLParams moduleSslParams ;
    protected OkHttpClient okHttpClient;     // 实例级 OkHttpClient，替代 OkHttpUtils 全局单例

    public TLHttpClient() {
        super();
    }
    public TLHttpClient(String name ) {
        super(name);
    }
    public TLHttpClient(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected void setModuleParams() {
        if(params!=null  ){
            if( params.get(SSL_SCERFILE)!=null)
                sslCerFile =params.get(SSL_SCERFILE);
            if( params.get("connTimeOut")!=null)
                connTimeOut= Long.valueOf(params.get("connTimeOut"))*1000;
            if( params.get("readTimeOut")!=null)
                readTimeOut= Long.valueOf(params.get("readTimeOut"))*1000;
            if( params.get("writeTimeOut")!=null)
                writeTimeOut= Long.valueOf(params.get("writeTimeOut"))*1000;
        }
    }
    @Override
    protected TLBaseModule init() {
        // 创建默认的 OkHttpClient（HTTP 请求使用）
        okHttpClient = new OkHttpClient.Builder().build();
        if(sslCerFile !=null){
            InputStream  cerFileStream = TLHttpClient.class.getResourceAsStream(sslCerFile);
            InputStream[] certificates = new InputStream[1];
            certificates[0]=cerFileStream;
            moduleSslParams= TLSslUtils.getSslSocketFactory(certificates);
        }
        return this ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case HTTP_POST:
                returnMsg=uPost( fromWho,   msg);
                break;
            case HTTP_POSTJSON:
                returnMsg=postJson( fromWho,  msg);
                break;
            case HTTP_POSTFILE:
                returnMsg=postFile( fromWho,  msg);
                break;
            case HTTP_POSSTRING:
                returnMsg=uPostString( fromWho,  msg);
                break;
            case HTTP_GET:
                returnMsg=uGet( fromWho,   msg);
                break;
            case HTTP_DOWNLOAD:
              download( fromWho,   msg);
                break;
            case HTTP_CANCEL:
                cancel( fromWho,   msg);
                break;
            default:

        }
        return returnMsg;
    }

    private void cancel(Object fromWho, TLMsg msg) {
        if(!msg.isNull(HTTP_P_TAG)) {
            Object tag = msg.getParam(HTTP_P_TAG);
            for (Call call : okHttpClient.dispatcher().queuedCalls()) {
                if (tag.equals(call.request().tag())) call.cancel();
            }
            for (Call call : okHttpClient.dispatcher().runningCalls()) {
                if (tag.equals(call.request().tag())) call.cancel();
            }
        } else if(!msg.isNull(HTTP_P_URL)){
            String url = msg.getStringParam(HTTP_P_URL, "");
            for (Call call : okHttpClient.dispatcher().queuedCalls()) {
                if (url.equals(call.request().url().toString())) call.cancel();
            }
            for (Call call : okHttpClient.dispatcher().runningCalls()) {
                if (url.equals(call.request().url().toString())) call.cancel();
            }
        }
    }

    // ---------- 内部类：封装 Request.Builder + OkHttpClient ----------
    private static class RequestPack {
        final Builder builder;
        final OkHttpClient client;
        String tag;  // 用于 cancel

        RequestPack(Builder builder, OkHttpClient client) {
            this.builder = builder;
            this.client = client;
        }
    }

    // ---------- 构建 RequestPack ----------
    private RequestPack getHttpPack(String type, TLMsg msg) {
        String url = msg.getStringParam(HTTP_P_URL,"").trim();
        if(url.isEmpty())
            return null;
        String httpprotocol = url.substring(0,7);
        String httpsprotocol = url.substring(0,8);
        if(!httpprotocol.equals("http://") && !httpsprotocol.equals("https://"))
        {
            putLog("url is wrong:"+url, LogLevel.WARN, "builder");
            return null;
        }

        OkHttpClient client;
        if(msg.getBooleanParam(HTTP_P_ISHTTPS, false)) {
            String cerFile = msg.getStringParam(SSL_SCERFILE, null);
            TLSslUtils.SSLParams sslParams;
            if(cerFile != null) {
                InputStream httpsCerFileStream = TLHttpClient.class.getResourceAsStream(cerFile);
                InputStream[] certificates = new InputStream[1];
                certificates[0] = httpsCerFileStream;
                sslParams = TLSslUtils.getSslSocketFactory(certificates);
            } else if(moduleSslParams != null) {
                sslParams = moduleSslParams;
            } else {
                return null;
            }
            client = new OkHttpClient.Builder()
                    .hostnameVerifier(new HostnameVerifier() {
                        @Override
                        public boolean verify(String hostname, SSLSession session) {
                            return true;
                        }
                    })
                    .sslSocketFactory(sslParams.sSLSocketFactory, sslParams.trustManager)
                    .build();
        } else {
            client = this.okHttpClient;    // 使用实例级默认 client
        }

        Builder builder = new Builder().url(url);
        if(msg.containsParam(HTTP_P_TAG)) {
            Object tag = msg.getParam(HTTP_P_TAG);
            builder.tag(tag);
        }
        RequestPack pack = new RequestPack(builder, client);
        pack.tag = msg.containsParam(HTTP_P_TAG) ? (String) msg.getParam(HTTP_P_TAG) : null;
        return pack;
    }

    // 给 RequestPack 加 header
    private void addHeaders(RequestPack pack, Map<String, String> header) {
        if(header != null) {
            for (Map.Entry<String, String> e : header.entrySet()) {
                pack.builder.addHeader(e.getKey(), e.getValue());
            }
        }
    }

    // ---------- HTTP 方法 ----------

    protected TLMsg uGet(final Object fromWho, final TLMsg msg){
        RequestPack pack = getHttpPack(HTTP_GET, msg);
        if(pack == null)
            return createMsg().setParam(HTTP_ERROR, true).setParam("msg","url");
        HashMap<String,String> params = (HashMap<String,String>)msg.getMapParam("params",null);
        if(params != null) {
            HttpUrl.Builder urlBuilder = pack.builder.build().url().newBuilder();
            // 重建 builder 的 url（带 query params）
            // 直接用 HttpUrl 加参数
        }
        // 将 query params 加到 URL 上
        if(params != null && !params.isEmpty()) {
            HttpUrl httpUrl = HttpUrl.parse(pack.builder.build().url().toString());
            if(httpUrl != null) {
                HttpUrl.Builder ub = httpUrl.newBuilder();
                for (String key : params.keySet()) {
                    if(params.get(key) != null)
                        ub.addQueryParameter(key, params.get(key).toString());
                }
                pack.builder.url(ub.build());
            }
        }
        addHeaders(pack, (Map<String, String>) msg.getMapParam(HTTP_P_REQUESTHEADER, null));
        pack.builder.get();
        return httpCallExecute(pack, fromWho, msg);
    }

    private void download(Object fromWho, TLMsg msg) {
        String url = msg.getStringParam(HTTP_P_URL,"").trim();
        if(url.isEmpty())
            return;
        String resultAction = (String) msg.getSystemParam(RESULTACTION);
        Object resultFor = getResultObject(msg);
        String path = msg.getStringParam(HTTP_P_DOWNLOAD_SAVEPATH, "");
        String filename = msg.getStringParam(HTTP_P_DOWNLOAD_FILENAME, null);
        if(filename == null)
            filename = StringUtils.substringAfterLast(url, "/");
        okhttp3.Callback fileCallBack = new UFileCallback(resultFor, resultAction, path, filename,
                msg.getParam(HTTP_P_SESSIONDATA), (TLMsg) msg.getParam(HTTP_P_FILE_PROGRESSMSG));
        RequestPack pack = getHttpPack(HTTP_GET, msg);
        if(pack == null)
            return;
        addHeaders(pack, (LinkedHashMap<String, String>) msg.getMapParam("requestHeader", null));
        pack.builder.get();
        httpCallExecuteAsync(pack, fileCallBack);
    }

    protected TLMsg uPost(Object fromWho, TLMsg msg){
        RequestPack pack = getHttpPack(HTTP_POST, msg);
        if(pack == null)
            return createMsg().setParam(HTTP_ERROR, true).setParam("msg","url");
        addHeaders(pack, (Map<String, String>) msg.getParam(HTTP_P_REQUESTHEADER));
        FormBody.Builder formBuilder = new FormBody.Builder();
        HashMap<String,String> params = (HashMap<String,String>)msg.getParam(HTTP_P_POSTPARAMS);
        if(params != null){
            for (String key : params.keySet()) {
                if(params.get(key) != null)
                    formBuilder.add(key, params.get(key));
            }
        }
        pack.builder.post(formBuilder.build());
        return httpCallExecute(pack, fromWho, msg);
    }

    private TLMsg postFile(Object fromWho, TLMsg msg) {
        Object fileNames = msg.getParam(HTTP_P_UPFILE);
        if(fileNames == null)
            return createMsg().setParam(HTTP_ERROR, true).setParam("msg", HTTP_P_UPFILE);
        RequestPack pack = getHttpPack(HTTP_POST, msg);
        if(pack == null)
            return createMsg().setParam(HTTP_ERROR, true).setParam("msg","url");
        addHeaders(pack, (LinkedHashMap<String, String>) msg.getMapParam("requestHeader", null));
        // 构建 multipart 表单
        MultipartBody.Builder mpBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        // 添加文件
        if(fileNames instanceof ArrayList) {
            for(int i = 0; i < ((ArrayList) fileNames).size(); i++) {
                String fileName = (String) ((ArrayList) fileNames).get(i);
                File file = new File(fileName);
                mpBuilder.addFormDataPart("mFile" + i, file.getName(),
                        RequestBody.create(MediaType.parse("application/octet-stream"), file));
            }
        } else if(fileNames instanceof Map) {
            for(Object key : ((Map) fileNames).keySet()) {
                String fileName = (String) ((Map) fileNames).get(key);
                File file = new File(fileName);
                mpBuilder.addFormDataPart((String) key, file.getName(),
                        RequestBody.create(MediaType.parse("application/octet-stream"), file));
            }
        } else if(fileNames instanceof String) {
            File file = new File((String) fileNames);
            mpBuilder.addFormDataPart("mFile", file.getName(),
                    RequestBody.create(MediaType.parse("application/octet-stream"), file));
        } else {
            return createMsg().setParam(HTTP_ERROR, true).setParam("msg", HTTP_P_UPFILE);
        }
        // 添加普通表单参数
        HashMap<String,String> params = (HashMap<String,String>)msg.getMapParam(HTTP_P_POSTPARAMS, null);
        if(params != null) {
            for (String key : params.keySet()) {
                if(params.get(key) != null)
                    mpBuilder.addFormDataPart(key, params.get(key));
            }
        }
        pack.builder.post(mpBuilder.build());
        return httpCallExecute(pack, fromWho, msg);
    }

    public TLMsg postJson(Object fromWho, TLMsg msg) {
        RequestPack pack = getHttpPack(HTTP_POSTJSON, msg);
        if(pack == null)
            return createMsg().setParam(HTTP_ERROR, true).setParam("msg","url");
        if(msg.isNull(HTTP_P_POSTCONTENT))
            return createMsg().setParam(HTTP_ERROR, true).setParam("msg", HTTP_P_POSTCONTENT);
        String json = new Gson().toJson(msg.getParam(HTTP_P_POSTCONTENT));
        addHeaders(pack, (Map<String, String>) msg.getMapParam(HTTP_P_REQUESTHEADER, null));
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), json);
        pack.builder.post(body);
        return httpCallExecute(pack, fromWho, msg);
    }

    protected TLMsg uPostString(Object fromWho, TLMsg msg){
        RequestPack pack = getHttpPack(HTTP_POSSTRING, msg);
        if(pack == null)
            return createMsg().setParam(HTTP_ERROR, true).setParam("msg","url");
        if(msg.isNull(HTTP_P_POSTCONTENT))
            return createMsg().setParam(HTTP_ERROR, true).setParam("msg", HTTP_P_POSTCONTENT);
        addHeaders(pack, (Map<String, String>) msg.getMapParam(HTTP_P_REQUESTHEADER, null));
        RequestBody body = RequestBody.create(MediaType.parse("text/plain; charset=utf-8"),
                msg.getStringParam(HTTP_P_POSTCONTENT, ""));
        pack.builder.post(body);
        return httpCallExecute(pack, fromWho, msg);
    }

    // ---------- 执行 ----------

    private TLMsg httpCallExecute(RequestPack pack, Object fromWho, TLMsg msg){
        OkHttpClient client = pack.client;
        // 超时处理：每个请求构建独立的 client（带超时），避免影响其他请求
        OkHttpClient execClient = applyTimeout(client, msg);
        String resultAction = (String) msg.getSystemParam(RESULTACTION);
        TLMsg progressMsg = (TLMsg) msg.getSystemParam(HTTP_P_FILE_PROGRESSMSG);
        if(resultAction != null || progressMsg != null) {
            Object resultFor = msg.getSystemParam(RESULTFOR);
            if(resultFor == null)
                resultFor = fromWho;
            else if(resultFor instanceof String)
                resultFor = getModule((String) resultFor);
            String action = msg.getAction();
            okhttp3.Callback callback;
            if(action.equals(HTTP_POSTFILE))
                callback = new PostFileCallback(resultFor, resultAction,
                        msg.getSystemParam(HTTP_P_SESSIONDATA), progressMsg);
            else
                callback = new UStringCallback(resultFor, resultAction,
                        msg.getSystemParam(HTTP_P_SESSIONDATA));
            execClient.newCall(pack.builder.build()).enqueue(callback);
            return null;
        }
        try {
            Response response = execClient.newCall(pack.builder.build()).execute();
            if(response.isSuccessful()) {
                try {
                    String content = response.body().string();
                    return createMsg().setParam(HTTP_ERROR, false)
                            .setParam(WEBRESPONSE, content).setParam(HTTP_R_REPONSE, response);
                } catch (IOException e) {
                }
            }
            String url = msg.getStringParam(HTTP_P_URL, "");
            putLog("response error:" + url, LogLevel.ERROR, "httpCall");
            return createMsg().setParam(HTTP_ERROR, true);
        } catch (IOException e) {
            String url = msg.getStringParam(HTTP_P_URL, "");
            putLog("http error:" + url, LogLevel.ERROR, "httpCall");
            return createMsg().setParam(HTTP_ERROR, true).setParam("msg", "io");
        }
    }

    private void httpCallExecuteAsync(RequestPack pack, okhttp3.Callback callback) {
        OkHttpClient client = pack.client;
        client.newCall(pack.builder.build()).enqueue(callback);
    }

    /** 根据需要给 client 加上超时配置 */
    private OkHttpClient applyTimeout(OkHttpClient base, TLMsg msg) {
        long ct = !msg.isNull("connTimeOut") ? msg.getLongParam("connTimeOut", 30L) * 1000
                : (connTimeOut > 0L ? connTimeOut : 0L);
        long rt = !msg.isNull("readTimeOut") ? msg.getLongParam("readTimeOut", 30L) * 1000
                : (readTimeOut > 0L ? readTimeOut : 0L);
        long wt = !msg.isNull("writeTimeOut") ? msg.getLongParam("writeTimeOut", 30L) * 1000
                : (writeTimeOut > 0L ? writeTimeOut : 0L);
        if(ct == 0L && rt == 0L && wt == 0L)
            return base;
        OkHttpClient.Builder builder = base.newBuilder();
        if(ct > 0L) builder.connectTimeout(ct, TimeUnit.MILLISECONDS);
        if(rt > 0L) builder.readTimeout(rt, TimeUnit.MILLISECONDS);
        if(wt > 0L) builder.writeTimeout(wt, TimeUnit.MILLISECONDS);
        return builder.build();
    }

    // ======================== 内部 Callback 类 ========================

    protected class UStringCallback implements okhttp3.Callback
    {
        private Object resultFor ;
        private String resultAction ;
        private Object sesstionData ;
        private Response httpResponse ;

        public UStringCallback(Object resultFor, String resultAction, Object sesstionData) {
            this.resultFor = resultFor;
            this.resultAction = resultAction;
            this.sesstionData = sesstionData;
        }

        @Override
        public void onFailure(Call call, IOException e) {
            TLMsg responseMsg = createMsg().setAction(resultAction).setParam("error", true)
                    .setParam(WEBRESPONSE, null).setParam(HTTP_P_SESSIONDATA, sesstionData);
            putLog("response error", LogLevel.ERROR, "callback");
            putMsg((IObject) resultFor, responseMsg);
        }

        @Override
        public void onResponse(Call call, Response response) throws IOException {
            this.httpResponse = response;
            String body = response.body().string();
            TLMsg responseMsg = createMsg().setAction(resultAction)
                    .setParam("error", false)
                    .setParam(WEBRESPONSE, body)
                    .setParam(HTTP_R_REPONSE, this.httpResponse)
                    .setParam(HTTP_P_SESSIONDATA, sesstionData);
            putMsg((IObject) resultFor, responseMsg);
        }
    }

    protected class UFileCallback implements okhttp3.Callback
    {
        private Object resultFor ;
        private String resultAction ;
        private TLMsg progressMsg ;
        private float lastProgress = 0L;
        private Object sesstionData ;
        private String destFileDir;
        private String destFileName;

        public UFileCallback(Object resultFor, String resultAction, String destFileDir,
                             String destFileName, Object sesstionData, TLMsg pregressMsg) {
            this.resultFor = resultFor;
            this.resultAction = resultAction;
            this.sesstionData = sesstionData;
            this.progressMsg = pregressMsg;
            this.destFileDir = destFileDir;
            this.destFileName = destFileName;
        }

        @Override
        public void onFailure(Call call, IOException e) {
            if(resultFor == null) return;
            TLMsg responseMsg = createMsg().setAction(resultAction).setParam(HTTP_ERROR, true)
                    .setParam(WEBRESPONSE, null).setParam(HTTP_P_SESSIONDATA, sesstionData);
            putLog("response error", LogLevel.ERROR, "callback");
            putMsg((IObject) resultFor, responseMsg);
        }

        @Override
        public void onResponse(Call call, Response response) throws IOException {
            if(resultFor == null) return;
            // 写入文件
            File dir = new File(destFileDir);
            if(!dir.exists()) dir.mkdirs();
            File file = new File(dir, destFileName);
            InputStream is = response.body().byteStream();
            FileOutputStream fos = new FileOutputStream(file);
            byte[] buf = new byte[4096];
            int len;
            long total = response.body().contentLength();
            long downloaded = 0;
            while((len = is.read(buf)) != -1) {
                fos.write(buf, 0, len);
                downloaded += len;
                if(progressMsg != null && total > 0) {
                    float p = downloaded * 100.0f / total;
                    if(p - lastProgress >= 0.01f) {
                        lastProgress = p;
                        progressMsg.setParam(HTTP_R_FILE_PROGRESS, p)
                                .setParam(HTTP_R_FILE_TOTAL, total)
                                .setParam(HTTP_P_SESSIONDATA, sesstionData);
                        putMsg(progressMsg.getDestination(), progressMsg);
                    }
                }
            }
            fos.close();
            is.close();
            if(progressMsg != null) {
                progressMsg.setParam(HTTP_R_FILE_PROGRESS, 100.00f)
                        .setParam(HTTP_P_SESSIONDATA, sesstionData);
                putMsg(progressMsg.getDestination(), progressMsg);
            }
            TLMsg responseMsg = createMsg().setAction(resultAction).setParam(HTTP_ERROR, false)
                    .setParam(WEBRESPONSE, file).setParam(HTTP_P_SESSIONDATA, sesstionData);
            putMsg((IObject) resultFor, responseMsg);
        }
    }

    protected class PostFileCallback implements okhttp3.Callback
    {
        private Object resultFor ;
        private String resultAction ;
        private TLMsg progressMsg ;
        private float lastProgress = 0L;
        private Object sesstionData ;

        public PostFileCallback(Object resultFor, String resultAction, Object sesstionData, TLMsg pregressMsg) {
            this.resultFor = resultFor;
            this.resultAction = resultAction;
            this.sesstionData = sesstionData;
            this.progressMsg = pregressMsg;
        }

        @Override
        public void onFailure(Call call, IOException e) {
            if(resultFor == null) return;
            TLMsg responseMsg = createMsg().setAction(resultAction).setParam(HTTP_ERROR, true)
                    .setParam(WEBRESPONSE, null).setParam(HTTP_P_SESSIONDATA, sesstionData);
            putLog("response error", LogLevel.ERROR, "callback");
            putMsg((IObject) resultFor, responseMsg);
        }

        @Override
        public void onResponse(Call call, Response response) throws IOException {
            if(resultFor == null) return;
            String body = response.body().string();
            if(progressMsg != null) {
                progressMsg.setParam(HTTP_R_FILE_PROGRESS, 100.00f)
                        .setParam(HTTP_P_SESSIONDATA, sesstionData);
                putMsg(progressMsg.getDestination(), progressMsg);
            }
            TLMsg responseMsg = createMsg().setAction(resultAction).setParam(HTTP_ERROR, false)
                    .setParam(WEBRESPONSE, body).setParam(HTTP_P_SESSIONDATA, sesstionData);
            putMsg((IObject) resultFor, responseMsg);
        }
    }
}
