package cn.tianlong.java.demo.service.server;

import cn.tianlong.java.demo.base.DemoCommon;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.utils.TLMsgUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

import static java.lang.Thread.sleep;

public class serverModule extends DemoCommon {

    public serverModule(String name) {
        super(name);
    }
    public serverModule(String name, TLObjectFactory moduleFactory) {
        super(name, moduleFactory);
    }
    @Override
    protected void initProperty() {
        super.initProperty();

    }

    @Override
    protected TLBaseModule init() {
       return this ;
    }

    @Override
    public void runStartMsg()  {
        super.runStartMsg();
        TLMsg receivermsg = createMsg().setDestination(name).setAction("onUserLogin");
        putMsg(M_MSGBROADCAST, createMsg().setAction(MSGBROADCAST_REGISTRECEIVER)
                .setParam(MSGBROADCAST_P_MESSAGETYPE, C_MESSAGETYPE_LOGIN )
                .setParam(MSGBROADCAST_P_RECEIVEMSG, receivermsg));

    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        printAction(msg);
        TLMsg returnMsg =null ;
        switch (msg.getAction()) {
            case "receiveClientMsg":
               returnMsg = receiveClientMsg(fromWho, msg);
                break;
            case "receiveFileFromClient":
                returnMsg= receiveFileFromClient(fromWho, msg);
                break;
            case "onUserLogin":
               onUserLogin(fromWho, msg);
                break;
            case "getFileFromClient":
                returnMsg=getFileFromClient(fromWho, msg);
                break;
            case "sendFileFromServer":
                returnMsg=sendFileFromServer(fromWho, msg);
                break;
            case "sendMsgFromServer":
                returnMsg=sendMsgFromServer(fromWho, msg);
                break;
            case "getFileByServer":
                returnMsg=getFileByServer(fromWho, msg);
                break;
            default:
        }
        return  returnMsg;
    }

    private TLMsg receiveClientMsg(Object fromWho, TLMsg msg) {
        System.out.print("收到客户的消息：");
        TLMsgUtils.printMsg(msg);
        TLMsg clientMsg= createMsg()
                .setParam("data","来自服务器的消息") ;
        return createMsg().setSystemParam(SOCKETSERVER_R_IFRETURN,true)
                .setArgs(TLMsgUtils.msgToSocketDataMap(clientMsg));
    }

    private TLMsg receiveFileFromClient(Object fromWho, TLMsg msg) {
        TLMsgUtils.printMsg(msg);
        ArrayList<HashMap<String,Object>> files = (ArrayList<HashMap<String, Object>>) msg.getParam(WEBSOCKET_R_RECEIVEDFILEGROUP);
        if(files!= null)
        {
            System.out.println("receive file number:"+files.size());
            doFileList(files);
        }
        try {
            sleep(1500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return createMsg().setParam("file","received");
    }

    private TLMsg getFileFromClient(Object fromWho, TLMsg msg) {
        String fileName= (String) msg.getParam("fileName");
        String filePath=moduleFactory.getConfigDir();
        fileName =filePath +fileName ;
        System.out.println("start server sendfile"+ fileName);
        HashMap<String,Object> threadDatas = (HashMap<String, Object>) msg.getSystemParam(TASKRESESSIONDATA);
        TLMsg gmsg =createMsg().setAction(WEBSOCKET_SENDFILE)
                .setSystemArgs(threadDatas)
                .setArgs(msg.getArgs())
                .setParam("fileName",fileName);
        putMsg("clientMsgHandler",gmsg);
        return null ;
    }

    private void onUserLogin(Object fromWho, TLMsg msg) {
        msg.setSystemParam(USERMANAGER_P_USERCHANNEL,msg.getAndRemoveParam(USERMANAGER_P_USERCHANNEL));
        msg.setSystemParam(USERMANAGER_P_USERID,msg.getAndRemoveParam(USERMANAGER_P_USERID));
        getMsg(this,msg.setAction(null).setMsgId("onUserLogin"));

    }
    private TLMsg getFileByServer(Object fromWho, TLMsg msg) {
        String fileName= (String) msg.getParam("fileName");
        TLMsg gmsg =createMsg().setAction("getFile")
                .setSystemArgs(msg.getSystemArgs())
                .setParam(MSG_P_MSGID,"getFileByServer")
                .setParam("fileName",fileName);
        TLMsg returnMsg = putMsg("webSocketReceiveFileModule",gmsg);
        TLMsgUtils.printMsg(returnMsg);
        return null ;
    }
    private TLMsg sendFileFromServer(Object fromWho, TLMsg msg) {
        String fileName= (String) msg.getParam("fileName");
        String filePath=moduleFactory.getConfigDir();
        fileName =filePath +fileName ;
        System.out.println("start server sendfile"+ fileName);
        TLMsg gmsg =createMsg().setAction(WEBSOCKET_SENDFILE)
                .setSystemArgs(msg.getSystemArgs())
                .setParam(MSG_P_MSGID,"receiveFileFromServer")
                .setParam("parama","a")
                .setParam("paramb",true)
                .setParam("paramc",12)
                .setParam("paramd",99.1)
                .setParam("fileName",fileName);
        TLMsg returnMsg = putMsg("clientMsgHandler",gmsg);
        if( returnMsg !=null)
            TLMsgUtils.printMsg(returnMsg);
        return null ;
    }

    private TLMsg sendMsgFromServer(Object fromWho, TLMsg msg) {
        TLMsg cliengMsg =createMsg().setMsgId("fromServerMsg")
                         .setParam("message","this message is  from server");
        TLMsg gmsg =createMsg().setAction( WEBSOCKET_PUTMSG)
                .setSystemArgs(msg.getSystemArgs())
                .setArgs(TLMsgUtils.msgToSocketDataMap(cliengMsg))  ;            ;
        TLMsg returnMsg = putMsg("clientMsgHandler",gmsg);
        if( returnMsg !=null)
            TLMsgUtils.printMsg(returnMsg);
        return null ;
    }

    private void doFileList(ArrayList<HashMap<String,Object>> files) {
        for(HashMap<String,Object> map: files){
            printFile(map);
        }
    }

    private void printFile(HashMap<String,Object> map) {
        String tmpfile = (String)  map.get("fileName");
        String realfile = (String)  map.get("realFileName");
        TLMsgUtils.printMap(map);
        File oldName = new File(tmpfile);
        String path =oldName.getParent();
        realfile = path+File.separator+realfile;
        File newName = new File(realfile);
        if(newName.exists())
            newName.delete() ;
        oldName.renameTo(newName);
    }

}
