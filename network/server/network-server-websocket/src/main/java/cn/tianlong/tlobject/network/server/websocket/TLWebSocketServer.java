package cn.tianlong.tlobject.network.server.websocket;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;


public class TLWebSocketServer extends TLBaseServer {
    protected Map<String, Channel> channels = new ConcurrentHashMap<String, Channel>();
    protected Gson gson = new GsonBuilder().serializeNulls().create();
    protected SSLContext sslContext = null;
    protected String sslCerFile;
    private String sslCerFilePwd;
    private long clientIdleTime =10L;    // 客户idle 间隔

    public TLWebSocketServer(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
        so_BackLog = 1024;
    }
    @Override
    protected void initProperty() {
        super.initProperty();
        if(params!=null  ){
            if( params.get(SSL_SCERFILE)!=null)
                sslCerFile =params.get(SSL_SCERFILE);
            if( params.get(SSL_SCERFILE_PWD)!=null)
                sslCerFilePwd =params.get(SSL_SCERFILE_PWD);
            if( params.get("clientIdleTime")!=null)
                clientIdleTime =Long.parseLong(params.get("clientIdleTime"));
        }
    }
    @Override
    protected TLBaseModule init(){
        super.init();
        if(sslCerFile!=null && !sslCerFile.isEmpty())
        {
            try {
                String path = TLWebSocketServer.class.getResource(sslCerFile).getFile();
                sslContext = SslUtil.createSSLContext("JKS", path, sslCerFilePwd);
            } catch (Exception e) {
                putLog("ssl初始化错误，检查文件及口令是否正确文件:"+sslCerFile,LogLevel.ERROR,"init");
                putLog(e,LogLevel.ERROR,"init");
                System.exit(0);
            }
        }
        return this ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case SOCKETSERVER_PUTTOCLIENT :
                returnMsg = putToClient(fromWho, msg);
                break;
            case SOCKETSERVER_GETCHANNEL:
                returnMsg = getChannel(fromWho, msg);
                break;
            case "closeChannel":
                returnMsg = closeChannel(fromWho, msg);
                break;
            default:
                returnMsg = super.checkMsgAction(fromWho, msg);
        }
        return returnMsg;
    }

    private TLMsg getChannel(Object fromWho, TLMsg msg) {
       Object  channelName =  msg.getParam(NETTY_CHANNEL);
        ArrayList<Channel> userChannels =new ArrayList<>();
        if (channelName == null)
        {
            for (String cName : channels.keySet())
            {
                Channel channel = channels.get(cName);
                if(channel ==null)
                    continue;
                userChannels.add(channel);
            }  
        }
        else if (channelName instanceof String)
        {
            Channel channel = channels.get(channelName);
            userChannels.add(channel);
        }
        else if (channelName instanceof String[])
        {
            for (int i = 0; i < ((String[])channelName).length; i++) {
                Channel channel = channels.get(((String[])channelName)[i]);
                if(channel ==null)
                    continue;
                userChannels.add(channel);
            }
        }
        return createMsg().setParam(NETTY_CHANNELOBJ,userChannels);
    }

    public boolean login(TLMsg loginMsg,Channel clientChannel){
        if(maxClient > 0 && currentChannelNumbs.intValue() > maxClient)
            return false ;
        TLMsg authMsg = createMsg().setMsgId(USERMANAGER_LOGIN).setArgs(loginMsg.getArgs()).setParam(USERMANAGER_P_SERVERNAME,serverName);
        TLMsg returnMsg = clientMsgHandler.getMsg(this, authMsg);
         if (returnMsg != null) {
            String channelName = (String) loginMsg.getParam(USERMANAGER_P_USERCHANNEL);
            channels.put(channelName, clientChannel);
             currentChannelNumbs.incrementAndGet();
            return true;
        } else
            return false;
    }
    public void handleClientMsg(TLMsg clientMsg,  Channel clientChannel) {
        TLMsg returnMsg = clientMsgHandler.getMsg(this, clientMsg);
        if (returnMsg != null  && returnMsg.parseBoolean(SOCKETSERVER_R_IFRETURN,false))
        {
            returnMsg.removeParam(SOCKETSERVER_R_IFRETURN);
            HashMap<String, Object> serverData = returnMsg.getArgs();
            if(serverData !=null && !serverData.isEmpty())
            {
                String jsonString = gson.toJson(serverData);
                TextWebSocketFrame tws = new TextWebSocketFrame(jsonString);
                clientChannel.writeAndFlush(tws);
                putLog(serverName+" 服务器发送:" + clientChannel.remoteAddress().toString() +" " +jsonString, LogLevel.DEBUG, "handleClientMsg");
            }
        }
    }

    protected TLMsg closeChannel(Object fromWho, TLMsg msg) {
        Object channels =msg.getParam(NETTY_CHANNEL);
       if(channels  instanceof String)
           closeChannel((String)channels );
       else if(channels  instanceof List){
           for(String channel : (List<String>)channels)
               closeChannel(channel );
       }
        return null;
    }
    protected void closeChannel(String channelName) {
        Channel channel = channels.get(channelName);
        if (channel != null) {
            channels.remove(channelName);
            channel.close();
            currentChannelNumbs.decrementAndGet() ;
        }
    }
    protected TLMsg putToClient(Object fromWho, TLMsg msg) {
        Object chanaelName = msg.getParam(NETTY_CHANNEL);
        int result = 0;
        Object content = msg.getParam(MSG_CONTENT);
        Object tws ;
        if (content instanceof String)
            tws = new TextWebSocketFrame((String) content);
        else if (content instanceof  ByteBuf)
            tws = new BinaryWebSocketFrame((ByteBuf) content);
        else
            tws = new TextWebSocketFrame(gson.toJson(content));
        if (chanaelName == null)
        {
            result = putALlToClient(tws);
            putLog(serverName+" 服务器广播发送:"+result+"内容:"+ content.toString(), LogLevel.DEBUG, "handleClientMsg");
        }
        else if (chanaelName instanceof String)
        {
            result = putSingleToClient(tws, (String) chanaelName);
            putLog(serverName+" 服务器发送:" +result+"通道:"+ chanaelName +" " +content.toString(), LogLevel.DEBUG, "handleClientMsg");
        }
        else if (chanaelName instanceof String[])
        {
            result = putGroupToClient(tws, (String[]) chanaelName);
            putLog(serverName+" 服务器发送:" +result+"通道:"+ chanaelName.toString() +" " +content.toString(), LogLevel.DEBUG, "handleClientMsg");
        }
        return createMsg().setParam(RESULT, result);
    }

    protected void removeChannel(String chanaelName) {
        if(channels.containsKey(chanaelName))
        {
            channels.remove(chanaelName);
            currentChannelNumbs.decrementAndGet() ;
            TLMsg clientMsg = createMsg().setMsgId("logout").setParam(USERMANAGER_P_USERCHANNEL, chanaelName).setParam(USERMANAGER_P_SERVERNAME,serverName);;
            clientMsgHandler.getMsg(this, clientMsg);
        }
    }
    protected int putALlToClient(Object tws) {
        ChannelGroup group = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        for (String cName : channels.keySet()) {
            Channel channel = channels.get(cName);
            group.add(channel);
        }
        group.writeAndFlush(tws);
        return channels.size();
    }

    protected int putSingleToClient(Object tws, String channelName) {
        Channel channel = channels.get(channelName);
        if(channel ==null)
            return 0;
        Boolean isWrite ;
        do{
            if(!channel.isActive())
            {
                removeChannel(channelName)  ;
                return 0;
            }
            isWrite =channel.isWritable();
            if(isWrite ==true)
                channel.writeAndFlush(tws);
        }while (isWrite==false);
        return 1;
    }

    protected int putGroupToClient(Object tws, String[] channelName) {
        ChannelGroup group = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        int numb=0;
        for (int i = 0; i < channelName.length; i++) {
            Channel channel = channels.get(channelName[i]);
            if(channel ==null)
                continue;
            else
            {
                group.add(channel);
                numb ++ ;
            }
        }
        if(numb > 0)
           group.writeAndFlush(tws);
        return numb;
    }

    @Override
    protected ChannelHandler getChannelInitializer(TLBaseServer server) {
        return new myChannelInitializer(server);
    }

    protected class myChannelInitializer extends ChannelInitializer {
        protected TLBaseServer server;

        public myChannelInitializer(TLBaseServer server) {
            this.server = server;
        }

        @Override
        protected void initChannel(Channel ch) throws Exception {

           //SSLEngine 此类允许使用ssl安全套接层协议进行安全通信
            SSLEngine engine =null ;
            if(sslContext !=null)
            {
                engine = sslContext.createSSLEngine();
                engine.setUseClientMode(false);
            }

            ChannelPipeline pipeline = ch.pipeline();
            pipeline.addLast("IdleState", new IdleStateHandler(clientIdleTime, 0, 0, TimeUnit.SECONDS));
            pipeline.addLast("IdleState-trigger", idleStateTrigger);
            //开启ssl
            if(engine !=null)
                 pipeline.addLast(new SslHandler(engine));
            //websocket协议本身是基于http协议的，所以这边也要使用http解编码器
            pipeline.addLast(new HttpServerCodec());
            //以块的方式来写的处理器
            pipeline.addLast(new ChunkedWriteHandler());
            pipeline.addLast(new HttpObjectAggregator(65535));
            pipeline.addLast("authHandler",new TLWebSocketServerAuthHandler((TLWebSocketServer)server));
            pipeline.addLast(new WebSocketServerProtocolHandler("/ws",null,true));
            pipeline.addLast(new WebSocketServerHandler(server));
        }
    }
}
