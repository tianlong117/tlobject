package cn.tianlong.tlobject.servletutils.clientinterface;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.servletutils.TLWServModule;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * SSE 输出接口：把当前 HTTP 响应包装成 SSE 长连接通道（TLWebChannel），
 * 注册给业务模块后由后台线程（如 agent 回调）渐进写帧。
 *
 * <p>双模式（msg 参数区分）：</p>
 * <ul>
 *   <li><b>SSE 注册模式</b>（msg 带 {@code sseRegister=true}）：创建通道 →
 *       设 event-stream 响应头 → 启动心跳线程 → 发 {@code registerSseChannel}
 *       消息（param: sseKey/channel）给 {@code sseOwner} 模块注册 → <b>不写不关</b>，
 *       请求线程释放、连接保持，由注册方后续写帧。</li>
 *   <li><b>普通 JSON 模式</b>（无 sseRegister）：原样走 {@link TLWJsonDataOutInterface}
 *       一次性输出（含 httpStatus 支持）。</li>
 * </ul>
 *
 * <p>通用契约：任何模块实现 "registerSseChannel" action 接收通道，即可获得
 * SSE 推送能力，无需关心 Servlet API。</p>
 *
 * @author tianlong
 */
public class TLWSSEOutInterface extends TLWJsonDataOutInterface {

    private static final String P_SSE_REGISTER = "sseRegister";
    private static final String P_SSE_OWNER = "sseOwner";
    private static final String P_SSE_KEY = "sseKey";
    /** 心跳间隔（毫秒） */
    private static final long HEARTBEAT_INTERVAL = 20000;

    public TLWSSEOutInterface(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected TLMsg putDataToUser(Object fromWho, TLMsg msg) {
        if (msg.isNull(P_SSE_REGISTER) || !msg.parseBoolean(P_SSE_REGISTER, false))
            return super.putDataToUser(fromWho, msg);

        String sseOwner = (String) msg.getParam(P_SSE_OWNER);
        String sseKey = (String) msg.getParam(P_SSE_KEY);
        if (sseOwner == null || sseOwner.isEmpty() || sseKey == null || sseKey.isEmpty())
            return createMsg().setParam(RESULT, false);
        HttpServletResponse response = getResponse();
        if (response == null)
            return createMsg().setParam(RESULT, false);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");

        SseChannel channel = new SseChannel(response);
        // 注册给业务模块（同步 putMsg：注册完成后调用方才提交后台任务，保证无丢帧）
        TLMsg registerMsg = createMsg().setAction("registerSseChannel")
                .setParam(P_SSE_KEY, sseKey)
                .setParam("channel", channel);
        // 透传调用方附加参数（如 sseType=stream/event），供注册方分发
        Object sseType = msg.getParam("sseType");
        if (sseType != null) registerMsg.setParam("sseType", sseType);
        return putMsg(sseOwner, registerMsg);
    }

    /**
     * SSE 通道实现：写 "data: {json}\n\n" 帧 + 20s 心跳，close 时中断心跳线程并关 writer。
     */
    private static class SseChannel implements TLWebChannel {
        private final PrintWriter writer;
        private final Thread heartbeat;
        private final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        private volatile boolean open = true;

        SseChannel(HttpServletResponse response) {
            PrintWriter w;
            try {
                w = response.getWriter();
            } catch (IOException e) {
                e.printStackTrace();
                w = null;
            }
            this.writer = w;
            this.heartbeat = new Thread(this::heartbeatLoop, "sse-hb");
            this.heartbeat.setDaemon(true);
            this.heartbeat.start();
        }

        private void heartbeatLoop() {
            while (open) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL);
                } catch (InterruptedException e) {
                    break;
                }
                if (open) writeHeartbeat();
            }
        }

        @Override
        public synchronized void write(String data) {
            if (!open || writer == null) return;
            writer.print("data: " + data + "\n\n");
            writer.flush();
            if (writer.checkError()) close();
        }

        private synchronized void writeHeartbeat() {
            if (!open || writer == null) return;
            writer.print("data: {\"hb\":true}\n\n");
            writer.flush();
            if (writer.checkError()) close();
        }

        @Override
        public synchronized void close() {
            if (!open) return;
            open = false;
            heartbeat.interrupt();
            latch.countDown();
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void awaitClosed() {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
