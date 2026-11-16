package cn.tianlong.tlobject.aiagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 过程旁白生成自测（runnable main，无 JUnit）。
 *
 * <p>守：工具轮没有模型正文时，按工具名 + 白名单参数合成"正在…"；
 * 只读白名单键（敏感参数不入 UI/历史）；多行去重；裁剪上限；历史回放优先级。
 */
public class TLProcessNarrationSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        // 1) 搜索：函数名 + query
        List<TLToolCall> search = List.of(call("tc1", "search",
                map("query", "肺癌 中国 酒消耗")));
        String s = TLProcessNarration.forToolCalls(search);
        check("搜索：含动作与 query", s.contains("正在搜索") && s.contains("肺癌 中国 酒消耗"));

        // 2) file_operation read：读 operation + path
        String f = TLProcessNarration.forToolCalls(List.of(call("tc2", "file_operation",
                map("operation", "read", "path", "workspace/u1/a.md"))));
        check("读文件：含路径", f.contains("正在读取文件") && f.contains("workspace/u1/a.md"));

        // 3) 非白名单参数不外泄
        String secret = TLProcessNarration.forToolCalls(List.of(call("tc3", "unknown_tool",
                map("password", "top-secret", "token", "abc123"))));
        check("未知工具：通用文案", secret.contains("正在调用 unknown_tool"));
        check("敏感参数不外泄", !secret.contains("top-secret") && !secret.contains("abc123"));

        // 4) 多个工具：逐行 + 去重
        String multi = TLProcessNarration.forToolCalls(List.of(
                call("t1", "search", map("query", "a")),
                call("t2", "search", map("query", "a")),
                call("t3", "todo", new LinkedHashMap<>())));
        check("多工具：两行（去重）", multi.split("\n").length == 2);
        check("多工具：含清单更新", multi.contains("正在更新任务清单"));

        // 5) 空参数：仍有一句可读文案
        String blank = TLProcessNarration.forToolCalls(
                List.of(call("t4", "browser", new LinkedHashMap<>())));
        check("空参数：浏览器通用文案", "正在操作浏览器".equals(blank));

        // 6) 超长裁剪
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 300; i++) longText.append("很长的说明");
        String clipped = TLProcessNarration.clip(longText.toString(),
                TLProcessNarration.MAX_NARRATION_CHARS);
        check("裁剪：不超过上限", clipped.length() <= TLProcessNarration.MAX_NARRATION_CHARS + 1);
        check("裁剪：补省略号", clipped.endsWith("…"));

        // 7) 历史回放：content 优先，空则按 toolCalls 合成
        TLConversationHistory withContent = new TLConversationHistory(
                TLConversationHistory.Role.assistant, "模型写的解说");
        check("历史：content 优先",
                "模型写的解说".equals(TLProcessNarration.historyNarration(withContent)));

        List<TLToolCall> calls = new ArrayList<>();
        calls.add(call("tc9", "view_image", map("path", "a.png")));
        TLConversationHistory emptyContent = new TLConversationHistory(
                TLConversationHistory.Role.assistant, calls);
        String hn = TLProcessNarration.historyNarration(emptyContent);
        check("历史：空 content 按工具合成", hn.contains("正在查看图片") && hn.contains("a.png"));

        check("空列表：空串", TLProcessNarration.forToolCalls(null).isEmpty()
                && TLProcessNarration.forToolCalls(List.of()).isEmpty());

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static TLToolCall call(String id, String fn, Map<String, Object> args) {
        return new TLToolCall(id, fn, args);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2)
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name);
        }
    }
}
