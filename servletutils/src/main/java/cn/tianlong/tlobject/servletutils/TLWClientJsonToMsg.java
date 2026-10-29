package cn.tianlong.tlobject.servletutils;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.utils.TLDataUtils;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.internal.LinkedTreeMap;
import com.google.gson.reflect.TypeToken;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Type;
import java.util.Map;

import static cn.tianlong.tlobject.servletutils.TLParamString.M_DIRECTININTERFACE;

public class TLWClientJsonToMsg extends TLWServModule {
    protected  Gson gson;
    protected IObject inInterface;
    protected TLMsg  getInMsg =createMsg().setAction("getContentFromUser");
    protected  Type jsonType = new TypeToken<Map<String, Object>>() {}.getType();
    public TLWClientJsonToMsg() {
        super();
    }
    public TLWClientJsonToMsg(String name) {
        super(name);
    }
    public TLWClientJsonToMsg(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }
    @Override
    protected TLBaseModule init() {
         gson =new Gson();
         inInterface = (IObject) getModule(M_DIRECTININTERFACE);
        return this ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "fromClient":
                return fromClient(fromWho, msg);
            case"toServlet":
                returnMsg=toServlet( fromWho, msg);
                break;
            default:

        }
        return returnMsg;
    }
    private TLMsg toServlet(Object fromWho, TLMsg msg) {
        TLMsg dmsg =(TLMsg) msg.getParam("domsg");
        dmsg.removeParam("token");
        return   putMsg(this, (TLMsg) msg.getParam("domsg"));
    }
    private TLMsg fromClient(Object fromWho, TLMsg msg) {
        if (inInterface == null)          // init 时 M_DIRECTININTERFACE 未注册/创建失败
            return null;
        TLMsg returnMsg=  putMsg( inInterface,getInMsg);
        if (returnMsg == null)
            return null;
        Object contentObj = returnMsg.getParam("content");
        if (!(contentObj instanceof String))
            return null;
        String jstr = (String) contentObj;
        LinkedTreeMap<String, Object> contentmap;
        try {
            contentmap = gson.fromJson(jstr, jsonType);
        }catch (JsonSyntaxException e){
            contentmap=null;
            putLog("客户端发送非json字符;"+jstr,LogLevel.WARN);
        }
        if(contentmap==null )
            return null ;
        Object msgidObj = contentmap.get(MSG_P_MSGID);
        String msgid = msgidObj != null ? String.valueOf(msgidObj) : null;
        TLMsg clientMsg =createMsg().setMsgId(msgid);
        Object datasObj = contentmap.get("datas");
        if(datasObj instanceof Map)
        {
            // JSON 里 datas 写成数组/标量时，强转 LinkedTreeMap 会 CCE（且不是 JsonSyntaxException，接不住）
            LinkedTreeMap<String, Object> datas = (LinkedTreeMap<String, Object>) datasObj;
            datas = (LinkedTreeMap<String, Object>) TLDataUtils.jsonDoubleToInt(datas);
           clientMsg.addArgs(datas);
        }
        HttpServletRequest request =getRequest() ;
        String token = request != null ? request.getHeader("Access-User-Token") : null;
        if(token ==null)
        {
            Object tokenObj = contentmap.get("token");
            token = tokenObj != null ? String.valueOf(tokenObj) : null;   // 数字 token 强转 String 会 CCE
        }
        if(token!=null)
           clientMsg.setParam("token",token);
        return putMsg(this,createMsg().setAction("toServlet").setParam("domsg",clientMsg));
    }
}

