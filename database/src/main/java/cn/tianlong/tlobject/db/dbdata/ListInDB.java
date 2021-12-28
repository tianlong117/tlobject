package cn.tianlong.tlobject.db.dbdata;


import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class ListInDB extends MapInDB {
    public ListInDB(String name, TLObjectFactory moduleFactory) {
        super(name, moduleFactory);
    }
    public ListInDB(String name, TLObjectFactory moduleFactory ,String tableName) {
        super(name, moduleFactory,tableName);
    }
    public ListInDB(String name , TLObjectFactory moduleFactory , Long cacheExptime){
        super( name , moduleFactory,cacheExptime);

    }
    public ListInDB(String name , TLObjectFactory moduleFactory , Long cacheExptime,String tableName){
        super( name , moduleFactory,cacheExptime,tableName);

    }
    public boolean  add(Object value){
        long size=size() ;
        String key =String.valueOf(size);
        return super.put(key,value);
    }
    public boolean  addAll(int index ,List list){
        int size =list.size();
        for(int i=0;i < size;i ++){
            Object value =list.get(i);
            String key =String.valueOf(index +i);
            boolean result= super.put(key,value);
            if(result ==false)
                return false ;
        }
        return true;
    }
    public Object get (int index){
        String key =String.valueOf(index);
        return super.get(key);
    }

    public Boolean remove(int index){
        String key =String.valueOf(index);
        return super.remove(key) ;
    }
    @Override
    public  Boolean remove(String key){
        return false ;
    }
    @Override
    public boolean  put(String key,Object value){
        return false ;
    }
    @Override
    public HashMap<String,Object> getAll(){
        return null ;
    }
    @Override
    public boolean putAll(Map<String,Object> map){
        return false ;
    }
    @Override
    public boolean containsKey(String key){
        return false ;
    }
    public ArrayList getList(){
        Map<String,Object> dbmap =super.getAll() ;
        if(dbmap ==null)
            return null ;
        ArrayList list = new ArrayList();
        for(String key : dbmap.keySet()){
            list.add(dbmap.get(key));
        }
        return list ;
    }
}
