package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 流式回调模块——接收LLM流式响应的通用模块。
 * 提供文本块累积、同步等待、缓冲管理等能力。
 * 任何需要使用流式Chat的场景都可以注册此模块作为回调目标。
 *
 * 使用方式:
 *   1. 在moduleFactory中注册: <module name="streamCallback" classfile="...TLStreamCallback"/>
 *   2. 流式请求中指定: resultFor="streamCallback", resultAction="onStreamChunk"
 *   3. 等待完成: putMsg("streamCallback", {action:"waitForStream", timeout:60})
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public class TLStreamCallback extends TLBaseModule implements TLAiAgentParamString {

    private final Object bufferLock = new Object();
    private StringBuilder streamBuffer = new StringBuilder();
    private StringBuilder reasoningBuffer = new StringBuilder();
    /** volatile：wait 侧在锁外 snapshot，reset 侧在锁内换代 */
    private volatile CountDownLatch streamLatch;
    private volatile boolean streamDone = false;
    private volatile String streamError = null;
    /** done 事件携带的 finish_reason（stop/tool_calls/length）——供同步流式入口判断"被截断" */
    private volatile String finishReason = null;

    public TLStreamCallback() {
        super();
    }

    public TLStreamCallback(String name) {
        super(name);
    }

    public TLStreamCallback(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected TLBaseModule init() {
        resetStreamState();
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case STREAM_ONCHUNK:  return onStreamChunk(fromWho, msg);
            case STREAM_ONDONE:   return onStreamDone(fromWho, msg);
            case STREAM_ONERROR:  return onStreamError(fromWho, msg);
            case STREAM_GETBUFFER: return getStreamBuffer(fromWho, msg);
            case STREAM_CLEARBUFFER: return clearStreamBuffer(fromWho, msg);
            case STREAM_RESET:    return resetStream(fromWho, msg);
            case STREAM_WAITFORSTREAM:  return waitForStream(fromWho, msg);
            default: return null;
        }
    }

    // ======================== 流式回调处理 ========================

    protected TLMsg onStreamChunk(Object fromWho, TLMsg msg) {
        synchronized (bufferLock) {
            if (msg.containsParam(AI_P_CHUNK)) {
                streamBuffer.append(msg.getStringParam(AI_P_CHUNK, ""));
            }
            if (msg.containsParam(AI_P_REASONING_CHUNK)) {
                reasoningBuffer.append(msg.getStringParam(AI_P_REASONING_CHUNK, ""));
            }
            // 输出护栏改写：整段替换已累积文本（不是追加）。
            // ForwardStreamSink 在终答前缀不成立时发此帧；测试/外部集成若把它当 sink，
            // 也必须认同这个协议，否则会保留未净化的原文。
            if (msg.containsParam(AI_P_STREAMREPLACE)) {
                streamBuffer = new StringBuilder(msg.getStringParam(AI_P_STREAMREPLACE, ""));
            }
            if (msg.parseBoolean(AI_P_STREAMDONE, false)) {
                onStreamDone(fromWho, msg);
            }
            if (msg.containsParam(AI_P_STREAMERROR)) {
                onStreamError(fromWho, msg);
            }
        }
        return null;
    }

    protected TLMsg onStreamDone(Object fromWho, TLMsg msg) {
        synchronized (bufferLock) {
            streamDone = true;
            // 保存 finish_reason：流式收集端（测试 sink / 外部集成）靠它判断"被截断"
            if (msg.containsParam(AI_P_FINISH_REASON)) {
                finishReason = msg.getStringParam(AI_P_FINISH_REASON, "");
            }
            if (msg.containsParam(AI_P_RESPONSE) && streamBuffer.length() == 0) {
                streamBuffer.append(msg.getStringParam(AI_P_RESPONSE, ""));
            }
            // 与 reset 共用 bufferLock：避免 reset 换 latch 后 done 打向另一代
            CountDownLatch latch = streamLatch;
            if (latch != null) latch.countDown();
        }
        putLog("Stream completed: " + streamBuffer.length() + " chars", LogLevel.DEBUG);
        return null;
    }

    protected TLMsg onStreamError(Object fromWho, TLMsg msg) {
        synchronized (bufferLock) {
            streamError = msg.getStringParam(AI_P_STREAMERROR, "Unknown stream error");
            streamDone = true;
            CountDownLatch latch = streamLatch;
            if (latch != null) latch.countDown();
        }
        putLog("Stream error: " + streamError, LogLevel.ERROR);
        return null;
    }

    // ======================== 工具方法 ========================

    protected TLMsg getStreamBuffer(Object fromWho, TLMsg msg) {
        synchronized (bufferLock) {
            return createMsg().setParam(RESULT, true)
                    .setParam("content", streamBuffer.toString())
                    .setParam("length", streamBuffer.length())
                    .setParam("streamDone", streamDone)
                    .setParam(AI_P_STREAMERROR, streamError)
                    .setParam(AI_P_FINISH_REASON, finishReason == null ? "" : finishReason)
                    .setParam(AI_P_REASONING, reasoningBuffer.toString());
        }
    }

    protected TLMsg clearStreamBuffer(Object fromWho, TLMsg msg) {
        synchronized (bufferLock) {
            streamBuffer = new StringBuilder();
        }
        return createMsg().setParam(RESULT, true);
    }

    protected TLMsg resetStream(Object fromWho, TLMsg msg) {
        resetStreamState();
        return createMsg().setParam(RESULT, true);
    }

    protected TLMsg waitForStream(Object fromWho, TLMsg msg) {
        int timeoutSeconds = msg.getIntParam("timeout", 60);
        // snapshot 当前代：reset 换 latch 时会 countDown 旧 latch（见 resetStreamState），
        // 所以旧代的等待者不会永久挂起；completed 还要与 streamDone 取交，防止 reset 的
        // countDown 被误判成"流正常完成"。
        CountDownLatch latch = streamLatch;
        if (latch == null) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_STREAMERROR, "stream latch not initialized");
        }
        try {
            boolean completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);
            synchronized (bufferLock) {
                completed = completed && streamDone;
                return createMsg().setParam(RESULT, completed)
                        .setParam("content", streamBuffer.toString())
                        .setParam("streamDone", streamDone)
                        .setParam(AI_P_STREAMERROR, streamError)
                        .setParam(AI_P_FINISH_REASON, finishReason == null ? "" : finishReason)
                        .setParam(AI_P_REASONING, reasoningBuffer.toString())
                        .setParam("timedOut", !completed);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_STREAMERROR, "Interrupted");
        }
    }

    // ======================== 内部方法 ========================

    private void resetStreamState() {
        synchronized (bufferLock) {
            streamBuffer = new StringBuilder();
            reasoningBuffer = new StringBuilder();
            streamDone = false;
            streamError = null;
            finishReason = null;
            CountDownLatch old = streamLatch;
            streamLatch = new CountDownLatch(1);
            // 释放等上一代的等待者：否则换 latch 后旧 latch 永远没人 countDown，
            // waitForStream 会一直等到超时（甚至无超时配置下永久挂起）。
            if (old != null) old.countDown();
        }
    }

    public String getContent() {
        synchronized (bufferLock) {
            return streamBuffer.toString();
        }
    }
    public String getReasoning() {
        synchronized (bufferLock) {
            return reasoningBuffer.toString();
        }
    }
    public boolean isStreamDone() { return streamDone; }
    public String getStreamError() { return streamError; }
}
