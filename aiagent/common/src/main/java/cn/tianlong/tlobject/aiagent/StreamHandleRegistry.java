package cn.tianlong.tlobject.aiagent;

/** 流式句柄的会话级登记表（实现在 SessionRegistry/Agent）。 */
interface StreamHandleRegistry {
    void set(String sessionId, StreamHandle handle);
    void clear(String sessionId, StreamHandle expect);
}
