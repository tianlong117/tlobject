package cn.tianlong.java.servletdemo;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.servletutils.TLWServModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

public class loginModule extends TLWServModule {
    public loginModule(){
        super();
    }
    public loginModule(String name ){
        super(name);
    }
    public loginModule(String name , TLObjectFactory modulefactory){
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
            case "regist":
                loginDeny(fromWho,msg);
                break;
            default:
        }
        return returnMsg ;
    }

    private void loginDeny(Object fromWho, TLMsg msg) {
        outData odata =  creatOutDataMsg();
        String content ="type"+getUserData("status");
        odata.addData("你不是注册用户:"+content);
        putOutData(odata);
    }



}
