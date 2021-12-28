package cn.tianlong.tlobject.servletutils;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.modules.TLBaseSessionData;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import javax.servlet.ServletContext;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLWModuleInContext extends TLBaseSessionData {
    protected ServletContext servletContext;
    public TLWModuleInContext(){
        super();
    }
    public TLWModuleInContext(String name ){
        super(name);
    }
    public TLWModuleInContext(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }


    @Override
    protected TLBaseModule init() {
        servletContext= (ServletContext) getModuleFromFactory("servletContext");
        return this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        return null;
    }

    @Override
    protected TLMsg deletSessionData(Object fromWho, TLMsg msg) {
        String key = (String) msg.getParam("key");
        if(key !=null)
            servletContext.removeAttribute(key);
        return null;
    }

    @Override
    protected TLMsg putSessionData(Object fromWho, TLMsg msg) {
        String key = (String) msg.getParam("key");
        Object value =msg.getParam("value") ;
        if(key !=null && value!=null)
             servletContext.setAttribute(key,value);
        return null;
    }

    @Override
    protected TLMsg getSessionData(Object fromWho, TLMsg msg) {
        String key = (String) msg.getParam("key");
        if(key !=null)
        {
            Object value =servletContext.getAttribute(key);
            return createMsg().setParam("value",value);
        }
        return null;
    }




}
