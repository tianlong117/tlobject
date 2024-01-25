package cn.tianlong.tlobject.utils;

import cn.tianlong.tlobject.base.TLMsg;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.internal.LinkedTreeMap;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.*;

import static cn.tianlong.tlobject.base.TLParamString.*;

/**
 * 创建日期：2018/4/4 on 8:47
 * 描述:
 * 作者:tianlong
 */

public class TLMsgUtils {
    public static  int binaryHeaderLength=14;
    public static ArrayList<String> msgSystemArgs = new ArrayList<>();
    public static Gson gson ;
    public static Type clientJsonType = new TypeToken<Map<String, Object>>() { }.getType();
    static {
        msgSystemArgs.add(IFLOADMODULE);
        msgSystemArgs.add(IGNOREMODULEISNULL);
        msgSystemArgs.add(IFTASKDEAMON);
        msgSystemArgs.add(IFTASKJOIN);
        msgSystemArgs.add(JOINTIME);
        msgSystemArgs.add(INTHREADPOOL);
        msgSystemArgs.add(IFDOMSGTRANSFERACTION);
        msgSystemArgs.add(THREADPOOLNAME);
        msgSystemArgs.add(RESULTFOR);
        msgSystemArgs.add(RESULTACTION);
        msgSystemArgs.add(PARAMSFROMMSG);
        msgSystemArgs.add(MSGTABLEUSETYPE);
        msgSystemArgs.add(USEMSG);
        msgSystemArgs.add(USEINPUTMSG);
        msgSystemArgs.add(USEPRERETURNMSG);
        msgSystemArgs.add(USEACTIONRETURNMSG);
        msgSystemArgs.add(RETURNACTIONRETURNMSG);
        msgSystemArgs.add(RESULTFORNEXTMSG);
        msgSystemArgs.add(IGNOREBEFORE);
        msgSystemArgs.add(IGNOREAFTER);
        msgSystemArgs.add(TASKRESULTFOR);
        msgSystemArgs.add(TASKRESULTACTION);
        msgSystemArgs.add(TASKRESULTMSG);
        msgSystemArgs.add(TASKRESESSIONDATA);
        msgSystemArgs.add(TASKDELAYTIME);
        msgSystemArgs.add(TASKWAITTIME);
        msgSystemArgs.add(TASKMAINTHREAD);
        msgSystemArgs.add(TASKDELAYTIME);
    }
    public static Gson getGson ()
    {
       if(gson==null)
           gson = new Gson();
        return gson ;
    }
    public static Type getGsonType ()
    {
        if(clientJsonType==null)
            clientJsonType = new TypeToken<Map<String, Object>>() { }.getType();
        return clientJsonType ;
    }
    /**
     * 字符串转换为TLMsg。字符串格式a=方法:d=目的w=是否异步:m=消息id:参数名=参数值
     *
     * @param string
     * @return
     */
    public static TLMsg strToMsg(String string) {
        TLMsg cmdMsg = new TLMsg();
        if (!string.contains(":"))
            return cmdMsg.setAction(string);
        String str[] = string.split(":");
        for (int i = 0; i < str.length; i++) {
            if (!str[i].isEmpty() && str[i].contains("=")) {
                String cmd[] = str[i].split("=");
                if (cmd.length == 2 && !cmd[0].trim().isEmpty()) {
                    String key = cmd[0].trim();
                    String value = cmd[1].trim();
                    cmdMsg = strToMsg(cmdMsg, key, value);
                }
            }
        }
        return cmdMsg;
    }

    public static TLMsg strToMsg(TLMsg msg, String key, String value) {
        switch (key) {
            case "#d":
            case MSG_P_DESTINATION:
                msg.setDestination(value);
                break;
            case "#s":
            case "source":
                msg.setSource(value);
                break;
            case "#a":
            case MSG_P_ACTION:
                msg.setAction(value);
                break;
            case "#w":
            case MSG_P_WAITFLAG:
                msg.setWaitFlag(Boolean.parseBoolean(value));
                break;
            case "#m":
            case MSG_P_MSGID:
                msg.setMsgId(value);
                break;
            case "#n":
            case MSG_P_NEXTMSGID:
                TLMsg nextmsg = new TLMsg().setMsgId(value);
                msg.setNextMsg(nextmsg);
                break;
            case "#ns":
            case "nextMsgStr":
                TLMsg nextmsg2 = strToMsg(value);
                msg.setNextMsg(nextmsg2);
                break;
            default:
               {
                if(msgSystemArgs.contains(key))
                    msg.setSystemParam(key, value);
                else
                {
                    if(key.length()>6 && key.substring(0,6).equals("TLSYS_"))
                        msg.setSystemParam(key, value);
                    else
                       msg.setParam(key, value);
                }
               };
        }
        return msg;
    }

    public static TLMsg mapToMsg(Map<String, String> map) {
        TLMsg msg = new TLMsg();
         return  mapToMsg( msg, map);
    }

    public static TLMsg mapToMsg(TLMsg msg, Map<String, String> map) {
        for (String key : map.keySet()) {
            msg = strToMsg(msg, key, map.get(key));
        }
        return msg;
    }

    public static String msgToStr(TLMsg msg) {
        StringBuilder logBuffer = new StringBuilder();
        logBuffer.append(msgActionToStr(msg));
        logBuffer.append(" params:");
        HashMap<String, Object> args = msg.getArgs();
        if (args != null && !args.isEmpty())
        {
            for (String key : args.keySet())
            {
                logBuffer.append(key);
                logBuffer.append(": ");
                Object value = args.get(key);
                if (value == null)
                    continue;
                if (value instanceof String) {
                    String content = (String) args.get(key);
                    if (content.length() > 20)
                        content = content.substring(0, 20) + "....";
                    logBuffer.append(content);
                } else if (value instanceof Integer || value instanceof Long || value instanceof Double)
                    logBuffer.append(String.valueOf(value));
                else if (value instanceof Date) {
                    SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    logBuffer.append(df.format(value));
                } else if (value instanceof TLMsg) {
                    logBuffer.append("[msg]: \r\n\t");
                    if (!value.equals(msg))
                        logBuffer.append(msgToStr((TLMsg) value));
                } else
                    logBuffer.append(value.toString());
                logBuffer.append(" ; ");
            }
        }
        return logBuffer.toString();
    }

    public static String msgActionToStr(TLMsg msg) {
        StringBuilder logBuffer = new StringBuilder();
        String nowObject = msg.getNowObject();
        String previouts = msg.getPrevious();
        if (previouts != null) {
            logBuffer.append(" previouts:");
            logBuffer.append(previouts);
        }
        if (nowObject != null) {
            logBuffer.append(" nowObject: ");
            logBuffer.append(nowObject);
        }
        String source = msg.getSource();
        if (source != null) {
            logBuffer.append(" source: ");
            logBuffer.append(source);
        }
        String destination = msg.getDestination();
        if (destination != null) {
            logBuffer.append(" destination: ");
            logBuffer.append(destination);
        }
        String msgId = msg.getMsgId();
        if (msgId != null) {
            logBuffer.append(" msgid: ");
            logBuffer.append(msgId);
        }
        String action = msg.getAction();
        if (action != null) {
            logBuffer.append(" action: ");
            logBuffer.append(action);
        }
        return logBuffer.toString();
    }

    public static void msgtofile(TLMsg msg, String filename) {
        File file = new File(filename);
        try {
            file.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            FileOutputStream fos = new FileOutputStream(file);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(msg);
            oos.flush();
            oos.close();
            fos.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String msgToJson(TLMsg msg) {
        GsonBuilder gsonBuilder = new GsonBuilder();
        String jsonString = gsonBuilder.serializeNulls().create().toJson(msg);
        return jsonString;
    }

    public static TLMsg jsonToMsg(String jstr) {
        Gson gson = new Gson();
        try {
            return gson.fromJson(jstr, TLMsg.class);
        } catch (Exception e) {
            return null;
        }
    }
    public static <T> T cloneMsg(T src) throws RuntimeException {
        ByteArrayOutputStream memoryBuffer = new ByteArrayOutputStream();
        ObjectOutputStream out = null;
        ObjectInputStream in = null;
        T dist = null;
        try {
            out = new ObjectOutputStream(memoryBuffer);
            out.writeObject(src);
            out.flush();
            in = new ObjectInputStream(new ByteArrayInputStream(memoryBuffer.toByteArray()));
            dist = (T) in.readObject();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (out != null)
                try {
                    out.close();
                    out = null;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            if (in != null)
                try {
                    in.close();
                    in = null;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
        }
        return dist;
    }

    public static LinkedTreeMap<String, Object> jsonStrToMap(String jstr) {
        if(jstr ==null || jstr.isEmpty())
            return null ;
        LinkedTreeMap<String, Object> contentmap;
        try {
            contentmap = getGson().fromJson(jstr, getGsonType());
        } catch (JsonSyntaxException e) {
            contentmap = null;
        }
        return contentmap;
    }

    public static TLMsg websocketJsonStrToMsg(String jstr) {

        LinkedTreeMap<String, Object> map = jsonStrToMap(jstr);
        if (map == null)
            return null;
        TLMsg msg = new TLMsg();
        String msgid = (String) map.get(MSG_P_MSGID);
        if (msgid != null && !msgid.isEmpty())
            msg.setMsgId(msgid);
        else {
            String action = (String) map.get(MSG_P_ACTION);
            if (action != null && !action.isEmpty())
                msg.setAction(action);
        }
        String destination = (String) map.get(MSG_P_DESTINATION);
        if(destination ==null)
               destination = (String) map.get(MSG_P_MODULE);
        if (destination != null && !destination.isEmpty())
             msg.setDestination(destination);
        if(map.get(MSG_P_SYSTEMARGS)!=null )
        {
            LinkedTreeMap<String, Object> systemArgs = (LinkedTreeMap<String, Object>) map.get(MSG_P_SYSTEMARGS);
            msg.addSystemArgs(systemArgs);
            map.remove(MSG_P_SYSTEMARGS);
        }
        if(map.get(MSG_P_PARAMS)!=null )
        {
            LinkedTreeMap<String, Object> datas = (LinkedTreeMap<String, Object>) map.get(MSG_P_PARAMS);
            msg.addArgs(datas);
        }
        else
            msg.addArgs(map);
        return msg;
    }

    public static   HashMap<String,Object> msgToSocketDataMap(TLMsg msg)
    {
        return  makeSocketDataMap(msg.getMsgId(),  msg.getAction(),  msg.getArgs(),  msg.getSystemArgs(), msg.getDestination());
    }
    public static   HashMap<String,Object> makeSocketDataMap(String action, String destination, Map<String,Object> params)
    {
        return  makeSocketDataMap(null,  action,  params,  null, destination);
    }
    public static   HashMap<String,Object> makeSocketDataMap(String msgid, Map<String,Object> params, Map<String,Object> systemArgs)
    {
        return  makeSocketDataMap( msgid,  null,  params,  systemArgs, null);
    }
    public static   HashMap<String,Object> makeSocketDataMap(String msgid, Map<String,Object> params)
    {
        return  makeSocketDataMap( msgid,  null,  params,  null, null);
    }
    public static   HashMap<String,Object> makeSocketDataMap(String msgid, String action, Map<String,Object> params, Map<String,Object> systemArgs, String destination)
    {
        HashMap<String,Object> map =new HashMap<>();
        if(msgid != null)
            map.put(MSG_P_MSGID,msgid);
        if(action !=null )
            map.put(MSG_P_ACTION,action);
        if(destination !=null)
            map.put(MSG_P_DESTINATION,destination);
        if(params !=null && !params.isEmpty())
            map.put(MSG_P_PARAMS,params);
        if(systemArgs !=null && !systemArgs.isEmpty())
            map.put(MSG_P_SYSTEMARGS,systemArgs);
        return map;
    }
    public static   TLMsg addSystemArgToMsg(String key , Object value , TLMsg msg){
        Map<String,Object> systemArgs;
        if(msg ==null)
        {
            systemArgs =new HashMap<>();
            msg =new TLMsg().setParam(MSG_P_SYSTEMARGS,systemArgs);
        }
        else {
            systemArgs =  msg.getMapParam(MSG_P_SYSTEMARGS,null);
            if(systemArgs ==null)
            {
                systemArgs =new HashMap<>();
                msg.setParam(MSG_P_SYSTEMARGS,systemArgs);
            }
        }
        systemArgs.put(key,value);
        return msg ;
    }

    public static void printMsg(TLMsg msg) {
        if (msg == null)
            return;
        printMap(msgToSocketDataMap(msg));
    }
    public static void printMap(Map<String, Object> map) {
        if (map == null || map.isEmpty())
            return;
        for (String key : map.keySet()) {
            Object value = map.get(key);
            System.out.print(key + ":");
            printObject(value);
        }
    }

    public static void printObject(Object value) {
        if (value == null)
            return;
        if (value instanceof Map)
            printMap((Map<String, Object>) value);
        else if (value instanceof List)
            printList((List) value);
        else if (value instanceof TLMsg) {
            System.out.println("is msg:");
            return;
        } else {
            System.out.println(value.toString());
        }
    }

    public static void printList(List list) {
        if (list == null || list.isEmpty())
            return;
        for (Object value : list) {
            printObject(value);
        }
    }


    public static byte[] enCodeMsgBuf(String msgid , byte cmdCode, int sessionId,byte[] dataBuf ,int  dataLength ,int  order){

        byte[] headBuf =makeHeadBuf(msgid,cmdCode,sessionId,dataLength,  order);
        if(dataLength ==0 || dataBuf ==null)
         return headBuf ;
        byte[] msgBuf = new byte[dataLength + binaryHeaderLength];
        System.arraycopy(headBuf, 0, msgBuf, 0, binaryHeaderLength);
        System.arraycopy(dataBuf, 0, msgBuf, binaryHeaderLength, dataLength);
        return msgBuf ;
    }

    private static byte[] makeHeadBuf(String msgid,  byte cmdCode, int sessionId ,int dataLength,int  order) {
        byte[] buf = new byte[binaryHeaderLength];
        byte msgidCode =msgidToCode(msgid);
        buf[0]=msgidCode;
        buf[1]=cmdCode;
        buf[2] = (byte)((sessionId  >> 24) & 0xFF);
        buf[3] = (byte)((sessionId  >> 16) & 0xFF);
        buf[4] = (byte)((sessionId  >> 8) & 0xFF);
        buf[5] = (byte)(sessionId  & 0xFF);
        buf[6] = (byte)((dataLength  >> 24) & 0xFF);
        buf[7] = (byte)((dataLength  >> 16) & 0xFF);
        buf[8] = (byte)((dataLength  >> 8) & 0xFF);
        buf[9] = (byte)(dataLength  & 0xFF);
        buf[10] = (byte)((order  >> 24) & 0xFF);
        buf[11] = (byte)((order  >> 16) & 0xFF);
        buf[12] = (byte)((order  >> 8) & 0xFF);
        buf[13] = (byte)(order  & 0xFF);
        return buf ;
    }

    public static byte msgidToCode(String msgid) {
        byte msgidCode ;
        switch (msgid) {
            case WEBSOCKET_V_BINARYRECEIVEFILEMSGID:
                msgidCode = 1;
                break;
            case WEBSOCKET_V_BINARYRECEIVEMSGID:
                msgidCode = 2;
                break;
            default:
                msgidCode = 1;
        }
        return msgidCode ;
    }
    public static String codeToMsg(byte code) {
        String  msgid ;
        switch (code) {
            case 1 :
                msgid= WEBSOCKET_V_BINARYRECEIVEFILEMSGID;
                break;
            case 2 :
                msgid= WEBSOCKET_V_BINARYRECEIVEMSGID;
                break;
            default:
                msgid= WEBSOCKET_V_BINARYRECEIVEFILEMSGID;
        }
        return msgid ;
    }
    public static HashMap<String,Object > deCodeMsgBuf(byte[] bytes)
    {
        if (bytes.length<binaryHeaderLength)
            return null;
        HashMap<String,Object > map = new HashMap<>();
        byte msgcode=bytes[0];
        String msgid = codeToMsg(msgcode);
        map.put(MSG_P_MSGID,msgid);
        byte cmdcode=bytes[1];
        map.put(WEBSOCKET_P_BINARYCMDCODE , cmdcode);
        byte[] sessionidarry = new byte[4];
        for(int i=0 ;i <4 ;i++)
            sessionidarry[i] = bytes[2+i];
        int sessionid=byteArrayToInt(sessionidarry); // 线程id
        map.put(WEBSOCKET_P_BINARYSESSION,sessionid);
        byte[] lengthary = new byte[4];
        for(int i=0 ;i <4 ;i++)
            lengthary[i] = bytes[6+i];
        int length=byteArrayToInt(lengthary); // 数据包长
        if (bytes.length<length) {
            return null;
        }
        map.put(WEBSOCKET_P_BINARYDATASIZE,length);
        byte[] orderary = new byte[4];
        for(int i=0 ;i <4 ;i++)
            orderary[i] = bytes[10+i];
        int order=byteArrayToInt(orderary); // 包次序
        map.put(WEBSOCKET_P_BINARYDATAORDER,order);
        byte[] data= new byte[length];
        System.arraycopy(bytes, binaryHeaderLength, data, 0, length);
        map.put(WEBSOCKET_P_CONTENT,data);
        return map ;
    }
    public static TLMsg deCodeMsgBufToMsg(byte[] bytes)
    {
        HashMap<String,Object > content= TLMsgUtils.deCodeMsgBuf(bytes);
        if(content ==null)
            return null ;
        String msgid= (String) content.get(MSG_P_MSGID);
        if(msgid ==null)
            return null ;
        TLMsg msg = new TLMsg().setMsgId(msgid).addArgs(content);
        return msg ;
    }
    private static int bytesToInt(byte[] bs) {
        int a = 0;
        for (int i = bs.length - 1; i >= 0; i--) {
            a += bs[i] * Math.pow(0xFF, bs.length - i - 1);
        }
        return a;
    }
    /**
     * int到byte[] 由高位到低位
     * @param i 需要转换为byte数组的整行值。
     * @return byte数组
     */
    public static byte[] intToByteArray(int i) {
        byte[] result = new byte[4];
        result[0] = (byte)((i >> 24) & 0xFF);
        result[1] = (byte)((i >> 16) & 0xFF);
        result[2] = (byte)((i >> 8) & 0xFF);
        result[3] = (byte)(i & 0xFF);
        return result;
    }

    /**
     * byte[]转int
     * @param bytes 需要转换成int的数组
     * @return int值
     */
    public static int byteArrayToInt(byte[] bytes) {
        int value=0;
        for(int i = 0; i < 4; i++) {
            int shift= (3-i) * 8;
            value +=(bytes[i] & 0xFF) << shift;
        }
        return value;
    }
}
