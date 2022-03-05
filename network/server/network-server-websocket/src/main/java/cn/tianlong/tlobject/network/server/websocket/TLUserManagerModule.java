package cn.tianlong.tlobject.network.server.websocket;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.network.common.TLJWT;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.network.common.TLBaseWebSocketSendFile;
import cn.tianlong.tlobject.network.common.TLNetSession;
import cn.tianlong.tlobject.utils.TLMsgUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.lang.Thread.sleep;

public class TLUserManagerModule extends TLBaseModule {
    protected String userType = "token";
    protected String tokenSecret = "sdfsdfsdfsdfsdfwervdgert";
    protected String tokenIssure = "qinqin";
    protected String serverName;
    protected int tokenExpireMinute = 30;
    protected String serverModule;
    protected String userRouteModule;
    protected HashMap<String, String> users;
    protected boolean logInOne = false;  //限制是否多次登录
    protected Map<String, String> channelToUser = new ConcurrentHashMap<String, String>();
    protected Map<String, CopyOnWriteArrayList<HashMap<String, String>>> usersInfo = new ConcurrentHashMap<String, CopyOnWriteArrayList<HashMap<String, String>>>();
    protected TLNetSession netSession ;
    protected binarySendModule binarySendModule;
    protected String msgBroadCast ;

    public TLUserManagerModule(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("serverName") != null)
                serverName = params.get("serverName");
            if (params.get("userRouteModule") != null)
                userRouteModule = params.get("userRouteModule");
            if (params.get("serverModuleName") != null)
                serverModule = params.get("serverModuleName");
            if (params.get("msgBroadCast") != null)
                msgBroadCast = params.get("msgBroadCast");
            if (params.get("userType") != null)
                userType = params.get("userType");
            if (params.get("tokenSecret") != null)
                tokenSecret = params.get("tokenSecret");
            if (params.get("tokenIssure") != null)
                tokenIssure = params.get("tokenIssure");
            if (params.get("tokenExpireMinute") != null)
                tokenExpireMinute = Integer.parseInt(params.get("tokenExpireMinute"));
            if (params.get("logInOne") != null)
                logInOne = Boolean.parseBoolean(params.get("logInOne"));
            if (params.get("users") != null && !params.get("users").isEmpty())
            {
                String usersStr[] = params.get("users").trim().split(";");
                users = new HashMap<>();
                for (int i = 0; i < usersStr.length; i++) {
                    if (!usersStr[i].isEmpty() && usersStr[i].contains(":")) {
                        String usersArray[] = usersStr[i].trim().split(":");
                        users.put(usersArray[0].trim(), usersArray[1].trim());
                    }
                }
            }
        }
    }

    @Override
    protected TLBaseModule init() {
        netSession =new TLNetSession(name+"_session",moduleFactory);
        netSession.start(null,params);
        binarySendModule=new binarySendModule("binarySendModule",moduleFactory);
        binarySendModule.start(null,params);
        return this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case USERMANAGER_LOGIN:
                returnMsg = login(fromWho, msg);
                break;
            case USERMANAGER_PUTTOUSER:
                returnMsg = putToUser(fromWho, msg);
                break;
            case USERMANAGER_GETUSERBYCHANNEL:
                returnMsg = getUserByChannel(fromWho, msg);
                break;
            case USERMANAGER_GETUSERINFOBYCHANNEL:
                returnMsg = getUserInfoByChannel(fromWho, msg);
                break;
            case USERMANAGER_GETUSERIPBYCHANNEL:
                returnMsg = getUserIpByChannel(fromWho, msg);
                break;
            case USERMANAGER_GETUSERCHANNELS:
                returnMsg = getUserChannels(fromWho, msg);
                break;
            case USERMANAGER_GETUSERCHANNELOBJ:
                returnMsg = getUserChannelOBJ(fromWho, msg);
                break;
            case USERMANAGER_GETUSERS:
                returnMsg = getUsers(fromWho, msg);
                break;
            case USERMANAGER_LOGOUT:
                returnMsg = logout(fromWho, msg);
                break;
            case "otherLogin":
                returnMsg = otherLogin(fromWho, msg);
                break;
            case "fromRouteServer":
                fromRouteServer(fromWho, msg);
                break;
            case WEBSOCKET_SENDFILE:
                returnMsg = sendFile(fromWho, msg);
                break;
            case USERMANAGER_SENDBINARY:
                returnMsg = sendBinary(fromWho, msg);
                break;
            case USERMANAGER_SENDFILEERROR:
                 sendFileError( fromWho,msg);;
                break;
            default:
                ;
        }
        return returnMsg;
    }

    private void   sendFileError(Object fromWho, TLMsg msg) {
        if(msg.isNull(WEBSOCKET_P_BINARYSESSION) && !(msg.getParam(WEBSOCKET_P_BINARYSESSION) instanceof  Integer ))
            return ;
        int sessionId = (int) msg.getParam(WEBSOCKET_P_BINARYSESSION);
        binarySendModule.setSendError(sessionId);
    }

    private boolean binarySend(ArrayList<Channel> userChannels, String binaryMsgid, byte cmdCode, int sessionId, byte[] buf, int size, int order)
    {
        byte[] msgbuf = TLMsgUtils.enCodeMsgBuf(binaryMsgid,  cmdCode, sessionId, buf,size,order);
        return putBinaryToChannel( userChannels, msgbuf);
    }

    protected TLMsg sendBinary(Object fromWho, TLMsg msg) {
        return binarySendModule.sendBinary(msg);
    }
    protected TLMsg sendFile(Object fromWho, TLMsg msg) {
        return binarySendModule.sendFile(msg);
    }

    private ArrayList<Channel> getUserChannels(TLMsg msg) {
        ArrayList<String> channels =getChannelsInMsg(msg);
        ArrayList<Channel> userChannels ;
        if (channels != null){
            TLMsg toServer = createMsg().setAction(SOCKETSERVER_GETCHANNEL);
            if (channels.size() == 1)
                toServer.setParam(NETTY_CHANNEL, channels.get(0));
            else {
                String[] strings = new String[channels.size()];
                channels.toArray(strings);
                toServer.setParam(NETTY_CHANNEL, strings);
            }
            TLMsg resultMsg = putMsg(serverModule, toServer);
            userChannels = (ArrayList<Channel>) resultMsg.getParam(NETTY_CHANNELOBJ);
        }
        else
        {
            TLMsg resultMsg = getUserChannelOBJ(this, msg);
            userChannels = (ArrayList<Channel>) resultMsg.getParam(NETTY_CHANNELOBJ);
        }
        return userChannels ;
    }

    protected Boolean putBinaryToChannel(List<Channel> userChannels,byte[] msgbuf){

        if(userChannels ==null || userChannels.isEmpty())
            return false;
        for (Channel channel : userChannels)
        {
            if (channel ==null)
                continue;
            if(channel.isActive())
            {
                ByteBuf byteBuf = Unpooled.directBuffer(msgbuf.length);
                byteBuf.writeBytes(msgbuf, 0, msgbuf.length);
                BinaryWebSocketFrame tws = new BinaryWebSocketFrame(byteBuf);
                Boolean isWrite ;
                do{
                    if(!channel.isActive())
                    {
                        userChannels.remove(channel)  ;
                        break;
                    }
                   isWrite =channel.isWritable();
                    if(isWrite ==true)
                        channel.writeAndFlush(tws);
                    else{
                        try {
                            sleep(1);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }while (isWrite==false);
            }
            else
                userChannels.remove(channel)  ;
            if(userChannels.isEmpty())
                return false ;
        }
        return true ;
    }
    private TLMsg otherLogin(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam(USERMANAGER_P_USERID);
        ArrayList<String> channels = getUserChannelAsList(userid);
        if (channels == null || channels.isEmpty())
            return null;
        putContentToChanels("otherlogin", channels);
        return putMsg(this, createMsg().setAction(USERMANAGER_LOGOUT).setParam(USERMANAGER_P_USERID, userid));
    }

    private void fromRouteServer(Object fromWho, TLMsg msg) {
        Map datas = (Map) msg.getParam("datas");
        String userid = (String) datas.get(USERMANAGER_P_USERID);
        TLMsg rmsg = createMsg().setParam(WEBSOCKET_P_CONTENT, datas).setParam(USERMANAGER_P_USERID, userid);
        putToUser(fromWho, rmsg);
    }

    private TLMsg getUserChannels(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam(USERMANAGER_P_USERID);
        if(userid ==null)
            return createMsg();
        ArrayList<String> channels = getUserChannelAsList(userid);
        return createMsg().setParam(USERMANAGER_R_USERCHANNEL, channels);
    }

    private TLMsg getUserChannelOBJ(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam(USERMANAGER_P_USERID);
        ArrayList<String> channels = getUserChannelAsList(userid);
        if (channels == null)
            return createMsg().setParam(USERMANAGER_R_USERCHANNELOBJ, null);
        TLMsg toServer = createMsg().setAction(SOCKETSERVER_GETCHANNEL);
        if (channels.size() == 1)
            toServer.setParam(NETTY_CHANNEL, channels.get(0));
        else {
            String[] strings = new String[channels.size()];
            channels.toArray(strings);
            toServer.setParam(NETTY_CHANNEL, strings);
        }
        return putMsg(serverModule, toServer);
    }

    private TLMsg getUsers(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam(USERMANAGER_P_USERID);
        if (userid != null) {
            CopyOnWriteArrayList<HashMap<String, String>> userChannels = usersInfo.get(userid);
            return createMsg().setParam(RESULT, userChannels);
        } else {
            Set users = usersInfo.keySet();
            return createMsg().setParam(RESULT, users);
        }
    }

    private TLMsg getUserByChannel(Object fromWho, TLMsg msg) {
        String channel = (String) msg.getParam(USERMANAGER_P_USERCHANNEL);
        String userid = channelToUser.get(channel);
        return createMsg().setParam(USERMANAGER_P_USERID, userid);
    }

    private TLMsg getUserInfoByChannel(Object fromWho, TLMsg msg) {
        String channel = (String) msg.getParam(USERMANAGER_P_USERCHANNEL);
        String userid = channelToUser.get(channel);
        if (userid == null)
            return createMsg().setParam(USERMANAGER_R_USERINFO, null);
        CopyOnWriteArrayList<HashMap<String, String>> userChannels = usersInfo.get(userid);
        if (userChannels == null)
            return createMsg().setParam(USERMANAGER_R_USERINFO, null);
        for (HashMap<String, String> channelMap : userChannels) {
            if (channelMap.get("channel").equals(channel)) {
                return createMsg().setParam(USERMANAGER_R_USERINFO, channelMap);
            }
        }
        return createMsg().setParam(USERMANAGER_R_USERINFO, null);
    }

    protected TLMsg getUserIpByChannel(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = getUserInfoByChannel(fromWho, msg);
        HashMap<String, String> userInfo = (HashMap<String, String>) returnMsg.getParam(USERMANAGER_R_USERINFO);
        if (userInfo == null)
            return createMsg().setParam(USERMANAGER_R_USERIP, null);
        return createMsg().setParam(USERMANAGER_R_USERIP, userInfo.get("ip"));
    }

    protected TLMsg login(Object fromWho, TLMsg msg) {
        String userid = checkUser(msg);
        if (userid == null)
            return null;
        String userServerName = serverName;
        if (userServerName == null)
            userServerName = (String) msg.getParam(USERMANAGER_P_SERVERNAME);
        String userSource = (String) msg.getParam(USERMANAGER_P_USERSOURCE);
        String channel = (String) msg.getParam(USERMANAGER_P_USERCHANNEL);
        String ip = (String) msg.getParam(USERMANAGER_P_USERIP);
        if (logInOne) {
            if (usersInfo.containsKey(userid)) {
                ArrayList<String> channels = getUserChannelAsList(userid);
                if (!channels.contains(channel)) {
                    putContentToChanels("otherlogin", channels);
                    putMsg(this, createMsg().setAction(USERMANAGER_LOGOUT).setParam(USERMANAGER_P_USERID, userid));
                } else {
                    return createMsg().setParam(USERMANAGER_R_LOGINRESULT, "0000").setParam(USERMANAGER_P_USERID, userid)
                            .setParam(USERMANAGER_P_USERCHANNEL, channel).setParam(USERMANAGER_P_SERVERNAME, userServerName).setParam(USERMANAGER_P_USERIP, ip)
                            .setParam("message", "ok");
                }
            } else if (userRouteModule != null) {
                HashMap<String, Object> datas = new HashMap<>();
                datas.put(USERMANAGER_P_USERID, userid);
                TLMsg qmsg = createMsg().setAction(USERROUTE_TOSERVER)
                        .setParam(WEBSOCKET_P_CONTENT, datas)
                        .setParam(USERMANAGER_P_USERID, userid)
                        .setParam("msgid", "otherLogin");
                putMsg(userRouteModule, qmsg);
            }
            userLogin(userid, userSource, channel, ip);
            putLog("用户login: " + userid + "  " + ip + "  " + channel, LogLevel.DEBUG, "login");
        } else {
            if (usersInfo.containsKey(userid)) {
                putLog("用户再次login: " + userid + "  " + ip, LogLevel.INFO, "login");
                addUserChannel(userid, userSource, channel, ip);
            } else {
                userLogin(userid, userSource, channel, ip);
                putLog("用户login: " + userid + "  " + ip + "  " + channel, LogLevel.DEBUG, "login");
            }
        }
        if(msgBroadCast!=null){
            TLMsg bmsg = createMsg().setDestination(msgBroadCast).setAction(MSGBROADCAST_BROADCAST)
                    .setParam(MSGBROADCAST_P_MESSAGETYPE, C_MESSAGETYPE_LOGIN)
                    .setParam(USERMANAGER_P_SERVERNAME, userServerName)
                    .setParam(USERMANAGER_P_USERID, userid)
                    .setParam(USERMANAGER_P_USERIP, ip)
                    .setParam(USERMANAGER_P_USERCHANNEL, channel);
            putMsg(msgBroadCast, bmsg);
        }
        return createMsg().setParam(USERMANAGER_R_LOGINRESULT, "0000").setParam(USERMANAGER_P_USERID, userid)
                .setParam(USERMANAGER_P_USERCHANNEL, channel).setParam(USERMANAGER_P_SERVERNAME, userServerName).setParam(USERMANAGER_P_USERIP, ip)
                .setParam("message", "ok");
    }

    private TLMsg logout(Object fromWho, TLMsg msg) {
        String channel = (String) msg.getParam(USERMANAGER_P_USERCHANNEL);
        String userid;
        ArrayList<String> channels;
        if (channel != null)
            userid = logoutByChannel(channel);
        else {
            userid = (String) msg.getParam(USERMANAGER_P_USERID);
            if (userid != null) {
                channels = logoutByUserid(userid);
                if (channels != null) {
                    String[] array = channels.toArray(new String[channels.size()]);
                    channel = StringUtils.join(array, ";");
                }
            }
        }
        String userverName = serverName;
        if (userverName == null)
            userverName = (String) msg.getParam(USERMANAGER_P_SERVERNAME);
        if(msgBroadCast!=null)
        {
            TLMsg bmsg = createMsg().setDestination(msgBroadCast).setAction(MSGBROADCAST_BROADCAST)
                    .setParam(MSGBROADCAST_P_MESSAGETYPE, C_MESSAGETYPE_LOGOUT)
                    .setParam(USERMANAGER_P_SERVERNAME, userverName)
                    .setParam(USERMANAGER_P_USERID, userid)
                    .setParam(USERMANAGER_P_USERCHANNEL, channel);
            putMsg(msgBroadCast, bmsg);
        }
        return createMsg().setParam(USERMANAGER_P_USERID, userid)
                .setParam(USERMANAGER_P_USERCHANNEL, channel)
                .setParam(USERMANAGER_P_SERVERNAME, userverName);
    }

    protected ArrayList<String> logoutByUserid(String userid) {
        ArrayList<String> channels = getUserChannelAsList(userid);
        if (channels == null)
            return null;
        for (String channel : channels)
            channelToUser.remove(channel);
        usersInfo.remove(userid);
        putLog("用户logout:" + userid, LogLevel.DEBUG, "logout");
        putMsg(serverModule, createMsg().setAction("closeChannel").setParam(NETTY_CHANNEL, channels));
        return channels;
    }

    protected String logoutByChannel(String channel) {
        String userid = channelToUser.get(channel);
        if (userid == null)
            return null;
        channelToUser.remove(channel);
        List channels = delUserChannel(userid, channel);
        if (channels == null || channels.size() == 0)
            usersInfo.remove(userid);
        putLog("用户logout:" + userid, LogLevel.DEBUG, "logout");
        return userid;
    }

    private TLMsg putToUser(Object fromWho, TLMsg msg) {
        ArrayList<String> channels =getChannelsInMsg(msg);
        if (channels != null )
            return putContentToChanels(msg.getParam(WEBSOCKET_P_CONTENT), channels);
        Object userid = msg.getSystemParam(USERMANAGER_P_USERID);
        if(userid ==null)
            msg.getParam(USERMANAGER_P_USERID);
        if (userid == null && msg.parseBoolean("broadcast", false)) {
            TLMsg toServer = createMsg().setAction("putToClient").setParam(MSG_CONTENT, msg.getParam(WEBSOCKET_P_CONTENT));
            return putMsg(serverModule, toServer);
        }
        if (userid instanceof String)
            channels = getUserChannelAsList((String) userid);
        else if (userid instanceof String[]) {
            String[] array = (String[]) userid;
            for (int i = 0; i < array.length; i++) {
                String toUserid = array[i];
                getUserChannel(toUserid, channels);
            }
        } else if (userid instanceof List) {
            List array = (List) userid;
            for (int i = 0; i < array.size(); i++) {
                String toUserid = (String) array.get(i);
                getUserChannel(toUserid, channels);
            }
        }
        if (channels == null || channels.isEmpty())
        {
            if (userRouteModule != null)
            {
                TLMsg returnMsg = putMsg(userRouteModule, msg.setAction(USERROUTE_ROUSER));
                if (returnMsg != null ) {
                    Boolean result = returnMsg.parseBoolean(RESULT,false);
                    if (result == true)
                        return returnMsg.setParam(RESULT, 1);
                    else
                        return returnMsg.setParam(RESULT, 0);
                } else
                    return returnMsg;
            } else
                return createMsg().setParam(RESULT, 0);
        }
        return putContentToChanels(msg.getParam(WEBSOCKET_P_CONTENT), channels);
    }

    private ArrayList<String> getChannelsInMsg(TLMsg msg) {
        Object channelList =msg.getSystemParam(USERMANAGER_P_USERCHANNEL);
        if( channelList ==null)
            return null ;
        if (channelList instanceof String )
        {
            ArrayList<String> channels = new ArrayList<>() ;
            channels.add((String)channelList);
            return channels ;
        }
        else  if(channelList instanceof List)
            return (ArrayList<String>) channelList;
         return null ;
    }

    private TLMsg putContentToChanels(Object content, ArrayList<String> channels) {

        TLMsg toServer = createMsg().setAction(SOCKETSERVER_PUTTOCLIENT).setParam(MSG_CONTENT, content);
        if (channels.size() == 1)
            toServer.setParam(NETTY_CHANNEL, channels.get(0));
        else {
            String[] strings = new String[channels.size()];
            channels.toArray(strings);
            toServer.setParam(NETTY_CHANNEL, strings);
        }
        return putMsg(serverModule, toServer);
    }

    private void getUserChannel(String userid, ArrayList<String> channels) {
        ArrayList channel = getUserChannelAsList(userid);
        if (channel != null) {
            channels.addAll(channel);
            putLog("获得用户通道准备发送:" + userid, LogLevel.DEBUG, "putToUser");
        }
    }

    protected String checkUser(TLMsg msg) {
        if (userType.equals(USERMANAGER_P_ANONYMOUS))
            return USERMANAGER_P_ANONYMOUS + msg.getParam(USERMANAGER_P_USERIP) + System.currentTimeMillis();
        if (userType.equals("username")) {
            if (users != null) {
                String loginname = (String) msg.getParam(USERMANAGER_P_USERNAME);
                String passwd = (String) msg.getParam(USERMANAGER_P_USERPASSWORD);
                if (loginname != null && passwd != null) {
                    if (users.containsKey(loginname) && users.get(loginname).equals(passwd))
                        return loginname;
                    else {
                        putLog("用户认证错误：" + loginname + "  " + msg.getParam(USERMANAGER_P_USERIP), LogLevel.DEBUG, "login");
                        return null;
                    }
                }
            } else
                return null;
        }
        if (userType.equals("token")) {
            String token = (String) msg.getParam("token");
            if (token == null)
                return null;
            HashMap<String, String> claims = TLJWT.parserToken(tokenSecret, token, tokenIssure);
            if (claims == null || claims.get("expire") != null || claims.get("decode") != null) {
                putLog("token认证错误" + msg.getParam("ip"), LogLevel.DEBUG, "login");
                return null;
            }
            return claims.get("userid");
        } else {
            String str[] = userType.split(":");   //模块认证，模块:方法
            if (str.length == 2) {
                TLMsg loginMsg = createMsg().setAction(str[1].trim()).setArgs(msg.getArgs());
                TLMsg returnMsg = putMsg(str[0].trim(), loginMsg);
                if (returnMsg == null)
                    return null;
                return (String) returnMsg.getParam("userid");
            } else
                return null;
        }
    }

    protected void userLogin(String userid, String userSource, String channel, String ip) {
        HashMap<String, String> channelMap = userChannel(userSource, channel, ip);
        CopyOnWriteArrayList<HashMap<String, String>> userChannels = new CopyOnWriteArrayList<>();
        userChannels.add(channelMap);
        usersInfo.put(userid, userChannels);
        channelToUser.put(channel, userid);
    }

    protected HashMap<String, String> userChannel(String userSource, String channel, String ip) {
        HashMap<String, String> channelMap = new HashMap<>();
        channelMap.put("channel", channel);
        channelMap.put("userSource", userSource);
        channelMap.put("ip", ip);
        channelMap.put("loginTime", String.valueOf(new Date()));
        return channelMap;
    }

    protected void addUserChannel(String userid, String userSource, String channel, String ip) {
        HashMap<String, String> channelMap = userChannel(userSource, channel, ip);
        CopyOnWriteArrayList<HashMap<String, String>> userChannels = usersInfo.get(userid);
        if (userChannels == null) {
            userChannels = new CopyOnWriteArrayList<>();
            userChannels.add(channelMap);
            usersInfo.put(userid, userChannels);
        } else
            userChannels.add(channelMap);
        channelToUser.put(channel, userid);
    }

    protected CopyOnWriteArrayList<HashMap<String, String>> delUserChannel(String userid, String channel) {
        CopyOnWriteArrayList<HashMap<String, String>> userChannels = usersInfo.get(userid);
        if (userChannels == null)
            return null;
        for (HashMap<String, String> channelMap : userChannels) {
            if (channelMap.get("channel").equals(channel)) {
                userChannels.remove(channelMap);
                break;
            }
        }
        return userChannels;
    }

    protected ArrayList<String> getUserChannelAsList(String userid) {
        if(userid ==null)
            return null ;
        CopyOnWriteArrayList<HashMap<String, String>> userChannels = usersInfo.get(userid);
        if (userChannels == null || userChannels.isEmpty())
            return null;
        ArrayList<String> channels = new ArrayList<>();
        for (HashMap<String, String> channel : userChannels)
            channels.add(channel.get("channel"));
        return channels;
    }
    private class   binarySendModule  extends TLBaseWebSocketSendFile
    {

        public binarySendModule(String name, TLObjectFactory modulefactory) {
            super(name, modulefactory);
        }

        @Override
        protected boolean binarySend(List userChannels, String binaryMsgid, byte cmdCode, int sessionId, byte[] contentStrBytes, int length, int order) {
           return TLUserManagerModule.this.binarySend((ArrayList<Channel>)userChannels, binaryMsgid,  cmdCode,  sessionId,  contentStrBytes,  length,  order);
        }
        @Override
        protected  List getUserChannels(TLMsg msg) {
            return TLUserManagerModule.this.getUserChannels( msg);
        }
    }
}
