package cn.tianlong.tlobject.modules;


import cn.tianlong.tlobject.base.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TLSlf4jLog extends TLBaseLog {
    protected   String log4j2Config="log4j2.xml";
    protected  Logger logger;

    public TLSlf4jLog(){
        super();

    }
    public TLSlf4jLog(String name ){
        super(name);

    }
    public TLSlf4jLog(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);

    }
    @Override
    protected void initProperty(){
        super.initProperty();
        if(params!=null && params.get("log4j2Config")!=null )
         log4j2Config=params.get("log4j2Config");

    }
    @Override
    protected TLBaseModule init() {
        System.setProperty("log4j.configurationFile", moduleFactory.getConfigDir() + log4j2Config);
        logger = LoggerFactory.getLogger("tlobject");
        return  this ;
    }
    @Override
    protected void setLog0(String content, LogLevel logLevel) {
        switch (logLevel){
            case TRACE:
                logger.trace(content);
                break;
            case DEBUG:
                logger.debug(content);
                break;
            case INFO:
                logger.info(content);
                break;
            case WARN:
                logger.warn(content);
                break;
            case ERROR:
                logger.error(content);
                break;
            default:
                logger.info(content);
        }
    }
}
