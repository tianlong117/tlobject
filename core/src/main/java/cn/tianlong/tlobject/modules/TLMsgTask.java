package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.*;
import org.quartz.CronExpression;
import org.xmlpull.v1.XmlPullParser;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 创建日期：2018/4/23 on 21:00
 * 描述:
 * 作者:tianlong
 */
/**
 消息计划任务模块
 */
public class TLMsgTask extends TLBaseModule {
    protected ScheduledExecutorService executor;
    protected Map<String, TLMsg>  taskMsgTable = new ConcurrentHashMap<>();
    protected Map<String, HashMap<String, Object>> taskDatas = new ConcurrentHashMap<>();
    protected int poolSize = 1;
    protected TLMsg denyMsg = new TLMsg().setParam(MODULE_DONEXTMSG, "false");

    public TLMsgTask() {
        super();
    }

    public TLMsgTask(String name) {
        super(name);
    }

    public TLMsgTask(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected Object setConfig() {
        myConfig config = new myConfig(configFile,moduleFactory.getConfigDir());;
        mconfig = config;
        super.setConfig();
        ArrayList<TLMsg> taskMsgs = config.getTaskMsgTable();
        if(taskMsgs !=null)
        {
            for (TLMsg msg : taskMsgs)
                taskMsgTable.put(getTaskid(msg),msg);
        }
        return config;
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        if (params != null && params.get("poolSize") != null)
                poolSize = Integer.parseInt(params.get("poolSize"));
    }

    @Override
    protected TLBaseModule init() {
        if(poolSize ==0)
        {
            putLog("poolSize must to set ", LogLevel.ERROR);
            return this ;
        }
        if (params != null && params.get("status") != null && !params.get("status").equals("run")) {
            putLog("if you wang to run ,set status to run ", LogLevel.WARN);
            return this ;
        }
        if (executor == null)
            executor = Executors.newScheduledThreadPool(poolSize);
        if (taskMsgTable == null || taskMsgTable.isEmpty())
            return this ;
        for (String taskid:taskMsgTable.keySet())
        {
            TLMsg msg = taskMsgTable.get(taskid);
            String status = (String) msg.getParam("status");
            if (status == null || status.equals("run")) {
                runTask(msg);
            }
            else  if(status.equals("stop"))
                stopTask(msg);
        }
        return this ;
    }

    @Override
    protected void reConfig() {
        if (params != null) {
            if (params.get("status") != null && params.get("status").equals("shutdown")) {
                poolShutdown();
                return;
            }
            if (params.get("status") != null && params.get("status").equals("stop")) {
                for (String taskid:taskMsgTable.keySet()) {
                    TLMsg msg = taskMsgTable.get(taskid);
                    stopTask(msg);;
                }
                return;
            }
        }
        if (taskMsgTable == null || taskMsgTable.isEmpty())
            return;
        for (String taskid:taskMsgTable.keySet())
        {
            TLMsg tmsg = taskMsgTable.get(taskid);
            String status = (String) tmsg.getParam("status");
            if (status == null)
                continue;
            if (status.equals("restart"))
                restartTask(taskid, tmsg);
            else {
                HashMap<String, Object> nowTaskdata = taskDatas.get(taskid);
                if(nowTaskdata == null)
                {
                    if (status.equals("run"))
                        runTask(tmsg);
                    else if(status.equals("stop"))
                        stopTask(tmsg);
                }
            }
        }
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case TASK_REGISTTASK:
                registTask(fromWho, msg);
                break;
            case TASK_SETTASKSTATUS:
                setTaskStatus(fromWho, msg);
                break;
            case TASK_UNREGISTTASK:
                unRegistTask(fromWho, msg);
                break;
            case TASK_STARTTASK:
                startTask(fromWho, msg);
                break;
            case TASK_GETTASK:
                returnMsg = getTasks(fromWho, msg);
                break;
            case "taskControl":
                returnMsg = taskControl(fromWho, msg);
                break;
            case TASK_SHUTDOWN:
                shutdown(fromWho, msg);
                break;
            case TASK_STOPTASK:
                stopTask(fromWho, msg);
                break;
            case TASK_RUNTASK:
                runTask(fromWho, msg);
                break;
            default:
        }
        return returnMsg;
    }

    private void runTask(Object fromWho, TLMsg msg) {
        String nowTaskid = (String) msg.getParam(TASK_P_TASKID);
        if (nowTaskid != null) {
            TLMsg nowTaskMsg = taskMsgTable.get(nowTaskid);
            if(nowTaskMsg !=null)
                runTask(nowTaskMsg);
        } else {
            for (String taskid:taskMsgTable.keySet())
            {
                TLMsg tmsg = taskMsgTable.get(taskid);
                runTask(tmsg);
            }
        }
    }

    private void  stopTask(Object fromWho, TLMsg msg) {
        String nowTaskid = (String) msg.getParam(TASK_P_TASKID);
        if (nowTaskid != null) {
            TLMsg nowTaskMsg = taskMsgTable.get(nowTaskid);
            if(nowTaskMsg !=null)
                stopTask(nowTaskMsg);
        } else {
            for (String taskid:taskMsgTable.keySet())
            {
                TLMsg tmsg = taskMsgTable.get(taskid);
                stopTask(tmsg);
            }
        }
    }
    private Boolean stopTask(TLMsg msg){
        HashMap<String, Object> nowTaskdata = taskDatas.get(msg.getParam(TASK_P_TASKID));
        if (nowTaskdata == null)
        {
            msg.setParam("status","shutdown");
            return false ;
        }
        String status = (String) msg.getParam("status");
        if(status.equals("run"))
        {
            msg.setParam("status","stop");
            return true;
        }
        else
            return false ;
    }
    private Boolean runTask(TLMsg msg){
        HashMap<String, Object> nowTaskdata = taskDatas.get(msg.getParam(TASK_P_TASKID));
        if (nowTaskdata == null)
        {
            if (msg.getParam(TASK_P_CRON) != null)
                msg.setParam("delay", "100").setParam("timeUnit", "ms");
            startTask(msg);
            return true;
        }
        String status = (String) msg.getParam(TASK_P_STATUS);
        if(status.equals("stoping"))
        {
            msg.setParam("status","run");
            return true;
        }
        else
            return false ;
    }
    private void shutdown(Object fromWho, TLMsg msg) {
        String nowTaskid = (String) msg.getParam(TASK_P_TASKID);
        if (nowTaskid != null) {
            HashMap<String, Object> nowTaskdata = taskDatas.get(nowTaskid);
            if (nowTaskdata != null)
                shutdownTask(nowTaskid);
        } else {
            String pool = (String) msg.getParam("pool");
            if(pool !=null)
                poolShutdown();
            else
                for (String taskid : taskDatas.keySet()) {
                shutdownTask(taskid);
                }
        }
    }
    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        executor.shutdown();
        executor=null;
        return null ;
    }
    private void poolShutdown(){
        executor.shutdown();
        executor=null;
        for (String taskid:taskMsgTable.keySet()) {
            TLMsg tmsg = taskMsgTable.get(taskid);
            tmsg.setParam("status", "shutdown");
            tmsg.setParam("datetime", new Date());
            tmsg.removeParam("nextDatetime");
        }
        taskDatas.clear();
        putLog("session pool shutdown", LogLevel.WARN,"shutdown");
    }
    private void startTask(Object fromWho, TLMsg msg) {
        String nowTaskid = (String) msg.getParam(TASK_P_TASKID);
        if (nowTaskid != null) {
            TLMsg nowTaskMsg = taskMsgTable.get(nowTaskid);
            if(nowTaskMsg !=null)
                restartTask(nowTaskid, nowTaskMsg);
        } else {
            for (String taskid:taskMsgTable.keySet())
            {
                TLMsg tmsg = taskMsgTable.get(taskid);
                restartTask(taskid, tmsg);
            }
        }
    }

    private TLMsg getTasks(Object fromWho, TLMsg msg) {
        String nowTaskid = (String) msg.getParam(TASK_P_TASKID);
        if (nowTaskid != null) {
            return   taskMsgTable.get(nowTaskid);
        } else
            return createMsg().setParam("tasks", taskMsgTable);
    }

    private void setTaskStatus(Object fromWho, TLMsg msg) {
        String nowTaskid = (String) msg.getParam(TASK_P_TASKID);
        if (nowTaskid == null) {
            putLog("taskid is not set", LogLevel.ERROR);
            return;
        }
        TLMsg nowTaskMsg = taskMsgTable.get(nowTaskid);
        if (nowTaskMsg == null)
            return;
        msg.removeParam(TASK_P_TASKID);
        nowTaskMsg.addArgs(msg.getArgs());
        String status = (String) msg.getParam("status");
        if (status != null && status.equals("restart"))
            restartTask(nowTaskid, nowTaskMsg);
    }


    private void restartTask(String taskid, TLMsg tmsg) {
        HashMap<String, Object> nowTaskdata = taskDatas.get(taskid);
        if (nowTaskdata != null)
            shutdownTask(taskid);
        if (tmsg.getParam("cronExp") != null)
            tmsg.setParam("delay", "100").setParam("timeUnit", "ms");
        startTask(tmsg);
    }

    private TLMsg taskControl(Object fromWho, TLMsg msg) {
        String nowTaskid = (String) msg.getParam(TASK_P_TASKID);
        TLMsg nowTaskMsg = taskMsgTable.get(nowTaskid);
        if (nowTaskMsg == null)
            return null;
        String status = (String) nowTaskMsg.getParam("status");
        if (status != null && status.equals("error")) {
            putLog("taskid:" + nowTaskid + " has error,task has shutdown", LogLevel.ERROR, "error");
            shutdownTask(nowTaskid);
            nowTaskMsg.setParam("status", "error");
            return denyMsg;
        }
        if (status != null && status.equals("shutdown")) {
            shutdownTask(nowTaskid);
            return denyMsg;
        }
        if (status != null && status.equals("stop")) {
            nowTaskMsg.setParam("status", "stoping");
            putLog("taskid:" + nowTaskid + " stop", LogLevel.WARN, "stop");
            nowTaskMsg.setParam("datetime", new Date());
            return denyMsg;
        }
        if (status != null && status.equals("stoping"))
            return denyMsg;
        HashMap<String, Object> nowTaskdata = taskDatas.get(nowTaskid);
        int nowTimes =0;
        if(nowTaskdata !=null && nowTaskdata.containsKey("times"))
           nowTimes = (int) nowTaskdata.get("times");
        String timesLimit = (String) nowTaskMsg.getParam("times");
        if (timesLimit != null) {
            int times = Integer.parseInt(timesLimit);
            if (times > 0) {
                if (times == nowTimes) {
                    nowTaskMsg.setParam("status", "stoping");
                    putLog("taskid:" + nowTaskid + " stop,runing times:" + nowTimes, LogLevel.WARN, "stop");
                    shutdownTask(nowTaskid);
                    return denyMsg;
                }
            }
        }
        if (msg.getParam("cron") != null) {
            Date startTime = (Date) msg.getParam("startTime");
            CronExpression cron = (CronExpression) msg.getParam("cron");
            Long now = System.currentTimeMillis();
            Date execDate = cron.getTimeAfter(startTime);
            if (execDate == null) {
                ScheduledFuture<?> sf = (ScheduledFuture<?>) nowTaskdata.get("future");
                putLog("taskid:" + nowTaskid + " is work over,session shutdown", LogLevel.WARN, "shutdown");
                taskDatas.remove(nowTaskid);
                sf.cancel(true);
                nowTaskMsg.setParam("status", "shutdown");
                nowTaskMsg.setParam("datetime", new Date());
                return denyMsg;
            }
            long execTime = execDate.getTime();
            long timeUntilExec = execTime - now;
            if (timeUntilExec > 2) {
                displayTimeUntil(nowTaskid, timeUntilExec / 1000, msg);
                nowTaskMsg.setParam("nextDatetime", execDate);
                return denyMsg;
            } else
                msg.setParam("startTime", new Date());
        }
        nowTimes++;
        nowTaskdata.put("times", nowTimes);
        nowTaskMsg.setParam("execDatetime", new Date());
        nowTaskMsg.setParam("execTimes",nowTimes);
        putLog("taskid:" + nowTaskid + "  has runing times:" + nowTimes, LogLevel.DEBUG, "runing");
        return null;
    }

    private void shutdownTask(String taskid) {
        HashMap<String, Object> nowTaskdata = taskDatas.get(taskid);
        if (nowTaskdata == null) {
            putLog("shutdown error, no taskid:" + taskid, LogLevel.ERROR);
            return;
        }
        ScheduledFuture<?> sf = (ScheduledFuture<?>) nowTaskdata.get("future");
        putLog("taskid:" + taskid + " shutdown", LogLevel.WARN, "shutdown");
        taskDatas.remove(taskid);
        sf.cancel(true);
        TLMsg nowTaskMsg = taskMsgTable.get(taskid);
        nowTaskMsg.setParam("status", "shutdown");
        nowTaskMsg.setParam("datetime", new Date());
        nowTaskMsg.removeParam("nextDatetime");
    }

    private void displayTimeUntil(String taskid, Long time, TLMsg msg) {
        int lastDisplayTime = (int) msg.getParam("lastDisplayTime");
        String logContent = null;
        if (time < 60 && lastDisplayTime > 100)
            logContent = time + "s";
        else if (60 <= time && lastDisplayTime > 600)
            logContent = time / 60 + "m";
        if (logContent != null) {
            putLog("taskid:" + taskid + "  waiting:" + logContent, LogLevel.DEBUG, "waiting");
            msg.setParam("lastDisplayTime", 0);
        } else
            msg.setParam("lastDisplayTime", lastDisplayTime + 1);
    }

    private void registTask(Object fromWho, TLMsg msg) {
        TLMsg tmsg = (TLMsg) msg.getParam(TASK_P_TASKMSG);
        taskMsgTable.put(getTaskid(tmsg),tmsg);
        if (tmsg.getParam(TASK_P_STATUS) != null && tmsg.getParam(TASK_P_STATUS).equals(TASK_V_STATUS_RUN))
            executeTask(tmsg);
    }

    private void startTask(TLMsg msg) {
        String taskid = getTaskid(msg);
        if (taskDatas.get(taskid) != null)
            return;
        TLMsg controlMsg = createMsg().setAction("taskControl");
        TLMsg bmsg = createMsg();
        bmsg.copyFrom(msg);
        controlMsg.setParam(TASK_P_TASKID, taskid);
        controlMsg.setParam("taskMsg", msg);
        controlMsg.setNextMsg(bmsg);
        if (msg.getParam(TASK_P_CRON) != null) {
            CronExpression cron = null;
            try {
                cron = new CronExpression((String) msg.getParam(TASK_P_CRON));
            } catch (ParseException e) {
                putLog("cronExp error ", LogLevel.ERROR);
                return;
            }
            Date now = new Date();
            controlMsg.setParam("cron", cron)
                    .setParam("startTime", now)
                    .setParam("lastDisplayTime", 0);
        }
        executeTask(controlMsg);
    }

    private void unRegistTask(Object fromWho, TLMsg msg) {
        taskMsgTable.remove((String) msg.getParam("msgId"));
    }

    private String getTaskid(TLMsg msg) {
        String taskid = (String) msg.getParam(TASK_P_TASKID);
        if (taskid == null || taskid.isEmpty())
            taskid = msg.getDestination() + msg.getMsgId() + msg.getAction();
        return taskid;
    }

    private void executeTask(TLMsg msg) {
        Long begin = Long.valueOf(0);
        TLMsg taskMsg = msg.getNextMsg();
        String taskid = (String) msg.getParam(TASK_P_TASKID);
        String sbegin = (String) taskMsg.getParam(TASK_P_BEGINTIME);
        if (sbegin != null)
            begin = Long.parseLong(sbegin);
        String sdelay = (String) taskMsg.getParam(TASK_P_DELAYTIME);
        if (sdelay == null) {
            putLog("no set delay,taskid" + taskid, LogLevel.ERROR);
            return;
        }
        Long delay = Long.parseLong(sdelay);
        String timeUnitStr = (String) taskMsg.getParam(TASK_P_TIMEUNIT);
        if (timeUnitStr == null)
            timeUnitStr = TASK_V_TIMEUNIT_S;
        TimeUnit timeUnit = getTimeUnit(timeUnitStr);
        taskMsg.removeParam(TASK_P_DELAYTIME);
        taskMsg.removeParam(TASK_P_BEGINTIME);
        taskMsg.removeParam(TASK_P_TASKID);
        taskMsg.removeParam(TASK_P_RUNTIMES);
        taskMsg.removeParam(TASK_P_STATUS);
        taskMsg.removeParam(TASK_P_TIMEUNIT);
        taskMsg.removeParam(TASK_P_CRON);
        TLMsg taskMsgInTable =taskMsgTable.get(taskid);
        taskMsgInTable.setParam(TASK_P_STATUS, TASK_V_STATUS_RUN);
        Runnable task = getMsgTask(this, msg);
        if (executor == null)
            executor = Executors.newScheduledThreadPool(poolSize);
        ScheduledFuture<?> sf = executor.scheduleAtFixedRate(task, begin, delay, timeUnit);
        HashMap<String, Object> taskData = new HashMap<>();
        taskData.put(TASK_P_RUNTIMES, 0);
        taskData.put("future", sf);
        taskDatas.put(taskid, taskData);
        putLog("taskid: " + taskid + "  start ", LogLevel.DEBUG,"start");
    }

    private TimeUnit getTimeUnit(String timeUnitStr) {
        TimeUnit timeUnit;
        switch (timeUnitStr) {
            case TASK_V_TIMEUNIT_MS:
                timeUnit = TimeUnit.MILLISECONDS;
                break;
            case TASK_V_TIMEUNIT_S:
                timeUnit = TimeUnit.SECONDS;
                break;
            case TASK_V_TIMEUNIT_M:
                timeUnit = TimeUnit.MINUTES;
                break;
            case TASK_V_TIMEUNIT_H:
                timeUnit = TimeUnit.HOURS;
                break;
            default:
                timeUnit = TimeUnit.SECONDS;
        }
        return timeUnit;
    }

    private Runnable getMsgTask(final TLMsgTask object, final TLMsg msg) {
        Runnable task = new Runnable() {

            @Override
            public void run() {
                try{
                    object.getMsg(object, msg);
                } catch (Exception e) {
                    object.catchExp((String) msg.getParam("taskid"),e);
                }
            }
        };
        return task;
    }
    protected void catchExp(String taskid,Exception e){
        TLMsg taskMsg = taskMsgTable.get(taskid);
        taskMsg.setParam("status","error");
        putLog(e,LogLevel.ERROR,"catchExp");
    }


    protected class myConfig extends TLModuleConfig {
        protected ArrayList<TLMsg> taskMsgTable;
        public myConfig(String configFile ,String configDir) {
            super(configFile,configDir);
        }
        public myConfig() {

        }

        public ArrayList<TLMsg> getTaskMsgTable() {
            return taskMsgTable;
        }

        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if (xpp.getName().equals("taskMsgTable")) {
                    taskMsgTable = getMsgList(xpp, "taskMsgTable");
                }

            } catch (Throwable t) {

            }
        }

    }
}
