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
public class TLDBUtils {

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
                    // strToDay 解析失败返回 null（不抛异常），不判空会 NPE
                    result =(date !=null)? new java.sql.Timestamp(date.getTime()) : null;
                }
                break;
            case "Integer":
            case "I":
                if (value ==null || value.isEmpty())
                    result =0 ;
                else
                    result=parseNumberOrNull(value, Integer.class);
                break;
            case "Long":
            case "L":
                if (value ==null || value.isEmpty())
                    result =0L ;
                else
                    result=parseNumberOrNull(value, Long.class);
                break;
            case "Float":
            case "F":
                if (value ==null || value.isEmpty())
                    result =0F ;
                else
                    result=parseNumberOrNull(value, Float.class);
                break;
            case "BigDecimal":
            case "D":
                if (value ==null || value.isEmpty())
                    result =0D ;
                else
                    result=parseNumberOrNull(value, Double.class);
                break;
            default:
                result=value;
        }
        return result ;
    }

    /** 数字解析失败（"abc"/"  "）返回 null，交给 SQL 层报错，而不是把 NumberFormatException 抛到调用方 */
    private static Object parseNumberOrNull(String value, Class<?> type) {
        try {
            String v = value.trim();
            if (type == Integer.class) return Integer.parseInt(v);
            if (type == Long.class) return Long.parseLong(v);
            if (type == Float.class) return Float.parseFloat(v);
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return null;
        }
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
        if (datas == null || datas.isEmpty())
            return 0;
        int sucessNumber=0 ;
        for (Map data: datas )
        {
            TLMsg insertmsg = new TLMsg().setAction(DB_INSERT)
                    .setParam(DB_P_SQL, sql)
                    .setParam(DB_P_PARAMS, data);
            TLMsg resultMsg = table.putMsg(table, insertmsg);
            if (resultMsg == null)          // 目标模块异常路径可能返回 null
                continue;
            int result = resultMsg.getIntParam(DB_R_RESULT,0);
            sucessNumber = sucessNumber +result ;
        }
        return sucessNumber ;
    }
    public static int  batchInsertList(String sql , List<LinkedHashMap> datas , TLTable table){
        if (datas == null || datas.isEmpty())   // 空列表原来 datas.get(0) 直接越界
            return 0;
        LinkedHashMap<String,Object> data0 =datas.get(0) ;
        if(sql ==null || sql.isEmpty())
          sql =createInsertSql(data0,null);
        TLMsg insertmsg = new TLMsg().setAction(DB_BATCH)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_PARAMS, datas);
        TLMsg resultMsg = table.putMsg(table, insertmsg);
        if (resultMsg == null)
            return 0;
        int[] result = (int[]) resultMsg.getArrayParam(DB_R_RESULT,null);
        if(result ==null)
            return 0 ;
        else
            return result.length ;
    }
    public static int  batchInsertList(TLBaseModule fromWho, String tableName, List<HashMap<String, Object>> datas, HashMap<String ,String> dbFields) {

        String sql ;
        String[] fieldsNames ;
        if (datas == null || datas.isEmpty())   // 空列表原来 datas.get(0) 直接越界
            return 0;
        if(dbFields ==null || dbFields.isEmpty())
        {
            HashMap<String, Object> dataMap0= datas.get(0);
            Set keySets =dataMap0.keySet() ;
            fieldsNames = (String[]) keySets.toArray(new String[0]);
        }
        else
            fieldsNames =  dbFields.keySet().toArray(new String[0]);
        sql =TLDBUtils.createInsertSql(fieldsNames,tableName) ;
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
                    bparams[i][j] =TLDBUtils.stringToDBValue(ftype, (String) map.get(fieldName));
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

    public static TLDBSqlCondition makeSqlCondition(LinkedHashMap<String,Object> params){
        TLDBSqlCondition sqlCondition = new TLDBSqlCondition();
        int i =0;
        int size =params.size();
        for(String key : params.keySet()){
            if(i <  size-1)
                sqlCondition.add(key,params.get(key),"=","and");
            else
                sqlCondition.add(key,params.get(key),"=","");
           i++ ;
        }
        return sqlCondition ;
    }
    public static int insertList(List<LinkedHashMap> datas , TLTable table){
        if (datas == null || datas.isEmpty())
            return 0;
        LinkedHashMap<String,Object> data0 =datas.get(0) ;
        String sql =createInsertSql(data0,null);
        return insertList( sql,  datas , table);
    }
}
