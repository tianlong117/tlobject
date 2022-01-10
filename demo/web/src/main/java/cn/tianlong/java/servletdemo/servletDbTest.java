package cn.tianlong.java.servletdemo;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.servletutils.TLWServModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLDBSqlConditionExpression;
import cn.tianlong.tlobject.db.TLDBView;
import cn.tianlong.tlobject.db.TLDataBase;
import cn.tianlong.tlobject.db.TLTable;
import cn.tianlong.tlobject.utils.TLMsgUtils;

import java.util.ArrayList;
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
        return this ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case "find":
                find(fromWho,msg);
                break;
            case "dbmodle":
                dbmodle(fromWho,msg);
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

    private void dbmodle(Object fromWho, TLMsg msg) {
        String userName=msg.getStringParam("name",null);
        if(userName ==null)
            return;
        long startTime =System.currentTimeMillis();
        TLMsg returnMsg =putMsg("userModle",createMsg().setAction("queryTb")
                .setParam("username",userName)
                .setParam("isFromWeb",true));
        List datas =  returnMsg.getListParam(DB_R_RESULT,null);
        Long nowTime =System.currentTimeMillis();
        Long runtime=nowTime-startTime;
        outData odata =  creatOutDataMsg("dbmodle");
        odata.addData("time","数据查询时间："+runtime);
        if(datas==null || datas.isEmpty())
        {
            odata.addData(name+" 没有数据");
            putOutData(odata);
            return;
        }
        odata.addData("datas",datas);
        putOutData(odata);
    }

    private void find(Object fromWho, TLMsg msg) {
        String userName=msg.getStringParam("name",null);
        if(userName ==null)
            return;
        outData odata =  creatOutDataMsg("find");
        odata.addData("time",date());
        TLMsg returnMsg =putMsg("dbDemo",createMsg().setAction("queryTb").setParam("username",userName));
        ArrayList<LinkedHashMap> datas = (ArrayList<LinkedHashMap>) returnMsg.getListParam(RESULT,null);
        if(datas ==null || datas.isEmpty())
            odata.addData("msg","没有数据");
        else
            odata.addData("datas",datas);
        putOutData(odata);
    }

}
