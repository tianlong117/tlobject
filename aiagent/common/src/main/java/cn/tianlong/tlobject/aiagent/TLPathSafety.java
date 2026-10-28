package cn.tianlong.tlobject.aiagent;

/**
 * 文件路径片段净化：把 userId / sessionId / 任务名等用户可控值拼进文件系统路径前的统一防线。
 *
 * <p>只处理“单一路径段”——结果不含路径分隔符。"." / ".." 这类特殊段回落默认值，
 * 防止 {@code data/<userId>/...} 被上跳到 data 之外；Windows 尾部的点和空格会被系统
 * 静默吞掉（"user" 与 "user." 落到同一目录），也一并替换。</p>
 *
 * <p>注意：文件名（非目录段）里的点号是合法的，本方法只替换“尾部”点/空格，保留
 * "john.doe" 这类正常 ID。</p>
 *
 * 创建日期：2026/10/28
 * 作者:tianlong
 */
public final class TLPathSafety {

    private TLPathSafety() {}

    /**
     * 净化成单一路径段。
     *
     * @param raw 原始值（可为 null）
     * @param def 空值/特殊值的回落值（null/空 → "default"）
     */
    public static String safeSegment(String raw, String def) {
        String fallback = (def == null || def.isEmpty()) ? "default" : def;
        if (raw == null) return fallback;
        // 路径分隔符 + Windows 非法字符 + 控制字符
        String s = raw.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_").trim();
        if (s.isEmpty() || ".".equals(s) || "..".equals(s)) return fallback;
        // Windows 忽略尾部点/空格：避免 "user" 与 "user." 落到同一目录
        s = s.replaceAll("[. ]+$", "_");
        // Windows 设备名（CON/NUL/COM1...）不能作为普通文件名
        if (s.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) return "_" + s;
        return s;
    }

    public static String safeSegment(String raw) {
        return safeSegment(raw, "default");
    }
}
