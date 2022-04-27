package cn.tianlong.tlobject.db;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.mchange.v2.c3p0.ComboPooledDataSource;

import java.beans.PropertyVetoException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;


/**
 * 创建日期：2018/3/21 on 8:48
 * 描述:
 * 作者:tianlong
 */

public class TLC3P0connect extends TLBaseModule implements TLBaseConnectorInterface {
    private  ComboPooledDataSource dataSource ;
    public TLC3P0connect() {
        super();
    }
    public TLC3P0connect(String name ) {
        super(name);
    }
    public TLC3P0connect(String name , TLObjectFactory modulefactory){
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

        params= (HashMap) msg.getParam("params");
        dataSource = new ComboPooledDataSource();// 使用默认的配置
        try {
            dataSource.setDriverClass(params.get("driver"));//获取驱动
        } catch (PropertyVetoException e) {
            putLog("没有发现驱动："+params.get("driver"),LogLevel.ERROR);
        }
        dataSource.setJdbcUrl(params.get("dburl"));//设置连接字符串
        dataSource.setUser(params.get("dbuser"));//用户名
        dataSource.setPassword(params.get("dbpass"));//密码
        dataSource.setInitialPoolSize(Integer.parseInt(params.get("initPoolSize")));//初始化时获取三个连接
        dataSource.setMaxPoolSize(Integer.parseInt(params.get("maxPoolSize")));//连接池中保留的最大连接数
        dataSource.setMaxIdleTime(Integer.parseInt(params.get("maxIdleTime"))); //最大空闲时间,60秒内未使用则连接被丢弃。若为0则永不丢弃

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
