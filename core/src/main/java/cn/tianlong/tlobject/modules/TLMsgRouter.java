package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;


public class TLMsgRouter extends TLBaseModule {

    public TLMsgRouter(){
        super();
    }
    public TLMsgRouter(String name ){
        super(name);
    }
    public TLMsgRouter(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected TLBaseModule init() {
        return this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
          return null ;
    }

}
