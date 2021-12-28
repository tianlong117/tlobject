package cn.tianlong.tlobject.network.server.websocket;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import cn.tianlong.tlobject.utils.TLMsgUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import java.util.HashMap;

import static cn.tianlong.tlobject.base.TLParamString.*;

/**
 * ClassName:MyWebSocketServerHandler Function: TODO ADD FUNCTION.
 *
 * @author hxy
 */
public class WebSocketServerHandler extends TLBaseServerInboundHandler<Object> {

     protected Gson gson = new GsonBuilder().serializeNulls().create();

    public WebSocketServerHandler(TLBaseServer server) {
        super(server);
    }

    /**
     * channel 通道 Inactive 不活跃的 当客户端主动断开服务端的链接后，这个通道就是不活跃的。也就是说客户端与服务端关闭了通信通道并且不可以传输数据
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
// 移除
        String channelName =ctx.channel().id().asLongText();
        ((TLWebSocketServer) server).removeChannel(channelName);
        server.putLog("客户端与服务端连接关闭：" + ctx.channel().remoteAddress().toString(), LogLevel.DEBUG, "channelInactive");
    }
    /**
     * 接收客户端发送的消息 channel 通道 Read 读 简而言之就是从通道中读取数据，也就是服务端接收客户端发来的数据。但是这个数据在不进行解码时它是ByteBuf类型的
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof WebSocketFrame) {
            handlerWebSocketFrame(ctx, (WebSocketFrame) msg);
        }
    }
    /**
     * channel 通道 Read 读取 Complete 完成 在通道读取完成后会在这个方法里通知，对应可以做刷新操作 ctx.flush()
     */
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    private void handlerWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (!(frame instanceof TextWebSocketFrame)) {
            if (frame instanceof BinaryWebSocketFrame)
                 handlerBinaryWebSocketFrame( ctx, (BinaryWebSocketFrame)frame);
            return;
        }
        String content = ((TextWebSocketFrame) frame).text();
        String ip =ctx.channel().remoteAddress().toString() ;
        server.putLog("服务器收到: " +ip +" " + content, LogLevel.DEBUG, "channelRead");
        TLMsg clientMsg =  TLMsgUtils.websocketJsonStrToMsg(content);
        if(clientMsg ==null)
        {
            server.putLog("client发送非json字符，IP：" + ip , LogLevel.WARN);
            return ;
        }
        String channelName =ctx.channel().id().asLongText();
        doClientMsg(clientMsg ,ip ,channelName,ctx);
    }

    private void handlerBinaryWebSocketFrame(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) {
        ByteBuf byteBuf = frame.content();
        String ip =ctx.channel().remoteAddress().toString() ;
        server.putLog("服务器收到二进制,ip: " + ip , LogLevel.DEBUG, "channelRead");
        int readableBytes = byteBuf.readableBytes();
        if ( readableBytes ==0)
            return  ;
        byte[] bytes = new byte[readableBytes];
        byteBuf.readBytes(bytes);
        TLMsg clientMsg = TLMsgUtils.deCodeMsgBufToMsg(bytes);
        if(clientMsg ==null)
        {
            server.putLog("client发送非json字符，IP：" + ip , LogLevel.WARN);
            return ;
        }
        String channelName =ctx.channel().id().asLongText();
        doClientMsg(clientMsg ,ip ,channelName,ctx);
    }
    private void doClientMsg(TLMsg clientMsg ,String ip ,String channelName,ChannelHandlerContext ctx){
        HashMap<String,Object> channelData =new HashMap<>();
        channelData.put(USERMANAGER_P_USERCHANNEL, channelName);
        channelData.put(USERMANAGER_P_USERIP,ip);
        clientMsg.setParam(USERMANAGER_P_CHANNELDATA ,channelData);
        TLMsg handleMsg = new TLMsg().setAction("fromClient")
                .setParam(USERMANAGER_P_USERCHANNEL, channelName)
                .setParam(USERMANAGER_P_CLIENTMSG,clientMsg);
        ((TLWebSocketServer) server).handleClientMsg(handleMsg, ctx.channel()) ;
    }
    /**
     * exception 异常 Caught 抓住 抓住异常，当发生异常的时候，可以做一些相应的处理，比如打印日志、关闭链接
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }
    @Override
    public String getName() {
        return name;
    }
    @Override
    public TLMsg putMsg(IObject toWho, TLMsg msg) {
        return null;
    }
    @Override
    public TLMsg getMsg(Object fromWho, TLMsg msg) {
        return null;
    }
}
