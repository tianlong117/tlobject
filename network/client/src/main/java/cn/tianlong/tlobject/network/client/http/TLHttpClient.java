package cn.tianlong.tlobject.network.client.http;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import com.zhy.http.okhttp.OkHttpUtils;
import com.zhy.http.okhttp.builder.GetBuilder;
import com.zhy.http.okhttp.builder.OkHttpRequestBuilder;
import com.zhy.http.okhttp.builder.PostFormBuilder;
import com.zhy.http.okhttp.builder.PostStringBuilder;
import com.zhy.http.okhttp.callback.Callback;
import com.zhy.http.okhttp.callback.FileCallBack;
import com.zhy.http.okhttp.callback.StringCallback;
import com.zhy.http.okhttp.https.HttpsUtils;
import com.zhy.http.okhttp.request.RequestCall;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;


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
    protected HttpsUtils.SSLParams moduleSslParams ;
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
      if(sslCerFile !=null){
          InputStream  cerFileStream = TLHttpClient.class.getResourceAsStream(sslCerFile);
          InputStream[] certificates = new InputStream[1];
          certificates[0]=cerFileStream;
          moduleSslParams= HttpsUtils.getSslSocketFactory(certificates, null, null);
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
        if(msg.containsParam(HTTP_P_TAG))
            new OkHttpUtils(null).cancelTag(msg.getParam(HTTP_P_TAG));
        else if(msg.containsParam(HTTP_P_URL)){
            RequestCall call = OkHttpUtils.get().url((String) msg.getParam(HTTP_P_URL)).build();
            call.cancel();
        }
    }

    protected TLMsg uGet(final Object fromWho, final TLMsg msg){
        GetBuilder getBuilder= (GetBuilder) getHttpBuilder(HTTP_GET,msg);
        if (getBuilder ==null)
            return createMsg().setParam(HTTP_ERROR, true).setParam("msg","url");
        HashMap<String,String> params = (HashMap<String,String>)msg.getParam("params");
        if(params !=null){
            for (String key : params.keySet()) {
                if(params.get(key)!=null)
                   getBuilder.addParams(key, params.get(key));
            }
        }
       Map<String ,String> header= (Map<String, String>) msg.getParam(HTTP_P_REQUESTHEADER);
        if(header !=null)
            getBuilder.headers(header) ;
        RequestCall requestCall =getBuilder.build();
        return httpCallExecute( requestCall,fromWho, msg);
    }
    private void download(Object fromWho, TLMsg msg) {
        String url=(String)msg.getParam(HTTP_P_URL);
        String    resultAction = (String) msg.getParam(RESULTACTION);
        Object  resultFor =getResultObject(msg);
        String    path = (String) msg.getParam(HTTP_P_DOWNLOAD_SAVEPATH);
        String    filename = (String) msg.getParam(HTTP_P_DOWNLOAD_FILENAME);
        if(filename==null)
            filename =StringUtils.substringAfterLast(url,"/");
        FileCallBack  fileCallBack =new  UFileCallback(resultFor,resultAction,path,filename,msg.getParam(HTTP_P_SESSIONDATA), (TLMsg) msg.getParam(HTTP_P_FILE_PROGRESSMSG));
        GetBuilder getBuilder=(GetBuilder) getHttpBuilder(HTTP_GET,msg);
        if (getBuilder ==null)
            return  ;
        LinkedHashMap<String ,String> header= (LinkedHashMap<String, String>) msg.getParam("requestHeader");
        if(header !=null)
            getBuilder.headers(header) ;
        getBuilder.build().execute(fileCallBack);
    }
    protected TLMsg uPost(Object fromWho, TLMsg msg){
        PostFormBuilder postFormBuilder= (PostFormBuilder) getHttpBuilder(HTTP_POST,msg);
        if (postFormBuilder ==null)
            return createMsg().setParam(HTTP_ERROR, true).setParam("msg","url");
        Map<String ,String> header= (Map<String, String>) msg.getParam(HTTP_P_REQUESTHEADER);
        if(header !=null)
            postFormBuilder.headers(header) ;
        HashMap<String,String> params = (HashMap<String,String>)msg.getParam(HTTP_P_POSTPARAMS);
        if(params !=null){
            for (String key : params.keySet()) {
                if(params.get(key)!=null)
                   postFormBuilder.addParams(key, params.get(key));
            }
        }
        RequestCall requestCall =postFormBuilder.build();
        return httpCallExecute( requestCall,fromWho, msg);
    }
    private TLMsg postFile(Object fromWho, TLMsg msg) {
        Object fileNames=  msg.getParam(HTTP_P_UPFILE);
        PostFormBuilder postFormBuilder=(PostFormBuilder) getHttpBuilder(HTTP_POST,msg);
        if (postFormBuilder ==null)
            return createMsg().setParam(HTTP_ERROR, true).setParam("msg","url");
        LinkedHashMap<String ,String> header= (LinkedHashMap<String, String>) msg.getParam("requestHeader");
        if(header !=null)
            postFormBuilder.headers(header);
        if(fileNames instanceof ArrayList)
        {
            for(int i=0 ; i < ((ArrayList) fileNames).size();i++)
            {
                String fileName = (String) ((ArrayList) fileNames).get(i);
                File file =new File(fileName);
                postFormBuilder.addFile("mFile"+i, fileName, file);
            }
        }
        else  if(fileNames instanceof Map)
        {
            for(Object key : ((Map) fileNames).keySet())
            {
                String fileName = (String)((Map) fileNames).get(key);
                File file =new File(fileName);
                postFormBuilder.addFile((String)key, fileName, file);
            }
        }
        else  if(fileNames instanceof String){
            File file =new File((String)fileNames);
            postFormBuilder.addFile("mFile", (String)fileNames, file);
        }
        else
            return null ;
        HashMap<String,String> params = (HashMap<String,String>)msg.getParam(HTTP_P_POSTPARAMS);
        if(params !=null){
            for (String key : params.keySet()) {
                if(params.get(key)!=null)
                    postFormBuilder.addParams(key, params.get(key));
            }
        }
        RequestCall requestCall =postFormBuilder.build();
        return httpCallExecute( requestCall,fromWho, msg);
    }
    public TLMsg postJson(Object fromWho, TLMsg msg) {
        PostStringBuilder postStringBuilder = (PostStringBuilder) getHttpBuilder(HTTP_POSTJSON,msg);
        if (postStringBuilder ==null)
            return createMsg().setParam(HTTP_ERROR, true).setParam("msg","url");
        String json = new Gson().toJson(msg.getParam(HTTP_P_POSTCONTENT));
        postStringBuilder.mediaType(MediaType.parse("context/json; charset=utf-8"))
             .content(json);
        Map<String ,String> header= (Map<String, String>) msg.getParam(HTTP_P_REQUESTHEADER);
        if(header !=null)
            postStringBuilder.headers(header) ;
        RequestCall requestCall= postStringBuilder.build() ;
        return httpCallExecute( requestCall,fromWho, msg);
    }
    protected TLMsg uPostString(Object fromWho, TLMsg msg){
        PostStringBuilder postStringBuilder = (PostStringBuilder) getHttpBuilder(HTTP_POSSTRING,msg);
        if (postStringBuilder ==null)
            return createMsg().setParam(HTTP_ERROR, true).setParam("msg","url");
        postStringBuilder.content((String)msg.getParam(HTTP_P_POSTCONTENT));
        Map<String ,String> header= (Map<String, String>) msg.getParam(HTTP_P_REQUESTHEADER);
        if(header !=null)
            postStringBuilder.headers(header) ;
        RequestCall requestCall= postStringBuilder.build() ;
        return httpCallExecute( requestCall,fromWho, msg);
    }
    protected OkHttpRequestBuilder getHttpBuilder(String type,TLMsg msg ){
        String url = ((String) msg.getParam(HTTP_P_URL)).trim();
        String httpprotocol =url.substring(0,7) ;
        String httpsprotocol =url.substring(0,8) ;
        if(!httpprotocol.equals("http://") && !httpsprotocol.equals("https://"))
        {
            putLog("url is wrong:"+url,LogLevel.WARN,"builder");
            return null ;
        }
        if(msg.containsParam(HTTP_P_ISHTTPS) && (Boolean) msg.getParam(HTTP_P_ISHTTPS)==true)
        {
            String cerFile =(String) msg.getParam(SSL_SCERFILE);
            HttpsUtils.SSLParams sslParams ;
            if(cerFile !=null)
            {
                InputStream httpsCerFileStream = null;
                httpsCerFileStream = TLHttpClient.class.getResourceAsStream(cerFile);
                InputStream[] certificates = new InputStream[1];
                certificates[0]=httpsCerFileStream;
                sslParams = HttpsUtils.getSslSocketFactory(certificates, null, null);
            }
            else if( moduleSslParams !=null)
                sslParams =moduleSslParams ;
            else
                return  null ;
            OkHttpClient okHttpClient = new OkHttpClient.Builder().hostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    //强行返回true 即验证成功
                    return true;
                }
            })
                    .sslSocketFactory(sslParams.sSLSocketFactory, sslParams.trustManager)
                    //其他配置
                    .build();
            OkHttpUtils.initClient(okHttpClient);
        }
        OkHttpRequestBuilder builder ;
        switch (type) {
            case HTTP_POST:
            case HTTP_POSTFILE:
                builder=OkHttpUtils.post();
                break;
            case HTTP_POSSTRING:
            case HTTP_POSTJSON:
                builder=OkHttpUtils.postString();
                break;
            case HTTP_GET:
            case HTTP_DOWNLOAD:
                builder=OkHttpUtils.get();
                break;
            default:
                builder=OkHttpUtils.get();
        }
        if(msg.containsParam(HTTP_P_TAG))
            builder.tag(msg.getParam(HTTP_P_TAG));
        builder.url(url);
        return  builder ;
    }
    private TLMsg httpCallExecute(RequestCall requestCall, Object fromWho, TLMsg msg){
        String    resultAction = (String) msg.getParam(RESULTACTION);
        if(msg.containsParam("connTimeOut"))
            requestCall.connTimeOut((Long) msg.getParam("connTimeOut")*1000);
        else if(connTimeOut >0L)
            requestCall.connTimeOut(connTimeOut);
        if(msg.containsParam("readTimeOut"))
            requestCall.readTimeOut((Long) msg.getParam("readTimeOut")*1000);
        else if(readTimeOut >0L)
            requestCall.readTimeOut(readTimeOut);
        if(msg.containsParam("writeTimeOut"))
            requestCall.writeTimeOut((Long) msg.getParam("writeTimeOut")*1000);
        else if(writeTimeOut >0L)
            requestCall.writeTimeOut(writeTimeOut);
        if(resultAction !=null )
        {
            Object  resultFor =msg.getParam(RESULTFOR);
            if(resultFor ==null )
                resultFor=fromWho ;
            else if (resultFor instanceof String)
                resultFor= getModule((String)resultFor);
            String action =msg.getAction();
            Callback callback ;
            if(action.equals(HTTP_POSTFILE))
                callback=new PostFileCallback(resultFor,resultAction,msg.getParam(HTTP_P_SESSIONDATA),(TLMsg) msg.getParam(HTTP_P_FILE_PROGRESSMSG));
            else
              callback=new UStringCallback(resultFor,resultAction,msg.getParam(HTTP_P_SESSIONDATA));
            requestCall.execute(callback);
            return null ;
        }
        Response response ;
        try {
            response=requestCall.execute();
        } catch (IOException e) {
            String url=(String)msg.getParam(HTTP_P_URL);
            putLog("http error:"+url,LogLevel.ERROR,"httpCall");
           return createMsg().setParam(HTTP_ERROR, true).setParam("msg","io");
        }
        if (response.isSuccessful()) {
            try {
                String content =response.body().string();
                return createMsg().setParam(HTTP_ERROR, false)
                        .setParam(WEBRESPONSE, content).setParam(HTTP_R_REPONSE,response);
            } catch (IOException e) {
          }
        }
        String url=(String)msg.getParam(HTTP_P_URL);
        putLog("response error:"+url,LogLevel.ERROR,"httpCall");
        return createMsg().setParam(HTTP_ERROR, true);
    }
    protected class UStringCallback extends StringCallback
    {
        private Object resultFor ;
        private String resultAction ;
        private Object sesstionData ;
        private Response httpResponse ;
        public String parseNetworkResponse(Response response, int id) throws IOException {
            this.httpResponse  =response ;
            return  super.parseNetworkResponse(response,id) ;
        }
        public UStringCallback(Object resultFor, String resultAction,Object sesstionData) {
            this.resultFor = resultFor ;
            this.resultAction = resultAction ;
            this.sesstionData =sesstionData ;
        }
        @Override
        public void onError(Call call, Exception e, int id) {
            TLMsg responseMsg =createMsg().setAction( resultAction).setParam("error", true)
                    .setParam(WEBRESPONSE, null).setParam(HTTP_P_SESSIONDATA,sesstionData);
            putLog("response error",LogLevel.ERROR,"callback");
            putMsg((IObject) resultFor, responseMsg);
        }
        @Override
        public void onResponse(String response, int id) {
            TLMsg responseMsg =createMsg().setAction( resultAction)
                    .setParam("error", false)
                    .setParam(WEBRESPONSE, response)
                    .setParam(HTTP_R_REPONSE,this.httpResponse)
                    .setParam(HTTP_P_SESSIONDATA,sesstionData);
            putMsg((IObject) resultFor, responseMsg);
        }
    }
    protected class UFileCallback extends FileCallBack
    {
        private Object resultFor ;
        private String resultAction ;
        private TLMsg progressMsg ;
        private float lastProgress=0L;
        private Object sesstionData ;

        public UFileCallback(Object resultFor, String resultAction,String destFileDir, String destFileName ,Object sesstionData,TLMsg pregressMsg) {
            super(destFileDir, destFileName);
            this.resultFor = resultFor ;
            this.resultAction = resultAction ;
            this.sesstionData =sesstionData ;
            this.progressMsg =pregressMsg ;
        }

        @Override
        public void onError(Call call, Exception e, int id) {
            if(resultFor==null)
                return;
            TLMsg responseMsg =createMsg().setAction( resultAction).setParam(HTTP_ERROR, true)
                    .setParam(WEBRESPONSE, null).setParam(HTTP_P_SESSIONDATA,sesstionData);
            putLog("response error",LogLevel.ERROR,"callback");
            putMsg((IObject) resultFor, responseMsg);
        }

        @Override
        public void onResponse(File response, int id) {
            if(resultFor==null)
                return;
            if(progressMsg !=null)
            {
                progressMsg.setParam(HTTP_R_FILE_PROGRESS, 100.00f).setParam(HTTP_P_SESSIONDATA,sesstionData);
                putMsg( progressMsg.getDestination(), progressMsg);
            }
            TLMsg responseMsg =createMsg().setAction( resultAction).setParam(HTTP_ERROR, false)
                    .setParam(WEBRESPONSE, response).setParam(HTTP_P_SESSIONDATA,sesstionData);
            putMsg((IObject) resultFor, responseMsg);

        }
        @Override
        public void inProgress(float progress, long total , int id)
        {
            if(progressMsg ==null)
                return;
            if(progress <98.00 && (progress - lastProgress)<0.01)
               return;
            lastProgress =progress;
            progressMsg.setParam(HTTP_R_FILE_PROGRESS, progress).setParam(HTTP_R_FILE_TOTAL, total).setParam(HTTP_P_SESSIONDATA,sesstionData);
            putMsg( progressMsg.getDestination(), progressMsg);
        }
    }
    protected class PostFileCallback extends Callback<String>
    {
        private Object resultFor ;
        private String resultAction ;
        private TLMsg progressMsg ;
        private float lastProgress=0L;
        private Object sesstionData ;

        public PostFileCallback(Object resultFor, String resultAction,Object sesstionData,TLMsg pregressMsg) {
            this.resultFor = resultFor ;
            this.resultAction = resultAction ;
            this.sesstionData =sesstionData ;
            this.progressMsg =pregressMsg ;
        }

        @Override
        public void onError(Call call, Exception e, int id) {
            if(resultFor==null)
                return;
            TLMsg responseMsg =createMsg().setAction( resultAction).setParam(HTTP_ERROR, true)
                    .setParam(WEBRESPONSE, null).setParam(HTTP_P_SESSIONDATA,sesstionData) ;
            putLog("response error",LogLevel.ERROR,"callback");
            putMsg((IObject) resultFor, responseMsg);
        }

        @Override
        public void onResponse(String s, int i) {
            if(resultFor==null)
                return;
            if(progressMsg !=null)
            {
                progressMsg.setParam(HTTP_R_FILE_PROGRESS, 100.00f).setParam(HTTP_P_SESSIONDATA,sesstionData);
                putMsg( progressMsg.getDestination(), progressMsg);
            }
            TLMsg responseMsg =createMsg().setAction( resultAction).setParam(HTTP_ERROR, false)
                    .setParam(WEBRESPONSE, s).setParam(HTTP_P_SESSIONDATA,sesstionData);
            putMsg((IObject) resultFor, responseMsg);
        }
        @Override
        public void inProgress(float progress, long total , int id)
        {
            if(progressMsg ==null)
                return;
            if(progress <100.00 && (progress - lastProgress)<0.01)
                return;
            lastProgress =progress;
            progressMsg.setParam(HTTP_R_FILE_PROGRESS, progress).setParam(HTTP_R_FILE_TOTAL, total).setParam(HTTP_P_SESSIONDATA,sesstionData);
            putMsg( progressMsg.getDestination(), progressMsg);
        }

        @Override
        public String parseNetworkResponse(Response response, int i) throws Exception {
            return response.body().string();
        }


    }
}
