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

    /**
     * 过程旁白：工具轮执行前给 UI 的一句话说明。
     * default no-op，保证既有测试 sink 与外部实现源码兼容。
     *
     * @param text            旁白文本（纯文本，已裁剪）
     * @param alreadyStreamed true = 文本已由 onChunk 流进当前气泡，UI 只封印不重复追加
     * @param round           1-based LLM 迭代轮次
     */
    default void onNarration(String text, boolean alreadyStreamed, int round) { }

    void onFinalText(String answer, boolean alreadyStreamed);

    void onDone();

    void onError(String message);

    /** 非流式路径的空实现：所有回调 no-op */
    final class NullStreamSink implements StreamSink {
        public static final NullStreamSink INSTANCE = new NullStreamSink();

        @Override public boolean isNull() { return true; }
        @Override public void onChunk(String text) { }
        @Override public void onToolEvent(String toolName, String output) { }
        @Override public void onNarration(String text, boolean alreadyStreamed, int round) { }
        @Override public void onFinalText(String answer, boolean alreadyStreamed) { }
        @Override public void onDone() { }
        @Override public void onError(String message) { }
    }
}
