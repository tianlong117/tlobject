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
        TLMsg returnMsg=  putMsg( inInterface,getInMsg);
        String jstr = (String) returnMsg.getParam("content");
        LinkedTreeMap<String, Object> contentmap;
        try {
            contentmap = gson.fromJson(jstr, jsonType);
        }catch (JsonSyntaxException e){
            contentmap=null;
            putLog("客户端发送非json字符;"+jstr,LogLevel.WARN);
        }
        if(contentmap==null )
            return null ;
        String msgid = (String) contentmap.get(MSG_P_MSGID);
        TLMsg clientMsg =createMsg().setMsgId(msgid);
        if(contentmap.get("datas")!=null )
        {
            LinkedTreeMap<String, Object> datas = (LinkedTreeMap<String, Object>) contentmap.get("datas");
            datas = (LinkedTreeMap<String, Object>) TLDataUtils.jsonDoubleToInt(datas);
           clientMsg.addArgs(datas);
        }
        HttpServletRequest request =getRequest() ;
        String token = request.getHeader("Access-User-Token");
        if(token ==null)
            token = (String) contentmap.get("token");
        if(token!=null)
           clientMsg.setParam("token",token);
        return putMsg(this,createMsg().setAction("toServlet").setParam("domsg",clientMsg));
    }
}

