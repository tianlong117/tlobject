package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.aiagent.TLProcessRegistry;
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

        // asString（非 (String) 强转）：LLM 发数字/布尔时 Gson 解析为 Double/Boolean，强转 CCE 会逃出
        // execute 经框架异常链（无 exceptionHandler 时 moduleFactory.shutdown(-1)）终止整个应用
        String language = asString(input.getOrDefault("language", "python"), "python");
        String code = asString(input.getOrDefault("code", ""), "");

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

    /** 探测结果缓存：每次执行都起一个 `python3 --version` 子进程是纯开销（几十~几百 ms） */
    private volatile String cachedPythonCommand = null;

    /**
     * 查找可用的Python命令（优先python3，不可用时回退python），结果缓存。
     */
    private String findPythonCommand() {
        String cached = cachedPythonCommand;
        if (cached != null) return cached;
        synchronized (this) {
            if (cachedPythonCommand != null) return cachedPythonCommand;
            String cmd = "python";
            // 先尝试python3
            try {
                Process p = new ProcessBuilder("python3", "--version").start();
                if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    cmd = "python3";
                } else {
                    p.destroyForcibly();   // 探测失败/超时：别留下僵进程
                }
            } catch (Exception ignored) {}
            // 回退到python（Windows通常用python）
            cachedPythonCommand = cmd;
            return cmd;
        }
    }

    /**
     * 通过子进程执行Python代码
     */
    private TLMsg executePython(String code) {
        File tempFile = null;
        try {
            // 将代码写入临时文件
            tempFile = File.createTempFile("tl_ai_py_", ".py");
            try (FileWriter writer = new FileWriter(tempFile)) {
                writer.write(code);
            }

            // 兼容Windows (python) 和 Linux/Mac (python3)
            String pythonCmd = findPythonCommand();
            ProcessBuilder pb = new ProcessBuilder(pythonCmd, tempFile.getAbsolutePath());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            Thread owner = Thread.currentThread();
            TLProcessRegistry.register(owner, process);   // C：登记进程，供 stopChat 强杀
            final StringBuilder output = new StringBuilder();
            final boolean[] truncated = {false};

            // 必须边跑边读：输出超过管道缓冲（Windows 64KB）时子进程会阻塞在 print()，
            // 先 waitFor 再读会等不到进程退出——脚本早就打印完了也要等到超时，输出全丢。
            // （ScriptExecution / Browser 都有 drainer，这里此前漏了）
            Thread drainer = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        boolean over;
                        synchronized (output) {
                            over = output.length() + line.length() > maxOutputSize;
                            if (!over) output.append(line).append("\n");
                        }
                        if (over) truncated[0] = true;   // 继续排空管道，不能让子进程阻塞
                    }
                } catch (IOException ignored) {
                }
            }, "py-exec-drain");
            drainer.setDaemon(true);
            drainer.start();

            try {
                boolean finished = process.waitFor(maxExecutionTime, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();   // 先杀再收尾：drainer 读到 EOF 才能退出
                    drainer.join(1000);
                    return createMsg().setParam(RESULT, false)
                            .setParam(AI_P_SKILLOUTPUT, "Execution timeout (" + maxExecutionTime + "s)");
                }
                // 进程已退出：drainer 读到 EOF 即结束，join 保证输出完整
                drainer.join(2000);

                int exitCode = process.exitValue();
                String result;
                synchronized (output) {
                    result = output.length() > 0 ? output.toString() : "(no output)";
                }
                if (truncated[0]) result += "\n...(output truncated)";
                putLog("Python executed, exit code: " + exitCode, LogLevel.DEBUG);

                return createMsg().setParam(RESULT, exitCode == 0)
                        .setParam(AI_P_SKILLOUTPUT, "Exit code: " + exitCode + "\n" + result)
                        .setParam("exitCode", exitCode);
            } finally {
                // 超时 / 被 /stop interrupt / 任何异常：进程仍存活则强杀，避免泄漏；再注销
                if (process.isAlive()) process.destroyForcibly();
                TLProcessRegistry.unregister(owner, process);
            }
        } catch (IOException e) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Python execution failed: " + e.getMessage()
                            + "\nMake sure python or python3 is installed and available in PATH.");
        } catch (InterruptedException e) {
            // 被 /stop 中断：进程已在上面的 finally 中强杀，这里只需复位中断标志并返回
            Thread.currentThread().interrupt();
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Execution interrupted");
        } finally {
            // 兜底删除：超时/中断/IO异常/建进程失败等所有路径都覆盖（原先只在成功与超时分支删）
            if (tempFile != null) {
                try { tempFile.delete(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 通过Java ScriptEngine执行JavaScript。
     * <p>每次新建 ScriptEngine 是**故意的**：JSR-223 的 ScriptEngine 非线程安全，
     * 池化/单例必须自己加锁（eval 会改引擎内部状态）。此处选择"慢但正确"，
     * 不要为了性能改成 shared 单例。</p>
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
