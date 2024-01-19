package cn.tianlong.tlobject.network.server.tomcat;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.servletutils.TLWAPPCenter;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.servlets.DefaultServlet;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static cn.tianlong.tlobject.servletutils.TLParamString.M_APPCENTER;

/**
 * 创建日期：2018/4/23 on 21:00
 * 描述:
 * 作者:tianlong
 */

public class TLTomcat extends TLBaseModule {
    protected  String host ="0.0.0.0";
    protected  int port =8080;
    protected  int httpsPort =8443;
    protected  Tomcat tomcat;
    protected  String serverName;
    protected  String resourceBase;
    protected  String contextPath ="/";
    protected  String servletPath ="/";
    protected  String webappDir ="";
    protected  String baseDir="temp";
    protected  String sslCerFile ="/" ;
    protected  String sslCerFilePwd ="/" ;
    protected  String connector="http";
    protected  int minThreads =10;
    protected  int maxThreads =200 ;
    protected  int idleTimeout =30000 ;
    public TLTomcat(){
        super();
    }
    public TLTomcat(String name ){
        super(name);
    }
    public TLTomcat(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected void setModuleParams()
    {
        if(params!=null  ){
            if( params.get("serverName")!=null)
                serverName = params.get("serverName");
            if( params.get("baseDir")!=null)
                baseDir = params.get("baseDir");
            if( params.get("webappDir")!=null)
                webappDir = params.get("webappDir");
            if( params.get("connector")!=null)
                connector =params.get("connector");
            if( params.get(host)!=null)
                host =params.get(host);
            minThreads =(params.get("minThreads")!=null && !params.get("minThreads").isEmpty())
                    ?Integer.parseInt(params.get("minThreads")):minThreads;
            maxThreads =(params.get("maxThreads")!=null&& !params.get("maxThreads").isEmpty())
                    ?Integer.parseInt(params.get("maxThreads")):maxThreads;
            idleTimeout =(params.get("idleTimeout")!=null&& !params.get("idleTimeout").isEmpty())
                    ?Integer.parseInt(params.get("idleTimeout")):idleTimeout;
            port =(params.get("port")!=null && !params.get("port").isEmpty())
                    ?Integer.parseInt(params.get("port")):port;
            httpsPort =(params.get("httpsPort")!=null && !params.get("httpsPort").isEmpty())
                    ?Integer.parseInt(params.get("httpsPort")):httpsPort;
            if( params.get("resourceBase")!=null)
                resourceBase = params.get("resourceBase");
            if( params.get("contextPath")!=null)
                contextPath = params.get("contextPath");
            if( params.get("servletPath")!=null)
                servletPath = params.get("servletPath");
            if( params.get(SSL_SCERFILE)!=null)
                sslCerFile =params.get(SSL_SCERFILE);
            if( params.get(SSL_SCERFILE_PWD)!=null)
                sslCerFilePwd =params.get(SSL_SCERFILE_PWD);

        }
        if(webappDir.isEmpty())
            webappDir =resourceBase ;
    }
    @Override
    protected TLBaseModule init() {
        initServer();
        return this ;
    }

    protected void initServer() {
        tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir);
        tomcat.setPort(port);
        Connector connector=tomcat.getConnector();
        Connector httpsConnector =createSslConnector();
        Context context ;
        if(params.get("contextType") ==null || params.get("contextType").equals("webapp"))
            context= tomcat.addWebapp(contextPath, webappDir);
        else {
            TLServletDispatch   servletdispatch = new TLServletDispatch(name,moduleFactory);
            try {
                servletdispatch.init();
            } catch (ServletException e) {
                e.printStackTrace();
            }
            String lastChar = (String)servletPath.substring(servletPath.length()-1,servletPath.length());
            String path ;
            if(lastChar.equals("/"))
                path=servletPath+"*";
            else
                path=servletPath+"/*";
            context = tomcat.addContext(contextPath,webappDir);
            tomcat.addServlet(context,"default",new DefaultServlet()); // 处理静态文件
            context.addServletMappingDecoded("/","default");
            Wrapper wrapperdispatch = tomcat.addServlet(context, "servletdispatch", servletdispatch);
     //       context.addServletMappingDecoded(servletPath, "servletdispatch");
            wrapperdispatch.addMapping(path);
            context.addWelcomeFile("index.html");
            context.addWelcomeFile("index.htm");
            context.addWelcomeFile("index");
        }
        tomcat.setConnector(httpsConnector);
    }
    private Connector createSslConnector(){
        Connector httpsConnector = new Connector();
        httpsConnector.setPort(httpsPort);
        httpsConnector.setSecure(true);
        httpsConnector.setScheme("https");
        Http11NioProtocol protocol = (Http11NioProtocol) httpsConnector.getProtocolHandler();
        protocol.setSSLEnabled(true);
        SSLHostConfig sslConfig = new SSLHostConfig();
        SSLHostConfigCertificate certConfig = new SSLHostConfigCertificate(sslConfig, SSLHostConfigCertificate.Type.RSA);
        certConfig.setCertificateKeystoreFile(sslCerFile);
        certConfig.setCertificateKeystorePassword(sslCerFilePwd);
    //    certConfig.setCertificateKeyAlias("mykeyalias");
        sslConfig.addCertificate(certConfig);

        httpsConnector.addSslHostConfig(sslConfig);
        return httpsConnector;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case "run":
                returnMsg=run( fromWho,  msg);
                break;
            case "stop" :
                returnMsg=stop( fromWho,  msg);
            default:              ;
        }
        return returnMsg;
    }

    private TLMsg stop(Object fromWho, TLMsg msg) {
        if(tomcat !=null)
        {
            try {
                tomcat.stop();
                return createMsg().setParam(RESULT,true) ;
            } catch (Exception e) {
                e.printStackTrace();
                return createMsg().setParam(RESULT,false) ;
            }
        }
        return createMsg().setParam(RESULT,false) ;
    }

    private TLMsg run(Object fromWho, TLMsg msg)
    {
        try {
            tomcat.start();
        } catch (LifecycleException e) {
            putLog("tomcat server start up failure :"+host+ ":"+port,LogLevel.ERROR);
            return createMsg().setParam(RESULT,true);
        }
        putLog("tomcat server start up  :"+host+ ":"+port,LogLevel.INFO);
        tomcat.getServer().await();
        return createMsg().setParam(RESULT,true);
    }

    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        stop(null, null) ;
        return  super.destroy(fromWho,msg);
    }
    protected class TLServletDispatch extends GenericServlet {
        protected TLBaseModule appCenter;
        protected  String  name ;
        protected Map<String,HttpServletRequest> requestMap;
        protected Map<String,HttpServletResponse>responseMap ;
        protected Map<String,HashMap<String ,Object>> threadDatas;
        protected TLObjectFactory moduleFactory;
        protected boolean isStartup =true ;
        protected int initialCapacity=256;

        public TLServletDispatch(String name ,TLObjectFactory factory)
        {
            this.name =name ;
            this.moduleFactory =factory ;
        }
        protected void setInitialCapacity(int initialCapacity)
        {
            this.initialCapacity =initialCapacity ;
        }

        public  void setIsStartup (boolean isStartup){
            this.isStartup =isStartup ;
        }
        public  int getNowRequestNumber(){
            return requestMap.size();
        }
        @Override
        public void init(ServletConfig config) throws ServletException {
            super.init(config);
            requestMap =new ConcurrentHashMap<>( initialCapacity);
            responseMap =new ConcurrentHashMap<>(initialCapacity);
            threadDatas =new ConcurrentHashMap<>(initialCapacity);
            ServletContext context =getServletContext();
            registInfactory( moduleFactory,"servletContext",context);
            registInfactory(moduleFactory, "servletRequest", requestMap);
            registInfactory(moduleFactory, "servletResponse", responseMap);
            registInfactory(moduleFactory, "threadDatas", threadDatas);
            moduleFactory.putLog(name+" is statup,configPath:",LogLevel.INFO,"filterInit");
            appCenter =(TLBaseModule) moduleFactory.getModule(M_APPCENTER);
            ((TLWAPPCenter)appCenter).setFilter(this);
            ((TLWAPPCenter)appCenter).setFilterName(name);
        }
        @Override
        public void service(ServletRequest servletRequest, ServletResponse servletResponse) throws IOException {
            if(isStartup ==false){
                servletResponse.setContentType("text/html;charset=utf-8");//设置编码格式，以防前端页面出现中文乱码
                PrintWriter printWriter = servletResponse.getWriter();//创建输出流
                printWriter.println("<h1>应用关闭</h1>");
                printWriter.flush();
                return;
            }
            long startTime = System.currentTimeMillis();
            String threadName=Thread.currentThread().getName();
            HttpServletRequest request = (HttpServletRequest) servletRequest;
            HttpServletResponse response = (HttpServletResponse) servletResponse;
            HashMap<String ,Object> datas =new HashMap<>();
            datas.put("startTime",startTime);
            requestMap.put(threadName, request);
            responseMap.put(threadName,  response);
            threadDatas.put(threadName,datas);
            String uri= request.getRequestURI();
            if (uri == null || uri.isEmpty())
                return ;

            TLMsg msg = new TLMsg().setAction("start").setParam("uri",uri);
            appCenter.getMsg(moduleFactory, msg);
            requestMap.remove(threadName);
            responseMap.remove(threadName);
            threadDatas.remove(threadName);
            Long nowTime = System.currentTimeMillis();
            Long runtime = nowTime - startTime;
            moduleFactory.putLog(name+ " 运行时间：" + runtime,LogLevel.INFO,"doFilter");
        }
        protected void registInfactory(TLObjectFactory modulefactory, String name, Object object)
        {
            TLMsg registInFactoryMsg = new TLMsg().setAction(FACTORY_REGISTINFACTORY)
                    .setParam(FACTORY_P_MODULENAME, name)
                    .setParam(INSTANCE, object);
            modulefactory.putMsg(modulefactory, registInFactoryMsg);
        }
    }
}
