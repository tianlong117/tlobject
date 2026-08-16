package cn.tianlong.tlobject.aiagent.workflow;

import cn.tianlong.tlobject.aiagent.IAgentCapable;
import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLModuleConfig;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import org.xmlpull.v1.XmlPullParser;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 工作流编排模块——用 DAG 描述多 Agent 协作流程。
 *
 * 支持节点类型：
 * - agent:     调用指定 Agent/Skill
 * - condition: 条件分支（表达式求值）
 * - join:      汇聚等待（多上游合并）
 * - fanout:    扇出（一份输入 → 多下游并行）
 *
 * 配置方式：
 * 1. XML 配置文件（{name}_config.xml）
 * 2. 编程式：doWorkflow msg 中传入 nodes + edges 参数动态构建
 * 3. 表达式式：msg 参数 expression 或 XML &lt;param name="expression"/&gt;，
 *    经 {@link TLWorkflowExprParser} 编译成 DAG（如 "A && B -> C"、"if(c) X else Y"）
 *
 * 节点/边来源优先级：msg.expression &gt; msg.nodes/edges &gt; XML expression &gt; XML nodes/edges
 *
 * 与 TLAgentGroup 互补：Group 是"一个团队"，Workflow 是"一条流水线"。
 * 流水线的每个工位可以是 Agent，也可以是 Group（对 Workflow 透明）。
 *
 * @author tianlong
 * @since 2026/7/26
 */
public class TLAgentWorkflow extends TLBaseModule
        implements TLAiAgentParamString, TLWorkflowEngine.NodeRunner, IAgentCapable {

    /** 静态配置的节点（XML 解析） */
    private Map<String, TLWorkflowNode> configuredNodes = new LinkedHashMap<>();

    /** 静态配置的边（XML 解析） */
    private List<TLWorkflowEdge> configuredEdges = new ArrayList<>();

    /** XML &lt;param name="expression"/&gt; 编译缓存（null = 未配置） */
    private Map<String, TLWorkflowNode> xmlExprNodes;
    private List<TLWorkflowEdge> xmlExprEdges;

    /** 最大并行节点数 */
    private int maxParallel = 5;

    /** 工作流总超时（毫秒，0=不限制） */
    private long workflowTimeout;

    /** 工作流描述（作为子agent时返回给master） */
    private String description;

    public TLAgentWorkflow() { super(); }
    public TLAgentWorkflow(String name) { super(name); }
    public TLAgentWorkflow(String name, TLObjectFactory factory) { super(name, factory); }

    @Override
    protected Object setConfig() {
        myConfig config = new myConfig(configFile, moduleFactory.getConfigDir());
        mconfig = config;
        super.setConfig();
        return config;
    }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("maxParallel") != null) {
                try { maxParallel = Integer.parseInt(params.get("maxParallel")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("workflowTimeout") != null) {
                try { workflowTimeout = Long.parseLong(params.get("workflowTimeout")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("description") != null) {
                description = params.get("description");
            }
            // XML 表达式模式：启动期编译一次并缓存（失败回退 XML nodes/edges）
            if (params.get("expression") != null) {
                try {
                    TLWorkflowExprParser.Compiled compiled =
                            TLWorkflowExprParser.compile(params.get("expression"));
                    xmlExprNodes = compiled.nodes;
                    xmlExprEdges = compiled.edges;
                    putLog("Workflow [" + name + "] expression compiled: "
                            + params.get("expression"), LogLevel.DEBUG);
                } catch (RuntimeException e) {
                    putLog("Workflow [" + name + "] expression parse failed: " + e.getMessage()
                            + " (fallback to XML nodes/edges)", LogLevel.ERROR);
                }
            }
        }
        configuredNodes = ((myConfig) mconfig).getNodes();
        configuredEdges = ((myConfig) mconfig).getEdges();

        if (!configuredNodes.isEmpty()) {
            putLog("Workflow [" + name + "] loaded: " + configuredNodes.size()
                    + " nodes, " + configuredEdges.size() + " edges", LogLevel.DEBUG);
        }
    }

    @Override
    public void runStartMsg() {
        initNodeModules();
        initExprNodeModules();
        super.runStartMsg();
        if (configuredNodes.isEmpty() && xmlExprNodes == null) {
            putLog("Workflow [" + name + "] has no statically configured nodes, "
                    + "will use dynamic nodes from doWorkflow message", LogLevel.DEBUG);
        }
    }

    /**
     * 初始化阶段创建表达式节点的私有模块实例（与静态节点 initNodeModules 同模式）。
     * 启动期建好处：失败当场报错（fail-fast），执行时直接命中缓存，
     * 避免任务中途才拉起节点（msg 级动态表达式仍在执行期惰性创建，无法预知）。
     */
    private void initExprNodeModules() {
        if (xmlExprNodes == null) return;
        for (TLWorkflowNode node : xmlExprNodes.values()) {
            if (node.getType() != TLWorkflowNodeType.AGENT) continue;
            String nid = node.getId();
            try {
                Object inst = getMyModule(nid);
                if (inst != null) {
                    putLog("Workflow expr node [" + nid + "] initialized", LogLevel.DEBUG);
                } else {
                    putLog("Workflow expr node [" + nid + "] failed to create", LogLevel.ERROR);
                }
            } catch (Exception e) {
                putLog("Workflow expr node [" + nid + "] init error: " + e, LogLevel.ERROR);
            }
        }
    }

    /** 初始化阶段创建所有 agent 节点的私有模块实例，失败当场报错 */
    private void initNodeModules() {
        for (TLWorkflowNode node : configuredNodes.values()) {
            if (node.getType() != TLWorkflowNodeType.AGENT) continue;
            String nid = node.getId();
            try {
                Object inst = getMyModule(nid);
                if (inst != null) {
                    putLog("Workflow node [" + nid + "] initialized", LogLevel.DEBUG);
                } else {
                    putLog("Workflow node [" + nid + "] failed to create", LogLevel.ERROR);
                }
            } catch (Exception e) {
                putLog("Workflow node [" + nid + "] init error: " + e, LogLevel.ERROR);
            }
        }
    }

    @Override
    protected TLBaseModule init() { return this; }


    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case WORKFLOW_EXECUTE:
                return doWorkflow(msg);
            case AGENT_CHAT:
                return doWorkflow(msg);
            case SKILL_EXECUTE:
                return doWorkflowAsTool(msg);
            case AGENT_GETDESCRIPTION:
                return createMsg().setParam(RESULT, true)
                        .setParam(AI_P_AGENTDESCRIPTION,
                                description != null ? description
                                        : "Workflow: " + name + " ("
                                        + configuredNodes.size() + " nodes, "
                                        + configuredEdges.size() + " edges)");
            case AGENT_STOPCHAT:
                putLog("Workflow [" + name + "] stop requested", LogLevel.INFO);
                return createMsg().setParam(RESULT, true);
            default:
                return null;
        }
    }

    /**
     * 执行工作流。
     * 节点和边来源优先级：msg 中动态传入 > XML 静态配置。
     * msg 参数：
     * - workflowInput / userMessage: 初始输入
     * - nodes: Map&lt;String, Map&lt;String, String&gt;&gt;  动态节点（可选，覆盖XML）
     * - edges: List&lt;Map&lt;String, String&gt;&gt;          动态边（可选，覆盖XML）
     */
    @SuppressWarnings("unchecked")
    /** 作为 function 被调用：执行工作流 → AI_P_SKILLOUTPUT */
    private TLMsg doWorkflowAsTool(TLMsg msg) {
        // 从 AI_P_SKILLINPUT 提取 task，注入 userMessage
        Map<String, Object> args = (Map<String, Object>) msg.getParam(AI_P_SKILLINPUT, Map.class);
        if (args != null && args.containsKey("task")) {
            msg.setParam("userMessage", String.valueOf(args.get("task")));
        }
        if (!msg.containsParam("userMessage")) {
            msg.setParam("userMessage", msg.getStringParam(AI_P_USERMESSAGE, ""));
        }
        TLMsg result = doWorkflow(msg);
        String output = result != null ? result.getStringParam(AI_P_RESPONSE, "") : "";
        return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLOUTPUT, output);
    }

    private TLMsg doWorkflow(TLMsg msg) {
        // 节点/边来源优先级：msg.expression > msg.nodes/edges > XML expression > XML nodes/edges
        Map<String, TLWorkflowNode> workflowNodes;
        List<TLWorkflowEdge> workflowEdges;
        Object exprParam = msg.getParam("expression");
        if (exprParam instanceof String && !((String) exprParam).trim().isEmpty()) {
            try {
                TLWorkflowExprParser.Compiled compiled =
                        TLWorkflowExprParser.compile((String) exprParam);
                workflowNodes = compiled.nodes;
                workflowEdges = compiled.edges;
                putLog("Workflow [" + name + "] expression run: "
                        + ((String) exprParam).trim(), LogLevel.DEBUG);
            } catch (RuntimeException e) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "expression parse failed: " + e.getMessage());
            }
        } else {
            // 解析节点
            Object dynamicNodes = msg.getParam("nodes");
            if (dynamicNodes instanceof Map) {
                workflowNodes = parseDynamicNodes((Map<String, Map<String, String>>) dynamicNodes);
            } else if (xmlExprNodes != null) {
                workflowNodes = xmlExprNodes;
            } else {
                workflowNodes = new LinkedHashMap<>(configuredNodes);
            }

            // 解析边
            Object dynamicEdges = msg.getParam("edges");
            if (dynamicEdges instanceof List) {
                workflowEdges = parseDynamicEdges((List<Map<String, String>>) dynamicEdges);
            } else if (xmlExprEdges != null) {
                workflowEdges = xmlExprEdges;
            } else {
                workflowEdges = new ArrayList<>(configuredEdges);
            }
        }

        // 构建输入
        TLMsg input = createMsg();
        String userMessage = msg.getStringParam("userMessage",
                msg.getStringParam(WORKFLOW_INPUT, ""));
        if (!userMessage.isEmpty()) {
            input.setParam("userMessage", userMessage);
        }
        input.addArgs(msg.getArgs());
        // 转发会话上下文（框架系统参数区），保持级联停止、用户数据隔离（与 executeAsTool 同款）
        if (msg.containsSystemParam(AI_P_SESSIONID))
            input.setSystemParam(AI_P_SESSIONID, msg.getSystemParam(AI_P_SESSIONID, ""));
        if (msg.containsSystemParam("rootSessionId"))
            input.setSystemParam("rootSessionId", msg.getSystemParam("rootSessionId", ""));
        if (msg.containsSystemParam("userId"))
            input.setSystemParam("userId", msg.getSystemParam("userId", ""));
        // 全链追踪：上游 roundId 透传——工作流节点的一轮就是上游（控制台）的一轮。
        // 缺了它节点各自生成 roundId，追踪记录会互相清空（截断）
        if (msg.containsSystemParam(AI_P_ROUNDID))
            input.setSystemParam(AI_P_ROUNDID, msg.getSystemParam(AI_P_ROUNDID, ""));

        return executeWorkflow(workflowNodes, workflowEdges, input);
    }

    /**
     * 核心执行逻辑：验证 → 构建上下文 → 执行引擎 → 构建返回。
     * doWorkflow 和 doWorkflowMd 的公共部分。
     */
    private TLMsg executeWorkflow(Map<String, TLWorkflowNode> workflowNodes,
                                   List<TLWorkflowEdge> workflowEdges,
                                   TLMsg input) {
        if (workflowNodes.isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "No nodes configured for workflow [" + name + "]");
        }

        // 验证边引用的节点存在
        for (TLWorkflowEdge edge : workflowEdges) {
            if (!workflowNodes.containsKey(edge.getFrom())) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "Edge references unknown node: " + edge.getFrom());
            }
            if (!workflowNodes.containsKey(edge.getTo())) {
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "Edge references unknown node: " + edge.getTo());
            }
        }

        // 为动态节点创建私有模块实例（静态节点由 initNodeModules 在启动时处理）
        for (TLWorkflowNode node : workflowNodes.values()) {
            if (node.getType() != TLWorkflowNodeType.AGENT) continue;
            String nid = node.getId();
            if (getMyModule(nid) != null) continue; // 已存在（静态配置或之前创建过）

            if (node.getSameClassAs() != null) {
                HashMap<String, String> nodeParams = new HashMap<>(node.getParams());
                // 用 5 参 getModule（ifSaveModule=true）落本地缓存，
                // 避免 runAgentNode 的 getMyModule 再建一个实例（首轮双实例）
                Object inst = getModule(nid, node.getSameClassAs(), false, true, nodeParams);
                if (inst != null) {
                    putLog("Workflow dynamic node [" + nid + "] created (sameClassAs="
                            + node.getSameClassAs() + ")", LogLevel.DEBUG);
                } else {
                    putLog("Workflow dynamic node [" + nid + "] create failed (sameClassAs="
                            + node.getSameClassAs() + ")", LogLevel.ERROR);
                }
            } else if (node.getClassfile() != null) {
                Object inst = getModule(nid, node.getClassfile(), false, true, null);
                if (inst != null) {
                    putLog("Workflow dynamic node [" + nid + "] created (classfile="
                            + node.getClassfile() + ")", LogLevel.DEBUG);
                } else {
                    putLog("Workflow dynamic node [" + nid + "] create failed (classfile="
                            + node.getClassfile() + ")", LogLevel.ERROR);
                }
            }
            // 无 sameClassAs 无 classfile：使用 module 字段指向工厂单例，runAgentNode 回退分支处理
        }

        // 构建上下文并执行
        TLWorkflowContext context = new TLWorkflowContext(input);
        TLWorkflowEngine engine = new TLWorkflowEngine(workflowNodes, workflowEdges, context, this);
        engine.setMaxParallel(maxParallel);
        engine.setWorkflowTimeout(workflowTimeout);

        putLog("Workflow [" + name + "] starting: " + workflowNodes.size()
                + " nodes, " + workflowEdges.size() + " edges", LogLevel.INFO);

        long t0 = System.currentTimeMillis();
        TLMsg result = engine.execute();
        long elapsed = System.currentTimeMillis() - t0;

        // 构建返回
        TLMsg ret = createMsg()
                .setParam(RESULT, result.parseBoolean(RESULT, false))
                .setParam("elapsed", elapsed)
                .setParam(WORKFLOW_OUTPUT, context.getNodeOutputs())
                .setParam("completedNodes", result.getParam("completedCount"))
                .setParam("failedNodes", result.getParam("failedCount"))
                .setParam("skippedNodes", result.getParam("skippedCount"))
                .setParam("message", result.getStringParam("message", ""));

        // 透传完成/未完成节点列表
        ret.setParam(COMPLETEDMSGLIST, result.getParam(COMPLETEDMSGLIST));
        ret.setParam(UNCOMPLETEDMSGLIST, result.getParam(UNCOMPLETEDMSGLIST));
        ret.setParam("nodeOutputs", result.getParam("nodeOutputs"));
        ret.setParam("nodeStatus", result.getParam("nodeStatus"));

        // 汇总文本：按节点声明顺序拼接 AGENT 节点产出。
        // JOIN/FANOUT/CONDITION 是管线节点——其 output 为 addArgs 覆盖式合并的上游产物，
        // 混入汇总会产生【__eN】冗余段（内容还是最后上游的 aiResponse 重复）；
        // 且 nodeOutputs 是 ConcurrentHashMap，迭代顺序随机，必须按声明序遍历
        StringBuilder summary = new StringBuilder();
        Map<String, TLMsg> outputs = context.getNodeOutputs();
        for (String nid : workflowNodes.keySet()) {
            TLWorkflowNode node = workflowNodes.get(nid);
            if (node.getType() != TLWorkflowNodeType.AGENT) continue;
            TLMsg v = outputs.get(nid);
            if (v != null) {
                String resp = v.getStringParam("aiResponse", "");
                if (resp != null && !resp.isEmpty()) {
                    summary.append("【").append(nid).append("】\n").append(resp).append("\n\n");
                }
            }
        }
        ret.setParam(AI_P_RESPONSE, summary.toString().trim());

        putLog("Workflow [" + name + "] done in " + elapsed + "ms: "
                + result.getParam("completedCount") + "/" + result.getParam("totalNodes")
                + " completed, " + result.getParam("failedCount") + " failed",
                result.parseBoolean(RESULT, false) ? LogLevel.INFO : LogLevel.WARN);

        return ret;
    }

    /** 从 Map 解析动态节点 */
    private Map<String, TLWorkflowNode> parseDynamicNodes(Map<String, Map<String, String>> nodeMaps) {
        Map<String, TLWorkflowNode> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : nodeMaps.entrySet()) {
            Map<String, String> attrs = e.getValue();
            TLWorkflowNode node = new TLWorkflowNode();
            node.setId(e.getKey());
            String typeStr = attrs.getOrDefault("type", "AGENT").toUpperCase();
            try {
                node.setType(TLWorkflowNodeType.valueOf(typeStr));
            } catch (IllegalArgumentException iae) {
                node.setType(TLWorkflowNodeType.AGENT);
            }
            node.setModule(attrs.get("module"));
            node.setSameClassAs(attrs.get("sameClassAs"));
            node.setClassfile(attrs.get("classfile"));
            node.setAction(attrs.getOrDefault("action", "chat"));
            if (attrs.containsKey("timeout")) {
                try { node.setTimeout(Long.parseLong(attrs.get("timeout"))); }
                catch (NumberFormatException nfe) {
                    putLog("Workflow dynamic node [" + e.getKey() + "] invalid timeout: "
                            + attrs.get("timeout"), LogLevel.WARN);
                }
            }
            if (attrs.containsKey("onFailure"))
                node.setOnFailure(attrs.get("onFailure"));
            if (attrs.containsKey("maxRetries")) {
                try { node.setMaxRetries(Integer.parseInt(attrs.get("maxRetries"))); }
                catch (NumberFormatException nfe) {
                    putLog("Workflow dynamic node [" + e.getKey() + "] invalid maxRetries: "
                            + attrs.get("maxRetries"), LogLevel.WARN);
                }
            }
            if (attrs.containsKey("fallbackNodeId"))
                node.setFallbackNodeId(attrs.get("fallbackNodeId"));

            // 额外参数（如 expression）存入 params
            Map<String, String> params = new HashMap<>(attrs);
            params.remove("type"); params.remove("module"); params.remove("action");
            params.remove("sameClassAs"); params.remove("classfile");
            params.remove("timeout"); params.remove("onFailure");
            params.remove("maxRetries"); params.remove("fallbackNodeId");
            if (!params.isEmpty()) node.setParams(params);

            result.put(e.getKey(), node);
        }
        return result;
    }

    /** 从 List&lt;Map&gt; 解析动态边 */
    private List<TLWorkflowEdge> parseDynamicEdges(List<Map<String, String>> edgeMaps) {
        List<TLWorkflowEdge> result = new ArrayList<>();
        for (Map<String, String> attrs : edgeMaps) {
            TLWorkflowEdge edge = new TLWorkflowEdge();
            edge.setFrom(attrs.get("from"));
            edge.setTo(attrs.get("to"));
            edge.setCondition(attrs.get("condition"));
            result.add(edge);
        }
        return result;
    }

    // ======================== NodeRunner 实现 ========================

    @Override
    public TLWorkflowEngine.NodeTask runAgentNode(TLWorkflowNode node, TLMsg nodeInput) {
        String nid = node.getId();
        TLMsg msg = createMsg()
                .setAction(node.getAction() != null ? node.getAction() : "chat")
                .setWaitFlag(false);

        // 从 nodeInput 中取 userMessage
        Object upstreamListObj = nodeInput != null ? nodeInput.getParam("upstreamResponses") : null;
        boolean hasUpstream = upstreamListObj instanceof List && !((List) upstreamListObj).isEmpty();
        if (nodeInput != null) {
            // 先合并上游 args（注意 nodeInput args 里带原始 userMessage，
            // 若后合并会把构造好的提示词覆盖掉——必须先合并后覆盖）
            msg.addArgs(nodeInput.getArgs());
            String userMessage = nodeInput.getStringParam("userMessage", "");
            // -> 顺序链数据流：上游产出正文合并进本节点的提示词。
            // 原始任务不前置——它是上游节点的职责，前置会误导下游
            //（如把"写诗"任务带给评判节点，评判节点也会去写诗）；
            // 下游职责由其 systemMessage 定义，任务指令可经 <modules> 条目的 task 参数声明
            if (hasUpstream) {
                StringBuilder sb = new StringBuilder();
                for (Object o : (List) upstreamListObj) sb.append(o).append("\n\n");
                userMessage = sb.toString().trim();
            }
            if (!userMessage.isEmpty()) {
                msg.setParam(AI_P_USERMESSAGE, userMessage);
            }
        }

        // 节点参数透传（temperature、systemMessage 等），运行时覆盖模块默认值
        if (node.getParams() != null) {
            for (Map.Entry<String, String> e : node.getParams().entrySet()) {
                msg.setParam(e.getKey(), e.getValue());
            }
        }

        // 会话上下文透传：与 TLAgentGroup 同模式。rootSessionId 保持 stopByRoot 级联停止能力；
        // 节点用独立 sid（并行节点不互相污染 aiContext）。sid 照常继承父会话——链不断；
        // 历史是否跨运行累积由节点的 noHistory 配置控制（见 TLAiAgent）
        String baseSession = nodeInput != null ? String.valueOf(nodeInput.getSystemParam(AI_P_SESSIONID, "")) : "";
        String rootSid = nodeInput != null ? String.valueOf(nodeInput.getSystemParam("rootSessionId", "")) : "";
        if (!rootSid.isEmpty()) {
            msg.setSystemParam("rootSessionId", rootSid);
        }
        msg.setSystemParam(AI_P_SESSIONID, name + "_" + nid + ":"
                + (baseSession.isEmpty() ? System.currentTimeMillis() : baseSession));
        if (nodeInput != null && nodeInput.containsSystemParam("userId")) {
            msg.setSystemParam("userId", nodeInput.getSystemParam("userId", ""));
        }
        // 全链追踪：上游 roundId 透传——工作流节点的一轮就是上游（控制台）的一轮
        if (nodeInput != null && nodeInput.containsSystemParam(AI_P_ROUNDID)) {
            msg.setSystemParam(AI_P_ROUNDID, nodeInput.getSystemParam(AI_P_ROUNDID, ""));
        }

        // 节点 id 即模块名（<modules> 中定义），getMyModule 创建/获取自有实例。
        // 节点自身任务指令由 TLAiAgent 层统一处理（task 参数前置到 user 消息），此处无需重复
        TLMsg ret;
        TLBaseModule target = (TLBaseModule) getMyModule(nid);
        if (target != null) {
            ret = putMsg(target, msg);
        } else if (node.getModule() != null) {
            msg.setDestination(node.getModule());
            ret = putMsg(msg);
        } else {
            putLog("Workflow node [" + nid + "] has no module target", LogLevel.ERROR);
            return null;
        }

        if (ret != null && !ret.isNull(THREADPOOL_TASK)) {
            ThreadTask threadTask = (ThreadTask) ret.getParam(THREADPOOL_TASK);
            return new NodeTaskAdapter(threadTask);
        }
        return null;
    }

    /** ThreadTask → NodeTask 适配器 */
    private static class NodeTaskAdapter implements TLWorkflowEngine.NodeTask {
        private final ThreadTask task;
        NodeTaskAdapter(ThreadTask task) { this.task = task; }
        @Override public boolean isOver() { return task.isThreadOver(); }
        @Override public TLMsg getResult() { return task.getResult(); }
    }

    // ======================== XML 配置解析 ========================

    protected class myConfig extends TLModuleConfig {
        private LinkedHashMap<String, TLWorkflowNode> nodes = new LinkedHashMap<>();
        private List<TLWorkflowEdge> edges = new ArrayList<>();

        public myConfig() {}
        public myConfig(String configFile, String configDir) {
            super(configFile, configDir);
        }

        public LinkedHashMap<String, TLWorkflowNode> getNodes() { return nodes; }
        public List<TLWorkflowEdge> getEdges() { return edges; }

        @Override
        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                switch (xpp.getName()) {
                    case "nodes":
                        parseNodes(xpp);
                        break;
                    case "edges":
                        parseEdges(xpp);
                        break;
                }
            } catch (Throwable t) {
                putLog("TLAgentWorkflow config parse error: " + t, LogLevel.WARN);
            }
        }

        private void parseNodes(XmlPullParser xpp) throws Throwable {
            while (true) {
                xpp.next();
                if (isEndTag(xpp, "nodes") || xpp.getEventType() == XmlPullParser.END_DOCUMENT)
                    break;
                if (xpp.getEventType() == XmlPullParser.START_TAG && xpp.getName().equals("node")) {
                    TLWorkflowNode node = new TLWorkflowNode();
                    String id = xpp.getAttributeValue(null, "id");
                    node.setId(id);

                    String typeStr = xpp.getAttributeValue(null, "type");
                    if (typeStr != null) {
                        try {
                            node.setType(TLWorkflowNodeType.valueOf(typeStr.toUpperCase()));
                        } catch (IllegalArgumentException e) {
                            node.setType(TLWorkflowNodeType.AGENT);
                        }
                    }

                    node.setModule(xpp.getAttributeValue(null, "module"));
                    node.setSameClassAs(xpp.getAttributeValue(null, "sameClassAs"));
                    node.setClassfile(xpp.getAttributeValue(null, "classfile"));
                    String action = xpp.getAttributeValue(null, "action");
                    if (action != null) node.setAction(action);

                    // 解析 <param> 子元素
                    Map<String, String> params = new HashMap<>();
                    parseParams(xpp, "node", params);
                    node.setParams(params);

                    if (params.containsKey("timeout")) {
                        try { node.setTimeout(Long.parseLong(params.get("timeout"))); }
                        catch (NumberFormatException e) {
                            putLog("Workflow node [" + id + "] invalid timeout: "
                                    + params.get("timeout"), LogLevel.WARN);
                        }
                    }
                    if (params.containsKey("onFailure"))
                        node.setOnFailure(params.get("onFailure"));
                    if (params.containsKey("maxRetries")) {
                        try { node.setMaxRetries(Integer.parseInt(params.get("maxRetries"))); }
                        catch (NumberFormatException e) {
                            putLog("Workflow node [" + id + "] invalid maxRetries: "
                                    + params.get("maxRetries"), LogLevel.WARN);
                        }
                    }
                    if (params.containsKey("fallbackNodeId"))
                        node.setFallbackNodeId(params.get("fallbackNodeId"));

                    nodes.put(id, node);
                }
            }
        }

        private void parseEdges(XmlPullParser xpp) throws Throwable {
            while (true) {
                xpp.next();
                if (isEndTag(xpp, "edges") || xpp.getEventType() == XmlPullParser.END_DOCUMENT)
                    break;
                if (xpp.getEventType() == XmlPullParser.START_TAG && xpp.getName().equals("edge")) {
                    TLWorkflowEdge edge = new TLWorkflowEdge();
                    edge.setFrom(xpp.getAttributeValue(null, "from"));
                    edge.setTo(xpp.getAttributeValue(null, "to"));
                    edge.setCondition(xpp.getAttributeValue(null, "condition"));
                    edges.add(edge);
                }
            }
        }

        /** 解析 node 下的 <param> 子元素 */
        private void parseParams(XmlPullParser xpp, String parentTag,
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
                if (xpp.getEventType() == XmlPullParser.START_TAG && xpp.getName().equals("param")) {
                    String key = xpp.getAttributeValue(null, "name");
                    String value = xpp.getAttributeValue(null, "value");
                    if (key != null) params.put(key, value != null ? value : "");
                }
            }
        }

        private boolean isEndTag(XmlPullParser xpp, String tagName)
                throws org.xmlpull.v1.XmlPullParserException {
            return xpp.getEventType() == XmlPullParser.END_TAG && xpp.getName().equals(tagName);
        }
    }
}
