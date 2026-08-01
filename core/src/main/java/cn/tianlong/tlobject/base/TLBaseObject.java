package cn.tianlong.tlobject.base;

import cn.tianlong.tlobject.utils.TLDataUtils;

import java.util.concurrent.CountDownLatch;

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
                if (threadTask != null)
                    return threadTask.getResult();
                return createMsg().setParam(TASKRESULTTIMEOUT,true);
            }
            if (threadTask != null && threadTask.isThreadOver())
                return threadTask.getResult();
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
                Thread.currentThread().interrupt();
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
        protected volatile boolean isThreadOver =false ;
        protected Boolean ifTaskResult =false ;
        private CountDownLatch doneSignal;
        /** 暂停标志，pauseTask() 置 true，resumeTask() 置 false */
        volatile boolean paused = false;
        /** 暂停等待锁 */
        final Object pauseLock = new Object();
        /** 当前线程正在执行的 ThreadTask，供模块内部检查暂停状态 */
        static final ThreadLocal<ThreadTask> currentTask = new ThreadLocal<>();
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
           currentTask.set(this);
           try{
               // 暂停检查点 1 — 执行前
               checkPause();
               if (isThreadOver) return;

               if(msg.systemParamIsNull(TASKDELAYTIME))
                   returnMsg=toWho.getMsg(fromWho,msg);
               else {
                   int time = (int) msg.getAndRemoveSystemParam(TASKDELAYTIME);
                   sleep(time);
                   // 暂停检查点 2 — 延迟后执行前
                   checkPause();
                   if (isThreadOver) return;
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
           } finally {
               currentTask.remove();
               if (doneSignal != null)
                   doneSignal.countDown();
           }
       }
       public TLMsg getResult(){
          return returnMsg ;
        }
       public boolean isThreadOver(){
           return isThreadOver ;
       }
       public void setDoneSignal(CountDownLatch latch) {
           this.doneSignal = latch;
       }
       /**
        * 暂停检查点 — 若当前任务被暂停则阻塞等待，直到 resume 或 cancel
        */
       private void checkPause() {
           synchronized (pauseLock) {
               while (paused && !isThreadOver) {
                   try {
                       pauseLock.wait(1000);
                   } catch (InterruptedException e) {
                       Thread.currentThread().interrupt();
                       break;
                   }
               }
           }
       }
       /** 暂停当前任务 */
       public void pauseTask() { paused = true; }
       /** 恢复当前任务 */
       public void resumeTask() {
           paused = false;
           synchronized (pauseLock) { pauseLock.notifyAll(); }
       }
       /** 查询是否已暂停 */
       public boolean isPaused() { return paused; }
       /** 取消任务 — 标记结束并唤醒暂停 */
       public void cancelTask() {
           isThreadOver = true;
           resumeTask();
       }
       /** 获取当前线程正在执行的 ThreadTask（供模块内部检查暂停状态） */
       public static ThreadTask current() { return currentTask.get(); }
       /**
        * 模块内部暂停检查点 — 一行调用即可。
        * 若当前线程跑在 ThreadTask 里且该任务已被暂停，则阻塞直到 resume 或 cancel。
        * 未跑在 ThreadTask 里（比如同步调用路径）则直接返回，不做任何事。
        */
       public static void checkPauseHere() {
           ThreadTask task = currentTask.get();
           if (task == null) return;
           synchronized (task.pauseLock) {
               while (task.paused && !task.isThreadOver) {
                   try {
                       task.pauseLock.wait(1000);
                   } catch (InterruptedException e) {
                       Thread.currentThread().interrupt();
                       break;
                   }
               }
           }
       }
       /** 当前线程的 ThreadTask 是否已被取消 */
       public static boolean isCurrentCancelled() {
           ThreadTask task = currentTask.get();
           return task != null && task.isThreadOver;
       }
    }
}
