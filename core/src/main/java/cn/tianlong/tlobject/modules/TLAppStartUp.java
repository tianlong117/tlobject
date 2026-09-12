package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLModuleConfig;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.utils.TLMsgUtils;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;

import static java.lang.System.exit;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.*;
/**
 * 程序启动模块类
 */
public class TLAppStartUp extends TLBaseModule {
    static TLObjectFactory appFactory ;
    protected String appName ;
    protected HashMap<String, HashMap<String, String>> appModules;
    public TLAppStartUp() {
        super();
    }
    public TLAppStartUp(String name) {
        super(name);
    }
    public TLAppStartUp(String name, TLObjectFactory moduleFactory) {
        super(name, moduleFactory);
    }
    @Override
    protected Object setConfig() {
        myConfig config = new myConfig(configFile,moduleFactory.getConfigDir());
        mconfig = config;
        super.setConfig();
        appModules = config.getTables();
        return config;
    }
    public static void  main (String[] args ) {

        HashMap<String,String> argsMap =parseArgs(args) ;
        boolean checkArgsResult =checkArgs(argsMap);
        if(checkArgsResult==false)
            return;
        main0( argsMap);
    }
    public static void  main0 (HashMap<String,String> argsMap ) {

        TLAppStartUp instance = new TLAppStartUp("serverStartup");
        instance.startup( argsMap);
    }
    public static HashMap<String,String>  parseArgs(String[] args){
        // create Options object
        Options options = new Options();
        // Create a Parser
        CommandLineParser parser = new BasicParser( );
        options.addOption("d", "configPath", true, "配置文件目录");
        options.addOption("n", "appname", true, "应用名称" );
        options.addOption("m", "factoryConfigFile", true, "模块工厂配置文件名称");
        options.addOption("f", "appconfigFile", true, "应用配置文件" );
        options.addOption("h", "help", false, "帮助");
        // Parse the program arguments

        CommandLine commandLine ;
        try {
            commandLine = parser.parse( options, args );
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
        if( commandLine.hasOption('h') ) {
            printHelp();      // 原来只打一句 "Help Message"，真正的用法说明永远看不到
            exit(0);
        }
        HashMap<String,String> argsMap =new HashMap<>() ;
        if(commandLine.hasOption('d'))
            argsMap.put("configPath",commandLine.getOptionValue('d'));
        if(commandLine.hasOption('n'))
            argsMap.put("appName",commandLine.getOptionValue('n'));
        if(commandLine.hasOption('m'))
            argsMap.put("factoryConfigFile",commandLine.getOptionValue('m'));
        if(commandLine.hasOption('f'))
            argsMap.put("configFile",commandLine.getOptionValue('f'));
        return argsMap ;
    }
    public static boolean checkArgs(HashMap<String,String> argsMap)
    {
        if(!argsMap.containsKey("configPath"))
        {
            System.out.println("缺少配置文件路径");
            return false ;
        }
        return true ;
    }
    public static void printHelp(){
        String helpStr ="用法:TLAppStartUp -d 配置文件路径 -m 工厂配置文件 -f 应用启动配置文件 -n 应用名称 \n";
        helpStr=helpStr+" 参数说明： \n";
        helpStr=helpStr+"  -- 如配置文件路径不以/开头，则默认CLASSPATH/conf/配置文件路径 \n";
        helpStr=helpStr+"  -- 工厂配置文件可缺省，缺省默认"+MODULEFACTORY + "_config.xml \n";
        helpStr=helpStr+"  -- 应用启动配置文件可缺省 \n";
        helpStr=helpStr+"  -- 应用名称可缺省 \n";
        System.out.println(helpStr);
    }
    public  TLObjectFactory startup(String[] args) {
        String appName = null;
        String configdir = "/";
        String factoryConfigFile = null;
        if (args == null || args.length != 4)
        {
            println("参数不能为空,或者数量不匹配");
            return null ;
        }
        configdir = args[0];
        factoryConfigFile = args[1];
        configFile = args[2];
        appName = args[3];
        HashMap<String,Object> argsMap =new HashMap<>() ;
        argsMap.put("appName",appName);
        argsMap.put("configPath",configdir);
        argsMap.put("factoryConfigFile",factoryConfigFile);
        argsMap.put("configFile",configFile);
        return startup(argsMap);
    }
    public  TLObjectFactory startup(HashMap argsMap) {
        appName = (String) argsMap.get("appName");
        if(appName ==null)
            appName=name;
        String configdir = (String) argsMap.get("configPath");
        String factoryConfigFile = (String) argsMap.get("factoryConfigFile");
        configFile = (String) argsMap.get("configFile");
        HashMap<String, String> params = (HashMap<String, String>) argsMap.get("params");
        if (factoryConfigFile == null)
            factoryConfigFile = MODULEFACTORY + "_config.xml";
        if (configdir == null)
            configdir = CLASSPATH+ File.separator;
        String classPath =TLObjectFactory.getSysClassPath();
        println("classPath  : "+classPath);
        String realConfigDir = TLObjectFactory.getConfigRealPath(configdir,classPath,null);
        println("realConfigDir  : "+realConfigDir);
        moduleFactory = TLObjectFactory.getInstance(realConfigDir, factoryConfigFile);
        moduleFactory.setClassPath(classPath);
        moduleFactory.startFactory(null,null);
        moduleFactory.boot();
        appFactory =moduleFactory ;
        modules.put(MODULEFACTORY,moduleFactory);
        if (configFile != null) {
            configFile = realConfigDir + configFile;
            start(configFile, params);
        } else
            start(null, params);
        addAppModules();
        runStartMsg();
        run();
        return  moduleFactory ;
    }
    private void addAppModules() {
        if(appModules ==null )
            return;
        for(String appName  : appModules.keySet())
        {
            HashMap<String,String> appConfig =appModules.get(appName);
            appConfig.put("appName",appName) ;
            String waitFlag =appConfig.get("waitFlag");
            if(waitFlag ==null || waitFlag.isEmpty() || Boolean.parseBoolean(waitFlag)!=false)
               startAppModule(appConfig);
            else {
                TLMsg msg =createMsg().setAction("startup").setWaitFlag(false);
                TLMsgUtils.mapToMsg(msg,appConfig);
                putMsg(this,msg);
            }
        }
    }
    private void startAppModule(HashMap<String,String> appConfig){
         String registAppName =appConfig.get("registAppName");
         if(registAppName ==null || registAppName.isEmpty())
             registAppName = this.appName ;
         String startUpclass =appConfig.get("startUpclass");
         String appName =appConfig.get("appName");
         TLAppStartUp appStartUp ;
         if(startUpclass !=null && !startUpclass.isEmpty())
         {
             if(!appConfig.containsKey("configPath") || !appConfig.containsKey("factoryConfigFile"))
             {
                 TLObjectFactory factory = runAppByClass(startUpclass);
                 if(factory ==null)
                     return;
                 registInfactory(moduleFactory, appName, factory);
                 registInfactory(factory,  registAppName, moduleFactory);
                 return;
             }
             else
                 appStartUp = (TLAppStartUp)getObject(startUpclass);
         }
         else
             appStartUp = new TLAppStartUp(appName);
         if(appStartUp ==null)
         {
             putLog(this.appName+" app模块加载失败:"+appName,LogLevel.DEBUG,"addAppModules");
             return;
         }
         TLObjectFactory factory =appStartUp.startup(appConfig);
         if(factory ==null)
             return;
         putLog(this.appName+" 加载app模块:"+appName,LogLevel.DEBUG,"addAppModules");
         registInfactory(moduleFactory, appName, factory);
         registInfactory(factory,  registAppName, moduleFactory);
     }
    private  TLObjectFactory runAppByClass(String startUpclass) {
        TLObjectFactory  myfactory ;
        Class<?> startUpClazz = null;
        try {
            startUpClazz = Class.forName(startUpclass);
        } catch (ClassNotFoundException e) {
            putLog(this.appName+"无法加载模块"+startUpclass,LogLevel.DEBUG,"addAppModules");
            return null;
        }
        Method startMethod = null;
        try {
            startMethod = startUpClazz.getMethod("startModule", String[].class);
        } catch (NoSuchMethodException e) {
            putLog(this.appName+"加载模块，模块缺失startMethod方法"+startUpclass,LogLevel.DEBUG,"addAppModules");
        }
        if(startMethod !=null)
        {
            try {
                String[] s = null;
                myfactory= (TLObjectFactory) startMethod.invoke(null, (Object)s);
            } catch (IllegalAccessException e) {
                putLog(this.appName+"加载模块，main方法非法运行"+startUpclass,LogLevel.DEBUG,"addAppModules");
                return null;
            } catch (InvocationTargetException e) {
                putLog(this.appName+"加载模块，main方法运行错误"+startUpclass,LogLevel.DEBUG,"addAppModules");
                return null;
            }
            return myfactory ;
        }
        //
        Method mainMethod = null;
        try {
            mainMethod = startUpClazz.getMethod("main", String[].class);
        } catch (NoSuchMethodException e) {
            putLog(this.appName+"加载模块，模块缺失main方法"+startUpclass,LogLevel.DEBUG,"addAppModules");
            return null;
        }
        try {
            String[] s = null;
            mainMethod.invoke(null, (Object)s);
        } catch (IllegalAccessException e) {
            putLog(this.appName+"加载模块，main方法非法运行"+startUpclass,LogLevel.DEBUG,"addAppModules");
            return null;
        } catch (InvocationTargetException e) {
            putLog(this.appName+"加载模块，main方法运行错误"+startUpclass,LogLevel.DEBUG,"addAppModules");
            return null;
        }
        try {
            Field field =startUpClazz.getDeclaredField("appFactory");
            try {
                myfactory = (TLObjectFactory) field.get(startUpClazz) ;
            } catch (IllegalAccessException e) {
                putLog(this.appName+"加载模块，app工厂获得错误"+startUpclass,LogLevel.DEBUG,"addAppModules");
                return null;
            }
        } catch (NoSuchFieldException e) {
            putLog(this.appName+"加载模块，app工厂获得错误"+startUpclass,LogLevel.DEBUG,"addAppModules");
            return null;
        }
        return myfactory ;
    }

    protected void run() {
    }
    @Override
    protected TLBaseModule init() {
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "startup":
                startup( fromWho,  msg);
                break;
            default:
                returnMsg=null;
        }
        return returnMsg;
    }

    private void startup(Object fromWho, TLMsg msg) {
        HashMap argsMap =msg.getArgs();
        startAppModule(argsMap);
    }

    public  static void  shutdown(){
        appFactory.shutdown();
    }

    protected void registInfactory(TLObjectFactory modulefactory, String name, TLBaseModule object)
    {
        modulefactory.addFactory(name,object);
    }
    protected class myConfig extends TLModuleConfig {
        protected HashMap<String, HashMap<String, String>> appModules;

        public myConfig() {

        }
        public myConfig(String configFile, String configDir) {
            super(configFile,configDir);
        }
        public HashMap getTables() {
            return appModules;
        }


        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if (xpp.getName().equals("appModules")) {
                    appModules = getHashMap(xpp, "appModules", "app");
                }
            } catch (Throwable t) {

            }
        }

    }
}
