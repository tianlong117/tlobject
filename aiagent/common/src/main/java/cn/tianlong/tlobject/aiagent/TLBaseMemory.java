package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

/**
 * 抽象记忆模块基类。管理AI Agent的记忆存储和检索。
 * 短期记忆（内存Map，带TTL）和长期记忆（文件/数据库持久化）的具体实现继承此类。
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public abstract class TLBaseMemory extends TLBaseModule implements TLAiAgentParamString {

    /** 记忆类型：shortTerm / longTerm */
    protected String memoryType = "shortTerm";

    /** 默认过期时间（分钟），0 = 永不过期 */
    protected int defaultExptime = 0;

    /** 命名空间前缀，用于多Agent记忆隔离。通常设为所属Agent名称 */
    protected String agentNamespace = "";

    /** embedding 调用的实际 Provider 实例（由 Agent 注入；工厂单例无 apiKey，不可用名字查找） */
    protected TLLlmProvider embeddingProviderInstance;

    public TLBaseMemory() {
        super();
    }

    public TLBaseMemory(String name) {
        super(name);
    }

    public TLBaseMemory(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("memoryType") != null)
                memoryType = params.get("memoryType");
            if (params.get("defaultExptime") != null) {
                try {
                    defaultExptime = Integer.parseInt(params.get("defaultExptime"));
                } catch (NumberFormatException ignored) {}
            }
            if (params.get("agentNamespace") != null)
                agentNamespace = params.get("agentNamespace");
        }
    }

    /**
     * 构建带命名空间前缀的key，用于多Agent记忆隔离。
     * 若agentNamespace为空则返回原始key，保持向后兼容。
     */
    protected String buildScopedKey(String key) {
        if (agentNamespace != null && !agentNamespace.isEmpty())
            return agentNamespace + ":" + key;
        return key;
    }

    @Override
    protected TLBaseModule init() {
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case MEMORY_STORE:
                returnMsg = store(fromWho, msg);
                break;
            case MEMORY_RETRIEVE:
                returnMsg = retrieve(fromWho, msg);
                break;
            case MEMORY_SEARCH:
                returnMsg = search(fromWho, msg);
                break;
            case MEMORY_DELETE:
                returnMsg = delete(fromWho, msg);
                break;
            case MEMORY_CLEARALL:
                returnMsg = clearAll(fromWho, msg);
                break;
            case "setEmbeddingProvider":
                if (msg.getParam("provider") instanceof TLLlmProvider)
                    embeddingProviderInstance = (TLLlmProvider) msg.getParam("provider");
                returnMsg = createMsg().setParam(RESULT, true);
                break;
            default:
                returnMsg = null;
        }
        return returnMsg;
    }

    // ======================== 抽象方法（子类实现） ========================

    /**
     * 存储记忆条目
     */
    protected abstract TLMsg store(Object fromWho, TLMsg msg);

    /**
     * 按键检索记忆
     */
    protected abstract TLMsg retrieve(Object fromWho, TLMsg msg);

    /**
     * 按查询文本搜索记忆（支持模糊/语义搜索）
     */
    protected abstract TLMsg search(Object fromWho, TLMsg msg);

    /**
     * 按键删除记忆
     */
    protected abstract TLMsg delete(Object fromWho, TLMsg msg);

    /**
     * 清除所有记忆
     */
    protected abstract TLMsg clearAll(Object fromWho, TLMsg msg);

    // ======================== 工具方法 ========================

    /**
     * 计算过期时间戳
     */
    protected long calculateExpiresAt(int exptimeMinutes) {
        if (exptimeMinutes <= 0) {
            if (defaultExptime <= 0) return 0L;
            exptimeMinutes = defaultExptime;
        }
        return System.currentTimeMillis() + (exptimeMinutes * 60L * 1000L);
    }

    // ======================== getters/setters ========================

    public String getMemoryType() { return memoryType; }
    public void setMemoryType(String memoryType) { this.memoryType = memoryType; }

    public int getDefaultExptime() { return defaultExptime; }
    public void setDefaultExptime(int defaultExptime) { this.defaultExptime = defaultExptime; }
}
