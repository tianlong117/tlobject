package cn.tianlong.tlobject.redis;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.*;

/**
 * 创建日期：${Date}${time}
 * 描述: 数据库表映射为redis的List数据，数据库主键不唯一，每个主键为一个redis的key。
 *      数据库表通过主键查询，自动转为redis操作
 * 作者:tianlong
 */
public class TLTableToRedisSetTrigger extends TLBaseTriggerForTableToRedis {

    protected  boolean ifQueryDb=false;  /* 如果redis查询不到是否继续在数据库表中查询  */
    protected  boolean ifAutoSaveOnQuery=false;   /* 数据库查询是否自动存储到redis里 */
    protected  String tbMainKey;

    public TLTableToRedisSetTrigger(String name ) {
        super(name);
    }
    public TLTableToRedisSetTrigger(String name , TLObjectFactory modulefactory){
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
        TLMsg nmsg=(TLMsg) msg.getSystemParam(DOWITHMAG);
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
            ((TLRedisSet)redisTable).srem(rediskey,  value.toString());
        }
        return (TLMsg) msg.getParam(PRERESULT);
    }
    @Override
    protected TLMsg onInsert(Object fromWho, TLMsg msg) {
        TLMsg nmsg=(TLMsg) msg.getSystemParam(DOWITHMAG);
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
            ((TLRedisSet)redisTable).sadd(rediskey,  value.toString());
        }

        return (TLMsg) msg.getParam(PRERESULT);
    }

    @Override
    protected TLMsg onUpdate(Object fromWho, TLMsg msg) {
       return  null ;
    }

    @Override
    protected TLMsg onQuery(Object fromWho, TLMsg msg) {
        TLMsg preResultMsg =(TLMsg) msg.getParam(PRERESULT);
        TLMsg nmsg=(TLMsg) msg.getSystemParam(DOWITHMAG);
        if(preResultMsg ==null)
            return getValueFromRedis(nmsg);
        else
            return afterQuery(preResultMsg);
    }
    @Override
    protected TLMsg getValueFromRedis(TLMsg msg) {
        LinkedHashMap<String ,Object> sqlParams = (LinkedHashMap<String, Object>) msg.getParam(DB_P_PARAMS);
        String rediskey = getRedisKey(sqlParams);
        if(rediskey ==null)
            return null;
        if(fields !=null && fields.size() ==1)
        {
            String key = fields.keySet().iterator().next();
            if(sqlParams.containsKey(key))
            {
                Object value = sqlParams.get(key);
                Boolean ifExist = ((TLRedisSet)redisTable).sismember(rediskey,  value.toString());
                List result = new ArrayList();
                if(ifExist)
                    result.add(value);
                return msg.setParam(DB_R_RESULT,result).setSystemParam(MODULE_DONEXTMSG,false);
            }
            else {
                List<HashMap<String,Object>> dbdata;
                if(sqlParams.get(DB_P_LIMITNUMB) ==null)
                {
                    Set<String> members = ((TLRedisSet)redisTable).smembers(rediskey );
                    if(members ==null || members.size()==0)
                    {
                        if(ifQueryDb)
                            return (TLMsg) msg.getParam(PRERESULT);
                        else
                            return msg.setSystemParam(MODULE_DONEXTMSG,false);
                    }
                    dbdata = redisToDbdata(members,key);
                }
                else {
                    List<String> members = ((TLRedisSet)redisTable).srandmember(rediskey,(int)sqlParams.get(DB_P_LIMITNUMB)  );
                    if(members ==null || members.size()==0)
                    {
                        if(ifQueryDb)
                            return (TLMsg) msg.getParam(PRERESULT);
                        else
                            return msg.setSystemParam(MODULE_DONEXTMSG,false);
                    }
                    dbdata = redisToDbdata(members,key);
                }
                return msg.setParam(DB_R_RESULT,dbdata).setSystemParam(MODULE_DONEXTMSG,false);
            }
        }
        return (TLMsg) msg.getParam(PRERESULT);
    }

    protected TLMsg afterQuery(TLMsg preResultMsg) {
        if (ifAutoSaveOnQuery == false)
            return preResultMsg;
        return preResultMsg;
    }
}
