package cn.tianlong.tlobject.db;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.cache.TLBaseCache;
import cn.tianlong.tlobject.db.dbdata.BeanTable;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;
import cn.tianlong.tlobject.utils.TLMsgUtils;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;
import org.apache.commons.dbutils.handlers.*;
import org.xmlpull.v1.XmlPullParser;

import java.sql.*;
import java.util.*;


/**
 * 创建日期：2018/3/21 on 8:48
 * 描述:
 * 作者:tianlong
 */

public class TLDatabase extends TLBaseModule {
    protected String dbPackageName;
    private final static String prefixTable = "table_";
    private final static String prefixBeanTable = "bean_";
    private final static String prefixTrigger = "trigger_";
    private final static String prefixView = "view_";
    private final static String prefixServer = "server_";
    protected int cacheExptime=1;
    protected HashMap<String, HashMap<String, String>> dbservers;
    protected HashMap<String, HashMap<String, String>> tables;
    protected HashMap<String, HashMap<String, String>> views;
    protected HashMap<String, HashMap<String, String>> triggers;
    protected HashMap<String, Object> dbObjs = new HashMap<>();
    protected HashMap<String, String> triggerName = new HashMap<>();

    public TLDatabase() {
        super();
    }

    public TLDatabase(String name) {
        super(name);
    }

    public TLDatabase(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected Object setConfig() {
        myConfig config = new myConfig(configFile,moduleFactory.getConfigDir());
        mconfig = config;
        super.setConfig();
        dbservers = config.getDBservers();
        tables = config.getTables();
        views = config.getViews();
        triggers = config.getTriggers();
        return config;
    }
    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("dbPackageName") != null)
                dbPackageName = params.get("dbPackageName");
        }
    }

    @Override
    protected TLBaseModule init() {
        triggerName.put("beforeTrigger", MODULE_ADDBEFOREMSG);
        triggerName.put("afterTrigger", MODULE_ADDAFTERMSG);
        Boolean initSucessed =initDbServer();
        if(initSucessed ==false)
            return null ;
        return  this ;
    }

    private Boolean initDbServer() {
        for(String serverName :dbservers.keySet()){
            HashMap<String, String> config =dbservers.get(serverName) ;
            if(config !=null && config.get("statup")!=null && Boolean.parseBoolean(config.get("statup"))==true)
            {
                TLBaseModule serverModule =createDbServer(serverName,config);
                if(serverModule ==null)
                    return false ;
            }
        }
        return true ;
    }

    private TLBaseModule createDbServer(String serverName, HashMap<String,String> config) {
        String sameClassAs= (config !=null )?config.get(MODULE_SameClassAs):DEFAULTDBSERVERMODULE;
        TLBaseModule serverobj;
        if(sameClassAs.indexOf("@") >0){
            String[] array =TLDataUtils.splitStrToArray(sameClassAs,"@");
            if(array.length !=3)
                return null;
            TLMsg msg =createMsg().setAction(DB_GETSERVER).setParam(DB_P_SERVERNAME,array[0]);
            String database =array[1]+"@"+array[2];
            TLMsg returnMsg =putMsg(database,msg);
            serverobj = (TLBaseModule) returnMsg.getParam(INSTANCE);
        }
        else
            serverobj =  (TLBaseModule)getModule( serverName,sameClassAs,false,false,config) ;
         if(serverobj != null)
          dbObjs.put(prefixServer+serverName, serverobj);
       return serverobj ;
    }

    @Override
    public void runStartMsg() {
        initTable();
        super.runStartMsg();
    }

    protected Boolean initTable() {
        boolean result =true;
        for(String tableName :tables.keySet())
        {
            HashMap<String, String> tableparams =tables.get(tableName) ;  
            if( TLDataUtils.parseBoolean(tableparams.get("statup"),true))
            {
                TLBaseModule tableModule =  makeTable(tableName,tableparams);
                if(tableModule ==null)
                    result =false ;
                String ifCreate =TLDataUtils.parseString(tableparams.get("ifCreate"),"");
                if(ifCreate.equals("create") || ifCreate.equals("reCreate") || ifCreate.equals("copy"))
                {
                    String dbtable=tableparams.get("dbtable");
                    if(dbtable !=null && !dbtable.isEmpty())
                        createTable(dbtable,tableparams);
                }
            }
        }
        return result ;
    }

    private boolean createTable(String tableName, HashMap<String, String> tableparams) {
        boolean result =false ;
        String ifCreate =TLDataUtils.parseString(tableparams.get("ifCreate"),"");
        String dbserver =tableparams.get("dbserver");
        if(dbserver==null || dbserver.isEmpty())
            dbserver=params.get("defaultDBserver") ;
        if( ifCreate.equals("reCreate"))
        {
            String deletesql ="drop table if exists " +tableName  ;
            result=prepareCall(dbserver,deletesql);
        }
        if( ifCreate.equals("copy")){
            String copyTable = tableparams.get(DB_P_COPYTABLE);
            result= createDBtableFromCopy(tableName,copyTable,dbserver);
        }
        else {
            String createSql = tableparams.get("createSql");
            if(createSql!=null && !createSql.isEmpty()) {
                if (!isTableExistInDb(dbserver, tableName)) {
                    result = prepareCall(dbserver, createSql);
                } else {
                    putLog("表已存在，跳过创建: " + tableName, LogLevel.DEBUG);
                    result = true;
                }
            }
            else {
                String fields =tableparams.get("fields");
                if(fields!=null && !fields.isEmpty())
                {
                    String dbtable =tableparams.get("dbtable");
                    createSql ="CREATE TABLE IF NOT EXISTS "  +dbtable
                            +" ( "
                            +fields
                            +" ) " ;
                    result= prepareCall(dbserver,createSql);
                }
            }
        }
        if(result)
            putLog("表创建成功: "+tableName, LogLevel.DEBUG);
        else
            putLog("表创建失败: "+tableName, LogLevel.ERROR);
        return result ;
    }

    private boolean isTableExistInDb(String dbserver, String tableName) {
        Connection conn = getConnection(dbserver);
        if (conn == null)
            return false;
        java.sql.ResultSet tables = null;
        try {
            DatabaseMetaData meta = conn.getMetaData();
            tables = meta.getTables(null, null, tableName, null);
            return tables.next();
        } catch (SQLException e) {
            return false;
        } finally {
            // 原来只有成功路径 conn.close()，异常路径直接 return false → 连接泄漏（ResultSet 也从没关过）
            if (tables != null) {
                try { tables.close(); } catch (SQLException ignored) {}
            }
            try { conn.close(); } catch (SQLException ignored) {}
        }
    }

    private Boolean prepareCall(String dbserver, String sql) {
        Connection rconn = getConnection(dbserver);
        if (rconn == null) {
            putLog("数据库没有连接", LogLevel.ERROR);
            return false;
        }
        Boolean result = false;
        try {
            java.sql.Statement stmt = rconn.createStatement();
            stmt.execute(sql);
            stmt.close();
            result = true;
            rconn.close();
            putLog(dbserver+" sql成功执行： "+sql, LogLevel.DEBUG);
        } catch (SQLException e) {
            e.printStackTrace();
            putLog(dbserver+" sql执行失败： "+sql, LogLevel.ERROR);
        }
        return result;
    }
    
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case DB_GETCONN:
                returnMsg = getConnection(fromWho, msg);
                break;
            case "getDbserverParams":
                returnMsg = getDbserverParams(fromWho, msg);
                break;
            case "getDbTriggerParams":
                returnMsg = getDbTriggerParams(fromWho, msg);
                break;
            case DB_GETTABLEPARAMS:
                returnMsg = getTableParams(fromWho, msg);
                break;
            case "getViewParams":
                returnMsg = getViewParams(fromWho, msg);
                break;
            case DB_CREATETABLE:
                returnMsg = createTable(fromWho, msg);
                break;
            case DB_GETSERVER:
                returnMsg = getServer(fromWho, msg);
                break;
            case DB_GETTABLE:
                returnMsg = getTable(fromWho, msg);
                break;
            case DB_GETBEANTABLE:
                returnMsg = getBeanTable(fromWho, msg);
                break;
            case DB_GETVIEW:
                returnMsg = getViews(fromWho, msg);
                break;
            case DB_FIND:
            case DB_FINDALL:
            case DB_TOTAL:
            case DB_QUERY:
            case DB_DELETE:
            case DB_INSERT:
            case DB_UPDATE:
            case DB_BATCH:
            case DB_EXECQUERY:
                returnMsg = execQuery(fromWho, msg);
                break;
            case DB_ISTABLEEXIST:
                returnMsg = isTableExist(fromWho, msg);
                break;
            case DB_STARTTRANSACTION:
                try {
                    returnMsg = startTranscation(fromWho, msg);
                } catch (SQLException e) {
                    e.printStackTrace();
                    return createMsg().setParam(RESULT,false);
                }
                break;
            default:
                returnMsg = null;
        }
        return returnMsg;
    }

    private TLMsg isTableExist(Object fromWho, TLMsg msg) {
        String tableName = msg.getStringParam(DB_P_TABLENAME,"");
        if(tableName.isEmpty())
            return createMsg().setParam(RESULT,false);
        String dbserver = msg.getStringParam(DB_DBSERVER,"");
        if (dbserver == null || dbserver.isEmpty())
        {
            HashMap<String, String> tableparams = tables.get(tableName);
            dbserver = tableparams.get("dbserver");
        }
        if(dbserver==null || dbserver.isEmpty())
            dbserver = params.get("defaultDBserver");
        boolean result =isTableExistInDb( dbserver, tableName);
        if(result ==true)
            return createMsg().setParam(RESULT,true);
        String sql =msg.getStringParam(DB_P_SQL,"");
        if(sql ==null || sql.isEmpty())
            return createMsg().setParam(RESULT,result);
        Connection conn = getConnection(dbserver);
        if (conn == null) {
            putLog("数据库没有连接", LogLevel.ERROR);
            return createMsg().setParam(RESULT, false);
        }
        Statement stmt= null;
        try {
            stmt = conn.createStatement();
            stmt.execute(sql);
            result =true ;
            conn.close();
        } catch (SQLException e) {
            result=false ;
            try {
                conn.close();
            } catch (SQLException e1) {
                e1.printStackTrace();
            }
            e.printStackTrace();
        }
        return createMsg().setParam(RESULT,result);
    }

    /**
     事务模式下调用：

     java
     // 构建消息列表
     ArrayList<TLMsg> msgList = new ArrayList<>();
     msgList.add(createMsg()
     .setAction(DB_INSERT)
     .setParam(DB_P_TABLENAME, "user")
     .setParam(DB_P_PARAMS, userData));
     msgList.add(createMsg()
     .setAction(DB_UPDATE)
     .setParam(DB_P_TABLENAME, "account")
     .setParam(DB_P_PARAMS, accountData));

     // 开启事务
     TLMsg txMsg = createMsg()
     .setAction(DB_STARTTRANSACTION)
     .setParam(DB_P_MSGLIST, msgList);

     TLMsg result = putMsg(DEFAULTDATABASE, txMsg);
     boolean success = result.parseBoolean(RESULT, false);
     */
    private TLMsg startTranscation(Object fromWho, TLMsg msg) throws SQLException {
        ArrayList<TLMsg> msgList = (ArrayList<TLMsg>) msg.getListParam(DB_P_MSGLIST, null);
        if (msgList == null || msgList.isEmpty()) {
            return createMsg().setParam(RESULT, false);
        }

        ArrayList<Connection> connections = new ArrayList<>();
        int successCount = 0;

        try {
            for (int i = 0; i < msgList.size(); i++) {
                TLMsg uMsg = msgList.get(i);
                TLMsg tMsg = new TLMsg().copyFrom(uMsg);

                String tableName = tMsg.getStringParam(DB_P_TABLENAME, "");
                if (tableName.isEmpty()) {
                    putLog("no tableName", LogLevel.ERROR, "startTranscation");
                    return createMsg().setParam(RESULT, false);
                }

                // 获取表模块
                TLMsg tableMsg = getTable(this, tMsg);
                TLBaseModule table = (TLBaseModule) tableMsg.getParam(INSTANCE);
                if (table == null) {
                    putLog("table not found: " + tableName, LogLevel.ERROR, "startTranscation");
                    return createMsg().setParam(RESULT, false);
                }

                // 设置事务参数
                tMsg.setParam(DB_P_IFTRANSACTION, true);
                tMsg.setParam(DB_P_IFCLOSECONNECTION, false);
                tMsg.removeParam(DB_P_TABLENAME);

                // 执行
                TLMsg returnMsg = putMsg(table, tMsg);

                if (!returnMsg.parseBoolean(RESULT, true)) {
                    // 执行失败，回滚所有已成功的操作
                    rollbackAll(connections);
                    return createMsg().setParam(RESULT, false)
                            .setParam("number", i)
                            .setParam("error", returnMsg.getStringParam("error", ""));
                }

                // 收集连接（去重）
                Connection conn = (Connection) returnMsg.getParam(DB_R_CONN);
                if (conn != null && !connections.contains(conn)) {
                    connections.add(conn);
                }
                successCount++;
            }

            // 全部成功：提交所有连接
            for (Connection conn : connections) {
                try {
                    if (conn != null && !conn.isClosed()) {
                        conn.commit();
                    }
                } catch (SQLException e) {
                    putLog("commit error: " + e.getMessage(), LogLevel.ERROR, "startTranscation");
                    rollbackAll(connections);
                    return createMsg().setParam(RESULT, false)
                            .setParam("number", connections.indexOf(conn));
                }
            }

            // 关闭所有连接
            for (Connection conn : connections) {
                try {
                    if (conn != null && !conn.isClosed()) {
                        conn.close();
                    }
                } catch (SQLException e) {
                    putLog("close connection error: " + e.getMessage(), LogLevel.WARN, "startTranscation");
                }
            }

            return createMsg().setParam(RESULT, true);

        } catch (Exception e) {
            putLog("transaction error: " + e.getMessage(), LogLevel.ERROR, "startTranscation");
            rollbackAll(connections);
            return createMsg().setParam(RESULT, false);
        }
    }

    /**
     * 回滚所有连接
     */
    private void rollbackAll(ArrayList<Connection> connections) {
        for (Connection conn : connections) {
            if (conn != null) {
                try {
                    if (!conn.isClosed()) {
                        conn.rollback();
                    }
                } catch (SQLException e) {
                    putLog("rollback error: " + e.getMessage(), LogLevel.WARN, "rollbackAll");
                }
                try {
                    if (!conn.isClosed()) {
                        conn.close();
                    }
                } catch (SQLException e) {
                    putLog("close connection error: " + e.getMessage(), LogLevel.WARN, "rollbackAll");
                }
            }
        }
        connections.clear();
    }


    private String selectDbServer(TLMsg msg){
        String dbserver = msg.getStringParam(DB_P_SERVERNAME,"");
        if (dbserver == null || dbserver.isEmpty())
            dbserver = params.get("defaultDBserver");
        return dbserver ;
    }
    private TLMsg createTable(Object fromWho, TLMsg msg) {
        String dbserver =msg.getStringParam(DB_DBSERVER,"");
        String sql =msg.getStringParam(DB_P_SQL,"");
        if(sql !=null && !sql.isEmpty())
        {
            if(dbserver==null || dbserver.isEmpty())
                dbserver=params.get("defaultDBserver") ;
            Boolean result = prepareCall(dbserver,sql)  ;
            return createMsg().setParam(DB_R_RESULT, result);
        }
        String tablename =  msg.getStringParam(DB_P_TABLENAME,null) ;
        if(tablename == null || tablename.isEmpty())
            return createMsg().setParam(DB_R_RESULT, false);
        String copyTable = msg.getStringParam(DB_P_COPYTABLE,null);
        if(copyTable ==null || copyTable.isEmpty())
            return createMsg().setParam(DB_R_RESULT, false);
        if(dbserver==null || dbserver.isEmpty()) {
            HashMap<String, String> tableparams = tables.get(copyTable);
            dbserver = tableparams.get("dbserver");
        }
        Boolean result = createDBtableFromCopy(tablename, copyTable, dbserver);
        return createMsg().setParam(DB_R_RESULT, result);
    }

    private Boolean createDBtableFromCopy(String tablename, String copyTable, String dbserver) {
        String sql = "CREATE  TABLE IF NOT EXISTS " + tablename + " LIKE " + copyTable;
          return  prepareCall(dbserver,sql)  ;
    }

    private TLMsg execQuery(Object fromWho, TLMsg msg) {
        TLBaseModule tableobj =null ;
        String tablename = msg.getStringParam(DB_P_TABLENAME,"");
        if (!tablename.isEmpty())
           {
               tableobj = getTable(tablename,msg);
               msg.removeParam(DB_P_TABLENAME);
           }
        else {
            String viewName = msg.getStringParam(DB_P_VIEWNAME,"");
            if(!viewName.isEmpty())
               tableobj = (TLBaseModule) dbObjs.get(prefixView+viewName);
        }
        if (tableobj != null)
           return   putMsg(tableobj,msg) ;
        String dbserver =selectDbServer(msg);
        Object resultType = msg.getParam(DB_P_RESULTTYPE);
        RESULT_TYPE sqlResultType =getResultType(resultType);
        String action = msg.getAction();
        LinkedHashMap<String, Object> sqlParamsMap = (LinkedHashMap<String, Object>) msg.getMapParam(DB_P_PARAMS,null);
        String sql = (String) msg.getParam(DB_P_SQL);
        String cacheKey = null;
        String cacheName = null;
        TLBaseCache cacheModule=null ;
        String cacheValueType =sqlResultType.toString().toLowerCase();
        TLMsg returnMsg =createMsg();
        if( action.equals(DB_QUERY)){
            if(!msg.isNull(DB_P_CACHENAME))
            {
                String  cacheModuleName = msg.getStringParam(DB_P_CACHEMODULE ,"memoryCache");
                cacheModule= (TLBaseCache) getModule(cacheModuleName);
                cacheName= (String) msg.getParam(DB_P_CACHENAME);
                cacheKey =msg.getStringParam(DB_P_CACHEKEY,null);
                if(cacheKey ==null)
                    cacheKey =makeCacheKey(sql,sqlParamsMap);
                Object cacheValue =cacheModule.getCache(cacheName,cacheKey, cacheValueType);
                if(cacheModule.isCacheValue(cacheValue))
                    return   returnMsg.setParam(DB_R_RESULT, cacheValue);
            }
        }

        Connection conn = getConnection(dbserver);
        if (conn == null) {
            putLog("数据库没有连接", LogLevel.ERROR);
            return createMsg().setParam(RESULT, false);
        }
        if(msg.parseBoolean(DB_P_IFTRANSACTION,false) == true)
        {
            try {
                conn.setAutoCommit(false);
            } catch (SQLException e) {
                e.printStackTrace();
                return createMsg().setParam(RESULT, false);
            }
        }
        ResultSetHandler rsh = getResultSetHandler(sqlResultType,msg);
        if (rsh == null) {
            putLog("ResultSetHandler is wrong :" +  msg.getParam(DB_P_RESULTTYPE), LogLevel.WARN, "query");
            return createMsg().setParam(RESULT, false);
        }
        QueryRunner runner = new QueryRunner();
        putLog(sql + " 进程id: " + Thread.currentThread().getName(), LogLevel.DEBUG);
        Object result;
        // 处理 [in] 参数
        Object[] sqlParams = null;
        Map<String, Object> processed = processInParams(sql, sqlParamsMap);
        if (processed != null) {
            sql = (String) processed.get("sql");
            sqlParams = (Object[]) processed.get("params");
        } else if (sqlParamsMap != null) {
            // 如果没有处理，则从 map 构建参数数组
            sqlParams = new Object[sqlParamsMap.size()];
            int i = 0;
            for (String key : sqlParamsMap.keySet()) {
                sqlParams[i] = sqlParamsMap.get(key);
                i++;
            }
        }
        try {
            result = execSql( conn , runner, sql , action , rsh , sqlParams);
            if(msg.parseBoolean(DB_P_IFCLOSECONNECTION,true) ==true)
                conn.close();
            else
                returnMsg.setParam(DB_R_CONN,conn);
        } catch (SQLException e) {
            if(msg.parseBoolean(DB_P_IFCLOSECONNECTION,true) ==true)
            {
                try {
                    conn.close();
                } catch (SQLException e1) {
                    e1.printStackTrace();
                }
            }
            else
                returnMsg.setParam(DB_R_CONN,conn);
            e.printStackTrace();
            return returnMsg.setParam(RESULT, false);
        }
        if( cacheKey !=null && action.equals(DB_QUERY))
        {
            int exptime = msg.getIntParam(DB_P_CACHEEXPTIME,cacheExptime);
            cacheModule.writeCache(cacheName,cacheKey, result,  exptime,cacheValueType);
        }
        returnMsg .setParam(DB_R_RESULT, result);
        return returnMsg;
    }
    /**
     * 处理 SQL 中的 [in] 参数，返回处理后的 SQL 和参数数组
     * @param sql 原始 SQL
     * @param paramsMap 参数 Map
     * @return 包含处理后的 SQL 和参数数组的 Map，或 null
     */
    private Map<String, Object> processInParams(String sql, Map<String, Object> paramsMap) {
        if (sql == null || paramsMap == null || paramsMap.isEmpty()) {
            return null;
        }
        boolean hasIn = false;
        for (String key : paramsMap.keySet()) {
            if (key.indexOf("[in]") >= 0) {
                hasIn = true;
                break;
            }
        }
        if (!hasIn) return null;

        StringBuilder sqlBuilder = new StringBuilder(sql);
        List<Object> paramList = new ArrayList<>();
        for (Map.Entry<String, Object> entry : paramsMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key.indexOf("[in]") >= 0) {
                if (value instanceof Collection) {
                    Collection<?> coll = (Collection<?>) value;
                    if (coll.isEmpty()) {
                        int idx = sqlBuilder.indexOf(key);
                        if (idx != -1) sqlBuilder.replace(idx, idx + key.length(), "1=0");
                    } else {
                        String placeholders = String.join(",", Collections.nCopies(coll.size(), "?"));
                        int idx = sqlBuilder.indexOf(key);
                        if (idx != -1) {
                            sqlBuilder.replace(idx, idx + key.length(), placeholders);
                            paramList.addAll(coll);
                        }
                    }
                } else if (value instanceof Object[]) {
                    Object[] arr = (Object[]) value;
                    if (arr.length == 0) {
                        int idx = sqlBuilder.indexOf(key);
                        if (idx != -1) sqlBuilder.replace(idx, idx + key.length(), "1=0");
                    } else {
                        String placeholders = String.join(",", Collections.nCopies(arr.length, "?"));
                        int idx = sqlBuilder.indexOf(key);
                        if (idx != -1) {
                            sqlBuilder.replace(idx, idx + key.length(), placeholders);
                            paramList.addAll(Arrays.asList(arr));
                        }
                    }
                } else {
                    int idx = sqlBuilder.indexOf(key);
                    if (idx != -1) {
                        sqlBuilder.replace(idx, idx + key.length(), "?");
                        paramList.add(value);
                    }
                }
            } else {
                paramList.add(value);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sql", sqlBuilder.toString());
        result.put("params", paramList.toArray());
        return result;
    }
    private Object execSql(Connection conn ,QueryRunner runner,String sql ,String action ,ResultSetHandler rsh ,Object[] sqlParams) throws SQLException {
        if( action ==null ){
            if (sqlParams == null)
                return  runner.execute(conn,sql);
            else
                return runner.execute(conn,sql, sqlParams);
        }
       else {
            if( action.equals(DB_QUERY))
            {
                if (sqlParams == null)
                    return  runner.query(conn, sql, rsh);
                else
                    return runner.query(conn, sql, rsh, sqlParams);
            }
            else if(action.equals(DB_UPDATE) || action.equals(DB_INSERT))
            {
                if (sqlParams == null)
                    return  runner.update(conn,sql);
                else
                    return runner.update(conn,sql, sqlParams);
            }
            else {
                if (sqlParams == null)
                    return  runner.execute(conn,sql);
                else
                    return runner.execute(conn,sql, sqlParams);
            }
        }
     }

    private TLMsg getViewParams(Object fromWho, TLMsg msg) {
        String viewName = (String) msg.getParam("viewName");
        HashMap<String, String> tparams = views.get(viewName);
        if (tparams != null)
        {
            tparams.put("defaultDBserver", params.get("defaultDBserver"));
            return createMsg().addMap(tparams);
        }
        else
            return createMsg().setParam(RESULT,false) ;
    }

    private TLMsg getTableParams(Object fromWho, TLMsg msg) {
        String tableName = (String) msg.getParam(DB_P_TABLENAME);
        HashMap<String, String> tparams = getTableParam(tableName,msg);
        tparams.put("defaultDBserver", params.get("defaultDBserver"));
        return createMsg().addMap(tparams);
    }

    private TLMsg getDbTriggerParams(Object fromWho, TLMsg msg) {

        return createMsg().addMap(triggers.get((String) msg.getParam("triggerName")));
    }

    private TLMsg getDbserverParams(Object fromWho, TLMsg msg) {
        return createMsg().addMap(dbservers.get((String) msg.getParam("serverName")));
    }
    private TLMsg getServer(Object fromWho, TLMsg msg) {
        String serverName = (String) msg.getParam(DB_P_SERVERNAME );
        TLBaseModule serverobj = getServer(serverName);
        return createMsg().setParam(INSTANCE, serverobj);
    }
    private TLBaseModule getServer(String serverName){
        TLBaseModule serverobj = (TLBaseModule) dbObjs.get(prefixServer+serverName);
        if (serverobj != null)
            return serverobj;
        HashMap<String, String> config = dbservers.get(serverName);
       return createDbServer(serverName,config);
    }

    private TLMsg getBeanTable(Object fromWho, TLMsg msg) {
        String tablename = msg.getStringParam(DB_P_TABLENAME,"");
        if(tablename == null || tablename.isEmpty())
            return createMsg().setParam(INSTANCE, null);
        TLBaseModule tableobj = (TLBaseModule) dbObjs.get(prefixBeanTable+tablename);
        if (tableobj != null)
            return createMsg().setParam(INSTANCE, tableobj);
        HashMap<String, String> tableparams =new HashMap<>();
        tableparams.put(DB_P_PRIMARYKEY, msg.getStringParam(DB_P_PRIMARYKEY,null));
        tableparams.put(DB_P_IFPRIMARYKEYAUTO, msg.getStringParam(DB_P_IFPRIMARYKEYAUTO,null));
        tableparams.put("database", name);
        synchronized (BeanTable.class)
        {
            tableobj = (TLBaseModule) dbObjs.get(prefixBeanTable+tablename);
            if (tableobj != null)
                return createMsg().setParam(INSTANCE, tableobj);
            tableobj = (TLBaseModule) getNewModule(tablename,"beanTable",tableparams);
            if(tableobj !=null)
               dbObjs.put(prefixBeanTable+tablename, tableobj);
        }
        return createMsg().setParam(INSTANCE, tableobj);
    }

    private TLMsg getTable(Object fromWho, TLMsg msg) {
        String tablename =  msg.getStringParam(DB_P_TABLENAME,"");
        if(tablename.isEmpty())
            return createMsg().setParam(RESULT,false);
        TLBaseModule tableobj = getTable(tablename, msg);
        return createMsg().setParam(INSTANCE, tableobj);
    }
    private TLBaseModule getTable( String tablename, TLMsg msg){
        TLBaseModule tableobj = (TLBaseModule) dbObjs.get(prefixTable+tablename);
        if (tableobj != null)
            return tableobj;
        HashMap<String, String> tableparams =  getTableParam(tablename,msg);
        tableobj = makeTable(tablename, tableparams);
        return tableobj;
    }
    private HashMap<String, String>  getTableParam(String tablename,TLMsg msg){
        HashMap<String, String> tableparams = tables.get(tablename);
        if (tableparams == null && msg !=null)
        {
            if (msg.containsParam(DB_P_COPYTABLE)) {
                tableparams = copyTable(tablename, (String) msg.getParam(DB_P_COPYTABLE), (String) msg.getParam(DB_DBSERVER));
            }
        }
        if (tableparams == null)
            return  new HashMap<>();
        String sameAsTable = tableparams.get("sameAsTable");
        if (sameAsTable != null && !sameAsTable.isEmpty()) {
            HashMap<String, String> sameParams = tables.get(sameAsTable);
            for (String key : sameParams.keySet())
                tableparams.putIfAbsent(key, sameParams.get(key));
        }
        return tableparams ;
    }
    private TLBaseModule makeTable(String tablename, HashMap<String, String> tableparams) {
        String sameClassAs = tableparams.get(MODULE_SameClassAs);
        TLBaseModule tableobj ;
        if(sameClassAs != null && sameClassAs.indexOf("@") >0)
        {
            String[] array =TLDataUtils.splitStrToArray(sameClassAs,"@");
            if(array.length !=3)
                return null;
            TLMsg msg =createMsg().setAction(DB_GETTABLE).setParam(DB_P_TABLENAME,array[0]);
            String database =array[1]+"@"+array[2];
            TLMsg returnMsg =putMsg(database,msg);
            tableobj = (TLBaseModule) returnMsg.getParam(INSTANCE);
        }
        else
        {
            if (sameClassAs != null && !sameClassAs.isEmpty())
                sameClassAs = addPackage(sameClassAs);
            else
                sameClassAs = DB_DBTABLEMODULENAME;
            tableparams.put("defaultDBserver", params.get("defaultDBserver"));
            tableparams.putIfAbsent("database", name);
            tableobj = (TLBaseModule) getNewModule(tablename,sameClassAs,tableparams);
        }
        if(tableobj ==null)
        {
            putLog("make table Module is error:"+tablename,LogLevel.ERROR,"makeTable");
            return null ;
        }
        dbObjs.put(prefixTable+tablename, tableobj);
        addTrigger(tableobj, tableparams);
        return tableobj;
    }

    private HashMap<String, String> copyTable(String tablename, String copyTable, String dbserver) {
        HashMap<String, String> tableparams = new HashMap<>();
        tableparams.putAll(tables.get(copyTable));
        tableparams.put("dbtable", tablename);
        tables.put(tablename, tableparams);
        if (dbserver == null)
            dbserver = tableparams.get("dbserver");
        boolean result = createDBtableFromCopy(tablename, copyTable, dbserver);
        if (result == true)
            return tableparams;
        else
            return null;
    }

    private void addTrigger(TLBaseModule tbobj, HashMap<String, String> viewparams) {
        if (viewparams == null)
            return;
        for (String key : triggerName.keySet()) {
            String triggersStr = viewparams.get(key);
            if (triggersStr != null && !triggersStr.isEmpty()) {
                String[] triggersNames = triggersStr.split(";");
                HashMap<String, String> tparams;
                for (int n = 0; n < triggersNames.length; n++) {
                    String trigger = triggersNames[n].trim();
                    tparams = triggers.get(trigger);
                    if (tparams != null && !tparams.isEmpty())
                        addTriggerMstTable(tbobj, trigger, triggerName.get(key), tparams);
                }
            }
        }
    }

    private void addTriggerMstTable(TLBaseModule tbobj, String triggerName, String addTriggerAction, HashMap<String, String> tparams) {
        String[] actions = {"delete", "update", "insert", "query", "batch","all"};
        for (int i = 0; i < actions.length; i++)
        {
            String onAction =actions[i] ;
            String trigAction =tparams.get(onAction) ;
            if (trigAction != null && !trigAction.isEmpty())
            {
               if(onAction.equals("all"))
                   onAction = "*";
                insertTriggerMsgTable(tbobj, triggerName, addTriggerAction, onAction, trigAction);
            }
        }
    }

    private void insertTriggerMsgTable(TLBaseModule tbobj, String triggerName, String addTriggerAction, String onAction, String trigAction) {
        Object triggerObj = dbObjs.get(prefixTrigger+triggerName);
        if (triggerObj == null) {
            HashMap<String, String> tparams = triggers.get(triggerName);
            tparams.putIfAbsent("database", name);
            tparams.putIfAbsent("tableName", tbobj.getName());
            String classFile = tparams.get("classfile");
            if (classFile != null && !classFile.isEmpty())
                classFile = addPackage(classFile);
            triggerObj = getModule( prefixTrigger+triggerName,classFile,false,false,tparams);
            dbObjs.put(prefixTrigger+triggerName, triggerObj);
        }
        TLMsg trmsg = TLMsgUtils.strToMsg(trigAction).setDestination(prefixTrigger+triggerName).setSystemParam(IFLOADMODULE ,false);
        TLMsg bmsg = createMsg().setAction(addTriggerAction).setParam("action", onAction).setParam("msg", trmsg);
        putMsg(tbobj, bmsg);
        TLMsg addModuleMsg =createMsg().setAction(ADDMODULE).setParam(MODULENAME,prefixTrigger+triggerName).setParam(INSTANCE,triggerObj);
        putMsg(tbobj, addModuleMsg);
    }

    private TLMsg getViews(Object fromWho, TLMsg msg) {
        String viewName = (String) msg.getParam(DB_P_VIEWNAME);
        TLBaseModule viewobj = (TLBaseModule) dbObjs.get(prefixView+viewName);
        if (viewobj == null) {
            HashMap<String, String> viewparams = views.get(viewName);
            String sameClassAs = viewparams.get(MODULE_SameClassAs);
            if(sameClassAs != null && sameClassAs.indexOf("@") >0)
            {
                String[] array =TLDataUtils.splitStrToArray(sameClassAs,"@");
                if(array.length !=3)
                    return null;
                TLMsg vmsg =createMsg().setAction(DB_GETVIEW).setParam(DB_P_VIEWNAME,array[0]);
                String database =array[1]+"@"+array[2];
                TLMsg returnMsg =putMsg(database,vmsg);
                viewobj = (TLBaseModule) returnMsg.getParam(INSTANCE);
            }
            else
            {
                viewparams.putIfAbsent("database", name);
                viewobj = (TLBaseModule) getNewModule(viewName,DB_DBTVIEWMODULENAME,viewparams);
            }
            dbObjs.put(prefixView+viewName, viewobj);
            addTrigger(viewobj, viewparams);
        }
        return createMsg().setParam(INSTANCE, viewobj);
    }

    protected TLMsg getConnection(Object fromWho, TLMsg msg) {
        String dbserver = (String) msg.getParam(DB_P_SERVERNAME);
        String readserver = null;
        if (dbserver == null || dbserver.isEmpty()) {
            String tablename = (String) msg.getParam(DB_P_TABLENAME);
            HashMap<String, String> tableparams;
            if (tablename != null)
                tableparams = tables.get(tablename);
            else {
                String viewName = (String) msg.getParam(DB_P_VIEWNAME);
                tableparams = views.get(viewName);
            }
            if (tableparams == null || tableparams.get("dbserver") == null)
                dbserver = params.get("defaultDBserver");
            else {
                dbserver = tableparams.get("dbserver");
            }
            if (tableparams != null)
                readserver = tableparams.get("readserver");
        }
        String connType = (String) msg.getParam("connType");
        Connection connection;
        if (connType == null)
            connection = getConnection(dbserver);
        else {
            if (readserver != null) {
                connection = getConnection(readserver);
                dbserver = readserver;
            } else
                connection = getConnection(dbserver);
        }
        return createMsg().setParam(DB_R_CONN, connection)
                .setParam("dbserver", dbserver).setParam("connType", connType);

    }

    private Connection getConnection(String dbserver) {
        TLBaseModule serverModule =getServer(dbserver);
        return (Connection) putMsg(serverModule, createMsg().setAction(DB_GETCONN))
                .getParam(DB_R_CONN);
    }
    static public String makeCacheKey(String sql, Map<String,Object> sqlParams  ){
       String cacheKey =String.valueOf(sql.hashCode());
        StringBuilder strBuffer = new StringBuilder();
        if(sqlParams !=null)
            for(String key :sqlParams.keySet())
            {
                strBuffer.append(key) ;
                Object value =sqlParams.get(key);
                if(value !=null)
                    strBuffer.append(String.valueOf(value)) ;
            }
       cacheKey =cacheKey+ String.valueOf( strBuffer.toString().hashCode());
       return cacheKey ;
    }

    static public TLTable  getTable(String tableName,TLBaseModule caller){
        TLMsg tmsg = caller.createMsg().setAction(DB_GETTABLE).setParam(DB_P_TABLENAME,tableName);
        TLMsg returnmsg =caller.putMsg(DEFAULTDATABASE,tmsg);
        return (TLTable) returnmsg.getParam(INSTANCE,TLTable.class);
    }

    static public BeanTable  getBeanTable(String tableName,String primaryKey,TLBaseModule caller){
        TLMsg tmsg = caller.createMsg().setAction(DB_GETBEANTABLE).
                setParam(DB_P_TABLENAME,tableName)
                .setParam(DB_P_PRIMARYKEY,primaryKey) ;
        TLMsg returnmsg =caller.putMsg(DEFAULTDATABASE,tmsg);
        return (BeanTable) returnmsg.getParam(INSTANCE,BeanTable.class);
    }

    static public String  getTableDbName(String tableName,TLBaseModule caller){
        TLMsg msg = new TLMsg().setAction(DB_GETTABLEPARAMS).setParam(DB_P_TABLENAME,tableName);
        TLMsg returnMsg= caller.putMsg(DEFAULTDATABASE, msg);
        if (returnMsg !=null)
            return (String) returnMsg.getParam("dbtable");
        else
            return null ;
    }
    static public String  getTableServer(String tableName,TLBaseModule module,String type){
        TLMsg msg = new TLMsg().setAction(DB_GETTABLEPARAMS).setParam(DB_P_TABLENAME,tableName);
        TLMsg returnMsg= module.putMsg(DEFAULTDATABASE, msg);
        if (returnMsg ==null)
            return null ;
        String server ;
        if(type !=null && type.equals("read"))
            server= (String) returnMsg.getParam("readserver");
        else
            server= (String) returnMsg.getParam("dbserver");
        if(server ==null)
           server = (String) returnMsg.getParam("defaultDBserver");
        return server ;
    }

    static public  RESULT_TYPE getResultType(Object resultType){
        TLDatabase.RESULT_TYPE dbType ;
        if (resultType == null)
            dbType = TLDatabase.RESULT_TYPE.MAPLIST;
        else if (resultType instanceof String)
            dbType = TLDatabase.RESULT_TYPE.valueOf(((String) resultType).toUpperCase());
        else if (resultType instanceof TLDatabase.RESULT_TYPE)
            dbType = (TLDatabase.RESULT_TYPE) resultType;
        else
            dbType = TLDatabase.RESULT_TYPE.MAPLIST;
        return dbType ;
    }
    static public  ResultSetHandler  getResultSetHandler(  TLDatabase.RESULT_TYPE dbType,TLMsg msg){
        String handerkey = null;
        if (dbType == TLDatabase.RESULT_TYPE.KEYED)
            handerkey = (String) msg.getParam(DB_P_HANDERKEY);
        ResultSetHandler rsh = getResultSetHandler(dbType, handerkey);
        if (rsh == null) {
            Class<?> beanClass = (Class<?>) msg.getParam(DB_P_BEANCLASS);
            rsh = getResultSetHandler(dbType, beanClass,msg.getStringParam(DB_P_PRIMARYKEY,null));
        }
        return  rsh ;
    }
    static public ResultSetHandler getResultSetHandler(RESULT_TYPE resultType, String key) {
        switch (resultType) {
            case ARRAY:
                return new ArrayHandler();
            case ARRAYLIST:
                return new ArrayListHandler();
            case KEYED:
                return new KeyedHandler(key);
            case MAPLIST:
                return new MapListHandler();
            case MAP:
                return new MapHandler();
            default:
               return null;
        }
    }
    static public ResultSetHandler getResultSetHandler(RESULT_TYPE resultType, Class beanClass,String primarykey ) {
        switch (resultType) {
            case BEAN:
                return new BeanHandler(beanClass);
            case BEANLIST:
                return new BeanListHandler(beanClass);
            case BEANMAP:
                if(primarykey !=null)
                    return new BeanMapHandler(beanClass,primarykey);
                else
                    return new BeanMapHandler(beanClass);
            default:
                 return  null ;
        }
    }
    protected String addPackage(String name) {
        if (dbPackageName == null)
            return name;
        String firstCha = (String) name.subSequence(0, 1);
        if (firstCha.equals(".")) {
            return dbPackageName + name;
        } else
            return name;
    }

    public enum RESULT_TYPE {
        ARRAY, ARRAYLIST, KEYED, MAP, MAPLIST ,BEAN,BEANLIST ,BEANMAP
    }

    protected class myConfig extends TLModuleConfig {
        protected HashMap<String, HashMap<String, String>> dbservers;
        protected HashMap<String, HashMap<String, String>> tables;
        protected HashMap<String, HashMap<String, String>> views;
        protected HashMap<String, HashMap<String, String>> triggers;

        public myConfig() {

        }

        public myConfig(String configFile, String configDir) {
            super(configFile,configDir);
        }

        public HashMap getDBservers() {
            return dbservers;
        }

        public HashMap getTables() {
            return tables;
        }

        public HashMap getViews() {
            return views;
        }

        public HashMap getTriggers() {
            return triggers;
        }

        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if (xpp.getName().equals("dbservers")) {
                    dbservers = getHashMap(xpp, "dbservers", "dbserver");
                }
                if (xpp.getName().equals("tables")) {
                    tables = getHashMap(xpp, "tables", "table");
                }
                if (xpp.getName().equals("views")) {
                    views = getHashMap(xpp, "views", "view");
                }
                if (xpp.getName().equals("triggers")) {
                    triggers = getHashMap(xpp, "triggers", "trigger");
                }
            } catch (Throwable t) {

            }
        }

    }

}
