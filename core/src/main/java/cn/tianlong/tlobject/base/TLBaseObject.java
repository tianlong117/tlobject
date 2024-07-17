package cn.tianlong.tlobject.base;

import cn.tianlong.tlobject.utils.TLDataUtils;

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

    public TLMsg createMsg(){
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
            if (msg.systemParamIsNull(TASKWAITTIME))
                return  putMsgNoWait( toWho, msg) ;
            msg.setSystemParam(TASKMAINTHREAD,Thread.currentThread());
            int waitTime = TLDataUtils.getIntParam(msg.getSystemParam(TASKWAITTIME),0) ;
            TLMsg returnMsg =  putMsgNoWait( toWho, msg) ;
            if (waitTime <=0 )
                waitTime =Integer.MAX_VALUE ;
            ThreadTask threadTask = (ThreadTask) returnMsg.getParam(THREADPOOL_TASK);
            try {
                sleep(waitTime);
            } catch (InterruptedException e) {
               return threadTask.getResult();
            }
            return createMsg().setParam(TASKRESULTTIMEOUT,true);
        }
    }
    /**  异步put****/
    public TLMsg putMsgNoWait(IObject toWho,TLMsg msg){
        ThreadTask threadTask=  new ThreadTask(toWho,msg,this);
        if(TLDataUtils.parseBoolean(msg.getSystemParam(IFTASKDEAMON),false)==true)
            threadTask.setDaemon(true);
        if(msg.getSystemParam(EXCEPTIONHANDLER) !=null )
            threadTask.setUncaughtExceptionHandler((Thread.UncaughtExceptionHandler) msg.getSystemParam(EXCEPTIONHANDLER));
        threadTask.start();
        if(TLDataUtils.parseBoolean(msg.getSystemParam(IFTASKJOIN),false)==true)
        {
             try {
                long joinTime =TLDataUtils.parseLong(msg.getSystemParam(JOINTIME),0L);
                if(joinTime >0L)
                    threadTask.join(joinTime);
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
        protected Boolean ifTaskResult =false ;
        public ThreadTask(IObject toWho ,TLMsg msg,IObject fromWho){
            this.toWho=toWho;
            this.msg =msg ;
            this.fromWho=fromWho;
            exceptionMsg = (TLMsg) msg.getSystemParam(EXCEPTIONMSG);
            if (!msg.systemParamIsNull(TASKMAINTHREAD) )
                mainThread = (Thread) msg.getAndRemoveSystemParam(TASKMAINTHREAD);
            taskResultFor = (IObject) msg.getAndRemoveSystemParam(TASKRESULTFOR);
            taskResultAction = (String) msg.getAndRemoveSystemParam(TASKRESULTACTION);
            if(taskResultAction==null )
               taskResultMsg = (TLMsg)  msg.getAndRemoveSystemParam(TASKRESULTMSG);
            if(!msg.systemParamIsNull(IFTASKRESULT))
               ifTaskResult = (Boolean) msg.getAndRemoveSystemParam(IFTASKRESULT);
            taskSessionData=  msg.getSystemParam(TASKRESESSIONDATA);
        }
       public void run() {
           try{
               if(msg.systemParamIsNull(TASKDELAYTIME))
                   returnMsg=toWho.getMsg(fromWho,msg);
               else {
                   int time = (int) msg.getAndRemoveSystemParam(TASKDELAYTIME);
                   sleep(time);
                   returnMsg=toWho.getMsg(fromWho,msg);
               }
               if(ifTaskResult)
                   msg.setSystemParam(TASKRESULT,returnMsg);
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
                           returnMsg.setSystemParam(TASKRESESSIONDATA,taskSessionData);
                       putMsg(taskResultFor,returnMsg);
                   }
                   else
                   {
                       if(returnMsg!=null)
                          taskResultMsg.addArgs(returnMsg.getArgs());
                       if(taskSessionData!=null)
                          taskResultMsg.setSystemParam(TASKRESESSIONDATA,taskSessionData);
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
