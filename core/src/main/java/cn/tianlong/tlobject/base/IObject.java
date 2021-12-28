package cn.tianlong.tlobject.base;

/**
 * 创建日期：2018/3/4 on 8:47
 * 描述:
 * 作者:tianlong
 */

public interface IObject  {

    String getName();
    TLMsg putMsg(IObject toWho, TLMsg msg);
    TLMsg getMsg(Object fromWho, TLMsg msg);
}
