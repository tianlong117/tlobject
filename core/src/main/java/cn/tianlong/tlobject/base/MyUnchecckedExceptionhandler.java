package cn.tianlong.tlobject.base;


public class MyUnchecckedExceptionhandler implements Thread.UncaughtExceptionHandler {
    private IObject module;
    private TLMsg msg;

    public  MyUnchecckedExceptionhandler(IObject module, TLMsg msg){
        this.module =module;
        this.msg =msg ;
    }
    @Override
    public void uncaughtException(Thread t, Throwable e) {
        module.putMsg(module,msg.setParam("throwable" ,e));
    }
}