package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Skill 安装器 —— 将任意脚本安装为 aiagent 的脚本 Skill。
 *
 * 自动完成：建目录 → 拷脚本 → 生成 SKILL.md → 注册到 Agent
 * 底层复用 {@code TLAiAgent.hotLoadSkill()} 完成注册和持久化。
 *
 * 函数名: install_skill
 *
 * 输入参数:
 *   - script_path (必填): 要安装的脚本文件路径（绝对路径或相对路径）
 *   - skill_name (必填): 新 skill 的名称（kebab-case，如 "my-revenue-report"）
 *   - skill_description (可选): skill 的功能描述，LLM 据此决定何时调用
 *   - interpreter (可选): 脚本解释器，默认根据扩展名自动检测
 *   - max_execution_time (可选): 最大执行时间（秒），默认 120
 *
 * 输出: 安装结果信息（成功/失败、skill 名称、目录路径等）
 *
 * @author tianlong
 * @since 2026/7/29
 */
public class TLSkillInstallerSkill extends TLBaseSkill {

    /** 默认解释器 */
    private static final String DEFAULT_INTERPRETER = "python";

    /** 默认超时（秒） */
    private static final int DEFAULT_MAX_EXECUTION_TIME = 120;

    /** 支持的脚本扩展名 → 默认解释器映射 */
    private static final Map<String, String> EXT_INTERPRETER_MAP = new LinkedHashMap<>();
    static {
        EXT_INTERPRETER_MAP.put(".py", "python");
        EXT_INTERPRETER_MAP.put(".sh", "bash");
        EXT_INTERPRETER_MAP.put(".bash", "bash");
        EXT_INTERPRETER_MAP.put(".js", "node");
        EXT_INTERPRETER_MAP.put(".mjs", "node");
        EXT_INTERPRETER_MAP.put(".rb", "ruby");
        EXT_INTERPRETER_MAP.put(".pl", "perl");
        EXT_INTERPRETER_MAP.put(".ps1", "powershell");
        EXT_INTERPRETER_MAP.put(".bat", "cmd");
        EXT_INTERPRETER_MAP.put(".cmd", "cmd");
        EXT_INTERPRETER_MAP.put(".R", "Rscript");
        EXT_INTERPRETER_MAP.put(".lua", "lua");
    }

    /** 二进制/不可安装的扩展名 */
    private static final java.util.Set<String> BINARY_EXTENSIONS = new java.util.HashSet<>();
    static {
        BINARY_EXTENSIONS.add(".exe");
        BINARY_EXTENSIONS.add(".dll");
        BINARY_EXTENSIONS.add(".so");
        BINARY_EXTENSIONS.add(".class");
        BINARY_EXTENSIONS.add(".jar");
        BINARY_EXTENSIONS.add(".pyc");
        BINARY_EXTENSIONS.add(".pyo");
        BINARY_EXTENSIONS.add(".obj");
        BINARY_EXTENSIONS.add(".bin");
        BINARY_EXTENSIONS.add(".zip");
        BINARY_EXTENSIONS.add(".tar");
        BINARY_EXTENSIONS.add(".gz");
        BINARY_EXTENSIONS.add(".7z");
        BINARY_EXTENSIONS.add(".rar");
    }

    public TLSkillInstallerSkill() { super(); }
    public TLSkillInstallerSkill(String name) { super(name); }
    public TLSkillInstallerSkill(String name, TLObjectFactory factory) { super(name, factory); }

    @Override
    protected void setModuleParams() {
        if (skillName == null || skillName.isEmpty())
            skillName = "install_skill";
        super.setModuleParams();

        if (skillDescription == null || skillDescription.isEmpty())
            skillDescription = "将脚本文件安装为可复用的 AI Agent Skill。"
                    + "自动创建目录结构、复制脚本、生成说明文档并注册到 Agent。"
                    + "当用户想把某个脚本（Python/Shell/JS等）安装为 Skill 以便后续调用时使用。";

        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();

            Map<String, Object> pathProp = new LinkedHashMap<>();
            pathProp.put("type", "string");
            pathProp.put("description", "脚本文件的完整路径（如 D:/scripts/my_report.py 或 ./sort_excel.py）");
            pathProp.put("required", true);
            parameterSchema.put("script_path", pathProp);

            Map<String, Object> nameProp = new LinkedHashMap<>();
            nameProp.put("type", "string");
            nameProp.put("description", "新 Skill 的名称，kebab-case 格式（如 my-python-skill），用作目录名和模块名");
            nameProp.put("required", true);
            parameterSchema.put("skill_name", nameProp);

            Map<String, Object> descProp = new LinkedHashMap<>();
            descProp.put("type", "string");
            descProp.put("description", "Skill 的功能描述（可选）。说明脚本做什么、何时调用。如未提供则从脚本内容自动分析");
            parameterSchema.put("skill_description", descProp);

            Map<String, Object> interpProp = new LinkedHashMap<>();
            interpProp.put("type", "string");
            interpProp.put("description", "解释器命令（可选），如 python/bash/node。如未提供则根据脚本扩展名自动检测");
            parameterSchema.put("interpreter", interpProp);

            Map<String, Object> timeoutProp = new LinkedHashMap<>();
            timeoutProp.put("type", "integer");
            timeoutProp.put("description", "最大执行时间秒数（可选），默认 120");
            parameterSchema.put("max_execution_time", timeoutProp);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        // 1. 提取参数
        Map<String, Object> input = (Map<String, Object>) msg.getMapParam(AI_P_SKILLINPUT, new LinkedHashMap<>());
        if (input.isEmpty()) {
            input = new LinkedHashMap<>();
            if (msg.containsParam("script_path"))
                input.put("script_path", msg.getStringParam("script_path", ""));
            if (msg.containsParam("skill_name"))
                input.put("skill_name", msg.getStringParam("skill_name", ""));
            if (msg.containsParam("skill_description"))
                input.put("skill_description", msg.getStringParam("skill_description", ""));
            if (msg.containsParam("interpreter"))
                input.put("interpreter", msg.getStringParam("interpreter", ""));
            if (msg.containsParam("max_execution_time"))
                input.put("max_execution_time", msg.getIntParam("max_execution_time", DEFAULT_MAX_EXECUTION_TIME));
        }

        String scriptPath = (String) input.getOrDefault("script_path", "");
        String skillName = (String) input.getOrDefault("skill_name", "");
        String skillDescription = (String) input.getOrDefault("skill_description", "");
        String interpreter = (String) input.getOrDefault("interpreter", "");
        int maxExecutionTime = input.get("max_execution_time") instanceof Integer
                ? (Integer) input.get("max_execution_time")
                : DEFAULT_MAX_EXECUTION_TIME;

        // 2. 校验必填参数
        if (scriptPath.isEmpty()) {
            return fail("缺少必填参数: script_path（要安装的脚本文件路径）");
        }
        if (skillName.isEmpty()) {
            return fail("缺少必填参数: skill_name（新 Skill 的名称，kebab-case 格式）");
        }

        // 3. 校验 skill_name 格式（只允许小写字母、数字、连字符）
        if (!skillName.matches("^[a-z0-9][a-z0-9-]*[a-z0-9]$") && !skillName.matches("^[a-z0-9]$")) {
            return fail("skill_name 格式不合法: '" + skillName
                    + "'。只允许小写字母、数字、连字符，且不能以连字符开头或结尾。如 'my-python-skill'");
        }

        // 4. 校验脚本文件
        Path sourceScript = Paths.get(scriptPath);
        if (!Files.exists(sourceScript)) {
            return fail("脚本文件不存在: " + sourceScript.toAbsolutePath());
        }
        if (!Files.isRegularFile(sourceScript)) {
            return fail("路径不是文件: " + sourceScript.toAbsolutePath());
        }
        // 检查是否是二进制文件
        String fileName = sourceScript.getFileName().toString();
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')).toLowerCase() : "";
        if (BINARY_EXTENSIONS.contains(ext)) {
            return fail("不支持的文件类型: " + ext + "（二进制/编译文件不能安装为 Skill）");
        }

        // 5. 自动检测解释器
        if (interpreter.isEmpty()) {
            interpreter = EXT_INTERPRETER_MAP.getOrDefault(ext, DEFAULT_INTERPRETER);
            putLog("自动检测解释器: " + interpreter + " (扩展名: " + ext + ")", LogLevel.DEBUG);
        }

        // 6. 构建目标路径
        String configDir = moduleFactory != null ? moduleFactory.getConfigDir() : ".";
        // 修复 Windows 路径
        if (configDir.startsWith("CLASSPATH/"))
            configDir = configDir.substring("CLASSPATH/".length());
        else if (configDir.startsWith("CLASSPATH\\"))
            configDir = configDir.substring("CLASSPATH\\".length());
        if (configDir.startsWith("/") && configDir.length() > 3 && configDir.charAt(2) == ':')
            configDir = configDir.substring(1);
        configDir = configDir.replaceAll("[/\\\\]+", "/");

        Path skillsDir = Paths.get(configDir, "skills", skillName);
        Path scriptsDir = skillsDir.resolve("scripts");

        // 7. 检查是否已存在
        if (Files.exists(skillsDir)) {
            return fail("Skill 目录已存在: " + skillsDir.toAbsolutePath()
                    + "。如果要覆盖，请先手动删除或使用 /uninstall -s " + skillName);
        }

        // 8. 创建目录结构
        try {
            Files.createDirectories(scriptsDir);
            putLog("创建目录: " + scriptsDir.toAbsolutePath(), LogLevel.DEBUG);
        } catch (IOException e) {
            return fail("创建目录失败: " + e.getMessage());
        }

        // 9. 复制脚本文件
        Path destScript = scriptsDir.resolve(fileName);
        try {
            Files.copy(sourceScript, destScript, StandardCopyOption.REPLACE_EXISTING);
            putLog("复制脚本: " + sourceScript.toAbsolutePath() + " → " + destScript.toAbsolutePath(), LogLevel.DEBUG);
        } catch (IOException e) {
            // 清理已创建的目录
            try { Files.deleteIfExists(destScript); } catch (IOException ignored) {}
            try { Files.deleteIfExists(scriptsDir); } catch (IOException ignored) {}
            try { Files.deleteIfExists(skillsDir); } catch (IOException ignored) {}
            return fail("复制脚本失败: " + e.getMessage());
        }

        // 10. 生成 SKILL.md
        String skillMdContent = generateSkillMd(skillName, skillDescription, fileName, interpreter, maxExecutionTime);
        Path skillMdPath = skillsDir.resolve("SKILL.md");
        try {
            Files.write(skillMdPath, skillMdContent.getBytes(StandardCharsets.UTF_8));
            putLog("生成 SKILL.md: " + skillMdPath.toAbsolutePath(), LogLevel.DEBUG);
        } catch (IOException e) {
            return fail("生成 SKILL.md 失败: " + e.getMessage());
        }

        // 11. 发送 AGENT_HOTLOADSKILL 给父 Agent
        if (fromWho instanceof TLBaseModule) {
            TLBaseModule agent = (TLBaseModule) fromWho;
            TLMsg hotLoadMsg = createMsg()
                    .setAction(AGENT_HOTLOADSKILL)
                    .setParam("skillDir", skillName)
                    .setParam(AI_P_SKILLNAME, "script_execution")
                    .setParam("interpreter", interpreter)
                    .setParam("maxExecutionTime", maxExecutionTime)
                    .setParam(HOTLOAD_P_PERSIST, "true");
            hotLoadMsg.setSystemParam(IGNOREMODULEISNULL, true);

            TLMsg result = putMsg(agent, hotLoadMsg);
            if (result != null && result.parseBoolean(RESULT, false)) {
                String registeredModule = result.getStringParam(MODULENAME, skillName);
                putLog("Skill 安装成功: " + skillName + " (module=" + registeredModule + ")", LogLevel.INFO);
                return createMsg()
                        .setParam(RESULT, true)
                        .setParam(AI_P_SKILLOUTPUT, "✓ Skill 安装成功！\n"
                                + "名称: " + skillName + "\n"
                                + "解释器: " + interpreter + "\n"
                                + "脚本: skills/" + skillName + "/scripts/" + fileName + "\n"
                                + "SKILL.md: skills/" + skillName + "/SKILL.md\n"
                                + "现在可以通过 script_execution 工具调用此 Skill，"
                                + "script_path 参数使用 '" + fileName + "'。");
            } else {
                String err = result != null ? result.getStringParam("error", "Agent 无响应") : "Agent 无响应";
                // hotload 失败但不删除已创建的文件——用户可以手动 /install -s
                return createMsg()
                        .setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "⚠ 脚本文件已就绪，但注册到 Agent 失败: " + err + "\n"
                                + "请手动在控制台执行: /install -s " + skillName);
            }
        } else {
            // fromWho 不是 TLBaseModule，无法注册
            return createMsg()
                    .setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "⚠ 脚本文件已复制到 skills/" + skillName
                            + "/，但无法自动注册（未找到父 Agent）。\n"
                            + "请手动在控制台执行: /install -s " + skillName);
        }
    }

    /** 生成 SKILL.md 内容 */
    private String generateSkillMd(String skillName, String skillDescription,
                                    String scriptFileName, String interpreter, int maxExecutionTime) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(skillName).append("\n");
        sb.append("description: >-\n");
        if (skillDescription != null && !skillDescription.isEmpty()) {
            sb.append("  ").append(skillDescription).append("\n");
        } else {
            sb.append("  ").append("执行 ").append(scriptFileName).append(" 脚本。\n");
        }
        sb.append("  通过 script_execution 工具调用，script_path 为 '").append(scriptFileName).append("'。\n");
        sb.append("---\n\n");
        sb.append("# ").append(skillName).append("\n\n");
        sb.append("执行 `").append(scriptFileName).append("` 脚本。\n\n");

        if (skillDescription != null && !skillDescription.isEmpty()) {
            sb.append("## 功能描述\n\n");
            sb.append(skillDescription).append("\n\n");
        }

        sb.append("## 执行方式\n\n");
        sb.append("```bash\n");
        sb.append(interpreter).append(" <skill_dir>/scripts/").append(scriptFileName).append(" [选项]\n");
        sb.append("```\n\n");
        sb.append("- 解释器: `").append(interpreter).append("`\n");
        sb.append("- 超时: ").append(maxExecutionTime).append(" 秒\n\n");
        sb.append("## 依赖\n\n");
        sb.append("需要 ").append(interpreter).append(" 环境。\n");

        return sb.toString();
    }

    /** 快捷失败响应 */
    private TLMsg fail(String errorMsg) {
        putLog("SkillInstaller 失败: " + errorMsg, LogLevel.ERROR);
        return createMsg()
                .setParam(RESULT, false)
                .setParam(AI_P_SKILLOUTPUT, "✗ " + errorMsg);
    }
}
