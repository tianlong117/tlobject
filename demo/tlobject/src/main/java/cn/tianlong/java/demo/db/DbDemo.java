package cn.tianlong.java.demo.db;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLBaseObject;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.cache.TLMemoryCache;
import cn.tianlong.tlobject.db.TLDBView;
import cn.tianlong.tlobject.db.TLDataBase;
import cn.tianlong.tlobject.db.TLTable;
import cn.tianlong.tlobject.db.dbdata.BeanTable;
import cn.tianlong.tlobject.db.dbdata.ListInDB;
import cn.tianlong.tlobject.db.dbdata.MapInDB;
import cn.tianlong.tlobject.modules.TLAppStartUp;

import java.util.*;

import static com.sun.org.apache.xalan.internal.lib.ExsltDatetime.date;

/***
 * 表user结构  CREATE TABLE `userm` (
 *   `name` varchar(255) DEFAULT NULL,
 *   `number` int(11) DEFAULT NULL,
 *   `time` datetime DEFAULT NULL
 * )
 */

public class DbDemo extends TLBaseModule {
    static long startTime;
    protected TLTable tb ;
    public DbDemo(String name) {
        super( name);
    }
    public DbDemo(String main, TLObjectFactory myfactory) {
        super(main, myfactory);
    }
    @Override
    protected TLBaseModule init() {
        TLMsg tmsg = new TLMsg().setAction(DB_GETTABLE)
                .setParam(DB_P_TABLENAME, "userTable");
        TLMsg returnmsg =putMsg(DEFAULTDATABASE, tmsg);
        tb = (TLTable) returnmsg.getParam(TLObjectFactory.FACTORY_R_MODULEINSTANCE);
        return this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "getResult":
                getResult(fromWho, msg);
                break;
            case "insertTb":
                returnMsg = insertTb(fromWho, msg);
                break;
            case "queryTb":
                returnMsg = queryTb(fromWho, msg);
                break;
            default:
                returnMsg = null;
        }
        return returnMsg;
    }
    private TLMsg insertTb(Object fromWho, TLMsg msg) {
        String sql = "insert into  [table] (name,number,time) values(?,?,?)";
        String name = "redistest";
        int number = 44;
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            sqlparams.put("name", name+i);
            sqlparams.put("number", number);
            sqlparams.put("time", date());
            TLMsg insertmsg = new TLMsg().setAction(DB_INSERT)
                    .setParam(DB_P_SQL, sql)
                    .setParam("params", sqlparams);
            putMsg(tb, insertmsg);
        }
        return null ;
    }
    private TLMsg queryTb(Object fromWho, TLMsg msg) {
        String username=msg.getStringParam("username",null);
        String sql = "select * from  [table]  where  name = ? ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("name", username);
        TLMsg querymsg = createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_PARAMS, sqlparams);
       return  putMsg(tb, querymsg);
    }
    private void testtransactionByDB() {
        String sql = "insert into  user1 (name,number,date) values(?,?,?)";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("name", "dongq7");
        sqlparams.put("number", 20);
        sqlparams.put("data", date());
        TLMsg msg1 = createMsg().setAction(DB_INSERT) .setParam(DB_P_SQL, sql)
                .setParam(DB_P_PARAMS, sqlparams).setParam(DB_P_SERVERNAME,"dbserver1");
        String sql1 = "insert into  user2 (name,number,date) values(?,?,?)";
        LinkedHashMap<String, Object> sqlparams1 = new LinkedHashMap<>();
        sqlparams1.put("name", "dongq7");
        sqlparams1.put("number", 30);
        sqlparams1.put("data", date());
        TLMsg msg2 = createMsg().setAction(DB_INSERT) .setParam(DB_P_SQL, sql1)
                .setParam(DB_P_PARAMS, sqlparams1).setParam(DB_P_SERVERNAME,"dbserver2");
        ArrayList<TLMsg> msglist =new ArrayList<>();
        msglist.add(msg2);
        msglist.add(msg1);
        TLMsg msg =createMsg().setAction(DB_STARTTRANSACTION).setParam(DB_P_MSGLIST,msglist);
        TLMsg returnMsg = putMsg(DEFAULTDATABASE, msg);
        System.out.println("time:");
    }
    private void testtransaction() {
        TLMsg tmsg = new TLMsg().setAction(DB_GETTABLE)
                .setParam(DB_P_TABLENAME, "userTable");
        TLMsg returnmsg =putMsg(DEFAULTDATABASE, tmsg);
        TLTable table = (TLTable) returnmsg.getParam(TLObjectFactory.FACTORY_R_MODULEINSTANCE);
        String sql = "insert into  user1 (name,number,date) values(?,?,?)";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("name", "dongq3");
        sqlparams.put("number", 20);
        sqlparams.put("data", date());
        TLMsg msg1 = createMsg().setAction(DB_INSERT) .setParam(DB_P_SQL, sql) .setParam(DB_P_PARAMS, sqlparams);
        String sql1 = "insert into  user2 (name,number,date) values(?,?,?)";
        LinkedHashMap<String, Object> sqlparams1 = new LinkedHashMap<>();
        sqlparams1.put("name", "dongq3");
        sqlparams1.put("number", 30);
        sqlparams1.put("data", date());
        TLMsg msg2 = createMsg().setAction(DB_INSERT) .setParam(DB_P_SQL, sql1) .setParam(DB_P_PARAMS, sqlparams1);
        ArrayList<TLMsg> msglist =new ArrayList<>();
        msglist.add(msg1);
        msglist.add(msg2);
        TLMsg msg =createMsg().setAction(DB_STARTTRANSACTION).setParam(DB_P_MSGLIST,msglist);
        TLMsg returnMsg = putMsg(table, msg);
        System.out.println("time:");
    }
    protected void testDatabaseSql(){
        String sql = "select * from  user1  where  name = ? ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("name", "dongq");
        TLMsg querymsg = new TLMsg().setAction(DB_EXECSQL)
                .setParam(DB_DBSEVERMODULENAME,"dbserver2")
                .setParam(DB_P_SQLTYPE,DB_QUERY)
                .setParam("sql", sql)
                .setParam("resultType", TLDataBase.RESULT_TYPE.BEANLIST)
                .setParam(DB_P_BEANCLASS,userBean.class)
                .setParam("params", sqlparams);
        TLMsg returnMsg= putMsg(DEFAULTDATABASE, querymsg);
        List data = (List) returnMsg.getParam(DB_R_RESULT);
        System.out.println("time:");
    }
    private void testBeanResult() {
        String sql = "select * from  [table]  where  name = ? ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("name", "dongq");
        TLMsg querymsg = new TLMsg().setAction("query")
                .setParam("sql", sql)
                .setParam("resultType", TLDataBase.RESULT_TYPE.BEANLIST)
                .setParam(DB_P_BEANCLASS,userBean.class)
                .setParam("params", sqlparams);
        TLMsg returnMsg= putMsg(tb, querymsg);
        List data = (List) returnMsg.getParam(DB_R_RESULT);
        System.out.println("time:");

       String dbtable =TLDataBase.getTableServer("user1",this,null);
        System.out.println("time:");
    }

    protected void testDbBeanCache(){
        TLMemoryCache mycache = new TLMemoryCache(50);
        BeanTable beanTable =new BeanTable("userm","name",getFactory());
        Long starttime =moduleFactory.getRunTime(false);
        Object value = beanTable.get("dongq99");
        Long time =moduleFactory.getRunTime(false)-starttime ;
        System.out.println("time:"+time);
        mycache.writeCache("dongq99", value);
        starttime =moduleFactory.getRunTime(false);
        Object data = mycache.getCache("dongq99");
        if(data.equals(mycache))
            value = beanTable.get("dongq99");
        time =moduleFactory.getRunTime(false)-starttime ;
        System.out.println("time:"+time);

        starttime =moduleFactory.getRunTime(false);
        value = beanTable.get("dongq99");
        time =moduleFactory.getRunTime(false)-starttime ;
        System.out.println("time:"+time);
    }
    protected void testDbBean1(){
        BeanTable beanTable =new BeanTable("testbean","id",getFactory());
        LinkedHashMap<String ,Object> datas = new LinkedHashMap<>();
        datas.put("age",100);
        datas.put("city","大庆");
        ArrayList<Map<String,Object>> result =beanTable.getAll(datas);
        System.out.println(":-------");
        beanTable.update("111","age",400);
    }
    protected void testDbBean(){
        BeanTable beanTable =new BeanTable("testbean","id",getFactory());
        LinkedHashMap<String ,Object> datas = new LinkedHashMap<>();
        datas.put("id","1111");
        datas.put("name","笑声");
        datas.put("age",200);
        beanTable.add(datas) ;
        datas.put("id","222");
        datas.put("name","xiaoqiang");
        datas.put("age",100);
        beanTable.add(datas) ;
        datas.clear();
        datas.put("name","xiaoqiang1");
        datas.put("age",1001);
        beanTable.update("222",datas) ;
        ArrayList<Map<String,Object>> result =beanTable.getAll();
        ArrayList<LinkedHashMap> newDatas =new ArrayList<>();
        for (Map<String,Object> map :result){
            map.put("age",100);
            map.put("id",(String)map.get("id")+"test");
            LinkedHashMap<String,Object> newmap =new LinkedHashMap<>() ;
            newmap.putAll(map);
            newDatas.add(newmap);
        }
       boolean ifadd =beanTable.addAll(newDatas) ;
    }
    protected void testlistIndb1(){
   //     ListInDB list5 =new ListInDB("mylist5",this);
   //     ArrayList<HashMap<String,Object>> mylist5 =list5.getList();
        MapInDB map5= new MapInDB("maplisttest",moduleFactory) ;
   //     map5.put("list1",mylist5);
  //      map5.put("list2",mylist5);
        Map<String,Object> result =map5.getAll();
        for(String key : result.keySet()) {
            System.out.println(key +":-------");
            ArrayList<HashMap<String,Object>> list = (ArrayList<HashMap<String, Object>>) result.get(key);
            for(HashMap<String,Object>map :list)
                for(String key1 : map.keySet()){
                    System.out.println(key1 +":"+map.get(key1));
                }
        }
    }
    private void testListIndb0() {

        ListInDB list5 =new ListInDB("mylist5",moduleFactory);
        ArrayList<HashMap<String,Object>> mylist5 =list5.getList();
        for(HashMap<String,Object> value :mylist5)
            for(String key : value.keySet()){
                System.out.println(key +":"+value.get(key));
            }
        MapInDB map5= new MapInDB("maplisttest",moduleFactory) ;
        map5.put("list1",mylist5);
        map5.put("list2",mylist5);

    }
    private void testListIndb() {
        MapInDB map =new MapInDB("maptext",moduleFactory);
        map.put("long",99999);
        map.put("nianling",88);
        map.put("shengri",new Date());
        map.put("time",System.currentTimeMillis());
        map.put("xingbie","男");
        map.put("shuoming","从上面的源码可以很清晰的看出null值不用担心的理由。但是，这也恰恰给了我们隐患。我们应当注意到，当object为null 时，String.valueOf（object）的值是字符串”null”，而不是null！！！在使用过程中切记要注意。");

        ListInDB list =new ListInDB("mylist",moduleFactory);
        list.add("dongq");
        list.add("wangpeng");
        list.add("wuxiao");
        list.add("zhangqiang");
        list.add("1");
        String name = (String) list.get(1);
        System.out.println(name);
        System.out.println("----------");
        ArrayList<String> mylist =list.getList();
        for(String value :mylist)
            System.out.println(value);
        ListInDB list1 =new ListInDB("mylist1",moduleFactory);
        MapInDB mapt =new MapInDB("maptext",moduleFactory);
        Map<String,Object> datas = mapt.getAll();
        list1.add(datas);
        list1.add(datas);
        ArrayList<HashMap<String,Object>> mylist2 =list1.getList();
        ListInDB list5 =new ListInDB("mylist5",moduleFactory);
        list5.addAll(0,mylist2);
        ArrayList<HashMap<String,Object>> mylist5 =list5.getList();
        for(HashMap<String,Object> value :mylist5)
            for(String key : value.keySet()){
                System.out.println(key +":"+value.get(key));
            }
    }

    private void testMapIndb() {
        MapInDB map =new MapInDB("maptext",moduleFactory);
       map.put("long",99999);
       map.put("nianling",88);
       map.put("shengri",new Date());
       map.put("time",System.currentTimeMillis());
       map.put("xingbie","男");
       map.put("shuoming","从上面的源码可以很清晰的看出null值不用担心的理由。但是，这也恰恰给了我们隐患。我们应当注意到，当object为null 时，String.valueOf（object）的值是字符串”null”，而不是null！！！在使用过程中切记要注意。");
        int xingbie = (int) map.get("nianling");
        System.out.println("xingbie: "+xingbie);
        HashMap<String ,Object> sonmap =new HashMap<>();
        sonmap.put("s1","1111111111");
        sonmap.put("s2",111111111);
        map.put("sonmap",sonmap);
        HashMap<String ,Object> sonmap1 = (HashMap<String ,Object>) map.get("sonmap");
        for(String key : sonmap1.keySet()){
         System.out.println(key +":"+sonmap1.get(key));
        }
     //   map.remove("sonmap");
     //   sonmap1 = (HashMap<String ,Object>) map.get("sonmap");
    //    if(sonmap1 !=null)
    //    {
   //         for(String key : sonmap1.keySet()){
      //          System.out.println(key +":"+sonmap1.get(key));
   //         }
   //     }
     Map<String,Object> datas = map.getAll();
     if(datas ==null)
          return;
      for(String key : datas.keySet()){
           System.out.println(key +":"+datas.get(key));
       }
        TLMsg msg =createMsg().setParam("testst","sdfdsfd");
       map.put("msg",msg);
    }

   private void testCreateTable(){
       TLMsg tmsg = new TLMsg().setAction("getTable")
               .setParam("tableName", "members_test11")
               .setParam("copyTable","information_a");
       TLMsg returnmsg =putMsg(DEFAULTDATABASE, tmsg);
       TLTable tb = (TLTable) returnmsg.getParam(TLObjectFactory.FACTORY_R_MODULEINSTANCE);

   }
    private void userModle() {
        userModle modle = (userModle) getModule("userModle");
        TLMsg total = putMsg(modle, createMsg().setAction("total"));
        System.out.println("总数:" + total.getParam("result"));
        String name = "yy888";
        TLMsg returnMsg = putMsg(modle, createMsg().setAction("findUser").setParam("userName", name));
        List datas = (List) returnMsg.getParam("result");
        if (datas == null || datas.isEmpty()) {
            System.out.println("没有数据");
            return;
        }
        System.out.println(name + " 数据:");
        for (int i = 0; i < datas.size(); i++) {
            Object[] unit = (Object[]) datas.get(i);
            for (int j = 0; j < unit.length; j++) {
                System.out.print(unit[j] + "  ");
            }
            System.out.println("");
        }

    }


    private void findall(TLTable tb) {
        startTime = System.currentTimeMillis();
        TLMsg querymsg = new TLMsg().setAction("findAll");
        querymsg.setParam("cacheName", "table_user");
        querymsg.setParam("cacheKey", "all");
        //	querymsg.setParam("resultFor",this)
        //			.setParam("resultAction","getResult");
        TLMsg returnmsg = putMsg(tb, querymsg);
        List datas = (List) returnmsg.getParam("result");
        if (datas == null || datas.isEmpty()) {
            System.out.println("没有数据");
            return;
        }
        for (int i = 0; i < datas.size(); i++) {
            Object[] unit = (Object[]) datas.get(i);
            for (int j = 0; j < unit.length; j++) {
                System.out.print(unit[j] + "  ");
            }
            System.out.println("");
        }
        Long nowTime = System.currentTimeMillis();
        Long runtime = nowTime - startTime;
        System.out.println("运行时间：" + runtime);
    }

    protected void total(TLTable tb) {
        startTime = System.currentTimeMillis();
        TLMsg totalMsg = createMsg().setAction("total");
        totalMsg.setParam("cacheName", "table_user");
        totalMsg.setParam("cacheKey", "total");
        List totalDatas = (List) putMsg(tb, totalMsg).getParam("result");
        Long totalNumber = Long.valueOf(0);
        for (int i = 0; i < totalDatas.size(); i++) {
            Object[] unit = (Object[]) totalDatas.get(i);
            totalNumber = totalNumber + (Long) unit[0];
            System.out.println("");
        }
        System.out.println("总数：" + totalNumber);
        Long nowTime = System.currentTimeMillis();
        Long runtime = nowTime - startTime;
        System.out.println("运行时间：" + runtime);
    }

    private void find(TLTable tb) {
        startTime = System.currentTimeMillis();
        TLMsg querymsg = new TLMsg().setAction("find")
                .setParam("key", "name")
                .setParam("value", "yyyy999");
        querymsg.setParam("cacheName", "table_user");
        querymsg.setParam("cacheKey", "yy888");
        //	querymsg.setParam("resultFor",this)
        //			.setParam("resultAction","getResult");
        TLMsg returnmsg = putMsg(tb, querymsg);
        List datas = (List) returnmsg.getParam("result");
        if (datas == null || datas.isEmpty()) {
            System.out.println("没有数据");
            return;
        }
        System.out.println("数据------------------");
        for (int i = 0; i < datas.size(); i++) {
            Object[] unit = (Object[]) datas.get(i);
            for (int j = 0; j < unit.length; j++) {
                System.out.print(unit[j] + "  ");
            }
            System.out.println("");
        }
        Long nowTime = System.currentTimeMillis();
        Long runtime = nowTime - startTime;
        System.out.println("运行时间：" + runtime);

    }
    private void testviewOfAppManger() {
        startTime = System.currentTimeMillis();
        TLMsg returnmsg;
        TLMsg tmsg = new TLMsg().setAction(DB_GETVIEW).setParam(DB_P_VIEWNAME, "adminmenue");
        returnmsg = putMsg(DEFAULTDATABASE, tmsg);
        TLDBView tv = (TLDBView) returnmsg.getParam(TLObjectFactory.FACTORY_R_MODULEINSTANCE);
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("userid", "admin");
        TLMsg querymsg = new TLMsg().setAction(DB_QUERY)
                .setParam("params", sqlparams);
        returnmsg = putMsg(tv, querymsg);
        List datas = (List) returnmsg.getParam("result");
        System.out.println("数据------------------");
        for (int i = 0; i < datas.size(); i++) {
            Map<String,Object> unit = ( Map<String,Object>) datas.get(i);
            for (String key :unit.keySet()) {
                System.out.println(key+ " : " +unit.get(key));
            }
            System.out.println("");
        }
        Long nowTime = System.currentTimeMillis();
        Long runtime = nowTime - startTime;
        System.out.println("运行时间：" + runtime);

    }
    private void testview() {
        startTime = System.currentTimeMillis();
        TLMsg returnmsg;
        TLMsg tmsg = new TLMsg().setAction(DB_GETVIEW).setParam(DB_P_VIEWNAME, "vusers");
        returnmsg = putMsg(DEFAULTDATABASE, tmsg);
        TLDBView tv = (TLDBView) returnmsg.getParam(TLObjectFactory.FACTORY_R_MODULEINSTANCE);
        int number = 110;
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("number", number);
        TLMsg querymsg = new TLMsg().setAction("query")
                .setParam("params", sqlparams);
        querymsg.setParam("cacheName", "table_user");
        querymsg.setParam("cacheKey", "" + number);
        returnmsg = putMsg(tv, querymsg);
        List datas = (List) returnmsg.getParam("result");
        System.out.println("数据------------------");
        for (int i = 0; i < datas.size(); i++) {
            Object[] unit = (Object[]) datas.get(i);
            for (int j = 0; j < unit.length; j++) {
                System.out.print(unit[j] + "  ");
            }
            System.out.println("");
        }
        Long nowTime = System.currentTimeMillis();
        Long runtime = nowTime - startTime;
        System.out.println("运行时间：" + runtime);

    }




    private void getResult(Object fromWho, TLMsg msg) {
        List datas = (List) msg.getParam("result");
        if (datas.isEmpty()) {
            System.out.println("getResult 返回没有数据");
            return;
        }
        System.out.println("getResult 返回 数据----------------");
        for (int i = 0; i < datas.size(); i++) {
            Object[] unit = (Object[]) datas.get(i);
            for (int j = 0; j < unit.length; j++) {
               System.out.print( unit[j] );
            }
            System.out.println("");
        }
        Long nowTime = System.currentTimeMillis();
        Long runtime = nowTime - startTime;
        System.out.println("getResult 运行时间：" + runtime);
    }

    private void delete() {
        String sql = "delete from  [table] where  name = ?";
        String name = "batch446";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("name", name);
        TLMsg querymsg = createMsg().setAction("delete")
                .setParam("sql", sql).setParam("params", sqlparams);
        putMsg(tb, querymsg);
    }


    private void updata() {
        startTime = System.currentTimeMillis();
        String sql = "update  [table] set number =? where name =? ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("number", 888);
        sqlparams.put("name", "batch446");
        TLMsg insertmsg = new TLMsg().setAction(DB_UPDATE)
                .setParam("sql", sql)
                .setParam("params", sqlparams);
        TLMsg returnmsg = putMsg(tb, insertmsg);
        Long nowTime = System.currentTimeMillis();
        Long runtime = nowTime - startTime;
        System.out.println("运行时间：" + runtime);
    }
    private void batch(TLTable tb) {
        startTime = System.currentTimeMillis();
        String sql = "insert into  userm (name,number,time) values(?,?,?)";
        String name = "yyyy999";
        int number = 255;
        Object[][] bparams = new Object[500][3];
        for (int i = 0; i < 500; i++) {
            bparams[i][0] = name;
            bparams[i][1] = number;
            bparams[i][2] = date() + 1;
        }
        TLMsg insertmsg = new TLMsg().setAction("batch")
                .setParam("sql", sql)
                .setParam("params", bparams);
        TLMsg returnmsg = putMsg(tb, insertmsg);
        System.out.println(" work over ");
        Long nowTime = System.currentTimeMillis();
        Long runtime = nowTime - startTime;
        System.out.println("运行时间：" + runtime);
    }
    private void printDBResult(List datas){
        if(datas ==null)
            return;
        for (int i = 0; i < datas.size(); i++) {
            Map<String,Object> unit= (Map<String, Object>) datas.get(i);
            for(Map.Entry<String, Object> info : unit.entrySet()){

                System.out.println(info.getKey()+":"+info.getValue());
            }
            System.out.println("-------");
        }
    }

}
