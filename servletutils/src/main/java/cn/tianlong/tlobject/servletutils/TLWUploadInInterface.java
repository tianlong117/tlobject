package cn.tianlong.tlobject.servletutils;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import java.util.HashMap;

import static cn.tianlong.tlobject.servletutils.TLParamString.*;

public class TLWUploadInInterface extends TLBaseClientDataInInterface {

    public TLWUploadInInterface(){
        super();
    }
    public TLWUploadInInterface(String name ){
        super(name);
    }
    public TLWUploadInInterface(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }


    @Override
    protected TLBaseModule init() {
        return this ;
    }

    @Override
    protected TLMsg getContentFromUser(Object fromWho, TLMsg msg) {
       return null ;
    }

    @Override
    protected TLMsg getDataFromUser(Object fromWho, TLMsg msg) {
        TLMsg umsg = createMsg().setAction(UPLOADFILE_UPLOAD)
                .setParam(UPLOADFILE_P_FILESIZE, params.get(UPLOADFILE_P_FILESIZE))
                .setParam(UPLOADFILE_P_FILETYPES ,params.get(UPLOADFILE_P_FILETYPES ))
                .setParam(UPLOADFILE_P_FILEPATH, params.get(UPLOADFILE_P_FILEPATH));
        TLMsg returnMsg= putMsg(M_UPLOADFILE, umsg);
        HashMap<String ,String> dataMap= (HashMap<String, String>) returnMsg.getParam(UPLOADFILE_R_UPLOADPARAMS);
        if(dataMap !=null)
        {
            String[] varname = (String[]) msg.getParam(CLIENT_P_VARNAME);
            if(varname ==null || varname.length ==0)
            {
                for (String key : dataMap.keySet())
                    returnMsg.setParam(key,dataMap.get(key));
            }
            else{
                for(String key : varname)
                    returnMsg.setParam(key,dataMap.get(key));
            }
        }
        return  returnMsg ;
    }
}
