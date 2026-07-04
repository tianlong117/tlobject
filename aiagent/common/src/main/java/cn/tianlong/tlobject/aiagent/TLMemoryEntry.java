package cn.tianlong.tlobject.aiagent;

import java.io.Serializable;
import java.util.Map;

/**
 * 记忆条目POJO。在Memory模块中存储和检索。
 * 支持短期（内存）和长期（文件持久化）记忆。
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public class TLMemoryEntry implements Serializable {
    private static final long serialVersionUID = 1962329380390742089L;

    private String key;
    private Object value;
    private String type;      // "shortTerm" or "longTerm"
    private String tag;       // 分类标签
    private long createdAt;
    private long expiresAt;   // 0 = 永不过期
    private Map<String, Object> metadata;

    public TLMemoryEntry() {
        this.createdAt = System.currentTimeMillis();
    }

    public TLMemoryEntry(String key, Object value, String type) {
        this();
        this.key = key;
        this.value = value;
        this.type = type;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public boolean isExpired() {
        if (expiresAt <= 0) return false;
        return System.currentTimeMillis() > expiresAt;
    }

    @Override
    public String toString() {
        return "TLMemoryEntry{key=" + key + ", type=" + type + ", tag=" + tag + '}';
    }
}
