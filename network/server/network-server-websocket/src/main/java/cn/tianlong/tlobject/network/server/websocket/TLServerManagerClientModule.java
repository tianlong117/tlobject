package cn.tianlong.tlobject.network.server.websocket;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.Thread.sleep;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLServerManagerClientModule extends TLBaseServiceModule {
    protected String cerFile="/mycerts.cer" ;
    protected String socketClientAgentPool;
    protected String serverUserManagerModule;
    public TLServerManagerClientModule(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

   @Override
    protected void initProperty(){
        super.initProperty();
       if(params !=null && params.get("cerFile")!=null)
           cerFile = params.get("cerFile");
       if(params !=null && params.get("socketClientAgentPool")!=null)
           socketClientAgentPool = params.get("socketClientAgentPool");
       if(params !=null && params.get("serverUserManagerModule")!=null)
           serverUserManagerModule = params.get("serverUserManagerModule");
   }
    @Override
    protected TLBaseModule init() {
        return this ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "setServersParam":
                setServersParam( fromWho,  msg);
                break;
            case "onServerStatusAction":
                onServerStatusAction( fromWho,  msg);
                break;
            default:
                ;
        }
        return returnMsg;
    }

    private void onServerStatusAction(Object fromWho, TLMsg msg) {
        String status = (String) msg.getParam(WEBSOCKET_P_STATUS);
        String serverName =(String) msg.getParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME);
        if (!status.equals(WEBSOCKET_R_OPEN))
            return;
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
        for(Map<String,Object> p :serversParam) {
            String server = (String) p.get("server");
            if(checkIfLogin(server))
                continue;
            HashMap<String ,Object> sparam =new HashMap<>() ;
            sparam.put("url",p.get("ip_server"));
            sparam.put("token",token);
            sparam.put("cerFile",cerFile) ;
            sparam.put("autoConnect","false") ;
            TLMsg serverMsg =createMsg().setAction(SOCKETCLIENTAGENTPOOL_ADDSERVER).setSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME,server)
                    .setParam(SOCKETCLIENTAGENTPOOL_P_SERVERPARAM,sparam);
            putMsg(socketClientAgentPool,serverMsg);
        }
    }

    private boolean checkIfLogin(String server) {
        TLMsg routeMsg =createMsg().setAction(USERMANAGER_GETUSERS)
                  .setParam(USERMANAGER_P_USERID,server);
        TLMsg returnMsg =  putMsg(serverUserManagerModule,routeMsg) ;
        if(returnMsg.getParam(RESULT) ==null)
            return false ;
        else
            return  true ;
    }
}
