package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */

/**
 * 定时任务MsgTask命令控制台,通过配置msgTaskModule来控制相应的计划任务
 */
public class TLMsgTaskConsole extends TLMsgScanner {
    // 配置控制的msgTask模块
    protected  String msgTaskModule;
    protected  String taskParams[] = {"delay","begin","times"};
    protected  Map<String,TLMsg> taskMsgTable ;

    public TLMsgTaskConsole(String name) {
        super(name);
    }

    public TLMsgTaskConsole(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        if (params != null) {
            if (params.get("msgTaskModule") != null) {
                msgTaskModule = params.get("msgTaskModule");
            }
        }
        params.put("defaultModule",name);
        params.put("defaultAction","console");
        defaultModule = name;
        defaultAction ="console" ;
        HashMap<String ,String> mparams= new HashMap<>();
        mparams.put("actions","console");
        if(msgToModules ==null)
            msgToModules=new HashMap<>();
        msgToModules.put(name,mparams);
    }
    @Override
    protected TLBaseModule init(){
        super.init();
        listTask();
        return this ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case "console":
                console(fromWho,msg);
                break;
            default:
                super.checkMsgAction( fromWho,  msg);
        }
        return returnMsg;
    }
    protected void help(){
        System.out.println("1 restart/run/stop/shutdown all or  taskid ");
        System.out.println("2 set task status :s=status  t=taskid cron=  delay= begin=  times= ");
        System.out.println("  status must be set , value :restart/stop/run/shutdown ");
        System.out.println("3 list :display all tasks");
    }
    private void console(Object fromWho, TLMsg msg) {
        if(msgTaskModule==null)
        {
            putLog("msgTaskModule is null ",LogLevel.ERROR);
            return;
        }
        execMsg(msg);
    }

    private boolean execMsg(TLMsg msg) {
        HashMap<String,Object> cmds =msg.getArgs();
        if(cmds.size() ==1)
        {
            for (String cmd : cmds.keySet()) {
                if(cmds.get(cmd)==null)
                {
                    if( execCmd( cmd))
                        return true;
                    else
                    {
                        System.out.println("no cmd: "+cmd);
                        return false ;
                    }
                }
            }
        }
        if(setTaskStatus(msg) == false)
        {
            System.out.println("no cmd: "+cmds.toString());
            return false ;
        }
        else
            return  true ;
    }

    private Boolean execCmd(String cmd) {
       if(cmd.contains(" "))
       {
           String str[] = cmd.split(" ");
           if(str.length !=2)
           {
               System.out.println(" need params ,all or gave a taskid ");
               return false ;
           }
           if(str[0].equals("shutdown")  )
           {
              if(shutDownTocheckTaskid(str[1]))
                  shutdown(str[1]);
              return true ;
           }
           if(str[0].equals("restart")  )
           {
               if(checkTaskid (str[1]))
                 restart(str[1]);
               return true ;
           }
           if(str[0].equals("stop")  )
           {
               if(checkTaskid (str[1]))
                  stop(str[1]);
               return true ;
           }
           if(str[0].equals("run")  )
           {
               if(checkTaskid (str[1]))
                   run(str[1]);
               return true ;
           }
           return false ;
       }
        switch (cmd) {
            case "list":
                listTask();
                return true;
            default:
               return false ;
        }
    }
    private Boolean setTaskStatus(TLMsg msg) {
        String taskid = (String) msg.getParam("t");
        if(taskid==null || !taskMsgTable.containsKey(taskid))
        {
            putLog("taskid is error ",LogLevel.ERROR);
            return false;
        }
        String status = (String) msg.getParam("s");
        if(status==null)
        {
            putLog("status is null ",LogLevel.ERROR);
            return false;
        }
        TLMsg  statusMsg =createMsg().setAction("setTaskStatus");
        String cronExp = (String) msg.getParam("cron");
        if(cronExp!=null)
            statusMsg.setParam("cronExp",cronExp);
        statusMsg.setParam("taskid",taskid);
        statusMsg.setParam("status",status);
        for(int j=0;j< taskParams.length;j++)
        {
            Object p =msg.getParam(taskParams[j]);
            if(p!=null){
                statusMsg.setParam(taskParams[j],p);
            }
        }
        putMsg(msgTaskModule,statusMsg);
        return true ;
    }

    private void run(String taskid) {
        TLMsg msg=createMsg().setAction("runTask");
        if(!taskid.equals("all"))
            msg.setParam("taskid",taskid);
        putMsg(msgTaskModule,msg);
    }
    private void stop(String taskid) {
        TLMsg msg=createMsg().setAction("stopTask");
        if(!taskid.equals("all"))
            msg.setParam("taskid",taskid);
        putMsg(msgTaskModule,msg);
    }

    private void restart(String taskid) {
        TLMsg msg=createMsg().setAction("startTask");
        if(!taskid.equals("all"))
          msg.setParam("taskid",taskid);
        putMsg(msgTaskModule,msg);
    }

    private void shutdown(String taskid) {
        TLMsg msg=createMsg().setAction("shutdown");
        if(!taskid.equals("all") )
        {
            if(!taskid.equals("pool"))
                msg.setParam("taskid",taskid);
            else
                msg.setParam("pool","true");
        }
        putMsg(msgTaskModule,msg);
    }

    private void listTask() {
        System.out.println("/**** task list***/");
        TLMsg returnMsg = putMsg(msgTaskModule,createMsg().setAction("getTasks"));
        if(returnMsg.getParam("tasks")==null){
            printlnMsg(1,returnMsg);
            return;
        }
         taskMsgTable = ( Map<String,TLMsg>) returnMsg.getParam("tasks");
        int i=1;
        for (String taskid:taskMsgTable.keySet())
        {
            TLMsg tmsg = taskMsgTable.get(taskid);
            printlnMsg(i,tmsg);
            i++;
        }
        System.out.println("--nexttime only for cron express type");
    }
    private void printlnMsg(int number,TLMsg tmsg){
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String  dateStr ="";
        if(tmsg.getParam("datetime")!=null)
            dateStr ="("+ format.format(tmsg.getParam("datetime"))+")";
        String  lastdateStr =" lasttime:";
        if(tmsg.getParam("execDatetime")!=null)
            lastdateStr =" lasttime:"+ format.format(tmsg.getParam("execDatetime"));
        String  nextdateStr ="";
        if(tmsg.getParam("nextDatetime")!=null)
            nextdateStr ="  nexttime:"+ format.format(tmsg.getParam("nextDatetime"));
        String  execTimes =" runTimes:0";
        if(tmsg.getParam("execTimes")!=null)
            execTimes =" runTimes:"+ tmsg.getParam("execTimes");
        String content = number+". taskid: "+String.format("%1$-15s",tmsg.getParam("taskid"))
                +" status: "+String.format("%1$-31s",tmsg.getParam("status")+dateStr)
                 +String.format("%1$-31s",lastdateStr)
                + nextdateStr+execTimes;
        System.out.println(content);
    }
    private boolean checkTaskid (String taskid){
        if(!taskMsgTable.containsKey(taskid) && !taskid.equals("all"))
        {
            System.out.println("no taskid:" + taskid);
            return false ;
        }
        else
            return true ;

    }
    private boolean shutDownTocheckTaskid (String taskid){
        if(!taskMsgTable.containsKey(taskid) && !taskid.equals("all") && !taskid.equals("pool"))
        {
            System.out.println("no taskid:" + taskid);
            return false ;
        }
        else
            return true ;
    }
}
