package cn.tianlong.tlobject.servletutils.clientinterface;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.Map;

import static cn.tianlong.tlobject.servletutils.TLParamString.CLIENT_P_VARNAME;
import static cn.tianlong.tlobject.servletutils.TLParamString.CLIENT_R_CONTENT;

public class TLWDirectInInterface extends TLBaseClientDataInInterface {

    public TLWDirectInInterface(){
        super();
    }
    public TLWDirectInInterface(String name ){
        super(name);
    }
    public TLWDirectInInterface(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }


    @Override
    protected TLBaseModule init() {
        return this ;
    }

    @Override
    protected TLMsg getContentFromUser(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=createMsg();
        HttpServletRequest request =getRequest();
        if (request == null) {
            // 请求上下文已失效（非请求线程 / 服务关闭瞬间线程回收）：返回空内容，避免 NPE
            return returnMsg;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(request.getInputStream(),charset))) {
            String line;
            StringBuilder sb = new StringBuilder();
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return returnMsg.setParam(CLIENT_R_CONTENT, sb.toString());
        } catch (IOException e) {
            e.printStackTrace();
            return returnMsg;
        }
    }

    @Override
    protected TLMsg getDataFromUser(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=createMsg();
        HttpServletRequest request =getRequest();
        try {
            request.setCharacterEncoding(charset);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        String[] varname = (String[]) msg.getArrayParam(CLIENT_P_VARNAME,null);
        if(varname ==null || varname.length ==0)
            return returnMsg;
        Map<String, String[]> dataMap = request.getParameterMap();
        if(dataMap ==null || dataMap.isEmpty())
            return returnMsg ;
        if(varname[0].equals("*"))
        {
            for (String key : dataMap.keySet()) {
                String[] value = dataMap.get(key);
                if(value.length==1)
                    returnMsg.setParam(key,value[0]);
                else
                    returnMsg.setParam(key,value);
            }
        }
        else{
            for(String var : varname){
                String[] value = dataMap.get(var);
                if(value ==null)
                    continue;
                if(value.length==1)
                    returnMsg.setParam(var,value[0]);
                else
                    returnMsg.setParam(var,value);
            }
        }
        return returnMsg;
    }
}
