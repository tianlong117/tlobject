package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public abstract class TLBaseSessionData extends TLBaseModule {
    public TLBaseSessionData() {
        super();
    }
    public TLBaseSessionData(String name ) {
        super(name);
    }
    public TLBaseSessionData(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected TLBaseModule init() {
        return this ;
    }

    @Override
    public TLMsg getMsg(Object fromWho, TLMsg msg) {
        TLMsg returnMsg;
        switch (msg.getAction()) {
            case "get":
                returnMsg=getSessionData( fromWho,  msg);
                break;
            case "put":
                returnMsg=putSessionData( fromWho,  msg);
                break;
            case "delete":
                returnMsg=deleteSessionData( fromWho,  msg);
                break;
            default:
                returnMsg=checkMsgAction(fromWho,msg);
        }
        return returnMsg;
    }

    protected abstract TLMsg deleteSessionData(Object fromWho, TLMsg msg);

    protected abstract TLMsg putSessionData(Object fromWho, TLMsg msg);

    protected abstract TLMsg getSessionData(Object fromWho, TLMsg msg);

}
