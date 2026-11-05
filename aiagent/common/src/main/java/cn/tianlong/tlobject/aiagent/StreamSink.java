package cn.tianlong.tlobject.aiagent;

/**
 * 流式事件出口：chunk 在回调线程调用，final/done/error 在编排线程调用。
 * 非流式路径使用 {@link NullStreamSink}。
 */
interface StreamSink {

    default boolean isNull() { return false; }

    void onChunk(String text);

    /**
     * 已成功转发给 UI 的流式文本。仅用于 Provider 的 done 消息缺 AI_P_RESPONSE 时的兜底：
     * 编排层优先用 done 的完整正文，缺失才读这里，避免把 UI 已收到的内容替换成占位符。
     * 默认空串（非流式/测试 sink 无累积语义）。
     */
    default String collectedText() { return ""; }

    void onToolEvent(String toolName, String output);

    void onFinalText(String answer, boolean alreadyStreamed);

    void onDone();

    void onError(String message);

    /** 非流式路径的空实现：所有回调 no-op */
    final class NullStreamSink implements StreamSink {
        public static final NullStreamSink INSTANCE = new NullStreamSink();

        @Override public boolean isNull() { return true; }
        @Override public void onChunk(String text) { }
        @Override public void onToolEvent(String toolName, String output) { }
        @Override public void onFinalText(String answer, boolean alreadyStreamed) { }
        @Override public void onDone() { }
        @Override public void onError(String message) { }
    }
}
