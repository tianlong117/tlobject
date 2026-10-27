package cn.tianlong.tlobject.aiagent.mcp;

import cn.tianlong.tlobject.aiagent.*;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

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
public class TLMcpAgent extends TLBaseModule implements TLAiAgentParamString, IAgentCapable {

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

    /** MCP 客户端标识（连接握手用，init/重连共用） */
    private String clientName;
    private String clientVersion;

    /** 惰性重连退避间隔（毫秒），防每次被访问都重试形成风暴 */
    private long retryIntervalMs = 60_000;
    private volatile long lastConnectAttempt;

    /**
     * 单次响应读超时（毫秒，0=不限；-1=用 transport 默认）。
     * 未配置时：SSE 沿用 OkHttp 默认 120s；stdio 不限（长任务工具不被打断）。
     * 长耗时 MCP 工具（构建/抓取）可调大或设 0；卡死的 Server 可设小值让它被强杀重连。
     */
    private long readTimeoutMs = -1L;

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
            if (params.get("retryIntervalMs") != null) {
                try { retryIntervalMs = Long.parseLong(params.get("retryIntervalMs")); }
                catch (NumberFormatException ignored) {}
            }
            if (params.get("readTimeoutMs") != null) {
                try { readTimeoutMs = Long.parseLong(params.get("readTimeoutMs")); }
                catch (NumberFormatException ignored) {}
            }
        }
    }

    /**
     * 解析 args 参数：支持 JSON 数组格式（如 ["-y", "@pkg"]，参数含空格时用），
     * 否则按空格分割（兼容旧配置）。
     */
    private String[] parseArgs(String argsString) {
        if (argsString == null || argsString.trim().isEmpty()) return new String[0];
        String s = argsString.trim();
        if (s.startsWith("[")) {
            try {
                java.util.List<String> list = new Gson().fromJson(s,
                        new TypeToken<java.util.List<String>>() {}.getType());
                if (list != null && !list.isEmpty()) {
                    return list.toArray(new String[0]);
                }
            } catch (Exception e) {
                putLog("MCP Agent [" + name + "]: args JSON 解析失败，回退空格分割: " + argsString, LogLevel.WARN);
            }
        }
        return s.split("\\s+");
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
            transport = readTimeoutMs >= 0
                    ? new SseMcpTransport(url, readTimeoutMs, (msg, level) -> putLog(msg, level))
                    : new SseMcpTransport(url, (msg, level) -> putLog(msg, level));
        } else {
            // 默认 stdio
            if (command == null || command.isEmpty()) {
                putLog("MCP Agent [" + agentName + "]: stdio transport requires 'command' parameter", LogLevel.ERROR);
                return this;
            }
            String[] args = parseArgs(argsString);
            // 未配置 readTimeoutMs 时 stdio 保持不限时（长任务工具不被打断）
            transport = new StdioMcpTransport(command, args,
                    readTimeoutMs >= 0 ? readTimeoutMs : 0L, (msg, level) -> putLog(msg, level));
        }

        // 连接并发现工具
        this.clientName = "tlobject-mcp-agent/" + agentName;
        this.clientVersion = "1.0";
        try {
            connectInternal();
        } catch (Exception e) {
            initialized = false;
            putLog("MCP Agent [" + agentName + "]: INIT FAILED - " + e.toString()
                    + " (will retry on demand, interval " + retryIntervalMs + "ms)", LogLevel.ERROR);
        }

        return this;
    }

    /** 执行一次连接握手 + 工具发现（init 与惰性重连共用） */
    private void connectInternal() throws Exception {
        try {
            transport.connect(clientName, clientVersion);
            // tools/list 失败 = 连接不可用。不能只打个日志就 initialized=true：
            // 那样惰性重连的快速路径会直接返回，Agent 会永远拿着空/过期的工具表不再重试。
            if (!refreshTools()) {
                throw new Exception("tools/list failed after connect");
            }
        } catch (Exception e) {
            // 清掉半开连接（stdio 握手失败已自行 disconnect，重复调用幂等），等退避后整体重连
            transport.disconnect();
            throw e;
        }
        initialized = true;
        putLog("MCP Agent [" + agentName + "]: connected, " + tools.size() + " tools discovered", LogLevel.INFO);

        for (McpTool tool : tools.values()) {
            String funcName = agentName + "_" + tool.getName();
            functionNameToTool.put(funcName, tool.getName());
            putLog("    ▸ " + funcName + " - " + tool.getDescription(), LogLevel.DEBUG);
        }
    }

    /**
     * 惰性重连：init 失败或连接中断后，下次被主控访问时按退避间隔重试。
     * AGENT_GETTOOLDEFS / callTool / refreshTools 入口调用，
     * 避免"工具瞬间不可用"，同时退避保证不形成重试风暴。
     */
    private void ensureInitialized() {
        // 快路径无锁：并行 tool 调用都抢同一把锁会把每次调用串行化（npx 启动+握手可达数秒）
        if (initialized && transport != null && transport.isConnected()) return;
        synchronized (this) {
            // 双检：等锁期间别的线程可能已经重连成功
            if (initialized && transport != null && transport.isConnected()) return;
            long now = System.currentTimeMillis();
            if (now - lastConnectAttempt < retryIntervalMs) return;
            lastConnectAttempt = now;
            try {
                connectInternal();
            } catch (Exception e) {
                initialized = false;
                putLog("MCP Agent [" + agentName + "] reconnect failed: " + e.toString(), LogLevel.WARN);
            }
        }
    }

    @Override
    public void runStartMsg() {
        super.runStartMsg();
        if (initialized) {
            putLog("MCP Agent [" + agentName + "]: " + tools.size()
                    + " tools ready via " + transportType, LogLevel.INFO);
        } else {
            putLog("MCP Agent [" + agentName + "]: NOT initialized, tools unavailable", LogLevel.WARN);
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
                ensureInitialized();
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
            ensureInitialized();  // 惰性重连（含退避），仍不可用再报错
        }
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
        } catch (McpJsonRpc.McpRpcException e) {
            // 协议级错误（工具参数不合法/工具内部报错）：连接本身正常，不能触发重连
            putLog("MCP tool [" + toolName + "] rpc error: " + e, LogLevel.ERROR);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error executing MCP tool " + toolName + ": " + e.getMessage())
                    .setParam(AI_P_TOOLNAME, toolName);
        } catch (Exception e) {
            putLog("MCP tool [" + toolName + "] error: " + e.toString(), LogLevel.ERROR);
            // 传输级错误（断连/超时/HTTP 错误）：标记断开，下次访问走 ensureInitialized 重连。
            // SSE 的 isConnected 只是连接旗标，服务器挂了它不会自己变 false——不在这里断开
            // 就永远走不到惰性重连分支。
            try { transport.disconnect(); } catch (Exception ignored) {}
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
     * 重新发现工具列表（调用 MCP tools/list）。
     *
     * @return true=发现成功（工具表已刷新）；false=失败（保留旧工具表，由调用方决定是否算连接可用）。
     *         失败时保留旧表是刻意策略：手工 refresh 遇到网络抖动不该瞬间清空工具。
     */
    private boolean refreshTools() {
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
            return true;
        } catch (Exception e) {
            putLog("MCP Agent [" + agentName + "] tools/list failed: " + e.toString(), LogLevel.ERROR);
            return false;
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
        ensureInitialized();
        // 手工刷新失败时保留旧工具表（见 refreshTools 注释），refreshed 让调用方知道这次没刷成
        boolean refreshed = initialized && refreshTools();
        return createMsg().setParam(RESULT, true)
                .setParam("refreshed", refreshed)
                .setParam("toolCount", tools.size());
    }
}
