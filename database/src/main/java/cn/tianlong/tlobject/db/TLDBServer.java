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

    protected TLBaseConnectorInterface connector;

    public TLDBServer() {
        super();
    }

    public TLDBServer(String name) {
        super(name);
    }

    public TLDBServer(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }


    /** 连不上时的最大重试次数（原来是无上限 do-while，数据库不可用则启动线程永久阻塞） */
    private int maxConnectRetries = 20;
    /** 重试间隔：按指数退避，从 retryBaseInterval 起翻倍，上限 maxRetryInterval */
    private int retryBaseInterval = 2000;
    private int maxRetryInterval = 10000;
    /** 重试总时长上限（秒）：20 次指数退避最坏 ~3 分钟，多个 dbserver 串行会拖死启动 */
    private int maxConnectRetrySeconds = 60;

    @Override
    protected TLBaseModule init() {
        setConnector();
        if (params != null) {
            if (params.get("maxConnectRetries") != null)
                maxConnectRetries = Integer.parseInt(params.get("maxConnectRetries"));
            if (params.get("retryBaseInterval") != null)
                retryBaseInterval = Integer.parseInt(params.get("retryBaseInterval"));
            if (params.get("maxRetryInterval") != null)
                maxRetryInterval = Integer.parseInt(params.get("maxRetryInterval"));
            if (params.get("maxConnectRetrySeconds") != null)
                maxConnectRetrySeconds = Integer.parseInt(params.get("maxConnectRetrySeconds"));
        }
        Object conn = connector.connect();
        if(conn ==null)
        {
            putLog("数据库没有连接",LogLevel.ERROR,"init");
            int interval = retryBaseInterval;
            int attempt = 0;
            long deadline = System.currentTimeMillis() + maxConnectRetrySeconds * 1000L;
            while (conn == null && attempt < maxConnectRetries && System.currentTimeMillis() < deadline) {
                attempt++;
                try {
                    sleep(interval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();     // 恢复中断标志，不要只 printStackTrace
                    putLog("数据库连接等待被中断，放弃重试",LogLevel.ERROR,"init");
                    return null;
                }
                putLog("数据库连接中，第" + attempt + "次重试",LogLevel.ERROR,"init");
                conn = connector.connect();
                interval = Math.min(interval * 2, maxRetryInterval);
            }
            if (conn == null) {
                putLog("数据库连接失败，已重试" + attempt + "次 / " + maxConnectRetrySeconds
                        + "s，放弃（模块启动失败，交由上层处理）",LogLevel.ERROR,"init");
                return null;
            }
        }
        putLog("数据库连接",LogLevel.DEBUG,"init");
        connector.close(conn);
        return this ;
    }


    private void setConnector() {
        String dbconnector = (params != null) ? params.get(DB_R_CONNECTOR) : null;
        if (dbconnector == null || dbconnector.isEmpty()) {
            connector = new selfConnector();
            // 语义提示：selfConnector 每次 connect() 都新建物理连接（无池化），和 c3p0/Hikari 差异巨大
            putLog("未配置连接器，使用 selfConnector：每次连接都是新建物理连接，生产环境请配置 c3p0/Hikari",
                    LogLevel.WARN, "setConnector");
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
