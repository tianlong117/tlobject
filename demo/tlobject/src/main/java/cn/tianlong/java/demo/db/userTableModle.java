package cn.tianlong.java.demo.db;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLBaseTableModle;
import cn.tianlong.tlobject.db.TLDataBase;
import cn.tianlong.tlobject.db.dbdata.BeanTable;
import cn.tianlong.tlobject.utils.TLDataUtils;

import java.util.*;

import static com.sun.org.apache.xalan.internal.lib.ExsltDatetime.date;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class userTableModle extends TLBaseTableModle {

    public userTableModle(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
        tableName="userTable";
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case "queryByNumber":
                returnMsg=queryByNumber( fromWho,  msg);
                break;
            case "batch":
                batch( fromWho,  msg);
                break;
            default:
                returnMsg=null;
        }
        return returnMsg;
    }

     private List<LinkedHashMap> productDatas(String name){
         ArrayList<LinkedHashMap> datas =new ArrayList<>() ;
         for (int i=0 ;i<200 ;i++){
             LinkedHashMap<String, Object> data =new LinkedHashMap<>();
             data.put("name",name+i);
             data.put("number",i+3000);
             data.put("time",date());
             datas.add(data);
         }
         return datas ;
     }
    private void batch(Object fromWho, TLMsg msg) {

        List<LinkedHashMap> datas = productDatas("cpinxxx");
        String sql = "insert into  [table] (name,number,time) values(?,?,?)";
        TLMsg insertmsg = new TLMsg().setAction(DB_BATCH)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_PARAMS, datas);
        Long startTime =System.currentTimeMillis();
        putMsg(table, insertmsg);
        System.out.println(" work over ");
        Long nowTime = System.currentTimeMillis();
        Long runtime = nowTime - startTime;
        System.out.println("运行时间：" + runtime);

        /**
       List<LinkedHashMap> datas1 = productDatas("www");
        BeanTable beanTable =new BeanTable("userTable","name",moduleFactory);
        Long nowTime = System.currentTimeMillis();
       beanTable.addAll(datas1);
       System.out.println("addAll 运行时间：" + (System.currentTimeMillis()-nowTime));
        List<LinkedHashMap> datas2 = productDatas("wwaa");
        nowTime = System.currentTimeMillis();
       beanTable.addAllByBatch(datas2);
       System.out.println("addAllByBatch 运行时间：" + (System.currentTimeMillis()-nowTime));
      **/
    }

    private TLMsg queryByNumber(Object fromWho, TLMsg msg) {
        Long startTime  =moduleFactory.getRunTime(false);
        int number=TLDataUtils.parseInt(msg.getParam("number"),0);
        boolean  ifCache =TLDataUtils.parseBoolean(msg.getAndRemoveParam("ifCache"),false);
        String sql = "select * from  [table]  where  number = ? ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("number", number);
        TLMsg querymsg = createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_ORDERBY,"name")
                .setParam(DB_P_PARAMS, sqlparams);
        if(ifCache)
        {
            querymsg.setParam("cacheName","usertable_number");
            println("启动缓存————————");
        }
      TLMsg returnMsg =  putMsg(table, querymsg);
        Long endTime  =moduleFactory.getRunTime(false);
        println("run time :"+ (endTime-startTime));
        return  returnMsg ;
    }

}
