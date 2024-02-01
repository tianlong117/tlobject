package cn.tianlong.java.demo.db;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLBaseTableModle;
import cn.tianlong.tlobject.db.TLDataBase;
import cn.tianlong.tlobject.utils.TLDataUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;

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

    private void batch(Object fromWho, TLMsg msg) {
        ArrayList<HashMap<String,Object>> datas =new ArrayList<>() ;
        String name ="chanp" ;
        for (int i=2001 ;i<20000 ;i++){
            LinkedHashMap<String, Object> data =new LinkedHashMap<>();
            data.put("name",name+i);
            data.put("number",i+3000);
            data.put("time",date());
            datas.add(data);
        }
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
