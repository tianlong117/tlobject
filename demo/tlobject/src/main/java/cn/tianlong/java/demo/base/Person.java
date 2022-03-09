package cn.tianlong.java.demo.base;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.utils.TLMapUtils;
import cn.tianlong.tlobject.utils.TLMsgUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static java.lang.Thread.sleep;

public class Person extends DemoCommon {
    boolean   sleep = false;
    boolean ifputFile =false ;
    public Person(String name) {
        super(name);
    }
    public Person(String name, TLObjectFactory moduleFactory) {
        super(name, moduleFactory);
    }
    @Override
    protected void initProperty() {
        super.initProperty();
        String mood =TLMapUtils.getStringParam(params,"mood",null);
        if(mood !=null)
            printState(mood);
        ifputFile =TLMapUtils.parseBoolean(params,"ifputFile",false);
    }

    @Override
    protected TLBaseModule init() {
       return this ;
    }

    @Override
    public void runStartMsg()  {
        super.runStartMsg();
        if(ifputFile)
        {
            TLMsg receivermsg = createMsg().setDestination(name).setAction("onUserLogin");
            putMsg(M_MSGBROADCAST, createMsg().setAction(MSGBROADCAST_REGISTRECEIVER)
                    .setParam(MSGBROADCAST_P_MESSAGETYPE, C_MESSAGETYPE_LOGIN )
                    .setParam(MSGBROADCAST_P_RECEIVEMSG, receivermsg));
        }

    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        printAction(msg);
        TLMsg returnMsg =null ;
        switch (msg.getAction()) {
            case "study":
                study(msg);
                break;
            case "getFileByServer":
                getFileByServer(msg);
                break;
            case "toWife":
                toWife();
                break;
            case "putFileFromClient":
                putFileFromClient();
                break;
            case "onHouse":
                onHouse(fromWho, msg);
                break;
            case "onLight":
                returnMsg = onLight(fromWho, msg);
                break;
            case "cook":
                returnMsg=cook(fromWho, msg);
                break;
            case "psleep":
                ssleep(fromWho, msg);
                break;
            case "comein":
                comein(fromWho, msg);
                break;
            case "sing":
                returnMsg= sing(fromWho, msg);
                break;
            case "onUserLogin":
               onUserLogin(fromWho, msg);
                break;
            case "receiveFileFromClient":
               returnMsg= receiveFileFromClient(fromWho, msg);
                break;
            case "getFile":
                returnMsg=getFile(fromWho, msg);
                break;
            case "fromXiaoMing":
                returnMsg=fromXiaoMing(fromWho, msg);
                break;
            default:
        }
        return  returnMsg;
    }

    private void study(TLMsg msg) {
        printState(" 应用id:"+applicationId+"  ;"+name+" 学习中。。。");
    }

    private void onHouse(Object fromWho, TLMsg msg) {
        //注册到广播接收者
        printState(" 在屋里。注册接受广播消息,消息类型：light");
        TLMsg receivermsg = createMsg().setDestination(name).setAction("onLight");
        putMsg(M_MSGBROADCAST, createMsg().setAction(MSGBROADCAST_REGISTRECEIVER)
                .setParam(MSGBROADCAST_P_MESSAGETYPE, "light").setParam(MSGBROADCAST_P_RECEIVEMSG, receivermsg));
    }

    private TLMsg sing(Object fromWho, TLMsg msg) {
        printState(" 开心的唱起了歌....");
        TLMsg wmsg =createMsg().setAction(WEBSOCKET_PUTANDWAIT).setParam("content","来自小明的消息")
                .setParam(MSG_P_MSGID,"fromXiaoMing");
        putMsg("wife",wmsg);
        say(" 给老婆发送个消息");
        return  createMsg().setParam(RESULT,"from client "+name+" sing");
    }

    private TLMsg cook(Object fromWho, TLMsg msg) {
        printState(" applicationid:"+applicationId+"  ;"+name+" 做饭..."+ " 进程id: " + Thread.currentThread().getName() );

        return createMsg().setParam("content","return from "+name+" cook");
    }

    private void comein(Object fromWho, TLMsg msg) {
        say("我回家了，开灯啦 ");
        putMsg("light", createMsg().setAction("on"));
    }
    private void ssleep(Object fromWho, TLMsg msg) {
        sleep = true;
        System.out.println(" applicationid:"+applicationId+"  ;"+name + " 在睡觉 ");
    }
    private TLMsg onLight(Object fromWho, TLMsg msg) {
        String housestatus = (String) msg.getParam("status");
        String thread = " (进程id: " + Thread.currentThread().getName() + ")";
        if (housestatus.equals("light"))
        {
            if (sleep == true)
            {
                say( "喊:关灯，我在睡觉呢。  " + thread);
                try {
                    sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                TLMsg cmsg =createMsg().setMsgId("fromServerWife");
                putToClient(cmsg,true,msg.getSystemArgs());
            //    TLMsg returnMsg =createMsg().setArgs(TLMsgUtils.msgToMap(cmsg)) ;
           //     return  returnMsg ;
            }
            else {
                say( "屋子亮啦，回家的感觉真好。 上上网吧 " + thread);
                webclient("http://www.baidu.com");
                TLBaseModule module1Fac= moduleFactory.getFactory("module1");
                if(module1Fac!=null)
                {
                    printState("给增加的模块程序发送消息");
                    say( "儿子做饭吧!");
                    TLMsg returnMsg= putMsg("son@module1",createMsg().setAction("cook"));
                    String content =returnMsg.getStringParam("content",null) ;
                    if(content !=null)
                        say("收到儿子返回的消息："+content);
                }
                moduleFactory.shutdown();
            }
        }
        return  null ;
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

    private void webclient(String url) {
        TLMsg msg =createMsg().setAction("get") .setParam("url", url);
        TLMsg resultMsg =putMsg("httpClient", msg);
        if(resultMsg.parseBoolean(HTTP_ERROR,true)==true)
        {
            System.out.println( "网站打不开啊"+ url);
            return;
        }
        say( "看看百度!");
        String response = (String) resultMsg.getParam(WEBRESPONSE);
        System.out.println(response);
    }
    protected void say(String message){
        System.out.println(name +" 说:"+message);
    }
    private void putFileFromClient() {
        String fileName =moduleFactory.getConfigDir()+params.get("putfileName");
        TLMsg msg =createMsg().setAction(WEBSOCKET_SENDFILE)
                .setParam("parama","a")
                .setParam("paramb",true)
                .setParam("paramc",12)
                .setParam("paramd",99.1)
                .setParam(MSG_P_MSGID,"receiveFileFromClient")
                .setParam("fileName",fileName);
        //    .setWaitFlag(false);
        TLMsg returnmsg =putMsg("socketClientAgentPool",msg);
        if(returnmsg.parseBoolean(RESULT,false)==true)
        {
            System.out.println("file is send sucessfuliy "+fileName);
        }
        else
            System.out.println("file is send failure "+fileName);

    }
    private TLMsg fromXiaoMing(Object fromWho, TLMsg msg) {
        System.out.print("收到小明的Msg：");
         TLMsgUtils.printMsg(msg);
         TLMsg clientMsg= createMsg().setMsgId("fromServerWife")
                 .setParam("data"," yes") ;
         return createMsg().setSystemParam(SOCKETSERVER_R_IFRETURN,true)
                 .setArgs(TLMsgUtils.msgToMap(clientMsg));
    }

    private void toWife() {
        TLMsg msg =createMsg().setAction(WEBSOCKET_PUTANDWAIT).setParam("data","wakeup1")
                .setParam(MSG_P_MSGID,"fromXiaoMing");
       TLMsg returnMsg= putMsg("socketClientAgentPool",msg);
       System.out.print("1......");
       TLMsgUtils.printMsg(returnMsg);

         for(int i =0 ; i<1 ; i++){
             TLMsg msg1 =createMsg().setAction(WEBSOCKET_PUTANDWAIT).setParam("data","wakeup"+i)
                     .setParam(MSG_P_MSGID,"fromXiaoMing").setWaitFlag(false).setSystemParam(INTHREADPOOL,true);
             TLMsg returnMsg1= putMsg("socketClientAgentPool",msg1);
             System.out.print(i+"......");
             TLMsgUtils.printMsg(returnMsg1);
         }

    }

    private void getFileByServer(TLMsg msg) {
        String fileName = (String) msg.getParam("fileName");
        TLMsg fmsg =createMsg().setAction(WEBSOCKET_SENDFILE).setParam(msg.getArgs())
                .setParam("fileName",fileName).setWaitFlag(false);
        putMsg("socketClientAgentPool",fmsg);
    }

    private TLMsg getFile(Object fromWho, TLMsg msg) {
        String fileName= (String) msg.getParam("fileName");
        String filePath="D:\\";
        fileName =filePath +fileName ;
        System.out.println("start server sendfile"+ fileName);
        TLMsg gmsg =createMsg().setAction("sendFile").setArgs(msg.getArgs())
                .setParam("fileName",fileName)
                .setParam(USERMANAGER_P_USERID,"demo_user");
        putMsg("userManagerModule",gmsg);
        return null ;
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
            sleep(15000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return createMsg().setParam("file","received");
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

    private void onUserLogin(Object fromWho, TLMsg msg) {

        String fileName =moduleFactory.getConfigDir()+params.get("putfilebyserver");
        sendFileFromServer(fileName);

    }

    private void sendFilesByServer() {
        ArrayList<String> fileList =new ArrayList<>() ;
      fileList.add("D:\\Art-Kins.-.[唤醒超觉].唤醒超觉盛夏版.mp3")        ;
       fileList.add("D:\\apache-maven-3.5.3-bin.zip");
        fileList.add("D:\\IMG_0433.JPG");
        TLMsg msg =createMsg().setAction(WEBSOCKET_SENDFILE)
                .setParam(WEBSOCKET_P_SENDFILEAPPWAITTIMEONSEND,10000)
                .setParam("parama","a")
                .setParam("paramb",true)
                .setParam("paramc",12)
                .setParam("paramd",99.1)
                .setParam(MSG_P_MSGID,"receiveFileFromClient")
                .setParam("fileGroup",fileList)  .setParam(USERMANAGER_P_USERID,"demo_user");
        TLMsg returnMsg = putMsg("clientMsgHandler",msg);
        if(returnMsg.parseBoolean(RESULT,false)==true)
        {
            System.out.println("file is send sucessfuliy ");
        }
        else
            System.out.println("file is send failure ");
        TLMsgUtils.printMap(returnMsg.getArgs());
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

    private void  sendFilesByClient() {
          ArrayList<String> fileList =new ArrayList<>() ;
        fileList.add("D:\\Art-Kins.-.[唤醒超觉].唤醒超觉盛夏版.mp3")        ;
        fileList.add("D:\\apache-maven-3.5.3-bin.zip");
        fileList.add("D:\\IMG_0433.JPG");
        TLMsg msg =createMsg().setAction(WEBSOCKET_SENDFILE).setParam("parama","A").
                 setParam("paramb",1001)
                .setParam(MSG_P_MSGID,"receiveFileFromClient")
                .setParam("fileGroup",fileList) ;
        TLMsg returnMsg = putMsg("socketClientAgentPool",msg);
        if(returnMsg.parseBoolean(RESULT,false)==true)
        {
            System.out.println("file is send sucessfuliy ");
        }
        else
            System.out.println("file is send failure ");
        TLMsgUtils.printMap(returnMsg.getArgs());
    }

    private void sendFileFromServer(String fileName) {
        System.out.println("start server sendfile"+ fileName);
        TLMsg msg =createMsg().setAction("sendFile").setWaitFlag(false)
                .setParam("parama","a")
                .setParam("paramb",true)
                .setParam("paramc",12)
                .setParam("paramd",99.1)
                .setParam("fileName",fileName)
                .setParam(MSG_P_MSGID,"receiveFileFromClient")
                .setParam(USERMANAGER_P_USERID,"demo_user")
                .setParam(TASKWAITTIME,5000);
       TLMsg returnMsg= putMsgInThreadAndWait("clientMsgHandler",msg,10000);
       TLMsgUtils.printMap(returnMsg.getArgs());
    }


}
