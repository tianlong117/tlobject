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

public class TLServletFactory extends GenericServlet {

    private  String servletName;
    protected Map<String,HttpServletRequest> requestMap;
    protected Map<String,HttpServletResponse>responseMap ;
    protected Map<String,HashMap<String ,Object>>sessionDatas ;
    protected TLObjectFactory moduleFactory;
    protected TLBaseModule appCenter;
    protected boolean isStartup =true ;
    protected int initialCapacity=256;
    public  void setIsStartup (boolean isStartup){
        this.isStartup =isStartup ;
    }
    public  int getNowRequestNumber(){
        return requestMap.size();
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
        moduleFactory = new TLObjectFactory(servletName,factoryConfigFile,configDir);
        moduleFactory.startFactory();
        context.setAttribute( servletName, moduleFactory);
        registInfactory(moduleFactory, "servletContext", context);
        registInfactory(moduleFactory, "servletRequest", requestMap);
        registInfactory(moduleFactory, "servletResponse", responseMap);
        registInfactory(moduleFactory, "sessionDatas", sessionDatas);
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
        appCenter = (TLBaseModule) moduleFactory.getModule("appCenter");
        ((TLWAPPCenter)appCenter).setFilter(this);
        ((TLWAPPCenter)appCenter).setFilterName(servletName);
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
        moduleFactory.putLog(servletName + " 运行时间：" + runtime,LogLevel.INFO,"doFilter");
    }
    protected void registInfactory(TLObjectFactory modulefactory, String name, Object object)
    {
        TLMsg registInFactoryMsg = new TLMsg().setAction(FACTORY_REGISTINFACTORY)
                .setParam(FACTORY_P_MODULENAME, name)
                .setParam(INSTANCE, object);
        modulefactory.putMsg(modulefactory, registInFactoryMsg);
    }
}
