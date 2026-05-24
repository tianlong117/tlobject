package cn.tianlong.tlobject.redis;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import redis.clients.jedis.Jedis;

import java.util.List;
import java.util.Set;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLRedisSet extends TLRedisTable {

    public TLRedisSet(String name, TLObjectFactory modulefactory) {
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
       Object result = sadd(key, (String) values);
       return  createMsg().setParam(REDIS_RESULT,result)   ;
    }

    @Override
    protected TLMsg update(Object fromWho, TLMsg msg) {
       return null;
    }
    protected TLMsg query(Object fromWho, TLMsg msg) {
        String key = (String) msg.getParam(REDIS_KEY);
        Object result;
        if(msg.getParam(REDIS_VALUE)!=null){
                result =sismember(key, (String) msg.getParam(REDIS_VALUE)) ;
            return createMsg().setParam(REDIS_RESULT,result);
        }
        result = smembers(key) ;
        return createMsg().setParam(REDIS_RESULT,result);

    }

    protected TLMsg delete(Object fromWho, TLMsg msg) {
        String key = (String) msg.getParam(REDIS_KEY);
        Object result;
        if(msg.getParam(REDIS_VALUE)!=null){
               result =srem(key, (String) msg.getParam(REDIS_VALUE)) ;
              return createMsg().setParam(REDIS_RESULT,result);
        }
        else
            return null;
    }

    /**
     * 通过key向指定的set中添加value
     *
     * @param key
     * @param members 可以是一个String 也可以是一个String数组
     * @return 添加成功的个数
     */
    public Long sadd(String key, String... members) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        Long result =  jedis.sadd(key, members);
        if(expire > 0)
            jedis.expire(key,expire);
        jedis.close();
        return result ;
    }
    /**
     * 通过key删除set中对应的value值
     *
     * @param key
     * @param members 可以是一个String 也可以是一个String数组
     * @return 删除的个数
     */
    public Long srem(String key, String... members) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        Long result =jedis.srem(key, members);
        jedis.close();
        return result ;
    }

    /**
     * 通过key随机删除一个set中的value并返回该值
     *
     * @param key
     * @return
     */
    public String spop(String key) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        String result =   jedis.spop(key);
        jedis.close();
        return result ;
    }

    /**
     * 通过key获取set中的差集
     * 以第一个set为标准
     *
     * @param keys 可以 是一个string 则返回set中所有的value 也可以是string数组
     * @return
     */
    public Set<String> sdiff(String... keys) {
        for(int i=0 ; i<keys.length; i++)
            keys[i]=prefix+keys[i];
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Set result =   jedis.sdiff(keys);
        jedis.close();
        return result ;
    }

    /**
     * 通过key获取set中的差集并存入到另一个key中
     * 以第一个set为标准
     *
     * @param dstkey 差集存入的key
     * @param keys   可以 是一个string 则返回set中所有的value 也可以是string数组
     * @return
     */
    public Long sdiffstore(String dstkey, String... keys) {
        for(int i=0 ; i<keys.length; i++)
            keys[i]=prefix+keys[i];
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        Long result =  jedis.sdiffstore(dstkey, keys);
        jedis.close();
        return result ;
    }

    /**
     * 通过key获取指定set中的交集
     *
     * @param keys 可以 是一个string 也可以是一个string数组
     * @return
     */
    public Set<String> sinter(String... keys) {
        for(int i=0 ; i<keys.length; i++)
            keys[i]=prefix+keys[i];
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Set result =  jedis.sinter(keys);
        jedis.close();
        return result ;
    }

    /**
     * 通过key获取指定set中的交集 并将结果存入新的set中
     *
     * @param dstkey
     * @param keys   可以 是一个string 也可以是一个string数组
     * @return
     */
    public Long sinterstore(String dstkey, String... keys) {
        for(int i=0 ; i<keys.length; i++)
            keys[i]=prefix+keys[i];
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        Long result = jedis.sinterstore(dstkey, keys);
        jedis.close();
        return result ;
    }

    /**
     * 通过key返回所有set的并集
     *
     * @param keys 可以 是一个string 也可以是一个string数组
     * @return
     */
    public Set<String> sunion(String... keys) {
        for(int i=0 ; i<keys.length; i++)
            keys[i]=prefix+keys[i];
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Set result =jedis.sunion(keys);
        jedis.close();
        return result ;
    }

    /**
     * 通过key返回所有set的并集,并存入到新的set中
     *
     * @param dstkey
     * @param keys   可以 是一个string 也可以是一个string数组
     * @return
     */
    public Long sunionstore(String dstkey, String... keys) {
        for(int i=0 ; i<keys.length; i++)
            keys[i]=prefix+keys[i];
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        Long result =jedis.sunionstore(dstkey, keys);
        jedis.close();
        return result ;
    }

    /**
     * 通过key将set中的value移除并添加到第二个set中
     *
     * @param srckey 需要移除的
     * @param dstkey 添加的
     * @param member set中的value
     * @return
     */
    public Long smove(String srckey, String dstkey, String member) {
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        Long result =jedis.smove(prefix+srckey, prefix+dstkey, member);
        jedis.close();
        return result ;
    }

    /**
     * 通过key获取set中value的个数
     *
     * @param key
     * @return
     */
    public Long scard(String key) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Long result = jedis.scard(key);
        jedis.close();
        return result ;
    }

    /**
     * 通过key判断value是否是set中的元素
     *
     * @param key
     * @param member
     * @return
     */
    public Boolean sismember(String key, String member) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Boolean result = jedis.sismember(key, member);
        jedis.close();
        return result ;
    }

    /**
     * 通过key获取set中随机的value,不删除元素
     *
     * @param key
     * @return
     */
    public String srandmember(String key) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        String result = jedis.srandmember(key);
        jedis.close();
        return result ;
    }
    /**
     * 通过key获取set中随机的value,不删除元素
     *如果 count 为正数，且小于集合基数，那么命令返回一个包含 count 个元素的数组，数组中的元素各不相同。如果 count 大于等于集合基数，那么返回整个集合。
     * 如果 count 为负数，那么命令返回一个数组，数组中的元素可能会重复出现多次，而数组的长度为 count 的绝对值。
     * @param key
     * @return
     */
    public List<String> srandmember(String key, int count) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        List<String>  result = jedis.srandmember( key,  count);
        jedis.close();
        return result ;
    }
    /**
     * 通过key获取set中所有的value
     *
     * @param key
     * @return
     */
    public Set<String> smembers(String key) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Set result = jedis.smembers(key);
        jedis.close();
        return result ;
    }


}
