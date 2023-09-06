package cn.tianlong.java.servletdemo;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.servletutils.TLWServModule;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.servletutils.view.TLHtmlForm;
import cn.tianlong.tlobject.servletutils.view.TLHtmlFormInput;
import cn.tianlong.tlobject.servletutils.view.TLHtmlFormUtils;
import org.apache.commons.codec.digest.DigestUtils;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.*;

import static cn.tianlong.tlobject.cache.TLParamString.*;
import static cn.tianlong.tlobject.cache.TLParamString.M_FILECACHE;
import static cn.tianlong.tlobject.servletutils.TLParamString.*;
import static cn.tianlong.tlobject.servletutils.utils.TLParamString.URLFILE_P_URL;

public class homePageControl extends TLWServModule {
    public int m=0;
    protected List servodata;
    public homePageControl(){
        super();
    }
    public homePageControl(String name ){
        super(name);
    }
    public homePageControl(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);

    }

    protected TLBaseModule init(){
        return this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case "index":
                index(fromWho,msg);
                break;
            case "baidu" :
                baidu(fromWho,msg);
                break;
            case "thymeleaf" :
                thymeleaf(fromWho,msg);
                break;
            default:
                putMsg("error",creatOutMsg().setAction("setError").setParam("content","no action"));
        }
        return returnMsg ;
    }

    private void thymeleaf(Object fromWho, TLMsg msg) {
        outData odata =  creatOutDataMsg("thymeleaf");
        odata.addData("currentDate", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        putOutData(odata);
    }

    protected void index(Object fromWho, TLMsg msg) {
        HttpServletRequest request = getRequest();
        ServletContext context= request.getServletContext();
        outData odata =  creatOutDataMsg();
        odata.addData("<html><head><title>RequestInfo</TITLE></head>");
        odata.addData("<body>");
        //打印服务器端的IP地址
        odata.addData("<br>LocalAddr: "+request.getLocalAddr());
        odata.addData("<br>ServerName: "+request.getServerName());
        odata.addData("<br>getLocalName: "+request.getLocalName());
        //打印服务器端的的主机名
        odata.addData("<br>LocalName: "+request.getLocalName());
        //打印服务器端的的FTP端口号
        odata.addData("<br>LocalPort: "+request.getLocalPort());
        //打印客户端与服务器端通信所用的协议的名称以及版本号
        odata.addData("<br>Protocol: "+request.getProtocol());
        //打印客户端的IP地址
        odata.addData("<br>RemoteAddr: "+request.getRemoteAddr());
        //打印客户端的主机名
        odata.addData("<br>RemoteHost: "+request.getRemoteHost());
        //打印客户端的FTP端口号
        odata.addData("<br>RemotePort: "+request.getRemotePort());
        odata.addData("<br>SessionId: "+request.getRequestedSessionId());
        odata.addData("<br>getRemoteUser: "+request.getRemoteUser());
        //打印HTTP请求方式 getRealPath("/");
        odata.addData("<br>Method: "+request.getMethod());
        //打印HTTP请求中的URI
        odata.addData("<br>ServletPath: "+request.getServletPath());
        odata.addData("<br>URI: "+request.getRequestURI());
        //打印客户端所请求访问的Web应用的URL入口
        odata.addData("<br>ContextPath: "+request.getContextPath());
        odata.addData("<br>context.getRealPath: "+context.getRealPath("/WEB-INI/conf"));
        odata.addData("<br>docBase: "+context.getInitParameter("docBase"));
        //打印HTTP请求中的查询字符串
        odata.addData("<br>QueryString: "+request.getQueryString());

        /**打印HTTP请求头*/
        odata.addData("<br>***打印HTTP请求头***");
        Enumeration eu=request.getHeaderNames();
        while(eu.hasMoreElements()){
            String headerName=(String)eu.nextElement();
            odata.addData("<br>"+headerName+": "+request.getHeader(headerName));
        }
        odata.addData("<br>***打印HTTP请求头结束***<br>");
        //打印请求参数username
        String[] args ={"p1","p2"};
        //   HashMap<String,Object> userdata=getUserData().getArgs();
        HashMap<String,Object> userdata=getUserData(args).getArgs();
        if(userdata !=null || !userdata.isEmpty())
        for (String key : userdata.keySet()) {
            if(userdata.get(key)!=null)
                if(key.equals("p1"))
                {
                    String[] value = (String[]) userdata.get(key);
                    odata.addData("<br>"+key+": "+value[0]+";"+value[1]);

                }
                else {
                    String value = (String) userdata.get(key);
                    odata.addData("<br>"+key+": "+value);
                }

        }
        odata.addData("<br>p2: "+(String)getUserData().getParam("p2"));
        odata.addData("</body></html>");
        putOutData(odata);
    }
    private void baidu(Object fromWho, TLMsg msg) {
        String url="http://www.baidu.com";
        TLMsg hmsg= createMsg().setAction(HTTP_GET).setParam(HTTP_P_URL,url);
        TLMsg resultMsg =putMsg("httpClient",hmsg);
        String response;
        if(resultMsg.parseBoolean("error",false)==true)
            response="网络错误";
        else
            response= (String) resultMsg.getParam(WEBRESPONSE);
        outData odata =  creatOutDataMsg();
        odata.addData(response);
        putOutData(odata);
    }


}
