package cn.tianlong.java.demo.db;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.cache.TLMemoryCache;
import cn.tianlong.tlobject.db.TLDBView;
import cn.tianlong.tlobject.db.TLDataBase;
import cn.tianlong.tlobject.db.TLTable;
import cn.tianlong.tlobject.db.dbdata.BeanTable;
import cn.tianlong.tlobject.db.dbdata.ListInDB;
import cn.tianlong.tlobject.db.dbdata.MapInDB;
import cn.tianlong.tlobject.utils.TLDataUtils;
import cn.tianlong.tlobject.utils.TLMapUtils;
import cn.tianlong.tlobject.utils.TLMsgUtils;
import org.apache.commons.beanutils.BeanUtils;

import java.lang.reflect.InvocationTargetException;
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
        TLMsg tmsg = new TLMsg().setAction(DB_GETTABLE).setParam(DB_P_TABLENAME, "userTable");
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
            default:
                returnMsg = null;
        }
        return returnMsg;
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
        ArrayList<LinkedHashMap> datas = (ArrayList<LinkedHashMap>) resultMsg.getListParam(RESULT,null);
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
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAP)
                .setParam("cacheName","user")
                .setParam(DB_P_PARAMS, sqlparams);
        return  putMsg(tb, querymsg);
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
        BeanTable beanTable =new BeanTable("userTable","name",moduleFactory);
        LinkedHashMap<String ,Object> datas = new LinkedHashMap<>();
        datas.put("name","beanTable");
        datas.put("number",1);
        datas.put("time",date());
        beanTable.add(datas) ;
        System.out.println("beanTable插入:");
        TLMsgUtils.printMap(datas);
        datas.put("name","tBeanTable");
        datas.put("number",2);
        datas.put("time",date());
        beanTable.add(datas) ;
        System.out.println("beanTable插入:");
        TLMsgUtils.printMap(datas);
      //  datas.clear();
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("name", "tBeanTable");
         ArrayList<Map<String,Object>> result =beanTable.getAll(sqlparams);
        System.out.println("beanTable查询:");
        TLMsgUtils.printList(result);
     //   Map<String,Object> alldatas =beanTable.getAllBeanMap(userBean.class);
   //     ArrayList<Object> alldatas =beanTable.getAllBeanList(userBean.class);


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
     * 同一实体数据库中的事务。与分布式事务操作的不同，不设定数据操作消息DB_P_TABLENAME。
     * 最终消息处理发送给表。
     */
    private void transaction() {
        TLMsg tmsg = new TLMsg().setAction(DB_GETTABLE)
                .setParam(DB_P_TABLENAME, "userTable");
        String sql1 = "insert into  user1 (name,number,time) values(?,?,?)";
        LinkedHashMap<String, Object> sqlparams1 = new LinkedHashMap<>();
        sqlparams1.put("name", "dongq3");
        sqlparams1.put("number", 20);
        sqlparams1.put("data", date());
        TLMsg msg1 = createMsg().setAction(DB_INSERT) .setParam(DB_P_SQL, sql1) .setParam(DB_P_PARAMS, sqlparams1);
        String sql2 = "insert into  user2 (name,number,time) values(?,?,?)";
        LinkedHashMap<String, Object> sqlparams2 = new LinkedHashMap<>();
        sqlparams2.put("name", "dongq3");
        sqlparams2.put("number", 30);
        sqlparams2.put("data", date());
        TLMsg msg2 = createMsg().setAction(DB_INSERT) .setParam(DB_P_SQL, sql2) .setParam(DB_P_PARAMS, sqlparams2);
        ArrayList<TLMsg> msglist =new ArrayList<>();
        msglist.add(msg1);
        msglist.add(msg2);
        TLMsg msg =createMsg().setAction(DB_STARTTRANSACTION).setParam(DB_P_MSGLIST,msglist);
        TLMsg returnmsg =putMsg(DEFAULTDATABASE, tmsg);
        TLTable table = (TLTable) returnmsg.getParam(TLObjectFactory.FACTORY_R_MODULEINSTANCE);
        putMsg(table, msg);

    }
    protected void testDatabaseSql(){
        String sql = "select * from  user1  where  name = ? ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("name", "dongq");
        TLMsg querymsg = new TLMsg().setAction(DB_EXECSQL)
                .setParam(DB_P_SERVERNAME,"dbserver2")
                .setParam(DB_P_SQLTYPE,DB_QUERY)
                .setParam("sql", sql)
                .setParam("resultType", TLDataBase.RESULT_TYPE.BEANLIST)
                .setParam(DB_P_BEANCLASS,userBean.class)
                .setParam("params", sqlparams);
        TLMsg returnMsg= putMsg(DEFAULTDATABASE, querymsg);
        List data = (List) returnMsg.getParam(DB_R_RESULT);
        System.out.println("time:");
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

}
