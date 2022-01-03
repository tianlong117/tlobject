package cn.tianlong.java.demo.base;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

public class House extends DemoCommon {

    public House(String name) {
        super(name);
    }

    public House(String name, TLObjectFactory moduleFactory) {
        super(name, moduleFactory);
    }

    @Override
    protected TLBaseModule init() {
        return this;
    }


    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        printAction(msg);
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

        printState("屋子亮了");
        toPerson("light");
    }
    private void dark(Object fromWho, TLMsg msg) {
        printState("屋子黑了 ");
        toPerson("dark");
    }
    private void toPerson(String status) {
        printState("屋子发送广播消息,消息类型:"+status);
        TLMsg bmsg = createMsg().setDestination("msgBroadCast").setAction(MSGBROADCAST_BROADCAST)
                .setParam(MSGBROADCAST_P_MESSAGETYPE, "light")
                .setParam("status", status);
        putMsg("msgBroadCast", bmsg);
    }
}
