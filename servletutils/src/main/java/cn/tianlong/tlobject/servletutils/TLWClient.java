package cn.tianlong.tlobject.servletutils;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import static cn.tianlong.tlobject.servletutils.TLParamString.*;

public class TLWClient extends TLWServModule {
    protected String outInterface;
    protected String preOutInterface;
    protected String inInterface;
    protected String preInInterface;

    // 运行期切换的接口名按请求线程存（模块是单例，实例字段会被并发请求互相覆盖）。
    // 实例字段只作为配置默认值。
    private static final String TD_OUT = "__tlw_outInterface";
    private static final String TD_OUT_PRE = "__tlw_preOutInterface";
    private static final String TD_IN = "__tlw_inInterface";
    private static final String TD_IN_PRE = "__tlw_preInInterface";

    protected String currentOutInterface() {
        Object v = getThreadData(TD_OUT);
        return v instanceof String ? (String) v : outInterface;
    }
    protected String currentInInterface() {
        Object v = getThreadData(TD_IN);
        return v instanceof String ? (String) v : inInterface;
    }
    public TLWClient(){
        super();
    }
    public TLWClient(String name ){
        super(name);
    }
    public TLWClient(String name ,TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        if( params.get("outInterface")!=null)
            outInterface=params.get("outInterface");
        else
            outInterface=M_VELOCITY;
        if( params.get("inInterface")!=null)
            inInterface=params.get("inInterface");
        else
            inInterface=M_DIRECTININTERFACE;
    }
    @Override
    protected TLBaseModule init() {
        return this ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case WEBCLIENT_SETININTERFACE:
                setinInterface(fromWho,msg);
                break;
            case WEBCLIENT_SETOUTINTERFACE:
                setOutInterface(fromWho,msg);
                break;
            case WEBCLIENT_RECOVERYINTERFACE:
                recoveryInterface(fromWho,msg);
                break;
            case WEBCLIENT_RECOVERYOUTINTERFACE:
                recoveryOutInterface(fromWho,msg);
                break;
            case WEBCLIENT_RECOVERYIINTERFACE:
                recoveryInInterface(fromWho,msg);
                break;
            case WEBCLIENT_GETINDATA:
                returnMsg=getInData(fromWho,msg);
                break;
            case WEBCLIENT_GETINJSON:
                returnMsg=getInJson(fromWho,msg);
                break;
            case WEBCLIENT_GETINCONTENT:
                returnMsg=getInContent(fromWho,msg);
                break;
            case WEBCLIENT_PUTOUTDATA:
                returnMsg=putOutData(fromWho,msg);
                break;
            case WEBCLIENT_PUTCONTENT:
                returnMsg=putContent(fromWho,msg);
                break;
            case WEBCLIENT_GETOUTCONTENT:
                returnMsg=getOutContent(fromWho,msg);
                break;
            default:

        }
        return returnMsg ;
    }

    private TLMsg getOutContent(Object fromWho, TLMsg msg) {
        return  putMsg(currentOutInterface(),msg.setAction("getOutContent"));
    }

    private TLMsg getInJson(Object fromWho, TLMsg msg) {
        return putMsg(M_JSONININTERFACE,msg.setAction(ININTERFACE_GETDATAFROMUSER));
    }

    private void recoveryOutInterface(Object fromWho, TLMsg msg) {
        Object pre = getThreadData(TD_OUT_PRE);
        setThreadData(TD_OUT, pre instanceof String ? pre : outInterface);
        putLog("outInterface 恢复:"+currentOutInterface(),LogLevel.DEBUG);
    }
    private void recoveryInterface(Object fromWho, TLMsg msg) {
        recoveryOutInterface(fromWho, msg);
        recoveryInInterface(fromWho, msg);
    }
    private void recoveryInInterface(Object fromWho, TLMsg msg) {
        Object pre = getThreadData(TD_IN_PRE);
        setThreadData(TD_IN, pre instanceof String ? pre : inInterface);
        putLog("inInterface 恢复:"+currentInInterface(),LogLevel.DEBUG);
    }
    private void setOutInterface(Object fromWho, TLMsg msg) {
        setThreadData(TD_OUT_PRE, currentOutInterface());
        Object v = msg.getParam("outInterface");
        setThreadData(TD_OUT, v instanceof String ? v : outInterface);
        putLog("outInterface 更换:"+currentOutInterface(),LogLevel.DEBUG);
    }

    private void setinInterface(Object fromWho, TLMsg msg) {
        setThreadData(TD_IN_PRE, currentInInterface());
        Object v = msg.getParam("interface");
        setThreadData(TD_IN, v instanceof String ? v : inInterface);
        putLog("inInterface 更换:"+currentInInterface(),LogLevel.DEBUG);
    }
    private TLMsg getInContent(Object fromWho, TLMsg msg) {
        return  putMsg(currentInInterface(),createMsg().setAction(ININTERFACE_GETCONTENTFROMUSER));
    }

    private TLMsg putContent(Object fromWho, TLMsg msg) {
        return putMsg(currentOutInterface(),msg.setAction(OUTINTERFACE_PUTCONTENTTOUSER));
    }
    private TLMsg putOutData(Object fromWho, TLMsg msg) {
        return   putMsg(currentOutInterface(),msg.setAction(OUTINTERFACE_PUTDATATOUSER));
    }
    protected TLMsg getInData(Object fromWho, TLMsg msg) {
        return putMsg(currentInInterface(),msg.setAction(ININTERFACE_GETDATAFROMUSER));
    }

}
