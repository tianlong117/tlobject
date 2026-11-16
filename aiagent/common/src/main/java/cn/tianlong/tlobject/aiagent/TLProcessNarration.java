package cn.tianlong.tlobject.aiagent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具轮过程解说（旁白）生成：模型在 tool_calls 轮没写正文时，用工具名 + 白名单参数
 * 合成一句"正在…"，供 WebUI 聊天流持久展示、历史回放复用。
 *
 * <p>参数只读白名单键且只接受标量值，避免把任意工具参数（密码/token/嵌套对象）
 * 带进 UI 与会话历史。</p>
 */
public final class TLProcessNarration implements TLAiAgentParamString {

    /** 单条旁白上限：模型自己写超长 preamble 时按此裁剪（回放同规则） */
    public static final int MAX_NARRATION_CHARS = 800;
    /** 单个参数值裁剪长度：旁白只做提示，不搬运完整命令/长文本 */
    private static final int MAX_ARG_CHARS = 60;
    /** 只读这些键作为"这一步在做什么"的提示；顺序即优先级 */
    private static final String[] ARG_KEYS = {
            "query", "q", "keyword", "keywords", "url", "path", "file", "filepath",
            "filename", "command", "operation", "action", "target"
    };

    private TLProcessNarration() { }

    /** 每个 tool call 一行；去重后按原始顺序拼接。 */
    public static String forToolCalls(List<TLToolCall> calls) {
        if (calls == null || calls.isEmpty()) return "";
        Set<String> lines = new LinkedHashSet<>();
        for (TLToolCall call : calls) {
            if (call == null) continue;
            String line = forOne(call);
            if (line != null && !line.isEmpty()) lines.add(line);
        }
        return String.join("\n", lines);
    }

    private static String forOne(TLToolCall call) {
        String fn = call.getFunctionName() == null ? "" : call.getFunctionName().trim();
        String low = fn.toLowerCase();
        Map<String, Object> args = call.getArguments();
        String arg = pickArg(args);
        if (low.startsWith("delegate_to_")) {
            String target = fn.substring("delegate_to_".length());
            return "正在委托 " + target + " 处理任务";
        }
        if (low.contains("search") || fn.contains("搜索")) {
            return "正在搜索" + suffix(arg);
        }
        if (low.contains("file_operation") || low.contains("fileoperation")) {
            String op = pickArgByKey(args, "operation");
            String opl = op == null ? "" : op.toLowerCase();
            String verb;
            if (opl.contains("read") || opl.contains("get")) verb = "正在读取文件";
            else if (opl.contains("write") || opl.contains("create") || opl.contains("edit")
                    || opl.contains("append") || opl.contains("set")) verb = "正在写入文件";
            else if (opl.contains("list") || opl.contains("glob") || opl.contains("search"))
                verb = "正在浏览目录";
            else if (opl.contains("delete") || opl.contains("remove")) verb = "正在处理文件";
            else verb = "正在操作文件";
            return verb + suffix(arg);
        }
        if (low.contains("list") && (low.contains("dir") || low.contains("file")))
            return "正在浏览目录" + suffix(arg);
        if (low.contains("code_execution") || low.contains("script_execution")
                || low.contains("execute") || low.contains("python")) {
            return "正在执行代码";
        }
        if (low.contains("view_image")) return "正在查看图片" + suffix(arg);
        if (low.contains("word")) return "正在处理 Word 文档" + suffix(arg);
        if (low.contains("todo")) return "正在更新任务清单";
        if (low.contains("browser")) {
            String action = pickArgByKey(args, "action");
            return action == null || action.isEmpty()
                    ? "正在操作浏览器"
                    : "正在操作浏览器：" + clipValue(action);
        }
        if (low.contains("schedule") || low.contains("task")) return "正在处理任务";
        return fn.isEmpty() ? "正在处理" : "正在调用 " + fn;
    }

    private static String suffix(String arg) {
        return arg == null || arg.isEmpty() ? "" : "：" + arg;
    }

    /** 按白名单顺序取第一个非空标量参数 */
    private static String pickArg(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "";
        for (String key : ARG_KEYS) {
            String v = pickArgByKey(args, key);
            if (v != null && !v.isEmpty()) return clipValue(v);
        }
        return "";
    }

    private static String pickArgByKey(Map<String, Object> args, String key) {
        if (args == null || key == null) return null;
        Object v = args.get(key);
        if (v == null) {
            for (Map.Entry<String, Object> e : args.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                    v = e.getValue();
                    break;
                }
            }
        }
        // 只接受标量：嵌套对象/数组不搬运（可能含敏感结构或不适合展示的内容）
        if (!(v instanceof String) && !(v instanceof Number) && !(v instanceof Boolean)) return null;
        String s = String.valueOf(v);
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static String clipValue(String v) {
        String s = v.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        if (s.length() <= MAX_ARG_CHARS) return s;
        return s.substring(0, MAX_ARG_CHARS) + "…";
    }

    /** 归一化并裁剪旁白：压缩连续空行，超长按码点截断补省略号。 */
    public static String clip(String text, int max) {
        if (text == null) return "";
        String s = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        s = s.replaceAll("\n{3,}", "\n\n");
        if (max <= 0 || s.length() <= max) return s;
        int end = max;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end).trim() + "…";
    }

    /**
     * 历史回放用：content（模型当时的解说）优先，空则按 toolCalls 现场合成。
     * 不依赖额外 metadata——toolCalls 本就随消息持久化。
     */
    public static String historyNarration(TLConversationHistory ch) {
        if (ch == null) return "";
        String content = ch.getContent();
        if (content != null && !content.trim().isEmpty())
            return clip(content, MAX_NARRATION_CHARS);
        return clip(forToolCalls(ch.getToolCalls()), MAX_NARRATION_CHARS);
    }
}
