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
                registBus( fromWho,  msg);
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
        if (destination == null) return;   // ConcurrentHashMap 禁 null key
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
        if (destination == null) return null;   // ConcurrentHashMap 禁 null key；无 destination 非总线消息（如 shutdown 的 destroy 消息）
        CopyOnWriteArrayList<Object> list = receivers.get(destination);
        if(list ==null || list.isEmpty())
            return null ;
        // 总线已按 destination 完成路由，清除之，避免接收方 getMsg 按 destination 二次路由（可支持 topic 键，而非仅模块名）
        msg.setDestination(null);
        // 扇出给全部订阅者；任一订阅者返回非 null 即整体 ack（兼容旧单订阅者语义）
        TLMsg ack = null;
        for (Object object : list) {
            // 每个订阅者一份独立 msg：共享同一实例时 putMsg 会把 waitFlag 就地翻成 true，
            // 导致首个订阅者异步、其余订阅者变同步直调；异步订阅者还会与总线线程并发读写
            // 同一个非并发容器 args/systemArgs（对照 TLMsgBroadCast 同样是逐接收者拷贝）
            TLMsg send = createMsg().copyFrom(msg);
            TLMsg r = object instanceof String ? putMsg((String)object,send) : putMsg((IObject)object,send);
            if (r != null && ack == null) ack = r;
        }
        return ack;
    }
    private void registBus(Object fromWho, TLMsg msg) {
        String destination = (String) msg.getParam("destination");
        Object object=msg.getParam("object");
        if (object == null || destination == null) return;   // ConcurrentHashMap 禁 null key
        receivers.computeIfAbsent(destination, k -> new CopyOnWriteArrayList<>()).add(object);
    }
}
