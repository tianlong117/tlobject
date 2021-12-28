package cn.tianlong.java.servletdemo;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLBaseTriggerForSplitTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;


/**
 * 创建日期：2018/3/21 on 8:48
 * 描述:
 * 作者:tianlong
 */

public  class UserTableSplitTrigger extends TLBaseTriggerForSplitTable {

    public UserTableSplitTrigger() {
        super();
    }
    public UserTableSplitTrigger(String name ) {
        super(name);
    }
    public UserTableSplitTrigger(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected TLMsg selectTable(Object fromWho,TLMsg msg){
        TLMsg nmsg=(TLMsg) msg.getParam(DOWITHMAG);
        String isWait = (String) nmsg.getParam("isWait");
        if(isWait!=null && isWait.equals("false"))
            nmsg.setWaitFlag(false);
        LinkedHashMap<String ,Object> tparams= (LinkedHashMap<String, Object>) nmsg.getParam("params");
        if(tparams==null ||tparams.get("number")==null)
        {
            ArrayList<Object> totaldatas=new ArrayList<>();
            TLMsg wreturn =changeTable("userw",nmsg);
            TLMsg mreturn= changeTable("userm",nmsg);
            List mdatas;
            if(mreturn!=null && mreturn.getParam("result")!=null)
            {
                mdatas = (List) mreturn.getParam("result");
                totaldatas.addAll(mdatas);
            }
            List wdatas ;
            if(wreturn!=null && (List) wreturn.getParam("result")!=null)
            {
                wdatas = (List) wreturn.getParam("result");
                totaldatas.addAll(wdatas);
            }
           return createMsg().setParam("result",totaldatas).setParam(MODULE_DONEXTMSG,"false");
        }
        else
        {
            int numb= (int) tparams.get("number");
            if(numb==0)
                return null ;
            if(numb >=100)
                return changeTable("userm",nmsg);
            else
                return changeTable("userw",nmsg);
        }
    }



}
