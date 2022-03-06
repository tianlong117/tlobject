package cn.tianlong.java.application.webmanager;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.utils.TLDataUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class appClientAgent extends adminCommon {
    protected String appType ="factory";
    protected   String  appConfigPath="";
    protected   String  urlMapconfigfile ;
    protected   String  appFactory ;
    protected   ArrayList<String> appServers ;
    protected   String  serverClient = M_SOCKETCLIENTAGENTPOOL;
    public appClientAgent() {
        super();
    }

    public appClientAgent(String name) {
        super(name);
    }

    public appClientAgent(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }
    @Override
    protected TLBaseModule init() {
        return this ;
    }
    @Override
    protected void setModuleParams() {
        if(params !=null && params.get("appType")!=null)
            appType = params.get("appType");
        if(params !=null && params.get("appConfigPath")!=null)
            appConfigPath=params.get("appConfigPath");
        if(params !=null && params.get("appFactory")!=null)
            appFactory=params.get("appFactory");
        if(params !=null && params.get("appServers")!=null)
            appServers=TLDataUtils.splitStrToList(params.get("appServers"),";");
        if(params !=null && params.get("serverClient")!=null)
            serverClient = params.get("serverClient");
        if(params !=null && params.get("urlMapconfigfile")!=null)
            urlMapconfigfile = params.get("urlMapconfigfile");
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
          return null ;
    }
    public String  getAppConfigPath(){
        return appConfigPath ;
    }
    public String  getUrlMapconfigfile(){
        return urlMapconfigfile ;
    }
    public TLMsg putToApp(String moduleName,TLMsg msg){
        TLMsg  returnMsg ;
        if(appType.equals("factory"))
        {
            msg.removeParam("waitServerReturn");
            returnMsg = putToAppFactory(moduleName,msg);
        }
        else
        {
            String[] array =TLDataUtils.splitStrToArray(moduleName,"@");
            if(array.length <3)
              returnMsg =  putToAllServer(moduleName,msg);
            else
            {
                String  module  =array[0];
                String  factory =array[1] ;
                String  server  =array[2];
                return  putToServer( module+"@"+factory, server , msg);
            }
        }
        return returnMsg.setParam("appType",appType);
    }
    public TLMsg putToAppFactory(String moduleName,TLMsg msg){
        TLBaseModule factory =getAppfactory();
        if(factory ==null)
            return null;
        if(moduleName.indexOf("@") <0)
              moduleName =moduleName+"@"+appFactory;
        return putMsg(moduleName,msg);
    }
    public TLMsg putToAllServer(String moduleName,TLMsg msg){
        HashMap<String,Map<String,Object>> resultMap =new HashMap<>() ;
        for(String server :appServers){
            TLMsg returnMsg =putToServer( moduleName, server , msg);
            resultMap.put(server,returnMsg.getArgs());
        }
        return createMsg().addMap(resultMap);
    }
    public TLMsg putToServer(String moduleName,String server ,TLMsg msg){
        TLMsg serverMsg =createMsg();
        if(msg.parseBoolean("waitServerReturn",true))
            serverMsg.setAction(WEBSOCKET_PUTANDWAIT) ;
        else
            serverMsg.setAction(WEBSOCKET_PUTMSG) ;
        msg.removeParam("waitServerReturn");
        serverMsg.addMap(msg.getArgs())
                 .setParam(MSG_P_ACTION,msg.getAction())
                 .setParam(MSG_P_DESTINATION,moduleName)
                 .setSystemParam(SOCKETCLIENTAGENTPOOL_P_SERVERNAME,server);
        return putMsg(serverClient,serverMsg);
    }
}
