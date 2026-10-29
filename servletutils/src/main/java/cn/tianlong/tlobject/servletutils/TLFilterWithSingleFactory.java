package cn.tianlong.tlobject.servletutils;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static cn.tianlong.tlobject.base.TLObjectFactory.FACTORY_P_MODULENAME;
import static cn.tianlong.tlobject.base.TLParamString.FACTORY_REGISTINFACTORY;
import static cn.tianlong.tlobject.base.TLParamString.INSTANCE;
import static cn.tianlong.tlobject.servletutils.TLParamString.M_APPCENTER;
import static java.lang.Thread.sleep;

public class TLFilterWithSingleFactory implements Filter{

    private  String filterName ;
    protected Map<String,HttpServletRequest> requestMap;
    protected Map<String,HttpServletResponse>responseMap ;
    protected Map<String,HashMap<String ,Object>>threadDatas ;
    protected TLObjectFactory moduleFactory;
    protected int initialCapacity=256;
    protected TLBaseModule appCenter;
    /** destroy 置 false、请求线程读：需 volatile 保证关闭瞬间能立刻看到 */
    protected volatile boolean isStartup =true ;
    public  void setIsStartup (boolean isStartup){
        this.isStartup =isStartup ;
    }
    public String  getFilterName(){
        return filterName ;
    }
    public void init(FilterConfig config) throws ServletException {
        Filter.super.init(config);
        filterName =config.getFilterName();
        String  startup=config.getInitParameter("startup");
        if(startup !=null && startup.equals("no"))
        {
            System.out.println(filterName+" has no statup");
            isStartup =false ;
            return;
        }
        String  initialCapacityOfConf=config.getInitParameter("initialCapacity");
        if(initialCapacityOfConf !=null && !initialCapacityOfConf.isEmpty() )
            initialCapacity= Integer.parseInt(initialCapacityOfConf);
        String  initConfigPath=config.getInitParameter("configPath");
        if(initConfigPath==null || initConfigPath.isEmpty())
            initConfigPath="conf";
        ServletContext context = config.getServletContext();
        String logConfigFile =context.getRealPath("/WEB-INF/"+initConfigPath+"/log4j2.xml");
        System.setProperty("log4j.configurationFile",logConfigFile);
        String configDir =context.getRealPath("/WEB-INF")+File.separator+initConfigPath;
        String  initConfigFile=config.getInitParameter("configFileOfFactory");
        if(initConfigFile==null || initConfigFile.isEmpty())
            initConfigFile="moduleFactory_config.xml";
        String factoryConfigFile = configDir +File.separator + initConfigFile;
        moduleFactory = new TLObjectFactory(filterName,factoryConfigFile,configDir);
        String classPath =context.getRealPath("/WEB-INF/classes") ;
        moduleFactory.setClassPath(classPath);
        moduleFactory.startFactory(null,null);
        context.setAttribute( filterName, moduleFactory);
        registInfactory(moduleFactory, "servletContext", context);
        // 与 TLServletDispatch 同款：工厂里已有这三张表就复用，别覆盖注册
        //（多入口共用一个工厂时，后启动者覆盖会让先启动者的请求上下文失效）
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
        moduleFactory.putLog(filterName+" is statup,configPath:"+configDir,LogLevel.INFO,"filterInit");
        moduleFactory.boot();
        appCenter = (TLBaseModule) moduleFactory.getModule(M_APPCENTER);
        // 与 TLServletDispatch 一致：appCenter 配成别的模块类时不能盲转，否则启动即 CCE
        if (appCenter instanceof TLWAPPCenter) {
            ((TLWAPPCenter) appCenter).setFilter(this);
            ((TLWAPPCenter) appCenter).setFilterName(filterName);
        } else {
            moduleFactory.putLog(filterName + " appCenter is not TLWAPPCenter: "
                    + (appCenter == null ? "null" : appCenter.getClass().getName()),
                    LogLevel.ERROR, "filterInit");
        }
    }

    /** 请求终点 filter：全权处理请求并直接写响应，<b>不调用 chain.doFilter</b>（设计如此，不是漏写）。
     *  需要继续走后续 filter/servlet 的场景请用 TLServletDispatch，不要改这里。 */
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws ServletException, IOException
    {
       if(isStartup ==false){
           resp.setContentType("text/html;charset=utf-8");//设置编码格式，以防前端页面出现中文乱码
           PrintWriter printWriter = resp.getWriter();//创建输出流
           printWriter.println("<h1>应用关闭</h1>");
           printWriter.flush();
           //   chain.doFilter(req,resp);
           return;
       }
        long startTime = System.currentTimeMillis();
        String threadName=Thread.currentThread().getName();
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;
        HashMap<String ,Object> datas =new HashMap<>();
        datas.put("startTime",startTime);
        requestMap.put(threadName, request);
        responseMap.put(threadName,  response);
        threadDatas.put(threadName,datas);
        try {
            String uri= request.getRequestURI();
            if (uri == null || uri.isEmpty())
                return ;
            appCenter.getMsg(moduleFactory, new TLMsg().setAction("start").setParam("uri",uri));
        } finally {
            requestMap.remove(threadName);
            responseMap.remove(threadName);
            threadDatas.remove(threadName);
        }
        Long nowTime = System.currentTimeMillis();
        Long runtime = nowTime - startTime;
        moduleFactory.putLog(filterName+ " 运行时间：" + runtime,LogLevel.DEBUG,"doFilter");
    }
    public void destroy() {
        if(isStartup ==false){
            return;
        }
        isStartup=false ;
        int sessionNumb =responseMap == null ? 0 : responseMap.size();
        int maxWaitSeconds = 30;
        int waited = 0;
        while (sessionNumb !=0 && waited < maxWaitSeconds)
        {
            try {
                moduleFactory.putLog(filterName+ " destroying,session number:" + sessionNumb,LogLevel.INFO,"doFilter");
                sleep(1000);
                waited++;
                sessionNumb =responseMap == null ? 0 : responseMap.size();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (sessionNumb != 0) {
            // 超时强关前把还挂在请求上的线程名列出来，便于排查是谁没退出
            String active = requestMap == null ? "?" : String.join(",", requestMap.keySet());
            moduleFactory.putLog(filterName+ " destroy timeout, remaining sessions:" + sessionNumb
                    + " threads:[" + active + "]", LogLevel.WARN, "destroy");
        }
        moduleFactory.destroyModule();
    }

    protected void registInfactory(TLObjectFactory modulefactory, String name, Object object)
    {
        TLMsg registInFactoryMsg = new TLMsg().setAction(FACTORY_REGISTINFACTORY)
                .setParam(FACTORY_P_MODULENAME, name)
                .setParam(INSTANCE, object);
        modulefactory.putMsg(modulefactory, registInFactoryMsg);
    }
}
