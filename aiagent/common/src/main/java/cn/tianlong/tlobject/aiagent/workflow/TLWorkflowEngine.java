package cn.tianlong.tlobject.aiagent.workflow;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLParamString;

import java.util.*;
import java.util.concurrent.*;

/**
 * DAG 工作流执行引擎。
 * 核心算法：拓扑排序验证 → 就绪节点并行调度 → 条件分支级联跳过。
 * 通过 NodeRunner 接口委托宿主模块执行 AGENT 节点（走框架线程池），
 * CONDITION/JOIN/FANOUT 引擎内联处理。
 *
 * @author tianlong
 * @since 2026/7/26
 */
public class TLWorkflowEngine implements TLParamString {

    /** 引擎委托宿主执行 AGENT 节点后返回的任务句柄 */
    public interface NodeTask {
        boolean isOver();
        TLMsg getResult();
    }

    /** 宿主模块提供的节点执行能力 */
    public interface NodeRunner {
        /** @param node 节点定义
         *  @param nodeInput 引擎收集的节点输入（上游产出 + 工作流初始输入） */
        NodeTask runAgentNode(TLWorkflowNode node, TLMsg nodeInput);
    }

    private final Map<String, TLWorkflowNode> nodes;
    private final Map<String, List<TLWorkflowEdge>> outEdges;
    private final Map<String, List<TLWorkflowEdge>> inEdges;
    private final Map<String, Integer> inDegree;
    private final TLWorkflowContext context;
    private final NodeRunner runner;

    private int maxParallel = 10;
    private long workflowTimeout;

    private final Set<String> completed = ConcurrentHashMap.newKeySet();
    private final Set<String> skipped = ConcurrentHashMap.newKeySet();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private final Map<String, NodeTask> running = new ConcurrentHashMap<>();

    /** 节点重试计数（onFailure="retry" 时按节点累积） */
    private final Map<String, Integer> retryCounts = new ConcurrentHashMap<>();

    /**
     * 节点"活"入边激活计数（上游真实完成 / CONDITION 命中边）。
     * join-aware 跳过传播用：分辨"所有入边都已死（该跳过）"与"有活上游（该就绪）"。
     */
    private final Map<String, Integer> liveIncoming = new ConcurrentHashMap<>();

    public TLWorkflowEngine(Map<String, TLWorkflowNode> nodes,
                            List<TLWorkflowEdge> edges,
                            TLWorkflowContext context,
                            NodeRunner runner) {
        this.nodes = nodes;
        this.context = context;
        this.runner = runner;

        this.outEdges = new HashMap<>();
        this.inEdges = new HashMap<>();
        for (String nid : nodes.keySet()) {
            outEdges.put(nid, new ArrayList<>());
            inEdges.put(nid, new ArrayList<>());
        }
        for (TLWorkflowEdge edge : edges) {
            outEdges.computeIfAbsent(edge.getFrom(), k -> new ArrayList<>()).add(edge);
            inEdges.computeIfAbsent(edge.getTo(), k -> new ArrayList<>()).add(edge);
        }

        this.inDegree = new ConcurrentHashMap<>();
        for (String nid : nodes.keySet()) {
            inDegree.put(nid, inEdges.get(nid).size());
        }
    }

    public void setMaxParallel(int maxParallel) { this.maxParallel = maxParallel; }
    public void setWorkflowTimeout(long timeout) { this.workflowTimeout = timeout; }

    /**
     * 执行工作流，阻塞直到所有节点完成、超时或死锁。
     */
    public TLMsg execute() {
        long startTime = System.currentTimeMillis();
        long deadline = workflowTimeout > 0 ? startTime + workflowTimeout : Long.MAX_VALUE;

        if (topologicalSort() == null) {
            context.putStatus("_workflow", "CYCLE_DETECTED");
            return buildResult(false, "cycle detected");
        }

        context.putStatus("_workflow", "RUNNING");

        try {
            while (completed.size() + skipped.size() + failed.size() < nodes.size()) {
                if (System.currentTimeMillis() >= deadline) {
                    timeoutRemaining("WORKFLOW_TIMEOUT");
                    break;
                }

                List<String> ready = collectReady();
                if (ready.isEmpty()) {
                    if (running.isEmpty()) break;
                    pollRunningTasks(100);
                    continue;
                }

                int slots = maxParallel - running.size();
                if (slots <= 0) {
                    pollRunningTasks(50);
                    continue;
                }
                if (ready.size() > slots) ready = ready.subList(0, slots);

                for (String nid : ready) {
                    // 防线：同一批就绪列表中，先处理的 CONDITION 可能已跳过列表后半的节点
                    if (skipped.contains(nid) || failed.contains(nid)) continue;
                    TLWorkflowNode node = nodes.get(nid);
                    if (node.getType() == TLWorkflowNodeType.AGENT) {
                        TLMsg nodeInput = buildNodeInput(node);
                        NodeTask task = runner.runAgentNode(node, nodeInput);
                        if (task != null) {
                            running.put(nid, task);
                            context.putStatus(nid, "RUNNING");
                        } else {
                            // 提交失败走统一失败处理：默认策略等价，且 onFailure="skip" 能放行下游（|| 回退的硬失败路径）
                            handleNodeFailure(nid, node);
                        }
                    } else {
                        processBuiltinNode(node);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            context.putStatus("_workflow", "INTERRUPTED");
        }

        long elapsed = System.currentTimeMillis() - startTime;
        boolean allDone = completed.size() + skipped.size() == nodes.size();
        TLMsg result = buildResult(allDone, allDone ? "completed" : "partial");
        result.setParam("elapsed", elapsed);
        return result;
    }

    private List<String> collectReady() {
        List<String> ready = new ArrayList<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            String nid = e.getKey();
            if (e.getValue() == 0
                    && !completed.contains(nid) && !skipped.contains(nid)
                    && !failed.contains(nid) && !running.containsKey(nid)) {
                ready.add(nid);
            }
        }
        return ready;
    }

    private void pollRunningTasks(int sleepMs) throws InterruptedException {
        List<String> doneNow = new ArrayList<>();
        for (Map.Entry<String, NodeTask> e : running.entrySet()) {
            if (e.getValue().isOver()) doneNow.add(e.getKey());
        }
        if (doneNow.isEmpty()) {
            Thread.sleep(sleepMs);
            return;
        }
        for (String nid : doneNow) {
            NodeTask task = running.remove(nid);
            onAgentNodeDone(nid, task.getResult());
        }
    }

    private void onAgentNodeDone(String nid, TLMsg output) {
        TLWorkflowNode node = nodes.get(nid);
        context.putOutput(nid, output);
        context.putDuration(nid, System.currentTimeMillis());

        if (output != null && output.parseBoolean(RESULT, true)) {
            completed.add(nid);
            context.putStatus(nid, "DONE");
            onNodeDone(nid);
        } else {
            handleNodeFailure(nid, node);
        }
    }

    private void processBuiltinNode(TLWorkflowNode node) {
        String nid = node.getId();
        TLMsg upstream = collectUpstreamInput(nid);

        switch (node.getType()) {
            case CONDITION:
                boolean condResult = evalCondition(node, upstream);
                context.putStatus(nid, condResult ? "TRUE" : "FALSE");
                context.putOutput(nid, new TLMsg().setParam(RESULT, condResult));
                completed.add(nid);
                for (TLWorkflowEdge edge : outEdges.getOrDefault(nid, Collections.emptyList())) {
                    if (edgeMatches(edge, condResult)) {
                        decrementInDegree(edge.getTo());
                        liveIncoming.merge(edge.getTo(), 1, Integer::sum);  // 命中边是"活"激活
                    } else {
                        cascadeSkip(edge.getTo());
                    }
                }
                break;

            case JOIN:
            case FANOUT:
                context.putOutput(nid, upstream != null ? upstream : new TLMsg());
                context.putStatus(nid, "DONE");
                completed.add(nid);
                onNodeDone(nid);
                break;

            default:
                completed.add(nid);
                context.putStatus(nid, "DONE");
                onNodeDone(nid);
        }
    }

    private void onNodeDone(String nid) {
        for (TLWorkflowEdge edge : outEdges.getOrDefault(nid, Collections.emptyList())) {
            decrementInDegree(edge.getTo());
            liveIncoming.merge(edge.getTo(), 1, Integer::sum);  // 完成节点的出边是"活"激活
        }
    }

    private void decrementInDegree(String targetId) {
        inDegree.computeIfPresent(targetId, (k, v) -> Math.max(0, v - 1));
    }

    /**
     * join-aware 跳过传播：本入边判定为"死"（decrement），
     * 仅当目标所有入边都已判定且无活上游时才真正跳过并继续传播；
     * 否则留待活分支到达后自然就绪——支持条件分支重建合流（菱形图）。
     */
    private void cascadeSkip(String nodeId) {
        if (skipped.contains(nodeId) || completed.contains(nodeId) || failed.contains(nodeId)
                || running.containsKey(nodeId)) return;
        List<TLWorkflowEdge> in = inEdges.get(nodeId);
        if (in == null || in.isEmpty()) return;  // 防御：无入边源节点不被传播误杀
        decrementInDegree(nodeId);
        int resolved = in.size() - inDegree.getOrDefault(nodeId, 0);
        if (resolved < in.size()) return;                       // 还有入边未判定 → 留待活分支
        if (liveIncoming.getOrDefault(nodeId, 0) > 0) return;   // 有活上游 → 不该跳过
        skipped.add(nodeId);
        context.putStatus(nodeId, "SKIPPED");
        for (TLWorkflowEdge edge : outEdges.getOrDefault(nodeId, Collections.emptyList())) {
            cascadeSkip(edge.getTo());
        }
    }

    private TLMsg collectUpstreamInput(String nodeId) {
        List<TLWorkflowEdge> incoming = inEdges.get(nodeId);
        if (incoming == null || incoming.isEmpty()) return null;
        TLMsg merged = new TLMsg();
        List<String> upstreamResponses = new ArrayList<>();
        for (TLWorkflowEdge edge : incoming) {
            TLMsg up = context.getOutput(edge.getFrom());
            if (up != null) {
                merged.addArgs(up.getArgs());
                // 收集上游正文（addArgs 是覆盖式合并，多个上游的 aiResponse 会互相覆盖丢失）。
                // 已携带 upstreamResponses 的输出（JOIN/FANOUT 汇聚产物）不再重复收录——
                // 其上游正文已随 args 合并进 merged，重复收录会产生【__eN】冗余段
                if (up.getParam("upstreamResponses") == null) {
                    String resp = up.getStringParam("aiResponse", "");
                    if (!resp.isEmpty()) {
                        upstreamResponses.add("【" + edge.getFrom() + "】\n" + resp);
                    }
                }
            }
        }
        if (!upstreamResponses.isEmpty()) {
            merged.setParam("upstreamResponses", upstreamResponses);
        }
        return merged;
    }

    /** 构建节点的完整输入：工作流初始输入 + 上游产出 */
    private TLMsg buildNodeInput(TLWorkflowNode node) {
        TLMsg input = new TLMsg();
        // 先拷贝工作流初始输入（含 userMessage）
        if (context.getInput() != null) {
            input.addArgs(context.getInput().getArgs());
            String um = context.getInput().getStringParam("userMessage", "");
            if (!um.isEmpty()) input.setParam("userMessage", um);
        }
        // 再合并上游产出
        TLMsg upstream = collectUpstreamInput(node.getId());
        if (upstream != null) input.addArgs(upstream.getArgs());
        // 会话上下文最后覆盖：上游 agent 的返回消息自带其 sessionId，
        // 合并后会把工作流级会话覆盖掉（sid 链污染）——此处恢复初始值
        if (context.getInput() != null) {
            TLMsg initial = context.getInput();
            if (initial.containsParam("sessionId"))
                input.setParam("sessionId", initial.getStringParam("sessionId", ""));
            if (initial.containsParam("rootSessionId"))
                input.setParam("rootSessionId", initial.getStringParam("rootSessionId", ""));
            if (initial.containsParam("userId"))
                input.setParam("userId", initial.getStringParam("userId", ""));
        }
        return input;
    }

    private void timeoutRemaining(String status) {
        for (String nid : running.keySet()) {
            context.putStatus(nid, status);
        }
        running.clear();
        for (String nid : nodes.keySet()) {
            if (!completed.contains(nid) && !skipped.contains(nid) && !failed.contains(nid)) {
                context.putStatus(nid, status);
            }
        }
        context.putStatus("_workflow", status);
    }

    private void handleNodeFailure(String nid, TLWorkflowNode node) {
        String strategy = node.getOnFailure() != null ? node.getOnFailure() : "fail";
        if ("retry".equals(strategy)) {
            int used = retryCounts.merge(nid, 1, Integer::sum);
            int max = Math.max(0, node.getMaxRetries());
            if (used <= max) {
                // 不入 failed、不动 inDegree，主循环下一轮自然重排该节点
                context.putStatus(nid, "RETRY:" + used + "/" + max);
                return;
            }
            strategy = "retry(exhausted)";
        }
        failed.add(nid);
        context.putStatus(nid, "FAILED:" + strategy);
        if ("skip".equals(strategy)) {
            onNodeDone(nid);
        } else if (node.getFallbackNodeId() != null) {
            cascadeSkip(nid);
            String fb = node.getFallbackNodeId();
            skipped.remove(fb);
            inDegree.put(fb, 0);
            context.putStatus(fb, "FALLBACK_READY");
        }
    }

    // ==================== 条件求值 ====================

    private boolean edgeMatches(TLWorkflowEdge edge, boolean condResult) {
        String c = edge.getCondition();
        if (c == null) return true;
        return condResult ? "true".equals(c) : "false".equals(c);
    }

    private boolean evalCondition(TLWorkflowNode node, TLMsg upstream) {
        Map<String, String> nodeParams = node.getParams();
        String expr = nodeParams != null ? nodeParams.get("expression") : null;
        if (expr == null || expr.isEmpty()) {
            // 无表达式：直接读上游合并结果的 RESULT（|| 路由节点依赖此语义）
            return upstream != null && upstream.parseBoolean(RESULT, false);
        }
        try {
            String[] parts = expr.split("\\s+");
            if (parts.length < 3) return false;
            String path = parts[0], op = parts[1];
            // 引号字面量含空格时把剩余部分拼回
            String valStr = parts.length > 3
                    ? String.join(" ", Arrays.copyOfRange(parts, 2, parts.length)) : parts[2];
            Object actual = context.getValueByPath(path);
            if (actual == null) return false;

            // 字符串模式：引号字面量仅对 == / != 有意义
            boolean strMode = valStr.length() >= 2
                    && ((valStr.startsWith("'") && valStr.endsWith("'"))
                        || (valStr.startsWith("\"") && valStr.endsWith("\"")));
            if (strMode) {
                String b = valStr.substring(1, valStr.length() - 1);
                switch (op) {
                    case "==": return String.valueOf(actual).equals(b);
                    case "!=": return !String.valueOf(actual).equals(b);
                    default:   return false;
                }
            }

            double a = toDouble(actual), b = Double.parseDouble(valStr);
            switch (op) {
                case ">":  return a > b;
                case ">=": return a >= b;
                case "<":  return a < b;
                case "<=": return a <= b;
                case "==": return a == b;
                case "!=": return a != b;
                default:   return false;
            }
        } catch (Exception e) { return false; }
    }

    private double toDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    // ==================== 拓扑排序 ====================

    private List<String> topologicalSort() {
        Map<String, Integer> indeg = new HashMap<>();
        for (String nid : nodes.keySet())
            indeg.put(nid, inEdges.get(nid).size());

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> e : indeg.entrySet())
            if (e.getValue() == 0) queue.offer(e.getKey());

        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String nid = queue.poll();
            order.add(nid);
            for (TLWorkflowEdge edge : outEdges.getOrDefault(nid, Collections.emptyList())) {
                int v = indeg.merge(edge.getTo(), -1, Integer::sum);
                if (v == 0) queue.offer(edge.getTo());
            }
        }
        return order.size() == nodes.size() ? order : null;
    }

    // ==================== 结果构建 ====================

    private TLMsg buildResult(boolean success, String message) {
        TLMsg result = new TLMsg();
        result.setParam(RESULT, success);
        result.setParam("message", message);
        result.setParam("totalNodes", nodes.size());
        result.setParam("completedCount", completed.size());
        result.setParam("skippedCount", skipped.size());
        result.setParam("failedCount", failed.size());

        List<String> completedIds = new ArrayList<>(completed);
        List<String> uncompletedIds = new ArrayList<>();
        for (String nid : nodes.keySet()) {
            if (!completed.contains(nid) && !skipped.contains(nid))
                uncompletedIds.add(nid);
        }
        result.setParam(COMPLETEDMSGLIST, completedIds);
        result.setParam(UNCOMPLETEDMSGLIST, uncompletedIds);
        result.setParam("nodeOutputs", new HashMap<>(context.getNodeOutputs()));
        result.setParam("nodeStatus", new HashMap<>(context.getNodeStatus()));
        result.setParam("nodeDuration", new HashMap<>(context.getNodeDuration()));
        return result;
    }
}
