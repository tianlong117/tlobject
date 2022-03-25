package cn.tianlong.tlobject.network.common;


import cn.tianlong.tlobject.base.MyUnchecckedExceptionhandler;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.modules.TLReUsedModulePool;
import cn.tianlong.tlobject.utils.TLMsgUtils;
import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import com.google.gson.reflect.TypeToken;
import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 创建日期：2020/2/1214:11
 * 描述:
 * 作者:tianlong
 */
public class TLWebSocketReceiveFileModule extends TLBaseModule {

    protected int maxSessions = 100;
    protected int timeOut = 30000;
    protected String filePath;
    protected String msgHandler = "clientMsgHandler";
    protected String fileSessionPoolName ="reUsedSingleThreadPoolGroup";
    protected ConcurrentHashMap<Integer, HashMap<String, Object>> files = new ConcurrentHashMap<>();
    protected TLNetSession netSession;
    protected TLReUsedModulePool sessionPool ;
    protected Type jsonType = new TypeToken<Map<String, Object>>() { }.getType();

    public TLWebSocketReceiveFileModule(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        if (params != null)
        {
            if ( params.get("filePath") != null)
                filePath = params.get("filePath");
            else
                filePath = System.getProperty("user.dir") + "\\cache\\";
            if ( params.get("msgHandler") != null)
                msgHandler = params.get("msgHandler");
            if ( params.get("maxSessions") != null)
                maxSessions = Integer.valueOf(params.get("maxSessions"));
            if ( params.get("timeOut") != null)
                timeOut = Integer.valueOf(params.get("timeOut"));
            if(params.get("fileSessionPoolName")!=null && !params.get("fileSessionPoolName").isEmpty()){
                fileSessionPoolName =params.get("fileSessionPoolName");
            }
        }
    }

    @Override
    protected TLBaseModule init() {
        sessionPool = (TLReUsedModulePool) getNewModule(fileSessionPoolName,fileSessionPoolName);
        netSession = new TLNetSession(name + "_session", moduleFactory);
        netSession.start(null, params);
        TLMsg checkSessionTimeOutMsg =createMsg().setAction("checkSessions")
                .setSystemParam(SESSIONDEAMON, true)
                .setSystemParam(EXCEPTIONHANDLER, new MyUnchecckedExceptionhandler(this, createMsg().setAction("checkSessions")));
        invokeActionInThread("checkSessions",this,checkSessionTimeOutMsg);
        return this;
    }

    protected void checkSessions(Object fromWho, TLMsg msg) {
        do {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            for (Integer sessionId : files.keySet())
            {
                HashMap<String, Object> fileSessionData = files.get(sessionId);
                Long time = (Long) fileSessionData.get("time");
                if ((System.currentTimeMillis() - time) > timeOut)
                {
                    FileClass file = (FileClass) fileSessionData.get("file");
                    if(file !=null)
                    {
                        String fileName = file.getFileName();
                        putLog("file receive timeout:" + fileName, LogLevel.DEBUG, "heckSessions");
                        file.errorOnReceive();
                    }
                    files.remove(sessionId);
                    sessionPool.removeUser(String.valueOf(sessionId));
                    sessionPool.useModuleOver();
                }
            }
        } while (true);
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "receiveBinaryFile":
                returnMsg = receiveBinaryFile(fromWho, msg);
                break;
            case "receiveFile":
                receiveFile( msg);
                break;
            case "getFile":
                returnMsg = getFile(fromWho, msg);
                break;
            default:
                ;
        }
        return returnMsg;
    }

    private TLMsg getFile(Object fromWho, TLMsg msg) {
        int sessionId = netSession.makeBinSessionId();
        TLMsg getFileMsg =new TLMsg();
        getFileMsg.setArgs(msg.getArgs());
        getFileMsg.setParam(WEBSOCKET_P_BINARYSESSION, String.valueOf(sessionId));
        getFileMsg.setParam(WEBSOCKET_P_FILEACTIONTYPE, WEBSOCKET_V_FILEACTION_GETFILE);
        getFileMsg.setMsgId((String) msg.getParam(MSG_P_MSGID));
        TLMsg serverMsg = createMsg().setMsgId("getFile")
                         .setSystemArgs(msg.getSystemArgs())
                        .addMap(TLMsgUtils.msgToSocketDataMap(getFileMsg));
        msg.setParam(WEBSOCKET_P_FILEACTIONTYPE, WEBSOCKET_V_FILEACTION_GETFILE);
        makeFileSessionData(sessionId, msg.getArgs());
        netSession.saveSessionId(String.valueOf(sessionId));
        TLMsg resultMsg = getMsg(this, serverMsg);
        if( resultMsg ==null || resultMsg.getBooleanParam(RESULT, false))
        {
            netSession.removeSessionId(String.valueOf(sessionId));
            return createMsg().setParam(RESULT, false);
        }
        do {
            HashMap<String, Object> fileSessionData = null ;
            try {
                Thread.sleep(4000);
                fileSessionData = files.get(sessionId);
                if (fileSessionData == null)
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

    private TLMsg receiveBinaryFile(Object fromWho, TLMsg msg)
    {
        if (msg.isNull(WEBSOCKET_P_BINARYCMDCODE))
            return createMsg().setParam(RESULT, false);
        byte cmdCode = (byte) msg.getParam(WEBSOCKET_P_BINARYCMDCODE);
        switch (cmdCode) {
            case WEBSOCKET_V_BINARYMFILEDATACMDCODE:
                return dispatchThread( msg);
            default:
                return null;
        }
    }
    protected TLMsg dispatchThread(TLMsg msg)
    {
        int sessionId =  msg.getIntParam(WEBSOCKET_P_BINARYSESSION,0);
        if (sessionId == 0)
            return createMsg().setParam(RESULT, false);
        int order = msg.getIntParam(WEBSOCKET_P_BINARYDATAORDER,-99);
        if(order== -99 )
            return createMsg().setParam(RESULT, false);
        String sessionIdStr =String.valueOf(sessionId) ;
        TLBaseModule threadPool ;
        if(order==0 )
        {
            if (files.size() > maxSessions)
            {
                String channel = (String) msg.getSystemParam(USERMANAGER_P_USERCHANNEL);
                putLog(    " 接收文件数已经达到最大值"+maxSessions, LogLevel.WARN, "receiveFile");
                sendFileErrorMsg(sessionId,channel);
                return null;
            }
            threadPool = (TLBaseModule) sessionPool.getModuleInPool(sessionIdStr);
        }
        else {
            threadPool = (TLBaseModule) sessionPool.getExistUserModuleInPool(sessionIdStr);
            if(threadPool ==null)
               return createMsg().setParam(RESULT, false);
        }
        if(threadPool ==null)
            return  null;
        msg.setDestination(name).setAction("receiveFile");
        TLMsg  threadMsg =createMsg().setAction(THREADPOOL_EXECUTE).setParam(THREADPOOL_P_TASKMSG,msg);
        putMsg(threadPool,threadMsg);
        return createMsg().setParam(RESULT, true);
    }
    private void receiveFile( TLMsg msg)
    {
        String channel ;
        if(!msg.systemParamIsNull(USERMANAGER_P_USERCHANNEL))
           channel = (String)msg.getSystemParam(USERMANAGER_P_USERCHANNEL);
        else
            channel=String.valueOf(Thread.currentThread().getId()) ;
        int sessionId = (int) msg.getParam(WEBSOCKET_P_BINARYSESSION);
        int order = (int) msg.getParam(WEBSOCKET_P_BINARYDATAORDER);
        if (order == 0)
            doWithDataOrder0(msg,sessionId,channel);
        else if (order == -1) //发送完毕标志
            doWithDataOrderEnd(sessionId,channel);
        else
        {
            byte[] bytes = (byte[]) msg.getArrayParam(WEBSOCKET_P_CONTENT,null);
            if(bytes !=null)
               doWithData(bytes,sessionId,order,channel);
        }
        sessionPool.useModuleOver();
    }
    private void doWithDataOrder0(TLMsg msg, int sessionId, String channel)
    {
        byte[] bytes = (byte[]) msg.getArrayParam(WEBSOCKET_P_CONTENT,null);
        if(bytes ==null)
            return ;
        LinkedTreeMap<String, Object> fileParams =deCodeMsg(bytes);
        if(fileParams==null)
            return ;
        String fileName = (String) fileParams.get(WEBSOCKET_P_SENDFILENAME);
        if(fileName ==null)
            return ;
        if(startReceive(sessionId,fileParams) ==false)
        {
            receiverFileError(fileName,sessionId,channel);
            return;
        }
    }
    private boolean  startReceive(int sessionId ,LinkedTreeMap<String, Object> fileParams)
    {
        if(fileParams.containsKey("fileGroupId") )
        {
            int fileGoupId = ((Double) fileParams.get("fileGroupId")).intValue();
            fileParams.put("fileGroupId",fileGoupId) ;
        }
        HashMap<String, Object> fileSessionData = files.get(sessionId);
        FileClass fileObj ;
        String fileName =  (String) fileParams.get(WEBSOCKET_P_SENDFILENAME);
        if (fileSessionData == null)
        {
            fileObj =makeFileSessionData(sessionId, fileParams);
            if (fileObj.createFile() == false)
            {
                putLog(fileName+"  创建文件错误" , LogLevel.DEBUG, "receiveFile");
                return false;
            }
            fileSessionData = files.get(sessionId);
            if( fileSessionData.containsKey("fileGroupId") )
            {
                int fileGoupId = (int) fileSessionData.get("fileGroupId");
                String msgId = (String) fileSessionData.get(MSG_P_MSGID) ;
                String appSessionId = (String) fileSessionData.get(WEBSOCKET_P_APPSESSIONID);
                fileSessionData.remove(MSG_P_MSGID);
                fileSessionData.remove(WEBSOCKET_P_APPSESSIONID);
                makeFileGroupSessionData(fileGoupId ,sessionId,msgId,(Map<String, Object>) fileSessionData.get(MSG_P_PARAMS),fileObj,appSessionId );
            }
        }
        else
        {
           String actionType = (String) fileSessionData.get(WEBSOCKET_P_FILEACTIONTYPE);
            if(actionType ==null )   // 已经有接收文件数据 ，sessionID重复，返回错误
                return false ;
            fileObj = (FileClass) fileSessionData.get("file");
            if(fileSessionData.get(WEBSOCKET_P_BINARYDATAIFRETURNSTREAM)!=null && (boolean)fileSessionData.get(WEBSOCKET_P_BINARYDATAIFRETURNSTREAM) ==true)
            {
                if (fileObj.createInputStream() == false)
                {
                    putLog(fileName+"  创建文件流错误" , LogLevel.DEBUG, "receiveFile");
                    return false;
                }
                else
                {
                    TLMsg fmsg = createMsg().addArgs(fileSessionData);
                    netSession.saveSesstiondata(String.valueOf(sessionId), fmsg); // for getFile
                }
            }
            else
            {
                if (fileObj.createFile() == false)
                {
                    putLog(fileName+"  创建文件错误" , LogLevel.DEBUG, "receiveFile");
                    return false;
                }
            }
        }
        fileSessionData.put("order", 0);
        fileSessionData.put("time", System.currentTimeMillis());
        return true ;
    }

    private FileClass makeFileSessionData(int sessionId, Map<String, Object> args) {

        String realFileName = (String) args.get(WEBSOCKET_P_SENDFILENAME);
        realFileName = changeFileName(realFileName);
        FileClass fileObj = new FileClass(realFileName,sessionId,filePath);
        HashMap<String, Object> fileSessionData = new HashMap<>();
        if(args.containsKey(MSG_P_MSGID))
        {
            fileSessionData.put(MSG_P_MSGID,args.get(MSG_P_MSGID));
            args.remove(MSG_P_MSGID);
        }
        if(args.containsKey("fileGroupId"))
        {
            fileSessionData.put("fileGroupId",args.get("fileGroupId"));
            args.remove("fileGroupId");
        }
        if(args.containsKey(WEBSOCKET_P_APPSESSIONID))
        {
            fileSessionData.put(WEBSOCKET_P_APPSESSIONID,args.get(WEBSOCKET_P_APPSESSIONID));
            args.remove(WEBSOCKET_P_APPSESSIONID);
        }
        if(args.containsKey(WEBSOCKET_P_BINARYDATAIFRETURNSTREAM))
        {
            fileSessionData.put(WEBSOCKET_P_BINARYDATAIFRETURNSTREAM,args.get(WEBSOCKET_P_BINARYDATAIFRETURNSTREAM));
            args.remove(WEBSOCKET_P_BINARYDATAIFRETURNSTREAM);
        }
        fileSessionData.put(WEBSOCKET_P_FILEACTIONTYPE,args.get(WEBSOCKET_P_FILEACTIONTYPE));
        fileSessionData.put(MSG_P_PARAMS,args);
        fileSessionData.put("file",fileObj);
        fileSessionData.put("time", System.currentTimeMillis());
        fileSessionData.put("order", -1);
        files.put(sessionId, fileSessionData);
        putLog(sessionId + "make File Session Data:" + realFileName, LogLevel.DEBUG, "startFile");
        return fileObj;
    }

    private synchronized void makeFileGroupSessionData(int fileGoupId, int fileSessionId, String msgid, Map<String, Object> params, FileClass file,String appSessionId)
    {
        HashMap<String, Object> groupMap =files.get(fileGoupId);
        if(groupMap ==null)
        {
            groupMap = new HashMap<>();
            ConcurrentHashMap<Integer, FileClass> fileList = new ConcurrentHashMap<>();
            fileList.put(fileSessionId,file);
            groupMap.put("time", System.currentTimeMillis());
            groupMap.put("receiveType", "group");
            groupMap.put(MSG_P_MSGID, msgid);
            groupMap.put("files", fileList);
            groupMap.put(MSG_P_PARAMS,params);
            groupMap.put(WEBSOCKET_P_APPSESSIONID,appSessionId) ;
            files.put(fileGoupId, groupMap);
        }
        else {
            ConcurrentHashMap<Integer, FileClass> fileList = (ConcurrentHashMap<Integer, FileClass>) groupMap.get("files");
            fileList.put(fileSessionId,file);
        }
    }
    private void doWithDataOrderEnd( int sessionId,  String channel)
    {
        HashMap<String, Object> fileSessionData = files.get(sessionId);
        if (fileSessionData == null)
            return ;
        files.remove(sessionId) ;
        sessionPool.removeUser(String.valueOf(sessionId));
        FileClass fileObj = (FileClass) fileSessionData.get("file");
        String fileName = fileObj.getFileName();
        if( fileObj.receiverOver() ==false)
        {
            putLog(fileName+" 文件接收错误，发送错误回应" , LogLevel.DEBUG, "receiveFile");
            receiverFileError(fileName,sessionId,channel);
            return;
        }
        putLog(fileName+" 文件接收完毕，发送完毕回应" , LogLevel.DEBUG, "receiveFile");
        sendFileSucess(sessionId,channel);
        if(fileSessionData.containsKey("fileGroupId") )
        {
            int fileGoupId = (int) fileSessionData.get("fileGroupId");
            Map<String,Object>  groupMap=files.get(fileGoupId);
            if(groupMap ==null)
                return  ;
            if(ifFileGroupAllOver(groupMap) ==true)
            {
          //      sendFileSucess(fileGoupId,channel);
                files.remove(fileGoupId) ;
                String msgid= (String) groupMap.get(MSG_P_MSGID);
                if(msgid==null )
                    return  ;
                ConcurrentHashMap<Integer, FileClass> fileList = (ConcurrentHashMap<Integer, FileClass>) groupMap.get("files");
                ArrayList<HashMap<String,Object>> receivedFiles = new ArrayList<>();
                for( FileClass file : fileList.values())
                    receivedFiles.add(file.getFileParam());
                Map<String,Object> fileParams= (Map<String, Object>) groupMap.get(MSG_P_PARAMS) ;
                TLMsg fmsg =createMsg().setMsgId(msgid).setParam(WEBSOCKET_R_RECEIVEDFILEGROUP,receivedFiles)
                        .setParam(MSG_P_PARAMS,fileParams);
                TLMsg resultMsg =putMsg(msgHandler, fmsg);
                String appSessionId = (String) groupMap.get(WEBSOCKET_P_APPSESSIONID);
                if(appSessionId !=null)
                    putAppSessionResult(appSessionId,resultMsg,channel);
            }
        }
        else {
            String msgid = (String) fileSessionData.get(MSG_P_MSGID);
            Map<String,Object> fileParams= (Map<String, Object>) fileSessionData.get(MSG_P_PARAMS) ;
            TLMsg resultMsg =putFileMsgid(msgid,sessionId,fileObj.getFileParam(), fileParams);
            String appSessionId = (String) fileSessionData.get(WEBSOCKET_P_APPSESSIONID);
            if(appSessionId !=null)
                putAppSessionResult(appSessionId,resultMsg,channel);
        }
    }
    private void putAppSessionResult(String appSessionId, TLMsg resultMsg ,String channel)
    {
        ArrayList<TLMsg> sessionMsgList = msgTable.get("returnAppResult");
        if(sessionMsgList ==null || sessionMsgList.isEmpty())
            return;
        TLMsg sessionMsg =sessionMsgList.get(0);
        sessionMsg.setSystemParam(USERMANAGER_P_USERCHANNEL,channel);
        if(resultMsg ==null)
            resultMsg =new TLMsg();
        if(sessionMsg.getStringParam("sesstionType","server").equals("server"))
            resultMsg.setSystemParam(WEBSOCKET_P_SESSION,appSessionId);
        else
            resultMsg.setSystemParam(WEBSOCKET_P_NOTIFYID,appSessionId);
        sessionMsg.setArgs(TLMsgUtils.msgToSocketDataMap(resultMsg)) ;
        getMsg(this, sessionMsg);
    }

    private void doWithData(byte[] bytes, int sessionId,int order,  String channel) {
        {
            HashMap<String, Object> fileSessionData = files.get(sessionId);
            if (fileSessionData == null)
                return ;
            FileClass fileObj = (FileClass) fileSessionData.get("file");
            String fileName = fileObj.getFileName();
            int lastOrder = (int) fileSessionData.get("order");
            if (order - lastOrder != 1)
            {
                putLog(fileName+" 文件接收错误，数据包次序错误" , LogLevel.DEBUG, "receiveFile");
                fileObj.errorOnReceive();
                receiverFileError(fileName,sessionId,channel);
                return;
            }
            Long time = System.currentTimeMillis() ;
            fileSessionData.put("order", order);
            fileSessionData.put("time", time);
            if(fileObj.saveFileData(bytes)==false)
            {
                putLog(fileName+" 文件接收错误，数据保存错误" , LogLevel.DEBUG, "receiveFile");
                fileObj.errorOnReceive();
                receiverFileError(fileName,sessionId,channel);
                return;
            }
            if(fileSessionData.containsKey("fileGroupId") )
            {
                int fileGoupId = (int) fileSessionData.get("fileGroupId");
                if (setFileGroupSessionTime( fileGoupId ,time ) == false)
                {
                    fileObj.errorOnReceive();
                    receiverFileError(fileName,sessionId,channel);
                    return;
                }
            }
        }
    }

    private void receiverFileError(String fileName, int sessionId, String channel) {
        files.remove(sessionId) ;
        sessionPool.removeUser(String.valueOf(sessionId));
        sendFileErrorMsg(sessionId,channel);
    }

    private void sendFileSucess(int sessionId, String channel) {
        TLMsg overmsg = createMsg().setParam(WEBSOCKET_P_BINARYSESSION,sessionId)
                .setParam(WEBSOCKET_P_BINARYCMDCODE,WEBSOCKET_V_BINARYMFILRECEIVEOVERCODE)
                .setSystemParam(USERMANAGER_P_USERCHANNEL,channel)
                .setParam(WEBSOCKET_P_BINARYMSGID,USERMANAGER_RECEIVEBINARY);
        getMsg(this,overmsg.setMsgId("sendBinary")) ;
    }

    private TLMsg putFileMsgid(String msgid ,int sessionId,Map<String,Object>fileParams,Map<String,Object>sendParams ) {
        TLMsg fmsg = createMsg().addMap(fileParams);
        if(sendParams !=null)
            fmsg.setParam(MSG_P_PARAMS,sendParams);
        netSession.saveSesstiondata(String.valueOf(sessionId), fmsg); // for getFile
        if(msgid !=null)
        {
            fmsg.setMsgId(msgid);
            return   putMsg(msgHandler, fmsg);
        }
        return null ;
    }

    private LinkedTreeMap<String,Object> deCodeMsg(byte[] bytes) {
        String str = new String(bytes);
        return new Gson().fromJson(str, jsonType);
    }

    protected void sendFileErrorMsg(int sessionId, String channel){
        TLMsg msg = createMsg().setParam(WEBSOCKET_P_BINARYSESSION,sessionId)
                .setParam(WEBSOCKET_P_BINARYCMDCODE,WEBSOCKET_V_BINARYMFILEDATACMDERRORCODE)
                .setSystemParam(USERMANAGER_P_USERCHANNEL,channel)
                .setParam(WEBSOCKET_P_BINARYMSGID,USERMANAGER_RECEIVEBINARY);
        getMsg(this,msg.setMsgId("sendBinary")) ;
    }

    private Boolean setFileGroupSessionTime(int fileGoupId, Long time) {
        HashMap<String, Object> groupMap =files.get(fileGoupId);
        if(groupMap ==null)
            return false ;
        groupMap.put("time", time);
        return true ;
    }

    private TLMsg returnFileMsg(HashMap fileMap) {
        String tmpfile = (String) fileMap.get(WEBSOCKET_P_SENDFILENAME);
        String realFileName = (String) fileMap.get(WEBSOCKET_R_SENDREALFILENAME);
        String savePath = (String) fileMap.get("savePath");
        File tmpFileObj = new File(tmpfile);
        if (savePath == null || savePath.isEmpty())
            savePath = tmpFileObj.getParent();
        String realfilePath = savePath + File.separator + realFileName;
        File realFile = new File(realfilePath);
        if (realFile.exists())
            realFile.delete();
        tmpFileObj.renameTo(realFile);
        return createMsg().setParam(WEBSOCKET_P_SENDFILENAME, realFileName)
                .setParam("savePath", savePath)
                .setParam("filePath", realfilePath);
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

    private boolean ifFileGroupAllOver( Map<String,Object>  groupMap) {
        synchronized (groupMap)
        {
            if(groupMap.containsKey("ifEnd"))
                return false ;
            ConcurrentHashMap<Integer, FileClass> fileList = (ConcurrentHashMap<Integer, FileClass>) groupMap.get("files");
            for( FileClass file : fileList.values())
            {
                if(file.isIfEnd() ==false)
                    return false ;
            }
            groupMap.put("ifEnd",true) ;
            return true ;
        }
    }
}
