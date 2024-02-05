package cn.tianlong.tlobject.db.dbdata;


import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.*;
import cn.tianlong.tlobject.utils.TLMsgUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class MapInDB extends TLBaseTableModle {
    private  String  defaultTable ="tldataunits";
    protected  Long cacheExptime =0L ;   //缓存过期时间毫秒
    protected Map<String, Object> cacheMap ;
    private  String tableSql ="-- table definition\n" +
            "\n" +
            "CREATE TABLE `tldataunits` (\n" +
            "  `dbid` int NOT NULL AUTO_INCREMENT,\n" +
            "  `id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,\n" +
            "  `mid` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,\n" +
            "  `mkey` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,\n" +
            "  `value` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,\n" +
            "  `type` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,\n" +
            "  `remarks` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,\n" +
            "  PRIMARY KEY (`dbid`) USING BTREE,\n" +
            "  UNIQUE KEY `id_2` (`id`) USING BTREE,\n" +
            "  KEY `mid` (`mid`,`dbid`) USING BTREE\n" +
            ") ENGINE=InnoDB AUTO_INCREMENT=4717 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC;";


    public MapInDB(String name , TLObjectFactory moduleFactory){
         this( name ,  moduleFactory ,"");
    }
    public MapInDB(String name ,TLObjectFactory moduleFactory ,TLBaseDataUnit table){
        super( name ,  moduleFactory ,table);
    }
    public MapInDB(String name ,TLObjectFactory moduleFactory ,String tableName){
        if(tableName==null || tableName.isEmpty())
            this.tableName=defaultTable ;
        else
            this.tableName =tableName ;
        this.moduleFactory = moduleFactory;
        this.name  =name ;
        init();
        isTableExist();
    }
    private boolean isTableExist() {
        String sql = tableSql.replace("tldataunits", tableName);
        TLMsg  domsg =createMsg().setAction(DB_ISTABLEEXIST)
                .setParam(DB_P_SQL,sql)
                .setParam(DB_P_TABLENAME,tableName);
        TLMsg resultMsg =putMsg(DEFAULTDATABASE,domsg);
        boolean result =resultMsg.getBooleanParam(RESULT,false);
        return result ;
    }

    public MapInDB(String name , TLObjectFactory moduleFactory ,Long minute){
        this( name , moduleFactory,minute,"");
    }
    public MapInDB(String name , TLObjectFactory moduleFactory ,Long minute ,String tableName){
        super( name ,moduleFactory,tableName);
        if(minute >0)
        {
            this.cacheExptime =minute * 60 * 1000 ;
            cacheMap = new ConcurrentHashMap<>() ;
        }
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        return null;
    }

    public boolean  put(String key,Object value){
        if(cacheExptime > 0L)
           cacheMap.clear();
        HashMap<String,String> dbdatas =objectToString(key,value);
        String type =dbdatas.get("type") ;
        String dbvalue =dbdatas.get("value") ;
        String id =getValueId(key);
        int result;
        Map<String,String> dbmap =queryMapUnits(key);
        if(dbmap ==null || dbmap.isEmpty())
            result = insertMapValue(id,key,dbvalue,type);
        else
            result =updateMapValue(id,dbvalue,type);
        return (result==0)?false : true ;
    }

    protected  HashMap<String,String> objectToString (String key,Object value){
        String type  ;
        String dbvalue ;
        if(value !=null){
            if( value instanceof Map){
                type="Map" ;
                dbvalue = saveSonMap(key, (Map<String, Object>) value);
            }
            else  if( value instanceof List){
                type="List" ;
                dbvalue = saveSonList(key, (List) value);
            }
            else {
                type =getObjectType(value);
                dbvalue =TLDBUtilis.objectToString(value);
            }
        }
        else
        {
            type="String" ;
            dbvalue="";
        }
        HashMap<String,String> result =new HashMap<>();
        result.put("type",type);
        result.put("value",dbvalue);
        return result ;
    }

    protected String saveSonMap(String key,Map<String,Object> value){
        String mname =getValueId(key);
        MapInDB map =getMapModule(mname,"map");
        map.putAll( value);
        return  mname ;
    }
    private MapInDB  getMapModule(String mapName ,String type){
        MapInDB mapInDB = (MapInDB) modules.get(mapName);
        if(mapInDB !=null)
            return mapInDB ;
        synchronized (this){
            mapInDB = (MapInDB) modules.get(mapName);
            if(mapInDB ==null)
            {
                if(type.equals("map"))
                    mapInDB=new MapInDB( mapName,moduleFactory,table);
                else if(type.equals("list"))
                    mapInDB=new ListInDB(mapName,moduleFactory,table);
                else
                    mapInDB=new MapInDB( mapName,moduleFactory,table);
                modules.put(mapName ,mapInDB) ;
            }
        }
        return mapInDB ;
    }
    protected String saveSonList(String key,List value){
        String mname =getValueId(key);
        ListInDB map = (ListInDB) getMapModule(mname,"list");
        map.addAll(0,value);
        return  mname ;
    }

    public boolean putAll(Map<String,Object> map){
        if(cacheExptime > 0L)
            writeCache(map);
        if(map instanceof  LinkedHashMap)
            return putAllByOneByOne( map) ;
        ArrayList<LinkedHashMap> datas= new ArrayList<>();
        for(String key : map.keySet()){
            Object value =map.get(key);
            HashMap<String,String> dbdatas =objectToString(key,value);
            String type =dbdatas.get("type") ;
            String dbvalue =dbdatas.get("value") ;
            LinkedHashMap<String,Object> unit = new LinkedHashMap<>() ;
            String id =getValueId(key);
            unit.put("id",id);
            unit.put("mid",name);
            unit.put("mkey",key);
            unit.put("value", dbvalue);
            unit.put("type",type);
            datas.add(unit);
        }
        int datasize =datas.size();
        String sql = " replace into  [table] ( id ,mid,mkey,value,type ) values(?,?,?,?,?)";
        int result =TLDBUtilis.batchInsertList(sql,datas, (TLTable) table);
        return (result==datasize)?true : false ;
    }

    public boolean putAllByOneByOne(Map<String,Object> map) {
        for(String key : map.keySet()){
            Object value =map.get(key);
            Boolean result= put(key,value);
            if(result ==false)
                return false ;
           }
           return true ;
    }

    public  Object get(String key){
        if(cacheExptime > 0L)
        {
            Object value =getCache(key);
            if(!value.equals(this))
                return value ;
        }
        Map<String,String> result =queryMapUnits(key);
        if(result ==null || result.isEmpty())
            return null ;
        String value =result.get("value");
        if(value ==null)
            return null ;
        String type =result.get("type");
        Object mapValue  =stringToObject(value,type) ;
        if(cacheExptime > 0L)
            getAll();
        return mapValue ;
    }

    public  Map getAll(){
        if(cacheExptime > 0L)
        {
            Object value = getCache(null);
            if(!value.equals(this))
                return ( LinkedHashMap) value ;
        }
        String sql = " select *  from [table]  where mid=? order by dbid ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("mid", name);
        TLMsg insertmsg = createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg resultMsg = putMsg(table, insertmsg);
        ArrayList<Map<String,String>> result = (ArrayList<Map<String, String>>) resultMsg.getParam(DB_R_RESULT);
        if (result ==null || result.isEmpty())
            return null ;
        LinkedHashMap<String,Object> resultMap =new LinkedHashMap<>();
        for(Map<String,String> map : result)
        {
            String type =map.get("type");
            String value =map.get("value") ;
            Object  mvalue =stringToObject(value,type) ;
            String key =map.get("mkey") ;
            resultMap.put(key,mvalue);
        }
        if(cacheExptime > 0L)
             writeCache(resultMap);
        return resultMap ;
    }

    protected Object stringToObject(String value,String type){
        Object  mvalue ;
        if(type.equals("Map"))
        {
            MapInDB smap = getMapModule(value,"map");
            mvalue= smap.getAll() ;
        }
        else  if(type.equals("List"))
        {
            ListInDB list = (ListInDB) getMapModule(value,"list");
            mvalue= list.getList() ;
        }
        else  if(type.equals("TLMsg"))
            mvalue= TLMsgUtils.jsonToMsg(value);
        else
            mvalue=TLDBUtilis.stringToDBValue(type,value) ;
        return mvalue ;
    }

    protected  Map<String,String> queryMapUnits(String key){
        String sql="select * from [table] where id=?";
        String id =getValueId(key);
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("id",id);
        TLMsg sqlmsg =createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL,sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAP)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg resultMsg = putMsg(table,sqlmsg);
        Map<String,String> result = (Map<String, String>) resultMsg.getMapParam(DB_R_RESULT,null);
        return result ;
    }

    protected   ArrayList<Map<String,String>>  querySonMapUnits(){
        String sql="select * from [table] where mid=? and type in ('Map','List') order by dbid";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("mid",name);
        TLMsg sqlmsg =createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL,sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg resultMsg = putMsg(table,sqlmsg);
        ArrayList<Map<String,String>> result = (ArrayList<Map<String, String>>) resultMsg.getParam(DB_R_RESULT);
        return result ;
    }

    public long size(){
        if(cacheExptime > 0L){
            Map<String ,Object> map = getMapFromCache();
            if(map !=null)
                return map.size() ;
        }
        String sql = " select count(*) as numbers  from [table]  where mid=?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("mid", name);
        TLMsg insertmsg = createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAP)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg resultMsg = putMsg(table, insertmsg);
        Map<String,Object> result = (Map<String, Object>) resultMsg.getParam(DB_R_RESULT);
        if(result ==null || result.isEmpty())
            return 0 ;
        Long  numbers = ( Long) result.get("numbers");
        return numbers ;
    }

    public boolean isEmpty(){
        Long size =size();
        return (size >0L)? true :false ;
    }

    public boolean containsKey(String key){
        if(cacheExptime > 0L){
            Map<String ,Object> map = getMapFromCache();
            if(map !=null)
                return map.containsKey(key) ;
        }
        Map<String,String> result =queryMapUnits(key);
        if(result ==null || result.isEmpty())
            return false ;
        return true ;
    }

    public  Boolean remove(String key){
        if(cacheExptime > 0L)
            cacheMap.clear();
        Map<String,String> dbdata =queryMapUnits(key);
        if(dbdata ==null || dbdata.isEmpty())
            return null ;
        String type =dbdata.get("type");
        String mname =dbdata.get("value");
        deleteSonMap( mname , type);
        int  result =deleteMapValue(key);
        return (result==0)?false : true ;
    }

    public boolean clear(){
        if(cacheExptime > 0L)
            cacheMap.clear();
        ArrayList<Map<String,String>> sonMaps = querySonMapUnits();
        if(sonMaps !=null && !sonMaps .isEmpty()){
            for(Map<String,String> sonMap :sonMaps)
            {
                String type =sonMap.get("type");
                String mname =sonMap.get("value");
                deleteSonMap( mname , type);
            }
        }
        String sql = " delete  from [table]  where mid=?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("mid", name);
        TLMsg insertmsg = createMsg().setAction(DB_DELETE)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.ARRAYLIST)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg resultMsg = putMsg(table, insertmsg);
        int result = (int) resultMsg.getParam(DB_R_RESULT);
        return (result==0)?false : true ;
    }

    protected  void deleteSonMap(String name ,String type){
        if(type.equals("Map"))
        {
            MapInDB map = getMapModule(name,"map");
            map.clear() ;
        }
        else  if(type.equals("List"))
        {
            ListInDB list = (ListInDB) getMapModule(name,"list");
            list.clear() ;
        }
    }

    public void clearCache(){
        if(cacheMap !=null)
            cacheMap.clear();
    }

    public void setCacheExptime(Long minute){
        if(minute >0 && cacheMap ==null)
           cacheMap = new ConcurrentHashMap<>() ;
        this.cacheExptime =minute * 60 * 1000 ;
    }

    private int deleteMapValue(String key) {
        String id =getValueId(key);
        String sql="delete  from [table] where id=?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("id",id);
        TLMsg sqlmsg =createMsg().setAction(DB_DELETE)
                .setParam(DB_P_SQL,sql)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg resultMsg = putMsg(table,sqlmsg);
        int result = (int) resultMsg.getParam(DB_R_RESULT);
        return result ;
    }

    protected int updateMapValue(String id , String value, String type) {
        String sql = " update  [table]  set value =? ,type =? where id =?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("value", value);
        sqlparams.put("type", type);
        sqlparams.put("id", id);
        TLMsg updateMsg = createMsg().setAction(DB_UPDATE)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg resultMsg = putMsg(table, updateMsg);
        int result = (int) resultMsg.getParam(DB_R_RESULT);
        return result ;
    }
    protected int insertMapValue(String id ,String key, String value, String type) {
        String sql = " replace into  [table] ( id ,mid,mkey,value,type ) values(?,?,?,?,?)";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("id", id);
        sqlparams.put("mid", name);
        sqlparams.put("mkey", key);
        sqlparams.put("value", value);
        sqlparams.put("type", type);
        TLMsg insertmsg = createMsg().setAction(DB_INSERT)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg resultMsg = putMsg(table, insertmsg);
        int result = (int) resultMsg.getParam(DB_R_RESULT);
        return result ;
    }

    protected String getValueId(String key) {
        return name+":"+key;
    }

    /**
     * 获取数据类型
     * @param object
     * @return
     */
    protected  String getObjectType(Object object){
        String typeName=object.getClass().getName();
        int length= typeName.lastIndexOf(".");
        String type =typeName.substring(length+1);
        return type;
    }

    protected  Object getCache(String key){
        if(cacheMap ==null )
            return this;
        Long time = (Long) cacheMap.get("time");
        if(time ==null)
            return  this ;
        if(System.currentTimeMillis() > time)
        {
            cacheMap.clear();
            return  this ;
        }
        Map<String ,Object> map = (Map<String, Object>) cacheMap.get("map");
        if(map ==null)
            return this ;
        if(key !=null)
             return map.get(key) ;
        LinkedHashMap<String,Object> tmpmap =new LinkedHashMap<>() ;
        tmpmap.putAll(map);
        return tmpmap ;
    }

    protected Map<String, Object> getMapFromCache(){
        if(cacheMap !=null && cacheMap.containsKey("map"))
            return (Map<String, Object>) cacheMap.get("map");
        return null ;
    }

    protected synchronized void writeCache( Map<String ,Object> map){
        if(!cacheMap.isEmpty())
            return;
        Long time = System.currentTimeMillis() +cacheExptime ;
        Map<String ,Object> tmpmap =new ConcurrentHashMap<>() ;
        tmpmap.putAll(map);
        cacheMap.put("time",time) ;
        cacheMap.put("map", tmpmap) ;
    }
}
