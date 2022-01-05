package cn.tianlong.java.demo.db;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLBaseTableModle;

import java.util.LinkedHashMap;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class userModle extends TLBaseTableModle {

    public userModle(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
        tableName="user";
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg;
        switch (msg.getAction()) {
            case "findUser":
                returnMsg=findUser( fromWho,  msg);
                break;
            case "total":
                returnMsg=total( fromWho,  msg);
                break;
            default:
                returnMsg=null;
        }
        return returnMsg;
    }

    private TLMsg findUser(Object fromWho, TLMsg msg) {
        LinkedHashMap<String, Object> dbparams =new LinkedHashMap<>();
        dbparams.put("name",msg.getParam("userName"));
        TLMsg querymsg=createMsg().setAction(DB_FIND)
                .setParam(DB_P_PARAMS,dbparams);
        querymsg.setParam("cacheName","table_user");
        querymsg.setParam("cacheKey",msg.getParam("userName"));
       return  putMsg(table,querymsg);
    }
}
