package cn.tianlong.tlobject.aiagent.mcp;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP Tool 定义 POJO，对应 MCP 协议 tools/list 返回的单个 tool。
 * 每个 tool 有 name、description 和 inputSchema（JSON Schema 格式）。
 *
 * 创建日期：2026/7/7
 * 作者:tianlong
 */
public class McpTool implements Serializable {
    private static final long serialVersionUID = -7495836287107259436L;

    /** MCP tool 名称（如 read_file, write_file） */
    private String name;

    /** tool 的自然语言描述 */
    private String description;

    /** JSON Schema 格式的输入参数定义 */
    private Map<String, Object> inputSchema;

    public McpTool() {
        this.inputSchema = new LinkedHashMap<>();
    }

    public McpTool(String name, String description, Map<String, Object> inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema != null ? inputSchema : new LinkedHashMap<>();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, Object> getInputSchema() { return inputSchema; }
    public void setInputSchema(Map<String, Object> inputSchema) { this.inputSchema = inputSchema; }

    @Override
    public String toString() {
        return "McpTool{name=" + name + ", description=" + description + '}';
    }
}
