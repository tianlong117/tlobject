package cn.tianlong.tlobject.redis;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLDatabase;
import cn.tianlong.tlobject.utils.TLDataUtils;

import java.util.*;

/**
 * 创建日期：${Date}${time}
 * 描述: 数据库表映射为redis的MAP数据，数据库主键唯一，每个主键为一个redis的key。
 *      数据库表通过主键查询，自动转为redis操作
 * 作者:tianlong
 */
public class TLTableToRedisMapTrigger extends TLBaseTriggerForTableToRedis {


    private boolean ifQueryDb=false;  /* 如果redis查询不到是否继续在数据库表中查询  */
    private boolean ifAutoSaveOnQuery=false;   /* 数据库查询是否自动存储到redis里 */

    public TLTableToRedisMapTrigger(String name ) {
        super(name);
    }
    public TLTableToRedisMapTrigger(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }
    @Override
    protected void initProperty() {
        super.initProperty();
        if(params !=null && params.get("ifQueryDb")!=null)
            ifQueryDb =Boolean.parseBoolean(params.get("ifQueryDb"));
        if(params !=null && params.get("ifAutoSaveOnQuery")!=null)
            ifAutoSaveOnQuery =Boolean.parseBoolean(params.get("ifAutoSaveOnQuery"));
    }
    @Override
    protected TLMsg onDelete(Object fromWho, TLMsg msg) {
        TLMsg nmsg=(TLMsg) msg.getSystemParam(DOWITHMSG);
        LinkedHashMap<String ,Object> sqlParams = (LinkedHashMap<String, Object>) nmsg.getParam(DB_P_PARAMS);
        String rediskey = getRedisKey(sqlParams);
        redisTable.del(rediskey);
        return (TLMsg) msg.getParam(PRERESULT);
    }

    @Override
    protected TLMsg onInsert(Object fromWho, TLMsg msg) {
        TLMsg nmsg=(TLMsg) msg.getSystemParam(DOWITHMSG);
        LinkedHashMap<String ,Object> sqlParams = (LinkedHashMap<String, Object>) nmsg.getParam(DB_P_PARAMS);
        insertRedisTable(sqlParams,null);
        return (TLMsg) msg.getParam(PRERESULT);
    }

    protected void insertRedisTable(HashMap<String,Object> sqlParams,String rediskey) {
        if(rediskey ==null)
            rediskey = getRedisKey(sqlParams);
        if(rediskey ==null)
            return;
        HashMap<String ,String> redisData ;
        HashMap<String,Object>  fieldsMap;
        if(fields !=null )
            fieldsMap =makeRedisData(sqlParams,fields,true);
        else if(tableFieldType!=null)
            fieldsMap = makeRedisData( sqlParams,tableFieldType,true);
        else
            fieldsMap = sqlParams;
        if(fieldsMap.size() ==0)
            return;
        redisData = (HashMap<String, String>) TLDataUtils.mapToStrMap(fieldsMap);
        ((TLRedisMap)redisTable).hmset(rediskey,redisData);
    }
    protected HashMap<String,Object> makeRedisData(Map<String,Object> inputMap, HashMap<String,String> inputfields,Boolean useDef) {
        HashMap<String,Object>  result =new HashMap<>();
        for(String field : inputfields.keySet())
        {
            if(inputMap.containsKey(field))
                result.put(field, inputMap.get(field));
            else if(useDef ==true &&tableDefaultValue.containsKey(field))
                result.put(field, tableDefaultValue.get(field));
        }
        return  result ;
    }

    @Override
    protected TLMsg onUpdate(Object fromWho, TLMsg msg) {
        TLMsg nmsg=(TLMsg) msg.getSystemParam(DOWITHMSG);
        LinkedHashMap<String ,Object> sqlParams = (LinkedHashMap<String, Object>) nmsg.getParam(DB_P_PARAMS);
        String rediskey = getRedisKey(sqlParams);
        boolean isExist = redisTable.exists(rediskey);
        if(isExist==false)
            return (TLMsg) msg.getParam(PRERESULT);
        HashMap<String ,Object> redisdatas =new LinkedHashMap<>();
        redisdatas.putAll(sqlParams);
        for(String keyName : redisKeyName){
            redisdatas.remove(keyName) ;           //去掉查询主键
        }
        updateRedisTable(redisdatas,rediskey);
        return (TLMsg) msg.getParam(PRERESULT);
    }

   protected void updateRedisTable(Map<String,Object> sqlParams, String rediskey) {
        Map<String ,String> redisData ;
        Map<String,Object>  fieldsMap;
        if(fields !=null )
            fieldsMap =makeRedisData(sqlParams,fields,false);
        else
            fieldsMap = sqlParams;
        if(fieldsMap.size() ==0)
            return;
        redisData =  TLDataUtils.mapToStrMap(fieldsMap);
        ((TLRedisMap)redisTable).hmset(rediskey,redisData);
    }
    protected void add(TLMsg msg,String field) {
        LinkedHashMap<String ,Object> sqlParams = (LinkedHashMap<String, Object>) msg.getParam(DB_P_PARAMS);
        String rediskey = getRedisKey(sqlParams);
        if(rediskey ==null)
            return ;
        Long value ;
        if(sqlParams.containsKey(field))
            value= (Long) sqlParams.get(field);
        else
            value = 1L;
        ((TLRedisMap)redisTable).hincrby(rediskey,field,value);
    }
    @Override
    protected TLMsg onQuery(Object fromWho, TLMsg msg) {
        TLMsg preResultMsg =(TLMsg) msg.getParam(PRERESULT);
        TLMsg nmsg=(TLMsg) msg.getSystemParam(DOWITHMSG);
        if(preResultMsg ==null){
            HashMap<String,String> result=queryRedis(nmsg);
            if(result !=null && !result.isEmpty()  )
            {
                HashMap<String,Object> dbData =redisToDbdata(result);
                TLDatabase.RESULT_TYPE result_type = (TLDatabase.RESULT_TYPE) nmsg.getParam(DB_P_RESULTTYPE);
                if(result_type.equals(TLDatabase.RESULT_TYPE.MAPLIST))
                {
                    List<Map> dbresult =new ArrayList();
                    dbresult.add(dbData);
                    return msg.setParam(DB_R_RESULT,dbresult).setSystemParam(MODULE_DONEXTMSG,false);
                }
                else
                    return msg.setParam(DB_R_RESULT,dbData).setSystemParam(MODULE_DONEXTMSG,false);
            }
            else if(ifQueryDb==false)
                return msg.setSystemParam(MODULE_DONEXTMSG,false);
            else
                return null ;
        }
        else
            return afterQuery(preResultMsg);
    }

    @Override
    protected TLMsg getValueFromRedis(TLMsg msg) {
        return null;
    }

    protected TLMsg afterQuery(TLMsg preResultMsg) {
        if(ifAutoSaveOnQuery==false)
            return preResultMsg ;
        List<HashMap<String,Object> > dbresult = (List) preResultMsg.getParam(DB_R_RESULT);
        if(dbresult.size() ==0)
            return preResultMsg ;
        for (HashMap<String,Object> redisData : dbresult){
            insertRedisTable(redisData,null);
        }
        return preResultMsg ;
    }

    protected HashMap<String, String> queryRedis(TLMsg msg){
        LinkedHashMap<String ,Object> sqlParams = (LinkedHashMap<String, Object>) msg.getParam(DB_P_PARAMS);
        String rediskey = getRedisKey(sqlParams);
        if(rediskey ==null)
            return null;
        Object sqlFields=  msg.getParam(DB_P_FIELDS);
        HashMap<String,String> result = new HashMap<>();
        if(sqlFields==null)
            result = (HashMap) ((TLRedisMap)redisTable).hgetall(rediskey);
        else if(sqlFields instanceof String[])
        {
           if(fields !=null){
               for(String sqlField : (String[])sqlFields){
                   if(!fields.containsKey(sqlField))
                        return null ;
               }
           }
            List<String> resultList =  ((TLRedisMap)redisTable).hmget(rediskey, (String[])sqlFields);
           if(resultList.isEmpty())
               return null;
            int j=0;
            int fieldsLenght = ((String[])sqlFields).length;
            for(int i = 0 ;i < fieldsLenght ;i++)
            {
               String value =resultList.get(i);
               if(value ==null)
                   j++;
               if(j == fieldsLenght)
                   return null ;
               result.put(((String[])sqlFields)[i],value);
            }
        }else if(sqlFields instanceof String){
            if(fields !=null){
               if(!fields.containsKey(sqlFields))
                       return null ;
            }
            String resultStr =  ((TLRedisMap)redisTable).hget(rediskey, (String) sqlFields);
            if(resultStr !=null)
               result.put((String) sqlFields,resultStr);
            else
                return null ;
        }
        return result;
    }

}
