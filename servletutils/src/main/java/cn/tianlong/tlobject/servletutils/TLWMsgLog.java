package cn.tianlong.tlobject.servletutils;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.TLMsgLog;
import cn.tianlong.tlobject.base.TLObjectFactory;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLWMsgLog extends TLMsgLog {
    public TLWMsgLog(String name) {
        super(name);
    }

    public TLWMsgLog(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }
    protected TLMsg startLog(Object fromWho, TLMsg msg) {
        TLMsg dmsg = (TLMsg) msg.getParam("dmsg");
     return    super.startLog(fromWho,createMsg().setParam(DOWITHMAG,dmsg));
    }
}
