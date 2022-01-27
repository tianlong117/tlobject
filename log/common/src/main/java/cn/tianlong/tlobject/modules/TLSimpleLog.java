package cn.tianlong.tlobject.modules;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.utils.TLDateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TLSimpleLog extends TLBaseLog {
    protected  Logger logger;

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
        String time =TLDateUtils.getNowDateStr(null);
        StringBuilder logBuffer = new StringBuilder();
        logBuffer.append(time);
        logBuffer.append("  ");
        logBuffer.append(logLevel.toString());
        logBuffer.append("  ");
        logBuffer.append(content);
        println(logBuffer.toString());
    }
}
