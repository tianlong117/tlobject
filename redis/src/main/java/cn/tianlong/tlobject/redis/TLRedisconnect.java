package cn.tianlong.tlobject.redis;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLBaseConnectorInterface;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import java.util.HashMap;


/**
 * 创建日期：2018/3/21 on 8:48
 * 描述:
 * 作者:tianlong
 */

public class TLRedisconnect extends TLBaseModule implements TLBaseConnectorInterface {
    private static int MAX_WAIT = 3 * 1000;
    //超时时间
    private static int TIMEOUT = 3 * 1000;
    private  JedisPool jedisPool = null;
    public TLRedisconnect() {
        super();
    }
    public TLRedisconnect(String name ) {
        super(name);
    }
    public TLRedisconnect(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected TLBaseModule init() {
        return this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "init":
                init( fromWho,  msg);
                break;
            case DB_GETCONN:
                returnMsg=getConnection( fromWho,  msg);
                break;
            case "getDataSource":
                returnMsg=createMsg().setParam("dataSource",jedisPool);
                break;
            default:
                returnMsg=null;
        }
        return returnMsg;
    }
    protected void init(Object fromWho, TLMsg msg) {

        params= (HashMap) msg.getParam("params");
        initialPool() ;
    }
    private  void initialPool() {
        //Redis服务器IP
        String HOST = params.get("host");
        //Redis的端口号
        int PORT = NumberUtils.toInt(params.get("port"), 6379);
        //访问密码
        String AUTH = params.get("password");

        try {
            JedisPoolConfig config = new JedisPoolConfig();
            //最大连接数，如果赋值为-1，则表示不限制；如果pool已经分配了maxActive个jedis实例，则此时pool的状态为exhausted(耗尽)。
            config.setMaxTotal(NumberUtils.toInt(params.get("maxTotal"), 400));
            //最大空闲数，控制一个pool最多有多少个状态为idle(空闲的)的jedis实例，默认值也是8。
            config.setMaxIdle(NumberUtils.toInt(params.get("maxIdle"), 50));
            //最小空闲数
            config.setMinIdle(NumberUtils.toInt(params.get("minIdle"), 10));
            //是否在从池中取出连接前进行检验，如果检验失败，则从池中去除连接并尝试取出另一个
            config.setTestOnBorrow(true);
            //在return给pool时，是否提前进行validate操作
            config.setTestOnReturn(true);
            //在空闲时检查有效性，默认false
            config.setTestWhileIdle(true);
            //表示一个对象至少停留在idle状态的最短时间，然后才能被idle object evitor扫描并驱逐；
            //这一项只有在timeBetweenEvictionRunsMillis大于0时才有意义
            config.setMinEvictableIdleTimeMillis(30000);
            //表示idle object evitor两次扫描之间要sleep的毫秒数
            config.setTimeBetweenEvictionRunsMillis(60000);
            //表示idle object evitor每次扫描的最多的对象数
            config.setNumTestsPerEvictionRun(1000);
            //等待可用连接的最大时间，单位毫秒，默认值为-1，表示永不超时。如果超过等待时间，则直接抛出JedisConnectionException；
            config.setMaxWaitMillis(MAX_WAIT);

            if (StringUtils.isNotBlank(AUTH)) {
                jedisPool = new JedisPool(config, HOST, PORT, TIMEOUT, AUTH);
            } else {
                jedisPool = new JedisPool(config, HOST, PORT, TIMEOUT);
            }
        } catch (Exception e) {
            if(jedisPool != null){
                jedisPool.close();
            }
            exception(this, null,e);
        }
    }
    private TLMsg getConnection(Object fromWho, TLMsg msg) {
        Jedis  conn= (Jedis) connect();
        return msg.setParam(DB_R_CONN,conn);
    }

    @Override
    public Object connect() {
        Jedis jedis = null;
        if (jedisPool != null)
            try {
                jedis = jedisPool.getResource();
            }catch (Exception e) {
               return null ;
            }
        return jedis;
    }

    @Override
    public void close(Object conn) {
        ((Jedis)conn).close();
    }
}
