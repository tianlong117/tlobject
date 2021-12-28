package cn.tianlong.java.demo.base;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

public class MyAppCenter extends TLBaseModule {
    public MyAppCenter(String name ){
        super(name);
    }
    public MyAppCenter(String name, TLObjectFactory moduleFactory)  {
        super(name,moduleFactory);
    }

    @Override
    protected TLBaseModule init() {
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        return null;
    }


}
