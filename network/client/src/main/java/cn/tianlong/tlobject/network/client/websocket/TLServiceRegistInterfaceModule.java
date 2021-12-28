package cn.tianlong.tlobject.network.client.websocket;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.utils.TLDataUtils;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.Thread.sleep;


/**
 * 创建日期：2020/2/1214:11
 * 描述:
 * 作者:tianlong
 */
public class TLServiceRegistInterfaceModule extends TLSocketClientAgentPool {
    protected List<String> serviceMsgid ;
    protected Gson gson =new Gson();
    protected Type type = new TypeToken<Map<String, Object>>() {   }.getType();
    public TLServiceRegistInterfaceModule(String name , TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        if (params != null && params.get("serviceMsgid") != null) {
            String serviceMsgidStr = params.get("serviceMsgid");
            serviceMsgid =TLDataUtils.splitStrToList(serviceMsgidStr,";")  ;
        }
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "setServersParam":
                setServersParam( fromWho,  msg);
                break;
            default:
                super.checkMsgAction(fromWho,msg);
        }
        return returnMsg;
    }
    private void setServersParam(Object fromWho, TLMsg msg) {
        String token = (String) msg.getParam("token");
        List<Map<String,Object>> serversParam = (List<Map<String, Object>>) msg.getParam("servers");
        if(serversParam.isEmpty())
            return;
        try {
            sleep(10000) ;         //等候其他server连接
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }
    @Override
    protected void fromAgent(Object fromWho, TLMsg msg) {
        super.fromAgent(fromWho,msg);
        String status = (String) msg.getParam(WEBSOCKET_P_STATUS);
        if (!status.equals(WEBSOCKET_R_OPEN))
            return;
        String serverName = (String) msg.getParam(WEBSOCKET_R_CLIENTAGENT);
        HashMap<String,String> serverconfig =servers.get(serverName);

    }


}
