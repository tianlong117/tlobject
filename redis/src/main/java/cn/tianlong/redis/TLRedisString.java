package cn.tianlong.tlobject.redis;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import redis.clients.jedis.Jedis;

import java.util.List;


/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLRedisString extends TLRedisTable {

    public TLRedisString(String name, TLObjectFactory modulefactory) {
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
       Object result = set(key, (String) values);
       return  createMsg().setParam(REDIS_RESULT,result)   ;
    }
    @Override
    protected TLMsg update(Object fromWho, TLMsg msg) {
      return   insert( fromWho, msg);
    }
    protected TLMsg query(Object fromWho, TLMsg msg) {
        String key = (String) msg.getParam(REDIS_KEY);
        Object result = get(key) ;
        return createMsg().setParam(REDIS_RESULT,result);
    }

    protected TLMsg delete(Object fromWho, TLMsg msg) {
        String key = (String) msg.getParam(REDIS_KEY);
        Object result=del(key);
        return createMsg().setParam(REDIS_RESULT,result);
    }

    /**
     * 设置key的值为value
     *
     * @param key
     * @param value
     * @return
     */
    public String set(String key, String value) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        String result = jedis.set(key, value);
        if(expire > 0)
            jedis.expire(key,expire);
        jedis.close();
        return result ;
    }
    /**
     * 获取指定key的值,如果key不存在返回null，如果该Key存储的不是字符串，会抛出一个错误
     *
     * @param key
     * @return
     */
    public String get(String key) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        String value = null;
        value = jedis.get(key);
        jedis.close();
        return value ;
    }
    /**
     * 通过key向指定的value值追加值
     *
     * @param key
     * @param str
     * @return
     */
    public Long append(String key, String str) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Long result = jedis.append(key, str);
        jedis.close();
        return result ;
    }
    /**
     * 设置key value,如果key已经存在则返回0
     *
     * @param key
     * @param value
     * @return
     */
    public Long setnx(String key, String value) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Long result = jedis.setnx(key, value);
        jedis.close();
        return result ;
    }
    /**
     * 设置key value并指定这个键值的有效期
     *
     * @param key
     * @param seconds
     * @param value
     * @return
     */
    public String setex(String key, int seconds, String value) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        String result = jedis.setex(key, seconds, value);
        jedis.close();
        return result ;
    }
    /**
     * 通过key 和offset 从指定的位置开始将原先value替换
     *
     * @param key
     * @param offset
     * @param str
     * @return
     */
    public Long setrange(String key, int offset, String str) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Long result = jedis.setrange(key, offset, str);
        jedis.close();
        return result ;
    }
    /**
     * 通过批量的key获取批量的value
     *
     * @param keys
     * @return
     */
    public List<String> mget(String... keys) {
        for(int i=0 ; i<keys.length; i++)
            keys[i]=prefix+keys[i];
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        List result = jedis.mget(keys);
        jedis.close();
        return result ;
    }
    /**
     * 批量的设置key:value,也可以一个
     *
     * @param keysValues
     * @return
     */
    public String mset(String... keysValues) {
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        String result = jedis.mset(keysValues);
        jedis.close();
        return result ;
    }

    /**
     * 批量的设置key:value,可以一个,如果key已经存在则会失败,操作会回滚
     *
     * @param keysValues
     * @return
     */
    public Long msetnx(String... keysValues) {
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Long result = jedis.msetnx(keysValues);
        jedis.close();
        return result ;
    }

    /**
     * 设置key的值,并返回一个旧值
     *
     * @param key
     * @param value
     * @return
     */
    public String getSet(String key, String value) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        String result = jedis.getSet(key, value);
        jedis.close();
        return result ;
    }

    /**
     * 通过下标 和key 获取指定下标位置的 value
     *
     * @param key
     * @param startOffset
     * @param endOffset
     * @return
     */
    public String getrange(String key, int startOffset, int endOffset) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        String result = jedis.getrange(key, startOffset, endOffset);
        jedis.close();
        return result ;
    }

    /**
     * 通过key 对value进行加值+1操作,当value不是int类型时会返回错误,当key不存在是则value为1
     *
     * @param key
     * @return
     */
    public Long incr(String key) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Long result = jedis.incr(key);
        jedis.close();
        return result ;
    }

    /**
     * 通过key给指定的value加值,如果key不存在,则这是value为该值
     *
     * @param key
     * @param integer
     * @return
     */
    public Long incrBy(String key, long integer) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Long result = jedis.incrBy(key, integer);
        jedis.close();
        return result ;
    }

    /**
     * 对key的值做减减操作,如果key不存在,则设置key为-1
     *
     * @param key
     * @return
     */
    public Long decr(String key) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Long result = jedis.decr(key);
        jedis.close();
        return result ;
    }

    /**
     * 减去指定的值
     *
     * @param key
     * @param integer
     * @return
     */
    public Long decrBy(String key, long integer) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Long result =  jedis.decrBy(key, integer);
        jedis.close();
        return result ;
    }

    /**
     * 通过key获取value值的长度
     *
     * @param key
     * @return
     */
    public Long strLen(String key) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Long result = jedis.strlen(key);
        jedis.close();
        return result ;
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
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Long result = jedis.hsetnx(key, field, value);
        jedis.close();
        return result ;
    }

}
