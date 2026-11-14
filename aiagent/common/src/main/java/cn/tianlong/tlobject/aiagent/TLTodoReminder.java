package cn.tianlong.tlobject.aiagent;

import java.util.List;
import java.util.Locale;

/**
 * todo 清单"未完成项"提取：发送视图重锚定（TLAiAgent）与收尾门禁（ChatTurn）共用。
 *
 * <p>只认调用方给定范围内、名字含 todo 的最新 tool 结果——上一轮遗留清单可能与当前话题无关，
 * 由调用方用 roundStartIdx/msgStartIdx 排除。最新结果全部完成即返回 null（不回退看更早的清单）。
 */
public final class TLTodoReminder {

    private TLTodoReminder() { }

    /** 文本里状态为 pending/in_progress 的清单行；没有返回 null。最多 20 行，超出标注截断。 */
    public static String unfinishedItems(String todoText) {
        if (todoText == null || todoText.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String line : todoText.split("\\R")) {
            String t = line.trim();
            if (!(t.startsWith("- [ ]") || t.startsWith("- [~]"))) continue;
            if (n >= 20) {
                sb.append("- …（清单过长，已截断）\n");
                break;
            }
            sb.append(t).append('\n');
            n++;
        }
        return n == 0 ? null : sb.toString();
    }

    /**
     * 从 history 的 (fromExclusive, 末尾] 范围找最新名字含 todo 的 tool 结果，返回其未完成项。
     * 找不到、或最新清单已全部完成 → null。
     */
    public static String latestUnfinishedItems(List<TLConversationHistory> history, int fromExclusive) {
        if (history == null) return null;
        for (int i = history.size() - 1; i > fromExclusive; i--) {
            TLConversationHistory h = history.get(i);
            if (h.getRole() != TLConversationHistory.Role.tool) continue;
            String name = h.getName();
            if (name != null && name.toLowerCase(Locale.ROOT).contains("todo"))
                return unfinishedItems(h.getContent());
        }
        return null;
    }
}
