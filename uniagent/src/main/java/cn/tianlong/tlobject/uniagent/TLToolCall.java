package cn.tianlong.tlobject.uniagent;

import java.io.Serializable;
import java.util.Map;

/**
 * LLM返回的tool/function call数据结构。
 * 复制自 aiagent.TLToolCall，包名改为 uniagent。
 */
public class TLToolCall implements Serializable {
    private static final long serialVersionUID = 1682553227186489290L;

    private String id;
    private String type = "function";
    private String functionName;
    private Map<String, Object> arguments;

    public TLToolCall() {}

    public TLToolCall(String id, String functionName, Map<String, Object> arguments) {
        this.id = id;
        this.functionName = functionName;
        this.arguments = arguments;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getFunctionName() { return functionName; }
    public void setFunctionName(String functionName) { this.functionName = functionName; }

    public Map<String, Object> getArguments() { return arguments; }
    public void setArguments(Map<String, Object> arguments) { this.arguments = arguments; }

    @Override
    public String toString() {
        return "TLToolCall{id=" + id + ", function=" + functionName + ", args=" + arguments + '}';
    }
}
