package cn.tianlong.tlobject.network.server.websocket;

import cn.tianlong.tlobject.base.IObject;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.UUID;

/**
 * ClassName:MyWebSocketServerHandler Function: TODO ADD FUNCTION.
 *
 * @author hxy
 */
public abstract class TLBaseServerInboundHandler<T> extends SimpleChannelInboundHandler<T> implements IObject{
    protected String name;
    protected TLBaseServer server;
    public TLBaseServerInboundHandler( TLBaseServer server) {
        super();
        this.server = server;
        this.name =UUID.randomUUID().toString();
    }
    /**
     * channel 通道 action 活跃的 当客户端主动链接服务端的链接后，这个通道就是活跃的了。也就是客户端与服务端建立了通信通道并且可以传输数据
     */

}
