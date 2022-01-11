package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.utils.TLMsgUtils;
import org.xmlpull.v1.XmlPullParser;

import java.util.*;

/**
 * 控制台消息输入扫描检测模块
 */
public class TLMsgScanner extends TLBaseModule {
    protected HashMap<String, HashMap<String,String>>msgToModules;
    protected HashMap<String, HashMap<String,String>>cmds;
    protected String defaultModule;
    protected String defaultAction ;
    protected String passwd;
    protected ArrayList<String> systemCmd=new ArrayList(){{add("help");add("quit");add("shutdown");}};
    protected boolean ifLogin=true;
    public TLMsgScanner() {
        super();
    }

    public TLMsgScanner(String name) {
        super(name);
    }

    public TLMsgScanner(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected TLBaseModule init() {
        ifExceptionHandle =false ;
        return this ;
    }
    @Override
    protected void initProperty() {
        super.initProperty();
        if(params!=null  ){
            if( params.get("defaultModule")!=null)
                defaultModule = params.get("defaultModule");
            if( params.get("defaultAction")!=null)
                defaultAction = params.get("defaultAction");
            if( params.get("passwd")!=null)
            {
                passwd = params.get("passwd");
                ifLogin=false;
            }
        }
    }
    protected Object setConfig(){
        myConfig config=new myConfig(configFile,moduleFactory.getConfigDir());;
        mconfig=config;
        super.setConfig();
        msgToModules=config.getMsgToModules();
        cmds = config.getCmds();
        return config ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case "startScan":
                startScan(fromWho,msg);
                break;
            default:
        }
        return returnMsg;
    }

    private void startScan(Object fromWho, TLMsg msg) {
        if(msgToModules==null || msgToModules.isEmpty()){
            System.out.println("no configure msgToModules table");
            return;
        }
        System.out.println(name +" start:");
        if(ifLogin==false)
            System.out.println(" passwd:");
        Scanner sc = new Scanner(System.in);
        //利用hasNextXXX()判断是否还有下一输入项
        while (sc.hasNextLine()) {
            //利用nextXXX()方法输出内容
            String str = sc.nextLine().trim();
            if(str.isEmpty())
                continue;
            if(!ifCheckLogin(str))
               continue;
            if(isSystemCmd(str))
                continue;
            TLMsg cmdMsg=analyzeCmdStr(str);
            if(cmdMsg !=null){
               TLMsg returMsg = putMsg(this,cmdMsg) ;
               if(returMsg !=null)
               {
                   System.out.println("运行结果");
                   TLMsgUtils.printMsg(returMsg);
               }
              continue;
            }
            TLMsg inputMsg=analyzeStr(str);
            if(inputMsg ==null)
                System.out.println(" no cmd:" +str);
            else {
              Boolean result= checkInputMsg(inputMsg);
              if(result ==true)
              {
                  IObject module = (IObject) getModuleFromFactory(inputMsg.getDestination());
                  if(module ==null)
                      System.out.println(" module has no intrance :" +inputMsg.getDestination());
                  else
                     putMsg(module,inputMsg);
              }
              else {
                  System.out.println(" msg is error,please check module or action");
              }
            }
        }
    }



    private boolean ifCheckLogin(String str) {
        if(ifLogin==false )
        {
            if(!str.equals(passwd))
            {
                System.out.println(" passwd is error");
                System.out.println(" passwd:");
            }
            else
            {
                ifLogin =true ;
                System.out.println(" welcome!");
            }
            return false ;
        }
        return true ;
    }

    private boolean isSystemCmd(String str) {
        if(!systemCmd.contains(str))
            return false;
        switch (str){
            case "help" :
                help();
                break;
            case "quit":
                if(passwd!=null && !passwd.isEmpty())
                {
                    ifLogin=false;
                    System.out.println(" you have quit");
                    return true ;
                }
                return false;
            case "shutdown":
                System.out.println(" system is shutdown !");
                moduleFactory.shutdown();
                System.exit(0);

        }
        return true ;
    }

    protected void help() {
        if(msgToModules==null || msgToModules.isEmpty()){
            System.out.println("no configure msgToModules table");
            return;
        }
        for (String moduleName:msgToModules.keySet()) {
            HashMap<String,String> moduleConfigs = msgToModules.get(moduleName);
            String actions =moduleConfigs.get("actions");
            System.out.println("module:"+moduleName +"  actions:"+actions);
        }
        if(cmds==null)
            return;
        for (String cmd:cmds.keySet()) {
            HashMap<String,String> cmdConfigs = cmds.get(cmd);
            String module =cmdConfigs.get("module");
            String action =cmdConfigs.get("action");
            System.out.println("cmd: " +cmd+ "执行 module:"+module+"  action:"+action);
        }
    }

    private Boolean checkInputMsg(TLMsg inputMsg) {
        String module =inputMsg.getDestination() ;
        Boolean result= false ;
        HashMap<String,String> moduleConfigs;
        if(module ==null || module.isEmpty())
        {
            if(defaultModule!=null && !defaultModule.isEmpty())
            {
                module =defaultModule;
                inputMsg.setDestination(module);
            }
            else
            {
                System.out.println("module  must set m=module");
                return false;
            }
        }
        moduleConfigs =msgToModules.get(module);
        if(moduleConfigs==null)
            return false;
        String action =inputMsg.getAction() ;
        if(action ==null || action.isEmpty())
        {
            if(defaultAction!=null && !defaultAction.isEmpty())
            {
                action =params.get("defaultAction");
                inputMsg.setAction(action);
            }
            else
            {
                System.out.println("action  must set a=action");
                return false;
            }
        }
        if(moduleConfigs.get("actions")!=null && !moduleConfigs.get("actions").isEmpty())
        {
            String actionsStr= moduleConfigs.get("actions");
            String actions[] = actionsStr.split(";");
            for(int i=0;i<actions.length;i++)
            {
                if(actions[i].equals(action))
                {
                    result =true;
                    break;
                }
            }
        }
        else
            result =true ;
        return  result ;
    }

    private TLMsg analyzeCmdStr(String string) {
        if(cmds ==null || cmds .isEmpty())
            return null ;
        String str[] = string.split(" ");
        for(int i =0 ;i < str.length ;i++)
        {
            str[i] =str[i].trim() ;
        }
        if(!cmds.containsKey(str[0]))
            return null ;
        HashMap<String,String> msgParams = cmds.get(str[0]);
        if( msgParams.get("module")==null || msgParams.get("module").isEmpty())
            return null ;
        TLMsg cmdMsg =createMsg().setDestination(msgParams.get("module")) ;
        if( msgParams.get("action") ==null || msgParams.get("action").isEmpty() )
        {
            if(msgParams.get("msgid") !=null && !msgParams.get("msgid").isEmpty())
               cmdMsg.setMsgId(msgParams.get("msgid")) ;
             else
                 return null ;
        }
        else
            cmdMsg.setAction(msgParams.get("action")) ;
        if(str.length ==1)
            return cmdMsg ;
        for(int i =1 ;i < str.length ;i++)
        {
            if (!str[i].isEmpty())
            {
                if(!str[i].contains("="))
                    continue;
                else
                {
                    String cparams[] = str[i].split("=");
                    if(cparams.length >=1 && !cparams[0].isEmpty())
                       cmdMsg.setParam(cparams[0],cparams[1]);
                }
            }
        }
        return cmdMsg;
    }

    private TLMsg analyzeStr(String string) {
        TLMsg cmdMsg=createMsg();
        if(!string.contains("="))
            return null;
        String str[] = string.split(" ");
        for(int i =0 ;i < str.length ;i++)
        {
            if (!str[i].isEmpty())
            {
                if(!str[i].contains("="))
                   cmdMsg.setParam(str[i],null);
               else
                {
                    String cmd[] = str[i].split("=");
                    if(cmd.length >=1 && !cmd[0].isEmpty())
                         takeMsg(cmdMsg,cmd);
                }
            }
        }
        return cmdMsg;
    }

    private void takeMsg(TLMsg cmdMsg, String[] cmd) {
        if(cmd.length ==1 )
        {
            cmdMsg.setParam(cmd[0],null);
            return;
        }
        switch (cmd[0]) {
            case "m":
                cmdMsg.setDestination(cmd[1]);
                break;
            case "a":
                cmdMsg.setAction(cmd[1]);
                break;
            default:
                cmdMsg.setParam(cmd[0],cmd[1]);
        }
    }
    protected class myConfig extends TLModuleConfig {
        protected HashMap<String, HashMap<String,String>> msgToModules;
        protected HashMap<String, HashMap<String,String>> cmds;
        public myConfig(String configFile ,String configDir) {
            super(configFile,configDir);
        }
        public myConfig() {

        }
        public HashMap getMsgToModules() {
            return msgToModules;
        }
        public HashMap getCmds() {
            return cmds;
        }
        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if (xpp.getName().equals("msgToModules")) {
                    msgToModules= getHashMap(xpp,"msgToModules","module");
                }
                if (xpp.getName().equals("cmds")) {
                    cmds= getHashMap(xpp,"cmds","cmd");
                }
            } catch (Throwable t) {

            }
        }

    }
}
