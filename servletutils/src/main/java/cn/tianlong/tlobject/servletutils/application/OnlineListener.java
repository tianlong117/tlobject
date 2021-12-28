package cn.tianlong.tlobject.servletutils.application;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.http.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


import static cn.tianlong.tlobject.servletutils.TLParamString.USER_ONOFFLINE;
import static cn.tianlong.tlobject.servletutils.TLParamString.USER_P_USERID;
import static cn.tianlong.tlobject.servletutils.TLParamString.USER_P_USEROBJ;

/**
 * 创建日期：2021/2/513:25
 * 描述:
 * 作者:tianlong
 */
public class OnlineListener implements ServletContextListener, HttpSessionAttributeListener, HttpSessionListener {
    private ServletContext application = null;
    //往会话中添加属性时回调的方法
    public void attributeAdded(HttpSessionBindingEvent httpSessionBindingEvent) {
        //取得用户名列表
        if ("userid".equals(httpSessionBindingEvent.getName())) {
            //将当前用户名添加到列表中
            Map<String, HashMap<String,Object>>  userTable = (Map<String, HashMap<String, Object>>) this.application.getAttribute("userTable");
            HttpSession httpSession =httpSessionBindingEvent.getSession();
            String sid = httpSession.getId();
            HashMap<String,Object> userInfo =userTable.get(sid);
           if(userInfo ==null)
             {
                 userInfo = new HashMap<>();
                 userTable.put(sid,userInfo) ;
             }
            userInfo.put(USER_P_USERID,httpSessionBindingEvent.getValue());
            userInfo.put("logintime",System.currentTimeMillis());
            userInfo.put("session",httpSession) ;
        }
    }
    //应用上下文初始化会回调的方法
    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent) {
        //初始化一个application对象
        this.application = servletContextEvent.getServletContext();
        //设置一个列表属性，用于保存在线用户名
        Map<String, HashMap<String,Object>> online = new ConcurrentHashMap<>();
        this.application.setAttribute("userTable",online);
    }
    //会话销毁时会回调的方法
    @Override
    public void sessionDestroyed(HttpSessionEvent httpSessionEvent) {
        ServletContext servletContext = httpSessionEvent.getSession().getServletContext();
        //获取用户列表
        Map<String, HashMap<String,Object>>  userTable = ( Map<String, HashMap<String,Object>> )servletContext.getAttribute("userTable");
        //获取sessionId
        HttpSession httpSession =httpSessionEvent.getSession();
        TLBaseModule userObj = (TLBaseModule) httpSession.getAttribute(USER_P_USEROBJ);
        String userid= (String) httpSession.getAttribute(USER_P_USERID);
        if(userObj !=null  && userid !=null)
            userObj.getMsg(userObj,new TLMsg().setAction(USER_ONOFFLINE).setParam(USER_P_USERID,userid));
        String sid = httpSession.getId();
        userTable.remove(sid);
    }
    //以下方法用空实现
    @Override
    public void attributeRemoved(HttpSessionBindingEvent httpSessionBindingEvent) {
    }
    @Override
    public void attributeReplaced(HttpSessionBindingEvent httpSessionBindingEvent) {
    }
    @Override
    public void sessionCreated(HttpSessionEvent httpSessionEvent) {
    }

    @Override
    public void contextDestroyed(ServletContextEvent servletContextEvent) {
    }
}
