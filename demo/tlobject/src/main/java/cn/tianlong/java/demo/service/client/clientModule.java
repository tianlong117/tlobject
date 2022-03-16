package cn.tianlong.java.demo.service.client;

import cn.tianlong.java.demo.base.DemoCommon;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import cn.tianlong.tlobject.utils.TLMsgUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;



public class clientModule extends DemoCommon {
    final String  myInterface = "clientInterfaceModule";
    public clientModule(String name) {
        super(name);
    }
    public clientModule(String name, TLObjectFactory moduleFactory) {
        super(name, moduleFactory);
    }

    @Override
    protected TLBaseModule init() {
       return this ;
    }


    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        printAction(msg);
        TLMsg returnMsg =null ;
        switch (msg.getAction()) {
            case "putMsgToServerAndWait":
                putMsgToServerAndWait(fromWho, msg);
                break;
            case "getFileByServer":
                invokeActionInThread("getFileByServer", this,msg);
                break;
            case "putFileFromClient":
                invokeActionInThread("putFileFromClientInThread", this, null);
                break;
            case "sendFilesByClient":
                invokeActionInThread("sendFilesByClient", this, null);
                break;
            case "getFileFromServer":
                getFileFromServer(fromWho, msg);
                break;
            case "receiveFileFromServer":
                returnMsg= receiveFileFromServer(fromWho, msg);
                break;
            default:
        }
        return  returnMsg;
    }

    private TLMsg receiveFileFromServer(Object fromWho, TLMsg msg) {
        TLMsgUtils.printMsg(msg);
        return createMsg().setParam("message","client have receive file");
    }

    private void putMsgToServerAndWait(Object fromWho, TLMsg msg) {
        invokeActionInThread("putMsgToServerInThread", this, null);
    }
    private void putMsgToServerInThread(Object fromWho, TLMsg msg) {
        TLMsg smsg =createMsg().setMsgId("receiveClientMsg")
                .setParam("data","你好，来自客户端的发送消息");
        TLMsg pmsg =createMsg().setAction(WEBSOCKET_PUTANDWAIT).setArgs(TLMsgUtils.msgToMap(smsg));
        TLMsg returnMsg = putMsg(myInterface,pmsg);
        println(" 服务返回：");
        TLMsgUtils.printMsg(returnMsg);
    }

    private void putFileFromClientInThread(Object fromWho, TLMsg msg) {
        String fileName =moduleFactory.getConfigDir()+params.get("putfileName");
        TLMsg smsg =createMsg().setAction(WEBSOCKET_SENDFILE)
                .setParam("parama","a")
                .setParam("paramb",true)
                .setParam("paramc",12)
                .setParam("paramd",99.1)
                .setParam(MSG_P_MSGID,"receiveFileFromClient")
                .setParam("fileName",fileName);
        //    .setWaitFlag(false);
        TLMsg returnmsg =putMsg(myInterface,smsg);
        if(returnmsg.parseBoolean(RESULT,false)==true)
        {
            System.out.println("file is send sucessfuliy "+fileName);
        }
        else
            System.out.println("file is send failure "+fileName);

    }
    private void  sendFilesByClient(Object fromWho, TLMsg msg) {
        String path =moduleFactory.getConfigDir();
        ArrayList<String> fileList =new ArrayList<>() ;
        fileList.add(path+"filedemo.sql")        ;
        fileList.add(path+"filedemo1.sql");
        fileList.add(path+"filedemo2.sql");
        TLMsg smsg =createMsg().setAction(WEBSOCKET_SENDFILE).setParam("parama","A").
                setParam("paramb",1001)
                .setParam(MSG_P_MSGID,"receiveFileFromClient")
                .setParam(WEBSOCKET_P_SENDFILEGROUP,fileList) ;
        TLMsg returnMsg = putMsg(myInterface,smsg);
        if(returnMsg.parseBoolean(RESULT,false)==true)
        {
            System.out.println("file is send sucessfuliy ");
        }
        else
            System.out.println("file is send failure ");
        TLMsgUtils.printMap(returnMsg.getArgs());
    }


    private void getFileFromServer(Object fromWho, TLMsg msg) {
        String fileName="apache.zip";
        TLMsg gmsg =createMsg().setAction("getFile").setParam(MSG_P_MSGID,"getFileFromClient")
                .setParam("fileName",fileName).setWaitFlag(false);
        //  putMsg("socketClientAgentPool",msg);
        TLMsg returnMsg = putMsg("webSocketReceiveFileModule",gmsg);
        TLMsgUtils.printMsg(returnMsg);
    }

    private void getFileByServer(Object fromWho, TLMsg msg) {
        String fileName= (String) msg.getParam("fileName");
        String filePath=moduleFactory.getConfigDir();
        fileName =filePath +fileName ;
        System.out.println("start server sendfile"+ fileName);
        HashMap<String,Object> threadDatas = (HashMap<String, Object>) msg.getSystemParam(TASKRESESSIONDATA);
        TLMsg gmsg =createMsg().setAction(WEBSOCKET_SENDFILE)
                .setSystemArgs(threadDatas)
                .setArgs(msg.getArgs())
                .setParam("fileName",fileName);
        putMsg(myInterface,gmsg);
    }


}
