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

    /** 用户输入消息 */
    public String input;

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

    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }

    public String getExpectedOutput() { return expectedOutput; }
    public void setExpectedOutput(String expectedOutput) { this.expectedOutput = expectedOutput; }

    public List<String> getExpectedToolCalls() { return expectedToolCalls; }
    public void setExpectedToolCalls(List<String> expectedToolCalls) { this.expectedToolCalls = expectedToolCalls; }

    public List<JudgeConfig> getJudges() { return judges; }
    public void setJudges(List<JudgeConfig> judges) { this.judges = judges; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
