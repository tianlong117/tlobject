package cn.tianlong.tlobject.db;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLMapUtils;
import com.zaxxer.hikari.HikariDataSource;

import java.beans.PropertyVetoException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;


/**
 * 创建日期：2018/3/21 on 8:48
 * 描述:
 * 作者:tianlong
 */

public class TLHikariCP extends TLBaseModule implements TLBaseConnectorInterface {
    private HikariDataSource dataSource ;
    public TLHikariCP() {
        super();
    }
    public TLHikariCP(String name ) {
        super(name);
    }
    public TLHikariCP(String name , TLObjectFactory modulefactory){
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
                returnMsg=createMsg().setParam("dataSource",dataSource);
                break;
            default:
                returnMsg=null;
        }
        return returnMsg;
    }
    protected void init(Object fromWho, TLMsg msg) {
        if(!msg.isNull("params"))
            params= (HashMap) msg.getParam("params");
        params= (HashMap) msg.getParam("params");
        dataSource = new HikariDataSource();// 使用默认的配置
        dataSource.setJdbcUrl(params.get("dburl"));//设置连接字符串
        dataSource.setDriverClassName(params.get("driver"));//获取驱动
        dataSource.setUsername(params.get("dbuser"));//用户名
        dataSource.setPassword(params.get("dbpass"));//密码
        if(params.get("cachePrepStmts")!=null)
           dataSource.addDataSourceProperty("cachePrepStmts", TLMapUtils.parseBoolean(params,"cachePrepStmts",false));
        if(params.get("prepStmtCacheSize")!=null)
           dataSource.addDataSourceProperty("prepStmtCacheSize", params.get("prepStmtCacheSize"));
        if(params.get("prepStmtCacheSqlLimit")!=null)
            dataSource.addDataSourceProperty("prepStmtCacheSqlLimit", params.get("prepStmtCacheSqlLimit"));

    }
    private TLMsg getConnection(Object fromWho, TLMsg msg) {
        Connection   conn=connect();
        return msg.setParam(DB_R_CONN,conn);
    }

    @Override
    public Connection connect() {
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
           putLog("数据库无法连接："+params.get("dburl")+" user:"+params.get("dbuser"),LogLevel.ERROR);
            return null;
        }
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
