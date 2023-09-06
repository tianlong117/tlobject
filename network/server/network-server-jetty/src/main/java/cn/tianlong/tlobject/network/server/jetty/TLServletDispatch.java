package cn.tianlong.tlobject.network.server.jetty;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.servletutils.TLWAPPCenter;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static cn.tianlong.tlobject.base.TLParamString.*;

public class TLServletDispatch extends GenericServlet {
    protected TLBaseModule appCenter;
    protected String prefixUrl ;
    protected  String  name ;
    protected Map<String,HttpServletRequest> requestMap;
    protected Map<String,HttpServletResponse>responseMap ;
    protected Map<String,HashMap<String ,Object>> threadDatas;
    protected TLObjectFactory moduleFactory;
    protected boolean isStartup =true ;
    protected int initialCapacity=256;

    public TLServletDispatch(String name ,TLObjectFactory factory)
    {
        this.name =name ;
        this.moduleFactory =factory ;
    }
    protected void setInitialCapacity(int initialCapacity)
    {
        this.initialCapacity =initialCapacity ;
    }
    protected void setPrefixUrl(String prefixUrl){
        this.prefixUrl=prefixUrl;
    }
    public  void setIsStartup (boolean isStartup){
        this.isStartup =isStartup ;
    }
    public  int getNowRequestNumber(){
        return requestMap.size();
    }
    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        requestMap =new ConcurrentHashMap<>( initialCapacity);
        responseMap =new ConcurrentHashMap<>(initialCapacity);
        threadDatas =new ConcurrentHashMap<>(initialCapacity);
        ServletContext context =getServletContext();
        registInfactory( moduleFactory,"servletContext",context);
        registInfactory(moduleFactory, "servletRequest", requestMap);
        registInfactory(moduleFactory, "servletResponse", responseMap);
        registInfactory(moduleFactory, "threadDatas", threadDatas);
        moduleFactory.putLog(name+" is statup,configPath:",LogLevel.INFO,"filterInit");
        appCenter = (TLBaseModule) moduleFactory.getModule("appCenter");
        ((TLWAPPCenter)appCenter).setFilter(this);
        ((TLWAPPCenter)appCenter).setFilterName(name);
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
        threadDatas.put(threadName,datas);
        String uri= request.getRequestURI();
        if (uri == null || uri.isEmpty())
            return ;
        if(prefixUrl !=null)
        {
            if(uri.length() < prefixUrl.length())
                return;
            uri=uri.substring(prefixUrl.length());
        }
        TLMsg msg = new TLMsg().setAction("start").setParam("uri",uri);
        appCenter.getMsg(moduleFactory, msg);
        requestMap.remove(threadName);
        responseMap.remove(threadName);
        threadDatas.remove(threadName);
        Long nowTime = System.currentTimeMillis();
        Long runtime = nowTime - startTime;
        moduleFactory.putLog(name+ " 运行时间：" + runtime,LogLevel.INFO,"doFilter");
    }
    protected void registInfactory(TLObjectFactory modulefactory, String name, Object object)
    {
        TLMsg registInFactoryMsg = new TLMsg().setAction(FACTORY_REGISTINFACTORY)
                .setParam(FACTORY_P_MODULENAME, name)
                .setParam(INSTANCE, object);
        modulefactory.putMsg(modulefactory, registInFactoryMsg);
    }
}
