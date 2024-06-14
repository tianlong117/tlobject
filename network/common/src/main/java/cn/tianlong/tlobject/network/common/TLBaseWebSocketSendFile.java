package cn.tianlong.tlobject.network.common;



import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 创建日期：2020/2/1214:11
 * 描述:
 * 作者:tianlong
 */
abstract public  class TLBaseWebSocketSendFile extends TLBaseModule {

    protected CopyOnWriteArrayList<Integer> sendErrorList = new CopyOnWriteArrayList<>();
    protected TLNetSession netSession;

    public TLBaseWebSocketSendFile(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected TLBaseModule init() {
        putMsg(M_MSGBROADCAST, createMsg().setAction(MSGBROADCAST_REGISTRECEIVER)
                .setParam(MSGBROADCAST_P_MESSAGETYPE, "logout")
                .setParam(MSGBROADCAST_P_RECEIVEACTION, "onUserLogout")
                .setParam(MSGBROADCAST_P_RECEIVER, this));
        netSession = new TLNetSession(name + "_session", moduleFactory);
        netSession.start(null, params);
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case USERMANAGER_SENDFILE:
                returnMsg =sendFile( msg);
                break;
            case "waitFileEnd" :
                returnMsg =waitFileEnd( msg);    
            default:
                ;
        }
        return returnMsg;
    }

    protected  TLMsg waitFileEnd(TLMsg msg) {
        return null ;
    };

    public void setSendError(int sessionId){
        sendErrorList.add(sessionId);
    }
    public TLMsg sendBinary( TLMsg msg) {
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
        msg.removeParam(WEBSOCKET_P_BINARYCMDCODE);
        msg.removeParam(WEBSOCKET_P_BINARYMSGID);
        HashMap<String, Object> contentMap = new HashMap<>();
        contentMap.putAll(msg.getArgs());
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
    public static TLMsg sendFile(TLBaseModule execModule , TLMsg msg,  TLNetSession netSession, IObject sendModule)
    {
        String sessionIdStr =msg.getStringParam(WEBSOCKET_P_BINARYSESSION,null);
        int  sessionId;
        if( sessionIdStr == null)
        {
            sessionId = netSession.makeBinSessionId();
            msg.setParam(WEBSOCKET_P_BINARYSESSION,sessionId);
        }
        else
            sessionId =Integer.valueOf(sessionIdStr) ;
        String appSessionId = null;
        if( msg.isNull("fileGroupId") && !(msg.getStringParam(WEBSOCKET_P_FILEACTIONTYPE,"send").equals(WEBSOCKET_V_FILEACTION_GETFILE)))
        {
            appSessionId = netSession.makeSessionId();
            msg.setParam(WEBSOCKET_P_APPSESSIONID,appSessionId);
        }
        TLMsg sendResultMsg;
        if( msg.getListParam(WEBSOCKET_P_SENDFILEGROUP,null) !=null)
            sendResultMsg= sendFiles(execModule,msg);
        else {
            String fileName = msg.getStringParam(WEBSOCKET_P_SENDFILENAME,"");
            sendResultMsg  = execModule.putMsg(sendModule,msg);
            if(sendResultMsg.getBooleanParam(RESULT,false) ==false)
                return sendResultMsg ;
            execModule.putLog(fileName+" 传输完毕，等候回应" , LogLevel.DEBUG, "sendFile");
            TLMsg  receiveEndMsg= netSession.waitServerReturnUntilTimeOut(String.valueOf(sessionId),null,null,20000,0) ;
            if(receiveEndMsg ==null)
            {
                execModule.putLog(fileName+" 传输失败，没有收到结束回应" , LogLevel.DEBUG, "sendFile");
                return sendResultMsg ;
            }
            byte cmdcode = receiveEndMsg.getByteParam(WEBSOCKET_P_BINARYCMDCODE,(byte)-1);
            if(cmdcode == WEBSOCKET_V_BINARYMFILRECEIVEOVERCODE)
            {
                execModule.putLog(fileName+" 收到回应，传输成功" , LogLevel.DEBUG, "sendFile");
                sendResultMsg.setParam(RESULT,true);
            }
            else
                sendResultMsg.setParam(RESULT,false);
            netSession.removeBinSessionId(sessionId);
            if( appSessionId==null)
                return sendResultMsg ;
        }
        int waitTime =msg.getIntParam(WEBSOCKET_P_SENDFILEAPPWAITTIMEONSEND,0) ;
        TLMsg appResultMsg = netSession.waitServerReturnUntilTimeOut(appSessionId,null,null,waitTime,0) ;
        return  appResultMsg.addMap(sendResultMsg.getArgs()) ;
    }
    public static TLMsg sendFiles(TLBaseModule execModule ,TLMsg msg) {
        ArrayList<String> fileGroup = (ArrayList<String>) msg.getListParam(WEBSOCKET_P_SENDFILEGROUP,null);
        if(fileGroup==null || fileGroup.isEmpty())
            return new TLMsg().setParam(RESULT ,false);
        ArrayList<TLMsg>  msgList =new ArrayList<>();
        int  fileGroupId =msg.getIntParam(WEBSOCKET_P_BINARYSESSION,0);
        if(fileGroupId == 0)
            return new TLMsg().setParam(RESULT ,false);
        msg.removeParam(WEBSOCKET_P_SENDFILEGROUP);
        HashMap<String ,Object> msgArgs= new HashMap<>();
        msgArgs.putAll(msg.getArgs());
        msgArgs.remove(WEBSOCKET_P_SENDFILEAPPWAITTIMEONSEND);
        for(String fileName : fileGroup)
        {
            TLMsg fmsg =new TLMsg() .setAction(USERMANAGER_SENDFILE)
                    .addArgs(msgArgs)
                    .setParam("fileGroupId",fileGroupId)
                    .setSystemParam(INTHREADPOOL,true)
                    .setParam(WEBSOCKET_P_SENDFILENAME,fileName);
            msgList.add(fmsg);
        }
        TLMsg returnMsg= execModule.putMsgGroupByThread(msgList,0);
        ArrayList<TLMsg> resultMsgList = (ArrayList<TLMsg>) returnMsg.getListParam(RESULT,null);
        if(resultMsgList ==null)
            return  new TLMsg().setParam(RESULT,false);
        boolean result =true ;
        HashMap<String,Boolean> fileStatus= new HashMap<>();
        for(TLMsg rmsg :resultMsgList)
        {
            Boolean ifSucessed = rmsg.parseBoolean(RESULT,false) ;
            if(ifSucessed ==false)
                result =false ;
            fileStatus.put((String)rmsg.getParam(WEBSOCKET_P_SENDFILENAME ),ifSucessed);
        }
        return  new TLMsg().setParam(RESULT,result).setParam(WEBSOCKET_R_SENDFILEGROUPRESULT ,fileStatus);
    }
    public TLMsg sendFile(TLMsg msg) {
        int cmdOrder = WEBSOCKET_V_BINARYMFILEDATACMDCODE;
        InputStream inputStream = (InputStream) msg.getParam(WEBSOCKET_P_SENDINPUTSTREAM);
        String filePath = (String) msg.getParam(WEBSOCKET_P_SENDFILENAME);
        String fileName;
        File file ;
        if (inputStream == null)
        {
            file = new File(filePath);
            if (!file.exists())
                return createMsg().setParam(RESULT, false).setParam(WEBSOCKET_P_SENDFILENAME,filePath);
            try {
                inputStream = new FileInputStream(file);
            } catch (FileNotFoundException e) {
                putLog(filePath + " :无该文件" ,LogLevel.WARN, "putFile");
                return createMsg().setParam(RESULT, false).setParam(WEBSOCKET_P_SENDFILENAME,filePath);
            }
            fileName = file.getName();
        } else {
            msg.removeParam(WEBSOCKET_P_SENDINPUTSTREAM);
            fileName = filePath;
        }
        String binaryMsgid = msg.getStringParam(WEBSOCKET_P_BINARYMSGID,WEBSOCKET_V_BINARYRECEIVEFILEMSGID);
        msg.setParam(WEBSOCKET_P_SENDFILENAME, fileName)
                .setParam(WEBSOCKET_P_IFMAKESESSION, true)
                .setParam(WEBSOCKET_P_BINARYCMDCODE, cmdOrder)
                .setParam(WEBSOCKET_P_BINARYMSGID, binaryMsgid);
        TLMsg resultMsg = sendBinary(msg);
        if (resultMsg.getBooleanParam(RESULT, false) == false) {
            try {
                inputStream.close();
            } catch (IOException e) {
                putLog(filePath + " :传输失败", LogLevel.WARN, "putFile");
                e.printStackTrace();
            }
            return resultMsg.setParam(WEBSOCKET_P_SENDFILENAME,fileName);
        }
        int sessionId = resultMsg.getIntParam(WEBSOCKET_P_BINARYSESSION,0);
        if(sessionId ==0)
            return createMsg().setParam(RESULT, false).setParam(WEBSOCKET_P_SENDFILENAME,fileName);
        List userChannels = getUserChannels(msg);
        if (userChannels == null)
            return createMsg().setParam(RESULT, false).setParam(WEBSOCKET_P_SENDFILENAME,fileName);
        byte[] buf = new byte[4096];
        int size;
        int order = 1;   //包序列号 ,从1开始
        Long startTime = moduleFactory.getRunTime(false);
        boolean ifSucess = true;
        putLog( " 开始传送文件:"+filePath, LogLevel.DEBUG, "putFile");
        try {
            while (-1 != (size = inputStream.read(buf)))
            {
               if(sendErrorList.contains(sessionId))
               {
                   ifSucess = false;
                   sendErrorList.remove(new Integer(sessionId));
                   putLog(filePath + " 收到传输错误", LogLevel.WARN, "putFile");
                   break;
               }
                boolean result = binarySend(userChannels, binaryMsgid, (byte) cmdOrder, sessionId, buf, size, order);
                order++;
                if (result == false) {
                    ifSucess = false;
                    break;
                }
            }
            if (ifSucess == true)
            {
                // 传输完毕后，发送结束标志，order为-1
                boolean result = binarySend(userChannels, binaryMsgid, (byte) cmdOrder, sessionId, null, 0, -1);
                if (result == false)
                    ifSucess = false;
            }
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
            putLog(filePath + " :传输失败", LogLevel.WARN, "putFile");
            return createMsg().setParam(RESULT, false).setParam("fileName",fileName);
        }
        Long takeTime = moduleFactory.getRunTime(false) - startTime;
        putLog(filePath + " :传输时间:" + takeTime, LogLevel.DEBUG, "putFile");
        return createMsg().setParam(RESULT, true).setParam(WEBSOCKET_P_SENDFILENAME,fileName);
    }

    abstract protected boolean binarySend(List userChannels, String binaryMsgid, byte cmdCode, int sessionId, byte[] contentStrBytes, int length, int i) ;


    abstract protected List getUserChannels(TLMsg msg) ;

    protected int getBinarySessionId(TLMsg msg) {
        int sessionId = 0;
        if (msg.isNull(WEBSOCKET_P_BINARYSESSION) && msg.parseBoolean(WEBSOCKET_P_IFMAKESESSION, false) == true)
            sessionId = netSession.makeBinSessionId();
        else {
            if (msg.getParam(WEBSOCKET_P_BINARYSESSION) instanceof String)
                sessionId = Integer.valueOf((String) msg.getParam(WEBSOCKET_P_BINARYSESSION));
            else if (msg.getParam(WEBSOCKET_P_BINARYSESSION) instanceof Integer)
                sessionId = (int) msg.getParam(WEBSOCKET_P_BINARYSESSION);
            msg.removeParam(WEBSOCKET_P_BINARYSESSION);
        }
        return sessionId;
    }

}
