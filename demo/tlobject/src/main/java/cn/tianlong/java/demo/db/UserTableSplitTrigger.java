package cn.tianlong.java.demo.db;

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
    protected TLMsg selectTable(Object fromWho, TLMsg msg){
        TLMsg nmsg=(TLMsg) msg.getParam(DOWITHMAG);
        String isWait = (String) nmsg.getParam("isWait");
        if(isWait!=null && isWait.equals("false"))
            nmsg.setWaitFlag(false);
        String table = (String) nmsg.getParam(DB_P_TABLENAME);
        if(table !=null)
            return changeTable(table,nmsg);
        LinkedHashMap<String ,Object> tparams= (LinkedHashMap<String, Object>) nmsg.getParam(DB_P_PARAMS);
        String  username =null;
        if(tparams !=null)
            username = (String) tparams.get("username");
        if(username ==null)
            username = (String) nmsg.getParam(DB_P_SPLITKEY);
        if(username!=null )
        {
            int charint = Integer.parseInt( username.substring(0,1));

            if(charint < 20)
                charint "a";
            else
                return "b";
            String tbtableName=QQDatabase.splitMembers(tableName, username);
            return changeTable(tbtableName,nmsg);
        }
        else
            return doAllTable(nmsg);
    }


}
