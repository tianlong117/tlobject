package cn.tianlong.tlobject.db;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public abstract class TLBaseTableModel extends TLBaseModule {

    protected String tableName;
    protected TLDatabase dataBase;
    protected String databaseName = DEFAULTDATABASE;
    protected TLBaseDataUnit table;
    public TLBaseTableModel() {
        super();
    }
    public TLBaseTableModel(String name ) {
        super(name);
    }
    public TLBaseTableModel(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }
    public TLBaseTableModel(String name ,TLObjectFactory moduleFactory ,String tableName){
        this.moduleFactory = moduleFactory;
        this.name  =name ;
        if(tableName!=null && !tableName.isEmpty())
        this.tableName =tableName ;
        init();
    }
    public TLBaseTableModel(String name ,TLObjectFactory moduleFactory ,TLBaseDataUnit table){
        this.moduleFactory = moduleFactory;
        this.name  =name ;
        this.table=table ;
        this.tableName =table.getTableName() ;
    }
    @Override
    protected void initProperty() {
        super.initProperty();
        if(params!=null && params.get(DB_P_TABLENAME)!=null)
            tableName=params.get(DB_P_TABLENAME);
        if(params!=null && params.get("database")!=null)
            databaseName=params.get("database");
    }
    @Override
    protected TLBaseModule init() {
        if(table !=null)
            return this ;
        setTable(null);
        return  this ;
    }
    protected TLBaseDataUnit getTable(String tableName){
        if(dataBase ==null)
            dataBase = (TLDatabase) getModule(databaseName);
        TLMsg tmsg = createMsg().setAction(DB_GETTABLE).setParam(DB_P_TABLENAME,tableName);
        TLMsg returnmsg =putMsg(dataBase,tmsg);
        table= (TLBaseDataUnit) returnmsg.getParam(INSTANCE);
        return table ;
    }
    protected TLBaseDataUnit setTable(String tableName){
        if(tableName!=null)
            this.tableName=tableName;
        dataBase = (TLDatabase) getModule(databaseName);
        return  getTable(this.tableName) ;
    }
    public  String getTableName(){
        return  tableName ;
    }
    public  TLBaseDataUnit getTable(){
        return table ;
    }
    public  void openConn(){
        putMsg(table,createMsg().setAction(DB_SETCONNECTION));
    }
    public  void closeConn(){
        putMsg(table,createMsg().setAction(DB_CLOSECONNECTION));
    }
    protected  TLMsg total(Object fromWho, TLMsg msg){
        TLMsg totalMsg =createMsg().setAction("total");
        List totalDatas = (List) putMsg(table,totalMsg).getParam("result");
        Long totalNumber= Long.valueOf(0);
        for(int i=0;i< totalDatas.size();i++){
            Object [] unit= (Object[]) totalDatas.get(i);
            totalNumber=totalNumber + (Long)unit[0];
        }
        return createMsg().setParam("result",totalNumber);
    }
    protected TLMsg queryBy(String fieldName,Object fieldValue,String[] fields,TLDatabase.RESULT_TYPE resultType ,String action_tag ){
       if(resultType ==null )
           resultType =TLDatabase.RESULT_TYPE.MAP;
        String  queryfields ="*" ;
       if( fields !=null)
          queryfields = StringUtils.join(fields, ",");
        String sql="select "+queryfields+" from [table] where "+fieldName+"=?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put(fieldName, fieldValue);
        TLMsg sqlmsg =createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL,sql)
                .setParam(DB_P_FIELDS, fields)
                .setParam(DB_P_RESULTTYPE, resultType)
                .setParam(DB_P_PARAMS, sqlparams);
         if(action_tag !=null)
             sqlmsg.setParam(DB_P_ACTIONTAG, action_tag);
        return putMsg(table,sqlmsg);
    }
     protected TLMsg insertHashMap(LinkedHashMap<String, Object> data ,String tableName) {
        String insertSql = TLDBUtils.createInsertSql(data,tableName);
        TLMsg insertmsg = createMsg().setAction(DB_INSERT)
                    .setParam(DB_P_SQL, insertSql)
                    .setParam(DB_P_PARAMS, data);
        return putMsg(table, insertmsg);
    }
    protected TLMsg insertHashMap(LinkedHashMap<String, Object> data) {
        return insertHashMap(data ,"[table]");
    }
    protected TLMsg replaceHashMap(LinkedHashMap<String, Object> data) {
        return replaceHashMap(data ,"[table]");
    }

    private TLMsg replaceHashMap(LinkedHashMap<String,Object> data, String tableName) {
        String insertSql = TLDBUtils.createReplaceSql(data,tableName);
        TLMsg insertmsg = createMsg().setAction(DB_INSERT)
                .setParam(DB_P_SQL, insertSql)
                .setParam(DB_P_PARAMS, data);
        return putMsg(table, insertmsg);
    }

}
