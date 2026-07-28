package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.aiagent.TLConversationHistory;
import cn.tianlong.tlobject.aiagent.TLMdFileLoader;
import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务分解 Skill —— 将复杂需求拆解为可执行的子任务清单。
 *
 * 与 TLDagPlanner 的关键区别：
 * - 输出是纯文本 checklist，不是 DAG/YAML/MD 文件
 * - 不缓存（计划存在于对话上下文中）
 * - 不执行 workflow（agent 在 ReAct 循环中逐项执行清单）
 * - 轻量：单次 LLM 调用，~170 行 vs DAG Planner 的 1100 行
 *
 * @author tianlong
 * @since 2026/7/28
 */
public class TLPlanTaskSkill extends TLBaseSkill {

    /** LLM Provider 模块名（默认 openAiProvider，可配置为 agentName:providerName 借用模式） */
    private String plannerProvider = "openAiProvider";

    /** 规划用 model（null 则使用 provider 默认值） */
    private String plannerModel;

    /** 规划用 temperature（默认 0.3，保持分解稳定性） */
    private double planTemperature = 0.3;

    /** 规划最大 token 数（2048 足够覆盖 3-7 项清单） */
    private int planMaxTokens = 2048;

    /** 计划文件输出目录 */
    private String planOutputDir = "data/task_plans/";

    /** 规划系统提示词（从 md 文件加载） */
    private String basePlanningPrompt;

    /** LLM Provider 引用 */
    private IObject llmProvider;

    public TLPlanTaskSkill() { super(); }
    public TLPlanTaskSkill(String name) { super(name); }
    public TLPlanTaskSkill(String name, TLObjectFactory factory) { super(name, factory); }

    @Override
    protected void setModuleParams() {
        // skillName 设为 LLM 友好的名称（带下划线），需在 super 之前设置
        // 否则 loadSkillMd() 会用模块名 "planTask" 找不到 skillmd/plan_task.md
        if (skillName == null || skillName.isEmpty())
            skillName = "plan_task";
        super.setModuleParams();

        // skillDescription 默认值（XML 未配置时使用）
        if (skillDescription == null || skillDescription.isEmpty())
            skillDescription = "将复杂任务分解为可执行的子任务清单。"
                    + "输入任务描述，返回带进度标记 [ ] 的结构化清单。"
                    + "适用于多步骤、需多种工具协作的复杂任务。";

        // 参数 schema 默认值
        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();
            Map<String, Object> taskProp = new LinkedHashMap<>();
            taskProp.put("type", "string");
            taskProp.put("description", "需要分解的复杂任务描述");
            taskProp.put("required", true);
            parameterSchema.put("task", taskProp);
        }

        // 读取可配置参数
        Map<String, String> p = (params != null && !params.isEmpty()) ? params
                : (mconfig != null ? mconfig.getParams() : null);
        if (p != null) {
            if (p.get("plannerProvider") != null)
                plannerProvider = p.get("plannerProvider");
            if (p.get("plannerModel") != null)
                plannerModel = p.get("plannerModel");
            if (p.get("planTemperature") != null)
                planTemperature = Double.parseDouble(p.get("planTemperature"));
            if (p.get("planMaxTokens") != null)
                planMaxTokens = Integer.parseInt(p.get("planMaxTokens"));
            if (p.get("planOutputDir") != null)
                planOutputDir = p.get("planOutputDir");
        }
    }

    @Override
    public void runStartMsg() {
        super.runStartMsg();
        llmProvider = resolveLlmProvider(plannerProvider);
        if (llmProvider == null) {
            putLog("PlanTaskSkill [" + name + "] cannot find LLM provider: "
                    + plannerProvider, LogLevel.ERROR);
        } else {
            putLog("PlanTaskSkill [" + name + "] provider=" + plannerProvider
                    + " model=" + plannerModel, LogLevel.INFO);
        }
        loadPlannerMd();
    }

    /**
     * 借用 LLM Provider。支持两种模式：
     * 1. "agentName:providerName" → 从指定 agent 借用
     * 2. "providerName" → 从工厂直接获取
     */
    private IObject resolveLlmProvider(String providerRef) {
        if (providerRef == null || providerRef.isEmpty()) return null;

        int colonIdx = providerRef.indexOf(':');
        if (colonIdx > 0) {
            String agentName = providerRef.substring(0, colonIdx);
            String provName = providerRef.substring(colonIdx + 1);

            TLMsg provResult = putMsg(agentName,
                    createMsg().setAction("getLlmProvider")
                            .setParam("provName", provName));
            Object ref = provResult != null ? provResult.getParam("provider") : null;
            if (ref instanceof IObject) {
                putLog("PlanTaskSkill: provider borrowed from " + providerRef, LogLevel.DEBUG);
                return (IObject) ref;
            }
            putLog("PlanTaskSkill: failed to borrow provider from " + providerRef, LogLevel.WARN);
            return null;
        }

        // 直接 provider 名 → 从工厂取
        TLMsg getMsg = createMsg().setAction("getModule")
                .setParam("moduleName", providerRef);
        TLMsg result = putMsg("moduleFactory", getMsg);
        if (result != null && result.getParam(INSTANCE) instanceof IObject) {
            return (IObject) result.getParam(INSTANCE);
        }
        return null;
    }

    /**
     * 加载规划提示词 md 文件。
     * 查找顺序：{configDir}md/{name}.md → classpath
     */
    protected void loadPlannerMd() {
        if (basePlanningPrompt != null && !basePlanningPrompt.trim().isEmpty()) return;

        String content = TLMdFileLoader.readFileOrResource(
                moduleFactory.getConfigDir() + "md/" + name + ".md", this.getClass());
        if (content == null || content.trim().isEmpty()) {
            putLog("PlanTaskSkill [" + name + "] no md prompt found, using built-in default",
                    LogLevel.DEBUG);
            return;
        }

        String body = TLMdFileLoader.parseBody(content);
        if (body != null && !body.isEmpty()) {
            basePlanningPrompt = body;
            putLog("PlanTaskSkill [" + name + "] prompt loaded from md ("
                    + body.length() + " chars)", LogLevel.DEBUG);
        }
    }

    // ======================== Skill 接口实现 ========================

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        Map<String, Object> input = msg.getMapParam(AI_P_SKILLINPUT, new LinkedHashMap<>());
        String task = input.getOrDefault("task", "").toString();
        if (task.isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "缺少必填参数 task（需要分解的任务描述）");
        }

        TLMsg result = doPlan(task);
        boolean ok = result.parseBoolean(RESULT, false);
        TLMsg ret = createMsg().setParam(RESULT, ok);
        if (ok) {
            String checklist = result.getStringParam(AI_P_RESPONSE, "");

            // 计划落盘：data/task_plans/plan_{timestamp}.md
            String planFile = savePlanToFile(task, checklist);

            StringBuilder output = new StringBuilder();
            output.append(checklist);
            if (planFile != null) {
                output.append("\n\n---\n计划已保存: ").append(planFile);
                ret.setParam("planFile", planFile);
            }
            ret.setParam(AI_P_SKILLOUTPUT, output.toString());
        } else {
            ret.setParam(AI_P_SKILLOUTPUT,
                    "任务分解失败: " + result.getStringParam("error", "unknown error"));
        }
        return ret;
    }

    // ======================== 核心逻辑 ========================

    /**
     * 调用 LLM 将任务分解为子任务清单。
     * @param task 用户任务描述
     * @return RESULT=true + AI_P_RESPONSE=checklist 文本
     */
    private TLMsg doPlan(String task) {
        if (llmProvider == null) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "LLM provider not available. "
                            + "请配置 plannerProvider 参数（如 aiagent_master:openAiProvider）");
        }

        // 构建 system prompt
        StringBuilder systemPrompt = new StringBuilder();
        if (basePlanningPrompt != null && !basePlanningPrompt.trim().isEmpty()) {
            systemPrompt.append(basePlanningPrompt.trim());
        } else {
            // 内置兜底提示词
            systemPrompt.append("你是一个任务分解专家。请将用户需求分解为可执行的子任务清单。\n");
            systemPrompt.append("每个子任务一行，格式: N. [ ] 子任务描述\n");
            systemPrompt.append("数量控制在3-7个，按执行顺序排列，使用中文。\n");
            systemPrompt.append("只输出清单，不要额外说明。");
        }

        // 构建消息历史
        String userMessage = "请将以下任务分解为子任务清单：\n" + task;

        List<TLConversationHistory> history = new ArrayList<>();
        history.add(new TLConversationHistory(
                TLConversationHistory.Role.system, systemPrompt.toString()));
        history.add(new TLConversationHistory(
                TLConversationHistory.Role.user, userMessage));

        // 调用 LLM
        TLMsg llmMsg = createMsg()
                .setAction(LLM_COMPLETION)
                .setParam(AI_P_MESSAGEHISTORY, history)
                .setParam(AI_P_TEMPERATURE, planTemperature)
                .setParam(AI_P_MAXTOKENS, planMaxTokens);
        if (plannerModel != null && !plannerModel.isEmpty()) {
            llmMsg.setParam(AI_P_MODEL, plannerModel);
        }

        putLog("PlanTaskSkill calling LLM for task: "
                + (task.length() > 60 ? task.substring(0, 60) + "..." : task), LogLevel.DEBUG);

        TLMsg llmResult = putMsg(llmProvider, llmMsg);
        if (llmResult == null || !llmResult.parseBoolean(RESULT, false)) {
            String err = llmResult != null
                    ? llmResult.getStringParam(AI_P_RESPONSEBODY, "unknown error")
                    : "null response from LLM provider";
            putLog("PlanTaskSkill LLM call failed: " + err, LogLevel.WARN);
            return createMsg().setParam(RESULT, false).setParam("error", err);
        }

        String checklist = llmResult.getStringParam(AI_P_RESPONSE, "");
        putLog("PlanTaskSkill got checklist (" + checklist.length() + " chars)", LogLevel.DEBUG);
        return createMsg().setParam(RESULT, true).setParam(AI_P_RESPONSE, checklist);
    }

    // ======================== 文件输出 ========================

    /**
     * 将计划保存为 Markdown 文件。
     * @return 文件绝对路径，失败返回 null
     */
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
            putLog("PlanTaskSkill saved plan to: " + filePath, LogLevel.INFO);
            return filePath.toAbsolutePath().toString();
        } catch (Exception e) {
            putLog("PlanTaskSkill failed to save plan file: " + e.getMessage(), LogLevel.WARN);
            return null;
        }
    }
}
