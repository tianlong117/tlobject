package cn.tianlong.tlobject.servletutils.application;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static cn.tianlong.tlobject.servletutils.TLParamString.*;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public  class TLWUserInSession extends TLWAbstractUser {
    protected  String useridPrefix;
    protected  int useridPrefixLength;
    public TLWUserInSession(){
        super();
    }
    public TLWUserInSession(String name ){
        super(name);
    }
    public TLWUserInSession(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        if(params!=null  ){
            if( params.get("useridPrefix")!=null)
                useridPrefix=params.get("useridPrefix");
        }
        if(useridPrefix==null)
            useridPrefix=applicationId+"."+name+".";
        useridPrefixLength=useridPrefix.length();
    }
    @Override
    protected TLBaseModule init() {
       return this ;
    }
    @Override
    protected TLMsg removeData(Object fromWho, TLMsg msg) {
        HttpSession userSessionData =getSesstion();
        if(userSessionData ==null)
            return createMsg().setParam(RESULT,false) ;
        String dataName = (String) msg.getParam( USER_P_DATANAMES);
        HashMap <String ,Object> sessionDatas = (HashMap<String, Object>)userSessionData.getAttribute(USER_P_USERDATAS);
        if(sessionDatas.containsKey(dataName))
            sessionDatas.remove(dataName) ;
        return createMsg().setParam(RESULT,true) ;
    }
    @Override
    protected TLMsg getData(Object fromWho, TLMsg msg) {
        HttpSession userSessionData =getSesstion();
        if(userSessionData ==null)
            return createMsg().setParam(RESULT,false) ;
        HashMap <String ,Object> sessionDatas = (HashMap<String, Object>)userSessionData.getAttribute(USER_P_USERDATAS);
        String dataName = (String) msg.getParam( USER_P_DATANAMES);
        if(dataName !=null)
            return createMsg().setParam(dataName,sessionDatas.get(dataName));
        HashMap <String ,Object> datas =msg.getArgs();
        TLMsg returnMsg =createMsg();
        for(String key:datas.keySet())
        {
            returnMsg.setParam(key,sessionDatas.get(key));
        }
        return  returnMsg;
    }
    @Override
    protected TLMsg getAllData(Object fromWho, TLMsg msg) {
        HttpSession userSessionData =getSesstion();
        if(userSessionData ==null)
            return createMsg().setParam(RESULT,false) ;
        HashMap <String ,Object> sessionDatas = (HashMap<String, Object>)userSessionData.getAttribute(USER_P_USERDATAS);
        TLMsg returnMsg =createMsg().addArgs(sessionDatas);
        return  returnMsg;
    }
    @Override
    protected TLMsg saveData(Object fromWho, TLMsg msg) {
        HttpSession userSessionData =getSesstion();
        if(userSessionData ==null)
            return createMsg().setParam(RESULT,false) ;
        HashMap <String ,Object> datas =msg.getArgs();
        HashMap <String ,Object> sessionDatas = (HashMap<String, Object>)userSessionData.getAttribute(USER_P_USERDATAS);
        if(sessionDatas ==null)
        {
            sessionDatas =new HashMap<>();
            userSessionData.setAttribute(USER_P_USERDATAS,sessionDatas);
        }
        sessionDatas.putAll(datas);
        return createMsg().setParam(RESULT,true) ;
    }

    @Override
    protected void onOffline(Object fromWho, TLMsg msg) {
    }
    @Override
    protected void setOffline(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam(USER_P_USERID);
        HashMap<String,Object> userinfo =getOnlineUserInfo(userid);
        if(userinfo ==null)
            return;
        HttpSession session = (HttpSession) userinfo.get("session");
        if(session ==null)
            return;
        session.invalidate();
    }

    @Override
    protected TLMsg getOnline(Object fromWho, TLMsg msg) {
        HashMap<String,HashMap<String,Object>> myOnline = getOnline() ;
        if(myOnline ==null)
            return createMsg();
        return createMsg().setArgs(myOnline);
    }
    protected HashMap<String,Object> getOnlineUserInfo(String userid){
        ServletContext servletContext = (ServletContext)getModuleInFactory("servletContext");
        Map<String, HashMap<String,Object>>  userTable = (Map<String, HashMap<String, Object>>)servletContext.getAttribute("userTable");
        if(userTable ==null || userTable.isEmpty())
            return null;
        for(String sid : userTable.keySet()){
            HashMap<String,Object> userInfo = userTable.get(sid);
            if(userInfo ==null)
                continue;
            String suserid = (String) userInfo.get(USER_P_USERID);
            if(suserid==null)
                continue;
            if(useridFormSesstion(suserid).equals(userid))
               return userInfo;
        }
        return  null;

    }
    protected HashMap<String,HashMap<String,Object>> getOnline() {
        ServletContext servletContext = (ServletContext)getModuleInFactory("servletContext");
        Map<String, HashMap<String,Object>>  userTable = (Map<String, HashMap<String, Object>>)servletContext.getAttribute("userTable");
        if(userTable ==null || userTable.isEmpty())
            return null;
        HashMap<String,HashMap<String,Object>> myOnline = new HashMap<>() ;
        for(String sid : userTable.keySet()){
            HashMap<String,Object> userInfo = userTable.get(sid);
            if(userInfo ==null)
                continue;
            String userid = (String) userInfo.get(USER_P_USERID);
            if(userid==null)
                continue;
            String p =userid.substring(0,useridPrefixLength);
            if(p.equals(useridPrefix))
                myOnline.put(useridFormSesstion(userid),userInfo);
        }
        return  myOnline;
    }
    @Override
    protected void logOut(Object fromWho, TLMsg msg) {
        HttpSession session =getSesstion();
        session.invalidate();
    }
    @Override
    protected void auToLogin(Object fromWho, TLMsg msg) {

    }

    @Override
    protected TLMsg getLoginState(Object fromWho, TLMsg msg)
    {
        HttpSession userSessionData =getSesstion();
        if(userSessionData ==null)
            return null ;
        String userid = (String) userSessionData.getAttribute(USER_P_USERID);
        if(userid ==null)
            return null ;
        String userName = (String) userSessionData.getAttribute(USER_P_USERNAME);
        ArrayList userRole= (ArrayList)userSessionData.getAttribute(USER_P_ROLE);
        TLMsg returnMsg = createMsg().setParam(USER_P_USERID, useridFormSesstion(userid))
                .setParam(USER_P_ROLE, userRole).setParam(USER_P_USERNAME,userName);
        String userGroup= (String) userSessionData.getAttribute(USER_P_GROUP);
        if( userGroup !=null)
            returnMsg.setParam(USER_P_GROUP,userGroup) ;
        return returnMsg ;
    }

    @Override
    protected TLMsg login(Object fromWho, TLMsg msg) {
        String userName = (String) msg.getParam(USER_P_USERNAME);
        String userid = (String) msg.getParam(USER_P_USERID);
        String userRole= (String) msg.getParam(USER_P_ROLE);
        HttpSession userSessionData =getSesstion();
        if(params.get(USER_P_TIMEOUT)!=null)
           userSessionData.setMaxInactiveInterval(Integer.valueOf(params.get(USER_P_TIMEOUT))*60);
        userSessionData.setAttribute(USER_P_USEROBJ,this);
        userSessionData.setAttribute(USER_P_USERID,SesstionUserid(userid));
        userSessionData.setAttribute(USER_P_USERNAME,userName);
        userSessionData.setAttribute(USER_P_ROLE,roleStrToList(userRole));
        String userGroup= (String) msg.getParam(USER_P_GROUP);
        if(userGroup !=null)
            userSessionData.setAttribute(USER_P_GROUP,userGroup);
        if (!msg.isNull(USER_P_USERDATAS))
        {
            HashMap <String ,Object> sessionDatas = (HashMap<String, Object>) msg.getParam(USER_P_USERDATAS);
            userSessionData.setAttribute(USER_P_USERDATAS,sessionDatas);
        }
        return createMsg().setParam(RESULT,TRUESTR) ;
    }
    protected HttpSession getSesstion () {
        String threadName=Thread.currentThread().getName();
        Map<String,HttpServletRequest> requestMap = (Map<String, HttpServletRequest>) getModuleInFactory("servletRequest");
        return requestMap.get(threadName).getSession();
    }
     protected  String  SesstionUserid(String userid){
        if(userid ==null)
            return userid ;
        return useridPrefix+userid;
     }
    protected  String   useridFormSesstion(String sesstionUserid){
        if(sesstionUserid ==null)
            return sesstionUserid ;
        return  sesstionUserid.substring(useridPrefixLength);
    }
}
