package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 外部脚本执行 Skill。允许 LLM 调用 Python / Shell / 任意解释器脚本。
 * 函数名: script_execution
 *
 * 与 TLCodeExecutionSkill 的区别：
 * - TLCodeExecutionSkill 执行内联代码片段
 * - TLScriptExecutionSkill 执行已有的外部脚本文件（如 .py / .sh）
 *
 * 配置参数（XML params）：
 * - interpreter: 解释器命令，默认 "python"
 * - allowedScriptDir: 允许的脚本目录，默认 "."（当前工作目录）
 * - maxExecutionTime: 超时秒数，默认 60
 * - maxOutputSize: 最大输出字节数，默认 1MB
 *
 * 创建日期：2026/7/7
 * 作者:tianlong
 */
public class TLScriptExecutionSkill extends TLBaseSkill {

    /** 解释器命令（如 python, python3, bash, node） */
    private String interpreter = "python";

    /** 允许的脚本目录（配置值，相对路径基于 configDir） */
    private String allowedScriptDir = ".";

    /** 相对于 configDir 解析脚本目录为绝对路径 */
    private String resolveScriptDir() {
        java.nio.file.Path p = java.nio.file.Paths.get(allowedScriptDir);
        if (p.isAbsolute()) return p.normalize().toString();
        String configDir = moduleFactory != null ? moduleFactory.getConfigDir() : ".";
        return java.nio.file.Paths.get(configDir, allowedScriptDir).normalize().toAbsolutePath().toString();
    }

    /** 最大执行时间（秒） */
    private int maxExecutionTime = 60;

    /** 最大输出大小 */
    private int maxOutputSize = 1024 * 1024; // 1MB

    public TLScriptExecutionSkill() { super(); }
    public TLScriptExecutionSkill(String name) { super(name); }
    public TLScriptExecutionSkill(String name, TLObjectFactory factory) { super(name, factory); }

    @Override
    protected void setModuleParams() {
        // 先读脚本相关参数：super.setModuleParams() 会触发 loadSkillMd()，
        // 需要 allowedScriptDir 已就绪才能自动发现 SKILL.md
        if (params != null) {
            if (params.get("interpreter") != null)
                interpreter = params.get("interpreter");
            if (params.get("allowedScriptDir") != null)
                allowedScriptDir = params.get("allowedScriptDir");
            if (params.get("maxExecutionTime") != null) {
                try { maxExecutionTime = Integer.parseInt(params.get("maxExecutionTime")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("maxOutputSize") != null) {
                try { maxOutputSize = Integer.parseInt(params.get("maxOutputSize")); }
                catch (NumberFormatException ignored) {}
            }
        }

        super.setModuleParams();

        if (skillName == null || skillName.isEmpty())
            skillName = "script_execution";
        if (skillDescription == null || skillDescription.isEmpty())
            skillDescription = "Execute an existing script file (.py, .sh, .js, etc.) and return its output. IMPORTANT: script_path must be a relative filename (e.g. 'process.py'), NOT a shell command.";

        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();
            Map<String, Object> pathProp = new LinkedHashMap<>();
            pathProp.put("type", "string");
            pathProp.put("description", "Script file name or relative path (e.g. 'process.py', 'scripts/run.sh'), NOT a shell command like 'dir' or 'ls'");
            pathProp.put("required", true);
            parameterSchema.put("script_path", pathProp);

            Map<String, Object> argsProp = new LinkedHashMap<>();
            argsProp.put("type", "string");
            argsProp.put("description", "Optional arguments to pass to the script (space-separated), e.g. '--months 1-5'");
            parameterSchema.put("arguments", argsProp);
        }

        // 把可用脚本清单直接写进 description，LLM 无需用 dir/ls 探索目录（contains 守卫幂等）
        String scriptsHint = availableScriptsHint();
        if (!scriptsHint.isEmpty() && !skillDescription.contains(scriptsHint))
            skillDescription = skillDescription + "\n\n" + scriptsHint;
    }

    /** 列出 allowedScriptDir 下的可用脚本文件，供 description 与错误提示引用 */
    private String availableScriptsHint() {
        try {
            Path dir = Paths.get(resolveScriptDir());
            if (!Files.isDirectory(dir)) return "";
            List<String> scripts = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path p : stream) {
                    String fn = p.getFileName().toString();
                    if (Files.isRegularFile(p) && fn.matches(".*\\.(py|sh|js|bat|cmd|ps1)$"))
                        scripts.add(fn);
                }
            }
            if (scripts.isEmpty()) return "";
            Collections.sort(scripts);
            if (scripts.size() > 20)
                scripts = scripts.subList(0, 20);
            return "Available scripts (pass file name as script_path): " + String.join(", ", scripts);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 扩展 SKILL.md 发现：在 allowedScriptDir 父目录查找。
     * 脚本 skill 通常不放在 classpath 中，需要在部署目录查找。
     */
    @Override
    protected void loadSkillMd() {
        // 显式配置优先，未配则在 allowedScriptDir 父目录自动发现
        if (skillMdPath == null && allowedScriptDir != null && !allowedScriptDir.isEmpty()) {
            try {
                java.nio.file.Path dir = java.nio.file.Paths.get(resolveScriptDir());
                java.nio.file.Path parent = dir.getParent();
                if (parent != null) {
                    // {skillName}.md 优先
                    java.nio.file.Path namedMd = parent.resolve(skillName + ".md");
                    if (java.nio.file.Files.exists(namedMd)) {
                        skillMdPath = namedMd.toString();
                    } else {
                        // 兜底 SKILL.md
                        java.nio.file.Path skillMd = parent.resolve("SKILL.md");
                        if (java.nio.file.Files.exists(skillMd)) {
                            skillMdPath = skillMd.toString();
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        super.loadSkillMd();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        Map<String, Object> input = (Map<String, Object>) msg.getMapParam(AI_P_SKILLINPUT, new LinkedHashMap<>());
        if (input.isEmpty()) {
            input = new LinkedHashMap<>();
            if (msg.containsParam("script_path"))
                input.put("script_path", msg.getStringParam("script_path", ""));
            if (msg.containsParam("arguments"))
                input.put("arguments", msg.getStringParam("arguments", ""));
        }

        String scriptPath = (String) input.getOrDefault("script_path", "");
        String arguments = (String) input.getOrDefault("arguments", "");

        if (scriptPath.isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error: script_path is required");
        }

        // LLM 常见误用：把 shell 命令当 script_path 传入（含空格/引号必非合法脚本名），
        // 提前拦截并回可用脚本清单，让 LLM 下一轮自纠，而不是 InvalidPathException 干耗迭代
        if (scriptPath.contains(" ") || scriptPath.contains("\"") || scriptPath.contains("'")) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error: script_path must be a script file name, NOT a shell command. "
                            + availableScriptsHint());
        }

        try {
            // 安全检查：解析脚本路径，确保在 allowedScriptDir 内
            Path allowedRoot = Paths.get(resolveScriptDir());
            Path resolvedScript = allowedRoot.resolve(scriptPath).normalize().toAbsolutePath();

            if (!resolvedScript.startsWith(allowedRoot)) {
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "Error: script path is outside allowed directory: " + allowedRoot);
            }

            if (!Files.exists(resolvedScript)) {
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "Error: script not found: " + resolvedScript
                                + ". " + availableScriptsHint());
            }

            if (!Files.isRegularFile(resolvedScript)) {
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "Error: not a file: " + resolvedScript);
            }

            return executeScript(resolvedScript, arguments);

        } catch (InvalidPathException e) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error: invalid script_path '" + scriptPath
                            + "'. script_path must be a script file name. " + availableScriptsHint());
        } catch (Exception e) {
            putLog("Script Execution error: " + e.toString(), LogLevel.ERROR);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Execution error: " + e.getMessage());
        }
    }

    /**
     * 通过子进程执行脚本文件
     */
    private TLMsg executeScript(Path scriptPath, String arguments) {
        try {
            // 构建命令：interpreter + script + args
            List<String> cmdList = new ArrayList<>();
            cmdList.add(interpreter);
            cmdList.add(scriptPath.toString());
            if (arguments != null && !arguments.trim().isEmpty()) {
                cmdList.addAll(Arrays.asList(arguments.trim().split("\\s+")));
            }

            ProcessBuilder pb = new ProcessBuilder(cmdList);
            pb.redirectErrorStream(true); // stderr 合并到 stdout

            putLog("Executing script: " + String.join(" ", cmdList), LogLevel.DEBUG);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

                boolean finished = process.waitFor(maxExecutionTime, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return createMsg().setParam(RESULT, false)
                            .setParam(AI_P_SKILLOUTPUT, "Script execution timeout (" + maxExecutionTime + "s)");
                }

                String line;
                int totalBytes = 0;
                while ((line = reader.readLine()) != null) {
                    totalBytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
                    if (totalBytes > maxOutputSize) {
                        output.append("\n...(output truncated)");
                        break;
                    }
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.exitValue();

            String result = output.length() > 0 ? output.toString() : "(no output)";
            putLog("Script exited with code: " + exitCode, LogLevel.DEBUG);

            TLMsg resultMsg = createMsg()
                    .setParam(RESULT, exitCode == 0)
                    .setParam(AI_P_SKILLOUTPUT, result)
                    .setParam("exitCode", exitCode)
                    .setParam("script", scriptPath.toString());

            if (exitCode != 0) {
                resultMsg.setParam(AI_P_SKILLOUTPUT,
                        "Script failed with exit code " + exitCode + "\n" + result);
            }

            return resultMsg;

        } catch (IOException e) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Script execution failed: " + e.getMessage()
                            + "\nMake sure '" + interpreter + "' is installed and available in PATH.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Execution interrupted");
        }
    }
}
