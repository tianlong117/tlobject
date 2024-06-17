package cn.tianlong.tlobject.db;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.cache.TLBaseCache;
import cn.tianlong.tlobject.modules.LogLevel;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.lang.Thread.sleep;


/**
 * 创建日期：2018/3/21 on 8:48
 * 描述:
 * 作者:tianlong
 */

public class TLDBServer extends TLBaseModule {
    protected boolean ifCache =false;
    protected TLBaseConnectorInterface connector;

    protected TLBaseCache cacheModule ;
    protected String cacheModuleName="memoryCache";
    protected CopyOnWriteArrayList<String> cacheSqlList = new CopyOnWriteArrayList<>() ;
    public TLDBServer() {
        super();
    }

    public TLDBServer(String name) {
        super(name);
    }

    public TLDBServer(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("ifCache") != null)
                ifCache = Boolean.parseBoolean(params.get("ifCache"));
            if (params.get("cacheModule") != null)
                cacheModuleName = params.get("cacheModule");
        }
    }
    @Override
    protected TLBaseModule init() {
        setConnector();
        Object conn = connector.connect();
        if(conn ==null)
        {
            putLog("数据库没有连接",LogLevel.ERROR,"init");
           do {
               try {
                   sleep(2000) ;
                   putLog("数据库连接中",LogLevel.ERROR,"init");
                   conn = connector.connect();
               } catch (InterruptedException e) {
                   e.printStackTrace();
                   return null ;
               }
           }while (conn ==null) ;
        }
        putLog("数据库连接",LogLevel.DEBUG,"init");
        connector.close(conn);
        if(ifCache)
            cacheModule= (TLBaseCache) getModule(cacheModuleName);
        return this ;
    }
    public boolean ifCache(){
        return ifCache;
    }
    public boolean writeCache(String tableName,String cacheKey,Object cacheValue, TLDataBase.RESULT_TYPE resultType,int exptime){

        String valueType =resultType.toString().toLowerCase();
        return cacheModule.writeCache( tableName,cacheKey,cacheValue,exptime,valueType);
    }
    public Object getCache (String tableName,String cacheKey ,TLDataBase.RESULT_TYPE resultType){
        String valueType =resultType.toString().toLowerCase();
        String cacheSql =tableName+cacheKey;
        /**
         * 检查是否包含cacheSql，如包含说明正有相同的sql在执行
         */
        do{
            if(!cacheSqlList.contains(cacheSql))
                break;
        } while (true);
        return cacheModule.getCache( tableName,cacheKey,valueType);
    }
    public String makeCacheKey(String sql, Map<String,Object> sqlParams ,TLDataBase.RESULT_TYPE resultType ){
        StringBuilder strBuffer = new StringBuilder().append(sql);
        if(sqlParams !=null)
            for(String key :sqlParams.keySet())
            {
                strBuffer.append(key) ;
                Object value =sqlParams.get(key);
                if(value !=null)
                    strBuffer.append(String.valueOf(value)) ;
            }
        strBuffer.append(resultType.toString());
        return String.valueOf( strBuffer.toString().hashCode());
    }
    public Boolean isCacheValue(Object value){
        return cacheModule.isCacheValue(value) ;
    }
    public Boolean addSqlCacheIndex(String tableName,String cacheKey){
        return   cacheSqlList.addIfAbsent(tableName+cacheKey);
    }
    public Boolean removeSqlCacheIndex(String tableName,String cacheKey){
        return   cacheSqlList.remove(tableName+cacheKey);
    }
    public Boolean ifSqlCacheIndexExist(String tableName,String cacheKey){
        return   cacheSqlList.contains(tableName+cacheKey);
    }
    private void setConnector() {
        String dbconnector = params.get(DB_R_CONNECTOR);
        if (dbconnector == null || dbconnector.isEmpty()) {
            connector = new selfConnector();
            return;
        }
        connector = (TLBaseConnectorInterface) getMyModule(dbconnector);
        putMsg((IObject) connector, createMsg().setAction("init").setParam("params", params));
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg;
        switch (msg.getAction()) {
            case DB_GETCONN:
                returnMsg = getConnection(fromWho, msg);
                break;
            case DB_GETCONNECTOR:
                returnMsg = getConnector(fromWho, msg);
                break;
            default:
                returnMsg = null;
        }
        return returnMsg;
    }

    private TLMsg getConnector(Object fromWho, TLMsg msg) {
        return msg.setParam(DB_R_CONNECTOR, connector);
    }

    private TLMsg getConnection(Object fromWho, TLMsg msg) {
        Object conn;
        if (connector instanceof selfConnector)
            conn = connector.connect();
        else {
            TLMsg returnmsg = putMsg((IObject) connector, createMsg().setAction(DB_GETCONN));
            conn =  returnmsg.getParam(DB_R_CONN);
        }
        return msg.setParam(DB_R_CONN, conn);
    }
    private class selfConnector implements TLBaseConnectorInterface {
        public Connection connect() {
            try {
                Class.forName(params.get("driver"));
            } catch (ClassNotFoundException e) {
                return null;
            }
            Connection conn;
            try {
                conn = DriverManager.getConnection(params.get("dburl"), params.get("dbuser"), params.get("dbpass"));
            } catch (SQLException e) {
                return null;
            }
            return conn;
        }

        @Override
        public void close(Object conn) {
            try {
                ( (Connection)conn).close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

    }
}
