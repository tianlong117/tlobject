package cn.tianlong.java.demo.base;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

public class House extends TLBaseModule {

    public House(String name) {
        super(name);
    }

    public House(String name, TLObjectFactory moduleFactory) {
        super(name, moduleFactory);
    }

    @Override
    protected TLBaseModule init() {
        System.out.println("---模块创建: "+name + " 创建 ");
        return this;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case "light":
                light(fromWho, msg);
                break;
            case "dark":
                dark(fromWho, msg);
                break;
            default:
        }
        return null;
    }
    private void light(Object fromWho, TLMsg msg) {

        System.out.println("屋子亮了");
        toPerson("light");
    }
    private void dark(Object fromWho, TLMsg msg) {
        System.out.println("屋子黑了 ");
        toPerson("dark");
    }
    private void toPerson(String status) {
        TLMsg bmsg = createMsg().setDestination("msgBroadCast").setAction(MSGBROADCAST_BROADCAST)
                .setParam(MSGBROADCAST_P_MESSAGETYPE, "house")
                .setParam("status", status);
        putMsg("msgBroadCast", bmsg);
    }
}
