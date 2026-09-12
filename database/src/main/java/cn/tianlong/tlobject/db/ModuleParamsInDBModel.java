package cn.tianlong.tlobject.db;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import java.util.*;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class ModuleParamsInDBModel extends TLBaseTableModel {

    public ModuleParamsInDBModel(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
        tableName="module_params";
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg;
        switch (msg.getAction()) {
            case DB_INSERT:
                returnMsg=insert( fromWho,  msg);
                break;
            case DB_DELETE:
                returnMsg=delete( fromWho,  msg);
                break;
            case DB_QUERY:
                returnMsg=query( fromWho,  msg);
                break;
            default:
                returnMsg=null;
        }
        return returnMsg;
    }

    protected TLMsg query(Object fromWho, TLMsg msg) {
        String sql = "select * from  [table] where module=? ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        String module= (String) msg.getParam("module");
        sqlparams.put("module", module);
        TLMsg qmsg = createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_RESULTTYPE, TLDatabase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg returnMsg = putMsg(table, qmsg);
        ArrayList<HashMap<String, Object>> result = (ArrayList<HashMap<String, Object>>) returnMsg.getParam(DB_R_RESULT);
        if(result ==null || result.isEmpty())
            return null ;
        TLMsg paramMsg =createMsg();
        for(HashMap<String, Object> param : result)
        {
            String key = (String) param.get("param_key");
            String value = (String) param.get("param_value");
            paramMsg.setParam(key,value) ;
        }
        return paramMsg;
    }

    protected TLMsg delete(Object fromWho, TLMsg msg) {
        String sql = "delete from  [table] where module=?  and param_key =?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("module", msg.getParam("module"));
        sqlparams.put("param_key", msg.getParam("param_key"));
        TLMsg dmsg = createMsg().setAction(DB_DELETE)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_PARAMS, sqlparams);
        return putMsg(table, dmsg);
    }
    protected TLMsg insert(Object fromWho, TLMsg msg) {

        String sql = "insert into  [table]  (module,param_key,param_value,param_type,remark) values(?,?,?,?,?)  ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("module", msg.getParam("module"));
        sqlparams.put("param_key", msg.getParam("param_key"));
        sqlparams.put("param_value", msg.getParam("param_value"));
        sqlparams.put("param_type", msg.getParam("param_type"));
        sqlparams.put("remark", msg.getParam("remark"));
        TLMsg insertmsg = createMsg().setAction(DB_INSERT)
                    .setParam(DB_P_SQL, sql)
                    .setParam(DB_P_PARAMS, sqlparams);
        return putMsg(table, insertmsg);
    }

}
