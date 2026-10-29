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
            else {
                // 优先用容器映射出的真实路径；拿不到再退回历史相对路径（webapp 解压深度假设）
                configDir = context.getRealPath("/WEB-INF/" + initConfigPath);
                if (configDir == null || configDir.isEmpty()) {
                    configDir = "../../../../../" + initConfigPath;
                    moduleFactory.putLog(servletName + " getRealPath 失败，回退相对路径: " + configDir,
                            LogLevel.WARN, "servletInit");
                }
            }
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
        if(moduleFactory ==null || requestMap ==null || responseMap ==null || threadDatas ==null)
            return;                      // init 未走完/所需模块缺失：别在 put 时 NPE
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
            if (appCenter == null) {
                try {
                    servletResponse.setContentType("text/html;charset=utf-8");
                    servletResponse.getWriter().println("<h1>服务未就绪</h1>");
                } catch (java.io.IOException ignored) {}
                return;
            }
            appCenter.getMsg(moduleFactory, new TLMsg().setAction("start").setParam("uri", uri));
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
        // 每请求一条：INFO 在高并发下会刷屏，排障时调 DEBUG 即可
        moduleFactory.putLog(servletName + " 运行时间：" + runtime,LogLevel.DEBUG,"doFilter");
    }
}
