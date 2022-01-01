package cn.tianlong.java.demo.base;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.network.common.FileClass;
import cn.tianlong.tlobject.utils.TLMsgUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static java.lang.Thread.sleep;

public class Person extends TLBaseModule {
    boolean sleep;
    boolean  toClient =false ;
    boolean  ifput =false ;
    public Person(String name) {
        super(name);
    }
    public Person(String name, TLObjectFactory moduleFactory) {
        super(name, moduleFactory);
    }
    @Override
    protected TLBaseModule init() {
        System.out.println("---模块创建: "+name + " 创建 ");
        sleep = false;
        putMsg(this,createMsg().setAction("toClient").setWaitFlag(false));
        TLMsg receivermsg = createMsg().setDestination(name).setAction("onUserLogin");
        putMsg(M_MSGBROADCAST, createMsg().setAction(MSGBROADCAST_REGISTRECEIVER)
                .setParam(MSGBROADCAST_P_MESSAGETYPE, "login").setParam(MSGBROADCAST_P_RECEIVEMSG, receivermsg));

        return  this ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg =null ;
        switch (msg.getAction()) {
            case "getFileByServer":
                getFileByServer(msg);
                break;
            case "toWife":
                toWife();
                break;
            case "putFile":
                putFile();
                break;
            case "toClient":
                toClient();
            case "onHouse":
                onHouse(fromWho, msg);
                break;
            case "house":
                returnMsg = house(fromWho, msg);
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
            case "onUserLogin11":
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

    private TLMsg fromXiaoMing(Object fromWho, TLMsg msg) {
        System.out.print("收到小明的Msg：");
         TLMsgUtils.printMsg(msg);
         return createMsg().setParam("data"," yes") ;
    }

    private void toWife() {
        TLMsg msg =createMsg().setAction(SOCKETCLIENTAGENTPOOL_PUTTOSERVERANDWAIT).setParam("data","wakeup1")
                .setParam(MSG_P_MSGID,"fromXiaoMing");
       TLMsg returnMsg= putMsg("socketClientAgentPool",msg);
       System.out.print("1......");
       TLMsgUtils.printMsg(returnMsg);

         for(int i =0 ; i<1 ; i++){
             TLMsg msg1 =createMsg().setAction(SOCKETCLIENTAGENTPOOL_PUTTOSERVERANDWAIT).setParam("data","wakeup"+i)
                     .setParam(MSG_P_MSGID,"fromXiaoMing").setWaitFlag(false).setParam(INTHREADPOOL,true);
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

        try {
            sleep(7000);
            ifput =false ;
            if(ifput ==true)
                return;
            ifput =true ;
     //  sendFileFromServer("D:\\weixin.apk");
    //   sendFileFromServer("D:\\微信测试工具20141116.exe");
   //   sendFileFromServer("D:\\企政通.txt");
   //sendFileFromServer("D:\\nmap-7.40-setup.exe");
     //  sendFileFromServer("D:\\web并发＆压力测试工具http_loadWin32.zip");
    //sendFileFromServer("D:\\apache-maven-3.5.3-bin.zip");
      //      sendFileFromServer("D:\\微信图片_20210819084148.jpg");
   // sendFileFromServer("D:\\BaiduNetdisk_7.6.0.13.exe");
 //   sendFileFromServer("D:\\qinqin.txt");
   //   sendFilesByServer();
      //      sendFilesByServerToHttpProxy();
          String fileName ="D:\\winweb.rar";
     getFileFromclient(fileName,msg);
            fileName ="D:\\IMG_0433.JPG";
    //        getFileFromclient(fileName,msg);
            String Url1 ="http://www.daqing.gov.cn/index.html";
            TLMsg msg1=createMsg().addMap(msg.getArgs());
            TLMsg msg2=createMsg().addMap(msg.getArgs());
            TLMsg msg3=createMsg().addMap(msg.getArgs());
  //  getUrlByProxy(Url1,msg1);
            String  Url2 ="http://www.baidu.com/index.html";
   // getUrlByProxy(Url2,msg2);
          String Url ="https://gimg2.baidu.com/image_search/src=http%3A%2F%2Fpic3.zhimg.com%2Fv2-15f2a9981430de6de993eb1bff20b4f2_b.jpg&refer=http%3A%2F%2Fpic3.zhimg.com&app=2002&size=f9999,10000&q=a80&n=0&g=0n&fmt=jpeg?sec=1633590858&t=ec002fa04c00d18aefca00622d8300e0";
   //    getUrlByProxy(Url,msg3);

        } catch (InterruptedException e) {
            e.printStackTrace();
        }

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

    private void putFile() {

  sendFile("D:\\apache-tomcat-9.0.1.zip");

    }
    private void sendFiles1() {
        TLMsg msg1 =createMsg().setDestination("socketClientAgentPool")
                .setAction(WEBSOCKET_SENDFILE)
                .setParam(INTHREADPOOL,true)
                .setParam(MSG_P_MSGID,"receiveFileFromClient")
                .setParam("fileName","D:\\javaweb.jar").setWaitFlag(false);
        TLMsg msg2 =createMsg()
                .setDestination("socketClientAgentPool")
                .setParam(INTHREADPOOL,true)
                .setAction(WEBSOCKET_SENDFILE)
                .setParam(MSG_P_MSGID,"receiveFileFromClient")
                .setParam("fileName","D:\\数据恢复R-Studio.rar").setWaitFlag(false);
        TLMsg msg3 =createMsg()
                .setDestination("socketClientAgentPool")
                .setParam(INTHREADPOOL,true)
                .setAction(WEBSOCKET_SENDFILE)
                .setParam(MSG_P_MSGID,"receiveFileFromClient")
                .setParam("fileName","D:\\微信图片_20210819084148.jpg").setWaitFlag(false);
        ArrayList<TLMsg> msgList =new ArrayList<>() ;
        msgList.add(msg1)        ;
        msgList.add(msg2);
        msgList.add(msg3);
        TLMsg returnMsg = putMsgGroupByThread(msgList,0);
        System.out.print("x");
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
    private void sendFile(String fileName) {
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

    private void toClient() {
       while (true){
           try {
               sleep(2000);
               if(toClient)
               {
                   TLMsg cmsg =createMsg().setMsgId("fromServerWife");
                   TLMsg clientMsg =createMsg().setAction("toClientAndWait").setArgs(TLMsgUtils.msgToMap(cmsg)).setParam(USERMANAGER_P_USERID,"demo_user") ;
                   TLMsg returnMsg= putMsg("clientMsgHandler",clientMsg);
                   TLMsgUtils.printMsg(returnMsg);
               //    break;
               }
           } catch (InterruptedException e) {
               e.printStackTrace();
           }
       }
    //    sendFileFromServer("D:\\企政通.txt");
    //    sendFileFromServer("D:\\dz.png");
    //    sendFileFromServer("D:\\微信测试工具20141116.exe");
     //   sendFileFromServer("D:\\winMd5Sum.exe");
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

    private void onHouse(Object fromWho, TLMsg msg) {
        //注册到广播接收者
        TLMsg receivermsg = createMsg().setDestination(name).setAction("house");
        putMsg(M_MSGBROADCAST, createMsg().setAction(MSGBROADCAST_REGISTRECEIVER)
                .setParam(MSGBROADCAST_P_MESSAGETYPE, "house").setParam(MSGBROADCAST_P_RECEIVEMSG, receivermsg));
    }

    private TLMsg sing(Object fromWho, TLMsg msg) {
        System.out.println(name+" 听见老婆的声音，小明开心的唱起了歌....");
        TLMsg wmsg =createMsg().setAction(SOCKETCLIENTAGENTPOOL_PUTTOSERVERANDWAIT).setParam("content","来自小明的消息")
                .setParam(MSG_P_MSGID,"fromXiaoMing");
        putMsg("wife",wmsg);
        System.out.println(name+" 给老婆发送消息");
        return  createMsg().setParam(RESULT,"from client "+name+" sing");
    }

    private TLMsg cook(Object fromWho, TLMsg msg) {
        try {
            System.out.println(" applicationid:"+applicationId+"  ;"+name+" is cooking"+ " 进程id: " + Thread.currentThread().getName() );
            sleep(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.out.println(" applicationid:"+applicationId+"  ;"+name+" cook over");
        return createMsg().setParam("content","return from "+name+" cook");
    }

    private void comein(Object fromWho, TLMsg msg) {
        System.out.println(" applicationid:"+applicationId+"  ;"+name + "说：我回家了，开灯啦 ");
        putMsg("light", createMsg().setAction("on"));
    }
    private void ssleep(Object fromWho, TLMsg msg) {
        sleep = true;
        System.out.println(" applicationid:"+applicationId+"  ;"+name + " 在睡觉 ");
    }
    private TLMsg house(Object fromWho, TLMsg msg) {
        String housestatus = (String) msg.getParam("status");
        String response = "(" + name + " 进程id: " + Thread.currentThread().getName() + ")";
        if (housestatus.equals("light"))
        {
            if (sleep == true)
            {
                System.out.println(name + "喊:关灯，我在睡觉呢。  " + response);
                try {
                    sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
    //           toClient =true ;
                TLMsg cmsg =createMsg().setMsgId("fromServerWife");
                TLMsg returnMsg =createMsg().setArgs(TLMsgUtils.msgToMap(cmsg)) ;
                 return  returnMsg ;
            }
            else {
                System.out.println(name + "说：屋子亮啦，回家的感觉真好。 上上网吧 " + response);
                webclient("http://www.baidu.com");
            }
        }
        return  null ;
    }
    private void webclient(String url) {
        TLMsg msg =createMsg().setAction("get") .setParam("url", url);
        String action = name + " 打开:" + url;
        TLMsg resultMsg =putMsg("httpClient", msg);
        if(resultMsg.parseBoolean(HTTP_ERROR,true)==true)
        {
            System.out.println( "网站打不开啊"+ url);
            return;
        }
        System.out.println( "看看百度!");
        String response = (String) resultMsg.getParam(WEBRESPONSE);
        System.out.println(response);
    }

}
