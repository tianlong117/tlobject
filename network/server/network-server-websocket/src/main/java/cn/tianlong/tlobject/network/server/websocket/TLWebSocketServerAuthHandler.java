package cn.tianlong.tlobject.network.server.websocket;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLMsg;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;

import java.net.InetSocketAddress;

import static cn.tianlong.tlobject.base.TLParamString.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * 创建日期：2020/1/2120:49
 * 描述:
 * 作者:tianlong
 */
public class TLWebSocketServerAuthHandler extends ChannelInboundHandlerAdapter implements IObject {
    protected TLWebSocketServer socketServer;

    public TLWebSocketServerAuthHandler(TLWebSocketServer socketServer) {
        this.socketServer = socketServer;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof FullHttpRequest)) {
            ctx.close();
            return;
        }
        final FullHttpRequest req = (FullHttpRequest) msg;
        if (req.method() != HttpMethod.GET) {
            ctx.channel().writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.FORBIDDEN));
            ctx.close();
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
            ctx.fireChannelRead(msg);
        } else {
            FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.FORBIDDEN);
            ctx.channel().writeAndFlush(response);
            ctx.close();
        }
    }

    @Override
    public String getName() {
        return null;
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
