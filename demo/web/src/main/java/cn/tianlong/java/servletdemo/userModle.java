package cn.tianlong.java.servletdemo;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLBaseTableModle;
import cn.tianlong.tlobject.db.TLDataBase;
import com.google.gson.internal.LinkedTreeMap;

import java.util.LinkedHashMap;
import java.util.List;

import static com.sun.org.apache.xalan.internal.lib.ExsltDatetime.date;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class userModle extends TLBaseTableModle {

    public userModle(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
        tableName="user";
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg;
        switch (msg.getAction()) {
            case "findUser":
                returnMsg=findUser( fromWho,  msg);
                break;
            case "total":
                returnMsg=total( fromWho,  msg);
                break;
            case "insert":
                returnMsg=insert( fromWho,  msg);
                break;
            case "insertMap":
                returnMsg=insertMap( fromWho,  msg);
                break;
            default:
                returnMsg=null;
        }
        return returnMsg;
    }

    private TLMsg insertMap(Object fromWho, TLMsg msg) {
     /**
        try {
            Thread.sleep(20000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
      **/
        LinkedTreeMap<String, Object> datas = (LinkedTreeMap<String, Object>) msg.getParam("datas");
        LinkedHashMap<String, Object>  sqldatas = new LinkedHashMap<>();
        sqldatas.put("name",datas.get("name"));
        sqldatas.put("number",(new Double((Double) datas.get("number")).intValue() ));
        sqldatas.put("time",datas.get("time"));
        return  insertHashMap(sqldatas,"[table]");
    }

    private TLMsg insert(Object fromWho, TLMsg msg) {
        System.out.println("start insert------------");
        String sql = "insert into  [table] (name,number,time) values(?,?,?)";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        putMsg(table, createMsg().setAction("setConnection"));
        List datas = (List) msg.getParam("datas");
        TLMsg returnmsg = new TLMsg();
        int total= 0;
        for(int i=0;i< datas.size();i++){
            List unit= (List) datas.get(i);
            sqlparams.put("name", unit.get(0)+"1");
            sqlparams.put("number", (new Double((Double)unit.get(1)).intValue() ) );
            sqlparams.put("time", date());
            TLMsg insertmsg = new TLMsg().setAction("insert")
                    .setParam("sql", sql)
                    .setParam("resultType", TLDataBase.RESULT_TYPE.ARRAYLIST)
                    .setParam("params", sqlparams);
            returnmsg = putMsg(table, insertmsg);
            total=total+(int)returnmsg.getParam("number");
        }
     return   createMsg().setParam("number",total);
    }

    @Override
    protected TLMsg total(Object fromWho, TLMsg msg) {
       msg.setParam("cacheName","table_user");
       msg.setParam("cacheKey","user_tocal");
      return super.total(fromWho,msg);
    }
    private TLMsg findUser(Object fromWho, TLMsg msg) {
        LinkedHashMap<String, Object> dbparams =new LinkedHashMap<>();
        dbparams.put("name",msg.getParam("userName"));
        TLMsg querymsg=createMsg().setAction(DB_FIND)
                .setParam(DB_P_PARAMS,dbparams);
        querymsg.setParam("cacheName","table_user");
        querymsg.setParam("cacheKey",msg.getParam("userName"));
        return  putMsg(table,querymsg);
    }
}
