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
import static java.lang.Thread.sleep;

public class TLFilterWithSingleFactory implements Filter{

    private  String filterName ;
    protected Map<String,HttpServletRequest> requestMap;
    protected Map<String,HttpServletResponse>responseMap ;
    protected Map<String,HashMap<String ,Object>>sessionDatas ;
    protected TLObjectFactory moduleFactory;
    protected int initialCapacity=256;
    protected TLBaseModule appCenter;
    protected boolean isStartup =true ;
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
        requestMap =new ConcurrentHashMap<>( initialCapacity);
        responseMap =new ConcurrentHashMap<>(initialCapacity);
        sessionDatas =new ConcurrentHashMap<>(initialCapacity);
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
        moduleFactory.startFactory(null,null);
        context.setAttribute( filterName, moduleFactory);
        registInfactory(moduleFactory, "servletContext", context);
        registInfactory(moduleFactory, "servletRequest", requestMap);
        registInfactory(moduleFactory, "servletResponse", responseMap);
        registInfactory(moduleFactory, "sessionDatas", sessionDatas);
        moduleFactory.putLog(filterName+" is statup,configPath:"+configDir,LogLevel.INFO,"filterInit");
        moduleFactory.boot();
        appCenter = (TLBaseModule) moduleFactory.getModule("appCenter");
        ((TLWAPPCenter)appCenter).setFilter(this);
        ((TLWAPPCenter)appCenter).setFilterName(filterName);
    }

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
        sessionDatas.put(threadName,datas);
        String uri= request.getRequestURI();
        if (uri == null || uri.isEmpty())
            return ;
        appCenter.getMsg(moduleFactory, new TLMsg().setAction("start").setParam("uri",uri));
        requestMap.remove(threadName);
        responseMap.remove(threadName);
        sessionDatas.remove(threadName);
        Long nowTime = System.currentTimeMillis();
        Long runtime = nowTime - startTime;
        moduleFactory.putLog(filterName+ " 运行时间：" + runtime,LogLevel.DEBUG,"doFilter");
    }
    public void destroy() {
        if(isStartup ==false){
            return;
        }
        isStartup=false ;
        int sessionNumb =responseMap.size();
        while (sessionNumb !=0)
        {
            try {
                moduleFactory.putLog(filterName+ " destroying,session number:" + sessionNumb,LogLevel.INFO,"doFilter");
                sleep(1000);
                sessionNumb =responseMap.size();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
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
