package cn.tianlong.tlobject.base;

import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;
import cn.tianlong.tlobject.utils.TLMsgUtils;
import cn.tianlong.tlobject.utils.TLToolsUtils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.lang.Thread.sleep;

/**
 * 创建日期：2018/4/5 on 8:46
 * 描述:
 * 作者:tianlong
 */

/**
 *  TLBaseModule 为消息对象编程的基础，继承此类的模块对象则具有消息对象的功能和属性。
 *
 */
public abstract class TLBaseModule extends TLBaseObject {
    public static final String PRERESULT = "beforeResult";
    protected String applicationId="tlobjectApp";
    protected boolean ifMonitor = false;    //是否开启工厂监控 ，默认关闭
    protected HashMap<String, HashMap<String, String>> modulesClass;  //定义的模块配置，取代工厂配置，getmodule 时自动赋值
    protected HashMap<String, HashMap<String, String>> modulesParams;  //定义的模块配置参数params，getmodule 时自动赋值
    protected HashMap<String, HashMap<String, String>> paramsForModules;   //定义参数适用的模块，getmodule 时自动赋值
    protected Map<String, Object> modules = new ConcurrentHashMap<>();   // 模块对象实例，名字对应该模块的实例
    protected Map<String, Method> classMethods ;   // 类方法的实例，名字对应该方法的实例
    protected HashMap<String, HashMap<String, Object>> msgTable;   //消息路由表，msgid→{msglist:ArrayList<TLMsg>, mode:"parallel", ...}
    protected ArrayList<TLMsg> initMsgTable;          //对象初始化时，还没放入工厂，执行的消息队列
    protected ArrayList<TLMsg> startMsgTable;          //对象初始化后，已经放入工厂，执行的消息队列
    protected HashMap<String, ArrayList<TLMsg>> beforeMsgTable;          //方法运行前执行的msg列表
    protected HashMap<String, ArrayList<TLMsg>> afterMsgTable;          //方法运行后执行的msg列表
    protected TLObjectFactory moduleFactory;
    protected HashMap<String, String> params;  //模块参数，在配置文件中设定
    protected String configFile;
    protected TLModuleConfig mconfig;
    protected boolean destroy = true;       //工厂发出销毁命令时，返回的销毁标志
    protected volatile boolean shutdownable = true;  //是否允许关闭，默认允许
    protected boolean autoConfig = true; //当配置文件没有设置 ，是否采取工厂自动配置 ，默认采用
    protected boolean ifLog = true;        //是否开启日志 ，默认开启
    protected boolean logWait = true;      //日志是否同步，默认同步
    protected LogLevel defaultLoglevel = LogLevel.INFO;     //默认日志级别
    protected List<String> logTags;       // 日志输出控制，允许日志输出的日志标签，分号分割，设置后只有定义的标签输出日志
    protected List<String> nologTags;     //日志输出控制，禁止日志输出的标签，分号分割
    protected boolean ifExceptionHandle = true;   //发生异常时是否处理 ，默认处理，否则继续运行
    protected String exceptionHandler;     //异常处理模块，如果设置则由该模块处理异常，否则由工厂处理 。返回如果为空，则程序停止
    protected Boolean ifDoMsgTransfer =false;   //对于其他目的的msg是否执行msgTransfer ，默认不执行
    protected Long startTime ;
    public TLBaseModule() {
        super();
    }

    public TLBaseModule(String name) {
        this.name = name;
        modules.put(name, this);
        startTime =System.currentTimeMillis();
    }
    public TLBaseModule(String name ,String configFile) {
        this( name);
        this.configFile =configFile ;
    }
    public TLBaseModule(String name, TLObjectFactory moduleFactory) {
        this( name);
        registFactory(moduleFactory);
        this.moduleFactory = moduleFactory;
    }
    public TLBaseModule(String name, String configFile,TLObjectFactory moduleFactory) {
        this( name,moduleFactory);
        this.configFile =configFile ;
    }
    public void setFactory(TLObjectFactory moduleFactory) {
        this.moduleFactory = moduleFactory;
        if(moduleFactory==null)
            return;
        registFactory(moduleFactory);
    }
    private void  registFactory(TLObjectFactory factory){
        String factoryName =factory.getName();
        modules.put(factoryName, factory);

            modules.put(MODULEFACTORY, factory);
    }
    public TLObjectFactory getFactory() {
        return moduleFactory;
    }

    public String getConfigFile() {
        return configFile;
    }

    public TLBaseModule start(String configFile, HashMap<String, String> startParams) {
        if (configFile != null)
            this.configFile = configFile;
        if (startParams != null)
            params = startParams;
        configure();
        StringBuilder logBuffer0 = new StringBuilder().append(name).append(" begin start init ,params:\n\r");
        if(params !=null && !params.isEmpty())
        {
            for(String key : params.keySet())
                logBuffer0.append(key).append("=").append(params.get(key)).append(";");
        }
        putLog(logBuffer0.toString(), LogLevel.DEBUG, "start");
        TLBaseModule initModule = init();
        if(initModule ==null)
            return null ;
        runInitMsg();
        Long startUseTime =System.currentTimeMillis()-startTime ;
        Long factoryTime=0L;
        if( moduleFactory !=null)
            factoryTime = moduleFactory.getRunTime(false);
        StringBuilder logBuffer = new StringBuilder().append(name).append(" have start over, configFile: ").append(this.configFile).append(" startTimes: ")
                                 .append(startUseTime).append("ms factoryTime:").append(factoryTime).append("ms");
        putLog(logBuffer.toString(), LogLevel.DEBUG, "start");
        return this ;
    }

    protected void configure() {
        setConfig();
        setSystemParams();
        initProperty();
        setfieldFromModule();
        reMakeProperty() ;
        setModuleParams();

    }
    protected Object setConfig() {
        String parseFile ;
        if (configFile == null || configFile.isEmpty())
        {
            if (autoConfig == false)
                return null;
            else {
                String configDir = moduleFactory.getConfigDir();
                parseFile = configDir +  name + "_config.xml";
            }
        }
        else
            parseFile =configFile ;
        if (mconfig == null)
            mconfig = new TLModuleConfig(parseFile,moduleFactory.getConfigDir());
        mconfig.setFactory(moduleFactory);
        mconfig= mconfig.parse(parseFile);
        if(mconfig ==null)
           configFile=null ;
        else
            configFile =parseFile ;
        return  mconfig;
    }

    protected void initProperty() {
        if (mconfig != null)
        {
            modulesClass = mconfig.getModulesClass();
            modulesParams =mconfig.getModulesParams();
            paramsForModules =mconfig.getParamsModules();
            initMsgTable = mconfig.getInitMsg();
            msgTable = mconfig.getMsgTable();
            beforeMsgTable = mconfig.getBeforeMsgTable();
            afterMsgTable = mconfig.getAfterMsgTable();
            startMsgTable=mconfig.getStartMsgTable();
            if (params == null || params.isEmpty())
                params = mconfig.getParams();
            else {
                HashMap<String, String> cparams = mconfig.getParams();
                if (cparams != null) {
                    params.putAll(cparams);
                }
            }
        }
        if (params !=null && params.get("applicationId") != null)
            applicationId = params.get("applicationId");
    }

    protected void reMakeProperty() {
        if(modulesParams !=null && !modulesParams.isEmpty())
            splitModulesParams(modulesParams);
        if(paramsForModules !=null && !paramsForModules.isEmpty())
            splitParamsModules(paramsForModules);
        if(initMsgTable !=null && !initMsgTable.isEmpty())
            setParamsFromMsg(initMsgTable);
        if(beforeMsgTable !=null && !beforeMsgTable.isEmpty())
        {
            setParamsFromMsg(beforeMsgTable) ;
            splitActionsArray(beforeMsgTable) ;
        }
        if(afterMsgTable !=null && !afterMsgTable.isEmpty())
        {
            setParamsFromMsg(afterMsgTable);
            splitActionsArray(afterMsgTable) ;
        }
        if(initMsgTable !=null && !initMsgTable.isEmpty())          // 设置 MSGTABLEUSETYPE 为 MSGTABLEONLY 方式
            setMsgTableUseType(initMsgTable,MSGTABLEONLY) ;
        if(startMsgTable !=null && !startMsgTable.isEmpty())
            setMsgTableUseType(startMsgTable,MSGTABLEONLY) ;
    }

    protected void setfieldFromModule() {
        if(params ==null)
            return;
        if(  params.get("fieldsInModule")!=null && !params.get("fieldsInModule").isEmpty())
        {
           String[] fields =TLDataUtils.splitStrToArray( params.get("fieldsInModule"),";");
           if(fields ==null)
               return;
            String fieldValueModule = "moduleFieldInDb" ;
           if( params.get("fieldValueModule")!=null && !params.get("fieldValueModule").isEmpty())
              fieldValueModule= params.get("fieldValueModule");
           for(int i=0 ;i<fields.length ; i++){
               String fieldName =fields[i];
               Field field =getField(fieldName) ;
               if(field ==null)
                   continue;
              String fieldType =field.getType().getSimpleName();
              fieldName =getFieldNameInApp(fieldName);
              TLMsg msg =createMsg().setAction("getFieldValue").setParam("fieldName",fieldName).setParam("fieldType",fieldType);
              TLMsg  fieldMsg = putMsg(fieldValueModule,msg);
              if(fieldMsg ==null )
                  return;
              Object value =fieldMsg.getParam("value");
               try {
                   field.setAccessible(true);
                   field.set(this ,value);
               } catch (IllegalAccessException e) {
                   putLog(" field set error:"+ fieldName,LogLevel.ERROR);
                   putLog(e, LogLevel.ERROR, "setfieldFromModule");
               }
           }
        }
    }
    public String getFieldNameInApp(String fieldName){
        return applicationId +":"+name+":"+fieldName;
    }
    public String getApplicationId(){
        return  applicationId;
    }
    public TLModuleConfig getModuleMConfig(){
        return  mconfig ;
    }

    private void splitModulesParams(HashMap<String,HashMap<String,String>> modulesParams) {
        HashMap<String,HashMap<String,String>>  tmpMap =new HashMap<>();
        Iterator<?> entries = modulesParams.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry entry = (Map.Entry) entries.next();
            String key = (String) entry.getKey();
            if(key.indexOf(";") !=-1)
            {
                String[] moduelsArray =TLDataUtils.splitStrToArray(key,";");
                if(moduelsArray ==null )
                    continue;
                for(int i=0 ; i<moduelsArray.length ; i ++)
                {
                    String moduleUnit = moduelsArray[i];
                    HashMap<String,String> mParams = (HashMap<String, String>) entry.getValue();
                    if(tmpMap.containsKey(moduleUnit))
                    {
                        HashMap<String,String> tmpParams =tmpMap.get(moduleUnit);
                        tmpParams.putAll(mParams);
                    }
                    else
                        tmpMap.put(moduleUnit,mParams);
                }
                entries.remove();
            }
        }
        if(tmpMap.isEmpty())
            return;
        for(String module : tmpMap.keySet())
        {
            HashMap<String,String> mParams =tmpMap.get(module);
            if (modulesParams.containsKey(module))
            {
                HashMap<String,String> tmpParams = modulesParams.get(module);
                tmpParams.putAll(mParams);
            }
            else
                modulesParams.put(module, mParams);
        }
    }

    private void splitParamsModules(HashMap<String,HashMap<String,String>> paramsModules) {
        for(String paramName : paramsModules.keySet())
        {
            HashMap<String,String> map =paramsModules.get(paramName) ;
            String value =map.get("value");
            String modules =map.get("modules");
            if(modules ==null || modules.isEmpty())
                continue;
            String[] moduelsArray =TLDataUtils.splitStrToArray(modules,";");
            if(moduelsArray ==null )
                continue;
            if(modulesParams ==null)
                modulesParams =new HashMap<>() ;
            for(int i=0 ; i<moduelsArray.length ; i ++)
            {
               String moduleUnit = moduelsArray[i];
              if(modulesParams.containsKey(moduleUnit))
              {
                   HashMap<String,String> tmpParams =modulesParams.get(moduleUnit);
                   tmpParams.put(paramName,value);
              }
               else
                  {
                      HashMap<String,String> tmpParams =new HashMap<>();
                      tmpParams.put(paramName,value);
                      modulesParams.put(moduleUnit,tmpParams);
                  }
            }
        }
    }
    // 设置 MSGTABLEUSETYPE 为 MSGTABLEONLY 方式
   protected void  setMsgTableUseType(ArrayList<TLMsg> msgList ,String msgTableUserType){
        for(int i=0 ;i <msgList.size() ; i++){
            TLMsg msg =msgList.get(i) ;
            if(msg.systemParamIsNull(MSGTABLEUSETYPE))
                msg.setSystemParam(MSGTABLEUSETYPE,msgTableUserType) ;
        }
    }

    protected  void splitActionsArray(HashMap<String,ArrayList<TLMsg>> msgTableList){
        HashMap<String,ArrayList<TLMsg>> tmpMsgList =new HashMap<>() ;
        for(String action : msgTableList.keySet())
        {

            if(action.indexOf(";") !=-1)
            {
                String[] actionArray =TLDataUtils.splitStrToArray(action,";");
                if(actionArray ==null )
                    continue;
                for(int i=0 ; i<actionArray.length ; i ++)
                {
                    String actionUnit = actionArray[i];
                    if(actionUnit ==null || actionUnit.isEmpty())
                        continue;
                    ArrayList<TLMsg> msgListCopy =new ArrayList<>() ;
                    ArrayList<TLMsg> msgList =msgTableList.get(action);
                    for(TLMsg msg :msgList)
                    {
                        TLMsg nmsg = new TLMsg() ;
                        nmsg.copyFrom(msg);
                        msgListCopy.add(nmsg) ;
                    }
                    if(tmpMsgList.containsKey(actionUnit))
                    {
                        ArrayList<TLMsg> actionMsgList =tmpMsgList.get(actionUnit);
                        msgListCopy.addAll(actionMsgList);
                    }
                    else
                        tmpMsgList.put(actionUnit,msgListCopy);
                }
            }
        }
        if(tmpMsgList.isEmpty())
            return;
        for(String action : tmpMsgList.keySet())
        {
            ArrayList<TLMsg> msgList =tmpMsgList.get(action);
            if (msgTableList.containsKey(action))
            {
                ArrayList<TLMsg> actionMsgList = msgTableList.get(action);
                msgList.addAll(actionMsgList);
            }
           else
               msgTableList.put(action, msgList);
        }
    }
    // 初始化 PARAMSFROMMSG 参数
    protected void setParamsFromMsg(ArrayList<TLMsg> msgList){
        for (int i = 0; i <  msgList.size(); i++){
            TLMsg msg =msgList.get(i) ;
            String paramsFromMsg = (String) msg.getSystemParam(PARAMSFROMMSG,null);
            if(paramsFromMsg !=null )
            {
                String[] paramskey =(paramsFromMsg.trim().split(";"));
                if(paramskey !=null && paramskey.length >0)
                    msg.setSystemParam(PARAMSFROMMSG,paramskey) ;
            }
        }
    }

    private void setParamsFromMsg(HashMap<String, ArrayList<TLMsg>> msgHashMap){
        for(ArrayList<TLMsg> msgList :msgHashMap.values()){
              setParamsFromMsg(msgList);
        }
    }
    protected Field getField(String fieldName){
        Class clazz = this.getClass();
        try {
            Field field=clazz.getDeclaredField(fieldName) ;
            return  field ;
        } catch (NoSuchFieldException e) {
            putLog("no field:"+fieldName ,LogLevel.ERROR);
            putLog(e, LogLevel.ERROR, "getField");
            return null ;
        }
    }
    protected Field[] getAllFields(){
        Class clazz = this.getClass();
        List<Field> fieldList = new ArrayList<>();
        while (clazz != null){
            fieldList.addAll(new ArrayList<>(Arrays.asList(clazz.getDeclaredFields())));
            clazz = clazz.getSuperclass();
        }
        Field[] fields = new Field[fieldList.size()];
        fieldList.toArray(fields);
        return fields;
    }
    /** 覆盖方法时，如果初始化成功要返回对象实例句柄this，否则返回空值 **/
    protected abstract TLBaseModule init();
    protected void setModuleParams() {
    }
    private void setSystemParams() {
        if (params != null && !params.isEmpty()) {
            if (params.get("applicationId") != null)
                applicationId = params.get("applicationId");
            if (params.get(IFDOMSGTRANSFERACTION) != null)
                ifDoMsgTransfer = Boolean.parseBoolean(params.get(IFDOMSGTRANSFERACTION));
            if (params.get("ifMonitor") != null)
                ifMonitor = Boolean.parseBoolean(params.get("ifMonitor"));
            if (params.get("autoConfig") != null)
                autoConfig = Boolean.parseBoolean(params.get("autoConfig"));
            if (params.get("exceptionHandler") != null)
                exceptionHandler =params.get("exceptionHandler");
            if (params.get("ifExceptionHandle") != null)
                ifExceptionHandle =Boolean.parseBoolean(params.get("ifExceptionHandle"));
            String ifLogStr = params.get("ifLog");
            if (ifLogStr != null && !ifLogStr.isEmpty())
                ifLog = Boolean.parseBoolean(ifLogStr);
            String logWaitStr = params.get("logWait");
            if (logWaitStr != null && !logWaitStr.isEmpty())
                logWait = Boolean.parseBoolean(logWaitStr);
            String loglevelstr = params.get("loglevel");
            if (loglevelstr != null && !loglevelstr.isEmpty())
                defaultLoglevel = LogLevel.valueOf(params.get("loglevel").toUpperCase());
            String logTagsStr = params.get("logtags");
            logTags =TLDataUtils.splitStrToList(logTagsStr,FENHAO) ;
            String nologTagsStr = params.get("nologtags");
            nologTags =TLDataUtils.splitStrToList(nologTagsStr,FENHAO) ;
        }
    }

    protected void runInitMsg() {
        if (initMsgTable != null)
        {
            putLog("start run initMsg", LogLevel.DEBUG, "runInitMsg");
            doMsgList(initMsgTable, null, null);
        }
    }

    public void runStartMsg() {
        if (startMsgTable != null)
        {
            putLog("start run startMsg", LogLevel.DEBUG, "runStartMsg");
            doMsgList(startMsgTable, null, null);
        }
    }

    protected Object  parseConfig(String paramName){
       return mconfig.parseParamFromConfig(paramName);
    }

    protected TLMsg doAfterMsgTable( String action, TLMsg msg, TLMsg actionReturnMsg) {

        ArrayList msgList = afterMsgTable.get("*");
        if (msgList != null && !msgList.isEmpty())
        {
            putLogForMsglist("*" ,"after");
            TLMsg msgListReturnMsg = doMsgList(msgList, msg, actionReturnMsg);
            if (!ifDoNextMsg(msgListReturnMsg))
                return msgListReturnMsg;
        }
        msgList = afterMsgTable.get(action);
        if (msgList != null && !msgList.isEmpty())
        {
            putLogForMsglist(action ,"after");
            TLMsg msgListReturnMsg = doMsgList(msgList, msg, actionReturnMsg);
            return msgListReturnMsg;
        }
        return actionReturnMsg ;
    }

    protected TLMsg doBeforMsgTable(String action, TLMsg msg) {
        TLMsg msgListReturnMsg = null;
        ArrayList msgList = beforeMsgTable.get("*");
        if (msgList != null && !msgList.isEmpty())
        {
            putLogForMsglist("*" ,"before");
            msgListReturnMsg = doMsgList(msgList, msg, null);
            if (!ifDoNextMsg(msgListReturnMsg))
                return selectReturnMsg(msg, msgListReturnMsg);
        }
        msgList = beforeMsgTable.get(action);
        if (msgList != null && !msgList.isEmpty())
        {
            putLogForMsglist(action ,"before");
            TLMsg actionReturnMsg = doMsgList(msgList, msg, null);
            if (!ifDoNextMsg(actionReturnMsg))
                return selectReturnMsg(msg, actionReturnMsg);
            msgListReturnMsg = actionReturnMsg;
        }
        return selectReturnMsg( msg, msgListReturnMsg);
    }

    private void putLogForMsglist(String action ,String type){
        String tag =action+":"+type;
        if(checkIfLog(LogLevel.DEBUG,tag)==true){
            StringBuilder logBuffer = new StringBuilder();
            logBuffer.append("start run ");
            logBuffer.append(action);
            logBuffer.append("'s ");
            logBuffer.append(type);
            logBuffer.append(" msgTable");
            putContentLog(logBuffer.toString(), LogLevel.DEBUG, tag,true,null,name);
        }
    }

    private TLMsg selectReturnMsg(TLMsg msg, TLMsg returnMsg) {
            if (returnMsg == null)
                return msg;
            if (!returnMsg.systemParamIsNull(RESULTFORNEXTMSG) && TLDataUtils.parseBoolean(returnMsg.getSystemParam(RESULTFORNEXTMSG),false)==true)
               return returnMsg ;
            msg.setSystemParam(PRERESULT, returnMsg);
            if (TLDataUtils.parseBoolean(msg.getSystemParam(USEPRERETURNMSG),false)==true
                    ||TLDataUtils.parseBoolean(msg.getSystemParam(RESULTFORNEXTMSG),false)==true
                   )
                msg.addArgs(returnMsg.getArgs());
            return msg;
    }

    protected TLMsg doMsgList(ArrayList<TLMsg> msgList, TLMsg msg, TLMsg actionReturnMsg) {
        TLMsg returnMsg = null;
        int msgListSize = msgList.size();
        for (int i = 0; i <  msgListSize; i++)
        {
            TLMsg msgInMsgList = msgList.get(i);
            TLMsg cmsg ;
            String msgTableUserType = (String) msgInMsgList.getSystemParam(MSGTABLEUSETYPE);
            if (msgTableUserType !=null && msgTableUserType.equals(MSGTABLEONLY))
                cmsg =msgInMsgList ;
            else
                {
                    String[] paramKeys = (String[]) msgInMsgList.getSystemParam(PARAMSFROMMSG);
                    if (msgTableUserType !=null && msgTableUserType.equals(USEMSG))
                    {
                        cmsg =msg ;
                        cmsg = copyMsgFromMsgTable(cmsg, msgInMsgList);
                     }
                    else
                        {
                             cmsg = new TLMsg();
                             cmsg = copyMsgFromMsgTable(cmsg, msgInMsgList);
                             if (msg != null )
                             {
                                 cmsg.setSystemParam(DOWITHMSG, msg);
                                  if(TLDataUtils.parseBoolean(msgInMsgList.getSystemParam(USEINPUTMSG),true)==true )
                                  {
                                      cmsg.copyParams(paramKeys,msg);
                                      cmsg.addSystemArgs(msg.getSystemArgs());
                                  }
                             }
                        }
                     usePreReturnMsg(cmsg , returnMsg) ;
                     if (actionReturnMsg != null )
                     {
                        cmsg.setSystemParam(PRERESULT, actionReturnMsg);
                        if(TLDataUtils.parseBoolean(msgInMsgList.getSystemParam(USEACTIONRETURNMSG),false)==true)
                            cmsg.copyParams(paramKeys,actionReturnMsg );
                     }
                }
            putLog(cmsg,LogLevel.DEBUG,"doMsgList");
            if(cmsg.getWaitFlag()==true)
                returnMsg = putMsg(name, cmsg);
            else {
                putMsg(name, cmsg);  //异步无返回值
                returnMsg = actionReturnMsg;
            }
            if (i == msgListSize-1 || !ifDoNextMsg(returnMsg)  )
            {
                if ( TLDataUtils.parseBoolean(msgInMsgList.getSystemParam(RETURNACTIONRETURNMSG),false)==true
                        ||
                    (msg !=null && TLDataUtils.parseBoolean(msg.getSystemParam(RETURNACTIONRETURNMSG),false)==true))
                    returnMsg= actionReturnMsg;
                break;
            }
        }
        return returnMsg;
    }
    /**
     * 与 doMsgList 对称的并行执行器：所有 msg 独立 copyFrom template + 注入 paramKeys，
     * putMsgGroupByThread 并发发出，返回 TLMsg 内含 RESULT 为 List&lt;TLMsg&gt; 原始结果集
     * （无格式化——标注由应用层完成）。未设置 destination 的 msg 默认发往本模块自身。
     */
    @SuppressWarnings("unchecked")
    protected TLMsg doMsgListParallel(ArrayList<TLMsg> msgList, TLMsg msg, int waitTime) {
        List<TLMsg> parallelMsgs = new ArrayList<>();
        for (TLMsg template : msgList) {
            TLMsg step = new TLMsg();
            step.copyFrom(template);
            // 从原始消息注入参数，遵循 MSGTABLEONLY 外的 copyMsgFromMsgTable 语义
            String useType = (String) template.getSystemParam(MSGTABLEUSETYPE);
            if (useType == null || !useType.equals(MSGTABLEONLY)) {
                String[] paramKeys = (String[]) template.getSystemParam(PARAMSFROMMSG);
                step.copyParams(paramKeys, msg);
                if (msg != null && TLDataUtils.parseBoolean(template.getSystemParam(USEINPUTMSG), true) == true)
                    step.copyParams(paramKeys, msg);
                step.addSystemArgs(msg != null ? msg.getSystemArgs() : null);
            }
            step.setSource(name);
            if (step.getDestination() == null || step.getDestination().isEmpty())
                step.setDestination(name);   // 缺省发往本模块自身
            parallelMsgs.add(step);
        }
        TLMsg groupResult = putMsgGroupByThread(parallelMsgs, waitTime);
        return groupResult;
    }

    protected   boolean ifToMySelf(String destination ){
        if(destination ==null)
            return true ;
        int index =destination.indexOf("@");
        if(index <0 && destination.equals(name))
          return true ;
        if(destination.equals(name+"@"+moduleFactory.getName()) || destination.equals(name+"@"+MODULEFACTORY) )
            return  true ;
         return  false ;
    }
    public TLMsg getMsg(Object fromWho, TLMsg msg) {
        putLog(msg, LogLevel.TRACE, "getMsg");
        if (ifMonitor)
            msg = moduleFactory.moduleActionStart(name, msg);
        if (!ifDoNextMsg(msg))
            return msg;
        TLMsg returnMsg;
        String destination = msg.getDestination();
        if (!ifToMySelf(destination) )
        {
            if(ifDoMsgTransfer ==true && TLDataUtils.parseBoolean(msg.getSystemParam(IFDOMSGTRANSFERACTION),false) ==true)
            {
                TLMsg tranferMsg = createMsg().setAction(MODULE_MSGTRANSFER).setParam("domsg", msg);
                msg = tranferMsg;
            }
            else {
                returnMsg = putMsg(destination, msg);
                if (ifMonitor)
                    returnMsg = moduleFactory.moduleActionEnd(name, msg, returnMsg);
                return returnMsg;
            }
        }
        //防止某些情况直接使用msg而没有清空destiantion，导致循环
     //   msg.setDestination(null);
        String msgId = msg.getMsgId();
        String action = msg.getAction();
        if ( action == null && msgId == null)
            returnMsg = defaultAction( fromWho, msg);
        else if (action == null && msgId != null )  //检查是否设置msgid
            returnMsg = checkMsgId(msgId, fromWho, msg);
        else {
            TLMsg preResult =null ;
            if(beforeMsgTable  !=null && !beforeMsgTable.isEmpty() && msg.parseBoolean(IGNOREBEFORE,false) == false)
            {
                returnMsg =doBeforMsgTable( action, msg);
                preResult= (TLMsg) returnMsg.getSystemParam(PRERESULT);
            }
            else
                returnMsg =msg ;
            if (ifDoNextMsg(preResult))
            {
                try {
                    returnMsg = runAction(fromWho, returnMsg);
                } catch (Exception e) {
                    returnMsg = exception(returnMsg, e);
                }
                // 先执行 nextMsg 链，再执行 afterMsgTable
                // 这样 after 钩子在整条处理链完成后触发，语义上更正确（善后/清理）
                // 同时也让 before→action→nextMsg→after 形成天然的断点配对
                TLMsg nextMsg = msg.getNextMsg();
                if (nextMsg != null && ifDoNextMsg(returnMsg))
                {
                    usePreReturnMsg(nextMsg ,returnMsg) ;
                    returnMsg = ((IObject) fromWho).putMsg(this, nextMsg);
                }
                if (afterMsgTable !=null && !afterMsgTable.isEmpty() && msg.parseBoolean(IGNOREAFTER,false) == false && ifDoNextMsg(returnMsg))
                     returnMsg = doAfterMsgTable(action, msg, returnMsg);
            } else
                {
                preResult.removeSystemParam(MODULE_DONEXTMSG);
                returnMsg = preResult;
                }
        }
        if (ifMonitor)
            returnMsg = moduleFactory.moduleActionEnd(name, msg, returnMsg);
        return returnMsg;
    }
   private  TLMsg  usePreReturnMsg(TLMsg msg ,TLMsg returnMsg){
       if (returnMsg != null)
       {
           if (TLDataUtils.parseBoolean(msg.getSystemParam(USEPRERETURNMSG),false)==true) {
               msg.copyParams((String[]) msg.getSystemParam(PARAMSFROMMSG,null),returnMsg);
               if(returnMsg.getParam(RESULT) !=null)
                   msg.setParam(INPUT,returnMsg.getParam(RESULT)) ;
               else
                   msg.setParam(INPUT,returnMsg.getSingleParam(null));
           }
       }
       return  msg ;
   }
   protected TLMsg defaultAction(Object fromWho, TLMsg msg) {
        return null ;
    }

    protected TLMsg exception(TLMsg msg, Exception exception) {
        StringBuilder sb = new StringBuilder();
        sb.append(name);
        if(msg !=null){
            sb.append("\n [msg]:");
            sb.append(TLMsgUtils.msgToStr(msg));
        }
        sb.append("\n ");
        sb.append(TLToolsUtils.exceptionToString(exception));
        sb.append("\n");
        String content = sb.toString();
        putLog(content, LogLevel.ERROR,msg.getAction());
        if(ifExceptionHandle==false)
            return createMsg().setParam(EXCEPTIONHANDLER_P_MSG,msg);
        TLMsg  handlerMsg =createMsg().setAction(EXCEPTIONHANDLER_HANDLER).setParam(EXCEPTIONHANDLER_P_MODULE,name)
                .setParam(EXCEPTIONHANDLER_P_MSG,msg).setParam(EXCEPTIONHANDLER_P_EXCEPTION,exception);
        TLMsg returnMsg;
        if(exceptionHandler !=null)
           returnMsg= putMsg(exceptionHandler,handlerMsg);
        else
            returnMsg= putMsg(moduleFactory,handlerMsg);
        if(returnMsg ==null)
            moduleFactory.shutdown(-1);
        return returnMsg;
    }

    protected Boolean ifDoNextMsg(TLMsg returnMsg) {
         if(returnMsg ==null)
             return true ;
         return TLDataUtils.parseBoolean(returnMsg.getSystemParam(MODULE_DONEXTMSG),true);
    }
    protected String getFactoryParam(String paramName){
        return moduleFactory.getParam(paramName) ;
    }
    protected String getModuleParam(String moduleName ,String paramName){
        TLMsg returnMsg =putMsg(moduleName,createMsg().setAction(MODULE_GETPARAM)
                .setParam(MODULE_PARAMS,paramName));
       return (String) returnMsg.getParam(paramName);
    }
    protected void invokeActionInThread(String action ,IObject fromWho ,TLMsg msg)
    {
        TLMsg thMsg =createMsg().setAction("runActionInThread")
                   .setParam(MSG_P_ACTION,action)
                   .setWaitFlag(false);
        if(msg !=null)
        {
            thMsg.setParam("dmsg",msg) ;
            if(!msg.systemParamIsNull(INTHREADPOOL))
                thMsg.setSystemParam(INTHREADPOOL,msg.getSystemParam(INTHREADPOOL));
            if(!msg.systemParamIsNull(THREADPOOLNAME))
               thMsg.setSystemParam(THREADPOOLNAME,msg.getSystemParam(THREADPOOLNAME));
        }
        fromWho.putMsg(this,thMsg);
    }
    protected TLMsg invokeActionInThreadAndWait(String action ,TLBaseModule fromWho ,TLMsg msg)
    {
        TLMsg thMsg =createMsg().setAction("runActionInThread").setParam(MSG_P_ACTION,action)
                .setParam("dmsg",msg) .setWaitFlag(false);
        thMsg.setSystemParam(INTHREADPOOL,msg.getSystemParam(INTHREADPOOL,true));
        thMsg.setSystemParam(THREADPOOLNAME,msg.getSystemParam(THREADPOOLNAME));
        thMsg.setWaitFlag(false);
        thMsg.setSystemParam(TASKWAITTIME,0);
        return  fromWho.putMsg(this,thMsg);
    }
    protected TLMsg invokeAction(String action,Object fromWho,TLMsg msg){
        Method method =getMethod(action) ;
        if(method==null)
            return null ;
        TLMsg result = null;
        try {
            result = (TLMsg) method.invoke(this,fromWho,msg);
        } catch (IllegalAccessException e) {
            putLog(e, LogLevel.ERROR, action);
        } catch (InvocationTargetException e) {
            putLog(e, LogLevel.ERROR, action);
        }
        return result;
    }

    private Method getMethod(String action) {
        String actionName =this.getClass().getSimpleName()+":"+action;
        Method method = null;
        if(classMethods !=null)
            method =  classMethods.get(actionName);
        if(method==null)
        {
           synchronized (this)
           {
               if(classMethods ==null)
                   classMethods = new ConcurrentHashMap<>();
               else {
                   method =  classMethods.get(actionName);
                   if(method !=null)
                       return method ;
               }
               method = getDeclaredMethod(this,action);
               if(method==null)
                   return null ;
               if(!method.isAccessible())
                   method.setAccessible(true);
               classMethods.put(actionName,method);
           }
        }
        return method ;
    }

    /**
     * 循环向上转型, 获取对象的 DeclaredMethod
     * @param object : 子类对象
     * @param methodName : 父类中的方法名
     * @return 父类中的方法对象
     */
    private Method getDeclaredMethod(Object object, String methodName){
        Method method = null ;
        for(Class<?> clazz = object.getClass(); clazz != Object.class; clazz = clazz.getSuperclass()) {
            try {
                method = clazz.getDeclaredMethod(methodName,Object.class,TLMsg.class);
                putLog("获取类方法，类："+clazz.getName()+" ,方法:"+methodName,LogLevel.DEBUG,"getMethod");
                return method ;
            } catch (Exception e) {
                //这里甚么都不要做！并且这里的异常必须这样写，不能抛出去。
                //如果这里的异常打印或者往外抛，则就不会执行clazz = clazz.getSuperclass(),最后就不会进入到父类中了
            }
        }
        return null;
    }

    protected ScheduledExecutorService runActionWithFixedDelay(String taskAction ,int period )
    {
      return  runActionWithFixedDelay( taskAction,this,null ,0 , period ,TimeUnit.SECONDS);
    }
    protected ScheduledExecutorService runActionWithFixedDelay(String taskAction,Object fromWho,TLMsg msg ,int delay ,int period ,TimeUnit unit)
    {
        Runnable runnable = new Runnable() {
            public void run() {
                try {
                    invokeAction( taskAction, fromWho, msg);
                } catch (Exception e) {
                   putLog("task error:"+taskAction,LogLevel.ERROR);
                }
            };
        };
        ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor();
        service.scheduleWithFixedDelay(runnable, delay, period, unit);
        return service ;
    }
    protected ScheduledExecutorService putMsgWithFixedDelay(String toWho,TLMsg msg ,int period )
    {
        return putMsgWithFixedDelay(toWho,msg ,0, period ,TimeUnit.SECONDS);
    }
    protected ScheduledExecutorService putMsgWithFixedDelay(String toWho,TLMsg msg ,int delay ,int period ,TimeUnit unit)
    {
        Runnable runnable = new Runnable() {
            public void run() {
                try {
                   putMsg(toWho,msg);
                } catch (Exception e) {
                    putLog("task error:"+TLMsgUtils.msgToStr(msg),LogLevel.ERROR);
                }
            };
        };
        ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor();
        service.scheduleWithFixedDelay(runnable, delay, period, unit);
        return service ;
    }
    protected TLMsg runAction(Object fromWho, TLMsg msg) {
        String action = msg.getAction();
        if (action == null)
        {
            action = "";
            msg.setAction(action) ;
        }
        TLMsg returnMsg = null;
        Long startTime =System.currentTimeMillis();
        switch (action) {
            case MODULE_SLEEP:
                 msleep(fromWho, msg);
                break;
            case MODULE_GETRUNMODULES:
                returnMsg = getRunModules(fromWho, msg);
                break;
            case MODULE_GETMODULECLASS:
                returnMsg = getModuleClass(fromWho, msg);
                break;
            case MODULE_SETFACTORY:
                moduleFactory = (TLObjectFactory) msg.getParam("moduleFactory");
                break;
            case MODULE_GETMODULEBYMODULE:
                returnMsg = getModuleByModule(fromWho, msg);
                break;
            case MODULE_GETPARAM:
                returnMsg = getParam(fromWho, msg);
                break;
            case MODULE_SETFIELD:
                returnMsg =setField(fromWho, msg);
                break;
            case MODULE_SETPARAM:
                setParam(fromWho, msg);
                break;
            case MODULE_INITACTION:
                init();
                runInitMsg();
                break;
            case ADDMODULE:
                returnMsg = addModule(fromWho, msg);
                break;
            case ADDMODULECLASS:
                 addModuleClass(fromWho, msg);
                break;
            case RESETMODULE:
                returnMsg = resetModule(fromWho, msg);
                break;
            case GETMODULES:
                returnMsg = createMsg().setParam("modules", modules);
                break;
            case MODULE_ADDMSGTABLE:
                if (msgTable == null)
                    msgTable = new HashMap<>();
                if(!msg.isNull("msgTable"))
                    msgTable= (HashMap<String, HashMap<String, Object>>) msg.getParam("msgTable");
                else {
                    // 运行时动态加 msgTable 条目（适配新容器类型）
                    String msgId = msg.getStringParam("msgId", null);
                    if (msgId != null && !msgId.isEmpty() && !msg.isNull("msg")) {
                        HashMap<String, Object> entry = msgTable.get(msgId);
                        ArrayList<TLMsg> msglist = null;
                        if (entry != null)
                            msglist = (ArrayList<TLMsg>) entry.get("msglist");
                        if (msglist == null) {
                            if (entry == null) {
                                entry = new HashMap<>();
                                msgTable.put(msgId, entry);
                            }
                            msglist = new ArrayList<>();
                            entry.put("msglist", msglist);
                        }
                        if (msg.getParam("msg") instanceof TLMsg) {
                            msglist.add((TLMsg) msg.getParam("msg"));
                        } else if (msg.getParam("msg") instanceof ArrayList) {
                            msglist.addAll((ArrayList) msg.getParam("msg"));
                        }
                    }
                }
                break;
            case MODULE_ADDINITMSG:
                addInitMsg(msg);
                break;
            case MODULE_ADDBEFOREMSG:
                if (beforeMsgTable == null)
                    beforeMsgTable = new HashMap<>();
                addMsgTable(beforeMsgTable, "action", msg);
                putLog(name +" action:"+ action + "增加beforeMsg", LogLevel.DEBUG,"runAction");
                break;
            case MODULE_ADDAFTERMSG:
                if (afterMsgTable == null)
                    afterMsgTable = new HashMap<>();
                addMsgTable(afterMsgTable, "action", msg);
                putLog(name +" action:"+ action +" 增加afterMsg" , LogLevel.DEBUG,"runAction");
                break;
            case MODULE_RELOADCONFIG:
                returnMsg = reloadConfig(fromWho, msg);
                break;
            case MODULE_MSGTRANSFER:
                returnMsg = msgTransfer(fromWho, msg);
                break;
            case "runActionInThread":
                returnMsg = runActionInThread(fromWho, msg);
                break;
            case MODULE_DESTROY:
                returnMsg = destroy(fromWho, msg);
                break;
            case "setshutdown":
                returnMsg = setShutdown(fromWho, msg);
                break;
            default:
                returnMsg = checkMsgAction(fromWho, msg);
        }
        Long actionTime =System.currentTimeMillis()-startTime ;
        putLog(name +" action:"+ action +" 运行时间:"+ actionTime+"(ms)", LogLevel.TRACE,"runAction");
        return returnMsg;
    }

    private TLMsg runActionInThread(Object fromWho, TLMsg msg) {
        String action = (String) msg.getParam(MSG_P_ACTION);
        TLMsg dmsg = (TLMsg) msg.getParam("dmsg");
        return invokeAction(action, fromWho,dmsg);
    }

    private void msleep(Object fromWho, TLMsg msg) {
        Object  time =msg.getParam(MODULE_P_SLEEPTIME);
        if(time ==null)
            return;
        int ms;
        if(time instanceof  String)
            ms =Integer.parseInt((String) time);
        else if(time instanceof Number)
            ms =((Number) time).intValue();
        else
            return ;
        try {
            sleep(ms);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private TLMsg getModuleClass(Object fromWho, TLMsg msg) {
        return  createMsg().addMap(modulesClass);
    }

    protected TLMsg getRunModules(Object fromWho, TLMsg msg) {
        return  createMsg().addArgs(modules);
    }
    private TLMsg setField(Object fromWho, TLMsg msg) {
        String fieldName = (String) msg.getParam(FIELDNAME);
        Field field =getField(fieldName) ;
        if(field ==null)
            return null;
       Object fieldValue =  msg.getParam(FIELDVALUE);
        try {
            field.setAccessible(true);
            field.set(this,fieldValue);
            return createMsg().setParam(RESULT,true);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
            putLog("set field is error:"+ fieldName ,LogLevel.ERROR,"setField");
            return createMsg().setParam(RESULT,false);
        }
    }

    protected TLMsg reloadConfig(Object fromWho, TLMsg msg) {
        preReConfig();
        HashMap<String, String> cparams =moduleFactory.getModuleParam(name);
        params.putAll(cparams);
        configure();
        reload();
        reConfig();
        putLog(name + " 重新加载配置", LogLevel.DEBUG, "reloadConfig");
        return null;
    }

    protected void preReConfig() {
    }

    protected void reConfig() {
    }

    protected void reload() {
        if (params != null && params.get("reload") != null && (params.get("reload")).equals("true"))
            putMsg(moduleFactory, createMsg().setAction("reloadModule").setParam("moduleName", name));
    }

    protected TLMsg setParam(Object fromWho, TLMsg msg) {
        if(params==null)
            params =new HashMap<>();
        HashMap<String, Object> args = msg.getArgs();
        for (String key : args.keySet()) {
            params.put(key, (String) args.get(key));
        }
        setModuleParams();
        setSystemParams();
        return createMsg().setParam(RESULT,true);
    }

    private TLMsg getModuleByModule(Object fromWho, TLMsg msg) {
        String moduleName = (String) msg.getParam(FACTORY_P_MODULENAME);
        Object module = getModule(moduleName);
        return createMsg().setParam(FACTORY_R_MODULEINSTANCE, module).setParam(FACTORY_P_MODULENAME, moduleName);
    }

    protected TLMsg getParam(Object fromWho, TLMsg msg) {
        Object paramNames = msg.getParam(MODULE_PARAMS);
        if (paramNames != null)
        {
            TLMsg returnMsg = createMsg();
            String getParams[];
            if (paramNames instanceof String )
                getParams = ((String) paramNames).trim().split(";");
            else if (paramNames instanceof String[])
                getParams = (String[]) paramNames;
            else
                return returnMsg;
            if (getParams.length == 0)
                return returnMsg;
            for (int i = 0; i < getParams.length; i++) {
                String key =getParams[i].trim() ;
                if (params.containsKey(key))
                    returnMsg.setParam(key, params.get(key));
            }
            return returnMsg;
        } else
            return createMsg().addMap(params);
    }

    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        while (!shutdownable) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
        return createMsg().setParam("destroy", destroy);
    }

    /**
     * 模块准备关闭的信号。在 afterMsgTable 的最后调用，
     * 确保所有清理工作完成后才标记为可关闭。
     */
    public TLMsg setShutdown(Object fromWho, TLMsg msg) {
        shutdownable = "true".equals(msg.getStringParam("value", "true"));
        return createMsg();
    }

    protected TLMsg msgTransfer(Object fromWho, TLMsg msg) {
        TLMsg transMsg = (TLMsg) msg.getParam("domsg");
        String destination = transMsg.getDestination();
        putLog("trans msg:"+destination, LogLevel.TRACE, "msgTransfer");
        return putMsg(destination, transMsg);//消息目的非本类则直接转走
    }

    protected abstract TLMsg checkMsgAction(Object fromWho, TLMsg msg);

    protected TLMsg checkMsgId(String msgId, Object fromWho, TLMsg msg) {
        if (msgTable == null || msgTable.isEmpty())
            return null;
        HashMap<String, Object> entry = msgTable.get(msgId);
        if (entry == null)
            return null;
        ArrayList<TLMsg> msgList = (ArrayList<TLMsg>) entry.get("msglist");
        if (msgList == null || msgList.isEmpty())
            return null;
        String mode = (String) entry.getOrDefault("mode", "sequential");
        putLog("run msgid:"+msgId+" mode:"+mode, LogLevel.TRACE, "checkMsgId");
        if ("parallel".equals(mode)) {
            int waitTime = 120000;
            if (entry.containsKey("waitTime")) {
                try { waitTime = Integer.parseInt((String) entry.get("waitTime")); }
                catch (NumberFormatException ignored) {}
            }
            return doMsgListParallel(msgList, msg, waitTime);
        }
        return doMsgList(msgList, msg, null);
    }

    private TLMsg copyMsgFromMsgTable(TLMsg msg, TLMsg fromMsg) {
        String[] paramsKey = (String[]) fromMsg.getSystemParam(PARAMSFROMMSG,null);
        msg.copyParams(paramsKey,fromMsg);
        msg.setAction(fromMsg.getAction());
        msg.setMsgId(fromMsg.getMsgId());
        msg.setNextMsg(fromMsg.getNextMsg());
        msg.setDestination(fromMsg.getDestination());
        msg.setWaitFlag(fromMsg.getWaitFlag());
        msg.setSource(name) ;
        msg.addSystemArgs(fromMsg.getSystemArgs());
        return msg ;
    }

    protected void addInitMsg(TLMsg msg) {
        initMsgTable.add((TLMsg) msg.getParam("msg"));
    }

    protected void addMsgTable(HashMap<String, ArrayList<TLMsg>> tableVar, String tag, TLMsg msg) {
        String key =msg.getStringParam(tag,null);
        if (key == null || key.isEmpty())
            return;
        if(msg.isNull("msg")) return;
        if (msg.getParam("msg") instanceof TLMsg) {
            int position = -1;
            if (msg.getParam("position") != null && msg.getParam("position") instanceof Integer)
                position = (int) msg.getParam("position");
            addMsg(tableVar, key, (TLMsg) msg.getParam("msg"), position);
        } else if (msg.getParam("msg") instanceof ArrayList) {
            ArrayList msgs = (ArrayList) msg.getParam("msg");
            for (int i = 0; i < msgs.size(); i++) {
                addMsg(tableVar, key, (TLMsg) msgs.get(i), -1);
            }
        }
    }

    protected void addMsg(HashMap<String, ArrayList<TLMsg>> tableVar, String key, TLMsg msg, int position) {
        if (msg == null)
            return;
        if (tableVar == null)
            tableVar = new HashMap<>();
        ArrayList<TLMsg> msgList = tableVar.get(key);
        if (msgList == null) {
            msgList = new ArrayList<TLMsg>();
            if (position == -1)
                msgList.add(msg);
            else
                msgList.add(position, msg);
            tableVar.put(key, msgList);
        } else
            msgList.add(msg);
    }

    private void  addModuleClass(Object fromWho, TLMsg msg) {
        HashMap<String, HashMap<String, String>> addModulesClass =  msg.getArgs();
        if(addModulesClass ==null)
            return;
        if(modulesClass ==null)
            modulesClass =new HashMap<>();
        modulesClass.putAll(addModulesClass);
    }
    private TLMsg addModule(Object fromWho, TLMsg msg) {
        String moduleName = (String) msg.getParam(MODULENAME);
        Object module = msg.getParam(INSTANCE );
        if (!modules.containsKey(moduleName)) {
            modules.put(moduleName, module);
            putLog(name + "增加模块:" + moduleName, LogLevel.DEBUG);
            return createMsg().setParam(RESULT ,true);
        }
        return createMsg().setParam(RESULT ,false);
    }

    protected TLMsg resetModule(Object fromWho, TLMsg msg) {
        String moduleName = (String) msg.getParam(MODULENAME);
        Object module = msg.getParam(INSTANCE );
        if (modules.containsKey(moduleName)) {
            modules.put(moduleName, module);
            putLog(name + "重新设置模块:" + moduleName, LogLevel.DEBUG);
            return createMsg().setParam(RESULT ,true);
        }
        return createMsg().setParam(RESULT ,false);
    }

    public TLMsg putMsg( TLMsg msg)
    {
        String   destination =msg.getDestination();
        if(destination !=null)
           return putMsg(destination,msg);
        IObject toWho = (IObject) msg.getAndRemoveSystemParam(MSG_P_TOWHO);
        if(toWho !=null)
            return putMsg(toWho,msg);
        return putMsg(this,msg);
    }

    public TLMsg runMsgChain (TLMsg ... msgs )
    {
        TLMsg msg0 =TLMsgUtils.makeMsgChain( msgs);
        return putMsg(msg0);
    }

    public TLMsg putMsg(String moduleName, TLMsg msg) {
        IObject module ;
        if((boolean)(msg.getSystemParam(IFLOADMODULE,true)) ==true)
            module = (IObject) getModule(moduleName);
        else
        {
            msg.removeSystemParam(IFLOADMODULE) ;
            module = (IObject) getExistintModule(moduleName);
        }
        if (module != null)
        {
            msg.setNowObject(moduleName);
            return putMsg(module, msg);
        }
        else {
            if((boolean)(msg.getSystemParam(IGNOREMODULEISNULL,false)) ==true)
                return null ;
            putLog("module is not exist " + moduleName, LogLevel.ERROR);
            moduleFactory.shutdown(-1);
            return null ;
        }
    }

    public TLMsg putMsg(String msgStr ,Map<String,Object>param){
        TLMsg msg =TLMsgUtils.strToMsg(msgStr);
        if(msg ==null)
        {
            putLog("msgStr is error : "+msgStr,LogLevel.ERROR );
            return null;
        }
        if(param !=null)
            msg.addMap(param);
        return putMsg(msg);
    }

    public TLMsg putMsg(String msgStr ,Map<String,Object>param,Map<String,Object>sysParam){
        TLMsg msg =TLMsgUtils.strToMsg(msgStr);
        if(msg ==null)
        {
            putLog("msgStr is error : "+msgStr,LogLevel.ERROR );
            return null;
        }
        if(param !=null)
            msg.addMap(param);
        if(sysParam!=null)
            msg.addSystemArgs(sysParam) ;
        return putMsg(msg);
    }
    /**
     *
     * @param moduleName
     * @param msg
     * @param waitTime  -1 不等候 0 永远等候  大于0则等候 waitTime时间 ，单位为毫秒
     * @return
     */
    public TLMsg putMsgInThreadAndWait(String moduleName, TLMsg msg,int waitTime) {
            if(waitTime <0)
                return putMsg(moduleName,msg);
            else
            {
                msg.setWaitFlag(false);
                msg.setSystemParam(TASKWAITTIME,waitTime);
                return putMsg(moduleName,msg);
            }
    }

    public TLMsg putMsgInThreadAndWaitReturn(String moduleName, TLMsg msg) {
        IObject module = (IObject) getModule(moduleName);
         return  putMsgInThreadAndWaitReturn(module,msg) ;
    }

    public TLMsg putMsgInThreadAndWaitReturn(IObject module, TLMsg msg) {
           msg.setWaitFlag(false)
              .setSystemParam(TASKWAITTIME,0)   ;
           return putMsg(module,msg);
    }

    public   TLMsg putMsgGroupByThread(List<TLMsg> msgList, int waitTime){
        int msgNumber =msgList.size() ;
        ArrayList<ThreadTask> threadTaskList = new ArrayList<>(msgNumber) ;
        ArrayList<TLMsg> resultMsgList =new ArrayList<>(msgNumber) ;
        CountDownLatch latch = new CountDownLatch(msgNumber);
        for(int j=0 ; j < msgList.size() ; j++)
        {
            TLMsg msg = msgList.get(j);
            msg.setWaitFlag(false);
            TLMsg returnMsg= putMsg(msg);
            if(returnMsg !=null && !returnMsg.isNull(THREADPOOL_TASK))
            {
                ThreadTask threadTask = (ThreadTask) returnMsg.getParam(THREADPOOL_TASK);
                threadTask.setDoneSignal(latch);
                threadTaskList.add(j,threadTask);
            }
            else {
                threadTaskList.add(j,null);
                latch.countDown();  // 无法获取 ThreadTask 的任务直接视为完成
            }
            resultMsgList.add(j,null);
        }
        try {
            boolean completed = latch.await(waitTime, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 收集所有已完成任务的结果
        for (int i = 0; i < msgNumber; i++) {
            ThreadTask task = threadTaskList.get(i);
            if (task != null && task.isThreadOver()) {
                resultMsgList.set(i, task.getResult());
            }
        }
        return createMsg().setParam(RESULT,resultMsgList);
    }

    /**
     * 异步发送单条消息并等待返回结果
     * @param msg 要发送的消息
     * @param waitTime 等待超时时间（毫秒）
     * @return 消息返回结果
     */
    public TLMsg putMsgByThread(TLMsg msg, int waitTime) {
        msg.setWaitFlag(false);
        CountDownLatch latch = new CountDownLatch(1);
        TLMsg returnMsg = putMsg(msg);
        ThreadTask threadTask = null;
        if (returnMsg != null && !returnMsg.isNull(THREADPOOL_TASK)) {
            threadTask = (ThreadTask) returnMsg.getParam(THREADPOOL_TASK);
            threadTask.setDoneSignal(latch);
        } else {
            latch.countDown();
        }
        try {
            latch.await(waitTime, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (threadTask != null && threadTask.isThreadOver())
            return threadTask.getResult();
        return createMsg().setParam(TASKRESULTTIMEOUT, true);
    }

    public   ArrayList putMsgGroupByThread(List<TLMsg> msgList, int waitTime , String resultParam)
    {
        TLMsg returnMsg = putMsgGroupByThread(msgList, waitTime);   // 多表并行查询
        ArrayList resultList = new ArrayList<>();
        if (returnMsg != null)
        {
            List<TLMsg> dbResultList = (List<TLMsg>) returnMsg.getParam(RESULT, List.class);
            if (dbResultList != null)
            {
                for (TLMsg rmsg : dbResultList)
                {
                    Object dbresult = rmsg.getParam(resultParam);
                    if(dbresult !=null)
                      resultList.add(dbresult);

                }
                return resultList ;
            }
            else
                return null ;
        }
        else
            return null ;
    }

    /**
     * 异步put
     ****/
    protected void putMsgInThreadResultFor(IObject toWho, TLMsg msg ,Object sessionData) {
        msg.setSystemParam(TASKRESESSIONDATA,sessionData);
        msg.setSystemParam(TASKRESULTFOR,this);
        if(msg.getSystemParam(TASKRESULTACTION,null) ==null)
            msg.setSystemParam(TASKRESULTACTION,"threadReturn");
        msg.setWaitFlag(false);
        putMsg(toWho,msg);
    }
    @Override
    public TLMsg putMsgNoWait(IObject toWho, TLMsg msg) {
        Object taskResultFor = msg.getSystemParam(TASKRESULTFOR);
        if (taskResultFor != null) {
            if (taskResultFor instanceof String) {
                taskResultFor = getModule((String) taskResultFor);
                msg.setSystemParam(TASKRESULTFOR, taskResultFor);
            }
        }
        if (TLDataUtils.parseBoolean(msg.getSystemParam(INTHREADPOOL),false)==false) {
            msg.removeSystemParam(INTHREADPOOL);
            return super.putMsgNoWait(toWho, msg);
        }
        msg.removeSystemParam(INTHREADPOOL);
        if (!msg.systemParamIsNull(TASKWAITTIME))
            msg.setSystemParam(TASKMAINTHREAD,Thread.currentThread());
        TLMsg tmsg = createMsg().setAction(THREADPOOL_EXECUTE).setParam(THREADPOOL_P_TASKMSG, msg).setParam(THREADPOOL_P_TASKMODULE, toWho);
        Object threadPool = msg.getAndRemoveSystemParam(THREADPOOLNAME);
        if (threadPool == null)
          return   putMsg(DEFAULTTHREADPOOL, tmsg);
        if(threadPool instanceof  String)
             return   putMsg((String)threadPool, tmsg);
        if(threadPool instanceof  TLBaseModule)
            return   putMsg(( TLBaseModule)threadPool, tmsg);
        else
            return   putMsg(DEFAULTTHREADPOOL, tmsg);
    }

    protected Object getResultObject(TLMsg msg) {
        Object resultFor = msg.getSystemParam(RESULTFOR);
        if (resultFor == null)
            return null;
        if (resultFor instanceof String && !((String) resultFor).isEmpty())
            return getModule((String) resultFor);
        else
            return resultFor;
    }

    public TLMsg putMsg(String moduleName, String action) {
        return putMsg(moduleName, createMsg().setAction(action));
    }
    public Object putMsgAndGetResult(String moduleName, TLMsg msg ){
            return putMsgAndGetResult( moduleName, msg ,RESULT );
    }

    public Object putMsgAndGetResult(String moduleName, TLMsg msg ,String resultName ){
        TLMsg returnMsg =putMsg(moduleName,msg);
        if (returnMsg == null)
            return null ;
        return returnMsg.getParam(resultName);
    }

    public Object putMsgAndGetResult(String moduleName, TLMsg msg ,String resultName ,Class classType){
        TLMsg returnMsg =putMsg(moduleName,msg);
        if (returnMsg == null)
            return null ;
        return returnMsg.getParam(resultName,classType);
    }

    public void putLog(String content, LogLevel logLevel) {
        putLog(content, logLevel, "default");
    }

    public void putLog(Exception e, LogLevel logLevel, String tag) {
        StringBuilder sb = new StringBuilder();
        if (e != null) {
            for (StackTraceElement element : e.getStackTrace()) {
                sb.append("\r\n\t").append(element);
            }
        }
        putLog(sb.toString(), logLevel, tag);
    }

    public void putLog(TLMsg msg, LogLevel logLevel, String tag) {
        if (checkIfLog( logLevel, tag) == false)
            return;
        putContentLog(TLMsgUtils.msgToStr(msg), logLevel,  tag, true ,null, name);
    }

    public void putLog(String content, LogLevel logLevel, String tag) {
        putLog( content, logLevel,tag,true ,null,name);
    }

    public void putLog(String content, LogLevel logLevel, String tag,Boolean ifWait ,String threadPool,String module) {
        if (checkIfLog( logLevel, tag) == false)
            return;
        putContentLog( content, logLevel,  tag, ifWait ,threadPool, module);
    }

    public void putContentLog(String content, LogLevel logLevel, String tag,Boolean ifWait ,String threadPool,String module) {
        TLMsg msg =createMsg()
                .setParam(LOG_P_LOGMODULE, module)
                .setParam("thread",Thread.currentThread().getName())
                .setParam(LOG_P_LOGLEVEL, logLevel)
                .setParam(LOG_P_LOGTAG, tag)
                .setParam(LOG_P_LOGONTENT, content);
        if(logWait ==false || ifWait ==false ||threadPool !=null)
        {
            msg.setAction("log");
            msg.setWaitFlag(false);
            msg.setSystemParam(INTHREADPOOL,true) ;
            if(threadPool !=null)
                msg.setSystemParam(THREADPOOLNAME,threadPool);
            else
                msg.setSystemParam(THREADPOOLNAME, DEFAULTLOGTTHREADPOOL);
        }
        else
            msg.setAction(LOG_PUTLOG);
        putMsg(DEFAULTLOG, msg);
    }

    private boolean checkIfLog( LogLevel logLevel,String tag){
        if (ifLog == false)
            return false;
        if (logLevel.compareTo(defaultLoglevel) < 0)
            return false;
        if (tag != null && !tag.isEmpty()) {
            if (nologTags != null && nologTags.contains(tag))
                return false;
            else if (logTags != null && !logTags.contains(tag))
                return false;
        }
        return  true ;
    }

    //获取单例模块。本地没有则从工厂中获取，模块名为名称 ,创建模块
    protected Object getModule(String moduleName) {
         return getModule( moduleName,moduleName,true,true,null);
    }
    //只创建模块实例，不运行。不存储在工厂和本地
    protected Object getObject(String moduleName) {
        TLMsg msg = createMsg().setAction(FACTORY_GETMODULE)
                .setParam(MODULE_IFONLYCREATE, true)
                .setParam(FACTORY_P_MODULENAME, moduleName)
                .setParam(FACTORY_P_NEWMODULENAME, moduleName);
        TLMsg returnMsg = putMsg(moduleFactory, msg);
        Object module = returnMsg.getParam(FACTORY_R_MODULEINSTANCE);
        return module;
    }
    protected Object getModule(String moduleName,HashMap<String, String> paramsInMsg) {
        return getModule( moduleName,moduleName,true,true,paramsInMsg);
    }
    //创建非单例模块 ,不工厂注册
    protected Object getMyModule(String moduleName) {
        return getModule( moduleName,moduleName,false,true,null);
    }

    protected Object getMyModule(String moduleName ,HashMap<String, String> paramsInMsg) {
        return getModule( moduleName,moduleName,false,true,paramsInMsg);
    }
    //从工厂获取模块，创建模块,
    protected Object getModuleFromFactory(String moduleName) {
        return getModule( moduleName,moduleName,true,false,null);
    }
    //创建模块，不工厂注册,不保存在当地
    protected Object getNewModule( String moduleName) {
        return getModule( moduleName,moduleName,false,false,null);
    }

    protected Object getNewModule( String moduleName,HashMap<String, String> paramsInMsg) {
        return getModule( moduleName,moduleName,false,false,paramsInMsg);
    }
    //创建新模块，不工厂注册,不保存在当地
    protected Object getNewModule(String newModuleName, String moduleName) {
        return getModule( newModuleName,moduleName,false,false,null);
    }

    protected Object getNewModule(String newModuleName, String moduleName,HashMap<String, String> paramsInMsg) {
        return getModule( newModuleName,moduleName,false,false,paramsInMsg);
    }
    //同一个类创建新模块。从本地获取模块，如果没有则从工厂中获取
    protected Object getModule(String newModuleName, String moduleName) {
        return getModule( newModuleName,moduleName,true,true,null);
    }

    protected Object getModule(String newModuleName, String moduleName ,HashMap<String, String> paramsInMsg) {
        return getModule( newModuleName,moduleName,true,true,paramsInMsg);
    }
    //根据类创建模块 ,不在工厂、不在本地保存
    protected Object getModuleByClass(String moduleName, String classFile) {
        return getModule(  moduleName,classFile,false,false,null);
    }

    protected Object getModule(String newModuleName ,String oldModuleName,Boolean ifSingleton ,Boolean ifSaveModule ,HashMap<String, String> paramsInMsg) {
        if (ifSaveModule ==true && modules.get(newModuleName) != null)
            return modules.get(newModuleName);
        else {
            TLMsg msg = createMsg()
                    .setAction(FACTORY_GETMODULE)
                    .setParam(MODULE_SINGLETON, ifSingleton)
                    .setParam(FACTORY_P_MODULENAME, oldModuleName)
                    .setParam(FACTORY_P_NEWMODULENAME, newModuleName);
            if (modulesClass != null) {
                HashMap<String, String> moduleConfig = modulesClass.get(newModuleName);
                if(moduleConfig !=null && !moduleConfig.isEmpty())
                    msg.setParam(FACTORY_P_MODULECONFIG, moduleConfig);
            }
            HashMap<String, String> moduleParam =null;
            if (modulesParams != null) {
                HashMap<String, String> stored = modulesParams.get(newModuleName);
                if (stored != null) {
                    moduleParam = new HashMap<>(stored);
                }
            }
            if(paramsInMsg !=null){
                if(moduleParam ==null)
                    moduleParam =new HashMap<>(paramsInMsg) ;
                else
                    moduleParam.putAll(paramsInMsg);
            }
            if(moduleParam !=null && !moduleParam.isEmpty()) {
                msg.setParam(MODULE_PARAMS, moduleParam);
            }
            TLMsg returnMsg = putMsg(moduleFactory, msg);
            Object module = returnMsg.getParam(FACTORY_R_MODULEINSTANCE);
            if (ifSaveModule ==true && module != null)
                modules.put(newModuleName, module);
            return module;
        }
    }
    //获取现成的模块，不创建模块
    protected Object getExistintModule(String moduleName) {
        if (modules.get(moduleName) != null)
            return modules.get(moduleName);
        else
        {
            Object module =getModuleInFactory(moduleName);
            if (module != null)
                modules.put(moduleName, module);
            return module;
        }
    }
    //从工厂已经存在的模块中获取模块，不创建模块
    protected Object getModuleInFactory(String moduleName) {
        TLMsg returnMsg = putMsg(moduleFactory, createMsg()
                .setAction("getModuleInFactory").setParam(FACTORY_P_MODULENAME, moduleName));
        return returnMsg.getParam(FACTORY_R_MODULEINSTANCE);    }

    protected void registInfactory(TLObjectFactory factory) {
        putMsg(factory, createMsg().setAction(FACTORY_REGISTINFACTORY)
                .setParam(FACTORY_P_MODULENAME, name).setParam(INSTANCE, this));    }

    protected void registInfactory(String moduleName, Object instance) {
        putMsg(moduleFactory, createMsg().setAction(FACTORY_REGISTINFACTORY)
                .setParam(FACTORY_P_MODULENAME, moduleName).setParam(INSTANCE, instance));
    }

    protected void println(String str){
        System.out.println(str);
    }
}


