package cn.tianlong.java.servletdemo;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.servletutils.TLWServModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

/**
 * 创建日期：2018/10/2811:23
 * 描述:
 * 作者:tianlong
 */
public class cacheKeyModule extends TLWServModule {
    public cacheKeyModule(){
        super();
    }
    public cacheKeyModule(String name ){
        super(name);
    }
    public cacheKeyModule(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected TLBaseModule init() {
         return this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {

            case "dbmodleGetCacheKey":
                returnMsg= dbmodleGetCacheKey(fromWho,msg);
                break;
            default:
                putMsg("error",creatOutMsg().setAction("setError").setParam("content","no action"));
        }
        return returnMsg ;
    }
    private TLMsg dbmodleGetCacheKey(Object fromWho, TLMsg msg) {
        return createMsg().setParam("cacheKey",getUserData("name"));
    }
}
