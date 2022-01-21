package cn.tianlong.java.demo.task;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 创建日期：2019/1/318:58
 * 描述:
 * 作者:tianlong
 */
public class PcBoy extends TLBaseModule {
    private  int  i=3;
    public PcBoy(String name, TLObjectFactory moduleFactory)  {
        super(name,moduleFactory);
    }
    @Override
    protected TLBaseModule init() {
        return this ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "mysleep":
                mysleep(fromWho, msg);
                break;
            case "eat":
                eat(fromWho, msg);
                break;
            case "play":
                play(fromWho, msg);
                break;
            case "study":
                study(fromWho, msg);
                break;
            default:
        }
        return returnMsg;
    }
    private void play(Object fromWho, TLMsg msg) {
        say("我在玩");
    }
    private void eat(Object fromWho, TLMsg msg) {

        say("我在吃饭，好好吃");
        i-- ;
        int m =2/i;
    }
    private void mysleep(Object fromWho, TLMsg msg) {
        say("我在睡觉。。。");
    }
    private void study(Object fromWho, TLMsg msg) {
        say("我开始学习。。。");
    }
    private void say(String content)
    {
        Date data = new Date();
        SimpleDateFormat ft = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        System.out.println("时间:" + ft.format(data) +" "+name+"说:"+content);
    }
}
