package cn.tianlong.java.demo.db;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.*;
import cn.tianlong.tlobject.db.dbdata.BeanTable;
import cn.tianlong.tlobject.db.dbdata.MapInDB;
import cn.tianlong.tlobject.execl.TLExeclFileUtils;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;
import cn.tianlong.tlobject.utils.TLMsgUtils;

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
    static String sql ="-- tldbdemo1.`user22` definition\n" +
            "\n" +
            "CREATE TABLE `user22` (\n" +
            "  `name` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,\n" +
            "  `number` int DEFAULT NULL,\n" +
            "  `time` datetime DEFAULT NULL,\n" +
            "  PRIMARY KEY (`name`) USING BTREE\n" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC;";
    protected TLTable tb ;
    public DbDemo(String name) {
        super( name);
    }
    public DbDemo(String main, TLObjectFactory myfactory) {
        super(main, myfactory);
    }
    @Override
    protected TLBaseModule init() {
        TLMsg tmsg = new TLMsg().setAction(DB_GETTABLE).setParam(DB_P_TABLENAME, "userTable");
        TLMsg returnmsg =putMsg(DEFAULTDATABASE, tmsg);
        tb = (TLTable) returnmsg.getParam(INSTANCE);
        return this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "initDataTb":
                returnMsg = initDataTb(fromWho, msg);
                break;
            case "insertTb":
                insertTb(fromWho, msg);
                break;
            case "queryTb":
                returnMsg = queryTb(fromWho, msg);
                break;
            case "queryResultIsBean":
                returnMsg = queryResultIsBean(fromWho, msg);
                break;
            case "updateTb":
                returnMsg = updateTb(fromWho, msg);
                break;
            case "deleteTb":
                returnMsg = deleteTb(fromWho, msg);
                break;
            case "dbBean":
                 dbBean(fromWho, msg);
                break;
            case "dbview":
                returnMsg = dbview(fromWho, msg);
                break;
            case "transactionByDB":
                transactionByDB(fromWho, msg);
                break;
            case "clearTb":
                clearTb(fromWho, msg);
                break;
            case "isTableExist":
                isTableExist(fromWho, msg);
                break;
            case "testMapInDB":
                testMapInDB(fromWho, msg);
                break;
            case "testDatabaseSql":
                testDatabaseSql(fromWho, msg);
                break;
            case "testDatabaseSql1":
                testDatabaseSql1(fromWho, msg);
                break;
            case "execToDb":
                execToDb(fromWho, msg);
                break;
            case "dbToExecl":
                dbToExecl(fromWho, msg);
                break;
            default:
                returnMsg = null;
        }
        return returnMsg;
    }

    private void dbToExecl(Object fromWho, TLMsg msg) {
        String filePath=moduleFactory.getConfigDir();
        String saveFile =filePath+"demo.xls";
        BeanTable beanTable =new BeanTable("userTable1",moduleFactory);
        ArrayList<Map<String,Object>> dbTjlist = beanTable.getAll();

        if(dbTjlist !=null && !dbTjlist.isEmpty())
        {

            String sucessFile = TLExeclFileUtils.listToExeclFile(dbTjlist,saveFile,null);
            if( sucessFile ==null)
                System.out.println( "导出execl文件错误");
            else
                System.out.println( "导出execl文件:"+ sucessFile);
        }

    }

    private void execToDb(Object fromWho, TLMsg msg) {
        String tableName ="execltodb" ;
        String filePath=moduleFactory.getConfigDir();
        String execlFile =filePath+"demo.xls";
        List<HashMap<String, Object>> listMap =TLExeclFileUtils.parseExeclFileToList(execlFile,true,null);
        if(listMap ==null)
        {
            putLog("文件不存在或者格式错误："+execlFile,LogLevel.ERROR);
         }
         HashMap<String,String> dbFieles= new HashMap<>();
        dbFieles.put("name","String");
        dbFieles.put("number","String");

        int number= TLDBUtilis.batchInsertList(this,tableName,listMap,null);
        println("导入execl文件，导入数据 :"+number+"个");
    }

    protected void testDatabaseSql(Object fromWho, TLMsg msg){
        String userName = msg.getStringParam("username",null);
        String sql = "select * from  user2  where  name = ? ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("name", userName);
        TLMsg querymsg = new TLMsg().setAction(DB_QUERY)
                .setParam(DB_P_SERVERNAME,"dbserver2")
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_CACHENAME,"testuser")
                .setParam(DB_P_CACHEMODULE,"caffeine")
                .setParam(DB_P_CACHEEXPTIME,2)
              //  .setParam(DB_P_BEANCLASS,userBean.class)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg returnMsg= putMsg(DEFAULTDATABASE, querymsg);
        List data = (List) returnMsg.getParam(DB_R_RESULT);
        println("result:");
        TLMsgUtils.printList(data);
    }
    protected void testDatabaseSql1(Object fromWho, TLMsg msg){
        String userName = msg.getStringParam("username",null);
        int number = TLDataUtils.parseInt(msg.getParam("number"),0);
        String sql = "update  user2 set number=? where  name = ? ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("number", number);
        sqlparams.put("name", userName);
        TLMsg querymsg = new TLMsg().setAction(DB_UPDATE)
                .setParam(DB_P_SERVERNAME,"dbserver2")
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_CACHENAME,"testuser")
                .setParam(DB_P_CACHEMODULE,"caffeine")
                .setParam(DB_P_CACHEEXPTIME,2)
                //  .setParam(DB_P_BEANCLASS,userBean.class)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg returnMsg= putMsg(DEFAULTDATABASE, querymsg);
        Object result = returnMsg.getParam(DB_R_RESULT);
        println("result:"+result.toString());

    }
    private void testMapInDB(Object fromWho, TLMsg msg) {
        MapInDB mymap =new MapInDB("myMap",moduleFactory);
        mymap.put("name","dongq");
        mymap.put("age",55);
        mymap.put("googman",true);
        String name = (String) mymap.get("name");
        Map map = mymap.getAll();
        TLMsgUtils.printMap(map);
        map = mymap.getAll();
        TLMsgUtils.printMap(map);
        HashMap<String ,Object> data =new HashMap<>();
        data.put("name","tianlong");
        data.put("age",55);
        data.put("googman",true);
        mymap.put("tianlong",data);
        Map datasdb = (Map) mymap.get("tianlong");
        TLMsgUtils.printMap(datasdb);
        mymap.putAll(data);
        map = mymap.getAll();
        TLMsgUtils.printMap(map);
      //  mymap.clear();
        MapInDB mymap1 =new MapInDB("myMap1",moduleFactory,"mymap1");

        map = mymap1.getAll();
        TLMsgUtils.printMap(map);
        println("-----------");
        HashMap<String ,Object> data1 =new HashMap<>();
        mymap1.put("name","dongq");
        data1.put("address","大庆2222");
        data1.put("age",99);
        mymap1.put("dongq",data1);
        map = mymap1.getAll();
        TLMsgUtils.printMap(map);

    }

    private void isTableExist(Object fromWho, TLMsg msg) {
        String tableName =msg.getStringParam("tableName",null);
        TLMsg  domsg =createMsg().setAction(DB_ISTABLEEXIST)
                .setParam(DB_P_SQL,sql)
                .setParam(DB_P_TABLENAME,tableName);
        TLMsg resultMsg =putMsg(DEFAULTDATABASE,domsg);
        boolean result =resultMsg.getBooleanParam(RESULT,false);
        if(result)
        println(tableName +"  is exist--------");
        else
            println(tableName +"  is not exist !!!----------");

    }

    private void clearTb(Object fromWho, TLMsg msg) {
        BeanTable beanTable =new BeanTable("userTable","name",moduleFactory);
        int number = beanTable.removeAll();
        System.out.println("数据删除，删除数量:"+number);

    }

    private void insertTb(Object fromWho, TLMsg msg) {
        String numberStr =msg.getStringParam("number",null);
        if(numberStr ==null)
            return  ;
        int number =Integer.parseInt(numberStr) ;
        String username =msg.getStringParam("username",null) ;
        if(username ==null)
            return  ;
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("name",username);
        sqlparams.put("number", number);
        sqlparams.put("time", date());
        TLMsg insertmsg = createMsg().setAction(DB_INSERT)
                .setParam(DB_P_PARAMS, sqlparams);
       TLMsg returnMsg = putMsg(tb, insertmsg);
       if(returnMsg.getIntParam(DB_R_RESULT,0) ==0)
           System.out.println("插入失败："+username);
       else
           System.out.println("插入成功："+username);

        TLMsg tmsg = new TLMsg().setAction(DB_GETTABLE).setParam(DB_P_TABLENAME, "userTable1");
        TLMsg returnmsg =putMsg(DEFAULTDATABASE, tmsg);
        TLBaseModule tb1 = (TLTable) returnmsg.getParam(INSTANCE);
       ArrayList< LinkedHashMap<String, Object>>  datas =new ArrayList<>();
       for (int i =0 ;i <200 ;i++)
       {
           LinkedHashMap<String, Object> data = new LinkedHashMap<>();
           data.put("name",username+i);
           data.put("number", number);
           data.put("time", date());
           datas.add(data);
       }
       long starttime =System.currentTimeMillis();
   //   String isql ="insert into  [table] ( name,number,time )  values( ?,?,? ) ";
       insertmsg = createMsg().setAction(DB_INSERT)
 //              .setParam(DB_P_SQL ,isql)
                .setParam(DB_P_PARAMS, datas);
        returnMsg = putMsg(tb1, insertmsg);
        if(returnMsg.parseBoolean(RESULT,false) ==true)
            System.out.println("插入成功："+returnMsg.getIntParam(DB_R_RESULT,0));
        else
            System.out.println("插入失败：");
        println("时间："+(System.currentTimeMillis()-starttime));
    }

    private TLMsg dbview(Object fromWho, TLMsg msg) {
        String username =msg.getStringParam("username",null) ;
        if(username ==null)
            return null ;
        TLMsg tmsg = createMsg().setAction(DB_GETVIEW).setParam(DB_P_VIEWNAME, "vusers");
        TLMsg returnmsg = putMsg(DEFAULTDATABASE, tmsg);
        TLDBView tv = (TLDBView) returnmsg.getParam(TLObjectFactory.FACTORY_R_MODULEINSTANCE);
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("name", username);
        TLMsg querymsg = createMsg().setAction(DB_QUERY)
                .setParam(DB_P_PARAMS, sqlparams);
       TLMsg resultMsg = putMsg(tv, querymsg);
        ArrayList<LinkedHashMap> datas = (ArrayList<LinkedHashMap>) resultMsg.getListParam(DB_R_RESULT,null);
        if(datas ==null || datas.isEmpty())
        {
            System.out.println("没有数据");
            return null;
        }
        System.out.println("查询结果:");
        TLMsgUtils.printList(datas);
        return  resultMsg ;
    }

    protected TLMsg deleteTb(Object fromWho, TLMsg msg) {
        String username =msg.getStringParam("username",null) ;
        if(username ==null)
            return null ;
        String sql = "delete from  [table] where name=?  ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("name", msg.getParam("username"));
        TLMsg insertmsg = createMsg().setAction(DB_DELETE)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_PARAMS, sqlparams);
        return putMsg(tb, insertmsg);
    }
    private TLMsg updateTb(Object fromWho, TLMsg msg) {
        String numberStr =msg.getStringParam("number",null);
        if(numberStr ==null)
            return null ;
        int newNumber =Integer.parseInt(numberStr) ;
        String username =msg.getStringParam("username",null) ;
        if(username ==null)
            return null ;
        String sql = "update  [table] set number = ?  where name=? ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("number", newNumber);
        sqlparams.put("name", username);
        TLMsg insertmsg = createMsg().setAction(DB_UPDATE)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_PARAMS, sqlparams);
        return putMsg(tb, insertmsg);
    }

    private TLMsg initDataTb(Object fromWho, TLMsg msg) {
        TLMsg  returnMsg =queryTb(this, createMsg().setParam("username","dong1"));
        ArrayList<LinkedHashMap> datas = (ArrayList<LinkedHashMap>) returnMsg.getListParam(RESULT,null);
        if(datas !=null && !datas.isEmpty())
        {
            System.out.println("数据已经初始化完毕，退出");
           return null ;
        }
        String sql = "insert into  [table] (name,number,time) values(?,?,?)";
        String[] names ={"jiang","dong","tian","wang"};
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        System.out.println("开始插入数据");
        for(int j=0 ;j < names.length ;j ++)
        {

            String username = names[j];
            for (int i = 0; i <10; i++)
            {
                System.out.println("insert :"+username+i);
                sqlparams.put("name",username+i);
                sqlparams.put("number", i);
                sqlparams.put("time", date());
                TLMsg insertmsg = createMsg().setAction(DB_INSERT)
                        .setParam(DB_P_SQL, sql)
                        .setParam(DB_P_PARAMS, sqlparams);
                putMsg(tb, insertmsg);
            }
        }

        return null ;
    }
    private TLMsg queryTb(Object fromWho, TLMsg msg) {
        String username=msg.getStringParam("username",null);
        //   String sql = "select * from  [table]  where  name like  ?\"%\" ";
        String sql = "select * from  [table]  where  name = ? ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("name", username);
        TLMsg querymsg = createMsg().setAction(DB_QUERY)
       //         .setParam(DB_P_TABLENAME,"userTable")
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAP)
                .setParam(DB_P_CACHENAME,"user")
                .setParam(DB_P_PARAMS, sqlparams);
        return  putMsg(tb, querymsg);
   //   return  putMsg(DEFAULTDATABASE, querymsg);
    }
    private TLMsg queryResultIsBean(Object fromWho, TLMsg msg) {
        String username=msg.getStringParam("username",null);
        String sql = "select * from  [table]  where  name like  ?\"%\" ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("name", username);
        TLMsg querymsg = createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.BEANMAP)
                .setParam(DB_P_BEANCLASS,userBean.class)
                .setParam(DB_P_PRIMARYKEY,"name")
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg returnMsg =  putMsg(tb, querymsg);
        Map<String,userBean> datas =returnMsg.getMapParam(RESULT,null);
        if(datas!=null){
           for (userBean user:datas.values()) {
               System.out.print("name:"+user.getName());
               System.out.print(" number:"+user.getName());
               System.out.println(" time:"+user.getTime());
           }
        }
        return returnMsg ;
    }

    /**
     * 演示用BeanTable对象操作表
     * @param fromWho
     * @param msg
     */
    protected void dbBean(Object fromWho, TLMsg msg){
        String numberStr =msg.getStringParam("number",null);
        if(numberStr ==null)
            return  ;
        int number =Integer.parseInt(numberStr) ;
        String username =msg.getStringParam("username",null) ;
        if(username ==null)
            return  ;
     //   BeanTable beanTable =new BeanTable("userTable","name",moduleFactory);
         BeanTable beanTable = TLDataBase.getBeanTable("userTable","name",this);

        Map<String, Object> data =beanTable.get(username);
        if(data !=null &&!data.isEmpty())
        {
            println("data is exist :" + username);
            int result= beanTable.remove(username);
            if(result >0)
             println("data is deleted");
            else
            {
                println("delete is error");
                return;
            }
        }
        LinkedHashMap<String ,Object> datas = new LinkedHashMap<>();
        datas.put("name",username);
        datas.put("number",number);
        datas.put("time",date());
        beanTable.add(datas) ;
        System.out.println("userTable 通过 beanTable 插入:");
        TLMsgUtils.printMap(datas);
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("name", username);
         ArrayList<Map<String,Object>> result =beanTable.getAll(sqlparams);
        System.out.println("userTable 通过 beanTable 查询:");
        TLMsgUtils.printList(result);
     //   beanTable.remove(sqlparams);
    //    result =beanTable.getAll(sqlparams);
    //    System.out.println("数据删除，userTable 通过 beanTable 查询:");
   //     TLMsgUtils.printList(result);
     //   Map<String,Object> alldatas =beanTable.getAllBeanMap(userBean.class);
   //     ArrayList<Object> alldatas =beanTable.getAllBeanList(userBean.class);
        String[] fields ={"name","number"};
        TLDBSqlCondition ce = new TLDBSqlCondition() ;
        ce.add("name","大庆",DB_P_EXP_LIKELEFT,"and") ;
        ce.add("number",9999,"<",null) ;
        ArrayList<Map<String,Object>> result1= beanTable.get(ce,fields) ;
        TLMsgUtils.printList(result1);
        ce.clear();
        println("________");
        LinkedHashMap<String, Object> sqlparams1 = new LinkedHashMap<>();
        sqlparams1.put("name", "www");
        ce.add( "name like ?\"%\"",sqlparams1);
        result1= beanTable.get(ce,fields) ;
        TLMsgUtils.printList(result1);
    }
    /**
     * 简单的分布式事务操作。虽然都是对userTable插入数据，但是因为分表，实际是对不同数据库不同表的操作。
     * 要么都插入、要么都取消。原理是对第一个库操作后，不进行数据库连接事务提交（commit），在第二个库操作成功后再提交，
     * 否则事务滚回。相对于一般的数据操作，每个数据操作消息要设定DB_P_TABLENAME，明确操作的表。
     */
    private void transactionByDB(Object fromWho, TLMsg msg) {
        String namesStr =msg.getStringParam("names",null);
        if(namesStr ==null){
            System.out.println("没有设定names,最少两个名字，中间以;间隔。如daqiang;zhangsan");
            System.out.println("以ASCII表数值为分表标准，名字第一个字母小于n在user1，其他的在user2中，为保证插入数据分表到不同的表里，名字第一个字母尽量分别取在字母表的前、后");
            return;
        }
        String[] names =TLDataUtils.splitStrToArray(namesStr,";");
        if(names.length <2)
        {
            System.out.println("names设置错误。最少两个名字，中间以;间隔。如daqiang;zhangsan");
            System.out.println("以ASCII表数值为分表标准，名字第一个字母小于n在user1，其他的在user2中，为保证插入数据分表到不同的表里，名字第一个字母尽量分别取在字母表的前、后");

            return;
        }
        String sql1 = "insert into  [table] (name,number,time) values(?,?,?)";
        ArrayList<TLMsg> msglist =new ArrayList<>();
        for(int i=0 ;i < names.length ;i++)
        {
            System.out.println(names[i]+" ：创立插入消息");
            LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
            sqlparams.put("name", names[i]);
            sqlparams.put("number", 20);
            sqlparams.put("time", date());
            TLMsg tmsg = createMsg().setAction(DB_INSERT) .setParam(DB_P_SQL, sql1)
                    .setParam(DB_P_PARAMS, sqlparams).setParam(DB_P_TABLENAME,"userTable");
            msglist.add(tmsg);
        }
        System.out.println("执行事务。。。");
        TLMsg dbmsg =createMsg().setAction(DB_STARTTRANSACTION).setParam(DB_P_MSGLIST,msglist);
        TLMsg returnMsg = putMsg(DEFAULTDATABASE, dbmsg);
        if(returnMsg.parseBoolean(RESULT,true)==false)
        {
            System.out.println("事务失败，失败消息索引:"+returnMsg.getIntParam("number",0));
        }
        System.out.println("查询事务结果:");
        for(int i=0 ;i < names.length ;i++)
        {
           TLMsg qmsg=createMsg().setParam("username",names[i]);
            System.out.println("查询 username="+names[i]);
           TLMsg resultMsg =queryTb(this,qmsg);
            ArrayList<LinkedHashMap> datas = (ArrayList<LinkedHashMap>) resultMsg.getListParam(RESULT,null);
            if(datas ==null || datas.isEmpty())
                System.out.println("没有数据");
            else {
                System.out.println("查询结果:");
                TLMsgUtils.printList(datas);
            }
        }
    }

    /**
     * 批量插入表
    */
    private void batch() {
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
        TLMsg insertmsg = new TLMsg().setAction(DB_BATCH)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_PARAMS, bparams);
        putMsg(tb, insertmsg);
        System.out.println(" work over ");
        Long nowTime = System.currentTimeMillis();
        Long runtime = nowTime - startTime;
        System.out.println("运行时间：" + runtime);
    }

}
