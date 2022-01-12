package cn.tianlong.java.servletdemo;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.cache.TLFileCache;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.servletutils.TLWServModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLTable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

import static cn.tianlong.tlobject.cache.TLParamString.*;
import static cn.tianlong.tlobject.servletutils.TLParamString.CLIENT_R_OUTCONTENT;
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
                returnMsg=find(fromWho,msg);
                break;
            case "dbmodle":
                returnMsg=dbmodle(fromWho,msg);
                break;
            case "dbmodleCache":
                returnMsg=dbmodleCache(fromWho,msg);
                break;
            default:
                putMsg("error",creatOutMsg().setAction("setError").setParam("content","no action"));
        }
        return returnMsg ;
    }
    private TLMsg dbmodle(Object fromWho, TLMsg msg) {
        String userName=msg.getStringParam("name",null);
        if(userName ==null)
            return null;
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
            return null;
        }
        odata.addData("datas",datas);
        return putOutData(odata);
    }

    private TLMsg dbmodleCache(Object fromWho, TLMsg msg) {
        String userName=msg.getStringParam("name",null);
        if(userName ==null)
            return null;
        String cacheName ="queryByName" ;
        String cacheKye ="name_"+userName ;
        TLFileCache fileCache = (TLFileCache) getModule(M_FILECACHE);
        TLMsg cmsg =createMsg().setAction(CACHE_GETCACHE).setParam(CACHE_P_CACHENAME,cacheName)
                .setParam(CACHE_P_KEY,cacheKye);
        TLMsg returnMsg =putMsg(fileCache,cmsg);
        Object value = returnMsg.getParam(CACHE_R_VALUE);
        if(value !=null && !value.equals(fileCache) )
        {
            putLog("读取cache,cacheKye:"+cacheKye,LogLevel.DEBUG,"dbmodle");
            return putContent((String)value);
        }
        TLMsg outMsg=dbmodle(fromWho,msg);
        String content =outMsg.getStringParam(CLIENT_R_OUTCONTENT,null);
        if(content==null)
            return outMsg ;
        TLMsg wcmsg =createMsg().setAction(CACHE_WRITECACHE).setParam(CACHE_P_CACHENAME,cacheName)
                .setParam(CACHE_P_KEY,cacheKye).setParam(CACHE_P_VALUE,content);
        putMsg(M_FILECACHE,wcmsg);
        putLog("写cache,cacheKye:"+cacheKye,LogLevel.DEBUG,"dbmodle");
        return outMsg ;
    }
    private TLMsg find(Object fromWho, TLMsg msg) {
        String userName=msg.getStringParam("name",null);
        if(userName ==null)
            return msg;
        outData odata =  creatOutDataMsg("find");
        odata.addData("time",date());
        TLMsg returnMsg =putMsg("dbDemo",createMsg().setAction("queryTb").setParam("username",userName));
        ArrayList<LinkedHashMap> datas = (ArrayList<LinkedHashMap>) returnMsg.getListParam(RESULT,null);
        if(datas ==null || datas.isEmpty())
            odata.addData("msg","没有数据");
        else
            odata.addData("datas",datas);
        return putOutData(odata);
    }

}
