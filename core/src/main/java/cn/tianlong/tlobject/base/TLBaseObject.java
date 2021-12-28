package cn.tianlong.tlobject.base;

import static java.lang.Thread.sleep;

/**
 * 创建日期：2018/3/4 on 8:46
 * 描述:
 * 作者:tianlong
 */

public abstract class TLBaseObject implements IObject ,TLParamString{
    protected String name;
    public TLBaseObject() {
         name=getClass().getSimpleName();
    }
    public TLBaseObject(String name )
    {
        this.name=name ;
    }

    protected TLMsg createMsg(){
        TLMsg cmsg =new TLMsg() ;
        cmsg.setSource(name);
        return  cmsg ;
    }
    public String getName() {
        return name;
    }
    public TLMsg putMsg(IObject toWho, TLMsg msg) {
        msg.setPrevious(name);
        if (msg.getWaitFlag()==true)
            return toWho.getMsg(this, msg);
        else
        {
            msg.setWaitFlag(true);
            int waitTime =msg.getIntParam(TASKWAITTIME,0);
            TLMsg returnMsg =  putMsgNoWait( toWho, msg) ;
            if (waitTime==0)
                return  returnMsg ;
            ThreadTask threadTask = (ThreadTask) returnMsg.getParam(THREADPOOL_TASK);
            try {
                sleep(waitTime);
                return createMsg().setParam(RESULT,false);
            } catch (InterruptedException e) {
               return threadTask.getResult();
            }
        }
    }
    /**  异步put****/
    public TLMsg putMsgNoWait(IObject toWho,TLMsg msg){
        if (!msg.isNull(TASKWAITTIME))
              msg.setParam(TASKMAINTHREAD,Thread.currentThread());
        ThreadTask threadTask=  new ThreadTask(toWho,msg,this);
        if(msg.parseBoolean(SESSIONDEAMON,false)==true)
        {
            msg.removeParam(SESSIONDEAMON);
            threadTask.setDaemon(true);
        }
        if(msg.getParam(EXCEPTIONHANDLER) !=null )
        {
            threadTask.setUncaughtExceptionHandler((Thread.UncaughtExceptionHandler) msg.getParam(EXCEPTIONHANDLER));
            msg.removeParam(EXCEPTIONHANDLER );
        }
        threadTask.start();
        if(msg.parseBoolean(SESSIONJOIN,false)==true)
        {
            msg.removeParam(SESSIONJOIN);
            try {
                long joinTime =msg.getLongParam(JOINTIME,0L);
                if(joinTime >0L)
               {
                   msg.removeParam(JOINTIME);
                   threadTask.join(joinTime);
               }
               else
                   threadTask.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return threadTask.getResult() ;
        }
        else
           return createMsg().setParam(THREADPOOL_TASK,threadTask);
    }

   protected class  ThreadTask extends Thread{
        private IObject toWho ;
        private IObject fromWho ;
        private TLMsg msg;
        private TLMsg returnMsg;
        private TLMsg exceptionMsg ;
        private TLMsg taskResultMsg ;
        private IObject taskResultFor ;
        private  Object taskSessionData ;
        private String taskResultAction ;
        private Thread mainThread ;
        protected Boolean isThreadOver =false ;
        public ThreadTask(IObject toWho ,TLMsg msg,IObject fromWho){
            this.toWho=toWho;
            this.msg =msg ;
            this.fromWho=fromWho;
            if(msg.getParam(EXCEPTIONMSG) !=null && msg.getParam(EXCEPTIONMSG) instanceof TLMsg)
                  exceptionMsg = (TLMsg) msg.getParam(EXCEPTIONMSG);
            if (!msg.isNull(TASKWAITTIME) && !msg.isNull(TASKMAINTHREAD))
            {
                mainThread = (Thread) msg.getParam(TASKMAINTHREAD);
                msg.removeParam(TASKWAITTIME);
                msg.removeParam(TASKMAINTHREAD);
            }
            if(!msg.isNull(TASKRESULTFOR) )
            {
                taskResultFor = (IObject) msg.getParam(TASKRESULTFOR);
                msg.removeParam(TASKRESULTFOR);
            }
            if(!msg.isNull(TASKRESULTMSG) )
            {
                taskResultMsg = (TLMsg) msg.getParam(TASKRESULTMSG);
                msg.removeParam(TASKRESULTMSG);
            }
            else if(!msg.isNull(TASKRESULTACTION) )
            {
                taskResultAction = (String) msg.getParam(TASKRESULTACTION);
                msg.removeParam(taskResultAction);
            }
            if(!msg.isNull(TASKRESESSIONDATA) )
            {
                taskSessionData=  msg.getParam(TASKRESESSIONDATA);
                msg.removeParam(TASKRESESSIONDATA);
            }
        }
       public void run() {
           try{
               if(msg.isNull(TASKDELAYTIME))
                   returnMsg=toWho.getMsg(fromWho,msg);
               else {
                   int time = (int) msg.getParam(TASKDELAYTIME);
                   sleep(time);
                   returnMsg=toWho.getMsg(fromWho,msg);
               }
               isThreadOver =true ;
               if(mainThread !=null)
               {
                   mainThread.interrupt();
                   return;
               }
               if(taskResultFor !=null)
               {
                   if(returnMsg==null)
                       returnMsg =new TLMsg();
                   if(taskResultMsg ==null){
                       returnMsg.setAction(taskResultAction);
                       if(taskSessionData!=null)
                           returnMsg.setParam(TASKRESESSIONDATA,taskSessionData);
                       putMsg(taskResultFor,returnMsg);
                   }
                   else
                   {
                       taskResultMsg.addArgs(returnMsg.getArgs());
                       putMsg(taskResultFor,taskResultMsg);
                   }
               }
           } catch (Exception e) {
               if(exceptionMsg!=null )
                   fromWho.getMsg(this,exceptionMsg.setParam("exception",e));
           }
       }
        public TLMsg getResult(){
          return returnMsg ;
        }
       public boolean isThreadOver(){
           return isThreadOver ;
       }
    }
}
