package cn.tianlong.tlobject.servletutils.clientinterface;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.internal.LinkedTreeMap;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.Map;

import static cn.tianlong.tlobject.servletutils.TLParamString.CLIENT_R_CONTENT;

public class TLWJsonInInterface extends TLWDirectInInterface {
    protected Gson gson;
    public TLWJsonInInterface(){
        super();
    }
    public TLWJsonInInterface(String name ){
        super(name);
    }
    public TLWJsonInInterface(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected TLBaseModule init() {
        gson =new Gson();
        return this ;
    }

    @Override
    protected TLMsg getContentFromUser(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=super.getContentFromUser(fromWho,msg);
        if(returnMsg ==null)
            return null ;
        String jstr = (String) returnMsg.getParam(CLIENT_R_CONTENT);
        if(jstr ==null)
            return null ;
        Type type = new TypeToken<Map<String, Object>>() {}.getType();
        LinkedTreeMap<String, Object> contentmap;
        try {
            contentmap = gson.fromJson(jstr, type);
        }catch (JsonSyntaxException e){
            putLog("客户端发送非json字符;"+jstr,LogLevel.WARN,"getContentFromUser");
            return null ;
        }
        if(contentmap!=null )
        {
            returnMsg.clear();
            return  returnMsg.addArgs(contentmap) ;
        }
        return  null ;
    }

    @Override
    protected TLMsg getDataFromUser(Object fromWho, TLMsg msg) {
       return getContentFromUser( fromWho,  msg);
    }
}
