package cn.tianlong.tlobject.network.server.websocket;

import cn.tianlong.tlobject.base.TLMsg;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;

import java.net.InetSocketAddress;

import static cn.tianlong.tlobject.base.TLParamString.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * 创建日期：2020/1/2120:49
 * 描述:
 * 作者:tianlong
 */
public class TLWebSocketServerAuthHandler extends ChannelInboundHandlerAdapter {
    protected TLWebSocketServer socketServer;

    public TLWebSocketServerAuthHandler(TLWebSocketServer socketServer) {
        this.socketServer = socketServer;
    }

    /** 403 应答：显式补 Content-Length，并在写完后关连接（不要再手写 ctx.close，避免响应还没 flush 就断） */
    private static void writeForbidden(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.FORBIDDEN,
                Unpooled.EMPTY_BUFFER);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.channel().writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof FullHttpRequest)) {
            ReferenceCountUtil.release(msg);   // 这一层不消费就自己释放，不能指望下游
            ctx.close();
            return;
        }
        final FullHttpRequest req = (FullHttpRequest) msg;
        if (req.method() != HttpMethod.GET) {
            writeForbidden(ctx);
            req.release();      // 请求被丢弃，引用计数对象必须释放，否则 ByteBuf 泄漏
            return;
        }
        HttpHeaders httpHeaders = req.headers();
        String authType = httpHeaders.get("Access-authType");
        Channel clientChannel = ctx.channel();
        String channelName = clientChannel.id().asLongText();
        InetSocketAddress insocket = (InetSocketAddress) ctx.channel().remoteAddress();
        String  clientIP = insocket.getAddress().getHostAddress();
        String userSource = httpHeaders.get("Access-userSource");
        TLMsg loginMsg = new TLMsg();
        loginMsg.setParam(USERMANAGER_P_USERCHANNEL, channelName);
        loginMsg.setParam(USERMANAGER_P_USERIP, clientIP);
        loginMsg.setParam(USERMANAGER_P_USERSOURCE, userSource);
        if(authType !=null)
        {
            if (authType.equals("token")) {
                String token = httpHeaders.get("Access-User-Token");
                loginMsg.setParam("token", token);
            } else if (authType.equals(USERMANAGER_P_USERNAME)) {
                String username = httpHeaders.get("Access-username");
                String passwd = httpHeaders.get("Access-passwd");
                loginMsg.setParam(USERMANAGER_P_USERNAME, username);
                loginMsg.setParam(USERMANAGER_P_USERPASSWORD, passwd);
            }
        }
        boolean ifLogin = socketServer.login(loginMsg, clientChannel);
        if (ifLogin == true) {
            ctx.pipeline().remove("authHandler");
            ctx.fireChannelRead(msg);   // 所有权交给下游，由下游释放，此处不能 release
        } else {
            writeForbidden(ctx);
            req.release();              // 认证失败同样要释放
        }
    }
}
