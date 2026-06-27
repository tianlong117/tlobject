package cn.tianlong.tlobject.servletutils.clientinterface;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.servletutils.TLWServModule;

import java.util.LinkedHashMap;

import static cn.tianlong.tlobject.servletutils.TLParamString.CHARSET;
import static cn.tianlong.tlobject.servletutils.TLParamString.CLIENT_P_CONTENT;
import static cn.tianlong.tlobject.servletutils.TLParamString.CLIENT_P_OUDDATA;

public class TLWDirectOutInterface extends TLBaseClientDataOutInterface {

    public TLWDirectOutInterface(){
        super();
    }
    public TLWDirectOutInterface(String name ){
        super(name);
    }
    public TLWDirectOutInterface(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected TLBaseModule init() {
        return this ;
    }

    @Override
    protected TLMsg putContentToUser(Object fromWho, TLMsg msg) {
        String outContent = (String) msg.getParam(CLIENT_P_CONTENT);
        if(outContent==null)
            return null;
        return  responseWrite(outContent,(String) msg.getParam(CHARSET));
    }

    @Override
    protected TLMsg putDataToUser(Object fromWho, TLMsg msg) {
        outData outData = (TLWServModule.outData) msg.getParam(CLIENT_P_OUDDATA);
        LinkedHashMap<String, Object> datas =(LinkedHashMap<String, Object>)outData.getParam(CLIENT_P_OUDDATA);
        if(datas==null)
            return null;
        StringBuilder buffer = new StringBuilder();
        for (String key : datas.keySet()) {
            buffer.append((String)datas.get(key).toString());
        }
        String outContent=buffer.toString();
        return  responseWrite(outContent,(String) msg.getParam(CHARSET));
    }
}
