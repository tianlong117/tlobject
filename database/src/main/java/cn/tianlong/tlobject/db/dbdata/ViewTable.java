package cn.tianlong.tlobject.db.dbdata;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.*;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class ViewTable extends TLBaseTableModel {


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
            dataBase = (TLDatabase) getModule(databaseName);
        if (dataBase == null) {
            putLog("ViewTable 初始化失败：找不到 database: " + databaseName, LogLevel.ERROR, "init");
            return this ;
        }
        TLMsg tmsg = createMsg().setAction(DB_GETVIEW).setParam(DB_P_VIEWNAME,tableName);
        TLMsg returnmsg =putMsg(dataBase,tmsg);
        if (returnmsg == null) {
            putLog("ViewTable 初始化失败：database 无响应: " + databaseName, LogLevel.ERROR, "init");
            return this ;
        }
        table= (TLBaseDataUnit) returnmsg.getParam(INSTANCE);
        if (table == null)
            putLog("ViewTable 初始化失败：视图不存在或未注册: " + tableName, LogLevel.ERROR, "init");
        return  this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        return null;
    }

    public void getSql(String sql ) {
        if (table == null) return;
        TLMsg sqlmsg =createMsg().setAction(DB_SETSQL).setParam(DB_P_SQL,sql)  ;
        putMsg(table,sqlmsg);
    }
    public void replaceSql(String sql ) {
        if (table == null) return;
        TLMsg sqlmsg =createMsg().setAction(DB_REPLACESQL).setParam(DB_P_SQL,sql)  ;
        putMsg(table,sqlmsg);
    }
    public String getSql( ) {
        if (table == null) return null;
        TLMsg sqlmsg =createMsg().setAction(DB_GETSQL)  ;
        TLMsg returnMsg = putMsg(table,sqlmsg);
        return returnMsg == null ? null : (String) returnMsg.getParam(DB_P_SQL);
    }
    public ArrayList<Map<String,Object>> get( ) {
        if (table == null) return null;
        TLMsg sqlmsg =createMsg().setAction(DB_QUERY).setParam(DB_P_RESULTTYPE,TLDatabase.RESULT_TYPE.MAPLIST)  ;
        TLMsg returnMsg = putMsg(table,sqlmsg);
        return returnMsg == null ? null : (ArrayList<Map<String,Object>> ) returnMsg.getParam(DB_R_RESULT);
    }
    public ArrayList<Map<String,Object>> get( LinkedHashMap<String, Object> sqlparams) {
        if (table == null) return null;
        TLMsg updatemsg=createMsg().setAction(DB_QUERY)
                .setParam(DB_P_RESULTTYPE,TLDatabase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg returMsg = putMsg(table,  updatemsg);
        return returMsg == null ? null : (ArrayList<Map<String,Object>> )  returMsg.getParam(DB_R_RESULT);
    }
}
