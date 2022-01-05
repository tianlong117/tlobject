package cn.tianlong.java.demo.db;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLDBTrigger;


/**
 * 创建日期：2018/3/21 on 8:48
 * 描述:
 * 作者:tianlong
 */

public  class userAfterTrigger extends TLDBTrigger {

    public userAfterTrigger() {
        super();
    }
    public userAfterTrigger(String name ) {
        super(name);
    }
    public userAfterTrigger(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        return null;
    }


}
