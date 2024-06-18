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
        return this ;
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
