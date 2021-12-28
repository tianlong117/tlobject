package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.HashMap;

/**
 * 创建日期：2018/4/23 on 21:00
 * 描述:
 * 作者:tianlong
 */

public class TLMsgBus extends TLBaseModule {
    protected HashMap<String,Object>  receivers= new HashMap <String, Object>();
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
        receivers.remove(destination);
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        return null;
    }

    private TLMsg onBus(Object fromWho, TLMsg msg) {
        String destination = msg.getDestination();
        Object object =receivers.get(destination);
        if(object instanceof String)
          return   putMsg((String)object,msg);
        else
         return    putMsg((IObject)object,msg);
    }
    private void regsitBus(Object fromWho, TLMsg msg) {
        String destination = (String) msg.getParam("destination");
        Object object=msg.getParam("object");
        receivers.put(destination,object);
    }
}
