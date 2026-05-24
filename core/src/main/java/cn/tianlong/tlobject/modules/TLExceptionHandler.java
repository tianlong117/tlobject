package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

/**
 * 创建日期：2018/4/23 on 21:00
 * 描述:
 * 作者:tianlong
 */

public class TLExceptionHandler extends TLBaseModule {
    public TLExceptionHandler(String name ){
        super(name);
    }
    public TLExceptionHandler(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }
    @Override
    protected TLBaseModule init() {
        return this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg;
        switch (msg.getAction()) {
            case EXCEPTIONHANDLER_HANDLER:
                returnMsg=exceptionHandler( fromWho,  msg);
                break;
            default:
                returnMsg=exceptionHandler( fromWho,  msg);;
        }
        return returnMsg;
    }

   protected TLMsg exceptionHandler(Object fromWho, TLMsg msg) {
       return null;
   }

}
