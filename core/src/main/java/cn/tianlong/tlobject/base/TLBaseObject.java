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
            int waitTime =-1 ;
            if (!msg.isNull(TASKWAITTIME))
            {
                msg.setParam(TASKMAINTHREAD,Thread.currentThread());
                waitTime =  msg.parseInt(TASKWAITTIME,-1);
            }
            TLMsg returnMsg =  putMsgNoWait( toWho, msg) ;
            if (waitTime==-1)
                return  returnMsg ;
            if(waitTime ==0)
                waitTime =Integer.MAX_VALUE ;
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
            if (!msg.isNull(TASKMAINTHREAD) && !msg.isNull(TASKWAITTIME))
            {
                mainThread = (Thread) msg.getAndRemoveParam(TASKMAINTHREAD);
                msg.removeParam(TASKWAITTIME);
            }
            if(!msg.isNull(TASKRESULTFOR) )
                taskResultFor = (IObject) msg.getAndRemoveParam(TASKRESULTFOR);
            if(!msg.isNull(TASKRESULTMSG) )
                taskResultMsg = (TLMsg)  msg.getAndRemoveParam(TASKRESULTMSG);
            else if(!msg.isNull(TASKRESULTACTION) )
                taskResultAction = (String) msg.getAndRemoveParam(TASKRESULTACTION);
            if(!msg.isNull(TASKRESESSIONDATA) )
                taskSessionData=  msg.getAndRemoveParam(TASKRESESSIONDATA);
        }
       public void run() {
           try{
               if(msg.isNull(TASKDELAYTIME))
                   returnMsg=toWho.getMsg(fromWho,msg);
               else {
                   int time =  msg.getIntParam(TASKDELAYTIME,0);
                   sleep(time);
                   returnMsg=toWho.getMsg(fromWho,msg);
               }
               isThreadOver =true ;
               if(mainThread !=null)
                   mainThread.interrupt();
               if(taskResultFor !=null)
               {
                   if(taskResultMsg ==null)
                   {
                       if(returnMsg==null)
                           returnMsg =new TLMsg();
                       returnMsg.setAction(taskResultAction);
                       if(taskSessionData!=null)
                           returnMsg.setParam(TASKRESESSIONDATA,taskSessionData);
                       putMsg(taskResultFor,returnMsg);
                   }
                   else
                   {
                       if(returnMsg!=null)
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
