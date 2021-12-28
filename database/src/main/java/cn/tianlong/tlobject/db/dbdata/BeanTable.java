package cn.tianlong.tlobject.db.dbdata;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.*;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.*;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class BeanTable extends TLBaseTableModle {

    protected  String primaryKey ;
    protected  Boolean IfPrimaryKeyAuto =false;

    public BeanTable (String tableName , TLObjectFactory modulefactory){
        super(tableName,modulefactory);
        this.tableName  =tableName ;
        init();
    }
    public BeanTable (TLBaseDataUnit table,String primaryKey){
        this.table =table ;
        this.primaryKey =primaryKey ;
        this.tableName =table.getName();
        this.moduleFactory =table.getFactory() ;
    }
    public BeanTable(TLBaseDataUnit table,String primaryKey,Boolean IfPrimaryKeyAuto){
         this(table,primaryKey);
         this.IfPrimaryKeyAuto =IfPrimaryKeyAuto ;
    }
    public BeanTable(String tableName , String primaryKey,TLObjectFactory moduleFactory){
        this.moduleFactory =moduleFactory;
        this.tableName  =tableName ;
        this.primaryKey =primaryKey ;
        name=tableName;
        init();
    }
    public BeanTable(String tableName , String primaryKey,Boolean IfPrimaryKeyAuto ,TLObjectFactory moduleFactory){
        this( tableName , primaryKey,moduleFactory);
        this.IfPrimaryKeyAuto =IfPrimaryKeyAuto ;
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        if(params!=null && params.get("primaryKey")!=null)
            primaryKey=params.get("primaryKey");
        if(params!=null && params.get("IfPrimaryKeyAuto")!=null)
            IfPrimaryKeyAuto=Boolean.parseBoolean(params.get("IfPrimaryKeyAuto"));
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        return null;
    }

    public int add(LinkedHashMap<String, Object> data ) {
        if( !containsPrimaryKey( data))
            return 0 ;
        TLMsg returnMsg = insertHashMap(data);
        return (int) returnMsg.getParam(DB_R_RESULT);
    }
    public int replace(LinkedHashMap<String, Object> data ) {
        if( !containsPrimaryKey( data))
            return 0 ;
        TLMsg returnMsg = replaceHashMap(data);
        return (int) returnMsg.getParam(DB_R_RESULT);
    }
    public boolean addAll(ArrayList<LinkedHashMap> datas){
        int datasize =datas.size();
        if(datasize ==0)
             return false;
        int result =TLDBUtilis.insertList( datas , (TLTable) table);
        return (result==datasize)?true : false ;
    }
    public boolean replaceAll(ArrayList<LinkedHashMap> datas){
        int datasize =datas.size();
        if(datasize ==0)
            return false;
        int result =TLDBUtilis.replaceList( datas , (TLTable) table);
        return (result==datasize)?true : false ;
    }
    public ArrayList<Map<String,Object>> query(String sql,LinkedHashMap<String, Object> sqlparams ) {
        TLMsg sqlmsg =createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL,sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAPLIST);
        if(sqlparams !=null)
            sqlmsg.setParam(DB_P_PARAMS, sqlparams);
        TLMsg returnMsg = putMsg(table,sqlmsg);
        return (ArrayList<Map<String, Object>>) returnMsg.getListParam(DB_R_RESULT,null);
    }
    public int updateBySql(String sql, LinkedHashMap<String, Object> sqlparams ) {
        TLMsg sqlmsg =createMsg().setAction(DB_UPDATE)
                .setParam(DB_P_SQL,sql);
        if(sqlparams !=null)
            sqlmsg.setParam(DB_P_PARAMS, sqlparams);
        TLMsg returnMsg = putMsg(table,sqlmsg);
        return (int) returnMsg.getParam(DB_R_RESULT);
    }
    public int delete(String sql,LinkedHashMap<String, Object> sqlparams ) {
        TLMsg sqlmsg =createMsg().setAction(DB_DELETE)
                .setParam(DB_P_SQL,sql);
        if(sqlparams !=null)
            sqlmsg.setParam(DB_P_PARAMS, sqlparams);
        TLMsg returnMsg = putMsg(table,sqlmsg);
        return (int) returnMsg.getParam(DB_R_RESULT);
    }
    public Map<String,Object> get(Object primaryKeyValue ) {
        String sql="select * from [table] where "+this.primaryKey+"=?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put(primaryKey, primaryKeyValue);
        TLMsg sqlmsg =createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL,sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAP)
                .setParam(DB_P_PARAMS, sqlparams);
       TLMsg returnMsg = putMsg(table,sqlmsg);
       return (Map<String, Object>) returnMsg.getParam(DB_R_RESULT);
    }
    public Map<String,Object> get(Object primaryKeyValue ,String[] fields) {
        TLMsg returnMsg= queryBy(this.primaryKey,primaryKeyValue, fields,TLDataBase.RESULT_TYPE.MAP ,null );
        return (Map<String, Object>) returnMsg.getParam(DB_R_RESULT);
    }
    public ArrayList<Map<String,Object>> get( LinkedHashMap<String, TLDBSqlConditionExpression> sqlCondition) {
        TLMsg idmsg=createMsg().setAction(DB_QUERY)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_SQLCONDITION, sqlCondition);
        TLMsg returMsg = putMsg(table, idmsg);
        return (ArrayList<Map<String, Object>>) returMsg.getParam(DB_R_RESULT);
    }
    public ArrayList<Map<String,Object>> get( LinkedHashMap<String, TLDBSqlConditionExpression> sqlCondition,String[] fields) {
        TLMsg idmsg=createMsg().setAction(DB_QUERY)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_FIELDS,fields)
                .setParam(DB_P_SQLCONDITION, sqlCondition);
        TLMsg returMsg = putMsg(table, idmsg);
        return (ArrayList<Map<String, Object>>) returMsg.getParam(DB_R_RESULT);
    }
    public ArrayList<Map<String,Object>> getAll( LinkedHashMap<String,Object> params) {
        LinkedHashMap<String, Object> sqlCondition = makeSqlCondition(params);
        TLMsg idmsg=createMsg().setAction(DB_QUERY)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_SQLCONDITION, sqlCondition);
        TLMsg returMsg = putMsg(table, idmsg);
        return (ArrayList<Map<String, Object>>) returMsg.getParam(DB_R_RESULT);
    }
    public ArrayList<Map<String,Object>> getAll( LinkedHashMap<String,Object> params,String[] fields ) {
        LinkedHashMap<String, Object> sqlCondition = makeSqlCondition(params);
        TLMsg idmsg=createMsg().setAction(DB_QUERY)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_FIELDS,fields)
                .setParam(DB_P_SQLCONDITION, sqlCondition);
        TLMsg returMsg = putMsg(table, idmsg);
        return (ArrayList<Map<String, Object>>) returMsg.getParam(DB_R_RESULT);
    }
    protected  LinkedHashMap<String, Object>  makeSqlCondition(LinkedHashMap<String,Object> params){
        LinkedHashMap<String, Object> sqlCondition = new LinkedHashMap<>();
        int i =0;
        int size =params.size();
        for(String key : params.keySet()){
            TLDBSqlConditionExpression sqlConditionExpression ;
            if(i <  size-1)
                sqlConditionExpression =new TLDBSqlConditionExpression(key,params.get(key),"=","and");
            else
                sqlConditionExpression =new TLDBSqlConditionExpression(key,params.get(key),"=","");
            sqlCondition.put(key, sqlConditionExpression);
            i++ ;
        }
        return sqlCondition ;
    }
    public ArrayList<Map<String,Object>> getAll(String[] fields ) {
        TLMsg sqlmsg =createMsg().setAction(DB_FINDALL).setParam(DB_P_FIELDS,fields) ;
        TLMsg returnMsg = putMsg(table,sqlmsg);
        return (ArrayList<Map<String,Object>> ) returnMsg.getParam(DB_R_RESULT);
    }
    public ArrayList<Map<String,Object>> getAll( ) {
        TLMsg sqlmsg =createMsg().setAction(DB_FINDALL) ;
        TLMsg returnMsg = putMsg(table,sqlmsg);
        return (ArrayList<Map<String,Object>> ) returnMsg.getParam(DB_R_RESULT);
    }
    public ArrayList<Map<String,Object>> getAll( String fieldName,Object value) {
        String sql ="select * from  [table] where "+fieldName+" =?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put(fieldName,  value);
        TLMsg updatemsg=createMsg().setAction(DB_QUERY).setParam(DB_P_SQL,sql)
                .setParam(DB_P_RESULTTYPE,TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg returMsg = putMsg(table,  updatemsg);
        return (ArrayList<Map<String,Object>> )  returMsg.getParam(DB_R_RESULT);
    }
    public int update(Object primaryKeyValue, LinkedHashMap<String,Object> datas) {
        LinkedHashMap<String, Object> sqlCondition = new LinkedHashMap<>();
        TLDBSqlConditionExpression sqlConditionExpression =new TLDBSqlConditionExpression(primaryKey,primaryKeyValue);
        sqlCondition.put(this.primaryKey, sqlConditionExpression);
        TLMsg idmsg=createMsg().setAction(DB_UPDATE)
                .setParam(DB_P_PARAMS, datas)
                .setParam(DB_P_SQLCONDITION, sqlCondition);
        TLMsg returMsg = putMsg(table, idmsg);
        return (int) returMsg.getParam(DB_R_RESULT);
    }
    public int update(Object primaryKeyValue, String fieldName,Object value) {
        String sql ="update  [table]  set "+ fieldName+" =?  where "+this.primaryKey+" =?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put(fieldName,  value);
        sqlparams.put(primaryKey, primaryKeyValue);
        TLMsg updatemsg=createMsg().setAction(DB_UPDATE).setParam(DB_P_SQL,sql)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg returMsg = putMsg(table,  updatemsg);
        return (int) returMsg.getParam(DB_R_RESULT);
    }
    public int  remove(LinkedHashMap<String,Object> params){
        LinkedHashMap<String, Object> sqlCondition = makeSqlCondition(params);
        TLMsg idmsg=createMsg().setAction(DB_DELETE)
                .setParam(DB_P_PARAMS, params)
                .setParam(DB_P_SQLCONDITION, sqlCondition);
        TLMsg returMsg = putMsg(table, idmsg);
        return (int) returMsg.getParam(DB_R_RESULT);
    }
    public int  remove(Object primaryKeyValue){
        String sql="delete from [table] where "+primaryKey+"=?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put(primaryKey, primaryKeyValue);
        TLMsg sqlmsg =createMsg().setAction(DB_DELETE)
                .setParam(DB_P_SQL,sql)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg returnMsg = putMsg(table,sqlmsg);
        return (int) returnMsg.getParam(DB_R_RESULT);
    }
    public int  removeAll(){
        String sql="delete from [table] ";
        TLMsg sqlmsg =createMsg().setAction(DB_DELETE) .setParam(DB_P_SQL,sql);
        TLMsg returnMsg = putMsg(table,sqlmsg);
        return (int) returnMsg.getParam(DB_R_RESULT);
    }
    public Long size(){
        TLMsg sqlmsg =createMsg().setAction(DB_TOTAL);
        TLMsg returnMsg = putMsg(table,sqlmsg);
        return (Long) returnMsg.getParam(DB_R_RESULT);
    }
    protected boolean containsPrimaryKey(Map<String,Object> data){
        if(primaryKey ==null || primaryKey.isEmpty())
            return true ;
        if( IfPrimaryKeyAuto ==false)
        {
            if(!data.containsKey(primaryKey))
            {
                putLog("no primaryKey",LogLevel.ERROR);
                return false ;
            }
        }
        return true ;
    }
}
