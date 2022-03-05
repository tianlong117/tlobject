package cn.tianlong.java.demo.db;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLBaseTriggerForSplitTable;
import cn.tianlong.tlobject.db.TLDBTrigger;
import cn.tianlong.tlobject.utils.TLDataUtils;
import cn.tianlong.tlobject.utils.TLMapUtils;

import java.util.LinkedHashMap;


/**
 * 创建日期：2018/3/21 on 8:48
 * 描述:
 * 作者:tianlong
 */

public  class CheckNameTrigger extends TLDBTrigger {
    protected String badname;
    protected int maxNumber=0;
    public CheckNameTrigger() {
        super();
    }
    public CheckNameTrigger(String name ) {
        super(name);
    }
    public CheckNameTrigger(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected void initProperty() {
        badname =TLMapUtils.getStringParam(params,"badname",null);
        maxNumber =TLMapUtils.parseInteger(params,"maxNumber",0);
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        System.out.println(" 执行触发器 CheckNameTrigger ---------------");
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "onInsert":
                returnMsg =onInsert( fromWho,  msg);
                break;
            default:
                ;
        }
        return returnMsg;
    }

    private TLMsg onInsert(Object fromWho, TLMsg msg) {
        TLMsg nmsg=(TLMsg) msg.getSystemParam(DOWITHMAG);
        LinkedHashMap<String ,Object> tparams= (LinkedHashMap<String, Object>) nmsg.getMapParam(DB_P_PARAMS,null);
        if(tparams ==null)
            return null ;
        String username = (String) tparams.get("name");
        int number = TLMapUtils.getIntParam(tparams,"number",0);
        if(!username.equals(badname))
        {
            if(maxNumber ==0 || number < maxNumber)
                return null ;
            else
            {
                System.out.println("number超过最大值:"+number);
                return createMsg().setParam(MODULE_DONEXTMSG,FALSESTR);
            }
        }
        else {
            System.out.println("名字非法:"+username);
            return createMsg().setParam(MODULE_DONEXTMSG,FALSESTR);
        }
    }


}
