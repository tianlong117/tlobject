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
            case "getUrlFile":
                getUrlFile(fromWho,msg);
                break;
            case "getUrlImage":
                getUrlImage(fromWho,msg);
                break;
            case "test":
                test(fromWho,msg);
                break;
            case "getWebParam":
                getWebParam(fromWho,msg);
                break;
            case "input" :
                input(fromWho,msg);
                break;
            case "getuser" :
                getuser(fromWho,msg);
                break;
            case "content" :
                content(fromWho,msg);
                break;
            case "getmsg" :
                getmsg(fromWho,msg);
                break;
            case "msgmap" :
                msgmap(fromWho,msg);
                break;
            case "baidu" :
                baidu(fromWho,msg);
                break;
            case "thymeleaf" :
                thymeleaf(fromWho,msg);
                break;
            case "velocity" :
                velocity(fromWho,msg);
                break;
            case "dbmodle" :
                dbmodle(fromWho,msg);
                break;
            case "upload" :
                upload(fromWho,msg);
                break;
            case "reciveFromService" :
                reciveFromService(fromWho,msg);
                break ;
            case "fileCache" :
                fileCache(fromWho,msg);
                break ;
            case "putapp" :
                putapp(fromWho,msg);
                break ;
            case "setPassword":
                setPassword(fromWho,msg);
                break;
            default:
                putMsg("error",creatOutMsg().setAction("setError").setParam("content","no action"));
        }
        return returnMsg ;
    }
    private void setPassword(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam("userid");
        String password = (String) msg.getParam("password");
        String pwdmd5= DigestUtils.md5Hex(password+userid);
        outData odata =  creatOutDataMsg("setPassword");
        odata.addData("userid","用户名: "+userid);
        odata.addData( "password","口令: "+password);
        odata.addData("md5password","加密口令: "+pwdmd5);
        putOutData(odata);
    }
    private void putapp(Object fromWho, TLMsg msg) {
       String filterName ="qqadmin";
   //    TLMsg  appmsg =createMsg().setAction(USER_GETONLINE);
       TLMsg appmsg =createMsg().setAction(USER_SETOFFLINE).setParam(USER_P_USERID,"admin");
       TLMsg returnMsg =putAppMsg("adminUser",appmsg,filterName);
       if(returnMsg ==null)
           return;
        Map  onlines =returnMsg.getArgs() ;
    }
    private void fileCache(Object fromWho, TLMsg msg) {
        TLMsg cmsg =createMsg().setAction(CACHE_GETCACHE).setParam(CACHE_P_CACHENAME,"www")
                .setParam(CACHE_P_KEY,"baidu");
         TLMsg returnMsg =putMsg(M_FILECACHE,cmsg);
         String cache = (String) returnMsg.getParam(CACHE_R_VALUE);
         if(cache !=null){
             outData odata =  creatOutDataMsg();
             odata.addData(cache);
             putOutData(odata);
             return;
         }
        String url ="http://www.baidu.com";
        TLMsg bmsg= new TLMsg ().setAction("get")
                .setDestination("httpClient")
                .setParam("url",url);
        TLMsg resultMsg =putMsg("httpClient", bmsg);
        String response;
        if(resultMsg.getParam("error")!=null && (boolean)resultMsg.getParam("error")==true){
            response="网络延时";
        }
        else
            response= (String) resultMsg.getParam(WEBRESPONSE);
        TLMsg wcmsg =createMsg().setAction(CACHE_WRITECACHE).setParam(CACHE_P_CACHENAME,"www")
                .setParam(CACHE_P_KEY,"baidu").setParam(CACHE_P_VALUE,response);
        putMsg(M_FILECACHE,wcmsg);
        outData odata =  creatOutDataMsg();
        odata.addData(response);
        putOutData(odata);
    }

    private void getUrlFile(Object fromWho, TLMsg msg) {
     //  String url ="http://pic14.nipic.com/20110514/6108441_175913401116_2.jpg";
    //   String url ="http://www.daqing.gov.cn/zfgz/zfgg/images/2020/12/10/510D298CAF4F487492918178045EB526.docx" ;
    //   String url ="http://www.daqing.gov.cn/zfgz/zfgg/675830.shtml" ;
        String url ="http://bg.daquni.cn:8079/mysql.tar";
        TLMsg returnMsg = putMsg(M_URLFILECACHE,createMsg()
                .setAction(CACHE_GETCACHE).setParam(URLFILE_P_URL,url));
        InputStream in = (InputStream) returnMsg.getParam(CACHE_R_VALUE);
        if (in ==null)
            return;
        String fileName = url.substring(url.lastIndexOf('/') + 1, url.length());
        putStream(fileName,in);
    }
    private void getUrlImage(Object fromWho, TLMsg msg) {
    //    String url ="http://bg.daquni.cn:8079/test/testpic.jpg";
     String url ="http://localhost:8080/test2.jpg";
        TLMsg gMsg =createMsg()
                .setParam(CACHE_P_CACHENAME,"newsimage")
           //     .setParam(URLIMAGE_P_WIDTH,800)
            //    .setParam(URLIMAGE_P_HEIGHT,800)
                .setAction(CACHE_GETCACHE)
                .setParam(URLFILE_P_URL,url) ;
        TLMsg returnMsg = putMsg(M_URLIMAGECACHE,gMsg);
        InputStream in = (InputStream) returnMsg.getParam(CACHE_R_VALUE);
        if (in ==null)
            return;
        String fileName = url.substring(url.lastIndexOf('/') + 1, url.length());
        putStream(fileName,in);
    }
    private void upload(Object fromWho, TLMsg msg) {
        TLMsg umsg =createMsg().setAction("upload");
        String newFielName = String.valueOf(new Date().getTime());
        umsg.setParam("newFileName",null).setParam("filepath","/workupload");
        TLMsg returnMsg =putMsg("uploadFile",umsg);
        outData odata =  creatOutDataMsg();
        odata.addData("code","2002");
        odata.addData("message","file");
        odata.addData("result",returnMsg.getArgs());
        putOutData(odata);
    }
    private void getWebParam(Object fromWho, TLMsg msg) {
        TLMsg returnMsg =putMsg(getUserObjName(),createMsg().setAction(MODULE_GETPARAM));
        outData odata =  creatOutDataMsg();
        odata.addData("website params:");
        odata.addData("params",returnMsg.getArgs());
        putOutData(odata);
    }

    private void getuser(Object fromWho, TLMsg msg) {
        TLMsg returnMsg =putMsg(getUserObjName(),createMsg().setAction("getLoginState"));
        outData odata =  creatOutDataMsg();
        odata.addData("用户输入:");
        odata.addData("username",returnMsg.getParam("userName"));
        odata.addData("uid",returnMsg.getParam("uid"));
        putOutData(odata);
    }

    private void reciveFromService(Object fromWho, TLMsg msg) {


        putLog(" 进程id: "+Thread.currentThread().getName(),LogLevel.INFO,"reciveFromService");
        servodata = (List) msg.getParam("result");
    }


    private void dbmodle(Object fromWho, TLMsg msg) {
    }
    private void thymeleaf(Object fromWho, TLMsg msg) {
        outData odata =  creatOutDataMsg("thymeleaf");
        odata.addData("currentDate", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        putOutData(odata);
    }
    private void velocity(Object fromWho, TLMsg msg) {
        //   setOutInterface("volecity");
        HashMap<String,String> params = new HashMap<>();
        params.put("action","toserver");
        params.put("methord","get");
        TLHtmlForm form = (TLHtmlForm) TLHtmlFormUtils.getFormUnit("form",params);
        params.clear();
        params.put("type","text");
        params.put("value","你好");
        params.put("name","uname");
        TLHtmlFormInput input1 = (TLHtmlFormInput) TLHtmlFormUtils.getFormUnit("input",params);
        params.clear();
        params.put("type","submit");
        params.put("value","提交");
        params.put("name","tijiao");
        TLHtmlFormInput input2 = (TLHtmlFormInput) TLHtmlFormUtils.getFormUnit("input",params);
        form.addFormUnits(input1);
        form.addFormUnits(input2);
        outData odata =  creatOutDataMsg("velocity");
        HashMap list=params;
        odata.addData("list",list);
        odata.addData("name","dongqiang");
        odata.addData("input",form.toString());
        putOutData(odata);
    }

    private void msgmap(Object fromWho, TLMsg msg) {

        m++;
        TLMsg cmsg=createMsg();
        cmsg.setParam("service","this msgmap "+m);
        putUserMsg(cmsg);
    }

    private void getmsg(Object fromWho, TLMsg msg) {

        TLMsg cmsg=getUserMsg();
        cmsg.setParam("service","ni hao ");
        putUserMsg(cmsg);
    }

    private void content(Object fromWho, TLMsg msg) {
        TLMsg umsg=getUserContent();
        outData odata =  creatOutDataMsg();
        odata.addData((String)umsg.getParam("content"));
        putOutData(odata);
    }

    private void input(Object fromWho, TLMsg msg) {
      String username =  getUserData("username");
      String [] inputname ={"username","passwd"};
      TLMsg  input=getUserData(inputname);
        String username1= (String) input.getParam("username");
        if(username1==null)
            username1="";
        String passwd = (String) input.getParam("passwd");
        if(passwd==null)
            passwd="";
        putMsg(getUserObjName(),createMsg().setAction("login")
                .setParam("userName",username).setParam("role","user").
                        setParam("uid","15645902298"));
        outData odata =  creatOutDataMsg();
        odata.addData("用户输入:");
        odata.addData("username",username);
        odata.addData("inputUsername",username1);
        odata.addData("inputPasswd",passwd);
        putOutData(odata);
    }

    private void baidu(Object fromWho, TLMsg msg) {
        putLog(" 进程id: "+Thread.currentThread().getName(),LogLevel.INFO,"baidu");
        webclient("http://www.baidu.com");
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    protected void index(Object fromWho, TLMsg msg) {
        HttpServletRequest request = getRequest();
        ServletContext context= request.getServletContext();
        outData odata =  creatOutDataMsg("index");
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
    protected void test(Object fromWho, TLMsg msg) {
       HttpServletRequest request =getRequest();
       outData odata =  creatOutDataMsg();
        odata.addData("<html><head><title>RequestInfo</TITLE></head>");
        odata.addData("<body>");
        //打印服务器端的IP地址
        odata.addData("this is appcenter test ");
        odata.addData("<br>LocalAddr: "+request.getLocalAddr());
        //打印服务器端的的主机名
        odata.addData("<br>LocalName: "+request.getLocalName());
        odata.addData("</body></html>");
        putOutData(odata);
    }
    private    void webclient(String url){
        TLMsg msg= new TLMsg () ;
        msg.setAction("get")
         //       .setMsgId("webServer")
                .setDestination("httpClient")
                .setParam("url",url);
        TLMsg resultMsg =putMsg("appCenter",msg);
        String response;
        if(resultMsg.getParam("error")!=null && (boolean)resultMsg.getParam("error")==true){
            response="网络延时";
        }
        else
            response= (String) resultMsg.getParam(WEBRESPONSE);
        outData odata =  creatOutDataMsg();
        odata.addData(response);
        putOutData(odata);
    }

}
