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
                getFileByServer(msg);
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
            default:
        }
        return  returnMsg;
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
        String fileName="BaiduNetdisk_7.6.0.13.exe";
        TLMsg gmsg =createMsg().setAction("getFile").setParam(MSG_P_MSGID,"getFileFromClient")
                .setParam("fileName",fileName).setWaitFlag(false);
        //  putMsg("socketClientAgentPool",msg);
        TLMsg returnMsg = putMsg("webSocketReceiveFIleModule",gmsg);
        TLMsgUtils.printMsg(returnMsg);
    }



    private void putToClient(TLMsg cmsg, boolean wait, HashMap systemArgs) {
        TLMsg msg =createMsg().setSystemArgs(systemArgs).setArgs(TLMsgUtils.msgToMap(cmsg));
        if( !wait)
            msg.setAction(WEBSOCKET_PUTMSG);
        else
            msg.setAction(WEBSOCKET_PUTANDWAIT);
       TLMsg returnMsg =  putMsg("clientMsgHandler",msg);
       TLMsgUtils.printMsg(returnMsg);
    }



    private void getFileByServer(TLMsg msg) {
        String fileName = (String) msg.getParam("fileName");
        TLMsg fmsg =createMsg().setAction(WEBSOCKET_SENDFILE).setParam(msg.getArgs())
                .setParam("fileName",fileName).setWaitFlag(false);
        putMsg("socketClientAgentPool",fmsg);
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


    private void sendFilesByServerToHttpProxy() {
         String  url ="http://www.daqing.net" ;
         HashMap<String,String> fileList =new HashMap<>();
        fileList.put("1","D:\\city.sql")        ;
        fileList.put("2","D:\\2.jpg");
        fileList.put ("3","D:\\IMG_0433.JPG");
        HashMap<String,String> datas =new HashMap<>();
        datas.put("a","1")        ;
        datas.put("b","2");
        datas.put ("c","3");
        HashMap<String,String>httpHeader =new HashMap<>();
        httpHeader.put("User-Agent", "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 6.1; WOW64; Trident/5.0; SLCC2; .NET CLR 2.0.50727; .NET CLR 3.5.30729; .NET CLR 3.0.30729; .NET4.0C; .NET4.0E)");
        httpHeader.put("Accept", "*/*");
        httpHeader.put("Accept-Encoding", "gzip, deflate");
        httpHeader.put("Accept-Language", "zh-CN");
        HashMap<String,String>cookie =new HashMap<>();
        cookie.put("username", "dongq");
        cookie.put("passwd", "1111111111111111");
        Map <String,Object > result =postFormDataByProxy(url,cookie,datas,httpHeader,fileList);
   //     TLMsgUtils.printMap(result);
    }
    protected Map<String,Object> postFormDataByProxy(String url, Map<String,String> cookie, Map<String,String> datas, HashMap<String, String> header, Map<String,String>files )
    {

        ArrayList<String>  fileList = new ArrayList<>();
        for(String key : files.keySet())
            fileList.add(files.get(key));
        TLMsg gmsg =createMsg().setAction(WEBSOCKET_SENDFILE)
                .setParam(USERMANAGER_P_USERID,"demo_user")
                .setParam("url",url)
                .setParam(MSG_P_MSGID,"postFormReturnDoc")
                .setParam("fileName",url)
                .setParam("charset","utf-8")
                .setParam("httpHeader",header)
                .setParam("fileGroup",fileList) ;
        if(cookie !=null)
            gmsg.setParam("cookie",cookie);
        if(datas !=null)
            gmsg.setParam("datas",datas);
        TLMsg returnMsg = putMsg("userManagerModule",gmsg);
        return returnMsg.getArgs();
    }
    private void getFileFromclient(String fileName ,TLMsg msg){
         TLMsg gmsg =createMsg().setAction("getFile").setParam(msg.getArgs()).setParam(MSG_P_MSGID,"getFile")
                 .setParam("fileName",fileName).setWaitFlag(false);
         //  putMsg("socketClientAgentPool",msg);
         putMsg("webSocketReceiveFIleModule",gmsg);
     }



}
