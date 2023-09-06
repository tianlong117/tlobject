package cn.tianlong.tlobject.network.server.jetty;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;


import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;

import javax.servlet.ServletException;

import static java.lang.Thread.sleep;


/**
 * 创建日期：2018/4/23 on 21:00
 * 描述:
 * 作者:tianlong
 */

public class TLJettyServer extends TLBaseModule {
    protected  String host ="0.0.0.0";
    protected  int port =8080;
    protected  int httpsPort =8443;
    protected  Server server;
    protected  String serverName;
    protected  String resourceBase;
    protected  String contextPath ="/";
    protected  String servletPath ="/";
    protected  String sslCerFile ="/" ;
    protected  String sslCerFilePwd ="/" ;
    protected  String connector="http";
    protected  int minThreads =10;
    protected  int maxThreads =200 ;
    protected  int idleTimeout =30000 ;
    private TLServletDispatch servletdispatch ;
    public TLJettyServer(){
        super();
    }
    public TLJettyServer(String name ){
        super(name);
    }
    public TLJettyServer(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected void setModuleParams()
    {
        if(params!=null  ){
            if( params.get("serverName")!=null)
                serverName = params.get("serverName");
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
    }
    @Override
    protected TLBaseModule init() {
        initServer();
        return this ;
    }

    protected void initServer() {
        server = createServer();
        ServletContextHandler  context =initContext();
        context.setResourceBase(resourceBase);
        context.setContextPath(contextPath);
        server.setHandler(context);
        if(!(context instanceof WebAppContext)){
            initServletDispatch(context);
        }
    }

    private Server createServer() {
        server =  new Server(createThreadPool());
        Connector[] connectors =getConnector();
        server.setConnectors(connectors);
        return server ;
    }
    private ThreadPool createThreadPool() {
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMinThreads(minThreads);
        threadPool.setMaxThreads(maxThreads);
        return threadPool;
    }
    private Connector[] getConnector() {
        ArrayList<String> connectorList =TLDataUtils.splitStrToList(connector,";");
        // HTTP Configuration
        // HttpConfiguration is a collection of configuration information
        // appropriate for http and https. The default scheme for http is
        // <code>http</code> of course, as the default for secured http is
        // <code>https</code> but we show setting the scheme to show it can be
        // done. The port for secured communication is also set here.
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSecureScheme("https");
        httpConfig.setSecurePort(httpsPort);
        httpConfig.setOutputBufferSize(32768);

        // HTTP connector
        // The first server connector we create is the one for http, passing in
        // the http configuration we configured above so it can get things like
        // the output buffer size, etc. We also set the port (8080) and
        // configure an idle timeout.
        ServerConnector http = null;
        if(connectorList.contains("http"))
        {
            http = new ServerConnector(server,
                    new HttpConnectionFactory(httpConfig));
            http.setHost(host);
            http.setPort(port);
            http.setIdleTimeout(idleTimeout);
            putLog("jetty server http connector :"+host+ ":"+port,LogLevel.INFO);
        }
        ServerConnector https = null;
        if(connectorList.contains("https"))
        {
        // SSL Context Factory for HTTPS
        // SSL requires a certificate so we configure a factory for ssl contents
        // with information pointing to what keystore the ssl connection needs
        // to know about. Much more configuration is available the ssl context,
        // including things like choosing the particular certificate out of a
        // keystore to be used.
        Path keystore = Paths.get(sslCerFile).toAbsolutePath();
        SslContextFactory sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore.toString());
        sslContextFactory.setKeyStorePassword(sslCerFilePwd);
        sslContextFactory.setKeyManagerPassword(sslCerFilePwd);

        // OPTIONAL: Un-comment the following to use Conscrypt for SSL instead of
        // the native JSSE implementation.

        //Security.addProvider(new OpenSSLProvider());
        //sslContextFactory.setProvider("Conscrypt");

        // HTTPS Configuration
        // A new HttpConfiguration object is needed for the next connector and
        // you can pass the old one as an argument to effectively clone the
        // contents. On this HttpConfiguration object we add a
        // SecureRequestCustomizer which is how a new connector is able to
        // resolve the https connection before handing control over to the Jetty
        // Server.

            HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
            SecureRequestCustomizer src = new SecureRequestCustomizer();
            src.setStsMaxAge(2000);
            src.setStsIncludeSubDomains(true);
            httpsConfig.addCustomizer(src);

            // HTTPS connector
            // We create a second ServerConnector, passing in the http configuration
            // we just made along with the previously created ssl context factory.
            // Next we set the port and a longer idle timeout.
            https = new ServerConnector(server,
                    new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
                    new HttpConnectionFactory(httpsConfig));
            https.setHost(host);
            https.setPort(httpsPort);
            https.setIdleTimeout(idleTimeout);
            putLog("jetty server https connector :"+host+ ":"+httpsPort,LogLevel.INFO);
        }

        // Here you see the server having multiple connectors registered with
        // it, now requests can flow into the server from both http and https
        // urls to their respective ports and be processed accordingly by jetty.
        // A simple handler is also registered with the server so the example
        // has something to pass requests off to.

        // Set the connectors
        if(http !=null && https !=null)
          return new Connector[]{http, https};
        else if(http !=null)
            return new Connector[]{http};
        else if(https !=null)
            return new Connector[]{https};
        else
            return null ;
    }

    private void initServletDispatch(ServletContextHandler  context) {
        servletdispatch = new TLServletDispatch(name,moduleFactory);
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
        context.addServlet(new ServletHolder(servletdispatch), path);
        // 添加 default servlet
        context.addServlet(DefaultServlet.class, contextPath);
    }

    protected ServletContextHandler initContext() {
        ServletContextHandler context;
        if(params.get("contextType") ==null || !params.get("contextType").equals("webapp"))
          context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        else
        {
            context = new WebAppContext();
            ((WebAppContext)context).setDescriptor("/WEB-INF/web.xml");
        }
        return context ;
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
        if(server !=null)
        {
            servletdispatch.setIsStartup(false);
            int number =  servletdispatch.getNowRequestNumber();
            Long  startTime = System.currentTimeMillis();
            while (number >0){
                try {
                    sleep(2000);
                    Long nowTime = System.currentTimeMillis();
                    if((nowTime - startTime)>180000)
                        break;
                    number =  servletdispatch.getNowRequestNumber();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            try {
                server.stop();
                return createMsg().setParam(RESULT,true) ;
            } catch (Exception e) {
                e.printStackTrace();
                return createMsg().setParam(RESULT,false) ;
            }
        }
        return createMsg().setParam(RESULT,false) ;
    }

    private TLMsg run(Object fromWho, TLMsg msg) {
        try {
            server.start();
        } catch (Exception e) {
            e.printStackTrace();
            putLog("jetty server start up failure :"+host+ ":"+port,LogLevel.INFO);
            return null;
        }
        ArrayList<String> connectorList =TLDataUtils.splitStrToList(connector,";");
        if(connectorList.contains("http"))
            putLog("jetty server start http :"+host+ ":"+port,LogLevel.INFO);
        if(connectorList.contains("https"))
            putLog("jetty server start https :"+host+ ":"+httpsPort,LogLevel.INFO);
        try {
            server.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return null;
    }

    protected TLMsg destroy(Object fromWho, TLMsg msg) {

        return  super.destroy(fromWho,msg);
    }

}
