package cn.tianlong.tlobject.servletutils;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import javax.servlet.GenericServlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static cn.tianlong.tlobject.base.TLParamString.*;
import static cn.tianlong.tlobject.servletutils.TLParamString.M_APPCENTER;

/**
 * 公共 Servlet 分发器，用于将 HTTP 请求转发到 TLObject 框架的 appCenter。
 * 可被 Jetty 和 Tomcat 等嵌入式容器共用。
 *
 * @author tianlong
 */
public class TLServletDispatch extends GenericServlet {

    protected TLBaseModule appCenter;
    protected final String name;
    protected final TLObjectFactory moduleFactory;
    protected Map<String, HttpServletRequest> requestMap;
    protected Map<String, HttpServletResponse> responseMap;
    protected Map<String, HashMap<String, Object>> threadDatas;
    protected boolean isStartup = true;
    protected int initialCapacity = 256;

    public TLServletDispatch(String name, TLObjectFactory moduleFactory) {
        this.name = name;
        this.moduleFactory = moduleFactory;
    }

    public void setInitialCapacity(int initialCapacity) {
        this.initialCapacity = initialCapacity;
    }

    public void setIsStartup(boolean isStartup) {
        this.isStartup = isStartup;
    }

    public int getNowRequestNumber() {
        return requestMap == null ? 0 : requestMap.size();
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        ServletContext context = getServletContext();
        // 多实例共享同一组线程 Map（如 /app/* 与 /api/* 各挂一个 TLServletDispatch）：
        // 后启动的实例复用已注册的 Map，避免覆盖注册导致 getRequest()/getResponse()
        // 取到另一实例的空 Map（线程名隔离，共享安全）。
        // 用 containsModule 探测（getModule 未命中会尝试按类名加载并打 ERROR 日志）
        if (moduleFactory.containsModule("servletRequest")) {
            requestMap = (Map<String, HttpServletRequest>) moduleFactory.getModule("servletRequest");
        } else {
            requestMap = new ConcurrentHashMap<>(initialCapacity);
            registInfactory(moduleFactory, "servletRequest", requestMap);
        }
        if (moduleFactory.containsModule("servletResponse")) {
            responseMap = (Map<String, HttpServletResponse>) moduleFactory.getModule("servletResponse");
        } else {
            responseMap = new ConcurrentHashMap<>(initialCapacity);
            registInfactory(moduleFactory, "servletResponse", responseMap);
        }
        if (moduleFactory.containsModule("threadDatas")) {
            threadDatas = (Map<String, HashMap<String, Object>>) moduleFactory.getModule("threadDatas");
        } else {
            threadDatas = new ConcurrentHashMap<>(initialCapacity);
            registInfactory(moduleFactory, "threadDatas", threadDatas);
        }
        registInfactory(moduleFactory, "servletContext", context);
        moduleFactory.putLog(name + " is startup", LogLevel.INFO, "servletDispatch");

        try {
            appCenter = (TLBaseModule) moduleFactory.getModule(M_APPCENTER);
            if (appCenter == null) {
                moduleFactory.putLog("appCenter 模块未找到，请检查配置", LogLevel.ERROR, "servletDispatch");
                return;
            }
            if (appCenter instanceof TLWAPPCenter) {
                ((TLWAPPCenter) appCenter).setFilter(this);
                ((TLWAPPCenter) appCenter).setFilterName(name);
            } else {
                moduleFactory.putLog("appCenter 不是 TLWAPPCenter 类型", LogLevel.WARN, "servletDispatch");
            }
        } catch (Exception e) {
            moduleFactory.putLog("appCenter 初始化失败: " + e.getMessage(), LogLevel.ERROR);
            e.printStackTrace();
        }
    }

    @Override
    public void service(ServletRequest servletRequest, ServletResponse servletResponse) throws IOException {
        if (!isStartup) {
            servletResponse.setContentType("text/html;charset=utf-8");
            PrintWriter printWriter = servletResponse.getWriter();
            printWriter.println("<h1>应用关闭</h1>");
            printWriter.flush();
            return;
        }

        if (appCenter == null) {
            servletResponse.setContentType("text/html;charset=utf-8");
            servletResponse.getWriter().println("<h1>服务未就绪</h1>");
            return;
        }

        long startTime = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        HashMap<String, Object> datas = new HashMap<>();
        datas.put("startTime", startTime);
        requestMap.put(threadName, request);
        responseMap.put(threadName, response);
        threadDatas.put(threadName, datas);

        try {
            String uri = request.getRequestURI();
            if (uri != null && !uri.isEmpty()) {
                TLMsg msg = new TLMsg().setAction("start").setParam("uri", uri);
                appCenter.getMsg(moduleFactory, msg);
            }
        } catch (Exception e) {
            moduleFactory.putLog("请求处理异常: " + e.getMessage(), LogLevel.ERROR);
            e.printStackTrace();
            try {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().println("<h1>服务器内部错误</h1>");
            } catch (IOException ignored) {}
        } finally {
            requestMap.remove(threadName);
            responseMap.remove(threadName);
            threadDatas.remove(threadName);
        }

        long runtime = System.currentTimeMillis() - startTime;
        moduleFactory.putLog(name + " 运行时间：" + runtime + "ms", LogLevel.INFO, "servletDispatch");
    }

    protected void registInfactory(TLObjectFactory modulefactory, String name, Object object) {
        if (modulefactory == null || object == null) {
            return;
        }
        TLMsg registInFactoryMsg = new TLMsg()
                .setAction(FACTORY_REGISTINFACTORY)
                .setParam(FACTORY_P_MODULENAME, name)
                .setParam(INSTANCE, object);
        modulefactory.putMsg(modulefactory, registInFactoryMsg);
    }
}
