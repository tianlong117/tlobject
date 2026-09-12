package cn.tianlong.tlobject.base;


public class MyUncheckedExceptionhandler implements Thread.UncaughtExceptionHandler {
    private IObject module;
    private TLMsg msg;

    public  MyUncheckedExceptionhandler(IObject module, TLMsg msg){
        this.module =module;
        this.msg =msg ;
    }
    @Override
    public void uncaughtException(Thread t, Throwable e) {
        module.putMsg(module,msg.setParam("throwable" ,e));
    }
}
