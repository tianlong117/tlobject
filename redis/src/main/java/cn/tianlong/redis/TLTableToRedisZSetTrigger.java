package cn.tianlong.tlobject.redis;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.*;

/**
 * 创建日期：${Date}${time}
 * 描述: 数据库表映射为redis的List数据，数据库主键不唯一，每个主键为一个redis的key。
 * 数据库表通过主键查询，自动转为redis操作
 * 作者:tianlong
 */
public class TLTableToRedisZSetTrigger extends TLBaseTriggerForTableToRedis {


    protected boolean ifAutoSaveOnQuery = false;   /* 数据库查询是否自动存储到redis里 */
    protected String tbMainKey;
    protected String scoreField;

    public TLTableToRedisZSetTrigger(String name) {
        super(name);
    }

    public TLTableToRedisZSetTrigger(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }


    @Override
    protected void initProperty() {
        super.initProperty();

        if (params != null && params.get("ifAutoSaveOnQuery") != null)
            ifAutoSaveOnQuery = Boolean.parseBoolean(params.get("ifAutoSaveOnQuery"));
        if (params != null && params.get("tbMainKey") != null)
            tbMainKey = params.get("tbMainKey");
        if (params != null && params.get("scoreField") != null)
            scoreField = params.get("scoreField");
    }

    @Override
    protected TLMsg onDelete(Object fromWho, TLMsg msg) {
        TLMsg nmsg = (TLMsg) msg.getParam(DOWITHMAG);
        LinkedHashMap<String, Object> sqlParams = (LinkedHashMap<String, Object>) nmsg.getParam(DB_P_PARAMS);
        String rediskey = getRedisKey(sqlParams);
        if (rediskey == null)
            return null;
        if (fields != null && fields.size() == 1) {
            String key = fields.keySet().iterator().next();
            Object value = sqlParams.get(key);
            if (value == null)
                return (TLMsg) msg.getParam(PRERESULT);
            Long result = ((TLRedisZSet) redisTable).zrem(rediskey, value.toString());
            return msg.setParam(DB_R_RESULT, result.intValue());
        }
        return (TLMsg) msg.getParam(PRERESULT);
    }

    @Override
    protected TLMsg onInsert(Object fromWho, TLMsg msg) {
        TLMsg nmsg = (TLMsg) msg.getParam(DOWITHMAG);
        LinkedHashMap<String, Object> sqlParams = (LinkedHashMap<String, Object>) nmsg.getParam(DB_P_PARAMS);
        String rediskey = getRedisKey(sqlParams);
        if (rediskey == null)
            return null;
        Double score = 0D;
        if (scoreField != null) {
            Object scoreValue = sqlParams.get(scoreField);
            score = makeScore(scoreValue);
        }
        Long result;
        if (fields != null && fields.size() == 1) {
            String key = fields.keySet().iterator().next();
            Object value = sqlParams.get(key);
            if (value == null)
                return (TLMsg) msg.getParam(PRERESULT);
            result = ((TLRedisZSet) redisTable).zadd(rediskey, score, value.toString());
        } else {
            HashMap<String, Object> redisMap = new HashMap<>();
            if(fields !=null)
            {
                for (String field : fields.keySet())
                    redisMap.put(field, sqlParams.get(field));
                result = ((TLRedisZSet) redisTable).zadd(rediskey, score, redisMap);
            }
            else
                result = ((TLRedisZSet) redisTable).zadd(rediskey, score, sqlParams);
        }
        if (result == 0)
            putLog("redis is error" + redisTable.getName(), LogLevel.WARN, "onInsert");
        return (TLMsg) msg.getParam(PRERESULT);
    }

    protected Double makeScore(Object scoreValue) {
        if (scoreValue instanceof String)
            return Double.parseDouble((String) scoreValue);
        else {
            return Double.parseDouble(String.valueOf(scoreValue));
        }
    }

    @Override
    protected TLMsg onUpdate(Object fromWho, TLMsg msg) {
       return  onInsert(fromWho,msg) ;
    }

    @Override
    protected TLMsg onQuery(Object fromWho, TLMsg msg) {
        TLMsg preResultMsg = (TLMsg) msg.getParam(PRERESULT);
        TLMsg nmsg = (TLMsg) msg.getParam(DOWITHMAG);
        if (preResultMsg == null)
            return getValueFromRedis(nmsg);
        else
            return afterQuery(preResultMsg);
    }
    protected TLMsg getValueByScore(TLMsg msg){
        LinkedHashMap<String, Object> sqlParams = (LinkedHashMap<String, Object>) msg.getParam(DB_P_PARAMS);
        String rediskey = getRedisKey(sqlParams);
        if (rediskey == null)
            return null;
        Object scoreValue = sqlParams.get(scoreField);
        String score = String.valueOf(makeScore(scoreValue));
        Set<String>  members = ((TLRedisZSet) redisTable).zrangebyscore(rediskey, score, score);
        if (members == null || members.size() == 0) {
            if (ifQueryDb)
                return (TLMsg) msg.getParam(PRERESULT);
            else
                return msg.setParam(MODULE_DONEXTMSG, "false");
        }
        Iterator<String> it = members.iterator();
        int i = 0;
        String value=null ;
        while(it.hasNext()){
            if(i ==0)
             value = it.next();
            else
                break;
        }
        return msg.setParam(DB_R_RESULT,value).setParam(MODULE_DONEXTMSG,"false");
    }
    @Override
    protected TLMsg getValueFromRedis(TLMsg msg) {
        LinkedHashMap<String, Object> sqlParams = (LinkedHashMap<String, Object>) msg.getParam(DB_P_PARAMS);
        String rediskey = getRedisKey(sqlParams);
        if (rediskey == null)
            return null;
        String orderType = (String) msg.getParam(DB_P_ORDERTYPE);
        if (orderType == null)
            orderType = DB_V_DESC;
        int number = (sqlParams.get(DB_P_LIMITNUMB) != null) ? (int) sqlParams.get(DB_P_LIMITNUMB) : 0;
        int offset = (sqlParams.get(DB_P_LIMITSTART) != null) ? (int) sqlParams.get(DB_P_LIMITSTART) : 0;
        if (fields != null && fields.size() == 1) {
            String key = fields.keySet().iterator().next();
            if (sqlParams.containsKey(key)) {
                Object value = sqlParams.get(key);
                Long index = ((TLRedisZSet) redisTable).zrank(rediskey, value.toString());
                List result = new ArrayList();
                if (index != null)
                    result.add(value);
                return msg.setParam(DB_R_RESULT, result).setParam(MODULE_DONEXTMSG, "false");
            }
        }
        Set<String> members;
        if (msg.isNull(REDIS_MINVALUE) && msg.isNull(REDIS_MAXVALUE)) {
            String min = "-inf";
            String max = "+inf";
            if (!msg.isNull(REDIS_MINSCORE))
                min = "(" + makeScore(msg.getParam(REDIS_MINSCORE));
            else if (!msg.isNull(REDIS_EQALMINSCORE))
                min = "" + makeScore(msg.getParam(REDIS_EQALMINSCORE));
            if (!msg.isNull(REDIS_MAXSCORE))
                max = "(" + makeScore(msg.getParam(REDIS_MAXSCORE));
            else if (!msg.isNull(REDIS_EQALMAXSCORE))
                max = "" + makeScore(msg.getParam(REDIS_EQALMAXSCORE));
            members = getMemberByScore(rediskey, min, max, offset, number, orderType);

        } else {
            String min = "-";
            String max = "+";
            if (!msg.isNull(REDIS_MINVALUE))
                min = "(" + msg.getParam(REDIS_MINVALUE);
            else if (!msg.isNull(REDIS_EQALMINVALUE))
                min = "[" + msg.getParam(REDIS_EQALMINVALUE);
            if (!msg.isNull(REDIS_MAXVALUE))
                max = "(" + msg.getParam(REDIS_MAXVALUE);
            else if (!msg.isNull(REDIS_EQALMAXVALUE))
                max = "[" + msg.getParam(REDIS_EQALMAXVALUE);
            members = getMemberByLex(rediskey, min, max, offset, number, orderType);
        }
        if (members == null || members.size() == 0) {
            if (ifQueryDb)
                return (TLMsg) msg.getParam(PRERESULT);
            else
                return msg.setParam(MODULE_DONEXTMSG, "false");
        }
        List<Map> dbresult;
        if(fields !=null && fields.size()==1)
           dbresult =new ArrayList(members);
        else
        {
             dbresult =new ArrayList();
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Gson gson =new Gson() ;
            for (String value :members)
            {
                Map<String ,Object> data = gson.fromJson(value,type);
                dbresult.add( data);
            }
        }
        return msg.setParam(DB_R_RESULT,dbresult).setParam(MODULE_DONEXTMSG,"false");
    }

    private Set<String> getMemberByLex(String rediskey, String min, String max, int offset, int number, String orderType) {
        Set<String> members;
        if (orderType.equals(DB_V_ASC)) {
            if (number == 0)
                members = ((TLRedisZSet) redisTable).zrangeByLex(rediskey, min, max);
            else
                members = ((TLRedisZSet) redisTable).zrangeByLex(rediskey, min, max, offset, number);
        } else {
            if (number == 0)
                members = ((TLRedisZSet) redisTable).zrevrangeByLex(rediskey, max, min);
            else
                members = ((TLRedisZSet) redisTable).zrevrangeByLex(rediskey, max, min, offset, number);
        }
        return members;

    }

    private Set<String> getMemberByScore(String rediskey, String min, String max, int offset, int number, String orderType) {
        Set<String> members;
        if (orderType.equals(DB_V_ASC)) {
            if (number == 0)
                members = ((TLRedisZSet) redisTable).zrangebyscore(rediskey, min, max);
            else
                members = ((TLRedisZSet) redisTable).zrangeByScore(rediskey, min, max, offset, number);
        } else {
            if (number == 0)
                members = ((TLRedisZSet) redisTable).zrevrangeByScore(rediskey, max, min);
            else
                members = ((TLRedisZSet) redisTable).zrevrangeByScore(rediskey, max, min, offset, number);
        }
        return members;

    }

    protected TLMsg afterQuery(TLMsg preResultMsg) {
        if (ifAutoSaveOnQuery == false)
            return preResultMsg;
        return preResultMsg;
    }
}
