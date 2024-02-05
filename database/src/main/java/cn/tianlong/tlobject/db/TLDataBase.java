package cn.tianlong.tlobject.db;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.db.dbdata.BeanTable;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;
import cn.tianlong.tlobject.utils.TLMsgUtils;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;
import org.apache.commons.dbutils.handlers.*;
import org.xmlpull.v1.XmlPullParser;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;


/**
 * 创建日期：2018/3/21 on 8:48
 * 描述:
 * 作者:tianlong
 */

public class TLDataBase extends TLBaseModule {
    protected String dbPackageName;
    private final static String prefixTable = "table_";
    private final static String prefixBeanTable = "bean_";
    private final static String prefixMapTable = "map_";
    private final static String prefixTrigger = "trigger_";
    private final static String prefixView = "view_";
    private final static String prefixServer = "server_";
    protected HashMap<String, HashMap<String, String>> dbservers;
    protected HashMap<String, HashMap<String, String>> tables;
    protected HashMap<String, HashMap<String, String>> views;
    protected HashMap<String, HashMap<String, String>> triggers;
    protected HashMap<String, Object> dbObjs = new HashMap<>();
    protected HashMap<String, String> triggerName = new HashMap<>();

    public TLDataBase() {
        super();
    }

    public TLDataBase(String name) {
        super(name);
    }

    public TLDataBase(String name, TLObjectFactory modulefactory) {
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
        String proxyModule= (config !=null )?config.get(MODULE_PROXYMODULE):DEFAULTDBSERVERMODULE;
        TLBaseModule serverobj;
        if(proxyModule.indexOf("@") >0){
            String[] array =TLDataUtils.splitStrToArray(proxyModule,"@");
            if(array.length !=3)
                return null;
            TLMsg msg =createMsg().setAction(DB_GETSERVER).setParam(DB_P_SERVERNAME,array[0]);
            String database =array[1]+"@"+array[2];
            TLMsg returnMsg =putMsg(database,msg);
            serverobj = (TLBaseModule) returnMsg.getParam(INSTANCE);
        }
        else
            serverobj =  (TLBaseModule)getModule( serverName,proxyModule,false,false,config) ;
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
        for(String tableName :tables.keySet()){
            HashMap<String, String> config =tables.get(tableName) ;
            if(config.get("statup")!=null && Boolean.parseBoolean(config.get("statup"))==true)
            {
                HashMap<String, String> tableparams = tables.get(tableName);
                TLBaseModule tableModule =  makeTable(tableName,tableparams);
                if(tableModule ==null)
                    return false ;
            }
        }
        return true ;
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
            case DB_CREATEDBTABLE:
                returnMsg = createDBTable(fromWho, msg);
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
            case DB_EXECSQL:
                returnMsg = execSql(fromWho, msg);
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
        String tableName = (String) msg.getParam(DB_P_TABLENAME);
        String dbserver = (String) msg.getParam(DB_DBSERVER);
        if (dbserver == null)
        {
            if(tables.containsKey(tableName))
            {
                HashMap<String, String> tableparams = tables.get(tableName);
                dbserver = tableparams.get("dbserver");
                if(dbserver==null)
                    dbserver = params.get("defaultDBserver");
                else
                    dbserver = params.get("defaultDBserver");
            }
            else
                dbserver = params.get("defaultDBserver");
        }
        else
            dbserver = params.get("defaultDBserver");
        Connection conn = getConnection(dbserver);
        if (conn == null) {
            putLog("数据库没有连接", LogLevel.ERROR);
            return createMsg().setParam(RESULT,false);
        }
        DatabaseMetaData meta = null;
        boolean result =false ;
        try {
            meta = conn.getMetaData();
            java.sql.ResultSet tables = meta.getTables (null, null, tableName, null);
            if (tables.next())
            {
                result = true;
                conn.close();
            }
        } catch (SQLException e) {
            return createMsg().setParam(RESULT,false);
        }
        if(result ==true)
            return createMsg().setParam(RESULT,true);
        String sql =msg.getStringParam(DB_P_SQL,null);
        if(sql ==null)
            return createMsg().setParam(RESULT,result);
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

    private TLMsg startTranscation(Object fromWho, TLMsg msg) throws SQLException {
        ArrayList<TLMsg> msgList = (ArrayList<TLMsg>) msg.getParam(DB_P_MSGLIST);
        ArrayList<Connection> connections= new ArrayList<>();
        for(int i = 0 ; i< msgList.size() ; i ++)
        {
            TLMsg uMsg = msgList.get(i);
            TLMsg tMsg =new TLMsg().copyFrom(uMsg);
            if(tMsg.isNull(DB_P_TABLENAME))
            {
                putLog("no tableName",LogLevel.ERROR,"startTranscation");
                return createMsg().setParam(RESULT,false) ;
            }
            TLMsg tableMsg =getTable(this,tMsg);
            TLBaseModule table = (TLBaseModule) tableMsg.getParam(INSTANCE);
            tMsg.setParam(DB_P_IFTRANSACTION,true) ;
            tMsg.setParam(DB_P_IFCLOSECONNECTION,false) ;
            tMsg.removeParam(DB_P_TABLENAME);
            TLMsg returnMsg =putMsg(table, tMsg);
            if(returnMsg.parseBoolean(RESULT,true)==false)
            {
                trancsationRollbak(0,connections);
                return createMsg().setParam(RESULT,false).setParam("number",i);
            }
            Connection connection = (Connection) returnMsg.getParam(DB_R_CONN);
            if(!connections.contains(connection))
                connections.add(connection);
        }
        for(int i = 0 ; i< connections.size() ; i ++)
        {
            Connection conn=connections.get(i);
            try {
                conn.commit();
            } catch (Exception e) {
                putLog("transcation is error",LogLevel.ERROR,"startTranscation");
                trancsationRollbak(i,connections);
                return createMsg().setParam(RESULT,false).setParam("number",i);
            }
            conn.close();
        }
        return createMsg().setParam(RESULT,true);
    }

    private void trancsationRollbak(int i , ArrayList<Connection> connections){
        if(i ==connections.size())
            return;
        Connection conn =connections.get(i);
        if(conn ==null)
            return;
        try {
            conn.rollback();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        try {
            conn.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        if(i ==connections.size()-1)
            return;
        i=i+1;
        trancsationRollbak(i ,connections);
    }

    private String selectDbServer(TLMsg msg){
        String dbserver = (String) msg.getParam(DB_P_SERVERNAME);
        if (dbserver == null || dbserver.isEmpty())
            dbserver = params.get("defaultDBserver");
        return dbserver ;
    }
    private TLMsg createDBTable(Object fromWho, TLMsg msg) {
        String copyTable = (String) msg.getParam(DB_P_COPYTABLE);
        String dbserver = (String) msg.getParam(DB_DBSERVER);
        if (dbserver == null) {
            HashMap<String, String> tableparams = tables.get(copyTable);
            dbserver = tableparams.get("dbserver");
        }
        Boolean result = createDBtableFromCopy((String) msg.getParam(DB_P_TABLENAME), copyTable, dbserver);
        return createMsg().setParam(DB_R_RESULT, result);
    }

    private Boolean createDBtableFromCopy(String tablename, String copyTable, String dbserver) {
        Connection rconn = getConnection(dbserver);
        if (rconn == null) {
            putLog("数据库没有连接", LogLevel.ERROR);
            return false;
        }
        String sql = "CREATE  TABLE IF NOT EXISTS " + tablename + " LIKE " + copyTable;
        Boolean result = false;
        try {
            CallableStatement proc = rconn.prepareCall(sql);
            proc.execute();
            result = true;
            rconn.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return result;
    }

    private TLMsg execSql(Object fromWho, TLMsg msg) {
        String dbserver =selectDbServer(msg);
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
        Object resultType = msg.getParam(DB_P_RESULTTYPE);
        RESULT_TYPE dbType =getResultType(resultType);
        ResultSetHandler rsh = getResultSetHandler(dbType,msg);
        if (rsh == null) {
            putLog("ResultSetHandler is wrong :" +  msg.getParam(DB_P_RESULTTYPE), LogLevel.WARN, "query");
            return createMsg().setParam(RESULT, false);
        }
        String sqlType = (String) msg.getParam(DB_P_SQLTYPE);
        QueryRunner runner = new QueryRunner();
        LinkedHashMap<String, Object> sqlParamsList = (LinkedHashMap<String, Object>) msg.getParam("params");
        String sql = (String) msg.getParam(DB_P_SQL);
        putLog(sql + " 进程id: " + Thread.currentThread().getName(), LogLevel.DEBUG);
        Object result = null;
        if (sqlParamsList == null)
        {
            try {
                result = execSql( conn , runner, sql , sqlType , rsh , null);
                if(msg.parseBoolean(DB_P_IFCLOSECONNECTION,true) ==true)
                    conn.close();
            } catch (SQLException e) {
                if(msg.parseBoolean(DB_P_IFCLOSECONNECTION,true) ==true) {
                    try {
                        conn.close();
                    } catch (SQLException e1) {
                        e1.printStackTrace();
                    }
                }
                e.printStackTrace();
                return createMsg().setParam(RESULT, false).setParam(DB_R_CONN,conn);
            }
            return createMsg().setParam(DB_R_RESULT, result).setParam(DB_R_CONN,conn);
        }
        Object[] sqlParams = new Object[sqlParamsList.size()];
        int i = 0;
        for (String key1 : sqlParamsList.keySet()) {
            sqlParams[i] = sqlParamsList.get(key1);
            i++;
        }
        try {
            result = execSql( conn , runner, sql , sqlType , rsh , sqlParams);
            if(msg.parseBoolean(DB_P_IFCLOSECONNECTION,true) ==true)
                conn.close();
        } catch (SQLException e) {
            if(msg.parseBoolean(DB_P_IFCLOSECONNECTION,true) ==true)
            {
                try {
                    conn.close();
                } catch (SQLException e1) {
                    e1.printStackTrace();
                }
            }
            e.printStackTrace();
            return createMsg().setParam(RESULT, false).setParam(DB_R_CONN,conn);
        }
        TLMsg returnMsg = createMsg().setParam(DB_R_RESULT, result).setParam(DB_R_CONN,conn);
        return returnMsg;
    }

    private Object execSql(Connection conn ,QueryRunner runner,String sql ,String sqlType ,ResultSetHandler rsh ,Object[] sqlParams) throws SQLException {
        if( sqlType ==null ){
            if (sqlParams == null)
                return  runner.execute(conn,sql);
            else
                return runner.execute(conn,sql, sqlParams);
        }
       else {
            if( sqlType.equals(DB_QUERY))
            {
                if (sqlParams == null)
                    return  runner.query(conn, sql, rsh);
                else
                    return runner.query(conn, sql, rsh, sqlParams);
            }
            else if(sqlType.equals(DB_UPDATE) || sqlType.equals(DB_INSERT))
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
        String tablename = (String) msg.getParam(DB_P_TABLENAME);
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
        String tablename = (String) msg.getParam(DB_P_TABLENAME);
        TLBaseModule tableobj = (TLBaseModule) dbObjs.get(prefixTable+tablename);
        if (tableobj != null)
            return createMsg().setParam(INSTANCE, tableobj);
        HashMap<String, String> tableparams =  getTableParam(tablename,msg);
        tableobj = makeTable(tablename, tableparams);
        return createMsg().setParam(INSTANCE, tableobj);
    }
    private HashMap<String, String>  getTableParam(String tablename,TLMsg msg){
        HashMap<String, String> tableparams = tables.get(tablename);
        if (tableparams == null) {
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
        String proxyModule = tableparams.get(MODULE_PROXYMODULE);
        TLBaseModule tableobj ;
        if(proxyModule != null && proxyModule.indexOf("@") >0)
        {
            String[] array =TLDataUtils.splitStrToArray(proxyModule,"@");
            if(array.length !=3)
                return null;
            TLMsg msg =createMsg().setAction(DB_GETTABLE).setParam(DB_P_TABLENAME,array[0]);
            String database =array[1]+"@"+array[2];
            TLMsg returnMsg =putMsg(database,msg);
            tableobj = (TLBaseModule) returnMsg.getParam(INSTANCE);
        }
        else
        {
            if (proxyModule != null && !proxyModule.isEmpty())
                proxyModule = addPackage(proxyModule);
            else
                proxyModule = DB_DBTABLEMODULENAME;
            tableparams.put("defaultDBserver", params.get("defaultDBserver"));
            tableparams.putIfAbsent("database", name);
            tableobj = (TLBaseModule) getNewModule(tablename,proxyModule,tableparams);
        }
        if(tableobj ==null)
            return null ;
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
        String[] actions = {"delete", "update", "insert", "query", "batch"};
        for (int i = 0; i < actions.length; i++) {
            if (tparams.get(actions[i]) != null) {
                insertTriggerMsgTable(tbobj, triggerName, addTriggerAction, actions[i], tparams.get(actions[i]));
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
            String proxyModule = viewparams.get(MODULE_PROXYMODULE);
            if(proxyModule != null && proxyModule.indexOf("@") >0)
            {
                String[] array =TLDataUtils.splitStrToArray(proxyModule,"@");
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
    static public String  getTableDbName(String tableName,TLBaseModule module){
        TLMsg msg = new TLMsg().setAction(DB_GETTABLEPARAMS).setParam(DB_P_TABLENAME,tableName);
        TLMsg returnMsg= module.putMsg(DEFAULTDATABASE, msg);
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
        TLDataBase.RESULT_TYPE dbType ;
        if (resultType == null)
            dbType = TLDataBase.RESULT_TYPE.MAPLIST;
        else if (resultType instanceof String)
            dbType = TLDataBase.RESULT_TYPE.valueOf(((String) resultType).toUpperCase());
        else if (resultType instanceof TLDataBase.RESULT_TYPE)
            dbType = (TLDataBase.RESULT_TYPE) resultType;
        else
            dbType = TLDataBase.RESULT_TYPE.MAPLIST;
        return dbType ;
    }
    static public  ResultSetHandler  getResultSetHandler(  TLDataBase.RESULT_TYPE dbType,TLMsg msg){
        String handerkey = null;
        if (dbType == TLDataBase.RESULT_TYPE.KEYED)
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
