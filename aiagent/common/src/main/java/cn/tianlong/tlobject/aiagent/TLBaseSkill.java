package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 抽象Skill基类。所有skill模块继承此类。
 * 每个skill是一个可被LLM调用的功能单元，拥有名称、描述和参数schema。
 * 遵循TLBaseCache模式：抽象基类 + 具体实现注册到XML配置。
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public abstract class TLBaseSkill extends TLBaseModule implements TLAiAgentParamString {

    /** LLM可见的skill名称（对应function name） */
    protected String skillName;

    /** skill的NL描述，LLM据此决定何时调用 */
    protected String skillDescription;

    /** JSON Schema格式的参数定义 */
    protected Map<String, Object> parameterSchema;

    /** SKILL.md 路径（显式配置或自动发现） */
    protected String skillMdPath;

    /** 是否启用 */
    protected boolean enabled = true;

    public TLBaseSkill() {
        super();
    }

    public TLBaseSkill(String name) {
        super(name);
    }

    public TLBaseSkill(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("skillName") != null)
                skillName = params.get("skillName");
            if (params.get("skillDescription") != null)
                skillDescription = params.get("skillDescription");
            if (params.get("skillMd") != null)
                skillMdPath = params.get("skillMd");
            if (params.get("enabled") != null)
                enabled = Boolean.parseBoolean(params.get("enabled"));
        }
        // 默认用模块名作为skillName
        if (skillName == null || skillName.isEmpty())
            skillName = name;
        if (skillDescription == null)
            skillDescription = name + " skill";
        if (parameterSchema == null)
            parameterSchema = new LinkedHashMap<>();

        // 自动加载 SKILL.md（子类覆盖可扩展发现路径）
        loadSkillMd();
    }

    @Override
    protected TLBaseModule init() {
        return this;
    }

    // ======================== SKILL.md 自动加载 ========================

    /**
     * 加载 SKILL.md 并注入 skillDescription。
     * 查找顺序：
     * 1. XML 显式配置 skillMd 路径
     * 2. classpath 同 package 下 {skillName}.md
     * 3. classpath 同 package 下 SKILL.md
     * 子类可覆盖以扩展发现路径（如 TLScriptExecutionSkill 额外查找脚本目录）。
     */
    protected void loadSkillMd() {
        String content = null;

        // 1. XML 显式配置
        if (skillMdPath != null && !skillMdPath.isEmpty()) {
            content = readFileOrResource(skillMdPath);
        }

        // 2. classpath 同 package 下 {skillName}.md
        if (content == null) {
            String pkgPath = this.getClass().getPackage().getName().replace('.', '/');
            content = readClasspathResource(pkgPath + "/" + skillName + ".md");
        }

        // 3. classpath 同 package 下 SKILL.md
        if (content == null) {
            String pkgPath = this.getClass().getPackage().getName().replace('.', '/');
            content = readClasspathResource(pkgPath + "/SKILL.md");
        }

        if (content == null || content.trim().isEmpty()) return;

        // 解析 YAML frontmatter
        String fmDescription = null;
        String body = content;

        if (content.startsWith("---")) {
            int endIdx = content.indexOf("\n---", 3);
            if (endIdx > 0) {
                String frontmatter = content.substring(4, endIdx).trim();
                body = content.substring(endIdx + 4).trim();
                for (String line : frontmatter.split("\n")) {
                    line = line.trim();
                    if (line.startsWith("description:")) {
                        fmDescription = line.substring("description:".length()).trim();
                        // YAML 多行折叠语法 >- / >
                        if (fmDescription.startsWith(">-")) fmDescription = fmDescription.substring(2).trim();
                        else if (fmDescription.startsWith(">")) fmDescription = fmDescription.substring(1).trim();
                        // YAML 引号
                        if ((fmDescription.startsWith("\"") && fmDescription.endsWith("\""))
                                || (fmDescription.startsWith("'") && fmDescription.endsWith("'")))
                            fmDescription = fmDescription.substring(1, fmDescription.length() - 1);
                    }
                }
            }
        }

        // frontmatter description 与已有 skillDescription 合并
        if (fmDescription != null && !fmDescription.isEmpty()) {
            if (skillDescription == null || skillDescription.equals(name + " skill")) {
                skillDescription = fmDescription;
            } else if (!skillDescription.contains(fmDescription)) {
                skillDescription = fmDescription + "\n\n" + skillDescription;
            }
        }

        // 正文追加（去重）
        if (body != null && !body.isEmpty()) {
            if (skillDescription == null || skillDescription.equals(name + " skill")) {
                skillDescription = body;
            } else if (!skillDescription.contains(body)) {
                skillDescription = skillDescription + "\n\n" + body;
            }
        }
    }

    /** 尝试文件系统，回退到 classpath */
    private String readFileOrResource(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return readClasspathResource(path);
        }
    }

    /** 从 classpath 读取资源文件 */
    private String readClasspathResource(String resourcePath) {
        if (resourcePath.startsWith("./") || resourcePath.startsWith(".\\"))
            resourcePath = resourcePath.substring(2);
        InputStream is = this.getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) return null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case SKILL_GETINFO:
                returnMsg = getSkillInfo(fromWho, msg);
                break;
            case SKILL_EXECUTE:
                returnMsg = execute(fromWho, msg);
                break;
            case SKILL_VALIDATE:
                returnMsg = validate(fromWho, msg);
                break;
            default:
                returnMsg = null;
        }
        return returnMsg;
    }

    // ======================== 公共方法 ========================

    /**
     * 返回skill的元信息（名称、描述、参数schema）
     */
    protected TLMsg getSkillInfo(Object fromWho, TLMsg msg) {
        return createMsg()
                .setParam(AI_P_SKILLNAME, skillName)
                .setParam(AI_P_SKILLDESCRIPTION, skillDescription)
                .setParam(AI_P_SKILLPARAMS, parameterSchema);
    }

    /**
     * 执行skill。子类必须实现。
     * 通过 putMsg(skillModule, msg) 消息传递方式调用，
     * TLBaseSkill.checkMsgAction 中 switch(SKILL_EXECUTE) 路由到此方法。
     */
    protected abstract TLMsg execute(Object fromWho, TLMsg msg);

    /**
     * 校验输入参数是否匹配parameterSchema。
     */
    @SuppressWarnings("unchecked")
    protected TLMsg validate(Object fromWho, TLMsg msg) {
        Map<String, Object> input = (Map<String, Object>) msg.getMapParam(AI_P_SKILLINPUT, new LinkedHashMap<>());
        // 简易校验：检查required参数是否存在
        for (Map.Entry<String, Object> entry : parameterSchema.entrySet()) {
            String paramName = entry.getKey();
            Object schemaDef = entry.getValue();
            if (schemaDef instanceof Map) {
                Map<String, Object> defMap = (Map<String, Object>) schemaDef;
                if (Boolean.TRUE.equals(defMap.get("required"))) {
                    if (!input.containsKey(paramName)) {
                        return createMsg()
                                .setParam(RESULT, false)
                                .setParam("error", "Missing required parameter: " + paramName);
                    }
                }
            }
        }
        return createMsg().setParam(RESULT, true);
    }

    /**
     * 将skill转换为LLM function definition。
     */
    public TLFunctionDefinition buildFunctionDefinition() {
        return TLFunctionDefinition.fromSkill(skillName, skillDescription, parameterSchema);
    }

    // ======================== getters/setters ========================

    public String getSkillName() { return skillName; }
    public void setSkillName(String skillName) { this.skillName = skillName; }

    public String getSkillDescription() { return skillDescription; }
    public void setSkillDescription(String skillDescription) { this.skillDescription = skillDescription; }

    public Map<String, Object> getParameterSchema() { return parameterSchema; }
    public void setParameterSchema(Map<String, Object> parameterSchema) { this.parameterSchema = parameterSchema; }

    public boolean isEnabled() { return enabled; }
    /**
     * 契约：任何在运行时改变 skill 集合或其 enabled 状态的路径，都必须触发所属
     * TLAiAgent 的 invalidateToolDefs()（如通过 setSkillEnabled action），否则
     * function definitions 缓存会 stale。直接调用本 setter 不会自动失效缓存。
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
