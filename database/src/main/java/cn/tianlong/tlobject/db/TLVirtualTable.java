package cn.tianlong.tlobject.db;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

/**
 * 创建日期：2019/6/216:10
 * 描述:
 * 作者:tianlong
 */
public class TLVirtualTable extends TLTable {

    public TLVirtualTable(String name ) {
        super(name);
    }
    public TLVirtualTable(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }
    @Override
    protected TLMsg insert(Object fromWho, TLMsg msg) {
        return null;
    }

    @Override
    protected TLMsg update(Object fromWho, TLMsg msg) {
        return null;
    }

    @Override
    protected TLMsg delete(Object fromWho, TLMsg msg) {
        return null;
    }

    @Override
    protected TLMsg query(Object fromWho, TLMsg msg) {
        return null;
    }
}
