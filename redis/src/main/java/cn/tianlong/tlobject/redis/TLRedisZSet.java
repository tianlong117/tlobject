package cn.tianlong.tlobject.redis;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import com.google.gson.Gson;
import redis.clients.jedis.Jedis;

import java.util.Map;
import java.util.Set;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLRedisZSet extends TLRedisTable {

    public TLRedisZSet(String name, TLObjectFactory modulefactory) {
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
       String value = (String) msg.getParam(REDIS_VALUE);
       Long score = (Long) msg.getParam(REDIS_SCORE);
       Object result = zadd(key,score,value);
       return  createMsg().setParam(REDIS_RESULT,result)   ;
    }

    @Override
    protected TLMsg update(Object fromWho, TLMsg msg) {
       return null;
    }
    protected TLMsg query(Object fromWho, TLMsg msg) {
        String key = (String) msg.getParam(REDIS_KEY);
        String max = ( String) msg.getParam(REDIS_MAX);
        String min = ( String) msg.getParam(REDIS_MIN);
        Object result;
        result = zrangebyscore(key,max,min);
        return createMsg().setParam(REDIS_RESULT,result);

    }

    protected TLMsg delete(Object fromWho, TLMsg msg) {
        String key = (String) msg.getParam(REDIS_KEY);
        Object result;
        if(msg.getParam(REDIS_VALUE)!=null){
               result =zrem(key, (String) msg.getParam(REDIS_VALUE)) ;
              return createMsg().setParam(REDIS_RESULT,result);
        }
        else
            return null;
    }

    /**
     * 通过key向zset中添加value,score,其中score就是用来排序的
     * 如果该value已经存在则根据score更新元素
     *
     * @param key
     * @param score
     * @param member
     * @return
     */
    public Long zadd(String key, double score, String member) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        Long result = jedis.zadd(key, score, member);
        if(expire > 0)
            jedis.expire(key,expire);
        jedis.close();
        return result ;
    }
    /**
     *  增加map类型
     * @param key
     * @param score
     * @param value
     * @return
     */
    public Long zadd(String key, double score, Map value ) {

        return zadd(key ,score,new Gson().toJson(value));
    }
    /**
     * 通过key删除在zset中指定的value
     *
     * @param key
     * @param members 可以 是一个string 也可以是一个string数组
     * @return
     */
    public Long zrem(String key, String... members) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        Long result = jedis.zrem(key, members);
        jedis.close();
        return result ;
    }
    /**
     * 通过key增加该zset中value的score的值
     *
     * @param key
     * @param score
     * @param member
     * @return
     */
    public Double zincrby(String key, double score, String member) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        Double result = jedis.zincrby(key, score, member);
        jedis.close();
        return result ;
    }

    /**
     * 通过key返回zset中value的排名
     * 下标从小到大排序
     *
     * @param key
     * @param member
     * @return
     */
    public Long zrank(String key, String member) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        Long result =  jedis.zrank(key, member);
        jedis.close();
        return result ;
    }

    /**
     * 通过key返回zset中value的排名
     * 下标从大到小排序
     *
     * @param key
     * @param member
     * @return
     */
    public Long zrevrank(String key, String member) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        Long result = jedis.zrevrank(key, member);
        jedis.close();
        return result ;
    }

    /**
     * 通过key将获取score从start到end中zset的value
     * socre从大到小排序
     * 当start为0 end为-1时返回全部
     *
     * @param key
     * @param start
     * @param end
     * @return
     */
    public Set<String> zrevrange(String key, long start, long end) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Set result = jedis.zrevrange(key, start, end);
        jedis.close();
        return result ;
    }
    /**
     * 通过key返回指定score内zset中的value
     *
     * @param key
     * @param start
     * @param stop
     * @return
     */
    public Set<String> zrange(String key, Long start, Long stop ) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Set result = jedis.zrange(key, start, stop);
        jedis.close();
        return result ;
    }
    /**
     * 通过key返回指定score内zset中的value
     *
     * @param key
     * @param max
     * @param min
     * @return
     */
    public Set<String> zrangebyscore(String key, String min, String max) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        Set result = jedis.zrangeByScore(key, min, max);
        jedis.close();
        return result ;
    }

    /**
     * 通过key返回指定score内zset中的value
     *
     * @param key
     * @param max
     * @param min
     * @return
     */
    public Set<String> zrangeByScore(String key, double min, double max) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if (jedis ==null)
            return null ;
        Set result = jedis.zrangeByScore(key, min, max);
        jedis.close();
        return result ;
    }
    public Set<String> zrevrangeByScore(String key, String max, String min) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if (jedis ==null)
            return null ;
        Set result = jedis.zrevrangeByScore(key,  min,  max);
        jedis.close();
        return result ;
    }
    public Set<String> zrevrangeByScore(String key, double min, double max, int offset, int count) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if (jedis ==null)
            return null ;
        Set result = jedis.zrevrangeByScore(key,  min,  max, offset,  count);
        jedis.close();
        return result ;
    }
    public Set<String> zrangeByScore(String key, String min, String max, int offset, int count) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if (jedis ==null)
            return null ;
        Set result = jedis.zrangeByScore(key,  min,  max, offset,  count);
        jedis.close();
        return result ;
    }
    public Set<String> zrevrangeByScore(String key, String min, String max, int offset, int count) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if (jedis ==null)
            return null ;
        Set result = jedis.zrevrangeByScore(key,  min,  max, offset,  count);
        jedis.close();
        return result ;
    }
    public Set<String> zrangeByLex(String key, String min, String max, int offset, int count) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if (jedis ==null)
            return null ;
        Set result = jedis.zrangeByLex(key, min,max,offset,count);
        jedis.close();
        return result ;
    }
    public Set<String> zrangeByLex(String key, String min, String max) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if (jedis ==null)
            return null ;
        Set result = jedis.zrangeByLex(key, min,max);
        jedis.close();
        return result ;
    }
    public Set<String> zrevrangeByLex(String key, String max,String min ) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if (jedis ==null)
            return null ;
        Set result = jedis.zrevrangeByLex(key, max,min);
        jedis.close();
        return result ;
    }
    public Set<String> zrevrangeByLex(String key, String max ,String min, int offset, int count ) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if (jedis ==null)
            return null ;
        Set result = jedis.zrevrangeByLex(key, max,min,offset,count);
        jedis.close();
        return result ;
    }
    /**
     * 返回指定区间内zset中value的数量
     *
     * @param key
     * @param min
     * @param max
     * @return
     */
    public Long zcount(String key, String min, String max) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if (jedis ==null)
            return null ;
        Long result = jedis.zcount(key, min, max);
        jedis.close();
        return result ;
    }

    /**
     * 通过key返回zset中的value个数
     *
     * @param key
     * @return
     */
    public Long zcard(String key) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if (jedis ==null)
            return null ;
        Long result = jedis.zcard(key);
        jedis.close();
        return result ;
    }

    /**
     * 通过key获取zset中value的score值
     *
     * @param key
     * @param member
     * @return
     */
    public Double zscore(String key, String member) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if (jedis ==null)
            return null ;
        Double result = jedis.zscore(key, member);
        jedis.close();
        return result ;
    }

    /**
     * 通过key删除给定区间内的元素
     *
     * @param key
     * @param start
     * @param end
     * @return
     */
    public Long zremrangeByRank(String key, long start, long end) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if (jedis ==null)
            return null ;
        Long result =jedis.zremrangeByRank(key, start, end);
        jedis.close();
        return result ;
    }

    /**
     * 通过key删除指定score内的元素
     *
     * @param key
     * @param start
     * @param end
     * @return
     */
    public Long zremrangeByScore(String key, double start, double end) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if (jedis ==null)
            return null ;
        Long result = jedis.zremrangeByScore(key, start, end);
        jedis.close();
        return result ;
    }

}
