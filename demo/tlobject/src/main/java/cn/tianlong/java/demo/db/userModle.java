package cn.tianlong.java.demo.db;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLBaseTableModel;
import cn.tianlong.tlobject.db.TLDatabase;
import cn.tianlong.tlobject.utils.TLMsgUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class userModle extends TLBaseTableModel {

    public userModle(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
        tableName="userTable";
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg;
        switch (msg.getAction()) {
            case "queryTb":
                 returnMsg = queryTb(fromWho, msg);
                break;
            default:
                returnMsg=null;
        }
        return returnMsg;
    }

    private TLMsg queryTb(Object fromWho, TLMsg msg) {
        String username=msg.getStringParam("username",null);
        String sql = "select * from  [table]  where  name like  ?\"%\" ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("name", username);
        TLMsg querymsg = createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_RESULTTYPE, TLDatabase.RESULT_TYPE.MAPLIST)
                .setParam("cacheName","users")
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg  returnMsg = putMsg(table, querymsg);
        if(msg.getBooleanParam("isFromWeb",false)==true)
            return returnMsg ;
        ArrayList<LinkedHashMap> datas = (ArrayList<LinkedHashMap>) returnMsg.getListParam(RESULT,null);
        if(datas ==null || datas.isEmpty())
        {
            System.out.println(name+":没有数据");
            return returnMsg;
        }
        System.out.println(name+":查询结果:");
        TLMsgUtils.printList(datas);
        return  returnMsg ;
    }

}
