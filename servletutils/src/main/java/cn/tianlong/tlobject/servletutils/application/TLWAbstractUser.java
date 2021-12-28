package cn.tianlong.tlobject.servletutils.application;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.utils.TLDataUtils;

import java.util.ArrayList;

import static cn.tianlong.tlobject.servletutils.TLParamString.*;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public abstract class TLWAbstractUser extends TLBaseModule {
    protected  String client;
    public TLWAbstractUser(){
        super();
    }
    public TLWAbstractUser(String name ){
        super(name);
    }
    public TLWAbstractUser(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        if(params!=null  ){
            if( params.get(USER_R_CLIENT)!=null)
                client=params.get(USER_R_CLIENT);
        }
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) { 
            case USER_LOGIN:
                returnMsg= login(fromWho,msg);
                break;
            case USER_AUTOLOGIN:
                auToLogin( fromWho, msg);
                break;
            case USER_LOGOUT:
               logOut(fromWho,msg);
                break;
            case USER_GETLOGINSTATE:
                returnMsg= getLoginState(fromWho,msg);
                break;
            case USER_SAVEDATA:
                returnMsg= saveData(fromWho,msg);
                break;
            case USER_REMOVEDATA:
                removeData(fromWho,msg);
                break;
            case USER_GETDATA:
                returnMsg= getData(fromWho,msg);
                break;
            case USER_GETALLDATA:
                returnMsg= getAllData(fromWho,msg);
                break;
            case USER_GETCLIENT:
                returnMsg= getClient(fromWho,msg);
                break;
            case USER_GETONLINE:
                returnMsg= getOnline(fromWho,msg);
                break;
            case USER_SETOFFLINE:
                setOffline(fromWho,msg);
                break;
            case USER_ONOFFLINE:
                onOffline(fromWho,msg);
                break;
            default:
               ;
        }
        return returnMsg ;
    }

    protected void onOffline(Object fromWho, TLMsg msg) {
    }

    protected void setOffline(Object fromWho, TLMsg msg) {
    }

    protected TLMsg getOnline(Object fromWho, TLMsg msg) {
        return null ;
    }

    protected TLMsg removeData(Object fromWho, TLMsg msg) {
        return null ;
    }

    protected abstract void auToLogin(Object fromWho, TLMsg msg);

    private TLMsg getClient(Object fromWho, TLMsg msg) {
        return  createMsg().setParam(USER_R_CLIENT,client);    }


    protected abstract TLMsg getLoginState(Object fromWho, TLMsg msg);
    protected abstract TLMsg login(Object fromWho, TLMsg msg);
    protected abstract void logOut(Object fromWho, TLMsg msg);
    protected abstract TLMsg saveData(Object fromWho, TLMsg msg);
    protected abstract TLMsg getData(Object fromWho, TLMsg msg);
    protected abstract TLMsg getAllData(Object fromWho, TLMsg msg);
    protected ArrayList roleStrToList(String roles){
         return TLDataUtils.splitStrToList(roles,";");
    }

}
