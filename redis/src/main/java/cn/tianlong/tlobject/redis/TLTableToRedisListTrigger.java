package cn.tianlong.tlobject.redis;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.*;

/**
 * 创建日期：${Date}${time}
 * 描述: 数据库表映射为redis的List数据，数据库主键不唯一，每个主键为一个redis的key。
 *      数据库表通过主键查询，自动转为redis操作
 * 作者:tianlong
 */
public class TLTableToRedisListTrigger extends TLBaseTriggerForTableToRedis {

    protected  boolean ifQueryDb=false;  /* 如果redis查询不到是否继续在数据库表中查询  */
    protected  boolean ifAutoSaveOnQuery=false;   /* 数据库查询是否自动存储到redis里 */
    protected  String tbMainKey;
    protected  final Gson gson = new Gson();

    public TLTableToRedisListTrigger(String name ) {
        super(name);
    }
    public TLTableToRedisListTrigger(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }


    @Override
    protected void initProperty() {
        super.initProperty();
        if(params !=null && params.get("ifQueryDb")!=null)
            ifQueryDb =Boolean.parseBoolean(params.get("ifQueryDb"));
        if(params !=null && params.get("ifAutoSaveOnQuery")!=null)
            ifAutoSaveOnQuery =Boolean.parseBoolean(params.get("ifAutoSaveOnQuery"));
        if(params !=null && params.get("tbMainKey")!=null)
            tbMainKey =params.get("tbMainKey");
    }
    @Override
    protected TLMsg onDelete(Object fromWho, TLMsg msg) {
        TLMsg nmsg=(TLMsg) msg.getSystemParam(DOWITHMSG); msg.getSystemParam(DOWITHMSG);
        LinkedHashMap<String ,Object> sqlParams = (LinkedHashMap<String, Object>) nmsg.getParam(DB_P_PARAMS);
        String rediskey = getRedisKey(sqlParams);
        if(rediskey ==null)
            return null;
        if(fields !=null && fields.size() ==1)
        {
            String key = fields.keySet().iterator().next();
            Object value =sqlParams.get(key);
            if(value ==null)
                 return (TLMsg) msg.getParam(PRERESULT);
            ((TLRedisList)redisTable).lrem(rediskey, 0, value.toString());
        }
        else if(tbMainKey!=null)
             deleteByMainkey(nmsg);
        return (TLMsg) msg.getParam(PRERESULT);
    }
    protected long deleteByMainkey(TLMsg msg){
        LinkedHashMap<String ,Object> sqlParams = (LinkedHashMap<String, Object>) msg.getParam(DB_P_PARAMS);
        String rediskey = getRedisKey(sqlParams);
        String mainKey = (String) sqlParams.get(tbMainKey);
        int i=0;
        long result=0L;
        Type type = new TypeToken<Map<String, Object>>() {}.getType();
        Long size =((TLRedisList)redisTable).llen(rediskey);
        while (i < size) {
             String value= ((TLRedisList)redisTable).lindex(rediskey,i);
             Map<String ,Object> data = gson.fromJson(value,type);
             if(data.get(tbMainKey).equals(mainKey))
             {
                 result= ((TLRedisList)redisTable).lrem(rediskey,0,value);
                 break;
             }
            i++ ;
        }
        return result;
    }
    @Override
    protected TLMsg onInsert(Object fromWho, TLMsg msg) {
        TLMsg nmsg=(TLMsg) msg.getSystemParam(DOWITHMSG);
        LinkedHashMap<String ,Object> sqlParams = (LinkedHashMap<String, Object>) nmsg.getParam(DB_P_PARAMS);
        lpushRedisTable(sqlParams,null);
        return  (TLMsg) msg.getParam(PRERESULT);
    }

    protected Long  lpushRedisTable(Map<String,Object> sqlParams,String rediskey) {
        if(rediskey ==null)
           rediskey = getRedisKey(sqlParams);
        if(rediskey ==null)
            return null;
        if(fields !=null )
        {
            if(fields.size() ==1)
            {
                Object value=null;
                for(String field : fields.keySet())
                    value =sqlParams.get(field);
                return   ((TLRedisList)redisTable).lpush(rediskey,  value.toString());
            }
            else
            {
                HashMap<String,Object>  redisMap =new HashMap<>();
                for(String field : fields.keySet())
                    redisMap.put(field,sqlParams.get(field));
                return ((TLRedisList)redisTable).lpush(rediskey,redisMap);
            }
        }
        else
            return  ((TLRedisList)redisTable).lpush(rediskey,sqlParams);
    }

    @Override
    protected TLMsg onUpdate(Object fromWho, TLMsg msg) {
       return  null ;
    }

    @Override
    protected TLMsg onQuery(Object fromWho, TLMsg msg) {
        TLMsg preResultMsg =(TLMsg) msg.getParam(PRERESULT);
        TLMsg nmsg=(TLMsg) msg.getSystemParam(DOWITHMSG);
        if(preResultMsg ==null)
            return getValueFromRedis(nmsg);
        else
            return afterQuery(preResultMsg);
    }

    protected TLMsg afterQuery(TLMsg preResultMsg) {
        if(ifAutoSaveOnQuery==false)
            return preResultMsg ;
        Object dbresult =  preResultMsg.getParam(DB_R_RESULT);
        if(dbresult instanceof  List)
        {
            if((( List)dbresult).size() >0)
              for (Map redisData : ( List<Map>)dbresult){
                  lpushRedisTable(redisData,null);
              }
        }
        else  if(dbresult instanceof Map){
            lpushRedisTable((Map<String, Object>) dbresult,null);
        }
        return preResultMsg ;
    }
    protected String getLast(String rediskey){

        if(rediskey ==null)
            return null;
        List<String> result = ((TLRedisList)redisTable).lrange(rediskey,-1,-1);
        if( result ==null)
            return null ;
        if(result.size()==0)
            return null;
        return result.get(0);
    }
    @Override
    protected TLMsg getValueFromRedis(TLMsg msg){
        LinkedHashMap<String ,Object> sqlParams = (LinkedHashMap<String, Object>) msg.getParam(DB_P_PARAMS);
        String rediskey = getRedisKey(sqlParams);
        if(rediskey ==null)
            return null;
        int start=0;
        if(sqlParams.get(DB_P_LIMITSTART) !=null)
           start = (int) sqlParams.get(DB_P_LIMITSTART);
        int end=-1;
        if(sqlParams.get(DB_P_LIMITNUMB) !=null)
            end = start + (int)sqlParams.get(DB_P_LIMITNUMB)-1;
        Long tableLen = ((TLRedisList)redisTable).llen(rediskey);
        if(tableLen < end+1)
        {
            if(ifQueryDb==false)
                return msg.setSystemParam(MODULE_DONEXTMSG,false);
            else
                return null ;
        }
        List<String> result =  ((TLRedisList)redisTable).lrange(rediskey,start,end);
        if( result ==null)
        {
            if(ifQueryDb==false)
                return msg.setSystemParam(MODULE_DONEXTMSG,false);
            else
                return null ;
        }
        if(result.size()>0)
        {
           if(fields !=null && fields.size()==1)
               return msg.setParam(DB_R_RESULT,result).setSystemParam(MODULE_DONEXTMSG,false);
           else
            {
                List<Map> dbresult =new ArrayList();
                Type type = new TypeToken<Map<String, Object>>() {}.getType();
                        for (String value :result)
                {
                    Map<String ,Object> data = gson.fromJson(value,type);
                    dbresult.add( data);
                }
                return msg.setParam(DB_R_RESULT,dbresult).setSystemParam(MODULE_DONEXTMSG,false);
            }
        }
        else if(ifQueryDb==false)
            return msg.setSystemParam(MODULE_DONEXTMSG,false);
        else
            return null ;
    }

}
