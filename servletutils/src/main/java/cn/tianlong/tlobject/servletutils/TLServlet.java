package cn.tianlong.tlobject.servletutils;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;

import static cn.tianlong.tlobject.servletutils.TLParamString.M_APPCENTER;

public class TLServlet extends GenericServlet {

    private  String servletName;
    protected Map<String,HttpServletRequest> requestMap;
    protected Map<String,HttpServletResponse>responseMap ;
    protected Map<String,HashMap<String ,Object>>threadDatas ;
    protected TLObjectFactory moduleFactory;
    protected TLBaseModule appCenter;

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        servletName =config.getServletName();
        ServletContext context = config.getServletContext();
        String usefactory=config.getInitParameter("usefactory");
        if( usefactory ==null || usefactory.isEmpty())
        {
            usefactory =context.getInitParameter("usefactory");
            if( usefactory ==null || usefactory.isEmpty())
                return;
        }
        moduleFactory= (TLObjectFactory) context.getAttribute(usefactory);
        if(moduleFactory ==null)
            return;
        String  initConfigFile=config.getInitParameter("configFileOfFactory");
        if(initConfigFile!=null && !initConfigFile.isEmpty()){
            String  initConfigPath=config.getInitParameter("configPath");
            String configDir ;
            if(initConfigPath==null || initConfigPath.isEmpty())
                configDir=moduleFactory.getConfigDir();
            else
                configDir ="../../../../../"+initConfigPath;
           moduleFactory.addConfig(initConfigFile,configDir);
            moduleFactory.putLog(servletName +" is statup,configPath:"+configDir,LogLevel.INFO,"filterInit");
        }
        appCenter = (TLBaseModule) moduleFactory.getModule(M_APPCENTER);
        requestMap =   (Map<String, HttpServletRequest>) moduleFactory.getModule("servletRequest");
        responseMap =  (Map<String, HttpServletResponse>) moduleFactory.getModule("servletResponse");
        threadDatas = (Map<String, HashMap<String, Object>>) moduleFactory.getModule("threadDatas");


    }
    @Override
    public void service(ServletRequest servletRequest, ServletResponse servletResponse)  {
        if(moduleFactory ==null)
            return;
        long startTime = System.currentTimeMillis();
        String threadName=Thread.currentThread().getName();
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        HashMap<String ,Object> datas =new HashMap<>();
        requestMap.put(threadName, request);
        responseMap.put(threadName,  response);
        threadDatas.put(threadName,datas);
        try {
            String uri = request.getRequestURI();
            if (uri == null || uri.isEmpty())
                return;
            appCenter.getMsg(moduleFactory, new TLMsg().setAction("start").setParam("uri", uri));
        } finally {
            requestMap.remove(threadName);
            responseMap.remove(threadName);
            threadDatas.remove(threadName);
        }
        Long nowTime = System.currentTimeMillis();
        Long runtime = nowTime - startTime;
        moduleFactory.putLog(servletName + " 运行时间：" + runtime,LogLevel.INFO,"doFilter");
    }
}
