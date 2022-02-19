package cn.tianlong.tlobject.servletutils.clientinterface;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.servletutils.TLWServModule;
import com.google.gson.GsonBuilder;
import java.util.LinkedHashMap;

import static cn.tianlong.tlobject.servletutils.TLParamString.CHARSET;

public class TLWJsonDataOutInterface extends TLWDirectOutInterface {
    private GsonBuilder gsonBuilder = new GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss");
    public TLWJsonDataOutInterface(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected TLMsg putContentToUser(Object fromWho, TLMsg msg) {
        String jsonString = gsonBuilder.serializeNulls().create().toJson(msg.getParam("content"));
        msg.setParam("content",jsonString);
     return    super.putContentToUser(fromWho,  msg);
    }
    @Override
    protected TLMsg putDataToUser(Object fromWho, TLMsg msg) {
        outData outData = (TLWServModule.outData) msg.getParam("outData");
        LinkedHashMap<String, Object> datas =(LinkedHashMap<String, Object>)outData.getParam("outData");
        String  outContent = gsonBuilder.serializeNulls().create().toJson(datas);
        return  responseWrite(outContent,(String) msg.getParam(CHARSET));
    }
}
