package cn.tianlong.tlobject.db;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;

import static cn.tianlong.tlobject.base.TLParamString.*;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLDBSqlCondition {
    protected ArrayList<sqlCondition> ceList = new ArrayList<>();

    public TLDBSqlCondition() {

    }
    public TLDBSqlCondition add(String varName, Object value, String relation, String nextRelation) {
        sqlCondition ce = new sqlCondition( varName, value, relation,nextRelation);
        ceList.add(ce);
        return this ;
    }
    public TLDBSqlCondition add(String sql, LinkedHashMap<String, Object> sqlParams) {
        sqlCondition ce = new sqlCondition( sql,sqlParams);
        ceList.add(ce);
        return this ;
    }
    public void clear(){
        ceList.clear();
    }
    public String getSqlCondition(){
        StringBuilder csb = new StringBuilder();
        for (sqlCondition ce : ceList) {
            csb.append(ce.getSqlStr());
        }
        return csb.toString() ;
    }
    public  LinkedHashMap<String, Object> getSqlParam(){
        LinkedHashMap<String, Object> sqlParams = new LinkedHashMap<>();
        for (sqlCondition ce : ceList) {
            Object value =ce.getValue() ;
            if(value !=null)
            {
                if(value instanceof LinkedHashMap)
                    sqlParams.putAll((LinkedHashMap<String, Object>)value);
                else {
                    String varName =ce.getVarName() ;
                    if(varName !=null && !varName.isEmpty())
                        sqlParams.putIfAbsent(varName, ce.getValue());
                }
            }
        }
        if(!sqlParams.isEmpty())
          return sqlParams ;
        return null ;
    }

   private class sqlCondition {
        private String varName ;
        private Object value ;
        private String relation=" = " ;
        private String nextRelation ;
        private String sql ;

        public sqlCondition(String sql, LinkedHashMap<String, Object> sqlParams) {
           this.sql =sql ;
           this.value =sqlParams;
        }
        public sqlCondition(String varName, Object value, String relation, String nextRelation) {
            this.varName = varName;
            this.value = value;
            if(relation !=null)
                this.relation = relation;
            if(nextRelation!=null)
                this.nextRelation = nextRelation;
        }
        public sqlCondition(String varName, Object value) {
            this( varName,value,null,null);
        }

         public String getSqlStr(){
            if(sql !=null)
                return sql ;
            String str ;
            if(relation.equals(DB_P_EXP_IN) || relation.equals(DB_P_EXP_NOTIN))
                str=  sqlOfIn();
            else if(relation.equals(DB_P_EXP_LIKELEFT))
                str =  varName +" like  ?\"%\" ";
            else if(relation.equals(DB_P_EXP_LIKERIGHT))
                str =  varName +" like  \"%\"? ";
            else if(relation.equals(DB_P_EXP_LIKE))
                str =  varName +" like  \"%\"?\"%\" ";
            else
                str =  varName +" "+ relation +" ? " ;
            if(nextRelation ==null)
                return str ;
            else
                return str +" "+nextRelation+" " ;
        }

        private String sqlOfIn() {
            // 占位符个数必须与 valueOfIn 生成的参数个数一致：两者都从同一个 toArray 取值。
            // （曾一度写成 new String[]{value.toString()}，numb 恒为 1 → SQL 只有 1 个 ? 却绑定 N 个参数）
            Object [] array = toArray(this.value);
            int numb =array.length;
            if(numb ==0)
                return varName+" "+relation+" ( null ) " ;   // 空集合：in 语义下匹配不到任何行
            int i=0;
            String insql=varName+" "+relation+" ( " ;
            for(Object value :array){
                if(i == numb-1)
                    insql= insql+" ? )";
                else
                    insql= insql+" ?,";
                i++ ;
            }
            return insql ;
        }

        public String getVarName(){
            return varName ;
        }
        public Object getValue(){
            // 与 getSqlStr 用同一判断（原来只认硬编码 "in"，not in 时 SQL 出 N 个 ? 却返回裸数组）
            if(relation.equals(DB_P_EXP_IN) || relation.equals(DB_P_EXP_NOTIN))
                return valueOfIn();
            else
                return value ;
        }

        private  LinkedHashMap<String, Object> valueOfIn() {
            LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
            Object [] array = toArray(this.value);
            int i =0;
            for(Object value :array){
                i++ ;
                sqlparams.put(varName+i, value);
            }
            return sqlparams ;
        }
    }

    /** in / not in 的值可能是数组、集合或单值，统一成 Object[]，保证占位符与参数一一对应 */
    private static Object[] toArray(Object value) {
        if (value == null)
            return new Object[0];
        if (value instanceof Object[])
            return (Object[]) value;
        if (value instanceof Collection)
            return ((Collection<?>) value).toArray();
        return new Object[]{value};
    }

}
