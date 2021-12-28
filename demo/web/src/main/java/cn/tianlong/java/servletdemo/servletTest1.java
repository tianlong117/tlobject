package cn.tianlong.java.servletdemo;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.servletutils.TLWServModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

public class servletTest1 extends TLWServModule {
    public servletTest1(){
        super();
    }
    public servletTest1(String name ){
        super(name);
    }
    public servletTest1(String name , TLObjectFactory modulefactory){
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
            case "index":
                index(fromWho,msg);
                break;
            case "user":
                setuser(fromWho,msg);
                break;
            case "test":
                test1(fromWho,msg);
                break;
            case "login":
                login(fromWho,msg);
            case "client":
                client(fromWho,msg);
                break;
            default:
                putMsg("error",creatOutMsg().setAction("setError").setParam("content","no action"));
        }
        return returnMsg ;
    }

    private void client(Object fromWho, TLMsg msg) {
        TLMsg cmsg=createMsg();
        cmsg.setParam("service","this client ");
        putUserMsg(cmsg);
    }

    private void login(Object fromWho, TLMsg msg) {
        String username= (String) msg.getParam("username");
        outData odata =  creatOutDataMsg();
        odata.addData("你好"+username);
        putOutData(odata);
    }


    private void test1(Object fromWho, TLMsg msg) {
        System.out.println("tservletTest1 test1");
    }

    protected void index(Object fromWho, TLMsg msg) {
        outData odata =  creatOutDataMsg();
        odata.addData("<html><head><title>RequestInfo</TITLE></head>");
        odata.addData("<html><head><title>RequestInfo</TITLE></head>");
        odata.addData("<body>");
        odata.addData("this is serviceModle index ");
        odata.addData("</body></html>");
        putOutData(odata);
    }
    protected void setuser(Object fromWho, TLMsg msg) {
        outData odata =  creatOutDataMsg();
        odata.addData("<html><head><title>RequestInfo</TITLE></head>");
        odata.addData("<html><head><title>RequestInfo</TITLE></head>");
        odata.addData("<body>");
        odata.addData("this is serviceModle setuser ");
        odata.addData("</body></html>");
        putOutData(odata);
    }
}
