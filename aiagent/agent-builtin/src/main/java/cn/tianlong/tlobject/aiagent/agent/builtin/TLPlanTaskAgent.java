package cn.tianlong.tlobject.aiagent.agent.builtin;

import cn.tianlong.tlobject.aiagent.TLAiAgent;
import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 任务分解 Agent —— 将复杂需求拆解为可执行的子任务清单。
 * <p>
 * 作为主 Agent 的子 Agent 运行，通过 {@code planTask(task="...")} 被调用。
 * 继承 {@link TLAiAgent}，原生享受 Provider 管理、上下文、会话等框架能力，
 * 不再需要 {@code resolveLlmProvider()} hack。
 * </p>
 *
 * <h3>调用流程</h3>
 * <ol>
 *   <li>主 Agent 的 LLM 调用 {@code planTask(task="...")}</li>
 *   <li>ToolExecutor 发送 SKILL_EXECUTE → TLAiAgent.executeAsTool()</li>
 *   <li>内部走 chat() → doChat() → LLM completion（使用配置的 Provider）</li>
 *   <li>系统提示词从 {@code md/planTask.md} 自动加载（loadAgentMd）</li>
 *   <li>返回 checklist + 文件落地</li>
 * </ol>
 *
 * <h3>XML 配置示例</h3>
 * <pre>
 * &lt;agent name="planTask" sameClassAs="planTask" statup="true"
 *        defaultLlmProvider="aiagent_master:openAiProvider"
 *        maxToolCallIterations="1"
 *        defaultTemperature="0.3"
 *        defaultMaxTokens="2048"
 *        description="将复杂任务分解为可执行的子任务清单"/&gt;
 * </pre>
 *
 * @author tianlong
 * @since 2026/8/12
 */
public class TLPlanTaskAgent extends TLAiAgent {

    /** 计划文件输出目录 */
    private String planOutputDir = "data/task_plans/";

    public TLPlanTaskAgent() { super(); }
    public TLPlanTaskAgent(String name) { super(name); }
    public TLPlanTaskAgent(String name, TLObjectFactory factory) { super(name, factory); }

    // ======================== 生命周期 ========================

    @Override
    protected void setModuleParams() {
        if (agentDescription == null || agentDescription.isEmpty())
            agentDescription = "将复杂任务分解为可执行的子任务清单。"
                    + "输入任务描述，返回带进度标记 [ ] 的结构化清单。"
                    + "适用于多步骤、需多种工具协作的复杂任务。";

        super.setModuleParams();

        Map<String, String> p = (params != null && !params.isEmpty()) ? params
                : (mconfig != null ? mconfig.getParams() : null);
        if (p != null) {
            if (p.get("planOutputDir") != null)
                planOutputDir = p.get("planOutputDir");
        }

        putLog("TLPlanTaskAgent [" + name + "] initialized, planOutputDir=" + planOutputDir,
                LogLevel.DEBUG);
    }

    // ======================== Tool 执行入口 ========================

    /**
     * 重写父类的 executeAsTool，在 LLM 返回 checklist 后追加文件落盘。
     */
    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg executeAsTool(Object fromWho, TLMsg msg) {
        Map<String, Object> args = (Map<String, Object>) msg.getParam(AI_P_SKILLINPUT, Map.class);
        String task = null;
        if (args != null && args.containsKey("task")) {
            task = args.get("task") != null ? args.get("task").toString() : "";
        }
        if (task == null || task.isEmpty()) {
            task = msg.getStringParam(AI_P_USERMESSAGE, "");
        }

        TLMsg result = super.executeAsTool(fromWho, msg);
        String checklist = result.getStringParam(AI_P_SKILLOUTPUT, "");

        if (checklist != null && !checklist.isEmpty()) {
            String planFile = savePlanToFile(task, checklist);
            if (planFile != null) {
                result.setParam(AI_P_SKILLOUTPUT,
                        checklist + "\n\n---\n计划已保存: " + planFile);
                result.setParam("planFile", planFile);
            }
        }

        return result;
    }

    // ======================== 文件输出 ========================

    private String savePlanToFile(String task, String checklist) {
        try {
            Path dir = Paths.get(planOutputDir);
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir);
            }

            String timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String filename = "plan_" + timestamp + ".md";
            Path filePath = dir.resolve(filename);

            StringBuilder sb = new StringBuilder();
            sb.append("# 任务分解计划\n\n");
            sb.append("**时间**: ").append(LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");
            sb.append("**任务**: ").append(task).append("\n\n");
            sb.append("---\n\n");
            sb.append(checklist);

            Files.write(filePath, sb.toString().getBytes(StandardCharsets.UTF_8));
            putLog("PlanTaskAgent saved plan to: " + filePath, LogLevel.INFO);
            return filePath.toAbsolutePath().toString();
        } catch (Exception e) {
            putLog("PlanTaskAgent failed to save plan file: " + e.getMessage(), LogLevel.WARN);
            return null;
        }
    }
}
