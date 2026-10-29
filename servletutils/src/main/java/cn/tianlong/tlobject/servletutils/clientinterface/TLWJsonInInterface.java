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
    /** 类型标记（匿名子类反射取泛型）：每请求 new 一次的代价约为解析本身数倍，必须缓存 */
    private static final Type JSON_MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
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
        // 注意：这里通过 super 读的是原始 body（request.getInputStream）。同一请求若再调
        // getDataFromUser（request.getParameterMap），form-urlencoded body 已被消费、拿到空参数；
        // 一个请求只用一套输入接口，不要混着取
        LinkedTreeMap<String, Object> contentmap;
        try {
            contentmap = gson.fromJson(jstr, JSON_MAP_TYPE);
            // 将解析后的数据存入线程上下文，供后续模块使用
            setThreadData("jsonRequestBody", contentmap);
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
