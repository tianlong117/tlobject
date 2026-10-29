package cn.tianlong.tlobject.servletutils;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static cn.tianlong.tlobject.base.TLParamString.*;
import static cn.tianlong.tlobject.servletutils.TLParamString.M_APPCENTER;

public class TLServletFactory extends GenericServlet {

    private  String servletName;
    protected Map<String,HttpServletRequest> requestMap;
    protected Map<String,HttpServletResponse>responseMap ;
    protected Map<String,HashMap<String ,Object>>threadDatas ;
    protected TLObjectFactory moduleFactory;
    protected TLBaseModule appCenter;
    protected boolean isStartup =true ;
    protected int initialCapacity=256;
    public  void setIsStartup (boolean isStartup){
        this.isStartup =isStartup ;
    }
    public  int getNowRequestNumber(){
        return requestMap == null ? 0 : requestMap.size();   // startup=no 时三张表未初始化
    }
    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        servletName =config.getServletName();
        String  startup=config.getInitParameter("startup");
        if(startup !=null && startup.equals("no"))
        {
            System.out.println(servletName+" has no statup");
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
        moduleFactory = new TLObjectFactory(servletName,factoryConfigFile,configDir);
        moduleFactory.startFactory(null,null);
        context.setAttribute( servletName, moduleFactory);
        registInfactory(moduleFactory, "servletContext", context);
        // 与 TLServletDispatch / TLFilterWithSingleFactory 同款：已有则复用，不覆盖注册
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
        moduleFactory.putLog(servletName+" is statup,configPath:"+configDir,LogLevel.INFO,"filterInit");
        String usefactory=config.getInitParameter("usefactory");
        if( usefactory ==null || usefactory.isEmpty())
            usefactory =context.getInitParameter("usefactory");
        if( usefactory !=null && !usefactory.isEmpty())
        {
            TLObjectFactory factory= (TLObjectFactory) context.getAttribute(usefactory);
            if(factory !=null)
            {
                registInfactory(moduleFactory, usefactory, factory);
                registInfactory(factory, servletName, moduleFactory);
            }
        }
        moduleFactory.boot();
        appCenter = (TLBaseModule) moduleFactory.getModule(M_APPCENTER);
        // 与 TLServletDispatch / Filter 一致：appCenter 未配置或配错类时不能盲转
        if (appCenter instanceof TLWAPPCenter) {
            ((TLWAPPCenter) appCenter).setFilter(this);
            ((TLWAPPCenter) appCenter).setFilterName(servletName);
        } else {
            moduleFactory.putLog(servletName + " appCenter is not TLWAPPCenter: "
                    + (appCenter == null ? "null" : appCenter.getClass().getName()),
                    LogLevel.ERROR, "servletInit");
        }
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
        // init 因 usefactory 找不到而提前返回时，三张表仍为 null——不能直接 put
        if(moduleFactory ==null || requestMap ==null || responseMap ==null || threadDatas ==null
                || appCenter ==null)
            return;
        long startTime = System.currentTimeMillis();
        String threadName=Thread.currentThread().getName();
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
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
        } catch (Exception e) {
            // 与 TLServletDispatch 一致：业务异常转 500，不把栈甩给容器
            moduleFactory.putLog("请求处理异常: " + e.getMessage(), LogLevel.ERROR, "servletService");
            try {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().println("<h1>服务器内部错误</h1>");
            } catch (Exception ignored) {}
        } finally {
            requestMap.remove(threadName);
            responseMap.remove(threadName);
            threadDatas.remove(threadName);
        }
        long runtime = System.currentTimeMillis() - startTime;
        // 每请求一条：INFO 在高并发下会刷屏
        moduleFactory.putLog(servletName + " 运行时间：" + runtime,LogLevel.DEBUG,"doFilter");
    }
    protected void registInfactory(TLObjectFactory modulefactory, String name, Object object)
    {
        TLMsg registInFactoryMsg = new TLMsg().setAction(FACTORY_REGISTINFACTORY)
                .setParam(FACTORY_P_MODULENAME, name)
                .setParam(INSTANCE, object);
        modulefactory.putMsg(modulefactory, registInFactoryMsg);
    }
}
