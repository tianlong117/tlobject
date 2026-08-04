package cn.tianlong.tlobject.uniagent;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * LLM 调试追踪记录。记录一次完整 LLM 调用的发送/响应原始信息。
 * 配合 {@link TLLlmProvider} 的 debugMode 使用，每次调用生成一条 trace，
 * 写入 session 专属的 .trace 文件。
 *
 * 复制自 aiagent.TLLlmTrace，包名改为 uniagent。
 *
 * 创建日期：2026/7/15
 * 作者:tianlong
 */
public class TLLlmTrace {

    private String sessionId;
    private String senderName;
    private String providerName;
    private String requestBody;
    private String responseBody;
    private int httpStatus;
    private long timestamp;
    private String model;
    private long durationMs;
    private int callIndex;

    public TLLlmTrace() {}

    /**
     * 将 trace 格式化为人类可读的文本块，追加到 trace 文件。
     */
    public String toFormattedString() {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

        // ==== Header ====
        sb.append("\n");
        sb.append("======================================================================\n");
        sb.append("[").append(sdf.format(new Date(timestamp))).append("]");
        sb.append("  #").append(callIndex);
        sb.append(" | model=").append(model != null ? model : "?");
        sb.append(" | ").append(durationMs).append("ms");
        sb.append(" | HTTP ").append(httpStatus);
        sb.append("\n");
        sb.append("Sender: ").append(senderName != null ? senderName : "unknown");
        sb.append("\n");
        sb.append("======================================================================\n");

        // ==== Request ====
        sb.append(">>> REQUEST:\n");
        if (requestBody != null && !requestBody.isEmpty()) {
            sb.append(prettyJson(requestBody));
        } else {
            sb.append("(empty)\n");
        }

        // ==== Response ====
        sb.append("<<< RESPONSE:\n");
        if (responseBody != null && !responseBody.isEmpty()) {
            sb.append(prettyJson(responseBody));
        } else {
            sb.append("(empty)\n");
        }

        return sb.toString();
    }

    /**
     * 简易 JSON 美化：做基本缩进，让 JSON 可读。
     * 不做完整解析，用简单状态机处理，避免引入额外依赖。
     */
    private String prettyJson(String json) {
        if (json == null || json.isEmpty()) return "(empty)\n";
        StringBuilder sb = new StringBuilder();
        int indent = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                sb.append(c);
                escaped = false;
                continue;
            }
            if (inString) {
                sb.append(c);
                if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            switch (c) {
                case '"':
                    inString = true;
                    sb.append(c);
                    break;
                case '{':
                case '[':
                    sb.append(c).append('\n');
                    indent++;
                    appendIndent(sb, indent);
                    break;
                case '}':
                case ']':
                    sb.append('\n');
                    indent = Math.max(0, indent - 1);
                    appendIndent(sb, indent);
                    sb.append(c);
                    break;
                case ',':
                    sb.append(c).append('\n');
                    appendIndent(sb, indent);
                    break;
                case ':':
                    sb.append(": ");
                    break;
                case ' ':
                case '\t':
                case '\n':
                case '\r':
                    // 跳过原始空白
                    break;
                default:
                    sb.append(c);
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    private void appendIndent(StringBuilder sb, int count) {
        for (int i = 0; i < count; i++) sb.append("  ");
    }

    // ======================== getters / setters ========================

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }

    public String getRequestBody() { return requestBody; }
    public void setRequestBody(String requestBody) { this.requestBody = requestBody; }

    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }

    public int getHttpStatus() { return httpStatus; }
    public void setHttpStatus(int httpStatus) { this.httpStatus = httpStatus; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public int getCallIndex() { return callIndex; }
    public void setCallIndex(int callIndex) { this.callIndex = callIndex; }

    @Override
    public String toString() {
        return "TLLlmTrace{session=" + sessionId + ", sender=" + senderName
                + ", model=" + model + ", httpStatus=" + httpStatus
                + ", duration=" + durationMs + "ms}";
    }
}
