package cn.tianlong.tlobject.network.common;


import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import com.google.gson.reflect.TypeToken;
import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 创建日期：2020/2/1214:11
 * 描述:
 * 作者:tianlong
 */
public class TLWebSocketBinaryModule extends TLBaseModule {
    protected int maxSessions = 100;
    protected String filePath;
    protected String msgHandler = "clientMsgHandler";
    protected ConcurrentHashMap<Integer, HashMap<String, Object>> files = new ConcurrentHashMap<>();
    protected ConcurrentHashMap<String, CopyOnWriteArrayList<Integer>> channelSessions = new ConcurrentHashMap<>();
    protected TLNetSession netSession;
    protected Type jsonType = new TypeToken<Map<String, Object>>() {
    }.getType();

    public TLWebSocketBinaryModule(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        if (params != null && params.get("cachePath") != null)
            filePath = params.get("cachePath");
        else
            filePath = System.getProperty("user.dir") + "\\cache\\";
        if (msgHandler != null && params.get("msgHandler") != null)
            msgHandler = params.get("msgHandler");
        if (msgHandler != null && params.get("maxSessions") != null)
            maxSessions = Integer.valueOf(params.get("maxSessions"));
    }

    @Override
    protected TLBaseModule init() {

        TLMsg receivermsg = createMsg().setDestination(name).setAction("onUserLogout");
        putMsg(M_MSGBROADCAST, createMsg().setAction(MSGBROADCAST_REGISTRECEIVER)
                .setParam(MSGBROADCAST_P_MESSAGETYPE, "logout").setParam(MSGBROADCAST_P_RECEIVEMSG, receivermsg));
        netSession = new TLNetSession(name + "_session", moduleFactory);
        netSession.start(null, params);
        putMsg(this, createMsg().setAction("checkSessions")
                .setParam(SESSIONDEAMON, true)
                .setParam(EXCEPTIONHANDLER, new MyUnchecckedExceptionhandler(this, createMsg().setAction("checkSessions")))
                .setWaitFlag(false));
        return this;
    }

    public void setNetSession(TLNetSession netSession) {
        this.netSession = netSession;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "receiveBinary":
                returnMsg = receiveBinary(fromWho, msg);
                break;
            case "receiveBinaryFile":
                returnMsg = receiveBinaryFile(fromWho, msg);
                break;
            case "onUserLogout":
                onUserLogout(fromWho, msg);
                break;
            case "checkSessions":
                try {
                    checkSessions(fromWho, msg);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                break;
            case "getFile":
                returnMsg = getFile(fromWho, msg);
                break;
            default:
                ;
        }
        return returnMsg;
    }

    private void checkSessions(Object fromWho, TLMsg msg) throws InterruptedException {
        do {
            Thread.sleep(2000);
            for (Integer sessionId : files.keySet()) {
                HashMap<String, Object> channelFile = files.get(sessionId);
                Long time = (Long) channelFile.get("time");
                if ((System.currentTimeMillis() - time) > 30000) {
                    String fileName = (String) channelFile.get("fileName");
                    putLog("file receive timeout:" + fileName, LogLevel.DEBUG, "heckSessions");
                    closeStream(sessionId, false);
                }
            }
        } while (true);
    }
    private TLMsg receiveBinary(Object fromWho, TLMsg msg) {
        if (msg.isNull(WEBSOCKET_P_BINARYCMDCODE))
            return createMsg().setParam(RESULT, false);
        byte cmdCode = (byte) msg.getParam(WEBSOCKET_P_BINARYCMDCODE);
        switch (cmdCode) {
            case WEBSOCKET_V_BINARYMFILEDATACMDCODE:
                return receiveFile( msg);
            default:
                return null;
        }
    }
    private TLMsg receiveBinaryFile(Object fromWho, TLMsg msg) {
        if (msg.isNull(WEBSOCKET_P_BINARYCMDCODE))
            return createMsg().setParam(RESULT, false);
        byte cmdCode = (byte) msg.getParam(WEBSOCKET_P_BINARYCMDCODE);
        switch (cmdCode) {
            case WEBSOCKET_V_BINARYMFILEDATACMDCODE:
                return receiveFile( msg);
            case WEBSOCKET_V_BINARYMFILEDATACMDERRORCODE:
                return ErrorForSendFile(fromWho, msg);
            default:
                return null;
        }
    }

    private TLMsg ErrorForSendFile(Object fromWho, TLMsg msg) {
        if (msg.isNull(WEBSOCKET_P_BINARYSESSION))
            return createMsg().setParam(RESULT, false);
        int sessionId = (int) msg.getParam(WEBSOCKET_P_BINARYSESSION);
        if (sessionId == 0)
            return createMsg().setParam(RESULT, false);
        netSession.removeBinSessionId(sessionId);
        return createMsg().setParam(RESULT, true);
    }

    private TLMsg receiveFile( TLMsg msg) {
        if (msg.isNull(WEBSOCKET_P_BINARYSESSION))
            return createMsg().setParam(RESULT, false);
        int sessionId = (int) msg.getParam(WEBSOCKET_P_BINARYSESSION);
        if (sessionId == 0)
            return createMsg().setParam(RESULT, false);
        if (msg.isNull(WEBSOCKET_P_BINARYDATAORDER))
            return createMsg().setParam(RESULT, false);
        int order = (int) msg.getParam(WEBSOCKET_P_BINARYDATAORDER);
        HashMap<String, Object> channelFile = files.get(sessionId);
        if (order == -1) //发送完毕标志
        {
            if (channelFile != null)
                return closeSession(sessionId);
            else
                return createMsg().setParam(RESULT, false);
        }
        if (order == 0)
        {
            byte[] bytes = (byte[]) msg.getParam(WEBSOCKET_P_CONTENT);
            String str = new String(bytes);
            LinkedTreeMap<String, Object> fileParams = new Gson().fromJson(str, jsonType);
            if (channelFile == null)
            {
                boolean result = makeFileSessionData(sessionId, fileParams);
                if (result == false)
                    return createMsg().setParam(RESULT, false);
                result = createFile(sessionId, fileParams);
                if (result == false)
                    return createMsg().setParam(RESULT, false);
                channelFile = files.get(sessionId);
            } else
                {
                  boolean result ;
                  if(channelFile.get(WEBSOCKET_P_BINARYDATAIFRETURNSTREAM)!=null && (boolean)channelFile.get(WEBSOCKET_P_BINARYDATAIFRETURNSTREAM) ==true)
                  {
                      result =createInputStream(sessionId,fileParams);
                      if (result == false)
                          return createMsg().setParam(RESULT, false);
                      else
                      {
                          TLMsg fmsg = createMsg().addArgs(channelFile);
                          netSession.saveSesstiondata(String.valueOf(sessionId), fmsg); // for getFile
                      }
                  }
                   else
                    {
                        result = createFile(sessionId, fileParams);
                        if (result == false)
                            return createMsg().setParam(RESULT, false);
                    }
                }
            channelFile.put("order", 0);
            channelFile.put("time", System.currentTimeMillis());
            return createMsg().setParam(RESULT, true).setParam(WEBSOCKET_P_BINARYSESSION, String.valueOf(sessionId));
        }
        if (channelFile == null || channelFile.isEmpty())
            return null;
        int lastOrder = (int) channelFile.get("order");
        if (order - lastOrder != 1) {
            closeStream(sessionId, false);
            return createMsg().setParam(RESULT, false);
        }
        channelFile.put("order", order);
        channelFile.put("time", System.currentTimeMillis());
        byte[] bytes = (byte[]) msg.getParam(WEBSOCKET_P_CONTENT);
        BufferedOutputStream bufferedOutputStream = (BufferedOutputStream) channelFile.get("outputStream");
        try {
            bufferedOutputStream.write(bytes, 0, bytes.length);//再从buffer中取出来保存到本地
        } catch (IOException e) {
            e.printStackTrace();
        }
        putLog(" receive data:sessionid:" + sessionId, LogLevel.DEBUG, "receiveBinaryFile");
        return createMsg().setParam(RESULT, true);
    }
    protected TLMsg sendFileErrorMsg(int sessionId){
        TLMsg msg = createMsg().setParam(WEBSOCKET_P_BINARYCMDCODE,sessionId)
                               .setParam(WEBSOCKET_P_BINARYCMDCODE,WEBSOCKET_V_BINARYMFILEDATACMDERRORCODE)
                               .setParam(WEBSOCKET_P_BINARYMSGID,WEBSOCKET_V_BINARYRECEIVEFILEMSGID);
        return getMsg(this,msg.setMsgId("sendBinary")) ;
    }
    public TLMsg sendBinary(Object fromWho, TLMsg msg) {
        if (msg.isNull(WEBSOCKET_P_BINARYCMDCODE))
            return createMsg().setParam(RESULT, false);
        List userChannels = getUserChannels(msg);
        if (userChannels == null)
            return createMsg().setParam(RESULT, false);
        int cmdCode = (int) msg.getParam(WEBSOCKET_P_BINARYCMDCODE);
        int sessionId = getBinarySessionId(msg);
        String binaryMsgid = (msg.isNull(WEBSOCKET_P_BINARYMSGID)) ? WEBSOCKET_V_BINARYRECEIVEFILEMSGID : (String) msg.getParam(WEBSOCKET_P_BINARYMSGID);
        msg.removeParam(WEBSOCKET_P_BINARYSESSION);
        msg.removeParam(WEBSOCKET_P_IFMAKESESSION);
        HashMap<String, Object> contentMap = new HashMap<>();
        contentMap.putAll(msg.getArgs());
        contentMap.put(WEBSOCKET_P_BINARYSESSION, String.valueOf(sessionId));
        String contentStr = new Gson().toJson(contentMap);
        byte[] contentStrBytes = contentStr.getBytes();
        boolean result = binarySend(userChannels, binaryMsgid, (byte) cmdCode, sessionId, contentStrBytes, contentStrBytes.length, 0);
        if (result)
            return createMsg().setParam(RESULT, true).setParam(WEBSOCKET_P_BINARYSESSION, sessionId);
        else {
            if (sessionId > 0)
                netSession.removeBinSessionId(sessionId);
            return createMsg().setParam(RESULT, false);
        }
    }

    public TLMsg sendFile(Object fromWho, TLMsg msg) {
        int cmdOrder = WEBSOCKET_V_BINARYMFILEDATACMDCODE;
        InputStream inputStream = (InputStream) msg.getParam(WEBSOCKET_P_SENDINPUTSTREAM);
        String filePath = (String) msg.getParam(WEBSOCKET_P_SENDFILENAME);
        String fileName;
        File file = null;
        if (inputStream == null) {
            file = new File(filePath);
            if (!file.exists())
                return createMsg().setParam(RESULT, false);
            try {
                inputStream = new FileInputStream(file);
            } catch (FileNotFoundException e) {
                return createMsg().setParam(RESULT, false);
            }
            fileName = file.getName();
        } else {
            msg.removeParam(WEBSOCKET_P_SENDINPUTSTREAM);
            fileName = filePath;
        }
        String binaryMsgid = (msg.isNull(WEBSOCKET_P_BINARYMSGID)) ? WEBSOCKET_V_BINARYRECEIVEFILEMSGID : (String) msg.getParam(WEBSOCKET_P_BINARYMSGID);
        msg.setParam(WEBSOCKET_P_SENDFILENAME, fileName)
                .setParam(WEBSOCKET_P_IFMAKESESSION, true)
                .setParam(WEBSOCKET_P_BINARYCMDCODE, cmdOrder)
                .setParam(WEBSOCKET_P_BINARYMSGID, binaryMsgid);
        TLMsg resultMsg = sendBinary(this, msg);
        if (resultMsg.parseBoolean(RESULT, false) == false) {
            try {
                inputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            return resultMsg;
        }
        int sessionId = (int) resultMsg.getParam(WEBSOCKET_P_BINARYSESSION);
        List userChannels = getUserChannels(msg);
        if (userChannels == null)
            return createMsg().setParam(RESULT, false);
        byte[] buf = new byte[4096];
        int size;
        int order = 1;   //包序列号 ,从1开始
        Long startTime = moduleFactory.getRunTime(false);
        boolean ifSucess = true;
        try {
            while (-1 != (size = inputStream.read(buf))) {
                boolean result = binarySend(userChannels, binaryMsgid, (byte) cmdOrder, sessionId, buf, size, order);
                order++;
                if (result == false) {
                    ifSucess = false;
                    break;
                }
            }
            // 传输完毕后，发送结束标志，order为-1
            boolean result = binarySend(userChannels, binaryMsgid, (byte) cmdOrder, sessionId, null, 0, -1);
            if (result == false)
                ifSucess = false;
        } catch (Exception e) {
            ifSucess = false;
        }
        try {
            inputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        netSession.removeBinSessionId(sessionId);
        if (ifSucess == false) {
            putLog(filePath + " :传输失败", LogLevel.DEBUG, "putFile");
            return createMsg().setParam(RESULT, false);
        }
        Long takeTime = moduleFactory.getRunTime(false) - startTime;
        putLog(filePath + " :传输时间:" + takeTime, LogLevel.DEBUG, "putFile");
        return createMsg().setParam(RESULT, true);
    }

    protected boolean binarySend(List userChannels, String binaryMsgid, byte cmdCode, int sessionId, byte[] contentStrBytes, int length, int i) {
        return false;
    }

    protected List getUserChannels(TLMsg msg) {
        return null;
    }

    protected int getBinarySessionId(TLMsg msg) {
        int sessionId = 0;
        if (msg.isNull(WEBSOCKET_P_BINARYSESSION) && msg.parseBoolean(WEBSOCKET_P_IFMAKESESSION, false) == true)
            sessionId = netSession.makeBinSessionId();
        else {
            if (msg.getParam(WEBSOCKET_P_BINARYSESSION) instanceof String)
                sessionId = Integer.valueOf((String) msg.getParam(WEBSOCKET_P_BINARYSESSION));
            else if (msg.getParam(WEBSOCKET_P_BINARYSESSION) instanceof Integer)
                sessionId = (int) msg.getParam(WEBSOCKET_P_BINARYSESSION);
        }
        return sessionId;
    }

    private TLMsg getFile(Object fromWho, TLMsg msg) {
        int sessionId = netSession.makeBinSessionId();
        msg.setParam(WEBSOCKET_P_BINARYSESSION, String.valueOf(sessionId));
        TLMsg serverMsg = createMsg().setMsgId("getFile").addMap(msg.getArgs())
                .setParam(MSG_P_PARAMS, msg.getArgs());
        makeFileSessionData(sessionId, msg.getArgs());
        netSession.saveSessionId(String.valueOf(sessionId));
        TLMsg resultMsg = getMsg(this, serverMsg);
        Boolean result = resultMsg.parseBoolean(RESULT, false);
        if (result == false)
        {
            netSession.removeSessionId(String.valueOf(sessionId));
            return createMsg().setParam(RESULT, false);
        }
        do {
            HashMap<String, Object> channelFile = null ;
            try {
                Thread.sleep(10000);
                channelFile = files.get(sessionId);
                if (channelFile == null)
                    return createMsg().setParam(RESULT, false);
            } catch (InterruptedException e) {
                TLMsg returnMsg = netSession.returnServerMsg(String.valueOf(sessionId));
                if(returnMsg.parseBoolean(WEBSOCKET_P_BINARYDATAIFRETURNSTREAM,false) ==true)
                    return  returnMsg;
                else
                    return returnFileMsg(returnMsg.getArgs());
            }
        } while (true);
    }

    private TLMsg returnFileMsg(HashMap fileMap) {
        String tmpfile = (String) fileMap.get("fileName");
        String realFileName = (String) fileMap.get("realFileName");
        String savePath = (String) fileMap.get("savePath");
        File tmpFileObj = new File(tmpfile);
        if (savePath == null || savePath.isEmpty())
            savePath = tmpFileObj.getParent();
        String realfilePath = savePath + File.separator + realFileName;
        File realFile = new File(realfilePath);
        if (realFile.exists())
            realFile.delete();
        tmpFileObj.renameTo(realFile);
        return createMsg().setParam("fileName", realFileName)
                .setParam("savePath", savePath)
                .setParam("filePath", realfilePath);
    }

    private boolean makeFileSessionData(int sessionId, Map<String, Object> args) {
        if (files.size() > maxSessions)
            return false;
        String realFileName = (String) args.get("fileName");
        realFileName = changeFileName(realFileName);
        //   realFileName=realFileName.replaceAll("[\\s\\\\/:\\*\\?\\\"<>\\|]","_");
        String fileName = filePath + File.separator + sessionId + "__" + realFileName;
         HashMap<String, Object> channelMap = new HashMap<>();
        channelMap.putAll(args);
        channelMap.put("fileName", fileName);
        channelMap.put("realFileName", realFileName);
        //创建的一个写出的缓冲流
        channelMap.put("time", System.currentTimeMillis());
        channelMap.put("order", -1);
        files.put(sessionId, channelMap);
        putLog(sessionId + "make File Session Data:" + fileName, LogLevel.DEBUG, "startFile");
        return true;
    }
    private boolean createFile(int sessionId ,Map<String, Object> args) {
        HashMap<String, Object> channelMap = files.get(sessionId);
        if(channelMap.get("outputStream")!=null)
            return true ;
        String fileName = (String) channelMap.get("fileName");
        OutputStream fileOutputStream = null;
        File file = new File(fileName);
        try {
            fileOutputStream = new FileOutputStream(file);
        } catch (FileNotFoundException e) {
            putLog("file dont create:" + fileName, LogLevel.ERROR, "startFile");
            return false;
        }
        String channel = (String) args.get(USERMANAGER_P_USERCHANNEL);
        channel = getChannel(channel);
        channelMap.put("channel", channel);
        //创建的一个写出的缓冲流
        BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(fileOutputStream);
        channelMap.put("outputStream", bufferedOutputStream);
        channelMap.put("foutputStream", fileOutputStream);
        boolean result = channelSessionsPut(channel, sessionId);
        if (result == false) {
            closeStream(sessionId, false);
            return false;
        }
        return true;
    }
    private boolean createInputStream(int sessionId ,Map<String, Object> args) {
        HashMap<String, Object> channelMap = files.get(sessionId);
        if(channelMap.get("outputStream")!=null)
            return true ;
        PipedInputStream  pipedInputStream = new PipedInputStream();
        OutputStream pipedOutputStream=null;
        try {
            pipedOutputStream = new PipedOutputStream(pipedInputStream);
        } catch (IOException e) {
            e.printStackTrace();
            return false ;
        }
        String channel = (String) args.get(USERMANAGER_P_USERCHANNEL);
        channel = getChannel(channel);
        channelMap.put("channel", channel);
        //创建的一个写出的缓冲流
        BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(pipedOutputStream);
        channelMap.put("outputStream", bufferedOutputStream);
        channelMap.put("foutputStream", pipedOutputStream);
        channelMap.put("inputStream", pipedInputStream);
        boolean result = channelSessionsPut(channel, sessionId);
        if (result == false) {
            closeStream(sessionId, false);
            return false;
        }
        return true;
    }
    private String changeFileName(String realFileName) {
        boolean result = isFileNameValid(realFileName);
        if (result == true)
            return realFileName;
        String fileName = System.currentTimeMillis() + "";
        if (realFileName.indexOf(".") == -1)
            return fileName;
        String suffix = StringUtils.substringAfterLast(realFileName, ".");
        if (suffix == null || suffix.isEmpty())
            return fileName;
        if (suffix.length() > 4)
            return fileName;
        return fileName + "." + suffix;
    }

    private static boolean isFileNameValid(String name) {
        if (name == null || name.length() > 255) {
            return false;
        } else {
            return name.matches("^[a-zA-Z0-9](?:[a-zA-Z0-9 ._-]*[a-zA-Z0-9])?\\.[a-zA-Z0-9_-]+$");
        }
    }

    protected TLMsg closeSession(int sessionId) {
        HashMap<String, Object> channelFile = files.get(sessionId);
        if (channelFile == null)
            return createMsg().setParam(RESULT, false);
        closeStream(sessionId, true);
        TLMsg fmsg = createMsg().addArgs(channelFile);
        netSession.saveSesstiondata(String.valueOf(sessionId), fmsg); // for getFile
        String msgid = (String) channelFile.get(MSG_P_MSGID);
        if (msgid == null)
            return createMsg().setParam(RESULT, true);
        channelFile.remove("outputStream");
        channelFile.remove("outputStream");
        fmsg.setMsgId(msgid);
        return putMsg(msgHandler, fmsg);
    }

    private synchronized boolean channelSessionsPut(String channel, int sessionId) {
        if (channelSessions.containsKey(channel)) {
            List<Integer> sessionList = channelSessions.get((channel));
            if (sessionList.contains(sessionId))
                return false;
            else {
                sessionList.add(sessionId);
                return true;
            }
        }
        CopyOnWriteArrayList<Integer> sessionList = new CopyOnWriteArrayList();
        sessionList.add(sessionId);
        channelSessions.put(channel, sessionList);
        return true;
    }

    protected void onUserLogout(Object fromWho, TLMsg msg) {
        String channel = (String) msg.getParam(USERMANAGER_P_USERCHANNEL);
        if (channel == null)
            channel = Thread.currentThread().getId() + "";
        if (!channelSessions.contains(channel))
            return;
        List<Integer> sessionList = channelSessions.get(channel);
        if (sessionList.isEmpty())
            return;
        for (Integer sessionid : sessionList) {
            HashMap<String, Object> channelFile = files.get(sessionid);
            if (channelFile == null)
                return;
            channelFile.put("logout", true);
            String fileName = (String) channelFile.get("fileName");
            closeStream(sessionid, false);
            File file = new File(fileName);
            if (file.exists())
                file.delete();
        }
    }

    protected void closeStream(int sessionid, boolean ifEnd) {
        HashMap<String, Object> channelFile = files.get(sessionid);
        if (channelFile == null)
            return;
        String logMsg = (ifEnd) ? "文件接收完毕" : "文件接收失败";
        BufferedOutputStream bufferedOutputStream = (BufferedOutputStream) channelFile.get("outputStream");
        if(bufferedOutputStream !=null)
        {
           try {
                bufferedOutputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        OutputStream fileOutputStream = (OutputStream) channelFile.get("foutputStream");
        if(fileOutputStream !=null)
        {
            try {
                fileOutputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        String channel = (String) channelFile.get("channel");
        String fileName = (String) channelFile.get("fileName");
        files.remove(sessionid);
        synchronized (channelSessions) {
            List<Integer> sessionList = channelSessions.get(channel);
            if (sessionList != null && !sessionList.isEmpty()) {
                int index = sessionList.indexOf(sessionid);
                if (index != -1)
                    sessionList.remove(index);
                if (sessionList.isEmpty())
                    channelSessions.remove(channel);
            }
        }
        putLog(fileName + ":" + logMsg, LogLevel.DEBUG, "endfile");
    }

    private String getChannel(String channel) {
        if (channel == null)
            channel = Thread.currentThread().getId() + "";
        return channel;
    }
}
