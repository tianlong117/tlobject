package cn.tianlong.tlobject.servletutils;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;


/**
 * 创建日期：2018/3/6 on 8:50
 * 描述:
 * 作者:tianlong
 */

public  class TLWAPPCenter extends TLWServModule {
    protected String filterName ;
    private  Object filter ;
    protected  String prefixUrl ;
    protected  String tlobjectPath="/" ;
    public TLWAPPCenter(){
        super();
    }
    public TLWAPPCenter(String name ){
       super(name);
    }
    public TLWAPPCenter(String name ,TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    public void setFilter(Object filter){
        this.filter =filter ;
    }
    public void setFilterName(String filterName){this.filterName =filterName ;}
    @Override
    protected void setModuleParams()
    {
        if(params!=null  ){
            if( params.get("prefixUrl")!=null && !params.get("prefixUrl").isEmpty())
                prefixUrl=params.get("prefixUrl");
            if( params.get("tlobjectPath")!=null && !params.get("tlobjectPath").isEmpty())
                tlobjectPath=params.get("tlobjectPath");
        }
        // 在 setModuleParams() 中
        if (prefixUrl == null) {
            ServletContext context = getContext();
            String contextPath = context.getContextPath();
            prefixUrl = contextPath + (tlobjectPath != null ? tlobjectPath : "/");
        }
// 规范化：去掉末尾斜杠（但根路径 "/" 保留）
        if (prefixUrl != null && prefixUrl.length() > 1 && prefixUrl.endsWith("/")) {
            prefixUrl = prefixUrl.substring(0, prefixUrl.length() - 1);
        }
    }
    @Override
    protected TLBaseModule init() {
        return this ;
    }
    @Override
    public void runStartMsg(){
        super.runStartMsg();
        if( params.get("defaultClientUser")!=null)
        {
            TLMsg returnMsg =putMsg(params.get("defaultClientUser"),createMsg().setAction("getClient"));
            params.put("defaultClient", (String) returnMsg.getParam("client"));
        }
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case "getDefaultUser":
                returnMsg=getDefaultUser(fromWho, msg);
                break;
            case "getUserObj":
                String userObj =getUserObjName();
                returnMsg=createMsg().setParam("userObj",userObj);
                break;
            case "getDefaultClient":
                returnMsg = getDefaultClient(fromWho, msg);
                break;
            case "getClient":
                String client=getClient();
                returnMsg = createMsg().setParam("client",client);
                break;
            case "start":
                returnMsg = start(fromWho,msg);
                break;
            case "end":
                end(fromWho,msg);
                break;
            case "stop":
                returnMsg=stop(fromWho,msg);
                break;
            case "getRequestNumber":
                returnMsg=getRequestNumber(fromWho,msg);
                break;
            case "dispatchUrl":
                returnMsg=dispatchUrl(fromWho,msg);
                break;
            case "setThreadData":
               setThreadData(fromWho,msg);
                break;
            case "getThreadData":
                returnMsg=getThreadData(fromWho,msg);
                break;
            default:
                putMsg("error",creatOutMsg().setAction("setError").setParam("content","no action"));
        }
        return returnMsg ;
    }

    protected void end(Object fromWho, TLMsg msg) {
        Long startTime = (Long) getThreadData("startTime");
        Long nowTime = System.currentTimeMillis();
        Long runtime = nowTime - startTime;
        putLog(filterName+ " 运行时间：" + runtime,LogLevel.INFO,"end");
    }

    private TLMsg getRequestNumber(Object fromWho, TLMsg msg) {
        int requestNumber =0;
        Map<String,HttpServletRequest> requestMap = (Map<String, HttpServletRequest>) getModuleInFactory("servletRequest");
        if(requestMap !=null)
            requestNumber =requestMap.size();
        return createMsg().setParam(RESULT,requestNumber);
    }

    private TLMsg stop(Object fromWho, TLMsg msg) {
        if(filter instanceof  TLFilterWithSingleFactory)
            ((TLFilterWithSingleFactory)filter).setIsStartup(false);
        else  if(filter instanceof  TLServletFactory)
            ((TLServletFactory)filter).setIsStartup(false);
        return createMsg().setParam(RESULT,true);
    }

    private void setThreadData(Object fromWho, TLMsg msg) {
        String varname = (String) msg.getParam("varname");
        Object  value =msg.getParam("value");
        setThreadData(varname,value);
    }
    private TLMsg getThreadData(Object fromWho, TLMsg msg) {
        String varname = (String) msg.getParam("varname");
        Object  value =getThreadData(varname);
        return createMsg().setParam("value",value);
    }

    protected String getUserObjName() {
        String userObj = (String) getThreadData("userObj");
        if(userObj ==null)
            userObj= params.get("defaultClientUser");
        return userObj ;
    }
    protected String getClient()
    {
        String userObj =  getUserObjName();
        TLMsg returnMsg =putMsg(userObj,createMsg().setAction("getClient"));
        String client= (String) returnMsg.getParam("client");
        return client;
    }
    private TLMsg getDefaultClient(Object fromWho, TLMsg msg) {
        return createMsg().setParam("client",params.get("defaultClient"));
    }

    private TLMsg getDefaultUser(Object fromWho, TLMsg msg) {
       return createMsg().setParam("userObj",params.get("defaultClientUser"));
    }


    private TLMsg dispatchUrl(Object fromWho, TLMsg msg) {
        putMsg("urlMap",createMsg().setAction("doWithUrl").setParam("url",msg.getParam("url")));
        return msg ;
    }


    protected TLMsg start(Object fromWho, TLMsg msg) {
        String url = (String) msg.getParam("url");
        if (url == null) {
            String uri = (String) msg.getParam("uri");
            if (uri == null) {
                return null;
            }
            // 安全截取：如果 prefixUrl 为空或长度超过 uri，则使用完整 uri
            if (prefixUrl == null || prefixUrl.isEmpty() || uri.length() <= prefixUrl.length()) {
                url = uri;
            } else {
                url = uri.substring(prefixUrl.length());
            }
            // 如果截取后为空，则设为根路径（"/"），保证后续路由能找到默认映射
            if (url == null || url.isEmpty()) {
                url = "/";
            }
            msg.removeParam("uri");
        }
        return putMsg("urlMap", msg.setAction("doWithUrl").setParam("url", url));
    }
}
