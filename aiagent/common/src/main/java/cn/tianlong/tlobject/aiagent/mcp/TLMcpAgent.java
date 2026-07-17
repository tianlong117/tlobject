package cn.tianlong.tlobject.aiagent.mcp;

import cn.tianlong.tlobject.aiagent.*;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Agent——MCP Server 的轻量桥接模块。
 *
 * 不作为完整的 AI Agent（无 LLM、无 Context、无 Memory），只做协议翻译：
 * LLM function call → MCP tools/call。
 *
 * 在主控 Agent 的 <agents> 段中配置，type="mcp" 声明其行为模式。
 * 每个 MCP Agent 实例管理一条到 MCP Server 的连接（stdio 或 SSE），
 * 将从 tools/list 发现的全部 tool 作为 function definitions 暴露给主控。
 *
 * 创建日期：2026/7/7
 * 作者:tianlong
 */
public class TLMcpAgent extends TLBaseModule implements TLAiAgentParamString {

    // ======================== 配置字段 ========================

    /** Transport 类型：stdio 或 sse */
    private String transportType;

    /** stdio: 启动命令（如 npx, node） */
    private String command;

    /** stdio: 命令参数（空格分隔，在 myConfig 中解析为数组） */
    private String argsString;

    /** sse: MCP Server URL */
    private String url;

    /** Agent 描述（来自 XML description 属性） */
    private String agentDescription;

    /** 工具白名单（逗号分隔），为空则全部暴露 */
    private String toolFilter;

    // ======================== 运行时字段 ========================

    /** Transport 实例 */
    private McpTransport transport;

    /** 已发现的工具：toolName → McpTool */
    private Map<String, McpTool> tools;

    /** function name → toolName（用于路由，functionName = agentName_toolName） */
    private final Map<String, String> functionNameToTool;

    /** JSON-RPC 工具 */
    private McpJsonRpc jsonRpc;

    /** 是否已初始化（连接成功 + tools 发现） */
    private volatile boolean initialized = false;

    /** 当前 Agent 名称 */
    private String agentName;

    // ======================== 构造函数 ========================

    public TLMcpAgent() {
        super();
        this.functionNameToTool = new ConcurrentHashMap<>();
    }

    public TLMcpAgent(String name) {
        super(name);
        this.functionNameToTool = new ConcurrentHashMap<>();
    }

    public TLMcpAgent(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
        this.functionNameToTool = new ConcurrentHashMap<>();
    }

    // ======================== 生命周期 ========================

    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("transport") != null)
                transportType = params.get("transport");
            if (params.get("command") != null)
                command = params.get("command");
            if (params.get("args") != null)
                argsString = params.get("args");
            if (params.get("url") != null)
                url = params.get("url");
            if (params.get("description") != null)
                agentDescription = params.get("description");
            if (params.get("tools") != null)
                toolFilter = params.get("tools");
        }
    }

    @Override
    protected TLBaseModule init() {
        this.agentName = name;
        this.tools = new ConcurrentHashMap<>();
        this.jsonRpc = new McpJsonRpc();

        // 创建 transport
        if ("sse".equalsIgnoreCase(transportType)) {
            if (url == null || url.isEmpty()) {
                putLog("MCP Agent [" + agentName + "]: SSE transport requires 'url' parameter", LogLevel.ERROR);
                return this;
            }
            transport = new SseMcpTransport(url, (msg, level) ->
                    putLog(msg, level));
        } else {
            // 默认 stdio
            if (command == null || command.isEmpty()) {
                putLog("MCP Agent [" + agentName + "]: stdio transport requires 'command' parameter", LogLevel.ERROR);
                return this;
            }
            String[] args = (argsString != null && !argsString.isEmpty())
                    ? argsString.split("\\s+") : new String[0];
            transport = new StdioMcpTransport(command, args, (msg, level) ->
                    putLog(msg, level));
        }

        // 连接并发现工具
        try {
            String clientName = "tlobject-mcp-agent/" + agentName;
            String clientVersion = "1.0";
            transport.connect(clientName, clientVersion);
            refreshTools();
            initialized = true;
            System.out.println("  MCP Agent [" + agentName + "]: connected, " + tools.size() + " tools discovered");

            for (McpTool tool : tools.values()) {
                String funcName = agentName + "_" + tool.getName();
                functionNameToTool.put(funcName, tool.getName());
                System.out.println("    ▸ " + funcName + " - " + tool.getDescription());
            }
        } catch (Exception e) {
            initialized = false;
            System.out.println("  MCP Agent [" + agentName + "]: INIT FAILED - " + e.toString());
            putLog("MCP Agent [" + agentName + "] init failed: " + e.toString(), LogLevel.ERROR);
        }

        return this;
    }

    @Override
    public void runStartMsg() {
        super.runStartMsg();
        if (initialized) {
            System.out.println("  MCP Agent [" + agentName + "]: " + tools.size()
                    + " tools ready via " + transportType);
        } else {
            System.out.println("  MCP Agent [" + agentName + "]: NOT initialized, tools unavailable");
        }
    }

    // ======================== 消息分发 ========================

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "callTool":
                returnMsg = handleCallTool(fromWho, msg);
                break;
            case "listTools":
                returnMsg = handleListTools(fromWho, msg);
                break;
            case "refreshTools":
                returnMsg = handleRefreshTools(fromWho, msg);
                break;
            case AGENT_GETTOOLDEFS:
                // 向 master 贡献工具定义（统一子 agent 协议）：defs + functionName→toolName 路由映射
                returnMsg = createMsg().setParam(RESULT, true)
                        .setParam(AI_P_FUNCTIONDEFS, getToolDefinitions())
                        .setParam(AI_P_TOOLROUTES, new LinkedHashMap<>(functionNameToTool));
                break;
            case AGENT_GETDESCRIPTION:
                // 与 TLAiAgent/TLAgentGroup 对称（MCP 的 delegate 描述场景少，但协议统一）
                returnMsg = createMsg().setParam(RESULT, true)
                        .setParam(AI_P_AGENTDESCRIPTION, getAgentDescription());
                break;
            default:
                returnMsg = null;
        }
        return returnMsg;
    }

    // ======================== 给主控调用的公开方法 ========================

    /**
     * 返回所有已发现 tool 的 TLFunctionDefinition 列表，
     * 供主控 Agent 的 buildFunctionDefinitions() 使用。
     * function name 格式：{agentName}_{toolName}
     */
    @SuppressWarnings("unchecked")
    public List<TLFunctionDefinition> getToolDefinitions() {
        List<TLFunctionDefinition> defs = new ArrayList<>();
        for (McpTool tool : tools.values()) {
            String funcName = agentName + "_" + tool.getName();
            Map<String, Object> inputSchema = tool.getInputSchema();

            // MCP inputSchema 是完整的 JSON Schema: {type, properties, required, $schema}
            // TLFunctionDefinition.fromSkill() 接受 properties map 并自行包装
            // 需要提取 properties，剥离 $schema 等元数据字段
            Map<String, Object> cleanProperties = new LinkedHashMap<>();
            if (inputSchema != null && inputSchema.containsKey("properties")) {
                Map<String, Object> props = (Map<String, Object>) inputSchema.get("properties");
                if (props != null) {
                    for (Map.Entry<String, Object> entry : props.entrySet()) {
                        Map<String, Object> propDef = new LinkedHashMap<>((Map<String, Object>) entry.getValue());
                        // 移除 $schema 等 LLM API 不识别的字段
                        propDef.remove("$schema");
                        cleanProperties.put(entry.getKey(), propDef);
                    }
                }
                // 处理 MCP 的 required 数组：给对应属性标记 required=true
                if (inputSchema.containsKey("required")) {
                    Object requiredObj = inputSchema.get("required");
                    if (requiredObj instanceof List) {
                        for (String reqName : (List<String>) requiredObj) {
                            if (cleanProperties.containsKey(reqName)) {
                                Map<String, Object> propDef = (Map<String, Object>) cleanProperties.get(reqName);
                                propDef.put("required", true);
                            }
                        }
                    }
                }
            }

            TLFunctionDefinition def = TLFunctionDefinition.fromSkill(
                    funcName, tool.getDescription(), cleanProperties);
            defs.add(def);
        }
        return defs;
    }

    /**
     * 执行指定的 tool。由主控 executeToolCall() 调用。
     *
     * @param toolName 原始 MCP tool 名称（如 "read_file"）
     * @param arguments tool 输入参数
     * @return 执行结果 TLMsg
     */
    public TLMsg callTool(String toolName, Map<String, Object> arguments) {
        if (!initialized || transport == null || !transport.isConnected()) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error: MCP Agent not initialized or disconnected");
        }

        McpTool tool = tools.get(toolName);
        if (tool == null) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error: MCP tool not found: " + toolName);
        }

        try {
            Map<String, Object> callParams = jsonRpc.buildToolCallParams(toolName, arguments);
            String request = jsonRpc.buildRequest("tools/call", callParams);
            String response = transport.send(request);

            com.google.gson.JsonObject result = jsonRpc.parseResponse(response);
            String output = jsonRpc.parseToolCallResult(result);

            putLog("MCP tool [" + toolName + "] executed successfully", LogLevel.DEBUG);

            return createMsg().setParam(RESULT, true)
                    .setParam(AI_P_SKILLOUTPUT, output)
                    .setParam(AI_P_TOOLNAME, toolName);
        } catch (Exception e) {
            putLog("MCP tool [" + toolName + "] error: " + e.toString(), LogLevel.ERROR);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error executing MCP tool " + toolName + ": " + e.getMessage())
                    .setParam(AI_P_TOOLNAME, toolName);
        }
    }

    /**
     * 获取 Agent 描述
     */
    public String getAgentDescription() {
        return agentDescription != null ? agentDescription : "MCP Agent: " + agentName;
    }

    /**
     * 检查是否已初始化成功
     */
    public boolean isInitialized() {
        return initialized;
    }

    // ======================== 内部方法 ========================

    /**
     * 重新发现工具列表（调用 MCP tools/list）
     */
    private void refreshTools() {
        try {
            String request = jsonRpc.buildRequest("tools/list");
            String response = transport.send(request);

            com.google.gson.JsonObject result = jsonRpc.parseResponse(response);
            List<McpTool> toolList = jsonRpc.parseToolsList(result);

            // 解析白名单
            Set<String> whitelist = null;
            if (toolFilter != null && !toolFilter.trim().isEmpty()) {
                whitelist = new HashSet<>();
                for (String name : toolFilter.split(",")) {
                    whitelist.add(name.trim());
                }
            }

            tools.clear();
            functionNameToTool.clear();
            int filtered = 0;
            for (McpTool tool : toolList) {
                if (whitelist != null && !whitelist.contains(tool.getName())) {
                    filtered++;
                    continue;
                }
                tools.put(tool.getName(), tool);
                String funcName = agentName + "_" + tool.getName();
                functionNameToTool.put(funcName, tool.getName());
            }

            String msg = "MCP Agent [" + agentName + "] discovered " + toolList.size()
                    + " tools, exposed " + tools.size();
            if (filtered > 0) msg += " (filtered out " + filtered + ")";
            putLog(msg, LogLevel.DEBUG);
        } catch (Exception e) {
            putLog("MCP Agent [" + agentName + "] tools/list failed: " + e.toString(), LogLevel.ERROR);
        }
    }

    /**
     * 根据 function name 解析出 toolName。格式：{agentName}_{toolName}
     */
    public String getToolNameByFunctionName(String functionName) {
        return functionNameToTool.get(functionName);
    }

    // ======================== 消息处理方法 ========================

    private TLMsg handleCallTool(Object fromWho, TLMsg msg) {
        String toolName = msg.getStringParam(AI_P_TOOLNAME, "");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = msg.getMapParam(AI_P_TOOLARGUMENTS, new LinkedHashMap<>());
        if (toolName.isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error: toolName required");
        }
        return callTool(toolName, args);
    }

    private TLMsg handleListTools(Object fromWho, TLMsg msg) {
        List<Map<String, Object>> toolInfos = new ArrayList<>();
        for (McpTool tool : tools.values()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", tool.getName());
            info.put("description", tool.getDescription());
            toolInfos.add(info);
        }
        return createMsg().setParam(RESULT, true).setParam("tools", toolInfos);
    }

    private TLMsg handleRefreshTools(Object fromWho, TLMsg msg) {
        refreshTools();
        return createMsg().setParam(RESULT, true).setParam("toolCount", tools.size());
    }
}
