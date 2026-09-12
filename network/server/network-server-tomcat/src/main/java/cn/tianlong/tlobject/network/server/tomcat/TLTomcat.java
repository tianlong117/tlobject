package cn.tianlong.tlobject.network.server.tomcat;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.servletutils.TLServletDispatch;
import cn.tianlong.tlobject.utils.TLDataUtils;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.servlets.DefaultServlet;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;

import javax.servlet.ServletException;
import java.util.ArrayList;

import static java.lang.Thread.sleep;

/**
 * Tomcat 嵌入式服务器模块。
 * 使用公共 TLServletDispatch 处理请求。
 *
 * @author tianlong
 */
public class TLTomcat extends TLBaseModule {
    protected String host = "0.0.0.0";
    protected int port = 8080;
    protected int httpsPort = 8443;
    protected Tomcat tomcat;
    protected String serverName;
    protected String resourceBase;
    protected String contextPath = "/";
    protected String servletPath = "/";
    protected String webappDir = "";
    protected String baseDir = "temp";
    protected String sslCerFile = "/";
    protected String sslCerFilePwd = "/";
    protected String connector = "http";
    protected ArrayList<String> connectorList;
    /** HTTPS connector 是否真的创建成功（创建失败时启动日志要如实反映，不能无条件打 "HTTPS started"） */
    protected boolean httpsStarted = false;
    protected int minThreads = 10;
    protected int maxThreads = 200;
    protected int idleTimeout = 30000;
    private TLServletDispatch servletdispatch;

    public TLTomcat() {
        super();
    }

    public TLTomcat(String name) {
        super(name);
    }

    public TLTomcat(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("serverName") != null)
                serverName = params.get("serverName");
            if (params.get("baseDir") != null)
                baseDir = params.get("baseDir");
            if (params.get("webappDir") != null)
                webappDir = params.get("webappDir");
            if (params.get("connector") != null)
                connector = params.get("connector");
            // 修复：使用 "host" 作为配置键
            if (params.get("host") != null)
                host = params.get("host");
            if (params.get("minThreads") != null && !params.get("minThreads").isEmpty())
                minThreads = Integer.parseInt(params.get("minThreads"));
            if (params.get("maxThreads") != null && !params.get("maxThreads").isEmpty())
                maxThreads = Integer.parseInt(params.get("maxThreads"));
            if (params.get("idleTimeout") != null && !params.get("idleTimeout").isEmpty())
                idleTimeout = Integer.parseInt(params.get("idleTimeout"));
            if (params.get("port") != null && !params.get("port").isEmpty())
                port = Integer.parseInt(params.get("port"));
            if (params.get("httpsPort") != null && !params.get("httpsPort").isEmpty())
                httpsPort = Integer.parseInt(params.get("httpsPort"));
            if (params.get("resourceBase") != null)
                resourceBase = params.get("resourceBase");
            if (params.get("contextPath") != null)
                contextPath = params.get("contextPath");
            if (params.get("servletPath") != null)
                servletPath = params.get("servletPath");
            if (params.get(SSL_SCERFILE) != null)
                sslCerFile = params.get(SSL_SCERFILE);
            if (params.get(SSL_SCERFILE_PWD) != null)
                sslCerFilePwd = params.get(SSL_SCERFILE_PWD);
        }
        if (webappDir.isEmpty())
            webappDir = resourceBase;
        connectorList = TLDataUtils.splitStrToList(connector, ";");
    }

    @Override
    protected TLBaseModule init() {
        initServer();
        return this;
    }

    protected void initServer() {
        tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir);

        // 创建 Connectors
        if (connectorList.contains("http")) {
            Connector httpConnector = new Connector();
            httpConnector.setPort(port);
            httpConnector.setProperty("connectionTimeout", String.valueOf(idleTimeout));
            httpConnector.setProperty("maxThreads", String.valueOf(maxThreads));
            httpConnector.setProperty("minSpareThreads", String.valueOf(minThreads));
            tomcat.getService().addConnector(httpConnector);
            putLog("Tomcat HTTP Connector: " + host + ":" + port, LogLevel.INFO);
        }

        if (connectorList.contains("https")) {
            try {
                Connector httpsConnector = createSslConnector();
                tomcat.getService().addConnector(httpsConnector);
                httpsStarted = true;
                putLog("Tomcat HTTPS Connector: " + host + ":" + httpsPort, LogLevel.INFO);
            } catch (Exception e) {
                // 原来是静默降级：配了 https 但证书错，服务器照常启动且后面的日志还会打"HTTPS started"，
                // 运维可能几个月后才发现。这里明确记为 ERROR 并在启动日志里如实反映。
                putLog("HTTPS 配置错误，HTTPS 未启用: " + e.getMessage(), LogLevel.ERROR);
                e.printStackTrace();
            }
        }

        // 创建 Context
        Context context;
        if (params.get("contextType") == null || params.get("contextType").equals("webapp")) {
            context = tomcat.addWebapp(contextPath, webappDir);
        } else {
            // 普通 Context 模式，使用自定义 Servlet
            context = tomcat.addContext(contextPath, webappDir);
            initServletDispatch(context);
        }
        // 添加欢迎文件
        context.addWelcomeFile("index.html");
        context.addWelcomeFile("index.htm");
        context.addWelcomeFile("index");
    }

    private Connector createSslConnector() throws Exception {
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
        sslConfig.addCertificate(certConfig);
        httpsConnector.addSslHostConfig(sslConfig);

        return httpsConnector;
    }

    private void initServletDispatch(Context context) {
        // 空值保护
        if (servletPath == null || servletPath.isEmpty()) {
            servletPath = "/";
        }
        if (!servletPath.startsWith("/")) {
            servletPath = "/" + servletPath;
        }

        servletdispatch = new TLServletDispatch(name, moduleFactory);
        try {
            servletdispatch.init();
        } catch (ServletException e) {
            putLog("Servlet 初始化失败: " + e.getMessage(), LogLevel.ERROR);
            e.printStackTrace();
            return;
        }

        // 添加 DefaultServlet 处理静态资源
        tomcat.addServlet(context, "default", new DefaultServlet());
        context.addServletMappingDecoded("/", "default");

        // 添加分发 Servlet
        Wrapper wrapper = tomcat.addServlet(context, "servletdispatch", servletdispatch);
        String dynamicPath = servletPath;
        if (dynamicPath.equals("/")) {
            dynamicPath = "/app/*";
        } else {
            if (!dynamicPath.endsWith("/*")) {
                dynamicPath = dynamicPath.endsWith("/") ? dynamicPath + "*" : dynamicPath + "/*";
            }
        }
        wrapper.addMapping(dynamicPath);
        putLog("Servlet 映射: " + dynamicPath + " -> TLServletDispatch, / -> DefaultServlet", LogLevel.DEBUG);
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case "run":
                return run(fromWho, msg);
            case "stop":
                return doStop();
            default:
                return null;
        }
    }

    private TLMsg doStop() {
        if (tomcat != null) {
            try {
                if (servletdispatch != null) {
                    servletdispatch.setIsStartup(false);
                    int number = servletdispatch.getNowRequestNumber();
                    long startTime = System.currentTimeMillis();
                    while (number > 0) {
                        try {
                            sleep(2000);
                            if ((System.currentTimeMillis() - startTime) > 180000)
                                break;
                            number = servletdispatch.getNowRequestNumber();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                tomcat.stop();
                tomcat.destroy();
                putLog("Tomcat server stopped", LogLevel.INFO);
                return createMsg().setParam(RESULT, true);
            } catch (Exception e) {
                putLog("停止 Tomcat 失败: " + e.getMessage(), LogLevel.ERROR);
                e.printStackTrace();
                return createMsg().setParam(RESULT, false);
            }
        }
        return createMsg().setParam(RESULT, false);
    }

    private TLMsg run(Object fromWho, TLMsg msg) {
        try {
            tomcat.start();
        } catch (LifecycleException e) {
            putLog("Tomcat server start failure: " + host + ":" + port, LogLevel.ERROR);
            e.printStackTrace();
            return createMsg().setParam(RESULT, false);
        }

        if (connectorList.contains("http"))
            putLog("Tomcat HTTP started: " + host + ":" + port, LogLevel.INFO);
        if (connectorList.contains("https"))
            // 只有 connector 真的加进去了才算启动，否则如实报未启用（原来无条件打 "started" 会误导）
            putLog(httpsStarted ? "Tomcat HTTPS started: " + host + ":" + httpsPort
                                : "Tomcat HTTPS 未启用（connector 创建失败）",
                   httpsStarted ? LogLevel.INFO : LogLevel.ERROR);

        // 异步等待，避免阻塞消息线程
        Thread waitThread = new Thread(() -> {
            tomcat.getServer().await();
            putLog("Tomcat server await ended", LogLevel.INFO);
        }, "tomcat-wait-" + name);
        waitThread.setDaemon(true);
        waitThread.start();

        return createMsg().setParam(RESULT, true);
    }

    @Override
    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        return doStop();
    }
}