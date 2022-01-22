package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.*;
import org.xmlpull.v1.XmlPullParser;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * 创建日期：2018/4/23 on 21:00
 * 描述:
 * 作者:tianlong
 */

public  class TLModuleMonitor extends TLBaseModule {
    protected  boolean ifStartMonitor =false ;
    protected  List<String> noMonitorModules ;
    protected  List<String> monitorModules ;
    protected  HashMap<String, HashMap<String,String>> moduleSetting;
    protected  HashMap<String, HashMap<String,List<String>>> modulesForCheck =new HashMap<>();
    protected  String defaultMonitorMsg ;
    public TLModuleMonitor(){
        super();
    }
    public TLModuleMonitor(String name ){
        super(name);
    }
    public TLModuleMonitor(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
        ifMonitor =false ;
    }

   @Override
    protected Object setConfig(){
        myConfig config=new myConfig(configFile,moduleFactory.getConfigDir());;
        mconfig=config;
        super.setConfig();
        moduleSetting =config.getModuleSetting();
        return config ;
    }
    @Override
    protected void initProperty(){
        super.initProperty();
        if(params!=null )
        {
            if( params.get("ifStartMonitor")!=null)
                ifStartMonitor = Boolean.valueOf(params.get("ifStartMonitor"));
            if( params.get("defaultMonitorMsg")!=null)
                defaultMonitorMsg =params.get("defaultMonitorMsg");
            if( params.get("noMonitorModules")!=null && !params.get("noMonitorModules").isEmpty()){
                String str[] = params.get("noMonitorModules").split(";");
                for(int i=0;i<str.length;i++) {
                    str[i] = str[i].trim();
                }
                noMonitorModules= Arrays.asList(str);
            }
            else
                noMonitorModules=null;
            if( params.get("monitorModules")!=null && !params.get("monitorModules").isEmpty()){
                String str[] = params.get("monitorModules").split(";");
                for(int i=0;i<str.length;i++) {
                    str[i] = str[i].trim();
                }
                monitorModules= Arrays.asList(str);
            }
            else
                monitorModules=null;

        }
        if(moduleSetting ==null || moduleSetting.isEmpty())
            return;
        for(String module: moduleSetting.keySet()){
            HashMap<String,String> moduleConfig = moduleSetting.get(module);
            HashMap<String,List<String>> configArray =new HashMap<>();
            if(moduleConfig.get("actionsStart")!=null && !moduleConfig.get("actionsStart").isEmpty())
            {
                String actions = (String) moduleConfig.get("actionsStart");
                String moduleActions[] = actions.split(";");
                for(int i=0;i<moduleActions.length;i++) {
                    moduleActions[i] = moduleActions[i].trim();
                }
                configArray.put("actionsStart",Arrays.asList(moduleActions));
            }
            if(moduleConfig.get("msgidsStart")!=null && !moduleConfig.get("msgidsStart").isEmpty())
            {
                String moduleMsgid= (String) moduleConfig.get("msgidsStart");
                String moduleMsgids[] = moduleMsgid.split(";");
                for(int i=0;i<moduleMsgids.length;i++) {
                    moduleMsgids[i] = moduleMsgids[i].trim();
                }
                configArray.put("msgidsStart",Arrays.asList(moduleMsgids));
            }
            if(moduleConfig.get("actionsEnd")!=null && !moduleConfig.get("actionsEnd").isEmpty())
            {
                String actions = (String) moduleConfig.get("actionsEnd");
                String moduleActions[] = actions.split(";");
                for(int i=0;i<moduleActions.length;i++) {
                    moduleActions[i] = moduleActions[i].trim();
                }
                configArray.put("actionsEnd",Arrays.asList(moduleActions));
            }
            if(moduleConfig.get("msgidsEnd")!=null && !moduleConfig.get("msgidsEnd").isEmpty())
            {
                String moduleMsgid= (String) moduleConfig.get("msgidsEnd");
                String moduleMsgids[] = moduleMsgid.split(";");
                for(int i=0;i<moduleMsgids.length;i++) {
                    moduleMsgids[i] = moduleMsgids[i].trim();
                }
                configArray.put("msgidsEnd",Arrays.asList(moduleMsgids));
            }
            modulesForCheck.put(module,configArray);
        }
    }
    @Override
    protected TLBaseModule init() {
      return this ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case "actionStart":
                returnMsg=actionStart( fromWho,  msg);
                break;
            case "actionEnd":
                returnMsg=actionEnd( fromWho,  msg);
                break;
            default:
                ;
        }
        return returnMsg;
    }
    protected TLMsg actionStart(Object fromWho, TLMsg msg) {
        if(ifStartMonitor ==false)
            return null ;
        String moduleName = (String) msg.getParam(MODULENAME);
        TLMsg mmsg = (TLMsg) msg.getParam(MSG);
        String action =mmsg.getAction();
        String msgid = mmsg.getMsgId();
        if(!checkIfMonitor(moduleName , action ,  msgid ,"start"))
            return null ;
        return moduleActionStart(moduleName,mmsg);
    }

    protected TLMsg moduleActionStart(String moduleName , TLMsg msg) {
        String msgid = getDoMsg(moduleName,"doStartMsgid");
        if(msgid ==null)
            return null ;
        TLMsg doMsg =createMsg().setMsgId(msgid)
                .setParam(MSG,msg)
                .setParam("type","start")
                .setParam(MODULENAME,moduleName);
        return  getMsg(this,doMsg);
    }
    protected TLMsg actionEnd(Object fromWho, TLMsg msg) {
        if(ifStartMonitor ==false || msg==null)
            return null ;
        String moduleName = (String) msg.getParam(MODULENAME);
        TLMsg mmsg = (TLMsg) msg.getParam(MSG);
        TLMsg returnMsg = (TLMsg) msg.getParam(RETURNMSG);

        String action =mmsg.getAction();
        String msgid = mmsg.getMsgId();
        if(!checkIfMonitor(moduleName , action ,  msgid ,"end"))
            return null ;
        return moduleActionEnd(moduleName,mmsg,returnMsg);
    }

    protected TLMsg moduleActionEnd(String moduleName, TLMsg msg, TLMsg returnMsg) {
        String msgid =  getDoMsg(moduleName,"doEndMsgid");
        if(msgid ==null)
            return null ;
        TLMsg doMsg =createMsg().setMsgId(msgid)
                .setParam(MSG,msg)
                .setParam("type","end")
                .setParam(RETURNMSG,returnMsg)
                .setParam(MODULENAME,moduleName);
        return  getMsg(this,doMsg);
    }
    protected String  getDoMsg(String moduleName,String type){
        HashMap<String,String> moduleConfig = moduleSetting.get(moduleName);
        String doEndMsgid ;
        if(moduleConfig ==null || !moduleConfig.containsKey(type))
            doEndMsgid =defaultMonitorMsg;
        else
            doEndMsgid =moduleConfig.get(type);
        return  doEndMsgid ;
    }

    protected  Boolean checkIfMonitor(String module , String action , String msgid,String type ){

        if(monitorModules !=null &&  !monitorModules.contains(module))
            return false;
        else if(noMonitorModules !=null  && noMonitorModules.contains(module))
            return false;
        if(modulesForCheck.isEmpty())
            return true ;
        HashMap<String,List<String>> moduleConfig =modulesForCheck.get(module);
        if( moduleConfig==null || moduleConfig.isEmpty())
            return true ;
        List<String> actions ;
        if(type.equals("start"))
            actions =  moduleConfig.get("actionsStart");
        else
            actions =  moduleConfig.get("actionsEnd");
        if(action !=null  )
        {
            if(actions !=null && actions.contains(action))
                return true ;
        }
        List<String> msgids ;
        if(type.equals("start"))
            msgids =  moduleConfig.get("msgidStart");
        else
           msgids =  moduleConfig.get("msgidEnd");
        if(msgid ==null || msgids==null)
            return false ;
        if(!msgids.contains(msgid) )
            return false ;
        return true;
    }

    protected class myConfig extends TLModuleConfig {
        protected HashMap<String, HashMap<String,String>> moduleSetting;
        public myConfig(String configFile ,String configDir) {
            super(configFile,configDir);
        }
        public myConfig() {

        }
        public HashMap getModuleSetting() {
            return moduleSetting;
        }

        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if (xpp.getName().equals("moduleSetting")) {
                    moduleSetting= getHashMap(xpp,"moduleSetting","module");
                }

            } catch (Throwable t) {

            }
        }

    }
}
