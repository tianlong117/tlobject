package cn.tianlong.tlobject.network.server.jetty;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.servletutils.TLServletDispatch;
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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

import static java.lang.Thread.sleep;

/**
 * Jetty 嵌入式服务器模块。
 * 使用公共 TLServletDispatch 处理请求。
 *
 * @author tianlong
 */
public class TLJettyServer extends TLBaseModule {
    protected String host = "0.0.0.0";
    protected int port = 8080;
    protected int httpsPort = 8443;
    protected Server server;
    protected String serverName;
    protected String resourceBase;
    protected String contextPath = "/";
    protected String servletPath = "/";
    protected String sslCerFile = "/";
    protected String sslCerFilePwd = "/";
    protected String connector = "http";
    protected int minThreads = 10;
    protected int maxThreads = 200;
    protected int idleTimeout = 30000;
    private TLServletDispatch servletdispatch;
    /** 额外 servlet 注册：path=类名;path=类名（类需有 (String, TLObjectFactory) 构造或默认构造） */
    protected String extraServlets;

    public TLJettyServer() {
        super();
    }

    public TLJettyServer(String name) {
        super(name);
    }

    public TLJettyServer(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("serverName") != null)
                serverName = params.get("serverName");
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
            if (params.get("extraServlets") != null)
                extraServlets = params.get("extraServlets");
        }
    }

    @Override
    protected TLBaseModule init() {
        initServer();
        return this;
    }

    protected void initServer() {
        server = createServer();
        ServletContextHandler context = initContext();
        if (resourceBase != null) {
            context.setResourceBase(resourceBase);
        }
        context.setContextPath(contextPath);
        server.setHandler(context);
        if (!(context instanceof WebAppContext)) {
            initServletDispatch(context);
        }
    }

    private Server createServer() {
        server = new Server(createThreadPool());
        Connector[] connectors = getConnectors();
        if (connectors != null && connectors.length > 0) {
            server.setConnectors(connectors);
        }
        return server;
    }

    private ThreadPool createThreadPool() {
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMinThreads(minThreads);
        threadPool.setMaxThreads(maxThreads);
        threadPool.setIdleTimeout(idleTimeout);
        return threadPool;
    }

    private Connector[] getConnectors() {
        ArrayList<String> connectorList = TLDataUtils.splitStrToList(connector, ";");
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSecureScheme("https");
        httpConfig.setSecurePort(httpsPort);
        httpConfig.setOutputBufferSize(32768);

        ServerConnector http = null;
        if (connectorList.contains("http")) {
            http = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
            http.setHost(host);
            http.setPort(port);
            http.setIdleTimeout(idleTimeout);
            putLog("Jetty HTTP Connector: " + host + ":" + port, LogLevel.INFO);
        }

        ServerConnector https = null;
        if (connectorList.contains("https")) {
            try {
                Path keystore = Paths.get(sslCerFile).toAbsolutePath();
                SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
                sslContextFactory.setKeyStorePath(keystore.toString());
                sslContextFactory.setKeyStorePassword(sslCerFilePwd);
                sslContextFactory.setKeyManagerPassword(sslCerFilePwd);

                HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
                SecureRequestCustomizer src = new SecureRequestCustomizer();
                src.setStsMaxAge(2000);
                src.setStsIncludeSubDomains(true);
                httpsConfig.addCustomizer(src);

                https = new ServerConnector(server,
                        new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
                        new HttpConnectionFactory(httpsConfig));
                https.setHost(host);
                https.setPort(httpsPort);
                https.setIdleTimeout(idleTimeout);
                putLog("Jetty HTTPS Connector: " + host + ":" + httpsPort, LogLevel.INFO);
            } catch (Exception e) {
                putLog("SSL 配置错误: " + e.getMessage(), LogLevel.ERROR);
                e.printStackTrace();
            }
        }

        if (http != null && https != null)
            return new Connector[]{http, https};
        else if (http != null)
            return new Connector[]{http};
        else if (https != null)
            return new Connector[]{https};
        else
            return null;
    }

    private void initServletDispatch(ServletContextHandler context) {
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
        } catch (Exception e) {
            putLog("Servlet 初始化失败: " + e.getMessage(), LogLevel.ERROR);
            e.printStackTrace();
            return;
        }

        // 动态路径：如果 servletPath 是 "/" 改为 "/app/*" 避免与静态资源冲突
        String dynamicPath = servletPath;
        if (dynamicPath.equals("/")) {
            dynamicPath = "/app/*";
        } else {
            if (!dynamicPath.endsWith("/*")) {
                dynamicPath = dynamicPath.endsWith("/") ? dynamicPath + "*" : dynamicPath + "/*";
            }
        }
        context.addServlet(new ServletHolder(servletdispatch), dynamicPath);
        // 静态资源由 DefaultServlet 处理
        context.addServlet(DefaultServlet.class, "/*");
        addExtraServlets(context);
        putLog("Servlet 映射: " + dynamicPath + " -> TLServletDispatch, /* -> DefaultServlet", LogLevel.DEBUG);
    }

    /**
     * 注册额外 servlet（extraServlets 参数，格式 "path=类名;path=类名"）。
     * 优先使用 (String, TLObjectFactory) 构造器（同 TLServletDispatch 模式），失败回退默认构造器。
     */
    private void addExtraServlets(ServletContextHandler context) {
        if (extraServlets == null || extraServlets.trim().isEmpty()) return;
        for (String pair : extraServlets.split(";")) {
            pair = pair.trim();
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) {
                putLog("extraServlets 配置错误: " + pair + "（格式 path=类名）", LogLevel.WARN);
                continue;
            }
            String path = pair.substring(0, eq).trim();
            String cls = pair.substring(eq + 1).trim();
            try {
                Class<?> clazz = Class.forName(cls);
                Object servlet;
                try {
                    servlet = clazz.getConstructor(String.class, TLObjectFactory.class)
                            .newInstance(name + "-" + cls.substring(cls.lastIndexOf('.') + 1), moduleFactory);
                } catch (NoSuchMethodException nsme) {
                    servlet = clazz.getDeclaredConstructor().newInstance();
                }
                context.addServlet(new ServletHolder((javax.servlet.Servlet) servlet), path);
                putLog("extra servlet: " + path + " -> " + cls, LogLevel.INFO);
            } catch (Exception e) {
                putLog("extraServlets 实例化失败: " + cls + " - " + e.getMessage(), LogLevel.ERROR);
            }
        }
    }

    protected ServletContextHandler initContext() {
        ServletContextHandler context;
        if (params.get("contextType") == null || !params.get("contextType").equals("webapp")) {
            context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        } else {
            WebAppContext webapp = new WebAppContext();
            webapp.setDescriptor("/WEB-INF/web.xml");
            context = webapp;
        }
        return context;
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
        if (server != null && server.isRunning()) {
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
            try {
                server.stop();
                putLog("Jetty server stopped", LogLevel.INFO);
                return createMsg().setParam(RESULT, true);
            } catch (Exception e) {
                putLog("停止 Jetty 失败: " + e.getMessage(), LogLevel.ERROR);
                e.printStackTrace();
                return createMsg().setParam(RESULT, false);
            }
        }
        return createMsg().setParam(RESULT, false);
    }

    private TLMsg run(Object fromWho, TLMsg msg) {
        try {
            server.start();
        } catch (Exception e) {
            putLog("Jetty server start failure: " + host + ":" + port, LogLevel.ERROR);
            e.printStackTrace();
            return createMsg().setParam(RESULT, false);
        }

        ArrayList<String> connectorList = TLDataUtils.splitStrToList(connector, ";");
        if (connectorList.contains("http"))
            putLog("Jetty HTTP started: " + host + ":" + port, LogLevel.INFO);
        if (connectorList.contains("https"))
            putLog("Jetty HTTPS started: " + host + ":" + httpsPort, LogLevel.INFO);

        // 异步等待，避免阻塞消息线程
        Thread waitThread = new Thread(() -> {
            try {
                server.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                putLog("Jetty wait interrupted", LogLevel.WARN);
            }
            putLog("Jetty server stopped", LogLevel.INFO);
        }, "jetty-wait-" + name);
        waitThread.setDaemon(true);
        waitThread.start();

        return createMsg().setParam(RESULT, true);
    }

    @Override
    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        return doStop();
    }
}