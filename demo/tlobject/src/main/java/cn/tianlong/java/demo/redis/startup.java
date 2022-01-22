package cn.tianlong.java.demo.redis;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLDataBase;
import cn.tianlong.tlobject.modules.TLAppStartUp;
import cn.tianlong.tlobject.redis.TLRedisMap;
import cn.tianlong.tlobject.utils.TLDataUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 创建日期：2021/2/1010:29
 * 描述:
 * 作者:tianlong
 */
public class startup extends TLAppStartUp {
    protected TLBaseModule userTable ;
    protected   TLRedisMap redisMap  ;
    public static   TLObjectFactory appFactory ;
    public startup(String name) {
        super( name);
    }
    public static void  main (String[] args ) {
        HashMap<String,String> argsMap = null;
        if(args !=null && args.length >0){
            TLAppStartUp.main(args);
          return;
        }
        startModule (argsMap );
    }
    @Override
    protected TLBaseModule init() {
      userTable = (TLBaseModule) getTable("userTable");
      redisMap = (TLRedisMap) getTable("userByIdInRedis");
      return this ;
    }
    public static TLObjectFactory   startModule (HashMap<String,String> configMap  ) {
        HashMap<String,Object> argsMap =new HashMap<>() ;
        argsMap.put("appName","demo0");
        argsMap.put("configPath",CLASSPATH+"/conf/demo/redis/");
        argsMap.put("factoryConfigFile","moduleFactory_config.xml");
        argsMap.put("configFile","demoappstart.xml");
        if(configMap !=null)
            argsMap.putAll(configMap);
        startup instance = new startup("startup");
        appFactory=  instance.startup(argsMap);
        appFactory.shutdown();
        return appFactory ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "dbToRedis":
                dbToRedis( fromWho,  msg);
                break;
            case "queryRedis":
                queryRedis( fromWho,  msg);
                break;
            default:
                returnMsg=super.checkMsgAction(fromWho,msg);
        }
        return returnMsg;
    }

    private void dbToRedis(Object fromWho, TLMsg msg) {
       String sql = "select * from  [table]  ";
        TLMsg querymsg = createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAPLIST);
        TLMsg  returnMsg = putMsg(userTable, querymsg);
        ArrayList<LinkedHashMap> datasList = (ArrayList<LinkedHashMap>) returnMsg.getListParam(RESULT,null);
        if(datasList ==null || datasList.isEmpty())
        {
            System.out.println("没有数据");
            return ;
        }
        int i=0;
        for (Map<String,Object> value : datasList) {
            i++ ;
            Map<String ,String> redisData =TLDataUtils.mapToStrMap(value);
            String username = (String) value.get("name");
            String result=redisMap.hmset(username,redisData);
            println(i+" redis 插入数据,name:"+username+"->"+result);
        }

    }


    private void queryRedis(Object fromWho, TLMsg msg) {
        String username=msg.getStringParam("username",null);
        Map<String, String> redisdata =redisMap.hgetall(username);
        println("redis 查询结果：");
        printMap(redisdata);
    }

    private Object getTable(String tableName){
        TLMsg tmsg = createMsg().setAction(DB_GETTABLE).setParam(DB_P_TABLENAME, tableName);
        TLMsg returnmsg =putMsg(DEFAULTDATABASE, tmsg);
         return returnmsg.getParam(INSTANCE);
    }
    private void printMap(Map<String,String> map){
        for(String key : map.keySet())
         println(key+":"+map.get(key));
    }


}
