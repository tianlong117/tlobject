package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 代码执行Skill。允许LLM在沙箱环境中执行代码片段。
 * 函数名: code_execution
 * 支持Python (需安装) 和 JavaScript (内建Nashorn/GraalVM)。
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public class TLCodeExecutionSkill extends TLBaseSkill {

    /** 最大执行时间（秒） */
    private int maxExecutionTime = 30;
    /** 最大输出大小 */
    private int maxOutputSize = 1024 * 100; // 100KB

    public TLCodeExecutionSkill() {
        super();
    }

    public TLCodeExecutionSkill(String name) {
        super(name);
    }

    public TLCodeExecutionSkill(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (skillName == null || skillName.isEmpty())
            skillName = "code_execution";
        if (skillDescription == null || skillDescription.isEmpty())
            skillDescription = "Execute code snippets in a sandboxed environment. Supports Python (via python3 command) and JavaScript (via built-in ScriptEngine).";

        if (params != null) {
            if (params.get("maxExecutionTime") != null) {
                try { maxExecutionTime = Integer.parseInt(params.get("maxExecutionTime")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("maxOutputSize") != null) {
                try { maxOutputSize = Integer.parseInt(params.get("maxOutputSize")); }
                catch (NumberFormatException ignored) {}
            }
        }

        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();
            Map<String, Object> langProp = new LinkedHashMap<>();
            langProp.put("type", "string");
            langProp.put("description", "Programming language: python or javascript");
            langProp.put("enum", new String[]{"python", "javascript"});
            langProp.put("required", true);
            parameterSchema.put("language", langProp);

            Map<String, Object> codeProp = new LinkedHashMap<>();
            codeProp.put("type", "string");
            codeProp.put("description", "The code to execute");
            codeProp.put("required", true);
            parameterSchema.put("code", codeProp);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        Map<String, Object> input = (Map<String, Object>) msg.getMapParam(AI_P_SKILLINPUT, new LinkedHashMap<>());
        if (input.isEmpty()) {
            input = new LinkedHashMap<>();
            if (msg.containsParam("language")) input.put("language", msg.getStringParam("language", "python"));
            if (msg.containsParam("code")) input.put("code", msg.getStringParam("code", ""));
        }

        String language = (String) input.getOrDefault("language", "python");
        String code = (String) input.getOrDefault("code", "");

        if (code.isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error: code is required");
        }

        try {
            switch (language.toLowerCase()) {
                case "python":
                    return executePython(code);
                case "javascript":
                case "js":
                    return executeJavaScript(code);
                default:
                    return createMsg().setParam(RESULT, false)
                            .setParam(AI_P_SKILLOUTPUT, "Unsupported language: " + language);
            }
        } catch (Exception e) {
            putLog("Code Execution error: " + e.toString(), LogLevel.ERROR);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Execution error: " + e.getMessage());
        }
    }

    /**
     * 查找可用的Python命令（优先python3，不可用时回退python）
     */
    private String findPythonCommand() {
        // 先尝试python3
        try {
            ProcessBuilder pb = new ProcessBuilder("python3", "--version");
            Process p = pb.start();
            if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                return "python3";
            }
        } catch (Exception ignored) {}
        // 回退到python（Windows通常用python）
        return "python";
    }

    /**
     * 通过子进程执行Python代码
     */
    private TLMsg executePython(String code) {
        try {
            // 将代码写入临时文件
            File tempFile = File.createTempFile("tl_ai_py_", ".py");
            try (FileWriter writer = new FileWriter(tempFile)) {
                writer.write(code);
            }

            // 兼容Windows (python) 和 Linux/Mac (python3)
            String pythonCmd = findPythonCommand();
            ProcessBuilder pb = new ProcessBuilder(pythonCmd, tempFile.getAbsolutePath());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            // 读取输出（带超时）
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                boolean finished = process.waitFor(maxExecutionTime, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    tempFile.delete();
                    return createMsg().setParam(RESULT, false)
                            .setParam(AI_P_SKILLOUTPUT, "Execution timeout (" + maxExecutionTime + "s)");
                }

                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() + line.length() > maxOutputSize) {
                        output.append("\n...(output truncated)");
                        break;
                    }
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.exitValue();
            tempFile.delete();

            String result = output.length() > 0 ? output.toString() : "(no output)";
            putLog("Python executed, exit code: " + exitCode, LogLevel.DEBUG);

            return createMsg().setParam(RESULT, exitCode == 0)
                    .setParam(AI_P_SKILLOUTPUT, "Exit code: " + exitCode + "\n" + result)
                    .setParam("exitCode", exitCode);
        } catch (IOException e) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Python execution failed: " + e.getMessage()
                            + "\nMake sure python or python3 is installed and available in PATH.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Execution interrupted");
        }
    }

    /**
     * 通过Java ScriptEngine执行JavaScript
     */
    private TLMsg executeJavaScript(String code) {
        try {
            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByName("JavaScript");

            if (engine == null) {
                // 尝试GraalJS
                engine = manager.getEngineByName("graal.js");
            }
            if (engine == null) {
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "JavaScript engine not available. Install GraalVM or Nashorn.");
            }

            // 捕获stdout
            StringWriter outputWriter = new StringWriter();
            engine.getContext().setWriter(outputWriter);
            engine.getContext().setErrorWriter(outputWriter);

            Object evalResult = engine.eval(code);
            String output = outputWriter.toString();

            StringBuilder result = new StringBuilder();
            if (!output.isEmpty()) {
                result.append(output).append("\n");
            }
            if (evalResult != null) {
                result.append("=> ").append(evalResult.toString());
            }
            if (result.length() == 0) {
                result.append("(no output)");
            }

            return createMsg().setParam(RESULT, true)
                    .setParam(AI_P_SKILLOUTPUT, result.toString());
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "JavaScript error: " + e.getMessage());
        }
    }
}
