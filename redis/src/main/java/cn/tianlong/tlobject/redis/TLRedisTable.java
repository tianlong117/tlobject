package cn.tianlong.tlobject.redis;


import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLBaseDataUnit;
import cn.tianlong.tlobject.modules.LogLevel;
import redis.clients.jedis.Jedis;
import java.util.Set;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public abstract class TLRedisTable extends TLBaseDataUnit {
    protected int databaseIndex=0;
    protected String prefix="";
    protected int expire =0;
    public TLRedisTable() {
        super();
    }
    public TLRedisTable(String name ) {
        super(name);
    }
    public TLRedisTable(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);

    }
    @Override
    protected void initProperty()  {
        if(params!=null && params.get("prefix")!=null)
            prefix= params.get("prefix");
        else
            prefix = name+"_" ;
        if(params!=null && params.get("databaseIndex")!=null)
            databaseIndex= Integer.parseInt(params.get("databaseIndex"));
        if(params!=null && params.get("databaseIndex")!=null)
            databaseIndex= Integer.parseInt(params.get("databaseIndex"));
        if(params!=null && params.get("expire")!=null)
        {
            String expireStr = params.get("expire");
            String numberStr = expireStr.substring(0,expireStr.length() - 1);
            int number = Integer.parseInt(numberStr);
            String unitStr = expireStr.substring(expireStr.length() - 1).toLowerCase();
            if(unitStr.equals("s"))
               expire= number;
            else if(unitStr.equals("m"))
                expire=number *60 ;
            else if(unitStr.equals("h"))
                expire=number *60 *60 ;
            else if(unitStr.equals("d"))
                expire=number *60 * 60 * 24;
            else
                expire=number ;
        }
        super.initProperty();
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "setConnection":
                setConnection( fromWho,  msg);
                break;
            case "getRedis":
                returnMsg=getRedis( fromWho,  msg);
                break;
            default:
                returnMsg=super.checkMsgAction(fromWho,msg);
        }
        return returnMsg;
    }

    private TLMsg getRedis(Object fromWho, TLMsg msg) {
        String type = (String) msg.getParam(REDIS_DOTYPE);
        Jedis  jedis;
        if(type==null || !type.equals("read"))
            jedis= (Jedis) getConnection(null);
        else
            jedis= (Jedis) getConnection("read");
        return  createMsg().setParam(REDIS_OBJ,jedis);
    }
    public String getPrefix(){
        return prefix;
    }
    public void setPrefix(String prefix){
       this.prefix =prefix;
    }
    public String getKey(String key){
        return prefix+key;
    }
    public Jedis getRedis(String connType){
       return (Jedis) getConnection( connType);
    }
    public Long expire(String key,int time) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        Long result =jedis.expire(key,time);
        jedis.close();
        return result ;
    }
    public Long expireAt(String key, long unixTime) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        Long result =jedis.expireAt(key,unixTime);
        if(jedis ==null)
            return null ;
        jedis.close();
        return result ;
    }

    public Long del(String key) {
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection(null);
        Long result =jedis.del(key);
        jedis.close();
        return result ;
    }
    public Long del(String... key) {
        for(int i=0 ; i<key.length; i++)
            key[i]=prefix+key[i];
        Jedis jedis = (Jedis) getConnection(null);
        if(jedis ==null)
            return null ;
        Long result =jedis.del(key);
        jedis.close();
        return result ;
    }
    public Set<String> keys(String pattern){
        pattern=prefix+pattern;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Set<String> result =jedis.keys(pattern);
        jedis.close();
        return result ;
    }
    public Boolean exists(String key){
        key=prefix+key;
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        boolean result =jedis.exists(key);
        jedis.close();
        return result ;
    }
    public Long exists(String... key) {
        for(int i=0 ; i<key.length; i++)
            key[i]=prefix+key[i];
        Jedis jedis = (Jedis) getConnection("read");
        if(jedis ==null)
            return null ;
        Long result =jedis.exists(key);
        jedis.close();
        return result ;
    }
    @Override
    protected Object getConnection(String connType){
        Object redis =super.getConnection(connType);
        if(redis!=null)
            ((Jedis)redis).select(databaseIndex);
        return redis ;
    }
    public Jedis getConn(String connType){
        return (Jedis) getConnection(connType);
    }

    protected void connClose(Jedis conn){
        if(this.conn==null) {
            try {
                conn.close();
            } catch (Exception e) {
                putLog("connClose",LogLevel.WARN);
            }
        }
        else
            return;
    }
    protected void readconnClose(Jedis readconn){
        if(this.readconn==null) {
            try {
                readconn.close();
            } catch (Exception e) {
                putLog("connClose",LogLevel.WARN);
            }
        }
        else
            return;
    }
}
