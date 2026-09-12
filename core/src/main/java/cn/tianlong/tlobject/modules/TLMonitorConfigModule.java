package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.base.MyUncheckedExceptionhandler;

import java.io.File;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 创建日期：2018/7/1013:49
 * 描述:
 * 作者:tianlong
 */
/**
 *  模块配置文件监控，当被监控模块的配置文件发生修改，则出发模块重新加载配置文件。
 */

public class TLMonitorConfigModule extends TLBaseModule {
    protected TLMsg reloadMsg=createMsg().setAction("reloadConfig");
    protected Map<String,String> moduleConfigFiles;
    protected Map<String,Long> fileLasttime;
    protected boolean ifStop =false;
    public TLMonitorConfigModule(){
        super();
    }
    public TLMonitorConfigModule(String name ){
        super(name);
    }
    public TLMonitorConfigModule(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }


    protected void initProperty(){
        super.initProperty();
        initModulesMap();
    }
    protected void initModulesMap(){
        moduleConfigFiles=new ConcurrentHashMap<>();
        fileLasttime=new ConcurrentHashMap<>();
        moduleConfigFiles.put(name,configFile);
        fileLasttime.put(name, Long.valueOf(0));
        // modules 未配置时只监听自身配置文件，而不是 NPE 掉整个模块启动
        String modulesStr = params.get("modules");
        if(modulesStr ==null || modulesStr.trim().isEmpty())
            return;
        String modules[] = modulesStr.split(";");
        for(int i=0;i<modules.length;i++){
                modules[i]=modules[i].trim();
                if(modules[i].isEmpty())
                    continue;
                moduleConfigFiles.put(modules[i],"");
                fileLasttime.put(modules[i], Long.valueOf(0));
        }
    }

    @Override
    protected TLBaseModule init() {
        putMsg(this,createMsg().setAction("checkModules")
                .setSystemParam(IFTASKDEAMON,true)
                .setSystemParam(EXCEPTIONHANDLER,new MyUncheckedExceptionhandler(this,createMsg().setAction("restart")))
                .setWaitFlag(false));
        putLog("监听配置文件模块启动",LogLevel.DEBUG);
        return  this ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case"checkModules":
                ifStop=false;
                checkTask();
                break;
            case"stop":
                ifStop=true;
                break;
            case"restart":
                restart(fromWho,msg);
                break;
            default:
                ;
        }
        return null;
    }

    private void restart(Object fromWho, TLMsg msg) {
        Throwable e = (Throwable) msg.getParam("throwable");
        putLog("发生异常，监听配置文件模块重启",LogLevel.ERROR);
        putLog(e.getMessage() +e.getCause(),LogLevel.ERROR);
        init();
    }

    protected void checkTask() {
        int delay=10;
        if(params.get("delay")!=null)
            delay= Integer.parseInt(params.get("delay"));
        int i =0 ;
        while (!ifStop){
            try {
                i++;
                Thread.sleep(delay*1000);
                if(i >=10)
                {
                    putLog("监听模块工作中。。。。",LogLevel.DEBUG,"runing");
                    i=0;
                }
                checkModules();
            } catch (InterruptedException e) {
                // 中断来自线程池 shutdownNow / destroy：退出循环，不能再 init() 重投任务
                // （中断标志被重新置位会让下一轮 sleep 立即抛异常，形成紧循环不断新增监听任务）
                Thread.currentThread().interrupt();
                putLog("config monitor interrupted, exit",LogLevel.DEBUG,"exception1");
                break;
            }
        }
        putLog("配置文件监听结束",LogLevel.DEBUG);
    }
    protected void checkModules() {
     for (String moduleName:moduleConfigFiles.keySet())
     {
       if(ifStop==true)
           return;
        String configFile=moduleConfigFiles.get(moduleName);
        if(configFile==null)
            continue;
        if(configFile.equals(""))
        {
           TLBaseModule module= (TLBaseModule) getModuleInFactory(moduleName);
           if(module!=null)
           {
               configFile=module.getConfigFile();
               if(configFile==null || configFile.isEmpty())
                   continue;
           }
           else
               continue;
        }

        Long lasttime=fileLasttime.get(moduleName);
        if(lasttime==null)
            continue;
        if(lasttime==0){
            URL res =TLBaseModule.class.getResource(configFile);
            if(res ==null)
                continue;
            File file = new File(res.getFile());
            long nowTime = file.lastModified();
            fileLasttime.put(moduleName,nowTime);
            continue;
        }
         Long nowTime =checkFileTime(configFile,lasttime);
         if(nowTime >0)
          {
              fileLasttime.put(moduleName,nowTime);
              putMsg(moduleName,reloadMsg);
          }
     }
    }
    protected Long checkFileTime(String moduleConfigFile, Long lastTime) {
        URL res =TLBaseModule.class.getResource(moduleConfigFile);
        if(res ==null)
            return Long.valueOf(0);
        File file = new File(res.getFile());
        long newTime = file.lastModified();
        if(newTime >lastTime)
            return newTime;
        else
            return Long.valueOf(0);
    }
    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        ifStop=true;
        return msg;
    }

}
