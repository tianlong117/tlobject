package cn.tianlong.tlobject.execl;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLBaseTableModel;
import cn.tianlong.tlobject.db.TLDBUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * 创建日期：2020/9/420:26
 * 描述:
 * 作者:tianlong
 */
public class TLExeclToDbModule extends TLBaseTableModel {
    private HashMap<String ,String> dbFields ;
    public TLExeclToDbModule(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }
    @Override
    protected void initProperty() {
        super.initProperty();
        if(params !=null && params.get("fields")!=null )
            dbFields =TLDBUtils.fieldsStrToMap(params.get("fields"));
    }
    @Override
    protected TLBaseModule init() {
        return  super.init();
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg;
        switch (msg.getAction()) {
            case "execlToDb":
                returnMsg=execlToDb( fromWho,  msg);
                break;
            default:
                returnMsg=null;
        }
        return returnMsg;
    }

    protected TLMsg execlToDb(Object fromWho, TLMsg msg) {
        String tableName =(msg.isNull("table"))?"[table]": (String) msg.getParam("table");
        List<HashMap<String, Object>> listMap = (List<HashMap<String, Object>>) msg.getParam("datas");
        String sql ;
        String[] fieldsNames ;
        if(dbFields ==null)
        {
            HashMap<String, Object> dataMap0= listMap.get(0);
            Set keySets =dataMap0.keySet() ;
            fieldsNames = (String[]) keySets.toArray(new String[0]);
        }
        else
            fieldsNames =  dbFields.keySet().toArray(new String[0]);
        sql =TLDBUtils.createInsertSql(fieldsNames,tableName) ;
        int rows =listMap.size();
        Object[][] bparams = new Object[rows][fieldsNames.length];
        List<String> dbFildsList = Arrays.asList(fieldsNames);
        for (int i = 0; i < rows; i++) {
            HashMap<String, Object> map =listMap.get(i);
            for(int j =0 ;j < dbFildsList.size() ; j++){
                String fieldName =dbFildsList.get(j) ;
                if(dbFields ==null)
                   bparams[i][j] = map.get(fieldName);
                else
                {
                    String ftype =dbFields.get(fieldName);
                    bparams[i][j] =TLDBUtils.stringToDBValue(ftype, (String) map.get(fieldName));
                }
            }
        }
        TLMsg insertmsg = new TLMsg().setAction(DB_BATCH)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_PARAMS, bparams);
        TLMsg returnMsg = putMsg(table, insertmsg);
        int[] result = (int[]) returnMsg.getParam(DB_R_RESULT);
        if(result==null)
            return  createMsg().setParam(DB_R_RESULT,0);
        else
            return  createMsg().setParam(DB_R_RESULT,result.length);
    }

}
