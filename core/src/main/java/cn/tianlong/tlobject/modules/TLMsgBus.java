package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 创建日期：2018/4/23 on 21:00
 * 描述:
 * 作者:tianlong
 */
/**
 消息总线模块，注册到总线上的模块，接受总线传来的消息
 同一 destination 支持多个订阅者（扇出），任一订阅者返回非 null 即整体 ack（兼容单订阅者语义）
 */
public class TLMsgBus extends TLBaseModule {
    protected ConcurrentHashMap<String, CopyOnWriteArrayList<Object>>  receivers = new ConcurrentHashMap<>();
    public TLMsgBus(){
        super();
    }
    public TLMsgBus(String name ){
        super(name);
    }
    public TLMsgBus(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }
    @Override
    protected TLBaseModule init() {
        return this ;
    }

    @Override
    public TLMsg getMsg(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case "registBus":
                regsitBus( fromWho,  msg);
                break;
            case "unRegistBus":
                unRegistBus( fromWho,  msg);
                break;
            default:
                return onBus( fromWho,  msg);
        }
        return returnMsg;
    }

    private void unRegistBus(Object fromWho, TLMsg msg) {
        String destination = (String) msg.getParam("destination");
        Object object = msg.getParam("object");
        CopyOnWriteArrayList<Object> list = receivers.get(destination);
        if (list == null) return;
        if (object == null) {
            // 旧调用方式：未指定 object，移除该 destination 全部订阅
            receivers.remove(destination);
        } else {
            list.remove(object);
            if (list.isEmpty()) receivers.remove(destination);
        }
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        return null;
    }

    private TLMsg onBus(Object fromWho, TLMsg msg) {
        String destination = msg.getDestination();
        CopyOnWriteArrayList<Object> list = receivers.get(destination);
        if(list ==null || list.isEmpty())
            return null ;
        // 总线已按 destination 完成路由，清除之，避免接收方 getMsg 按 destination 二次路由（可支持 topic 键，而非仅模块名）
        msg.setDestination(null);
        // 扇出给全部订阅者；任一订阅者返回非 null 即整体 ack（兼容旧单订阅者语义）
        TLMsg ack = null;
        for (Object object : list) {
            TLMsg r = object instanceof String ? putMsg((String)object,msg) : putMsg((IObject)object,msg);
            if (r != null && ack == null) ack = r;
        }
        return ack;
    }
    private void regsitBus(Object fromWho, TLMsg msg) {
        String destination = (String) msg.getParam("destination");
        Object object=msg.getParam("object");
        if (object == null) return;
        receivers.computeIfAbsent(destination, k -> new CopyOnWriteArrayList<>()).add(object);
    }
}
