package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

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

    /** SKILL.md 的正文内容（frontmatter 之后），供子类复用（如规划提示词） */
    protected String skillMdBody;

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
        // 默认用模块名作为skillName（模块名是真实的名字，兜底合理）
        if (skillName == null || skillName.isEmpty())
            skillName = name;
        // ⚠ skillDescription 此处**故意不填默认值**。
        // 原因：本方法由子类 setModuleParams() 的第一行 super.setModuleParams() 调用，**整个方法
        // 先于子类方法体执行完毕**。若在此填了默认值，子类随后的
        // `if (skillDescription == null || isEmpty()) skillDescription = "长描述"` 就恒为假
        // ——类里写的描述成了死代码（实测 desktopJavaSkill 的 1100 字符操作指南从未进过真实
        // prompt，模型只看到 "desktop skill"）。
        // 兜底改到读取时做（effectiveSkillDescription()），并**发出 WARN**：技能没描述是配置
        // 缺陷，要让人看见，不能再编一个 "X skill" 静默糊过去。
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
     * 2. 配置目录下 md/ 文件夹：{configDir}md/{name}.md（与 Agent 统一）
     * 3. classpath 同 package 下 {name}.md
     * 子类可覆盖以扩展发现路径（如 TLScriptExecutionSkill 额外查找脚本目录）。
     */
    protected void loadSkillMd() {
        String content = null;

        // 1. XML 显式配置
        if (skillMdPath != null && !skillMdPath.isEmpty()) {
            content = readFileOrResource(skillMdPath);
        }

        // 2. 配置目录下 md/ 文件夹：{name}.md（与 TLAiAgent.loadAgentMd 同目录）
        if (content == null && moduleFactory != null) {
            content = readFileOrResource(moduleFactory.getConfigDir() + "md/" + name + ".md");
        }

        // 3. classpath 同 package 下 {name}.md
        if (content == null) {
            String pkgPath = this.getClass().getPackage().getName().replace('.', '/');
            content = readClasspathResource(pkgPath + "/" + name + ".md");
        }

        if (content == null || content.trim().isEmpty()) return;

        // 解析 YAML frontmatter
        String fmDescription = TLMdFileLoader.parseFrontmatterDescription(content);
        String body = TLMdFileLoader.parseBody(content);

        // 缓存正文供子类复用（如 TLPlanTaskSkill 的规划提示词）
        this.skillMdBody = body;

        // frontmatter description 与已有 skillDescription 合并
        if (fmDescription != null && !fmDescription.isEmpty()) {
            // 判空即可：不再拿 name + " skill" 当哨兵——那串已不再由本类预填
            if (skillDescription == null || skillDescription.isEmpty()) {
                skillDescription = fmDescription;
            } else if (!skillDescription.contains(fmDescription)) {
                skillDescription = fmDescription + "\n\n" + skillDescription;
            }
        }

        // 正文仅缓存为 skillMdBody，不追加到 skillDescription
        // （skillDescription 会暴露给 LLM 的 function definitions 和 listSkills 返回值，不宜过长）
    }

    /** 尝试文件系统，回退到 classpath */
    private String readFileOrResource(String path) {
        return TLMdFileLoader.readFileOrResource(path, this.getClass());
    }

    /** 从 classpath 读取资源文件 */
    private String readClasspathResource(String resourcePath) {
        return TLMdFileLoader.readClasspathResource(resourcePath, this.getClass());
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        String action = msg == null ? null : msg.getAction();
        return guard(action, () -> {
            switch (msg.getAction()) {
                case SKILL_GETINFO:
                    return getSkillInfo(fromWho, msg);
                case SKILL_EXECUTE:
                    return execute(fromWho, msg);
                case SKILL_VALIDATE:
                    return validate(fromWho, msg);
                case OUTPUT_VALIDATE_ACTION:
                    return validateOutput(fromWho, msg);
                default:
                    return null;
            }
        });
    }

    /**
     * 技能输出自校验（由 outputValidator 的 validator="self" 规则调用）。
     * 默认通过；子类按需覆盖。msg.getParam("result") 是本次执行的完整 TLMsg。
     *
     * <p>这是"组件自检"，不替代独立校验：不要用同一份产出逻辑自证（Bug 会共享），
     * 关键值优先用标准校验器的声明式 checks 或独立校验模块。
     */
    protected TLMsg validateOutput(Object fromWho, TLMsg msg) {
        return createMsg().setParam("valid", true);
    }

    /**
     * 技能动作统一兜底（backlog ⑥）：把动作体（标准 switch 之外的**自定义动作分发**同样适用，
     * 如 TLScheduleTaskSkill 的 runScheduledTask/onTaskStream 引擎回调）包进安全网——任何
     * Throwable 转"优雅工具报错"。逃出技能层的 Throwable 会经框架链（无 exceptionHandler 时
     * moduleFactory.shutdown(-1)）终止整个应用；此处是最后一道安全网（含 Error 族：IOError/SOE/OOM）。
     * ThreadDeath 原样重抛保持线程终止语义；异常 message 不泄给 LLM（只给类名 + 指向错误日志）。
     */
    protected TLMsg guard(String action, java.util.function.Supplier<TLMsg> body) {
        try {
            return body.get();
        } catch (ThreadDeath t) {
            throw t;   // 保持线程终止语义
        } catch (Throwable t) {
            // 各技能内部更精确的守卫照旧优先（更可行动的文案），本兜底是最后一道安全网。
            // 日志本身再包一层 try：日志路径（模块未初始化/无工厂）不能反过来把安全网搞崩。
            try {
                putLog("Skill [" + skillName + "] action=" + action
                        + " threw " + t.getClass().getName() + ": " + t.getMessage()
                        + "\n" + stackTraceToString(t), LogLevel.ERROR, "skill-guard");
            } catch (Throwable ignored) {
            }
            // 不向 LLM 泄漏 JVM 内部异常文本：只给类简单名 + 指向错误日志。
            // 匿名异常类 getSimpleName() 为空——退化为全限定名，别让文案成 "error:  ——"
            String cls = t.getClass().getSimpleName();
            if (cls == null || cls.isEmpty()) cls = t.getClass().getName();
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Skill internal error: " + cls
                            + " —— 操作未完成（详细信息见错误日志）");
        }
    }

    /** Throwable 堆栈转字符串（含 cause 链），供兜底日志落盘——putLog(Exception,...) 只接 Exception，
     *  Error 族（IOError/SOE）必须走字符串日志，否则堆栈在日志里无声丢失。 */
    private static String stackTraceToString(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    // ======================== 公共方法 ========================

    /** 缺描述时的告警只发一次，避免 toolDefs 反复重建时刷屏 */
    private boolean descMissingLogged = false;

    /**
     * 取生效的技能描述。
     *
     * 技能描述是**约定必须提供**的（写在子类 setModuleParams() 里，或 agent 配置的
     * skillDescription 属性里，或 SKILL.md 的 frontmatter 里）。三处都没有 = 配置缺陷：
     * 这里兜一个不含信息量的串保证链路不崩，但**同时发 WARN 点名**，让它可见——
     * 以前是在 setModuleParams() 里静默填 "X skill"，结果模型只收到 "desktop skill"
     * 这种毫无用处的描述，却没有任何地方报错。
     */
    protected String effectiveSkillDescription() {
        if (skillDescription != null && !skillDescription.isEmpty()) return skillDescription;
        if (!descMissingLogged) {
            descMissingLogged = true;
            // putLog 在模块未初始化/无工厂时会 NPE——告警路径不能反过来把链路搞崩
            try {
                putLog("技能 " + name + " 没有任何描述（子类 setModuleParams / 配置 skillDescription / "
                        + "SKILL.md frontmatter 三处都空）——模型将收到无意义的占位描述，请补上",
                        LogLevel.WARN);
            } catch (Throwable ignored) { }
        }
        return name + " skill";
    }

    /**
     * 返回skill的元信息（名称、描述、参数schema）
     */
    protected TLMsg getSkillInfo(Object fromWho, TLMsg msg) {
        return createMsg()
                .setParam(AI_P_SKILLNAME, skillName)
                .setParam(AI_P_SKILLDESCRIPTION, effectiveSkillDescription())
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
        return TLFunctionDefinition.fromSkill(skillName, effectiveSkillDescription(), parameterSchema);
    }

    // ======================== 工具方法 ========================

    /** 安全字符串化：null → def；String 原样；非字符串（Gson 数字为 Double、布尔）→ String.valueOf。
     *  普通 (String) 强转遇非字符串会 CCE——若发生在 execute 的 try 之外，会逃出技能层
     *  经框架异常链（无 exceptionHandler 时 moduleFactory.shutdown(-1)）终止整个应用。
     *  注：数字经 String.valueOf 得 "42.0"（Gson Double），如需 JSON 原文保真另议。 */
    protected static String asString(Object raw, String def) {
        if (raw == null) return def;
        if (raw instanceof String) return (String) raw;
        return String.valueOf(raw);
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
