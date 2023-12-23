package cn.tianlong.tlobject.base;

import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLToolsUtils;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 创建日期：2018/4/5 on 8:47
 * 描述:
 * 作者:tianlong
 */
/**
 *  消息对象工厂类，负责实例化每个模块实例。
 *
 */
public class TLObjectFactory extends TLBaseModule {

    protected String configDir = "";
    protected String factoryConfigFile = "";
    protected String packageName;
    protected Boolean ifModuleMonitor = false;
    protected IObject moduleMonitor;
    protected boolean defaultUseParentFactory =true ;
    protected HashMap<String, String> commonParams;         // 用于所有模块的公共参数
    protected ArrayList<TLMsg> factoryBoot;          //工厂启动msg 序列
    protected Map<String, TLBaseModule> factorys = new ConcurrentHashMap<>();
    protected long startTime = System.nanoTime();
    protected TLBaseModule  parentFactory ;
    protected  String  classPath ;
    public TLObjectFactory(String name, String factoryConfigFile) {
        super(name, factoryConfigFile);
    }

    public TLObjectFactory(String name, String factoryConfigFile, String configDir) {
        super(name, factoryConfigFile);
        this.configDir = configDir;
    }
    public TLObjectFactory(String name, String factoryConfigFile, String configDir,String classPath) {
        this(name, factoryConfigFile,configDir);
        this.classPath =classPath;
    }
    public TLObjectFactory(String name, String factoryConfigFile, String configDir,TLBaseModule  parentFactory) {
        this(name, factoryConfigFile,configDir);
        this.parentFactory =parentFactory;
        factorys.put(parentFactory.getName(),parentFactory);
    }
    public void startFactory(String startConfigFile, HashMap<String, String> startParams) {
        moduleFactory = this;
        ifMonitor = false;
        if (configDir != null && !configDir.isEmpty())
        {
            String lastCharOfPath = configDir.substring(configDir.length() - 1);
            if (!lastCharOfPath.equals(File.separator) && ! lastCharOfPath.equals("\\")&& ! lastCharOfPath.equals("/"))
                configDir = configDir + File.separator;
        } else
            configDir =""+ File.separator;
        start(startConfigFile,  startParams);
    }
    public TLBaseModule start(String configFile, HashMap<String, String> startParams) {
         if( classPath ==null)
              classPath = ClassLoader.getSystemResource("").getPath();
        return super.start( configFile,  startParams);
    }
    public static String getSysClassPath () {
        return ClassLoader.getSystemResource("").getPath();
    }

    public void addConfig( String addConfigFile, String addConfigDir){
        if(addConfigDir ==null)
            addConfigDir =configDir ;
        addConfigFile =addConfigDir+"/"+addConfigFile;
        myConfig config = new myConfig(addConfigFile, addConfigDir);
        config.init();
        HashMap<String, HashMap<String, String>> addmodulesClass = config.getModulesClass();
        if(addmodulesClass !=null)
            modulesClass.putAll(addmodulesClass);
        HashMap<String, HashMap<String, String>>  addmodulesParams =config.getModulesParams();
        if(addmodulesParams !=null)
            modulesParams.putAll(addmodulesParams);
        HashMap<String, String> addparams = mconfig.getParams();
        if(addparams !=null)
            params.putAll(addparams);
      }
    @Override
    protected Object setConfig() {
        if (configFile == null)
            configFile = configDir + File.separator + name + "_config.xml";
        myConfig config = new myConfig(configFile, configDir);
        config.setFactory(this);
        config.init();
        mconfig = config;
        return config;
    }

    @Override
    protected void initProperty() {
        factoryBoot = ((myConfig) mconfig).getFactoryBoot();
        commonParams = ((myConfig) mconfig).getCommonParams();
        super.initProperty();
        if (params != null) {
            if (params.get("package") != null)
                packageName = params.get("package");
            if (params.get("ifModuleMonitor") !=null)
                ifModuleMonitor = Boolean.parseBoolean(params.get("ifModuleMonitor"));
            if (params.get("defaultUseParentFactory") !=null)
                defaultUseParentFactory = Boolean.parseBoolean(params.get("defaultUseParentFactory"));
        }
        modules.put(name, this);
        if (factoryBoot != null && !factoryBoot.isEmpty())
            setMsgTableUseType(factoryBoot, MSGTABLEONLY);
        /**
        if(commonParams !=null && !commonParams.isEmpty())
        {
            if(params ==null)
                params =new HashMap<>();
            params.putAll(commonParams);
        }
         **/
    }

    @Override
    protected TLBaseModule init() {
        if (ifModuleMonitor)
            moduleMonitor = (IObject) getModule(DEFAULTMODULEMONITOR);
        return this;
    }

    @Override
    public void putLog(String content, LogLevel logLevel, String tag) {
        if (tag.equals("getMsg"))
            return;
        super.putLog(content, logLevel, tag);
    }

    public void  setParentFactory ( TLBaseModule module){
        parentFactory =module ;
        factorys.put(parentFactory.getName(),module);
    }
    public Long getRunTime(Boolean nanoTimeflag) {
        if (nanoTimeflag)
            return System.nanoTime() - startTime;
        else
            return (System.nanoTime() - startTime) / 1000000L;
    }
    public Map<String, TLBaseModule> getFactorys(){
        return factorys;
    }
    public TLBaseModule getFactory(String factoryName){
        return factorys.get(factoryName);
    }
    public void addFactory(String factoryName ,TLBaseModule factory){
        factorys.put(factoryName,factory);
    }
    public TLBaseModule getFactoryOfModule(String moduleName){
        String factory =getFactoryName(moduleName);
        if(factory ==null)
            return null ;
        return factorys.get(factory);
    }
    public static TLObjectFactory getInstance(String uconfigDir, String uconfigFile) {
      return getInstance(MODULEFACTORY , uconfigDir, uconfigFile);
    }
    public static TLObjectFactory getInstance(String factoryName ,String uconfigDir, String uconfigFile) {
         return getInstance(factoryName,uconfigDir, uconfigFile, null);
    }
    public static TLObjectFactory getInstance(String factoryName ,String configDir, String configFile,TLBaseModule  parentFactory) {
        if(factoryName ==null)
            factoryName =MODULEFACTORY;
        if(configDir ==null)
            configDir="";
        if (configFile != null && !configDir.isEmpty())
        {
            String lastCharOfPath = configDir.substring(configDir.length() - 1);
            if (lastCharOfPath.equals(File.separator) || lastCharOfPath.equals("\\") || lastCharOfPath.equals("/"))
                configFile =configDir + configFile;
            else
                configFile = configDir + File.separator + configFile;
        } else
            configFile = configDir +  File.separator + factoryName + "_config.xml";

        if(parentFactory ==null)
            return new TLObjectFactory(factoryName, configFile, configDir);
        else
           return new TLObjectFactory(factoryName, configFile, configDir,parentFactory);
    }
    public String getConfigDir() {
        return configDir;
    }
    public String getClassPath(){
        return this.classPath ;
    }
    public void setClassPath(String classPath){
        this.classPath =classPath ;
    }
    public void setConfigDir(String configDir) {
        this.configDir = configDir;
    }
    public void setfactoryConfigFile(String factoryConfigFile) {

        this.factoryConfigFile = factoryConfigFile;
    }
    public String getParam(String paramName){
        return params.get(paramName);
    }
    public void boot() {
        if (factoryBoot == null)
            return;
        doMsgList(factoryBoot, null, null);
        putLog("factory:"+name +" boot over",LogLevel.DEBUG,"boot");
    }

    public void destroyModule() {
        Object module;
        TLMsg msg = createMsg().setAction(MODULE_DESTROY);
        for (String key : modules.keySet()) {
            if (key.equals(DEFAULTLOGTTHREADPOOL) || key.equals(DEFAULTLOG))
                continue;
            module = modules.get(key);
            if (module instanceof IObject) {
                putMsg((IObject) module, msg);
                putLog(((IObject) module).getName() + " is destoryed", LogLevel.DEBUG);
            }
        }
    }

    public void shutdown() {
        System.out.println("start shutdown...");
        putLog("start shutdown...",LogLevel.DEBUG,"shutdown");
        destroyModule();
        System.out.println("game is over,bye !");
        System.exit(0);
    }

    public void shutdown(int status) {
        putLog("app shutdown... " , LogLevel.INFO);
        destroyModule();
        System.exit(status);
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case FACTORY_ADDFACTORY:
                returnMsg = addFactory(fromWho, msg);
                break;
            case FACTORY_GETMODULEPARAM:
                returnMsg = getModuleParam(fromWho, msg);
                break;
            case FACTORY_GETMODULE:
                returnMsg = getModule(fromWho, msg);
                break;
            case FACTORY_RELOADMODULE:
                reloadModule(fromWho, msg);
                break;
            case "getModuleInFactory":
                returnMsg = getModuleInFactory(fromWho, msg);
                break;
            case FACTORY_REGISTINFACTORY:
                returnMsg = registInFactory(fromWho, msg);
                break;
            case FACTORY_REMOVEFROMFACTORY:
                if (modules != null)
                    modules.remove(msg.getParam(FACTORY_P_MODULENAME));
                break;
            case FACTORY_GETRUNMODULES:
                returnMsg = getRunModuesInFactory(fromWho, msg);
                break;
            case EXCEPTIONHANDLER_HANDLER:
                returnMsg = exceptionHandler(fromWho, msg);
                break;
            default:

        }
        return returnMsg;
    }

    private TLMsg getRunModuesInFactory(Object fromWho, TLMsg msg) {
        ArrayList<Map<String, Object>> moduleList =  new ArrayList<>();
        for(String key :modules.keySet()){
            HashMap<String ,Object> map =new HashMap<>();
            map.put("name",key+ "@"+name);
            map.put("factory",name);
            moduleList.add(map);
        }
        Map<String,TLBaseModule> factorys = getFactorys();
        if(!factorys.isEmpty()){
            for(String factoryName :factorys.keySet()){
                TLBaseModule sonfactory =factorys.get(factoryName);
                if(sonfactory ==this)
                    continue;
                ArrayList<Map<String, Object>> smoduleList =  getRunModulesInFactory(sonfactory);
                moduleList.addAll(smoduleList);
            }
        }
        return createMsg().setParam(RESULT,moduleList) ;
    }
    private   ArrayList<Map<String, Object>> getRunModulesInFactory(TLBaseModule factory){
        ArrayList<Map<String, Object>> moduleList =  new ArrayList<>();
        String factoryName =factory.getName();
        String  factorystr ="@"+factoryName;
        TLMsg returnMsg=putMsg( factory,createMsg().setAction(MODULE_GETRUNMODULES));
        Map<String, Object> runModule =returnMsg.getArgs();
        if(runModule ==null)
            return moduleList ;
        for(String key : runModule.keySet()){
            HashMap<String ,Object> map =new HashMap<>();
            map.put("name",key+ factorystr);
            map.put("factory",factoryName);
            moduleList.add(map);
        }
        return moduleList ;
    }
    private TLMsg getModuleParam(Object fromWho, TLMsg msg) {
        String moduleName = (String) msg.getParam(MODULENAME);
        HashMap<String, String> moduleParamMap =getModuleParam( moduleName);
        return createMsg().setArgs(moduleParamMap);
    }

    public TLObjectFactory addFactory(String factoryName ,String configdir, String configfile) {
        TLObjectFactory factory =getInstance(factoryName , configdir,  configfile,this);
        if(factory ==null)
            return null;
        factory.setClassPath(classPath);
        factory.startFactory(null,null);
        factorys.put(factoryName,factory) ;
        factory.boot();
        return factory ;
    }
    private TLMsg addFactory(Object fromWho, TLMsg msg) {
        String  moduleName = (String) msg.getParam(MODULENAME);
        String fconfigdir  ;
        String fconfigfile  ;
        if(!msg.isNull(MODULE_CONFIGDIR))
        {
            fconfigdir = (String) msg.getParam(MODULE_CONFIGDIR);
            fconfigfile = (String) msg.getParam(MODULE_CONFIGFILE);
        }
        else {
            HashMap<String, String> moduleConfig = modulesClass.get(moduleName);
            if (moduleConfig == null)
               return null ;
            fconfigdir =moduleConfig.get(MODULE_CONFIGDIR);
            fconfigfile =moduleConfig.get(MODULE_CONFIGFILE);
        }
        String firstCha = (String)  fconfigdir.subSequence(0, 1);
        if (!firstCha.equals("/"))
            fconfigdir =  configDir + fconfigdir;
        TLObjectFactory factory =getInstance(moduleName , fconfigdir,  fconfigfile,this);
        if(factory ==null)
            return null;
        factory.setClassPath(classPath);
        factory.startFactory(null,null);
        factorys.put(moduleName,factory) ;
        factory.boot();
        return createMsg().setParam(FACTORY_R_MODULEINSTANCE,factory).setParam(FACTORY_P_MODULENAME, moduleName);
    }

    /**
     * 处理模块异常情况
     * @param fromWho
     * @param msg
     * @return
     */
    private TLMsg exceptionHandler(Object fromWho, TLMsg msg) {
        if(ifExceptionHandle==false)
            return msg ;
        if (exceptionHandler != null)
            return putMsg(exceptionHandler, msg);
        else
            return null;

    }

    private TLMsg registInFactory(Object fromWho, TLMsg msg) {
        String moduleName = (String) msg.getParam(FACTORY_P_MODULENAME);
        modules.put(moduleName, msg.getParam(INSTANCE));
        return null;
    }

    private void reloadModule(Object fromWho, TLMsg msg) {
        String moduleName = (String) msg.getParam(MODULENAME);
        if (moduleName == null)
            moduleName = ((IObject) fromWho).getName();
        String factory =getFactoryName(moduleName);
        if(factory !=null && !factory.equals(name) ){
            TLBaseModule factoryObj =factorys.get(factory);
            if(factoryObj !=null)
            {
                putMsg(factoryObj,msg);
                return;
            }
        }
        msg.setParam("singleton", "false");
        TLMsg returnMsg = getModule(fromWho, msg);
        Object module = returnMsg.getParam(FACTORY_R_MODULEINSTANCE);
        moduleName =getModuleName(moduleName);
        if (module != null) {
            modules.put(moduleName, module);
            TLMsg resetmsg = createMsg().setAction(RESETMODULE).setParam(MODULENAME, moduleName)
                    .setParam(INSTANCE, module);
            for (Map.Entry<String, Object> entry : modules.entrySet()) {
                if (entry.getKey().equals(name) || entry.getKey().equals(SESSIONDATAMODULE))
                    continue;
                Object moduleObj = entry.getValue();
                if (moduleObj instanceof TLBaseModule)
                    putMsg((IObject) moduleObj, resetmsg);
            }
            putLog(moduleName + "  模块重新加载", LogLevel.WARN);
        }
    }

    private TLMsg getModuleInFactory(Object fromWho, TLMsg msg) {
        String moduleName = (String) msg.getParam(FACTORY_P_MODULENAME);
        Object module = modules.get(moduleName);
        if(module ==null && defaultUseParentFactory ==true && parentFactory !=null)
            return putMsg(parentFactory,msg);
        else
            return createMsg().setParam(FACTORY_R_MODULEINSTANCE, module);
    }
    @Override
    protected   boolean ifToMySelf(String destination ){
        if(destination !=null && destination.equals(MODULEFACTORY))
            return true ;
        return super.ifToMySelf(destination);
    }
    /**
    public TLMsg getMsg(Object fromWho, TLMsg msg){
        String destination =msg.getDestination();
        if(destination !=null && destination.equals(MODULEFACTORY))
        {
            msg.setDestination(null);
            return   super.getMsg(fromWho,msg);
        }
        else
            return   super.getMsg(fromWho,msg);
    }
     **/
    @Override
    public Object getModule(String moduleName) {
        if (modules.get(moduleName) != null)
            return modules.get(moduleName);
        else {
            TLMsg msg = createMsg().setAction(FACTORY_GETMODULE).setParam(FACTORY_P_MODULENAME, moduleName);
            TLMsg returnMsg = getModule(moduleFactory, msg);
            Object module = returnMsg.getParam(FACTORY_R_MODULEINSTANCE);
            return module;
        }
    }
    private String getModuleName(String moduleName){
        if(moduleName==null || moduleName.isEmpty())
            return moduleName ;
        int position =moduleName.indexOf("@");
        if(position == -1)
            return moduleName ;
        return moduleName.substring(0,position);
    }
    private String getFactoryName(String moduleName){
        String factory=null;
        int position =moduleName.indexOf("@");
        if(position !=-1 )
           factory =moduleName.substring(position+1);
        return factory ;
    }
    public  String getConfigRealPath_old(String uconfigFile,String defaultConfigPath){
        String realPath = null;
        if(uconfigFile.length() >9 && uconfigFile.substring(0, 9).equals(CLASSPATH))
        {

            String  cpath =uconfigFile.substring(9);
            java.net.URL cfile = this.getClass().getResource(cpath);
            if (cfile == null)
            {
                System.out.println("no config path:"+uconfigFile);
                return null ;
            }
            realPath =cfile.getPath();
            System.out.println("cfilePath:"+realPath);
        }
        else
        {
            if ( !uconfigFile.startsWith("/") && uconfigFile.indexOf(":") < 0 && defaultConfigPath !=null)
                realPath = defaultConfigPath + uconfigFile;
        }
        System.out.println("path:"+realPath);
        return realPath ;
    }
    public  String getConfigRealPath(String configFile){
        String realPath ;
        if(configFile.length() >9 && configFile.substring(0, 9).equals(CLASSPATH))
            realPath =classPath+configFile.substring(9) ;
        else
        {
            if ( !configFile.startsWith(File.separator) && configFile.indexOf(":") < 0 )
                realPath = configDir + configFile;
            else
                return  configFile;
        }
        System.out.println("path:"+realPath);
        return realPath ;
    }
    public  static String getConfigRealPath(String configFile ,String classPath ,String configDir){
        String realPath ;
        if(configFile.length() >9 && configFile.substring(0, 9).equals(CLASSPATH))
            realPath =classPath+configFile.substring(9) ;
        else
        {
            if ( !configFile.startsWith(File.separator) && configFile.indexOf(":") < 0 )
                realPath = configDir + configFile;
            else
                return  configFile;
        }
        System.out.println("path:"+realPath);
        return realPath ;
    }
    private TLMsg getModuleFromOtherFactory(String factoryName , TLMsg msg){
        if(factoryName.equals(FACTORY_P_PARENTFACTORY) && parentFactory !=null)
            return  putMsg(parentFactory,msg);
        TLBaseModule  factory = factorys.get(factoryName);
        if(factory != null)
           return  putMsg(factory,msg);
        else
           return createMsg().setParam(FACTORY_R_MODULEINSTANCE, null);
    }
    protected TLMsg getModule(Object fromWho, TLMsg msg) {
        String moduleName =  msg.getStringParam(FACTORY_P_MODULENAME,null);
        String newModuleName = msg.getStringParam(FACTORY_P_NEWMODULENAME,null);
        if (newModuleName == null)
            newModuleName = moduleName;
        int position =moduleName.indexOf("@");
        if(position !=-1 )
        {
            String factory =moduleName.substring(position+1);   //其他工厂的模块
            moduleName= moduleName.substring(0,position);
            newModuleName = getModuleName(newModuleName);
            if(!factory.equals(name) && !factory.equals(MODULEFACTORY))
            {
                msg.setParam(FACTORY_P_MODULENAME,moduleName).setParam(FACTORY_P_NEWMODULENAME,newModuleName);
                return getModuleFromOtherFactory(factory,msg)  ;
            }
        }
        if(moduleName.equals(MODULEFACTORY) || moduleName.equals(name))
             return createMsg().setParam(FACTORY_R_MODULEINSTANCE, this).setParam(FACTORY_P_MODULENAME, newModuleName);
        HashMap<String, String> moduleConfig = (HashMap<String, String>) msg.getMapParam(FACTORY_P_MODULECONFIG,null);
        if (moduleConfig == null)
        {
            moduleConfig = modulesClass.get(moduleName);
            if (moduleConfig == null &&  moduleName.indexOf(".") ==-1 && defaultUseParentFactory ==true && parentFactory !=null)
                return putMsg(parentFactory,msg) ;
        }
        //获取单例标志
        Boolean singleton = true;
        if (!msg.isNull(MODULE_SINGLETON))
            singleton = msg.parseBoolean(MODULE_SINGLETON, true);
        else if (moduleConfig != null) {
            String csingleton = moduleConfig.get(MODULE_SINGLETON);
            if (csingleton != null && !csingleton.isEmpty())
                singleton = Boolean.valueOf(csingleton);
        }
        if (singleton == true) {
            Object module = modules.get(newModuleName);
            if (module != null)
                return createMsg().setParam(FACTORY_R_MODULEINSTANCE, module).setParam(FACTORY_P_MODULENAME, newModuleName);
        }
        if (moduleConfig != null)
        {
            String factoryConfig = moduleConfig.get(MODULE_ONFACTORY);
            if (factoryConfig != null && !factoryConfig.isEmpty() && !factoryConfig.equals(name))       //其他工厂的模块
            {
                 position =factoryConfig.indexOf("@");
                 String factory;
                if(position !=-1 )
                {
                    String moduleInFactory =factoryConfig.substring(0,position);
                    factory =factoryConfig.substring(position+1);   //其他工厂的模块
                    String newModuleInFactory = getModuleName(newModuleName);
                    msg.setParam(FACTORY_P_MODULENAME,moduleInFactory).setParam(FACTORY_P_NEWMODULENAME,newModuleInFactory);
                }
               else
                    factory = factoryConfig ;
               if(!factory.equals(name) && !factory.equals(MODULEFACTORY))
                    return getModuleFromOtherFactory(factory,msg)  ;
            }
        }
        String classFilename;
        if (moduleConfig != null)
        {
            String proxyModule = moduleConfig.get(MODULE_PROXYMODULE);
            if (proxyModule != null && !proxyModule.isEmpty())
                classFilename = modulesClass.get(proxyModule).get(MODULE_CLASSFILE);
            else
                classFilename = moduleConfig.get(MODULE_CLASSFILE);
        } else
            classFilename = moduleName;
        classFilename = addPackage(classFilename);
        //是否只创建模块，而不start运行
        Boolean ifOnlyCreate = false;
        if (!msg.isNull(MODULE_IFONLYCREATE))
            ifOnlyCreate = msg.parseBoolean(MODULE_IFONLYCREATE, false);
        else if (moduleConfig != null) {
            String ifOnlyCreateParams = moduleConfig.get(MODULE_IFONLYCREATE);
            if (ifOnlyCreateParams != null && !ifOnlyCreateParams.isEmpty())
                ifOnlyCreate = Boolean.valueOf(ifOnlyCreateParams);
        }
        if (ifOnlyCreate == true) {
            Object module = createObject(newModuleName, classFilename);
            return createMsg().setParam(FACTORY_R_MODULEINSTANCE, module).setParam(FACTORY_P_MODULENAME, newModuleName);
        }

        if (moduleConfig != null)
        {
            String toModule = moduleConfig.get(MODULE_TOMODULE);
            if (toModule != null && !toModule.isEmpty())
            {
                msg.setParam(FACTORY_P_NEWMODULENAME,toModule).setParam(FACTORY_P_MODULENAME, toModule) ;
                return getModule(moduleFactory, msg);
            }
        }
        HashMap<String, String> cparams = getModuleParam(moduleName);
        if(!moduleName.equals(newModuleName))
        {
            HashMap<String,String> newModuleParams = getModuleParam(newModuleName);
            cparams.putAll(newModuleParams);
        }
        if (!msg.isNull(MODULE_PARAMS)) {
            HashMap<String, String> paramsInMsg = (HashMap<String, String>) msg.getMapParam(MODULE_PARAMS,null);
            if (paramsInMsg != null)
                cparams.putAll(paramsInMsg);       //添加 执行msg里面的参数
        }
        String moduleConfigFile = null;
        if(!msg.isNull(MODULE_CONFIGFILE))
            moduleConfigFile =msg.getStringParam(MODULE_CONFIGFILE,null) ;
        else if (cparams.get(MODULE_CONFIGFILE) != null)
            moduleConfigFile = cparams.get(MODULE_CONFIGFILE);
        else if (moduleConfig != null)
              moduleConfigFile = moduleConfig.get(MODULE_CONFIGFILE);

        if (moduleConfigFile != null && !moduleConfigFile.isEmpty())
               moduleConfigFile =getConfigRealPath(moduleConfigFile) ;
        Object module;
        if (singleton == false)
            module = createModule(newModuleName, classFilename, moduleConfigFile, cparams);
        else {
            Class<?> clazz  = myClassforName(classFilename); // 取得Class对象
            if(clazz ==null)
                return createMsg().setParam(FACTORY_R_MODULEINSTANCE, null).setParam(FACTORY_P_MODULENAME, newModuleName);
            synchronized (clazz)
            {
                module = modules.get(newModuleName);
                if (module != null)
                    return createMsg().setParam(FACTORY_R_MODULEINSTANCE, module).setParam(FACTORY_P_MODULENAME, newModuleName);
                module = createModule(newModuleName, classFilename, moduleConfigFile, cparams);
                if (module != null)
                    modules.put(newModuleName, module);
            }
        }
        if (module != null && module instanceof TLBaseModule)
            ((TLBaseModule) module).runStartMsg();
        return createMsg().setParam(FACTORY_R_MODULEINSTANCE, module).setParam(FACTORY_P_MODULENAME, newModuleName);
    }
    public   HashMap<String, String> getModuleParam(String moduleName){
        HashMap<String, String> moduleConfig = modulesClass.get(moduleName);
        HashMap<String, String> cparams = new HashMap<>();
        cparams.put("applicationId",applicationId);
        if (commonParams != null && !commonParams.isEmpty())   //添加commonParams里面公共参数
            cparams.putAll(commonParams);
        if (moduleConfig != null) {
            HashMap<String, String> moduleParam = getModuleParams(moduleConfig);
            if (moduleParam != null)                              //添加 modules 里面的参数
                cparams.putAll(moduleParam);
        }
        if (modulesParams != null) {           //添加modulesParams里面的模块参数
            HashMap<String, String> moduleParam = modulesParams.get(moduleName);
            if (moduleParam != null)
                cparams.putAll(moduleParam);
        }
         return cparams ;
    }
    private Object createModule(String newModuleName, String classFilename, String configFile, HashMap<String, String> cparams) {
        if (!newModuleName.equals(DEFAULTLOG))
            putLog(" 创建模块:" + newModuleName, LogLevel.DEBUG, "createModule");
        Object module = createObject(newModuleName, classFilename);
        if (module == null) {
            putLog(newModuleName + "  为空值,创建失败", LogLevel.ERROR, "crateModule");
            return null ;
        }
        if (module instanceof TLBaseModule) {
            TLBaseModule startModule = ((TLBaseModule) module).start(configFile, cparams);
            if (startModule == null) {
                putLog(newModuleName + " 启动失败", LogLevel.ERROR, "crateModule");
                return null ;
            }
        }
        return module;
    }

    protected String addPackage(String name) {
        if (name == null)
            return null;
        String firstCha = (String) name.subSequence(0, 1);
        if (firstCha.equals(".")) {
            return packageName + name;
        } else
            return name;
    }

    protected HashMap<String, String> getModuleParams(HashMap<String, String> moduleConfig) {
        HashMap<String, String> params = null;
        for (String key : moduleConfig.keySet()) {
            if (!key.equals("name") && !key.equals(MODULE_CLASSFILE) && !key.equals(MODULE_ONFACTORY) && !key.equals(MODULE_PROXYMODULE)
                    && !key.equals(MODULE_SINGLETON) && !key.equals(MODULE_CONFIGFILE)&& !key.equals(MODULE_TOMODULE)) {
                if (params == null)
                    params = new HashMap<>();
                params.put(key, moduleConfig.get(key));
            }
        }
        return params;
    }

    protected Object createObject(String moduleName, String className) {

        Class<?> cls = myClassforName(className); // 取得Class对象
        if(cls ==null)
            return null ;
        Constructor<?> cons;
        try {
            cons = cls.getConstructor(String.class, TLObjectFactory.class);
        } catch (NoSuchMethodException e1) {
            try {
                cons = cls.getConstructor(String.class);
            } catch (NoSuchMethodException e2) {
                Object obj = null;
                try {
                    obj = cls.newInstance();
                } catch (InstantiationException e3) {
                    putLog(className + " 实例化异常 e3", LogLevel.ERROR, "createObject");
                    e3.printStackTrace();
                    return null ;
                } catch (IllegalAccessException e4) {
                    putLog(className + " 非法访问 e4", LogLevel.ERROR, "createObject");
                    e4.printStackTrace();
                    return null ;
                }
                return obj;
            }
            try {
                return cons.newInstance(moduleName); // 为构造方法传递参数
            } catch (InstantiationException e5) {
                putLog(className + " 实例化异常 e5", LogLevel.ERROR, "createObject");
                e5.printStackTrace();
                return null ;
            } catch (IllegalAccessException e6) {
                putLog(className + " 非法访问 e6", LogLevel.ERROR, "createObject");
                e6.printStackTrace();
                return null ;
            } catch (InvocationTargetException e7) {
                putLog(className + " 反射异常 e7", LogLevel.ERROR, "createObject");
                e7.printStackTrace();
                return null ;
            }
        }
        try {
            return cons.newInstance(moduleName, this);
        } catch (InstantiationException e8) {
            putLog(className + " 实例化异常 e8", LogLevel.ERROR, "createObject");
            e8.printStackTrace();
            return null ;
        } catch (IllegalAccessException e9) {
            putLog(className + " 非法访问 e9", LogLevel.ERROR, "createObject");
            e9.printStackTrace();
            return null ;
        } catch (InvocationTargetException e10) {
            putLog(className + " 反射异常 e10", LogLevel.ERROR, "createObject");
            e10.printStackTrace();
            return null ;
        }
    }

    public  Class<?> myClassforName(String className){
        Class<?> cls = null; // 取得Class对象
        try {
            cls = Class.forName(className);
        } catch (ClassNotFoundException e)
        {
            ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
            try {
                cls=Class.forName(className,true,systemClassLoader) ;
            } catch (ClassNotFoundException e1) {
                e1.printStackTrace();
                String log= "classPath:"+classPath +"\n"+className + ": 没有找到类文件\n"+TLToolsUtils.exceptionToString(e1) ;
                putLog(log, LogLevel.ERROR, "myClassforName");
            }
        }
        return  cls;
    }

    public static class myConfig extends TLModuleConfig {

        protected ArrayList<TLMsg> factoryBoot;
        protected HashMap<String, String> commonParams;

        public myConfig(String configFile, String configDir) {
            super(configFile, configDir);
        }

        public ArrayList getFactoryBoot() {
            return factoryBoot;
        }

        public HashMap<String, String> getCommonParams() {
            return commonParams;
        }


        @Override
        protected Object moduleConfig(XmlPullParser xpp, String paramName) {
            Object returnObj = null;
            try {
                if (xpp.getName().equals("factoryBoot")) {
                    if (paramName != null)
                        returnObj = getMsgList(xpp, "factoryBoot");
                    else
                        factoryBoot = getMsgList(xpp, "factoryBoot");
                } else if (xpp.getName().equals("commonParams")) {
                    if (paramName != null)
                        returnObj = getParam(xpp, "commonParams");
                    else
                        commonParams = getParam(xpp, "commonParams");
                }
            } catch (Throwable t) {

            }
            return returnObj;
        }
    }

    public TLMsg moduleActionStart(String module, TLMsg msg) {
        if (ifModuleMonitor == false)
            return msg;
        TLMsg mmsg = createMsg().setAction("actionStart").setParam(MODULENAME, module).setParam(MSG, msg);
        TLMsg mreturnMsg = putMsg(moduleMonitor, mmsg);
        if (mreturnMsg == null)
            return msg;
        else
            return mreturnMsg;
    }

    public TLMsg moduleActionEnd(String module, TLMsg msg, TLMsg returnMsg) {
        if (ifModuleMonitor == false)
            return returnMsg;
        TLMsg mmsg = createMsg().setAction("actionEnd")
                .setParam(MODULENAME, module)
                .setParam(RETURNMSG, returnMsg)
                .setParam(MSG, msg);
        TLMsg mreturnMsg = putMsg(moduleMonitor, mmsg);
        if (mreturnMsg == null)
            return returnMsg;
        else
            return mreturnMsg;
    }
}
