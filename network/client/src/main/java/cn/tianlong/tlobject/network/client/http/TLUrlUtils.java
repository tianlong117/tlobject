package cn.tianlong.tlobject.network.client.http;


import cn.tianlong.tlobject.base.TLMsg;
import org.apache.commons.io.FileUtils;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import javax.net.ssl.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

public class TLUrlUtils {

    public static boolean isHttpURL(String url){
        if(url.length() <11)
            return false ;
        String prefix = url.substring(0,8).toLowerCase();
        if(prefix.equals("https://"))
            return true ;
        prefix = url.substring(0,7).toLowerCase();
        if(prefix.equals("http://"))
            return true ;
        return false ;

    }
    public static boolean isHttps(String url){
        if(url.length() <11)
            return false ;
        String prefix = url.substring(0,8).toLowerCase();
        if(prefix.equals("https://"))
            return true ;
        return false ;
    }

    public static void trustEveryoneForHttps() {
        try {
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new X509TrustManager[] { new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                }

                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            } }, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
        } catch (Exception e) {
// e.printStackTrace();
        }
    }
    public static File getUrlFile(String url, TLMsg msg) {
         return  getUrlFile(url,
                           (Map<String, String>) msg.getParam("httpHeader"),
                           (Map<String, String>) msg.getParam("cookie"),
                           (Map<String, String>) msg.getParam("datas"),
                           (String) msg.getParam("fileName"),
                           (String) msg.getParam("filePath"));
    }
    public static InputStream getUrlStream(String url, TLMsg msg) {
        Connection.Response  response =getUrlResponse(url,
                (Map<String, String>) msg.getParam("httpHeader"),
                (Map<String, String>) msg.getParam("cookie"),
                (Map<String, String>) msg.getParam("datas"));
        if(response !=null)
            return response.bodyStream();
        else
            return null ;
    }
    public static Document getUrlDOC(String url, TLMsg msg) {
       return  getUrlDOC(url,
                (Map<String, String>) msg.getParam("httpHeader"),
                (Map<String, String>) msg.getParam("cookie"),
                (Map<String, String>) msg.getParam("datas"));

    }
    public static HashMap<String,Object> getUrlContent(String url, TLMsg msg) {
        return  getUrlContent(url,
                (Map<String, String>) msg.getParam("httpHeader"),
                (Map<String, String>) msg.getParam("cookie"),
                (Map<String, String>) msg.getParam("datas"));

    }
    public static File getUrlFile(String url, Map<String, String> httpHeader, Map<String, String> cookie, Map<String, String> datas ,String fileName ,String filePath) {
        if(fileName ==null)
            return null ;
        InputStream  inputStream =getUrlStream(url,  httpHeader,  cookie,datas);
        if (inputStream ==null)
            return null ;
        if(filePath !=null)
        {
            File dir = new File(filePath);
            if (!dir.exists()) {
                try {
                    FileUtils.forceMkdir(dir);
                } catch (IOException e) {
                    e.printStackTrace();
                    return null ;
                }
            }
        }
        File file =new File(fileName);
        try {
            FileUtils.copyInputStreamToFile(inputStream,file);
            return file ;
        } catch (IOException e) {
            e.printStackTrace();
            return null ;
        }
    }
    public static InputStream getUrlStream(String url, Map<String, String> httpHeader, Map<String, String> cookie, Map<String, String> datas) {

        Connection.Response response = getUrlResponse( url, httpHeader, cookie,  datas);
        if(response !=null)
            return response.bodyStream();
        else
            return null ;
    }
    public static HashMap<String,Object> getUrlContent(String url, Map<String, String> httpHeader, Map<String, String> cookie, Map<String, String> datas) {
        Connection.Response response = getUrlResponse( url, httpHeader, cookie,  datas);
        if(response ==null)
          return null ;
        HashMap<String,Object> map = new HashMap<>();
        map.put("header",response.headers());
        map.put("cookies",response.cookies());
        map.put("body",response.body());
        map.put("charset",response.charset());
        return map ;
    }
    public static Document getUrlDOC(String url, Map<String, String> httpHeader, Map<String, String> cookie, Map<String, String> datas) {
        Connection.Response response = getUrlResponse( url, httpHeader, cookie,  datas);
        if(response ==null)
            return null ;
        try {
            return response.parse();
        } catch (IOException e) {
            e.printStackTrace();
            return null ;
        }
    }
    public static Connection.Response getUrlResponse(String url, Map<String, String> httpHeader, Map<String, String> cookie, Map<String, String> datas) {
        Connection   connection = Jsoup.connect(url);
        if (httpHeader != null)
            connection.headers(httpHeader);
        if (cookie != null)
            connection.cookies(cookie);
        if (datas != null)
            connection.data(datas);
        connection.ignoreHttpErrors(true);
        Connection.Response response;
        try {
            response = connection.method(Connection.Method.GET).userAgent("Opera").ignoreContentType(true).maxBodySize(500000000).timeout(20 * 1000).execute();
        } catch (IOException e) {
            return null;
        }
        return response;
    }
    public static Connection.Response postToHost(String url ,TLMsg msg ){
        Map<String, String> datas = msg.getMapParam("datas",null);
        boolean  isFormData =false ;
        if(datas !=null && datas.get("isFormData")!=null)
            isFormData =Boolean.parseBoolean(datas.get("isFormData"));
        Map<String, String>  httpHeader =msg.getMapParam("httpHeader",new HashMap<>());
        if(isFormData)
            httpHeader.put("Content-Type", "multipart/form-data") ;
        return postToHost(url,
                httpHeader,
               msg.getMapParam("cookie",null),
                datas,
                (String)msg.getParam("requestBody"),
                (String)msg.getParam("charset"),
                msg.getMapParam("files",null));

    }
    public static Connection.Response postToHost(String url, Map<String, String> httpHeader, Map<String, String> cookie, Map<String, String> datas,String requestBody,String charset,Map<String,String>files) {
        Connection connection = Jsoup.connect(url);
        if(files !=null && !files.isEmpty())
        {
            for(String key : files.keySet()){
                String path =files.get(key);
                File file =new File(path);
                try {
                    InputStream inputStream =FileUtils.openInputStream(file);
                    connection.data(key,files.get(key),inputStream) ;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (httpHeader == null)
                httpHeader =new HashMap<>();
            httpHeader.put("Content-Type", "multipart/form-data") ;
        }
        if (httpHeader != null)
            connection.headers(httpHeader);
        if (cookie != null)
            connection.cookies(cookie);
        if (datas != null)
            connection.data(datas);
        if (charset != null)
            connection.postDataCharset(charset) ;
        if (requestBody !=null)
            connection.requestBody(requestBody) ;
        Connection.Response response = null;
        try {
            response = connection.method(Connection.Method.POST)
                    .userAgent("Opera").ignoreContentType(true)
                    .maxBodySize(500000000)
                    .timeout(10 * 1000).execute();
        } catch (IOException e) {
            return null;
        }
        return response;
    }

}
