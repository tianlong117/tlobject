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
        /**
         * 取消仍在执行的节点任务。超时/中断收尾必须调它——只清 running 表不会停掉底层
         * LLM 调用：线程会继续跑完、继续烧 token，结果却再没人取。
         */
        void cancel();
    }

    /** 宿主模块提供的节点执行能力 */
    public interface NodeRunner {
        /** @param node 节点定义
         *  @param nodeInput 引擎收集的节点输入（上游产出 + 工作流初始输入） */
        NodeTask runAgentNode(TLWorkflowNode node, TLMsg nodeInput);
    }

    /**
     * 节点状态变更监听器：宿主模块用它把节点进度发布到 UI。
     * 回调在引擎调度线程，异常会被吞掉——进度是旁路，不能影响工作流执行。
     */
    public interface NodeProgressListener {
        /**
         * @param nodeId     节点 id；"_workflow" 表示工作流整体
         * @param status     RUNNING/DONE/SKIPPED/FAILED:xxx/RETRY:n/m/TRUE/FALSE/FALLBACK_READY 等
         * @param durationMs 耗时（仅节点 DONE 有意义，其余为 0）
         * @param detail     附加说明（节点类型/取消原因等，可为空）
         */
        void onNodeStatus(String nodeId, String status, long durationMs, String detail);
    }

    private final Map<String, TLWorkflowNode> nodes;
    private final Map<String, List<TLWorkflowEdge>> outEdges;
    private final Map<String, List<TLWorkflowEdge>> inEdges;
    private final Map<String, Integer> inDegree;
    private final TLWorkflowContext context;
    private final NodeRunner runner;

    private int maxParallel = 10;
    private long workflowTimeout;

    /** 节点进度监听器（可空；宿主模块按运行实例注入） */
    private volatile NodeProgressListener progressListener;

    private final Set<String> completed = ConcurrentHashMap.newKeySet();
    private final Set<String> skipped = ConcurrentHashMap.newKeySet();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private final Map<String, NodeTask> running = new ConcurrentHashMap<>();
    /** 运行中节点的提交时间戳（nodeDuration 要的是"耗时"，不能拿完成时刻当耗时） */
    private final Map<String, Long> runningStartTs = new ConcurrentHashMap<>();

    /** 节点重试计数（onFailure="retry" 时按节点累积） */
    private final Map<String, Integer> retryCounts = new ConcurrentHashMap<>();

    /** 运行期告警（如字段类型不一致），由宿主模块经 drainWarnings 取走输出 */
    private final List<String> warnings = new ArrayList<>();

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
    public void setProgressListener(NodeProgressListener listener) { this.progressListener = listener; }

    /**
     * 执行工作流，阻塞直到所有节点完成、超时或死锁。
     */
    public TLMsg execute() {
        long startTime = System.currentTimeMillis();
        long deadline = workflowTimeout > 0 ? startTime + workflowTimeout : Long.MAX_VALUE;

        if (topologicalSort() == null) {
            setStatus("_workflow", "CYCLE_DETECTED", 0L, "");
            return buildResult(false, "cycle detected");
        }

        setStatus("_workflow", "RUNNING", 0L, "nodes=" + nodes.size());

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
                            runningStartTs.put(nid, System.currentTimeMillis());
                            running.put(nid, task);
                            setStatus(nid, "RUNNING", 0L, "type=" + node.getType());
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
            cancelRunning("INTERRUPTED");
            setStatus("_workflow", "INTERRUPTED", 0L, "");
        }

        long elapsed = System.currentTimeMillis() - startTime;
        boolean allDone = completed.size() + skipped.size() == nodes.size();
        // 正常收尾才补工作流终态事件；超时/中断已在上面发过终态，不能覆盖
        if ("RUNNING".equals(context.getNodeStatus().get("_workflow"))) {
            emitProgress("_workflow", allDone ? "DONE" : "PARTIAL", elapsed, "");
        }
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
        Long startTs = runningStartTs.remove(nid);
        long durationMs = startTs != null ? System.currentTimeMillis() - startTs : 0L;
        context.putDuration(nid, durationMs);

        if (output != null && output.parseBoolean(RESULT, true)) {
            completed.add(nid);
            setStatus(nid, "DONE", durationMs, "");
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
                setStatus(nid, condResult ? "TRUE" : "FALSE", 0L, "type=CONDITION");
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
                setStatus(nid, "DONE", 0L, "type=" + node.getType());
                completed.add(nid);
                onNodeDone(nid);
                break;

            default:
                completed.add(nid);
                setStatus(nid, "DONE", 0L, "type=" + node.getType());
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
        setStatus(nodeId, "SKIPPED", 0L, "");
        for (TLWorkflowEdge edge : outEdges.getOrDefault(nodeId, Collections.emptyList())) {
            cascadeSkip(edge.getTo());
        }
    }

    private TLMsg collectUpstreamInput(String nodeId) {
        List<TLWorkflowEdge> incoming = inEdges.get(nodeId);
        if (incoming == null || incoming.isEmpty()) return null;
        TLMsg merged = new TLMsg();
        List<String> upstreamResponses = new ArrayList<>();
        Map<String, TLStateReducer> fieldReducers = fieldReducersOf(nodeId);
        for (TLWorkflowEdge edge : incoming) {
            TLMsg up = context.getOutput(edge.getFrom());
            if (up != null) {
                mergeUpstreamArgs(merged, up.getArgs(), fieldReducers);
                // 收集上游正文（args 里多个上游的 aiResponse 会互相覆盖丢失）。
                // 汇聚点产物自带 upstreamResponses，必须整段收纳而不是丢弃——否则链式汇聚
                // (A && B) as m1 -> (m1 && C) as m2 里 m1 收集的正文在下游就没了；
                // 也不能再叠加它自己的 aiResponse：那等于把已收录的某一段重复加一次
                //（菱形 A→m1、A→C 场景会出现两份 A）
                Object collected = up.getParam("upstreamResponses");
                if (collected instanceof List) {
                    for (Object o : (List<?>) collected) {
                        if (o != null) upstreamResponses.add(o.toString());
                    }
                } else {
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

    /**
     * 框架独占字段：由 collectUpstreamInput 的正文收集逻辑在 reducer 之后无条件写入，
     * 声明合并策略没有任何效果（不影响运行，但会误导配置者）。见 TLAgentWorkflow 加载告警。
     */
    public static final Set<String> RESERVED_MERGE_FIELDS =
            Collections.singleton("upstreamResponses");

    /** 取节点的字段级合并策略（无声明返回 null，调用方走默认） */
    private Map<String, TLStateReducer> fieldReducersOf(String nodeId) {
        TLWorkflowNode node = nodes.get(nodeId);
        if (node == null) return null;
        Map<String, TLStateReducer> reducers = node.getFieldReducers();
        return reducers.isEmpty() ? null : reducers;
    }

    /**
     * 按字段合并上游 args。已声明策略的字段用它；其余交给 {@link TLReducers#ADAPTIVE}——
     * 列表类字段追加不丢数据，标量保持覆盖（与改造前行为一致）。
     *
     * <p>{@code upstreamResponses} 不在这里合并（它由本方法下方的正文收集逻辑写回），
     * 但多个汇聚点串联时会经上游 args 带过来，ADAPTIVE 会让它按列表追加而不是互相覆盖。
     */
    private void mergeUpstreamArgs(TLMsg merged, Map<String, ?> incoming, Map<String, TLStateReducer> fieldReducers) {
        if (incoming == null || incoming.isEmpty()) return;
        for (Map.Entry<String, ?> entry : incoming.entrySet()) {
            String field = entry.getKey();
            TLStateReducer reducer = fieldReducers != null ? fieldReducers.get(field) : null;
            Object existing = merged.getParam(field);
            if (reducer == null) {
                warnTypeMismatch(field, existing, entry.getValue());
                reducer = TLReducers.ADAPTIVE;
            }
            Object value = reducer.reduce(existing, entry.getValue());
            if (value == null) merged.removeParam(field);
            else merged.setParam(field, value);
        }
    }

    /**
     * 同名字段在各上游之间应保持同类型（契约，见 README「上游类型契约」）。
     * 现有值与新来值一边是集合、另一边不是时，说明某上游违反了契约——ADAPTIVE 按
     * 「谁的类型」决定的合并方式会让人困惑（标量→列表是覆盖、列表→标量是追加，
     * 同一对类型换个顺序结果就不同），所以这里记录下来，但不改变合并行为：
     * 契约该在上游修，框架不替上游做类型调和。
     *
     * <p>引擎不打日志（它是纯算法类，没有 putLog），告警收集起来由宿主模块输出——
     * 见 {@link #drainWarnings()}。</p>
     */
    private void warnTypeMismatch(String field, Object existing, Object incoming) {
        if (existing == null || incoming == null) return;
        if (typeBucket(existing).equals(typeBucket(incoming))) return;   // 同类型：正常，不打扰
        warnings.add("字段 '" + field + "' 类型不一致：已有 " + existing.getClass().getSimpleName()
                + "，新来 " + incoming.getClass().getSimpleName()
                + "。同名字段应由上游节点保证同类型，请检查节点产出");
    }

    /**
     * 类型归类。Map 与 Collection 算不同类——两者都不是标量，但契约要求的是同类型，
     * Map 对 List 同样是不一致（ADAPTIVE 也会给出让人困惑的结果）。
     */
    private static String typeBucket(Object v) {
        if (v instanceof Map) return "map";
        if (v instanceof Collection) return "collection";
        return "scalar";
    }

    /** 取走本次执行收集到的告警（取后清空，由宿主模块负责输出） */
    public java.util.List<String> drainWarnings() {
        if (warnings.isEmpty()) return java.util.Collections.emptyList();
        java.util.List<String> out = new ArrayList<>(warnings);
        warnings.clear();
        return out;
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
            if (initial.containsSystemParam("sessionId"))
                input.setSystemParam("sessionId", initial.getSystemParam("sessionId", ""));
            if (initial.containsSystemParam("rootSessionId"))
                input.setSystemParam("rootSessionId", initial.getSystemParam("rootSessionId", ""));
            if (initial.containsSystemParam("userId"))
                input.setSystemParam("userId", initial.getSystemParam("userId", ""));
            if (initial.containsSystemParam("roundId"))
                input.setSystemParam("roundId", initial.getSystemParam("roundId", ""));
        }
        return input;
    }

    /**
     * 收尾仍在跑的节点：先落状态，再取消任务（{@link NodeTask#cancel()}）。
     * 取消失败不能挡住收尾——工作流已经超时/被中断，引擎必须能返回。
     */
    private void cancelRunning(String status) {
        for (Map.Entry<String, NodeTask> e : running.entrySet()) {
            setStatus(e.getKey(), status, 0L, "cancelled");
            try {
                e.getValue().cancel();
            } catch (Throwable t) {
                // 取消是尽力而为：异常留给宿主日志（引擎是纯算法类，不打日志）
                warnings.add("取消节点 '" + e.getKey() + "' 失败: " + t);
            }
        }
        running.clear();
        runningStartTs.clear();
    }

    private void timeoutRemaining(String status) {
        cancelRunning(status);
        for (String nid : nodes.keySet()) {
            if (!completed.contains(nid) && !skipped.contains(nid) && !failed.contains(nid)
                    && !status.equals(context.getNodeStatus().get(nid))) {   // 运行中节点已由 cancelRunning 落状态
                setStatus(nid, status, 0L, "timeout");
            }
        }
        setStatus("_workflow", status, 0L, "");
    }

    /** 状态写入与进度通知的唯一入口：保证 context 与监听器不会漏掉任何一次状态变更。 */
    private void setStatus(String nodeId, String status, long durationMs, String detail) {
        context.putStatus(nodeId, status);
        emitProgress(nodeId, status, durationMs, detail);
    }

    /** 进度是旁路：监听器异常/慢回调不能影响工作流执行。 */
    private void emitProgress(String nodeId, String status, long durationMs, String detail) {
        NodeProgressListener l = progressListener;
        if (l == null) return;
        try {
            l.onNodeStatus(nodeId, status, durationMs, detail);
        } catch (Throwable ignored) {
            // 进度推送失败不影响工作流
        }
    }

    private void handleNodeFailure(String nid, TLWorkflowNode node) {
        String strategy = node.getOnFailure() != null ? node.getOnFailure() : "fail";
        if ("retry".equals(strategy)) {
            int used = retryCounts.merge(nid, 1, Integer::sum);
            int max = Math.max(0, node.getMaxRetries());
            if (used <= max) {
                // 不入 failed、不动 inDegree，主循环下一轮自然重排该节点
                setStatus(nid, "RETRY:" + used + "/" + max, 0L, "");
                return;
            }
            strategy = "retry(exhausted)";
        }
        failed.add(nid);
        setStatus(nid, "FAILED:" + strategy, 0L, "");
        if ("skip".equals(strategy)) {
            onNodeDone(nid);
        } else if (node.getFallbackNodeId() != null) {
            cascadeSkip(nid);
            String fb = node.getFallbackNodeId();
            skipped.remove(fb);
            inDegree.put(fb, 0);
            setStatus(fb, "FALLBACK_READY", 0L, "");
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
