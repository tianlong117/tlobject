package cn.tianlong.java.application.webmanager;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class moduleManagerControl extends adminCommon {

     public moduleManagerControl() {
        super();
    }

    public moduleManagerControl(String name) {
        super(name);
    }

    public moduleManagerControl(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected TLBaseModule init() {
        return this;    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "index":
                index(fromWho, msg);
                break;
            case "runModules":
                runModules(fromWho, msg);
                break;
            case "runModulesList":
                runModulesList(fromWho, msg);
                break;
            case "moduleParamsPage":
                moduleParamsPage(fromWho, msg);
                break;
            case "getModuleParams":
                getModuleParams(fromWho, msg);
                break;
            case "changeParam":
                changeParam(fromWho, msg);
                break;
            case "putCmdtoModule":
                putCmdtoModule(fromWho, msg);
                break;
            default:
                putMsg("error", creatOutMsg().setAction("setError").setParam("content", "no action"));
        }
        return returnMsg;
    }
    private void changeParam(Object fromWho, TLMsg msg) {
        String moduleName = (String) msg.getParam("modulename");
        String moduleIn = (String) msg.getParam("moduleIn");
        String name = (String) msg.getParam("name");
        String value = (String) msg.getParam("value");
        TLMsg setMsg =createMsg().setAction(MODULE_SETPARAM).setParam(name,value).setParam("waitServerReturn",false);
        putMsgToModule(moduleName,setMsg,moduleIn);
        putResultToClient(true,"changeParam");
    }

    private void moduleParamsPage(Object fromWho, TLMsg msg) {
        String name = (String) msg.getParam("name");
        outData odata = creatOutDataMsg("moduleParamsPage");
        odata.addData("name",name);
        putOutData(odata);
    }

    private void getModuleParams(Object fromWho, TLMsg msg) {
        String moduleName = (String) msg.getParam("name");
        String moduleIn = (String) msg.getParam("moduleIn");
        ArrayList<Map<String, Object>> moduleList =  new ArrayList<>();
        TLMsg returnMsg = putCmdtoModule(moduleName,MODULE_GETPARAM,moduleIn,true);
        Map<String ,Object> moduleParams =returnMsg.getArgs();
        for(String key : moduleParams.keySet()){
            HashMap<String ,Object> map =new HashMap<>();
            map.put("name",key);
            map.put("value",moduleParams.get(key));
            moduleList.add(map);
        }
        outData odata =  creatOutDataMsg("getModuleParams");
        odata.addData("code","0") ;
        odata.addData("msg","") ;
        odata.addData("count",moduleList.size());
        odata.addData("params",moduleList);
        putOutData(odata);
    }

    private void runModulesList(Object fromWho, TLMsg msg) {
        String moduleIn = (String) msg.getParam("moduleIn");
        TLMsg fmsg =createMsg().setAction(FACTORY_GETRUNMODULES);
        if(moduleIn ==null)
            moduleIn ="factory" ;
        TLMsg returnMsg ;
        ArrayList<Map<String, Object>> moduleList =new ArrayList<>();
        if( moduleIn.equals("factory"))
        {
            returnMsg =putMsg(moduleFactory,fmsg);
            moduleList = (ArrayList<Map<String, Object>>) returnMsg.getParam(RESULT);
        }
        else
        {
            returnMsg=putToApp(MODULEFACTORY,fmsg);
            if(returnMsg.getParam("appType").equals("server"))
            {
                Map<String,Object> resultMap =returnMsg.getArgs();
                returnMsg.removeParam("appType");
                for(String server : resultMap.keySet())
                {
                    HashMap<String,Object> result = (HashMap<String, Object>) resultMap.get(server);
                    Object resultData =result.get(RESULT);
                    if(resultData instanceof ArrayList)
                    {
                        ArrayList<Map<String, Object>> serverList = (ArrayList<Map<String, Object>>) result.get(RESULT);
                        for(Map<String,Object> module : serverList){
                            module.put("name",module.get("name")+"@"+server);
                            module.put("factory",module.get("factory")+"@"+server);
                        }
                        moduleList.addAll(serverList);
                    }
                    else {
                        outData odata =  creatOutDataMsg("modulesList");
                        odata.addData("code","1") ;
                        odata.addData("msg","接口错误") ;
                        odata.addData("count",0);
                        odata.addData("data",null);
                        putOutData(odata);
                    }
                }
            }
            else
                moduleList = (ArrayList<Map<String, Object>>) returnMsg.getParam(RESULT);
        }
        outData odata =  creatOutDataMsg("modulesList");
        odata.addData("code","0") ;
        odata.addData("msg","") ;
        odata.addData("count",moduleList.size());
        odata.addData("data",moduleList);
        putOutData(odata);
    }
    private void putCmdtoModule(Object fromWho, TLMsg msg ) {
        if(msg.isNull("name"))
            return ;
        String moduleName = (String) msg.getParam("name");
        String cmd = (String) msg.getParam("cmd");
        String moduleIn = (String) msg.getParam("moduleIn");
        putCmdtoModule(moduleName,cmd,moduleIn,false);
        putResultToClient(true,cmd);
    }

    protected void index(Object fromWho, TLMsg msg) {
        outData odata = creatOutDataMsg("index");
        putOutData(odata);
    }
    protected void runModules(Object fromWho, TLMsg msg) {
        outData odata = creatOutDataMsg("runModules");
        putOutData(odata);
    }


}

