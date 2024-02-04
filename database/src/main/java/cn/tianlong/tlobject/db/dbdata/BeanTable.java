package cn.tianlong.tlobject.db.dbdata;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.*;
import cn.tianlong.tlobject.modules.LogLevel;
import org.apache.commons.beanutils.BeanMap;

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
        return  returnMsg.getIntParam(DB_R_RESULT,0);
    }
    public int add(Object bean ) {
        BeanMap beanMap= new BeanMap(bean);
        LinkedHashMap<String, Object> data =new LinkedHashMap<>();
        for (Object key :beanMap.keySet())
        {
            String keyStr =String.valueOf(key);
            if(!keyStr.equals("class"))
                data.put(String.valueOf(key),beanMap.get(key));
        }
       return add(data);
    }
    public int replace(LinkedHashMap<String, Object> data ) {
        if( !containsPrimaryKey( data))
            return 0 ;
        TLMsg returnMsg = replaceHashMap(data);
        return  returnMsg.getIntParam(DB_R_RESULT,0);
    }
    //通过 insert 每条数据插入 ，启动表前插入触发器
    public boolean addAll(List<LinkedHashMap> datas){
        int datasize =datas.size();
        if(datasize ==0)
             return false;
        int result =TLDBUtilis.insertList( datas , (TLTable) table);
        return (result==datasize)?true : false ;
    }
    public boolean addAllByBatch(List<LinkedHashMap> datas){
        int datasize =datas.size();
        if(datasize ==0)
            return false;
        int result =TLDBUtilis.batchInsertList( "",datas , (TLTable) table);
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
        return  returnMsg.getIntParam(DB_R_RESULT,0);
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
       return (Map<String, Object>) returnMsg.getMapParam(DB_R_RESULT,null);
    }
    public Map<String,Object> getBean(Object primaryKeyValue ,Class beanClass) {
        String sql="select * from [table] where "+this.primaryKey+"=?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put(primaryKey, primaryKeyValue);
        TLMsg sqlmsg =createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL,sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.BEANMAP)
                .setParam(DB_P_BEANCLASS,beanClass)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg returnMsg = putMsg(table,sqlmsg);
        return (Map<String, Object>) returnMsg.getMapParam(DB_R_RESULT,null);
    }
    public Map<String,Object> get(Object primaryKeyValue ,String[] fields) {
        TLMsg returnMsg= queryBy(this.primaryKey,primaryKeyValue, fields,TLDataBase.RESULT_TYPE.MAP ,null );
        return (Map<String, Object>) returnMsg.getMapParam(DB_R_RESULT,null);
    }
    public ArrayList<Map<String,Object>> get( LinkedHashMap<String, TLDBSqlConditionExpression> sqlCondition) {
        TLMsg idmsg=createMsg().setAction(DB_QUERY)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_SQLCONDITION, sqlCondition);
        TLMsg returMsg = putMsg(table, idmsg);
        return (ArrayList<Map<String, Object>>) returMsg.getListParam(DB_R_RESULT,null);
    }
    public Map<String,Object> getBeanMap( LinkedHashMap<String, TLDBSqlConditionExpression> sqlCondition,Class beanClass) {
        TLMsg idmsg=createMsg().setAction(DB_QUERY)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.BEANMAP)
                .setParam(DB_P_BEANCLASS,beanClass)
                .setParam(DB_P_PRIMARYKEY,primaryKey)
                .setParam(DB_P_SQLCONDITION, sqlCondition);
        TLMsg  returnMsg = putMsg(table, idmsg);
        return returnMsg.getMapParam(DB_R_RESULT,null);
    }
    public ArrayList<Object> getBeanList( LinkedHashMap<String, TLDBSqlConditionExpression> sqlCondition,Class beanClass) {
        TLMsg idmsg=createMsg().setAction(DB_QUERY)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.BEANLIST)
                .setParam(DB_P_BEANCLASS,beanClass)
                .setParam(DB_P_SQLCONDITION, sqlCondition);
        TLMsg returMsg = putMsg(table, idmsg);
        return (ArrayList<Object>) returMsg.getListParam(DB_R_RESULT,null);
    }
    public ArrayList<Map<String,Object>> get( LinkedHashMap<String, TLDBSqlConditionExpression> sqlCondition,String[] fields) {
        TLMsg idmsg=createMsg().setAction(DB_QUERY)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_FIELDS,fields)
                .setParam(DB_P_SQLCONDITION, sqlCondition);
        TLMsg returMsg = putMsg(table, idmsg);
        return (ArrayList<Map<String, Object>>) returMsg.getListParam(DB_R_RESULT,null);
    }
    public ArrayList<Map<String,Object>> getAll( LinkedHashMap<String,Object> params) {
        TLMsg idmsg=createMsg().setAction(DB_QUERY)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_PARAMS, params);
        TLMsg returMsg = putMsg(table, idmsg);
        return (ArrayList<Map<String, Object>>) returMsg.getListParam(DB_R_RESULT,null);
    }
    public ArrayList<Map<String,Object>> getAll( LinkedHashMap<String,Object> params,String[] fields ) {
        TLMsg idmsg=createMsg().setAction(DB_QUERY)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_FIELDS,fields)
                .setParam(DB_P_PARAMS, params);
        TLMsg returMsg = putMsg(table, idmsg);
        return (ArrayList<Map<String, Object>>) returMsg.getListParam(DB_R_RESULT,null);
    }

    public ArrayList<Map<String,Object>> getAll(String[] fields ) {
        TLMsg sqlmsg =createMsg().setAction(DB_FINDALL).setParam(DB_P_FIELDS,fields) ;
        TLMsg returnMsg = putMsg(table,sqlmsg);
        return (ArrayList<Map<String,Object>> ) returnMsg.getListParam(DB_R_RESULT,null);
    }
    public ArrayList<Map<String,Object>> getAll( ) {
        TLMsg sqlmsg =createMsg().setAction(DB_FINDALL) ;
        TLMsg returnMsg = putMsg(table,sqlmsg);
        return (ArrayList<Map<String,Object>> ) returnMsg.getListParam(DB_R_RESULT,null);
    }
    public Map<String,Object> getAllBeanMap( Class beanClass) {
        TLMsg sqlmsg =createMsg().setAction(DB_FINDALL)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.BEANMAP)
                .setParam(DB_P_BEANCLASS,beanClass)
                .setParam(DB_P_PRIMARYKEY,primaryKey) ;
        TLMsg returnMsg = putMsg(table,sqlmsg);
        return  returnMsg.getMapParam(DB_R_RESULT,null);
    }
    public ArrayList<Object> getAllBeanList( Class beanClass) {
        TLMsg sqlmsg =createMsg().setAction(DB_FINDALL)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.BEANLIST)
                .setParam(DB_P_BEANCLASS,beanClass) ;
        TLMsg returnMsg = putMsg(table,sqlmsg);
        return (ArrayList<Object> ) returnMsg.getListParam(DB_R_RESULT,null);
    }
    public ArrayList<Map<String,Object>> getAll( String fieldName,Object value) {
        String sql ="select * from  [table] where "+fieldName+" =?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put(fieldName,  value);
        TLMsg updatemsg=createMsg().setAction(DB_QUERY).setParam(DB_P_SQL,sql)
                .setParam(DB_P_RESULTTYPE,TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg returMsg = putMsg(table,  updatemsg);
        return (ArrayList<Map<String,Object>> )  returMsg.getListParam(DB_R_RESULT,null);
    }
    public ArrayList<Object> getAllBeanList( String fieldName,Object value,Class beanClass) {
        String sql ="select * from  [table] where "+fieldName+" =?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put(fieldName,  value);
        TLMsg updatemsg=createMsg().setAction(DB_QUERY).setParam(DB_P_SQL,sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.BEANLIST)
                .setParam(DB_P_BEANCLASS,beanClass)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg returMsg = putMsg(table,  updatemsg);
        return (ArrayList<Object> )  returMsg.getListParam(DB_R_RESULT,null);
    }
    public int update(Object primaryKeyValue, LinkedHashMap<String,Object> datas) {
        LinkedHashMap<String, Object> sqlCondition = new LinkedHashMap<>();
        TLDBSqlConditionExpression sqlConditionExpression =new TLDBSqlConditionExpression(primaryKey,primaryKeyValue);
        sqlCondition.put(this.primaryKey, sqlConditionExpression);
        TLMsg idmsg=createMsg().setAction(DB_UPDATE)
                .setParam(DB_P_PARAMS, datas)
                .setParam(DB_P_SQLCONDITION, sqlCondition);
        TLMsg returnMsg = putMsg(table, idmsg);
        return returnMsg.getIntParam(DB_R_RESULT,0);
    }
    public int update(Object primaryKeyValue, String fieldName,Object value) {
        String sql ="update  [table]  set "+ fieldName+" =?  where "+this.primaryKey+" =?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put(fieldName,  value);
        sqlparams.put(primaryKey, primaryKeyValue);
        TLMsg updatemsg=createMsg().setAction(DB_UPDATE).setParam(DB_P_SQL,sql)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg returnMsg = putMsg(table,  updatemsg);
        return returnMsg.getIntParam(DB_R_RESULT,0);
    }
    public int  remove(LinkedHashMap<String,Object> params){
        TLMsg idmsg=createMsg().setAction(DB_DELETE)
                .setParam(DB_P_PARAMS, params);
        TLMsg returnMsg = putMsg(table, idmsg);
        return returnMsg.getIntParam(DB_R_RESULT,0);
    }
    public int  remove(Object primaryKeyValue){
        String sql="delete from [table] where "+primaryKey+"=?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put(primaryKey, primaryKeyValue);
        TLMsg sqlmsg =createMsg().setAction(DB_DELETE)
                .setParam(DB_P_SQL,sql)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg returnMsg = putMsg(table,sqlmsg);
        return returnMsg.getIntParam(DB_R_RESULT,0);
    }
    public int  removeAll(){
        String sql="delete from [table] ";
        TLMsg sqlmsg =createMsg().setAction(DB_DELETE) .setParam(DB_P_SQL,sql);
        TLMsg returnMsg = putMsg(table,sqlmsg);
        return returnMsg.getIntParam(DB_R_RESULT,0);
    }
    public Long size(){
        TLMsg sqlmsg =createMsg().setAction(DB_TOTAL);
        TLMsg returnMsg = putMsg(table,sqlmsg);
        return  returnMsg.getLongParam(DB_R_RESULT,0L);
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
