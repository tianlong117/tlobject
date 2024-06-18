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
                returnMsg =  query( fromWho,  msg);
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
    @Override
    protected TLMsg query(Object fromWho, TLMsg msg) {
        msg.setParam(DB_P_SQL,runSql);
        msg.setParam(DB_P_RESULTTYPE,resultType);
        if(ifCache)
        {
            String cacheName=(params.get(DB_P_CACHENAME)!=null)? params.get(DB_P_CACHENAME) :name;
            msg.setParam(DB_P_CACHENAME,cacheName);
        }
        return super.query(fromWho,msg);
    }
}
