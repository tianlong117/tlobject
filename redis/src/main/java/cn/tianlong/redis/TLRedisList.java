package cn.tianlong.tlobject.redis;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import com.google.gson.Gson;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.ListPosition ;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLRedisList extends TLRedisTable {

    public TLRedisList(String name, TLObjectFactory modulefactory) {
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
        Object result = null;
       if(msg.getParam(REDIS_DATAINDEX) !=null)
       {
           Long index  = (Long) msg.getParam(REDIS_DATAINDEX);
           result = lset(key,index, (String) values);
           return  createMsg().setParam(REDIS_RESULT,result)   ;
       }
        if(msg.getParam(REDIS_INSERT) !=null)
        {
            ListPosition where = (ListPosition) msg.getParam(REDIS_INSERT);
            result = linsert(key,where, (String) msg.getParam(REDIS_PIVOT),(String)values);
            return  createMsg().setParam(REDIS_RESULT,result)   ;
        }
        String position = (String) msg.getParam(DB_P_POSITION);
       if(position ==null )
           position = DB_P_POSITION_TOP;
       if(position.equals(DB_P_POSITION_TOP))
       {
           if(values instanceof List)
               result = lpush(key, (List) values);
           else  if(values instanceof String[])
               result = lpush(key, (String) values);
       }
       else
       {
           if(values instanceof List)
               result =rpush(key, (List) values);
           else  if(values instanceof String[])
               result = rpush(key, (String) values);
       }
       return  createMsg().setParam(REDIS_RESULT,result)   ;
    }

    @Override
    protected TLMsg update(Object fromWho, TLMsg msg) {
       return null;
    }
    protected TLMsg query(Object fromWho, TLMsg msg) {
        String key = (String) msg.getParam(REDIS_KEY);
        Object result;
        if(msg.getParam(REDIS_DATAINDEX)!=null){
            result = lindex(key, (Long) msg.getParam(REDIS_DATAINDEX));
            return createMsg().setParam(REDIS_RESULT,result);
        }
        Long start = (Long) msg.getParam(REDIS_START);
        Long end;
        if(msg.getParam(REDIS_END) ==null)
            end =-1L;
        else
            end = (Long) msg.getParam(REDIS_END);
        result = lrange(key,start,end);
        return createMsg().setParam(REDIS_RESULT,result);

    }
    protected TLMsg delete(Object fromWho, TLMsg msg) {
        String key = (String) msg.getParam(REDIS_KEY);
        String value = (String) msg.getParam(REDIS_VALUE);
        Object result = null;
        if(value !=null)
        {
            Long count=0L;
            if(msg.getParam(REDIS_COUNT)!=null)
                count = (Long) msg.getParam(REDIS_COUNT);
            lrem(key,count,value);
            return createMsg().setParam(REDIS_RESULT,result);
        }
        else if(  msg.getParam(DB_P_POSITION)!=null){
            if(  msg.getParam(DB_P_POSITION).equals(DB_P_POSITION_TOP))
                result = lpop(key);
            else
                result =rpop(key);
        }
        else

            return null ;
        return createMsg().setParam(REDIS_RESULT,result);
    }
    public Long lpush(String key, Map value) {
        return lpush(key, new Gson().toJson(value));
    }
    public Long rpush(String key, Map value) {
        return rpush(key, new Gson().toJson(value));
    }
    /**
     * 通过key向list头部添加字符串
     *
     * @param key
     * @param value 为list类型
     * @return 返回list的value个数
     */
    public Long lpush(String key, List value) {
        return lpush(key, listToArray(value));
    }
    /**
     * 通过key向list尾部添加字符串
     *
     * @param key
     * @param value 为list类型
     * @return 返回list的value个数
     */
    public Long rpush(String key, List value) {
        return rpush(key, listToArray(value));
    }

    protected  String[] listToArray(List value){
        String[] resultArray;
        Object firstUnit =  value.get(0);
        if (firstUnit instanceof String)
            resultArray = (String[])  value.toArray(new String[ value.size()]);
        else
        {
            ArrayList<String> redisdatas = new ArrayList<>();
            for (int i = 0; i <  value.size(); i++) {
                redisdatas.add(new Gson().toJson( value.get(i)));
            }
            resultArray =  redisdatas.toArray(new String[redisdatas.size()]);
        }
        return  resultArray ;
    }
    /**
     * 通过key向list头部添加字符串
     *
     * @param key
     * @param strs 可以是一个string 也可以是string数组
     * @return 返回list的value个数
     */
    public Long lpush(String key, String... strs) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        Long result= jedis.lpush(key, strs);
        if(expire > 0)
            jedis.expire(key,expire);
        jedis.close();
        return result ;
    }

    /**
     * 通过key向list尾部添加字符串
     *
     * @param key
     * @param strs 可以是一个string 也可以是string数组
     * @return 返回list的value个数
     */
    public Long rpush(String key, String... strs) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        Long result =jedis.rpush(key, strs);
        if(expire > 0)
            jedis.expire(key,expire);
        jedis.close();
        return result ;
    }

    /**
     * 通过key在list指定的位置之前或者之后 添加字符串元素
     *
     * @param key
     * @param where LIST_POSITION枚举类型
     * @param pivot list里面的value
     * @param value 添加的value
     * @return
     */
    public Long linsert(String key, ListPosition where,
                        String pivot, String value) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        Long result = jedis.linsert(key, where, pivot, value);
        if(expire > 0)
            jedis.expire(key,expire);
        jedis.close();
        return result ;
    }

    /**
     * 通过key设置list指定下标位置的value
     * 如果下标超过list里面value的个数则报错
     *
     * @param key
     * @param index 从0开始
     * @param value
     * @return 成功返回OK
     */
    public String lset(String key, Long index, String value) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        String result =  jedis.lset(key, index, value);
        if(expire > 0)
            jedis.expire(key,expire);
        jedis.close();
        return result ;
    }

    /**
     * 通过key从对应的list中删除指定的count个 和 value相同的元素
     *
     * @param key
     * @param count 当count为0时删除全部
     * @param value
     * @return 返回被删除的个数
     */
    public Long lrem(String key, long count, String value) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        Long result = jedis.lrem(key, count, value);
        jedis.close();
        return result ;
    }

    /**
     * 通过key保留list中从strat下标开始到end下标结束的value值
     *
     * @param key
     * @param start
     * @param end
     * @return 成功返回OK
     */
    public String ltrim(String key, long start, long end) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        String result = jedis.ltrim(key, start, end);
        jedis.close();
        return result ;
    }

    /**
     * 通过key从list的头部删除一个value,并返回该value
     *
     * @param key
     * @return
     */
    public synchronized String lpop(String key) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        String result =jedis.lpop(key);
        jedis.close();
        return result ;
    }

    /**
     * 通过key从list尾部删除一个value,并返回该元素
     *
     * @param key
     * @return
     */
    synchronized public String rpop(String key) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        String result =  jedis.rpop(key);
        jedis.close();
        return result ;
    }

    /**
     * 通过key从一个list的尾部删除一个value并添加到另一个list的头部,并返回该value
     * 如果第一个list为空或者不存在则返回null
     *
     * @param srckey
     * @param dstkey
     * @return
     */
    public String rpoplpush(String srckey, String dstkey) {
        srckey=prefix+srckey;
        dstkey=prefix+dstkey;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        String result =jedis.rpoplpush(srckey, dstkey);
        jedis.close();
        return result ;
    }

    /**
     * 通过key获取list中指定下标位置的value
     *
     * @param key
     * @param index
     * @return 如果没有返回null
     */
    public String lindex(String key, long index) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        String result = jedis.lindex(key, index);
        jedis.close();
        return result ;
    }

    /**
     * 通过key返回list的长度
     *
     * @param key
     * @return
     */
    public Long llen(String key) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Long result = jedis.llen(key);
        jedis.close();
        return result ;
    }

    /**
     * 通过key获取list指定下标位置的value
     * 如果start 为 0 end 为 -1 则返回全部的list中的value
     *
     * @param key
     * @param start
     * @param end
     * @return
     */
    public List<String> lrange(String key, long start, long end) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        List<String> result = jedis.lrange(key, start, end);
        jedis.close();
        return result ;
    }

}
