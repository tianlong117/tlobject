package cn.tianlong.tlobject.aiagent.evals;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 用例生成器的纯逻辑：解析 LLM 输出 / 校验用例 / 生成文件名。
 *
 * 不依赖框架，也不碰 IO——这几步是最容易出错、也最该能脱离运行环境验证的部分。
 *
 * 创建日期：2026/9/19
 * 作者:tianlong
 */
public class TLEvalGenSupport {

    private TLEvalGenSupport() {}

    private static final Gson GSON = new Gson();

    /**
     * 解析 LLM 返回的用例 JSON。
     * LLM 不总是乖乖只吐 JSON——可能包在 markdown 围栏里，也可能前后带一句废话，
     * 所以先剥围栏、再按最外层方括号截取。都失败返回空表（调用方据此报错）。
     */
    public static List<TLEvalCase> parseCasesJson(String raw) {
        List<TLEvalCase> out = new ArrayList<>();
        if (raw == null) return out;
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            int fence = s.lastIndexOf("```");
            if (fence >= 0) s = s.substring(0, fence);
            s = s.trim();
        }
        int lb = s.indexOf('[');
        int rb = s.lastIndexOf(']');
        if (lb < 0 || rb <= lb) return out;
        String json = s.substring(lb, rb + 1);
        try {
            Type listType = new TypeToken<List<TLEvalCase>>() {}.getType();
            List<TLEvalCase> parsed = GSON.fromJson(json, listType);
            if (parsed != null) out.addAll(parsed);
        } catch (Exception ignored) {
            // 解析不了就是空表，由调用方报"输出解析失败"并回显原文
        }
        return out;
    }

    /**
     * 丢掉不合格的用例，返回可落盘的那些。
     * 丢弃原因逐条记进 dropped，供回执里说明——静默丢会让用户以为生成得少。
     *
     * @param existingIds 用例目录里已有的 id（冲突的直接丢，不静默改名）
     */
    public static List<TLEvalCase> validateAndFilter(List<TLEvalCase> raw, List<String> existingIds,
                                                     List<String> dropped) {
        List<TLEvalCase> kept = new ArrayList<>();
        Set<String> seenIds = existingIds == null ? new HashSet<>() : new HashSet<>(existingIds);
        if (raw == null) return kept;
        for (TLEvalCase c : raw) {
            if (c == null) {
                dropped.add("空条目");
                continue;
            }
            if (c.id == null || c.id.trim().isEmpty()) {
                String hint = c.name != null ? c.name
                        : (c.input != null ? String.valueOf(c.input) : "(无名无输入)");
                dropped.add("缺 id（用例：" + hint + "）");
                continue;
            }
            // 没有 judges 的用例会被评测直接判通过——那是假绿灯，宁可不要
            if (c.judges == null || c.judges.isEmpty()) {
                dropped.add(c.id + "：缺 judges（没有判据的用例会被直接判通过）");
                continue;
            }
            if (c.input == null) {
                dropped.add(c.id + "：缺 input");
                continue;
            }
            // exact_match 判据靠 evalCase.expectedOutput 比对，而 TLExactMatchJudge 对空期望值
            // 直接判 fail——漏填就是一条恒定红灯的用例，比不生成更糟
            if (hasExactMatchWithoutExpected(c)) {
                dropped.add(c.id + "：exact_match 判据缺 expectedOutput（会永远判失败）");
                continue;
            }
            if (!seenIds.add(c.id)) {
                dropped.add(c.id + "：id 冲突（与已有用例或本批重复）");
                continue;
            }
            kept.add(c);
        }
        return kept;
    }

    /** 用例里有没有 exact_match 判据、却压根没给期望输出 */
    private static boolean hasExactMatchWithoutExpected(TLEvalCase c) {
        if (c.expectedOutput != null && !c.expectedOutput.trim().isEmpty()) return false;
        if (c.judges == null) return false;
        for (JudgeConfig j : c.judges) {
            if (j != null && "exact_match".equals(j.type)) return true;
        }
        return false;
    }

    /**
     * 把每条用例的 targetAgent 强制设成用户指定的目标。
     * LLM 不该改评测对象——它可能在用例里塞一个别的 agent（schema 里没让它填，
     * 但模型有时会自作主张），那会让"给 A 写的用例"悄悄去打 B。
     */
    public static void normalizeTarget(List<TLEvalCase> cases, String target) {
        if (cases == null || target == null || target.isEmpty()) return;
        for (TLEvalCase c : cases) {
            if (c != null) c.targetAgent = target;
        }
    }

    /** 一次生成 = 一个文件：<目标短名>-<yyyyMMdd-HHmm>.json */
    public static String buildFileName(String targetName, String stamp) {
        String shortName = targetName == null ? "agent" : targetName;
        int colon = shortName.lastIndexOf(':');
        if (colon >= 0) shortName = shortName.substring(colon + 1);
        shortName = shortName.replaceAll("[^A-Za-z0-9_-]", "");
        if (shortName.isEmpty()) shortName = "agent";
        return shortName + "-" + stamp + ".json";
    }
}
