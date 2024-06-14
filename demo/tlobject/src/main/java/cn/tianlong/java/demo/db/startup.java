package cn.tianlong.java.demo.db;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLAppStartUp;
import cn.tianlong.tlobject.utils.TLMsgUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 创建日期：2021/2/1010:29
 * 描述:
 * 作者:tianlong
 */
public class startup extends TLAppStartUp {
    public static   TLObjectFactory appFactory ;
    public startup(String name) {
        super( name);
    }
    public static void  main (String[] args ) {
        HashMap<String,String> argsMap = null;
        if(args !=null && args.length >0){
            TLAppStartUp.main(args);
          return;
        }
        startModule (argsMap );
    }
    @Override
    protected void run() {

    }
    public static TLObjectFactory   startModule (HashMap<String,String> configMap  ) {
        HashMap<String,Object> argsMap =new HashMap<>() ;
        argsMap.put("appName","demo0");
        argsMap.put("configPath",CLASSPATH+"/conf/demo/db/");
        argsMap.put("factoryConfigFile","moduleFactory_config.xml");
        argsMap.put("configFile","demoappstart.xml");
        if(configMap !=null)
            argsMap.putAll(configMap);
        startup instance = new startup("startup");
        appFactory=  instance.startup(argsMap);
        return appFactory ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "queryByUserName":
                queryByUserName( fromWho,  msg);
                break;
            case "queryByNumber":
                queryByNumber( fromWho,  msg);
                break;
            case "updateByTable":
                updateTable( fromWho,  msg);
                break;
            case "deleteByTable":
                deleteByTable( fromWho,  msg);
                break;
            default:
                returnMsg=super.checkMsgAction(fromWho,msg);
        }
        return returnMsg;
    }

    private void queryByNumber(Object fromWho, TLMsg msg) {
        System.out.println("查询 number="+msg.getParam("number"));
        TLMsg returnMsg =putMsg("userTableModle",msg.setAction("queryByNumber"));
        List datas =  returnMsg.getListParam(RESULT,null);
        if(datas ==null || datas.isEmpty())
        {
            System.out.println("没有数据");
            return;
        }
        System.out.println("查询结果:");
        TLMsgUtils.printList(datas);
    }

    private void queryByUserName(Object fromWho, TLMsg msg) {
        System.out.println("查询 username="+msg.getParam("username"));
        Map<String,Object> datas = (Map<String, Object>) putMsgAndGetResult("dbDemo",msg.setAction("queryTb"));
        if(datas ==null || datas.isEmpty())
        {
            System.out.println("没有数据");
            return;
        }
        System.out.println("查询结果:");
        TLMsgUtils.printMap(datas);
    }
    private void updateTable(Object fromWho, TLMsg msg) {
        System.out.println(" --------- 修改前 ---------------");
        queryByUserName( fromWho, msg);
        putMsg("dbDemo",msg.setAction("updateTb"));
        System.out.println(" --------- 修改后 ---------------");
        queryByUserName( fromWho, msg);
    }

    private void deleteByTable(Object fromWho, TLMsg msg) {
        System.out.println(" --------- 删除前 ---------------");
        queryByUserName( fromWho, msg);
        putMsg("dbDemo",msg.setAction("deleteTb"));
        System.out.println(" --------- 删除后 ---------------");
        queryByUserName( fromWho, msg);
    }




}
