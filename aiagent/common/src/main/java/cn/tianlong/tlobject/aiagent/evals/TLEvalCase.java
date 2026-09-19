package cn.tianlong.tlobject.aiagent.evals;

import java.util.List;
import java.util.Map;

/**
 * 评测用例 POJO。
 * 从 JSON 文件反序列化，描述一个评测用例的输入、期望和评判标准。
 *
 * 创建日期：2026/7/29
 * 作者:tianlong
 */
public class TLEvalCase {
    /** 用例唯一标识（如 "weather-query-001"） */
    public String id;

    /** 用例名称（人类可读） */
    public String name;

    /** 用户输入。agent_chat 模式为字符串消息，skill_execute 模式为 JSON 对象（参数 Map） */
    public Object input;

    /** 用例级目标模块名，覆盖 evals_config.xml 中的全局 targetAgent（可选）。为 null 时使用全局配置 */
    public String targetAgent;

    /** 调用类型：agent_chat（默认，发 AGENT_CHAT 消息）| skill_execute（发 SKILL_EXECUTE 消息） */
    public String callType;

    /** 期望的最终输出文本（exact_match 评判用） */
    public String expectedOutput;

    /** 期望调用的工具名称列表 */
    public List<String> expectedToolCalls;

    /** 评判器配置列表 */
    public List<JudgeConfig> judges;

    /** 扩展元数据（标签、难度、分类等） */
    public Map<String, Object> metadata;

    public TLEvalCase() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTargetAgent() { return targetAgent; }
    public void setTargetAgent(String targetAgent) { this.targetAgent = targetAgent; }

    public String getCallType() { return callType; }
    public void setCallType(String callType) { this.callType = callType; }

    public String getInput() { return input != null ? input.toString() : null; }
    public void setInput(String input) { this.input = input; }
    /** 获取原始 input 对象（skill_execute 模式用于获取参数 Map） */
    public Object getInputRaw() { return input; }
    public void setInputRaw(Object input) { this.input = input; }

    public String getExpectedOutput() { return expectedOutput; }
    public void setExpectedOutput(String expectedOutput) { this.expectedOutput = expectedOutput; }

    public List<String> getExpectedToolCalls() { return expectedToolCalls; }
    public void setExpectedToolCalls(List<String> expectedToolCalls) { this.expectedToolCalls = expectedToolCalls; }

    public List<JudgeConfig> getJudges() { return judges; }
    public void setJudges(List<JudgeConfig> judges) { this.judges = judges; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    /**
     * 用例稳定性标记，取自 metadata.stability：stable（默认）/ flaky / experimental。
     * 用 metadata 而不是新增字段，是为了不破坏已有用例的 JSON schema。
     */
    public String getStability() {
        if (metadata == null) return "stable";
        Object v = metadata.get("stability");
        return (v != null && !v.toString().trim().isEmpty()) ? v.toString().trim() : "stable";
    }
}
