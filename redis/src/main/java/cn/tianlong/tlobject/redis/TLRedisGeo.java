package cn.tianlong.tlobject.redis;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import redis.clients.jedis.GeoCoordinate;
import redis.clients.jedis.GeoRadiusResponse;
import redis.clients.jedis.GeoUnit;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.GeoRadiusParam;

import java.util.List;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLRedisGeo extends TLRedisZSet {

    public TLRedisGeo(String name, TLObjectFactory modulefactory) {
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
       Object result =geoadd( key, (long)msg.getParam("longitude"),(long)msg.getParam("latitude") ,(String)msg.getParam("member"));
       return  createMsg().setParam(REDIS_RESULT,result)   ;
    }

    @Override
    protected TLMsg update(Object fromWho, TLMsg msg) {
       return null;
    }
    protected TLMsg query(Object fromWho, TLMsg msg) {
        String key = (String) msg.getParam(REDIS_KEY);
        return null;
    }

    protected TLMsg delete(Object fromWho, TLMsg msg) {
        String key = (String) msg.getParam(REDIS_KEY);
        return null;
    }

    /**
     * 将指定的地理空间位置（纬度、经度、名称）添加到指定的key中。
     *添加到sorted set元素的数目，但不包括已更新score的元素。
     * @param key
     * @param longitude 经度
     * @param latitude 纬度
     * @return 添加成功的个数
     */
    public Long geoadd(String key, double longitude,double latitude,String member) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        Long result =  jedis.geoadd(key, longitude,latitude,member);
        if(expire > 0)
            jedis.expire(key,expire);
        jedis.close();
        return result ;
    }

    /**
     * 返回两个给定位置之间的距离。如果两个位置之间的其中一个不存在， 那么命令返回空值。指定单位的参数 unit 必须是以下单位的其中一个：
     *
     * m 表示单位为米。
     * km 表示单位为千米。
     * mi 表示单位为英里。
     * ft 表示单位为英尺。
     * @param key
     * @param member1
     * @param member2
     * @param unit
     * @return
     */
    public Double geodist(String key, String member1, String member2, GeoUnit unit) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        Double result =  jedis.geodist(key,member1,member2,unit);
        if(expire > 0)
            jedis.expire(key,expire);
        jedis.close();
        return result ;
    }

    /**
     * 命令描述：从key里返回所有给定位置元素的位置（经度和纬度）。
     * @param key
     * @param member
     * @return GEOPOS 命令返回一个数组， 数组中的每个项都由两个元素组成： 第一个元素为给定位置元素的经度，
     *      * 而第二个元素则为给定位置元素的纬度。当给定的位置元素不存在时， 对应的数组项为空值。
     */
    public List<GeoCoordinate> geopos(String key, String ... member ) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        List<GeoCoordinate> result =  jedis.geopos(key,member);
        if(expire > 0)
            jedis.expire(key,expire);
        jedis.close();
        return result ;
    }

    /**
     * 命令描述：返回一个或多个位置元素的 Geohash 表示。通常使用表示位置的元素使用不同的技术，使用Geohash位置52点整数编码。
     * 由于编码和解码过程中所使用的初始最小和最大坐标不同，编码的编码也不同于标准。此命令返回一个标准的Geohash
     * @param key
     * @param member
     * @return 一个数组， 数组的每个项都是一个 geohash 。 命令返回的 geohash 的位置与用户给定的位置元素的位置一一对应
     */
    public  List<String>  geohash(String key, String ... member ) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        List<String> result =  jedis.geohash(key,member);
        if(expire > 0)
            jedis.expire(key,expire);
        jedis.close();
        return result ;
    }

    /**
     *
     * @param key
     * @param longitude
     * @param latitude
     * @param radius
     * @param unit
     * @return
     */
    public  List<GeoRadiusResponse>  georadius(String key, double longitude,double latitude,Double radius,GeoUnit unit ) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        List<GeoRadiusResponse> result =  jedis.georadius(key,longitude,latitude,radius,unit);
        if(expire > 0)
            jedis.expire(key,expire);
        jedis.close();
        return result ;
    }
    public  List<GeoRadiusResponse>  georadius(String key, double longitude,double latitude,Double radius,GeoUnit unit , GeoRadiusParam param) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        List<GeoRadiusResponse> result =  jedis.georadius(key,longitude,latitude,radius,unit,param);
        if(expire > 0)
            jedis.expire(key,expire);
        jedis.close();
        return result ;
    }
    public  List<GeoRadiusResponse>  georadiusByMember(String key, String member,Double radius,GeoUnit unit , GeoRadiusParam param) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        List<GeoRadiusResponse> result =  jedis.georadiusByMember(key,member,radius,unit,param);
        if(expire > 0)
            jedis.expire(key,expire);
        jedis.close();
        return result ;
    }
}
