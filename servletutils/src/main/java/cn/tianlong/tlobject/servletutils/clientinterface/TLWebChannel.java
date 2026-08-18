package cn.tianlong.tlobject.servletutils.clientinterface;

/**
 * Web 通道接口：SSE 长连接写入通道（由 TLWSSEOutInterface 创建，注册给业务模块，
 * 业务模块在后台线程（如 agent 回调）向通道写帧）。
 *
 * <p>通用契约：任何模块实现 "registerSseChannel" action（param: sseKey/channel）
 * 接收 TLWSSEOutInterface 创建的通道，即可获得 SSE 推送能力。</p>
 *
 * @author tianlong
 */
public interface TLWebChannel {
    void write(String data);
    boolean isOpen();
    void close();

    /** 阻塞等待通道关闭（流结束/异常/连接断开）。请求线程挂起保持 HTTP 连接，直到关闭 */
    void awaitClosed();
}
