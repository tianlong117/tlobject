package cn.tianlong.tlobject.aiagent.evals;

import java.util.Map;

/**
 * 评判器配置 POJO。
 * 从 JSON 用例文件的 judges[].config 反序列化。
 *
 * 创建日期：2026/7/29
 * 作者:tianlong
 */
public class JudgeConfig {
    /** 评判器类型：exact_match | llm_judge | constraint */
    public String type;

    /** 评判器配置参数，具体字段取决于 type */
    public Map<String, Object> config;

    public JudgeConfig() {}

    public JudgeConfig(String type, Map<String, Object> config) {
        this.type = type;
        this.config = config;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }

    public String getString(String key) {
        Object v = config != null ? config.get(key) : null;
        return v != null ? v.toString() : null;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object v = config != null ? config.get(key) : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        Object v = config != null ? config.get(key) : null;
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException e) {}
        }
        return defaultValue;
    }

    public double getDouble(String key, double defaultValue) {
        Object v = config != null ? config.get(key) : null;
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble((String) v); } catch (NumberFormatException e) {}
        }
        return defaultValue;
    }
}
