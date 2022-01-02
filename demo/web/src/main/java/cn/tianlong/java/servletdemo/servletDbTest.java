package cn.tianlong.java.servletdemo;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.servletutils.TLWServModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLDBSqlConditionExpression;
import cn.tianlong.tlobject.db.TLDBView;
import cn.tianlong.tlobject.db.TLDataBase;
import cn.tianlong.tlobject.db.TLTable;

import java.util.LinkedHashMap;
import java.util.List;

import static com.sun.org.apache.xalan.internal.lib.ExsltDatetime.date;
import static java.lang.Thread.sleep;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class servletDbTest extends TLWServModule {
    TLTable tb;
    public servletDbTest(){
        super();
    }
    public servletDbTest(String name ){
        super(name);
    }
    public servletDbTest(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected TLBaseModule init(){
        TLMsg tmsg = new TLMsg().setAction("getTable").setParam("tableName","user");
        TLMsg returnmsg =putMsg("database",tmsg);
        tb= (TLTable) returnmsg.getParam(TLObjectFactory.FACTORY_R_MODULEINSTANCE);
        return this ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case "find":
                find(fromWho,msg);
                break;
            case "query":
                query(fromWho,msg);
                break;
            case "view":
                testview(fromWho,msg);
                break;
            case "dbmodle":
                dbmodle(fromWho,msg);
                break;
            case "dbservice":
                returnMsg= dbservice(fromWho,msg);
                break;
            case "dbmodleGetCacheKey":
                returnMsg= dbmodleGetCacheKey(fromWho,msg);
                break;
            default:
                putMsg("error",creatOutMsg().setAction("setError").setParam("content","no action"));
        }
        return returnMsg ;
    }

    private TLMsg dbmodleGetCacheKey(Object fromWho, TLMsg msg) {
       return createMsg().setParam("cacheKey",dbmodleGetCacheKey());
    }
    private String dbmodleGetCacheKey() {
       return getUserData("name");
    }
    private TLMsg dbservice(Object fromWho, TLMsg msg) {
        long startTime =System.currentTimeMillis();
        String name= (String) msg.getParam("name");
        userModle modle= (userModle) getModule("userModle");
        TLMsg servicemsg=createMsg();
        TLMsg total =putMsg(modle,createMsg().setAction("total"));
        servicemsg.setParam("total",total.getParam("result"));
        TLMsg returnMsg =putMsg(modle,createMsg().setAction("findUser").setParam("userName",name));
        List datas = (List) returnMsg.getParam("result");
        Long nowTime =System.currentTimeMillis();
        Long runtime=nowTime-startTime;
        servicemsg.setParam("time",runtime);
        if(datas==null || datas.isEmpty())
        {
            servicemsg.setParam("result",null);
            putUserMsg(servicemsg);
            return null;
        }
        servicemsg.setParam("result",datas);
   //     putUserMsg(servicemsg);
        return servicemsg;
    }

    private void dbmodle(Object fromWho, TLMsg msg) {
        long startTime =System.currentTimeMillis();
        outData odata =  creatOutDataMsg("dbmodle");
        String name= (String) msg.getParam("name");
        TLMsg total =putMsg("userModle",createMsg().setAction("total"));
        odata.addData("总数:"+total.getParam("result"));
        TLMsg returnMsg =putMsg("userModle",createMsg().setAction("findUser").setParam("userName",name));
        List datas = (List) returnMsg.getParam("result");
        Long nowTime =System.currentTimeMillis();
        Long runtime=nowTime-startTime;
        odata.addData("time","数据查询时间："+runtime);
        if(datas==null || datas.isEmpty())
        {
            odata.addData(name+" 没有数据");
            putOutData(odata);
            return;
        }
        odata.addData("datas",datas);
        odata.setParam("cacheKey",dbmodleGetCacheKey() );
        putOutData(odata);
    }

    private void find(Object fromWho, TLMsg msg) {
        outData odata =  creatOutDataMsg();
        String userName=getUserData("name");
        long startTime =System.currentTimeMillis();
        LinkedHashMap<String, Object> sqlCondition = new LinkedHashMap<>();
        TLDBSqlConditionExpression userid =new TLDBSqlConditionExpression("name",userName);
        sqlCondition.put("name", userid);
        TLMsg qmsg=createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQLCONDITION, sqlCondition);
        TLMsg returnmsg = putMsg(tb,qmsg);
        Long nowTime =System.currentTimeMillis();
        Long runtime=nowTime-startTime;
        odata.addData("数据查询时间："+runtime);
        List datas = (List) returnmsg.getParam("result");

        if(datas==null || datas.isEmpty())
        {
            odata.addData(name+" 没有数据");
            putOutData(odata);
            return;
        }
        odata.addData("datas",datas);

        putOutData(odata);

    }

    private void testview(Object fromWho, TLMsg msg)	{
        long startTime =System.currentTimeMillis();
        TLMsg returnmsg ;
        TLMsg tmsg = new TLMsg().setAction("getView").setParam("viewName","vusers");
        returnmsg =putMsg("database",tmsg);
        TLDBView tv= (TLDBView) returnmsg.getParam(TLObjectFactory.FACTORY_R_MODULEINSTANCE);
        int number=110;
        LinkedHashMap<String ,Object> sqlparams=new LinkedHashMap<>();
        sqlparams.put("number",number);
        TLMsg querymsg=new TLMsg().setAction("query")
                .setParam("params",sqlparams);
        outData odata =  creatOutDataMsg();
        returnmsg=	 putMsg(tv,querymsg);
        List datas = (List) returnmsg.getParam("result");
        if(datas==null || datas.isEmpty())
        {
            odata.addData(name+" 没有数据");
            putOutData(odata);
            return;
        }
        odata.addData("datas",datas);
        putOutData(odata);
    }
    private void query(Object fromWho, TLMsg msg)	{
        queryfunc( 200);
        queryfunc( 30);

        //	TLMsg ctmsg=new TLMsg().setAction("total");
        //	returnmsg=	putMsg(tb,ctmsg);
        //	Long count = (Long) returnmsg.getParam("result");
        //	System.out.print("总数:  "+count);
    }
    private void queryfunc(int number){
        long startTime =System.currentTimeMillis();
        TLMsg returnmsg;
        String sql ="select * from  [table]  where number>? and name like ?";
        LinkedHashMap<String ,Object> sqlparams=new LinkedHashMap<>();
        sqlparams.put("number",number);
        sqlparams.put("name","%67%");
        TLMsg querymsg=new TLMsg().setAction("query")
                .setParam("sql",sql)
                .setParam("resultType", TLDataBase.RESULT_TYPE.ARRAYLIST)
                .setParam("params",sqlparams)
                .setParam("resultFor",this)
                .setParam("resultAction","getResult");
        querymsg.setParam("cacheName","table_user");
        querymsg.setParam("cacheKey",""+number);
        returnmsg=	 putMsg(tb,querymsg);

        /**
         List datas = (List) returnmsg.getParam("result");
         if(datas.isEmpty())
         {
         System.out.println("没有数据");
         return;
         }
         for(int i=0;i< datas.size();i++){
         Object [] unit= (Object[]) datas.get(i);
         for(int j=0;j< unit.length;j++){
         System.out.print(unit[j]+"  ");
         }
         System.out.println("");
         }
         */
    }
    private  void  getResult(Object fromWho, TLMsg msg){
        List datas = (List) msg.getParam("result");
        if(datas.isEmpty())
        {
            System.out.println("没有数据");
            return;
        }
        for(int i=0;i< datas.size();i++){
            Object [] unit= (Object[]) datas.get(i);
            for(int j=0;j< unit.length;j++){
                System.out.print("  "+unit[j]+"  ");
            }
            System.out.println("");
        }

    }
    private void  insert(Object fromWho, TLMsg msg){
        String sql ="insert into  [table] (name,number,time) values(?,?,?)";
        String name="yyyy999";
        int number=2245;
        LinkedHashMap<String ,Object> sqlparams=new LinkedHashMap<>();
        sqlparams.put("name",name);
        sqlparams.put("number",number);
        sqlparams.put("time",date());
        putMsg(tb,createMsg().setAction("setConnection"))  ;
        TLMsg returnmsg;
        for(int i=0;i<500 ;i++)
        {
            sqlparams.put("time",date());
            TLMsg insertmsg=new TLMsg().setAction("insert")
                    .setParam("sql",sql)
                    .setParam("resultType", TLDataBase.RESULT_TYPE.ARRAYLIST)
                    .setParam("params",sqlparams);
            returnmsg= putMsg(tb,insertmsg);
            System.out.println(" "+i);
        }

    }
    private void  batch(Object fromWho, TLMsg msg){
        String sql ="insert into  userm (name,number,time) values(?,?,?)";
        String name="batch555";
        int number=255;
        Object[][] bparams = new Object[10000][3];
        for(int i=0;i<10000 ;i++)
        {
            bparams[i][0]=name;
            bparams[i][1]=number;
            bparams[i][2]=date()+1;
        }
        TLMsg insertmsg=new TLMsg().setAction("batch")
                .setParam("sql",sql)
                .setParam("params",bparams);
        TLMsg	returnmsg= putMsg(tb,insertmsg);
        System.out.println(" work over ");
    }

}
