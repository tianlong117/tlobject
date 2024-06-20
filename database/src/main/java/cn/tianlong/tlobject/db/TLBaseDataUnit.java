package cn.tianlong.tlobject.db;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.cache.TLBaseCache;
import cn.tianlong.tlobject.utils.TLDataUtils;

import java.util.HashMap;
import java.util.Map;


/**
 * 创建日期：2018/3/21 on 8:48
 * 描述:
 * 作者:tianlong
 */

public abstract class TLBaseDataUnit extends TLBaseModule {
    protected TLBaseConnectorInterface connector;
    protected TLBaseConnectorInterface readConnector;
    protected boolean ifCache =false ;
    protected int cacheExptime=1;
    protected Object conn;
    protected Object readconn;
    protected String dbtable;
    protected TLBaseModule dbserver;
    protected TLBaseModule readserver;
    protected String database ;

    protected TLBaseCache cacheModule ;
    protected String cacheModuleName="memoryCache";
    public TLBaseDataUnit() {
        super();
    }
    public TLBaseDataUnit(String name ) {
        super(name);
    }
    public TLBaseDataUnit(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
        autoConfig =false;
    }
    @Override
    protected void initProperty()  {
        setTableParams();
    }

    @Override
    protected TLBaseModule init()
    {
        String dbserverName=params.get("dbserver");
        if(dbserverName ==null || dbserverName.isEmpty())
            dbserverName=params.get("defaultDBserver");
        dbserver =(TLBaseModule)putMsg(database,createMsg().setAction(DB_GETSERVER).setParam(DB_P_SERVERNAME,dbserverName)).getParam(INSTANCE) ;
        if(dbserver ==null)
            return null ;
        String readserverName=params.get("readserver");
        if(readserverName ==null || readserverName.isEmpty())
            readserver =dbserver ;
        else {
            readserver =(TLBaseModule)putMsg(database,createMsg().setAction(DB_GETSERVER).setParam(DB_P_SERVERNAME,readserverName)).getParam(INSTANCE) ;
            if(readserver ==null)
                return null ;
        }
        if(ifCache)
            cacheModule= (TLBaseCache) getModule(cacheModuleName);
        return  this ;
    }

    protected void setTableParams(){
        if(params!=null && params.get("dbtable")!=null)
            dbtable= params.get("dbtable");
        else
            dbtable= name;
        database=params.get("database");
        if (params.get(DB_P_IFCACHE) != null)
            ifCache =TLDataUtils.parseBoolean(params.get(DB_P_IFCACHE),false);
        if (params.get(DB_P_CACHEEXPTIME) != null)
            cacheExptime = TLDataUtils.parseInt(params.get(DB_P_CACHEEXPTIME),3);
        if (params.get(DB_P_CACHEMODULE) != null)
            cacheModuleName = params.get(DB_P_CACHEMODULE);
    }
    public String getTableName(){
        return dbtable ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "setTableParams":
                setTableParams( fromWho,  msg);
                break;
            case DB_SETCONNECTION:
                setConnection( fromWho,  msg);
                break;
            case DB_CLOSECONNECTION:
                closeConnection( fromWho,  msg);
                break;
            case DB_INSERT:
                returnMsg = insert( fromWho,  msg);
                break;
            case DB_UPDATE:
                returnMsg = update( fromWho,  msg);
                break;
            case DB_DELETE:
                returnMsg =  delete( fromWho,  msg);
                break;
            case DB_QUERY:
                returnMsg =  query( fromWho,  msg);
                break;
            default:
                returnMsg=null;
        }
        return returnMsg;
    }

    protected abstract TLMsg insert(Object fromWho, TLMsg msg);
    protected abstract TLMsg update(Object fromWho, TLMsg msg);
    protected abstract TLMsg delete(Object fromWho, TLMsg msg);
    protected abstract TLMsg query(Object fromWho, TLMsg msg);

    protected void setTableParams(Object fromWho, TLMsg msg) {
        params= (HashMap)msg.getParam("params");
        if(params!=null )
            setTableParams();
    }

    protected void setConnection(Object fromWho, TLMsg msg) {
        conn= getConnection(null);
        readconn= getConnection("read");
    }
    protected void closeConnection(Object fromWho, TLMsg msg) {
    }
    protected Object getConnection(String connType){
        if(connType==null )
        {
            if(connector ==null)
                connector= (TLBaseConnectorInterface) putMsg(dbserver,createMsg().setAction(DB_GETCONNECTOR))
                        .getParam(DB_R_CONNECTOR);
            return  connector.connect();
        }
        else
        {
            if(readConnector ==null)
                readConnector= (TLBaseConnectorInterface) putMsg(readserver,createMsg().setAction(DB_GETCONNECTOR))
                        .getParam(DB_R_CONNECTOR);
            return  readConnector.connect();
        }
    }


    protected boolean writeCache(String tableName,String cacheKey,Object cacheValue, TLDataBase.RESULT_TYPE resultType,int exptime){

        String valueType =resultType.toString().toLowerCase();
        return cacheModule.writeCache( tableName,cacheKey,cacheValue,exptime,valueType);
    }

    protected Object getCache (String tableName,String cacheKey ,TLDataBase.RESULT_TYPE resultType){
        String valueType =resultType.toString().toLowerCase();
        return cacheModule.getCache( tableName,cacheKey,valueType);
    }

    public Boolean isCacheValue(Object value){
        return cacheModule.isCacheValue(value) ;
    }
}
