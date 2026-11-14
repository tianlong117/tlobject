package cn.tianlong.tlobject.aiagent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 工具结果写入：按 toolCallId 配对、图片攒批注入、流式实时事件。
 * Phase 1 从 TLAiAgent.appendToolResults/appendToolResult 原样搬入。
 */
final class ToolResultAppender implements TLAiAgentParamString {

    private final AttachmentPipeline attachments;

    ToolResultAppender(AttachmentPipeline attachments) {
        this.attachments = attachments;
    }

    /**
     * 整批写入：results 与 tasks 按 toolCallId 配对（index 仅兜底），图片攒到批次末统一注入。
     *
     * <p>toolEventSink 喂的是<b>剥掉图片数据</b>的 UI 安全文本：聊天界面不再渲染浏览器截图
     * （用户明确要求"截图不要发到聊天界面"；模型仍通过附件管线拿到图）。旧实现把原始 base64
     * 直接推给 webui，前端从 screenshot_base64 提图渲染——既刷屏又占 DOM。
     */
    void appendAll(List<TLConversationHistory> history,
                   List<TLToolExecutor.ToolTask> tasks,
                   List<TLToolExecutor.ToolResult> results,
                   String sessionId, String userId,
                   BiConsumer<String, String> toolEventSink) {
        if (results == null) return;
        // 以 toolCallId 配对，index 只作兜底：results 一旦缺项，按 index 会把后面的工具名
        // 串到错误的结果上（tool_call_id 正确、name 错误），污染下一轮请求。
        Map<String, TLToolExecutor.ToolTask> taskById = new HashMap<>();
        if (tasks != null) {
            for (TLToolExecutor.ToolTask t : tasks) {
                if (t != null && t.toolCallId != null)
                    taskById.putIfAbsent(t.toolCallId, t);
            }
        }
        List<TLAttachmentRef> pendingImages = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            TLToolExecutor.ToolResult r = results.get(i);
            if (r == null) continue;
            TLToolExecutor.ToolTask task = r.toolCallId != null ? taskById.get(r.toolCallId) : null;
            if (task == null && tasks != null && i < tasks.size()) task = tasks.get(i);
            String toolName = task != null ? task.functionName : r.toolCallId;
            appendOne(history, toolName, r, sessionId, userId, pendingImages);
            // 无论附件管线是否成功提取，UI 侧一律剥掉图片数据：宁可界面看不到图，
            // 也不能把 base64 塞进聊天 DOM（用户要求 + 长工具循环的上下文/DOM 负担）
            if (toolEventSink != null)
                toolEventSink.accept(toolName, attachments.stripImageKeys(r.output));
        }
        attachments.flushToolImages(history, pendingImages);
    }

    /**
     * 单条写入（审批恢复路径复用）：图片进 pendingImages，由调用方批次末 flush。
     * 工具消息的 name 是函数名，不是 toolCallId。
     */
    void appendOne(List<TLConversationHistory> history, String toolName,
                   TLToolExecutor.ToolResult r, String sessionId, String userId,
                   List<TLAttachmentRef> pendingImages) {
        String uid = userId != null ? userId : sessionId;
        AttachmentPipeline.ToolImageBundle bundle =
                attachments.extractAndStrip(r.output, uid, history);
        String msgToolName = toolName != null && !toolName.isEmpty() ? toolName : r.toolCallId;
        history.add(new TLConversationHistory(r.toolCallId, msgToolName, bundle.text()));
        if (!bundle.images().isEmpty()) pendingImages.addAll(bundle.images());
    }
}
