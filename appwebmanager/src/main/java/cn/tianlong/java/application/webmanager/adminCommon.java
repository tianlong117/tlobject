package cn.tianlong.java.application.webmanager;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.servletutils.TLWServModule;

import java.util.Map;


public abstract class adminCommon extends TLWServModule {
    final static String M_APPCLIENTAGENT = "appClientAgent";
    protected   String  appFactory ;
    public adminCommon(){
        super();
    }
    public adminCommon(String name ){
        super(name);
    }
    public adminCommon(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }
    protected void initProperty() {
        super.initProperty();
        if(params !=null && params.get("appConfigPath")!=null)
        if(params !=null && params.get("appFactory")!=null)
            appFactory=params.get("appFactory");
    }

    @Override
    protected TLBaseModule init() {
        return this ;
    }

    protected void errorInput(Object fromWho, TLMsg msg) {
        String message =(String)msg.getParam("paramName")+"输入错误 :"+msg.getParam("message");
        outData outData =creatOutDataMsg("errorInput");
        outData.addData("code","1");
        outData.addData("message",message);
        putOutData(outData);
    }
    protected TLMsg putResultToClient(int result ,String action){
        boolean resultBoolen =(result >=1 )?true : false ;
        return putResultToClient(resultBoolen,action);
    }
    protected TLMsg putResultToClient(boolean result ,String action){

        return  putResultToClient(result , action,null) ;
    }
    protected TLMsg putResultToClient(boolean result ,String action,String message ,Map<String,Object> data){
        outData outData =creatOutDataMsg(action);
        String code ;
        if(result){
            code ="0";
            message=(message!=null)? message:"操作成功";
        }
        else {
            code ="1";
            message=(message!=null)? message:"操作成功";
        }
        outData.addData("code",code);
        outData.addData("message", message);
        if(data!=null)
            outData.addData("data", data);
        putOutData(outData);
        return createMsg().setParam(RESULT,result).setParam("action",action) ;
    }

    protected TLMsg putResultToClient(boolean result ,String action,String  message){
      return putResultToClient( result ,action, message ,null) ;
    }
    protected TLBaseModule getAppfactory(){
        TLBaseModule factory = moduleFactory.getFactory(appFactory);
        if(factory ==null)
        {
            factory =  getAppFactory(appFactory);
            if(factory !=null)
            {
                moduleFactory.addFactory(appFactory,factory);
                Map<String,TLBaseModule> factorys =  moduleFactory.getFactorys();
                if(!factorys.isEmpty()){
                    for(String factoryName :factorys.keySet()){
                        TLObjectFactory sonfactory = (TLObjectFactory) factorys.get(factoryName);
                        sonfactory.addFactory(appFactory,factory);
                    }
                }
                return factory ;
            }
            else
                return null ;
        }
        return factory ;
    }
    protected String selectModule(String moduleName){
        TLBaseModule factory;
        if(params.get("loadAppModule")!=null && Boolean.parseBoolean(params.get("loadAppModule"))==true && appFactory !=null)
        {
            factory = moduleFactory.getFactory(appFactory);
            if(factory ==null)
            {
                factory =  getAppFactory(appFactory);
                if(factory !=null)
                {
                    moduleFactory.addFactory(appFactory,factory);
                    return moduleName ;
                }
                else
                    return null ;
            }
            else
                return moduleName ;
        }
        else
            return moduleName;
    }

    protected String getAppParams(String paramName){
        appClientAgent appClientAgent = (appClientAgent) getModule(M_APPCLIENTAGENT);
        TLMsg  returnMsg = putMsg(appClientAgent,createMsg().setAction(MODULE_GETPARAM));
        return (String) returnMsg.getParam(paramName);
    }
    protected TLMsg putCmdtoModule(String moduleName, String cmd ,String moduleIn,Boolean waitServerReturn ) {
        if(moduleIn==null)
            moduleIn ="factory" ;
        TLMsg msg = createMsg().setAction(cmd).setParam("waitServerReturn",waitServerReturn);
        return putMsgToModule(moduleName,msg,moduleIn);
    }
    public TLMsg putMsgToModule(String moduleName,TLMsg msg,String moduleIn){
        if(moduleIn==null)
            moduleIn ="factory";
        if(moduleIn.equals("factory"))
            return putMsg(moduleName,msg);
        else  if(moduleIn.equals("app"))
            return   putToApp(moduleName,msg);
        else {
            moduleName=moduleName+"@"+moduleIn;
            return   putToApp(moduleName,msg);
        }
    }
    protected TLMsg putToApp(String moduleName,TLMsg msg){
       appClientAgent appClientAgent = (appClientAgent) getModule(M_APPCLIENTAGENT);
       return appClientAgent.putToApp(moduleName,msg);
    }
    protected TLMsg putToAppFactory(String moduleName,TLMsg msg){
        appClientAgent appClientAgent = (appClientAgent) getModule(M_APPCLIENTAGENT);
        return appClientAgent.putToAppFactory(moduleName,msg);
    }
    protected TLMsg putToAllServer(String moduleName,TLMsg msg){
        appClientAgent appClientAgent = (appClientAgent) getModule(M_APPCLIENTAGENT);
        return appClientAgent.putToAllServer(moduleName,msg);
    }
    protected TLMsg putToServer(String moduleName,String server ,TLMsg msg){
        appClientAgent appClientAgent = (appClientAgent) getModule(M_APPCLIENTAGENT);
        return appClientAgent.putToServer(moduleName,server,msg);
    }
}
