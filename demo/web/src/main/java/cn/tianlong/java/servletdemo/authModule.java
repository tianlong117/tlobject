package cn.tianlong.java.servletdemo;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.servletutils.TLWServModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

public class authModule extends TLWServModule {
    public authModule(){
        super();
    }
    public authModule(String name ){
        super(name);
    }
    public authModule(String name , TLObjectFactory modulefactory){
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
            case "anonymousDeny":
                anonymousDeny(fromWho,msg);
                break;
            case "loginDeny":
                loginDeny(fromWho,msg);
                break;
            default:
                putMsg("error",creatOutMsg().setAction("setError").setParam("content","no action"));
        }
        return returnMsg ;
    }

    private void loginDeny(Object fromWho, TLMsg msg) {
       sendRedirect("/tlobject/login?status="+msg.getParam("value"));
    }

    private void anonymousDeny(Object fromWho, TLMsg msg) {
        outData odata =  creatOutDataMsg();
        odata.addData("你时匿名用户，不能访问");
        putOutData(odata);

    }


}
