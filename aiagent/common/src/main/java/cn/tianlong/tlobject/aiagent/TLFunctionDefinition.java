package cn.tianlong.tlobject.aiagent;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM function/tool定义。由TLBaseSkill.buildFunctionDefinition()生成，
 * 传递给LLM provider作为tool-use的函数schema。
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
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
     * 从Skill信息创建function定义
     */
    public static TLFunctionDefinition fromSkill(String skillName, String skillDescription,
                                                  Map<String, Object> parameterSchema) {
        TLFunctionDefinition def = new TLFunctionDefinition();
        def.name = skillName;
        def.description = skillDescription;
        // 构建标准JSON Schema格式
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", parameterSchema != null ? parameterSchema : new LinkedHashMap<>());
        Map<String, Object> required = new LinkedHashMap<>();
        // 提取required字段
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
