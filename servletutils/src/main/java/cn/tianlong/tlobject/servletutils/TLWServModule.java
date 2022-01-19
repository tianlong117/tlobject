package cn.tianlong.tlobject.servletutils;

import cn.tianlong.tlobject.base.*;
import org.apache.commons.lang3.StringUtils;

import javax.servlet.ServletContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.URLEncoder;
import java.util.*;

import static cn.tianlong.tlobject.servletutils.TLParamString.*;


/**
 * 创建日期：2018/3/6 on 8:50
 * 描述:
 * 作者:tianlong
 */

/**
 * webcontrol控制基本模块
 */
public abstract class TLWServModule extends TLBaseModule {

    public TLWServModule(){
        super();
    }
    public TLWServModule(String name ){
        super(name);
    }
    public TLWServModule(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    protected HashMap<String ,String > getAppParams(){
        TLMsg moduleMsg =createMsg().setAction(MODULE_GETPARAM);
        TLMsg paramsMsg= putMsg(M_APPCENTER, moduleMsg);
        HashMap<String,String> appParams=paramsMsg.getArgs();
        return  appParams;
    }

    protected String getUserObjName()
    {
        String userObj = (String) getSessionData("userObj");
        if(userObj ==null)
        {
            TLMsg returnMsg =putMsg(M_APPCENTER,createMsg().setAction("getDefaultUser"));
            userObj= (String) returnMsg.getParam("userObj");
        }
        return userObj;
    }
    protected String getUserid()
    {
        TLMsg returnMsg =putMsg(getUserObjName(),createMsg().setAction(USER_GETLOGINSTATE));
        if(returnMsg ==null)
            return null ;
        return (String) returnMsg.getParam(USER_P_USERID);
    }
    protected String getUserName()
    {
        TLMsg returnMsg =putMsg(getUserObjName(),createMsg().setAction(USER_GETLOGINSTATE));
        if(returnMsg ==null)
            return null ;
        return (String) returnMsg.getParam(USER_P_USERNAME);
    }
    protected String getToken()
    {
        TLMsg returnMsg =putMsg(getUserObjName(),createMsg().setAction(USER_GETLOGINSTATE));
        if(returnMsg ==null)
            return null ;
        return (String) returnMsg.getParam(TOKENUSER_P_TOKEN);
    }
    protected ArrayList<String> getUserRole()
    {
        TLMsg returnMsg =putMsg(getUserObjName(),createMsg().setAction(USER_GETLOGINSTATE));
        if(returnMsg ==null)
            return null ;
        return (ArrayList) returnMsg.getParam(USER_P_ROLE);
    }
    protected boolean setUserSessionData(String varName,Object varValue)
    {
        TLMsg returnMsg =putMsg(getUserObjName(),
                createMsg().setAction(USER_SAVEDATA).setParam( varName,varValue));
        if(returnMsg ==null)
            return false ;
        return (Boolean) returnMsg.getParam(RESULT);
    }
    protected Boolean removeUserSessionData(String varName)
    {
        TLMsg returnMsg =putMsg(getUserObjName(),
                createMsg().setAction(USER_REMOVEDATA).setParam( USER_P_DATANAMES,varName));
        if(returnMsg ==null)
            return false ;
        return (Boolean) returnMsg.getParam(RESULT);
    }
    protected Object getUserSessionData(String varName)
    {
        TLMsg returnMsg =putMsg(getUserObjName(),createMsg().setAction(USER_GETDATA).setParam( USER_P_DATANAMES,varName));
        if(returnMsg ==null)
            return null ;
        return  returnMsg.getParam(varName);
    }
    protected Map<String,Object> getUserSessionData()
    {
        TLMsg returnMsg =putMsg(getUserObjName(),createMsg().setAction(USER_GETALLDATA));
        if(returnMsg ==null)
            return null ;
        return returnMsg.getArgs();
    }
    protected boolean isRole(String role)
    {
        ArrayList rolesList =getUserRole();
        if(rolesList==null)
        {
            if( role.equals(USER_V_ROLE_GUEST))
                return true ;
            else
                return false ;
        }
       return rolesList.contains(role) ;
    }
    protected  void setInInterface(String interfaceName){
        TLMsg msg =createMsg().setAction(WEBCLIENT_SETININTERFACE).setParam("interface",interfaceName);
        String client= getClient();
        putMsg(client,msg);

    }
    protected  void setOutInterface(String outInterfaceName){
        TLMsg msg =createMsg().setAction(WEBCLIENT_SETOUTINTERFACE).setParam("outInterface",outInterfaceName);
        String client= getClient();
        putMsg(client,msg);

    }
    protected String getUserData(String varname){
        String[] var = new String[1];
        var[0] =varname ;
        TLMsg returnMsg =   putMsgToClient(createMsg().setAction(WEBCLIENT_GETINDATA).setParam(CLIENT_P_VARNAME,var));
        if(returnMsg ==null)
            return null ;
        return (String) returnMsg.getParam(varname);
    }
    protected TLMsg getUserData(String[] varname){
        return  putMsgToClient(createMsg().setAction(WEBCLIENT_GETINDATA).setParam(CLIENT_P_VARNAME,varname));
    }
    protected TLMsg getUserJson(){
        return   putMsgToClient(createMsg().setAction(WEBCLIENT_GETINJSON));
    }
    protected TLMsg getUserData(){
        return   putMsgToClient(createMsg().setAction(WEBCLIENT_GETINDATA));
    }
    protected TLMsg getUserContent(){
        return   putMsgToClient( createMsg().setAction(WEBCLIENT_GETINCONTENT));
    }
    protected LinkedHashMap creatOutData(){
        return  new LinkedHashMap<>();
    }

    protected TLMsg creatOutMsg(){
        return  createMsg().setAction("putOutData");
    }
    protected outData creatOutDataMsg(){
        return  new outData();
    }
    protected outData creatOutDataMsg(String dataId){
        return  new outData(dataId);
    }
    protected TLMsg putContent(String content){
        TLMsg moduleMsg =createMsg().setAction("putContent").setParam("content",content);
        return   putMsgToClient(moduleMsg);
    }
    protected TLMsg putVar(String message,String dataid ){
        outData odata =  creatOutDataMsg(dataid);
        odata.addData("message",message);
        return  putOutData(odata);
    }
    protected TLMsg putOutData(outData outData){
        TLMsg moduleMsg =createMsg().setAction("putOutData").setParam("outData",outData);
        return   putMsgToClient(moduleMsg);
    }
    private TLMsg putMsgToClient(TLMsg msg){
        String client= getClient();
        return   putMsg(client, msg);
    }
    protected String getClient()
    {
        String client = (String) getSessionData("client");
        if(client ==null)
        {
            String userObj =  getUserObjName();
            TLMsg returnMsg =putMsg(userObj,createMsg().setAction(USER_GETCLIENT));
            client= (String) returnMsg.getParam(USER_R_CLIENT);
        }
        return client;
    }
   protected  boolean putFile (String filePath,String fileName){
       File file = new File(filePath);//获取缓存文件
       if(!file.exists())
           return false ;
       InputStream inputStream = null;
       try {
           inputStream = new FileInputStream(file);
       } catch (FileNotFoundException e) {
         return  false ;
       }
       return putStream(fileName,inputStream) ;
   }
    protected boolean putStream(String fileName, InputStream inputStream){
        boolean   ifDo =true ;
        String filetype =StringUtils.substringAfterLast(fileName,".");
        HttpServletResponse response =getResponse();
        try {
            int length = inputStream.available();
            response.setContentLength( length);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if(filetype.equals("jpg")||filetype.equals("gif")||filetype.equals("jpeg")||filetype.equals("tif")
                ||filetype.equals("png")||filetype.equals("bmp")){
            response.setContentType("image/".concat(filetype).concat(";charset=UTF-8"));
            response.setHeader("Content-Disposition", "inline; filename=\"" + fileName + "\"");
        }
        else if(filetype.equals("js"))
        {
            response.setContentType("application/javascript");
            response.setHeader("Content-Disposition", "inline; filename=\"" + fileName + "\"");
        }
        else if( filetype.equals("css"))
        {
            response.setContentType("text/css");
            response.setHeader("Content-Disposition", "inline; filename=\"" + fileName + "\"");
        }
        else
        {
            response.setContentType("application/octet-stream");
            try {
                response.setHeader("Content-Disposition", "attachment;fileName="+ URLEncoder.encode(fileName, "UTF-8"));
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
        ServletOutputStream out= null;
        try {
            out = response.getOutputStream();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        byte[] buf = new byte[4096];
        int size;
        try {
            while (-1 != (size = inputStream.read(buf))) {
                out.write(buf, 0, size);
                out.flush();
            }
        }catch (Exception e){
            ifDo =false ;
        }
        try {
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            inputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return ifDo ;
    }

    protected void setSessionData(String varname ,Object value)
    {
        String threadName=Thread.currentThread().getName();
        Map<String,HashMap<String ,Object>> sessionDatas = (Map<String, HashMap<String, Object>>) getModule("sessionDatas");
        HashMap<String ,Object> datas =sessionDatas.get(threadName);
        datas.put(varname,value);
    }
    protected void setSessionData(HashMap<String ,Object>inputdatas)
    {
        String threadName=Thread.currentThread().getName();
        Map<String,HashMap<String ,Object>> sessionDatas = (Map<String, HashMap<String, Object>>) getModule("sessionDatas");
        HashMap<String ,Object> datas =sessionDatas.get(threadName);
        datas.putAll(inputdatas);
    }
    protected Object getSessionData(String varname)
    {
        String threadName=Thread.currentThread().getName();
        Map<String,HashMap<String ,Object>> sessionDatas = (Map<String, HashMap<String, Object>>) getModule("sessionDatas");
        HashMap<String ,Object> datas =sessionDatas.get(threadName);
        return datas.get(varname);
    }
    protected HashMap<String ,Object> getSessionData()
    {
        String threadName=Thread.currentThread().getName();
        Map<String,HashMap<String ,Object>> sessionDatas = (Map<String, HashMap<String, Object>>) getModule("sessionDatas");
        return  sessionDatas.get(threadName);
    }
    protected  HttpServletResponse getResponse(){
        String threadName=Thread.currentThread().getName();
        Map<String,HttpServletResponse>responseMap = (Map<String, HttpServletResponse>) getModuleInFactory("servletResponse");
        return responseMap.get(threadName);
    }
    protected  HttpServletRequest getRequest(){
        String threadName=Thread.currentThread().getName();
        Map<String,HttpServletRequest> requestMap = (Map<String, HttpServletRequest>) getModuleInFactory("servletRequest");
        if (requestMap ==null)
            return null ;
        return requestMap.get(threadName);
    }
    protected  ServletContext getContext(){
        HttpServletRequest request = getRequest();
        if(request ==null)
            return null ;
        ServletContext servletContext ;
        if(request !=null)
           servletContext = request.getServletContext();
        else
           servletContext = (ServletContext)getModuleInFactory("servletContext");
        return servletContext ;
    }
    protected   TLObjectFactory getAppFactory(String filterName){
        ServletContext servletContext =getContext();
         return (TLObjectFactory) servletContext.getAttribute(filterName);
    }
    protected   TLBaseModule getAppModule(String module,String filterName){
         TLObjectFactory factory = getAppFactory(filterName);
         if(factory ==null)
             return null ;
          return  (TLBaseModule)factory.getModule(module);
    }
    protected   TLMsg putAppMsg(String module,TLMsg msg ,String appConfPath){
        TLBaseModule moduleObj =getAppModule(module,appConfPath);
        if(moduleObj ==null)
            return  null ;
        return  putMsg(moduleObj,msg) ;
    }
    protected void putError(String content){
        putMsg("error",creatOutMsg().setAction("setError").setParam("content",content));
    }
    protected void sendRedirect(String url){
        HttpServletResponse response =getResponse();
        try {
            response.sendRedirect(url);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    protected void sendUrlMap(String url){
        putMsg("urlMap",createMsg().setAction("doWithUrl").setParam("url",url));
    }
    protected class outData extends TLMsg{
        protected LinkedHashMap<String, Object> outDataMap=new LinkedHashMap<>();
        private int i=1;
        private String dataId;
        public outData(){
            super();
            setParam("outData",outDataMap);

        }
        public outData(String dataId){
            super();
            if(dataId==null)
                dataId="mydata";
            setParam("outData",outDataMap);
            this.dataId=name+"."+dataId;
        }
        public void setDataId(String dataId){
           this.dataId = dataId ;
        }
        public String getDataId(){
            return dataId;
        }
        public outData addData(String varname , Object value )
        {
            outDataMap.put(varname,value);
            return this;
        }
        public outData addData(Object value )
        {
            String varname =name+i;
            outDataMap.put(varname,value);
            i++;
            return this;
        }

    }
}

