package cn.tianlong.tlobject.base;

import cn.tianlong.tlobject.utils.TLDataUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
       /** 共享 worker 线程池，daemon 线程，供超时 task 的 getMsg 外包执行 */
       private static final ExecutorService timeoutWorkerPool =
               Executors.newCachedThreadPool(r -> {
                   Thread t = new Thread(r, "task-timeout-worker");
                   t.setDaemon(true);
                   return t;
               });

       public void run() {
           currentTask.set(this);
           Object timeoutObj = msg.getAndRemoveSystemParam(TASKTIMEOUT);
           int timeout = timeoutObj instanceof Number ? ((Number) timeoutObj).intValue() : 0;
           try{
               // 暂停检查点 1 — 执行前
               checkPause();
               if (isThreadOver) return;

               // 核心执行：有超时则用 Future 外包给共享线程池，当前线程限时等待
               if (timeout > 0) {
                   final int timeoutMs = timeout;
                   Future<TLMsg> future = timeoutWorkerPool.submit(() -> {
                       currentTask.set(ThreadTask.this);
                       try { return doExecute(timeoutMs); } finally { currentTask.remove(); }
                   });
                   try {
                       returnMsg = future.get(timeout, TimeUnit.MILLISECONDS);
                   } catch (java.util.concurrent.TimeoutException e) {
                       future.cancel(true);
                       returnMsg = new TLMsg().setParam(TASKTIMEOUT, true);
                   }
               } else {
                   returnMsg = doExecute(0);
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

       private TLMsg doExecute(int timeoutMs) throws InterruptedException {
           long startTime = System.currentTimeMillis();
           TLMsg result;
           if (msg.systemParamIsNull(TASKDELAYTIME))
               result = toWho.getMsg(fromWho, msg);
           else {
               int time = (int) msg.getAndRemoveSystemParam(TASKDELAYTIME);
               sleep(time);
               // 暂停检查点 2 — 延迟后执行前
               checkPause();
               if (isThreadOver) return new TLMsg();
               result = toWho.getMsg(fromWho, msg);
           }
           // 即使 getMsg 正常返回，只要超过时限就标记超时（解决工作在超时边界刚好完成的问题）
           if (timeoutMs > 0 && System.currentTimeMillis() - startTime > timeoutMs)
               return new TLMsg().setParam(TASKTIMEOUT, true);
           return result;
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
           if (doneSignal != null) doneSignal.countDown();
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
