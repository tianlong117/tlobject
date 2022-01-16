package cn.tianlong.tlobject.modules;


import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.utils.TLDataUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlpull.v1.XmlPullParser;

import java.util.HashMap;
import java.util.List;

public class TLLog extends TLBaseModule {

    private static TLBaseModule  logObj ;
    protected  HashMap<String, HashMap<String,String>> moduleSetting;
    protected  HashMap<String, HashMap<String,String[]>>logModulesForCheck =new HashMap<>();
    protected  List<String> noLogModules ;
    protected  List<String> logModules ;
    protected  Logger logger;
    protected  LogLevel syslogLevel =LogLevel.INFO;
    public TLLog(){
        super();
        logObj =this ;
    }
    public TLLog(String name ){
        super(name);
        logObj =this ;
    }
    public TLLog(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
        ifMonitor =false ;
        logObj =this ;
    }

    @Override
    protected void initProperty(){
        super.initProperty();
        if(params!=null )
        {
            noLogModules= TLDataUtils.splitStrToList(params.get("noLogModules"),FENHAO) ;
            logModules= TLDataUtils.splitStrToList(params.get("logModules"),FENHAO) ;
            if(params.get("level")!=null)
                syslogLevel =LogLevel.valueOf(params.get("level").toUpperCase());
            else
                syslogLevel=LogLevel.INFO;
        }
        doLogModuleToArray();
    }
    @Override
    protected TLBaseModule init() {
        return  this ;
    }

    protected  Logger getLogger(){
        if(logger==null)
            logger = LoggerFactory.getLogger("tlobject");
        return  logger;
    }
    @Override
    protected Object setConfig(){
        myConfig config=new myConfig(configFile,moduleFactory.getConfigDir());
        mconfig=config;
        super.setConfig();
        moduleSetting =config.getModuleSetting();
        return config ;
    }

    private void  doLogModuleToArray(){
        if(moduleSetting ==null)
            return;
        for(String module: moduleSetting.keySet()){
            HashMap<String,String> moduleConfig = moduleSetting.get(module);
            HashMap<String,String[]> configArray =new HashMap<>();
            String moduleLogLevels[] = TLDataUtils.splitStrToArray(moduleConfig.get("levels"),FENHAO);
            configArray.put("levels",moduleLogLevels);
            String tags[] = TLDataUtils.splitStrToArray(moduleConfig.get("tags"),FENHAO);
            configArray.put("tags",tags);
            logModulesForCheck.put(module,configArray);
        }
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case LOG_PUTLOG:
                putLog(fromWho,msg);
                break;
            case "log":
                setLog(fromWho,msg);
                break;
            case "openLog":
                 ifLog =true;
                 break;
            case "closeLog":
                ifLog =false;
                break;
            default:
        }
        return returnMsg ;
    }

    private void putLog(Object fromWho, TLMsg msg) {
        if(ifLog ==false)
            return  ;
        if(logWait==true)
            setLog(fromWho,msg);
        else {
            IObject threadPool = (IObject) getExistintModule(DEFAULTLOGTTHREADPOOL);
            if(threadPool ==null)
            {
                setLog(fromWho,msg);
                return ;
            }
            TLMsg tmsg = createMsg().setAction(THREADPOOL_EXECUTE).setParam(DOWITHMAG, msg.setAction("log")).setParam(THREADPOOL_P_TASKMODULE, this);
            putMsg(threadPool, tmsg);
        }
    }

    @Override
    public void putLog(String content, LogLevel logLevel, String tag) {

    }
    public static void setLog(String moduleName, String content, LogLevel logLevel, String tag,String threadPool) {
        logObj.putLog(content, logLevel,  tag,null ,threadPool,moduleName);
    }
    protected void setLog(Object fromWho, TLMsg msg) {
        if(ifLog ==false)
              return;
        String thread = (String) msg.getParam("thread");
        String moduleName=(String)msg.getParam(LOG_P_LOGMODULE);
        String  tag=(String)msg.getParam(LOG_P_LOGTAG);
        String content =(String) msg.getParam(LOG_P_LOGONTENT);
        LogLevel logLevel= (LogLevel) msg.getParam(LOG_P_LOGLEVEL);
        if( checkIfLog( moduleName ,logLevel, tag)==false)
              return;
        writeLog(moduleName, content, logLevel, tag,thread);
    }
    public  void  writeLog(String moduleName, String content, LogLevel logLevel, String tag,String thread) {
        if(thread ==null)
            thread =Thread.currentThread().getName();
        StringBuilder logBuffer = new StringBuilder();
        logBuffer.append(thread);
        logBuffer.append(":");
        logBuffer.append(moduleName);
        if(tag !=null && !tag.isEmpty())
        {
            logBuffer.append(" Tag:");
            logBuffer.append(tag);
        }
        logBuffer.append(" Content:");
        logBuffer.append(content);
        setLog0( logBuffer.toString(), logLevel);
    }
    private  void setLog0( String content,LogLevel logLevel) {
        getLogger();
        switch (logLevel){
            case TRACE:
                logger.trace(content);
                break;
            case DEBUG:
                logger.debug(content);
                break;
            case INFO:
                logger.info(content);
                break;
            case WARN:
                logger.warn(content);
                break;
            case ERROR:
                logger.error(content);
                break;
            default:
                logger.info(content);
        }
    }

    protected  Boolean checkIfLog(String module , LogLevel logLevel , String tag ){
        if(logLevel.compareTo(syslogLevel) <0)
            return false;
        if(logModules !=null && !logModules.isEmpty() && !logModules.contains(module))
            return false;
        else if(noLogModules !=null && !noLogModules.isEmpty() && noLogModules.contains(module))
            return false;
        HashMap<String,String[]> moduleConfig =logModulesForCheck.get(module);
        if( moduleConfig==null || moduleConfig.isEmpty())
            return true ;
        if(checkString( moduleConfig.get("levels"),logLevel.toString())==false)
            return false ;
        if(checkString(moduleConfig.get("tags"),tag)==false)
            return false ;
        return true;
    }

    private static boolean checkString(String[] stringArr, String checkValue)
    {
        if(stringArr==null ||stringArr.length==0)
            return true ;
        if(checkValue==null ||checkValue.isEmpty())
            return false;
        for(String s: stringArr)
       {
        if(s.equalsIgnoreCase(checkValue))
             return true;
       }
       return false;
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
