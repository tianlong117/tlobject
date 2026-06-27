package cn.tianlong.tlobject.redis;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.util.*;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLRedisMap extends TLRedisTable {

    public TLRedisMap(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "set":
                returnMsg = insert( fromWho,  msg);
                break;
            default:
                returnMsg=super.checkMsgAction(fromWho,msg);
        }
        return returnMsg;
    }

    @Override
    protected TLMsg insert(Object fromWho, TLMsg msg) {
       String key = (String) msg.getParam(REDIS_KEY);
       Object values =  msg.getParam(REDIS_VALUE);
       Object result ;
        if(values instanceof  Map)
            result =hmset(getKey(key), (Map<String, String>) values);
        else if(values instanceof String && msg.getParam(REDIS_FIELD)!=null)
            result =hset(getKey(key),(String)msg.getParam(REDIS_FIELD),(String)values) ;
        else
            return null ;
       return  createMsg().setParam(REDIS_RESULT,result)   ;
    }

    @Override
    protected TLMsg update(Object fromWho, TLMsg msg) {
        String redisAction = (String) msg.getParam(REDIS_ACTION);
        if(redisAction ==null || redisAction.isEmpty())
            return insert( fromWho,  msg) ;
        String redisKey = (String) msg.getParam(REDIS_KEY);
        Object redisValue = msg.getParam(REDIS_VALUE);
        Object redisField = msg.getParam(REDIS_FIELD);
        Object result = null;
        switch (redisAction) {
            case REDIS_HINCRBY:
                result = hincrby(redisKey,(String)redisField,(Long) redisValue);
                break;
            default:               ;
        }
        return  createMsg().setParam(DB_R_RESULT,result) ;
    }
    @Override
    protected TLMsg query(Object fromWho, TLMsg msg) {
        String key = (String) msg.getParam(REDIS_KEY);
        Object result;
        if(msg.getParam(REDIS_FIELD)!=null){
            if(msg.getParam(REDIS_FIELD) instanceof  String)
                result =hget(getKey(key), (String) msg.getParam(REDIS_FIELD)) ;
            else
              result = hmget(getKey(key),(String[]) msg.getParam(REDIS_FIELD));
        }
        else
          result = hgetall(getKey(key)) ;
        return createMsg().setParam(REDIS_RESULT,result);

    }
    @Override
    protected TLMsg delete(Object fromWho, TLMsg msg) {
        String key = (String) msg.getParam(REDIS_KEY);
        Object result = null;
        if(msg.getParam(REDIS_FIELD)!=null){
            if(msg.getParam(REDIS_FIELD) instanceof  String)
                result =hdel(getKey(key), (String) msg.getParam(REDIS_FIELD)) ;
            else
                result = hdel(getKey(key),(String[]) msg.getParam(REDIS_FIELD));
        }
        else
            result =del(getKey(key));
        return createMsg().setParam(REDIS_RESULT,result);
    }

    /**
     * 通过key给field设置指定的值,如果key不存在则先创建,如果field已经存在,返回0
     *
     * @param key
     * @param field
     * @param value
     * @return
     */
    public Long hsetnx(String key, String field, String value) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
         long result = jedis.hsetnx(key, field, value);
        if(expire > 0)
            jedis.expire(key,expire);
        jedis.close();
        return result ;
    }

    /**
     * 通过key给field设置指定的值,如果key不存在,则先创建
     *
     * @param key
     * @param field
     * @param value
     * @return
     */
    public Long hset(String key, String field, String value) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        long result = jedis.hset(key, field, value);
        if(expire > 0)
            jedis.expire(key,expire);
        jedis.close();
        return result ;
    }

    /**
     * 通过key同时设置 hash的多个field
     *
     * @param key
     * @param hash
     * @return
     */
    public String hmset(String key, Map<String, String> hash) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        String result = jedis.hmset(key, hash);
        if(expire > 0)
            jedis.expire(key,expire);
        jedis.close();
        return result ;
    }

    /**
     * 通过key 和 field 获取指定的 value
     *
     * @param key
     * @param field
     * @return
     */
    public String hget(String key, String field) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        String result = jedis.hget(key, field);
        jedis.close();
        return result ;
    }
    /**
     * 通过key 和 fields 获取指定的value 如果没有对应的value则返回null
     *
     * @param key
     * @param fields 可以是 一个String 也可以是 String数组
     * @return
     */
    public List<String> hmget(String key, String... fields) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        List result = jedis.hmget(key, fields);
        jedis.close();
        return result ;
    }
    /**
     * 通过key 和 fields 获取指定的value 如果没有对应的value则返回null
     *
     * @param key
     * @param fields 可以是 一个String 也可以是 String数组
     * @return
     */
    public  HashMap<String,String> hmgetToMap(String key,Jedis jedis, String... fields) {
        key=prefix+key;
        HashMap<String,String> result = new HashMap<>();
        List<String> resultList = jedis.hmget(key, fields);
        if(resultList.isEmpty())
            return null;
        int fieldsLenght = (fields).length;
        for(int i = 0 ;i < fieldsLenght ;i++)
        {
            String value =resultList.get(i);
            result.put(fields[i],value);
        }
        return result ;
    }
    /**
     * 批量读取数据
     *
     * @param keys  批量keys
     * @param preFix key 的前缀
     * @return
     */
    public   List hmgetToMap( List<String> keys,String preFix )
    {

        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        Pipeline pipelined = jedis.pipelined();
        try {
            for(String key : keys){
                key =(preFix ==null)?key :preFix+key;
                key =prefix +key ;
                pipelined.hgetAll(key);
            }
            return pipelined.syncAndReturnAll();
        } finally {
            pipelined.close();
            jedis.close();
        }
    }
    public   List hmgetToMapOrderByKeys( List<String> keys,String preFix )
    {

        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        Pipeline pipelined = jedis.pipelined();
        try {
            Map<String,Response<Map<String,String>>> responses = new HashMap<String,Response<Map<String,String>>>(keys.size());
            for(String key : keys){
                key =(preFix ==null)?key :preFix+key;
                key =prefix +key ;
                responses.put(key, pipelined.hgetAll(key));
            }
            pipelined.sync();
            List<Map> result =new ArrayList<>();
            for(String key : keys)
            {
                key =(preFix ==null)?key :preFix+key;
                key =prefix +key;
                Response<Map<String,String>>response =responses.get(key);
                if(response ==null)
                    continue;
                result.add(response.get());
            }
            return result ;
        } finally {
            pipelined.close();
            jedis.close();
        }
    }
    /**
     * 通过key给指定的field的value加上给定的值
     *
     * @param key
     * @param field
     * @param value
     * @return
     */
    public Long hincrby(String key, String field, Long value) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        Long result = jedis.hincrBy(key, field, value);
        if(expire > 0)
            jedis.expire(key,expire);
        jedis.close();
        return result ;
    }

    /**
     * 通过key和field判断是否有指定的value存在
     *
     * @param key
     * @param field
     * @return
     */
    public Boolean hexists(String key, String field) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Boolean result = jedis.hexists(key, field);
        jedis.close();
        return result ;
    }

    /**
     * 通过key返回field的数量
     *
     * @param key
     * @return
     */
    public Long hlen(String key) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Long result = jedis.hlen(key);
        jedis.close();
        return result ;
    }

    /**
     * 通过key 删除指定的 field
     *
     * @param key
     * @param fields 可以是 一个 field 也可以是 一个数组
     * @return
     */
    public Long hdel(String key, String... fields) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        Long result = jedis.hdel(key, fields);
        jedis.close();
        return result ;
    }

    /**
     * 通过key返回所有的field
     *
     * @param key
     * @return
     */
    public Set<String> hkeys(String key) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Set result = jedis.hkeys(key);
        jedis.close();
        return result ;
    }

    /**
     * 通过key返回所有和key有关的value
     *
     * @param key
     * @return
     */
    public List<String> hvals(String key) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        List result = jedis.hvals(key);
        jedis.close();
        return result ;
    }

    /**
     * 通过key获取所有的field和value
     *
     * @param key
     * @return
     */
    public Map<String, String> hgetall(String key) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Map result = jedis.hgetAll(key);
        jedis.close();
        return result ;
    }
    /**
     * 通过key获取所有的field和value
     *
     * @param key
     * @return
     */
    public Map<String, String> hgetall(String key,Jedis jedis ) {
        key=prefix+key;
        Map result = jedis.hgetAll(key);
        return result ;
    }
}
