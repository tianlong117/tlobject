package cn.tianlong.tlobject.db.dbdata;

import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.ArrayList;
import java.util.Map;

public class TLDataInDBUtils {

    public static MapInDB getMapInDBModule(String mapName, TLObjectFactory moduleFactory, Long minute){
        MapInDB mapInDB =new MapInDB(mapName,moduleFactory,minute);
        return mapInDB;
    }
    public static Map getHashMapFromDB(String mapName, TLObjectFactory moduleFactory, Long minute){
        MapInDB mapInDB =new MapInDB(mapName,moduleFactory,minute);
        return mapInDB.getAll();
    }
    public static Map getHashMapFromDB(String mapName, TLObjectFactory moduleFactory, Long minute,String tableName){
           MapInDB mapInDB =new MapInDB(mapName,moduleFactory,minute,tableName);
        return mapInDB.getAll();
    }
    public static ListInDB getListInDBModule(String listName, TLObjectFactory moduleFactory, Long minute){
         ListInDB listInDb =new ListInDB(listName,moduleFactory,minute);
        return listInDb ;
    }
    public static ArrayList getListFromDB(String listName, TLObjectFactory moduleFactory, Long minute){
        ListInDB listInDb =new ListInDB(listName,moduleFactory,minute);
        return listInDb.getList() ;
    }
    public static ArrayList getListFromDB(String listName, TLObjectFactory moduleFactory, Long minute,String tableName){
        ListInDB listInDb =new ListInDB(listName,moduleFactory,minute,tableName);
        return listInDb.getList() ;
    }
    public static  VariableInDB getVariableInDBModule(String variableName, TLObjectFactory moduleFactory, Long minute){
        VariableInDB variableInDB  =new VariableInDB(variableName,moduleFactory,minute);
        return variableInDB ;
    }
    public static Object getVariableFromDB(String variableName, TLObjectFactory moduleFactory, Long minute){
        VariableInDB variableInDB  =new VariableInDB(variableName,moduleFactory,minute);
        return variableInDB.get() ;
    }
    public static Object getVariableFromDB(String variableName, TLObjectFactory moduleFactory, Long minute ,String tableName){
        VariableInDB variableInDB  =new VariableInDB(variableName,moduleFactory,minute,tableName);
        return variableInDB.get() ;
    }
}
