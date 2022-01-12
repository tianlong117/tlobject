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
        return  putMsg(outInterface,msg.setAction("getOutContent"));
    }

    private TLMsg getInJson(Object fromWho, TLMsg msg) {
        return putMsg(M_JSONININTERFACE,msg.setAction(ININTERFACE_GETDATAFROMUSER));
    }

    private void recoveryOutInterface(Object fromWho, TLMsg msg) {
        outInterface = preOutInterface;
        putLog("outInterface 更换:"+outInterface,LogLevel.DEBUG);
    }
    private void recoveryInterface(Object fromWho, TLMsg msg) {
        outInterface = preOutInterface;
        inInterface = preInInterface;
    }
    private void recoveryInInterface(Object fromWho, TLMsg msg) {
        inInterface = preInInterface;
        putLog("inInterface 更换:"+inInterface,LogLevel.DEBUG);
    }
    private void setOutInterface(Object fromWho, TLMsg msg) {
        preOutInterface=outInterface;
        outInterface= (String) msg.getParam("outInterface");
        putLog("outInterface 更换:"+outInterface,LogLevel.DEBUG);
    }

    private void setinInterface(Object fromWho, TLMsg msg) {
        preInInterface=inInterface;
        inInterface= (String) msg.getParam("interface");
        putLog("inInterface 更换:"+inInterface,LogLevel.DEBUG);
    }
    private TLMsg getInContent(Object fromWho, TLMsg msg) {
        return  putMsg(inInterface,createMsg().setAction(ININTERFACE_GETCONTENTFROMUSER));
    }

    private TLMsg putContent(Object fromWho, TLMsg msg) {
        return putMsg(outInterface,msg.setAction(OUTINTERFACE_PUTCONTENTTOUSER));
    }
    private TLMsg putOutData(Object fromWho, TLMsg msg) {
        return   putMsg(outInterface,msg.setAction(OUTINTERFACE_PUTDATATOUSER));
    }
    protected TLMsg getInData(Object fromWho, TLMsg msg) {
        return putMsg(inInterface,msg.setAction(ININTERFACE_GETDATAFROMUSER));
    }

}
