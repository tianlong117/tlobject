package cn.tianlong.tlobject.db;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.utils.TLDataUtils;
import cn.tianlong.tlobject.utils.TLDateUtils;
import cn.tianlong.tlobject.utils.TLMsgUtils;

import java.util.*;

import static cn.tianlong.tlobject.base.TLParamString.*;

/**
 * 创建日期：2020/9/513:07
 * 描述:
 * 作者:tianlong
 */
public class TLDBUtilis {

    public static String createReplaceSql(String[] fields ,String tableName){
          return  createInsertOrReplaceSql( fields ,tableName,"replace ") ;
    }
    public static String createReplaceSql(HashMap<String,Object> datas,String tableName)
    {
        Set keySet =datas.keySet() ;
        String[] keyArray = (String[]) keySet.toArray(new String[0]);
        return createReplaceSql(keyArray,tableName) ;
    }
    public static String createInsertOrReplaceSql(String[] fields ,String tableName,String action)
    {
        if(tableName==null)
            tableName= "[table]";
        StringBuffer sqlBuffer = new StringBuffer();
        sqlBuffer.append(action);
        sqlBuffer.append(" into  ");
        sqlBuffer.append(tableName);
        sqlBuffer.append(" ( ");
        int i=0;
        int size= fields.length;
        for (String field:fields) {
            if(i!=size-1)
                sqlBuffer.append(field+" ,");
            else
                sqlBuffer.append(field+" )");
            i++;
        }
        sqlBuffer.append(" values ( ");
        for  (int j=0;j< size;j++)
        {
            if(j!=size-1)
                sqlBuffer.append("?,");
            else
                sqlBuffer.append("? )");
        }
        return  sqlBuffer.toString();
    }
    public static String createInsertSql(String[] fields ,String tableName)
    {
       return  createInsertOrReplaceSql( fields ,tableName,"insert ") ;
    }
    public static String createInsertSql(HashMap<String,Object> datas,String tableName)
    {
        Set keySet =datas.keySet() ;
        String[] keyArray = (String[]) keySet.toArray(new String[0]);
        return createInsertSql(keyArray,tableName) ;
    }
    //    username:S;
    public static HashMap<String,String> fieldsStrToMap(String fieldsStr) {
        String[] fieldsArray = TLDataUtils.splitStrToArray(fieldsStr,";");
        if(fieldsArray ==null)
            return null ;
        HashMap<String ,String> fields =new HashMap<>();
        for(String field : fieldsArray){
            String[] fieldArray = TLDataUtils.splitStrToArray(field,":");
            if(fieldArray.length >1){
                if(fieldArray[1].length()==1)
                    fieldArray[1]=fieldArray[1].toUpperCase();
                fields.put(fieldArray[0],fieldArray[1]);
            }
            else
                fields.put(fieldArray[0],"S");
        }
        return  fields ;
    }
    public static Object stringToDBValue(String ftype, String value) {
        if (ftype ==null || ftype.isEmpty())
            ftype="String";
        Object result;
        switch (ftype){
            case "String":
            case "varchar":
            case "S":
                result=value;
                break;
            case "Date":
            case "Timestamp":
            case "DateTime":
            case "T":
                if (value ==null || value.isEmpty())
                    result =null ;
                else
                {
                    Date date=TLDateUtils.strToDay(value,null);
                    result =new java.sql.Timestamp(date.getTime());
                }
                break;
            case "Integer":
            case "I":
                if (value ==null || value.isEmpty())
                    result =0 ;
                else
                    result=Integer.parseInt(value);
                break;
            case "Long":
            case "L":
                if (value ==null || value.isEmpty())
                    result =0L ;
                else
                    result=Long.parseLong(value);
                break;
            case "Float":
            case "F":
                if (value ==null || value.isEmpty())
                    result =0F ;
                else
                    result=Float.parseFloat(value);
                break;
            case "BigDecimal":
            case "D":
                if (value ==null || value.isEmpty())
                    result =0D ;
                else
                    result=Double.parseDouble(value);
                break;
            default:
                result=value;
        }
        return result ;
    }
    public static String objectToString( Object object) {
        if(object ==null)
            return "" ;
        if(object instanceof String)
            return (String) object;
        else if(object instanceof Date)
            return TLDateUtils.dateToStr(((Date)object),null);
        else if(object instanceof TLMsg)
            return TLMsgUtils.msgToJson((TLMsg) object);
        else
            return  object.toString();

    }

    public static int insertList(String sql, List<LinkedHashMap> datas , TLTable table){
        int sucessNumber=0 ;
        for (Map data: datas )
        {
            TLMsg insertmsg = new TLMsg().setAction(DB_INSERT)
                    .setParam(DB_P_SQL, sql)
                    .setParam(DB_P_PARAMS, data);
            TLMsg resultMsg = table.putMsg(table, insertmsg);
            int result = resultMsg.getIntParam(DB_R_RESULT,0);
            sucessNumber = sucessNumber +result ;
        }
        return sucessNumber ;
    }
    public static int  batchInsertList(String sql , List<LinkedHashMap> datas , TLTable table){
        LinkedHashMap<String,Object> data0 =datas.get(0) ;
        if(sql ==null || sql.isEmpty())
          sql =createInsertSql(data0,null);
        TLMsg insertmsg = new TLMsg().setAction(DB_BATCH)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_PARAMS, datas);
        TLMsg resultMsg = table.putMsg(table, insertmsg);
        int[] result = (int[]) resultMsg.getArrayParam(DB_R_RESULT,null);
        if(result ==null)
            return 0 ;
        else
            return result.length ;
    }
    public static int  batchInsertList(TLBaseModule fromWho, String tableName, List<HashMap<String, Object>> datas, HashMap<String ,String> dbFields) {

        String sql ;
        String[] fieldsNames ;
        if(dbFields ==null || dbFields.isEmpty())
        {
            HashMap<String, Object> dataMap0= datas.get(0);
            Set keySets =dataMap0.keySet() ;
            fieldsNames = (String[]) keySets.toArray(new String[0]);
        }
        else
            fieldsNames =  dbFields.keySet().toArray(new String[0]);
        sql =TLDBUtilis.createInsertSql(fieldsNames,tableName) ;
        int rows =datas.size();
        Object[][] bparams = new Object[rows][fieldsNames.length];
        List<String> dbFildsList = Arrays.asList(fieldsNames);
        for (int i = 0; i < rows; i++) {
            HashMap<String, Object> map =datas.get(i);
            for(int j =0 ;j < dbFildsList.size() ; j++){
                String fieldName =dbFildsList.get(j) ;
                if(dbFields ==null || dbFields.isEmpty())
                    bparams[i][j] = map.get(fieldName);
                else
                {
                    String ftype =dbFields.get(fieldName);
                    bparams[i][j] =TLDBUtilis.stringToDBValue(ftype, (String) map.get(fieldName));
                }
            }
        }
        TLMsg insertmsg = new TLMsg().setAction(DB_BATCH)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_TABLENAME,tableName)
                .setParam(DB_P_PARAMS, bparams);
        TLMsg returnMsg = fromWho.putMsg(DEFAULTDATABASE, insertmsg);
        int[] result = (int[]) returnMsg.getParam(DB_R_RESULT);
        if(result==null)
            return  0;
        else
            return  result.length;
    }

    public static  LinkedHashMap<String, TLDBSqlConditionExpression>  makeSqlCondition(LinkedHashMap<String,Object> params){
        LinkedHashMap<String, TLDBSqlConditionExpression> sqlCondition = new LinkedHashMap<>();
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
    public static int insertList(List<LinkedHashMap> datas , TLTable table){
        LinkedHashMap<String,Object> data0 =datas.get(0) ;
        String sql =createInsertSql(data0,null);
        return insertList( sql,  datas , table);
    }
    public static  TLDBSqlConditionExpression  makeSqlCondition(String key ,Object value ,String relation ,String nextRelation){
         if(relation ==null)
             relation="=";
         if(nextRelation == null )
             nextRelation="";
        return new TLDBSqlConditionExpression(key,value,relation,nextRelation);
    }
    protected  LinkedHashMap<String, TLDBSqlConditionExpression>  makeSqlConditionMap(LinkedHashMap<String, TLDBSqlConditionExpression> map,String key ,Object value ,String relation ,String nextRelation){
        if(map ==null)
            map = new LinkedHashMap<>();
        TLDBSqlConditionExpression sqlConditionExpression = makeSqlCondition( key , value , relation , nextRelation) ;
        map.put(key, sqlConditionExpression);
        return map ;
    }
}
