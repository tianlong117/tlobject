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
        outData odata =  creatOutDataMsg("dbmodle");
        List<Object> totalDatas = new ArrayList();
        long startTime =System.currentTimeMillis();
        // 查询10次，比较通过缓存的情况
        for(int i =0 ;i < 10 ;i++)
        {
            TLMsg returnMsg =putMsg("userModle",createMsg().setAction("queryTb")
                    .setParam("username",userName)
                    .setParam("isFromWeb",true));
            List datas =  returnMsg.getListParam(DB_R_RESULT,null);
            if(datas==null || datas.isEmpty())
            {
                odata.addData(name+" 没有数据");
                putOutData(odata);
                return null;
            }
            totalDatas.addAll(datas);
        }
        Long nowTime =System.currentTimeMillis();
        Long runtime=nowTime-startTime;
        odata.addData("time","数据查询时间："+runtime);
        odata.addData("datas",totalDatas);
        return putOutData(odata);
    }

    private TLMsg dbmodleCache(Object fromWho, TLMsg msg) {
        String userName=msg.getStringParam("name",null);
        if(userName ==null)
            return null;
        long startTime =System.currentTimeMillis();
        String cacheName ="queryByName" ;
        String cacheKey ="name_"+userName ;
        TLFileCache fileCache = (TLFileCache) getModule(M_FILECACHE);
        Object cacheValue = fileCache.getCache(cacheName,cacheKey);
        if(cacheValue !=null && !cacheValue.equals(fileCache) )
        {
            putLog("读取cache,cacheKye:"+cacheKey,LogLevel.DEBUG,"dbmodle");
            Long nowTime =System.currentTimeMillis();
            Long runtime=nowTime-startTime;
            outData odata =  creatOutDataMsg();
            odata.addData("<br>（缓存读取时间: "+runtime+"）<br>");
            odata.addData(cacheValue);
            return putOutData(odata);
        }
        TLMsg outMsg=dbmodle(fromWho,msg);
        String content =outMsg.getStringParam(CLIENT_R_OUTCONTENT,null);
        if(content==null)
            return outMsg ;
        fileCache.writeCache(cacheName,cacheKey,content,-1);
        putLog("写cache,cacheKye:"+cacheKey,LogLevel.DEBUG,"dbmodle");
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
