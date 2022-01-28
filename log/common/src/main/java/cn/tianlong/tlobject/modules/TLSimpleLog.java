package cn.tianlong.tlobject.modules;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.text.SimpleDateFormat;
import java.util.Date;

public class TLSimpleLog extends TLBaseLog {

    public TLSimpleLog(){
        super();

    }
    public TLSimpleLog(String name ){
        super(name);

    }
    public TLSimpleLog(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);

    }
    @Override
    protected void initProperty(){
        super.initProperty();

    }
    @Override
    protected TLBaseModule init() {

        return  this ;
    }
    @Override
    protected void setLog0(String content, LogLevel logLevel) {
        Date date = new Date();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String time = format.format(date);
        StringBuilder logBuffer = new StringBuilder();
        logBuffer.append(time);
        logBuffer.append("  ");
        logBuffer.append(logLevel.toString());
        logBuffer.append("  ");
        logBuffer.append(content);
        println(logBuffer.toString());
    }
}
