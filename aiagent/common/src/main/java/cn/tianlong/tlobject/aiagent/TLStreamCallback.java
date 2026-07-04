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

    private StringBuilder streamBuffer = new StringBuilder();
    private CountDownLatch streamLatch;
    private volatile boolean streamDone = false;
    private String streamError = null;

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
            case "onStreamChunk":  return onStreamChunk(fromWho, msg);
            case "onStreamDone":   return onStreamDone(fromWho, msg);
            case "onStreamError":  return onStreamError(fromWho, msg);
            case "getStreamBuffer": return getStreamBuffer(fromWho, msg);
            case "clearStreamBuffer": return clearStreamBuffer(fromWho, msg);
            case "resetStream":    return resetStream(fromWho, msg);
            case "waitForStream":  return waitForStream(fromWho, msg);
            default: return null;
        }
    }

    // ======================== 流式回调处理 ========================

    protected TLMsg onStreamChunk(Object fromWho, TLMsg msg) {
        if (msg.containsParam(AI_P_CHUNK)) {
            streamBuffer.append(msg.getStringParam(AI_P_CHUNK, ""));
        }
        if (msg.parseBoolean(AI_P_STREAMDONE, false)) {
            onStreamDone(fromWho, msg);
        }
        if (msg.containsParam(AI_P_STREAMERROR)) {
            onStreamError(fromWho, msg);
        }
        return null;
    }

    protected TLMsg onStreamDone(Object fromWho, TLMsg msg) {
        streamDone = true;
        if (msg.containsParam(AI_P_RESPONSE) && streamBuffer.length() == 0) {
            streamBuffer.append(msg.getStringParam(AI_P_RESPONSE, ""));
        }
        if (streamLatch != null) streamLatch.countDown();
        putLog("Stream completed: " + streamBuffer.length() + " chars", LogLevel.DEBUG);
        return null;
    }

    protected TLMsg onStreamError(Object fromWho, TLMsg msg) {
        streamError = msg.getStringParam(AI_P_STREAMERROR, "Unknown stream error");
        streamDone = true;
        if (streamLatch != null) streamLatch.countDown();
        putLog("Stream error: " + streamError, LogLevel.ERROR);
        return null;
    }

    // ======================== 工具方法 ========================

    protected TLMsg getStreamBuffer(Object fromWho, TLMsg msg) {
        return createMsg().setParam(RESULT, true)
                .setParam("content", streamBuffer.toString())
                .setParam("length", streamBuffer.length())
                .setParam("streamDone", streamDone)
                .setParam(AI_P_STREAMERROR, streamError);
    }

    protected TLMsg clearStreamBuffer(Object fromWho, TLMsg msg) {
        streamBuffer = new StringBuilder();
        return createMsg().setParam(RESULT, true);
    }

    protected TLMsg resetStream(Object fromWho, TLMsg msg) {
        resetStreamState();
        return createMsg().setParam(RESULT, true);
    }

    protected TLMsg waitForStream(Object fromWho, TLMsg msg) {
        int timeoutSeconds = msg.getIntParam("timeout", 60);
        try {
            boolean completed = streamLatch.await(timeoutSeconds, TimeUnit.SECONDS);
            return createMsg().setParam(RESULT, completed)
                    .setParam("content", streamBuffer.toString())
                    .setParam("streamDone", streamDone)
                    .setParam(AI_P_STREAMERROR, streamError)
                    .setParam("timedOut", !completed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_STREAMERROR, "Interrupted");
        }
    }

    // ======================== 内部方法 ========================

    private void resetStreamState() {
        streamBuffer = new StringBuilder();
        streamDone = false;
        streamError = null;
        streamLatch = new CountDownLatch(1);
    }

    public String getContent() { return streamBuffer.toString(); }
    public boolean isStreamDone() { return streamDone; }
    public String getStreamError() { return streamError; }
}
