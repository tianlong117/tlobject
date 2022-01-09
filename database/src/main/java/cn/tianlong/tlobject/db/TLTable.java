package cn.tianlong.tlobject.db;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static cn.tianlong.tlobject.db.TLDataBase.getResultSetHandler;


/**
 * 创建日期：2018/3/21 on 8:48
 * 描述:
 * 作者:tianlong
 */

public class TLTable extends TLBaseDataUnit {
    protected boolean ifGetStructure = true;
    protected List<ColumnModel> columnModelList;
    protected TLBaseDataUnit deleteBackUpTable;

    public TLTable() {
        super();
    }

    public TLTable(String name) {
        super(name);
    }

    public TLTable(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        if (params.get("ifGetStructure") != null)
            ifGetStructure = Boolean.parseBoolean(params.get("ifGetStructure"));
        if (params.get("deleteBackUpTable") != null) {
            TLMsg tmsg = createMsg().setAction("getTable").setParam("tableName", params.get("deleteBackUpTable"));
            TLMsg returnmsg = putMsg(database, tmsg);
            deleteBackUpTable = (TLBaseDataUnit) returnmsg.getParam(TLObjectFactory.FACTORY_R_MODULEINSTANCE);
        }
    }

    @Override
    protected TLBaseModule init() {
        super.init();
        if (params.get("existInDb") != null && Boolean.parseBoolean(params.get("existInDb")) == false)  //表是否是实表，是否在数据库里存在
            return this;
        if (ifGetStructure) {
            columnModelList = getTableStructureFromDB();
            if (columnModelList == null)
                return null;
        }
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {

            case DB_GETCOLUMLIST:
                returnMsg = getColumList(fromWho, msg);
                break;
            case DB_DELETERTURN:
                returnMsg = deleteReturn(fromWho, msg);
                break;
            case DB_UPDATEUNIT:
                returnMsg = updateUnit(fromWho, msg);
                break;
            case DB_BATCh:
                returnMsg = batch(fromWho, msg);
                break;
            case DB_FIND:
                returnMsg = find(fromWho, msg);
                break;
            case DB_FINDALL:
                returnMsg = findAll(fromWho, msg);
                break;
            case DB_TOTAL:
                returnMsg = total(fromWho, msg);
                break;
            case DB_STARTTRANSACTION:
                returnMsg = startTransaction(fromWho, msg);
                break;
            default:
                returnMsg = super.checkMsgAction(fromWho, msg);
        }
        return returnMsg;
    }

    private TLMsg startTransaction(Object fromWho, TLMsg msg) {
        Connection conn = (Connection) getConnection(null);
        try {
             conn.setAutoCommit(false);
        } catch (SQLException e) {
            e.printStackTrace();
            return createMsg().setParam(RESULT, false);
        }
        ArrayList<TLMsg> msgList = (ArrayList<TLMsg>) msg.getParam(DB_P_MSGLIST);
        boolean   flag = false;
        try {
            for (TLMsg tMsg : msgList)
            {
                tMsg.setParam(DB_P_CONNECTION,conn) ;
                tMsg.setParam(DB_P_IFCLOSECONNECTION,false) ;
                TLMsg returnMsg =getMsg(fromWho, tMsg);
                if(returnMsg.parseBoolean(RESULT,true)==false)
               {
                   conn.rollback();
                   conn.close();
                   flag = false;
                   return createMsg().setParam(RESULT,flag);
               }
            }
            conn.commit();
            flag = true;
        } catch (Exception e) {
            e.printStackTrace();
            flag = false;
            try {
               conn.rollback();
            } catch (SQLException e1) {
                e1.printStackTrace();
            }
        } finally {
            try {
                conn.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return createMsg().setParam(RESULT,flag);
    }

    private TLMsg deleteReturn(Object fromWho, TLMsg msg) {
        msg.setParam(DB_P_RETURNRECORD, true);
        return delete(fromWho, msg);
    }

    protected TLMsg getColumList(Object fromWho, TLMsg msg) {
        return createMsg().setParam(DB_R_RESULT, columnModelList);
    }

    protected TLMsg updateUnit(Object fromWho, TLMsg msg) {
        String unitName = (String) msg.getParam(DB_P_FIELDNAME);
        String keyName = (String) msg.getParam(DB_P_KEYNAME);
        String idsql = "update  [table]  set " + unitName + " =?  where " + keyName + " =?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put(unitName, msg.getParam(unitName));
        sqlparams.put(keyName, msg.getParam(keyName));
        TLMsg updatemsg = createMsg().setAction(DB_UPDATE).setParam(DB_P_SQL, idsql)
                .setParam(DB_P_PARAMS, sqlparams);
        return getMsg(fromWho, updatemsg);
    }

    @Override
    protected void closeConnection(Object fromWho, TLMsg msg) {
        try {
            ((Connection) conn).close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        if (readconn != null) {
            try {
                ((Connection) readconn).close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    protected TLMsg find(Object fromWho, TLMsg msg) {
        LinkedHashMap<String, Object> params = (LinkedHashMap<String, Object>) msg.getParam(DB_P_PARAMS);
        Set<String> paramsSet = params.keySet();
        String[] paramsArray = paramsSet.toArray(new String[paramsSet.size()]);
        String condition;
        if (paramsArray.length == 1)
            condition = paramsArray[0] + " =? ";
        else {
            condition = StringUtils.join(paramsArray, " =? and ");
            condition = condition + " = ?";
        }
        Object fields = msg.getParam(DB_P_FIELDS);
        String queryfields = makeSqlField(fields);
        String sql = "select " + queryfields + " from [table] where " + condition;
        msg.setAction(DB_QUERY).setParam(DB_P_SQL, sql);
        return getMsg(fromWho, msg);
    }

    protected TLMsg batch(Object fromWho, TLMsg msg) {
        Connection wconn =getConnectionWithMsg(msg);
        if (wconn == null) {
            putLog("数据库没有连接", LogLevel.ERROR, "batch");
            return createMsg().setParam(RESULT,false);
        }
        String sql = (String) msg.getParam(DB_P_SQL);
        sql = sql.replace("[table]", dbtable);
        QueryRunner runner = new QueryRunner();
        Object[][] sqlParams = (Object[][]) msg.getParam(DB_P_PARAMS);
        int[] result = null;
        try {
            result = runner.batch(wconn, sql, sqlParams);
            connClose(wconn,msg);
        } catch (SQLException e) {
            putLog("batch error", LogLevel.ERROR);
            connClose(wconn,msg);
            return createMsg().setParam(RESULT,false);
        }
        return createMsg().setParam("result", result);
    }

    protected TLMsg total(Object fromWho, TLMsg msg) {
        String sql = "select count(*) as total from [table] ";
        TLMsg amsg = createMsg().copyFrom(msg).setAction("query").setParam("sql", sql);
        amsg.setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAP);
        TLMsg returnMsg = getMsg(fromWho, amsg);
        Map<String, Object> result = (Map<String, Object>) returnMsg.getParam(DB_R_RESULT);
        return createMsg().setParam(DB_R_RESULT, result.get("total"));
    }

    protected TLMsg findAll(Object fromWho, TLMsg msg) {
        String queryfields = " * ";
        Object fields = msg.getParam(DB_P_FIELDS);
        if (fields != null)
            queryfields = makeSqlField(fields);
        StringBuilder sb = new StringBuilder();
        sb.append("select ");
        sb.append(queryfields);
        sb.append(" from [table] ");
        String sql = sb.toString();
        TLMsg querymsg = createMsg().setAction(DB_QUERY)
                .addArgs(msg.getArgs())
                .setParam(DB_P_SQL, sql);
        return getMsg(fromWho, querymsg);
    }

    @Override
    protected TLMsg query(Object fromWho, TLMsg msg) {
        if (msg.getParam(DB_P_SQL) != null)
            return query(msg);
        LinkedHashMap<String, TLDBSqlConditionExpression> sqlconditon = (LinkedHashMap<String, TLDBSqlConditionExpression>) msg.getParam(DB_P_SQLCONDITION);
        if (sqlconditon == null)
            return null;
        String condition = makeSqlCondition(sqlconditon);
        Object fields = msg.getParam(DB_P_FIELDS);
        String queryfields = makeSqlField(fields);
        StringBuilder sb = new StringBuilder();
        sb.append("select ");
        sb.append(queryfields);
        sb.append(" from [table] ");
        sb.append(condition);
        String sql = sb.toString();
        LinkedHashMap<String, Object> sqlParams = (LinkedHashMap<String, Object>) msg.getParam(DB_P_PARAMS);
        if (sqlParams == null)
            sqlParams = new LinkedHashMap<>();
        for (String key : sqlconditon.keySet()) {
            TLDBSqlConditionExpression ce = sqlconditon.get(key);
            sqlParams.putIfAbsent(ce.getVarName(), ce.getValue());
        }
        msg.setAction(DB_QUERY).setParam(DB_P_SQL, sql).setParam(DB_P_PARAMS, sqlParams);
        return query(msg);
    }

    protected TLMsg query(TLMsg msg) {
       Connection rconn = (Connection) msg.getParam(DB_P_CONNECTION);
       if(rconn ==null){
           if (readconn == null)
               rconn = (Connection) getConnection("read");
           else
               rconn = (Connection) readconn;
       }
        if (rconn == null) {
            putLog("数据库没有连接", LogLevel.ERROR, DB_QUERY);
            return createMsg().setParam(RESULT,false);
        }
        String sql = (String) msg.getParam(DB_P_SQL);
        if (msg.getParam(DB_P_TABLENAME) != null)
            sql = sql.replace("[table]", (CharSequence) msg.getParam(DB_P_TABLENAME));
        else
            sql = sql.replace("[table]", dbtable);
        ResultSetHandler rsh = TLDataBase.getResultSetHandler(msg);
        if (rsh == null) {
            putLog("ResultSetHandler is wrong :" +  msg.getParam(DB_P_RESULTTYPE), LogLevel.WARN, "query");
            return createMsg().setParam(RESULT, false);
        }
        QueryRunner runner = new QueryRunner();
        LinkedHashMap<String, Object> sqlParamsList = (LinkedHashMap<String, Object>) msg.getParam(DB_P_PARAMS);
        putLog(sql, LogLevel.DEBUG, "query");
        Object result = null;
        if (sqlParamsList == null || sqlParamsList.isEmpty()) {
            try {
                result = runner.query(rconn, sql, rsh);
                readconnClose(rconn,msg);
            } catch (SQLException e) {
                readconnClose(rconn,msg);
                putLog(sql, LogLevel.ERROR, DB_QUERY);
                return createMsg().setParam(RESULT,false);
            }
        } else {
            Object[] sqlParams = new Object[sqlParamsList.size()];
            int i = 0;
            for (String key1 : sqlParamsList.keySet()) {
                if (key1.indexOf("[in]") >= 0) {
                    ArrayList<Object> indatas = (ArrayList) sqlParamsList.get(key1);
                    Object[] newParams = new Object[sqlParams.length + indatas.size() - 1];
                    System.arraycopy(sqlParams, 0, newParams, 0, sqlParams.length);
                    for (Object datas : indatas) {
                        newParams[i] = datas;
                        i++;
                    }
                    sqlParams = newParams;
                    sql = sql.replace(key1, makeQuestionMark(indatas.size()));
                } else {
                    sqlParams[i] = sqlParamsList.get(key1);
                    i++;
                }
            }
            try {
                result = runner.query(rconn, sql, rsh, sqlParams);
                readconnClose(rconn,msg);
            } catch (SQLException e) {
                readconnClose(rconn,msg);
                putLog(sql, LogLevel.ERROR, "query");
                return createMsg().setParam(RESULT,false);
            }
        }
        msg.setParam(DB_R_RESULT, result);
        Object resultFor = getResultObject(msg);
        if (resultFor == null)
            return msg;
        else {
            msg.setAction((String) msg.getParam(RESULTACTION));
            putMsg((IObject) resultFor, msg);
            return msg;
        }
    }

    @Override
    protected TLMsg delete(Object fromWho, TLMsg msg) {
        if (msg.getParam(DB_P_SQL) == null) {
            LinkedHashMap<String, TLDBSqlConditionExpression> sqlconditon = (LinkedHashMap<String, TLDBSqlConditionExpression>) msg.getParam(DB_P_SQLCONDITION);
            if (sqlconditon == null)
                return createMsg().setParam(DB_R_RESULT, 0);
            String condition = makeSqlCondition(sqlconditon);
            String sql = "delete from [table]  " + condition;
            msg.setParam(DB_P_SQL, sql);
        }
        TLMsg qreturnMsg = null;
        if (msg.parseBoolean(DB_P_RETURNRECORD, false) == true || deleteBackUpTable != null) {
            String sql = (String) msg.getParam(DB_P_SQL);
            sql = sql.replace("delete ", "select * ");
            TLMsg qmsg = createMsg().setAction(DB_QUERY)
                    .setParam(DB_P_SQL, sql)
                    .copyParam(DB_P_PARAMS, msg);
            TLDataBase.RESULT_TYPE result_type = (msg.getParam(DB_P_RESULTTYPE) != null) ? (TLDataBase.RESULT_TYPE) msg.getParam(DB_P_RESULTTYPE) : TLDataBase.RESULT_TYPE.MAP;
            qmsg.setParam(DB_P_RESULTTYPE, result_type);
            qreturnMsg = query(qmsg);
        }
        TLMsg returnMsg = insertAndupdateAndDelete(fromWho, msg);
        if(returnMsg.parseBoolean(RESULT,true)==false)
            return returnMsg ;
        int deletedResultNumb = (int) returnMsg.getParam(DB_R_RESULT);
        if (deletedResultNumb == 0)
            return returnMsg;
        if (msg.parseBoolean(DB_P_RETURNRECORD, false) == true)
            returnMsg.setParam(DB_R_RECORD, qreturnMsg.getParam(DB_R_RESULT));
        if (deleteBackUpTable != null && qreturnMsg != null) {
            Object deletedResult = qreturnMsg.getParam(DB_R_RESULT);
            if (deletedResult instanceof Map) {
                Map<String, Object> queryResult = (Map<String, Object>) qreturnMsg.getParam(DB_R_RESULT);
                if (queryResult != null && !queryResult.isEmpty()) {
                    TLMsg imsg = createMsg().setAction(DB_INSERT).setParam(DB_P_PARAMS, queryResult);
                    putMsg(deleteBackUpTable, imsg);
                }
            } else if (deletedResult instanceof List) {
                List<Map<String, Object>> dataList = (List<Map<String, Object>>) deletedResult;
                for (Map<String, Object> data : dataList) {
                    TLMsg imsg = createMsg().setAction(DB_INSERT).setParam(DB_P_PARAMS, data);
                    putMsg(deleteBackUpTable, imsg);
                }
            }
        }
        return returnMsg;
    }

    @Override
    protected TLMsg update(Object fromWho, TLMsg msg) {
        if (msg.getParam(DB_P_SQL) != null)
            return insertAndupdateAndDelete(fromWho, msg);
        LinkedHashMap<String, TLDBSqlConditionExpression> sqlconditon = (LinkedHashMap<String, TLDBSqlConditionExpression>) msg.getParam(DB_P_SQLCONDITION);
        if (sqlconditon == null)
            return createMsg().setParam(RESULT,false);
        String condition = makeSqlCondition(sqlconditon);
        String sql = "update [table] set ";
        LinkedHashMap<String, Object> params = (LinkedHashMap<String, Object>) msg.getParam(DB_P_PARAMS);
        LinkedHashMap<String, Object> sqlParams = new LinkedHashMap<>();
        String[] dbfields = new String[params.size()];
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String key : params.keySet()) {
            Object value = params.get(key);
            if (value instanceof TLDBFieldExpression) {
                sb.append(key);
                sb.append(" = ");
                sb.append(((TLDBFieldExpression) value).getValue());
                sb.append(",");
            } else {
                sb.append(key);
                sb.append(" = ? , ");
                sqlParams.put(key, value);
            }
            dbfields[i] = key;
            i++;
        }
        for (String key : sqlconditon.keySet()) {
            TLDBSqlConditionExpression ce = sqlconditon.get(key);
            sqlParams.put(ce.getVarName(), ce.getValue());
        }
        sb.deleteCharAt(sb.lastIndexOf(","));
        String sqlfields = sb.toString();
        sql = sql + sqlfields + condition;
        msg.setParam(DB_P_SQL, sql).setParam(DB_P_PARAMS, sqlParams).setParam(DB_P_FIELDS, dbfields);
        return insertAndupdateAndDelete(fromWho, msg);
    }

    @Override
    protected TLMsg insert(Object fromWho, TLMsg msg) {
        if (msg.getParam(DB_P_SQL) != null)
            return insertAndupdateAndDelete(fromWho, msg);
        Map<String, Object> params = (HashMap<String, Object>) msg.getParam(DB_P_PARAMS);
        Set<String> keySet = params.keySet();
        String[] keyArray = keySet.toArray(new String[keySet.size()]);
        String keys = StringUtils.join(keyArray, ",");
        String QuestionMark = makeQuestionMark(keyArray.length);
        StringBuilder sqlBuffer = new StringBuilder();
        sqlBuffer.append("insert into  [table] ( ");
        sqlBuffer.append(keys);
        sqlBuffer.append(" )  values( ");
        sqlBuffer.append(QuestionMark);
        sqlBuffer.append(" )");
        msg.setParam(DB_P_SQL, sqlBuffer.toString());
        return insertAndupdateAndDelete(fromWho, msg);
    }

    private Connection getConnectionWithMsg(TLMsg msg) {
        Connection wconn = (Connection) msg.getParam(DB_P_CONNECTION);
        if(wconn ==null){
            if (conn == null)
                wconn = (Connection) getConnection(null);
            else
                wconn = (Connection) conn;
        } 
        return wconn ;
    }

    protected TLMsg insertAndupdateAndDelete(Object fromWho, TLMsg msg) {
        Connection wconn =getConnectionWithMsg(msg);
        if (wconn == null) {
            putLog("数据库没有连接", LogLevel.ERROR, "insertAndupdateAndDelete");
            return createMsg().setParam(RESULT,false);
        }
        if(msg.parseBoolean(DB_P_IFTRANSACTION,false) == true)
        {
            try {
                wconn.setAutoCommit(false);
            } catch (SQLException e) {
                e.printStackTrace();
                return createMsg().setParam(RESULT, false);
            }
        }
        String sql = (String) msg.getParam(DB_P_SQL);
        sql = sql.replace("[table]", dbtable);
        QueryRunner runner = new QueryRunner();
        LinkedHashMap<String, Object> sqlParamsList = (LinkedHashMap<String, Object>) msg.getParam(DB_P_PARAMS);
        putLog(sql, LogLevel.DEBUG, "insertAndupdateAndDelete");
        int sucessNumb = 0;
        if (sqlParamsList == null) {
            try {
                sucessNumb = runner.update(wconn, sql);
            } catch (SQLException e) {
                e.printStackTrace();
                putLog(sql, LogLevel.ERROR);
                connClose(wconn,msg.setParam(DB_P_IFCLOSECONNECTION,true));
                return createMsg().setParam(RESULT,false);
            }
            connClose(wconn,msg);
            return msg.setParam(DB_R_RESULT, sucessNumb);
        }
        Object[] sqlParams = new Object[sqlParamsList.size()];
        int i = 0;
        for (String key : sqlParamsList.keySet()) {
            sqlParams[i] = sqlParamsList.get(key);
            i++;
        }
        try {
            sucessNumb = runner.update(wconn, sql, sqlParams);
        } catch (SQLException e) {
            e.printStackTrace();
            putLog(sql, LogLevel.ERROR);
            connClose(wconn,msg.setParam(DB_P_IFCLOSECONNECTION,true));
            return createMsg().setParam(RESULT,false);
        }
        connClose(wconn,msg);
        return msg.setParam(DB_R_RESULT, sucessNumb);
    }

    protected void connClose(Connection conn ,TLMsg msg) {
        if(msg.parseBoolean(DB_P_IFCLOSECONNECTION,true) ==true)
        {
            try {
                conn.close();
            } catch (SQLException e) {
                putLog("connClose", LogLevel.WARN);
            }
        } else
        {
            msg.setParam(DB_R_CONN,conn) ;
            return;
        }
    }

    protected void readconnClose(Connection readconn,TLMsg msg) {
        if(msg.parseBoolean(DB_P_IFCLOSECONNECTION,true) ==true) {
            try {
                readconn.close();
            } catch (SQLException e) {
                putLog("connClose", LogLevel.WARN);
            }
        } else
            return;
    }

    protected String makeSqlCondition(LinkedHashMap<String, TLDBSqlConditionExpression> sqlconditon) {
        StringBuilder csb = new StringBuilder();
        csb.append(" where ");
        for (String key : sqlconditon.keySet()) {
            TLDBSqlConditionExpression conditionExpression = sqlconditon.get(key);
            csb.append(conditionExpression.getSqlStr());
        }
        return csb.toString();
    }

    protected String makeSqlField(Object fields) {
        String queryfields = "";
        if (fields == null)
            queryfields = " * ";
        else if (fields instanceof String[])
            queryfields = StringUtils.join((String[]) fields, ",");
        else if (fields instanceof String)
            queryfields = (String) fields;
        return queryfields;
    }

    public static String makeQuestionMark(int number) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < number; ++i) {
            sb.append("?,");
        }
        sb.deleteCharAt(sb.lastIndexOf(","));
        return sb.toString();
    }

    public List<ColumnModel> getTableStructure() {
        if (columnModelList == null || columnModelList.isEmpty())
            columnModelList = getTableStructureFromDB();
        return columnModelList;
    }

    protected List<ColumnModel> getTableStructureFromDB() {
        List<ColumnModel> columnModelList = new ArrayList<ColumnModel>();
        try {
            //TODO 表相关
            //ResultSet tableSet = metaData.getTables(null, "%",tableName,new String[]{"TABLE"});
            //TODO 字段相关
            Connection connection = (Connection) getConnection(null);
            if (connection == null)
                return null;
            DatabaseMetaData metaData = connection.getMetaData();
            ResultSet columnSet = metaData.getColumns(null, "%", dbtable, "%");
            ColumnModel columnModel = null;
            connection.close();
            while (columnSet.next()) {
                columnModel = new ColumnModel();
                columnModel.setColumnName(columnSet.getString("COLUMN_NAME"));
                columnModel.setColumnSize(columnSet.getInt("COLUMN_SIZE"));
                columnModel.setDataType(columnSet.getString("DATA_TYPE"));
                columnModel.setRemarks(columnSet.getString("REMARKS"));
                columnModel.setTypeName(columnSet.getString("TYPE_NAME"));
                columnModel.setColumnDef(columnSet.getString("COLUMN_DEF"));
                String typeName = columnModel.getTypeName();
                String columnClassName = ColumnTypeEnum.getColumnTypeEnumByDBType(typeName);
                String fieldName = getFieldName(columnModel.getColumnName());
                String fieldType;
                if (!columnClassName.isEmpty()) {
                    try {
                        Class<?> fieldTypeClass = Class.forName(columnClassName);
                        fieldType = fieldTypeClass.getSimpleName();
                    } catch (Exception e) {
                        fieldType = "";
                    }
                } else
                    fieldType = "";
                columnModel.setFieldName(fieldName);
                columnModel.setColumnClassName(columnClassName);
                columnModel.setFieldType(fieldType);
                columnModelList.add(columnModel);
                //System.out.println(columnModel.toString());
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

        return columnModelList;
    }
    /**
     * 将数据库字段转换成bean属性
     *
     * @param columnName
     * @return
     */
    private String getFieldName(String columnName) {
        char[] columnCharArr = columnName.toLowerCase().toCharArray();
        StringBuilder sb = new StringBuilder();
        int ad = -1;
        for (int i = 0; i < columnCharArr.length; i++) {
            char cur = columnCharArr[i];
            if (cur == '_') {
                ad = i;
            } else {
                if ((ad + 1) == i && ad != -1) {
                    sb.append(Character.toUpperCase(cur));
                } else {
                    sb.append(cur);
                }
                ad = -1;
            }
        }
        return sb.toString();
    }

    /**
     * 列模型
     *
     * @author LUSHUIFA
     */
    public class ColumnModel {
        private String columnName;
        private String dataType;
        private String typeName;
        private String columnClassName;
        private String fieldName;
        private String fieldType;
        private int columnSize;
        private String columnDef;
        private String remarks;

        public String getColumnName() {
            return columnName;
        }

        public void setColumnName(String columnName) {
            this.columnName = columnName;
        }

        public String getDataType() {
            return dataType;
        }

        public void setDataType(String dataType) {
            this.dataType = dataType;
        }

        public String getTypeName() {
            return typeName;
        }

        public void setTypeName(String typeName) {
            this.typeName = typeName;
        }

        public int getColumnSize() {
            return columnSize;
        }

        public void setColumnSize(int columnSize) {
            this.columnSize = columnSize;
        }

        public String getRemarks() {
            return remarks;
        }

        public void setRemarks(String remarks) {
            this.remarks = remarks;
        }

        @Override
        public String toString() {
            return "ColumnModel [columnName=" + columnName + ", dataType="
                    + dataType + ", typeName=" + typeName + ", columnClassName="
                    + columnClassName + ", fieldName=" + fieldName + ", fieldType="
                    + fieldType + ", columnSize=" + columnSize + ", columnDef="
                    + columnDef + ", remarks=" + remarks + "]";
        }

        public String getColumnDef() {
            return columnDef;
        }

        public void setColumnDef(String columnDef) {
            this.columnDef = columnDef;
        }

        public String getColumnClassName() {
            return columnClassName;
        }

        public void setColumnClassName(String columnClassName) {
            this.columnClassName = columnClassName;
        }

        public String getFieldName() {
            return fieldName;
        }

        public void setFieldName(String fieldName) {
            this.fieldName = fieldName;
        }

        public String getFieldType() {
            return fieldType;
        }

        public void setFieldType(String fieldType) {
            this.fieldType = fieldType;
        }

    }

    /**
     * 数据库类型枚举
     *
     * @author LUSHUIFA
     */
    public enum ColumnTypeEnum {
        CHAR("CHAR", "java.lang.String"),
        VARCHAR("VARCHAR", "java.lang.String"),
        TEXT("TEXT", "java.lang.String"),
        DATE("DATE", "java.sql.Date"),
        BIT("BIT", "java.lang.Boolean"),
        DATETIME("DATETIME", "java.sql.Timestamp"),
        INT("INT", "java.lang.Integer"),
        SMALLINT("SMALLINT", "java.lang.Integer"),
        TINYINT("TINYINT", "java.lang.Integer"),
        MEDIUMINT("MEDIUMINT", "java.lang.Integer"),
        BIGINT("BIGINT", "java.lang.Long"),
        DECIMAL("DECIMAL", "java.math.BigDecimal"),
        FLOAT("FLOAT", "java.lang.Float"),
        DOUBLE("NUMBER", "java.lang.Double"),
        MEDIUMTEXT("MEDIUMTEXT", "java.lang.String"),
        BOOLEAN("BOOLEAN", "java.lang.Boolean"),
        NUMBER("NUMBER", "java.lang.Double");

        private String dbType;
        private String javaType;

        ColumnTypeEnum(String dbType, String javaType) {
            this.dbType = dbType;
            this.javaType = javaType;
        }

        public static String getColumnTypeEnumByDBType(String dbType) {
            for (ColumnTypeEnum columnTypeEnum : ColumnTypeEnum.values()) {
                if (columnTypeEnum.getDbType().equals(dbType)) {
                    return columnTypeEnum.getJavaType();
                }
            }
            return "";
        }

        public String getDbType() {
            return dbType;
        }

        public void setDbType(String dbType) {
            this.dbType = dbType;
        }

        public String getJavaType() {
            return javaType;
        }

        public void setJavaType(String javaType) {
            this.javaType = javaType;
        }
    }

}
