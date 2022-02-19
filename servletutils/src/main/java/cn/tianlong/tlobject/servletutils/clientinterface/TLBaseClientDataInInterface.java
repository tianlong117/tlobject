package cn.tianlong.tlobject.servletutils.clientinterface;


import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.servletutils.TLWServModule;

import static cn.tianlong.tlobject.servletutils.TLParamString.*;

public abstract class TLBaseClientDataInInterface extends TLWServModule {
    protected String charset=UTF8 ;
    public TLBaseClientDataInInterface(){
        super();
    }
    public TLBaseClientDataInInterface(String name ) {
        super(name);
    }
    public TLBaseClientDataInInterface(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }
    @Override
    protected void initProperty() {
        super.initProperty();
        if( params !=null && params.get(CHARSET)!=null)
            charset=params.get(CHARSET);
    }
    @Override
    public TLMsg getMsg(Object fromWho, TLMsg msg) {
       return  runAction(fromWho,msg);
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case ININTERFACE_GETDATAFROMUSER:
                returnMsg=getDataFromUser(fromWho,msg);
                break;
            case ININTERFACE_GETCONTENTFROMUSER:
                returnMsg=getContentFromUser(fromWho,msg);
                break;
            default:

        }
        return returnMsg ;
    }
    protected abstract TLMsg getContentFromUser(Object fromWho, TLMsg msg);

    protected abstract TLMsg getDataFromUser(Object fromWho, TLMsg msg) ;
}
