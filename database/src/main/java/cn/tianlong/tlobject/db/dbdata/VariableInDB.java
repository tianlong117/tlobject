package cn.tianlong.tlobject.db.dbdata;
import cn.tianlong.tlobject.base.TLObjectFactory;

/**
 * 创建日期：2021/1/113:03
 * 描述:
 * 作者:tianlong
 */
public class VariableInDB extends MapInDB
{

    public VariableInDB(String name, TLObjectFactory factory) {
        super(name, factory);
    }
    public VariableInDB(String name , TLObjectFactory factory , Long cacheExptime){
        super( name , factory,cacheExptime);

    }
    public VariableInDB(String name , TLObjectFactory factory , Long cacheExptime ,String tableName){
        super( name , factory,cacheExptime ,tableName);

    }
    public boolean  set(Object value){
        String key =name;
        return super.put(key,value);
    }
    public Object get (){
        String key =name;
        return super.get(key);
    }
    public Boolean remove(){
        String key =name;
        return super.remove(key) ;
    }
}
