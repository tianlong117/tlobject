package cn.tianlong.tlobject.db;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLMapUtils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

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
        // 原来此处还有一行无条件 params = msg.getParam("params")，把上面的 if 变成死代码；
        // 且调用方没传 params 时 params 为 null → 紧接着 params.get("dburl") 直接 NPE
        if (params == null || params.get("dburl") == null) {
            putLog("HikariCP 初始化失败：缺少 params 或 dburl", LogLevel.ERROR, "init");
            return;
        }
        // 改用 HikariConfig：配置在构造时校验（否则要到第一次 getConnection 才报错），
        // 并且池参数可配 —— 原来全部用 Hikari 默认值（maximumPoolSize=10 等），配置里的池参数被忽略
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(params.get("dburl"));//设置连接字符串
        cfg.setDriverClassName(params.get("driver"));//获取驱动
        cfg.setUsername(params.get("dbuser"));//用户名
        cfg.setPassword(params.get("dbpass"));//密码
        cfg.setPoolName(name);
        cfg.setMaximumPoolSize(intParam(params, "maxPoolSize", 10));
        if (params.get("minIdle") != null)
            cfg.setMinimumIdle(intParam(params, "minIdle", 2));
        if (params.get("connectionTimeout") != null)
            cfg.setConnectionTimeout(longParam(params, "connectionTimeout", 30000L));
        if (params.get("idleTimeout") != null)
            cfg.setIdleTimeout(longParam(params, "idleTimeout", 600000L));
        if (params.get("maxLifetime") != null)
            cfg.setMaxLifetime(longParam(params, "maxLifetime", 1800000L));
        if (params.get("connectionTestQuery") != null)
            cfg.setConnectionTestQuery(params.get("connectionTestQuery"));
        if(params.get("cachePrepStmts")!=null)
           cfg.addDataSourceProperty("cachePrepStmts", TLMapUtils.parseBoolean(params,"cachePrepStmts",false));
        if(params.get("prepStmtCacheSize")!=null)
           cfg.addDataSourceProperty("prepStmtCacheSize", params.get("prepStmtCacheSize"));
        if(params.get("prepStmtCacheSqlLimit")!=null)
            cfg.addDataSourceProperty("prepStmtCacheSqlLimit", params.get("prepStmtCacheSqlLimit"));
        dataSource = new HikariDataSource(cfg);
    }

    /** 取整数参数，缺失或非法时用默认值（原来直接 parseInt 会在配置缺项时崩） */
    protected static int intParam(HashMap<String, String> p, String key, int def) {
        String v = p.get(key);
        if (v == null || v.trim().isEmpty())
            return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }
    protected static long longParam(HashMap<String, String> p, String key, long def) {
        String v = p.get(key);
        if (v == null || v.trim().isEmpty())
            return def;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return def; }
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
        // DataSource 是重量级资源，Hikari 内部有 housekeeping 线程；不 close 会让 JVM 无法退出
        if (dataSource != null) {
            try { dataSource.close(); } catch (Exception e) { putLog("关闭 Hikari 连接池失败: "+e, LogLevel.ERROR, "destroy"); }
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
