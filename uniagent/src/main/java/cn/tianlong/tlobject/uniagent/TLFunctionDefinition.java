package cn.tianlong.tlobject.uniagent;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM function/tool定义。
 * 复制自 aiagent.TLFunctionDefinition，包名改为 uniagent。
 */
public class TLFunctionDefinition implements Serializable {
    private static final long serialVersionUID = 4494366470699100525L;

    private String name;
    private String description;
    private Map<String, Object> parameters;

    public TLFunctionDefinition() {
        this.parameters = new LinkedHashMap<>();
    }

    public TLFunctionDefinition(String name, String description, Map<String, Object> parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters != null ? parameters : new LinkedHashMap<>();
    }

    /**
     * 从工具信息创建function定义
     */
    public static TLFunctionDefinition create(String toolName, String toolDescription,
                                               Map<String, Object> parameterSchema) {
        TLFunctionDefinition def = new TLFunctionDefinition();
        def.name = toolName;
        def.description = toolDescription;
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", parameterSchema != null ? parameterSchema : new LinkedHashMap<>());
        if (parameterSchema != null) {
            java.util.List<String> requiredList = new java.util.ArrayList<>();
            for (Map.Entry<String, Object> entry : parameterSchema.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> propDef = (Map<String, Object>) entry.getValue();
                    if (Boolean.TRUE.equals(propDef.get("required"))) {
                        requiredList.add(entry.getKey());
                        propDef.remove("required");
                    }
                }
            }
            if (!requiredList.isEmpty()) {
                schema.put("required", requiredList);
            }
        }
        def.parameters = schema;
        return def;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, Object> getParameters() { return parameters; }
    public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }

    @Override
    public String toString() {
        return "TLFunctionDefinition{name=" + name + ", description=" + description + '}';
    }
}
