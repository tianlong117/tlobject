package cn.tianlong.tlobject.aiagent.workflow;

import cn.tianlong.tlobject.aiagent.IAgentCapable;
import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.aiagent.TLConversationHistory;
import cn.tianlong.tlobject.aiagent.TLMdFileLoader;
import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLModuleConfig;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import org.xmlpull.v1.XmlPullParser;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * DAG 规划器 —— LLM 驱动的任务分解与工作流编排。
 *
 * 输入自然语言需求，LLM 分解子任务 → 识别依赖 → 编排 DAG → 生成 MD 任务文件。
 * 内置两层缓存：精确匹配（需求文本 hash）+ 语义匹配（轻量 LLM 判断）。
 *
 * 可用作独立模块或注册为子 Agent（实现 IAgentCapable）。
 *
 * @author tianlong
 * @since 2026/7/26
 */
public class TLDagPlanner extends TLBaseSkill
        implements IAgentCapable {

    /** LLM Provider 模块名（默认 openAiProvider） */
    private String plannerProvider = "openAiProvider";

    /** 规划用 model（默认 null，使用 provider 默认值） */
    private String plannerModel;

    /** 语义匹配用轻量 model（默认同 plannerModel） */
    private String plannerModelLight;

    /** MD 计划文件输出目录 */
    private String planOutputDir = "data/dag_plans/";

    /** 规划用 LLM Provider 引用（从主 agent 借用，与其他子 agent 同模式） */
    private IObject llmProvider;

    /** 规划用 temperature */
    private double planTemperature = 0.3;

    /** 最大规划 token 数 */
    private int planMaxTokens = 4096;

    /** 执行工作流的模块名（默认 agentWorkflow） */
    private String workflowModule = "agentWorkflow";

    /** 规划系统提示词模板（null 则用内置默认值） */
    private String basePlanningPrompt;

    /** 配置的可用 Agent 白名单（name → info） */
    private final Map<String, Map<String, Object>> configuredAgents = new LinkedHashMap<>();

    /** 配置的可用 Skill 白名单（name → description） */
    private final Map<String, String> configuredSkills = new LinkedHashMap<>();

    /** 是否从注册表动态发现 Agent（补充白名单），默认 false */
    private boolean discoverFromRegistry = false;

    public TLDagPlanner() { super(); }
    public TLDagPlanner(String name) { super(name); }
    public TLDagPlanner(String name, TLObjectFactory factory) { super(name, factory); }

    @Override
    protected Object setConfig() {
        myConfig config = new myConfig(configFile, moduleFactory.getConfigDir());
        mconfig = config;
        super.setConfig();
        return config;
    }

    @Override
    protected void setModuleParams() {
        // 先调用 TLBaseSkill.setModuleParams() 加载 skillName/skillDescription/parameterSchema
        super.setModuleParams();
        // skillName 由 super 默认设为 name（即模块名 "dagPlanner"），无需硬编码

        // skill 描述（XML 未配置时用默认值）
        if (skillDescription == null || skillDescription.isEmpty())
            skillDescription = "将复杂任务需求分解为子任务并编排为DAG工作流。"
                    + "接受自然语言需求描述，返回结构化的执行计划（MD文件+节点列表+边列表）。"
                    + "适用场景：用户需求涉及多步骤、多Agent协作的复杂任务。";

        // 参数 schema（XML 未配置时用默认值）
        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();
            Map<String, Object> reqProp = new LinkedHashMap<>();
            reqProp.put("type", "string");
            reqProp.put("description", "任务需求描述（自然语言）");
            reqProp.put("required", true);
            parameterSchema.put("requirement", reqProp);

            Map<String, Object> nameProp = new LinkedHashMap<>();
            nameProp.put("type", "string");
            nameProp.put("description", "计划名称（可选，不指定则自动生成）");
            parameterSchema.put("planName", nameProp);

            Map<String, Object> forceProp = new LinkedHashMap<>();
            forceProp.put("type", "boolean");
            forceProp.put("description", "是否强制重新生成，跳过缓存（可选，默认false）");
            parameterSchema.put("forceRegenerate", forceProp);
        }

        // 优先从 this.params 读，fallback 到 mconfig.params
        Map<String, String> p = (params != null && !params.isEmpty()) ? params
                : (mconfig != null ? mconfig.getParams() : null);
        if (p != null) {
            if (p.get("plannerProvider") != null)
                plannerProvider = p.get("plannerProvider");
            if (p.get("plannerModel") != null)
                plannerModel = p.get("plannerModel");
            if (p.get("plannerModelLight") != null)
                plannerModelLight = p.get("plannerModelLight");
            if (p.get("planOutputDir") != null)
                planOutputDir = p.get("planOutputDir");
            if (p.get("planTemperature") != null)
                planTemperature = Double.parseDouble(p.get("planTemperature"));
            if (p.get("planMaxTokens") != null)
                planMaxTokens = Integer.parseInt(p.get("planMaxTokens"));
            if (p.get("workflowModule") != null)
                workflowModule = p.get("workflowModule");
            if (p.get("basePlanningPrompt") != null)
                basePlanningPrompt = p.get("basePlanningPrompt");
            if (p.get("discoverFromRegistry") != null)
                discoverFromRegistry = Boolean.parseBoolean(p.get("discoverFromRegistry"));
        }
        if (plannerModelLight == null) plannerModelLight = plannerModel;

        // 从 myConfig 自定义段读取白名单（与 params 同源，直接从 mconfig 读）
        if (mconfig instanceof myConfig) {
            myConfig cfg = (myConfig) mconfig;
            configuredAgents.putAll(cfg.getConfiguredAgents());
            configuredSkills.putAll(cfg.getConfiguredSkills());
        }
    }

    @Override
    public void runStartMsg() {
        super.runStartMsg();
        // 借用 LLM Provider（与其他子 agent 同模式）
        llmProvider = resolveLlmProvider(plannerProvider);
        if (llmProvider == null) {
            putLog("DagPlanner [" + name + "] cannot find LLM provider: "
                    + plannerProvider, LogLevel.ERROR);
        } else {
            putLog("DagPlanner [" + name + "] provider=" + plannerProvider
                    + " model=" + plannerModel + " workflowModule=" + workflowModule,
                    LogLevel.INFO);
        }
        // 加载 MD 规划提示词（与 Agent 加载 md 同构）
        loadPlannerMd();
    }

    /**
     * 借用 LLM Provider（与其他子 agent 完全相同的模式）。
     * agent借用格式 "agentName:providerName" → 发 getLlmProvider 给 agentName，
     * 与 TLAiAgent.initProvider 相同的协议。
     */
    private IObject resolveLlmProvider(String providerRef) {
        if (providerRef == null || providerRef.isEmpty()) return null;

        int colonIdx = providerRef.indexOf(':');
        if (colonIdx > 0) {
            String agentName = providerRef.substring(0, colonIdx);
            String provName = providerRef.substring(colonIdx + 1);

            // 与 TLAiAgent.initProvider 完全相同的借用协议
            TLMsg provResult = putMsg(agentName,
                    createMsg().setAction("getLlmProvider")
                            .setParam("provName", provName));
            Object ref = provResult != null ? provResult.getParam("provider") : null;
            if (ref instanceof IObject) {
                putLog("DagPlanner: provider borrowed from " + providerRef, LogLevel.DEBUG);
                return (IObject) ref;
            }
            putLog("DagPlanner: failed to borrow provider from " + providerRef, LogLevel.WARN);
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
     * 自动加载规划提示词 md 文件（与 TLAiAgent.loadAgentMd 同构）。
     * 查找顺序：
     * 1. XML 显式配置 basePlanningPrompt 参数（直接使用文本）
     * 2. 配置目录下默认 md/ 文件夹：{configDir}md/{name}.md
     * 正文作为规划系统提示词模板。
     * 运行时会在末尾拼接 {可用资源} + {用户需求}。
     */
    protected void loadPlannerMd() {
        // XML 直接配置的文本优先
        if (basePlanningPrompt != null && !basePlanningPrompt.trim().isEmpty()) return;

        String content = TLMdFileLoader.readFileOrResource(
                moduleFactory.getConfigDir() + "md/" + name + ".md", this.getClass());
        if (content == null || content.trim().isEmpty()) {
            putLog("DagPlanner [" + name + "] no md prompt found, using built-in default",
                    LogLevel.DEBUG);
            return;
        }

        String body = TLMdFileLoader.parseBody(content);
        if (body != null && !body.isEmpty()) {
            basePlanningPrompt = body;
            putLog("DagPlanner [" + name + "] prompt loaded from md (" + body.length() + " chars)",
                    LogLevel.DEBUG);
        }
    }

    // ======================== Skill 接口实现 ========================

    /**
     * 实现 TLBaseSkill.execute()——处理 SKILL_EXECUTE 消息。
     * 从 AI_P_SKILLINPUT 中提取参数，转换为 doPlan() 所需的 TLMsg 格式，
     * 执行后将结果格式化为 AI_P_SKILLOUTPUT 返回给 LLM。
     */
    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        Map<String, Object> input = msg.getMapParam(AI_P_SKILLINPUT, new LinkedHashMap<>());

        // 转换为 doPlan 期望的 TLMsg 格式
        TLMsg planMsg = createMsg();
        if (input.containsKey("requirement")) {
            planMsg.setParam(DAGPLAN_REQUIREMENT, input.get("requirement").toString());
        }
        if (input.containsKey("planName")) {
            planMsg.setParam(DAGPLAN_PLANNAME, input.get("planName").toString());
        }
        if (input.containsKey("forceRegenerate")) {
            Object fr = input.get("forceRegenerate");
            boolean v = fr instanceof Boolean ? (Boolean) fr
                    : Boolean.parseBoolean(String.valueOf(fr));
            planMsg.setParam(DAGPLAN_FORCE_REGENERATE, v);
        }

        // 执行规划
        TLMsg result = doPlan(planMsg);

        // 格式化为 skill 输出
        boolean ok = result.parseBoolean(RESULT, false);
        TLMsg ret = createMsg().setParam(RESULT, ok);
        if (ok) {
            String mdFile = result.getStringParam(DAGPLAN_MDFILE, "");
            String planName = result.getStringParam(DAGPLAN_PLANNAME, "");
            String fromCache = result.getStringParam(DAGPLAN_FROMCACHE, "false");
            int nodeCount = result.getParam("nodeCount") != null
                    ? ((Number) result.getParam("nodeCount")).intValue() : 0;
            int edgeCount = result.getParam("edgeCount") != null
                    ? ((Number) result.getParam("edgeCount")).intValue() : 0;
            String desc = result.getStringParam("description", "");

            StringBuilder output = new StringBuilder();
            output.append("计划生成成功！\n");
            output.append("- 计划名: ").append(planName).append("\n");
            output.append("- MD 文件: ").append(mdFile).append("\n");
            output.append("- 节点数: ").append(nodeCount).append(", 边数: ").append(edgeCount).append("\n");
            output.append("- 来源: ").append(fromCache.equals("false") ? "LLM生成"
                    : (fromCache + " 缓存命中")).append("\n");
            if (!desc.isEmpty()) output.append("- 描述: ").append(desc).append("\n");

            ret.setParam(AI_P_SKILLOUTPUT, output.toString());
            // 额外透传结构化数据，供后续 workflow 执行
            ret.setParam(DAGPLAN_MDFILE, mdFile);
            ret.setParam(DAGPLAN_PLANNAME, planName);
            ret.setParam("nodeCount", nodeCount);
            ret.setParam("edgeCount", edgeCount);
        } else {
            ret.setParam(AI_P_SKILLOUTPUT,
                    "计划生成失败: " + result.getStringParam("error", "unknown error"));
        }
        return ret;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        // 先让 TLBaseSkill 处理 SKILL_GETINFO / SKILL_EXECUTE / SKILL_VALIDATE
        TLMsg skillResult = super.checkMsgAction(fromWho, msg);
        if (skillResult != null) return skillResult;

        // 处理 DAG Planner 自身的 action
        switch (msg.getAction()) {
            case DAGPLAN_DOPLAN:
                return doPlan(msg);
            case DAGPLAN_LISTPLANS:
                return listPlans(msg);
            case DAGPLAN_SHOWPLAN:
                return showPlan(msg);
            case AGENT_CHAT:
                // 作为子 agent 被调用，userMessage 即为需求
                return doPlan(msg);
            case AGENT_GETDESCRIPTION:
                return createMsg().setParam(RESULT, true)
                        .setParam(AI_P_AGENTDESCRIPTION,
                                "DAG Planner: 将自然语言需求分解为子任务并编排为工作流");
            case AGENT_STOPCHAT:
                return createMsg().setParam(RESULT, true);
            default:
                return null;
        }
    }

    // ======================== doPlan 主入口 ========================

    /**
     * 执行规划，内置两层缓存匹配。
     * msg 参数：
     * - requirement/userMessage: 用户需求文本（必填）
     * - planName: 指定计划名（可选，不指定则 hash 生成）
     * - forceRegenerate: 强制重新生成（可选，默认 false）
     *
     * 返回参数：
     * - RESULT: 是否成功
     * - mdFile: MD 文件路径
     * - fromCache: exact | semantic | false
     * - planName: 计划名
     * - nodeCount / edgeCount
     */
    private TLMsg doPlan(TLMsg msg) {
        String requirement = msg.getStringParam(DAGPLAN_REQUIREMENT,
                msg.getStringParam(AI_P_USERMESSAGE, ""));
        if (requirement.isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "Missing requirement/userMessage");
        }

        String planName = msg.getStringParam(DAGPLAN_PLANNAME, "");
        boolean forceRegenerate = msg.parseBoolean(DAGPLAN_FORCE_REGENERATE, false);

        // 确定计划名
        if (planName.isEmpty()) {
            planName = "plan_" + sha256hex(requirement).substring(0, 8);
        }

        // 确保输出目录存在
        ensureOutputDir();

        // ===== 第一层：精确匹配 =====
        Path mdPath = Paths.get(planOutputDir, planName + ".md");
        if (!forceRegenerate && Files.exists(mdPath)) {
            putLog("DagPlanner exact cache hit: " + planName, LogLevel.INFO);
            return buildPlanResult(mdPath.toString(), planName, "exact");
        }

        // ===== 第二层：语义匹配 =====
        if (!forceRegenerate) {
            String matched = semanticMatch(requirement);
            if (matched != null) {
                Path matchedPath = Paths.get(planOutputDir, matched + ".md");
                if (Files.exists(matchedPath)) {
                    putLog("DagPlanner semantic cache hit: " + matched
                            + " for requirement: " + truncate(requirement, 50), LogLevel.INFO);
                    return buildPlanResult(matchedPath.toString(), matched, "semantic");
                }
            }
        }

        // ===== 第三层：完整 LLM 规划 =====
        putLog("DagPlanner generating new plan for: " + truncate(requirement, 80), LogLevel.INFO);
        String mdContent = generatePlanWithLLM(requirement, planName);
        if (mdContent == null || mdContent.trim().isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "LLM plan generation returned empty content");
        }

        // 保存 MD 文件
        try {
            Files.write(mdPath, mdContent.getBytes(StandardCharsets.UTF_8));
            putLog("DagPlanner saved plan: " + mdPath, LogLevel.INFO);
        } catch (IOException e) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "Failed to save MD file: " + e.getMessage());
        }

        return buildPlanResult(mdPath.toString(), planName, "false");
    }

    /**
     * 构建规划结果消息
     */
    private TLMsg buildPlanResult(String mdPath, String planName, String fromCache) {
        TLMsg ret = createMsg()
                .setParam(RESULT, true)
                .setParam(DAGPLAN_MDFILE, mdPath)
                .setParam(DAGPLAN_PLANNAME, planName)
                .setParam(DAGPLAN_FROMCACHE, fromCache)
                .setParam("workflowModule", workflowModule);

        // 解析 MD 中 YAML，补充 nodeCount/edgeCount/description
        TLParsedDag dag = parseMdFile(mdPath);
        if (dag != null) {
            ret.setParam("nodeCount", dag.getNodeCount());
            ret.setParam("edgeCount", dag.getEdgeCount());
            if (dag.getDescription() != null) {
                ret.setParam("description", dag.getDescription());
            }
            // 将解析后的 nodes/edges 也放入结果，方便调用方直接执行
            ret.setParam("nodes", dag.getNodes());
            ret.setParam("edges", dag.getEdges());
        }

        return ret;
    }

    // ======================== 语义匹配 ========================

    /**
     * 第二层缓存：收集所有已有计划的摘要，调轻量 LLM 判断用户需求是否匹配。
     * @return 匹配的 planName，不匹配返回 null
     */
    private String semanticMatch(String requirement) {
        if (llmProvider == null) return null;

        List<Map<String, String>> summaries = buildPlanSummaries();
        if (summaries.isEmpty()) return null;

        // 构建 prompt
        StringBuilder sb = new StringBuilder();
        sb.append("用户需求: \"").append(requirement).append("\"\n\n");
        sb.append("已有工作流计划:\n");
        for (Map<String, String> s : summaries) {
            sb.append("- ").append(s.get("name")).append(": ").append(s.get("description")).append("\n");
        }
        sb.append("\n请判断用户需求是否与某个已有计划匹配（语义相同或高度相似即为匹配）。\n");
        sb.append("返回 JSON 格式: {\"match\": true/false, \"planName\": \"匹配的计划名\", \"reason\": \"判断理由\"}\n");
        sb.append("只返回 JSON，不要其他内容。");

        try {
            String response = callLLM(sb.toString(), plannerModelLight, 0.0, 256);
            if (response == null) return null;

            // 解析 JSON 响应
            response = extractJSON(response);
            if (response == null) return null;

            // 简单 JSON 解析（避免引入 Gson 依赖做额外 import... 直接用字符串解析）
            boolean match = response.contains("\"match\": true") || response.contains("\"match\":true");
            if (!match) return null;

            // 提取 planName
            String planName = extractJsonString(response, "planName");
            return (planName != null && !planName.isEmpty()) ? planName : null;

        } catch (Exception e) {
            putLog("DagPlanner semanticMatch error: " + e, LogLevel.WARN);
            return null;
        }
    }

    /** 提取 JSON 字符串字段值 */
    private String extractJsonString(String json, String key) {
        // 匹配 "key": "value" 或 "key":"value"
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** 从 LLM 响应中提取 JSON 部分（去掉可能的 markdown 代码块包裹） */
    private String extractJSON(String text) {
        if (text == null) return null;
        text = text.trim();
        // 去掉 ```json ... ``` 包裹
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (start >= 0 && end > start) {
                text = text.substring(start + 1, end).trim();
            }
        }
        // 找到 { 开头 } 结尾
        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return text.substring(braceStart, braceEnd + 1);
        }
        return null;
    }

    // ======================== 完整 LLM 规划 ========================

    /**
     * 调用 LLM 生成完整的 MD 任务文件。
     * System prompt 来自 MD 文件（basePlanningPrompt），末尾拼接动态数据。
     */
    private String generatePlanWithLLM(String requirement, String planName) {
        if (llmProvider == null) {
            putLog("DagPlanner no LLM provider available", LogLevel.ERROR);
            return null;
        }

        // 收集可用资源
        String capabilities = collectAvailableCapabilities();

        // 构建 prompt：MD 模板 + 动态数据
        StringBuilder prompt = new StringBuilder();
        if (basePlanningPrompt != null && !basePlanningPrompt.trim().isEmpty()) {
            prompt.append(basePlanningPrompt.trim()).append("\n\n");
        } else {
            prompt.append("你是一个任务规划专家。请根据用户需求，将其分解为子任务，并以 DAG 方式编排。\n\n");
        }

        // 替换模板占位符
        String text = prompt.toString();
        text = text.replace("{planName}", planName);

        prompt = new StringBuilder(text);
        prompt.append("\n## 可用资源\n\n");
        if (!capabilities.isEmpty()) {
            prompt.append(capabilities).append("\n");
        } else {
            prompt.append("- aiagent: 通用 AI Agent\n");
        }
        prompt.append("- 内置节点类型: agent, fanout, join, condition\n");

        prompt.append("## 用户需求\n");
        prompt.append(requirement).append("\n\n");
        prompt.append("请直接输出完整的 Markdown 文件内容（从 --- 开始），不要加任何额外说明。");

        try {
            String response = callLLM(prompt.toString(), plannerModel, planTemperature, planMaxTokens);
            if (response == null) return null;

            // 清理响应：确保以 --- 开头
            response = response.trim();
            if (!response.startsWith("---")) {
                int idx = response.indexOf("---");
                if (idx >= 0) {
                    response = response.substring(idx);
                } else {
                    putLog("DagPlanner LLM response missing YAML frontmatter", LogLevel.WARN);
                    return null;
                }
            }

            return response;
        } catch (Exception e) {
            putLog("DagPlanner plan generation error: " + e, LogLevel.ERROR);
            return null;
        }
    }

    // ======================== LLM 调用 ========================

    /**
     * 调用 LLM 完成单轮对话。
     * @param userMessage 用户消息
     * @param model 模型名（null 则使用 provider 默认）
     * @param temperature 温度
     * @param maxTokens 最大 token
     * @return LLM 返回的文本内容
     */
    private String callLLM(String userMessage, String model, double temperature, int maxTokens) {
        if (llmProvider == null) {
            putLog("DagPlanner: LLM provider not available", LogLevel.ERROR);
            return null;
        }

        List<TLConversationHistory> history = new ArrayList<>();
        history.add(new TLConversationHistory(TLConversationHistory.Role.user, userMessage));

        TLMsg llmMsg = createMsg()
                .setAction(LLM_COMPLETION)
                .setParam(AI_P_MESSAGEHISTORY, history)
                .setParam(AI_P_TEMPERATURE, temperature)
                .setParam(AI_P_MAXTOKENS, maxTokens);
        if (model != null && !model.isEmpty()) {
            llmMsg.setParam(AI_P_MODEL, model);
        }

        TLMsg result = putMsg(llmProvider, llmMsg);
        if (result == null || !result.parseBoolean(RESULT, false)) {
            String err = result != null
                    ? result.getStringParam(AI_P_RESPONSEBODY, "unknown error")
                    : "null response";
            putLog("DagPlanner LLM call failed: " + err, LogLevel.WARN);
            return null;
        }

        return result.getStringParam(AI_P_RESPONSE, "");
    }

    // ======================== 已有计划管理 ========================

    /**
     * 收集所有已有计划的名称和描述。
     */
    private List<Map<String, String>> buildPlanSummaries() {
        List<Map<String, String>> summaries = new ArrayList<>();
        Path dir = Paths.get(planOutputDir);
        if (!Files.isDirectory(dir)) return summaries;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.md")) {
            for (Path p : stream) {
                TLParsedDag dag = parseMdFile(p.toString());
                if (dag != null) {
                    Map<String, String> s = new HashMap<>();
                    s.put("name", dag.getPlanName() != null ? dag.getPlanName()
                            : p.getFileName().toString().replace(".md", ""));
                    s.put("description", dag.getDescription() != null ? dag.getDescription()
                            : "No description");
                    summaries.add(s);
                }
            }
        } catch (IOException e) {
            putLog("DagPlanner buildPlanSummaries error: " + e, LogLevel.WARN);
        }
        return summaries;
    }

    /**
     * 列出所有已保存计划。
     */
    private TLMsg listPlans(TLMsg msg) {
        List<Map<String, String>> plans = new ArrayList<>();
        Path dir = Paths.get(planOutputDir);
        if (!Files.isDirectory(dir)) {
            return createMsg().setParam(RESULT, true)
                    .setParam("plans", plans)
                    .setParam(AI_P_RESPONSE, "No saved plans found.");
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.md")) {
            for (Path p : stream) {
                TLParsedDag dag = parseMdFile(p.toString());
                Map<String, String> info = new HashMap<>();
                String pname = p.getFileName().toString().replace(".md", "");
                info.put("name", pname);
                info.put("file", p.toString());
                info.put("description", dag != null && dag.getDescription() != null
                        ? dag.getDescription() : "");
                info.put("nodes", dag != null ? String.valueOf(dag.getNodeCount()) : "?");
                info.put("edges", dag != null ? String.valueOf(dag.getEdgeCount()) : "?");
                try {
                    info.put("modified", new Date(Files.getLastModifiedTime(p).toMillis()).toString());
                } catch (IOException ignored) {}
                plans.add(info);
            }
        } catch (IOException e) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "Failed to list plans: " + e.getMessage());
        }

        // 构建可读的响应
        StringBuilder sb = new StringBuilder();
        if (plans.isEmpty()) {
            sb.append("No saved plans.");
        } else {
            sb.append("Saved plans (").append(plans.size()).append("):\n");
            for (Map<String, String> p : plans) {
                sb.append("  ").append(p.get("name"))
                        .append(" | ").append(p.get("nodes")).append(" nodes, ")
                        .append(p.get("edges")).append(" edges");
                if (!p.get("description").isEmpty()) {
                    sb.append(" | ").append(p.get("description"));
                }
                sb.append("\n");
            }
        }

        return createMsg().setParam(RESULT, true)
                .setParam("plans", plans)
                .setParam(AI_P_RESPONSE, sb.toString().trim());
    }

    /**
     * 查看指定计划的内容。
     */
    private TLMsg showPlan(TLMsg msg) {
        String planName = msg.getStringParam(DAGPLAN_PLANNAME, "");
        if (planName.isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "Missing planName");
        }

        Path mdPath = Paths.get(planOutputDir, planName + ".md");
        if (!Files.exists(mdPath)) {
            mdPath = Paths.get(planName); // 尝试作为完整路径
            if (!Files.exists(mdPath)) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "Plan not found: " + planName);
            }
        }

        try {
            String content = new String(Files.readAllBytes(mdPath), StandardCharsets.UTF_8);
            return createMsg().setParam(RESULT, true)
                    .setParam(DAGPLAN_MDCONTENT, content)
                    .setParam(DAGPLAN_MDFILE, mdPath.toString())
                    .setParam(AI_P_RESPONSE, content);
        } catch (IOException e) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "Failed to read plan: " + e.getMessage());
        }
    }

    // ======================== Agent 收集 ========================

    /**
     * 收集可用 Agent + Skill，优先使用配置白名单，可选从注册表补充。
     */
    private String collectAvailableCapabilities() {
        StringBuilder sb = new StringBuilder();

        // ===== 第一来源：配置白名单（主要） =====
        if (!configuredAgents.isEmpty()) {
            sb.append("**可用 Agent:**\n");
            for (Map.Entry<String, Map<String, Object>> e : configuredAgents.entrySet()) {
                String name = e.getKey();
                Map<String, Object> info = e.getValue();
                sb.append("- agent: ").append(name).append("\n");
                sb.append("  description: ").append(info.getOrDefault("description", "")).append("\n");
                if (info.containsKey("sameClassAs")) {
                    sb.append("  sameClassAs: ").append(info.get("sameClassAs")).append("\n");
                }
                @SuppressWarnings("unchecked")
                Map<String, String> defaultParams = (Map<String, String>) info.get("defaultParams");
                if (defaultParams != null && !defaultParams.isEmpty()) {
                    sb.append("  默认 params: ").append(defaultParams).append("\n");
                }
            }
            sb.append("\n");
        }

        if (!configuredSkills.isEmpty()) {
            sb.append("**可用 Skill（工具）:**\n");
            for (Map.Entry<String, String> e : configuredSkills.entrySet()) {
                sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
            sb.append("\n");
        }

        // ===== 第二来源：注册表动态发现（补充，仅 discoverFromRegistry=true 时） =====
        if (discoverFromRegistry) {
            int beforeLen = sb.length();
            collectFromRegistry(sb, AGENT_LISTAGENTS, "**额外注册 Agent:**\n", "collectAgents");
            collectFromRegistry(sb, AGENT_LISTSKILLS, "**额外注册 Skill:**\n", "collectSkills");

            // 去重：如果注册表返回的已在白名单中，不出现在"额外"部分
            // （简单实现：仍可能出现重复，但 LLM 能自行判断）
            if (sb.length() == beforeLen + getRegistryHeaderLen(beforeLen)) {
                // 没有额外内容，回退
                sb.setLength(beforeLen);
            }
        }

        // 兜底：没有任何可用资源时，至少给一个通用 Agent
        if (sb.length() == 0) {
            sb.append("- aiagent: 通用 AI Agent\n");
        }

        return sb.toString();
    }

    /** 从注册表收集 Agent 或 Skill */
    private void collectFromRegistry(StringBuilder sb, String action, String header, String logLabel) {
        try {
            TLMsg result = putMsg(M_AIAGENT, createMsg().setAction(action));
            if (result != null && result.parseBoolean(RESULT, false)) {
                Object obj = result.getParam(AI_P_SUBAGENTS);
                if (obj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) obj;
                    if (!map.isEmpty()) {
                        sb.append(header);
                        for (Map.Entry<String, Object> e : map.entrySet()) {
                            sb.append("- ").append(e.getKey());
                            if (e.getValue() instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> info = (Map<String, Object>) e.getValue();
                                Object desc = info.get("description");
                                if (desc != null && !desc.toString().isEmpty()) {
                                    sb.append(": ").append(desc);
                                }
                            }
                            sb.append("\n");
                        }
                        sb.append("\n");
                    }
                }
            }
        } catch (Exception ex) {
            putLog("DagPlanner " + logLabel + " error: " + ex, LogLevel.DEBUG);
        }
    }

    private int getRegistryHeaderLen(int beforeLen) { return 0; } // stub, 简单实现

    // ======================== MD 文件解析 ========================

    /**
     * 解析 MD 文件的 YAML frontmatter。
     */
    @SuppressWarnings("unchecked")
    public static TLParsedDag parseMdFile(String mdFilePath) {
        try {
            String content = new String(Files.readAllBytes(Paths.get(mdFilePath)), StandardCharsets.UTF_8);
            return parseMdContent(content);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 解析 MD 内容中的 YAML frontmatter。
     */
    @SuppressWarnings("unchecked")
    public static TLParsedDag parseMdContent(String mdContent) {
        if (mdContent == null || !mdContent.trim().startsWith("---")) return null;

        try {
            // 提取 frontmatter: 第一个 --- 到第二个 --- 之间
            int firstDash = mdContent.indexOf("---");
            int secondDash = mdContent.indexOf("---", firstDash + 3);
            if (secondDash < 0) return null;

            String yamlStr = mdContent.substring(firstDash + 3, secondDash).trim();
            String body = mdContent.substring(secondDash + 3).trim();

            Yaml yaml = new Yaml();
            Map<String, Object> frontmatter = yaml.load(yamlStr);
            if (frontmatter == null) return null;

            TLParsedDag dag = new TLParsedDag();
            dag.setPlanName((String) frontmatter.get("name"));
            dag.setDescription((String) frontmatter.get("description"));
            dag.setBody(body);

            // 解析 dag 部分
            Object dagObj = frontmatter.get("dag");
            if (dagObj instanceof Map) {
                Map<String, Object> dagMap = (Map<String, Object>) dagObj;

                // 解析 nodes
                Object nodesObj = dagMap.get("nodes");
                if (nodesObj instanceof List) {
                    List<Map<String, Object>> nodeList = (List<Map<String, Object>>) nodesObj;
                    for (Map<String, Object> nodeMap : nodeList) {
                        TLWorkflowNode node = new TLWorkflowNode();
                        node.setId((String) nodeMap.get("id"));

                        String typeStr = (String) nodeMap.get("type");
                        if (typeStr != null) {
                            try {
                                node.setType(TLWorkflowNodeType.valueOf(typeStr.toUpperCase()));
                            } catch (IllegalArgumentException e) {
                                node.setType(TLWorkflowNodeType.AGENT);
                            }
                        }

                        // 模块引用（优先级：agent > module）
                        node.setModule((String) nodeMap.get("agent"));
                        if (node.getModule() == null) {
                            node.setModule((String) nodeMap.get("module"));
                        }
                        // 私有实例：sameClassAs 或 classfile（可选，优先用 agent 指向单例）
                        node.setSameClassAs((String) nodeMap.get("sameClassAs"));
                        node.setClassfile((String) nodeMap.get("classfile"));
                        node.setAction((String) nodeMap.get("action"));

                        // params: 合并顶层字段 + params 子对象
                        Map<String, String> params = new HashMap<>();
                        // 从顶层提取 params（LLM 可能平铺写）
                        for (Map.Entry<String, Object> e : nodeMap.entrySet()) {
                            String key = e.getKey();
                            if (isNodeMetaField(key)) continue; // 跳过元字段
                            if ("params".equals(key)) continue; // params 子对象单独处理
                            if (e.getValue() instanceof String || e.getValue() instanceof Number) {
                                params.put(key, e.getValue().toString());
                            }
                        }
                        // params 子对象覆盖
                        Object paramsObj = nodeMap.get("params");
                        if (paramsObj instanceof Map) {
                            Map<String, Object> pMap = (Map<String, Object>) paramsObj;
                            for (Map.Entry<String, Object> e : pMap.entrySet()) {
                                params.put(e.getKey(),
                                        e.getValue() != null ? e.getValue().toString() : "");
                            }
                        }
                        node.setParams(params);

                        // 从 params 提取超时/重试等配置
                        if (params.containsKey("timeout"))
                            node.setTimeout(Long.parseLong(params.get("timeout")));
                        if (params.containsKey("onFailure"))
                            node.setOnFailure(params.get("onFailure"));
                        if (params.containsKey("maxRetries"))
                            node.setMaxRetries(Integer.parseInt(params.get("maxRetries")));
                        if (params.containsKey("fallbackNodeId"))
                            node.setFallbackNodeId(params.get("fallbackNodeId"));

                        dag.addNode(node);
                    }
                }

                // 解析 edges
                Object edgesObj = dagMap.get("edges");
                if (edgesObj instanceof List) {
                    List<Map<String, Object>> edgeList = (List<Map<String, Object>>) edgesObj;
                    for (Map<String, Object> edgeMap : edgeList) {
                        TLWorkflowEdge edge = new TLWorkflowEdge();
                        edge.setFrom((String) edgeMap.get("from"));
                        edge.setTo((String) edgeMap.get("to"));
                        edge.setCondition((String) edgeMap.get("condition"));
                        dag.addEdge(edge);
                    }
                }
            }

            return dag;
        } catch (Exception e) {
            // YAML 解析失败
            return null;
        }
    }

    // ======================== 工具方法 ========================

    /** 确保输出目录存在 */
    private void ensureOutputDir() {
        try {
            Files.createDirectories(Paths.get(planOutputDir));
        } catch (IOException e) {
            putLog("DagPlanner cannot create output dir: " + planOutputDir, LogLevel.ERROR);
        }
    }

    /** SHA-256 哈希（十六进制） */
    private static String sha256hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /** YAML 节点元字段（不是 params，不应被放入参数 map） */
    private static final Set<String> NODE_META_FIELDS = new HashSet<>(Arrays.asList(
            "id", "type", "agent", "module", "sameClassAs", "classfile", "action",
            "timeout", "onFailure", "maxRetries", "fallbackNodeId", "params"
    ));

    private static boolean isNodeMetaField(String key) {
        return NODE_META_FIELDS.contains(key);
    }

    /** 截断字符串 */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ======================== XML 配置解析 ========================

    protected class myConfig extends TLModuleConfig {
        private final Map<String, Map<String, Object>> agents = new LinkedHashMap<>();
        private final Map<String, String> skills = new LinkedHashMap<>();

        public myConfig() {}
        public myConfig(String configFile, String configDir) {
            super(configFile, configDir);
        }

        public Map<String, Map<String, Object>> getConfiguredAgents() { return agents; }
        public Map<String, String> getConfiguredSkills() { return skills; }

        @Override
        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                switch (xpp.getName()) {
                    case "availableAgents":
                        parseAgents(xpp);
                        break;
                    case "availableSkills":
                        parseSkills(xpp);
                        break;
                    case "basePlanningPrompt":
                        basePlanningPrompt = readCdataContent(xpp, "basePlanningPrompt");
                        break;
                }
            } catch (Throwable t) {
                putLog("DagPlanner config parse error: " + t, LogLevel.WARN);
            }
        }

        /** 解析 <agent name="x" description="y" sameClassAs="z"> 含子 <param> */
        private void parseAgents(XmlPullParser xpp) throws Throwable {
            while (true) {
                xpp.next();
                if (xpp.getEventType() == XmlPullParser.END_DOCUMENT) break;
                if (isEndTag(xpp, "availableAgents")) break;
                if (xpp.getEventType() == XmlPullParser.START_TAG
                        && xpp.getName().equals("agent")) {
                    String name = xpp.getAttributeValue(null, "name");
                    String desc = xpp.getAttributeValue(null, "description");
                    String sameClassAs = xpp.getAttributeValue(null, "sameClassAs");
                    if (name != null) {
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("description", desc != null ? desc : "");
                        if (sameClassAs != null) info.put("sameClassAs", sameClassAs);
                        // 解析子 <param>
                        Map<String, String> defaultParams = new LinkedHashMap<>();
                        parseChildParams(xpp, "agent", defaultParams);
                        if (!defaultParams.isEmpty()) {
                            info.put("defaultParams", defaultParams);
                        }
                        agents.put(name, info);
                    }
                }
            }
        }

        /** 解析 <skill name="x" description="y"/> */
        private void parseSkills(XmlPullParser xpp) throws Throwable {
            while (true) {
                xpp.next();
                if (xpp.getEventType() == XmlPullParser.END_DOCUMENT) break;
                if (isEndTag(xpp, "availableSkills")) break;
                if (xpp.getEventType() == XmlPullParser.START_TAG
                        && xpp.getName().equals("skill")) {
                    String name = xpp.getAttributeValue(null, "name");
                    String desc = xpp.getAttributeValue(null, "description");
                    if (name != null) {
                        skills.put(name, desc != null ? desc : "");
                    }
                }
            }
        }

        /** 解析父元素内的 <param name="k" value="v"/> 子元素 */
        private void parseChildParams(XmlPullParser xpp, String parentTag,
                                       Map<String, String> params) throws Throwable {
            int depth = xpp.getDepth();
            while (true) {
                xpp.next();
                if (xpp.getEventType() == XmlPullParser.END_DOCUMENT) break;
                if (xpp.getEventType() == XmlPullParser.END_TAG
                        && xpp.getDepth() == depth
                        && xpp.getName().equals(parentTag)) {
                    break;
                }
                if (xpp.getEventType() == XmlPullParser.START_TAG
                        && xpp.getName().equals("param")) {
                    String key = xpp.getAttributeValue(null, "name");
                    String value = xpp.getAttributeValue(null, "value");
                    if (key != null) params.put(key, value != null ? value : "");
                }
            }
        }

        /** 读取 CDATA 包裹的文本内容 */
        private String readCdataContent(XmlPullParser xpp, String parentTag) throws Throwable {
            StringBuilder sb = new StringBuilder();
            while (true) {
                xpp.next();
                if (xpp.getEventType() == XmlPullParser.END_DOCUMENT) break;
                if (xpp.getEventType() == XmlPullParser.END_TAG
                        && xpp.getName().equals(parentTag)) {
                    break;
                }
                if (xpp.getEventType() == XmlPullParser.TEXT
                        || xpp.getEventType() == XmlPullParser.CDSECT) {
                    sb.append(xpp.getText());
                }
            }
            String text = sb.toString().trim();
            return text.isEmpty() ? null : text;
        }

        private boolean isEndTag(XmlPullParser xpp, String tagName)
                throws org.xmlpull.v1.XmlPullParserException {
            return xpp.getEventType() == XmlPullParser.END_TAG
                    && xpp.getName().equals(tagName);
        }
    }
}
