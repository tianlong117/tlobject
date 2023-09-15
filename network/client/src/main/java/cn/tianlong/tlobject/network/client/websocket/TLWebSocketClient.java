package cn.tianlong.tlobject.network.client.websocket;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.network.client.http.TLHttpClient;
import cn.tianlong.tlobject.network.common.TLBaseWebSocketSendFile;
import cn.tianlong.tlobject.network.common.TLNetSession;
import cn.tianlong.tlobject.utils.TLMsgUtils;
import com.zhy.http.okhttp.OkHttpUtils;
import com.zhy.http.okhttp.https.HttpsUtils;

import okhttp3.*;
import okio.ByteString;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static java.lang.Thread.sleep;


/**
 * 创建日期：2018/3/21 on 8:48
 * 描述:
 * 作者:tianlong
 */

public class TLWebSocketClient extends TLHttpClient {
    protected int reConnectTime = 2000;
    protected int pingInterval = 6;
    protected MyWebSocketListener webSocketListener;
    protected WebSocket mWebSocket;
    protected IObject socketClient;
    protected Boolean connected = false;
    private Request request;
    private OkHttpClient client;
    protected TLNetSession netSession;
    protected binarySendModule binarySendModule;


    public TLWebSocketClient(String name) {
        super(name);
    }

    public TLWebSocketClient(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
        socketClient = this;
    }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null) {
            if (params.get("reConnectTime") != null)
                reConnectTime = Integer.parseInt(params.get("reConnectTime")) * 1000;
            if (params.get("pingInterval") != null)
                pingInterval = Integer.parseInt(params.get("pingInterval"));
        }
    }

    @Override
    protected TLBaseModule init() {
        super.init();
        netSession = new TLNetSession(name + "_session", moduleFactory);
        netSession.start(null, params);
        binarySendModule=new binarySendModule("binarySendModule",moduleFactory);
        binarySendModule.start(null,params);
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case WEBSOCKET_CONNECT:
                returnMsg = connect(fromWho, msg);
                break;
            case WEBSOCKET_SEND:
                returnMsg = send(fromWho, msg);
                break;
            case WEBSOCKET_SETRESULTFOR:
                returnMsg = setResultFor(fromWho, msg);
                break;
            case WEBSOCKET_CANCEL:
                cancel(fromWho, msg);
                break;
            case WEBSOCKET_CLOSE:
                close(fromWho, msg);
                break;
            case WEBSOCKET_SENDFILE:
                returnMsg = sendFile(fromWho, msg);
                break;
            case USERMANAGER_SENDFILEERROR:
               sendFileError(fromWho, msg);
                break;
            case WEBSOCKET_SENDBINARY:
                returnMsg = sendBinary(fromWho, msg);
                break;
            default:

        }
        return returnMsg;
    }
    protected TLMsg sendBinary(Object fromWho, TLMsg msg) {
        return binarySendModule.sendBinary(msg);
    }
    private TLMsg sendFile(Object fromWho, TLMsg msg) {
        return binarySendModule.sendFile(msg);
    }
    private void   sendFileError(Object fromWho, TLMsg msg) {
        if(msg.isNull(WEBSOCKET_P_BINARYSESSION) && !(msg.getParam(WEBSOCKET_P_BINARYSESSION) instanceof  Integer ))
            return ;
        int sessionId = (int) msg.getParam(WEBSOCKET_P_BINARYSESSION);
        binarySendModule.setSendError(sessionId);
    }
    protected TLMsg connect(Object fromWho, TLMsg msg) {
        if (msg.parseBoolean(HTTP_P_ISHTTPS, false) == true) {
            String cerFile = (String) msg.getParam(SSL_SCERFILE);
            HttpsUtils.SSLParams sslParams;
            if (cerFile != null) {
                InputStream httpsCerFileStream = null;
                httpsCerFileStream = TLWebSocketClient.class.getResourceAsStream(cerFile);
                InputStream[] certificates = new InputStream[1];
                certificates[0] = httpsCerFileStream;
                sslParams = HttpsUtils.getSslSocketFactory(certificates, null, null);
            } else if (moduleSslParams != null)
                sslParams = moduleSslParams;
            else
                return null;
            client = new OkHttpClient.Builder()
                    //        .addInterceptor(new HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
                    .pingInterval(pingInterval, TimeUnit.SECONDS)
                    .hostnameVerifier(new HostnameVerifier() {
                        @Override
                        public boolean verify(String hostname, SSLSession session) {
                            //强行返回true 即验证成功
                            return true;
                        }
                    })
                    .sslSocketFactory(sslParams.sSLSocketFactory, sslParams.trustManager)
                    //其他配置
                    .build();
            OkHttpUtils.initClient(client);
        } else
            client = new OkHttpClient.Builder()
                    //     .addInterceptor(new HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
                    .pingInterval(pingInterval, TimeUnit.SECONDS)
                    .build();
        String url = (String) msg.getParam(URL);
        //  Request request = new Request.Builder().url(url).header("Access-User-Token","111111111111111").build();
        Request.Builder builder = new Request.Builder().url(url);
        if (!msg.isNull("token")) {
            builder.header("Access-authType", "token");
            builder.header("Access-User-Token", (String) msg.getParam("token"));
        } else if (!msg.isNull("username")) {
            builder.header("Access-authType", "username");
            builder.header("Access-username", (String) msg.getParam("username"));
            builder.header("Access-passwd", (String) msg.getParam("passwd"));
        }
        if (!msg.isNull("headerParm")) {
            Object headerParam = msg.getParam("headerParm");
            if (headerParam instanceof String)
                builder.header("headerParm", (String) msg.getParam("headerParm"));
            else if (headerParam instanceof Map) {
                Map<String, String> map = (Map) headerParam;
                for (String key : map.keySet()) {
                    builder.header(key, map.get(key));
                }
            }
        }
        request = builder.build();
        //建立连接
        String resultAction = (String) msg.getSystemParam(RESULTACTION);
        Object resultFor = msg.getSystemParam(RESULTFOR);
        if (resultFor == null) {
            resultFor = fromWho;
            msg.setSystemParam(RESULTFOR, resultFor);
        } else if (resultFor instanceof String)
            resultFor = getModule((String) resultFor);
        boolean reConnect = msg.parseBoolean("reConnect",true);
        webSocketListener = new MyWebSocketListener((IObject) resultFor, resultAction, reConnect);
        putLog("webSocket contecting..", LogLevel.DEBUG, "connect");
        mWebSocket = client.newWebSocket(request, webSocketListener);
        return createMsg().setParam("webSocket", mWebSocket);
    }

    protected TLMsg send(Object fromWho, TLMsg msg) {
        if (!msg.systemParamIsNull(RESULTFOR))
            setResultFor(fromWho, msg);
        String content = (String) msg.getParam(WEBSOCKET_P_CONTENT);
        boolean result = webSocketSend(content);
        if(result ==true)
            putLog("send sucess:" + content, LogLevel.DEBUG, "send");
        else
            putLog("send failure:" + content, LogLevel.DEBUG, "send");
        return createMsg().setParam("result", result);
    }

    protected boolean binarySend(String msgid, byte cmdOrder, int sessionId, byte[] buf, int size, int order) {
        byte[] msgbuf = TLMsgUtils.enCodeMsgBuf(msgid, cmdOrder, sessionId, buf,size,order);
        ByteString byteString = ByteString.of(msgbuf, 0, msgbuf.length);
        boolean result = webSocketSend(byteString);
        if(result)
            putLog("send binary sucess ,order:" + order, LogLevel.DEBUG, "bsend");
        else
            putLog("send binary failure,order:" + order, LogLevel.DEBUG, "bsend");
        return result ;
    }

    private void close(Object fromWho, TLMsg msg) {
        mWebSocket.close((int) msg.getParam("int"), (String) msg.getParam("string"));
    }

    private void cancel(Object fromWho, TLMsg msg) {
        mWebSocket.cancel();
    }

    private TLMsg setResultFor(Object fromWho, TLMsg msg) {
        String resultAction = (String) msg.getSystemParam(RESULTACTION);
        if (resultAction == null)
            return null;
        webSocketListener.setResultAction(resultAction);
        Object resultFor = msg.getSystemParam(RESULTFOR);
        if (resultFor instanceof String)
            resultFor = getModule((String) resultFor);
        webSocketListener.setResultFor((IObject) resultFor);
        return null;
    }

    public synchronized boolean webSocketSend(String content) {
        if (connected == false)
            return false;
        int contentsize = content.length()*3*8 ;
        boolean IfQueueCanWrite= IfQueueCanWrite(contentsize);
        if( IfQueueCanWrite ==true)
            return mWebSocket.send(content);
        return false ;
    }

     public synchronized boolean webSocketSend(ByteString content) {
        if (connected == false)
            return false;
         int contentsize =content.size();
         boolean IfQueueCanWrite= IfQueueCanWrite(contentsize);
         if( IfQueueCanWrite ==true)
             return mWebSocket.send(content);
         return false ;
    }

    private boolean IfQueueCanWrite(int contentsize) {
        long queueSize ;
        do{
            queueSize =mWebSocket.queueSize()+contentsize;
            if(queueSize < 16777000L)
                return true ;
        } while (true);
    }
    private boolean IfQueueCanWrite_old(int contentsize) {
        int timeOutTimes =3000;
        long queueSize ;
        int i=0;
        do{
            queueSize =mWebSocket.queueSize()+contentsize;
            if(queueSize < 16777000L)
                return true ;
            try {
                sleep(1);
                i++;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if(i >= timeOutTimes)
                return false ;
        } while (true);
    }
    protected class MyWebSocketListener extends WebSocketListener {
        private IObject resultFor;
        private String resultAction;
        protected Boolean reConnect;

        public MyWebSocketListener(IObject resultFor, String resultAction, boolean reConnect) {
            this.resultFor = resultFor;
            this.resultAction = resultAction;
            this.reConnect = reConnect;
        }

        public void setResultAction(String resultAction) {
            this.resultAction = resultAction;
        }

        public void setResultFor(IObject resultFor) {
            this.resultFor = resultFor;
        }

        public void setByReturnMsg(TLMsg returnMsg) {
            if (returnMsg != null) {
                if (!returnMsg.isNull("reConnect"))
                    reConnect = (Boolean) returnMsg.getParam("reConnect");
                if (!returnMsg.systemParamIsNull(RESULTFOR))
                    resultFor = (IObject) returnMsg.getSystemParam(RESULTFOR);
                if (!returnMsg.systemParamIsNull(RESULTACTION))
                    resultAction = (String) returnMsg.getSystemParam(RESULTACTION);
            }
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            putLog("webSocket open", LogLevel.DEBUG, "onOpen");
            connected = true;
            mWebSocket = webSocket;
            TLMsg responseMsg = createMsg().setAction(resultAction).setParam(WEBSOCKET_P_STATUS, WEBSOCKET_R_OPEN)
                    .setParam(WEBRESPONSE, response).setParam("socketClient", socketClient)
                    .setParam("webSocket", mWebSocket);
            TLMsg returnMsg = putMsg(resultFor, responseMsg);
            setByReturnMsg(returnMsg);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            putLog("webSocket receive" + text, LogLevel.DEBUG, "onMessage");
            TLMsg responseMsg = createMsg().setAction(resultAction)
                    .setParam(WEBSOCKET_P_STATUS, WEBSOCKET_R_MESSAGE)
                    .setParam(WEBRESPONSE, text);
            TLMsg returnMsg = putMsg(resultFor, responseMsg);
            setByReturnMsg(returnMsg);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString byteString) {
            putLog("webSocket receive ByteString", LogLevel.DEBUG, "onMessage");
            int readableBytes = byteString.size();
            if (readableBytes == 0)
                return;
            byte[] bytes = byteString.toByteArray();
            TLMsg clientMsg = TLMsgUtils.deCodeMsgBufToMsg(bytes);
            TLMsg responseMsg = createMsg().setAction(resultAction)
                    .setParam(USERMANAGER_P_CLIENTMSG,clientMsg)
                    .setParam(WEBSOCKET_P_STATUS, WEBSOCKET_R_MESSAGE);
            TLMsg returnMsg = putMsg(resultFor, responseMsg);
           setByReturnMsg(returnMsg);
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            super.onClosing(webSocket, code, reason);
            putLog("webSocket close", LogLevel.DEBUG, "onCloseing");
            connected = false;
            TLMsg responseMsg = createMsg().setAction(resultAction).setParam(WEBSOCKET_P_STATUS, WEBSOCKET_R_CLOSEING)
                    .setParam("reason", reason).setParam("code", code)
                    .setParam("socketClient", socketClient);
            TLMsg returnMsg = putMsg(resultFor, responseMsg);
            setByReturnMsg(returnMsg);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            super.onClosed(webSocket, code, reason);
            putLog("webSocket close", LogLevel.DEBUG, "onClosed");
            connected = false;
            TLMsg responseMsg = createMsg().setAction(resultAction).setParam(WEBSOCKET_P_STATUS, WEBSOCKET_R_CLOSED)
                    .setParam("reason", reason).setParam("code", code)
                    .setParam("socketClient", socketClient);
            TLMsg returnMsg = putMsg(resultFor, responseMsg);
            setByReturnMsg(returnMsg);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            super.onFailure(webSocket, t, response);
            putLog("webSocket failure", LogLevel.DEBUG, "onFailure");
            mWebSocket.close(1011, "failure");
            connected = false;
            if (response != null) {
                String message = response.message();
                if (message.equals(WEBSOCKET_V_AUTHFORBIDDEN))
                    reConnect = false;
            }
            TLMsg responseMsg = createMsg().setAction(resultAction).setParam(WEBSOCKET_P_STATUS, WEBSOCKET_R_FAILURE)
                    .setParam(WEBRESPONSE, response).setParam("throwable", t).setParam("socketClient", socketClient);
            TLMsg returnMsg = putMsg(resultFor, responseMsg);
            setByReturnMsg(returnMsg);
            if (reConnect) {
                try {
                    sleep(reConnectTime);
                    putLog("webSocket reconnect ", LogLevel.DEBUG, "onFailure");
                    mWebSocket = client.newWebSocket(request, this);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    mWebSocket.close(1011, "failure");
                }
            }
        }
    }
    private class   binarySendModule  extends TLBaseWebSocketSendFile
    {

        public binarySendModule(String name, TLObjectFactory modulefactory) {
            super(name, modulefactory);
        }

        @Override
        protected boolean binarySend(List userChannels, String binaryMsgid, byte cmdCode, int sessionId, byte[] contentStrBytes, int length, int order) {
            return TLWebSocketClient.this.binarySend(binaryMsgid,  cmdCode,  sessionId,  contentStrBytes,  length,  order);
        }
        @Override
        protected List<Object> getUserChannels(TLMsg msg) {
            return new ArrayList<>();
        }
    }
}
