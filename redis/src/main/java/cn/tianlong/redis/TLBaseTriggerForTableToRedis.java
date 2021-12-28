package cn.tianlong.tlobject.redis;


import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLDBTrigger;
import cn.tianlong.tlobject.db.TLDBUtilis;
import cn.tianlong.tlobject.db.TLTable;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.*;

/**
 * 创建日期：2018/3/21 on 8:48
 * 描述:
 * 作者:tianlong
 */

public abstract class TLBaseTriggerForTableToRedis extends TLDBTrigger {
    protected  boolean ifQueryDb=false;  /* 如果redis查询不到是否继续在数据库表中查询  */
    protected String[] redisKeyName;
    protected HashMap<String ,String> fields;
    protected HashMap<String ,String> tableFieldType ;
    protected HashMap<String ,String> tableDefaultValue ;
    protected String keySeparator=":";
    protected TLRedisTable redisTable;

    public TLBaseTriggerForTableToRedis() {
        super();
    }
    public TLBaseTriggerForTableToRedis(String name ) {
        super(name);
    }
    public TLBaseTriggerForTableToRedis(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
        ifExceptionHandle =false ;
    }
    @Override
    protected void initProperty() {
         super.initProperty();
        if(params !=null && params.get("fields")!=null )
            fields =TLDBUtilis.fieldsStrToMap(params.get("fields"));
        if(params !=null && params.get("tableDefaultValue")!=null )
        {
            tableDefaultValue =new HashMap<>();
            String[] fieldStr = params.get("tableDefaultValue").split(";");
            for(String field : fieldStr){
                field =field.trim();
                String[] fieldArray = field .split(":");
               tableDefaultValue.put(fieldArray[0],fieldArray[1]);
            }
        }

        if(params !=null && params.get("redisKeyName")!=null && !params.get("redisKeyName").isEmpty())
            redisKeyName = params.get("redisKeyName").split(";");
        if(params !=null && params.get("keySeparator")!=null )
            keySeparator = params.get("keySeparator");
        if(params !=null && params.get("redisTable")!=null)
        {
            String redisTableName=params.get("redisTable");
             redisTable = (TLRedisTable) getTable(redisTableName);
        }
        if(params !=null && params.get("ifQueryDb")!=null)
            ifQueryDb =Boolean.parseBoolean(params.get("ifQueryDb"));
        if(tableName !=null){
            TLTable tb = (TLTable) getTable(tableName);
            List<TLTable.ColumnModel> tableColumns =tb.getTableStructure();
            if(tableColumns ==null || tableColumns.size()==0)
                return;
            tableFieldType =new HashMap<>();
            if(tableDefaultValue ==null)
                tableDefaultValue =new HashMap<>();
            for(TLTable.ColumnModel columnModel: tableColumns )
            {
                tableFieldType.put(columnModel.getColumnName(),columnModel.getFieldType());
                tableDefaultValue.putIfAbsent(columnModel.getColumnName(),columnModel.getColumnDef());
            }
        }
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg;
        switch (msg.getAction()) {
            case "onInsert":
                returnMsg=onInsert( fromWho,  msg);
                break;
            case "onUpdate":
                returnMsg=onUpdate( fromWho,  msg);
                break;
            case "onDelete":
                returnMsg=onDelete( fromWho,  msg);
                break;
            case "onQuery":
                returnMsg=onQuery( fromWho,  msg);
                break;
            default:
                returnMsg=null;
        }
        return returnMsg;
    }
    protected abstract TLMsg onDelete(Object fromWho, TLMsg msg);
    protected abstract TLMsg onInsert(Object fromWho, TLMsg msg);
    protected abstract TLMsg onUpdate(Object fromWho, TLMsg msg);
    protected abstract TLMsg onQuery(Object fromWho, TLMsg msg);
    protected Object getValueFromSqlParams(TLMsg msg ,String param){
            LinkedHashMap<String ,Object> sqlParams = (LinkedHashMap<String, Object>) msg.getParam(DB_P_PARAMS);
            return  sqlParams.get(param);
    }
    protected String getRedisKey(Map<String,Object> dbparams){
        StringBuffer sb=new StringBuffer();
        if(redisKeyName ==null || redisKeyName.length==0)
            return "" ;
        for(String keyName : redisKeyName){
            Object value = dbparams.get(keyName) ;
            if (value ==null)
              return null ;
            if(value instanceof  String)
                sb.append(value);
            else
                sb.append(value.toString());
            sb.append(keySeparator);
        }
        sb.deleteCharAt(sb.lastIndexOf(keySeparator));
        return sb.toString();
    }


    protected List<HashMap<String,Object>> redisToDbdata(List<HashMap<String,String>> redisLists,String[] fields) {
        List<HashMap<String,Object>> dbData =new ArrayList<>();
        for (HashMap<String,String> data : redisLists)
            if(data.isEmpty() || data.size() ==0)
                continue;
            else
              dbData.add(redisToDbdata(data,fields));
        return dbData ;
    }
    protected HashMap<String,Object> redisToDbdata(HashMap<String,String> redisDatas ,String[] fields) {
        HashMap<String,Object> dbData =new HashMap<>();
        if(fields !=null && fields.length >0)
        {
            for(String key : fields){
                String value =redisDatas.get(key);
                String ftype =getValueType(key);
                Object fvalue =TLDBUtilis.stringToDBValue(ftype,value);
                dbData.put(key,fvalue);
            }
        }
        else
        {
            for (String key : redisDatas.keySet())
            {
                String value =redisDatas.get(key);
                String ftype =getValueType(key);
                Object fvalue =TLDBUtilis.stringToDBValue(ftype,value);
                dbData.put(key,fvalue);
            }
        }
        return dbData ;
    }
    protected HashMap<String,Object> redisToDbdata(HashMap<String,String> redisDatas ) {
        HashMap<String,Object> dbData =new HashMap<>();
        for (String key : redisDatas.keySet())
        {
            String value =redisDatas.get(key);
            String ftype =getValueType(key);
            Object fvalue =TLDBUtilis.stringToDBValue(ftype,value);
            dbData.put(key,fvalue);
        }
        return dbData ;
    }
    protected  List<HashMap<String,Object>> redisToDbdata(Set<String> redisDatas,String key) {
        List<HashMap<String,Object>> dblist =new ArrayList<>();
        for (String value : redisDatas)
        {
            HashMap<String,Object> dbData =new HashMap<>();
            String ftype =getValueType(key);
            Object fvalue =TLDBUtilis.stringToDBValue(ftype,value);
            dbData.put(key,fvalue);
            dblist.add(dbData);
        }
        return dblist ;
    }
    protected  List<HashMap<String,Object>> redisToDbdata(List<String> redisDatas,String key) {
        List<HashMap<String,Object>> dblist =new ArrayList<>();
        for (String value : redisDatas)
        {
            HashMap<String,Object> dbData =new HashMap<>();
            String ftype =getValueType(key);
            Object fvalue =TLDBUtilis.stringToDBValue(ftype,value);
            dbData.put(key,fvalue);
            dblist.add(dbData);
        }
        return dblist ;
    }
    private String getValueType(String key) {
        String valueType ;
        if(tableFieldType !=null && !tableFieldType.isEmpty())
            valueType =tableFieldType.get(key);
        else if(fields !=null && !fields.isEmpty())
            valueType = fields.get(key);
        else
            valueType ="String" ;
        return  valueType ;
    }
    protected TLMsg getContentFromMapTable(TLMsg msg , String mapTableName,String preFix) {
        TLMsg  returnMsg =getValueFromRedis(msg);
        if(returnMsg ==null)
            return null ;
        List<String> ids = (List<String>) returnMsg.getParam(DB_R_RESULT);
        if(ids ==null || ids.size()==0)
            return  returnMsg ;
        TLRedisMap mapTable= (TLRedisMap) getTable(mapTableName);
        List<HashMap<String ,String>> redisdatas=mapTable.hmgetToMap( ids, preFix );
        String[] fields = (String[]) msg.getParam(DB_P_FIELDS);
        List<HashMap<String ,Object>> datas =redisToDbdata(redisdatas,fields);
        /**  不通过管道
        Jedis redis = mapTable.getConn("read");
        List<HashMap<String ,Object>> datas =new ArrayList<>();
        for(String id : ids){
            String key =(preFix ==null)?id :preFix+id;
            HashMap<String,String> redisData;
            String[] fields = (String[]) msg.getParam(DB_P_FIELDS);
            if(fields!=null){
                redisData = mapTable.hmgetToMap(key, redis,fields);
            }
            else
                  redisData = (HashMap<String, String>) mapTable.hgetall(key,redis);
            HashMap<String ,Object> dbdata =redisToDbdata(redisData);
            datas.add(dbdata);
        }
        redis.close();
         **/
        return  createMsg().setParam(DB_R_RESULT,datas).setParam(MODULE_DONEXTMSG,"false");
    }
    protected TLMsg delContentFromMapTable(TLMsg msg , String mapTableName) {
        TLMsg  returnMsg =getValueFromRedis(msg);
        if(returnMsg ==null)
            return null ;
        List<String> ids = (List<String>) returnMsg.getParam(DB_R_RESULT);
        if(ids ==null || ids.size()==0)
            return  returnMsg ;
        TLRedisMap mapTable= (TLRedisMap) getTable(mapTableName);
        String[] idsArray = (String[]) ids.toArray();
        Long result = mapTable.del(idsArray);
        if(result ==0)
        {
            putLog("redis is error"+mapTable.getName(),LogLevel.ERROR,"delContentFromMapTable");
            return null;
        }
        LinkedHashMap<String ,Object> sqlParams = (LinkedHashMap<String, Object>) msg.getParam(DB_P_PARAMS);
        String rediskey = getRedisKey(sqlParams);
        redisTable.del(rediskey);
        return  createMsg().setParam(DB_R_RESULT,result).setParam(MODULE_DONEXTMSG,"false");
    }
    protected TLMsg delWithMapTable(TLMsg msg , String mapTableName) {
        TLMsg returnMsg =onDelete(this, msg);
        String result = (String) returnMsg.getParam(DB_R_RESULT);
        if(result==null)
            return (TLMsg) msg.getParam(PRERESULT);
        TLRedisMap mapTable= (TLRedisMap) getTable(mapTableName);
        mapTable.del(result);
        return (TLMsg) msg.getParam(PRERESULT);
    }
    protected  TLMsg getValueFromRedis(TLMsg msg){return  null;}
}
