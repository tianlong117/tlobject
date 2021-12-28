package cn.tianlong.tlobject.network.client.websocket;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;
import cn.tianlong.tlobject.utils.TLMsgUtils;
import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.*;


/**
 * 创建日期：2020/2/1214:11
 * 描述:
 * 作者:tianlong
 */
public class TLServiceGateWayInterface extends TLSocketClientAgentPool {
    protected List<String> serviceMsgid ;
    protected Gson gson =new Gson();
    protected Type type = new TypeToken<Map<String, Object>>() {   }.getType();
    public TLServiceGateWayInterface(String name , TLObjectFactory modulefactory) {
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
    protected void fromAgent(Object fromWho, TLMsg msg) {
        super.fromAgent(fromWho,msg);
        String status = (String) msg.getParam(WEBSOCKET_P_STATUS);
        if (!status.equals(WEBSOCKET_R_OPEN))
            return;
        String serverName = (String) msg.getParam(WEBSOCKET_R_CLIENTAGENT);
        HashMap<String,String> serverconfig =servers.get(serverName);
        String serverType=serverconfig.get("serverType");
        if(serverType !=null && serverType.equals("gateWay"))
             putshMsgidToGateWay(serverName) ; //msg网关连接成功后将msgid表推送给网关
    }
    private void putshMsgidToGateWay(String serverName) {
        ArrayList<String> msgids = null;
        if(serviceMsgid !=null && !serviceMsgid.isEmpty())
            msgids =(ArrayList<String>)serviceMsgid ;
        else  if(msgTable !=null && !msgTable.isEmpty())
               msgids =new ArrayList<>(msgTable.keySet());
        if(msgids ==null)
            return;
        HashMap<String ,Object> datas =new HashMap<>() ;
        datas.put("msgid","setServiceMsgid");
        datas.put("msgids",msgids);
        TLMsg serverMsg =createMsg().setAction(WEBSOCKETCLIENTAGENT_PUTTOSOCKET)
                .setParam(WEBSOCKET_P_CONTENT,datas)
                .setParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME,serverName);
       putToServer(this,serverMsg);
    }

    @Override
    protected TLMsg onMessage(Object fromWho, TLMsg msg){
        String jstr = (String) msg.getParam(WEBRESPONSE);
        if(jstr.equals("otherlogin"))
            return createMsg().setParam("reConnect",false);
        LinkedTreeMap<String, Object> gateWayDatas =TLMsgUtils.jsonStrToMap(jstr);
        if(gateWayDatas ==null || gateWayDatas.isEmpty() )
        {
            putLog("服务器返回非json字符;" + jstr, LogLevel.WARN);
            return null;
        }
        String msgid = (String) gateWayDatas.get(MSG_P_MSGID);
        if(msgid==null || msgid.isEmpty())
            return null;
        String server = (String) msg.getParam(WEBSOCKET_R_CLIENTAGENT);
        String sesstionId =null;
        if(gateWayDatas.containsKey(WEBSOCKET_P_SESSION))
        {
            sesstionId = (String) gateWayDatas.get(WEBSOCKET_P_SESSION);
            gateWayDatas.remove(WEBSOCKET_P_SESSION);
        }
        TLMsg serverMsg =createMsg().setMsgId(msgid).addArgs(gateWayDatas);
        TLMsg returnMsg ;
        if(onMessageModule !=null )
            returnMsg= putMsg(onMessageModule, serverMsg);
        else
            returnMsg=  getMsg(this, serverMsg);
        if(returnMsg ==null)
            return null;
        if(sesstionId !=null)
            returnMsg.setParam(WEBSOCKET_P_SESSION,sesstionId);
        Map<String,Object> userInfo = (Map<String, Object>) gateWayDatas.get(SERVICEGATEMAY_P_USERINFO);
        TLMsg toUserMsg =createMsg().setAction(WEBSOCKETCLIENTAGENT_PUTTOSOCKET);
        userInfo.put(MSG_P_MSGID,"fromService");
        userInfo.put("servicedatas",returnMsg.getArgs());
        toUserMsg.setParam(WEBSOCKET_P_CONTENT,userInfo).setParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME,server);
        putToServer(this,toUserMsg);
        return null ;
    }
}
