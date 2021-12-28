package cn.tianlong.tlobject.db.dbdata;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class ViewTable extends TLBaseTableModle {


    public ViewTable(String tableName ,  TLObjectFactory moduleFactory){
        super(tableName,moduleFactory);
        this.moduleFactory =moduleFactory;
        this.tableName  =tableName ;
        name=tableName;
        init();
    }
    @Override
    protected TLBaseModule init() {
        if(dataBase ==null)
            dataBase = (TLDataBase) getModule(databaseName);
        TLMsg tmsg = createMsg().setAction(DB_GETVIEW).setParam(DB_P_VIEWNAME,tableName);
        TLMsg returnmsg =putMsg(dataBase,tmsg);
        table= (TLBaseDataUnit) returnmsg.getParam(INSTANCE);
        return  this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        return null;
    }

    public void getSql(String sql ) {
        TLMsg sqlmsg =createMsg().setAction(DB_SETSQL).setParam(DB_P_SQL,sql)  ;
        putMsg(table,sqlmsg);
    }
    public void replaceSql(String sql ) {
        TLMsg sqlmsg =createMsg().setAction(DB_REPLACESQL).setParam(DB_P_SQL,sql)  ;
        putMsg(table,sqlmsg);
    }
    public String getSql( ) {
        TLMsg sqlmsg =createMsg().setAction(DB_GETSQL)  ;
        TLMsg returnMsg = putMsg(table,sqlmsg);
        return (String) returnMsg.getParam(DB_P_SQL);
    }
    public ArrayList<Map<String,Object>> get( ) {
        TLMsg sqlmsg =createMsg().setAction(DB_QUERY).setParam(DB_P_RESULTTYPE,TLDataBase.RESULT_TYPE.MAPLIST)  ;
        TLMsg returnMsg = putMsg(table,sqlmsg);
        return (ArrayList<Map<String,Object>> ) returnMsg.getParam(DB_R_RESULT);
    }
    public ArrayList<Map<String,Object>> get( LinkedHashMap<String, Object> sqlparams) {

        TLMsg updatemsg=createMsg().setAction(DB_QUERY)
                .setParam(DB_P_RESULTTYPE,TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg returMsg = putMsg(table,  updatemsg);
        return (ArrayList<Map<String,Object>> )  returMsg.getParam(DB_R_RESULT);
    }
}
