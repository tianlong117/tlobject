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
        if(!msg.isNull("params"))
           params= (HashMap) msg.getParam("params");
        if (params == null || params.get("dburl") == null) {
            putLog("c3p0 初始化失败：缺少 params 或 dburl", LogLevel.ERROR, "init");
            return;
        }
        dataSource = new ComboPooledDataSource();// 使用默认的配置
        try {
            dataSource.setDriverClass(params.get("driver"));//获取驱动
        } catch (PropertyVetoException e) {
            putLog("没有发现驱动："+params.get("driver"),LogLevel.ERROR);
        }
        dataSource.setJdbcUrl(params.get("dburl"));//设置连接字符串
        dataSource.setUser(params.get("dbuser"));//用户名
        dataSource.setPassword(params.get("dbpass"));//密码
        // 原来直接 Integer.parseInt(params.get(...))，配置里少写任一项就 NPE 崩在启动阶段
        dataSource.setInitialPoolSize(intParam(params, "initPoolSize", 3));//初始化时获取三个连接
        dataSource.setMaxPoolSize(intParam(params, "maxPoolSize", 15));//连接池中保留的最大连接数
        dataSource.setMaxIdleTime(intParam(params, "maxIdleTime", 60)); //最大空闲时间,60秒内未使用则连接被丢弃。若为0则永不丢弃
        // 连接有效性检测：没有这些，MySQL wait_timeout 之后 c3p0 仍会把死连接分发出去
        dataSource.setIdleConnectionTestPeriod(intParam(params, "idleConnectionTestPeriod", 60));
        dataSource.setPreferredTestQuery(params.get("preferredTestQuery") != null
                ? params.get("preferredTestQuery") : "SELECT 1");
        dataSource.setTestConnectionOnCheckout(Boolean.parseBoolean(params.get("testConnectionOnCheckout")));
        if (params.get("maxConnectionAge") != null)
            dataSource.setMaxConnectionAge(intParam(params, "maxConnectionAge", 1800));

    }

    /** 取整数参数，缺失或非法时用默认值（原来直接 parseInt 会在配置缺项时崩） */
    protected static int intParam(HashMap<String, String> p, String key, int def) {
        String v = p.get(key);
        if (v == null || v.trim().isEmpty())
            return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
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
           // 不再打印 dbuser（敏感信息）；带上 SQLState/errorCode 便于区分"连不上"与"超时"
           putLog("数据库无法连接："+TLBaseConnectorInterface.logSafeDbUrl(params)+" SQLState="+e.getSQLState()+" errorCode="+e.getErrorCode(),LogLevel.ERROR);
            return null;
        }
    }

    @Override
    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        // DataSource 是重量级资源；c3p0 还会注册到全局 C3P0Registry，不 close 会一直被引用
        if (dataSource != null) {
            try { dataSource.close(); } catch (Exception e) { putLog("关闭 c3p0 连接池失败: "+e, LogLevel.ERROR, "destroy"); }
            dataSource = null;
        }
        return super.destroy(fromWho, msg);
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
