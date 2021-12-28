package cn.tianlong.tlobject.servletutils;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import static cn.tianlong.tlobject.servletutils.TLParamString.CLIENT_P_CONTENT;
import static cn.tianlong.tlobject.servletutils.TLParamString.M_DIRECTOUTNTERFACE;
import static cn.tianlong.tlobject.servletutils.TLParamString.OUTINTERFACE_PUTCONTENTTOUSER;

public class TLWError extends TLWServModule {
    public TLWError(){
        super();
    }
    public TLWError(String name ){
        super(name);
    }
    public TLWError(String name ,TLObjectFactory modulefactory){
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
            case "setError":
                setError(fromWho,msg);
                break;

            default:
        }
        return returnMsg ;
    }

    protected void setError(Object fromWho, TLMsg msg) {
        String content = (String) msg.getParam("content");
        TLMsg  emsg =createMsg().setAction(OUTINTERFACE_PUTCONTENTTOUSER).setParam(CLIENT_P_CONTENT,content);
        putMsg(M_DIRECTOUTNTERFACE, emsg);
    }
}
