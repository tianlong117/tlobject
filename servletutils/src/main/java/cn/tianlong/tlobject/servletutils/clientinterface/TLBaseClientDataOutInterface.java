package cn.tianlong.tlobject.servletutils.clientinterface;


import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.servletutils.TLWServModule;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;

import static cn.tianlong.tlobject.servletutils.TLParamString.*;

public abstract class TLBaseClientDataOutInterface extends TLWServModule {
    protected String charset;
    public TLBaseClientDataOutInterface(){
        super();
    }
    public TLBaseClientDataOutInterface(String name ) {
        super(name);
    }
    public TLBaseClientDataOutInterface(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }
    @Override
    protected void initProperty() {
        super.initProperty();
        if (params != null && params.get(CHARSET) != null)
             charset = params.get(CHARSET);
        if(charset ==null)
        {
            HashMap<String,String> appParams=getAppParams();
            if(appParams.get(CHARSET)!=null)
                charset =appParams.get(CHARSET);
        }
    }
    @Override
    public TLMsg getMsg(Object fromWho, TLMsg msg) {
        return  runAction(fromWho,msg);
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case OUTINTERFACE_PUTDATATOUSER:
                returnMsg=putDataToUser(fromWho,msg);
                break;
            case OUTINTERFACE_PUTCONTENTTOUSER:
                returnMsg=putContentToUser(fromWho,msg);
                break;
            default:

        }
        return returnMsg ;
    }
    protected TLMsg responseWrite(String content , String charset){
        if(charset ==null)
            charset =this.charset ;
        PrintWriter out;
        HttpServletResponse response =getResponse();
        response.setContentType("text/html;charset=" + (charset != null ? charset : "UTF-8"));
        try {
            out = response.getWriter();
        } catch (IOException e) {
            e.printStackTrace();
            return createMsg().setParam(CLIENT_R_OUTCONTENT ,null);
        }
        out.write( content);
        out.close();
        return createMsg().setParam(CLIENT_R_OUTCONTENT ,content);
    }
    protected abstract TLMsg putContentToUser(Object fromWho, TLMsg msg);

    protected abstract TLMsg putDataToUser(Object fromWho, TLMsg msg) ;
}
