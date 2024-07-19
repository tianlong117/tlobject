package cn.tianlong.tlobject.db;

import java.util.ArrayList;
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
            String [] array = (String[]) this.value;
            int numb =array.length;
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
            if(relation.equals("in"))
                return valueOfIn();
            else
                return value ;
        }

        private  LinkedHashMap<String, Object> valueOfIn() {
            LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
            Object [] array = (Object[]) this.value;
            int i =0;
            for(Object value :array){
                i++ ;
                sqlparams.put(varName+i, value);
            }
            return sqlparams ;
        }
    }

}
