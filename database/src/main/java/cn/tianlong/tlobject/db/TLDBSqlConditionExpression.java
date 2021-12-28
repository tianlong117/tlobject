package cn.tianlong.tlobject.db;

import java.util.LinkedHashMap;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLDBSqlConditionExpression {
    private String varName ;
    private Object value ;
    private String relation=" = " ;
    private String nextRelation ;
    private String sql ;

    public TLDBSqlConditionExpression(String varName,Object value,String relation,String nextRelation) {
        this.varName = varName;
        this.value = value;
        if(relation !=null)
          this.relation = relation;
        if(nextRelation!=null)
            this.nextRelation = nextRelation;
    }
    public TLDBSqlConditionExpression(String varName,Object value) {
        this( varName,value,null,null);
    }
    public TLDBSqlConditionExpression(String sql) {
        this.sql =sql ;
    }
    public String getSqlStr (){
        if(sql !=null)
            return sql ;
        String str ;
        if(relation.equals("in"))
            str=  sqlOfIn();
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
