package cn.tianlong.java.demo.base;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

public abstract class DemoCommon extends TLBaseModule {

    public DemoCommon(String name) {
        super(name);
    }

    public DemoCommon(String name, TLObjectFactory moduleFactory) {
        super(name, moduleFactory);
        System.out.println("/* 模块创建: "+name +" */");
    }

    protected TLMsg printAction( TLMsg msg) {
        System.out.println("/* 模块: "+name +",执行消息，action:"+msg.getAction()+" */");
        return null;
    }
   protected void printState(String state) {

       System.out.println("模块: "+name +" 状态:"+state);
    }

}
