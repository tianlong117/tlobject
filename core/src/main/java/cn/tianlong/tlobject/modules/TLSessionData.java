package cn.tianlong.tlobject.modules;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.base.MyUncheckedExceptionhandler;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLSessionData extends TLBaseSessionData {
    protected  Long expire= 0L;
    protected   ConcurrentHashMap<String ,Object> sessionDatas=new ConcurrentHashMap<String ,Object>();
    protected   ConcurrentHashMap<String ,Long> sessionTimer=new ConcurrentHashMap<String ,Long>();
    public TLSessionData() {
        super();
    }
    public TLSessionData(String name ) {
        super(name);
    }
    public TLSessionData(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }
    @Override
    protected void initProperty(){
        super.initProperty();
        if(params!=null && params.get("expire")!=null)
            expire = Long.valueOf(Integer.parseInt(params.get("expire"))*1000*60);

    }
    @Override
    protected TLBaseModule init() {
        if(expire >0L)
        putMsg(this,createMsg().setAction("checkTask")
                .setSystemParam(IFTASKDEAMON,true)
                .setSystemParam(EXCEPTIONHANDLER,new MyUncheckedExceptionhandler(this,createMsg().setAction("restart")))
                .setWaitFlag(false));
        return this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case"checkTask":
                checkTask();
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
        putLog("发生异常，sessiondata模块重启",LogLevel.ERROR);
        putLog(e.getMessage() +e.getCause(),LogLevel.ERROR);
        putMsg(this,createMsg().setAction("checkTask")
                .setSystemParam(IFTASKDEAMON,true)
                .setSystemParam(EXCEPTIONHANDLER,new MyUncheckedExceptionhandler(this,createMsg().setAction("restart")))
                .setWaitFlag(false));
    }
    @Override
    protected TLMsg deleteSessionData(Object fromWho, TLMsg msg) {
        String key = (String) msg.getParam("sid");
        if(key !=null)
        {
            sessionDatas.remove(key);
            sessionTimer.remove(key);
        }
        return null;
    }

    @Override
    protected TLMsg putSessionData(Object fromWho, TLMsg msg) {
        String key = (String) msg.getParam("sid");
        if(key !=null)
        {
            sessionDatas.put(key,msg.getParam("value"));
            sessionTimer.put(key,System.currentTimeMillis());
        }
        return null;
    }

    @Override
    protected TLMsg getSessionData(Object fromWho, TLMsg msg) {
        String key = (String) msg.getParam("sid");
        if(key !=null)
        {
            Object value =sessionDatas.get(key );
            sessionTimer.put(key,System.currentTimeMillis());
            return createMsg().setParam("value",value);
        }
        return null;
    }
    protected void checkTask() {
        int delay=30;
        if(params.get("delay")!=null)
            delay= Integer.parseInt(params.get("delay"));
        while (true){
            try {
                Thread.sleep(delay*1000);
                checkSessionTime();
            } catch (InterruptedException e) {
                // 中断来自线程池 shutdownNow / destroy：必须退出循环。
                // 原实现在这里 interrupt()+init()，中断标志被重新置位会让下一轮 sleep 立即抛异常，
                // 形成紧循环且每轮再投一个 checkTask，非 daemon worker 被占满后进程无法退出。
                Thread.currentThread().interrupt();
                putLog("session check interrupted, exit",LogLevel.DEBUG,"exception1");
                break;
            }
        }
    }
    protected void checkSessionTime(){
        putLog("开始sessiondata过期清理:",LogLevel.DEBUG);
        Long nowTime =System.currentTimeMillis();
        for(String key :sessionTimer.keySet()){
            Long stime =sessionTimer.get(key);
            if((nowTime - stime) >expire )
            {
                sessionDatas.remove(key);
                sessionTimer.remove(key);
                putLog("key过期:"+key,LogLevel.DEBUG);
            }
        }
    }


}
