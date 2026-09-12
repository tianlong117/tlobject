package cn.tianlong.tlobject.network.server.websocket;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.ResourceLeakDetector;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
/**
 * Created by carl.yu on 2016/12/16.
 */
 abstract public class TLBaseServer extends TLBaseModule {
    protected String serverName ;
    protected  int port=8080;
    protected AtomicInteger currentChannelNumbs = new AtomicInteger(0);
    protected  int maxClient =0; // 最大客户数量, 0 不限制,默认不限制
    protected int so_BackLog=1024;
    protected final AcceptorIdleStateTrigger idleStateTrigger = new AcceptorIdleStateTrigger();
    protected  EventLoopGroup bossGroup;
    protected  EventLoopGroup workerGroup;
    protected volatile boolean runFlag=false;
    protected  IObject clientMsgHandler;

    public TLBaseServer(){
        super();
    }
    public TLBaseServer(String name ){
        super(name);
    }
    public TLBaseServer(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected void initProperty(){
        super.initProperty();
        if(params!=null && params.get("port")!=null)
            port = Integer.parseInt(params.get("port"));
        if(params!=null && params.get("maxClient")!=null)
            maxClient = Integer.parseInt(params.get("maxClient"));
        if(params!=null && params.get("so_BackLog")!=null)
            so_BackLog = Integer.parseInt(params.get("so_BackLog"));
        if(params!=null && params.get("serverName")!=null)
            serverName = params.get("serverName");
    }
    @Override
    protected TLBaseModule init() {
        if( params!=null && params.get("clientMsgHandler")!=null)
            clientMsgHandler = (IObject) getModule(params.get("clientMsgHandler"));
        else {
            clientMsgHandler = (IObject) getModule(name+"_clientMsgHandler","clientMsgHandler");
            putMsg(clientMsgHandler,createMsg().setAction(MODULE_ADDMSGTABLE).setParam("msgTable",msgTable));
        }
        return this ;
    }

    protected boolean run( int port , IObject serviceHander) throws Exception {
        if(port==0)
            port=this.port;
        if(serviceHander !=null )
               clientMsgHandler =serviceHander;
         bossGroup = new NioEventLoopGroup();
         workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(getChannelInitializer(this) )
                    .option(ChannelOption.SO_BACKLOG, so_BackLog)
                    .childOption(ChannelOption.TCP_NODELAY,true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            ChannelFuture future = bootstrap.bind(new InetSocketAddress(port));
            future.sync();
            System.out.println("服务器: "+serverName+" 启动,地址: " + "localhost:" + port);
            runFlag=true;
            afterServerRun();
            ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.ADVANCED);
            future.channel().closeFuture().sync();
        } finally {
            // 两个 group 的构造在 try 之外：第二个构造失败时它仍为 null，直接解引用会在 finally 里 NPE
            if(bossGroup !=null)
                bossGroup.shutdownGracefully();
            if(workerGroup !=null)
                workerGroup.shutdownGracefully();
            runFlag=false;
        }
        return runFlag ;
    }
    protected abstract ChannelHandler getChannelInitializer(TLBaseServer server) ;
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "run":
                if(  runFlag==true)
                    return createMsg().setParam("result","runing");
                returnMsg=serverRun(fromWho,msg);
                break;
            case "stop":
                if(  runFlag==false)
                    return createMsg().setParam("result","stoped");
                returnMsg= stop(fromWho,msg);
                break;
            case "getStatus":
                returnMsg=createMsg().setParam("status",runFlag);
                break;
            case "getServerStatus":
                returnMsg=getServerStatus(fromWho,msg);
                break;
            case "setMaxClient":
                returnMsg=setMaxClient(fromWho,msg);
                break;
            default:
                returnMsg = null;
        }
        return returnMsg;
    }


    private TLMsg getServerStatus(Object fromWho, TLMsg msg) {
        return createMsg().setParam("currentChannelNumbs",currentChannelNumbs)
                .setParam("maxClient",maxClient)
                .setParam("runFlag",runFlag);
    }

    private TLMsg setMaxClient(Object fromWho, TLMsg msg) {
        // 原来判断用 "setMaxClient"、取值用 "maxClient"：只写 maxClient 时永远不更新，
        // 只写 setMaxClient 时取值为 null → toString() NPE。统一按 maxClient 取值。
        if(msg.getParam("maxClient")!=null)
            maxClient= Integer.valueOf( msg.getParam("maxClient").toString());
        return createMsg().setParam("result",maxClient);
    }

    protected TLMsg serverRun(Object fromWho, TLMsg msg) {
        IObject serviceHander = null;
        int port=0;
        if(msg.getParam("clientMsgHandler")!=null)
            serviceHander= (IObject) msg.getParam("clientMsgHandler");
        if(msg.getParam("port")!=null)
            port=(Integer) msg.getParam("port");
        try {
            run(port,serviceHander);
        } catch (Exception e) {
            putLog("服务器发生错误",LogLevel.ERROR,"serverRun");
            e.printStackTrace();
        }
        if(runFlag==false)
        {
            putLog("服务器启动失败",LogLevel.ERROR,"serverRun");
            return createMsg().setParam("result",runFlag);
        }
        return createMsg().setParam("result",runFlag);
    }

    protected  void afterServerRun(){};

    /**
     * 模块销毁时停服。
     * <p>
     * 原来这里只在 checkMsgAction 里写了 {@code case "destroy"}，但框架的 runAction 会先把
     * "destroy" 拦到 TLBaseModule.destroy，那条分支永远走不到 —— 结果是工厂关闭时
     * Netty 的 boss/worker 线程组不会被 shutdown，run() 里的 closeFuture().sync() 也不会返回。
     */
    @Override
    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        if (bossGroup != null || workerGroup != null)
            putLog("服务器销毁，释放 Netty 线程组: " + serverName, LogLevel.DEBUG, "destroy");
        stop(fromWho, msg);
        return super.destroy(fromWho, msg);
    }

    protected TLMsg stop(Object fromWho, TLMsg msg) {
        // 服务器从未启动时两个 group 为 null，直接解引用会 NPE；stop 本身也要可重复调用
        if(bossGroup !=null)
            bossGroup.shutdownGracefully();
        if(workerGroup !=null)
            workerGroup.shutdownGracefully();
        runFlag=false;
        putLog(" 服务器停止，网址是 : " + "http://localhost:" + port,LogLevel.WARN);
        return createMsg().setParam("result","stoped");
    }
}