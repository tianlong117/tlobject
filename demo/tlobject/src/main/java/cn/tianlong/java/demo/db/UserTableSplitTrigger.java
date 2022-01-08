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
 * demo分表触发器， 以name第一个字母进行分表。以ASCII表数值为分表标准，名字第一个字母小于n在user1，其他的在user2中
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
            username = (String) tparams.get("name");
        if(username ==null)
            username = (String) nmsg.getParam(DB_P_SPLITKEY);
        if(username!=null )
        {
            //以ASCII表数值为分表标准，名字第一个字母小于n在user1，其他的在user2中
            int charint = username.charAt(0);
            if(charint < 110)
                charint=1 ;
            else
                charint=2 ;
            String tbtableName=tableName+charint;
            System.out.println("执行分表，当前表:"+tbtableName);
            return changeTable(tbtableName,nmsg);
        }
        else
            return doAllTable(nmsg);
    }


}
