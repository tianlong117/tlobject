package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * 创建日期：2018/4/23 on 21:00
 * 描述:
 * 作者:tianlong
 */
/**
 监控msg处理
 */
public  class TLMsgLog extends TLBaseModule {

    public TLMsgLog(String name ){
        super(name);
    }
    public TLMsgLog(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }
    @Override
    protected TLBaseModule init() {
        return this ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case "startLog":
                returnMsg=startLog(fromWho,msg);
                break;
            case "transferLog":
                returnMsg=transferLog(fromWho,msg);
                break;
            case "startLogOnRun":
                returnMsg=startLogOnRun(fromWho,msg);
                break;
            case "addLogModule" :
                returnMsg=addLogModule(fromWho,msg);
                break;
            case "fromFactoryGetModule" :
                returnMsg= fromFactoryGetModule(fromWho,msg);
                break;
            default:
        }
        return returnMsg;
    }

    protected TLMsg transferLog(Object fromWho, TLMsg msg) {
        TLMsg  domsg = (TLMsg) msg.getParam(DOWITHMAG);
        TLMsg  transferMsg = (TLMsg) domsg.getParam("msg");
        String transferMsgstr=msgToStr(transferMsg);
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now().withNano(0);
        putLog(today.toString()+" "+now.toString()+" "+"传输消息"+":"+transferMsgstr ,LogLevel.DEBUG,"transferLog");
        return domsg;
    }

    protected TLMsg fromFactoryGetModule(Object fromWho, TLMsg msg) {
        if (msgTable == null || msgTable.isEmpty()) return msg;
        TLMsg  returnMsg = (TLMsg) msg.getParam(PRERESULT);
        if(returnMsg ==null || returnMsg.getParam("new")==null) return msg;
        String moduleName= (String) returnMsg.getParam("moduleName");
        if(moduleName ==null || moduleName==name) return msg;
        ArrayList<TLMsg> msgList = msgTable.get(moduleName);//取出msgid对应的信息路由表
        if (msgList == null) return msg;
        TLMsg logmsg=new TLMsg().setDestination(name).setAction("startLog");
        for (int i = 0; i < msgList.size(); i++) {
            TLMsg addlogMsg =createMsg();
            addlogMsg.setAction(msgList.get(i).getAction());
            addlogMsg.setParam("msg",logmsg);
            addlogMsg.setParam("position",msgList.get(i).getParam("postion"));
            addlogMsg.setParam("action",msgList.get(i).getParam("maction"));
            putMsg((IObject) returnMsg.getParam("instance"),addlogMsg);
            putLog("监听 :"+moduleName+"->"+msgList.get(i).getParam("maction"),LogLevel.DEBUG);
        }
        return returnMsg;
    }

   protected TLMsg startLogOnRun(Object fromWho, TLMsg msg) {
        TLMsg logmsg=new TLMsg().setDestination(name)
                .setAction("fromFactoryGetModule");
        TLMsg fmsg=createMsg().setAction("addAfterMsg").setParam("action","getModule")
                .setParam("msg",logmsg).setParam("position",0);
        putMsg(moduleFactory,fmsg);
        return msg;
    }

    protected TLMsg addLogModule(Object fromWho, TLMsg msg) {
        TLMsg logmsg=new TLMsg().setDestination(name)
                .setAction("startLog");
        TLMsg addmsg=createMsg().setAction((String) msg.getParam("type")).setParam("action",msg.getParam("maction"))
                .setParam("msg",logmsg);   //动态添加aftertable
        putMsg((String) msg.getParam("logModule"),addmsg);
        putLog("监听 :"+(String) msg.getParam("logModule")+"->"+msg.getParam("maction"),LogLevel.DEBUG);
        return addmsg;
    }
    protected TLMsg startLog(Object fromWho, TLMsg msg) {
        TLMsg  domsg = (TLMsg) msg.getParam(DOWITHMAG);
        TLMsg  returnMsg = (TLMsg) msg.getParam(PRERESULT);
        TLMsg  netxMsg =  msg.getNextMsg();
        String domsgStr=msgToStr(domsg);
        String netxMsgstr=msgToStr(netxMsg);
        String returnMsgstr=msgToStr(returnMsg);
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now().withNano(0);
        putLog(today.toString()+" "+now.toString()+" "+"执行消息"+":"+domsgStr ,LogLevel.DEBUG);
        putLog(today.toString()+" "+now.toString()+" "+"nextMsg"+":"+netxMsgstr ,LogLevel.DEBUG);
        putLog("       返回消息"+":"+returnMsgstr,LogLevel.DEBUG);
        return domsg;
    }
    private String msgToStr(TLMsg msg){
        if(msg ==null ||(msg instanceof  TLMsg)==false)
            return "";
        StringBuffer logBuffer = new StringBuffer();
        logBuffer.append(" 当前对象: ");
        logBuffer.append(msg.getNowObject());
        logBuffer.append(" 当前动作: ");
        logBuffer.append(msg.getAction());
        logBuffer.append(" 消息源: ");
        logBuffer.append(msg.getSource());
        logBuffer.append(" 消息目的: ");
        logBuffer.append(msg.getDestination());
        logBuffer.append(" 上一个处理对象: ");
        logBuffer.append(msg.getPrevious());
        logBuffer.append(" 参数： ");
        HashMap<String ,Object> args=msg.getArgs();
        for (String key : args.keySet()) {
            if(args.get(key) instanceof  String)
            {
                String content=(String)args.get(key);
                if(content.length()>50)
                    content=content.substring(0,20)+"....";
                logBuffer.append(key);
                logBuffer.append(":");
                logBuffer.append(content);
                logBuffer.append(";");
            }
            else if(args.get(key) instanceof  TLMsg) {
                logBuffer.append(key);
                logBuffer.append(":参数消息 ");
                logBuffer.append(msgToStr((TLMsg) args.get(key)));
            }
            else {
                logBuffer.append(key);
                logBuffer.append(":非字符串 ");
            }
        }
        return   logBuffer.toString();
    }
}
