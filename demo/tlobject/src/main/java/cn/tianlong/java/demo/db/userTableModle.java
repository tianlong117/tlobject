package cn.tianlong.java.demo.db;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLBaseTableModle;
import cn.tianlong.tlobject.db.TLDataBase;
import cn.tianlong.tlobject.utils.TLDataUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class userTableModle extends TLBaseTableModle {

    public userTableModle(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
        tableName="userTable";
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg;
        switch (msg.getAction()) {
            case "queryByNumber":
                returnMsg=queryByNumber( fromWho,  msg);
                break;
            default:
                returnMsg=null;
        }
        return returnMsg;
    }

    private TLMsg queryByNumber(Object fromWho, TLMsg msg) {
        Long startTime  =moduleFactory.getRunTime(false);
        int number=TLDataUtils.parseInt(msg.getParam("number"),0);
        boolean  ifCache =TLDataUtils.parseBoolean(msg.getAndRemoveParam("ifCache"),false);
        String sql = "select * from  [table]  where  number = ? ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("number", number);
        TLMsg querymsg = createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_ORDERBY,"name")
                .setParam(DB_P_PARAMS, sqlparams);
        if(ifCache)
            querymsg.setParam("cacheName","usertable_number");
      TLMsg returnMsg =  putMsg(table, querymsg);
        Long endTime  =moduleFactory.getRunTime(false);
        println("run time :"+ (endTime-startTime));
        return  returnMsg ;
    }

}
