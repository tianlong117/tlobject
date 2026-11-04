package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 一次流式调用的等待句柄：回调线程 complete/fail，编排线程 await。
 * 与原 TLAiAgent 内部类语义一致；提为包级类型供 ChatTurn 使用。
 */
final class StreamHandle {

    private static final long TIMEOUT_SECONDS = 600L;

    final String sessionId;
    final String roundId;
    final StreamSink sink;

    private final TLAiAgent owner;
    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile TLMsg doneMsg;
    private volatile String error;
    private volatile boolean completed;

    StreamHandle(TLAiAgent owner, String sessionId, String roundId, StreamSink sink) {
        this.owner = owner;
        this.sessionId = sessionId;
        this.roundId = roundId;
        this.sink = sink;
    }

    boolean isCompleted() { return completed; }

    void complete(TLMsg msg) {
        if (completed) return;
        // done 消息本身不一定带 RESULT；这里显式置 true，保证调用方能识别为成功
        msg.setParam(TLAiAgentParamString.RESULT, true);
        doneMsg = msg;
        completed = true;
        latch.countDown();
    }

    void fail(String message) {
        if (completed) return;
        error = message;
        completed = true;
        latch.countDown();
    }

    TLMsg await() throws InterruptedException {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                // 超时后 worker 已按错误收尾；置 completed 挡住迟到的 complete/fail 改写状态
                completed = true;
                return owner.createMsg().setParam(TLAiAgentParamString.RESULT, false)
                        .setParam("timedOut", true)
                        .setParam(TLAiAgentParamString.AI_P_RESPONSE, "流式超时/未完成");
            }
        } catch (InterruptedException e) {
            // 中断（/stop）也标记终态：防迟到的 done 把 doneMsg 写进已放弃的 handle
            completed = true;
            throw e;
        }
        if (error != null) {
            return owner.createMsg().setParam(TLAiAgentParamString.RESULT, false)
                    .setParam(TLAiAgentParamString.AI_P_RESPONSE, "流式中断: " + error);
        }
        return doneMsg;
    }
}
