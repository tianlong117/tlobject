package cn.tianlong.tlobject.db;


import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;

import static cn.tianlong.tlobject.db.TLDataBase.getResultSetHandler;


/**
 * 创建日期：2018/3/21 on 8:48
 * 描述:
 * 作者:tianlong
 */

public class TLDBView extends TLTable {

    private String sql ;
    private String runSql ;
    private TLDataBase.RESULT_TYPE resultType;

    public TLDBView() {
        super();
    }
    public TLDBView(String name ) {
        super(name);
    }
    public TLDBView(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected void initProperty()  {
        super.initProperty();
        sql= params.get("sql");
        runSql =sql ;
        params.put("connOnDB","false");
        if(params.get("resultType")==null)
            resultType=TLDataBase.RESULT_TYPE.MAPLIST;
        else
            resultType =TLDataBase.RESULT_TYPE.valueOf(params.get("resultType").toUpperCase());
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case DB_QUERY:
                returnMsg =  vquery( fromWho,  msg);
                break;
            case DB_SETSQL:
              setSql( fromWho,  msg);
                break;
            case DB_GETSQL:
                returnMsg =getSql( fromWho,  msg);
                break;
            case DB_REPLACESQL:
                replaceSql( fromWho,  msg);
                break;
            default:
                returnMsg=null;
        }
        return returnMsg;
    }

    private void replaceSql(Object fromWho, TLMsg msg) {
        runSql=sql.replace("[SQL]", (CharSequence) msg.getParam(DB_P_SQL));
    }

    private TLMsg getSql(Object fromWho, TLMsg msg) {
        return  createMsg().setParam(DB_P_SQL,sql) ;
    }

    private void setSql(Object fromWho, TLMsg msg) {
        sql= (String) msg.getParam(DB_P_SQL);
        runSql =sql ;
    }
    private TLMsg vquery(Object fromWho, TLMsg msg) {
        boolean ifQueryCache=ifCache || msg.parseBoolean(DB_P_IFCACHE,false);
        LinkedHashMap<String ,Object> sqlParamsList = (LinkedHashMap<String, Object>) msg.getParam("params");
        String cacheKey = null;
        if(ifQueryCache)
        {
            cacheKey =makeCacheKey(sql,sqlParamsList,resultType);
            Object cacheValue =((TLDBServer)dbserver).getCache(name,cacheKey, resultType);
            if(((TLDBServer)dbserver).isCacheValue(cacheValue))
                return   msg.setParam(DB_R_RESULT, cacheValue);
            else
            {
                boolean  addSucess=  ((TLDBServer)dbserver).addSqlCacheIndex(name,cacheKey);
                if(addSucess==false){
                    cacheValue =((TLDBServer)dbserver).getCache(name,cacheKey, (TLDataBase.RESULT_TYPE) resultType);
                    if(((TLDBServer)dbserver).isCacheValue(cacheValue))
                        return   msg.setParam(DB_R_RESULT, cacheValue);
                    else
                        return  msg.setParam(DB_R_RESULT, false);
                }
            }
        }
        Connection rconn = (Connection) getConnection("read");
        if(rconn==null){
            putLog("数据库没有连接",LogLevel.ERROR);
            return null;
        }
        String key= (String) msg.getParam(DB_P_HANDERKEY);
        ResultSetHandler rsh=getResultSetHandler( resultType,key);
        QueryRunner runner = new QueryRunner();
        putLog(sql,LogLevel.DEBUG,"query");
        Object result = null;
        if(sqlParamsList==null)
        {
            try {
                result=runner.query(rconn,runSql,rsh);
                rconn.close();
            } catch (SQLException e) {
                putLog(runSql,LogLevel.ERROR,"query");
            }
            return createMsg().setParam(DB_R_RESULT,result);
        }
        Object[] sqlParams =new Object[sqlParamsList.size()];
        int i=0;
        for (String key1 : sqlParamsList.keySet()) {            ;
            sqlParams[i]=sqlParamsList.get(key1);
            i++;
        }
        try {
            result=runner.query(rconn,runSql, rsh,sqlParams);
            rconn.close();
        } catch (SQLException e) {
            putLog(runSql,LogLevel.ERROR,"query");
        }
        if(cacheKey !=null)
        {

            ((TLDBServer)dbserver).writeCache(name,cacheKey, result,  resultType,1);
            ((TLDBServer)dbserver).removeSqlCacheIndex(name,cacheKey);
        }
        TLMsg returnMsg=createMsg().setParam(DB_R_RESULT,result);
        return returnMsg;

    }

}
