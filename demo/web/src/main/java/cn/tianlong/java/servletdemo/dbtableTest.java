package cn.tianlong.java.servletdemo;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLTable;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class dbtableTest extends TLTable {
    public dbtableTest(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected TLMsg query(Object fromWho, TLMsg msg){
        TLMsg returenMsg =super.query(fromWho,msg);
        return returenMsg;
    }
}
